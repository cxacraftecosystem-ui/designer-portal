import { expect, test } from "@playwright/test";

const NONSENSE = "/this-route-does-not-exist";
const NESTED = "/this-route/does-not/exist-either";

test("an unmatched URL renders the app's own 404, with a 404 status", async ({ page }) => {
  const response = await page.goto(NONSENSE);

  expect(response?.status()).toBe(404);

  await expect(page.getByRole("heading", { level: 1, name: "Page not found" })).toBeVisible();
  await expect(page.getByTestId("error-not-found")).toContainText("The link may be out of date, or the page has moved.");
});

test("a nested unmatched URL lands on the same page", async ({ page }) => {
  const response = await page.goto(NESTED);

  expect(response?.status()).toBe(404);
  await expect(page.getByRole("heading", { level: 1, name: "Page not found" })).toBeVisible();
});

test("a signed-out visitor is offered a way back and home, and told which app they are in", async ({ page }) => {
  await page.goto(NONSENSE);

  await expect(page.getByRole("button", { name: "Go back" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Home" })).toHaveAttribute("href", "/");

  await expect(page.getByRole("main")).toContainText("Design Prototype Workshop");
});

test("a real route still renders itself", async ({ page }) => {
  const response = await page.goto("/login");

  expect(response?.status()).toBe(200);
  await expect(page.getByRole("heading", { level: 1, name: "Page not found" })).toHaveCount(0);
});
