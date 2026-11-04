import { expect, test, type Page } from "@playwright/test";

const WHO = { email: process.env.E2E_EMAIL ?? "admin2@example.org", password: process.env.E2E_PASSWORD ?? "LocalDev123!" };

const HOLD_THE_ANSWER_MS = 12_000;

async function signIn(page: Page) {
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill(WHO.email);
  await page.getByPlaceholder("Enter your password").fill(WHO.password);
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
}

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

test.setTimeout(240_000);

test("the list does not adopt a workshop this device is still creating", async ({ page }) => {
  await signIn(page);

  const title = `adopt race ${Date.now()}`;

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
  await page.evaluate(() => (document.querySelector("form") as HTMLFormElement).requestSubmit());
  await page.waitForURL(/\/design-workshops\/dwlocal-/, { timeout: 60_000 });

  const before = (await readDrafts(page)).filter((row) => row.title === title);
  expect(before.length, "the fallback did not leave a local draft to race against").toBe(1);
  const mine = before[0].localId;
  expect(before[0].remoteId).toBeNull();

  await page.goBack();
  await expect(page).toHaveURL(/\/design-workshops(\?|$)/, { timeout: 30_000 });

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
  await page.waitForTimeout(1000);

  expect(createAnswered, "the create is still unanswered, so this is the real window").toBe(false);

  const during = (await readDrafts(page)).filter((row) => row.title === title);
  expect(during.length, "the list adopted the workshop this device is still creating").toBe(1);
  expect(during[0].localId, "the designer's own record was replaced by an adopted one").toBe(mine);
  expect(during[0].remoteId, "the id cannot be recorded yet — the answer is being held").toBeNull();

  let settled = during;
  for (let i = 0; i < 60 && !settled[0]?.remoteId; i += 1) {
    await page.waitForTimeout(500);
    settled = (await readDrafts(page)).filter((row) => row.title === title);
  }
  expect(settled.length, "a second local record appeared once the create was answered").toBe(1);
  expect(settled[0].localId, "the server's id landed on a new record instead of the designer's own").toBe(mine);
  expect(settled[0].remoteId, "the create was never recorded locally").toBeTruthy();

  const all = await readDrafts(page);
  expect(
    all.filter((row) => row.remoteId === settled[0].remoteId).length,
    "two local records claim one server workshop"
  ).toBe(1);

  expect(creates, "the create was sent more times than the story allows").toBe(2);
});

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

  await page.goto("/design-workshops");

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
  expect(creates, "the create was sent again instead of being resumed").toBe(2);
});
