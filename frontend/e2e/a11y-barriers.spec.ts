import { expect, test, type Locator, type Page } from "@playwright/test";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

const WORKSHOP_ID = process.env.E2E_DW_ID ?? "cmsik2jg8000eh8xc1lcy661a";

const STAGE_KEY = "WORKSHOP_SETUP";
const ENTITY_KEY = "workshopSetup";
const FIELD_LABEL = "Workshop title";

const DATE_FIELD_LABEL = "Start date";

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

  try {
    await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 30_000 });
  } catch {
    await submit.click();
    await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 60_000 });
  }
}

async function describedByText(control: Locator): Promise<string> {
  return control.evaluate((node) => {
    const ids = (node.getAttribute("aria-describedby") ?? "").split(/\s+/).filter(Boolean);
    return ids
      .map((id) => document.getElementById(id)?.textContent?.trim() ?? "")
      .filter(Boolean)
      .join(" ");
  });
}

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

test("a refused sign-in is announced, not merely painted red", async ({ page }) => {
  await page.goto("/login");
  await page.getByPlaceholder("Enter your email").fill("nobody@example.org");
  await page.getByPlaceholder("Enter your password").fill("definitely-not-the-password");
  await page.getByRole("button", { name: /sign in/i }).click();

  const announcement = page.getByRole("alert").filter({ hasText: /invalid|unable|incorrect|password/i });
  await expect(announcement).toBeVisible({ timeout: 30_000 });

  await expect(page).toHaveURL(/\/login/);
});

test.describe("the stage form's hints and refusals reach a screen reader", () => {
  test("a field's help line is what the control is described by", async ({ page }) => {
    await signIn(page);
    await page.goto(`/design-workshops/${WORKSHOP_ID}/stages/${STAGE_KEY}`);

    const title = page.getByLabel(new RegExp(`^${FIELD_LABEL}`, "i")).first();
    await expect(title).toBeVisible({ timeout: 45_000 });

    const described = await describedByText(title);
    expect(described.length, "the help line must be reachable through aria-describedby").toBeGreaterThan(0);
  });

  test("a per-field refusal is announced, attached, and marks the box invalid", async ({ page }) => {
    await signIn(page);

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

    await title.fill("Bagru block printing — accessibility regression");
    await page.getByRole("button", { name: /^Save stage$/ }).click();

    const alert = page.getByRole("alert").filter({ hasText: REFUSAL });
    await expect(alert).toBeVisible({ timeout: 30_000 });

    await expect(title).toHaveAttribute("aria-invalid", "true");

    expect(await describedByText(title)).toContain(REFUSAL);

    const startDate = page.getByLabel(new RegExp(`^${DATE_FIELD_LABEL}`, "i")).first();
    await expect(startDate).toHaveAttribute("aria-invalid", "true");
    expect(await describedByText(startDate)).toContain(DATE_REFUSAL);
  });
});

test.describe("the artisan form can be filled in without seeing it", () => {
  test("every required numbered-list row has a name and an ordinal", async ({ page }) => {
    await signIn(page);
    await page.goto("/artisans/new");

    const dos = page.getByRole("group", { name: /^Do's/ });
    await expect(dos).toBeVisible({ timeout: 45_000 });
    const donts = page.getByRole("group", { name: /^Don'ts/ });
    await expect(donts).toBeVisible();

    await expect(dos.getByRole("textbox", { name: "Point 1" })).toBeVisible();

    await dos.getByRole("textbox", { name: "Point 1" }).fill("Soak the cloth before printing");
    await dos.getByRole("button", { name: /Add point/ }).click();
    await expect(dos.getByRole("textbox", { name: "Point 2" })).toBeVisible();
    await expect(dos.getByRole("button", { name: "Remove point 2" })).toBeVisible();

    const nameless = await page.evaluate(() => {
      const form = document.querySelector("form");
      if (!form) return ["no form on the page"];
      const missing: string[] = [];
      for (const control of Array.from(form.querySelectorAll("input, textarea"))) {
        const el = control as HTMLInputElement;
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

    await phone.fill("98765");

    await expect(phone).toHaveAttribute("aria-invalid", "true");
    await expect(page.getByRole("alert").filter({ hasText: /10-digit/ })).toBeVisible();
    expect(await describedByText(phone)).toMatch(/10-digit/);

    await phone.fill("9876543210");
    await expect(phone).toHaveAttribute("aria-invalid", "false");
    expect(await describedByText(phone)).toBe("");
  });
});

test("an unanswered list picker states its question above the AA contrast floor", async ({ page }) => {
  await signIn(page);

  await page.goto("/media");

  const picker = page.getByRole("button", { name: /Linked record type/i }).first();
  await expect(picker).toBeVisible({ timeout: 45_000 });

  const question = picker.locator("span[class*='truncate']").first();
  await expect(question).toHaveText(/\S/);

  const ratio = await textContrast(question);
  expect(ratio, `unanswered picker text measured ${ratio.toFixed(2)}:1 against its card`).toBeGreaterThanOrEqual(4.5);
});

test("the report's page-break markers are legible", async ({ page }) => {
  await signIn(page);
  await page.goto(`/design-workshops/${WORKSHOP_ID}/report`);

  const label = page.locator(".rp-break-label").first();
  await expect(label).toBeVisible({ timeout: 60_000 });

  const ratio = await textContrast(label);
  expect(ratio, `page-break label measured ${ratio.toFixed(2)}:1 against the sheet`).toBeGreaterThanOrEqual(4.5);
});

test("a media preview takes focus, keeps it, and gives it back", async ({ page }) => {
  await signIn(page);
  await page.goto("/artisans/new");

  const file = page.locator('input[type="file"][multiple]').first();
  await expect(file, "the artisan media gallery's own file input").toBeAttached({ timeout: 45_000 });
  await file.setInputFiles({
    name: "loom.png",
    mimeType: "image/png",
    buffer: Buffer.from(
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
      "base64"
    )
  });

  const tile = page.getByRole("button", { name: /^Open preview for loom\.png$/ });
  await expect(tile).toBeVisible({ timeout: 30_000 });

  await tile.focus();
  await page.keyboard.press("Enter");

  const dialog = page.getByRole("dialog", { name: /Preview loom\.png/i });
  await expect(dialog).toBeVisible({ timeout: 15_000 });

  const landedInside = await dialog.evaluate((node) => node.contains(document.activeElement));
  expect(landedInside, "opening the preview must move focus into the dialog").toBe(true);

  for (let press = 0; press < 8; press += 1) {
    await page.keyboard.press("Tab");
    const stillInside = await dialog.evaluate((node) => node.contains(document.activeElement));
    expect(stillInside, `Tab #${press + 1} left a dialog that claims aria-modal`).toBe(true);
  }

  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden({ timeout: 15_000 });
  await expect(tile).toBeFocused();
});
