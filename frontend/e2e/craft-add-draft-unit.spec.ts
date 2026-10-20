import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { reviewRecordTarget } from "@/components/crafts/CraftReviewQueue";
import { DW_CRAFT_NAME_FROM_LIST, stageCraftNameLocked } from "@/components/designworkshop/FieldInput";
import { reviewEditableFields } from "@/components/review/reviewEditFields";
import { addCraftHref, safeReturnPath } from "@/lib/craftAddDraft";
import type { DwField } from "@/lib/designWorkshops";

/*
  THE CRAFT FINDINGS OF 2026-10-20 (REQUIREMENTS_2026-10-20 §2.4–§2.6), held as pure calls and
  source reads: there is no React renderer in this repository.
*/

const ROOT = join(__dirname, "..");
const read = (rel: string) => readFileSync(join(ROOT, rel), "utf8").replace(/\r\n/g, "\n");

test.describe("Add it to the list saves the form's draft first", () => {
  test("only a same-origin path is a place to come back to", () => {
    expect(safeReturnPath("/artisans/new")).toBe("/artisans/new");
    expect(safeReturnPath("/tools/abc/edit?focus=craft")).toBe("/tools/abc/edit?focus=craft");
    expect(safeReturnPath("//evil.example")).toBeNull();
    expect(safeReturnPath("/\\evil.example")).toBeNull();
    expect(safeReturnPath("https://evil.example")).toBeNull();
    expect(safeReturnPath(null)).toBeNull();
  });

  test("the link to /crafts carries where to come back to", () => {
    expect(addCraftHref("/crafts?new=Ajrakh", "/artisans/new")).toBe("/crafts?new=Ajrakh&return=%2Fartisans%2Fnew");
  });

  test("every record form that offers Add it to the list wraps its picker in the draft boundary", () => {
    for (const file of ["components/forms/ArtisanForm.tsx", "components/forms/ProductForm.tsx", "components/forms/ToolForm.tsx"]) {
      const source = read(file);
      const offers = (source.match(/canAddCraft=\{/g) ?? []).length;
      expect(offers, file).toBeGreaterThan(0);
      expect((source.match(/<CraftAddDraftBoundary/g) ?? []).length, file).toBe(offers);
      expect(source, file).toContain("onLeave={resetDirty}");
    }
    const boundary = read("components/forms/CraftAddDraftBoundary.tsx");
    // The anchor is caught before it navigates: a plain navigation would fire the unload prompt.
    expect(boundary).toContain("onClickCapture");
    expect(boundary).toContain("event.preventDefault()");
    expect(boundary).toContain("writeCraftAddDraft(");
  });

  test("saving the craft goes back to the form and picks it", () => {
    const page = read("app/(protected)/crafts/page.tsx");
    expect(page).toContain('safeReturnPath(searchParams.get("return"))');
    expect(page).toContain("markCraftAdded(saved.id)");
  });
});

test.describe("no typed craft on the review edit or on stage 1", () => {
  test("a product's and a tool's craft are shown read-only in review edit", () => {
    for (const kind of ["product", "tool"]) {
      const craft = reviewEditableFields(kind).find((field) => field.key === "craftName");
      expect(craft, kind).toEqual({ key: "craftName", label: "Craft", readOnly: true });
    }
    expect(read("components/review/ReviewEditPanel.tsx")).toContain(".filter((field) => !field.readOnly)");
  });

  test("stage 1's craft name is read-only wherever its entity carries a craft link", () => {
    const craftName = { key: "craftName" } as DwField;
    const craftRef = { key: "craftRef", refModel: "Craft" } as DwField;
    expect(stageCraftNameLocked({ fields: [craftName, craftRef] }, craftName)).toBe(true);
    // A craft name copied from a tool or product record has no craft link beside it.
    expect(stageCraftNameLocked({ fields: [craftName] }, craftName)).toBe(false);
    // The handset says the same words.
    const android = readFileSync(
      join(ROOT, "..", "android", "app", "src", "main", "java", "com", "designprototype", "workshop", "ui", "designworkshop", "FieldRenderer.kt"),
      "utf8"
    );
    expect(android).toContain(`DW_CRAFT_NAME_FROM_LIST = "${DW_CRAFT_NAME_FROM_LIST}"`);
  });
});

test.describe("Needs review lists the records whose craft was typed", () => {
  test("each record opens where its craft is picked", () => {
    expect(reviewRecordTarget("artisan", "a1")).toEqual({ noun: "Artisan", href: "/artisans/a1/edit" });
    expect(reviewRecordTarget("productdocumentation", "p1")?.href).toBe("/products/p1/edit");
    expect(reviewRecordTarget("tooldocumentation", "t1")?.href).toBe("/tools/t1/edit");
    expect(reviewRecordTarget("designworkshop", "d1")?.href).toBe("/design-workshops/d1/edit");
    expect(reviewRecordTarget("annualplanentry", "e1")?.href).toBe("/annual-plan");
    expect(reviewRecordTarget("somethingelse", "x")).toBeNull();
  });

  test("the queue reads the server's review list and offers Pick craft", () => {
    const queue = read("components/crafts/CraftReviewQueue.tsx");
    expect(queue).toContain('apiFetch<CraftReviewQueueAnswer>("/crafts/review")');
    expect(queue).toContain("Pick craft");
    expect(queue).toContain("Every craft is on the list.");
  });
});

test("the Records column is for every reader", () => {
  const page = read("app/(protected)/crafts/page.tsx");
  expect(page).toContain("<ResizableTh>Records</ResizableTh>");
  expect(page).not.toContain("{referenceCounts ? <ResizableTh>Records</ResizableTh> : null}");
  expect(page).toContain("loadCraftReferenceCounts()");
});
