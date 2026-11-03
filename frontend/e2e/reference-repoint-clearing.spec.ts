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

function field(key: string, type: DwField["type"], extra: Partial<DwField> = {}): DwField {
  return { key, label: key, type, tier: "BASIC", required: false, ...extra };
}

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

const KAILASH = option("artisan-2", {
  name: "Kailash Bhoi",
  specialisation: "Warping"
});

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

const PRODUCT_MAPPING: Record<string, string> = {
  name: "name",
  category: "category",
  material: "material",
  price: "price",
  use: "use",
  photo: "productPhotos"
};

function serverSave(
  entity: DwEntity,
  refKey: string,
  mapping: Record<string, string>,
  records: Record<string, DwReferenceOption>,
  stored: DwEntryData,
  sent: DwEntryData
): DwEntryData {
  const data: DwEntryData = {};
  for (const [key, value] of Object.entries(sent)) {
    if (value === null || value === undefined) continue;
    if (typeof value === "string" && !value.trim()) continue;
    if (Array.isArray(value) && !value.length) continue;
    data[key] = value;
  }

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

  return data;
}

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

test("re-pointing at a thinly documented artisan clears what the last one filled in", () => {
  const row = pick(PARTICIPANT, ARTISAN_REF, SUSHILA, {});
  expect(row.phone).toBe("9861000111");

  const patch = hydrateFromReference(PARTICIPANT, ARTISAN_REF, KAILASH, row, SUSHILA.id);

  expect(patch.name).toBe("Kailash Bhoi");
  expect(patch.specialisation).toBe("Warping");
  for (const key of ["localName", "experienceYears", "gender", "phone", "village", "photo"]) {
    expect(patch, `${key} must be cleared, not left holding artisan A's value`).toHaveProperty(key);
    expect(patch[key]).toBeNull();
  }

  const after = { ...row, ...patch };
  for (const key of ["localName", "gender", "phone", "village", "photo"]) {
    expect(isFilled(after[key])).toBe(false);
  }
});

test("a FIRST pick clears nothing — only a re-point does", () => {
  const typed: DwEntryData = { phone: "9861999888", village: "Remunda" };
  const patch = hydrateFromReference(PARTICIPANT, ARTISAN_REF, KAILASH, typed, "");

  expect(Object.values(patch)).not.toContain(null);
  expect(patch.phone).toBeUndefined();
  const after = { ...typed, ...patch };
  expect(after.phone).toBe("9861999888");
  expect(after.village).toBe("Remunda");
});

test("re-choosing the SAME artisan clears nothing", () => {
  const row: DwEntryData = { artisanRef: SUSHILA.id, name: "Sushila Meher", phone: "9861222333" };
  const patch = hydrateFromReference(PARTICIPANT, ARTISAN_REF, SUSHILA, row, SUSHILA.id);
  expect(Object.values(patch)).not.toContain(null);
  expect({ ...row, ...patch }.phone).toBe("9861222333");
});

test("a gallery is exempt from the clearing, exactly as it is exempt from the overwrite", () => {
  const first = option("product-1", { name: "Sambalpuri stole", category: "Stole", photo: "media-10" });
  const second = option("product-2", { name: "Bandha runner" });

  const row = pick(EXISTING_PRODUCT, PRODUCT_REF, first, {});
  const shot: DwEntryData = { ...row, productPhotos: ["media-taken-at-the-workshop"] };
  const patch = hydrateFromReference(EXISTING_PRODUCT, PRODUCT_REF, second, shot, first.id);

  expect(patch.name).toBe("Bandha runner");
  expect(patch.category).toBeNull();
  expect(patch).not.toHaveProperty("productPhotos");
  expect({ ...shot, ...patch }.productPhotos).toEqual(["media-taken-at-the-workshop"]);
});

test("the re-point survives a SECOND save of the same draft", () => {
  const records = { [SUSHILA.id]: SUSHILA, [KAILASH.id]: KAILASH };
  const save = (stored: DwEntryData, sent: DwEntryData) =>
    serverSave(PARTICIPANT, "artisanRef", ARTISAN_MAPPING, records, stored, sent);

  let draft = pick(PARTICIPANT, ARTISAN_REF, SUSHILA, {});
  let stored = save({}, draft);
  expect(stored.phone).toBe("9861000111");
  expect(stored.photo).toBe("media-77");

  draft = pick(PARTICIPANT, ARTISAN_REF, KAILASH, draft);

  stored = save(stored, draft);
  expect(stored.name).toBe("Kailash Bhoi");
  expect(stored).not.toHaveProperty("phone");
  expect(stored).not.toHaveProperty("photo");

  stored = save(stored, draft);
  expect(stored.name).toBe("Kailash Bhoi");
  expect(stored.specialisation).toBe("Warping");
  for (const key of ["localName", "experienceYears", "gender", "phone", "village", "photo"]) {
    expect(stored, `${key} came back under artisan B on the second save`).not.toHaveProperty(key);
  }

  expect(save(stored, draft)).toEqual(stored);
});

test("two saves do not swallow the photographs the designer took", () => {
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
