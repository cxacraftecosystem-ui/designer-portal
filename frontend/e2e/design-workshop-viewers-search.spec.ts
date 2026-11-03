import { expect, test, type Locator, type Page } from "@playwright/test";

import { createWorkshop, discard, stamp } from "./support/records";
import { API, apiToken, bearer, CREDENTIALS_MISSING } from "./support/session";

const PASSWORD = process.env.E2E_PASSWORD ?? "";
const ADMIN = process.env.E2E_EMAIL ?? "admin2@example.org";

test.skip(!!CREDENTIALS_MISSING, CREDENTIALS_MISSING);

const RUN = stamp();

const PERSON_NAME = `Zzz Late Sorting Designer ${RUN}`;
const PERSON_EMAIL = `dw-viewer-search-${RUN}@example.org`;
const WORKSHOP_TITLE = `Viewer search fixture ${RUN}`;
const CLEAN_WORKSHOP_TITLE = `Viewer search untouched ${RUN}`;

let adminToken = "";
let personId = "";
let rosterId = "";
let workshopId = "";
let cleanWorkshopId = "";
let cannotRun = "";

test.beforeAll(async ({ request }) => {
  adminToken = await apiToken(request, ADMIN, PASSWORD);
  const headers = bearer(adminToken);

  const roster = await request.post(`${API}/api/designers/roster`, {
    headers,
    data: { email: PERSON_EMAIL, fullName: PERSON_NAME, isActive: true }
  });
  expect(roster.ok(), `empanel the fixture designer: ${roster.status()} ${await roster.text()}`).toBeTruthy();
  rosterId = (await roster.json()).id as string;

  const person = await request.post(`${API}/api/users`, {
    headers,
    data: { email: PERSON_EMAIL, name: PERSON_NAME, password: `Fixture${RUN}!aA`, role: "DESIGNER" }
  });
  expect(person.ok(), `create the fixture designer: ${person.status()} ${await person.text()}`).toBeTruthy();
  personId = (await person.json()).id as string;

  workshopId = await createWorkshop(request, adminToken, WORKSHOP_TITLE);
  cleanWorkshopId = await createWorkshop(request, adminToken, CLEAN_WORKSHOP_TITLE);

  const probe = await request.get(`${API}/api/design-workshops/eligible-viewers`, { headers });
  expect(probe.ok(), `read the eligible viewers: ${probe.status()} ${await probe.text()}`).toBeTruthy();
  const answer = (await probe.json()) as { users: { id: string }[]; truncated?: boolean };
  const offered = new Set(answer.users.map((user) => user.id));
  if (!answer.truncated) {
    cannotRun = `the eligible list is not truncated on this database (${answer.users.length} accounts), so nothing is being hidden and there is nothing to reach past`;
  } else if (offered.has(personId)) {
    cannotRun = "the fixture designer fitted inside the eligible ceiling, so this run cannot show an account being hidden by it";
  }

  const found = await request.get(
    `${API}/api/design-workshops/eligible-viewers?search=${encodeURIComponent(PERSON_NAME)}`,
    { headers }
  );
  expect(found.ok(), `search the eligible viewers: ${found.status()} ${await found.text()}`).toBeTruthy();
  const matches = (await found.json()) as { users: { id: string }[] };
  expect(
    matches.users.map((user) => user.id),
    "the server's search must reach an eligible account that sorts past its own ceiling"
  ).toContain(personId);
});

test.afterAll(async ({ request }) => {
  if (personId) await discard(request, adminToken, `/api/users/${personId}`);
  if (rosterId) await discard(request, adminToken, `/api/designers/roster/${rosterId}`);
  if (workshopId) await discard(request, adminToken, `/api/design-workshops/${workshopId}`);
  if (cleanWorkshopId) await discard(request, adminToken, `/api/design-workshops/${cleanWorkshopId}`);
});

async function beAnAdminOnThePage(page: Page) {
  await page.addInitScript((token) => {
    window.localStorage.setItem("field_repo_token", token);
    try {
      const sub = String(JSON.parse(atob(token.split(".")[1] ?? "")).sub ?? "");
      if (sub) window.localStorage.setItem(`field_repo_admin_view:${sub}`, "on");
    } catch {
    }
  }, adminToken);
}

const viewersPanel = (page: Page) => page.locator("[data-design-workshop-viewers]");
const popover = (page: Page) => page.locator("[data-anchored-popover]");
const options = (page: Page) => popover(page).getByRole("option");
const trigger = (page: Page, name: RegExp) => viewersPanel(page).getByRole("button", { name });
const searchBox = (page: Page) => viewersPanel(page).getByRole("searchbox");

async function openPicker(page: Page, control: Locator) {
  await expect(popover(page)).toHaveCount(0);
  await control.evaluate((node) => node.scrollIntoView({ block: "center" }));
  await control.click();
  await expect(popover(page)).toHaveCount(1);
  await page.waitForFunction(
    () => {
      const node = document.querySelector("[data-anchored-popover]");
      if (!node) return false;
      const { top, left, width, height } = node.getBoundingClientRect();
      const store = window as unknown as { __panelBox?: string };
      const box = [top, left, width, height].map((value) => Math.round(value * 10) / 10).join(",");
      const settled = store.__panelBox === box;
      store.__panelBox = box;
      return settled;
    },
    null,
    { polling: "raf" }
  );
}

async function commitTopMatch(filter: Locator, expected: Locator) {
  await expect(expected).toHaveCount(1);
  await filter.press("Enter");
}

async function closePicker(page: Page) {
  await page.keyboard.press("Escape");
  await expect(popover(page)).toHaveCount(0);
}

async function openTheFixtureWorkshop(page: Page, title: string = WORKSHOP_TITLE) {
  await beAnAdminOnThePage(page);
  const listed = page.waitForResponse(
    (response) => /\/api\/design-workshops\?/.test(response.url()) && response.ok(),
    { timeout: 120_000 }
  );
  await page.goto("/workshop-access/manage", { waitUntil: "domcontentloaded", timeout: 180_000 });
  await expect(viewersPanel(page).getByRole("heading", { name: /design workshop visibility/i })).toBeVisible({
    timeout: 60_000
  });
  await expect(viewersPanel(page).getByText(/isn't available right now/i)).toHaveCount(0);
  await listed;

  await page.waitForFunction(
    () => {
      const node = document.querySelector("[data-design-workshop-viewers]");
      if (!node) return false;
      const top = Math.round(node.getBoundingClientRect().top + window.scrollY);
      const store = window as unknown as { __dwPanelTop?: number; __dwPanelStill?: number };
      if (store.__dwPanelTop === top) store.__dwPanelStill = (store.__dwPanelStill ?? 0) + 1;
      else {
        store.__dwPanelTop = top;
        store.__dwPanelStill = 0;
      }
      return (store.__dwPanelStill ?? 0) >= 30;
    },
    null,
    { polling: "raf", timeout: 120_000 }
  );

  await openPicker(page, trigger(page, /^Design workshop$/));
  const filter = popover(page).getByRole("combobox");
  await filter.fill(title);
  await commitTopMatch(filter, options(page).filter({ hasText: title }));
  await expect(popover(page)).toHaveCount(0);
  await expect(viewersPanel(page).getByText(/^Always has access$/)).toBeVisible({ timeout: 60_000 });
}

function recordSearches(page: Page): string[] {
  const sent: string[] = [];
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (!url.pathname.endsWith("/design-workshops/eligible-viewers")) return;
    sent.push(url.searchParams.get("search") ?? "");
  });
  return sent;
}

test.describe("finding a design workshop viewer past the picker's ceiling", () => {

  test("an eligible designer the ceiling hides is reached by the search and granted", async ({ page, request }) => {
    test.skip(!!cannotRun, cannotRun);
    test.setTimeout(420_000);

    const sent = recordSearches(page);
    await openTheFixtureWorkshop(page);
    const panel = viewersPanel(page);

    await expect(panel.getByText(/Too many accounts to show them all/)).toBeVisible();

    await openPicker(page, trigger(page, /^Designers who may see this workshop/));
    await expect(options(page).filter({ hasText: PERSON_NAME })).toHaveCount(0);
    await closePicker(page);

    await searchBox(page).fill(PERSON_NAME);
    await expect.poll(() => sent.filter((term) => term.includes(PERSON_NAME)).length).toBeGreaterThan(0);
    const people = trigger(page, /^Designers who may see this workshop/);
    await openPicker(page, people);
    const her = options(page).filter({ hasText: PERSON_NAME });
    await expect(her).toHaveCount(1);
    await page.keyboard.press(" ");
    await expect(her).toHaveAttribute("aria-selected", "true");
    await closePicker(page);

    await searchBox(page).fill("");
    await expect(panel.getByText(/Will be added on save/)).toContainText(PERSON_NAME);
    await expect(panel.getByText(/Will be added on save/)).not.toContainText("Unknown user");

    await panel.getByRole("button", { name: /save who can see this/i }).click();
    await expect(panel.getByRole("listitem").filter({ hasText: PERSON_NAME })).toBeVisible();

    const after = await request.get(`${API}/api/design-workshops/${workshopId}/viewers`, {
      headers: bearer(adminToken)
    });
    expect(after.ok(), `read the viewers back: ${after.status()} ${await after.text()}`).toBeTruthy();
    const rows = (await after.json()) as { viewers: { userId: string }[] };
    expect(
      rows.viewers.map((row) => row.userId),
      "the grant made through the searched picker must exist in the database"
    ).toContain(personId);
  });

  test("typing eight characters does not fire eight searches", async ({ page }) => {
    test.skip(!!cannotRun, cannotRun);
    test.setTimeout(420_000);

    const sent = recordSearches(page);
    await openTheFixtureWorkshop(page);
    const before = sent.length;

    await searchBox(page).pressSequentially("Zzz Late", { delay: 40 });
    await expect.poll(() => sent.filter((term) => term === "Zzz Late").length).toBeGreaterThan(0);

    const searches = sent.length - before;
    expect(
      searches,
      "eight keystrokes must not be eight ILIKE scans of the largest table in the database"
    ).toBeLessThanOrEqual(3);
  });

  test("the search is the server's, and a complete list says nothing at all", async ({ page }) => {
    test.setTimeout(420_000);
    const asked: string[] = [];
    await page.route(/\/api\/design-workshops\/eligible-viewers(\?|$)/, (route) => {
      asked.push(new URL(route.request().url()).searchParams.get("search") ?? "");
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          users: [
            { id: "stub-a", name: "Aabha Complete", email: "aabha@example.org", role: "DESIGNER" },
            { id: "stub-b", name: "Bhavesh Complete", email: "bhavesh@example.org", role: "DESIGNER" },
            { id: "stub-c", name: "Chetna Complete", email: "chetna@example.org", role: "ADMIN" }
          ],
          truncated: false
        })
      });
    });

    await openTheFixtureWorkshop(page, CLEAN_WORKSHOP_TITLE);
    const panel = viewersPanel(page);

    await expect(panel.getByText(/Too many (accounts|matches)/)).toHaveCount(0);

    const people = trigger(page, /^Designers who may see this workshop/);
    await openPicker(page, people);
    await expect(options(page)).toHaveCount(3);
    await closePicker(page);

    await searchBox(page).fill("qqq no account matches this");
    await expect.poll(() => asked).toContain("qqq no account matches this");

    await openPicker(page, people);
    await expect(options(page)).toHaveCount(3);
    await closePicker(page);
    await expect(panel.getByText(/Too many (accounts|matches)/)).toHaveCount(0);
  });

  test("a cut answer with nobody in it does not tell an admin to narrow an empty list", async ({ page }) => {
    test.setTimeout(420_000);
    await page.route(/\/api\/design-workshops\/eligible-viewers(\?|$)/, (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ users: [], truncated: true })
      })
    );

    await openTheFixtureWorkshop(page, CLEAN_WORKSHOP_TITLE);
    const panel = viewersPanel(page);

    await expect(panel.getByText(/Some eligible accounts couldn.t be listed/)).toBeVisible();
    await expect(panel.getByText(/Too many (accounts|matches)/)).toHaveCount(0);
    await expect(panel.getByText(/No eligible account matches that search/)).toHaveCount(0);

    await searchBox(page).fill("Meher");
    await expect(panel.getByText(/Some eligible accounts couldn.t be listed/)).toBeVisible();
    await expect(panel.getByText(/Too many (accounts|matches)/)).toHaveCount(0);
  });
});
