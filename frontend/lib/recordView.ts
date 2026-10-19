/**
 * WHERE A RECORD OPENS, AND WHO IS OFFERED "UPDATE" ON IT — the one table every list, card and
 * search hit reads (owner ruling, 2026-10-16).
 *
 * The ruling: clicking anywhere on a record's card or row opens that record READ-ONLY. Editing is a
 * second, explicit act behind a button labelled "Update". Before it, half the lists opened the edit
 * form from a small "Edit" link and the rest of the row did nothing, so the commonest thing a
 * researcher does with a record — look at it — went through a form that could be changed by a stray
 * keystroke and that armed the unsaved-changes prompt on the way out.
 *
 * TWO TABLES, BECAUSE THERE ARE TWO DESTINATIONS. `RECORD_VIEW_HREF` is the read-only page. The
 * Update destination keeps the two shapes §13.0 of the frontend contract describes — a real
 * `/{type}/{id}/edit` route, or an inline form on the list page reached with `?edit={id}` — and must
 * keep the id: a bare list route there opens the CREATE form, the bug that shipped in six places.
 *
 * NOTHING HERE IS A NEW PERMISSION. `canUpdateRecord` restates the server's PATCH gate for each type
 * (`require_craft_manager`, `require_workshop_manager`, and the plain sign-in the four field records
 * and interviews take, where `guard_record_edit` decides field by field). It decides whether the
 * button is DRAWN; the server still decides whether the save lands.
 */

import { canManageCrafts, canManageWorkshops } from "@/lib/permissions";
import type { User } from "@/lib/types";

/**
 * WHY A RECORD DID NOT OPEN, in one sentence — the read-only page and the edit form say the same.
 *
 * A 404 is "gone", and only gone: reading is open to every signed-in account, so a record the caller
 * may not read is a 403 and says so instead. The server's own "Record not found" was printed raw on
 * the edit form, where it reads as an accusation about a record the designer just opened.
 */
export function recordLoadFailure(noun: string, status: number | null | undefined, message?: string | null): string {
  if (status === 404) return `This ${noun} could not be found. It may have been deleted.`;
  if (status === 403) return `Your account can't open this ${noun}.`;
  const said = (message ?? "").trim();
  return said && said.toLowerCase() !== "record not found" ? said : `Unable to load this ${noun}.`;
}

/** The record kinds that have a read-only page of their own. */
export type ViewableRecord = "artisan" | "product" | "tool" | "process" | "craft" | "workshop" | "interview" | "media";

/** The read-only page for a record. Every entry carries the id — see the header. */
export const RECORD_VIEW_HREF: Record<ViewableRecord, (id: string) => string> = {
  artisan: (id) => `/artisans/${encodeURIComponent(id)}`,
  product: (id) => `/products/${encodeURIComponent(id)}`,
  tool: (id) => `/tools/${encodeURIComponent(id)}`,
  process: (id) => `/processes/${encodeURIComponent(id)}`,
  craft: (id) => `/crafts/${encodeURIComponent(id)}`,
  workshop: (id) => `/workshops/${encodeURIComponent(id)}`,
  interview: (id) => `/questionnaire/interviews/${encodeURIComponent(id)}`,
  media: (id) => `/media/${encodeURIComponent(id)}`
};

/**
 * The read-only page for a row of the review queue, whose `recordType` is the server's review key
 * (`_REVIEW_TYPES` in `backend/app/api/routes/review.py`). An interview is filed there as
 * "questionnaire"; a key with no page of its own answers null, and the row is drawn plainly.
 */
export function reviewRecordViewHref(recordType: string, id: string): string | null {
  const key = recordType.toLowerCase();
  const kind: ViewableRecord | null =
    key === "questionnaire"
      ? "interview"
      : (["artisan", "product", "tool", "process", "workshop", "media"] as const).find((k) => k === key) ?? null;
  return kind ? RECORD_VIEW_HREF[kind](id) : null;
}

/**
 * Where "Update" goes. Media has no entry: a stored file has no edit form, so its page offers
 * Download and never Update.
 */
export const RECORD_UPDATE_HREF: Record<Exclude<ViewableRecord, "media">, (id: string) => string> = {
  artisan: (id) => `/artisans/${encodeURIComponent(id)}/edit`,
  product: (id) => `/products/${encodeURIComponent(id)}/edit`,
  tool: (id) => `/tools/${encodeURIComponent(id)}/edit`,
  // The second shape: an inline form on the list page, read by `useEditDeepLink`.
  process: (id) => `/processes?edit=${encodeURIComponent(id)}`,
  craft: (id) => `/crafts?edit=${encodeURIComponent(id)}`,
  workshop: (id) => `/workshops?edit=${encodeURIComponent(id)}`,
  interview: (id) => `/questionnaire?edit=${encodeURIComponent(id)}`
};

/** The Update destination for a kind, or null where the kind has none. */
export function recordUpdateHref(kind: ViewableRecord, id: string): string | null {
  return kind === "media" ? null : RECORD_UPDATE_HREF[kind](id);
}

/**
 * Whether "Update" is drawn for this reader on a record of this kind. Mirrors the server's PATCH
 * gate — see the header. Signed out is never offered it.
 */
export function canUpdateRecord(user: User | null | undefined, kind: ViewableRecord): boolean {
  if (!user) return false;
  switch (kind) {
    case "craft":
      return canManageCrafts(user);
    case "workshop":
      return canManageWorkshops(user);
    case "media":
      return false;
    default:
      return true;
  }
}

/**
 * The controls inside a row or card that keep their own click. A press on any of them is THAT
 * control's act — Update, Discuss, Delete, a status menu, a preview tile — and must not also open
 * the record underneath. Matched with `closest()` from the click target, so a control nested inside
 * a cell is caught however deep it sits, and a page adding a new button to a row needs no
 * `stopPropagation` of its own. `[data-row-ignore]` is the escape hatch for anything else.
 */
export const ROW_OWN_CLICK_SELECTOR = [
  "a[href]",
  "button",
  "input",
  "select",
  "textarea",
  "label",
  // A player's own controls: a press on play or the scrubber is never a press on the card.
  "audio",
  "video",
  "summary",
  "[role='button']",
  "[role='link']",
  "[role='checkbox']",
  "[role='switch']",
  "[role='menuitem']",
  "[role='combobox']",
  "[contenteditable='true']",
  "[data-row-ignore]"
].join(", ");

/** What a click on an openable row should do. */
export type RowClickIntent = "ignore" | "open" | "open-new-tab";

/**
 * The decision, kept free of the DOM so it can be tested without a browser.
 *
 * - A press that started on one of the row's own controls belongs to that control.
 * - A press whose target is not inside the row (a portalled menu raised from it — React bubbles
 *   portal events through the component tree, not the document) is not a press on the row.
 * - A reader who dragged to select text was copying a value, not opening the record.
 * - Anything but the primary button, or the primary button with a modifier, asks for a new tab —
 *   exactly what the same press does on the real link in the row's first cell.
 */
export function rowClickIntent(input: {
  button: number;
  ctrlKey?: boolean;
  metaKey?: boolean;
  shiftKey?: boolean;
  insideRow: boolean;
  onOwnControl: boolean;
  selectedText?: string | null;
}): RowClickIntent {
  if (!input.insideRow || input.onOwnControl) return "ignore";
  if ((input.selectedText ?? "").trim().length > 0) return "ignore";
  if (input.button === 1) return "open-new-tab";
  if (input.button !== 0) return "ignore";
  if (input.ctrlKey || input.metaKey || input.shiftKey) return "open-new-tab";
  return "open";
}
