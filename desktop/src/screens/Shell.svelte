<script lang="ts">
  import { onDestroy, onMount } from "svelte";
  import Button from "../lib/components/Button.svelte";
  import Devices from "./Devices.svelte";
  import Journal from "./Journal.svelte";
  import Settings from "./Settings.svelte";
  import { api, onBackend, type AppStatus, type DeviceSummary } from "../lib/api";
  import { t, type MessageKey } from "../lib/i18n";
  import { PRODUCT_NAME } from "../lib/brand";

  interface Props {
    status: AppStatus;
    onlocked: () => void;
  }

  let { status, onlocked }: Props = $props();
  type View = "devices" | "journal" | "settings";
  let view: View = $state("devices");
  let devices: DeviceSummary[] = $state([]);
  let connection = $state("idle");
  let onlineMap = $state(true);
  let revision = $state(0);
  let now = $state(Date.now());
  const cleanups: (() => void)[] = [];

  async function refresh() {
    try {
      const [list, s, settings] = await Promise.all([api.devices(), api.status(), api.settings()]);
      devices = list;
      connection = s.connection;
      onlineMap = settings.onlineMap;
      revision += 1;
      if (!s.unlocked) onlocked();
    } catch {
      onlocked();
    }
  }

  // Activity pings drive the backend auto-lock; throttled to one per 30 s.
  let lastPing = 0;
  function activity() {
    const at = Date.now();
    if (at - lastPing > 30_000) {
      lastPing = at;
      void api.touch().catch(() => undefined);
    }
  }

  onMount(async () => {
    await refresh();
    cleanups.push(await onBackend("changed", () => void refresh()));
    cleanups.push(await onBackend("locked", onlocked));
    const timer = setInterval(() => (now = Date.now()), 15_000);
    cleanups.push(() => clearInterval(timer));
    for (const name of ["pointerdown", "keydown"] as const) {
      window.addEventListener(name, activity, { passive: true });
      cleanups.push(() => window.removeEventListener(name, activity));
    }
  });

  onDestroy(() => cleanups.forEach((c) => c()));

  async function lock() {
    await api.lock();
    onlocked();
  }

  const nav: { id: View; label: MessageKey }[] = [
    { id: "devices", label: "nav.dashboard" },
    { id: "journal", label: "nav.journal" },
    { id: "settings", label: "nav.settings" },
  ];
</script>

<div class="shell">
  <nav aria-label={PRODUCT_NAME}>
    <span class="wordmark">{PRODUCT_NAME}</span>
    <ul>
      {#each nav as item (item.id)}
        <li>
          <button
            aria-current={view === item.id ? "page" : undefined}
            onclick={() => (view = item.id)}>{t(item.label)}</button
          >
        </li>
      {/each}
    </ul>
    <div class="bottom">
      <p class={`conn ${connection}`} role="status">
        {t(`conn.${connection}` as MessageKey)}
      </p>
      <Button variant="ghost" onclick={lock}>{t("nav.lock")}</Button>
      <span class="footer mono"
        >v{status.version} · {t("app.footer.protocol", { version: status.protocolVersion })}</span
      >
    </div>
  </nav>

  <main>
    {#if view === "devices"}
      <Devices {devices} {revision} {onlineMap} {now} />
    {:else if view === "journal"}
      <Journal {revision} />
    {:else}
      <Settings onsaved={() => void refresh()} />
    {/if}
  </main>
</div>

<style>
  .shell {
    display: grid;
    grid-template-columns: 220px 1fr;
    height: 100%;
  }

  nav {
    display: flex;
    flex-direction: column;
    gap: var(--space-xl);
    padding: var(--space-lg);
    border-right: var(--border-width) solid var(--color-border);
    background: var(--color-surface);
  }

  .wordmark {
    font-family: var(--font-mono);
    font-size: var(--type-title-size);
    line-height: var(--type-title-line-height);
    font-weight: var(--type-title-weight);
    text-transform: uppercase;
    letter-spacing: 0.12em;
    color: var(--color-text-primary);
  }

  .wordmark::before {
    content: "▚ ";
    color: var(--color-accent);
  }

  ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: var(--space-xxs);
  }

  nav li button {
    width: 100%;
    display: flex;
    align-items: center;
    min-height: 38px;
    padding: 0 var(--space-sm);
    border: none;
    border-left: 2px solid transparent;
    background: transparent;
    color: var(--color-text-secondary);
    font-family: var(--font-mono);
    font-size: var(--type-caption-size);
    font-weight: 500;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    cursor: pointer;
    transition: color var(--motion-fast) var(--motion-easing);
  }

  nav li button:hover {
    color: var(--color-text-primary);
  }

  nav li button[aria-current="page"] {
    color: var(--color-text-primary);
    background: var(--color-surface-raised);
    border-left-color: var(--color-accent);
  }

  .bottom {
    margin-top: auto;
    display: flex;
    flex-direction: column;
    gap: var(--space-xs);
  }

  .conn {
    margin: 0;
    display: flex;
    align-items: center;
    gap: var(--space-xxs);
    font-family: var(--font-mono);
    font-size: var(--type-caption-size);
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: var(--color-text-secondary);
  }

  .conn::before {
    content: "●";
    font-size: 0.7em;
    color: currentcolor;
  }

  .conn.online {
    color: var(--color-success);
  }

  .conn.offline {
    color: var(--color-warning);
  }

  .conn.pinMismatch {
    color: var(--color-danger);
  }

  .footer {
    color: var(--color-text-secondary);
    font-size: var(--type-caption-size);
  }

  main {
    padding: var(--space-lg) var(--space-xl);
    min-height: 0;
    overflow: hidden;
  }
</style>
