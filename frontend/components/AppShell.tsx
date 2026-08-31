"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { motion } from "framer-motion";
import { EyeOff, Lock } from "lucide-react";

import {
  adminChromeRouteFor,
  adminChromeVisible,
  useAdminView,
  type AdminChromeRoute
} from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { DynamicIslandNav } from "@/components/DynamicIslandNav";
// The SAME form `/login` renders. See its header: two hosts, one password vocabulary.
import { FirstPasswordGate } from "@/components/FirstPasswordGate";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import { PageSelvedge } from "@/components/PageSelvedge";
import { WorkshopLogo } from "@/components/WorkshopLogo";
import { isAdmin, roleLabel, routeGuardFor } from "@/lib/permissions";
import { mustChangePassword } from "@/lib/signIn";

export function AppShell({ children }: { children: React.ReactNode }) {
  const { user, loading, logout, refreshMe } = useAuth();
  const { adminMode, adminViewResolved, setAdminView } = useAdminView();
  const router = useRouter();
  const pathname = usePathname();
  /**
   * The route-change fade below is the one animation nobody using this app can avoid — it replays on
   * every navigation, on every protected page — so it is also the one that most needs the preference
   * honoured. `useAppReducedMotion()` and not framer's `useReducedMotion()`: the latter sees only the
   * OS media query, and this app's second switch is the Settings toggle, which stamps
   * `data-reduced-motion="true"` on <html> and reaches CSS but never JavaScript.
   *
   * Branching `initial` as well as the transition is safe HERE, unlike on the public hero. `loading`
   * starts true, so the server and the first client render both paint the "Opening the repository…"
   * frame and `motion.main` does not exist to mismatch; by the time `/me` has answered and it first
   * mounts, ThemeProvider's mount effect has long since read the stored preference. Every later
   * navigation remounts it through `key={pathname}`, which is when `initial` is read again.
   */
  const reduce = useAppReducedMotion();
  /**
   * THIS VISIT HAS ALREADY REPLACED THE TEMPORARY PASSWORD — a latch, not a copy of the flag.
   *
   * `changeOwnPassword` has succeeded server-side by the time this is set, and the `refreshMe()`
   * that would prove it is best-effort: on the connections this product is used over, a dropped
   * request between two calls is an ordinary event. Without the latch that dropped request re-locks
   * somebody the second after they complied, and the gate then tells them the current password they
   * have just replaced is wrong.
   *
   * A LATCH AND NOT A STORED COPY OF `mustChangePassword`, and the direction matters: the condition
   * below reads the LIVE account, so an administrator who sets the flag on a session that is already
   * open is obeyed at that session's next `/me`. A copy taken at mount would be a copy that only
   * ever goes stale in the direction that lets somebody through.
   */
  const [passwordSet, setPasswordSet] = useState(false);

  useEffect(() => {
    if (!loading && !user) router.replace("/login");
  }, [loading, router, user]);

  if (loading) {
    return (
      <main className="flex min-h-screen items-center justify-center bg-bg-0 text-sm text-ink-500">
        Opening the repository…
      </main>
    );
  }

  if (!user) return null;

  /**
   * ══════════════════════════════════════════════════════════════════════════════════════════════
   * THE FIRST-LOGIN PASSWORD, HELD ABOVE THE APP AND NOT ONLY AT THE DOOR
   * ══════════════════════════════════════════════════════════════════════════════════════════════
   *
   * ── THE GAP THIS CLOSES ───────────────────────────────────────────────────────────────────────
   *
   * `FirstPasswordGate` landed on /login and fired NOWHERE ELSE, so the obligation was enforced
   * against people arriving and against nobody already inside. An administrator who resets somebody's
   * password through `PATCH /api/users/{id}` sets `mustChangePassword` on an account whose browser
   * tab is open, and that tab went on working indefinitely, because a session that never revisits
   * /login never meets the door. The server REPORTS and deliberately never refuses — the only route
   * that can change a password needs a bearer token, so a 403 at sign-in would be a demand the
   * account could never satisfy — which makes the client the WHOLE of the enforcement, and half of
   * the client was not enforcing. The handset has gated here since it landed
   * (`ui/PasswordGate.kt`, a `when` arm replacing `HomeScreen`); this is the web's half of the same
   * arrangement.
   *
   * ── WHY IT IS AN EARLY RETURN AND NOT A PANEL INSIDE `<main>` ─────────────────────────────────
   *
   * Because everything below this line is the app. The island alone offers twenty destinations, the
   * admin-view toggle and sign-out; a lock drawn inside `motion.main` would leave every one of them
   * live under it, which is a picture of a gate rather than a gate. Returning here is what makes
   * this a full surface BETWEEN sign-in and the product — the shape `UsageConsentGateScreen` argues
   * for at length and the shape `FirstPasswordGate`'s own header inherits.
   *
   * ── AND WHY IT IS ABOVE `ROUTE_GUARDS` AND ABOVE ADMIN VIEW ───────────────────────────────────
   *
   * Those two answer "may this person open THIS route". This one answers "may this person use the
   * product at all", so it is the outer question and is asked first. Below them, an account that
   * happens to fail a route guard would be shown the honest permission copy inside full chrome while
   * still holding a password an administrator typed — the gate silently not applying on exactly the
   * screens that already refuse something.
   *
   * ── THE SETTLING RULE (§7.10), AND WHY THERE IS NOTHING EXTRA TO WAIT FOR ─────────────────────
   *
   * Chrome that merely HIDES may act on an unsettled answer; anything that LOCKS must hold the frame
   * until the answer is known. Admin view needs `adminViewResolved` for that because its answer
   * arrives from localStorage one commit after the account does. This flag does not: it rides on the
   * same `/me` payload as the user, so "known" is exactly `!loading` — which is why this branch sits
   * BELOW the loading frame and below the signed-out return above, and not one line higher. Moved
   * above them it would paint a lock for somebody who does not need one, or, worse, paint the app for
   * one commit for somebody who does.
   *
   * ── WHAT THIS DOES NOT PROMISE ────────────────────────────────────────────────────────────────
   *
   * It is not a poll. The flag is re-read whenever `/me` is — a page load, or any `refreshMe()` — so
   * a reset performed while a tab sits idle is met the next time that tab reads the account, not
   * within the second. That is the honest reach of a client-side gate over a fact the server states
   * and does not enforce, and it is a great deal more than "the next visit to /login", which was the
   * previous answer.
   *
   * ── THE ESCAPE, AND THE ONE DOOR THIS MUST NEVER STAND IN FRONT OF ────────────────────────────
   *
   * "Sign out instead" is inside the form, for `UsageConsentGateScreen`'s reason. It matters more
   * here than at the door: an administrator who resets a password WITHOUT telling anybody leaves a
   * person who cannot fill this form in at all, because the route requires the current password and
   * they do not know it. Their way back is the link that administrator issues, redeemed at
   * `/set-password` — which is public and lives OUTSIDE `app/(protected)/`, so this gate is not in
   * front of it and must never be moved anywhere that would put it there.
   */
  if (!passwordSet && mustChangePassword(user)) {
    return (
      <FirstPasswordLocked
        onDone={() => {
          setPasswordSet(true);
          // Best-effort and deliberately not awaited into the branch, the treatment /login gives its
          // own re-read: the password IS set by this point, and the latch above is what closes the
          // gate either way. This is only a cache invalidation.
          refreshMe().catch(() => undefined);
        }}
        onSignOut={() => {
          // `logout` clears the session, which drops `user`, which sends the effect above to /login.
          // It does not continue into the app — a way out is not a way in.
          logout().catch(() => undefined);
        }}
      />
    );
  }

  /**
   * Page-level enforcement for the whole protected tree. Hiding a nav entry only removes the link —
   * /users, /review, /data and the create forms are still one typed URL away — so the guard table in
   * lib/permissions.ts is applied HERE, above every page, and the page never renders at all when the
   * user fails it. Pages that also guard themselves are simply defended twice.
   */
  const guard = routeGuardFor(pathname);
  const blocked = Boolean(guard && !guard.can(user));

  /**
   * The second, softer gate: admin view. It is consulted only AFTER the real one has passed, so a
   * user who genuinely may not open the route always gets the honest permission copy and never the
   * "you turned this off" copy. Because it is a preference and not a permission it can only ever
   * subtract — `adminChromeVisible` returns true for everyone who has no toggle, so a professor
   * keeps /users and a forged `adminMode` in localStorage unlocks nothing (the guard above still
   * ran, and `adminMode` itself is recomputed from the server-issued role every render).
   */
  const chrome = blocked ? null : adminChromeRouteFor(pathname);
  // One commit exists where the account is known but its stored preference is not. Deciding then
  // would flash a lock at every admin who browses with admin view ON, so we hold the frame instead.
  const chromeSettling = Boolean(chrome) && isAdmin(user) && !adminViewResolved;
  const chromeHidden = Boolean(chrome) && !adminChromeVisible(user, adminMode);

  return (
    <div className="min-h-screen bg-bg-0">
      {/* The island is a floating pill and comes first in the tab order — give the keyboard a way
          past it straight to the page content. Visible only while focused. */}
      <a
        href="#main-content"
        className="sr-only left-3 top-3 z-[60] rounded-md bg-purple-700 px-3 py-2 text-sm font-medium text-white focus:not-sr-only focus:fixed"
      >
        Skip to content
      </a>
      <DynamicIslandNav />
      {/* Decorative only — see PageSelvedge. It is `fixed z-0`, so `main` below carries an explicit
          `relative`: two positioned elements at the same stacking level paint in tree order, and the
          selvedge comes first, so `main` covers it at every width between `md` and the point the
          content column stops filling the viewport. Without the `relative` the strips would sit ON
          the page rather than behind it.

          `main` carries NO z-index, and that omission is the whole reason two full-screen surfaces
          are reachable at all. `z-10` here made `main` a STACKING CONTEXT, and everything drawn
          inside it — the media lightbox and the rich-text editor's full-screen mode, both fixed and
          both at a higher rung than the island — was then capped at that context's level 10 and
          painted UNDERNEATH the nav island, which lives outside it and still takes pointer events.
          A click on the pill navigated away from a surface that had just declared itself
          `aria-modal`, and the unsaved-changes interception does not cover the island's links. An
          `auto` z-index on a positioned element creates no stacking context, so removing the class
          keeps the painting order above and lets those surfaces reach their declared rung.

          The cost of that, and it is paid in DynamicIslandNav rather than here: the cap worked in
          both directions. Fixed chrome a PAGE mounts — `UploadTray`, `z-40` — was also held under
          level 10, and so under the nav sheet's scrim, which was `z-40` too. Uncapped, the two meet
          in the root stacking context and tree order decides; the nav renders first, so the dock
          would have painted over an open `aria-modal` sheet. The sheet's overlay is `z-[90]` now
          for exactly that reason. Anything else mounted inside `main` is at or below `z-20` or is
          one of the two `z-[100]` surfaces above. */}
      <PageSelvedge />
      <motion.main
        id="main-content"
        tabIndex={-1}
        key={pathname}
        initial={reduce ? { opacity: 1, y: 0 } : { opacity: 0, y: 6 }}
        animate={{ opacity: 1, y: 0 }}
        transition={reduce ? { duration: 0 } : { duration: 0.22, ease: "easeOut" }}
        className="relative mx-auto max-w-7xl px-4 pb-12 pt-24"
      >
        {blocked && guard ? (
          <RouteLocked title={guard.title} message={guard.message} role={roleLabel(user.role)} />
        ) : chrome && chromeSettling ? (
          <section className="panel p-6 text-sm text-ink-500">Checking your admin view…</section>
        ) : chrome && chromeHidden ? (
          <AdminViewHidden route={chrome} onEnable={() => setAdminView(true)} />
        ) : (
          children
        )}
      </motion.main>
    </div>
  );
}

/**
 * THE SURFACE A PERSON MEETS INSTEAD OF THE APP while their account still holds a password somebody
 * else chose.
 *
 * ── IT IS THE AUTH FRAME, NOT THE REFUSAL FRAME, AND THAT IS DELIBERATE ───────────────────────
 *
 * `RouteLocked` and `AdminViewHidden` below are panels drawn INSIDE the shell, under the island,
 * because both are answers about one route and the rest of the app is still the reader's to use.
 * This one replaces the shell outright, so it has to supply its own frame — and it supplies the
 * SIGN-IN card's frame rather than a wider page: the mark, one heading, one column of 52px boxes.
 * Somebody who reloaded a tab and met this needs to recognise in one glance that they are being
 * asked for a credential, not told that something is broken or forbidden.
 *
 * ── THE MARK IS HERE FOR `DeadEndFrame`'S REASON ──────────────────────────────────────────────
 *
 * The island carries the wordmark and the island is not on screen. Without it this is a white card
 * on a tinted page with no answer to "what have I opened" — which is the wrong thing to wonder on
 * the one screen in the product that asks for a password.
 *
 * ── NO `aria-live`, THOUGH BOTH PANELS BELOW HAVE ONE ─────────────────────────────────────────
 *
 * The form already opens with a `role="status"` box carrying the sentence that matters ("An
 * administrator set your password. Choose your own to continue."), and it is present from this
 * surface's first paint. A live region wrapped around a live region announces the same demand twice,
 * which on a screen reader is indistinguishable from the app repeating itself.
 *
 * ── NO COPY OF ITS OWN BEYOND THE HEADING ─────────────────────────────────────────────────────
 *
 * Terse, and one vocabulary: the explanation, the length rule, the mismatch sentence and both
 * buttons all live in `FirstPasswordGate`, which /login renders too. Anything added here would be a
 * sentence only half the people who meet this gate ever see.
 */
function FirstPasswordLocked({ onDone, onSignOut }: { onDone: () => void; onSignOut: () => void }) {
  return (
    <div className="grid min-h-screen place-items-center bg-bg-0 px-4 py-12">
      <main id="main-content" tabIndex={-1} className="w-full max-w-md">
        <div className="mb-6 flex flex-col items-center gap-2 text-center">
          <WorkshopLogo className="h-12 w-12 rounded-xl shadow-sm" />
          {/* Android's `PasswordGateScreen` heading, word for word — a designer refused on the phone
              opens the website next, and a different sentence there reads as a different demand. */}
          <h1 className="font-display text-2xl font-bold text-ink-900">Set your own password</h1>
        </div>
        <section className="panel p-6">
          {/* Always "" here: the protected tree never saw a password typed at a door, so the form
              draws its "Current password" box. Android's gate does the same for the same case — a
              session that was already open when the app was launched. */}
          <FirstPasswordGate currentPassword="" onDone={onDone} onSignOut={onSignOut} />
        </section>
      </main>
    </div>
  );
}

/**
 * What a user sees instead of a page they may not open. It names their tier and points at the two
 * routes that are always available — the dashboard and the walkthrough — rather than dead-ending.
 */
function RouteLocked({ title, message, role }: { title: string; message: string; role: string }) {
  return (
    <section className="panel px-6 py-14 text-center" aria-live="polite">
      <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-purple-50 text-purple-700">
        <Lock className="h-5 w-5" aria-hidden />
      </div>
      <h1 className="font-display text-xl font-bold tracking-tight text-ink-900">{title}</h1>
      <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-ink-500">{message}</p>
      <p className="mt-3 text-xs text-ink-500">
        You are signed in as <span className="font-medium text-ink-700">{role}</span>. An admin can raise your access.
      </p>
      <div className="mt-7 flex flex-wrap items-center justify-center gap-3">
        <Link href="/dashboard" className="field-button">
          Back to dashboard
        </Link>
        <Link href="/guide" className="field-button-secondary">
          Open the walkthrough
        </Link>
      </div>
    </section>
  );
}

/**
 * What an ADMIN sees on an admin route while their own admin view is off.
 *
 * Deliberately not RouteLocked: this is self-inflicted and one click from being undone, so it must
 * not borrow the padlock, the "access required" title or the "an admin can raise your access" line
 * that a genuine non-admin sees — telling an admin they lack access they in fact hold is the worse
 * error of the two. The primary action turns the toggle back on and leaves them exactly where they
 * are, so the page they asked for appears in place rather than after a detour.
 */
function AdminViewHidden({ route, onEnable }: { route: AdminChromeRoute; onEnable: () => void }) {
  return (
    <section className="panel px-6 py-14 text-center" aria-live="polite">
      <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-surface-50 text-ink-500">
        <EyeOff className="h-5 w-5" aria-hidden />
      </div>
      <h1 className="font-display text-xl font-bold tracking-tight text-ink-900">
        {route.label} is hidden while admin view is off
      </h1>
      <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-ink-500">
        {route.blurb} You switched admin view off, so the repository is behaving exactly as it does for an ordinary
        user.
      </p>
      <p className="mt-3 text-xs text-ink-500">
        Your access has not changed — this is your own setting, not a permission you are missing.
      </p>
      <div className="mt-7 flex flex-wrap items-center justify-center gap-3">
        <button type="button" onClick={onEnable} className="field-button">
          Turn admin view back on
        </button>
        {route.alternative ? (
          <Link href={route.alternative.href} className="field-button-secondary">
            {route.alternative.label}
          </Link>
        ) : null}
        <Link href="/dashboard" className="field-button-secondary">
          Back to dashboard
        </Link>
      </div>
    </section>
  );
}
