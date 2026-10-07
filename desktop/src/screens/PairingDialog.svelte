<script lang="ts">
  import { onDestroy, onMount, untrack } from "svelte";
  import Button from "../lib/components/Button.svelte";
  import Modal from "../lib/components/Modal.svelte";
  import { api, errorCode, type DeviceSummary, type PairingView } from "../lib/api";
  import { t } from "../lib/i18n";
  import { PRODUCT_NAME } from "../lib/brand";

  interface Props {
    devices: DeviceSummary[];
    onclose: () => void;
  }

  let { devices, onclose }: Props = $props();
  let invite: PairingView | undefined = $state();
  let error = $state("");
  let now = $state(Date.now());
  let showLink = $state(false);
  // Devices that existed before this dialog opened are not part of this pairing.
  const known = new Set(untrack(() => devices.map((d) => d.id)));
  let timer: ReturnType<typeof setInterval> | undefined;

  const remaining = $derived(
    invite ? Math.max(0, Math.ceil((invite.expiresAtMs - now) / 1000)) : 0,
  );
  // A device that appeared since the dialog opened and awaits the SAS check.
  const pending = $derived(devices.find((d) => d.state === "pendingSas" && !known.has(d.id)));
  const awaiting = $derived(devices.find((d) => d.state === "awaitingPhone" && !known.has(d.id)));
  const done = $derived(devices.find((d) => d.state === "active" && !known.has(d.id)));

  async function renew() {
    error = "";
    showLink = false;
    try {
      invite = await api.startPairing();
    } catch (e) {
      error = t(`error.${errorCode(e)}`);
    }
  }

  async function decide(id: string, accept: boolean) {
    try {
      await api.confirmPairing(id, accept);
      if (!accept) onclose();
    } catch (e) {
      error = t(`error.${errorCode(e)}`);
    }
  }

  function close() {
    void api.cancelPairing().catch(() => undefined);
    onclose();
  }

  onMount(() => {
    timer = setInterval(() => (now = Date.now()), 1000);
    void renew();
  });

  onDestroy(() => clearInterval(timer));

  $effect(() => {
    if (done) onclose();
  });
</script>

<Modal title={pending ? t("sas.title") : t("pairing.title")} onclose={close}>
  {#if pending}
    <p>{t("sas.body", { label: pending.label })}</p>
    <p class="sas mono" aria-live="polite">{pending.sas}</p>
    <p class="caption mono">{pending.fingerprint}</p>
    <div class="actions">
      <Button variant="danger" onclick={() => decide(pending.id, false)}>{t("sas.mismatch")}</Button
      >
      <Button variant="primary" onclick={() => decide(pending.id, true)}>{t("sas.match")}</Button>
    </div>
  {:else if awaiting}
    <p>{t("sas.awaiting")}</p>
    <p class="sas mono">{awaiting.sas}</p>
    <div class="actions"><Button onclick={onclose}>{t("action.close")}</Button></div>
  {:else}
    <ol>
      <li>{t("pairing.step1", { product: PRODUCT_NAME })}</li>
      <li>{t("pairing.step2", { seconds: remaining })}</li>
    </ol>
    {#if invite && remaining > 0}
      <img class="qr" src={invite.qrDataUrl} alt={t("pairing.title")} width="320" height="320" />
      <p class="caption">{t("pairing.endpoint", { endpoint: invite.endpoint })}</p>
      <p class="caption warn">{t("pairing.private")}</p>
      <p class="caption" aria-live="polite">{t("pairing.waiting")}</p>
      {#if showLink}
        <textarea class="mono link" readonly rows="4">{invite.uri}</textarea>
      {:else}
        <Button variant="ghost" onclick={() => (showLink = true)}>{t("pairing.link")}</Button>
      {/if}
    {:else if invite}
      <p>{t("pairing.expired")}</p>
    {/if}
    {#if error}<p class="error" role="alert">{error}</p>{/if}
    <div class="actions">
      <Button onclick={close}>{t("action.cancel")}</Button>
      {#if !invite || remaining === 0}
        <Button variant="primary" onclick={renew}>{t("pairing.renew")}</Button>
      {/if}
    </div>
  {/if}
</Modal>

<style>
  ol {
    margin: 0 0 var(--space-md);
    padding-left: var(--space-lg);
    color: var(--color-text-secondary);
  }

  .qr {
    display: block;
    margin: 0 auto var(--space-sm);
    border-radius: var(--radius-md);
  }

  .sas {
    margin: var(--space-md) 0;
    text-align: center;
    font-size: 40px;
    letter-spacing: 6px;
    color: var(--color-accent);
  }

  .caption {
    margin: 0 0 var(--space-xs);
    font-size: var(--type-caption-size);
    color: var(--color-text-secondary);
    text-align: center;
    word-break: break-all;
  }

  .warn {
    color: var(--color-warning);
  }

  .link {
    width: 100%;
    background: var(--color-background);
    color: var(--color-text-secondary);
    border: var(--border-width) solid var(--color-border);
    border-radius: var(--radius-sm);
    font-size: var(--type-caption-size);
    word-break: break-all;
  }

  .actions {
    display: flex;
    justify-content: flex-end;
    gap: var(--space-sm);
    margin-top: var(--space-md);
  }

  .error {
    color: var(--color-danger);
  }
</style>
