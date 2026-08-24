/**
 * Turning a finished trace into a `File` the ordinary upload door will take.
 *
 * THE DOOR IS `onAttach`, AND THIS FILE DELIBERATELY DOES NOT KNOW WHAT IS BEHIND IT.
 * `components/designworkshop/SketchRectifyField.tsx` solves the identical problem for the neighbouring
 * feature and its header states the property this copies: "The derived file also goes in through the
 * ordinary door — `attach`, the same one a camera photograph uses — so eager pre-upload, per-file
 * retry and the offline draft store all already apply to it. Nothing here uploads anything itself."
 * Nothing here uploads anything either. These functions return a `File` and stop.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * WHICH FORMATS, AND THE HONEST ACCOUNT OF THE ONES THAT ARE MISSING
 * ────────────────────────────────────────────────────────────────────────────
 *
 * `engine/exportFormats.ts` can write SVG, PDF, EPS, DXF, PNG, BMP and TIFF. This offers **SVG and
 * PNG**, and the reason the other five are absent is weight rather than difficulty:
 * `exportDocument` takes a `VecDocument`, the worker never sends one (see `geometryToSvg.ts`'s
 * header), and rehydrating one on the page thread means `engine/path.ts` plus a writer plus the
 * million object allocations the flat-array protocol exists to avoid. SVG needed none of that
 * because the flat arrays already contain the file in order; PDF, EPS and DXF genuinely do.
 *
 * SVG AND PNG ARE ALSO THE TWO THE ARCHIVE ACTUALLY WANTS, which is what makes the trade a good one
 * rather than merely a cheap one:
 *   - the registry declares `sketch.lineArtFile` as "An SVG or vector export", so SVG is the target
 *     the field was written for;
 *   - and PNG is the form anybody can open, drop into a slide or print without a vector tool.
 *
 * ── WHAT NEITHER OF THEM DOES, AND THIS FILE USED TO CLAIM ONE OF THEM DID ──────────────────────
 *
 * The paragraph above used to end "the ministry `.docx` gathers IMAGE fields, so a raster is the
 * only form of this drawing that can be printed in a report at all", and the PNG hint below said the
 * same thing to the designer at the moment of choosing. It is true of the report and false of this
 * tab, which is the worst combination: BOTH exports are attached to `sketch.lineArtFile`, which is a
 * FILE field, and `report_builder.format_value` prints a FILE as "1 document attached" whatever is
 * inside it. Stage 11 declares exactly ONE image slot — `sketch.image` — and `UploadTabPanel`
 * reserves it for the source photograph so a derived plate cannot displace the original
 * (`docs/MEDIA_PIPELINE.md` §5). So choosing PNG here changed nothing about what the officer sees,
 * while the copy said it changed everything.
 *
 * The fix is the copy, not the export: making a traced plate print as a picture needs a SECOND image
 * slot on the sketch entity, which is a registry change in all four places plus the Android bundled
 * asset, and an owner decision about whether a machine-traced drawing belongs in the photographic
 * record beside the photographs. `backend/tests/test_report_sketch_prototype_mapping.py` pins what
 * happens today so the sentence cannot drift back.
 *
 * If somebody adds PDF later, add it here behind its own `await import()` and measure what it costs.
 */

import {
  DEFAULT_DERIVED_SUFFIX,
  buildSvg,
  derivedFileName,
  shapePathData,
  type SvgInput,
  type SvgResult
} from "./geometryToSvg";

/** What a caller gets back: the file to attach, plus anything the designer must be told. */
export interface ExportOutcome {
  readonly file: File;
  /** Non-null when the drawing was truncated or otherwise reduced. Show it. */
  readonly note: string | null;
}

export interface ExportRefusal {
  readonly reason: string;
}

export type ExportResult = ExportOutcome | ExportRefusal;

export function isExported(result: ExportResult): result is ExportOutcome {
  return "file" in result;
}

/**
 * The formats offered, in the order the panel lists them.
 *
 * SVG leads because it is what the field asks for and because it is the only one of the two that can
 * still be edited, re-scaled or sent to a plotter afterwards.
 */
export const EXPORT_FORMATS = [
  {
    id: "svg",
    label: "SVG",
    hint: "Vector. Scales to any size, opens in Illustrator, Inkscape and CorelDRAW, and is what the “Line art” field is declared for."
  },
  {
    id: "png",
    label: "PNG",
    hint: "Raster, transparent where the drawing is not. Opens anywhere, and drops straight into a letter or a slide. Both forms are filed as attachments on the sketch: the ministry report names the file and draws only the photograph, whichever you choose."
  }
] as const;

export type ExportFormatId = (typeof EXPORT_FORMATS)[number]["id"];

/* ────────────────────────────────────────────────────────────────────────────
 * THE THREE THINGS ONE PHOTOGRAPH PRODUCES, AND THE SUFFIX EACH ONE CARRIES
 *
 * The panel attaches a plate to the record, and — since the owner asked for both downloads — also
 * hands the designer the vector geometry and the rendered raster as files on their own device. All
 * three come out of the SAME `buildSvg`/`paintGeometry` pair, on purpose: a download that was drawn
 * by different code from the file the record gets is a download that can disagree with the archive,
 * and the disagreement would surface as "the drawing I emailed is not the drawing in the report".
 *
 * So the only thing that distinguishes them is the name, which makes the name load-bearing:
 *
 *   ATTACH_SUFFIX ("line-art")  the plate filed on `sketch.lineArtFile`. UNCHANGED from before this
 *                               was a constant — `sketch-line-art.svg` is what the record already
 *                               holds and what `sketch-trace-panel.spec.ts` pins.
 *   TRACE_SUFFIX ("line-art")   the downloaded VECTOR trace. Deliberately the same word as the
 *                               attach: it is byte-for-byte the same file, and giving the copy on
 *                               the designer's laptop a different name would invite the belief that
 *                               it is a different drawing. Two constants rather than one alias
 *                               because they answer to different questions — if the archive ever
 *                               renames its plate, the download must not silently follow.
 *   RENDER_SUFFIX ("traced")    the downloaded RENDERED raster. A different word, because a PNG
 *                               named "-line-art.png" is exactly what an attached PNG is called.
 * ──────────────────────────────────────────────────────────────────────────── */

export const ATTACH_SUFFIX = DEFAULT_DERIVED_SUFFIX;
export const TRACE_SUFFIX = DEFAULT_DERIVED_SUFFIX;
export const RENDER_SUFFIX = "traced";

/** Per-call naming, for the callers that are not the attach. See the block above. */
export interface ExportNaming {
  /** The word between the photograph's stem and the extension. Defaults to {@link ATTACH_SUFFIX}. */
  readonly suffix?: string;
}

/* ────────────────────────────────────────────────────────────────────────────
 * SVG
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * @returns an `image/svg+xml` file.
 *
 * The MIME type matters beyond tidiness: `lib/imageQuality.isMeasurableImage` excludes
 * `image/svg+xml` from the blur and resolution checks, and that exclusion is correct for line art —
 * a Laplacian variance measured on a rasterised vector says nothing about the photograph anybody took.
 * Declaring the type honestly is what lets that existing rule do its job without a special case.
 */
export function exportSvgFile(
  input: SvgInput,
  sourceName: string,
  provenanceNote?: string,
  naming: ExportNaming = {}
): ExportOutcome {
  const result: SvgResult = buildSvg(input, { provenanceNote });
  const name = derivedFileName(sourceName, "svg", naming.suffix ?? ATTACH_SUFFIX);
  return {
    file: new File([result.svg], name, { type: "image/svg+xml" }),
    note: result.truncationNote
  };
}

/* ────────────────────────────────────────────────────────────────────────────
 * PNG
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The longest edge a rendered PNG may have.
 *
 * A trace of a 4096px photograph would otherwise produce a 4096px canvas, which is 67 MB of RGBA
 * before compression — on the handsets this runs on that is a `toBlob` that returns null and a
 * feature that fails with no explanation. 2048 is large enough that a printed plate at 300 dpi is
 * still about 17 cm across, and the SVG beside it has no ceiling at all for anyone who needs more.
 */
export const PNG_MAX_EDGE_PX = 2048;

/**
 * Render the traced paths to a PNG.
 *
 * WHY `Path2D` AND NOT A RASTERISED SVG STRING. The obvious route — put the SVG in an `<img>` and
 * draw it — is a network-shaped operation in disguise: an SVG loaded into an image element is a
 * separate document fetch, it is tainted in some browsers, and it silently drops external references.
 * `Path2D` accepts the same path data directly, draws synchronously, and cannot reach anything.
 * The whole render therefore stays on this device, which is the owner's requirement for this feature
 * and not merely a preference.
 *
 * It runs on the MAIN thread, unlike the trace itself, and that is a deliberate difference rather
 * than an oversight: this is one pass of the browser's own C++ path rasteriser over geometry that is
 * already computed, measured in tens of milliseconds, and it happens once when a button is pressed —
 * not per slider tick. The engine runs in a worker because `Pipeline.run` is seconds of TypeScript;
 * this is not that.
 */
export async function exportPngFile(
  input: SvgInput,
  sourceName: string,
  maxEdge: number = PNG_MAX_EDGE_PX,
  naming: ExportNaming = {}
): Promise<ExportResult> {
  const sourceWidth = Number.isFinite(input.width) && input.width > 0 ? input.width : 1;
  const sourceHeight = Number.isFinite(input.height) && input.height > 0 ? input.height : 1;
  const scale = Math.min(1, maxEdge / Math.max(sourceWidth, sourceHeight));
  const width = Math.max(1, Math.round(sourceWidth * scale));
  const height = Math.max(1, Math.round(sourceHeight * scale));

  const canvas = createCanvas(width, height);
  if (canvas === null) {
    return { reason: "This browser would not give the page a drawing surface, so a PNG cannot be made here. The SVG can." };
  }
  const context = canvas.getContext("2d") as
    | CanvasRenderingContext2D
    | OffscreenCanvasRenderingContext2D
    | null;
  if (context === null) {
    return { reason: "This browser would not give the page a drawing surface, so a PNG cannot be made here. The SVG can." };
  }

  paintGeometry(context, input, scale);

  const blob = await canvasToBlob(canvas);
  if (blob === null) {
    return {
      reason:
        "The drawing was traced but this browser could not turn it into a PNG — usually because the " +
        "image is very large. Attach the SVG instead, or lower “Trace resolution” and try again."
    };
  }
  const name = derivedFileName(sourceName, "png", naming.suffix ?? ATTACH_SUFFIX);
  const note =
    scale < 1
      ? `The PNG is ${width}x${height}, reduced from ${Math.round(sourceWidth)}x${Math.round(sourceHeight)} so it stays printable on a phone. The SVG beside it has no such limit.`
      : null;
  return { file: new File([blob], name, { type: "image/png" }), note };
}

/**
 * Paint a traced document onto a 2D context, already scaled.
 *
 * SHARED BY THE LIVE PREVIEW AND THE PNG EXPORT, WHICH IS THE WHOLE REASON IT IS A FUNCTION. A
 * preview drawn by different code from the file that gets attached is a preview that can lie, and it
 * lies in the one direction nobody checks — the picture on screen looks right and the file does not.
 * One painter means the thing a designer approved is the thing the record receives.
 *
 * Scaling the CONTEXT rather than the coordinates keeps stroke widths proportional, which is the
 * difference between a reduced drawing and a reduced drawing whose hairlines stayed the same width.
 *
 * TRANSLUCENCY IS THE PLACE THIS HAS ALREADY DIVERGED ONCE — see the note at the fill and stroke
 * passes. A colour's alpha byte and `style.opacity` are two separate multiplications in the SVG the
 * writer emits, so they are two separate multiplications here.
 */
export function paintGeometry(
  context: CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D,
  input: SvgInput,
  scale: number
): void {
  const width = Number.isFinite(input.width) && input.width > 0 ? input.width : 1;
  const height = Number.isFinite(input.height) && input.height > 0 ? input.height : 1;

  context.save();
  context.scale(scale, scale);

  if (input.background !== null) {
    context.globalAlpha = alphaOf(input.background);
    context.fillStyle = argbToCss(input.background);
    context.fillRect(0, 0, width, height);
    context.globalAlpha = 1;
  }

  const geometry = input.geometry;
  const count = geometry.styleIndex.length;
  for (let i = 0; i < count; i += 1) {
    const d = shapePathData(geometry, i, 3);
    if (d.length === 0) continue;
    const style = geometry.styleTable[geometry.styleIndex[i]];
    if (!style) continue;
    const path = new Path2D(d);
    // THE COLOUR'S ALPHA IS MULTIPLIED IN, PER PASS, BECAUSE THE SVG WRITER DOES EXACTLY THAT.
    // `geometryToSvg.styleAttrs` emits `fill-opacity` / `stroke-opacity` from the colour's own alpha
    // byte AND `opacity` from `style.opacity`, and SVG multiplies the two. Setting `globalAlpha` from
    // `style.opacity` alone therefore painted a translucent stroke opaque — on screen and in the
    // attached PNG — while the attached SVG showed it translucent, which is the one divergence
    // between the painter and the writer this shared function exists to prevent. The background path
    // above already did it this way, which is what made the omission look accidental rather than
    // meant. (One approximation remains and is not fixable here: SVG's `opacity` composites the whole
    // element, so a half-transparent path whose own fill and stroke overlap does not darken at the seam,
    // while two canvas passes do. Today's geometry never sets both on one shape.)
    const shapeOpacity = Number.isFinite(style.opacity) ? Math.min(1, Math.max(0, style.opacity)) : 1;
    if (style.fill !== null) {
      context.globalAlpha = shapeOpacity * alphaOf(style.fill);
      context.fillStyle = argbToCss(style.fill);
      context.fill(path, style.fillRule === "NONZERO" ? "nonzero" : "evenodd");
    }
    if (style.stroke !== null) {
      context.globalAlpha = shapeOpacity * alphaOf(style.stroke);
      context.strokeStyle = argbToCss(style.stroke);
      context.lineWidth = Number.isFinite(style.strokeWidth) && style.strokeWidth > 0 ? style.strokeWidth : 1;
      context.lineCap = style.cap === "SQUARE" ? "square" : style.cap === "BUTT" ? "butt" : "round";
      context.lineJoin = style.join === "MITER" ? "miter" : style.join === "BEVEL" ? "bevel" : "round";
      if (Number.isFinite(style.miterLimit) && style.miterLimit > 0) context.miterLimit = style.miterLimit;
      context.stroke(path);
    }
  }

  context.restore();
}

/** The alpha byte of a packed ARGB colour, as 0..1. Multiplied into `globalAlpha` — see `paintGeometry`. */
function alphaOf(argb: number): number {
  return ((argb >>> 24) & 0xff) / 255;
}

/**
 * Packed ARGB to a CSS colour, RGB only.
 *
 * The alpha is deliberately NOT spelled here: it is multiplied into `globalAlpha` by the caller, per
 * fill and per stroke, so that one colour's translucency reaches the canvas the same way it reaches
 * the SVG. Spelling it in an `rgba()` here as well would apply it twice.
 */
function argbToCss(argb: number): string {
  const r = (argb >>> 16) & 0xff;
  const g = (argb >>> 8) & 0xff;
  const b = argb & 0xff;
  return `rgb(${r} ${g} ${b})`;
}

/**
 * A drawing surface, `OffscreenCanvas` where it exists and a detached `<canvas>` where it does not.
 *
 * EXPORTED FOR `comparisonPlates.ts`, WHICH IS THE ONLY OTHER PLACE THAT PAINTS THIS GEOMETRY. The
 * fallback exists because Safari carried `createImageBitmap` for several versions before
 * `OffscreenCanvas` (`decodeToPixels.ts` names the same asymmetry), and a second copy of that
 * knowledge is a second thing to update the next time a browser moves. Same argument as
 * `paintGeometry`: one painter, one surface, one opinion.
 */
export function createCanvas(width: number, height: number): HTMLCanvasElement | OffscreenCanvas | null {
  if (typeof OffscreenCanvas !== "undefined") return new OffscreenCanvas(width, height);
  if (typeof document === "undefined") return null;
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  return canvas;
}

/** PNG bytes off either surface kind, or null when the browser refused. Exported for the same reason. */
export async function canvasToBlob(canvas: HTMLCanvasElement | OffscreenCanvas): Promise<Blob | null> {
  if ("convertToBlob" in canvas) {
    try {
      return await canvas.convertToBlob({ type: "image/png" });
    } catch {
      return null;
    }
  }
  return await new Promise<Blob | null>((resolve) => {
    canvas.toBlob((blob) => resolve(blob), "image/png");
  });
}
