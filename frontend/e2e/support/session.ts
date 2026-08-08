import { expect, type APIRequestContext, type Page } from "@playwright/test";

/**
 * The one place a spec learns who it is and where the API lives.
 *
 * Before this module every spec carried its own copy of four lines, and the copies had drifted:
 * some waited 60 seconds for the login form, some replayed a captured localStorage, one had a
 * retry around the submit button and forty did not. Drift in a helper nobody owns is how a suite
 * ends up with failures that name the sign-in page while complaining about something else.
 */

/** Backend ORIGIN, no `/api` suffix — the specs append it themselves. */
export const API = process.env.E2E_API_URL ?? "http://localhost:8000";

export const EMAIL = process.env.E2E_EMAIL ?? "";
export const PASSWORD = process.env.E2E_PASSWORD ?? "";

/**
 * The reason to skip, or "" when the suite can run.
 *
 * Returned as a string rather than thrown so a spec can hand it straight to `test.skip`, which is
 * the behaviour this suite wants: a run that cannot sign in has learned nothing about the product,
 * and a red result that means "you forgot an env var" trains people to ignore red results.
 */
export const CREDENTIALS_MISSING = !EMAIL || !PASSWORD
  ? "Set E2E_EMAIL and E2E_PASSWORD (see e2e/README.md) to run the signed-in specs."
  : "";

/** localStorage key `lib/api.ts` reads the bearer token from. */
const TOKEN_KEY = "field_repo_token";

/**
 * A bearer token, straight from the API.
 *
 * Takes an `APIRequestContext` rather than a `Page` so it works in `beforeAll` — seeding a fixture
 * before any browser exists is the pattern the newer specs use, and a `page`-bound helper cannot.
 */
export async function apiToken(
  request: APIRequestContext,
  email: string = EMAIL,
  password: string = PASSWORD
): Promise<string> {
  const res = await request.post(`${API}/api/auth/login`, { data: { email, password } });
  expect(res.ok(), `sign in as ${email}: ${res.status()} ${await res.text()}`).toBeTruthy();
  return (await res.json()).accessToken as string;
}

/** `Authorization` header for the API helpers, so no spec has to spell the scheme out. */
export function bearer(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}` };
}

/**
 * Sign in by planting the token the app reads, rather than driving the login form.
 *
 * THE HYDRATION RACE THIS AVOIDS. On a dev server compiling under load the sign-in button paints
 * before React has attached its handler, so a click submits nothing at all and the spec dies sixty
 * seconds later inside `waitForURL` — a failure that names `/login` while actually reporting that
 * the page was not yet interactive. It was observed three times while these specs were being
 * written, always on a busy machine, never in isolation. Not clicking the button at all is the only
 * fix that does not depend on guessing how long hydration takes.
 *
 * THE THROTTLE THIS AVOIDS. The API rate-limits repeated logins from one address. A spec that drives
 * the form AND calls the API for a token logs the same account in twice per test; past a few tests
 * the form re-renders `/login` with no error the spec can see, which reads as "wrong credentials"
 * and is not.
 *
 * `addInitScript`, NOT `goto` + `evaluate`: `AuthProvider.refreshMe` fires an anonymous `/me` probe
 * on mount, that probe 401s, and `refreshMe` clears the token on 401 — including one written a few
 * milliseconds earlier. An init script runs before any page script on every navigation, so the
 * token is simply there from the start.
 *
 * The login form is not left untested by this: `a11y-barriers.spec.ts` drives it deliberately, which
 * is where a test of the form belongs.
 */
export async function signInWithToken(page: Page, token: string) {
  await page.addInitScript(
    ([key, value]) => {
      window.localStorage.setItem(key, value);
    },
    [TOKEN_KEY, token] as const
  );
}

/**
 * Sign in and LAND ON a signed-in page — the whole ceremony, for specs that just need to be someone.
 *
 * The navigation is not decoration. The form-driving helper this replaces ended with the browser
 * sitting on `/dashboard`, because submitting the form redirected there, and a good number of call
 * sites quietly depend on that: they follow `signIn(page)` with `page.evaluate(...)` reaching into
 * `localStorage` or IndexedDB, which on `about:blank` is a different origin — or no origin at all.
 * Planting a token without navigating would have left those specs failing in a new and more
 * confusing way than the one this module exists to remove.
 *
 * Returns the token, because most callers want it for the API fixtures they are about to seed and
 * asking for it twice is the throttle trap described above.
 */
export async function signIn(page: Page, email: string = EMAIL, password: string = PASSWORD): Promise<string> {
  const token = await apiToken(page.request, email, password);
  await signInWithToken(page, token);
  await page.goto("/dashboard");
  return token;
}
