import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  ApiError,
  PASSWORD_CHANGE_REQUIRED_HEADER,
  apiFetch,
  apiFetchWithHeaders,
  onPasswordChangeRequired,
  sessionOwesPasswordChange,
  setSessionOwesPasswordChange
} from "@/lib/api";
import { isCredentialExpiry, isPasswordChangeRefusal, isUnreachable } from "@/lib/failureTriage";
import { MediaBatchError } from "@/lib/media";
import { currentPasswordRefused } from "@/lib/passwordChange";
import { SESSION_TOKEN_HEADER, changeOwnPassword } from "@/lib/signIn";

/**
 * THE PASSWORD GATE, ENFORCED BY THE SERVER AND MET — NOT SUFFERED — BY THE CLIENT.
 *
 * ── WHAT CHANGED ON THE SERVER ──────────────────────────────────────────────────────────────────
 *
 * The owner ruled that the server enforces `mustChangePassword`: while an account carries it, every
 * authenticated route outside a short allow-list answers 401 with `X-Password-Change-Required: 1`
 * and "Choose a new password to continue.". A 401, not a 403, because both clients keep queued work
 * on a 401 and park or destroy it on a 403.
 *
 * ── WHY THE CLIENT HAD TO CHANGE WITH IT ────────────────────────────────────────────────────────
 *
 * `apiFetch` read EVERY 401 sent with a token as a dead session: it threw the token away and, off
 * /login, hard-navigated to the sign-in form. Under enforcement that would have turned "choose a new
 * password" into "sign in again, then choose a new password" for every open tab — and the token it
 * threw away is the one `POST /auth/change-password` needs. So the gated 401 is a different answer:
 * the token stays, nothing navigates, and the auth layer is told so it re-reads `/me` and `AppShell`
 * draws the gate in place. Every other 401 is exactly what it was, and `public-page-401-unit.spec.ts`
 * still pins that.
 *
 * WHY THIS DRIVES THE REAL FUNCTIONS. `apiFetch` and `changeOwnPassword` are plain async functions
 * whose only ambient dependencies are `window` and `fetch`, both of which stand up in Node — the
 * approach `public-page-401-unit.spec.ts` takes — so the 401 branch is exercised, not read. Only the
 * `AuthProvider` half is a source read, because this repository has no React renderer.
 *
 * ⚠ LINE-ENDING AGNOSTIC: `\s` and `[\s\S]` throughout; never a literal newline.
 */

type Recorded = { assign: string[]; replace: string[] };

const realFetch = globalThis.fetch;
const GATED = { [PASSWORD_CHANGE_REQUIRED_HEADER]: "1" };
const AUTH = readFileSync(join(__dirname, "..", "components", "AuthProvider.tsx"), "utf8");
const SIGN_IN = readFileSync(join(__dirname, "..", "lib", "signIn.ts"), "utf8");
const BACKEND = join(__dirname, "..", "..", "backend");
const readBackend = (...parts: string[]) => readFileSync(join(BACKEND, ...parts), "utf8");

/** Every `.py` file under one backend directory. `__pycache__` holds no source. */
function pythonFiles(dir: string, found: string[] = []): string[] {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === "__pycache__") continue;
    const full = join(dir, entry.name);
    if (entry.isDirectory()) pythonFiles(full, found);
    else if (entry.name.endsWith(".py")) found.push(full);
  }
  return found;
}

/**
 * A module-level string constant's value, wherever under `backend/app` it is declared — so a header
 * the server names through a constant (`PASSWORD_CHANGE_REQUIRED_HEADER`, imported from `deps`) is
 * read as the string it is, and a rename of the constant cannot pass for a missing header.
 */
function backendConstant(name: string): string | null {
  const bare = name.split(".").at(-1) ?? "";
  if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(bare)) return null;
  const declared = new RegExp(`^${bare}\\s*(?::[^=\\r\\n]+)?=\\s*["']([^"']+)["']`, "m");
  for (const file of pythonFiles(join(BACKEND, "app"))) {
    const value = declared.exec(readFileSync(file, "utf8"))?.[1];
    if (value) return value;
  }
  return null;
}

/**
 * A window just real enough for `apiFetch`: the token store it reads and clears, and the location it
 * inspects and navigates. `protocol: "http:"` for the reason `public-page-401-unit.spec.ts` gives — a
 * loopback API base from an https page is refused before any request is made.
 */
function installFakeWindow(pathname: string, storedToken: string | null): Recorded {
  const recorded: Recorded = { assign: [], replace: [] };
  const store = new Map<string, string>();
  if (storedToken) store.set("field_repo_token", storedToken);
  (globalThis as Record<string, unknown>).window = {
    localStorage: {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => void store.set(key, value),
      removeItem: (key: string) => void store.delete(key)
    },
    location: {
      pathname,
      protocol: "http:",
      assign: (url: string) => void recorded.assign.push(url),
      replace: (url: string) => void recorded.replace.push(url)
    }
  };
  return recorded;
}

/** Every request answered with one status, one sentence and the given headers. */
function respond(status: number, detail: string, headers: Record<string, string> = {}) {
  (globalThis as Record<string, unknown>).fetch = async () =>
    new Response(JSON.stringify({ detail }), {
      status,
      headers: { "content-type": "application/json", ...headers }
    });
}

const tokenNow = () =>
  ((globalThis as Record<string, unknown>).window as { localStorage: { getItem(k: string): string | null } })
    .localStorage.getItem("field_repo_token");

function asBatchFailure(error: unknown, name = "loom.jpg"): MediaBatchError {
  return new MediaBatchError(`All 1 media file(s) failed to upload (${name}).`, [
    { name, error: error instanceof Error ? error.message : String(error), cause: error }
  ]);
}

test.afterEach(() => {
  delete (globalThis as Record<string, unknown>).window;
  (globalThis as Record<string, unknown>).fetch = realFetch;
  // Module state, and this worker runs other spec files after this one.
  setSessionOwesPasswordChange(false);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 1. The gated 401 keeps the session
 * ──────────────────────────────────────────────────────────────────────────── */

test("a gated 401 keeps the token, navigates nowhere, and is recognisable to the caller", async () => {
  const recorded = installFakeWindow("/products", "a-perfectly-good-token");
  respond(401, "Choose a new password to continue.", GATED);

  // No options: an ordinary call from a protected page, which defaults to redirecting on a 401.
  const refused = await apiFetch("/products?page=1").catch((error: unknown) => error);

  expect(refused).toBeInstanceOf(ApiError);
  expect((refused as ApiError).status, "still a 401: queued work is kept on a 401").toBe(401);
  expect((refused as ApiError).passwordChangeRequired, "and told apart by the header, never the sentence").toBe(true);
  expect((refused as ApiError).message).toBe("Choose a new password to continue.");
  expect(tokenNow(), "the token is what /auth/change-password will be sent with").toBe("a-perfectly-good-token");
  expect(recorded.replace, "no bounce to /login: the gate is drawn in place").toEqual([]);
  expect(recorded.assign).toEqual([]);
});

test("even a caller that asked for the redirect is not sent to /login", async () => {
  const recorded = installFakeWindow("/design-workshops", "a-perfectly-good-token");
  respond(401, "Choose a new password to continue.", GATED);

  await apiFetch("/design-workshops", {}, { redirectOn401: true }).catch(() => undefined);

  expect(recorded.replace).toEqual([]);
  expect(tokenNow()).toBe("a-perfectly-good-token");
});

test("the flag is raised before the caller hears, and the auth layer is told once per refusal", async () => {
  installFakeWindow("/dashboard", "a-perfectly-good-token");
  respond(401, "Choose a new password to continue.", GATED);
  let told = 0;
  const unsubscribe = onPasswordChangeRequired(() => {
    told += 1;
  });
  try {
    expect(sessionOwesPasswordChange()).toBe(false);
    const seenByCaller = await apiFetch("/preferences/me").then(
      () => null,
      // Read INSIDE the rejection: a drain asks this in its catch, before anything else has run.
      () => sessionOwesPasswordChange()
    );
    expect(seenByCaller, "the background drains read this to stop spending refused requests").toBe(true);
    expect(told, "AuthProvider re-reads /me on this, so AppShell can draw the gate").toBe(1);
  } finally {
    unsubscribe();
  }
});

test("a listener that throws cannot turn the refusal into a different error", async () => {
  installFakeWindow("/dashboard", "a-perfectly-good-token");
  respond(401, "Choose a new password to continue.", GATED);
  const unsubscribe = onPasswordChangeRequired(() => {
    throw new Error("a broken subscriber");
  });
  try {
    const refused = await apiFetch("/artisans?page=1").catch((error: unknown) => error);
    expect(refused).toBeInstanceOf(ApiError);
    expect((refused as ApiError).passwordChangeRequired).toBe(true);
  } finally {
    unsubscribe();
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. Every other 401 is what it always was
 * ──────────────────────────────────────────────────────────────────────────── */

test("an ordinary 401 still ends the session and still lands on /login", async () => {
  const recorded = installFakeWindow("/products", "a-token-the-server-no-longer-honours");
  respond(401, "This session is no longer valid. Sign in again.");
  let told = 0;
  const unsubscribe = onPasswordChangeRequired(() => {
    told += 1;
  });
  try {
    const refused = await apiFetch("/products?page=1").catch((error: unknown) => error);
    expect((refused as ApiError).passwordChangeRequired).toBe(false);
    expect(tokenNow(), "a dead token is still dropped").toBeNull();
    expect(recorded.replace).toEqual(["/login"]);
    expect(sessionOwesPasswordChange(), "and nothing is said about a password").toBe(false);
    expect(told).toBe(0);
  } finally {
    unsubscribe();
  }
});

test("the header means nothing on another status, or with another value", () => {
  // Spelled once, lower-case because `Headers.get` is case-insensitive.
  expect(PASSWORD_CHANGE_REQUIRED_HEADER).toBe("x-password-change-required");
  expect(new ApiError(401, "…", null, new Headers({ "X-Password-Change-Required": "1" })).passwordChangeRequired).toBe(true);
  // A 403 is a refusal of the WORK, and both clients park work on a 403: never the gate.
  expect(new ApiError(403, "…", null, new Headers(GATED)).passwordChangeRequired).toBe(false);
  expect(new ApiError(401, "…", null, new Headers({ [PASSWORD_CHANGE_REQUIRED_HEADER]: "0" })).passwordChangeRequired).toBe(false);
  // A cross-origin response that does not expose the header reads as an ordinary dead session —
  // the safe direction to be wrong in, at the cost of one sign-in.
  expect(new ApiError(401, "…", null, new Headers()).passwordChangeRequired).toBe(false);
  expect(new ApiError(401, "…", null).passwordChangeRequired).toBe(false);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. The triage reads it through a wrapper, and keeps it on the expiry row
 * ──────────────────────────────────────────────────────────────────────────── */

test("a gated refusal is recognised bare and inside a media batch, and stays a stop-and-keep", () => {
  const gated = new ApiError(401, "Choose a new password to continue.", null, new Headers(GATED));
  const expired = new ApiError(401, "Could not validate credentials", null);

  expect(isPasswordChangeRefusal(gated)).toBe(true);
  // How the media leg of a drain meets it — the case that would otherwise mark a photograph refused.
  expect(isPasswordChangeRefusal(asBatchFailure(gated))).toBe(true);
  expect(isPasswordChangeRefusal(expired), "an expired token is not the gate").toBe(false);
  expect(isPasswordChangeRefusal(new TypeError("Failed to fetch"))).toBe(false);
  // A NARROWER READING, NOT A NEW KIND: it keeps the 401 row — stop the pass, mark nothing — so any
  // drain that already handles an expiry already does the safe thing with it.
  expect(isCredentialExpiry(gated)).toBe(true);
  expect(isUnreachable(gated), "the server answered").toBe(false);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. The gate's own form no longer costs the session on a typo
 * ──────────────────────────────────────────────────────────────────────────── */

test("a wrong current password is a 400 that keeps the session and empties only the box", async () => {
  // The server answered a wrong current password with a 401, and `apiFetch` read it as a dead
  // session: the token went, the protected host hard-reloaded onto the sign-in form, and "Current
  // password is incorrect" was never seen. It is a 400 now, which `apiFetch` leaves alone.
  const recorded = installFakeWindow("/dashboard", "a-perfectly-good-token");
  respond(400, "Current password is incorrect");

  const refused = await changeOwnPassword("not-my-password", "my-own-password").catch((error: unknown) => error);

  expect(refused).toBeInstanceOf(ApiError);
  expect((refused as ApiError).message, "the server's own sentence reaches the form").toBe("Current password is incorrect");
  expect(currentPasswordRefused(refused), "and the form knows to reveal and empty its box").toBe(true);
  expect(tokenNow()).toBe("a-perfectly-good-token");
  expect(recorded.replace).toEqual([]);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 5. The auth layer's half — source reads, for want of a React renderer
 * ──────────────────────────────────────────────────────────────────────────── */

test("AuthProvider re-reads /me on a gated refusal, one re-read at a time", () => {
  const listener = AUTH.slice(AUTH.indexOf("onPasswordChangeRequired(() => {"));
  expect(listener.length, "the subscription was located").toBeGreaterThan(0);
  const body = listener.slice(0, listener.indexOf("[refreshMe]"));
  // Six refused requests from one page cost one `/me` — and a refusal of `/me` itself, were the
  // server ever to send one, arrives while its own re-read is the one in flight: no loop.
  expect(body).toContain("if (gateProbe.current) return;");
  expect(body).toMatch(/gateProbe\.current = refreshMe\(\)\.finally\(\(\) => \{\s*gateProbe\.current = null;\s*\}\);/);
});

test("every account the provider adopts writes the flag the background drains read", () => {
  const adopt = AUTH.slice(AUTH.indexOf("const adopt = useCallback("));
  expect(adopt.slice(0, adopt.indexOf("}, []);"))).toContain("setSessionOwesPasswordChange(mustChangePassword(next));");
  // Sign-in on both paths, sign-out, a refused /me, and a re-read — all through the one door.
  expect((AUTH.match(/adopt\(result\.user\);/g) ?? []).length, "both sign-in paths").toBe(2);
  expect(AUTH).toContain("adopt(null);");
  expect(AUTH, "nothing sets the account around it").not.toMatch(/setUser\(result\.user\)|setUser\(me\)|setUser\(null\)/);
});

test("a re-read that fails on the connection keeps the account it already had", () => {
  // A dropped connection during a re-read is an ordinary event here, and answering it with
  // `user = null` sent a person holding a valid token to the sign-in form. Only the server's own
  // 401/403 ends the account in this tab.
  const refresh = AUTH.slice(AUTH.indexOf("const refreshMe = useCallback("));
  const body = refresh.slice(0, refresh.indexOf("}, [adopt]);"));
  const caught = body.slice(body.indexOf("} catch (err) {"), body.indexOf("} finally {"));
  expect(caught).toMatch(
    /if \(err instanceof ApiError && \(err\.status === 401 \|\| err\.status === 403\) && !sessionReplacedSince\(sentWith\)\) \{\s*setToken\(null\);\s*adopt\(null\);\s*\}/
  );
  expect((caught.match(/adopt\(/g) ?? []).length, "and nowhere else in the catch").toBe(1);
  // `sentWith` is the token the re-read went out with, read before the request — not after it.
  expect(body.indexOf("const sentWith = getToken();")).toBeGreaterThan(-1);
  expect(body.indexOf("const sentWith = getToken();")).toBeLessThan(body.indexOf('apiFetch<User>("/me"'));
});

/* ────────────────────────────────────────────────────────────────────────────
 * 6. A changed password retires the session that sent it, and hands back a fresh one — in a header
 * ──────────────────────────────────────────────────────────────────────────── */

/** Answer every request with one JSON body; `before` runs as the answer is produced. */
function answer(status: number, body: unknown, before?: () => void, headers: Record<string, string> = {}) {
  (globalThis as Record<string, unknown>).fetch = async () => {
    before?.();
    return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });
  };
}

const setStoredToken = (token: string) =>
  ((globalThis as Record<string, unknown>).window as { localStorage: { setItem(k: string, v: string): void } })
    .localStorage.setItem("field_repo_token", token);

test("changeOwnPassword hands back the fresh session from its header, and null where none came back", async () => {
  // Since 2026-10-09 every token carries the fingerprint of the password it was opened with, so the
  // change retires the token that sent it — and answers with one minted after the write, in the
  // `X-Session-Token` header. The body stays `{"ok": true}`: see `SESSION_TOKEN_HEADER`.
  installFakeWindow("/settings", "a-token-the-change-will-retire");
  answer(200, { ok: true }, undefined, { "X-Session-Token": "a-fresh-token" });
  expect(await changeOwnPassword("old-password", "new-password")).toBe("a-fresh-token");
  // The helper hands it over and adopts nothing itself: the caller adopts it before re-reading /me.
  expect(tokenNow()).toBe("a-token-the-change-will-retire");

  // An older server — or a header the browser was not allowed to read: nothing to adopt.
  answer(200, { ok: true });
  expect(await changeOwnPassword("old-password", "new-password")).toBeNull();
  answer(200, { ok: true }, undefined, { "X-Session-Token": "  " });
  expect(await changeOwnPassword("old-password", "new-password"), "a blank header is no token").toBeNull();
});

test("a token in the change-password BODY is never adopted, whatever a server puts there", async () => {
  /*
    THE CONTRACT IS THE HEADER, and the body is `{"ok": true}` exactly — handsets 0.0.6–0.0.15 decode
    it as a map of booleans, and a string beside `ok` makes them throw and print the token on screen.
    A client that went on reading `accessToken` out of the body would keep a server that put it back
    looking healthy on the web while every shipped phone broke.
  */
  installFakeWindow("/settings", "a-token-the-change-will-retire");
  answer(200, { ok: true, accessToken: "a-token-in-the-body" });
  expect(await changeOwnPassword("old-password", "new-password")).toBeNull();
  answer(200, { ok: true, accessToken: "a-token-in-the-body" }, undefined, { "X-Session-Token": "the-header-token" });
  expect(await changeOwnPassword("old-password", "new-password")).toBe("the-header-token");

  expect(SESSION_TOKEN_HEADER, "spelled once, lower-case: Headers.get is case-insensitive").toBe("x-session-token");
  // From the declaration to the first closing brace at the start of a line — `^` under the `m` flag, so
  // a CRLF checkout matches exactly as an LF one does.
  const body = /export async function changeOwnPassword\([\s\S]*?^\}/m.exec(SIGN_IN)?.[0] ?? "";
  expect(body, "the helper was located").toContain('apiFetchWithHeaders<unknown>("/auth/change-password"');
  expect(body).toContain("headers.get(SESSION_TOKEN_HEADER)");
  expect(body, "and nothing reads the body for a token").not.toContain("accessToken");
});

test("apiFetchWithHeaders is apiFetch with the headers kept: same body, same refusals", async () => {
  installFakeWindow("/settings", "a-perfectly-good-token");
  answer(200, { ok: true }, undefined, { "X-Session-Token": "t-2" });
  const { body, headers } = await apiFetchWithHeaders<{ ok: boolean }>("/auth/change-password", { method: "POST" });
  expect(body).toEqual({ ok: true });
  expect(headers.get(SESSION_TOKEN_HEADER)).toBe("t-2");
  expect(await apiFetch("/auth/change-password", { method: "POST" }), "apiFetch drops only the headers").toEqual({ ok: true });

  // A refusal is apiFetch's: the server's sentence, and a dead session handled exactly as before.
  const recorded = installFakeWindow("/settings", "a-token-the-server-no-longer-honours");
  respond(401, "This session is no longer valid. Sign in again.");
  const refused = await apiFetchWithHeaders("/preferences/me").catch((error: unknown) => error);
  expect(refused).toBeInstanceOf(ApiError);
  expect((refused as ApiError).message).toBe("This session is no longer valid. Sign in again.");
  expect(tokenNow()).toBeNull();
  expect(recorded.replace).toEqual(["/login"]);
});

test("the API lets a browser read the session header — or every web password change costs a sign-in", () => {
  /*
    A cross-origin response shows JavaScript only the headers named in CORS `expose_headers`. Hidden,
    the fresh session never reaches this client, which keeps the retired token and is signed out by
    its next request: safe, and invisible to every server test. Read off `backend/app/main.py`, with a
    named constant resolved to its value wherever in `app/` it is declared.
  */
  const main = readBackend("app", "main.py");
  const list = /expose_headers\s*=\s*\[([^\]]*)\]/.exec(main)?.[1];
  expect(list, "expose_headers was located in backend/app/main.py").toBeTruthy();
  const exposed = list!
    // A comment between two entries is prose, not a header.
    .replace(/#[^\r\n]*/g, "")
    .split(",")
    .map((entry) => entry.trim())
    .filter(Boolean)
    .map((entry) => {
      const literal = /^["']([^"']+)["']$/.exec(entry)?.[1];
      return literal ?? backendConstant(entry);
    })
    .map((name) => (name ?? "").toLowerCase());
  expect(exposed, "X-Session-Token must be exposed beside X-Password-Change-Required").toContain(SESSION_TOKEN_HEADER);
  expect(exposed).toContain(PASSWORD_CHANGE_REQUIRED_HEADER);
});

test("the server sends the session in the header this client reads, and the body stays {\"ok\": true}", () => {
  // Two halves of one contract, read where each is written: the route sets a header whose name is the
  // one `changeOwnPassword` asks for, and answers with nothing a map of booleans cannot decode.
  const auth = readBackend("app", "api", "routes", "auth.py");
  const from = auth.indexOf("async def change_password(");
  expect(from, "the change-password route was located").toBeGreaterThan(-1);
  const route = auth.slice(from, auth.indexOf("@router.", from));
  const named = /response\.headers\[([A-Za-z_][A-Za-z0-9_]*)\]\s*=/.exec(route)?.[1];
  expect(named, "the route sets a response header from a named constant").toBeTruthy();
  expect((backendConstant(named!) ?? "").toLowerCase()).toBe(SESSION_TOKEN_HEADER);
  expect(route).toContain('return {"ok": True}');
  expect(route, "no token in the body, for the handsets already in the field").not.toMatch(/"accessToken"\s*:/);
});

test("a 401 for a token this tab has since replaced keeps the session that replaced it", async () => {
  /*
    THE RACE R1 OPENS. A request sent with the old token — a theme save, a drain, the gate's own /me
    re-read — is answered 401 "no longer valid" because the change retired it, and lands after the
    fresh token was adopted. Reading that as a dead session would clear the FRESH token and bounce
    the person to /login the moment they complied.
  */
  const recorded = installFakeWindow("/settings", "the-retired-token");
  answer(401, { detail: "This session is no longer valid. Sign in again." }, () => setStoredToken("the-fresh-token"));
  let told = 0;
  const unsubscribe = onPasswordChangeRequired(() => {
    told += 1;
  });
  try {
    const refused = await apiFetch("/preferences/me").catch((error: unknown) => error);
    expect(refused, "the caller still hears the refusal").toBeInstanceOf(ApiError);
    expect(tokenNow(), "the session that replaced it is kept").toBe("the-fresh-token");
    expect(recorded.replace, "and nobody is sent to /login").toEqual([]);

    // A gated answer for the retired token is not the fresh session's either.
    answer(401, { detail: "Choose a new password to continue." }, () => setStoredToken("a-fresher-token"), GATED);
    setStoredToken("the-fresh-token");
    await apiFetch("/artisans?page=1").catch(() => undefined);
    expect(sessionOwesPasswordChange(), "no gate raised for a session already let go of").toBe(false);
    expect(told).toBe(0);
    expect(tokenNow()).toBe("a-fresher-token");
  } finally {
    unsubscribe();
  }
});

test("a token CLEARED while the request was out is not 'replaced': the usual 401 handling stands", async () => {
  const recorded = installFakeWindow("/products", "a-dying-token");
  answer(401, { detail: "This session is no longer valid. Sign in again." }, () =>
    ((globalThis as Record<string, unknown>).window as { localStorage: { removeItem(k: string): void } }).localStorage.removeItem(
      "field_repo_token"
    )
  );
  await apiFetch("/products?page=1").catch(() => undefined);
  expect(tokenNow()).toBeNull();
  expect(recorded.replace).toEqual(["/login"]);
});
