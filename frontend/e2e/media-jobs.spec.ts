import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

/**
 * A FAILURE NOBODY CAN SEE IS A FAILURE NOBODY CAN FIX.
 *
 * MediaProcessingJob shipped with three working endpoints and no client on either surface. The
 * queue drained itself, so transcription worked and the missing half stayed quiet: a job that used
 * up its attempts stopped in FAILED with the provider's reason in a column no screen read, and
 * there was no way to ask for it again — from the web, from the phone, at any rank. That is a state
 * no type check and no unit test has an opinion about, because every part of it is correct on its
 * own. Only a browser standing on the page can say whether the reason and the retry are THERE.
 *
 * So this spec asserts the three things the gap was made of:
 *   1. the queue is visible at all, on the page a person would look at;
 *   2. a failed job states WHY, in words, not just the word "Failed";
 *   3. that job can actually be re-run, and the app says the re-run was accepted.
 *
 * Plus the web half of the Android parity gap: "Transcribe now" / "Re-transcribe now" on an audio
 * row, which Android has offered since the feature landed.
 *
 * MUTATION: exactly one — a single Retry, which puts an already-failed job back on the queue. The
 * transcribe-now button is asserted but deliberately NEVER clicked: that call runs a real
 * transcription against a paid provider, which is not a thing a test should spend.
 */

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

    // Narrow to the state the panel exists for. Jobs come back newest-first whatever their status,
    // so on a queue of any size a failure sits pages deep — the filter is the only reliable way to
    // land on one, which is exactly why the panel offers it.
    await panel.getByRole("button", { name: /^Failed/ }).click();

    // The whole reason block, not the bolded label inside it — asserting the label alone would pass
    // on a row that printed "Why it failed:" and then nothing, which is the original bug wearing a
    // new heading. What has to be on screen is the sentence AFTER the colon.
    const reason = panel.getByTestId("media-job-reason").first();
    await expect(reason).toBeVisible();
    await expect(reason).toContainText(/Why it failed:/);
    expect(((await reason.textContent()) ?? "").replace(/^\s*Why it failed:\s*/, "").trim().length).toBeGreaterThan(10);

    // The account driving this spec is an admin, which is what `POST /media/jobs/{id}/retry`
    // requires — so the control must be here rather than the "an admin can re-run this" note.
    const retry = panel.getByTestId("media-job-retry").first();
    await expect(retry).toBeVisible();
    await retry.click();

    // The panel confirms in words that the job went back on the queue. Asserting the row vanished
    // instead would be asserting the worker's timing, not this control's effect.
    await expect(panel.getByText(/re-queued/i).first()).toBeVisible();
  });

  test("an audio row offers the re-run Android has always had", async ({ page }) => {
    await signIn(page);
    await page.goto("/media");

    // Both labels are Android's, verbatim (MainActivity `TranscribeNowButton`): "Transcribe now"
    // with no transcript yet, "Re-transcribe now" over one that already exists.
    const transcribe = page.getByTestId("media-transcribe-now").first();
    await expect(transcribe).toBeVisible();
    await expect(transcribe).toHaveText(/^(Re-transcribe now|Transcribe now)$/);
  });
});
