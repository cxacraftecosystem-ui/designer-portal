import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";

import { expect, test } from "@playwright/test";

/**
 * THE OWNER'S RULE (2026-10-17), held mechanically: on no surface a plain `<select>`, a native date
 * input, or a single-box six-digit code. Use `SearchableSelect` / `SearchableMultiSelect`,
 * `CalendarField`, and `OtpCodeInput` (field-repo-frontend §11.12).
 *
 * The scan reads JSX ELEMENTS, not prose: a line that STARTS with `<select` or with `type="date"` is
 * markup, while the many comments explaining why a native control was replaced quote it mid-line.
 * Checkbox-as-setting is not machine-detectable (a checkbox choosing rows is legitimate), so it is held
 * by review and the skill, not here.
 */

const ROOT = path.resolve(__dirname, "..");

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const full = path.join(dir, name);
    if (statSync(full).isDirectory()) walk(full, out);
    else if (/\.tsx$/.test(name)) out.push(full);
  }
  return out;
}

const files = [...walk(path.join(ROOT, "app")), ...walk(path.join(ROOT, "components"))];

function offenders(pattern: RegExp): string[] {
  const found: string[] = [];
  for (const file of files) {
    readFileSync(file, "utf8")
      .split(/\r?\n/)
      .forEach((line, index) => {
        if (pattern.test(line)) found.push(`${path.relative(ROOT, file)}:${index + 1}`);
      });
  }
  return found;
}

test("no plain <select> element on any surface", () => {
  // components/ui/calendar.tsx draws react-day-picker's month/year dropdown, whose real <select> sits
  // transparent over the themed label — that is the calendar's own internals, not a form control.
  expect(offenders(/^\s*<select\b/).filter((hit) => !hit.startsWith(path.join("components", "ui", "calendar.tsx")))).toEqual([]);
});

test("no native date input on any surface", () => {
  expect(offenders(/^\s*type="date"/)).toEqual([]);
  expect(offenders(/^\s*<(input|TextInput)\b[^>]*\btype="date"/)).toEqual([]);
});

test("every six-digit code is entered in the six boxes", () => {
  // `autoComplete="one-time-code"` belongs to the first of OtpCodeInput's boxes and nowhere else.
  const hits = offenders(/autoComplete="one-time-code"|autoComplete=\{index === 0 \? "one-time-code"/);
  expect(hits.every((hit) => hit.startsWith(path.join("components", "ui", "OtpCodeInput.tsx")))).toBe(true);
});

test("no setting reads as an ON/OFF word", () => {
  // A setting says on or off by being a switch (`Toggle` / `ToggleSwitch`), never by printing the word.
  expect(offenders(/\? "On" : "Off"|: ON"|: OFF"/)).toEqual([]);
});

test("every date box is the CalendarField", () => {
  // `DateField` is the box `CalendarField` is built on. The one other caller is the stage form's
  // `FieldInput`, whose registry-driven `labelled()` wrapper already draws the label, hint and error
  // that `CalendarField` would otherwise draw a second time.
  const allowed = [
    path.join("components", "ui", "CalendarField.tsx"),
    path.join("components", "forms", "DateTimeField.tsx"),
    path.join("components", "designworkshop", "FieldInput.tsx")
  ];
  expect(offenders(/^\s*<DateField\b/).filter((hit) => !allowed.some((file) => hit.startsWith(file)))).toEqual([]);
});
