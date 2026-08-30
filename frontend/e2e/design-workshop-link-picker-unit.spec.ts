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

/**
 * "THE DESIGNER WORKSHOPS ARE NOT SHOWING UP" — the picker that could not say why.
 *
 * The "Linked workshop record" control on `/design-workshops/{id}/edit` held its list as
 * `useState<Workshop[]>([])`, filled it in a `.then`, and left it untouched in a `.catch`.
 * Everything downstream branched on `rows.length`. So four completely different situations — the
 * read is still in flight, the read FAILED, the device is offline, and this account genuinely has
 * no design-prototype workshops — all rendered one confident sentence:
 *
 *     "No design & prototype workshops are open to this account, so there is nothing to link to.
 *      Mark one on the Workshops page — or ask an admin for access to it — to use it here."
 *
 * Two things are wrong with that and both are in scope here. It is a claim about a GRANT TABLE
 * produced by a request that never arrived (SKILL §17's "absence read as non-existence", this
 * repository's most repeated bug class, and the whole reason `lib/workshopOptions.ts` exists). And
 * its remedy is an act the reader is forbidden to perform: setting a workshop's Kind goes through
 * `can_manage_workshops`, which is `has_rank(user, "PROFESSOR")` — rank 40 — and a DESIGNER is 35,
 * so the one screen that explained the empty list told the product's primary user to go and do
 * something the API refuses them, on a page whose form is hidden from them.
 *
 * ── WHY A NODE SPEC ────────────────────────────────────────────────────────────────────────────
 *
 * Because a browser spec with a working connection cannot reach three of the four states, and this
 * project has no React renderer in `devDependencies` — Playwright is the whole of it — so a
 * judgement living inside a hook cannot be asserted at all. `components/designworkshop/
 * linkedWorkshopPicker.ts` holds the judgement for exactly this reason, the same split
 * `headerDiff.ts` already took for the body of the save. What this file CANNOT prove, and what
 * belongs in `workshop-type.spec.ts`, is that the request really carries `workshopType` and that the
 * server really honours it.
 */

const root = join(__dirname, "..");

/** Newlines normalised: this checkout is CRLF, and a multi-line `toContain` would match nothing. */
function sourceOf(...parts: string[]): string {
  return readFileSync(join(root, ...parts), "utf8").replace(/\r\n/g, "\n");
}

/**
 * COMMENTS STRIPPED, and every ABSENCE assertion below runs against this rather than the file.
 *
 * This repository's house style is long comments that name the concrete failure a rule prevents, so
 * the code that was removed is very often still quoted in the paragraph explaining why it was
 * removed — the edit form's own note on `linkList` quotes `useState<Workshop[]>([])` verbatim. An
 * absence check over the raw file would then fail about source that is perfectly correct, and the
 * obvious "fix" is to delete the explanation, which is the wrong half to lose.
 * `dropdown-sweep-unit.spec.ts` carries the same helper for the same reason.
 */
function withoutComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, " ").replace(/(^|[^:])\/\/.*/g, "$1");
}

function workshop(id: string, title: string, extra: Partial<FieldWorkshopRow> = {}): FieldWorkshopRow {
  return { id, title, place: "Bagru", startDate: "2026-07-20T00:00:00Z", ...extra };
}

const ROWS = [workshop("w1", "Bagru monsoon sitting"), workshop("w2", "Ajrakhpur winter sitting")];

/** The unnarrowed, answered, online, nothing-stored case — every test below varies one thing. */
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

/* ────────────────────────────────────────────────────────────────────────────
 * The four states, which is the whole point
 * ──────────────────────────────────────────────────────────────────────────── */

test("a FAILED read never claims this account has no workshops", () => {
  const failed = view({ list: FAILED, pending: false });

  // The exact sentence the old code printed. If this ever comes back, the bug is back.
  expect(failed.notice).not.toContain("are open to this account");
  expect(failed.notice).toContain("could not be loaded");
  expect(failed.notice).toContain("Nothing you have entered is at risk");
  // And the panel's own line agrees with the line under the field — one failure, one story.
  expect(failed.emptyLabel).toContain("could not be loaded");
  // Nobody is sent on an errand about a Kind because the network dropped.
  expect(failed.gap).toBe("");
});

test("offline is its own sentence, and does not promise a cache these lists never keep", () => {
  const offline = view({ list: FAILED, online: false });
  expect(offline.notice).toContain("has not received the workshops list yet");
  expect(offline.notice).toContain("That is not a claim that there are none.");
  // §3.3 rules "disable with a reason, never cache" for an ACCESS list: a stored copy of who may
  // file where reads a grant revoked in March as a grant in September.
  expect(offline.notice).toContain("never kept on the device");
});

test("LOADING says nothing under the field and waits inside the panel", () => {
  const loading = view({ list: LOADING, pending: true });
  // A sentence that appears and vanishes inside a second is noise on a fast connection and, on a
  // slow one, is replaced just as the reader finishes reading it. The panel covers the wait.
  expect(loading.notice).toBe("");
  expect(loading.gap).toBe("");
  expect(loading.emptyLabel).toBe(SEARCHING_LABEL);
  expect(loading.placeholder).toBe("Loading workshops…");
  // "There is nothing to pick" is a claim, and mid-flight it is not one this knows to be true.
  expect(loading.standingDown).toBe(false);
});

test("a genuinely empty answer says so AND names somebody who can actually fix it", () => {
  const empty = view({ list: EMPTY });
  expect(empty.notice).toBe("No workshops are open to this account. An administrator can give you access to one.");
  expect(empty.gap).toBe(LINKED_WORKSHOP_KIND_GAP);
  // The whole point of the second sentence: the reader is a designer and the act is a professor's.
  expect(empty.gap).toContain("a designer account cannot");
  expect(empty.gap).toContain("professor");
  // And the dead-end advice the old copy gave is gone.
  expect(empty.gap).not.toContain("Mark one on the Workshops page");
  // R2/R3 together: nothing to pick, so the control is disabled and the sentence above says why.
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
  // "No workshops are open to this account" is a claim about a SCOPE. Over an answer to "zzz" it is
  // false, and false in the direction that sends a designer to an administrator about access they
  // already have. The panel draws `serverNoMatchSentence` instead, where the reader is looking.
  expect(searched.notice).toBe("");
  expect(searched.gap).toBe("");
  // And the control must not stand down over the reader's own keystroke: the box lives INSIDE the
  // panel, so a disabled trigger makes the term unclearable and locks them out of the list.
  expect(searched.standingDown).toBe(false);
});

/* ────────────────────────────────────────────────────────────────────────────
 * The off-page value — the destructive one
 * ──────────────────────────────────────────────────────────────────────────── */

test("a record filed under a workshop that is not on the page still shows that workshop", () => {
  const stored = workshop("w-old", "Kutch 2024 sitting");
  const recovered = view({ storedId: "w-old", storedRow: stored });

  const row = recovered.options.find((option) => option.value === "w-old");
  expect(row, "the stored link must be offered even though the list does not hold it").toBeTruthy();
  expect(row?.label).toBe("Kutch 2024 sitting");
  // Its own heading, not folded into "Open": a picker that says "these are the workshops open to
  // you" and quietly includes one that is not is lying about its own scope in order to be helpful.
  expect(row?.group).toBe(GROUP_ON_THIS_RECORD);
  expect(recovered.set.recovered).toBe(true);
  expect(recovered.unresolved).toBe(false);
});

test("an UNRESOLVABLE stored link is still drawn — a blank trigger reads as 'not linked'", () => {
  // `useRecordOffPage` gives up in silence on a 403 or a 404, so `storedRow` stays null. There is no
  // second box on this form holding the name, so the trigger would fall back to the "none" row —
  // and the obvious repair for a picker that looks unlinked is to pick something, which is the one
  // action that really does re-point the link and strand every record filed under the old one.
  const orphan = view({ storedId: "w-secret", storedRow: null });

  const row = orphan.options.find((option) => option.value === "w-secret");
  expect(row, "the id must be offered under a label that says what it is").toBeTruthy();
  expect(row?.label).toBe(UNRESOLVED_LINK_LABEL);
  expect(orphan.unresolved).toBe(true);
});

test("a failed read with a stored link is NOT an empty control", () => {
  const failedButFiled = view({ list: FAILED, storedId: "w-old", storedRow: workshop("w-old", "Kutch 2024") });
  // Standing this down would leave a designer looking at a correct value they cannot change, which
  // is worse than the failure that caused it. `workshopListStandsDown` takes the OPTIONS, not the
  // state, for exactly this case.
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
  // Two layers each entitled to draw "none" is two rows sharing the React key "", a duplicate-key
  // warning, a list offering the same answer twice, and a control that cannot say which of the two
  // is selected. The form passes `noneLabel={NO_FIELD_WORKSHOP}` and builds no row of its own.
  for (const state of [LOADING, FAILED, EMPTY, { kind: "ok" as const, rows: ROWS, total: 2 }]) {
    expect(view({ list: state }).options.some((option) => option.value === "")).toBe(false);
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * The cut, and the label format
 * ──────────────────────────────────────────────────────────────────────────── */

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
  // Six label shapes shipped for this one question before `lib/workshopOptions.ts` owned it. The
  // hand-built `title · place` this control used is one of the six.
  expect(first.label).toBe("Ajrakhpur winter sitting");
  expect(first.hint).toBe("Bagru · 2026-07-20");
});

/* ────────────────────────────────────────────────────────────────────────────
 * What the component must actually be wired to — source assertions
 * ──────────────────────────────────────────────────────────────────────────── */

const form = sourceOf("components", "designworkshop", "DesignWorkshopHeaderForm.tsx");
const createPage = sourceOf("app", "(protected)", "design-workshops", "page.tsx");
const formCode = withoutComments(form);
const createPageCode = withoutComments(createPage);

test("the edit form's catch arm records the failure instead of discarding it", () => {
  expect(form).toContain('setLinkList({ kind: "failed" })');
  // The collapse this replaced: a list held as an array with an empty catch.
  expect(formCode).not.toContain("useState<Workshop[]>([])");
  expect(formCode).not.toContain("linkableRows.length");
});

test("the edit form asks for exactly as many rows as the panel can draw", () => {
  expect(form).toContain("pageSize: WORKSHOP_OPTION_PAGE_SIZE");
  // 100 rows into a control that draws 80 is a dead band where the page says nothing and the panel
  // silently drops rows.
  expect(formCode).not.toContain("pageSize: 100");
});

test("the edit form's picker is the shared primitive's, not a hand-rolled one", () => {
  expect(form).toContain("noneLabel={NO_FIELD_WORKSHOP}");
  expect(form).toContain("emptyLabel={link.emptyLabel}");
  expect(form).toContain("<CappedListNotice id={linkCutId} cuts={[link.cut]} />");
  expect(form).toContain("serverQuery={{ value: linkTerm, onChange: setLinkTerm, pending: linkPending }}");
  // The nine spellings of the "no workshop" row collapse to four constants and to nothing smaller.
  expect(formCode).not.toContain('label: "Not linked to a workshop record"');
});

test("the edit form still narrows by kind and by access, and searches the server", () => {
  expect(form).toContain('workshopType: "DESIGN_PROTOTYPE"');
  expect(form).toContain('accessibleOnly: "true"');
  expect(form).toContain("search: trimmed || undefined");
});

test("the create form's picker no longer tells the builder the read succeeded", () => {
  // `{ kind: "ok", rows: sourceWorkshops, total: sourceWorkshopsTotal }` was the three-state type
  // being asked for its opinion and told what to say.
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
