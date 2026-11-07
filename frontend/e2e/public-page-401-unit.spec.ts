import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { ApiError, apiFetch, getSessionMarker } from "../lib/api";

import { installSessionCookie, removeSessionCookie } from "./support/fakeSessionCookie";

type Recorded = { assign: string[]; replace: string[] };

function installFakeWindow(pathname: string, storedToken: string | null): Recorded {
  const recorded: Recorded = { assign: [], replace: [] };
  installSessionCookie(storedToken);

  (globalThis as Record<string, unknown>).window = {
    location: {
      pathname,
      protocol: "http:",
      assign: (url: string) => void recorded.assign.push(url),
      replace: (url: string) => void recorded.replace.push(url)
    }
  };
  return recorded;
}

function install401() {
  (globalThis as Record<string, unknown>).fetch = async () =>
    new Response(JSON.stringify({ detail: "Could not validate credentials" }), {
      status: 401,
      headers: { "content-type": "application/json" }
    });
}

const tokenNow = () => getSessionMarker();

test.afterEach(() => {
  delete (globalThis as Record<string, unknown>).window;
  removeSessionCookie();
});

test("a 401 on the public landing page clears the token and navigates nowhere", async () => {
  const recorded = installFakeWindow("/", "stale-token-from-six-weeks-ago");
  install401();

  await expect(apiFetch("/me", {}, { redirectOn401: false })).rejects.toBeInstanceOf(ApiError);

  expect(tokenNow(), "the proven-dead token must not survive").toBeNull();
  expect(recorded.assign, "no history-pushing navigation off a public page").toEqual([]);
  expect(recorded.replace, "no navigation off a public page at all").toEqual([]);
});

test("a 401 on a signed-in screen still lands on /login, without leaving the dead page in history", async () => {
  const recorded = installFakeWindow("/products", "stale-token-from-six-weeks-ago");
  install401();

  await expect(apiFetch("/products?page=1")).rejects.toBeInstanceOf(ApiError);

  expect(tokenNow()).toBeNull();
  expect(recorded.replace, "the redirect is not gone, only narrowed").toEqual(["/session-ended?next=%2Fproducts"]);
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
