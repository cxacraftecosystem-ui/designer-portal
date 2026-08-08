import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { deriveValue, isDerived } from "@/lib/derivedFields";
import type { DwEntryData, DwField, DwFieldType, DwValue } from "@/lib/designWorkshops";

/**
 * `lib/derivedFields.ts` against the SERVER'S OWN ANSWERS — no browser, no server, no IndexedDB.
 *
 * WHAT THIS FILE IS FOR. The module is a port of `stage_schema.derive_value`, and its header makes a
 * promise about itself: "IT IS A PORT, NOT A SECOND OPINION… inventing one here would produce a
 * figure the save then overwrote, which is worse than no figure at all because the designer has
 * already read it." Nothing checked that promise. The three surfaces that compute a duration, a line
 * amount and a cost-sheet total — the server on save, this file at the keyboard, and the Android
 * renderer on the handset — each held their own arithmetic, and two of them were wrong.
 *
 * THE EXPECTATIONS ARE NOT WRITTEN HERE. `e2e/fixtures/derived-field-cases.json` is produced by
 * `frontend/tools/regenerate_derived_field_golden.py`, which runs every row below through the actual
 * Python. A golden written by reading the TypeScript would only prove the port agrees with itself.
 * When a case fails, the Python is right — regenerating the golden until it passes is the one move
 * that destroys the whole point of the file.
 *
 * THREE THINGS IT CAUGHT, all of them shipped, all of them found by running the two sides:
 *
 *  1. **Money rounded the wrong way on a tie.** `toFixed` is specified to pick the LARGER candidate;
 *     Python rounds to the EVEN digit. 1.5 m of braid at ₹4.75 is exactly ₹7.125 — the box said
 *     ₹7.13 and the save stored ₹7.12. A rate ending in 5 against an odd quantity lands on a
 *     half-paisa routinely, so this was not an exotic input; it is the ordinary shape of a cost line.
 *  2. **`Number()` is not `float()`.** `"0x1A"` became 26 here and nothing there; a one-element list
 *     `[5]` stringifies to `"5"` in JavaScript and `"[5]"` in Python, so it became a quantity here
 *     and nothing there; `"1_000"` was refused here and accepted there. The identical four traps
 *     `lib/marketAnalysis.ts` documents — which is why the fix was to import its `asNumber` rather
 *     than write a third opinion.
 *  3. **`Date.UTC` rolls over and does not complain.** `Date.UTC(2026, 1, 30)` is the 2nd of March,
 *     so "2026-02-30" produced a confident duration against a server that answers "not computable",
 *     and "2026-13-01" produced one dated next January.
 *
 * WHY A NODE SPEC AND NOT A BROWSER ONE. Every case is one pure call over a plain object. Driving it
 * through a stage form would test `FieldInput`'s placeholder logic and would still not tell you
 * whether ₹7.13 or ₹7.12 is the right answer, because only the server knows that.
 */

// `__dirname`, not `import.meta.url`: Playwright transpiles a spec to CommonJS, where the latter is
// a syntax the loader does not provide. `workshop-codes.spec.ts` reads its own golden the same way.
const FIXTURE = join(__dirname, "fixtures", "derived-field-cases.json");

type GoldenCase = {
  name: string;
  type: string;
  derivedKind: string;
  derivedFrom: string[];
  row: Record<string, DwValue>;
  expected: DwValue | null;
};

const golden = JSON.parse(readFileSync(FIXTURE, "utf8")) as { cases: GoldenCase[] };

/**
 * The smallest field descriptor `deriveValue` reads: everything else on `DwField` is form chrome.
 *
 * `derivedKind` is cast rather than typed, on purpose. The union on `DwField` names the three kinds
 * the registry declares today, and one case in the table is a kind the registry does NOT declare —
 * the server answers null to an unknown kind and this port must too, which is a claim about a value
 * the type system says cannot arrive. It can: the registry is served over the wire.
 */
function fieldOf(type: string, derivedKind: string, derivedFrom: string[]): DwField {
  return {
    key: "derived",
    label: "Derived",
    type: type as DwFieldType,
    tier: "STANDARD",
    required: false,
    derivedKind: derivedKind as DwField["derivedKind"],
    derivedFrom
  };
}

test("every case answers exactly what the server answered", () => {
  // A count assertion, because a fixture that silently truncated would otherwise pass this file
  // with two cases in it. 45 is what the generator writes today.
  expect(golden.cases.length).toBe(45);

  const disagreements: string[] = [];
  for (const kase of golden.cases) {
    const actual = deriveValue(fieldOf(kase.type, kase.derivedKind, kase.derivedFrom), kase.row as DwEntryData);
    // Type-aware, not loose: `"7.12"` and `7.12` are different answers here. MONEY is stored as a
    // fixed-2 string precisely so it survives the JSON round trip without picking up a binary-float
    // artefact, and a number where the server wrote a string would break that on the next save.
    const same = Object.is(actual, kase.expected) || (actual === null && kase.expected === null);
    if (!same) {
      disagreements.push(`${kase.name}: server ${JSON.stringify(kase.expected)}, web ${JSON.stringify(actual)}`);
    }
  }
  expect(disagreements).toEqual([]);
});

test("a half-paisa tie goes to the even digit, the way the save will write it", () => {
  // Named on its own as well as sitting in the table above, because this is the case the module was
  // wrong about and the one a future edit is most likely to reintroduce by reaching for `toFixed`.
  const amount = fieldOf("MONEY", "PRODUCT", ["quantity", "rate"]);
  expect(deriveValue(amount, { quantity: "1.5", rate: "4.75" })).toBe("7.12");
  expect(deriveValue(amount, { quantity: "0.5", rate: "0.25" })).toBe("0.12");
  // …and a tie in the other direction still rounds UP, which is what makes this rounding to even
  // rather than rounding down. 0.375 → 0.38: the digit before the tie is odd, so it moves.
  expect(deriveValue(amount, { quantity: "1.5", rate: "0.25" })).toBe("0.38");
});

test("a factor the server would read as infinite is refused rather than printed", () => {
  /*
   * THE ONE DELIBERATE DIVERGENCE, declared here rather than left for somebody to discover.
   *
   * `float("Infinity")` succeeds, so `derive_value` returns `inf` and the save would store the
   * string "inf" in a MONEY column. `asNumber` refuses non-finite values one step earlier, so the
   * box stays blank. Blank is the better of the two wrong answers: "₹inf" in a cost sheet reaches a
   * ministry looking like a figure, and a blank box looks like what it is.
   *
   * It is also unreachable from a form — the number keyboards on both clients cannot type it — and
   * it cannot be carried in the golden at all, because `Infinity` is not JSON.
   */
  const amount = fieldOf("MONEY", "PRODUCT", ["quantity", "rate"]);
  expect(deriveValue(amount, { quantity: "Infinity", rate: "2" })).toBeNull();
  expect(deriveValue(amount, { quantity: "nan", rate: "2" })).toBeNull();
});

test("isDerived answers for the field, not for the row", () => {
  // `FieldInput` asks this before it asks for a value: a field that computes itself shows the
  // computed figure as a placeholder while the box is empty, and an ordinary one must not.
  expect(isDerived(fieldOf("MONEY", "PRODUCT", ["quantity", "rate"]))).toBe(true);
  expect(isDerived(fieldOf("MONEY", "", ["quantity"]))).toBe(false);
  // A kind with nothing to compute FROM is not derived — the same guard `derive_value` opens with,
  // and the reason a half-declared registry entry shows a plain empty box instead of a placeholder
  // that never fills in.
  expect(isDerived(fieldOf("MONEY", "SUM", []))).toBe(false);
});
