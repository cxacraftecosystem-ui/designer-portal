import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

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
  await page.locator('input[type="file"]').first().setInputFiles({ name, mimeType: "image/jpeg", buffer });
}

test.describe("On-device photograph quality warnings", () => {
  test("an under-resolution photograph is flagged with its measurement, and still uploads", async ({ page }) => {
    await signIn(page);
    await page.goto("/media");

    await attachGeneratedImage(page, 1024, 768, "under-resolution.jpg");

    const panel = page.getByTestId("image-quality-warning");
    await expect(panel).toBeVisible({ timeout: 30_000 });
    await expect(panel).toContainText("under-resolution.jpg");

    await expect(panel).toContainText("1024x768");
    await expect(panel).toContainText("1280");

    await expect(panel).toContainText(/uploading anyway/i);

    await expect(page.getByText("All uploaded ✓ — ready to save")).toBeVisible({ timeout: 60_000 });

    await page.getByRole("button", { name: "Dismiss" }).click();
    await expect(panel).toHaveCount(0);
    await expect(page.getByText("1 file attached")).toBeVisible();
    await expect(page.getByText("All uploaded ✓ — ready to save")).toBeVisible();
  });

  test("a sharp, full-resolution photograph produces no warning at all", async ({ page }) => {
    await signIn(page);
    await page.goto("/media");

    await attachGeneratedImage(page, 2400, 1800, "good-plate.jpg");

    await expect(page.getByText("All uploaded ✓ — ready to save")).toBeVisible({ timeout: 60_000 });
    await expect(page.getByText(/Check this photograph/)).toHaveCount(0);
  });
});
