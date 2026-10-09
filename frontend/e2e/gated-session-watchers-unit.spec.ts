import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { outboxOutcome } from "@/components/OutboxBanner";
import { syncOutcome } from "@/components/designworkshop/DraftSyncBanner";
import { ApiError, PASSWORD_CHANGE_REQUIRED_HEADER, setSessionOwesPasswordChange } from "@/lib/api";
import { stageRefusalIsPassLevel } from "@/lib/designWorkshopStore";
import { sweepStagedObjects } from "@/lib/media";
import { acknowledgeOutboxTrouble, syncOutbox } from "@/lib/offline";

/**
 * A GATED TAB NEVER BOUNCES TO /login, AND NEVER PARKS QUEUED WORK.
 *
 * ── WHY ANY OF THIS IS NEEDED ───────────────────────────────────────────────────────────────────
 *
 * While an account owes a new password the server refuses every request outside a short allow-list
 * with a 401 carrying `X-Password-Change-Required`. `AppShell` replaces every protected page with the
 * password form, which keeps everything INSIDE it quiet. Two kinds of work are not inside it:
 *
 *   • the components mounted BESIDE `<AppShell>` in the protected layout, which the gate does not
 *     cover — a request from one of them was, before `apiFetch` learned the header, a hard bounce to
 *     /login with the token thrown away;
 *   • the two background drains, which run outside React altogether. The design-workshop drain wrote
 *     any answered refusal onto the stage or photograph as a PERMANENT one — "the repository refused
 *     stage …" over fieldwork nobody got wrong, held by `blocksRetry` long after the password was
 *     chosen. The records outbox stopped safely on a 401 but told the person their sign-in had
 *     expired, which is false and sends them to sign in again.
 *
 * ── WHAT IS PINNED ──────────────────────────────────────────────────────────────────────────────
 *
 *   1. the components beside `AppShell` are exactly three, and each is quiet while gated;
 *   2. neither drain starts while the session is known to be gated, and a gate raised mid-pass
 *      stops it before anything is marked;
 *   3. what each banner would SAY about such a pass is neither "sign-in expired" nor "no connection"
 *      nor "nothing to send".
 *
 * ⚠ LINE-ENDING AGNOSTIC: `\s` and `[\s\S]` throughout.
 */

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

/** The staged-object journal `lib/media.ts` keeps in localStorage, as the sweep reads it. */
const JOURNAL = "field_repo_staged_objects";

/**
 * A window just real enough for `apiFetch` and the sweep: a token, a journal, and a location to watch
 * for navigation. `protocol: "http:"` for `public-page-401-unit.spec.ts`'s reason.
 */
function installFakeWindow(journal: Record<string, number>) {
  const replaced: string[] = [];
  const store = new Map<string, string>([
    ["field_repo_token", "a-perfectly-good-token"],
    [JOURNAL, JSON.stringify(journal)]
  ]);
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
  // Module state shared with every other spec this worker runs.
  setSessionOwesPasswordChange(false);
  delete (globalThis as Record<string, unknown>).window;
  (globalThis as Record<string, unknown>).fetch = realFetch;
});

/* ────────────────────────────────────────────────────────────────────────────
 * 1. Beside AppShell
 * ──────────────────────────────────────────────────────────────────────────── */

test("exactly three components are mounted beside AppShell, so each one is reviewed for the gate", () => {
  // A FENCE, not a description: a fourth component added here runs over the password form with
  // nothing standing in front of it, and must answer for itself what it does while gated.
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
  // In the dependency list, so the check is made once the password is chosen.
  expect(ONBOARDING).toMatch(/\}, \[user, passwordChangeRequired, pathname, router\]\);/);
  expect(ONBOARDING).toMatch(/const \{ user, passwordChangeRequired \} = useAuth\(\);/);
});

test("the offline notice is not drawn over the gate, and does not announce a recovery there", () => {
  // It makes no request of its own; what it would do is draw "saving still works … held on this
  // device" over a form whose one button no outbox can carry.
  expect(OFFLINE_DIALOG).toContain("<OfflineDialog open={open && !passwordChangeRequired}");
  const online = OFFLINE_DIALOG.slice(OFFLINE_DIALOG.indexOf("const goOnline = useCallback("));
  const body = online.slice(0, online.indexOf("}, [toast]);"));
  expect(body.indexOf("if (gated.current) return;"), "the recovery toast stands down").toBeGreaterThan(-1);
  expect(body.indexOf("if (gated.current) return;")).toBeLessThan(body.indexOf("toast("));
});

test("the stale-build watcher makes no request at all, so it cannot bounce a gated tab", () => {
  // Its trigger is a chunk-load failure the browser reports, and its one action is a reload.
  expect(UPDATE_DIALOG).not.toMatch(/from "@\/lib\/api"/);
  expect(UPDATE_DIALOG).not.toMatch(/\bapiFetch\(|\bfetch\(/);
});

test("the theme provider waits for the gate before reading the account's preferences, and reads them after", () => {
  /*
    NOT BESIDE `AppShell` BUT ABOVE IT — `ThemeProvider` sits in the ROOT layout, so the gate never
    covers it. Its read of `/preferences/me` is not on the server's allow-list: keyed on the account
    alone it was refused once while gated and not asked again until a reload, leaving the account's
    saved look unread for the whole session. Keyed on the gate as well, it waits and then runs.
  */
  const THEME = read("components", "ThemeProvider.tsx");
  expect(THEME).toMatch(/const \{ user, loading: authLoading, passwordChangeRequired \} = useAuth\(\);/);
  expect(THEME).toContain("if (!hydrated || authLoading || !userId || passwordChangeRequired) return;");
  expect(THEME).toMatch(/\}, \[authLoading, hydrated, passwordChangeRequired, persist, userId\]\);/);
  // A look chosen while gated is HELD for the gate to clear rather than sent into the refusal — and
  // sent then INSTEAD of the read, which would put the row from before the change back on screen.
  expect(THEME).toContain("if (gated.current) heldBack.current = signedIn.current;");
  const effect = THEME.slice(THEME.indexOf("const held = heldBack.current === userId;"));
  expect(effect.indexOf("void persist(latest.current);"), "the held change is sent first").toBeLessThan(
    effect.indexOf('apiFetch<Partial<Preferences>>("/preferences/me")')
  );
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The records outbox
 * ──────────────────────────────────────────────────────────────────────────── */

test("the outbox does not start a pass while the session is known to be gated", async () => {
  // The real function. There is no IndexedDB in this process, so the queue reads as unreadable and
  // empty — which is beside the point: the answer is decided before a single entry is considered.
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
  // Mid-pass: the first refused request stops the pass in the pass-level catch.
  const caught = OUTBOX.slice(OUTBOX.indexOf("THE CREDENTIAL, NOT THE ENTRY, AND IT IS ASKED FIRST."));
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
  // A gate raised part way through: what was sent is still the answer, and the reason the rest is
  // waiting is said rather than lost to the count.
  const partly = outboxOutcome({ ...base, synced: 2, remaining: 4, passwordChangeRequired: true });
  expect(partly.kind).toBe("sent");
  expect(partly.description).toMatch(/new password/);
  expect(partly.description).toContain("4");
  // And a device that cannot read its own queue still says THAT first.
  expect(outboxOutcome({ ...base, storeUnreadable: true, passwordChangeRequired: true }).kind).toBe("unreadable");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2b. The staged-object sweep — a journal drain on a five-minute timer
 * ──────────────────────────────────────────────────────────────────────────── */

test("the orphan sweep does not start while the session is known to be gated", async () => {
  // A TIMER, so nothing a person does starts it — it would go on spending refused DELETEs behind the
  // gate every five minutes. Aged past the five-minute staleness window so a live session WOULD send it.
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
  // The token check that used to stop this loop relied on `apiFetch` CLEARING the token on a 401. The
  // gated 401 keeps it — so without the gate check the sweep would grind through the whole batch,
  // every DELETE refused.
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
  expect(store.get("field_repo_token")).toBe("a-perfectly-good-token");
  expect(replaced, "a timer must not take the screen away").toEqual([]);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. The design-workshop drafts
 * ──────────────────────────────────────────────────────────────────────────── */

test("a gated refusal of a stage is about the session, never recorded against the stage", () => {
  // `stageRefusalIsPassLevel` decides between "write this onto the stage, permanently" and "hand it to
  // the pass". The gate's 401 used to fall to the first, and `blocksRetry` then held the stage shut.
  expect(stageRefusalIsPassLevel(gated())).toBe(true);
});

test("the draft drain does not start while gated, and stops before marking anything if gated mid-pass", () => {
  const runAt = DRAFTS.indexOf("async function runSync(): Promise<DwSyncResult> {");
  const run = DRAFTS.slice(runAt);
  const preCheck = run.indexOf("if (sessionOwesPasswordChange()) {");
  expect(preCheck, "the pre-pass check").toBeGreaterThan(-1);
  expect(preCheck, "after the who-is-signed-in check").toBeGreaterThan(run.indexOf("if (draftSessionUnknown()) {"));
  expect(preCheck, "before any work is listed").toBeLessThan(run.indexOf("const work = await pendingWork();"));
  // The photograph leg hands it on instead of recording it against the file. It asks the wider
  // `isCredentialExpiry` — the gated 401 is a 401, so it is inside that row — because a plain expiry
  // must not be recorded against the file either (`draft-drain-credential-expiry-unit.spec.ts`).
  expect(run).toContain("if (isUnreachable(error) || isCredentialExpiry(error)) throw error;");
  // And the pass-level catch asks for it FIRST — above the arm that writes a refusal onto the draft.
  const caught = run.slice(run.indexOf('THE TEST IS "DID THE SERVER ANSWER", NOT `isTransient`.'));
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
  // A refusal recorded earlier in the same pass is still the one thing a person can act on.
  expect(syncOutcome({ ...idle, failed: 1, passwordChangeRequired: true }).kind).toBe("refused");
});
