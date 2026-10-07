<script lang="ts">
  import { onMount } from "svelte";
  import Setup from "./screens/Setup.svelte";
  import Shell from "./screens/Shell.svelte";
  import Unlock from "./screens/Unlock.svelte";
  import { api, type AppStatus } from "./lib/api";
  import { t } from "./lib/i18n";

  let status: AppStatus | undefined = $state();
  let failed = $state(false);

  async function load() {
    try {
      status = await api.status();
      failed = false;
    } catch {
      failed = true;
    }
  }

  onMount(load);
</script>

{#if !status}
  <p class="loading" role="status">{failed ? t("error.internal") : t("app.loading")}</p>
{:else if !status.vaultExists}
  <Setup ondone={load} />
{:else if !status.unlocked}
  <Unlock ondone={load} />
{:else}
  <Shell {status} onlocked={load} />
{/if}

<style>
  .loading {
    margin: 0;
    height: 100%;
    display: grid;
    place-items: center;
    color: var(--color-text-secondary);
  }
</style>
