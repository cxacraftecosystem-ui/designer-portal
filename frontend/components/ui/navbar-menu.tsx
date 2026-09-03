"use client";
import React from "react";
import { motion } from "framer-motion";
import Link from "next/link";

import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";

const transition = {
  type: "spring" as const,
  mass: 0.5,
  damping: 11.5,
  stiffness: 100,
  restDelta: 0.001,
  restSpeed: 0.001
};

/**
 * The same zero-duration transition `DynamicIslandNav` uses, for the same reason.
 *
 * There is no `MotionConfig reducedMotion="user"` in this app and there deliberately is not going to
 * be one (`components/guide/steps.ts` sets out why), so every framer animation branches for itself.
 * This dropdown had no branch at all until 2026-09-03: the panel sprang open from `scale: 0.85` and
 * 10px down, and `layoutId="active"` slid the whole card sideways between triggers, for a reader who
 * had asked the application for less motion.
 */
const NO_MOTION = { duration: 0 } as const;

export const MenuItem = ({
  setActive,
  active,
  item,
  children
}: {
  setActive: (item: string) => void;
  active: string | null;
  item: string;
  children?: React.ReactNode;
}) => {
  /*
    `useAppReducedMotion()` and not framer's `useReducedMotion()`: the latter subscribes to the OS
    media query alone, and this application's second switch is the Settings toggle, which stamps
    `data-reduced-motion="true"` on `<html>` and reaches CSS but never an inline style framer wrote.
    Read here rather than passed down from `DynamicIslandNav` — this file's other export is a bare
    `Link` and there is no props channel between the two that is not one more thing to keep in step.
    Safe in this tree: the only consumer is the island nav, which `AppShell` renders inside
    `ThemeProvider`.
  */
  const reduce = useAppReducedMotion();
  const panelTransition = reduce ? NO_MOTION : transition;
  return (
    <div onMouseEnter={() => setActive(item)} className="relative">
      <motion.p
        transition={reduce ? NO_MOTION : { duration: 0.3 }}
        className="cursor-pointer text-sm font-medium text-ink-body hover:opacity-[0.9]"
      >
        {item}
      </motion.p>
      {active !== null && (
        <motion.div
          // The entrance states go entirely under reduce rather than being raced back to rest at
          // duration 0 — this is an in-app surface behind AuthProvider, so branching `initial` is
          // the guide's rule and not the hero's (§8.4 of the frontend reference).
          initial={reduce ? { opacity: 1, scale: 1, y: 0 } : { opacity: 0, scale: 0.85, y: 10 }}
          animate={{ opacity: 1, scale: 1, y: 0 }}
          transition={panelTransition}
        >
          {active === item && (
            <div className="absolute left-1/2 top-[calc(100%_+_1.2rem)] -translate-x-1/2 transform pt-4">
              <motion.div
                transition={panelTransition}
                // `layoutId` is kept under reduce: it is what makes ONE card exist across triggers
                // rather than two, and at duration 0 the projection simply lands. Removing it would
                // change the DOM identity of the panel, not merely how it travels.
                layoutId="active" // layoutId ensures smooth animation
                className="overflow-hidden rounded-2xl border border-line-200 bg-card/95 shadow-panel backdrop-blur-sm"
              >
                <motion.div
                  layout // layout ensures smooth animation
                  transition={panelTransition}
                  className="h-full w-max p-4"
                >
                  {children}
                </motion.div>
              </motion.div>
            </div>
          )}
        </motion.div>
      )}
    </div>
  );
};

export const HoveredLink = ({ children, ...rest }: React.ComponentProps<typeof Link>) => {
  return (
    <Link {...rest} className="text-sm text-ink-body transition hover:text-field-600">
      {children}
    </Link>
  );
};
