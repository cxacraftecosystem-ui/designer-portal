import { expect, test, type Page } from "@playwright/test";

import { openCapturedAt, serveAddressReference } from "./fixtures/location";
import { anyCraftId, createArtisan, discard, findArtisan } from "./support/records";
import { CREDENTIALS_MISSING, apiToken, signIn as signInAs } from "./support/session";

/**
 * The two things the mandatory rule must NOT do: lock a researcher out of a record that predates it,
 * and render its warnings in a colour the dark theme swallows.
 *
 * THE LEGACY ROW. Some artisans on any long-lived database carry no location, because they were
 * documented before one was required. A rule that refuses to save such a record is a rule that stops
 * that artisan's phone number ever being corrected — while a rule that DEMANDS one teaches the
 * researcher to satisfy the form with wherever they happen to be sitting, which is worse than the
 * gap it filled. So the card drops the requirement for exactly that record, says why, and does not
 * auto-capture over it.
 *
 * WHAT IT WRITES: one fixture artisan, created through the API in `beforeAll` and deleted in
 * `afterAll`. Nothing is written through the FORM — every assertion is about the form's own state,
 * and no save is ever submitted, which is the property that makes it safe to point at a database
 * somebody cares about.
 */

test.skip(Boolean(CREDENTIALS_MISSING), CREDENTIALS_MISSING || "credentials present");

/*
 * THE TWO RECORDS, RESOLVED AT RUN TIME.
 *
 * These were two ids copied out of the production database. They 404 against any other one, so the
 * spec failed with "element not found" on a page that was a 404 — a report about the location card
 * that was really a report about which database was in front of it, and one `prisma migrate reset`
 * away from being wrong for everybody.
 *
 * They are resolved differently because the API treats them differently, and the difference is the
 * point of the file:
 *
 *   - The record with NO location row cannot be CREATED. A create without one is refused, and that
 *     refusal is the mandatory rule working as intended — the rows this test is about are the ones
 *     that predate it. So it is SEARCHED for, and when a database has none the test skips saying so
 *     rather than failing about a card it never got to see.
 *   - The record WITH one is built, because every assertion below is about its stored coordinate
 *     (22.x, deliberately nowhere near the browser's Bagru fix) and borrowing an arbitrary live row
 *     would make the spec depend on a latitude nobody chose.
 */
let ARTISAN_WITHOUT_LOCATION = process.env.E2E_LEGACY_ARTISAN_ID ?? "";
let ARTISAN_WITH_LOCATION = process.env.E2E_LOCATED_ARTISAN_ID ?? "";
let fixtureToken = "";
/** Only what this run made. A discovered record belongs to the database and is left alone. */
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
        // 22.313 on purpose: the browser below is in Bagru at 26.8, so "the stored one, not this
        // device's" is a distinction the assertion can actually make.
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
  // Granted on purpose. The point is that the card declines to capture anyway, not that it cannot.
  test.use({ permissions: ["geolocation"], geolocation: BAGRU });

  test("a record with no stored location stays saveable, and is not stamped with the editor's own", async ({
    page
  }) => {
    // Skipped rather than failed, and the message says which record is missing: a database seeded
    // after the mandatory-location rule landed has no row that predates it, and there is no way to
    // make one — the create endpoint refuses. Nothing about the card is in question here.
    test.skip(
      !ARTISAN_WITHOUT_LOCATION,
      "no artisan without a Location row exists on this database, and the API refuses to create one; set E2E_LEGACY_ARTISAN_ID to name one"
    );
    await signIn(page);
    await page.goto(`/artisans/${ARTISAN_WITHOUT_LOCATION}/edit`);

    await openCapturedAt(page);
    const latitude = page.locator('input[name="latitude"]');
    await expect(latitude).toBeVisible({ timeout: 30_000 });

    // Give the automatic capture longer than it would ever need. It must still not have run: this
    // browser is in Bagru and the artisan was documented in Kolkata.
    await page.waitForTimeout(6_000);
    await expect(latitude).toHaveValue("");
    await expect(page.getByText(/captured automatically/i)).toHaveCount(0);

    // The requirement is off for this record, so the form is not held hostage by it...
    await expect(latitude).toHaveJSProperty("validity.valid", true);
    await expect(latitude).not.toHaveAttribute("required", /.*/);
    // ...and the card explains the exception rather than leaving it to be inferred.
    await expect(page.getByText(/created before a coordinate was required/i)).toBeVisible();

    // The stated address is off too, for the same reason and on the same record: all fifteen live
    // locations have a NULL state and district, and demanding them here would be demanding a guess.
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
    // The stored coordinate, not this browser's. Bagru is 26.8; the record is 22.3.
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

    /*
     * The reason this assertion exists: the repo's usual warning pairing is
     * `border-amber-200 bg-amber-50`, and neither shade is in this project's amber ramp
     * (tailwind.config.ts defines 100/500/800 only). Those classes therefore resolve to nothing,
     * leaving dark-brown text on whatever the card is — unreadable on the dark theme. These
     * notices are the only thing standing between a network estimate and a research record, so
     * they are painted with tokens that exist.
     */
    const painted = await notice.evaluate((element) => {
      const panel = element.closest("div");
      const style = panel ? getComputedStyle(panel) : null;
      return { background: style?.backgroundColor ?? "", colour: style?.color ?? "" };
    });
    expect(painted.background).not.toBe("rgba(0, 0, 0, 0)");
    expect(painted.background).not.toBe("transparent");

    // 360px is the narrowest phone this app supports; nothing in the card may push the page sideways.
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth
    );
    expect(overflow).toBeLessThanOrEqual(0);
  });
});
