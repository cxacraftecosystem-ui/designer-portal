import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { outboxMintsClientKey } from "@/lib/offline";

/**
 * THE IDEMPOTENCY KEY THE OUTBOX MINTS, PINNED AT EVERY DECISION IT MAKES.
 *
 * ── WHAT THIS DEFENDS ──────────────────────────────────────────────────────────────────────────
 *
 * A queued create is POSTed, the server writes the row, and the answer dies on the way back — a
 * tunnel, a captive portal, the tab closed mid-flight. This browser learned nothing, so the entry is
 * still in IndexedDB and the next pass sends the identical body: a SECOND government record for one
 * save, under one researcher's name, in an index nobody reconciles.
 *
 * `entryAlreadyCreated` cannot see it. That guard reads `created`/`createdId`, and both are records
 * of a REPLY — they close the case where an answer arrived and this browser wrote it down, and are
 * structurally blind to the case where none ever came. `persistProgress` says so in the file itself,
 * and the sentence is the specification for the field these tests are about:
 *
 *     "a few milliseconds of IndexedDB is as small as that window gets without idempotency keys on
 *      the API."
 *
 * ── THE FOUR WAYS THIS CAN BE GOT WRONG, WHICH IS WHY EACH HAS A TEST ─────────────────────────
 *
 *   1. A KEY SENT TO A ROUTE THAT DOES NOT DECLARE ONE. Every request body on the API is an
 *      `APIModel` with `extra="forbid"`, so `clientKey` posted to `/artisans` is a 422 on the whole
 *      save — and `saveOrQueue` does not re-queue a 4xx, so the record and its photographs are gone.
 *      The list is therefore a decision, not an optimisation.
 *   2. A KEY ON A CORRECTION. The four UPDATE schemas do not declare it either, and a 422 carrying
 *      `extra_forbidden` is read by this queue as a disagreement between BUILDS — re-attempted once
 *      per app run, for ever.
 *   3. A KEY MINTED AT SEND TIME. It would be a NEW key on every pass, so the second send of a lost
 *      create would look to the server like a different create and make the very duplicate the key
 *      exists to prevent. It has to be exactly as old as the entry.
 *   4. AN OLDER ENTRY GAINING A KEY IT WAS NEVER QUEUED WITH. Entries written by an earlier build
 *      have none, and must replay byte for byte as they always did — the rule `unfiled` and
 *      `ownerUserId` already follow.
 *
 * WHAT IS ASSERTED DIRECTLY AND WHAT IS PINNED BY READING THE SOURCE. The decision —
 * {@link outboxMintsClientKey} — is pure and exported, so it is called. The mint site and the merge
 * site are module-private and only observable against a real IndexedDB and a real server, so they
 * are pinned by reading `lib/offline.ts`, exactly as `outbox-drain-triage-unit.spec.ts` pins the
 * cross-tab Web Lock and the `queuedHere` counter next door. A weaker assertion, and better than the
 * nothing that was there before.
 */

const OFFLINE_SOURCE = readFileSync(join(__dirname, "..", "lib", "offline.ts"), "utf8");

/** The body of one named function, sliced out so a test can assert about that function alone. */
function functionBody(source: string, opener: string): string {
  const start = source.indexOf(opener);
  expect(start, `\`${opener}\` is still in lib/offline.ts`).toBeGreaterThan(-1);
  const rest = source.slice(start);
  const end = rest.indexOf("\n}");
  expect(end, `\`${opener}\` still closes at column 0`).toBeGreaterThan(-1);
  return rest.slice(0, end);
}

/* ────────────────────────────────────────────────────────────────────────────
 * 1. WHICH ENTRIES GET A KEY
 * ──────────────────────────────────────────────────────────────────────────── */

test("the four guarded create routes get a key", () => {
  for (const endpoint of ["/workshops", "/products", "/tools", "/processes"]) {
    expect(outboxMintsClientKey({ endpoint, method: "POST" }), endpoint).toBe(true);
  }
});

test("artisans and crafts do not, because they are already idempotent under a better key", () => {
  /*
    NOT AN OVERSIGHT AND NOT A COST SAVING. `Artisan.aadhaarNumber` is @unique and
    `artisans._guard_identity_conflicts` answers a pre-write 409 that NAMES the artisan already
    holding the number, which is the sentence the drain's 409 arm shows a researcher so they can go
    and find them. `Craft.name` is @unique with its own 409. A second mechanism beside either would
    be two guards that can disagree about what a duplicate is — and worse, the 409 arm is written on
    the assumption that a clash is SOMEBODY ELSE'S record, which a collision with our own earlier
    create would falsify.

    It is also a hard refusal rather than a preference: `clientKey` is not declared on `ArtisanCreate`
    or `CraftCreate`, and `APIModel` is extra="forbid", so sending one 422s the whole save.
  */
  expect(outboxMintsClientKey({ endpoint: "/artisans", method: "POST" })).toBe(false);
  expect(outboxMintsClientKey({ endpoint: "/crafts", method: "POST" })).toBe(false);
});

test("a correction never gets one, whatever it is a correction to", () => {
  // The UPDATE schemas do not declare `clientKey`. A PATCH carrying one is `extra_forbidden`, which
  // this queue reads as a build disagreement and re-attempts once per app run for ever. A
  // correction's own idempotency question is answered by `expectedUpdatedAt`, not by this.
  for (const endpoint of ["/workshops/w1", "/products/p1", "/tools/t1", "/processes/pr1"]) {
    expect(outboxMintsClientKey({ endpoint, method: "PATCH" }), endpoint).toBe(false);
  }
  // And the bare collection path under PATCH is not a create either, however it is spelled.
  expect(outboxMintsClientKey({ endpoint: "/products", method: "PATCH" })).toBe(false);
});

test("a create of something else — a rating, a questionnaire, a media-only entry — gets none", () => {
  // Every one of these is either idempotent by construction on the server or performs no create at
  // all, and all of them would 422 on an undeclared key.
  for (const endpoint of ["/design-ratings", "/questionnaire/interviews", "/questionnaires", "/media/complete"]) {
    expect(outboxMintsClientKey({ endpoint, method: "POST" }), endpoint).toBe(false);
  }
});

test("a sub-path of a guarded route is not the guarded route", () => {
  // `/tools/{id}/artisans` is a POST that creates no ToolDocumentation, and its body has no
  // `clientKey`. An `endsWith`/`startsWith` test would have handed it one.
  expect(outboxMintsClientKey({ endpoint: "/tools/t1/artisans", method: "POST" })).toBe(false);
  expect(outboxMintsClientKey({ endpoint: "/workshops/access-requests", method: "POST" })).toBe(false);
  expect(outboxMintsClientKey({ endpoint: "/workshops/unmapped/map", method: "POST" })).toBe(false);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. WHEN THE KEY IS MINTED — the half that decides whether any of this works
 * ──────────────────────────────────────────────────────────────────────────── */

test("the key is minted at QUEUE time, in the one place every form passes through", () => {
  /*
    THE WHOLE MECHANISM IS IN THE TIMING. A key minted in the drain would be a new key on every pass,
    so the second send of a create whose answer was lost would look to the server like a different
    create — and produce the second record this exists to prevent. It has to be as old as the entry.

    AND IN ONE PLACE, for `ownerUserId`'s reason one line above it in the same object literal: six
    forms reach `queueOffline`, and a mint any of them could forget is a mint one of them eventually
    does, leaving exactly one record type able to duplicate itself on the one path nobody tests with
    a signal.
  */
  const queue = functionBody(OFFLINE_SOURCE, "export async function queueOffline(");
  expect(queue, "minted inside the object handed to store.add").toContain("clientKey: outboxMintsClientKey(entry)");
  expect(queue.indexOf("clientKey:"), "written with the row, not after it").toBeGreaterThan(
    queue.indexOf("store.add(")
  );
  // The drain must not mint. If this ever fails, read the paragraph above before "fixing" it.
  const drain = OFFLINE_SOURCE.slice(OFFLINE_SOURCE.indexOf("export async function syncOutbox("));
  expect(drain, "the drain never mints a key").not.toContain("mintClientKey(");
});

test("a key a caller already set is respected rather than overwritten", () => {
  // Nothing does this today. The `??` is what makes it safe when something does — a re-queue that
  // must keep its identity would otherwise be handed a new one and become a second record.
  const queue = functionBody(OFFLINE_SOURCE, "export async function queueOffline(");
  expect(queue).toContain("entry.clientKey ?? mintClientKey()");
});

test("a browser with no crypto.randomUUID mints nothing rather than something guessable", () => {
  /*
    NULL IS A KNOWN, SURVIVABLE STATE — it is what every entry queued before this build is in. A
    hand-rolled `Math.random` UUID is not: a repeated key means one designer's create is answered
    with another's row, which is worse than the duplicate this whole field exists to prevent.
  */
  const mint = functionBody(OFFLINE_SOURCE, "function mintClientKey(");
  expect(mint).toContain("crypto.randomUUID");
  expect(mint, "no fallback generator").not.toContain("Math.random");
  expect(mint, "an unavailable API answers null").toContain("null");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. HOW THE KEY REACHES THE WIRE
 * ──────────────────────────────────────────────────────────────────────────── */

test("the key travels beside the stored body and is merged in at replay", () => {
  /*
    `body` is "serialised at queue time so a later schema change cannot alter what the user actually
    saved". The key is not something the user saved — it is bookkeeping about the SEND — so it lives
    on the entry and is written into the request at replay, exactly where the deliberate link
    clearances are written in. Android does the same, in `createFromEntry`.
  */
  const merge = functionBody(OFFLINE_SOURCE, "function bodyWithClearances(");
  expect(merge, "read off the entry, not off the stored body").toContain("entry.clientKey");
  expect(merge, "written into the request object").toContain("record.clientKey = clientKey");
  // An empty string is not an identity — the same rule `entryAlreadyCreated` applies to a stored "".
  expect(merge).toContain('typeof entry.clientKey === "string" && entry.clientKey');
});

test("an entry with no key adds no key, so an older entry replays byte for byte", () => {
  /*
    THE HALF THAT DECIDES WHETHER THIS IS SAFE TO SHIP. A laptop out of coverage for a fortnight
    holds entries written by the build installed a fortnight ago and none of them carries a key.
    Adding one would not merely be wrong, it would be a 422: these entries may be for `/artisans`,
    whose schema has no such field.
  */
  const merge = functionBody(OFFLINE_SOURCE, "function bodyWithClearances(");
  expect(merge, "no clearances and no key means the stored body, untouched").toContain(
    "if (cleared.length === 0 && !clientKey) return entry.body;"
  );
  expect(merge, "and the write itself is guarded").toContain("if (clientKey) record.clientKey");
});

test("the entry type declares the field, so a stored key survives a round trip through the store", () => {
  // IndexedDB here holds whole objects with no declared shape, so the TYPE is the only thing that
  // says this property exists — and a property TypeScript cannot see is a property the drain would
  // silently never read. The same trap `MediaFile.extraMetadata` had to be declared to avoid.
  expect(OFFLINE_SOURCE).toContain("clientKey?: string | null;");
  // No DB_VERSION bump: an upgrade transaction is the one moment a browser can decide it cannot open
  // the store that is holding somebody's fortnight, and an additive optional property needs none.
  const bumped = /DB_VERSION\s*=\s*(\d+)/.exec(OFFLINE_SOURCE);
  expect(bumped, "DB_VERSION is still declared").not.toBeNull();
});
