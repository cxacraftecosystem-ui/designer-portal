"use client";

/**
 * THE REVIEW LAYOUT (A18): a fixed menu of stages with completeness dots beside the read, a fixed
 * decision bar at the foot of the window that knows which stage is in view, and the "Changed since
 * round N" control (C13). Shared by the inspection, approval and supervision screens.
 *
 * - The menu is a second pane from `lg` up (the §10.3 two-pane rule) and a "Jump to stage" picker
 *   below it — a phone has no room for two panes.
 * - The stage in view is the one crossing the middle tenth of the window (the walkthrough's band),
 *   so the bar's Note and Send back preselect what the reader is looking at.
 * - The bar is a bottom dock (z-40 on the ladder) from `sm` up, with a spacer so it never covers
 *   the last stage. On a phone the same actions sit in the page's own panels.
 */

import { useEffect, useMemo, useState, type ReactNode } from "react";
import { GitCompare } from "lucide-react";

import { ActionGroup } from "@/components/ui/ActionGroup";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { Toggle } from "@/components/ui/Toggle";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import type { DwStage, DwStageCompleteness, DwStageData } from "@/lib/designWorkshops";
import { formatDate } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import {
  changesSummary,
  comparableRounds,
  getRoundSnapshot,
  roundChanges,
  STAGE_DOT_LABEL,
  stageDot,
  type DwReviewSurface,
  type DwRoundSnapshot,
  type DwRoundSnapshotRef,
  type StageChanges,
  type StageDot
} from "@/lib/reviewAids";

const DOT_CLASS: Record<StageDot, string> = {
  complete: "bg-success-600",
  partial: "bg-amber-500",
  empty: "border border-ink-300 bg-transparent"
};

/** Which stage section is crossing the middle of the window. */
export function useActiveStage(stageKeys: string[], ready: boolean): string | null {
  const [active, setActive] = useState<string | null>(null);
  const joined = stageKeys.join("|");
  useEffect(() => {
    // `ready` is "the stage sections are on the page": observing before they render observes nothing.
    if (!ready || !joined || typeof IntersectionObserver === "undefined") return;
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            const key = (entry.target as HTMLElement).dataset.stageKey;
            if (key) setActive(key);
          }
        }
      },
      { rootMargin: "-45% 0px -45% 0px" }
    );
    for (const key of joined.split("|")) {
      const node = document.getElementById(`stage-${key}`);
      if (node) observer.observe(node);
    }
    return () => observer.disconnect();
  }, [joined, ready]);
  return active;
}

export function scrollToStage(stageKey: string, reduce: boolean) {
  document.getElementById(`stage-${stageKey}`)?.scrollIntoView({
    behavior: reduce ? "auto" : "smooth",
    block: "start"
  });
}

function StageNav({
  stages,
  completeness,
  active,
  changes,
  onJump
}: {
  stages: DwStage[];
  completeness: Record<string, DwStageCompleteness> | undefined;
  active: string | null;
  changes: Record<string, StageChanges> | null;
  onJump: (key: string) => void;
}) {
  return (
    <nav aria-label="Stages" className="panel p-2">
      <ol className="grid gap-0.5">
        {stages.map((stage) => {
          const dot = stageDot(completeness?.[stage.key]);
          const moved = changes?.[stage.key]?.count ?? 0;
          const current = stage.key === active;
          return (
            <li key={stage.key}>
              <button
                type="button"
                aria-current={current ? "location" : undefined}
                onClick={() => onJump(stage.key)}
                className={`flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-xs transition ${
                  current ? "bg-purple-50 font-semibold text-purple-800 dark:bg-purple-950/50 dark:text-purple-200" : "text-ink-700 hover:bg-surface-50"
                }`}
              >
                <span aria-hidden className={`h-2 w-2 shrink-0 rounded-full ${DOT_CLASS[dot]}`} />
                <span className="min-w-0 flex-1 truncate">
                  {stage.number}. {stage.title}
                </span>
                <span className="sr-only">, {STAGE_DOT_LABEL[dot]}</span>
                {moved ? (
                  <span className="shrink-0 rounded-full bg-purple-100 px-1.5 text-[10px] font-semibold text-purple-800 dark:bg-purple-900 dark:text-purple-100">
                    {moved}
                    <span className="sr-only"> changed</span>
                  </span>
                ) : null}
              </button>
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

/** The two-pane review layout: stage menu, then the read. */
export function ReviewLayout({
  stages,
  completeness,
  active,
  changes,
  children
}: {
  stages: DwStage[];
  completeness: Record<string, DwStageCompleteness> | undefined;
  active: string | null;
  changes: Record<string, StageChanges> | null;
  children: ReactNode;
}) {
  const reduce = useAppReducedMotion();
  return (
    <div className="grid gap-4 lg:grid-cols-[15rem_minmax(0,1fr)]">
      <aside className="hidden lg:block">
        <div className="lg:sticky lg:top-24 lg:max-h-[calc(100dvh-7.5rem)] lg:overflow-y-auto lg:overscroll-contain">
          <StageNav
            active={active}
            changes={changes}
            completeness={completeness}
            onJump={(key) => scrollToStage(key, reduce)}
            stages={stages}
          />
        </div>
      </aside>
      <div className="min-w-0">
        <div className="mb-3 lg:hidden">
          <SearchableSelect
            ariaLabel="Jump to stage"
            value=""
            placeholder="Jump to stage"
            onChange={(key) => {
              if (key) scrollToStage(key, reduce);
            }}
            options={stages.map((stage) => ({
              value: stage.key,
              label: `${stage.number}. ${stage.title}`,
              hint: STAGE_DOT_LABEL[stageDot(completeness?.[stage.key])]
            }))}
            advanceOnSelect={false}
          />
        </div>
        <div className="grid gap-4">{children}</div>
      </div>
    </div>
  );
}

/** The fixed decision bar. `children` are the buttons; they act on the stage named on the left. */
export function ReviewDecisionBar({ stage, children }: { stage: DwStage | null; children: ReactNode }) {
  return (
    <>
      <div aria-hidden className="hidden h-20 sm:block" />
      <div
        className="fixed inset-x-0 bottom-0 z-40 hidden border-t border-line-200 bg-card/95 shadow-panel backdrop-blur-sm sm:block"
        role="region"
        aria-label="Decision"
      >
        <div className="mx-auto flex max-w-7xl items-center gap-3 px-4 py-3">
          <p className="min-w-0 flex-1 truncate text-xs text-ink-500">
            {stage ? (
              <>
                In view: <span className="font-medium text-ink-900">{stage.number}. {stage.title}</span>
              </>
            ) : (
              "Scroll to a stage to note it."
            )}
          </p>
          <ActionGroup align="end">{children}</ActionGroup>
        </div>
      </div>
    </>
  );
}

/** The kept copy of one earlier round, and what moved since. */
export function useRoundCompare(
  surface: DwReviewSurface,
  workshopId: string,
  stages: DwStage[],
  current: Record<string, DwStageData> | undefined,
  snapshots: DwRoundSnapshotRef[] | undefined,
  currentRound: number | null | undefined
) {
  const rounds = comparableRounds(snapshots, currentRound);
  const [round, setRound] = useState<number | null>(null);
  const [snapshot, setSnapshot] = useState<DwRoundSnapshot | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [onlyChanges, setOnlyChanges] = useState(false);

  useEffect(() => {
    if (round === null) {
      setSnapshot(null);
      setProblem(null);
      return;
    }
    let cancelled = false;
    setProblem(null);
    getRoundSnapshot(surface, workshopId, round)
      .then((answer) => {
        if (!cancelled) setSnapshot(answer);
      })
      .catch((err) => {
        if (cancelled) return;
        setSnapshot(null);
        setProblem(isUnreachable(err) ? "No connection — couldn't load that round." : "That round's copy couldn't be loaded.");
      });
    return () => {
      cancelled = true;
    };
  }, [surface, workshopId, round]);

  const changes = useMemo(
    () => (snapshot && snapshot.round === round ? roundChanges(stages, current, snapshot.stages) : null),
    [snapshot, round, stages, current]
  );

  return { rounds, round, setRound, snapshot, changes, problem, onlyChanges, setOnlyChanges };
}

/** "Compare with round N" and "Only changes". Hidden when no earlier round was kept. */
export function RoundCompareControls({ compare }: { compare: ReturnType<typeof useRoundCompare> }) {
  if (!compare.rounds.length) return null;
  return (
    <section className="panel mb-4 grid gap-3 p-4" aria-labelledby="round-compare-heading">
      <h2 id="round-compare-heading" className="flex items-center gap-2 font-display text-base font-bold tracking-tight text-ink-900">
        <GitCompare className="h-4 w-4 text-purple-700 dark:text-purple-300" aria-hidden />
        Changed since an earlier round
      </h2>
      <div className="grid gap-3 sm:grid-cols-2 sm:items-center">
        <SearchableSelect
          ariaLabel="Compare with"
          value={compare.round === null ? "" : String(compare.round)}
          onChange={(next) => compare.setRound(next ? Number(next) : null)}
          noneLabel="Don't compare"
          options={compare.rounds.map((round) => ({ value: String(round), label: `Round ${round} as handed in` }))}
          advanceOnSelect={false}
        />
        <Toggle
          label="Only show changes"
          checked={compare.onlyChanges}
          onChange={compare.setOnlyChanges}
          disabled={compare.changes === null}
        />
      </div>
      {compare.problem ? <p className="text-sm text-red-700 dark:text-red-400">{compare.problem}</p> : null}
      {compare.changes && compare.round !== null ? (
        <p className="text-sm text-ink-700" role="status">
          {changesSummary(compare.changes, compare.round)}
          {compare.snapshot?.takenAt ? ` Round ${compare.round} was handed in on ${formatDate(compare.snapshot.takenAt)}.` : ""}
        </p>
      ) : null}
    </section>
  );
}
