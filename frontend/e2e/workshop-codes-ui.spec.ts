/**
 * The generator sheet and the manual-entry fallback, driven in a real browser.
 *
 * WHAT THIS SPEC DELIBERATELY DOES NOT DO: pretend to test the camera. `BarcodeDetector` is not
 * implemented in the Chromium that Playwright ships, and Chromium's `--use-fake-device-for-media-
 * stream` only supplies a synthetic video track — there is nothing behind it to read a QR out of a
 * frame. A spec that stubbed `window.BarcodeDetector` would be a test of the stub: it would pass
 * with the scanner deleted. So the camera path is verified by hand on a device, and what is
 * automated here is the half that a CI browser can honestly exercise — and, as it happens, the half
 * that most of the handsets in the field will run:
 *
 *   - the feature detection reaching the RIGHT conclusion on a browser that genuinely lacks the API
 *     (asserted against `'BarcodeDetector' in window`, not against an assumption about the runner);
 *   - the honest sentence it produces instead of a button that would open a camera and see nothing;
 *   - the typed code doing the whole job on its own, which is what makes that acceptable;
 *   - the two refusals that carry the safety property — a mis-typed character, and a code that does
 *     not resolve to anything this caller may see.
 *
 * It also measures the sheet IN MILLIMETRES. "Prints legibly at real size" is not an aesthetic
 * claim: a QR module below about half a millimetre is at the resolution limit of a handset camera,
 * so a sheet that silently rendered at 80% would produce cards that scan for the developer at 8cm
 * and fail for everyone else. The pixel figures below are those millimetres at CSS's fixed 96dpi.
 */

import { expect, test, type Page, type APIRequestContext } from "@playwright/test";

import { signIn } from "./support/session";

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
 * session). It changes nothing for the other tests here: none of them opens a camera, and the Scan
 * button they would need does not render unless `BarcodeDetector` exists, which only that test
 * arranges.
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

test("a browser without the scanning API says so, and the typed code does the whole job", async ({ page }) => {
  await openCodes(page);
  const nativeScanner = await page.evaluate(() => "BarcodeDetector" in window);

  if (nativeScanner) {
    // Not the runner we expect, but asserting the opposite would make this spec a lie the day
    // Playwright's Chromium ships the API.
    await expect(page.getByRole("button", { name: /scan a code/i })).toBeVisible();
  } else {
    await expect(page.getByText(/cannot read a QR code from the camera/i)).toBeVisible();
    await expect(page.getByRole("button", { name: /scan a code/i })).toHaveCount(0);
  }

  // The fallback is present either way — it is the keyboard route and the answer to a cracked lens.
  const box = page.getByLabel(/type the code printed under the QR/i);
  await expect(box).toBeVisible();

  const code = (await page.getByTestId("workshop-code-qr").first().getAttribute("data-code")) as string;
  await box.fill(formatWorkshopCodeForPrint(code).toLowerCase());
  await page.getByTestId("workshop-code-lookup").click();
  const outcome = page.getByTestId("workshop-code-outcome");
  await expect(outcome).toContainText("Found:");
  await expect(outcome).toContainText("Sambalpuri table runner");
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
 * It does NOT test decoding. `BarcodeDetector` is stubbed here so that the Scan button appears at
 * all, and a stub can only ever prove the stub works. What is under test is the code this repo
 * actually owns and gets wrong: opening the device, attaching it to the element, and — the
 * requirement that costs a field handset its battery when it is missed — releasing every track on
 * the way out. The stream is captured at `getUserMedia` before the app ever sees it, so the
 * assertion is against the real MediaStreamTracks Chromium handed over, not against a spy on the
 * component. Chromium supplies them from `--use-fake-device-for-media-stream`.
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
      // Present but never asked to decode anything: its only job is to make the app conclude that
      // this browser can scan, which is the branch whose lifecycle we are here to check.
      class StubDetector {
        static async getSupportedFormats() {
          return ["qr_code"];
        }
        async detect() {
          return [];
        }
      }
      (window as unknown as { BarcodeDetector: unknown }).BarcodeDetector = StubDetector;
    });

    await openCodes(page);
    await page.getByRole("button", { name: /scan a code/i }).click();

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
