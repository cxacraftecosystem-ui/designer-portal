import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

const WORKSHOP = process.env.E2E_DW_ID ?? "cmsik2jg8000eh8xc1lcy661a";

const SLOW = "PHOTO_CATALOGUE";
const FAST = "COMPACT_SUMMARY";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

async function apiToken(page: Page): Promise<string> {
  const res = await page.request.post(`${API}/api/auth/login`, { data: { email: EMAIL, password: PASSWORD } });
  expect(res.ok(), `sign-in for the API fixture: ${await res.text()}`).toBeTruthy();
  return (await res.json()).accessToken as string;
}

async function previewText(page: Page, token: string, templateId: string): Promise<{ headings: string[]; all: string }> {
  const res = await page.request.get(
    `${API}/api/design-workshops/${WORKSHOP}/report/preview?templateId=${templateId}`,
    { headers: { Authorization: `Bearer ${token}` } }
  );
  expect(res.ok(), `preview ${templateId}: ${await res.text()}`).toBeTruthy();
  const payload = (await res.json()) as { blocks: Array<Record<string, unknown>> };
  const runsOf = (block: Record<string, unknown>) =>
    ((block.runs as Array<{ text?: string }>) ?? []).map((run) => run.text ?? "").join("");
  return {
    headings: payload.blocks
      .filter((block) => String(block.type).toUpperCase() === "HEADING")
      .map(runsOf)
      .filter(Boolean),
    all: payload.blocks.map(runsOf).join("\n")
  };
}

test("a slower earlier template cannot overwrite the preview the designer selected", async ({ page }) => {
  const token = await apiToken(page);

  const slow = await previewText(page, token, SLOW);
  const fast = await previewText(page, token, FAST);
  const distinct = (mine: string[], theirs: string) =>
    mine.find((head) => head.length >= 12 && !theirs.includes(head));
  const onlySlow = distinct(slow.headings, fast.all);
  const onlyFast = distinct(fast.headings, slow.all);
  expect(onlySlow, `${SLOW} has a heading that appears nowhere in ${FAST}`).toBeTruthy();
  expect(onlyFast, `${FAST} has a heading that appears nowhere in ${SLOW}`).toBeTruthy();
  console.log(`discriminators — ${SLOW}: "${onlySlow}" · ${FAST}: "${onlyFast}"`);

  await signIn(page);

  await page.route(`**/report/preview*`, async (route) => {
    if (route.request().url().includes(`templateId=${SLOW}`)) {
      await new Promise((resolve) => setTimeout(resolve, 3_000));
    }
    await route.continue();
  });

  await page.goto(`/design-workshops/${WORKSHOP}/report`);
  const templatePicker = page.getByRole("button", { name: "Report template" });
  await expect(templatePicker).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText(/Sheet 1|Page 1|A4/i).first()).toBeVisible({ timeout: 60_000 });

  const pick = async (label: string) => {
    await templatePicker.click();
    await page.getByRole("option", { name: label, exact: true }).click();
  };

  await pick("Photo catalogue");
  await page.waitForTimeout(400);
  await pick("Compact summary");

  await page.waitForTimeout(6_000);

  await expect(
    page.getByRole("button", { name: "Report template" }),
    "the page still says Compact summary"
  ).toContainText("Compact summary");
  const sheets = page.locator(".rp-pages");
  await expect(
    sheets.getByText(onlyFast!, { exact: false }).first(),
    `the sheets on screen are the ${FAST} document the dropdown names`
  ).toBeVisible({ timeout: 15_000 });
  await expect(
    sheets.getByText(onlySlow!, { exact: false }),
    `the ${SLOW} document did not arrive late and overwrite it`
  ).toHaveCount(0);
});
