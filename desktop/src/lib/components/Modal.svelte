<script lang="ts">
  import type { Snippet } from "svelte";

  interface Props {
    title: string;
    onclose: () => void;
    children: Snippet;
    danger?: boolean;
  }

  let { title, onclose, children, danger = false }: Props = $props();
  let dialog: HTMLDialogElement | undefined = $state();

  $effect(() => {
    dialog?.showModal();
  });
</script>

<dialog
  bind:this={dialog}
  class:danger
  aria-labelledby="modal-title"
  oncancel={(event) => {
    event.preventDefault();
    onclose();
  }}
>
  <h2 id="modal-title">{title}</h2>
  {@render children()}
</dialog>

<style>
  dialog {
    width: min(520px, calc(100vw - 48px));
    max-height: calc(100vh - 48px);
    overflow: auto;
    padding: var(--space-lg);
    border-radius: var(--radius-lg);
    border: var(--border-width) solid var(--color-border-active);
    background: var(--color-surface);
    color: var(--color-text-primary);
  }

  dialog.danger {
    border-color: var(--color-danger);
  }

  dialog::backdrop {
    background: rgb(0 0 0 / 60%);
  }

  h2 {
    margin: 0 0 var(--space-md);
    font-size: var(--type-title-size);
    line-height: var(--type-title-line-height);
    font-weight: var(--type-title-weight);
  }
</style>
