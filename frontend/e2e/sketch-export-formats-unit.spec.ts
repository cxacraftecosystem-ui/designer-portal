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

const PANEL_SOURCE = join(__dirname, "..", "components", "sketches", "upload", "SketchTraceField.tsx");

const ENGINE_BY_NAME = new Map<string, ExportFormat>(
  Object.values(ExportFormat).map((format) => [String(format), format])
);

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

test("every format the engine can write is either offered or refused in writing", () => {
  const offered = EXPORT_FORMATS.map((entry) => String(entry.engineFormat));
  const refused = NOT_OFFERED.map((entry) => String(entry.format));
  const accounted = [...offered, ...refused];

  expect(new Set(accounted).size).toBe(accounted.length);

  expect([...accounted].sort()).toEqual(Object.values(ExportFormat).map(String).sort());

  for (const entry of NOT_OFFERED) {
    expect(entry.reason.length, `${entry.format} needs a real reason`).toBeGreaterThan(60);
    expect(entry.reason.trim().endsWith("."), `${entry.format}'s reason should be a sentence`).toBe(true);
  }
});

test("the table's extension and mime type are the engine's own answers, not a second opinion", () => {
  for (const entry of EXPORT_FORMATS) {
    const format = ENGINE_BY_NAME.get(entry.engineFormat);
    expect(format, `${entry.id} names an engine format that does not exist`).toBeDefined();
    const options = new ExportOptions({ format: format as ExportFormat });
    expect(options.extension, `${entry.id} extension`).toBe(entry.extension);
    expect(options.mimeType, `${entry.id} mime type`).toBe(entry.mime);
  }
});

test("every offered format has exactly one download control, and none is written out by hand", () => {
  const panel = readFileSync(PANEL_SOURCE, "utf8");

  expect(panel).toContain("EXPORT_FORMATS.map((entry) => (");
  expect(panel).toContain("void downloadDerived(entry.id)");
  expect(panel).toContain("{entry.download}");
  expect(panel).toContain("{entry.hint}");
  expect(panel).toContain("running === `download-${entry.id}`");

  expect(panel).toContain("ATTACHABLE_FORMATS.map((entry) => (");

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
    expect(entry.hint.length, `${entry.id} needs a sentence saying what it is FOR`).toBeGreaterThan(30);
    expect(entry.hint.trim().endsWith("."), `${entry.id}'s hint should be a sentence`).toBe(true);
    expect(entry.download.toLowerCase()).toContain("download");
  }
});

test("only the two formats the record can carry are attachable", () => {
  expect(ATTACHABLE_FORMATS.map((entry) => entry.id)).toEqual(["svg", "png"]);
  expect(EXPORT_FORMATS.filter((entry) => entry.attachable).length).toBe(ATTACHABLE_FORMATS.length);
});

test("the document rebuilt from the worker's arrays is the drawing the SVG writer sees", () => {
  const built = documentFrom(fixture());

  expect(built.document.width).toBe(40);
  expect(built.document.height).toBe(30);
  expect(built.document.layers).toHaveLength(1);
  expect(built.document.layers[0].name).toBe(TRACE_LAYER_NAME);

  const shapes = built.document.layers[0].shapes;
  expect(shapes).toHaveLength(3);
  expect(built.shapesWritten).toBe(3);
  expect(built.truncationNote).toBeNull();

  expect(shapes[0].path.start).toEqual({ x: 0, y: 0 });
  expect(shapes[0].path.segments).toEqual([{ kind: "line", to: { x: 10, y: 0 } }]);
  expect(shapes[0].path.closed).toBe(false);

  expect(shapes[1].path.start).toEqual({ x: 0, y: 10 });
  expect(shapes[1].path.segments).toEqual([
    { kind: "quad", c: { x: 5, y: 15 }, to: { x: 10, y: 10 } }
  ]);
  expect(shapes[1].path.closed).toBe(true);

  expect(shapes[2].path.segments).toEqual([
    { kind: "cubic", c1: { x: 3, y: 25 }, c2: { x: 7, y: 25 }, to: { x: 10, y: 20 } }
  ]);

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
  const broken = { ...fixture(), width: Number.NaN, height: -12 };
  const built = documentFrom(broken);
  expect(built.document.width).toBe(1);
  expect(built.document.height).toBe(1);
});

test("the shape ceiling and its sentence are the SVG's, so two formats of one drawing agree", () => {
  const built = documentFrom(fixture(), 2);
  expect(built.shapesWritten).toBe(2);
  expect(built.document.layers[0].shapes).toHaveLength(2);
  expect(built.truncationNote).toContain("Minimum speck");
  expect(built.truncationNote).toContain("the file holds the first 2");

  expect(documentFrom(fixture()).shapesWritten).toBeLessThanOrEqual(MAX_SHAPES_PER_FILE);
});

test("each vector format produces a file its own reader would recognise", async () => {
  const input = fixture();

  const svg = exportSvgFile(input, "sheet.png", NOTE, { suffix: TRACE_SUFFIX });
  expect(svg.file.name).toBe("sheet-line-art.svg");
  expect(svg.file.type).toBe("image/svg+xml");

  for (const id of ["pdf", "eps", "dxf"] as const) {
    const entry = EXPORT_FORMATS.find((candidate) => candidate.id === id);
    expect(entry, `${id} is missing from EXPORT_FORMATS`).toBeDefined();

    const outcome = await exportVectorFile(id, input, "sheet.png", NOTE, { suffix: TRACE_SUFFIX });
    expect(isExported(outcome) ? "exported" : outcome.reason).toBe("exported");
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
      expect(text).toContain("0 0 m");
      expect(text).toContain("10 0 l");
    } else if (id === "eps") {
      expect(text.startsWith("%!PS-Adobe-3.0 EPSF-3.0")).toBe(true);
      expect(text).toContain("%%BoundingBox: 0 0 40 30");
      expect(text).toContain("0 0 moveto");
      expect(text).toContain("10 0 lineto");
      expect(text).toContain("showpage");
    } else {
      expect(text.startsWith("0\r\nSECTION")).toBe(true);
      expect(text).toContain("AC1009");
      expect(text).toContain("LINE_ART");
      expect(text).toContain("POLYLINE");
      expect(text).toContain("VERTEX");
      expect(text.trimEnd().endsWith("EOF")).toBe(true);
    }
  }
});

test("provenance reaches the three formats that have somewhere to keep it, and no others", async () => {
  const input = fixture();

  const pdf = await exportVectorFile("pdf", input, "sheet.png", NOTE);
  const eps = await exportVectorFile("eps", input, "sheet.png", NOTE);
  const dxf = await exportVectorFile("dxf", input, "sheet.png", NOTE);
  expect(isExported(pdf) && isExported(eps) && isExported(dxf)).toBe(true);
  if (!isExported(pdf) || !isExported(eps) || !isExported(dxf)) return;

  expect(await pdf.file.text()).toContain("Traced on the device from sheet.png");
  expect(await eps.file.text()).toContain("Traced on the device from sheet.png");
  expect(await dxf.file.text()).not.toContain("Traced on the device");

  const svg = await exportSvgFile(input, "sheet.png", NOTE).file.text();
  expect(svg).toContain(`<!-- ${NOTE} -->`);
});

test("a format the panel cannot write is refused with a sentence rather than thrown", async () => {
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
