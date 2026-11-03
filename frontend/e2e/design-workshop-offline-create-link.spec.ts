import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

async function apiToken(page: Page): Promise<string> {
  const res = await page.request.post(`${API}/api/auth/login`, { data: { email: EMAIL, password: PASSWORD } });
  expect(res.ok(), `sign-in for the API fixture: ${await res.text()}`).toBeTruthy();
  return (await res.json()).accessToken as string;
}

async function drafts(page: Page) {
  for (let attempt = 0; ; attempt += 1) {
    try {
      return await page.evaluate(async () => {
        const db: IDBDatabase = await new Promise((resolve, reject) => {
          const open = indexedDB.open("design-workshop-drafts");
          open.onsuccess = () => resolve(open.result);
          open.onerror = () => reject(open.error);
        });
        const rows: Record<string, unknown>[] = await new Promise((resolve, reject) => {
          const rq = db.transaction("drafts", "readonly").objectStore("drafts").getAll();
          rq.onsuccess = () => resolve(rq.result as Record<string, unknown>[]);
          rq.onerror = () => reject(rq.error);
        });
        return rows.map((row) => ({
          remoteId: (row.remoteId as string | null) ?? null,
          header: row.header as Record<string, unknown>
        }));
      });
    } catch (error) {
      if (attempt >= 8) throw error;
      await page.waitForTimeout(500);
    }
  }
}

test("a workshop created with no connection keeps the workshop link the designer chose", async ({ page }) => {
  const token = await apiToken(page);
  await signIn(page);

  const title = `Offline link ${Date.now()}`;

  await page.goto("/design-workshops");
  await page.getByRole("button", { name: /new design workshop/i }).click();

  const picker = page.getByRole("button", { name: "Start from a recorded workshop" });
  await expect(picker).toBeVisible({ timeout: 30_000 });
  await picker.click();
  const options = page.getByRole("option");
  await expect(options.nth(1), "at least one design & prototype workshop is recorded").toBeVisible({
    timeout: 30_000
  });
  const picked = ((await options.nth(1).textContent()) ?? "").trim();
  await options.nth(1).click();
  await page.locator('input[name="title"]').fill(title);

  await page.route("**/api/design-workshops", async (route) => {
    if (route.request().method() !== "POST") return route.fallback();
    await route.abort("internetdisconnected");
  });
  await page.getByRole("button", { name: /create design workshop/i }).click();
  await page.waitForURL(/\/design-workshops\/dwlocal-/, { timeout: 30_000 });

  const local = (await drafts(page)).filter((draft) => draft.header?.title === title);
  expect(local, "the workshop was banked on this device").toHaveLength(1);
  expect(
    local[0].header.workshopId,
    `the chosen workshop ("${picked}") is still linked in the local draft`
  ).toBeTruthy();

  await page.unroute("**/api/design-workshops");
  let remoteId: string | null = null;
  for (let attempt = 0; attempt < 20 && !remoteId; attempt += 1) {
    await page.goto("/design-workshops");
    await page.waitForTimeout(1_000);
    remoteId = (await drafts(page)).find((draft) => draft.header?.title === title)?.remoteId ?? null;
  }
  expect(remoteId, "the draft reached the repository").toBeTruthy();

  const detail = await page.request.get(`${API}/api/design-workshops/${remoteId}`, {
    headers: { Authorization: `Bearer ${token}` }
  });
  expect(detail.ok(), await detail.text()).toBeTruthy();
  expect(
    (await detail.json()).workshopId,
    "the created design workshop is linked to the workshop record, not orphaned"
  ).toBeTruthy();
});
