<script lang="ts">
  import { onDestroy } from "svelte";
  import Button from "../lib/components/Button.svelte";
  import StatusBadge from "../lib/components/StatusBadge.svelte";
  import { api, errorCode, onSms, type SmsMessage } from "../lib/api";
  import { dateTime } from "../lib/format";
  import { locale, t } from "../lib/i18n";

  interface Props {
    deviceId: string;
    active: boolean;
  }

  let { deviceId, active }: Props = $props();

  // No page after this long: the phone is offline or refused (permission off).
  const STALL_MS = 8000;
  // After sending, re-read the thread once the phone has written the message to its outbox.
  const REFRESH_AFTER_SEND_MS = 1500;

  let conversations = $state<SmsMessage[]>([]);
  let thread = $state<SmsMessage[]>([]);
  let peer = $state("");
  let peerName = $state("");
  let draft = $state("");
  let newNumber = $state("");
  let loaded = $state(false);
  let loading = $state(false);
  let stalled = $state(false);
  let feedback = $state("");
  let stallTimer: ReturnType<typeof setTimeout> | undefined;
  const cleanups: (() => void)[] = [];

  // Last digits identify a number whatever its prefix ("+33 6…" and "06…").
  const key = (value: string) => value.replace(/\D/g, "").slice(-9);

  onSms((page) => {
    if (page.deviceId !== deviceId) return;
    if (page.incoming) {
      const message = page.messages[0];
      if (!message) return;
      conversations = [
        message,
        ...conversations.filter((c) => key(c.address) !== key(message.address)),
      ];
      if (peer && key(peer) === key(message.address)) thread = [message, ...thread];
      return;
    }
    clearTimeout(stallTimer);
    stalled = false;
    loading = false;
    if (page.peer === "") {
      loaded = true;
      conversations = page.messages;
    } else if (key(page.peer) === key(peer)) {
      thread = page.messages;
    }
  }).then((fn) => cleanups.push(fn));

  onDestroy(() => {
    cleanups.forEach((fn) => fn());
    clearTimeout(stallTimer);
    // Messages are personal data: drop them as soon as the panel closes.
    conversations = [];
    thread = [];
  });

  async function request(target: string) {
    feedback = "";
    loading = true;
    stalled = false;
    clearTimeout(stallTimer);
    try {
      await api.sendCommand(deviceId, { kind: "smsList", peer: target });
    } catch (e) {
      loading = false;
      feedback = t(`error.${errorCode(e)}`);
      return;
    }
    stallTimer = setTimeout(() => {
      if (loading) {
        loading = false;
        stalled = true;
      }
    }, STALL_MS);
  }

  function open(message: SmsMessage) {
    peer = message.address;
    peerName = message.name;
    thread = [];
    void request(message.address);
  }

  function startNew() {
    const number = newNumber.replace(/[\s().-]/g, "");
    if (!number) return;
    peer = number;
    peerName = "";
    thread = [];
    newNumber = "";
    void request(number);
  }

  async function send() {
    const body = draft.trim();
    if (!body || !peer) return;
    feedback = "";
    try {
      await api.sendCommand(deviceId, { kind: "smsSend", to: peer, body });
    } catch (e) {
      feedback = t(`error.${errorCode(e)}`);
      return;
    }
    // Show it at once; the thread is re-read below to reflect what the phone really stored.
    thread = [{ address: peer, name: peerName, body, atMs: Date.now(), outgoing: true }, ...thread];
    draft = "";
    setTimeout(() => void request(peer), REFRESH_AFTER_SEND_MS);
  }

  const chronological = $derived([...thread].reverse());
</script>

<section aria-labelledby="sms-title">
  <h2 id="sms-title">{t("sms.title")}</h2>
  <p class="note">{t("sms.note")}</p>

  <div class="layout">
    <div class="list">
      <div class="row">
        <Button disabled={!active || loading} onclick={() => request("")}>
          {loaded ? t("phone.reload") : t("sms.load")}
        </Button>
      </div>
      <form
        class="row"
        onsubmit={(e) => {
          e.preventDefault();
          startNew();
        }}
      >
        <input
          class="field mono"
          inputmode="tel"
          placeholder={t("sms.newNumber")}
          aria-label={t("sms.newNumber")}
          bind:value={newNumber}
          maxlength="40"
        />
        <Button type="submit" disabled={!active || !newNumber.trim()}>{t("sms.new")}</Button>
      </form>

      {#if loading && conversations.length === 0}
        <p class="empty">{t("sms.loading")}</p>
      {:else if stalled && conversations.length === 0}
        <p class="empty warn">{t("sms.err.sms_permission_denied")}</p>
      {:else if !loaded}
        <p class="empty">{t("sms.idle")}</p>
      {:else if conversations.length === 0}
        <p class="empty">{t("sms.empty")}</p>
      {:else}
        <ul class="conversations">
          {#each conversations as c (c.address)}
            <li>
              <button class:selected={key(c.address) === key(peer)} onclick={() => open(c)}>
                <span class="who">
                  {c.name || c.address}
                  <span class="mono when">{dateTime(locale, c.atMs)}</span>
                </span>
                <span class="preview">{c.outgoing ? `${t("sms.you")} ` : ""}{c.body}</span>
              </button>
            </li>
          {/each}
        </ul>
      {/if}
    </div>

    <div class="thread">
      {#if !peer}
        <p class="empty">{t("sms.pick")}</p>
      {:else}
        <div class="thread-head">
          <StatusBadge tone="ok" label={peerName || peer} />
          {#if peerName}<span class="mono">{peer}</span>{/if}
        </div>
        <div class="messages" role="log" aria-live="polite">
          {#if loading && thread.length === 0}
            <p class="empty">{t("sms.loading")}</p>
          {:else if thread.length === 0}
            <p class="empty">{t("sms.emptyThread")}</p>
          {/if}
          {#each chronological as m, i (i)}
            <div class="bubble" class:out={m.outgoing}>
              <p>{m.body}</p>
              <span class="mono when">{dateTime(locale, m.atMs)}</span>
            </div>
          {/each}
        </div>
        <form
          class="row compose"
          onsubmit={(e) => {
            e.preventDefault();
            void send();
          }}
        >
          <input
            class="field"
            placeholder={t("sms.compose")}
            aria-label={t("sms.compose")}
            bind:value={draft}
            maxlength="1000"
          />
          <Button type="submit" variant="primary" disabled={!active || !draft.trim()}>
            {t("sms.send")}
          </Button>
        </form>
      {/if}
    </div>
  </div>
  {#if feedback}<p class="error" role="status">{feedback}</p>{/if}
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

  .layout {
    display: grid;
    grid-template-columns: minmax(220px, 1fr) minmax(0, 1.4fr);
    gap: var(--space-md);
    min-height: 320px;
  }

  .list,
  .thread {
    display: flex;
    flex-direction: column;
    gap: var(--space-xs);
    min-width: 0;
  }

  .row {
    display: flex;
    gap: var(--space-xs);
  }

  .field {
    flex: 1;
    min-width: 0;
    min-height: var(--control-h, 36px);
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

  .conversations {
    list-style: none;
    margin: 0;
    padding: 0;
    max-height: 360px;
    overflow-y: auto;
    border: var(--border-width) solid var(--color-border);
    border-radius: var(--radius-sm);
  }

  .conversations button {
    width: 100%;
    display: grid;
    gap: 2px;
    text-align: left;
    padding: var(--space-xs) var(--space-sm);
    border: none;
    border-bottom: var(--border-width) solid var(--color-border);
    background: transparent;
    color: var(--color-text-primary);
    font: inherit;
    cursor: pointer;
  }

  .conversations button.selected {
    background: var(--color-surface-raised);
    box-shadow: inset 2px 0 0 var(--color-accent);
  }

  .who {
    display: flex;
    justify-content: space-between;
    gap: var(--space-xs);
    font-size: var(--type-label-size);
    font-weight: var(--type-label-weight);
  }

  .when {
    font-size: var(--type-caption-size);
    color: var(--color-text-secondary);
    font-weight: 400;
  }

  .preview {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: var(--type-caption-size);
    color: var(--color-text-secondary);
  }

  .thread-head {
    display: flex;
    align-items: center;
    gap: var(--space-sm);
    padding-bottom: var(--space-xs);
    border-bottom: var(--border-width) solid var(--color-border);
    font-size: var(--type-caption-size);
    color: var(--color-text-secondary);
  }

  .messages {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: var(--space-xs);
    max-height: 300px;
    overflow-y: auto;
    padding: var(--space-xs);
    border: var(--border-width) solid var(--color-border);
    border-radius: var(--radius-sm);
    background: var(--color-surface);
  }

  .bubble {
    align-self: flex-start;
    max-width: 82%;
    padding: var(--space-xs) var(--space-sm);
    border: var(--border-width) solid var(--color-border-active);
    border-radius: var(--radius-md);
    background: var(--color-surface-raised);
  }

  .bubble.out {
    align-self: flex-end;
    border-color: var(--color-accent);
  }

  .bubble p {
    margin: 0;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    font-size: var(--type-label-size);
  }

  .empty {
    margin: 0;
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
