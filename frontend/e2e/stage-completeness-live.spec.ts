import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

/**
 * The stage progress bar counts on this device, as the designer types.
 *
 * WHAT THIS IS DEFENDING. "Required fields in this stage" used to be driven by the figure the API
 * returned at the last successful push. That produced two failures, and neither of them looked like
 * a bug from the inside — the number was always a real number, it was just answering a question
 * nobody had asked:
 *
 *   1. A stage that had never been pushed had no figure, so the panel did not render AT ALL. A
 *      designer opening stage 2 on a new workshop filled in three narrative fields with nothing on
 *      screen counting them, and the panel appeared from nowhere on the first Save.
 *   2. After a Save it FROZE. The designer kept typing and the bar sat where the save had left it,
 *      so "Still needed: Acknowledgement" stayed on screen after the acknowledgement was written.
 *
 * The second is the dangerous one. The progress bar is what a designer reads in a courtyard to
 * decide whether they can pack up, and a stale "Still needed" list is read and acted on — it sends
 * somebody back to an artisan for an answer they already have.
 *
 * WHY IT HAS TO BE A BROWSER TEST. `scoreStageData` is a pure function and a unit test of it would
 * have passed in the broken world too — the scorer was always correct, it simply was not the thing
 * wired to the bar. Only asking the browser "does the number move when I type, with no save and no
 * request" catches the wiring, which is what this does. The whole point of the change is that this
 * costs the server nothing, so the spec asserts NO stage request is made while the number moves.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

/**
 * Stage 2 is used because its required fields are three plain narrative fields and it has NO
 * collection entities, so the expected count is a constant the spec can assert outright rather than
 * a number it has to recompute — and because it is reachable on a brand-new workshop without
 * filling in stage 1 first (the same reason `rich-text-editor.spec.ts` uses it).
 */
const STAGE_KEY = "INTRODUCTORY_ADMIN_DOCUMENTATION";

async function apiToken(page: Page): Promise<string> {
  const res = await page.request.post(`${API}/api/auth/login`, {
    data: { email: EMAIL, password: PASSWORD }
  });
  expect(res.ok(), "sign-in for the API fixture").toBeTruthy();
  return (await res.json()).accessToken as string;
}

/** The "N of M" counter beside the panel heading. */
function counter(page: Page) {
  return page.getByText("Required fields in this stage").locator("..").locator("span").nth(1);
}

test("the required-field count is drawn before any save and moves on a keystroke", async ({ page }) => {
  const token = await apiToken(page);
  const auth = { Authorization: `Bearer ${token}` };

  // The count this stage must show is read from the REGISTRY rather than hardcoded, so that adding
  // a required field to stage 2 makes this spec adjust rather than fail with a mystery number. The
  // registry is the single declaration all three clients render from; the bar is one more thing
  // that has to agree with it.
  const schema = await (await page.request.get(`${API}/api/design-workshops/schema`, { headers: auth })).json();
  const stage = schema.stages.find((s: { key: string }) => s.key === STAGE_KEY);
  expect(stage, `${STAGE_KEY} is in the registry`).toBeTruthy();
  const singletonEntities = (stage.entities ?? []).filter(
    (e: { cardinality: string }) => e.cardinality === "SINGLETON"
  );
  const requiredFields = singletonEntities.flatMap((e: { fields: { key: string; required: boolean; deprecated: boolean; label: string }[] }) =>
    (e.fields ?? []).filter((f) => f.required && !f.deprecated)
  );
  const requiredTotal = requiredFields.length;
  expect(requiredTotal, "stage 2 has required fields to count").toBeGreaterThan(0);
  expect(
    (stage.entities ?? []).filter((e: { cardinality: string }) => e.cardinality !== "SINGLETON"),
    "stage 2 has no collections, so the singleton count IS the whole count"
  ).toHaveLength(0);

  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: auth,
    data: { title: `Live completeness spec ${Date.now()}` }
  });
  expect(created.ok(), "create a design workshop").toBeTruthy();
  const workshopId = (await created.json()).id as string;

  await signIn(page);

  // Fail loudly if anything asks the SERVER to score this stage. The bar is supposed to be free.
  const stageRequests: string[] = [];
  page.on("request", (request) => {
    const url = request.url();
    if (request.method() !== "GET" && url.includes(`/design-workshops/${workshopId}/stages/`)) {
      stageRequests.push(`${request.method()} ${url}`);
    }
  });

  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);

  // Addressed by its ACCESSIBLE NAME, not by being the first `contenteditable` on the page. The two
  // happen to be the same field today; a registry that gains an optional narrative field above it
  // would silently move this spec onto a field whose emptiness proves nothing.
  const firstRequired = requiredFields[0];
  const editor = page.getByRole("textbox", { name: firstRequired.label });
  await expect(editor, `the editor for "${firstRequired.label}" rendered`).toBeVisible({ timeout: 30_000 });

  // FAILURE 1: on a stage that has never been saved, the panel is on screen and reads zero.
  await expect(
    counter(page),
    "the panel is drawn on a never-saved stage instead of appearing only after the first Save"
  ).toHaveText(`0 of ${requiredTotal}`);

  // Every required field is named as outstanding, because none of them is filled.
  const stillNeeded = page.getByText(/^Still needed:/);
  await expect(stillNeeded, "the outstanding list is drawn before any save").toBeVisible();
  for (const field of requiredFields) {
    await expect(
      stillNeeded,
      `"${field.label}" is listed as outstanding while it is empty`
    ).toContainText(field.label);
  }

  // FAILURE 2: type into ONE required field. The count moves without a save.
  const typed = "Barpali";
  await editor.click();
  await page.keyboard.type(typed);

  await expect(
    counter(page),
    "the count moved on the keystroke — with no Save pressed and no request made"
  ).toHaveText(`1 of ${requiredTotal}`);

  // The field that was just written is no longer listed as outstanding. Asserting only the count
  // would pass if `missing` had been left frozen, and the list is the half of this panel a designer
  // actually acts on — a stale entry sends somebody back to an artisan for an answer they have.
  await expect(
    stillNeeded,
    `"${firstRequired.label}" left the outstanding list once it was written`
  ).not.toContainText(firstRequired.label);
  expect(
    stageRequests,
    `the live count must cost nothing: the page sent ${JSON.stringify(stageRequests)}`
  ).toEqual([]);

  // And it goes back down. A bar that only ever climbs would pass everything above while still
  // being a one-way ratchet that lies the moment a designer clears a box they filled by mistake.
  // Backspaced exactly as many times as it was typed, rather than through a select-all: what
  // Control+A means inside a contenteditable is the editor's business, and this spec is not about
  // the editor.
  await editor.click();
  await page.keyboard.press("End");
  for (let i = 0; i < typed.length; i += 1) await page.keyboard.press("Backspace");
  await expect(
    counter(page),
    "emptying the field takes the count back down"
  ).toHaveText(`0 of ${requiredTotal}`);
  await expect(
    stillNeeded,
    `"${firstRequired.label}" is outstanding again once it is emptied`
  ).toContainText(firstRequired.label);
});
