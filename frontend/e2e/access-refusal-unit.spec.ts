import { test, expect } from "@playwright/test";

/**
 * THE TWO ANSWERS THE SIGN-IN PAGE MUST NEVER GIVE INTERCHANGEABLY.
 *
 * ── THE RULING ───────────────────────────────────────────────────────────────────────────────────
 *
 * "Wrong password and pending approval should be differentiated." A person waiting on an
 * administrator, told "Invalid email or password", resets a password that was never wrong — twice —
 * and then telephones somebody who cannot help them, because this product has no registration page
 * and no password-reset email, so the vague answer leaves them with no next action that exists. The
 * account-enumeration cost of answering honestly was weighed by the product owner and accepted.
 *
 * ── WHY IT IS A STUBBED SPEC AND NOT A LIVE SIGN-IN ──────────────────────────────────────────────
 *
 * Every case here needs an account in a particular state on the server — waiting, refused,
 * suspended — and creating three of those and then signing in as each is a fixture that would take
 * minutes, need admin credentials, and leave rows behind. The behaviour under test is entirely in
 * the browser: given a 403 carrying a status label, what does the card say? So the network answer is
 * stubbed at the route and the page is the thing being tested. The SERVER's half of the same
 * contract — that those labels are actually sent, and that CORS lets the browser read them — is
 * pinned in `backend/tests/test_platform_access_gate.py`, against a real database.
 *
 * NEEDS NO CREDENTIALS AND NO SEEDED DATA, on purpose: every sign-in it attempts is one that must
 * be refused, so it runs on a clean checkout, which is where the regression it guards would
 * otherwise land unnoticed.
 */

/** The API's own sentences, copied verbatim from `backend/app/api/routes/auth.py`. */
const PENDING_DETAIL =
  "Your access request is awaiting administrator approval. This is not a password problem — an " +
  "administrator has to approve this address before you can sign in.";
const SUSPENDED_DETAIL = "Your access to this application has been suspended. Contact the administrator.";
const WRONG_CREDENTIAL_DETAIL = "Invalid email or password";

type Refusal = { status: number; detail: string; accessStatus?: string };

/** Answer the next sign-in with one refusal, exactly as the API would shape it. */
async function stubLogin(page: import("@playwright/test").Page, refusal: Refusal) {
  await page.route("**/api/auth/login", async (route) => {
    await route.fulfill({
      status: refusal.status,
      contentType: "application/json",
      headers: {
        // THE STUB HAS TO SPEAK CORS OR IT IS NOT REPRODUCING THE REAL ANSWER. The page is served
        // from localhost:3000 and the API from localhost:8000, so every sign-in is cross-origin,
        // and a browser lets JavaScript read only a handful of "simple" headers off such a response
        // unless the server names the rest in `Access-Control-Expose-Headers`. Without this line the
        // label is INVISIBLE to the page and every case below falls back to the unlabelled panel —
        // which is exactly the production bug this pair of lines exists to model, and it is why
        // `expose_headers` had to be added to the CORS middleware in backend/app/main.py.
        "access-control-allow-origin": "*",
        // The label the clients branch on. Absent for the unclassified case below, which is what an
        // older deployment or a header-stripping proxy looks like.
        ...(refusal.accessStatus
          ? {
              "x-access-status": refusal.accessStatus,
              "access-control-expose-headers": "X-Access-Status"
            }
          : {})
      },
      body: JSON.stringify({ detail: refusal.detail })
    });
  });
}

/**
 * The refusal panel on the sign-in card.
 *
 * SCOPED TO `main` RATHER THAN `getByRole("alert")` ALONE. Next.js mounts its own route announcer —
 * an empty `<div role="alert">` — in every page, so an unscoped query matches two nodes and fails
 * on strict mode with a message about the announcer that says nothing about this feature. The card
 * lives in the page's `main`; the announcer does not.
 */
function signInAlert(page: import("@playwright/test").Page) {
  return page.locator("main").getByRole("alert");
}

async function signIn(page: import("@playwright/test").Page) {
  await page.goto("/login");
  await page.getByLabel(/email/i).first().fill("waiting@example.org");
  // Eight characters: the form refuses anything shorter without asking the server, which would mean
  // no request and no answer to assert. See login-credential-floor-unit.spec.ts.
  await page.getByLabel(/password/i).first().fill("password-that-is-long-enough");
  await page.getByRole("button", { name: /sign in/i }).first().click();
}

test("a person awaiting approval is told so, and not that their password is wrong", async ({ page }) => {
  await stubLogin(page, { status: 403, detail: PENDING_DETAIL, accessStatus: "PENDING" });
  await signIn(page);

  const alert = signInAlert(page);
  await expect(alert).toBeVisible();

  // THE THREE ASSERTIONS THAT ARE THE FEATURE.
  // 1. The heading says what is happening, because a bare sentence in a box above a "Sign In"
  //    button is read as a validation error and dismissed.
  await expect(alert).toContainText(/waiting for an administrator/i);
  // 2. The server's own sentence, verbatim. It is the only text that knows why THIS attempt was
  //    refused, and nothing the bundle could write in its place would know it.
  await expect(alert).toContainText(PENDING_DETAIL);
  // 3. And the answer a mistyped password gets is NOWHERE on the card. This is the assertion that
  //    fails if somebody ever "hardens" the refusal by collapsing it into the credential error.
  await expect(alert).not.toContainText(/invalid email or password/i);
  await expect(alert).not.toContainText(/withdrawn|suspended/i);
});

test("a mistyped password still reads as a mistyped password", async ({ page }) => {
  await stubLogin(page, { status: 401, detail: WRONG_CREDENTIAL_DETAIL });
  await signIn(page);

  const alert = signInAlert(page);
  await expect(alert).toContainText(WRONG_CREDENTIAL_DETAIL);
  // The other half of "differentiated": a typo must not be dressed up as an account problem either,
  // or everybody who fat-fingers a password is sent to email an administrator.
  await expect(alert).not.toContainText(/waiting for an administrator/i);
  await expect(alert).not.toContainText(/awaiting administrator approval/i);
});

test("a suspended account is told its access ended, which is not the same as waiting", async ({ page }) => {
  await stubLogin(page, { status: 403, detail: SUSPENDED_DETAIL, accessStatus: "SUSPENDED" });
  await signIn(page);

  const alert = signInAlert(page);
  await expect(alert).toContainText(/suspended/i);
  await expect(alert).toContainText(SUSPENDED_DETAIL);
  // "Nobody has answered you yet" and "somebody answered, and the answer was no" are different
  // facts, and only one of them is worth waiting for.
  await expect(alert).not.toContainText(/waiting for an administrator/i);
});

test("a refusal with no label says only what the server said", async ({ page }) => {
  // An older API, or a proxy that strips unknown headers, or a deployment whose CORS configuration
  // does not expose it. The card must fall back to neutral chrome around the server's own words —
  // never to a guessed heading, because the wrong heading here tells a person waiting to be
  // approved that their access was withdrawn.
  await stubLogin(page, { status: 403, detail: PENDING_DETAIL });
  await signIn(page);

  const alert = signInAlert(page);
  await expect(alert).toContainText(PENDING_DETAIL);
  await expect(alert).not.toContainText(/waiting for an administrator/i);
  await expect(alert).not.toContainText(/suspended|withdrawn|not approved/i);
});
