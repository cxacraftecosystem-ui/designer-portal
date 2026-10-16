/**
 * What the "Two-step verification" switch shows, decided in one pure place so the web card
 * (components/settings/TwoStepVerificationCard.tsx) and its unit spec (e2e/two-step-switch-unit.spec.ts)
 * cannot disagree — and Android's `TwoStepSwitch` (ui/TwoStepVerification.kt) is the same table.
 *
 * THE RULE THE OWNER REPORTED BROKEN (2026-10-17): the switch reads ON only when the SERVER says it is
 * on — `/enable` accepted a code. Starting setup, scanning nothing, and coming back later must read Off.
 * So `checked` is `status.enabled` and nothing else; a setup in progress is `busy` with the words
 * "Setting up…", never a checked switch; and a setup the server still holds as pending from an earlier
 * visit is offered as "start again", not shown as on.
 */

import type { TwoFactorStatus } from "@/lib/twoFactor";

/** Which part of the conversation the card is in. */
export type TwoStepPhase = "idle" | "setup" | "codes" | "disable" | "regenerate";

export type TwoStepSwitchView = {
  checked: boolean;
  busy: boolean;
  busyLabel: string | null;
  /** The plain-words line under the switch. */
  statusLine: string;
};

/** "17 Oct 2026" — day, short month, year, in every locale, so the line reads the same everywhere. */
export function formatOnSince(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return null;
  return date.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" });
}

/** "8 recovery codes left", "1 recovery code left", "No recovery codes left". */
export function recoveryCodesLeft(count: number): string {
  if (count <= 0) return "No recovery codes left";
  return `${count} recovery ${count === 1 ? "code" : "codes"} left`;
}

export function twoStepSwitchView(status: TwoFactorStatus | null, phase: TwoStepPhase): TwoStepSwitchView {
  const enabled = status?.enabled === true;
  if (phase === "setup") {
    return { checked: false, busy: true, busyLabel: "Setting up… Nothing is switched on until the code is checked.", statusLine: "Off" };
  }
  if (phase === "disable" && enabled) {
    return { checked: true, busy: true, busyLabel: "Enter a code to turn it off.", statusLine: onLine(status) };
  }
  if (!status) return { checked: false, busy: true, busyLabel: "Loading…", statusLine: "" };
  if (enabled) return { checked: true, busy: false, busyLabel: null, statusLine: onLine(status) };
  return {
    checked: false,
    busy: false,
    busyLabel: null,
    statusLine: status.pending
      ? "Off · You started setting this up and did not finish. Switch it on to start again."
      : "Off"
  };
}

function onLine(status: TwoFactorStatus | null): string {
  if (!status) return "On";
  const since = formatOnSince(status.enabled_at);
  return [since ? `On since ${since}` : "On", recoveryCodesLeft(status.recovery_codes_remaining)].join(" · ");
}

/**
 * Leaving setup without a confirmed code DISCARDS it (`POST /auth/two-factor/setup/cancel`): Cancel,
 * switching it back off, navigating away, closing or reloading the tab. NOT the tab being hidden: on a
 * phone the person leaves for their authenticator app in the middle of setup, and cancelling then would
 * throw away the key they are about to read a code from. The server's 15-minute limit covers the case
 * where they never come back.
 */
export function leavingSetupDiscards(event: "cancel" | "switch-off" | "unmount" | "pagehide" | "hidden"): boolean {
  return event !== "hidden";
}
