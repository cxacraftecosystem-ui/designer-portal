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
