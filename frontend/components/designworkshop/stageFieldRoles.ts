/**
 * Which registry fields play a role the generic renderer cannot read off `field.type` alone.
 *
 * There are exactly five, and all are kept here rather than inline so that the day the registry
 * grows an explicit descriptor for any of them, ONE file changes and the guessing stops.
 *
 * FOUR OF THE FIVE GUESS, AND SAY SO. `stage_definitions.py` declares no "this image is an identity
 * card" flag, no "this text box holds a national identity number", no "these two dates are the ends
 * of one range" and no "this image is a signature", so those four are inferred from the field key. An
 * inference that is wrong there costs an offered button that finds nothing, which a designer
 * ignores. An inference that WROTE something would be a different matter entirely, which is why
 * nothing in the OCR path commits without a human pressing Confirm.
 *
 * THE FIFTH DOES NOT GUESS, and the difference is worth naming because it is the shape the other four
 * would like to be. {@link measurableLengthFields} never looks at a key: it asks whether the registry
 * DECLARED the field numeric and DECLARED its unit to be a length. `lengthCm`, `finalWidthCm` and
 * `diameterCm` qualify because they carry `unit="cm"`; `weightG`, `makingTimeDays` and `materialCost`
 * do not, because they carry `unit="g"`, `"days"` and `"INR"` — not because of anything in their
 * names. A registry that adds a `unit="mm"` field gets the behaviour with no change here, and one
 * that adds `spanInHands` gets nothing, which is the correct answer in both directions.
 */

import type { DwEntity, DwField } from "@/lib/designWorkshops";
import { type LengthUnit, LENGTH_UNITS } from "@/lib/photoMeasure";

/** Keys and labels folded to letters, so `artisanCardNo` and "Artisan ID / card number" both match. */
function folded(value: string): string {
  return value.toLowerCase().replace(/[^a-z]/g, "");
}

/**
 * A text field that holds a card or identity number.
 *
 * `aadhar` is listed beside `aadhaar` deliberately: it is the spelling roughly half of India writes,
 * it appears in this repository's own historical metadata, and a registry field named with it would
 * otherwise be missed for the one reason nobody would ever look for.
 */
const IDENTITY_NUMBER_KEY = /(aadhaar|aadhar|pehchan|cardno|cardnumber|idnumber|identitynumber|idcardno)/;

/** An image that is plausibly a photograph OF a card rather than of a person or a product. */
const IDENTITY_IMAGE_KEY = /(card|aadhaar|aadhar|pehchan|identity|idproof)/;

/** The field an OCR read would fill, or null when this entity records no identity number at all. */
export function identityNumberField(entity: DwEntity): DwField | null {
  return (
    entity.fields.find(
      (field) => !field.deprecated && field.type === "TEXT" && IDENTITY_NUMBER_KEY.test(folded(`${field.key} ${field.label}`))
    ) ?? null
  );
}

/**
 * Should this media field offer "Read the number from this card"?
 *
 * Two conditions, and the second is what keeps the button off a participant's portrait: the entity
 * must actually have somewhere to put a number, AND this image must be the one that looks like a
 * card. Where no image field in the entity names a card, the offer is made on the entity's first
 * image field regardless — a registry that records a card number almost certainly photographs the
 * card, and a designer who has attached it to a generically-named box is exactly who this helps.
 */
export function offersIdentityOcr(entity: DwEntity, field: DwField): boolean {
  if (field.type !== "IMAGE" && field.type !== "IMAGE_LIST") return false;
  if (!identityNumberField(entity)) return false;
  const images = entity.fields.filter(
    (candidate) => !candidate.deprecated && (candidate.type === "IMAGE" || candidate.type === "IMAGE_LIST")
  );
  const named = images.filter((candidate) => IDENTITY_IMAGE_KEY.test(folded(`${candidate.key} ${candidate.label}`)));
  if (named.length) return named.some((candidate) => candidate.key === field.key);
  return images[0]?.key === field.key;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Signatures
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * An image field that holds a handwritten signature rather than a photograph.
 *
 * THIS ONE IS A MUCH SAFER GUESS THAN THE TWO ABOVE, and it is worth saying why rather than letting
 * a reader assume it carries the same risk. The identity-card and date-range inferences read keys
 * somebody else declared for other reasons; this one reads a key that was added FOR this, in the
 * same change that added the pad (`signatureImage` on stage 19's `certificate` entity). It is still
 * matched by name rather than hardcoded to that one key so that a second signature field — a
 * designer's own sign-off, an officer's — picks the pad up with no client change, which is the same
 * property the whole registry is built for.
 *
 * A wrong answer here is cheap in BOTH directions, which is the test the file header sets: a false
 * positive offers a drawing pad on a photograph field, which a designer ignores and which writes
 * nothing; a false negative leaves the ordinary file picker, which still works. Nothing is refused
 * and nothing is committed either way.
 */
const SIGNATURE_KEY = /(signature|signed|thumbimpression|thumbprint)/;

export function offersSignaturePad(field: DwField): boolean {
  // IMAGE_LIST is included because a gallery of signatures is a legitimate shape (a sheet signed by
  // several people) and the pad appends one at a time to whatever is already there.
  if (field.type !== "IMAGE" && field.type !== "IMAGE_LIST") return false;
  if (field.deprecated) return false;
  return SIGNATURE_KEY.test(folded(`${field.key} ${field.label}`));
}

/* ────────────────────────────────────────────────────────────────────────────
 * Date ranges
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A field key as lowercase word tokens: `surveyStartDate` -> ["survey", "start", "date"].
 *
 * Tokenised rather than folded to one string, which the two helpers above can afford and this one
 * cannot. Folding `totalDate` gives "totaldate", and a substring test for "to" then reads it as the
 * end of a range — so the marker below is matched as a WHOLE WORD or not at all.
 */
function keyTokens(key: string): string[] {
  return key
    .split(/(?=[A-Z])|[_\-\s]+/)
    .map((part) => part.toLowerCase())
    .filter(Boolean);
}

/** Words that name the opening end of a range, as whole tokens of a field key. */
const RANGE_START_TOKENS = new Set(["start", "started", "from", "begin", "began", "opened", "commenced"]);

/** And the closing end. `completed` is here because stage 17 spells its pair `startDate`/`completedDate`. */
const RANGE_END_TOKENS = new Set(["end", "ended", "to", "until", "complete", "completed", "finish", "finished", "closed"]);

export type DateRangeRole = "start" | "end";

/** Which end of a range this key names, and what is left of the key once that word is removed. */
function rangeRole(key: string): { role: DateRangeRole; stem: string } | null {
  const parts = keyTokens(key);
  let role: DateRangeRole | null = null;
  let markerIndex = -1;
  for (let index = 0; index < parts.length; index += 1) {
    const isStart = RANGE_START_TOKENS.has(parts[index]);
    const isEnd = RANGE_END_TOKENS.has(parts[index]);
    if (!isStart && !isEnd) continue;
    // Two markers in one key ("startToDate") name nothing this can be confident about, and a wrong
    // confidence here REFUSES a date rather than merely failing to offer a button. Decline instead.
    if (role) return null;
    role = isStart ? "start" : "end";
    markerIndex = index;
  }
  if (!role) return null;
  return { role, stem: parts.filter((_, index) => index !== markerIndex).join("") };
}

/**
 * The sibling DATE field that is the other end of this field's range, or null when it has none.
 *
 * WHAT THIS PREVENTS is a workshop of negative duration printed on the cover of a DCH report.
 * `stage_definitions.py` declares its ranges as two ordinary DATE fields — `startDate`/`endDate` on
 * the workshop identity entity, `surveyStartDate`/`surveyEndDate` on the baseline survey,
 * `startDate`/`completedDate` on the intervention log — and two independent boxes have no idea about
 * one another. An end date a month BEFORE the start therefore saves happily: both are valid dates, no
 * validator on either side is looking at the pair, and nothing errors anywhere. The report then
 * prints a workshop that finished before it began, and the only person who can tell is a reader who
 * happens to subtract the two.
 *
 * Pairing this way rather than by a registry flag is a deliberate stopgap and the file header owns
 * it. The honest fix is a DATE_RANGE field type, but the backend's own comment is that a new type has
 * to earn its place four times over — web form, Android Composable, validator, report renderer — so
 * inventing one from the web client alone would hand Android a `FieldType` its `when` cannot answer.
 *
 * THE MATCH IS DELIBERATELY NARROW, because unlike the identity-card guess above a wrong answer here
 * has a real cost: it would put a bound on a field that has no partner and silently refuse a date a
 * designer is entitled to enter. So both fields must be DATE, live on the same entity, reduce to the
 * SAME stem once the marker word is removed, name OPPOSITE ends, and the partner must be unique —
 * two candidates mean the guess is not good enough and no bound is applied at all.
 */
export function dateRangePartner(entity: DwEntity, field: DwField): { role: DateRangeRole; partner: DwField } | null {
  if (field.type !== "DATE" || field.deprecated) return null;
  const own = rangeRole(field.key);
  if (!own) return null;
  const opposite: DateRangeRole = own.role === "start" ? "end" : "start";
  const matches = entity.fields.filter((candidate) => {
    if (candidate.key === field.key || candidate.deprecated || candidate.type !== "DATE") return false;
    const other = rangeRole(candidate.key);
    return other !== null && other.role === opposite && other.stem === own.stem;
  });
  return matches.length === 1 ? { role: own.role, partner: matches[0] } : null;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Dimensions measurable from a photograph
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The numeric fields on this entity that hold a LENGTH, in an order the form already renders them in.
 *
 * Read entirely off the declaration — see the file header. Sixteen fields across five entities
 * qualify today (`existingProduct`, `sketch`, `prototype`, `prototypeValidation`, `finalProduct`),
 * which are exactly the five entities in the registry that describe a physical object somebody
 * photographs, and that is not a coincidence: a field with a length unit on an entity with no
 * photograph simply never gets the offer, because {@link offersPhotoMeasure} also needs an image.
 *
 * MONEY and PERCENT are excluded by the type test, and `unit="g"` / `"days"` / `"years"` / `"pieces"`
 * / `"bytes"` by the unit test. A photograph cannot weigh anything, and proposing a centimetre figure
 * into a weight in grams is the exact class of silent, plausible, uncorrectable error this whole
 * feature exists to reduce.
 */
export function measurableLengthFields(entity: DwEntity): { field: DwField; unit: LengthUnit }[] {
  const found: { field: DwField; unit: LengthUnit }[] = [];
  for (const field of entity.fields) {
    if (field.deprecated) continue;
    if (field.type !== "DECIMAL" && field.type !== "INT") continue;
    const unit = field.unit;
    if (!unit) continue;
    // Case-folded because the registry writes "cm" and a future field might write "CM"; trimmed
    // because a stray space in a declaration should not silently remove a field from the list.
    const normalised = unit.trim().toLowerCase();
    if (!Object.prototype.hasOwnProperty.call(LENGTH_UNITS, normalised)) continue;
    found.push({ field, unit: normalised as LengthUnit });
  }
  return found;
}

/**
 * Should this media field offer "Measure a dimension from this photograph"?
 *
 * OFFERED ON EVERY IMAGE FIELD OF A QUALIFYING ENTITY, deliberately, and this is where it parts
 * company with {@link offersIdentityOcr}. The card reader can tell which photograph it wants, because
 * a card looks like a card and the registry names the field accordingly. Nothing can tell which
 * photograph has the ruler in it — that is a fact about what the designer chose to lay beside the
 * object thirty seconds ago — so narrowing the offer to a "likely" image field would hide the feature
 * on precisely the photograph that was taken for it. Two offers on stage 13's prototype entity
 * (`prototypePhotos` and `turntablePhotos`) is the honest cost of not guessing.
 */
export function offersPhotoMeasure(entity: DwEntity, field: DwField): boolean {
  if (field.type !== "IMAGE" && field.type !== "IMAGE_LIST") return false;
  if (field.deprecated) return false;
  return measurableLengthFields(entity).length > 0;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Sketches that can be straightened into a plate
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A FILE field that is where a derived line-art or vector plate belongs.
 *
 * This is the SIXTH role and it guesses, like four of the five above, so the same rule applies: a
 * wrong answer must be cheap. A false positive offers a straightening panel on a file field that
 * holds something else, which writes nothing until a designer presses a button and which they can
 * simply not press. A false negative leaves the ordinary file picker, which still works.
 */
const LINE_ART_KEY = /(lineart|linedrawing|vector|plate|artwork)/;

/**
 * Should this FILE field offer "straighten a photographed sketch into a plate"?
 *
 * TWO CONDITIONS, AND THE SECOND IS THE ONE THAT MATTERS. The field must look like the destination
 * for a plate, AND the entity must actually have an image field to make one FROM. Stage 11's `sketch`
 * entity is the case this was written for: it declares `image` (required, the photograph of the
 * sheet) and `lineArtFile` ("An SVG or vector export, if one was produced"), which is exactly the
 * pairing — a photograph to read and a separate slot to put the derived plate in.
 *
 * THE SEPARATE SLOT IS THE WHOLE REASON THE OFFER IS MADE HERE AND NOT ON THE PHOTOGRAPH. A single
 * IMAGE field REPLACES its value when a new file is attached (`FieldInput`'s MediaField calls
 * `onChange(uploadedIds[uploadedIds.length - 1])` for a non-list field), so a panel that attached a
 * rectified plate to `image` would detach the original photograph from the record — the exact outcome
 * docs/MEDIA_PIPELINE.md §5 refuses. Offering it on the destination field instead means the plate is
 * written where a plate belongs and `image` is never touched at all.
 */
export function offersSketchRectify(entity: DwEntity, field: DwField): boolean {
  if (field.type !== "FILE" || field.deprecated) return false;
  if (!LINE_ART_KEY.test(folded(`${field.key} ${field.label}`))) return false;
  return sketchSourceFields(entity).length > 0;
}

/**
 * The image fields on this entity a plate could be made from, in the order the form renders them.
 *
 * Every non-deprecated image field qualifies, deliberately, for the same reason
 * {@link offersPhotoMeasure} does not try to pick one: nothing here can tell which photograph is of a
 * sheet of paper and which is of the artisan holding it. That is a fact about what somebody pointed a
 * camera at, and the designer choosing from a list of their own photographs is both cheaper and more
 * reliable than any guess this file could make.
 */
export function sketchSourceFields(entity: DwEntity): DwField[] {
  return entity.fields.filter(
    (field) => !field.deprecated && (field.type === "IMAGE" || field.type === "IMAGE_LIST")
  );
}
