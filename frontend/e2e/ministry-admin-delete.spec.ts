import { expect, test, type Page } from "@playwright/test";

import { API, apiToken, bearer, CREDENTIALS_MISSING, PASSWORD, signInWithToken } from "./support/session";

/**
 * A Ministry Admin sees Delete, and what they delete comes back (owner, 2026-10-20). Signed in, in a
 * browser, against a running stack (`e2e-live.yml` seeds the accounts below).
 *
 * The rule under test is `canDeleteRecords = isContentAdmin(user)`, WHETHER OR NOT admin view is on
 * (owner, 2026-10-21): every Delete reads it, so a Ministry Admin must see the questionnaire's Delete,
 * each sitting's Delete and a record page's "Danger zone" even with admin view off, and a designer
 * must see none of them. Deleting a questionnaire or a sitting is a SOFT delete, so the same Ministry
 * Admin then restores both from the screen — restoring stays in admin view.
 *
 *   npx playwright test e2e/ministry-admin-delete.spec.ts
 */

test.skip(Boolean(CREDENTIALS_MISSING), CREDENTIALS_MISSING);

const MINISTRY_ADMIN_EMAIL = process.env.E2E_MINISTRY_ADMIN_EMAIL ?? "ministryadmin@example.org";
const DESIGNER_EMAIL = process.env.E2E_DESIGNER_EMAIL ?? "designer@example.org";

/** Signed in as `email`; admin view switched on before the first page reads it. */
async function signInAs(page: Page, email: string, adminView: boolean): Promise<string> {
  const token = await apiToken(page.request, email, PASSWORD);
  await signInWithToken(page, token);
  if (adminView) {
    await page.addInitScript((value) => {
      try {
        const sub = String(JSON.parse(atob(value.split(".")[1] ?? "")).sub ?? "");
        if (sub) window.localStorage.setItem(`field_repo_admin_view:${sub}`, "on");
      } catch {
        // Not a JWT: the Delete assertions say what is missing.
      }
    }, token);
  }
  return token;
}

/** An unattached questionnaire with one sitting, made through the API as the Ministry Admin. */
async function questionnaireWithASitting(page: Page, token: string, stamp: number) {
  const created = await page.request.post(`${API}/api/questionnaires`, {
    headers: bearer(token),
    data: {
      title: `E2E delete check ${stamp}`,
      sections: [{ code: "A", title: "About the loom", questions: [{ prompt: "How many looms?" }] }]
    }
  });
  expect(created.status(), await created.text()).toBe(201);
  const form = (await created.json()) as { id: string; title: string };
  const sitting = await page.request.post(`${API}/api/questionnaires/${form.id}/entries`, {
    headers: bearer(token),
    data: { respondentName: `Respondent ${stamp}` }
  });
  expect(sitting.status(), await sitting.text()).toBe(201);
  return form;
}

async function confirmDelete(page: Page) {
  await page.getByRole("alertdialog").getByRole("button", { name: "Delete", exact: true }).click();
}

test("a Ministry Admin in admin view sees Delete, deletes, and restores", async ({ page }) => {
  const stamp = Date.now();
  const token = await signInAs(page, MINISTRY_ADMIN_EMAIL, true);
  const form = await questionnaireWithASitting(page, token, stamp);
  const respondent = `Respondent ${stamp}`;

  await page.goto(`/questionnaires/${form.id}`);
  await expect(page.getByRole("heading", { name: form.title })).toBeVisible();

  // The sitting's Delete, then its restore.
  const sittingRow = page.locator("li").filter({ hasText: respondent }).first();
  await expect(sittingRow.getByRole("button", { name: "Delete", exact: true })).toBeVisible();
  await sittingRow.getByRole("button", { name: "Delete", exact: true }).click();
  await confirmDelete(page);
  const deleted = page.getByRole("region", { name: "Deleted answers" });
  await expect(deleted.getByText(respondent)).toBeVisible();
  await deleted.getByRole("button", { name: "Restore" }).click();
  await expect(deleted).toBeHidden();
  await expect(page.locator("li").filter({ hasText: respondent }).first()).toBeVisible();

  // The questionnaire's own Delete, then its restore from the trash on the list.
  await page.getByRole("button", { name: "Delete", exact: true }).first().click();
  await confirmDelete(page);
  await expect(page).toHaveURL(/\/questionnaires$/);
  const trash = page.locator("#deleted-questionnaires");
  const trashRow = trash.locator("tr").filter({ hasText: form.title });
  await expect(trashRow).toBeVisible();
  await trashRow.getByRole("button", { name: "Restore" }).click();
  await expect(trash.getByText(`“${form.title}” is restored.`)).toBeVisible();

  const back = await page.request.get(`${API}/api/questionnaires/${form.id}`, { headers: bearer(token) });
  expect(back.status()).toBe(200);
  const body = (await back.json()) as { entries: { respondentName: string | null }[] };
  expect(body.entries.map((entry) => entry.respondentName)).toEqual([respondent]);
});

/** The first artisan on the stack, or a skip: the Danger zone needs a record page to end. */
async function firstArtisanId(page: Page, token: string): Promise<string> {
  const list = await page.request.get(`${API}/api/artisans?pageSize=1`, { headers: bearer(token) });
  expect(list.ok(), await list.text()).toBeTruthy();
  const artisan = (((await list.json()) as { items?: { id: string }[] }).items ?? [])[0];
  test.skip(!artisan, "No artisan in this database to open.");
  return artisan!.id;
}

test("a Ministry Admin with admin view OFF still sees Delete and the Danger zone", async ({ page }) => {
  const stamp = Date.now();
  const token = await signInAs(page, MINISTRY_ADMIN_EMAIL, false);
  const form = await questionnaireWithASitting(page, token, stamp);

  await page.goto(`/questionnaires/${form.id}`);
  await expect(page.getByRole("heading", { name: form.title })).toBeVisible();
  const sittingRow = page.locator("li").filter({ hasText: `Respondent ${stamp}` }).first();
  await expect(sittingRow.getByRole("button", { name: "Delete", exact: true })).toBeVisible();
  // Restoring is still admin view's: the trash is not drawn with it off.
  await expect(page.getByRole("region", { name: "Deleted answers" })).toHaveCount(0);

  await page.goto(`/artisans/${await firstArtisanId(page, token)}`);
  await expect(page.getByRole("heading", { name: "Danger zone" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Delete this artisan" })).toBeVisible();
});

test("a designer sees no Danger zone on a record page", async ({ browser }) => {
  const adminPage = await browser.newPage();
  const adminToken = await apiToken(adminPage.request, MINISTRY_ADMIN_EMAIL, PASSWORD);
  const artisanId = await firstArtisanId(adminPage, adminToken);
  await adminPage.close();

  const page = await browser.newPage();
  await signInAs(page, DESIGNER_EMAIL, false);
  await page.goto(`/artisans/${artisanId}`);
  await page.waitForLoadState("networkidle").catch(() => {});
  await expect(page.getByRole("heading", { name: "Danger zone" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: /^Delete/ })).toHaveCount(0);
  await page.close();
});

test("a designer sees no Delete on the same questionnaire", async ({ browser }) => {
  const stamp = Date.now();
  const adminPage = await browser.newPage();
  const adminToken = await apiToken(adminPage.request, MINISTRY_ADMIN_EMAIL, PASSWORD);
  const form = await questionnaireWithASitting(adminPage, adminToken, stamp);
  await adminPage.close();

  const page = await browser.newPage();
  await signInAs(page, DESIGNER_EMAIL, false);
  await page.goto(`/questionnaires/${form.id}`);
  await expect(page.getByRole("heading", { name: form.title })).toBeVisible();
  await expect(page.getByRole("button", { name: "Delete", exact: true })).toHaveCount(0);
  await page.close();
});
