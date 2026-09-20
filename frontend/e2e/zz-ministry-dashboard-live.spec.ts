import { expect, test, type Page } from "@playwright/test";

/**
 * DOES THE MINISTRY DASHBOARD ACTUALLY LOAD — signed in, against a real API, in a real browser.
 *
 * ── WHY THIS FILE EXISTS ───────────────────────────────────────────────────────────────────────
 *
 * On 2026-09-20 the three people registers shipped, were released, and answered **404** in
 * production. Nothing in the pipeline was red: the routes existed in the tree, every unit spec
 * passed, and the deploy that would have put them on the box had been skipped by a gate measuring
 * the wrong thing (see `docs/CI.md` and the header of `deploy-backend.yml`). The screen said
 * *"This list could not be read"* three times, which was true and was the only place it showed.
 *
 * Every other ministry spec in this directory reads SOURCE TEXT. Source text cannot answer "does
 * the screen fill", and that was the question nobody could answer without signing in. So this one
 * signs in.
 *
 * ── IT IS `zz-` PREFIXED AND IT IS NOT IN THE UNIT GATE ───────────────────────────────────────
 *
 * `npm run test:unit` runs the source-reading specs and needs no server. This needs a running API
 * and a running web app — either the local stack `.github/workflows/e2e-live.yml` stands up, or
 * LIVE PRODUCTION. It skips itself, loudly and with the reason, when the credentials are absent,
 * which is the same contract `feature-entry-points.spec.ts` uses.
 *
 * Credentials come from `.env.e2e` (gitignored; `.env.e2e.example` is the committed template):
 *
 *   set -a; . ../.env.e2e; set +a
 *   npx playwright test e2e/zz-ministry-dashboard-live.spec.ts
 *
 * ⚠ READ-ONLY BY CONSTRUCTION. Pointed at production this signs in as a real account on the live
 * estate, so every step below navigates and asserts and nothing writes. A spec here that needs to
 * write needs its own account and its own decision.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

/**
 * ⚠ THE IDENTIFIER FIELD IS NOT CALLED "EMAIL", AND THE FORM WILL NOT SUBMIT UNTIL THE TERMS BOX IS
 * TICKED. Both were read off the rendered page rather than assumed, and both are why the helper in
 * `a11y-barriers.spec.ts` — `getByPlaceholder("Enter your email")` and a bare click — cannot be
 * copied verbatim any more:
 *
 *   * the accessible name is "Email, phone or empanelment number", because an empanelled designer
 *     signs in with a number rather than an address;
 *   * "Sign In" renders `[disabled]` until the "I agree to the …" checkbox is checked, with a
 *     `status` beside it reading "Required to sign in." A click on a disabled button is a no-op
 *     that Playwright reports as a `waitForURL` timeout 60 seconds later — a failure that names the
 *     wrong thing entirely.
 *
 * The retry survives from that helper and is not superstition: on a server compiling under load the
 * button paints before React has attached its handler, so the first click submits nothing.
 */
async function signIn(page: Page) {
  await page.goto("/login");
  const identifier = page.getByRole("textbox", { name: /email, phone or empanelment/i });
  await identifier.waitFor({ state: "visible", timeout: 60_000 });
  await identifier.fill(EMAIL);
  await page.getByRole("textbox", { name: /^password$/i }).fill(PASSWORD);
  await page.getByRole("checkbox", { name: /i agree to the/i }).check();

  const submit = page.getByRole("button", { name: /^sign in$/i });
  await expect(submit, "the terms box was ticked and Sign In is still disabled").toBeEnabled({
    timeout: 10_000
  });
  await submit.click();
  try {
    await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 20_000 });
  } catch {
    await submit.click();
    await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 40_000 });
  }
}

/**
 * The three cards, by the titles `PEOPLE_KINDS` gives them in `lib/ministryDashboard.ts`.
 *
 * Written out rather than imported, deliberately: importing the registry would make this spec agree
 * with whatever the registry says, including the day somebody empties it. A literal list is the
 * second register, and that is the point of having one.
 */
const CARDS = [
  { title: "Designers", kind: "designers" },
  { title: "Assistant & Regional Directors", kind: "officers" },
  { title: "Inspectors", kind: "inspectors" }
] as const;

test.describe("the ministry dashboard's people registers", () => {
  test("all three cards open and fill, and none reports a failed read", async ({ page }) => {
    await signIn(page);
    await page.goto("/ministry-dashboard");

    // The page itself, before anything is expanded.
    await expect(page.getByRole("heading", { name: /ministry dashboard/i }).first()).toBeVisible({
      timeout: 30_000
    });

    for (const { title, kind } of CARDS) {
      /*
        ⚠ SCOPED TO BUTTONS THAT CARRY `aria-expanded`, NOT TO EVERY BUTTON NAMED "Designers".
        `getByRole("button", { name: /Designers/i }).first()` matched a control elsewhere on the page
        that has no `aria-expanded` at all, and the failure read "Designers shipped open" with
        `Received: ""` — an assertion complaining about the wrong element in the wrong words. A mega
        card's toggle is the only button on this screen that owns that attribute.
      */
      const toggle = page.locator("button[aria-expanded]").filter({ hasText: title }).first();
      await expect(toggle, `the "${title}" card is not on the page at all`).toBeVisible();

      // Shut on a first visit — the owner's ruling, asserted rather than assumed.
      await expect(toggle, `"${title}" shipped open`).toHaveAttribute("aria-expanded", "false");
      await toggle.click();
      await expect(toggle).toHaveAttribute("aria-expanded", "true");

      /*
        THE ASSERTION THAT WOULD HAVE CAUGHT THE 404. `PeoplePanel` renders exactly this sentence
        when the read fails, and it is deliberately distinguishable from an empty register — "none"
        and "not read" are different facts everywhere on this screen. A card that is merely EMPTY is
        a pass here; a card that could not read is not.
      */
      const card = page.locator("section").filter({ has: toggle });
      await expect(
        card.getByText(/could not be read/i),
        `"${title}" reported a failed read — the route is missing, refused or erroring`
      ).toHaveCount(0, { timeout: 20_000 });

      /*
        AND IT ACTUALLY RENDERED SOMETHING: a table of people, or the server's own empty state.

        ⚠ THE EMPTY STATE IS MATCHED BY ITS EXACT TITLE, not by `/^No .* in this scope$/`. That
        pattern also matches "No workshops in this scope" — which is a PROGRESS CELL inside a
        populated row, i.e. the opposite of an empty register — and the two together resolved to
        two elements and failed strict mode while the card was working perfectly. `EmptyState`
        renders `No ${kind} in this scope`, so the kind is what tells them apart.
      */
      // ⚠ WAIT FOR THE READ FIRST. `PeoplePanel` renders "Loading..." until its fetch resolves, and
      // sampling `table.count()` before that is a coin toss that reports an empty register as a
      // broken one. The card is opened, THEN the loading line is waited out, THEN what it drew is
      // asserted.
      await expect(card.getByText("Loading...", { exact: true })).toHaveCount(0, { timeout: 30_000 });

      // The empty state is `EmptyState title={`No ${kind} in this scope`}` — the KIND, not the
      // card's title. It is matched exactly so it cannot collide with a populated row's progress
      // cell, which reads "No workshops in this scope" and means the opposite.
      const table = card.getByRole("table");
      const empty = card.getByText(`No ${kind} in this scope`, { exact: true });
      const rendered = (await table.count()) > 0 ? table.first() : empty.first();
      await expect(
        rendered,
        `"${title}" opened and rendered neither a table of people nor an empty state`
      ).toBeVisible({ timeout: 20_000 });

      // The scope sentence is the server's and is printed verbatim; its absence means the payload
      // arrived without the caption this screen is required to show.
      await expect(card.getByText(/register is built FROM/i).first()).toBeVisible();

      /*
        ── THE COUNT MUST EXPLAIN ITSELF WHEN IT IS SHORTER THAN THE ROSTER ────────────────────

        This is the assertion the 2026-09-20 report produced. Production listed NINE designers
        against a designer roster of thirty-five, and the nine was arithmetically correct — 24 of
        those addresses had no account and 2 held one under another role. Every figure was right
        and the screen still could not be believed.

        So: the Designers card must either account for the whole roster, or say what it cannot
        show. `rosterRepresentationNote` is absent from the payload when nothing is missing, which
        is why this is a conditional rather than an unconditional assertion — a caveat that is
        always on screen is a caveat nobody reads, and demanding one here would force the product
        to lie on an installation where every empanelled designer has signed up.
      */
      if (kind === "designers") {
        const shortfall = card.getByText(/have not created an account yet/i);
        const otherRole = card.getByText(/under a role this register does not list/i);
        const listedLine = card.getByText(/empanelled designers are listed above/i);
        if ((await shortfall.count()) > 0 || (await otherRole.count()) > 0) {
          await expect(
            listedLine.first(),
            "the Designers card reports a shortfall without saying how many of the roster ARE listed"
          ).toBeVisible();
        }
      }
    }
  });

  test("the workshop register beneath them still loads", async ({ page }) => {
    // The people cards were added below an existing screen. A regression that broke the original
    // register while the new cards worked would look like a success from the three tests above.
    await signIn(page);
    await page.goto("/ministry-dashboard");
    await expect(page.getByRole("heading", { name: /ministry dashboard/i }).first()).toBeVisible({
      timeout: 30_000
    });
    await expect(page.getByText(/Read at /i).first()).toBeVisible({ timeout: 30_000 });
  });
});
