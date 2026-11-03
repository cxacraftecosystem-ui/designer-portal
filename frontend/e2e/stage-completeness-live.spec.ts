import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

const STAGE_KEY = "INTRODUCTORY_ADMIN_DOCUMENTATION";

async function apiToken(page: Page): Promise<string> {
  const res = await page.request.post(`${API}/api/auth/login`, {
    data: { email: EMAIL, password: PASSWORD }
  });
  expect(res.ok(), "sign-in for the API fixture").toBeTruthy();
  return (await res.json()).accessToken as string;
}

function counter(page: Page) {
  return page.getByText("Required fields in this stage").locator("..").locator("span").nth(1);
}

test("the required-field count is drawn before any save and moves on a keystroke", async ({ page }) => {
  const token = await apiToken(page);
  const auth = { Authorization: `Bearer ${token}` };

  const schema = await (await page.request.get(`${API}/api/design-workshops/schema`, { headers: auth })).json();
  const stage = schema.stages.find((s: { key: string }) => s.key === STAGE_KEY);
  expect(stage, `${STAGE_KEY} is in the registry`).toBeTruthy();
  const singletonEntities = (stage.entities ?? []).filter(
    (e: { cardinality: string }) => e.cardinality === "SINGLETON"
  );
  const requiredFields = singletonEntities.flatMap((e: { fields: { key: string; required: boolean; deprecated: boolean; label: string }[] }) =>
    (e.fields ?? []).filter((f) => f.required && !f.deprecated)
  );
  const requiredTotal = requiredFields.length;
  expect(requiredTotal, "stage 2 has required fields to count").toBeGreaterThan(0);
  expect(
    (stage.entities ?? []).filter((e: { cardinality: string }) => e.cardinality !== "SINGLETON"),
    "stage 2 has no collections, so the singleton count IS the whole count"
  ).toHaveLength(0);

  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: auth,
    data: { title: `Live completeness spec ${Date.now()}` }
  });
  expect(created.ok(), "create a design workshop").toBeTruthy();
  const workshopId = (await created.json()).id as string;

  await signIn(page);

  const stageRequests: string[] = [];
  page.on("request", (request) => {
    const url = request.url();
    if (request.method() !== "GET" && url.includes(`/design-workshops/${workshopId}/stages/`)) {
      stageRequests.push(`${request.method()} ${url}`);
    }
  });

  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);

  const firstRequired = requiredFields[0];
  const editor = page.getByRole("textbox", { name: firstRequired.label });
  await expect(editor, `the editor for "${firstRequired.label}" rendered`).toBeVisible({ timeout: 30_000 });

  await expect(
    counter(page),
    "the panel is drawn on a never-saved stage instead of appearing only after the first Save"
  ).toHaveText(`0 of ${requiredTotal}`);

  const stillNeeded = page.getByText(/^Still needed:/);
  await expect(stillNeeded, "the outstanding list is drawn before any save").toBeVisible();
  for (const field of requiredFields) {
    await expect(
      stillNeeded,
      `"${field.label}" is listed as outstanding while it is empty`
    ).toContainText(field.label);
  }

  const typed = "Barpali";
  await editor.click();
  await page.keyboard.type(typed);

  await expect(
    counter(page),
    "the count moved on the keystroke — with no Save pressed and no request made"
  ).toHaveText(`1 of ${requiredTotal}`);

  await expect(
    stillNeeded,
    `"${firstRequired.label}" left the outstanding list once it was written`
  ).not.toContainText(firstRequired.label);
  expect(
    stageRequests,
    `the live count must cost nothing: the page sent ${JSON.stringify(stageRequests)}`
  ).toEqual([]);

  await editor.click();
  await page.keyboard.press("End");
  for (let i = 0; i < typed.length; i += 1) await page.keyboard.press("Backspace");
  await expect(
    counter(page),
    "emptying the field takes the count back down"
  ).toHaveText(`0 of ${requiredTotal}`);
  await expect(
    stillNeeded,
    `"${firstRequired.label}" is outstanding again once it is emptied`
  ).toContainText(firstRequired.label);
});
