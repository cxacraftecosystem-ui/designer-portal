import { expect, test } from "@playwright/test";

import {
  hydrateFromReference,
  isFilled,
  type DwEntity,
  type DwEntryData,
  type DwField,
  type DwReferenceOption,
  type DwValue
} from "@/lib/designWorkshops";

/**
 * RE-POINTING A REFERENCE MUST CLEAR THE PREVIOUS RECORD'S VALUES — AND MUST STAY CLEARED WHEN THE
 * SAME DRAFT IS SAVED A SECOND TIME.
 *
 * WHAT THIS IS ABOUT. A participant row stores `artisanRef` (an id) plus a denormalised copy of the
 * artisan's name, village, gender, phone and photograph, because the copy IS the historical record
 * — the row must still print correctly after the master record is edited or deleted. Hydration is
 * what writes that copy. Its hardest case is a RE-POINT: the designer picks a fully documented
 * artisan A, notices the phone number belongs to a different Sita Devi, and re-points the row at a
 * thinly documented artisan B. Everything B has nothing to say about — phone, village, gender,
 * photo — has to GO, or the row reads as B's name over A's telephone number and A's photograph,
 * with `artisanRef` naming B so nothing can ever re-resolve which was meant. That table is printed
 * into a .docx submitted to a ministry.
 *
 * WHY IT NEEDED A SPEC OF ITS OWN. The clearing rule was closed server-side first
 * (`hydrate_entries`, the `if replaced:` pop) and the browser half was not written. That closes the
 * door for EXACTLY ONE SAVE, because the server decides `replaced` against the STORED row: once the
 * stored row names B, `replaced` is false forever after, the only-fill-blanks rule takes over, and
 * a draft still holding A's phone writes it straight back under B's id. So the interesting
 * assertion is not "the patch clears the key" — it is "save the same draft TWICE and the value is
 * still gone", and that is what the two-save tests below do.
 *
 * `reference-hydration-unit.spec.ts` covers hydration's other rules (fill-blanks, seed-a-list,
 * payload shapes) and deliberately never saves. This file covers the re-point and the round trip.
 *
 * PURE ON PURPOSE — no dev server, no browser, no database. The server's rules are restated below
 * as a miniature, with the file and the branch each rule comes from named beside it, so a change to
 * either side that breaks the agreement shows up here in milliseconds instead of in a report.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Registry shapes, cut down — but with the REAL keys
 *
 * `DW_REFERENCE_HYDRATION` is keyed by `"entityKey.fieldKey"` and its values are keyed by the
 * reference payload's own column names, so a fixture with invented keys would hydrate nothing and
 * pass every assertion by accident.
 * ──────────────────────────────────────────────────────────────────────────── */

function field(key: string, type: DwField["type"], extra: Partial<DwField> = {}): DwField {
  return { key, label: key, type, tier: "BASIC", required: false, ...extra };
}

/** Stage 3's roster row, with EVERY target of `participant.artisanRef` declared. */
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
    field("specialisation", "TEXT"),
    field("experienceYears", "INT"),
    field("gender", "ENUM"),
    field("phone", "TEXT"),
    field("village", "TEXT"),
    field("photo", "IMAGE")
  ]
};

const ARTISAN_REF = PARTICIPANT.fields[0];

/** Stage 6's row: the one whose photograph target is a GALLERY, which is never replaced. */
const EXISTING_PRODUCT: DwEntity = {
  key: "existingProduct",
  name: "DwExistingProduct",
  cardinality: "COLLECTION",
  title: "Existing products",
  description: "",
  parent: "",
  labelField: "name",
  fields: [
    field("productRef", "REF", { refModel: "Product" }),
    field("name", "TEXT"),
    field("category", "TEXT"),
    field("material", "TEXT"),
    field("price", "MONEY"),
    field("use", "TEXT"),
    field("productPhotos", "IMAGE_LIST")
  ]
};

const PRODUCT_REF = EXISTING_PRODUCT.fields[0];

function option(id: string, data: Record<string, unknown>): DwReferenceOption {
  return { id, label: id, sublabel: "", data };
}

/** Artisan A: documented down to the photograph. This is the record whose values must not linger. */
const SUSHILA = option("artisan-1", {
  name: "Sushila Meher",
  localName: "ସୁଶୀଳା ମେହେର",
  specialisation: "Bandha tie-dye",
  experienceYears: 22,
  gender: "FEMALE",
  phone: "9861000111",
  village: "Barpali",
  photo: "media-77"
});

/**
 * Artisan B: a name and a craft and NOTHING ELSE, which is the whole point.
 *
 * A second fully documented artisan would overwrite every field on its way in and the defect would
 * be invisible — that is exactly why the pre-existing replace test passed while the bug was live.
 */
const KAILASH = option("artisan-2", {
  name: "Kailash Bhoi",
  specialisation: "Warping"
});

/* ────────────────────────────────────────────────────────────────────────────
 * The server, in miniature
 *
 * Restated from backend/app/services/design_workshops.py and services/stage_schema.py. Each rule
 * names its origin, because the value of this fake is entirely in its fidelity: if it drifts, the
 * two-save tests below stop meaning anything.
 * ──────────────────────────────────────────────────────────────────────────── */

/** The mapping the server holds in `stage_schema.REFERENCE_HYDRATION` for `participant.artisanRef`. */
const ARTISAN_MAPPING: Record<string, string> = {
  name: "name",
  localName: "localName",
  specialisation: "specialisation",
  experienceYears: "experienceYears",
  gender: "gender",
  phone: "phone",
  village: "village",
  photo: "photo"
};

/** …and for `existingProduct.productRef`, whose `photo` lands in a GALLERY. */
const PRODUCT_MAPPING: Record<string, string> = {
  name: "name",
  category: "category",
  material: "material",
  price: "price",
  use: "use",
  photo: "productPhotos"
};

/**
 * One save of one row, as `save_stage` performs it for a stage the browser HAS read.
 *
 * Three steps in the server's own order:
 *
 *  1. `validate_entry` (stage_schema.py) coerces every declared field and DROPS the blank ones —
 *     `coerce_value` returns None for null and for an all-whitespace string, and a None value is
 *     never put into `cleaned`. This is the step that turns the browser's explicit `null` into an
 *     absent key, and it is why clearing client-side works at all.
 *  2. `hydrate_entries` (design_workshops.py) runs against `previous`, the STORED row's data: it
 *     computes `replaced`, pops every mapped non-multi target when `replaced`, then copies in what
 *     the chosen record has to say, filling blanks only — unless `replaced`, in which case a
 *     single-value target is overwritten and a list target is still not.
 *  3. The row's `data` is written WHOLESALE (`updates.append(... {"data": _json(item.data)} ...)`),
 *     so a key that survived neither step is gone from the stored row.
 *
 * `merge` is deliberately not modelled: it is sent only by a browser that has never read the stage,
 * and the sequence under test is the ordinary online one, where the draft is saved twice.
 */
function serverSave(
  entity: DwEntity,
  refKey: string,
  mapping: Record<string, string>,
  records: Record<string, DwReferenceOption>,
  stored: DwEntryData,
  sent: DwEntryData
): DwEntryData {
  // Step 1 — validate_entry: blanks are dropped, so they cannot overwrite anything.
  const data: DwEntryData = {};
  for (const [key, value] of Object.entries(sent)) {
    if (value === null || value === undefined) continue;
    if (typeof value === "string" && !value.trim()) continue;
    if (Array.isArray(value) && !value.length) continue;
    data[key] = value;
  }

  // Step 2 — hydrate_entries.
  const refId = data[refKey];
  const source = typeof refId === "string" ? records[refId] : undefined;
  if (source) {
    const was = String(stored[refKey] ?? "");
    const replaced = Boolean(was) && was !== String(refId);
    if (replaced) {
      for (const targetKey of Object.values(mapping)) {
        const target = entity.fields.find((candidate) => candidate.key === targetKey);
        if (!target || target.type === "IMAGE_LIST" || target.type === "MULTI_ENUM" || target.type === "TAGS") {
          continue;
        }
        delete data[targetKey];
      }
    }
    for (const [sourceKey, targetKey] of Object.entries(mapping)) {
      const value = source.data?.[sourceKey];
      if (value === null || value === undefined || value === "") continue;
      const target = entity.fields.find((candidate) => candidate.key === targetKey);
      if (!target || target.deprecated) continue;
      const multi =
        target.type === "IMAGE_LIST" || target.type === "MULTI_ENUM" || target.type === "TAGS";
      if (isFilled(data[targetKey]) && (!replaced || multi)) continue;
      if (typeof value !== "string" && typeof value !== "number") continue;
      data[targetKey] = multi ? [String(value)] : (value as DwValue);
    }
  }

  // Step 3 — the row is replaced by what is left.
  return data;
}

/** What the picker does to the row: one patch, spread over it, plus the id. `patchRowMany`'s shape. */
function pick(
  entity: DwEntity,
  refField: DwField,
  chosen: DwReferenceOption,
  row: DwEntryData
): DwEntryData {
  const previousRefId = typeof row[refField.key] === "string" ? (row[refField.key] as string) : "";
  const patch = hydrateFromReference(entity, refField, chosen, row, previousRefId);
  return { ...row, ...patch, [refField.key]: chosen.id };
}

/* ────────────────────────────────────────────────────────────────────────────
 * The patch itself
 * ──────────────────────────────────────────────────────────────────────────── */

test("re-pointing at a thinly documented artisan clears what the last one filled in", () => {
  // The patch has to carry the clears as VALUES, not as absences: every caller applies it with a
  // spread (`{...row, ...patch}`), and an absent key deletes nothing through a spread.
  const row = pick(PARTICIPANT, ARTISAN_REF, SUSHILA, {});
  expect(row.phone).toBe("9861000111");

  const patch = hydrateFromReference(PARTICIPANT, ARTISAN_REF, KAILASH, row, SUSHILA.id);

  // What B has to say about is rewritten…
  expect(patch.name).toBe("Kailash Bhoi");
  expect(patch.specialisation).toBe("Warping");
  // …and what B is silent about is CLEARED rather than left standing under B's name. A phone
  // number, a village, a gender and a PHOTOGRAPH belonging to a different woman.
  for (const key of ["localName", "experienceYears", "gender", "phone", "village", "photo"]) {
    expect(patch, `${key} must be cleared, not left holding artisan A's value`).toHaveProperty(key);
    expect(patch[key]).toBeNull();
  }

  // And the row the designer is looking at shows blanks, not the previous artisan's answers.
  const after = { ...row, ...patch };
  for (const key of ["localName", "gender", "phone", "village", "photo"]) {
    expect(isFilled(after[key])).toBe(false);
  }
});

test("a FIRST pick clears nothing — only a re-point does", () => {
  // The guard on the whole rule. Clearing unconditionally would blank the answers a designer typed
  // before reaching for the picker, which is the failure the only-fill-blanks rule exists to
  // prevent and is far more common than a re-point.
  const typed: DwEntryData = { phone: "9861999888", village: "Remunda" };
  const patch = hydrateFromReference(PARTICIPANT, ARTISAN_REF, KAILASH, typed, "");

  expect(Object.values(patch)).not.toContain(null);
  expect(patch.phone).toBeUndefined();
  const after = { ...typed, ...patch };
  expect(after.phone).toBe("9861999888");
  expect(after.village).toBe("Remunda");
});

test("re-choosing the SAME artisan clears nothing", () => {
  // `previousRefId === option.id` is not a replacement. Tapping the picker again to check you have
  // the right person must not wipe the phone number you corrected by hand a minute earlier.
  const row: DwEntryData = { artisanRef: SUSHILA.id, name: "Sushila Meher", phone: "9861222333" };
  const patch = hydrateFromReference(PARTICIPANT, ARTISAN_REF, SUSHILA, row, SUSHILA.id);
  expect(Object.values(patch)).not.toContain(null);
  expect({ ...row, ...patch }.phone).toBe("9861222333");
});

test("a gallery is exempt from the clearing, exactly as it is exempt from the overwrite", () => {
  // The documented product's photograph SEEDS the gallery; the gallery then holds the photographs
  // the designer took in the room, and there is no second copy of those anywhere. Clearing it on a
  // re-point would destroy them, which is worse than the stale value the clearing exists to remove.
  const first = option("product-1", { name: "Sambalpuri stole", category: "Stole", photo: "media-10" });
  const second = option("product-2", { name: "Bandha runner" });

  const row = pick(EXISTING_PRODUCT, PRODUCT_REF, first, {});
  const shot: DwEntryData = { ...row, productPhotos: ["media-taken-at-the-workshop"] };
  const patch = hydrateFromReference(EXISTING_PRODUCT, PRODUCT_REF, second, shot, first.id);

  expect(patch.name).toBe("Bandha runner");
  expect(patch.category).toBeNull();          // single value, silent record → cleared
  expect(patch).not.toHaveProperty("productPhotos"); // gallery → untouched, neither cleared nor replaced
  expect({ ...shot, ...patch }.productPhotos).toEqual(["media-taken-at-the-workshop"]);
});

/* ────────────────────────────────────────────────────────────────────────────
 * TWO SAVES — the half the server cannot do alone
 * ──────────────────────────────────────────────────────────────────────────── */

test("the re-point survives a SECOND save of the same draft", () => {
  const records = { [SUSHILA.id]: SUSHILA, [KAILASH.id]: KAILASH };
  const save = (stored: DwEntryData, sent: DwEntryData) =>
    serverSave(PARTICIPANT, "artisanRef", ARTISAN_MAPPING, records, stored, sent);

  // The designer picks artisan A and saves. The stored row is fully documented.
  let draft = pick(PARTICIPANT, ARTISAN_REF, SUSHILA, {});
  let stored = save({}, draft);
  expect(stored.phone).toBe("9861000111");
  expect(stored.photo).toBe("media-77");

  // She notices the phone belongs to a different Sita Devi and re-points the row at artisan B.
  draft = pick(PARTICIPANT, ARTISAN_REF, KAILASH, draft);

  // SAVE 1 after the re-point. The server's own pop covers this one: `previous.artisanRef` is still
  // A, so `replaced` is true and the mapped targets are cleared before anything is copied in. This
  // passed with the browser half missing, which is precisely why it proves nothing on its own.
  stored = save(stored, draft);
  expect(stored.name).toBe("Kailash Bhoi");
  expect(stored).not.toHaveProperty("phone");
  expect(stored).not.toHaveProperty("photo");

  // SAVE 2, from the SAME unchanged draft — a retry after a dropped connection, an autosave, or the
  // designer pressing Save again. THIS is the one that bites. The stored row now names B, so the
  // server computes `replaced = false`, pops nothing, and its only-fill-blanks rule will keep
  // whatever the client sends as a filled value. Everything therefore rests on what the draft still
  // holds: with the clearing in `hydrateFromReference` the draft holds `null` and the key is
  // dropped; without it the draft still holds A's phone, village, gender and photograph, and they
  // are written back under B's id where nothing can ever re-resolve them.
  stored = save(stored, draft);
  expect(stored.name).toBe("Kailash Bhoi");
  expect(stored.specialisation).toBe("Warping");
  for (const key of ["localName", "experienceYears", "gender", "phone", "village", "photo"]) {
    expect(stored, `${key} came back under artisan B on the second save`).not.toHaveProperty(key);
  }

  // A third save changes nothing either — the state is a fixed point, not a value in transit.
  expect(save(stored, draft)).toEqual(stored);
});

test("two saves do not swallow the photographs the designer took", () => {
  // The companion guard for the exemption above, across the same round trip: the gallery must
  // neither be cleared by the re-point nor overwritten by the new product's catalogue shot.
  const first = option("product-1", { name: "Sambalpuri stole", category: "Stole", photo: "media-10" });
  const second = option("product-2", { name: "Bandha runner" });
  const records = { [first.id]: first, [second.id]: second };
  const save = (stored: DwEntryData, sent: DwEntryData) =>
    serverSave(EXISTING_PRODUCT, "productRef", PRODUCT_MAPPING, records, stored, sent);

  let draft = pick(EXISTING_PRODUCT, PRODUCT_REF, first, {});
  draft = { ...draft, productPhotos: ["media-taken-at-the-workshop"] };
  let stored = save({}, draft);
  expect(stored.productPhotos).toEqual(["media-taken-at-the-workshop"]);

  draft = pick(EXISTING_PRODUCT, PRODUCT_REF, second, draft);
  stored = save(stored, draft);
  stored = save(stored, draft);

  expect(stored.name).toBe("Bandha runner");
  expect(stored).not.toHaveProperty("category");
  expect(stored.productPhotos).toEqual(["media-taken-at-the-workshop"]);
});
