"use client";

import Link from "next/link";
import { motion, type Variants } from "framer-motion";
import { ArrowRight, Compass } from "lucide-react";

import { useHeroReducedMotion } from "@/components/hero/useHeroMotion";

/**
 * The chapters the in-app walkthrough covers, so the link promises something specific.
 *
 * ⚠ IT PROMISED FOUR, ALL OF THEM ABOUT ARTISAN RECORDS, while `components/guide/steps.ts` carries
 * a whole run of chapters about the design & prototype workshop — every step whose `id` begins
 * `design` (the designer's profile, the workshop itself, its stages, the code cards, sketches and
 * prototypes, design review, readiness, the report and the report history). This list is a promise
 * about a destination, and a promise that omits the half of the destination a designer came for
 * sends them away from the one tour that covers it.
 *
 * NO COUNT IS STATED HERE, DELIBERATELY. That file is under active edit and gained three steps
 * between two reads of it while this component was being written; a number in this comment would
 * have been wrong before the change was finished, and a number on the page beside a list of six
 * lines would be worse. The two lines added below are compressions of that run, in its own order,
 * and each names something the guide really shows.
 *
 * SIX LINES IS WHERE THIS STOPS. One line per chapter would be a table of contents, and a table of
 * contents is the thing this section is trying to get somebody to open rather than to read here.
 */
const CHAPTERS = [
  "Recording an artisan and carrying them into a product",
  "Running an interview and reading the transcript back",
  "Sending work up the review ladder",
  "Sharing data with another researcher",
  "Your designer profile, and working a workshop through its stages",
  "Sketches, peer review, readiness, and generating the report"
];

/**
 * The walkthrough band. `/guide` sits inside the app (it is the web twin of the Android
 * first-run walkthrough), so this links to it and signing in is the first step — the same journey
 * a new researcher takes anyway.
 */
export default function WalkthroughCallout() {
  const reduce = useHeroReducedMotion();

  const reveal: Variants = {
    hidden: { opacity: 0, y: 18 },
    show: { opacity: 1, y: 0, transition: { duration: reduce ? 0 : 0.5, ease: [0.16, 1, 0.3, 1] } }
  };

  return (
    <section className="mx-auto max-w-6xl px-6 pb-24" aria-label="The in-app walkthrough">
      <motion.div
        initial="hidden"
        whileInView="show"
        viewport={{ once: true, amount: 0.3 }}
        variants={reveal}
        className="grid gap-8 rounded-xl border border-line-200 bg-card p-8 shadow-sm sm:p-10 lg:grid-cols-[1fr_auto] lg:items-center lg:gap-12"
      >
        <div>
          <span className="mb-5 flex h-11 w-11 items-center justify-center rounded-md bg-purple-700 text-white">
            <Compass className="h-5 w-5" aria-hidden />
          </span>
          <h2 className="font-display text-2xl font-bold tracking-tight text-ink-900 sm:text-3xl">
            Never documented a craft before? Start with the walkthrough.
          </h2>
          <p className="mt-3 max-w-2xl text-base leading-relaxed text-ink-500">
            Every account gets the same guided tour of the documentation process — what each record
            type is for, what makes a good interview, and how work travels from the field to an
            approved, exportable dataset. For a designer it keeps going: how a design &amp; prototype
            workshop runs from its first stage to a submitted report.
          </p>
          <ul className="mt-6 grid gap-2 sm:grid-cols-2">
            {CHAPTERS.map((chapter) => (
              <li key={chapter} className="flex items-start gap-2.5 text-sm leading-relaxed text-ink-700">
                <ArrowRight className="mt-0.5 h-4 w-4 shrink-0 text-purple-700" aria-hidden />
                {chapter}
              </li>
            ))}
          </ul>
        </div>
        <Link
          href="/guide"
          className="inline-flex h-12 shrink-0 items-center justify-center gap-2 rounded-md bg-purple-700 px-7 font-display text-base font-bold tracking-tight text-white shadow-cta transition hover:-translate-y-0.5 hover:bg-purple-800 active:translate-y-0"
        >
          Open the walkthrough
          <ArrowRight className="h-4 w-4" aria-hidden />
        </Link>
      </motion.div>
    </section>
  );
}
