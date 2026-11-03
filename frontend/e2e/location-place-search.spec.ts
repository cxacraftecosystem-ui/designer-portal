import { expect, test, type Page, type Route } from "@playwright/test";

import { serveAddressReference } from "./fixtures/location";

import { sessionTokenOf } from "./support/session";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

const QUERY = "Barpali";

const FORWARD_GEOCODE = /api\.maptiler\.com\/geocoding\/[^,]+\.json/;

let sessionToken = "";

test.beforeAll(async ({ browser }) => {
  test.setTimeout(180_000);
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto("/login", { timeout: 60_000 });
  await page.getByPlaceholder("Enter your email").fill(EMAIL);
  await page.getByPlaceholder("Enter your password").fill(PASSWORD);
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
  sessionToken = await sessionTokenOf(page);
  expect(sessionToken, "could not sign in against the API the dev server is pointed at").not.toBe("");
  await context.close();
});

const seeded = new WeakSet<Page>();

async function openMapAndFocusSearch(page: Page) {
  if (!seeded.has(page)) {
    seeded.add(page);
    await page.addInitScript(
      ([token]) => window.localStorage.setItem("field_repo_token", token),
      [sessionToken]
    );
    await serveAddressReference(page);
  }
  await page.goto("/artisans/new", { timeout: 60_000 });

  const reveal = page.getByRole("button", { name: /point to the exact place/i });
  await expect(reveal).toBeVisible({ timeout: 60_000 });
  await reveal.click();

  const search = page.getByRole("combobox", { name: /search for a place to move the map/i });
  await expect(search).toBeVisible({ timeout: 30_000 });

  await reveal.focus();
  await page.keyboard.press("Tab");
  await expect(search).toBeFocused();
  return search;
}

async function expectNothingWritten(page: Page) {
  await expect(page.locator("input[name='subjectLatitude']")).toHaveValue("");
  await expect(page.locator("input[name='subjectLongitude']")).toHaveValue("");
  await expect(page.getByText(/Filled in from the place you pointed at/i)).toHaveCount(0);
  await expect(page.locator("[data-location-suggestion]")).toHaveCount(0);
}

function tileCentre(z: number, x: number, y: number) {
  const n = 2 ** z;
  return {
    lon: ((x + 0.5) / n) * 360 - 180,
    lat: (Math.atan(Math.sinh(Math.PI * (1 - (2 * (y + 0.5)) / n))) * 180) / Math.PI
  };
}

async function stubResults(page: Page, features: unknown[]) {
  await page.route(FORWARD_GEOCODE, (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ type: "FeatureCollection", features })
    })
  );
}

function feature(id: string, text: string, placeName: string, center: [number, number]) {
  return { id, text, place_name: placeName, place_type: ["place"], center };
}

test("a search moves the map and writes nothing into the record", async ({ page }) => {
  test.setTimeout(120_000);

  const tiles: Array<{ z: number; lon: number; lat: number }> = [];
  page.on("request", (request) => {
    const match = /\/(\d{1,2})\/(\d+)\/(\d+)(?:@2x)?\.(?:pbf|png|jpg|jpeg|webp)/.exec(request.url());
    if (!match || !request.url().includes("maptiler.com")) return;
    const [z, x, y] = [Number(match[1]), Number(match[2]), Number(match[3])];
    tiles.push({ z, ...tileCentre(z, x, y) });
  });

  const asked: string[] = [];
  let answered = "";
  await page.route(FORWARD_GEOCODE, async (route) => {
    asked.push(route.request().url());
    const response = await route.fetch();
    answered = await response.text();
    await route.fulfill({ response, body: answered });
  });

  const search = await openMapAndFocusSearch(page);

  await expect
    .poll(() => tiles.length, { timeout: 30_000, message: "the map never requested a tile" })
    .toBeGreaterThan(0);
  const tilesBefore = tiles.length;

  await page.keyboard.type(QUERY);

  const options = page.getByRole("option");
  await expect(options.first()).toBeVisible({ timeout: 30_000 });

  expect(asked.length, "one debounced request, not one per keystroke").toBeLessThanOrEqual(2);
  const sent = new URL(asked[asked.length - 1]);
  expect(sent.searchParams.get("country")).toBe("in");
  expect(sent.searchParams.get("proximity")).toMatch(/^-?\d+(\.\d+)?,-?\d+(\.\d+)?$/);
  expect(sent.searchParams.get("limit")).toBe("8");
  expect(sent.searchParams.get("autocomplete")).toBe("true");
  expect(sent.searchParams.get("language")).toBe("en");

  const body = JSON.parse(answered) as { features: Array<{ center: [number, number] }> };
  const destination = body.features.filter((entry) => Array.isArray(entry.center))[0];
  expect(destination, "the live geocoder returned no usable feature for " + QUERY).toBeTruthy();

  await options.first().click();
  await expect(options).toHaveCount(0);

  await expect
    .poll(
      () =>
        tiles
          .slice(tilesBefore)
          .some(
            (tile) =>
              tile.z >= 10 &&
              Math.abs(tile.lon - destination.center[0]) < 0.5 &&
              Math.abs(tile.lat - destination.center[1]) < 0.5
          ),
      { timeout: 30_000, message: "no tile was fetched near the searched place — the map did not fly" }
    )
    .toBe(true);

  await expectNothingWritten(page);
});

test("a place can be chosen with the keyboard alone, and Enter does not reach the form", async ({ page }) => {
  test.setTimeout(120_000);

  await stubResults(page, [
    feature("place.1", "Barpali", "Barpali, Paikmal, Odisha, India", [82.7458, 20.894]),
    feature("place.2", "Barpali", "Barpali, Malkharoda Tahsil, Chhattisgarh, India", [83.0078, 21.768]),
    feature("place.3", "Barapali", "Barapali, Barapali, Odisha, India", [83.5872, 21.19])
  ]);

  const search = await openMapAndFocusSearch(page);
  await page.keyboard.type(QUERY);

  const options = page.getByRole("option");
  await expect(options).toHaveCount(3, { timeout: 30_000 });

  const firstId = await options.nth(0).getAttribute("id");
  const secondId = await options.nth(1).getAttribute("id");
  await expect(search).toHaveAttribute("aria-activedescendant", firstId ?? "");
  await expect(search).toHaveAttribute("aria-expanded", "true");

  await page.keyboard.press("ArrowDown");
  await expect(search).toHaveAttribute("aria-activedescendant", secondId ?? "");
  await expect(options.nth(1)).toHaveAttribute("aria-selected", "true");
  await expect(options.nth(0)).toHaveAttribute("aria-selected", "false");

  await page.keyboard.press("ArrowUp");
  await expect(search).toHaveAttribute("aria-activedescendant", firstId ?? "");

  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Enter");
  await expect(options).toHaveCount(0);
  await expect(search).toHaveAttribute("aria-expanded", "false");

  await expect(search).toBeFocused();
  expect(page.url()).toContain("/artisans/new");

  await page.keyboard.press("ArrowDown");
  await expect(options).toHaveCount(3);
  await page.keyboard.press("Escape");
  await expect(options).toHaveCount(0);
  await expect(search).toBeFocused();

  await expectNothingWritten(page);
});

const ENDINGS = [
  {
    name: "no results",
    arrange: (page: Page) => stubResults(page, []),
    expect: /No place in India is called/i
  },
  {
    name: "no connection",
    arrange: (page: Page) => page.route(FORWARD_GEOCODE, (route) => route.abort("failed")),
    expect: /No connection/i
  },
  {
    name: "the service is down or rate-limited",
    arrange: (page: Page) => page.route(FORWARD_GEOCODE, (route) => route.fulfill({ status: 429, body: "" })),
    expect: /not responding just now/i
  },
  {
    name: "the key is refused",
    arrange: (page: Page) => page.route(FORWARD_GEOCODE, (route) => route.fulfill({ status: 403, body: "" })),
    expect: /isn't available right now/i
  }
] as const;

for (const ending of ENDINGS) {
  test(`the search says "${ending.name}" in its own words`, async ({ page }) => {
    test.setTimeout(120_000);
    await ending.arrange(page);
    await openMapAndFocusSearch(page);
    await page.keyboard.type(QUERY);

    const status = page.locator("[data-place-search-status]");
    await expect(status).toBeVisible({ timeout: 30_000 });
    await expect(status).toHaveText(ending.expect);

    await expect(page.getByRole("option")).toHaveCount(0);
    await expectNothingWritten(page);
  });
}

test("the four endings are actually distinguishable from one another", async ({ page }) => {
  test.setTimeout(180_000);
  const said: string[] = [];
  const status = page.locator("[data-place-search-status]");

  for (const ending of ENDINGS) {
    await page.unroute(FORWARD_GEOCODE);
    await ending.arrange(page);
    await openMapAndFocusSearch(page);
    await page.keyboard.type(QUERY);
    await expect(status).not.toHaveText(/Searching/, { timeout: 30_000 });
    said.push((await status.textContent())?.trim() ?? "");
  }

  expect(said.filter(Boolean)).toHaveLength(ENDINGS.length);
  expect(new Set(said).size, `four endings, ${new Set(said).size} distinct sentences: ${said.join(" | ")}`).toBe(
    ENDINGS.length
  );
});
