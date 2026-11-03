import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

const STAGE_KEY = "INSPECTION_CLOSING";
const ROSTER_STAGE_KEY = "WORKSHOP_PLAN_PARTICIPANTS_OPENING";
const ARTISAN_NAME = "Kamla Devi Signature Fixture";

const OBJECT_STORE_MAP = process.env.E2E_OBJECT_STORE_MAP ?? "";
if (OBJECT_STORE_MAP) {
  test.use({ launchOptions: { args: [`--host-resolver-rules=MAP ${OBJECT_STORE_MAP}`] } });
}

async function apiToken(page: Page): Promise<string> {
  const res = await page.request.post(`${API}/api/auth/login`, { data: { email: EMAIL, password: PASSWORD } });
  expect(res.ok(), `sign-in for the API fixture: ${await res.text()}`).toBeTruthy();
  return (await res.json()).accessToken as string;
}

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

async function serverCertificates(page: Page, token: string, workshopId: string) {
  const res = await page.request.get(`${API}/api/design-workshops/${workshopId}/stages/${STAGE_KEY}`, {
    headers: { Authorization: `Bearer ${token}` }
  });
  expect(res.ok(), `read stage 19 back: ${await res.text()}`).toBeTruthy();
  return ((await res.json()).collections?.certificate ?? []) as Array<Record<string, unknown>>;
}

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
  await expect(picker).toContainText(ARTISAN_NAME);

  await page.getByRole("group", { name: /^Issued\s*\*?$/ }).getByRole("button", { name: "Yes" }).click();
  await page.getByLabel(/^Days attended$/).fill("5");

  const pad = page.getByTestId("signature-pad-canvas");
  await expect(pad, "the signature pad should still be offered — just not required").toBeVisible();
  await expect(pad).toHaveAttribute("data-empty", "true");

  await page.getByRole("button", { name: "Save stage" }).click();
  await expect(page.getByText(/Saved on this device/i).first()).toBeVisible({ timeout: 30_000 });

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

  expect((await canvasInk(page)).opaque, "a fresh pad is not blank").toBe(0);
  const attach = page.getByRole("button", { name: /^Attach signature$/ });
  await expect(attach, "an empty pad must have nothing to attach").toBeDisabled();

  await pad.scrollIntoViewIfNeeded();
  const box = await pad.boundingBox();
  expect(box, "the pad has no layout box").not.toBeNull();
  if (!box) return;
  await page.mouse.move(box.x + box.width * 0.15, box.y + box.height * 0.7);
  await page.mouse.down();
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
  expect(drawn.opaque).toBeLessThan(drawn.total * 0.5);

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

  expect(stats.magic).toEqual([137, 80, 78, 71]);
  expect(stats.width).toBeGreaterThan(0);
  expect(stats.opaque, "the exported PNG is blank — the signature would print as nothing").toBeGreaterThan(0);
  expect(stats.transparent, "the exported PNG has no transparent pixels").toBeGreaterThan(0);

  await expect(pad).toHaveAttribute("data-empty", "true");
  expect((await canvasInk(page)).opaque).toBe(0);

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
