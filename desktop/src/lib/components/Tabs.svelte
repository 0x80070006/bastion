<script lang="ts" generics="T extends string">
  import Icon from "./Icon.svelte";

  type IconName = "overview" | "control" | "media" | "comms" | "security";

  interface Props {
    tabs: { id: T; label: string; icon: IconName }[];
    selected: T;
    onselect: (id: T) => void;
  }

  let { tabs, selected, onselect }: Props = $props();

  // Arrow keys, Home and End move between tabs, as for any ARIA tablist.
  function target(key: string, index: number): number | undefined {
    if (key === "ArrowRight") return (index + 1) % tabs.length;
    if (key === "ArrowLeft") return (index - 1 + tabs.length) % tabs.length;
    if (key === "Home") return 0;
    if (key === "End") return tabs.length - 1;
    return undefined;
  }

  function onkeydown(event: KeyboardEvent) {
    const next = target(
      event.key,
      tabs.findIndex((tab) => tab.id === selected),
    );
    if (next === undefined) return;
    event.preventDefault();
    const tab = tabs[next];
    if (tab) onselect(tab.id);
  }
</script>

<div class="tabs" role="tablist" tabindex="-1" {onkeydown}>
  {#each tabs as tab (tab.id)}
    <button
      role="tab"
      id={`tab-${tab.id}`}
      aria-selected={tab.id === selected}
      aria-controls={`panel-${tab.id}`}
      tabindex={tab.id === selected ? 0 : -1}
      onclick={() => onselect(tab.id)}
    >
      <Icon name={tab.icon} size={14} />
      {tab.label}
    </button>
  {/each}
</div>

<style>
  .tabs {
    display: flex;
    gap: var(--space-xxs);
    border-bottom: var(--border-width) solid var(--color-border);
  }

  button {
    display: inline-flex;
    align-items: center;
    gap: var(--space-xs);
    min-height: var(--control-h, 36px);
    padding: 0 var(--space-md);
    border: none;
    border-bottom: 2px solid transparent;
    background: transparent;
    color: var(--color-text-secondary);
    font-family: var(--font-mono);
    font-size: var(--type-caption-size);
    letter-spacing: 0.08em;
    text-transform: uppercase;
    cursor: pointer;
    margin-bottom: -1px;
  }

  button:hover {
    color: var(--color-text-primary);
  }

  button[aria-selected="true"] {
    color: var(--color-text-primary);
    border-bottom-color: var(--color-accent);
    background: var(--color-surface);
  }
</style>
