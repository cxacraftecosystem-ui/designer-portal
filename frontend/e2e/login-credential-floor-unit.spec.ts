import { test, expect } from "@playwright/test";

/**
 * THE SIGN-IN FORM REFUSES AN IMPOSSIBLE PASSWORD ITSELF, WITHOUT ASKING THE SERVER.
 *
 * `POST /api/auth/login` validates `password` with a minimum length of 8 and answers anything
 * shorter with `422 {"type":"string_too_short","ctx":{"min_length":8}}` — a shape the app has no
 * rendering for, because it is a schema error rather than an authentication error. The login page
 * therefore carries the same floor natively (`required minLength={8}` on the password input), so a
 * value that could not possibly authenticate is stopped in the browser and the user is told by the
 * control that is wrong rather than by a 422 the page cannot explain.
 *
 * WHY THIS IS PINNED, AND WHAT IT COST. `zzz-consolidated-shot.spec.ts` defaulted its password to
 * `""` when E2E_PASSWORD was unset. It filled that into the form, clicked Sign In, and waited for a
 * navigation — and because this floor did its job, no request was ever sent and no navigation ever
 * came. The spec spent ninety seconds dying inside `waitForURL` and reported a broken sign-in, which
 * is the single most expensive kind of false accusation a suite can make: it sent people reading
 * `AuthProvider`, the protected-route guard and `lib/api.ts` looking for a session bug that did not
 * exist. The behaviour under test here is CORRECT, and it is exactly the correctness that made the
 * failure look like a defect. Anyone deleting `required` or `minLength` to "let the server decide"
 * would trade a clear field-level refusal for an unrenderable 422 — and this test is the argument
 * against it.
 *
 * NEEDS NO CREDENTIALS, on purpose: every password it types is one that must be refused, so there is
 * no account to know about and nothing to skip for. That is what lets this run on a clean checkout,
 * which is where the regression it guards would otherwise land unnoticed.
 */

/** Values the API's `min_length=8` cannot accept, whatever account they are paired with. */
const IMPOSSIBLE = [
  { label: "empty", value: "", expect: /fill out this field/i },
  { label: "shorter than the 8-character minimum", value: "short", expect: /lengthen|8 characters/i }
];

for (const credential of IMPOSSIBLE) {
  test(`a password that is ${credential.label} is refused without a request`, async ({ page }) => {
    const loginRequests: string[] = [];
    page.on("request", (request) => {
      if (request.method() === "POST" && request.url().includes("/auth/login")) {
        loginRequests.push(request.url());
      }
    });

    await page.goto("/login");
    await page.getByLabel(/email/i).first().fill("admin@example.com");
    await page.getByLabel(/password/i).first().fill(credential.value);
    await page.getByRole("button", { name: /sign in|log in/i }).first().click();

    /*
     * A settle window, not a wait-for-condition: the assertion is that something NEVER happens, and
     * there is no event to await for an absence. Kept short because the request — if the floor were
     * removed — would be issued on the click, not seconds later.
     */
    await page.waitForTimeout(1500);

    // The load-bearing assertion. Not "an error appeared" but "the network was never touched":
    // a rendered error would also be satisfied by submitting and displaying the 422, which is
    // precisely the behaviour this floor exists to prevent.
    expect(
      loginRequests,
      "the sign-in form sent a password the API defines as too short; the client-side floor is gone"
    ).toHaveLength(0);

    // And the user is not left guessing why nothing happened: the browser marks the field invalid
    // and says which rule was broken.
    const password = page.locator("#password");
    expect(await password.evaluate((el: HTMLInputElement) => el.checkValidity())).toBe(false);
    expect(await password.evaluate((el: HTMLInputElement) => el.validationMessage)).toMatch(credential.expect);

    // Still on the front door. This is the assertion whose failure was misread as a session bug.
    expect(new URL(page.url()).pathname).toContain("/login");
  });
}
