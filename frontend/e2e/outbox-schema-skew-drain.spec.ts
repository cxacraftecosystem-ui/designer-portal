import { expect, test, type Page } from "@playwright/test";

import { apiToken, CREDENTIALS_MISSING, signInWithToken } from "./support/session";

/**
 * THE RECORDS OUTBOX HAS TO ACTUALLY REACH ITS OWN RETRY POLICY.
 *
 * `lib/offline.ts` re-attempts a refusal caused by this build of the client and this build of the
 * server disagreeing about the shape of a request ONCE PER APP RUN (`blocksRetry`), and writes a
 * sentence onto the entry promising the researcher it "will be sent by itself … you do not have to
 * do anything". A promise like that is kept by a TRIGGER, not by a predicate: something has to run
 * the pass on a new app run for `blocksRetry` to be asked at all.
 *
 * IT DID NOT. `OutboxBanner` read the store on mount and drained only on the `online` event or a
 * click, and `online` never fires for a tab that was never offline — a researcher who queued a
 * record in a courtyard, closed the laptop and opened it the next morning on office wifi got no
 * pass at all. MEASURED before the mount drain existed: an entry refused by an earlier run produced
 * ZERO replay requests across a reload. The sibling `DraftSyncBanner` had the drain and the comment
 * explaining why; this queue did not, so one of the two clients' outboxes kept the promise and the
 * other did not.
 *
 * WHAT THIS PINS, in the two directions that matter:
 *   1. an entry a previous run recorded as a skew IS re-sent by a new app run, with no click; and
 *   2. what comes back settles it — the server answers with a FIELD error, the pass re-records it
 *      without a run stamp, and the app run after that leaves it alone. Retrying for ever would be
 *      the opposite defect and just as expensive on a prepaid connection.
 *
 * NOTHING IS CREATED ON THE SERVER. The seeded body is deliberately empty, so the re-attempt is
 * answered `422 {"type":"missing","loc":["body","name"] …}` — measured against the running API —
 * which is a refusal a person settles, not a dialect mismatch.
 */

test.skip(!!CREDENTIALS_MISSING, CREDENTIALS_MISSING);

/** A refusal recorded by SOME OTHER run of the app: the state a designer reopens the laptop in. */
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
          // Empty on purpose: the re-attempt must be REFUSED so nothing is written to the
          // repository by a test, and refused for a reason that is not a dialect mismatch.
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

/** The one entry, as the outbox holds it on disk. */
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

  // A RELOAD IS A NEW APP RUN, and it is the only thing that happens: no click, no `online` event,
  // no edit. This is the morning after the courtyard.
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

  // THE OTHER DIRECTION. Once it is a refusal a person must settle, a further app run must leave it
  // alone — re-sending it every time the app is opened would be a prepaid data bill for an answer
  // that cannot change.
  replays = 0;
  await page.reload();
  await page.waitForTimeout(8_000);
  expect(replays, "a refusal only a person can clear is not re-sent by the next run").toBe(0);
});
