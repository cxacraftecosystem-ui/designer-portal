import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

test.describe("Media processing jobs", () => {
  test("the queue is on /media, a failed job says why, and it can be re-run", async ({ page }) => {
    await signIn(page);
    await page.goto("/media");

    const panel = page.getByTestId("media-jobs-panel");
    await expect(panel).toBeVisible();
    await expect(panel.getByRole("heading", { name: "Media processing jobs" })).toBeVisible();

    await panel.getByRole("button", { name: /^Failed/ }).click();

    const reason = panel.getByTestId("media-job-reason").first();
    await expect(reason).toBeVisible();
    await expect(reason).toContainText(/Why it failed:/);
    expect(((await reason.textContent()) ?? "").replace(/^\s*Why it failed:\s*/, "").trim().length).toBeGreaterThan(10);

    const retry = panel.getByTestId("media-job-retry").first();
    await expect(retry).toBeVisible();
    await retry.click();

    await expect(panel.getByText(/re-queued/i).first()).toBeVisible();
  });

  test("an audio row offers the re-run Android has always had", async ({ page }) => {
    await signIn(page);
    await page.goto("/media");

    const transcribe = page.getByTestId("media-transcribe-now").first();
    await expect(transcribe).toBeVisible();
    await expect(transcribe).toHaveText(/^(Re-transcribe now|Transcribe now)$/);
  });
});
