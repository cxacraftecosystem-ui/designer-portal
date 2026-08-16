import { expect, test, type Page } from "@playwright/test";

/**
 * ONE WORKSHOP, ONE LOCAL RECORD — even while the create's answer is still crawling back.
 *
 * ── THE DEFECT THIS PINS ─────────────────────────────────────────────────────────────────────────
 *
 * `adoptServerSummaries` caches every row the list returns so the list still draws in a courtyard
 * with no signal, and it decides a row is new to this device by asking whether any local draft
 * carries that row's id. There is exactly one case where that question has the wrong answer, and it
 * is the expensive one: a workshop THIS DEVICE is creating right now, which the server has already
 * committed and whose id the store has not written back yet.
 *
 * The list — twenty summaries, answered in a fraction of the create's work — comes back inside that
 * window, finds no local draft carrying the new id, and mints a SECOND local draft for the
 * designer's own workshop. Moments later the create's reply lands and the write-back stamps the same
 * id onto the ORIGINAL draft. Two local records now claim one server workshop, which is the fork
 * `ensureDraft`'s own docstring argues is unrecoverable short of clearing browser storage: autosaves
 * land in whichever record the URL happens to name, while `adoptServerDetail`/`adoptServerStage`
 * resolve by REMOTE id through `getAll().find()` and fold into whichever record key order reaches
 * first. The designer sees their workshop twice and watches the two copies diverge.
 *
 * `zzz-skeptic-offline-link.spec.ts` met this as an intermittent `Expected: 1, Received: 2`, because
 * whether the list or the create is answered first is a coin flip on a fast localhost.
 *
 * ── WHY THIS SPEC LOOKS THE WAY IT DOES ──────────────────────────────────────────────────────────
 *
 * A LOCAL DRAFT MADE BY A 500, NOT BY `context.setOffline`. The create form takes the identical
 * fallback for both — `isTransient` is deliberately what it tests, so that a 5xx keeps the workshop
 * on the device rather than losing the room — and the 500 path is the one that can be made to happen
 * on demand. Emulated offline could not: `setOffline` resolves before the renderer flips
 * `navigator.onLine`, so the submit sometimes went out over a connection that was about to vanish,
 * and when the request landed and its ANSWER did not, the sync created the workshop a SECOND time on
 * the server. A spec that manufactures duplicate government records while looking for one is worse
 * than no spec.
 *
 * THE HELD ANSWER IS NOT A CHEAT. Nothing about the create changes: the request reaches the server,
 * the server commits it, the server answers. Only the answer is held — which is what a field
 * connection does to it anyway — so that a race that is real and rare becomes real and certain.
 */

/*
 * THE ACCOUNT THIS FILE SIGNS IN AS CHANGED, AND THE REASON IS THE POINT.
 *
 * It used to be `designer@example.org`, and every test below opens the CREATE form. Design-workshop
 * creation is now admin-and-above: a designer may still open every workshop they have access to,
 * fill its 22 stages and produce its report, but may not bring a new one into existence — so the
 * "New design workshop" control is HIDDEN for them and these tests timed out clicking a button that
 * is correctly not there.
 *
 * The behaviour under test is unchanged and is not about permission at all: it is the offline
 * create, the local draft, and what happens when the server's answer to a create is lost. Those are
 * properties of the create path whoever performs it, so the fix is to perform it as an account that
 * may — NOT to relax the gate, and not to delete the coverage. `design-workshop-create-gate-unit`
 * is where the permission itself is asserted.
 */
const WHO = { email: process.env.E2E_EMAIL ?? "admin2@example.org", password: process.env.E2E_PASSWORD ?? "LocalDev123!" };

/**
 * Long enough for a search, its list fetch, the adopt behind it and the reads below to all happen
 * while the create is still unanswered. Generous on purpose: too short and this spec stops testing
 * the window and starts testing how fast the machine is.
 */
const HOLD_THE_ANSWER_MS = 12_000;

async function signIn(page: Page) {
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill(WHO.email);
  await page.getByPlaceholder("Enter your password").fill(WHO.password);
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
}

/**
 * Every draft on the device, read straight out of IndexedDB.
 *
 * Retried, because the page may still be mid-navigation when this is called and a document that is
 * being replaced answers `InvalidStateError` rather than "no drafts" — reporting an empty store for
 * that is how a duplicate-draft assertion passes by reading nothing at all.
 */
async function readDrafts(page: Page) {
  for (let attempt = 0; ; attempt += 1) {
    try {
      return await page.evaluate(async () => {
        const db: IDBDatabase = await new Promise((resolve, reject) => {
          const open = indexedDB.open("design-workshop-drafts");
          open.onsuccess = () => resolve(open.result);
          open.onerror = () => reject(open.error);
        });
        const rows: Array<Record<string, unknown>> = await new Promise((resolve, reject) => {
          const rq = db.transaction("drafts", "readonly").objectStore("drafts").getAll();
          rq.onsuccess = () => resolve(rq.result as Array<Record<string, unknown>>);
          rq.onerror = () => reject(rq.error);
        });
        return rows.map((r) => ({
          localId: r.localId as string,
          remoteId: (r.remoteId as string | null) ?? null,
          title: ((r.header as { title?: string } | undefined)?.title ?? "") as string
        }));
      });
    } catch (err) {
      if (attempt >= 8) throw err;
      await page.waitForTimeout(750);
    }
  }
}

// The held answer alone is twelve seconds, and the nudge that gets the sync pass moving can take
// another ten. The default budget is not enough for a spec whose whole subject is a slow create.
test.setTimeout(240_000);

test("the list does not adopt a workshop this device is still creating", async ({ page }) => {
  await signIn(page);

  // Carries `Date.now()` so the counts below can only ever see rows THIS run made. The context's
  // IndexedDB starts empty, but the first list load fills it: `adoptServerSummaries` caches every
  // row the server returns, and the server still holds the workshop every previous run of this spec
  // created. A fixed title would be counting the whole history of the suite.
  const title = `adopt race ${Date.now()}`;

  /*
    ONE HANDLER, TWO CREATES, IN ORDER.

    The FIRST create is the form's, and it is refused with a 500 so the workshop stays on this device
    as a local draft — the fallback a designer gets when the ministry's server hiccups mid-submit.
    The SECOND is the sync pass sending that draft, and it is allowed through to the server and then
    held: committed up there, unanswered down here. That is the window.
  */
  let creates = 0;
  let createCommitted = false;
  let createAnswered = false;
  await page.route("**/api/design-workshops", async (route) => {
    if (route.request().method() !== "POST") return route.fallback();
    creates += 1;
    if (creates === 1) {
      await route.fulfill({ status: 500, contentType: "application/json", body: '{"detail":"boom"}' });
      return;
    }
    const response = await route.fetch();
    createCommitted = true;
    await new Promise((resolve) => setTimeout(resolve, HOLD_THE_ANSWER_MS));
    createAnswered = true;
    await route.fulfill({ response });
  });

  await page.goto("/design-workshops");
  await page.getByRole("button", { name: /new design workshop/i }).click();
  const titleBox = page.locator('input[name="title"]');
  await expect(titleBox).toBeVisible({ timeout: 30_000 });
  await titleBox.fill(title);
  // Submitted through the form rather than the button for the reason the skeptic spec gives: what is
  // under test is the create path, not a button's hit box.
  await page.evaluate(() => (document.querySelector("form") as HTMLFormElement).requestSubmit());
  // The local id in the URL IS the proof that the fallback ran and the draft is on disk.
  await page.waitForURL(/\/design-workshops\/dwlocal-/, { timeout: 60_000 });

  const before = (await readDrafts(page)).filter((row) => row.title === title);
  expect(before.length, "the fallback did not leave a local draft to race against").toBe(1);
  const mine = before[0].localId;
  expect(before[0].remoteId).toBeNull();

  /*
    BACK TO THE LIST THROUGH HISTORY, NEVER `page.goto`, AND THIS IS THE SPEC'S SHARPEST EDGE.

    The create form reached this URL with `router.push`, so going back is a same-document traversal:
    the layout — and with it `DraftSyncBanner` and the sync pass it started — stays alive. A
    `page.goto` would tear the document down instead, and tearing it down mid-create is a SEPARATE
    and worse defect, registered as outstanding: the in-flight `POST /design-workshops` dies with
    the document, its `remoteId` write-back never happens, the Web Lock is released by the browser,
    and the next document's drain finds a draft that still says "never created" and posts it AGAIN.
    Two government records under one title, which the create route cannot catch because
    `createDesignWorkshop` carries no client key. That is not what this spec is about, and letting
    it happen here would make this spec fail for a reason it does not describe.
  */
  await page.goBack();
  await expect(page).toHaveURL(/\/design-workshops(\?|$)/, { timeout: 30_000 });

  /*
    THE WINDOW IS WAITED FOR, NOT ASSUMED — and then a list load is put INSIDE it deliberately.

    Both halves matter, and the first draft of this spec had neither. The list load the page does on
    mount and the sync pass's create start together and finish in whichever order the machine feels
    like: on this laptop the list is usually answered seconds before the create even goes out, so the
    mount's adopt runs while the workshop still does not exist on the server, sees nothing new, and a
    build with the defect wide open passes. That coin flip is why the original report of this bug was
    a flake rather than a failure, and reproducing it by waiting would inherit the same coin.

    So: wait until the create has actually committed — the handler above holds its answer from that
    moment — and only then make the list fetch again, by searching for the workshop, which is what a
    designer does when they come back into signal and want to see it.

    AND THE WAIT IS A NUDGE, NOT A VIGIL. The drain-on-mount pass fires somewhere between two seconds
    and never depending on what else the page is doing. So the pass is asked for the way the laptop
    itself asks for it — `DraftSyncBanner` drains on the window's `online` event, which is exactly
    what fires when a courtyard gets signal back — and the banner's own buttons are pressed when it
    is showing them. None of it can send a second create: `syncDesignWorkshopDrafts` hands every
    caller the pass already in flight.
  */
  for (let i = 0; i < 40 && !createCommitted; i += 1) {
    await page.evaluate(() => window.dispatchEvent(new Event("online"))).catch(() => undefined);
    if (i % 4 === 3) {
      const button = page.getByRole("button", { name: /sync now|try again/i });
      if (await button.count().catch(() => 0)) await button.first().click({ timeout: 3000 }).catch(() => undefined);
    }
    await page.waitForTimeout(500);
  }
  expect(createCommitted, "the create never reached this spec's route, so there was no window to test in").toBe(true);

  const listAnswered = page.waitForResponse(
    (response) =>
      response.request().method() === "GET" && /\/api\/design-workshops\?.*\bsearch=/.test(response.url()),
    { timeout: 60_000 }
  );
  await page.getByPlaceholder(/search by title/i).fill(title);
  await listAnswered;
  // `adoptServerSummaries` is deliberately NOT awaited by the list page — a slow IndexedDB write must
  // never hold up a render — so its write lands a tick or two after the response. This is the window
  // the defect lived in, and the assertions below are taken inside it.
  await page.waitForTimeout(1000);

  expect(createAnswered, "the create is still unanswered, so this is the real window").toBe(false);

  const during = (await readDrafts(page)).filter((row) => row.title === title);
  expect(during.length, "the list adopted the workshop this device is still creating").toBe(1);
  expect(during[0].localId, "the designer's own record was replaced by an adopted one").toBe(mine);
  expect(during[0].remoteId, "the id cannot be recorded yet — the answer is being held").toBeNull();

  /* Let the answer through, and check the write-back landed on the designer's own record. ----- */
  let settled = during;
  for (let i = 0; i < 60 && !settled[0]?.remoteId; i += 1) {
    await page.waitForTimeout(500);
    settled = (await readDrafts(page)).filter((row) => row.title === title);
  }
  expect(settled.length, "a second local record appeared once the create was answered").toBe(1);
  expect(settled[0].localId, "the server's id landed on a new record instead of the designer's own").toBe(mine);
  expect(settled[0].remoteId, "the create was never recorded locally").toBeTruthy();

  // The fork this spec exists to prevent, stated on its own so that a regression which keeps the
  // count at one but writes the id twice cannot slip past: one workshop, one local record.
  const all = await readDrafts(page);
  expect(
    all.filter((row) => row.remoteId === settled[0].remoteId).length,
    "two local records claim one server workshop"
  ).toBe(1);

  // Exactly two creates left this browser: the one the server refused and the one it kept. A third
  // would mean the workshop had been filed twice in the ministry's index.
  expect(creates, "the create was sent more times than the story allows").toBe(2);
});

/**
 * THE OTHER HALF, AND THE MORE EXPENSIVE ONE: a create whose ANSWER IS NEVER SEEN AT ALL.
 *
 * The create is a check-then-act across a network round trip — `remoteId === null` is read, the POST
 * is awaited, the id is written back — and the document can die anywhere in that middle. It does not
 * take a crash: it takes a tap. The offline create leaves the designer on `/design-workshops/dwlocal-…`,
 * signal comes back, `DraftSyncBanner` drains and the create goes out, and the designer taps through
 * to the list. A full document teardown kills the in-flight `fetch`, so the write-back never runs,
 * AND it releases the Web Lock the pass was holding, so the next document's drain is granted the
 * lease at once, finds a draft that still says "never created", and sends it again.
 *
 * `createDesignWorkshop` carries no client key and the create route de-duplicates nothing, so the
 * ministry ends up holding two `DesignWorkshop` rows under one title — measured at 3.8 seconds apart
 * from a single submit while this was being diagnosed. One of them is then orphaned: no local draft
 * points at it, so the list adopts it as a SECOND local draft, which is the "two drafts where one is
 * expected" that `zzz-skeptic-offline-link.spec.ts` kept intermittently catching. The duplicate LOCAL
 * row is the visible half; the duplicate GOVERNMENT RECORD underneath it is the half that matters.
 *
 * This spec kills the document at exactly that point, on purpose, and then asks the server how many
 * workshops it has. The answer must be one.
 */
test("a create whose answer is lost is resumed, not sent a second time", async ({ page }) => {
  await signIn(page);
  const title = `adopt resume ${Date.now()}`;

  let creates = 0;
  let createCommitted = false;
  await page.route("**/api/design-workshops", async (route) => {
    if (route.request().method() !== "POST") return route.fallback();
    creates += 1;
    if (creates === 1) {
      await route.fulfill({ status: 500, contentType: "application/json", body: '{"detail":"boom"}' });
      return;
    }
    // The server commits it. The answer is then held long past the teardown below, which is what
    // makes the loss total: this browser never learns the id.
    await route.fetch();
    createCommitted = true;
    await new Promise((resolve) => setTimeout(resolve, 120_000));
    await route.fulfill({ status: 599, body: "" }).catch(() => undefined);
  });

  await page.goto("/design-workshops");
  await page.getByRole("button", { name: /new design workshop/i }).click();
  const titleBox = page.locator('input[name="title"]');
  await expect(titleBox).toBeVisible({ timeout: 30_000 });
  await titleBox.fill(title);
  await page.evaluate(() => (document.querySelector("form") as HTMLFormElement).requestSubmit());
  await page.waitForURL(/\/design-workshops\/dwlocal-/, { timeout: 60_000 });

  for (let i = 0; i < 40 && !createCommitted; i += 1) {
    await page.evaluate(() => window.dispatchEvent(new Event("online"))).catch(() => undefined);
    if (i % 4 === 3) {
      const button = page.getByRole("button", { name: /sync now|try again/i });
      if (await button.count().catch(() => 0)) await button.first().click({ timeout: 3000 }).catch(() => undefined);
    }
    await page.waitForTimeout(500);
  }
  expect(createCommitted, "the create never reached this spec's route, so nothing was interrupted").toBe(true);

  // THE TAP. A hard navigation, which is what `page.goto` is and what a browser does when a link
  // leaves the app: the document goes, the in-flight create goes with it, and the lock is released.
  await page.goto("/design-workshops");

  // Now let the app do whatever it is going to do about a draft whose create is unaccounted for.
  let settled = (await readDrafts(page)).filter((row) => row.title === title);
  for (let i = 0; i < 60 && !settled[0]?.remoteId; i += 1) {
    await page.evaluate(() => window.dispatchEvent(new Event("online"))).catch(() => undefined);
    if (i % 4 === 3) {
      const button = page.getByRole("button", { name: /sync now|try again/i });
      if (await button.count().catch(() => 0)) await button.first().click({ timeout: 3000 }).catch(() => undefined);
    }
    await page.waitForTimeout(500);
    settled = (await readDrafts(page)).filter((row) => row.title === title);
  }

  expect(settled.length, "the lost answer left the designer with two local records").toBe(1);
  expect(settled[0].remoteId, "the workshop the server already holds was never adopted back").toBeTruthy();

  /* And the only assertion that can speak for the ministry's index. ------------------------- */
  const login = await page.request.post(`${process.env.E2E_API_URL ?? "http://localhost:8000"}/api/auth/login`, { data: WHO });
  const token = (await login.json()).accessToken as string;
  const found = await page.request.get(
    `${process.env.E2E_API_URL ?? "http://localhost:8000"}/api/design-workshops?pageSize=100&search=${encodeURIComponent(title)}`,
    { headers: { Authorization: `Bearer ${token}` } }
  );
  const items = ((await found.json()).items ?? []) as Array<{ id: string; title: string }>;
  const mine = items.filter((row) => row.title === title);
  expect(mine.length, "the server holds more than one workshop for one submit").toBe(1);
  expect(mine[0].id, "the draft adopted a different workshop than the one that was created").toBe(settled[0].remoteId);
  // Two POSTs: the one the server refused and the one it kept. A third is the duplicate itself.
  expect(creates, "the create was sent again instead of being resumed").toBe(2);
});
