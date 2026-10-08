<script lang="ts">
  import Button from "../lib/components/Button.svelte";
  import DeviceDetailView from "./DeviceDetail.svelte";
  import PairingDialog from "./PairingDialog.svelte";
  import { api, type DeviceDetail, type DeviceSummary } from "../lib/api";
  import { relativeTime } from "../lib/format";
  import { t, type MessageKey } from "../lib/i18n";

  interface Props {
    devices: DeviceSummary[];
    revision: number;
    onlineMap: boolean;
    now: number;
  }

  let { devices, revision, onlineMap, now }: Props = $props();
  let selected: string | undefined = $state();
  let detail: DeviceDetail | undefined = $state();
  let pairing = $state(false);

  const current = $derived(devices.find((d) => d.id === selected) ?? devices[0]);

  $effect(() => {
    // Reload the detail when the selection or any backend data changes.
    void revision;
    const id = current?.id;
    if (!id) {
      detail = undefined;
      return;
    }
    api
      .device(id)
      .then((d) => (detail = d))
      .catch(() => (detail = undefined));
  });
</script>

{#if devices.length === 0 && !pairing}
  <section class="empty" aria-labelledby="empty-title">
    <h1 id="empty-title">{t("app.status.noDevice")}</h1>
    <p>{t("app.status.noDeviceHint")}</p>
    <Button variant="primary" onclick={() => (pairing = true)}>{t("app.action.addPhone")}</Button>
  </section>
{:else}
  <div class="layout">
    <nav class="list" aria-label={t("nav.dashboard")}>
      <ul>
        {#each devices as d (d.id)}
          <li>
            <button
              class:selected={d.id === current?.id}
              aria-current={d.id === current?.id ? "true" : undefined}
              onclick={() => (selected = d.id)}
            >
              <span class="label">{d.label}</span>
              <span class={`state ${d.state}`}>{t(`device.state.${d.state}` as MessageKey)}</span>
              <span class="seen">
                {d.lastSeenMs ? relativeTime(t, d.lastSeenMs, now) : t("device.neverSeen")}
              </span>
            </button>
          </li>
        {/each}
      </ul>
      <Button onclick={() => (pairing = true)}>{t("app.action.addPhone")}</Button>
    </nav>
    <div class="content">
      {#if detail && detail.summary.id === current?.id}
        <DeviceDetailView {detail} {onlineMap} {now} {revision} />
      {/if}
    </div>
  </div>
{/if}

{#if pairing}
  <PairingDialog {devices} onclose={() => (pairing = false)} />
{/if}

<style>
  .empty {
    height: 100%;
    max-width: 420px;
    margin: 0 auto;
    display: flex;
    flex-direction: column;
    justify-content: center;
    align-items: flex-start;
    gap: var(--space-md);
  }

  .empty h1 {
    margin: 0;
    font-size: var(--type-title-size);
    font-weight: var(--type-title-weight);
  }

  .empty p {
    margin: 0;
    color: var(--color-text-secondary);
  }

  .layout {
    display: grid;
    grid-template-columns: 220px minmax(0, 1fr);
    gap: var(--space-lg);
    height: 100%;
    min-height: 0;
  }

  .list {
    display: flex;
    flex-direction: column;
    gap: var(--space-md);
  }

  ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: var(--space-xxs);
  }

  li button {
    width: 100%;
    display: grid;
    gap: 2px;
    text-align: left;
    padding: var(--space-sm);
    border-radius: var(--radius-md);
    border: var(--border-width) solid var(--color-border);
    background: var(--color-surface);
    color: var(--color-text-primary);
    font: inherit;
    cursor: pointer;
  }

  li button.selected {
    border-color: var(--color-accent);
  }

  .label {
    font-weight: var(--type-label-weight);
  }

  .state,
  .seen {
    font-size: var(--type-caption-size);
    color: var(--color-text-secondary);
  }

  .state.active {
    color: var(--color-success);
  }

  .state.pendingSas,
  .state.awaitingPhone {
    color: var(--color-warning);
  }

  .content {
    min-height: 0;
  }
</style>
