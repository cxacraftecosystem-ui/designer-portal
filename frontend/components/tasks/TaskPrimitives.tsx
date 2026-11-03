"use client";

import { ProgressBar } from "@/components/ui/ProgressBar";
import { useId } from "react";
import {
  Boxes,
  CalendarClock,
  CheckCircle2,
  ClipboardList,
  HelpCircle,
  Minus,
  TriangleAlert,
  type LucideIcon
} from "lucide-react";

import { progressGap } from "@/components/tasks/scope";
import { FieldLabelProvider } from "@/components/ui/fieldLabel";
import type { TaskArtisanRef, TaskSectionRef, TaskStatus, TaskUserBrief } from "@/components/tasks/types";
import { formatDate } from "@/lib/format";
import { roleLabel } from "@/lib/permissions";
import { cn } from "@/lib/utils";
import { RequiredMark } from "@/components/ui/RequiredMark";

const CHIP = "inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium";

export function FieldBlock({
  label,
  required,
  hint,
  children,
  fieldKey
}: {
  label: string;
  required?: boolean;
  hint?: React.ReactNode;
  children: React.ReactNode;
  fieldKey?: string;
}) {
  const id = useId();
  return (
    <div className="grid gap-1" data-field={fieldKey}>
      <span id={id} className="field-label">
        {label}
        <RequiredMark when={required} />
      </span>
      <div role="group" aria-labelledby={id}>
        <FieldLabelProvider value={id}>{children}</FieldLabelProvider>
      </div>
      {hint}
    </div>
  );
}

const STATUS_TONE: Record<TaskStatus, string> = {
  OPEN: "border-line-200 bg-surface-50 text-ink-500",
  IN_PROGRESS: "border-amber-500/30 bg-amber-100 text-amber-800",
  DONE: "border-success-600/25 bg-success-100 text-success-600",
  CANCELLED: "border-error-600/25 bg-error-100 text-red-800"
};

const STATUS_TEXT: Record<TaskStatus, string> = {
  OPEN: "Open",
  IN_PROGRESS: "In progress",
  DONE: "Done",
  CANCELLED: "Cancelled"
};

export function TaskStatusPill({ status }: { status: TaskStatus }) {
  return <span className={cn(CHIP, STATUS_TONE[status] ?? STATUS_TONE.OPEN)}>{STATUS_TEXT[status] ?? status}</span>;
}

export function TaskKindBadge({ recordTypes, sections }: { recordTypes: string[]; sections: TaskSectionRef[] }) {
  const hasRecords = (recordTypes ?? []).length > 0;
  const hasSections = (sections ?? []).length > 0;
  if (!hasRecords && !hasSections) return null;
  const both = hasRecords && hasSections;
  const Icon: LucideIcon = hasSections && !hasRecords ? ClipboardList : Boxes;
  const text = both ? "Records + questionnaire" : hasSections ? "Questionnaire sections" : "Record documentation";
  return (
    <span className={cn(CHIP, "border-purple-200 bg-purple-50 text-purple-700")}>
      <Icon className="h-3.5 w-3.5" aria-hidden />
      {text}
    </span>
  );
}

export function DueBadge({ dueAt, overdue }: { dueAt?: string | null; overdue?: boolean }) {
  if (!dueAt) return null;
  return (
    <span className={cn("inline-flex items-center gap-1.5 text-xs", overdue ? "font-semibold text-error-600" : "text-ink-500")}>
      <CalendarClock className="h-3.5 w-3.5" aria-hidden />
      Due {formatDate(dueAt)}
      {overdue ? " — overdue" : ""}
    </span>
  );
}

export function PersonLine({ user, className }: { user?: TaskUserBrief | null; className?: string }) {
  if (!user) return <span className={cn("text-sm text-ink-500", className)}>Unknown user</span>;
  return (
    <span className={cn("inline-flex flex-wrap items-baseline gap-x-2", className)}>
      <span className="font-medium text-ink-900">{user.name}</span>
      <span className="text-xs text-ink-500">{user.roleLabel ?? roleLabel(user.role)}</span>
    </span>
  );
}

export function ScopeChips({
  recordTypeLabels,
  sections,
  artisans,
  targetCount,
  workshopTitle,
  maxArtisans = 4
}: {
  recordTypeLabels: string[];
  sections: TaskSectionRef[];
  artisans: TaskArtisanRef[];
  targetCount?: number | null;
  workshopTitle?: string | null;
  maxArtisans?: number;
}) {
  const shownArtisans = artisans.slice(0, maxArtisans);
  const hiddenArtisans = artisans.length - shownArtisans.length;
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {workshopTitle ? <span className={cn(CHIP, "border-line-200 bg-surface-50 text-ink-700")}>{workshopTitle}</span> : null}
      {(recordTypeLabels ?? []).map((label) => (
        <span key={label} className={cn(CHIP, "border-purple-200 bg-purple-50 capitalize text-purple-700")}>
          {label}
        </span>
      ))}
      {targetCount ? (
        <span className={cn(CHIP, "border-purple-200 bg-purple-50 text-purple-700")}>Target {targetCount}</span>
      ) : null}
      {(sections ?? []).map((section) => (
        <span key={section.id} title={section.title} className={cn(CHIP, "border-line-200 bg-card text-ink-700")}>
          <ClipboardList className="h-3.5 w-3.5 text-purple-700 dark:text-purple-300" aria-hidden />
          Section {section.code}
        </span>
      ))}
      {shownArtisans.map((artisan) => (
        <span key={artisan.id} className={cn(CHIP, "border-line-200 bg-card text-ink-700")}>
          {artisan.name}
        </span>
      ))}
      {hiddenArtisans > 0 ? (
        <span className={cn(CHIP, "border-line-200 bg-card text-ink-500")}>+{hiddenArtisans} more artisans</span>
      ) : null}
      {artisans.length === 0 ? (
        <span className={cn(CHIP, "border-dashed border-line-200 bg-card text-ink-500")}>All artisans in scope</span>
      ) : null}
    </div>
  );
}

const GAP_TONE = {
  match: { className: "border-success-600/25 bg-success-100 text-success-600", Icon: CheckCircle2 },
  ahead: { className: "border-success-600/25 bg-success-100 text-success-600", Icon: CheckCircle2 },
  behind: { className: "border-amber-500/30 bg-amber-100 text-amber-800", Icon: TriangleAlert },
  idle: { className: "border-line-200 bg-surface-50 text-ink-500", Icon: Minus },
  unknown: { className: "border-line-200 bg-surface-50 text-ink-500", Icon: HelpCircle }
} as const;

export function GapChip({ reported, derived }: { reported: number; derived: number | null | undefined }) {
  const gap = progressGap(reported, derived);
  const tone = GAP_TONE[gap.tone];
  return (
    <span className={cn(CHIP, "w-fit max-w-full", tone.className)}>
      <tone.Icon className="h-3.5 w-3.5 shrink-0" aria-hidden />
      <span className="min-w-0 truncate">{gap.label}</span>
    </span>
  );
}

function Bar({ value, denominator, className }: { value: number; denominator: number; className?: string }) {
  const pct = denominator > 0 ? Math.min(100, Math.round((100 * value) / denominator)) : 0;
  return (
    <ProgressBar percent={pct} className="h-2" fillClassName={cn("transition-all", className)} />
  );
}

export function ProgressGapMeter({
  reported,
  derived,
  target,
  className
}: {
  reported: number;
  derived: number | null | undefined;
  target?: number | null;
  className?: string;
}) {
  const hasTarget = !!target && target > 0;
  const denominator = hasTarget ? (target as number) : Math.max(reported, derived ?? 0, 1);
  const suffix = hasTarget ? ` / ${target}` : "";
  return (
    <div className={cn("grid gap-2", className)}>
      <div className="grid grid-cols-[6.5rem_1fr_auto] items-center gap-x-3 gap-y-2">
        <span className="text-xs text-ink-500">Reported</span>
        <Bar value={reported} denominator={denominator} />
        <span className="text-xs font-semibold tabular-nums text-ink-900">
          {reported}
          {suffix}
        </span>
        <span className="text-xs text-ink-500">In repository</span>
        {derived === null || derived === undefined ? (
          <span className="text-xs text-ink-500">not counted for this page</span>
        ) : (
          <Bar value={derived} denominator={denominator} className="bg-ink-500" />
        )}
        <span className="text-xs font-semibold tabular-nums text-ink-900">
          {derived ?? "—"}
          {derived === null || derived === undefined ? "" : suffix}
        </span>
      </div>
      <GapChip reported={reported} derived={derived} />
      {!hasTarget ? (
        <p className="text-xs text-ink-500">No target count on this task — the bars compare the two figures to each other.</p>
      ) : null}
    </div>
  );
}

export function StatTile({
  label,
  value,
  hint,
  tone = "neutral"
}: {
  label: string;
  value: number | string;
  hint?: string;
  tone?: "neutral" | "warn" | "good";
}) {
  const valueTone =
    tone === "warn" && value !== 0 ? "text-error-600" : tone === "good" && value !== 0 ? "text-success-600" : "text-ink-900";
  return (
    <div className="rounded-md border border-line-200 bg-card px-4 py-3">
      <div className={cn("font-display text-2xl font-bold tabular-nums", valueTone)}>{value}</div>
      <div className="mt-0.5 text-xs font-medium text-ink-500">{label}</div>
      {hint ? <div className="mt-0.5 text-xs text-ink-500">{hint}</div> : null}
    </div>
  );
}

export function PercentBar({ percent, label }: { percent: number; label?: string }) {
  const clamped = Math.max(0, Math.min(100, Math.round(percent)));
  return (
    <div className="grid gap-1">
      <div className="flex items-baseline justify-between gap-3">
        <span className="text-xs text-ink-500">{label ?? "Progress"}</span>
        <span className="text-xs font-semibold tabular-nums text-ink-900">{clamped}%</span>
      </div>
      <Bar value={clamped} denominator={100} />
    </div>
  );
}
