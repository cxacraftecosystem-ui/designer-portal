import { expect, test, type Page } from "@playwright/test";

/**
 * Bulk photo import — the confirm flow, in a real browser.
 *
 * WHAT ONLY A BROWSER TEST CAN ANSWER HERE. `e2e/photo-intake-ranking.spec.ts` pins the ranking by
 * value and needs no page. This spec exists for the one property the pure module cannot have an
 * opinion about: **that nothing is written until the designer says so.** "Propose, never commit" is a
 * claim about the order of effects across a file picker, a table and a button, and the way to be sure
 * of it is to read the device's storage before Confirm is pressed and find it empty, then read it
 * again afterwards and find exactly one reference.
 *
 * It also checks the evidence reaches the screen. A proposal a designer cannot check is a proposal
 * they will rubber-stamp, so the sentence naming the matched row is as much a part of the feature as
 * the match itself.
 *
 * THE FIXTURE IS A REAL JPEG WITH A REAL EXIF SEGMENT, built byte by byte below. Using a stub with a
 * fake timestamp would test the table and skip the only hard part — that the capture clock is
 * actually read off the file, unshifted, by the same reader the page uses.
 *
 * NO UPLOAD HAPPENS IN THIS SPEC, which is why it is immune to the known local-stack problem where
 * MinIO presigns `minio:9000` and a host browser cannot resolve it. Confirm writes the bytes to the
 * draft store and the field to IndexedDB; the upload is the existing sync pass's job and is tested
 * where that lives.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

/**
 * Longer than the 90s default because the FIRST test to reach a route pays the dev server's compile
 * for it, and this spec opens two routes that no earlier spec touches. Against a built server it
 * finishes in seconds; the allowance costs nothing when it is not needed.
 */
test.describe.configure({ timeout: 240_000 });

const STAGE_1 = "WORKSHOP_SETUP";
const STAGE_13 = "PROTOTYPE_DEVELOPMENT";

/** The workshop ran 12–26 Feb 2026, with one prototype stage log on the 14th. */
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

/**
 * Sign in by planting the token the app reads, rather than driving the login form.
 *
 * ONE login per test instead of two. Driving the form and then calling the API for a token logs the
 * same account in twice within a second, which is enough to trip the login throttle — the form then
 * re-renders `/login` with no error the spec can see, which reads as "the credentials are wrong" and
 * is not. The form has its own specs; nothing here is testing it.
 *
 * `addInitScript`, NOT `goto` + `evaluate`. Writing the token after a page has already mounted loses
 * it: `AuthProvider.refreshMe` fires its `/me` probe on load, that probe is anonymous and comes back
 * 401, and `refreshMe` clears the token on 401 — including the one written a few milliseconds
 * earlier. The symptom is the app bouncing to `/login` with a valid token in hand, which looks like
 * an auth bug and is a write-ordering one. An init script runs before any page script on every
 * navigation, so the token is simply there from the start.
 */
async function signInWithToken(page: Page, token: string) {
  await page.addInitScript((value) => {
    window.localStorage.setItem("field_repo_token", value);
  }, token);
}

/**
 * A workshop with the two anchors this feature is about: stage 1's window, and one dated log row on
 * stage 13 inside it. The photograph below is taken on the log's day, so the correct proposal is the
 * NARROW one — which is exactly the ranking rule worth proving end to end.
 */
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
          // `workshopSetup`, the key `single("workshopSetup", …)` declares — NOT "workshop". The
          // server accepts a PUT naming an entity it does not know, reports it in `droppedKeys` and
          // stores nothing, so getting this wrong looks like a successful seed that silently wrote
          // no dates at all.
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
  // A 200 is NOT proof the seed landed. `save_stage` accepts entities and fields its registry does
  // not know, stores nothing for them and names them in `droppedKeys` — so a typo in an entity key
  // produced a green seed, a workshop with no dates, and a failure three assertions later that
  // pointed at the intake instead of at this request. Assert the drop list is empty.
  expect((await stage1.json()).droppedKeys ?? [], "stage 1 seed dropped nothing").toEqual([]);

  const stage13 = await page.request.put(`${API}/api/design-workshops/${id}/stages/${STAGE_13}`, {
    headers,
    data: {
      entries: [
        {
          entityKey: "prototypeStageLog",
          // `stageName` is the entity's `label_field`, so it is what names the row in the evidence
          // sentence — the fixture would still match without it, but the sentence a designer reads
          // is the product here and the test should exercise the whole of it.
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

/* ────────────────────────────────────────────────────────────────────────────
 * A real JPEG carrying a real EXIF APP1 segment
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Build a JPEG whose EXIF `DateTimeOriginal` is exactly `stamp` ("2026:02:14 10:22:33").
 *
 * Written out rather than pulled from a fixture file so the timestamp under test is visible in the
 * spec: a binary fixture would make the one number this whole feature turns on invisible to the next
 * reader. Big-endian ("MM") TIFF, IFD0 carrying only the Exif SubIFD pointer, and the SubIFD carrying
 * DateTimeOriginal — the minimum a camera writes and the minimum exifr needs.
 */
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
  tiff.writeUInt16BE(0x8769, p); p += 2; // ExifIFDPointer
  tiff.writeUInt16BE(4, p); p += 2;      // LONG
  tiff.writeUInt32BE(1, p); p += 4;
  tiff.writeUInt32BE(exifIfdOffset, p); p += 4;
  tiff.writeUInt32BE(0, p);

  p = exifIfdOffset;
  tiff.writeUInt16BE(1, p); p += 2;
  tiff.writeUInt16BE(0x9003, p); p += 2; // DateTimeOriginal
  tiff.writeUInt16BE(2, p); p += 2;      // ASCII
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

/** A PNG with no EXIF at all — the screenshot / scan / WhatsApp case. */
const BARE_PNG = Buffer.from(
  "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000a49444154789c6360000002000100" +
    "05fe02fea7b5a3f80000000049454e44ae426082",
  "hex"
);

/**
 * Every `dwlocal:` reference stored anywhere in this workshop's draft, read straight out of
 * IndexedDB.
 *
 * The point of asking storage rather than the screen: "nothing has been committed" is a claim about
 * what is on the device, and a table that merely has not rendered a tile yet would satisfy a
 * screen-only assertion while the write had already happened.
 */
/**
 * This workshop's draft, straight out of IndexedDB.
 *
 * `getAll()` and then a match on either id, NOT `.get(workshopId)`: the store's primary key is the
 * draft's own `localId`, and a workshop created on the server is found by its `remoteId` instead —
 * which is the id in the URL and the one this spec holds. Reading by primary key returns undefined
 * for every server-created workshop, which looks exactly like "nothing was written".
 */
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
    // The app navigates once on its own after hydration (the auth check settles and the route
    // re-renders), and an `evaluate` awaiting IndexedDB across that teardown comes back as
    // "Resulting promise was garbage collected" rather than as a result. That is a race with the
    // page, not an answer about storage, so it is reported as "nothing read yet" and the caller's
    // poll asks again. Every assertion on this helper is a poll or runs on a settled page.
    return null;
  }
}

/**
 * Open the workshop and wait until its dates are actually on this device.
 *
 * The precondition for the intake is not "the stage index has painted" — the stage titles come from
 * the registry and are on screen long before the server's answer has been folded into the draft. It
 * is "the draft holds stage 1's start date", so that is what is polled. Waiting on a UI string
 * instead made this spec fail for whichever of its two tests happened to run first, on a dev server
 * still compiling the route, with an error that pointed at the wrong thing entirely.
 */
async function openAndAdopt(page: Page, workshopId: string) {
  await page.goto(`/design-workshops/${workshopId}`, { waitUntil: "domcontentloaded" });

  // The header prints the workshop's window only once the server's detail has been folded into the
  // local draft, so this string IS the adoption signal — and waiting on the DOM rides out the dev
  // server's Fast Refresh churn, which destroys the JS execution context repeatedly in the first
  // seconds and makes an `evaluate`-based poll read as "nothing there yet" every time.
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

/** Every `dwlocal:` reference stored anywhere in this workshop's draft. */
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

/* ────────────────────────────────────────────────────────────────────────────
 * The flow
 * ──────────────────────────────────────────────────────────────────────────── */

test("a dated photograph is proposed with its evidence, and NOTHING is written until Confirm", async ({ page }) => {
  const token = await apiToken(page);
  const workshopId = await seedWorkshop(page, token, `Photo intake ${Date.now()}`);
  await signInWithToken(page, token);

  // Open the workshop once so this device holds its dates. The intake matches against the LOCAL
  // draft, which is what makes the whole page work with no connection.
  await openAndAdopt(page, workshopId);

  await page.goto(`/design-workshops/${workshopId}/photos`, { waitUntil: "domcontentloaded" });
  await expect(page.getByRole("heading", { name: "Bulk photo import" })).toBeVisible();

  // The assumption is stated before anything is read, not after something looks wrong.
  await expect(page.getByText("Asia/Kolkata")).toBeVisible();

  // Wait for the workshop's dates to have been folded into this device's draft before picking any
  // files. The stage titles above come from the REGISTRY and are on screen well before the server's
  // answer has been adopted, so they are not a signal that the anchors exist yet.
  await expect(page.getByText(/This workshop records \d+ dated entr/)).toBeVisible({ timeout: 30_000 });

  await page.setInputFiles("#photo-intake-files", {
    name: "DSC_0041.JPG",
    mimeType: "image/jpeg",
    buffer: exifJpeg("2026:02:14 10:22:33")
  });

  // The clock was read off the file, unshifted: 10:22 stayed 10:22 and the day stayed the 14th.
  await expect(page.getByRole("cell", { name: "14 Feb 2026, 10:22", exact: true })).toBeVisible({
    timeout: 30_000
  });

  // The evidence names the row it matched — the narrow stage 13 log, not the whole-workshop window.
  await expect(page.getByText(/stage 13's Stage logs row “Warping the loom”, date 14 Feb 2026/)).toBeVisible();

  /*
    The destination defaulted to that same stage 13 row.

    READ OFF THE TRIGGER'S TEXT, NOT A `value`, because this control is no longer a native
    `<select>`: it is the app's themed picker, which the photo page moved to once `SelectOption`
    grew a `group` field and the two `<optgroup>`s could be expressed (it was the last long list in
    the application with no filter box). A themed trigger is a `<button>` whose text is the chosen
    option's label, so what is asserted is what a designer reads — and the id now belongs to the
    cell's wrapper, since `Dropdown` deliberately takes none.
  */
  const destination = page.locator("#photo-intake-destination-0 [data-searchable-select]");
  await expect(destination).toContainText("Warping the loom");

  // PROPOSE, NEVER COMMIT: the proposal is on screen and the device holds nothing.
  expect(await storedMediaRefs(page, workshopId), "nothing is written before Confirm").toEqual([]);

  await page.getByRole("button", { name: /^Confirm 1 photograph$/ }).click();
  await expect(page.getByText(/1 photograph attached on this device/)).toBeVisible({ timeout: 30_000 });

  // …and now exactly one reference exists, on the row that was proposed.
  await expect
    .poll(async () => (await storedMediaRefs(page, workshopId)).length, { timeout: 15_000 })
    .toBe(1);

  const draft = await readDraft(page, workshopId);
  const rows = draft?.stages?.PROTOTYPE_DEVELOPMENT?.collections?.prototypeStageLog ?? [];
  const row = rows.find((item: any) => (item._clientKey ?? item._entryId) === "log-14-feb");

  // `logPhotos` is the IMAGE_LIST on that entity, so the reference is APPENDED to an array — not
  // written over whatever the field already held.
  expect(Array.isArray(row?.logPhotos), "logPhotos is a list").toBeTruthy();
  expect(
    JSON.stringify(row?.logPhotos),
    "the reference landed on the stage 13 log row that was proposed"
  ).toContain("dwlocal:");

  // And nowhere else: the whole-workshop window was the second proposal and must not have been
  // written too.
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

  // The count says how many need a human — a bulk import that hid this would be worse than none.
  await expect(page.getByText(/1\s*need you/)).toBeVisible();

  // The undated one is NOT pre-selected, and the filename saying "2026-02-14" did not become a date.
  // "Leave out" IS the empty choice's own row — it is a real option with a real sentence, not a
  // placeholder — so the honest assertion is that the trigger reads it.
  await expect(page.locator("#photo-intake-destination-1 [data-searchable-select]")).toContainText(
    "Leave out"
  );

  // Only the dated one is offered for attachment.
  await expect(page.getByRole("button", { name: /^Confirm 1 photograph$/ })).toBeVisible();
});

test("a photograph on an unlogged day is NOT auto-pointed at a single-valued field", async ({ page }) => {
  const token = await apiToken(page);
  const workshopId = await seedWorkshop(page, token, `Photo intake cover guard ${Date.now()}`);
  await signInWithToken(page, token);

  await openAndAdopt(page, workshopId);
  await page.goto(`/design-workshops/${workshopId}/photos`, { waitUntil: "domcontentloaded" });
  await expect(page.getByText(/This workshop records \d+ dated entr/)).toBeVisible({ timeout: 30_000 });

  // 21 Feb is inside the 12–26 Feb window but on no logged day, so the best anchor is stage 1's
  // whole-workshop span — whose only image field is `coverPhoto`, a SINGLE-valued box.
  await page.setInputFiles("#photo-intake-files", {
    name: "DSC_0090.JPG",
    mimeType: "image/jpeg",
    buffer: exifJpeg("2026:02:21 15:05:00")
  });

  await expect(page.getByRole("cell", { name: "21 Feb 2026, 15:05", exact: true })).toBeVisible({
    timeout: 30_000
  });

  // The proposal is real and its evidence is on screen…
  await expect(page.getByText(/inside stage 1's Workshop details/)).toBeVisible();

  // …but nothing is auto-selected, because writing two hundred photographs one at a time into a box
  // that holds one would attach exactly one and destroy the rest without a word.
  await expect(page.locator("#photo-intake-destination-0 [data-searchable-select]")).toContainText(
    "Leave out"
  );
  await expect(page.getByText(/1\s*need you/)).toBeVisible();

  // Confirm is therefore not offered for it.
  await expect(page.getByRole("button", { name: /^Confirm 0 photographs$/ })).toBeDisabled();

  /*
    The designer can still choose the cover deliberately — the refusal is only about the default.

    Driven the way every other themed picker is driven in this suite: open the trigger, narrow with
    the panel's filter box, click the row. The filter step is not decoration — this list is every
    place in the workshop a photograph can go, which is past `RENDER_CAP`, so the row wanted is not
    necessarily drawn until it is searched for. That is the whole reason this control gained a filter
    box.
  */
  await page.locator("#photo-intake-destination-0 [data-searchable-select]").click();
  await page.getByRole("combobox", { name: /^Filter Where / }).fill("Workshop details");
  await page.getByRole("option", { name: /Workshop details/ }).first().click();
  await expect(page.getByRole("button", { name: /^Confirm 1 photograph$/ })).toBeEnabled();
});
