import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const WORKSHOP = process.env.E2E_WORKSHOP_ID ?? "cmsik2jg8000eh8xc1lcy661a";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

const PHOTO = readFileSync(join(__dirname, "fixtures", "sketch-on-table.png"));

test("a photographed sketch can be straightened into a plate without touching the photograph", async ({
  page,
  context
}) => {
  await signIn(page);
  await page.goto(`/design-workshops/${WORKSHOP}/stages/SKETCH_DEVELOPMENT`);

  await page.getByRole("button", { name: "Phoda kumbha table runner" }).first().click();
  await page.getByRole("button", { name: /More detail/ }).first().click();
  await expect(page.getByText("Line art / vector file").first()).toBeVisible({ timeout: 30_000 });

  await expect(page.getByRole("button", { name: /Straighten a photographed sketch/i })).toHaveCount(0);

  await context.setOffline(true);
  const offlineNotice = page.getByRole("button", { name: /Continue offline/i });
  await offlineNotice.click({ timeout: 30_000 });

  const imageInput = page.locator('input[type="file"]').first();
  await imageInput.setInputFiles({ name: "sketch-on-table.png", mimeType: "image/png", buffer: PHOTO });

  const trigger = page.getByRole("button", { name: /Straighten a photographed sketch/i }).first();
  await expect(trigger).toBeVisible({ timeout: 30_000 });

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

  await expect(page.getByText(/drag the four corners of the sheet/i)).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole("button", { name: "Sheet corner 1" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Sheet corner 4" })).toBeVisible();

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
  expect(painted.tones).toBe(2);

  await page.getByRole("button", { name: /Find the sheet/i }).click();
  await expect(page.getByText(/Found a sheet filling \d+% of the frame/i)).toBeVisible({ timeout: 30_000 });

  await expect(page.getByRole("button", { name: /Keep the original only/i })).toBeVisible();

  await expect(page.getByText(/never modified or replaced/i)).toBeVisible();

  await context.setOffline(false);
});
