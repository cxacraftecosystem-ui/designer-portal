import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { ApiError, apiFetch } from "../lib/api";
import { fetchStageRegistry } from "../lib/designWorkshops";

/**
 * THE ONE REQUEST IN THIS CLIENT THAT MAY COME OUT OF THE BROWSER'S HTTP CACHE, AND THE FENCE ROUND IT.
 *
 * `GET /design-workshops/schema` is the largest body this API serves to a cold client and the only
 * route in it that answers a conditional GET: `get_stage_schema` has sent
 * `ETag: W/"<sha256 of the emitted bytes>"` with `Cache-Control: private, max-age=0, must-revalidate`
 * since 2026-08-22, and `backend/tests/test_schema_conditional_get.py` pins all of it. **Neither
 * client collected the saving**, because `apiFetch` passed `cache: "no-store"` to every `fetch`,
 * which bypasses the HTTP cache entirely — no stored response means no `If-None-Match` means the
 * server's 304 branch was unreachable from a browser. Every cold tab re-downloaded the whole
 * registry to draw its first form. `docs/SCALABILITY.md` §9.1 recorded the gap in those words.
 *
 * WHAT IS BEING FENCED, and why this file exists rather than a comment.
 *
 * **THE OPT-IN MUST STAY A LIST OF ONE.** `no-store` everywhere else is not an oversight: this
 * client lists records people travelled to a village to collect, and a list served from a stale
 * store looks exactly like a place with no records — the silent-emptiness class this repository
 * keeps re-filing. A later agent reading "we cache the registry" and generalising it to the record
 * lists would reintroduce that with no test going red, so the last test below counts the opt-ins
 * across `lib/`, `app/` and `components/` and fails at two.
 *
 * **AND A CALLER MUST NOT BE ABLE TO REACH IT SIDEWAYS.** `cache` is a legal `RequestInit` key, so
 * `apiFetch(path, { cache: "force-cache" })` would have been a second, undocumented door into the
 * same behaviour if the spread had come last. It does not; a test here says so.
 *
 * **AND THE CHANGE MUST BE INVISIBLE TO THE CALLER.** A conditional GET is only safe here because
 * the browser performs the revalidation itself and materialises the stored response as an ordinary
 * 200 with its body — `fetch` never hands a 304 to JavaScript. The last two behavioural tests hold
 * both ends of that: the 200-shaped answer parses exactly as before, and a bare 304, if one ever
 * did arrive by some route nobody has thought of, is REFUSED loudly rather than parsed as an empty
 * registry. The second matters more than it looks: `""` is what `response.text()` returns for a
 * body-less response, and a version of this code that treated a 304 as success would hand every
 * form in the feature a registry with no fields in it and no error anywhere.
 *
 * WHY THE FIRST FOUR RUN THE REAL FUNCTIONS. `apiFetch` is a plain async function whose only
 * ambient dependency here is `fetch`, and `fetchStageRegistry` is a plain async function on top of
 * it — both stand up in Node, so these drive the ACTUAL code and read the `RequestInit` it built,
 * which is stronger than any grep. `public-page-401-unit.spec.ts` stubs the same global for the
 * same reason. Only the census at the end is a source read, because "how many callers opt in" is a
 * question about files rather than about one call.
 */

/** Every `RequestInit` `apiFetch` handed to `fetch`, in order, with the URL it went to. */
type Sent = { url: string; init: RequestInit };

function installFetch(respond: () => Response): Sent[] {
  const sent: Sent[] = [];
  (globalThis as Record<string, unknown>).fetch = async (url: string, init: RequestInit) => {
    sent.push({ url: String(url), init });
    return respond();
  };
  return sent;
}

/**
 * The shape of a registry, cut to what `fetchStageRegistry` actually reads — `version`, which is the
 * key its identity contract turns on. A real one is ~160 KB and nothing here depends on its size.
 */
const registryBody = (version: string) =>
  JSON.stringify({ version, stages: [], enums: {} });

const ok = (version: string) => () =>
  new Response(registryBody(version), {
    status: 200,
    headers: { "content-type": "application/json", etag: `W/"${version}"` }
  });

test.afterEach(() => {
  delete (globalThis as Record<string, unknown>).fetch;
});

test("the field registry is fetched in a mode that can revalidate — the whole point of the ETag", async () => {
  const sent = installFetch(ok("registry-a"));

  // `refresh` because the module-level cache would otherwise answer without a request at all; that
  // cache is the WITHIN-a-tab half and is not what this file is about.
  await fetchStageRegistry({ refresh: true });

  expect(sent).toHaveLength(1);
  expect(sent[0].url, "the registry path, and no other, is what may be revalidated").toContain(
    "/api/design-workshops/schema"
  );
  // `no-cache`, not `default`: the request itself demands a revalidation, so the guarantee survives
  // anything that widens the response's freshness — a proxy rewriting Cache-Control, or an edit to
  // `_SCHEMA_CACHE_CONTROL`. It still stores and still sends If-None-Match, which is the saving.
  expect(
    sent[0].init.cache,
    "no-store bypasses the HTTP cache, so the server's 304 branch is unreachable from a browser"
  ).toBe("no-cache");
});

test("every other request still refuses the store", async () => {
  const sent = installFetch(() => new Response("[]", { status: 200, headers: { "content-type": "application/json" } }));

  await apiFetch("/artisans?page=1");

  expect(sent).toHaveLength(1);
  expect(
    sent[0].init.cache,
    "a list served from a stale store is indistinguishable from a place with no records"
  ).toBe("no-store");
});

test("a caller cannot reach the browser cache sideways through RequestInit", async () => {
  const sent = installFetch(() => new Response("[]", { status: 200, headers: { "content-type": "application/json" } }));

  // `cache` is a legal RequestInit key. If the spread came after the literal, this would win.
  await apiFetch("/artisans?page=1", { cache: "force-cache" });

  expect(
    sent[0].init.cache,
    "the named option is the only door; keep `cache` set AFTER the spread in apiFetch"
  ).toBe("no-store");
});

test("a revalidated registry is an ordinary 200 to the caller, exactly as before", async () => {
  installFetch(ok("registry-b"));

  // This is what the browser hands back after a 304: the STORED response, materialised as a 200
  // carrying its body. Nothing in the client can tell it apart from a fresh download, which is the
  // property that made it safe to turn on.
  const registry = await fetchStageRegistry({ refresh: true });

  expect(registry.version).toBe("registry-b");
});

test("a bare 304 is refused loudly, never parsed as a registry with no fields in it", async () => {
  installFetch(() => new Response(null, { status: 304, headers: { etag: 'W/"registry-b"' } }));

  // Unreachable through the browser — it resolves the revalidation itself — but asserted anyway,
  // because the failure it forecloses is the worst one available here. `response.text()` on a
  // body-less response is `""`, and a version of `apiFetch` that read 304 as success would hand
  // every form in the feature an empty field list with no error raised anywhere.
  const failure = await fetchStageRegistry({ refresh: true }).catch((error: unknown) => error);

  expect(failure, "a 304 that somehow reached JS must throw, not resolve").toBeInstanceOf(ApiError);
  expect((failure as ApiError).status).toBe(304);
});

/* ────────────────────────────────────────────────────────────────────────────
 * The census: the opt-in is a list of one, and a grep is what keeps it one
 * ──────────────────────────────────────────────────────────────────────────── */

const ROOT = join(__dirname, "..");
const SEARCHED = ["lib", "app", "components"];

/**
 * Every .ts/.tsx file under the three source trees. `node_modules` is not inside any of them.
 *
 * `withFileTypes` rather than a `statSync` per entry: one syscall instead of two, and no window
 * between listing a name and stat-ing it in which another process can delete the file and turn this
 * test red for a reason that has nothing to do with what it asserts.
 */
function sources(dir: string, found: string[] = []): string[] {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) sources(full, found);
    else if (entry.name.endsWith(".ts") || entry.name.endsWith(".tsx")) found.push(full);
  }
  return found;
}

/**
 * An OPT-IN, not a mention. `revalidateFromHttpCache:` is how the option is passed and how it is
 * declared; the bare identifier also appears in prose in `lib/registryProvenance.ts`, which explains
 * why the `"network"` provenance state is still honest now that this path may be answered from the
 * browser's store. Counting mentions would make writing that explanation a test failure, which is
 * the wrong incentive — a reader who understands the option is exactly who should be citing it.
 */
const OPT_IN = /revalidateFromHttpCache\s*\??\s*:/;

test("exactly one call in the client opts into the browser cache, and it is the registry", () => {
  const opted: string[] = [];
  for (const tree of SEARCHED) {
    for (const file of sources(join(ROOT, tree))) {
      // The declaration in `lib/api.ts` is the option itself, not a caller of it.
      if (file.endsWith(join("lib", "api.ts"))) continue;
      if (OPT_IN.test(readFileSync(file, "utf8"))) opted.push(file);
    }
  }

  expect(
    opted.map((f) => f.slice(ROOT.length + 1)),
    "no-store on everything else is deliberate — read ApiFetchOptions.revalidateFromHttpCache before adding a second"
  ).toEqual([join("lib", "designWorkshops.ts")]);

  // And that it is on the registry call rather than on some other request in the same file.
  const source = readFileSync(join(ROOT, "lib", "designWorkshops.ts"), "utf8");
  const call = source.slice(source.indexOf('apiFetch<DwRegistry>("/design-workshops/schema"'));
  expect(call.slice(0, 200)).toContain("revalidateFromHttpCache: true");
});
