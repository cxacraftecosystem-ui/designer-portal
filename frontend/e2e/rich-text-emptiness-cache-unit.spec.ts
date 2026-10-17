import { expect, test } from "@playwright/test";

import { fromStored, isStoredRichTextEmpty, toStored } from "@/lib/richText";

/**
 * `isStoredRichTextEmpty` remembers its answer per document OBJECT, which is only right while a stored
 * document is never edited in place. These hold both halves of that: the editor's path emits a new
 * object every time, and outside production a cached document is frozen, so an in-place edit throws at
 * the line that does it instead of leaving a stale answer behind.
 */

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
  // A blank document edited the way the editor edits it — a NEW object — is answered afresh.
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
  // So the remembered answer is still the true one.
  expect(isStoredRichTextEmpty(doc)).toBe(true);
});
