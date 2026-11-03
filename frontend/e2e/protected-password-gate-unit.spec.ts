import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { mustChangePassword } from "@/lib/signIn";
import type { User } from "@/lib/types";

const at = (...parts: string[]) => join(__dirname, "..", ...parts);
const read = (...parts: string[]) => readFileSync(at(...parts), "utf8");

const APP_SHELL = read("components", "AppShell.tsx");
const GATE = read("components", "FirstPasswordGate.tsx");
const LOGIN = read("app", "login", "page.tsx");
const LAYOUT = read("app", "(protected)", "layout.tsx");
const SET_PASSWORD = read("app", "set-password", "page.tsx");
const AUTH = read("components", "AuthProvider.tsx");

const gateAt = APP_SHELL.indexOf("if (mustChangePassword(user)) {");

test("the protected chrome reads the flag", () => {
  expect(APP_SHELL).toMatch(/import \{ mustChangePassword \} from "@\/lib\/signIn"/);
  expect(gateAt, "the gate branch was located").toBeGreaterThan(-1);
});

test("it reads the flag through the shared function and never off the field", () => {
  expect(APP_SHELL).not.toMatch(/user\.mustChangePassword/);
});

test("it is route-independent, so every protected page enforces it", () => {
  const branch = APP_SHELL.slice(gateAt, APP_SHELL.indexOf("const guard = routeGuardFor(pathname);", gateAt));
  expect(branch).not.toContain("pathname");
});

test("the gate is an early return, not a branch inside the page frame", () => {
  const branch = APP_SHELL.slice(gateAt, gateAt + 700);
  expect(branch).toContain("return (");
  expect(branch).toContain("<FirstPasswordLocked");
});

test("and the surface it returns carries no app chrome at all", () => {
  const from = APP_SHELL.indexOf("function FirstPasswordLocked(");
  expect(from, "the surface was located").toBeGreaterThan(-1);
  const surface = APP_SHELL.slice(from, APP_SHELL.indexOf("function RouteLocked(", from));
  expect(surface).not.toContain("DynamicIslandNav");
  expect(surface).not.toContain("children");
  expect(surface).not.toContain("motion.main");
  expect(surface, "and it does render the shared form").toContain("<FirstPasswordGate");
});

test("above the route guards and above admin view, not beside them", () => {
  const guardAt = APP_SHELL.indexOf("const guard = routeGuardFor(pathname);");
  const adminAt = APP_SHELL.indexOf("const chromeSettling =");
  expect(guardAt, "the route guard decision was located").toBeGreaterThan(-1);
  expect(adminAt, "the admin-view decision was located").toBeGreaterThan(-1);
  expect(gateAt).toBeLessThan(guardAt);
  expect(gateAt).toBeLessThan(adminAt);
});

test("nothing is decided while /me is still in flight", () => {
  const loadingAt = APP_SHELL.indexOf("if (loading) {");
  expect(loadingAt, "the loading frame was located").toBeGreaterThan(-1);
  expect(loadingAt).toBeLessThan(gateAt);
});

test("and not for a visitor who is not signed in at all", () => {
  const signedOutAt = APP_SHELL.indexOf("if (!user) return null;");
  expect(signedOutAt, "the signed-out return was located").toBeGreaterThan(-1);
  expect(signedOutAt).toBeLessThan(gateAt);
  expect(mustChangePassword(null)).toBe(false);
  expect(mustChangePassword({ id: "u1", role: "DESIGNER" } as User)).toBe(false);
});

test("the form is a component both screens import, not a copy in each", () => {
  expect(GATE).toMatch(/export function FirstPasswordGate\(/);
  expect(LOGIN).toMatch(/import \{ FirstPasswordGate \} from "@\/components\/FirstPasswordGate"/);
  expect(APP_SHELL).toMatch(/import \{ FirstPasswordGate \} from "@\/components\/FirstPasswordGate"/);
  expect(LOGIN, "and no local declaration is left behind").not.toMatch(/function FirstPasswordGate\(/);
});

test("the protected host mints no password vocabulary of its own", () => {
  const imports = /import \{[^}]*\} from "@\/lib\/signIn";/.exec(APP_SHELL)?.[0] ?? "";
  expect(imports, "the signIn import was located").toContain("mustChangePassword");
  expect(imports, "and nothing else from it").not.toContain("changeOwnPassword");
  expect(imports).not.toContain("MIN_PASSWORD_LENGTH");
  expect(imports).not.toContain("passwordRuleLine");
  expect(APP_SHELL, "the host renders no password boxes of its own").not.toContain('type="password"');
});

test("the protected host asks for the current password, because it never saw one", () => {
  expect(APP_SHELL).toMatch(/currentPassword=""/);
  expect(GATE).toMatch(/const askCurrent = currentPassword\.length === 0 \|\| currentRefusals > 0;/);
});

test("the link-redemption screen is outside the protected tree", () => {
  expect(existsSync(at("app", "set-password", "page.tsx")), "/set-password is public").toBe(true);
  expect(existsSync(at("app", "(protected)", "set-password")), "and is not under the gate").toBe(false);
  expect(SET_PASSWORD).not.toMatch(/import \{[^}]*AppShell[^}]*\}/);
  expect(LAYOUT, "which is mounted by the protected layout and nowhere else").toContain("<AppShell>");
});

test("the gate carries a way out that is not a way in", () => {
  const escape = /onSignOut=\{\(\) => \{[\s\S]*?\}\}/.exec(APP_SHELL.slice(gateAt))?.[0] ?? "";
  expect(escape, "the escape was located").toContain("logout()");
  expect(escape, "and it does not navigate into the app").not.toContain("/dashboard");
});

test("a completed change closes the gate even if the /me that would prove it never lands", () => {
  expect(APP_SHELL.slice(gateAt, gateAt + 600)).toContain("onDone={markPasswordChanged}");
  expect(LOGIN).toContain("markPasswordChanged();");
  const latch = AUTH.slice(AUTH.indexOf("const markPasswordChanged = useCallback("));
  const body = latch.slice(0, latch.indexOf("}, [refreshMe]);"));
  expect(body.indexOf("mustChangePassword: false"), "the account is patched first").toBeGreaterThan(-1);
  expect(body.indexOf("mustChangePassword: false")).toBeLessThan(body.indexOf("void refreshMe();"));
});

test("there is one latch, and it is not a copy of the flag", () => {
  expect(APP_SHELL, "no host keeps a latch of its own").not.toMatch(/setPasswordSet|const \[passwordSet,/);
  expect(LOGIN).not.toMatch(/setPasswordSet|const \[passwordSet,/);
  expect(APP_SHELL.slice(gateAt, gateAt + 40)).toContain("if (mustChangePassword(user)) {");
  expect(AUTH).toMatch(/adopt\(probe <= changedAtProbe\.current \? \{ \.\.\.me, mustChangePassword: false \} : me\);/);
});

test("a refused current password does not cost the protected host its session", () => {
  expect(GATE).toContain("if (currentPasswordRefused(err)) {");
  expect(GATE).toMatch(/import \{[^}]*currentPasswordRefused[^}]*\} from "@\/lib\/passwordChange"/);
});
