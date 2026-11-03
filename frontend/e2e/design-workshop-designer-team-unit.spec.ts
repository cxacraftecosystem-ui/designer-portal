import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  DW_DRAFT_SCHEMA_VERSION,
  adoptedIntoWorkshop,
  newLocalDraft,
  type DwDraft
} from "@/lib/designWorkshopStore";
import { MAX_NAMED_DESIGNERS, designerCreateFields, namedDesignerTeam } from "@/lib/designWorkshops";

const A = "ckuser0000000000000000aaa";
const B = "ckuser0000000000000000bbb";
const C = "ckuser0000000000000000ccc";

test("nobody ticked is a real answer, and it names nobody", () => {
  expect(namedDesignerTeam({ chosen: [], lead: "" })).toEqual({ lead: "", team: [] });
});

test("one designer ticked is the lead by default — never the admin who pressed create", () => {
  expect(namedDesignerTeam({ chosen: [A], lead: "" })).toEqual({ lead: A, team: [A] });
});

test("the lead is put first, so the wire says which one it is rather than implying it", () => {
  expect(namedDesignerTeam({ chosen: [A, B, C], lead: B })).toEqual({ lead: B, team: [B, A, C] });
});

test("unticking the lead promotes the first ticked — it does NOT put them back", () => {
  expect(namedDesignerTeam({ chosen: [B, C], lead: A })).toEqual({ lead: B, team: [B, C] });
});

test("a lead with nothing ticked IS the team, because that is what an older draft looks like", () => {
  expect(namedDesignerTeam({ chosen: [], lead: A })).toEqual({ lead: A, team: [A] });
});

test("blanks are absent and duplicates collapse, and the ticked order is otherwise kept", () => {
  expect(namedDesignerTeam({ chosen: ["", " ", C, A, C], lead: "" })).toEqual({ lead: C, team: [C, A] });
});

test("a padded id is trimmed on both sides of the comparison, so the lead still matches", () => {
  expect(namedDesignerTeam({ chosen: [` ${A} `, B], lead: ` ${A} ` })).toEqual({ lead: A, team: [A, B] });
});

test("nobody named sends NEITHER key, which is the only shape an older API can answer", () => {
  expect(designerCreateFields({ chosen: [], lead: "" })).toEqual({});
});

test("ONE designer sends the singular key alone — byte-identical to the body before this wave", () => {
  expect(designerCreateFields({ chosen: [A], lead: "" })).toEqual({ designerUserId: A });
  expect(designerCreateFields({ chosen: [], lead: A })).toEqual({ designerUserId: A });
});

test("several designers send both keys, lead first", () => {
  expect(designerCreateFields({ chosen: [A, B], lead: B })).toEqual({
    designerUserId: B,
    designerUserIds: [B, A]
  });
});

test("an empty array is never sent: silence and 'the answer is none' are different sentences", () => {
  const body = designerCreateFields({ chosen: ["", "  "], lead: "" });
  expect(Object.prototype.hasOwnProperty.call(body, "designerUserIds")).toBe(false);
  expect(Object.prototype.hasOwnProperty.call(body, "designerUserId")).toBe(false);
});

test("the cap is the server's, not a number this client chose", () => {
  expect(MAX_NAMED_DESIGNERS).toBe(100);
});

test("a brand-new draft carries an empty team and the current document version", () => {
  const draft = newLocalDraft({ title: "Bhujodi" }, { ownerUserId: "u1" });
  expect(draft.header.designerUserIds).toEqual([]);
  expect(draft.header.designerUserId).toBeNull();
  expect(draft.schemaVersion).toBe(DW_DRAFT_SCHEMA_VERSION);
});

test("a workshop started in a courtyard keeps the designers the admin picked before the signal went", () => {
  const draft = newLocalDraft(
    { title: "Bhujodi", designerUserId: B, designerUserIds: [B, A] },
    { ownerUserId: "u1" }
  );
  expect(draft.header.designerUserId).toBe(B);
  expect(draft.header.designerUserIds).toEqual([B, A]);
  expect(
    designerCreateFields({
      chosen: draft.header.designerUserIds,
      lead: draft.header.designerUserId ?? ""
    })
  ).toEqual({ designerUserId: B, designerUserIds: [B, A] });
});

test("adopting a stranded draft leaves the designers alone — it re-points, it does not re-decide", () => {
  const draft = newLocalDraft(
    { title: "Bhujodi", designerUserId: A, designerUserIds: [A, B] },
    { ownerUserId: "u1" }
  );
  const moved: DwDraft = adoptedIntoWorkshop(draft, "srv-1");
  expect(moved.remoteId).toBe("srv-1");
  expect(moved.header.designerUserIds).toEqual([A, B]);
  expect(moved.header.designerUserId).toBe(A);
});

const root = join(__dirname, "..");

function sourceOf(...parts: string[]): string {
  return readFileSync(join(root, ...parts), "utf8").replace(/\r\n/g, "\n");
}

const picker = sourceOf("components", "designworkshop", "WorkshopDesignerPicker.tsx");
const page = sourceOf("app", "(protected)", "design-workshops", "page.tsx");
const wire = sourceOf("lib", "designWorkshops.ts");
const store = sourceOf("lib", "designWorkshopStore.ts");

test("the picker is a multi-select whose own filter is off, with a capHint naming the server box", () => {
  expect(picker).toContain("<MultiSelectDropdown");
  expect(picker).toContain("searchable={false}");
  expect(picker).toContain('capHint="Search above to find the rest');
  expect(picker).toContain("fetchEligible ?? listEligibleDesignWorkshopViewers");
  expect(picker).toContain("fetchEligibleRef.current(term)");
  expect(picker).toContain("<SearchInput");
});

test("typing in the picker's search box does not arm the form's unsaved-changes prompt", () => {
  expect(picker).toContain("onInput={(event) => event.stopPropagation()}");
});

test("the lead is printed on screen, because a tick order is not a decision anybody can see", () => {
  expect(picker).toContain("namedDesignerTeam({ chosen: values, lead })");
  expect(picker).toContain("The report names");
});

test("the multi-select arms the dirty flag by hand, because a themed control fires no input event", () => {
  expect(page).toContain("onInput={markDirty}");
  expect(page).toContain("markDirty();\n              setDesignerUserIds(next);");
  expect(page).toContain("useLeaveGuard(dirty,");
});

test("the create sends what the picker resolved, through the one shared rule", () => {
  expect(page).toContain("namedDesignerTeam({ chosen: designerUserIds, lead: leadDesignerId })");
  expect(page).toContain("designerUserId: designers.lead || undefined");
  expect(page).toContain("designerUserIds: designers.team");
});

test("PATCH is closed to BOTH designer keys, or a header edit is refused whole", () => {
  expect(wire).toContain('Omit<Partial<DwCreateBody>, "designerUserId" | "designerUserIds">');
});

test("one normaliser decides the wire shape, and both create arms go through it", () => {
  expect(wire).toContain("designerCreateFields({ chosen: designerUserIds ?? [], lead: designerUserId ?? \"\" })");
  expect(store).toContain("designerUserId: draft.header.designerUserId,\n              designerUserIds: draft.header.designerUserIds,");
});

test("the migration rung defaults the team to empty rather than seeding it from the lead", () => {
  expect(store).toContain("export const DW_DRAFT_SCHEMA_VERSION = 4;");
  expect(store).toContain("      case 3:");
  expect(store).toContain("              : []");
});
