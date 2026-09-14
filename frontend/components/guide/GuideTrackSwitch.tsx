"use client";

import { motion } from "framer-motion";
import { Check } from "lucide-react";

import { riseItem, springy, staggerParent } from "@/components/guide/guideMotion";
import type { GuideTrack } from "@/components/guide/tracks";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";

/**
 * WHICH WALKTHROUGH — the control that makes the role-chosen deck a default rather than a gate.
 *
 * ── WHY IT EXISTS AT ALL, GIVEN THE ROLE ALREADY ANSWERED ───────────────────────────────────────
 *
 * Because `/guide` is deliberately ungated, and the reason is written out at length in
 * `steps.ts`: the walkthrough teaches the process to people who have not earned the capability yet.
 * That argument does not stop applying once there are three decks — it applies three times over. A
 * designer whose workshop is about to be read back by an Inspector / Reviewer has every reason to
 * read what that person is looking at; a researcher hoping to be empanelled has every reason to
 * read the designer's deck; an Assistant Director has every reason to know what the designer they
 * just named is being asked to do. Choosing a deck FOR somebody and then hiding the other two would
 * convert an ungated teaching surface into a per-role one, which is a narrowing, and this repository
 * does not narrow a teaching surface to match a capability.
 *
 * So: the role decides which deck OPENS (`guideTrackFor`), and this control reaches all three,
 * always, for everybody.
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
          account is a page two colleagues cannot compare notes about. "Opened on" rather than "is
          for": what the role decided is which one you are looking at, not which one you may read.
        */}
        This one opened on the deck that matches your access. All three are readable by anybody — the
        screens each teaches are not.
      </motion.p>

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
