import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  addressListRole,
  identityNumberField,
  ownWorkshopTitleRole,
  workshopTitleRole
} from "@/components/designworkshop/stageFieldRoles";
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

test("state, district and pincode are recognised under the plain and `record`-prefixed spellings", () => {
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

test("the designer's own state and PIN code are recognised under the third spelling, stage 3's", () => {
  /*
   * `workshopPlan` is the stage-3 cover block, where a designer types their OWN address, and it was
   * the prefix the anchored tripwire below could not express. Until 2026-08-26 `designerPincode` fell
   * through to `FieldInput`'s generic TEXT arm: a dictation button on a six-digit field (which the
   * comment two lines above it said was refused), no numeric keypad, no `autoComplete="postal-code"`,
   * no digits-only strip and no postal-zone check.
   *
   * THE PAIR IS THE UNIT. `designerState` had to be admitted in the same change, because
   * `addressSibling` finds the state BY KEY: a PIN code admitted alone would compile, look wired, and
   * run no zone check at all — a silent regression dressed as a fix.
   */
  const plan = entity([
    field("designerAddress", "TEXT"),
    field("designerCity", "TEXT"),
    field("designerState", "TEXT"),
    field("designerPincode", "TEXT", { format: "PINCODE" })
  ]);
  expect(addressListRole(plan, plan.fields[2])?.role).toBe("state");
  expect(addressListRole(plan, plan.fields[3])?.role).toBe("pincode");
  // The state the zone check is run against — null here would be the silent-no-op case above.
  expect(addressListRole(plan, plan.fields[3])?.stateField?.key).toBe("designerState");
  // Stage 3 declares no designer DISTRICT, so the state box has nothing to clear. `StageAddressField`
  // draws that arm; a null here is the registry's answer, not a missing lookup.
  expect(addressListRole(plan, plan.fields[2])?.districtField).toBeNull();
  // A street address and a town name have no closed list to join, so both stay prose. Recorded as an
  // assertion rather than a comment: this is the decision, not an oversight waiting to be repaired.
  expect(addressListRole(plan, plan.fields[0])).toBeNull();
  expect(addressListRole(plan, plan.fields[1])).toBeNull();
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

test("STANDING TRIPWIRE: every field the registry could mean as part of an address, and what it got", () => {
  /*
   * The exact-key list is only safe while it is complete, and completeness is a fact about the
   * registry rather than about this file. Read off the bundled dump — `registry_to_dict()`, no
   * database needed — so a NEW address field, or a `state` field that means something else entirely,
   * fails HERE with the key named instead of silently getting a dropdown or silently not getting one.
   *
   * THE SWEEP IS WIDER THAN THE SPELLINGS THAT EXIST, AND THE ANCHORED FORM IT REPLACES IS WHY.
   * `/^(record)?(state|district|pincode)$/` could not express a THIRD prefix, so when stage 3's
   * `designerState` and `designerPincode` were declared this test went on passing while the PIN code
   * box fell through to `FieldInput`'s generic TEXT arm — dictation button, alphabetic keyboard, no
   * digits-only strip, no zone check — and the eleven-key expectation below reproduced the asset
   * exactly the whole time. A rule with no prefix in it is the only kind that can fail for a FOURTH
   * one: the registry's own declared `PINCODE` format, plus the key's last camelCase word.
   *
   * SO IT CATCHES NEAR-MISSES ON PURPOSE, and they are listed below with the rest rather than
   * filtered out here, because "somebody looked at this and it is prose" is the fact worth pinning.
   * `designerCity` and `surveyPlace.cityDistrict` are both free text: there is no closed list of
   * Indian towns to offer, and "City / District" is one line a designer writes rather than a district
   * box. The last WORD and not a suffix test, or `monthlyCapacity` would be dragged in by ending in
   * "city".
   *
   * AND EACH ONE CARRIES WHAT IT WAS DECIDED TO BE, which is the half a set alone cannot hold: the
   * set fails when the registry grows a candidate, the role fails when one silently loses or gains a
   * closed list. `designerPincode` was missed by both at once.
   *
   * KEYS AND NOT TYPES, deliberately. `recordState` is a live candidate for being retyped to
   * `ENUM(INDIAN_STATE)` on the server, which would be a BETTER answer than this client-side role
   * (the registry would then validate it and Android would get it from the same asset) and which
   * would take the field out of `addressListRole`'s reach by the type test alone. Pinning the type
   * here would turn that improvement into a failure in a lane that has nothing to do with it. What
   * must not change quietly is the SET of facts that are an administrative address.
   */
  const dump = JSON.parse(readFileSync(SCHEMA, "utf8")) as {
    stages: { key: string; entities: DwEntity[] }[];
  };
  /** The key's last camelCase word: `recordPincode` → "pincode", `monthlyCapacity` → "capacity". */
  const lastWord = (key: string) => (key.split(/(?=[A-Z])/).pop() ?? "").toLowerCase();
  const found: string[] = [];
  for (const stage of dump.stages) {
    for (const declared of stage.entities) {
      for (const spec of declared.fields) {
        const couldBeAnAddress =
          spec.format === "PINCODE" || ["state", "district", "pincode", "city"].includes(lastWord(spec.key));
        if (!couldBeAnAddress) continue;
        found.push(`${declared.key}.${spec.key} → ${addressListRole(declared, spec)?.role ?? "prose"}`);
      }
    }
  }
  expect(found.sort()).toEqual([
    "existingProduct.recordDistrict → district",
    "existingProduct.recordPincode → pincode",
    "existingProduct.recordState → state",
    "participant.district → district",
    "participant.pincode → pincode",
    "participant.state → state",
    "surveyPlace.cityDistrict → prose",
    "tool.recordDistrict → district",
    "tool.recordPincode → pincode",
    "tool.recordState → state",
    "workshopPlan.designerCity → prose",
    "workshopPlan.designerPincode → pincode",
    "workshopPlan.designerState → state",
    "workshopSetup.district → district",
    "workshopSetup.state → state"
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
  expect(source).toContain(
    'import { aadhaarValidationError, isMaskedIdentityNumber } from "@/components/forms/AadhaarField";'
  );
  // And the media-side reader stays: it is the right control for a card a designer really did attach,
  // and it is the only one of the two that can offer a real delete.
  expect(source).toContain("<IdentityCardReader");
});

test("STANDING TRIPWIRE: the Pehchan capture resolves to the card box and not to the Aadhaar box", () => {
  /*
   * DECLARATION ORDER IS LOAD-BEARING ON THIS CLIENT AND NOTHING PINNED IT.
   *
   * `identityNumberField` returns the FIRST non-deprecated TEXT field of the entity whose key or
   * label matches its identity pattern, and `FieldInput` mounts `IdentityCardCapture kind="PEHCHAN"`
   * on exactly that one. Until 2026-08-24 `participant.artisanCardNo` was the only field in the
   * registry that matched, so "first" and "only" were the same fact. The owner then had
   * `participant.aadhaarNumber` added to the same entity, AFTER it — and from that moment the web's
   * Pehchan card reader stayed on the right box because of the ORDER OF TWO LINES in
   * `stage_definitions.py` and nothing else. `FieldInput` and `stage_definitions` both say so in
   * prose, and neither file can enforce it; swapping the two lines would silently point a
   * Pehchan-only recogniser (no checksum, no fixed shape) at a box that now stores only the mask of
   * an Aadhaar number, with no change and no failure on this side.
   *
   * ASKED OF THE REAL FUNCTION AGAINST THE REAL REGISTRY — the bundled dump, no database — because
   * a hand-written list of matching keys would pass for ever while the registry moved underneath it.
   * The assertion is on the KEY the function picks, so a third identity field added anywhere before
   * the card box fails here, named.
   */
  const dump = JSON.parse(readFileSync(SCHEMA, "utf8")) as {
    stages: { entities: { key: string; fields: DwField[] }[] }[];
  };
  const participant = dump.stages
    .flatMap((stage) => stage.entities)
    .find((declared) => declared.key === "participant");
  expect(participant, "the registry no longer declares a participant entity").toBeTruthy();

  const entity = { key: "participant", fields: participant!.fields } as unknown as DwEntity;
  expect(identityNumberField(entity)?.key).toBe("artisanCardNo");

  // And the Aadhaar box IS one of the candidates — this test would pass vacuously if it were not, so
  // the thing that makes the order matter is asserted rather than assumed.
  const candidates = participant!.fields.filter(
    (spec) => !spec.deprecated && spec.type === "TEXT" && identityNumberField({ key: "x", fields: [spec] } as unknown as DwEntity)
  );
  expect(candidates.map((spec) => spec.key)).toEqual(["artisanCardNo", "aadhaarNumber"]);
});

test("dictation cannot write past the bound the box itself enforces on typing", () => {
  const source = read(FIELD_INPUT);
  /*
   * THE DEFECT. `maxLength` on an `<input>`/`<textarea>` constrains TYPING ONLY — a programmatic
   * value ignores it — and `appendDictated` writes programmatically into every TEXT and LONG_TEXT
   * box on the form. So a designer dictating two sentences into `participant.recordMediaNote`
   * (`max_length=200`, and it arrives ALREADY holding a hydrated count) produced a value
   * `coerce_value` refuses; `save_stage` then restores the refused key from `previous`, leaving an
   * error against a box that had silently reverted. `StageMediaNoteField` refuses an over-length
   * SELECTION on screen for exactly that reason while the microphone beside it wrote one anyway.
   *
   * REFUSED, NOT TRUNCATED, for the reason the chooser gives: a sentence cut to fit is a count
   * nobody can tell is wrong, and this one goes into a submitted document.
   */
  const append = source.slice(source.indexOf("const appendDictated"), source.indexOf("const dictationNotice"));
  expect(append, "the composed value is measured against the declared bound").toContain(
    "field.maxLength && composed.length > field.maxLength"
  );
  expect(append, "and nothing is written when it does not fit").toContain("setDictationRefusal({");
  expect(append, "never shorten a dictated phrase to make it fit").not.toContain(".slice(0, field.maxLength");
  // Said on screen, in both wrappers, or a reduced-motion-style silent failure is all the designer
  // gets: the box simply does not change when they speak.
  expect(source.match(/\{dictationNotice\}/g), "the notice renders under a labelled AND an unlabelled control").toHaveLength(2);
});

test("the roles file still says how many roles there are, and which of them guess", () => {
  // The header is the only place a reader learns that five of these are inferences and what a wrong
  // answer costs. It has been wrong about the count once already (it said five while six were
  // declared), and a stale count is how the next reader concludes the file is not maintained.
  const source = read(ROLES);
  expect(source).toContain("There are nine");
  expect(source).toContain("FIVE OF THE NINE GUESS, AND SAY SO");
  // TWO roles can refuse an answer now, not one — `workshopTitleRole` joined `addressListRole` when
  // "Documented at workshop" became a dropdown, and the paragraph naming the exact-key rule has to
  // name both or the next reader adds a third by pattern.
  expect(source).toContain("{@link addressListRole} AND {@link workshopTitleRole} ARE THE TWO THAT COULD REFUSE AN ANSWER");
  // The ORDINAL as well as the count, because the count moving is what made the ordinal stale the
  // last time: the header went to seven roles and five guessers while this paragraph still called
  // `measurableLengthFields` "THE FIFTH" and the guessers "the other four". Both halves are pinned
  // so raising the count cannot silently leave a paragraph counting to an older total.
  expect(source).toContain("THE EIGHTH DOES NOT GUESS EITHER");
  expect(source).toContain("other five would like to be");
  /*
    THE NINTH, ADDED 2026-08-24 WITH "Media on the artisan record", and this test earning its lines
    for the second time: the count moved to nine and the two assertions above failed on the same run,
    which is exactly the drift the block was written to catch. `recordMediaNoteRole` is the SECOND
    role that reads a declaration rather than a key — it reads the hydration table, not `unit` — and
    it is the third that can change what is STORED, so the header has to place it against both
    groups or the next reader files it with the five inferences whose wrong answers are cheap.
  */
  expect(source).toContain("THE NINTH IS THE SECOND ONE THAT READS A DECLARATION");
  expect(source).toContain("{@link recordMediaNoteRole}");
});

/* ────────────────────────────────────────────────────────────────────────────
 * "Documented at workshop" — the second role that can refuse an answer
 * ──────────────────────────────────────────────────────────────────────────── */

const WORKSHOP_FIELD = "components/designworkshop/StageWorkshopField.tsx";
const WORKSHOP_SELECT = "components/forms/WorkshopSelect.tsx";
const SKETCHES_HUB = "app/(protected)/sketches-and-prototypes/page.tsx";
// The chooser's three answers moved out of the page into a module of their own so that a test can
// compare them with each other without mounting React — see `e2e/sketch-chooser-sentences-unit.spec.ts`.
// The offline PANEL is still what this file is about; its words now live one import away.
const SKETCHES_HUB_SENTENCES = "app/(protected)/sketches-and-prototypes/chooserSentences.ts";
const DW_LIST = "app/(protected)/design-workshops/page.tsx";
/*
  THE HANDSET'S HALF, READ AS TEXT FOR WANT OF ANYTHING BETTER.

  The first version of the guard below read `WorkshopSelect.tsx` and the sketches hub and concluded
  that EVERY workshop picker asked for the scoped list. It could not have known: the six Android
  record forms mount one picker of their own, that picker loaded the unscoped list, and nothing
  anywhere asked the picker question of BOTH clients. The two client-parity tests this repository
  does have are about carried DATA — one reads this browser's copy of the hydration table, the other
  reads the handset's copy of the registry — and a permission asked for per request appears in
  neither table. The web was narrowed, the handset was not, and the parity comment in `MainActivity`
  claimed both until somebody read the Kotlin.

  Kotlin cannot be imported here, so these are substring assertions over source, which is the same
  weakness (and the same justification) as the mount assertions this file's header describes: what is
  being guarded against is a call site quietly reverting to the wide list, and that is visible in the
  text. A Gradle test cannot replace it either — it would pin the Kotlin against itself, and the
  thing that has to hold is that the two clients ask the same question of the same endpoint.
*/
const ANDROID = (relative: string) => join("..", "android", "app", "src", "main", "java", "com", "designprototype", "workshop", relative);
const ANDROID_API = ANDROID("data/WorkshopRepositoryApi.kt");
const ANDROID_REPO = ANDROID("data/WorkshopRepository.kt");
const ANDROID_FORMS = ANDROID("MainActivity.kt");

test("the boxes that hold a workshop's TITLE get the list, and the one that holds a title does not", () => {
  // The three hydration targets: `participant.documentedAtWorkshop` ("Documented at workshop"),
  // `workshopSetup.craftDocumentedAtWorkshop` ("Workshop the craft was documented at") and
  // `artisanBaseline.interviewDocumentedAtWorkshop` ("Documented under"). All three are filled from
  // `Workshop.title`, so what a designer types over them is a title nothing can match.
  expect(workshopTitleRole(field("documentedAtWorkshop", "TEXT"))).toBe(true);
  expect(workshopTitleRole(field("craftDocumentedAtWorkshop", "TEXT"))).toBe(true);
  // THE THIRD, added with the sixth reference model. It reached the registry while the key list said
  // two, and because the match is by exact key the only symptom was a prose box where its two
  // siblings are lists. The tripwire below is what named it.
  expect(workshopTitleRole(field("interviewDocumentedAtWorkshop", "TEXT"))).toBe(true);

  /*
    THE TRAP, PINNED — AND STILL PINNED, THOUGH THE BOX IS NO LONGER A BARE TEXT FIELD.

    `workshopSetup.workshopTitle` is the DESIGN workshop's own title — a required cover field a
    designer types, and not a reference to a `Workshop` row at all. A closed dropdown there would
    refuse a workshop that has no `Workshop` record yet, which is most of them on day one. That is
    unchanged and this expectation is unchanged with it.

    WHAT CHANGED ON 2026-08-31, said here because this line is where a reader will come looking. The
    owner asked for the workshop's name to OFFER the names already on record while still accepting a
    new one typed straight in, and the sentence above was being read as a ruling against that too. It
    is not: it refuses a control that can REFUSE AN ANSWER, and a creatable combo cannot — whatever is
    in its box is committable in one keystroke. So a SECOND role, `ownWorkshopTitleRole`, now matches
    this key and mounts `StageWorkshopNameField`, which offers `DesignWorkshop` NAMES (not `Workshop`
    rows) and stores the same plain string this field has always stored. The two roles are disjoint
    and the case below is what holds them apart; folding them into one key list would put the
    reference control on this box, which is the thing the sentence above forbids.
  */
  expect(workshopTitleRole(field("workshopTitle", "TEXT"))).toBe(false);
  expect(workshopTitleRole(field("workshopCode", "TEXT"))).toBe(false);

  // Exact keys and no pattern, for the reason `addressListRole` is: this role can refuse an answer.
  expect(workshopTitleRole(field("documentedAtWorkshopNotes", "TEXT"))).toBe(false);
  expect(workshopTitleRole(field("workshopDocumentedAt", "TEXT"))).toBe(false);
  // A deprecated field keeps whatever it had, and a non-TEXT field is somebody else's branch.
  expect(workshopTitleRole(field("documentedAtWorkshop", "TEXT", { deprecated: true }))).toBe(false);
  expect(workshopTitleRole(field("documentedAtWorkshop", "LONG_TEXT"))).toBe(false);
});

test("the workshop's OWN name gets the creatable combo, and the two title roles stay disjoint", () => {
  // The one key, and only that key. `PROMOTED_COLUMNS` copies `workshopSetup.workshopTitle` onto
  // `DesignWorkshop.title`, so this is the workshop's name and not a reference to anything.
  expect(ownWorkshopTitleRole(field("workshopTitle", "TEXT"))).toBe(true);

  // DISJOINT FROM THE REFERENCE ROLE, in both directions. A key that answered to both would mount two
  // controls for one box, and the branch that wins would be decided by the order of two `if`s in
  // `FieldInput` rather than by anything a reader can see.
  expect(workshopTitleRole(field("workshopTitle", "TEXT"))).toBe(false);
  expect(ownWorkshopTitleRole(field("documentedAtWorkshop", "TEXT"))).toBe(false);
  expect(ownWorkshopTitleRole(field("craftDocumentedAtWorkshop", "TEXT"))).toBe(false);
  expect(ownWorkshopTitleRole(field("interviewDocumentedAtWorkshop", "TEXT"))).toBe(false);

  // Exact key, not a pattern: the workshop CODE sits beside the name on the same entity and is a
  // different fact with a different vocabulary.
  expect(ownWorkshopTitleRole(field("workshopCode", "TEXT"))).toBe(false);
  expect(ownWorkshopTitleRole(field("workshopTitleNotes", "TEXT"))).toBe(false);
  // A deprecated field keeps whatever it had, and a non-TEXT field is somebody else's branch.
  expect(ownWorkshopTitleRole(field("workshopTitle", "TEXT", { deprecated: true }))).toBe(false);
  expect(ownWorkshopTitleRole(field("workshopTitle", "LONG_TEXT"))).toBe(false);
});

test("the creatable combo is the PRIMITIVE's affordance, not a toggle rebuilt at the call site", () => {
  /*
    THE POINT OF THE PROP, ASSERTED WHERE IT CAN BE. Android's `SearchableSelect.kt` has had
    `createAction` since the beginning and the web had no equivalent, so "offer the list and take
    anything typed" was reachable only by hand-rolling an "…or type your own" toggle per call site —
    which is how one question ends up with four wordings and four keyboard routes. The affordance
    lives in `components/ui/SearchableSelect.tsx` and is forwarded by `Dropdown`; the call site
    passes a label and a commit and nothing else.
  */
  const primitive = read("components/ui/SearchableSelect.tsx");
  expect(primitive).toContain("export type SelectCreateAction");
  // It forces the filter box on, because the box is where the typed term comes from. A create action
  // behind `searchable={false}` is an answer nobody can reach.
  expect(primitive).toContain("serverDriven || createAction != null");
  // Under the options and never among them: a row in the list is a row Enter can take while the
  // reader is still typing. Same rule, same words, as the handset's.
  expect(primitive).toContain("else if (createTerm) create();");

  expect(read("components/ui/Dropdown.tsx")).toContain("createAction={createAction}");

  const control = read("components/designworkshop/StageWorkshopNameField.tsx");
  expect(control).toContain("createAction={{ label: workshopNameCreateLabel, onCreate: onChange }}");
  /*
    THE ROW'S WORDS HAVE ONE OWNER, AND ON 2026-08-31 THEY ACQUIRED A SECOND CALLER.

    This assertion named a local `createRowLabel` until the design workshop's own name became a
    creatable combo on the HEADER form as well. The two boxes write the same column — stage 1's
    value is promoted onto `DesignWorkshop.title` and wins the moment stage 1 is saved — so a
    designer meets both, and a second wording of one row is a second row as far as a reader is
    concerned. Exported rather than copied, for the same reason `workshopListNotice` owns the
    sentence under every one of these controls. `dwWorkshopNameCreateLabel` is the Kotlin twin, and
    `DwWorkshopNameFieldTest` pins the quoting on that side.
  */
  expect(control).toContain("export function workshopNameCreateLabel");
  const header = read("components/designworkshop/DesignWorkshopHeaderForm.tsx");
  expect(header).toContain("createAction={{ label: workshopNameCreateLabel, onCreate: commitTitle }}");
  expect(header, "a second wording of the create row").not.toContain("as the name`");
  // The box is the SERVER's, not a client-side filter over one truncated page: a name that sits past
  // the cut would otherwise be answered "No matches", and the next thing a person does after that is
  // type the name again slightly differently — the exact divergence this control was added to end.
  expect(control).toContain("serverQuery={{ value: term, onChange: setTerm, pending }}");
  /*
    R2 IS SATISFIED BY THE BOX, NOT BY DISABLING ANYTHING, and this is the one place the difference
    can be checked. Every other design-workshop picker calls `workshopListStandsDown` and goes grey
    when its list is empty, because there the list IS the only answer. Here the list is a
    convenience over a box that always works, so standing the control down over a dropped connection
    would take away an answer the designer can give — which is R2 read backwards. The CALL is what
    is asserted absent; the header names the function while ruling it out, and a bare substring test
    would fail on the sentence that explains the decision.
  */
  expect(control).not.toContain("workshopListStandsDown(");
  expect(control).toContain("this control never stands down");
});

test("STANDING TRIPWIRE: the registry declares exactly those three workshop-title fields, all TEXT", () => {
  /*
   * The exact-key list is only safe while it is complete, and completeness is a fact about the
   * registry. Read off the bundled dump, so a THIRD field that carries a referenced record's workshop
   * title fails HERE with its key named, instead of silently staying a prose box while its two
   * siblings became lists.
   *
   * The TYPE is pinned here, unlike the address tripwire, and the difference is deliberate: this role
   * exists only because the field is TEXT holding a title. The honest end state is a registry that
   * declares the vocabulary — at which point the role dies rather than being retargeted — so a type
   * change is exactly the event that should stop this test.
   */
  const dump = JSON.parse(readFileSync(SCHEMA, "utf8")) as {
    stages: { key: string; entities: { key: string; fields: { key: string; type: string; label: string }[] }[] }[];
  };
  const found: string[] = [];
  for (const stage of dump.stages) {
    for (const declared of stage.entities) {
      for (const spec of declared.fields) {
        if (/documentedatworkshop$/i.test(spec.key)) found.push(`${declared.key}.${spec.key}:${spec.type}`);
      }
    }
  }
  expect(found.sort()).toEqual([
    // Added 2026-08-24 with `REFERENCE_MODELS["QuestionnaireInterview"]`. It is a THIRD field
    // carrying a referenced record's workshop title, this test is what said so, and the key list in
    // `stageFieldRoles.ts` was widened in the same change rather than the expectation alone.
    "artisanBaseline.interviewDocumentedAtWorkshop:TEXT",
    "participant.documentedAtWorkshop:TEXT",
    "workshopSetup.craftDocumentedAtWorkshop:TEXT"
  ]);
});

test("the workshop-title box mounts the dropdown, keeps the dictation button on the free-text half", () => {
  const source = read(FIELD_INPUT);
  // The role decides, and the control is the one file that knows about workshops — not a second
  // dropdown assembled inline in the TEXT branch.
  expect(source).toContain("if (workshopTitleRole(field))");
  expect(source).toContain("<StageWorkshopField");
  // `unlabelled`, because the control contains a button: a wrapping <label> forwards a stray click
  // into the menu and slams it shut after one pick.
  expect(source.replace(/\s+/g, " ")).toContain("return unlabelled( <StageWorkshopField");

  const control = read(WORKSHOP_FIELD);
  // ONE list, not a second copy of the request: the shared, memoised loader the record page's own
  // picker owns. A second fetch here would be a second answer to "which workshops may I use".
  expect(control).toContain("loadAccessibleWorkshops");
  expect(control).not.toContain('listResource');
  // The escape hatch. The registry says TEXT and a designer cannot create a `Workshop` row, so a
  // closed list would refuse an answer the registry accepts and the designer knows.
  expect(control).toContain("Type a title instead");
  // And the dictation button follows the box rather than the field: dictating a workshop title is
  // the mistyped-title failure this control exists to remove.
  expect(control).toContain("{dictation}");
});

test("(e) EVERY WORKSHOP PICKER ASKS THE SERVER FOR THE SCOPED LIST, AND NOTHING FALLS BACK TO A CACHE", () => {
  /*
   * The owner's requirement is that a designer only ever SEES the workshops they have access to, and
   * `viewable_where` returns `{}` for every signed-in account — reading the repository is open on
   * purpose — so the narrowing has to be asked for, per request, by every control that offers a
   * workshop to save against. A picker that forgets the parameter is indistinguishable from one that
   * has it: it just quietly offers 196 workshops instead of four, and the refusal arrives after the
   * researcher has typed a record.
   */
  const picker = read(WORKSHOP_SELECT);
  expect(picker).toContain('accessibleOnly: "true"');
  // The literal string and not a boolean, because `buildQuery` takes no booleans — it stringifies,
  // and it drops "" as if it were null.
  expect(picker).not.toContain("accessibleOnly: true");

  /*
   * AND THE OFFLINE CACHE IS NOT AN ACCESS LIST. The sketches chooser used to fall back to this
   * browser's IndexedDB copies when the repository could not be reached, which is stale in the
   * PERMISSIVE direction — `draftSummary` keys on `remoteId ?? localId` and hardcodes
   * `deletedAt: null`, so a revoked grant and a soft-deleted workshop both survive in it, with no
   * bound on how old the evidence is. It now renders a panel and no chooser.
   */
  const hub = read(SKETCHES_HUB);
  // The IMPORT and the CALLS, not the words: the header still names both functions, because a
  // reversal explained is worth more than a reversal that left no trace. What must not come back is
  // the wiring.
  expect(hub).not.toContain('from "@/lib/designWorkshopStore"');
  expect(hub).not.toContain("listDrafts()");
  expect(hub).not.toContain("draftSummary)");
  // The panel is still drawn, and still says which of the two things happened. The page mounts the
  // heading by name; the words themselves are pinned next door, which is why both halves are read.
  expect(hub).toContain("CHOOSER_OFFLINE_TITLE");
  expect(read(SKETCHES_HUB_SENTENCES)).toContain("The repository could not be reached");

  /*
   * AND THE SAME RULE ON THE WORKSHOP LIST, WHICH IS THE BIGGER SURFACE OF THE TWO. It is the primary
   * list and the route INTO a workshop, and it prepended every cached draft in this browser whenever
   * the repository could not be reached — `draft.remoteId === null || offline`. What may still be
   * prepended is this device's own unsent work (a workshop started here, or a row holding edits that
   * have not been sent), which is a claim about the outbox rather than about anybody's access.
   */
  // THE STATEMENT AND NOT THE WORDS: the page's own note quotes the condition it used to have (a
  // reversal explained is worth more than a reversal that left no trace), so a bare substring match
  // finds the explanation and passes for ever. The trailing `extras.push` is what pins the branch.
  const list = read(DW_LIST);
  expect(list).not.toContain("|| offline) extras.push");
  expect(list).toContain("|| (offline && unsent)) extras.push");
});

test("(e2) THE ANDROID RECORD FORMS ASK FOR THE SAME SCOPED LIST AS THE WEB", () => {
  /*
   * Trap 6 of this repository's carry invariants, applied to a permission rather than to a field: a
   * change that lands on one client and not the other fails silently, and this one fails in the
   * permissive direction — the handset offering 196 workshops while the browser offers four, with the
   * 403 arriving after a record has been typed in a courtyard.
   */
  const api = read(ANDROID_API);
  // The parameter has to EXIST in the client before any call site can send it. It did not.
  expect(api).toContain('@Query("accessibleOnly")');

  const repo = read(ANDROID_REPO);
  // Two named lists, because the wide one is right for a read surface and wrong for a picker. A
  // single flagged function is what makes a call site's choice invisible at the call site.
  expect(repo).toContain("suspend fun workshopsIMaySubmitTo()");
  expect(repo).toContain("accessibleOnly = true");

  const forms = read(ANDROID_FORMS);
  // The record forms' one picker. `rememberWorkshopPicker` is mounted by all six of them, so this is
  // the single line that decides what a designer is offered on the handset. It reads
  // `workshopsIMaySubmitToPage()` and not the plain `workshopsIMaySubmitTo()` pinned above — a second,
  // later change gave the picker the server's `total` too, so the handset can print the same cap
  // sentence the web control does, and `WorkshopRepository.workshopsIMaySubmitToPage`'s own doc is
  // explicit that this widens WHAT the picker reads, not WHO may see it: the page is still resolved
  // through `workshops(accessibleOnly = true)`, the same scoped call `workshopsIMaySubmitTo()` makes,
  // so the guarantee this test exists to pin — the SCOPED list and nothing wider — still holds.
  expect(forms).toContain("repository.workshopsIMaySubmitToPage()");
  // And nothing in the picker falls back to the wide list "just in case" the scoped one is empty —
  // an empty dropdown is the honest answer and is what the web control does.
  expect(forms).not.toContain("workshopsByOccurrence() }.onSuccess");
});
