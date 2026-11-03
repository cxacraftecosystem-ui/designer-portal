import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { outboxOutcome } from "@/components/OutboxBanner";
import { syncOutcome } from "@/components/designworkshop/DraftSyncBanner";
import { ApiError, PASSWORD_CHANGE_REQUIRED_HEADER, setSessionOwesPasswordChange } from "@/lib/api";
import { stageRefusalIsPassLevel } from "@/lib/designWorkshopStore";
import { sweepStagedObjects } from "@/lib/media";
import { acknowledgeOutboxTrouble, syncOutbox } from "@/lib/offline";

import { getSessionMarker } from "@/lib/session";

import { installSessionCookie, removeSessionCookie } from "./support/fakeSessionCookie";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const LAYOUT = read("app", "(protected)", "layout.tsx");
const ONBOARDING = read("components", "designers", "DesignerProfileOnboarding.tsx");
const OFFLINE_DIALOG = read("components", "dialogs", "OfflineDialog.tsx");
const UPDATE_DIALOG = read("components", "dialogs", "AppUpdateDialog.tsx");
const OUTBOX = read("lib", "offline.ts");
const DRAFTS = read("lib", "designWorkshopStore.ts");

const gated = () =>
  new ApiError(401, "Choose a new password to continue.", null, new Headers({ [PASSWORD_CHANGE_REQUIRED_HEADER]: "1" }));

const realFetch = globalThis.fetch;

const JOURNAL = "field_repo_staged_objects";

function installFakeWindow(journal: Record<string, number>) {
  const replaced: string[] = [];
  installSessionCookie("a-perfectly-good-token");
  const store = new Map<string, string>([[JOURNAL, JSON.stringify(journal)]]);
  (globalThis as Record<string, unknown>).window = {
    localStorage: {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => void store.set(key, value),
      removeItem: (key: string) => void store.delete(key)
    },
    location: {
      pathname: "/products",
      protocol: "http:",
      assign: (url: string) => void replaced.push(url),
      replace: (url: string) => void replaced.push(url)
    }
  };
  return { replaced, store };
}

test.afterEach(() => {
  setSessionOwesPasswordChange(false);
  delete (globalThis as Record<string, unknown>).window;
  removeSessionCookie();
  (globalThis as Record<string, unknown>).fetch = realFetch;
});

test("exactly three components are mounted beside AppShell, so each one is reviewed for the gate", () => {
  const outside = LAYOUT.slice(LAYOUT.indexOf("</AppShell>"), LAYOUT.indexOf("</ConfirmProvider>"));
  const mounted = [...outside.matchAll(/<([A-Z]\w*)\s*\/>/g)].map((match) => match[1]).sort();
  expect(mounted).toEqual(["AppUpdateWatcher", "DesignerProfileOnboarding", "OfflineWatcher"]);
});

test("the first-sign-in profile redirect makes no request while the account owes a password", () => {
  const effect = ONBOARDING.slice(ONBOARDING.indexOf("useEffect(() => {"));
  const skip = effect.indexOf("if (passwordChangeRequired) return;");
  expect(skip, "the gate check was located").toBeGreaterThan(-1);
  expect(skip, "before the request").toBeLessThan(effect.indexOf("getMyDesignerProfile()"));
  expect(skip, "and before the in-flight guard is claimed, so nothing is held").toBeLessThan(
    effect.indexOf("checking.current = true;")
  );
  expect(ONBOARDING).toMatch(/\}, \[user, passwordChangeRequired, pathname, router\]\);/);
  expect(ONBOARDING).toMatch(/const \{ user, passwordChangeRequired \} = useAuth\(\);/);
});

test("the offline notice is not drawn over the gate, and does not announce a recovery there", () => {
  expect(OFFLINE_DIALOG).toContain("<OfflineDialog open={open && !passwordChangeRequired}");
  const online = OFFLINE_DIALOG.slice(OFFLINE_DIALOG.indexOf("const goOnline = useCallback("));
  const body = online.slice(0, online.indexOf("}, [toast]);"));
  expect(body.indexOf("if (gated.current) return;"), "the recovery toast stands down").toBeGreaterThan(-1);
  expect(body.indexOf("if (gated.current) return;")).toBeLessThan(body.indexOf("toast("));
});

test("the stale-build watcher makes no request at all, so it cannot bounce a gated tab", () => {
  expect(UPDATE_DIALOG).not.toMatch(/from "@\/lib\/api"/);
  expect(UPDATE_DIALOG).not.toMatch(/\bapiFetch\(|\bfetch\(/);
});

test("the theme provider waits for the gate before reading the account's preferences, and reads them after", () => {
  const THEME = read("components", "ThemeProvider.tsx");
  expect(THEME).toMatch(/const \{ user, loading: authLoading, passwordChangeRequired \} = useAuth\(\);/);
  expect(THEME).toContain("if (!hydrated || authLoading || !userId || passwordChangeRequired) return;");
  expect(THEME).toMatch(/\}, \[authLoading, hydrated, passwordChangeRequired, persist, userId\]\);/);
  expect(THEME).toContain("if (gated.current) heldBack.current = signedIn.current;");
  const effect = THEME.slice(THEME.indexOf("const held = heldBack.current === userId;"));
  expect(effect.indexOf("void persist(latest.current);"), "the held change is sent first").toBeLessThan(
    effect.indexOf('apiFetch<Partial<Preferences>>("/preferences/me")')
  );
});

test("the outbox does not start a pass while the session is known to be gated", async () => {
  setSessionOwesPasswordChange(true);
  try {
    const result = await syncOutbox();
    expect(result.passwordChangeRequired).toBe(true);
    expect(result.credentialExpired, "the sign-in has NOT expired, and must not be said to have").toBe(false);
    expect(result.synced).toBe(0);
    expect(result.failed, "nothing was marked").toBe(0);
  } finally {
    setSessionOwesPasswordChange(false);
    acknowledgeOutboxTrouble();
  }
});

test("the outbox asks for the gate before the expiry, and both before anything is marked", () => {
  const loopAt = OUTBOX.indexOf("for (const entry of entries) {");
  expect(OUTBOX.indexOf("if (sessionOwesPasswordChange()) {"), "the pre-pass check").toBeGreaterThan(-1);
  expect(OUTBOX.indexOf("if (sessionOwesPasswordChange()) {")).toBeLessThan(loopAt);
  const caught = OUTBOX.slice(OUTBOX.indexOf("await discardOutboxEntry(progress.id!);"));
  const gateAt = caught.indexOf("if (isPasswordChangeRefusal(error)) {");
  expect(gateAt, "the mid-pass stop was located").toBeGreaterThan(-1);
  expect(gateAt).toBeLessThan(caught.indexOf("if (isCredentialExpiry(error)) {"));
  expect(gateAt).toBeLessThan(caught.indexOf("await markFailure("));
});

test("what the outbox banner says about such a pass", () => {
  const base = {
    synced: 0,
    failed: 0,
    remaining: 3,
    stoppedOffline: false,
    declined: false,
    credentialExpired: false,
    storeUnreadable: false
  };
  const stopped = outboxOutcome({ ...base, passwordChangeRequired: true });
  expect(stopped.kind).toBe("password");
  expect(stopped.title).not.toMatch(/expired|connection|Nothing to send/i);
  expect(stopped.description).toContain("nothing has been thrown away");
  const partly = outboxOutcome({ ...base, synced: 2, remaining: 4, passwordChangeRequired: true });
  expect(partly.kind).toBe("sent");
  expect(partly.description).toMatch(/new password/);
  expect(partly.description).toContain("4");
  expect(outboxOutcome({ ...base, storeUnreadable: true, passwordChangeRequired: true }).kind).toBe("unreadable");
});

test("the orphan sweep does not start while the session is known to be gated", async () => {
  const { store } = installFakeWindow({ "staged/abandoned.jpg": Date.now() - 3_600_000 });
  let requests = 0;
  (globalThis as Record<string, unknown>).fetch = async () => {
    requests += 1;
    return new Response(null, { status: 204 });
  };
  setSessionOwesPasswordChange(true);

  expect(await sweepStagedObjects(), "nothing attempted").toBe(0);
  expect(requests).toBe(0);
  expect(Object.keys(JSON.parse(store.get(JOURNAL) ?? "{}")), "and the journal is untouched").toEqual(["staged/abandoned.jpg"]);
});

test("a gate met mid-sweep stops it, keeps every key, keeps the token, and moves nobody", async () => {
  const stale = Date.now() - 3_600_000;
  const { replaced, store } = installFakeWindow({ "staged/a.jpg": stale, "staged/b.jpg": stale, "staged/c.jpg": stale });
  let requests = 0;
  (globalThis as Record<string, unknown>).fetch = async () => {
    requests += 1;
    return new Response(JSON.stringify({ detail: "Choose a new password to continue." }), {
      status: 401,
      headers: { "content-type": "application/json", [PASSWORD_CHANGE_REQUIRED_HEADER]: "1" }
    });
  };

  expect(await sweepStagedObjects(), "one attempt, then the gate").toBe(1);
  expect(requests).toBe(1);
  expect(Object.keys(JSON.parse(store.get(JOURNAL) ?? "{}")).sort(), "a 401 never forgets a key").toEqual([
    "staged/a.jpg",
    "staged/b.jpg",
    "staged/c.jpg"
  ]);
  expect(getSessionMarker(), "the session is kept").toBe("a-perfectly-good-token");
  expect(replaced, "a timer must not take the screen away").toEqual([]);
});

test("a gated refusal of a stage is about the session, never recorded against the stage", () => {
  expect(stageRefusalIsPassLevel(gated())).toBe(true);
});

test("the draft drain does not start while gated, and stops before marking anything if gated mid-pass", () => {
  const runAt = DRAFTS.indexOf("async function runSync(): Promise<DwSyncResult> {");
  const run = DRAFTS.slice(runAt);
  const preCheck = run.indexOf("if (sessionOwesPasswordChange()) {");
  expect(preCheck, "the pre-pass check").toBeGreaterThan(-1);
  expect(preCheck, "after the who-is-signed-in check").toBeGreaterThan(run.indexOf("if (draftSessionUnknown()) {"));
  expect(preCheck, "before any work is listed").toBeLessThan(run.indexOf("const work = await pendingWork();"));
  expect(run).toContain("if (isUnreachable(error) || isCredentialExpiry(error)) throw error;");
  const caught = run.slice(run.indexOf("lastSyncedAt: Date.now(), failure: null }));"));
  const stop = caught.indexOf("if (isPasswordChangeRefusal(error)) {");
  expect(stop, "the mid-pass stop was located").toBeGreaterThan(-1);
  expect(stop).toBeLessThan(caught.indexOf("if (isUnreachable(error) || serverAskedForTime(error)) {"));
  expect(stop).toBeLessThan(caught.indexOf("await mutate(draft.localId"));
});

test("what the draft banner says about such a pass", () => {
  const idle = { workshopsCreated: 0, stagesSent: 0, mediaUploaded: 0, failed: 0, pending: 2, stoppedOffline: false };
  const stopped = syncOutcome({ ...idle, passwordChangeRequired: true });
  expect(stopped.kind).toBe("password");
  expect(stopped.title, "the wifi is fine").not.toBe("Still no connection");
  expect(stopped.title, "and the queue is waiting, not empty").not.toBe("Nothing to send");
  expect(syncOutcome({ ...idle, failed: 1, passwordChangeRequired: true }).kind).toBe("refused");
});
