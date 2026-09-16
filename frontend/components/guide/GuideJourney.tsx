"use client";

import { useEffect, useRef, useState } from "react";
import { motion, useScroll, useSpring, useTransform } from "framer-motion";

import { GuideRail } from "@/components/guide/GuideRail";
import { GuideStepCard } from "@/components/guide/GuideStepCard";
import { scrollToStep } from "@/components/guide/guideMotion";
import type { GuideStep } from "@/components/guide/steps";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";

/**
 * The journey: every step of the deck it is handed, threaded onto a scroll-linked spine, with the
 * sticky rail alongside on large screens.
 *
 * ONE DECK AT A TIME, CHOSEN ABOVE IT. This said "every step in GUIDE_STEPS" while that was the only
 * array there was; `/guide` now carries three (`components/guide/tracks.ts`) and the page decides
 * which one to render, keyed on the deck so this component's per-deck state — `expandedId` and
 * `activeIndex`, both indices into ONE array — cannot be carried across a change of deck and open a
 * card that is not there.
 *
 * The spine is the page's organising animation. `useScroll` measures how far the reader has
 * travelled through the step list (not the document — the offsets are anchored to the list's
 * own top and bottom), and that single progress value feeds three consumers: the spine's
 * `scaleY` fill, the travelling node's `top`, and the rail's progress ring. None of them
 * re-render React; they are motion values written straight to the DOM.
 *
 * Horizontally, ONE thing owns the axis: `--guide-rail`, the width of the list's first grid
 * column. The spine is centred in a box of that width and every step's numbered bubble is a grid
 * item in that same column, so the track, the fill, the node and the numbers share a centre
 * line by construction. They used to be independent guesses (`absolute left-4 sm:left-6` in two
 * places plus `pl-12 sm:pl-16` in a third, reconciled by `-translate-x-1/2`), which is a
 * three-way agreement that has to be re-derived by hand every time a size or a breakpoint moves —
 * and it had already broken: see the note on the spine below.
 *
 * Under reduced motion — the OS preference OR the app's own Settings toggle, see
 * `useAppReducedMotion` — the spring smoothing is dropped (the raw scroll value is used, so the
 * fill tracks the scrollbar exactly with no inertia) and the travelling node is not rendered at
 * all.
 */
export function GuideJourney({ steps }: { steps: GuideStep[] }) {
  const reduce = useAppReducedMotion();
  const listRef = useRef<HTMLOListElement>(null);
  const [activeIndex, setActiveIndex] = useState(0);
  // One card open at a time. The first is open on arrival so the shape of a step is obvious
  // without the reader having to discover that the cards expand.
  //
  // `steps[0]` IS INDEXED UNGUARDED, exactly as `GUIDE_STEPS[0]` was before the decks existed: a
  // walkthrough with no steps in it is not a state this page has a rendering for, and a `?.` here
  // would only turn an empty deck into a blank page instead of a crash. What changed is that there
  // are now three arrays that must never be empty rather than one, so
  // `e2e/guide-tracks-unit.spec.ts` asserts it of every registered deck.
  const [expandedId, setExpandedId] = useState<string | null>(steps[0].id);

  // "start 65%" → progress begins when the list's top passes 65% down the viewport;
  // "end 65%"   → it completes when the list's bottom reaches the same line. Measured: the fill
  // reads 100% with the LAST step's bottom edge still on screen (~58vh at 1280, ~43vh at 360),
  // i.e. the spine completes while you are looking at the last step, not after you have scrolled
  // past it into the outro — and it does reach 100% at every width, with room to spare.
  //
  // It said "the tenth step's" until 2026-08-29, which was this measurement's own subject when there
  // were ten of them and a contradiction with the very next clause once there were not. The offsets
  // are anchored to the LIST — `target: listRef` — so they are indifferent to how many children it
  // has, and the measurement re-derives itself as the array grows. Nothing here needs a number; a
  // number here only records how long ago somebody last looked.
  const { scrollYProgress } = useScroll({ target: listRef, offset: ["start 65%", "end 65%"] });
  const smoothed = useSpring(scrollYProgress, { stiffness: 140, damping: 30, mass: 0.4 });
  const progress = reduce ? scrollYProgress : smoothed;
  const nodeTop = useTransform(progress, [0, 1], ["0%", "100%"]);

  // Deep links: /guide#questionnaire opens that step and scrolls to it.
  //
  // VALIDATED AGAINST THIS DECK AND SILENTLY IGNORED OTHERWISE, and since 2026-09-16 that check is
  // load-bearing rather than defensive.
  //
  // ⚠ THIS COMMENT STATED THE OPPOSITE RULE AND THE OLD CLAUSE IS KEPT RATHER THAN DROPPED. It read:
  // "an anchor belonging to ANOTHER deck must select that deck rather than be discarded … so by the
  // time this effect runs, a valid anchor is already in `steps`." That was true while the anchor
  // outranked the role. The owner ruled the other way (OQ-5, arm b): `app/(protected)/guide/page.tsx`
  // resolves the anchor with `guideTrackForAnchor` and then INTERSECTS the answer with
  // `guideTracksFor(user)`, so for every tier but ADMIN and MASTER_ADMIN an anchor into another deck
  // is discarded and the page opens on the reader's own deck. A valid anchor is therefore precisely
  // NOT already in `steps` — it is there only when the deck that owns it is a deck this account may
  // read.
  //
  // SO THIS CHECK NOW FAILS FOR TWO DIFFERENT REASONS AND BEHAVES THE SAME WAY FOR BOTH: an anchor
  // belonging to NO deck (a stale link, a renamed step), and an anchor belonging to a deck this
  // reader is not shown. Either way the page stays where it is rather than throwing, and nothing on
  // screen says the hash meant something — which is arm (b) as ruled, and why `steps.ts`' header now
  // qualifies the "point every lock panel at its own step anchor" recommendation it carries.
  useEffect(() => {
    const hash = window.location.hash.replace("#", "");
    if (!hash || !steps.some((step) => step.id === hash)) return;
    setExpandedId(hash);
    // Wait a frame so the expanded card has its final height before we scroll to it.
    const frame = window.requestAnimationFrame(() => scrollToStep(hash, true));
    return () => window.cancelAnimationFrame(frame);
    // Deliberately once per mount, as before. The page remounts this component on a deck change
    // (a key of "journey-" plus the track id), so a fresh deck gets a fresh run and a live `steps`
    // identity change does not re-fire the scroll under a reader already part-way down the page.
    //
    // THE PREFIX ON THAT KEY IS LOAD-BEARING and was added on 2026-09-16: this component and
    // `GuideHero` are siblings in one fragment and both read `key={track.id}`, which made the two
    // collide in React's sibling map and left every previous deck's hero band undeleted in the DOM.
    // Both keys still change per deck — the remount here is unaffected — and they now also differ
    // from each other. See the comment beside them in `app/(protected)/guide/page.tsx`.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function jump(id: string) {
    setExpandedId(id);
    scrollToStep(id, reduce);
  }

  return (
    <section className="mt-10 grid gap-8 lg:grid-cols-[260px_minmax(0,1fr)]" aria-label="The documentation process, step by step">
      <GuideRail steps={steps} activeIndex={activeIndex} progress={progress} onJump={jump} />

      <div className="relative">
        <ol
          ref={listRef}
          // `--guide-rail` is the single owner of the spine's horizontal axis. It is the width of
          // the list's first grid column (see `GuideStepCard`), and the spine below is centred in
          // a box of exactly that width anchored to the same `left-0`. Track, fill, travelling
          // node and every numbered bubble therefore share one centre line by construction —
          // change the rail width or the bubble size and they still cannot drift apart.
          className="relative grid gap-4 [--guide-rail:2rem] sm:[--guide-rail:3rem]"
        >
          {/* Spine: a static track, a scroll-linked fill, and the node that rides the fill.
              Nothing here is centred with `translateX`. framer-motion writes its own inline
              `transform` for `scaleY` (and for the bubbles' `scale`), and an inline transform
              silently overrides a Tailwind `-translate-x-1/2` from a class — which is exactly how
              the fill ended up 1px off the track and every bubble a full half-width (16px) to the
              right of it. Flex centring inside the rail box cannot be clobbered that way. */}
          <div
            aria-hidden
            className="pointer-events-none absolute inset-y-6 left-0 flex w-[var(--guide-rail)] justify-center"
          >
            <div className="relative h-full w-0.5">
              <span className="absolute inset-0 rounded-full bg-line-200" />
              <motion.span
                style={{ scaleY: progress }}
                className="absolute inset-0 origin-top rounded-full bg-purple-700"
              />
              {reduce ? null : (
                <motion.span
                  style={{ top: nodeTop }}
                  // Centred on the 2px track by margins, not transforms: -4px = (2px − 10px) / 2
                  // horizontally, -5px = half the dot vertically.
                  className="absolute -left-1 -mt-[5px] h-2.5 w-2.5 rounded-full bg-purple-700 ring-4 ring-purple-100"
                />
              )}
            </div>
          </div>

          {steps.map((step, index) => (
            <GuideStepCard
              key={step.id}
              step={step}
              index={index}
              expanded={expandedId === step.id}
              onToggle={() => setExpandedId((current) => (current === step.id ? null : step.id))}
              onEnterView={() => setActiveIndex(index)}
            />
          ))}
        </ol>
      </div>
    </section>
  );
}
