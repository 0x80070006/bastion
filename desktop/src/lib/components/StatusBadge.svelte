<script lang="ts">
  interface Props {
    /** Semantic tone: ok, warn (alert), crit (critical) or off (inactive). */
    tone: "ok" | "warn" | "crit" | "off";
    label: string;
  }

  let { tone, label }: Props = $props();
</script>

<span class={`badge ${tone}`}>
  <span class="led" aria-hidden="true"></span>
  {label}
</span>

<style>
  .badge {
    display: inline-flex;
    align-items: center;
    gap: var(--space-xs);
    font-family: var(--font-mono);
    font-size: var(--type-caption-size);
    line-height: var(--type-caption-line-height);
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--color-text-secondary);
  }

  .led {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--color-inactive);
  }

  .ok .led {
    background: var(--color-success);
  }

  .warn .led {
    background: var(--color-warning);
  }

  .crit .led {
    background: var(--color-danger);
  }

  .ok {
    color: var(--color-success);
  }

  .warn {
    color: var(--color-warning);
  }

  .crit {
    color: var(--color-danger);
  }
</style>
