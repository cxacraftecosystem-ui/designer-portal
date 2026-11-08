import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  ApiError,
  PASSWORD_CHANGE_REQUIRED_HEADER,
  apiFetch,
  apiFetchWithHeaders,
  getSessionMarker,
  onPasswordChangeRequired,
  sessionOwesPasswordChange,
  setSessionOwesPasswordChange
} from "@/lib/api";
import { isCredentialExpiry, isPasswordChangeRefusal, isUnreachable } from "@/lib/failureTriage";
import { MediaBatchError } from "@/lib/media";
import { currentPasswordRefused } from "@/lib/passwordChange";
import { SESSION_ROTATED_HEADER } from "@/lib/sessionCookie";
import { SESSION_TOKEN_HEADER, changeOwnPassword } from "@/lib/signIn";

import { installSessionCookie, removeSessionCookie, type FakeSessionCookie } from "./support/fakeSessionCookie";

let session: FakeSessionCookie = installSessionCookie(null);

type Recorded = { assign: string[]; replace: string[] };

const realFetch = globalThis.fetch;
const GATED = { [PASSWORD_CHANGE_REQUIRED_HEADER]: "1" };
const AUTH = readFileSync(join(__dirname, "..", "components", "AuthProvider.tsx"), "utf8");
const SIGN_IN = readFileSync(join(__dirname, "..", "lib", "signIn.ts"), "utf8");
const BACKEND = join(__dirname, "..", "..", "backend");
const readBackend = (...parts: string[]) => readFileSync(join(BACKEND, ...parts), "utf8");

function pythonFiles(dir: string, found: string[] = []): string[] {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === "__pycache__") continue;
    const full = join(dir, entry.name);
    if (entry.isDirectory()) pythonFiles(full, found);
    else if (entry.name.endsWith(".py")) found.push(full);
  }
  return found;
}

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

function installFakeWindow(pathname: string, storedToken: string | null): Recorded {
  const recorded: Recorded = { assign: [], replace: [] };
  session = installSessionCookie(storedToken);
  (globalThis as Record<string, unknown>).window = {
    location: {
      pathname,
      protocol: "http:",
      assign: (url: string) => void recorded.assign.push(url),
      replace: (url: string) => void recorded.replace.push(url)
    }
  };
  return recorded;
}

function respond(status: number, detail: string, headers: Record<string, string> = {}) {
  (globalThis as Record<string, unknown>).fetch = async () =>
    new Response(JSON.stringify({ detail }), {
      status,
      headers: { "content-type": "application/json", ...headers }
    });
}

const tokenNow = () => getSessionMarker();

function asBatchFailure(error: unknown, name = "loom.jpg"): MediaBatchError {
  return new MediaBatchError(`All 1 media file(s) failed to upload (${name}).`, [
    { name, error: error instanceof Error ? error.message : String(error), cause: error }
  ]);
}

test.afterEach(() => {
  delete (globalThis as Record<string, unknown>).window;
  removeSessionCookie();
  (globalThis as Record<string, unknown>).fetch = realFetch;
  setSessionOwesPasswordChange(false);
});

test("a gated 401 keeps the token, navigates nowhere, and is recognisable to the caller", async () => {
  const recorded = installFakeWindow("/products", "a-perfectly-good-token");
  respond(401, "Choose a new password to continue.", GATED);

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
    expect(recorded.replace).toEqual(["/session-ended?next=%2Fproducts"]);
    expect(sessionOwesPasswordChange(), "and nothing is said about a password").toBe(false);
    expect(told).toBe(0);
  } finally {
    unsubscribe();
  }
});

test("the header means nothing on another status, or with another value", () => {
  expect(PASSWORD_CHANGE_REQUIRED_HEADER).toBe("x-password-change-required");
  expect(new ApiError(401, "…", null, new Headers({ "X-Password-Change-Required": "1" })).passwordChangeRequired).toBe(true);
  expect(new ApiError(403, "…", null, new Headers(GATED)).passwordChangeRequired).toBe(false);
  expect(new ApiError(401, "…", null, new Headers({ [PASSWORD_CHANGE_REQUIRED_HEADER]: "0" })).passwordChangeRequired).toBe(false);
  expect(new ApiError(401, "…", null, new Headers()).passwordChangeRequired).toBe(false);
  expect(new ApiError(401, "…", null).passwordChangeRequired).toBe(false);
});

test("a gated refusal is recognised bare and inside a media batch, and stays a stop-and-keep", () => {
  const gated = new ApiError(401, "Choose a new password to continue.", null, new Headers(GATED));
  const expired = new ApiError(401, "Could not validate credentials", null);

  expect(isPasswordChangeRefusal(gated)).toBe(true);
  expect(isPasswordChangeRefusal(asBatchFailure(gated))).toBe(true);
  expect(isPasswordChangeRefusal(expired), "an expired token is not the gate").toBe(false);
  expect(isPasswordChangeRefusal(new TypeError("Failed to fetch"))).toBe(false);
  expect(isCredentialExpiry(gated)).toBe(true);
  expect(isUnreachable(gated), "the server answered").toBe(false);
});

test("a wrong current password is a 400 that keeps the session and empties only the box", async () => {
  const recorded = installFakeWindow("/dashboard", "a-perfectly-good-token");
  respond(400, "Current password is incorrect");

  const refused = await changeOwnPassword("not-my-password", "my-own-password").catch((error: unknown) => error);

  expect(refused).toBeInstanceOf(ApiError);
  expect((refused as ApiError).message, "the server's own sentence reaches the form").toBe("Current password is incorrect");
  expect(currentPasswordRefused(refused), "and the form knows to reveal and empty its box").toBe(true);
  expect(tokenNow()).toBe("a-perfectly-good-token");
  expect(recorded.replace).toEqual([]);
});

test("AuthProvider re-reads /me on a gated refusal, one re-read at a time", () => {
  const listener = AUTH.slice(AUTH.indexOf("onPasswordChangeRequired(() => {"));
  expect(listener.length, "the subscription was located").toBeGreaterThan(0);
  const body = listener.slice(0, listener.indexOf("[refreshMe]"));
  expect(body).toContain("if (gateProbe.current) return;");
  expect(body).toMatch(/gateProbe\.current = refreshMe\(\)\.finally\(\(\) => \{\s*gateProbe\.current = null;\s*\}\);/);
});

test("every account the provider adopts writes the flag the background drains read", () => {
  const adopt = AUTH.slice(AUTH.indexOf("const adopt = useCallback("));
  expect(adopt.slice(0, adopt.indexOf("}, []);"))).toContain("setSessionOwesPasswordChange(mustChangePassword(next));");
  expect((AUTH.match(/adopt\(result\.user\);/g) ?? []).length, "one adoption for every sign-in answer").toBe(1);
  expect((AUTH.match(/return open\(\s*await apiFetch<SignInResult>\("\/auth\/login"/g) ?? []).length, "every sign-in path").toBe(3);
  expect(AUTH).toContain("const account = open(result);");
  expect(AUTH).toContain("adopt(null);");
  expect(AUTH, "nothing sets the account around it").not.toMatch(/setUser\(result\.user\)|setUser\(me\)|setUser\(null\)/);
});

test("a re-read that fails on the connection keeps the account it already had", () => {
  const refresh = AUTH.slice(AUTH.indexOf("const refreshMe = useCallback("));
  const body = refresh.slice(0, refresh.indexOf("}, [adopt]);"));
  const caught = body.slice(body.indexOf("} catch (err) {"), body.indexOf("} finally {"));
  expect(caught).toMatch(
    /if \(err instanceof ApiError && \(err\.status === 401 \|\| err\.status === 403\) && !sessionReplacedSince\(sentWith\)\) \{\s*endSession\(\);\s*adopt\(null\);\s*\}/
  );
  expect((caught.match(/adopt\(/g) ?? []).length, "and nowhere else in the catch").toBe(1);
  expect(body.indexOf("const sentWith = getSessionMarker();")).toBeGreaterThan(-1);
  expect(body.indexOf("const sentWith = getSessionMarker();")).toBeLessThan(body.indexOf('apiFetch<User>("/me"'));
});

function answer(status: number, body: unknown, before?: () => void, headers: Record<string, string> = {}) {
  (globalThis as Record<string, unknown>).fetch = async () => {
    before?.();
    return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });
  };
}

const setStoredToken = (token: string) => session.set(token);

test("changeOwnPassword reports a renewed session from the site's header, and false where none came", async () => {
  installFakeWindow("/settings", "a-token-the-change-will-retire");
  answer(200, { ok: true }, undefined, { [SESSION_ROTATED_HEADER]: "1" });
  expect(await changeOwnPassword("old-password", "new-password")).toBe(true);

  answer(200, { ok: true });
  expect(await changeOwnPassword("old-password", "new-password")).toBe(false);
  answer(200, { ok: true }, undefined, { "X-Session-Token": "a-fresh-token" });
  expect(await changeOwnPassword("old-password", "new-password"), "the page never takes a token").toBe(false);
});

test("a token in the change-password BODY is never adopted, whatever a server puts there", async () => {
  installFakeWindow("/settings", "a-token-the-change-will-retire");
  answer(200, { ok: true, accessToken: "a-token-in-the-body" });
  expect(await changeOwnPassword("old-password", "new-password")).toBe(false);

  expect(SESSION_TOKEN_HEADER, "spelled once, lower-case: Headers.get is case-insensitive").toBe("x-session-token");
  const body = /export async function changeOwnPassword\([\s\S]*?^\}/m.exec(SIGN_IN)?.[0] ?? "";
  expect(body, "the helper was located").toContain('apiFetchWithHeaders<unknown>("/auth/change-password"');
  expect(body).toContain("sessionRotated(headers)");
  expect(body, "and nothing reads the body for a token").not.toContain("accessToken");
});

test("apiFetchWithHeaders is apiFetch with the headers kept: same body, same refusals", async () => {
  installFakeWindow("/settings", "a-perfectly-good-token");
  answer(200, { ok: true }, undefined, { "X-Session-Token": "t-2" });
  const { body, headers } = await apiFetchWithHeaders<{ ok: boolean }>("/auth/change-password", { method: "POST" });
  expect(body).toEqual({ ok: true });
  expect(headers.get(SESSION_TOKEN_HEADER)).toBe("t-2");
  expect(await apiFetch("/auth/change-password", { method: "POST" }), "apiFetch drops only the headers").toEqual({ ok: true });

  const recorded = installFakeWindow("/settings", "a-token-the-server-no-longer-honours");
  respond(401, "This session is no longer valid. Sign in again.");
  const refused = await apiFetchWithHeaders("/preferences/me").catch((error: unknown) => error);
  expect(refused).toBeInstanceOf(ApiError);
  expect((refused as ApiError).message).toBe("This session is no longer valid. Sign in again.");
  expect(tokenNow()).toBeNull();
  expect(recorded.replace).toEqual(["/session-ended?next=%2Fsettings"]);
});

test("the API lets a browser read the session header — or every web password change costs a sign-in", () => {
  const main = readBackend("app", "main.py");
  const list = /expose_headers\s*=\s*\[([^\]]*)\]/.exec(main)?.[1];
  expect(list, "expose_headers was located in backend/app/main.py").toBeTruthy();
  const exposed = list!
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
  answer(401, { detail: "This session is no longer valid. Sign in again." }, () => session.set(null));
  await apiFetch("/products?page=1").catch(() => undefined);
  expect(tokenNow()).toBeNull();
  expect(recorded.replace).toEqual(["/session-ended?next=%2Fproducts"]);
});
