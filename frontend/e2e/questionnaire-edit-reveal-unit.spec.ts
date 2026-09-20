/**
 * WHAT AN EDIT SHOWS THE RESEARCHER, AND WHAT A COLLIDING ARTISAN SET CAN NOW BE OFFERED.
 *
 * ── THE THREE COMPLAINTS THIS FILE IS ABOUT ─────────────────────────────────────────────────────
 *
 * Owner, 2026-09-20:
 *
 *  1. *"when edit page is opened, already existing entries and media do not show up in the
 *     respective sections, those should show up while editing as well, on both android and web"*.
 *  2. The shared-entry banner said "No questions answered yet" about an interview a researcher had
 *     fully recorded.
 *  3. Two researchers recorded one artisan set as two sittings; correcting the roster on one of them
 *     made its artisan-set key equal the other's and the correction died as a 409 with nowhere to go.
 *
 * ── WHY NONE OF THIS WAS A SEEDING BUG ──────────────────────────────────────────────────────────
 *
 * The answers were seeded correctly the whole time. `seedFromInterview` walks the record's responses
 * by question id and writes them into `answers`, and `submit` would have sent them straight back.
 * They were INVISIBLE, behind three display gates that each looked reasonable alone:
 *
 *   · `DEFAULT_CAPTURE_PREFS` sets `hideAnswers: true`, so the answer box is never drawn;
 *   · only the FIRST section's `<details>` was open, whatever the record held;
 *   · the `<summary>` printed a code and a title and no counts at all, where the handset's section
 *     header has always printed "N questions · M answered · K saved recording(s)".
 *
 * On the default capture settings — one take for a whole section, answer boxes hidden — a fully
 * recorded sitting and an untouched one therefore rendered identically: a column of shut rows.
 *
 * MEDIA IS THE OTHER HALF, and NOT SEEDING THE UPLOAD TRAY IS CORRECT: pre-loading saved clips into
 * `mediaFiles`/`questionAudioFiles` would offer every one of them for upload a second time on the
 * next Save. Android resolved that by drawing saved clips READ-ONLY under the section each belongs
 * to, plus an "Other saved recordings & media" catch-all. The web drew none at all, in either
 * repository — the one edit form here without such a panel.
 *
 * ── WHY SOURCE-READING, AND WHY THE COMMENTS ARE STRIPPED FIRST ─────────────────────────────────
 *
 * There is no React renderer in this repository's devDependencies, so the page cannot be mounted;
 * `questionnaire-edit-unit.spec.ts`, `dashboard-tile-parity-unit.spec.ts` and
 * `ministry-surface-unit.spec.ts` read source for the same reason.
 *
 * And this page now EXPLAINS each of these decisions at length — a good explanation of a defect
 * quotes the defect. The block above the answer gate quotes "already existing entries … do not show
 * up"; the block above `sharedEntryContents` quotes "No questions answered yet" as the sentence it
 * replaces; `SavedClips` quotes `<ExistingMedia linkedRecordType="artisan" …>` while arguing why that
 * panel is not mounted here. Every one of those would satisfy a substring check over the raw file for
 * exactly the string the check is hunting for the ABSENCE of. Comments out, code in.
 *
 * ── WHAT IS ASSERTED AGAINST THE SERVER AND NOT AGAINST A PARAPHRASE ────────────────────────────
 *
 * The refusal contract is read off `backend/app/api/routes/questionnaire.py` in this file too, so the
 * client's constants are held against the ones the server actually sends rather than against a
 * description of them. The holder keys in particular are FLAT and are spelled `existingInterviewId` /
 * `existingInterviewTitle`; a client written against a nested `holder: {id, title}` would compile,
 * pass a type check, and silently never make the offer.
 *
 * ── WHAT IS DELIBERATELY NOT ASSERTED HERE ──────────────────────────────────────────────────────
 *
 * Nothing about `MainActivity.kt`. The handset's wording is the authority for the counts sentence and
 * is copied into `sectionProgressLabel`, but an assertion reaching into `android/` from this suite
 * would tie a web unit test to a file on the other client's release cadence, and the failure would
 * name this page for a change made over there. The parity is argued at the function and pinned here
 * only on the web side.
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

const PAGE_RAW = read("app/(protected)/questionnaire/page.tsx");
const BACKEND = read("../backend/app/api/routes/questionnaire.py");

/**
 * Source with comments removed, quotes respected.
 *
 * The same helper `questionnaire-edit-unit.spec.ts` and `ministry-surface-unit.spec.ts` need, and for
 * the same stated reason: these files argue their own rules in prose, so a substring check over the
 * raw text matches the very sentence that explains what is forbidden.
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

/** Source between two markers, with both ends asserted so a rename reports itself. */
function between(source: string, from: string, to: string, what: string): string {
  const start = source.indexOf(from);
  expect(start, `${what}: ${JSON.stringify(from)} is no longer in the page`).toBeGreaterThan(-1);
  const end = source.indexOf(to, start + from.length);
  expect(end, `${what}: ${JSON.stringify(to)} no longer follows it`).toBeGreaterThan(start);
  return source.slice(start, end);
}

/** The capture `<form>`'s own source — the builder further down the page draws its own sections. */
function captureForm(): string {
  const anchor = PAGE.search(/<form\b[^>]*onSubmit=\{submit\}/);
  expect(anchor, "the capture form's opening tag has moved").toBeGreaterThan(-1);
  return between(PAGE.slice(anchor), "<form", "</form>", "the capture form");
}

/**
 * The instrument's sections loop, inside that form.
 *
 * It ends AT the catch-all panel rather than at `<MultiNoteField`, so "this is drawn once per
 * section" and "this is drawn once for the whole interview" are two different slices of source —
 * which is the distinction the catch-all exists to make.
 */
function sectionsLoop(): string {
  return between(
    captureForm(),
    "orderedGroups.map(",
    '<SavedClips heading="Other saved recordings & media"',
    "the sections loop"
  );
}

/** The shared-entry banner, inside that form. */
function sharedEntryBanner(): string {
  return between(
    captureForm(),
    "A shared entry already exists for this set of artisans",
    "{selectedArtisan ?",
    "the shared-entry banner"
  );
}

/** One named module-level function's body. */
function functionBody(name: string): string {
  return between(PAGE, `function ${name}(`, "\n}\n", `${name}`);
}

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * (a) The saved recordings, drawn read-only, grouped by section
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

test.describe("an edit draws the recordings already saved against the sitting", () => {
  test("per section, and a catch-all for the rest — the handset's two blocks", () => {
    /*
      Android files a saved clip under the section it belongs to and gathers the rest into "Other
      saved recordings & media", so that nothing a sitting holds is invisible. The web drew neither.
    */
    const form = captureForm();
    expect(sectionsLoop()).toContain('heading="Saved recordings & media for this section"');
    expect(form).toContain('<SavedClips heading="Other saved recordings & media" items={savedClips.other}');
    /*
      THE CATCH-ALL IS OUTSIDE THE LOOP, because that is what "belongs to no section" means — drawn
      from inside it, every unplaceable clip would be listed once per section on the page.
    */
    const loopCloses = form.indexOf("})}", form.indexOf("orderedGroups.map("));
    expect(loopCloses).toBeGreaterThan(-1);
    expect(form.indexOf('<SavedClips heading="Other saved recordings & media"')).toBeGreaterThan(loopCloses);
    expect(form.indexOf('<SavedClips heading="Other saved recordings & media"')).toBeLessThan(
      form.indexOf("<MultiNoteField")
    );
  });

  test("read-only: the panel is handed no remove, and cannot reach the upload state", () => {
    /*
      The rule this protects is `seedFromInterview`'s: the tray stays empty on an edit because
      pre-loading stored clips would re-upload them on the next Save. A panel that could write back
      into `mediaFiles` or `questionAudioFiles` would reintroduce exactly that, and one that could
      DELETE would do something worse — on this instrument the clips are the record.
    */
    const panel = between(PAGE, "function SavedClips({", "\n}\n", "SavedClips");
    expect(panel).toContain("<MediaPreviewTile item={preview} onOpen={() => onOpenPreview(preview)} />");
    expect(panel).not.toContain("onRemove");
    expect(panel).not.toContain("setMediaFiles");
    expect(panel).not.toContain("setQuestionAudioFiles");
    expect(panel).not.toContain("apiFetch");
    expect(panel).not.toContain("method: \"DELETE\"");
  });

  test("and the tray is still seeded empty, which is the rule the panel exists to keep", () => {
    const seed = between(PAGE, "const seedFromInterview = useCallback(", "\n  }, []);", "seedFromInterview");
    expect(seed).toContain("setMediaFiles([]);");
    expect(seed).toContain("setQuestionAudioFiles({});");
  });

  test("it reuses this page's own lightbox rather than mounting a second one", () => {
    // `ExistingMedia` carries its own `MediaLightbox`; two dialogs able to answer one gesture is the
    // third reason that panel is not mounted here. The tiles call the page's `setActivePreview`.
    expect(sectionsLoop()).toContain("onOpenPreview={setActivePreview}");
    expect(PAGE).not.toContain("<ExistingMedia");
  });

  test("the rows come off the record already in hand, not a second /media request", () => {
    /*
      `RELATIONS` in backend/app/api/routes/questionnaire.py declares `media`, and
      `GET /questionnaire/interviews/{id}` hydrates it — which is the read `useEditDeepLink` has
      already made. A fetch here would ask for rows the page is holding.
    */
    expect(PAGE).toContain("for (const media of editingInterview?.media ?? []) {");
    expect(BACKEND).toContain('Relation("media", "mediafile", "questionnaireInterviewId", many=True)');
    expect(PAGE).not.toContain('listResource<MediaFile>("/media"');
  });

  test("a clip is placed by its own metadata first and by its caption only as a fallback", () => {
    /*
      `clipBatch` stamps `sectionId`/`questionId` on everything the web uploads, so that half cannot
      be defeated by editing a prompt afterwards. The caption prefixes place the handset's clips and
      the older corpus — and they are a real contract: the server parses the same two strings when it
      derives section coverage.
    */
    const placer = functionBody("savedClipSectionId");
    expect(placer.indexOf('metaString(meta, "sectionId")')).toBeGreaterThan(-1);
    expect(placer.indexOf('metaString(meta, "questionId")')).toBeGreaterThan(-1);
    expect(placer.indexOf('metaString(meta, "sectionId")')).toBeLessThan(placer.indexOf("SECTION_CAPTION_PREFIX"));
    expect(PAGE).toContain('const SECTION_CAPTION_PREFIX = "Section audio:";');
    expect(PAGE).toContain('const QUESTION_CAPTION_PREFIX = "Question audio:";');
  });

  test("an unplaceable clip goes to the catch-all and is never guessed at", () => {
    expect(functionBody("savedClipSectionId")).toContain("return null;");
    expect(PAGE).toContain("if (!sectionId) {\n        other.push(media);");
  });

  test("the longest matching section code wins, so `A` cannot claim `AB1`", () => {
    // Section codes are free text. Under a plain `startsWith` a deployment carrying both `A` and `AB`
    // files AB's clips under A, with nothing on screen to say so.
    expect(PAGE).toContain("function sectionsByCodeLength(");
    expect(functionBody("savedClipSectionId")).toContain("const ranked = sectionsByCodeLength(sections);");
  });
});

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * (b) The recorded answers, without touching the stored preference
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

test.describe("an edit that carries typed words shows them", () => {
  test("the gate is the preference AND whether this record answered this question", () => {
    expect(sectionsLoop()).toContain("{capture.hideAnswers && !recordedAnswerIds.has(question.id) ? null : (");
    expect(sectionsLoop()).not.toContain("{capture.hideAnswers ? null : (");
  });

  test("`hideAnswers` is never written — the reader's stored choice for capture is untouched", () => {
    /*
      Flipping the preference would show the answers and would also unhide the boxes for every future
      capture, as a side effect of one correction. The only writer of these prefs is
      `QuestionnaireCaptureControls`, and `updateCapture` is handed to it and used nowhere else.
    */
    expect(PAGE).not.toContain("hideAnswers:");
    expect(PAGE).not.toContain("updateCapture({");
    expect(PAGE.match(/updateCapture/g) ?? []).toHaveLength(2);
    expect(PAGE).toContain("<QuestionnaireCaptureControls prefs={capture} onChange={updateCapture} />");
  });

  test("the set is read off the RECORD, not off live `answers`", () => {
    /*
      A box drawn because `answers` currently holds text would vanish the moment the researcher
      cleared it — the one keystroke after which they most need the box. The stored record does not
      change while the form is open.
    */
    const memo = between(PAGE, "const recordedAnswerIds = useMemo(", "}, [editingInterview]);", "recordedAnswerIds");
    expect(memo).toContain("for (const response of editingInterview?.responses ?? []) {");
    expect(memo).toContain("answerHasWords(response.answerText)");
    expect(memo).not.toContain("answers[");
  });

  test("an emptied rich-text answer does not count as words, and asks that in its own words", () => {
    /*
      An emptied DOCUMENT still serialises to a non-blank string, so a bare `.trim()` reports every
      cleared answer as answered. And the test is NAMED rather than spelled inline, because
      `questionnaire-voice-note-unit.spec.ts` counts the sites that flatten a stored answer FOR
      DISPLAY — an emptiness check written identically is indistinguishable from a third render site.
    */
    expect(functionBody("answerHasWords")).toContain("plainFromStoredRichText(answerText).trim().length > 0");
    expect(PAGE.match(/plainFromStoredRichText\(response\.answerText\)/g) ?? []).toHaveLength(2);
  });
});

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * (c) The sections that have something in them
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

test.describe("an edit opens the sections the record actually has content in", () => {
  test("`open` is decided by the rule, not by the index", () => {
    expect(sectionsLoop()).toContain(
      "open={sectionOpensOnLoad(group.section.id, index, sectionsWithContent, !!editingInterview)}"
    );
    expect(sectionsLoop()).not.toContain("open={index === 0}");
  });

  test("a create is unchanged: the first section and only the first", () => {
    // `questionnaire-capture.spec.ts` asserts the first instrument section is visible with no clicks
    // at all, and a work surface that opens fully shut reads as a page that has not loaded.
    const rule = functionBody("sectionOpensOnLoad");
    expect(rule).toContain("if (!editing || withContent.size === 0) return index === 0;");
    expect(rule).toContain("return withContent.has(sectionId);");
  });

  test("content means an answer with words OR a saved clip, and both are counted", () => {
    const memo = between(PAGE, "const sectionsWithContent = useMemo(", "}, [savedClips, sections, recordedAnswerIds]);", "sectionsWithContent");
    expect(memo).toContain("new Set<string>(savedClips.bySection.keys())");
    expect(memo).toContain("recordedAnswerIds.has(question.id)");
  });
});

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * (d) The counts on each section's summary
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

test.describe("each section says how much of it is done", () => {
  test("the summary prints questions, answered and saved recordings", () => {
    expect(sectionsLoop()).toContain("{sectionProgressLabel(progress)}");
    const label = functionBody("sectionProgressLabel");
    expect(label).toContain("${progress.questions} questions · ${progress.answered} answered");
    expect(label).toContain("${progress.recordings} saved recording(s)");
  });

  test("a whole-section take marks every question in its section answered", () => {
    // That take IS the answer to all of them — the rule the handset's header already counts by, and
    // the shape of the work on this instrument.
    const progress = functionBody("sectionProgress");
    expect(progress).toContain("const wholeSectionRecorded = input.liveSectionClips > 0 || input.savedClips.some(isWholeSectionTake);");
    expect(progress).toContain("? questions.length");
  });

  test("live clips count as well as saved ones, so the number moves before a save", () => {
    const progress = functionBody("sectionProgress");
    expect(progress).toContain("answerHasWords(input.answers[question.id])");
    expect(progress).toContain("(input.liveQuestionClips[question.id] ?? []).length");
    expect(progress).toContain("input.savedClips.some((media) => savedClipAnswers(media, question))");
    expect(sectionsLoop()).toContain("liveQuestionClips: questionAudioFiles");
  });

  test("a question token never claims a longer one's clip", () => {
    // "D1" must not match "D10 — …". Equality, or the token followed by a space, and nothing else.
    const matcher = functionBody("savedClipAnswers");
    expect(matcher).toContain("return rest === token || rest.startsWith(`${token} `);");
  });
});

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * (e) The banner counts media as well as responses
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

test.describe("the shared-entry banner says what it found", () => {
  test("it measures both columns, not responses alone", () => {
    /*
      On an instrument captured as whole-section audio with the boxes hidden, a correctly recorded
      sitting has ZERO response rows and many media rows — so the old sentence was derived from the
      one column that is empty by design.
    */
    expect(sharedEntryBanner()).toContain(
      "sharedEntryContents(existingEntry.responses?.length ?? 0, existingEntry.media?.length ?? 0)"
    );
  });

  test("and never calls a recorded sitting empty", () => {
    expect(sharedEntryBanner()).not.toContain("No questions answered yet");
    const sentence = functionBody("sharedEntryContents");
    expect(sentence).toContain("${clips}, no typed answers yet.");
    expect(sentence).toContain("${typed} and ${clips} are already on it.");
    expect(sentence.toLowerCase()).not.toContain("empty");
  });

  test("the media the banner counts is on the wire already", () => {
    // `by-artisans` hydrates RELATIONS, and that route's own comment calls itself the first place the
    // existing entry's recordings are drawn. Nothing here needed a request.
    expect(BACKEND).toContain('interview = await db.questionnaireinterview.find_unique(where={"artisanSetKey": set_key})');
    expect(BACKEND).toContain("await hydrate_relations([interview], RELATIONS)");
  });
});

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * (f) The merge offer
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

test.describe("a refused save names the holder and offers to fold into it", () => {
  test("the client branches on the server's codes, and they are the server's codes", () => {
    expect(PAGE).toContain('const DUPLICATE_SET_CODE = "duplicate_artisan_set";');
    expect(PAGE).toContain('const MERGE_CONFLICT_CODE = "merge_answer_conflict";');
    expect(BACKEND).toContain('_DUPLICATE_SET_CODE = "duplicate_artisan_set"');
    expect(BACKEND).toContain('_MERGE_CONFLICT_CODE = "merge_answer_conflict"');
  });

  test("the holder keys are the FLAT ones the route actually sends", () => {
    /*
      ⚠ There is no `holder: {id, title}` object on this route. `duplicate_set_detail` answers
      `existingInterviewId` / `existingInterviewTitle`, both PRESENT AND NULL when the holding row was
      deleted between the unique violation and the lookup. A client written against a nested shape
      would compile, type-check, and silently never make the offer.
    */
    const reader = functionBody("duplicateSetHolder");
    expect(reader).toContain('metaString(detail, "existingInterviewId")');
    expect(reader).toContain('metaString(detail, "existingInterviewTitle")');
    expect(BACKEND).toContain('"existingInterviewId": get_value(holder, "id"),');
    expect(BACKEND).toContain('"existingInterviewTitle": get_value(holder, "title"),');
    // A vanished holder is "no offer to make", and the page falls back to the server's sentence.
    expect(reader).toContain("if (!id) return null;");
  });

  test("the move calls the merge route, and only after a confirmation", () => {
    const offer = between(PAGE, "async function offerMergeIntoHolder(", "\n  }\n", "offerMergeIntoHolder");
    expect(offer).toContain("const ok = await confirm({");
    expect(offer).toContain("if (!ok) return false;");
    expect(offer).toContain("`/questionnaire/interviews/${sourceId}/merge-into/${holder.id}`");
    expect(offer).toContain('method: "POST"');
    // The confirmation NAMES the interview the work is going into — that is what the holder keys are
    // for, and an unnamed "merge?" is exactly the unactionable refusal they were added to end.
    expect(offer).toContain("`“${holder.title}” already covers this set of artisans`");
    // The repository's own dialog, and its non-destructive tone: nothing is deleted by this move.
    expect(PAGE).toContain('import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";');
    expect(offer).toContain('tone: "warning"');
    // Confirm comes before the request, not after it.
    expect(offer.indexOf("if (!ok) return false;")).toBeLessThan(offer.indexOf("merge-into"));
  });

  test("the route the client calls is the route the server declares", () => {
    expect(BACKEND).toContain('@router.post("/interviews/{interview_id}/merge-into/{target_id}")');
  });

  test("the offer is made on an EDIT and never on a create", () => {
    /*
      `create_interview` folds a colliding POST into the holder by itself, so on a create there is no
      source row to move and no id to put in the path.
    */
    const handler = between(PAGE, "const holder = duplicateSetHolder(err);", "setError(err instanceof Error", "the catch");
    expect(handler).toContain("if (editingId && holder) {");
    expect(handler).toContain("if (await offerMergeIntoHolder(editingId, holder)) return;");
    // Declining still says what happened, and says WHICH interview is in the way.
    expect(handler).toContain("already covers this exact set of artisans");
  });

  test("a conflicting-questions refusal is listed, never swallowed", () => {
    /*
      The server refuses outright when both sittings answer one question with different words and
      names every such question, because picking a winner would destroy a researcher's words under a
      200 and the losing row goes with the deleted source.
    */
    const offer = between(PAGE, "async function offerMergeIntoHolder(", "\n  }\n", "offerMergeIntoHolder");
    expect(offer).toContain("questions: mergeConflictQuestions(err)");
    const reader = functionBody("mergeConflictQuestions");
    expect(reader).toContain("Array.isArray(detail.conflicts) ? detail.conflicts : []");
    expect(reader).toContain('metaString(bag, "sectionCode")');
    expect(BACKEND).toContain('"conflicts": conflicts,');
    expect(PAGE).toContain("{mergeOutcome.questions.map((question) => (");
  });

  test("the verdict is a word before it is a colour", () => {
    /*
      The theme defines exactly three amber tokens (100/500/800) and a reader who never gets the wash
      still has to be able to tell a completed fold from a refused one.
    */
    const panel = between(PAGE, "{mergeOutcome ? (", ") : null}", "the merge panel");
    expect(panel).toContain('{mergeOutcome.moved ? "Moved." : "Nothing was moved."}');
    expect(panel).toContain("border-amber-500 bg-amber-100");
    expect(panel).toContain('role="alert"');
  });

  test("a completed fold leaves edit mode, because the source row is gone", () => {
    // The next Save from a form still claiming that id would PATCH a deleted record, and a surviving
    // `?edit=` would re-open it on the next read.
    const offer = between(PAGE, "async function offerMergeIntoHolder(", "\n  }\n", "offerMergeIntoHolder");
    expect(offer).toContain("resetToCreate();");
    expect(offer).toContain("else await loadInterviews();");
  });

  test("the outcome has its own banner, because `error` is wiped by the refresh this flow performs", () => {
    expect(PAGE).toContain(
      "const [mergeOutcome, setMergeOutcome] = useState<{ moved: boolean; message: string; questions: string[] } | null>("
    );
    expect(PAGE).toContain("setMergeOutcome(null);");
  });
});

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * (g) The banner's edit-mode fork — claimed in a comment, missing from the JSX
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

test.describe("the banner tells the truth about what saving will do", () => {
  test("it forks on edit mode, which is what the comment above it always claimed", () => {
    /*
      THE DEFECT WAS A LIE ON SCREEN AND NOT A WORDING NIT. The banner is suppressed against the
      record being edited, so it only ever draws over a DIFFERENT entry — and in edit mode "saving
      below adds your answers and media to X — it will not create a duplicate" is the OPPOSITE of
      what happens: a create folds into the holder, an edit into an occupied set is REFUSED. The
      comment directly above the JSX said the wording "forks on exactly that", and it did not.
    */
    const banner = sharedEntryBanner();
    expect(banner).toContain("{editingInterview ? (");
    expect(banner).toContain("will\n                  be refused");
    expect(banner).toContain("it will not create a duplicate");
    // Both arms of one ternary: the create sentence is now reachable only when NOT editing.
    expect(banner.indexOf("{editingInterview ? (")).toBeLessThan(banner.indexOf("it will not create a duplicate"));
    expect(banner.indexOf("will\n                  be refused")).toBeLessThan(
      banner.indexOf("it will not create a duplicate")
    );
  });

  test("and it points at the ways out, including the offer the refusal now carries", () => {
    const banner = sharedEntryBanner();
    expect(banner).toContain("Put the original artisans back");
    expect(banner).toContain("move this interview into it when the refusal comes back");
  });

  test("the banner is still suppressed against the record being edited", () => {
    // It keys on the artisan SET, which an open edit normally still has, so without this it announces
    // the record to itself.
    expect(captureForm()).toContain("{existingEntry && existingEntry.id !== editingInterview?.id ? (");
  });
});

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * The house colour rules, on everything this change drew
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

test.describe("the palette", () => {
  test("only the three brand amber rungs are used", () => {
    /*
      `amber` deep-merges with stock Tailwind, so 100/500/800 are this theme's and every other rung is
      stock and does not pair with them. Asserted over the whole page rather than over the two new
      panels, because the next amber panel added here inherits the rule.

      ⚠ COLLECTED WITH A REGEX AND NOT CHECKED WITH `not.toContain`, because "amber-50" is a SUBSTRING
      of "amber-500" — the obvious spelling of this test fails on a perfectly correct file and reports
      a stock rung that is not there. `\d+` then a boundary is what actually asks the question.
    */
    const rungs = new Set((PAGE.match(/\bamber-\d+\b/g) ?? []).map((token) => token.slice("amber-".length)));
    expect([...rungs].sort(), "only amber-100/500/800 are brand rungs in this theme").toEqual(["100", "500", "800"]);
  });

  test("no second action colour was introduced", () => {
    // purple-700 is the only action colour in this product; nothing drawn here is a control anyway.
    expect(PAGE).not.toContain("bg-blue-");
    expect(PAGE).not.toContain("bg-green-");
    expect(PAGE).not.toContain("text-emerald-");
  });
});
