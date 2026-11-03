import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

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

  const swatch = page.getByRole("radio", { name: /maroon/i }).first();
  await expect(swatch).toBeVisible({ timeout: 30_000 });
  await swatch.click();

  const save = page.getByRole("button", { name: /save as the workshop's colour/i });
  await expect(save, "the picker offers a way to keep the colour").toBeVisible();
  await save.click();

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

  const report = await page.request.post(`${API}/api/design-workshops/${workshopId}/report`, {
    headers: auth,
    data: { formats: ["DOCX"], record: false }
  });
  expect(report.status()).toBe(200);
});
