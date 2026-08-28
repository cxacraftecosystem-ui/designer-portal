import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  AUTO_FILLABLE_FLAGS,
  declaredMinItems,
  faultRefuses,
  galleryFloorSentence,
  gatePhotograph,
  gateRefusalSentence,
  gateScopeSentence,
  galleryProgress,
  mediaQualityFlagRows,
  type CapturedFinding,
  type GateFault
} from "@/components/media/photoGate";
import {
  BLUR_VARIANCE_FLOOR,
  MIN_CONTRAST_STDDEV,
  MIN_LONG_EDGE_PX,
  type ImageMeasurement
} from "@/lib/imageQuality";

/**
 * THE CAPTURE GATE: WHAT IT REFUSES, WHAT IT LETS THROUGH, AND WHAT IT IS FORBIDDEN TO CLAIM.
 *
 * ── WHY A REFUSAL NEEDS A HARDER TEST THAN A WARNING DID ──────────────────────────────────────────
 *
 * `lib/imageQuality.ts` has always MEASURED these things, and `e2e/image-quality.spec.ts` calibrates
 * the numbers. What changed on 2026-08-28 is the consequence: a finding used to be a sentence beside
 * a photograph that was already uploading, and it is now a door. That moves the cost of every false
 * positive from "a designer reads a warning they disagree with" to "a photograph the designer wanted
 * is not in the archive and they are two hundred kilometres away".
 *
 * So this file tests the two things a calibration spec cannot:
 *
 *   1. THE GATE FAILS OPEN. Every path that produces no measurement, or an unreliable one, ADMITS.
 *      The low-contrast arm is the one that matters most and is the one nobody would think to check:
 *      a perfectly sharp photograph of a plain-dyed cloth scores BELOW the blur floor, and the only
 *      thing standing between it and a refusal is `MIN_CONTRAST_STDDEV`.
 *   2. THE GATE DOES NOT OVERCLAIM. Nothing it prints may suggest a fault this product does not
 *      measure was checked, and nothing may suggest an enforcement that does not exist.
 *
 * ── AND WHY HALF OF IT IS A SOURCE READ ───────────────────────────────────────────────────────────
 *
 * The judgements are pure and are tested by calling them. Their call site is a React component and
 * this repository has no React renderer in its devDependencies — Playwright is the whole of it — so
 * the wiring is read out of the source, exactly as `photo-intake-cap-unit.spec.ts` and
 * `existing-media-count-unit.spec.ts` read theirs. What that cannot prove is that a browser paints
 * the sentence; what it does prove is that the gate runs on the side of `setPending` where no byte
 * has moved, which is the only property of the wiring that the feature depends on.
 */

const ROOT = join(__dirname, "..");
const FIELD_INPUT = readFileSync(join(ROOT, "components/designworkshop/FieldInput.tsx"), "utf8");
const KOTLIN_QUALITY = join(ROOT, "..", "android/app/src/main/java/com/designprototype/workshop/data/ImageQuality.kt");

/** A measurement with nothing wrong with it: big, sharp, and contrasty enough to be judged. */
function goodPhotograph(overrides: Partial<ImageMeasurement> = {}): ImageMeasurement {
  return {
    width: 4000,
    height: 3000,
    blurScore: 900,
    contrast: 60,
    perceptualHash: "0".repeat(16),
    elapsedMs: 4,
    ...overrides
  };
}

test.describe("what the gate refuses", () => {
  test("a shaky photograph is refused, and the refusal carries the reading AND the floor", () => {
    const verdict = gatePhotograph({ measurement: goodPhotograph({ blurScore: 42 }) });
    expect(verdict.admitted).toBe(false);
    expect(verdict.faults.map((fault) => fault.kind)).toEqual(["BLUR"]);
    // The whole argument for a refusal a designer will accept rather than route around: the number
    // they can check, the number it was checked against, and what to do next. A bare verdict is
    // indistinguishable from the app being wrong.
    expect(verdict.faults[0].message).toContain("42");
    expect(verdict.faults[0].message).toContain(String(BLUR_VARIANCE_FLOOR));
    expect(verdict.faults[0].message.toLowerCase()).toContain("again");
  });

  test("a photograph too small for a report plate is refused, and names both sizes", () => {
    const verdict = gatePhotograph({ measurement: goodPhotograph({ width: 1024, height: 768 }) });
    expect(verdict.admitted).toBe(false);
    expect(verdict.faults.map((fault) => fault.kind)).toEqual(["LOW_RESOLUTION"]);
    expect(verdict.faults[0].message).toContain("1024x768");
    expect(verdict.faults[0].message).toContain(String(MIN_LONG_EDGE_PX));
  });

  test("the identical file twice is refused, and names the copy already attached", () => {
    const verdict = gatePhotograph({
      measurement: goodPhotograph(),
      checksum: "sha256:abc",
      attached: [{ label: "IMG_0007.jpg", checksum: "sha256:abc" }]
    });
    expect(verdict.admitted).toBe(false);
    expect(verdict.faults[0].kind).toBe("EXACT_DUPLICATE");
    expect(verdict.faults[0].message).toContain("IMG_0007.jpg");
    // Refusing an exact duplicate is the one refusal that costs the designer nothing, and the
    // sentence has to say so or it reads like lost work.
    expect(verdict.faults[0].message.toLowerCase()).toContain("nothing is lost");
  });

  test("two faults at once are both reported, and the refusal is not doubled", () => {
    const verdict = gatePhotograph({ measurement: goodPhotograph({ blurScore: 10, width: 800, height: 600 }) });
    expect(verdict.admitted).toBe(false);
    expect(verdict.faults.map((fault) => fault.kind)).toEqual(["BLUR", "LOW_RESOLUTION"]);
  });
});

test.describe("what the gate must NEVER refuse — the fail-open half", () => {
  /**
   * THE FALSE POSITIVE THAT WOULD COST THE FEATURE, AND THE ONE CONSTANT STANDING IN ITS WAY.
   *
   * Variance of the Laplacian scales with the square of the subject's own contrast, so a PERFECTLY
   * sharp photograph of a flat subject scores like a blurred one — `e2e/image-quality.spec.ts` pins a
   * sharp flat field below the blur floor, and Android's `ImageQuality.kt` records the same subject
   * at 58.98 against a floor of 60. Undyed cotton on a white sheet, a smooth metal tool and a
   * plain-dyed length of cloth are all ordinary motif documentation and all flat.
   *
   * As a WARNING that false positive cost a sentence nobody read. As a REFUSAL it would turn away a
   * correct photograph of a motif, which is the failure that teaches a designer to work around the
   * gate. `isBlurred`'s contrast guard is the only thing preventing it, and this test is what says
   * so out loud on the refusal's side of the line.
   */
  test("a sharp photograph of a FLAT subject is admitted, though it scores below the blur floor", () => {
    const flat = goodPhotograph({ blurScore: 58, contrast: MIN_CONTRAST_STDDEV - 3 });
    expect(flat.blurScore).toBeLessThan(BLUR_VARIANCE_FLOOR);
    const verdict = gatePhotograph({ measurement: flat });
    expect(verdict.admitted).toBe(true);
    expect(verdict.faults).toEqual([]);
  });

  test("an unknown checksum is 'unknown' and never 'unique' — it raises nothing either way", () => {
    // `computeChecksum` answers null above a size ceiling and where WebCrypto is absent. Neither is
    // evidence of anything, so neither may produce a finding.
    const verdict = gatePhotograph({
      measurement: goodPhotograph(),
      checksum: null,
      attached: [{ label: "IMG_0007.jpg", checksum: "sha256:abc" }]
    });
    expect(verdict.admitted).toBe(true);
    expect(verdict.faults).toEqual([]);
  });

  test("an attached row with no checksum of its own cannot make anything a duplicate", () => {
    const verdict = gatePhotograph({
      measurement: goodPhotograph(),
      checksum: "sha256:abc",
      attached: [{ label: "older.jpg", checksum: null }, { label: "older2.jpg" }]
    });
    expect(verdict.admitted).toBe(true);
  });

  test("a NEAR duplicate is not a fault this gate raises at all — only the exact one closes the door", () => {
    /*
      `imageQuality.ts`'s own calibration says two exposures of one object seconds apart land within
      the near-duplicate threshold, and that is exactly how twenty-five motifs on one length of cloth
      get photographed. So the perceptual-hash match stays where it was — a warning on the capture
      card — and this gate never sees it. `faultRefuses` is the one place that decision lives.
    */
    const near: GateFault = { kind: "NEAR_DUPLICATE", flag: "DUPLICATE", severity: "LOW", message: "x" };
    expect(faultRefuses(near)).toBe(false);
    for (const kind of ["BLUR", "LOW_RESOLUTION", "EXACT_DUPLICATE"] as const) {
      expect(faultRefuses({ kind, flag: "BLUR", severity: "MEDIUM", message: "x" })).toBe(true);
    }
  });

  test("the wiring admits a photograph nothing could measure, rather than refusing it", () => {
    // A source read, because the branch is in a React component. `measureImageFile` answers null for
    // a corrupt file, a codec the browser lacks (HEIC), or a GPU that refused the bitmap — and the
    // call site must let that file through. `release(file, null)` is the admission.
    expect(FIELD_INPUT).toContain("const measurement = await measureImageFile(file);");
    expect(FIELD_INPUT).toMatch(/if \(!measurement\) \{\s*\n\s*release\(file, null\);/);
    expect(FIELD_INPUT).toContain("if (!verdict || verdict.admitted) {");
    // And a file that is not a decodable image is never sent through the gate in the first place.
    expect(FIELD_INPUT).toContain("const photographs = fresh.filter(isMeasurableImage);");
    expect(FIELD_INPUT).toContain("if (rest.length) setPending((current) => [...current, ...rest]);");
  });
});

test.describe("the gate runs BEFORE a byte moves", () => {
  /**
   * THE ONE PROPERTY OF THE WIRING THE WHOLE FEATURE DEPENDS ON.
   *
   * `MediaCaptureField` starts streaming every file in its `files` prop to object storage before
   * anything else it does — its own quality effect says so in as many words. So a photograph that
   * reaches `setPending` is a photograph that is already on its way to the server, and a check after
   * that point reports on something it cannot prevent. The gate is therefore only a gate for as long
   * as `acceptFiles` puts photographs into the SCREENING list and not the pending one.
   */
  test("acceptFiles hands photographs to the screening list, never straight to the capture card", () => {
    expect(FIELD_INPUT).toContain("setScreening((current) => [...current, ...photographs]);");
    expect(FIELD_INPUT).toContain("void screen(photographs);");
    // The direct write this replaced. If it comes back, every picked photograph uploads unjudged.
    expect(FIELD_INPUT).not.toContain("setPending([...next.slice(0, pending.length), ...kept]);");
    // Both of `acceptFiles`' growing arms go through the same door.
    expect(FIELD_INPUT).toContain("admit(next.slice(0, pending.length), added);");
    expect(FIELD_INPUT).toContain("admit(next.slice(0, pending.length), kept);");
  });

  test("files under measurement are counted against the ceiling, in both directions", () => {
    // A second pick arriving mid-measurement would otherwise compute its room against a list that
    // does not yet hold the first batch, and walk the gallery past a cap `coerce_value` REFUSES
    // rather than trims.
    expect(FIELD_INPUT).toContain(
      "const room = cap === null ? null : Math.max(0, cap - ids.length - pending.length - screening.length);"
    );
    expect(FIELD_INPUT).toContain("const accounted = ids.length + pending.length + screening.length;");
  });

  test("the screening list has the hoisted store's lifetime, not the panel's", () => {
    // A collection row unmounts its whole panel when collapsed. A file lost from the screening list
    // was never uploaded and never refused — it simply is not there any more, with no object to
    // clean up and no sentence anywhere.
    expect(FIELD_INPUT).toContain('usePendingMedia(mediaPlace, "screening")');
    expect(FIELD_INPUT).toContain("function usePendingMedia(place: StageMediaPlace, slot = \"\")");
  });
});

test.describe("the floor, and the bar counted against it", () => {
  test("minItems is read, never assumed, and an absent floor is no floor", () => {
    expect(declaredMinItems({ key: "motifPhotos", minItems: 25 })).toBe(25);
    expect(declaredMinItems({ key: "clusterPhotos" })).toBeNull();
    // Android's `FieldDto` defaults an absent integer to 0 for the same absence, so a registry that
    // ever reached this client through that shape must read as "not declared" and not as a floor of
    // zero — the same `> 0` rule `declaredMaxItems` is under.
    expect(declaredMinItems({ key: "x", minItems: 0 })).toBeNull();
    expect(declaredMinItems({ key: "x", minItems: "25" })).toBeNull();
  });

  test("the component reads the floor off the field and hard-codes no number", () => {
    expect(FIELD_INPUT).toContain("const floor = multiple ? declaredMinItems(field) : null;");
    expect(FIELD_INPUT).toContain("floor={floor}");
    // The whole point of reading it: 25 is the registry's to change, and this client has already
    // learned once what printing a figure it did not read costs.
    expect(FIELD_INPUT).not.toMatch(/floor\s*[=:]\s*25\b/);
  });

  test("the bar counts what is IN the gallery and never what is on its way", () => {
    const progress = galleryProgress({
      counts: { held: 23, onDevice: 0, uploading: 2, screening: 0 },
      floor: 25
    });
    // The failure this prevents: "25 of 25" drawn over a save that would post twenty-three.
    expect(progress.readout).toBe("23 of 25");
    expect(progress.held).toBe(23);
    expect(progress.complete).toBe(false);
    expect(progress.words).toContain("2 more are uploading");
    expect(progress.words).toContain("not counted yet");
  });

  test("the call site feeds it the attached ids and not the pending list", () => {
    expect(FIELD_INPUT).toContain("held: ids.length,");
    expect(FIELD_INPUT).toContain("uploading: pending.length,");
    expect(FIELD_INPUT).toContain("screening: screening.length");
    expect(FIELD_INPUT).toContain("onDevice: ids.filter(isLocalMediaRef).length,");
  });

  test("an empty gallery states the demand rather than saying nothing", () => {
    const progress = galleryProgress({ counts: { held: 0, onDevice: 0, uploading: 0, screening: 0 }, floor: 25 });
    expect(progress.percent).toBe(0);
    expect(progress.words).toContain("None of the 25");
    // The sentence a designer needs before the twentieth photograph, not after it.
    expect(progress.readout).toBe("0 of 25");
  });

  test("a full gallery says so in words, not only by being full", () => {
    const progress = galleryProgress({ counts: { held: 25, onDevice: 0, uploading: 0, screening: 0 }, floor: 25 });
    expect(progress.complete).toBe(true);
    expect(progress.percent).toBe(100);
    expect(progress.words).toContain("All 25 photographs are attached.");
  });

  test("percent is clamped, so a gallery over its floor cannot draw past the track", () => {
    const over = galleryProgress({ counts: { held: 30, onDevice: 0, uploading: 0, screening: 0 }, floor: 25 });
    expect(over.percent).toBe(100);
    // ...and the readout still tells the truth about what is held.
    expect(over.readout).toBe("30 of 25");
  });

  test("photographs that exist only in this browser are counted AND named", () => {
    const progress = galleryProgress({ counts: { held: 25, onDevice: 11, uploading: 0, screening: 0 }, floor: 25 });
    // Counted: the designer took them, they are real, and the sync pass carries them.
    expect(progress.complete).toBe(true);
    // Named: "25 of 25" in a courtyard with no signal must not hide that eleven are one cleared
    // cache from gone.
    expect(progress.words).toContain("11 of the 25");
    expect(progress.words).toContain("on this device only");
  });

  test("photographs still being measured are named as their own state", () => {
    const progress = galleryProgress({ counts: { held: 4, onDevice: 0, uploading: 0, screening: 3 }, floor: 25 });
    expect(progress.words).toContain("3 are being checked before they upload");
  });
});

test.describe("nothing on screen claims more than is true", () => {
  test("the standing sentence promises no submission block, because there is none", () => {
    const sentence = galleryFloorSentence({ floor: 25, label: "Traditional motif photographs" });
    expect(sentence).toContain("All 25 are required");
    // The half that keeps fieldwork safe, and it is true: the floor lives in `stage_completeness`
    // and in no validator, so no save path can refuse a partial gallery.
    expect(sentence).toContain("still saves with fewer");
    expect(sentence.toLowerCase()).toContain("scored incomplete");
    /*
      `PATCH /design-workshops/{id}` writes status SUBMITTED with an enum check and no completeness
      test anywhere, so "you cannot submit until 25" would be this client inventing an enforcement
      that does not exist. And `lib/submissionReadiness.ts` does not read `minItems` yet, so naming
      this browser's readiness screen would be a second false claim on the same line.
    */
    expect(sentence.toLowerCase()).not.toContain("submit");
    expect(sentence.toLowerCase()).not.toContain("readiness");
  });

  test("the standing sentence distinguishes attached from saved", () => {
    const sentence = galleryFloorSentence({ floor: 25, label: "Traditional motif photographs" });
    expect(sentence).toContain("Attached is not saved");
  });

  test("the scope sentence names the faults that are NOT measured, and claims none of them", () => {
    const sentence = gateScopeSentence().toLowerCase();
    // Three tokens exist in stage 21's MEDIA_QUALITY_FLAG enum that no code in this product
    // computes. A designer who reads "checked" over a screen that has just refused two soft
    // photographs will otherwise believe a dark one was judged too.
    expect(sentence).toContain("exposure and subject are not checked");
    expect(sentence).toContain("focus");
    expect(sentence).toContain("resolution");
    // No numbers: the floors are client constants and a fixed sentence quoting one goes stale
    // silently. Every refusal prints its own reading and its own floor instead.
    expect(sentence).not.toMatch(/\d/);
  });

  test("the refusal names every file and every reason, not a count", () => {
    const sentence = gateRefusalSentence([
      { name: "IMG_1.jpg", faults: [{ kind: "BLUR", flag: "BLUR", severity: "MEDIUM", message: "the sharpness reading was 42 against a floor of 60." }] },
      { name: "IMG_2.jpg", faults: [{ kind: "LOW_RESOLUTION", flag: "LOW_RESOLUTION", severity: "MEDIUM", message: "it is 800x600." }] }
    ]);
    expect(sentence).toContain("IMG_1.jpg");
    expect(sentence).toContain("IMG_2.jpg");
    expect(sentence).toContain("42");
    expect(sentence).toContain("800x600");
    // "2 photographs were refused" tells a designer holding twenty-five nothing about which two.
    expect(sentence).toContain("2 photographs were not uploaded");
    expect(sentence).toContain("Nothing was sent");
  });

  test("nothing turned away leaves no sentence at all", () => {
    expect(gateRefusalSentence([])).toBeNull();
  });
});

test.describe("the write path into stage 21", () => {
  function finding(overrides: Partial<CapturedFinding> = {}): CapturedFinding {
    return {
      mediaId: "media-1",
      fileName: "IMG_1.jpg",
      flag: "DUPLICATE",
      severity: "LOW",
      note: "looks like the same shot",
      raisedAt: "2026-08-28T10:00:00.000Z",
      ...overrides
    };
  }

  test("only the flags this product MEASURES may ever be filled in by a machine", () => {
    expect([...AUTO_FILLABLE_FLAGS]).toEqual(["BLUR", "LOW_RESOLUTION", "DUPLICATE"]);
    // MISSING_VIEW is measured but is about a ROW's named view slots rather than about one file, so
    // it has no `mediaId` to be recorded against. The three the enum carries and nothing computes —
    // OVEREXPOSED, UNDEREXPOSED, WRONG_SUBJECT — stay hand-entered, and a row claiming otherwise
    // would put the app's guess in a column an officer reads as an observation.
    const rows = mediaQualityFlagRows([
      finding({ flag: "MISSING_VIEW" }),
      finding({ flag: "OVEREXPOSED" as never }),
      finding()
    ]);
    expect(rows).toHaveLength(1);
    expect(rows[0].flag).toBe("DUPLICATE");
  });

  test("an auto-filled row says it was auto-filled, and names the file in its note", () => {
    const rows = mediaQualityFlagRows([finding()]);
    expect(rows[0]).toEqual({
      mediaId: "media-1",
      flag: "DUPLICATE",
      severity: "LOW",
      autoDetected: true,
      note: "IMG_1.jpg: looks like the same shot"
    });
  });

  test("one file and one flag make one row, however many times it is measured", () => {
    const rows = mediaQualityFlagRows([finding(), finding({ note: "measured again" })]);
    expect(rows).toHaveLength(1);
    // Two identical rows in an archive table read as two separate problems.
    expect(rows[0].note).toContain("measured again");
  });

  test("one file with two different faults is two rows", () => {
    const rows = mediaQualityFlagRows([finding({ flag: "BLUR" }), finding({ flag: "LOW_RESOLUTION" })]);
    expect(rows.map((row) => row.flag)).toEqual(["BLUR", "LOW_RESOLUTION"]);
  });

  test("the finding is banked at the only moment a mediaId exists, and by POSITION", () => {
    // `mediaQualityFlag.mediaId` is a required BASIC field, so a finding is not a row until its file
    // has an id — which happens exactly once, when `uploadMediaBatch` answers.
    expect(FIELD_INPUT).toContain("if (raised.length) recordCaptureFindings(workshopId, raised);");
    /*
      `uploaded` is the by-index array with its NULLS FILTERED OUT, so in a batch where anything
      failed the two do not line up — the same defect that once filed one identity card's digits
      under another card's photograph. And never by filename: two shots off one handset are both
      IMG_0001.jpg.
    */
    expect(FIELD_INPUT).toMatch(/uploadedByIndex\.forEach\(\(media, index\) => \{[\s\S]{0,400}?const key = refusedKeyOf\(source\);/);
  });

  test("a refused photograph can never become a row, because it never gets an id", () => {
    // The gate and this table are complementary halves of one decision: blur and low resolution are
    // refused, so they never upload and there is no file in the archive for a row to be about. Only
    // an ADMITTED file's findings are remembered.
    expect(FIELD_INPUT).toContain("if (verdict?.faults.length) admittedFindingsRef.current.set(key, verdict.faults);");
    expect(FIELD_INPUT).toContain("admittedFindingsRef.current.delete(key);");
  });
});

test("the web and the handset agree on every floor the gate refuses against", () => {
  /**
   * TWO CLIENTS MUST NOT DISAGREE ABOUT WHAT "SHAKY" MEANS.
   *
   * A designer photographs the same motif on a phone and again on a laptop; if the two builds hold
   * different floors, one of them refuses a photograph the other accepts and neither can say why.
   * `lib/imageQuality.ts` is a calibrated port of `ImageQuality.kt` and the numbers were copied
   * across by hand, so nothing but this test holds them together — the same reason
   * `test_role_ladder_parity.py` exists for the role ladder's twenty-three hand-kept copies.
   *
   * Read out of the Kotlin source rather than asserted as literals here, so moving a floor on one
   * side fails on the other rather than quietly diverging.
   */
  const kotlin = readFileSync(KOTLIN_QUALITY, "utf8");
  const constant = (name: string): number => {
    const match = kotlin.match(new RegExp(`const val ${name} = ([0-9.]+)`));
    expect(match, `${name} not found in ImageQuality.kt`).toBeTruthy();
    return Number(match![1]);
  };
  expect(constant("BLUR_VARIANCE_FLOOR")).toBe(BLUR_VARIANCE_FLOOR);
  expect(constant("MIN_LONG_EDGE_PX")).toBe(MIN_LONG_EDGE_PX);
  expect(constant("MIN_CONTRAST_STDDEV")).toBe(MIN_CONTRAST_STDDEV);
});
