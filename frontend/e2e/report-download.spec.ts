import { expect, test, type Page } from "@playwright/test";

import { signIn, sessionTokenOf } from "./support/session";

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

    const token = await sessionTokenOf(page);
    const direct = await page.request.post(`${API}/api/design-workshops/${WORKSHOP}/report`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { formats: [format], record: false }
    });
    expect(direct.status(), `the API itself generates the ${format}`).toBe(200);

    const button = page.getByRole("button", { name: new RegExp(format, "i") }).first();
    await expect(button).toBeVisible({ timeout: 30_000 });

    const downloadPromise = page.waitForEvent("download", { timeout: 90_000 });
    await button.click();

    const offline = page.getByText(/Connect to the internet to generate the/i);
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
