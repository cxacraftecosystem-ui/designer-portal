"use client";

import { motion, useMotionValue, useSpring, useTransform } from "framer-motion";
import { ArrowDown } from "lucide-react";

import { riseItem, springy, staggerParent } from "@/components/guide/guideMotion";
import type { GuideTrack } from "@/components/guide/tracks";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import { useGsapHeadline } from "@/components/guide/useGsapHeadline";

/**
 * The walkthrough's opening band: a dark purple surface (`surface-dark`) carrying the promise
 * of the page, three orienting facts, and the control that drops the reader into step 1.
 *
 * The one piece of ambient motion here is a derived one — a purple wash whose centre is
 * driven by two motion values tracking the pointer, spring-smoothed and composed into a
 * `radial-gradient` string by `useTransform`. Nothing animates on its own: with no pointer
 * (touch, keyboard, or reduced motion from either the OS or the app's Settings toggle) the
 * values never leave their centred rest position, so the band renders as a plain static
 * gradient.
 */
export function GuideHero({ track, onStart }: { track: GuideTrack; onStart: () => void }) {
  const reduce = useAppReducedMotion();
  const headline = useGsapHeadline<HTMLHeadingElement>(reduce);

  // Pointer position as a percentage of the band, defaulting to dead centre.
  const pointerX = useMotionValue(50);
  const pointerY = useMotionValue(50);
  const smoothX = useSpring(pointerX, { stiffness: 110, damping: 24, mass: 0.6 });
  const smoothY = useSpring(pointerY, { stiffness: 110, damping: 24, mass: 0.6 });

  // Derived animation: two motion values → one CSS background string, recomputed off the
  // main React render loop.
  const glow = useTransform([smoothX, smoothY], (latest: number[]) => {
    const [x, y] = latest;
    return `radial-gradient(30rem 26rem at ${x}% ${y}%, oklch(0.648 0.19 305 / 0.42), transparent 64%)`;
  });

  /*
   * ── THE COPY IN THIS BAND BELONGS TO THE TRACK, NOT TO THIS COMPONENT ─────────────────────────
   *
   * The headline, the paragraph and the three facts were literals here until `/guide` grew a second
   * and third deck, and most of them were FALSE of the other two audiences rather than merely
   * ill-fitting: "the repository records first, then the 22-stage design & prototype workshop they
   * feed" describes one job out of three. They are fields on `GuideTrack` now
   * (`components/guide/tracks.ts`), and this component renders whichever deck it is handed.
   *
   * ── THE RULES THEY ARE STILL WRITTEN UNDER, WHICH DID NOT MOVE WITH THEM ──────────────────────
   *
   * THREE FACTS AND NOT FOUR, because four facts is a list and three is an orientation.
   *
   * ⚠ EVERY FACT MUST ADDRESS THE READER WHO IS ACTUALLY IN THE ROOM, and getting that wrong here is
   * invisible. The first fact opened "The walkthrough is reachable before anybody has an account",
   * which is not true of this page: `/guide` lives under `app/(protected)`, `AppShell` does
   * `router.replace("/login")` and then `if (!user) return null`, so a signed-out reader is sent to
   * sign in and never reads a word of this band — and nor can the reader that fact was WRITTEN for,
   * since an address nobody has admitted gets a pending request rather than an account. The band was
   * addressing an audience that cannot see it, which is the quietest way for help text to be wrong:
   * nothing on screen contradicts it and the people who would have noticed are not here.
   * `components/hero/WalkthroughCallout.tsx` carries the honest version for the public side. The
   * fact survived the correction by being re-aimed — how a COLLEAGUE gets in is a live question for
   * somebody who is already signed in — and every fact added to any deck owes the same test.
   *
   * ⚠ AND NO FACT MAY CARRY A HAND-TYPED COUNT. Where a deck states how many steps it has, the
   * number is `steps.length` interpolated in `tracks.ts` and never a literal — the page header said
   * "Ten steps" over an array of sixteen, and a fact in a tinted box is no more durable than a page
   * header. Numbering a step in prose is the same defect: a sentence here once named a card by its
   * position, and the array has grown three times since.
   */
  const facts = track.facts;

  return (
    <motion.section
      variants={staggerParent(reduce, 0.08)}
      initial="hidden"
      animate="show"
      onPointerMove={
        reduce
          ? undefined
          : (event) => {
              const box = event.currentTarget.getBoundingClientRect();
              pointerX.set(((event.clientX - box.left) / box.width) * 100);
              pointerY.set(((event.clientY - box.top) / box.height) * 100);
            }
      }
      onPointerLeave={
        reduce
          ? undefined
          : () => {
              pointerX.set(50);
              pointerY.set(50);
            }
      }
      className="surface-dark relative isolate overflow-hidden px-6 py-10 sm:px-10 sm:py-12"
    >
      <motion.div aria-hidden className="pointer-events-none absolute inset-0 -z-10" style={{ backgroundImage: glow }} />

      <motion.p variants={riseItem(reduce)} className="text-[0.8125rem] font-semibold uppercase tracking-[0.14em] text-purple-300">
        Walkthrough
      </motion.p>
      {/* The one GSAP-owned element on the page: an overlapping per-word timeline, which framer's
          sequential stagger cannot express. See useGsapHeadline for why. `overflow-hidden` gives
          the words something to rise out of.

          ⚠ THE SPLIT IS DONE ONCE PER MOUNTED NODE (`useGsapHeadline` guards on `dataset.split`,
          because re-splitting would nest spans inside spans), so changing `track.headline` on a
          LIVE node would leave the previous deck's words in the DOM as spans and the new sentence
          unrendered. The page therefore keys this component `hero-${track.id}`, which remounts it
          and gives the hook a fresh <h2> to split. If that key is ever removed, this headline stops
          changing with the deck and nothing will say so.

          ⚠ AND THE PREFIX IS PART OF THE KEY, not decoration. `key={track.id}` was enough to remount
          this band and was NOT enough to unmount the previous one: the page's fragment carried the
          identical key on `<GuideJourney>` three elements down, React's sibling reconciliation is a
          Map keyed by key, the later child evicted this one, and the old band was never deleted — so
          the purple bands stacked, one per deck visited. Fixed 2026-09-16; the reasoning is written
          out beside the two elements in `app/(protected)/guide/page.tsx`. */}
      <h2
        ref={headline}
        className="mt-3 max-w-2xl overflow-hidden font-display text-3xl font-bold tracking-tight text-white sm:text-4xl"
      >
        {track.headline}
      </h2>
      <motion.p variants={riseItem(reduce)} className="mt-4 max-w-2xl text-sm leading-relaxed text-purple-100">
        {track.intro}
      </motion.p>

      <motion.ul variants={staggerParent(reduce)} className="mt-7 grid gap-2.5 sm:grid-cols-3">
        {facts.map((fact) => (
          <motion.li
            key={fact.text}
            variants={riseItem(reduce, 10)}
            className="flex items-start gap-2.5 rounded-md border border-white/10 bg-white/5 px-3 py-2.5"
          >
            <fact.icon className="mt-0.5 h-4 w-4 shrink-0 text-purple-300" aria-hidden />
            <span className="text-xs leading-5 text-purple-100">{fact.text}</span>
          </motion.li>
        ))}
      </motion.ul>

      <motion.div variants={riseItem(reduce)} className="mt-8">
        <motion.button
          type="button"
          onClick={onStart}
          whileHover={reduce ? undefined : { y: -2 }}
          whileTap={reduce ? undefined : { scale: 0.97 }}
          transition={springy(reduce)}
          className="inline-flex min-h-10 items-center gap-2 rounded-md bg-purple-700 px-5 py-2.5 text-sm font-medium text-white shadow-cta transition-colors hover:bg-purple-800"
        >
          Start at step 1
          <ArrowDown className="h-4 w-4" aria-hidden />
        </motion.button>
      </motion.div>
    </motion.section>
  );
}
