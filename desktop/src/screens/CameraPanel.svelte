<script lang="ts">
  import { onDestroy } from "svelte";
  import Button from "../lib/components/Button.svelte";
  import { api, errorCode, onAudio, onFrame, type CameraChoice, type PhotoView } from "../lib/api";
  import { dateTime } from "../lib/format";
  import { locale, t, type MessageKey } from "../lib/i18n";

  interface Props {
    deviceId: string;
    active: boolean;
    revision: number;
  }

  let { deviceId, active, revision }: Props = $props();

  const STREAM_FPS = 3;
  const STREAM_EDGE = 640;
  const STREAM_SECONDS = 120;

  let photos: PhotoView[] = $state([]);
  let thumbs: Record<string, string> = $state({});
  let opened: string | null = $state(null);
  let openedUrl = $state("");
  let streaming = $state(false);
  let frameUrl = $state("");
  let listening = $state(false);
  let talking = $state(false);
  let busy = $state(false);
  let feedback = $state("");
  const cleanups: (() => void)[] = [];

  // Microphone capture (controller voice → phone) for the two-way intercom.
  let micCtx: AudioContext | undefined;
  let micStream: MediaStream | undefined;
  let micNode: ScriptProcessorNode | undefined;
  let micSource: MediaStreamAudioSourceNode | undefined;
  let outSeq = 0;

  const INTERCOM_SECONDS = 900;

  // Web Audio playback of incoming PCM chunks, scheduled back to back.
  let audioCtx: AudioContext | undefined;
  let nextPlayTime = 0;

  function playChunk(base64: string, sampleRate: number) {
    const ctx = audioCtx ?? (audioCtx = new AudioContext());
    const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
    const samples = new Int16Array(
      bytes.buffer,
      bytes.byteOffset,
      Math.floor(bytes.byteLength / 2),
    );
    if (samples.length === 0) return;
    const buffer = ctx.createBuffer(1, samples.length, sampleRate);
    const channel = buffer.getChannelData(0);
    for (let i = 0; i < samples.length; i++) channel[i] = (samples[i] ?? 0) / 0x8000;
    const source = ctx.createBufferSource();
    source.buffer = buffer;
    source.connect(ctx.destination);
    const start = Math.max(nextPlayTime, ctx.currentTime);
    source.start(start);
    nextPlayTime = start + buffer.duration;
  }

  onFrame((frame) => {
    // Ignore frames for other devices or frames arriving after we stopped.
    if (frame.deviceId === deviceId && streaming) {
      frameUrl = `data:image/jpeg;base64,${frame.jpeg}`;
    }
  }).then((fn) => cleanups.push(fn));

  onAudio((chunk) => {
    // Play the phone's microphone while listening or during a two-way intercom.
    if (chunk.deviceId === deviceId && (listening || talking))
      playChunk(chunk.pcm, chunk.sampleRate);
  }).then((fn) => cleanups.push(fn));

  onDestroy(() => {
    cleanups.forEach((fn) => fn());
    void audioCtx?.close();
    stopMic();
    if (streaming) void api.sendCommand(deviceId, stopRequest()).catch(() => undefined);
    if (listening) void api.sendCommand(deviceId, audioRequest(false)).catch(() => undefined);
    if (talking) void api.sendCommand(deviceId, intercomRequest(false)).catch(() => undefined);
  });

  function intercomRequest(enabled: boolean) {
    return { kind: "intercom", enabled, durationSeconds: INTERCOM_SECONDS } as const;
  }

  function encodePcm(samples: Float32Array): string {
    const pcm = new Int16Array(samples.length);
    for (let i = 0; i < samples.length; i++) {
      const s = Math.max(-1, Math.min(1, samples[i] ?? 0));
      pcm[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
    }
    const bytes = new Uint8Array(pcm.buffer);
    let binary = "";
    for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i] ?? 0);
    return btoa(binary);
  }

  async function startMic(): Promise<boolean> {
    try {
      micStream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true },
      });
    } catch {
      return false;
    }
    micCtx = new AudioContext();
    micSource = micCtx.createMediaStreamSource(micStream);
    micNode = micCtx.createScriptProcessor(4096, 1, 1);
    const rate = micCtx.sampleRate;
    micNode.onaudioprocess = (event) => {
      if (!talking) return;
      const input = event.inputBuffer.getChannelData(0);
      void api.audioPlay(deviceId, outSeq++, encodePcm(input), rate).catch(() => undefined);
    };
    micSource.connect(micNode);
    micNode.connect(micCtx.destination);
    return true;
  }

  function stopMic() {
    micNode?.disconnect();
    micSource?.disconnect();
    micStream?.getTracks().forEach((t) => t.stop());
    void micCtx?.close();
    micNode = undefined;
    micSource = undefined;
    micStream = undefined;
    micCtx = undefined;
  }

  async function toggleIntercom() {
    if (talking) {
      talking = false;
      stopMic();
      nextPlayTime = 0;
      await run(() => api.sendCommand(deviceId, intercomRequest(false)));
      return;
    }
    await (audioCtx ?? (audioCtx = new AudioContext())).resume().catch(() => undefined);
    if (!(await startMic())) {
      feedback = t("camera.micDenied");
      return;
    }
    nextPlayTime = 0;
    outSeq = 0;
    await run(() => api.sendCommand(deviceId, intercomRequest(true)));
    talking = true;
  }

  function audioRequest(enabled: boolean) {
    return { kind: "audio", enabled, durationSeconds: STREAM_SECONDS } as const;
  }

  async function toggleListen() {
    if (listening) {
      listening = false;
      nextPlayTime = 0;
      await run(() => api.sendCommand(deviceId, audioRequest(false)));
      return;
    }
    // Resume the audio context on a user gesture (autoplay policy).
    await (audioCtx ?? (audioCtx = new AudioContext())).resume().catch(() => undefined);
    nextPlayTime = 0;
    await run(() => api.sendCommand(deviceId, audioRequest(true)));
    listening = true;
  }

  function stopRequest() {
    return {
      kind: "stream",
      enabled: false,
      camera: "unspecified",
      fps: STREAM_FPS,
      edgePx: STREAM_EDGE,
      durationSeconds: STREAM_SECONDS,
    } as const;
  }

  $effect(() => {
    void revision;
    if (!deviceId) return;
    api
      .photos(deviceId)
      .then((list) => {
        photos = list;
        for (const p of list.slice(0, 24)) {
          if (!(p.id in thumbs)) {
            api
              .photo(p.id)
              .then((url) => (thumbs = { ...thumbs, [p.id]: url }))
              .catch(() => undefined);
          }
        }
      })
      .catch(() => (photos = []));
  });

  async function run(action: () => Promise<unknown>) {
    busy = true;
    feedback = "";
    try {
      await action();
    } catch (e) {
      feedback = t(`error.${errorCode(e)}`);
    } finally {
      busy = false;
    }
  }

  function capture(camera: CameraChoice) {
    return run(() => api.sendCommand(deviceId, { kind: "capturePhoto", camera }));
  }

  async function toggleStream(camera: CameraChoice) {
    if (streaming) {
      streaming = false;
      frameUrl = "";
      await run(() => api.sendCommand(deviceId, stopRequest()));
      return;
    }
    await run(() =>
      api.sendCommand(deviceId, {
        kind: "stream",
        enabled: true,
        camera,
        fps: STREAM_FPS,
        edgePx: STREAM_EDGE,
        durationSeconds: STREAM_SECONDS,
      }),
    );
    streaming = true;
  }

  async function open(id: string) {
    opened = id;
    openedUrl = thumbs[id] ?? (await api.photo(id).catch(() => ""));
  }

  function triggerLabel(trigger: string): string {
    const key = `camera.trigger.${trigger}` as MessageKey;
    const value = t(key);
    return value === key ? trigger : value;
  }
</script>

<section aria-labelledby="camera-title">
  <h2 id="camera-title">{t("camera.title")}</h2>
  <p class="note">{t("camera.indicator")}</p>

  <div class="controls">
    <Button disabled={!active || busy} onclick={() => capture("back")}>
      {t("camera.capture", { camera: t("camera.back") })}
    </Button>
    <Button disabled={!active || busy} onclick={() => capture("front")}>
      {t("camera.capture", { camera: t("camera.front") })}
    </Button>
    {#if streaming}
      <Button variant="danger" disabled={busy} onclick={() => toggleStream("back")}>
        {t("camera.streamStop")}
      </Button>
    {:else}
      <Button variant="primary" disabled={!active || busy} onclick={() => toggleStream("back")}>
        {t("camera.streamStart", { camera: t("camera.back") })}
      </Button>
    {/if}
    {#if listening}
      <Button variant="danger" disabled={busy} onclick={toggleListen}>
        {t("camera.listenStop")}
      </Button>
    {:else}
      <Button disabled={!active || busy || talking} onclick={toggleListen}>
        {t("camera.listenStart")}
      </Button>
    {/if}
    {#if talking}
      <Button variant="danger" disabled={busy} onclick={toggleIntercom}>
        {t("camera.talkStop")}
      </Button>
    {:else}
      <Button disabled={!active || busy || listening} onclick={toggleIntercom}>
        {t("camera.talkStart")}
      </Button>
    {/if}
  </div>
  {#if feedback}<p class="error" role="status">{feedback}</p>{/if}

  {#if streaming}
    <div class="live">
      {#if frameUrl}
        <img src={frameUrl} alt={t("camera.live", { fps: STREAM_FPS })} />
        <span class="badge">● {t("camera.live", { fps: STREAM_FPS })}</span>
      {:else}
        <p class="placeholder">{t("camera.connecting")}</p>
      {/if}
    </div>
  {/if}
  {#if listening}
    <p class="listening" aria-live="polite">● {t("camera.listening")}</p>
  {/if}
  {#if talking}
    <p class="listening" aria-live="polite">● {t("camera.talking")}</p>
  {/if}

  <h3>{t("camera.photos")}</h3>
  {#if photos.length === 0}
    <p class="muted">{t("camera.noPhotos")}</p>
  {:else}
    <ul class="gallery">
      {#each photos.slice(0, 24) as p (p.id)}
        <li>
          <button
            class="thumb"
            onclick={() => open(p.id)}
            aria-label={dateTime(locale, p.capturedMs)}
          >
            {#if thumbs[p.id]}
              <img src={thumbs[p.id]} alt="" loading="lazy" />
            {:else}
              <span class="loading"></span>
            {/if}
          </button>
          <span class="caption">{triggerLabel(p.trigger)}</span>
        </li>
      {/each}
    </ul>
  {/if}
</section>

{#if opened}
  <!-- svelte-ignore a11y_click_events_have_key_events -->
  <div
    class="lightbox"
    onclick={() => (opened = null)}
    role="dialog"
    aria-modal="true"
    tabindex="-1"
  >
    {#if openedUrl}<img src={openedUrl} alt="" />{/if}
    <Button onclick={() => (opened = null)}>{t("camera.close")}</Button>
  </div>
{/if}

<style>
  h2 {
    margin: var(--space-md) 0 var(--space-xs);
    font-size: var(--type-label-size);
    font-weight: var(--type-label-weight);
    color: var(--color-text-secondary);
  }

  h3 {
    margin: var(--space-md) 0 var(--space-xs);
    font-size: var(--type-caption-size);
    color: var(--color-text-secondary);
  }

  .note {
    margin: 0 0 var(--space-sm);
    font-size: var(--type-caption-size);
    color: var(--color-text-secondary);
  }

  .controls {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-xs);
  }

  .live {
    position: relative;
    margin-top: var(--space-sm);
    border-radius: var(--radius-md);
    overflow: hidden;
    border: var(--border-width) solid var(--color-border-active);
    background: var(--color-surface);
    min-height: 180px;
    display: grid;
    place-items: center;
  }

  .live img {
    width: 100%;
    display: block;
  }

  .badge {
    position: absolute;
    top: var(--space-xs);
    left: var(--space-xs);
    padding: 2px var(--space-xs);
    border-radius: var(--radius-sm);
    background: rgb(0 0 0 / 55%);
    color: var(--color-danger);
    font-size: var(--type-caption-size);
  }

  .placeholder,
  .muted {
    margin: 0;
    color: var(--color-text-secondary);
    font-size: var(--type-label-size);
  }

  .gallery {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(84px, 1fr));
    gap: var(--space-xs);
  }

  .thumb {
    width: 100%;
    aspect-ratio: 3 / 4;
    padding: 0;
    border: var(--border-width) solid var(--color-border);
    border-radius: var(--radius-sm);
    overflow: hidden;
    background: var(--color-surface);
    cursor: pointer;
  }

  .thumb img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }

  .loading {
    display: block;
    width: 100%;
    height: 100%;
    background: var(--color-surface-raised);
  }

  .caption {
    display: block;
    font-size: var(--type-caption-size);
    color: var(--color-text-secondary);
  }

  .lightbox {
    position: fixed;
    inset: 0;
    /* Above Leaflet's tile panes and controls (which reach z-index ~1000), so an
       enlarged photo is never overlapped by the map behind it. */
    z-index: 2000;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: var(--space-md);
    padding: var(--space-xl);
    background: rgb(0 0 0 / 80%);
  }

  .lightbox img {
    max-width: 90vw;
    max-height: 80vh;
    border-radius: var(--radius-md);
  }

  .error {
    color: var(--color-danger);
    font-size: var(--type-label-size);
  }

  .listening {
    margin: var(--space-xs) 0 0;
    color: var(--color-danger);
    font-size: var(--type-label-size);
  }
</style>
