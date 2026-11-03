import { expect, test, type Page } from "@playwright/test";

import { DESIGNER_TRACK, DIRECTORATE_TRACK, INSPECTOR_TRACK } from "@/components/guide/tracks";
import { discard, stamp } from "./support/records";
import { API, apiToken, bearer, CREDENTIALS_MISSING, EMAIL, PASSWORD, signInWithToken } from "./support/session";

test.skip(!!CREDENTIALS_MISSING, CREDENTIALS_MISSING);

const RUN = stamp();
const DESIGNER_EMAIL = `guide-deck-scope-${RUN}@example.org`;
const DESIGNER_NAME = `Guide Deck Scope ${RUN}`;
const TEMP_PASSWORD = `Fixture${RUN}!aA`;
const OWN_PASSWORD = `Chosen${RUN}!aA`;

let adminToken = "";
let designerToken = "";
let designerId = "";
let cannotRun = "";

const HERO = ".surface-dark";
const SWITCHER = "section[aria-labelledby='guide-track-switch-heading']";

test.beforeAll(async ({ request }) => {
  adminToken = await apiToken(request, EMAIL, PASSWORD);
  const headers = bearer(adminToken);

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

  designerToken = await apiToken(request, DESIGNER_EMAIL, TEMP_PASSWORD);
  const changed = await request.post(`${API}/api/auth/change-password`, {
    headers: bearer(designerToken),
    data: { currentPassword: TEMP_PASSWORD, newPassword: OWN_PASSWORD }
  });
  expect(changed.ok(), `clear mustChangePassword: ${changed.status()} ${await changed.text()}`).toBeTruthy();

  const after = await request.get(`${API}/api/me`, { headers: bearer(designerToken) });
  expect(after.ok(), `re-read the fixture designer: ${after.status()}`).toBeTruthy();
  const account = (await after.json()) as { role?: string; mustChangePassword?: boolean };
  expect(account.role, "the fixture must be a DESIGNER").toBe("DESIGNER");
  expect(account.mustChangePassword ?? false, "the first-password gate must be closed").toBe(false);
});

test.afterAll(async ({ request }) => {
  if (designerId) await discard(request, adminToken, `/api/users/${designerId}`);
});

async function openGuide(page: Page, token: string, hash = "") {
  await signInWithToken(page, token);
  await page.goto(`/guide${hash}`);
  await expect(page.locator(HERO)).toHaveCount(1);
}

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

test("a designer is shown the designer's deck, and no other deck and no switcher", async ({ page }) => {
  test.skip(!!cannotRun, cannotRun);
  await openGuide(page, designerToken);

  await expect(page.locator(`${HERO} h2`)).toHaveText(DESIGNER_TRACK.headline);

  await expect(page.locator(SWITCHER)).toHaveCount(0);
  await expect(page.locator("#guide-track-switch-heading")).toHaveCount(0);

  const body = page.locator("body");
  for (const track of [DIRECTORATE_TRACK, INSPECTOR_TRACK]) {
    await expect(body, `${track.id}'s headline is on a designer's walkthrough`).not.toContainText(track.headline);
    await expect(body, `${track.id}'s audience line is on a designer's walkthrough`).not.toContainText(track.audience);
  }
});

test("a deep link into a deck the reader may not see is ignored, and says nothing about it", async ({ page }) => {
  test.skip(!!cannotRun, cannotRun);
  const anchor = DIRECTORATE_TRACK.steps[0].id;
  await openGuide(page, designerToken, `#${anchor}`);

  await expect(page.locator(`${HERO} h2`), "the role's own deck opens, exactly as with no hash").toHaveText(
    DESIGNER_TRACK.headline
  );
  await expect(page.locator("body")).not.toContainText(DIRECTORATE_TRACK.headline);
  expect(page.url()).toContain(`#${anchor}`);
});
