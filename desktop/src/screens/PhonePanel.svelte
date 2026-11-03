<script lang="ts">
  import { onDestroy } from "svelte";
  import Button from "../lib/components/Button.svelte";
  import {
    api,
    errorCode,
    onCall,
    onContacts,
    type CallAction,
    type CallState,
    type PhoneContact,
  } from "../lib/api";
  import { createIntercom } from "../lib/intercom";
  import { t, type MessageKey } from "../lib/i18n";

  interface Props {
    deviceId: string;
    active: boolean;
  }

  let { deviceId, active }: Props = $props();

  // No page after this long means the phone is offline, locked down or on an old build.
  const STALL_MS = 8000;

  const key = (value: string) => value as MessageKey;

  let query = $state("");
  let asked = "";
  let contacts = $state<PhoneContact[]>([]);
  let total = $state(0);
  let loaded = $state(false);
  let loading = $state(false);
  let stalled = $state(false);
  let call = $state<CallState | null>(null);
  let dial = $state("");
  let feedback = $state("");
  let stallTimer: ReturnType<typeof setTimeout> | undefined;
  const cleanups: (() => void)[] = [];

  onContacts((page) => {
    if (page.deviceId !== deviceId || page.query !== asked) return;
    clearTimeout(stallTimer);
    stalled = false;
    loading = false;
    loaded = true;
    total = page.total;
    contacts = page.offset === 0 ? page.contacts : [...contacts, ...page.contacts];
  }).then((fn) => cleanups.push(fn));

  onCall((state) => {
    if (state.deviceId !== deviceId) return;
    call = state;
    feedback = state.error ? t(key(`phone.err.${state.error}`)) : "";
  }).then((fn) => cleanups.push(fn));

  // "Sound on the PC": the phone goes on speaker, its microphone picks the caller up and the PC
  // plays it, while the PC microphone plays on the phone. Closes by itself when the call ends.
  const intercom = createIntercom(
    () => deviceId,
    () => (feedback = t("phone.pcAudioSilent")),
  );
  let pcAudio = $state(false);
  let pcAudioLive = $state(false);
  let pcAudioBusy = $state(false);
  let sawCall = false;

  const AUDIO_ERRORS: Record<string, MessageKey> = {
    pc_mic_denied: "phone.pcAudioDenied",
    pc_mic_missing: "phone.pcAudioNoMic",
    pc_audio_error: "phone.pcAudioError",
    mic_permission_denied: "phone.pcAudioPhoneMic",
    mic_unavailable: "phone.pcAudioPhoneBusy",
    send_failed: "phone.pcAudioSend",
    timeout: "phone.pcAudioNoAnswer",
  };

  async function togglePcAudio() {
    feedback = "";
    if (pcAudio) {
      pcAudio = false;
      await closePcAudio();
      return;
    }
    pcAudio = true;
    await intercom.prepare();
    await openPcAudio();
  }

  async function openPcAudio() {
    if (pcAudioLive || pcAudioBusy) return;
    pcAudioBusy = true;
    try {
      if (inCall && !call?.speaker) act("speakerOn");
      const result = await intercom.start();
      if (result.ok) {
        pcAudioLive = true;
      } else {
        pcAudio = false;
        const key = AUDIO_ERRORS[result.reason];
        feedback = key ? t(key) : t("phone.pcAudioFailed", { reason: result.reason });
      }
    } finally {
      pcAudioBusy = false;
    }
  }

  async function closePcAudio() {
    pcAudioLive = false;
    await intercom.stop();
  }

  onDestroy(() => {
    cleanups.forEach((fn) => fn());
    clearTimeout(stallTimer);
    void intercom.stop();
    // Contacts are personal data: drop them as soon as the panel closes.
    contacts = [];
  });

  async function send(request: Parameters<typeof api.sendCommand>[1]) {
    feedback = "";
    try {
      await api.sendCommand(deviceId, request);
      return true;
    } catch (e) {
      feedback = t(key(`error.${errorCode(e)}`));
      return false;
    }
  }

  async function fetchPage(offset: number) {
    loading = true;
    stalled = false;
    asked = query.trim();
    clearTimeout(stallTimer);
    if (!(await send({ kind: "contacts", query: asked, offset }))) {
      loading = false;
      return;
    }
    stallTimer = setTimeout(() => {
      if (loading) {
        loading = false;
        stalled = true;
      }
    }, STALL_MS);
  }

  function search() {
    contacts = [];
    total = 0;
    void fetchPage(0);
  }

  function act(action: CallAction, number = "") {
    void send({ kind: "call", action, number });
  }

  function dialNumber(number: string) {
    act("dial", number.replace(/[\s().-]/g, ""));
  }

  const phase = $derived(call?.state ?? "idle");
  const inCall = $derived(phase !== "idle");

  // End of a call closes the PC audio and unticks the box, so the microphone never stays open.
  $effect(() => {
    if (phase !== "idle") {
      sawCall = true;
    } else if (sawCall) {
      sawCall = false;
      if (pcAudio || pcAudioLive) {
        pcAudio = false;
        void closePcAudio();
      }
    }
  });
</script>

<section aria-labelledby="phone-title">
  <h2 id="phone-title">{t("phone.title")}</h2>
  <p class="note">{t("phone.note")}</p>

  <div class="callbar" class:live={inCall} role="status" aria-live="polite">
    <span class="led" class:on={inCall} aria-hidden="true"></span>
    <span class="state">{t(key(`phone.state.${phase}`))}</span>
    {#if inCall}
      <span class="who">
        {call?.name || t("phone.unknown")}
        <span class="mono">{call?.number}</span>
      </span>
    {/if}
    <span class="spacer"></span>
    {#if phase === "ringing"}
      <Button variant="primary" disabled={!active} onclick={() => act("answer")}>
        {t("phone.answer")}
      </Button>
    {/if}
    {#if inCall}
      <Button
        variant="ghost"
        disabled={!active}
        onclick={() => act(call?.speaker ? "speakerOff" : "speakerOn")}
      >
        {call?.speaker ? t("phone.speakerOff") : t("phone.speakerOn")}
      </Button>
      <Button variant="danger" disabled={!active} onclick={() => act("hangup")}>
        {t("phone.hangup")}
      </Button>
    {/if}
  </div>

  <label class="pcaudio">
    <input
      type="checkbox"
      checked={pcAudio}
      onchange={togglePcAudio}
      disabled={!active || pcAudioBusy}
    />
    <span>{t("phone.pcAudio")}</span>
    {#if pcAudioLive}<span class="live mono">{t("phone.pcAudioLive")}</span>{/if}
  </label>
  <p class="hint">{t("phone.pcAudioHint")}</p>

  <form
    class="row"
    onsubmit={(e) => {
      e.preventDefault();
      if (dial.trim()) dialNumber(dial.trim());
    }}
  >
    <input
      class="field mono"
      inputmode="tel"
      placeholder={t("phone.dialer")}
      aria-label={t("phone.dialer")}
      bind:value={dial}
      maxlength="40"
    />
    <Button type="submit" variant="primary" disabled={!active || inCall || !dial.trim()}>
      {t("phone.dial")}
    </Button>
  </form>

  {#if feedback}<p class="error" role="status">{feedback}</p>{/if}

  <form
    class="row"
    onsubmit={(e) => {
      e.preventDefault();
      search();
    }}
  >
    <input
      class="field"
      type="search"
      placeholder={t("phone.search")}
      aria-label={t("phone.search")}
      bind:value={query}
      maxlength="64"
    />
    <Button type="submit" disabled={!active || loading}>
      {loaded ? t("phone.reload") : t("phone.load")}
    </Button>
  </form>

  {#if loading && contacts.length === 0}
    <p class="empty">{t("phone.loading")}</p>
  {:else if stalled && contacts.length === 0}
    <p class="empty warn">{t("phone.err.contacts_permission_denied")}</p>
  {:else if !loaded}
    <p class="empty">{t("phone.idle")}</p>
  {:else if contacts.length === 0}
    <p class="empty">{t("phone.empty")}</p>
  {:else}
    <ul class="list">
      {#each contacts as contact, i (i)}
        <li>
          <span class="name">{contact.name || t("phone.unknown")}</span>
          <span class="numbers">
            {#each contact.numbers as number (number)}
              <span class="number">
                <span class="mono">{number}</span>
                <Button
                  variant="ghost"
                  disabled={!active || inCall}
                  onclick={() => dialNumber(number)}
                >
                  {t("phone.call")}
                </Button>
              </span>
            {/each}
          </span>
        </li>
      {/each}
    </ul>
    <div class="foot">
      <span class="mono count">{t("phone.count", { shown: contacts.length, total })}</span>
      {#if contacts.length < total}
        <Button disabled={!active || loading} onclick={() => fetchPage(contacts.length)}>
          {t("phone.more")}
        </Button>
      {/if}
    </div>
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

  .callbar {
    display: flex;
    align-items: center;
    gap: var(--space-xs);
    min-height: 44px;
    padding: 0 var(--space-sm);
    border: var(--border-width) solid var(--color-border);
    border-radius: var(--radius-sm);
    background: var(--color-surface);
  }

  .callbar.live {
    border-color: var(--color-accent);
  }

  .led {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--color-text-tertiary);
  }

  .led.on {
    background: var(--color-success);
  }

  .state {
    font-family: var(--font-mono);
    font-size: var(--type-caption-size);
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--color-text-secondary);
  }

  .who {
    display: flex;
    gap: var(--space-xs);
    font-size: var(--type-label-size);
  }

  .spacer {
    flex: 1;
  }

  .pcaudio {
    display: flex;
    align-items: center;
    gap: var(--space-xs);
    margin-top: var(--space-sm);
    font-size: var(--type-label-size);
    cursor: pointer;
  }

  .pcaudio input {
    accent-color: var(--color-accent);
  }

  .live {
    margin-left: auto;
    font-size: var(--type-caption-size);
    color: var(--color-success);
  }

  .hint {
    margin: var(--space-xxs) 0 0;
    font-size: var(--type-caption-size);
    color: var(--color-text-secondary);
  }

  .row {
    display: flex;
    gap: var(--space-xs);
    margin-top: var(--space-sm);
  }

  .field {
    flex: 1;
    min-height: 36px;
    padding: 0 var(--space-xs);
    border-radius: var(--radius-sm);
    border: var(--border-width) solid var(--color-border-active);
    background: var(--color-background);
    color: var(--color-text-primary);
    font: inherit;
  }

  .field.mono {
    font-family: var(--font-mono);
  }

  .list {
    margin: var(--space-sm) 0 0;
    padding: 0;
    list-style: none;
    max-height: 360px;
    overflow-y: auto;
    border: var(--border-width) solid var(--color-border);
    border-radius: var(--radius-sm);
  }

  .list li {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-sm);
    padding: var(--space-xs) var(--space-sm);
    border-bottom: var(--border-width) solid var(--color-border);
  }

  .list li:last-child {
    border-bottom: 0;
  }

  .name {
    font-size: var(--type-label-size);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .numbers {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: var(--space-xxs);
  }

  .number {
    display: flex;
    align-items: center;
    gap: var(--space-xs);
    font-size: var(--type-caption-size);
    color: var(--color-text-secondary);
  }

  .foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: var(--space-xs);
  }

  .count {
    font-size: var(--type-caption-size);
    color: var(--color-text-secondary);
  }

  .empty {
    margin: var(--space-sm) 0 0;
    padding: var(--space-md);
    border: var(--border-width) dashed var(--color-border);
    text-align: center;
    font-size: var(--type-label-size);
    color: var(--color-text-secondary);
  }

  .warn {
    color: var(--color-warning);
  }

  .error {
    color: var(--color-danger);
    font-size: var(--type-label-size);
  }
</style>
