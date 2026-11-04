import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

const WORKSHOP = process.env.E2E_DW_ID ?? "cmsik2jg8000eh8xc1lcy661a";

const PHRASE = "indigo depth";
const STAGE_KEY = "DATA_QUALITY_ARCHIVE";
const ENTITY_KEY = "mediaQualityFlag";
const FIELD_KEY = "note";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

const searchBox = (page: Page) => page.getByRole("searchbox", { name: "Search this workshop" });

const results = (page: Page) => page.getByRole("list", { name: "Search results" }).getByRole("listitem");

test("a phrase in stage 21 is found and navigated to from the overview page, with no connection", async ({ page }) => {
  await signIn(page);

  await page.goto(`/design-workshops/${WORKSHOP}`);
  await expect(
    page.getByRole("link", { name: /Data Quality & Archive/ }),
    "the 22-stage index drew, so this account can read the flagship workshop (it needs ADMIN)"
  ).toBeVisible({ timeout: 60_000 });
  await expect(page.getByText(/Searches every written answer on this device/)).toContainText(/across 22 stages/);

  const alive = /\/api\/(me|auth\/)/;
  await page.route("**/api/**", (route) =>
    alive.test(route.request().url()) ? route.continue() : route.abort("internetdisconnected")
  );
  await page.reload();

  await expect(searchBox(page), "the search is usable from the local copy alone").toBeEnabled({ timeout: 60_000 });
  await expect(
    page.getByText("You're offline, so this is the copy saved on this device.", { exact: false }),
    "the page knows it is offline, so what follows is genuinely being answered from the device"
  ).toBeVisible();

  const apiCalls: string[] = [];
  page.on("request", (request) => {
    if (request.url().includes("/api/") && !alive.test(request.url())) {
      apiCalls.push(`${request.method()} ${request.url()}`);
    }
  });

  await searchBox(page).fill(PHRASE);

  const result = results(page).filter({ hasText: "21. Data Quality & Archive" });
  await expect(result, "the phrase was found three stages below the fold").toHaveCount(1);
  await expect(result).toContainText("Media quality flags");
  await expect(result).toContainText("DPDW-0417/IMG_20260119_1142_dyehouse_03.jpg");
  await expect(result).toContainText("Note");
  await expect(result.locator("mark").first()).toHaveText(/indigo/i);
  await expect(result, "the snippet is a window, with the rest of the note elided").toContainText("…");

  expect(apiCalls, `searching must cost nothing: the page sent ${JSON.stringify(apiCalls)}`).toEqual([]);

  await result.getByRole("link").click();
  await page.waitForURL(new RegExp(`/design-workshops/${WORKSHOP}/stages/${STAGE_KEY}\\?`), { timeout: 60_000 });
  const focus = new URL(page.url()).searchParams;
  expect(focus.get("find")).toBe(`${ENTITY_KEY}.${FIELD_KEY}`);
  expect(focus.get("row"), "the link names the row, or the field is in a panel nobody opened").toBeTruthy();

  const address = `[data-dw-field="${ENTITY_KEY}.${FIELD_KEY}"][data-dw-row="${focus.get("row")}"]`;

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

  await expect(results(page)).toHaveCount(0);

  await searchBox(page).fill("i");
  await expect(page.getByText(/Type at least \d+ characters\./)).toBeVisible();

  await searchBox(page).fill("zzzqqxnothinghere");
  await expect(page.getByText(/Nothing in this workshop holds that word\./)).toBeVisible();
  await expect(page.getByText(/Every answer saved on this device was searched — \d+ of them\./)).toBeVisible();
});
