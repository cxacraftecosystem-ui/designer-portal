import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { scopeNoticeLines } from "@/components/designworkshop/StageReferenceField";
import type { DwField, DwReferenceOption, DwReferencePayload } from "@/lib/designWorkshops";

/**
 * TENTATIVE-FIRST WHERE A SKETCH IS *CHOSEN* — the picker's half, pinned.
 *
 * ── WHAT WAS WRONG ──────────────────────────────────────────────────────────────────────────────
 *
 * Stage 11's sketches sort tentative-first wherever they are LISTED (`lib/sketchTentative.ts`, the
 * upload chooser, and the handset's twin). They did not where one is CHOSEN. The three
 * `ref_model="DwSketch"` pickers — `sketch.supersedesSketch`, `sketchReview.sketchRef`,
 * `prototype.sketchRef` — are server-resolved through
 * `GET /design-workshops/{id}/references?model=DwSketch`, and the reference payload carried no flag
 * at all, so no client could have drawn the word or applied the order.
 *
 * ── WHY THE FIX IS ON THE SERVER, WHICH IS WHAT THESE ASSERTIONS ARE REALLY ABOUT ───────────────
 *
 * `options` is ONE CAPPED PAGE. Sorting it in the browser would have sorted the page and left a
 * tentative sketch stranded behind the cap — "a client-side filter over a server-truncated list"
 * (§11.5 of the frontend contract), which is rule 10 wearing a sort key. So the partition runs
 * beside the truncation in `_in_record_options`, and everything this file covers is the CLIENT's
 * remaining duty: never claim an ordering the server did not apply, never claim it for a page that
 * shows no instance of it, and say what the cap does to a list that was reordered before it was cut.
 *
 * ── WHAT IS EXECUTED AND WHAT IS ONLY READ ──────────────────────────────────────────────────────
 *
 * `scopeNoticeLines` is a real function called with real payloads — it was lifted out of the
 * component for exactly this reason, and its own header says why. The chip is a Composable-shaped
 * problem with no React renderer in this repository's devDependencies, so it is pinned by reading
 * the source: that the word comes from the payload and not from a literal, and that the picker
 * contains no sort of its own. A source assertion pins a spelling and not an effect; both below are
 * written to fail against the tree before this change.
 */

const ROOT = join(__dirname, "..");
const source = readFileSync(join(ROOT, "components/designworkshop/StageReferenceField.tsx"), "utf8").replace(/\r\n/g, "\n");

/**
 * Where a top-level function body ends in this file, for the slice below.
 *
 * Spelled as a constant because the literal is a newline, a brace and a newline, and writing
 * that inline is how this spec first shipped with an unterminated string. The source above is
 * read with its line endings normalised, so one spelling serves both checkouts.
 */
const END_OF_FUNCTION = ["", "}", ""].join("\n");

/** The `prototype.sketchRef` field, which is the picker all three of these live in the shape of. */
const SKETCH_REF = {
  key: "sketchRef",
  label: "From sketch",
  type: "REF",
  refModel: "DwSketch",
  refScope: "WORKSHOP"
} as unknown as DwField;

function option(id: string, tentative?: boolean): DwReferenceOption {
  const base: DwReferenceOption = { id, label: id, sublabel: "", data: {} };
  return tentative === undefined ? base : { ...base, tentative };
}

function payload(overrides: Partial<DwReferencePayload> = {}): DwReferencePayload {
  return {
    model: "DwSketch",
    scope: "WORKSHOP",
    scopedToWorkshop: true,
    filtered: false,
    truncated: false,
    tentativeFirst: true,
    tentativeLabel: "Tentative",
    options: [option("a", true), option("b", false)],
    ...overrides
  };
}

test("a reordered list says why, in the registry's own word", () => {
  // The whole point of the sentence: a list whose order a reader cannot account for reads as
  // arbitrary, which is worse than the stage order it replaced.
  expect(scopeNoticeLines(SKETCH_REF, payload())).toContain(
    "“Tentative” rows come first; the rest keep their stage order."
  );
});

test("the word is whatever the registry calls it, never a literal in the component", () => {
  // `stage_definitions.py` owns the label and four surfaces draw it. If it is ever reworded, this
  // sentence must reword with it rather than being the one screen that still says the old word.
  const lines = scopeNoticeLines(SKETCH_REF, payload({ tentativeLabel: "Not settled" }));
  expect(lines[0]).toBe("“Not settled” rows come first; the rest keep their stage order.");
});

test("a picker showing nothing ticked says nothing about the order", () => {
  // The partition changed nothing here, so the sentence would be noise about a rule with no
  // instance. This gate is exact ONLY because the server orders above the cap — see the next test.
  expect(scopeNoticeLines(SKETCH_REF, payload({ options: [option("a", false), option("b", false)] }))).toEqual([]);
});

test("a server that predates the feature is not spoken for", () => {
  // Both keys absent is what an older deployment sends. Claiming an ordering nobody applied is
  // worse than saying nothing, and it is a claim no client can check.
  const older = payload({ tentativeFirst: undefined, tentativeLabel: undefined, options: [option("a"), option("b")] });
  expect(scopeNoticeLines(SKETCH_REF, older)).toEqual([]);
});

test("a model with no such flag draws no word even when the server answered", () => {
  // `prototype` and `sketchReview` declare no `isTentative`, so the server sends `false` and `""`.
  const plain = payload({ model: "DwPrototype", tentativeFirst: false, tentativeLabel: "", options: [option("a")] });
  expect(scopeNoticeLines(SKETCH_REF, plain)).toEqual([]);
});

test("the cap sentence says what the cap now falls on first", () => {
  /*
    THE HALF THAT ONLY MAKES SENSE BECAUSE OF WHERE THE ORDERING RUNS. The partition is applied
    ABOVE the slice, so what drops off the end is the settled rows — a designer who cannot find
    their tentative sketch here is looking at a list where none matched, not at one where the cap
    ate it. Said as "falls on the rest first" and deliberately NOT as "tentative rows are never
    cut", which is false once there are more tentative rows than the page holds.
  */
  const lines = scopeNoticeLines(SKETCH_REF, payload({ truncated: true }));
  expect(lines).toContain(
    "Only the first 50 matches are listed and the cap falls on the rest first — type more of the name to narrow them."
  );
  // And the box it points at is real: this picker's search is the server's, over every row, not a
  // filter over the page (§11.5). The clause naming it survives on both branches.
  expect(lines.some((line) => line.includes("type more of the name to narrow them"))).toBe(true);
});

test("an unordered truncated list keeps exactly the sentence it always had", () => {
  const lines = scopeNoticeLines(
    SKETCH_REF,
    payload({ truncated: true, tentativeFirst: false, tentativeLabel: "", options: [option("a")] })
  );
  expect(lines).toEqual(["Only the first 50 matches are listed — type more of the name to narrow them."]);
});

test("the two sentences are ordered why-then-how-short, and both are said", () => {
  // Neither replaces the other: one explains the order, one explains the length. A picker that
  // dropped the cap line on a reordered list would be short for a reason it no longer states.
  const lines = scopeNoticeLines(SKETCH_REF, payload({ truncated: true }));
  expect(lines).toHaveLength(2);
  expect(lines[0]).toContain("rows come first");
  expect(lines[1]).toContain("Only the first 50 matches");
});

test("the picker draws the chip from the payload and never from a hardcoded word", () => {
  /*
    READ RATHER THAN EXECUTED — there is no React renderer in this repository's devDependencies.
    The failure this guards is the one §16 names: a word typed into a component is the copy that
    goes stale when the registry is edited, and this chip has to match the stage form's row chip
    exactly.

    AIMED AT A PLACE, NOT AT A COUNT. "The literal is absent from the file" would be false and
    should be — the argument for the feature is written in comments and quotes the word. So the
    chip's own body is sliced out and the literal is required to be absent from THAT, which is the
    thing that could go stale.
  */
  const body = source.slice(source.indexOf("function TentativeChip("));
  const chip = body.slice(0, body.indexOf(END_OF_FUNCTION) + END_OF_FUNCTION.length);
  expect(chip).toContain("option.tentative !== true");
  // The MARKUP, not the whole function — the component's own NAME contains the word and must.
  const markup = chip.slice(chip.indexOf("return ("));
  expect(markup).toContain("{word}");
  expect(markup).not.toMatch(/Tentative/);
  // And the word reaches it from the payload, through the one reader.
  expect(source).toContain("payload.tentativeLabel");
  expect(source.split("tentativeWordFor(payload)").length - 1).toBe(2);
});

test("the picker sorts nothing of its own", () => {
  /*
    THE ASSERTION THAT WOULD CATCH A LATER AGENT "FINISHING THE JOB" IN THE BROWSER. Reordering
    `options` here is the obvious-looking change and it is the defect: the list is one capped page,
    so the reorder would apply to the page and the tentative sketch behind the cap would stay
    behind it, invisibly. Coarse, and aimed at a place rather than a count — the file has no sort
    at all today.
  */
  expect(source).not.toContain(".sort(");
  expect(source).not.toContain("tentativeFirst(");
});

test("both pickers in the file draw the chip, not just the single-select one", () => {
  // Two renderers over one list that disagreed about which rows are marked is invisible until
  // somebody compares two screens — the reason `TentativeChip` is one component and not two spans.
  expect(source.split("<TentativeChip ").length - 1).toBe(2);
});
