// SPDX-License-Identifier: GPL-3.0-or-later
import { en } from "./en";
import { fr, type MessageKey, type Messages } from "./fr";

export type Locale = "fr" | "en";
export type { MessageKey };

const catalogs: Record<Locale, Messages> = { fr, en };

/** French is the default; English is used only when the OS prefers it. */
export function resolveLocale(preferred: readonly string[]): Locale {
  for (const tag of preferred) {
    const base = tag.toLowerCase().split("-")[0];
    if (base === "fr") return "fr";
    if (base === "en") return "en";
  }
  return "fr";
}

export type Translate = (key: MessageKey, params?: Record<string, string | number>) => string;

/** Returns a translator bound to a locale. `{name}` placeholders are replaced from `params`. */
export function translator(locale: Locale): Translate {
  const catalog = catalogs[locale];
  return (key, params = {}) =>
    catalog[key].replace(/\{(\w+)\}/g, (match, name: string) => {
      const value = params[name];
      return value === undefined ? match : String(value);
    });
}

export const catalogKeys = (locale: Locale): string[] => Object.keys(catalogs[locale]).sort();
