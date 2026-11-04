export const DECODE_MAX_EDGE_PX = 4096;

export interface DecodedPixels {
  readonly data: Uint8ClampedArray;
  readonly width: number;
  readonly height: number;
  readonly sourceWidth: number;
  readonly sourceHeight: number;
  readonly decodeMs: number;
}

export interface DecodeRefusal {
  readonly reason: string;
}

export type DecodeOutcome = DecodedPixels | DecodeRefusal;

export function isDecoded(outcome: DecodeOutcome): outcome is DecodedPixels {
  return "data" in outcome;
}

export const TRACEABLE_IMAGE_TYPES = "JPEG, PNG, WebP, GIF, BMP, HEIC and AVIF";

export const TRACEABLE_ACCEPT = "image/*";

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

export async function decodeToPixels(
  file: Blob,
  maxEdge: number = DECODE_MAX_EDGE_PX
): Promise<DecodeOutcome> {
  if (typeof createImageBitmap === "undefined") {
    return {
      reason:
        "This browser can't trace images. Attach the photo as it is."
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
      return { reason: "That image is empty. Try another." };
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
          "This browser can't trace this photo."
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
    return {
      reason:
        `Can't read that image. Use ${TRACEABLE_IMAGE_TYPES}, or attach it as it is.`
    };
  } finally {
    full?.close();
    scaled?.close();
  }
}
