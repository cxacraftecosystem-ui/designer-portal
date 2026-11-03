import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { referenceHydrationFor, type DwEntity, type DwField } from "@/lib/designWorkshops";

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");
const SCHEMA = join(ROOT, "..", "android", "app", "src", "main", "assets", "design-workshop-schema.json");

type SchemaDump = { stages: Array<{ key: string; entities: DwEntity[] }> };

const registry = JSON.parse(readFileSync(SCHEMA, "utf8")) as SchemaDump;
const entities = new Map(registry.stages.flatMap((stage) => stage.entities).map((entity) => [entity.key, entity]));

function entityOf(key: string): DwEntity {
  const entity = entities.get(key);
  if (!entity) throw new Error(`the bundled registry has no entity "${key}"`);
  return entity;
}

function fieldOf(entityKey: string, fieldKey: string): DwField {
  const found = entityOf(entityKey).fields.find((candidate) => candidate.key === fieldKey);
  if (!found) throw new Error(`the bundled registry has no ${entityKey}.${fieldKey}`);
  return found;
}

const PROCESS_ENTITIES = ["traditionalProcess", "processStep"] as const;

test("both process pickers reach the clients declaring the product as their parent", () => {
  for (const entityKey of PROCESS_ENTITIES) {
    const child = fieldOf(entityKey, "processRef");
    expect(child.refFilterBy, `${entityKey}.processRef`).toBe("productRef");
    expect(child.refModel).toBe("Process");

    const parent = fieldOf(entityKey, "productRef");
    expect(parent.type, `${entityKey}.productRef`).toBe("REF");
    expect(parent.refModel).toBe("ProductDocumentation");
    expect(parent.refScope).toBe("WORKSHOP");
    expect(child.refScope).toBe("WORKSHOP");
    expect(parent.tier).not.toBe("BASIC");
    expect(parent.reportRole).toBe("HIDDEN");
  }
});

test("the parent is declared before the picker it narrows, and the picker before the boxes it fills", () => {
  for (const entityKey of PROCESS_ENTITIES) {
    const keys = entityOf(entityKey).fields.map((field) => field.key);
    expect(keys.indexOf("productRef"), entityKey).toBeGreaterThan(-1);
    expect(keys.indexOf("productRef")).toBeLessThan(keys.indexOf("processRef"));
    expect(keys.indexOf("processRef")).toBeLessThan(keys.indexOf("documentedFor"));
  }
});

test("the cascade's parent is a REF and never the text box holding the product's name", () => {
  for (const entityKey of PROCESS_ENTITIES) {
    const entity = entityOf(entityKey);
    for (const field of entity.fields) {
      if (!field.refFilterBy) continue;
      const parent = entity.fields.find((candidate) => candidate.key === field.refFilterBy);
      expect(parent, `${entityKey}.${field.key} names a parent that is not a field of the entity`).toBeTruthy();
      expect(parent?.type, `${entityKey}.${field.key}'s parent must be a REF, not a name box`).toBe("REF");
    }
  }
});

test("a cascading picker with a blank parent fetches nothing rather than the whole table", () => {
  const source = read("components/designworkshop/StageReferenceField.tsx");
  expect(source).toContain('field.refFilterBy ? inputValue(row[field.refFilterBy]) : ""');
  expect(source).toContain("const awaitingCascade = Boolean(field.refFilterBy) && !filterValue;");
  expect(source.replace(/\s+/g, " ")).toContain("active: open && !awaitingCascade");
});

test("nothing takes a single option except a scanned prototype tag", () => {
  const web = read("components/designworkshop/StageReferenceField.tsx").replace(/\s+/g, " ");
  expect(web).toContain('if (ref.recordType === "prototype" && !payload.truncated && payload.options.length === 1)');

  const android = read("../android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwReferenceField.kt").replace(
    /\s+/g,
    " "
  );
  expect(android).toContain("if (ref.recordType == DwWorkshopRecordType.PROTOTYPE && !payload.truncated && payload.options.size == 1)");

  const singles = (read("components/designworkshop/StageReferenceField.tsx").match(/options\.length === 1/g) ?? []).length;
  expect(singles, "a second single-option shortcut appeared in the picker").toBe(1);
});

test("documentedFor is answered by the product the designer picked, and by the process as a fallback", () => {
  for (const entityKey of PROCESS_ENTITIES) {
    const entity = entityOf(entityKey);
    const fromParent = referenceHydrationFor(entity, fieldOf(entityKey, "productRef"));
    expect(fromParent, `${entityKey}.productRef`).toEqual({ name: "documentedFor" });

    const fromChild = referenceHydrationFor(entity, fieldOf(entityKey, "processRef"));
    expect(fromChild.productName, `${entityKey}.processRef`).toBe("documentedFor");
  }
});

test("both clients clear the child when the parent moves, and neither pretends to settle the values", () => {
  const web = read("components/designworkshop/StageReferenceField.tsx");
  expect(web).toContain("if (!field.refFilterBy) return;");
  expect(web).toContain("The record this list depends on changed, so the previous choice was cleared");

  const androidField = read(
    "../android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwReferenceField.kt",
  );
  expect(
    androidField,
    "the handset's cascade clear is gone; a process picked under one product will sit on a row that " +
      "now names another, offerable by nothing and refused by nothing",
  ).toContain("dwCascadeClearsChild(field.refFilterBy, moved, selectedId)");
  expect(
    androidField,
    "a KDoc still says the handset has no cascade clear, which is now false",
  ).not.toContain("has no effect that clears a child when its parent changes");

  const server = read("../backend/app/services/design_workshops.py");
  expect(server).toContain("def _clear_cascade_orphans(");
  expect(server).toContain("_clear_cascade_orphans(entries)");
});

test("only a cascaded child can have its copied values popped, so an unlink keeps what it filled in", () => {
  const cascaded: string[] = [];
  for (const stage of registry.stages) {
    for (const entity of stage.entities) {
      for (const field of entity.fields) {
        if (!field.refFilterBy) continue;
        if (Object.keys(referenceHydrationFor(entity, field)).length === 0) continue;
        cascaded.push(`${entity.key}.${field.key}`);
      }
    }
  }
  expect(cascaded.sort()).toEqual([
    "existingProduct.productRef",
    "processStep.processRef",
    "prototype.productRef",
    "traditionalProcess.processRef",
  ]);
  expect(cascaded, "participant.artisanRef declares no parent and must never gain one here").not.toContain(
    "participant.artisanRef",
  );
});
