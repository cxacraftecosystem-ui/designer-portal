import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  CHALLENGE_ENDED_DETAIL,
  codeKind,
  isTwoFactorChallenge,
  recoveryCodesFileText
} from "../lib/twoFactor";

test("six digits is an authenticator code, eight letters and digits a recovery code", () => {
  expect(codeKind("123456")).toBe("totp");
  expect(codeKind(" 123 456 ")).toBe("totp");
  expect(codeKind("ab2c-def3")).toBe("recovery");
  expect(codeKind("AB2CDEF3")).toBe("recovery");
  for (const neither of ["", "12345", "1234567", "ab2c-def", "ab2c-def3x", "ab2c_def3"]) {
    expect(codeKind(neither), neither).toBeNull();
  }
});

test("a challenge is told apart from a session", () => {
  expect(isTwoFactorChallenge({ two_factor_required: true, challenge_token: "t" })).toBe(true);
  expect(isTwoFactorChallenge({ accessToken: "a", user: {} })).toBe(false);
  expect(isTwoFactorChallenge({ two_factor_required: true })).toBe(false);
  expect(isTwoFactorChallenge(null)).toBe(false);
});

test("the finished-challenge sentence is the server's, word for word", () => {
  const service = readFileSync(join(__dirname, "..", "..", "backend", "app", "services", "two_factor.py"), "utf8");
  expect(service).toContain(`CHALLENGE_DEAD_DETAIL = "${CHALLENGE_ENDED_DETAIL}"`);
});

test("the recovery-code download holds every code, one per line", () => {
  const text = recoveryCodesFileText(["ab2c-def3", "gh4j-km5n"], "meera@example.org");
  expect(text.split("\n")).toEqual(expect.arrayContaining(["ab2c-def3", "gh4j-km5n", "meera@example.org"]));
});

test("every sign-in path on /login handles the challenge", () => {
  const page = readFileSync(join(__dirname, "..", "app", "login", "page.tsx"), "utf8");
  expect(page.match(/isTwoFactorChallenge\(outcome\)/g)?.length).toBe(3);
  expect(page).toContain("<TwoStepChallengeForm");
});
