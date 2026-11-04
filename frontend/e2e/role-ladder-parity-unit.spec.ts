import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { ROLES_BY_RANK, ROLE_LABELS, ROLE_RANK } from "@/lib/permissions";
import type { UserRole } from "@/lib/types";

const DEPS_PY = readFileSync(
  join(__dirname, "..", "..", "backend", "app", "core", "deps.py"),
  "utf8"
);

function pythonDict(name: string): string {
  const match = new RegExp(String.raw`^${name}\s*:[^=]*=\s*\{([\s\S]*?)^\}`, "m").exec(DEPS_PY);
  expect(match, `${name} is no longer declared in deps.py where this spec reads it`).toBeTruthy();
  return match?.[1] ?? "";
}

function pythonKeys(body: string): string[] {
  return [...body.matchAll(/"(\w+)"\s*:/g)].map((m) => m[1]);
}

const RANK_BODY = pythonDict("ROLE_RANK");
const LABEL_BODY = pythonDict("ROLE_LABELS");

test("the eleven keys are the server's eleven keys, in the server's order", () => {
  expect(pythonKeys(RANK_BODY)).toEqual(Object.keys(ROLE_RANK));
});

test("every tier the server ranks has the client's label, spelled identically", () => {
  const serverLabels = Object.fromEntries(
    [...LABEL_BODY.matchAll(/"(\w+)"\s*:\s*"([^"]*)"/g)].map((m) => [m[1], m[2]])
  );

  expect(serverLabels).toEqual(ROLE_LABELS);
  expect(pythonKeys(LABEL_BODY)).toEqual(Object.keys(ROLE_LABELS));
  expect(pythonKeys(LABEL_BODY)).toEqual(pythonKeys(RANK_BODY));
});

test("the numbers agree too, so this spec fails on drift even if check-docs is not run", () => {
  const serverRanks = Object.fromEntries(
    [...RANK_BODY.matchAll(/"(\w+)"\s*:\s*(\d+)/g)].map((m) => [m[1], Number(m[2])])
  );

  expect(serverRanks).toEqual(ROLE_RANK);
});

test("ROLES_BY_RANK is decided by the numbers alone, so declaration order cannot change a picker", () => {
  const shuffled = [...Object.keys(ROLE_RANK)].reverse();
  shuffled.push(shuffled.shift() as string);
  const rebuilt = Object.fromEntries(shuffled.map((role) => [role, ROLE_RANK[role as UserRole]]));

  const fromShuffled = (Object.keys(rebuilt) as UserRole[]).sort((a, b) => ROLE_RANK[b] - ROLE_RANK[a]);

  expect(fromShuffled).toEqual(ROLES_BY_RANK);
  expect(ROLES_BY_RANK.map((role) => ROLE_RANK[role])).toEqual([60, 50, 48, 45, 42, 40, 37, 35, 30, 20, 10]);
});
