import { expect, test } from "@playwright/test";

import {
  hydrateFromReference,
  referenceDisplayHint,
  type DwEntity,
  type DwEntryData,
  type DwField,
  type DwReferenceOption
} from "@/lib/designWorkshops";

/**
 * `hydrateFromReference` and `referenceDisplayHint` on their own — no browser, no server.
 *
 * WHAT THESE DECIDE. A REF field stores an id and nothing else. When a designer picks an artisan,
 * these two functions are what put the artisan's name, village and specialisation into the boxes
 * beside it and what shows the name again when the stage is reopened next week. Get them wrong and
 * a ministry report prints a participant's name in its product column — which is not hypothetical,
 * it is the reason `DW_REFERENCE_HYDRATION` is an explicit table rather than a key-name match: on
 * `existingProduct` the reference's `data.name` is the ARTISAN's name under `artisanRef` and the
 * PRODUCT's name under `productRef`, and the entity has a `name` field of its own meaning the
 * product.
 *
 * WHY IT NEEDED A SPEC ON 2026-08-08. The table was widened that day — `processStep.processRef`
 * went from `{name}` to `{name, notes, productName}` to match the server's. What existed to catch a
 * mistake was `test_the_web_carries_the_same_hydration_table` in
 * `backend/tests/test_reference_registry.py`, which compares the TABLE, and
 * `DwReferenceHydrationTest.kt`, which covers the ANDROID reader. Nothing anywhere exercised the
 * web's RULES — only-fill-blanks, rewrite-on-a-changed-record, seed-a-list-never-replace-it — and
 * those rules are the whole difference between a picker that helps and one that quietly overwrites
 * what a designer typed in the room.
 *
 * PURE ON PURPOSE, so this costs no dev server: `inline-record-create.spec.ts` drives the picker in
 * a browser and needs the stack, and a rule that can be checked in three milliseconds should not
 * wait on it.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Two real shapes out of the registry, cut down
 *
 * The KEYS are the registry's, because the hydration table is keyed by `"entityKey.fieldKey"` and a
 * fixture with invented keys would hydrate nothing and pass every assertion by accident. The FIELD
 * LIST is cut to what each test needs.
 * ──────────────────────────────────────────────────────────────────────────── */

function field(key: string, type: DwField["type"], extra: Partial<DwField> = {}): DwField {
  return { key, label: key, type, tier: "BASIC", required: false, ...extra };
}

/** Stage 3's roster row: `participant.artisanRef` is the widest mapping in the table. */
const PARTICIPANT: DwEntity = {
  key: "participant",
  name: "DwParticipant",
  cardinality: "COLLECTION",
  title: "Participants",
  description: "",
  parent: "",
  labelField: "name",
  fields: [
    field("artisanRef", "REF", { refModel: "Artisan" }),
    field("name", "TEXT"),
    field("localName", "TEXT"),
    field("village", "TEXT"),
    field("specialisation", "TEXT"),
    field("experienceYears", "INT"),
    field("photo", "IMAGE")
  ]
};

const ARTISAN_REF = PARTICIPANT.fields[0];

/** Stage 6's row, the one whose two refs both carry a `name` that means different people. */
const EXISTING_PRODUCT: DwEntity = {
  key: "existingProduct",
  name: "DwExistingProduct",
  cardinality: "COLLECTION",
  title: "Existing products",
  description: "",
  parent: "",
  labelField: "name",
  fields: [
    field("artisanRef", "REF", { refModel: "Artisan" }),
    field("productRef", "REF", { refModel: "Product", refFilterBy: "artisanRef" }),
    field("artisanName", "TEXT"),
    field("name", "TEXT"),
    field("category", "TEXT"),
    field("productPhotos", "IMAGE_LIST")
  ]
};

const PRODUCT_REF = EXISTING_PRODUCT.fields[1];
const PRODUCT_ARTISAN_REF = EXISTING_PRODUCT.fields[0];

function option(id: string, data: Record<string, unknown>): DwReferenceOption {
  return { id, label: id, sublabel: "", data };
}

const SUSHILA = option("artisan-1", {
  name: "Sushila Meher",
  localName: "ସୁଶୀଳା ମେହେର",
  village: "Barpali",
  specialisation: "Bandha tie-dye",
  experienceYears: 22,
  photo: "media-77"
});

const KAILASH = option("artisan-2", {
  name: "Kailash Bhoi",
  village: "Sonepur",
  specialisation: "Warping",
  experienceYears: 9
});

/* ────────────────────────────────────────────────────────────────────────────
 * Filling a fresh row
 * ──────────────────────────────────────────────────────────────────────────── */

test("a chosen artisan fills the boxes the mapping names, and only those", () => {
  const patch = hydrateFromReference(PARTICIPANT, ARTISAN_REF, SUSHILA, {}, "");

  expect(patch).toEqual({
    name: "Sushila Meher",
    localName: "ସୁଶୀଳା ମେହେର",
    village: "Barpali",
    specialisation: "Bandha tie-dye",
    experienceYears: 22,
    // An IMAGE is not a list field on this entity, so it is written as the bare media id rather
    // than wrapped — a media field stores an id, never a URL.
    photo: "media-77"
  });
  // The ref field itself is the caller's to set. A hydration that wrote it back would be the
  // picker arguing with the picker.
  expect(patch).not.toHaveProperty("artisanRef");
});

test("a number arrives as a number and not as its text", () => {
  // `experienceYears` is an INT, and the row is what a stage form renders and what a report table
  // sums. Stringifying it here would put "22" in a numeric column and make every total downstream
  // a string concatenation.
  const patch = hydrateFromReference(PARTICIPANT, ARTISAN_REF, SUSHILA, {}, "");
  expect(typeof patch.experienceYears).toBe("number");
});

test("an unmapped entity or field hydrates nothing rather than guessing", () => {
  // The table fails CLOSED, and that is what makes it safe to let it drift. A missing entry costs
  // the designer one retyped field and the server fills it at save regardless; an entry that is
  // WRONG costs a wrong value nobody can see is wrong.
  const stranger: DwEntity = { ...PARTICIPANT, key: "notInTheTable" };
  expect(hydrateFromReference(stranger, ARTISAN_REF, SUSHILA, {}, "")).toEqual({});
  expect(hydrateFromReference(PARTICIPANT, field("someOtherRef", "REF"), SUSHILA, {}, "")).toEqual({});
});

/* ────────────────────────────────────────────────────────────────────────────
 * The rule that protects what the designer typed
 * ──────────────────────────────────────────────────────────────────────────── */

test("a box the designer already filled is left exactly as they left it", () => {
  // What is in the box is what was typed or accepted IN THE ROOM — a name the artisan prefers, a
  // village the master record has wrong. A picker that reverted every correction the moment it was
  // used would be watched doing it.
  const row: DwEntryData = { name: "Sushila Meher (Sushi)", village: "" };
  const patch = hydrateFromReference(PARTICIPANT, ARTISAN_REF, SUSHILA, row, "");

  expect(patch).not.toHaveProperty("name");
  // An empty string is not "already filled" — `isFilled` trims — so the blank village IS seeded.
  expect(patch.village).toBe("Barpali");
});

test("choosing a DIFFERENT artisan rewrites the fields the last one filled", () => {
  // The one outcome worse than either alternative: the previous artisan's name sitting beside the
  // new artisan's id. The report and the research data then name two different people for one row
  // and nothing in either says which was meant.
  const row: DwEntryData = { name: "Sushila Meher", village: "Barpali", experienceYears: 22 };
  const patch = hydrateFromReference(PARTICIPANT, ARTISAN_REF, KAILASH, row, SUSHILA.id);

  expect(patch.name).toBe("Kailash Bhoi");
  expect(patch.village).toBe("Sonepur");
  expect(patch.experienceYears).toBe(9);
});

test("re-choosing the SAME artisan changes nothing that was edited afterwards", () => {
  // `previousRefId === option.id` is not a replacement, so the only-fill-blanks rule stands. A
  // designer who corrected the spelling and then tapped the picker again to check they had the
  // right person must not lose the correction.
  const row: DwEntryData = { name: "Sushila Meher (Sushi)" };
  const patch = hydrateFromReference(PARTICIPANT, ARTISAN_REF, SUSHILA, row, SUSHILA.id);
  expect(patch).not.toHaveProperty("name");
});

test("a list is seeded when empty and never replaced, even on a changed record", () => {
  // The documented product's photograph is a STARTING POINT. Overwriting a gallery with it would
  // destroy the only copy of the photographs the designer took at the workshop — the field holds
  // media ids, and the ids that are dropped are dropped.
  const first = option("product-1", { name: "Sambalpuri stole", photo: "media-10" });
  const second = option("product-2", { name: "Bandha runner", photo: "media-20" });

  expect(hydrateFromReference(EXISTING_PRODUCT, PRODUCT_REF, first, {}, "").productPhotos).toEqual([
    "media-10"
  ]);

  const shot: DwEntryData = { productPhotos: ["media-shot-by-the-designer"], name: "Sambalpuri stole" };
  const patch = hydrateFromReference(EXISTING_PRODUCT, PRODUCT_REF, second, shot, first.id);
  // The single-value field IS rewritten, because the row now names a different product...
  expect(patch.name).toBe("Bandha runner");
  // ...and the gallery is NOT.
  expect(patch).not.toHaveProperty("productPhotos");
});

test("the two refs on one row write into different boxes", () => {
  // THE MAPPING'S WHOLE REASON FOR EXISTING. Both payloads carry `data.name`; the artisan's goes to
  // `artisanName` and the product's to `name`. A key-name match would write the artisan into the
  // product's name box, the server's only-fill-blanks rule would then refuse to correct it, and a
  // ministry report would print a participant in its product table.
  const artisan = hydrateFromReference(EXISTING_PRODUCT, PRODUCT_ARTISAN_REF, SUSHILA, {}, "");
  expect(artisan).toEqual({ artisanName: "Sushila Meher" });

  const product = hydrateFromReference(
    EXISTING_PRODUCT,
    PRODUCT_REF,
    option("product-1", { name: "Sambalpuri stole", category: "Stole" }),
    {},
    ""
  );
  expect(product.name).toBe("Sambalpuri stole");
  expect(product).not.toHaveProperty("artisanName");
});

/* ────────────────────────────────────────────────────────────────────────────
 * What the payload is allowed to contain
 * ──────────────────────────────────────────────────────────────────────────── */

test("a value that is not a string or a number is skipped rather than stringified", () => {
  // The payload can grow a nested object later. `"[object Object]"` in a participant table is a
  // value that LOOKS answered and is not, which is worse than the empty box the designer would
  // otherwise have filled in themselves.
  const odd = option("artisan-3", {
    name: { first: "Sushila", last: "Meher" },
    village: ["Barpali"],
    specialisation: true,
    experienceYears: 22
  });
  expect(hydrateFromReference(PARTICIPANT, ARTISAN_REF, odd, {}, "")).toEqual({ experienceYears: 22 });
});

test("a GEO target takes the subject pin as the object it is, and refuses half a coordinate", () => {
  /*
   * THE ONE EXCEPTION TO THE RULE ABOVE, and it had to be made explicit because the two surfaces
   * were disagreeing in silence. `_subject_point` carries the pin a researcher dropped on the place
   * itself — the half of invariant 4 that is allowed to cross, the device's own fix never being —
   * as `{lat, lon}`, and the target (`participant.subjectLocation`, and the two `recordSubjectLocation`
   * boxes added on the product and tool rows) is declared GEO. The scalar guard skipped it as "an
   * object the payload grew later", so `hydrate_entries` wrote the pin at SAVE while the browser
   * showed an empty map card until the page was reloaded — and a designer looking at that empty card
   * drops their own pin, which only-fill-blanks then keeps for ever in place of the village's.
   *
   * `geoValue` remains the guard: a coordinate missing its longitude is still refused outright,
   * because half a coordinate on a map is a claim about a place nobody visited.
   */
  const withPin: DwEntity = {
    ...PARTICIPANT,
    fields: [...PARTICIPANT.fields, field("subjectLocation", "GEO")]
  };
  const pinned = option("artisan-5", {
    name: "Sushila Meher",
    subjectLocation: { lat: 21.1938, lon: 83.5945 }
  });
  expect(hydrateFromReference(withPin, ARTISAN_REF, pinned, {}, "")).toEqual({
    name: "Sushila Meher",
    subjectLocation: { lat: 21.1938, lon: 83.5945 }
  });

  const halved = option("artisan-6", { name: "Kailash Bhoi", subjectLocation: { lat: 21.1938 } });
  expect(hydrateFromReference(withPin, ARTISAN_REF, halved, {}, "")).toEqual({ name: "Kailash Bhoi" });
});

test("null, undefined and empty string in the payload write nothing", () => {
  const sparse = option("artisan-4", { name: "Kailash Bhoi", village: "", specialisation: null });
  expect(hydrateFromReference(PARTICIPANT, ARTISAN_REF, sparse, {}, "")).toEqual({
    name: "Kailash Bhoi"
  });
});

test("a target the entity does not declare, or has deprecated, is not written", () => {
  // A client one release behind the server meets a mapping naming a field it has never heard of,
  // and a deprecated field is a dead input the form does not render. Writing either puts a key in
  // the row that nothing displays and the server's validator will report as dropped.
  const trimmed: DwEntity = {
    ...PARTICIPANT,
    fields: [
      ARTISAN_REF,
      field("name", "TEXT"),
      field("village", "TEXT", { deprecated: true })
    ]
  };
  expect(hydrateFromReference(trimmed, ARTISAN_REF, SUSHILA, {}, "")).toEqual({
    name: "Sushila Meher"
  });
});

/* ────────────────────────────────────────────────────────────────────────────
 * Reading the name back out
 * ──────────────────────────────────────────────────────────────────────────── */

test("the picker's trigger shows the name hydration put on the row, not the id", () => {
  // A REF stores an id, so a stage reopened next week has "cm3k…" in the field and no name to show
  // — and the option that would supply one is fifty rows deep behind a search nobody has typed.
  // Rendering the id is the artisan-dropdown bug this repository already shipped once: it asks a
  // designer to confirm "the right artisan" while showing them twenty-five random characters.
  const row = hydrateFromReference(PARTICIPANT, ARTISAN_REF, SUSHILA, {}, "");
  expect(referenceDisplayHint(PARTICIPANT, ARTISAN_REF, row)).toBe("Sushila Meher");
});

test("the hint reads through the SAME mapping the fill used", () => {
  // Which is why it can never point at the wrong box: on `existingProduct` the product's name is
  // under `name` and the artisan's under `artisanName`, and each ref's hint follows its own entry.
  const row: DwEntryData = { name: "Sambalpuri stole", artisanName: "Sushila Meher" };
  expect(referenceDisplayHint(EXISTING_PRODUCT, PRODUCT_REF, row)).toBe("Sambalpuri stole");
  expect(referenceDisplayHint(EXISTING_PRODUCT, PRODUCT_ARTISAN_REF, row)).toBe("Sushila Meher");
});

test("an unmapped field or an unfilled row hints nothing rather than something wrong", () => {
  expect(referenceDisplayHint(PARTICIPANT, field("someOtherRef", "REF"), { name: "x" })).toBe("");
  expect(referenceDisplayHint(PARTICIPANT, ARTISAN_REF, {})).toBe("");
  expect(referenceDisplayHint(PARTICIPANT, ARTISAN_REF, { name: "   " })).toBe("");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The sixth model: a questionnaire sitting, whose carry is mostly COUNTS
 *
 * WHY THIS NEEDS ITS OWN BLOCK. Every other mapping in the table carries prose and dates, so the
 * loop's blank test (`raw === null || raw === undefined || raw === ""`) never had to distinguish
 * "nothing" from "zero". This one carries three integers, and one of them is legitimately 0: a
 * sitting that answered nothing is exactly the citation a reader most needs to see for what it is.
 * A falsy test here would drop it, the box would sit empty at the keyboard, and then the SERVER
 * would write 0 at save — so the value a designer watched fail to appear would appear anyway, on a
 * different surface, which is the drift this whole hand-copied table is guarded against.
 * ──────────────────────────────────────────────────────────────────────────── */

/** Stage 6's new artisan-baseline singleton, cut to the boxes these tests read. */
const ARTISAN_BASELINE: DwEntity = {
  key: "artisanBaseline",
  name: "DwArtisanBaseline",
  cardinality: "SINGLETON",
  title: "Artisan baseline",
  description: "",
  parent: "",
  labelField: "",
  fields: [
    field("interviewRef", "REF", { refModel: "QuestionnaireInterview" }),
    field("interviewTitle", "TEXT"),
    field("interviewDate", "DATE"),
    field("interviewPlace", "TEXT"),
    field("interviewLanguage", "TEXT"),
    field("interviewArtisanCount", "INT"),
    field("interviewSectionsCovered", "INT"),
    field("interviewQuestionsAnswered", "INT"),
    field("interviewLastAnsweredOn", "DATE"),
    field("interviewMediaNote", "TEXT"),
    field("interviewDocumentedOn", "DATE"),
    field("interviewDocumentedAtWorkshop", "TEXT")
  ]
};

const INTERVIEW_REF = ARTISAN_BASELINE.fields[0];

const SITTING = option("interview-1", {
  interviewTitle: "Barpali weavers, group 2",
  interviewDate: "2026-03-14",
  interviewPlace: "Barpali",
  interviewLanguage: "Odia",
  interviewArtisanCount: 6,
  interviewSectionsCovered: 9,
  interviewQuestionsAnswered: 84,
  interviewLastAnsweredOn: "2026-03-15",
  interviewMediaNote: "Attached to the interview record: 1 photograph, 1 audio note.",
  interviewDocumentedOn: "2026-03-16",
  interviewDocumentedAtWorkshop: "Sambalpuri Ikat cluster survey, Barpali"
});

test("a chosen questionnaire interview fills the citation and carries no answer", () => {
  const patch = hydrateFromReference(ARTISAN_BASELINE, INTERVIEW_REF, SITTING, {}, "");

  expect(patch).toEqual({
    interviewTitle: "Barpali weavers, group 2",
    interviewDate: "2026-03-14",
    interviewPlace: "Barpali",
    interviewLanguage: "Odia",
    interviewArtisanCount: 6,
    interviewSectionsCovered: 9,
    interviewQuestionsAnswered: 84,
    interviewLastAnsweredOn: "2026-03-15",
    interviewMediaNote: "Attached to the interview record: 1 photograph, 1 audio note.",
    interviewDocumentedOn: "2026-03-16",
    interviewDocumentedAtWorkshop: "Sambalpuri Ikat cluster survey, Barpali"
  });

  // ELEVEN KEYS AND NOT ONE MORE, which is the assertion rather than a side effect of `toEqual`.
  // The server's payload for this model is composed from a lambda that reads the responses relation,
  // and the answers themselves are LOADED to be counted. A key that ever appeared beside these —
  // a sample answer, a prompt, an artisan name, the `artisanSetKey` — would be written onto the
  // entry by this loop, permanently, because hydration is never re-resolved.
  expect(Object.keys(patch)).toHaveLength(11);
});

test("a sitting that answered nothing writes zero, not nothing", () => {
  // `0` is a statement about the evidence; a blank is the absence of one. The server writes 0 for
  // the same three keys (`value in (None, "")` is False for it), so a falsy test here would put the
  // two surfaces permanently out of step on the most citable fact in the block.
  const empty = option("interview-2", {
    interviewTitle: "Sonepur weavers, first visit",
    interviewArtisanCount: 0,
    interviewSectionsCovered: 0,
    interviewQuestionsAnswered: 0
  });
  expect(hydrateFromReference(ARTISAN_BASELINE, INTERVIEW_REF, empty, {}, "")).toEqual({
    interviewTitle: "Sonepur weavers, first visit",
    interviewArtisanCount: 0,
    interviewSectionsCovered: 0,
    interviewQuestionsAnswered: 0
  });
});

test("re-pointing at a thinner sitting clears what the new one cannot answer", () => {
  // The clearing rule, on the model where leaving a stale value is worst: 84 questions answered
  // beside a different sitting's title is a citation that cites nothing, and only-fill-blanks would
  // refuse to correct it at save.
  const row: DwEntryData = hydrateFromReference(ARTISAN_BASELINE, INTERVIEW_REF, SITTING, {}, "");
  const thinner = option("interview-3", { interviewTitle: "Barpali weavers, group 3" });
  const patch = hydrateFromReference(ARTISAN_BASELINE, INTERVIEW_REF, thinner, row, "interview-1");

  expect(patch.interviewTitle).toBe("Barpali weavers, group 3");
  expect(patch.interviewQuestionsAnswered).toBeNull();
  expect(patch.interviewArtisanCount).toBeNull();
  expect(patch.interviewMediaNote).toBeNull();
});

test("the picker's trigger shows the sitting's title, not the interview id", () => {
  const row = hydrateFromReference(ARTISAN_BASELINE, INTERVIEW_REF, SITTING, {}, "");
  expect(referenceDisplayHint(ARTISAN_BASELINE, INTERVIEW_REF, row)).toBe("Barpali weavers, group 2");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The internal carry — a source that is another row of the same workshop
 *
 * ADDED 2026-09-03 with the first two mappings whose `refModel` is a `Dw…` entity of this very
 * registry rather than a record in another table. The RULES below are the rules already exercised
 * above, and that is the assertion: `hydrateFromReference` has no internal branch, does not know
 * that one option's `data` came from a `DwStageEntry` rather than from an `Artisan`, and must not
 * grow a branch for it. What these pin is that the two mapping ENTRIES are present, land in the
 * right boxes, and behave the same way — because the one way this table can hurt anybody is by
 * being WRONG rather than by being absent (a missing entry costs one retyped box that the server
 * fills at save; a wrong one writes a value nobody can see is wrong).
 *
 * WHY THE ENTRIES ARE PINNED HERE AND NOT ONLY IN THE BACKEND PARITY TEST.
 * `test_the_web_carries_the_same_hydration_table` asserts equality with the server and is the
 * authority on WHAT the table holds; it runs in the backend suite, on a different job. These run
 * wherever the web's specs run and fail with the box named, which is what a person editing this
 * file needs.
 * ──────────────────────────────────────────────────────────────────────────── */

/** Stage 16's catalogue row. `prototypeRef` is the widest internal mapping in the table. */
const FINAL_PRODUCT: DwEntity = {
  key: "finalProduct",
  name: "DwFinalProduct",
  cardinality: "COLLECTION",
  title: "Final products",
  description: "",
  parent: "",
  labelField: "name",
  fields: [
    field("productCode", "TEXT"),
    field("name", "TEXT"),
    field("prototypeRef", "REF", { refModel: "DwPrototype" }),
    field("finalPhotos", "IMAGE_LIST"),
    field("lengthCm", "DECIMAL"),
    field("widthCm", "DECIMAL"),
    field("heightCm", "DECIMAL"),
    field("weightG", "DECIMAL"),
    field("dimensionsNote", "TEXT"),
    field("materials", "TAGS"),
    field("technique", "TEXT"),
    field("makingProcess", "RICH_TEXT"),
    field("makingTimeDays", "DECIMAL")
  ]
};

const PROTOTYPE_REF = FINAL_PRODUCT.fields[2];

/** Stage 15's validation row, whose three "Final …" boxes are the whole of its mapping. */
const PROTOTYPE_VALIDATION: DwEntity = {
  key: "prototypeValidation",
  name: "DwPrototypeValidation",
  cardinality: "COLLECTION",
  title: "Validation",
  description: "",
  parent: "",
  labelField: "prototypeRef",
  fields: [
    field("prototypeRef", "REF", { refModel: "DwPrototype" }),
    field("decision", "ENUM"),
    field("reason", "RICH_TEXT"),
    field("approvedBy", "TEXT"),
    field("finalLengthCm", "DECIMAL"),
    field("finalWidthCm", "DECIMAL"),
    field("finalHeightCm", "DECIMAL")
  ]
};

const VALIDATION_PROTOTYPE_REF = PROTOTYPE_VALIDATION.fields[0];

/**
 * A stage-13 prototype as `_in_record_options` now serialises one.
 *
 * THE KEYS ARE THE PROTOTYPE ENTITY'S OWN, which is the whole difference from an external option: a
 * workshop row's `data` IS its display projection, so there is no `REFERENCE_MODELS` data lambda in
 * between and no second vocabulary to diverge from. The server narrows the payload to the nine keys
 * some mapping names (`_internal_carry_keys`), which is why `prototypeCode`, the photo gallery and
 * the two cost heads are absent here exactly as they are on the wire.
 */
const PROTOTYPE = option("proto-1", {
  name: "Bandha tote, wide gusset",
  materials: ["Cotton", "Jute webbing"],
  lengthCm: 38,
  widthCm: 12,
  heightCm: 34,
  weightG: 420,
  dimensionsNote: "38 x 12 x 34, handle drop 24",
  makingTimeDays: 3.5
});

test("a chosen prototype fills the catalogue row's boxes and nothing else", () => {
  const patch = hydrateFromReference(FINAL_PRODUCT, PROTOTYPE_REF, PROTOTYPE, {}, "");

  expect(patch).toEqual({
    name: "Bandha tote, wide gusset",
    materials: ["Cotton", "Jute webbing"],
    lengthCm: 38,
    widthCm: 12,
    heightCm: 34,
    weightG: 420,
    dimensionsNote: "38 x 12 x 34, handle drop 24",
    makingTimeDays: 3.5
  });
  // THE CODE IS NOT THE PROTOTYPE'S. Two identifiers with two lifetimes: a prototype tag is printed
  // the afternoon the piece is made, a product code goes on a catalogue.
  expect(patch).not.toHaveProperty("productCode");
  // THE PLATE IS NOT THE WORKING SHOT. Both rows live in ONE workshop, so a carry would be the SAME
  // media ids under two headings, and the report's image pass dedupes by media id.
  expect(patch).not.toHaveProperty("finalPhotos");
  // `technique` has no source: `prototype.toolsUsed` is a list of tools, not a named operation.
  expect(patch).not.toHaveProperty("technique");
});

test("a measurement the designer took off the finished piece is not reverted", () => {
  // ONLY-FILL-BLANKS, ON THE ONE SOURCE A DESIGNER OWNS BOTH ENDS OF. The catalogue measurement is
  // taken off the finished piece; the prototype's is taken at making. Where the two differ the
  // designer has measured, and a picker that reverted a measurement would be watched doing it.
  const row: DwEntryData = { lengthCm: 39.5, materials: ["Cotton"] };
  const patch = hydrateFromReference(FINAL_PRODUCT, PROTOTYPE_REF, PROTOTYPE, row, "");

  expect(patch).not.toHaveProperty("lengthCm");
  // A TAGS box already holding one tag is FILLED, so it is not seeded either: a list is never
  // replaced, only seeded when empty.
  expect(patch).not.toHaveProperty("materials");
  expect(patch.widthCm).toBe(12);
});

test("a carried tag is stripped the way PYTHON strips, not the way trim() does", () => {
  /*
    `coerce_value`'s multi arm is `[str(v).strip() for v in raw if str(v).strip()]`, and `trim()` is
    not that set: U+0085 and U+001C to U+001F are whitespace to Python and not to JavaScript. A
    material pasted with a next-line on it would have stayed a distinct tag in the browser and become
    the plain word on the server and the handset — one row disagreeing with itself across three
    surfaces about what a piece is made of, with nothing on any screen to show why.

    `lib/marketAnalysis.ts` exports `pyStrip` to end exactly this divergence and says so in its
    docstring; this arm was the last `String(item).trim()` claiming byte-parity with a `.strip()`
    (2026-09-03). The characters are named and built rather than typed, for the reason below.
  */
  // NAMED AND BUILT RATHER THAN TYPED. An invisible U+0085 sitting inside a string literal here
  // would be unreadable in every diff and every review of this spec — which is the defect the case
  // itself is made of. `android/app/src/test/resources/dw-analysis-cases.json` spells the same
  // characters out for the same reason, and `lib/marketAnalysis.ts` says why in its own words.
  const NEL = String.fromCharCode(0x85); // U+0085 NEXT LINE
  const FS = String.fromCharCode(0x1c); // U+001C FILE SEPARATOR
  const US = String.fromCharCode(0x1f); // U+001F UNIT SEPARATOR
  const padded = option("proto-4", {
    name: "Bandha tote",
    materials: [NEL + "Cotton" + NEL, FS + "Jute webbing" + US, "   ", NEL]
  });
  const patch = hydrateFromReference(FINAL_PRODUCT, PROTOTYPE_REF, padded, {}, "");
  expect(patch.materials).toEqual(["Cotton", "Jute webbing"]);
  // The emptiness test reads the STRIPPED token too, or a tag of pure padding survives as `""` and
  // the box shows a blank chip nobody typed.
  expect(patch.materials).not.toContain("");
});

test("a rich-text narrative is left for the server rather than flattened at the keyboard", () => {
  /*
   * `processSummary -> makingProcess` is the one pair this surface skips, and skipping is correct.
   * `stringifyRefValue` accepts only the two JSON scalars a display box can legitimately hold, and a
   * RICH_TEXT document is neither — `"[object Object]"` in a narrative box is a value that LOOKS
   * answered and is not. The server writes the normalised document at save through `coerce_value`'s
   * RICH_TEXT arm, exactly as it does for a typed one.
   */
  const withProse = option("proto-2", {
    name: "Bandha tote",
    processSummary: { blocks: [{ type: "paragraph", text: "Warped on the pit loom." }] }
  });
  expect(hydrateFromReference(FINAL_PRODUCT, PROTOTYPE_REF, withProse, {}, "")).toEqual({
    name: "Bandha tote"
  });
});

test("re-pointing at a different prototype rewrites the boxes the last one filled", () => {
  // The internal carry gets the SAME clearing rule, and it matters more here than anywhere: the
  // prototypes table and the final-products table of one report describe one physical object, so a
  // catalogue row holding prototype A's dimensions beside prototype B's id is a document that
  // measures two different things under one heading.
  const row: DwEntryData = hydrateFromReference(FINAL_PRODUCT, PROTOTYPE_REF, PROTOTYPE, {}, "");
  const thinner = option("proto-3", { name: "Bandha runner", lengthCm: 120 });
  const patch = hydrateFromReference(FINAL_PRODUCT, PROTOTYPE_REF, thinner, row, "proto-1");

  expect(patch.name).toBe("Bandha runner");
  expect(patch.lengthCm).toBe(120);
  // What the new prototype cannot answer is CLEARED rather than left holding the old one's answer.
  expect(patch.weightG).toBeNull();
  expect(patch.dimensionsNote).toBeNull();
  expect(patch.makingTimeDays).toBeNull();
});

test("the validation row takes the three dimensions and none of the reviewers' own boxes", () => {
  // Stage 15 is a JUDGEMENT of a prototype rather than a second description of it, so `decision`,
  // `reason` and `approvedBy` belong to the reviewer and the mapping stops at the measurements.
  const patch = hydrateFromReference(
    PROTOTYPE_VALIDATION,
    VALIDATION_PROTOTYPE_REF,
    PROTOTYPE,
    {},
    ""
  );
  expect(patch).toEqual({ finalLengthCm: 38, finalWidthCm: 12, finalHeightCm: 34 });
});

test("the same prototype fills differently named boxes on the two stages that name it", () => {
  // THE MAPPING'S WHOLE REASON FOR EXISTING, restated for the internal half: `data.lengthCm` lands
  // on `lengthCm` at stage 16 and on `finalLengthCm` at stage 15. A key-name match would write
  // nothing at all on stage 15 — silently, which is how the external half of this feature lost a
  // column for a year.
  const catalogue = hydrateFromReference(FINAL_PRODUCT, PROTOTYPE_REF, PROTOTYPE, {}, "");
  const validation = hydrateFromReference(
    PROTOTYPE_VALIDATION,
    VALIDATION_PROTOTYPE_REF,
    PROTOTYPE,
    {},
    ""
  );
  expect(catalogue.lengthCm).toBe(38);
  expect(catalogue).not.toHaveProperty("finalLengthCm");
  expect(validation.finalLengthCm).toBe(38);
  expect(validation).not.toHaveProperty("lengthCm");
});

test("the internal refs that were judged and refused hydrate nothing", () => {
  /*
   * THE OTHER HALF OF THE DECISION, MADE MECHANICAL ON THIS SURFACE. Thirteen internal REF fields
   * were read and left un-hydrated, each with its reason written above the server's table. Three are
   * pinned here because they are the ones somebody would "fix" by pattern-matching the two entries
   * above: a sketch is a DRAWING and the prototype made from it is an OBJECT; a successor sketch
   * exists BECAUSE something changed; and a material-usage line names ONE material where the
   * prototype holds a LIST, so hydration cannot choose which.
   *
   * The table fails closed, so an absent entry hydrates nothing rather than guessing — which is
   * what makes these three assertions cheap and a WRONG entry the only real hazard.
   */
  const fromSketch: DwEntity = {
    ...FINAL_PRODUCT,
    key: "prototype",
    fields: [field("sketchRef", "REF", { refModel: "DwSketch" }), field("materials", "TAGS")]
  };
  expect(hydrateFromReference(fromSketch, fromSketch.fields[0], PROTOTYPE, {}, "")).toEqual({});

  const supersedes: DwEntity = {
    ...FINAL_PRODUCT,
    key: "sketch",
    fields: [
      field("supersedesSketch", "REF", { refModel: "DwSketch" }),
      field("name", "TEXT"),
      field("isTentative", "BOOL")
    ]
  };
  expect(hydrateFromReference(supersedes, supersedes.fields[0], PROTOTYPE, {}, "")).toEqual({});

  const usage: DwEntity = {
    ...FINAL_PRODUCT,
    key: "materialUsage",
    fields: [field("prototypeRef", "REF", { refModel: "DwPrototype" }), field("material", "TEXT")]
  };
  expect(hydrateFromReference(usage, usage.fields[0], PROTOTYPE, {}, "")).toEqual({});
});
