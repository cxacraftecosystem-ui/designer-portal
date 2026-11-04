import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

const codeOnly = (relative: string) =>
  read(relative)
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/(^|[^:])\/\/.*$/gm, "$1");

const TRAY = "components/media/UploadTray.tsx";
const CARD = "components/media/MediaLightbox.tsx";
const GRID = "components/media/MediaCardGrid.tsx";
const EXISTING = "components/media/ExistingMedia.tsx";

test("the section breakdown is a bounded scroller, not an ever-growing stack", () => {
  const tray = read(TRAY);
  expect(tray).toContain(
    '<ul role="list" className="grid max-h-32 gap-2 overflow-y-auto overscroll-contain pr-1 sm:grid-cols-2 xl:grid-cols-3">'
  );
  expect(codeOnly(TRAY)).not.toContain('<ul className="grid gap-2">');
});

test("the dock card carries its own ceiling, so its top edge cannot leave the screen", () => {
  expect(read(TRAY)).toContain("grid max-h-[70vh] max-w-7xl gap-3 overflow-y-auto overscroll-contain rounded-lg");
});

test("the fixed wrapper keeps the rung and the geometry the z-ladder was measured against", () => {
  expect(read(TRAY)).toContain('className="pointer-events-none fixed inset-x-0 bottom-0 z-40 px-4 pt-3"');
});

test("the flow spacer stays — it is the only thing protecting the bottom of the document", () => {
  expect(read(TRAY)).toContain("<div aria-hidden style={{ height: dockHeight }} />");
});

test("the dock's header row cannot size the whole dock to an untruncated filename", () => {
  expect(read(TRAY)).toContain('<div className="flex min-w-0 items-center gap-3">');
  expect(codeOnly(TRAY)).not.toContain('<div className="flex items-center gap-3">');
});

test("the breakdown opens itself once, and a designer's collapse is never overruled", () => {
  const tray = read(TRAY);
  expect(tray).toContain("const dockDecided = useRef(false);");
  expect(tray).toContain("if (!uploading || dockDecided.current) return;");
  expect(tray).toContain("dockDecided.current = true;\n                setExpanded((current) => !current);");
  expect(codeOnly(TRAY)).not.toContain("if (uploading) setExpanded(true);");
});

test("the card is a wrapping flex row, so its shape follows the container and not the viewport", () => {
  const card = read(CARD);
  expect(card).toContain(
    'className="flex min-w-0 cursor-pointer flex-wrap items-start gap-2 rounded-md border border-line-200 bg-field-50 p-2 transition hover:border-purple-300"'
  );
  expect(card).toContain("flex-[1_1_6rem]");
  expect(card).toContain("flex-[999_1_8rem]");
});

test("min-w-0 runs the whole way down the card, root included", () => {
  const card = read(CARD);
  const tile = card.slice(card.indexOf("export function MediaPreviewTile("), card.indexOf("async function saveToDevice"));
  expect(tile).toContain('className="flex min-w-0 cursor-pointer');
  expect(tile).toContain("aspect-4/3 min-w-0 flex-[1_1_6rem]");
  expect(tile).toContain('<div className="grid min-w-0 flex-[999_1_8rem] gap-2">');
  expect(tile).toContain('<div className="flex min-w-0 items-start gap-2">');
  expect(tile).toContain('<div className="grid min-w-0 gap-1.5">');
  expect(tile).toContain('<span className="flex flex-wrap items-center gap-2">');
});

test("the filename breaks at a character, because break-words does not change min-content", () => {
  const card = read(CARD);
  expect(card).toContain('<div className="line-clamp-2 break-all text-sm font-medium text-ink" title={item.name}>');
  expect(codeOnly(CARD)).not.toContain("break-words");
});

test("the remove control is in the flow, not painted on top of the photograph it deletes", () => {
  const tile = codeOnly(CARD);
  expect(tile).not.toContain("absolute right-1.5 top-1.5");
  expect(read(CARD)).toContain("aria-label={`${removeLabel} ${item.name}`}");
});

test("the card is not overflow-hidden, so the focus ring of the controls inside it survives", () => {
  const card = read(CARD);
  expect(card).not.toContain('className="flex min-w-0 cursor-pointer flex-wrap items-start gap-2 overflow-hidden');
});

test("the grid announces itself as a list, with a real item per card", () => {
  const grid = read(GRID);
  expect(grid).toContain('<ul role="list" aria-label={label} className={COLUMN_CLASS[maxColumns]}>');
  expect(grid).toContain('<li className="grid min-w-0">{child}</li>');
  expect(grid).toContain('child === null || child === undefined || typeof child === "boolean" ? null : (');
});

test("the column counts are whole literal class strings at the stock breakpoints", () => {
  const grid = read(GRID);
  expect(grid).toContain('2: "grid gap-3 lg:grid-cols-2"');
  expect(grid).toContain('3: "grid gap-3 sm:grid-cols-2 lg:grid-cols-3"');
  expect(grid).toContain('4: "grid gap-3 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-4"');
});

test("the attachments panel flows into columns instead of one long stack", () => {
  const existing = read(EXISTING);
  expect(existing).toContain('import { MediaCardGrid } from "@/components/media/MediaCardGrid";');
  expect(existing).toContain("<MediaCardGrid label={title} maxColumns={2}>");
  expect(codeOnly(EXISTING)).not.toContain("sm:grid-cols-[200px_1fr]");
});
