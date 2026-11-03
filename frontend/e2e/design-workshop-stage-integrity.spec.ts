import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

const STAGE_KEY = "CLUSTER_CRAFT_BACKGROUND";
const ENTITY_KEY = "clusterBackground";

function richDoc(text: string) {
  return { blocks: [{ kind: "PARAGRAPH", spans: [{ text }] }] };
}

const SEEDED_STAGE: Record<string, unknown> = {
  clusterIntroduction: richDoc("Bagru lies thirty kilometres west of Jaipur."),
  craftIntroduction: richDoc("Hand block printing with natural dyes."),
  history: richDoc("Three hundred years of dabu resist printing."),
  traditionalProducts: richDoc("Bedcovers, saris and yardage."),
  geography: richDoc("On the Sanganer road, beside the Sanjaria river."),
  artisanHouseholds: 412,
  giDetails: "GI registered 2011"
};

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
      entries: [{ entityKey: ENTITY_KEY, data: SEEDED_STAGE }],
      replaceCollections: false,
      submit: false
    }
  });
  expect(saved.ok(), `seed stage 4: ${await saved.text()}`).toBeTruthy();
  return id;
}

async function serverStageKeys(page: Page, token: string, workshopId: string): Promise<string[]> {
  const res = await page.request.get(`${API}/api/design-workshops/${workshopId}/stages/${STAGE_KEY}`, {
    headers: { Authorization: `Bearer ${token}` }
  });
  expect(res.ok(), `read stage 4 back: ${await res.text()}`).toBeTruthy();
  return Object.keys((await res.json()).singleton ?? {}).sort();
}

async function localStage(page: Page, workshopId: string) {
  for (let attempt = 0; ; attempt += 1) {
    try {
      return await page.evaluate(
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
          if (!draft) return null;
          const stage = (draft.stages as Record<string, Record<string, unknown>>)?.[key] ?? null;
          return stage
            ? {
                dirtyAt: (stage.dirtyAt as number | null) ?? null,
                lastPushedAt: (stage.lastPushedAt as number | null) ?? null,
                serverLoadedAt: (stage.serverLoadedAt as number | null) ?? null,
                fieldCount: Object.keys(
                  ((stage.singletons as Record<string, Record<string, unknown>>) ?? {}).clusterBackground ?? {}
                ).length
              }
            : null;
        },
        { id: workshopId, key: STAGE_KEY }
      );
    } catch (error) {
      if (attempt >= 8) throw error;
      await page.waitForTimeout(500);
    }
  }
}

async function plantLegacyBlankDraft(page: Page, workshopId: string): Promise<void> {
  await page.evaluate(
    async ({ id, key }) => {
      const db: IDBDatabase = await new Promise((resolve, reject) => {
        const open = indexedDB.open("design-workshop-drafts", 1);
        open.onupgradeneeded = () => {
          const created = open.result;
          if (!created.objectStoreNames.contains("drafts")) created.createObjectStore("drafts", { keyPath: "localId" });
          if (!created.objectStoreNames.contains("media")) {
            created.createObjectStore("media", { keyPath: "id" }).createIndex("byDraft", "localId", { unique: false });
          }
          if (!created.objectStoreNames.contains("registry")) created.createObjectStore("registry", { keyPath: "version" });
        };
        open.onsuccess = () => resolve(open.result);
        open.onerror = () => reject(open.error);
      });
      const now = Date.now();
      await new Promise<void>((resolve, reject) => {
        const store = db.transaction("drafts", "readwrite").objectStore("drafts");
        const rq = store.put({
          schemaVersion: 1,
          localId: `dwlocal-legacy-${now}`,
          remoteId: id,
          header: {
            title: "",
            templateId: "DCH_STANDARD",
            status: "DRAFT",
            craftName: null,
            clusterName: null,
            state: null,
            district: null,
            startDate: null,
            endDate: null,
            workshopId: null,
            notes: null,
            workshopCode: null,
            venue: null,
            designerName: null
          },
          headerDirtyAt: null,
          stages: {
            [key]: {
              stageKey: key,
              singletons: {},
              collections: {},
              removedFrom: [],
              updatedAt: now,
              dirtyAt: now,
              lastPushedAt: null,
              completeness: null,
              failure: null
            }
          },
          createdAt: now,
          updatedAt: now,
          registryVersion: "",
          ownerUserId: null,
          lastSyncedAt: null,
          failure: null
        });
        rq.onsuccess = () => resolve();
        rq.onerror = () => reject(rq.error);
      });
    },
    { id: workshopId, key: STAGE_KEY }
  );
}

test("a blank stage already banked by the broken build is recovered, not pushed", async ({ page }) => {
  const token = await apiToken(page);
  const workshopId = await seedWorkshop(page, token, `Legacy blank recovery ${Date.now()}`);
  await signIn(page);

  await page.goto("/dashboard");
  await plantLegacyBlankDraft(page, workshopId);

  await page.goto(`/design-workshops/${workshopId}`);
  await page.waitForTimeout(2_000);

  await page.goto("/design-workshops");
  await page.waitForTimeout(3_000);

  expect(
    await serverStageKeys(page, token, workshopId),
    "the seven fields survive a sync pass over a draft banked blank by the previous build"
  ).toEqual(Object.keys(SEEDED_STAGE).sort());

  const recovered = await localStage(page, workshopId);
  expect(
    recovered!.dirtyAt,
    "and the artefact stops claiming to be unsent fieldwork, so the fold is no longer refused"
  ).toBeNull();

  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);
  await expect(page.getByRole("textbox", { name: /GI registration details/i })).toHaveValue("GI registered 2011", {
    timeout: 30_000
  });
});

test("opening a stage on a slow connection banks nothing and erases nothing", async ({ page }) => {
  const token = await apiToken(page);
  const workshopId = await seedWorkshop(page, token, `Stage integrity ${Date.now()}`);
  expect(await serverStageKeys(page, token, workshopId), "the fixture is on the server").toHaveLength(7);

  await signIn(page);

  await page.route(`**/api/design-workshops/${workshopId}/stages/${STAGE_KEY}*`, async (route) => {
    if (route.request().method() !== "GET") return route.fallback();
    await new Promise((resolve) => setTimeout(resolve, 2500));
    await route.continue();
  });

  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);

  await expect(
    page.getByText(/Loading this stage\. It isn't on this device yet/i),
    "a stage this device has never downloaded waits for the repository instead of drawing blank"
  ).toBeVisible({ timeout: 5_000 });

  await expect(page.getByRole("textbox", { name: /GI registration details/i })).toHaveValue("GI registered 2011", {
    timeout: 30_000
  });

  await page.waitForTimeout(2_000);
  const banked = await localStage(page, workshopId);
  expect(banked, "the stage was folded into the local draft").not.toBeNull();
  expect(banked!.dirtyAt, "merely opening a stage must not mark it as unsent fieldwork").toBeNull();
  expect(banked!.serverLoadedAt, "the fold recorded that this device has now read the server's copy").not.toBeNull();
  expect(banked!.fieldCount, "the local copy holds the server's seven answers, not nothing").toBe(7);

  await expect(
    page.getByText("Saved on this device only", { exact: true }),
    "the unsent chip is not shown for a stage nobody has touched"
  ).toHaveCount(0);

  await page.goto("/design-workshops");
  await page.waitForTimeout(1_500);
  await page.goto(`/design-workshops/${workshopId}`);
  await page.waitForTimeout(2_500);

  expect(
    await serverStageKeys(page, token, workshopId),
    "the seven fields written up in the office are still on the server"
  ).toEqual(Object.keys(SEEDED_STAGE).sort());
});

test("a saved stage stays saved: the sent readout appears and the unsent chip does not come back", async ({ page }) => {
  const token = await apiToken(page);
  const workshopId = await seedWorkshop(page, token, `Stage save readout ${Date.now()}`);
  await signIn(page);

  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);
  const plain = page.getByRole("textbox", { name: /GI registration details/i });
  await expect(plain).toHaveValue("GI registered 2011", { timeout: 30_000 });

  await plain.fill("GI registered 2011, renewed 2021");
  await expect(page.getByText("Saved on this device only", { exact: true }), "a real edit marks the stage unsent").toBeVisible({
    timeout: 10_000
  });

  await page.getByRole("button", { name: "Save stage", exact: true }).click();
  await expect(page.getByText(/Stage saved/), "the push landed").toBeVisible({ timeout: 30_000 });

  await expect(
    page.getByText(/Uploaded at/),
    "the stage says it reached the repository"
  ).toBeVisible({ timeout: 15_000 });

  await page.waitForTimeout(2_500);
  await expect(
    page.getByText("Saved on this device only", { exact: true }),
    "the unsent chip does not come back on a stage that has just landed"
  ).toHaveCount(0);
  await expect(page.getByText(/Uploaded at/)).toBeVisible();

  const banked = await localStage(page, workshopId);
  expect(banked!.dirtyAt, "the store agrees: nothing is outstanding").toBeNull();
  expect(banked!.lastPushedAt, "the push was recorded").not.toBeNull();
});

test("a repository that answers with a 500 is reported as a refusal, not as a lost connection", async ({ page }) => {
  const token = await apiToken(page);
  const workshopId = await seedWorkshop(page, token, `Stage 500 triage ${Date.now()}`);
  await signIn(page);

  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);
  const plain = page.getByRole("textbox", { name: /GI registration details/i });
  await expect(plain).toHaveValue("GI registered 2011", { timeout: 30_000 });

  await page.route(`**/api/design-workshops/${workshopId}/stages/${STAGE_KEY}*`, async (route) => {
    if (route.request().method() !== "PUT") return route.fallback();
    await route.fulfill({ status: 500, contentType: "application/json", body: '{"detail":"UnicodeEncodeError"}' });
  });

  await plain.fill("GI registered 2011, renewed 2021");
  await page.getByRole("button", { name: "Save stage", exact: true }).click();

  await expect(
    page.getByText(/couldn't be saved:/),
    "a 5xx is named as a refusal, and the stage is named with it"
  ).toBeVisible({ timeout: 30_000 });
  await expect(
    page.getByText(/could not be reached|no connection/i),
    "the designer is not sent to look at their signal for a fault the server reported"
  ).toHaveCount(0);
});
