import { expect, test } from "@playwright/test";

const NONSENSE = "/this-route-does-not-exist";
const RECORD_SHAPED = "/crafts/cmsik2jg8000eh8xc1lcy661a";

test("an unmatched URL renders the app's own 404, with a 404 status", async ({ page }) => {
  const response = await page.goto(NONSENSE);

  expect(response?.status()).toBe(404);

  await expect(page.getByRole("heading", { level: 1, name: "There is nothing at this address" })).toBeVisible();
});

test("the 404 names the deleted record without confirming it ever existed", async ({ page }) => {
  await page.goto(NONSENSE);

  const panel = page.getByRole("main");

  await expect(panel).toContainText("it may have been deleted, or it may not be one this account can open");

  await expect(panel).not.toContainText("Nothing here can tell you which");
  await expect(panel).not.toContainText("on purpose");
});

test("an ordinary link to a record lands on it too, and shows the address that was asked for", async ({ page }) => {
  const response = await page.goto(RECORD_SHAPED);

  expect(response?.status()).toBe(404);
  await expect(page.getByRole("heading", { level: 1, name: "There is nothing at this address" })).toBeVisible();

  await expect(page.getByRole("main")).toContainText(RECORD_SHAPED);
});

test("a signed-out visitor is offered the front door, and told which app they are in", async ({ page }) => {
  await page.goto(NONSENSE);

  await expect(page.getByRole("link", { name: "Sign in" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Go to the home page" })).toBeVisible();

  await expect(page.getByRole("link", { name: "Back to dashboard" })).toHaveCount(0);

  await expect(page.getByRole("main")).toContainText("Design Prototype Workshop");
});

test("a real route still renders itself", async ({ page }) => {
  const response = await page.goto("/login");

  expect(response?.status()).toBe(200);
  await expect(page.getByRole("heading", { level: 1, name: "There is nothing at this address" })).toHaveCount(0);
});
