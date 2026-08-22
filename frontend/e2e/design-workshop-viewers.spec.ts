import { expect, test, type Locator, type Page } from "@playwright/test";

import { signIn as signInAs } from "./support/session";

/**
 * WHO MAY OPEN A DESIGN & PROTOTYPE WORKSHOP, ADMINISTERED FROM /workshop-access/manage.
 *
 * The failure being defended against: `load_workshop_or_404` refuses a design workshop for every
 * account but the one that pressed "create" (`record.createdById != user.id and not admin` → 404).
 * A workshop is run by two designers alongside a master craftsperson and a reviewing officer, so
 * the colleague is told the record does not exist, and a designer who leaves mid-season takes the
 * fortnight's fieldwork with them. `DesignWorkshopViewersPanel` is the admin's way to name the
 * other readers; these tests are what stop it regressing into something that LOOKS like it works.
 *
 * **THE THREE VIEWER ENDPOINTS ARE STUBBED, AND THE REASON HAS CHANGED — READ THIS BEFORE COPYING
 * THE PATTERN.** They used to be stubbed because they did not exist yet: `/design-workshops/eligible-
 * viewers` answered 404 "Record not found" on this dev server, because FastAPI matched it against
 * `/design-workshops/{id}`. THEY ARE LIVE NOW, and `design-workshop-viewers-search.spec.ts` exercises
 * them for real. They stay stubbed HERE for the same reason the list endpoints are, which is the only
 * reason that still holds: every assertion below is an exact count or an exact PUT body — "4 options,
 * not 5", "2 of the 4 design workshops are Design & Prototype", "Padma holds a row and is no longer
 * eligible" — and none of those can be exact against whatever the database happens to hold on the day,
 * nor can a suspended-roster viewer be conjured through the API without leaving one behind. So the
 * stub is the wire contract, spelled out in {@link VIEWERS_BY_WORKSHOP} and {@link viewerRow}, and it
 * must be kept in step with the real shape — see the `truncated` note on the route below for what
 * happened the last time the wire grew a field. Everything else — the page, the route guard, the
 * components, the keyboard — is real.
 *
 * WHAT EACH TEST IS ACTUALLY DEFENDING.
 *
 * - **Granting and revoking are one explicit Save, and the PUT carries the WHOLE set.** The
 *   endpoint replaces; a payload built from "what I just ticked" silently revokes everybody else.
 *   The test ticks one person, unticks another, and asserts the exact body — including the
 *   creator's own row, which the panel re-attaches because dropping a row the server reported is
 *   the one edit it may not make.
 * - **The type dropdown narrows the selector, and says what it left out.** A design workshop with
 *   no linked `Workshop` row carries no type at all, and there are always some — a filter that
 *   dropped them silently would show an empty selector over a full repository.
 * - **The whole control works with no mouse.** Tab reaches it — through the people search box, which
 *   is a stop on that route and not decoration — arrows move it, Space and Enter toggle, Escape
 *   dismisses and gives focus back, the focus ring is really drawn, and the live region says where the
 *   workshop now stands.
 * - **A designer typing the URL never reaches it.** Tested by NAVIGATION, not by looking for a
 *   missing link: a UI guard over an open page has shipped twice in this repository.
 *
 * **THE PEOPLE SEARCH ITSELF IS TESTED NEXT DOOR, AGAINST THE LIVE API.**
 * `design-workshop-viewers-search.spec.ts` covers the 2000-account ceiling, the sentence that admits a
 * truncated list and the search that reaches past it. It cannot be done here: this file stubs the
 * eligible list, and a stubbed list is a list somebody chose the length of — the defect only exists
 * because a real table grew past a number. The stub below therefore states `truncated: false` — the
 * whole eligible set, nothing hidden — which is the case in which the panel must say nothing at all.
 */

const PASSWORD = process.env.E2E_PASSWORD ?? "";
const ADMIN = process.env.E2E_EMAIL ?? "admin2@example.org";
const DESIGNER = process.env.E2E_DESIGNER_EMAIL ?? "designer@example.org";

test.skip(!PASSWORD, "Set E2E_PASSWORD to run the signed-in specs.");

/* ────────────────────────────────────────────────────────────────────────────
 * The fixture
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Six invented accounts, and six rather than nine on purpose: `SearchableSelect` grows a filter box
 * at eight options, so a longer fixture would silently test the searchable variant and the keyboard
 * test would be driving an `<input>` instead of the listbox trigger that owns
 * `aria-activedescendant`.
 *
 * Padma Rao is the one who matters most. She HOLDS a viewer row on Ajrakh and is NOT eligible any
 * more — a designer whose roster row was suspended. She must still appear in the picker, ticked and
 * marked, or the next Save deletes her row as a side effect of adding somebody unrelated.
 */
const PEOPLE = [
  { id: "u-creator", name: "Rukmini Devi", email: "rukmini@example.org", role: "DESIGNER" },
  { id: "u-meera", name: "Meera Sundaram", email: "meera@example.org", role: "DESIGNER" },
  { id: "u-arjun", name: "Arjun Bhatt", email: "arjun@example.org", role: "DESIGNER" },
  { id: "u-kavya", name: "Kavya Nair", email: "kavya@example.org", role: "DESIGNER" },
  { id: "u-ilyas", name: "Ilyas Khan", email: "ilyas@example.org", role: "ADMIN" },
  { id: "u-padma", name: "Padma Rao", email: "padma@example.org", role: "DESIGNER" }
];

const byId = new Map(PEOPLE.map((person) => [person.id, person]));

/** Everyone but Padma — `GET /design-workshops/eligible-viewers` excludes a suspended roster row. */
const ELIGIBLE = PEOPLE.filter((person) => person.id !== "u-padma");

/**
 * Three `Workshop` rows, because `WorkshopType` lives on the workshop a design workshop was started
 * FROM and nowhere else. Two Design & Prototype, one Other — enough for the filter to have a wrong
 * answer available to it.
 */
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

/**
 * Four design workshops: two Design & Prototype, one Other, and one linked to no workshop at all.
 *
 * The fourth is the point of the excluded-count assertion. Only the title is required to open a
 * design workshop, so a record begun in a courtyard on day one genuinely carries no link and
 * therefore no type — and there is no honest way for a type filter to place it.
 */
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

/**
 * THE WIRE CONTRACT, as `GET /design-workshops/{id}/viewers` returns it: `{"viewers":[{userId,
 * name, email, role, grantedAt}]}`. The creator's own row is present on three of them, which is
 * what makes the "the PUT must re-attach it" assertion meaningful.
 */
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

/* ────────────────────────────────────────────────────────────────────────────
 * Sign-in and stubbing
 * ──────────────────────────────────────────────────────────────────────────── */

let adminToken = "";

/**
 * Both accounts share one password, so only the address varies here. The ceremony itself — planting
 * the token instead of driving the form — lives in support/session, along with the hydration race
 * and the login throttle it exists to avoid; this spec signs in as two different people, so it paid
 * that cost twice per test.
 */
async function signIn(page: Page, email: string) {
  await signInAs(page, email, PASSWORD);
}

test.beforeAll(async ({ browser }) => {
  // Once, not per test: seven trips through the real login form took ten minutes and flaked on the
  // way. The token is replayed into localStorage for every other page.
  const context = await browser.newContext();
  const page = await context.newPage();
  await signIn(page, ADMIN);
  adminToken = (await page.evaluate(() => window.localStorage.getItem("field_repo_token"))) ?? "";
  await context.close();
  expect(adminToken, "the admin account must be able to sign in").not.toBe("");
});

/**
 * Serve the fixture, and record every PUT.
 *
 * The four regexes are deliberately disjoint rather than ordered: `/design-workshops?` (a query
 * immediately after the collection), `…/eligible-viewers`, `…/{id}/viewers`. Relying on Playwright's
 * last-registered-wins rule instead would make the file's meaning depend on the order of the calls
 * below, which is exactly the sort of thing that survives a refactor and stops matching.
 */
async function stubViewers(page: Page) {
  const put: PutBody[] = [];
  const held: Record<string, string[]> = JSON.parse(JSON.stringify(VIEWERS_BY_WORKSHOP));

  // The token AND the admin-view preference, in one init script that runs before the app boots.
  //
  // THE SECOND HALF IS NOT OPTIONAL AND IS EASY TO MISS. /workshop-access/manage is in
  // ADMIN_CHROME_ROUTES, and an ADMIN's admin-view toggle defaults to OFF (only the master admin
  // defaults on) — so a signed-in admin who has never touched the toggle is shown AppShell's
  // "hidden while admin view is off" panel instead of the console, and every locator below finds
  // nothing. The failure reads as "the panel was never built" rather than "the toggle is off",
  // which is exactly how it wasted an afternoon. Same preference the toggle itself writes; see
  // `enableAdminView` in provider-order.spec.ts, which solves this the same way after sign-in.
  await page.addInitScript((token) => {
    window.localStorage.setItem("field_repo_token", token);
    try {
      const sub = String(JSON.parse(atob(token.split(".")[1] ?? "")).sub ?? "");
      if (sub) window.localStorage.setItem(`field_repo_admin_view:${sub}`, "on");
    } catch {
      // A malformed token is the beforeAll's problem, not this line's — it asserts on it already.
    }
  }, adminToken);

  // `truncated` is part of this shape and is stated rather than left out: the endpoint always sends it,
  // and `false` is the answer this fixture means — six accounts, all of them offered, nothing hidden,
  // so the panel must render no truncation notice. The regex now tolerates the `?search=` the panel
  // sends once an admin types, and the answer deliberately ignores the term: what the search DOES is
  // asserted next door against the live API, and this file must not become a second, fake copy of it.
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
      // A REPLACE, exactly as the endpoint is specified: what arrives becomes the whole set.
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

  // The other two panels on this page are not under test; answering their reads keeps their error
  // banners off a screenshot that is supposed to be about this one.
  await page.route(/\/api\/workshops\/[^/?]+\/assignments/, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: "[]" })
  );
  await page.route(/\/api\/workshops\/access-requests/, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: "[]" })
  );

  return put;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Locators
 * ──────────────────────────────────────────────────────────────────────────── */

const viewersPanel = (page: Page) => page.locator("[data-design-workshop-viewers]");
const popover = (page: Page) => page.locator("[data-anchored-popover]");
const options = (page: Page) => popover(page).getByRole("option");

const trigger = (page: Page, name: RegExp) => viewersPanel(page).getByRole("button", { name });

/**
 * Open one picker and wait until exactly one settled panel is on screen.
 *
 * Both waits are load-bearing and are lifted from `sharing-multiselect.spec.ts`, where they were
 * measured: AnimatePresence keeps a closing panel mounted for its exit spring, so opening a second
 * picker straight after Escape briefly leaves TWO `[data-anchored-popover]` nodes in the DOM and
 * every option locator becomes ambiguous; and the entrance spring moves the panel for a few frames,
 * which Playwright reports as "element is not stable" if a click is attempted during it.
 */
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
  // The eligible list decides whether the controls render at all (a 404 hides them behind an honest
  // notice), so nothing below is meaningful until it has landed.
  await expect(viewersPanel(page).getByText(/does not offer design workshop visibility/i)).toHaveCount(0);
}

/**
 * Click one row of an open picker.
 *
 * The scroll is not superstition. `openPicker` centres the TRIGGER, and the popover is portalled
 * and anchored to it, so a list long enough to reach past the fold leaves its lower rows outside
 * the viewport — and typing in the filter box re-lays the panel out underneath, which can move a
 * row that was on screen when the locator resolved. Playwright then retries "element is outside of
 * the viewport" until it times out, which reads like a missing option rather than a scroll
 * position. Bringing the row into view first is the same thing `openPicker` already does for the
 * control it clicks.
 */
async function clickOption(page: Page, option: Locator) {
  await expect(option).toHaveCount(1);
  await option.evaluate((node) => node.scrollIntoView({ block: "center" }));
  await option.click();
}

/** Choose a design workshop through its searchable picker. */
async function chooseWorkshop(page: Page, title: string) {
  await openPicker(page, trigger(page, /^Design workshop$/));
  await popover(page).getByRole("combobox").fill(title);
  await clickOption(page, options(page).filter({ hasText: title }));
  await expect(popover(page)).toHaveCount(0);
}

/** Choose a workshop type. Three options, so there is no filter box — the rows are clicked. */
async function chooseType(page: Page, label: RegExp) {
  await openPicker(page, trigger(page, /^Workshop type$/));
  await clickOption(page, options(page).filter({ hasText: label }));
  await expect(popover(page)).toHaveCount(0);
}

/* ────────────────────────────────────────────────────────────────────────────
 * The tests
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("design workshop visibility", () => {
  test("an admin grants one designer and revokes another in one explicit save", async ({ page }) => {
    const put = await stubViewers(page);
    await openManage(page);
    await chooseWorkshop(page, "Bagru prototype fortnight");

    const panel = viewersPanel(page);

    // The creator is named OUTSIDE the picker, because this list cannot take their access away.
    const creatorBlock = panel.locator("div").filter({ hasText: /^Always has access/ }).first();
    await expect(creatorBlock).toContainText("Rukmini Devi");
    await expect(creatorBlock).toContainText(/always open it/i);

    // What is SAVED right now, before anything is touched.
    await expect(panel.getByRole("listitem").filter({ hasText: "Meera Sundaram" })).toBeVisible();

    const people = trigger(page, /^Designers who may see this workshop/);
    await openPicker(page, people);
    // Four, not five: the creator is deliberately not on offer here.
    await expect(options(page)).toHaveCount(4);
    await expect(options(page).filter({ hasText: "Rukmini Devi" })).toHaveCount(0);
    await expect(options(page).filter({ hasText: "Meera Sundaram" })).toHaveAttribute("aria-selected", "true");

    await options(page).filter({ hasText: "Arjun Bhatt" }).click();
    await options(page).filter({ hasText: "Meera Sundaram" }).click();
    await closePicker(page);

    // Nothing has been sent yet — this is the whole point of an explicit Save.
    expect(put, "toggling a name must not write to the repository").toHaveLength(0);
    await expect(panel.getByText(/Will be added on save: Arjun Bhatt/)).toBeVisible();
    await expect(panel.getByRole("listitem").filter({ hasText: "Meera Sundaram" })).toContainText(
      "will be removed on save"
    );
    await expect(panel.getByText(/^2 unsaved changes$/)).toBeVisible();

    await panel.getByRole("button", { name: /save who can see this/i }).click();

    await expect.poll(() => put.length).toBe(1);
    expect(put[0].workshopId).toBe("dw-bagru");
    // THE WHOLE SET, not the delta — and the creator's own row is carried back rather than dropped,
    // because this endpoint replaces and the server itself reported that row.
    expect([...put[0].userIds].sort()).toEqual(["u-arjun", "u-creator"]);

    // The ANSWER is adopted, so the saved list is now the repository's list and not our payload.
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
    // Five: the four eligible non-creators, plus the one who holds a row and has dropped off the
    // eligible list. If she were missing, the next Save would delete her row in silence.
    await expect(options(page)).toHaveCount(5);
    await expect(padma).toHaveAttribute("aria-selected", "true");
    await expect(padma).toContainText("no longer eligible");
    await closePicker(page);
  });

  test("the workshop type dropdown narrows the selector and says what it left out", async ({ page }) => {
    await stubViewers(page);
    await openManage(page);
    const panel = viewersPanel(page);

    // Empty means EVERYTHING, by absence — the house rule, so all four are offered.
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
    // The one with no linked workshop is EXCLUDED AND COUNTED. A filter that dropped it silently
    // would be indistinguishable from a repository that has nothing in it.
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

    // Bagru is Design & Prototype, so filtering to Other must not leave its roster on screen —
    // administering a record that is named nowhere is how the wrong workshop gets edited.
    await chooseType(page, /Other workshop/);
    await expect(panel.getByText(/^Always has access$/)).toHaveCount(0);
    await expect(panel.getByText(/Pick a design workshop to see who can open it/)).toBeVisible();
  });

  test("the whole control is operable with no mouse at all", async ({ page }) => {
    const put = await stubViewers(page);
    await openManage(page);
    const panel = viewersPanel(page);

    /*
      Playwright's `press` focuses the element first, which is precisely what tabbing to it does —
      and every hop AFTER this one is a real Tab, so the chain from the type dropdown to Save is
      exercised as a keyboard user walks it. Starting the chain by clicking would have proved
      nothing about reachability.
    */
    const typeTrigger = trigger(page, /^Workshop type$/);
    await typeTrigger.press("ArrowDown");
    await expect(typeTrigger).toHaveAttribute("aria-expanded", "true");
    // The focus ring must be DRAWN, not merely notionally present: the global rule is an `outline`
    // at `:focus-visible`, and a keyboard-driven focus is exactly when it applies.
    await expect
      .poll(async () => typeTrigger.evaluate((node) => getComputedStyle(node).outlineStyle))
      .not.toBe("none");

    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Enter");
    await expect(typeTrigger).toContainText("Design & Prototype Development Workshop");

    // Tab reaches the next control without a pointer.
    await page.keyboard.press("Tab");
    await expect
      .poll(async () => page.evaluate(() => document.activeElement?.getAttribute("aria-label")))
      .toBe("Design workshop");
    await page.keyboard.press("ArrowDown");
    // A searchable single-select hands the keyboard to its filter box; Enter takes the top match.
    await page.keyboard.type("Bagru");
    await page.keyboard.press("Enter");
    await expect(popover(page)).toHaveCount(0);
    await expect(panel.getByText(/^Always has access$/)).toBeVisible();

    /*
      TWO TABS, NOT ONE, AND THE STOP IN BETWEEN IS THE POINT.

      The people search box sits between the workshop picker and the roster. It is the server's search
      — `eligible-viewers` answers at most 2000 accounts ordered by name and that cut is reached on a
      real repository, so the picker cannot reach a late-sorting colleague on its own and the box that
      can is a stop on the keyboard route rather than a mouse-only convenience. It carries
      `role="searchbox"` and no `aria-label` (`components/SearchInput.tsx`: the placeholder is its
      accessible name), which is why this hop is asserted on the role.
    */
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
    // The listbox announces itself as multi-selectable, and the trigger says which row the keyboard
    // is on — without that a reader arrowing through has no idea what Space is about to toggle.
    await expect(popover(page).getByRole("listbox")).toHaveAttribute("aria-multiselectable", "true");
    const firstActive = await people.getAttribute("aria-activedescendant");
    expect(firstActive).toBeTruthy();

    await page.keyboard.press("ArrowDown");
    await expect.poll(async () => people.getAttribute("aria-activedescendant")).not.toBe(firstActive);

    // Space toggles, Enter toggles. Both, because both are what a reader will reach for.
    await page.keyboard.press(" ");
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Enter");
    await expect(options(page).filter({ has: page.locator("[aria-selected='true']") })).toHaveCount(0);
    await expect(popover(page).locator("[role='option'][aria-selected='true']")).toHaveCount(3);

    // Escape dismisses and hands focus back to the control that opened it.
    await page.keyboard.press("Escape");
    await expect(popover(page)).toHaveCount(0);
    await expect
      .poll(async () => page.evaluate(() => document.activeElement?.getAttribute("aria-label")))
      .toMatch(/^Designers who may see this workshop/);

    // The panel's own live region — the picker's is inside a portal that has just unmounted, so
    // without this a reader who ticks two names and closes the panel hears nothing at all.
    await expect(panel.locator("[role='status']")).toContainText(/3 designers selected, not yet saved/);

    // Save, still with no mouse.
    await page.keyboard.press("Tab");
    await expect.poll(async () => page.evaluate(() => document.activeElement?.textContent)).toMatch(/Save who can see/);
    await page.keyboard.press("Enter");
    await expect.poll(() => put.length).toBe(1);
    await expect(panel.locator("[role='status']")).toContainText(/Nothing unsaved/);
  });

  /**
   * THE GUARD, TESTED AS A NAVIGATION.
   *
   * Not "the link is hidden" — a UI guard over an open page has shipped twice in this repository —
   * and deliberately with NO stubs: the point is what the real application does to a real designer
   * who was sent the URL. `/workshop-access/manage` carries a ROUTE_REDIRECTS rule, so the honest
   * answer for somebody below admin is the half of the page that IS theirs, not a padlock.
   */
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

  /**
   * THE SECOND MOUNT, TESTED THE SAME WAY — AND IT IS THE HARDER OF THE TWO.
   *
   * The panel is now also mounted on /design-workshops, and that page has NO ROUTE_REDIRECTS rule
   * and must never grow one: designers live on it, it is the list of their own fieldwork. So unlike
   * the test above there is no redirect to lean on — the gate is a client-side hide over a page the
   * designer is entitled to, which is precisely the shape the comment above warns has shipped twice
   * here. What must hold is BOTH halves at once: the page renders in full, and the browser never
   * asks a viewer endpoint. Either alone would pass while the feature was broken — a redirect would
   * satisfy "no viewer requests" by taking the fortnight of fieldwork away with it.
   *
   * NO STUBS, deliberately, for the same reason as above: the point is what the real application
   * does to a real designer.
   */
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

    // THE PAGE IS THEIRS. A narrowing applied one predicate too widely would cost a designer the
    // page itself, which is a far worse failure than the one the gate prevents — so this is asserted
    // before anything about the panel.
    await expect(page.getByRole("heading", { name: /^design workshops$/i })).toBeVisible();
    await expect(page).toHaveURL(/\/design-workshops(\?|$)/);

    // AND THE ADMINISTRATION IS NOT. Both the section and the panel itself, because the section is
    // what a designer would see and the panel is what would issue the requests.
    await expect(page.getByRole("button", { name: /designers on a workshop/i })).toHaveCount(0);
    await expect(page.locator("[data-design-workshop-viewers]")).toHaveCount(0);
    expect(asked, "a designer's browser must never ask who can see a design workshop").toEqual([]);

    await context.close();
  });
});
