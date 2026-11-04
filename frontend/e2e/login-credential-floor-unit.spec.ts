import { test, expect } from "@playwright/test";

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

    await page.waitForTimeout(1500);

    expect(
      loginRequests,
      "the sign-in form sent a password the API defines as too short; the client-side floor is gone"
    ).toHaveLength(0);

    const password = page.locator("#password");
    expect(await password.evaluate((el: HTMLInputElement) => el.checkValidity())).toBe(false);
    expect(await password.evaluate((el: HTMLInputElement) => el.validationMessage)).toMatch(credential.expect);

    expect(new URL(page.url()).pathname).toContain("/login");
  });
}
