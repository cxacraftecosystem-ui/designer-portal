/**
 * EDITING A RECORDED INTERVIEW — the rules the edit path keeps, and the three keys its body leaves
 * out on purpose.
 *
 * ── THE DEFECT THIS FILE EXISTS FOR ─────────────────────────────────────────────────────────────
 *
 * The browser never had an edit form for a recorded interview. Not "lost one" — never had one. What
 * it had was a BUTTON: `app/(protected)/data/page.tsx`'s questionnaire browse arm declared
 * `editHref: () => "/questionnaire"`, the only one of eight arms discarding the id its own signature
 * is handed. "Edit record" on an interview a researcher had just drilled into therefore opened the
 * blank CREATE form — the same screen the dashboard's Questionnaire tile opens, with the clicked
 * record nowhere on it. Filling it in filed a SECOND sitting, which under one-entry-per-artisan-set
 * either folded the answers into a shared entry nobody asked for, or came back 409.
 *
 * `useEditDeepLink` was written for exactly that shape on /crafts, /workshops and /processes. This
 * page is the fourth inline form and was simply never wired to it.
 *
 * ── WHY SOURCE-READING, AND WHY THE COMMENTS ARE STRIPPED FIRST ─────────────────────────────────
 *
 * There is no React renderer in this repository's devDependencies, so the page cannot be mounted;
 * `dashboard-tile-parity-unit.spec.ts` and `ministry-surface-unit.spec.ts` read source for the same
 * reason. And the page EXPLAINS each of the three omissions below at length — a good explanation of
 * a prohibition names the thing prohibited — so an assertion that `recordedAt` is absent, read
 * against the raw file, fails on the paragraph saying why it is absent. Comments out, code in.
 *
 * ── WHAT IS DELIBERATELY NOT ASSERTED HERE ──────────────────────────────────────────────────────
 *
 * The sister repository's version of this form carries an INSTRUMENT picker and a rule that an edit
 * must not let it move. Designer-portal has ONE global capture instrument — `QuestionnaireInterview`
 * has no `questionnaireId` column and `GET /questionnaire/sections` takes no instrument id — so
 * there is no picker to disable and no wrong instrument to draw. Assertions about it would be
 * green-forever theatre about a control that does not exist. Said here so a reader comparing the two
 * suites finds the reason rather than the gap.
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

const PAGE_RAW = read("app/(protected)/questionnaire/page.tsx");
const BROWSER_RAW = read("app/(protected)/data/page.tsx");

/**
 * Source with comments removed, quotes respected.
 *
 * The same helper `ministry-surface-unit.spec.ts` needs and for the same stated reason: "that block
 * states its own prohibitions in prose … so a substring check over the raw text fails on the very
 * sentence that forbids what it is hunting for".
 */
function stripComments(source: string): string {
  let out = "";
  let index = 0;
  let stringChar: string | null = null;

  while (index < source.length) {
    const char = source[index];
    const next = source[index + 1];

    if (stringChar) {
      out += char;
      if (char === "\\") {
        out += next ?? "";
        index += 2;
        continue;
      }
      if (char === stringChar) stringChar = null;
      index += 1;
      continue;
    }
    if (char === '"' || char === "'" || char === "`") {
      stringChar = char;
      out += char;
      index += 1;
      continue;
    }
    if (char === "/" && next === "/") {
      while (index < source.length && source[index] !== "\n") index += 1;
      continue;
    }
    if (char === "/" && next === "*") {
      index += 2;
      while (index < source.length && !(source[index] === "*" && source[index + 1] === "/")) index += 1;
      index += 2;
      continue;
    }
    out += char;
    index += 1;
  }
  return out;
}

const PAGE = stripComments(PAGE_RAW);
const BROWSER = stripComments(BROWSER_RAW);

/** The `submit` handler's body — where the two request shapes are decided. */
function submitBody(): string {
  const from = PAGE.indexOf("const commonPayload = {");
  expect(from, "the submit handler no longer builds `commonPayload`").toBeGreaterThan(-1);
  const to = PAGE.indexOf("media: [", from);
  expect(to, "the save call no longer follows the payload").toBeGreaterThan(from);
  return PAGE.slice(from, to);
}

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * The deep link
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

test.describe("the page reads ?edit= the way every other inline form does", () => {
  test("through the shared hook, not a fourth hand-rolled deep link", () => {
    // Four inline-form pages, one hook. Its header lists the obligations a hand-rolled version gets
    // wrong: fetch BY ID rather than search the page of rows on screen, strip the parameter with
    // `router.replace` so a cancelled edit is not put back under the Back button, defer the scroll
    // one frame so the remounted form has its final height, and release the guard in cleanup so
    // StrictMode's double mount cannot deadlock the load in development.
    expect(PAGE).toContain("useEditDeepLink<QuestionnaireInterview>({");
    expect(PAGE).toContain('endpoint: "/questionnaire/interviews"');
    expect(PAGE).toContain('basePath: "/questionnaire"');
    expect(PAGE).toContain("targetRef: captureFormRef");
  });

  test("inside the Suspense boundary Next 16 needs for useSearchParams", () => {
    // The hook calls `useSearchParams`, which Next 16 requires be wrapped. This page was already
    // split into a page component and a body for that reason, so the hook lands inside it for free —
    // asserted rather than assumed, because a later flattening of the two would break the build in a
    // way whose error names Suspense and not this hook.
    expect(PAGE).toContain("function QuestionnairePageBody()");
    expect(PAGE).toContain("<Suspense");
  });

  test("onNew is omitted, because ?new=1 is a render mode on this page and not a one-shot intent", () => {
    /*
      The dashboard's Questionnaire tile links to `/questionnaire?new=1` and this page reads that
      parameter on EVERY render. Handing it to the hook would let the hook consume it once and strip
      it — closing the create form as it opened. `/processes` carries the identical omission for the
      identical reason and the hook's own header names the case.
    */
    const call = PAGE.slice(PAGE.indexOf("useEditDeepLink<QuestionnaireInterview>({"));
    expect(call.slice(0, 600)).not.toContain("onNew:");
  });

  test("the deep link waits for an identity rather than guessing at a capability", () => {
    // Taking an interview is open to every signed-in account, and whether THIS account may correct
    // THIS sitting is `guard_record_edit`'s answer inside the PATCH. A client-side predicate here
    // would either hide the form from somebody entitled to it or open one that 403s on save.
    expect(PAGE).toContain("allowed: !!user");
  });
});

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * The two request shapes
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

test.describe("an edit corrects the sitting and a capture files a new one", () => {
  test("an edit PATCHes the record's own path; a capture POSTs to the collection", () => {
    // `saveOrQueue` already took a method, so an edit made with no signal is banked and replays as
    // the PATCH it was — rather than as a second sitting, which is the whole defect.
    expect(PAGE).toContain('method: editingId ? "PATCH" : "POST"');
    expect(PAGE).toContain("endpoint: editingId ? `/questionnaire/interviews/${editingId}`");
  });

  test("the update body never restamps when the capture happened", () => {
    /*
      `recordedAt` and `recordedTimezone` are ACCEPTED by the update schema, which is exactly why
      leaving them out had to be a decision rather than an oversight. The "Captured at" row is
      re-read from a form that has just remounted in an office, so sending them would restamp last
      week's fieldwork every time somebody corrects a typo.
    */
    const body = submitBody();
    // The TRUE arm of the ternary only — from `? {` to the `: {` that opens the create arm. Slicing
    // from `editingId` would take both arms and the assertion would read the create body, which is
    // exactly where these two keys legitimately live.
    const ternary = body.slice(body.indexOf("const interviewPayload = editingId"));
    const editArm = ternary.slice(ternary.indexOf("? {"), ternary.indexOf(": {"));
    expect(editArm.length, "the payload ternary no longer has two arms").toBeGreaterThan(10);
    expect(editArm).not.toContain("recordedAt");
    expect(editArm).not.toContain("recordedTimezone");
    // …and the CREATE arm still sends both, so this is an omission on one path and not a deletion.
    expect(ternary.slice(ternary.indexOf(": {"))).toContain("recordedAt");
  });

  test("location is spread conditionally and never sent as null", () => {
    // `forbid_clearing_location` is "omit to keep, send to replace, never null", so an edit typed
    // indoors with no fix must leave the key OFF. A null would be read as a request to clear the
    // coordinates the sitting was actually recorded at.
    expect(submitBody()).toContain("...(location ? { location } : {})");
  });

  test("the workshop keys ARE sent, and only because the picker is seeded from the record", () => {
    /*
      Owner ruling, 2026-09-20: a correction may also re-file the sitting. That is safe ONLY because
      `useWorkshopPicker` is seeded with the record's own two ids and `isEdit: true` — so a picker
      the reader never touched hands back what was already stored, rather than the "most recent
      workshop I can see" a blank create form would default to. The two assertions belong together:
      the payload key is safe because of the seeding, and neither may be removed alone.
    */
    expect(submitBody()).toContain("workshopId: workshop.workshopId || null");
    expect(PAGE).toContain("initialWorkshopId: editingInterview.workshopId ?? null");
    expect(PAGE).toContain("initialDesignWorkshopId: editingInterview.designWorkshopId ?? null");
    expect(PAGE).toContain("isEdit: true");
    expect(PAGE).toContain("resetKey: editingInterview.id");
  });

  test("no instrument id is sent by either body", () => {
    // Not a rule this page could break — there is no such column here. Asserted because the sister
    // repository's form does send one, and a future reader porting from it would add a key the
    // update schema forbids (`extra="forbid"` → 422 before the handler runs).
    expect(submitBody()).not.toContain("questionnaireId");
  });
});

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * The form, and leaving it
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

test.describe("the form belongs to the record it is showing", () => {
  test("it remounts per record, so the uncontrolled boxes re-seed", () => {
    // Status, the notes, the location and capture rows are uncontrolled `FormData`. Without the key
    // the previous occupant's values stay on screen under the new record's title — the defect
    // `useEditDeepLink`'s own header names the remount as existing for.
    expect(PAGE).toContain('key={editingInterview?.id ?? "new"}');
    expect(PAGE).toContain("ref={captureFormRef}");
  });

  test("the answers are seeded by question id and never by position", () => {
    // A sitting's responses are keyed to the questions that were asked, and an instrument's sections
    // can be reordered, retired and superseded between the sitting and the correction. Matching by
    // index would put last month's answer against this month's question — a wrong answer under a
    // real person's name, saved without anybody typing it.
    expect(PAGE).toContain("if (response.questionId) seeded[response.questionId] = response.answerText ?? \"\"");
  });

  test("both save paths leave edit mode, and the URL goes with them", () => {
    // Including the QUEUED one: a banked PATCH the server has not seen must not leave the form still
    // claiming to edit that record, or the next Save is a second PATCH of a sitting whose first is
    // still in the outbox.
    const calls = PAGE.match(/leaveEditMode\(\);/g) ?? [];
    expect(calls.length, "one of the two save branches does not leave edit mode").toBeGreaterThanOrEqual(2);
    expect(PAGE).toContain('router.replace("/questionnaire")');
  });

  test("cancelling clears the form as well as the mode", () => {
    expect(PAGE).toContain("const resetToCreate = useCallback(");
    expect(PAGE).toContain("Cancel edit");
  });
});

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * What the screen says
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

test.describe("edit mode says what it is and what it will not touch", () => {
  test("the banner names the record and the two things a correction does not change", () => {
    const flat = PAGE_RAW.replace(/\s+/g, " ");
    expect(flat).toContain("Saving corrects this sitting rather than filing another.");
    expect(flat).toContain("Recordings already attached to it are kept");
    expect(flat).toContain("when it was recorded is not changed by a correction made now");
  });

  test("the shared-entry banner is not shown against the record being edited", () => {
    // It keys on the artisan SET, which an open edit normally still has — so it announced the record
    // to itself and promised that saving would "add to" it, which is the opposite of what an edit
    // does.
    expect(PAGE).toContain("existingEntry && existingEntry.id !== editingInterview?.id");
  });

  test("the carry-forward offer is not made over an open edit", () => {
    // Carry-forward prefills a NEW record from the last one. Offered over an edit it would invite
    // overwriting a recorded sitting with another record's values.
    expect(PAGE).toContain("editingInterview ? null : <CarryContextBanner");
  });

  test("the submit button says which of the three acts it performs", () => {
    expect(PAGE).toContain('"Save changes"');
    expect(PAGE).toContain('"Add to shared entry"');
    expect(PAGE).toContain('"Save interview"');
  });

  test("every row offers Edit, outside the admin-view gate", () => {
    /*
      Admin view is a NARROWING switch over admin CHROME. Correcting an interview is ordinary work,
      and whether this account may correct THIS sitting is decided per record on the server — so
      hiding the link behind the toggle would take it from almost everybody entitled to it.
    */
    const rowActions = PAGE.slice(PAGE.indexOf("<RowActions>"), PAGE.indexOf("</RowActions>"));
    expect(rowActions).toContain("href={`/questionnaire?edit=${interview.id}`}");
    const beforeAdminGate = rowActions.slice(0, rowActions.indexOf("adminMode ?"));
    expect(beforeAdminGate, "the Edit link is inside the admin-view gate").toContain("?edit=");
  });
});

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * The button that started it
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

test.describe("the data browser carries the interview id", () => {
  test("its questionnaire arm no longer discards the id its signature declares", () => {
    // THE ASSERTION THE WHOLE FILE IS ABOUT. `editHref: () => "/questionnaire"` landed "Edit record"
    // on the blank create form and filing that in made a second sitting.
    expect(BROWSER).toContain("editHref: (id) => `/questionnaire?edit=${id}`");
    expect(BROWSER, "the arm still throws the id away").not.toContain('editHref: () => "/questionnaire"');
  });

  test("only the one arm with no per-record form declares editHref without an id", () => {
    /*
      Generalised so the next arm added cannot reintroduce the shape — but not to ZERO, because one
      zero-argument arm is correct. `media` has no per-record edit route at all: a media file is
      corrected on `/media`, which is a list and an uploader rather than a form keyed to a row, so
      there is no id for the href to carry. Every other arm has somewhere to carry one to.

      Pinned as a NAMED EXEMPTION rather than a smaller regex, so adding a second zero-argument arm
      fails here and has to be argued for, rather than quietly joining a pattern.
    */
    // No `/s` flag: `[^}]` already spans newlines, and the flag needs an ES2018 target this
    // project does not set — it compiled here and failed `tsc` in the same breath.
    const zeroArg = [...BROWSER.matchAll(/(\w+):\s*\{[^}]*?editHref:\s*\(\s*\)\s*=>/g)].map((m) => m[1]);
    expect(
      zeroArg,
      "a browse arm declares editHref with no parameter, so it drops the record id the way the questionnaire arm did"
    ).toEqual(["media"]);
  });
});

test("these assertions read code and not the comments explaining the code", () => {
  /*
    The guard every source-reading spec in this repository owes, and this one owes it twice over:
    the page argues each of the three omissions at length, so the absence assertions above would
    pass against prose and fail against code — or the reverse — if the stripper ever stopped working.
  */
  expect(PAGE, "the comment stripper let a block comment through").not.toContain("/*");
  // Normalised, because the explanation is prose in a wrapped comment and this assertion is about
  // whether it EXISTS, not about where the formatter broke the line.
  const flatRaw = PAGE_RAW.replace(/\s+/g, " ");
  const flatCode = PAGE.replace(/\s+/g, " ");
  expect(flatRaw, "the page no longer explains why recordedAt is omitted").toContain(
    "restamp last week's fieldwork"
  );
  expect(flatCode, "…but the explanation must not be what the omission assertions are reading").not.toContain(
    "restamp last week's fieldwork"
  );
});
