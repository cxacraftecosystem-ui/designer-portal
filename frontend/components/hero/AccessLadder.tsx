"use client";

import { motion, type Variants } from "framer-motion";

import { useHeroReducedMotion } from "@/components/hero/useHeroMotion";
import { ROLE_LABELS, ROLES_BY_RANK } from "@/lib/permissions";
import type { UserRole } from "@/lib/types";

const TIER_COPY: Record<UserRole, string> = {
  CROWDSOURCE_VOLUNTEER: "Take interviews, upload media, comment on records.",
  FIELD_CONTRIBUTOR:
    "Fill in and correct existing records, and review a volunteer's work. Cannot open a new record.",
  RESEARCHER:
    "Create artisans, products, processes, tools and interviews; edit your own, and review field contributors' and volunteers' work.",
  DESIGNER:
    "Run design & prototype workshops and sign the report — the stages, the custom sections, the AI layers, the exports.",
  INSPECTOR:
    "Inspect and review a designer's records — approve, reject, send back. Does not run workshops and does not rewrite a designer's record.",
  PROFESSOR: "Crafts, workshops, the questionnaire builder, promotions, full dataset download.",
  ASSISTANT_DIRECTOR:
    "Everything a professor can do, and review or correct a professor's records as well as an inspector's and a designer's.",
  REGIONAL_DIRECTOR:
    "Everything an assistant director can do, and review an assistant director's work too. Reads design-workshop data on screen.",
  MINISTRY_ADMIN:
    "The widest review and correction authority short of admin, and creates password accounts and resets their passwords. Not an admin: no deletes, no capability grants.",
  ADMIN: "Settings hub, task assignment, workshop access grants, accounts.",
  MASTER_ADMIN: "Everything, plus app-wide settings and the keys for connected services."
};

const TIERS_LOW_TO_HIGH: UserRole[] = [...ROLES_BY_RANK].reverse();

const RUNG_SPAN = Math.max(TIERS_LOW_TO_HIGH.length - 1, 1);

const NUMBER_WORD: Record<number, string | undefined> = {
  1: "One",
  2: "Two",
  3: "Three",
  4: "Four",
  5: "Five",
  6: "Six",
  7: "Seven",
  8: "Eight",
  9: "Nine",
  10: "Ten",
  11: "Eleven",
  12: "Twelve"
};
const TIER_COUNT = TIERS_LOW_TO_HIGH.length;

export const TIER_COUNT_WORD = NUMBER_WORD[TIER_COUNT] ?? String(TIER_COUNT);

export default function AccessLadder() {
  const reduce = useHeroReducedMotion();

  const container: Variants = {
    hidden: {},
    show: { transition: { staggerChildren: reduce ? 0 : 0.07 } }
  };
  const item: Variants = {
    hidden: { opacity: 0, x: -12 },
    show: { opacity: 1, x: 0, transition: { duration: reduce ? 0 : 0.45, ease: [0.16, 1, 0.3, 1] } }
  };

  return (
    <section
      id="access"
      className="mx-auto max-w-6xl px-6 py-24"
      aria-label={`The ${TIER_COUNT_WORD.toLowerCase()}-tier access ladder`}
    >
      <motion.div initial="hidden" whileInView="show" viewport={{ once: true, amount: 0.2 }} variants={container}>
        <motion.p variants={item} className="eyebrow mb-3">
          Access is a ladder
        </motion.p>
        <motion.h2
          variants={item}
          className="max-w-2xl font-display text-3xl font-bold tracking-tight text-ink-900 sm:text-4xl"
        >
          {TIER_COUNT_WORD} tiers, and each one inherits the last.
        </motion.h2>
        <motion.p variants={item} className="mt-4 max-w-2xl text-base leading-relaxed text-ink-500">
          An administrator approves your address, then sets your role. Single permissions — download,
          review, the questionnaire builder — can be granted without changing it.
        </motion.p>
        <motion.p variants={item} className="mt-3 max-w-2xl text-base leading-relaxed text-ink-500">
          One exception: design &amp; prototype workshops are run by designers, ministry officers and
          admins. Professors and inspectors do not run workshops.
        </motion.p>

        <ol className="mt-12 space-y-2.5">
          {TIERS_LOW_TO_HIGH.map((role, index) => (
            <motion.li
              key={role}
              variants={item}
              className="flex items-center gap-4 rounded-md border border-line-200 bg-card p-4 shadow-sm transition hover:shadow-md sm:gap-5"
            >
              <span
                aria-hidden
                className="grid h-8 w-8 shrink-0 place-items-center rounded-md bg-purple-50 font-display text-sm font-bold text-purple-700"
              >
                {index + 1}
              </span>
              <div className="min-w-0 flex-1 sm:flex sm:items-baseline sm:gap-5">
                <h3 className="font-display text-sm font-bold text-ink-900 sm:w-52 sm:shrink-0">{ROLE_LABELS[role]}</h3>
                <p className="mt-1 text-sm leading-relaxed text-ink-500 sm:mt-0">{TIER_COPY[role]}</p>
              </div>
              <span
                aria-hidden
                className="hidden h-1.5 shrink-0 rounded-full bg-purple-700 lg:block"
                style={{ width: `${2 + (index * 8) / RUNG_SPAN}rem`, opacity: 0.35 + (index * 0.65) / RUNG_SPAN }}
              />
            </motion.li>
          ))}
        </ol>
      </motion.div>
    </section>
  );
}
