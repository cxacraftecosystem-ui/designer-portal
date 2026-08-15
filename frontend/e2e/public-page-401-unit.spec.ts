import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { ApiError, apiFetch } from "../lib/api";

/**
 * THE EXPIRED TOKEN THAT THROWS A VISITOR OFF THE PUBLIC LANDING PAGE.
 *
 * `apiFetch`'s 401 handler fired on one condition — "a token was sent" — and hard-navigated the
 * browser to /login. The comment beside it reasoned that public pages were safe because "anonymous
 * requests sent no token", which is true only of a visitor with NOTHING in localStorage.
 * `AuthProvider` is mounted in the ROOT layout, above `app/page.tsx` as well as the protected tree,
 * and probes /me on every page load; a designer returning after her session expired therefore sent a
 * dead token from the marketing home page, got a 401, and was thrown to a sign-in form she never
 * asked for. `window.location.assign` also pushed a history entry, so Back bounced her onto the page
 * again and looked broken. Audit 2026-08-15 (MINOR, frontend).
 *
 * WHY THIS RUNS THE REAL FUNCTION RATHER THAN READING THE SOURCE. The behaviour is entirely inside
 * `apiFetch`, which is a plain async function with two ambient dependencies — `window` and `fetch` —
 * and both can be stood up in Node. So the first two tests below drive the ACTUAL 401 branch and
 * assert on what it did to a fake `location`, which is stronger than any grep. The third is a source
 * read, because `AuthProvider` is a React component and this repository has no React renderer in its
 * devDependencies (Playwright is the whole of it) — the same reason
 * `questionnaire-workshop-filter-unit.spec.ts` reads its subject.
 *
 * Both behavioural tests fail against the old code: the opt-out did not exist, and the navigation
 * was `assign`, not `replace`.
 */

type Recorded = { assign: string[]; replace: string[] };

/**
 * A window just real enough for `apiFetch`: the token store it reads and clears, and the location it
 * inspects and navigates.
 *
 * `protocol: "http:"` matters. `assertApiConfigured()` throws `ApiUnconfiguredError` when a LOOPBACK
 * API base is used from an https:// page, and `NEXT_PUBLIC_API_URL` is normally unset in a test
 * process, so the base falls back to http://localhost:8000. An https fake window would make every
 * test here fail on a 503 raised before the request, which has nothing to do with what is under
 * test.
 */
function installFakeWindow(pathname: string, storedToken: string | null): Recorded {
  const recorded: Recorded = { assign: [], replace: [] };
  const store = new Map<string, string>();
  if (storedToken) store.set("field_repo_token", storedToken);

  (globalThis as Record<string, unknown>).window = {
    localStorage: {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => void store.set(key, value),
      removeItem: (key: string) => void store.delete(key)
    },
    location: {
      pathname,
      protocol: "http:",
      assign: (url: string) => void recorded.assign.push(url),
      replace: (url: string) => void recorded.replace.push(url)
    }
  };
  return recorded;
}

/** What the server says to a token it no longer honours. */
function install401() {
  (globalThis as Record<string, unknown>).fetch = async () =>
    new Response(JSON.stringify({ detail: "Could not validate credentials" }), {
      status: 401,
      headers: { "content-type": "application/json" }
    });
}

const tokenNow = () =>
  ((globalThis as Record<string, unknown>).window as { localStorage: { getItem(k: string): string | null } })
    .localStorage.getItem("field_repo_token");

test.afterEach(() => {
  delete (globalThis as Record<string, unknown>).window;
});

test("a 401 on the public landing page clears the token and navigates nowhere", async () => {
  const recorded = installFakeWindow("/", "stale-token-from-six-weeks-ago");
  install401();

  // Exactly the call `AuthProvider.refreshMe` makes.
  await expect(apiFetch("/me", {}, { redirectOn401: false })).rejects.toBeInstanceOf(ApiError);

  // The token is dead whichever page we are on, and dropping it is what makes the second visit work.
  expect(tokenNow(), "the proven-dead token must not survive").toBeNull();
  // The whole finding, in two assertions: the visitor stays on the page they were reading.
  expect(recorded.assign, "no history-pushing navigation off a public page").toEqual([]);
  expect(recorded.replace, "no navigation off a public page at all").toEqual([]);
});

test("a 401 on a signed-in screen still lands on /login, without leaving the dead page in history", async () => {
  const recorded = installFakeWindow("/products", "stale-token-from-six-weeks-ago");
  install401();

  // No options: an ordinary call from a protected page, which is every other caller in the app.
  await expect(apiFetch("/products?page=1")).rejects.toBeInstanceOf(ApiError);

  expect(tokenNow()).toBeNull();
  expect(recorded.replace, "the redirect is not gone, only narrowed").toEqual(["/login"]);
  // `assign` pushed a history entry, so Back returned to the expired page and bounced again. That
  // half of the finding is closed by using `replace`, and this assertion is what keeps it closed.
  expect(recorded.assign, "assign() puts the expired page back in history — use replace()").toEqual([]);
});

test("AuthProvider's /me probe opts out of the redirect", () => {
  const source = readFileSync(join(__dirname, "..", "components", "AuthProvider.tsx"), "utf8");
  const probe = source.slice(source.indexOf('apiFetch<User>("/me"'));

  expect(
    probe.slice(0, 80),
    "the root-layout probe runs on public pages; it must not be able to navigate away from one"
  ).toContain("redirectOn401: false");
});
