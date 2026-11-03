import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

const STAGE_KEY = "CLUSTER_CRAFT_BACKGROUND";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

function richDoc(text: string) {
  return { blocks: [{ kind: "PARAGRAPH", spans: [{ text }] }] };
}

async function apiToken(page: Page): Promise<string> {
  const res = await page.request.post(`${API}/api/auth/login`, { data: { email: EMAIL, password: PASSWORD } });
  expect(res.ok(), `sign-in for the API fixture: ${await res.text()}`).toBeTruthy();
  return (await res.json()).accessToken as string;
}

async function seedWorkshop(page: Page, token: string, title: string): Promise<string> {
  const headers = { Authorization: `Bearer ${token}` };
  const created = await page.request.post(`${API}/api/design-workshops`, { headers, data: { title } });
  expect(created.ok(), `create a design workshop: ${await created.text()}`).toBeTruthy();
  const id = (await created.json()).id as string;
  const saved = await page.request.put(`${API}/api/design-workshops/${id}/stages/${STAGE_KEY}`, {
    headers,
    data: {
      entries: [
        {
          entityKey: "clusterBackground",
          data: {
            clusterIntroduction: richDoc("Bagru lies thirty kilometres west of Jaipur."),
            craftIntroduction: richDoc("Hand block printing with natural dyes."),
            history: richDoc("Three hundred years of dabu resist printing."),
            traditionalProducts: richDoc("Bedcovers, saris and yardage."),
            giDetails: "GI registered 2011"
          }
        }
      ],
      replaceCollections: false,
      submit: false
    }
  });
  expect(saved.ok(), `seed stage 4: ${await saved.text()}`).toBeTruthy();
  return id;
}

async function stageFailure(page: Page, workshopId: string) {
  return page.evaluate(
    async ({ id, key }) => {
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
      const draft = rows.find((row) => row.remoteId === id || row.localId === id);
      const stage = (draft?.stages as Record<string, Record<string, unknown>>)?.[key];
      return (stage?.failure as { message: string; permanent: boolean } | null) ?? null;
    },
    { id: workshopId, key: STAGE_KEY }
  );
}

test("the workshop list reports a 500 as a server fault, not as a missing connection", async ({ page }) => {
  await signIn(page);

  await page.route("**/api/design-workshops?**", async (route) => {
    if (route.request().method() !== "GET") return route.fallback();
    await route.fulfill({ status: 500, contentType: "application/json", body: '{"detail":"stale status filter"}' });
  });

  await page.goto("/design-workshops");

  await expect(
    page.getByText(/offline, so this shows only the design workshops started on this device/i),
    "the amber offline banner must not be raised for a repository that answered"
  ).toHaveCount(0);
  await expect(
    page.getByText(/stale status filter|Unable to load design workshops/i),
    "what the server said is on screen instead"
  ).toBeVisible({ timeout: 30_000 });
});

test("a stage the repository refuses is named and stops being retried, instead of cycling as 'offline'", async ({
  page
}) => {
  const token = await apiToken(page);
  const workshopId = await seedWorkshop(page, token, `Drain refusal ${Date.now()}`);
  await signIn(page);

  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);
  const plain = page.getByRole("textbox", { name: /GI registration details/i });
  await expect(plain).toHaveValue("GI registered 2011", { timeout: 30_000 });
  await plain.fill("GI registered 2011, renewed 2021");
  await expect(page.getByText("Saved on this device only", { exact: true })).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(1_500);

  await page.route(`**/api/design-workshops/${workshopId}/stages/**`, async (route) => {
    if (route.request().method() !== "PUT") return route.fallback();
    await route.fulfill({ status: 500, contentType: "application/json", body: '{"detail":"DataError"}' });
  });

  await page.goto("/design-workshops");
  await page.waitForTimeout(3_000);

  const failure = await stageFailure(page, workshopId);
  expect(failure, "the refusal was recorded against the stage that caused it").not.toBeNull();
  expect(
    failure!.permanent,
    "and recorded as permanent, so the pass stops re-sending a rejection the server has already made"
  ).toBe(true);
  expect(failure!.message, "and it names the stage").toContain(STAGE_KEY);

  await expect(
    page.getByText(/couldn't be saved:/i).first(),
    "the designer is told the stage could not be saved"
  ).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole("button", { name: /try again/i }).first()).toBeVisible();
});
