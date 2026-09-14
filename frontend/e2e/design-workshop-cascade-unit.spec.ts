import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { WORKSHOP_KIND_FLOOR } from "@/lib/designWorkshops";
import { DEFAULT_WORKSHOP_KIND } from "@/components/forms/DesignWorkshopCascade";

/**
 * THE TWO-BOX CASCADE: type of workshop, then the workshop, then the record's own pickers.
 *
 * The owner's instruction was that every page open on a TYPE box defaulting to the design and
 * prototype workshop, with the workshop box below it searchable and narrowed by that type. What is
 * pinned here is the handful of decisions that make that true and that a later edit can silently
 * undo — each one a real failure rather than a restatement of the code:
 *
 *  1. THE NARROWING GOES TO THE SERVER. `GET /design-workshops` has taken `workshopKind` since the
 *     column landed, and one page of that list is at most `WORKSHOP_OPTION_PAGE_SIZE` rows out of a
 *     much larger table. A `.filter()` over the fetched page would answer "no workshops of this
 *     type" about types that plainly have some — DROPDOWN_DESIGN R5, and the same failure the
 *     search box already pays a debounce to avoid.
 *  2. THE KIND IS IN THE EFFECT'S DEPENDENCIES. Without it the box changes, no request is sent, and
 *     the panel goes on drawing the previous type's rows under the new type's label: confidently
 *     wrong rather than merely stale, because nothing on screen says the list did not move.
 *  3. THE DEFAULT IS A REAL TOKEN. `workshopKind` is validated server-side; a token the registry
 *     does not carry is a 422 on a read nobody asked for.
 *  4. NOTHING HERE IS SAVED. The record stores `designWorkshopId`; the type is a lens over the
 *     list. A second copy beside the record can disagree with `DesignWorkshop.workshopKind`, which
 *     is answered in stage 1 and is the only source of that fact.
 *  5. EVERY MOUNT HAS IT. The field repository shipped its tracer form-by-form, missed four of nine
 *     mounts, and a researcher reported the feature simply absent. A cascade on four of six forms
 *     is the same bug.
 */

const ROOT = join(__dirname, "..");
const read = (rel: string) => readFileSync(join(ROOT, rel), "utf8");

const SELECT = "components/forms/DesignWorkshopSelect.tsx";
const CASCADE = "components/forms/DesignWorkshopCascade.tsx";

/**
 * Every file that mounts the workshop picker. Spelled out rather than globbed, because the thing
 * this list is protecting against is a SEVENTH form arriving with the bare select — and a glob over
 * "files that mention the cascade" would grow to include it only after somebody had already used
 * the cascade there.
 */
const MOUNTS = [
  "app/(protected)/media/page.tsx",
  "app/(protected)/questionnaire/page.tsx",
  "components/forms/ArtisanForm.tsx",
  "components/forms/ProcessForm.tsx",
  "components/forms/ProductForm.tsx",
  "components/forms/ToolForm.tsx"
];

// ══ 1. THE NARROWING IS A QUERY PARAMETER ══════════════════════════════════════════════════════

test("the chosen type is sent to the server, not filtered over the fetched page", () => {
  const source = read(SELECT);
  const call = source.slice(
    source.indexOf("listDesignWorkshops({"),
    source.indexOf("})", source.indexOf("listDesignWorkshops({"))
  );
  expect(
    call,
    "DesignWorkshopSelect no longer passes workshopKind to listDesignWorkshops. One page is at " +
      "most WORKSHOP_OPTION_PAGE_SIZE rows, so narrowing on the client answers 'none of this type' " +
      "about types that have some."
  ).toContain("workshopKind");
});

test("no mount narrows the list by filtering rows it already holds", () => {
  for (const rel of [SELECT, CASCADE]) {
    const source = read(rel);
    expect(
      /\.filter\(\s*\(?\s*\w+\s*\)?\s*=>\s*\w+\.workshopKind/.test(source),
      `${rel} filters rows by workshopKind on the client. That is DROPDOWN_DESIGN R5: the corpus ` +
        `is on the server and the box must ask it.`
    ).toBe(false);
  }
});

// ══ 2. A TYPE CHANGE RE-READS ══════════════════════════════════════════════════════════════════

test("changing the type re-runs the read", () => {
  const source = read(SELECT);
  // The dependency array of the effect that performs the listing.
  const deps = source.slice(source.indexOf("}, [term"), source.indexOf("]", source.indexOf("}, [term")) + 1);
  expect(
    deps,
    "the listing effect no longer depends on workshopKind, so changing the type box sends no " +
      "request and the panel keeps drawing the previous type's rows under the new type's label."
  ).toContain("workshopKind");
});

// ══ 3. THE DEFAULT IS A TOKEN THE SERVER KNOWS ═════════════════════════════════════════════════

test("the default type is one the vocabulary actually carries", () => {
  expect(
    WORKSHOP_KIND_FLOOR.map((option) => option.value),
    `DEFAULT_WORKSHOP_KIND is ${DEFAULT_WORKSHOP_KIND}, which is not in WORKSHOP_KIND_FLOOR. ` +
      `workshopKind is validated server-side, so an unknown token is a 422 on a read nobody asked for.`
  ).toContain(DEFAULT_WORKSHOP_KIND);
});

test("the default is the design and prototype workshop, which is the ruling", () => {
  expect(DEFAULT_WORKSHOP_KIND).toBe("DESIGN_PROTOTYPE_DEVELOPMENT");
});

test("an unset type means every type, by absence", () => {
  const source = read(CASCADE);
  expect(
    source,
    "the cascade no longer passes `kind || undefined`. An empty string on the wire is a type " +
      "nothing matches; `undefined` makes buildQuery omit the parameter, which is R1 — empty means " +
      "everything, BY ABSENCE."
  ).toContain("workshopKind={kind || undefined}");
});

// ══ 4. THE TYPE IS A LENS, NEVER A STORED COLUMN ═══════════════════════════════════════════════

test("the chosen type never reaches a payload", () => {
  const source = read(CASCADE);
  for (const leak of ["body:", "JSON.stringify", "apiFetch", "FormData"]) {
    expect(
      source,
      `${CASCADE} mentions ${leak}. The type is a lens over the list: the record stores ` +
        `designWorkshopId and nothing else, and a second copy beside the record can disagree with ` +
        `DesignWorkshop.workshopKind, which stage 1 answers and which is the only source of it.`
    ).not.toContain(leak);
  }
});

test("changing the type does not clear a chosen workshop", () => {
  const source = read(CASCADE);
  // The cascade owns `kind` and hands the id state straight through. If it ever calls a setter for
  // the workshop id, it has taken on the power to discard a filed record's link.
  for (const setter of ["setWorkshopId(", "prefillWorkshopId("]) {
    expect(
      source,
      `${CASCADE} calls ${setter}. Narrowing a list is not the same act as discarding an answer: a ` +
        `product filed last season under another type opens with that workshop chosen, and the type ` +
        `box must never be what detaches it.`
    ).not.toContain(setter);
  }
});

// ══ 5. IT IS ON EVERY FORM THAT HAD THE BARE PICKER ════════════════════════════════════════════

test("every workshop picker mount is the cascade", () => {
  for (const rel of MOUNTS) {
    const source = read(rel);
    expect(source, `${rel} does not mount <DesignWorkshopCascade>.`).toMatch(
      /<DesignWorkshopCascade[\s/>]/
    );
    expect(
      /<DesignWorkshopSelect[\s/>]/.test(source),
      `${rel} still mounts the bare <DesignWorkshopSelect>. Six forms carry this picker and a ` +
        `cascade on five of them is the tracer bug again — the missing one reads as the feature ` +
        `being absent, not as one screen differing.`
    ).toBe(false);
  }
});

test("the cascade is the only place the bare select is mounted", () => {
  const source = read(CASCADE);
  expect(
    source,
    "the cascade no longer renders DesignWorkshopSelect, so the two boxes are no longer one control."
  ).toMatch(/<DesignWorkshopSelect[\s/>]/);
});
