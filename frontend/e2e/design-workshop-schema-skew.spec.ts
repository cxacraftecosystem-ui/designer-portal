import { expect, test, type Page } from "@playwright/test";

import { API, apiToken, CREDENTIALS_MISSING, signIn } from "./support/session";

/**
 * A STAGE REFUSED BECAUSE THE CLIENT WAS AHEAD OF THE SERVER SYNCS ITSELF ONCE THE SERVER CATCHES UP.
 *
 * REPORTED, TWICE, IN THIS FORM. A designer opened a workshop and was told:
 *
 *   "The repository refused stage 'CLUSTER_CRAFT_BACKGROUND': merge: Extra inputs are not permitted
 *    … it will keep being refused until the answer that caused it is corrected — this is not a
 *    connection problem. Open the stage, then use Try again."
 *
 * There was no such answer. The client had sent the then-new `merge` flag to an API that predated
 * it, `APIModel` is `extra="forbid"`, and every never-downloaded singleton came back 422. By the
 * time the designer read the banner the API had been taught `merge` and the identical PUT answered
 * 200 — but the refusal had been recorded `permanent: true`, and a permanent failure is stepped over
 * by every future pass. The app could not recover from a version skew after the skew had closed, and
 * the only way out was a button the designer had no reason to press, on a stage whose problem was
 * never theirs.
 *
 * WHAT THESE SPECS ASSERT, and it is deliberately the outcome rather than the mechanism: after a
 * schema refusal, a server that accepts the payload leaves the stage SYNCED, with nobody clicking
 * anything. The second one starts from the exact state the report came from — a refusal already
 * sitting in IndexedDB, written by a build that could not tell the two kinds of refusal apart.
 *
 * The 422 body is the one the running API returns; `schema-skew-retry-unit.spec.ts` carries the
 * capture and the policy in a form that runs without a stack.
 */

const STAGE_KEY = "CLUSTER_CRAFT_BACKGROUND";
const ENTITY_KEY = "clusterBackground";

/** What the API answers for an unknown key. `type` is pydantic's own discriminator, not prose. */
const EXTRA_FORBIDDEN = JSON.stringify({
  detail: [
    {
      type: "extra_forbidden",
      loc: ["body", "entries", 0, "merge"],
      msg: "Extra inputs are not permitted",
      input: true
    }
  ]
});

test.skip(!!CREDENTIALS_MISSING, CREDENTIALS_MISSING);

function richDoc(text: string) {
  return { blocks: [{ kind: "PARAGRAPH", spans: [{ text }] }] };
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
          entityKey: ENTITY_KEY,
          data: {
            clusterIntroduction: richDoc("Bagru lies thirty kilometres west of Jaipur."),
            craftIntroduction: richDoc("Hand block printing with natural dyes."),
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

/** The stage's sync bookkeeping, as the store holds it on disk. */
async function stageRecord(page: Page, workshopId: string) {
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
      const stage = (draft?.stages as Record<string, Record<string, unknown>> | undefined)?.[key];
      return {
        found: Boolean(stage),
        dirtyAt: (stage?.dirtyAt as number | null) ?? null,
        failure:
          (stage?.failure as { message: string; permanent: boolean; skewRun?: string | null } | null) ?? null
      };
    },
    { id: workshopId, key: STAGE_KEY }
  );
}

/** Edit one plain field so the stage has something genuinely unsent. */
async function editStage(page: Page, workshopId: string, value: string) {
  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);
  const plain = page.getByRole("textbox", { name: /GI registration details/i });
  await expect(plain).toHaveValue(/GI registered/, { timeout: 30_000 });
  await plain.fill(value);
  await expect(page.getByText("Saved on this device only", { exact: true })).toBeVisible({ timeout: 10_000 });
  // Past the 800ms autosave debounce, so the edit is banked in IndexedDB before the navigation
  // rather than racing the document teardown. Same wait, same reason, as the sibling refusal spec.
  await page.waitForTimeout(1_500);
}

test("a schema refusal is recorded, then cleared by the next app run once the server accepts", async ({ page }) => {
  const token = await apiToken(page.request);
  const workshopId = await seedWorkshop(page, token, `Schema skew ${Date.now()}`);
  await signIn(page);
  await editStage(page, workshopId, "GI registered 2011, renewed 2021");

  // AN API THAT PREDATES SOMETHING THIS BUILD SENDS. Not invented: this is the shape `extra="forbid"`
  // produces, and it is what the field saw on 2026-08-08 for the `merge` key.
  await page.route(`**/api/design-workshops/${workshopId}/stages/**`, async (route) => {
    if (route.request().method() !== "PUT") return route.fallback();
    await route.fulfill({ status: 422, contentType: "application/json", body: EXTRA_FORBIDDEN });
  });

  // The banner drains on mount, with nobody pressing anything.
  await page.goto("/design-workshops");
  await page.waitForTimeout(3_000);

  const refused = await stageRecord(page, workshopId);
  expect(refused.failure, "the refusal is recorded against the stage that caused it").not.toBeNull();
  expect(
    refused.failure!.message,
    "and it does NOT tell the designer to correct an answer — no answer of theirs is involved"
  ).toContain("out of step");
  expect(
    refused.failure!.skewRun,
    "and it is marked as waiting for an update rather than for a person"
  ).toBeTruthy();
  await expect(
    page.getByText(/out of step/i),
    "the designer is told; a stage that is silently not syncing is worse than one that says so"
  ).toBeVisible({ timeout: 15_000 });

  // THE SERVER CATCHES UP — the state the defect was reported in, where the API had learned `merge`
  // and the banner was still refusing to make the request.
  await page.unroute(`**/api/design-workshops/${workshopId}/stages/**`);

  // A reload is a new app run, and nothing else happens: no Try again, no edit, no click.
  await page.goto("/design-workshops");
  await page.waitForTimeout(5_000);

  const after = await stageRecord(page, workshopId);
  expect(after.failure, "the refusal is gone because the stage went up, not because it was hidden").toBeNull();
  expect(after.dirtyAt, "and there is nothing left unsent").toBeNull();

  // Confirmed at the repository, which is the only place that can say the work is safe.
  const server = await page.request.get(`${API}/api/design-workshops/${workshopId}/stages/${STAGE_KEY}`, {
    headers: { Authorization: `Bearer ${token}` }
  });
  expect(server.ok(), `read the stage back: ${await server.text()}`).toBeTruthy();
  expect(JSON.stringify(await server.json())).toContain("GI registered 2011, renewed 2021");
});

test("a refusal already on disk from an older build is re-triaged rather than held for ever", async ({ page }) => {
  const token = await apiToken(page.request);
  const workshopId = await seedWorkshop(page, token, `Schema skew stored ${Date.now()}`);
  await signIn(page);
  await editStage(page, workshopId, "GI registered 2011, renewed 2022");

  await page.route(`**/api/design-workshops/${workshopId}/stages/**`, async (route) => {
    if (route.request().method() !== "PUT") return route.fallback();
    await route.fulfill({ status: 422, contentType: "application/json", body: EXTRA_FORBIDDEN });
  });
  await page.goto("/design-workshops");
  await page.waitForTimeout(3_000);
  expect((await stageRecord(page, workshopId)).failure, "there is a refusal to age").not.toBeNull();

  // WIND THE RECORD BACK TO WHAT THE DESIGNER ACTUALLY HAS ON DISK: a v2 document whose failure
  // carries no `skewRun`, because the build that wrote it could not tell a dialect mismatch from a
  // rejected field. Without the v3 rung this is exactly the stuck state that was reported — the
  // stage is stepped over by every pass and the banner never changes.
  const rewritten = await page.evaluate(
    async ({ id, key }) => {
      const db: IDBDatabase = await new Promise((resolve, reject) => {
        const open = indexedDB.open("design-workshop-drafts");
        open.onsuccess = () => resolve(open.result);
        open.onerror = () => reject(open.error);
      });
      const store = db.transaction("drafts", "readwrite").objectStore("drafts");
      const rows: Record<string, unknown>[] = await new Promise((resolve, reject) => {
        const rq = store.getAll();
        rq.onsuccess = () => resolve(rq.result as Record<string, unknown>[]);
        rq.onerror = () => reject(rq.error);
      });
      const draft = rows.find((row) => row.remoteId === id || row.localId === id);
      if (!draft) return false;
      const stages = draft.stages as Record<string, Record<string, unknown>>;
      const failure = stages[key].failure as Record<string, unknown>;
      delete failure.skewRun;
      failure.message =
        `The repository refused stage “${key}”: merge: Extra inputs are not permitted It is still on this device ` +
        "and nothing has been thrown away, but it will keep being refused until the answer that caused it is " +
        "corrected — this is not a connection problem. Open the stage, then use Try again.";
      draft.schemaVersion = 2;
      await new Promise((resolve, reject) => {
        const rq = store.put(draft);
        rq.onsuccess = () => resolve(null);
        rq.onerror = () => reject(rq.error);
      });
      return true;
    },
    { id: workshopId, key: STAGE_KEY }
  );
  expect(rewritten, "the stored refusal was wound back to the reported shape").toBe(true);

  // The server has since been updated, as it had been when this was reported.
  await page.unroute(`**/api/design-workshops/${workshopId}/stages/**`);
  await page.goto("/design-workshops");
  await page.waitForTimeout(5_000);

  const after = await stageRecord(page, workshopId);
  expect(after.failure, "the stale refusal did not survive the update that closed its cause").toBeNull();
  expect(after.dirtyAt, "and the fortnight of work is at the repository").toBeNull();
});
