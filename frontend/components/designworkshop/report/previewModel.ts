/**
 * The shapes `GET /design-workshops/{id}/report/preview` puts on the wire that the shared client
 * does not yet spell, plus the page geometry the preview draws itself against.
 *
 * WHY THESE TYPES ARE HERE AND NOT IN `lib/designWorkshops.ts`. That file owns `DwBlock`, and
 * `DwBlock` is the union of the block types the report builder has been emitting for as long as
 * the feature has existed. The three below are newer than it: `MapBlock` and `ChartBlock` were
 * added to `backend/app/services/report_model.py` in this same change, and the resolved-span form
 * of a rich-text field is produced by `rich_text.to_preview_json`, which exists for this screen
 * and for nothing else. Declaring them beside the one page that renders them keeps a shared,
 * heavily-imported module out of a race with work that is still landing, and costs nothing at the
 * call site: {@link PreviewBlock} is a widening of `DwBlock`, so `DwBlock[]` assigns to
 * `PreviewBlock[]` with no cast and the compiler still demands a branch per member.
 *
 * SNAKE_CASE, LIKE THE REST OF THE PREVIEW PAYLOAD. `_block_payload` in the route is
 * `dataclasses.asdict()` with the class name as `type`, and it renames nothing — so it is
 * `width_pct`, `rotation_deg`, `png_base64`. Every other endpoint in this API is camelCase
 * because it is built from pydantic models; this one is not, and a key "tidied" on the way in
 * here would compile perfectly and render an empty figure.
 */

import type { DwBlock, DwRun } from "@/lib/designWorkshops";
import { DEFAULT_ACCENT, paletteFromAccent, type ReportPalette } from "@/lib/reportTheme";

/* ────────────────────────────────────────────────────────────────────────────
 * Runs, now that a run can carry the RICH_TEXT marks
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A run as it now arrives, with the two marks `report_model.Run` grew for RICH_TEXT.
 *
 * `underline` and `strike` were added to the dataclass AFTER `italic` and BEFORE `script`,
 * deliberately, so that every positional `Run(text, bold, italic)` in the builder kept meaning
 * what it meant. They are optional here for the same reason every other field in this payload is
 * read defensively: a server one deploy behind simply does not send them, and reading a missing
 * key as `false` is the right answer, whereas refusing to draw the run is not.
 *
 * There is no `code` here because there is no `code` on `Run`. See {@link countCodeSpans} — a
 * mark the file writers cannot carry is a mark that has to be REPORTED, not quietly rendered.
 */
export type PreviewRun = DwRun & { underline?: boolean; strike?: boolean };

/* ────────────────────────────────────────────────────────────────────────────
 * Rich text, with its marks already resolved
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One span of a rich-text field, as `rich_text.to_preview_json` emits it.
 *
 * THE MARKS ARRIVE RESOLVED TO BOOLEANS rather than as the stored `marks: ["BOLD", …]` array,
 * and that is the whole point of the function that produces them. The stored vocabulary is an
 * enum the server owns; if the browser interpreted it, the browser would be a fifth renderer of
 * that vocabulary alongside the .docx writer, the server .pdf writer and the two on-device
 * Kotlin ones — and the fifth is the one that would drift, because it is the only one nobody
 * opens a file to check.
 */
export type DwRichSpan = {
  text: string;
  bold: boolean;
  italic: boolean;
  underline: boolean;
  strike: boolean;
  /**
   * `report_model.Run` HAS NO CODE MARK. A span marked as code therefore renders as code here
   * and as ordinary text in the .docx and the .pdf. That is a divergence between the screen and
   * the file, so the preview counts these and says so out loud rather than showing a designer a
   * distinction the ministry's copy will not have.
   */
  code: boolean;
};

export type DwRichBlockKind = "PARAGRAPH" | "HEADING" | "BULLET_ITEM" | "ORDERED_ITEM" | "QUOTE";

export type DwRichBlock = {
  kind: DwRichBlockKind;
  align: string;
  /** Heading level 1-4 for HEADING; list nesting depth 0-3 for the two item kinds; 0 otherwise. */
  level: number;
  spans: DwRichSpan[];
};

/* ────────────────────────────────────────────────────────────────────────────
 * The two rasterised figures
 * ──────────────────────────────────────────────────────────────────────────── */

export type DwMapPinKind = "VENUE" | "ARTISAN" | "MARKET" | "OTHER";

/**
 * One pin on the report's map.
 *
 * `count` exists because artisans cluster: six weavers from Barpali resolve to one coordinate,
 * and the builder folds them into a single point that says six rather than stacking six pins
 * nobody can tell apart.
 */
export type DwMapPin = {
  label: string;
  lat: number;
  lon: number;
  kind: DwMapPinKind;
  count: number;
};

/**
 * A rasterised figure, in the form `asdict()` can put on a JSON wire.
 *
 * PNG BYTES CANNOT TRAVEL AS BYTES. The rasteriser (`app/services/report_raster.py`) hands back
 * `bytes`, and the only way that reaches a browser inside a JSON document is base64 — so the two
 * figure blocks carry the picture as a base64 string and the `<img>` is pointed at a data URI
 * built from it. That is also why the figure is worth caching in the markup rather than fetching:
 * a second round trip for a picture the preview response already paid to draw would be slower and
 * could fail on its own.
 *
 * Both spellings are read. The field is landing in `_block_payload` in the same change as this
 * page, and a preview that renders a blank box because the key came through as `png` rather than
 * `png_base64` would be a designer told the map is missing from a report that contains it. The
 * cost of accepting both is one `??`.
 */
type DwRasterFigure = {
  png_base64?: string | null;
  png?: string | null;
  /** Pixel size of the raster, when the server states it — used to reserve the right box. */
  png_width_px?: number | null;
  png_height_px?: number | null;
};

export type DwMapBlock = DwRasterFigure & {
  type: "MAP";
  title: string;
  caption: string;
  points: DwMapPin[];
  /**
   * STATE NAMES to tint, not geometry — the model stays renderer-agnostic and the boundaries
   * already exist once, in the assets both apps draw. The live map on this page cannot tint
   * them, which is one of the two known ways it and the printed figure differ; see
   * `ReportMapFigure`.
   */
  highlight: string[];
  width_pct: number;
  align: string;
};

export type DwChartKind = "BAR" | "HORIZONTAL_BAR" | "PIE" | "DONUT" | "LINE";

export type DwChartBlock = DwRasterFigure & {
  type: "CHART";
  kind: DwChartKind;
  /** ONE series, always: `[label, value]`. A dual-axis figure is the most misread thing in a
   *  government report, so the model cannot express one. */
  series: Array<[string, number]>;
  title: string;
  caption: string;
  /** Printed once, beside the axis or in the title — never on every value. */
  unit: string;
  width_pct: number;
  align: string;
};

/**
 * A rich-text field rendered as itself rather than flattened into paragraphs.
 *
 * The .docx and .pdf writers consume the output of `rich_text.to_report_blocks`, which turns a
 * document into ordinary `ParagraphBlock` / `HeadingBlock` / `BulletListBlock`s — and in doing so
 * FLATTENS list nesting, because `BulletListBlock` has no depth. This block carries the document
 * before that flattening, so the preview can show the indentation the designer typed. Where the
 * two disagree the file wins, and the preview says which parts of it the file cannot keep.
 *
 * ⚠ NOTHING SENDS IT YET, and that is stated here rather than discovered. `_block_payload` names a
 * block by its dataclass, there is no `RichTextBlock` in `report_model`, and `to_preview_json` —
 * which emits exactly {@link DwRichBlock} and was written for this screen — has no caller. What
 * arrives today is the flattened form, with its marks already resolved onto runs, and the preview
 * draws that faithfully. The type and its renderer are kept because the flattening loses real
 * structure a designer typed, and the day the route emits the unflattened form this page shows it
 * without a change; a block type the switch did not know would be a section missing from a
 * document somebody had already approved.
 */
export type DwRichTextBlock = {
  type: "RICHTEXT";
  blocks: DwRichBlock[];
  caption?: string;
};

/**
 * Every block this page can be handed.
 *
 * A widening of `DwBlock`, so `preview.blocks` assigns straight into it — and because every
 * member is discriminated on `type`, a `switch` with no `default` still makes the compiler point
 * at the renderer the day a seventeenth block type is added. Which is the arrangement that
 * matters: an unhandled block type is a section missing from a document a designer has already
 * approved.
 */
export type PreviewBlock = DwBlock | DwMapBlock | DwChartBlock | DwRichTextBlock;

/* ────────────────────────────────────────────────────────────────────────────
 * Reading the payload
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The data URI for a rasterised figure, or null when the server sent no picture.
 *
 * Accepts a value that is already a data URI as well as bare base64: a change that starts sending
 * `data:image/png;base64,…` would otherwise produce `data:image/png;base64,data:image/png;…`,
 * which is a broken image and not an obvious one.
 */
export function rasterSource(block: DwRasterFigure): string | null {
  const raw = block.png_base64 ?? block.png ?? null;
  if (!raw) return null;
  return raw.startsWith("data:") ? raw : `data:image/png;base64,${raw}`;
}

/** Every span in the document marked as code — the mark the file writers cannot carry. */
export function countCodeSpans(blocks: PreviewBlock[]): number {
  let found = 0;
  for (const block of blocks) {
    if (block.type !== "RICHTEXT") continue;
    for (const rich of block.blocks) {
      for (const span of rich.spans) if (span.code) found += 1;
    }
  }
  return found;
}

/* ────────────────────────────────────────────────────────────────────────────
 * The report palette
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The colours the sheet and the live figures are drawn in when nobody has chosen any.
 *
 * DERIVED, NOT TRANSCRIBED. It used to be nine hex strings copied out of `report_model.ReportTheme`'s
 * defaults; it is now `paletteFromAccent` applied to the same DCH indigo those defaults describe,
 * which is the identical palette to within a hex digit and — the point — cannot drift from the
 * derivation the picker, the server and the phone all now use. A hand-copied default is a tenth
 * place for a colour to be wrong.
 *
 * ONE DECLARATION FOR TWO CONSUMERS. `ReportSheet`'s stylesheet interpolates a palette into its
 * `--rp-*` custom properties and `ReportChart` fills its bars and slices from the same object, and
 * the two MUST be the same values: a chart drawn in navy on a sheet ruled in maroon is not a
 * preview of anything. They are passed as an object rather than read out of the CSS at runtime
 * because the pie's ramp is a computed blend between two of them — `getComputedStyle` in a layout
 * effect would produce the first frame in the wrong colour and cannot run at all while the page is
 * being printed.
 *
 * ⚠ THE THEME IS STILL NOT ON THE PREVIEW WIRE. `GET /report/preview` returns `meta` and `blocks`
 * and no `ReportTheme`, so a template's OWN colour — the DIC's green, the implementing agency's
 * maroon — is still not known to this screen and a report in either is previewed in indigo until
 * the designer picks a colour. What the picker chooses IS honoured, live and exactly, because the
 * derivation is a port rather than an approximation; the strip above the sheets says which of the
 * two situations the reader is in rather than letting them find out when the file opens.
 */
export const REPORT_PALETTE: ReportPalette = paletteFromAccent(DEFAULT_ACCENT);

/* ────────────────────────────────────────────────────────────────────────────
 * Page geometry
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The paper the document will be printed on, in millimetres.
 *
 * Read off `report_model.PageSize.size_mm` and `ReportMeta.margin_mm`. The preview draws its
 * sheets at these exact dimensions and sets its type in POINTS, so a browser Print to PDF from
 * this page lands within a line or two of the server's own .pdf — which is the point of the
 * exercise: a designer on a laptop with no backend needs a presentable file, not a screenshot.
 *
 * ⚠ The margin is NOT on the preview wire. `ReportMeta.margin_mm` defaults to 25 and no template
 * currently overrides it, so 25 is right today and would be silently wrong the day one does.
 */
export type PageGeometry = {
  widthMm: number;
  heightMm: number;
  marginMm: number;
  /** What `@page { size: … }` must say for the browser's own print to use the same sheet. */
  cssPageSize: string;
  label: string;
};

const A4: PageGeometry = {
  widthMm: 210,
  heightMm: 297,
  marginMm: 25,
  cssPageSize: "A4 portrait",
  label: "A4"
};

const LETTER: PageGeometry = {
  widthMm: 215.9,
  heightMm: 279.4,
  marginMm: 25,
  cssPageSize: "letter portrait",
  label: "US Letter"
};

export function pageGeometry(pageSize: string | undefined): PageGeometry {
  return String(pageSize).toUpperCase() === "LETTER" ? LETTER : A4;
}

/**
 * A CSS millimetre in CSS pixels.
 *
 * Fixed by the spec at 96 pixels to the inch, so this is exact rather than an estimate and does
 * not need to be measured off a probe element. It is what lets the fit-to-width scaler compute a
 * factor before anything has been laid out.
 */
export const PX_PER_MM = 96 / 25.4;

/* ────────────────────────────────────────────────────────────────────────────
 * Sheets
 * ──────────────────────────────────────────────────────────────────────────── */

export type ReportSheetContent = {
  /** 1-based: the N in the "Page N of M" every renderer draws in its own foot. */
  pageNumber: number;
  /** The cover is drawn as EXACTLY one page by both writers, and carries no running furniture. */
  isCover: boolean;
  blocks: PreviewBlock[];
};

/**
 * The block stream cut into the pages the DOCUMENT declares.
 *
 * WHAT THIS CAN AND CANNOT KNOW, because getting this wrong is the failure mode the whole screen
 * exists to avoid. A `PAGEBREAK` block is a break the template asked for, and both writers honour
 * it exactly — so a break here is a break in the file. What neither this page nor anything else
 * in a browser can know is where the OTHER breaks fall: those are decided by measuring wrapped
 * text against the remaining height on the page, which Word does on open and ReportLab does by
 * laying the body out twice. A sheet below can therefore be a page and a half of the real
 * document.
 *
 * SO THE COUNT THIS RETURNS IS A FLOOR, AND THE SCREEN HAS TO SAY SO. The running foot prints
 * "Page N of M" because all four FILE renderers print exactly that and a designer proofing here
 * must not read a differently-shaped label from the one in the document they hand over — but the
 * M is THIS count, the pages the template declares, not the file's own total. A total quoted out
 * of a preview and into a covering email is the failure this paragraph exists to prevent, and
 * once a number is on the page the only defence left is stating what it is: `ReportSheets` prints
 * the floor in as many words in the strip above the sheets, and that sentence is load-bearing.
 *
 * WHICH IS WHY THE M DOES NOT PRINT. The strip is chrome and `Ctrl+P` drops all of it, so a
 * printed sheet would carry the total with nothing left to qualify it — and print is where the
 * covering email actually comes from. The browser also re-paginates when it prints (a sheet loses
 * its fixed height and only asks for a break after itself), so an overflowing sheet becomes two
 * printed pages and the total stops being even a floor. `ReportSheets` therefore wraps the "of M"
 * in its own element and hides it under `@media print`: the screen gets the label the four file
 * renderers print, and the file a designer hands over gets the ordinal alone.
 *
 * The cover is split off on its own because both writers give it exactly one page and neither
 * draws a running head or foot on it.
 */
export function splitIntoSheets(blocks: PreviewBlock[]): ReportSheetContent[] {
  const sheets: ReportSheetContent[] = [];
  let current: PreviewBlock[] = [];
  let pageNumber = 1;

  const close = (isCover: boolean) => {
    if (!current.length) return;
    sheets.push({ pageNumber, isCover, blocks: current });
    pageNumber += 1;
    current = [];
  };

  for (const block of blocks) {
    if (block.type === "COVER") {
      close(false);
      current = [block];
      close(true);
      continue;
    }
    if (block.type === "PAGEBREAK") {
      close(false);
      continue;
    }
    current.push(block);
  }
  close(false);

  // A document whose every block was a page break still has to render as something. One empty
  // sheet reads as "this template produced nothing", which is the truth and is what the designer
  // needs to see; zero sheets would render as an empty panel indistinguishable from a load that
  // had not finished.
  if (!sheets.length) sheets.push({ pageNumber: 1, isCover: false, blocks: [] });
  return sheets;
}
