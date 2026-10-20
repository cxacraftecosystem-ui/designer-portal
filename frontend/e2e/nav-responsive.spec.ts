import { expect, test, type Page } from "@playwright/test";

import { apiToken, CREDENTIALS_MISSING, PASSWORD, signIn, signInWithToken } from "./support/session";

/**
 * Responsive navigation, in a browser (REQUIREMENTS_2026-10-20 §4). Signed in, against a running
 * stack. The pure rules are `island-nav-unit.spec.ts`; the source shape is `nav-responsive-unit.spec.ts`.
 *
 * Below 1024 px: the search header at the top and the bottom island (at most five slots, the fifth
 * "More" when there are more) — and no desktop island. From 1024 px: the desktop island, expanded,
 * with its strip and bell — and neither the header nor the bar.
 *
 * E2E_EMAIL must be an admin (the seeded `admin2@example.org` in e2e-live.yml); admin view is turned
 * on for it so the Admin group is on the bar's list. The non-admin is the seeded researcher.
 */

test.skip(Boolean(CREDENTIALS_MISSING), CREDENTIALS_MISSING);

/** A seeded account with no Admin destinations: Home, Tour, Record, Browse and Account — five, no More. */
const NON_ADMIN_EMAIL = process.env.E2E_RESEARCHER_EMAIL ?? "researcher@example.org";

const bar = (page: Page) => page.getByTestId("bottom-island-nav");
const slots = (page: Page) => bar(page).locator(":scope > div > div > :is(a, button)");
const header = (page: Page) => page.getByTestId("app-search-header");
const islandMenu = (page: Page) => page.getByRole("button", { name: "Toggle navigation menu" });

/** The slots' accessible names, less any unread count. */
async function slotNames(page: Page): Promise<string[]> {
  const names = await slots(page).evaluateAll((nodes) => nodes.map((node) => node.getAttribute("aria-label") ?? ""));
  return names.map((name) => name.replace(/, \d+\+? unread$/, ""));
}

/** Signed in as the E2E admin with admin view ON, landed on the dashboard. */
async function signInAsAdminWithAdminView(page: Page) {
  const token = await apiToken(page.request);
  await signInWithToken(page, token);
  await page.addInitScript((value) => {
    try {
      const sub = String(JSON.parse(atob(value.split(".")[1] ?? "")).sub ?? "");
      if (sub) window.localStorage.setItem(`field_repo_admin_view:${sub}`, "on");
    } catch {
      // Not a JWT: the slot-order assertion says what is missing.
    }
  }, token);
  await page.goto("/dashboard");
}

async function tallPage(page: Page) {
  await page.evaluate(() => {
    document.body.style.minHeight = "4000px";
  });
}

for (const size of [
  { width: 390, height: 844 },
  { width: 820, height: 1180 }
]) {
  test.describe(`at ${size.width}×${size.height}`, () => {
    test.use({ viewport: size, hasTouch: true });

    test.beforeEach(async ({ page }) => {
      await signInAsAdminWithAdminView(page);
      await expect(bar(page)).toBeVisible();
    });

    test("the bottom island and the header replace the desktop island", async ({ page }) => {
      await expect(header(page)).toBeVisible();
      await expect(islandMenu(page)).toBeHidden();
      // At most five slots, "More" included, then the menu button.
      const count = await slots(page).count();
      expect(count).toBeGreaterThan(0);
      expect(count).toBeLessThanOrEqual(5);
      await expect(bar(page).getByRole("button", { name: "Open menu" })).toBeVisible();
      await expect(bar(page).getByRole("link", { name: /^Dashboard/ })).toHaveAttribute("aria-current", "page");
      if (size.width >= 640) {
        const box = await bar(page).boundingBox();
        expect(box!.width).toBeLessThanOrEqual(560.5);
      }
    });

    test("an admin with six slots sees Home, Tour, Record, Browse and More", async ({ page }) => {
      await expect.poll(() => slotNames(page)).toEqual(["Dashboard", "Walkthrough", "Record", "Browse", "More"]);
      // The bar prints the short words; the full names stay the accessible names.
      await expect(slots(page).nth(0)).toContainText("Home");
      await expect(slots(page).nth(1)).toContainText("Tour");
    });

    test("More opens a sheet listing Admin and Account, and Escape closes it", async ({ page }) => {
      const more = bar(page).getByRole("button", { name: /^More/ });
      await more.click();
      const sheet = page.getByRole("dialog", { name: "More" });
      await expect(sheet).toBeVisible();
      await expect(sheet.getByRole("heading", { name: "Admin" })).toBeVisible();
      await expect(sheet.getByRole("heading", { name: "Account" })).toBeVisible();
      await page.keyboard.press("Escape");
      await expect(sheet).toBeHidden();
      await expect(more).toBeFocused();
      await expect(page).toHaveURL(/\/dashboard$/);
    });

    test("Back closes the More sheet and stays on the page", async ({ page }) => {
      await bar(page).getByRole("button", { name: /^More/ }).click();
      const sheet = page.getByRole("dialog", { name: "More" });
      await expect(sheet).toBeVisible();
      await page.goBack();
      await expect(sheet).toBeHidden();
      await expect(page).toHaveURL(/\/dashboard$/);
      await expect(bar(page)).toBeVisible();
    });

    test("a row in the More sheet opens its page and leaves no extra step behind", async ({ page }) => {
      await bar(page).getByRole("button", { name: /^More/ }).click();
      const sheet = page.getByRole("dialog", { name: "More" });
      await sheet.getByRole("link", { name: /^Settings$/ }).click();
      await expect(page).toHaveURL(/\/settings$/);
      await expect(sheet).toBeHidden();
      await page.goBack();
      await expect(page).toHaveURL(/\/dashboard$/);
      await expect(page.getByRole("dialog", { name: "More" })).toBeHidden();
    });

    test("the header hides on the way down and returns on any move up", async ({ page }) => {
      await tallPage(page);
      const frame = page.locator(".app-search-header-frame");
      await page.mouse.wheel(0, 200);
      await expect(frame).toHaveAttribute("data-shown", "false");
      // A wheel scroll animates; an opposite wheel before it lands only shortens the way down.
      await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThanOrEqual(199);
      await page.mouse.wheel(0, -10);
      await expect(frame).toHaveAttribute("data-shown", "true");
    });

    test("the header opens search, and the bar hides while it is open", async ({ page }) => {
      await header(page).getByRole("button", { name: /Search$/ }).click();
      const panel = page.getByTestId("app-search-panel");
      await expect(panel).toBeVisible();
      await expect(bar(page)).toBeHidden();
      await page.keyboard.press("Escape");
      await expect(panel).toBeHidden();
      await expect(bar(page)).toBeVisible();
    });

    test("the bar hides while the on-screen keyboard is open", async ({ page }) => {
      await page.evaluate(() => {
        const viewport = window.visualViewport!;
        Object.defineProperty(viewport, "height", { configurable: true, get: () => window.innerHeight - 300 });
        viewport.dispatchEvent(new Event("resize"));
      });
      await expect(bar(page)).toBeHidden();
    });
  });

  test.describe(`at ${size.width}×${size.height}, a non-admin`, () => {
    test.use({ viewport: size, hasTouch: true });

    test("sees five slots and no More", async ({ page }) => {
      await signIn(page, NON_ADMIN_EMAIL, PASSWORD);
      await expect(bar(page)).toBeVisible();
      await expect.poll(() => slotNames(page)).toEqual(["Dashboard", "Walkthrough", "Record", "Browse", "Account"]);
      await expect(bar(page).getByRole("button", { name: /^More/ })).toHaveCount(0);
    });
  });
}

test.describe("at 390×844, the upload tray", () => {
  test.use({ viewport: { width: 390, height: 844 }, hasTouch: true });

  test("sits above the bar", async ({ page }) => {
    await signIn(page);
    await page.goto("/media");
    await expect(bar(page)).toBeVisible();
    await page.locator('input[type="file"][multiple]:not([capture])').first().setInputFiles({
      name: "loom.png",
      mimeType: "image/png",
      // A 1x1 PNG: only the tray's position is looked at.
      buffer: Buffer.from(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        "base64"
      )
    });
    await page
      .getByRole("combobox", { name: /Linked record type/ })
      .or(page.getByRole("button", { name: /Linked record type/ }))
      .first()
      .click();
    await page.getByRole("option", { name: "Artisan" }).click();
    // The form's required location: a state and the device's coordinates.
    await page.getByRole("button", { name: /^State/ }).click();
    await page.getByRole("option", { name: "Rajasthan" }).click();
    await page.getByRole("spinbutton", { name: /^Latitude/ }).fill("26.82");
    await page.getByRole("spinbutton", { name: /^Longitude/ }).fill("75.54");
    await page.locator("form button.field-button").filter({ hasText: /Upload/ }).click();
    const tray = page.locator('[role="status"]').filter({
      has: page.getByRole("button", { name: /upload details|Dismiss the upload summary/ })
    });
    await expect(tray).toBeVisible({ timeout: 60_000 });
    await expect(bar(page)).toBeVisible();
    const trayBox = await tray.boundingBox();
    const barBox = await bar(page).boundingBox();
    expect(trayBox!.y + trayBox!.height).toBeLessThanOrEqual(barBox!.y + 0.5);
  });
});

test.describe("at 1440×900", () => {
  test.use({ viewport: { width: 1440, height: 900 } });

  test("the island is drawn expanded, and neither the header nor the bar", async ({ page }) => {
    await signIn(page);
    await page.goto("/dashboard");
    await expect(islandMenu(page)).toBeVisible();
    await expect(page.getByRole("navigation", { name: "Primary" })).toBeVisible();
    await expect(page.getByRole("link", { name: /Notifications/ }).first()).toBeVisible();
    await expect(bar(page)).toHaveCount(1);
    await expect(bar(page)).toBeHidden();
    await expect(header(page)).toBeHidden();
    const pill = page.locator(".nav-island-frame header");
    await expect(pill).toHaveClass(/px-4 py-2/);
    const logo = await page.locator(".nav-island-frame header a[href='/dashboard'] svg, .nav-island-frame header a[href='/dashboard'] img").first().boundingBox();
    expect(Math.round(logo!.height)).toBe(32);
    const name = page.locator(".nav-island-frame header a[href='/dashboard'] span").first();
    await expect(name).toHaveCSS("font-size", "16px");
  });
});
