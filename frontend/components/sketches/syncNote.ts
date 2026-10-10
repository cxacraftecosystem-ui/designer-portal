/**
 * What to say about a sync pass that has just returned — WHICH IS NOT ALWAYS "sent".
 *
 * ── WHY THIS IS ONE FUNCTION AND NOT A SENTENCE AT EACH CALL SITE ────────────────────────────────
 *
 * `DwSyncResult` is more than a yes or a no, and reading it as "did it work" is wrong in two shapes
 * that both look like success (it said "six fields" until two optional ones arrived on 2026-10-09
 * — the session stops below):
 *
 *   * `declinedResult()` in `lib/designWorkshopStore.ts` is returned whenever ANOTHER TAB holds the
 *     `SYNC_LOCK` — `failed: 0`, `stoppedOffline: false`, and a deliberately honest `pending` count.
 *     Nothing of this caller's was carried; the other tab is carrying it.
 *   * `syncDesignWorkshopDrafts()` hands back an ALREADY-RUNNING pass, which may have begun before
 *     the thing this caller just wrote was written.
 *
 * Announcing "sent to the repository" for either one tells a designer their work is safe in the
 * ministry's database when it is sitting in IndexedDB. `stagesSent` and `pending` are on the result
 * and say which happened, so they are read.
 *
 * ── AND WHY IT IS SHARED ─────────────────────────────────────────────────────────────────────────
 *
 * This logic was written for the REVIEW tab's arrangement save, and the UPLOAD tab needed exactly
 * it: both write into the local draft with `putDraftStage` and then ask the same pass to carry the
 * result up, so both have the same outcomes to report and the same two ways of being wrong about
 * them. A second copy would drift — and the direction it would drift in is over-claiming,
 * because "sent" is the short sentence and the honest ones are long.
 *
 * The caller supplies the SUBJECT because only it knows what was written. It is a noun phrase
 * carrying its own verb ("this arrangement is", "this file is"): the sentence it lands in reads
 * "…so this file is going up with that pass rather than this one", and a subject without the verb
 * would force this file to guess at number and agreement for phrases it has never seen.
 */

import type { DwSyncResult } from "@/lib/designWorkshopStore";

/** True when the repository has demonstrably taken everything this device was holding. */
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
  /*
    TWO STOPS THAT ARE ABOUT THE SESSION, AND BOTH MUST BE ASKED BEFORE `pending`. Each pass reports
    an honest pending count and nothing sent, which the branch below would read as "another sync is
    already running" — a third explanation, and a false one. The work is banked; what sends it is a
    new password or a new sign-in, and the sentence says which.
  */
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
