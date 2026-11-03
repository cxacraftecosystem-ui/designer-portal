import { expect, test, type Page } from "@playwright/test";

import { API, CREDENTIALS_MISSING, apiToken, bearer, signInWithToken } from "./support/session";

const STAGE = "TRADITIONAL_PROCESS_BASELINE";

test.skip(Boolean(CREDENTIALS_MISSING), CREDENTIALS_MISSING);

async function readStage(page: Page, remoteId: string) {
  return page.evaluate(
    async ([id, stageKey]) => {
      const db: IDBDatabase = await new Promise((resolve, reject) => {
        const open = indexedDB.open("design-workshop-drafts");
        open.onsuccess = () => resolve(open.result);
        open.onerror = () => reject(open.error);
      });
      const rows: unknown[] = await new Promise((resolve, reject) => {
        const rq = db.transaction("drafts", "readonly").objectStore("drafts").getAll();
        rq.onsuccess = () => resolve(rq.result as unknown[]);
        rq.onerror = () => reject(rq.error);
      });
      const draft = (rows as Record<string, any>[]).find((r) => r.remoteId === id);
      const stage = draft?.stages?.[stageKey as string];
      return {
        found: Boolean(draft),
        toolRows: (stage?.collections?.tool ?? null) as unknown[] | null,
        serverLoadedAt: (stage?.serverLoadedAt ?? null) as number | null,
        dirtyAt: (stage?.dirtyAt ?? null) as number | null
      };
    },
    [remoteId, STAGE] as const
  );
}

test("a stage emptied on the server loses its rows in the local draft", async ({ page, request }) => {
  const auth = bearer(await apiToken(request));

  const created = await request.post(`${API}/api/design-workshops`, {
    headers: auth,
    data: { title: `stale rows ${Date.now()}`, templateId: "DCH_STANDARD" }
  });
  expect(created.ok(), `create: ${await created.text()}`).toBeTruthy();
  const id = (await created.json()).id as string;

  const put1 = await request.put(`${API}/api/design-workshops/${id}/stages/${STAGE}`, {
    headers: auth,
    data: {
      entries: [{ entityKey: "tool", ordinal: 0, data: { name: "Probe loom", _clientKey: "stale-key-1" } }],
      replaceCollections: false,
      submit: false
    }
  });
  expect(put1.ok(), `put1: ${await put1.text()}`).toBeTruthy();

  await signInWithToken(page, await apiToken(request));
  await page.goto(`/design-workshops/${id}`);
  await expect(page.getByRole("heading", { name: /stale rows/i }).first()).toBeVisible({ timeout: 60_000 });
  await page.waitForTimeout(2500);

  const before = await readStage(page, id);
  expect(before.found, "the browser cached a draft for this workshop").toBeTruthy();
  expect(before.toolRows ?? [], "control: the row was folded into the local draft").toHaveLength(1);
  expect(before.dirtyAt, "control: the stage is CLEAN, so the fold is not refused by `dirtyAt`").toBeNull();

  const put2 = await request.put(`${API}/api/design-workshops/${id}/stages/${STAGE}`, {
    headers: auth,
    data: { entries: [], replaceCollections: true, emptiedEntities: ["tool"], submit: false }
  });
  expect(put2.ok(), `put2: ${await put2.text()}`).toBeTruthy();

  const serverNow = await request.get(`${API}/api/design-workshops/${id}/stages`, { headers: auth });
  const serverStages = (await serverNow.json()).stages;
  expect(serverStages[STAGE], "the server now reports nothing at all for this stage").toBeUndefined();

  await page.goto("/design-workshops");
  await page.waitForTimeout(1000);
  await page.goto(`/design-workshops/${id}`);
  await expect(page.getByRole("heading", { name: /stale rows/i }).first()).toBeVisible({ timeout: 60_000 });
  await page.waitForTimeout(2500);

  const after = await readStage(page, id);
  expect(after.toolRows ?? [], "the local draft holds no row the server has deleted").toHaveLength(0);
  expect(after.serverLoadedAt, "the stage records that the server's copy was read").not.toBeNull();
});
