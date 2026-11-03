import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

function verhoeffAadhaar(seed: number): string {
  const d = [
    [0, 1, 2, 3, 4, 5, 6, 7, 8, 9], [1, 2, 3, 4, 0, 6, 7, 8, 9, 5],
    [2, 3, 4, 0, 1, 7, 8, 9, 5, 6], [3, 4, 0, 1, 2, 8, 9, 5, 6, 7],
    [4, 0, 1, 2, 3, 9, 5, 6, 7, 8], [5, 9, 8, 7, 6, 0, 4, 3, 2, 1],
    [6, 5, 9, 8, 7, 1, 0, 4, 3, 2], [7, 6, 5, 9, 8, 2, 1, 0, 4, 3],
    [8, 7, 6, 5, 9, 3, 2, 1, 0, 4], [9, 8, 7, 6, 5, 4, 3, 2, 1, 0]
  ];
  const p = [
    [0, 1, 2, 3, 4, 5, 6, 7, 8, 9], [1, 5, 7, 6, 2, 8, 3, 0, 9, 4],
    [5, 8, 0, 3, 7, 9, 6, 1, 4, 2], [8, 9, 1, 6, 0, 4, 3, 5, 2, 7],
    [9, 4, 5, 3, 1, 2, 6, 8, 7, 0], [4, 2, 8, 6, 5, 7, 3, 9, 0, 1],
    [2, 7, 9, 3, 8, 0, 6, 4, 1, 5], [7, 0, 4, 6, 9, 1, 3, 2, 5, 8]
  ];
  const inv = [0, 4, 3, 2, 1, 5, 6, 7, 8, 9];
  const body = String(2 + (seed % 8)) + String(seed).padStart(10, "0").slice(-10);
  let c = 0;
  const reversed = body.split("").reverse();
  reversed.forEach((digit, i) => {
    c = d[c][p[(i + 1) % 8][Number(digit)]];
  });
  return body + String(inv[c]);
}


test("a reference picker offers to create the missing record, without leaving the stage", async ({
  page
}) => {
  const token = (
    await (await page.request.post(`${API}/api/auth/login`, { data: { email: EMAIL, password: PASSWORD } })).json()
  ).accessToken as string;

  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { title: `Inline create spec ${Date.now()}` }
  });
  const workshopId = (await created.json()).id as string;

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/stages/WORKSHOP_PLAN_PARTICIPANTS_OPENING`);

  const addRow = page.getByRole("button", { name: /add participating artisan/i }).first();
  await expect(addRow).toBeVisible({ timeout: 30_000 });
  await addRow.click();

  const picker = page.getByRole("button", { name: /^artisan record$/i });
  await expect(picker).toBeVisible({ timeout: 30_000 });
  await picker.click();

  const createButton = page.getByText(/create .*new artisan/i).first();
  await expect(createButton, "the picker offers inline creation").toBeVisible({ timeout: 15_000 });
  await createButton.click();

  const dialog = page.getByRole("dialog");
  await expect(dialog, "a dialog opened").toBeVisible({ timeout: 15_000 });
  await expect(dialog.getByText(/new artisan/i).first()).toBeVisible();
  await expect(
    dialog.getByText(/aadhaar/i).first(),
    "the REAL ArtisanForm is mounted, not a simplified stub"
  ).toBeVisible({ timeout: 15_000 });

  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden({ timeout: 15_000 });
  expect(page.url()).toContain(`/design-workshops/${workshopId}/stages/`);
});

test("the roster multi-picker offers it too, where leaving costs the most", async ({ page }) => {
  const token = (
    await (await page.request.post(`${API}/api/auth/login`, { data: { email: EMAIL, password: PASSWORD } })).json()
  ).accessToken as string;
  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { title: `Inline roster spec ${Date.now()}` }
  });
  const workshopId = (await created.json()).id as string;

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/stages/WORKSHOP_PLAN_PARTICIPANTS_OPENING`);

  const roster = page.getByRole("button", { name: /add several from artisan record/i }).first();
  await expect(roster).toBeVisible({ timeout: 30_000 });
  await roster.click();

  const createButton = page.getByText(/create .*new artisan/i).first();
  await expect(
    createButton,
    "the roster is the likeliest place to find a record missing and the most expensive to leave"
  ).toBeVisible({ timeout: 15_000 });
});

test("a linked record can be corrected without leaving the stage", async ({ page }) => {
  const token = (
    await (await page.request.post(`${API}/api/auth/login`, { data: { email: EMAIL, password: PASSWORD } })).json()
  ).accessToken as string;
  const auth = { Authorization: `Bearer ${token}` };
  const stamp = Date.now();

  const crafts = await (await page.request.get(`${API}/api/crafts?pageSize=1`, { headers: auth })).json();
  const craftId = crafts?.items?.[0]?.id as string | undefined;
  test.skip(!craftId, "no craft exists to assign the fixture artisan to");

  const artisan = await page.request.post(`${API}/api/artisans`, {
    headers: auth,
    data: {
      name: `Inline Edit Artisan ${stamp}`,
      place: "Barpali",
      craftId,
      aadhaarNumber: verhoeffAadhaar(stamp),
      dos: "Keep the warp evenly tensioned.",
      donts: "Do not wash the tied yarn before dyeing.",
      location: { state: "Odisha", district: "Bargarh", latitude: 21.19, longitude: 83.59 }
    }
  });
  expect(artisan.status(), `artisan fixture: ${await artisan.text()}`).toBe(201);
  const artisanId = (await artisan.json()).id as string;

  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: auth,
    data: { title: `Inline edit spec ${stamp}` }
  });
  const workshopId = (await created.json()).id as string;

  await page.request.put(`${API}/api/design-workshops/${workshopId}/stages/WORKSHOP_PLAN_PARTICIPANTS_OPENING`, {
    headers: auth,
    data: {
      entries: [{ entityKey: "participant", ordinal: 0, data: { artisanRef: artisanId, name: `Inline Edit Artisan ${stamp}` } }],
      replaceCollections: true,
      submit: false
    }
  });

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/stages/WORKSHOP_PLAN_PARTICIPANTS_OPENING`);

  await page.getByRole("button", { name: new RegExp(`Inline Edit Artisan ${stamp}`, "i") }).first().click();

  await expect(
    page.getByText(/the page below IS that record/i).first(),
    "the linked row says, in the product's own words, that the record itself is open below it"
  ).toBeVisible({ timeout: 30_000 });

  await expect(
    page.getByRole("button", { name: /edit this artisan/i }),
    "no second editor is offered over a record whose form is already mounted — see StageReferenceSelect's recordFormMountedOver guard"
  ).toHaveCount(0);

  await expect(
    page.locator(`input[value*="Inline Edit Artisan ${stamp}"]`).first(),
    "the embedded form holds the real record, not just the picker's label"
  ).toBeVisible({ timeout: 20_000 });

  expect(page.url()).toContain(`/design-workshops/${workshopId}/stages/`);
});
