import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

/**
 * Searching a whole design workshop from its overview page, WITH THE NETWORK BLOCKED.
 *
 * WHAT THIS IS DEFENDING. A workshop is 22 stages, 43 entities and ~496 fields, and until now the
 * only way to find where something was written was to open the stages one at a time. The answer is
 * `lib/workshopSearch`, an index over the draft `lib/designWorkshopStore` already holds in
 * IndexedDB — and the whole value of building it that way is that it works in the village, so the
 * spec proves it in the one condition that matters. Every request to the API is aborted before the
 * search is used, and the spec then asserts that using it issues NONE.
 *
 * WHY IT HAS TO BE A BROWSER TEST. `e2e/design-workshop-search-unit.spec.ts` already proves the
 * pure module: the tokeniser, the fold, the REF resolution, the snippet, the cap. None of that
 * catches the wiring, and the wiring is where this feature can be quietly useless — an index that
 * is never built, a result whose link points at a stage that opens with nothing marked on it, a row
 * that stays collapsed over the field the designer was sent to. The phrase used is three levels
 * down on purpose: stage 21 of 22, inside a collection row that renders COLLAPSED, in a field that
 * does not exist in the DOM at all until that row is opened.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

/**
 * The fully-populated workshop: 22 stages, 270 rows. It was created by a THIRD account, so a
 * DESIGNER-role sign-in gets 404 for it — run this as an ADMIN.
 */
const WORKSHOP = process.env.E2E_DW_ID ?? "cmsik2jg8000eh8xc1lcy661a";

/**
 * A phrase that occurs EXACTLY ONCE in the whole workshop, in stage 21's media-quality notes:
 * "…the tied hanks reading almost black and the indigo depth not judgeable."
 *
 * Chosen for three properties, all of which the assertions below depend on: it is unique, so a hit
 * proves the right field was found rather than a plausible one; it is in stage 21, so no part of it
 * is on screen when the search is typed; and it lives on a COLLECTION row, so reaching it requires
 * the row to be opened by the link rather than by the designer.
 */
const PHRASE = "indigo depth";
const STAGE_KEY = "DATA_QUALITY_ARCHIVE";
const ENTITY_KEY = "mediaQualityFlag";
const FIELD_KEY = "note";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

const searchBox = (page: Page) => page.getByRole("searchbox", { name: "Search this workshop" });

/**
 * The result rows, and they are addressed through the list's own accessible name rather than as
 * "every `<li>` on the page" — the 22-stage index below is a list of list items too, and a bare
 * `getByRole("listitem")` matches "Data Quality & Archive" there whether the search found anything
 * or not, which would make the "an empty query shows nothing" assertion pass for the wrong reason.
 */
const results = (page: Page) => page.getByRole("list", { name: "Search results" }).getByRole("listitem");

test("a phrase in stage 21 is found and navigated to from the overview page, with no connection", async ({ page }) => {
  await signIn(page);

  // ONLINE FIRST, once — this is the sync that puts the workshop on the device. Everything after it
  // is what a designer has on a laptop they opened in a courtyard three days later.
  await page.goto(`/design-workshops/${WORKSHOP}`);
  await expect(
    page.getByRole("link", { name: /Data Quality & Archive/ }),
    "the 22-stage index drew, so this account can read the flagship workshop (it needs ADMIN)"
  ).toBeVisible({ timeout: 60_000 });
  // The panel prints what it has indexed, and it must have indexed the whole workshop — not the
  // header alone, which is what a page that indexed before the draft landed would report.
  await expect(page.getByText(/Searches every written answer on this device/)).toContainText(/across 22 stages/);

  // FROM HERE THERE IS NO NETWORK, for everything this feature could possibly reach for. `abort`
  // rather than a 500, because a dropped connection is what this is about and the two reach
  // different branches of `lib/offline` — the same choice `location-offline-state.spec.ts` makes.
  //
  // `/me` IS LEFT ALONE, and the exemption is deliberate rather than a convenience. Cutting it does
  // not simulate a field laptop, it simulates a signed-out one: `AuthProvider` cannot confirm the
  // session, the protected layout has no user, and the browser lands on `/login` — which tests the
  // auth bootstrap, not the search. Every route the workshop, the registry, the stages, the
  // reference pickers and the report reader use is gone, which is the surface that matters here.
  const alive = /\/api\/(me|auth\/)/;
  await page.route("**/api/**", (route) =>
    alive.test(route.request().url()) ? route.continue() : route.abort("internetdisconnected")
  );
  await page.reload();

  await expect(searchBox(page), "the search is usable from the local copy alone").toBeEnabled({ timeout: 60_000 });
  await expect(
    page.getByText("There is no connection, so this is the copy saved in this browser.", { exact: false }),
    "the page knows it is offline, so what follows is genuinely being answered from the device"
  ).toBeVisible();

  // Count every request the page makes from the moment the box is ready. Typing into it must add
  // none: an index over data already on the device that still asked a server for the answer would
  // be the feature failing in exactly the condition it exists for.
  const apiCalls: string[] = [];
  page.on("request", (request) => {
    if (request.url().includes("/api/") && !alive.test(request.url())) {
      apiCalls.push(`${request.method()} ${request.url()}`);
    }
  });

  await searchBox(page).fill(PHRASE);

  const result = results(page).filter({ hasText: "21. Data Quality & Archive" });
  await expect(result, "the phrase was found three stages below the fold").toHaveCount(1);
  // The result says WHERE, in the same words the form uses: stage number and title, entity title,
  // the row's own title and the field's label.
  await expect(result).toContainText("Media quality flags");
  await expect(result).toContainText("DPDW-0417/IMG_20260119_1142_dyehouse_03.jpg");
  await expect(result).toContainText("Note");
  // …and the snippet quotes the sentence with the matched words marked, not the whole field.
  await expect(result.locator("mark").first()).toHaveText(/indigo/i);
  await expect(result, "the snippet is a window, with the rest of the note elided").toContainText("…");

  expect(apiCalls, `searching must cost nothing: the page sent ${JSON.stringify(apiCalls)}`).toEqual([]);

  // NAVIGATE. A result a designer cannot get to is not a result.
  await result.getByRole("link").click();
  await page.waitForURL(new RegExp(`/design-workshops/${WORKSHOP}/stages/${STAGE_KEY}\\?`), { timeout: 60_000 });
  const focus = new URL(page.url()).searchParams;
  expect(focus.get("find")).toBe(`${ENTITY_KEY}.${FIELD_KEY}`);
  expect(focus.get("row"), "the link names the row, or the field is in a panel nobody opened").toBeTruthy();

  // THE FIELD ITSELF. This element does not exist until the collection row has been opened, so its
  // presence is the proof that the link opened it — still with every request aborted.
  const address = `[data-dw-field="${ENTITY_KEY}.${FIELD_KEY}"][data-dw-row="${focus.get("row")}"]`;

  // MARKED, not merely scrolled to. `data-flash` is the `.fr-flash-row` contract, whose outline is
  // the half of the signal that survives `prefers-reduced-motion` — a field that was only scrolled
  // to leaves a designer looking at a form of thirty boxes with nothing saying which one they asked
  // for. The attribute is DELIBERATELY TRANSIENT (`FLASH_MS`), so it is waited for as part of the
  // selector rather than asserted on an element already found: two sequential awaits let the
  // window close between them, which is a flake in the spec and not a defect in the page.
  await expect(
    page.locator(`${address}[data-flash="true"]`),
    "the row the hit was in opened itself, and the field says it is the one that was asked for"
  ).toBeVisible({ timeout: 30_000 });

  await expect(
    page.locator(address).locator("textarea"),
    "and it holds the sentence that was searched for"
  ).toHaveValue(new RegExp(PHRASE, "i"));
});

test("an empty query shows nothing, and a query with no match says so rather than showing an empty box", async ({
  page
}) => {
  await signIn(page);
  await page.goto(`/design-workshops/${WORKSHOP}`);
  await expect(searchBox(page)).toBeEnabled({ timeout: 60_000 });

  // An empty box lists nothing. The opposite — listing all 1,393 answers — is the workshop again in
  // a smaller box, and it is what a naive filter over an array does.
  await expect(results(page)).toHaveCount(0);

  // A one-character query is REFUSED and says so, rather than answering with a fifth of the workshop.
  await searchBox(page).fill("i");
  await expect(page.getByText(/Type at least \d+ characters\./)).toBeVisible();

  // And a real miss names what was searched, so "nothing found" can never be confused with
  // "nothing was searched" — the failure this repository has shipped more often than any other.
  await searchBox(page).fill("zzzqqxnothinghere");
  await expect(page.getByText(/Nothing in this workshop holds that word\./)).toBeVisible();
  await expect(page.getByText(/Every answer saved on this device was searched — \d+ of them\./)).toBeVisible();
});
