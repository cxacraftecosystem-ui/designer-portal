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

const ROOT = join(__dirname, "..");
const FIELD_INPUT = readFileSync(join(ROOT, "components/designworkshop/FieldInput.tsx"), "utf8");
const KOTLIN_QUALITY = join(ROOT, "..", "android/app/src/main/java/com/designprototype/workshop/data/ImageQuality.kt");

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
    expect(verdict.faults[0].message.toLowerCase()).toContain("nothing is lost");
  });

  test("two faults at once are both reported, and the refusal is not doubled", () => {
    const verdict = gatePhotograph({ measurement: goodPhotograph({ blurScore: 10, width: 800, height: 600 }) });
    expect(verdict.admitted).toBe(false);
    expect(verdict.faults.map((fault) => fault.kind)).toEqual(["BLUR", "LOW_RESOLUTION"]);
  });
});

test.describe("what the gate must NEVER refuse — the fail-open half", () => {
  test("a sharp photograph of a FLAT subject is admitted, though it scores below the blur floor", () => {
    const flat = goodPhotograph({ blurScore: 58, contrast: MIN_CONTRAST_STDDEV - 3 });
    expect(flat.blurScore).toBeLessThan(BLUR_VARIANCE_FLOOR);
    const verdict = gatePhotograph({ measurement: flat });
    expect(verdict.admitted).toBe(true);
    expect(verdict.faults).toEqual([]);
  });

  test("an unknown checksum is 'unknown' and never 'unique' — it raises nothing either way", () => {
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
    const near: GateFault = { kind: "NEAR_DUPLICATE", flag: "DUPLICATE", severity: "LOW", message: "x" };
    expect(faultRefuses(near)).toBe(false);
    for (const kind of ["BLUR", "LOW_RESOLUTION", "EXACT_DUPLICATE"] as const) {
      expect(faultRefuses({ kind, flag: "BLUR", severity: "MEDIUM", message: "x" })).toBe(true);
    }
  });

  test("the wiring admits a photograph nothing could measure, rather than refusing it", () => {
    expect(FIELD_INPUT).toContain("const measurement = await measureImageFile(file);");
    expect(FIELD_INPUT).toMatch(/if \(!measurement\) \{\s*\n\s*release\(file, null\);/);
    expect(FIELD_INPUT).toContain("if (!verdict || verdict.admitted) {");
    expect(FIELD_INPUT).toContain("const photographs = fresh.filter(isMeasurableImage);");
    expect(FIELD_INPUT).toContain("if (rest.length) setPending((current) => [...current, ...rest]);");
  });
});

test.describe("the gate runs BEFORE a byte moves", () => {
  test("acceptFiles hands photographs to the screening list, never straight to the capture card", () => {
    expect(FIELD_INPUT).toContain("setScreening((current) => [...current, ...photographs]);");
    expect(FIELD_INPUT).toContain("void screen(photographs);");
    expect(FIELD_INPUT).not.toContain("setPending([...next.slice(0, pending.length), ...kept]);");
    expect(FIELD_INPUT).toContain("admit(next.slice(0, pending.length), added);");
    expect(FIELD_INPUT).toContain("admit(next.slice(0, pending.length), kept);");
  });

  test("files under measurement are counted against the ceiling, in both directions", () => {
    expect(FIELD_INPUT).toContain(
      "const room = cap === null ? null : Math.max(0, cap - ids.length - pending.length - screening.length);"
    );
    expect(FIELD_INPUT).toContain("const accounted = ids.length + pending.length + screening.length;");
  });

  test("the screening list has the hoisted store's lifetime, not the panel's", () => {
    expect(FIELD_INPUT).toContain('usePendingMedia(mediaPlace, "screening")');
    expect(FIELD_INPUT).toContain("function usePendingMedia(place: StageMediaPlace, slot = \"\")");
  });
});

test.describe("the floor, and the bar counted against it", () => {
  test("minItems is read, never assumed, and an absent floor is no floor", () => {
    expect(declaredMinItems({ key: "motifPhotos", minItems: 25 })).toBe(25);
    expect(declaredMinItems({ key: "clusterPhotos" })).toBeNull();
    expect(declaredMinItems({ key: "x", minItems: 0 })).toBeNull();
    expect(declaredMinItems({ key: "x", minItems: "25" })).toBeNull();
  });

  test("the component reads the floor off the field and hard-codes no number", () => {
    expect(FIELD_INPUT).toContain("const floor = multiple ? declaredMinItems(field) : null;");
    expect(FIELD_INPUT).toContain("floor={floor}");
    expect(FIELD_INPUT).not.toMatch(/floor\s*[=:]\s*25\b/);
  });

  test("the bar counts what is IN the gallery and never what is on its way", () => {
    const progress = galleryProgress({
      counts: { held: 23, onDevice: 0, uploading: 2, screening: 0 },
      floor: 25
    });
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
    expect(over.readout).toBe("30 of 25");
  });

  test("photographs that exist only in this browser are counted AND named", () => {
    const progress = galleryProgress({ counts: { held: 25, onDevice: 11, uploading: 0, screening: 0 }, floor: 25 });
    expect(progress.complete).toBe(true);
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
    expect(sentence).toContain("still saves with fewer");
    expect(sentence.toLowerCase()).toContain("scored incomplete");
    expect(sentence.toLowerCase()).not.toContain("submit");
    expect(sentence.toLowerCase()).not.toContain("readiness");
  });

  test("the standing sentence distinguishes attached from saved", () => {
    const sentence = galleryFloorSentence({ floor: 25, label: "Traditional motif photographs" });
    expect(sentence).toContain("Attached is not saved");
  });

  test("the scope sentence names the faults that are NOT measured, and claims none of them", () => {
    const sentence = gateScopeSentence().toLowerCase();
    expect(sentence).toContain("exposure and subject are not checked");
    expect(sentence).toContain("focus");
    expect(sentence).toContain("resolution");
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
    expect(rows[0].note).toContain("measured again");
  });

  test("one file with two different faults is two rows", () => {
    const rows = mediaQualityFlagRows([finding({ flag: "BLUR" }), finding({ flag: "LOW_RESOLUTION" })]);
    expect(rows.map((row) => row.flag)).toEqual(["BLUR", "LOW_RESOLUTION"]);
  });

  test("the finding is banked at the only moment a mediaId exists, and by POSITION", () => {
    expect(FIELD_INPUT).toContain("if (raised.length) recordCaptureFindings(workshopId, raised);");
    expect(FIELD_INPUT).toMatch(/uploadedByIndex\.forEach\(\(media, index\) => \{[\s\S]{0,400}?const key = refusedKeyOf\(source\);/);
  });

  test("a refused photograph can never become a row, because it never gets an id", () => {
    expect(FIELD_INPUT).toContain("if (verdict?.faults.length) admittedFindingsRef.current.set(key, verdict.faults);");
    expect(FIELD_INPUT).toContain("admittedFindingsRef.current.delete(key);");
  });
});

test("the web and the handset agree on every floor the gate refuses against", () => {
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
