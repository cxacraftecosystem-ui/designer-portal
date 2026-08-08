import { expect, test, type Page } from "@playwright/test";

import { signIn as signInAs } from "./support/session";

/**
 * A SHARED FIELD LAPTOP MUST NOT FILE ONE DESIGNER'S FORTNIGHT UNDER ANOTHER DESIGNER'S NAME.
 *
 * `DwDraft.ownerUserId` has always been written and nothing ever read it. So: designer A captures a
 * workshop in a courtyard, where it lives only in this browser's IndexedDB with `remoteId: null`; A
 * signs out — `AuthProvider.logout` clears the token and deliberately nothing else, because A's
 * fortnight has to survive the handover; designer B signs in on the same laptop.
 * `DesignWorkshopDraftBanner` drains on mount with no click at all, `pendingWork` and `runSync`
 * walked every draft in the database with no reference to the owner, and B's session POSTed A's
 * workshop under B's credentials. A's fortnight is then filed under B's name and
 * `load_workshop_or_404` 404s A out of their own record — a loss with no undo and nothing on screen
 * that ever mentioned it.
 *
 * The spec drives the real handover: one browser context, two sessions, the app's own automatic
 * drain. Its second half is as important as the first — a fix that simply refused to send anything
 * would "pass" the first half while stranding every workshop captured in the field, so the spec
 * signs A back in and requires the workshop to reach the repository under A.
 *
 * The offline create is expressed as an ABORTED POST rather than `context.setOffline`, because the
 * form's own fallback treats a failed create exactly the same way (see its `isTransient` branch)
 * and a truly offline context also breaks `router.push`, which leaves the tab on the browser's
 * error page where IndexedDB cannot be read.
 */

const API = process.env.E2E_API_URL ?? "http://localhost:8000";
const PASSWORD = process.env.E2E_PASSWORD ?? "";

/**
 * Two REAL accounts, and BOTH must satisfy `can_run_design_workshops` — the one predicate in
 * `deps.py` that is a SET (Designer, Admin, Master Admin) rather than a rank threshold. Two
 * designers is the actual field situation, and it is also the only pairing that reproduces the
 * WORST outcome: if B could not create a design workshop at all, B's session would merely mark A's
 * draft permanently refused instead of filing A's fortnight under B's name. The spec asserts
 * against both harms, but the account pairing has to make the first one reachable.
 */
const A = { email: "designer@example.org", password: PASSWORD };
const B = { email: "admin2@example.org", password: PASSWORD };

test.skip(!PASSWORD, "Set E2E_PASSWORD to run the signed-in specs.");

/**
 * This spec is about WHOSE draft a workshop is, so it signs in as two different people; the
 * ceremony — planting the token instead of driving the form — lives in support/session, along with
 * the hydration race and the login throttle it exists to avoid.
 */
async function signIn(page: Page, who: { email: string; password: string }) {
  await signInAs(page, who.email, who.password);
}

/**
 * The handover, as the app performs it.
 *
 * `AuthProvider.logout` is `setToken(null)` plus `setUser(null)` and a POST that cannot fail
 * usefully; the nav sheet that carries the button is not what is under test. Going to /login on a
 * fresh document is what a laptop handed over actually looks like.
 */
async function signOut(page: Page) {
  await page.goto("/dashboard");
  await page.evaluate(() => window.localStorage.removeItem("field_repo_token"));
  await page.goto("/login");
}

/** Every draft in this browser, with the two fields this spec is about. */
async function drafts(page: Page) {
  for (let attempt = 0; ; attempt += 1) {
    try {
      return await page.evaluate(async () => {
        const db: IDBDatabase = await new Promise((resolve, reject) => {
          const open = indexedDB.open("design-workshop-drafts");
          open.onsuccess = () => resolve(open.result);
          open.onerror = () => reject(open.error);
        });
        const rows: Record<string, unknown>[] = await new Promise((resolve, reject) => {
          const rq = db.transaction("drafts", "readonly").objectStore("drafts").getAll();
          rq.onsuccess = () => resolve(rq.result as Record<string, unknown>[]);
          rq.onerror = () => reject(rq.error);
        });
        return rows.map((row) => ({
          localId: row.localId as string,
          remoteId: (row.remoteId as string | null) ?? null,
          ownerUserId: (row.ownerUserId as string | null) ?? null,
          title: ((row.header as Record<string, unknown>)?.title as string) ?? "",
          failure: (row.failure as { message: string; permanent: boolean } | null) ?? null
        }));
      });
    } catch (error) {
      if (attempt >= 8) throw error;
      await page.waitForTimeout(500);
    }
  }
}

test("a second designer signing in on the same laptop does not drain the first one's workshop", async ({ page }) => {
  const title = `Owner handover ${Date.now()}`;

  /* ── Designer A captures a workshop with no connection ────────────────────────────────── */
  await signIn(page, A);
  await page.route("**/api/design-workshops", async (route) => {
    if (route.request().method() !== "POST") return route.fallback();
    // A courtyard: the request never reaches a server. The create form's own fallback banks the
    // workshop locally, which is the state this whole feature exists to make usable.
    await route.abort("internetdisconnected");
  });
  await page.goto("/design-workshops");
  await page.getByRole("button", { name: /new design workshop/i }).click();
  await page.locator('input[name="title"]').fill(title);
  await page.getByRole("button", { name: /create design workshop/i }).click();
  await page.waitForURL(/\/design-workshops\/dwlocal-/, { timeout: 30_000 });

  const captured = (await drafts(page)).filter((draft) => draft.title === title);
  expect(captured, "A's workshop was banked on this device").toHaveLength(1);
  expect(captured[0].remoteId, "and has never reached the repository").toBeNull();
  expect(captured[0].ownerUserId, "and is stamped with the designer who captured it").toBeTruthy();
  const ownerOfA = captured[0].ownerUserId;

  /* ── The laptop is handed over ────────────────────────────────────────────────────────── */
  // The courtyard lasts until A has gone. A's own session drains on every navigation and would
  // otherwise legitimately create the workshop before B ever signs in, which would leave this spec
  // proving nothing at all. The connection comes back for B — which is the whole hazard: B is the
  // one with signal.
  await signOut(page);
  await page.unroute("**/api/design-workshops");
  expect(
    (await drafts(page)).find((draft) => draft.title === title)?.remoteId,
    "A's workshop is still unsent at the moment the laptop changes hands"
  ).toBeNull();
  await signIn(page, B);

  // B's session drains on mount, and every navigation remounts the banner — which is exactly how
  // A's workshop used to be created under B's credentials with nobody pressing anything.
  await page.goto("/design-workshops");
  await page.waitForTimeout(2_000);
  await page.goto("/dashboard");
  await page.waitForTimeout(2_000);

  const underB = (await drafts(page)).filter((draft) => draft.title === title);
  expect(underB, "A's draft is still on the laptop — a handover must not delete it").toHaveLength(1);
  expect(
    underB[0].remoteId,
    "B's session must not have created A's workshop in the repository under B's name"
  ).toBeNull();
  expect(underB[0].ownerUserId, "and the owner is unchanged").toBe(ownerOfA);
  expect(
    underB[0].failure,
    "nor may it be marked as permanently refused — that is what stops A retrying it for good"
  ).toBeNull();

  // Nor is it on B's screen. Somebody else's unsent fieldwork is not B's to see or to send.
  await page.goto("/design-workshops");
  await page.waitForTimeout(1_000);
  await expect(page.getByText(title), "A's unsent workshop is not listed in B's session").toHaveCount(0);

  /* ── A signs back in, and the workshop finally lands. NOT STRANDED. ───────────────────── */
  await signOut(page);
  await signIn(page, A);
  await page.goto("/design-workshops");

  let remoteId: string | null = null;
  for (let attempt = 0; attempt < 30 && !remoteId; attempt += 1) {
    const rows = (await drafts(page)).filter((draft) => draft.title === title);
    remoteId = rows[0]?.remoteId ?? null;
    if (!remoteId) {
      const sync = page.getByRole("button", { name: /sync now/i });
      if (await sync.count()) await sync.first().click().catch(() => undefined);
      await page.waitForTimeout(1_000);
    }
  }
  expect(remoteId, "A's own session sends A's workshop — the guard must not strand fieldwork").toBeTruthy();

  // And the repository agrees it belongs to A.
  const login = await page.request.post(`${API}/api/auth/login`, { data: A });
  const token = (await login.json()).accessToken as string;
  const detail = await page.request.get(`${API}/api/design-workshops/${remoteId}`, {
    headers: { Authorization: `Bearer ${token}` }
  });
  expect(detail.ok(), `A can read the workshop A captured: ${await detail.text()}`).toBeTruthy();
  expect((await detail.json()).title).toBe(title);
});
