import type { TwoFactorStatus } from "@/lib/twoFactor";

export type TwoStepPhase = "idle" | "setup" | "codes" | "disable" | "regenerate";

export type TwoStepSwitchView = {
  checked: boolean;
  busy: boolean;
  busyLabel: string | null;
  statusLine: string;
};

export function formatOnSince(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return null;
  return date.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" });
}

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

export const SETUP_AWAY_LIMIT_MS = 5 * 60 * 1000;

export function leavingSetupDiscards(
  event: "cancel" | "switch-off" | "unmount" | "pagehide" | "hidden",
  awayMs = 0
): boolean {
  if (event === "hidden") return awayMs > SETUP_AWAY_LIMIT_MS;
  return true;
}
