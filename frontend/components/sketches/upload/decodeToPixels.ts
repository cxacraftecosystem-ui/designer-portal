/**
 * A chosen file, decoded to the RGBA the tracing engine takes.
 *
 * WHY THE DECODER IS HERE AND NOT IN `lib/trace/`. `traceClient.ts`'s header refuses the job in as
 * many words: "No React, no component, no canvas, no `File`. Decoding a photograph into pixels is the
 * caller's job — `lib/imageQuality.ts` and `lib/sketchRectify.ts` already own that in this repository,
 * and a second decoder here would be a second opinion about EXIF orientation." This file is the
 * caller doing its job.
 *
 * AND WHY IT IS NOT A SECOND OPINION EITHER. `lib/imageQuality.measureImageFile` decodes with a bare
 * `createImageBitmap(file)` and reads the pixels back through an `OffscreenCanvas` with a detached
 * `<canvas>` fallback "(Safari carried `createImageBitmap` for several versions before
 * `OffscreenCanvas`)". This file makes the IDENTICAL call with the IDENTICAL absence of options, so
 * the orientation a traced sketch is drawn at is the orientation the quality checks measured — there
 * is one opinion and both features hold it. The helper that does the readback is private to
 * `imageQuality.ts` and that file is not this unit's to edit, so the twenty lines are repeated rather
 * than shared; if the two ever need to differ, that is a bug in one of them, not a feature.
 *
 * WHY A CAP AT ALL, WHEN THE ENGINE ALREADY HAS ONE. `preprocess.workingLongEdge` caps the resolution
 * the trace RUNS at, and the engine downsamples to it internally — but that happens after the pixels
 * have crossed into the worker. A 12 MP phone photograph is 48 MB of RGBA, and the transfer, the
 * clone {@link https://developer.mozilla.org/en-US/docs/Web/API/Web_Workers_API | transferableFrom}
 * makes to keep the caller's copy intact, and the decode buffer itself are three copies of it on a
 * handset with 2 GB of RAM. Decoding to a bounded edge first costs nothing in output quality — the
 * trace was never going to run above `workingLongEdge` anyway — and is the difference between a slow
 * tab and a killed one.
 */

/**
 * The longest edge a decode is allowed to produce.
 *
 * 4096 rather than a smaller number because it is exactly the ceiling `traceParamTable.ts` puts on
 * "Trace resolution": decoding below that would silently cap a slider the designer can still see at
 * its top end, which is the kind of disagreement between two limits that nobody finds for a year.
 * Raising the slider's ceiling means raising this in the same edit.
 */
export const DECODE_MAX_EDGE_PX = 4096;

/** Pixels in `ImageData` byte order, plus the size the decode actually produced. */
export interface DecodedPixels {
  readonly data: Uint8ClampedArray;
  readonly width: number;
  readonly height: number;
  /** The file's own pixel size, before any capping. Stated on screen when the two differ. */
  readonly sourceWidth: number;
  readonly sourceHeight: number;
  readonly decodeMs: number;
}

/** Why a decode did not happen, in a sentence written to be shown to a designer. */
export interface DecodeRefusal {
  readonly reason: string;
}

export type DecodeOutcome = DecodedPixels | DecodeRefusal;

/** Narrowing helper, so a caller reads `if (isDecoded(outcome))` rather than `"data" in outcome`. */
export function isDecoded(outcome: DecodeOutcome): outcome is DecodedPixels {
  return "data" in outcome;
}

/**
 * The image kinds worth offering the file picker.
 *
 * SVG IS ABSENT AND THAT IS NOT AN OVERSIGHT. `lib/imageQuality.isMeasurableImage` excludes
 * `image/svg+xml` from measurement, and the same exclusion is correct here for a stronger reason: an
 * SVG is already vector art, so tracing one is a round trip that can only lose — rasterise, threshold,
 * re-fit curves — and it would arrive at the panel looking like a feature rather than like the
 * mistake it is. A designer who has an SVG already should attach it, which the ordinary file picker
 * under this panel does.
 *
 * `image/*` is the accept attribute rather than this list, because a phone camera roll offers formats
 * (HEIC on iOS, AVIF on newer Android) that a browser may or may not decode, and a picker that hides
 * a file the browser could in fact have opened is worse than a decode that fails with a sentence.
 * This list is what the panel NAMES to the designer; the browser decides what it can actually read.
 */
export const TRACEABLE_IMAGE_TYPES = "JPEG, PNG, WebP, GIF, BMP, HEIC and AVIF";

/** The accept attribute, kept beside the sentence above so the two cannot drift apart. */
export const TRACEABLE_ACCEPT = "image/*";

/**
 * Draw a bitmap at the given size and read the pixels back.
 *
 * `OffscreenCanvas` where it exists, a detached `<canvas>` where it does not. The fallback element is
 * never attached to the document, so it forces no layout and paints nothing.
 */
function readPixels(bitmap: ImageBitmap, width: number, height: number): Uint8ClampedArray | null {
  if (typeof OffscreenCanvas !== "undefined") {
    const canvas = new OffscreenCanvas(width, height);
    const context = canvas.getContext("2d", { willReadFrequently: true });
    if (!context) return null;
    context.drawImage(bitmap, 0, 0, width, height);
    return context.getImageData(0, 0, width, height).data;
  }
  if (typeof document === "undefined") return null;
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext("2d", { willReadFrequently: true });
  if (!context) return null;
  context.drawImage(bitmap, 0, 0, width, height);
  return context.getImageData(0, 0, width, height).data;
}

/**
 * @returns the working size for a source of this size — the source itself when it is already inside
 *   the cap, so the common case of a scanned A4 at 2480x3508 is not resampled for nothing.
 */
export function workingSizeFor(
  width: number,
  height: number,
  maxEdge: number = DECODE_MAX_EDGE_PX
): { width: number; height: number } {
  const scale = Math.min(1, maxEdge / Math.max(width, height));
  if (scale >= 1) return { width, height };
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale))
  };
}

/**
 * Decode `file` to bounded RGBA, or explain why not.
 *
 * FAILURE IS A SENTENCE, NEVER SILENCE, and this is the one place where this file deliberately differs
 * from `measureImageFile`, whose own header says "Failure is always silent and always null." That is
 * right for a background quality check nobody asked for and wrong here: the designer pressed a button
 * meaning "trace this", so a refusal has to say what happened and what to do instead. The photograph
 * itself is fine and is already attached either way.
 *
 * The full-size bitmap is `close()`d the instant it has been read, and the resize is a second
 * `createImageBitmap` so it happens off the main thread in every engine that has one — holding a
 * 4000x3000 decode and its resized copy at once is how a cheap phone kills the tab.
 */
export async function decodeToPixels(
  file: Blob,
  maxEdge: number = DECODE_MAX_EDGE_PX
): Promise<DecodeOutcome> {
  if (typeof createImageBitmap === "undefined") {
    return {
      reason:
        "This browser cannot decode images in the page, so a drawing cannot be traced here. " +
        "The photograph itself is unaffected — attach it as it is, or trace it on a handset."
    };
  }

  const startedAt = typeof performance !== "undefined" ? performance.now() : Date.now();
  let full: ImageBitmap | null = null;
  let scaled: ImageBitmap | null = null;
  try {
    full = await createImageBitmap(file);
    const sourceWidth = full.width;
    const sourceHeight = full.height;
    if (sourceWidth < 1 || sourceHeight < 1) {
      return { reason: "That file decoded to an empty image. Try another photograph." };
    }

    const working = workingSizeFor(sourceWidth, sourceHeight, maxEdge);
    scaled =
      working.width === sourceWidth && working.height === sourceHeight
        ? null
        : await createImageBitmap(full, { resizeWidth: working.width, resizeHeight: working.height });

    const data = readPixels(scaled ?? full, working.width, working.height);
    if (data === null) {
      return {
        reason:
          "This browser would not give the page a drawing surface to read the photograph back from, " +
          "so it cannot be traced here."
      };
    }

    const now = typeof performance !== "undefined" ? performance.now() : Date.now();
    return {
      data,
      width: working.width,
      height: working.height,
      sourceWidth,
      sourceHeight,
      decodeMs: now - startedAt
    };
  } catch {
    // A format this browser will not decode — HEIC on a desktop Chrome, most often — or a truncated
    // file from an interrupted transfer. The two want the same answer from the designer's side.
    return {
      reason:
        `This browser could not read that image. It reads ${TRACEABLE_IMAGE_TYPES} where the ` +
        "platform supports them; a photograph in another format can still be attached as it is."
    };
  } finally {
    full?.close();
    scaled?.close();
  }
}
