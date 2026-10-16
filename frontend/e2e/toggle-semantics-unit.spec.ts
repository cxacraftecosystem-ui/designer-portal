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
