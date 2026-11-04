import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

const STAGE_KEY = "INTRODUCTORY_ADMIN_DOCUMENTATION";

async function apiToken(page: Page): Promise<string> {
  const response = await page.request.post(`${API}/api/auth/login`, {
    data: { email: EMAIL, password: PASSWORD }
  });
  expect(response.ok(), "sign in through the API").toBeTruthy();
  return (await response.json()).accessToken as string;
}

test("the formatting bar is docked under the field's title and spans the writing surface", async ({ page }) => {
  const token = await apiToken(page);
  const auth = { Authorization: `Bearer ${token}` };
  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: auth,
    data: { title: `Toolbar layout spec ${Date.now()}` }
  });
  expect(created.ok(), "create a design workshop").toBeTruthy();
  const workshopId = (await created.json()).id as string;

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);

  const editor = page.locator('[contenteditable="true"]').first();
  await expect(editor, "the rich-text editor mounted").toBeVisible({ timeout: 60_000 });

  const toolbar = page.getByRole("toolbar", { name: /text formatting/i }).first();
  await expect(toolbar, "no bar before the field is focused").toHaveCount(0);

  await editor.click();
  await expect(toolbar, "the bar appears for the focused field").toBeVisible();

  const label = page.locator("label, .field-label").filter({ hasText: /\S/ }).first();
  await expect(label).toBeVisible();

  const [bar, surface, title] = await Promise.all([
    toolbar.boundingBox(),
    editor.boundingBox(),
    label.boundingBox()
  ]);
  expect(bar && surface && title, "all three boxes measurable").toBeTruthy();
  if (!bar || !surface || !title) return;

  expect(bar.y, "the bar starts below the field's title").toBeGreaterThan(title.y + title.height - 1);
  expect(bar.y + bar.height, "the bar ends above the writing surface").toBeLessThanOrEqual(surface.y + 1);

  const overlapY = Math.max(0, Math.min(bar.y + bar.height, title.y + title.height) - Math.max(bar.y, title.y));
  const overlapX = Math.max(0, Math.min(bar.x + bar.width, title.x + title.width) - Math.max(bar.x, title.x));
  expect(overlapX * overlapY, "the bar covers none of the field's title").toBe(0);

  expect(Math.abs(bar.x - surface.x), "the bar's left edge lines up with the box").toBeLessThanOrEqual(2);
  expect(
    Math.abs(bar.width - surface.width),
    `the bar spans the writing surface (bar ${bar.width}px vs box ${surface.width}px)`
  ).toBeLessThanOrEqual(2);
});

test("the find card opens below the formatting bar, not over it", async ({ page }) => {
  const token = await apiToken(page);
  const auth = { Authorization: `Bearer ${token}` };
  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: auth,
    data: { title: `Toolbar find spec ${Date.now()}` }
  });
  const workshopId = (await created.json()).id as string;

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);

  const editor = page.locator('[contenteditable="true"]').first();
  await expect(editor).toBeVisible({ timeout: 60_000 });
  await editor.click();

  const toolbar = page.getByRole("toolbar", { name: /text formatting/i }).first();
  await expect(toolbar).toBeVisible();

  await toolbar.getByRole("button", { name: /find/i }).first().click();

  const findBox = page.getByRole("textbox", { name: "Find" });
  await expect(findBox, "the find card opened").toBeVisible();

  const [bar, card, surface] = await Promise.all([
    toolbar.boundingBox(),
    findBox.boundingBox(),
    editor.boundingBox()
  ]);
  expect(bar && card && surface).toBeTruthy();
  if (!bar || !card || !surface) return;

  expect(card.y, "the find card is below the formatting bar").toBeGreaterThanOrEqual(bar.y + bar.height - 1);
  expect(card.y + card.height, "the find card is above the writing surface").toBeLessThanOrEqual(surface.y + 1);
});

test("focusing a field does not shove the page under the designer", async ({ page }) => {
  const token = await apiToken(page);
  const auth = { Authorization: `Bearer ${token}` };
  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: auth,
    data: { title: `Toolbar shift spec ${Date.now()}` }
  });
  const workshopId = (await created.json()).id as string;

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);

  const editor = page.locator('[contenteditable="true"]').first();
  await expect(editor).toBeVisible({ timeout: 60_000 });

  await editor.scrollIntoViewIfNeeded();
  await page.waitForTimeout(150);

  const before = await editor.boundingBox();
  await editor.click();
  await expect(page.getByRole("toolbar", { name: /text formatting/i }).first()).toBeVisible();
  await page.waitForTimeout(250);
  const after = await editor.boundingBox();

  expect(before && after).toBeTruthy();
  if (!before || !after) return;

  expect(
    Math.abs(after.y - before.y),
    `the writing surface stayed put as the bar docked (moved ${Math.round(after.y - before.y)}px)`
  ).toBeLessThanOrEqual(4);
});
