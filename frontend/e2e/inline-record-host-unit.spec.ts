import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { inlineSeed, scopeNoticeLines } from "@/components/designworkshop/StageReferenceField";
import { seedHasArtisan } from "@/components/forms/inlineRecordHost";
import { MEASUREMENT_GRID_PURPOSE } from "@/components/media/GridMeasurement";
import type { DwEntity, DwField, DwReferencePayload } from "@/lib/designWorkshops";

/**
 * FILLING IN AN ARTISAN, PRODUCT, TOOL OR PROCESS THROUGH THE WORKSHOP FLOW — the four ways it
 * failed in the field, pinned.
 *
 * `InlineRecordDialog` mounts the four real record forms over a half-filled stage so a designer who
 * finds the record missing does not have to abandon a 22-stage form to make it. Every one of the
 * defects below is that feature failing on a form that was written for a full-page route and never
 * re-audited for its second host:
 *
 *  1. **Cancel navigated.** All three of `ArtisanForm` / `ProductForm` / `ToolForm` ended their
 *     Cancel — and their unsaved-changes "Discard" — in `router.back()`. The dialog is not a route,
 *     so back popped the real history entry and the stage disappeared. Cancel is the most natural
 *     way to back out of a modal and it was the one control that lost the designer's place.
 *  2. **The create was filed against the wrong parent.** The dialog passed the forms no context at
 *     all, though the picker rendering it held the row's artisan and the workshop. The forms fell
 *     back to the carry bag — the last artisan documented anywhere — so a product created from
 *     Kamla's row was filed under somebody else, excluded by the server's own cascade from the list
 *     it was made in, undescribable, and left two required boxes blank until the stage 422'd.
 *  3. **An offline create said nothing.** The queued branch returned silently and relied on
 *     `OutboxBanner`, which is mounted outside the dialog's portal on a body whose scroll the
 *     dialog has locked. The button flipped back from "Saving…" and nothing else changed — read as
 *     a failed save, answered by pressing it again, three copies in the outbox.
 *  4. **The duplicate prompt navigated.** "Open the existing record" did `router.push` to that
 *     artisan's edit page, so acting on the very thing the prompt exists to surface cost the stage.
 *
 * TWO MORE share this file because they are the same class of thing — a repository record failing to
 * reach a stage:
 *
 *  5. **The measurement-grid marker**, which stops a sheet of graph paper being printed in a
 *     ministry report as the photograph of a tool.
 *  6. **An empty picker read as an empty repository.** `ScopeNotice` spoke only when the design
 *     workshop was UNLINKED. A LINKED one with nothing attached to its workshop record showed the
 *     generic "No records to choose from yet." — a claim about the repository where the truth was a
 *     claim about the filter — and the designer typed the records in by hand.
 *
 * ── WHICH HALF OF THIS IS EXECUTED, AND WHAT THE OTHER HALF CAN AND CANNOT CATCH ──────────────
 * Stated plainly because it is a weakness rather than a preference: there is no React renderer in
 * this repository's devDependencies (Playwright is the whole of it), so a component cannot be
 * mounted at all and a rule living inside a component body can only be READ. `discarded-work-unit`
 * and `derived-fields-unit` read source for the same reason.
 *
 * WHERE A RULE COULD BE LIFTED OUT OF A COMPONENT IT WAS, and those are the tests worth trusting:
 * {@link inlineSeed} and {@link scopeNoticeLines} are real functions called with real arguments
 * here, over the inputs that actually occur — a roster-entry id where an `Artisan` id is expected,
 * a linked workshop with nothing under it. Both were lifted because their failure is SILENT: a
 * product filed under a roster entry looks like a product, and a picker that explains itself wrongly
 * looks like a picker.
 *
 * A SOURCE ASSERTION PINS THE SPELLING OF A LINE, NOT ITS EFFECT, and that limit is real rather than
 * theoretical. `expect(source).toContain("leave();")` proves the discard branch names the exit
 * function; it cannot prove `leave` is reached, and it would pass with the literal sitting in a
 * branch nothing runs. So every one of them is written to fail against the OLD tree — that much
 * they do prove — and where an assertion could be aimed at a PLACE rather than at a count, it is:
 * see the grid-marker tests, which slice the two upload calls out of the form and require the marker
 * inside them and absent everywhere else, because "both literals are present somewhere" was true of
 * the version that wrote the marker onto the wrong batch.
 *
 * None of them proves anything PAINTS. `inline-record-create.spec.ts` and
 * `inline-create-hydration.spec.ts` are where a browser is driven, and they need the stack.
 */

const ROOT = join(__dirname, "..");
/**
 * One source file, with its line endings normalised.
 *
 * The working trees on this project are checked out CRLF on Windows and LF elsewhere, and an
 * assertion about a multi-line block would otherwise pass on one developer's machine and fail on
 * the next for a reason that has nothing to do with the code.
 */
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

const ARTISAN_FORM = "components/forms/ArtisanForm.tsx";
const PRODUCT_FORM = "components/forms/ProductForm.tsx";
const TOOL_FORM = "components/forms/ToolForm.tsx";
const PROCESS_FORM = "components/forms/ProcessForm.tsx";
const DIALOG = "components/designworkshop/InlineRecordDialog.tsx";
const PICKER = "components/designworkshop/StageReferenceField.tsx";

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The seed — the one rule that is executed rather than read
 * ──────────────────────────────────────────────────────────────────────────── */

function field(overrides: Partial<DwField> & { key: string }): DwField {
  return { label: overrides.key, type: "REF", tier: "BASIC", required: false, ...overrides } as DwField;
}

/** Stage 6 — the cascade whose filter really does hold an `Artisan` id. */
const EXISTING_PRODUCT: DwEntity = {
  key: "existingProduct",
  name: "DwExistingProduct",
  cardinality: "COLLECTION",
  title: "Existing products",
  description: "",
  parent: "",
  labelField: "name",
  fields: [
    field({ key: "artisanRef", refModel: "Artisan", refScope: "WORKSHOP" }),
    field({ key: "productRef", refModel: "ProductDocumentation", refScope: "WORKSHOP", refFilterBy: "artisanRef" }),
    field({ key: "artisanName", type: "TEXT" })
  ]
};

/** Stage 13 — the same-named cascade whose filter holds a `DwParticipant` ROSTER ENTRY id. */
const PROTOTYPE: DwEntity = {
  key: "prototype",
  name: "DwPrototype",
  cardinality: "COLLECTION",
  title: "Prototypes",
  description: "",
  parent: "",
  labelField: "name",
  fields: [
    field({ key: "artisanRef", refModel: "DwParticipant", refScope: "ALL" }),
    field({ key: "productRef", refModel: "ProductDocumentation", refScope: "WORKSHOP", refFilterBy: "artisanRef" }),
    field({ key: "productName", type: "TEXT" })
  ]
};

const productRefOf = (entity: DwEntity) => entity.fields.find((f) => f.key === "productRef") as DwField;

test("a product created from stage 6's cascade is seeded with the artisan the row names", () => {
  const seed = inlineSeed({
    entity: EXISTING_PRODUCT,
    field: productRefOf(EXISTING_PRODUCT),
    row: { artisanRef: "artisan_kamla", artisanName: "Kamla Devi" },
    filterValue: "artisan_kamla",
    linkedWorkshopId: null
  });
  expect(seed.artisanId).toBe("artisan_kamla");
  // The NAME as well as the id: `artisanName` is a REQUIRED free-text box on the product form, and a
  // form that opens with a linked artisan and a blank "Artisan name" is one the designer has to
  // retype an answer into that is already on screen beside it.
  expect(seed.artisanName).toBe("Kamla Devi");
});

test("a product created from stage 13's roster cascade is NOT seeded with the participant id", () => {
  /*
    THE NEGATIVE CONTROL FOR THE WHOLE SEED. `prototype.artisanRef` holds a `DwParticipant` entry
    id, not an `Artisan` id — the maker was chosen from stage 3's list of who was in the room — and
    the SERVER resolves it with `_artisan_id_behind`, a rule it deliberately spares the clients. A
    browser cannot follow it, so seeding the value it happens to be holding would write a
    roster-entry id into `ProductDocumentation.artisanId`: a foreign key pointing at the wrong
    table, on a record nothing on any screen would flag. Filing under nobody is strictly better.
  */
  const seed = inlineSeed({
    entity: PROTOTYPE,
    field: productRefOf(PROTOTYPE),
    row: { artisanRef: "dwentry_participant_7" },
    filterValue: "dwentry_participant_7",
    linkedWorkshopId: null
  });
  expect(seed.artisanId).toBeUndefined();
  expect(seed.artisanName).toBeUndefined();
});

test("an uncascaded picker seeds no artisan at all", () => {
  // The roster picker itself (`participant.artisanRef`) and every other plain REF. There is no
  // parent on the row to carry, and inventing one would be the claim Android's inline record host
  // refuses to make.
  const seed = inlineSeed({ field: field({ key: "artisanRef", refModel: "Artisan" }), linkedWorkshopId: "ws_1" });
  expect(seed.artisanId).toBeUndefined();
  expect(seed.workshopId).toBe("ws_1");
});

test("nothing is guessed when the design workshop has no linked workshop", () => {
  /*
    The unlinked case is real and the references endpoint already reports it (`scopedToWorkshop:
    false`, which `ScopeNotice` prints out loud). Filling a workshop in there would file the record
    against a sitting it was not documented at, which is worse than the widened list the designer
    has already been warned about.
  */
  const seed = inlineSeed({
    entity: EXISTING_PRODUCT,
    field: productRefOf(EXISTING_PRODUCT),
    row: { artisanRef: "artisan_kamla", artisanName: "Kamla Devi" },
    filterValue: "artisan_kamla",
    linkedWorkshopId: null
  });
  expect(seed.workshopId).toBeUndefined();
  expect(Object.keys(seed).sort()).toEqual(["artisanId", "artisanName"]);
});

test("a cascade with nothing chosen above it seeds no artisan", () => {
  const seed = inlineSeed({
    entity: EXISTING_PRODUCT,
    field: productRefOf(EXISTING_PRODUCT),
    row: {},
    filterValue: "",
    linkedWorkshopId: "ws_1"
  });
  expect(seed.artisanId).toBeUndefined();
});

test("seedHasArtisan is what decides whether the carry banner may still claim the artisan", () => {
  expect(seedHasArtisan(undefined)).toBe(false);
  expect(seedHasArtisan({ workshopId: "ws_1" })).toBe(false);
  expect(seedHasArtisan({ artisanId: "a1" })).toBe(true);
  expect(seedHasArtisan({ artisanName: "Kamla Devi" })).toBe(true);
});

test("the seed is create-only, and the DIALOG is what enforces it", () => {
  /*
    THIS USED TO ASSERT ONE SUBSTRING IN ONE CALLER AND NOTHING ELSE, which is the whole reason it
    needed repairing: `InlineRecordDialog` passed `seed` to all three forms UNCONDITIONALLY, and
    `StageReferenceMultiPicker` passes a bare `seed={seed}`. The single picker's ternary was the only
    gate in the tree, and this test pinned exactly that one line — blind to the two places that had
    the rule wrong, on a spec whose title claims the rule holds.

    THE FORMS CANNOT BE THE GUARD, which is why the assertion moved here: they resolve the seed with
    `??` (`initial?.artisanId ?? seed?.artisanId`), so on a record whose artisan or workshop column
    is NULL — the row this lane exists to let a designer fix — the seed would fill it, the form would
    submit a parent nobody chose, and `useWorkshopSelection` would mark it `touched` so the carry
    banner would not mention it either.
  */
  const dialog = read(DIALOG);
  expect(dialog, "one gate, computed once from the edit flag it already has").toContain(
    "const seedForForm = editing ? undefined : seed;"
  );
  // All three forms take the GATED value. `seed` reaching a form directly is the defect.
  expect(dialog.match(/seed=\{seedForForm\}/g)?.length, "Artisan, Product and Tool").toBe(3);
  expect(dialog).not.toMatch(/^\s+seed=\{seed\}$/m);

  // And the two call sites are now belt and braces over that rather than the whole of it.
  const picker = read(PICKER);
  expect(picker).toContain('seed={inlineDialog.mode === "create" ? seed : undefined}');
});

/* ───────────────────────────────────────────────────────────────────────────
 * 5. An empty list explains ITSELF, rather than being read as an empty repository
 * ────────────────────────────────────────────────────────────────────────── */

/** A references answer, defaulting to the shape a healthy WORKSHOP-scoped list comes back in. */
function payload(overrides: Partial<DwReferencePayload> = {}): DwReferencePayload {
  return {
    model: "ProductDocumentation",
    scope: "WORKSHOP",
    scopedToWorkshop: true,
    filtered: false,
    truncated: false,
    options: [{ id: "p1", label: "Sambalpuri saree", sublabel: "" }],
    ...overrides
  } as DwReferencePayload;
}

const WORKSHOP_SCOPED = field({ key: "productRef", refModel: "ProductDocumentation", refScope: "WORKSHOP" });

test("a LINKED workshop with nothing under it says the list is narrowed, not that the repository is empty", () => {
  /*
    THE DEFECT: `ScopeNotice` only ever spoke when the design workshop was UNLINKED. A linked one
    whose workshop record has no artisans, products or tools attached is the commoner case and said
    nothing at all — the picker fell through to its generic "No records to choose from yet.", which
    is a claim about the REPOSITORY when the truth is a claim about the FILTER. A designer reads it
    as "nothing has been documented", closes the picker and types thirty products in by hand, which
    is the exact behaviour the reference feature exists to end.
  */
  const lines = scopeNoticeLines(WORKSHOP_SCOPED, payload({ options: [] }));
  expect(lines.length).toBe(1);
  expect(lines[0]).toContain("narrowed to that workshop");
  // And it must say what to DO, or it is only a better-worded dead end.
  expect(lines[0]).toContain("Create the record here");
});

test("a list that came back with records says nothing at all", () => {
  // The notice is for a designer staring at an empty list. On a full one it is noise on every open.
  expect(scopeNoticeLines(WORKSHOP_SCOPED, payload())).toEqual([]);
});

test("the cascade's own empty list is left to the cascade to explain", () => {
  /*
    `payload.filtered` means the server narrowed to the parent row's record, and the picker's empty
    line already names that row ("That record has nothing documented under it yet"). Two competing
    explanations of one empty list is worse than the generic one, so this stays quiet.
  */
  expect(scopeNoticeLines(WORKSHOP_SCOPED, payload({ options: [], filtered: true }))).toEqual([]);
});

test("an UNLINKED workshop still gets the sentence it always got, and only that one", () => {
  /*
    THE NEGATIVE CONTROL FOR THE NEW BRANCH. Unlinked means the server widened the list to the whole
    table; saying it is "narrowed to that workshop" there would be false, and would send a designer
    looking for a workshop link that is already the thing being complained about.
  */
  const lines = scopeNoticeLines(WORKSHOP_SCOPED, payload({ scopedToWorkshop: false, options: [] }));
  expect(lines.length).toBe(1);
  expect(lines[0]).toContain("not linked to a workshop record yet");
});

test("an ALL-scoped picker is never told anything about workshops", () => {
  // Stage 13's roster picker and every other unscoped REF: an empty list there really is an empty
  // repository, and a sentence about workshop narrowing would be a wrong explanation of a true state.
  const all = field({ key: "artisanRef", refModel: "DwParticipant", refScope: "ALL" });
  expect(scopeNoticeLines(all, payload({ scope: "ALL", options: [] }))).toEqual([]);
});

test("the truncation sentence still comes through, and can share the line", () => {
  // A capped list is a different fact from a narrow one and both can be true at once; neither may
  // silence the other, because a list that quietly stops is this codebase's most repeated bug class.
  const lines = scopeNoticeLines(WORKSHOP_SCOPED, payload({ scopedToWorkshop: false, truncated: true }));
  expect(lines.length).toBe(2);
  expect(lines[1]).toContain("matches are listed");
});

test("the stage page puts the linked workshop within the pickers' reach", () => {
  const page = read("app/(protected)/design-workshops/[id]/stages/[stageKey]/page.tsx");
  expect(page).toContain("LinkedWorkshopProvider");
  // Off the DRAFT's header, not a fetch: inline creation is used in courtyards with no signal.
  expect(page).toContain("setLinkedWorkshopId(draft.header.workshopId ?? null)");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 1. Cancel
 * ──────────────────────────────────────────────────────────────────────────── */

for (const [name, path] of [
  ["ArtisanForm", ARTISAN_FORM],
  ["ProductForm", PRODUCT_FORM],
  ["ToolForm", TOOL_FORM]
] as const) {
  test(`${name}'s Cancel asks the host to leave rather than popping history`, () => {
    const source = read(path);
    // The exit is one function, so the button and the discard prompt cannot drift apart.
    expect(source).toContain("function leave() {\n    if (onCancel) onCancel();\n    else router.back();\n  }");
    expect(source).toContain("function handleBack() {\n    if (dirty) setBackPromptOpen(true);\n    else leave();\n  }");
    expect(source).toContain('onClick={handleBack}');
  });

  test(`${name}'s "Discard" goes through the same exit as Cancel`, () => {
    const source = read(path);
    /*
      The dirty prompt is KEPT in the dialog host — closing the dialog still throws the typing away,
      so the question is as load-bearing there as on a page. What changed is only what "Discard"
      does once it has been answered. A `router.back()` left anywhere in this file is the defect.
    */
    const discard = source.slice(source.indexOf("onDiscard={() => {"), source.indexOf("onSave={() => {"));
    expect(discard).toContain("leave();");
    // `leave` is the only place a CALL to `router.back()` may still stand, and only as the fallback
    // for the page host. A second one anywhere in the file is the defect this test is about. The
    // semicolon is what keeps this off the prose above, which names the function it replaced.
    expect(discard).not.toContain("router.back();");
    expect(source.match(/router\.back\(\);/g)?.length).toBe(1);
  });
}

/* ────────────────────────────────────────────────────────────────────────────
 * 3. The offline create
 * ──────────────────────────────────────────────────────────────────────────── */

for (const [name, path] of [
  ["ArtisanForm", ARTISAN_FORM],
  ["ProductForm", PRODUCT_FORM],
  ["ToolForm", TOOL_FORM],
  ["ProcessForm", PROCESS_FORM]
] as const) {
  test(`${name} reports a queued save to its host instead of returning silently`, () => {
    const source = read(path);
    expect(source).toContain("onQueued");
    // The branch itself, not merely the prop: the three page-hosted forms used to `return` straight
    // after scrolling to a banner that a dialog host cannot show.
    const queued = source.slice(source.indexOf("outcome.queued"));
    expect(queued.slice(0, 2000)).toMatch(/if \(onQueued\)/);
  });
}

test("the picker says a queued record was saved, is unlinked, and what to do next", () => {
  const source = read(PICKER);
  const notice = source.slice(source.indexOf("const QUEUED_OFFLINE_NOTICE"), source.indexOf("Single select"));
  // Three claims, all load-bearing: saved (or they create a duplicate), NOT linked (or they submit
  // the stage believing it is), and the way out.
  expect(notice).toContain("saved on this device");
  expect(notice).toContain("nothing could be linked here");
  expect(notice).toContain("choose it then");
  // Both pickers say it, so a designer meets one explanation of this state rather than two.
  expect(source.match(/QUEUED_OFFLINE_NOTICE/g)?.length).toBeGreaterThanOrEqual(3);
});

test("no placeholder id is ever written into the REF field", () => {
  /*
    A REF must hold a real server id: `hydrate_entries`, `canonical_divergence` and the report's
    `ReferencedRecord` join all resolve on it, so a client-invented id would render for ever as a
    reference to a deleted record. The queued handler supersedes the pending hydration and writes a
    notice; it must not call `onChange` with anything.
  */
  const source = read(PICKER);
  const handler = source.slice(source.indexOf("onQueued={() => {"));
  expect(handler.slice(0, 200)).toContain("supersede();");
  expect(handler.slice(0, 200)).not.toContain("onChange(");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. The duplicate prompt
 * ──────────────────────────────────────────────────────────────────────────── */

test("the duplicate prompt hands the existing artisan back when there is a host to hand it to", () => {
  const source = read(ARTISAN_FORM);
  const handler = source.slice(source.indexOf("onOpenExisting={() => {"), source.indexOf("onDiscard={discardEntry}"));
  expect(handler).toContain("if (onUseExisting) {\n            onUseExisting(conflict.existingArtisan);\n            return;\n          }");
  // The page host keeps its navigation, which is right there.
  expect(handler).toContain("router.push(`/artisans/${conflict.existingArtisan.id}/edit`)");
});

test("nothing from the conflict payload reaches the row", () => {
  /*
    `ArtisanIdentityConflict` carries `maskedValue` — a masked Aadhaar or Pehchan string — and a
    masked identity number must never be written onto a stage entry. The picker's handler may read
    the id and the name and nothing else, and the name only as the term `describeCreated` searches
    with; every value that lands on the row comes back from the server's own payload.
  */
  const source = read(PICKER);
  const handler = source.slice(source.indexOf("onUseExisting={(artisan) => {"));
  expect(handler.slice(0, 300)).toContain("{ id: artisan.id, name: artisan.name }");
  expect(handler.slice(0, 300)).not.toContain("maskedValue");
  // And the record handed on is the two-key object above and nothing wider: `adoptCreated` reads
  // only `record.id` and `createdLabel(record)`, so widening this object is the only way a masked
  // number could ever get near `hydrateFromReference`.
  expect(handler.slice(0, 300)).not.toContain("conflict");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The dialog wires all four, for all four models
 * ──────────────────────────────────────────────────────────────────────────── */

test("InlineRecordDialog gives every form a way out that is not a navigation", () => {
  const source = read(DIALOG);
  // Three `onCancel={onClose}` for the forms that gained one, plus ProcessForm's, which always had it.
  expect(source.match(/onCancel=\{onClose\}/g)?.length).toBe(4);
  expect(source.match(/onQueued=\{reportQueued\}/g)?.length).toBe(4);
  // The GATED seed, three times — see "the seed is create-only" above for why the gate is here and
  // not at the call sites.
  expect(source.match(/seed=\{seedForForm\}/g)?.length).toBe(3);
  expect(source).toContain("onUseExisting={onUseExisting ? adoptExisting : undefined}");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The measurement-grid marker — a three-surface contract
 * ──────────────────────────────────────────────────────────────────────────── */

test("the grid marker is the exact string the server and the handset agree on", () => {
  /*
    The web writes it, Android writes the identical string, and the server SORTS any candidate
    carrying it last (a sort key in `_reference_photos`, never a `WHERE` — nothing is excluded into a
    blank). Change it in one place and nothing errors: the ministry report just starts printing ruled
    graph paper captioned as the tool again, which is what the oldest-first rule did before the
    marker existed, because a grid shot is the first photograph most products ever get.

    IT IS A RESTATEMENT OF THE CONSTANT'S OWN DEFINITION, and that is admitted rather than dressed
    up. Its value is as one end of a three-surface pin — `MeasurementGridMarkerTest.kt` holds the
    mirror image and the server names the same literal — so a rename shows up as two red tests
    instead of a silently broken contract. What it CANNOT catch is the web writing the right string
    into the wrong place, which is the failure that would actually reach a report; the two placement
    tests below are what cover that.
  */
  expect(MEASUREMENT_GRID_PURPOSE).toBe("MEASUREMENT_GRID");
});

for (const [name, path] of [
  ["ProductForm", PRODUCT_FORM],
  ["ToolForm", TOOL_FORM]
] as const) {
  test(`${name} marks its grid photographs on both the online and the queued path, and marks nothing else`, () => {
    /*
      ASSERTED BY PLACEMENT, NOT BY A COUNT. This test used to be
      `expect(source.match(/extraMetadata: { purpose: MEASUREMENT_GRID_PURPOSE }/g)?.length).toBe(2)`
      — which passes with both literals sitting in the WRONG upload call, and the wrong call is the
      only interesting way to get this wrong: writing the marker onto the field-media batch would
      sort the researcher's real catalogue photographs behind the graph paper, which is the printed
      defect this whole marker exists to end, inverted.

      TWO PLACES, because a marker written on one leaves half the fleet printing graph paper: the
      `saveOrQueue` media batch (offline is the ordinary case in a village) and the `uploadMediaFile`
      loop that runs when the save went straight to the server.
    */
    const source = read(path);
    const marker = /extraMetadata: \{ purpose: MEASUREMENT_GRID_PURPOSE \}/;

    // The queued batch: the entry built from `gridFiles`, up to where the field-media entry begins.
    const queuedGrid = source.slice(
      source.indexOf("...(Object.entries(gridFiles) as [GridGroup, File][]).map("),
      source.indexOf("files: mediaFiles,")
    );
    expect(queuedGrid, "the queued grid entry carries the marker").toMatch(marker);

    // The online loop: the awaited `uploadMediaFile` per grid file, up to the field-media batch.
    const onlineGrid = source.slice(
      source.indexOf("for (const [group, file] of Object.entries(gridFiles)"),
      source.indexOf("if (mediaFiles.length) {")
    );
    expect(onlineGrid, "the online grid upload carries the marker").toMatch(marker);

    // AND NOWHERE ELSE. Everything outside those two windows — both field-media uploads above all —
    // must be free of it, or the record's real photographs get sorted behind the calibration shot.
    const elsewhere = source.split(queuedGrid).join("").split(onlineGrid).join("");
    expect(elsewhere, "no other upload on this form is marked as a measurement grid").not.toMatch(marker);
  });
}

/* ────────────────────────────────────────────────────────────────────────────
 * The leave guard, which the dialog made into a stack
 * ──────────────────────────────────────────────────────────────────────────── */

test("the unsaved-changes guard holds a stack, and a form only ever disarms itself", () => {
  /*
    `UnsavedChangesProvider` held ONE slot under the reasoning "only one form is ever on screen at a
    time". `InlineRecordDialog` made that false: it mounts a record form — all four of which call
    `useLeaveGuard` — on top of whatever page opened it, which may itself be a guarded form. The
    inner form overwrote the outer one on mount, and its cleanup ran `register(null)` unconditionally
    on unmount, so opening the dialog once and closing it left the page underneath with NO
    interceptor for the rest of its life: its back arrow silently stopped warning about unsaved work.

    Nothing in the tree hits it today — the design-workshop stage page keeps its draft in IndexedDB
    and registers no guard, so no screen that currently hosts a reference picker also holds one —
    which is why it was worth closing while it was cheap and while the reason was still legible.
  */
  const guard = read("components/UnsavedChangesGuard.tsx");
  expect(guard).toContain("register: (interceptor: LeaveInterceptor) => () => void;");
  expect(guard).toContain("const interceptors = useRef<LeaveInterceptor[]>([]);");
  // The TOPMOST answers: the innermost form is the one being typed in, and its dialog is the one on
  // top of the stack of dialogs.
  expect(guard).toContain("const top = interceptors.current[interceptors.current.length - 1];");
  // Removal BY IDENTITY, not by popping: a teardown order React is free to choose must not be able
  // to disarm the wrong form.
  expect(guard).toContain("interceptors.current.filter((entry) => entry !== next)");
  expect(guard).toContain("return unregister;");
  // The comment that made the old shape look correct is gone with it.
  expect(guard).not.toContain("only one form is ever on screen at a time so a single slot is enough");
});

/* ────────────────────────────────────────────────────────────────────────────
 * Craft: no inline create, and a way forward said out loud
 * ──────────────────────────────────────────────────────────────────────────── */

test("Craft is still not inline-creatable, and now the reason is written down", () => {
  const dialog = read(DIALOG);
  expect(dialog).toContain('export const INLINE_CREATABLE = ["Artisan", "ProductDocumentation", "ToolDocumentation", "Process"] as const;');
  // The docstring defended only the `Dw…` exclusions and said nothing whatever about Craft, which is
  // a genuine repository model with its own `ReferenceModel` and a stage-1 picker. An undocumented
  // omission on the first control a designer touches reads as an oversight, and the next reader
  // "fixes" it.
  expect(dialog).toContain("CRAFT IS ABSENT, AND THAT IS A DECISION RATHER THAN AN OVERSIGHT");
  expect(dialog).toContain("SHARED TAXONOMY");
  // And the condition under which the decision would be reversed, so it is a position rather than a
  // refusal.
  expect(dialog).toContain("IF THIS IS EVER REVERSED");
});

test("the craft picker offers the crafts page in a new tab, and only to somebody who can act on it", () => {
  const source = read(PICKER);
  const branch = source.slice(source.indexOf('{field.refModel === "Craft" && !disabled ? ('));
  const head = branch.slice(0, 2400);
  expect(head).toContain('href="/crafts"');
  // A NEW TAB, which is the whole point of this lane rather than a stylistic choice: the stage stays
  // open behind it, exactly as the dialog keeps it open for the other four models.
  expect(head).toContain('target="_blank"');
  expect(head).toContain('rel="noopener"');
  expect(head).toContain("{CRAFT_REGISTER_LINK}");
  expect(source).toContain('const CRAFT_REGISTER_LINK = "Add or correct a craft on the crafts page (opens in a new tab)";');

  /*
   * AND THE RANK GATE, which is the half that was missing and which turned this control into a dead
   * end for most of the people it was written for.
   *
   * `/crafts` renders its form only when `canManageCrafts(user)` — Professor and above — and below
   * that rank the page says "Ask the master admin for craft creation access". The anchor was gated on
   * `field.refModel === "Craft" && !disabled` and on NOTHING else, so a designer at stage 1 read
   * "Add or correct a craft on the crafts page", left the picker, opened a new tab and landed on a
   * read-only vocabulary list. The one control in the product added specifically so the remedy would
   * not have to be remembered was sending them somewhere they cannot act.
   *
   * Asserted through the SAME predicate the page uses rather than a rank comparison of its own: two
   * opinions about who may edit the taxonomy is how the two screens come to disagree.
   */
  expect(head).toContain("craftManager ? (");
  expect(source).toContain('import { canManageCrafts } from "@/lib/permissions";');
  expect(source).toContain("const craftManager = canManageCrafts(user);");

  // AND BELOW THAT RANK, A SENTENCE THAT IS BOTH TRUE AND ACTIONABLE — not an absence. The way
  // forward for a craft that is not in the register is the typed `craftName` box, and `craftRef` is
  // optional, so saying so keeps stage 1 unblocked instead of sending anybody looking for signal or
  // for a page they cannot use.
  expect(head).toContain("{CRAFT_REGISTER_BLOCKED}");
  expect(source).toContain("Adding a craft to the register needs craft-creation access");
  expect(source).toContain("the craft's name in the Craft box above and the stage still saves");
  // Android's picker offers NEITHER sentence today — its empty craft list is a claim about this
  // device's cache. When it gains its half it must carry these words, not a second phrasing.
  expect(source).toContain("DwReferenceSelectField");
});

test("the roster picker says why it does not edit, rather than leaving the asymmetry unexplained", () => {
  // The single picker has a pencil gated on `selectedId`, which a multi-select has no analogue of.
  // The edit path is one row-expansion away — `bulkField` resolves to `participant.artisanRef` alone
  // and every roster ROW renders its own `StageReferenceSelect` — and that is the better path, so
  // the docstring argues it rather than a per-option pencil being bolted on.
  const source = read(PICKER);
  expect(source).toContain("WHY IT DOES NOT ALSO EDIT");
  expect(source).toContain("BUILD-A-SELECTION control");
});
