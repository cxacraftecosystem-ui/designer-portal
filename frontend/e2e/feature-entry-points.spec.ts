import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

/**
 * A FEATURE A RESEARCHER CANNOT FIND IS A FEATURE THAT WAS NOT BUILT.
 *
 * Both destinations below shipped complete and were reachable only by typing the URL: nothing in
 * the navigation and nothing on the dashboard pointed at either. That is the kind of gap that
 * reopens silently — a nav array is edited for one reason and an entry goes with it, and no build,
 * type check or lint has an opinion about a menu that is one item shorter than it was.
 *
 * So both routes are asserted in both of the two places a researcher looks: the dashboard tiles
 * (which is where Android puts everything) and the navigation sheet (the keyboard-reachable list of
 * every destination the account qualifies for). Following each link and seeing the page's own
 * heading is what makes this a test of an ENTRY POINT rather than of a string.
 *
 * Read-only: navigation only, nothing submitted.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

/** Both are open to any signed-in user, so both must appear for every account that can sign in. */
const DESTINATIONS = [
  { href: "/map", tile: "Map", nav: "Map", group: "Miscellaneous", heading: /Where the work comes from/i },
  {
    href: "/questionnaire/consolidated",
    tile: "Consolidated questionnaire",
    nav: "Consolidated questionnaire",
    group: "Miscellaneous",
    heading: /Consolidated questionnaire/i
  }
] as const;

/**
 * ── WHY THESE TESTS NOW CLICK BEFORE THEY LOOK, AND WHY THAT IS NOT A WEAKENING ────────────────
 *
 * Until 2026-09-20 both assertions ran straight after `goto("/dashboard")`: every tile was on the
 * page at first paint, so `toBeVisible()` was the whole check. The owner then ruled that each mega
 * card *"stay minimized unless it is clicked upon"*, and `Map` and `Consolidated questionnaire` both
 * live under `Miscellaneous` — so on a first visit they are inside a shut card, and
 * `MegaCard`'s panel is not merely hidden but UNMOUNTED while collapsed.
 *
 * The temptation is to read that as this spec's premise being repealed. It is not. This file exists
 * because *"a feature a user cannot find is a feature that was not built"*, and the question it asks
 * is whether there is a path from the dashboard to the destination that a person can actually walk.
 * One click on a card that names its own group, states what it holds and counts its contents is such
 * a path. Zero clicks on a wall of twenty-one tiles was arguably a worse one.
 *
 * So the check got STRONGER rather than weaker: it now asserts the group card exists, that it is
 * shut on a first visit (which is the ruling, and the thing that would silently regress), that
 * opening it is one click, and only then that the tile and its link are there. If somebody files
 * `Map` under a group that does not exist, or ships the cards open, or breaks the toggle, this fails
 * — and none of those three were observable here before.
 */
async function openGroup(page: Page, title: string) {
  const toggle = page.getByRole("button", { name: new RegExp(title, "i") });
  await expect(toggle).toBeVisible();
  // The ruling, asserted rather than assumed. A build that shipped the cards open would otherwise
  // pass every line below it without anybody noticing the requirement had been dropped.
  await expect(toggle).toHaveAttribute("aria-expanded", "false");
  await toggle.click();
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
}

/**
 * The dashboard tile whose display-font label is exactly `label`.
 *
 * Reached from the label OUTWARDS: the nearest ancestor `<div>` that contains a link is the card
 * itself (`DashboardCard` renders a `GlassSurface`, which is a plain div, holding the label and
 * then the action links). Filtering divs by text and taking `.last()` instead lands on the label
 * div, which has no link inside it at all.
 */
function tile(page: Page, label: string) {
  return page.getByText(label, { exact: true }).locator("xpath=ancestor::div[.//a][1]");
}

test.describe("Entry points", () => {
  for (const destination of DESTINATIONS) {
    test(`the dashboard offers a tile that opens ${destination.href}`, async ({ page }) => {
      await signIn(page);
      await page.goto("/dashboard");
      await expect(page.getByRole("heading", { name: "What would you like to do?" })).toBeVisible();

      // Collapsed on a first visit, by owner ruling. See `openGroup` for why clicking here is not a
      // weakening of what this file is for.
      await openGroup(page, destination.group);

      // The tile's own "Open" link, scoped to the card carrying the label — the grid holds a dozen
      // buttons with the same word on them.
      const card = tile(page, destination.tile);
      await expect(card).toBeVisible();
      const open = card.getByRole("link").first();
      await expect(open).toHaveAttribute("href", destination.href);

      await open.click();
      await page.waitForURL(`**${destination.href}`);
      await expect(page.getByRole("heading", { name: destination.heading }).first()).toBeVisible();
    });

    test(`the navigation sheet lists ${destination.nav}`, async ({ page }) => {
      await signIn(page);
      await page.goto("/dashboard");

      await page.getByRole("button", { name: /toggle navigation menu/i }).click();
      const sheet = page.getByRole("dialog", { name: "Navigation" });
      await expect(sheet).toBeVisible();

      const link = sheet.getByRole("link", { name: destination.nav, exact: true });
      await expect(link).toHaveAttribute("href", destination.href);

      await link.click();
      await page.waitForURL(`**${destination.href}`);
      await expect(page.getByRole("heading", { name: destination.heading }).first()).toBeVisible();
    });
  }

  test("the consolidated view does not also light up 'Take interview' as the current page", async ({ page }) => {
    // Nesting a destination under an existing one (/questionnaire/consolidated inside
    // /questionnaire) makes a prefix test mark both entries current, which tells a screen reader
    // the reader is in two places at once. The most specific match wins.
    await signIn(page);
    await page.goto("/questionnaire/consolidated");

    await page.getByRole("button", { name: /toggle navigation menu/i }).click();
    const sheet = page.getByRole("dialog", { name: "Navigation" });
    await expect(sheet.getByRole("link", { name: "Consolidated questionnaire", exact: true })).toHaveAttribute(
      "aria-current",
      "page"
    );
    await expect(sheet.getByRole("link", { name: "Take interview", exact: true })).not.toHaveAttribute(
      "aria-current",
      /.*/
    );
  });
});
