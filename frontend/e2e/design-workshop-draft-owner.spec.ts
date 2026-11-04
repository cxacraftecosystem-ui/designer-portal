import { expect, test, type Page } from "@playwright/test";

import { signIn as signInAs } from "./support/session";

const API = process.env.E2E_API_URL ?? "http://localhost:8000";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

const A = { email: "designer@example.org", password: PASSWORD };
const B = { email: "admin2@example.org", password: PASSWORD };

test.skip(!PASSWORD, "Set E2E_PASSWORD to run the signed-in specs.");

async function signIn(page: Page, who: { email: string; password: string }) {
  await signInAs(page, who.email, who.password);
}

async function signOut(page: Page) {
  await page.goto("/dashboard");
  await page.context().clearCookies();
  await page.goto("/login");
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
          localId: row.localId as string,
          remoteId: (row.remoteId as string | null) ?? null,
          ownerUserId: (row.ownerUserId as string | null) ?? null,
          title: ((row.header as Record<string, unknown>)?.title as string) ?? "",
          failure: (row.failure as { message: string; permanent: boolean } | null) ?? null
        }));
      });
    } catch (error) {
      if (attempt >= 8) throw error;
      await page.waitForTimeout(500);
    }
  }
}

test("a second designer signing in on the same laptop does not drain the first one's workshop", async ({ page }) => {
  const title = `Owner handover ${Date.now()}`;

  await signIn(page, A);
  await page.route("**/api/design-workshops", async (route) => {
    if (route.request().method() !== "POST") return route.fallback();
    await route.abort("internetdisconnected");
  });
  await page.goto("/design-workshops");
  await page.getByRole("button", { name: /new design workshop/i }).click();
  await page.locator('input[name="title"]').fill(title);
  await page.getByRole("button", { name: /create design workshop/i }).click();
  await page.waitForURL(/\/design-workshops\/dwlocal-/, { timeout: 30_000 });

  const captured = (await drafts(page)).filter((draft) => draft.title === title);
  expect(captured, "A's workshop was banked on this device").toHaveLength(1);
  expect(captured[0].remoteId, "and has never reached the repository").toBeNull();
  expect(captured[0].ownerUserId, "and is stamped with the designer who captured it").toBeTruthy();
  const ownerOfA = captured[0].ownerUserId;

  await signOut(page);
  await page.unroute("**/api/design-workshops");
  expect(
    (await drafts(page)).find((draft) => draft.title === title)?.remoteId,
    "A's workshop is still unsent at the moment the laptop changes hands"
  ).toBeNull();
  await signIn(page, B);

  await page.goto("/design-workshops");
  await page.waitForTimeout(2_000);
  await page.goto("/dashboard");
  await page.waitForTimeout(2_000);

  const underB = (await drafts(page)).filter((draft) => draft.title === title);
  expect(underB, "A's draft is still on the laptop — a handover must not delete it").toHaveLength(1);
  expect(
    underB[0].remoteId,
    "B's session must not have created A's workshop in the repository under B's name"
  ).toBeNull();
  expect(underB[0].ownerUserId, "and the owner is unchanged").toBe(ownerOfA);
  expect(
    underB[0].failure,
    "nor may it be marked as permanently refused — that is what stops A retrying it for good"
  ).toBeNull();

  await page.goto("/design-workshops");
  await page.waitForTimeout(1_000);
  await expect(page.getByText(title), "A's unsent workshop is not listed in B's session").toHaveCount(0);

  await signOut(page);
  await signIn(page, A);
  await page.goto("/design-workshops");

  let remoteId: string | null = null;
  for (let attempt = 0; attempt < 30 && !remoteId; attempt += 1) {
    const rows = (await drafts(page)).filter((draft) => draft.title === title);
    remoteId = rows[0]?.remoteId ?? null;
    if (!remoteId) {
      const sync = page.getByRole("button", { name: /sync now/i });
      if (await sync.count()) await sync.first().click().catch(() => undefined);
      await page.waitForTimeout(1_000);
    }
  }
  expect(remoteId, "A's own session sends A's workshop — the guard must not strand fieldwork").toBeTruthy();

  const login = await page.request.post(`${API}/api/auth/login`, { data: A });
  const token = (await login.json()).accessToken as string;
  const detail = await page.request.get(`${API}/api/design-workshops/${remoteId}`, {
    headers: { Authorization: `Bearer ${token}` }
  });
  expect(detail.ok(), `A can read the workshop A captured: ${await detail.text()}`).toBeTruthy();
  expect((await detail.json()).title).toBe(title);
});
