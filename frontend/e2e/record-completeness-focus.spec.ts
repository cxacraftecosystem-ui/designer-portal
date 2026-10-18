import { expect, test } from "@playwright/test";

import { API, apiToken, bearer, CREDENTIALS_MISSING, signIn } from "./support/session";

/**
 * A meter pill on an artisan's page opens the edit form AT the missing field (2026-10-18).
 *
 * READ-ONLY: the dev server may point at real data, so nothing is written. A real artisan is read,
 * and its GET is answered with that artisan minus its craft start date and with the block the server
 * would then send — the page under test is the client's, not the server's arithmetic (which
 * `backend/tests/test_record_completeness.py` holds).
 */

test.skip(Boolean(CREDENTIALS_MISSING), CREDENTIALS_MISSING || "");

test("clicking the craft start date pill focuses that field on the edit form", async ({ page, request }) => {
  const token = await apiToken(request);
  const list = await request.get(`${API}/api/artisans?pageSize=1`, { headers: bearer(token) });
  const artisan = (await list.json()).items?.[0];
  test.skip(!artisan, "no artisan visible to this account");

  const block = {
    rulesVersion: 1,
    filled: 6,
    total: 7,
    percent: 85,
    requiredMissing: 1,
    missing: [{ key: "craftStartDate", label: "Craft start date", words: "craft start date", level: "required" }]
  };
  await page.route(new RegExp(`/api/artisans/${artisan.id}$`), async (route) => {
    const response = await route.fetch();
    const body = await response.json();
    await route.fulfill({ response, json: { ...body, craftStartDate: null, completeness: block } });
  });

  await signIn(page);
  await page.goto(`/artisans/${artisan.id}`);
  const meter = page.locator("[data-completeness-meter]");
  await expect(meter).toContainText("1 field left: craft start date");
  await meter.getByRole("link", { name: "Craft start date" }).click();
  await expect(page).toHaveURL(new RegExp(`/artisans/${artisan.id}/edit\?focus=craftStartDate`));
  await expect
    .poll(() => page.evaluate(() => Boolean(document.activeElement?.closest('[data-field="craftStartDate"]'))), { timeout: 15_000 })
    .toBe(true);
});
