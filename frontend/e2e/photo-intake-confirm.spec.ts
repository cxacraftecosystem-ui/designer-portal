import { expect, test, type Page } from "@playwright/test";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

test.describe.configure({ timeout: 240_000 });

const STAGE_1 = "WORKSHOP_SETUP";
const STAGE_13 = "PROTOTYPE_DEVELOPMENT";

const START = "2026-02-12";
const END = "2026-02-26";
const LOG_DAY = "2026-02-14";

async function apiToken(page: Page): Promise<string> {
  const res = await page.request.post(`${API}/api/auth/login`, {
    data: { email: EMAIL, password: PASSWORD }
  });
  expect(res.ok(), `log in through the API: ${await res.text()}`).toBeTruthy();
  const body = await res.json();
  return (body.accessToken ?? body.access_token ?? body.token) as string;
}

async function signInWithToken(page: Page, token: string) {
  await page.addInitScript((value) => {
    window.localStorage.setItem("field_repo_token", value);
  }, token);
}

async function seedWorkshop(page: Page, token: string, title: string): Promise<string> {
  const headers = { Authorization: `Bearer ${token}` };
  const created = await page.request.post(`${API}/api/design-workshops`, { headers, data: { title } });
  expect(created.ok(), `create a design workshop: ${await created.text()}`).toBeTruthy();
  const id = (await created.json()).id as string;

  const stage1 = await page.request.put(`${API}/api/design-workshops/${id}/stages/${STAGE_1}`, {
    headers,
    data: {
      entries: [
        {
          entityKey: "workshopSetup",
          data: {
            craftName: "Bagru hand block printing",
            clusterName: "Bagru",
            state: "Rajasthan",
            district: "Jaipur",
            venue: "Chhipa Mohalla",
            startDate: START,
            endDate: END,
            designerName: "Test Designer",
            implementingAgency: "Test agency",
            sponsor: "Test sponsor"
          }
        }
      ],
      replaceCollections: false,
      submit: false
    }
  });
  expect(stage1.ok(), `seed stage 1: ${await stage1.text()}`).toBeTruthy();
  expect((await stage1.json()).droppedKeys ?? [], "stage 1 seed dropped nothing").toEqual([]);

  const stage13 = await page.request.put(`${API}/api/design-workshops/${id}/stages/${STAGE_13}`, {
    headers,
    data: {
      entries: [
        {
          entityKey: "prototypeStageLog",
          data: { logDate: LOG_DAY, stageName: "Warping the loom", _clientKey: "log-14-feb" }
        }
      ],
      replaceCollections: false,
      submit: false
    }
  });
  expect(stage13.ok(), `seed stage 13: ${await stage13.text()}`).toBeTruthy();
  expect((await stage13.json()).droppedKeys ?? [], "stage 13 seed dropped nothing").toEqual([]);
  return id;
}

function exifJpeg(stamp: string): Buffer {
  const value = Buffer.from(`${stamp}\0`, "ascii");
  const ifd0Size = 2 + 12 + 4;
  const exifSize = 2 + 12 + 4;
  const exifIfdOffset = 8 + ifd0Size;
  const dataOffset = exifIfdOffset + exifSize;

  const tiff = Buffer.alloc(dataOffset + value.length);
  tiff.write("MM", 0, "ascii");
  tiff.writeUInt16BE(42, 2);
  tiff.writeUInt32BE(8, 4);

  let p = 8;
  tiff.writeUInt16BE(1, p); p += 2;
  tiff.writeUInt16BE(0x8769, p); p += 2;
  tiff.writeUInt16BE(4, p); p += 2;
  tiff.writeUInt32BE(1, p); p += 4;
  tiff.writeUInt32BE(exifIfdOffset, p); p += 4;
  tiff.writeUInt32BE(0, p);

  p = exifIfdOffset;
  tiff.writeUInt16BE(1, p); p += 2;
  tiff.writeUInt16BE(0x9003, p); p += 2;
  tiff.writeUInt16BE(2, p); p += 2;
  tiff.writeUInt32BE(value.length, p); p += 4;
  tiff.writeUInt32BE(dataOffset, p); p += 4;
  tiff.writeUInt32BE(0, p);
  value.copy(tiff, dataOffset);

  const payload = Buffer.concat([Buffer.from("Exif\0\0", "ascii"), tiff]);
  const app1 = Buffer.concat([
    Buffer.from([0xff, 0xe1]),
    Buffer.from([(payload.length + 2) >> 8, (payload.length + 2) & 0xff]),
    payload
  ]);
  const body = Buffer.from(
    "ffdb004300ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" +
      "ffffffffffffffffffffffffffffffffffffffffffffffffc0000b080001000101011100ffc4001400010000" +
      "0000000000000000000000000003ffc40014100100000000000000000000000000000000ffda0008010100003f00d2cf20ffd9",
    "hex"
  );
  return Buffer.concat([Buffer.from([0xff, 0xd8]), app1, body]);
}

const BARE_PNG = Buffer.from(
  "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000a49444154789c6360000002000100" +
    "05fe02fea7b5a3f80000000049454e44ae426082",
  "hex"
);

async function readDraft(page: Page, workshopId: string): Promise<Record<string, any> | null> {
  try {
    return await page.evaluate(async (id) => {
      const rows: any[] = await new Promise((resolve, reject) => {
        const open = indexedDB.open("design-workshop-drafts");
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
          const db = open.result;
          const all = db.transaction("drafts", "readonly").objectStore("drafts").getAll();
          all.onerror = () => reject(all.error);
          all.onsuccess = () => resolve(all.result ?? []);
        };
      });
      return rows.find((row) => row?.localId === id || row?.remoteId === id) ?? null;
    }, workshopId);
  } catch {
    return null;
  }
}

async function openAndAdopt(page: Page, workshopId: string) {
  await page.goto(`/design-workshops/${workshopId}`, { waitUntil: "domcontentloaded" });

  await expect(page.getByText(/12 Feb 2026\s*[–-]\s*26 Feb 2026/)).toBeVisible({ timeout: 120_000 });

  await expect
    .poll(
      async () => {
        const draft = await readDraft(page, workshopId);
        return draft?.stages?.WORKSHOP_SETUP?.singletons?.workshopSetup?.startDate ?? null;
      },
      { timeout: 60_000, message: "the workshop's dates reach this device's draft" }
    )
    .toBe(START);
}

async function storedMediaRefs(page: Page, workshopId: string): Promise<string[]> {
  const draft = await readDraft(page, workshopId);
  const found: string[] = [];
  const walk = (node: unknown) => {
    if (typeof node === "string") {
      if (node.startsWith("dwlocal:")) found.push(node);
      return;
    }
    if (Array.isArray(node)) {
      node.forEach(walk);
      return;
    }
    if (node && typeof node === "object") Object.values(node as Record<string, unknown>).forEach(walk);
  };
  walk(draft);
  return found;
}

test("a dated photograph is proposed with its evidence, and NOTHING is written until Confirm", async ({ page }) => {
  const token = await apiToken(page);
  const workshopId = await seedWorkshop(page, token, `Photo intake ${Date.now()}`);
  await signInWithToken(page, token);

  await openAndAdopt(page, workshopId);

  await page.goto(`/design-workshops/${workshopId}/photos`, { waitUntil: "domcontentloaded" });
  await expect(page.getByRole("heading", { name: "Bulk photo import" })).toBeVisible();

  await expect(page.getByText("Asia/Kolkata")).toBeVisible();

  await expect(page.getByText(/This workshop records \d+ dated entr/)).toBeVisible({ timeout: 30_000 });

  await page.setInputFiles("#photo-intake-files", {
    name: "DSC_0041.JPG",
    mimeType: "image/jpeg",
    buffer: exifJpeg("2026:02:14 10:22:33")
  });

  await expect(page.getByRole("cell", { name: "14 Feb 2026, 10:22", exact: true })).toBeVisible({
    timeout: 30_000
  });

  await expect(page.getByText(/stage 13's Stage logs row “Warping the loom”, date 14 Feb 2026/)).toBeVisible();

  const destination = page.locator("#photo-intake-destination-0 [data-searchable-select]");
  await expect(destination).toContainText("Warping the loom");

  expect(await storedMediaRefs(page, workshopId), "nothing is written before Confirm").toEqual([]);

  await page.getByRole("button", { name: /^Confirm 1 photograph$/ }).click();
  await expect(page.getByText(/1 photograph attached on this device/)).toBeVisible({ timeout: 30_000 });

  await expect
    .poll(async () => (await storedMediaRefs(page, workshopId)).length, { timeout: 15_000 })
    .toBe(1);

  const draft = await readDraft(page, workshopId);
  const rows = draft?.stages?.PROTOTYPE_DEVELOPMENT?.collections?.prototypeStageLog ?? [];
  const row = rows.find((item: any) => (item._clientKey ?? item._entryId) === "log-14-feb");

  expect(Array.isArray(row?.logPhotos), "logPhotos is a list").toBeTruthy();
  expect(
    JSON.stringify(row?.logPhotos),
    "the reference landed on the stage 13 log row that was proposed"
  ).toContain("dwlocal:");

  expect(draft?.stages?.WORKSHOP_SETUP?.singletons?.workshopSetup?.coverPhoto ?? null).toBeNull();
});

test("a photograph with no capture date is offered for manual assignment, never guessed", async ({ page }) => {
  const token = await apiToken(page);
  const workshopId = await seedWorkshop(page, token, `Photo intake undated ${Date.now()}`);
  await signInWithToken(page, token);

  await openAndAdopt(page, workshopId);
  await page.goto(`/design-workshops/${workshopId}/photos`, { waitUntil: "domcontentloaded" });
  await expect(page.getByText(/This workshop records \d+ dated entr/)).toBeVisible({ timeout: 30_000 });

  await page.setInputFiles("#photo-intake-files", [
    { name: "DSC_0041.JPG", mimeType: "image/jpeg", buffer: exifJpeg("2026:02:14 10:22:33") },
    { name: "Screenshot 2026-02-14.png", mimeType: "image/png", buffer: BARE_PNG }
  ]);

  await expect(page.getByText("No date", { exact: true })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText(/No capture date in this file/)).toBeVisible();

  await expect(page.getByText(/1\s*need you/)).toBeVisible();

  await expect(page.locator("#photo-intake-destination-1 [data-searchable-select]")).toContainText(
    "Leave out"
  );

  await expect(page.getByRole("button", { name: /^Confirm 1 photograph$/ })).toBeVisible();
});

test("a photograph on an unlogged day is NOT auto-pointed at a single-valued field", async ({ page }) => {
  const token = await apiToken(page);
  const workshopId = await seedWorkshop(page, token, `Photo intake cover guard ${Date.now()}`);
  await signInWithToken(page, token);

  await openAndAdopt(page, workshopId);
  await page.goto(`/design-workshops/${workshopId}/photos`, { waitUntil: "domcontentloaded" });
  await expect(page.getByText(/This workshop records \d+ dated entr/)).toBeVisible({ timeout: 30_000 });

  await page.setInputFiles("#photo-intake-files", {
    name: "DSC_0090.JPG",
    mimeType: "image/jpeg",
    buffer: exifJpeg("2026:02:21 15:05:00")
  });

  await expect(page.getByRole("cell", { name: "21 Feb 2026, 15:05", exact: true })).toBeVisible({
    timeout: 30_000
  });

  await expect(page.getByText(/inside stage 1's Workshop details/)).toBeVisible();

  await expect(page.locator("#photo-intake-destination-0 [data-searchable-select]")).toContainText(
    "Leave out"
  );
  await expect(page.getByText(/1\s*need you/)).toBeVisible();

  await expect(page.getByRole("button", { name: /^Confirm 0 photographs$/ })).toBeDisabled();

  await page.locator("#photo-intake-destination-0 [data-searchable-select]").click();
  await page.getByRole("combobox", { name: /^Filter Where / }).fill("Workshop details");
  await page.getByRole("option", { name: /Workshop details/ }).first().click();
  await expect(page.getByRole("button", { name: /^Confirm 1 photograph$/ })).toBeEnabled();
});
