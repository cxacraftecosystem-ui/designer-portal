import { readFileSync } from "node:fs";
import path from "node:path";

import { expect, test } from "@playwright/test";

import { TOGGLE_KEYS, switchAria, toggleOutcome } from "@/components/ui/toggleKeys";

/**
 * `Toggle` — the one on/off control for a setting (owner's ruling, 2026-10-17). A real switch: what a
 * screen reader is told, what a press does, and that it is a `<button>` (so Space and Enter work
 * natively) rather than a checkbox or a pressed button.
 */

const ROOT = path.resolve(__dirname, "..");
const source = (relative: string) => readFileSync(path.join(ROOT, relative), "utf8");

test("a switch says role=switch and aria-checked, and aria-busy only while it is changing", () => {
  expect(switchAria(true, false)).toEqual({ role: "switch", "aria-checked": true });
  expect(switchAria(false, false)).toEqual({ role: "switch", "aria-checked": false });
  expect(switchAria(false, true)).toEqual({ role: "switch", "aria-checked": false, "aria-busy": true });
});

test("a press flips it, and a disabled or busy switch refuses the press", () => {
  expect(toggleOutcome(false, {})).toBe(true);
  expect(toggleOutcome(true, {})).toBe(false);
  expect(toggleOutcome(false, { disabled: true })).toBeNull();
  expect(toggleOutcome(true, { busy: true })).toBeNull();
});

test("Space and Enter are the keys, and they come from a real <button>, not a keydown handler", () => {
  expect([...TOGGLE_KEYS]).toEqual([" ", "Enter"]);
  const toggle = source("components/ui/Toggle.tsx");
  expect(toggle).toContain('type="button"');
  expect(toggle).toContain("{...switchAria(checked, busy)}");
  // A hand-written keydown flip on top of the native click is how a switch flips twice.
  expect(toggle).not.toMatch(/onKeyDown=/);
  // At least 44px tall on a phone, and the row is the target.
  expect(toggle).toContain("min-h-11");
});

test("the settings switch is a switch too — never aria-pressed again", () => {
  const settings = source("components/settings/Toggle.tsx");
  expect(settings).toContain("ToggleSwitch as Toggle");
  expect(source("components/ui/Toggle.tsx")).not.toContain("aria-pressed");
});

test("FormData sees exactly what a checkbox sent: 'on' while on, nothing while off", () => {
  const toggle = source("components/ui/Toggle.tsx");
  expect(toggle).toContain('{name && checked ? <input type="hidden" name={name} value="on" /> : null}');
});

/*
 * WCAG 1.4.11: the parts of a switch that say its state need 3:1 against what is around them. The
 * hexes are globals.css's tokens (light :root, dark [data-theme="dark"]); the classes are the ones
 * Toggle.tsx exports and draws, so a colour change that fails the bar fails here.
 */
const TOKENS = {
  light: { card: "#ffffff", "ink-500": "#615d7a", "field-200": "#e9e6f5", "purple-700": "#762cb1", white: "#ffffff" },
  dark: { card: "#1a1725", "ink-500": "#9e98b2", "field-200": "#2e2840", "purple-400": "#c090f5", "purple-950": "#2f0d4b" }
} as const;

function luminance(hex: string): number {
  const channels = [1, 3, 5].map((at) => parseInt(hex.slice(at, at + 2), 16) / 255);
  const [r, g, b] = channels.map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

test("an Off switch and an On switch both stand out from the card at 3:1, in light and dark", () => {
  const drawn = source("components/ui/Toggle.tsx");
  expect(drawn).toContain('export const SWITCH_TRACK_OFF = "border-ink-500 bg-field-200";');
  expect(drawn).toContain('export const SWITCH_THUMB_OFF = "bg-ink-500";');
  expect(drawn).toContain(
    'export const SWITCH_TRACK_ON = "border-purple-700 bg-purple-700 dark:border-purple-400 dark:bg-purple-400";'
  );
  expect(drawn).toContain('export const SWITCH_THUMB_ON = "bg-white dark:bg-purple-950";');

  for (const mode of ["light", "dark"] as const) {
    const t = TOKENS[mode];
    expect(contrast(t["ink-500"], t.card), `${mode} Off edge vs card`).toBeGreaterThanOrEqual(3);
    expect(contrast(t["ink-500"], t["field-200"]), `${mode} Off thumb vs track`).toBeGreaterThanOrEqual(3);
  }
  expect(contrast(TOKENS.light["purple-700"], TOKENS.light.card), "light On track vs card").toBeGreaterThanOrEqual(3);
  expect(contrast(TOKENS.light.white, TOKENS.light["purple-700"]), "light On thumb vs track").toBeGreaterThanOrEqual(3);
  expect(contrast(TOKENS.dark["purple-400"], TOKENS.dark.card), "dark On track vs card").toBeGreaterThanOrEqual(3);
  expect(contrast(TOKENS.dark["purple-950"], TOKENS.dark["purple-400"]), "dark On thumb vs track").toBeGreaterThanOrEqual(3);

  // Both switches draw from the constants, and the 1.2:1 Off look is gone.
  const toggle = source("components/ui/Toggle.tsx");
  expect(toggle.match(/checked \? SWITCH_TRACK_ON : SWITCH_TRACK_OFF/g)?.length, "Toggle and ToggleSwitch").toBe(2);
  expect(toggle).not.toContain('"border-line-200 bg-field-200"');
});
