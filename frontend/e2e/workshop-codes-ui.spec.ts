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

const OBJECT_STORE_MAP = process.env.E2E_OBJECT_STORE_MAP ?? "";

test.use({
  launchOptions: {
    args: [
      "--use-fake-device-for-media-stream",
      "--use-fake-ui-for-media-stream",
      ...(OBJECT_STORE_MAP ? [`--host-resolver-rules=MAP ${OBJECT_STORE_MAP}`] : [])
    ]
  },
  permissions: ["camera"]
});

const PX_PER_MM = 96 / 25.4;

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

  const code = await tags.first().getAttribute("data-code");
  expect(code).toMatch(/^DPW1:P:[0-9A-Z-]{8,64}:[0-9A-Z]{4}$/);
  await expect(page.getByTestId("workshop-code-text").first()).toHaveText(formatWorkshopCodeForPrint(code as string));

  await expect(page.getByText("Sambalpuri table runner")).toBeVisible();
  await expect(page.getByText("PR-01")).toBeVisible();
  await expect(page.getByText("cotton, natural dye")).toBeVisible();
});

test("the sheet is laid out at the physical size it will be cut to", async ({ page }) => {
  await openCodes(page);
  await expect(page.getByTestId("workshop-code-qr").first()).toBeVisible();

  const sheet = page.locator(".wc-sheet").first();
  const sheetBox = await sheet.boundingBox();
  expect(Math.round(sheetBox?.width ?? 0)).toBe(Math.round(210 * PX_PER_MM));

  const card = page.locator(".wc-card").first();
  const cardBox = await card.boundingBox();
  expect(Math.round(cardBox?.width ?? 0)).toBe(Math.round(63 * PX_PER_MM));
  expect(Math.round(cardBox?.height ?? 0)).toBe(Math.round(45 * PX_PER_MM));

  const qrBox = await page.getByTestId("workshop-code-qr").first().boundingBox();
  const sideMm = (qrBox?.width ?? 0) / PX_PER_MM;
  expect(Math.round(sideMm)).toBe(26);
  expect(sideMm / 41).toBeGreaterThan(0.5);
});

test("both ways in are offered on a browser with no scanning API at all, and the typed code still works", async ({
  page
}) => {
  await openCodes(page);

  const nativeScanner = await page.evaluate(() => "BarcodeDetector" in window);
  expect(
    nativeScanner,
    "this runner has grown a native BarcodeDetector — the assertions below are still right, but the " +
      "'no native API' half of this test is no longer being exercised and needs another way to be"
  ).toBe(false);

  await expect(page.getByTestId("workshop-code-scan")).toBeVisible();
  await expect(page.getByTestId("workshop-code-upload")).toBeVisible();
  await expect(page.getByText(/cannot read a QR code from the camera/i)).toHaveCount(0);

  const box = page.getByLabel(/type the code printed under the QR/i);
  await expect(box).toBeVisible();

  const code = (await page.getByTestId("workshop-code-qr").first().getAttribute("data-code")) as string;
  await box.fill(formatWorkshopCodeForPrint(code).toLowerCase());
  await page.getByTestId("workshop-code-lookup").click();
  const outcome = page.getByTestId("workshop-code-outcome");
  await expect(outcome).toContainText("Found:");
  await expect(outcome).toContainText("Sambalpuri table runner");
});

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
  await expect(outcome).toContainText("Found:", { timeout: 20_000 });
  await expect(outcome).toContainText("Sambalpuri table runner");
});

test("a code occupying a corner of a large photograph is found by looking closer at it", async ({ page }) => {
  await openCodes(page);
  const code = (await page.getByTestId("workshop-code-qr").first().getAttribute("data-code")) as string;

  const symbol = encodeQr(code, "Q");
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
  await openCodes(page);

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
  await expect(problem).toContainText(/frame|focus|crop/i);
  expect((await problem.innerText()).length).toBeGreaterThan(40);
  await expect(problem).not.toContainText(/damaged|not one of ours/i);

  const code = (await page.getByTestId("workshop-code-qr").first().getAttribute("data-code")) as string;
  await page.getByLabel(/type the code printed under the QR/i).fill(code);
  await page.getByTestId("workshop-code-lookup").click();
  await expect(page.getByTestId("workshop-code-outcome")).toContainText("Sambalpuri table runner");
});

test("the repository-wide panel on /search offers both ways in too, and decodes an uploaded card", async ({ page }) => {
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

  const answer = page.locator('[data-scan-outcome="POINTER"]');
  await expect(answer).toContainText(/prototype tag/i, { timeout: 20_000 });
  await expect(answer).toContainText(/Cards & tags/i);
});

test("a QR that is not ours is refused as not ours, not as a damaged tag", async ({ page }) => {
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

  const code = (await page.getByTestId("workshop-code-qr").first().getAttribute("data-code")) as string;
  const parts = code.split(":");
  const bumped = parts[2].slice(0, -1) + (parts[2].endsWith("A") ? "B" : "A");
  await box.fill(`${parts[0]}:${parts[1]}:${bumped}:${parts[3]}`);
  await page.getByTestId("workshop-code-lookup").click();
  await expect(outcome).toContainText(/does not check out/i);

  const elsewhere = encodeWorkshopCode({ recordType: "prototype", id: "cmzz0000notarealrow00000z" });
  expect(elsewhere.ok).toBe(true);
  if (!elsewhere.ok) return;
  await box.fill(elsewhere.code);
  await page.getByTestId("workshop-code-lookup").click();
  await expect(outcome).toContainText(/No prototype in this workshop matches that tag/i);
  await expect(outcome).not.toContainText(/permission|not allowed|forbidden|exists/i);

  await box.fill("https://example.org/prototypes/abc");
  await page.getByTestId("workshop-code-lookup").click();
  await expect(outcome).toContainText(/not a workshop card or tag/i);
});

test.describe("camera lifecycle", () => {
  test("the camera is opened, attached, and every track is stopped on the way out", async ({ page, context }) => {
    await context.addInitScript(() => {
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

    const preview = page.getByTestId("workshop-code-preview");
    const refusal = page.getByTestId("workshop-code-problem");
    await expect
      .poll(
        async () =>
          (await preview.isVisible())
            ? "the preview is visible"
            : (await refusal.count())
              ? `the camera was refused: ${(await refusal.innerText()).trim()}`
              : "neither a preview nor a reason yet",
        { message: "the camera opened and the preview element unhid", timeout: 20_000 }
      )
      .toBe("the preview is visible");
    await expect(page.getByRole("button", { name: /stop the camera/i })).toBeVisible();
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

    await page.waitForTimeout(1200);
    await expect(page.getByTestId("workshop-code-problem")).toHaveCount(0);
    await expect(page.getByTestId("workshop-code-preview")).toBeVisible();

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

  await expect(page.getByText(/No artisans are on this workshop's roster yet/i)).toBeVisible();
  await expect(page.getByTestId("workshop-code-qr")).toHaveCount(0);
});
