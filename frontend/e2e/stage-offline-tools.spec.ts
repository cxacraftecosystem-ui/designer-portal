import { expect, test } from "@playwright/test";

import { CREDENTIALS_MISSING, apiToken, signInWithToken } from "./support/session";

/**
 * THE STAGE FORM'S ON-DEMAND TOOLS, OPENED FOR THE FIRST TIME WITH NO SIGNAL.
 *
 * The signature pad, the scanner, the record forms and the document preview load their script when
 * first shown (`components/designworkshop/deferredTool.tsx`). Two promises are pinned here, against a
 * PRODUCTION build (the service worker is registered only there):
 *
 * 1. Once the form has been up a few quiet seconds online, the preview's script is in the service
 *    worker's cache, so opening it offline for the first time works and the form stays on screen.
 * 2. When a script really cannot be fetched (the worker blocked, the chunks refused — the "never
 *    warmed" and "old tab after a deploy" cases), the tool says so in its own place, the form stays on
 *    screen, and "Try again" is offered. Before `deferredTool`, the page's error screen replaced the
 *    whole form.
 *
 * Needs a seeded workshop: `E2E_STAGE_OFFLINE_WORKSHOP=<id>` (any workshop the account can open, for
 * example one `e2e/stage-form-speed.spec.ts` seeded), plus `E2E_EMAIL` / `E2E_PASSWORD`, against
 * `next build && next start`. `E2E_STAGE_KEY` picks the stage (default the first).
 */

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
    // Controlled by the worker, then left alone long enough for the warming queue to run.
    await page.waitForFunction(() => Boolean(navigator.serviceWorker?.controller), undefined, { timeout: 30_000 });
    await page.waitForTimeout(20_000);

    await context.setOffline(true);
    // The app's own "You are offline" notice comes first; the designer carries on past it.
    await page.getByRole("button", { name: "Continue offline" }).click();
    await page.getByRole("button", { name: /Document preview/ }).click();
    await expect(page.locator("[data-stage-preview]")).toBeVisible();
    // The preview's own script came from the cache: no failure panel in its place.
    await expect(page.getByText("It needs a connection the first time it is used on this device.")).toHaveCount(0);
    await expect(save).toBeVisible();
    await expect(page.getByText(ERROR_SCREEN)).toHaveCount(0);
    await context.close();
  });

  test("a tool whose script cannot be fetched says so in its own place, and the form stays", async ({ browser, request }) => {
    const token = await apiToken(request);
    // No worker, so nothing is served from its cache and the refusal below is what the page meets.
    const context = await browser.newContext({ serviceWorkers: "block" });
    const page = await context.newPage();
    await signInWithToken(page, token);
    await page.goto(`/design-workshops/${WORKSHOP}/stages/${STAGE}`);
    const save = page.getByRole("button", { name: /Save stage/ });
    await expect(save).toBeVisible({ timeout: 60_000 });

    // From now on every script not already loaded is refused — no signal, or a hash gone after a deploy.
    await context.route(/\/_next\/static\/chunks\//, (route) => route.abort("internetdisconnected"));
    await page.getByRole("button", { name: /Document preview/ }).click();

    const panel = page.locator("[data-stage-preview]");
    await expect(panel.getByText("It needs a connection the first time it is used on this device.")).toBeVisible({ timeout: 30_000 });
    await expect(panel.getByRole("button", { name: "Try again" })).toBeVisible();
    await expect(save).toBeVisible();
    await expect(page.getByText(ERROR_SCREEN)).toHaveCount(0);

    // The panel can still be closed while it says so.
    await panel.getByRole("button", { name: /Document preview/ }).click();
    await expect(panel.getByText("It needs a connection the first time it is used on this device.")).toHaveCount(0);

    // With the connection back, "Try again" really fetches again.
    await context.unroute(/\/_next\/static\/chunks\//);
    await panel.getByRole("button", { name: /Document preview/ }).click();
    await panel.getByRole("button", { name: "Try again" }).click();
    await expect(panel.getByText("It needs a connection the first time it is used on this device.")).toHaveCount(0, { timeout: 30_000 });
    await context.close();
  });
});
