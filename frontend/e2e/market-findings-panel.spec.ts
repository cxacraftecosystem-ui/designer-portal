import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

/**
 * Stage 9's findings panel actually reaches the screen — and reaches it WITHOUT the network.
 *
 * This spec exists because the analysis was built twice and surfaced nowhere. The pure Python
 * module, its 47 tests, the endpoint and the TypeScript port can all be perfect and correct while
 * no client ever renders a single finding; a unit test of `lib/marketAnalysis.ts` passes in that
 * world, and so does a test of the API. Only asking the browser "is the finding on the page?"
 * catches it, which is what this does.
 *
 * It therefore asserts the JOIN, not the parts, in three claims:
 *
 *   1. On stage 9 of a workshop whose survey contradicts one of its declared bands, the panel is on
 *      screen and the LOW verdict is legible — the finding, in words, not merely a coloured chip.
 *   2. On another stage the panel is ABSENT. A findings panel about stage 8's prices bolted onto
 *      every stage would be noise on twenty-one screens, and "renders everywhere" is the failure a
 *      test of stage 9 alone cannot see.
 *   3. With `GET .../market-analysis` aborted at the network, the SAME finding still renders, and
 *      the endpoint is never even asked for. That is the whole reason the port exists: the designer
 *      who most needs to know their band is under what buyers said is standing in the village where
 *      they asked, on no signal. A panel that fetched its findings would be blank exactly there.
 *
 * Claim 3 is the load-bearing one, and its setup matters: the spec opens stage 8 first, because
 * that is what puts the survey rows in this browser's IndexedDB — which is also precisely what a
 * designer does, since they capture the survey before they analyse it. From that point on the
 * arithmetic is local and the network is irrelevant, which is what the assertions check.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

/**
 * The fully-populated flagship: ten respondent price expectations, five competitor products and
 * four declared bands, three of which the survey supports and one of which it does not.
 *
 * Overridable, because it is a seeded record rather than one this spec creates. It is used rather
 * than a workshop built here on purpose — a band can only be found LOW against a real distribution,
 * and a fixture thin enough to write inline would be refused as UNVERIFIABLE, which is the module
 * behaving correctly and would prove nothing about the panel.
 *
 * NOTE ON THE ACCOUNT: this workshop was created by a third user, so only an ADMIN sees it. A
 * DESIGNER-role account gets 404 from every endpoint that names it, including the one this spec
 * blocks. Run with `E2E_EMAIL=admin2@example.org`, or point `E2E_DW_ID` at a workshop the account
 * you are using can open.
 */
const WORKSHOP = process.env.E2E_DW_ID ?? "cmsik2jg8000eh8xc1lcy661a";

const ANALYSIS_STAGE = "MARKET_ANALYSIS_DIRECTION";
const SURVEY_STAGE = "MARKET_SURVEY_CAPTURE";

async function apiToken(page: Page): Promise<string> {
  const res = await page.request.post(`${API}/api/auth/login`, {
    data: { email: EMAIL, password: PASSWORD }
  });
  expect(res.ok(), "sign-in for the API fixture").toBeTruthy();
  return (await res.json()).accessToken as string;
}

/**
 * The band the survey contradicts, read from the server.
 *
 * Asserted BEFORE the browser assertions so a failure says which end broke. If this workshop's data
 * ever changes so that nothing comes back LOW, the browser assertions below would fail with "no
 * such text" and send a reader hunting through the panel for a bug that is in the fixture.
 */
async function lowBand(page: Page, token: string) {
  const res = await page.request.get(`${API}/api/design-workshops/${WORKSHOP}/market-analysis`, {
    headers: { Authorization: `Bearer ${token}` }
  });
  expect(res.ok(), `the account can read ${WORKSHOP} — a DESIGNER-role account cannot`).toBeTruthy();
  const payload = await res.json();
  const band = (payload.bands as { verdict: string; category: string; message: string }[]).find(
    (row) => row.verdict === "LOW"
  );
  expect(band, "the flagship has a band the survey says is LOW").toBeTruthy();
  return band as { verdict: string; category: string; message: string };
}

const panelHeading = (page: Page) => page.getByRole("heading", { name: "What the survey says" });

test("stage 9 shows the findings panel, and the LOW band is legible in it", async ({ page }) => {
  const band = await lowBand(page, await apiToken(page));

  await signIn(page);
  await page.goto(`/design-workshops/${WORKSHOP}/stages/${ANALYSIS_STAGE}`);

  await expect(panelHeading(page), "the findings panel mounted on stage 9").toBeVisible({
    timeout: 60_000
  });

  // The verdict as a WORD, not only as a tint — colour never carries meaning alone in this app, and
  // a chip that only changed hue would not survive greyscale or a courtyard at noon.
  await expect(page.getByText("Below the evidence").first()).toBeVisible();

  // And the finding itself: the sentence a designer has to be able to act on, naming the category
  // and the median the survey actually recorded.
  await expect(
    page.getByText(`sits below the evidence for ${band.category}`, { exact: false }).first()
  ).toBeVisible();

  // This browser has never opened stage 8, so the panel is honest about having asked the repository
  // — and about the fact that a server answer describes what has been SAVED, not what is on screen.
  await expect(page.getByText(/Computed by the repository/i).first()).toBeVisible();
});

test("no other stage grows a market findings panel", async ({ page }) => {
  await signIn(page);
  await page.goto(`/design-workshops/${WORKSHOP}/stages/${SURVEY_STAGE}`);

  // Wait for the stage form itself, or "the panel is absent" would also be true of a page that has
  // not finished loading — which is the assertion passing for the wrong reason.
  await expect(page.getByRole("button", { name: "Save stage" })).toBeVisible({ timeout: 60_000 });
  await expect(panelHeading(page), "stage 8 must not render stage 9's panel").toHaveCount(0);
});

test("the findings still render with the analysis endpoint blocked", async ({ page }) => {
  const band = await lowBand(page, await apiToken(page));

  // Aborted for the WHOLE test, including the visit to stage 8 below. A route installed only for
  // the second navigation would leave the possibility that the panel had already been served by the
  // endpoint and was rendering a cached answer.
  let analysisRequests = 0;
  await page.route("**/design-workshops/*/market-analysis*", async (route) => {
    analysisRequests += 1;
    await route.abort("failed");
  });

  await signIn(page);

  // STAGE 8 FIRST — this is the setup, and it is also the ordinary order of work. Opening the stage
  // downloads its rows into this browser's draft; from here on the analysis has everything it needs
  // on disk. The form is held back until the repository has answered the first time, so a visible
  // Save button means the survey rows have landed.
  await page.goto(`/design-workshops/${WORKSHOP}/stages/${SURVEY_STAGE}`);
  await expect(page.getByRole("button", { name: "Save stage" })).toBeVisible({ timeout: 60_000 });

  await page.goto(`/design-workshops/${WORKSHOP}/stages/${ANALYSIS_STAGE}`);

  await expect(panelHeading(page), "the panel renders without the analysis endpoint").toBeVisible({
    timeout: 60_000
  });
  await expect(page.getByText("Below the evidence").first()).toBeVisible();
  await expect(
    page.getByText(`sits below the evidence for ${band.category}`, { exact: false }).first()
  ).toBeVisible();

  // Computed here, from the rows on this device — and it says so, because "the repository worked
  // this out" and "this laptop worked this out" are different claims about how current it is.
  await expect(page.getByText(/Computed on this device/i).first()).toBeVisible();

  // The strongest form of the claim: not "it coped when the fetch failed" but "it never fetched".
  expect(analysisRequests, "the panel must not reach for the network when the rows are here").toBe(0);
});
