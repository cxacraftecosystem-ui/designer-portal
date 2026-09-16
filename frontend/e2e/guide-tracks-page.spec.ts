import { expect, test, type Page } from "@playwright/test";

import { DESIGNER_TRACK, DIRECTORATE_TRACK, INSPECTOR_TRACK } from "@/components/guide/tracks";
import { discard, stamp } from "./support/records";
import { API, apiToken, bearer, CREDENTIALS_MISSING, EMAIL, PASSWORD, signInWithToken } from "./support/session";

/**
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * `/guide` IN A REAL BROWSER — the half of this feature no module test can reach.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * ── WHY THIS FILE EXISTS, WHICH IS A DEFECT AND NOT A PREFERENCE ───────────────────────────────────
 *
 * `e2e/guide-tracks-unit.spec.ts` is a pure-module spec: it imports `tracks.ts`, walks the decks and
 * reads two components as TEXT. There is no `page.goto` in it. So when `/guide` shipped a defect that
 * left the previous deck's purple hero band in the DOM on every switch — three decks visited, three
 * stacked bands, each keeping a live "Start at step 1" button in the tab order — the whole suite
 * stayed green, and would have stayed green through its return. The bug was two siblings of one
 * fragment carrying `key={track.id}`: React's sibling reconciliation is a Map keyed by key, the later
 * child evicted the earlier, and the evicted fiber was never in the deletion set. It is not a
 * development-only warning; `mapRemainingChildren` is byte-identical in the production build.
 *
 * A test that renders nothing cannot notice a node that was never removed. THIS FILE RENDERS THE PAGE.
 *
 * ── THE THREE THINGS IT HOLDS ──────────────────────────────────────────────────────────────────────
 *
 *  1. EXACTLY ONE HERO BAND, after switching to every deck and back to one already read. Counted, not
 *     sampled: the defect's signature is N bands for N visits, so a `toBeVisible()` on the current
 *     headline would have passed throughout. The count is `.surface-dark`, which is the class the
 *     band is (`globals.css` — `@apply rounded-lg bg-purple-950 text-white`) and is used by no other
 *     component in `frontend/`, so the selector cannot drift onto something else and stay green.
 *  2. A NON-ADMIN IS SHOWN ONE DECK AND NO SWITCHER (owner ruling, 2026-09-16). Asserted as an
 *     absence of the other two decks' prose as well as an absence of the control, because "the
 *     buttons are gone" and "the other decks are not on this page" are different claims and only the
 *     second is the ruling.
 *  3. AN ANCHOR INTO A DECK THE READER MAY NOT SEE IS SILENTLY IGNORED (OQ-5, arm b). Role wins, no
 *     note, no redirect — so what is asserted is that the page is byte-for-byte the page they would
 *     have got with no hash at all, including the URL still carrying the hash that did nothing.
 *
 * ── WHY IT IS NOT A `-unit` SPEC, AND WHAT THAT COSTS ──────────────────────────────────────────────
 *
 * `npm run test:unit` selects `*-unit.spec.ts` — the service-free gate that runs with no stack and no
 * credentials. Everything below needs a signed-in browser against a real API, so it is deliberately
 * outside that name and runs with the full suite (`npx playwright test guide-`). The cost is that CI's
 * unit gate does not carry it; the alternative — stubbing the account — would test a fixture's idea of
 * a role rather than the product's, and this whole file exists because a test that did not render the
 * thing reported green over a shipped bug.
 *
 * ── THE FIXTURE, AND WHY A SECOND ACCOUNT IS UNAVOIDABLE ───────────────────────────────────────────
 *
 * The suite's account (`E2E_EMAIL`, documented as an ADMIN) is the only account that still HAS a
 * switcher, so it is the only one that can exercise claim 1 — and by the same ruling it is the one
 * account that can never demonstrate claim 2. A DESIGNER is created through the API for that half and
 * deleted afterwards. Its `mustChangePassword` is cleared first, through the route the product itself
 * uses: `POST /api/users` sets that flag on every account an admin types a password for, and `AppShell`
 * returns the first-password gate INSTEAD of the page for such an account — so without this the spec
 * would assert about a lock screen and report that the walkthrough is scoped.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 */

test.skip(!!CREDENTIALS_MISSING, CREDENTIALS_MISSING);

const RUN = stamp();
const DESIGNER_EMAIL = `guide-deck-scope-${RUN}@example.org`;
const DESIGNER_NAME = `Guide Deck Scope ${RUN}`;
/** Typed by the "admin", then replaced by the account itself — exactly the product's own flow. */
const TEMP_PASSWORD = `Fixture${RUN}!aA`;
const OWN_PASSWORD = `Chosen${RUN}!aA`;

let adminToken = "";
let designerToken = "";
let designerId = "";
/** Why this run cannot ask the question, or "" when it can. */
let cannotRun = "";

/** The hero band, by the one class that is it. */
const HERO = ".surface-dark";
/** The switcher panel, by the heading it labels itself with. */
const SWITCHER = "section[aria-labelledby='guide-track-switch-heading']";

test.beforeAll(async ({ request }) => {
  adminToken = await apiToken(request, EMAIL, PASSWORD);
  const headers = bearer(adminToken);

  /*
    THE PRECONDITION, READ OFF THE WIRE RATHER THAN TAKEN FROM THE README. Every assertion in the
    first half is about a control only an ADMIN or a MASTER ADMIN is now shown; run as any other tier
    the switcher is legitimately absent and "exactly one hero band after clicking three buttons"
    becomes a test that clicks nothing and passes. A run that cannot ask the question must SAY SO
    rather than report green — e2e/README.md's discipline, and the reason this is a skip with a
    sentence rather than an assertion.
  */
  const me = await request.get(`${API}/api/me`, { headers });
  expect(me.ok(), `read the suite account: ${me.status()} ${await me.text()}`).toBeTruthy();
  const role = ((await me.json()) as { role?: string }).role ?? "";
  if (role !== "ADMIN" && role !== "MASTER_ADMIN") {
    cannotRun =
      `E2E_EMAIL signs in as ${role || "an account with no role"}; since 2026-09-16 only an ADMIN or a ` +
      "MASTER_ADMIN is shown the deck switcher, so this run cannot exercise it. See e2e/README.md — " +
      "admin2@example.org is the account these specs are written for.";
    return;
  }

  const created = await request.post(`${API}/api/users`, {
    headers,
    data: { email: DESIGNER_EMAIL, name: DESIGNER_NAME, password: TEMP_PASSWORD, role: "DESIGNER" }
  });
  expect(created.ok(), `create the fixture designer: ${created.status()} ${await created.text()}`).toBeTruthy();
  designerId = (await created.json()).id as string;

  // The account replaces the password an admin typed for it, which is what clears
  // `mustChangePassword`. Done through the API rather than by driving `FirstPasswordLocked`: that
  // gate has its own spec, and a fixture that depends on another feature's form is a fixture that
  // fails for reasons this file is not about.
  designerToken = await apiToken(request, DESIGNER_EMAIL, TEMP_PASSWORD);
  const changed = await request.post(`${API}/api/auth/change-password`, {
    headers: bearer(designerToken),
    data: { currentPassword: TEMP_PASSWORD, newPassword: OWN_PASSWORD }
  });
  expect(changed.ok(), `clear mustChangePassword: ${changed.status()} ${await changed.text()}`).toBeTruthy();

  // Asserted rather than assumed: the page under test renders only once the gate has let go, and a
  // silently-still-flagged account would make every assertion below describe a lock screen.
  const after = await request.get(`${API}/api/me`, { headers: bearer(designerToken) });
  expect(after.ok(), `re-read the fixture designer: ${after.status()}`).toBeTruthy();
  const account = (await after.json()) as { role?: string; mustChangePassword?: boolean };
  expect(account.role, "the fixture must be a DESIGNER").toBe("DESIGNER");
  expect(account.mustChangePassword ?? false, "the first-password gate must be closed").toBe(false);
});

test.afterAll(async ({ request }) => {
  if (designerId) await discard(request, adminToken, `/api/users/${designerId}`);
});

/**
 * Land on `/guide` as a given account WITHOUT passing through `/dashboard`.
 *
 * `signIn()` ends on the dashboard, and that matters here rather than being a detail:
 * `DesignerProfileOnboarding` sends a designer with an empty profile from `/dashboard` to
 * `/designers/profile?welcome=1` on their first session — which every fixture designer is. It fires
 * from that one landing route by design, so going straight to `/guide` is not a workaround, it is the
 * ordinary "the reader went somewhere on purpose" path the redirect deliberately does not overrule.
 */
async function openGuide(page: Page, token: string, hash = "") {
  await signInWithToken(page, token);
  await page.goto(`/guide${hash}`);
  await expect(page.locator(HERO)).toHaveCount(1);
}

/* ────────────────────────────────────────────────────────────────────────────
 * 1 — the duplicate hero card
 * ──────────────────────────────────────────────────────────────────────────── */

test("an admin is offered every deck, which is what makes the next test mean anything", async ({ page }) => {
  test.skip(!!cannotRun, cannotRun);
  await openGuide(page, adminToken);

  const panel = page.locator(SWITCHER);
  await expect(panel).toHaveCount(1);
  for (const track of [DESIGNER_TRACK, DIRECTORATE_TRACK, INSPECTOR_TRACK]) {
    await expect(panel.getByRole("button", { name: track.name })).toHaveCount(1);
  }
});

test("switching decks leaves exactly one purple hero card on the page", async ({ page }) => {
  /*
    THE REGRESSION GUARD FOR THE DEFECT FIXED ON 2026-09-16.

    The visit order is deliberate and is the defect's own worst case: every deck once, then BACK to
    one already read. Returning to a deck you have seen appends another band exactly as a new deck
    does — the eviction happens to whichever hero is currently mounted, not to a deck seen for the
    first time — so a fix that somehow only handled forward motion would fail on the last step here.

    COUNTED, NEVER SAMPLED. Each assertion is `toHaveCount(1)`, and each is made twice over: once on
    the band itself and once on the button inside it, because the two failures are different in kind.
    A second band is a visual defect; a second "Start at step 1" is an ACCESSIBILITY one — a stale
    button in the tab order, wired to the previous deck's closure, that a keyboard reader meets before
    the live one. The headline assertion then says the surviving band is the deck that was asked for
    rather than merely a band.
  */
  test.skip(!!cannotRun, cannotRun);
  await openGuide(page, adminToken);

  const panel = page.locator(SWITCHER);
  for (const track of [DESIGNER_TRACK, DIRECTORATE_TRACK, INSPECTOR_TRACK, DESIGNER_TRACK]) {
    await panel.getByRole("button", { name: track.name }).click();
    await expect(page.locator(`${HERO} h2`), `after choosing ${track.id}`).toHaveText(track.headline);
    await expect(page.locator(HERO), `${track.id}: one band, not one per deck visited`).toHaveCount(1);
    await expect(
      page.getByRole("button", { name: "Start at step 1" }),
      `${track.id}: a stale band keeps its own live button in the tab order`
    ).toHaveCount(1);
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2 — the decks a role is scoped to
 * ──────────────────────────────────────────────────────────────────────────── */

test("a designer is shown the designer's deck, and no other deck and no switcher", async ({ page }) => {
  test.skip(!!cannotRun, cannotRun);
  await openGuide(page, designerToken);

  // The deck they own is on screen, whole.
  await expect(page.locator(`${HERO} h2`)).toHaveText(DESIGNER_TRACK.headline);

  // The control is not merely disabled or empty — it is not rendered. One button under a heading
  // reading "Which walkthrough", `aria-pressed={true}`, doing nothing when pressed, is a control that
  // controls nothing, which is why the page omits the panel rather than drawing a list of one.
  await expect(page.locator(SWITCHER)).toHaveCount(0);
  await expect(page.locator("#guide-track-switch-heading")).toHaveCount(0);

  /*
    AND THE OTHER DECKS ARE NOT ON THE PAGE AT ALL, which is the ruling — "the buttons are gone" would
    be satisfied by a page still rendering another deck's prose underneath. Asserted on the headlines
    and audience lines because those are unique to a deck: several step LABELS are nav entries that
    appear elsewhere in the chrome, and asserting their absence would be asserting something about the
    menu instead.

    ⚠ THIS IS ABOUT WHAT IS PRESENTED, NOT ABOUT WHAT IS OBTAINABLE. All three decks are statically
    imported by `tracks.ts` and all three are in the JavaScript bundle for every viewer; a reader with
    devtools can still read the prose. That is accepted deliberately — the decks are documentation, no
    records — and the reasoning is in `tracks.ts`' header. Do not "strengthen" this into a bundle
    assertion: it would fail, and the change that would make it pass is a `next/dynamic` split per deck
    plus a server-side decision.
  */
  const body = page.locator("body");
  for (const track of [DIRECTORATE_TRACK, INSPECTOR_TRACK]) {
    await expect(body, `${track.id}'s headline is on a designer's walkthrough`).not.toContainText(track.headline);
    await expect(body, `${track.id}'s audience line is on a designer's walkthrough`).not.toContainText(track.audience);
  }
});

test("a deep link into a deck the reader may not see is ignored, and says nothing about it", async ({ page }) => {
  /*
    OQ-5, ARM (b): THE ROLE WINS AND THE ANCHOR IS SILENTLY IGNORED. Arm (c) — open the reader's own
    deck and name the deck the link belonged to in one line — was costed and not taken, so "silently"
    is the assertion and not an omission: no note, no redirect, and the hash still sitting in the URL
    having done nothing.

    THE ANCHOR IS TAKEN OFF THE DECK AND NEVER TYPED: `DIRECTORATE_TRACK.steps[0].id`, which today is
    `ministry-annual-plan` — the directorate deck's FIRST card, and first on purpose because it is the
    one screen in that deck an Assistant Director cannot open (`directorateSteps.ts` argues it there).
    Deriving it is what keeps this test pointing at a real card if the deck is ever reordered, and it
    is why the failure message and the URL will both read `ministry-annual-plan`.

    ⚠ THIS PARAGRAPH SAID `#ministry-workshop` UNTIL 2026-09-16 AND THE LINE BELOW NEVER DID. That id
    is the deck's FOURTH card. Corrected here rather than "restored" in the code: hardcoding the
    documented anchor would quietly change the card under test from the deliberate first one to the
    fourth, and the first person to run this file would have been reading the comment while looking at
    a failure message that said something else.

    `guideTrackForAnchor` resolves this anchor to the directorate deck and the unit spec pins every
    anchor's owner, so it is a link a colleague could paste to a designer today — even though
    **nothing in the product emits one**: every `/guide` entry point goes to the bare route
    (`grep -rn '"/guide' frontend/app frontend/components` is the list, which is how `tracks.ts` and
    `steps.ts` both state it; the COUNT is deliberately not written here, having been falsified twice
    in those files already). The day something starts emitting anchors, this behaviour is worth
    re-opening, and this test is where the decision is written down.
  */
  test.skip(!!cannotRun, cannotRun);
  const anchor = DIRECTORATE_TRACK.steps[0].id;
  await openGuide(page, designerToken, `#${anchor}`);

  await expect(page.locator(`${HERO} h2`), "the role's own deck opens, exactly as with no hash").toHaveText(
    DESIGNER_TRACK.headline
  );
  await expect(page.locator("body")).not.toContainText(DIRECTORATE_TRACK.headline);
  // No redirect: the anchor is ignored where it stands rather than rewritten away, which is also what
  // makes it harmless — reloading gives the same page again.
  expect(page.url()).toContain(`#${anchor}`);
});
