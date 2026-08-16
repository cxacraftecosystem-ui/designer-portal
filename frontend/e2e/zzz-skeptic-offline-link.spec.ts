import { expect, test, type Page } from "@playwright/test";

/**
 * INDEPENDENT verification of the "offline create drops the chosen workshop link" claim.
 *
 * Goes further than a draft read: it lets the app's OWN sync push the draft and then asks the
 * SERVER whether the created design workshop carries `workshopId`. A control run does the same
 * thing online, so a null on the server can only mean the offline path lost it.
 */

const API = process.env.E2E_API_URL ?? "http://localhost:8000";
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

async function token(page: Page): Promise<string> {
  const res = await page.request.post(`${API}/api/auth/login`, { data: WHO });
  expect(res.ok(), `login: ${await res.text()}`).toBeTruthy();
  return (await res.json()).accessToken as string;
}

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
        const rows: any[] = await new Promise((resolve, reject) => {
          const rq = db.transaction("drafts", "readonly").objectStore("drafts").getAll();
          rq.onsuccess = () => resolve(rq.result as any[]);
          rq.onerror = () => reject(rq.error);
        });
        return rows.map((r) => ({ localId: r.localId, remoteId: r.remoteId, header: r.header }));
      });
    } catch (err) {
      if (attempt >= 8) throw err;
      await page.waitForTimeout(750);
    }
  }
}

/** Open the create form and pick the first real workshop in "Start from a recorded workshop". */
async function fillForm(page: Page, title: string): Promise<string> {
  await page.goto("/design-workshops");
  await page.getByRole("button", { name: /new design workshop/i }).click();
  const trigger = page.getByRole("button", { name: "Start from a recorded workshop" });
  await expect(trigger).toBeVisible({ timeout: 30_000 });
  await trigger.click();
  // The first option is the "do not link" sentinel; take the one after it.
  const options = page.getByRole("option");
  await expect(options.nth(1)).toBeVisible({ timeout: 30_000 });
  const label = (await options.nth(1).textContent()) ?? "";
  await options.nth(1).click();
  // The searchable dropdown renders into a dialog; make sure nothing is left overlaying the form.
  for (let i = 0; i < 5; i += 1) {
    if (!(await page.locator("[data-field-dialog-overlay]").count())) break;
    await page.keyboard.press("Escape");
    await page.waitForTimeout(300);
  }
  await page.locator('input[name="title"]').fill(title);
  return label.trim();
}

test("offline create keeps the chosen workshop link", async ({ page, context }) => {
  const tok = await token(page);
  await signIn(page);

  const title = `skeptic offline link ${Date.now()}`;
  const picked = await fillForm(page, title);
  console.log("PICKED SOURCE WORKSHOP:", picked);

  const overlay = page.locator("[data-field-dialog]");
  if (await overlay.count()) console.log("DIALOG IN THE WAY:", (await overlay.first().innerText()).slice(0, 400));

  await context.setOffline(true);
  // Submit through the form itself: a stray modal overlay in this build eats the pointer, and what
  // is under test is the create handler, not the button's hit box.
  await page.evaluate(() => {
    const form = document.querySelector("form");
    (form as HTMLFormElement).requestSubmit();
  });
  // `router.push` cannot fetch the next route's payload with no connection, so the tab lands on the
  // browser's own error page — where IndexedDB is unreadable. The draft is already on disk by then.
  await page.waitForTimeout(3000);

  /* Come back and read what was written, then let the app's own sync push it. ------------ */
  await context.setOffline(false);
  await page.goto("/design-workshops");
  await page.waitForTimeout(1500);

  const offlineDrafts = (await readDrafts(page)).filter((d) => (d.header as any)?.title === title);
  expect(offlineDrafts.length, "the offline draft was written").toBe(1);
  console.log("LOCAL DRAFT HEADER:", JSON.stringify(offlineDrafts[0].header, null, 2));

  let remoteId: string | null = null;
  for (let i = 0; i < 30 && !remoteId; i += 1) {
    const rows = (await readDrafts(page)).filter((d) => d.header?.title === title);
    remoteId = (rows[0]?.remoteId as string | null) ?? null;
    if (!remoteId) {
      const sync = page.getByRole("button", { name: /sync now/i });
      if (await sync.count()) await sync.first().click().catch(() => {});
      await page.waitForTimeout(1000);
    }
  }
  console.log("REMOTE ID AFTER SYNC:", remoteId);
  expect(remoteId, "the draft synced").toBeTruthy();

  const res = await page.request.get(`${API}/api/design-workshops/${remoteId}`, {
    headers: { Authorization: `Bearer ${tok}` }
  });
  expect(res.ok(), await res.text()).toBeTruthy();
  const detail = (await res.json()) as Record<string, unknown>;
  console.log("SERVER workshopId AFTER OFFLINE CREATE:", JSON.stringify(detail.workshopId));
  console.log("SERVER clusterName:", JSON.stringify(detail.clusterName), "state:", JSON.stringify(detail.state));

  /* Control: the same form, online. ----------------------------------------------------- */
  const onlineTitle = `skeptic online link ${Date.now()}`;
  await fillForm(page, onlineTitle);
  await page.evaluate(() => {
    const form = document.querySelector("form");
    (form as HTMLFormElement).requestSubmit();
  });
  await page.waitForURL(/\/design-workshops\/(?!dwlocal-)[a-z0-9]+/, { timeout: 30_000 });
  const onlineId = page.url().split("/design-workshops/")[1].split("/")[0];
  const res2 = await page.request.get(`${API}/api/design-workshops/${onlineId}`, {
    headers: { Authorization: `Bearer ${tok}` }
  });
  const detail2 = (await res2.json()) as Record<string, unknown>;
  console.log("SERVER workshopId AFTER ONLINE CREATE:", JSON.stringify(detail2.workshopId));

  expect(detail2.workshopId, "control: online create links the workshop").toBeTruthy();
  expect(detail.workshopId, "offline create must link the workshop too").toBeTruthy();
});

/** The same loss, while ONLINE, when the create request merely 500s once. */
test("a transient 500 on create takes the same lossy fallback", async ({ page }) => {
  await signIn(page);
  await page.route("**/api/design-workshops", async (route) => {
    if (route.request().method() !== "POST") return route.fallback();
    await route.fulfill({ status: 500, contentType: "application/json", body: '{"detail":"boom"}' });
  });

  const title = `skeptic transient link ${Date.now()}`;
  console.log("PICKED SOURCE WORKSHOP:", await fillForm(page, title));
  await page.evaluate(() => {
    const form = document.querySelector("form");
    (form as HTMLFormElement).requestSubmit();
  });
  await page.waitForURL(/\/design-workshops\/dwlocal-/, { timeout: 30_000 });
  const rows = (await readDrafts(page)).filter((d) => (d.header as any)?.title === title);
  expect(rows.length, "the fallback wrote a local draft while online").toBe(1);
  console.log("TRANSIENT-FALLBACK DRAFT HEADER:", JSON.stringify(rows[0].header, null, 2));
  expect((rows[0].header as any).workshopId, "the transient fallback must keep the link").toBeTruthy();
});
