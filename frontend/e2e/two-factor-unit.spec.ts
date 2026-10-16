import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  CHALLENGE_ENDED_DETAIL,
  codeKind,
  isTwoFactorChallenge,
  recoveryCodesFileText
} from "../lib/twoFactor";

/**
 * Two-step verification, the client's pure half.
 *
 * `codeKind` is the server's `two_factor.code_kind` and Android's `TwoFactorCode.kindOf` — the three
 * must agree character for character, because a recovery code typed by somebody who lost their phone
 * is the worst moment for one client to disagree. `CHALLENGE_ENDED_DETAIL` is matched EXACTLY by the
 * sign-in form, so it is read off the backend source rather than trusted. And the sign-in answer is a
 * union: `isTwoFactorChallenge` is what keeps a challenge from being stored as a session.
 */

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
  // The password, Google and Microsoft/Yahoo paths each branch on the answer before using it.
  expect(page.match(/isTwoFactorChallenge\(outcome\)/g)?.length).toBe(3);
  expect(page).toContain("<TwoStepChallengeForm");
});
