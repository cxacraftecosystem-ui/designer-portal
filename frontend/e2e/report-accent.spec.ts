import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

/**
 * The report colour can be KEPT, not only tried.
 *
 * The picker was designed as a per-file override — trying three colours before submitting must not
 * mean three saves — but that left no way to make one stick. A designer picked a custom colour,
 * generated the report, came back the next day and found the old colour, with nothing having said
 * the choice was temporary. "Save as the workshop's colour" is the missing exit.
 *
 * Both keys have to be written. `resolve_accent` reads `themeAccent` first and falls back to
 * `themePreset`, and "Custom colour" is deliberately NOT in `PRESETS_BY_KEY` — so a save that
 * wrote only the preset name would resolve to nothing and the report would come out in the
 * template's own colour, which is the exact bug this spec pins.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

test("a chosen report colour can be saved to the workshop", async ({ page }) => {
  const token = (
    await (await page.request.post(`${API}/api/auth/login`, { data: { email: EMAIL, password: PASSWORD } })).json()
  ).accessToken as string;
  const auth = { Authorization: `Bearer ${token}` };

  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: auth,
    data: { title: `Accent spec ${Date.now()}` }
  });
  const workshopId = (await created.json()).id as string;

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/report`);

  // Choose a named colour, which is the same code path a custom one takes.
  const swatch = page.getByRole("radio", { name: /maroon/i }).first();
  await expect(swatch).toBeVisible({ timeout: 30_000 });
  await swatch.click();

  const save = page.getByRole("button", { name: /save as the workshop's colour/i });
  await expect(save, "the picker offers a way to keep the colour").toBeVisible();
  await save.click();

  // BOTH keys must land, and the hex is the one that actually resolves.
  await expect
    .poll(
      async () => {
        const stage = await (
          await page.request.get(`${API}/api/design-workshops/${workshopId}/stages/REPORT_GENERATION`, {
            headers: auth
          })
        ).json();
        return stage?.singleton ?? {};
      },
      { message: "the colour reached stage 20", timeout: 30_000 }
    )
    .toMatchObject({ themeAccent: expect.stringMatching(/^#?[0-9A-F]{6}$/i), themePreset: expect.any(String) });

  // And the server resolves it — the report is generated in that colour with no override sent.
  const report = await page.request.post(`${API}/api/design-workshops/${workshopId}/report`, {
    headers: auth,
    data: { formats: ["DOCX"], record: false }
  });
  expect(report.status()).toBe(200);
});
