"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { motion } from "framer-motion";
import { ArrowUpRight, Check, ChevronDown, ChevronRight } from "lucide-react";

import {
  AWAITING_APPROVAL_BADGE_HREF,
  AWAITING_SANCTION_BADGE_CLASS,
  AWAITING_SANCTION_BADGE_HREF,
  awaitingApprovalSentence,
  AWAITING_INSPECTION_BADGE_HREF,
  awaitingInspectionSentence,
  awaitingSanctionSentence,
  ministryDeskFor,
  ministryDeskSummary,
  readMinistryDeskExpanded,
  writeMinistryDeskExpanded
} from "@/components/dashboard/ministryDesk";
import { useAuth } from "@/components/AuthProvider";
import { riseItem, staggerParent } from "@/components/guide/guideMotion";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { useAwaitingApprovalCount } from "@/components/hooks/useAwaitingApprovalCount";
import { useAwaitingInspectionCount } from "@/components/hooks/useAwaitingInspectionCount";
import { useAwaitingSanctionCount } from "@/components/hooks/useAwaitingSanctionCount";
import { canSeeMinistryDesk } from "@/lib/permissions";

export function MinistryDeskCard() {
  const { user } = useAuth();

  const audience = canSeeMinistryDesk(user);
  const open = audience ? ministryDeskFor(user) : [];

  const awaitingSanction = useAwaitingSanctionCount(
    open.some((destination) => destination.href === AWAITING_SANCTION_BADGE_HREF)
  );
  const awaitingApproval = useAwaitingApprovalCount(
    open.some((destination) => destination.href === AWAITING_APPROVAL_BADGE_HREF)
  );
  const awaitingCount = awaitingSanction ?? 0;
  const awaitingApprovalCount = awaitingApproval ?? 0;
  const awaitingInspection = useAwaitingInspectionCount(
    open.some((destination) => destination.href === AWAITING_INSPECTION_BADGE_HREF)
  );
  const awaitingInspectionCount = awaitingInspection ?? 0;

  const [expanded, setExpanded] = useState(false);
  const [reveal, setReveal] = useState(0);
  const reduce = useAppReducedMotion();
  useEffect(() => {
    let stored = false;
    try {
      stored = readMinistryDeskExpanded(window.localStorage);
    } catch {
    }
    if (stored) setExpanded(true);
  }, []);
  const toggle = () => {
    const next = !expanded;
    setExpanded(next);
    if (next) setReveal((count) => count + 1);
    try {
      writeMinistryDeskExpanded(window.localStorage, next);
    } catch {
    }
  };

  if (!audience) return null;

  if (open.length === 0) return null;

  const summary = ministryDeskSummary(open, { awaitingSanction, awaitingApproval, awaitingInspection });
  const folded = !expanded;

  return (
    <section
      aria-labelledby="ministry-desk-heading"
      className="panel mb-6 p-4 sm:p-5"
      data-surface="ministry"
    >
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <h2 id="ministry-desk-heading" className="font-display text-lg font-bold text-ink-900">
          Your ministry desk
        </h2>
        <p className={folded ? "text-xs text-ink-500 max-sm:hidden" : "text-xs text-ink-500"}>
          Your screens, in the order a workshop reaches them.
        </p>
        <p className={folded ? "text-xs tabular-nums text-ink-500 sm:hidden" : "hidden"}>
          {open.length} {open.length === 1 ? "screen" : "screens"}
        </p>
      </div>

      {folded ? (
        <div className="mt-3 min-h-12 sm:hidden">
          {summary.kind === "waiting" ? (
            <ul className="grid gap-2">
              {summary.counts.map((item) => (
                <li key={item.href}>
                  <Link
                    href={item.href}
                    aria-label={item.sentence}
                    className="flex min-h-12 items-center gap-3 rounded-md border border-line-200 bg-surface-50 px-3 py-2 transition-colors active:bg-field-100"
                  >
                    <span
                      aria-hidden
                      className="grid h-8 min-w-8 shrink-0 place-items-center rounded-md bg-amber-100 px-1.5 font-display text-base font-bold tabular-nums text-amber-800"
                    >
                      {item.count}
                    </span>
                    <span aria-hidden className="min-w-0 flex-1 text-sm font-medium text-ink-900">
                      {item.label}
                    </span>
                    <ChevronRight aria-hidden className="h-4 w-4 shrink-0 text-ink-300" />
                  </Link>
                </li>
              ))}
            </ul>
          ) : summary.kind === "clear" ? (
            <p className="flex min-h-12 items-center gap-2 text-sm text-ink-500">
              <Check aria-hidden className="h-4 w-4 shrink-0 text-success-600 dark:text-emerald-400" />
              Nothing waiting.
            </p>
          ) : null}
        </div>
      ) : null}

      <motion.div
        key={reveal}
        id="ministry-desk-screens"
        variants={staggerParent(reduce, 0.035)}
        initial={reveal > 0 ? "hidden" : false}
        animate="show"
        className={
          folded
            ? "mt-4 grid gap-2.5 max-sm:hidden sm:grid-cols-2 lg:grid-cols-3"
            : "mt-4 grid gap-2.5 sm:grid-cols-2 lg:grid-cols-3"
        }
      >
        {open.map((destination) => (
          <motion.div key={destination.href} variants={riseItem(reduce, 10)} className="min-w-0">
            <Link
              href={destination.href}
              className="flex h-full items-start gap-3 rounded-md border border-line-200 bg-card p-3.5 transition-shadow pointer-fine:hover:border-ministry-300 pointer-fine:hover:shadow-md focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-purple-700"
            >
              <span
                aria-hidden
                className="grid h-9 w-9 shrink-0 place-items-center rounded-md bg-ministry-50 text-ministry-700 dark:bg-ministry-950/40 dark:text-ministry-300"
              >
                <destination.icon className="h-[18px] w-[18px]" aria-hidden />
              </span>
              <span className="min-w-0 flex-1">
                <span className="flex items-center gap-1 font-display text-sm font-bold text-ink-900">
                  {destination.label}
                  <ArrowUpRight className="h-3.5 w-3.5 shrink-0 text-ink-300" aria-hidden />
                  {destination.href === AWAITING_SANCTION_BADGE_HREF && awaitingCount > 0 ? (
                    <span
                      title={awaitingSanctionSentence(awaitingCount)}
                      className={`ml-auto ${AWAITING_SANCTION_BADGE_CLASS}`}
                    >
                      <span aria-hidden>{awaitingCount}</span>
                      <span aria-hidden className="font-medium">
                        awaiting
                      </span>
                      <span className="sr-only">{awaitingSanctionSentence(awaitingCount)}</span>
                    </span>
                  ) : null}
                  {destination.href === AWAITING_APPROVAL_BADGE_HREF && awaitingApprovalCount > 0 ? (
                    <span
                      title={awaitingApprovalSentence(awaitingApprovalCount)}
                      className={`ml-auto ${AWAITING_SANCTION_BADGE_CLASS}`}
                    >
                      <span aria-hidden>{awaitingApprovalCount}</span>
                      <span aria-hidden className="font-medium">
                        waiting
                      </span>
                      <span className="sr-only">{awaitingApprovalSentence(awaitingApprovalCount)}</span>
                    </span>
                  ) : null}
                  {destination.href === AWAITING_INSPECTION_BADGE_HREF && awaitingInspectionCount > 0 ? (
                    <span
                      title={awaitingInspectionSentence(awaitingInspectionCount)}
                      className={`ml-auto ${AWAITING_SANCTION_BADGE_CLASS}`}
                    >
                      <span aria-hidden>{awaitingInspectionCount}</span>
                      <span aria-hidden className="font-medium">
                        to review
                      </span>
                      <span className="sr-only">{awaitingInspectionSentence(awaitingInspectionCount)}</span>
                    </span>
                  ) : null}
                </span>
                <span className="mt-0.5 block text-xs leading-5 text-ink-500">{destination.note}</span>
              </span>
            </Link>
          </motion.div>
        ))}
      </motion.div>

      <ActionGroup fill className="mt-3 sm:hidden">
        <button
          type="button"
          aria-expanded={expanded}
          aria-controls="ministry-desk-screens"
          onClick={toggle}
          className="field-button-secondary min-h-11 w-full"
        >
          {expanded ? "Show less" : "Show all"}
          <ChevronDown
            aria-hidden
            className={expanded ? "h-4 w-4 rotate-180 transition-transform" : "h-4 w-4 transition-transform"}
          />
        </button>
      </ActionGroup>

      <p
        className={
          folded
            ? "mt-4 border-t border-line-200 pt-3 text-xs leading-5 text-ink-500 max-sm:hidden"
            : "mt-4 border-t border-line-200 pt-3 text-xs leading-5 text-ink-500"
        }
      >
        Not sure which of these is yours?{" "}
        <Link
          href="/guide"
          className="font-medium text-ministry-700 underline-offset-2 hover:underline dark:text-ministry-300"
        >
          Open the walkthrough
        </Link>{" "}
        to see which post each screen is for. Approving a report is the Ministry Admin&apos;s.
      </p>
    </section>
  );
}
