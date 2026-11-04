import { expect, test, type Page } from "@playwright/test";

import { openCapturedAt, serveAddressReference } from "./fixtures/location";
import { anyCraftId, createArtisan, discard, findArtisan } from "./support/records";
import { CREDENTIALS_MISSING, apiToken, signIn as signInAs } from "./support/session";

test.skip(Boolean(CREDENTIALS_MISSING), CREDENTIALS_MISSING || "credentials present");

let ARTISAN_WITHOUT_LOCATION = process.env.E2E_LEGACY_ARTISAN_ID ?? "";
let ARTISAN_WITH_LOCATION = process.env.E2E_LOCATED_ARTISAN_ID ?? "";
let fixtureToken = "";
let createdArtisan = "";

const BAGRU = { latitude: 26.8137, longitude: 75.545 };

test.beforeAll(async ({ request }) => {
  if (CREDENTIALS_MISSING) return;
  fixtureToken = await apiToken(request);

  if (!ARTISAN_WITHOUT_LOCATION) {
    ARTISAN_WITHOUT_LOCATION =
      (await findArtisan(request, fixtureToken, (row) => !row.location)) ?? "";
  }

  if (!ARTISAN_WITH_LOCATION) {
    const craftId = await anyCraftId(request, fixtureToken);
    if (craftId) {
      ARTISAN_WITH_LOCATION = await createArtisan(request, fixtureToken, {
        name: `Located record ${Date.now()}`,
        place: "Paschim Medinipur",
        craftId,
        location: { state: "West Bengal", district: "Paschim Medinipur", latitude: 22.313, longitude: 87.313 }
      });
      createdArtisan = ARTISAN_WITH_LOCATION;
    }
  }
});

test.afterAll(async ({ request }) => {
  if (createdArtisan && fixtureToken) await discard(request, fixtureToken, `/api/artisans/${createdArtisan}`);
});

async function signIn(page: Page) {
  await serveAddressReference(page);
  await signInAs(page);
}

test.describe("Location on records that predate the rule", () => {
  test.use({ permissions: ["geolocation"], geolocation: BAGRU });

  test("a record with no stored location stays saveable, and is not stamped with the editor's own", async ({
    page
  }) => {
    test.skip(
      !ARTISAN_WITHOUT_LOCATION,
      "no artisan without a Location row exists on this database, and the API refuses to create one; set E2E_LEGACY_ARTISAN_ID to name one"
    );
    await signIn(page);
    await page.goto(`/artisans/${ARTISAN_WITHOUT_LOCATION}/edit`);

    await openCapturedAt(page);
    const latitude = page.locator('input[name="latitude"]');
    await expect(latitude).toBeVisible({ timeout: 30_000 });

    await page.waitForTimeout(6_000);
    await expect(latitude).toHaveValue("");
    await expect(page.getByText(/captured automatically/i)).toHaveCount(0);

    await expect(latitude).toHaveJSProperty("validity.valid", true);
    await expect(latitude).not.toHaveAttribute("required", /.*/);
    await expect(page.getByText(/created before a coordinate was required/i)).toBeVisible();

    await expect(page.locator('input[name="state"]')).toHaveValue("");
    await expect(page.locator('input[name="state"]')).not.toHaveAttribute("required", /.*/);
    await expect(page.locator('input[name="district"]')).not.toHaveAttribute("required", /.*/);
  });

  test("a record that has one keeps it, and cannot be emptied", async ({ page }) => {
    test.skip(!ARTISAN_WITH_LOCATION, "no craft exists to hang a fixture artisan off");
    await signIn(page);
    await page.goto(`/artisans/${ARTISAN_WITH_LOCATION}/edit`);

    await openCapturedAt(page);
    const latitude = page.locator('input[name="latitude"]');
    await expect(latitude).not.toHaveValue("", { timeout: 30_000 });
    await expect(latitude).toHaveValue(/^22\./);

    await latitude.fill("");
    await expect(latitude).toHaveJSProperty("validity.valid", false);
  });
});

test.describe("Location card in the dark theme, at 360px", () => {
  test.use({ permissions: [], viewport: { width: 360, height: 780 }, colorScheme: "dark" });

  test("the refusal notice is legible and the card does not overflow", async ({ page }) => {
    await signIn(page);
    await page.emulateMedia({ colorScheme: "dark" });
    await page.goto("/workshops");

    const notice = page.getByText(/blocking location/i);
    await expect(notice).toBeVisible({ timeout: 30_000 });

    const painted = await notice.evaluate((element) => {
      const panel = element.closest("div");
      const style = panel ? getComputedStyle(panel) : null;
      return { background: style?.backgroundColor ?? "", colour: style?.color ?? "" };
    });
    expect(painted.background).not.toBe("rgba(0, 0, 0, 0)");
    expect(painted.background).not.toBe("transparent");

    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth
    );
    expect(overflow).toBeLessThanOrEqual(0);
  });
});
