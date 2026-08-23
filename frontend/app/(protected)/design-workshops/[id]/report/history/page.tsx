"use client";

/**
 * Report history, and a diff between two generated reports.
 *
 * WHY THIS SCREEN EXISTS. A report submitted to a ministry comes back for revision three or four
 * times. Every one of those files was already recorded — `DwReportExport` has carried the checksum,
 * the size, the page count, the template, the registry version and the timestamp of each, INCLUDING
 * the ones a phone produced with no network — and none of it was on screen anywhere. So a
 * reviewer's "did you update the cost sheet before you resubmitted?" had no answer: four files
 * existed, and nothing said what was different about them.
 *
 * A SIBLING OF THE REPORT PAGE, NOT PART OF IT. `../page.tsx` is about producing the NEXT file:
 * template, colour, transcripts, preview, download. This is about the files already produced. They
 * are read at different moments by people asking different questions, and merging them would bury
 * the record of four submissions under the controls for making a fifth.
 *
 * WHAT THE DIFF CAN AND CANNOT SAY IS THE SUBSTANCE OF THE FEATURE, and it is stated on screen
 * rather than left for a reader to infer. No snapshot of the stage data is kept at export time, so
 * `lib/reportDiff.ts` works from the only evidence there is — every stage row's createdAt /
 * updatedAt / deletedAt. That supports a genuinely strong claim in one direction ("no row of the
 * costing stage was written to between these two files, so both carry identical data — provably,
 * not probably") and refuses the other ("stage 15 was written to", and NOT which field, because
 * nothing stored can say). Read that file's header before changing anything here; the temptation
 * this screen has to resist is inventing a field-level diff, which would be a confident answer to
 * the exact question a ministry reviewer is asking.
 *
 * THE VERB IS "WRITTEN", NEVER "CHANGED", and that is not fussiness. A stage is saved whole:
 * `save_stage` updates every row the payload names without comparing it to what is stored, so
 * correcting one word stamps the entire stage. Only the negative direction — nothing was written —
 * supports the word "identical".
 *
 * COMPLETENESS IS SHOWN ONLY WHERE IT IS TRUE OF BOTH FILES. Today's percentage is today's. It is
 * printed against a past export only for stages proven untouched since that export — where today's
 * figure IS that file's figure — and withheld everywhere else. That is the same discipline
 * `market_analysis.py` follows when it declines to quantile four observations: a number that cannot
 * be supported is worse than a blank, because the number is what gets quoted.
 *
 * IT NEEDS A CONNECTION, and says so instead of failing at the click. The export table records
 * files made on other devices by other people, so unlike a stage form it cannot be served from this
 * browser's draft. What is offline-shaped about it is the COMPARING: the history arrives in one
 * request and every pair a designer flips between afterwards is computed here, with no further
 * round trip.
 *
 * NOTHING ON THIS SCREEN WRITES. There is no control that edits or removes an export row — the
 * checksum is what makes the record evidence, and evidence that can be tidied up is not evidence.
 */

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
// Same rule as the report page beside this, from the same module: the route param may be a local
// draft id and `GET /report/history` only knows server ids.
import { reportServerId } from "../reportTarget";

/* ────────────────────────────────────────────────────────────────────────────
 * Small presentational pieces
 * ──────────────────────────────────────────────────────────────────────────── */

function Chip({ children, tone = "neutral" }: { children: React.ReactNode; tone?: "neutral" | "warn" | "good" }) {
  const tones = {
    neutral: "bg-field-200 text-ink-900",
    // Literal amber/success rungs: only 100 and 600/800 exist in this palette, and they do not
    // invert. Inside a tinted chip that is the pair to use — never amber-50 or success-500.
    warn: "bg-amber-100 text-amber-800",
    good: "bg-success-100 text-success-600"
  } as const;
  return (
    <span className={`rounded-full px-2.5 py-1 text-xs font-medium ${tones[tone]}`}>{children}</span>
  );
}

/**
 * The checksum, in full and selectable.
 *
 * SHOWN ENTIRE, not abbreviated to eight characters. It is the one field on this screen that
 * PROVES something — that the file in somebody's hand is this row and not the revision after it —
 * and a truncated hash cannot be compared against the output of `sha256sum` on a laptop in a
 * ministry office, which is the only way anybody ever checks one.
 */
function Checksum({ value, onDevice }: { value: string | null; onDevice: boolean }) {
  if (!value) {
    return (
      <span className="text-xs text-ink-500">
        No checksum recorded — this file cannot be matched to a copy by its contents.
        {onDevice
          ? " The Android app does not yet send one for a report it generates, so a phone-made file can" +
            " be identified only by its name and the moment it was made."
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

/* ────────────────────────────────────────────────────────────────────────────
 * The page
 * ──────────────────────────────────────────────────────────────────────────── */

export default function DesignWorkshopReportHistoryPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);

  const [history, setHistory] = useState<DwReportHistory | null>(null);
  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const [templates, setTemplates] = useState<DwTemplate[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [offline, setOffline] = useState(false);
  /**
   * True once the draft has been read and this workshop has no server record yet.
   *
   * There is nothing to be sorry about in this state: no file has been generated from a workshop the
   * repository has never seen, because both writers are on the server. What there IS to avoid is the
   * screen this page used to draw — a red "Record not found" produced by carrying the `dwlocal-…`
   * route param into `GET /report/history`, which reads as the workshop itself having been lost.
   */
  const [localOnly, setLocalOnly] = useState(false);
  const [leftId, setLeftId] = useState("");
  const [rightId, setRightId] = useState("");

  useEffect(() => {
    // A one-shot mount effect, so a `cancelled` flag is the right race guard (a generation counter
    // is for a list that refetches on filter changes; nothing here refetches).
    let cancelled = false;
    (async () => {
      /*
        THE SERVER'S ID FIRST, AND NOTHING IS ASKED OF THE SERVER WITHOUT ONE.

        `loadDraft` matches either id, so this one read answers for both shapes of URL; a workshop
        this browser has no draft for is an ordinary case (a colleague's, opened here for the first
        time) and its route param is then already a server id. Only a `dwlocal-…` id with no
        `remoteId` behind it has no server record at all, and that is the state below.
      */
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

        // The newest two, oldest on the left — the comparison a designer opening this page is
        // almost always making. With one export there is nothing to compare and the picker says so.
        const ordered = inGenerationOrder(loaded);
        if (ordered.length >= 2) {
          setLeftId(ordered[ordered.length - 2].id);
          setRightId(ordered[ordered.length - 1].id);
        }
      } catch (err) {
        if (cancelled) return;
        // `isUnreachable`, not `isTransient`: a repository that answered and then failed must not
        // be reported as a missing connection, or the designer goes to look at their signal for a
        // fault the server already named.
        if (isUnreachable(err)) setOffline(true);
        else setError(err instanceof Error ? err.message : "Unable to load the report history");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [id]);

  useEffect(() => {
    // The template NAMES, and nothing else. Fetched separately and allowed to fail on its own: a
    // template list that will not load must not cost the designer the history, and the id is a
    // legible fallback. It is not folded into the history payload for the same reason stage titles
    // are not — every client already caches `/templates`, and a second copy of a name on this wire
    // is a second thing to drift.
    let cancelled = false;
    (async () => {
      try {
        const loaded = await listReportTemplates();
        if (!cancelled) setTemplates(loaded);
      } catch {
        /* The template id is printed instead — see `templateName`. */
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [id]);

  const ordered = useMemo(() => (history ? inGenerationOrder(history) : []), [history]);
  const generationOf = useCallback(
    (exportId: string) => ordered.findIndex((row) => row.id === exportId) + 1,
    [ordered]
  );

  const templateName = useCallback(
    (templateId: string) => templates.find((t) => t.id === templateId)?.name ?? templateId,
    [templates]
  );

  const stageTitle = useCallback(
    (stageKey: string) => {
      const stage = registry?.stages.find((s) => s.key === stageKey);
      // The registry is the single declaration of what a stage is called, so an unknown key means
      // this browser's registry is older than the row — say the raw key rather than invent a title.
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
      ordered
        .slice()
        .reverse()
        .map((row, index) => ({
          value: row.id,
          label: `Generation ${ordered.length - index} · ${formatDateTime(row.generatedAt)} · ${row.format}`
        })),
    [ordered]
  );

  return (
    <>
      <PageHeader
        title="Report history"
        description="Every file generated from this workshop, and what changed in the record between any two of them."
        icon={<FileClock className="h-5 w-5" aria-hidden />}
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}

      {localOnly ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          This workshop was created on this device without a connection and has not reached the repository yet, so there
          is no history to read — every .docx and .pdf recorded here is written by the server, and none has been. It is
          created automatically on the next connection, and anything generated from then on is listed here. Nothing you
          have captured is affected.
        </div>
      ) : null}

      {offline ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          There is no connection, so the history cannot be read. It lists files generated on other devices by other
          people, so unlike your stages it is not kept in this browser. Everything you have captured is still here.
        </div>
      ) : null}

      {history === null && !error && !offline && !localOnly ? (
        <p className="text-sm text-ink-muted">Loading the history…</p>
      ) : null}

      {history && ordered.length === 0 ? (
        <EmptyState
          title="No report has been generated yet"
          body="Generate a .docx or a .pdf from the report page and it will be recorded here — with its checksum, its size and who made it — including files a phone produces with no network."
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
                    generation={generationOf(row.id)}
                    windowTruncated={history.exportsTruncated}
                    templateName={templateName(row.templateId)}
                    duplicates={sameFileAs(history, row.id)
                      .map((other) => generationOf(other.id))
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

/* ────────────────────────────────────────────────────────────────────────────
 * One recorded export
 * ──────────────────────────────────────────────────────────────────────────── */

function ExportCard({
  record,
  generation,
  windowTruncated,
  templateName,
  duplicates,
  staleStages
}: {
  record: DwExportRecord;
  generation: number;
  /**
   * True when the server cut the window this generation number is a position inside.
   *
   * `GET .../report/history` takes the NEWEST hundred and flags the overflow as `exportsTruncated`;
   * `inGenerationOrder` then re-sorts THAT WINDOW oldest-first and every label on this screen is an
   * index into it. So once the cap bites, "Generation 3" means "the third of the hundred listed",
   * not the third file this workshop ever produced — off by exactly the number of files cut, which
   * this page knows it does not know. Audit 2026-08-15 (LOW, frontend).
   *
   * The screen already discloses that files are missing, in two places. What it did not say is that
   * the NUMBERS are relative to what is left, and a generation number is precisely the thing a
   * designer quotes months later ("the cost sheet changed at generation 7") — a number that silently
   * means something different from what it did last quarter is worse than no number.
   */
  windowTruncated: boolean;
  templateName: string;
  duplicates: number[];
  /** Stages edited since this file was made — only computed for the newest, else null. */
  staleStages: number | null;
}) {
  return (
    <article className="rounded-md border border-line-200 bg-surface-50 p-3.5">
      <div className="flex flex-wrap items-center gap-2">
        {/* A generation number is a POSITION IN TIME, so a row that never recorded when it was
            generated has none — and "Generation 0" would read as one. It is also the row no
            comparison can include, which the wording says rather than leaving the reader to
            discover it in the picker. */}
        <span className="font-medium text-ink-900">
          {generation > 0
            ? windowTruncated
              ? `Generation ${generation} of the 100 most recent`
              : `Generation ${generation}`
            : "Undated file — it cannot be compared"}
        </span>
        <Chip>{record.format}</Chip>
        {/* Where the file came from is a fact about the archive, not trivia: a file a phone made
            offline exists on exactly one device until somebody copies it off. */}
        <Chip tone={record.generatedOnDevice ? "warn" : "neutral"}>
          {record.generatedOnDevice ? (
            <span className="inline-flex items-center gap-1">
              <Smartphone className="h-3 w-3" aria-hidden />
              Made on a phone, offline
            </span>
          ) : (
            <span className="inline-flex items-center gap-1">
              <Server className="h-3 w-3" aria-hidden />
              Made by the repository
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
          {/* Never substituted with the workshop's owner: `generatedBy` is SetNull, so a deleted
              account means nobody is named rather than the wrong person being named. */}
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
          {staleStages} stage{staleStages === 1 ? " has" : "s have"} been written to since this file was generated, so any
          copy already sent may no longer match the record.
        </p>
      ) : null}

      {record.warnings ? (
        <details className="mt-2">
          {/* The warnings never travel inside the document — an officer opening the .docx next month
              must not find a note about what was missing on the day — so the record is the only
              place they can still be read. */}
          <summary className="cursor-pointer text-xs font-medium text-ink-700">
            Warnings recorded when this file was made
          </summary>
          <p className="mt-1 whitespace-pre-line text-xs leading-5 text-ink-500">{record.warnings}</p>
        </details>
      ) : null}
    </article>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * The comparison
 * ──────────────────────────────────────────────────────────────────────────── */

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
          Only one file has been generated, so there is nothing to compare it with yet.
        </p>
      ) : (
        <>
          <div className="mt-3 grid gap-3 sm:grid-cols-2">
            {/* `advanceOnSelect={false}` on both: these filter the screen they sit on rather than
                filling in a form field, and moving focus away from a control the designer is still
                adjusting is wrong.

                `searchable` on both because this list grows one row per export, forever: it is the
                clearest case in the app of a list whose length is nobody's design decision. A
                workshop that has generated seven files would have no filter box and the same
                workshop next week would have one, and the thing a designer wants to do here — find
                the file from the 14th among thirty stamped with dates and names — is exactly what
                typing answers and scrolling does not. */}
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
              {/* "WRITTEN", NOT "CHANGED", and the distinction is the honest one rather than a
                  fussy one: a stage is saved whole and every row in the payload is updated without
                  being compared to what is stored, so one corrected word stamps the whole stage.
                  The negative direction carries no such loophole, which is why it is the sentence
                  allowed to say "identical". */}
              <p className="mt-4 text-sm leading-6 text-ink-700" data-testid="diff-summary">
                <strong className="text-ink-900">
                  Generation {diff.earlierGeneration} → generation {diff.laterGeneration}
                  {/* Same caveat as the card's, in the one other place these numbers are printed —
                      see `ExportCard`'s `windowTruncated`. Two labels for one arithmetic must never
                      disagree about how honest it is. */}
                  {history.exportsTruncated ? " (of the 100 most recent)" : ""}
                </strong>
                {": "}
                {touched.length === 0
                  ? "no stage of the workshop was written to between these two files."
                  : `${touched.length} stage${touched.length === 1 ? " was" : "s were"} written to between these two files.`}{" "}
                {diff.timelineComplete && untouched.length
                  ? `${untouched.length} other stage${untouched.length === 1 ? "" : "s"} carried identical data in both.`
                  : null}
              </p>

              <FileFacts diff={diff} templateName={templateName} />

              {/* ASYMMETRIC ON PURPOSE, and the asymmetry is the honest part. `save_stage` stamps
                  the workshop row on every stage save, so "written" cannot be told apart from an
                  ordinary save and must not be reported as "the cover was edited" — that sentence
                  would appear on almost every window and would be wrong on almost all of them.
                  "Not written" is a genuine proof and is worth saying plainly. */}
              {diff.headerRowWritten === false ? (
                <p className="mt-3 text-sm leading-6 text-ink-700" data-testid="header-verdict">
                  The cover details — title, craft, cluster and dates — were not written at all between these two
                  files, so both carry the same ones.
                </p>
              ) : null}
              {diff.headerRowWritten === true ? (
                <p className="mt-3 text-sm leading-6 text-ink-500" data-testid="header-verdict">
                  The workshop&apos;s own row was written in this window. Saving any stage stamps that row too, so on
                  its own this does not mean the cover details changed.
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
                          {stage.rowsTransient} row{stage.rowsTransient === 1 ? "" : "s"} added and removed again
                          between the two files, so neither document contains {stage.rowsTransient === 1 ? "it" : "them"}
                        </span>
                      ) : null}
                    </li>
                  ))}
                </ul>
              ) : null}

              {touched.length ? (
                <p className="mt-2 text-xs leading-5 text-ink-500">
                  A stage is saved whole, so every row it holds is stamped as written even when only one answer was
                  corrected. &ldquo;Rewritten&rdquo; counts rows saved, not answers that differ.
                </p>
              ) : null}

              {untouched.length ? (
                <div className="mt-4">
                  <h3 className="text-sm font-medium text-ink-900">
                    {diff.timelineComplete
                      ? "Identical in both files"
                      : "Nothing written (the timeline was capped — see below)"}
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

/**
 * A completeness figure, printed ONLY where it is true of both files.
 *
 * Today's score is today's. It describes a past export only when nothing has been written to the stage
 * since that export was generated — and then it describes BOTH exports, exactly. Everywhere else it
 * is withheld rather than shown with a caveat, because a percentage next to a date is read as that
 * date's percentage no matter what the caveat says.
 */
function StageCompleteness({
  stage,
  completeness
}: {
  stage: StageChange;
  completeness: DwReportHistory["completeness"];
}) {
  // Optional-chained deliberately: a server one deploy behind this client answers without
  // `completeness` at all, and the honest result of that is a missing percentage, not a screen
  // that throws on the whole history.
  const score = completeness?.[stage.stageKey];
  if (!score || !stage.currentReflectsBoth) return null;
  return (
    <span className="text-xs text-ink-500">
      {score.percent}% of its required fields, in both files and still today
    </span>
  );
}

/** The facts that come from the export rows themselves rather than from the stage timestamps. */
function FileFacts({ diff, templateName }: { diff: ReportDiff; templateName: (id: string) => string }) {
  const facts: React.ReactNode[] = [];

  if (diff.identicalFile) {
    facts.push(
      <>
        <strong className="text-ink-900">These two files are byte-for-byte identical.</strong> Their SHA-256 checksums
        match, so whatever else differs about the record, the documents themselves are the same file.
      </>
    );
  } else if (diff.checksumComparable) {
    facts.push(<>The two files differ — their checksums are not the same.</>);
  } else {
    facts.push(<>One of these files recorded no checksum, so the documents cannot be compared by their contents.</>);
  }

  if (diff.formatDiffers) {
    facts.push(
      <>
        One is a {diff.earlier.format} and the other a {diff.later.format}. Two formats of the same record are different
        files by construction, not by revision.
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
        The field registry itself moved between these two files, so a field may have been added, removed or retyped.
        Row counts remain comparable; the shape of what a row holds may not be.
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

/**
 * What this comparison cannot tell you.
 *
 * ON SCREEN AND NOT IN A COMMENT, because the reader of this panel is about to answer a ministry's
 * question from it. A limit nobody is told about is indistinguishable from a fact.
 */
function Limits({ diff, history }: { diff: ReportDiff; history: DwReportHistory }) {
  return (
    <div className="mt-5 rounded-md bg-surface-50 p-3.5">
      <h3 className="text-sm font-medium text-ink-900">What this comparison cannot tell you</h3>
      <ul className="mt-2 grid list-disc gap-1.5 pl-5 text-xs leading-5 text-ink-500">
        <li>
          <strong className="text-ink-700">Which field changed, or what it changed from.</strong> No copy of the stage
          data is kept when a report is generated — only the file&apos;s checksum, size, pages and template — so a
          rewritten row can be reported as rewritten and no further. Storing a snapshot of the workshop alongside each
          export is what would turn &ldquo;the costing stage was written to&rdquo; into &ldquo;the unit cost went from
          ₹420 to ₹455&rdquo;, and would survive the row being deleted afterwards.
        </li>
        <li>
          <strong className="text-ink-700">Whether a rewritten row&apos;s answers actually differ.</strong> A stage is
          saved in one write, and every row in that save is stamped without being compared to what was stored. A stage
          that nobody saved, on the other hand, is identical in both files with no such caveat.
        </li>
        <li>
          A row written twice between these two files counts once: each row remembers only when it was LAST written.
        </li>
        <li>
          Records this workshop points at — an artisan&apos;s name, a linked product, a photograph&apos;s caption — are
          not covered. They live outside the workshop and carry their own timestamps.
        </li>
        {diff.deviceClockInvolved ? (
          <li>
            <strong className="text-ink-700">One of these files was made on a phone with no network</strong>, so its
            timestamp is that device&apos;s clock while the stage edits are timed by the repository&apos;s. If the
            handset&apos;s clock was wrong, this window is wrong by the same amount.
          </li>
        ) : null}
        {!diff.timelineComplete ? (
          <li>
            <strong className="text-ink-700">The stage timeline was capped</strong>, so rows are missing from the
            evidence and no stage above can be called identical — only &ldquo;nothing written that we can see&rdquo;.
          </li>
        ) : null}
        {history.exportsTruncated ? (
          <li>
            Only the most recent 100 files are listed, and the generation numbers above count only those hundred — an
            older file made before them is not generation 0, it is simply not here.
          </li>
        ) : null}
      </ul>
    </div>
  );
}
