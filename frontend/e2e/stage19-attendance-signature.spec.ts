import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

/**
 * Stage 19's attendance record, and the signature pad that evidences it.
 *
 * WHAT THIS REPLACES. Certificates and attendance are BASIC tier — the minimum a report carries —
 * and the delivered artefact was a PHOTOGRAPH OF A PAPER ATTENDANCE SHEET. A photograph cannot be
 * counted, cannot be reconciled against the roster the same workshop built in stage 3, and cannot
 * be printed as a table, so the one fact the closing stage exists to record was the one fact the
 * report could not state.
 *
 * TWO THINGS ARE ASSERTED, AND THEY ARE DELIBERATELY SEPARATE TESTS, because the whole design rests
 * on their being separable:
 *
 *  1. **Attendance can be recorded with the keyboard alone, never touching the pad.** A signature
 *     pad is unusable to a keyboard-only designer and to anyone whose handset digitiser has stopped
 *     answering in the heat. If attendance were gated behind the pad, the app would refuse to record
 *     a fact the designer watched happen. So the first test drives the reference picker, the Yes/No
 *     buttons and the number box with `keyboard.*` only — no click lands on the canvas at any point
 *     — and then asks the SERVER what it stored. The assertion that matters is that the row it
 *     stored points at the stage-3 roster BY ID: a retyped name would look identical on screen and
 *     be uncountable.
 *
 *  2. **The pad produces a real, non-blank, transparent PNG, and Clear empties it.** This is the
 *     part a unit test cannot do. jsdom has no canvas rasteriser at all — `toDataURL` there returns
 *     a stub — so a jsdom "component test" of a drawing surface can assert that state changed and
 *     literally cannot assert that anything was DRAWN. The only way to tell a pad that renders from
 *     one that quietly renders nothing is to put real pointer events into a real compositor and
 *     read the pixels back, which is what this does.
 *
 * The PNG is read from the actual `Blob` the component exported, not from the display canvas, by
 * recording every blob the page creates (see `recordBlobs`). That matters because the display
 * canvas and the export are two different bitmaps at two different resolutions, and it is the
 * exported one that reaches the report.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

const STAGE_KEY = "INSPECTION_CLOSING";
const ROSTER_STAGE_KEY = "WORKSHOP_PLAN_PARTICIPANTS_OPENING";
/** Distinctive enough that the picker's search narrows to exactly one row. */
const ARTISAN_NAME = "Kamla Devi Signature Fixture";

/**
 * How the BROWSER reaches object storage, when that is not how the server reaches it.
 *
 * The local compose stack presigns `http://minio:9000`, a name that resolves inside the docker
 * network and nowhere else, so every browser upload fails on a developer machine before any code in
 * this repository runs. The signature cannot be worked around by rewriting the URL — SigV4 signs the
 * `Host` header — so a resolver rule is used instead. NOTHING IN THIS SPEC DEPENDS ON THE UPLOAD
 * LANDING: the PNG is asserted at the moment the component produces it, which is upstream of the
 * transfer. The rule is here only so a run against a reachable stack behaves identically.
 *
 *     E2E_OBJECT_STORE_MAP='minio:9000 127.0.0.1:9010'
 */
const OBJECT_STORE_MAP = process.env.E2E_OBJECT_STORE_MAP ?? "";
if (OBJECT_STORE_MAP) {
  test.use({ launchOptions: { args: [`--host-resolver-rules=MAP ${OBJECT_STORE_MAP}`] } });
}

async function apiToken(page: Page): Promise<string> {
  const res = await page.request.post(`${API}/api/auth/login`, { data: { email: EMAIL, password: PASSWORD } });
  expect(res.ok(), `sign-in for the API fixture: ${await res.text()}`).toBeTruthy();
  return (await res.json()).accessToken as string;
}

/**
 * A fresh workshop whose stage-3 roster holds exactly one artisan.
 *
 * Created through the real API rather than through the seeded workshop, so the picker's list is one
 * row long and "the option the test chose" and "the option the assertion expects" cannot drift.
 * Returns the workshop id and the roster row's `_entryId`, which is the id a certificate's
 * `participantRef` must end up holding.
 */
async function seedWorkshopWithRoster(page: Page, token: string): Promise<{ id: string; participantId: string }> {
  const headers = { Authorization: `Bearer ${token}` };
  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers,
    data: { title: `Signature pad fixture ${Date.now()}` }
  });
  expect(created.ok(), `create a design workshop: ${await created.text()}`).toBeTruthy();
  const id = (await created.json()).id as string;

  const seeded = await page.request.put(`${API}/api/design-workshops/${id}/stages/${ROSTER_STAGE_KEY}`, {
    headers,
    data: {
      entries: [{ entityKey: "participant", data: { serialNo: 1, name: ARTISAN_NAME, age: 42 } }],
      replaceCollections: false,
      submit: false
    }
  });
  expect(seeded.ok(), `seed the stage 3 roster: ${await seeded.text()}`).toBeTruthy();

  const roster = await page.request.get(`${API}/api/design-workshops/${id}/stages/${ROSTER_STAGE_KEY}`, { headers });
  expect(roster.ok(), `read the roster back: ${await roster.text()}`).toBeTruthy();
  const rows = ((await roster.json()).collections?.participant ?? []) as Array<Record<string, unknown>>;
  expect(rows, "the roster fixture did not store a participant").toHaveLength(1);
  const participantId = String(rows[0]._entryId ?? "");
  expect(participantId, "the roster row has no _entryId to reference").not.toEqual("");
  return { id, participantId };
}

/** What the server holds for stage 19's certificate collection right now. */
async function serverCertificates(page: Page, token: string, workshopId: string) {
  const res = await page.request.get(`${API}/api/design-workshops/${workshopId}/stages/${STAGE_KEY}`, {
    headers: { Authorization: `Bearer ${token}` }
  });
  expect(res.ok(), `read stage 19 back: ${await res.text()}`).toBeTruthy();
  return ((await res.json()).collections?.certificate ?? []) as Array<Record<string, unknown>>;
}

/**
 * Keep every `Blob` the page turns into an object URL, so the exported PNG can be read back.
 *
 * The capture card creates a preview URL for each attached file and REVOKES it when the file leaves
 * the pending list — which happens as soon as the upload settles either way. Reading the bytes out
 * of the DOM would therefore be a race against the network. Holding the Blob itself is not.
 */
async function recordBlobs(page: Page) {
  await page.addInitScript(() => {
    const store: Blob[] = [];
    (window as unknown as { __capturedBlobs: Blob[] }).__capturedBlobs = store;
    const original = URL.createObjectURL.bind(URL);
    URL.createObjectURL = (object: Blob | MediaSource) => {
      if (object instanceof Blob) store.push(object);
      return original(object);
    };
  });
}

/** Every fully-transparent and every inked pixel of the pad's own bitmap. */
async function canvasInk(page: Page): Promise<{ opaque: number; total: number }> {
  return page.getByTestId("signature-pad-canvas").evaluate((element) => {
    const canvas = element as HTMLCanvasElement;
    const ctx = canvas.getContext("2d");
    if (!ctx) return { opaque: 0, total: 0 };
    const data = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
    let opaque = 0;
    for (let index = 3; index < data.length; index += 4) if (data[index] > 0) opaque += 1;
    return { opaque, total: data.length / 4 };
  });
}

/** Open stage 19 and add one row to the certificates & attendance collection. */
async function openStage19WithARow(page: Page, workshopId: string) {
  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);
  const add = page.getByRole("button", { name: /^Add certificates & attendance$/i });
  await expect(add, "the certificates & attendance collection is not on stage 19").toBeVisible({ timeout: 30_000 });
  await add.click();
}

test("attendance is recorded against a rostered artisan with the keyboard alone", async ({ page }) => {
  const token = await apiToken(page);
  const { id, participantId } = await seedWorkshopWithRoster(page, token);
  await signIn(page);
  await openStage19WithARow(page, id);

  // ── The artisan, chosen from the roster. Keyboard only: open, type, arrow, Enter. ────────────
  // `Artisan *` — `labelText()` appends the asterisk for a required field, and the picker's
  // accessible name comes from that same label element.
  const picker = page.getByRole("button", { name: /^Artisan\s*\*?$/ }).first();
  await expect(picker).toBeVisible();
  await picker.focus();
  await page.keyboard.press("Enter");
  const search = page.getByPlaceholder(/^Search artisan$/i);
  await expect(search, "the roster picker did not open from the keyboard").toBeVisible();
  await search.pressSequentially("Kamla Devi Signature", { delay: 20 });
  await expect(page.getByRole("option", { name: new RegExp(ARTISAN_NAME, "i") })).toBeVisible({ timeout: 20_000 });
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Enter");
  // The trigger now names the person, which is the only on-screen proof the id was taken.
  await expect(picker).toContainText(ARTISAN_NAME);

  // ── Certified, and the days they came. Both ordinary registry controls. ──────────────────────
  await page.getByRole("group", { name: /^Issued\s*\*?$/ }).getByRole("button", { name: "Yes" }).click();
  await page.getByLabel(/^Days attended$/).fill("5");

  // ── THE PAD IS NEVER TOUCHED. It is on screen; nothing in this test has clicked it. ──────────
  const pad = page.getByTestId("signature-pad-canvas");
  await expect(pad, "the signature pad should still be offered — just not required").toBeVisible();
  await expect(pad).toHaveAttribute("data-empty", "true");

  await page.getByRole("button", { name: "Save stage" }).click();
  await expect(page.getByText(/Saved on this device/i).first()).toBeVisible({ timeout: 30_000 });

  // ── What the SERVER stored. The join key is the whole point. ─────────────────────────────────
  await expect
    .poll(async () => (await serverCertificates(page, token, id)).length, { timeout: 30_000 })
    .toBeGreaterThan(0);
  const rows = await serverCertificates(page, token, id);
  expect(rows).toHaveLength(1);
  expect(
    rows[0].participantRef,
    "attendance must reference the stage-3 roster row by id, or the report cannot count it"
  ).toBe(participantId);
  expect(rows[0].issued).toBe(true);
  expect(rows[0].daysAttended).toBe(5);
  // The evidence is absent and the record is still complete — which is the accessibility contract.
  expect(rows[0].signatureImage ?? null).toBeNull();
});

test("a stroke on the pad produces a non-blank transparent PNG, and Clear empties it", async ({ page }) => {
  const token = await apiToken(page);
  const { id } = await seedWorkshopWithRoster(page, token);
  await recordBlobs(page);
  await signIn(page);
  await openStage19WithARow(page, id);

  const pad = page.getByTestId("signature-pad-canvas");
  await expect(pad).toBeVisible();

  // ── Blank to begin with, asserted on the pixels and not merely on a flag. ────────────────────
  expect((await canvasInk(page)).opaque, "a fresh pad is not blank").toBe(0);
  const attach = page.getByRole("button", { name: /^Attach signature$/ });
  await expect(attach, "an empty pad must have nothing to attach").toBeDisabled();

  // ── One stroke, as a real pointer drag across the pad. ───────────────────────────────────────
  // Scrolled into view FIRST: `boundingBox()` reports viewport coordinates and does not scroll, so
  // on this page — where the collection row sits well below the fold — the drag would otherwise be
  // aimed at whatever happens to be at those screen coordinates instead.
  await pad.scrollIntoViewIfNeeded();
  const box = await pad.boundingBox();
  expect(box, "the pad has no layout box").not.toBeNull();
  if (!box) return;
  await page.mouse.move(box.x + box.width * 0.15, box.y + box.height * 0.7);
  await page.mouse.down();
  // Several moves, not one: a single move would be one straight segment, and the curve smoothing in
  // `renderSignature` is exactly what a one-segment stroke would not exercise.
  for (const [fx, fy] of [
    [0.3, 0.3],
    [0.45, 0.75],
    [0.6, 0.25],
    [0.75, 0.65],
    [0.85, 0.4]
  ]) {
    await page.mouse.move(box.x + box.width * fx, box.y + box.height * fy);
  }
  await page.mouse.up();

  await expect(pad).toHaveAttribute("data-empty", "false");
  const drawn = await canvasInk(page);
  expect(drawn.opaque, "the stroke drew nothing onto the pad").toBeGreaterThan(0);
  // A pad that filled its whole bitmap would also be "non-blank" and would be a bug, so the ink is
  // bounded as well: a signature is a line, not a wash.
  expect(drawn.opaque).toBeLessThan(drawn.total * 0.5);

  // ── The EXPORTED PNG — the bitmap that actually reaches the report. ──────────────────────────
  await expect(attach).toBeEnabled();
  await attach.click();

  type PngStats = { magic: number[]; width: number; height: number; opaque: number; transparent: number };
  const readExportedPng = async (): Promise<PngStats | null> =>
    page.evaluate(async () => {
      const blobs = (window as unknown as { __capturedBlobs?: Blob[] }).__capturedBlobs ?? [];
      const found = blobs.find((blob) => blob.type === "image/png");
      if (!found) return null;
      const bytes = new Uint8Array(await found.arrayBuffer());
      const bitmap = await createImageBitmap(found);
      const canvas = document.createElement("canvas");
      canvas.width = bitmap.width;
      canvas.height = bitmap.height;
      const ctx = canvas.getContext("2d");
      if (!ctx) return null;
      ctx.drawImage(bitmap, 0, 0);
      const data = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
      let opaque = 0;
      let transparent = 0;
      for (let index = 3; index < data.length; index += 4) {
        if (data[index] > 0) opaque += 1;
        else transparent += 1;
      }
      return {
        magic: Array.from(bytes.slice(0, 4)),
        width: bitmap.width,
        height: bitmap.height,
        opaque,
        transparent
      };
    });

  await expect
    .poll(async () => (await readExportedPng()) !== null, {
      timeout: 30_000,
      message: "the pad never handed a PNG blob to the media path"
    })
    .toBe(true);
  const stats = (await readExportedPng()) as PngStats;

  // \x89 P N G — it is genuinely a PNG and not a canvas fallback to JPEG.
  expect(stats.magic).toEqual([137, 80, 78, 71]);
  expect(stats.width).toBeGreaterThan(0);
  expect(stats.opaque, "the exported PNG is blank — the signature would print as nothing").toBeGreaterThan(0);
  // THE TRANSPARENCY REQUIREMENT, asserted rather than assumed: a JPEG-style white background would
  // put an opaque box over the table rules the signature sits between.
  expect(stats.transparent, "the exported PNG has no transparent pixels").toBeGreaterThan(0);

  // Attaching clears the pad, so the same signature cannot be uploaded three times over.
  await expect(pad).toHaveAttribute("data-empty", "true");
  expect((await canvasInk(page)).opaque).toBe(0);

  // ── And Clear empties a pad that has been drawn on. ──────────────────────────────────────────
  await page.mouse.move(box.x + box.width * 0.2, box.y + box.height * 0.5);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width * 0.8, box.y + box.height * 0.5);
  await page.mouse.up();
  expect((await canvasInk(page)).opaque, "the second stroke drew nothing").toBeGreaterThan(0);

  await page.getByRole("button", { name: /^Clear$/ }).click();
  expect((await canvasInk(page)).opaque, "Clear left ink on the pad").toBe(0);
  await expect(pad).toHaveAttribute("data-empty", "true");
  await expect(attach, "a cleared pad must have nothing to attach").toBeDisabled();
});
