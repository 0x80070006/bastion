// SPDX-License-Identifier: GPL-3.0-or-later
import type { MessageKey, Translate } from "./i18n";
import type { JournalEntry } from "./api";

/** "il y a 5 min" style relative time. */
export function relativeTime(t: Translate, timeMs: number, nowMs: number): string {
  const minutes = Math.floor(Math.max(0, nowMs - timeMs) / 60_000);
  if (minutes < 1) return t("time.justNow");
  if (minutes < 60) return t("time.minutes", { n: minutes });
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return t("time.hours", { n: hours });
  return t("time.days", { n: Math.floor(hours / 24) });
}

/** Absolute local date-time. */
export function dateTime(locale: string, timeMs: number): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: "short", timeStyle: "medium" }).format(
    new Date(timeMs),
  );
}

function known(t: Translate, key: string, fallback: MessageKey): string {
  // Keys come from the backend; only translate those that exist in the catalog.
  const value = t(key as MessageKey);
  return value === undefined || value === key ? t(fallback) : value;
}

/** Human description of a journal entry. */
export function journalText(t: Translate, entry: JournalEntry): string {
  const [group, name] = splitKind(entry.kind);
  if (group === "alert")
    return t("journal.kind.alert", { alert: known(t, `alert.${name}`, "alert.unknown") });
  if (group === "beacon")
    return t("journal.kind.beacon", { reason: known(t, `beacon.${name}`, "beacon.unknown") });
  if (group === "result") {
    const command = entry.detail?.split(":")[0] ?? "unknown";
    const status = known(t, `status.${name}`, "status.unknown");
    return `${known(t, `command.${command}`, "command.unknown")} — ${t("journal.kind.result", { status })}`;
  }
  if (entry.kind === "command.sent") {
    return `${t("journal.kind.command.sent")} : ${known(t, `command.${entry.detail ?? ""}`, "command.unknown")}`;
  }
  return known(t, `journal.kind.${entry.kind}`, "command.unknown");
}

function splitKind(kind: string): [string, string] {
  const dot = kind.indexOf(".");
  return dot < 0 ? [kind, ""] : [kind.slice(0, dot), kind.slice(dot + 1)];
}

/** Tile URL template for the backend tile proxy (custom `tiles:` scheme). */
export function tileUrlTemplate(userAgent: string): string {
  const base = /Windows/i.test(userAgent) ? "http://tiles.localhost" : "tiles://localhost";
  return `${base}/{z}/{x}/{y}.png`;
}
