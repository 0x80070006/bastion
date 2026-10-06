<script lang="ts">
  import Button from "./lib/components/Button.svelte";
  import { PRODUCT_NAME } from "./lib/brand";
  import { resolveLocale, translator } from "./lib/i18n";

  // Mirrors bastion_proto::PROTOCOL_VERSION; replaced by the get_app_info command in milestone 6.
  const PROTOCOL_VERSION = 1;
  const t = translator(resolveLocale(navigator.languages));
</script>

<div class="shell">
  <nav aria-label={PRODUCT_NAME}>
    <span class="wordmark">{PRODUCT_NAME}</span>
    <ul>
      <li><a href="#dashboard" aria-current="page">{t("nav.dashboard")}</a></li>
      <li><a href="#journal">{t("nav.journal")}</a></li>
      <li><a href="#settings">{t("nav.settings")}</a></li>
    </ul>
    <span class="footer mono">{t("app.footer.protocol", { version: PROTOCOL_VERSION })}</span>
  </nav>

  <main>
    <section class="empty" aria-labelledby="empty-title">
      <h1 id="empty-title">{t("app.status.noDevice")}</h1>
      <p>{t("app.status.noDeviceHint")}</p>
      <Button variant="primary" disabled describedBy="add-hint">{t("app.action.addPhone")}</Button>
      <p id="add-hint" class="hint">{t("app.action.addPhoneUnavailable")}</p>
    </section>
  </main>
</div>

<style>
  .shell {
    display: grid;
    grid-template-columns: 240px 1fr;
    height: 100%;
  }

  nav {
    display: flex;
    flex-direction: column;
    gap: var(--space-xl);
    padding: var(--space-lg);
    border-right: var(--border-width) solid var(--color-border);
    background: var(--color-surface);
  }

  .wordmark {
    font-size: var(--type-title-size);
    line-height: var(--type-title-line-height);
    font-weight: var(--type-title-weight);
    letter-spacing: var(--type-title-tracking);
  }

  ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: var(--space-xxs);
  }

  a {
    display: flex;
    align-items: center;
    min-height: 40px;
    padding: 0 var(--space-sm);
    border-radius: var(--radius-sm);
    color: var(--color-text-secondary);
    text-decoration: none;
    font-size: var(--type-label-size);
    font-weight: var(--type-label-weight);
  }

  a[aria-current="page"] {
    color: var(--color-text-primary);
    background: var(--color-surface-raised);
  }

  .footer {
    margin-top: auto;
    color: var(--color-text-secondary);
    font-size: var(--type-caption-size);
  }

  main {
    display: grid;
    place-items: center;
    padding: var(--space-xxl);
  }

  .empty {
    max-width: 420px;
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-md);
  }

  h1 {
    margin: 0;
    font-size: var(--type-title-size);
    line-height: var(--type-title-line-height);
    font-weight: var(--type-title-weight);
    letter-spacing: var(--type-title-tracking);
  }

  p {
    margin: 0;
    color: var(--color-text-secondary);
  }

  .hint {
    font-size: var(--type-caption-size);
    line-height: var(--type-caption-line-height);
  }
</style>
