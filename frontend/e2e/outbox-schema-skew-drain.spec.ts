import { expect, test, type Page } from "@playwright/test";

import { apiToken, CREDENTIALS_MISSING, signInWithToken } from "./support/session";

test.skip(!!CREDENTIALS_MISSING, CREDENTIALS_MISSING);

const PREVIOUS_RUN = "a-run-that-is-not-this-one";

async function seedRefusedEntry(page: Page, skewRun: string | null) {
  return page.evaluate(async (stale) => {
    const db: IDBDatabase = await new Promise((resolve, reject) => {
      const open = indexedDB.open("field-repo-outbox", 1);
      open.onupgradeneeded = () => {
        const created = open.result;
        if (!created.objectStoreNames.contains("entries")) {
          created.createObjectStore("entries", { keyPath: "id", autoIncrement: true });
        }
      };
      open.onsuccess = () => resolve(open.result);
      open.onerror = () => reject(open.error);
    });
    await new Promise((resolve, reject) => {
      const rq = db
        .transaction("entries", "readwrite")
        .objectStore("entries")
        .put({
          label: "Artisan · Giriraj Prasad",
          createdAt: Date.now(),
          endpoint: "/artisans",
          method: "POST",
          body: JSON.stringify({}),
          media: [],
          attempts: 1,
          failure:
            "This copy of the app and the repository are out of step, so the repository could not read what " +
            "was sent. Nothing you entered is wrong and nothing has been thrown away.",
          skewRun: stale
        });
      rq.onsuccess = () => resolve(null);
      rq.onerror = () => reject(rq.error);
    });
    db.close();
    return true;
  }, skewRun);
}

async function storedEntry(page: Page) {
  return page.evaluate(async () => {
    const db: IDBDatabase = await new Promise((resolve, reject) => {
      const open = indexedDB.open("field-repo-outbox", 1);
      open.onsuccess = () => resolve(open.result);
      open.onerror = () => reject(open.error);
    });
    const rows: Array<{ failure: string | null; skewRun?: string | null; attempts: number }> = await new Promise(
      (resolve, reject) => {
        const rq = db.transaction("entries", "readonly").objectStore("entries").getAll();
        rq.onsuccess = () => resolve(rq.result);
        rq.onerror = () => reject(rq.error);
      }
    );
    db.close();
    return rows[0] ?? null;
  });
}

test("a queued record refused by an earlier run is re-sent by a new one, and then settles", async ({ page }) => {
  const token = await apiToken(page.request);
  await signInWithToken(page, token);
  await page.goto("/dashboard");
  await expect(page).toHaveURL(/dashboard/, { timeout: 30_000 });

  await seedRefusedEntry(page, PREVIOUS_RUN);

  let replays = 0;
  page.on("request", (request) => {
    if (request.method() === "POST" && request.url().includes("/api/artisans")) replays += 1;
  });

  await page.reload();
  await page.waitForTimeout(8_000);

  expect(replays, "the new app run re-sent it with nobody pressing anything").toBeGreaterThan(0);

  const settled = await storedEntry(page);
  expect(settled, "and the entry and its files are still on the device").not.toBeNull();
  expect(
    settled!.failure,
    "the server's answer is recorded and shown — a record silently not syncing is worse than one that says so"
  ).toBeTruthy();
  expect(
    settled!.skewRun ?? null,
    "a field the validator rejected carries no run stamp, so from here it waits for the person who typed it"
  ).toBeNull();

  replays = 0;
  await page.reload();
  await page.waitForTimeout(8_000);
  expect(replays, "a refusal only a person can clear is not re-sent by the next run").toBe(0);
});
