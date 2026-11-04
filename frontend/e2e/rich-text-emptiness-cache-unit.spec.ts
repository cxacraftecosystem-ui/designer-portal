import { expect, test } from "@playwright/test";

import { fromStored, isStoredRichTextEmpty, toStored } from "@/lib/richText";

const filled = () => ({ blocks: [{ kind: "PARAGRAPH", spans: [{ text: "Indigo dyed in the courtyard" }] }] });
const blank = () => ({ blocks: [{ kind: "PARAGRAPH", spans: [] }] });

test("the editor's write path emits a new document object, never the one it was given", () => {
  const stored = filled();
  const next = toStored(fromStored(stored));
  expect(next).not.toBe(stored);
  expect(next.blocks).not.toBe(stored.blocks);
  expect(next.blocks[0]).not.toBe(stored.blocks[0]);
});

test("the answer is right for a new object every time, filled or blank", () => {
  expect(isStoredRichTextEmpty(blank())).toBe(true);
  expect(isStoredRichTextEmpty(filled())).toBe(false);
  const first = blank();
  expect(isStoredRichTextEmpty(first)).toBe(true);
  const edited = toStored(fromStored(filled()));
  expect(isStoredRichTextEmpty(edited)).toBe(false);
});

test("outside production a cached document cannot be edited in place", () => {
  const doc = blank();
  expect(isStoredRichTextEmpty(doc)).toBe(true);
  expect(Object.isFrozen(doc)).toBe(true);
  expect(Object.isFrozen(doc.blocks[0].spans)).toBe(true);
  expect(() => {
    (doc.blocks[0].spans as { text: string }[]).push({ text: "typed in place" });
  }).toThrow(TypeError);
  expect(isStoredRichTextEmpty(doc)).toBe(true);
});
