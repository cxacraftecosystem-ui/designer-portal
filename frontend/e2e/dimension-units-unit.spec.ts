import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  CM_PER_INCH,
  cmTextFromInches,
  inchesTextFromCm,
  parseDimension,
  propagate
} from "@/components/forms/dimensionUnits";

/**
 * CENTIMETRES ↔ INCHES: THE TABLE BOTH CLIENTS MUST REPRODUCE, ROW FOR ROW.
 *
 * `ToolDocumentation.height` / `.width` are the centimetre boxes and `heightInches` /
 * `breadthInches` their inch partners, and the tool form converts between each pair as a designer
 * types. The SAME function exists in Kotlin (`android/.../ui/DimensionUnits.kt`) and both clients
 * write into the same `Decimal(10, 2)` columns, so a disagreement of one hundredth means a tool
 * edited on a laptop and then on a phone changes its own width on every save, silently, for ever.
 *
 * THE TABLE BELOW IS THE CONTRACT AND IT IS COPIED VERBATIM INTO THE KOTLIN TEST. That duplication
 * is the entire mechanism: two implementations driven by one table is the only thing that can catch
 * a divergence, and neither test is allowed to "adjust" a row to match its own implementation. Every
 * value was computed in V8 against the arithmetic `dimensionUnits.ts` specifies —
 * `floor(v * 2.54 * 100 + 0.5)`, left to right, rendered from the scaled integer — and the rows that
 * land exactly on a halfway value are marked, because those are the only rows where a rounding rule
 * that drifted (banker's rounding, `kotlin.math.round`, a re-association of the multiply) shows up.
 *
 * WHY A SOURCE READ AT THE END. There is no React renderer in this repository's devDependencies, so
 * the FORM's use of these functions — that it converts on input and never on load, and that no
 * effect watches both boxes — can only be asserted by reading the file, exactly as
 * `record-number-bounds-unit.spec.ts` and `derived-fields-unit.spec.ts` read theirs.
 */

/** [input, inches → cm, cm → inches] */
const TABLE: ReadonlyArray<readonly [string, string, string]> = [
  ["1", "2.54", "0.39"],
  ["1.0", "2.54", "0.39"], // the trailing zero is dropped: same answer as "1"
  [".5", "1.27", "0.2"], // the leading-dot form is accepted
  ["0.5", "1.27", "0.2"],
  ["2", "5.08", "0.79"],
  ["3", "7.62", "1.18"],
  ["10", "25.4", "3.94"], // one decimal, not "25.40"
  ["100", "254", "39.37"], // integral, not "254.00"
  ["2.54", "6.45", "1"],
  ["12.34", "31.34", "4.86"],
  ["0.1", "0.25", "0.04"],
  ["1.25", "3.18", "0.49"], // (1.25 * 2.54) * 100 === 317.5 EXACTLY → floor(318.0) = 318
  ["0.125", "0.32", "0.05"], // === 31.75 exactly → 32
  ["1.875", "4.76", "0.74"], // === 476.25 exactly → 476
  ["6.25", "15.88", "2.46"], // === 1587.5 exactly → 1588
  ["0.05", "0.13", "0.02"], // === 12.699999999999999289 → 13
  ["1.005", "2.55", "0.4"]
];

/** Everything the grammar refuses. A partner may not be written from any of these. */
const REFUSED = ["", "-", "-1", "1.2.3", "abc", "1e3", "0x1A", "1.5f", "Infinity", "NaN", "1,5", "1 000"];

test("the inch is exactly 2.54 cm, and the constant is named rather than inlined", () => {
  // Not a measured quantity with an error bar: the international yard-and-pound agreement of 1959
  // DEFINES the inch as 25.4 mm. "2.5 for readability" is the failure this constant exists to stop.
  expect(CM_PER_INCH).toBe(2.54);
});

for (const [input, cm, inches] of TABLE) {
  test(`"${input}" converts to ${cm} cm and ${inches} in`, () => {
    expect(cmTextFromInches(input), `${input} in → cm`).toBe(cm);
    expect(inchesTextFromCm(input), `${input} cm → in`).toBe(inches);
  });
}

test("a value that cannot be a number converts to nothing at all", () => {
  for (const text of REFUSED) {
    expect(cmTextFromInches(text), `${JSON.stringify(text)} must not convert`).toBeNull();
    expect(inchesTextFromCm(text), `${JSON.stringify(text)} must not convert`).toBeNull();
    expect(parseDimension(text), `${JSON.stringify(text)} must not parse`).toBeNull();
  }
});

test("a value past Decimal(10, 2) converts to nothing rather than to a number the column cannot hold", () => {
  // 99,999,999.99 is the ceiling. A conversion that overflowed it would 422 the whole save on a
  // column the designer never typed into, which is the one refusal they could not act on.
  expect(cmTextFromInches("99999999")).toBeNull();
  expect(inchesTextFromCm("999999999")).toBeNull();
  // And the largest value that DOES fit still converts.
  expect(cmTextFromInches("1000000")).toBe("2540000");

  // A FOUR-HUNDRED-DIGIT INTEGER MATCHES THE GRAMMAR and parses to `Infinity` in both languages, so
  // the `isFinite` guard in `parseDimension` is reachable and is not decoration. It is refused for
  // not being a number rather than for being large — the ceiling test would also refuse it, but only
  // by accident of `Infinity > 9999999999` being true.
  const huge = "9".repeat(400);
  expect(parseDimension(huge)).toBeNull();
  expect(cmTextFromInches(huge)).toBeNull();
  expect(inchesTextFromCm(huge)).toBeNull();
});

test("a partially typed decimal keeps converting instead of blanking its partner", () => {
  /*
    THE CHOSEN BEHAVIOUR, AND WHY. Typing "1.5" passes through "1" and then "1.". Both of those are
    numbers under the grammar, so the partner tracks the keystrokes: 2.54 → 2.54 → 3.81. It never
    blanks and never flickers.

    "1.2." is not a number, so the partner holds the previous answer for one keystroke and is
    corrected by the next. That is strictly better than blanking, because a blank partner means "no
    value" — see the clearing rule below — and a person mid-decimal has not said that.
  */
  expect(cmTextFromInches("1")).toBe("2.54");
  expect(cmTextFromInches("1.")).toBe("2.54");
  expect(cmTextFromInches("1.5")).toBe("3.81");
  expect(cmTextFromInches("1.2")).toBe("3.05");
  expect(cmTextFromInches("1.2.")).toBeNull();
});

test("surrounding spaces and tabs are tolerated, and nothing else is", () => {
  expect(cmTextFromInches("  2  ")).toBe("5.08");
  expect(cmTextFromInches("\t2\t")).toBe("5.08");
  // A newline is not; nor is a sign, an exponent or a separator. See REFUSED above.
  expect(cmTextFromInches("2\n")).toBeNull();
});

test("propagate: an emptied box empties its partner, and only an emptied box does", () => {
  const writes: string[] = [];
  const setPartner = (value: string) => writes.push(value);

  // Rule 3 — clearing propagates, and it is the branch BEFORE the parse. An empty box means "no
  // value", and a stale converted number left standing beside it is a lie about a measurement.
  propagate("", cmTextFromInches, setPartner);
  propagate("   ", cmTextFromInches, setPartner);
  expect(writes).toEqual(["", ""]);

  // Rule 4 — anything else that is not a number leaves the partner exactly as it is. Not "", which
  // would claim the designer had said there is no height.
  writes.length = 0;
  for (const text of ["-", "abc", "1.2.3", "1e3", "-1"]) propagate(text, cmTextFromInches, setPartner);
  expect(writes).toEqual([]);

  // And a number writes its conversion, once.
  writes.length = 0;
  propagate("2", cmTextFromInches, setPartner);
  expect(writes).toEqual(["5.08"]);
});

test("a round trip does not eat the value — which is why the write is one-directional", () => {
  /*
    THE FAILURE THIS PAIRING IS SHAPED AROUND. 1 cm → 0.39 in → 0.99 cm: the two-decimal rounding is
    lossy in both directions, so a watcher effect over BOTH values (each write firing the other)
    walks a stored measurement downwards one hundredth at a time, on every keystroke, for ever.
    This test does not assert that the round trip is lossless — it is not, and cannot be. It pins
    that it LOSES, which is the whole argument for writing the partner only from the box the person
    is actually typing in.
  */
  const cm = inchesTextFromCm("1");
  expect(cm).toBe("0.39");
  expect(cmTextFromInches(cm as string)).toBe("0.99");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The form's half: where the conversion may fire, and where it may not
 * ──────────────────────────────────────────────────────────────────────────── */

const TOOL_FORM = readFileSync(join(__dirname, "..", "components", "forms", "ToolForm.tsx"), "utf8");

test("the tool form converts on input and NEVER on load", () => {
  // Each box is seeded from its OWN column and from nothing else. A tool saved before the pairing
  // genuinely holds two unrelated numbers in `height` and `heightInches` — the schema comment and
  // the on-screen note both say so — and a load-time conversion would overwrite one of them with a
  // number derived from the other, on the next save, for every historic row at once.
  for (const [state, column] of [
    ["height", "height"],
    ["width", "width"],
    ["heightInches", "heightInches"]
  ] as const) {
    expect(TOOL_FORM, `${state} is seeded from something other than its own column`).toContain(
      `useState(initial?.${column} != null ? String(initial.${column}) : "")`
    );
  }
  // Belt: no seed anywhere runs a conversion.
  expect(TOOL_FORM, "a conversion in a useState initialiser is a load-time rewrite").not.toMatch(
    /useState\([^)]*(?:cmTextFromInches|inchesTextFromCm)/
  );
});

test("no effect watches the paired boxes — the write is one-directional by construction", () => {
  /*
    A `useEffect(() => …, [height, heightInches])` fires for the value it just wrote, which
    reintroduces the round trip above. React's `setState` does not re-invoke the target control's
    own `onChange`, so writing the partner inside the source box's handler is already the correct
    shape; this assertion is what stops somebody "simplifying" it into a watcher.
  */
  const effects = TOOL_FORM.match(/useEffect\([\s\S]*?\n {2}\}, \[[^\]]*\]\);/g) ?? [];
  expect(effects.length, "the form still has effects to check").toBeGreaterThan(0);
  for (const effect of effects) {
    expect(effect, "an effect converts between the paired boxes").not.toMatch(/cmTextFromInches|inchesTextFromCm/);
  }
});

test("the centimetre boxes are the ones labelled (cm), and the columns keep their names", () => {
  // The visible label changed on all four clients together; the wire key, the column and
  // `_CLEARABLE_COLUMNS` did not. Renaming the column would have been a migration and a break for
  // every reader of it; renaming the label is what the designer actually needed.
  expect(TOOL_FORM).toContain('<Field label="Height (cm)">');
  expect(TOOL_FORM).toContain('<Field label="Width (cm)">');
  expect(TOOL_FORM).toContain('name="height"');
  expect(TOOL_FORM).toContain('name="width"');
  expect(TOOL_FORM, "the inch partners keep their own labels").toContain('<Field label="Breadth (inches)">');
  expect(TOOL_FORM).toContain('<Field label="Height (inches)">');
  // `Length (inches)` is standalone: no centimetre partner, no new column, nothing to pair it with.
  expect(TOOL_FORM).toContain('onChange={typeInches(setLength, "lengthInches")}');
});

test("the on-screen note states the pairing instead of the instruction it reversed", () => {
  const note = TOOL_FORM.slice(TOOL_FORM.indexOf("<p id={`${formId}-heights`}"), TOOL_FORM.indexOf("</p>", TOOL_FORM.indexOf("<p id={`${formId}-heights`}")));
  expect(note, "the note must say the two boxes are one measurement").toContain("the same measurement in two units");
  expect(note, "and that filling either fills the other").toContain("filling either fills the other");
  expect(note, "and it must stay honest about the rows that disagree").toContain("two numbers that disagree");
  // THE INSTRUCTION THAT IS NOW FALSE. It read "Fill one of the two, not both", which is the exact
  // opposite of what the form does — a designer following it would be avoiding the pairing.
  expect(note, "the reversed instruction is still on screen").not.toContain("Fill one of the two");
  // The wiring the note depends on is unchanged: both HEIGHT boxes name it, the width pair does not
  // (the paragraph names all four by label, and a second pointer would say it twice), and it is a
  // full-width row of its own so a two-line sentence cannot stretch the number boxes beside it.
  expect(TOOL_FORM.match(/aria-describedby=\{`\$\{formId\}-heights`\}/g)?.length).toBe(2);
  expect(note).toContain("md:col-span-2 xl:col-span-3");
});

test("an accepted machine reading fills the centimetre partner and leaves the marker alone", () => {
  /*
    `DIMENSION_FIELDS` is exactly `lengthInches` / `breadthInches` / `heightInches`, so the marker is
    recorded for the INCH box and the centimetre box carries no provenance — it is a conversion, not
    a second claim about how the tool was measured. `measurementMethodsFor` compares the inch box's
    text against the accepted text byte for byte, and writing the centimetre box does not touch it.

    Both measurement routes are covered: the deterministic panel's `onPropose` and the vision
    model's `onLengthBreadth` / `onHeight`.
  */
  const propose = TOOL_FORM.slice(TOOL_FORM.indexOf("onPropose={(key, text, method) => {"), TOOL_FORM.indexOf("onPhotoChange="));
  expect(propose, "an accepted breadth must fill the width box").toContain("propagate(text, cmTextFromInches, setWidth)");
  expect(propose, "an accepted height must fill the height (cm) box").toContain("propagate(text, cmTextFromInches, setHeight)");
  expect(propose, "an accepted length has no centimetre partner to fill").toMatch(
    /if \(key === "lengthInches"\) setLength\(text\);/
  );
  // NOT through the typing factory: it calls `forgetAcceptance` and writes the inch box, which would
  // undo the acceptance being recorded two lines below it.
  expect(propose, "the accept path must not route through the typing handler").not.toContain("typeInches(");

  const grid = TOOL_FORM.slice(TOOL_FORM.indexOf("onLengthBreadth={(l, b, method) => {"), TOOL_FORM.indexOf("onFilesChange="));
  expect(grid, "the grid's breadth must fill the width box").toContain("propagate(b, cmTextFromInches, setWidth)");
  expect(grid, "the grid's height must fill the height (cm) box").toContain("propagate(value, cmTextFromInches, setHeight)");
  // And no marker anywhere names a centimetre column — that is a 422 on the whole save, not a
  // dropped hint, because `rememberAcceptance` refuses a key outside `DIMENSION_FIELDS`.
  expect(TOOL_FORM).not.toMatch(/rememberAcceptance\(current, "(height|width)"[,)]/);
});
