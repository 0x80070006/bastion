<script lang="ts">
  interface Props {
    id: string;
    label: string;
    value: string;
    type?: "text" | "password" | "number" | "url";
    hint?: string;
    placeholder?: string;
    autocomplete?: "off" | "new-password" | "current-password";
    multiline?: boolean;
    maxlength?: number;
    mono?: boolean;
    disabled?: boolean;
  }

  let {
    id,
    label,
    value = $bindable(),
    type = "text",
    hint,
    placeholder,
    autocomplete = "off",
    multiline = false,
    maxlength,
    mono = false,
    disabled = false,
  }: Props = $props();
</script>

<div class="field">
  <label for={id}>{label}</label>
  {#if multiline}
    <textarea
      {id}
      bind:value
      {placeholder}
      {maxlength}
      {disabled}
      rows="3"
      aria-describedby={hint ? `${id}-hint` : undefined}></textarea>
  {:else}
    <input
      {id}
      {type}
      bind:value
      {placeholder}
      {maxlength}
      {disabled}
      {autocomplete}
      class:mono
      spellcheck="false"
      aria-describedby={hint ? `${id}-hint` : undefined}
    />
  {/if}
  {#if hint}<p id={`${id}-hint`} class="hint">{hint}</p>{/if}
</div>

<style>
  .field {
    display: flex;
    flex-direction: column;
    gap: var(--space-xxs);
  }

  label {
    font-size: var(--type-label-size);
    font-weight: var(--type-label-weight);
    color: var(--color-text-secondary);
  }

  input,
  textarea {
    min-height: 40px;
    padding: var(--space-xs) var(--space-sm);
    border-radius: var(--radius-sm);
    border: var(--border-width) solid var(--color-border-active);
    background: var(--color-background);
    color: var(--color-text-primary);
    font: inherit;
    resize: vertical;
  }

  input:focus,
  textarea:focus {
    outline: none;
    border-color: var(--color-accent);
  }

  .mono {
    font-family: var(--font-mono);
  }

  .hint {
    margin: 0;
    font-size: var(--type-caption-size);
    line-height: var(--type-caption-line-height);
    color: var(--color-text-secondary);
  }
</style>
