import {
  BLUR_VARIANCE_FLOOR,
  MIN_CONTRAST_STDDEV,
  MIN_LONG_EDGE_PX,
  isBlurred,
  isUnderResolution,
  type ImageMeasurement,
  type QualityFlag,
  type QualitySeverity
} from "@/lib/imageQuality";

/**
 * THE CAPTURE GATE — which photographs are allowed to start uploading, and the words for the ones
 * that are not.
 *
 * ── WHY THIS IS A REFUSAL WHERE `lib/imageQuality.ts` IS ADVICE, AND WHERE THE LINE IS ────────────
 *
 * That module's header states, in as many words, that "a finding is advice, never a refusal" and
 * that "the surfacing must keep it that way". This module is the surfacing, and it does NOT keep it
 * that way — so the disagreement is written down here rather than left for a reader to discover.
 *
 * The owner's instruction on 2026-08-27 was that a shaky or poor-quality photograph must not reach
 * the server at all. That is a different decision from the one `imageQuality.ts` was written under,
 * and it is the owner's to make. What is NOT the owner's to make is the claim: a gate may only
 * refuse on something this product actually MEASURES, and it may only refuse where a designer can
 * comply. So the split below is narrow on purpose, and `imageQuality.ts`'s argument survives intact
 * for everything outside it:
 *
 *   REFUSED — the photograph never enters the capture card, so the eager pre-upload never starts:
 *     * BLUR            variance of the Laplacian below {@link BLUR_VARIANCE_FLOOR}. "Shaky", the
 *                       owner's own first word, and the fault this gate exists for.
 *     * LOW_RESOLUTION  long edge below {@link MIN_LONG_EDGE_PX}. The other honest reading of
 *                       "poor quality", and unarguable: the photograph provably cannot fill a
 *                       report plate at the resolution this app rasterises its own figures at.
 *     * DUPLICATE, but only the EXACT one — the same SHA-256 as a file already attached here.
 *
 *   WARNED, NEVER REFUSED — admitted, and `MediaCaptureField` says its piece afterwards:
 *     * DUPLICATE by perceptual hash. See {@link nearDuplicateIsNeverRefused}.
 *
 *   NOT MEASURED, THEREFORE NOT CLAIMED ANYWHERE ON SCREEN — OVEREXPOSED, UNDEREXPOSED and
 *     WRONG_SUBJECT are tokens in stage 21's `MEDIA_QUALITY_FLAG` enum that NO code in this product
 *     computes. There is no luma histogram here and this module must not imply there is one. The
 *     registry's own help text for both motif galleries ends "Exposure and subject are not checked
 *     — judge those by eye", and {@link gateScopeSentence} is the same sentence at the point of
 *     capture, so a designer meets one claim and not two.
 *
 * ── IT FAILS OPEN, BY CONSTRUCTION AND NOT BY A BRANCH ────────────────────────────────────────────
 *
 * {@link gatePhotograph} takes a MEASUREMENT. A file this browser will not decode produces no
 * measurement (`measureImageFile` answers null for a corrupt file, an unsupported codec, a GPU that
 * refused the bitmap, or a browser with no `createImageBitmap` at all), and the caller then never
 * reaches this function — the photograph is admitted. That is the required direction: an image the
 * detector cannot read is not a bad photograph, and refusing it would make both motif galleries
 * unfillable on a handset whose decoder differs. There is no arm below that turns an absence of
 * evidence into a refusal, and there must never be one.
 *
 * The same rule one level down: {@link isBlurred} returns false when contrast is under
 * {@link MIN_CONTRAST_STDDEV}, because variance of the Laplacian stops discriminating on a flat
 * subject. That guard is what stops this gate refusing a perfectly sharp photograph of a plain-dyed
 * cloth or a smooth metal tool — measured in `e2e/image-quality.spec.ts`, which pins a sharp flat
 * field at a blur score BELOW the floor and a contrast BELOW the guard, and asserts that the guard
 * is "the only thing stopping it becoming a warning". It is now the only thing stopping it becoming
 * a REFUSAL, which is a heavier load on one constant than it was carrying before. Anyone raising
 * {@link MIN_CONTRAST_STDDEV}, or moving {@link BLUR_VARIANCE_FLOOR} toward the middle of its
 * calibration gap, is deciding how often a correct photograph of a flat motif is turned away.
 *
 * ── WHERE IT APPLIES: EVERY PHOTOGRAPH A PERSON CHOOSES, NOT ONLY THE TWO MOTIF GALLERIES ────────
 *
 * The owner's reason for the gate was that a poor photograph "would just go into reports", and that
 * is true of every image field in the registry, not only of the two the same instruction gave a
 * count of twenty-five. So the gate runs wherever a designer picks, photographs or drops an image,
 * and the FLOOR — the count and the bar — is drawn only where the registry declares a `minItems`.
 * Two features from one sentence, with different scopes, deliberately.
 *
 * TWO EXCEPTIONS, BOTH BY CONSTRUCTION RATHER THAN BY A FLAG. A file this app DERIVED rather than a
 * person chose — a signature drawn on a canvas, a rectified sketch, a traced line-art export — is
 * attached by its own panel and never passes here; the argument is written at that call site in
 * `FieldInput`, and the short version is that a signature canvas would be refused as too small to
 * print. And anything `isMeasurableImage` declines (audio, video, a PDF) is never a photograph to
 * begin with.
 *
 * ── PURE. NO DOM, NO FILE, NO NETWORK, NO REACT ───────────────────────────────────────────────────
 *
 * Same reason `lib/imageQuality.ts` splits the same way and `lib/photoIntake.ts` says so in its
 * header: this half is driven by value in `e2e/photo-quality-gate-unit.spec.ts` rather than by
 * driving a screen, and it is what the Kotlin port copies. This repository has no React renderer in
 * its devDependencies, so a judgement written inside JSX is only ever exercised by somebody looking
 * at a browser.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * The faults, and which of them close the door
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One reason a photograph was turned away, or flagged.
 *
 * `flag` is `QualityFlag` from `lib/imageQuality.ts` — the vocabulary is stage 21's
 * `MEDIA_QUALITY_FLAG` verbatim, so the sentence a designer reads in the courtyard and the row the
 * archive stores name the same problem with the same word. `kind` is finer than `flag` for exactly
 * one reason: `DUPLICATE` covers two situations that must be treated differently (see
 * {@link nearDuplicateIsNeverRefused}) and the archive has only the one token for both.
 */
export type GateFault = {
  kind: "BLUR" | "LOW_RESOLUTION" | "EXACT_DUPLICATE" | "NEAR_DUPLICATE";
  flag: QualityFlag;
  severity: QualitySeverity;
  /**
   * The sentence put in front of the designer, and it ALWAYS carries the reading and the floor it
   * was measured against.
   *
   * "This photograph is blurred" is indistinguishable from the app being wrong, and a gate a
   * designer believes is wrong is a gate they route around — by pressing the shutter until one gets
   * through, which fills the gallery with the photographs this exists to keep out. "The sharpness
   * reading was 42 against a floor of 60" can be argued with, checked, and acted on.
   */
  message: string;
};

/** Whether this fault stops the upload, or only annotates it. Nothing else may decide this. */
export function faultRefuses(fault: GateFault): boolean {
  return fault.kind !== "NEAR_DUPLICATE";
}

/**
 * WHY A PERCEPTUAL-HASH MATCH IS A WARNING AND AN EXACT ONE IS A REFUSAL.
 *
 * A refusal is only defensible where the designer can comply and where complying costs nothing.
 *
 * EXACT — identical SHA-256 with a file already attached to this same field. Refusing costs the
 * designer literally nothing: the bytes are already here, under a name the message prints. There is
 * no photograph to re-take and no information anywhere in the second copy.
 *
 * NEAR — within {@link NEAR_DUPLICATE_MAX_DISTANCE} bits of another shot. `imageQuality.ts`'s own
 * calibration says "two exposures of one object seconds apart land in the low single digits", which
 * is INSIDE the threshold — and two exposures of one object seconds apart is precisely how a
 * designer photographs twenty-five motifs on one length of cloth. Refusing there would turn away
 * correct, wanted, irreplaceable photographs, and it would do it most often to the designer working
 * fastest. So it is admitted and the capture card says its piece, exactly as it did before this gate
 * existed.
 *
 * This function exists to hold the argument; `faultRefuses` is what the code calls.
 */
export const nearDuplicateIsNeverRefused = true;

/** A photograph already attached to this field, as far as the EXACT-duplicate check is concerned. */
export type GateAttached = {
  label: string;
  /** The SHA-256 the upload path already computed. Absent is "unknown", NEVER "unique". */
  checksum?: string | null;
};

export type GateVerdict = {
  /** False only when at least one fault {@link faultRefuses}. */
  admitted: boolean;
  /** Everything found, refusing and not. Empty for a photograph with nothing wrong with it. */
  faults: GateFault[];
};

/**
 * Judge one photograph, from its measurement alone.
 *
 * CALLED ONLY WITH A MEASUREMENT — see the header on failing open. A caller holding `null` from
 * `measureImageFile`, or a file `isMeasurableImage` refused, must admit it without reaching here.
 *
 * The blur and resolution decisions delegate to `lib/imageQuality.ts`'s exported predicates rather
 * than re-testing the constants, so this module cannot come to disagree with the module the floors
 * are calibrated in — and cannot drift from Android's `DwImageQuality`, of which that file is a
 * calibrated port. The numbers are read for PRINTING only.
 */
export function gatePhotograph({
  measurement,
  checksum,
  attached
}: {
  measurement: ImageMeasurement;
  /** This file's own SHA-256 where it could be computed; absent is "unknown", never "unique". */
  checksum?: string | null;
  attached?: GateAttached[];
}): GateVerdict {
  const faults: GateFault[] = [];

  if (isBlurred(measurement)) {
    faults.push({
      kind: "BLUR",
      flag: "BLUR",
      severity: "MEDIUM",
      message:
        `the sharpness reading was ${Math.round(measurement.blurScore)} against a floor of ` +
        `${BLUR_VARIANCE_FLOOR}, so it is out of focus or the camera moved. Hold still, tap the ` +
        `subject to focus, and take it again.`
    });
  }

  if (isUnderResolution(measurement)) {
    faults.push({
      kind: "LOW_RESOLUTION",
      flag: "LOW_RESOLUTION",
      severity: "MEDIUM",
      message:
        `it is ${measurement.width}x${measurement.height}, and a report plate needs about ` +
        `${MIN_LONG_EDGE_PX}px on the long edge. Raise the camera's resolution, or send the ` +
        `original rather than a copy something has already shrunk.`
    });
  }

  // Exact first and exclusively: where the bytes are identical there is nothing a perceptual hash
  // can add, and reporting a duplicate twice about one file reads as two separate problems. Same
  // ordering, for the same reason, as `findQualityIssues`.
  const identical = checksum ? (attached ?? []).filter((item) => item.checksum && item.checksum === checksum) : [];
  if (identical.length) {
    faults.push({
      kind: "EXACT_DUPLICATE",
      flag: "DUPLICATE",
      severity: "LOW",
      message:
        `the identical file is already attached here as ` +
        `${identical.map((item) => `"${item.label}"`).join(", ")}. Nothing is lost by leaving this ` +
        `copy out.`
    });
  }

  return { admitted: !faults.some(faultRefuses), faults };
}

/**
 * WHAT THIS GATE CHECKS AND WHAT IT DOES NOT, IN ONE SENTENCE, ON SCREEN.
 *
 * The registry's help text for both motif galleries already ends with the second half of this, and
 * stage 21's note carries it too. It is repeated at the point of capture because that is where a
 * designer forms the belief — a gate that silently admits a badly-exposed photograph, on a screen
 * that has just refused two others for being soft, teaches "the app checks my photographs" and
 * nothing narrower. The three unmeasured tokens are named rather than merely omitted.
 *
 * NO NUMBERS. The floors are client constants that move with a re-calibration; a fixed sentence
 * quoting one goes stale silently. Every refusal prints its own reading and its own floor, which is
 * the only place a number belongs.
 */
export function gateScopeSentence(): string {
  return (
    "Each photograph is checked on this device before it uploads — for focus, for resolution, and " +
    "for being the identical file twice. Exposure and subject are not checked; judge those by eye."
  );
}

/**
 * The refusal, in words — or null when nothing was turned away.
 *
 * NAMES EVERY FILE AND ITS OWN REASON, one clause each, because a designer who photographed
 * twenty-five motifs and had four refused needs to know WHICH four and WHY each, and a count tells
 * them neither. The same rule `uploadMediaBatch`'s callers are under, and the same rule
 * `acceptFiles` follows for files the ceiling turned away one door later.
 *
 * DERIVED FROM THE LIST AT RENDER, never frozen when it happened, so removing an attachment and
 * re-picking cannot leave a stale receipt on screen. The caller holds the list.
 */
export function gateRefusalSentence(refused: Array<{ name: string; faults: GateFault[] }>): string | null {
  if (!refused.length) return null;
  const clauses = refused.map((entry) => {
    const reasons = entry.faults
      .filter(faultRefuses)
      .map((fault) => fault.message)
      .join(" And ");
    return `${entry.name} — ${reasons}`;
  });
  const noun = refused.length === 1 ? "photograph was" : "photographs were";
  return (
    `${refused.length} ${noun} not uploaded. ${clauses.join(" ")} ` +
    `Nothing was sent, so take ${refused.length === 1 ? "it" : "them"} again and attach ` +
    `${refused.length === 1 ? "it" : "them"} — this list stays until you do.`
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * The floor, and the progress it is counted against
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * HOW MANY ENTRIES THE REGISTRY SAYS THIS FIELD MUST HOLD, OR NULL WHERE IT DECLARES NONE.
 *
 * The mirror image of `declaredMaxItems`, and it obeys the same half of the contract: `field_to_dict`
 * emits `minItems` ONLY for a field that declares one (`stage_schema.py`), so an absent key means
 * "no floor" and must never be drawn as a number. Two fields in the whole registry answer it today —
 * the motif pair, 25 each — and every other gallery draws no bar and makes no demand.
 *
 * ── WHY THE ARGUMENT IS TYPED THIS LOOSELY, WHICH IS A DEBT AND NOT A STYLE ───────────────────────
 *
 * `DwField` in `lib/designWorkshops.ts` has no `minItems` member yet: that file belongs to another
 * lane and was not mine to edit in this wave. The registry is parsed from JSON, so the key IS on the
 * object at runtime — a closed TypeScript type does not remove a property the server sent. Reading
 * it through a structural parameter is the honest way to say "this key exists on the wire and not
 * yet in the type", and it keeps the coercion in one function instead of at every call site.
 *
 * THE FIX IS ONE LINE IN THAT FILE — `minItems?: number` on `DwField`, beside `maxItems` and with
 * the same note — after which this signature should narrow to `DwField`. Until then the `> 0` and
 * the `typeof` are load-bearing rather than defensive: they are the whole of the validation.
 *
 * `key` IS IN THE SIGNATURE AND IS NOT READ. It is there so the parameter shares a property with
 * `DwField` and a caller cannot hand this an arbitrary object by accident: a target type whose every
 * member is optional accepts anything at all, and TypeScript says so out loud ("has no properties in
 * common"). Requiring the one member every field descriptor has keeps the call sites honest for the
 * short time this signature has to be structural.
 *
 * The asymmetry with `maxItems` is deliberate on the server and matters here: `min_items` IS part of
 * `registry_version()` and `max_items` is not, because a ceiling has a server-side backstop
 * (`coerce_value` refuses the over-long array whatever a stale client believes) and a floor has
 * none. A handset that has never refetched scores the stage complete at one photograph. So a client
 * reading a stale floor is a client telling a designer they may leave the cluster.
 */
export function declaredMinItems(field: { key: string; minItems?: unknown }): number | null {
  const declared = field.minItems;
  return typeof declared === "number" && declared > 0 ? declared : null;
}

/** What a gallery is holding, in the three states that are genuinely different from each other. */
export type GalleryCounts = {
  /** References in the field's value: what a save would post. Includes on-device-only references. */
  held: number;
  /** Of `held`, the `dwlocal:` ones — real photographs the server has not been told about yet. */
  onDevice: number;
  /** In the capture card, uploading now. Will become `held` the moment each transfer lands. */
  uploading: number;
  /** Measured but not yet judged — see the gate above. Not uploading, and may never. */
  screening: number;
};

export type GalleryProgress = {
  held: number;
  floor: number;
  /** 0–100, clamped, for the bar's width and its `aria-valuenow`. */
  percent: number;
  /** The bare readout, e.g. "18 of 25". Drawn in digits AND carried in `aria-valuetext`. */
  readout: string;
  /** The full sentence: the readout, what is left, and every qualifier that is true right now. */
  words: string;
  complete: boolean;
};

/**
 * The bar's numbers and the bar's sentence, from one place so they cannot disagree.
 *
 * ── THE NUMERATOR IS `held`, AND NOTHING IN FLIGHT IS QUIETLY ADDED TO IT ─────────────────────────
 *
 * A photograph that is uploading is not in the gallery: its transfer can still fail, and until it
 * lands the field's value has no reference to it. Counting it would draw "25 of 25" over a gallery
 * that a save would post twenty-four of — a green bar as a receipt for work that has not happened,
 * which is the failure this repository names most often. So the in-flight files are stated as their
 * own clause instead, which is both honest and more useful: "23 of 25 attached, 2 more uploading"
 * tells a designer to wait, where "25 of 25" tells them to walk away.
 *
 * ── AND `held` IS STILL NOT "SAVED" ───────────────────────────────────────────────────────────────
 *
 * Attached is a value in a form; the workshop learns nothing until the stage is saved. That sentence
 * belongs on the standing floor paragraph rather than in here, because it is true from first paint
 * and does not change as the count moves — repeating it in a level that updates on every attach
 * would be noise around the one number that is changing.
 *
 * ── THE ON-DEVICE CLAUSE ──────────────────────────────────────────────────────────────────────────
 *
 * A `dwlocal:` reference IS in the value and IS counted — the photograph exists, the designer took
 * it, and the sync pass carries it. But the server has not seen it, so it is named: a designer who
 * reads "25 of 25" in a courtyard with no signal and never learns that eleven of them are sitting in
 * one browser is a designer one cleared cache away from losing them.
 */
export function galleryProgress({ counts, floor }: { counts: GalleryCounts; floor: number }): GalleryProgress {
  const held = Math.max(0, counts.held);
  const safeFloor = Math.max(1, floor);
  const percent = Math.max(0, Math.min(100, Math.round((held / safeFloor) * 100)));
  const readout = `${held} of ${floor}`;
  const remaining = Math.max(0, floor - held);

  const clauses: string[] = [];
  if (remaining === 0) {
    clauses.push(`All ${floor} photographs are attached.`);
  } else if (held === 0) {
    clauses.push(`None of the ${floor} photographs this gallery needs are attached yet.`);
  } else {
    clauses.push(
      `${readout} photographs are attached. ${remaining} more ` +
        `${remaining === 1 ? "is" : "are"} needed.`
    );
  }
  if (counts.screening > 0) {
    clauses.push(
      `${counts.screening} ${counts.screening === 1 ? "is" : "are"} being checked before ` +
        `${counts.screening === 1 ? "it uploads" : "they upload"}.`
    );
  }
  if (counts.uploading > 0) {
    clauses.push(
      `${counts.uploading} more ${counts.uploading === 1 ? "is" : "are"} uploading and ` +
        `${counts.uploading === 1 ? "is" : "are"} not counted yet.`
    );
  }
  if (counts.onDevice > 0) {
    clauses.push(
      `${counts.onDevice} of the ${held} ${counts.onDevice === 1 ? "is" : "are"} on this device ` +
        `only until the connection returns.`
    );
  }

  return { held, floor, percent, readout, words: clauses.join(" "), complete: remaining === 0 };
}

/**
 * THE STANDING SENTENCE — the one that must be on screen BEFORE the twentieth photograph, not after.
 *
 * Present from first paint, named in the field group's `aria-describedby` rather than announced, and
 * it says three things a designer needs in this order: how many are wanted, that falling short costs
 * them nothing today, and what it does cost.
 *
 * ── EVERY CLAIM IN IT WAS CHECKED AGAINST WHAT IS ACTUALLY ENFORCED ───────────────────────────────
 *
 * "A short gallery still saves" is the load-bearing one and it is TRUE: the floor lives in
 * `stage_completeness` and in nothing else — not in `coerce_value`, not in `validate_entry` — so no
 * save path can refuse a partial gallery. That is deliberate, and the reason is written above
 * `FieldSpec.min_items`: a designer in a village with twenty good photographs and no signal must be
 * able to save the twenty, and on Android a 4xx is DROPPED rather than queued, so a refusal there
 * would destroy the record rather than delay it.
 *
 * WHAT THIS SENTENCE DELIBERATELY DOES NOT SAY, because it is not true today: that the workshop
 * cannot be SUBMITTED. `PATCH /design-workshops/{id}` accepts `status: "SUBMITTED"` with an enum
 * check and no completeness test anywhere, so a promise of a hard block would be this client
 * inventing an enforcement that does not exist. Nor does it name this browser's readiness screen:
 * `lib/submissionReadiness.ts` does not read `minItems` yet (another lane's file, and a listed
 * follow-up), so it scores stage 4 complete at one photograph. What IS true, and is therefore what
 * is said, is that the SERVER scores the stage incomplete and the generated report prints it.
 */
export function galleryFloorSentence({ floor, label }: { floor: number; label: string }): string {
  return (
    `All ${floor} are required. ${label} still saves with fewer — nothing you attach is ever at ` +
    `risk — but until it holds ${floor} the stage is scored incomplete, and the generated report ` +
    `says so. Attached is not saved: the count reaches the workshop when you save the stage.`
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * The write path: a finding raised at capture becomes a stage-21 row
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A finding this device raised about a photograph that WAS uploaded, tied to the media id it got.
 *
 * The media id is the whole point and the reason this cannot be recorded any earlier: stage 21's
 * `mediaQualityFlag.mediaId` is a required BASIC field, and a row naming a file that does not exist
 * is worse than no row. So a finding becomes one of these at exactly one moment — when
 * `uploadMediaBatch` answers and the file has an id.
 */
export type CapturedFinding = {
  mediaId: string;
  fileName: string;
  flag: QualityFlag;
  severity: QualitySeverity;
  note: string;
  /** ISO 8601. Only so a reader can tell a finding from this workshop's fieldwork from an old one. */
  raisedAt: string;
};

/**
 * WHICH FLAGS MAY EVER BE FILLED IN BY A MACHINE — and it is a shorter list than the enum.
 *
 * Stage 21's `MEDIA_QUALITY_FLAG` has seven members. This product computes three of them. The other
 * four — OVEREXPOSED, UNDEREXPOSED, WRONG_SUBJECT, and MISSING_VIEW where it is about a row rather
 * than a file — are judgements no measurement here makes, or are not about one file at all, and they
 * stay hand-entered. Auto-filling a flag nothing measured would put the app's guess in a column an
 * officer reads as an observation.
 *
 * `autoDetected` on the row is what separates the two afterwards, and it is only ever true for a
 * member of this set.
 */
export const AUTO_FILLABLE_FLAGS: readonly QualityFlag[] = ["BLUR", "LOW_RESOLUTION", "DUPLICATE"];

/** One row of stage 21's `mediaQualityFlag` collection, in the registry's own field keys. */
export type MediaQualityFlagRow = {
  mediaId: string;
  flag: QualityFlag;
  severity: QualitySeverity;
  autoDetected: true;
  note: string;
};

/**
 * Turn what this device found at capture into stage-21 rows, ready to be appended.
 *
 * ── WHY THIS IS ALMOST ALWAYS ABOUT DUPLICATES, WHICH IS THE GATE WORKING ─────────────────────────
 *
 * The gate and this write path are complementary halves of one decision, and reading them together
 * is the only way either makes sense. A BLUR or LOW_RESOLUTION photograph is REFUSED, so it never
 * uploads, never gets a media id, and can never be one of these rows — correctly: there is no file
 * in the archive for the flag to be about. What survives the gate and still deserves a row is the
 * near-duplicate, which is admitted on purpose. So the practical effect of shipping the gate is that
 * this table stops recording faults and starts recording the one fault worth recording.
 *
 * The two arms are kept anyway rather than narrowed to DUPLICATE, because {@link faultRefuses} is
 * the one place that decision lives: if an owner ever answers the override question by letting a
 * designer push a soft photograph through, its finding travels here without another change.
 *
 * ── IT PROPOSES; SOMETHING ELSE COMMITS ───────────────────────────────────────────────────────────
 *
 * Same rule as `photoIntake.ts` and the identity-card reader: nothing here writes to a stage. It
 * returns rows and a screen offers them. A row appearing in an archive table that nobody chose is a
 * row nobody will trust — and stage 21's own registry note still reads "Rows here are entered by
 * hand either way; tick 'Detected automatically' for the ones the app raised", which is a promise
 * about a screen and stays true for exactly as long as the commit is a person's.
 */
export function mediaQualityFlagRows(findings: CapturedFinding[]): MediaQualityFlagRow[] {
  // ONE ROW PER FILE PER FLAG, AND THE NEWEST READING WINS — the same rule, in the same words, that
  // `recordCaptureFindings` applies to the log this reads from, because two functions in one feature
  // disagreeing about which of two measurements of one photograph is the true one is a difference
  // nobody would ever see and everybody would eventually trip over. The same photograph really is
  // measured more than once (a stage reopened, a collection row re-expanded), and the reading that
  // describes the file in hand is the last one taken; two identical rows in an archive table read as
  // two separate problems. `Map` keeps a re-set key in its ORIGINAL position, so refreshing a
  // reading does not reorder the table under a reader.
  const byKey = new Map<string, MediaQualityFlagRow>();
  for (const finding of findings) {
    if (!AUTO_FILLABLE_FLAGS.includes(finding.flag)) continue;
    byKey.set(`${finding.mediaId}:${finding.flag}`, {
      mediaId: finding.mediaId,
      flag: finding.flag,
      severity: finding.severity,
      autoDetected: true,
      note: `${finding.fileName}: ${finding.note}`
    });
  }
  return [...byKey.values()];
}
