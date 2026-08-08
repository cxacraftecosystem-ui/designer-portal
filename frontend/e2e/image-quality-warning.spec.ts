import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

/**
 * THE WARNING MUST APPEAR, AND IT MUST NEVER BE A GATE.
 *
 * The measurement itself is unit-tested in image-quality.spec.ts against generated images. What that
 * cannot say is whether the finding ever reaches a human, or — much more importantly — whether
 * reaching a human costs the designer their photograph. Those are properties of the running page,
 * and only a browser standing on it can answer them.
 *
 * So this spec asserts the two halves of the contract that make the feature safe to ship:
 *
 *   1. selecting an under-resolution photograph produces a visible warning that QUOTES THE
 *      MEASUREMENT ("1024x768", "1280px"), not merely a verdict; and
 *   2. the upload proceeds anyway — the file reaches "Uploaded ✓" whether the warning is dismissed
 *      or simply ignored. A warning that could block would be a new way to lose fieldwork, which is
 *      the exact failure this feature exists to prevent.
 *
 * MUTATION: files are attached and eagerly uploaded to object storage, then the page is left without
 * saving — which is precisely the path the staged-object sweeper reclaims (docs/MEDIA_PIPELINE.md
 * §2.7). No MediaFile row is created, because the form is never submitted.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

/*
 * REACHING OBJECT STORAGE FROM A BROWSER ON THE HOST.
 *
 * The compose stack presigns upload URLs against the container hostname `minio:9000`, because that
 * is what the API can reach. A browser running on the host cannot resolve it, so the eager upload's
 * PUT dies with ERR_NAME_NOT_RESOLVED and no file ever reports "Uploaded ✓" — which would leave this
 * spec unable to assert the half that actually matters, that a warning never costs the designer
 * their photograph.
 *
 * Nothing is done about that HERE any more. `playwright.config.ts` launches every browser in this
 * suite with `--host-resolver-rules=MAP minio:9000 127.0.0.1:9010`, which redirects the connection
 * and leaves the signed Host header alone — the one approach, applied once, for every spec that
 * uploads. This file used to run a TCP forwarder of its own on port 9000 to achieve the same thing;
 * it was removed because two mechanisms for one problem is how they drift apart, and because a
 * spec-local `test.use({ launchOptions })` REPLACES the config's flags rather than adding to them,
 * so declaring one here would have quietly switched the shared rule off.
 */

/**
 * Build a real JPEG in the page and hand it to the file input.
 *
 * Drawn rather than checked in, for the same reason the calibration corpus is generated: a binary
 * fixture's dimensions are invisible in a diff, and this spec's whole subject is its dimensions.
 * The pattern is high-contrast and detailed on purpose — the photograph must be SHARP, so that the
 * only finding is the resolution one and the assertion cannot accidentally pass on a blur warning.
 */
async function attachGeneratedImage(page: Page, width: number, height: number, name: string) {
  const dataUrl = await page.evaluate(
    ({ width: w, height: h }) => {
      const canvas = document.createElement("canvas");
      canvas.width = w;
      canvas.height = h;
      const context = canvas.getContext("2d")!;
      context.fillStyle = "#f2efe6";
      context.fillRect(0, 0, w, h);
      const step = Math.max(2, Math.round(w / 60));
      for (let x = 0; x < w; x += step * 2) {
        context.fillStyle = "#2b211c";
        context.fillRect(x, 0, step, h);
      }
      context.fillStyle = "#c0502f";
      for (let y = 0; y < h; y += step * 3) context.fillRect(0, y, w, Math.max(1, Math.round(step / 2)));
      return canvas.toDataURL("image/jpeg", 0.92);
    },
    { width, height }
  );
  const buffer = Buffer.from(dataUrl.split(",")[1], "base64");
  // The card renders several file inputs (Pick files / Take photo / Record video); the first is
  // "Pick files", which is the one a designer uses for an image already on the device.
  await page.locator('input[type="file"]').first().setInputFiles({ name, mimeType: "image/jpeg", buffer });
}

test.describe("On-device photograph quality warnings", () => {
  test("an under-resolution photograph is flagged with its measurement, and still uploads", async ({ page }) => {
    await signIn(page);
    await page.goto("/media");

    // 1024x768 — comfortably under the 1280px long edge a full-width A4 report plate needs.
    await attachGeneratedImage(page, 1024, 768, "under-resolution.jpg");

    const panel = page.getByTestId("image-quality-warning");
    await expect(panel).toBeVisible({ timeout: 30_000 });
    await expect(panel).toContainText("under-resolution.jpg");

    // THE MEASUREMENT, not just the verdict: both the actual dimensions and the figure they are
    // being judged against have to be on screen, or a designer cannot tell whether to re-shoot.
    await expect(panel).toContainText("1024x768");
    await expect(panel).toContainText("1280");

    // The promise the panel makes in writing, which the rest of this test then verifies for real.
    await expect(panel).toContainText(/uploading anyway/i);

    // 2. THE UPLOAD PROCEEDS. The eager pre-upload started at attach time and the warning did not
    //    touch it — the tile reaches "Uploaded ✓" with the warning still on screen.
    await expect(page.getByText("All uploaded ✓ — ready to save")).toBeVisible({ timeout: 60_000 });

    // 3. DISMISSING IS A DISMISSAL, NOT A DELETION. The sentence goes; the file stays attached and
    //    stays uploaded.
    await page.getByRole("button", { name: "Dismiss" }).click();
    await expect(panel).toHaveCount(0);
    await expect(page.getByText("1 file attached")).toBeVisible();
    await expect(page.getByText("All uploaded ✓ — ready to save")).toBeVisible();
  });

  test("a sharp, full-resolution photograph produces no warning at all", async ({ page }) => {
    await signIn(page);
    await page.goto("/media");

    // The silence case, and the one that matters most: a designer who is warned about a good
    // photograph stops reading warnings, and then the real one goes past unread too.
    await attachGeneratedImage(page, 2400, 1800, "good-plate.jpg");

    await expect(page.getByText("All uploaded ✓ — ready to save")).toBeVisible({ timeout: 60_000 });
    await expect(page.getByText(/Check this photograph/)).toHaveCount(0);
  });
});
