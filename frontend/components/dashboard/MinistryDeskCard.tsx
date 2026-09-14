"use client";

import Link from "next/link";
import { ArrowUpRight } from "lucide-react";

import { ministryDeskFor } from "@/components/dashboard/ministryDesk";
import { useAuth } from "@/components/AuthProvider";
import { canSeeMinistryDesk } from "@/lib/permissions";

/**
 * THE MINISTRY DESK — the dashboard card for the three directorate posts and the master admin.
 *
 * The table it draws, the order of it, and the per-row predicates live in
 * `components/dashboard/ministryDesk.ts`, because there is no React renderer in devDependencies and
 * a judgement written inside JSX is only ever exercised by somebody looking at a screen. This file
 * is the renderer and the two gates' call site, and nothing else.
 *
 * ── WHY IT IS ON THE DASHBOARD AND NOT IN THE SETTINGS HUB ──────────────────────────────────────
 *
 * Because the three tiers this card exists for cannot open the settings hub. `/admin` gates on
 * `isAdmin`, which is set membership on {ADMIN, MASTER_ADMIN} on both sides of the wire, and it is
 * enforced twice — a `ROUTE_GUARDS` row and a re-check inside `app/(protected)/admin/page.tsx`. A
 * card placed there would render for the master admin alone and would be invisible to the three
 * posts it was written for, which is the trap the `/annual-plan` route rule already records: a rule
 * that is WIDER than `/admin` must not sit underneath it. Widening `/admin` to fix that would hand
 * four ministry accounts the designer roster, the access roster, the deleted-workshop trash and
 * cross-workshop analytics in order to show them a card — a permission change to solve a layout
 * problem, and the one thing a launching surface must never cost.
 *
 * The dashboard is the screen the app opens on and the one place in this product a destination is
 * genuinely easy to reach, so that is where it is.
 *
 * ── ABOVE THE TILE GRID, NOT BELOW IT ───────────────────────────────────────────────────────────
 *
 * A ministry officer's tile grid is about nineteen cards of a designer's work — artisan, product,
 * process, tool, sketches, review rounds — and not one of their own screens is in it. Putting their
 * desk underneath all of that answers "gather them so they are easy to reach" with "scroll past
 * everything else first". Nothing about the grid changes: this is a sibling section rendered before
 * it, the `tiles` array is untouched, and every account outside this card's audience sees a
 * dashboard byte-identical to the one they saw before.
 *
 * ── NOT ADMIN CHROME, AND NOT A PERMISSION ──────────────────────────────────────────────────────
 *
 * Deliberately not wrapped in the dashboard's `adminSurface` helper: the admin-view toggle exists
 * only for an account `isAdmin` admits, so flagging this would hide the card from the one member of
 * the audience who has a toggle — the master admin — while leaving it on screen for the three tiers
 * below. The `/annual-plan` nav entry carries the same reasoning in full and is the only other
 * Admin-group entry without the flag.
 *
 * And nothing here opens a door. `canSeeMinistryDesk` decides whether the CARD is drawn;
 * `MinistryDestination.can` decides each ROW, and every one of those is the destination's own
 * predicate imported from the module that defines it. The card cannot show a row the route would
 * refuse, and it cannot make a route accept somebody it would not — `e2e/ministry-desk-unit.spec.ts`
 * asserts exactly that, per tier, per row, against `canAccessRoute`. `lib/permissions.ts` carries the
 * argument for why the card's audience is a set of its own rather than `isAdmin`, a rank floor, or a
 * borrowed predicate.
 */
export function MinistryDeskCard() {
  const { user } = useAuth();
  if (!canSeeMinistryDesk(user)) return null;

  const open = ministryDeskFor(user);

  /*
    A HUB WITH NOTHING IN IT IS WORSE THAN NO HUB, so an audience member with no open destination
    gets no card rather than an empty panel. Unreachable today — every tier in `MINISTRY_DESK_ROLES`
    clears both `canRecordSanctionOrders` (Assistant Director and above) and `canRunDesignWorkshops`,
    so the minimum is two rows, and the spec asserts that floor — and written anyway, because the
    alternative the day it stops being unreachable is a panel headed "Your ministry desk" with
    nothing under it, which reads as a feature that failed to load.

    ⚠ AND THE WITHHELD ROWS ARE SILENT ON PURPOSE, WHICH IS NOT THE TRUNCATION RULE BEING IGNORED.
    That rule is about a list that quietly stops: rows that exist, were not fetched or not shown, and
    whose absence reads as "there are none". Nothing is fetched here and nothing is cut — a row is
    absent because that destination is not this account's, which is a fact about the account rather
    than about the data. The master admin is refused `/officers/monitored` BY NAME on the server, so
    telling them "one destination is hidden from you" would be reporting a rule as a loss. The
    walkthrough below is where the rules are explained, and it is one click away.
  */
  if (open.length === 0) return null;

  return (
    <section aria-labelledby="ministry-desk-heading" className="panel mb-6 p-4 sm:p-5">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <h2 id="ministry-desk-heading" className="font-display text-lg font-bold text-ink-900">
          Your ministry desk
        </h2>
        <p className="text-xs text-ink-500">Your screens, in the order a workshop reaches them.</p>
      </div>

      <div className="mt-4 grid gap-2.5 sm:grid-cols-2 lg:grid-cols-3">
        {open.map((destination) => (
          <Link
            key={destination.href}
            href={destination.href}
            className="flex items-start gap-3 rounded-md border border-line-200 bg-card p-3.5 transition-shadow hover:border-purple-300 hover:shadow-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-purple-700"
          >
            <span aria-hidden className="grid h-9 w-9 shrink-0 place-items-center rounded-md bg-purple-50 text-purple-700">
              <destination.icon className="h-[18px] w-[18px]" aria-hidden />
            </span>
            <span className="min-w-0 flex-1">
              <span className="flex items-center gap-1 font-display text-sm font-bold text-ink-900">
                {destination.label}
                <ArrowUpRight className="h-3.5 w-3.5 shrink-0 text-ink-300" aria-hidden />
              </span>
              <span className="mt-0.5 block text-xs leading-5 text-ink-500">{destination.note}</span>
            </span>
          </Link>
        ))}
      </div>

      {/*
        THE WALKTHROUGH IS A FOOTER LINE AND NOT A SIXTH TILE, for two reasons that both matter. It
        is not a ministry screen — it is the page that explains the five above — so a tile would put
        a lesson in a row of destinations. And the grid above is asserted, row for row and in order,
        against the directorate walkthrough's own deck; a sixth tile pointing AT that deck would have
        to be special-cased out of the assertion, which is how an assertion starts drifting from what
        it claims to check.

        IT LINKS TO THE BARE ROUTE RATHER THAN TO AN ANCHOR. `guideTrackForAnchor` resolves a deck
        from `/guide#<id>` and `GuideJourney` opens and scrolls to it, so an anchor would work — and
        `steps.ts` records that not one of the existing links to that page uses one. Deep-linking
        them is a change to make everywhere at once or nowhere, so this one does not quietly become
        the exception.
      */}
      <p className="mt-4 border-t border-line-200 pt-3 text-xs leading-5 text-ink-500">
        Not sure which of these is yours?{" "}
        <Link href="/guide" className="font-medium text-purple-700 underline-offset-2 hover:underline">
          Open the walkthrough
        </Link>{" "}
        — it carries a deck for the ministry posts that says, screen by screen, which post each one is
        for. The three posts do not have the same powers: two of these screens refuse a Regional
        Director even though they outrank an Assistant Director, and one refuses an admin by name.
      </p>
    </section>
  );
}
