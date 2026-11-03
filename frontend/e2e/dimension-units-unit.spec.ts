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

const TABLE: ReadonlyArray<readonly [string, string, string]> = [
  ["1", "2.54", "0.39"],
  ["1.0", "2.54", "0.39"],
  [".5", "1.27", "0.2"],
  ["0.5", "1.27", "0.2"],
  ["2", "5.08", "0.79"],
  ["3", "7.62", "1.18"],
  ["10", "25.4", "3.94"],
  ["100", "254", "39.37"],
  ["2.54", "6.45", "1"],
  ["12.34", "31.34", "4.86"],
  ["0.1", "0.25", "0.04"],
  ["1.25", "3.18", "0.49"],
  ["0.125", "0.32", "0.05"],
  ["1.875", "4.76", "0.74"],
  ["6.25", "15.88", "2.46"],
  ["0.05", "0.13", "0.02"],
  ["1.005", "2.55", "0.4"]
];

const REFUSED = ["", "-", "-1", "1.2.3", "abc", "1e3", "0x1A", "1.5f", "Infinity", "NaN", "1,5", "1 000"];

test("the inch is exactly 2.54 cm, and the constant is named rather than inlined", () => {
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
  expect(cmTextFromInches("99999999")).toBeNull();
  expect(inchesTextFromCm("999999999")).toBeNull();
  expect(cmTextFromInches("1000000")).toBe("2540000");

  const huge = "9".repeat(400);
  expect(parseDimension(huge)).toBeNull();
  expect(cmTextFromInches(huge)).toBeNull();
  expect(inchesTextFromCm(huge)).toBeNull();
});

test("a partially typed decimal keeps converting instead of blanking its partner", () => {
  expect(cmTextFromInches("1")).toBe("2.54");
  expect(cmTextFromInches("1.")).toBe("2.54");
  expect(cmTextFromInches("1.5")).toBe("3.81");
  expect(cmTextFromInches("1.2")).toBe("3.05");
  expect(cmTextFromInches("1.2.")).toBeNull();
});

test("surrounding spaces and tabs are tolerated, and nothing else is", () => {
  expect(cmTextFromInches("  2  ")).toBe("5.08");
  expect(cmTextFromInches("\t2\t")).toBe("5.08");
  expect(cmTextFromInches("2\n")).toBeNull();
});

test("propagate: an emptied box empties its partner, and only an emptied box does", () => {
  const writes: string[] = [];
  const setPartner = (value: string) => writes.push(value);

  propagate("", cmTextFromInches, setPartner);
  propagate("   ", cmTextFromInches, setPartner);
  expect(writes).toEqual(["", ""]);

  writes.length = 0;
  for (const text of ["-", "abc", "1.2.3", "1e3", "-1"]) propagate(text, cmTextFromInches, setPartner);
  expect(writes).toEqual([]);

  writes.length = 0;
  propagate("2", cmTextFromInches, setPartner);
  expect(writes).toEqual(["5.08"]);
});

test("a round trip does not eat the value — which is why the write is one-directional", () => {
  const cm = inchesTextFromCm("1");
  expect(cm).toBe("0.39");
  expect(cmTextFromInches(cm as string)).toBe("0.99");
});

const TOOL_FORM = readFileSync(join(__dirname, "..", "components", "forms", "ToolForm.tsx"), "utf8");

test("the tool form converts on input and NEVER on load", () => {
  for (const [state, column] of [
    ["height", "height"],
    ["width", "width"],
    ["heightInches", "heightInches"]
  ] as const) {
    expect(TOOL_FORM, `${state} is seeded from something other than its own column`).toContain(
      `useState(initial?.${column} != null ? String(initial.${column}) : "")`
    );
  }
  expect(TOOL_FORM, "a conversion in a useState initialiser is a load-time rewrite").not.toMatch(
    /useState\([^)]*(?:cmTextFromInches|inchesTextFromCm)/
  );
});

test("no effect watches the paired boxes — the write is one-directional by construction", () => {
  const effects = TOOL_FORM.match(/useEffect\([\s\S]*?\n {2}\}, \[[^\]]*\]\);/g) ?? [];
  expect(effects.length, "the form still has effects to check").toBeGreaterThan(0);
  for (const effect of effects) {
    expect(effect, "an effect converts between the paired boxes").not.toMatch(/cmTextFromInches|inchesTextFromCm/);
  }
});

test("the centimetre boxes are the ones labelled (cm), and the columns keep their names", () => {
  expect(TOOL_FORM).toContain('<Field label="Height (cm)">');
  expect(TOOL_FORM).toContain('<Field label="Width (cm)">');
  expect(TOOL_FORM).toContain('name="height"');
  expect(TOOL_FORM).toContain('name="width"');
  expect(TOOL_FORM, "the inch partners keep their own labels").toContain('<Field label="Breadth (inches)">');
  expect(TOOL_FORM).toContain('<Field label="Height (inches)">');
  expect(TOOL_FORM).toContain('onChange={typeInches(setLength, "lengthInches")}');
});

test("the on-screen note states the pairing instead of the instruction it reversed", () => {
  const note = TOOL_FORM.slice(TOOL_FORM.indexOf("<p id={`${formId}-heights`}"), TOOL_FORM.indexOf("</p>", TOOL_FORM.indexOf("<p id={`${formId}-heights`}")));
  expect(note, "the note must say the two boxes are one measurement").toContain("the same measurement in two units");
  expect(note, "and that filling either fills the other").toContain("filling either fills the other");
  expect(note, "and it must stay honest about the rows that disagree").toContain("If two paired boxes disagree");
  expect(note, "the reversed instruction is still on screen").not.toContain("Fill one of the two");
  expect(TOOL_FORM.match(/aria-describedby=\{`\$\{formId\}-heights`\}/g)?.length).toBe(2);
  expect(note).toContain("md:col-span-2 xl:col-span-3");
});

test("an accepted machine reading fills the centimetre partner and leaves the marker alone", () => {
  const propose = TOOL_FORM.slice(TOOL_FORM.indexOf("onPropose={(key, text, method) => {"), TOOL_FORM.indexOf("onPhotoChange="));
  expect(propose, "an accepted breadth must fill the width box").toContain("propagate(text, cmTextFromInches, setWidth)");
  expect(propose, "an accepted height must fill the height (cm) box").toContain("propagate(text, cmTextFromInches, setHeight)");
  expect(propose, "an accepted length has no centimetre partner to fill").toMatch(
    /if \(key === "lengthInches"\) setLength\(text\);/
  );
  expect(propose, "the accept path must not route through the typing handler").not.toContain("typeInches(");

  const grid = TOOL_FORM.slice(TOOL_FORM.indexOf("onLengthBreadth={(l, b, method) => {"), TOOL_FORM.indexOf("onFilesChange="));
  expect(grid, "the grid's breadth must fill the width box").toContain("propagate(b, cmTextFromInches, setWidth)");
  expect(grid, "the grid's height must fill the height (cm) box").toContain("propagate(value, cmTextFromInches, setHeight)");
  expect(TOOL_FORM).not.toMatch(/rememberAcceptance\(current, "(height|width)"[,)]/);
});
