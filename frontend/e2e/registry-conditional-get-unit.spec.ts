import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { ApiError, apiFetch } from "../lib/api";
import { fetchStageRegistry } from "../lib/designWorkshops";

type Sent = { url: string; init: RequestInit };

function installFetch(respond: () => Response): Sent[] {
  const sent: Sent[] = [];
  (globalThis as Record<string, unknown>).fetch = async (url: string, init: RequestInit) => {
    sent.push({ url: String(url), init });
    return respond();
  };
  return sent;
}

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

  await fetchStageRegistry({ refresh: true });

  expect(sent).toHaveLength(1);
  expect(sent[0].url, "the registry path, and no other, is what may be revalidated").toContain(
    "/api/design-workshops/schema"
  );
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

  await apiFetch("/artisans?page=1", { cache: "force-cache" });

  expect(
    sent[0].init.cache,
    "the named option is the only door; keep `cache` set AFTER the spread in apiFetch"
  ).toBe("no-store");
});

test("a revalidated registry is an ordinary 200 to the caller, exactly as before", async () => {
  installFetch(ok("registry-b"));

  const registry = await fetchStageRegistry({ refresh: true });

  expect(registry.version).toBe("registry-b");
});

test("a bare 304 is refused loudly, never parsed as a registry with no fields in it", async () => {
  installFetch(() => new Response(null, { status: 304, headers: { etag: 'W/"registry-b"' } }));

  const failure = await fetchStageRegistry({ refresh: true }).catch((error: unknown) => error);

  expect(failure, "a 304 that somehow reached JS must throw, not resolve").toBeInstanceOf(ApiError);
  expect((failure as ApiError).status).toBe(304);
});

const ROOT = join(__dirname, "..");
const SEARCHED = ["lib", "app", "components"];

function sources(dir: string, found: string[] = []): string[] {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) sources(full, found);
    else if (entry.name.endsWith(".ts") || entry.name.endsWith(".tsx")) found.push(full);
  }
  return found;
}

const OPT_IN = /revalidateFromHttpCache\s*\??\s*:/;

test("exactly one call in the client opts into the browser cache, and it is the registry", () => {
  const opted: string[] = [];
  for (const tree of SEARCHED) {
    for (const file of sources(join(ROOT, tree))) {
      if (file.endsWith(join("lib", "api.ts"))) continue;
      if (OPT_IN.test(readFileSync(file, "utf8"))) opted.push(file);
    }
  }

  expect(
    opted.map((f) => f.slice(ROOT.length + 1)),
    "no-store on everything else is deliberate — read ApiFetchOptions.revalidateFromHttpCache before adding a second"
  ).toEqual([join("lib", "designWorkshops.ts")]);

  const source = readFileSync(join(ROOT, "lib", "designWorkshops.ts"), "utf8");
  const call = source.slice(source.indexOf('apiFetch<DwRegistry>("/design-workshops/schema"'));
  expect(call.slice(0, 200)).toContain("revalidateFromHttpCache: true");
});
