import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { ExportFormat, ExportOptions } from "@/lib/trace/engine/exportFormats";
import { FillRule, LineCap, LineJoin } from "@/lib/trace/engine/path";

import {
  MAX_SHAPES_PER_FILE,
  VERB_CUBIC,
  VERB_LINE,
  VERB_QUAD,
  type FlatGeometry,
  type GeometryStyle,
  type SvgInput
} from "@/components/sketches/upload/geometryToSvg";
import { TRACE_LAYER_NAME, documentFrom } from "@/components/sketches/upload/geometryToDocument";
import {
  ATTACHABLE_FORMATS,
  EXPORT_FORMATS,
  NOT_OFFERED,
  TRACE_SUFFIX,
  exportSvgFile,
  exportVectorFile,
  isExported,
  type VectorFormatId
} from "@/components/sketches/upload/traceExport";

/**
 * The sketch tracer's export surface, held shut in both directions.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * WHAT WENT WRONG, WHICH IS THE ONLY REASON THIS FILE EXISTS
 * ────────────────────────────────────────────────────────────────────────────
 *
 * `lib/trace/engine/exportFormats.ts` can write seven formats. The panel offered two. The other three
 * that a designer would actually want — PDF, EPS and DXF — were finished, tested writers sitting in
 * the engine with **no control anywhere able to reach them**, for as long as this feature has existed.
 * Nothing failed, nothing was logged, and nothing in the code said whether that was a decision or an
 * oversight; it was simply invisible. A designer could trace a paper sketch into clean line art and
 * then had no way to take the vector result to a print shop or a cutting machine.
 *
 * That is a gap no test could have caught, because there was nothing to test: the failure was an
 * ABSENCE, and absence is exactly the class of defect this repository keeps paying for (§1.10 of the
 * frontend contract, and every entry under "Silent-emptiness class" in §17).
 *
 * So this file makes the absence expressible. `EXPORT_FORMATS` is now the single register of what this
 * panel can produce, and the two assertions that matter are:
 *
 *   TABLE → CONTROL   every row draws a button, because the row of buttons is `EXPORT_FORMATS.map`.
 *                     There is no way to add a row and forget the control.
 *   ENGINE → TABLE    every member of `ExportFormat` is either in the table or in `NOT_OFFERED` with a
 *                     written reason. The next writer added to the engine cannot go unexposed the same
 *                     silent way: somebody has to wire it up, or say why not, and either is fine.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * WHY IT RUNS IN NODE
 * ────────────────────────────────────────────────────────────────────────────
 *
 * `-unit` means it needs nothing but node (`e2e/README.md`), and this needs nothing but node. The
 * engine's writers are string and byte arithmetic over a `VecDocument`; `engine/path.ts` imports
 * nothing at all; `exportFormats.ts` touches no DOM (`buffers.ts` carries its own `ImageDataLike`
 * precisely so "the engine never touches the DOM"); and the geometry below is typed arrays this file
 * fills in by hand. No browser, no server, no worker.
 *
 * **PNG is the one row whose bytes this file cannot produce**, and that is a property of the format
 * rather than a hole in the coverage: it is rendered through `canvas.toBlob`, deliberately, because
 * the browser already ships a PNG encoder and `exportFormats.ts` says in its own header that the
 * platform layer owns the pixel formats. `e2e/sketch-trace-panel.spec.ts` presses that button in a
 * real browser and checks the file that comes out. Everything about the PNG row that IS checkable
 * without a canvas — that it exists, that it has a control, that its extension and mime are the
 * engine's own answers — is checked here.
 */

const PANEL_SOURCE = join(__dirname, "..", "components", "sketches", "upload", "SketchTraceField.tsx");

/**
 * The engine's own enum, by its string value.
 *
 * A MAP RATHER THAN A CAST. `ExportFormat` is a string enum whose values equal their names, so
 * `"SVG"` and `ExportFormat.SVG` are the same bytes and different types — and `as ExportFormat` on a
 * plain literal is the kind of cast that keeps compiling after somebody renames a member. Looking the
 * value up means a table row naming a format the engine does not have fails here rather than silently
 * constructing options for `undefined`.
 */
const ENGINE_BY_NAME = new Map<string, ExportFormat>(
  Object.values(ExportFormat).map((format) => [String(format), format])
);

/* ────────────────────────────────────────────────────────────────────────────
 * A drawing, built by hand
 *
 * Three shapes, one of each segment kind, one of them closed — enough that a writer which dropped a
 * verb, mis-stepped the coordinate cursor or ignored `closed` produces visibly different bytes. The
 * style is deliberately awkward (a translucent fill, a non-default cap, join and miter limit) because
 * every one of those is a field the adapter has to map from a plain string onto an engine enum, and a
 * default-shaped style would pass whether the mapping ran or not.
 * ──────────────────────────────────────────────────────────────────────────── */

const STYLE: GeometryStyle = {
  stroke: 0xff112233,
  strokeWidth: 2.5,
  fill: 0x80445566,
  fillRule: "NONZERO",
  cap: "SQUARE",
  join: "BEVEL",
  miterLimit: 6,
  opacity: 0.5
};

function fixture(): SvgInput {
  const geometry: FlatGeometry = {
    // shape 0: M0 0 L10 0            shape 1: M0 10 Q5 15 10 10 Z      shape 2: M0 20 C3 25 7 25 10 20
    coords: new Float32Array([0, 0, 10, 0, 0, 10, 5, 15, 10, 10, 0, 20, 3, 25, 7, 25, 10, 20]),
    verbs: new Uint8Array([VERB_LINE, VERB_QUAD, VERB_CUBIC]),
    verbStarts: new Uint32Array([0, 1, 2, 3]),
    coordStarts: new Uint32Array([0, 4, 10, 18]),
    closed: new Uint8Array([0, 1, 0]),
    styleTable: [STYLE],
    styleIndex: new Uint32Array([0, 0, 0])
  };
  return { geometry, width: 40, height: 30, background: null };
}

const NOTE = "Traced on the device from sheet.png by the Design & Prototype Workshop portal.";

/* ────────────────────────────────────────────────────────────────────────────
 * 1. Engine → table: nothing the engine can write may go unaccounted for
 * ──────────────────────────────────────────────────────────────────────────── */

test("every format the engine can write is either offered or refused in writing", () => {
  const offered = EXPORT_FORMATS.map((entry) => String(entry.engineFormat));
  const refused = NOT_OFFERED.map((entry) => String(entry.format));
  const accounted = [...offered, ...refused];

  // No format may appear on both lists, or in one list twice — "offered and also refused" is not a
  // state a reader can act on, and it is what a copy-pasted row looks like.
  expect(new Set(accounted).size).toBe(accounted.length);

  // THE ASSERTION THIS WHOLE FILE IS FOR. Add `ExportFormat.AVIF` to the engine and this fails until
  // somebody either puts it in front of a designer or writes down why it stays out.
  expect([...accounted].sort()).toEqual(Object.values(ExportFormat).map(String).sort());

  // A refusal with no reason is the same silence as no entry at all, so the reason has to be a
  // sentence somebody wrote rather than a word somebody typed to make a test pass.
  for (const entry of NOT_OFFERED) {
    expect(entry.reason.length, `${entry.format} needs a real reason`).toBeGreaterThan(60);
    expect(entry.reason.trim().endsWith("."), `${entry.format}'s reason should be a sentence`).toBe(true);
  }
});

test("the table's extension and mime type are the engine's own answers, not a second opinion", () => {
  // `traceExport.ts` cannot import `exportFormats.ts` — that module pulls the rasteriser and the PNG
  // encoder, and the panel's whole bundle discipline is that the engine stays off the page graph. So
  // the table restates the extension and the mime type, and a restatement is a thing that drifts.
  // This is the only place the two can be compared, and it costs nothing here because node has no
  // bundle to protect.
  for (const entry of EXPORT_FORMATS) {
    const format = ENGINE_BY_NAME.get(entry.engineFormat);
    expect(format, `${entry.id} names an engine format that does not exist`).toBeDefined();
    const options = new ExportOptions({ format: format as ExportFormat });
    expect(options.extension, `${entry.id} extension`).toBe(entry.extension);
    expect(options.mimeType, `${entry.id} mime type`).toBe(entry.mime);
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. Table → control, and control → table
 * ──────────────────────────────────────────────────────────────────────────── */

test("every offered format has exactly one download control, and none is written out by hand", () => {
  const panel = readFileSync(PANEL_SOURCE, "utf8");

  // The row of buttons and the list of explanations are BOTH generated from the table, so a new row
  // arrives with a button and a sentence saying what it is for, and cannot arrive with one and not
  // the other.
  expect(panel).toContain("EXPORT_FORMATS.map((entry) => (");
  expect(panel).toContain("void downloadDerived(entry.id)");
  expect(panel).toContain("{entry.download}");
  expect(panel).toContain("{entry.hint}");
  // One spinner per format, keyed by id — this was `running === "download-trace"` when there were two
  // buttons, which would have spun the wrong one the moment a third appeared.
  expect(panel).toContain("running === `download-${entry.id}`");

  // The "Attach as" chips come from the filtered list, so moving a format between the two surfaces is
  // one boolean in the table rather than an edit in two pieces of JSX.
  expect(panel).toContain("ATTACHABLE_FORMATS.map((entry) => (");

  // AND NO LABEL IS DUPLICATED INTO THE COMPONENT. A hand-written button beside the generated ones
  // would satisfy every assertion above and still be a control the table does not know about — which
  // is one half of the bug this file exists to prevent, wearing the other coat.
  for (const entry of EXPORT_FORMATS) {
    expect(panel, `${entry.id}'s label is hard-coded in the panel`).not.toContain(entry.download);
  }
});

test("the table itself is usable copy: unique ids, unique labels, a sentence for each", () => {
  const ids = EXPORT_FORMATS.map((entry) => entry.id);
  expect(new Set(ids).size).toBe(ids.length);

  const labels = EXPORT_FORMATS.map((entry) => entry.download);
  expect(new Set(labels).size, "two buttons with one name is two buttons nobody can tell apart").toBe(
    labels.length
  );

  for (const entry of EXPORT_FORMATS) {
    // THE AUDIENCE IS A DESIGNER, NOT A DEVELOPER. A button that says "DXF" and nothing else asks the
    // person pressing it to already know what a DXF is for; the hint is the half that answers that,
    // so an empty or bare one is a regression even though nothing would break.
    expect(entry.hint.length, `${entry.id} needs a sentence saying what it is FOR`).toBeGreaterThan(60);
    expect(entry.hint.trim().endsWith("."), `${entry.id}'s hint should be a sentence`).toBe(true);
    expect(entry.download.toLowerCase()).toContain("download");
  }
});

test("only the two formats the record can carry are attachable", () => {
  // PINNED SO THAT WIDENING IT IS A DECISION. The record is a shared archive the Android client also
  // reads and writes, and the Kotlin port of this tracer is scoped separately — so filing a `.dxf` on
  // `sketch.lineArtFile` would put a file in the archive that the handset can neither produce nor
  // preview, buying a designer nothing they do not already get by downloading it.
  // `traceExport.ts`'s header carries the full argument; this line makes somebody read it.
  expect(ATTACHABLE_FORMATS.map((entry) => entry.id)).toEqual(["svg", "png"]);
  expect(EXPORT_FORMATS.filter((entry) => entry.attachable).length).toBe(ATTACHABLE_FORMATS.length);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. The adapter: flat arrays back into a document
 * ──────────────────────────────────────────────────────────────────────────── */

test("the document rebuilt from the worker's arrays is the drawing the SVG writer sees", () => {
  const built = documentFrom(fixture());

  expect(built.document.width).toBe(40);
  expect(built.document.height).toBe(30);
  expect(built.document.layers).toHaveLength(1);
  // The layer name is what a CAD operator reads off the DXF, so it is asserted rather than assumed.
  expect(built.document.layers[0].name).toBe(TRACE_LAYER_NAME);

  const shapes = built.document.layers[0].shapes;
  expect(shapes).toHaveLength(3);
  expect(built.shapesWritten).toBe(3);
  expect(built.truncationNote).toBeNull();

  // Segment kinds, in order, with their control points — a writer fed a mis-stepped cursor produces a
  // plausible-looking file full of the wrong curves, which is the failure that is hardest to see.
  expect(shapes[0].path.start).toEqual({ x: 0, y: 0 });
  expect(shapes[0].path.segments).toEqual([{ kind: "line", to: { x: 10, y: 0 } }]);
  expect(shapes[0].path.closed).toBe(false);

  expect(shapes[1].path.start).toEqual({ x: 0, y: 10 });
  expect(shapes[1].path.segments).toEqual([
    { kind: "quad", c: { x: 5, y: 15 }, to: { x: 10, y: 10 } }
  ]);
  // `closed` is one byte per shape and the easiest thing in the protocol to drop; a dropped close on
  // a DXF polyline is an open contour, which a cutting machine happily cuts as an open contour.
  expect(shapes[1].path.closed).toBe(true);

  expect(shapes[2].path.segments).toEqual([
    { kind: "cubic", c1: { x: 3, y: 25 }, c2: { x: 7, y: 25 }, to: { x: 10, y: 20 } }
  ]);

  // The three string-valued style fields, mapped onto the engine's enums rather than cast into them.
  expect(shapes[0].style.fillRule).toBe(FillRule.NONZERO);
  expect(shapes[0].style.cap).toBe(LineCap.SQUARE);
  expect(shapes[0].style.join).toBe(LineJoin.BEVEL);
  expect(shapes[0].style.stroke).toBe(0xff112233);
  expect(shapes[0].style.fill).toBe(0x80445566);
  expect(shapes[0].style.strokeWidth).toBeCloseTo(2.5, 5);
  expect(shapes[0].style.miterLimit).toBeCloseTo(6, 5);
  expect(shapes[0].style.opacity).toBeCloseTo(0.5, 5);
});

test("a dimension that never became a number still produces a page a reader can open", () => {
  // `writePdf`/`writeEps` guard with `Math.max(1, doc.width)`, and `Math.max(1, NaN)` is `NaN` — so an
  // unsanitised width reaches a `/MediaBox` as `0.0` and the PDF opens blank with no error anywhere.
  // `sanitizeDimension` is `geometryToSvg`'s, shared, so the SVG and the PDF agree about the page.
  const broken = { ...fixture(), width: Number.NaN, height: -12 };
  const built = documentFrom(broken);
  expect(built.document.width).toBe(1);
  expect(built.document.height).toBe(1);
});

test("the shape ceiling and its sentence are the SVG's, so two formats of one drawing agree", () => {
  const built = documentFrom(fixture(), 2);
  expect(built.shapesWritten).toBe(2);
  expect(built.document.layers[0].shapes).toHaveLength(2);
  // The same words `buildSvg` shows, through `truncationNoteFor` — five formats each phrasing the cut
  // differently would read to a designer as five different faults.
  expect(built.truncationNote).toContain("Minimum speck");
  expect(built.truncationNote).toContain("the file holds the first 2");

  // And the real ceiling is the SVG's own, not a number invented for the vector path.
  expect(documentFrom(fixture()).shapesWritten).toBeLessThanOrEqual(MAX_SHAPES_PER_FILE);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. Each writer, end to end through the door the panel presses
 * ──────────────────────────────────────────────────────────────────────────── */

test("each vector format produces a file its own reader would recognise", async () => {
  const input = fixture();

  // SVG first, as the reference: it is what the record receives, and the other three are the same
  // drawing written for a machine that will not take one.
  const svg = exportSvgFile(input, "sheet.png", NOTE, { suffix: TRACE_SUFFIX });
  expect(svg.file.name).toBe("sheet-line-art.svg");
  expect(svg.file.type).toBe("image/svg+xml");

  for (const id of ["pdf", "eps", "dxf"] as const) {
    const entry = EXPORT_FORMATS.find((candidate) => candidate.id === id);
    expect(entry, `${id} is missing from EXPORT_FORMATS`).toBeDefined();

    const outcome = await exportVectorFile(id, input, "sheet.png", NOTE, { suffix: TRACE_SUFFIX });
    // Surfacing the refusal in the diff rather than a bare `false`: the two ways this fails —
    // a writer that threw, and a dynamic import that did not resolve — need different fixes.
    expect(isExported(outcome) ? "exported" : outcome.reason).toBe("exported");
    // `continue`, not `return`: one writer failing must not take the other two's assertions with it,
    // or a single broken format reports as three.
    if (!isExported(outcome)) continue;

    expect(outcome.file.name).toBe(`sheet-line-art.${entry?.extension}`);
    expect(outcome.file.type).toBe(entry?.mime);
    expect(outcome.note).toBeNull();
    expect(outcome.file.size).toBeGreaterThan(0);

    const text = await outcome.file.text();
    if (id === "pdf") {
      expect(text.startsWith("%PDF-1.4")).toBe(true);
      expect(text).toContain("%%EOF");
      expect(text).toContain("/MediaBox [0 0 40 30]");
      // The GEOMETRY reached the content stream, not merely a well-formed empty page — which is what
      // a rehydration that produced no shapes would look like from every other assertion here.
      expect(text).toContain("0 0 m");
      expect(text).toContain("10 0 l");
    } else if (id === "eps") {
      expect(text.startsWith("%!PS-Adobe-3.0 EPSF-3.0")).toBe(true);
      expect(text).toContain("%%BoundingBox: 0 0 40 30");
      expect(text).toContain("0 0 moveto");
      expect(text).toContain("10 0 lineto");
      expect(text).toContain("showpage");
    } else {
      // R12 group codes on alternating lines, and the layer a controller will show the operator.
      expect(text.startsWith("0\r\nSECTION")).toBe(true);
      expect(text).toContain("AC1009");
      expect(text).toContain("LINE_ART");
      // R12 has no spline entity, so every curve arrives flattened into a polyline — the fact the
      // DXF hint tells a designer about before they send the file to a cutter.
      expect(text).toContain("POLYLINE");
      expect(text).toContain("VERTEX");
      expect(text.trimEnd().endsWith("EOF")).toBe(true);
    }
  }
});

test("provenance reaches the three formats that have somewhere to keep it, and no others", async () => {
  // THE COPY UNDER THE BUTTONS MAKES THIS CLAIM, AND NO CLIENT CAN CHECK A CLAIM ABOUT A FILE BY
  // LOOKING AT IT. `writePdf` writes `/Title`, `writeEps` writes `%%Title:`, and `writeDxf` takes no
  // metadata argument at all because DXF R12 has no channel for one — so a designer who cropped a
  // photograph and downloaded a `.dxf` holds a file that records nothing about the crop. The panel
  // says so beside the button; this is what stops that sentence going stale in either direction.
  const input = fixture();

  const pdf = await exportVectorFile("pdf", input, "sheet.png", NOTE);
  const eps = await exportVectorFile("eps", input, "sheet.png", NOTE);
  const dxf = await exportVectorFile("dxf", input, "sheet.png", NOTE);
  expect(isExported(pdf) && isExported(eps) && isExported(dxf)).toBe(true);
  if (!isExported(pdf) || !isExported(eps) || !isExported(dxf)) return;

  expect(await pdf.file.text()).toContain("Traced on the device from sheet.png");
  expect(await eps.file.text()).toContain("Traced on the device from sheet.png");
  expect(await dxf.file.text()).not.toContain("Traced on the device");

  // The SVG's own channel, for completeness — an XML comment, which is why `buildSvg` collapses runs
  // of hyphens before writing one. Four of the five formats carry the note; the fifth is the PNG, and
  // `exportPngFile` has no parameter to pass it through.
  const svg = await exportSvgFile(input, "sheet.png", NOTE).file.text();
  expect(svg).toContain(`<!-- ${NOTE} -->`);
});

test("a format the panel cannot write is refused with a sentence rather than thrown", async () => {
  // Unreachable through the UI, which only ever passes an id read off the table — which is exactly
  // why it is worth pinning: the next caller of this function may not be the panel, and a render that
  // throws is a worse answer than a refusal a designer can read.
  const outcome = await exportVectorFile(
    "tiff" as unknown as VectorFormatId,
    fixture(),
    "sheet.png",
    NOTE
  );
  expect(isExported(outcome)).toBe(false);
  if (isExported(outcome)) return;
  expect(outcome.reason).toContain("tiff");
});
