import { canManageCrafts, canManageWorkshops } from "@/lib/permissions";
import type { User } from "@/lib/types";

export function recordLoadFailure(noun: string, status: number | null | undefined, message?: string | null): string {
  if (status === 404) return `This ${noun} could not be found. It may have been deleted.`;
  if (status === 403) return `Your account can't open this ${noun}.`;
  const said = (message ?? "").trim();
  return said && said.toLowerCase() !== "record not found" ? said : `Unable to load this ${noun}.`;
}

export type ViewableRecord = "artisan" | "product" | "tool" | "process" | "craft" | "workshop" | "interview" | "media";

export const RECORD_VIEW_HREF: Record<ViewableRecord, (id: string) => string> = {
  artisan: (id) => `/artisans/${encodeURIComponent(id)}`,
  product: (id) => `/products/${encodeURIComponent(id)}`,
  tool: (id) => `/tools/${encodeURIComponent(id)}`,
  process: (id) => `/processes/${encodeURIComponent(id)}`,
  craft: (id) => `/crafts/${encodeURIComponent(id)}`,
  workshop: (id) => `/workshops/${encodeURIComponent(id)}`,
  interview: (id) => `/questionnaire/sessions/${encodeURIComponent(id)}/view`,
  media: (id) => `/media/${encodeURIComponent(id)}`
};

export function reviewRecordViewHref(recordType: string, id: string): string | null {
  const key = recordType.toLowerCase();
  const kind: ViewableRecord | null =
    key === "questionnaire"
      ? "interview"
      : (["artisan", "product", "tool", "process", "workshop", "media"] as const).find((k) => k === key) ?? null;
  return kind ? RECORD_VIEW_HREF[kind](id) : null;
}

export const RECORD_UPDATE_HREF: Record<Exclude<ViewableRecord, "media">, (id: string) => string> = {
  artisan: (id) => `/artisans/${encodeURIComponent(id)}/edit`,
  product: (id) => `/products/${encodeURIComponent(id)}/edit`,
  tool: (id) => `/tools/${encodeURIComponent(id)}/edit`,
  process: (id) => `/processes?edit=${encodeURIComponent(id)}`,
  craft: (id) => `/crafts?edit=${encodeURIComponent(id)}`,
  workshop: (id) => `/workshops?edit=${encodeURIComponent(id)}`,
  interview: (id) => `/questionnaire/sessions/${encodeURIComponent(id)}`
};

export function recordUpdateHref(kind: ViewableRecord, id: string): string | null {
  return kind === "media" ? null : RECORD_UPDATE_HREF[kind](id);
}

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

export const ROW_OWN_CLICK_SELECTOR = [
  "a[href]",
  "button",
  "input",
  "select",
  "textarea",
  "label",
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

export type RowClickIntent = "ignore" | "open" | "open-new-tab";

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

export const ROW_INNER_CONTROLS = "a, button, input, select, textarea, label, summary, [role='button'], [role='link'], [data-row-ignore]";

export function rowClickOpensRecord(target: { closest?: (selector: string) => unknown } | null, row: unknown): boolean {
  if (!target || typeof target.closest !== "function") return true;
  const inner = target.closest(ROW_INNER_CONTROLS);
  return !inner || inner === row;
}
