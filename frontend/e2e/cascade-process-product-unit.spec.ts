import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { referenceHydrationFor, type DwEntity, type DwField } from "@/lib/designWorkshops";

/**
 * The stage-5 process pickers, now narrowed by the product — the CLIENT half.
 *
 * WHAT THE SERVER CANNOT SAY. `backend/tests/test_process_product_cascade.py` asserts that the
 * narrowing is a WHERE clause on `Process.productId` and that the payload carries no signal telling
 * anybody to take a single option. Neither statement covers the two behaviours that actually decide
 * whether a designer is misled:
 *
 *  1. WITH THE PARENT BLANK, NOTHING IS FETCHED AT ALL. The server treats an absent `filterBy` as
 *     "no filter" and serves the whole table — the correct answer to the question it was asked and
 *     the wrong list to put on this control, because the descriptor says this field is the processes
 *     OF the product on this row. An unnarrowed list on a control the descriptor calls narrowed
 *     offers another product's sequence to a designer who has every reason to believe it was
 *     filtered.
 *  2. NOTHING AUTO-SELECTS. One product legitimately has MANY documented processes — the owner
 *     confirmed it — so a "there is only one, take it" shortcut would be wrong here even if it were
 *     wanted, and the one place such a shortcut exists is gated on a scanned PROTOTYPE tag.
 *
 * READ FROM THE SOURCE RATHER THAN RENDERED, for the reason `stage-record-embed-unit.spec.ts` gives
 * about its own assertions: this repository has no React renderer in its devDependencies, so a rule
 * that lives in a component body can be checked as a substring or not at all. Weaker than a render;
 * still the assertion that matters, because the failure being guarded is the DELETION of a gate, and
 * a deleted gate is visible in the source.
 *
 * THE REGISTRY IS THE REAL ONE — the bundled schema dump the Android client ships, which is a pure
 * `registry_to_dict()` and needs no database. A fixture with invented keys would pass every
 * assertion here by accident, which is how this class of test has failed in this repository before.
 */

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

/* ────────────────────────────────────────────────────────────────────────────
 * The declaration, as the CLIENTS receive it
 * ──────────────────────────────────────────────────────────────────────────── */

test("both process pickers reach the clients declaring the product as their parent", () => {
  /*
    ASSERTED OFF THE BUNDLED ASSET AND NOT OFF THE PYTHON, on purpose. `refFilterBy` is NOT in
    `registry_version()`'s digest — the digest covers key, type, tier, required, enum, deprecated,
    derivation, hydration, masking and text format, and nothing else — so a cascade added to the
    registry and never dumped leaves a handset that has never reached the network rendering a picker
    with no parent, under a version string that matches. This is the assertion that fails in that
    case; the version check in `test_controlled_vocabularies.py` is not.
  */
  for (const entityKey of PROCESS_ENTITIES) {
    const child = fieldOf(entityKey, "processRef");
    expect(child.refFilterBy, `${entityKey}.processRef`).toBe("productRef");
    expect(child.refModel).toBe("Process");

    const parent = fieldOf(entityKey, "productRef");
    expect(parent.type, `${entityKey}.productRef`).toBe("REF");
    expect(parent.refModel).toBe("ProductDocumentation");
    // A wider parent than the child it narrows would be incoherent: the product picker must not
    // offer a product whose processes the process picker would then refuse to show.
    expect(parent.refScope).toBe("WORKSHOP");
    expect(child.refScope).toBe("WORKSHOP");
    // Never BASIC. `EntityForm.tsx` picks the collection's bulk multi-select as THE field that is
    // `REF && refModel && !refFilterBy && tier === "BASIC"`, so a BASIC parent here would turn a
    // picker into a "tick thirty records and make thirty rows" control that means nothing on a
    // process step and less on a singleton.
    expect(parent.tier).not.toBe("BASIC");
    // HIDDEN: `documentedFor` already prints the product's name, so a printing `productRef` would be
    // a second, possibly-disagreeing statement of one fact — and `processStep`'s five declared
    // column widths already total exactly 100.
    expect(parent.reportRole).toBe("HIDDEN");
  }
});

test("the parent is declared before the picker it narrows, and the picker before the boxes it fills", () => {
  // `awaitingCascade` fetches NOTHING while the parent is blank, so a parent declared BELOW the
  // picker it narrows is a picker that is dead until the designer scrolls back up — and field order
  // in the registry is the order every client renders.
  for (const entityKey of PROCESS_ENTITIES) {
    const keys = entityOf(entityKey).fields.map((field) => field.key);
    expect(keys.indexOf("productRef"), entityKey).toBeGreaterThan(-1);
    expect(keys.indexOf("productRef")).toBeLessThan(keys.indexOf("processRef"));
    expect(keys.indexOf("processRef")).toBeLessThan(keys.indexOf("documentedFor"));
  }
});

test("the cascade's parent is a REF and never the text box holding the product's name", () => {
  /*
    THE TRAP THIS CLOSES. `documentedFor` is a `fromref` TEXT box holding the product's NAME, sitting
    a few lines from both pickers, and the server's `validate_registry` only requires that the field
    named by `refFilterBy` EXIST on the entity — not that it be a REF. So `refFilterBy:
    "documentedFor"` would validate, and then the client would read that box and send a product NAME
    as `filterBy`, where it is treated as an id and matches nothing. An empty picker reads as an
    empty repository, which is how a designer concludes the record was never made and types the whole
    thing in by hand.
  */
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

/* ────────────────────────────────────────────────────────────────────────────
 * The two client behaviours the payload cannot express
 * ──────────────────────────────────────────────────────────────────────────── */

test("a cascading picker with a blank parent fetches nothing rather than the whole table", () => {
  const source = read("components/designworkshop/StageReferenceField.tsx");
  // The parent's value is read off the SAME ROW, which is what `refFilterBy` means.
  expect(source).toContain('field.refFilterBy ? inputValue(row[field.refFilterBy]) : ""');
  // And with it blank the picker is not merely empty — it is INACTIVE, so no request is made and the
  // panel says which box to answer first.
  expect(source).toContain("const awaitingCascade = Boolean(field.refFilterBy) && !filterValue;");
  expect(source.replace(/\s+/g, " ")).toContain("active: open && !awaitingCascade");
});

test("nothing takes a single option except a scanned prototype tag", () => {
  /*
    ONE PRODUCT HAS MANY DOCUMENTED PROCESSES, so "there is only one, take it" is not a shortcut
    here — it is a wrong answer waiting for a product that happens to have one process today. The one
    single-option resolve in the picker is gated on `ref.recordType === "prototype"`, because a
    prototype TAG legitimately carries a `_clientKey` the option does not, which is the only case
    where the id itself cannot be the proof. Both clients are checked, because the handset carries a
    port of the same rule and the two have drifted before.
  */
  const web = read("components/designworkshop/StageReferenceField.tsx").replace(/\s+/g, " ");
  expect(web).toContain('if (ref.recordType === "prototype" && !payload.truncated && payload.options.length === 1)');

  const android = read("../android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwReferenceField.kt").replace(
    /\s+/g,
    " "
  );
  expect(android).toContain("if (ref.recordType == DwWorkshopRecordType.PROTOTYPE && !payload.truncated && payload.options.size == 1)");

  // AND NO UNGATED ONE ANYWHERE ELSE IN THE PICKER. Asserted as a count rather than as an absence,
  // so that a second gated use is a deliberate edit to this number and an ungated one fails.
  const singles = (read("components/designworkshop/StageReferenceField.tsx").match(/options\.length === 1/g) ?? []).length;
  expect(singles, "a second single-option shortcut appeared in the picker").toBe(1);
});

/* ────────────────────────────────────────────────────────────────────────────
 * The printed name follows the pick
 * ──────────────────────────────────────────────────────────────────────────── */

test("documentedFor is answered by the product the designer picked, and by the process as a fallback", () => {
  /*
    TWO WRITERS OF ONE BOX. What is asserted here is the TABLE — that both pairs exist, on both
    entities, with the right source keys — and that is all this file can assert: which of the two
    actually lands is a property of the hydration LOOP, and it depends on the save.

    Hydration walks an entity's fields in DECLARATION order and `productRef` is declared immediately
    before `processRef`, so the parent's answer lands first and the child's identical pair leaves the
    filled box alone — WHILE THE CHILD'S REF IS UNCHANGED. A re-pointed ref clears and rewrites its
    own targets, so on the cascade's ordinary path (product changed, process cleared and re-picked in
    the same save) the CHILD writes it last. `backend/tests/test_reference_carry.py` pins all five
    cases against the real loop, including the stale-pair case where the box then names the process's
    parent rather than the product the designer chose; an earlier version of this docstring asserted
    the parent always wins, which is true of only some saves.

    The child's pair is kept rather than removed because dropping `productName` from the server's
    `Process` data lambda would make `canonical_divergence` answer `canonical: null` for every
    `documentedFor` stamped before today and report the whole archive as diverged.

    THE SOURCE KEYS ARE DIFFERENT WORDS FOR THE SAME FACT, which is the thing a hand-copy gets
    wrong: the product model's data lambda produces `name` for its own name, while `productName` is
    the PROCESS model's key for its parent's name.
  */
  for (const entityKey of PROCESS_ENTITIES) {
    const entity = entityOf(entityKey);
    const fromParent = referenceHydrationFor(entity, fieldOf(entityKey, "productRef"));
    expect(fromParent, `${entityKey}.productRef`).toEqual({ name: "documentedFor" });

    const fromChild = referenceHydrationFor(entity, fieldOf(entityKey, "processRef"));
    expect(fromChild.productName, `${entityKey}.processRef`).toBe("documentedFor");
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * The state between the cascade's two halves
 * ──────────────────────────────────────────────────────────────────────────── */

test("both clients clear the child when the parent moves, and neither pretends to settle the values", () => {
  /*
    THE ROW THAT USED TO BE SAVED. Changing the product clears the process, one autosave is enough to
    store the row before it is re-picked, and a blank ref hydrated nothing AND popped nothing — so the
    six boxes copied from the old process stood while `productRef` rewrote `documentedFor`. Product B's
    name over product A's process, `processRef` null, and nothing able to flag it.

    Two clients and one server, so three things are asserted here, and the DIVISION is the point:

     * THE BROWSER clears the id in its filter effect.
     * THE HANDSET does the same, and had NOTHING in its place — `DwReferenceField`'s own KDoc used to
       state that outright ("unlike the browser, `DwReferenceSelectField` has no effect that clears a
       child when its parent changes"). The rule is lifted into `dwCascadeClearsChild` so this module's
       Compose-less unit tests can reach it; `DwCascadeClearTest` covers the branches.
     * NEITHER CLIENT clears the VALUES, and that is deliberate rather than unfinished. Deciding
       whether a re-pointed parent is about to rewrite one of them needs `previous` beside the incoming
       row, and no client has it: `validate_entry` drops blank keys, so "the parent was cleared" and
       "this build never sent the parent" are the same absence on the wire. So the pop lives once, on
       the server, in `_clear_cascade_orphans`.
  */
  const web = read("components/designworkshop/StageReferenceField.tsx");
  // The clear itself, and the sentence that goes with it.
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
  // AND THE CLAIM THAT IT DOES NOT EXIST IS GONE WITH IT. The KDoc sentence below was true, was
  // load-bearing for a reader deciding whether the scan guard was the only protection on that path,
  // and would be a lie the moment it survived the fix.
  expect(
    androidField,
    "a KDoc still says the handset has no cascade clear, which is now false",
  ).not.toContain("has no effect that clears a child when its parent changes");

  const server = read("../backend/app/services/design_workshops.py");
  // Named rather than matched loosely: the pass has to run BEFORE the early return, or a payload that
  // clears both refs resolves nothing and the whole stale set stands.
  expect(server).toContain("def _clear_cascade_orphans(");
  expect(server).toContain("_clear_cascade_orphans(entries)");
});

test("only a cascaded child can have its copied values popped, so an unlink keeps what it filled in", () => {
  /*
    THE RULE THE POP MUST NOT WEAKEN, checked from the declaration side because that is what bounds it.

    `StageReferenceField` states it: "Only the reference is cleared. The name, village and phone it
    filled in STAY: they are what the designer confirmed in the room, and a report that loses a
    participant's name because somebody unlinked a duplicate artisan record is the failure the copy
    exists to prevent."

    `_clear_cascade_orphans` is reachable only from a field declaring `refFilterBy`, so the set of
    fields it can touch is exactly the set of cascaded children — four of them today. Pinned as a
    LIST rather than a count: a fifth cascade is a legitimate change and its arrival must be read
    against this rule rather than slipped past it, and `participant.artisanRef` must never be on it.

    THE FOUR ARE NOT ALL THE SAME SHAPE, AND THE RULE IS RIGHT ON ALL OF THEM. Two have a parent that
    is itself a second writer of one of the child's boxes (`traditionalProcess`/`processStep` on
    `documentedFor`, `existingProduct` on `artisanName`), which is the self-contradicting row the pop
    exists for. `prototype.productRef`'s parent writes nothing, so its stale `productName` was
    consistent rather than contradictory — and it is still a product documented for the artisan this
    row NO LONGER names, printed as what this prototype was developed from, so it goes too.
  */
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
