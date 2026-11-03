"use client";

import { motion, type Variants } from "framer-motion";
import { ArrowUpDown, Check, IdCard, PenTool, QrCode } from "lucide-react";

import { useHeroReducedMotion } from "@/components/hero/useHeroMotion";
import { STAGE_COUNT_WORD_LOWER } from "@/components/hero/workshopArc";

const SURFACES = [
  {
    icon: IdCard,
    title: "Your profile, typed once",
    copy:
      "One page holding the twenty-one things a submitted report has to say about the person who ran the workshop.",
    points: [
      "Name and local-script name, designation, institution, department, qualification, specialisation, years",
      "Phone, email, website and address; empanelment number and date; photograph and signature",
      "A CV, shown on the page when it is a PDF",
      "Four are required — name, qualification, phone and email. The other seventeen can wait until you have them",
      "A new workshop's stages start pre-filled with all twenty-one, and each stays editable inside the workshop"
    ]
  },
  {
    icon: PenTool,
    title: "Sketches and prototypes",
    copy:
      "A sketch usually enters an archive as a photograph of a sheet of paper on a courtyard table, at whatever angle somebody happened to be standing.",
    points: [
      "The sheet squared to the page and turned into a clean, printable plate, right on the device",
      "Line art traced to vector on the same device, with nothing uploaded — in the browser and on the phone alike",
      "The photograph is never overwritten: the plate is saved as a second file beside it",
      "The trace saved five ways, on the web or the phone — SVG and PNG to attach, PDF to send on, DXF for a cutting machine, EPS for a print shop",
      "“Measure a dimension from a photograph”, for the piece that has gone home while the questions have not",
      "Prototypes carry their own drawings and photographs, and a 3D model file where there is one"
    ]
  },
  {
    icon: ArrowUpDown,
    title: "Review by the people who would know",
    copy:
      "Colleagues rate a sketch or a prototype qualitatively and quantitatively, leave a suggestion, and then put the set in order.",
    points: [
      "Sorted by score by default — and the designer has the final say",
      "Reorder by dragging, or by the up and down arrows on the card itself",
      "Two rounds: the workshop's own designers first, then the whole pool once prototypes are finalised",
      "The order the designers settle on is the one saved — their judgement, never a computed ranking"
    ]
  },
  {
    icon: QrCode,
    title: "One workshop, joined by scanning",
    copy:
      "Designers, the master craftsperson and the reviewing officer join one workshop by scanning a card — no signal needed.",
    points: [
      "A printed card admits a colleague to that workshop: they can read it and fill in its stages, but never delete anything or give access to anyone else",
      "Single-use by default; only an administrator can print one that lets a whole group in",
      "A card that has already been used turns nobody away: the request goes to an administrator to decide",
      "“Scan a code” is one tap from the screen the app opens on, on the web and the phone — for a card, a prototype tag or a screenshot of one",
      "The code holds no web address and no personal details: no name, no village, no craft, and never an Aadhaar number"
    ]
  }
];

export default function DesignerWorkbench() {
  const reduce = useHeroReducedMotion();

  const container: Variants = {
    hidden: {},
    show: { transition: { staggerChildren: reduce ? 0 : 0.08 } }
  };
  const item: Variants = {
    hidden: { opacity: 0, y: 18 },
    show: { opacity: 1, y: 0, transition: { duration: reduce ? 0 : 0.5, ease: [0.16, 1, 0.3, 1] } }
  };

  return (
    <section
      id="designer"
      className="border-y border-line-200 bg-surface-50 py-24"
      aria-label="Built for the designer"
    >
      <motion.div
        initial="hidden"
        whileInView="show"
        viewport={{ once: true, amount: 0.15 }}
        variants={container}
        className="mx-auto max-w-6xl px-6"
      >
        <motion.p variants={item} className="eyebrow mb-3">
          For the designer
        </motion.p>
        <motion.h2
          variants={item}
          className="max-w-3xl font-display text-3xl font-bold tracking-tight text-ink-900 sm:text-4xl"
        >
          Your profile, your sketches, and your colleagues&rsquo; own judgement.
        </motion.h2>
        <motion.p variants={item} className="mt-4 max-w-2xl text-base leading-relaxed text-ink-500">
          The {STAGE_COUNT_WORD_LOWER} stages record the craft. These four are for the person
          running them — the designer whose name the finished document is submitted under.
        </motion.p>

        <div className="mt-12 grid gap-5 md:grid-cols-2">
          {SURFACES.map((surface) => (
            <motion.div
              key={surface.title}
              variants={item}
              className="rounded-lg border border-line-200 bg-card p-6 shadow-sm transition hover:-translate-y-0.5 hover:shadow-md"
            >
              <span className="mb-4 flex h-11 w-11 items-center justify-center rounded-md bg-purple-700 text-white">
                <surface.icon className="h-5 w-5" aria-hidden />
              </span>
              <h3 className="font-display text-lg font-bold text-ink-900">{surface.title}</h3>
              <p className="mt-1.5 text-sm leading-relaxed text-ink-500">{surface.copy}</p>
              <ul className="mt-5 space-y-2.5 border-t border-line-200 pt-5">
                {surface.points.map((point) => (
                  <li key={point} className="flex items-start gap-2.5 text-sm leading-relaxed text-ink-700">
                    <Check className="mt-0.5 h-4 w-4 shrink-0 text-purple-700 dark:text-purple-300" aria-hidden />
                    {point}
                  </li>
                ))}
              </ul>
            </motion.div>
          ))}
        </div>

        <motion.p variants={item} className="mt-8 max-w-2xl text-sm leading-relaxed text-ink-500">
          A designer signing in for the first time is taken to that profile page once, with a note
          saying why. It does not come back on every visit.
        </motion.p>
      </motion.div>
    </section>
  );
}
