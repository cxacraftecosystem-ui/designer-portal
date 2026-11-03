import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { MAX_PASSWORD_LENGTH } from "@/lib/signIn";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const SET_PASSWORD = read("app", "set-password", "page.tsx");
const AUTH = read("components", "AuthProvider.tsx");

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
  expect(cleared).toBeLessThan(done);
  expect(SET_PASSWORD).toMatch(/const \{ clearSession \} = useAuth\(\);/);
});

test("a refused redemption leaves the session exactly as it was", () => {
  const refused = SUBMIT.slice(SUBMIT.indexOf("} catch (err) {"), SUBMIT.indexOf("} finally {"));
  expect(refused, "the catch was located").toContain("setError(");
  expect(refused).not.toContain("clearSession");
});

test("the session is dropped locally, never through a logout", () => {
  expect(SET_PASSWORD).not.toMatch(/const \{[^}]*\blogout\b[^}]*\} = useAuth\(\)/);
  expect(SET_PASSWORD).not.toMatch(/\blogout\(\)\s*[.;]/);
  const clear = AUTH.slice(AUTH.indexOf("const clearSession = useCallback("));
  const body = clear.slice(0, clear.indexOf("}, [adopt]);"));
  expect(body, "clearSession was located").toContain("endSession();");
  expect(body).toContain("adopt(null);");
  expect(body, "and it asks the server nothing").not.toContain("apiFetch");
});

test("the way out still goes to the sign-in form, which now opens on its form", () => {
  const done = SET_PASSWORD.slice(SET_PASSWORD.indexOf("if (done) {"), SET_PASSWORD.indexOf("if (linkValid === null) {"));
  expect(done).toContain('router.replace("/login")');
  expect(done).toContain("Go to sign in");
});

test("both boxes stop at the server's one ceiling", () => {
  expect(MAX_PASSWORD_LENGTH).toBe(200);
  expect((SET_PASSWORD.match(/maxLength=\{MAX_PASSWORD_LENGTH\}/g) ?? []).length).toBe(2);
});
