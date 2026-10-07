<script lang="ts">
  import { onMount } from "svelte";
  import Button from "../lib/components/Button.svelte";
  import Field from "../lib/components/Field.svelte";
  import { api, errorCode, type SettingsView } from "../lib/api";
  import { t } from "../lib/i18n";
  import { PRODUCT_NAME } from "../lib/brand";

  interface Props {
    onsaved: () => void;
  }

  let { onsaved }: Props = $props();
  let view: SettingsView | undefined = $state();
  let autoLock = $state("15");
  let onlineMap = $state(true);
  let host = $state("");
  let message = $state("");
  let isError = $state(false);

  onMount(async () => {
    view = await api.settings();
    autoLock = String(view.autoLockMinutes);
    onlineMap = view.onlineMap;
    host = view.advertisedHost ?? "";
  });

  async function save(event: SubmitEvent) {
    event.preventDefault();
    try {
      await api.updateSettings({
        autoLockMinutes: Number.parseInt(autoLock, 10) || 0,
        onlineMap,
        advertisedHost: host.trim() || null,
      });
      message = t("settings.saved");
      isError = false;
      view = await api.settings();
      onsaved();
    } catch (e) {
      message = t(`error.${errorCode(e)}`);
      isError = true;
    }
  }
</script>

<section aria-labelledby="settings-title">
  <h1 id="settings-title">{t("settings.title")}</h1>
  {#if view}
    <form onsubmit={save}>
      <Field
        id="autolock"
        type="number"
        label={t("settings.autoLock")}
        bind:value={autoLock}
        hint={t("settings.autoLockHint")}
      />
      <label class="check">
        <input type="checkbox" bind:checked={onlineMap} />
        <span>
          {t("settings.onlineMap")}
          <small>{t("settings.onlineMapHint")}</small>
        </span>
      </label>
      {#if view.relayMode === "embedded"}
        <Field id="host" label={t("settings.host")} bind:value={host} mono />
      {/if}
      {#if message}<p class:error={isError} role="status">{message}</p>{/if}
      <div><Button variant="primary" type="submit">{t("settings.save")}</Button></div>
    </form>

    <dl>
      <dt>{t("settings.relay")}</dt>
      <dd>
        {view.relayMode === "embedded" ? t("settings.relay.embedded") : t("settings.relay.remote")}
      </dd>
      <dt>{t("settings.endpoint")}</dt>
      <dd class="mono">{view.relayEndpoint ?? "—"}</dd>
      <dt>{t("settings.pin")}</dt>
      <dd class="mono">{view.relayPin || "—"}</dd>
      <dt>{t("settings.fingerprint")}</dt>
      <dd class="mono">{view.controllerFingerprint}</dd>
    </dl>
    <p class="note">{t("settings.tray", { product: PRODUCT_NAME })}</p>
  {/if}
</section>

<style>
  section {
    max-width: 640px;
    height: 100%;
    overflow-y: auto;
  }

  h1 {
    margin: 0 0 var(--space-md);
    font-size: var(--type-title-size);
    font-weight: var(--type-title-weight);
  }

  form {
    display: flex;
    flex-direction: column;
    gap: var(--space-md);
  }

  .check {
    display: flex;
    gap: var(--space-sm);
    align-items: flex-start;
  }

  .check span {
    display: flex;
    flex-direction: column;
  }

  small,
  .note {
    color: var(--color-text-secondary);
    font-size: var(--type-caption-size);
  }

  .error {
    color: var(--color-danger);
  }

  dl {
    margin: var(--space-xl) 0 var(--space-md);
    display: grid;
    grid-template-columns: 200px 1fr;
    gap: var(--space-xs) var(--space-md);
  }

  dt {
    color: var(--color-text-secondary);
    font-size: var(--type-label-size);
  }

  dd {
    margin: 0;
    word-break: break-all;
    font-size: var(--type-label-size);
  }
</style>
