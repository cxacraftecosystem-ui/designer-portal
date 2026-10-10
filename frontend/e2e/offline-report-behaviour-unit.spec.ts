import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { draftReportInput, droppedWarnings, reportFileName, type DraftLike } from "@/lib/offlineReport/generate";
import { MAX_AGE_MS, MEDIA_BUDGET_BYTES, planEviction, sourcesAreUsable } from "@/lib/offlineReport/reportCache";
import { allReportAssetUrls } from "@/lib/offlineReport/offlineAssets";

/**
 * HOW THE WEB BEHAVES WITH NO CONNECTION, pinned where it can be checked without one.
 *
 * Every rule below is only ever reached in conditions nobody develops in — a laptop in a courtyard,
 * a tab that lost its signal an hour ago, a shared machine after one designer signs out — so they
 * are pinned as pure functions and, where the rule lives in a file a unit spec cannot run (the
 * service worker, the report screen), on that file's source.
 */

const FRONTEND = join(__dirname, "..");
const read = (...parts: string[]) => readFileSync(join(FRONTEND, ...parts), "utf8");
const DAY = 24 * 60 * 60 * 1000;

test.describe("the photograph cache keeps to its limits", () => {
  test("nothing is evicted while the cache is inside its budget and nothing is stale", () => {
    const now = 100 * DAY;
    expect(planEviction([{ id: "a", sizeBytes: 10, usedAt: now - DAY }], now)).toEqual([]);
  });

  test("a photograph unused for longer than the age limit goes, whatever room is left", () => {
    const now = 100 * DAY;
    expect(planEviction([{ id: "old", sizeBytes: 1, usedAt: now - MAX_AGE_MS - 1 }], now)).toEqual(["old"]);
  });

  test("over budget, the least recently used go first and only until it fits", () => {
    const now = 100 * DAY;
    const big = MEDIA_BUDGET_BYTES / 2;
    const doomed = planEviction(
      [
        { id: "newest", sizeBytes: big, usedAt: now - 1 },
        { id: "oldest", sizeBytes: big, usedAt: now - 3 },
        { id: "middle", sizeBytes: big, usedAt: now - 2 }
      ],
      now
    );
    expect(doomed).toEqual(["oldest"]);
  });
});

test.describe("a kept copy is served to the account that fetched it and to no other", () => {
  const record = { workshopId: "w", ownerUserId: "u1", storedAt: 1000, sources: {} as never };
  test("the same account, within the age limit", () => {
    expect(sourcesAreUsable(record, "u1", 1000 + DAY)).toBe(true);
  });
  test("another account on the same browser", () => {
    expect(sourcesAreUsable(record, "u2", 1000 + DAY)).toBe(false);
  });
  test("nobody signed in", () => {
    expect(sourcesAreUsable(record, null, 1000 + DAY)).toBe(false);
  });
  test("stale", () => {
    expect(sourcesAreUsable(record, "u1", 1000 + MAX_AGE_MS + 1)).toBe(false);
  });
});

test.describe("the draft is read the way the server reads a workshop", () => {
  const draft: DraftLike = {
    localId: "dwlocal-1",
    remoteId: null,
    header: {
      title: "Offline workshop",
      templateId: "DCH_STANDARD",
      workshopCode: null,
      craftName: null,
      clusterName: "Barpali",
      state: "Odisha",
      designerName: null
    },
    stages: {
      WORKSHOP_SETUP: {
        singletons: { workshopSetup: { craftName: "Ikat", implementingAgency: "Cooperative" } },
        collections: {}
      },
      WORKSHOP_PLAN_PARTICIPANTS_OPENING: {
        singletons: {},
        collections: { participant: [{ _clientKey: "k1", name: "Kamla Devi" }] },
        custom: { q1: "yes" }
      }
    }
  };

  test("a workshop that was never uploaded is built under its local id", () => {
    const input = draftReportInput(draft, null, new Date("2026-10-10T09:30:00.123Z"));
    expect(input.workshop.id).toBe("dwlocal-1");
    expect(input.generatedAt).toBe("2026-10-10T09:30:00Z");
    expect(input.sources).toBeNull();
  });

  test("a header left blank on this device is filled from stage 1, as the server's columns are", () => {
    const input = draftReportInput(draft, null);
    expect(input.workshop.craftName).toBe("Ikat");
    expect(input.workshop.implementingAgency).toBe("Cooperative");
    expect(input.workshop.clusterName).toBe("Barpali");
  });

  test("rows and the designer's own answers travel; an empty singleton is not invented", () => {
    const input = draftReportInput(draft, null);
    expect(input.stages.WORKSHOP_PLAN_PARTICIPANTS_OPENING.collections.participant).toHaveLength(1);
    expect(input.stages.WORKSHOP_PLAN_PARTICIPANTS_OPENING.custom).toEqual({ q1: "yes" });
    expect(input.stages.WORKSHOP_PLAN_PARTICIPANTS_OPENING.singleton).toEqual({});
  });
});

test.describe("what a file written on the device says beside it", () => {
  test("the file is named as the server names it", () => {
    expect(reportFileName("DW-OD-2026-014", "x", "PDF", new Date("2026-10-10T00:00:00Z"))).toBe(
      "DesignWorkshop_DW-OD-2026-014_20261010.pdf"
    );
    expect(reportFileName(null, "Sambalpuri ikat / Barpali", "DOCX", new Date("2026-10-10T00:00:00Z"))).toBe(
      "DesignWorkshop_Sambalpuri_ikat___Barpali_20261010.docx"
    );
  });

  test("each kind of loss is named for what it is, and none of them mentions a server", () => {
    const said = droppedWarnings(["map:india", "chart:BAR", "cmgmedia1"]).join(" ");
    expect(said).toContain("locator map");
    expect(said).toContain("1 figure(s)");
    expect(said).toContain("1 photograph(s)");
    expect(said).not.toMatch(/server|endpoint|API|HTTP/i);
  });
});

test.describe("the service worker caches the application and never the data", () => {
  const sw = read("public", "sw.js");
  test("only same-origin GETs are answered", () => {
    expect(sw).toContain('if (request.method !== "GET") return;');
    expect(sw).toContain("if (url.origin !== globalThis.location.origin) return;");
  });
  test("API paths are never cached", () => {
    expect(sw).toContain('!url.pathname.startsWith("/api/")');
  });
  test("pages are network first, so a connection always wins", () => {
    expect(sw).toMatch(/async function networkFirst[\s\S]*await fetch\(request\)[\s\S]*cache\.match\(key\)/);
  });
  test("its caches are bounded and an old version's are deleted", () => {
    expect(sw).toContain("STATIC_LIMIT");
    expect(sw).toContain("PAGE_LIMIT");
    expect(sw).toContain("caches.delete(name)");
  });
  test("sign-out empties the kept pages and the report cache", () => {
    expect(sw).toContain('"dw-clear-pages"');
    const auth = read("components", "AuthProvider.tsx");
    expect(auth).toContain("void clearReportCache();");
    expect(auth).toContain("forgetOfflinePages();");
  });
  test("it is registered by production builds only", () => {
    expect(read("components", "OfflineAppShell.tsx")).toContain('if (process.env.NODE_ENV !== "production") return;');
  });
  test("every asset a file written offline needs is under a path the worker keeps", () => {
    for (const url of allReportAssetUrls()) expect(url).toMatch(/^\/(report-fonts|boundaries)\//);
  });
});

test.describe("the screens do the thing instead of describing its absence", () => {
  const page = read("app", "(protected)", "design-workshops", "[id]", "report", "page.tsx");
  test("the download buttons do not wait for a connection", () => {
    expect(page).not.toContain("disabled={downloading !== null || !online || !remoteId}");
    expect(page.match(/disabled=\{downloading !== null\}/g)).toHaveLength(2);
  });
  test("a request that never arrives builds the report on the device", () => {
    expect(page).toContain("if (isUnreachable(err) && (await fromDevice())) return;");
    expect(page).toContain("await writeHere(format);");
  });
  test("no visible sentence says the browser cannot build the report", () => {
    for (const source of [
      page,
      read("components", "designworkshop", "report", "StageDocumentPreview.tsx"),
      read("components", "hero", "HeroFAQ.tsx"),
      read("components", "hero", "ReportEngine.tsx")
    ]) {
      expect(source).not.toContain("deliberately has no renderer of its own");
      expect(source).not.toContain("The report needs a connection");
      expect(source).not.toContain("the one part of the web half that needs the server");
      expect(source).not.toContain("it needs the API");
      expect(source).not.toContain("the repository could not build the document");
    }
  });
});
