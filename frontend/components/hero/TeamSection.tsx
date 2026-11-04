"use client";

import { motion, type Variants } from "framer-motion";
import { Check, Compass, Microscope, PenTool, ShieldCheck } from "lucide-react";

import { useHeroReducedMotion } from "@/components/hero/useHeroMotion";
import { STAGE_COUNT_WORD } from "@/components/hero/workshopArc";

const AUDIENCES = [
  {
    icon: Compass,
    title: "Field teams",
    copy: "Capture everything where the work actually happens — no signal required.",
    points: ["Offline capture that syncs later", "GPS tagging on every record", "Audio, video and photo media"]
  },
  {
    icon: Microscope,
    title: "Researchers",
    copy: "Turn raw field material into structured, shareable research.",
    points: [
      "Structured questionnaires per artisan set",
      "Collaboration requests and grants",
      "Download, comment and edit sharing tiers"
    ]
  },
  {
    icon: PenTool,
    title: "Designers",
    copy: "Run a design & prototype workshop end to end, and sign the document it produces.",
    points: [
      `${STAGE_COUNT_WORD} stages, filled with no signal and resumed where you left off`,
      "Sketches, prototypes, and a ranking that is your colleagues' judgement",
      "A submission-ready .docx or PDF, from the web or from the phone"
    ]
  },
  {
    icon: ShieldCheck,
    title: "Administrators",
    copy: "Keep quality and access moving through the same ladder.",
    points: [
      "Review ladder — approve, reject, revise",
      "Assigned tasks with due dates",
      "Grantable dataset downloads",
      "Appoint an inspector to a workshop: they can read every stage and see who wrote each field, without changing anything"
    ]
  }
];

export default function TeamSection() {
  const reduce = useHeroReducedMotion();

  const container: Variants = {
    hidden: {},
    show: { transition: { staggerChildren: reduce ? 0 : 0.08 } }
  };
  const item: Variants = {
    hidden: { opacity: 0, y: 16 },
    show: { opacity: 1, y: 0, transition: { duration: reduce ? 0 : 0.45, ease: [0.16, 1, 0.3, 1] } }
  };

  return (
    <section className="mx-auto max-w-6xl px-6 py-24" aria-label="Built for the whole team">
      <motion.div initial="hidden" whileInView="show" viewport={{ once: true, amount: 0.2 }} variants={container}>
        <motion.p variants={item} className="eyebrow mb-3">
          Who it is for
        </motion.p>
        <motion.h2
          variants={item}
          className="max-w-2xl font-display text-3xl font-bold tracking-tight text-ink-900 sm:text-4xl"
        >
          Built for the whole team.
        </motion.h2>

        <div className="mt-12 grid gap-5 md:grid-cols-2 lg:grid-cols-4">
          {AUDIENCES.map((audience) => (
            <motion.div
              key={audience.title}
              variants={item}
              className="rounded-lg border border-line-200 bg-card p-6 shadow-sm transition hover:-translate-y-0.5 hover:shadow-md"
            >
              <span className="mb-4 flex h-11 w-11 items-center justify-center rounded-md bg-purple-700 text-white">
                <audience.icon className="h-5 w-5" aria-hidden />
              </span>
              <h3 className="font-display text-lg font-bold text-ink-900">{audience.title}</h3>
              <p className="mt-1.5 text-sm leading-relaxed text-ink-500">{audience.copy}</p>
              <ul className="mt-5 space-y-2.5 border-t border-line-200 pt-5">
                {audience.points.map((point) => (
                  <li key={point} className="flex items-start gap-2.5 text-sm leading-relaxed text-ink-700">
                    <Check className="mt-0.5 h-4 w-4 shrink-0 text-purple-700 dark:text-purple-300" aria-hidden />
                    {point}
                  </li>
                ))}
              </ul>
            </motion.div>
          ))}
        </div>
      </motion.div>
    </section>
  );
}
