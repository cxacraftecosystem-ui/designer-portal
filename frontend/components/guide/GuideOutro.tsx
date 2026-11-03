"use client";

import Link from "next/link";
import { motion } from "framer-motion";
import { ArrowUpRight, CheckCircle2 } from "lucide-react";

import { adminChromeRouteFor, adminChromeVisible } from "@/components/AdminViewProvider";
import { riseItem, slideItem, springy, staggerParent } from "@/components/guide/guideMotion";
import type { GuideTrack } from "@/components/guide/tracks";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import { canAccessRoute } from "@/lib/permissions";
import type { User } from "@/lib/types";

export function GuideOutro({
  track,
  user,
  adminMode
}: {
  track: GuideTrack;
  user: User | null | undefined;
  adminMode: boolean;
}) {
  const reduce = useAppReducedMotion();
  const steps = track.steps;

  const exits = track.next.filter(
    (entry) =>
      canAccessRoute(user, entry.href) &&
      (!adminChromeRouteFor(entry.href) || adminChromeVisible(user, adminMode))
  );

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
                <span aria-hidden className="font-display text-purple-700 dark:text-purple-300">
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
              <CheckCircle2 className="mt-1 h-4 w-4 shrink-0 text-purple-700 dark:text-purple-300" aria-hidden />
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
          {exits.map((entry) => (
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
