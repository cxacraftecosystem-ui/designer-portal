import { expect, test, type Locator, type Page } from "@playwright/test";

/**
 * The five access barriers that stopped somebody using this app from finishing a task, and the
 * evidence each one is gone.
 *
 * WHY THESE ARE BROWSER TESTS. Every assertion here is about the computed accessibility tree or the
 * resolved colour of a painted pixel — what a screen reader or a low-vision reader is actually
 * handed. None of it can be answered by reading the JSX: `aria-describedby` is a promise about an
 * element that must exist, be reachable, and hold the sentence it claims to; contrast is a function
 * of two computed colours whose tokens invert with the theme. A unit test of any of these five
 * components passes in the broken world, because each one was rendering exactly what it was asked
 * to render — the failure was that nothing connected it to anybody who could not see it.
 *
 * WHAT EACH ONE COST, before the fix:
 *
 *  1. **Sign-in.** A refused password repainted one line above the form and moved no focus. A
 *     researcher using a screen reader pressed Enter, heard nothing at all, and had no way to tell a
 *     wrong password from a server that had not answered. This is the front door.
 *  2. **The stage form.** `save_stage` returns per-field refusals; the page painted them red under
 *     each box. Nothing was announced, nothing was marked invalid, and `field.help` — the line that
 *     says which unit to type in — belonged to no control. On a 30-field stage that is a form that
 *     cannot be corrected, only guessed at. One renderer draws all 496 registry fields, so the
 *     assertion here stands for every one of them.
 *  3. **The artisan form's numbered lists.** Do's and Don'ts are REQUIRED and each row was an input
 *     with no label, no aria-label and no placeholder — accessible name empty. A reader tabbing the
 *     form met two mandatory boxes announced as "edit text, blank", identical to each other, and the
 *     save was then refused by a mirror `<textarea>` they cannot reach.
 *  4. **The phone number.** `aria-invalid` was set and pointed at nothing, so the browser said
 *     "invalid" and kept the reason — which of the two length rules was broken — to itself.
 *  5. **The list pickers.** With nothing chosen, the trigger's only text is the question it is
 *     asking ("Select one or more workshops"). It was drawn in the `ink-300` placeholder rung:
 *     2.44:1 on the card in light mode, below the 4.5:1 AA floor, on four of the eleven routes swept.
 *
 * These are grouped in one file because they are one claim — "a person who cannot see the screen, or
 * cannot see it well, can still finish a task here" — and splitting them per attribute would hide
 * that the same two defects (a message attached to nothing, a colour chosen for decoration) recur
 * across unrelated components.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

/** The fully-populated workshop. Overridable, because a fresh deployment seeds a different id. */
const WORKSHOP_ID = process.env.E2E_DW_ID ?? "cmsik2jg8000eh8xc1lcy661a";

/**
 * Stage 1's singleton entity and its first required TEXT field.
 *
 * Chosen because it is the plainest shape the registry produces — one singleton, a `TEXT` field that
 * carries `help` — so a failure here is unambiguously about the wiring and not about a composite
 * control's own quirks.
 */
const STAGE_KEY = "WORKSHOP_SETUP";
const ENTITY_KEY = "workshopSetup";
const FIELD_LABEL = "Workshop title";

/**
 * A DATE field on the same entity, asserted alongside the plain text box.
 *
 * `TEXT` is a bare `<input>` and `DATE` is the app's own composite control several components deep,
 * so they are the two ends of the renderer: covering only the first would leave the branches that
 * needed the description THREADED through them — 41 ENUM, 21 DATE, 4 MULTI_ENUM fields in the
 * registry — untested and free to silently drop it.
 */
const DATE_FIELD_LABEL = "Start date";

/** What the server would say. Distinctive, so the spec cannot pass on some other message. */
const REFUSAL = "This title is already used by another workshop in this cluster.";
const DATE_REFUSAL = "The workshop cannot start before its sanction order.";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

async function signIn(page: Page) {
  await page.goto("/login");
  const email = page.getByPlaceholder("Enter your email");
  await email.waitFor({ state: "visible", timeout: 60_000 });
  await email.fill(EMAIL);
  await page.getByPlaceholder("Enter your password").fill(PASSWORD);
  const submit = page.getByRole("button", { name: /sign in/i });
  await submit.click();

  /*
   * The retry is not superstition. On a dev server compiling under load the sign-in button paints
   * before React has attached its handler, so the first click submits nothing at all and the run
   * dies 60 seconds later in `waitForURL` — a failure that names the sign-in page while actually
   * complaining about hydration. Observed three times while these specs were being written, always
   * on a busy machine and never in isolation.
   *
   * Worth fixing rather than tolerating: an accessibility regression test that fails at random gets
   * muted, and a muted test protects nobody. Pressing again once the page is certainly interactive
   * is idempotent — if the first click DID land, the URL has already changed and we never get here.
   */
  try {
    await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 30_000 });
  } catch {
    await submit.click();
    await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
  }
}

/**
 * The text of every element `control` points at with `aria-describedby`, joined the way a screen
 * reader would read them.
 *
 * Resolved in the page rather than asserted against the attribute string, because the attribute is
 * only half a promise: an id that names no element, or names an empty one, reads as silence and is
 * exactly the shape a hand-written `aria-describedby` fails in.
 */
async function describedByText(control: Locator): Promise<string> {
  return control.evaluate((node) => {
    const ids = (node.getAttribute("aria-describedby") ?? "").split(/\s+/).filter(Boolean);
    return ids
      .map((id) => document.getElementById(id)?.textContent?.trim() ?? "")
      .filter(Boolean)
      .join(" ");
  });
}

/**
 * The contrast ratio between an element's own text colour and the first painted background behind
 * it, per WCAG 2.1 relative luminance.
 *
 * The walk up the ancestors is the part that matters: the control's own background is very often
 * `transparent`, and comparing text against `rgba(0,0,0,0)` yields a number that means nothing.
 */
async function textContrast(target: Locator): Promise<number> {
  return target.evaluate((node) => {
    const parse = (value: string): [number, number, number, number] | null => {
      const match = value.match(/rgba?\(([^)]+)\)/);
      if (!match) return null;
      const parts = match[1].split(/[\s,/]+/).filter(Boolean).map(Number);
      return [parts[0], parts[1], parts[2], parts[3] === undefined ? 1 : parts[3]];
    };
    const luminance = ([r, g, b]: number[]) => {
      const channel = (c: number) => {
        const s = c / 255;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
      };
      return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
    };

    const foreground = parse(getComputedStyle(node as Element).color);
    if (!foreground) return 0;

    let backdrop: [number, number, number, number] | null = null;
    let cursor: Element | null = node as Element;
    while (cursor) {
      const candidate = parse(getComputedStyle(cursor).backgroundColor);
      if (candidate && candidate[3] > 0) {
        backdrop = candidate;
        break;
      }
      cursor = cursor.parentElement;
    }
    if (!backdrop) backdrop = [255, 255, 255, 1];

    const a = luminance(foreground);
    const b = luminance(backdrop);
    const [hi, lo] = a > b ? [a, b] : [b, a];
    return (hi + 0.05) / (lo + 0.05);
  });
}

/* ────────────────────────────────────────────────────────────────────────────
 * 1. The front door
 * ──────────────────────────────────────────────────────────────────────────── */

test("a refused sign-in is announced, not merely painted red", async ({ page }) => {
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill("nobody@example.org");
  await page.getByPlaceholder("Enter your password").fill("definitely-not-the-password");
  await page.getByRole("button", { name: /sign in/i }).click();

  // `getByRole("alert")` is the assertion, not a text lookup that happens to find the box: the only
  // way this resolves is if the element carries a role assistive technology treats as live. A plain
  // <div> holding the identical sentence does not match, which is precisely the bug.
  //
  // Filtered on the text because Next mounts its own permanently-empty `#__next-route-announcer__`
  // with `role="alert"` on every page — matching it would make this test pass on any app at all.
  const announcement = page.getByRole("alert").filter({ hasText: /invalid|unable|incorrect|password/i });
  await expect(announcement).toBeVisible({ timeout: 30_000 });

  // Still on the sign-in page: the point is that the failure reached the reader, not that it moved them.
  await expect(page).toHaveURL(/\/login/);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The stage form — one renderer, 496 fields
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("the stage form's hints and refusals reach a screen reader", () => {
  test("a field's help line is what the control is described by", async ({ page }) => {
    await signIn(page);
    await page.goto(`/design-workshops/${WORKSHOP_ID}/stages/${STAGE_KEY}`);

    const title = page.getByLabel(new RegExp(`^${FIELD_LABEL}`, "i")).first();
    await expect(title).toBeVisible({ timeout: 45_000 });

    // The registry declares `help` on this field, and it is drawn under the box. Before the fix it
    // was a paragraph with no id that no control pointed at — visible to a sighted reader and
    // unreachable to everybody else.
    const described = await describedByText(title);
    expect(described.length, "the help line must be reachable through aria-describedby").toBeGreaterThan(0);
  });

  test("a per-field refusal is announced, attached, and marks the box invalid", async ({ page }) => {
    await signIn(page);

    /*
     * The refusal is injected rather than provoked. `save_stage`'s own validation rules are the
     * server's business and change with the registry; what is under test is what this page does with
     * a refusal once it has one, so the response is fixed at the boundary. That also makes the test
     * deterministic offline and leaves the seeded workshop untouched — the PUT never reaches the API.
     */
    await page.route(`**/design-workshops/${WORKSHOP_ID}/stages/${STAGE_KEY}`, async (route) => {
      if (route.request().method() !== "PUT") return route.fallback();
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          stageKey: STAGE_KEY,
          saved: 0,
          created: 0,
          updated: 0,
          removed: 0,
          errors: { [ENTITY_KEY]: { workshopTitle: REFUSAL, startDate: DATE_REFUSAL } },
          droppedKeys: [],
          completeness: null,
          schemaVersion: "test"
        })
      });
    });

    await page.goto(`/design-workshops/${WORKSHOP_ID}/stages/${STAGE_KEY}`);
    const title = page.getByLabel(new RegExp(`^${FIELD_LABEL}`, "i")).first();
    await expect(title).toBeVisible({ timeout: 45_000 });

    // Typing first is not decoration. A stage with nothing unsent on this device answers Save with
    // "There is nothing in this stage on this device to send" and never issues the PUT at all, so
    // without an edit the intercepted response is never asked for.
    await title.fill("Bagru block printing — accessibility regression");
    await page.getByRole("button", { name: /^Save stage$/ }).click();

    // (a) ANNOUNCED. The message must be in the accessibility tree as a live region — a red
    //     paragraph that is merely visible is what a reader gets no signal from.
    const alert = page.getByRole("alert").filter({ hasText: REFUSAL });
    await expect(alert).toBeVisible({ timeout: 30_000 });

    // (b) MARKED. Coming back to the field later must still say that this one was refused.
    await expect(title).toHaveAttribute("aria-invalid", "true");

    // (c) ATTACHED. And it must say WHY, from the field itself — the reason travelling with the box
    //     rather than sitting somewhere on the page a reader has to go hunting for.
    expect(await describedByText(title)).toContain(REFUSAL);

    // (d) AND THE SAME THREE THINGS ON A COMPOSITE CONTROL. The date field is not an `<input>` the
    //     renderer owns — it is the app's own picker, and the description has to be threaded down
    //     through two components to reach the box a designer actually types into. A refusal that
    //     stops at the edge of the component that owns the box is a refusal 88 registry fields
    //     never receive.
    const startDate = page.getByLabel(new RegExp(`^${DATE_FIELD_LABEL}`, "i")).first();
    await expect(startDate).toHaveAttribute("aria-invalid", "true");
    expect(await describedByText(startDate)).toContain(DATE_REFUSAL);
  });
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3 & 4. The artisan form
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("the artisan form can be filled in without seeing it", () => {
  test("every required numbered-list row has a name and an ordinal", async ({ page }) => {
    await signIn(page);
    await page.goto("/artisans/new");

    // The group carries the heading that is printed above the rows, so a reader knows which of the
    // two identical lists they have landed in.
    const dos = page.getByRole("group", { name: /Do's \(positive prompt\)/ });
    await expect(dos).toBeVisible({ timeout: 45_000 });
    const donts = page.getByRole("group", { name: /Don'ts \(negative prompt\)/ });
    await expect(donts).toBeVisible();

    // And each row is named by the ordinal it is drawn with, so point 2 is distinguishable from
    // point 5 in a list of eight boxes that are otherwise identical.
    await expect(dos.getByRole("textbox", { name: "Point 1" })).toBeVisible();

    await dos.getByRole("textbox", { name: "Point 1" }).fill("Soak the cloth before printing");
    await dos.getByRole("button", { name: /Add point/ }).click();
    await expect(dos.getByRole("textbox", { name: "Point 2" })).toBeVisible();
    // The remove control is named by its ordinal too — eight buttons all called "Remove point" tell
    // a reader nothing about which row they would destroy.
    await expect(dos.getByRole("button", { name: "Remove point 2" })).toBeVisible();

    // Nothing anywhere in the form may be a nameless box. This is the general form of the defect the
    // two lists were an instance of, so it is asserted over the whole form rather than per control.
    const nameless = await page.evaluate(() => {
      const form = document.querySelector("form");
      if (!form) return ["no form on the page"];
      const missing: string[] = [];
      for (const control of Array.from(form.querySelectorAll("input, textarea"))) {
        const el = control as HTMLInputElement;
        // Hidden inputs and the app's zero-size mirror twins are not reachable by anybody and are
        // deliberately `aria-hidden` / `tabindex="-1"`; naming them would add noise, not access.
        if (el.type === "hidden") continue;
        if (el.getAttribute("aria-hidden") === "true" || el.tabIndex < 0) continue;
        if (!el.offsetParent && el.type !== "file") continue;
        const named =
          el.labels?.length ||
          el.getAttribute("aria-label") ||
          el.getAttribute("aria-labelledby") ||
          el.getAttribute("placeholder") ||
          el.getAttribute("title");
        if (!named) missing.push(el.outerHTML.slice(0, 140));
      }
      return missing;
    });
    expect(nameless, "every reachable control in the artisan form must have an accessible name").toEqual([]);
  });

  test("a phone number that is refused says which rule it broke", async ({ page }) => {
    await signIn(page);
    await page.goto("/artisans/new");

    const phone = page.getByRole("textbox", { name: "Phone number" });
    await expect(phone).toBeVisible({ timeout: 45_000 });

    // Five digits under +91, where the rule is exactly ten.
    await phone.fill("98765");

    await expect(phone).toHaveAttribute("aria-invalid", "true");
    // The reason must be announced as it appears — the researcher is typing, and finding out at
    // submit time that the number was wrong is finding out too late.
    await expect(page.getByRole("alert").filter({ hasText: /10-digit/ })).toBeVisible();
    // …and must be attached to the box, so it is still there when they tab back to fix it.
    expect(await describedByText(phone)).toMatch(/10-digit/);

    // Ten digits clears both halves together. A field that stays marked invalid after being
    // corrected is its own barrier.
    await phone.fill("9876543210");
    await expect(phone).toHaveAttribute("aria-invalid", "false");
    expect(await describedByText(phone)).toBe("");
  });
});

/* ────────────────────────────────────────────────────────────────────────────
 * 5. Low vision
 * ──────────────────────────────────────────────────────────────────────────── */

test("an unanswered list picker states its question above the AA contrast floor", async ({ page }) => {
  await signIn(page);

  /*
   * MEASURED ON /media, NOT ON A STAGE FORM, and the reason is worth keeping.
   *
   * This test first pointed at the reference picker on WORKSHOP_SETUP and looked for the word
   * "Search and select". It never found it — not because the fix had regressed but because the
   * seeded workshop is FULLY POPULATED, so that picker has a craft chosen and renders the record's
   * name in `ink-900` instead of the question in the placeholder rung. The test was asserting
   * against a state the fixture cannot produce, and a test that cannot reach the broken state is
   * not evidence of anything.
   *
   * The media page's record-type picker is unanswered on arrival by construction — it filters a
   * list rather than holding a saved answer, so no amount of seeding fills it in — and it is
   * addressable by an accessible name rather than by its own placeholder text, which is the string
   * under test and must not also be the thing we search for.
   */
  await page.goto("/media");

  const picker = page.getByRole("button", { name: /Linked record type/i }).first();
  await expect(picker).toBeVisible({ timeout: 45_000 });

  // The trigger's visible text, which with nothing chosen is the whole question the control asks.
  const question = picker.locator("span[class*='truncate']").first();
  await expect(question).toHaveText(/\S/);

  const ratio = await textContrast(question);
  // 4.5:1 is the WCAG 2.1 AA floor for text below 18.66px bold / 24px regular; this text is 14px.
  // At the `ink-300` placeholder rung this measures 2.44:1 and the test fails, which is the point.
  expect(ratio, `unanswered picker text measured ${ratio.toFixed(2)}:1 against its card`).toBeGreaterThanOrEqual(4.5);
});

/**
 * The report preview's page-break markers.
 *
 * WHO WAS BLOCKED. These say where the printed page will split, which is the one thing a designer
 * laying out a ministry submission has to know — whether the costing table is about to be cut in
 * half. The label is 11px, uppercase and letter-spaced, already the hardest shape to read, and it
 * was drawn in the `ink-300` placeholder rung at 2.43:1: barely half the AA floor, and invisible to
 * the low-vision designer who most needs to know where the boundary falls.
 *
 * `aria-hidden` on the marker is correct and is left alone — a screen reader has no use for a
 * visual pagination cue — which is exactly why this had to be caught by measuring paint rather than
 * by reading the accessibility tree.
 */
test("the report's page-break markers are legible", async ({ page }) => {
  await signIn(page);
  await page.goto(`/design-workshops/${WORKSHOP_ID}/report`);

  const label = page.locator(".rp-break-label").first();
  await expect(label).toBeVisible({ timeout: 60_000 });

  const ratio = await textContrast(label);
  expect(ratio, `page-break label measured ${ratio.toFixed(2)}:1 against the sheet`).toBeGreaterThanOrEqual(4.5);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 6. The media lightbox — a modal that took no focus
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * WHO WAS BLOCKED. `MediaLightbox` declares `role="dialog" aria-modal="true"` — a promise that the
 * rest of the page is inert and that focus is inside the dialog — and then kept none of it. Opening
 * a photo from the keyboard left focus on the tile UNDERNEATH a full-screen black overlay; the
 * first Tab walked into the page the dialog had just declared inert; and Escape dropped focus on an
 * unrelated text box, so a researcher who closed a preview lost their place in a long record.
 * Measured before the fix, the tab order left the dialog after three stops.
 *
 * The lightbox is reached from eight call sites — every media field the registry renders, plus the
 * artisan, product, tool, process and craft forms — so this is one component standing in for every
 * photo, recording and PDF preview in the app.
 *
 * The fixture attaches a real file rather than relying on seeded media: the deployment under test
 * has no uploads, and a preview that only exists when somebody remembered to seed one is a test
 * that quietly stops running.
 */
test("a media preview takes focus, keeps it, and gives it back", async ({ page }) => {
  await signIn(page);
  await page.goto("/artisans/new");

  /*
    THE GALLERY'S INPUT, NOT THE PAGE'S FIRST ONE — RE-ANCHORED 2026-08-23.

    This read `input[type="file"]').first()`, which was the media gallery's input for as long as the
    gallery was the only thing on this form that took a file. Identity-card scanning then added FOUR
    file inputs to the "Identity" section, and that section renders ABOVE "Artisan media" — so
    `.first()` handed the 1x1 PNG to the identity-card READER instead of the gallery. Measured, not
    guessed: the attach fired `POST /api/design-workshops/ocr/identity`, which answered
    `503 Identity-card scanning is switched off`, no tile was ever created, and the failure named the
    preview button while actually reporting that the photograph went somewhere else entirely.

    `[multiple]` is the discriminator and it is a property of what each control is FOR, not of where
    it sits: the gallery accepts a set of files, and every identity-card input takes exactly one card
    (all four are `multiple: false` — enumerated from the live page). A positional selector on this
    form is now a selector that will move again the next time a field is added above it.
  */
  const file = page.locator('input[type="file"][multiple]').first();
  await expect(file, "the artisan media gallery's own file input").toBeAttached({ timeout: 45_000 });
  await file.setInputFiles({
    name: "loom.png",
    mimeType: "image/png",
    // A 1x1 PNG: this asserts focus behaviour, and the pixels are never looked at.
    buffer: Buffer.from(
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
      "base64"
    )
  });

  const tile = page.getByRole("button", { name: /^Open preview for loom\.png$/ });
  await expect(tile).toBeVisible({ timeout: 30_000 });

  // Opened FROM THE KEYBOARD, because that is the path that was broken. A mouse user never notices
  // where focus went; a keyboard user cannot do anything else.
  await tile.focus();
  await page.keyboard.press("Enter");

  const dialog = page.getByRole("dialog", { name: /Preview loom\.png/i });
  await expect(dialog).toBeVisible({ timeout: 15_000 });

  // (a) FOCUS MOVED IN. Without this the reader is still on the tile behind the overlay.
  const landedInside = await dialog.evaluate((node) => node.contains(document.activeElement));
  expect(landedInside, "opening the preview must move focus into the dialog").toBe(true);

  // (b) FOCUS STAYS IN. Eight stops is more than the dialog has controls, so an untrapped dialog
  //     is certain to have leaked by the end of this loop.
  for (let press = 0; press < 8; press += 1) {
    await page.keyboard.press("Tab");
    const stillInside = await dialog.evaluate((node) => node.contains(document.activeElement));
    expect(stillInside, `Tab #${press + 1} left a dialog that claims aria-modal`).toBe(true);
  }

  // (c) FOCUS COMES BACK. Escape returns the reader to the tile they opened, not to the top of the
  //     document and not to whatever happened to be next in the form.
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden({ timeout: 15_000 });
  await expect(tile).toBeFocused();
});
