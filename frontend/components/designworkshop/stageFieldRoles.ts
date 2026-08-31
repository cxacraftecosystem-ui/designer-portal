/**
 * Which registry fields play a role the generic renderer cannot read off `field.type` alone.
 *
 * There are nine, and all are kept here rather than inline so that the day the registry grows an
 * explicit descriptor for any of them, ONE file changes and the guessing stops.
 *
 * FIVE OF THE NINE GUESS, AND SAY SO. `stage_definitions.py` declares no "this image is an identity
 * card" flag, no "this text box holds a national identity number", no "these two dates are the ends
 * of one range", no "this image is a signature" and no "this file is where a plate belongs", so those
 * five are inferred from the field key. An inference that is wrong there costs an offered button that
 * finds nothing, which a designer ignores. An inference that WROTE something would be a different
 * matter entirely, which is why nothing in the OCR path commits without a human pressing Confirm.
 *
 * {@link addressListRole} AND {@link workshopTitleRole} ARE THE TWO THAT COULD REFUSE AN ANSWER, so
 * neither guesses at all: between them they match eleven exact keys and no pattern — eight parts of
 * an address and three names for the workshop a record was documented at. Their own doc blocks carry
 * the reasoning and the measurement behind those lists, and the figure here is the sum of the two
 * `const`s rather than a separate claim: count it from them, never from this line.
 *
 * THE EIGHTH DOES NOT GUESS EITHER, and the difference is worth naming because it is the shape the
 * other five would like to be. {@link measurableLengthFields} never looks at a key: it asks whether
 * the registry DECLARED the field numeric and DECLARED its unit to be a length. `lengthCm`,
 * `finalWidthCm` and `diameterCm` qualify because they carry `unit="cm"`; `weightG`,
 * `makingTimeDays` and `materialCost` do not, because they carry `unit="g"`, `"days"` and `"INR"` —
 * not because of anything in their names. A registry that adds a `unit="mm"` field gets the
 * behaviour with no change here, and one that adds `spanInHands` gets nothing, which is the correct
 * answer in both directions.
 *
 * THE NINTH IS THE SECOND ONE THAT READS A DECLARATION, and it reads a different one.
 * {@link recordMediaNoteRole} never looks at a field key either: it asks the HYDRATION TABLE which
 * REF field on this entity fills this box, and which source key it fills it from. `participant`'s
 * "Media on the artisan record" qualifies because `DW_REFERENCE_HYDRATION["participant.artisanRef"]`
 * maps `recordMediaNote -> recordMediaNote`; `participant.notes` does not, because the pair that
 * lands there is `notes -> recordNotes` and `notes` is not a media-note source. That is why it can
 * also answer the question the other roles cannot: WHICH RECORD's files to list, which is a fact
 * about the mapping and not about the field's name.
 *
 * It is nevertheless the only one of the nine that carries a NAME LIST, and the list is small and
 * argued: the four lambda keys `_media_note` produces, plus a refusal for the fifth media-note field
 * whose sentence a different function composes. Both are stated at the function.
 */

import { referenceHydrationFor, type DwEntity, type DwField } from "@/lib/designWorkshops";
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

/* ────────────────────────────────────────────────────────────────────────────
 * The administrative half of an address
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Which part of an Indian address a TEXT field holds — state, district or PIN code — or null.
 *
 * WHAT THIS IS FOR. `Location.state` is stored as the canonical name from the closed list in
 * `services/address.py`, which `api/routes/reference.py` also SERVES, so a form's dropdown and the
 * validator can never hold different lists; the column's own docstring says free text there "would
 * split one state across four spellings in every group-by and export, the way craft names did before
 * title-casing". The record page honours that with two dependent `Select`s and a digits-only PIN box.
 * The workshop rendered the same eleven facts as bare text inputs with a dictation button beside
 * them — thirteen boxes, once stage 3's designer pair is counted — and a stage entry is a FROZEN
 * COPY: nothing re-resolves it later, so a district typed under the wrong state is in the ministry's
 * document for good. `DwLocationField.kt` puts the cost in this
 * app's own live data: "fifteen live records that put Rajasthani artisans in West Bengal precisely
 * because a form captured a coordinate and let a human type the administrative half from memory."
 *
 * THE MATCH IS BY EXACT KEY, and unlike the four inferences above it has to be, because this one
 * REFUSES INPUT: a closed dropdown cannot be answered with a name it does not offer. The registry's
 * eleven address fields are spelled exactly two ways — `state`/`district`/`pincode` on `participant`
 * and `workshopSetup`, and the same three under a `record` prefix on `existingProduct` and `tool`,
 * which is the by-value copy of the linked record's STATED address.
 *
 * AND THERE IS A THIRD SPELLING, which this table missed until 2026-08-26: `designerState` and
 * `designerPincode` on stage 3's `workshopPlan`, where a designer types their OWN address. They were
 * added as a PAIR and have to stay one — `addressSibling` finds the state by key, so a PIN code
 * admitted without its state compiles, looks wired, and silently runs no postal-zone check at all,
 * which is a worse state than the plain box it replaced. `workshopPlan` declares no designer
 * DISTRICT, so the state box there has nothing to clear and `StageAddressField` draws it with a null
 * `districtField`; that arm exists and is exercised by the same registry.
 *
 * `designerCity` AND `designerAddress` STAY PROSE, written down here so the omission reads as a
 * decision rather than the same oversight repeated: there is no closed list for either of them to
 * join. A village or town name is free text on the record page too, and a street address is a street
 * address.
 *
 * MEASURED AGAINST THE BUNDLED SCHEMA DUMP, which is a pure `registry_to_dict()`: it holds 635 field
 * entries under 509 distinct keys, and exactly fifteen of them could be an administrative address by
 * name — the eleven above, the two designer boxes, `designerCity`, and `surveyPlace.cityDistrict`,
 * which is one prose line reading "City / District" and not a district box. Those figures are a
 * measurement and they drift, which is why the STANDING TRIPWIRE in
 * `e2e/stage-input-methods-unit.spec.ts` sweeps that set on every run rather than trusting this
 * paragraph: a fourth spelling is a thing the registry can grow and this table cannot notice. A loose
 * regex here would go the other way and eventually put a dropdown of Indian states on a field about
 * the state of a loom, which is the one class of wrong answer this file's header refuses.
 *
 * DISTRICT REQUIRES ITS STATE ON THE SAME ENTITY, for the reason the record page clears one when the
 * other changes: "Districts are only meaningful per state — several names are shared by two states —
 * so the pair is validated together, never apart." A district box with no state box beside it has
 * nothing to be scoped by and is left as the plain text input it is today.
 *
 * THE HONEST END STATE IS A DECLARATION, exactly as this file's header says of `dateRangePartner`: a
 * `vocabulary="INDIAN_STATE"` / `depends_on` pair on `FieldSpec` would make this a read rather than a
 * match, and would reach Android from the same asset. This is the stopgap until the registry carries
 * it, and it is written as one key list in one file so that the day it does, one function dies.
 */
export type AddressListRole = "state" | "district" | "pincode";

/** The eight exact keys, and which part of the address each names. */
const ADDRESS_FIELD_KEYS: Record<string, AddressListRole> = {
  state: "state",
  recordState: "state",
  designerState: "state",
  district: "district",
  recordDistrict: "district",
  pincode: "pincode",
  recordPincode: "pincode",
  // NEVER WITHOUT `designerState` ABOVE IT. See the doc block: a PIN code whose state sibling is
  // missing still gets the numeric keypad and the digits-only strip, and quietly gets no zone check.
  designerPincode: "pincode"
};

/** The sibling address field of a given part, or null when this entity does not declare one. */
function addressSibling(entity: DwEntity, own: DwField, want: AddressListRole): DwField | null {
  return (
    entity.fields.find(
      (candidate) =>
        candidate.key !== own.key &&
        !candidate.deprecated &&
        candidate.type === "TEXT" &&
        ADDRESS_FIELD_KEYS[candidate.key] === want
    ) ?? null
  );
}

export type AddressFieldRole = {
  role: AddressListRole;
  /** The state box this district or PIN code is scoped by. Null on the state box itself. */
  stateField: DwField | null;
  /**
   * The district box a change of state has to CLEAR. Null except on the state box.
   *
   * The record page clears it for a stated reason — "a Rajasthan district left standing under
   * Uttarakhand is the staleness bug wearing a different hat, and the server would reject it anyway
   * with a message about the wrong state" — and a stage entry is worse off than a record here,
   * because only-fill-blanks (invariant 2) clears a mapped scalar on a re-POINT and never on an
   * edit, so a stale district survives every later correction the designer makes.
   */
  districtField: DwField | null;
};

export function addressListRole(entity: DwEntity, field: DwField): AddressFieldRole | null {
  if (field.type !== "TEXT" || field.deprecated) return null;
  const role = ADDRESS_FIELD_KEYS[field.key];
  if (!role) return null;
  const stateField = role === "state" ? null : addressSibling(entity, field, "state");
  // A district with no state to scope it stays a text box — see the doc block.
  if (role === "district" && !stateField) return null;
  const districtField = role === "state" ? addressSibling(entity, field, "district") : null;
  return { role, stateField, districtField };
}

/* ────────────────────────────────────────────────────────────────────────────
 * The workshop a referenced record was documented at
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A TEXT field that holds the TITLE of a `Workshop` record, rather than free prose.
 *
 * WHY THIS EXISTS. The owner asked for "documented at workshop" to be a dropdown, and the exact
 * label they quoted belongs to a registry field, not to the record page: `participant`'s
 * "Documented at workshop" and `workshopSetup`'s "Workshop the craft was documented at" are both
 * TEXT boxes with a dictation button, while the fact they hold is picked from a searchable list of
 * `Workshop` rows two clicks away on the artisan and craft record pages. Both are also
 * hydration targets — `documentedAtWorkshop` is filled from `_rel(r, "workshop", "title")` and
 * `craftDocumentedAtWorkshop` from the craft's — so what lands in them is a title the repository
 * chose, and what a designer types over it is a title the repository has never heard of.
 *
 * A STAGE ENTRY IS A FROZEN COPY THAT NOTHING RE-RESOLVES (invariant 1), which is what makes a
 * mistyped title permanent: "Bagru Block Print Workshop 2025" and "Bagru block-printing workshop,
 * 2025" are the same fortnight to a reader and two different strings to every group-by, and the one
 * in the ministry's document is whichever was typed. This is the same argument
 * {@link addressListRole} makes for the state and district boxes, made about the field beside them.
 *
 * MATCHED BY EXACT KEY, FOR THE SAME REASON THAT ONE IS. A closed list REFUSES input, so a loose
 * pattern here would eventually put a list of workshops on a field about something else. Note in
 * particular that `workshopSetup.workshopTitle` — the design workshop's OWN title, a required cover
 * field a designer types — is NOT in the list and must never be: it is not a reference to a
 * `Workshop` row at all, and a dropdown there would refuse a workshop that has no `Workshop` record
 * yet, which is most of them on the day they start. Measured against the bundled schema dump
 * (`registry_to_dict()`), the three keys below are the only fields in the registry that carry a
 * referenced record's workshop TITLE.
 *
 * ── WHAT CHANGED ON 2026-08-31, AND WHAT DID NOT ────────────────────────────────────────────────
 *
 * The paragraph above is unchanged and is still the ruling: `workshopSetup.workshopTitle` is not in
 * {@link WORKSHOP_TITLE_FIELD_KEYS}, must never be, and does not get this role. What it refused is a
 * CLOSED PICKER OF `Workshop` ROWS, and that refusal stands.
 *
 * It was ALSO being read as a ruling against offering the designer any list at all on that box, and
 * it does not support that. {@link ownWorkshopTitleRole} is a second, separate role over the same
 * key, and it is not this one wearing a different name: it offers `DesignWorkshop` NAMES rather than
 * `Workshop` rows, it stores the same plain string this field has always stored, and — the clause
 * the objection turns on — **it cannot refuse an answer**, because whatever is typed into it is
 * committable in one keystroke. A workshop that has no record anywhere is answered exactly as fast
 * as one with ten years of history, which is the property the sentence above demands and the only
 * one it demands. `StageWorkshopNameField` carries the rest of the argument.
 *
 * THE THIRD KEY ARRIVED WITH THE SIXTH REFERENCE MODEL, AND A STANDING TRIPWIRE IS WHAT FOUND IT.
 * `artisanBaseline.interviewDocumentedAtWorkshop` is filled from `_rel(r, "workshop", "title")` on
 * `REFERENCE_MODELS["QuestionnaireInterview"]` — the same lambda shape as the other two — so it is
 * the same box holding the same kind of string, and it was declared while this list said "two".
 * Because the match is by EXACT KEY it did not fail loudly: the field simply rendered as a prose box
 * with a dictation button while its two siblings rendered a searchable list of `Workshop` rows, and
 * a title typed into it is a title no group-by can match — the whole defect this role exists for,
 * reappearing on the one field nobody had added. `stage-input-methods-unit.spec.ts`'s
 * "STANDING TRIPWIRE" case is what named it; that is what a tripwire read off the registry is for.
 *
 * THE CONTROL IS NOT ACTUALLY CLOSED, and that is the other half of the answer: `StageWorkshopField`
 * offers the list first and keeps a "type a title that is not in the list" escape hatch, because the
 * registry says TEXT and a stage may legitimately record a sitting that was never filed as a
 * `Workshop` row. The dropdown is the default path, not a gate.
 *
 * THE HONEST END STATE IS A DECLARATION, exactly as this file's header says of the others: a
 * `vocabulary="WORKSHOP_TITLE"` on `FieldSpec` would make this a read rather than a match and would
 * reach Android from the same asset. Until then this is one key list in one file, so that the day it
 * lands, one function dies.
 */
const WORKSHOP_TITLE_FIELD_KEYS = new Set([
  "documentedAtWorkshop",
  "craftDocumentedAtWorkshop",
  "interviewDocumentedAtWorkshop"
]);

export function workshopTitleRole(field: DwField): boolean {
  if (field.type !== "TEXT" || field.deprecated) return false;
  return WORKSHOP_TITLE_FIELD_KEYS.has(field.key);
}

/**
 * The design workshop's OWN name — the box `workshopTitleRole` refuses, and for the reason it gives.
 *
 * ── A SECOND ROLE OVER ONE KEY, RATHER THAN A WIDENING OF THE FIRST ─────────────────────────────
 *
 * Kept apart, deliberately, and the two are not variants of each other. {@link workshopTitleRole}
 * answers *"does this box hold a REFERENCED RECORD's workshop title"* — three hydration targets,
 * filled from `_rel(r, "workshop", "title")`, whose control is a scoped list of `Workshop` rows with
 * a written escape hatch. This one answers *"is this box the workshop's own NAME"*, which is a
 * different question about a different table with a different control behind it, and folding them
 * together would put the reference control on this key — the exact thing the paragraph above
 * forbids. One key, one role, one mount each.
 *
 * ── IT IS NOT ONE OF THE ROLES THAT CAN REFUSE AN ANSWER ───────────────────────────────────────
 *
 * This file's header names {@link addressListRole} and {@link workshopTitleRole} as the two roles
 * that can refuse an answer, and this role does not join them: its control offers the names already
 * on record and accepts anything typed, so there is no answer it can withhold. That is why the
 * header's count is untouched by this addition, and it is the whole reason the objection above does
 * not reach it.
 *
 * ── EXACT KEY, AND THE ENTITY IS NOT CHECKED, WHICH IS SAFE HERE AND WOULD NOT BE THERE ─────────
 *
 * `workshopTitle` is declared once in the registry, on `workshopSetup` (stage 1), and it is the
 * field `PROMOTED_COLUMNS` copies onto `DesignWorkshop.title`. Matching the key alone is therefore
 * exact today; the standing tripwire in `e2e/stage-input-methods-unit.spec.ts` reads the bundled
 * dump and fails HERE, with the key named, if a second entity ever declares one. The looser match is
 * affordable precisely because the control refuses nothing: the worst outcome of a false positive is
 * a box that offers some names beside it, where a false positive on the reference role would be a
 * closed list on a field it cannot answer.
 */
export function ownWorkshopTitleRole(field: DwField): boolean {
  if (field.type !== "TEXT" || field.deprecated) return false;
  return field.key === "workshopTitle";
}

/* ────────────────────────────────────────────────────────────────────────────
 * The sentence counting a referenced record's attached files
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The source keys in the hydration table whose value is a `_media_note` sentence.
 *
 * TWO, AND THEY ARE THE LAMBDA KEYS RATHER THAN THE FIELD KEYS. `design_workshops.py` produces
 * `recordMediaNote` on the Artisan, ToolDocumentation, ProductDocumentation and Process lambdas and
 * `craftMediaNote` on the Craft one; the hydration table maps each onto a target of the same name.
 * Matching the SOURCE side is what makes this a read of the declared mapping rather than a guess
 * about a field's name — and it is what would keep working if a future entity received the sentence
 * into a box called something else.
 */
const MEDIA_NOTE_SOURCE_KEYS = new Set(["recordMediaNote", "craftMediaNote"]);

/**
 * What the sentence for one reference model says, and which files the chooser may list.
 *
 * `subject` IS THE WORD IN THE SENTENCE and `linkedRecordType` IS THE `/media` TAG. They are the same
 * five strings today, and they are kept as two fields because they are two different contracts:
 * `subject` has to match the literal `design_workshops.py` passes to `_media_note` or the composed
 * value is not the value hydration wrote, and `linkedRecordType` has to match what
 * `records.media_relation_data` and the record forms write on upload or the fetch returns nothing.
 * One of them changing is not a reason to change the other.
 *
 * `numberedPrefix` IS EMPTY FOR FOUR OF THE FIVE, and that asymmetry is the server's, not a
 * simplification: only the tool's call passes `numbered_prefix="STAGE_STEP_"`, so only the tool's
 * sentence can grow the "of which N document the making in order" clause. Passing it everywhere would
 * make the artisan's composed sentence differ from the artisan's hydrated one on any record whose
 * files happen to be named that way.
 *
 * `Process` IS DELIBERATELY ABSENT, AND THIS IS THE REFUSAL. `traditionalProcess.recordMediaNote`
 * looks like the other four on screen — same label shape, same type, same 200-character bound — and
 * is filled by `_process_media_note`, a different function with a different grammar ("N on the
 * process itself, N across N step(s)"). This control composes `_media_note`'s grammar and nothing
 * else, so offering it there would let a designer replace one grammar with another inside a box the
 * report prints verbatim, and the two spellings of the same fact would then coexist in one archive
 * with nothing recording which was meant. That function is also DORMANT — it returns null for every
 * process, because `MediaFile` has no `processId` and a process's files reach it only through the
 * string tags — so the box there is blank today and a chooser over a fetched process file list would
 * be composing a value hydration has never written and cannot check. The honest control for that
 * field is the plain text box it already has, until the registry or the lambda settles which sentence
 * that box holds.
 *
 * A MODEL THAT IS NOT IN THIS TABLE GETS NOTHING, which is the fail-closed direction: the field stays
 * the text box it is today and the designer loses a chooser, rather than gaining one that lists the
 * wrong record's files.
 */
const MEDIA_NOTE_MODELS: Record<string, { linkedRecordType: string; subject: string; numberedPrefix: string }> = {
  Artisan: { linkedRecordType: "artisan", subject: "artisan", numberedPrefix: "" },
  Craft: { linkedRecordType: "craft", subject: "craft", numberedPrefix: "" },
  ProductDocumentation: { linkedRecordType: "product", subject: "product", numberedPrefix: "" },
  ToolDocumentation: { linkedRecordType: "tool", subject: "tool", numberedPrefix: "STAGE_STEP_" }
};

export type MediaNoteFieldRole = {
  /** The REF field on this entity whose chosen record's files the sentence counts. */
  refField: DwField;
  /** The `/media` `linkedRecordType` tag those files carry. */
  linkedRecordType: string;
  /** The word `_media_note` puts in "Attached to the … record". */
  subject: string;
  /** The filename prefix that marks an ordered making sequence — "" for everything but the tool. */
  numberedPrefix: string;
};

/**
 * Is this TEXT field a `_media_note` sentence, and if so which record's files does it count?
 *
 * FOUR CONDITIONS, AND EVERY ONE OF THEM IS A READ RATHER THAN A MATCH ON THIS FIELD'S NAME:
 *
 *  1. the field is TEXT and not deprecated — a chooser that wrote into a retired box would put a new
 *     value in a field the registry has stopped asking for, and it would still print;
 *  2. some REF field on the SAME entity hydrates this exact field key from a media-note source key,
 *     read out of {@link referenceHydrationFor} so this file never has to know the table's key shape;
 *  3. that match is UNIQUE — two ref fields claiming one box means nothing here can be confident
 *     which record's files the sentence is about, and the same rule and reason as
 *     {@link dateRangePartner}: a wrong answer would list a different person's photographs;
 *  4. the ref field's `refModel` is in {@link MEDIA_NOTE_MODELS}, which is where the Process refusal
 *     lives and where the sentence's own subject word comes from.
 *
 * A WRONG ANSWER HERE IS NOT CHEAP, which is why none of it guesses. This is the third role that can
 * change what is STORED — the other two are {@link addressListRole} and {@link workshopTitleRole} —
 * and unlike those two it can also put a list of somebody's private photographs on screen. Listing
 * the wrong record's files would be both, at once, on a roster row that prints.
 */
export function recordMediaNoteRole(entity: DwEntity, field: DwField): MediaNoteFieldRole | null {
  if (field.type !== "TEXT" || field.deprecated) return null;
  const matches: MediaNoteFieldRole[] = [];
  for (const candidate of entity.fields) {
    if (candidate.type !== "REF" || candidate.deprecated) continue;
    const mapping = referenceHydrationFor(entity, candidate);
    const fills = Object.entries(mapping).some(
      ([sourceKey, targetKey]) => targetKey === field.key && MEDIA_NOTE_SOURCE_KEYS.has(sourceKey)
    );
    if (!fills) continue;
    const model = candidate.refModel ? MEDIA_NOTE_MODELS[candidate.refModel] : undefined;
    // A ref field that fills the box but names a model this table refuses still COUNTS as a match,
    // so it cannot be silently replaced by a second, wronger one. It just contributes no role — the
    // Process refusal has to mean "no control here", not "look for another candidate".
    if (!model) return null;
    matches.push({ refField: candidate, ...model });
  }
  return matches.length === 1 ? matches[0] : null;
}
