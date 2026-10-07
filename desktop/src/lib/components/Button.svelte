<script lang="ts">
  import type { Snippet } from "svelte";

  interface Props {
    variant?: "primary" | "secondary" | "danger" | "ghost";
    type?: "button" | "submit";
    disabled?: boolean;
    describedBy?: string;
    onclick?: () => void;
    children: Snippet;
  }

  let {
    variant = "secondary",
    type = "button",
    disabled = false,
    describedBy,
    onclick,
    children,
  }: Props = $props();
</script>

<button class={variant} {type} {disabled} aria-describedby={describedBy} {onclick}>
  {@render children()}
</button>

<style>
  button {
    min-height: 40px;
    padding: 0 var(--space-md);
    border-radius: var(--radius-md);
    border: var(--border-width) solid var(--color-border-active);
    background: var(--color-surface-raised);
    color: var(--color-text-primary);
    font: inherit;
    font-size: var(--type-label-size);
    font-weight: var(--type-label-weight);
    letter-spacing: var(--type-label-tracking);
    cursor: pointer;
    white-space: nowrap;
    transition:
      border-color var(--motion-fast) var(--motion-easing),
      background-color var(--motion-fast) var(--motion-easing);
  }

  button:hover:not(:disabled) {
    border-color: var(--color-text-tertiary);
  }

  .primary {
    background: var(--color-accent);
    border-color: var(--color-accent);
    color: var(--color-on-accent);
  }

  .primary:hover:not(:disabled) {
    border-color: var(--color-text-primary);
  }

  .danger {
    background: transparent;
    border-color: var(--color-danger);
    color: var(--color-danger);
  }

  .danger:hover:not(:disabled) {
    background: var(--color-danger);
    color: var(--color-text-primary);
  }

  .ghost {
    background: transparent;
    border-color: transparent;
    color: var(--color-text-secondary);
  }

  button:disabled {
    cursor: not-allowed;
    background: var(--color-surface);
    border-color: var(--color-border);
    color: var(--color-text-tertiary);
  }
</style>
