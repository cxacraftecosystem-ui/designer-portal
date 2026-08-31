import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  TENTATIVE_FIELD_KEY,
  isTentativeRow,
  tentativeField,
  tentativeFirst
} from "@/lib/sketchTentative";
import type { DwEntity, DwRow } from "@/lib/designWorkshops";

/**
 * TENTATIVE-FIRST, PINNED — the ordering rule the owner asked for, and the two properties of it that
 * nothing on a screen can show.
 *
 * WHY THIS SPEC EXISTS. "Bring them to the top of the list" is one sentence with two silent failure
 * modes behind it. The first is that a designer's own arrangement INSIDE each group is thrown away —
 * a partition that sorted rather than partitioned would look identical on a list of three sketches
 * and would scramble a list of nine. The second is worse and is the reason `ordinal` is never
 * written: unticking the box has to put the row back exactly where it was, and a screenshot of a
 * correct list and a screenshot of a list that can never be undone are the same screenshot. Both are
 * questions about which array a function returns, which is what a unit spec can hold still.
 *
 * IT ALSO PINS THE PREDICATE'S STRICTNESS, because that one is a parity question: the web reads a
 * real `true` and nothing else, exactly as `BoolField` draws it, and a later "helpful" widening
 * would put a chip on a row whose own checkbox reads "Not answered".
 *
 * AND IT PINS THE TWO SURFACES THAT MUST NOT REORDER, by reading their source. That is a coarse
 * test and it is the only kind available here: whether `EntityForm` and `ReviewPanel` draw the
 * stored order is not observable from a pure function, and the failure it guards — a later agent
 * "finishing the job" by partitioning the list that gets SAVED — is exactly the mutation of
 * `ordinal` this feature was designed around.
 */

/** A sketch row, as the draft store holds one. */
function row(entryId: string, tentative?: unknown): DwRow {
  return tentative === undefined
    ? ({ _entryId: entryId, name: entryId } as DwRow)
    : ({ _entryId: entryId, name: entryId, [TENTATIVE_FIELD_KEY]: tentative } as DwRow);
}

const ids = (rows: readonly DwRow[]) => rows.map((r) => r._entryId);

test("tentative rows come first and each group keeps its own stored order", () => {
  const rows = [
    row("a"),
    row("b", true),
    row("c"),
    row("d", true),
    row("e", false),
    row("f", true)
  ];
  expect(ids(tentativeFirst(rows, isTentativeRow))).toEqual(["b", "d", "f", "a", "c", "e"]);
});

test("a list with nothing ticked is returned in exactly the order it arrived", () => {
  // The owner's second clause: "the ones for which it is not checked would be considered as normal
  // as they are treated right now". On the overwhelming majority of workshops this is the whole
  // behaviour, so it is the case worth failing loudly on.
  const rows = [row("a"), row("b", false), row("c")];
  expect(ids(tentativeFirst(rows, isTentativeRow))).toEqual(["a", "b", "c"]);
});

test("a list with everything ticked is returned in exactly the order it arrived", () => {
  const rows = [row("a", true), row("b", true), row("c", true)];
  expect(ids(tentativeFirst(rows, isTentativeRow))).toEqual(["a", "b", "c"]);
});

test("unticking restores the row to precisely the position it would have had", () => {
  /*
    THE PROPERTY THE WHOLE DESIGN TURNS ON. `ordinal` is never written, so the stored array is
    untouched by a tick; partitioning it again with the box cleared must therefore reproduce the
    stored order exactly. A version that wrote the ordinal would pass the first assertion here and
    fail this one, and on a real record that failure is permanent.
  */
  const stored = [row("a"), row("b"), row("c"), row("d")];
  const ticked = stored.map((r) => (r._entryId === "d" ? { ...r, [TENTATIVE_FIELD_KEY]: true } : r));
  expect(ids(tentativeFirst(ticked, isTentativeRow))).toEqual(["d", "a", "b", "c"]);

  const unticked = ticked.map((r) =>
    r._entryId === "d" ? { ...r, [TENTATIVE_FIELD_KEY]: false } : r
  );
  expect(ids(tentativeFirst(unticked, isTentativeRow))).toEqual(ids(stored));
});

test("the partition is generic, so a caller can keep each row's original stage position", () => {
  /*
    The upload chooser's shape. `rowLabel` falls back to "Untitled 3" and the handset's picker prints
    "Row 3 of 8", and both numbers are the position on the STAGE FORM — so the pairs are partitioned
    and the index inside each pair is not touched.
  */
  const rows = [row("a"), row("b", true), row("c")];
  const pairs = rows.map((r, index) => ({ row: r, index }));
  const ordered = tentativeFirst(pairs, (pair) => isTentativeRow(pair.row));
  expect(ordered.map((pair) => pair.index)).toEqual([1, 0, 2]);
});

test("the predicate accepts a real true and refuses everything a checkbox does not draw as ticked", () => {
  expect(isTentativeRow(row("a", true))).toBe(true);
  expect(isTentativeRow(row("a", false))).toBe(false);
  expect(isTentativeRow(row("a"))).toBe(false);
  expect(isTentativeRow(row("a", null))).toBe(false);
  // Looser tokens are refused on this client on purpose — see the function's own note.
  expect(isTentativeRow(row("a", "true"))).toBe(false);
  expect(isTentativeRow(row("a", 1))).toBe(false);
  expect(isTentativeRow(undefined)).toBe(false);
  expect(isTentativeRow(null)).toBe(false);
});

test("the field is found by its registry declaration, and absence is an ordinary state", () => {
  const entity = (fields: DwEntity["fields"]): DwEntity => ({
    key: "sketch",
    name: "DwSketch",
    cardinality: "COLLECTION",
    title: "Sketches",
    description: "",
    parent: "",
    labelField: "name",
    fields
  });
  const declared = {
    key: TENTATIVE_FIELD_KEY,
    label: "Tentative",
    type: "BOOL",
    tier: "BASIC",
    required: false
  } as DwEntity["fields"][number];

  expect(tentativeField(entity([declared]))?.label).toBe("Tentative");
  // A registry without the field — an older build — draws no chip rather than throwing.
  expect(tentativeField(entity([]))).toBeNull();
  expect(tentativeField(null)).toBeNull();
  // A deprecated declaration is a dead input everywhere else in this client and is dead here too.
  expect(tentativeField(entity([{ ...declared, deprecated: true }]))).toBeNull();
  // The key alone is not enough: a same-named field of another type is not this flag.
  expect(tentativeField(entity([{ ...declared, type: "TEXT" }]))).toBeNull();
});

test("the two surfaces that write an order back do not partition the list they draw", () => {
  /*
    A SOURCE READ, AND THE REASON IT IS ONE. `EntityForm`'s row array is what `buildStageEntries`
    turns into `ordinal`, and `ReviewPanel`'s `order` state is what `arrangeRows` writes into the
    draft — so a partition applied to either of those is a write, not a view, and unticking could
    never undo it. Neither may import the partition at all; both must carry the word instead.
  */
  const root = join(process.cwd(), "components");
  for (const file of ["designworkshop/EntityForm.tsx", "sketches/ReviewPanel.tsx"]) {
    const source = readFileSync(join(root, file), "utf8");
    expect(source, `${file} must not partition the array it persists`).not.toContain("tentativeFirst");
    // It must still SAY which rows are tentative, which is the half that surface does owe a reader.
    expect(source, `${file} must still name the tentative flag`).toContain("sketchTentative");
  }
});
