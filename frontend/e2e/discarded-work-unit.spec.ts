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

/**
 * WORK A DESIGNER TYPED, THROWN AWAY BY THE CONTROL THEY PRESSED NEXT.
 *
 * Five defects were reported together and four of them were one defect on four screens: a courtyard
 * screen holds answers that exist nowhere else, and an exit beside it discards them without a word.
 * The questionnaire answer screen had three such exits (the round back arrow, the header's "Edit the
 * questionnaire" link, and the Sitting dropdown, which did not even navigate — it re-seeded every box
 * from another sitting's stored answers and emptied the dirty set in the same commit, so a later
 * press of "Save this section" sent nothing and said nothing). `ProcessForm` had one: it computed
 * `dirty`, imported the dialog, and handed the flag to its own Cancel button only, so the header's
 * arrow — the arrow that prompts on five sibling forms — dropped a six-step process with its media.
 * The custom-sections editor had one, and behind it the whole authored instrument a fortnight of
 * fieldwork would be recorded against, held in React state with nothing on disk.
 *
 * The fifth is the other side of the same coin: a date the designer typed was VALID, was refused by a
 * bound derived from a sibling field, and the box reverted on blur with nothing said — so the earlier
 * end of a range could not be moved forward without first clearing the later end, a rule stated on no
 * screen, and the record kept a start date nobody chose.
 *
 * WHY THIS SPEC IS A SOURCE READ AND NOT A BROWSER RUN, stated plainly because it is a real weakness
 * rather than a preference. What is being pinned for the four guard findings is structural — "this
 * screen registers an interceptor", "this exit goes through the prompt" — and there is no React
 * renderer in this repository's devDependencies (Playwright is the whole of it), so mounting a
 * component is not available at all; the browser half is `back-control.spec.ts`, which needs a signed-
 * in dev server and skips without one. `derived-fields-unit.spec.ts` reads `FieldInput.tsx` the same
 * way and for the same reason. Every assertion below therefore fails against the code as it was —
 * each names the exact substring the old file lacked — and none of them proves the prompt PAINTS.
 * When a rendering test runner arrives, the guard cases belong in it.
 *
 * The date half is not a source read: `dateOutsideBounds` and `boundRefusalMessage` were pulled out
 * of the component precisely so the refusal — the part that was wrong — could be executed here.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

/** The body of a named function, brace-matched, so an assertion cannot drift into its neighbour. */
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

/** The text between two markers — for a JSX element, which has no function body to match. */
function between(source: string, from: string, to: string): string {
  const start = source.indexOf(from);
  expect(start, `${from} not found — has the file been restructured?`).toBeGreaterThan(-1);
  const end = source.indexOf(to, start);
  expect(end, `${to} not found after ${from}`).toBeGreaterThan(-1);
  return source.slice(start, end);
}

/* ────────────────────────────────────────────────────────────────────────────
 * The four screens that let unsaved work leave
 * ──────────────────────────────────────────────────────────────────────────── */

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
    // THE ONE LINE EACH OF THESE FILES WAS MISSING. `UnsavedChangesProvider` holds a single
    // interceptor slot and `BackButton` consults it with `if (interceptLeave()) return;` before
    // either navigation branch; a screen that never registers leaves the slot null, `intercept()`
    // returns `interceptor.current?.() ?? false`, and the arrow navigates. Importing the DIALOG is
    // not enough and was exactly the trap `ProcessForm` was in — it imported it and wired it to its
    // own Cancel button, so the footer asked and the header did not.
    expect(source, "must import the guard, not only the dialog").toContain(
      'from "@/components/UnsavedChangesGuard"'
    );
    expect(source).toContain("useLeaveGuard(");
    expect(source).toContain("<UnsavedChangesDialog");
  });

  test(`${screen.name} warns before the tab closes`, () => {
    const source = read(...screen.path);
    // The other half, and it is a different exit rather than a belt-and-braces repeat: closing the
    // tab or reloading never reaches the router, so no interceptor can see it. A screen with only
    // one of the two is still losing work, just by the other door.
    expect(source).toContain("beforeunload");
  });
}

test("the Sitting dropdown asks before it re-seeds the boxes", () => {
  const source = read("app", "(protected)", "questionnaires", "[id]", "answer", "page.tsx");
  const dropdown = between(source, "value={entryId}", "</FieldBlock>");

  // The reported defect, in one assertion: the dropdown used to call `setEntryId(next)` inline.
  // `entry` is `useMemo`-derived from `entryId`, so that fired the seeding effect, which overwrote
  // `draft` and `notes` from the other sitting's stored answers and reset `dirty.current` to an
  // empty set — eleven answers taken in front of a respondent gone, with the record of their being
  // unsaved gone with them.
  expect(dropdown, "the dropdown must not switch sittings itself").not.toContain("setEntryId(");
  expect(dropdown).toContain('requestExit({ kind: "sitting"');

  // And the switch itself still exists — behind the gate, not deleted.
  const leaveNow = bodyOf(source, "const leaveNow = useCallback(");
  expect(leaveNow).toContain("setEntryId(exit.entryId)");

  // `requestExit` is the gate: nothing unsaved goes straight out, otherwise the prompt goes up and
  // the exit is held until Save or Discard answers for it.
  const requestExit = bodyOf(source, "const requestExit = useCallback(");
  expect(requestExit).toContain("dirty.current.size === 0");
  expect(requestExit).toContain("leaveNow(exit)");
  expect(requestExit).toContain("setPendingExit(exit)");
});

/**
 * The rest of the enclosing block, from `at` to the `}` that closes whatever block `at` sits in.
 *
 * Used to ask "is this write paired with the mirror" as a question about the STATEMENT LIST the
 * write lives in, rather than about the file as a whole — which is the difference between a test
 * that bites and the one this replaced.
 */
/**
 * The file with its comments blanked out, so a census of CODE cannot be thrown by PROSE.
 *
 * Not fussiness: the first run of the pairing loop below failed on a `//` comment in the page that
 * quotes `dirty.current.add(...)` while explaining why the bare call is forbidden — a house-style
 * comment naming the defect is exactly what this repository asks for, and a structural test that
 * punishes writing one would be quietly training the next author to stop. Comments are replaced by
 * spaces rather than deleted so every reported line number still matches the real file.
 *
 * The scan is deliberately simple (it does not track string or regex literals), which is safe here
 * because the file it reads contains no `//` or `/*` inside a literal — checked with
 * `grep -n '://' page.tsx`, which returns nothing. If that ever stops being true, the failure is
 * loud (a mangled slice), not silent.
 */
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
  // `useLeaveGuard` takes a BOOLEAN and re-reads it on every commit; `dirty.current` is a Set
  // mutated in place, and mutating a ref commits nothing. Handing the guard `dirty.current.size > 0`
  // computed once would leave it holding `false` for the whole first section a designer types —
  // a guard that is registered and never fires, which is worse than none because it looks fixed.
  expect(source).toContain("useLeaveGuard(dirtyCount > 0");
  expect(source).toContain("const syncDirty = useCallback(() => setDirtyCount(dirty.current.size)");

  /*
    WHY THIS IS A PAIRING TEST AND NOT A COUNT.

    What stood here counted occurrences of `syncDirty()` and asserted `>= 4`, with a comment
    claiming three writes to the set existed. The census was wrong — there were FIVE — and the two
    it omitted were the only two that happen while a designer is typing: the answer box's `onChange`
    and the note box's `onChange` both called `dirty.current.add(question.id)` and never mirrored it.
    All four counted `syncDirty()` calls sat beside writes that EMPTY the set, so `dirtyCount` was 0
    for the whole of a section being filled in, `useLeaveGuard` held `false`, and `beforeunload` was
    never installed. THE ASSERTION PASSED WITH THE DEFECT FULLY PRESENT and was read as cover for it.

    A count of one token can always be satisfied by code that has nothing to do with the rule. So
    the rule is now asserted as a relationship: enumerate every write to the set and require each
    one to be mirrored inside its OWN statement block. Add a sixth write anywhere in this file
    without a `syncDirty()` beside it and this fails, naming the line.
  */
  const code = withoutComments(source);
  const writes = /dirty\.current\.(?:add|delete|clear)\(|dirty\.current\s*=/g;
  const sites: number[] = [];
  for (let hit = writes.exec(code); hit; hit = writes.exec(code)) sites.push(hit.index);

  // The census is not hard-coded, but it must not collapse to nothing: an "everything passed"
  // produced by deleting the writes (or renaming the ref) is the other way this test could stop
  // meaning anything. Five is what the file holds today — two seeding resets, the post-save delete
  // loop, Discard's reset, and the per-question `add` inside `markAnswerDirty`.
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

  // And the two writes that happen while a designer is WORKING reach the mirror. The loop above
  // proves the pairing wherever a write is; this proves the typing handlers are still write sites
  // at all, rather than having quietly stopped marking anything dirty (which would satisfy the
  // loop by having nothing to check).
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

  // `BackButton`'s interceptor cannot cover a `<Link>` — it is a different element entirely — so
  // this exit needed its own handler, and it was the one exit the finder's remedy could not reuse.
  expect(link).toContain("event.preventDefault()");
  expect(link).toContain('setPendingExit({ kind: "edit" })');
  // A ctrl/⌘/middle click opens a SECOND tab and leaves this one, and its unsaved answers, exactly
  // where they were. Prompting for that would be a false alarm on the one click that costs nothing.
  expect(link).toContain("event.metaKey || event.ctrlKey");
});

test("ProcessForm's guard is wired to the same flag its Cancel button uses", () => {
  const source = read("components", "forms", "ProcessForm.tsx");
  expect(source).toContain("useLeaveGuard(dirty, () => setGuardOpen(true))");
  // The dialog was already correct and already rendered; only the registration was missing, which
  // is why the one-line fix is the whole fix here.
  expect(source).toContain("onDiscard=");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The other half of the same guard: what it must NOT offer
 * ──────────────────────────────────────────────────────────────────────────── */

test("a save that partly failed is not unsaved work", () => {
  // The state the guard used to mishandle, spelled out as values. ProcessForm uploads its media
  // AFTER the record is written — the uploads need the process id and the step ids the server just
  // minted — so a failed upload returns with the record saved, the form still mounted, `saving`
  // back to false in the `finally`, and the signature still different from the mount snapshot
  // because the signature describes what was TYPED. Every term except `committed` therefore says
  // "unsaved work", and the header arrow reached a dialog whose Save button re-ran `submit()`:
  // a second POST (a duplicate process, with duplicate step rows) on the create path, and on an
  // edit a re-upload of every attachment including the ones that had just succeeded.
  expect(
    hasUnsavedWork({ saving: false, committed: true, signature: "typed", initialSignature: "" }),
    "a written record with a failed attachment is not work to be saved again"
  ).toBe(false);

  // And the guard still guards. These are the states it exists for, and none of them may be lost to
  // the fix above: work typed and not yet sent, with nothing in flight.
  expect(hasUnsavedWork({ saving: false, committed: false, signature: "typed", initialSignature: "" })).toBe(true);
  // An untouched form prompts about nothing — the automatic workshop default and a carried artisan
  // are both excluded from the signature upstream for this reason.
  expect(hasUnsavedWork({ saving: false, committed: false, signature: "same", initialSignature: "same" })).toBe(false);
  // Mid-write: the dialog has its own busy state and prompting here is noise.
  expect(hasUnsavedWork({ saving: true, committed: false, signature: "typed", initialSignature: "" })).toBe(false);
});

test("ProcessForm marks the write committed the moment it lands, and will not send a second", () => {
  const source = read("components", "forms", "ProcessForm.tsx");
  const code = withoutComments(source);

  // The flag is set from the ONE place that knows the write happened, before any of the follow-up
  // work (media, the carry bag, the names re-derived from the response) can fail. Asserted as a
  // slice so it cannot be satisfied by a `setCommitted(true)` somewhere harmless.
  const landed = between(code, "const outcome = await saveOrQueue", "if (outcome.queued)");
  expect(landed, "the write must be marked committed as soon as saveOrQueue returns").toContain("setCommitted(true)");
  // Queued counts: an outbox entry replays on its own, so re-submitting would file it twice.
  expect(landed).toContain("await saveOrQueue");

  // It latches. Clearing it anywhere would put the re-submit path back, one state change further
  // away and harder to see.
  expect(code, "`committed` must never be cleared — the record does not become unwritten").not.toContain(
    "setCommitted(false)"
  );

  // Both routes into a second write are closed, not just the guard's: `submit` refuses outright, and
  // the footer button (the other control still on screen after a partial failure) is disabled.
  const submit = bodyOf(code, "async function submit(): Promise<void>");
  expect(submit, "submit must refuse to run once the write has landed").toContain("if (committed) return;");
  expect(code).toContain("disabled={saving || committed}");

  // The guard itself still reads one flag, and that flag is still the one the Cancel button and the
  // beforeunload effect read — see the test above.
  expect(code).toContain("const dirty = hasUnsavedWork({ saving, committed, signature, initialSignature })");
});

test("the custom-sections editor's prompt only leaves once the server took the definition", () => {
  const source = read("components", "designworkshop", "CustomSectionsEditor.tsx");
  expect(source).toContain("useLeaveGuard(dirty, () => setLeavePromptOpen(true))");
  // `save()` returns a boolean for exactly this: a 409 from a stale digest or a 422 listing the
  // rules the definition breaks leaves the draft on screen deliberately, and navigating away on the
  // back of one would discard the work the refusal is asking the designer to correct.
  expect(source).toContain("async function save(): Promise<boolean>");
  const dialog = between(source, "<UnsavedChangesDialog", "/>");
  expect(dialog).toContain("if (saved) router.back()");
  // Discard puts the editable projection of what the server holds back, never `stored` itself —
  // the same rule the Discard BUTTON follows, because a cached route can put this screen back.
  expect(dialog).toContain("setSections(baseline)");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The date that was refused in silence
 * ──────────────────────────────────────────────────────────────────────────── */

/** Stage 1's `workshopIdentity` as the report knows it: sanctioned 12–14 March, revised to 16–18. */
const STORED_START = "2026-03-12";
const STORED_END = "2026-03-14";

test("a date outside a bound is named as outside it, and says which bound", () => {
  const typed = parseTypedDate("16/03/2026");
  expect(typed, "16/03/2026 must parse — the refusal is about the bound, not the text").toBeTruthy();
  // The exact keystroke from the report: a new start date after the current end date.
  expect(dateOutsideBounds(typed as Date, undefined, STORED_END)).toBe("max");
  // The other end of the same range, which is the half that always worked: 18/03 is above End
  // date's `min` of 12/03, so it committed — which is how the record ended up 12/03–18/03 with a
  // start date nobody chose.
  expect(dateOutsideBounds(parseTypedDate("18/03/2026") as Date, STORED_START, undefined)).toBeNull();
  // The bound is inclusive on both sides: a one-day workshop is a workshop.
  expect(dateOutsideBounds(parseTypedDate("14/03/2026") as Date, undefined, STORED_END)).toBeNull();
  expect(dateOutsideBounds(parseTypedDate("12/03/2026") as Date, STORED_START, undefined)).toBeNull();
  // An absent partner imposes nothing — a stage is filled in over three days and the end is very
  // often typed before the start is known.
  expect(dateOutsideBounds(typed as Date, undefined, undefined)).toBeNull();
  expect(dateOutsideBounds(typed as Date, undefined, "")).toBeNull();
});

test("the refusal names the sibling field and both dates, in dd/mm/yyyy", () => {
  const typed = parseTypedDate("16/03/2026") as Date;
  const message = boundRefusalMessage(typed, "max", STORED_END, "End date");

  // WHY THE NAME IS THE POINT. "16/03/2026 is not allowed" is a dead end: the only way through is to
  // move the END first, and until this sentence existed that rule appeared on no screen in the app.
  expect(message).toContain("End date");
  expect(message).toContain("16/03/2026");
  expect(message).toContain("14/03/2026");
  // dd/mm/yyyy on BOTH dates. A message that printed 03/14/2026 would reintroduce the exact
  // ambiguity `DateField` exists to remove, inside the field's own error text.
  expect(message).not.toContain("03/16");
  expect(message).not.toContain("2026-03-14");
  expect(message).toContain("after");

  // The unlabelled form still says what happened and to what — a caller that supplies a bound
  // without a name gets a weaker sentence, never a silent one.
  const anonymous = boundRefusalMessage(typed, "max", STORED_END);
  expect(anonymous).toContain("16/03/2026");
  expect(anonymous).toContain("latest");

  // The mirror case, for the range's other end.
  const lower = boundRefusalMessage(parseTypedDate("10/03/2026") as Date, "min", STORED_START, "Start date");
  expect(lower).toContain("before");
  expect(lower).toContain("Start date");
  expect(lower).toContain(formatDisplayDate(parseTypedDate("12/03/2026") as Date));
});

test("DateField keeps a refused date on screen instead of reverting on blur", () => {
  const source = read("components", "forms", "DateTimeField.tsx");

  // THE LINE THE WHOLE FINDING IS ABOUT. `revert()` is wired unconditionally to the input's
  // `onBlur`, so a Tab to the next field rewrote the box from the STORED value — the designer typed
  // 16/03/2026, read it back, tabbed on, and it became 12/03/2026 with nothing said anywhere.
  const revert = bodyOf(source, "function revert()");
  expect(revert).toContain("if (refusal) return;");

  // And the refusal is recorded rather than swallowed. Both bound checks used to be bare `return`s,
  // indistinguishable from the half-typed-text branch two lines above them.
  const handleText = bodyOf(source, "function handleText(next: string)");
  expect(handleText).toContain("dateOutsideBounds(parsed, min, max)");
  expect(handleText).toContain("setRefusal({ date: parsed, bound: outside })");
  // Unparseable text is still NOT a refusal: there is nothing to explain and nothing to keep.
  expect(handleText).toContain("if (!parsed) return;");

  // The box says so to a screen reader as well as to the eye, and the message is wired into
  // `aria-describedby` alongside whatever the caller already described it with.
  expect(source).toContain("invalid={invalid || refusal !== null}");
  expect(source).toContain("refusal ? refusalId : null");

  // A refused date commits itself once the bound moves. Without this the fix is half of one: the
  // designer reads "change End date first", changes it, and is left looking at a box holding text
  // that was never stored.
  expect(source).toContain("if (dateOutsideBounds(refusal.date, min, max)) return;");
});

test("the stage form tells DateField what the bound is called", () => {
  const source = read("components", "designworkshop", "FieldInput.tsx");
  const branch = between(source, "const range = dateRangePartner(entity, field);", "case \"TIME\":");
  // `dateRangePartner` is the only thing in the app that knows `startDate` is capped by `endDate`,
  // and the partner's LABEL is the designer-facing name of the field they have to change first.
  // It exists here and nowhere below, so it has to travel with the bound it produced.
  expect(branch).toContain("minLabel={range?.role === \"end\" ? range.partner.label : undefined}");
  expect(branch).toContain("maxLabel={range?.role === \"start\" ? range.partner.label : undefined}");
});
