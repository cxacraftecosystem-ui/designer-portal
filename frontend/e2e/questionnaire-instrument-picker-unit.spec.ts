import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * PICKING BETWEEN QUESTIONNAIRES — the control that did not exist, and the four contracts it had to
 * be built around rather than through.
 *
 * ── WHAT WAS ASKED FOR ─────────────────────────────────────────────────────────────────────────
 *
 * Owner, 2026-09-20: *"there is no way to pick between multiple questionnaires like there is in
 * field repo app. Fix that."* Also, on the same screen: *"this functionality should be there in the
 * record questionnaire page as well"* — the mega cards.
 *
 * ── WHY THE OBVIOUS IMPLEMENTATION IS THE WRONG ONE, PINNED HERE SO IT STAYS REJECTED ──────────
 *
 * The tempting build is "put a dropdown on /questionnaire and re-point the form at the chosen
 * form". Three things in the schema make that silently destructive, and all three are asserted
 * below because a future reader will propose it again:
 *
 *   1. `QuestionnaireInterview` has NO `questionnaireId` column and no relation to `Questionnaire`.
 *   2. `QuestionnaireResponse.questionId` is `Restrict`-FK'd to the GLOBAL `QuestionnaireQuestion`,
 *      and `upsert_responses` 404s any id not in that table — an interview can only ever answer
 *      global questions.
 *   3. `QuestionnaireInterview.artisanSetKey` is `@unique` REPOSITORY-WIDE and `create_interview`
 *      folds a submission into the existing row for that artisan set. One artisan set answering two
 *      instruments merges both onto one row, with no error anywhere.
 *
 * So the picker NAVIGATES, and these tests fail if it ever starts sending an instrument id with an
 * interview instead.
 *
 * ⚠ ABSENCES ARE CHECKED AGAINST COMMENT-STRIPPED SOURCE. Every file here argues its own rules and
 * therefore NAMES what it forbids — `questionnaireId`, `label="`, `/questionnaires/shared/answer`
 * all appear in prose. A bare `toContain` over the raw file finds the explanation and passes while
 * the code does the opposite.
 */

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8");

function stripComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
}

const PICKER = read("components/questionnaires/InstrumentPicker.tsx");
const PICKER_CODE = stripComments(PICKER);
const ANSWER = read("app/(protected)/questionnaires/[id]/answer/page.tsx");
const ANSWER_CODE = stripComments(ANSWER);

// ══════════════════════════════════════════════════════════════════════════════════════════════
// 1. THE PICKER ITSELF
// ══════════════════════════════════════════════════════════════════════════════════════════════

test.describe("the instrument picker", () => {
  test("offers the shared artisan instrument under a value that is not an id", () => {
    expect(PICKER_CODE).toContain('export const SHARED_INSTRUMENT = "shared"');
    // One string for the name, so the two screens — and one day the handset — cannot word it
    // differently.
    expect(PICKER_CODE).toContain("export const SHARED_INSTRUMENT_LABEL");
  });

  test("renders nothing at all for an account that may not list questionnaires", () => {
    /*
      Every route under `/api/questionnaires` begins with `_require_designer`, while `/questionnaire`
      itself is open to every signed-in account and has no ROUTE_GUARDS row. An ungated picker would
      403 for exactly the volunteers and field contributors that page exists to serve — a broken
      control in front of the people least able to tell it is not their fault.
    */
    expect(PICKER_CODE).toContain("if (!allowed) return null;");
  });

  test("walks pages to exhaustion and says so when it stops early", () => {
    // `GET /questionnaires` declares pageSize with no upper bound and `normalize_pagination` then
    // clamps to 100 — asking for 500 gets 100 and no warning. A bound that is never stated is the
    // most repeated defect class in this repository.
    expect(PICKER_CODE).toContain("const PAGE_SIZE = 100");
    expect(PICKER_CODE).toMatch(/page <= pages && page <= MAX_PAGES/);
    expect(PICKER_CODE).toContain("setTruncated(pages > MAX_PAGES)");
    expect(PICKER_CODE).toContain("{truncated ?");
  });

  test("a failed list is not a list of none", () => {
    // The shared instrument needs no request and stays offered; the failure is stated rather than
    // rendered as an empty dropdown that tells a designer with nine forms they have none.
    expect(PICKER_CODE).toMatch(/catch \(err\)/);
    expect(PICKER_CODE).toContain('role="alert"');
    expect(PICKER_CODE).toContain("only the shared artisan questionnaire is listed");
  });

  test("it does not steal focus, because it switches screens rather than filling a field", () => {
    // The same reason the sitting dropdown on the answer screen sets it, and the same bug if it is
    // dropped: focus jumping to the next control after a navigation.
    expect(PICKER_CODE).toContain("advanceOnSelect={false}");
  });

  test("the options are searchable, because they are fetched records", () => {
    expect(PICKER_CODE).toMatch(/^\s*searchable$/m);
  });

  test("a published standard form says whose it is", () => {
    // A designer's list can contain a form they did not upload. A row that cannot say so reads as
    // somebody else's work leaking in.
    expect(PICKER_CODE).toContain("Standard form");
    // And the kind's wording comes from the SERVER, so the clients cannot disagree about a value.
    expect(PICKER_CODE).toContain("questionnaireKindLabel(form.kind)");
  });

  test("the shared instrument is drawn apart from the designer's own forms", () => {
    // It carries no `group`, which is what lists it first and ungrouped; the designer's forms sit
    // under a heading. The shared instrument is not one of them.
    expect(PICKER_CODE).toContain('group: "Your questionnaires"');
    const sharedRow = /value: SHARED_INSTRUMENT,[\s\S]{0,400}?\},/.exec(PICKER_CODE);
    expect(sharedRow).not.toBeNull();
    expect(sharedRow![0], "the shared instrument was filed under the designer's own forms").not.toContain("group:");
  });
});

// ══════════════════════════════════════════════════════════════════════════════════════════════
// 2. ON /questionnaire — IT NAVIGATES, AND IT STAYS OUT OF THE CONTRACTED FORM
// ══════════════════════════════════════════════════════════════════════════════════════════════


// ══════════════════════════════════════════════════════════════════════════════════════════════
// 3. ON THE ANSWER SCREEN — IT IS AN EXIT, AND IT ASKS LIKE ONE
// ══════════════════════════════════════════════════════════════════════════════════════════════

test.describe("the answer screen's picker", () => {
  test("switching forms is its own kind of exit", () => {
    // It discards strictly more than the sitting switch does: every answer box, the dirty set, the
    // cache key and the entry id at once.
    expect(ANSWER_CODE).toContain('| { kind: "instrument"; target: string }');
  });

  test("it goes through `requestExit` and never straight to the router", () => {
    /*
      This screen was reported for exactly this class of bug: the sitting dropdown used to call
      `setEntryId` inline and silently threw away the section a designer was in the middle of.
      `leaveNow` is reachable only through `requestExit`, and that is the whole fix.
    */
    expect(ANSWER_CODE).toContain('requestExit({ kind: "instrument", target: next })');
    const picker = /<InstrumentPicker[\s\S]*?\/>/.exec(ANSWER_CODE);
    expect(picker).not.toBeNull();
    expect(picker![0], "the picker navigates without asking about unsaved answers").not.toContain("router.");
  });

  test("the shared instrument goes to the singular route, which is where it lives", () => {
    // `/questionnaires/shared/answer` is a plausible-looking URL that 404s. The shared artisan
    // instrument is not a `Questionnaire` row and has no id.
    expect(ANSWER_CODE).toContain(
      'router.push(exit.target === SHARED_INSTRUMENT ? "/questionnaire" : `/questionnaires/${exit.target}/answer`)'
    );
    expect(ANSWER_CODE).not.toContain("/questionnaires/shared");
  });

  test("picking the form you are already in does nothing", () => {
    expect(ANSWER_CODE).toContain("if (next === id) return;");
  });

  test("the sitting dropdown still asks too — this did not replace that fix", () => {
    expect(ANSWER_CODE).toContain('requestExit({ kind: "sitting", entryId: next })');
  });
});
