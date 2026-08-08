import { expect, test, type Page } from "@playwright/test";

import { API, CREDENTIALS_MISSING, apiToken, bearer, signInWithToken } from "./support/session";

/**
 * A stage emptied on the server must not keep its rows in this browser's draft.
 *
 * THE DEFECT THIS PINS. `adoptServerDetail` folds the server's copy of a workshop into the local
 * draft stage by stage. When the server says NOTHING about a stage — which is how `_stages_payload`
 * reports a stage with no entries at all — the fold took the `!incoming` branch, whose comment reads
 * "this device has now seen it and holds the same emptiness". It did not. The branch spread
 * `...current` and changed only `serverLoadedAt`, so every row the local copy already held survived,
 * AND the stage was stamped with this store's word for "the server's copy has been read into this
 * stage". A row the ministry had deleted then read as downloaded and current on the handset that
 * mattered, and the next push could put it back.
 *
 * IT IS THE `dirtyAt` GUARD ABOVE THAT MAKES CLEARING SAFE, and it is why this spec sets up the way
 * it does: the row is written through the API and folded in by a plain page read, so the stage is
 * clean. Fieldwork this device has typed and not yet sent takes the earlier branch and is never
 * touched — a spec that dirtied the stage first would pass against the bug.
 *
 * WHY THE ASSERTION READS INDEXEDDB RATHER THAN THE SCREEN. The stale row's visible consequence is
 * a printed artisan card for somebody no longer on the roster, several screens away from the fold
 * that caused it. The draft is where the defect actually lives, and reading it keeps the failure
 * naming the store instead of a card component that is behaving correctly.
 */

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

  // ONE collection row and NO singleton entry, so deleting the row leaves the stage with zero entry
  // rows — which is the only shape that makes `_stages_payload` omit the stage entirely.
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
  // The control. Without this the test would pass against a browser that never read the row at all.
  expect(before.toolRows ?? [], "control: the row was folded into the local draft").toHaveLength(1);
  expect(before.dirtyAt, "control: the stage is CLEAN, so the fold is not refused by `dirtyAt`").toBeNull();

  // Delete the only row, server-side, exactly the way the app itself would.
  const put2 = await request.put(`${API}/api/design-workshops/${id}/stages/${STAGE}`, {
    headers: auth,
    data: { entries: [], replaceCollections: true, emptiedEntities: ["tool"], submit: false }
  });
  expect(put2.ok(), `put2: ${await put2.text()}`).toBeTruthy();

  const serverNow = await request.get(`${API}/api/design-workshops/${id}/stages`, { headers: auth });
  const serverStages = (await serverNow.json()).stages;
  expect(serverStages[STAGE], "the server now reports nothing at all for this stage").toBeUndefined();

  // A plain background read of the workshop — what happens every time the page opens.
  await page.goto("/design-workshops");
  await page.waitForTimeout(1000);
  await page.goto(`/design-workshops/${id}`);
  await expect(page.getByRole("heading", { name: /stale rows/i }).first()).toBeVisible({ timeout: 60_000 });
  await page.waitForTimeout(2500);

  const after = await readStage(page, id);
  expect(after.toolRows ?? [], "the local draft holds no row the server has deleted").toHaveLength(0);
  // The other half of the same claim: having adopted the emptiness, the stage may say it has read
  // the server. Before the fix it said so while still holding the row, which is the worse failure.
  expect(after.serverLoadedAt, "the stage records that the server's copy was read").not.toBeNull();
});
