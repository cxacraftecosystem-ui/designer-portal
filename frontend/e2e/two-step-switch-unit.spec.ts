import { readFileSync } from "node:fs";
import path from "node:path";

import { expect, test } from "@playwright/test";

import type { TwoFactorStatus } from "@/lib/twoFactor";
import {
  SETUP_AWAY_LIMIT_MS,
  formatOnSince,
  leavingSetupDiscards,
  recoveryCodesLeft,
  twoStepSwitchView
} from "@/lib/twoStepSwitch";

/**
 * THE TWO-STEP VERIFICATION SWITCH TELLS THE TRUTH (owner's report, 2026-10-17): it was switched on in
 * the emulator, the code was never scanned or checked, and coming back the screen said it was on and
 * asked for an authenticator or recovery code. The switch now reads On only when the server says
 * `enabled` — i.e. `/enable` accepted a code — and a setup in progress or abandoned reads Off.
 */

const off: TwoFactorStatus = { enabled: false, pending: false, recovery_codes_remaining: 0, enabled_at: null };
const pending: TwoFactorStatus = { ...off, pending: true };
const on: TwoFactorStatus = {
  enabled: true,
  pending: false,
  recovery_codes_remaining: 8,
  enabled_at: "2026-10-17T09:30:00.000Z"
};

test("off is off, with the plain word under the switch", () => {
  expect(twoStepSwitchView(off, "idle")).toEqual({ checked: false, busy: false, busyLabel: null, statusLine: "Off" });
});

test("while setting up the switch is NOT on — it reads Setting up…", () => {
  const view = twoStepSwitchView(off, "setup");
  expect(view.checked).toBe(false);
  expect(view.busy).toBe(true);
  expect(view.busyLabel).toMatch(/^Setting up…/);
});

test("a setup left unfinished reads Off and offers to start again — never On", () => {
  const view = twoStepSwitchView(pending, "idle");
  expect(view.checked).toBe(false);
  expect(view.statusLine).toMatch(/^Off · You started setting this up and did not finish/);
});

test("on only when the server says enabled, with since-when and codes left in plain words", () => {
  const view = twoStepSwitchView(on, "idle");
  expect(view.checked).toBe(true);
  expect(view.statusLine).toBe("On since 17 Oct 2026 · 8 recovery codes left");
});

test("turning it off keeps it On until a code is accepted", () => {
  const view = twoStepSwitchView(on, "disable");
  expect(view.checked).toBe(true);
  expect(view.busyLabel).toBe("Enter a code to turn it off.");
});

test("the counts and the date read as words", () => {
  expect(recoveryCodesLeft(0)).toBe("No recovery codes left");
  expect(recoveryCodesLeft(1)).toBe("1 recovery code left");
  expect(recoveryCodesLeft(10)).toBe("10 recovery codes left");
  expect(formatOnSince("2026-10-17T09:30:00.000Z")).toBe("17 Oct 2026");
  expect(formatOnSince(null)).toBeNull();
  expect(formatOnSince("not a date")).toBeNull();
});

test("leaving setup discards it — a short hide (a look at the authenticator) does not, a long absence does", () => {
  expect(leavingSetupDiscards("cancel")).toBe(true);
  expect(leavingSetupDiscards("switch-off")).toBe(true);
  expect(leavingSetupDiscards("unmount")).toBe(true);
  expect(leavingSetupDiscards("pagehide")).toBe(true);
  // A short look at the authenticator app keeps the key; a long absence drops it.
  expect(leavingSetupDiscards("hidden")).toBe(false);
  expect(leavingSetupDiscards("hidden", 60_000)).toBe(false);
  expect(leavingSetupDiscards("hidden", SETUP_AWAY_LIMIT_MS)).toBe(false);
  expect(leavingSetupDiscards("hidden", SETUP_AWAY_LIMIT_MS + 1)).toBe(true);
});

test("the card cancels on pagehide, unmount and a long absence, and its switch is the server's enabled", () => {
  const card = readFileSync(path.resolve(__dirname, "../components/settings/TwoStepVerificationCard.tsx"), "utf8");
  expect(card).toContain('window.addEventListener("pagehide", onPageHide)');
  expect(card).toContain("cancelTwoFactorSetup({ keepalive: true })");
  // Hidden for longer than the limit is a leave; a shorter hide is a trip to the authenticator app.
  expect(card).toContain('document.addEventListener("visibilitychange", onVisibility)');
  expect(card).toContain('leavingSetupDiscards("hidden", away)');
  // A pending setup with none on this screen is cancelled on load, so a returning client reads Off.
  expect(card).toContain("if (!answer.enabled && answer.pending && !settingUp.current) {");
  expect(card).toContain("<Toggle");
  expect(card).toContain("<OtpCodeInput");
  const client = readFileSync(path.resolve(__dirname, "../lib/twoFactor.ts"), "utf8");
  expect(client).toContain('"/auth/two-factor/setup/cancel"');
});
