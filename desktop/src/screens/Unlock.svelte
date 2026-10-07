<script lang="ts">
  import Button from "../lib/components/Button.svelte";
  import Field from "../lib/components/Field.svelte";
  import { api, errorCode } from "../lib/api";
  import { t } from "../lib/i18n";
  import { PRODUCT_NAME } from "../lib/brand";

  interface Props {
    ondone: () => void;
  }

  let { ondone }: Props = $props();
  let password = $state("");
  let working = $state(false);
  let error = $state("");

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    working = true;
    error = "";
    try {
      await api.unlock(password);
      password = "";
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
    <h1>{t("unlock.title", { product: PRODUCT_NAME })}</h1>
    <Field
      id="unlock-pw"
      type="password"
      label={t("unlock.password")}
      bind:value={password}
      autocomplete="current-password"
    />
    {#if error}<p class="error" role="alert">{error}</p>{/if}
    <Button variant="primary" type="submit" disabled={working || password.length === 0}>
      {working ? t("unlock.working") : t("unlock.submit")}
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
    width: min(400px, 100%);
    display: flex;
    flex-direction: column;
    gap: var(--space-md);
  }

  h1 {
    margin: 0;
    font-size: var(--type-title-size);
    line-height: var(--type-title-line-height);
    font-weight: var(--type-title-weight);
  }

  .error {
    margin: 0;
    color: var(--color-danger);
  }
</style>
