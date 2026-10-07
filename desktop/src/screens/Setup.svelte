<script lang="ts">
  import { onMount } from "svelte";
  import Button from "../lib/components/Button.svelte";
  import Field from "../lib/components/Field.svelte";
  import { api, errorCode, type RelaySetup } from "../lib/api";
  import { t } from "../lib/i18n";
  import { PRODUCT_NAME } from "../lib/brand";

  interface Props {
    ondone: () => void;
  }

  let { ondone }: Props = $props();

  let password = $state("");
  let confirm = $state("");
  let mode: "embedded" | "remote" = $state("embedded");
  let port = $state("8443");
  let host = $state("");
  let detected = $state("—");
  let url = $state("");
  let pin = $state("");
  let token = $state("");
  let working = $state(false);
  let error = $state("");

  onMount(async () => {
    detected = (await api.suggestedHost()) ?? "—";
  });

  const mismatch = $derived(confirm.length > 0 && password !== confirm);

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    error = "";
    if (password !== confirm) {
      error = t("setup.passwordMismatch");
      return;
    }
    const relay: RelaySetup =
      mode === "embedded"
        ? { mode, port: Number.parseInt(port, 10), advertisedHost: host.trim() || null }
        : { mode, url: url.trim(), pinHex: pin.trim(), adminToken: token.trim() };
    working = true;
    try {
      await api.setup(password, relay);
      password = "";
      confirm = "";
      ondone();
    } catch (e) {
      error = t(`error.${errorCode(e)}`);
    } finally {
      working = false;
    }
  }
</script>

<main class="center">
  <form class="card" onsubmit={submit}>
    <h1>{t("setup.title", { product: PRODUCT_NAME })}</h1>
    <p class="intro">{t("setup.intro")}</p>

    <Field
      id="pw"
      type="password"
      label={t("setup.password")}
      bind:value={password}
      autocomplete="new-password"
      hint={t("setup.passwordHint")}
    />
    <Field
      id="pw2"
      type="password"
      label={t("setup.passwordConfirm")}
      bind:value={confirm}
      autocomplete="new-password"
    />
    {#if mismatch}<p class="error" role="alert">{t("setup.passwordMismatch")}</p>{/if}

    <fieldset>
      <legend>{t("setup.relay")}</legend>
      <label class="choice">
        <input type="radio" bind:group={mode} value="embedded" />
        <span>
          <strong>{t("setup.relay.embedded")}</strong>
          <small>{t("setup.relay.embeddedHint")}</small>
        </span>
      </label>
      <label class="choice">
        <input type="radio" bind:group={mode} value="remote" />
        <span>
          <strong>{t("setup.relay.remote")}</strong>
          <small>{t("setup.relay.remoteHint")}</small>
        </span>
      </label>
    </fieldset>

    {#if mode === "embedded"}
      <div class="row">
        <Field id="port" type="number" label={t("setup.port")} bind:value={port} mono />
        <Field
          id="host"
          label={t("setup.host")}
          bind:value={host}
          placeholder={detected}
          hint={t("setup.hostHint", { host: detected })}
          mono
        />
      </div>
    {:else}
      <Field
        id="url"
        type="url"
        label={t("setup.url")}
        bind:value={url}
        placeholder="https://relay.example.org:8443"
        mono
      />
      <Field id="pin" label={t("setup.pin")} bind:value={pin} maxlength={64} mono />
      <Field id="token" type="password" label={t("setup.adminToken")} bind:value={token} mono />
    {/if}

    {#if error}<p class="error" role="alert">{error}</p>{/if}
    <Button variant="primary" type="submit" disabled={working || password.length === 0}>
      {working ? t("setup.working") : t("setup.submit")}
    </Button>
  </form>
</main>

<style>
  .center {
    min-height: 100%;
    display: grid;
    place-items: center;
    padding: var(--space-xl);
  }

  .card {
    width: min(560px, 100%);
    display: flex;
    flex-direction: column;
    gap: var(--space-md);
  }

  h1 {
    margin: 0;
    font-size: var(--type-display-size);
    line-height: var(--type-display-line-height);
    font-weight: var(--type-display-weight);
    letter-spacing: var(--type-display-tracking);
  }

  .intro {
    margin: 0;
    color: var(--color-text-secondary);
  }

  fieldset {
    border: none;
    padding: 0;
    margin: 0;
    display: flex;
    flex-direction: column;
    gap: var(--space-xs);
  }

  legend {
    font-size: var(--type-label-size);
    color: var(--color-text-secondary);
    margin-bottom: var(--space-xs);
  }

  .choice {
    display: flex;
    gap: var(--space-sm);
    align-items: flex-start;
    padding: var(--space-sm);
    border-radius: var(--radius-md);
    border: var(--border-width) solid var(--color-border);
    cursor: pointer;
  }

  .choice:has(input:checked) {
    border-color: var(--color-accent);
  }

  .choice span {
    display: flex;
    flex-direction: column;
    gap: var(--space-xxs);
  }

  small {
    color: var(--color-text-secondary);
    font-size: var(--type-caption-size);
    line-height: var(--type-caption-line-height);
  }

  .row {
    display: grid;
    grid-template-columns: 120px 1fr;
    gap: var(--space-sm);
  }

  .error {
    margin: 0;
    color: var(--color-danger);
  }
</style>
