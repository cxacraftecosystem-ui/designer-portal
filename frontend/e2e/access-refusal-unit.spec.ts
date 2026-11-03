import { test, expect } from "@playwright/test";

const PENDING_DETAIL =
  "Your access request is awaiting administrator approval. This is not a password problem — an " +
  "administrator has to approve this address before you can sign in.";
const SUSPENDED_DETAIL = "Your access to this application has been suspended. Contact the administrator.";
const WRONG_CREDENTIAL_DETAIL = "Invalid email or password";

type Refusal = { status: number; detail: string; accessStatus?: string };

async function stubLogin(page: import("@playwright/test").Page, refusal: Refusal) {
  await page.route("**/api/auth/login", async (route) => {
    await route.fulfill({
      status: refusal.status,
      contentType: "application/json",
      headers: {
        "access-control-allow-origin": "*",
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

function signInAlert(page: import("@playwright/test").Page) {
  return page.locator("main").getByRole("alert");
}

async function signIn(page: import("@playwright/test").Page) {
  await page.goto("/login");
  await page.getByLabel(/email/i).first().fill("waiting@example.org");
  await page.getByLabel(/password/i).first().fill("password-that-is-long-enough");
  await page.getByRole("button", { name: /sign in/i }).first().click();
}

test("a person awaiting approval is told so, and not that their password is wrong", async ({ page }) => {
  await stubLogin(page, { status: 403, detail: PENDING_DETAIL, accessStatus: "PENDING" });
  await signIn(page);

  const alert = signInAlert(page);
  await expect(alert).toBeVisible();

  await expect(alert).toContainText(/waiting for an administrator/i);
  await expect(alert).toContainText(PENDING_DETAIL);
  await expect(alert).not.toContainText(/invalid email or password/i);
  await expect(alert).not.toContainText(/withdrawn|suspended/i);
});

test("a mistyped password still reads as a mistyped password", async ({ page }) => {
  await stubLogin(page, { status: 401, detail: WRONG_CREDENTIAL_DETAIL });
  await signIn(page);

  const alert = signInAlert(page);
  await expect(alert).toContainText(WRONG_CREDENTIAL_DETAIL);
  await expect(alert).not.toContainText(/waiting for an administrator/i);
  await expect(alert).not.toContainText(/awaiting administrator approval/i);
});

test("a suspended account is told its access ended, which is not the same as waiting", async ({ page }) => {
  await stubLogin(page, { status: 403, detail: SUSPENDED_DETAIL, accessStatus: "SUSPENDED" });
  await signIn(page);

  const alert = signInAlert(page);
  await expect(alert).toContainText(/suspended/i);
  await expect(alert).toContainText(SUSPENDED_DETAIL);
  await expect(alert).not.toContainText(/waiting for an administrator/i);
});

test("a refusal with no label says only what the server said", async ({ page }) => {
  await stubLogin(page, { status: 403, detail: PENDING_DETAIL });
  await signIn(page);

  const alert = signInAlert(page);
  await expect(alert).toContainText(PENDING_DETAIL);
  await expect(alert).not.toContainText(/waiting for an administrator/i);
  await expect(alert).not.toContainText(/suspended|withdrawn|not approved/i);
});
