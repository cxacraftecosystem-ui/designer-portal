import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

/**
 * A missing artisan can be created WITHOUT leaving the stage.
 *
 * Half a design workshop's fields are references. The picker can only offer records that already
 * exist, so a designer who reached stage 3 and found the artisan missing had to abandon a
 * half-filled stage, go to /artisans/new, fill in the full page, come back, find their place and
 * re-open the row — in a room, with the artisan in front of them.
 *
 * The dialog mounts the REAL `ArtisanForm`, not a simpler one, which is the point: an artisan
 * carries an Aadhaar checksum, a duplicate check against the repository's deduplication key, a
 * mandatory location and the Do's and Don'ts. A four-box "quick create" would be a second answer
 * to what an artisan is, and the records it made would be the ones quietly missing fields.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

/**
 * A Verhoeff-valid 12-digit number, unique per run.
 *
 * `aadhaarNumber` is the repository's deduplication key and is UNIQUE in the database, so a fixed
 * fixture number passes once and then 409s on every later run — a test that skips itself after the
 * first execution is a test that stopped existing. The checksum is the UIDAI one the API enforces
 * (`app/services/artisan_identity.verhoeff_ok`); these are fixtures, not anybody's Aadhaar.
 */
function verhoeffAadhaar(seed: number): string {
  const d = [
    [0, 1, 2, 3, 4, 5, 6, 7, 8, 9], [1, 2, 3, 4, 0, 6, 7, 8, 9, 5],
    [2, 3, 4, 0, 1, 7, 8, 9, 5, 6], [3, 4, 0, 1, 2, 8, 9, 5, 6, 7],
    [4, 0, 1, 2, 3, 9, 5, 6, 7, 8], [5, 9, 8, 7, 6, 0, 4, 3, 2, 1],
    [6, 5, 9, 8, 7, 1, 0, 4, 3, 2], [7, 6, 5, 9, 8, 2, 1, 0, 4, 3],
    [8, 7, 6, 5, 9, 3, 2, 1, 0, 4], [9, 8, 7, 6, 5, 4, 3, 2, 1, 0]
  ];
  const p = [
    [0, 1, 2, 3, 4, 5, 6, 7, 8, 9], [1, 5, 7, 6, 2, 8, 3, 0, 9, 4],
    [5, 8, 0, 3, 7, 9, 6, 1, 4, 2], [8, 9, 1, 6, 0, 4, 3, 5, 2, 7],
    [9, 4, 5, 3, 1, 2, 6, 8, 7, 0], [4, 2, 8, 6, 5, 7, 3, 9, 0, 1],
    [2, 7, 9, 3, 8, 0, 6, 4, 1, 5], [7, 0, 4, 6, 9, 1, 3, 2, 5, 8]
  ];
  const inv = [0, 4, 3, 2, 1, 5, 6, 7, 8, 9];
  // 11 digits from the seed, never starting 0 or 1 (UIDAI reserves those).
  const body = String(2 + (seed % 8)) + String(seed).padStart(10, "0").slice(-10);
  let c = 0;
  const reversed = body.split("").reverse();
  reversed.forEach((digit, i) => {
    c = d[c][p[(i + 1) % 8][Number(digit)]];
  });
  return body + String(inv[c]);
}


test("a reference picker offers to create the missing record, without leaving the stage", async ({
  page
}) => {
  const token = (
    await (await page.request.post(`${API}/api/auth/login`, { data: { email: EMAIL, password: PASSWORD } })).json()
  ).accessToken as string;

  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { title: `Inline create spec ${Date.now()}` }
  });
  const workshopId = (await created.json()).id as string;

  await signIn(page);
  // Stage 3 carries the participant roster, whose rows reference an Artisan.
  await page.goto(`/design-workshops/${workshopId}/stages/WORKSHOP_PLAN_PARTICIPANTS_OPENING`);

  // Add a participant row so its reference field exists.
  const addRow = page.getByRole("button", { name: /add participating artisan/i }).first();
  await expect(addRow).toBeVisible({ timeout: 30_000 });
  await addRow.click();

  // Open the artisan reference picker on that row. The picker's trigger carries the field's own
  // accessible name, so it is found by the label the registry gives the field.
  // `.last()`: two controls carry this name on the page — the stage-level "Add several from
  // artisan record" roster multi-select, and the ROW's own single picker. It is the row's one that
  // this feature is about.
  const picker = page.getByRole("button", { name: /artisan record/i }).last();
  await expect(picker).toBeVisible({ timeout: 30_000 });
  await picker.click();

  // By TEXT rather than by accessible name: the control is a button carrying an aria-hidden icon
  // and a template string that changes with the search box ("Create a new artisan" when empty,
  // "Create “Meera” as a new artisan" once something is typed).
  const createButton = page.getByText(/create .*new artisan/i).first();
  await expect(createButton, "the picker offers inline creation").toBeVisible({ timeout: 15_000 });
  await createButton.click();

  // The dialog mounts the real form — an Aadhaar field is the giveaway that it is not a stub.
  const dialog = page.getByRole("dialog");
  await expect(dialog, "a dialog opened").toBeVisible({ timeout: 15_000 });
  await expect(dialog.getByText(/new artisan/i).first()).toBeVisible();
  await expect(
    dialog.getByText(/aadhaar/i).first(),
    "the REAL ArtisanForm is mounted, not a simplified stub"
  ).toBeVisible({ timeout: 15_000 });

  // And the stage behind it is still there — the whole point.
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden({ timeout: 15_000 });
  expect(page.url()).toContain(`/design-workshops/${workshopId}/stages/`);
});

test("the roster multi-picker offers it too, where leaving costs the most", async ({ page }) => {
  const token = (
    await (await page.request.post(`${API}/api/auth/login`, { data: { email: EMAIL, password: PASSWORD } })).json()
  ).accessToken as string;
  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { title: `Inline roster spec ${Date.now()}` }
  });
  const workshopId = (await created.json()).id as string;

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/stages/WORKSHOP_PLAN_PARTICIPANTS_OPENING`);

  // The stage-level roster picker — "Add several from artisan record".
  const roster = page.getByRole("button", { name: /add several from artisan record/i }).first();
  await expect(roster).toBeVisible({ timeout: 30_000 });
  await roster.click();

  const createButton = page.getByText(/create .*new artisan/i).first();
  await expect(
    createButton,
    "the roster is the likeliest place to find a record missing and the most expensive to leave"
  ).toBeVisible({ timeout: 15_000 });
});

test("a linked record can be corrected without leaving the stage", async ({ page }) => {
  const token = (
    await (await page.request.post(`${API}/api/auth/login`, { data: { email: EMAIL, password: PASSWORD } })).json()
  ).accessToken as string;
  const auth = { Authorization: `Bearer ${token}` };
  const stamp = Date.now();

  // An artisan to link, and a workshop to link it from.
  /*
    A REAL artisan payload, and it has to be. `aadhaarNumber` is the repository's deduplication key
    and is checked against the UIDAI Verhoeff checksum, `dos` and `donts` are non-empty strings.
    Those rules are exactly why the inline dialog mounts the real form instead of a four-box stub —
    a fixture that could cut corners here would be testing something the product does not do.
    The number is generated per run so it never collides with an earlier one.
  */
  // An artisan must be assigned to a craft, so borrow whichever the repository already has.
  const crafts = await (await page.request.get(`${API}/api/crafts?pageSize=1`, { headers: auth })).json();
  const craftId = crafts?.items?.[0]?.id as string | undefined;
  test.skip(!craftId, "no craft exists to assign the fixture artisan to");

  const artisan = await page.request.post(`${API}/api/artisans`, {
    headers: auth,
    data: {
      name: `Inline Edit Artisan ${stamp}`,
      place: "Barpali",
      craftId,
      aadhaarNumber: verhoeffAadhaar(stamp),
      dos: "Keep the warp evenly tensioned.",
      donts: "Do not wash the tied yarn before dyeing.",
      location: { state: "Odisha", district: "Bargarh", latitude: 21.19, longitude: 83.59 }
    }
  });
  expect(artisan.status(), `artisan fixture: ${await artisan.text()}`).toBe(201);
  const artisanId = (await artisan.json()).id as string;

  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: auth,
    data: { title: `Inline edit spec ${stamp}` }
  });
  const workshopId = (await created.json()).id as string;

  // Link it on a participant row through the API, so the test is about the EDIT affordance.
  await page.request.put(`${API}/api/design-workshops/${workshopId}/stages/WORKSHOP_PLAN_PARTICIPANTS_OPENING`, {
    headers: auth,
    data: {
      entries: [{ entityKey: "participant", ordinal: 0, data: { artisanRef: artisanId, name: `Inline Edit Artisan ${stamp}` } }],
      replaceCollections: true,
      submit: false
    }
  });

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/stages/WORKSHOP_PLAN_PARTICIPANTS_OPENING`);

  // Collection rows render COLLAPSED — the row is a button carrying its own label — so the
  // reference field and its controls do not exist until it is opened.
  await page.getByRole("button", { name: new RegExp(`Inline Edit Artisan ${stamp}`, "i") }).first().click();

  const edit = page.getByRole("button", { name: /edit this artisan/i }).first();
  await expect(edit, "a linked record offers to be corrected in place").toBeVisible({ timeout: 30_000 });
  await edit.click();

  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible({ timeout: 15_000 });
  await expect(dialog.getByText(/edit artisan/i).first()).toBeVisible();
  // The record was RE-READ, not seeded from the picker's option — the name is in the form.
  await expect(
    dialog.locator(`input[value*="Inline Edit Artisan ${stamp}"]`).first(),
    "the form holds the real record, not just the picker's label"
  ).toBeVisible({ timeout: 20_000 });
});
