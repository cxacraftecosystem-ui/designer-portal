import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import {
  FIRST_PASSWORD_NOTE,
  PASSWORD_CHANGE_SESSIONS,
  canSetFirstPassword,
  currentPasswordRefused,
  hasOwnPassword,
  newPasswordProblem,
  providerConfirmationRequired
} from "@/lib/passwordChange";
import { CONFIRM_PROVIDER_HEADER, MAX_PASSWORD_LENGTH } from "@/lib/signIn";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const CARD = read("components", "settings", "ChangePasswordCard.tsx");
const SETTINGS = read("app", "(protected)", "settings", "page.tsx");
const GATE = read("components", "FirstPasswordGate.tsx");

const SUBMIT = (() => {
  const from = CARD.indexOf("async function submit(");
  return from < 0 ? "" : CARD.slice(from, CARD.indexOf("return (", from));
})();

test("it is offered to an account with a password of its own, and to no other", () => {
  expect(hasOwnPassword({ passwordSetAt: "2026-10-01T09:30:00.000Z" })).toBe(true);
  expect(hasOwnPassword({ passwordSetAt: null })).toBe(false);
  expect(hasOwnPassword({})).toBe(false);
  expect(hasOwnPassword(null)).toBe(false);
  expect(hasOwnPassword(undefined)).toBe(false);
});

test("the card decides between changing and setting a password after its hooks", () => {
  const gate = CARD.indexOf("if (!user) return null;");
  const mode = CARD.indexOf("const settingFirst = canSetFirstPassword(user);");
  expect(gate, "the gate was located").toBeGreaterThan(-1);
  expect(mode, "the mode was located").toBeGreaterThan(gate);
  const card = CARD.slice(0, CARD.indexOf("function ConfirmItsYou("));
  expect(gate).toBeGreaterThan(card.lastIndexOf("useEffect("));
  expect(gate).toBeGreaterThan(card.lastIndexOf("useState("));
});

test("an account with no password is offered SET a password, and the server's own answer wins", () => {
  expect(hasOwnPassword({ hasPassword: false, passwordSetAt: "2026-10-01T09:30:00.000Z" })).toBe(false);
  expect(hasOwnPassword({ hasPassword: true, passwordSetAt: null })).toBe(true);
  expect(canSetFirstPassword({ hasPassword: false })).toBe(true);
  expect(canSetFirstPassword({ passwordSetAt: null })).toBe(true);
  expect(canSetFirstPassword({ hasPassword: true })).toBe(false);
  expect(canSetFirstPassword(null)).toBe(false);
  expect(CARD).toContain('settingFirst ? "Set a password" : "Change password"');
  expect(CARD).toMatch(/\{settingFirst \? null : \(\s*<div>\s*<label htmlFor="settings-current-password"/);
  expect(SUBMIT).toContain("settingFirst && proof ? await setFirstPassword(next, proof, askCode ? code : undefined) : await changeOwnPassword(current, next);");
  expect(FIRST_PASSWORD_NOTE).toMatch(/or the way you sign in now/);
});

test("a first password needs a fresh provider sign-in, and a refused one is asked for again", () => {
  const named = new Headers({ [CONFIRM_PROVIDER_HEADER]: "MICROSOFT" });
  expect(providerConfirmationRequired(new ApiError(400, "Confirm it's you", null, named))).toBe("MICROSOFT");
  expect(providerConfirmationRequired(new ApiError(400, "Confirm it's you", null))).toBe(null);
  expect(providerConfirmationRequired(new ApiError(401, "x", null, named))).toBe(null);
  expect(providerConfirmationRequired(new ApiError(400, "x", null, new Headers({ [CONFIRM_PROVIDER_HEADER]: "ELSEWHERE" })))).toBe(null);
  expect(SUBMIT).toContain("if (settingFirst && !proof) {");
  expect(CARD).toContain("{settingFirst && !proof ? (");
  expect(SUBMIT).toContain("const confirmAgain = settingFirst ? providerConfirmationRequired(err) : null;");
  expect(SUBMIT).toMatch(/if \(confirmAgain\) \{\s*setProof\(null\);/);
  expect(CARD).not.toContain("Sign out and sign in again");
});

test("it sits on the account's own settings hub, with no role gate", () => {
  expect(SETTINGS).toMatch(/import \{ ChangePasswordCard \} from "@\/components\/settings\/ChangePasswordCard"/);
  expect(SETTINGS).toContain("<ChangePasswordCard />");
  const adminAt = SETTINGS.indexOf("{links.length ? (");
  expect(SETTINGS.indexOf("<ChangePasswordCard />")).toBeLessThan(adminAt);
  expect(SETTINGS).toMatch(/<UsageConsentCard \/>\s*<AadhaarVerifyPanel subject=\{\{ kind: "self" \}\} \/>\s*<GrievanceRedressalCard \/>/);
});

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
  expect(CARD).toMatch(/<input type="text" name="username" autoComplete="username" value=\{user\.email\} readOnly hidden \/>/);
});

test("a refused current password empties that box and keeps the other two", () => {
  expect(currentPasswordRefused(new ApiError(400, "Current password is incorrect", null))).toBe(true);
  expect(currentPasswordRefused(new ApiError(429, "Too many attempts", null))).toBe(false);
  const refused = /if \(!settingFirst && currentPasswordRefused\(err\)\) \{[\s\S]*?\}/.exec(SUBMIT)?.[0] ?? "";
  expect(refused, "the refusal arm was located").toContain('setCurrent("")');
  expect(refused).not.toContain("setNext(");
  expect(refused).not.toContain("setConfirm(");
  expect(CARD).toMatch(/if \(currentRefusals > 0\) currentBox\.current\?\.focus\(\);/);
});

test("a success empties all three boxes and says so", () => {
  const succeeded = SUBMIT.slice(SUBMIT.indexOf("await changeOwnPassword(current, next);"), SUBMIT.indexOf("} catch (err) {"));
  for (const setter of ['setCurrent("")', 'setNext("")', 'setConfirm("")', "PASSWORD_ADDED : CHANGED_HERE_ONLY)", "setChanged(CHANGED)"]) {
    expect(succeeded, setter).toContain(setter);
  }
  expect(CARD).toMatch(/const CHANGED_HERE_ONLY = "Password changed\./);
  expect(CARD).toMatch(/const CHANGED = "Password changed\./);
});

test("the receipt is said into a status region that exists before it has anything to say", () => {
  expect(CARD).not.toMatch(/\{changed \? \(\s*<p role="status"/);
  expect(CARD).not.toMatch(/\{error \? \(\s*<div role="alert"/);
  expect(CARD).toMatch(/<p\s+role="status"\s+aria-live="polite"\s+className=\{changed \? "[^"]+" : "sr-only"\}\s*>\s*\{changed \?\? ""\}\s*<\/p>/);
  expect(CARD).toMatch(/<div\s+role="alert"\s+aria-live="assertive"\s+className=\{error \? "[^"]+" : "sr-only"\}\s*>\s*\{error \?\? ""\}\s*<\/div>/);
  expect(CARD).not.toMatch(/className=\{changed \? "[^"]+" : "hidden"\}/);
});

test("the session the change answers with is in the cookie before anything re-reads /me", () => {
  expect(SUBMIT).toContain("settingFirst && proof ? await setFirstPassword(next, proof, askCode ? code : undefined) : await changeOwnPassword(current, next);");
  const renewed = SUBMIT.slice(SUBMIT.indexOf("if (fresh) {"), SUBMIT.indexOf("} else {"));
  expect(renewed, "the renewal arm was located").toContain("void refreshMe();");
  const older = SUBMIT.slice(SUBMIT.indexOf("} else {"), SUBMIT.indexOf("} catch (err) {"));
  expect(older).not.toContain("refreshMe(");
  expect(CARD, "no script touches the session token").not.toMatch(/setToken|getToken|localStorage/);
});

test("it is drawn with the data-screen recipes, not the auth stack", () => {
  expect(CARD).not.toMatch(/from "@\/components\/ui\/button"/);
  expect(CARD).toContain('<section ref={section} className="panel scroll-mt-28 p-5">');
  expect((CARD.match(/className="field-input/g) ?? []).length).toBe(3);
  expect(CARD).toContain("<TwoStepCodeEntry");
  expect(CARD).toMatch(/<button type="submit" className="field-button"/);
  expect(CARD).toContain("passwordRuleLine(settingFirst ? FIRST_PASSWORD_NOTE : PASSWORD_CHANGE_SESSIONS)");
  expect(GATE).toContain("passwordRuleLine(PASSWORD_CHANGE_SESSIONS)");
  expect(PASSWORD_CHANGE_SESSIONS).toMatch(/signed out everywhere else/);
  expect(CARD).not.toContain("Other devices stay signed in");
  expect(GATE).not.toContain('passwordRuleLine("Other devices stay signed in.")');
});
