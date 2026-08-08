import { expect, test, type Locator, type Page } from "@playwright/test";

import { signIn } from "./support/session";

/**
 * THE REPORT COLOUR PANEL: IT DRAGS, IT CONFIRMS, AND BACKING OUT OF IT STILL APPLIES.
 *
 * Three separate reports, one control. It used to be a native `<input type="color">`, which on a
 * laptop opens a second OS window over the very pages the choice is being made against and on a
 * tablet does whatever the platform feels like. It is now a saturation square and a hue strip
 * drawn in the page.
 *
 * The third test is the one that matters and the one most likely to be got backwards. A designer
 * drags a colour, then clicks back onto the document to look at it — and that click is a dismissal.
 * A picker that treated dismissal as "cancel" would throw the colour away at the exact moment the
 * designer believed they had chosen it, which is the reported failure. So Escape and a click
 * outside both run the SAME commit the confirm button runs, and the proof demanded here is the
 * strongest available one: the colour is still on the workshop after a full page reload, and the
 * server says so independently.
 *
 * Every assertion about "the colour" is made twice over — once against the stage-20 record through
 * the API, and once against the pixel the page is actually painting — because the previous defect
 * in this area ("custom colour not saving") looked correct on screen and had written nothing.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

async function apiToken(page: Page): Promise<string> {
  const res = await page.request.post(`${API}/api/auth/login`, { data: { email: EMAIL, password: PASSWORD } });
  expect(res.ok(), `sign-in for the API fixture: ${await res.text()}`).toBeTruthy();
  return (await res.json()).accessToken as string;
}

/** A workshop of this spec's own, because every test here WRITES the accent onto stage 20. */
async function newWorkshop(page: Page, token: string): Promise<string> {
  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { title: `Accent drag spec ${Date.now()}` }
  });
  expect(created.ok(), `create a workshop: ${await created.text()}`).toBeTruthy();
  return (await created.json()).id as string;
}

/** Stage 20's saved accent as a bare upper-case `RRGGBB`, or "" when nothing has been written. */
async function storedAccent(page: Page, token: string, id: string): Promise<string> {
  const res = await page.request.get(`${API}/api/design-workshops/${id}/stages/REPORT_GENERATION`, {
    headers: { Authorization: `Bearer ${token}` }
  });
  if (!res.ok()) return "";
  const stage = (await res.json()) as { singleton?: Record<string, unknown> };
  return String(stage?.singleton?.themeAccent ?? "")
    .replace("#", "")
    .toUpperCase();
}

async function openReport(page: Page, id: string) {
  await page.goto(`/design-workshops/${id}/report`);
  await expect(page.getByRole("heading", { name: "Report colour" })).toBeVisible({ timeout: 60_000 });
}

const trigger = (page: Page) => page.getByRole("button", { name: "Custom colour" });
const panelOf = (page: Page) => page.getByRole("dialog", { name: "Custom report colour" });

/** What the page is actually painting in the trigger's swatch, as the browser reports it. */
function swatchColour(page: Page): Promise<string> {
  return trigger(page)
    .locator("span")
    .first()
    .evaluate((node) => getComputedStyle(node).backgroundColor);
}

async function openPanel(page: Page): Promise<Locator> {
  await trigger(page).click();
  const panel = panelOf(page);
  await expect(panel).toBeVisible();
  return panel;
}

/**
 * Press inside `surface` at the first fraction and travel through the rest, reading the hex box
 * after every leg.
 *
 * The button stays DOWN for the whole journey and the reading is taken between moves: a picker that
 * only answered discrete clicks would return the same string at every sample but the last, which is
 * precisely the difference this spec exists to detect. Fractions outside 0..1 are deliberate — see
 * the capture assertion in the first test.
 */
async function dragThrough(
  page: Page,
  surface: Locator,
  readout: Locator,
  points: Array<[number, number]>
): Promise<string[]> {
  const box = await surface.boundingBox();
  expect(box, "the drag surface is on screen and has a size").toBeTruthy();
  const at = ([fx, fy]: [number, number]) => [box!.x + box!.width * fx, box!.y + box!.height * fy] as const;

  const [firstX, firstY] = at(points[0]);
  await page.mouse.move(firstX, firstY);
  await page.mouse.down();
  const seen: string[] = [await readout.inputValue()];
  for (const point of points.slice(1)) {
    const [x, y] = at(point);
    // Several intermediate moves per leg, so this is a travelling pointer and not a teleport.
    await page.mouse.move(x, y, { steps: 6 });
    seen.push(await readout.inputValue());
  }
  await page.mouse.up();
  return seen;
}

test("the colour panel tracks a press-and-drag, on both surfaces and past its own edge", async ({ page }) => {
  const token = await apiToken(page);
  const workshopId = await newWorkshop(page, token);
  await signIn(page);
  await openReport(page, workshopId);

  const panel = await openPanel(page);
  const readout = panel.getByLabel("Colour hex code");
  const square = panel.getByRole("slider", { name: "Colour saturation and brightness" });
  const hue = panel.getByRole("slider", { name: "Colour hue" });

  // Five stops across the square, diagonally, in one unbroken press.
  const acrossSquare = await dragThrough(page, square, readout, [
    [0.1, 0.1],
    [0.3, 0.3],
    [0.5, 0.5],
    [0.7, 0.7],
    [0.9, 0.85]
  ]);
  const distinctSquare = new Set(acrossSquare);
  expect(
    distinctSquare.size,
    `the colour changed continuously along the drag rather than only on release — saw ${acrossSquare.join(", ")}`
  ).toBeGreaterThanOrEqual(4);

  // The hue strip is a second, independent drag surface and has to track too.
  const acrossHue = await dragThrough(page, hue, readout, [
    [0.05, 0.5],
    [0.3, 0.5],
    [0.55, 0.5],
    [0.8, 0.5]
  ]);
  expect(
    new Set(acrossHue).size,
    `the hue strip changed continuously along the drag — saw ${acrossHue.join(", ")}`
  ).toBeGreaterThanOrEqual(4);

  /*
    POINTER CAPTURE, WHICH IS THE WHOLE REASON THIS IS NOT A MOUSEMOVE-ON-DOCUMENT CONTROL.

    A drag that runs off the bottom of the square must keep tracking until the button comes up. The
    last two stops here are BELOW the surface — a fifth and a whole box-height past its bottom edge
    — so an implementation that only listened on the element itself would freeze at the edge. The
    bottom row of a saturation/brightness square is black, so the clamped answer is knowable
    without arithmetic.
  */
  const offTheEdge = await dragThrough(page, square, readout, [
    [0.5, 0.5],
    [0.5, 1.2],
    [0.5, 2.0]
  ]);
  expect(offTheEdge[0], "the press landed on a colour").not.toBe("000000");
  expect(
    offTheEdge[offTheEdge.length - 1],
    "the drag kept tracking after the pointer left the surface, clamped to the bottom edge"
  ).toBe("000000");
});

test("confirming in the panel applies the colour and keeps it on the workshop", async ({ page }) => {
  const token = await apiToken(page);
  const workshopId = await newWorkshop(page, token);
  expect(await storedAccent(page, token, workshopId), "a fresh workshop has no saved colour").toBe("");

  await signIn(page);
  await openReport(page, workshopId);

  const panel = await openPanel(page);
  const readout = panel.getByLabel("Colour hex code");
  await dragThrough(page, panel.getByRole("slider", { name: "Colour hue" }), readout, [
    [0.08, 0.5],
    [0.16, 0.5]
  ]);
  await dragThrough(page, panel.getByRole("slider", { name: "Colour saturation and brightness" }), readout, [
    [0.5, 0.5],
    [0.78, 0.72]
  ]);
  const picked = await readout.inputValue();
  expect(picked, "the panel is showing a six-digit colour").toMatch(/^[0-9A-F]{6}$/);

  await panel.getByRole("button", { name: "Use this colour" }).click();
  await expect(panel, "confirming closes the panel").toBeHidden();

  await expect
    .poll(() => storedAccent(page, token, workshopId), {
      message: "the confirmed colour reached stage 20",
      timeout: 30_000
    })
    .toBe(picked);
});

test("dismissing the panel without confirming still applies the colour, and it survives a reload", async ({ page }) => {
  const token = await apiToken(page);
  const workshopId = await newWorkshop(page, token);
  await signIn(page);
  await openReport(page, workshopId);

  const before = await swatchColour(page);
  const kept = page.getByText("Using the colour saved on stage 20 for this file.");
  await expect(kept, "nothing is saved on a fresh workshop yet").toBeHidden();

  /* ── Escape, which is the keyboard's way of backing out ─────────────────────────────────── */
  let panel = await openPanel(page);
  let readout = panel.getByLabel("Colour hex code");
  await dragThrough(page, panel.getByRole("slider", { name: "Colour hue" }), readout, [
    [0.35, 0.5],
    [0.42, 0.5]
  ]);
  await dragThrough(page, panel.getByRole("slider", { name: "Colour saturation and brightness" }), readout, [
    [0.5, 0.5],
    [0.82, 0.66]
  ]);
  const escaped = await readout.inputValue();
  expect(escaped).toMatch(/^[0-9A-F]{6}$/);

  // No confirm. Just leave.
  await page.keyboard.press("Escape");
  await expect(panel).toBeHidden();

  await expect
    .poll(() => storedAccent(page, token, workshopId), {
      message: "a colour dismissed with Escape was applied, not discarded",
      timeout: 30_000
    })
    .toBe(escaped);

  // And on screen: the page stops offering an override and says the colour is the workshop's.
  await expect(kept).toBeVisible({ timeout: 30_000 });
  const afterEscape = await swatchColour(page);
  expect(afterEscape, "the page is painting a different colour from the one it opened on").not.toBe(before);

  /* ── The reload. This is the assertion the whole spec is for. ───────────────────────────── */
  await page.reload();
  await expect(page.getByRole("heading", { name: "Report colour" })).toBeVisible({ timeout: 60_000 });
  await expect(kept, "the dismissed colour is still the workshop's after a reload").toBeVisible({ timeout: 30_000 });
  expect(await swatchColour(page), "and the page reloads painting that same colour").toBe(afterEscape);

  /* ── Clicking back onto the document is the other way out, and must behave identically ──── */
  panel = await openPanel(page);
  readout = panel.getByLabel("Colour hex code");
  await dragThrough(page, panel.getByRole("slider", { name: "Colour hue" }), readout, [
    [0.62, 0.5],
    [0.7, 0.5]
  ]);
  const clickedAway = await readout.inputValue();
  expect(clickedAway).not.toBe(escaped);

  // A click well clear of both the panel and the trigger — the gesture of going back to reading
  // the pages, which is exactly when a designer would have lost their colour before.
  await page.mouse.click(5, 300);
  await expect(panel).toBeHidden();

  await expect
    .poll(() => storedAccent(page, token, workshopId), {
      message: "a colour dismissed by clicking away was applied, not discarded",
      timeout: 30_000
    })
    .toBe(clickedAway);
});
