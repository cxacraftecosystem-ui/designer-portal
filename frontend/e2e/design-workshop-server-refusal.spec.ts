import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

/**
 * A SERVER THAT ANSWERED AND REFUSED IS NOT A LOST CONNECTION.
 *
 * `isTransient` answers one question — "is it worth retrying" — and it counts every status ≥ 500 as
 * yes. That is right for the outbox and wrong for anything a designer READS, because a 5xx means the
 * server was reached and then failed. Three design-workshop call sites used it as the offline test:
 * the stage save said "Saved on this device. The repository could not be reached…", the workshop
 * list raised its amber "there is no connection, this shows only what is saved in this browser"
 * banner over a full repository, and the draft store's drain set `stoppedOffline` and broke the pass
 * without marking anything failed — so a stage the server had permanently refused was retried, in
 * silence, for as long as the app was open.
 *
 * The 5xxs are not hypothetical: a lone surrogate in a name and a non-finite decimal both produce a
 * deterministic 500 on `PUT /stages/{key}`. A designer in that state was sent to look at their
 * signal while nothing on screen named the stage or the answer that caused it.
 *
 * The report page's download handler already drew this distinction by hand — its comment says
 * "NOT isTransient here, deliberately … isTransient answers 'is it worth retrying', and it counts
 * every 5xx as yes". These specs are that rule carried to the other three sites.
 */

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

/** The stage's failure record, as the store holds it. */
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

  // The server ANSWERS, and fails.
  await page.route("**/api/design-workshops?**", async (route) => {
    if (route.request().method() !== "GET") return route.fallback();
    await route.fulfill({ status: 500, contentType: "application/json", body: '{"detail":"stale status filter"}' });
  });

  await page.goto("/design-workshops");

  await expect(
    page.getByText(/There is no connection, so this shows only the design workshops saved in this browser/i),
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

  // Edit the stage so there is genuinely something to send.
  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);
  const plain = page.getByRole("textbox", { name: /GI registration details/i });
  await expect(plain).toHaveValue("GI registered 2011", { timeout: 30_000 });
  await plain.fill("GI registered 2011, renewed 2021");
  await expect(page.getByText("Saved on this device only", { exact: true })).toBeVisible({ timeout: 10_000 });
  // Past the 800ms autosave debounce, so the edit is banked in IndexedDB before the navigation
  // rather than racing the document teardown.
  await page.waitForTimeout(1_500);

  // From here the repository answers every stage PUT with a deterministic failure — which is what a
  // lone surrogate or a non-finite decimal in one answer actually produces on this endpoint.
  await page.route(`**/api/design-workshops/${workshopId}/stages/**`, async (route) => {
    if (route.request().method() !== "PUT") return route.fallback();
    await route.fulfill({ status: 500, contentType: "application/json", body: '{"detail":"DataError"}' });
  });

  // Navigate: the banner drains on mount, with nobody pressing anything.
  await page.goto("/design-workshops");
  await page.waitForTimeout(3_000);

  const failure = await stageFailure(page, workshopId);
  expect(failure, "the refusal was recorded against the stage that caused it").not.toBeNull();
  expect(
    failure!.permanent,
    "and recorded as permanent, so the pass stops re-sending a rejection the server has already made"
  ).toBe(true);
  expect(failure!.message, "and it names the stage").toContain(STAGE_KEY);

  // On screen: the refusal, and a way to act on it — not a silent "waiting" row.
  await expect(
    page.getByText(/The repository refused stage/i),
    "the designer is told the repository refused it"
  ).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole("button", { name: /try again/i }).first()).toBeVisible();
});
