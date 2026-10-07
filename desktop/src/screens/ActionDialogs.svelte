<script lang="ts" module>
  export type DialogKind = "lock" | "unpair" | "forget" | "lost" | "wipe";
</script>

<script lang="ts">
  import { onDestroy } from "svelte";
  import Button from "../lib/components/Button.svelte";
  import Field from "../lib/components/Field.svelte";
  import Modal from "../lib/components/Modal.svelte";
  import { api, errorCode, type ContactInput } from "../lib/api";
  import { t } from "../lib/i18n";

  interface Props {
    kind: DialogKind;
    deviceId: string;
    onclose: (sent: boolean) => void;
  }

  let { kind, deviceId, onclose }: Props = $props();

  let password = $state("");
  let message = $state("");
  let phone = $state("");
  let email = $state("");
  let working = $state(false);
  let error = $state("");
  // Wipe: two acknowledgements, a keyword and a backend-enforced 30 s delay.
  let ack1 = $state(false);
  let ack2 = $state(false);
  let keyword = $state("");
  let external = $state(false);
  let armedUntil: number | undefined = $state();
  let now = $state(Date.now());
  const timer = setInterval(() => (now = Date.now()), 500);
  onDestroy(() => clearInterval(timer));

  const titles: Record<DialogKind, string> = {
    lock: t("sensitive.title.lock"),
    unpair: t("sensitive.title.unpair"),
    forget: t("sensitive.title.forget"),
    lost: t("lost.title"),
    wipe: t("wipe.title"),
  };

  const wipeKeyword = t("wipe.keywordValue");
  const secondsLeft = $derived(armedUntil ? Math.max(0, Math.ceil((armedUntil - now) / 1000)) : 0);
  const contactInput = (): ContactInput | null =>
    message.trim() || phone.trim() || email.trim() ? { message, phone, email } : null;

  async function run(action: () => Promise<unknown>) {
    working = true;
    error = "";
    try {
      await action();
      password = "";
      onclose(true);
    } catch (e) {
      error = t(`error.${errorCode(e)}`);
    } finally {
      working = false;
    }
  }

  async function arm() {
    try {
      armedUntil = await api.armWipe(deviceId);
    } catch (e) {
      error = t(`error.${errorCode(e)}`);
    }
  }

  function cancel() {
    if (armedUntil) void api.disarmWipe(deviceId).catch(() => undefined);
    password = "";
    onclose(false);
  }

  function submit(event: SubmitEvent) {
    event.preventDefault();
    switch (kind) {
      case "lost":
        return run(() =>
          api.sendCommand(deviceId, { kind: "lostMode", enabled: true, contact: contactInput() }),
        );
      case "lock":
        return run(() =>
          api.sendSensitive(deviceId, password, { kind: "lock", contact: contactInput() }),
        );
      case "unpair":
        return run(() => api.sendSensitive(deviceId, password, { kind: "unpair" }));
      case "forget":
        return run(() => api.forgetDevice(deviceId, password));
      case "wipe":
        return run(() =>
          api.sendSensitive(deviceId, password, { kind: "wipe", includeExternalStorage: external }),
        );
    }
  }

  const wipeReady = $derived(
    ack1 && ack2 && keyword.trim() === wipeKeyword && armedUntil !== undefined && secondsLeft === 0,
  );
</script>

<Modal title={titles[kind]} onclose={cancel} danger={kind === "wipe"}>
  <form onsubmit={submit} class="form">
    {#if kind === "lost" || kind === "lock"}
      <p>{kind === "lost" ? t("lost.body") : t("sensitive.body.lock")}</p>
      <Field
        id="c-msg"
        label={t("contact.message")}
        bind:value={message}
        multiline
        maxlength={280}
        placeholder={t("contact.messagePlaceholder")}
      />
      <Field id="c-phone" label={t("contact.phone")} bind:value={phone} maxlength={64} />
      <Field id="c-email" label={t("contact.email")} bind:value={email} maxlength={128} />
    {:else if kind === "unpair"}
      <p>{t("sensitive.body.unpair")}</p>
    {:else if kind === "forget"}
      <p>{t("sensitive.body.forget")}</p>
    {:else}
      <p class="warning">{t("wipe.warning")}</p>
      <label class="check"><input type="checkbox" bind:checked={ack1} /> {t("wipe.confirm1")}</label
      >
      <label class="check"><input type="checkbox" bind:checked={ack2} /> {t("wipe.confirm2")}</label
      >
      <label class="check"
        ><input type="checkbox" bind:checked={external} /> {t("wipe.external")}</label
      >
      <Field
        id="wipe-kw"
        label={t("wipe.keyword", { keyword: wipeKeyword })}
        bind:value={keyword}
        mono
      />
      {#if armedUntil === undefined}
        <Button
          variant="danger"
          disabled={!ack1 || !ack2 || keyword.trim() !== wipeKeyword}
          onclick={arm}>{t("wipe.arm")}</Button
        >
      {:else if secondsLeft > 0}
        <p class="countdown" aria-live="polite">{t("wipe.countdown", { seconds: secondsLeft })}</p>
      {:else}
        <p class="countdown">{t("wipe.ready")}</p>
      {/if}
    {/if}

    {#if kind !== "lost" && (kind !== "wipe" || (armedUntil !== undefined && secondsLeft === 0))}
      <Field
        id="reauth"
        type="password"
        label={t("sensitive.password")}
        bind:value={password}
        autocomplete="current-password"
      />
    {/if}

    {#if error}<p class="error" role="alert">{error}</p>{/if}
    <div class="actions">
      <Button onclick={cancel}>{t("action.cancel")}</Button>
      {#if kind === "wipe"}
        <Button variant="danger" type="submit" disabled={working || !wipeReady || !password}>
          {t("wipe.send")}
        </Button>
      {:else}
        <Button
          variant={kind === "lost" ? "primary" : "danger"}
          type="submit"
          disabled={working || (kind !== "lost" && !password)}
        >
          {kind === "lost" ? t("lost.enable") : t("action.confirm")}
        </Button>
      {/if}
    </div>
  </form>
</Modal>

<style>
  .form {
    display: flex;
    flex-direction: column;
    gap: var(--space-md);
  }

  p {
    margin: 0;
    color: var(--color-text-secondary);
  }

  .warning {
    color: var(--color-danger);
  }

  .check {
    display: flex;
    gap: var(--space-xs);
    align-items: center;
  }

  .countdown {
    color: var(--color-warning);
  }

  .error {
    color: var(--color-danger);
  }

  .actions {
    display: flex;
    justify-content: flex-end;
    gap: var(--space-sm);
  }
</style>
