import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { hasStoredOverride } from "@/components/settings/ApiKeysPanel";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

function between(source: string, from: string, to: string): string {
  const start = source.indexOf(from);
  expect(start, `${from} not found — has the file been restructured?`).toBeGreaterThan(-1);
  const end = source.indexOf(to, start);
  expect(end, `${to} not found after ${from}`).toBeGreaterThan(-1);
  return source.slice(start, end);
}

function codeOf(source: string): string {
  const blanked = (match: string) => match.replace(/[^\n]/g, " ");
  return source.replace(/\/\*[\s\S]*?\*\//g, blanked).replace(/\/\/[^\n]*/g, blanked);
}

test("a healthy override can still be cleared", () => {
  expect(hasStoredOverride({ source: "database" })).toBe(true);
  expect(hasStoredOverride({ source: "database", overrideUnreadable: false })).toBe(true);
});

test("an override that cannot be decrypted can be cleared — that is the whole point of it", () => {
  expect(hasStoredOverride({ source: "environment", overrideUnreadable: true })).toBe(true);
  expect(hasStoredOverride({ source: "unset", overrideUnreadable: true })).toBe(true);
});

test("a key with no stored row of its own offers nothing to clear", () => {
  expect(hasStoredOverride({ source: "environment" })).toBe(false);
  expect(hasStoredOverride({ source: "environment", overrideUnreadable: false })).toBe(false);
  expect(hasStoredOverride({ source: "unset" })).toBe(false);
  expect(hasStoredOverride({ source: "unset", overrideUnreadable: undefined })).toBe(false);
});

test("the panel draws Clear override from row existence, not from where the value came from", () => {
  const source = read("components", "settings", "ApiKeysPanel.tsx");
  const code = codeOf(source);

  const clearButton = between(code, "RowActions>", "Clear override");
  expect(clearButton, "the Clear override button must be gated on hasStoredOverride").toContain(
    "hasStoredOverride(secret)"
  );
  expect(
    clearButton,
    'gating the remedy on `source === "database"` hides it from the unreadable-override row the source fix exists to report'
  ).not.toContain('secret.source === "database"');

  expect(source).toContain("secret.overrideUnreadable ?");
});
