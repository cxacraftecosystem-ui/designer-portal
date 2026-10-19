import { expect, test } from "@playwright/test";

import { focusField } from "@/lib/forms";

// THE ONE COMPLETENESS CHECK THAT NEEDS A BROWSER: focusField scrolls and focuses a real DOM node.
// Kept out of record-completeness-unit.spec.ts because a `*-unit.spec.ts` runs with no browser
// installed (checks.yml, "Unit specs"); this one runs with the full e2e suite. It needs no server.

test("focusField scrolls to and focuses the anchored field, skipping a mirror twin", async ({ page }) => {
  await page.setContent(`
    <div style="height:2400px"></div>
    <div data-field="craftStartDate">
      <input tabindex="-1" aria-hidden="true" name="mirror" />
      <input id="typed" name="craftStartDate" />
    </div>
    <div style="height:2400px"></div>`);
  const source = focusField.toString();
  const found = await page.evaluate(([src, key]) => {
    const fn = new Function(`return (${src})`)() as (k: string, r?: ParentNode) => boolean;
    return fn(key, document);
  }, [source, "craftStartDate"] as const);
  expect(found).toBe(true);
  await expect(page.locator("#typed")).toBeFocused();
  await expect(page.locator('[data-field="craftStartDate"]')).toHaveAttribute("data-flash", "true");
  // Smooth scrolling (no reduced motion here) lands over a few frames, so poll for it.
  await expect
    .poll(() =>
      page.locator("#typed").evaluate((el) => {
        const box = el.getBoundingClientRect();
        return box.top >= 0 && box.bottom <= window.innerHeight;
      })
    )
    .toBe(true);
  const missing = await page.evaluate(([src]) => (new Function(`return (${src})`)() as (k: string) => boolean)("nowhere"), [source] as const);
  expect(missing).toBe(false);
});
