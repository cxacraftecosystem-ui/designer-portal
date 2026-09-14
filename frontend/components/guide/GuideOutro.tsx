"use client";

import Link from "next/link";
import { motion } from "framer-motion";
import { ArrowUpRight, CheckCircle2 } from "lucide-react";

import { riseItem, slideItem, springy, staggerParent } from "@/components/guide/guideMotion";
import type { GuideTrack } from "@/components/guide/tracks";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";

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
 * ── "WHERE TO GO NEXT" MUST BE REACHABLE BY THE DECK'S AUDIENCE ──────────────────────────────────
 *
 * Every entry is a plain `<Link>` with no gate on it, so an entry the reader cannot open is a tile
 * that lands them on a lock panel from the one band of the page that exists to say "you are done,
 * here is where to go". A step CARD may name a screen the reader cannot open — that is how the
 * ungated guide teaches a capability somebody has not earned yet, and every such card says so in its
 * own `watch`. An exit tile may not: it makes a promise instead of a description.
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 */

/**
 * The closing section: a compact recap of the steps as a single readable line, the checklist of
 * things that cannot be fixed later, and the screens this deck's reader goes to once they are done.
 *
 * Everything here reveals on scroll with the same staggered vocabulary as the step cards, so
 * the end of the page feels like the same document rather than a footer bolted on.
 */
export function GuideOutro({ track }: { track: GuideTrack }) {
  const reduce = useAppReducedMotion();
  const steps = track.steps;

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
          {track.next.map((entry) => (
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
