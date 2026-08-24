/**
 * `FieldSpec.text_format`, READ OFF THE REGISTRY AND ANSWERED WITH THE RECORD PAGE'S OWN VALIDATOR.
 *
 * ── WHAT THIS FILE IS FOR ─────────────────────────────────────────────────────────────────────
 *
 * A participant row in a stage draws TWO boxes for one artisan's email address and two for their
 * phone number. `StageRecordEmbed` mounts the real `ArtisanForm`, where every record-page validator
 * is present and working; `splitMirroredFields` then puts every hydration target into a second,
 * deliberately editable set rendered by `FieldInput` — and THE REPORT PRINTS THE STAGE COPY. So the
 * governed box and the printed box were different boxes, and the ungoverned one is the one that
 * reached a Development Commissioner's office. The audit's whole finding reduces to that: the
 * validations did not go missing, they were attached to the wrong box.
 *
 * The fix is not a check in `FieldInput`. It is a DECLARATION on the field, enforced by
 * `coerce_value` — the one path this client, the handset and every direct API caller all go
 * through, and the only one that can re-refuse a value that is ALREADY STORED, because
 * `validate_entry` re-coerces every field on every save. This file is the client half: it maps a
 * declared format to the sentence the designer sees, and it does so by CALLING THE RECORD PAGE'S
 * EXISTING VALIDATOR rather than by writing a second one.
 *
 * ── EVERY RULE HERE IS A REUSE, AND THAT IS THE DESIGN ────────────────────────────────────────
 *
 *   AADHAAR  → `AadhaarField.aadhaarValidationError` (12 digits / not leading 0-1 / Verhoeff),
 *              behind the mask predicate below.
 *   PINCODE  → `LocationFields.pincodeValidationError` (6 digits / no leading 0).
 *   EMAIL    → `lib/textFormats.emailValidationError` — extracted out of `ArtisanForm`, which held
 *              it privately and restated it a second time as a `pattern` attribute.
 *   PHONE_IN → `lib/textFormats.phoneValidationError` — extracted out of `PhoneField`, which held
 *              it inline and unexported, which is why it was enforced on the record page and
 *              nowhere else.
 *   PEHCHAN  → below; nothing in today's registry declares it, and §"PEHCHAN" says why.
 *
 * There is exactly one implementation of each rule in this client, and the shared case table at
 * `shared/text-format-vectors.json` — read by this client's `e2e/text-format-parity-unit.spec.ts`,
 * by `backend/tests/test_stage_schema.py` and by the handset's `DwTextFormatParityTest`, and copied
 * by none of them — is what proves it agrees with the server's and the handset's. Parity comments
 * are not a mechanism — email was the control experiment: same intent, three implementations, three
 * different answers, and nobody noticed for as long as the repository has existed.
 *
 * THAT PATH USED TO NAME `android/app/src/test/resources/dw-text-format-cases.json`, WHICH DOES NOT
 * EXIST. It was the handset's own copy, written in this same wave and deleted when the three sides
 * converged on one table — the vectors file records the deletion. A file that claims to name "what
 * proves it agrees" and names nothing is worse than silence: it reads as a mechanism to the next
 * person deciding whether one is needed.
 */

import { aadhaarValidationError } from "@/components/forms/AadhaarField";
import { pincodeValidationError } from "@/components/forms/LocationFields";
import { addressListRole } from "@/components/designworkshop/stageFieldRoles";
import { emailValidationError, phoneValidationError } from "@/lib/textFormats";
import { inputValue, type DwEntity, type DwField, type DwValue } from "@/lib/designWorkshops";

/* ────────────────────────────────────────────────────────────────────────────
 * AADHAAR: the rule has to accept the mask, and `isMaskedIdentityNumber` is NOT how
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Separators a card or a handwritten address prints and a person types, stripped so the value can
 * be measured — the server's `_SEPARATORS`, shared by its `normalize_aadhaar` and `normalize_pincode`.
 *
 * The same set as the server's `_SEPARATORS` and Kotlin's `normalizeAadhaar`: whitespace, the ASCII
 * hyphen, and the six dashes U+2010 to U+2015. LETTERS ARE NOT STRIPPED, deliberately and in all
 * three — a number with a letter in it has to reach the validator and be refused BY NAME, not be
 * silently repaired into a shorter number that then fails a length check the designer cannot
 * explain.
 *
 * SPELLED WITH ESCAPES AND NOT WITH THE CHARACTERS THEMSELVES. The server's copy reads
 * `[\s‐-―-]+`, which is a range whose two ends are visually indistinguishable dashes followed by a
 * third one meant literally — three characters nobody can tell apart in a diff, in a character class
 * where getting the order wrong silently widens the set. The trailing `-` before the `]` is the
 * ASCII hyphen and is literal there by regex rules.
 */
const SEPARATORS = /[\s\u2010-\u2015-]+/g;

/**
 * The two shapes a mask can have: eight X's then the real last four, or twelve X's for a value too
 * short to reveal any.
 *
 * ── WHY THIS AND NOT `isMaskedIdentityNumber`, WHICH ALREADY EXISTS ───────────────────────────
 *
 * `isMaskedIdentityNumber`'s rule is "an X anywhere", and that rule is CORRECT where it is used:
 * `ArtisanUpdate` sees a masked value and the route then DROPS THE KEY ENTIRELY before the write
 * (`drop_masked_identity_numbers`), so a false positive there costs a skipped update. `coerce_value`
 * has no drop — whatever it accepts is what gets STORED and then masked. Under an "X anywhere" rule
 * `"XxamplE 1234"` is a mask, passes, and is stored as `"XXXX XXXX 1234"`: the original defect,
 * unchanged, now sitting behind a validator that reports it as clean.
 *
 * Case-sensitive on purpose. Both mask producers — `mask_aadhaar` and Kotlin `ArtisanIdentity.mask`
 * — emit upper-case X's, and folding case is exactly what lets a run of prose containing "xxxx"
 * through the door.
 *
 * `isMaskedIdentityNumber` KEEPS ITS CURRENT JOB and must never become the accept-arm either: it
 * decides whether `FieldInput` shows the "only the last four digits are stored" sentence, where an
 * over-broad answer costs one hidden hint rather than one stored forgery.
 */
const AADHAAR_MASK_RE = /^X{8}(?:\d{4}|X{4})$/;

/**
 * True when `value` is a mask this application produced — the ACCEPT-ARM's own predicate, exported
 * so the one box that draws a sentence about masking asks the same question the rule asks.
 *
 * `FieldInput` used to gate its "only the last four digits are stored" hint on
 * `isMaskedIdentityNumber` alone, which answers "an X anywhere" and therefore said NOTHING over a
 * value like `"XXXX XXXX 1234"` that a designer had typed or pasted in themselves. That value is
 * accepted (it is byte-for-byte what hydration writes — see `_aadhaar_format_error` for why it has
 * to be, and for why the server cannot tell the two apart), stored unchanged, and printed in the
 * report as though it were the mask of a number somebody had read off a card. The hint's own
 * argument for staying quiet — "over a hydrated row it would be a warning about nothing" — holds
 * only for a WARNING; the box can still state what it is holding, which is true in both cases and
 * is the one thing that distinguishes them for the person looking at it.
 *
 * `isMaskedIdentityNumber` keeps its own job (it decides when the hint about masking-on-save is
 * relevant, where an over-broad answer costs one hidden sentence). This one is the strict shape, so
 * a value like `"XxamplE 1234"` — which that rule calls a mask and this format refuses — gets the
 * red refusal and not a sentence claiming it is a stored mask.
 */
export function isAadhaarMaskValue(value: string | null | undefined): boolean {
  return AADHAAR_MASK_RE.test((value ?? "").replace(SEPARATORS, "").trim());
}

/**
 * The reason `value` is neither a usable Aadhaar number nor a mask this application produced.
 *
 * ── WHY A MASK MUST BE ACCEPTED, WHICH IS NOT A CONVENIENCE ───────────────────────────────────
 *
 * `hydrate_entries` writes `mask_identity_number(...)` into `participant.aadhaarNumber` when a row
 * is hydrated from an artisan record, and `validate_entry` re-coerces every field on every save. A
 * validator that knew only `aadhaarValidationError` would look at `"XXXX XXXX 9012"` and answer
 * "Aadhaar number must be 12 digits — remove any letters or symbols." `save_stage` then restores the
 * key from `previous` — the same mask — so the stored value never changes, and a red error appears
 * on that box on EVERY SAVE, FOREVER, on a row nobody touched, naming a fault the designer cannot
 * fix because the digits are not theirs to see.
 *
 * `mask_aadhaar` is idempotent, so a hydrated mask accepted here is stored byte-identical and the
 * re-saves stay silent.
 *
 * Ported character for character from `aadhaar_or_mask_error` in
 * `backend/app/services/artisan_identity.py` and `ArtisanIdentity.aadhaarOrMaskError` in Kotlin, so
 * that no client shows twelve digits under a promise the server will refuse.
 */
export function aadhaarOrMaskValidationError(value: string | null | undefined): string | null {
  const raw = (value ?? "").trim();
  // Blank is `validate_entry`'s question — `required` — not this one's.
  if (!raw) return null;
  /*
   * A VALUE THAT NORMALISES TO NOTHING IS MEASURED RAW, WHICH IS WHAT THE SERVER DOES AND WHAT THIS
   * FUNCTION USED TO GET WRONG.
   *
   * `_aadhaar_format_error` is `if normalize_aadhaar(text) is None: return aadhaar_error(text)` —
   * the RAW text — and only otherwise consults `aadhaar_or_mask_error`. This file ported the second
   * half and not the first, so a box holding "---" normalised to "" and was answered `null`: no
   * error shown, Save pressed, the server refusing that one field, `save_stage` restoring it from
   * `previous`, and the value snapping back on the next GET with nothing said. That silent revert is
   * the exact failure this whole feature was written to end, rebuilt inside the preview.
   *
   * `measured` is therefore the normalised value where there is one and the raw string where there
   * is not — one expression, the server's two branches.
   */
  const measured = raw.replace(SEPARATORS, "") || raw;
  if (AADHAAR_MASK_RE.test(measured)) return null;
  return aadhaarValidationError(measured);
}

/* ────────────────────────────────────────────────────────────────────────────
 * PEHCHAN: implemented, declared on nothing, and the reason is written down
 * ──────────────────────────────────────────────────────────────────────────── */

/** The bounds `artisan_identity.PEHCHAN_MIN_LENGTH`/`PEHCHAN_MAX_LENGTH` set. */
const PEHCHAN_MIN_LENGTH = 4;
const PEHCHAN_MAX_LENGTH = 32;

/**
 * The reason `value` is not a usable Pehchan card number, or null when it is fine (blank included).
 *
 * Same two checks, same order, same sentences as `pehchan_error` on the server and
 * `ArtisanIdentity.pehchanError` on Android, applied to the value NORMALISED the way
 * `normalize_pehchan` normalises it — see the note in the body.
 *
 * ── NOTHING IN THE REGISTRY DECLARES THIS FORMAT, AND THAT IS THE DECISION, NOT AN OMISSION ────
 *
 * The obvious candidate is `participant.artisanCardNo`, which has no `max_length` and no format
 * check at all today while the record path has `validate_pehchan` returning a named 422. It is NOT
 * declared, for one measured reason: that field carries a mask from hydration too, and
 * `pehchan_error` ACCEPTS `"XXXX XXXX 3456"` — alphanumeric once the separators come off, fourteen
 * characters, inside 4-32. `schemas/records.py` records that this exact acceptance has already
 * stored a mask over a real card number once. So a PEHCHAN format on that box would be a no-op that
 * LOOKS like protection, which is worse than the hole it appears to close.
 *
 * It needs its own mask-aware predicate — the Aadhaar one above will not do, because a Pehchan
 * number has no fixed length to measure a mask against — and an owner call about
 * `IdentityCardCapture kind="PEHCHAN"`, which exists precisely to write FULL card numbers into that
 * box. Exported and tested so that the day the owner decides, the client half is already here.
 */
export function pehchanValidationError(value: string | null | undefined): string | null {
  /*
   * NORMALISED FIRST, WHICH IS WHY THE ALNUM ARM BELOW CAN NEVER FIRE — and that is not dead code,
   * it is the shape of the server's `_pehchan_format_error`, which also calls `pehchan_error` on the
   * normalised value and keeps the arm for a caller that hands it a raw one. Reproduced rather than
   * tightened, because a preview stricter than the enforcement refuses values the repository accepts.
   *
   * IT IS ALSO EXACTLY WHY NO FIELD DECLARES THIS FORMAT. "XXXX XXXX 3456" normalises to twelve
   * alphanumerics, sits comfortably inside 4-32, and there is no checksum to fail — so this function
   * ACCEPTS a mask, and `PEHCHAN` on `participant.artisanCardNo` would be a no-op that looks like
   * protection. See the paragraph below.
   */
  const raw = (value ?? "").trim();
  if (!raw) return null;
  /*
   * NORMALISED WHERE THERE IS ANYTHING LEFT, RAW WHERE THERE IS NOT — `_pehchan_format_error`'s two
   * branches as one expression, for the reason spelled out in `aadhaarOrMaskValidationError`: a box
   * holding only separators normalises to "" and this used to answer `null` while the server
   * refused it, which is a silent revert on the next GET. Measuring the raw string there is also
   * what makes the alnum arm above reachable at all, on both sides, in the same case.
   *
   * ONE RESIDUAL DIFFERENCE, NAMED BECAUSE IT IS NOT PINNED: the server's arm is
   * `str.isalnum()`, which is true of Devanagari and every other script, while this one is ASCII.
   * So a value of nothing but non-ASCII letters gets "letters and digits only" here and the length
   * sentence there. It is left rather than papered over because NO FIELD DECLARES THIS FORMAT (see
   * below), so there is nothing to pin it against and no user standing in front of it; the day
   * `participant.artisanCardNo` declares PEHCHAN, this is the line to fix first and a vector row is
   * how to prove it.
   */
  const measured = raw.replace(/[^A-Za-z0-9]+/g, "").toUpperCase() || raw;
  if (!/^[A-Za-z0-9]+$/.test(measured)) return "Pehchan card number must be letters and digits only.";
  if (measured.length < PEHCHAN_MIN_LENGTH || measured.length > PEHCHAN_MAX_LENGTH) {
    return `Pehchan card number must be between ${PEHCHAN_MIN_LENGTH} and ${PEHCHAN_MAX_LENGTH} characters.`;
  }
  return null;
}

/* ────────────────────────────────────────────────────────────────────────────
 * PINCODE: the rule runs on the NORMALISED value, because the server's does
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The reason `value` is not a usable PIN code, measured after separators come off.
 *
 * ── WHY THE RECORD PAGE'S VALIDATOR IS NOT CALLED DIRECTLY ────────────────────────────────────
 *
 * `pincodeValidationError` refuses anything that is not six bare digits, and that is right where it
 * lives: the record page's PIN code box strips non-digits as you type, so a space cannot get into it.
 * A STAGE box is not that box. `participant.pincode` is a hydration target with `max_length = 10`,
 * and its own comment in `stage_definitions.py` names "768 029" — typed exactly that way by somebody
 * reading an address aloud — as a value already sitting in the column.
 *
 * So `_pincode_format_error` on the server normalises before it checks, and leaves the STORED string
 * as typed. A client preview that skipped the normalisation would be STRICTER THAN THE ENFORCEMENT:
 * it would paint a red error under a value the repository accepts, on a stage a designer is trying
 * to submit, with nothing to do about it — a fourth answer, which is the exact class of divergence
 * this whole file exists to end. Measured against the server's own dispatch, not assumed.
 *
 * ── AND IT IS EXPORTED, BECAUSE THE BOX ITSELF HAS TO CALL IT ─────────────────────────────────
 *
 * `formatShownByControl` says the PIN code box prints this sentence itself, so `FieldInput`'s
 * wrapper stays quiet for that one control — which is only honest if the control computes the SAME
 * answer. It did not: `StageAddressField` called `pincodeValidationError(own)` on the RAW stored
 * value, so a hydrated "768 029" — the value `participant.pincode`'s own comment names as already
 * being in the column, and the vector table pins as accepted — drew a red "Pincode must be 6
 * digits" under a box the server, the handset and `fieldFormatError` all accept. With no
 * `aria-invalid` (the wrapper's is driven by `fieldFormatError`, which said the value was fine) and
 * with `needsAttentionIn` counting zero, so a closed disclosure hid a red line it said nothing
 * about. Two implementations of one rule, two answers, one of them suppressing the other's message:
 * the same defect this file was written to end, in the act of ending it. There is now one function
 * and the control calls it.
 */
export function pincodeOrSpacedValidationError(value: string | null | undefined): string | null {
  const raw = (value ?? "").trim();
  if (!raw) return null;
  // `measured` for the reason given at `aadhaarOrMaskValidationError`: `_pincode_format_error`
  // measures the raw string when normalisation leaves nothing, so a box holding "---" is refused
  // there and used to be accepted here.
  return pincodeValidationError(raw.replace(SEPARATORS, "") || raw);
}

/* ────────────────────────────────────────────────────────────────────────────
 * The dispatch
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The five rules, by the name the server publishes them under.
 *
 * A `Record` and not a `switch` so that the set of formats this build knows is one readable list and
 * the shared case table can be driven straight off its keys.
 */
const FORMATS: Record<string, (value: string) => string | null> = {
  EMAIL: emailValidationError,
  PHONE_IN: phoneValidationError,
  AADHAAR: aadhaarOrMaskValidationError,
  PEHCHAN: pehchanValidationError,
  PINCODE: pincodeOrSpacedValidationError
};

/**
 * The reason the value in this field's box will be refused on save, or null when there is none.
 *
 * ── AN UNKNOWN FORMAT ENFORCES NOTHING, AND THAT DIRECTION IS CHOSEN ON PURPOSE ────────────────
 *
 * The server refuses at install any `TextFormat` member with no entry in its own dispatch — a
 * published flag nothing enforces is the silent failure this whole feature exists to end. This
 * client cannot make the same refusal, because the schema arrives over the wire from a server that
 * may be a release ahead, and the two ways of being wrong are not symmetrical:
 *
 *   - enforce nothing: the designer types a value, the SERVER refuses it, and the refusal lands on
 *     this exact box through `placeStageErrors`. One round trip lost, nothing wrong stored.
 *   - guess a rule: the box refuses an answer the server would have accepted, on a stage a designer
 *     cannot get past, with no way to override it and no signal to ask anybody.
 *
 * So an unrecognised member returns null. `registry_version()` carries `text_format`, so a build
 * that is behind is also a build whose cached drafts have already been invalidated — the designer is
 * on a fresh schema fetch, not an indefinitely stale one.
 */
export function fieldFormatError(field: DwField, value: DwValue | null | undefined): string | null {
  if (!field.format) return null;
  const rule = FORMATS[field.format];
  if (!rule) return null;
  const text = inputValue(value);
  return text.trim() ? rule(text) : null;
}

/**
 * True when the control this field actually mounts ALREADY prints the format's sentence itself, so
 * `FieldInput`'s wrapper must not print it a second time.
 *
 * ── TWO CASES, AND BOTH ARE THE RECORD PAGE'S CONTROL DOING ITS JOB ───────────────────────────
 *
 * 1. **PHONE** — `FieldInput` mounts the record page's own `PhoneField`, which computes the same
 *    rule from the same function and shows it under the box with `role="alert"` as the designer
 *    types. Drawing it again in the wrapper would put two identical red sentences one under the
 *    other, which reads as two separate faults.
 * 2. **The PIN code box** — `StageAddressField`'s `pincode` arm already renders
 *    `pincodeOrSpacedValidationError(own)` for exactly the same reason, beside the
 *    `postalZoneMismatch` warning that is the more interesting of the two.
 *
 * `aria-invalid` and the counts still come from `fieldFormatError` in both cases: what is suppressed
 * is one duplicated paragraph, never the fact that the field is refused.
 *
 * ── SO THE TWO CONTROLS THIS ANSWERS "YES" FOR HAVE ONE OBLIGATION EACH ───────────────────────
 *
 * Suppressing the wrapper's paragraph is a claim about the CONTROL, and a claim it can break in two
 * ways. Both have already been broken, both are now closed, and both are the reason this list is
 * short and will stay short:
 *
 *   * it must compute the message with THE SAME FUNCTION. The PIN code box called
 *     `pincodeValidationError` on the raw stored value, so it drew a red line under "768 029" —
 *     which the server, the handset and `fieldFormatError` all accept — while `aria-invalid` stayed
 *     off and the collapsed-disclosure count stayed at zero.
 *   * it must be able to SEE the stored string. `PhoneField` renders only what
 *     `splitStoredPhone` could parse, so a stored `"not a number"` drew an empty box: the wrapper
 *     was quiet because this function said the control would speak, and the control was quiet
 *     because it had nothing in it. `PhoneField` now measures the value it was HANDED while it is
 *     untouched, and prints the stored string when it cannot show it.
 */
export function formatShownByControl(entity: DwEntity, field: DwField): boolean {
  if (!field.format) return false;
  if (field.type === "PHONE") return true;
  return addressListRole(entity, field)?.role === "pincode";
}

/*
 * ── WHAT USED TO BE HERE: `formatViolationsIn`, DELETED BECAUSE NOTHING CALLED IT ──────────────
 *
 * It counted "how many of these fields hold a value their declared format will refuse", for a
 * COLLAPSED disclosure — and `EntityForm.needsAttentionIn` was already doing that job by calling
 * `fieldFormatError` directly, on the same fields, in the same place. So this file exported a
 * function whose docstring claimed the one job the file's own consumer performs elsewhere, and
 * nobody called it: two answers to "how many need attention", one of them dead, which is precisely
 * the shape of duplication the rest of this file exists to argue against.
 *
 * `needsAttentionIn` merges format violations with server refusals into ONE count per field, which
 * is what a collapsed summary can usefully say; a second exported counter could only ever disagree
 * with it. Its `!field.deprecated` guard is not lost — `EntityForm` now carries it, and it is
 * unreachable there anyway because `formFields()` drops deprecated fields before the groups are
 * built and `validate_entry` skips them server-side.
 *
 * If the owner ever decides a format violation should gate "Save stage", the count to gate on is
 * `needsAttentionIn`'s (or `fieldFormatError` over the whole entry) and the stage page's `save()`
 * is the one place with the whole stage in view.
 */
