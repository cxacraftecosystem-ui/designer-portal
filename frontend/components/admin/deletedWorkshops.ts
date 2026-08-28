/**
 * THE TRASH CARD'S SENTENCES — the pure half of `/admin`'s deleted-workshops panel.
 *
 * WHY A MODULE AND NOT THREE TERNARIES IN THE JSX, which is the same argument
 * `components/data/cappedList.ts` and `lib/designWorkshopViewers.eligibleViewerNotice` make and this
 * file deliberately copies: two of the states below cannot be produced by any live database — a page
 * past the end of the trash, a deleter whose account has since been closed — so a decision written
 * inside a render is only ever exercised by somebody looking at a screen. This repository has no
 * React renderer in its devDependencies, so `e2e/deleted-workshops-unit.spec.ts` calls these
 * directly. Same split, same reason, as `components/ui/selectFilter.ts`.
 *
 * WHAT THE PANEL IS FOR. `DELETE /design-workshops/{id}` is a soft delete — the row and all 22
 * stages stay, only `deletedAt` is set — and `POST /{id}/restore` undoes it. The delete confirmation
 * on `/design-workshops` says so. Until the list route grew `deletedOnly`, nothing on any surface
 * would NAME a deleted workshop, so the promise was true of the database and false of the product:
 * an admin could restore only a workshop whose id they had written down before deleting it.
 */

import { listCut, type ListCut } from "@/components/data/cappedList";
import type { DwSummary } from "@/lib/designWorkshops";
import type { PageResult } from "@/lib/types";

/**
 * Rows per page of the trash.
 *
 * Twenty, matching the list route's own default, and NOT the 100 ceiling: this is a paged table with
 * a pager under it, not a picker holding one page, so a larger page buys nothing but a longer scroll
 * inside an admin hub that already carries the recovered-recordings table.
 */
export const DELETED_WORKSHOPS_PAGE_SIZE = 20;

/** The plural this card's sentences are built around. Written once so two of them cannot drift. */
const NOUN = "deleted workshops";

/**
 * Was this page of the trash cut, and by how much?
 *
 * Delegates to `listCut` rather than comparing two numbers here, because that helper is where the
 * `Number.isFinite` guard lives: `total` is a plain cast off the wire (`apiFetch` validates no
 * schema), and a server that omitted it must make this card say NOTHING rather than claim a cut of
 * `NaN`.
 */
export function deletedWorkshopsCut(result: PageResult<DwSummary>): ListCut | null {
  return listCut(result, NOUN);
}

/**
 * THE ONE SENTENCE ABOVE THE TRASH TABLE, or "" when there is nothing to say.
 *
 * Three arms, ordered so the impossible-looking one is tested first:
 *
 * 1. **Nothing on the page although the server says rows exist.** Reachable by paging past the end —
 *    delete two workshops, open page 2, restore both from another tab — and it is the state where
 *    silence does the most damage: the card would draw its "Nothing has been deleted" empty state
 *    over a trash that is not empty, which is the exact reading this whole panel exists to prevent.
 * 2. **Cut, with the pager below.** Say the arithmetic and point at the pager.
 * 3. **Not cut.** Silence. A standing note about pagination on a card holding three rows is padding.
 *
 * WHY NOT `cappedListNotice(cut, "pager")` VERBATIM. Its pager arm ends "…which are not searched by
 * the box above", and this card has no search box — a sentence naming a control that is not on
 * screen is the defect that module's own header warns about, one card over. The ARITHMETIC is shared
 * (`listCut`, `ListCut`); only the instruction differs, because only the instruction is about this
 * screen. If a server-backed search box is ever added here, this sentence owes it a clause.
 */
export function deletedWorkshopsNotice(cut: ListCut | null): string {
  if (!cut) return "";
  if (cut.loaded === 0) {
    return `None of the ${cut.total} ${cut.noun} could be listed on this page — the trash is not empty.`;
  }
  return `Showing ${cut.loaded} of ${cut.total} ${cut.noun} — the other ${cut.total - cut.loaded} are on the pages below.`;
}

/**
 * WHAT THE PANEL SAYS WHERE "NOTHING HAS BEEN DELETED" WOULD BE A LIE.
 *
 * The card has a table, a loading state and an empty state, and "no rows came back" is TWO facts
 * that were sharing the last of those: the trash really is empty, or this PAGE is past the end of a
 * trash that is not. Only the first may be drawn as an empty state. The second is what
 * {@link deletedWorkshopsNotice}'s first arm is written for, and it was being rendered with
 * "Nothing has been deleted" underneath it — two sentences on one screen contradicting each other,
 * and the one a reader believes is the big centred heading.
 *
 * TWO ARMS, BECAUSE THE INSTRUCTION IS ONLY TRUE ON ONE OF THEM. Past the end of the trash
 * (`page > 1`) every remaining row is behind the reader and the pager is the way back. On page ONE
 * the pager is no help and saying so would be the "name a control that cannot do it" defect this
 * file's other sentence avoids — that state is a race, not a page: `list_design_workshops` gathers
 * its `count` and its `find_many` concurrently, so a colleague restoring the last rows between the
 * two answers a total with no rows to go under it. Re-reading is the only thing that helps.
 *
 * @param total what the server says is in the trash under this filter — the caller has already
 *   established it is above zero, which is what makes this state distinguishable from an empty one.
 * @param page the page the rows were asked for, as the SERVER echoed it back.
 */
export function strandedPageSentence(total: number, page: number): string {
  const subject = total === 1 ? "One deleted workshop is" : `${total} deleted workshops are`;
  const object = total === 1 ? "it" : "them";
  return page > 1
    ? `${subject} still on record — on earlier pages. Use the pager below to reach ${object}.`
    : `${subject} still on record, but none came back on this request. Reload the page to see ${object}.`;
}

/**
 * WHO DELETED THIS WORKSHOP, in words, for the column of the same name.
 *
 * Three answers and they are three different facts, which is why none of them is a blank cell:
 *
 * - The name, when the server resolved one.
 * - **An id with no name** — `DesignWorkshop.deletedById` is `onDelete: SetNull` against `User`, so
 *   the pointer outlives the account. The honest rendering is that the account is gone, never a
 *   guess at the workshop's creator: naming somebody on the one screen whose purpose is undoing a
 *   deletion they did not perform is worse than naming nobody. `entry_provenance.resolve_display_names`
 *   argues the same case at length for field provenance.
 * - **No pointer at all** — rows deleted before the column was written carry none, and a workshop
 *   can legitimately sit in the trash with nobody on record against it.
 */
export function deletedByLabel(row: Pick<DwSummary, "deletedById" | "deletedByName">): string {
  if (row.deletedByName) return row.deletedByName;
  if (row.deletedById) return "An account no longer on record";
  return "Not recorded";
}

/**
 * What the card says after a successful restore.
 *
 * It NAMES THE WORKSHOP, and that is the whole point of the sentence rather than a nicety: the row
 * leaves the trash the moment the list reloads, so a mis-aimed click on a table of similar titles
 * would otherwise leave nothing on screen saying which workshop just came back.
 *
 * A blank title is a real state — a workshop created and deleted before stage 1 was saved — and it
 * gets the untitled wording rather than a pair of empty quotation marks.
 */
export function restoredNotice(title: string): string {
  const named = title.trim();
  return named
    ? `“${named}” is restored, and is back on the design workshops list.`
    : "That workshop is restored, and is back on the design workshops list.";
}
