import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { ROLES_BY_RANK, ROLE_LABELS, ROLE_RANK } from "@/lib/permissions";
import type { UserRole } from "@/lib/types";

/**
 * THE ROLE LADDER, PROVED EQUAL TO THE SERVER'S — labels and key order included.
 *
 * WHY THIS SPEC EXISTS. `frontend/lib/permissions.ts` opens by claiming it mirrors
 * `backend/app/core/deps.py` "exactly": the same eight keys, at the same numbers, with the same
 * labels, in the same declaration order. Two of those four were already mechanical —
 * `docs/tools/check-docs.mjs::checkRoleParity` parses `ROLE_RANK` out of both files and diffs the
 * KEYS and the NUMBERS in both directions. Nothing compared the LABELS or the ORDER, so the header
 * was asserting four properties on the strength of somebody having once looked at two of them. The
 * whole argument that produced this spec is that "already right" and "asserted" are different
 * states; a comment is the first of those and this file is the second.
 *
 * WHY THE LABELS ARE NOT COSMETIC. `roleLabel()` is what every surface in the client renders when
 * it has to name a tier — the user table, the assignment builder, the activity feed, every refusal
 * panel. The server sends the ROLE, never its label, so a client whose label map has drifted does
 * not fail: it silently names the wrong tier at the user, on the page where they decide who may
 * write a ministry report. A missing key is worse still, because `roleLabel` falls back to the raw
 * enum and shows "CROWDSOURCE_VOLUNTEER" in a sentence.
 *
 * WHY THE ORDER IS PINNED EVEN THOUGH NOTHING READS IT. It is a readability convention, not a
 * behaviour — `ROLES_BY_RANK` sorts on the VALUES and the ranks are distinct, so the sort is
 * order-independent, and the last test here proves that rather than asserting it in prose. What
 * declaration order buys is that the two files can be diffed against each other by eye, which is
 * how every ladder question in this repository has actually been answered. It is cheap to keep and
 * the check costs one line.
 *
 * READ OFF DISK, NOT REMEMBERED. A hard-coded expectation here would be a third copy of the ladder
 * and would agree with the server only on the day it was typed — the exact failure this file is
 * about. If one of these ever fails, do not edit the expectation: find which side moved.
 *
 * WHAT THIS DOES NOT COVER. `ROLE_LABELS` has FOUR copies in the repository. The two here are now
 * diffed; the Android pair (`MainActivity.kt`, `TaskAdminScreen.kt`) is hand-kept and unchecked,
 * and both were correct when this spec was written. Widening to Kotlin belongs with whoever owns
 * the Android client, not here.
 *
 * PURE NODE — no browser, no server, no database.
 * Run: `npx playwright test e2e/role-ladder-parity-unit.spec.ts --reporter=line`
 */

const DEPS_PY = readFileSync(
  join(__dirname, "..", "..", "backend", "app", "core", "deps.py"),
  "utf8"
);

/**
 * One `NAME: dict[...] = { ... }` literal out of the Python, as its raw body text.
 *
 * The closing brace is matched at column zero, which is what a module-level dict literal ends with
 * in this file — a lazy `[\s\S]*?\}` would stop at the first `}` inside the body the day somebody
 * puts one there.
 */
function pythonDict(name: string): string {
  const match = new RegExp(String.raw`^${name}\s*:[^=]*=\s*\{([\s\S]*?)^\}`, "m").exec(DEPS_PY);
  expect(match, `${name} is no longer declared in deps.py where this spec reads it`).toBeTruthy();
  return match?.[1] ?? "";
}

/** The keys of a Python dict literal, IN DECLARATION ORDER. Comment lines carry no `"key":`. */
function pythonKeys(body: string): string[] {
  return [...body.matchAll(/"(\w+)"\s*:/g)].map((m) => m[1]);
}

const RANK_BODY = pythonDict("ROLE_RANK");
const LABEL_BODY = pythonDict("ROLE_LABELS");

test("the eight keys are the server's eight keys, in the server's order", () => {
  /*
    ORDER-SENSITIVE ON PURPOSE — `toEqual` on arrays, not on sets. The keys agreeing as a set is
    what `checkRoleParity` already proves; this test exists for the other half of the header's
    claim. Failing here means the two files no longer diff line-for-line, which is a documentation
    defect rather than a runtime one — reorder whichever side moved, do not relax the assertion.
  */
  expect(pythonKeys(RANK_BODY)).toEqual(Object.keys(ROLE_RANK));
});

test("every tier the server ranks has the client's label, spelled identically", () => {
  const serverLabels = Object.fromEntries(
    [...LABEL_BODY.matchAll(/"(\w+)"\s*:\s*"([^"]*)"/g)].map((m) => [m[1], m[2]])
  );

  /*
    BOTH DIRECTIONS, and the second one is the one a human would skip. A key the server has and the
    client does not is a tier rendered as its raw enum name; a key the CLIENT has and the server does
    not is a tier that no longer exists being offered in a picker, which is worse — it is an option
    that produces a 403 on submit.
  */
  expect(serverLabels).toEqual(ROLE_LABELS);
  expect(pythonKeys(LABEL_BODY)).toEqual(Object.keys(ROLE_LABELS));
  expect(pythonKeys(LABEL_BODY)).toEqual(pythonKeys(RANK_BODY));
});

test("the numbers agree too, so this spec fails on drift even if check-docs is not run", () => {
  /*
    DELIBERATELY OVERLAPS `checkRoleParity`. That check lives in `node docs/tools/check-docs.mjs`,
    which is a separate command a person has to remember; this suite is the one a frontend change
    runs. A duplicated assertion is a smaller cost than a ladder that drifts because the only thing
    watching it was in the other checker.
  */
  const serverRanks = Object.fromEntries(
    [...RANK_BODY.matchAll(/"(\w+)"\s*:\s*(\d+)/g)].map((m) => [m[1], Number(m[2])])
  );

  expect(serverRanks).toEqual(ROLE_RANK);
});

test("ROLES_BY_RANK is decided by the numbers alone, so declaration order cannot change a picker", () => {
  /*
    THE CLAIM IN `permissions.ts`' HEADER, PROVED RATHER THAN ASSERTED. An earlier version of that
    header said the declaration order mattered "because ROLES_BY_RANK sorts on the values" — which
    is precisely the reason it does NOT matter. All eight ranks are distinct, so the sort is total
    and its result is independent of the order the keys were written in.

    Shuffling deterministically rather than randomly: a flaky ordering would make this test's own
    failures unreproducible, and reversing plus rotating is enough to break any accidental reliance
    on the source order.
  */
  const shuffled = [...Object.keys(ROLE_RANK)].reverse();
  shuffled.push(shuffled.shift() as string);
  const rebuilt = Object.fromEntries(shuffled.map((role) => [role, ROLE_RANK[role as UserRole]]));

  const fromShuffled = (Object.keys(rebuilt) as UserRole[]).sort((a, b) => ROLE_RANK[b] - ROLE_RANK[a]);

  expect(fromShuffled).toEqual(ROLES_BY_RANK);
  // And it really is the ladder, highest first — otherwise the two could agree while both were wrong.
  // INSPECTOR's 37 sits between DESIGNER's 35 and PROFESSOR's 40, added 2026-08-27. The literal is
  // spelled out rather than derived on purpose: a test that computed this from ROLE_RANK would agree
  // with any ladder at all, including one a bad edit had reordered.
  expect(ROLES_BY_RANK.map((role) => ROLE_RANK[role])).toEqual([60, 50, 40, 37, 35, 30, 20, 10]);
});
