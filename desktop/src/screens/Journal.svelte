<script lang="ts">
  import { api, type JournalEntry } from "../lib/api";
  import { dateTime, journalText } from "../lib/format";
  import { locale, t } from "../lib/i18n";

  interface Props {
    revision: number;
  }

  let { revision }: Props = $props();
  let entries: JournalEntry[] = $state([]);

  $effect(() => {
    void revision;
    api
      .journal()
      .then((e) => (entries = e))
      .catch(() => (entries = []));
  });
</script>

<section aria-labelledby="journal-title">
  <h1 id="journal-title">{t("journal.title")}</h1>
  {#if entries.length === 0}
    <p class="empty">{t("journal.empty")}</p>
  {:else}
    <ol>
      {#each entries as entry, i (`${entry.timeMs}-${i}`)}
        <li class:alert={entry.kind.startsWith("alert.") || entry.kind.startsWith("beacon.")}>
          <time class="mono">{dateTime(locale, entry.timeMs)}</time>
          <span class="device">{entry.deviceLabel ?? ""}</span>
          <span>{journalText(t, entry)}</span>
        </li>
      {/each}
    </ol>
  {/if}
</section>

<style>
  section {
    height: 100%;
    overflow-y: auto;
  }

  h1 {
    margin: 0 0 var(--space-md);
    font-size: var(--type-title-size);
    font-weight: var(--type-title-weight);
  }

  .empty {
    color: var(--color-text-secondary);
  }

  ol {
    list-style: none;
    margin: 0;
    padding: 0;
  }

  li {
    display: grid;
    grid-template-columns: 180px 160px 1fr;
    gap: var(--space-sm);
    padding: var(--space-xs) 0;
    border-bottom: var(--border-width) solid var(--color-border);
    font-size: var(--type-label-size);
  }

  li.alert {
    color: var(--color-warning);
  }

  time,
  .device {
    color: var(--color-text-secondary);
  }
</style>
