import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { NAV_ITEMS } from "@/components/DynamicIslandNav";
import {
  DESIGN_WORKSHOP_DESTINATIONS,
  ELIGIBLE_INSPECTOR_SEARCH_MAX,
  eligibleInspectorNotice,
  inspectionFieldReading,
  inspectionIsReadOnly,
  inspectionMayOpen,
  MAX_DESIGN_WORKSHOP_INSPECTORS
} from "@/lib/designWorkshopInspections";
import type { DwEntity, DwField, DwRegistry } from "@/lib/designWorkshops";
import {
  canAccessRoute,
  canInspectDesignWorkshops,
  canRunDesignWorkshops,
  isInspectorTier,
  ROLE_LABELS,
  ROLE_RANK,
  routeGuardFor
} from "@/lib/permissions";
import type { User, UserRole } from "@/lib/types";

/**
 * THE INSPECTOR / REVIEWER SURFACE ON THE WEB — its guard, its label, and the read-only promise.
 *
 * ── WHY THIS FILE, AND WHY THESE THREE THINGS ───────────────────────────────────────────────────
 *
 * The whole INSPECTOR tier landed on the server — a rank, a fifth scope system, a forward-only
 * migration and machine-checked parity over all of it — and no client called any of it. This spec
 * covers the three parts of the client half that are decidable without a browser, and every one of
 * them is a place a reasonable instinct gives the wrong answer:
 *
 * 1. **THE GUARD IS NOT A RANK FLOOR AND IS NOT MONOTONIC.** Since the owner's ruling of
 *    2026-10-09 it is everybody who may be APPOINTED to inspect — the Inspector / Reviewer tier at 37
 *    and the three administering tiers at 48, 50 and 60 — so a professor (40), an Assistant Director
 *    (42) and a Regional Director (45) are refused a page the tiers on either side of them may open.
 *    Until that ruling the hole was the other way up: an ADMIN was refused by name and only rank 37
 *    was admitted. Either way §2's ladder gives the wrong answer, and nothing else in `ROUTE_GUARDS`
 *    behaves like this, so nothing else would catch it drifting.
 * 2. **THE LABEL IS TWO WORDS AND THE ENUM IS ONE.** The tier is stored `INSPECTOR` and labelled
 *    "Inspector / Reviewer", because `canReview` already means the RELATION "may review anyone
 *    strictly below me" and one word cannot be both a rank and a relation. Every refusal this
 *    feature prints has to use the label; a screen that says "Inspector access required" has named
 *    a tier that does not exist in the picker the reader would go looking in.
 * 3. **`readOnly` HAS TO SUPPRESS EVERY WRITE AFFORDANCE, AND THE ABSENT FLAG TOO.** The boolean is
 *    on the wire precisely because both clients will one day render this payload through the same
 *    screen as the designer's read, and a screen that cannot tell them apart offers a Save button
 *    the API answers 404 to. A `=== true` test would fail open on a deployment that predates the
 *    key, which is the one direction that must never fail open.
 *
 * ── AND WHY THE NUMBERS ARE READ OFF THE SERVER RATHER THAN TYPED ───────────────────────────────
 *
 * `MAX_DESIGN_WORKSHOP_INSPECTORS` and the search length are MIRRORS. A hard-coded expectation here
 * would be a third copy that agreed with the server on the day it was typed — the exact failure the
 * role-ladder spec beside this one exists to prevent. If one of these fails, do not edit the
 * expectation: find which side moved.
 *
 * PURE NODE — no browser, no server, no database.
 * Run: `npx playwright test e2e/design-workshop-inspections-unit.spec.ts --reporter=line`
 */

const PATH = "/design-workshop-inspections";

const INSPECTORS_PY = readFileSync(
  join(__dirname, "..", "..", "backend", "app", "services", "design_workshop_inspectors.py"),
  "utf8"
);

const INSPECTIONS_ROUTES_PY = readFileSync(
  join(__dirname, "..", "..", "backend", "app", "api", "routes", "design_workshop_inspections.py"),
  "utf8"
);

const ROLES: UserRole[] = [
  "MASTER_ADMIN",
  "ADMIN",
  "MINISTRY_ADMIN",
  "REGIONAL_DIRECTOR",
  "ASSISTANT_DIRECTOR",
  "PROFESSOR",
  "INSPECTOR",
  "DESIGNER",
  "RESEARCHER",
  "FIELD_CONTRIBUTOR",
  "CROWDSOURCE_VOLUNTEER"
];

/** The role is the whole fixture: every predicate this file exercises reads nothing else. */
const user = (role: UserRole): User => ({ id: "u1", email: "a@b.c", name: "A", role } as User);

/**
 * Who may be APPOINTED to inspect a workshop, written out here rather than imported, so the
 * assertions below are a second statement of the rule and not the rule read back to itself. The
 * owner's ruling of 2026-10-09 (D3): the tier, plus the three administering tiers by appointment.
 */
const MAY_BE_APPOINTED: UserRole[] = ["MASTER_ADMIN", "ADMIN", "MINISTRY_ADMIN", "INSPECTOR"];

test.describe("who may open the inspection surface", () => {
  test("whoever may be appointed to inspect may, and nobody else may", () => {
    for (const role of ROLES) {
      expect(canInspectDesignWorkshops(user(role)), `${role} and the inspection surface`).toBe(
        MAY_BE_APPOINTED.includes(role)
      );
    }
    expect(canInspectDesignWorkshops(null)).toBe(false);
    expect(canInspectDesignWorkshops(undefined)).toBe(false);
  });

  test("the tiers BETWEEN the inspector and the admins are refused, which the rank ladder would talk you out of", () => {
    // THE NON-MONOTONIC ROWS. A professor, an Assistant Director and a Regional Director all outrank
    // an inspector and are outranked by every admin, and all three are refused: none of them may be
    // appointed to inspect. Until 2026-10-09 the hole was the other way up — an ADMIN was refused by
    // name — and the assertion that it is a SET, not a floor, is the part that did not move.
    for (const role of ["PROFESSOR", "ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"] as const) {
      expect(ROLE_RANK[role]).toBeGreaterThan(ROLE_RANK.INSPECTOR);
      expect(ROLE_RANK[role]).toBeLessThan(ROLE_RANK.MINISTRY_ADMIN);
      expect(canAccessRoute(user(role), PATH), role).toBe(false);
    }
    expect(canAccessRoute(user("ADMIN"), PATH)).toBe(true);
    expect(canAccessRoute(user("MASTER_ADMIN"), PATH)).toBe(true);
  });

  test("the route guard refuses everyone the predicate refuses, and nested paths too", () => {
    for (const role of ROLES) {
      const admitted = MAY_BE_APPOINTED.includes(role);
      expect(canAccessRoute(user(role), PATH), `${role} and ${PATH}`).toBe(admitted);
      // The detail page lives under the same prefix and inherits the rule — `routeMatches` is
      // segment-wise, so this is the nesting half rather than a second row.
      expect(canAccessRoute(user(role), `${PATH}/dw_1`), `${role} and a workshop under it`).toBe(admitted);
    }
    expect(canAccessRoute(null, PATH)).toBe(false);
  });

  test("the rule is this path's own row, not a prefix that happens to reach it", () => {
    // `routeGuardFor` is longest-match, so a row could be shadowed — or believed to exist because
    // something broader is doing the refusing. Pinning `path` means a future edit that deletes this
    // row and relies on `/design-workshops` covering it fails here rather than in production. It
    // cannot cover it: the two are siblings, and the API's prefixes are separate for the same reason
    // — a shared loader widened to admit an inspector would grant STAGE WRITES.
    expect(routeGuardFor(PATH)?.path).toBe(PATH);
    expect(routeGuardFor(`${PATH}/dw_1`)?.path).toBe(PATH);
    expect(routeGuardFor("/design-workshops")?.path).toBe("/design-workshops");
    // A sibling whose name merely starts with the same letters must not inherit the refusal.
    expect(routeGuardFor(`${PATH}-archive`)).toBeNull();
  });

  test("it is NOT gated on canRunDesignWorkshops, which an inspector deliberately fails", () => {
    // The assertion that catches somebody "tidying" this row into the design-workshop family: it
    // would hide the only surface the tier exists for from the only tier that can use it. The two
    // sets OVERLAP since 2026-10-09 — the three administering tiers are in both — and that overlap is
    // exactly why a post on a workshop takes that workshop's writes away from its holder.
    expect(canRunDesignWorkshops(user("INSPECTOR"))).toBe(false);
    expect(canInspectDesignWorkshops(user("DESIGNER"))).toBe(false);
    expect(routeGuardFor(PATH)?.can).not.toBe(canRunDesignWorkshops);
  });
});

test.describe("the menu entry and the guard agree", () => {
  test("there is exactly one NAV_ITEMS entry and it is spelled like the guard row", () => {
    const entries = NAV_ITEMS.filter((item) => item.href === PATH);
    expect(entries.length, "exactly one nav entry for the inspection surface").toBe(1);
    expect(routeGuardFor(entries[0].href)?.path).toBe(PATH);
  });

  test("nobody is hidden from the menu and admitted at the URL, or the reverse", () => {
    // A hidden entry is a decision about DRAWING; a guard is a decision about REACHING. This family
    // has shipped the first without the second three times. Both directions are asserted here
    // because this row is the first whose refusal is non-monotonic, so "the guard is stricter, which
    // is safe" is not an argument that holds.
    const entry = NAV_ITEMS.find((item) => item.href === PATH);
    expect(entry).toBeTruthy();
    for (const role of ROLES) {
      const account = user(role);
      expect(entry!.can(account), `menu for ${role}`).toBe(canAccessRoute(account, PATH));
    }
  });
});

test.describe("the tier's name, verbatim", () => {
  test("the enum is INSPECTOR, the label is both words, and the rank is 37", () => {
    expect(ROLE_LABELS.INSPECTOR).toBe("Inspector / Reviewer");
    expect(ROLE_RANK.INSPECTOR).toBe(37);
    expect(ROLE_RANK.DESIGNER).toBeLessThan(ROLE_RANK.INSPECTOR);
    expect(ROLE_RANK.INSPECTOR).toBeLessThan(ROLE_RANK.PROFESSOR);
  });

  test("the refusal a reader meets names the tier by its label, not by the enum", () => {
    // A panel that said "INSPECTOR access required" would name a token the reader has never seen,
    // and one that said "Inspector access required" would name half a tier — the picker they would
    // go looking in lists "Inspector / Reviewer".
    const guard = routeGuardFor(PATH);
    expect(guard?.title).toContain(ROLE_LABELS.INSPECTOR);
    expect(guard?.message).toContain(ROLE_LABELS.INSPECTOR);
    expect(guard?.message).not.toContain("INSPECTOR");
  });
});

test.describe("readOnly suppresses every write affordance", () => {
  test("the flag is honoured, and its ABSENCE fails closed", () => {
    expect(inspectionIsReadOnly({ readOnly: true })).toBe(true);
    // THE CASE THIS FUNCTION EXISTS FOR. A deployment that predates the key sends no flag, and a
    // `=== true` test would then draw a Save button on a prefix with no write route at all.
    expect(inspectionIsReadOnly({})).toBe(true);
    expect(inspectionIsReadOnly(null)).toBe(true);
    expect(inspectionIsReadOnly(undefined)).toBe(true);
    // An explicit `false` is honoured rather than ignored, because that is the value the designer's
    // read will carry the day one screen serves both.
    expect(inspectionIsReadOnly({ readOnly: false })).toBe(false);
  });

  test("every destination of the workshop hub is refused on a read", () => {
    // The list is walked rather than spot-checked so that a TENTH page added to the designer's hub
    // is refused here the moment it is named — which is the whole reason the constant exists instead
    // of nine JSX branches nobody can enumerate.
    expect(DESIGN_WORKSHOP_DESTINATIONS.length).toBeGreaterThan(0);
    expect(DESIGN_WORKSHOP_DESTINATIONS).toContain("stages");
    for (const destination of DESIGN_WORKSHOP_DESTINATIONS) {
      expect(inspectionMayOpen(destination, { readOnly: true }), destination).toBe(false);
      expect(inspectionMayOpen(destination, {}), `${destination} with no flag`).toBe(false);
      expect(inspectionMayOpen(destination, null), `${destination} with no payload`).toBe(false);
    }
  });

  test("and none of them is refused for a reason other than the read", () => {
    // The counterpart, so the test above cannot pass by the function being a constant `false`: on a
    // payload that explicitly is not a read, every destination opens. That is what makes the
    // assertions above statements about `readOnly` rather than about nothing.
    for (const destination of DESIGN_WORKSHOP_DESTINATIONS) {
      expect(inspectionMayOpen(destination, { readOnly: false }), destination).toBe(true);
    }
  });
});

test.describe("the caps and the search length mirror the server", () => {
  test("MAX_DESIGN_WORKSHOP_INSPECTORS is the server's number, read off disk", () => {
    const match = INSPECTORS_PY.match(/^MAX_DESIGN_WORKSHOP_INSPECTORS\s*=\s*(\d+)/m);
    expect(match, "MAX_DESIGN_WORKSHOP_INSPECTORS not found in the service module").toBeTruthy();
    expect(MAX_DESIGN_WORKSHOP_INSPECTORS).toBe(Number(match![1]));
  });

  test("the search box caps itself at the length the endpoints accept", () => {
    // Both search routes declare `Query(None, max_length=120)`; past it the answer is a 422, and a
    // picker that answers a long paste with a red banner has taught nobody anything actionable.
    const lengths = [...INSPECTIONS_ROUTES_PY.matchAll(/max_length=(\d+)/g)].map((m) => Number(m[1]));
    expect(lengths.length, "no max_length found on the inspection routes").toBeGreaterThan(0);
    for (const declared of lengths) expect(ELIGIBLE_INSPECTOR_SEARCH_MAX).toBe(declared);
  });

  test("the TIER is one role on both sides, and this client's tier predicate holds the same one", () => {
    // `INSPECTION_ROLES = frozenset({"INSPECTOR"})` — the tier whose whole job inspecting is. It was
    // also the surface's door until 2026-10-09; the door is now the holder set, which joins this tier
    // to the three administering tiers and is held to the server in `admin-serve-as-unit.spec.ts`.
    // What stays pinned HERE is the tier itself, which decides the walkthrough deck and how a refusal
    // of the surface is read — so the day the owner adds a second member, this fails and points at
    // `isInspectorTier`.
    const match = INSPECTORS_PY.match(/^INSPECTION_ROLES\s*=\s*frozenset\(\{([^}]*)\}\)/m);
    expect(match, "INSPECTION_ROLES not found in the service module").toBeTruthy();
    const declared = [...match![1].matchAll(/"([A-Z_]+)"/g)].map((m) => m[1]).sort();
    expect(declared).toEqual(["INSPECTOR"]);
    expect(ROLES.filter((role) => isInspectorTier(user(role))).sort()).toEqual(declared);
  });
});

test.describe("the admin picker's one-line notice", () => {
  test("silence is the answer for a complete list, and for a search that found somebody", () => {
    expect(eligibleInspectorNotice({ truncated: false, offered: 12, searched: false })).toBe("");
    expect(eligibleInspectorNotice({ truncated: false, offered: 3, searched: true })).toBe("");
  });

  test("a cut list says so, and says which move reaches the rest", () => {
    expect(eligibleInspectorNotice({ truncated: true, offered: 2000, searched: false })).toContain("search");
    expect(eligibleInspectorNotice({ truncated: true, offered: 2000, searched: true })).toContain("narrow");
  });

  test("an empty SEARCH is never reported as an empty repository", () => {
    // The silent-emptiness class in one sentence: the reader mistyped a surname and is told nobody
    // is eligible. The two must be different strings and the searched one must name the search.
    const searched = eligibleInspectorNotice({ truncated: false, offered: 0, searched: true });
    expect(searched).toContain("search");
    expect(searched).not.toBe("");
  });

  test("the handset's admin picker says the same three sentences, word for word", () => {
    /*
      The server sends the widened list — Ministry Admin, admin and master admin beside the tier — to
      the handset's admin-only inspectors screen as well, so the tier-only wording it kept was false
      there; it now says these. An admin moves between the two apps, and one cut with two spellings
      reads as two different problems.
    */
    const handset = readFileSync(
      join(
        __dirname,
        "..",
        "..",
        "android",
        "app",
        "src",
        "main",
        "java",
        "com",
        "designprototype",
        "workshop",
        "data",
        "DesignWorkshopInspections.kt"
      ),
      "utf8"
    );
    const notice = handset.slice(handset.indexOf("fun dwInspectorOfferNotice("));
    expect(notice.length, "the handset's notice was located").toBeGreaterThan(0);
    for (const sentence of [
      eligibleInspectorNotice({ truncated: true, offered: 2000, searched: false }),
      eligibleInspectorNotice({ truncated: true, offered: 2000, searched: true }),
      eligibleInspectorNotice({ truncated: false, offered: 0, searched: true })
    ]) {
      expect(notice.slice(0, notice.indexOf("else -> null")), sentence).toContain(`"${sentence}"`);
    }
  });
});

/* ────────────────────────────────────────────────────────────────────────────
 * Reading one stored answer without a renderer
 * ──────────────────────────────────────────────────────────────────────────── */

const registry: DwRegistry = {
  version: "test",
  enums: { YesNo: [{ value: "Y", label: "Yes, always" }] },
  stages: []
};

function field(overrides: Partial<DwField> & Pick<DwField, "key" | "type">): DwField {
  return {
    label: overrides.key,
    tier: "BASIC",
    required: false,
    ...overrides
  } as DwField;
}

const entity = { key: "e", name: "E", cardinality: "SINGLETON", title: "E", description: "", parent: "", labelField: "", fields: [] } as unknown as DwEntity;

test.describe("what a read shows about one field", () => {
  test("a media field is COUNTED, never drawn and never called empty", () => {
    // The distinction this whole branch exists for: "no photograph" and "a photograph this read does
    // not carry" are different facts, and an empty gallery says the first about the second.
    const images = field({ key: "photos", type: "IMAGE_LIST" });
    expect(inspectionFieldReading(registry, entity, images, { photos: ["m1", "m2", "m3"] })).toEqual({
      kind: "media",
      count: 3
    });
    const one = field({ key: "photo", type: "IMAGE" });
    expect(inspectionFieldReading(registry, entity, one, { photo: "m1" })).toEqual({ kind: "media", count: 1 });
    // A media field with nothing in it is genuinely empty and must not print the sentence.
    expect(inspectionFieldReading(registry, entity, one, {})).toEqual({ kind: "empty" });
  });

  test("an ENUM prints its label, and an unknown token prints itself rather than vanishing", () => {
    const enumField = field({ key: "ok", type: "ENUM", enum: "YesNo" });
    expect(inspectionFieldReading(registry, entity, enumField, { ok: "Y" })).toEqual({
      kind: "text",
      text: "Yes, always"
    });
    // A real answer given against a list that has since changed. Hiding it from an INSPECTION is the
    // least forgivable place to hide anything.
    expect(inspectionFieldReading(registry, entity, enumField, { ok: "Z" })).toEqual({ kind: "text", text: "Z" });
  });

  test("a BOOL reads as a word, and false is an ANSWER rather than an absence", () => {
    const bool = field({ key: "attended", type: "BOOL" });
    expect(inspectionFieldReading(registry, entity, bool, { attended: true })).toEqual({ kind: "text", text: "Yes" });
    // `isFilled` counts `false` as filled — the server's `_is_filled` does too — so "No" must reach
    // the screen. A read that dropped it would show an inspector nothing where a designer answered.
    expect(inspectionFieldReading(registry, entity, bool, { attended: false })).toEqual({ kind: "text", text: "No" });
  });

  test("a blank, a whitespace string and an absent key are all empty", () => {
    const text = field({ key: "note", type: "TEXT" });
    expect(inspectionFieldReading(registry, entity, text, {})).toEqual({ kind: "empty" });
    expect(inspectionFieldReading(registry, entity, text, { note: "" })).toEqual({ kind: "empty" });
    expect(inspectionFieldReading(registry, entity, text, { note: "   " })).toEqual({ kind: "empty" });
  });

  test("a MONEY value keeps its trailing zero, because the server took care to preserve it", () => {
    // `inputValue` is `String()` rather than a number round trip for exactly this reason, and a read
    // that re-parsed would show 1250.1 for a price recorded as 1250.10.
    const money = field({ key: "price", type: "MONEY" });
    expect(inspectionFieldReading(registry, entity, money, { price: "1250.10" })).toEqual({
      kind: "text",
      text: "1250.10"
    });
  });
});
