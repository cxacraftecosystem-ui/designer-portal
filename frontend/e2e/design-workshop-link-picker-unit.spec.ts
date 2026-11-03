import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  LINKED_WORKSHOP_KIND_GAP,
  LINKED_WORKSHOP_SCOPE_SENTENCE,
  UNRESOLVED_LINK_LABEL,
  linkedWorkshopView
} from "@/components/designworkshop/linkedWorkshopPicker";
import { SEARCHING_LABEL } from "@/components/ui/selectFilter";
import {
  GROUP_ON_THIS_RECORD,
  type FieldWorkshopRow,
  type WorkshopListState
} from "@/lib/workshopOptions";

const root = join(__dirname, "..");

function sourceOf(...parts: string[]): string {
  return readFileSync(join(root, ...parts), "utf8").replace(/\r\n/g, "\n");
}

function withoutComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, " ").replace(/(^|[^:])\/\/.*/g, "$1");
}

function workshop(id: string, title: string, extra: Partial<FieldWorkshopRow> = {}): FieldWorkshopRow {
  return { id, title, place: "Bagru", startDate: "2026-07-20T00:00:00Z", ...extra };
}

const ROWS = [workshop("w1", "Bagru monsoon sitting"), workshop("w2", "Ajrakhpur winter sitting")];

function view(overrides: Partial<Parameters<typeof linkedWorkshopView>[0]> = {}) {
  return linkedWorkshopView({
    list: { kind: "ok", rows: ROWS, total: ROWS.length },
    searchApplied: "",
    term: "",
    pending: false,
    storedId: "",
    storedRow: null,
    online: true,
    ...overrides
  });
}

const LOADING: WorkshopListState<FieldWorkshopRow> = { kind: "loading" };
const FAILED: WorkshopListState<FieldWorkshopRow> = { kind: "failed" };
const EMPTY: WorkshopListState<FieldWorkshopRow> = { kind: "ok", rows: [], total: 0 };

test("a FAILED read never claims this account has no workshops", () => {
  const failed = view({ list: FAILED, pending: false });

  expect(failed.notice).not.toContain("are open to this account");
  expect(failed.notice).toContain("could not be loaded");
  expect(failed.notice).toContain("Nothing you have entered is at risk");
  expect(failed.emptyLabel).toContain("could not be loaded");
  expect(failed.gap).toBe("");
});

test("offline is its own sentence, and does not promise a cache these lists never keep", () => {
  const offline = view({ list: FAILED, online: false });
  expect(offline.notice).toContain("has not received the workshops list yet");
  expect(offline.notice).toContain("so there is nothing to pick here.");
  expect(offline.notice).toContain("never kept on the device");
});

test("LOADING says nothing under the field and waits inside the panel", () => {
  const loading = view({ list: LOADING, pending: true });
  expect(loading.notice).toBe("");
  expect(loading.gap).toBe("");
  expect(loading.emptyLabel).toBe(SEARCHING_LABEL);
  expect(loading.placeholder).toBe("Loading workshops…");
  expect(loading.standingDown).toBe(false);
});

test("a genuinely empty answer says so AND names somebody who can actually fix it", () => {
  const empty = view({ list: EMPTY });
  expect(empty.notice).toBe("No workshops are open to this account. An administrator can give you access to one.");
  expect(empty.gap).toBe(LINKED_WORKSHOP_KIND_GAP);
  expect(empty.gap).toContain("a designer account cannot");
  expect(empty.gap).toContain("professor");
  expect(empty.gap).not.toContain("Mark one on the Workshops page");
  expect(empty.standingDown).toBe(true);
  expect(empty.notice).not.toBe("");
});

test("a list with rows explains the scope instead, because a scoped list looks like a small one", () => {
  const ok = view();
  expect(ok.notice).toBe(LINKED_WORKSHOP_SCOPE_SENTENCE);
  expect(ok.gap).toBe("");
  expect(ok.standingDown).toBe(false);
  expect(ok.placeholder).toBe("Select or type to search");
});

test("a search that matched nothing is not reported as an empty account", () => {
  const searched = view({ list: EMPTY, searchApplied: "zzz", term: "zzz" });
  expect(searched.notice).toBe("");
  expect(searched.gap).toBe("");
  expect(searched.standingDown).toBe(false);
});

test("a record filed under a workshop that is not on the page still shows that workshop", () => {
  const stored = workshop("w-old", "Kutch 2024 sitting");
  const recovered = view({ storedId: "w-old", storedRow: stored });

  const row = recovered.options.find((option) => option.value === "w-old");
  expect(row, "the stored link must be offered even though the list does not hold it").toBeTruthy();
  expect(row?.label).toBe("Kutch 2024 sitting");
  expect(row?.group).toBe(GROUP_ON_THIS_RECORD);
  expect(recovered.set.recovered).toBe(true);
  expect(recovered.unresolved).toBe(false);
});

test("an UNRESOLVABLE stored link is still drawn — a blank trigger reads as 'not linked'", () => {
  const orphan = view({ storedId: "w-secret", storedRow: null });

  const row = orphan.options.find((option) => option.value === "w-secret");
  expect(row, "the id must be offered under a label that says what it is").toBeTruthy();
  expect(row?.label).toBe(UNRESOLVED_LINK_LABEL);
  expect(orphan.unresolved).toBe(true);
});

test("a failed read with a stored link is NOT an empty control", () => {
  const failedButFiled = view({ list: FAILED, storedId: "w-old", storedRow: workshop("w-old", "Kutch 2024") });
  expect(failedButFiled.standingDown).toBe(false);
  expect(failedButFiled.options.map((option) => option.value)).toContain("w-old");
});

test("a stored link that IS on the page is drawn once, as an ordinary row", () => {
  const onPage = view({ storedId: "w1", storedRow: ROWS[0] });
  expect(onPage.options.filter((option) => option.value === "w1")).toHaveLength(1);
  expect(onPage.set.recovered).toBe(false);
  expect(onPage.unresolved).toBe(false);
});

test("the 'none' row belongs to the primitive and is never in the options array", () => {
  for (const state of [LOADING, FAILED, EMPTY, { kind: "ok" as const, rows: ROWS, total: 2 }]) {
    expect(view({ list: state }).options.some((option) => option.value === "")).toBe(false);
  }
});

test("a truncated list says so, with the number, and names the box that reaches the rest", () => {
  const capped = view({ list: { kind: "ok", rows: ROWS, total: 196 } });
  expect(capped.cut).toBe("Showing the first 2 of 196. Keep typing to narrow the list.");
  expect(capped.set.cut).toBe(194);
});

test("a whole list says nothing about a cut", () => {
  expect(view().cut).toBe("");
});

test("the recovered row is counted as 'plus 1 already selected', never as a row of the corpus", () => {
  const capped = view({
    list: { kind: "ok", rows: ROWS, total: 196 },
    storedId: "w-old",
    storedRow: workshop("w-old", "Kutch 2024 sitting")
  });
  expect(capped.cut).toBe("Showing the first 2 of 196, plus 1 already selected. Keep typing to narrow the list.");
});

test("the label is the title alone and the hint carries what tells two workshops apart", () => {
  const [first] = view().options;
  expect(first.label).toBe("Ajrakhpur winter sitting");
  expect(first.hint).toBe("Bagru · 2026-07-20");
});

const form = sourceOf("components", "designworkshop", "DesignWorkshopHeaderForm.tsx");
const createPage = sourceOf("app", "(protected)", "design-workshops", "page.tsx");
const formCode = withoutComments(form);
const createPageCode = withoutComments(createPage);

test("the edit form's catch arm records the failure instead of discarding it", () => {
  expect(form).toContain('setLinkList({ kind: "failed" })');
  expect(formCode).not.toContain("useState<Workshop[]>([])");
  expect(formCode).not.toContain("linkableRows.length");
});

test("the edit form asks for exactly as many rows as the panel can draw", () => {
  expect(form).toContain("pageSize: WORKSHOP_OPTION_PAGE_SIZE");
  expect(formCode).not.toContain("pageSize: 100");
});

test("the edit form's picker is the shared primitive's, not a hand-rolled one", () => {
  expect(form).toContain("noneLabel={NO_FIELD_WORKSHOP}");
  expect(form).toContain("emptyLabel={link.emptyLabel}");
  expect(form).toContain("<CappedListNotice id={linkCutId} cuts={[link.cut]} />");
  expect(form).toContain("serverQuery={{ value: linkTerm, onChange: setLinkTerm, pending: linkPending }}");
  expect(formCode).not.toContain('label: "Not linked to a workshop record"');
});

test("the edit form still narrows by kind and by access, and searches the server", () => {
  expect(form).toContain('workshopType: "DESIGN_PROTOTYPE"');
  expect(form).toContain('accessibleOnly: "true"');
  expect(form).toContain("search: trimmed || undefined");
});

test("the create form's picker no longer tells the builder the read succeeded", () => {
  expect(createPage).toContain("fieldWorkshopOptions(sourceList,");
  expect(createPage).toContain('setSourceList({ kind: "failed" })');
  expect(createPage).toContain("workshopListNotice(sourceList, sourceVoice)");
  expect(createPage).toContain("workshopEmptyLabel(sourceList, sourceVoice)");
  expect(createPageCode).not.toContain('{ kind: "ok", rows: sourceWorkshops');
});

test("both screens explain an empty list in one voice", () => {
  expect(createPage).toContain("LINKED_WORKSHOP_KIND_GAP");
  expect(createPageCode).not.toContain("Mark one on the Workshops page");
  expect(formCode).not.toContain("Mark one on the Workshops page");
});
