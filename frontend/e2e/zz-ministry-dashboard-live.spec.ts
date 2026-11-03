import { expect, test, type Page } from "@playwright/test";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

async function signIn(page: Page) {
  await page.goto("/login");
  const identifier = page.getByRole("textbox", { name: /email, phone or empanelment/i });
  await identifier.waitFor({ state: "visible", timeout: 60_000 });
  await identifier.fill(EMAIL);
  await page.getByRole("textbox", { name: /^password$/i }).fill(PASSWORD);
  await page.getByRole("checkbox", { name: /i agree to the/i }).check();

  const submit = page.getByRole("button", { name: /^sign in$/i });
  await expect(submit, "the terms box was ticked and Sign In is still disabled").toBeEnabled({
    timeout: 10_000
  });
  await submit.click();
  try {
    await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 20_000 });
  } catch {
    await submit.click();
    await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 40_000 });
  }
}

const CARDS = [
  { title: "Designers", kind: "designers" },
  { title: "Assistant & Regional Directors", kind: "officers" },
  { title: "Inspectors", kind: "inspectors" }
] as const;

test.describe("the ministry dashboard's people registers", () => {
  test("all three cards open and fill, and none reports a failed read", async ({ page }) => {
    await signIn(page);
    await page.goto("/ministry-dashboard");

    await expect(page.getByRole("heading", { name: /ministry dashboard/i }).first()).toBeVisible({
      timeout: 30_000
    });

    for (const { title, kind } of CARDS) {
      const toggle = page.locator("button[aria-expanded]").filter({ hasText: title }).first();
      await expect(toggle, `the "${title}" card is not on the page at all`).toBeVisible();

      await expect(toggle, `"${title}" shipped open`).toHaveAttribute("aria-expanded", "false");
      await toggle.click();
      await expect(toggle).toHaveAttribute("aria-expanded", "true");

      const card = page.locator("section").filter({ has: toggle });
      await expect(
        card.getByText(/could not be read|couldn't be loaded/i),
        `"${title}" reported a failed read — the route is missing, refused or erroring`
      ).toHaveCount(0, { timeout: 20_000 });

      await expect(card.getByText("Loading...", { exact: true })).toHaveCount(0, { timeout: 30_000 });

      const table = card.getByRole("table");
      const empty = card.getByText(`No ${kind} in this scope`, { exact: true });
      const rendered = (await table.count()) > 0 ? table.first() : empty.first();
      await expect(
        rendered,
        `"${title}" opened and rendered neither a table of people nor an empty state`
      ).toBeVisible({ timeout: 20_000 });

      await expect(card.getByText(/register is built FROM/i).first()).toBeVisible();

      if (kind === "designers") {
        const shortfall = card.getByText(/have not created an account yet/i);
        const otherRole = card.getByText(/under a role this register does not list/i);
        const listedLine = card.getByText(/empanelled designers are listed above/i);
        if ((await shortfall.count()) > 0 || (await otherRole.count()) > 0) {
          await expect(
            listedLine.first(),
            "the Designers card reports a shortfall without saying how many of the roster ARE listed"
          ).toBeVisible();
        }
      }
    }
  });

  test("the workshop register beneath them still loads", async ({ page }) => {
    await signIn(page);
    await page.goto("/ministry-dashboard");
    await expect(page.getByRole("heading", { name: /ministry dashboard/i }).first()).toBeVisible({
      timeout: 30_000
    });
    await expect(page.getByText(/Updated at /i).first()).toBeVisible({ timeout: 30_000 });
  });
});
