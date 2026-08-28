import { expect, test } from "@playwright/test";

import {
  NAMES_IN_PROSE,
  classifyChange,
  covers,
  nameList,
  plural,
  scopeKey,
  scopeKeysOf,
  scopeRemoval,
  scopeWords,
  splitScopeKey,
  standingsBy,
  type Scope,
  type Standing
} from "@/lib/sharingScope";
import type { DataAccessGrant } from "@/lib/types";

/**
 * WHAT PRESSING GRANT WOULD TAKE AWAY — pinned without a browser.
 *
 * `_upsert_grant` (backend/app/api/routes/data_access.py) reconciles a grant's scope to exactly what
 * the payload names, so a grant is a REPLACEMENT and not an addition. Every case below is a case
 * where getting the arithmetic wrong deletes a colleague's access and reports success — and every
 * one of them is reachable only through a live API and a signed-in session, which is why they are
 * the kind of thing that ships broken. There is no React renderer in devDependencies either, so a
 * judgement left inside JSX is only ever exercised by somebody looking at a screen; the judgement
 * lives in `lib/sharingScope.ts` for that reason and is asserted here.
 */

function standing(over: Partial<Standing> = {}): Standing {
  return { status: "GRANTED", tier: "COMMENT", allData: false, keys: new Set<string>(), ...over };
}

function scope(over: Partial<Scope> = {}): Scope {
  return { allData: false, keys: new Set<string>(), ...over };
}

/* ────────────────────────────────────────────────────────────────────────────
 * The key is one string, spelled once
 * ──────────────────────────────────────────────────────────────────────────── */

test("a scope key round-trips, and splitting keeps the whole id", () => {
  expect(scopeKey("artisan", "ckabc123")).toBe("artisan::ckabc123");
  expect(splitScopeKey("artisan::ckabc123")).toEqual({ recordType: "artisan", recordId: "ckabc123" });
  // A `key.split("::")` destructured into two would drop everything past a second separator. Nothing
  // issues such an id today, and half of a record's identity is not a thing to lose on that basis.
  expect(splitScopeKey("media::a::b")).toEqual({ recordType: "media", recordId: "a::b" });
});

test("standings index the grant rows by person, keyed as scope items are", () => {
  const rows = [
    {
      id: "g1",
      ownerId: "owner",
      granteeId: "priya",
      status: "GRANTED",
      tier: "EDIT",
      allData: false,
      scopeItems: [
        { recordType: "craft", recordId: "c1" },
        { recordType: "process", recordId: "p1" }
      ]
    }
  ] as unknown as DataAccessGrant[];
  const map = standingsBy(rows, "granteeId");
  expect(map.get("priya")?.tier).toBe("EDIT");
  expect([...(map.get("priya")?.keys ?? [])].sort()).toEqual(["craft::c1", "process::p1"]);
  expect([...scopeKeysOf(rows[0])].sort()).toEqual(["craft::c1", "process::p1"]);
});

/* ────────────────────────────────────────────────────────────────────────────
 * covers / classifyChange — `reduce` is the answer that matters
 * ──────────────────────────────────────────────────────────────────────────── */

test("all-data covers everything, and nothing but all-data covers all-data", () => {
  expect(covers(scope({ allData: true }), scope({ keys: new Set(["artisan::a"]) }))).toBe(true);
  expect(covers(scope({ keys: new Set(["artisan::a"]) }), scope({ allData: true }))).toBe(false);
});

test("a narrower scope at the same tier is a REDUCE, not a raise", () => {
  const held = standing({ tier: "EDIT", keys: new Set(["artisan::a", "artisan::b"]) });
  expect(classifyChange(held, { ...scope({ keys: new Set(["artisan::a"]) }), tier: "EDIT" })).toBe("reduce");
});

test("a lower tier over the SAME records is still a reduce", () => {
  const held = standing({ tier: "EDIT", allData: true });
  expect(classifyChange(held, { ...scope({ allData: true }), tier: "DOWNLOAD" })).toBe("reduce");
});

test("a person with no grant, or a non-GRANTED one, is new rather than reduced", () => {
  expect(classifyChange(undefined, { ...scope({ allData: true }), tier: "DOWNLOAD" })).toBe("new");
  expect(
    classifyChange(standing({ status: "PENDING", tier: "EDIT", allData: true }), {
      ...scope({ keys: new Set(["artisan::a"]) }),
      tier: "DOWNLOAD"
    })
  ).toBe("new");
});

test("same tier and same reach is 'same'; a wider reach is a raise", () => {
  const held = standing({ tier: "COMMENT", keys: new Set(["artisan::a"]) });
  expect(classifyChange(held, { ...scope({ keys: new Set(["artisan::a"]) }), tier: "COMMENT" })).toBe("same");
  expect(classifyChange(held, { ...scope({ allData: true }), tier: "COMMENT" })).toBe("raise");
});

/* ────────────────────────────────────────────────────────────────────────────
 * scopeRemoval — WHICH records go, which is the half the warning was missing
 * ──────────────────────────────────────────────────────────────────────────── */

test("widening to all data removes nothing, even from a subset holder", () => {
  const held = standing({ keys: new Set(["artisan::a", "product::p"]) });
  expect(scopeRemoval(held, scope({ allData: true }))).toEqual({ kind: "none" });
});

test("an all-data holder narrowed to a subset loses everything, and there is no list to give", () => {
  const held = standing({ allData: true });
  // Deliberately NOT an enumeration: `allData` names no rows on the server either, and a list built
  // from whatever the picker happened to fetch would be wrong by exactly the records it could not show.
  expect(scopeRemoval(held, scope({ keys: new Set(["artisan::a"]) }))).toEqual({ kind: "allData" });
});

test("a subset narrowed names exactly the keys that leave", () => {
  const held = standing({ keys: new Set(["artisan::a", "product::p", "craft::c"]) });
  const removal = scopeRemoval(held, scope({ keys: new Set(["artisan::a"]) }));
  expect(removal.kind).toBe("records");
  expect(removal.kind === "records" ? [...removal.keys].sort() : []).toEqual(["craft::c", "product::p"]);
});

test("a subset that is kept whole removes nothing, even when the new scope is bigger", () => {
  const held = standing({ keys: new Set(["artisan::a"]) });
  expect(scopeRemoval(held, scope({ keys: new Set(["artisan::a", "tool::t"]) }))).toEqual({ kind: "none" });
});

/**
 * THE CASE THE THREE NEW RECORD TYPES CREATED.
 *
 * Before the picker offered crafts, processes and files, a grant containing one of them could only
 * have come from the API or the handset — and a subset save from the Sharing page would have dropped
 * it while the warning said nothing, because the page cannot name a record it never fetched. The key
 * arithmetic never cared about the type; what had to change is that the CALLER counts the keys it
 * cannot resolve to a name instead of listing only the ones it can.
 */
test("removal is computed on keys, so a type the picker cannot name is still reported as leaving", () => {
  const held = standing({ keys: new Set(["artisan::a", "media::m1", "media::m2"]) });
  const removal = scopeRemoval(held, scope({ keys: new Set(["artisan::a"]) }));
  expect(removal.kind === "records" ? removal.keys.length : 0).toBe(2);
});

/* ────────────────────────────────────────────────────────────────────────────
 * Prose — a collapsed tail must state the collapse
 * ──────────────────────────────────────────────────────────────────────────── */

test("scopeWords says all data, or a counted number of records", () => {
  expect(scopeWords(standing({ allData: true }))).toBe("all data");
  expect(scopeWords(standing({ keys: new Set(["artisan::a"]) }))).toBe("1 record");
  expect(scopeWords(standing({ keys: new Set(["artisan::a", "tool::t"]) }))).toBe("2 records");
});

test("nameList collapses its tail into a COUNT rather than just stopping", () => {
  const names = Array.from({ length: NAMES_IN_PROSE + 3 }, (_, index) => `Person ${index + 1}`);
  const rendered = nameList(names);
  expect(rendered).toContain("and 3 others");
  expect(rendered.startsWith("Person 1, Person 2")).toBe(true);
  // Under the limit nothing is added — "and 0 others" would be worse than saying nothing.
  expect(nameList(["Priya", "Anil"])).toBe("Priya, Anil");
  // A caller may tighten the limit; the collapse must still be stated at the tighter one.
  expect(nameList(["a", "b", "c", "d", "e"], 4)).toBe("a, b, c, d and 1 other");
});

test("plural gets one right", () => {
  expect(plural(1, "record")).toBe("1 record");
  expect(plural(0, "record")).toBe("0 records");
});
