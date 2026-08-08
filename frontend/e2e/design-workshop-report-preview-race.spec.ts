import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

/**
 * THE PREVIEW ON SCREEN MUST BE THE TEMPLATE THE PAGE SAYS IT IS.
 *
 * `loadPreview` awaited `previewDesignWorkshopReport` and called `setPreview` with no generation
 * counter, no `cancelled` flag and no AbortSignal — the three race conventions this repository
 * documents for exactly this case — while the effect above it refires on every `templateId` change.
 * So: pick "Photo catalogue", then pick "Compact summary" before the first response lands. The
 * compact preview renders, the older photo-catalogue answer arrives second and replaces it, and
 * because `previewing` is already false there is no "Refreshing…" to warn anybody. The dropdown,
 * its description and both download buttons say Compact summary while the A4 sheets on screen are
 * the Photo catalogue document.
 *
 * That is the exact failure this screen exists to prevent. Its own header says a designer reads the
 * preview INSTEAD of opening the generated file; approving a preview of a different template than
 * the one about to be generated is worse than having no preview at all.
 *
 * The two templates are told apart by a heading only one of them emits — read from the API in this
 * spec rather than assumed, so a template whose sections are renamed makes this spec adjust rather
 * than fail with a mystery string.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

/**
 * The seeded, fully populated workshop. READ ONLY here — this spec chooses templates and reads
 * pages; it writes nothing, and the stage-20 settings panel is never touched.
 */
const WORKSHOP = process.env.E2E_DW_ID ?? "cmsik2jg8000eh8xc1lcy661a";

const SLOW = "PHOTO_CATALOGUE";
const FAST = "COMPACT_SUMMARY";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

async function apiToken(page: Page): Promise<string> {
  const res = await page.request.post(`${API}/api/auth/login`, { data: { email: EMAIL, password: PASSWORD } });
  expect(res.ok(), `sign-in for the API fixture: ${await res.text()}`).toBeTruthy();
  return (await res.json()).accessToken as string;
}

/** One template's preview: its headings, and every word of it. */
async function previewText(page: Page, token: string, templateId: string): Promise<{ headings: string[]; all: string }> {
  const res = await page.request.get(
    `${API}/api/design-workshops/${WORKSHOP}/report/preview?templateId=${templateId}`,
    { headers: { Authorization: `Bearer ${token}` } }
  );
  expect(res.ok(), `preview ${templateId}: ${await res.text()}`).toBeTruthy();
  const payload = (await res.json()) as { blocks: Array<Record<string, unknown>> };
  const runsOf = (block: Record<string, unknown>) =>
    ((block.runs as Array<{ text?: string }>) ?? []).map((run) => run.text ?? "").join("");
  return {
    // The block payload is `dataclasses.asdict()` output — see the snake_case note in
    // `lib/designWorkshops.ts`. The discriminant arrives as "HEADING".
    headings: payload.blocks
      .filter((block) => String(block.type).toUpperCase() === "HEADING")
      .map(runsOf)
      .filter(Boolean),
    all: payload.blocks.map(runsOf).join("\n")
  };
}

test("a slower earlier template cannot overwrite the preview the designer selected", async ({ page }) => {
  const token = await apiToken(page);

  /*
    What each document says that the other does not — derived from the real payloads, never
    hardcoded, so renaming a template's sections makes this spec adjust rather than fail on a
    mystery string. Tested against the OTHER document's whole text rather than only its headings:
    a heading like "Products" is a substring of half a dozen lines of prose in both formats, and a
    discriminator that can appear inside the wrong document proves nothing.
  */
  const slow = await previewText(page, token, SLOW);
  const fast = await previewText(page, token, FAST);
  const distinct = (mine: string[], theirs: string) =>
    mine.find((head) => head.length >= 12 && !theirs.includes(head));
  const onlySlow = distinct(slow.headings, fast.all);
  const onlyFast = distinct(fast.headings, slow.all);
  expect(onlySlow, `${SLOW} has a heading that appears nowhere in ${FAST}`).toBeTruthy();
  expect(onlyFast, `${FAST} has a heading that appears nowhere in ${SLOW}`).toBeTruthy();
  console.log(`discriminators — ${SLOW}: "${onlySlow}" · ${FAST}: "${onlyFast}"`);

  await signIn(page);

  /*
    ONLY THE FIRST TEMPLATE IS SLOW. Three seconds is not a contrivance — this preview traverses a
    whole 22-stage record and resolves its media on a laptop sharing a rural link — but the exact
    number does not matter: the defect needs only that the FIRST request answers after the second.
  */
  await page.route(`**/report/preview*`, async (route) => {
    if (route.request().url().includes(`templateId=${SLOW}`)) {
      await new Promise((resolve) => setTimeout(resolve, 3_000));
    }
    await route.continue();
  });

  await page.goto(`/design-workshops/${WORKSHOP}/report`);
  const templatePicker = page.getByRole("button", { name: "Report template" });
  await expect(templatePicker).toBeVisible({ timeout: 30_000 });
  // The workshop's own template renders first; wait for a document to be on screen at all.
  await expect(page.getByText(/Sheet 1|Page 1|A4/i).first()).toBeVisible({ timeout: 60_000 });

  const pick = async (label: string) => {
    await templatePicker.click();
    await page.getByRole("option", { name: label, exact: true }).click();
  };

  // Pick the slow one, then change your mind — which is what a designer comparing formats does.
  await pick("Photo catalogue");
  await page.waitForTimeout(400);
  await pick("Compact summary");

  // Long enough for the SLOW answer to have arrived and, in the broken world, to have replaced the
  // document on screen.
  await page.waitForTimeout(6_000);

  await expect(
    page.getByRole("button", { name: "Report template" }),
    "the page still says Compact summary"
  ).toContainText("Compact summary");
  // Scoped to the sheets themselves (`.rp-pages`), not to the whole document: the settings panel
  // and the nav legitimately carry words that also appear in a report.
  const sheets = page.locator(".rp-pages");
  await expect(
    sheets.getByText(onlyFast!, { exact: false }).first(),
    `the sheets on screen are the ${FAST} document the dropdown names`
  ).toBeVisible({ timeout: 15_000 });
  await expect(
    sheets.getByText(onlySlow!, { exact: false }),
    `the ${SLOW} document did not arrive late and overwrite it`
  ).toHaveCount(0);
});
