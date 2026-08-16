/**
 * The generator sheet and BOTH ways a code gets read back in, driven in a real browser.
 *
 * ── WHAT THIS SPEC CAN NOW DO THAT IT COULD NOT BEFORE ───────────────────────────────────────
 *
 * It used to say, in this comment, that it deliberately did not test decoding: `BarcodeDetector` is
 * absent from the Chromium Playwright ships, so the only way to make the app decode anything was to
 * stub the API — and a spec that stubs the decoder is a test of the stub, which would pass with the
 * scanner deleted.
 *
 * That is no longer true, and it is no longer true in the way that matters most. The app now carries
 * its own decoder (`lib/qrDecode.ts`, fed by `lib/qrImageDecode.ts`), so a browser with no
 * `BarcodeDetector` at all — which is every Windows laptop this client runs on, measured, and also
 * this runner — decodes a real PNG of a real symbol with nothing stubbed. The upload tests below
 * therefore exercise the whole chain end to end: the printed code off the sheet, encoded to a symbol,
 * drawn into a deliberately unkind photograph, written as a PNG, handed to the file input as a file,
 * decoded, parsed by the payload grammar, and resolved to a named prototype. Every link is real.
 *
 * WHAT IT STILL CANNOT DO is point a camera at a card: `--use-fake-device-for-media-stream` supplies
 * a synthetic video track with nothing in it. So the camera's LIFECYCLE is tested (opening the
 * device, attaching it, and releasing every track on the way out — the part this repository owns and
 * gets wrong) and its decoding is not, which is acceptable precisely because the camera and the
 * upload now run the identical decoder over the identical code path.
 *
 * ── AND THE THINGS IT ALWAYS COVERED ─────────────────────────────────────────────────────────
 *
 *   - the typed code doing the whole job on its own, which is what makes every other route optional;
 *   - the two refusals that carry the safety property — a mis-typed character, and a code that does
 *     not resolve to anything this caller may see;
 *   - the sheet measured IN MILLIMETRES. "Prints legibly at real size" is not an aesthetic claim: a
 *     QR module below about half a millimetre is at the resolution limit of a handset camera, so a
 *     sheet that silently rendered at 80% would produce cards that scan for the developer at 8cm and
 *     fail for everyone else. The pixel figures below are those millimetres at CSS's fixed 96dpi.
 */

import { expect, test, type Page, type APIRequestContext } from "@playwright/test";

import { greyPng, photograph, type Quad } from "./support/qrPhotograph";
import { signIn } from "./support/session";

import { type GreyPlane } from "@/lib/imageQuality";
import { encodeQr } from "@/lib/qrEncode";
import { encodeWorkshopCode, formatWorkshopCodeForPrint } from "@/lib/workshopCodes";

const API = process.env.E2E_API_URL ?? "http://localhost:8000";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const EMAIL = process.env.E2E_EMAIL ?? "admin2@example.org";

test.skip(!PASSWORD, "Set E2E_PASSWORD to run the signed-in specs.");

/**
 * Chromium's synthetic camera, needed by the lifecycle test at the foot of this file.
 *
 * File-level rather than inside that one describe because Playwright refuses `launchOptions` in a
 * group (it would force a second worker, and this project runs one on purpose — the specs share a
 * session). It changes nothing for the other tests here: none of them presses Scan. The Scan button
 * is now rendered on every browser (it is not gated on `BarcodeDetector` — see
 * `WorkshopCodeScanner`'s header for the measurement that removed that gate), so these flags are
 * what stop an accidental press from hanging on a permission prompt rather than what makes the
 * button appear.
 */
test.use({
  launchOptions: { args: ["--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream"] },
  permissions: ["camera"]
});

/** CSS defines an inch as exactly 96 px, so a millimetre is this many pixels, everywhere. */
const PX_PER_MM = 96 / 25.4;

/**
 * A throwaway workshop with three prototypes in it.
 *
 * Built rather than borrowed: the fully-populated fixture workshop has rows in seventeen
 * collections and NOT ONE prototype (its `prototypeStageLog` and `materialUsage` children were
 * seeded without their parents), so a spec pointed at it would assert against the empty state and
 * call it coverage. It is deleted in `afterAll`.
 */
const PROTOTYPES = [
  { prototypeCode: "PR-01", name: "Sambalpuri table runner", materials: ["cotton", "natural dye"] },
  { prototypeCode: "PR-02", name: "Ikat cushion cover", materials: ["cotton"] },
  { prototypeCode: "PR-03", name: "Tussar stole", materials: ["tussar silk"] }
];

let token = "";
let workshopId = "";

async function apiToken(request: APIRequestContext): Promise<string> {
  const response = await request.post(`${API}/api/auth/login`, { data: { email: EMAIL, password: PASSWORD } });
  expect(response.ok(), `sign-in for ${EMAIL} failed with ${response.status()}`).toBeTruthy();
  const body = (await response.json()) as { accessToken?: string; access_token?: string };
  return body.accessToken ?? body.access_token ?? "";
}

test.beforeAll(async ({ request }) => {
  if (!PASSWORD) return;
  token = await apiToken(request);
  const created = await request.post(`${API}/api/design-workshops`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { title: `Codes spec ${Date.now()}` }
  });
  expect(created.ok(), `create failed with ${created.status()}`).toBeTruthy();
  workshopId = ((await created.json()) as { id: string }).id;

  const saved = await request.put(`${API}/api/design-workshops/${workshopId}/stages/PROTOTYPE_DEVELOPMENT`, {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      entries: PROTOTYPES.map((row, index) => ({ entityKey: "prototype", ordinal: index, data: row })),
      replaceCollections: true
    }
  });
  expect(saved.ok(), `stage save failed with ${saved.status()}: ${await saved.text()}`).toBeTruthy();
});

test.afterAll(async ({ request }) => {
  if (!workshopId || !token) return;
  await request.delete(`${API}/api/design-workshops/${workshopId}`, { headers: { Authorization: `Bearer ${token}` } });
});

async function openCodes(page: Page) {
  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/codes`);
  await expect(page.getByRole("heading", { name: "Cards & tags" })).toBeVisible();
}

test("every prototype gets a tag, and the tag carries the code the payload module produces", async ({ page }) => {
  await openCodes(page);

  const tags = page.getByTestId("workshop-code-qr");
  await expect(tags).toHaveCount(PROTOTYPES.length);

  // The symbol and the printed text under it are the same code — the whole point of printing the
  // text is that it is the fallback for the symbol, so the two disagreeing would be silent.
  const code = await tags.first().getAttribute("data-code");
  expect(code).toMatch(/^DPW1:P:[0-9A-Z-]{8,64}:[0-9A-Z]{4}$/);
  await expect(page.getByTestId("workshop-code-text").first()).toHaveText(formatWorkshopCodeForPrint(code as string));

  // The name a human reads, so a designer holding the tag knows which object it belongs to, and
  // the two supporting lines. `materials` is a TAGS field — an array — and printing it goes through
  // `listValue`; `inputValue` silently yields "" for anything array-shaped, so this assertion is
  // what stops that line from quietly disappearing again.
  await expect(page.getByText("Sambalpuri table runner")).toBeVisible();
  await expect(page.getByText("PR-01")).toBeVisible();
  await expect(page.getByText("cotton, natural dye")).toBeVisible();
});

test("the sheet is laid out at the physical size it will be cut to", async ({ page }) => {
  await openCodes(page);
  await expect(page.getByTestId("workshop-code-qr").first()).toBeVisible();

  const sheet = page.locator(".wc-sheet").first();
  const sheetBox = await sheet.boundingBox();
  // A4 portrait. Anything else here means the sheet is being scaled, and a scaled sheet prints
  // cards that are not the size they were designed to scan at.
  expect(Math.round(sheetBox?.width ?? 0)).toBe(Math.round(210 * PX_PER_MM));

  const card = page.locator(".wc-card").first();
  const cardBox = await card.boundingBox();
  expect(Math.round(cardBox?.width ?? 0)).toBe(Math.round(63 * PX_PER_MM));
  expect(Math.round(cardBox?.height ?? 0)).toBe(Math.round(45 * PX_PER_MM));

  const qrBox = await page.getByTestId("workshop-code-qr").first().boundingBox();
  const sideMm = (qrBox?.width ?? 0) / PX_PER_MM;
  expect(Math.round(sideMm)).toBe(26);
  // The real requirement, stated as the thing that actually matters: a version 4 symbol (33
  // modules plus a 4-module quiet zone each side = 41) must still clear 0.5mm per module.
  expect(sideMm / 41).toBeGreaterThan(0.5);
});

test("both ways in are offered on a browser with no scanning API at all, and the typed code still works", async ({
  page
}) => {
  await openCodes(page);

  // THE MEASUREMENT THIS TEST EXISTS TO DEFEND. `BarcodeDetector` is absent from this runner, and
  // from Chrome and Edge on the Windows laptops this client is actually used on. Both controls must
  // be present ANYWAY, because the app carries its own decoder. Asserted against the real
  // `'BarcodeDetector' in window` rather than an assumption about the runner, so this stays honest
  // the day Playwright's Chromium ships the API.
  const nativeScanner = await page.evaluate(() => "BarcodeDetector" in window);
  expect(
    nativeScanner,
    "this runner has grown a native BarcodeDetector — the assertions below are still right, but the " +
      "'no native API' half of this test is no longer being exercised and needs another way to be"
  ).toBe(false);

  await expect(page.getByTestId("workshop-code-scan")).toBeVisible();
  await expect(page.getByTestId("workshop-code-upload")).toBeVisible();
  // The sentence the old build showed instead of the buttons. Its presence would mean the capability
  // gate is back, which is the exact defect that left field laptops with no way in but the keyboard.
  await expect(page.getByText(/cannot read a QR code from the camera/i)).toHaveCount(0);

  // The keyboard route is present too, on every device — the answer to a cracked lens.
  const box = page.getByLabel(/type the code printed under the QR/i);
  await expect(box).toBeVisible();

  const code = (await page.getByTestId("workshop-code-qr").first().getAttribute("data-code")) as string;
  await box.fill(formatWorkshopCodeForPrint(code).toLowerCase());
  await page.getByTestId("workshop-code-lookup").click();
  const outcome = page.getByTestId("workshop-code-outcome");
  await expect(outcome).toContainText("Found:");
  await expect(outcome).toContainText("Sambalpuri table runner");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The upload route, end to end, with nothing stubbed
 * ──────────────────────────────────────────────────────────────────────────── */

/** Hand a plane to the real file input as a real PNG file, the way choosing one from disk does. */
async function upload(page: Page, plane: GreyPlane, name: string): Promise<void> {
  await page.getByTestId("workshop-code-file").setInputFiles({
    name,
    mimeType: "image/png",
    buffer: greyPng(plane)
  });
}

test("a photograph of a card decodes from the upload button and names the prototype", async ({ page }) => {
  await openCodes(page);
  const code = (await page.getByTestId("workshop-code-qr").first().getAttribute("data-code")) as string;

  // The code taken off the sheet this page just rendered, turned back into a symbol and PHOTOGRAPHED
  // — skewed, lit from one side, blurred by a lens. This is the case the upload exists for: the card
  // was photographed in the morning and the artisan has gone home, or it arrived over WhatsApp.
  const symbol = encodeQr(code, "Q");
  const plane = photograph(
    symbol.matrix,
    900,
    680,
    [
      { x: 176, y: 104 },
      { x: 712, y: 158 },
      { x: 676, y: 604 },
      { x: 214, y: 540 }
    ],
    { gradient: true }
  );

  await upload(page, plane, "card-on-a-table.png");

  const outcome = page.getByTestId("workshop-code-outcome");
  // The exact record, not merely "something was read". A decoder that returned a near-miss string
  // would be refused by the grammar and never reach here, which is the property being asserted.
  await expect(outcome).toContainText("Found:", { timeout: 20_000 });
  await expect(outcome).toContainText("Sambalpuri table runner");
});

test("a code occupying a corner of a large photograph is found by looking closer at it", async ({ page }) => {
  // THE RESOLUTION LADDER IN `lib/qrImageDecode.ts`, exercised through the UI. A designer photographs
  // a card lying on a table from standing height and the code is a small part of the frame; the whole
  // picture has to be bounded before it is scanned, and the bounded copy throws away the very detail
  // the symbol is made of. The fix is to locate the symbol in the bounded copy and then re-cut THAT
  // rectangle out of the full-resolution original. Delete that second rung and this test goes red
  // while the one above stays green.
  await openCodes(page);
  const code = (await page.getByTestId("workshop-code-qr").first().getAttribute("data-code")) as string;

  const symbol = encodeQr(code, "Q");
  // 2400x1700 with the symbol 230px across — under 1.5% of the frame's area, and well past the point
  // where a single bounded pass can read it.
  const plane = photograph(symbol.matrix, 2400, 1700, [
    { x: 1780, y: 1170 },
    { x: 2010, y: 1186 },
    { x: 1996, y: 1414 },
    { x: 1768, y: 1398 }
  ]);

  await upload(page, plane, "workbench-from-standing-height.png");

  const outcome = page.getByTestId("workshop-code-outcome");
  await expect(outcome).toContainText("Found:", { timeout: 30_000 });
  await expect(outcome).toContainText("Sambalpuri table runner");
});

test("a picture with no code in it is refused with advice, and the control still works afterwards", async ({ page }) => {
  // THE SILENT NO-OP IS THE WORST OUTCOME THIS CONTROL CAN PRODUCE. A designer who uploads the wrong
  // photograph and sees nothing happen presses the button again with the same file, and then decides
  // the feature is broken. So the refusal must appear, must say what to do differently, and must not
  // leave the control wedged — the next attempt has to work.
  await openCodes(page);

  // Structure, not flat grey: diagonal banding and a soft blob, so this is a real negative rather
  // than a picture with nothing in it for the detector to consider.
  const width = 900;
  const height = 640;
  const data = new Uint8ClampedArray(width * height);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      data[y * width + x] = 120 + 90 * Math.sin((x + y) / 9) + 40 * Math.exp(-(((x - 560) ** 2 + (y - 210) ** 2) / 3600));
    }
  }
  await upload(page, { data, width, height }, "a-photograph-of-a-loom.png");

  const problem = page.getByTestId("workshop-code-problem");
  await expect(problem).toBeVisible({ timeout: 20_000 });
  // A sentence that names the next action. "Could not read the image" would send the designer back
  // to photograph the same thing the same way.
  await expect(problem).toContainText(/frame|focus|crop/i);
  expect((await problem.innerText()).length).toBeGreaterThan(40);
  // And it must not have claimed the code was damaged or foreign — this picture holds no code at all,
  // and those two refusals send somebody somewhere else entirely.
  await expect(problem).not.toContainText(/damaged|not one of ours/i);

  // NOT WEDGED. The same control, immediately afterwards, still resolves a real code.
  const code = (await page.getByTestId("workshop-code-qr").first().getAttribute("data-code")) as string;
  await page.getByLabel(/type the code printed under the QR/i).fill(code);
  await page.getByTestId("workshop-code-lookup").click();
  await expect(page.getByTestId("workshop-code-outcome")).toContainText("Sambalpuri table runner");
});

test("the repository-wide panel on /search offers both ways in too, and decodes an uploaded card", async ({ page }) => {
  // THE SECOND SURFACE, ASSERTED AT RUNTIME AND NOT ONLY IN THE IMPORT GRAPH. `qr-surfaces-unit.spec.ts`
  // proves that both reading surfaces mount the same control and that the control offers both inputs;
  // that is a claim about the source. This is the claim about the product — that a designer who opens
  // `/search` on a laptop with no camera can actually get a code in — and the two are worth having
  // separately, because a page can mount a control and still hide it behind a layout that never
  // renders, which no amount of reading the imports would catch.
  //
  // It uses an ARTISAN card rather than a prototype tag: `/search` resolves against the repository,
  // and a prototype belongs to one workshop's local draft, so the panel correctly refuses one. That
  // refusal is itself the assertion — it proves the upload reached the payload grammar and the
  // grammar identified the record TYPE, which is everything this surface is responsible for. A code
  // for a record this spec did not create cannot be asserted to resolve without inventing one.
  await signIn(page);
  await page.goto("/search");

  await expect(page.getByTestId("workshop-code-scan")).toBeVisible();
  await expect(page.getByTestId("workshop-code-upload")).toBeVisible();

  const tag = encodeWorkshopCode({ recordType: "prototype", id: "cmzz0000notarealrow00000z" });
  expect(tag.ok).toBe(true);
  if (!tag.ok) return;
  const symbol = encodeQr(tag.code, "Q");
  const plane = photograph(
    symbol.matrix,
    820,
    600,
    [
      { x: 168, y: 92 },
      { x: 648, y: 132 },
      { x: 614, y: 536 },
      { x: 202, y: 486 }
    ],
    { gradient: true }
  );
  await upload(page, plane, "a-tag-photographed-in-a-courtyard.png");

  // Decoded, parsed, and sent to the one screen that can answer it. Reaching this sentence at all
  // means every link from the PNG to the grammar worked.
  await expect(page.getByTestId("workshop-code-outcome")).toContainText(/prototype tag/i, { timeout: 20_000 });
  await expect(page.getByTestId("workshop-code-outcome")).toContainText(/open that workshop/i);
});

test("a QR that is not ours is refused as not ours, not as a damaged tag", async ({ page }) => {
  // A payment code, a shop barcode, a shipping label: the second commonest wrong picture after one
  // with no code in it, and the one where the two possible refusals lead somewhere completely
  // different. "This is not a workshop card" means stop trying; "the tag is damaged" means go and
  // photograph it again, which for somebody holding a payment QR is an instruction to waste an
  // afternoon. Reaching this refusal at all requires the decoder to READ a foreign symbol, in byte
  // mode, which our own encoder cannot even produce.
  await openCodes(page);

  const symbol = encodeQr("HTTPS://EXAMPLE.ORG/PAY/12345", "Q");
  const plane = photograph(symbol.matrix, 760, 560, [
    { x: 150, y: 96 },
    { x: 610, y: 96 },
    { x: 610, y: 500 },
    { x: 150, y: 500 }
  ]);
  await upload(page, plane, "somebody-elses-code.png");

  const outcome = page.getByTestId("workshop-code-outcome");
  await expect(outcome).toContainText(/not a workshop card or tag/i, { timeout: 20_000 });
  await expect(outcome).not.toContainText(/damaged/i);
});

test("one wrong character is refused rather than resolved, and an unknown code gives nothing away", async ({ page }) => {
  await openCodes(page);
  const box = page.getByLabel(/type the code printed under the QR/i);
  const outcome = page.getByTestId("workshop-code-outcome");

  // A single character of the identifier mistyped. The mutated id is still a well-formed one, so
  // nothing but the check stands between this and two days of work on the wrong prototype.
  const code = (await page.getByTestId("workshop-code-qr").first().getAttribute("data-code")) as string;
  const parts = code.split(":");
  const bumped = parts[2].slice(0, -1) + (parts[2].endsWith("A") ? "B" : "A");
  await box.fill(`${parts[0]}:${parts[1]}:${bumped}:${parts[3]}`);
  await page.getByTestId("workshop-code-lookup").click();
  await expect(outcome).toContainText(/does not check out/i);

  // A perfectly valid code for a prototype that is not in this workshop. It must not distinguish
  // "no such row" from "a row you may not see" — the API answers 404 rather than 403 for exactly
  // that reason, and a scanner that leaked the difference would undo it one card at a time.
  const elsewhere = encodeWorkshopCode({ recordType: "prototype", id: "cmzz0000notarealrow00000z" });
  expect(elsewhere.ok).toBe(true);
  if (!elsewhere.ok) return;
  await box.fill(elsewhere.code);
  await page.getByTestId("workshop-code-lookup").click();
  await expect(outcome).toContainText(/No prototype in this workshop matches that tag/i);
  await expect(outcome).not.toContainText(/permission|not allowed|forbidden|exists/i);

  // Something that is not one of ours at all must not be reported as a damaged tag.
  await box.fill("https://example.org/prototypes/abc");
  await page.getByTestId("workshop-code-lookup").click();
  await expect(outcome).toContainText(/not a workshop card or tag/i);
});

/**
 * The camera LIFECYCLE — and precisely what this does and does not claim.
 *
 * It does NOT test decoding: `--use-fake-device-for-media-stream` supplies a synthetic video track
 * with no QR code anywhere in it. What is under test is the code this repository actually owns and
 * gets wrong — opening the device, attaching it to the element, and, the requirement that costs a
 * field handset its battery when it is missed, releasing every track on the way out. The stream is
 * captured at `getUserMedia` before the app ever sees it, so the assertion is against the real
 * `MediaStreamTrack`s Chromium handed over rather than against a spy on the component.
 *
 * THE `BarcodeDetector` STUB THIS TEST USED TO INSTALL HAS BEEN REMOVED, and its absence is now part
 * of what is being asserted. It existed for one reason: the Scan button did not render at all unless
 * the API was present, so the lifecycle could not be reached without faking it. The button is no
 * longer gated on anything (see `WorkshopCodeScanner`'s header), so the stub is not merely
 * unnecessary — keeping it would hide a regression, because the gate could come back and this test
 * would go on passing by installing the very API whose absence is the field condition.
 *
 * What runs against the synthetic frames now is the app's OWN decoder, which finds no symbol in them
 * and quietly asks for the next frame. That is the correct behaviour for an empty frame and it is
 * incidentally proved here: an implementation that reported a problem per empty frame would bury the
 * preview under a wall of amber before this test finished.
 */
test.describe("camera lifecycle", () => {
  test("the camera is opened, attached, and every track is stopped on the way out", async ({ page, context }) => {
    await context.addInitScript(() => {
      // Recorded before the app can touch them, so what is asserted below is what the browser
      // really handed over.
      const opened: MediaStream[] = [];
      (window as unknown as { __openedStreams: MediaStream[] }).__openedStreams = opened;
      const real = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
      navigator.mediaDevices.getUserMedia = async (constraints?: MediaStreamConstraints) => {
        const stream = await real(constraints);
        opened.push(stream);
        return stream;
      };
    });

    await openCodes(page);
    await page.getByTestId("workshop-code-scan").click();

    await expect(page.getByTestId("workshop-code-preview")).toBeVisible();
    await expect(page.getByRole("button", { name: /stop the camera/i })).toBeVisible();
    // The element is actually carrying the stream — the bug this catches is a preview that mounts
    // after the stream is attached, which opens the camera and then shows nothing.
    expect(await page.getByTestId("workshop-code-preview").evaluate((el) => (el as HTMLVideoElement).srcObject !== null)).toBe(
      true
    );
    expect(
      await page.evaluate(() =>
        (window as unknown as { __openedStreams: MediaStream[] }).__openedStreams.flatMap((s) =>
          s.getTracks().map((t) => t.readyState)
        )
      )
    ).toContain("live");

    // Several frames' worth of the app's own decoder finding nothing in a synthetic video track, and
    // NOT ONE complaint about it. An empty frame is the normal state of a camera pointed at a room,
    // and a control that reported each one would bury its own preview in amber within a second.
    await page.waitForTimeout(1200);
    await expect(page.getByTestId("workshop-code-problem")).toHaveCount(0);
    await expect(page.getByTestId("workshop-code-preview")).toBeVisible();

    // Leaving the page is the path that matters: a designer scans, is interrupted, and navigates
    // away. React unmounts the component and nothing else will ever come back for the camera.
    await page.getByRole("link", { name: "Stages" }).click();
    await expect(page.getByTestId("workshop-code-preview")).toHaveCount(0);
    const states = await page.evaluate(() =>
      (window as unknown as { __openedStreams: MediaStream[] }).__openedStreams.flatMap((s) =>
        s.getTracks().map((t) => t.readyState)
      )
    );
    expect(states.length).toBeGreaterThan(0);
    expect(states.every((state) => state === "ended")).toBe(true);
  });
});

test("a roster entry with no artisan record behind it prints a reason, not a blank card", async ({ page }) => {
  await openCodes(page);
  await page.getByRole("button", { name: "Artisan cards" }).click();

  // This workshop's roster is empty, so the honest answer is the empty state — and it must say
  // where the artisans come from rather than leaving a blank panel.
  await expect(page.getByText(/No artisans are on this workshop's roster yet/i)).toBeVisible();
  await expect(page.getByTestId("workshop-code-qr")).toHaveCount(0);
});
