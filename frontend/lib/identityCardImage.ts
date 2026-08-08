/**
 * Making a photograph of an identity card fit to leave the machine — smaller, and stripped of
 * everything about it that is not the card.
 *
 * ── WHY THIS IS NOT AN OPTIMISATION ───────────────────────────────────────────────────────────
 *
 * A photograph straight off a handset is a 12-megapixel JPEG of three to five megabytes, and it
 * carries an EXIF block. On a card photographed in the field that block routinely holds
 * `GPSLatitude` / `GPSLongitude` — where the artisan was standing — plus the device make, model and
 * serial and the exact second. Today every one of those bytes is posted to a third-party vision
 * model along with the card. Re-drawing the image through a canvas and re-encoding it produces a
 * NEW JPEG built from pixels alone: there is no EXIF to strip because none is written. That is a
 * privacy improvement measured in what is no longer sent, and it costs zero bundle bytes.
 *
 * The second half is the web client's actual constraint. This app runs "on a laptop, sometimes in
 * an office and sometimes in a field guest house on a phone hotspot" — the connection is slow and
 * metered, not absent. Android answered "no signal" by putting the recogniser on the device; the
 * browser's honest answer to "slow signal" is to send an order of magnitude less. A 4032×3024 photo
 * scaled to a 2000px longest edge is about a fifteenth of the bytes, and the server's own ceiling
 * (`IDENTITY_OCR_MAX_IMAGE_BYTES`, 8 MB by default) stops being something a modern phone can breach
 * with one picture.
 *
 * ── WHY 2000 PIXELS AND NOT SMALLER ───────────────────────────────────────────────────────────
 *
 * The digits are the whole payload and a misread digit is the failure this feature exists to avoid,
 * so the ceiling is set where a card filling the frame still leaves each digit tens of pixels tall,
 * not at the smallest size that "usually works". Quality is 0.92 rather than the usual 0.8 for the
 * same reason: JPEG ringing lands hardest on high-contrast edges, which is exactly what a printed
 * digit is. The point of this module is to remove megabytes of camera sensor noise and metadata,
 * never to make the number harder to read.
 *
 * ── IT NEVER FAILS THE READ ───────────────────────────────────────────────────────────────────
 *
 * Every failure path returns the ORIGINAL file. A browser without `createImageBitmap`, a canvas
 * that refuses `toBlob`, an image the decoder cannot open — none of those are reasons a designer
 * should be told their card could not be read. The worst case is that the original bytes are sent,
 * which is exactly what happened before this module existed.
 */

/** The longest edge, in pixels, a card photograph is reduced to before it is sent anywhere. */
export const IDENTITY_MAX_EDGE = 2000;

/** JPEG quality for the re-encode. High on purpose — see the header. */
export const IDENTITY_JPEG_QUALITY = 0.92;

export type IdentityUploadPlan = {
  width: number;
  height: number;
  /** False when the source is already within the ceiling and only the re-encode is doing work. */
  scaled: boolean;
};

/**
 * The pixel dimensions a photograph should be sent at. PURE, so the arithmetic is tested in Node.
 *
 * Aspect ratio is preserved by scaling both edges by one factor, and the result is rounded rather
 * than floored so a 4032×3024 image lands on 2000×1500 and not 2000×1499 — a one-pixel difference
 * nobody would see, but two rounding rules in one function is how an image gets stretched by half a
 * percent and a recogniser gets a card that is very slightly not rectangular.
 *
 * A degenerate size (zero, negative, non-finite — a decoder that failed halfway) returns itself
 * unscaled, so the caller's guard, not this function, decides what to do about it.
 */
export function identityUploadPlan(width: number, height: number, maxEdge = IDENTITY_MAX_EDGE): IdentityUploadPlan {
  const longest = Math.max(width, height);
  if (!Number.isFinite(longest) || longest <= 0) return { width, height, scaled: false };
  if (longest <= maxEdge) return { width, height, scaled: false };
  const factor = maxEdge / longest;
  return { width: Math.round(width * factor), height: Math.round(height * factor), scaled: true };
}

/**
 * The file that should be sent to the reader in place of `file`.
 *
 * Always a re-encode when it succeeds, even for an image already under the ceiling, because the
 * metadata is half the reason this exists and a small photograph carries the same GPS tag as a
 * large one.
 *
 * The name is deliberately generic and carries no part of the original: the original name is often
 * `IMG_20260809_141233.jpg`, which states when the card was photographed, and it travels in the
 * multipart body to a third party for no reason at all.
 */
export async function prepareIdentityPhotograph(file: File): Promise<File> {
  if (typeof createImageBitmap !== "function" || typeof document === "undefined") return file;

  let bitmap: ImageBitmap | null = null;
  try {
    bitmap = await createImageBitmap(file, { imageOrientation: "from-image" });
    const plan = identityUploadPlan(bitmap.width, bitmap.height);
    if (!Number.isFinite(plan.width) || plan.width <= 0 || plan.height <= 0) return file;

    const canvas = document.createElement("canvas");
    canvas.width = plan.width;
    canvas.height = plan.height;
    const context = canvas.getContext("2d");
    if (!context) return file;
    // White underneath, so a PNG or WebP with transparency does not re-encode to black behind the
    // digits — a card scan saved with an alpha channel is uncommon but it is unreadable when it
    // happens, and one fill is cheaper than finding that out from a designer.
    context.fillStyle = "#ffffff";
    context.fillRect(0, 0, plan.width, plan.height);
    context.drawImage(bitmap, 0, 0, plan.width, plan.height);

    const blob = await new Promise<Blob | null>((resolve) => {
      canvas.toBlob(resolve, "image/jpeg", IDENTITY_JPEG_QUALITY);
    });
    if (!blob) return file;
    // Sending MORE bytes than the original would be a pure loss — a small PNG of a card can
    // re-encode larger than it started. The original is then the better thing to send, and it is
    // already what the caller was going to send.
    if (blob.size >= file.size && !plan.scaled) return file;
    return new File([blob], "identity-card.jpg", { type: "image/jpeg" });
  } catch {
    // See the header: no failure here is a reason to tell a designer the card could not be read.
    return file;
  } finally {
    bitmap?.close();
  }
}
