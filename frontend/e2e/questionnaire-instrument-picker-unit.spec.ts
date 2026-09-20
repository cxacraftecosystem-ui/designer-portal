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

const PICKER = read("components/questionnaire/InstrumentPicker.tsx");
const PICKER_CODE = stripComments(PICKER);
const RECORD = read("app/(protected)/questionnaire/page.tsx");
const RECORD_CODE = stripComments(RECORD);
const ANSWER = read("app/(protected)/questionnaires/[id]/answer/page.tsx");
const ANSWER_CODE = stripComments(ANSWER);

/**
 * The region the questionnaire form contract slices and asserts an EQUALITY over — from the opening
 * form tag to the first `</form>`. Recreated here with the contract's own anchor so this file fails
 * for the same reason `backend/tests/test_questionnaire_form_contract.py` would, but in the gate
 * that actually runs on a frontend change.
 */
function formRegion(source: string): string {
  const open = /<form\b[^>]*onSubmit=\{submit\}/.exec(source);
  expect(open, "the capture form's anchor moved; the contract test will fail too").not.toBeNull();
  const from = open!.index;
  const to = source.indexOf("</form>", from);
  expect(to, "the capture form is never closed").toBeGreaterThan(from);
  return source.slice(from, to);
}

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

test.describe("the record page's picker", () => {
  test("is gated on the designer predicate", () => {
    expect(RECORD_CODE).toContain("allowed={canRunDesignWorkshops(user)}");
  });

  test("choosing a form navigates and never re-points this form", () => {
    expect(RECORD_CODE).toContain("router.push(`/questionnaires/${next}/answer`)");
    // Choosing the shared instrument on the screen that IS the shared instrument is a no-op, not a
    // navigation to the page you are already on.
    expect(RECORD_CODE).toContain("if (next === SHARED_INSTRUMENT) return;");
  });

  test("no instrument id is ever sent with an interview", () => {
    /*
      The column does not exist, and the FK on `QuestionnaireResponse.questionId` means it would not
      be enough if it did. This is the assertion that fails if somebody "finishes the job".
    */
    expect(RECORD_CODE).not.toContain("questionnaireId");
  });

  test("the picker sits OUTSIDE the region the form contract slices", () => {
    /*
      `test_questionnaire_form_contract.py` asserts an EQUALITY over the `label="…"` literals between
      the form's opening tag and the first `</form>`, against the handset's `QuestionnaireForm`. A
      ninth control inside that region is a red build whatever it is — and would owe a matching box
      on Android and a row in all three walkthrough registers.
    */
    const region = formRegion(RECORD_CODE);
    expect(region, "the instrument picker was placed inside the contracted form").not.toContain("InstrumentPicker");
  });

  test("the contracted label literals still hold the form region, in order", () => {
    /*
      This is the SAME equality `test_questionnaire_form_contract.py` performs against the handset's
      `QuestionnaireForm`, restated in the gate that actually runs on a frontend change — the backend
      suite is not in the web gate, so a regrouping that moved a box would otherwise go red on main.

      "Answer" is in the list and is NOT one of the eight header fields: it is the per-question box
      inside the sections loop, drawn once per question, and the scanner sees the one literal in the
      source that produces all of them. The workshop pair is absent for the opposite reason — the two
      boxes come from `WorkshopPicker`, which carries its own labels in its own file.
    */
    const region = formRegion(RECORD_CODE);
    const labels = [...region.matchAll(/label="([^"]+)"/g)].map((match) => match[1]);
    expect(labels, "a label literal entered or left the contracted region").toEqual([
      "Interview title",
      "Place",
      "Language",
      "Status",
      "Artisans interviewed",
      "Answer",
      "Interview notes"
    ]);
  });

  test("the form is still one element with its key, its ref and its handler", () => {
    // Splitting it into per-card forms, or renaming the handler, breaks the contract's anchor regex
    // AND `questionnaire-capture.spec.ts`, which locates the instrument sections as `form.panel
    // details`.
    expect(RECORD_CODE).toContain('key={editingInterview?.id ?? "new"}');
    expect(RECORD_CODE).toContain("ref={captureFormRef}");
    expect(RECORD_CODE).toContain("onSubmit={submit}");
    expect(RECORD_CODE, "the `panel` class is a selector a browser spec depends on").toMatch(
      /className="panel grid gap-4/
    );
  });
});

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

// ══════════════════════════════════════════════════════════════════════════════════════════════
// 4. THE RECORD PAGE'S OWN MEGA CARDS
// ══════════════════════════════════════════════════════════════════════════════════════════════

test.describe("the record page's mega cards", () => {
  test("there are four, and each is colour-coded with a distinct tone", () => {
    const tones = [...RECORD_CODE.matchAll(/tone="(\w+)"/g)].map((match) => match[1]);
    expect(tones.length, "a mega card lost its tone").toBe(4);
    expect(new Set(tones).size, "two cards on one screen share a tone, which is the opposite of coding").toBe(4);
  });

  test("the capture card opens on a first visit and the other three do not", () => {
    /*
      NOT a softening of "stay minimized unless it is clicked upon". `/questionnaire?new=1` is what
      the dashboard tile and `guide/steps.ts` both link to and it expects to land on the create form,
      and `questionnaire-capture.spec.ts` asserts the first instrument section is visible with no
      clicks at all. A collapsed form would send both to a shut card with nothing on screen to say
      the page had finished loading.
    */
    expect(RECORD_CODE).toContain('useMegaCards("questionnaire", ["capture"])');
    // And the dashboard, where the ruling was actually made, passes no defaults at all.
    const dashboard = stripComments(read("app/(protected)/dashboard/page.tsx"));
    expect(dashboard).toContain('useMegaCards("dashboard")');
  });

  test("the two screens keep separate remembered sets", () => {
    // Different groups with the same-looking ids; one shared key would let opening a card on one
    // screen open something unrelated on the other.
    expect(RECORD_CODE).toContain('"questionnaire"');
    expect(stripComments(read("app/(protected)/dashboard/page.tsx"))).toContain('"dashboard"');
  });

  test("the two full-bleed cards say they are full-bleed", () => {
    // The form is ONE element by contract and the table carries `min-w-[980px]`; either in half a
    // page is a horizontal scrollbar inside a card inside a rail.
    expect((RECORD_CODE.match(/span="full"/g) ?? []).length).toBe(2);
  });

  test("the rail is the owner's geometry", () => {
    expect(RECORD_CODE).toContain('className="grid items-start gap-5 lg:grid-cols-2"');
  });

  test("the recorded-interviews count is the server's total, not the loaded page's length", () => {
    // `data.items` is one page. Counting it tells a reader with four hundred interviews that they
    // have twenty.
    expect(RECORD_CODE).toContain("count={data?.total ?? 0}");
    expect(RECORD_CODE).not.toContain("count={data?.items.length");
  });

  test("the completion matrix still fetches on its first open and only then", () => {
    /*
      It used to hang off `Accordion`'s `onOpenChange`. The mega card is controlled by the page, so
      the signal is the `expanded` prop going true — and the `opened` ref is what keeps it a FIRST
      open rather than a fetch on every collapse and expand.
    */
    expect(RECORD_CODE).toContain("if (!expanded || opened.current) return;");
    expect(RECORD_CODE).toContain("opened.current = true;");
  });

  test("the builder's failure message stays outside the toggle", () => {
    // Reading the error must not also collapse the editor you were in.
    expect(RECORD_CODE).toContain("headerRight={message ?");
  });

  test("the old Accordion shell is fully gone from this page", () => {
    // Two disclosures nested inside each other is the failure a half-finished conversion produces.
    expect(RECORD_CODE).not.toContain("<Accordion");
    expect(RECORD_CODE).not.toContain("</Accordion>");
  });
});
