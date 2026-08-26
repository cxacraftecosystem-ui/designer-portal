"use client";

/**
 * A designer signing in for the first time is taken to their profile page.
 *
 * The owner's instruction of 2026-08-25: *"When a designer logs into the application for the first
 * time, they should be automatically redirected to the Designer Page, where they can enter all of
 * their personal and professional information."* Now that `PREFILL_MAP` carries all twenty-one
 * profile columns into every report the designer generates, that page is the one screen whose
 * emptiness is felt on every document they will ever produce — so it is worth one redirect.
 *
 * Renders nothing. Mounted once, in the protected layout, beside `AppUpdateWatcher` and
 * `OfflineWatcher`, for the same reason those live there: it is a signed-in-only concern that must
 * apply to every protected page rather than to one of them.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE FOUR RULES THAT KEEP A REDIRECT FROM BECOMING A TRAP
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * A forced navigation is the most intrusive thing a client can do to somebody, and every one of
 * these exists because the naive version fails it.
 *
 * 1. **ONCE PER SESSION, WHATEVER HAPPENS NEXT.** The mark is written the moment the decision is
 *    taken, not when the profile is saved — so a designer who reads the page, decides they will do
 *    it this evening and navigates to the artisan list is NOT dragged back on the next page load.
 *    Keyed on the account id, in `sessionStorage`: a shared field laptop signs in and out as
 *    different people all day, and a `localStorage` mark would greet the second designer as though
 *    they were the first. Where the browser refuses `sessionStorage` outright the mark falls back to
 *    an in-memory Set that lasts as long as the loaded page — see `handled` below for what that
 *    does and, just as importantly, does not guarantee.
 *
 * 2. **ONLY A DESIGNER, NOT EVERYBODY WHO *MAY* RUN A WORKSHOP.** `canRunDesignWorkshops` is the
 *    predicate for the nav entry and the dashboard tile, and it is deliberately NOT the predicate
 *    here: its set is `{DESIGNER, ADMIN, MASTER_ADMIN}`, and an admin's first sign-in is spent
 *    empanelling people and fixing records, not writing a biography for a report they will never
 *    sign. Redirecting them would be the app misreading who they are on the one screen where first
 *    impressions are formed. The instruction says "a designer"; this reads it literally.
 *
 * 3. **NEVER OVER A DEEP LINK, AND `router.replace` NOT `push`.** A designer who followed a link to
 *    a specific workshop, or who is mid-way through any other route, has already told the app where
 *    they want to be — so the redirect only fires from the landing route a sign-in actually delivers
 *    somebody to. `replace` rather than `push` because a `push` would put the dashboard under the
 *    back button, and the round back arrow on the profile page would then return them to a dashboard
 *    they never chose to leave, which reads as the app fighting them.
 *
 * 4. **IT SAYS WHY.** `?welcome=1` is what the profile page reads to draw its explanation. An
 *    unexplained forced navigation is indistinguishable from a bug — the reader's first thought is
 *    "I clicked the dashboard and it sent me somewhere else" — and the one thing this feature must
 *    not do is teach a new designer that the app moves on its own.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY "EMPTY PROFILE" AND NOT A `firstLoginAt` COLUMN
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `designerProfileIsEmpty` already exists and already answers the question the feature is really
 * asking. A `firstLoginAt` timestamp would answer a DIFFERENT question — "have they been here
 * before" — and answer it worse: the designer who signs in, is shown the page, fills in nothing and
 * comes back tomorrow is exactly the person the redirect is for, and a timestamp would have spent
 * itself on them. It would also be a migration, a column and a write on a hot path, for a fact the
 * data already carries.
 *
 * The row is created empty by the GET itself (the server upserts rather than 404s), so "no profile"
 * and "an empty profile" are the same state and no caller has to tell them apart.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE STRICT-MODE DEADLOCK THIS SHIPPED WITH, AND HOW IT IS ACTUALLY AVOIDED
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `reactStrictMode: true` (`next.config.ts`), so React runs setup → cleanup → setup on EVERY mount in
 * development. §17 of the frontend contract names the shape that fails: a guard claimed on START
 * whose completion is gated on a `cancelled` flag. The first attempt claims it, is torn down, can no
 * longer finish, and the second attempt returns at the guard. Nothing ever happens.
 *
 * THE FIRST VERSION OF THIS FILE HAD EXACTLY THAT BUG, WHILE ITS HEADER CLAIMED IT DID NOT — which is
 * worth leaving on the record, because the false claim is what would have stopped the next reader
 * looking. It released `checking.current` in a `.finally()`, i.e. after the fetch settled, and
 * reasoned that the ref was "per-mount". IT IS PER-FIBER, and strict mode reuses the fiber: the
 * second setup runs SYNCHRONOUSLY, before the fetch resolves, and returned at the guard. The first
 * attempt then resolved, saw `cancelled`, and returned having written no mark and performed no
 * redirect. `[user, pathname, router]` never change again, so no third attempt was ever scheduled and
 * the owner-requested first-login redirect NEVER FIRED — 100% of the time in `npm run dev`, and in
 * production on any deps change while the request was in flight: `AuthProvider` handing down a fresh
 * `user` object after a `/me` re-read, or a designer clicking off `/dashboard` and back before the
 * profile GET returned.
 *
 * THE FIX IS `useEditDeepLink`'S, WHICH HAD AND FIXED THE SAME BUG: the guard is released in the
 * EFFECT CLEANUP, so a torn-down attempt hands it back on its way out and the re-run is free to
 * proceed. The `.finally()` release stays as well — it is what frees the guard after a completed
 * attempt within one mount — and the two together mean the guard is held only while an attempt is
 * both in flight AND still able to finish.
 *
 * The mark is still written only inside `if (!cancelled)`, and that half was always right: marking on
 * a discarded attempt would be the same deadlock one layer up, in `sessionStorage`, where a cleanup
 * cannot reach it.
 */

import { useEffect, useRef } from "react";
import { usePathname, useRouter } from "next/navigation";

import { useAuth } from "@/components/AuthProvider";
import { designerProfileIsEmpty, getMyDesignerProfile } from "@/lib/designers";

/** Where a designer is sent, and the route the redirect must never fire FROM. */
const PROFILE_PATH = "/designers/profile";

/**
 * The only route the redirect fires from — where a sign-in actually lands somebody.
 *
 * `/dashboard` is the post-login destination and is the whole of the set: it is the first protected
 * route a sign-in reaches, and any other path means the designer went somewhere on purpose (a
 * bookmark, a shared workshop link, a QR-code join), which rule 3 above is that the app does not
 * overrule. A Set rather than a bare comparison so that a second landing route, if one is ever
 * added, is one member and not a rewrite of the condition.
 *
 * `/` WAS A MEMBER AND IS NOT ONE, because it is a route this component can never see. It is
 * mounted from `app/(protected)/layout.tsx` and nowhere else, while `/` is served by `app/page.tsx`
 * OUTSIDE that layout — `(protected)/` has no `page.tsx` of its own — so `usePathname()` cannot
 * answer "/" while this component exists. The reasoning the member carried (that the landing page
 * bounces a signed-in visitor onward and a redirect racing that one would be two navigations in a
 * frame) described a case no reader could reach and no test could exercise, which is the kind of
 * stated design that outlives the code it was written about. Moving the mount up to the root layout
 * to make it real is the wrong direction: this calls `useAuth()` and the whole design assumes the
 * protected layout has already resolved `/me`, so it would then run for anonymous visitors on the
 * public landing page.
 */
const LANDING_PATHS = new Set(["/dashboard"]);

/** Session-scoped, per account. See rule 1 for why the session and not `localStorage`. */
function markKey(userId: string): string {
  return `field_repo_designer_onboarding:${userId}`;
}

/**
 * THE SAME-DOCUMENT HALF OF THE MARK, read before `sessionStorage` and written beside it.
 *
 * `alreadyHandled` cannot tell "no mark" from "storage refused the question", and it answers false
 * to both — deliberately, because a designer whose browser blocks site data must still be brought
 * here once. Without this Set that false was the whole story: nothing else in this file survives a
 * navigation (`checking` is per-fiber and handed back in the cleanup), the effect re-runs on every
 * landing-route visit, and so a storage-blocked tab re-fetched the profile and re-issued the
 * redirect every single time the designer pressed Dashboard. Being brought to a form once is a
 * welcome; being brought there on every return is the app fighting you, and it fell hardest on the
 * browsers that could not say why. Not redirecting at all where storage is unreadable was the other
 * way out and is worse — it silently deletes an owner-requested feature for exactly the population
 * that cannot report its absence.
 *
 * MODULE SCOPE, so it lives as long as the LOADED PAGE and not as long as the tab: a reload starts a
 * fresh module and the check is made once more. That is the honest ceiling of a fallback that
 * persists nothing, it is why the header of `alreadyHandled` says "per loaded page" rather than
 * "per session", and it is why the welcome card on the profile page promises no more than that.
 * Keyed on the account id for rule 1's reason — a shared field laptop signs in as several people a
 * day.
 */
const handled = new Set<string>();

/**
 * `sessionStorage` in a try/catch, because it THROWS rather than returning null when storage is
 * blocked — a managed browser with site data locked down does it, as does an origin the reader has
 * told the browser to block. NOT private browsing: a modern Safari private tab gets a working
 * `sessionStorage` of its own, and naming it here sent the reader looking at the wrong case and made
 * this look more common than it is. The same treatment `useAdminView` gives `localStorage`, and for
 * the same reason: an exception on this path would propagate out of an effect in the protected
 * layout and take every page down with it. A designer whose browser blocks storage gets the check
 * once per loaded page instead of once per session — `handled` above is what makes that "once" true
 * at all — which is a far better failure than a blank app.
 */
function alreadyHandled(userId: string): boolean {
  if (handled.has(userId)) return true;
  try {
    return window.sessionStorage.getItem(markKey(userId)) === "1";
  } catch {
    return false;
  }
}

function markHandled(userId: string): void {
  // In memory FIRST and unconditionally: it is the half that cannot throw, and the whole point of it
  // is to hold the mark when the line below does.
  handled.add(userId);
  try {
    window.sessionStorage.setItem(markKey(userId), "1");
  } catch {
    /* Blocked storage. See `handled` and `alreadyHandled` above. */
  }
}

export function DesignerProfileOnboarding() {
  const { user } = useAuth();
  const router = useRouter();
  const pathname = usePathname();
  const checking = useRef(false);

  useEffect(() => {
    // `user` is null until `AuthProvider` has resolved `/me`; there is nothing to decide yet, and
    // deciding on the absence would send every anonymous first paint to a designer form.
    if (!user) return;
    // Rule 2: a DESIGNER, not the wider set that may run a workshop.
    if (user.role !== "DESIGNER") return;
    // Rule 3: only from a landing route, and never from the destination (which would loop).
    if (!LANDING_PATHS.has(pathname) || pathname === PROFILE_PATH) return;
    if (alreadyHandled(user.id)) return;
    if (checking.current) return;

    checking.current = true;
    let cancelled = false;
    // `settled` is what stops the cleanup from releasing a guard a COMPLETED attempt has already
    // released — harmless today (the release is idempotent) but it keeps the two paths honest about
    // which one owns the hand-back, and makes the cleanup's intent readable.
    let settled = false;

    getMyDesignerProfile()
      .then((profile) => {
        // EVERYTHING BELOW IS INSIDE THE GUARD, INCLUDING THE MARK. See the strict-mode note above:
        // marking here on a discarded attempt is precisely the deadlock this file is written against.
        if (cancelled) return;
        markHandled(user.id);
        if (designerProfileIsEmpty(profile)) {
          router.replace(`${PROFILE_PATH}?welcome=1`);
        }
      })
      .catch(() => {
        // NOT MARKED, AND NOT RETRIED THIS RENDER. A designer signing in with no connection — the
        // ordinary case in the field — must not be redirected on the strength of a failed read: an
        // empty form would look like a profile that had been wiped. Leaving the mark unwritten means
        // the check is simply made again on the next landing-route visit, by which time there may be
        // signal. `checking` is released below so that next attempt can happen at all.
      })
      .finally(() => {
        settled = true;
        checking.current = false;
      });

    return () => {
      cancelled = true;
      // ── THE HALF THAT WAS MISSING, AND THE WHOLE FEATURE DEPENDED ON IT ──────────────────────────
      //
      // A teardown between "request issued" and "request resolved" leaves this attempt unable to
      // finish — it will see `cancelled` and return. So the guard MUST come back here, or the re-run
      // strict mode is about to perform returns at it and the redirect never happens at all. See the
      // header: that is precisely what shipped.
      //
      // Guarded on `settled` so a cleanup that runs AFTER a completed attempt does not clear a guard
      // some later attempt has since claimed. `checking` is not a lock on a resource, only a bar
      // against issuing two requests at once, but a guard that can be released by the wrong owner is
      // a guard that will be.
      if (!settled) checking.current = false;
    };
  }, [user, pathname, router]);

  return null;
}
