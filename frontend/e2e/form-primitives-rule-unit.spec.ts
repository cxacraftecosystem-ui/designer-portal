import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";

import { expect, test } from "@playwright/test";

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
  expect(offenders(/^\s*<select\b/).filter((hit) => !hit.startsWith(path.join("components", "ui", "calendar.tsx")))).toEqual([]);
});

test("no native date input on any surface", () => {
  expect(offenders(/^\s*type="date"/)).toEqual([]);
  expect(offenders(/^\s*<(input|TextInput)\b[^>]*\btype="date"/)).toEqual([]);
});

test("every six-digit code is entered in the six boxes", () => {
  const hits = offenders(/autoComplete="one-time-code"|autoComplete=\{index === 0 \? "one-time-code"/);
  expect(hits.every((hit) => hit.startsWith(path.join("components", "ui", "OtpCodeInput.tsx")))).toBe(true);
});

test("no setting reads as an ON/OFF word", () => {
  expect(offenders(/\? "On" : "Off"|: ON"|: OFF"/)).toEqual([]);
});

test("every date box is the CalendarField", () => {
  const allowed = [
    path.join("components", "ui", "CalendarField.tsx"),
    path.join("components", "forms", "DateTimeField.tsx"),
    path.join("components", "designworkshop", "FieldInput.tsx")
  ];
  expect(offenders(/^\s*<DateField\b/).filter((hit) => !allowed.some((file) => hit.startsWith(file)))).toEqual([]);
});
