"use client";

import Link from "next/link";
import { ArrowUpRight } from "lucide-react";

import {
  AWAITING_SANCTION_BADGE_CLASS,
  AWAITING_SANCTION_BADGE_HREF,
  MINISTRY_APPROVAL_GAP,
  awaitingSanctionSentence,
  ministryDeskFor
} from "@/components/dashboard/ministryDesk";
import { useAuth } from "@/components/AuthProvider";
import { useAwaitingSanctionCount } from "@/components/hooks/useAwaitingSanctionCount";
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
 *
 * ── IT WEARS THE MINISTRY ACCENT AND CARRIES ITS OWN `data-surface` ─────────────────────────────
 *
 * `AppShell` stamps `data-surface="ministry"` on <main> for the four routes whose `ROUTE_GUARDS` row
 * carries `ministry: true`. This card is on /dashboard, which is not one of them and must never
 * become one — a designer's dashboard is the same screen — so the `<section>` below carries the
 * attribute itself. That works because every rule in the scoped block at the end of `app/globals.css`
 * is written twice, as a descendant AND as a self-match: `.panel[data-surface="ministry"]` is what
 * gives this card its left rule, and a descendant-only rule would have matched nothing here and
 * failed with no error anywhere.
 *
 * ── SURFACE ACCENT ONLY, AND THE GROUND STAYS NEUTRAL ───────────────────────────────────────────
 *
 * The orange is on the tiles' icon chips, the tiles' hover border and the walkthrough link, and on
 * nothing else. The card's own ground stays `.panel`, for a reason peculiar to the screen it lives
 * on: `dashboard/page.tsx` renders it immediately above two purple blur orbs whose stated job is to
 * be what the glass tiles refract, and an orange card sitting on purple orbs is a visible clash.
 * (Nor may it become a `GlassSurface` — `DashboardCard` already is one and glass must never be
 * nested.) The wider rule is the repo's non-negotiable: purple-700 is the only action colour, and
 * ministry orange is grounds, borders and chips. There is no button on this card, so nothing here
 * had to be argued about — with one exception, at the focus ring, which is argued where it sits.
 *
 * ── AND ONE ROW WEARS A COUNT ───────────────────────────────────────────────────────────────────
 *
 * "Sanction orders" carries the number of orders whose designer has not filled stage 1 in yet, from
 * `useAwaitingSanctionCount` — the same store the nav's badge reads, so the two cannot disagree.
 * Everything pure about that pill (the destination, the sentence, the class string, and why it is
 * amber rather than orange) is in `ministryDesk.ts` beside the table.
 */
export function MinistryDeskCard() {
  const { user } = useAuth();

  /*
    BOTH GATES ARE COMPUTED BEFORE THE HOOK BELOW AND ACTED ON AFTER IT, which is not a style choice.
    This component used to `return null` above `ministryDeskFor`, and a hook added under that return
    is a CONDITIONAL hook call: React counts them per render, so the first dashboard load by somebody
    outside the audience would render one hook and the next render two, which is the "rendered more
    hooks than during the previous render" crash rather than a lint nit. The two early returns below
    are unchanged in meaning and each still carries its own argument.

    Nothing is spent by an account that is not in the audience: `open` is empty, so `enabled` is
    false, and the hook neither subscribes nor fetches.
  */
  const audience = canSeeMinistryDesk(user);
  const open = audience ? ministryDeskFor(user) : [];

  /*
    HOW MANY SANCTIONED WORKSHOPS ARE STALLED ON THEIR DESIGNER — drawn on the row it belongs to.

    `enabled` is "the badged ROW is actually on screen", the same expression the nav uses for the
    same count. It folds in `canRecordSanctionOrders` — the mirror of `require_sanction_recorder`,
    which is what guards the endpoint — without restating it, so an account that could not read the
    count never asks for it and is never 403'd, and the request can never outlive the row.
  */
  const awaitingCount =
    useAwaitingSanctionCount(open.some((destination) => destination.href === AWAITING_SANCTION_BADGE_HREF)) ?? 0;

  if (!audience) return null;

  /*
    A HUB WITH NOTHING IN IT IS WORSE THAN NO HUB, so an audience member with no open destination
    gets no card rather than an empty panel. Unreachable today — every tier in `MINISTRY_DESK_ROLES`
    clears both `canRecordSanctionOrders` (Assistant Director and above) and `canRunDesignWorkshops`,
    so the minimum is two rows, and the spec asserts that floor — and written anyway, because the
    alternative the day it stops being unreachable is a panel headed "Your ministry desk" with
    nothing under it, which reads as a feature that failed to load.

    ⚠ AND THE WITHHELD ROWS ARE SILENT ON PURPOSE, WHICH IS NOT THE TRUNCATION RULE BEING IGNORED.
    That rule is about a list that quietly stops: rows that exist, were not fetched or not shown, and
    whose absence reads as "there are none". The ROWS are fetched from nowhere and nothing is cut —
    a row is absent because that destination is not this account's, which is a fact about the account
    rather than about the data. (The awaiting count above is a request, and it is the one thing on
    this card that is: it is a number ABOUT one row, not a decision about which rows there are, and
    when it fails it leaves the pill absent rather than the row.) The master admin is refused
    `/officers/monitored` BY NAME on the server, so telling them "one destination is hidden from you"
    would be reporting a rule as a loss. The walkthrough below is where the rules are explained, and
    it is one click away.

    WHAT IS NOT SILENT IS A STEP THAT DOES NOT EXIST. `MINISTRY_APPROVAL_GAP` is rendered under the
    grid because these rows are drawn "in the order a workshop reaches them", and an ordered sequence
    reads as a complete one. The argument, the verification and the instruction to delete it are all
    on that constant.
  */
  if (open.length === 0) return null;

  return (
    <section
      aria-labelledby="ministry-desk-heading"
      className="panel mb-6 p-4 sm:p-5"
      /* The card opts itself into the ministry surface — see the header. The attribute sits on the
         `.panel` element itself rather than on a wrapper, which is exactly the case the scoped
         block's self-matching selector (`.panel[data-surface="ministry"]`) was written twice for. */
      data-surface="ministry"
    >
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
            /*
              THE HOVER BORDER IS MINISTRY AND THE FOCUS RING STAYS PURPLE, and the pair is not an
              oversight. This tile is a <Link>, i.e. an <a>, and `globals.css` already draws every
              focused anchor a purple `outline` at `outline-offset: 2px` that no scope re-points; an
              orange `ring` would be drawn immediately inside that purple outline, so the tile would
              wear two accent colours at once on one keyboard focus. Focus is also app-wide chrome
              rather than ministry chrome — it is the same mark on every screen in the product, and
              the whole of the ministry accent is a SURFACE accent.
            */
            className="flex items-start gap-3 rounded-md border border-line-200 bg-card p-3.5 transition-shadow hover:border-ministry-300 hover:shadow-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-purple-700"
          >
            {/*
              THE `dark:` PAIR IS NOT OPTIONAL. The ministry ramp is literal and does not invert, so
              `bg-ministry-50` is a near-white peach in BOTH themes and would paint a bright patch on
              a dark card; `text-ministry-700` on `bg-card` in dark is 2.44:1. The dark half is the
              `CarryContextBanner` / `SearchableSelect` pair with the hue moved — a `ministry-950/40`
              wash with `ministry-300` ink on it, 9.87:1.

              9.87 AND NOT 10.06, WHICH IS THE RAMP'S HOUSE FIGURE MEASURED SOMEWHERE ELSE. 10.06:1
              — quoted on the `.eyebrow` rule in `globals.css` and in `ministry-surface-unit.spec.ts`
              — is `ministry-300` on the BARE dark card, `--card` #1a1725. This chip is not bare: the
              40% `ministry-950` wash composites to #281616, a shade lighter than the card, and a
              lighter ground under the same ink is a slightly smaller ratio. Nothing changes — both
              clear AA several times over — but the figure is written as measured rather than copied
              from the neighbouring site, because every other ratio in this feature was measured and
              one number that does not re-derive is what stops a reader trusting the rest.
            */}
            <span
              aria-hidden
              className="grid h-9 w-9 shrink-0 place-items-center rounded-md bg-ministry-50 text-ministry-700 dark:bg-ministry-950/40 dark:text-ministry-300"
            >
              <destination.icon className="h-[18px] w-[18px]" aria-hidden />
            </span>
            <span className="min-w-0 flex-1">
              <span className="flex items-center gap-1 font-display text-sm font-bold text-ink-900">
                {destination.label}
                <ArrowUpRight className="h-3.5 w-3.5 shrink-0 text-ink-300" aria-hidden />
                {/*
                  The count rides on the row it is about, and only on that row. `ml-auto` puts it at
                  the far edge of the label line rather than beside the arrow, so a two-digit number
                  never pushes the arrow off the title. It draws nothing at zero and nothing while
                  the count is unknown, so a tile with no badge means "none waiting, or not asked
                  yet" — never "the badge failed".
                */}
                {destination.href === AWAITING_SANCTION_BADGE_HREF && awaitingCount > 0 ? (
                  <span
                    title={awaitingSanctionSentence(awaitingCount)}
                    className={`ml-auto ${AWAITING_SANCTION_BADGE_CLASS}`}
                  >
                    <span aria-hidden>{awaitingCount}</span>
                    <span aria-hidden className="font-medium">
                      awaiting
                    </span>
                    <span className="sr-only">{awaitingSanctionSentence(awaitingCount)}</span>
                  </span>
                ) : null}
              </span>
              <span className="mt-0.5 block text-xs leading-5 text-ink-500">{destination.note}</span>
            </span>
          </Link>
        ))}
      </div>

      {/* The step this journey does not have yet. See `MINISTRY_APPROVAL_GAP` for why it is on
          screen, why it names no tier and no release, and the test that will demand its removal. */}
      <p className="mt-3 text-xs leading-5 text-ink-500">{MINISTRY_APPROVAL_GAP}</p>

      {/*
        THE WALKTHROUGH IS A FOOTER LINE AND NOT A SIXTH TILE, for two reasons that both matter. It
        is not a ministry screen — it is the page that explains the five above — so a tile would put
        a lesson in a row of destinations. And the grid above is asserted, row for row and in order,
        against the directorate walkthrough's own deck; a sixth tile pointing AT that deck would have
        to be special-cased out of the assertion, which is how an assertion starts drifting from what
        it claims to check.

        IT LINKS TO THE BARE ROUTE RATHER THAN TO AN ANCHOR, AND THE REASON GOT STRONGER ON
        2026-09-16. It used to be only a consistency argument: `guideTrackForAnchor` resolves a deck
        from `/guide#<id>` and `GuideJourney` opens and scrolls to it, so an anchor WOULD have
        worked, and `steps.ts` recorded that not one of the existing links to that page used one —
        deep-linking them being a change to make everywhere at once or nowhere. Since the walkthrough
        became role-scoped, an anchor is no longer merely inconsistent: the role now wins and an
        anchor naming a deck the reader may not see is discarded in silence (OQ-5, arm b, argued on
        `guideTrackForAnchor` itself). It would still resolve for the three ministry posts, whose
        deck this is — and it would go nowhere, with no explanation, for the master admin who reads
        this same card and opens on the designer's deck. A link that works for three of its four
        readers is the worse kind of broken, because nobody who can see it fail is the person who
        wrote it.
      */}
      <p className="mt-4 border-t border-line-200 pt-3 text-xs leading-5 text-ink-500">
        Not sure which of these is yours?{" "}
        <Link
          href="/guide"
          className="font-medium text-ministry-700 underline-offset-2 hover:underline dark:text-ministry-300"
        >
          Open the walkthrough
        </Link>{" "}
        — it carries a deck for the ministry posts that says, screen by screen, which post each one is
        for, and for the three posts it is the deck that opens. The three do not have the same powers:
        two of these screens refuse a Regional Director even though they outrank an Assistant
        Director, and one refuses an admin by name.
      </p>
    </section>
  );
}
