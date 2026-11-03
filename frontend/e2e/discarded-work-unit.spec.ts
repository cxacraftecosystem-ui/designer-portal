import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  boundRefusalMessage,
  dateOutsideBounds,
  formatDisplayDate,
  parseTypedDate
} from "@/components/forms/DateTimeField";
import { hasUnsavedWork } from "@/components/forms/ProcessForm";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

function bodyOf(source: string, header: string): string {
  const start = source.indexOf(header);
  expect(start, `${header} not found — has the file been restructured?`).toBeGreaterThan(-1);
  const open = source.indexOf("{", start + header.length);
  let depth = 0;
  for (let at = open; at < source.length; at += 1) {
    if (source[at] === "{") depth += 1;
    else if (source[at] === "}") {
      depth -= 1;
      if (depth === 0) return source.slice(open, at + 1);
    }
  }
  throw new Error(`unbalanced braces after ${header}`);
}

function between(source: string, from: string, to: string): string {
  const start = source.indexOf(from);
  expect(start, `${from} not found — has the file been restructured?`).toBeGreaterThan(-1);
  const end = source.indexOf(to, start);
  expect(end, `${to} not found after ${from}`).toBeGreaterThan(-1);
  return source.slice(start, end);
}

const GUARDED_SCREENS = [
  {
    name: "the questionnaire answer screen",
    path: ["app", "(protected)", "questionnaires", "[id]", "answer", "page.tsx"]
  },
  { name: "ProcessForm", path: ["components", "forms", "ProcessForm.tsx"] },
  {
    name: "the custom-sections editor",
    path: ["components", "designworkshop", "CustomSectionsEditor.tsx"]
  }
];

for (const screen of GUARDED_SCREENS) {
  test(`${screen.name} hands its dirty flag to the header's back control`, () => {
    const source = read(...screen.path);
    expect(source, "must import the guard, not only the dialog").toContain(
      'from "@/components/UnsavedChangesGuard"'
    );
    expect(source).toContain("useLeaveGuard(");
    expect(source).toContain("<UnsavedChangesDialog");
  });

  test(`${screen.name} warns before the tab closes`, () => {
    const source = read(...screen.path);
    expect(source).toContain("beforeunload");
  });
}

test("the Sitting dropdown asks before it re-seeds the boxes", () => {
  const source = read("app", "(protected)", "questionnaires", "[id]", "answer", "page.tsx");
  const dropdown = between(source, "value={entryId}", "</FieldBlock>");

  expect(dropdown, "the dropdown must not switch sittings itself").not.toContain("setEntryId(");
  expect(dropdown).toContain('requestExit({ kind: "sitting"');

  const leaveNow = bodyOf(source, "const leaveNow = useCallback(");
  expect(leaveNow).toContain("setEntryId(exit.entryId)");

  const requestExit = bodyOf(source, "const requestExit = useCallback(");
  expect(requestExit).toContain("dirty.current.size === 0");
  expect(requestExit).toContain("leaveNow(exit)");
  expect(requestExit).toContain("setPendingExit(exit)");
});

function withoutComments(source: string): string {
  const blanked = (match: string) => match.replace(/[^\n]/g, " ");
  return source.replace(/\/\*[\s\S]*?\*\//g, blanked).replace(/\/\/[^\n]*/g, blanked);
}

function restOfBlock(source: string, at: number): string {
  let depth = 0;
  for (let i = at; i < source.length; i += 1) {
    if (source[i] === "{") depth += 1;
    else if (source[i] === "}") {
      if (depth === 0) return source.slice(at, i);
      depth -= 1;
    }
  }
  return source.slice(at);
}

test("every write to the answer screen's dirty set is paired with the state mirror", () => {
  const source = read("app", "(protected)", "questionnaires", "[id]", "answer", "page.tsx");
  expect(source).toContain("useLeaveGuard(dirtyCount > 0");
  expect(source).toContain("const syncDirty = useCallback(() => setDirtyCount(dirty.current.size)");

  const code = withoutComments(source);
  const writes = /dirty\.current\.(?:add|delete|clear)\(|dirty\.current\s*=/g;
  const sites: number[] = [];
  for (let hit = writes.exec(code); hit; hit = writes.exec(code)) sites.push(hit.index);

  expect(sites.length, "no writes to `dirty.current` found — has the ref been renamed?").toBeGreaterThanOrEqual(5);

  for (const at of sites) {
    const line = code.slice(0, at).split("\n").length;
    const statement = source.slice(at, source.indexOf("\n", at));
    expect(
      restOfBlock(code, at),
      `line ${line} writes to the dirty set (${statement.trim()}) without reaching syncDirty() in the same block — ` +
        "the guards and the beforeunload effect cannot see this write"
    ).toContain("syncDirty()");
  }

  const answerBox = between(source, 'value={draft[question.id] ?? ""}', "</label>");
  expect(answerBox, "typing an answer must mark it dirty AND mirror the count").toMatch(
    /markAnswerDirty\(|syncDirty\(\)/
  );
  const noteBox = between(source, 'value={notes[question.id] ?? ""}', "</details>");
  expect(noteBox, "typing a note must mark it dirty AND mirror the count").toMatch(
    /markAnswerDirty\(|syncDirty\(\)/
  );
});

test("the answer screen's header link is held back only for the plain click", () => {
  const source = read("app", "(protected)", "questionnaires", "[id]", "answer", "page.tsx");
  const link = between(source, 'href={`/questionnaires/${id}`}', "Edit the questionnaire");

  expect(link).toContain("event.preventDefault()");
  expect(link).toContain('setPendingExit({ kind: "edit" })');
  expect(link).toContain("event.metaKey || event.ctrlKey");
});

test("ProcessForm's guard is wired to the same flag its Cancel button uses", () => {
  const source = read("components", "forms", "ProcessForm.tsx");
  expect(source).toContain("useLeaveGuard(dirty, () => setGuardOpen(true))");
  expect(source).toContain("onDiscard=");
});

test("a save that partly failed is not unsaved work", () => {
  expect(
    hasUnsavedWork({ saving: false, committed: true, signature: "typed", initialSignature: "" }),
    "a written record with a failed attachment is not work to be saved again"
  ).toBe(false);

  expect(hasUnsavedWork({ saving: false, committed: false, signature: "typed", initialSignature: "" })).toBe(true);
  expect(hasUnsavedWork({ saving: false, committed: false, signature: "same", initialSignature: "same" })).toBe(false);
  expect(hasUnsavedWork({ saving: true, committed: false, signature: "typed", initialSignature: "" })).toBe(false);
});

test("ProcessForm marks the write committed the moment it lands, and will not send a second", () => {
  const source = read("components", "forms", "ProcessForm.tsx");
  const code = withoutComments(source);

  const landed = between(code, "const outcome = await saveOrQueue", "if (outcome.queued)");
  expect(landed, "the write must be marked committed as soon as saveOrQueue returns").toContain("setCommitted(true)");
  expect(landed).toContain("await saveOrQueue");

  expect(code, "`committed` must never be cleared — the record does not become unwritten").not.toContain(
    "setCommitted(false)"
  );

  const submit = bodyOf(code, "async function submit(): Promise<void>");
  expect(submit, "submit must refuse to run once the write has landed").toContain("if (committed) return;");
  expect(code).toMatch(/disabled=\{saving \|\| committed(?: \|\| [^}]+)?\}/);

  expect(code).toContain("const dirty = hasUnsavedWork({ saving, committed, signature, initialSignature })");
});

test("the custom-sections editor's prompt only leaves once the server took the definition", () => {
  const source = read("components", "designworkshop", "CustomSectionsEditor.tsx");
  expect(source).toContain("useLeaveGuard(dirty, () => setLeavePromptOpen(true))");
  expect(source).toContain("async function save(): Promise<boolean>");
  const dialog = between(source, "<UnsavedChangesDialog", "/>");
  expect(dialog).toContain("if (saved) router.back()");
  expect(dialog).toContain("setSections(baseline)");
});

const STORED_START = "2026-03-12";
const STORED_END = "2026-03-14";

test("a date outside a bound is named as outside it, and says which bound", () => {
  const typed = parseTypedDate("16/03/2026");
  expect(typed, "16/03/2026 must parse — the refusal is about the bound, not the text").toBeTruthy();
  expect(dateOutsideBounds(typed as Date, undefined, STORED_END)).toBe("max");
  expect(dateOutsideBounds(parseTypedDate("18/03/2026") as Date, STORED_START, undefined)).toBeNull();
  expect(dateOutsideBounds(parseTypedDate("14/03/2026") as Date, undefined, STORED_END)).toBeNull();
  expect(dateOutsideBounds(parseTypedDate("12/03/2026") as Date, STORED_START, undefined)).toBeNull();
  expect(dateOutsideBounds(typed as Date, undefined, undefined)).toBeNull();
  expect(dateOutsideBounds(typed as Date, undefined, "")).toBeNull();
});

test("the refusal names the sibling field and both dates, in dd/mm/yyyy", () => {
  const typed = parseTypedDate("16/03/2026") as Date;
  const message = boundRefusalMessage(typed, "max", STORED_END, "End date");

  expect(message).toContain("End date");
  expect(message).toContain("16/03/2026");
  expect(message).toContain("14/03/2026");
  expect(message).not.toContain("03/16");
  expect(message).not.toContain("2026-03-14");
  expect(message).toContain("after");

  const anonymous = boundRefusalMessage(typed, "max", STORED_END);
  expect(anonymous).toContain("16/03/2026");
  expect(anonymous).toContain("latest");

  const lower = boundRefusalMessage(parseTypedDate("10/03/2026") as Date, "min", STORED_START, "Start date");
  expect(lower).toContain("before");
  expect(lower).toContain("Start date");
  expect(lower).toContain(formatDisplayDate(parseTypedDate("12/03/2026") as Date));
});

test("DateField keeps a refused date on screen instead of reverting on blur", () => {
  const source = read("components", "forms", "DateTimeField.tsx");

  const revert = bodyOf(source, "function revert()");
  expect(revert).toContain("if (refusal) return;");

  const handleText = bodyOf(source, "function handleText(next: string)");
  expect(handleText).toContain("dateOutsideBounds(parsed, min, max)");
  expect(handleText).toContain("setRefusal({ date: parsed, bound: outside })");
  expect(handleText).toContain("if (!parsed) return;");

  expect(source).toContain("invalid={invalid || refusal !== null}");
  expect(source).toContain("refusal ? refusalId : null");

  expect(source).toContain("if (dateOutsideBounds(refusal.date, min, max)) return;");
});

test("the stage form tells DateField what the bound is called", () => {
  const source = read("components", "designworkshop", "FieldInput.tsx");
  const branch = between(source, "const range = dateRangePartner(entity, field);", "case \"TIME\":");
  expect(branch).toContain("minLabel={range?.role === \"end\" ? range.partner.label : undefined}");
  expect(branch).toContain("maxLabel={range?.role === \"start\" ? range.partner.label : undefined}");
});
