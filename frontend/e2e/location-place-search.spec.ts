import { expect, test, type Page, type Route } from "@playwright/test";

import { serveAddressReference } from "./fixtures/location";

/**
 * SEARCHING FOR A PLACE MOVES THE MAP. IT MUST NEVER SET THE RECORD'S VALUE.
 *
 * The location card's whole argument is one sentence — "the geocoder may offer a value; a person
 * accepts it" — and a search box is the easiest place in the app to break it. A designer types
 * "Barpali", the map flies to Odisha, and the tempting next line of code drops the pin for them.
 * That line would put a geocoder's guess into a research record with nobody in the loop, which is
 * the exact defect that put fifteen artisans' addresses in Kharagpur. So the first test here does
 * not check that the search works; it checks that the search DID NOT WRITE, and it checks it three
 * ways at once (the two coordinate boxes, the autofill notice, the suggestion chip) because those
 * are the three surfaces that would light up if it had.
 *
 * WHY THE CAMERA ASSERTION READS TILE REQUESTS. MapLibre's centre is not in the DOM, and exposing it
 * for a test would be a production seam that exists only for this file. The map does say where it is
 * looking, out loud, on the network: every tile it fetches is `/{z}/{x}/{y}` in Web Mercator, which
 * inverts to a longitude and latitude. So "the map moved to Barpali" is provable from the outside
 * with no hook in the component — and a `flyToPlace` that quietly stopped working would fail here
 * rather than passing on a map still sitting over the centre of India.
 *
 * ONE TEST USES THE LIVE GEOCODER ON PURPOSE. `NEXT_PUBLIC_MAPTILER_API_KEY` is configured in this
 * checkout, so the first test really does call MapTiler and asserts the parameters that go out
 * (`country=in`, `proximity=…`) — otherwise this spec could pass against an API that never supported
 * forward geocoding at all. The failure paths are stubbed, because there is no way to ask a live
 * service to be rate-limited on cue.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

/** A village in Bargarh district, Odisha — the case in the brief, and genuinely hard to pan to. */
const QUERY = "Barpali";

/** Every request to the forward geocoder; the reverse path is `/geocoding/{lng},{lat}.json`. */
const FORWARD_GEOCODE = /api\.maptiler\.com\/geocoding\/[^,]+\.json/;

/**
 * ONE sign-in for the whole file, replayed as the stored token.
 *
 * Five tests each driving a real login form is five round trips to an auth endpoint to establish a
 * precondition none of these tests is about, and the house map spec already learnt that lesson the
 * expensive way (two of six timed out per run, reported as a failure of the map).
 */
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
  sessionToken = await page.evaluate(() => window.localStorage.getItem("field_repo_token") ?? "");
  expect(sessionToken, "could not sign in against the API the dev server is pointed at").not.toBe("");
  await context.close();
});

/**
 * A signed-in new-artisan form with its subject map open and the search box focused BY KEYBOARD.
 *
 * The Tab is not decoration. The search box has to be the next stop after the button that reveals
 * it, or a keyboard user opens the map and lands somewhere else entirely — and the map canvas
 * itself is deliberately not tabbable (it is a pointer instrument), so if the order were wrong there
 * would be nothing obvious to catch it.
 */
/** Pages already carrying the session — `addInitScript` and `route` both STACK, and seeding one page
 *  four times would leave four copies of each running on every navigation. */
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

/** Nothing on this form may have been written by the search. The three surfaces that would show it. */
async function expectNothingWritten(page: Page) {
  await expect(page.locator("input[name='subjectLatitude']")).toHaveValue("");
  await expect(page.locator("input[name='subjectLongitude']")).toHaveValue("");
  await expect(page.getByText(/Filled in from the place you pointed at/i)).toHaveCount(0);
  await expect(page.locator("[data-location-suggestion]")).toHaveCount(0);
}

/** Web Mercator, inverted: the centre of tile `z/x/y` as a longitude and latitude. */
function tileCentre(z: number, x: number, y: number) {
  const n = 2 ** z;
  return {
    lon: ((x + 0.5) / n) * 360 - 180,
    lat: (Math.atan(Math.sinh(Math.PI * (1 - (2 * (y + 0.5)) / n))) * 180) / Math.PI
  };
}

/** Answer the forward geocoder with a fixed list, so a test about behaviour is not a test about ranking. */
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

  /** Every tile the map asks for, decoded. This is the map saying where it is looking. */
  const tiles: Array<{ z: number; lon: number; lat: number }> = [];
  page.on("request", (request) => {
    const match = /\/(\d{1,2})\/(\d+)\/(\d+)(?:@2x)?\.(?:pbf|png|jpg|jpeg|webp)/.exec(request.url());
    if (!match || !request.url().includes("maptiler.com")) return;
    const [z, x, y] = [Number(match[1]), Number(match[2]), Number(match[3])];
    tiles.push({ z, ...tileCentre(z, x, y) });
  });

  // Pass the live request through, keeping a copy: this is the only way to assert BOTH that MapTiler
  // really answers a forward query and that the bias parameters were on the request that asked.
  const asked: string[] = [];
  let answered = "";
  await page.route(FORWARD_GEOCODE, async (route) => {
    asked.push(route.request().url());
    const response = await route.fetch();
    answered = await response.text();
    await route.fulfill({ response, body: answered });
  });

  const search = await openMapAndFocusSearch(page);

  // Where the map is before anything is searched, so "it moved" is a claim about a change.
  await expect
    .poll(() => tiles.length, { timeout: 30_000, message: "the map never requested a tile" })
    .toBeGreaterThan(0);
  const tilesBefore = tiles.length;

  await page.keyboard.type(QUERY);

  const options = page.getByRole("option");
  await expect(options.first()).toBeVisible({ timeout: 30_000 });

  // The request that went out. `country` and `proximity` are what stop a cluster in Odisha being
  // outranked by a same-named town elsewhere, and they are MapTiler's own parameter names.
  expect(asked.length, "one debounced request, not one per keystroke").toBeLessThanOrEqual(2);
  const sent = new URL(asked[asked.length - 1]);
  expect(sent.searchParams.get("country")).toBe("in");
  expect(sent.searchParams.get("proximity")).toMatch(/^-?\d+(\.\d+)?,-?\d+(\.\d+)?$/);
  expect(sent.searchParams.get("limit")).toBe("8");
  expect(sent.searchParams.get("autocomplete")).toBe("true");
  expect(sent.searchParams.get("language")).toBe("en");

  // Where the first row will take the map, read from the answer rather than hard-coded: MapTiler's
  // ranking is theirs to change, and a spec that pinned it would fail on their release, not ours.
  const body = JSON.parse(answered) as { features: Array<{ center: [number, number] }> };
  const destination = body.features.filter((entry) => Array.isArray(entry.center))[0];
  expect(destination, "the live geocoder returned no usable feature for " + QUERY).toBeTruthy();

  await options.first().click();
  await expect(options).toHaveCount(0);

  // The map went there. A tile close in, centred on the chosen place — which cannot be true of a map
  // still sitting at zoom 4 over the middle of India, 400 km away.
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

  // AND THE RECORD IS UNTOUCHED. The point of the whole feature.
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

  // The combobox contract: the active option is named on the input, not merely painted on the row.
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

  /*
   * ENTER MUST NOT HAVE LEFT THIS BOX. Every record form is `<form onKeyDown={handleFormEnter}>`,
   * whose job is to walk focus to the next field or submit — so an Enter that escaped this combobox
   * would either fling the designer into the notes field or try to save a half-filled artisan, from
   * a control whose entire purpose is to look at a map without touching the record.
   */
  await expect(search).toBeFocused();
  expect(page.url()).toContain("/artisans/new");

  // Escape dismisses, and does not steal focus on the way out.
  await page.keyboard.press("ArrowDown");
  await expect(options).toHaveCount(3);
  await page.keyboard.press("Escape");
  await expect(options).toHaveCount(0);
  await expect(search).toBeFocused();

  // Nothing chosen from the keyboard wrote anything either.
  await expectNothingWritten(page);
});

/**
 * THE FOUR ENDINGS, AND WHY THEY MAY NOT SHARE A SENTENCE.
 *
 * A designer in a village with one bar who is told "no such place" goes looking for a different
 * spelling of a village they are standing in. A designer whose tenant is out of quota who is told
 * "no connection" walks outside. Each wrong message costs the wrong thing, which is why this table
 * asserts they are all DIFFERENT from each other and not merely that each is non-empty.
 */
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
    expect: /not answering just now/i
  },
  {
    name: "the key is refused",
    arrange: (page: Page) => page.route(FORWARD_GEOCODE, (route) => route.fulfill({ status: 403, body: "" })),
    expect: /refused this request/i
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

    // A failure is never a silent empty list, and never a written value.
    await expect(page.getByRole("option")).toHaveCount(0);
    await expectNothingWritten(page);
  });
}

test("the four endings are actually distinguishable from one another", async ({ page }) => {
  test.setTimeout(180_000);
  const said: string[] = [];
  const status = page.locator("[data-place-search-status]");

  for (const ending of ENDINGS) {
    // `page.route` handlers STACK and the first registered wins, so without this every ending after
    // the first would be answered by whichever was arranged first — and the test would "prove" four
    // identical sentences. One page rather than four contexts: a context created by hand here shares
    // the trace recorder's artifact directory and tears its files out from under it on close.
    await page.unroute(FORWARD_GEOCODE);
    await ending.arrange(page);
    await openMapAndFocusSearch(page);
    await page.keyboard.type(QUERY);
    /*
     * Wait for the sentence to SETTLE, and deliberately not by matching what it should say — that is
     * the four tests above, and repeating them here would make this test unable to fail differently
     * from them. "Searching…" occupies this same element while the request is out, so reading it too
     * early collects four identical waiting messages and calls them indistinguishable.
     */
    await expect(status).not.toHaveText(/Searching/, { timeout: 30_000 });
    said.push((await status.textContent())?.trim() ?? "");
  }

  expect(said.filter(Boolean)).toHaveLength(ENDINGS.length);
  expect(new Set(said).size, `four endings, ${new Set(said).size} distinct sentences: ${said.join(" | ")}`).toBe(
    ENDINGS.length
  );
});
