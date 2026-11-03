<script lang="ts">
  import { onDestroy, onMount } from "svelte";
  import Button from "../lib/components/Button.svelte";
  import Icon from "../lib/components/Icon.svelte";
  import StatusBadge from "../lib/components/StatusBadge.svelte";
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
  let clock = $state(utcClock());
  let density: "comfortable" | "compact" = $state(readDensity());
  const cleanups: (() => void)[] = [];

  function utcClock(): string {
    return new Date().toISOString().slice(11, 19);
  }

  // Density is a per-viewer convenience: storage may be unavailable, the UI must still render.
  function readDensity(): "comfortable" | "compact" {
    try {
      const saved =
        localStorage.getItem("bastion.density") === "compact" ? "compact" : "comfortable";
      document.documentElement.dataset.density = saved;
      return saved;
    } catch {
      return "comfortable";
    }
  }

  function toggleDensity() {
    density = density === "compact" ? "comfortable" : "compact";
    document.documentElement.dataset.density = density;
    try {
      localStorage.setItem("bastion.density", density);
    } catch {
      /* not persisted */
    }
  }

  // Alt+1..3 switch views, Alt+L locks: a persistent, predictable keyboard map.
  function shortcut(event: KeyboardEvent) {
    if (!event.altKey || event.ctrlKey || event.metaKey) return;
    const target = nav.find((_, i) => event.key === String(i + 1));
    if (target) {
      view = target.id;
      event.preventDefault();
    } else if (event.key.toLowerCase() === "l") {
      event.preventDefault();
      void lock();
    }
  }

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
    const tick = setInterval(() => (clock = utcClock()), 1000);
    cleanups.push(() => clearInterval(tick));
    window.addEventListener("keydown", shortcut);
    cleanups.push(() => window.removeEventListener("keydown", shortcut));
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

  const nav: { id: View; label: MessageKey; icon: "devices" | "journal" | "settings" }[] = [
    { id: "devices", label: "nav.dashboard", icon: "devices" },
    { id: "journal", label: "nav.journal", icon: "journal" },
    { id: "settings", label: "nav.settings", icon: "settings" },
  ];

  const connTone = $derived(
    connection === "online"
      ? "ok"
      : connection === "pinMismatch"
        ? "crit"
        : connection === "offline"
          ? "warn"
          : "off",
  );
</script>

<div class="shell">
  <nav aria-label={PRODUCT_NAME}>
    <span class="wordmark">{PRODUCT_NAME}</span>
    <ul>
      {#each nav as item, i (item.id)}
        <li>
          <button
            aria-current={view === item.id ? "page" : undefined}
            aria-keyshortcuts={`Alt+${i + 1}`}
            onclick={() => (view = item.id)}
          >
            <Icon name={item.icon} />
            <span class="name">{t(item.label)}</span>
            <kbd class="mono">⌥{i + 1}</kbd>
          </button>
        </li>
      {/each}
    </ul>
    <div class="bottom">
      <div role="status">
        <StatusBadge tone={connTone} label={t(`conn.${connection}` as MessageKey)} />
      </div>
      <button class="density" onclick={toggleDensity}>
        <Icon name="density" />
        {t(density === "compact" ? "nav.density.compact" : "nav.density.comfortable")}
      </button>
      <Button variant="ghost" onclick={lock}>
        <Icon name="lock" size={14} />
        {t("nav.lock")} <kbd class="mono">⌥L</kbd>
      </Button>
      <span class="footer mono">{t("nav.utc")} {clock}</span>
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

  kbd {
    margin-left: auto;
    font-size: var(--type-caption-size);
    color: var(--color-text-tertiary);
  }

  .name {
    flex: 1;
  }

  nav li button {
    gap: var(--space-xs);
  }

  .density {
    display: flex;
    align-items: center;
    gap: var(--space-xs);
    min-height: var(--control-h);
    padding: 0 var(--space-sm);
    border: var(--border-width) solid var(--color-border);
    border-radius: var(--radius-sm);
    background: transparent;
    color: var(--color-text-secondary);
    font-family: var(--font-mono);
    font-size: var(--type-caption-size);
    letter-spacing: 0.06em;
    text-transform: uppercase;
    cursor: pointer;
  }

  .density:hover {
    border-color: var(--color-border-active);
    color: var(--color-text-primary);
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
