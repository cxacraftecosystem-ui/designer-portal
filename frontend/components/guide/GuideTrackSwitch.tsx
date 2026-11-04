"use client";

import { motion } from "framer-motion";
import { Check } from "lucide-react";

import { riseItem, springy, staggerParent } from "@/components/guide/guideMotion";
import type { GuideTrack } from "@/components/guide/tracks";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";

export function GuideTrackSwitch({
  tracks,
  active,
  onChoose
}: {
  tracks: readonly GuideTrack[];
  active: GuideTrack;
  onChoose: (track: GuideTrack) => void;
}) {
  const reduce = useAppReducedMotion();

  return (
    <motion.section
      variants={staggerParent(reduce, 0.05)}
      initial="hidden"
      animate="show"
      aria-labelledby="guide-track-switch-heading"
      className="panel mt-6 p-4 sm:p-5"
    >
      <motion.h2
        id="guide-track-switch-heading"
        variants={riseItem(reduce, 8)}
        className="font-display text-sm font-bold text-ink-900"
      >
        Which walkthrough
      </motion.h2>
      <motion.p variants={riseItem(reduce, 8)} className="mt-1 text-xs leading-5 text-ink-500">
        This opened on the walkthrough for your role. As an admin you can also read the others;
        everyone else sees only the walkthrough for their own role.
      </motion.p>

      <motion.div variants={staggerParent(reduce, 0.04)} className="mt-4 grid gap-2.5 sm:grid-cols-3">
        {tracks.map((track) => {
          const selected = track.id === active.id;
          return (
            <motion.div key={track.id} variants={riseItem(reduce, 10)}>
              <motion.button
                type="button"
                aria-pressed={selected}
                onClick={() => onChoose(track)}
                whileHover={reduce ? undefined : { y: -2 }}
                whileTap={reduce ? undefined : { scale: 0.99 }}
                transition={springy(reduce)}
                className={
                  selected
                    ? "flex h-full w-full flex-col items-start gap-1 rounded-md border border-purple-600 bg-purple-50 px-3.5 py-3 text-left transition-shadow hover:shadow-md"
                    : "flex h-full w-full flex-col items-start gap-1 rounded-md border border-line-200 bg-card px-3.5 py-3 text-left transition-shadow hover:border-purple-300 hover:shadow-md"
                }
              >
                <span className="flex w-full items-center gap-1.5">
                  <span
                    className={
                      selected
                        ? "font-display text-sm font-bold text-purple-700 dark:text-purple-300"
                        : "font-display text-sm font-bold text-ink-900"
                    }
                  >
                    {track.name}
                  </span>
                  {selected ? <Check className="ml-auto h-4 w-4 shrink-0 text-purple-700 dark:text-purple-300" aria-hidden /> : null}
                </span>
                <span className="text-xs leading-5 text-ink-500">{track.audience}</span>
                <span className="text-xs text-ink-500">
                  {track.steps.length} steps
                </span>
              </motion.button>
            </motion.div>
          );
        })}
      </motion.div>
    </motion.section>
  );
}
