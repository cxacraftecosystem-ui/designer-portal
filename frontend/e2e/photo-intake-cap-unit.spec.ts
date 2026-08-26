import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { DW_DEFAULT_MAX_ITEMS, type DwRegistry } from "@/lib/designWorkshops";
import { appendMediaRef, effectiveMaxItems, mediaRefRoom, photoTargets } from "@/lib/photoIntake";

/**
 * THE INTAKE PATH THAT WROTE PAST EVERY CEILING THERE IS.
 *
 * `appendMediaRef` is the door the browser's two BULK write paths go through — the bulk photo import
 * at `/design-workshops/[id]/photos` and the UPLOAD tab's turntable picker — and until 2026-08-26 it
 * took a value, a reference and a boolean, and appended. No cap of any kind. So a confirmed import of
 * two hundred camera photographs went straight past the two motif galleries' DECLARED twenty and past
 * the server's own default of two hundred, on a path where the capture card's own ceiling
 * (`MediaField` in FieldInput.tsx) never runs.
 *
 * WHAT THAT COSTS IS NOT THE OVERFLOW. `coerce_value` REFUSES an over-long array rather than trimming
 * it (backend/app/services/stage_schema.py, under a comment headed "A REFUSAL, NOT A TRUNCATION") and
 * `save_stage` then restores the refused key from the previous entry — so the designer does not lose
 * the two hundred and first photograph, they lose every photograph that field was about to store,
 * reported as one error against the field with every byte already copied and queued for upload.
 *
 * THE TWO HALVES OF THE RULE ARE BOTH TESTED HERE, because satisfying one by breaking the other is
 * how this was got wrong twice already (docs/DESIGN_WORKSHOP.md:229-232, "a client must neither read
 * the absence as no limit nor print a number it did not read"):
 *
 *   * an ABSENT `maxItems` is ENFORCED at {@link DW_DEFAULT_MAX_ITEMS} and never as "no limit"; and
 *   * a photograph the ceiling turns away is SAID OUT LOUD, by name, and is not counted in the
 *     receipt — because a silent drop is the failure the refusal exists to prevent, and a green
 *     "12 attached" over ten that landed is that same failure wearing a receipt.
 *
 * WHY HALF OF IT IS A SOURCE READ. The functions are pure and are tested by calling them. The two
 * call sites are a Next.js page and a React component, and this repository has no React renderer in
 * its devDependencies — Playwright is the whole of it — so their walks are read out of the source,
 * exactly as `existing-media-count-unit.spec.ts` and `capped-lists-unit.spec.ts` read theirs. What
 * that cannot prove is that a browser paints the sentence; what it does prove is that the number in
 * the sentence is the number of writes that actually happened.
 *
 * Every assertion below fails against the tree as it was.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const PHOTOS_PAGE = () => read("app", "(protected)", "design-workshops", "[id]", "photos", "page.tsx");
const UPLOAD_HOST = () => read("components", "sketches", "UploadTabHost.tsx");

/** The text between two markers, so an assertion cannot drift into a neighbouring block. */
function between(source: string, from: string, to: string): string {
  const start = source.indexOf(from);
  expect(start, `${from} not found — has the file been restructured?`).toBeGreaterThan(-1);
  const end = source.indexOf(to, start);
  expect(end, `${to} not found after ${from}`).toBeGreaterThan(-1);
  return source.slice(start, end);
}

/** `n` distinct references, as a stored IMAGE_LIST holds them. */
const refs = (n: number) => Array.from({ length: n }, (_, index) => `dwlocal:ref-${index}`);

/* ────────────────────────────────────────────────────────────────────────────
 * The ceiling itself
 * ──────────────────────────────────────────────────────────────────────────── */

test("an undeclared ceiling is the server's default, not the absence of one", () => {
  // Pinned against the literal as well as against itself: comparing the constant with itself proves
  // nothing, and the number that matters is DEFAULT_MAX_ITEMS in stage_schema.py. If the server
  // changes it, this line is the one that says so out loud rather than a stage save that fails in a
  // village. `StageSchema.kt` holds the handset's copy and its own test pins it the same way.
  expect(DW_DEFAULT_MAX_ITEMS).toBe(200);
  expect(effectiveMaxItems(undefined)).toBe(DW_DEFAULT_MAX_ITEMS);
  // 0 is how Android's `FieldDto.maxItems` says "not declared" — a schema that ever reached this
  // client through that shape must read as the default and NOT as a gallery that refuses its first
  // photograph, which is the same silent loss in the opposite direction.
  expect(effectiveMaxItems(0)).toBe(DW_DEFAULT_MAX_ITEMS);
  expect(effectiveMaxItems(-3)).toBe(DW_DEFAULT_MAX_ITEMS);
  expect(effectiveMaxItems(20)).toBe(20);
});

test("a declared ceiling stops the file after it, and says so by refusing to grow", () => {
  const full = refs(20);
  expect(mediaRefRoom(full, true, 20)).toBe(0);
  expect(appendMediaRef(full, "dwlocal:one-more", true, 20)).toEqual(full);

  const nearly = refs(19);
  expect(mediaRefRoom(nearly, true, 20)).toBe(1);
  expect(appendMediaRef(nearly, "dwlocal:one-more", true, 20)).toEqual([...nearly, "dwlocal:one-more"]);
});

test("an undeclared gallery is capped at two hundred rather than left open", () => {
  const full = refs(DW_DEFAULT_MAX_ITEMS);
  expect(mediaRefRoom(full, true, undefined)).toBe(0);
  expect(appendMediaRef(full, "dwlocal:two-hundred-and-one", true, undefined)).toEqual(full);
  // The whole defect in one line: this is what the old two-argument call did to the same list.
  expect(appendMediaRef(full, "dwlocal:two-hundred-and-one", true, undefined)).toHaveLength(200);
});

test("a reference the field already holds is a no-op, not a refusal, even at the ceiling", () => {
  const full = refs(20);
  // Confirming the same photograph twice must stay the no-op it always was: reporting it as turned
  // away would put a red sentence about a file that is, in fact, exactly where the designer sent it.
  expect(mediaRefRoom(full, true, 20)).toBe(0);
  expect(appendMediaRef(full, full[0], true, 20)).toEqual(full);
});

test("a single-valued field replaces and therefore always has room", () => {
  expect(mediaRefRoom("dwlocal:already-there", false, undefined)).toBe(1);
  expect(appendMediaRef("dwlocal:already-there", "dwlocal:new", false, undefined)).toBe("dwlocal:new");
});

test("junk in a stored list is not counted as a photograph", () => {
  // The stored value crosses the wire as JSON and a field that once held something else can arrive
  // holding it still. Counting a number as an attachment would refuse a photograph on behalf of a
  // value that is not one.
  const value = ["dwlocal:a", 7, null, "dwlocal:b"] as unknown as string[];
  expect(mediaRefRoom(value, true, 3)).toBe(1);
  expect(appendMediaRef(value, "dwlocal:c", true, 3)).toEqual(["dwlocal:a", "dwlocal:b", "dwlocal:c"]);
});

/* ────────────────────────────────────────────────────────────────────────────
 * The ceiling reaching the destination it has to be enforced at
 * ──────────────────────────────────────────────────────────────────────────── */

const REGISTRY: DwRegistry = {
  version: "test",
  stages: [
    {
      key: "DESIGN_BRIEF",
      number: 2,
      title: "Design brief",
      entities: [
        {
          key: "brief",
          title: "Brief",
          cardinality: "SINGLETON",
          fields: [
            { key: "notes", label: "Notes", type: "TEXT", tier: "BASIC", required: false },
            {
              key: "motifPhotos",
              label: "Motif photographs",
              type: "IMAGE_LIST",
              tier: "BASIC",
              required: false,
              maxItems: 20
            },
            { key: "logPhotos", label: "Photographs", type: "IMAGE_LIST", tier: "BASIC", required: false },
            { key: "coverPhoto", label: "Cover photograph", type: "IMAGE", tier: "STANDARD", required: false }
          ]
        }
      ]
    }
  ]
} as unknown as DwRegistry;

test("a target carries the ceiling its field declared, and the silence where it declared none", () => {
  const targets = photoTargets(REGISTRY, "DESIGN_BRIEF", "brief");
  expect(targets.map((target) => target.fieldKey)).toEqual(["motifPhotos", "logPhotos", "coverPhoto"]);
  // Declared: the number travels, so the destination is enforced at twenty and a caller MAY print it.
  expect(targets[0].maxItems).toBe(20);
  // Undeclared: undefined travels, NOT 200. Resolving it here would hand every refusal sentence a
  // figure this client read from nowhere, which is the half of the rule that forbids printing it.
  expect(targets[1].maxItems).toBeUndefined();
  expect(targets[2].maxItems).toBeUndefined();
  // And what is ENFORCED for that silence is still the server's number rather than nothing at all.
  expect(mediaRefRoom(refs(200), targets[1].multiple, targets[1].maxItems)).toBe(0);
});

/* ────────────────────────────────────────────────────────────────────────────
 * The two call sites: what they ask, and what they then claim
 * ──────────────────────────────────────────────────────────────────────────── */

test("the bulk import asks for room before it copies a byte, and counts only what landed", () => {
  const source = PHOTOS_PAGE();
  const confirm = between(source, "async function confirm()", "/* ── Render");

  // Room is asked for BEFORE `stageLocalMedia` runs — `copyToDevice` is a closure called from inside
  // the branches for exactly that reason. Staging first would leave a photograph nothing references
  // in IndexedDB and push it to the repository on the next connection.
  const askedFirst = confirm.indexOf("mediaRefRoom(");
  const copied = confirm.indexOf("await copyToDevice()");
  expect(askedFirst).toBeGreaterThan(-1);
  expect(copied).toBeGreaterThan(askedFirst);

  // The ceiling actually reaches the door, both branches of the walk.
  expect(confirm.match(/destination\.maxItems/g)?.length).toBeGreaterThanOrEqual(4);

  // The refusal is SAID, by filename and by destination — and it carries the destination's DECLARED
  // ceiling, because the sentence may name that number and may name no other.
  expect(confirm).toContain(
    "full.push({ file: line.file.name, label: destination.label, declaredCap: destination.maxItems })"
  );
  expect(confirm).toContain("headed for ${because}: ");
  // The undeclared arm names no number at all, which is the other half of the same rule.
  expect(confirm).toMatch(/:\s*"is full"/);

  // BOTH HALVES OF docs/DESIGN_WORKSHOP.md:229-232, AND NEITHER TRADED FOR THE OTHER. This
  // assertion used to read `not.toContain("at most")` — no ceiling named here at all — and that
  // was the wrong half of the rule on its own: Android's `dwIntakeFullNotice` names a declared cap
  // and this browser's own capture card prints "holds at most 20 files", so a silent web intake
  // made the two surfaces of one client disagree about one field. What is forbidden is printing a
  // number the client did not READ, so the ceiling in the sentence must always be `declared` —
  // never the enforced default, which arrives from nowhere the designer can see.
  expect(confirm).toContain("declared && declared > 0");
  expect(confirm).toContain("holds at most ${declared}");
  expect(confirm).not.toContain("DW_DEFAULT_MAX_ITEMS");
  expect(confirm).not.toContain("at most 200");

  // The receipt counts stages that RECEIVED something. `pending` holds every stage merely considered
  // — including one every photograph was refused from — so counting it would print "across 4 stages"
  // over three that changed.
  expect(confirm).toContain("${touched.size} stage");
  expect(confirm).not.toContain("${pending.size} stage");
});

test("the upload tab counts frames the field took, not frames it was handed", () => {
  const source = UPLOAD_HOST();
  const attach = between(source, "const attach = useCallback(", "const sketchEntity =");

  // `what` became a phrase-maker so that the number in it is decided AFTER the writes, by the only
  // scope that knows how many of them happened.
  expect(attach).toContain("what: (landedCount: number) => string");
  expect(attach).toContain("${what(took)} attached to");
  expect(attach).not.toContain("${what} attached to");

  // Including the singular/plural of the sync note one clause later, which counted the handful handed
  // over rather than the handful that landed.
  expect(attach).toContain('took === 1 ? "this file is" : "these files are"');
  expect(attach).not.toContain('files.length === 1 ? "this file is"');

  // Room per file, inside the loop, because the previous iteration is what filled the field.
  expect(attach).toContain("if (!mediaRefRoom(row[target.fieldKey], target.multiple, target.maxItems))");
  expect(attach).toContain("turnedAway.push(file.name)");

  // Nothing fitted: no write, no green line, and a plain no to the panel.
  expect(attach).toContain("if (took === 0)");

  // A partial turn answers false, so `PrototypeModelField` cannot print "12 photographs were added"
  // over the ten that were. See the note on the return for what that costs.
  expect(attach).toContain("return turnedAway.length === 0;");
});

test("every destination the upload tab writes to carries its field's declared ceiling", () => {
  const source = UPLOAD_HOST();
  // Four handlers, four targets, four ceilings — the turntable being the one that can be handed more
  // than one file at a time and therefore the one that can hit a ceiling in a single action.
  expect(source).toContain("maxItems: lineArt.maxItems");
  expect(source).toContain("maxItems: sketchImage.maxItems");
  expect(source).toContain("maxItems: model.maxItems");
  expect(source).toContain("maxItems: turntable.maxItems");
});
