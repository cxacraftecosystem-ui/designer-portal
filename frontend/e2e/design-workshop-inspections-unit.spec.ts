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

const user = (role: UserRole): User => ({ id: "u1", email: "a@b.c", name: "A", role } as User);

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
      expect(canAccessRoute(user(role), `${PATH}/dw_1`), `${role} and a workshop under it`).toBe(admitted);
    }
    expect(canAccessRoute(null, PATH)).toBe(false);
  });

  test("the rule is this path's own row, not a prefix that happens to reach it", () => {
    expect(routeGuardFor(PATH)?.path).toBe(PATH);
    expect(routeGuardFor(`${PATH}/dw_1`)?.path).toBe(PATH);
    expect(routeGuardFor("/design-workshops")?.path).toBe("/design-workshops");
    expect(routeGuardFor(`${PATH}-archive`)).toBeNull();
  });

  test("it is NOT gated on canRunDesignWorkshops, which an inspector deliberately fails", () => {
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
    const guard = routeGuardFor(PATH);
    expect(guard?.title).toContain(ROLE_LABELS.INSPECTOR);
    expect(guard?.message).toContain(ROLE_LABELS.INSPECTOR);
    expect(guard?.message).not.toContain("INSPECTOR");
  });
});

test.describe("readOnly suppresses every write affordance", () => {
  test("the flag is honoured, and its ABSENCE fails closed", () => {
    expect(inspectionIsReadOnly({ readOnly: true })).toBe(true);
    expect(inspectionIsReadOnly({})).toBe(true);
    expect(inspectionIsReadOnly(null)).toBe(true);
    expect(inspectionIsReadOnly(undefined)).toBe(true);
    expect(inspectionIsReadOnly({ readOnly: false })).toBe(false);
  });

  test("every destination of the workshop hub is refused on a read", () => {
    expect(DESIGN_WORKSHOP_DESTINATIONS.length).toBeGreaterThan(0);
    expect(DESIGN_WORKSHOP_DESTINATIONS).toContain("stages");
    for (const destination of DESIGN_WORKSHOP_DESTINATIONS) {
      expect(inspectionMayOpen(destination, { readOnly: true }), destination).toBe(false);
      expect(inspectionMayOpen(destination, {}), `${destination} with no flag`).toBe(false);
      expect(inspectionMayOpen(destination, null), `${destination} with no payload`).toBe(false);
    }
  });

  test("and none of them is refused for a reason other than the read", () => {
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
    const lengths = [...INSPECTIONS_ROUTES_PY.matchAll(/max_length=(\d+)/g)].map((m) => Number(m[1]));
    expect(lengths.length, "no max_length found on the inspection routes").toBeGreaterThan(0);
    for (const declared of lengths) expect(ELIGIBLE_INSPECTOR_SEARCH_MAX).toBe(declared);
  });

  test("the TIER is one role on both sides, and this client's tier predicate holds the same one", () => {
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
    const searched = eligibleInspectorNotice({ truncated: false, offered: 0, searched: true });
    expect(searched).toContain("search");
    expect(searched).not.toBe("");
  });

  test("the handset's admin picker says the same three sentences, word for word", () => {
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
    const images = field({ key: "photos", type: "IMAGE_LIST" });
    expect(inspectionFieldReading(registry, entity, images, { photos: ["m1", "m2", "m3"] })).toEqual({
      kind: "media",
      count: 3
    });
    const one = field({ key: "photo", type: "IMAGE" });
    expect(inspectionFieldReading(registry, entity, one, { photo: "m1" })).toEqual({ kind: "media", count: 1 });
    expect(inspectionFieldReading(registry, entity, one, {})).toEqual({ kind: "empty" });
  });

  test("an ENUM prints its label, and an unknown token prints itself rather than vanishing", () => {
    const enumField = field({ key: "ok", type: "ENUM", enum: "YesNo" });
    expect(inspectionFieldReading(registry, entity, enumField, { ok: "Y" })).toEqual({
      kind: "text",
      text: "Yes, always"
    });
    expect(inspectionFieldReading(registry, entity, enumField, { ok: "Z" })).toEqual({ kind: "text", text: "Z" });
  });

  test("a BOOL reads as a word, and false is an ANSWER rather than an absence", () => {
    const bool = field({ key: "attended", type: "BOOL" });
    expect(inspectionFieldReading(registry, entity, bool, { attended: true })).toEqual({ kind: "text", text: "Yes" });
    expect(inspectionFieldReading(registry, entity, bool, { attended: false })).toEqual({ kind: "text", text: "No" });
  });

  test("a blank, a whitespace string and an absent key are all empty", () => {
    const text = field({ key: "note", type: "TEXT" });
    expect(inspectionFieldReading(registry, entity, text, {})).toEqual({ kind: "empty" });
    expect(inspectionFieldReading(registry, entity, text, { note: "" })).toEqual({ kind: "empty" });
    expect(inspectionFieldReading(registry, entity, text, { note: "   " })).toEqual({ kind: "empty" });
  });

  test("a MONEY value keeps its trailing zero, because the server took care to preserve it", () => {
    const money = field({ key: "price", type: "MONEY" });
    expect(inspectionFieldReading(registry, entity, money, { price: "1250.10" })).toEqual({
      kind: "text",
      text: "1250.10"
    });
  });
});
