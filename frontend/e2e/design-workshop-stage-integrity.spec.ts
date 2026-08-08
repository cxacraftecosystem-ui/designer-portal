import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

/**
 * The stage form must not be able to DESTROY a stage it never read, and must tell the truth about
 * where the answers are.
 *
 * Four confirmed defects lived in one file and one debounce, and every one of them was invisible on
 * screen:
 *
 *  1. Opening a stage seeded the form from the local draft and raised a `hydrated` ref on the very
 *     next line — synchronously, before React had committed the seeded state. The autosave effect
 *     therefore ran on exactly the render it was written to skip, and `putDraftStage` banked the
 *     stage with `dirtyAt` set 800ms after mount, having been typed into by nobody. On a stage this
 *     browser had never downloaded, the banked copy was BLANK.
 *  2. `adoptServerStage` then refused to fold the server's real answers into what it had been told
 *     was unsent fieldwork — correctly, by its own rule — so the blank copy outranked the truth.
 *  3. The next sync pass PUT `{"entityKey": "clusterBackground", "data": {}}`, and `save_stage`
 *     replaces a singleton row's data wholesale and writes no `RecordRevision` for stage entries.
 *     Seven fields, four of them BASIC-tier narratives written up in an office, gone in place.
 *  4. After a successful save, `setRemovedFrom([])` handed React a fresh array — never
 *     `Object.is`-equal — so the same effect re-dirtied a stage that had just landed. The amber
 *     "Saved on this device only" chip came back within a second of every save and the "Sent to the
 *     repository at …" readout was unreachable, on the one control a designer reads to decide
 *     whether they can pack up.
 *
 * WHY THESE ARE BROWSER TESTS AND NOT UNIT TESTS. Every one of them is a question about the order
 * React commits state in and what a debounce does between two awaits. A unit test of
 * `putDraftStage` passes in the broken world — the store was always doing exactly what it was
 * asked. Only mounting the page, delaying the read the way one bar of signal delays it, and then
 * asking the SERVER what it still holds can tell the two worlds apart.
 *
 * Every fixture is created fresh through the real API. Nothing here touches the seeded workshop.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

/**
 * Stage 4 is the defect's own stage: one singleton entity, seven fields with four BASIC-tier
 * rich-text narratives, and no collections — so an empty PUT for it is unambiguously destructive
 * and the count of surviving keys is the whole assertion.
 */
const STAGE_KEY = "CLUSTER_CRAFT_BACKGROUND";
const ENTITY_KEY = "clusterBackground";

/** The stored rich-text shape — `{"blocks":[…]}`, never HTML. See `lib/richText.ts`. */
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

/** A fresh workshop with stage 4 written up, exactly as it would be in an office. */
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

/** What the server holds for stage 4 right now. */
async function serverStageKeys(page: Page, token: string, workshopId: string): Promise<string[]> {
  const res = await page.request.get(`${API}/api/design-workshops/${workshopId}/stages/${STAGE_KEY}`, {
    headers: { Authorization: `Bearer ${token}` }
  });
  expect(res.ok(), `read stage 4 back: ${await res.text()}`).toBeTruthy();
  return Object.keys((await res.json()).singleton ?? {}).sort();
}

/** The stage as this browser's IndexedDB draft holds it — the store's own record of what is unsent. */
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
      // The store may still be opening on the very first paint.
      if (attempt >= 8) throw error;
      await page.waitForTimeout(500);
    }
  }
}

/**
 * Write a draft in the SHAPE THE BROKEN BUILD LEFT ON DISK: schema version 1, no `serverLoadedAt`
 * key at all, and a stage marked as unsent fieldwork that holds nothing whatsoever.
 *
 * This is not a hypothetical document. It is what every laptop that opened a stage on a slow
 * connection is carrying right now, and the fix has to reach it: a new build that only stops
 * CREATING the artefact would still push the ones already banked, on its first pass, before anybody
 * touched anything.
 */
async function plantLegacyBlankDraft(page: Page, workshopId: string): Promise<void> {
  await page.evaluate(
    async ({ id, key }) => {
      const db: IDBDatabase = await new Promise((resolve, reject) => {
        // Version 1 and the three stores by name, exactly as `openDb` creates them — the container
        // version is IndexedDB's own and is NOT the document version this fix bumped. Opening
        // without a version races the app's own first open: on a page with no drafts to show,
        // nothing has necessarily created the object stores by the time this runs.
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
              // The artefact: banked as unsent fieldwork by a form nobody typed into.
              dirtyAt: now,
              lastPushedAt: null,
              completeness: null,
              failure: null
            }
          },
          createdAt: now,
          updatedAt: now,
          registryVersion: "",
          // Null, as every draft written before the owner was stamped — it must still be readable
          // and sendable by the session sitting here, or the fix strands existing fieldwork.
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

  // Get onto the origin so IndexedDB is reachable, then plant the poisoned draft — the app then
  // reads it exactly as it would on the morning after an upgrade.
  await page.goto("/dashboard");
  await plantLegacyBlankDraft(page, workshopId);

  // The workshop index FIRST, and not merely for realism: it is what caches the field registry in
  // this browser, and `runSync` cannot build a stage payload without one. Without this step the
  // old build would fail to send for the wrong reason and the spec would pass over the bug it is
  // written for. The index also folds the server's copy in — and correctly refuses to, because the
  // artefact claims to be unsent fieldwork, which is exactly how the blank copy survives to be
  // pushed.
  await page.goto(`/design-workshops/${workshopId}`);
  await page.waitForTimeout(2_000);

  // The banner drains on mount, with nobody pressing anything. This is the pass that used to send
  // `{"entityKey": "clusterBackground", "data": {}}` over seven real answers.
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

  // Which means opening the stage now shows the real answers rather than seven empty boxes.
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

  /*
    ONE BAR OF SIGNAL, EXPRESSED AS 2.5 SECONDS. The defect needs only that the stage GET take
    longer than the 800ms autosave debounce; 2.5s is a village, not a pathological case. Only the
    read is delayed — a PUT the page decides to make must go straight through, or the spec would be
    proving something about latency instead of about what the page chose to send.
  */
  await page.route(`**/api/design-workshops/${workshopId}/stages/${STAGE_KEY}*`, async (route) => {
    if (route.request().method() !== "GET") return route.fallback();
    await new Promise((resolve) => setTimeout(resolve, 2500));
    await route.continue();
  });

  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);

  // THE FORM IS HELD BACK, and says why. A blank form drawn over a stage this device has never read
  // is not offline-first, it is a lie — and it is the surface the designer would have typed into
  // while the real answers were still in flight.
  await expect(
    page.getByText(/Reading this stage from the repository/i),
    "a stage this device has never downloaded waits for the repository instead of drawing blank"
  ).toBeVisible({ timeout: 5_000 });

  // The read lands and the real answers appear.
  await expect(page.getByRole("textbox", { name: /GI registration details/i })).toHaveValue("GI registered 2011", {
    timeout: 30_000
  });

  // NOTHING WAS TYPED, SO NOTHING IS UNSENT. Well past the 800ms debounce.
  await page.waitForTimeout(2_000);
  const banked = await localStage(page, workshopId);
  expect(banked, "the stage was folded into the local draft").not.toBeNull();
  expect(banked!.dirtyAt, "merely opening a stage must not mark it as unsent fieldwork").toBeNull();
  expect(banked!.serverLoadedAt, "the fold recorded that this device has now read the server's copy").not.toBeNull();
  expect(banked!.fieldCount, "the local copy holds the server's seven answers, not nothing").toBe(7);

  // And nothing on screen claims otherwise.
  await expect(
    page.getByText("Saved on this device only", { exact: true }),
    "the unsent chip is not shown for a stage nobody has touched"
  ).toHaveCount(0);

  /*
    NOW LET THE APP TRY TO SYNC. `DesignWorkshopDraftBanner` drains on mount and it is remounted by
    every navigation, so two client-side navigations are two full passes — which is exactly how the
    erasure reached the server in the field: nobody pressed anything.
  */
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

  // A real edit. The chip SHOULD appear for this one — that is the control working.
  await plain.fill("GI registered 2011, renewed 2021");
  await expect(page.getByText("Saved on this device only", { exact: true }), "a real edit marks the stage unsent").toBeVisible({
    timeout: 10_000
  });

  await page.getByRole("button", { name: "Save stage", exact: true }).click();
  await expect(page.getByText(/Stage saved/), "the push landed").toBeVisible({ timeout: 30_000 });

  // THE READOUT A DESIGNER PACKS UP ON. It was unreachable: `setRemovedFrom([])` re-dirtied the
  // stage within 800ms of every successful save, so the amber chip always won this race.
  await expect(
    page.getByText(/Sent to the repository at/),
    "the stage says it reached the repository"
  ).toBeVisible({ timeout: 15_000 });

  // And it is still saying so after the debounce that used to take it away.
  await page.waitForTimeout(2_500);
  await expect(
    page.getByText("Saved on this device only", { exact: true }),
    "the unsent chip does not come back on a stage that has just landed"
  ).toHaveCount(0);
  await expect(page.getByText(/Sent to the repository at/)).toBeVisible();

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

  // The server ANSWERS, and refuses. Two of these reproduce for real on this endpoint — a lone
  // surrogate in a name and a non-finite decimal both 500 it — which is why the message matters.
  await page.route(`**/api/design-workshops/${workshopId}/stages/${STAGE_KEY}*`, async (route) => {
    if (route.request().method() !== "PUT") return route.fallback();
    await route.fulfill({ status: 500, contentType: "application/json", body: '{"detail":"UnicodeEncodeError"}' });
  });

  await plain.fill("GI registered 2011, renewed 2021");
  await page.getByRole("button", { name: "Save stage", exact: true }).click();

  await expect(
    page.getByText(/The repository refused to save/),
    "a 5xx is named as a refusal, and the stage is named with it"
  ).toBeVisible({ timeout: 30_000 });
  await expect(
    page.getByText(/could not be reached|no connection/i),
    "the designer is not sent to look at their signal for a fault the server reported"
  ).toHaveCount(0);
});
