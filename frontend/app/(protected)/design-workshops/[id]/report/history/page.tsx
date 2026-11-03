"use client";

import { use, useCallback, useEffect, useMemo, useState } from "react";
import { FileClock, Smartphone, Server } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { EmptyState } from "@/components/EmptyState";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown } from "@/components/ui/Dropdown";
import {
  fetchDesignWorkshopReportHistory,
  fetchStageRegistry,
  listReportTemplates,
  type DwRegistry,
  type DwTemplate
} from "@/lib/designWorkshops";
import {
  diffExports,
  generationOf,
  generationsAreAbsolute,
  inGenerationOrder,
  sameFileAs,
  stagesTouchedSince,
  type DwExportRecord,
  type DwReportHistory,
  type ReportDiff,
  type StageChange
} from "@/lib/reportDiff";
import { loadDraft } from "@/lib/designWorkshopStore";
import { bytes, formatDateTime } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import { reportServerId } from "../reportTarget";

function Chip({ children, tone = "neutral" }: { children: React.ReactNode; tone?: "neutral" | "warn" | "good" }) {
  const tones = {
    neutral: "bg-field-200 text-ink-900",
    warn: "bg-amber-100 text-amber-800",
    good: "bg-success-100 text-success-600"
  } as const;
  return (
    <span className={`rounded-full px-2.5 py-1 text-xs font-medium ${tones[tone]}`}>{children}</span>
  );
}

function Checksum({ value, onDevice }: { value: string | null; onDevice: boolean }) {
  if (!value) {
    return (
      <span className="text-xs text-ink-500">
        No checksum recorded.
        {onDevice
          ? " Made by an older phone app."
          : ""}
      </span>
    );
  }
  return (
    <code className="block break-all font-mono text-[0.6875rem] leading-4 text-ink-700" data-testid="export-checksum">
      {value}
    </code>
  );
}

export default function DesignWorkshopReportHistoryPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);

  const [history, setHistory] = useState<DwReportHistory | null>(null);
  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const [templates, setTemplates] = useState<DwTemplate[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [offline, setOffline] = useState(false);
  const [localOnly, setLocalOnly] = useState(false);
  const [leftId, setLeftId] = useState("");
  const [rightId, setRightId] = useState("");

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const draft = await loadDraft(id);
      if (cancelled) return;
      const target = reportServerId(id, draft);
      if (!target) {
        setLocalOnly(true);
        return;
      }
      try {
        const [loaded, loadedRegistry] = await Promise.all([
          fetchDesignWorkshopReportHistory(target),
          fetchStageRegistry()
        ]);
        if (cancelled) return;
        setRegistry(loadedRegistry);
        setHistory(loaded);
        setError(null);

        const ordered = inGenerationOrder(loaded);
        if (ordered.length >= 2) {
          setLeftId(ordered[ordered.length - 2].id);
          setRightId(ordered[ordered.length - 1].id);
        }
      } catch (err) {
        if (cancelled) return;
        if (isUnreachable(err)) setOffline(true);
        else setError(err instanceof Error ? err.message : "Unable to load the report history");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [id]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const loaded = await listReportTemplates();
        if (!cancelled) setTemplates(loaded);
      } catch {
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [id]);

  const ordered = useMemo(() => (history ? inGenerationOrder(history) : []), [history]);
  const generationsAbsolute = useMemo(
    () => (history ? generationsAreAbsolute(history) : false),
    [history]
  );

  const templateName = useCallback(
    (templateId: string) => templates.find((t) => t.id === templateId)?.name ?? templateId,
    [templates]
  );

  const stageTitle = useCallback(
    (stageKey: string) => {
      const stage = registry?.stages.find((s) => s.key === stageKey);
      return stage ? `${stage.number}. ${stage.title}` : stageKey;
    },
    [registry]
  );

  const stageNumber = useCallback(
    (stageKey: string) => registry?.stages.find((s) => s.key === stageKey)?.number ?? 999,
    [registry]
  );

  const diff = useMemo(
    () => (history && leftId && rightId && leftId !== rightId ? diffExports(history, leftId, rightId) : null),
    [history, leftId, rightId]
  );

  const staleAfterNewest = useMemo(
    () => (history && ordered.length ? stagesTouchedSince(history, ordered[ordered.length - 1].id) : []),
    [history, ordered]
  );

  const exportOptions = useMemo(
    () =>
      history
        ? ordered
            .slice()
            .reverse()
            .map((row) => ({
              value: row.id,
              label: `Generation ${generationOf(history, row.id)} · ${formatDateTime(row.generatedAt)} · ${row.format}`
            }))
        : [],
    [history, ordered]
  );

  return (
    <>
      <PageHeader
        title="Report history"
        description="Every file generated, and what changed between them."
        icon={<FileClock className="h-5 w-5" aria-hidden />}
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}

      {localOnly ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          Not uploaded yet — reports appear here once it is.
        </div>
      ) : null}

      {offline ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          You&rsquo;re offline. Connect to see the history.
        </div>
      ) : null}

      {history === null && !error && !offline && !localOnly ? (
        <p className="text-sm text-ink-muted">Loading the history…</p>
      ) : null}

      {history && ordered.length === 0 ? (
        <EmptyState
          title="No report has been generated yet"
          body="Files you generate from the report page are listed here."
        />
      ) : null}

      {history && ordered.length > 0 ? (
        <>
          <DiffPanel
            history={history}
            diff={diff}
            ordered={ordered}
            exportOptions={exportOptions}
            leftId={leftId}
            rightId={rightId}
            onLeft={setLeftId}
            onRight={setRightId}
            stageTitle={stageTitle}
            stageNumber={stageNumber}
            templateName={templateName}
            completeness={history.completeness}
          />

          <section className="panel mb-5 p-4" data-testid="export-history">
            <h2 className="display-title text-lg">Every file generated</h2>
            <p className="mt-1 text-sm leading-6 text-ink-muted">
              Newest first. {ordered.length} file{ordered.length === 1 ? "" : "s"} recorded.
              {history.exportsTruncated ? " Only the most recent 100 are shown." : ""}
            </p>
            <ol className="mt-4 grid gap-3">
              {history.exports.map((row) => (
                <li key={row.id}>
                  <ExportCard
                    record={row}
                    generation={generationOf(history, row.id)}
                    generationIsAbsolute={generationsAbsolute}
                    windowTruncated={history.exportsTruncated}
                    templateName={templateName(row.templateId)}
                    duplicates={sameFileAs(history, row.id)
                      .map((other) => generationOf(history, other.id))
                      .filter((position) => position > 0)}
                    staleStages={row.id === ordered[ordered.length - 1].id ? staleAfterNewest.length : null}
                  />
                </li>
              ))}
            </ol>
          </section>
        </>
      ) : null}
    </>
  );
}

function ExportCard({
  record,
  generation,
  generationIsAbsolute,
  windowTruncated,
  templateName,
  duplicates,
  staleStages
}: {
  record: DwExportRecord;
  generation: number;
  generationIsAbsolute: boolean;
  windowTruncated: boolean;
  templateName: string;
  duplicates: number[];
  staleStages: number | null;
}) {
  return (
    <article className="rounded-md border border-line-200 bg-surface-50 p-3.5">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-medium text-ink-900">
          {generation > 0
            ? !generationIsAbsolute && windowTruncated
              ? `Generation ${generation} of the 100 most recent`
              : `Generation ${generation}`
            : "Undated file — it cannot be compared"}
        </span>
        <Chip>{record.format}</Chip>
        <Chip tone={record.generatedOnDevice ? "warn" : "neutral"}>
          {record.generatedOnDevice ? (
            <span className="inline-flex items-center gap-1">
              <Smartphone className="h-3 w-3" aria-hidden />
              Made on a phone, offline
            </span>
          ) : (
            <span className="inline-flex items-center gap-1">
              <Server className="h-3 w-3" aria-hidden />
              Made online
            </span>
          )}
        </Chip>
        <span className="text-sm text-ink-muted">{formatDateTime(record.generatedAt)}</span>
      </div>

      <dl className="mt-3 grid gap-x-4 gap-y-2 text-sm sm:grid-cols-2 lg:grid-cols-4">
        <div className="min-w-0">
          <dt className="field-label">Template</dt>
          <dd className="truncate text-ink-700" title={templateName}>
            {templateName}
          </dd>
        </div>
        <div className="min-w-0">
          <dt className="field-label">Pages</dt>
          <dd className="text-ink-700">{record.pageCount ?? "Not recorded"}</dd>
        </div>
        <div className="min-w-0">
          <dt className="field-label">Size</dt>
          <dd className="text-ink-700">{bytes(record.fileSizeBytes)}</dd>
        </div>
        <div className="min-w-0">
          <dt className="field-label">Generated by</dt>
          <dd className="truncate text-ink-700" title={record.generatedByName ?? undefined}>
            {record.generatedByName ?? "Account no longer exists"}
          </dd>
        </div>
      </dl>

      <div className="mt-3">
        <span className="field-label">SHA-256 of the file</span>
        <Checksum value={record.checksumSha256} onDevice={record.generatedOnDevice} />
      </div>

      <p className="mt-2 text-xs leading-5 text-ink-500">
        File name: <span className="break-all">{record.fileName}</span>
      </p>

      {duplicates.length ? (
        <p className="mt-2 rounded-md bg-amber-100 px-2.5 py-1.5 text-xs leading-5 text-amber-800">
          Byte-for-byte the same file as generation {duplicates.join(", ")}. The same document was recorded more than
          once — worth checking, if one of them was meant to be a revision.
        </p>
      ) : null}

      {staleStages ? (
        <p className="mt-2 rounded-md bg-amber-100 px-2.5 py-1.5 text-xs leading-5 text-amber-800">
          {staleStages} stage{staleStages === 1 ? "" : "s"} changed since this file was made.
        </p>
      ) : null}

      {record.warnings ? (
        <details className="mt-2">
          <summary className="cursor-pointer text-xs font-medium text-ink-700">
            Warnings recorded when this file was made
          </summary>
          <p className="mt-1 whitespace-pre-line text-xs leading-5 text-ink-500">{record.warnings}</p>
        </details>
      ) : null}
    </article>
  );
}

function DiffPanel({
  history,
  diff,
  ordered,
  exportOptions,
  leftId,
  rightId,
  onLeft,
  onRight,
  stageTitle,
  stageNumber,
  templateName,
  completeness
}: {
  history: DwReportHistory;
  diff: ReportDiff | null;
  ordered: DwExportRecord[];
  exportOptions: { value: string; label: string }[];
  leftId: string;
  rightId: string;
  onLeft: (value: string) => void;
  onRight: (value: string) => void;
  stageTitle: (key: string) => string;
  stageNumber: (key: string) => number;
  templateName: (id: string) => string;
  completeness: DwReportHistory["completeness"];
}) {
  const touched = useMemo(
    () =>
      (diff ? diff.touchedStageKeys : [])
        .map((key) => diff!.byStage[key])
        .sort((a, b) => stageNumber(a.stageKey) - stageNumber(b.stageKey)),
    [diff, stageNumber]
  );
  const untouched = useMemo(
    () =>
      (diff ? diff.untouchedStageKeys : [])
        .map((key) => diff!.byStage[key])
        .sort((a, b) => stageNumber(a.stageKey) - stageNumber(b.stageKey)),
    [diff, stageNumber]
  );

  return (
    <section className="panel mb-5 p-4" data-testid="report-diff">
      <h2 className="display-title text-lg">What changed between two files</h2>

      {ordered.length < 2 ? (
        <p className="mt-1 text-sm leading-6 text-ink-muted">
          Only one file so far — nothing to compare.
        </p>
      ) : (
        <>
          <div className="mt-3 grid gap-3 sm:grid-cols-2">
            <FieldBlock label="Compare">
              <Dropdown
                value={leftId}
                onChange={onLeft}
                options={exportOptions}
                ariaLabel="Earlier report"
                advanceOnSelect={false}
                searchable
              />
            </FieldBlock>
            <FieldBlock label="With">
              <Dropdown
                value={rightId}
                onChange={onRight}
                options={exportOptions}
                ariaLabel="Later report"
                advanceOnSelect={false}
                searchable
              />
            </FieldBlock>
          </div>

          {leftId === rightId ? (
            <p className="mt-3 text-sm text-ink-muted">Choose two different files.</p>
          ) : null}

          {diff ? (
            <>
              <p className="mt-4 text-sm leading-6 text-ink-700" data-testid="diff-summary">
                <strong className="text-ink-900">
                  Generation {diff.earlierGeneration} → generation {diff.laterGeneration}
                  {history.exportsTruncated && !generationsAreAbsolute(history)
                    ? " (of the 100 most recent)"
                    : ""}
                </strong>
                {": "}
                {touched.length === 0
                  ? "no stage changed."
                  : `${touched.length} stage${touched.length === 1 ? "" : "s"} changed.`}{" "}
                {diff.timelineComplete && untouched.length
                  ? `${untouched.length} unchanged.`
                  : null}
              </p>

              <FileFacts diff={diff} templateName={templateName} />

              {diff.headerRowWritten === false ? (
                <p className="mt-3 text-sm leading-6 text-ink-700" data-testid="header-verdict">
                  Cover details are unchanged.
                </p>
              ) : null}
              {diff.headerRowWritten === true ? (
                <p className="mt-3 text-sm leading-6 text-ink-500" data-testid="header-verdict">
                  Details were saved in between; cover details may not have changed.
                </p>
              ) : null}

              {touched.length ? (
                <ul className="mt-4 grid gap-2" data-testid="touched-stages">
                  {touched.map((stage) => (
                    <li
                      key={stage.stageKey}
                      className="flex flex-wrap items-center gap-2 rounded-md border border-line-200 bg-card px-3 py-2 text-sm"
                    >
                      <span className="font-medium text-ink-900">{stageTitle(stage.stageKey)}</span>
                      {stage.rowsAdded ? <Chip tone="good">{stage.rowsAdded} added</Chip> : null}
                      {stage.rowsRewritten ? <Chip tone="warn">{stage.rowsRewritten} rewritten</Chip> : null}
                      {stage.rowsRemoved ? <Chip tone="warn">{stage.rowsRemoved} removed</Chip> : null}
                      {stage.rowsTransient ? (
                        <span className="text-xs text-ink-500">
                          {stage.rowsTransient} {stage.rowsTransient === 1 ? "entry" : "entries"} added and removed in between
                        </span>
                      ) : null}
                    </li>
                  ))}
                </ul>
              ) : null}

              {touched.length ? (
                <p className="mt-2 text-xs leading-5 text-ink-500">
                  &ldquo;Rewritten&rdquo; counts entries saved, not answers changed.
                </p>
              ) : null}

              {untouched.length ? (
                <div className="mt-4">
                  <h3 className="text-sm font-medium text-ink-900">
                    {diff.timelineComplete
                      ? "Identical in both files"
                      : "Nothing written (history partly checked)"}
                  </h3>
                  <ul className="mt-2 grid gap-1.5" data-testid="untouched-stages">
                    {untouched.map((stage) => (
                      <li key={stage.stageKey} className="flex flex-wrap items-baseline gap-x-2 text-sm text-ink-700">
                        <span>{stageTitle(stage.stageKey)}</span>
                        <StageCompleteness stage={stage} completeness={completeness} />
                      </li>
                    ))}
                  </ul>
                </div>
              ) : null}

              <Limits diff={diff} history={history} />
            </>
          ) : null}
        </>
      )}
    </section>
  );
}

function StageCompleteness({
  stage,
  completeness
}: {
  stage: StageChange;
  completeness: DwReportHistory["completeness"];
}) {
  const score = completeness?.[stage.stageKey];
  if (!score || !stage.currentReflectsBoth) return null;
  return (
    <span className="text-xs text-ink-500">
      {score.percent}% of required fields
    </span>
  );
}

function FileFacts({ diff, templateName }: { diff: ReportDiff; templateName: (id: string) => string }) {
  const facts: React.ReactNode[] = [];

  if (diff.identicalFile) {
    facts.push(
      <>
        <strong className="text-ink-900">The two files are identical.</strong>
      </>
    );
  } else if (diff.checksumComparable) {
    facts.push(<>The two files differ.</>);
  } else {
    facts.push(<>One file has no checksum, so contents can&rsquo;t be compared.</>);
  }

  if (diff.formatDiffers) {
    facts.push(
      <>
        One is a {diff.earlier.format} and the other a {diff.later.format}. Two formats of the same record are always
        different files.
      </>
    );
  }
  if (diff.templateChanged) {
    facts.push(
      <>
        The template changed: {templateName(diff.earlier.templateId)} → {templateName(diff.later.templateId)}. A
        template decides which stages a report contains, so the documents can differ even where the data did not.
      </>
    );
  }
  if (diff.schemaVersionChanged) {
    facts.push(
      <>
        The form changed between these files, so fields may differ.
      </>
    );
  }
  if (diff.pageDelta !== null && diff.pageDelta !== 0) {
    facts.push(
      <>
        {Math.abs(diff.pageDelta)} page{Math.abs(diff.pageDelta) === 1 ? "" : "s"}{" "}
        {diff.pageDelta > 0 ? "longer" : "shorter"} ({diff.earlier.pageCount} → {diff.later.pageCount}).
      </>
    );
  }
  if (diff.sizeDelta !== null && diff.sizeDelta !== 0) {
    facts.push(
      <>
        {bytes(Math.abs(diff.sizeDelta))} {diff.sizeDelta > 0 ? "larger" : "smaller"} ({bytes(diff.earlier.fileSizeBytes)}{" "}
        → {bytes(diff.later.fileSizeBytes)}).
      </>
    );
  }

  return (
    <ul className="mt-3 grid gap-1.5 text-sm leading-6 text-ink-700" data-testid="file-facts">
      {facts.map((fact, index) => (
        <li key={index}>{fact}</li>
      ))}
    </ul>
  );
}

function Limits({ diff, history }: { diff: ReportDiff; history: DwReportHistory }) {
  return (
    <div className="mt-5 rounded-md bg-surface-50 p-3.5">
      <h3 className="text-sm font-medium text-ink-900">What this comparison doesn&rsquo;t show</h3>
      <ul className="mt-2 grid list-disc gap-1.5 pl-5 text-xs leading-5 text-ink-500">
        <li>
          <strong className="text-ink-700">Which field changed.</strong>
        </li>
        <li>
          <strong className="text-ink-700">Whether a rewritten entry&apos;s answers differ.</strong>
        </li>
        <li>
          Repeat saves count once.
        </li>
        <li>
          Changes to linked records (artisans, products, photos).
        </li>
        {diff.deviceClockInvolved ? (
          <li>
            <strong className="text-ink-700">Exact timing</strong> — one file used an offline phone&apos;s clock.
          </li>
        ) : null}
        {!diff.timelineComplete ? (
          <li>
            <strong className="text-ink-700">Full history</strong> — only part could be checked.
          </li>
        ) : null}
        {history.exportsTruncated ? (
          <li>
            {generationsAreAbsolute(history)
              ? "Files older than the latest 100."
              : "Files older than the latest 100 (numbering counts only these)."}
          </li>
        ) : null}
      </ul>
    </div>
  );
}
