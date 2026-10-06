import { describe, expect, it } from "vitest";
import { catalogKeys, resolveLocale, translator } from "./index";

describe("i18n", () => {
  it("has identical keys in every locale", () => {
    expect(catalogKeys("en")).toEqual(catalogKeys("fr"));
  });

  it("defaults to French", () => {
    expect(resolveLocale([])).toBe("fr");
    expect(resolveLocale(["de-DE"])).toBe("fr");
  });

  it("picks the first supported preferred language", () => {
    expect(resolveLocale(["en-GB", "fr-FR"])).toBe("en");
    expect(resolveLocale(["fr-CA"])).toBe("fr");
  });

  it("interpolates parameters and keeps unknown placeholders", () => {
    const t = translator("fr");
    expect(t("app.footer.protocol", { version: 1 })).toBe("Protocole v1");
    expect(t("app.footer.protocol")).toBe("Protocole v{version}");
  });
});
