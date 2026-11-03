import { expect, test, type Locator, type Page } from "@playwright/test";

import { signIn as signInAs, sessionTokenOf } from "./support/session";

const PASSWORD = process.env.E2E_PASSWORD ?? "";
const ADMIN = process.env.E2E_EMAIL ?? "admin2@example.org";
const DESIGNER = process.env.E2E_DESIGNER_EMAIL ?? "designer@example.org";

test.skip(!PASSWORD, "Set E2E_PASSWORD to run the signed-in specs.");

const PEOPLE = [
  { id: "u-creator", name: "Rukmini Devi", email: "rukmini@example.org", role: "DESIGNER" },
  { id: "u-meera", name: "Meera Sundaram", email: "meera@example.org", role: "DESIGNER" },
  { id: "u-arjun", name: "Arjun Bhatt", email: "arjun@example.org", role: "DESIGNER" },
  { id: "u-kavya", name: "Kavya Nair", email: "kavya@example.org", role: "DESIGNER" },
  { id: "u-ilyas", name: "Ilyas Khan", email: "ilyas@example.org", role: "ADMIN" },
  { id: "u-padma", name: "Padma Rao", email: "padma@example.org", role: "DESIGNER" }
];

const byId = new Map(PEOPLE.map((person) => [person.id, person]));

const ELIGIBLE = PEOPLE.filter((person) => person.id !== "u-padma");

const WORKSHOPS = [
  { id: "w-dp-1", title: "Bagru cluster visit", workshopType: "DESIGN_PROTOTYPE" },
  { id: "w-dp-2", title: "Ajrakhpur cluster visit", workshopType: "DESIGN_PROTOTYPE" },
  { id: "w-other-1", title: "Kota doria survey", workshopType: "OTHER" }
].map((workshop) => ({
  ...workshop,
  date: "2026-03-02T00:00:00Z",
  place: "Rajasthan",
  status: "APPROVED",
  createdAt: "2026-03-02T00:00:00Z"
}));

const DESIGN_WORKSHOPS = [
  { id: "dw-bagru", title: "Bagru prototype fortnight", workshopId: "w-dp-1", createdById: "u-creator" },
  { id: "dw-ajrakh", title: "Ajrakh prototype fortnight", workshopId: "w-dp-2", createdById: "u-creator" },
  { id: "dw-kota", title: "Kota documentation follow-up", workshopId: "w-other-1", createdById: "u-creator" },
  { id: "dw-loose", title: "Unlinked courtyard draft", workshopId: null, createdById: "u-kavya" }
].map((row) => ({
  ...row,
  templateId: "DCH_STANDARD",
  status: "IN_PROGRESS",
  workshopCode: null,
  scheme: null,
  craftName: null,
  clusterName: null,
  state: null,
  district: null,
  venue: null,
  startDate: "2026-03-02",
  endDate: null,
  designerName: null,
  implementingAgency: null,
  sponsor: null,
  notes: null,
  createdAt: "2026-03-02T00:00:00Z",
  updatedAt: "2026-03-02T00:00:00Z",
  deletedAt: null
}));

function viewerRow(id: string, grantedAt = "2026-03-04T09:30:00Z") {
  const person = byId.get(id)!;
  return { userId: person.id, name: person.name, email: person.email, role: person.role, grantedAt };
}

const VIEWERS_BY_WORKSHOP: Record<string, string[]> = {
  "dw-bagru": ["u-creator", "u-meera"],
  "dw-ajrakh": ["u-creator", "u-padma"],
  "dw-kota": ["u-creator"],
  "dw-loose": []
};

type PutBody = { workshopId: string; userIds: string[] };

let adminToken = "";

async function signIn(page: Page, email: string) {
  await signInAs(page, email, PASSWORD);
}

test.beforeAll(async ({ browser }) => {
  const context = await browser.newContext();
  const page = await context.newPage();
  await signIn(page, ADMIN);
  adminToken = await sessionTokenOf(page);
  await context.close();
  expect(adminToken, "the admin account must be able to sign in").not.toBe("");
});

async function stubViewers(page: Page) {
  const put: PutBody[] = [];
  const held: Record<string, string[]> = JSON.parse(JSON.stringify(VIEWERS_BY_WORKSHOP));

  await page.addInitScript((token) => {
    window.localStorage.setItem("field_repo_token", token);
    try {
      const sub = String(JSON.parse(atob(token.split(".")[1] ?? "")).sub ?? "");
      if (sub) window.localStorage.setItem(`field_repo_admin_view:${sub}`, "on");
    } catch {
    }
  }, adminToken);

  await page.route(/\/api\/design-workshops\/eligible-viewers(\?|$)/, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ users: ELIGIBLE, truncated: false })
    })
  );

  await page.route(/\/api\/design-workshops\/[^/?]+\/viewers$/, async (route) => {
    const request = route.request();
    const id = new URL(request.url()).pathname.split("/").at(-2)!;
    if (request.method() === "PUT") {
      const body = request.postDataJSON() as { userIds: string[] };
      put.push({ workshopId: id, userIds: body.userIds });
      held[id] = [...body.userIds];
    }
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ viewers: (held[id] ?? []).map((userId) => viewerRow(userId)) })
    });
  });

  await page.route(/\/api\/design-workshops\?/, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        items: DESIGN_WORKSHOPS,
        total: DESIGN_WORKSHOPS.length,
        page: 1,
        pageSize: 100,
        pages: 1
      })
    })
  );

  await page.route(/\/api\/workshops\?/, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ items: WORKSHOPS, total: WORKSHOPS.length, page: 1, pageSize: 200, pages: 1 })
    })
  );

  await page.route(/\/api\/workshops\/[^/?]+\/assignments/, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: "[]" })
  );
  await page.route(/\/api\/workshops\/access-requests/, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: "[]" })
  );

  return put;
}

const viewersPanel = (page: Page) => page.locator("[data-design-workshop-viewers]");
const popover = (page: Page) => page.locator("[data-anchored-popover]");
const options = (page: Page) => popover(page).getByRole("option");

const trigger = (page: Page, name: RegExp) => viewersPanel(page).getByRole("button", { name });

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

async function closePicker(page: Page) {
  await page.keyboard.press("Escape");
  await expect(popover(page)).toHaveCount(0);
}

async function openManage(page: Page) {
  await page.goto("/workshop-access/manage");
  await expect(viewersPanel(page).getByRole("heading", { name: /design workshop visibility/i })).toBeVisible();
  await expect(viewersPanel(page).getByText(/isn't available right now/i)).toHaveCount(0);
}

async function clickOption(page: Page, option: Locator) {
  await expect(option).toHaveCount(1);
  await option.evaluate((node) => node.scrollIntoView({ block: "center" }));
  await option.click();
}

async function chooseWorkshop(page: Page, title: string) {
  await openPicker(page, trigger(page, /^Design workshop$/));
  await popover(page).getByRole("combobox").fill(title);
  await clickOption(page, options(page).filter({ hasText: title }));
  await expect(popover(page)).toHaveCount(0);
}

async function chooseType(page: Page, label: RegExp) {
  await openPicker(page, trigger(page, /^Workshop type$/));
  await clickOption(page, options(page).filter({ hasText: label }));
  await expect(popover(page)).toHaveCount(0);
}

test.describe("design workshop visibility", () => {
  test("an admin grants one designer and revokes another in one explicit save", async ({ page }) => {
    const put = await stubViewers(page);
    await openManage(page);
    await chooseWorkshop(page, "Bagru prototype fortnight");

    const panel = viewersPanel(page);

    const creatorBlock = panel.locator("div").filter({ hasText: /^Always has access/ }).first();
    await expect(creatorBlock).toContainText("Rukmini Devi");
    await expect(creatorBlock).toContainText(/always open it/i);

    await expect(panel.getByRole("listitem").filter({ hasText: "Meera Sundaram" })).toBeVisible();

    const people = trigger(page, /^Designers who may see this workshop/);
    await openPicker(page, people);
    await expect(options(page)).toHaveCount(4);
    await expect(options(page).filter({ hasText: "Rukmini Devi" })).toHaveCount(0);
    await expect(options(page).filter({ hasText: "Meera Sundaram" })).toHaveAttribute("aria-selected", "true");

    await options(page).filter({ hasText: "Arjun Bhatt" }).click();
    await options(page).filter({ hasText: "Meera Sundaram" }).click();
    await closePicker(page);

    expect(put, "toggling a name must not write to the repository").toHaveLength(0);
    await expect(panel.getByText(/Will be added on save: Arjun Bhatt/)).toBeVisible();
    await expect(panel.getByRole("listitem").filter({ hasText: "Meera Sundaram" })).toContainText(
      "will be removed on save"
    );
    await expect(panel.getByText(/^2 unsaved changes$/)).toBeVisible();

    await panel.getByRole("button", { name: /save who can see this/i }).click();

    await expect.poll(() => put.length).toBe(1);
    expect(put[0].workshopId).toBe("dw-bagru");
    expect([...put[0].userIds].sort()).toEqual(["u-arjun", "u-creator"]);

    await expect(panel.getByRole("listitem").filter({ hasText: "Arjun Bhatt" })).toBeVisible();
    await expect(panel.getByRole("listitem").filter({ hasText: "Meera Sundaram" })).toHaveCount(0);
    await expect(panel.getByRole("button", { name: /save who can see this/i })).toBeDisabled();
  });

  test("a viewer who is no longer eligible is still offered, ticked and marked", async ({ page }) => {
    await stubViewers(page);
    await openManage(page);
    await chooseWorkshop(page, "Ajrakh prototype fortnight");

    await openPicker(page, trigger(page, /^Designers who may see this workshop/));
    const padma = options(page).filter({ hasText: "Padma Rao" });
    await expect(options(page)).toHaveCount(5);
    await expect(padma).toHaveAttribute("aria-selected", "true");
    await expect(padma).toContainText("no longer eligible");
    await closePicker(page);
  });

  test("the workshop type dropdown narrows the selector and says what it left out", async ({ page }) => {
    await stubViewers(page);
    await openManage(page);
    const panel = viewersPanel(page);

    await openPicker(page, trigger(page, /^Design workshop$/));
    await expect(options(page)).toHaveCount(4);
    await closePicker(page);
    await expect(panel.getByText(/not linked to a workshop record/)).toHaveCount(0);

    await chooseType(page, /Design & Prototype Development Workshop/);
    await openPicker(page, trigger(page, /^Design workshop$/));
    await expect(options(page)).toHaveCount(2);
    await expect(options(page).filter({ hasText: "Bagru prototype fortnight" })).toHaveCount(1);
    await expect(options(page).filter({ hasText: "Ajrakh prototype fortnight" })).toHaveCount(1);
    await closePicker(page);
    await expect(panel.getByText(/1 design workshop is not linked to a workshop record/)).toBeVisible();

    await chooseType(page, /Other workshop/);
    await openPicker(page, trigger(page, /^Design workshop$/));
    await expect(options(page)).toHaveCount(1);
    await expect(options(page).filter({ hasText: "Kota documentation follow-up" })).toHaveCount(1);
    await closePicker(page);

    await chooseType(page, /Any workshop type/);
    await expect(panel.getByText(/not linked to a workshop record/)).toHaveCount(0);
  });

  test("a workshop the filter no longer offers stops being administered", async ({ page }) => {
    await stubViewers(page);
    await openManage(page);
    const panel = viewersPanel(page);

    await chooseWorkshop(page, "Bagru prototype fortnight");
    await expect(panel.getByText(/^Always has access$/)).toBeVisible();

    await chooseType(page, /Other workshop/);
    await expect(panel.getByText(/^Always has access$/)).toHaveCount(0);
    await expect(panel.getByText(/Pick a design workshop to see who can open it/)).toBeVisible();
  });

  test("the whole control is operable with no mouse at all", async ({ page }) => {
    const put = await stubViewers(page);
    await openManage(page);
    const panel = viewersPanel(page);

    const typeTrigger = trigger(page, /^Workshop type$/);
    await typeTrigger.press("ArrowDown");
    await expect(typeTrigger).toHaveAttribute("aria-expanded", "true");
    await expect
      .poll(async () => typeTrigger.evaluate((node) => getComputedStyle(node).outlineStyle))
      .not.toBe("none");

    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Enter");
    await expect(typeTrigger).toContainText("Design & Prototype Development Workshop");

    await page.keyboard.press("Tab");
    await expect
      .poll(async () => page.evaluate(() => document.activeElement?.getAttribute("aria-label")))
      .toBe("Design workshop");
    await page.keyboard.press("ArrowDown");
    await page.keyboard.type("Bagru");
    await page.keyboard.press("Enter");
    await expect(popover(page)).toHaveCount(0);
    await expect(panel.getByText(/^Always has access$/)).toBeVisible();

    await page.keyboard.press("Tab");
    await expect
      .poll(async () => page.evaluate(() => document.activeElement?.getAttribute("role")))
      .toBe("searchbox");

    await page.keyboard.press("Tab");
    await expect
      .poll(async () => page.evaluate(() => document.activeElement?.getAttribute("aria-label")))
      .toMatch(/^Designers who may see this workshop/);

    const people = trigger(page, /^Designers who may see this workshop/);
    await page.keyboard.press("ArrowDown");
    await expect(people).toHaveAttribute("aria-expanded", "true");
    await expect(popover(page).getByRole("listbox")).toHaveAttribute("aria-multiselectable", "true");
    const firstActive = await people.getAttribute("aria-activedescendant");
    expect(firstActive).toBeTruthy();

    await page.keyboard.press("ArrowDown");
    await expect.poll(async () => people.getAttribute("aria-activedescendant")).not.toBe(firstActive);

    await page.keyboard.press(" ");
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Enter");
    await expect(options(page).filter({ has: page.locator("[aria-selected='true']") })).toHaveCount(0);
    await expect(popover(page).locator("[role='option'][aria-selected='true']")).toHaveCount(3);

    await page.keyboard.press("Escape");
    await expect(popover(page)).toHaveCount(0);
    await expect
      .poll(async () => page.evaluate(() => document.activeElement?.getAttribute("aria-label")))
      .toMatch(/^Designers who may see this workshop/);

    await expect(panel.locator("[role='status']")).toContainText(/3 designers selected, not yet saved/);

    await page.keyboard.press("Tab");
    await expect.poll(async () => page.evaluate(() => document.activeElement?.textContent)).toMatch(/Save who can see/);
    await page.keyboard.press("Enter");
    await expect.poll(() => put.length).toBe(1);
    await expect(panel.locator("[role='status']")).toContainText(/Nothing unsaved/);
  });

  test("a designer typing the URL is sent away and never asks for a viewer list", async ({ browser }) => {
    const context = await browser.newContext();
    const page = await context.newPage();
    const asked: string[] = [];
    page.on("request", (request) => {
      const path = new URL(request.url()).pathname;
      if (/\/design-workshops\/(eligible-viewers|[^/]+\/viewers)$/.test(path)) asked.push(path);
    });

    await signIn(page, DESIGNER);
    await page.goto("/workshop-access/manage");

    await page.waitForURL(/\/workshop-access\/request/, { timeout: 30_000 });
    await expect(page.locator("[data-design-workshop-viewers]")).toHaveCount(0);
    await expect(page.getByRole("heading", { name: /design workshop visibility/i })).toHaveCount(0);
    expect(asked, "a non-admin's browser must never even ask who can see a design workshop").toEqual([]);

    await context.close();
  });

  test("a designer keeps the whole workshop list and never asks for a viewer list on it", async ({ browser }) => {
    const context = await browser.newContext();
    const page = await context.newPage();
    const asked: string[] = [];
    page.on("request", (request) => {
      const path = new URL(request.url()).pathname;
      if (/\/design-workshops\/(eligible-viewers|[^/]+\/viewers)$/.test(path)) asked.push(path);
    });

    await signIn(page, DESIGNER);
    await page.goto("/design-workshops");

    await expect(page.getByRole("heading", { name: /^design workshops$/i })).toBeVisible();
    await expect(page).toHaveURL(/\/design-workshops(\?|$)/);

    await expect(page.getByRole("button", { name: /designers on a workshop/i })).toHaveCount(0);
    await expect(page.locator("[data-design-workshop-viewers]")).toHaveCount(0);
    expect(asked, "a designer's browser must never ask who can see a design workshop").toEqual([]);

    await context.close();
  });
});
