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
 *
 * A DIALOG IS THE ANSWER FOR CREATING AND NO LONGER THE ANSWER FOR CORRECTING. Once a row is LINKED,
 * the record's own page is embedded beneath the picker, so there is no pencil to press and no dialog
 * to open — `StageReferenceField.tsx:1871` suppresses the "Edit this artisan" control for precisely
 * as long as a form is mounted over that record, because two editors over one repository row end
 * with the first one's pre-edit snapshot overwriting the second one's correction. The third test
 * below therefore asserts the embed and the ABSENCE of the pencil; it used to assert the pencil, and
 * that is a change in what the product does rather than a relaxation of what the test demands.
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
  //
  // ANCHORED, and it has to be. This read `/artisan record/i` with `.last()`, on the reasoning that
  // exactly two controls carried the phrase — the stage-level "Add several from artisan record"
  // roster multi-select and the row's own picker — so the last one was the row's. A third arrived
  // with QR scanning: "Scan a card or tag into “Artisan record”, in Participating artisans", which
  // renders immediately AFTER the picker. `.last()` therefore selected the SCANNER, the picker never
  // opened, and the failure named the create button while actually reporting a clicked camera.
  // `/^artisan record$/i` matches the trigger's whole accessible name and nothing else on the page.
  const picker = page.getByRole("button", { name: /^artisan record$/i });
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

  /*
    RE-POINTED 2026-08-23, AND SAY SO: this test used to click an "Edit this artisan" pencil and
    assert on the dialog it opened. That pencil is now deliberately ABSENT on a linked row, and the
    absence is the feature — `StageReferenceSelect` renders it only while
    `recordFormMountedOver !== selectedId` (StageReferenceField.tsx; cite the symbol, not a line —
    that file moves, and this comment shipped once already naming 1871 for a guard on 1873), and `StageRecordEmbed.tsx::embeddedRecordId` states the
    rest: "A LINKED row is never gated, so 'the row names a record' and 'there is a form open over
    it' are the same answer." Two editors over one repository record is the bug that guard exists to
    prevent — the one opened first posts its pre-edit snapshot over the correction made in the other.

    So the affordance the old assertion was looking for has not gone missing; it has been replaced by
    something stronger, which is what is asserted here instead. What the test is FOR is unchanged and
    its title still says it: a linked record can be corrected without leaving the stage. The one
    assertion that carried real information — that the record was RE-READ rather than seeded from the
    picker's option, which holds only a label and a sublabel — is kept exactly as it was, because a
    form seeded from that option would show a blank name box rather than this one.
  */
  await expect(
    page.getByText(/the page below IS that record/i).first(),
    "the linked row says, in the product's own words, that the record itself is open below it"
  ).toBeVisible({ timeout: 30_000 });

  await expect(
    page.getByRole("button", { name: /edit this artisan/i }),
    "no second editor is offered over a record whose form is already mounted — see StageReferenceSelect's recordFormMountedOver guard"
  ).toHaveCount(0);

  // The record was RE-READ, not seeded from the picker's option — the name is in the embedded form.
  await expect(
    page.locator(`input[value*="Inline Edit Artisan ${stamp}"]`).first(),
    "the embedded form holds the real record, not just the picker's label"
  ).toBeVisible({ timeout: 20_000 });

  // And nothing navigated: correcting the record did not cost the half-filled stage.
  expect(page.url()).toContain(`/design-workshops/${workshopId}/stages/`);
});
