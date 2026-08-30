import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * "MULTIPLE CARDS OVERLAP OVER EACH OTHER" — THE CAUSE, AND THE THREE THINGS THAT NOW HOLD IT SHUT.
 *
 * The report was about media cards and the media cards were not the cause. Measured at 320/360/390/
 * 640/768/1024/1280/1536px, `MediaPreviewTile` and the grids that hold it never intersected a
 * sibling. What did was `UploadTray`: a `position: fixed` dock with no height ceiling, growing one
 * row per media section (a design-workshop stage mounts one per media field, and stage 13 has
 * eleven) until it lay across the page. Measured before the fix: 531px at eleven sections, 83 % of a
 * 360×640 phone; 701px at sixteen, with the card's top edge at y = −61 — off-screen, and therefore
 * a summary nobody could close, over a page nobody could read.
 *
 * Three separate facts keep that shut, and each is asserted below because each was absent before:
 *   1. the sections list is a BOUNDED SCROLLER laid out in columns, so the row count stops driving
 *      the height at all;
 *   2. the card has an overall ceiling, so no combination of sections, completions and window size
 *      can put its top edge off the screen again;
 *   3. the breakdown opens itself ONCE. It used to re-open on every idle→uploading transition, so a
 *      designer who collapsed it had it forced back open by the next file they attached — which is
 *      why the tall state was the default rather than anything anybody chose.
 *
 * The second half of the same request — "all media appears in the card format horizontally stacked
 * over one another, for bigger screens … multiple horizontal stacks next to each other, so as to
 * ensure that the depth does not grow too long" — is `MediaPreviewTile` becoming a landscape card
 * and `MediaCardGrid` flowing a list of them into columns. The assertions on those are about the
 * one way a landscape card CAN reproduce the reported bug: a card that demands more width than its
 * grid track and overhangs the card beside it. Measured, with the `min-w-0` chain broken at the
 * root, an underscored camera filename sized a card at 425px inside a 311px track and each card
 * overhung the next by 118 × 90px; with it broken at the rows inside the text column, the document's
 * scrollWidth was 493px against a 320px viewport. Both are pinned here.
 *
 * WHY THESE ARE SOURCE READS. There is no React renderer in this repository's devDependencies —
 * `discarded-work-unit.spec.ts` and `existing-media-count-unit.spec.ts` say so plainly and read
 * their subjects the same way — so a component cannot be mounted here. What this cannot prove is
 * that the browser paints it; that was measured separately, against the repo's own compiled
 * Tailwind in Chromium, and the numbers quoted above are from that run. What this DOES prove is
 * that the declarations those numbers came from are still in the file.
 */

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

/**
 * The same source with its comments taken out — lifted from `overlay-layering-unit.spec.ts`, which
 * explains the need: the house style is long prose comments naming the defect they closed, so a
 * file that says "this was `absolute right-1.5 top-1.5`" contains the very string a test asserting
 * its absence is hunting for. Every negative assertion below runs through this.
 */
const codeOnly = (relative: string) =>
  read(relative)
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/(^|[^:])\/\/.*$/gm, "$1");

const TRAY = "components/media/UploadTray.tsx";
const CARD = "components/media/MediaLightbox.tsx";
const GRID = "components/media/MediaCardGrid.tsx";
const EXISTING = "components/media/ExistingMedia.tsx";

/* ────────────────────────────────────────────────────────────────────────────
 * 1. The dock cannot grow without limit
 * ──────────────────────────────────────────────────────────────────────────── */

test("the section breakdown is a bounded scroller, not an ever-growing stack", () => {
  const tray = read(TRAY);
  // `max-h-32` is the same ceiling the completed list one block down has always had — the two were
  // written a block apart and only one of them got it. Losing it is the whole bug.
  expect(tray).toContain(
    '<ul role="list" className="grid max-h-32 gap-2 overflow-y-auto overscroll-contain pr-1 sm:grid-cols-2 xl:grid-cols-3">'
  );
  // The bare list, which is what shipped and what grew.
  expect(codeOnly(TRAY)).not.toContain('<ul className="grid gap-2">');
});

test("the dock card carries its own ceiling, so its top edge cannot leave the screen", () => {
  expect(read(TRAY)).toContain("grid max-h-[70vh] max-w-7xl gap-3 overflow-y-auto overscroll-contain rounded-lg");
});

test("the fixed wrapper keeps the rung and the geometry the z-ladder was measured against", () => {
  // Paired with overlay-layering-unit.spec.ts, which asserts this same string against the nav
  // sheet's scrim at z-[90]. The fix for the height is inside the card; the wrapper does not move.
  expect(read(TRAY)).toContain('className="pointer-events-none fixed inset-x-0 bottom-0 z-40 px-4 pt-3"');
});

test("the flow spacer stays — it is the only thing protecting the bottom of the document", () => {
  expect(read(TRAY)).toContain("<div aria-hidden style={{ height: dockHeight }} />");
});

test("the dock's header row cannot size the whole dock to an untruncated filename", () => {
  /*
    THE SAME `min-w-0` RULE `MediaLightbox` ARGUES AT LENGTH, IN THE COMPONENT THAT CAUSED THE BUG.

    The header row is a GRID ITEM of the dock, so its `min-width` is `auto` — the content-based
    minimum — and it is also a flex container holding two `truncate` paragraphs. `truncate` is
    `white-space: nowrap`: it clips what is PAINTED and leaves the box's minimum contribution at the
    whole unbroken sentence. The second line is `${currentFileName} · ${sent} of ${total} · ${eta}`,
    and a real object name out of `buildObjectName` is long, so that one paragraph set the dock's
    single grid track. Every child of the dock stretches to that track.

    Measured in Chromium against this repository's compiled Tailwind, 16 sections and a real
    filename: the track was 731px against a 288px card at 320px wide (443px of section rows,
    progress bar and uploaded chips laid out past the edge of the card), 731 against 328 at 360px,
    731 against 608 at 640px — and 0px past the card at every one of those widths with `min-w-0`
    present. The page never scrolled in either state, because `position: fixed` keeps the dock out
    of the document's scrollable area, which is why only a measurement of the dock itself finds it.
  */
  expect(read(TRAY)).toContain('<div className="flex min-w-0 items-center gap-3">');
  // The row as it shipped, which sized the track to the sentence.
  expect(codeOnly(TRAY)).not.toContain('<div className="flex items-center gap-3">');
});

test("the breakdown opens itself once, and a designer's collapse is never overruled", () => {
  const tray = read(TRAY);
  expect(tray).toContain("const dockDecided = useRef(false);");
  expect(tray).toContain("if (!uploading || dockDecided.current) return;");
  // Touching the chevron retires the automation, whichever way it was toggled.
  expect(tray).toContain("dockDecided.current = true;\n                setExpanded((current) => !current);");
  // The effect that re-fired on every idle→uploading transition.
  expect(codeOnly(TRAY)).not.toContain("if (uploading) setExpanded(true);");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The landscape card, and the width it must never demand
 * ──────────────────────────────────────────────────────────────────────────── */

test("the card is a wrapping flex row, so its shape follows the container and not the viewport", () => {
  const card = read(CARD);
  expect(card).toContain(
    'className="flex min-w-0 cursor-pointer flex-wrap items-start gap-2 rounded-md border border-line-200 bg-field-50 p-2 transition hover:border-purple-300"'
  );
  // The 999-against-1 pair IS the layout: the thumbnail holds its 6rem base while they share a
  // line, and takes the whole line when the text wraps below it. Change either and the 116px table
  // cells on /products and /tools stop looking like the tiles they are supposed to still be.
  expect(card).toContain("flex-[1_1_6rem]");
  expect(card).toContain("flex-[999_1_8rem]");
});

test("min-w-0 runs the whole way down the card, root included", () => {
  const card = read(CARD);
  const tile = card.slice(card.indexOf("export function MediaPreviewTile("), card.indexOf("async function saveToDevice"));
  // Root, thumbnail, text column, and both rows inside the text column. Any one of these missing
  // re-exports an intrinsic width to the grid track above and the card overhangs its neighbour.
  expect(tile).toContain('className="flex min-w-0 cursor-pointer');
  expect(tile).toContain("aspect-[4/3] min-w-0 flex-[1_1_6rem]");
  expect(tile).toContain('<div className="grid min-w-0 flex-[999_1_8rem] gap-2">');
  expect(tile).toContain('<div className="flex min-w-0 items-start gap-2">');
  expect(tile).toContain('<div className="flex min-w-0 items-center justify-between gap-2">');
});

test("the filename breaks at a character, because break-words does not change min-content", () => {
  const card = read(CARD);
  expect(card).toContain('<div className="line-clamp-2 break-all text-sm font-medium text-ink" title={item.name}>');
  // `overflow-wrap: break-word` stops the overflow being painted and leaves the box's minimum
  // contribution at the whole unbreakable word — which for an underscored camera filename is the
  // whole filename, and is what put a horizontal scrollbar on a 320px viewport.
  expect(codeOnly(CARD)).not.toContain("break-words");
});

test("the remove control is in the flow, not painted on top of the photograph it deletes", () => {
  const tile = codeOnly(CARD);
  expect(tile).not.toContain("absolute right-1.5 top-1.5");
  // It keeps its accessible name, which is the half of it that is not about layout at all.
  expect(read(CARD)).toContain("aria-label={`${removeLabel} ${item.name}`}");
});

test("the card is not overflow-hidden, so the focus ring of the controls inside it survives", () => {
  const card = read(CARD);
  // The global ring is an `outline` at `outline-offset: 2px`, drawn OUTSIDE the border box; the
  // remove and Retry buttons sit flush inside the card's `p-2`. Same rule as the guide's step card.
  expect(card).not.toContain('className="flex min-w-0 cursor-pointer flex-wrap items-start gap-2 overflow-hidden');
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. The set of cards is a list, and it flows into columns
 * ──────────────────────────────────────────────────────────────────────────── */

test("the grid announces itself as a list, with a real item per card", () => {
  const grid = read(GRID);
  // Tailwind's preflight sets `list-style: none`, and Safari/VoiceOver drops list semantics from a
  // list styled that way — so the explicit role is load-bearing on the browser most field iPads run.
  expect(grid).toContain('<ul role="list" aria-label={label} className={COLUMN_CLASS[maxColumns]}>');
  expect(grid).toContain('<li className="grid min-w-0">{child}</li>');
  // Holes reach the callback as null; an unguarded map announces one as an extra empty item.
  expect(grid).toContain('child === null || child === undefined || typeof child === "boolean" ? null : (');
});

test("the column counts are whole literal class strings at the stock breakpoints", () => {
  const grid = read(GRID);
  // Whole strings, or the JIT never emits them. `cn()` here is a plain join and not tailwind-merge,
  // which is why the choice is a prop rather than an overridable className.
  expect(grid).toContain('2: "grid gap-3 lg:grid-cols-2"');
  expect(grid).toContain('3: "grid gap-3 sm:grid-cols-2 lg:grid-cols-3"');
  expect(grid).toContain('4: "grid gap-3 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-4"');
});

test("the attachments panel flows into columns instead of one long stack", () => {
  const existing = read(EXISTING);
  expect(existing).toContain('import { MediaCardGrid } from "@/components/media/MediaCardGrid";');
  expect(existing).toContain("<MediaCardGrid label={title} maxColumns={2}>");
  // The fixed rail is what made one column the only option: it left a half-width card ~90px for the
  // provenance and the transcript.
  expect(codeOnly(EXISTING)).not.toContain("sm:grid-cols-[200px_1fr]");
});
