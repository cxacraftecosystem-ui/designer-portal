import { expect, test, type Locator, type Page } from "@playwright/test";

import { createWorkshop, discard, stamp } from "./support/records";
import { API, apiToken, bearer, CREDENTIALS_MISSING } from "./support/session";

/**
 * REACHING AN ELIGIBLE COLLEAGUE WHO SORTS PAST THE PICKER'S CEILING.
 *
 * `GET /design-workshops/eligible-viewers` answers at most 2000 accounts ORDERED BY NAME. That
 * ceiling was written on the assumption of "a few dozen accounts in a real deployment" and the
 * assumption is measured false: on this database the eligible set counted 2571 while this was written
 * and was still growing — the admins, who are not roster-gated at all, plus the designers the active
 * roster admits — so the cut falls in the middle of the alphabet. It is counted again in `beforeAll`
 * rather than trusted from this comment, which is why the number here can age without lying. Every eligible account past it was ABSENT from the admin's picker, on both clients,
 * with no search box to reach it and nothing on screen saying anything had been hidden. An admin
 * looking for a colleague simply did not find them, and could not tell that from the colleague never
 * having been empanelled. **Those two states must never look identical**, which is what these tests
 * are here to keep true.
 *
 * **THESE SPECS RUN AGAINST THE REAL API AND THE REAL DATABASE, AND THAT IS THE POINT.** A stubbed
 * eligible list is a list somebody chose the length of; this defect only exists because a table grew
 * past a number, so a fixture cannot show it and did not — the backend assertion that finally caught
 * this had been green for months and only failed when the account count crossed 2000. The one thing
 * stubbed below is stubbed to reach the state the live database CANNOT be in (see the third test): a
 * complete, untruncated answer, where the contract is that the screen says nothing at all.
 *
 * WHAT EACH TEST DEFENDS.
 *
 * - **The ceiling is real in the browser, the sentence appears, and the search gets past it.** The
 *   fixture designer is asserted ABSENT from the unsearched picker first — otherwise the rest of the
 *   test proves only that a name can be typed — then found by name, granted, and the grant read back
 *   out of the API rather than off the screen that just drew it.
 * - **Typing does not fire a request per keystroke.** Eight characters, counted against the requests
 *   they produce. The server matches with an `ILIKE '%term%'` no index can answer, so an undebounced
 *   box is a full scan of the largest table in the database per letter.
 * - **The search is the SERVER'S, never a filter over the rows already in the browser.** Proved with
 *   a stub that answers the same three accounts whatever it is asked: a client filtering locally would
 *   show none of them, and would have searched only the part of the alphabet that fitted under the
 *   ceiling — the same defect wearing a search box.
 * - **Nothing is said when nothing is hidden.** `truncated: false` renders no sentence. The
 *   repository owner has asked twice this week for less text on these screens, so the notice is
 *   conditional on the flag and not a permanent disclaimer.
 * - **A cut answer with NOBODY in it is not "narrow your search".** `truncated` also covers the
 *   active-roster read being cut, which removes eligible designers from every possible search rather
 *   than lengthening the list — so the advice that fits the ceiling is advice that cannot work here,
 *   and it used to shadow the accurate sentence as well. Stubbed for the same reason as the test above
 *   it: 1523 active roster rows cannot reach a 50000-row cap.
 */

const PASSWORD = process.env.E2E_PASSWORD ?? "";
const ADMIN = process.env.E2E_EMAIL ?? "admin2@example.org";

test.skip(!!CREDENTIALS_MISSING, CREDENTIALS_MISSING);

/* ────────────────────────────────────────────────────────────────────────────
 * The fixture — built through the API, never named out of the database
 * ──────────────────────────────────────────────────────────────────────────── */

const RUN = stamp();

/**
 * A name that sorts LAST, which is the whole reason this account exists.
 *
 * Everything currently past the cut on this database sorts under S…W, so a "Zzz…" name is beyond the
 * 2000th row by a wide margin and stays there as the table grows. It is a DESIGNER with an active
 * roster row rather than an ADMIN for two reasons: a designer has to be admitted by the roster to be
 * eligible at all, so the account exercises the eligibility rule instead of side-stepping it; and an
 * ADMIN cannot be deleted by another ADMIN (`assert_can_manage_target` — peers are the master admin's
 * business), so an admin fixture would have been permanent residue on a shared database.
 */
const PERSON_NAME = `Zzz Late Sorting Designer ${RUN}`;
const PERSON_EMAIL = `dw-viewer-search-${RUN}@example.org`;
const WORKSHOP_TITLE = `Viewer search fixture ${RUN}`;
/**
 * A SECOND workshop, for the one test that counts the picker's options exactly.
 *
 * Not tidiness — the spec has already failed for want of it. The first test grants the fixture
 * designer a viewer row on `WORKSHOP_TITLE`, and a viewer who holds a row is ALWAYS offered (the PUT
 * replaces the whole set, so an option that is not drawn is a row the next Save silently deletes). So
 * the stubbed test, reading the same workshop, counted four options where its stub had supplied three
 * — a real answer to a question it had not meant to ask. One test's writes must not be another's
 * fixture, all the more so because a retry runs them in a different order.
 */
const CLEAN_WORKSHOP_TITLE = `Viewer search untouched ${RUN}`;

let adminToken = "";
let personId = "";
let rosterId = "";
let workshopId = "";
let cleanWorkshopId = "";
/** Why the live half of this file cannot run here, or "" when it can. */
let cannotRun = "";

test.beforeAll(async ({ request }) => {
  adminToken = await apiToken(request, ADMIN, PASSWORD);
  const headers = bearer(adminToken);

  // Empanelled FIRST. The eligibility rule folds the active roster into the user query's WHERE, so a
  // designer created before their roster row is simply not eligible until it exists — and a fixture
  // that raced that would fail as "the search found nobody", which is the failure this file is about.
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

  /*
    THE PRECONDITION, ASSERTED AGAINST THE LIVE ENDPOINT RATHER THAN ASSUMED.

    Two things have to be true for the browser tests below to mean anything: the answer must really be
    truncated, and the fixture must really be one of the accounts it leaves out. Both are properties of
    how big this database happens to be, so they are read off the wire here. A database small enough to
    answer completely is not broken — it is a database in which this defect does not exist — so the
    live tests SKIP with a reason rather than failing, which is the discipline the suite's README asks
    for: a red result that means "your database is small" trains people to ignore red results.
  */
  const probe = await request.get(`${API}/api/design-workshops/eligible-viewers`, { headers });
  expect(probe.ok(), `read the eligible viewers: ${probe.status()} ${await probe.text()}`).toBeTruthy();
  const answer = (await probe.json()) as { users: { id: string }[]; truncated?: boolean };
  const offered = new Set(answer.users.map((user) => user.id));
  if (!answer.truncated) {
    cannotRun = `the eligible list is not truncated on this database (${answer.users.length} accounts), so nothing is being hidden and there is nothing to reach past`;
  } else if (offered.has(personId)) {
    cannotRun = "the fixture designer fitted inside the eligible ceiling, so this run cannot show an account being hidden by it";
  }

  // And the other half of the same precondition: the search DOES reach her. If this fails the browser
  // has no chance, and the failure belongs here where it names the endpoint rather than a locator.
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
  // The user goes first and takes the viewer row with it — `DesignWorkshopViewer.user` is `Cascade`,
  // deliberately, because a viewer row is not authorship. The roster row can only be SUSPENDED (the
  // API has no hard delete, by design: the empanelment date is the answer to "when did they lose
  // access"), so that one leaves an inactive row behind and is meant to.
  if (personId) await discard(request, adminToken, `/api/users/${personId}`);
  if (rosterId) await discard(request, adminToken, `/api/designers/roster/${rosterId}`);
  if (workshopId) await discard(request, adminToken, `/api/design-workshops/${workshopId}`);
  if (cleanWorkshopId) await discard(request, adminToken, `/api/design-workshops/${cleanWorkshopId}`);
});

/* ────────────────────────────────────────────────────────────────────────────
 * Getting to the panel
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The token AND the admin-view preference, before the app boots.
 *
 * THE SECOND HALF IS NOT OPTIONAL. `/workshop-access/manage` is in ADMIN_CHROME_ROUTES and a plain
 * ADMIN's admin-view toggle defaults to OFF — only the master admin opens in it — so a signed-in admin
 * who has never touched the toggle gets AppShell's "hidden while admin view is off" panel and every
 * locator below finds nothing. The failure reads as "the panel was never built", which is how the same
 * omission wasted an afternoon on the sibling spec.
 */
async function beAnAdminOnThePage(page: Page) {
  await page.addInitScript((token) => {
    window.localStorage.setItem("field_repo_token", token);
    try {
      const sub = String(JSON.parse(atob(token.split(".")[1] ?? "")).sub ?? "");
      if (sub) window.localStorage.setItem(`field_repo_admin_view:${sub}`, "on");
    } catch {
      // A malformed token is beforeAll's problem; it asserts on the sign-in already.
    }
  }, adminToken);
}

const viewersPanel = (page: Page) => page.locator("[data-design-workshop-viewers]");
const popover = (page: Page) => page.locator("[data-anchored-popover]");
const options = (page: Page) => popover(page).getByRole("option");
const trigger = (page: Page, name: RegExp) => viewersPanel(page).getByRole("button", { name });
const searchBox = (page: Page) => viewersPanel(page).getByRole("searchbox");

/**
 * Open one picker and wait until exactly one settled panel is on screen.
 *
 * Both waits are lifted from `design-workshop-viewers.spec.ts`, where they were measured:
 * AnimatePresence keeps a closing panel mounted for its exit spring, so opening a second picker
 * straight after Escape briefly leaves TWO `[data-anchored-popover]` nodes and every option locator
 * becomes ambiguous; and the entrance spring moves the panel for a few frames, which Playwright
 * reports as "element is not stable" if a click is attempted during it.
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

/**
 * Commit the highlighted row of an open picker WITH THE KEYBOARD, and never with a click.
 *
 * THIS IS THE THIRD ATTEMPT AND THE REASON THE OTHER TWO FAILED IS WORTH THE PARAGRAPH.
 * `AnchoredPopover` portals its panel to `<body>` and positions it `fixed`. When the anchor sits low —
 * and this panel is the third on a page of three, under a queue and a roster that both render tall
 * empty states against a live database — the panel is placed with its rows past the bottom of the
 * window, and NOTHING in a test can bring them back: `scrollIntoView` scrolls the document, a fixed
 * panel does not follow, and Playwright then retries "element is outside of the viewport" for the whole
 * test timeout while reporting a locator that resolved perfectly well. `block: "nearest"` did not fix
 * it. A 1400px window did not fix it either — the page simply did not need to scroll and the panel was
 * placed off the bottom anyway.
 *
 * The keyboard has no geometry. `SearchableSelect` commits `safeHighlight` on Enter and derives that
 * index from what is RENDERED, so Enter can only ever take a row that is really on the list — which is
 * the same guarantee a click was being used for, minus the pixels. The sibling spec's keyboard test
 * documents this path ("a searchable single-select hands the keyboard to its filter box; Enter takes
 * the top match"), so it is a supported route and not a workaround. The count assertion before it is
 * what makes it precise: exactly one row matches, so the top match IS the row named.
 */
async function commitTopMatch(filter: Locator, expected: Locator) {
  await expect(expected).toHaveCount(1);
  await filter.press("Enter");
}

async function closePicker(page: Page) {
  await page.keyboard.press("Escape");
  await expect(popover(page)).toHaveCount(0);
}

/**
 * Land on the console with the fixture workshop selected, so the picker is on screen.
 *
 * THE EXPLICIT WAIT FOR THE LIST RESPONSE IS NOT DECORATION, it is a failure this spec has already
 * had: on a cold dev server under load (this machine also builds Android and runs pytest) the panel's
 * `/design-workshops` request outlived the suite's 15s expect timeout, the picker was therefore an
 * empty list, and the run reported "the fixture workshop is not among the options" — a sentence about
 * the fixture that was really about a compile. Waiting for the answer on the wire says which.
 */
async function openTheFixtureWorkshop(page: Page, title: string = WORKSHOP_TITLE) {
  await beAnAdminOnThePage(page);
  // Registered BEFORE the navigation, or the response can land first and never be seen.
  const listed = page.waitForResponse(
    (response) => /\/api\/design-workshops\?/.test(response.url()) && response.ok(),
    { timeout: 120_000 }
  );
  /*
    `domcontentloaded`, and a timeout in minutes rather than the default 30s.

    Measured on this machine, not chosen: the dev server writes and compacts its Turbopack filesystem
    cache in bursts that have been observed taking 61s and 2.4 minutes ("⚠ Slow filesystem detected",
    says its own log), and a navigation issued during one of those simply waits. `load` additionally
    waits for every subresource of a page that mounts three panels, which is more than this spec needs
    — the assertions below wait for the panel itself, which is the honest signal that the app is up.
  */
  await page.goto("/workshop-access/manage", { waitUntil: "domcontentloaded", timeout: 180_000 });
  await expect(viewersPanel(page).getByRole("heading", { name: /design workshop visibility/i })).toBeVisible({
    timeout: 60_000
  });
  // A 404 from the id-less endpoint means the deployment predates the feature and the controls are
  // hidden behind an honest notice; nothing below is meaningful until that is ruled out.
  await expect(viewersPanel(page).getByText(/does not offer design workshop visibility/i)).toHaveCount(0);
  await listed;

  /*
    WAIT FOR THE TWO PANELS ABOVE THIS ONE TO STOP GROWING, and this is not politeness either.

    /workshop-access/manage stacks three panels and this is the third. The two above it fill in from
    their own requests — the queue's rows, the roster's workshop list — and every row that arrives
    pushes this panel further down the page. `AnchoredPopover` FOLLOWS ITS ANCHOR through a
    ResizeObserver, so a picker opened before that settles is carried below the fold a second later,
    taking the row a locator had already resolved with it. That failure is indistinguishable from a
    missing option and cost this spec two full timeouts.

    The condition is THIS PANEL'S OWN POSITION HOLDING STILL, deliberately, rather than some other
    panel's loading placeholder: it is the thing that actually has to be true, it needs no knowledge of
    how the two panels above are worded, and it does not become a false wait the day one of them is
    legitimately empty.
  */
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
      // ~half a second of stillness at one frame per check: long enough that a response still in
      // flight has landed and re-laid the page out, short enough not to pad every test with a wait.
      return (store.__dwPanelStill ?? 0) >= 30;
    },
    null,
    { polling: "raf", timeout: 120_000 }
  );

  await openPicker(page, trigger(page, /^Design workshop$/));
  const filter = popover(page).getByRole("combobox");
  await filter.fill(title);
  // One run-stamped title, so the only match is the fixture and Enter cannot take anything else.
  await commitTopMatch(filter, options(page).filter({ hasText: title }));
  await expect(popover(page)).toHaveCount(0);
  // The roster only renders once the workshop's viewers have landed.
  await expect(viewersPanel(page).getByText(/^Always has access$/)).toBeVisible({ timeout: 60_000 });
}

/** Every `search` term the browser actually sent to the endpoint, in order. */
function recordSearches(page: Page): string[] {
  const sent: string[] = [];
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (!url.pathname.endsWith("/design-workshops/eligible-viewers")) return;
    sent.push(url.searchParams.get("search") ?? "");
  });
  return sent;
}

/* ────────────────────────────────────────────────────────────────────────────
 * The tests
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("finding a design workshop viewer past the picker's ceiling", () => {

  test("an eligible designer the ceiling hides is reached by the search and granted", async ({ page, request }) => {
    test.skip(!!cannotRun, cannotRun);
    // A cold first navigation of the run, three real list requests and a 2000-row answer behind it —
    // on a box that is also building Android and running pytest for six other lanes. See the goto.
    test.setTimeout(420_000);

    const sent = recordSearches(page);
    await openTheFixtureWorkshop(page);
    const panel = viewersPanel(page);

    // THE SENTENCE, and only because the answer really was cut. One line, not a paragraph about
    // pagination — and it is the difference between "keep typing" and "never empanelled".
    await expect(panel.getByText(/Too many accounts to show them all/)).toBeVisible();

    // THE CEILING, IN THE BROWSER. She is eligible — beforeAll proved the server offers her to a
    // search — and she is not on offer here. Without this assertion the rest of the test would prove
    // only that a name can be typed into a box.
    await openPicker(page, trigger(page, /^Designers who may see this workshop/));
    await expect(options(page).filter({ hasText: PERSON_NAME })).toHaveCount(0);
    await closePicker(page);

    // The search reaches her. Asserted through the picker's options rather than the network, because
    // what is being fixed is what the admin can SEE.
    await searchBox(page).fill(PERSON_NAME);
    await expect.poll(() => sent.filter((term) => term.includes(PERSON_NAME)).length).toBeGreaterThan(0);
    /*
      TICKED WITH THE KEYBOARD, for the reason spelled out on {@link commitTopMatch} — and it costs
      nothing here, because the picker's own filter box is deliberately OFF (the box above it is the
      server's search), so the trigger owns the keyboard and Space toggles the highlighted row. The
      search narrowed the answer to this one account, so the highlight IS her: asserted below by the
      row's own `aria-selected`, not inferred from the keystroke.
    */
    const people = trigger(page, /^Designers who may see this workshop/);
    await openPicker(page, people);
    const her = options(page).filter({ hasText: PERSON_NAME });
    await expect(her).toHaveCount(1);
    await page.keyboard.press(" ");
    await expect(her).toHaveAttribute("aria-selected", "true");
    await closePicker(page);

    // CLEARING THE BOX MUST NOT UN-NAME HER. The option list is a moving window over the table now, so
    // a pending grant made under one search has to survive the next one — otherwise the line below
    // reads "Will be added on save: Unknown user" and the admin cannot tell who they are about to add.
    await searchBox(page).fill("");
    await expect(panel.getByText(/Will be added on save/)).toContainText(PERSON_NAME);
    await expect(panel.getByText(/Will be added on save/)).not.toContainText("Unknown user");

    await panel.getByRole("button", { name: /save who can see this/i }).click();
    await expect(panel.getByRole("listitem").filter({ hasText: PERSON_NAME })).toBeVisible();

    // AND THE REPOSITORY REALLY HOLDS IT. Read back out of the API rather than believed off the screen
    // that just drew it: a feature in this repository was reported working while returning 500 for
    // every request, and the tests over it had no database underneath.
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

    // Typed a character at a time, at a human-ish rate. `fill` would set the whole value in one event
    // and prove nothing about a debounce.
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
    /*
      THE ONE STUBBED TEST, and stubbed to reach a state the live database cannot be in: an answer that
      is NOT truncated. It also answers the SAME three accounts to every request, whatever `search`
      says, which is what makes the second assertion a proof rather than a demonstration — if the panel
      were filtering the rows it already holds, typing a term none of them match would empty the picker.
      A client that filtered locally would have searched only the part of the alphabet that fitted under
      the ceiling: the defect this file exists for, wearing a search box.
    */
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

    // The untouched workshop: nobody holds a row on it, so the three the stub offers are the three the
    // picker must draw, and the count is a real assertion rather than an arithmetic coincidence.
    await openTheFixtureWorkshop(page, CLEAN_WORKSHOP_TITLE);
    const panel = viewersPanel(page);

    // NOTHING IS HIDDEN, SO NOTHING IS SAID. Both truncation sentences, because there are two — one
    // for a cut list and one for a cut search — and either appearing here would be a claim the stub
    // did not make.
    await expect(panel.getByText(/Too many (accounts|matches)/)).toHaveCount(0);

    const people = trigger(page, /^Designers who may see this workshop/);
    await openPicker(page, people);
    await expect(options(page)).toHaveCount(3);
    await closePicker(page);

    await searchBox(page).fill("qqq no account matches this");
    await expect.poll(() => asked).toContain("qqq no account matches this");

    // The server said three, so three are offered. A local filter would have left none.
    await openPicker(page, people);
    await expect(options(page)).toHaveCount(3);
    await closePicker(page);
    await expect(panel.getByText(/Too many (accounts|matches)/)).toHaveCount(0);
  });

  test("a cut answer with nobody in it does not tell an admin to narrow an empty list", async ({ page }) => {
    test.setTimeout(420_000);
    /*
      THE SECOND STUBBED STATE, AND THE SECOND ONE THIS DATABASE CANNOT PRODUCE: `truncated: true` over
      an EMPTY list.

      `truncated` covers two different cuts. One is the account list stopping at the 2000-row ceiling,
      which a search can get past. The other is the ACTIVE-ROSTER read stopping at its own cap — and
      those emails are folded into the user query's WHERE, so the designers past that cut are missing
      from EVERY possible search and the answer can come back truncated with nothing in it at all.

      Told to "narrow the search" over an empty picker, an admin has been given advice that cannot work,
      and the sentence that would have been accurate ("No eligible account matches that search.") was
      shadowed by it — so "hidden from you" and "nobody matched" collapsed back into one sentence, which
      is the defect this whole file exists to keep apart. Reaching the state for real needs 50000 active
      roster rows against today's 1523, so it is stubbed; the wording is the thing under test.
    */
    await page.route(/\/api\/design-workshops\/eligible-viewers(\?|$)/, (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ users: [], truncated: true })
      })
    );

    await openTheFixtureWorkshop(page, CLEAN_WORKSHOP_TITLE);
    const panel = viewersPanel(page);

    await expect(panel.getByText(/Some eligible accounts could not be listed/)).toBeVisible();
    // And NOT either of the three sentences that would be untrue here: nothing can be narrowed, no
    // amount of typing reaches the missing designers, and this is not "nobody matched your term".
    await expect(panel.getByText(/Too many (accounts|matches)/)).toHaveCount(0);
    await expect(panel.getByText(/No eligible account matches that search/)).toHaveCount(0);

    // The same fact once something IS typed. The old spelling changed its advice here — from "search a
    // name or email to reach the rest" to "narrow the search" — while the underlying answer, and the
    // reason it is incomplete, had not changed at all.
    await searchBox(page).fill("Meher");
    await expect(panel.getByText(/Some eligible accounts could not be listed/)).toBeVisible();
    await expect(panel.getByText(/Too many (accounts|matches)/)).toHaveCount(0);
  });
});
