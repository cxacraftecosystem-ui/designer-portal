import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { MAX_PASSWORD_LENGTH } from "@/lib/signIn";

/**
 * AFTER A LINK IS REDEEMED, THIS TAB'S SESSION IS DROPPED BEFORE ANYTHING ELSE IS OFFERED.
 *
 * ── THE DEFECT ──────────────────────────────────────────────────────────────────────────────────
 *
 * Redeeming a password link stamps `sessionsValidFrom`, which revokes every earlier token, and
 * clears `mustChangePassword` (`POST /auth/set-password`). `/set-password` then did nothing but
 * `router.replace("/login")`. `AuthProvider` sits in the ROOT layout and was still holding the
 * account it read when the page loaded, and the revoked token was still in storage. So a person who
 * had been locked at the password gate — the very person a link is issued to — pressed "Go to sign
 * in" and was shown the gate again, asking for a current password; the password they had just set
 * was then refused with "This session is no longer valid. Sign in again." Only "Sign out instead" got
 * them out. An unflagged session instead bounced to /dashboard and then hard-reloaded on its first
 * 401.
 *
 * ── WHAT IS PINNED ──────────────────────────────────────────────────────────────────────────────
 *
 *   1. the local session is cleared after a SUCCESSFUL redemption, and before the confirmation that
 *      offers the way out is drawn;
 *   2. a FAILED redemption leaves the session alone — nothing has been revoked;
 *   3. it is cleared LOCALLY: `clearSession`, never `logout()` — the link may be somebody else's, and
 *      there is nothing on the server to end.
 *
 * Source reads, for this repository's usual reason: there is no React renderer in devDependencies.
 * ⚠ LINE-ENDING AGNOSTIC: `\s` and `[\s\S]` throughout.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const SET_PASSWORD = read("app", "set-password", "page.tsx");
const AUTH = read("components", "AuthProvider.tsx");

/** The form's submit callback, from its declaration to its dependency list. */
const SUBMIT = (() => {
  const from = SET_PASSWORD.indexOf("const submit = useCallback(");
  return from < 0 ? "" : SET_PASSWORD.slice(from, SET_PASSWORD.indexOf("if (done) {", from));
})();

test("a successful redemption clears the session before the way out is drawn", () => {
  expect(SUBMIT, "the submit was located").toContain("await setPasswordWithLink(token, password);");
  const redeemed = SUBMIT.indexOf("await setPasswordWithLink(token, password);");
  const cleared = SUBMIT.indexOf("clearSession();");
  const done = SUBMIT.indexOf("setDone(true);");
  expect(cleared, "the session is cleared").toBeGreaterThan(redeemed);
  // BEFORE `setDone`: the confirmation is the screen with the way out on it, and no path from it —
  // the button, the address bar, Back — may reach a page still holding the revoked account.
  expect(cleared).toBeLessThan(done);
  expect(SET_PASSWORD).toMatch(/const \{ clearSession \} = useAuth\(\);/);
});

test("a refused redemption leaves the session exactly as it was", () => {
  // Nothing was revoked: an expired, withdrawn or already-used link changes nothing on the server.
  const refused = SUBMIT.slice(SUBMIT.indexOf("} catch (err) {"), SUBMIT.indexOf("} finally {"));
  expect(refused, "the catch was located").toContain("setError(");
  expect(refused).not.toContain("clearSession");
});

test("the session is dropped locally, never through a logout", () => {
  // The link may be somebody else's — an administrator checking one before sending it — and then the
  // session in this tab is alive and simply not the one the next sign-in is for. `POST /auth/logout`
  // keeps no server state, so it would only be a request refused for a token that no longer counts.
  // Asserted on what the page TAKES from the provider, not on the word: the page's own comment says
  // "never `logout()`", and a bare match would read that argument as the defect it warns about.
  expect(SET_PASSWORD).not.toMatch(/const \{[^}]*\blogout\b[^}]*\} = useAuth\(\)/);
  expect(SET_PASSWORD).not.toMatch(/\blogout\(\)\s*[.;]/);
  const clear = AUTH.slice(AUTH.indexOf("const clearSession = useCallback("));
  const body = clear.slice(0, clear.indexOf("}, [adopt]);"));
  expect(body, "clearSession was located").toContain("setToken(null);");
  expect(body).toContain("adopt(null);");
  expect(body, "and it asks the server nothing").not.toContain("apiFetch");
});

test("the way out still goes to the sign-in form, which now opens on its form", () => {
  const done = SET_PASSWORD.slice(SET_PASSWORD.indexOf("if (done) {"), SET_PASSWORD.indexOf("if (linkValid === null) {"));
  expect(done).toContain('router.replace("/login")');
  expect(done).toContain("Go to sign in");
});

test("both boxes stop at the server's one ceiling", () => {
  // `SetPasswordRequest.password` refuses past 200; a box that let somebody type 210 characters twice
  // would meet that 422 only after the second.
  expect(MAX_PASSWORD_LENGTH).toBe(200);
  expect((SET_PASSWORD.match(/maxLength=\{MAX_PASSWORD_LENGTH\}/g) ?? []).length).toBe(2);
});
