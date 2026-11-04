import { expect, test } from "@playwright/test";

import { CREDENTIALS_MISSING, apiToken, signInWithToken } from "./support/session";

const WORKSHOP = process.env.E2E_STAGE_OFFLINE_WORKSHOP ?? "";
const STAGE = process.env.E2E_STAGE_KEY ?? "WORKSHOP_SETUP";
const ERROR_SCREEN = /Something went wrong|This page could not be shown/i;

test.describe("stage form tools offline", () => {
  test.skip(!WORKSHOP, "Set E2E_STAGE_OFFLINE_WORKSHOP to a workshop id (see the file header).");
  test.skip(Boolean(CREDENTIALS_MISSING), CREDENTIALS_MISSING);
  test.setTimeout(180_000);

  test("a tool first opened offline after the form warmed up opens, and the form stays", async ({ browser, request }) => {
    const token = await apiToken(request);
    const context = await browser.newContext();
    const page = await context.newPage();
    await signInWithToken(page, token);
    await page.goto(`/design-workshops/${WORKSHOP}/stages/${STAGE}`);
    const save = page.getByRole("button", { name: /Save stage/ });
    await expect(save).toBeVisible({ timeout: 60_000 });
    await page.waitForFunction(() => Boolean(navigator.serviceWorker?.controller), undefined, { timeout: 30_000 });
    await page.waitForTimeout(20_000);

    await context.setOffline(true);
    await page.getByRole("button", { name: "Continue offline" }).click();
    await page.getByRole("button", { name: /Document preview/ }).click();
    await expect(page.locator("[data-stage-preview]")).toBeVisible();
    await expect(page.getByText("It needs a connection the first time it is used on this device.")).toHaveCount(0);
    await expect(save).toBeVisible();
    await expect(page.getByText(ERROR_SCREEN)).toHaveCount(0);
    await context.close();
  });

  test("a tool whose script cannot be fetched says so in its own place, and the form stays", async ({ browser, request }) => {
    const token = await apiToken(request);
    const context = await browser.newContext({ serviceWorkers: "block" });
    const page = await context.newPage();
    await signInWithToken(page, token);
    await page.goto(`/design-workshops/${WORKSHOP}/stages/${STAGE}`);
    const save = page.getByRole("button", { name: /Save stage/ });
    await expect(save).toBeVisible({ timeout: 60_000 });

    await context.route(/\/_next\/static\/chunks\//, (route) => route.abort("internetdisconnected"));
    await page.getByRole("button", { name: /Document preview/ }).click();

    const panel = page.locator("[data-stage-preview]");
    await expect(panel.getByText("It needs a connection the first time it is used on this device.")).toBeVisible({ timeout: 30_000 });
    await expect(panel.getByRole("button", { name: "Try again" })).toBeVisible();
    await expect(save).toBeVisible();
    await expect(page.getByText(ERROR_SCREEN)).toHaveCount(0);

    await panel.getByRole("button", { name: /Document preview/ }).click();
    await expect(panel.getByText("It needs a connection the first time it is used on this device.")).toHaveCount(0);

    await context.unroute(/\/_next\/static\/chunks\//);
    await panel.getByRole("button", { name: /Document preview/ }).click();
    await panel.getByRole("button", { name: "Try again" }).click();
    await expect(panel.getByText("It needs a connection the first time it is used on this device.")).toHaveCount(0, { timeout: 30_000 });
    await context.close();
  });
});
