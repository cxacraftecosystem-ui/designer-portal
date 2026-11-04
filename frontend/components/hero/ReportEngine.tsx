"use client";

import { motion, type Variants } from "framer-motion";
import {
  BarChart3,
  FileClock,
  FileText,
  FileWarning,
  MonitorPlay,
  Palette,
  ScrollText,
  Smartphone
} from "lucide-react";

import { useHeroReducedMotion } from "@/components/hero/useHeroMotion";
import { STAGE_COUNT_WORD_LOWER } from "@/components/hero/workshopArc";
import { ACCENT_PRESETS } from "@/lib/reportTheme";

const RENDERERS = [
  { icon: FileText, format: ".docx", where: "online" },
  { icon: FileText, format: ".pdf", where: "online" },
  { icon: MonitorPlay, format: "Preview", where: "in the browser" },
  { icon: MonitorPlay, format: ".docx", where: "in the browser" },
  { icon: MonitorPlay, format: ".pdf", where: "in the browser" },
  { icon: Smartphone, format: ".docx", where: "on the phone" },
  { icon: Smartphone, format: ".pdf", where: "on the phone" },
  { icon: Smartphone, format: "Preview", where: "on the phone" }
];

const FACTS = [
  {
    icon: ScrollText,
    title: "Read as paper, not as cards",
    copy:
      "The preview shows real A4 or Letter pages, with every page break marked — so you see the layout before anyone prints it."
  },
  {
    icon: Palette,
    title: "Six templates, and colour you can see before you commit",
    copy:
      `DCH, DIC, implementing agency, compact summary, technical report and photo catalogue — with ${ACCENT_PRESETS.length} accent colours that restyle the preview instantly.`
  },
  {
    icon: FileWarning,
    title: "Warnings sit beside the download, never inside the file",
    copy:
      "Missing fields or photos are flagged next to the download. The file is still generated, and stays clean."
  },
  {
    icon: Smartphone,
    title: "Generated on the phone, with no signal needed",
    copy:
      "The phone and the browser write the .docx and PDF from the local draft, even with no signal."
  },
  {
    icon: FileClock,
    title: "Every file you have generated, and what provably did not change",
    copy:
      "Every generated file is listed with its template, pages and date. Compare any two: stages nobody saved in between are marked unchanged, saved ones as edited."
  },
  {
    icon: BarChart3,
    title: "Figures the report draws for itself",
    copy:
      "Seven charts — survey respondents and prices, cost by head, price bands, sketches to products, and follow-up adoption — each drawn from its own stage, so it always matches the table above it."
  }
];

export default function ReportEngine() {
  const reduce = useHeroReducedMotion();

  const container: Variants = {
    hidden: {},
    show: { transition: { staggerChildren: reduce ? 0 : 0.06 } }
  };
  const item: Variants = {
    hidden: { opacity: 0, y: 18 },
    show: { opacity: 1, y: 0, transition: { duration: reduce ? 0 : 0.5, ease: [0.16, 1, 0.3, 1] } }
  };

  return (
    <section id="report" className="mx-auto max-w-6xl px-6 py-24" aria-label="Report generation">
      <motion.div initial="hidden" whileInView="show" viewport={{ once: true, amount: 0.15 }} variants={container}>
        <motion.p variants={item} className="eyebrow mb-3">
          The deliverable
        </motion.p>
        <motion.h2
          variants={item}
          className="max-w-3xl font-display text-3xl font-bold tracking-tight text-ink-900 sm:text-4xl"
        >
          One document, drawn eight ways that agree line for line.
        </motion.h2>
        <motion.p variants={item} className="mt-4 max-w-2xl text-base leading-relaxed text-ink-500">
          The {STAGE_COUNT_WORD_LOWER} stages become one document. What you preview is what the
          ministry receives — even when built offline.
        </motion.p>

        <motion.div variants={item} className="mt-12 flex flex-col items-center">
          <div className="rounded-md border border-line-200 bg-card px-5 py-3 text-center shadow-sm">
            <p className="font-display text-sm font-bold text-ink-900">One document</p>
            <p className="mt-0.5 text-xs text-ink-500">assembled from the stages, once</p>
          </div>
          <div aria-hidden className="h-6 w-px bg-line-200" />
        </motion.div>
        <motion.ul variants={item} className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {RENDERERS.map((renderer) => (
            <li
              key={`${renderer.format} ${renderer.where}`}
              className="rounded-md border border-line-200 bg-card p-4 text-center shadow-sm transition hover:-translate-y-0.5 hover:shadow-md"
            >
              <span className="mx-auto mb-3 flex h-9 w-9 items-center justify-center rounded-md bg-purple-50 text-purple-700">
                <renderer.icon className="h-4 w-4" aria-hidden />
              </span>
              <p className="font-display text-sm font-bold text-ink-900">{renderer.format}</p>
              <p className="mt-0.5 text-xs text-ink-500">{renderer.where}</p>
            </li>
          ))}
        </motion.ul>

        <div className="mt-12 grid gap-5 md:grid-cols-2">
          {FACTS.map((fact) => (
            <motion.div
              key={fact.title}
              variants={item}
              className="rounded-lg border border-line-200 bg-card p-6 shadow-sm transition hover:-translate-y-0.5 hover:shadow-md"
            >
              <span className="mb-4 flex h-11 w-11 items-center justify-center rounded-md bg-purple-700 text-white">
                <fact.icon className="h-5 w-5" aria-hidden />
              </span>
              <h3 className="font-display text-lg font-bold text-ink-900">{fact.title}</h3>
              <p className="mt-2 text-sm leading-relaxed text-ink-700">{fact.copy}</p>
            </motion.div>
          ))}
        </div>
      </motion.div>
    </section>
  );
}
