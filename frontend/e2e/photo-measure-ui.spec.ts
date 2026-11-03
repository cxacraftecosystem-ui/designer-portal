import { expect, test, type Page } from "@playwright/test";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

const STAGE_KEY = "PROTOTYPE_DEVELOPMENT";

const MEDIA_ID = "photo-measure-fixture-1";

const IMAGE_WIDTH = 800;
const IMAGE_HEIGHT = 600;

const REFERENCE_FROM = { x: 100, y: 500 };
const REFERENCE_TO = { x: 300, y: 500 };
const OBJECT_FROM = { x: 200, y: 200 };
const OBJECT_TO = { x: 600, y: 200 };

function fixtureSvg(): string {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${IMAGE_WIDTH}" height="${IMAGE_HEIGHT}" viewBox="0 0 ${IMAGE_WIDTH} ${IMAGE_HEIGHT}">
    <rect width="${IMAGE_WIDTH}" height="${IMAGE_HEIGHT}" fill="#f2efe6"/>
    <rect x="${OBJECT_FROM.x}" y="${OBJECT_FROM.y - 60}" width="${OBJECT_TO.x - OBJECT_FROM.x}" height="120" fill="#8a6b4f"/>
    <rect x="${REFERENCE_FROM.x}" y="${REFERENCE_FROM.y - 12}" width="${REFERENCE_TO.x - REFERENCE_FROM.x}" height="24" fill="#1e1b2e"/>
  </svg>`;
  return `data:image/svg+xml;base64,${Buffer.from(svg, "utf8").toString("base64")}`;
}

async function apiToken(page: Page): Promise<string> {
  const res = await page.request.post(`${API}/api/auth/login`, { data: { email: EMAIL, password: PASSWORD } });
  expect(res.ok(), `sign-in for the API fixture: ${await res.text()}`).toBeTruthy();
  return (await res.json()).accessToken as string;
}

async function signIn(page: Page, token: string) {
  await page.addInitScript((value) => window.localStorage.setItem("field_repo_token", value), token);
}

async function seedWorkshop(page: Page, token: string): Promise<string> {
  const headers = { Authorization: `Bearer ${token}` };
  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers,
    data: { title: `Photo measure ${Date.now()}` }
  });
  expect(created.ok(), `create a design workshop: ${await created.text()}`).toBeTruthy();
  const id = (await created.json()).id as string;

  const saved = await page.request.put(`${API}/api/design-workshops/${id}/stages/${STAGE_KEY}`, {
    headers,
    data: {
      entries: [
        {
          entityKey: "prototype",
          data: {
            prototypeCode: "P-MEASURE",
            name: "Measuring fixture",
            materials: ["cotton"],
            prototypePhotos: [MEDIA_ID]
          }
        }
      ],
      replaceCollections: false,
      submit: false
    }
  });
  expect(saved.ok(), `seed stage 13: ${await saved.text()}`).toBeTruthy();
  return id;
}

async function stubMedia(page: Page) {
  await page.route(`**/api/media/${MEDIA_ID}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        id: MEDIA_ID,
        mediaType: "IMAGE",
        originalFilename: "measuring-fixture.svg",
        url: fixtureSvg(),
        caption: "Prototype beside a 100 mm scale bar",
        sizeBytes: 1024,
        mimeType: "image/svg+xml"
      })
    })
  );
}

async function openRow(page: Page) {
  const toggle = page.getByRole("button", { name: "Measuring fixture", exact: true });
  await expect(toggle).toBeVisible({ timeout: 60_000 });
  await toggle.click();
}

test.describe("stage 13 — measuring a dimension from a photograph", () => {
  test("a tapped reference and a tapped object propose a length into the registry field", async ({ page }) => {
    const token = await apiToken(page);
    const workshopId = await seedWorkshop(page, token);
    await stubMedia(page);
    await signIn(page, token);

    await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);
    await openRow(page);

    const openButton = page.getByRole("button", { name: /measure from a photograph/i }).first();
    await expect(openButton).toBeVisible({ timeout: 30_000 });
    await openButton.click();

    await expect(page.getByText(/same flat plane, square to the camera/i)).toBeVisible();

    const photo = page.getByAltText(/place marks on this photograph/i).first();
    await expect(photo).toBeVisible();

    await expect(page.getByText(/4 of 4 marks still to place/i)).toBeVisible();

    await photo.scrollIntoViewIfNeeded();

    const rect = await photo.boundingBox();
    expect(rect, "the photograph has a box to tap in").not.toBeNull();
    expect(rect!.y + rect!.height, "the whole photograph is inside the viewport").toBeLessThanOrEqual(
      page.viewportSize()!.height
    );
    const scale = rect!.width / IMAGE_WIDTH;
    const at = (point: { x: number; y: number }) => ({
      x: rect!.x + point.x * scale,
      y: rect!.y + point.y * scale
    });

    const dragMark = async (name: RegExp, to: { x: number; y: number }) => {
      const handle = page.getByRole("button", { name });
      const box = await handle.boundingBox();
      expect(box, `handle ${name} is on screen`).not.toBeNull();
      const destination = at(to);
      await page.mouse.move(box!.x + box!.width / 2, box!.y + box!.height / 2);
      await page.mouse.down();
      await page.mouse.move(destination.x, destination.y, { steps: 10 });
      await page.mouse.up();
    };

    await dragMark(/^Reference, first end/, REFERENCE_FROM);
    await dragMark(/^Reference, second end/, REFERENCE_TO);
    await dragMark(/^The dimension, first end/, OBJECT_FROM);
    await dragMark(/^The dimension, second end/, OBJECT_TO);

    await expect(page.getByText(/marks still to place/i)).toBeHidden();

    await page.getByRole("button", { name: /scale card, 100 mm/i }).click();

    const readout = page.getByText(/^\s*\d+(\.\d+)?\s*±\s*\d+(\.\d+)?\s*mm\s*$/);
    await expect(readout).toBeVisible();
    const measured = Number((await readout.innerText()).split("±")[0].trim());
    expect(measured).toBeGreaterThan(196);
    expect(measured).toBeLessThan(204);

    await expect(page.getByText(/That is ±\d+\.\d%/)).toBeVisible();

    const lengthField = page.getByLabel("Length", { exact: true }).first();
    await expect(lengthField).toHaveValue("");

    const propose = page.getByRole("button", { name: /^Length: \d+(\.\d+)? cm$/ });
    await expect(propose).toBeVisible();
    const proposed = (await propose.innerText()).replace(/^Length:\s*/, "").replace(/\s*cm$/, "").trim();
    await propose.click();

    await expect(lengthField).toHaveValue(proposed);
    expect(Number(proposed)).toBeGreaterThan(19.6);
    expect(Number(proposed)).toBeLessThan(20.4);
  });

  test("zooming in narrows the stated error bar without moving the answer", async ({ page }) => {
    const token = await apiToken(page);
    const workshopId = await seedWorkshop(page, token);
    await stubMedia(page);
    await signIn(page, token);
    await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);
    await openRow(page);

    await page.getByRole("button", { name: /measure from a photograph/i }).first().click();
    const photo = page.getByAltText(/place marks on this photograph/i).first();
    await expect(photo).toBeVisible();

    const precision = page.getByText(/a mark can be placed to about ±[\d.]+ image pixels/i);
    await expect(precision).toBeVisible();
    const readSigma = async () => {
      const text = await precision.innerText();
      return Number(/±([\d.]+) image pixels/.exec(text)?.[1] ?? Number.NaN);
    };
    const wide = await readSigma();
    expect(Number.isFinite(wide)).toBe(true);

    await page.getByRole("button", { name: "Zoom in" }).click();
    await page.getByRole("button", { name: "Zoom in" }).click();

    const close = await readSigma();
    expect(close).toBeLessThan(wide);
    expect(wide / close).toBeGreaterThan(2);
    expect(wide / close).toBeLessThan(2.5);
  });
});
