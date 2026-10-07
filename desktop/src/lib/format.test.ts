import { describe, expect, it } from "vitest";
import { journalText, relativeTime, tileUrlTemplate } from "./format";
import { translator } from "./i18n";
import { errorCode } from "./api";

const t = translator("fr");

describe("format", () => {
  it("formats relative times", () => {
    expect(relativeTime(t, 0, 30_000)).toBe("à l'instant");
    expect(relativeTime(t, 0, 5 * 60_000)).toBe("il y a 5 min");
    expect(relativeTime(t, 0, 3 * 3_600_000)).toBe("il y a 3 h");
    expect(relativeTime(t, 0, 72 * 3_600_000)).toBe("il y a 3 j");
  });

  it("describes journal entries, including unknown codes", () => {
    const base = { timeMs: 0, deviceId: null, deviceLabel: null, detail: null };
    expect(journalText(t, { ...base, kind: "alert.simRemoved" })).toBe(
      "Alerte : carte SIM retirée",
    );
    expect(journalText(t, { ...base, kind: "alert.somethingNew" })).toBe("Alerte : inconnue");
    expect(journalText(t, { ...base, kind: "result.completed", detail: "ring" })).toBe(
      "Sonnerie — Résultat : exécutée",
    );
  });

  it("uses the platform tile scheme", () => {
    expect(tileUrlTemplate("Mozilla/5.0 (Windows NT 10.0)")).toBe(
      "http://tiles.localhost/{z}/{x}/{y}.png",
    );
    expect(tileUrlTemplate("Linux")).toBe("tiles://localhost/{z}/{x}/{y}.png");
  });

  it("never trusts arbitrary error payloads", () => {
    expect(errorCode("wrong_password")).toBe("wrong_password");
    expect(errorCode("<img src=x>")).toBe("internal");
    expect(errorCode(new Error("x"))).toBe("internal");
  });
});
