"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect } from "react";
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
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import { PageSelvedge } from "@/components/PageSelvedge";
import { isAdmin, roleLabel, routeGuardFor } from "@/lib/permissions";

export function AppShell({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth();
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
