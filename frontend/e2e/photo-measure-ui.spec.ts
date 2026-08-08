import { expect, test, type Page } from "@playwright/test";

/**
 * The measuring panel, in the real stage form, driven by real taps.
 *
 * WHY THIS IS A BROWSER TEST AND NOT MORE UNIT TESTS. e2e/photo-measure.spec.ts already pins the
 * geometry to twelve decimal places, and none of it can answer the questions that actually decide
 * whether a designer ever sees a measurement:
 *
 *  - Is the panel OFFERED on stage 13 at all? That is not a decision anybody wrote down — it falls
 *    out of `measurableLengthFields` reading `unit="cm"` off the registry and `offersPhotoMeasure`
 *    finding an image field on the same entity. A registry edit, a renamed unit or a change to
 *    FieldInput's `extra` composition could remove the whole feature from the app while every unit
 *    test still passes, and nothing on screen would say so.
 *  - Does a tap on the photograph become a mark at the right IMAGE pixel? That is the pan/zoom
 *    arithmetic, and it is the one part of the feature that cannot be tested without a layout.
 *  - Does the proposal reach the registry field? The whole point of the panel is the last button.
 *
 * THE PHOTOGRAPH IS AN SVG DATA URI, so the test knows exactly where everything in it is, and so the
 * run needs no upload. That matters here beyond convenience: this stack presigns `minio:9000`, which
 * a host browser cannot resolve, so any spec that uploaded a real photograph would fail for a reason
 * that has nothing to do with measuring. The panel only ever needs a displayable URL and the geometry
 * of where somebody pointed — it never reads a pixel — so a data URI exercises exactly the same code
 * path a presigned S3 URL would.
 *
 * The workshop is created fresh through the real API, as every other signed-in spec here does.
 * Nothing touches the seeded workshop.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

const STAGE_KEY = "PROTOTYPE_DEVELOPMENT";

/** The id the stage row points at. Never fetched from the server — the spec answers for it. */
const MEDIA_ID = "photo-measure-fixture-1";

const IMAGE_WIDTH = 800;
const IMAGE_HEIGHT = 600;

/**
 * The fixture photograph, drawn rather than photographed so every distance in it is known.
 *
 *   a 100 mm scale bar from (100, 500) to (300, 500) — 200 px
 *   an object      from (200, 200) to (600, 200) — 400 px
 *
 * Twice the reference in pixels against 100 mm of reference is 200 mm, which is 20 cm, which is what
 * has to end up in stage 13's `lengthCm`.
 */
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

/**
 * Sign in by planting the token the app reads, rather than by driving the login form.
 *
 * The form is somebody else's test surface — `e2e/back-control.spec.ts` and friends exercise it —
 * and driving it here buys nothing while costing a 60-second navigation wait that goes flaky the
 * moment another spec run is warming the same dev server. `lib/api.ts` reads exactly this key.
 */
async function signIn(page: Page, token: string) {
  await page.addInitScript((value) => window.localStorage.setItem("field_repo_token", value), token);
}

/** A workshop whose stage 13 holds one prototype with one photograph and no dimensions yet. */
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

/**
 * Answer for the one media row the stage points at.
 *
 * Registered before navigation so the field's resolve effect never races a real 404 — an unresolved
 * id renders "This file is no longer readable from here" and the panel would correctly offer nothing.
 */
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

/**
 * Open the one prototype row. A collection row is a collapsed disclosure titled by its `labelField`,
 * and its fields are UNMOUNTED while it is shut — so nothing below exists until this runs.
 */
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

    // THE FEATURE IS OFFERED AT ALL. This is the registry inference, on screen.
    const openButton = page.getByRole("button", { name: /measure from a photograph/i }).first();
    await expect(openButton).toBeVisible({ timeout: 30_000 });
    await openButton.click();

    // THE ASSUMPTION IS STATED, not left to a comment in a file nobody opens.
    await expect(page.getByText(/same flat plane, square to the camera/i)).toBeVisible();

    const photo = page.getByAltText(/place marks on this photograph/i).first();
    await expect(photo).toBeVisible();

    // Nothing is measured until every mark has been placed — the seeded layout is not an answer.
    await expect(page.getByText(/4 of 4 marks still to place/i)).toBeVisible();

    // `page.mouse` works in VIEWPORT coordinates and, unlike `locator.click()`, scrolls nothing into
    // view first. The stage form is long enough that the panel opens below the fold, and every drag
    // below would otherwise be dispatched at a y-coordinate outside the window — landing on nothing,
    // silently, with the marks never moving and no error anywhere.
    await photo.scrollIntoViewIfNeeded();

    const rect = await photo.boundingBox();
    expect(rect, "the photograph has a box to tap in").not.toBeNull();
    // Flush with the fold is what `scrollIntoViewIfNeeded` produces and is fine — the guard is here
    // to fail loudly if the panel ever grows tall enough that the photograph cannot be got fully on
    // screen, because the symptom of that would be drags that quietly do nothing.
    expect(rect!.y + rect!.height, "the whole photograph is inside the viewport").toBeLessThanOrEqual(
      page.viewportSize()!.height
    );
    // Screen pixels per image pixel, straight off the rendered image rather than recomputed from the
    // component's own arithmetic — so a bug in that arithmetic cannot cancel itself out here.
    const scale = rect!.width / IMAGE_WIDTH;
    const at = (point: { x: number; y: number }) => ({
      x: rect!.x + point.x * scale,
      y: rect!.y + point.y * scale
    });

    /**
     * Drag one handle onto a known feature of the photograph.
     *
     * DRAGGED RATHER THAN TAPPED, and it is the more valuable half of the interaction to pin: a mark
     * has to be adjustable after it exists, because nobody lands a fingertip on the end of a ruler
     * first time on a handset. The handle is found by its accessible name and its CURRENT position is
     * read off the page, so this never has to know where the component seeds its marks.
     */
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

    // The reference's real length. The preset is the path a designer takes.
    await page.getByRole("button", { name: /scale card, 100 mm/i }).click();

    // 400 px of object against 200 px of a 100 mm bar is 200 mm — 20 cm. The tolerance is a tap's
    // worth of slop: a click lands on a whole screen pixel, which at this scale is more than one
    // image pixel, so the reading moves by a few tenths of a millimetre either way.
    const readout = page.getByText(/^\s*\d+(\.\d+)?\s*±\s*\d+(\.\d+)?\s*mm\s*$/);
    await expect(readout).toBeVisible();
    const measured = Number((await readout.innerText()).split("±")[0].trim());
    expect(measured).toBeGreaterThan(196);
    expect(measured).toBeLessThan(204);

    // The error bar is reported as a percentage, and it is not zero.
    await expect(page.getByText(/That is ±\d+\.\d%/)).toBeVisible();

    // NOTHING HAS BEEN WRITTEN YET. The dimension field is still empty, which is the contract.
    const lengthField = page.getByLabel("Length", { exact: true }).first();
    await expect(lengthField).toHaveValue("");

    // The proposal, and only now the write.
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

    // The claim on screen before any zoom.
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
    // Two presses of 1.5× is 2.25×, so the per-mark uncertainty in image pixels falls by the same
    // factor. This is the honest half of the zoom control: it is not a viewing aid, it is what makes
    // the measurement better, and the number on screen has to move when it does.
    expect(close).toBeLessThan(wide);
    expect(wide / close).toBeGreaterThan(2);
    expect(wide / close).toBeLessThan(2.5);
  });
});
