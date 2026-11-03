import { expect, type APIRequestContext, type Page } from "@playwright/test";

export const API = process.env.E2E_API_URL ?? "http://localhost:8000";

export const EMAIL = process.env.E2E_EMAIL ?? "";
export const PASSWORD = process.env.E2E_PASSWORD ?? "";

export const CREDENTIALS_MISSING = !EMAIL || !PASSWORD
  ? "Set E2E_EMAIL and E2E_PASSWORD (see e2e/README.md) to run the signed-in specs."
  : "";

const SESSION_COOKIE = "__Host-session";
const SESSION_MARKER_COOKIE = "__Host-session-marker";
const INSECURE_SESSION_COOKIE = "session";
const INSECURE_SESSION_MARKER_COOKIE = "session-marker";

export async function apiToken(
  request: APIRequestContext,
  email: string = EMAIL,
  password: string = PASSWORD
): Promise<string> {
  const res = await request.post(`${API}/api/auth/login`, { data: { email, password } });
  expect(res.ok(), `sign in as ${email}: ${res.status()} ${await res.text()}`).toBeTruthy();
  return (await res.json()).accessToken as string;
}

export function bearer(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}` };
}

export async function signInWithToken(page: Page, token: string) {
  const base = new URL(process.env.E2E_BASE_URL ?? "http://localhost:3000");
  const local = base.hostname === "localhost" || base.hostname === "127.0.0.1";
  const secure = base.protocol === "https:" || local;
  const url = secure ? `https://${base.host}` : base.origin;
  const common = { url, secure, sameSite: "Lax" as const };
  await page.context().addCookies([
    { name: secure ? SESSION_COOKIE : INSECURE_SESSION_COOKIE, value: token, httpOnly: true, ...common },
    {
      name: secure ? SESSION_MARKER_COOKIE : INSECURE_SESSION_MARKER_COOKIE,
      value: `e2e-${Date.now().toString(36)}`,
      httpOnly: false,
      ...common
    }
  ]);
}

export async function sessionTokenOf(page: Page): Promise<string> {
  const cookie = (await page.context().cookies()).find((entry) => entry.name === SESSION_COOKIE || entry.name === INSECURE_SESSION_COOKIE);
  return cookie ? decodeURIComponent(cookie.value) : "";
}

export async function signIn(page: Page, email: string = EMAIL, password: string = PASSWORD): Promise<string> {
  const token = await apiToken(page.request, email, password);
  await signInWithToken(page, token);
  await page.goto("/dashboard");
  return token;
}
