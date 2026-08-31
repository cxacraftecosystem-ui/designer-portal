import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { appendDictatedPhrase } from "@/components/richtext/dictatedValue";
import {
  appendDictatedToStored,
  appendStoredParagraph,
  decodeStoredRichText,
  plainFromStoredRichText
} from "@/components/richtext/storedRichText";
import { fromStored } from "@/lib/richText";

/**
 * THE QUESTIONNAIRE'S RICH-TEXT ANSWERS — the two web sites that read `answerText` without flattening.
 *
 * `QuestionnaireResponse.answerText` is a `String?` that became a rich-text column on 2026-08-31,
 * when the answer box on `/questionnaire` became a `RichTextField`. `encodeStoredRichText` writes
 * prose for an unformatted answer and a JSON document the moment somebody applies a mark, so every
 * reader of that column has to tell the two apart. Four did not, and this file covers the two that
 * are web code — the third is `data_browser._interview_answers`, pinned in
 * `backend/tests/test_rich_text_stored_columns.py`, and the fourth is the handset's field renderer.
 *
 * THE FAILURE IS SILENT IN BOTH DIRECTIONS, which is why it needs pinning rather than reviewing.
 * `report_builder.format_value` records it at its own RICH_TEXT branch: the value *"fell through to
 * `clean_text`, which stringifies whatever it is given"*, so a document prints as literal braces
 * **and every emptiness check reads that JSON-shaped string as a filled field**, so nothing anywhere
 * reports a problem.
 *
 * ── THE TWO SITES ARE NOT THE SAME KIND OF DEFECT, AND SECTION 1 IS THE SERIOUS ONE ───────────────
 * `ArtisanQuestionnairePanel` (section 2) DISPLAYS the raw value: ugly, recoverable, the answer is
 * still in the column. The two append paths on `/questionnaire` (section 1) WRITE: they joined the
 * machine's words onto the end of a JSON string, producing a value that is neither valid JSON nor
 * readable prose — the editor cannot parse it back, so the researcher's own formatted answer is
 * replaced on screen by braces with the transcript stuck on the end, and it saves in that state.
 *
 * WHY A NODE SPEC. This repository has no React renderer in its devDependencies, so the append rules
 * are genuinely EXECUTED here and the wiring that reaches them is read out of the source — the same
 * split `record-form-dictation-unit.spec.ts` makes, for the same reason. What none of it proves is
 * that a browser paints the box.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const QUESTIONNAIRE_PAGE = read("app", "(protected)", "questionnaire", "page.tsx");
const ARTISAN_PANEL = read("components", "ArtisanQuestionnairePanel.tsx");

/** An answer a researcher could actually produce in that box: one sentence with a bolded term. */
const BOLDED = JSON.stringify({
  blocks: [
    {
      kind: "PARAGRAPH",
      spans: [
        { text: "The warp is dressed with " },
        { text: "handspun", marks: ["BOLD"] },
        { text: " cotton." }
      ]
    }
  ]
});
const BOLDED_PLAIN = "The warp is dressed with handspun cotton.";
const PHRASE = "It is sized with rice starch.";
const OPEN_BRACE_JSON = '{"blocks"';
const OPEN_BRACE_REPR = "{'blocks'";

const blocksOf = (stored: string) => fromStored(decodeStoredRichText(stored)).blocks;
const spansOf = (stored: string) => blocksOf(stored).flatMap((block) => block.spans);
const textOf = (stored: string, index: number) =>
  blocksOf(stored)[index].spans.map((span) => span.text).join("");

/* ────────────────────────────────────────────────────────────────────────────
 * 1. The WRITE path: a transcript arriving over a formatted answer
 * ──────────────────────────────────────────────────────────────────────────── */

test("a transcript over a formatted answer stays a document, and is never a concatenation", () => {
  const merged = appendDictatedToStored(BOLDED, PHRASE);

  // The defect, named: `appendDictatedPhrase` on the raw column value glues plain text onto JSON.
  expect(merged).not.toBe(appendDictatedPhrase(BOLDED, PHRASE));
  expect(merged).not.toContain(`}]} ${PHRASE}`);

  // And the result is a document the editor can read back, not a string that merely looks like one.
  expect(typeof decodeStoredRichText(merged)).toBe("object");
  expect(plainFromStoredRichText(merged)).toBe(`${BOLDED_PLAIN}\n${PHRASE}`);
});

test("what a reader sees is the words, with no envelope anywhere in them", () => {
  const shown = plainFromStoredRichText(appendDictatedToStored(BOLDED, PHRASE));

  // Both quotings: the stored column is JSON (double quotes) and a value that fell through to a
  // Python `str()` is a repr (single quotes). A test pinning one would pass while the other shipped.
  expect(shown).not.toContain(OPEN_BRACE_JSON);
  expect(shown).not.toContain(OPEN_BRACE_REPR);
  expect(shown).not.toContain("PARAGRAPH");
});

test("the machine's words land in their own paragraph, never inside what the researcher built", () => {
  // THE HANDSET'S RULE, ASSERTED HERE SO THE TWO CLIENTS CANNOT DRIFT. Both write this column, and
  // `questionnaireAnswerAppend` in `android/…/ui/questionnaires/` gives the argument: a space-join is
  // the closer analogue of the prose rule and is wrong once there is structure to land in, because
  // the last block of a formatted answer is often a bullet, a table row or a photograph's caption. A
  // new paragraph is the one placement that cannot land inside something the researcher built.
  const merged = appendDictatedToStored(BOLDED, PHRASE);

  expect(blocksOf(merged).map((block) => block.kind)).toEqual(["PARAGRAPH", "PARAGRAPH"]);
  expect(textOf(merged, 0)).toBe(BOLDED_PLAIN);
  expect(textOf(merged, 1)).toBe(PHRASE);
});

test("prose is appended byte for byte, and never parsed and rewritten", () => {
  // The identity rule `plain_from_stored` and `plainFromStoredRichText` both exist for: a round trip
  // through `fromPlainText`/`toPlain` strips each line, drops blank lines and collapses runs, so a
  // researcher's typed answer would come back reformatted by a function they asked to append a
  // sentence to. Every value in this column is prose today; this is the branch nearly all of them
  // take.
  const typed = "Weaves on a pit loom.\n\n  Trained by his father.  \n";
  expect(appendDictatedToStored(typed, PHRASE)).toBe(appendDictatedPhrase(typed, PHRASE));
  expect(appendDictatedToStored(typed, PHRASE)).toBe(typed + PHRASE);
});

test("the prose branch keeps the single space, and not the blank line appendStoredParagraph uses", () => {
  // WHY THIS IS NOT `appendStoredParagraph` OUTRIGHT. That function's document half is exactly what
  // the branch above wants and is called for it; its PROSE half joins with a blank line, which is
  // right for the separate EXIF note it was written for and wrong for a dictated take — the
  // recogniser is stopped and started across one answer, so the second take is the rest of the
  // sentence. Taking it wholesale would break one sentence into two on EVERY answer rather than only
  // formatted ones, which is the polarity `storedRichText.ts`'s header warns about.
  const typed = "The warp is dressed with cotton.";

  expect(appendDictatedToStored(typed, PHRASE)).toBe(`${typed} ${PHRASE}`);
  expect(appendDictatedToStored(typed, PHRASE)).not.toBe(appendStoredParagraph(typed, PHRASE));
});

test("an empty box, a null column and a brace that is only typing all stay prose", () => {
  expect(appendDictatedToStored("", PHRASE)).toBe(PHRASE);
  expect(appendDictatedToStored(null, PHRASE)).toBe(PHRASE);
  expect(appendDictatedToStored(undefined, PHRASE)).toBe(PHRASE);
  // Not a document — somebody's typing, and `decodeStoredRichText` says so. Reading it as JSON would
  // blank a real answer.
  expect(appendDictatedToStored("{he charges 400 per metre}", PHRASE)).toBe(
    `{he charges 400 per metre} ${PHRASE}`
  );
});

test("a multi-line transcript keeps its lines instead of running them together", () => {
  // A refined transcript's speaker labels ARE multi-line, and `fromStored` repairs a span containing
  // a newline by replacing it with a space — so passing the whole phrase as one span would run every
  // speaker's turn into one unreadable paragraph.
  const transcript = "Speaker 1: twelve looms.\nSpeaker 2: fourteen since April.";
  const merged = appendDictatedToStored(BOLDED, transcript);

  expect(blocksOf(merged)).toHaveLength(3);
  expect(plainFromStoredRichText(merged)).toBe(`${BOLDED_PLAIN}\n${transcript}`);
});

test("the researcher's mark survives, and the machine does not inherit it", () => {
  const spans = spansOf(appendDictatedToStored(BOLDED, PHRASE));

  expect(spans.map((span) => `${span.text}|${span.marks.join(",")}`)).toContain("handspun|BOLD");
  const appended = spans.find((span) => span.text.includes("rice starch"));
  expect(appended, "the phrase must be somewhere in the document").toBeTruthy();
  expect(appended?.marks, "the machine did not apply the researcher's bold").toEqual([]);
});

test("a dictated sentence is never filed under a photograph", () => {
  // An IMAGE block's spans are its CAPTION. The paragraph rule is what keeps the transcript out of
  // it — this is the case the handset's argument names first, so it is pinned rather than assumed.
  const withPhoto = JSON.stringify({
    blocks: [
      { kind: "PARAGRAPH", spans: [{ text: "Motif study." }] },
      { kind: "IMAGE", media: "med-1", spans: [{ text: "Block four." }] }
    ]
  });

  const merged = appendDictatedToStored(withPhoto, PHRASE);

  expect(blocksOf(merged).map((block) => block.kind)).toEqual(["PARAGRAPH", "IMAGE", "PARAGRAPH"]);
  expect(textOf(merged, 1)).toBe("Block four.");
  expect(textOf(merged, 2)).toBe(PHRASE);
});

test("both append paths on /questionnaire go through the stored-value append", () => {
  // The transcript that lands in an untouched box, and the button that accepts an offered transcript
  // over a written one. Read from source because neither can be mounted here.
  expect(QUESTIONNAIRE_PAGE).toContain("appendDictatedToStored(inBox, text)");
  expect(QUESTIONNAIRE_PAGE).toContain('appendDictatedToStored(answers[question.id] ?? "", text)');
  expect(QUESTIONNAIRE_PAGE).not.toMatch(/appendDictatedPhrase\(\s*inBox/);
  expect(QUESTIONNAIRE_PAGE).not.toMatch(/appendDictatedPhrase\(\s*answers\[/);
});

test("the two boxes that are NOT stored columns keep the plain joiner", () => {
  // Over-applying the fix would be its own defect. A SECTION clip's transcript accumulates in
  // `machineText`, which is React state rendered under the recorder and never written to a column,
  // and the question-builder's prompt box is a plain input. Neither is rich text, and routing them
  // through the stored-value append would parse a value that is not a stored value.
  expect(QUESTIONNAIRE_PAGE).toMatch(/appendDictatedPhrase\(current\[key\] \?\? ""/);
  expect(QUESTIONNAIRE_PAGE).toMatch(
    /setPrompt\(\(current\) => appendDictatedPhrase\(current, phrase\)\)/
  );
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The READ path: the artisan's own answers panel
 * ──────────────────────────────────────────────────────────────────────────── */

test("the artisan questionnaire panel prints the answer and not the envelope", () => {
  expect(ARTISAN_PANEL).toContain("plainFromStoredRichText(answer.answerText)");
  expect(ARTISAN_PANEL).not.toMatch(/>\{answer\.answerText\}</);
});

test("a formatted answer flattens to its prose on that panel", () => {
  expect(plainFromStoredRichText(BOLDED)).toBe(BOLDED_PLAIN);
  expect(plainFromStoredRichText(BOLDED)).not.toContain(OPEN_BRACE_JSON);
  expect(plainFromStoredRichText(BOLDED)).not.toContain(OPEN_BRACE_REPR);
  // And identity on everything already in the column, which is nearly all of it.
  const typed = "Twelve looms, two of them idle.";
  expect(plainFromStoredRichText(typed)).toBe(typed);
  expect(plainFromStoredRichText(null)).toBe("");
});
