import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import type { DwEntity, DwField, DwStage } from "@/lib/designWorkshops";
import { isEmptyStageValue, prefillPatch } from "@/lib/designerPrefill";

/*
  The designer's profile fills a stage's EMPTY boxes when it opens (2026-10-21). One case per family
  of mapped keys, the never-overwrite rule, and the page wiring.
*/

const field = (key: string, extra: Partial<DwField> = {}) => ({ key, label: key, type: "TEXT", ...extra }) as DwField;
const entity = (key: string, cardinality: DwEntity["cardinality"], keys: string[]) =>
  ({ key, name: key, cardinality, title: key, description: "", parent: "", labelField: "", fields: keys.map((k) => field(k)) }) as DwEntity;
const stage = (...entities: DwEntity[]) => ({ entities }) as Pick<DwStage, "entities">;

const SETUP = stage(entity("workshopSetup", "SINGLETON", ["designerName", "designerInstitution", "craftName"]));
const PLAN = stage(
  entity("workshopPlan", "SINGLETON", [
    "designerProfile",
    "designerExperience",
    "designerQualification",
    "designerPhone",
    "designerEmail",
    "designerAddress",
    "designerCity",
    "designerState",
    "designerPincode",
    "designerEmpanelmentDate",
    "designerPhoto"
  ]),
  entity("participant", "COLLECTION", ["designerName"])
);

const PROFILE = {
  designerName: "Latha Nayak",
  designerInstitution: "NIFT Bhubaneswar",
  designerProfile: "Fourteen years on ikat.",
  designerExperience: 14,
  designerQualification: "M.Des",
  designerPhone: "+91 9876500001",
  designerEmail: "latha@nift.ac.in",
  designerAddress: "Plot 3, Chandaka",
  designerCity: "Bhubaneswar",
  designerState: "Odisha",
  designerPincode: "751024",
  designerEmpanelmentDate: "2026-03-14",
  designerPhoto: "cm-photo"
};

test("name and institution fill stage 1's empty boxes", () => {
  expect(prefillPatch(SETUP, {}, PROFILE)).toEqual({ designerName: "Latha Nayak", designerInstitution: "NIFT Bhubaneswar" });
});

test("contact fills stage 3", () => {
  const patch = prefillPatch(PLAN, {}, PROFILE);
  expect([patch.designerPhone, patch.designerEmail]).toEqual(["+91 9876500001", "latha@nift.ac.in"]);
});

test("address and location fill stage 3", () => {
  const patch = prefillPatch(PLAN, {}, PROFILE);
  expect([patch.designerAddress, patch.designerCity, patch.designerState, patch.designerPincode]).toEqual([
    "Plot 3, Chandaka",
    "Bhubaneswar",
    "Odisha",
    "751024"
  ]);
});

test("experience, qualification, biography, dates and media fill stage 3", () => {
  const patch = prefillPatch(PLAN, {}, PROFILE);
  expect(patch.designerExperience).toBe(14);
  expect(patch.designerQualification).toBe("M.Des");
  expect(patch.designerProfile).toBe("Fourteen years on ikat.");
  expect(patch.designerEmpanelmentDate).toBe("2026-03-14");
  expect(patch.designerPhoto).toBe("cm-photo");
});

test("keys the stage does not declare are left alone", () => {
  expect(Object.keys(prefillPatch(SETUP, {}, PROFILE)).sort()).toEqual(["designerInstitution", "designerName"]);
});

test("a typed box is never overwritten; blank ones still fill", () => {
  const patch = prefillPatch(SETUP, { designerName: "R. Mohanty", designerInstitution: "  " }, PROFILE);
  expect(patch).toEqual({ designerInstitution: "NIFT Bhubaneswar" });
  expect(prefillPatch(PLAN, { designerExperience: 0 }, PROFILE).designerExperience).toBeUndefined();
});

test("deprecated fields, collections and empty values take nothing", () => {
  const retired = stage({ ...entity("workshopSetup", "SINGLETON", []), fields: [field("designerName", { deprecated: true })] });
  expect(prefillPatch(retired, {}, PROFILE)).toEqual({});
  expect(prefillPatch(SETUP, {}, { designerName: "", designerInstitution: null })).toEqual({});
  expect(prefillPatch(null, {}, PROFILE)).toEqual({});
  expect(prefillPatch(SETUP, {}, null)).toEqual({});
});

test("emptiness matches copyIntoEmpty", () => {
  expect([undefined, null, "", "  ", []].every((value) => isEmptyStageValue(value))).toBe(true);
  expect([0, false, "x", ["a"]].some((value) => isEmptyStageValue(value))).toBe(false);
});

test("the stage page fills from the profile through the same helper", () => {
  const page = readFileSync(join(__dirname, "..", "app/(protected)/design-workshops/[id]/stages/[stageKey]/page.tsx"), "utf8");
  expect(page).toContain("loadDesignerPrefill(serverId)");
  expect(page).toContain("prefillPatch(stage, current, prefill.values)");
});
