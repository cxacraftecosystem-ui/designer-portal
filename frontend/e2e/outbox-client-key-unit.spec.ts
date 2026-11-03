import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { outboxMintsClientKey } from "@/lib/offline";

const OFFLINE_SOURCE = readFileSync(join(__dirname, "..", "lib", "offline.ts"), "utf8");

function functionBody(source: string, opener: string): string {
  const start = source.indexOf(opener);
  expect(start, `\`${opener}\` is still in lib/offline.ts`).toBeGreaterThan(-1);
  const rest = source.slice(start);
  const end = rest.indexOf("\n}");
  expect(end, `\`${opener}\` still closes at column 0`).toBeGreaterThan(-1);
  return rest.slice(0, end);
}

test("the four guarded create routes get a key", () => {
  for (const endpoint of ["/workshops", "/products", "/tools", "/processes"]) {
    expect(outboxMintsClientKey({ endpoint, method: "POST" }), endpoint).toBe(true);
  }
});

test("artisans and crafts do not, because they are already idempotent under a better key", () => {
  expect(outboxMintsClientKey({ endpoint: "/artisans", method: "POST" })).toBe(false);
  expect(outboxMintsClientKey({ endpoint: "/crafts", method: "POST" })).toBe(false);
});

test("a correction never gets one, whatever it is a correction to", () => {
  for (const endpoint of ["/workshops/w1", "/products/p1", "/tools/t1", "/processes/pr1"]) {
    expect(outboxMintsClientKey({ endpoint, method: "PATCH" }), endpoint).toBe(false);
  }
  expect(outboxMintsClientKey({ endpoint: "/products", method: "PATCH" })).toBe(false);
});

test("a create of something else — a rating, a questionnaire, a media-only entry — gets none", () => {
  for (const endpoint of ["/design-ratings", "/questionnaire/interviews", "/questionnaires", "/media/complete"]) {
    expect(outboxMintsClientKey({ endpoint, method: "POST" }), endpoint).toBe(false);
  }
});

test("a sub-path of a guarded route is not the guarded route", () => {
  expect(outboxMintsClientKey({ endpoint: "/tools/t1/artisans", method: "POST" })).toBe(false);
  expect(outboxMintsClientKey({ endpoint: "/workshops/access-requests", method: "POST" })).toBe(false);
  expect(outboxMintsClientKey({ endpoint: "/workshops/unmapped/map", method: "POST" })).toBe(false);
});

test("the key is minted at QUEUE time, in the one place every form passes through", () => {
  const queue = functionBody(OFFLINE_SOURCE, "export async function queueOffline(");
  expect(queue, "minted inside the object handed to store.add").toContain("clientKey: outboxMintsClientKey(entry)");
  expect(queue.indexOf("clientKey:"), "written with the row, not after it").toBeGreaterThan(
    queue.indexOf("store.add(")
  );
  const drain = OFFLINE_SOURCE.slice(OFFLINE_SOURCE.indexOf("export async function syncOutbox("));
  expect(drain, "the drain never mints a key").not.toContain("mintClientKey(");
});

test("a key a caller already set is respected rather than overwritten", () => {
  const queue = functionBody(OFFLINE_SOURCE, "export async function queueOffline(");
  expect(queue).toContain("entry.clientKey ?? mintClientKey()");
});

test("a browser with no crypto.randomUUID mints nothing rather than something guessable", () => {
  const mint = functionBody(OFFLINE_SOURCE, "function mintClientKey(");
  expect(mint).toContain("crypto.randomUUID");
  expect(mint, "no fallback generator").not.toContain("Math.random");
  expect(mint, "an unavailable API answers null").toContain("null");
});

test("the key travels beside the stored body and is merged in at replay", () => {
  const merge = functionBody(OFFLINE_SOURCE, "function bodyWithClearances(");
  expect(merge, "read off the entry, not off the stored body").toContain("entry.clientKey");
  expect(merge, "written into the request object").toContain("record.clientKey = clientKey");
  expect(merge).toContain('typeof entry.clientKey === "string" && entry.clientKey');
});

test("an entry with no key adds no key, so an older entry replays byte for byte", () => {
  const merge = functionBody(OFFLINE_SOURCE, "function bodyWithClearances(");
  expect(merge, "no clearances and no key means the stored body, untouched").toContain(
    "if (cleared.length === 0 && !clientKey) return entry.body;"
  );
  expect(merge, "and the write itself is guarded").toContain("if (clientKey) record.clientKey");
});

test("the entry type declares the field, so a stored key survives a round trip through the store", () => {
  expect(OFFLINE_SOURCE).toContain("clientKey?: string | null;");
  const bumped = /DB_VERSION\s*=\s*(\d+)/.exec(OFFLINE_SOURCE);
  expect(bumped, "DB_VERSION is still declared").not.toBeNull();
});
