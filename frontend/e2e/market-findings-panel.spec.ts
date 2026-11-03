import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

const WORKSHOP = process.env.E2E_DW_ID ?? "cmsik2jg8000eh8xc1lcy661a";

const ANALYSIS_STAGE = "MARKET_ANALYSIS_DIRECTION";
const SURVEY_STAGE = "MARKET_SURVEY_CAPTURE";

async function apiToken(page: Page): Promise<string> {
  const res = await page.request.post(`${API}/api/auth/login`, {
    data: { email: EMAIL, password: PASSWORD }
  });
  expect(res.ok(), "sign-in for the API fixture").toBeTruthy();
  return (await res.json()).accessToken as string;
}

async function lowBand(page: Page, token: string) {
  const res = await page.request.get(`${API}/api/design-workshops/${WORKSHOP}/market-analysis`, {
    headers: { Authorization: `Bearer ${token}` }
  });
  expect(res.ok(), `the account can read ${WORKSHOP} — a DESIGNER-role account cannot`).toBeTruthy();
  const payload = await res.json();
  const band = (payload.bands as { verdict: string; category: string; message: string }[]).find(
    (row) => row.verdict === "LOW"
  );
  expect(band, "the flagship has a band the survey says is LOW").toBeTruthy();
  return band as { verdict: string; category: string; message: string };
}

const panelHeading = (page: Page) => page.getByRole("heading", { name: "What the survey says" });

test("stage 9 shows the findings panel, and the LOW band is legible in it", async ({ page }) => {
  const band = await lowBand(page, await apiToken(page));

  await signIn(page);
  await page.goto(`/design-workshops/${WORKSHOP}/stages/${ANALYSIS_STAGE}`);

  await expect(panelHeading(page), "the findings panel mounted on stage 9").toBeVisible({
    timeout: 60_000
  });

  await expect(page.getByText("Below the evidence").first()).toBeVisible();

  await expect(
    page.getByText(`sits below the evidence for ${band.category}`, { exact: false }).first()
  ).toBeVisible();

  await expect(page.getByText(/Computed online/i).first()).toBeVisible();
});

test("no other stage grows a market findings panel", async ({ page }) => {
  await signIn(page);
  await page.goto(`/design-workshops/${WORKSHOP}/stages/${SURVEY_STAGE}`);

  await expect(page.getByRole("button", { name: "Save stage" })).toBeVisible({ timeout: 60_000 });
  await expect(panelHeading(page), "stage 8 must not render stage 9's panel").toHaveCount(0);
});

test("the findings still render with the analysis endpoint blocked", async ({ page }) => {
  const band = await lowBand(page, await apiToken(page));

  let analysisRequests = 0;
  await page.route("**/design-workshops/*/market-analysis*", async (route) => {
    analysisRequests += 1;
    await route.abort("failed");
  });

  await signIn(page);

  await page.goto(`/design-workshops/${WORKSHOP}/stages/${SURVEY_STAGE}`);
  await expect(page.getByRole("button", { name: "Save stage" })).toBeVisible({ timeout: 60_000 });

  await page.goto(`/design-workshops/${WORKSHOP}/stages/${ANALYSIS_STAGE}`);

  await expect(panelHeading(page), "the panel renders without the analysis endpoint").toBeVisible({
    timeout: 60_000
  });
  await expect(page.getByText("Below the evidence").first()).toBeVisible();
  await expect(
    page.getByText(`sits below the evidence for ${band.category}`, { exact: false }).first()
  ).toBeVisible();

  await expect(page.getByText(/Computed on this device/i).first()).toBeVisible();

  expect(analysisRequests, "the panel must not reach for the network when the rows are here").toBe(0);
});
