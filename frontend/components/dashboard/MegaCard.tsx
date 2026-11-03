"use client";

import { AnimatePresence, motion } from "framer-motion";
import { ChevronDown } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { useId } from "react";

import { layoutSpring, springy } from "@/components/guide/guideMotion";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";

export type MegaTone = "purple" | "archive" | "errand" | "steward" | "mango";

const TONE_CHIP: Record<MegaTone, string> = {
  purple: "bg-purple-800 text-white",
  archive: "bg-archive-100 text-archive-700 dark:bg-archive-950/40 dark:text-archive-300",
  errand: "bg-errand-100 text-errand-700 dark:bg-errand-950/40 dark:text-errand-300",
  steward: "bg-steward-100 text-steward-700 dark:bg-steward-950/40 dark:text-steward-300",
  mango: "bg-mango-100 text-mango-700 dark:bg-mango-950/40 dark:text-mango-300"
};

const TONE_BORDER: Record<MegaTone, string> = {
  purple: "hover:border-purple-300",
  archive: "hover:border-archive-300",
  errand: "hover:border-errand-300",
  steward: "hover:border-steward-300",
  mango: "hover:border-mango-300"
};

export function MegaCard({
  title,
  note,
  icon: Icon,
  tone,
  count,
  countLabel,
  expanded,
  onToggle,
  span = "half",
  headerRight,
  children
}: {
  title: string;
  note: string;
  icon: LucideIcon;
  tone: MegaTone;
  count: number;
  countLabel: string;
  expanded: boolean;
  onToggle: () => void;
  span?: "half" | "full";
  headerRight?: React.ReactNode;
  children: React.ReactNode;
}) {
  const reduce = useAppReducedMotion();
  const panelId = useId();
  const headingId = useId();

  return (
    <section
      aria-labelledby={headingId}
      className={`panel h-fit transition-[border-color,box-shadow] hover:shadow-md ${TONE_BORDER[tone]} ${
        span === "full" ? "lg:col-span-2" : ""
      }`}
    >
      <div className="flex items-start gap-3">
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={expanded}
        aria-controls={expanded ? panelId : undefined}
        className="flex min-w-0 flex-1 items-start gap-3 p-4 text-left"
      >
        <span aria-hidden className={`grid h-10 w-10 shrink-0 place-items-center rounded-md ${TONE_CHIP[tone]}`}>
          <Icon className="h-5 w-5" aria-hidden />
        </span>

        <span className="min-w-0 flex-1">
          <span id={headingId} className="block font-display text-lg font-bold leading-tight text-ink-900">
            {title}
          </span>
          <span className="mt-1 block text-xs leading-5 text-ink-500">{note}</span>
          <span className="mt-1.5 block text-xs font-medium text-ink-700">
            {count} {countLabel}
            {count === 1 ? "" : "s"}
          </span>
        </span>

        <span
          aria-hidden
          className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-full border border-line-200 text-ink-500"
        >
          <motion.span
            initial={false}
            animate={{ rotate: expanded ? 180 : 0 }}
            transition={springy(reduce)}
            className="grid place-items-center"
          >
            <ChevronDown className="h-4 w-4" aria-hidden />
          </motion.span>
        </span>
      </button>
      {headerRight ? <div className="shrink-0 p-4 pl-0">{headerRight}</div> : null}
      </div>

      <AnimatePresence initial={false}>
        {expanded ? (
          <motion.div
            key="panel"
            id={panelId}
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: "auto", opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={reduce ? { duration: 0 } : { height: layoutSpring(false), opacity: { duration: 0.18 } }}
            className="overflow-hidden rounded-b-lg"
          >
            <div className="border-t border-line-200 p-4">{children}</div>
          </motion.div>
        ) : null}
      </AnimatePresence>
    </section>
  );
}
