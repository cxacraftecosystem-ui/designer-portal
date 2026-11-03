import { listCut, type ListCut } from "@/components/data/cappedList";
import type { DwSummary } from "@/lib/designWorkshops";
import type { PageResult } from "@/lib/types";

export const DELETED_WORKSHOPS_PAGE_SIZE = 20;

const NOUN = "deleted workshops";

export function deletedWorkshopsCut(result: PageResult<DwSummary>): ListCut | null {
  return listCut(result, NOUN);
}

export function deletedWorkshopsNotice(cut: ListCut | null): string {
  if (!cut) return "";
  if (cut.loaded === 0) {
    return `None of the ${cut.total} ${cut.noun} could be listed on this page — the trash is not empty.`;
  }
  return `Showing ${cut.loaded} of ${cut.total} ${cut.noun} — the other ${cut.total - cut.loaded} are on the pages below.`;
}

export function strandedPageSentence(total: number, page: number): string {
  const subject = total === 1 ? "One deleted workshop is" : `${total} deleted workshops are`;
  const object = total === 1 ? "it" : "them";
  return page > 1
    ? `${subject} still on record — on earlier pages. Use the pager below to reach ${object}.`
    : `${subject} still on record, but none could be loaded. Reload the page to see ${object}.`;
}

export function deletedByLabel(row: Pick<DwSummary, "deletedById" | "deletedByName">): string {
  if (row.deletedByName) return row.deletedByName;
  if (row.deletedById) return "An account no longer on record";
  return "Not recorded";
}

export function restoredNotice(title: string): string {
  const named = title.trim();
  return named
    ? `“${named}” is restored, and is back on the design workshops list.`
    : "That workshop is restored, and is back on the design workshops list.";
}
