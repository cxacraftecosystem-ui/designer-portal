"use client";

import Link from "next/link";
import { motion, type Variants } from "framer-motion";
import { ChevronDown } from "lucide-react";

import { TIER_COUNT_WORD } from "@/components/hero/AccessLadder";
import { useHeroReducedMotion } from "@/components/hero/useHeroMotion";
import { STAGE_COUNT_WORD_LOWER } from "@/components/hero/workshopArc";

const TIER_COUNT_WORD_LOWER = TIER_COUNT_WORD.toLowerCase();

const FAQS = [
  {
    q: "Who can sign in?",
    a: "Only addresses an administrator has approved. Anyone else can request access when they sign in."
  },
  {
    q: "I signed in with Google and was told I need approval. Why?",
    a: "Google sign-in uses the same approved list. You can sign in once an administrator approves you."
  },
  {
    q: "What happens to my recordings?",
    a: "They upload to private storage and are transcribed and translated into English. Each transcript is linked to its artisan, craft and workshop."
  },
  {
    q: "How does review work?",
    a: "Each role reviews the roles below it. A reviewer approves, rejects, or sends a record back with comments."
  },
  {
    q: "Who can download the data?",
    a: "Professors and above. Others need a download grant or a share from the record's owner."
  },
  {
    q: "What can a brand-new account actually do?",
    a: "Take interviews, upload media and comment on records. Creating artisans, products, processes and tools starts at Researcher."
  },
  {
    q: "What is a design & prototype workshop?",
    a: `A sanctioned fortnight in a craft cluster, recorded as ${STAGE_COUNT_WORD_LOWER} stages — from setup and market survey to prototypes, costing and follow-up. The stages are the report, so there is no writing-up afterwards.`
  },
  {
    q: "Who can start a design workshop, and who does the work inside it?",
    a: "Admins start one. Designers, ministry officers and admins work inside it — designers, ask an admin to add you."
  },
  {
    q: "Will an unfilled field stop me submitting a workshop?",
    a: "No. You submit when you're ready; a readiness screen lists what's still missing, even offline."
  },
  {
    q: "Does it work offline?",
    a: "Yes. The Android app and the web portal both record workshops and build reports offline, and sync when you're back online. Recording questionnaire answers needs a connection."
  },
  {
    q: "What about privacy?",
    a: `Access follows ${TIER_COUNT_WORD_LOWER} roles, every edit is audited, and media is private. Aadhaar numbers show as XXXX XXXX 9012 everywhere they are shared or exported.`
  }
];

export default function HeroFAQ() {
  const reduce = useHeroReducedMotion();

  const container: Variants = {
    hidden: {},
    show: { transition: { staggerChildren: reduce ? 0 : 0.06 } }
  };
  const item: Variants = {
    hidden: { opacity: 0, y: 14 },
    show: { opacity: 1, y: 0, transition: { duration: reduce ? 0 : 0.45, ease: [0.16, 1, 0.3, 1] } }
  };

  return (
    <section id="faq" className="mx-auto max-w-3xl px-6 py-24" aria-label="Frequently asked questions">
      <motion.div initial="hidden" whileInView="show" viewport={{ once: true, amount: 0.15 }} variants={container}>
        <motion.p variants={item} className="eyebrow mb-3 text-center">
          Questions
        </motion.p>
        <motion.h2
          variants={item}
          className="text-center font-display text-3xl font-bold tracking-tight text-ink-900 sm:text-4xl"
        >
          Answered before you ask.
        </motion.h2>

        <motion.div variants={item} className="mt-10 rounded-lg border border-line-200 bg-card px-6 shadow-sm">
          {FAQS.map((faq) => (
            <details key={faq.q} className="group border-b border-line-200 last:border-b-0">
              <summary className="flex cursor-pointer list-none items-center justify-between gap-4 py-5 font-display text-base font-semibold text-ink-900 transition hover:text-purple-700 [&::-webkit-details-marker]:hidden">
                {faq.q}
                <ChevronDown
                  className="h-4 w-4 shrink-0 text-ink-500 transition-transform duration-200 group-open:rotate-180"
                  aria-hidden
                />
              </summary>
              <p className="pb-5 text-sm leading-relaxed text-ink-700">{faq.a}</p>
            </details>
          ))}
        </motion.div>

        <motion.p variants={item} className="mt-6 text-center text-sm text-ink-500">
          Still unsure where to start?{" "}
          <Link href="/guide" className="font-medium text-purple-700 dark:text-purple-300 underline-offset-2 hover:underline">
            The walkthrough
          </Link>{" "}
          covers every screen in the order you will meet them.
        </motion.p>
      </motion.div>
    </section>
  );
}
