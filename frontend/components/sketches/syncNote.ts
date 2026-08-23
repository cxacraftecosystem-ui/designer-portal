/**
 * What to say about a sync pass that has just returned — WHICH IS NOT ALWAYS "sent".
 *
 * ── WHY THIS IS ONE FUNCTION AND NOT A SENTENCE AT EACH CALL SITE ────────────────────────────────
 *
 * `DwSyncResult` has six fields and reading it as "did it work" is wrong in two shapes that both
 * look like success:
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
 * result up, so both have the same four outcomes to report and the same two ways of being wrong
 * about them. A second copy would drift — and the direction it would drift in is over-claiming,
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
  return !result.stoppedOffline && result.failed === 0 && result.pending === 0;
}

export function syncPassNote(result: DwSyncResult, subject: string): string {
  if (result.stoppedOffline) {
    return "Saved on this device. There is no connection, so it sends itself when one returns.";
  }
  if (result.failed > 0) {
    return "Saved on this device, but the repository refused something in this workshop — the sync banner names what.";
  }
  if (result.pending > 0) {
    return result.stagesSent === 0
      ? `Saved on this device. Another sync is already running, so ${subject} going up with that pass rather than this one — the sync banner follows it.`
      : "Saved on this device and a sync ran, but this device still has work outstanding, which the sync banner names.";
  }
  return "Saved, and sent to the repository.";
}
