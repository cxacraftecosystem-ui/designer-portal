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

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const ARTISAN_PANEL = read("components", "ArtisanQuestionnairePanel.tsx");

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

test("a transcript over a formatted answer stays a document, and is never a concatenation", () => {
  const merged = appendDictatedToStored(BOLDED, PHRASE);

  expect(merged).not.toBe(appendDictatedPhrase(BOLDED, PHRASE));
  expect(merged).not.toContain(`}]} ${PHRASE}`);

  expect(typeof decodeStoredRichText(merged)).toBe("object");
  expect(plainFromStoredRichText(merged)).toBe(`${BOLDED_PLAIN}\n${PHRASE}`);
});

test("what a reader sees is the words, with no envelope anywhere in them", () => {
  const shown = plainFromStoredRichText(appendDictatedToStored(BOLDED, PHRASE));

  expect(shown).not.toContain(OPEN_BRACE_JSON);
  expect(shown).not.toContain(OPEN_BRACE_REPR);
  expect(shown).not.toContain("PARAGRAPH");
});

test("the machine's words land in their own paragraph, never inside what the researcher built", () => {
  const merged = appendDictatedToStored(BOLDED, PHRASE);

  expect(blocksOf(merged).map((block) => block.kind)).toEqual(["PARAGRAPH", "PARAGRAPH"]);
  expect(textOf(merged, 0)).toBe(BOLDED_PLAIN);
  expect(textOf(merged, 1)).toBe(PHRASE);
});

test("prose is appended byte for byte, and never parsed and rewritten", () => {
  const typed = "Weaves on a pit loom.\n\n  Trained by his father.  \n";
  expect(appendDictatedToStored(typed, PHRASE)).toBe(appendDictatedPhrase(typed, PHRASE));
  expect(appendDictatedToStored(typed, PHRASE)).toBe(typed + PHRASE);
});

test("the prose branch keeps the single space, and not the blank line appendStoredParagraph uses", () => {
  const typed = "The warp is dressed with cotton.";

  expect(appendDictatedToStored(typed, PHRASE)).toBe(`${typed} ${PHRASE}`);
  expect(appendDictatedToStored(typed, PHRASE)).not.toBe(appendStoredParagraph(typed, PHRASE));
});

test("an empty box, a null column and a brace that is only typing all stay prose", () => {
  expect(appendDictatedToStored("", PHRASE)).toBe(PHRASE);
  expect(appendDictatedToStored(null, PHRASE)).toBe(PHRASE);
  expect(appendDictatedToStored(undefined, PHRASE)).toBe(PHRASE);
  expect(appendDictatedToStored("{he charges 400 per metre}", PHRASE)).toBe(
    `{he charges 400 per metre} ${PHRASE}`
  );
});

test("a multi-line transcript keeps its lines instead of running them together", () => {
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

test("the artisan questionnaire panel prints the answer and not the envelope", () => {
  expect(ARTISAN_PANEL).toContain("plainFromStoredRichText(answer.answerText)");
  expect(ARTISAN_PANEL).not.toMatch(/>\{answer\.answerText\}</);
});

test("a formatted answer flattens to its prose on that panel", () => {
  expect(plainFromStoredRichText(BOLDED)).toBe(BOLDED_PLAIN);
  expect(plainFromStoredRichText(BOLDED)).not.toContain(OPEN_BRACE_JSON);
  expect(plainFromStoredRichText(BOLDED)).not.toContain(OPEN_BRACE_REPR);
  const typed = "Twelve looms, two of them idle.";
  expect(plainFromStoredRichText(typed)).toBe(typed);
  expect(plainFromStoredRichText(null)).toBe("");
});
