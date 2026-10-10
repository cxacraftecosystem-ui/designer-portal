import type { PageResult } from "@/lib/types";

/**
 * Next.js INLINES `process.env.NEXT_PUBLIC_API_URL` into the bundle at build time, so a build that
 * cannot see the variable does not fail — it substitutes `undefined` and ships a site that can
 * reach nothing while every signal stays green. That is not hypothetical: Vercel refuses to hand
 * env vars marked "sensitive" to `vercel pull`, a CI build inlined nothing, and a user discovered
 * the site could not log anyone in.
 *
 * A variable that exists but is BLANK is the worse half of the same bug: `??` does not treat `""`
 * as absent, so the base would stay `""`, every request would resolve against the Vercel origin
 * itself, and the app would parse Vercel's own 404 HTML as an API answer. Missing, empty and
 * whitespace all mean one thing — nobody told this bundle where the backend is — so they collapse
 * into one case here.
 */
const configuredBase = (process.env.NEXT_PUBLIC_API_URL ?? "").trim();

/** A checkout with no env file at all is the normal state of a developer's laptop; keep it working. */
const LOCAL_DEV_BASE = "http://localhost:8000";

export const API_BASE = configuredBase || LOCAL_DEV_BASE;

const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "0.0.0.0", "[::1]"]);

/**
 * Does the base we ended up with only exist on the machine running the browser — whether by falling
 * back above or because someone deployed with a localhost URL? Resolved once at module load, since
 * it cannot change without a rebuild, which keeps the per-request check to two comparisons. A base
 * that does not parse is deliberately left alone: inventing new failure modes in the hot path is
 * not what this guard is for.
 */
const baseIsLoopback = ((): boolean => {
  try {
    const { hostname } = new URL(API_BASE);
    return LOOPBACK_HOSTS.has(hostname) || hostname.endsWith(".localhost");
  } catch {
    return false;
  }
})();

/**
 * THE ONE 401 THAT IS NOT A DEAD SESSION.
 *
 * While an account carries `mustChangePassword`, the server answers every authenticated route
 * outside a short allow-list (`GET /me`, `GET /auth/me`, `POST /auth/change-password`,
 * `POST /auth/logout`, `GET`/`POST /usage/consent`, `GET /app/release/latest`) with a 401 carrying
 * this header and the sentence "Choose a new password to continue." — the owner's ruling that the
 * SERVER enforces the flag, so a typed temporary password cannot be used to work around the gate.
 * The token is perfectly good: it is the very token `POST /auth/change-password` needs. So this
 * answer must not do what every other 401 here does, which is to throw the token away and send the
 * browser to /login — that would turn "choose a new password" into "sign in again, and then choose
 * a new password", and would bounce a tab whose only job is to show the form.
 *
 * 401 AND NOT 403, and the server chose it for the clients' sake: both of them keep queued work on a
 * 401 and park or destroy it on a 403 (`lib/failureTriage.ts`), so a gated tab's outbox stays exactly
 * as it was.
 *
 * Lower-case because `Headers.get` is case-insensitive; the server spells it
 * `X-Password-Change-Required`. It must be in the API's CORS `expose_headers`, or a cross-origin
 * browser hides it and the answer reads as an ordinary dead session — the safe direction to be wrong
 * in, since that only costs a second sign-in.
 */
export const PASSWORD_CHANGE_REQUIRED_HEADER = "x-password-change-required";

export class ApiError extends Error {
  status: number;
  payload: unknown;
  /**
   * The response's headers, when there was a response.
   *
   * Carried because one refusal in this API says something in a header that it deliberately does NOT
   * say in the body: `X-Access-Status` classifies a refused sign-in (awaiting approval, rejected,
   * suspended, queue full) so the sign-in page can tell a person waiting on an administrator apart
   * from a person who mistyped a password. It is a header rather than a field because the refusal
   * body is asserted to hold nothing but `detail` — see `tests/test_platform_access_gate.py` — and
   * that assertion is the privacy floor of the whole allow-list feature.
   *
   * OPTIONAL, AND EVERY READER MUST COPE WITH IT BEING ABSENT. {@link ApiUnconfiguredError} is
   * thrown without any response at all, and a cross-origin response only exposes headers the server
   * named in `expose_headers`. A missing header means "not classified", never "not refused".
   */
  headers?: Headers;
  /**
   * True for the server's "choose a new password first" 401 — see
   * {@link PASSWORD_CHANGE_REQUIRED_HEADER}. Read off the header and never off the sentence, which is
   * prose and will be reworded. Decided here, once, so every reader of a refusal asks the same
   * question the same way; a wrapped one (a `MediaBatchError` around it) is answered by
   * `isPasswordChangeRefusal` in `lib/failureTriage.ts`, the one module allowed to open a `cause`.
   */
  passwordChangeRequired: boolean;

  constructor(status: number, message: string, payload: unknown, headers?: Headers) {
    super(message);
    this.status = status;
    this.payload = payload;
    this.headers = headers;
    this.passwordChangeRequired =
      status === 401 && (headers?.get(PASSWORD_CHANGE_REQUIRED_HEADER) ?? "").trim() === "1";
  }
}

/**
 * Raised instead of a request that could not possibly have worked: a deployed page whose only known
 * API address is the visitor's own machine.
 *
 * It extends {@link ApiError} for two concrete reasons. Every screen already renders an ApiError's
 * `message`, so the sentence below lands in the UI the researcher is looking at. And `lib/offline.ts`
 * treats a NON-ApiError as "the network is down" and quietly banks the save in the outbox — the same
 * defect all over again, a failure reported as success. The 503 is not a response anyone received;
 * no request was made. It is the code the rest of the app already reads as "the service is not
 * answering right now", which is the behaviour we want: queued work stays queued and drains by
 * itself once an administrator redeploys, while the person in front of the screen is still told.
 */
export class ApiUnconfiguredError extends ApiError {
  constructor() {
    super(
      503,
      "The service isn't available right now, so you can't sign in or load records. " +
        "Please try again later, or contact your administrator if this continues.",
      null
    );
    this.name = "ApiUnconfiguredError";
  }
}

let unconfiguredLogged = false;

/**
 * Refuse the request when this build has no usable API address. Exported so the handful of call
 * sites that build their own `fetch` from {@link API_BASE} (downloads, media object fetches) can
 * fail the same way rather than with a bare "Failed to fetch".
 *
 * Two conditions keep it honest. It never runs outside a browser: this module is imported by
 * `next build` and by SSR, and throwing there would break the build instead of the request — the
 * silence we are trying to end. And it only fires on an https:// page, which can only be a real
 * deployment; a developer on http://localhost is looking at the correct address, and a laptop
 * serving the dev site over the LAN genuinely can reach its own localhost API.
 */
export function assertApiConfigured(): void {
  if (typeof window === "undefined") return;
  if (!baseIsLoopback) return;
  if (window.location.protocol !== "https:") return;

  if (!unconfiguredLogged) {
    unconfiguredLogged = true;
    // The sentence above is for the researcher; this line is for whoever they forward the screenshot
    // to, and names the variable and the fix that the user-facing wording deliberately does not.
    console.error(
      `[api] NEXT_PUBLIC_API_URL was missing or blank when this bundle was built, so requests would go to ${API_BASE}, ` +
        "which no visitor can reach. Set it in the deployment's environment variables and redeploy — changing it in the " +
        "dashboard alone does nothing, because the value is baked into the shipped JavaScript."
    );
  }

  throw new ApiUnconfiguredError();
}

/**
 * The sentence a failed response is actually carrying, dug out of whatever shape `detail` took.
 *
 * A route's `detail` is not always a string. FastAPI's own 422 makes it a LIST of per-field error
 * objects, and a few routes raise a structured object instead (the artisan identity conflict carries
 * the clashing artisan along with the message, so the form can offer to open it). Both used to go
 * through `String(detail)`, which yields the literal "[object Object]" — and since every screen
 * renders `ApiError.message` and nothing else, that string was the entire answer a researcher got
 * from a rejected save on every form but the two that had privately re-parsed the response body for
 * themselves. Unpacking it once, here, is what gives the rest of them a real sentence.
 *
 * Exported because those private copies (`components/forms/ArtisanForm`, `components/review/
 * reviewErrors`) exist only to work around this and should eventually just call it.
 */
export function describeApiDetail(detail: unknown, fallback: string): string {
  if (typeof detail === "string" && detail.trim()) return detail;
  if (Array.isArray(detail)) {
    const messages = detail
      .map((entry) => {
        if (!entry || typeof entry !== "object") return "";
        const record = entry as { msg?: unknown; loc?: unknown };
        const message = "msg" in record ? String(record.msg) : "";
        if (!message) return "";
        // `loc` is ["body", "<field>"] — naming the field is the whole point of showing a 422 raised
        // by a form with a dozen boxes in it. A bare ["body"] names nothing, so it is left off.
        const field = Array.isArray(record.loc)
          ? record.loc.filter((part): part is string => typeof part === "string").at(-1)
          : null;
        // Pydantic prefixes every custom validator message with "Value error, "; the researcher only
        // needs the sentence after it.
        const cleaned = message.replace(/^Value error,\s*/, "").trim();
        return field && field !== "body" ? `${field}: ${cleaned}` : cleaned;
      })
      .filter(Boolean);
    if (messages.length) return messages.join(" ");
  }
  if (detail && typeof detail === "object" && "message" in detail) {
    const message = String((detail as { message: unknown }).message);
    if (message.trim()) return message;
  }
  return fallback;
}

export function getToken(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem("field_repo_token");
}

export function setToken(token: string | null) {
  if (typeof window === "undefined") return;
  if (token) window.localStorage.setItem("field_repo_token", token);
  else window.localStorage.removeItem("field_repo_token");
}

/**
 * Has the token a request LEFT with been replaced by a different one while it was in flight?
 *
 * A refusal of such a token is about a session this tab has already let go of, and says nothing about
 * the one it holds now. The case that made it matter: since 2026-10-09 a changed password retires
 * every session it did not mint — each token carries the fingerprint of the password it was opened
 * with — and `changeOwnPassword` hands this tab a fresh token in the same answer. A request sent with
 * the old token that comes back 401 AFTER the fresh one was adopted would otherwise clear the stored
 * token and sign out the session that replaced it, the moment the person complied. A sign-out and a
 * sign-in as somebody else while a request is out is the same shape.
 *
 * A token CLEARED in the meantime is not "replaced": the session is over either way, and the usual
 * handling of the refusal stands.
 */
export function sessionReplacedSince(sent: string | null): boolean {
  if (!sent) return false;
  const held = getToken();
  return held !== null && held !== sent;
}

/*
  WHETHER THIS TAB'S ACCOUNT IS KNOWN TO OWE A NEW PASSWORD — the one fact the work that runs
  OUTSIDE React needs about the gate.

  `AuthProvider` holds the account and writes this from every one it adopts (a sign-in, a `/me`, the
  change itself). `apiFetch` raises it the moment a request comes back with the header, BEFORE anybody
  has re-read `/me`, because the two background drains (`lib/offline.ts`, `lib/designWorkshopStore.ts`)
  must stop spending requests the server is certain to refuse, and must not write a refusal onto
  fieldwork the person can do nothing about until they have chosen a password. React screens read the
  account itself (`useAuth().passwordChangeRequired`); this is for code that has no component to ask.
*/
let passwordChangeOwed = false;
const passwordChangeListeners = new Set<() => void>();

/** True while the signed-in account is known to owe a new password. See the note above. */
export function sessionOwesPasswordChange(): boolean {
  return passwordChangeOwed;
}

/** `AuthProvider`'s half: what the account it just adopted says. Never call it from a screen. */
export function setSessionOwesPasswordChange(owed: boolean): void {
  passwordChangeOwed = owed;
}

/**
 * Be told when a request has just been refused because the account owes a new password.
 *
 * `AuthProvider` is the one subscriber: it re-reads `/me` — which the server always answers, flag
 * and all — so `AppShell` draws the gate in place of whatever page asked. Returns the unsubscribe.
 */
export function onPasswordChangeRequired(listener: () => void): () => void {
  passwordChangeListeners.add(listener);
  return () => {
    passwordChangeListeners.delete(listener);
  };
}

/**
 * Per-call behaviour that is about the CALLER's context rather than the request itself, which is why
 * it is a third argument and not stuffed into `RequestInit`.
 */
export type ApiFetchOptions = {
  /**
   * Send the browser to /login when the server answers 401 to a request that carried a token.
   * Defaults to true: for a screen that only exists for a signed-in user, an expired session and a
   * sign-in form are the same thing.
   *
   * Pass `false` from a call that runs on PUBLIC pages. The one that matters is `AuthProvider`'s
   * `/me` probe — see the note in the 401 branch below.
   *
   * A 401 carrying {@link PASSWORD_CHANGE_REQUIRED_HEADER} never navigates and never clears the
   * token, whatever this says: the session is valid and the gate is drawn in place.
   */
  redirectOn401?: boolean;
  /**
   * Let the BROWSER'S OWN HTTP CACHE answer this one request, revalidating the stored copy against
   * the server's `ETag` instead of re-downloading the body.
   *
   * **OFF FOR EVERY CALL BY DEFAULT, AND THAT DEFAULT IS THE POINT.** `cache: "no-store"` on every
   * other request in this app is deliberate, not an oversight nobody got round to: this client
   * writes and lists records people travelled to a village to collect, and a list served from a
   * stale store is the silent-emptiness bug this repository keeps re-filing — a screen that looks
   * exactly like a place with no records. So this is opt-in **per call**, and there is exactly ONE
   * caller: `fetchStageRegistry` in `lib/designWorkshops.ts`, for `GET /design-workshops/schema`.
   * If you are reaching for it for a second endpoint, the question to answer first is not "is this
   * response big" but "does the server send this one a validator, and is a stale copy of it
   * harmless" — and for everything that lists records the answer to the second half is no.
   *
   * **WHY THAT ONE ENDPOINT QUALIFIES AND ESSENTIALLY NOTHING ELSE DOES.** The field registry is
   * the largest body this API serves to a cold client, it is a pure constant on the server (no
   * database read at all, byte-identical for every caller and every role), it changes only on
   * deployment, and every cold tab must download the whole of it before it can draw a single form.
   * It is also the only route in this API whose handler reads `If-None-Match` and answers 304 —
   * `backend/app/api/routes/design_workshops.py::get_stage_schema` sends
   * `ETag: W/"<sha256 of the emitted bytes>"` with `Cache-Control: private, max-age=0,
   * must-revalidate`, and a `grep -ri if-none-match backend/app` on 2026-08-28 returns that file and
   * no other. So turning this option on anywhere else would buy nothing today even where it was
   * harmless. Sizes and the reasoning behind that validator: `docs/SCALABILITY.md` §9.1.
   *
   * **`"no-cache"`, NOT `"default"`, AND THE DIFFERENCE IS A GUARANTEE RATHER THAN A PREFERENCE.**
   * Under today's headers the two are identical, because `max-age=0, must-revalidate` already
   * forces a revalidation on every use. `"no-cache"` puts that requirement in the REQUEST, so it
   * survives anything that widens the response's freshness without this file being reopened — a
   * CDN or corporate proxy rewriting `Cache-Control`, or an edit to `_SCHEMA_CACHE_CONTROL` made
   * for some other reason. What it buys is the assurance that this client never renders a registry
   * the server has not just confirmed, and it costs nothing at all: `"no-cache"` still stores the
   * response and still sends `If-None-Match`, which is where the entire saving comes from. A stale
   * registry is the one failure worse than having no cache — a tab holding a field list the server
   * has moved past renders a form whose keys the server drops at save time, and a field that
   * silently stops being recorded is indistinguishable, on screen, from one the designer forgot.
   *
   * **AND IT IS NOT A SECOND OFFLINE STORE.** `must-revalidate` also forbids serving the stored
   * copy when revalidation fails, so an unreachable backend produces the same network error it
   * always did and `lib/designWorkshopStore.ts`'s IndexedDB copy — versioned, and reported to the
   * screen as `source: "cache"` — stays the one thing that answers offline. An HTTP cache quietly
   * standing in for it would be an unversioned offline store that no banner knows how to warn about.
   *
   * **A CONDITIONAL GET IS INVISIBLE TO EVERY CALLER.** The browser performs the revalidation
   * itself and materialises the stored response as an ordinary 200 with its body; `fetch` never
   * surfaces a 304 to JavaScript. So the parsing, the 401 branch and every caller below see exactly
   * the 200-and-a-body they saw before, and nothing in this client needs to learn what a 304 is.
   * (A 401 answering the revalidation is NOT a 304, so it reaches the 401 branch normally — the
   * stored body cannot be served past an expired session. And the server resolves
   * `get_current_user` before it looks at `If-None-Match`, so the tag is not a way past the
   * identity dependency either; `backend/tests/test_schema_conditional_get.py::
   * test_the_tag_is_not_a_way_past_the_identity_dependency` pins that.)
   */
  revalidateFromHttpCache?: boolean;
};

export async function apiFetch<T>(
  path: string,
  init: RequestInit = {},
  options: ApiFetchOptions = {}
): Promise<T> {
  return (await apiFetchWithHeaders<T>(path, init, options)).body;
}

/** A successful answer's body, and the headers it arrived with. See {@link apiFetchWithHeaders}. */
export type ApiAnswer<T> = { body: T; headers: Headers };

/**
 * {@link apiFetch}, handing back the answer's HEADERS beside its body — for an answer that says
 * something in a header it deliberately does not say in the body.
 *
 * ONE CALLER, AND THE REASON IS A CLIENT ALREADY IN PEOPLE'S HANDS. `changeOwnPassword` reads the
 * fresh session off `X-Session-Token` (`SESSION_TOKEN_HEADER` in `lib/signIn.ts`) because Android
 * builds 0.0.6–0.0.15 decode the change-password BODY as a map of booleans and break on any string
 * inside it. A refusal reads exactly as it always has: this IS `apiFetch` — `apiFetch` is this with
 * the headers dropped — so the 401 branch, the password gate, `sessionReplacedSince` and the cache
 * rule cannot come to treat one answer two ways.
 *
 * A header the server did not send is simply absent, and so is one a cross-origin browser was not
 * allowed to show (the API's CORS `expose_headers`). Every reader must read absence as "not said".
 */
export async function apiFetchWithHeaders<T>(
  path: string,
  init: RequestInit = {},
  options: ApiFetchOptions = {}
): Promise<ApiAnswer<T>> {
  assertApiConfigured();

  const headers = new Headers(init.headers);
  const token = getToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  if (init.body && !(init.body instanceof FormData) && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(`${API_BASE}/api${path}`, {
    ...init,
    headers,
    // AFTER the spread, so a caller cannot reach the browser cache by putting `cache` in its own
    // `RequestInit` — the opt-in is the named option and nothing else, which is what keeps
    // "which requests may be cached" a list of one that a grep can produce. See
    // `ApiFetchOptions.revalidateFromHttpCache` for what the one is and why.
    cache: options.revalidateFromHttpCache ? "no-cache" : "no-store"
  });

  if (response.status === 204) return { body: undefined as T, headers: response.headers };

  const contentType = response.headers.get("content-type") ?? "";
  const body = contentType.includes("application/json") ? await response.json() : await response.text();

  if (!response.ok) {
    const detail = typeof body === "object" && body && "detail" in body ? (body as { detail: unknown }).detail : undefined;
    // `statusText` is empty over HTTP/2 — which every deployed request is — so it cannot be the last
    // resort on its own, or a body-less failure reaches the screen as a blank error box.
    const message = describeApiDetail(detail, response.statusText || "Something went wrong. Please try again.");
    const error = new ApiError(response.status, message, body, response.headers);
    // About a token this tab has since replaced — see `sessionReplacedSince`. The caller still hears
    // the refusal; the session that replaced the token is neither cleared, nor gated, nor navigated.
    if (sessionReplacedSince(token)) throw error;
    if (error.passwordChangeRequired && token && typeof window !== "undefined") {
      /*
        THE SESSION IS GOOD; THE ACCOUNT OWES A PASSWORD. See `PASSWORD_CHANGE_REQUIRED_HEADER`.

        So nothing below happens: the token stays (it is what `/auth/change-password` will be sent
        with), and nothing navigates, whatever `redirectOn401` says — /login would only hand the same
        account straight back to the same gate, one sign-in later. What changes is that the auth layer
        is told: it re-reads `/me`, the account comes back carrying the flag, and `AppShell` replaces
        whatever page asked with the form. The caller still gets its rejection, recognisable by
        `passwordChangeRequired`, so a drain stops without marking anything.

        A listener's failure is not this request's, so one cannot turn the refusal into a different
        error on its way out.
      */
      passwordChangeOwed = true;
      for (const listener of [...passwordChangeListeners]) {
        try {
          listener();
        } catch {
          /* See above. */
        }
      }
    } else if (response.status === 401 && token && typeof window !== "undefined") {
      // A previously-valid session expired: drop the stored token, always. The token is proven dead
      // by the answer we are holding, and every screen — public or protected — is better off
      // without it.
      setToken(null);

      /*
        THE NAVIGATION IS A SEPARATE DECISION, AND IT IS THE CALLER'S.

        The comment that used to live here reasoned that "anonymous requests — e.g. the landing
        page's /me probe — sent no token, so they fail without navigating the visitor away from
        public pages". That is true only of a visitor who has NO stored token. `AuthProvider` sits
        in the ROOT layout (app/layout.tsx), above the public landing page as well as the protected
        tree, and its mount effect probes /me on EVERY page load. A designer who last signed in six
        weeks ago opens the marketing home page: her expired token is sent, 401 comes back, and the
        browser hard-navigates her off the one route this app most needs to be publicly reachable,
        mid-scroll, with no explanation. Audit 2026-08-15 (MINOR, frontend) filed it; the invariant
        the old comment asserted was enforced by nothing.

        So the condition is no longer "a token was sent" — it is "the caller says this call belongs
        to a signed-in screen". `AuthProvider.refreshMe` opts out (`redirectOn401: false`) and
        handles its own 401 correctly: it clears the token, sets `user` to null, and `AppShell` then
        does the SOFT `router.replace("/login")` for protected routes only. Public pages are left
        alone, which is the whole point.

        `replace`, not `assign`: `assign` pushes a history entry, so Back returned the visitor to
        the page they had just been thrown off — Back appearing to malfunction was half of what the
        finding described. `replace` leaves history where it was.

        The /login self-exclusion stays; without it a 401 on the sign-in screen reloads it forever.
      */
      if ((options.redirectOn401 ?? true) && window.location.pathname !== "/login") {
        window.location.replace("/login");
      }
    }
    throw error;
  }

  return { body: body as T, headers: response.headers };
}

export function buildQuery(params: Record<string, string | number | undefined | null>) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") search.set(key, String(value));
  });
  const query = search.toString();
  return query ? `?${query}` : "";
}

export async function listResource<T>(path: string, params: Record<string, string | number | undefined | null>) {
  return apiFetch<PageResult<T>>(`${path}${buildQuery(params)}`);
}
