import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * REUSING A QUESTIONNAIRE AT ANOTHER WORKSHOP — the client-side properties that decide whether the
 * server's guarantees survive the trip to the screen.
 *
 * The server copies: a new `Questionnaire` row carrying the questions and NO sitting and NO answer,
 * with the target workshop checked through `load_workshop_or_404(..., for_edit=True)` — the same
 * helper the three existing attachment routes use. `backend/tests/test_questionnaire_reuse.py` pins
 * all of that. What it cannot pin is what this client asks for and what it says about the answer, and
 * every test below fails against a plausible version of these files. The first four were the original
 * set:
 *
 * 1. THE TARGET LIST IS THE PAGE'S OWN SCOPED ONE. `GET /design-workshops` is narrowed server-side by
 *    `visible_to_clause` (`createdById = me OR viewers.some(userId = me)`), which is the same door
 *    `load_workshop_or_404` opens. A dialog that fetched its own list would be a second source of
 *    truth for "which workshops may this account write to", and the version that drifted would be
 *    the one offering targets the server then refuses.
 * 2. AN UNPICKED WORKSHOP IS OMITTED, NOT SENT EMPTY. `QuestionnaireReuse.designWorkshopId` absent
 *    means "don't attach it yet"; a `""` is a workshop id that exists nowhere and would 404 the
 *    whole reuse for the ordinary case of making an unattached template.
 * 3. THE REUSE REPORT IS NOT LABELLED AS AN IMPORT. `UploadReport` used to branch
 *    `action === "answersNotImported" ? A : B`, so a third action fell into B — whose heading is
 *    "The answers in this workbook were recorded under your name". A reuse copies no answer and
 *    involves no workbook, so that branch would have made the panel state the exact falsehood the
 *    provenance field exists to prevent.
 * 4. THE CONTROL IS UNGATED, matching the server, which does NOT require ownership here because the
 *    instrument already leaves the system for any designer through `/question-set.xlsx`. A control
 *    hidden behind `mayEdit` would be the classic half-fixed permission in reverse: the UI refusing
 *    what the API allows, with no message anywhere saying so.
 *
 * THREE MORE, ADDED BY THE SECOND REVIEW ROUND, each of them a SENTENCE that was false rather than a
 * request that was wrong:
 *
 * 5. THE PREVIEWED NAME IS THE NAME THE ROW WILL GET. `reuse_title` counts its default up, and the
 *    dialog has to count with it — INCLUDING for "Don't attach it yet", which is the dialog's own
 *    default and the case the look-up used to skip entirely.
 * 6. THE REPORT PANEL NAMES THE COPY, NOT "THIS QUESTIONNAIRE". It is drawn on the page of the
 *    questionnaire that was copied FROM, so an unqualified subject made the screen say the form whose
 *    fieldwork is running is a copy carrying no answers — two lines under its own banner saying that
 *    form is untouched.
 * 7. THE CLIENT'S SUFFIX IS PINNED TO THE SERVER'S. Every other assertion on both sides tests the
 *    counting SHAPE and not the word, so the two constants could drift apart in silence.
 *
 * WHY THESE ARE SOURCE READS. This repository has no React renderer in its devDependencies —
 * Playwright is the whole of it — so mounting a page is not available at all. The request bodies and
 * the prop wiring are built inline inside components, and `questionnaire-workshop-filter-unit.spec.ts`,
 * `derived-fields-unit.spec.ts` and `discarded-work-unit.spec.ts` read their subjects the same way for
 * the same reason. None of this proves the browser PAINTS the dialog; that half belongs in a signed-in
 * spec when one exists for this screen.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const DIALOG = ["components", "questionnaires", "ReuseDialog.tsx"];
const REPORT = ["components", "questionnaires", "UploadReport.tsx"];
const LIST = ["app", "(protected)", "questionnaires", "page.tsx"];
const DETAIL = ["app", "(protected)", "questionnaires", "[id]", "page.tsx"];
const LIB = ["lib", "questionnaireForms.ts"];

/** The text between two markers, so an assertion cannot drift into a neighbouring call. */
function between(source: string, from: string, to: string): string {
  const start = source.indexOf(from);
  expect(start, `${from} not found — has the file been restructured?`).toBeGreaterThan(-1);
  const end = source.indexOf(to, start);
  expect(end, `${to} not found after ${from}`).toBeGreaterThan(-1);
  return source.slice(start, end);
}

/** Source with every comment stripped, so a phrase QUOTED in a docstring cannot satisfy — or trip —
 * an assertion about the code. Both files under test document the traps they avoid by name. */
function code(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .map((line) => line.replace(/(^|[^:])\/\/.*$/, "$1"))
    .join("\n");
}

test("the dialog is handed the page's scoped workshop list and fetches none of its own", () => {
  const dialog = code(read(...DIALOG));

  // The one call that would make a second, UNSCOPED source of truth. `GET /design-workshops` is the
  // scoped one, so the point is not that the endpoint is wrong — it is that the page has already
  // asked, and a component that asks again can answer differently.
  expect(dialog, "the dialog must not fetch workshops itself").not.toContain("listDesignWorkshops");
  expect(dialog, "and it must not reach for the unscoped questionnaire options either").not.toContain(
    "listQuestionnaireOptions"
  );
  // It takes them as a prop instead, exactly as `UploadDialog` does.
  expect(dialog).toContain("workshops: ReuseTarget[]");

  for (const [label, page] of [
    ["the list page", read(...LIST)],
    ["the detail page", read(...DETAIL)]
  ] as const) {
    const mount = between(page, "<ReuseDialog", "/>");
    expect(mount, `${label} must pass its own workshop list into the dialog`).toContain(
      "workshops={workshops.map("
    );
  }
});

test("an unpicked workshop is omitted from the body, never sent as an empty string", () => {
  const body = between(code(read(...DIALOG)), "await reuseQuestionnaire(questionnaireId, {", "});");

  // Spread-when-truthy, not `designWorkshopId: designWorkshopId || null`. An absent key is what the
  // server reads as "don't attach it yet"; `""` is a workshop id that exists nowhere and 404s the
  // whole reuse, and `null` would be indistinguishable from the absent key only by luck of the
  // server's own truthiness test.
  expect(body, "an unattached copy is the ordinary case and must not send a blank id").toContain(
    "...(designWorkshopId ? { designWorkshopId } : {})"
  );
  // Same shape for the title: `""` fails `min_length=1` on the API, and omitting it is what lets the
  // server count its own default up against the titles already at the target.
  expect(body).toContain("...(title.trim() ? { title: title.trim() } : {})");

  // And nothing in this dialog inherits the SOURCE's workshop as the default target — the least
  // likely thing anybody pressing "Reuse at another workshop" wants. `sourceWorkshopId` is read to
  // ANNOTATE the option and to caution, never to seed the value.
  expect(body).not.toContain("sourceWorkshopId");
  expect(code(read(...DIALOG))).toContain('useState("")');
});

test("the report panel names the reuse for what it is and never as an import", () => {
  const report = code(read(...REPORT));

  // A lookup with an explicit `reused` arm, not a two-way ternary on `answersNotImported`.
  expect(report, "the third provenance action needs its own arm").toContain(
    'provenance.action === "reused"'
  );
  // The heading that must NEVER be reachable for a reuse: no workbook exists and no answer was
  // recorded under anybody's name. If this string is chosen by a bare `else`, a reuse gets it.
  const importedArm = between(report, 'if (provenance.action === "answersImported")', "}");
  expect(importedArm).toContain("The answers in this workbook were recorded under your name");
  // The fallback arm is the CAUTIOUS one — amber, "not recorded against your copy" — so an action
  // this panel has never heard of is honest about not knowing rather than confidently wrong.
  expect(report).toContain('title: "The answers in this workbook were not recorded against your copy"');
  expect(report).toContain("border-amber-500/30 bg-amber-100");

  // And the panel's own copy stops claiming a workbook when there was none.
  expect(report, "a reuse has no spreadsheet behind it").toContain('"What the upload did"');
  expect(report, "a reuse is a copy, not an upload").toContain("What was copied");

  // The client type has to admit the action, or the arm above is unreachable code.
  expect(read(...LIB)).toContain('action: "answersImported" | "answersNotImported" | "reused";');
});

test("the reuse control is offered to every designer, as the server offers it", () => {
  const list = read(...LIST);
  const detail = read(...DETAIL);

  // On the list: a row action on EVERY row, beside the ungated "Download question set". The server
  // applies `_require_designer` and NOT `_require_owner` to this route, on the stated ground that
  // the questions already leave the system for any designer as a spreadsheet.
  const rowActions = between(list, "<RowActions>", "</RowActions>");
  expect(rowActions, "the row action is missing").toContain("Reuse at another workshop");
  expect(rowActions, "and it must not be conditioned on anything").not.toContain("mayEdit");
  expect(rowActions).toContain("setReuseRow(row)");

  // On the detail page: in the header, and OUTSIDE every `mayEdit ?` branch. Measured by cutting the
  // header at the first gated control — `Download .xlsx`, which is owner-gated because its workbook
  // carries the sittings — and requiring the reuse button to sit before it.
  const header = between(detail, "<PageHeader", "/>\n\n      {error ?");
  const ungated = between(header, "Download question set", "{mayEdit ?");
  expect(ungated, "the reuse button belongs with the ungated controls").toContain(
    "Reuse at another workshop"
  );

  // NOT in the details panel beside the "Design workshop" dropdown, deliberately: those two controls
  // differ by exactly one thing — whether the ORIGINAL keeps its workshop — and side by side they
  // invite a designer wanting a second copy to MOVE a live instrument off the workshop whose
  // fieldwork is already running against it.
  const detailsPanel = between(detail, "onSubmit={renameQuestionnaire}", "</form>");
  expect(detailsPanel).not.toContain("Reuse at another workshop");
});

/**
 * THE REUSE REPORT IS ABOUT A ROW THAT IS NOT ON SCREEN, and every sentence in it with a subject has
 * to say so.
 *
 * The bug this pins. `UploadReport`'s `reused` arm headed itself "This questionnaire is a copy, and it
 * carries no recorded answers" — a claim about the page's own subject. The detail page fills that
 * panel while showing the ORIGINAL, two lines under its own banner saying "This questionnaire and
 * every sitting against it are untouched". One screen said both. The tallies compounded it: "3
 * questions added · 2 sections added", under a heading with no subject, reads as an edit just made to
 * the form whose fieldwork is running. This is the same class of false provenance statement the
 * `skinFor` lookup was written to eliminate — that fix corrected the ACTION and left the SUBJECT.
 */
test("the reuse report names the copy rather than the page it is drawn on", () => {
  const report = code(read(...REPORT));

  // The panel takes the subject in rather than assuming the page's own.
  expect(report, "the panel needs the copy's title to talk about it").toContain("subject?: string | null;");
  // The provenance heading is built from it, and the bare wording survives only as the fallback for
  // the upload paths, where the report really is about the page's subject.
  expect(report).toContain("is a copy, and it carries no recorded answers");
  expect(report, "the subject must reach the heading").toContain("skinFor(provenance, subject)");
  // "added" is an account of an edit. Nothing was added to anything by a reuse.
  expect(report).toContain('copied ? "questions copied" : "questions added"');
  expect(report).toContain('copied ? "sections copied" : "sections added"');

  // BOTH PAGES PASS IT. The detail page passes the copy's title; the list page holds it in its own
  // state, because there "this questionnaire" names nothing at all.
  const detail = read(...DETAIL);
  expect(between(detail, "{report ? <UploadReport", "/>"), "the detail page must name the copy").toContain(
    "subject={reused?.title ?? null}"
  );
  const list = read(...LIST);
  expect(between(list, "{report ? <UploadReport", "/>"), "the list page must name the copy").toContain(
    "subject={reportSubject}"
  );
  // And the subject is CLEARED by an upload, or the next upload report would be labelled with the
  // last copy's name — and the detail page's reuse banner would keep claiming an edited form is
  // untouched.
  expect(code(detail), "an upload must drop the reuse subject").toContain("setReused(null)");
  expect(code(list), "an upload must drop the reuse subject").toContain("setReportSubject(null)");
});

/**
 * THE PICKER IS NOT THE REFUSAL, and the sentence that says so is not conditional on the list being
 * empty.
 *
 * The bug this pins. The dialog's docstring promised "an empty or short list gets a SENTENCE
 * explaining the alternative route"; the code was `workshops.length ? <Dropdown> : <p>`, so a
 * TRUNCATED list — the pages fetch `pageSize: 100` — got a searchable dropdown and no notice. That is
 * trap 1 of this repo's searchable-dropdown rule: a client-side filter over a server-truncated list
 * answers "No matches", and in a picker absence reads as "I may not reuse into that workshop".
 */
test("the alternative route is offered whatever the length of the workshop list", () => {
  const dialog = code(read(...DIALOG));
  const block = between(dialog, 'FieldBlock label="Design workshop for the copy"', "</FieldBlock>");

  // The dropdown is still only drawn when there is something to draw…
  expect(block).toContain("workshops.length ?");
  // …but the way out is drawn on BOTH sides of that branch. Measured by cutting at the branch: the
  // sentence has to appear before the `) : (` as well as after it.
  const withList = between(block, "workshops.length ?", ") : (");
  expect(withList, "a full dropdown needs the sentence too — the list is one page").toContain(
    "attach the copy from its own page afterwards"
  );
  expect(withList, "and it must say why the list is not the whole truth").toContain("one page of the newest workshops");
});

/**
 * THE FIFTH PROPERTY, added after the review: the dialog's preview of the copy's NAME has to agree
 * with the name the server will actually give it, because the warning built on that preview tells a
 * designer to go and change something.
 *
 * The bug this pins. `reuse_title` only names the copy when NO title is sent, and when it does it
 * COUNTS UP — "X (reused)", then "X (reused 2)" — against the titles already at the target. The
 * dialog mirrored the suffix and not the count, so with the box left empty it showed "X (reused)",
 * decided that name collided, and printed an amber panel saying "both will appear in that workshop's
 * report annexure under the same name" — at the exact moment the server was about to number the copy
 * so that they would not. It advised a correction against a fact that was not true.
 */
test("the copy's previewed name agrees with the name the server will give it", () => {
  const dialog = code(read(...DIALOG));

  // The counting is mirrored, not just the suffix. Without the loop the preview stops at "(reused)".
  expect(dialog, "the client must count up as reuse_title does").toContain(
    "candidate = `${base} (${REUSED} ${n})`"
  );
  // And the preview is counted against the titles actually found at the target, not against nothing.
  expect(dialog).toContain("reusedTitle(sourceTitle, atTarget ?? [])");

  // ONLY A TYPED TITLE CAN COLLIDE: a sent title is used verbatim, an absent one gets numbered. The
  // guard is what stops the amber panel firing on the case the server already handles.
  const collides = between(dialog, "const collides =", "const numbered");
  expect(collides, "an empty box cannot collide — the server numbers it").toContain("Boolean(typed) &&");

  // The look that feeds all of the above must count against the SAME set the server does.
  // `reuse_title` deliberately does not filter `isActive`, and the list endpoint defaults it to true.
  expect(dialog, "a deactivated form still owns its title on the server").toContain("activeOnly: false");

  // AND IT COVERS "DON'T ATTACH IT YET", the dialog's own default. The effect used to early-return on
  // a falsy `designWorkshopId`, so the unattached case previewed the UNCOUNTED name while the server
  // counted against `{designWorkshopId: None, ownerId: me}` — the very "shows a name the row would
  // NOT get" defect this test exists to close, left standing in the default branch.
  const look = between(dialog, "const look = designWorkshopId", ".then((result)");
  expect(look, "the unattached case must be looked up too").toContain("mineOnly: true");
  expect(dialog, "and filtered to the rows that are attached to nothing").toContain(
    "rows.filter((row) => row.designWorkshopId === null)"
  );
  // The early return that used to swallow that case must be gone: the guard is `open` alone.
  expect(dialog).not.toContain("if (!open || !designWorkshopId)");
});

/**
 * THE SUFFIX ITSELF, pinned across the two languages.
 *
 * Everything else about the naming is tested as a SHAPE: the spec above builds its expectation out of
 * `REUSED`, and `test_questionnaire_reuse.py` asserts "(reused)" server-side only. So the client and
 * the server could have come to spell one word differently with nothing failing — the placeholder
 * would preview "X (copied)" for a row about to be named "X (reused)", and the amber warning built on
 * that preview would advise a correction against a name that does not exist. `REUSED`'s own comment
 * says it exists to stop exactly that, which is an argument for reading the other side rather than
 * trusting the comment.
 */
test("the client's reuse suffix is the server's own literal", () => {
  const service = readFileSync(
    join(__dirname, "..", "..", "backend", "app", "services", "questionnaire_forms.py"),
    "utf8"
  );
  const server = /REUSE_TITLE_SUFFIX = "([^"]+)"/.exec(service);
  expect(server, "REUSE_TITLE_SUFFIX has moved or been renamed — the client mirrors it").not.toBeNull();

  const client = /const REUSED = "([^"]+)"/.exec(read(...DIALOG));
  expect(client, "REUSED has moved or been renamed").not.toBeNull();

  expect(client?.[1], "the placeholder would preview a name the row will not get").toBe(server?.[1]);

  // And the server still builds its default the way the client mirrors it, so the pin is over the
  // whole string and not just the word inside it.
  expect(service).toContain('f"{base} ({REUSE_TITLE_SUFFIX})"');
  expect(service).toContain('f"{base} ({REUSE_TITLE_SUFFIX} {n})"');
});
