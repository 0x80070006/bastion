<script lang="ts">
  import Button from "../lib/components/Button.svelte";
  import MapView from "../lib/components/MapView.svelte";
  import ActionDialogs, { type DialogKind } from "./ActionDialogs.svelte";
  import { api, errorCode, type CommandRequest, type DeviceDetail } from "../lib/api";
  import { dateTime, relativeTime } from "../lib/format";
  import { locale, t, type MessageKey } from "../lib/i18n";

  interface Props {
    detail: DeviceDetail;
    onlineMap: boolean;
    now: number;
  }

  let { detail, onlineMap, now }: Props = $props();
  let dialog: DialogKind | undefined = $state();
  let feedback = $state("");
  let feedbackError = $state(false);
  let busy = $state(false);

  const device = $derived(detail.summary);
  const status = $derived(device.status);
  const last = $derived(device.lastLocation);
  const active = $derived(device.state === "active");

  function key(text: string): MessageKey {
    return text as MessageKey;
  }

  async function send(request: CommandRequest) {
    busy = true;
    try {
      await api.sendCommand(device.id, request);
      feedback = t("action.sent");
      feedbackError = false;
    } catch (e) {
      feedback = t(`error.${errorCode(e)}`);
      feedbackError = true;
    } finally {
      busy = false;
    }
  }
</script>

<section class="detail" aria-labelledby="device-title">
  <header>
    <div>
      <h1 id="device-title">{device.label}</h1>
      <p class="meta">
        <span class={`state ${device.state}`}>{t(key(`device.state.${device.state}`))}</span>
        ·
        {device.lastSeenMs
          ? t("device.lastSeen", { when: relativeTime(t, device.lastSeenMs, now) })
          : t("device.neverSeen")}
      </p>
    </div>
  </header>

  <div class="grid">
    <div class="map-area">
      {#if !onlineMap}
        <p class="placeholder">{t("device.mapDisabled")}</p>
      {/if}
      {#if last}
        {#if onlineMap}<MapView points={detail.locations} label={device.label} />{/if}
        <p class="mono coords">
          {last.latitude.toFixed(5)}, {last.longitude.toFixed(5)}
          · {t("device.accuracy", { meters: Math.round(last.accuracyM) })}
          · {dateTime(locale, last.fixTimeMs)}
          · {t("device.positions", { count: detail.locations.length })}
        </p>
      {:else}
        <p class="placeholder">{t("device.noLocation")}</p>
      {/if}
    </div>

    <aside>
      {#if status}
        <ul class="facts">
          {#if status.batteryPercent !== null}
            <li>
              {t("device.battery", { level: status.batteryPercent })}{status.charging
                ? ` (${t("device.charging")})`
                : ""}
            </li>
          {/if}
          <li>{t("device.network", { network: t(key(`network.${status.network}`)) })}</li>
          <li>{t("device.tracking", { mode: t(key(`tracking.${status.trackingMode}`)) })}</li>
          {#if status.lostMode}<li class="lost">{t("device.lostMode")}</li>{/if}
        </ul>
        {#if status.health.length > 0}
          <h2>{t("device.health")}</h2>
          <ul class="health">
            {#each status.health as h (h.feature)}
              <li class={h.state}>
                <span class="mono">{h.feature}</span>
                <span>{t(key(`health.${h.state}`))}{h.reason ? ` · ${h.reason}` : ""}</span>
              </li>
            {/each}
          </ul>
        {/if}
      {/if}

      <div class="actions">
        <Button
          variant="primary"
          disabled={!active || busy}
          onclick={() =>
            send({ kind: "ring", durationSeconds: 0, flashlight: true, vibrate: true })}
          >{t("action.ring")}</Button
        >
        <Button disabled={!active || busy} onclick={() => send({ kind: "stopRing" })}
          >{t("action.stopRing")}</Button
        >
        <Button
          disabled={!active || busy}
          onclick={() => send({ kind: "locate", highAccuracy: true })}>{t("action.locate")}</Button
        >
        <Button disabled={!active || busy} onclick={() => send({ kind: "status" })}
          >{t("action.status")}</Button
        >
        <Button
          disabled={!active || busy}
          onclick={() => send({ kind: "trackingMode", mode: "active" })}
          >{t("action.trackActive")}</Button
        >
        <Button
          disabled={!active || busy}
          onclick={() => send({ kind: "trackingMode", mode: "standby" })}
          >{t("action.trackStandby")}</Button
        >
        {#if status?.lostMode}
          <Button
            disabled={!active || busy}
            onclick={() => send({ kind: "lostMode", enabled: false, contact: null })}
            >{t("action.lostModeOff")}</Button
          >
        {:else}
          <Button disabled={!active || busy} onclick={() => (dialog = "lost")}
            >{t("action.lostMode")}</Button
          >
        {/if}
      </div>
      {#if feedback}
        <p class:error={feedbackError} class="feedback" role="status">{feedback}</p>
      {/if}

      <h2>{t("action.sensitive")}</h2>
      <div class="actions">
        <Button variant="danger" disabled={!active} onclick={() => (dialog = "lock")}
          >{t("action.lock")}</Button
        >
        <Button variant="danger" disabled={!active} onclick={() => (dialog = "wipe")}
          >{t("action.wipe")}</Button
        >
        <Button variant="danger" disabled={!active} onclick={() => (dialog = "unpair")}
          >{t("action.unpair")}</Button
        >
        <Button variant="ghost" onclick={() => (dialog = "forget")}>{t("action.forget")}</Button>
      </div>

      {#if detail.commands.length > 0}
        <h2>{t("device.commands")}</h2>
        <ul class="commands">
          {#each detail.commands as c (c.id)}
            <li>
              <span>{t(key(`command.${c.kind}`))}</span>
              <span class={`status ${c.status}`}
                >{t(key(`status.${c.status}`))}{c.reason ? ` · ${c.reason}` : ""}</span
              >
              <span class="time">{relativeTime(t, c.sentMs, now)}</span>
            </li>
          {/each}
        </ul>
      {/if}

      <h2>{t("device.fingerprint")}</h2>
      <p class="mono fingerprint">{device.fingerprint}</p>
    </aside>
  </div>
</section>

{#if dialog}
  <ActionDialogs
    kind={dialog}
    deviceId={device.id}
    onclose={(sent) => {
      if (sent) {
        feedback = t("action.sent");
        feedbackError = false;
      }
      dialog = undefined;
    }}
  />
{/if}

<style>
  .detail {
    display: flex;
    flex-direction: column;
    gap: var(--space-md);
    height: 100%;
    min-height: 0;
  }

  h1 {
    margin: 0;
    font-size: var(--type-title-size);
    line-height: var(--type-title-line-height);
    font-weight: var(--type-title-weight);
  }

  h2 {
    margin: var(--space-md) 0 var(--space-xs);
    font-size: var(--type-label-size);
    font-weight: var(--type-label-weight);
    color: var(--color-text-secondary);
  }

  .meta {
    margin: var(--space-xxs) 0 0;
    color: var(--color-text-secondary);
    font-size: var(--type-label-size);
  }

  .state.active {
    color: var(--color-success);
  }

  .state.pendingSas,
  .state.awaitingPhone {
    color: var(--color-warning);
  }

  .grid {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 340px;
    gap: var(--space-lg);
    flex: 1;
    min-height: 0;
  }

  .map-area {
    display: flex;
    flex-direction: column;
    gap: var(--space-xs);
    min-height: 0;
  }

  .placeholder {
    flex: 1;
    display: grid;
    place-items: center;
    margin: 0;
    border-radius: var(--radius-md);
    border: var(--border-width) dashed var(--color-border-active);
    color: var(--color-text-secondary);
    min-height: 200px;
  }

  .coords {
    margin: 0;
    font-size: var(--type-caption-size);
    color: var(--color-text-secondary);
  }

  aside {
    overflow-y: auto;
  }

  ul {
    list-style: none;
    margin: 0;
    padding: 0;
  }

  .facts li {
    padding: var(--space-xxs) 0;
  }

  .lost {
    color: var(--color-warning);
  }

  .health li,
  .commands li {
    display: flex;
    justify-content: space-between;
    gap: var(--space-sm);
    padding: var(--space-xxs) 0;
    font-size: var(--type-label-size);
    border-bottom: var(--border-width) solid var(--color-border);
  }

  .health .disabled,
  .status.failed,
  .status.rejected {
    color: var(--color-danger);
  }

  .health .degraded,
  .status.unsupported {
    color: var(--color-warning);
  }

  .status.completed {
    color: var(--color-success);
  }

  .time {
    color: var(--color-text-secondary);
  }

  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-xs);
    margin-top: var(--space-md);
  }

  .feedback {
    font-size: var(--type-label-size);
    color: var(--color-success);
  }

  .feedback.error {
    color: var(--color-danger);
  }

  .fingerprint {
    margin: 0;
    font-size: var(--type-caption-size);
    color: var(--color-text-secondary);
  }
</style>
