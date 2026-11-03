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

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

const FIELD_INPUT = "components/designworkshop/FieldInput.tsx";
const ADDRESS_FIELD = "components/designworkshop/StageAddressField.tsx";

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
  const plan = entity([
    field("designerAddress", "TEXT"),
    field("designerCity", "TEXT"),
    field("designerState", "TEXT"),
    field("designerPincode", "TEXT", { format: "PINCODE" })
  ]);
  expect(addressListRole(plan, plan.fields[2])?.role).toBe("state");
  expect(addressListRole(plan, plan.fields[3])?.role).toBe("pincode");
  expect(addressListRole(plan, plan.fields[3])?.stateField?.key).toBe("designerState");
  expect(addressListRole(plan, plan.fields[2])?.districtField).toBeNull();
  expect(addressListRole(plan, plan.fields[0])).toBeNull();
  expect(addressListRole(plan, plan.fields[1])).toBeNull();
});

test("the district knows which box scopes it, and the state knows which box it has to clear", () => {
  const roster = entity([field("state", "TEXT"), field("district", "TEXT")]);
  expect(addressListRole(roster, roster.fields[1])?.stateField?.key).toBe("state");
  expect(addressListRole(roster, roster.fields[0])?.districtField?.key).toBe("district");
});

test("a district with no state beside it stays a text box rather than becoming an unscopeable list", () => {
  const orphan = entity([field("district", "TEXT")]);
  expect(addressListRole(orphan, orphan.fields[0])).toBeNull();
});

test("the match is by exact key, so nothing that merely CONTAINS one of the words is caught", () => {
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
  const dump = JSON.parse(readFileSync(SCHEMA, "utf8")) as {
    stages: { key: string; entities: DwEntity[] }[];
  };
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
  expect(source).toContain('from "@/components/forms/LocationFields"');
  expect(source).toContain("loadAddressReference");
  expect(source).toContain("OFFLINE_STATES");
  expect(source).toContain("postalZoneMismatch");
  expect(source).toContain("pincodeOrSpacedValidationError(own)");
  expect(source).toContain("PINCODE_LENGTH");
  expect(source).toContain('inputMode="numeric"');
  expect(source).toContain('autoComplete="postal-code"');
  expect(source).toContain('replace(/\\D/g, "").slice(0, PINCODE_LENGTH)');
  expect(source).not.toContain("DictationButton");
  expect(source).toContain("onPatch({ [field.key]: next || null, [role.districtField.key]: null })");
  expect(source).toContain("if (next === own) return;");
  expect(source).toContain('const stateName = role.role === "state" ? own : role.stateField ?');
  expect(source).not.toContain("inputValue(row[role.stateField.key]) : own;");
});

test("the newline-joined string survives a round trip through the rows, blank rows dropped", () => {
  const stored = "Soak the cloth before printing\nDry it flat in shade";
  expect(splitNumbered(stored)).toEqual(["Soak the cloth before printing", "Dry it flat in shade"]);
  expect(joinNumbered(splitNumbered(stored))).toBe(stored);
  expect(joinNumbered(["Soak the cloth", "", "  ", "Dry it flat"])).toBe("Soak the cloth\nDry it flat");
  expect(splitNumbered(null)).toEqual([""]);
  expect(splitNumbered("")).toEqual([""]);
});

test("there is ONE numbered-list control, and both surfaces mount it", () => {
  const record = read("components/forms/DosDontsField.tsx");
  expect(record).toContain('from "@/components/forms/NumberedListInput"');
  expect(record).toContain("<NumberedPointRows");
  expect(record).not.toContain("function splitNumbered");
  expect(record).not.toContain('aria-label={`Point ${index + 1}`}');

  const stage = read(FIELD_INPUT);
  expect(stage).toContain('from "@/components/forms/NumberedListInput"');
  expect(stage).toContain('if (field.reportRole === "BULLETS") {');
  expect(stage).toContain("<NumberedListField");
  const bullets = stage.slice(stage.indexOf("<NumberedListField"), stage.indexOf("<NumberedListField") + 400);
  expect(bullets).toContain("labelId={labelId}");
  const rows = read("components/forms/NumberedListInput.tsx");
  expect(rows).toContain('role="group" aria-labelledby={labelId}');
  expect(rows.slice(rows.indexOf("export function NumberedPointRows"), rows.indexOf("export function NumberedListField"))).not.toContain(
    'role="group"'
  );
});

test("PHONE is its own branch and renders the record page's phone field, not a second one", () => {
  const source = read(FIELD_INPUT);
  expect(source).toContain('from "@/components/forms/PhoneField"');
  expect(source).toContain("<StagePhoneField");
  expect(source).not.toContain('case "PHONE":\n    case "EMAIL":');
  expect(source).toContain("return unlabelled(<StagePhoneField");
  expect(source).toContain("mirror={false}");
});

test("the card reader is offered on the number box itself, and the media reader is left where it was", () => {
  const source = read(FIELD_INPUT);
  expect(source).toContain('from "@/components/forms/IdentityCardCapture"');
  expect(source).toContain("<IdentityCardCapture");
  expect(source).toContain('kind="PEHCHAN"');
  expect(source).toContain(
    'import { aadhaarValidationError, isMaskedIdentityNumber } from "@/components/forms/AadhaarField";'
  );
  expect(source).toContain("<IdentityCardReader");
});

test("STANDING TRIPWIRE: the Pehchan capture resolves to the card box and not to the Aadhaar box", () => {
  const dump = JSON.parse(readFileSync(SCHEMA, "utf8")) as {
    stages: { entities: { key: string; fields: DwField[] }[] }[];
  };
  const participant = dump.stages
    .flatMap((stage) => stage.entities)
    .find((declared) => declared.key === "participant");
  expect(participant, "the registry no longer declares a participant entity").toBeTruthy();

  const entity = { key: "participant", fields: participant!.fields } as unknown as DwEntity;
  expect(identityNumberField(entity)?.key).toBe("artisanCardNo");

  const candidates = participant!.fields.filter(
    (spec) => !spec.deprecated && spec.type === "TEXT" && identityNumberField({ key: "x", fields: [spec] } as unknown as DwEntity)
  );
  expect(candidates.map((spec) => spec.key)).toEqual(["artisanCardNo", "aadhaarNumber"]);
});

test("dictation cannot write past the bound the box itself enforces on typing", () => {
  const source = read(FIELD_INPUT);
  const append = source.slice(source.indexOf("const appendDictated"), source.indexOf("const dictationNotice"));
  expect(append, "the composed value is measured against the declared bound").toContain(
    "field.maxLength && composed.length > field.maxLength"
  );
  expect(append, "and nothing is written when it does not fit").toContain("setDictationRefusal({");
  expect(append, "never shorten a dictated phrase to make it fit").not.toContain(".slice(0, field.maxLength");
  expect(source.match(/\{dictationNotice\}/g), "the notice renders under a labelled AND an unlabelled control").toHaveLength(2);
});

const WORKSHOP_FIELD = "components/designworkshop/StageWorkshopField.tsx";
const WORKSHOP_SELECT = "components/forms/WorkshopSelect.tsx";
const SKETCHES_HUB = "app/(protected)/sketches-and-prototypes/page.tsx";
const SKETCHES_HUB_SENTENCES = "app/(protected)/sketches-and-prototypes/chooserSentences.ts";
const DW_LIST = "app/(protected)/design-workshops/page.tsx";
const ANDROID = (relative: string) => join("..", "android", "app", "src", "main", "java", "com", "designprototype", "workshop", relative);
const ANDROID_API = ANDROID("data/WorkshopRepositoryApi.kt");
const ANDROID_REPO = ANDROID("data/WorkshopRepository.kt");
const ANDROID_FORMS = ANDROID("MainActivity.kt");

test("the boxes that hold a workshop's TITLE get the list, and the one that holds a title does not", () => {
  expect(workshopTitleRole(field("documentedAtWorkshop", "TEXT"))).toBe(true);
  expect(workshopTitleRole(field("craftDocumentedAtWorkshop", "TEXT"))).toBe(true);
  expect(workshopTitleRole(field("interviewDocumentedAtWorkshop", "TEXT"))).toBe(true);

  expect(workshopTitleRole(field("workshopTitle", "TEXT"))).toBe(false);
  expect(workshopTitleRole(field("workshopCode", "TEXT"))).toBe(false);

  expect(workshopTitleRole(field("documentedAtWorkshopNotes", "TEXT"))).toBe(false);
  expect(workshopTitleRole(field("workshopDocumentedAt", "TEXT"))).toBe(false);
  expect(workshopTitleRole(field("documentedAtWorkshop", "TEXT", { deprecated: true }))).toBe(false);
  expect(workshopTitleRole(field("documentedAtWorkshop", "LONG_TEXT"))).toBe(false);
});

test("the workshop's OWN name gets the creatable combo, and the two title roles stay disjoint", () => {
  expect(ownWorkshopTitleRole(field("workshopTitle", "TEXT"))).toBe(true);

  expect(workshopTitleRole(field("workshopTitle", "TEXT"))).toBe(false);
  expect(ownWorkshopTitleRole(field("documentedAtWorkshop", "TEXT"))).toBe(false);
  expect(ownWorkshopTitleRole(field("craftDocumentedAtWorkshop", "TEXT"))).toBe(false);
  expect(ownWorkshopTitleRole(field("interviewDocumentedAtWorkshop", "TEXT"))).toBe(false);

  expect(ownWorkshopTitleRole(field("workshopCode", "TEXT"))).toBe(false);
  expect(ownWorkshopTitleRole(field("workshopTitleNotes", "TEXT"))).toBe(false);
  expect(ownWorkshopTitleRole(field("workshopTitle", "TEXT", { deprecated: true }))).toBe(false);
  expect(ownWorkshopTitleRole(field("workshopTitle", "LONG_TEXT"))).toBe(false);
});

test("the creatable combo is the PRIMITIVE's affordance, not a toggle rebuilt at the call site", () => {
  const primitive = read("components/ui/SearchableSelect.tsx");
  expect(primitive).toContain("export type SelectCreateAction");
  expect(primitive).toContain("serverDriven || createAction != null");
  expect(primitive).toContain("else if (createTerm) create();");

  expect(read("components/ui/Dropdown.tsx")).toContain("createAction={createAction}");

  const control = read("components/designworkshop/StageWorkshopNameField.tsx");
  expect(control).toContain("createAction={{ label: workshopNameCreateLabel, onCreate: onChange }}");
  expect(control).toContain("export function workshopNameCreateLabel");
  const header = read("components/designworkshop/DesignWorkshopHeaderForm.tsx");
  expect(header).toContain("createAction={{ label: workshopNameCreateLabel, onCreate: commitTitle }}");
  expect(header, "a second wording of the create row").not.toContain("as the name`");
  expect(control).toContain("serverQuery={{ value: term, onChange: setTerm, pending }}");
  expect(control).not.toContain("workshopListStandsDown(");
});

test("STANDING TRIPWIRE: the registry declares exactly those three workshop-title fields, all TEXT", () => {
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
    "artisanBaseline.interviewDocumentedAtWorkshop:TEXT",
    "participant.documentedAtWorkshop:TEXT",
    "workshopSetup.craftDocumentedAtWorkshop:TEXT"
  ]);
});

test("the workshop-title box mounts the dropdown, keeps the dictation button on the free-text half", () => {
  const source = read(FIELD_INPUT);
  expect(source).toContain("if (workshopTitleRole(field))");
  expect(source).toContain("<StageWorkshopField");
  expect(source.replace(/\s+/g, " ")).toContain("return unlabelled( <StageWorkshopField");

  const control = read(WORKSHOP_FIELD);
  expect(control).toContain("loadAccessibleWorkshops");
  expect(control).not.toContain('listResource');
  expect(control).toContain("Type a title instead");
  expect(control).toContain("(dictation ?? ((control: React.ReactNode) => control))(");
});

test("(e) EVERY WORKSHOP PICKER ASKS THE SERVER FOR THE SCOPED LIST, AND NOTHING FALLS BACK TO A CACHE", () => {
  const picker = read(WORKSHOP_SELECT);
  expect(picker).toContain('accessibleOnly: "true"');
  expect(picker).not.toContain("accessibleOnly: true");

  const hub = read(SKETCHES_HUB);
  expect(hub).not.toContain('from "@/lib/designWorkshopStore"');
  expect(hub).not.toContain("listDrafts()");
  expect(hub).not.toContain("draftSummary)");
  expect(hub).toContain("CHOOSER_OFFLINE_TITLE");
  expect(read(SKETCHES_HUB_SENTENCES)).toContain('CHOOSER_OFFLINE_TITLE = "Couldn\'t connect"');

  const list = read(DW_LIST);
  expect(list).not.toContain("|| offline) extras.push");
  expect(list).toContain("|| (offline && unsent)) extras.push");
});

test("(e2) THE ANDROID RECORD FORMS ASK FOR THE SAME SCOPED LIST AS THE WEB", () => {
  const api = read(ANDROID_API);
  expect(api).toContain('@Query("accessibleOnly")');

  const repo = read(ANDROID_REPO);
  expect(repo).toContain("suspend fun workshopsIMaySubmitTo()");
  expect(repo).toContain("accessibleOnly = true");

  const forms = read(ANDROID_FORMS);
  expect(forms).toContain("repository.workshopsIMaySubmitToPage()");
  expect(forms).not.toContain("workshopsByOccurrence() }.onSuccess");
});
