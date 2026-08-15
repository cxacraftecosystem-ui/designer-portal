/**
 * Whether a local design-workshop draft is EVIDENCE OF ANYTHING, or was invented by this browser.
 *
 * `ensureDraft(id)` fabricates a draft for whatever id it is handed — no server call, no ownership
 * check, by design, because that is how a designer with no signal gets a workspace for a workshop
 * she is about to create. The cost of that design is that "there is a draft" says nothing at all
 * about whether the repository has ever agreed such a workshop exists, and every render gate on the
 * stage index keyed on exactly that. Audit 2026-08-15 (MAJOR, frontend): a workshop id the account
 * may not open rendered as a real, editable 22-stage workshop with one red line above it.
 *
 * WHY THIS IS ITS OWN MODULE. The test below is three fields deep across two levels of a record and
 * it is the difference between "blank this screen" and "this is the designer's only copy of a
 * fortnight's fieldwork" — the single most expensive boolean on the page. Inline in a component it
 * was unreachable by a test; here it is checked in a millisecond and the reasoning has somewhere to
 * live. Kept out of `designWorkshopStore.ts` deliberately: this is a question a SCREEN asks about a
 * draft, not part of the store's contract, and that file is already the largest in the frontend.
 */

import type { DwDraft } from "@/lib/designWorkshopStore";

/**
 * True when nothing in this draft has ever come from the repository.
 *
 * TWO SIGNALS, OR'd, BECAUSE THEY RECORD DIFFERENT EVENTS AND EITHER ONE IS PROOF. `lastSyncedAt`
 * is stamped by a whole-workshop reconciliation (`adoptServerDetail`); a stage's `serverLoadedAt` is
 * stamped when THAT stage was read or folded (`adoptServerStage` / `foldStageInto`). A designer who
 * opened one stage on a train and nothing else has the second and not the first, and her stage holds
 * real rows the repository sent — treating that draft as fabricated would blank the only screen
 * showing them.
 *
 * The `remoteId` is deliberately NOT part of the test, and that is the trap this function exists to
 * avoid. `ensureDraft` stamps `remoteId` with the id it was passed, so a fabricated draft for a
 * forbidden workshop carries one and looks, by that field alone, exactly like a workshop this
 * browser downloaded last week. Anybody "simplifying" this to `draft.remoteId === null` restores the
 * original defect in full.
 */
export function neverReconciled(draft: DwDraft): boolean {
  if (draft.lastSyncedAt !== null && draft.lastSyncedAt !== undefined) return false;
  return !Object.values(draft.stages ?? {}).some(
    (stage) => stage?.serverLoadedAt !== null && stage?.serverLoadedAt !== undefined
  );
}
