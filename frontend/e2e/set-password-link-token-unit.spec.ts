import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { API_BASE } from "@/lib/api";
import { checkPasswordLink, takeLinkTokenFromAddress } from "@/lib/signIn";

/**
 * A SET-PASSWORD LINK'S TOKEN STAYS OFF EVERY REQUEST LINE THE WEB SENDS, AND OUT OF THE ADDRESS BAR.
 *
 * ── THE FINDING ─────────────────────────────────────────────────────────────────────────────────
 *
 * The token is the link's whole authority. The web checked a link with
 * `GET /api/auth/set-password?token=…`, so the token rode the request line, which nginx and any CDN in
 * front of the API write to their access logs; and the page left `?token=…` in the address bar, where
 * a copied address, a bookmark or Back would carry it on (docs/OPEN_FINDINGS.md, docs/SECURITY.md §3.5).
 *
 * ── WHAT IS PINNED ──────────────────────────────────────────────────────────────────────────────
 *
 *   1. the token is read from the fragment (`#token=`) first and the query (`?token=`) second, and the
 *      address the page puts back has neither, with everything else kept;
 *   2. the check is `POST /api/auth/set-password/check` with the token in the body, and the old GET is
 *      asked ONLY when that answers 404 or 405 — an API older than the POST;
 *   3. the page takes the token out of the address before it checks the link, without
 *      `useSearchParams` (which cannot see a fragment), and the server really serves the POST it calls.
 *
 * `takeLinkTokenFromAddress` and `checkPasswordLink` are driven for real: the first is pure, and the
 * second needs only `fetch`, which stands up in Node (the approach `password-change-enforcement-unit`
 * takes). The page is a source read, for this repository's usual reason: no React renderer.
 * ⚠ LINE-ENDING AGNOSTIC: `\s` and `[\s\S]` throughout.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");
const PAGE = read("app", "set-password", "page.tsx");
const AUTH_ROUTES = readFileSync(
  join(__dirname, "..", "..", "backend", "app", "api", "routes", "auth.py"),
  "utf8"
);

/** Shaped like `credential_links.mint_token`'s output, and distinctive enough to search for. */
const TOKEN = "eyJzdWIiOiJhY2N0LTA0NTEifQ.Link-Token-Signature-0451";
const SITE = "https://designer-repository.example";

test.describe("reading the token out of the address", () => {
  const cases: Array<[string, string, string, string | null]> = [
    ["the fragment", `${SITE}/set-password#token=${TOKEN}`, TOKEN, "/set-password"],
    ["the query, as every link issued so far has it", `${SITE}/set-password?token=${TOKEN}`, TOKEN, "/set-password"],
    ["the fragment over the query", `${SITE}/set-password?token=from-the-query#token=${TOKEN}`, TOKEN, "/set-password"],
    ["the query when the fragment's token is empty", `${SITE}/set-password?token=${TOKEN}#token=`, TOKEN, "/set-password"],
    [
      "everything else in the address, kept",
      `${SITE}/set-password?from=mail&token=${TOKEN}#token=other&step=2`,
      "other",
      "/set-password?from=mail#step=2"
    ],
    ["a fragment that held no token, kept as it was", `${SITE}/set-password?token=${TOKEN}#section`, TOKEN, "/set-password#section"],
    ["a percent-encoded token, decoded", `${SITE}/set-password?token=a%2Eb`, "a.b", "/set-password"],
    ["no token at all: nothing to read and nothing to rewrite", `${SITE}/set-password`, "", null],
    ["a fragment with no token in it either", `${SITE}/set-password#step=2`, "", null]
  ];
  for (const [what, href, token, address] of cases) {
    test(what, () => {
      expect(takeLinkTokenFromAddress(href)).toEqual({ token, address });
    });
  }

  test("the address put back never carries the token", () => {
    for (const [, href] of cases) {
      const { address } = takeLinkTokenFromAddress(href);
      expect(address ?? "").not.toContain(TOKEN);
      expect(address ?? "").not.toMatch(/[?#&]token=/);
    }
  });
});

test.describe("checking the link", () => {
  type Sent = { url: string; method: string; body: string | null };
  const realFetch = globalThis.fetch;
  let sent: Sent[] = [];

  /** Answer each request in turn with the next status; a 200 carries `verdict`. */
  function answer(statuses: number[], verdict: object = { valid: true, reason: null, purpose: "RESET" }) {
    sent = [];
    const queue = [...statuses];
    (globalThis as Record<string, unknown>).fetch = async (url: string, init: RequestInit = {}) => {
      sent.push({ url: String(url), method: init.method ?? "GET", body: (init.body as string) ?? null });
      const status = queue.shift() ?? 500;
      const body = status === 200 ? verdict : { detail: status === 404 ? "Not Found" : "Refused" };
      return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
    };
  }

  test.afterEach(() => {
    (globalThis as Record<string, unknown>).fetch = realFetch;
  });

  test("the token goes in a POST body, and no address carries it", async () => {
    const verdict = { valid: false, reason: "expired", purpose: null };
    answer([200], verdict);
    await expect(checkPasswordLink(TOKEN)).resolves.toEqual(verdict);
    expect(sent).toEqual([
      { url: `${API_BASE}/api/auth/set-password/check`, method: "POST", body: JSON.stringify({ token: TOKEN }) }
    ]);
  });

  for (const status of [404, 405]) {
    test(`an API older than the POST (${status}) is asked the old way, once`, async () => {
      const verdict = { valid: true, reason: null, purpose: "INVITE" };
      answer([status, 200], verdict);
      await expect(checkPasswordLink(TOKEN)).resolves.toEqual(verdict);
      expect(sent.map((request) => request.method)).toEqual(["POST", "GET"]);
      expect(sent[1].url).toBe(`${API_BASE}/api/auth/set-password?token=${encodeURIComponent(TOKEN)}`);
      expect(sent[1].body).toBeNull();
    });
  }

  for (const status of [400, 401, 403, 422, 429, 500, 502, 503]) {
    test(`a ${status} is the POST's answer, never a reason to put the token on a request line`, async () => {
      answer([status, 200]);
      await expect(checkPasswordLink(TOKEN)).rejects.toMatchObject({ status });
      expect(sent.map((request) => request.method)).toEqual(["POST"]);
    });
  }

  test("an unreachable API is not retried the old way either", async () => {
    sent = [];
    (globalThis as Record<string, unknown>).fetch = async (url: string, init: RequestInit = {}) => {
      sent.push({ url: String(url), method: init.method ?? "GET", body: (init.body as string) ?? null });
      throw new TypeError("Failed to fetch");
    };
    await expect(checkPasswordLink(TOKEN)).rejects.toThrow("Failed to fetch");
    expect(sent.map((request) => request.method)).toEqual(["POST"]);
  });
});

test.describe("the page", () => {
  test("reads the address itself, because useSearchParams cannot see a fragment", () => {
    // Asserted on the import and the call, not the word: the page's own header explains why the hook
    // is gone, and a bare match would read that explanation as the hook.
    expect(PAGE).not.toMatch(/import\s*\{[^}]*\buseSearchParams\b[^}]*\}\s*from\s*"next\/navigation"/);
    expect(PAGE).not.toMatch(/\buseSearchParams\(\)/);
    expect(PAGE).toMatch(/takeLinkTokenFromAddress\(window\.location\.href\)/);
  });

  test("takes the token out of the address before it checks the link", () => {
    const taken = PAGE.indexOf("takeLinkTokenFromAddress(window.location.href)");
    const scrubbed = PAGE.search(/window\.history\.replaceState\(null,\s*"",\s*address\)/);
    const checked = PAGE.indexOf("checkPasswordLink(token)");
    expect(taken, "the address is read").toBeGreaterThan(0);
    expect(scrubbed, "and rewritten").toBeGreaterThan(taken);
    expect(checked, "before the link is checked").toBeGreaterThan(scrubbed);
  });

  test("rewrites it through the router's own replaceState, one microtask after the effect", () => {
    // Next patches `history.replaceState` in an effect of its own, which runs AFTER this page's: a
    // call made inside the effect reaches the browser's method, wipes the router's state off the entry
    // and leaves the router to write the token back. `null` lets the patched method carry it over.
    expect(PAGE).toMatch(/queueMicrotask\(\(\)\s*=>\s*window\.history\.replaceState\(null,\s*"",\s*address\)\)/);
  });

  test("keeps the first token it read when Strict Mode runs the effect again", () => {
    // The second run reads the address the first has just cleaned; overwriting would read as "missing".
    expect(PAGE).toMatch(/setToken\(\(previous\)\s*=>\s*previous\s*\|\|\s*taken\.token\)/);
  });

  test("the API serves the POST the page calls, beside the GET the handsets still call", () => {
    expect(AUTH_ROUTES).toMatch(/@router\.post\("\/set-password\/check"\)/);
    expect(AUTH_ROUTES).toMatch(/@router\.get\("\/set-password"\)/);
  });
});
