import { render, screen } from "@testing-library/svelte";
import { describe, expect, it } from "vitest";
import App from "./App.svelte";

describe("App shell", () => {
  it("shows the empty state with a disabled, described primary action", () => {
    render(App);
    expect(screen.getByRole("heading", { level: 1 })).toBeTruthy();
    const button = screen.getByRole("button") as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(button.getAttribute("aria-describedby")).toBe("add-hint");
  });
});
