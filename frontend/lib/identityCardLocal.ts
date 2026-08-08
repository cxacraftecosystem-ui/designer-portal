/**
 * Reading an identity card WITHOUT sending it anywhere, when the browser can do it — and admitting
 * plainly when it cannot.
 *
 * THE FEATURE DETECTION IS THE DESIGN, exactly as it is in `WorkshopCodeScanner`, and for the same
 * reason: the machines this application runs on are whatever was cheapest that year, so "the reader
 * is missing" is a NORMAL state, not an error. This module is the browser half only — it produces
 * TEXT. Every decision about whether a digit string may be offered to a designer lives in the pure
 * `lib/identityCardText.ts`, so the rule can be tested in Node.
 *
 * ── WHAT WAS MEASURED, BECAUSE THIS ONE IS NOT AN EDGE CASE ───────────────────────────────────
 *
 * `TextDetector` was probed on this machine on 2026-08-09, not read off a specification:
 *
 * | browser | `TextDetector` | `BarcodeDetector` |
 * |---|---|---|
 * | Chrome 151, headed | absent | absent |
 * | Chrome 151, headed, `--enable-experimental-web-platform-features` | absent | absent |
 * | Edge 151, headed (and headless) | absent | absent |
 * | Chromium 151 (Playwright), incl. `--enable-blink-features=ShapeDetection` | absent | absent |
 *
 * Chrome's own capabilities article says barcode detection is available "on macOS, ChromeOS, and
 * Android" and that face and text detection are "available behind a flag", text detection having
 * been moved out of the standard "because it is not considered stable enough across either
 * computing platforms or character sets". On the WINDOWS LAPTOP this client is used on, the whole
 * Shape Detection family is simply absent — which is also true of the `BarcodeDetector` the QR
 * scanner is built on, and is exactly why that component treats its absence as ordinary.
 *
 * So this path is expected to be taken rarely, and the server reader — not this — is the route a
 * designer on a laptop actually gets. It is here anyway because it costs ZERO BYTES, it is the only
 * way a card is read with the photograph never leaving the machine, and it is the only reader that
 * works with no connection at all. `docs/DECISION-identity-card-ocr-on-web.md` records why nothing
 * was bundled to make it universal, with the numbers.
 *
 * ── HAVING THE CONSTRUCTOR IS NOT HAVING A RECOGNISER ─────────────────────────────────────────
 *
 * `BarcodeDetector` has `getSupportedFormats()`, so `WorkshopCodeScanner` can ask whether a
 * detector that exists can do the job. `TextDetector` has no such method — a build can expose the
 * constructor and then throw `NotSupportedError` from `detect()` on the platform underneath. The
 * honest equivalent is therefore to RUN one, once, on an 8×8 blank bitmap, and cache the answer for
 * the tab. A blank bitmap is the cheapest possible call and its result is unambiguous: an empty
 * array means a working detector that saw no text, and an exception means no detector at all.
 *
 * ── THE TYPES ARE LOCAL, DELIBERATELY ─────────────────────────────────────────────────────────
 *
 * Declared here rather than in a global `.d.ts`, for the reason `WorkshopCodeScanner` gives about
 * `BarcodeDetector`: a global declaration asserts to every other file that the API is always
 * present, which is the exact belief this module exists not to hold.
 */

/** The slice of the Shape Detection API this uses. See the header for why it is not global. */
type TextDetectorLike = {
  detect(source: CanvasImageSource | ImageBitmap | Blob): Promise<{ rawValue: string }[]>;
};
type TextDetectorConstructor = { new (): TextDetectorLike };

function constructorIfAny(): TextDetectorConstructor | undefined {
  return (globalThis as unknown as { TextDetector?: TextDetectorConstructor }).TextDetector;
}

/**
 * Resolved once per tab and then reused, the same shape as `serverOffersRoute`'s probe cache.
 *
 * A promise rather than a boolean, so two controls mounting in the same tick (the artisan form has
 * an Aadhaar box AND a Pehchan box) run one probe between them rather than two.
 */
let probe: Promise<boolean> | null = null;

/** Whether this browser can recognise text in an image at all. Never guessed at; see the header. */
export function browserCanReadCards(): Promise<boolean> {
  if (probe) return probe;
  probe = (async () => {
    const Detector = constructorIfAny();
    if (!Detector || typeof createImageBitmap !== "function" || typeof ImageData !== "function") {
      return false;
    }
    let bitmap: ImageBitmap | null = null;
    try {
      bitmap = await createImageBitmap(new ImageData(8, 8));
      await new Detector().detect(bitmap);
      return true;
    } catch {
      // A constructor that exists and throws is a browser without a recogniser, not a broken card.
      return false;
    } finally {
      bitmap?.close();
    }
  })();
  return probe;
}

/** Why a local read produced nothing, so the surface can name the right next action. */
export type LocalReadFailure =
  /** This browser has no recogniser. The surface should not have offered the local route at all. */
  | { reason: "unsupported" }
  /** The browser could not decode the FILE — almost always an iPhone HEIC, not an unreadable card. */
  | { reason: "undecodable"; filename: string }
  /** The recogniser ran and threw. Rare, and not something a better photograph fixes. */
  | { reason: "failed"; message: string };

export type LocalReadOutcome = { ok: true; text: string } | ({ ok: false } & LocalReadFailure);

/**
 * Recognise the text in a photograph, entirely inside this tab.
 *
 * Returns raw text and nothing else — no parsing, no filtering, no opinion about what a number is.
 * The caller hands it to `identityCandidatesFromText`.
 *
 * THE BITMAP IS CLOSED IN A `finally`, the lesson `WorkshopCodeScanner.readFromFile` records: a
 * twelve-megapixel photograph decodes to roughly 48 MB, and leaving a handful of those to the
 * garbage collector on a small machine is how a reader starts failing at the fourth card with an
 * error about the picture rather than about memory.
 *
 * A FILE THAT CANNOT BE DECODED IS NOT REPORTED AS A CARD THAT CANNOT BE READ. The commonest cause
 * is an iPhone HEIC, which most browsers refuse outright, and telling somebody their card is
 * unreadable when the truth is that this browser cannot open that FORMAT sends them back to
 * photograph a card that was fine.
 *
 * NOTHING IS KEPT. The bitmap is closed, the text is returned to the caller and this module holds
 * no reference to either. Nothing is logged on any path — recognised text off an identity card
 * contains the number by construction.
 */
export async function readCardTextInBrowser(file: File): Promise<LocalReadOutcome> {
  const Detector = constructorIfAny();
  if (!Detector) return { ok: false, reason: "unsupported" };

  let bitmap: ImageBitmap | null = null;
  try {
    try {
      // `from-image` explicitly: a card photographed in portrait carries an EXIF orientation, and a
      // recogniser handed the unrotated pixels reads a sideways card as nothing at all.
      bitmap = await createImageBitmap(file, { imageOrientation: "from-image" });
    } catch {
      return { ok: false, reason: "undecodable", filename: file.name };
    }
    const found = await new Detector().detect(bitmap);
    // Joined with a NEWLINE, never a space: a space is a separator inside a printed number, so
    // joining two detected blocks with one would fuse the last number of the first block to the
    // first number of the second and produce a token that was never on the card. The same reason
    // `identity_ocr.result_from_reply` joins its fields with a newline.
    return { ok: true, text: found.map((block) => block.rawValue).join("\n") };
  } catch (error) {
    return { ok: false, reason: "failed", message: error instanceof Error ? error.message : "unknown" };
  } finally {
    bitmap?.close();
  }
}
