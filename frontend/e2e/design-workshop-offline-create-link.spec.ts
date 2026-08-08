import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

/**
 * A DESIGN WORKSHOP CREATED WITHOUT A CONNECTION KEEPS THE WORKSHOP RECORD IT WAS STARTED FROM.
 *
 * The create form builds one `header` object — title, template, craft, cluster, state, district,
 * the dates and `workshopId`, the link to the `Workshop` row the designer picked out of "Start from
 * a recorded workshop" — and hands the whole thing to the create call. The offline fallback
 * (`createLocalOffline`) declared its own parameter type and re-enumerated NINE of those fields by
 * hand into `createLocalDraft`. `workshopId` was in neither list, so it was silently dropped and
 * `emptyHeader`'s null stood. The sync pass then created the workshop unlinked, permanently, with
 * nothing on screen ever saying so.
 *
 * NOTHING CAUGHT IT. TypeScript does not apply excess-property checking to a variable, so the extra
 * key was legal; and every OTHER field survived, so the row looked correctly pre-filled. The cost
 * lands a fortnight later on the stages whose reference pickers are scoped to the linked workshop:
 * `refScope: "WORKSHOP"` falls back to the whole table and prints "This design workshop is not
 * linked to a workshop record yet, so every documented record is offered" — a designer choosing
 * participants from the entire repository instead of this workshop's roster.
 *
 * The spec drives the real picker and then asks the SERVER, after the app's own sync has sent it,
 * whether the link is there — a draft read alone would not prove the field survives the push.
 */

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

  // Pick a real workshop to start from. The first option is the "do not link" sentinel.
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

  /*
    THE COURTYARD. The create POST never reaches a server, which is the path the form is written
    for — and it is also the path taken when the request merely fails while online, so this is not
    an exotic case. Expressed as an aborted request rather than an offline context because
    `router.push` cannot fetch the next route's payload with no connection, and the tab then lands
    on the browser's own error page where IndexedDB cannot be read.
  */
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

  /* ── And it survives the push. ────────────────────────────────────────────────────────── */
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
