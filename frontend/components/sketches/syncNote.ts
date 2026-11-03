import type { DwSyncResult } from "@/lib/designWorkshopStore";

export function syncPassLanded(result: DwSyncResult): boolean {
  return (
    !result.stoppedOffline &&
    !result.passwordChangeRequired &&
    !result.credentialExpired &&
    result.failed === 0 &&
    result.pending === 0
  );
}

export function syncPassNote(result: DwSyncResult, subject: string): string {
  if (result.stoppedOffline) {
    return "Saved on this device. It will upload when you're back online.";
  }
  if (result.failed > 0) {
    return "Saved on this device, but something in this workshop couldn't be uploaded — the sync banner says what.";
  }
  if (result.passwordChangeRequired) {
    return "Saved on this device. It will upload once your new password is set.";
  }
  if (result.credentialExpired) {
    return "Saved on this device. Your sign-in has expired, so it will upload once you sign in again.";
  }
  if (result.pending > 0) {
    return result.stagesSent === 0
      ? `Saved on this device. Another sync is already running, so ${subject} going up with that one — the sync banner shows its progress.`
      : "Saved on this device and a sync ran, but some work is still waiting to upload — the sync banner shows what.";
  }
  return "Saved, and uploaded.";
}
