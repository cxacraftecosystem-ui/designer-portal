import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { hasStoredOverride } from "@/components/settings/ApiKeysPanel";

/**
 * THE ROW THAT REPORTS A BROKEN OVERRIDE MUST KEEP THE BUTTON THAT CLEARS IT.
 *
 * Background, because this test only makes sense with it. `managed_secrets._describe` used to derive
 * a key's provenance from `row is not None`, so a stored override that could no longer be decrypted
 * (JWT_SECRET rotated while SECRETS_ENCRYPTION_KEY was unset — the module header's own scenario) was
 * labelled `source: "database"` while every provider call, and the reveal endpoint, were quietly
 * using the ENVIRONMENT key. Three audit findings, one cause. The fix made `source` name the value
 * in force, and moved the fact that a dead row is sitting behind it to `overrideUnreadable`.
 *
 * That fix then broke the remedy. This panel drew "Clear override" from `secret.source ===
 * "database"`, so the honest label took the button off the exact row it was reporting: the admin was
 * shown "this override cannot be read" and left with no control to remove it. A fix that removes the
 * remedy for the state it exists to report is not finished.
 *
 * WHAT EACH HALF OF THIS SPEC PROVES, stated plainly because one half alone would be cover rather
 * than a test. The pure calls pin the RULE (row existence, not value provenance) and would fail if
 * somebody narrowed it back to the readable case. The source read pins the WIRING — that the button
 * is actually drawn from that rule — and it is the half with teeth against the tree as it was: the
 * old file contains `{secret.source === "database" ? (` immediately above `Clear override`, which
 * the ban below rejects. Verified by running these assertions against `git show HEAD:` of the file
 * on 2026-08-16: the ban failed, and the `hasStoredOverride(` requirement failed with it.
 *
 * What neither half proves: that a browser paints the button. There is no React renderer in this
 * repository's devDependencies (Playwright is the whole of it), and the state cannot be produced on
 * a live stack without rotating a deployment's JWT_SECRET — which is why the rule is executed here
 * and the wiring is read.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

/** The text between two markers, so an assertion cannot drift into a neighbouring control. */
function between(source: string, from: string, to: string): string {
  const start = source.indexOf(from);
  expect(start, `${from} not found — has the file been restructured?`).toBeGreaterThan(-1);
  const end = source.indexOf(to, start);
  expect(end, `${to} not found after ${from}`).toBeGreaterThan(-1);
  return source.slice(start, end);
}

/**
 * Comments blanked out (spaces, so line numbers survive), because the bans below name identifiers
 * that the house-style comments beside them are REQUIRED to mention. A structural test that fails on
 * the comment explaining the rule would leave an author two ways out — delete the explanation, or
 * misspell it — and both are worse than the defect.
 */
function codeOf(source: string): string {
  const blanked = (match: string) => match.replace(/[^\n]/g, " ");
  return source.replace(/\/\*[\s\S]*?\*\//g, blanked).replace(/\/\/[^\n]*/g, blanked);
}

test("a healthy override can still be cleared", () => {
  expect(hasStoredOverride({ source: "database" })).toBe(true);
  expect(hasStoredOverride({ source: "database", overrideUnreadable: false })).toBe(true);
});

test("an override that cannot be decrypted can be cleared — that is the whole point of it", () => {
  // The rotation casualty, both ways round: with an environment key standing in for the dead
  // override, and with nothing standing in for it at all. `source` is honest in both — it names what
  // will actually be sent — and in both there is a row to delete.
  expect(hasStoredOverride({ source: "environment", overrideUnreadable: true })).toBe(true);
  expect(hasStoredOverride({ source: "unset", overrideUnreadable: true })).toBe(true);
});

test("a key with no stored row of its own offers nothing to clear", () => {
  // The other direction matters just as much: "Clear override" on an environment-only key would
  // promise to delete something that does not exist, and DELETE /secrets/{key} would answer 404.
  expect(hasStoredOverride({ source: "environment" })).toBe(false);
  expect(hasStoredOverride({ source: "environment", overrideUnreadable: false })).toBe(false);
  expect(hasStoredOverride({ source: "unset" })).toBe(false);
  // Absent on the wire is NOT "unreadable": a response predating the field must not sprout a button.
  expect(hasStoredOverride({ source: "unset", overrideUnreadable: undefined })).toBe(false);
});

test("the panel draws Clear override from row existence, not from where the value came from", () => {
  const source = read("components", "settings", "ApiKeysPanel.tsx");
  const code = codeOf(source);

  // The control itself, isolated so this cannot pass on some other `source` test elsewhere in the
  // file. `startEdit`'s Add/Update label legitimately still reads `source === "unset"` — that IS a
  // question about the value in force — and this slice deliberately excludes it.
  const clearButton = between(code, "RowActions>", "Clear override");
  expect(clearButton, "the Clear override button must be gated on hasStoredOverride").toContain(
    "hasStoredOverride(secret)"
  );
  expect(
    clearButton,
    'gating the remedy on `source === "database"` hides it from the unreadable-override row the source fix exists to report'
  ).not.toContain('secret.source === "database"');

  // And the row says WHY an "Environment" badge is offering to clear an override, otherwise the two
  // contradict each other on screen.
  expect(source).toContain("secret.overrideUnreadable ?");
});
