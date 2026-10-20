import fs from "node:fs";

import { expect, test, type Page } from "@playwright/test";

/**
 * The coverage view of `/map`, end to end in a real browser over the real page, with every API call
 * answered from fixtures: the assertions are about exact counts and links, which cannot be exact
 * against a live corpus, and nothing here writes. No account is needed — the session is a fixture
 * token and `/me` a ministry account — so the spec runs wherever the web app runs. The server's own
 * gate is held by `backend/tests/test_map_coverage.py`.
 */

const SHOTS = process.env.E2E_SHOT_DIR ?? "test-results/map-coverage";
const API = (process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8000").replace(/\/$/, "");

const BARMER = {
  stateLgd: 8,
  districtLgd: 90,
  state: "Rajasthan",
  district: "Barmer",
  polygon: "OWN",
  parentLgd: null,
  counts: { designWorkshops: 2, plannedWorkshops: 3, workshops: 1, artisans: 14, crafts: 2, products: 5, tools: 3, media: 40 },
  peer: null,
  craftCodes: ["ajrakh"],
  clusters: ["Barmer Ajrakh"]
};

function coverage(scheme: string[]) {
  const units = scheme.length ? [{ ...BARMER, counts: { ...BARMER.counts, designWorkshops: 1 } }] : [BARMER];
  return {
    asOf: "2026-10-18T00:00:00Z",
    register: { lgdAsOf: "2026-10-18", listVersion: 1, districtCount: 795, stateCount: 36 },
    boundaries: { shapesVersion: "x", depiction: "Survey of India" },
    filters: { level: "DISTRICT", craftCode: [], scheme, fy: [], stateLgd: null },
    catalogue: {
      crafts: [
        { code: "ajrakh", name: "Ajrakh" },
        { code: "bagru", name: "Bagru" }
      ],
      schemes: [
        { value: "DESIGN_PROTOTYPE_DEVELOPMENT", label: "Design & Prototype Development" },
        { value: "SKILL_UPGRADATION", label: "Skill Upgradation" }
      ],
      years: [{ fy: 2025, label: "2025-26" }]
    },
    units,
    markers: [
      {
        kind: "DESIGN_WORKSHOP", id: "dw1", title: "Ajrakh motifs", clusterName: "Barmer Ajrakh", craftCode: "ajrakh",
        stateLgd: 8, districtLgd: 90, lon: 71.4, lat: 25.75, precision: "DISTRICT_CENTROID", href: "/design-workshops/dw1"
      }
    ],
    markersTruncated: false,
    gaps: {
      craftsNeverDocumented: [{ code: "bagru", name: "Bagru" }],
      districtsWithoutWorkshop: {
        count: 794,
        byState: [{ stateLgd: 8, state: "Rajasthan", total: 41, without: [{ lgd: 91, name: "Jaisalmer" }] }]
      },
      statesWithoutWorkshop: [{ lgd: 24, name: "Gujarat" }]
    },
    unmatched: { count: 3, sample: [{ model: "DesignWorkshop", rawState: "Rajastan", rawDistrict: "Barmer", rows: 3 }] },
    peer: { status: "OFF", asOf: null },
    truncated: false
  };
}

async function stub(page: Page, options: { dark?: boolean } = {}) {
  const requests: string[] = [];
  const aliases: unknown[] = [];
  let unmatched = [
    { model: "DesignWorkshop", rawState: "Rajastan", rawDistrict: "Barmer", rows: 3, suggestion: null }
  ];
  await page.addInitScript((theme) => {
    window.localStorage.setItem("field_repo_token", "e2e-coverage-fixture");
    window.localStorage.setItem(
      "field_repo_preferences",
      JSON.stringify({ theme, reducedMotion: true, largerText: false, highContrast: false })
    );
  }, options.dark ? "dark" : "light");
  // Anything not answered below is a 404 from a stub, never a request to a real server.
  await page.route((url) => url.href.startsWith(API), (route) =>
    route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ detail: "Not Found" }) })
  );
  await page.route(/\/me$/, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        id: "u-ministry", email: "ministry@example.org", name: "Ministry Admin", role: "MINISTRY_ADMIN",
        isActive: true, mustChangePassword: false, twoFactorEnabled: false, usageConsentAt: "2026-01-01T00:00:00Z"
      })
    })
  );
  await page.route(/\/preferences\/me$/, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({}) })
  );
  await page.route(/\/ministry-dashboard\/entitlements$/, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ beneficiaries: true, coverageMap: true }) })
  );
  await page.route(/\/cross-portal\/status$/, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ enabled: false, coverageEnabled: false }) })
  );
  await page.route(/\/map\/coverage\/units\/.*/, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        unit: BARMER,
        buckets: {
          designWorkshops: {
            total: 2,
            items: [
              { id: "dw1", title: "Ajrakh motifs", href: "/design-workshops/dw1", craftCode: "ajrakh", fy: 2025 },
              { id: "dw2", title: "Natural dyes", href: "/design-workshops/dw2", craftCode: "ajrakh", fy: 2025 }
            ],
            nextCursor: null
          }
        },
        peerHref: null
      })
    })
  );
  await page.route(/\/map\/coverage\/unmatched$/, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        items: unmatched,
        total: unmatched.length,
        register: {
          states: [{ lgd: 8, name: "Rajasthan" }],
          districts: [{ lgd: 90, stateLgd: 8, name: "Barmer" }]
        }
      })
    })
  );
  await page.route(/\/map\/coverage\/aliases$/, async (route) => {
    aliases.push(route.request().postDataJSON());
    unmatched = [];
    await route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify({ id: "a1" }) });
  });
  await page.route(/\/map\/coverage(\?.*)?$/, (route) => {
    const url = new URL(route.request().url());
    requests.push(url.search);
    const body = coverage(url.searchParams.getAll("scheme"));
    if (!unmatched.length) body.unmatched = { count: 0, sample: [] };
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  });
  return { requests, aliases };
}

test("switching views keeps the URL in sync", async ({ page }) => {
  await stub(page);
  await page.goto("/map");
  const toggle = page.getByTestId("map-view-switch");
  await toggle.getByRole("link", { name: "Coverage" }).click();
  await expect(page).toHaveURL(/view=coverage/);
  await expect(page.getByTestId("coverage-view")).toBeVisible();
  await expect(toggle.getByRole("link", { name: "Coverage" })).toHaveAttribute("aria-current", "page");
  await toggle.getByRole("link", { name: "Places" }).click();
  await expect(page).not.toHaveURL(/view=coverage/);
});

test("a saved metric=records link opens on All records", async ({ page }) => {
  await stub(page);
  await page.goto("/map?view=coverage&metric=records");
  await expect(page.getByRole("group", { name: "Colour by" }).getByRole("button", { name: "All records" })).toHaveAttribute(
    "aria-pressed",
    "true"
  );
});

test("at 1440x900 the whole map is on screen with the side column beside it", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await stub(page);
  await page.goto("/map?view=coverage");
  await expect(page.getByTestId("coverage-table")).toBeVisible();
  const panel = page.getByTestId("coverage-map-panel");
  const sideBox = await page.getByTestId("coverage-side").boundingBox();
  const mapBox = await panel.boundingBox();
  expect(mapBox && sideBox).toBeTruthy();
  expect(sideBox!.x).toBeGreaterThanOrEqual(mapBox!.x + mapBox!.width);
  expect(sideBox!.y).toBeLessThan(900);
  // The panel is sticky under the header: once the page reaches it, the whole country is on screen,
  // and the map is never cut off inside its own panel.
  await page.evaluate((top) => window.scrollTo(0, top), Math.max(0, mapBox!.y - 96));
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(0);
  const svg = await page.getByTestId("coverage-map").boundingBox();
  expect(svg).toBeTruthy();
  expect(svg!.y).toBeGreaterThanOrEqual(0);
  expect(svg!.y + svg!.height).toBeLessThanOrEqual(900);
  expect(await panel.evaluate((node) => node.scrollHeight - node.clientHeight)).toBeLessThanOrEqual(1);
});

test("filters change the map and travel in the URL", async ({ page }) => {
  const { requests } = await stub(page);
  await page.goto("/map?view=coverage");
  await expect(page.getByTestId("coverage-table")).toContainText("Barmer, Rajasthan");
  await page.getByRole("button", { name: "Skill Upgradation" }).click();
  await expect(page).toHaveURL(/scheme=SKILL_UPGRADATION/);
  await expect.poll(() => requests.some((search) => search.includes("scheme=SKILL_UPGRADATION"))).toBe(true);
});

test("opening a district shows its records and the links work", async ({ page }) => {
  await stub(page);
  await page.goto("/map?view=coverage");
  await page.getByTestId("coverage-table").getByRole("button", { name: "Barmer, Rajasthan" }).click();
  const panel = page.getByTestId("coverage-unit-panel");
  await expect(panel).toContainText("Ajrakh motifs");
  await expect(panel.getByRole("link", { name: /Ajrakh motifs/ })).toHaveAttribute("href", "/design-workshops/dw1");
  const shape = page.locator('[data-lgd="90"]');
  if (await shape.count()) {
    await page.getByRole("button", { name: "Close" }).click();
    await shape.first().click({ force: true });
    await expect(page.getByTestId("coverage-unit-panel")).toBeVisible();
  }
});

test("the gaps panel shows its counts and the caption names the depiction", async ({ page }) => {
  await stub(page);
  await page.goto("/map?view=coverage");
  await expect(page.getByTestId("gap-crafts-count")).toHaveText("1");
  await expect(page.getByTestId("gap-districts-count")).toHaveText("794");
  await expect(page.getByTestId("coverage-caption")).toHaveText(
    "External boundaries of India as depicted by Survey of India. District boundaries are indicative."
  );
});

test("an admin maps an unmatched place and it leaves the list", async ({ page }) => {
  const { aliases } = await stub(page);
  await page.goto("/map?view=coverage");
  const row = page.getByTestId("unmatched-row").first();
  await expect(row).toContainText("Barmer, Rajastan");
  await row.getByRole("button", { name: "State" }).click();
  await page.getByRole("option", { name: "Rajasthan" }).click();
  await row.getByRole("button", { name: "Map" }).click();
  await expect.poll(() => aliases.length).toBe(1);
  expect(aliases[0]).toMatchObject({ rawState: "Rajastan", rawDistrict: "Barmer", stateLgd: 8 });
  await expect(page.getByTestId("unmatched-row")).toHaveCount(0);
});

for (const width of [375, 1280]) {
  for (const dark of [false, true]) {
    test(`looks right at ${width}px ${dark ? "dark" : "light"}`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await stub(page, { dark });
      await page.goto("/map?view=coverage");
      await expect(page.getByTestId("coverage-table")).toBeVisible();
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      expect(overflow).toBeLessThanOrEqual(1);
      fs.mkdirSync(SHOTS, { recursive: true });
      await page.screenshot({ path: `${SHOTS}/coverage-${width}-${dark ? "dark" : "light"}.png`, fullPage: true });
    });
  }
}
