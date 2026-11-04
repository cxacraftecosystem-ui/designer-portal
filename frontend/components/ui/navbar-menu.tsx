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
          initial={reduce ? { opacity: 1, scale: 1, y: 0 } : { opacity: 0, scale: 0.85, y: 10 }}
          animate={{ opacity: 1, scale: 1, y: 0 }}
          transition={panelTransition}
        >
          {active === item && (
            <div className="absolute left-1/2 top-[calc(100%+1.2rem)] -translate-x-1/2 transform pt-4">
              <motion.div
                transition={panelTransition}
                layoutId="active"
                className="overflow-hidden rounded-2xl border border-line-200 bg-card/95 shadow-panel backdrop-blur-xs"
              >
                <motion.div
                  layout
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
