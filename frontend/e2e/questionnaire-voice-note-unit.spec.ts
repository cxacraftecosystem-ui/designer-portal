/**
 * The transcript document every surface shows, copies and downloads, and the flattening of a stored
 * rich-text answer.
 *
 * ── WHAT MOVED ────────────────────────────────────────────────────────────────────────────────────
 * This file used to pin the global interview form at `/questionnaire` (its voice note's consent
 * route, clip ceilings, live-ref reads, eager staging) and the consolidated interview. Both were
 * retired on the web with the questionnaire unification (owner decision 2, 21 Oct 2026; UNIFY 4.4):
 * `/questionnaire` is the sessions list now and the consolidated page redirects to an artisan's
 * Questionnaire record. A take's consent gate, bounds and upload are the shared client's, and are
 * held in `e2e/questionnaire-dr-web.spec.ts` (browser) and `backend/tests/test_questionnaire_dr_wiring.py`.
 *
 * What stays here is what the live surfaces still share: `MarkdownDocument` renders, copies and
 * downloads a transcript for `TranscriptBlock`, and `plainFromStoredRichText` keeps a formatted
 * answer from reaching a reader as braces.
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { safeDocumentFileName } from "@/components/richtext/MarkdownDocument";
import { plainFromStoredRichText } from "@/components/richtext/storedRichText";
import { PORTAL } from "@/lib/namingPortal";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const TRANSCRIPT_BLOCK = read("components", "media", "TranscriptBlock.tsx");
const MARKDOWN_DOCUMENT = read("components", "richtext", "MarkdownDocument.tsx");

/* ────────────────────────────────────────────────────────────────────────────
 * 1. One component for render, copy and download
 * ──────────────────────────────────────────────────────────────────────────── */

test("the transcript surface mounts the shared document component", () => {
  expect(TRANSCRIPT_BLOCK, "TranscriptBlock mounts MarkdownDocument").toContain("<MarkdownDocument");
});

test("copy and download live in that component and are not re-derived per screen", () => {
  expect(MARKDOWN_DOCUMENT).toContain("navigator.clipboard.writeText");
  expect(MARKDOWN_DOCUMENT).toContain("anchor.download");
  expect(TRANSCRIPT_BLOCK, "TranscriptBlock does not roll its own clipboard call").not.toContain("clipboard.writeText");
  expect(TRANSCRIPT_BLOCK, "TranscriptBlock does not roll its own download anchor").not.toContain("anchor.download");
});

test("the markdown travels, not the rendered text", () => {
  // A refined transcript's speaker labels ARE the markdown. Flattening them produces a wall of prose
  // in which nobody can tell who spoke, which is what the refinement pass exists to establish.
  expect(MARKDOWN_DOCUMENT).toContain("navigator.clipboard.writeText(text)");
  expect(MARKDOWN_DOCUMENT).toContain("new Blob([text]");
  expect(MARKDOWN_DOCUMENT, "and it is saved as markdown").toContain("text/markdown");
});

test("the object URL is revoked on a later task, or Safari cancels the download", () => {
  expect(MARKDOWN_DOCUMENT).toMatch(/setTimeout\(\(\) => URL\.revokeObjectURL\(url\), 0\)/);
});


/* ────────────────────────────────────────────────────────────────────────────
 * 2. "Not edited" is a claim only one surface may make
 * ──────────────────────────────────────────────────────────────────────────── */

test("the read-only surface passes TRUE or nothing, never false", () => {
  // The stamp is null both for a transcript nobody edited and for every row stored before the
  // column existed on 2026-08-31, and those are different facts.
  expect(TRANSCRIPT_BLOCK).toContain("edited={current.transcriptEditedAt ? true : undefined}");
});

test("the flag is a word and never a colour alone", () => {
  expect(MARKDOWN_DOCUMENT).toContain("Edited");
  expect(MARKDOWN_DOCUMENT).toContain("Not edited");
  // Three states: an absent answer draws nothing at all.
  expect(MARKDOWN_DOCUMENT).toMatch(/if \(edited === undefined\) return null;/);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. A formatted answer must not reach a reader as braces
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("plainFromStoredRichText", () => {
  test("an ordinary typed answer is returned untouched", () => {
    // Nearly every answer is this. `encodeStoredRichText` writes prose for an unformatted document,
    // so the flattener has to be identity on it or the change would rewrite the whole corpus.
    expect(plainFromStoredRichText("She dyes the warp before the loom is dressed.")).toBe(
      "She dyes the warp before the loom is dressed."
    );
  });

  test("a formatted answer is flattened to its words", () => {
    const doc = JSON.stringify({ blocks: [{ kind: "paragraph", spans: [{ text: "Bagru", marks: ["bold"] }] }] });
    expect(plainFromStoredRichText(doc)).toBe("Bagru");
  });

  test("an answer that merely starts with a brace is somebody's typing, not a document", () => {
    expect(plainFromStoredRichText("{not a document}")).toBe("{not a document}");
  });

  test("an unanswered question is an empty string, so callers keep their own blank wording", () => {
    expect(plainFromStoredRichText(null)).toBe("");
    expect(plainFromStoredRichText(undefined)).toBe("");
  });
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. The download's file name — the one genuinely pure thing here
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("safeDocumentFileName", () => {
  // NAMING 2.1: every transcript download is <Portal>_<Subject>_Transcript.md, ASCII, <= 150.
  test("strips the characters Windows refuses outright", () => {
    // Every one of these is reachable from a question prompt, which is what these names are built
    // from. A file the browser cannot write is a download button that silently does nothing.
    expect(safeDocumentFileName('a/b\\c:d*e?f"g<h>i|j', "md")).toBe(`${PORTAL}_A-B-C-D-E-F-G-H-I-J_Transcript.md`);
  });

  test("collapses whitespace rather than leaving it in a file name", () => {
    expect(safeDocumentFileName("Section D  RAW   MATERIALS", "md")).toBe(`${PORTAL}_Section-D-RAW-Materials_Transcript.md`);
  });

  test("never leads or trails with a separator", () => {
    expect(safeDocumentFileName("  ?leading and trailing?  ", "md")).toBe(`${PORTAL}_Leading-And-Trailing_Transcript.md`);
  });

  test("caps the length and stays ASCII, because several filesystems cap a segment at 255 BYTES", () => {
    const long = safeDocumentFileName("क".repeat(500), "md");
    expect(long.length).toBeLessThanOrEqual(150);
    expect(/^[\x21-\x7e]+$/.test(long)).toBe(true);
  });

  test("a clip already named by the convention keeps its name, with What Transcript", () => {
    expect(safeDocumentFileName(`${PORTAL}_Interview_2026-07-01_1824_Giriraj_Recording_D-Q03.m4a-transcript`, "md")).toBe(
      `${PORTAL}_Interview_2026-07-01_1824_Giriraj_Transcript_D-Q03.md`
    );
  });

  test("a name that reduces to nothing still produces a usable file", () => {
    // "???" is a real prompt shape, and an empty name would make the anchor download the page.
    expect(safeDocumentFileName("???", "md")).toBe(`${PORTAL}_Transcript.md`);
    expect(safeDocumentFileName("", "md")).toBe(`${PORTAL}_Transcript.md`);
  });
});
