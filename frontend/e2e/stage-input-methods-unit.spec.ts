import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { addressListRole } from "@/components/designworkshop/stageFieldRoles";
import { joinNumbered, splitNumbered } from "@/components/forms/NumberedListInput";
import type { DwEntity, DwField } from "@/lib/designWorkshops";

/**
 * THE INPUT-METHOD HALF OF 1:1 WITH THE RECORD PAGES, which is the half nothing was watching.
 *
 * The owner's requirement has two parts. The first — every fact a record page captures has a
 * workshop box — is guarded on the server by `test_reference_carry` and by
 * `test_the_web_carries_the_same_hydration_table`. The second is that "the fields have certain input
 * methods that need to be emulated just as they are", and it had no test on any surface: a stage
 * field whose record-page twin is a validating phone box, a numbered list or a closed district list
 * carried its value perfectly well through a bare `<input type="text">`, so nothing anywhere
 * reported a problem and only the person typing paid.
 *
 * WHAT THIS FILE ASSERTS, and why in two different ways:
 *
 * - THE RULES, EXECUTED. `addressListRole` decides whether a TEXT box becomes a closed list, and it
 *   is the one role in `stageFieldRoles` that can REFUSE an answer — a dropdown cannot be answered
 *   with a name it does not offer. So its match is pinned key by key here, in both directions.
 *   `splitNumbered`/`joinNumbered` decide where a printed bullet ends, which is a three-way contract
 *   with Android and `report_builder`, so the round trip is executed rather than described.
 * - THE MOUNTS, READ. There is no React renderer in this repository's devDependencies (see
 *   `inline-record-host-unit.spec.ts`, which lifts functions out of components for exactly this
 *   reason), so "the workshop mounts the record page's own control rather than a second one" can
 *   only be asserted as a substring. That is weaker than a render, and it is still the assertion that
 *   matters: the failure mode being guarded against is somebody writing a SECOND phone validator or
 *   a SECOND state list, and a second implementation is visible in the source of this file.
 */

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

const FIELD_INPUT = "components/designworkshop/FieldInput.tsx";
const ADDRESS_FIELD = "components/designworkshop/StageAddressField.tsx";
const ROLES = "components/designworkshop/stageFieldRoles.ts";

/** The bundled registry dump — a pure `registry_to_dict()`, so it needs no database to read. */
const SCHEMA = join(ROOT, "..", "android", "app", "src", "main", "assets", "design-workshop-schema.json");

function field(key: string, type: DwField["type"], extra: Partial<DwField> = {}): DwField {
  return { key, label: key, type, tier: "BASIC", required: false, ...extra };
}

function entity(fields: DwField[]): DwEntity {
  return {
    key: "participant",
    name: "DwParticipant",
    cardinality: "COLLECTION",
    title: "Participants",
    description: "",
    parent: "",
    labelField: "name",
    fields
  };
}

/* ────────────────────────────────────────────────────────────────────────────
 * The administrative half of an address
 * ──────────────────────────────────────────────────────────────────────────── */

test("state, district and pincode are recognised under both of the spellings the registry uses", () => {
  const roster = entity([
    field("state", "TEXT"),
    field("district", "TEXT"),
    field("pincode", "TEXT"),
    field("village", "TEXT")
  ]);
  expect(addressListRole(roster, roster.fields[0])?.role).toBe("state");
  expect(addressListRole(roster, roster.fields[1])?.role).toBe("district");
  expect(addressListRole(roster, roster.fields[2])?.role).toBe("pincode");
  // `village` is free text on the record page too — a place name, not a closed list — so it must not
  // be dragged in by proximity to the three that are.
  expect(addressListRole(roster, roster.fields[3])).toBeNull();

  const productRow = entity([
    field("recordState", "TEXT"),
    field("recordDistrict", "TEXT"),
    field("recordPincode", "TEXT")
  ]);
  expect(addressListRole(productRow, productRow.fields[0])?.role).toBe("state");
  expect(addressListRole(productRow, productRow.fields[1])?.role).toBe("district");
  expect(addressListRole(productRow, productRow.fields[2])?.role).toBe("pincode");
});

test("the district knows which box scopes it, and the state knows which box it has to clear", () => {
  // The pair is the unit of meaning. "Districts are only meaningful per state — several names are
  // shared by two states — so the pair is validated together, never apart", which is why changing
  // the state clears the district rather than leaving a Rajasthan district under Uttarakhand.
  const roster = entity([field("state", "TEXT"), field("district", "TEXT")]);
  expect(addressListRole(roster, roster.fields[1])?.stateField?.key).toBe("state");
  expect(addressListRole(roster, roster.fields[0])?.districtField?.key).toBe("district");
});

test("a district with no state beside it stays a text box rather than becoming an unscopeable list", () => {
  // 795 names keyed to a state, and nothing to key them by. A closed list here would offer either
  // everything or nothing, and both are worse than the box the workshop already had.
  const orphan = entity([field("district", "TEXT")]);
  expect(addressListRole(orphan, orphan.fields[0])).toBeNull();
});

test("the match is by exact key, so nothing that merely CONTAINS one of the words is caught", () => {
  /*
   * THE REASON THIS ROLE DOES NOT GUESS, unlike four of the six beside it. Those offer a button; a
   * wrong answer there costs an offer a designer ignores. This one REPLACES a text box with a closed
   * list, so a wrong answer refuses a value somebody is entitled to type — and a dropdown of the 36
   * Indian states over a field about the state of a loom is exactly that.
   */
  const decoys = entity([
    field("stateOfRepair", "TEXT"),
    field("districtNotes", "TEXT"),
    field("pincodeVerified", "TEXT"),
    field("state", "BOOL"),
    field("district", "TEXT", { deprecated: true }),
    field("state", "TEXT", { deprecated: true })
  ]);
  for (const candidate of decoys.fields) {
    expect(addressListRole(decoys, candidate)).toBeNull();
  }
});

test("STANDING TRIPWIRE: the registry names no OTHER field state, district or pincode", () => {
  /*
   * The exact-key list is only safe while it is complete, and completeness is a fact about the
   * registry rather than about this file. Read off the bundled dump — `registry_to_dict()`, no
   * database needed — so a TWELFTH address field, or a `state` field that means something else
   * entirely, fails HERE with the key named instead of silently getting a dropdown or silently not
   * getting one.
   *
   * KEYS AND NOT TYPES, deliberately. `recordState` is a live candidate for being retyped to
   * `ENUM(INDIAN_STATE)` on the server, which would be a BETTER answer than this client-side role
   * (the registry would then validate it and Android would get it from the same asset) and which
   * would take the field out of `addressListRole`'s reach by the type test alone. Pinning the type
   * here would turn that improvement into a failure in a lane that has nothing to do with it. What
   * must not change quietly is the SET of facts that are an administrative address.
   */
  const dump = JSON.parse(readFileSync(SCHEMA, "utf8")) as {
    stages: { key: string; entities: { key: string; fields: { key: string; type: string }[] }[] }[];
  };
  const found: string[] = [];
  for (const stage of dump.stages) {
    for (const declared of stage.entities) {
      for (const spec of declared.fields) {
        if (/^(record)?(state|district|pincode)$/i.test(spec.key)) found.push(`${declared.key}.${spec.key}`);
      }
    }
  }
  expect(found.sort()).toEqual([
    "existingProduct.recordDistrict",
    "existingProduct.recordPincode",
    "existingProduct.recordState",
    "participant.district",
    "participant.pincode",
    "participant.state",
    "tool.recordDistrict",
    "tool.recordPincode",
    "tool.recordState",
    "workshopSetup.district",
    "workshopSetup.state"
  ]);
});

test("the address control reuses the record page's lists, zone check and six-digit rule", () => {
  const source = read(ADDRESS_FIELD);
  // ONE list, not a second copy: `loadAddressReference` is the shared module-level promise the record
  // pages already use, and `OFFLINE_STATES` is the bundled 36 that make the state answerable with no
  // signal. A local list here is the drift that ends with a form offering a name the API refuses.
  expect(source).toContain('from "@/components/forms/LocationFields"');
  expect(source).toContain("loadAddressReference");
  expect(source).toContain("OFFLINE_STATES");
  expect(source).toContain("postalZoneMismatch");
  expect(source).toContain("pincodeValidationError");
  expect(source).toContain("PINCODE_LENGTH");
  // The PIN box gets the record page's treatment: the numeric keypad, the browser's own postal-code
  // autofill, and a strip that means a letter cannot be entered at all.
  expect(source).toContain('inputMode="numeric"');
  expect(source).toContain('autoComplete="postal-code"');
  expect(source).toContain('replace(/\\D/g, "").slice(0, PINCODE_LENGTH)');
  // AND NO MICROPHONE. A recogniser hands back words, and "double oh three" for 003 is a guaranteed
  // correction — the same ground on which FieldInput already withholds it from URL, EMAIL and PHONE.
  expect(source).not.toContain("DictationButton");
  // Changing the state clears the district in ONE commit, so no render ever sees a row naming one
  // state and a district from another.
  expect(source).toContain("onPatch({ [field.key]: next || null, [role.districtField.key]: null })");
  // ...and RE-PICKING THE STATE ALREADY SHOWING is not a change, so it must not run that clear.
  // `SearchableSelect.choose` fires `onChange` unconditionally, so tapping the value already
  // selected — how a designer confirms what a hydrated row says — wiped the district. The record
  // page loses the same gesture and can afford to; a stage entry is a copy nothing re-resolves and
  // the district list needs the network it may not have.
  expect(source).toContain("if (next === own) return;");
  // The zone check is fed the STATE, per role, never this box's own value as a fall-through: a
  // `pincode` field on an entity with no state sibling used to pass the PIN itself as a state name.
  expect(source).toContain('const stateName = role.role === "state" ? own : role.stateField ?');
  expect(source).not.toContain("inputValue(row[role.stateField.key]) : own;");
});

/* ────────────────────────────────────────────────────────────────────────────
 * A bullet list is a list
 * ──────────────────────────────────────────────────────────────────────────── */

test("the newline-joined string survives a round trip through the rows, blank rows dropped", () => {
  /*
   * THE THREE-WAY CONTRACT. This repository's record forms write this string, Android's
   * `NumberedListInput` reads it back into rows, and `report_builder._render_narrative` splits it
   * into the bullets a ministry officer reads. So there is one pair of functions that decides where a
   * point ends, and a round trip has to be the identity on anything already stored.
   */
  const stored = "Soak the cloth before printing\nDry it flat in shade";
  expect(splitNumbered(stored)).toEqual(["Soak the cloth before printing", "Dry it flat in shade"]);
  expect(joinNumbered(splitNumbered(stored))).toBe(stored);
  // A blank row is what "Add point" creates, and it must not reach a printed bullet list.
  expect(joinNumbered(["Soak the cloth", "", "  ", "Dry it flat"])).toBe("Soak the cloth\nDry it flat");
  // An empty value opens as one empty row rather than none, or there is nothing to type into.
  expect(splitNumbered(null)).toEqual([""]);
  expect(splitNumbered("")).toEqual([""]);
});

test("there is ONE numbered-list control, and both surfaces mount it", () => {
  // `DosDontsField` kept the record form's group heading and its FormData mirror and handed the rows
  // themselves over, so the artisan page and the stage form cannot disagree about a bullet boundary.
  const record = read("components/forms/DosDontsField.tsx");
  expect(record).toContain('from "@/components/forms/NumberedListInput"');
  expect(record).toContain("<NumberedPointRows");
  // And the rows are no longer declared in the record form.
  expect(record).not.toContain("function splitNumbered");
  expect(record).not.toContain('aria-label={`Point ${index + 1}`}');

  const stage = read(FIELD_INPUT);
  expect(stage).toContain('from "@/components/forms/NumberedListInput"');
  // Gated on the role the registry already publishes, not on a key list: `reportRole === "BULLETS"`
  // is the same signal the RICH_TEXT branch beside it already reads to open a numbered item.
  expect(stage).toContain('if (field.reportRole === "BULLETS") {');
  expect(stage).toContain("<NumberedListField");
  // AND THE GROUP IS NAMED. The rows are named by their ordinal alone, so an unnamed group put two
  // identical runs of "Point 1"…"Point n" on one stage — `participant.dos` and `participant.donts`
  // sit next to each other. `unlabelled` renders the `<span className="field-label" id={labelId}>`,
  // so the id has to reach the control or that span names nothing at all. `DosDontsField` had
  // already paid for this defect on the record page (a11y-barriers.spec.ts finds its group by name).
  const bullets = stage.slice(stage.indexOf("<NumberedListField"), stage.indexOf("<NumberedListField") + 400);
  expect(bullets).toContain("labelId={labelId}");
  const rows = read("components/forms/NumberedListInput.tsx");
  expect(rows).toContain('role="group" aria-labelledby={labelId}');
  // ...on the string-in/string-out wrapper only. `NumberedPointRows` stays unnamed because
  // `DosDontsField` supplies its own group, and a group inside a group announces the heading twice.
  expect(rows.slice(rows.indexOf("export function NumberedPointRows"), rows.indexOf("export function NumberedListField"))).not.toContain(
    'role="group"'
  );
});

/* ────────────────────────────────────────────────────────────────────────────
 * The two record-form controls the stage form now mounts whole
 * ──────────────────────────────────────────────────────────────────────────── */

test("PHONE is its own branch and renders the record page's phone field, not a second one", () => {
  const source = read(FIELD_INPUT);
  // It used to be folded in with TEXT/URL/EMAIL, where it got one `<input type="tel">` with a
  // `maxLength` and no dial code, no length rule and no inline error — while the same fact typed into
  // the artisan record page two clicks away was checked as it was typed. The handset had already made
  // this call: `FieldRenderer.kt` gives PHONE its own arm and calls `ArtisanPhoneField`.
  expect(source).toContain('from "@/components/forms/PhoneField"');
  expect(source).toContain("<StagePhoneField");
  expect(source).not.toContain('case "PHONE":\n    case "EMAIL":');
  // `unlabelled`, because the first labelable descendant is the dial-code trigger — the exact defect
  // that moved this control from `Field` to `FieldBlock` on the record page.
  expect(source).toContain("return unlabelled(<StagePhoneField");
  // No native constraint validation from the stage form, and no stray `name="phone"` in a row.
  expect(source).toContain("mirror={false}");
});

test("the card reader is offered on the number box itself, and the media reader is left where it was", () => {
  const source = read(FIELD_INPUT);
  /*
   * WHAT THIS CLOSES. `IdentityCardReader` reads images that are ALREADY `MediaFile` rows, so the only
   * web path to card OCR on a roster row was to attach an unmasked PM Vishwakarma card to
   * `participant.photo` — replacing the portrait hydration had just copied in — read the number, and
   * hope the designer then pressed Discard. `IdentityCardCapture` is the record page's control and the
   * never-stored route: the `File` goes into one request body and the input is cleared.
   */
  expect(source).toContain('from "@/components/forms/IdentityCardCapture"');
  expect(source).toContain("<IdentityCardCapture");
  expect(source).toContain('kind="PEHCHAN"');
  // ONE checksum on this client. `aadhaarValidationError` is injected rather than reimplemented, which
  // is the same contract `IdentityCardCapture` states for its own caller.
  expect(source).toContain('import { aadhaarValidationError } from "@/components/forms/AadhaarField";');
  // And the media-side reader stays: it is the right control for a card a designer really did attach,
  // and it is the only one of the two that can offer a real delete.
  expect(source).toContain("<IdentityCardReader");
});

test("the roles file still says how many roles there are, and which of them guess", () => {
  // The header is the only place a reader learns that five of these are inferences and what a wrong
  // answer costs. It has been wrong about the count once already (it said five while six were
  // declared), and a stale count is how the next reader concludes the file is not maintained.
  const source = read(ROLES);
  expect(source).toContain("There are seven");
  expect(source).toContain("FIVE OF THE SEVEN GUESS, AND SAY SO");
  expect(source).toContain("{@link addressListRole} IS THE ONE THAT COULD REFUSE AN ANSWER");
  // The ORDINAL as well as the count, because the count moving is what made the ordinal stale the
  // last time: the header went to seven roles and five guessers while this paragraph still called
  // `measurableLengthFields` "THE FIFTH" and the guessers "the other four". Both halves are pinned
  // so raising the count cannot silently leave a paragraph counting to an older total.
  expect(source).toContain("THE SEVENTH DOES NOT GUESS EITHER");
  expect(source).toContain("other five would like to be");
});
