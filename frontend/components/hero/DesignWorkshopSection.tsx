"use client";

import { motion, type Variants } from "framer-motion";
import { Plus } from "lucide-react";

import { useHeroReducedMotion } from "@/components/hero/useHeroMotion";
import {
  arcRangeLabel,
  STAGE_COUNT_WORD_LOWER,
  WORKSHOP_ARC
} from "@/components/hero/workshopArc";

export default function DesignWorkshopSection() {
  const reduce = useHeroReducedMotion();

  const container: Variants = {
    hidden: {},
    show: { transition: { staggerChildren: reduce ? 0 : 0.045 } }
  };
  const item: Variants = {
    hidden: { opacity: 0, y: 18 },
    show: { opacity: 1, y: 0, transition: { duration: reduce ? 0 : 0.5, ease: [0.16, 1, 0.3, 1] } }
  };

  return (
    <section
      id="workshop"
      className="mx-auto max-w-6xl px-6 py-24"
      aria-label="The design and prototype workshop"
    >
      <motion.div initial="hidden" whileInView="show" viewport={{ once: true, amount: 0.15 }} variants={container}>
        <motion.p variants={item} className="eyebrow mb-3">
          The design &amp; prototype workshop
        </motion.p>
        <motion.h2
          variants={item}
          className="max-w-3xl font-display text-3xl font-bold tracking-tight text-ink-900 sm:text-4xl"
        >
          A fortnight in a cluster, carried from the first survey to a submission-ready document.
        </motion.h2>
        <motion.p variants={item} className="mt-4 max-w-2xl text-base leading-relaxed text-ink-500">
          A design &amp; prototype development workshop is not something you write up afterwards. It
          is {STAGE_COUNT_WORD_LOWER} stages the app walks a designer through while they are still in
          the room — and the stages <em>are</em> the report, so the document is finished at the moment
          the workshop is.
        </motion.p>
        <motion.p variants={item} className="mt-3 max-w-2xl text-base leading-relaxed text-ink-500">
          An administrator opens it for a cluster and its designers. It is submitted under one
          lead designer&rsquo;s name.
        </motion.p>

        <ul className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {WORKSHOP_ARC.map((group) => (
            <motion.li
              key={group.title}
              variants={item}
              className="rounded-lg border border-line-200 bg-card p-5 shadow-sm transition hover:-translate-y-0.5 hover:shadow-md"
            >
              <span className="mb-4 inline-flex h-7 items-center justify-center rounded-md bg-purple-700 px-2.5 font-display text-xs font-bold tabular-nums text-white">
                <span className="sr-only">Stages </span>
                {arcRangeLabel(group)}
              </span>
              <h3 className="font-display text-sm font-bold text-ink-900">{group.title}</h3>
              <p className="mt-1.5 text-sm leading-relaxed text-ink-500">{group.copy}</p>
            </motion.li>
          ))}
          <motion.li
            variants={item}
            className="rounded-lg border border-dashed border-purple-300 bg-card p-5 transition hover:-translate-y-0.5"
          >
            <span className="mb-4 inline-flex h-7 items-center justify-center rounded-md bg-purple-100 px-2.5 text-purple-700">
              <Plus className="h-4 w-4" aria-hidden />
            </span>
            <h3 className="font-display text-sm font-bold text-ink-900">And your own</h3>
            <p className="mt-1.5 text-sm leading-relaxed text-ink-700">
              Sections and questions a designer adds to their own workshop, ready to use straight
              away and reordered by arrows or by dragging.
            </p>
          </motion.li>
        </ul>

        <motion.div variants={item} className="mt-8 flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium text-ink-700">
            Across all {STAGE_COUNT_WORD_LOWER} stages:
          </span>
          {[
            "Every stage saves on its own, into a draft on the device",
            "Completeness scored right on the device",
            "A readiness list, not a locked submit",
            "Attach a photograph already in the repository",
            "An interview sitting cited into a stage — the facts about it, not the answers given in it",
            "Shaky and low-resolution photographs refused at the card, with the reason",
            "Printable code cards and prototype tags"
          ].map((chip) => (
            <span
              key={chip}
              className="rounded-full border border-line-200 bg-surface-50 px-3 py-1.5 text-xs font-medium text-ink-700"
            >
              {chip}
            </span>
          ))}
        </motion.div>

        <motion.p variants={item} className="mt-8 max-w-2xl text-sm leading-relaxed text-ink-500">
          You submit when you&rsquo;re ready — empty fields never block it. A readiness screen lists
          what&rsquo;s missing, even offline.
        </motion.p>
      </motion.div>
    </section>
  );
}
