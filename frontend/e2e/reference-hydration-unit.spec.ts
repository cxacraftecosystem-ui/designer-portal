import { expect, test } from "@playwright/test";

import {
  hydrateFromReference,
  referenceDisplayHint,
  type DwEntity,
  type DwEntryData,
  type DwField,
  type DwReferenceOption
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
    field("village", "TEXT"),
    field("specialisation", "TEXT"),
    field("experienceYears", "INT"),
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

test("a chosen artisan fills the boxes the mapping names, and only those", () => {
  const patch = hydrateFromReference(PARTICIPANT, ARTISAN_REF, SUSHILA, {}, "");

  expect(patch).toEqual({
    name: "Sushila Meher",
    localName: "ସୁଶୀଳା ମେହେର",
    village: "Barpali",
    specialisation: "Bandha tie-dye",
    experienceYears: 22,
    photo: "media-77"
  });
  expect(patch).not.toHaveProperty("artisanRef");
});

test("a number arrives as a number and not as its text", () => {
  const patch = hydrateFromReference(PARTICIPANT, ARTISAN_REF, SUSHILA, {}, "");
  expect(typeof patch.experienceYears).toBe("number");
});

test("an unmapped entity or field hydrates nothing rather than guessing", () => {
  const stranger: DwEntity = { ...PARTICIPANT, key: "notInTheTable" };
  expect(hydrateFromReference(stranger, ARTISAN_REF, SUSHILA, {}, "")).toEqual({});
  expect(hydrateFromReference(PARTICIPANT, field("someOtherRef", "REF"), SUSHILA, {}, "")).toEqual({});
});

test("a box the designer already filled is left exactly as they left it", () => {
  const row: DwEntryData = { name: "Sushila Meher (Sushi)", village: "" };
  const patch = hydrateFromReference(PARTICIPANT, ARTISAN_REF, SUSHILA, row, "");

  expect(patch).not.toHaveProperty("name");
  expect(patch.village).toBe("Barpali");
});

test("choosing a DIFFERENT artisan rewrites the fields the last one filled", () => {
  const row: DwEntryData = { name: "Sushila Meher", village: "Barpali", experienceYears: 22 };
  const patch = hydrateFromReference(PARTICIPANT, ARTISAN_REF, KAILASH, row, SUSHILA.id);

  expect(patch.name).toBe("Kailash Bhoi");
  expect(patch.village).toBe("Sonepur");
  expect(patch.experienceYears).toBe(9);
});

test("re-choosing the SAME artisan changes nothing that was edited afterwards", () => {
  const row: DwEntryData = { name: "Sushila Meher (Sushi)" };
  const patch = hydrateFromReference(PARTICIPANT, ARTISAN_REF, SUSHILA, row, SUSHILA.id);
  expect(patch).not.toHaveProperty("name");
});

test("a list is seeded when empty and never replaced, even on a changed record", () => {
  const first = option("product-1", { name: "Sambalpuri stole", photo: "media-10" });
  const second = option("product-2", { name: "Bandha runner", photo: "media-20" });

  expect(hydrateFromReference(EXISTING_PRODUCT, PRODUCT_REF, first, {}, "").productPhotos).toEqual([
    "media-10"
  ]);

  const shot: DwEntryData = { productPhotos: ["media-shot-by-the-designer"], name: "Sambalpuri stole" };
  const patch = hydrateFromReference(EXISTING_PRODUCT, PRODUCT_REF, second, shot, first.id);
  expect(patch.name).toBe("Bandha runner");
  expect(patch).not.toHaveProperty("productPhotos");
});

test("the two refs on one row write into different boxes", () => {
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

test("a value that is not a string or a number is skipped rather than stringified", () => {
  const odd = option("artisan-3", {
    name: { first: "Sushila", last: "Meher" },
    village: ["Barpali"],
    specialisation: true,
    experienceYears: 22
  });
  expect(hydrateFromReference(PARTICIPANT, ARTISAN_REF, odd, {}, "")).toEqual({ experienceYears: 22 });
});

test("a GEO target takes the subject pin as the object it is, and refuses half a coordinate", () => {
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

test("the picker's trigger shows the name hydration put on the row, not the id", () => {
  const row = hydrateFromReference(PARTICIPANT, ARTISAN_REF, SUSHILA, {}, "");
  expect(referenceDisplayHint(PARTICIPANT, ARTISAN_REF, row)).toBe("Sushila Meher");
});

test("the hint reads through the SAME mapping the fill used", () => {
  const row: DwEntryData = { name: "Sambalpuri stole", artisanName: "Sushila Meher" };
  expect(referenceDisplayHint(EXISTING_PRODUCT, PRODUCT_REF, row)).toBe("Sambalpuri stole");
  expect(referenceDisplayHint(EXISTING_PRODUCT, PRODUCT_ARTISAN_REF, row)).toBe("Sushila Meher");
});

test("an unmapped field or an unfilled row hints nothing rather than something wrong", () => {
  expect(referenceDisplayHint(PARTICIPANT, field("someOtherRef", "REF"), { name: "x" })).toBe("");
  expect(referenceDisplayHint(PARTICIPANT, ARTISAN_REF, {})).toBe("");
  expect(referenceDisplayHint(PARTICIPANT, ARTISAN_REF, { name: "   " })).toBe("");
});

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

  expect(Object.keys(patch)).toHaveLength(11);
});

test("a sitting that answered nothing writes zero, not nothing", () => {
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
  expect(patch).not.toHaveProperty("productCode");
  expect(patch).not.toHaveProperty("finalPhotos");
  expect(patch).not.toHaveProperty("technique");
});

test("a measurement the designer took off the finished piece is not reverted", () => {
  const row: DwEntryData = { lengthCm: 39.5, materials: ["Cotton"] };
  const patch = hydrateFromReference(FINAL_PRODUCT, PROTOTYPE_REF, PROTOTYPE, row, "");

  expect(patch).not.toHaveProperty("lengthCm");
  expect(patch).not.toHaveProperty("materials");
  expect(patch.widthCm).toBe(12);
});

test("a carried tag is stripped the way PYTHON strips, not the way trim() does", () => {
  const NEL = String.fromCharCode(0x85);
  const FS = String.fromCharCode(0x1c);
  const US = String.fromCharCode(0x1f);
  const padded = option("proto-4", {
    name: "Bandha tote",
    materials: [NEL + "Cotton" + NEL, FS + "Jute webbing" + US, "   ", NEL]
  });
  const patch = hydrateFromReference(FINAL_PRODUCT, PROTOTYPE_REF, padded, {}, "");
  expect(patch.materials).toEqual(["Cotton", "Jute webbing"]);
  expect(patch.materials).not.toContain("");
});

test("a rich-text narrative is left for the server rather than flattened at the keyboard", () => {
  const withProse = option("proto-2", {
    name: "Bandha tote",
    processSummary: { blocks: [{ type: "paragraph", text: "Warped on the pit loom." }] }
  });
  expect(hydrateFromReference(FINAL_PRODUCT, PROTOTYPE_REF, withProse, {}, "")).toEqual({
    name: "Bandha tote"
  });
});

test("re-pointing at a different prototype rewrites the boxes the last one filled", () => {
  const row: DwEntryData = hydrateFromReference(FINAL_PRODUCT, PROTOTYPE_REF, PROTOTYPE, {}, "");
  const thinner = option("proto-3", { name: "Bandha runner", lengthCm: 120 });
  const patch = hydrateFromReference(FINAL_PRODUCT, PROTOTYPE_REF, thinner, row, "proto-1");

  expect(patch.name).toBe("Bandha runner");
  expect(patch.lengthCm).toBe(120);
  expect(patch.weightG).toBeNull();
  expect(patch.dimensionsNote).toBeNull();
  expect(patch.makingTimeDays).toBeNull();
});

test("the validation row takes the three dimensions and none of the reviewers' own boxes", () => {
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
