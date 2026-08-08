"use client";

import Link from "next/link";

import { useAuth } from "@/components/AuthProvider";
import { DynamicIslandNav } from "@/components/DynamicIslandNav";
import { WorkshopLogo } from "@/components/WorkshopLogo";
import { roleLabel } from "@/lib/permissions";

/**
 * The shared parts of the two screens that appear when the app cannot give somebody the page they
 * asked for: `app/not-found.tsx` (no route matched) and the `error.tsx` boundaries (a page threw).
 *
 * WHY THESE LOOK LIKE `RouteLocked` AND `AdminViewHidden` (components/AppShell.tsx).
 * This app already has a settled anatomy for "the page you asked for is not the page you are getting"
 * — a centred `.panel`, a round icon disc, one `<h1>`, a short explanation, and named ways out. Both
 * refusal screens in `AppShell` are built that way and `RestrictedPanel` is the reduced version of
 * the same thing. A 404 and a crash are the third and fourth members of that family, not a new kind
 * of screen, so they reuse the anatomy rather than inventing a panel that would be the only one of
 * its shape in the app.
 */

/**
 * The page frame for a dead end rendered OUTSIDE `app/(protected)/layout.tsx`.
 *
 * `app/not-found.tsx` and `app/error.tsx` are siblings of `(protected)`, not children of it, so they
 * get `AuthProvider` / `ThemeProvider` / `ToastProvider` / `AdminViewProvider` from the root layout
 * but NOT `AppShell` — no island, no skip link, and none of the top padding that clears the island.
 * This supplies exactly that much and nothing else. (`app/(protected)/error.tsx` renders inside
 * `AppShell` already and must not use this.)
 *
 * `pt-24` is `AppShell`'s island-clearance contract, repeated here for the same reason it exists
 * there: the island is `position: fixed` and would otherwise sit on top of the panel's heading.
 *
 * No route-change fade. `AppShell`'s `motion.main` replays on every navigation because it is keyed on
 * the pathname; these screens are terminal, are reached once, and a reader who has just been told
 * something is missing does not need it to arrive gradually. Nothing here animates, so there is no
 * reduced-motion branch to get wrong.
 */
export function DeadEndFrame({
  nav,
  children
}: {
  /**
   * Render the island navigation above the panel.
   *
   * `false` on the error boundary, and that is not a style choice. `app/error.tsx` is the boundary
   * for `app/(protected)/layout.tsx` itself — a layout's own failure is caught by its PARENT — and
   * the island is mounted in that layout, inside `AppShell`. If the island is what threw, drawing it
   * again inside the fallback throws again, React escalates past this boundary, and the reader lands
   * on precisely the bare framework screen this file exists to replace.
   */
  nav: boolean;
  children: React.ReactNode;
}) {
  const { user, loading } = useAuth();

  /**
   * Say which application this is whenever the island — which carries the mark itself — will not be
   * on screen. Somebody who followed a dead link out of a colleague's message and is not signed in
   * otherwise gets a white card on a tinted page and no answer to "what have I opened".
   *
   * Suppressed while `/me` is in flight, so the mark never appears and is then replaced by the
   * island a moment later: a logo that shows up only to be swapped for a different logo reads as a
   * page still loading, on a screen whose whole job is to say the loading is over.
   */
  const brand = !nav || (!loading && !user);

  return (
    <div className="min-h-screen bg-bg-0">
      {nav ? (
        <>
          {/* Same contract as AppShell: the island comes first in the tab order, so the keyboard
              needs a way straight past it to the content. Visible only while focused. */}
          <a
            href="#main-content"
            className="sr-only left-3 top-3 z-[60] rounded-md bg-purple-700 px-3 py-2 text-sm font-medium text-white focus:not-sr-only focus:fixed"
          >
            Skip to content
          </a>
          {/* Self-gating: DynamicIslandNav renders null while nobody is signed in, so a visitor who
              followed a dead link from outside the app gets no chrome and a signed-in designer gets
              every destination back without this file knowing which case it is in. */}
          <DynamicIslandNav />
        </>
      ) : null}
      <main id="main-content" tabIndex={-1} className="mx-auto max-w-7xl px-4 pb-12 pt-24">
        {brand ? (
          // The island's own lockup, same mark and same wordmark, so the two cannot drift apart.
          // Deliberately not a link: the panel below already names where to go, and a second,
          // quieter route out competes with the answer this screen exists to give.
          <div className="mb-8 flex items-center justify-center gap-2 text-ink-900">
            <WorkshopLogo className="h-8 w-8 rounded-lg" />
            <span className="font-display text-base font-bold tracking-tight">Design Prototype Workshop</span>
          </div>
        ) : null}
        {children}
      </main>
    </div>
  );
}

/** The centred panel itself. `children` is everything under the heading — copy, then actions. */
export function DeadEndPanel({
  icon,
  tone,
  title,
  children
}: {
  icon: React.ReactNode;
  /** `notice` is the purple disc every other refusal panel uses; `alert` marks a genuine fault. */
  tone: "notice" | "alert";
  title: string;
  children: React.ReactNode;
}) {
  return (
    // aria-live so a screen reader hears the outcome: on the error boundary this panel REPLACES the
    // page under a reader who is already on it, and a silent swap is indistinguishable from a page
    // that simply stopped responding.
    <section className="panel px-6 py-14 text-center" aria-live="polite">
      <div
        className={
          tone === "alert"
            ? "mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-error-100 text-error-600"
            : "mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-purple-50 text-purple-700"
        }
      >
        {icon}
      </div>
      <h1 className="font-display text-xl font-bold tracking-tight text-ink-900">{title}</h1>
      {children}
    </section>
  );
}

/** A paragraph of explanation, at the width and rhythm the other refusal panels use. */
export function DeadEndText({ children }: { children: React.ReactNode }) {
  return <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-ink-500">{children}</p>;
}

/**
 * The address that was asked for, or the reference of the failure — the one piece of the screen the
 * reader can copy into a message to whoever sent them the link or maintains the app.
 *
 * `break-all` because these are ids and paths with no spaces in them: without it a cuid inside a long
 * URL widens the panel and the whole page scrolls sideways on a phone.
 */
export function DeadEndDetail({ label, value }: { label: string; value: string }) {
  return (
    <p className="mx-auto mt-4 max-w-lg rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs text-ink-500">
      {label} <code className="break-all font-mono text-ink-700">{value}</code>
    </p>
  );
}

/**
 * The named ways out, chosen by who is asking.
 *
 * This is why one `not-found.tsx` can serve a signed-in designer and a signed-out visitor honestly:
 * `AuthProvider` is mounted in the ROOT layout, above this boundary, so the page knows which of the
 * two it is talking to. A designer who mistyped a workshop id is offered their work; somebody who
 * followed a dead link from outside is offered the front door. Neither is shown the other's dead end.
 *
 * While `/me` is still in flight the LINKS are withheld rather than guessed at — offering "Sign in"
 * to somebody who is already signed in, and then swapping it out from under their thumb, is worse
 * than one sentence of waiting. `AppShell` holds the same line with "Opening the repository…".
 * `children` is never withheld: the error boundary's "Try again" has nothing to do with who is
 * signed in, and a crash screen whose one useful button waits on a network call is a crash screen
 * with no button at all for exactly the readers most likely to be offline.
 */
export function DeadEndWayBack({
  identity,
  children
}: {
  /**
   * Name the reader's tier under the buttons. The 404 wants it — "or it is not one this account can
   * open" is only checkable if you know what this account is — and the crash screen does not: a
   * fault in the app has nothing to do with the reader's rank, and saying so implies it does.
   */
  identity?: boolean;
  /**
   * The caller's own primary action, placed first in the same row. The error boundary's is "Try
   * again", which is a button running `reset()` and so can never be one of the links below.
   */
  children?: React.ReactNode;
}) {
  const { user, loading } = useAuth();

  /**
   * Whether the first LINK is the primary button, derived rather than passed: a caller that supplied
   * its own primary action has already answered "what should I press", and a second filled purple
   * button beside it would ask the question again. Deriving it means the two cannot disagree.
   */
  const lead = !children;

  return (
    <>
      {identity && !loading ? (
        <p className="mt-3 text-xs text-ink-500">
          {user ? (
            <>
              You are signed in as <span className="font-medium text-ink-700">{roleLabel(user.role)}</span>.
            </>
          ) : (
            "You are not signed in. Records in the repository are only visible to a signed-in account."
          )}
        </p>
      ) : null}
      <div className="mt-7 flex flex-wrap items-center justify-center gap-3">
        {children}
        {loading ? null : user ? (
          <>
            <Link href="/dashboard" className={lead ? "field-button" : "field-button-secondary"}>
              Back to dashboard
            </Link>
            {/* Android-parity label for /search (NAV_ITEMS). Offered here rather than the walkthrough
                because somebody who arrived at a dead end was looking for a particular record, and
                this is the page that finds one. */}
            <Link href="/search" className="field-button-secondary">
              Browse records
            </Link>
          </>
        ) : (
          <>
            <Link href="/login" className={lead ? "field-button" : "field-button-secondary"}>
              Sign in
            </Link>
            <Link href="/" className="field-button-secondary">
              Go to the home page
            </Link>
          </>
        )}
      </div>
      {loading ? <p className="mt-3 text-xs text-ink-500">Checking your session…</p> : null}
    </>
  );
}
