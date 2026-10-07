import { render, screen } from "@testing-library/svelte";
import { beforeEach, describe, expect, it, vi } from "vitest";

const invoke = vi.fn();
vi.mock("@tauri-apps/api/core", () => ({ invoke: (...args: unknown[]) => invoke(...args) }));
vi.mock("@tauri-apps/api/event", () => ({ listen: vi.fn(async () => () => undefined) }));

import App from "./App.svelte";

const status = (vaultExists: boolean, unlocked: boolean) => ({
  vaultExists,
  unlocked,
  connection: "idle",
  productName: "Bastion",
  version: "0.1.0",
  protocolVersion: 1,
});

describe("App", () => {
  beforeEach(() => invoke.mockReset());

  it("starts the setup flow when no vault exists", async () => {
    invoke.mockImplementation(async (cmd: string) =>
      cmd === "app_status" ? status(false, false) : null,
    );
    render(App);
    expect(await screen.findByRole("heading", { level: 1 })).toBeTruthy();
    expect(screen.getAllByRole("radio")).toHaveLength(2);
  });

  it("asks for the master password when locked", async () => {
    invoke.mockImplementation(async () => status(true, false));
    render(App);
    expect(await screen.findByLabelText(/mot de passe|password/i)).toBeTruthy();
  });

  it("shows the empty state once unlocked without devices", async () => {
    invoke.mockImplementation(async (cmd: string) => {
      if (cmd === "app_status") return status(true, true);
      if (cmd === "devices") return [];
      if (cmd === "settings") return { onlineMap: true };
      return null;
    });
    render(App);
    expect(
      await screen.findByRole("button", { name: /ajouter un téléphone|add a phone/i }),
    ).toBeTruthy();
  });
});
