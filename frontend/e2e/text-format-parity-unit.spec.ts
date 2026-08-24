import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  fieldFormatError,
  formatShownByControl,
  isAadhaarMaskValue,
  pincodeOrSpacedValidationError
} from "@/components/designworkshop/stageFieldFormats";
import { pincodeValidationError } from "@/components/forms/LocationFields";
import {
  EMAIL_PATTERN,
  EMAIL_RE,
  isPhoneNumberShaped,
  phoneValidationError,
  splitStoredPhone
} from "@/lib/textFormats";
import type { DwEntity, DwField, DwTextFormat } from "@/lib/designWorkshops";

/**
 * THE BROWSER'S ANSWER TO `FieldSpec.text_format`, PROVED EQUAL TO THE HANDSET'S AND THE SERVER'S.
 *
 * ── WHY THIS SPEC IS THE WHOLE VALUE OF THE FEATURE ───────────────────────────────────────────
 *
 * The stage box for a participant's email address is not the box the record page validates. It is a
 * SECOND box for the same fact, drawn by `splitMirroredFields` and mounted outside the record form,
 * and it is the copy `report_builder` prints into a document submitted to a Development
 * Commissioner's office. So "the same rule, applied in three places" is not a tidiness goal here: it
 * is the difference between the governed copy and the printed copy being the same value.
 *
 * ── AND WHY A COMMENT COULD NOT HAVE DONE IT ──────────────────────────────────────────────────
 *
 * Email is the control experiment. Three implementations of one rule, each carrying a comment saying
 * it matched the other two, and all three answers different: this browser applied
 * `[^\s@]+@[^\s@]+\.[^\s@]+` on the RECORD page only, the handset refused a missing `@` and nothing
 * more, and the server checked nothing at all on either path. Nobody noticed for as long as the
 * repository has existed, because a malformed address carries perfectly well — it is not wrong until
 * somebody tries to write to it, months later and in a different building. The same three files
 * carried the same "same checks, same order, same sentences" promise about Aadhaar, and that one
 * happens to be true. Nothing was testing either.
 *
 * ── THE EXPECTATIONS ARE NOT WRITTEN HERE ─────────────────────────────────────────────────────
 *
 * See {@link VECTOR_TABLE} — one file, at the repository root, read by all three sides.
 */

/**
 * THE ONE TABLE, AT THE REPOSITORY ROOT SO THAT NO SIDE OWNS IT.
 *
 * `shared/text-format-vectors.json`, read across the repository and copied nowhere. Its own header
 * records that the clients briefly DID have a second copy under `android/app/src/test/resources/` —
 * written in this same wave, before this file existed — and that converging on one was the first
 * thing the comparison found. Two tables is the failure mode, not the safeguard.
 *
 * ONLY `vectors` IS READ HERE. The table also carries `server_only`, whose rows a client reader must
 * NOT assert: they are values no client CONTROL can produce (a phone box that accepts only digits
 * cannot compose "not a number"), and asserting them would make this spec demand a rule the browser
 * has no way to reach. The direction of that particular disagreement is the safe one — the server
 * refuses and the refusal lands on the box through `placeStageErrors`.
 */
const VECTOR_TABLE = join(__dirname, "..", "..", "shared", "text-format-vectors.json");

type FormatVector = {
  format: string;
  value: string;
  /** The exact sentence the format answers, or null when the value is accepted. */
  error: string | null;
  note?: string;
};

const CASES: FormatVector[] = JSON.parse(readFileSync(VECTOR_TABLE, "utf8")).vectors;

/** A field declaring nothing but the format under test — the only key `fieldFormatError` reads. */
function fieldWith(format: string): DwField {
  return { key: "underTest", label: "Under test", type: "TEXT", format: format as DwTextFormat } as DwField;
}

test("the shared table is not empty and covers every format the client knows", () => {
  // A file that failed to parse, or one somebody emptied while "regenerating" it, would make every
  // assertion below pass vacuously — which is the one way a parity spec can lie.
  expect(CASES.length).toBeGreaterThan(30);
  const covered = new Set(CASES.map((row) => row.format));
  for (const format of ["EMAIL", "PHONE_IN", "AADHAAR", "PEHCHAN", "PINCODE"]) {
    expect(covered.has(format), `no case for ${format}`).toBe(true);
  }
});

test("the browser and the handset agree on the shared vector table", () => {
  const disagreements: string[] = [];
  for (const row of CASES) {
    const got = fieldFormatError(fieldWith(row.format), row.value);
    if (got !== row.error) {
      disagreements.push(
        `format=${row.format || "(none)"} value=${JSON.stringify(row.value)}\n` +
          `  expected: ${JSON.stringify(row.error)}\n` +
          `  got:      ${JSON.stringify(got)}` +
          (row.note ? `\n  the case is here because: ${row.note}` : "")
      );
    }
  }
  // Reported all at once rather than failing on the first: a divergence is usually a whole ARM being
  // wrong, and seeing one row of six tells you far less than seeing the six.
  expect(disagreements.join("\n\n"), "the browser does not match the shared table").toBe("");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The two facts the table cannot express
 * ──────────────────────────────────────────────────────────────────────────── */

test("the email rule and the pattern attribute are one rule, not two literals", () => {
  /*
   * `ArtisanForm` needs the rule twice — once for the inline message, once as the `pattern`
   * attribute that makes the RECORD form's Submit refuse a malformed address natively — and it used
   * to satisfy the second by writing the expression out again by hand, seven hundred lines from the
   * first. Two literals with no test between them is how a rule comes to have two versions, and the
   * browser would then block one set of strings while the red text underneath named another.
   *
   * `pattern` is implicitly anchored by the HTML spec, so the attribute takes the UNANCHORED source
   * and this spec checks the two describe the same language rather than that they are the same
   * string.
   */
  expect(EMAIL_RE.source).toBe(`^${EMAIL_PATTERN}$`);
  expect(new RegExp(`^(?:${EMAIL_PATTERN})$`).test("designer@dch.gov.in")).toBe(true);
  expect(new RegExp(`^(?:${EMAIL_PATTERN})$`).test("designer@dch")).toBe(false);
});

test("a bare ten-digit legacy phone number survives re-coercion", () => {
  /*
   * THE TRAP THIS PINS IS NOT HYPOTHETICAL AND IT IS THE EXPENSIVE KIND. Every phone written before
   * the dial-code picker existed is stored as a bare run of digits, and `coerce_value` re-coerces
   * EVERY field on EVERY save. A `phone_error` that read "no + means no country, therefore invalid"
   * would refuse every one of those rows the next time anybody saved the stage they sit on —
   * `save_stage` then restores the refused key from `previous`, so the value would never change and
   * a permanent red error would stand on a box nobody had touched, naming a fault that is not one.
   *
   * The legacy arm lives in `splitStoredPhone`, which is why that function is what this asserts on
   * as well as the rule: they have to be the same parser or the two will disagree about which of the
   * two length rules applies to a number.
   */
  expect(splitStoredPhone("9876543210")).toEqual({ iso2: "IN", digits: "9876543210" });
  expect(phoneValidationError("9876543210")).toBeNull();
  expect(phoneValidationError("+91 9876543210")).toBeNull();
  expect(phoneValidationError("987654321")).toBe("Enter a 10-digit number for +91.");
});

test("a stored phone the box cannot show is still refused, and named", () => {
  /*
   * THE ROW THAT USED TO SIT UNDER `server_only` SAYING "no client can reach it". That was measured
   * on the CONTROL — `PhoneField`'s box only accepts digits, so nothing typed can compose "not a
   * number" — and not on the DATA, which is what a client is actually handed: nothing has ever
   * validated `Artisan.phone`, `hydrate_entries` copies it into `participant.phone`, and
   * `coerce_value` re-coerces every field on every save. So the server refused it on every save
   * while this rule called it clean, and `PhoneField` drew an EMPTY BOX with `FieldInput`'s notice
   * suppressed (`formatShownByControl` says PHONE speaks for itself) — nothing on the page, and the
   * value silently back on the next GET.
   *
   * `isPhoneNumberShaped` is asserted alongside the rule because it is what `PhoneField` uses to
   * decide the box cannot SHOW the stored string, and the two must answer the same question: the
   * message and the sentence naming the stored value have to appear together or the red line stands
   * over an empty box.
   */
  expect(phoneValidationError("not a number")).toBe("Enter a 10-digit number for +91.");
  expect(isPhoneNumberShaped("not a number")).toBe(false);

  // TEN DIGITS AND STILL REFUSED: the window counts digits, having stripped everything else, so the
  // shape has to be checked too. `max_length = 20` cannot close this one — it is sixteen characters.
  expect(phoneValidationError("abc9876543210def")).toBe("Enter a 10-digit number for +91.");
  expect(isPhoneNumberShaped("abc9876543210def")).toBe(false);

  // A BLANK BOX STAYS QUIET. `PhoneField` composes "" when the digits box is empty, and blank is
  // `required`'s question, never a format's — this is the one case the removed `!digits` guard was
  // right about, and it is now handled by the blank check instead.
  expect(phoneValidationError("")).toBeNull();
  expect(phoneValidationError("   ")).toBeNull();

  // AND NOTHING EITHER PICKER COMPOSES CAN FAIL THE SHAPE. Both write "+CC digits"; the legacy shape
  // is a bare run of digits; separators inside a number are part of the set.
  for (const composed of ["+91 9876543210", "+44 20 7946 0958", "9876543210", "+919876543210", "0674-2345678"]) {
    expect(isPhoneNumberShaped(composed), composed).toBe(true);
  }
});

test("the phone control says what is stored when it cannot show it", () => {
  /*
   * THE RULE BEING RIGHT IS NOT THE PROPERTY THAT MATTERS HERE, WHICH IS WHY THIS READS THE SOURCE.
   *
   * `formatShownByControl` returns true for PHONE, so `FieldInput` prints no format sentence of its
   * own and the whole refusal rests on `PhoneField`. For a stored value the parser cannot read,
   * `PhoneField` renders an empty box — so the refusal has to be computed from the value it was
   * HANDED rather than from what it managed to recompose, and the stored string has to be named, or
   * the designer gets red text under a box that looks blank over a value they cannot see.
   *
   * Asserted against the source because both halves are rendering decisions inside a React
   * component and there is no renderer in devDependencies (the same reason `selectFilter.ts` and
   * `cappedList.ts` exist as pure modules). The pure half — `isPhoneNumberShaped`, which is what
   * decides "cannot show" — is asserted properly above.
   */
  const source = readFileSync(join(__dirname, "..", "components", "forms", "PhoneField.tsx"), "utf8");
  expect(source, "the stored value it cannot show drives the message").toContain(
    "phoneValidationError(unshown || combined)"
  );
  expect(source, "and the value itself is printed, because the box is empty").toContain(
    "What is saved for this field is"
  );
  expect(source, "only while untouched: once they type, the box IS the value").toContain(
    "!edited && stored !== \"\" && !isPhoneNumberShaped(stored)"
  );
});

test("the PIN code box and the wrapper compute one answer, not two", () => {
  /*
   * `formatShownByControl` returns true for the pincode role, so `FieldInput` deliberately stays
   * quiet and lets `StageAddressField` print the sentence. That is only honest while the control
   * computes the SAME answer, and for a while it did not: it called `pincodeValidationError` on the
   * RAW stored value, which refuses anything that is not six bare digits. A hydrated "768 029" —
   * named in `participant.pincode`'s own comment as already being in the column, and pinned as
   * ACCEPTED in the vector table — therefore drew a red line the server, the handset and
   * `fieldFormatError` all disagreed with, with `aria-invalid` absent and the collapsed-disclosure
   * count at zero.
   *
   * So this asserts the divergence directly: the raw-value function still refuses it (it is right
   * where it lives), and the one the control now calls does not.
   */
  expect(pincodeValidationError("768 029")).toBe("Pincode must be 6 digits — remove any letters or symbols.");
  expect(pincodeOrSpacedValidationError("768 029")).toBeNull();
  expect(pincodeOrSpacedValidationError("768-029")).toBeNull();
  // Separators only: refused, because the server's dispatch entry measures the raw string when
  // normalisation leaves nothing. Both clients used to answer null here.
  expect(pincodeOrSpacedValidationError("---")).toBe("Pincode must be 6 digits — remove any letters or symbols.");
  expect(pincodeOrSpacedValidationError("")).toBeNull();
});

test("the mask predicate FieldInput draws its sentence from is the accept-arm's own", () => {
  /*
   * A typed or pasted "XXXX XXXX 1234" is ACCEPTED by the AADHAAR format, and it has to be: it is
   * byte-for-byte what hydration writes, and refusing it would put a permanent red error on every
   * hydrated row over digits the designer cannot see. `_aadhaar_format_error` carries the long
   * version, including why the server cannot tell the two apart — the only discriminator is what the
   * row held BEFORE the save, and `coerce_value` gets one value and nothing else.
   *
   * So the box says what it is holding, and this is the predicate it says it from. It must be the
   * STRICT shape and not `isMaskedIdentityNumber`'s "an X anywhere", or a value the format refuses
   * would get a sentence claiming it is a stored mask.
   */
  expect(isAadhaarMaskValue("XXXX XXXX 1234")).toBe(true);
  expect(isAadhaarMaskValue("XXXXXXXX1234")).toBe(true);
  expect(isAadhaarMaskValue("XXXX XXXX XXXX")).toBe(true);
  expect(isAadhaarMaskValue("XxamplE 1234")).toBe(false);
  expect(isAadhaarMaskValue("234567890124")).toBe(false);
  expect(isAadhaarMaskValue("")).toBe(false);
});

test("a format refusal is drawn once, by the control that owns the box", () => {
  /*
   * PHONE and the PIN code box both mount the RECORD PAGE'S control, and both of those controls
   * already print the same sentence from the same function as the designer types. So `FieldInput`'s
   * wrapper must not print it a second time — two identical red sentences one under the other read
   * as two separate faults.
   *
   * WHAT IS SUPPRESSED IS ONE PARAGRAPH AND NOTHING ELSE. `aria-invalid` and the "N to fix" count on
   * a collapsed disclosure are both driven from `fieldFormatError` regardless of this answer, so a
   * suppressed paragraph can never become a suppressed refusal.
   */
  const participant = {
    key: "participant",
    fields: [
      { key: "phone", label: "Phone", type: "PHONE", format: "PHONE_IN" },
      { key: "pincode", label: "PIN code", type: "TEXT", format: "PINCODE" },
      { key: "state", label: "State", type: "TEXT" },
      { key: "email", label: "Email", type: "EMAIL", format: "EMAIL" },
      { key: "aadhaarNumber", label: "Aadhaar number", type: "TEXT", format: "AADHAAR" }
    ]
  } as unknown as DwEntity;
  const at = (key: string) => participant.fields.find((field) => field.key === key)!;

  expect(formatShownByControl(participant, at("phone"))).toBe(true);
  expect(formatShownByControl(participant, at("pincode"))).toBe(true);
  // Ordinary boxes: the wrapper is the only thing that can say it, so it must.
  expect(formatShownByControl(participant, at("email"))).toBe(false);
  expect(formatShownByControl(participant, at("aadhaarNumber"))).toBe(false);
  // No format declared, so there is nothing to draw and nothing to suppress.
  expect(formatShownByControl(participant, at("state"))).toBe(false);
});

test("a format this build has never heard of enforces nothing", () => {
  /*
   * NOT IN THE SHARED TABLE, AND IT CANNOT BE: the server's `_validate_registry` refuses an
   * unmapped `TextFormat` member at import, so `TextFormat("GSTIN")` raises and the pytest has no
   * way to express this row. It is a fact about the CLIENTS only, which is precisely why it needs a
   * test of its own rather than a comment.
   *
   * A server one release ahead can declare a format this build does not know. The two ways of being
   * wrong are not symmetrical: enforcing nothing costs one round trip and stores nothing wrong,
   * while guessing a rule refuses an answer the server would have taken, on a stage the designer
   * cannot get past. So an unrecognised member returns null, on both clients, deliberately.
   */
  expect(fieldFormatError(fieldWith("GSTIN"), "not a gstin")).toBeNull();
  // And a field declaring nothing is the state of all but seven of the registry's fields: it must
  // stay cheap and silent, and it must not fall into any arm at all.
  expect(fieldFormatError(fieldWith(""), "anything at all")).toBeNull();
});
