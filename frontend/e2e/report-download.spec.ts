import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

/**
 * The report actually downloads.
 *
 * Written because it did not, and because of HOW it did not: `isTransient` in `lib/offline.ts`
 * returns `true` for anything that is not an `ApiError`, so ANY client-side exception thrown
 * anywhere inside the download handler — a TypeError, a bad property read, a failed blob save —
 * was reported to the designer as
 *
 *     "The DOCX is written by the server, so it cannot be generated without a connection."
 *
 * That message is confident, specific and, when the server is up and answering, completely wrong.
 * It sends the designer to look at their signal instead of at the actual fault, and there is
 * nothing on screen to contradict it.
 *
 * So this spec asserts on the DOWNLOAD, not on the absence of an error box: it requires the
 * browser to actually receive a file. It also captures page errors, because the failure mode
 * being defended against is precisely one that hides itself behind a friendly sentence.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";
const WORKSHOP = process.env.E2E_WORKSHOP_ID ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");
test.skip(!WORKSHOP, "Set E2E_WORKSHOP_ID to a seeded workshop.");

for (const format of ["DOCX", "PDF"] as const) {
  test(`the ${format} downloads from the report page`, async ({ page }) => {
    const pageErrors: string[] = [];
    const consoleErrors: string[] = [];
    page.on("pageerror", (e) => pageErrors.push(String(e)));
    page.on("console", (m) => {
      if (m.type() === "error") consoleErrors.push(m.text());
    });

    await signIn(page);
    await page.goto(`/design-workshops/${WORKSHOP}/report`);

    // The API answers the same request happily — proven here so a failure below is unambiguously
    // the client's, not the server's.
    const token = await page.evaluate(() => window.localStorage.getItem("field_repo_token"));
    const direct = await page.request.post(`${API}/api/design-workshops/${WORKSHOP}/report`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { formats: [format], record: false }
    });
    expect(direct.status(), `the API itself generates the ${format}`).toBe(200);

    const button = page.getByRole("button", { name: new RegExp(format, "i") }).first();
    await expect(button).toBeVisible({ timeout: 30_000 });

    const downloadPromise = page.waitForEvent("download", { timeout: 90_000 });
    await button.click();

    // If the handler threw, the friendly-but-wrong offline sentence appears. Surface the REAL
    // cause in the failure message rather than the sentence, which is the whole point.
    const offline = page.getByText(/cannot be generated without a connection/i);
    const outcome = await Promise.race([
      downloadPromise.then(() => "download" as const),
      offline.waitFor({ state: "visible", timeout: 90_000 }).then(() => "offline" as const)
    ]);

    expect(
      outcome,
      `the ${format} reported "no connection" while the API returned 200.\n` +
        `page errors: ${JSON.stringify(pageErrors, null, 2)}\n` +
        `console errors: ${JSON.stringify(consoleErrors, null, 2)}`
    ).toBe("download");

    const download = await downloadPromise;
    expect(download.suggestedFilename()).toMatch(format === "DOCX" ? /\.docx$/i : /\.pdf$/i);
    expect(pageErrors, "no uncaught exception during the download").toEqual([]);
  });
}
