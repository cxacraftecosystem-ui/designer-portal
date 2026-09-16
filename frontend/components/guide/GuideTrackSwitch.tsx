"use client";

import { motion } from "framer-motion";
import { Check } from "lucide-react";

import { riseItem, springy, staggerParent } from "@/components/guide/guideMotion";
import type { GuideTrack } from "@/components/guide/tracks";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";

/**
 * WHICH WALKTHROUGH — the control that moves between the decks an account may read.
 *
 * ── WHO SEES IT, AND WHY THAT IS NOT EVERYBODY ANY MORE — 2026-09-16 ────────────────────────────
 *
 * The page renders this only when `guideTracksFor(user)` returns more than one deck, which today
 * means an ADMIN or a MASTER ADMIN and nobody else. Every other tier is scoped to the single deck
 * its role owns and never meets this panel. The owner ruled on 2026-09-16 that "except for admins
 * and master admins, the walkthrough that the people get to see should be the ones that are relevant
 * to their roles"; `components/guide/tracks.ts` carries the ruling, the tier-by-tier map and the
 * record of which tiers have no deck written for them.
 *
 * ⚠ WHAT THIS HEADER ARGUED UNTIL THEN, KEPT BECAUSE THE REVERSAL IS A PRODUCT DECISION RATHER THAN
 * A CORRECTION. It read: "Because `/guide` is deliberately ungated … the walkthrough teaches the
 * process to people who have not earned the capability yet. That argument does not stop applying
 * once there are three decks — it applies three times over. A designer whose workshop is about to be
 * read back by an Inspector / Reviewer has every reason to read what that person is looking at; a
 * researcher hoping to be empanelled has every reason to read the designer's deck; an Assistant
 * Director has every reason to know what the designer they just named is being asked to do. Choosing
 * a deck FOR somebody and then hiding the other two would convert an ungated teaching surface into a
 * per-role one, which is a narrowing, and this repository does not narrow a teaching surface to match
 * a capability." That reasoning was not found to be wrong; it was overruled. It is left here because
 * the day somebody proposes putting the other decks back, this is the argument they are making, and
 * it deserves to be read in its own words rather than reconstructed.
 *
 * The half of it that still binds, and is not the switcher's to give away: `/guide` stays out of
 * `ROUTE_GUARDS` and its nav entry stays `can: everyone`. The scoping is about WHICH DECK, never
 * about whether the page opens.
 *
 * ── THIS COMPONENT IS NOT THE GATE AND MUST NOT BECOME ONE ──────────────────────────────────────
 *
 * It renders whatever `tracks` it is handed and knows nothing about the reader. The page decides,
 * once, and also clamps the ACTIVE deck to the same list — so a second gate in here would be a
 * second copy of a permission rule, which is the failure this repository has paid for repeatedly.
 * Hand it the whole of `GUIDE_TRACKS` again and the buttons come back; the page is the one place
 * that stops that.
 *
 * ── IT IS NOT IN THE RAIL, AND THAT IS ARITHMETIC RATHER THAN TASTE ─────────────────────────────
 *
 * `GuideRail`'s step list is `max-h-[calc(100vh-19rem)]` and its own comment states the rule in
 * capitals: adding a step is free, adding a ROW OF CHROME above the list is not, and owes the `19rem`
 * a recount — because the panel is `sticky top-28` and never leaves the viewport, so every pixel of
 * chrome comes straight off the height the list has to scroll in. On a 1366×768 laptop that list has
 * already swallowed five rows once. Three buttons and their captions are roughly 7rem of chrome; put
 * here, above the journey, they cost the rail nothing and the recount is not owed.
 *
 * ── THE STATE IS NOT COLOUR ALONE ───────────────────────────────────────────────────────────────
 *
 * The selected deck carries `aria-pressed`, a purple border, a tinted ground AND a tick. Non-
 * negotiable 5 of the frontend contract is about motion, and the same rule governs any signal
 * carried by one channel: a selection a colour-blind reader, a greyscale printout or forced-colours
 * mode cannot see is a selection they do not get. The tick is what survives all three.
 *
 * ── AND IT IS A SET OF TOGGLES, NOT A TABLIST ───────────────────────────────────────────────────
 *
 * `role="tablist"` would be closer to what this looks like and would be a promise this does not
 * keep: tabs owe arrow-key roving focus and `aria-controls` pointing at a `tabpanel`, and the thing
 * they would control is most of the page rather than one panel. Plain buttons with `aria-pressed`
 * describe exactly what is here — three controls, one of them currently on — and tab order is DOM
 * order with no keyboard contract to get wrong. `AnchoredPopover`'s note about not trapping focus is
 * the same instinct: a control that claims an interaction model it does not implement is worse than
 * a plainer one that does.
 */
export function GuideTrackSwitch({
  tracks,
  active,
  onChoose
}: {
  tracks: readonly GuideTrack[];
  active: GuideTrack;
  onChoose: (track: GuideTrack) => void;
}) {
  const reduce = useAppReducedMotion();

  return (
    <motion.section
      variants={staggerParent(reduce, 0.05)}
      initial="hidden"
      animate="show"
      aria-labelledby="guide-track-switch-heading"
      className="panel mt-6 p-4 sm:p-5"
    >
      <motion.h2
        id="guide-track-switch-heading"
        variants={riseItem(reduce, 8)}
        className="font-display text-sm font-bold text-ink-900"
      >
        Which walkthrough
      </motion.h2>
      <motion.p variants={riseItem(reduce, 8)} className="mt-1 text-xs leading-5 text-ink-500">
        {/*
          The sentence says the mechanism out loud, because a page that silently re-shapes itself per
          account is a page two colleagues cannot compare notes about — and it now has MORE to say
          out loud, not less: the reader of this panel is holding a view of the product that their
          colleagues do not have, and nothing else on the page would tell them so.

          ⚠ IT SAID THE OPPOSITE UNTIL 2026-09-16 — "This one opened on the deck that matches your
          access. All three are readable by anybody — the screens each teaches are not." — and that
          second sentence became false for nine of the eleven tiers on the day the decks were scoped.
          It is the only place on screen where the old rule was written down, which is exactly why a
          product reversal has to reach the copy and not only the code.

          AND IT NAMES THE EXCEPTION RATHER THAN LEAVING IT AT "YOUR ACCESS". A reader of this panel
          is an admin; "you see all three because of your access" tells them nothing they can act on,
          while "an admin account is not scoped to one" tells them what every colleague is looking at
          instead. It states no count either — `tracks.length` is what the buttons below already say,
          and a number typed here is the "Ten steps" defect in a smaller font.
        */}
        This one opened on the deck that matches your access, and you can read the others because an
        admin account is not scoped to one. Every other tier sees only the walkthrough that matches
        its own role.
      </motion.p>

      {/*
        ⚠ `sm:grid-cols-3` IS HARDCODED AND THE PAGE ONLY EVER HANDS THIS PANEL 1 DECK OR 3. At one
        it is not rendered at all (the page's own condition), and at three the row is full; the two
        cases the literal serves are the only two that occur. A future audience granted exactly two
        decks would get a three-column row with a hole in it — noted rather than built for, because
        `grid-cols-${n}` cannot be a concatenated Tailwind class (the content globs never see it) and
        a length-to-class map written today for an audience that does not exist is a second rule to
        keep in step with `guideTracksFor`. Whoever grants that audience changes this line.

        AND THE KEY HERE IS CORRECT AS IT STANDS. It is inside a `.map()` over one list, where a key
        is unique among the siblings it is a key for. The duplicate-key defect fixed on 2026-09-16
        was two `key={track.id}` SIBLINGS in the page's fragment, not this — see the comment beside
        `<GuideHero>` in `app/(protected)/guide/page.tsx` before "tidying" anything here.
      */}
      <motion.div variants={staggerParent(reduce, 0.04)} className="mt-4 grid gap-2.5 sm:grid-cols-3">
        {tracks.map((track) => {
          const selected = track.id === active.id;
          return (
            <motion.div key={track.id} variants={riseItem(reduce, 10)}>
              <motion.button
                type="button"
                aria-pressed={selected}
                onClick={() => onChoose(track)}
                whileHover={reduce ? undefined : { y: -2 }}
                whileTap={reduce ? undefined : { scale: 0.99 }}
                transition={springy(reduce)}
                className={
                  selected
                    ? "flex h-full w-full flex-col items-start gap-1 rounded-md border border-purple-600 bg-purple-50 px-3.5 py-3 text-left transition-shadow hover:shadow-md"
                    : "flex h-full w-full flex-col items-start gap-1 rounded-md border border-line-200 bg-card px-3.5 py-3 text-left transition-shadow hover:border-purple-300 hover:shadow-md"
                }
              >
                <span className="flex w-full items-center gap-1.5">
                  <span
                    className={
                      selected
                        ? "font-display text-sm font-bold text-purple-700"
                        : "font-display text-sm font-bold text-ink-900"
                    }
                  >
                    {track.name}
                  </span>
                  {/* The half of the selected state that is not a colour. `aria-hidden` because
                      `aria-pressed` already carries it for assistive technology, and a screen reader
                      announcing both would say "pressed" twice. */}
                  {selected ? <Check className="ml-auto h-4 w-4 shrink-0 text-purple-700" aria-hidden /> : null}
                </span>
                <span className="text-xs leading-5 text-ink-500">{track.audience}</span>
                <span className="text-xs text-ink-300">
                  {/* Derived, never typed. Every count a reader sees on this page comes off the
                      array — the header said "Ten steps" over sixteen for exactly this reason. */}
                  {track.steps.length} steps
                </span>
              </motion.button>
            </motion.div>
          );
        })}
      </motion.div>
    </motion.section>
  );
}
