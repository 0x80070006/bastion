<script lang="ts">
  import Button from "../lib/components/Button.svelte";
  import MapView from "../lib/components/MapView.svelte";
  import ActionDialogs, { type DialogKind } from "./ActionDialogs.svelte";
  import CameraPanel from "./CameraPanel.svelte";
  import ScreenPanel from "./ScreenPanel.svelte";
  import {
    api,
    errorCode,
    type CommandRequest,
    type DeviceDetail,
    type GeofenceView,
  } from "../lib/api";
  import { dateTime, relativeTime } from "../lib/format";
  import { locale, t, type MessageKey } from "../lib/i18n";

  interface Props {
    detail: DeviceDetail;
    onlineMap: boolean;
    now: number;
    revision: number;
  }

  let { detail, onlineMap, now, revision }: Props = $props();
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

  // Geofences.
  let editingZones = $state(false);
  let zones: GeofenceView[] = $state([]);

  $effect(() => {
    void revision;
    const id = device.id;
    if (!id) return;
    api
      .geofences(id)
      .then((z) => {
        if (!editingZones) zones = z;
      })
      .catch(() => undefined);
  });

  function addZone(lat: number, lon: number) {
    if (!editingZones) return;
    zones = [...zones, { id: "", name: "", latitude: lat, longitude: lon, radiusM: 200 }];
  }

  async function saveZones() {
    busy = true;
    try {
      await api.setGeofences(device.id, zones);
      zones = await api.geofences(device.id);
      editingZones = false;
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
      {:else}
        <MapView
          points={detail.locations}
          label={device.label}
          {zones}
          editing={editingZones}
          onMapClick={addZone}
        />
        {#if last}
          <p class="mono coords">
            {last.latitude.toFixed(5)}, {last.longitude.toFixed(5)}
            · {t("device.accuracy", { meters: Math.round(last.accuracyM) })}
            · {dateTime(locale, last.fixTimeMs)}
            · {t("device.positions", { count: detail.locations.length })}
          </p>
        {:else}
          <p class="coords">{t("device.noLocation")}</p>
        {/if}
        <div class="zones">
          <div class="zones-head">
            <span>{t("zones.title")}</span>
            {#if editingZones}
              <span class="hint">{t("zones.hint")}</span>
            {:else}
              <Button variant="ghost" onclick={() => (editingZones = true)}
                >{t("zones.edit")}</Button
              >
            {/if}
          </div>
          {#if editingZones}
            {#each zones as zone, i (i)}
              <div class="zone-row">
                <input
                  class="zone-name"
                  placeholder={t("zones.name")}
                  bind:value={zone.name}
                  maxlength="64"
                />
                <input
                  class="zone-radius mono"
                  type="number"
                  min="50"
                  max="50000"
                  bind:value={zone.radiusM}
                  aria-label={t("zones.radius")}
                />
                <span class="mono unit">m</span>
                <Button variant="ghost" onclick={() => (zones = zones.filter((_, j) => j !== i))}>
                  {t("action.close")}
                </Button>
              </div>
            {/each}
            <div class="zone-actions">
              <Button
                onclick={() => {
                  editingZones = false;
                  zones = [];
                  void api.geofences(device.id).then((z) => (zones = z));
                }}
              >
                {t("action.cancel")}
              </Button>
              <Button variant="primary" disabled={!active || busy} onclick={saveZones}>
                {t("zones.save")}
              </Button>
            </div>
          {:else if zones.length === 0}
            <p class="hint">{t("zones.none")}</p>
          {:else}
            <ul class="zone-list">
              {#each zones as zone (zone.id)}
                <li>{zone.name || t("zones.unnamed")} · {Math.round(zone.radiusM)} m</li>
              {/each}
            </ul>
          {/if}
        </div>
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

      <CameraPanel deviceId={device.id} {active} {revision} />

      <ScreenPanel deviceId={device.id} {active} />

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

  .zones {
    display: flex;
    flex-direction: column;
    gap: var(--space-xs);
  }

  .zones-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: var(--type-label-size);
    color: var(--color-text-secondary);
  }

  .zone-row {
    display: flex;
    gap: var(--space-xs);
    align-items: center;
  }

  .zone-name {
    flex: 1;
  }

  .zone-radius {
    width: 88px;
  }

  .zone-name,
  .zone-radius {
    min-height: 32px;
    padding: 0 var(--space-xs);
    border-radius: var(--radius-sm);
    border: var(--border-width) solid var(--color-border-active);
    background: var(--color-background);
    color: var(--color-text-primary);
    font: inherit;
  }

  .unit {
    color: var(--color-text-secondary);
    font-size: var(--type-caption-size);
  }

  .zone-actions {
    display: flex;
    gap: var(--space-xs);
    justify-content: flex-end;
    margin-top: var(--space-xs);
  }

  .zone-list {
    list-style: none;
    margin: 0;
    padding: 0;
    font-size: var(--type-label-size);
    color: var(--color-text-secondary);
  }

  .hint {
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
