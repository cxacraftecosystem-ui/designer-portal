import { expect, test } from "@playwright/test";

/**
 * The 404 page, and the one promise its copy has to keep.
 *
 * WHY THIS IS A BROWSER SPEC AND NEEDS NO CREDENTIALS. Every other spec in this suite signs in; this
 * one must not, because the signed-out visitor IS one of the two readers the page is written for —
 * somebody who followed a dead link out of a colleague's message. Nothing here touches the API
 * either: `AuthProvider`'s `/me` probe fails on a machine with no backend exactly as it does for a
 * visitor with no token, and both land on the same branch. So this file runs against nothing but
 * `next start`.
 *
 * WHAT IT IS DEFENDING, in order of how badly it would hurt to lose it:
 *
 *  1. **That the page is reached at all.** A `not-found.tsx` that Next.js never routes to is a file
 *     that looks like a fix in the diff and changes nothing on the deployed site — the reader still
 *     gets the framework's bare "404 | This page could not be found" on a white page. The two
 *     `goto`s below are the proof, and the second is the one that matters: `/crafts/{id}` is a
 *     perfectly ordinary link to a record — crafts, workshops and processes have no `/[id]` route,
 *     their ids travel as `?edit=` — so a link somebody actually sends lands on an unmatched URL
 *     rather than on any page's own not-found handling.
 *  2. **That the copy cannot be used to enumerate records.** The API deliberately answers 404 and
 *     not 403 for a record the caller may not see (`_load_or_404` in data_browser.py,
 *     `load_workshop_or_404` in design_workshops.py), so that asking about an id tells you nothing
 *     about whether it exists. A 404 page that said "this record has been deleted" would hand that
 *     back in the client, and it would read as a helpful improvement to whoever wrote it. The
 *     assertion is on the DISJUNCTION and on the sentence that refuses to resolve it, because those
 *     are the two halves an edit would break independently: dropping "or it may not be one this
 *     account can open" asserts the record existed, and dropping "Nothing here can tell you which"
 *     leaves a reader to assume the first clause is the real answer.
 *  3. **That a real route is not swallowed.** A `not-found.tsx` is cheap to reach by accident. If
 *     `/login` ever renders this panel, every visitor is locked out of the app and the 404 page is
 *     the thing that did it.
 */

/** Two addresses that match no route: a plainly wrong one, and a plausible link to a real record. */
const NONSENSE = "/this-route-does-not-exist";
const RECORD_SHAPED = "/crafts/cmsik2jg8000eh8xc1lcy661a";

test("an unmatched URL renders the app's own 404, with a 404 status", async ({ page }) => {
  const response = await page.goto(NONSENSE);

  // The status, not just the pixels. A page that looks right but answers 200 tells every crawler,
  // link checker and monitor that a dead bookmark is a working page.
  expect(response?.status()).toBe(404);

  await expect(page.getByRole("heading", { level: 1, name: "There is nothing at this address" })).toBeVisible();
});

test("the 404 names the deleted record without confirming it ever existed", async ({ page }) => {
  await page.goto(NONSENSE);

  const panel = page.getByRole("main");

  // The disjunction. Both halves, in one sentence, so neither can be dropped on its own.
  await expect(panel).toContainText("it may have been deleted, or it may not be one this account can open");

  // And the refusal to resolve it, which is what stops a reader treating the first half as the answer.
  await expect(panel).toContainText("Nothing here can tell you which");
});

test("an ordinary link to a record lands on it too, and shows the address that was asked for", async ({ page }) => {
  const response = await page.goto(RECORD_SHAPED);

  expect(response?.status()).toBe(404);
  await expect(page.getByRole("heading", { level: 1, name: "There is nothing at this address" })).toBeVisible();

  // The address is read from the browser after mount, never rendered into the prerendered HTML — so
  // this also asserts that hydration actually filled it in rather than leaving the build's own path.
  await expect(page.getByRole("main")).toContainText(RECORD_SHAPED);
});

test("a signed-out visitor is offered the front door, and told which app they are in", async ({ page }) => {
  await page.goto(NONSENSE);

  await expect(page.getByRole("link", { name: "Sign in" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Go to the home page" })).toBeVisible();

  // Not the signed-in reader's route: offering "Back to dashboard" to somebody with no session
  // sends them to a redirect, which is a second dead end dressed as a way out.
  await expect(page.getByRole("link", { name: "Back to dashboard" })).toHaveCount(0);

  // The framework's 404 has no branding at all. This one says what it is.
  await expect(page.getByRole("main")).toContainText("Design Prototype Workshop");
});

test("a real route still renders itself", async ({ page }) => {
  const response = await page.goto("/login");

  expect(response?.status()).toBe(200);
  await expect(page.getByRole("heading", { level: 1, name: "There is nothing at this address" })).toHaveCount(0);
});
