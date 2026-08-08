import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

/**
 * The straightening panel, in the real form.
 *
 * WHAT THIS SPEC IS FOR, given that e2e/sketch-rectify.spec.ts already proves the arithmetic. It
 * proves the WIRING, which no unit test can reach: that `offersSketchRectify` matches a real registry
 * field on a real stage, that `FieldInput` renders the panel straight off the registry with no
 * per-stage form code, and — the assertion that matters most — that the panel is offered on the LINE
 * ART field rather than on the photograph. That placement is the entire guarantee that the original
 * photograph is never detached, because a single IMAGE field REPLACES its value when a file is
 * attached to it, so it is worth an assertion rather than a comment.
 *
 * WHY IT ATTACHES THE PHOTOGRAPH WITH THE BROWSER OFFLINE, which looks like an odd thing to do and is
 * the most realistic thing in the file. Two reasons, and both are the point:
 *
 *  1. It is the case the feature exists for. A designer photographing a sketch in a village has no
 *     connection, the photograph is banked in the local draft store as a `dwlocal:` reference, and
 *     the panel reads those bytes straight back out of IndexedDB. That is the path that has to work.
 *  2. It sidesteps a PRE-EXISTING local-stack defect rather than pretending it is fixed. The dev
 *     stack presigns `minio:9000`, a host name a host browser cannot resolve, so any spec that lets
 *     an upload actually run fails for reasons that have nothing to do with the code under test. With
 *     the browser offline `MediaField.settle` takes its `stageOffline` branch before any presign is
 *     requested, so nothing here depends on that defect either way.
 *
 * Stage 11 is `SKETCH_DEVELOPMENT`, whose `sketch` entity declares `image` (required, Basic) and
 * `lineArtFile` (Advanced — hence the "More detail" disclosure below).
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const WORKSHOP = process.env.E2E_WORKSHOP_ID ?? "cmsik2jg8000eh8xc1lcy661a";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

/**
 * A generated photograph of a tilted sheet under a lamp gradient — see
 * `frontend/e2e/fixtures/sketch-on-table.png`. Generated rather than a real photograph so the fixture
 * carries no likeness and no EXIF, and awkward on purpose: the sheet is a genuine quadrilateral and
 * the illumination ramps, which are the two things the feature exists to deal with.
 */
const PHOTO = readFileSync(join(__dirname, "fixtures", "sketch-on-table.png"));

test("a photographed sketch can be straightened into a plate without touching the photograph", async ({
  page,
  context
}) => {
  await signIn(page);
  await page.goto(`/design-workshops/${WORKSHOP}/stages/SKETCH_DEVELOPMENT`);

  // Open one sketch, then its Advanced tier — `lineArtFile` is an Advanced field and lives behind the
  // "More detail" disclosure, which is where the registry puts it.
  await page.getByRole("button", { name: "Phoda kumbha table runner" }).first().click();
  await page.getByRole("button", { name: /More detail/ }).first().click();
  await expect(page.getByText("Line art / vector file").first()).toBeVisible({ timeout: 30_000 });

  // No photograph yet, so nothing to straighten and nothing offered. Asserted rather than assumed:
  // an offer to process an image that does not exist is the failure mode this guard prevents.
  await expect(page.getByRole("button", { name: /Straighten a photographed sketch/i })).toHaveCount(0);

  // Offline BEFORE the attach, so the file is banked locally instead of presigned. See the header.
  await context.setOffline(true);
  // The app tells the designer it noticed, with a modal, before they discover it at the Save button.
  // Dismissing it is what a designer does; leaving it open would have this spec fighting an overlay
  // that is behaving correctly.
  const offlineNotice = page.getByRole("button", { name: /Continue offline/i });
  await offlineNotice.click({ timeout: 30_000 });

  const imageInput = page.locator('input[type="file"]').first();
  await imageInput.setInputFiles({ name: "sketch-on-table.png", mimeType: "image/png", buffer: PHOTO });

  const trigger = page.getByRole("button", { name: /Straighten a photographed sketch/i }).first();
  await expect(trigger).toBeVisible({ timeout: 30_000 });

  /*
    THE PLACEMENT ASSERTION. The panel must belong to "Line art / vector file" and NOT to "Sketch
    image": attaching to a single IMAGE field replaces its value, so a panel rendered on the
    photograph would be one press away from detaching the original — the outcome
    docs/MEDIA_PIPELINE.md §5 refuses. Walking up to the nearest labelled block states that as a fact
    about the DOM rather than about the source.
  */
  const owningLabel = await trigger.evaluate((node) => {
    let current: HTMLElement | null = node as HTMLElement;
    while (current) {
      const label = current.querySelector("span.field-label, label");
      if (label?.textContent && /line art|vector|sketch image/i.test(label.textContent)) {
        return label.textContent.trim();
      }
      current = current.parentElement;
    }
    return null;
  });
  expect(owningLabel).toMatch(/line art|vector/i);
  expect(owningLabel).not.toMatch(/sketch image/i);

  await trigger.click();

  // Both pictures are on screen: the photograph with four draggable corner handles, and the plate.
  await expect(page.getByText(/drag the four corners of the sheet/i)).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole("button", { name: "Sheet corner 1" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Sheet corner 4" })).toBeVisible();

  // The plate preview is a real canvas that has actually been painted — a blank one would pass a
  // visibility check while showing the designer nothing.
  const painted = await page.locator("canvas").first().evaluate((node) => {
    const canvas = node as HTMLCanvasElement;
    if (!canvas.width || !canvas.height) return { width: 0, height: 0, tones: 0 };
    const context = canvas.getContext("2d");
    if (!context) return { width: canvas.width, height: canvas.height, tones: 0 };
    const { data } = context.getImageData(0, 0, canvas.width, canvas.height);
    const seen = new Set<number>();
    for (let index = 0; index < data.length; index += 4) seen.add(data[index]);
    return { width: canvas.width, height: canvas.height, tones: seen.size };
  });
  expect(painted.width).toBeGreaterThan(50);
  // Line art is on by default, so the plate is bilevel: black strokes and white paper, and BOTH must
  // be present. One tone would mean a plate that is entirely blank or entirely black — which is
  // exactly what a global threshold does to a lamp-lit page, and the thing this must never ship as.
  expect(painted.tones).toBe(2);

  // The automatic guess runs on the device and finds this sheet; the manual handles remain either way.
  await page.getByRole("button", { name: /Find the sheet/i }).click();
  await expect(page.getByText(/Found a sheet filling \d+% of the frame/i)).toBeVisible({ timeout: 30_000 });

  // "Keep the original only" is a first-class outcome, in the same row and the same size as the
  // accept button — declining costs one press and no explanation.
  await expect(page.getByRole("button", { name: /Keep the original only/i })).toBeVisible();

  // And the promise about the original is on screen, not only in a comment.
  await expect(page.getByText(/never modified or replaced/i)).toBeVisible();

  await context.setOffline(false);
});
