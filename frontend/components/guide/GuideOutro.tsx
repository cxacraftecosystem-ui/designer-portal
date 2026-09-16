"use client";

import Link from "next/link";
import { motion } from "framer-motion";
import { ArrowUpRight, CheckCircle2 } from "lucide-react";

import { adminChromeRouteFor, adminChromeVisible } from "@/components/AdminViewProvider";
import { riseItem, slideItem, springy, staggerParent } from "@/components/guide/guideMotion";
import type { GuideTrack } from "@/components/guide/tracks";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import { canAccessRoute } from "@/lib/permissions";
import type { User } from "@/lib/types";

/*
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * THE THREE LISTS THIS BAND DRAWS NOW COME FROM THE TRACK (`components/guide/tracks.ts`).
 *
 * They were literals in this file until `/guide` grew a second and third deck, and every one of them
 * was specific to the designer's job rather than to the page: a recap headed "The whole process",
 * a checklist headed "Before you leave the field", and six links chosen for somebody who records
 * artisans. An inspector does not go to the field. The RULES each list is written under did not move
 * with the strings, so they are here, beside the component that renders them.
 *
 * ── THE CHECKLIST'S ADMISSION TEST ───────────────────────────────────────────────────────────────
 *
 * An item belongs on a checklist if it is CHEAP NOW AND EXPENSIVE OR IMPOSSIBLE LATER, and for no
 * other reason — not because it is important, and not because of which part of the job it belongs
 * to. That is why the designer's list ends with three workshop items among six record ones: a tag
 * cannot be tied to an object you have left in a courtyard, and a sketch that was set aside and
 * never written down is gone with the paper it was on. Both fail the test exactly as the six above
 * them do. The directorate's list is the same test applied to a different day — an account minted
 * against a mistyped address cannot be renamed, a sign-in link nobody sent expires — and the
 * inspector's to a suggestion that cannot be withdrawn once it is filed.
 *
 * The designer's photograph item is the clearest worked example and is worth keeping named, because
 * it passes the test harder than anything else on that list. Since the photograph gate shipped, a
 * file can be turned away on the handset before it uploads — `components/media/photoGate.ts` checks
 * each one "for focus, for resolution, and for being the identical file twice" — and the refusal
 * names its reason and ends "take it again". That is a free instruction while the loom is in front
 * of you and an impossible one from a desk, and NOTHING LATER RAISES IT AGAIN: the gate runs at
 * attach time, and a gallery that declares how many it wants still saves with fewer, so the
 * shortfall surfaces as a completeness figure weeks later rather than as a refusal today.
 *
 * ⚠ AND IT DOES NOT PRINT THE NUMBER, which is a rule and not brevity. The floor is declared per
 * field by the server registry (`min_items`), the gallery's own progress bar reads it and prints it,
 * and a count typed into a checklist would be a second, hand-kept copy of a value this page cannot
 * see — "never print a cap you did not read". "The number it asks for" is true whatever the registry
 * says. The same rule governs every deck: the recap's step numbers are `index + 1` over the array,
 * and no sentence anywhere in this band may carry a count somebody typed.
 *
 * ── THE RECAP'S PARAGRAPH IS THE ONE PLACE A CLAIM ABOUT ANDROID MAY GO, AND IT IS PER DECK ──────
 *
 * It said, of the whole process, "It is the same order on the web and in the Android app, and the
 * screens carry the same names in both." True of the designer's deck. False of the other two — the
 * handset has no annual plan, no sanction register and no oversight screens at all, and its
 * inspection detail screen has no box to file a suggestion from. A sentence like that repeated over
 * a deck it is not true of sends somebody hunting a phone for a screen that was never built, which
 * `WalkthroughSteps.kt` records as worse than a missing step. So `recapLead` is a track field, and
 * each deck's says what is true of its own screens.
 *
 * ── "WHERE TO GO NEXT" MUST BE REACHABLE BY THE READER, AND IS NOW FILTERED TO MAKE THAT TRUE ────
 *
 * Every entry is a plain `<Link>` with no gate on it, so an entry the reader cannot open is a tile
 * that lands them on a lock panel from the one band of the page that exists to say "you are done,
 * here is where to go". A step CARD may name a screen the reader cannot open — that is how the guide
 * teaches a capability somebody has not earned yet, and every such card says so in its own `watch`.
 * An exit tile may not: it makes a promise instead of a description.
 *
 * ⚠ THAT RULE WAS WRITTEN HERE AND ENFORCED NOWHERE UNTIL 2026-09-16. `e2e/guide-tracks-unit.spec.ts`
 * asserted it of the directorate and inspector decks and EXEMPTED the designer's in writing, naming
 * the gap it was leaving: the designer's deck is the fallback for seven tiers, including
 * CROWDSOURCE_VOLUNTEER (10), and its "Review" tile is `/review`, which `canReview` admits only from
 * FIELD_CONTRIBUTOR (20) up. So the bottom tier has been offered a padlock from this band for as
 * long as this band has existed. The spec named the one-line fix — filter `track.next` on
 * `canAccessRoute` — and deliberately did not take it, because narrowing a surface nobody asked to
 * narrow, inside a change about other things, is how a review loses track of what it approved.
 *
 * IT IS TAKEN NOW BECAUSE THE DECK SCOPING TURNED A TOLERABLE GAP INTO A TRAP. Until 2026-09-16 a
 * volunteer who met that padlock could switch to another deck; now the designer's is the only deck
 * they will ever be shown, and its closing band was the last thing they read. Same one line, and the
 * change that made it urgent is the change that carries it.
 *
 * ── WHY THE REMOVAL IS SILENT, WHICH IS NOT THIS REPOSITORY'S USUAL ANSWER ───────────────────────
 *
 * "A list that quietly stops is indistinguishable from a place with no records" governs TRUNCATION —
 * a cap, a page size, a server that stopped early — where the reader is owed the fact that there is
 * more. This is not that. A tile the reader cannot open was never a place they could go, so naming
 * it in order to explain its absence would put a screen in front of somebody for the sole purpose of
 * telling them they may not have it. Where naming a refused screen IS useful, this page already does
 * it properly: on the step card, in prose, with its own `watch` saying who the screen is for.
 *
 * ── AND IT IS TWO GATES, BECAUSE `AppShell` IS TWO GATES ─────────────────────────────────────────
 *
 * The filter shipped on 2026-09-16 reading `canAccessRoute` alone, justified as "the same table
 * `AppShell` enforces above every page, so a tile is drawn exactly when the page behind it would
 * render". The first half was true and the conclusion was not. `AppShell` applies a SECOND gate once
 * the guard has passed — `adminChromeRouteFor(pathname)` plus `adminChromeVisible(user, adminMode)`
 * — and renders `AdminViewLocked` INSTEAD of the page when an admin is browsing with admin view off.
 * `/users` is an `ADMIN_CHROME_ROUTES` entry and is the directorate deck's "Manage users" tile, and
 * `canAccessRoute` admits an admin to it, so an ADMIN or MASTER ADMIN with the toggle off was being
 * offered exactly the padlock this band was filtered to stop offering. The island nav beside it was
 * already hiding that destination from that same reader (`isNavItemVisible` consults `adminMode`;
 * `/users` carries `adminSurface: true`), so the guide was the one surface still promising it.
 *
 * ONLY THE SCOPING MADE IT REACHABLE, which is why it arrived with the filter rather than before it:
 * until ADMIN and MASTER_ADMIN became the only accounts that read all three decks, nobody in
 * particular was ever steered onto the directorate deck's closing band.
 *
 * IT IS A PREFERENCE AND NOT A PERMISSION, so it can only ever subtract, exactly as `AppShell` says:
 * `adminChromeVisible` answers true for everyone who has no toggle at all, and the entitlement test
 * runs first either way. `adminMode` arrives as a prop for the reason `user` does — see the filter
 * below.
 *
 * `/dashboard` is in every deck's `next`, is in no `ROUTE_GUARDS` row and is no admin chrome, so
 * neither gate can empty the band — checked rather than assumed, and asserted in
 * `e2e/guide-tracks-unit.spec.ts` (under both settings of the toggle) so that a deck whose exits are
 * all gated cannot ship a heading over nothing.
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 */

/**
 * The closing section: a compact recap of the steps as a single readable line, the checklist of
 * things that cannot be fixed later, and the screens this deck's reader goes to once they are done.
 *
 * Everything here reveals on scroll with the same staggered vocabulary as the step cards, so
 * the end of the page feels like the same document rather than a footer bolted on.
 */
export function GuideOutro({
  track,
  user,
  adminMode
}: {
  track: GuideTrack;
  user: User | null | undefined;
  /** The admin-view toggle, read once by the page. See the second-gate section in the header. */
  adminMode: boolean;
}) {
  const reduce = useAppReducedMotion();
  const steps = track.steps;

  /**
   * The exits this reader can actually open — BOTH of `AppShell`'s gates, in `AppShell`'s own order.
   * `canAccessRoute` is the entitlement table it enforces above every page; the second clause is the
   * admin-view gate it applies afterwards, which renders `AdminViewLocked` instead of the page. A
   * tile is drawn exactly when the page behind it would render, which is what this comment claimed
   * while it asked only the first question (see the header).
   *
   * `adminChromeRouteFor(href) === null` is the ordinary case and short-circuits the rest, so nothing
   * but the handful of admin-chrome destinations pays for the second clause at all.
   *
   * BOTH THE USER AND THE TOGGLE ARRIVE AS PROPS RATHER THAN FROM `useAuth()` / `useAdminView()`,
   * matching every other component in this folder: the page owns the account and hands each band what
   * it needs. It is also what keeps this component renderable from a test or a future preview with
   * neither provider above it — `useAdminView` THROWS without its provider, so reading it here would
   * have spent that property to save the page one line.
   *
   * THE ONE FRAME BEFORE THE PREFERENCE IS READ shows a master admin one tile fewer, because
   * `AdminViewProvider` reports `adminMode: false` until it has read `localStorage` for this account.
   * That is the safe direction and deliberately not held for: `AppShell` holds the whole frame there
   * because the alternative is flashing a padlock at somebody who may pass, while the worst case here
   * is an exit tile that appears a commit later in a band the reader has not scrolled to yet.
   *
   * Not memoised, and not counted in this comment either: `next` is a handful of entries and
   * `canAccessRoute` is a linear scan of `ROUTE_GUARDS`. Both are small, neither is on a hot path,
   * and a number written here would be a hand-kept copy of a table that grows every release.
   */
  const exits = track.next.filter(
    (entry) =>
      canAccessRoute(user, entry.href) &&
      (!adminChromeRouteFor(entry.href) || adminChromeVisible(user, adminMode))
  );

  return (
    <div className="mt-12 grid gap-6">
      <motion.section
        variants={staggerParent(reduce)}
        initial="hidden"
        whileInView="show"
        viewport={{ once: true, amount: 0.3 }}
        className="panel p-5"
      >
        <motion.h2 variants={riseItem(reduce)} className="font-display text-lg font-bold text-ink-900">
          {track.recapTitle}
        </motion.h2>
        <motion.p variants={riseItem(reduce)} className="mt-1.5 text-sm leading-6 text-ink-700">
          {track.recapLead}
        </motion.p>
        <motion.ol variants={staggerParent(reduce, 0.035)} className="mt-4 flex flex-wrap items-center gap-1.5">
          {steps.map((step, index) => (
            <motion.li key={step.id} variants={slideItem(reduce, 10)} className="flex items-center gap-1.5">
              <span className="inline-flex items-center gap-1.5 rounded-full border border-line-200 bg-card px-3 py-1 text-xs font-medium text-ink-900">
                <span aria-hidden className="font-display text-purple-700">
                  {index + 1}
                </span>
                {step.label}
              </span>
              {index < steps.length - 1 ? (
                <span aria-hidden className="text-ink-300">
                  →
                </span>
              ) : null}
            </motion.li>
          ))}
        </motion.ol>
      </motion.section>

      <motion.section
        variants={staggerParent(reduce)}
        initial="hidden"
        whileInView="show"
        viewport={{ once: true, amount: 0.25 }}
        className="panel p-5"
      >
        <motion.h2 variants={riseItem(reduce)} className="font-display text-lg font-bold text-ink-900">
          {track.checklistTitle}
        </motion.h2>
        <motion.p variants={riseItem(reduce)} className="mt-1.5 text-sm leading-6 text-ink-700">
          {track.checklistLead}
        </motion.p>
        <motion.ul variants={staggerParent(reduce, 0.045)} className="mt-4 grid gap-2 sm:grid-cols-2">
          {track.checklist.map((item) => (
            <motion.li
              key={item}
              variants={riseItem(reduce, 10)}
              className="flex items-start gap-2.5 rounded-md border border-line-200 bg-surface-50 px-3 py-2.5 text-sm leading-6 text-ink-700"
            >
              <CheckCircle2 className="mt-1 h-4 w-4 shrink-0 text-purple-700" aria-hidden />
              <span>{item}</span>
            </motion.li>
          ))}
        </motion.ul>
      </motion.section>

      <motion.section
        variants={staggerParent(reduce)}
        initial="hidden"
        whileInView="show"
        viewport={{ once: true, amount: 0.2 }}
        className="panel p-5"
      >
        <motion.h2 variants={riseItem(reduce)} className="font-display text-lg font-bold text-ink-900">
          Where to go next
        </motion.h2>
        <motion.div variants={staggerParent(reduce, 0.04)} className="mt-4 grid gap-2.5 sm:grid-cols-2 lg:grid-cols-3">
          {exits.map((entry) => (
            <motion.div key={entry.href} variants={riseItem(reduce, 10)}>
              <motion.div
                whileHover={reduce ? undefined : { y: -3 }}
                whileTap={reduce ? undefined : { scale: 0.99 }}
                transition={springy(reduce)}
                className="h-full rounded-md border border-line-200 bg-card transition-shadow hover:shadow-md"
              >
                <Link href={entry.href} className="flex h-full items-start gap-3 p-3.5">
                  <span aria-hidden className="grid h-9 w-9 shrink-0 place-items-center rounded-md bg-purple-50 text-purple-700">
                    <entry.icon className="h-[18px] w-[18px]" aria-hidden />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="flex items-center gap-1 font-display text-sm font-bold text-ink-900">
                      {entry.label}
                      <ArrowUpRight className="h-3.5 w-3.5 text-ink-300" aria-hidden />
                    </span>
                    <span className="mt-0.5 block text-xs leading-5 text-ink-500">{entry.note}</span>
                  </span>
                </Link>
              </motion.div>
            </motion.div>
          ))}
        </motion.div>
      </motion.section>
    </div>
  );
}
