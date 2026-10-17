import { expect, test } from "@playwright/test";

import { API, apiToken, CREDENTIALS_MISSING, signIn } from "./support/session";

/**
 * DW-14 ON A LIVE STAGE PAGE: the microphone is drawn INSIDE the field's box, and the "i" opens.
 *
 * `stage-help-dictate-unit.spec.ts` holds the pure half (the class strings and the ARIA helpers).
 * This is the half only a browser can see — where the button actually lands — measured at phone
 * width on a freshly created workshop, so it never depends on what an earlier run left behind.
 *
 * A stub recogniser is installed before the page loads, so the control draws in browser mode on
 * any Chromium build (headless included) and nothing is ever sent to a dictation provider.
 */

test.skip(!!CREDENTIALS_MISSING, CREDENTIALS_MISSING);

const STAGE_KEY = "WORKSHOP_SETUP";
/** `workshopSetup.craftName`: a one-line TEXT box that dictates and carries `details`. */
const FIELD_LABEL = "Craft";

test.use({ viewport: { width: 390, height: 844 } });

test("the microphone sits inside the box, and the i opens its panel", async ({ page }) => {
  await page.addInitScript(() => {
    class StubRecognition {
      lang = "";
      continuous = false;
      interimResults = false;
      onresult: unknown = null;
      onerror: unknown = null;
      onend: unknown = null;
      start() {}
      stop() {}
      abort() {}
    }
    (window as unknown as { webkitSpeechRecognition: unknown }).webkitSpeechRecognition = StubRecognition;
  });

  const token = await apiToken(page.request);
  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { title: `Stage help e2e ${Date.now()}` }
  });
  expect(created.ok(), `create a design workshop: ${await created.text()}`).toBeTruthy();
  const workshopId = (await created.json()).id as string;

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);

  const toggle = page.getByRole("button", { name: `About ${FIELD_LABEL}`, exact: true });
  await expect(toggle).toBeVisible();
  // The toggle's row, then the field's own column: label row, details, box, hint.
  const field = toggle.locator("xpath=../..");

  const box = field.locator(".field-input").first();
  const mic = field.locator("[data-dictate-button]").first();
  await expect(mic).toBeVisible();
  await expect(mic).toHaveAccessibleName("Dictate");
  await expect(field.locator("[data-dictation-adornment]")).toHaveCount(1);

  const boxRect = await box.boundingBox();
  const micRect = await mic.boundingBox();
  expect(boxRect, "the field box has a layout").not.toBeNull();
  expect(micRect, "the microphone has a layout").not.toBeNull();
  if (boxRect && micRect) {
    const slack = 1;
    expect(micRect.x).toBeGreaterThanOrEqual(boxRect.x - slack);
    expect(micRect.y).toBeGreaterThanOrEqual(boxRect.y - slack);
    expect(micRect.x + micRect.width).toBeLessThanOrEqual(boxRect.x + boxRect.width + slack);
    expect(micRect.y + micRect.height).toBeLessThanOrEqual(boxRect.y + boxRect.height + slack);
  }

  await expect(toggle).toHaveAttribute("aria-expanded", "false");
  await expect(field.locator("[data-info-panel]")).toHaveCount(0);
  await toggle.click();
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
  const panelId = await toggle.getAttribute("aria-controls");
  expect(panelId).toBeTruthy();
  const panel = page.locator(`[id="${panelId}"]`);
  await expect(panel).toBeVisible();
  await expect(panel).toHaveAttribute("data-info-panel", "");
  await expect(panel).toContainText("Filled in from the linked record");
});
