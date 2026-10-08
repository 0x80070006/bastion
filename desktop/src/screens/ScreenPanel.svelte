<script lang="ts">
  import { onDestroy } from "svelte";
  import Button from "../lib/components/Button.svelte";
  import {
    api,
    errorCode,
    onScreen,
    type GlobalInputAction,
    type RemoteInputRequest,
  } from "../lib/api";
  import { t } from "../lib/i18n";

  interface Props {
    deviceId: string;
    active: boolean;
  }

  let { deviceId, active }: Props = $props();

  const SCREEN_FPS = 2;
  const SCREEN_EDGE = 1600;
  const SCREEN_SECONDS = 900;
  // A pointer that travels less than this (fraction of the frame) is a tap, not a swipe.
  const TAP_SLOP = 0.02;
  const LONG_PRESS_MS = 500;

  let controlling = $state(false);
  let frameUrl = $state("");
  let frameW = $state(0);
  let frameH = $state(0);
  let locked = $state(false);
  let stalled = $state(false);
  let busy = $state(false);
  let feedback = $state("");
  let typed = $state("");
  let stallTimer: ReturnType<typeof setTimeout> | undefined;
  const STALL_MS = 6000;
  let img: HTMLImageElement | undefined = $state();
  let down: { x: number; y: number; at: number } | null = null;
  const cleanups: (() => void)[] = [];

  onScreen((frame) => {
    if (frame.deviceId === deviceId && controlling) {
      frameUrl = `data:image/jpeg;base64,${frame.jpeg}`;
      frameW = frame.width;
      frameH = frame.height;
      locked = frame.locked;
      stalled = false;
      clearTimeout(stallTimer);
    }
  }).then((fn) => cleanups.push(fn));

  onDestroy(() => {
    cleanups.forEach((fn) => fn());
    clearTimeout(stallTimer);
    if (controlling) void api.sendCommand(deviceId, screenRequest(false)).catch(() => undefined);
  });

  function screenRequest(enabled: boolean) {
    return {
      kind: "screen",
      enabled,
      fps: SCREEN_FPS,
      edgePx: SCREEN_EDGE,
      durationSeconds: SCREEN_SECONDS,
      keepAwake: true,
    } as const;
  }

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

  async function toggleControl() {
    if (controlling) {
      controlling = false;
      stalled = false;
      clearTimeout(stallTimer);
      frameUrl = "";
      await run(() => api.sendCommand(deviceId, screenRequest(false)));
      return;
    }
    await run(() => api.sendCommand(deviceId, screenRequest(true)));
    controlling = true;
    stalled = false;
    // If no frame arrives soon, the phone is likely on an old build, the accessibility service is
    // off, or it is locked — tell the operator instead of showing an empty panel.
    clearTimeout(stallTimer);
    stallTimer = setTimeout(() => {
      if (controlling && !frameUrl) stalled = true;
    }, STALL_MS);
  }

  function send(input: RemoteInputRequest) {
    // Fire-and-forget: a dropped tap is not worth surfacing an error for.
    void api.remoteInput(deviceId, input).catch(() => undefined);
  }

  // Normalised 0..1 point of a pointer event within the displayed frame.
  function point(event: PointerEvent): { x: number; y: number } {
    if (!img) return { x: 0, y: 0 };
    const rect = img.getBoundingClientRect();
    const x = rect.width > 0 ? (event.clientX - rect.left) / rect.width : 0;
    const y = rect.height > 0 ? (event.clientY - rect.top) / rect.height : 0;
    return { x: Math.min(1, Math.max(0, x)), y: Math.min(1, Math.max(0, y)) };
  }

  function onPointerDown(event: PointerEvent) {
    if (!controlling || locked) return;
    const p = point(event);
    down = { x: p.x, y: p.y, at: Date.now() };
    img?.setPointerCapture(event.pointerId);
  }

  function onPointerUp(event: PointerEvent) {
    if (!controlling || locked || !down) return;
    const start = down;
    down = null;
    const p = point(event);
    const dx = p.x - start.x;
    const dy = p.y - start.y;
    const moved = Math.hypot(dx, dy);
    const dt = Date.now() - start.at;
    if (moved < TAP_SLOP) {
      send({ kind: "tap", x: p.x, y: p.y, longPress: dt >= LONG_PRESS_MS });
    } else {
      send({
        kind: "swipe",
        x1: start.x,
        y1: start.y,
        x2: p.x,
        y2: p.y,
        durationMs: Math.min(3000, Math.max(50, dt)),
      });
    }
  }

  function global(action: GlobalInputAction) {
    send({ kind: "global", action });
  }

  function sendText() {
    const text = typed;
    if (!text) return;
    typed = "";
    send({ kind: "text", text, submit: true });
  }
</script>

<section aria-labelledby="remote-title">
  <h2 id="remote-title">{t("remote.title")}</h2>
  <p class="note">{t("remote.indicator")}</p>

  <div class="controls">
    {#if controlling}
      <Button variant="danger" disabled={busy} onclick={toggleControl}>{t("remote.stop")}</Button>
    {:else}
      <Button variant="primary" disabled={!active || busy} onclick={toggleControl}>
        {t("remote.start")}
      </Button>
    {/if}
  </div>
  {#if feedback}<p class="error" role="status">{feedback}</p>{/if}

  {#if controlling}
    <div class="stage" style={frameW && frameH ? `aspect-ratio:${frameW}/${frameH}` : ""}>
      {#if frameUrl}
        <img
          bind:this={img}
          src={frameUrl}
          alt={t("remote.title")}
          draggable="false"
          onpointerdown={onPointerDown}
          onpointerup={onPointerUp}
        />
        {#if locked}<div class="lockedOverlay">{t("remote.locked")}</div>{/if}
        <span class="badge">● {t("remote.live", { fps: SCREEN_FPS })}</span>
      {:else if stalled}
        <p class="placeholder stalled">{t("remote.stalled")}</p>
      {:else}
        <p class="placeholder">{t("remote.connecting")}</p>
      {/if}
    </div>
    <p class="hint">{t("remote.hint")}</p>

    <div class="nav">
      <Button variant="ghost" disabled={locked} onclick={() => global("back")}>
        {t("remote.back")}
      </Button>
      <Button variant="ghost" disabled={locked} onclick={() => global("home")}>
        {t("remote.home")}
      </Button>
      <Button variant="ghost" disabled={locked} onclick={() => global("recents")}>
        {t("remote.recents")}
      </Button>
      <Button variant="ghost" disabled={locked} onclick={() => global("notifications")}>
        {t("remote.notifications")}
      </Button>
      <Button variant="ghost" onclick={() => global("wake")}>{t("remote.wake")}</Button>
      <Button variant="ghost" onclick={() => global("lock")}>{t("remote.lock")}</Button>
    </div>

    <form
      class="typing"
      onsubmit={(e) => {
        e.preventDefault();
        sendText();
      }}
    >
      <input
        class="text-field"
        placeholder={t("remote.text")}
        bind:value={typed}
        maxlength="2048"
        disabled={locked}
      />
      <Button type="submit" disabled={locked || !typed}>{t("remote.send")}</Button>
    </form>
  {/if}
</section>

<style>
  h2 {
    margin: var(--space-md) 0 var(--space-xs);
    font-size: var(--type-label-size);
    font-weight: var(--type-label-weight);
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

  .stage {
    position: relative;
    margin-top: var(--space-sm);
    border-radius: var(--radius-md);
    overflow: hidden;
    border: var(--border-width) solid var(--color-border-active);
    background: var(--color-surface);
    min-height: 180px;
    max-height: 70vh;
    display: grid;
    place-items: center;
    margin-inline: auto;
  }

  .stage img {
    width: 100%;
    height: 100%;
    object-fit: contain;
    display: block;
    touch-action: none;
    cursor: crosshair;
    -webkit-user-select: none;
    user-select: none;
  }

  .lockedOverlay {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    padding: var(--space-lg);
    text-align: center;
    background: rgb(0 0 0 / 55%);
    color: var(--color-warning);
    font-size: var(--type-label-size);
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

  .placeholder {
    margin: 0;
    padding: var(--space-lg);
    text-align: center;
    color: var(--color-text-secondary);
    font-size: var(--type-label-size);
  }

  .stalled {
    color: var(--color-warning);
  }

  .hint {
    margin: var(--space-xs) 0 0;
    font-size: var(--type-caption-size);
    color: var(--color-text-secondary);
  }

  .nav {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-xs);
    margin-top: var(--space-sm);
  }

  .typing {
    display: flex;
    gap: var(--space-xs);
    margin-top: var(--space-sm);
  }

  .text-field {
    flex: 1;
    min-height: 32px;
    padding: 0 var(--space-xs);
    border-radius: var(--radius-sm);
    border: var(--border-width) solid var(--color-border-active);
    background: var(--color-background);
    color: var(--color-text-primary);
    font: inherit;
  }

  .error {
    color: var(--color-danger);
    font-size: var(--type-label-size);
  }
</style>
