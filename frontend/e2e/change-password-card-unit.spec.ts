import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import {
  PASSWORD_CHANGE_SESSIONS,
  currentPasswordRefused,
  hasOwnPassword,
  newPasswordProblem
} from "@/lib/passwordChange";
import { MAX_PASSWORD_LENGTH } from "@/lib/signIn";

/**
 * "CHANGE PASSWORD" IN SETTINGS — A PASSWORD ACCOUNT ROTATING ITS OWN SECRET, WITHOUT ASKING ANYBODY.
 *
 * ── THE GAP ─────────────────────────────────────────────────────────────────────────────────────
 *
 * `POST /auth/change-password` had one caller on the web: the first-password gate. Once past it, an
 * account that suspected somebody else knew its password could only ask an administrator for a link.
 * The accounts that sign in with a password are exactly the ones this matters for — an administrator
 * created them and typed a password two people therefore knew.
 *
 * ── WHAT IS PINNED ──────────────────────────────────────────────────────────────────────────────
 *
 *   1. it is offered to an account with a password of its own (`passwordSetAt`), and to no other;
 *   2. it refuses what the gate refuses, from the same functions — a mismatch, the password it is
 *      replacing — before anything is sent, and stops at the same 200-character ceiling;
 *   3. a refused current password empties that box and keeps the other two;
 *   4. it is a data-screen card drawn with the data-screen recipes, on the account's own hub;
 *   5. the receipt and the refusal are said into regions that exist before they have anything to say;
 *   6. the fresh session a change answers with is adopted before the account is re-read.
 *
 * Pure functions where the rule is a function, source reads where it is a component — there is no
 * React renderer in devDependencies. ⚠ LINE-ENDING AGNOSTIC: `\s` and `[\s\S]` throughout.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const CARD = read("components", "settings", "ChangePasswordCard.tsx");
const SETTINGS = read("app", "(protected)", "settings", "page.tsx");
const GATE = read("components", "FirstPasswordGate.tsx");

const SUBMIT = (() => {
  const from = CARD.indexOf("async function submit(");
  return from < 0 ? "" : CARD.slice(from, CARD.indexOf("return (", from));
})();

/* ────────────────────────────────────────────────────────────────────────────
 * 1. Who sees it
 * ──────────────────────────────────────────────────────────────────────────── */

test("it is offered to an account with a password of its own, and to no other", () => {
  expect(hasOwnPassword({ passwordSetAt: "2026-10-01T09:30:00.000Z" })).toBe(true);
  // A Google-only account has nothing to change, and the server would say so.
  expect(hasOwnPassword({ passwordSetAt: null })).toBe(false);
  // A server older than the column sends nothing: no card rather than a form the server refuses.
  expect(hasOwnPassword({})).toBe(false);
  expect(hasOwnPassword(null)).toBe(false);
  expect(hasOwnPassword(undefined)).toBe(false);
});

test("the card draws nothing for anybody else, and decides after its hooks", () => {
  const gate = CARD.indexOf("if (!user || !hasOwnPassword(user)) return null;");
  expect(gate, "the gate was located").toBeGreaterThan(-1);
  // Rules of hooks: an early return above a hook would change the hook count between renders.
  expect(gate).toBeGreaterThan(CARD.lastIndexOf("useEffect("));
  expect(gate).toBeGreaterThan(CARD.lastIndexOf("useState("));
});

test("it sits on the account's own settings hub, with no role gate", () => {
  expect(SETTINGS).toMatch(/import \{ ChangePasswordCard \} from "@\/components\/settings\/ChangePasswordCard"/);
  expect(SETTINGS).toContain("<ChangePasswordCard />");
  // Not behind the admin links: changing your own password needs permission from nobody.
  const adminAt = SETTINGS.indexOf("{links.length ? (");
  expect(SETTINGS.indexOf("<ChangePasswordCard />")).toBeLessThan(adminAt);
  // And the grievance card keeps its place DIRECTLY under the consent card, as its comment requires.
  expect(SETTINGS).toMatch(/<UsageConsentCard \/>\s*\{\/\*[\s\S]*?\*\/\}\s*<GrievanceRedressalCard \/>/);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The same refusals as the gate, from the same place
 * ──────────────────────────────────────────────────────────────────────────── */

test("it refuses a mismatch and the password it would replace, before sending anything", () => {
  expect(newPasswordProblem({ current: "old-password", next: "new-password", confirm: "new-passw0rd" })).toMatch(/do not match/);
  expect(newPasswordProblem({ current: "old-password", next: "old-password", confirm: "old-password" })).toMatch(
    /different from your current one/
  );
  expect(newPasswordProblem({ current: "old-password", next: "new-password", confirm: "new-password" })).toBeNull();

  expect(SUBMIT, "the submit was located").toContain("changeOwnPassword(current, next)");
  expect(SUBMIT.indexOf("newPasswordProblem(")).toBeGreaterThan(-1);
  expect(SUBMIT.indexOf("newPasswordProblem("), "checked before the request").toBeLessThan(
    SUBMIT.indexOf("changeOwnPassword(current, next)")
  );
});

test("the card and the gate import their rules from one module, and neither keeps a copy", () => {
  for (const [name, source] of [
    ["the card", CARD],
    ["the gate", GATE]
  ] as const) {
    expect(source, name).toMatch(/import \{[^}]*newPasswordProblem[^}]*\} from "@\/lib\/passwordChange"/);
    expect(source, name).toMatch(/import \{[^}]*currentPasswordRefused[^}]*\} from "@\/lib\/passwordChange"/);
    expect(source, `${name} carries no private mismatch sentence`).not.toContain("The two passwords do not match.");
  }
});

test("every box stops at the one ceiling and tells a password manager what it is", () => {
  expect(MAX_PASSWORD_LENGTH).toBe(200);
  expect((CARD.match(/maxLength=\{MAX_PASSWORD_LENGTH\}/g) ?? []).length, "current, new and repeat").toBe(3);
  expect((CARD.match(/autoComplete="current-password"/g) ?? []).length).toBe(1);
  expect((CARD.match(/autoComplete="new-password"/g) ?? []).length).toBe(2);
  // The account the new password belongs to, so a password manager updates the right entry.
  expect(CARD).toMatch(/<input type="text" name="username" autoComplete="username" value=\{user\.email\} readOnly hidden \/>/);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. A refusal, and a success
 * ──────────────────────────────────────────────────────────────────────────── */

test("a refused current password empties that box and keeps the other two", () => {
  expect(currentPasswordRefused(new ApiError(400, "Current password is incorrect", null))).toBe(true);
  expect(currentPasswordRefused(new ApiError(429, "Too many attempts", null))).toBe(false);
  const refused = /if \(currentPasswordRefused\(err\)\) \{[\s\S]*?\}/.exec(SUBMIT)?.[0] ?? "";
  expect(refused, "the refusal arm was located").toContain('setCurrent("")');
  expect(refused).not.toContain("setNext(");
  expect(refused).not.toContain("setConfirm(");
  expect(CARD).toMatch(/if \(currentRefusals > 0\) currentBox\.current\?\.focus\(\);/);
});

test("a success empties all three boxes and says so", () => {
  const succeeded = SUBMIT.slice(SUBMIT.indexOf("await changeOwnPassword(current, next);"), SUBMIT.indexOf("} catch (err) {"));
  for (const setter of ['setCurrent("")', 'setNext("")', 'setConfirm("")', "setChanged(CHANGED_HERE_ONLY)", "setChanged(CHANGED)"]) {
    expect(succeeded, setter).toContain(setter);
  }
  expect(CARD).toMatch(/const CHANGED_HERE_ONLY = "Password changed\./);
  expect(CARD).toMatch(/const CHANGED = "Password changed\./);
});

test("the receipt is said into a status region that exists before it has anything to say", () => {
  /*
    A region created together with its sentence announces nothing (this repository's own rule, argued
    above the stage page's save regions), and the card used to mount `<p role="status">` only once the
    change had worked — so a screen-reader user heard nothing, and could not tell success from a silent
    failure. One element each for the receipt and the refusal, always rendered, the class swapped to
    `sr-only` while empty; never `hidden`, which takes a region out of the tree.
  */
  expect(CARD).not.toMatch(/\{changed \? \(\s*<p role="status"/);
  expect(CARD).not.toMatch(/\{error \? \(\s*<div role="alert"/);
  expect(CARD).toMatch(/<p\s+role="status"\s+aria-live="polite"\s+className=\{changed \? "[^"]+" : "sr-only"\}\s*>\s*\{changed \?\? ""\}\s*<\/p>/);
  expect(CARD).toMatch(/<div\s+role="alert"\s+aria-live="assertive"\s+className=\{error \? "[^"]+" : "sr-only"\}\s*>\s*\{error \?\? ""\}\s*<\/div>/);
  expect(CARD).not.toMatch(/className=\{changed \? "[^"]+" : "hidden"\}/);
});

test("the session the change answers with is adopted before anything re-reads /me", () => {
  /*
    Since 2026-10-09 the server binds every session to the password it was opened with, so this change
    retires the token that sent it along with every other session — and answers with a fresh one, in
    its `X-Session-Token` header (the body stays `{"ok": true}`; `changeOwnPassword` reads the header
    and `password-change-enforcement-unit.spec.ts` drives it). The card adopts it, THEN re-reads the
    account; the other order is a re-read refused with the token the change just retired, and a tab
    signed out by its own success. With no header the card does exactly what it did before.
  */
  expect(SUBMIT).toContain("const fresh = await changeOwnPassword(current, next);");
  const adopted = SUBMIT.slice(SUBMIT.indexOf("if (fresh) {"), SUBMIT.indexOf("} else {"));
  expect(adopted, "the adoption arm was located").toContain("setToken(fresh);");
  expect(adopted.indexOf("setToken(fresh);"), "adopted first").toBeLessThan(adopted.indexOf("void refreshMe();"));
  const older = SUBMIT.slice(SUBMIT.indexOf("} else {"), SUBMIT.indexOf("} catch (err) {"));
  expect(older, "an older server: nothing changes hands").not.toContain("setToken(");
  expect(older).not.toContain("refreshMe(");
  expect(CARD).toMatch(/import \{ setToken \} from "@\/lib\/api";/);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. A data-screen card
 * ──────────────────────────────────────────────────────────────────────────── */

test("it is drawn with the data-screen recipes, not the auth stack", () => {
  // §11.2 of the frontend reference: a data screen is never standardised onto the auth `Button`.
  expect(CARD).not.toMatch(/from "@\/components\/ui\/button"/);
  expect(CARD).toContain('<section className="panel p-5">');
  expect((CARD.match(/className="field-input/g) ?? []).length).toBe(3);
  expect(CARD).toMatch(/<button type="submit" className="field-button"/);
  // The rule line under the boxes is the gate's, from the same function and the same constant — which
  // no longer says other devices stay signed in: a change now ends every session but the fresh one.
  expect(CARD).toContain("passwordRuleLine(PASSWORD_CHANGE_SESSIONS)");
  expect(GATE).toContain("passwordRuleLine(PASSWORD_CHANGE_SESSIONS)");
  expect(PASSWORD_CHANGE_SESSIONS).toMatch(/signed out everywhere else/);
  expect(CARD).not.toContain("Other devices stay signed in");
  expect(GATE).not.toContain('passwordRuleLine("Other devices stay signed in.")');
});
