"use client";

/**
 * ONE WORKSHOP UNDER OVERSIGHT — every stage, read-only, with who wrote each field.
 *
 * ── WHY PROVENANCE IS ON THIS SCREEN ──────────────────────────────────────────────────────────
 *
 * `read_overseen_workshop` resolves the per-field authorship names before it answers, deliberately,
 * because "who wrote this field" is most of what supervision is for and the ids without them are
 * unreadable. So this page draws {@link FieldProvenance} under every value — the SAME component the
 * designer's stage form draws under every box, producing the same sentence in the same words,
 * because an officer and the designer they supervise must not be reading two different accounts of
 * who did what.
 *
 * ── `readOnly` IS HONOURED RATHER THAN ASSUMED ───────────────────────────────────────────────
 *
 * `readOnly` IS THE ASSIGNMENT'S PROPERTY AND NOT A ROLE'S, WHICH MATTERS SINCE 2026-09-14. This
 * comment used to say an officer is outside `DESIGN_WORKSHOP_ROLES` so the workshop tree answers a
 * **404** to them, exactly as it does to a professor. The three directorate tiers are now inside
 * that set, so that is no longer the reason and this page must not lean on it: what makes a
 * MONITORED workshop read-only is the oversight assignment it is rendered from, which grants
 * watching and not authoring. Since the owner's ruling of 2026-10-09 that holds in the workshop tree
 * too: whoever holds a post on a workshop — an admin named its Regional Director, say — is refused
 * its writes there for as long as they hold it, and a designer row on it is refused to them.
 *
 * The read-only banner is DECLARED rather than left to be inferred from the absence of buttons: a
 * screen with no Save on it looks the same as a screen whose Save has not loaded yet.
 *
 * ── THE RENDERERS ARE THE INSPECTION SURFACE'S, IMPORTED AND NOT COPIED ──────────────────────
 *
 * `inspectionFieldReading` is a PURE function over the stage registry — it decides whether a field
 * is empty, is media this read does not carry, or is text, and it is the same question on both
 * surfaces. Its name says "inspection" because that scope needed it first; importing it is
 * deliberate, and a second copy here would be two renderers of one registry drifting the first time
 * either is corrected. **If it is ever promoted to `lib/designWorkshops`, this import follows it.**
 *
 * ── THE WORKSHOP'S FILES AND ITS OWN QUESTIONS ARE SHOWN, READ-ONLY (sweep item F5, 2026-10-10) ──
 *
 * The files come from this surface's own second read,
 * `GET /design-workshop-oversight/assigned/{id}/media`, behind the same loader as the workshop, with
 * short-lived signed links and no control that writes — the same components the inspection screen
 * draws them with (`components/designworkshop/ReaderWorkshopMedia.tsx`). The workshop's questions
 * travel on the read as `customSections`, so each stage's custom answers are printed with their
 * wording and their author.
 */

import Link from "next/link";
import { use, useEffect, useMemo, useState } from "react";
import { Binoculars, Lock } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { FieldProvenance } from "@/components/designworkshop/FieldProvenance";
import {
  ReaderCustomAnswers,
  ReaderFilesPanel,
  ReaderMediaProvider,
  ReaderMediaValue,
  useReaderMedia
} from "@/components/designworkshop/ReaderWorkshopMedia";
import { PageHeader } from "@/components/PageHeader";
import { StatusBadge } from "@/components/StatusBadge";
import { ApiError } from "@/lib/api";
import type { DwCustomDefinition } from "@/lib/customSections";
import { inspectionFieldReading } from "@/lib/designWorkshopInspections";
import {
  fetchStageRegistry,
  formFields,
  isFilled,
  overallPercent,
  rowTitle,
  type DwEntity,
  type DwEntryData,
  type DwField,
  type DwFieldStamp,
  type DwRegistry,
  type DwRow,
  type DwStage,
  type DwStageCompleteness,
  type DwStageData
} from "@/lib/designWorkshops";
import { decisionKindLabel, signOffLines } from "@/lib/designWorkshopApprovals";
import { formatDate } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import { canReadWorkshopOversight, roleLabel } from "@/lib/permissions";
import type { User } from "@/lib/types";
import {
  CAPACITY_LABELS,
  getOverseenWorkshop,
  oversightIsReadOnly,
  oversightRefusalMeansNoPosts,
  type DwOversightDetail
} from "../../oversight";

function describeFailure(error: unknown, user: User | null | undefined): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) {
    return "This device cannot reach the repository, so this workshop could not be read. Nothing was loaded at all.";
  }
  if (oversightRefusalMeansNoPosts(error, user)) {
    // An admin the server will not yet show this surface to holds no post anywhere — so none here.
    return "This workshop is not one you supervise: you do not hold any Assistant Director or Regional Director posts. A Ministry Admin, an admin or the master admin names them one workshop at a time, on Workshop oversight.";
  }
  if (error.status === 404) {
    return "This workshop is not one you have been named on as Assistant Director or Regional Director, or it no longer exists. A Ministry Admin, an admin or the master admin names them one workshop at a time, on Workshop oversight.";
  }
  return error.message;
}

/** One field of one record, with who wrote it. */
function ReadField({
  registry,
  entity,
  field,
  row,
  stamp
}: {
  registry: DwRegistry;
  entity: DwEntity;
  field: DwField;
  row: DwEntryData;
  stamp?: DwFieldStamp | null;
}) {
  const reading = inspectionFieldReading(registry, entity, field, row);
  if (reading.kind === "empty") return null;

  return (
    <div className="grid gap-0.5 py-2">
      <span className="field-label">{field.label}</span>
      {reading.kind === "media" ? (
        <ReaderMediaValue value={row[field.key]} />
      ) : (
        <span className="whitespace-pre-wrap text-sm leading-6 text-ink-900">{reading.text}</span>
      )}
      {/* The designer's own component, verbatim: same sentence, same wording, same day-first date. */}
      <FieldProvenance stamp={stamp} />
    </div>
  );
}

/** Every field of one record — a singleton entity, or one row of a collection. */
function ReadRecord({
  registry,
  entity,
  row,
  stamps
}: {
  registry: DwRegistry;
  entity: DwEntity;
  row: DwEntryData;
  stamps: Record<string, DwFieldStamp> | undefined;
}) {
  // `formFields` and not `entity.fields`: deprecated fields are dead inputs on the designer's form
  // and a caption belongs to the media field it captions, so showing either here would put a field
  // in front of an officer that the designer never saw.
  const fields = formFields(entity);
  const answered = fields.filter(
    (field) => inspectionFieldReading(registry, entity, field, row).kind !== "empty"
  );
  const unanswered = fields.length - answered.length;

  return (
    <div className="grid divide-y divide-line-200">
      {answered.map((field) => (
        <ReadField
          entity={entity}
          field={field}
          key={field.key}
          registry={registry}
          row={row}
          stamp={stamps?.[field.key]}
        />
      ))}
      {/* WHAT WAS LEFT OUT IS COUNTED. A read that silently drops empty boxes and a record that was
          fully answered look identical, and to a supervisor the difference is the finding. */}
      {unanswered > 0 ? (
        <p className="pt-2 text-xs leading-5 text-ink-500">
          {unanswered} of {fields.length} field{fields.length === 1 ? "" : "s"} here{" "}
          {unanswered === 1 ? "is" : "are"} unanswered and {unanswered === 1 ? "is" : "are"} not
          listed above.
        </p>
      ) : null}
    </div>
  );
}

/** One stage: its entities, its rows, and what this read cannot show about it. */
function ReadStage({
  registry,
  stage,
  data,
  score,
  definition
}: {
  registry: DwRegistry;
  stage: DwStage;
  data: DwStageData | undefined;
  /** The workshop's own questions, off the same read — see `customSections`. */
  definition: DwCustomDefinition | null | undefined;
  /**
   * THIS STAGE'S SCORE, PASSED IN FROM THE WORKSHOP-LEVEL MAP AND NOT READ OFF `data`.
   *
   * `DwStageData.completeness` exists and is EMPTY HERE: `_stages_payload` writes no such key, and
   * only the single-stage route — which an officer cannot reach — attaches one afterwards. Reading
   * the optional key would show "Nothing recorded" beside every stage of a finished workshop.
   */
  score: DwStageCompleteness | undefined;
}) {
  const singleton: DwEntryData = data?.singleton ?? {};
  const provenance = data?.provenance;

  /** Whether this stage holds any answer to the workshop's own questions — printed below with them. */
  const customAnswers = Object.entries(data?.custom ?? {}).filter(([, value]) =>
    isFilled(value)
  ).length;

  const collections = stage.entities.filter((entity) => entity.cardinality === "COLLECTION");
  const singletons = stage.entities.filter((entity) => entity.cardinality !== "COLLECTION");

  const nothingRecorded =
    !data ||
    (Object.keys(singleton).length === 0 &&
      collections.every((entity) => (data.collections?.[entity.key] ?? []).length === 0) &&
      customAnswers === 0);

  return (
    <section className="panel p-4" id={`stage-${stage.key}`}>
      <header className="flex flex-wrap items-baseline justify-between gap-2 border-b border-line-200 pb-3">
        <h2 className="font-display text-lg font-bold text-ink-900">
          {stage.number}. {stage.title}
        </h2>
        <span className="text-xs text-ink-500">
          {score
            ? score.isComplete
              ? "Every required field answered"
              : `${score.requiredFilled} of ${score.requiredTotal} required fields answered`
            : "Nothing recorded"}
          {stage.optionalStage ? " · this stage may be dropped" : ""}
        </span>
      </header>

      {stage.purpose ? <p className="pt-3 text-xs leading-5 text-ink-500">{stage.purpose}</p> : null}

      {nothingRecorded ? (
        <p className="pt-3 text-sm text-ink-500">
          Nothing has been recorded on this stage.
          {stage.optionalStage
            ? " The source document marks it as one a workshop may legitimately skip."
            : ""}
        </p>
      ) : (
        <div className="grid gap-4 pt-3">
          {singletons.map((entity) => (
            <div key={entity.key}>
              {singletons.length > 1 || collections.length > 0 ? (
                <h3 className="mb-1 text-sm font-medium text-ink-700">{entity.title}</h3>
              ) : null}
              <ReadRecord
                entity={entity}
                registry={registry}
                row={singleton}
                stamps={provenance?.singleton}
              />
            </div>
          ))}

          {collections.map((entity) => {
            const rows: DwRow[] = data?.collections?.[entity.key] ?? [];
            if (rows.length === 0) return null;
            return (
              <div key={entity.key}>
                <h3 className="mb-1 text-sm font-medium text-ink-700">
                  {entity.title} · {rows.length} {rows.length === 1 ? "row" : "rows"}
                </h3>
                <div className="grid gap-3">
                  {rows.map((row, index) => (
                    <div
                      className="rounded-md border border-line-200 bg-surface-50 p-3"
                      key={row._entryId ?? row._clientKey ?? index}
                    >
                      {/* `rowTitle` and never the row's id — a list of cuids asks a supervisor to
                          recognise rows they cannot possibly recognise. */}
                      <p className="mb-1 text-sm font-medium text-ink-900">
                        {rowTitle(entity, row, index)}
                      </p>
                      <ReadRecord
                        entity={entity}
                        registry={registry}
                        row={row}
                        // Keyed by ENTRY ID and never by position: the readers of this data sort
                        // their rows differently, and a positional map shows one participant's edits
                        // against another participant's name.
                        stamps={
                          row._entryId
                            ? provenance?.collections?.[entity.key]?.[row._entryId]
                            : undefined
                        }
                      />
                    </div>
                  ))}
                </div>
              </div>
            );
          })}

          {customAnswers > 0 ? (
            <ReaderCustomAnswers
              definition={definition}
              stageKey={stage.key}
              stamps={provenance?.custom}
              values={data?.custom}
            />
          ) : null}
        </div>
      )}
    </section>
  );
}

export default function WorkshopUnderOversightPage({
  params
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = use(params);
  const { user, loading } = useAuth();

  const [detail, setDetail] = useState<DwOversightDetail | null>(null);
  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [registryError, setRegistryError] = useState<string | null>(null);
  /* The workshop's files, asked for once the workshop itself has been read. */
  const media = useReaderMedia("oversight", id, detail !== null);

  useEffect(() => {
    if (loading || !canReadWorkshopOversight(user)) return;
    let cancelled = false;
    getOverseenWorkshop(id)
      .then((result) => {
        if (cancelled) return;
        setDetail(result);
        setError(null);
      })
      .catch((err) => {
        if (cancelled) return;
        setError(describeFailure(err, user));
      });
    return () => {
      cancelled = true;
    };
  }, [id, loading, user]);

  useEffect(() => {
    if (loading || !canReadWorkshopOversight(user)) return;
    let cancelled = false;
    fetchStageRegistry()
      .then((result) => {
        if (cancelled) return;
        setRegistry(result);
        setRegistryError(null);
      })
      .catch(() => {
        if (cancelled) return;
        // A SEPARATE FAILURE FROM THE WORKSHOP READ, because it means something different: the
        // answers are in hand and the field list that names them is not. Folding it into the error
        // above would say the workshop could not be read when it was.
        setRegistryError(
          "The field list could not be loaded, so the stages below cannot be named or labelled. The workshop itself was read; try again."
        );
      });
    return () => {
      cancelled = true;
    };
  }, [loading, user]);

  // EVERY HOOK ABOVE THE REFUSAL BELOW, WITHOUT EXCEPTION — the refusal returns early, and a
  // `useMemo` written under it would run on some renders and not others.
  const overall = useMemo(() => overallPercent(detail?.completeness), [detail]);

  if (!loading && !canReadWorkshopOversight(user)) {
    return (
      <div>
        <PageHeader
          title="Workshop I monitor"
          icon={<Binoculars className="h-5 w-5" aria-hidden />}
        />
        <section className="panel px-6 py-14 text-center" aria-live="polite">
          {/*
            PURPLE, DELIBERATELY, ON THE ONE PANEL OF THIS PAGE THAT IS NOT A MINISTRY SURFACE.
            The read-only band above moved to the `ministry` ramp in 0.0.12 and this padlock did
            not: a refusal is shown to somebody who is NOT a ministry account — a designer, a
            professor, an inspector — and painting the ministry's own colour around the notice that
            they are not of the ministry would be a lie told in colour. `AppShell` makes the same
            call one level up and stamps `data-surface="ministry"` only when the page is served.
          */}
          <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-purple-50 text-purple-700">
            <Lock className="h-5 w-5" aria-hidden />
          </div>
          <h1 className="font-display text-xl font-bold tracking-tight text-ink-900">
            Officer access required
          </h1>
          <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-ink-500">
            A monitored workshop is read by whoever was named on it as Assistant Director or Regional
            Director — an officer of that tier, a Ministry Admin, an admin or the master admin.
            Designers read design &amp; prototype workshops on Design workshops instead.
          </p>
          <p className="mt-3 text-xs text-ink-500">
            You are signed in as{" "}
            <span className="font-medium text-ink-700">{roleLabel(user?.role)}</span>.
          </p>
          <div className="mt-7">
            <Link className="field-button" href="/dashboard">
              Back to dashboard
            </Link>
          </div>
        </section>
      </div>
    );
  }

  const readOnly = oversightIsReadOnly(detail);
  const stages: DwStage[] = registry?.stages ?? [];

  return (
    <div>
      <PageHeader
        title={detail?.title?.trim() || "Workshop I monitor"}
        description={
          detail
            ? [
                detail.workshopCode,
                detail.craftName,
                detail.clusterName,
                detail.district,
                detail.state
              ]
                .filter(Boolean)
                .join(" · ") || "No workshop code yet — stage 1 has not been saved."
            : undefined
        }
        icon={<Binoculars className="h-5 w-5" aria-hidden />}
        actions={detail ? <StatusBadge status={detail.status} /> : undefined}
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      ) : null}
      {registryError ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-5 text-amber-800">
          {registryError}
        </div>
      ) : null}

      {/*
        S0's inline-swap row for this file. A plain `p` and NOT a `.panel`, which is what keeps it
        out of the scoped `[data-surface="ministry"] .panel` rule's way — that selector is (0,2,0)
        and would beat a border utility written on the same element.

        THE `dark:` PAIR IS NOT OPTIONAL HERE. The ministry ramp is literal and does not invert, so
        `bg-ministry-50` under a dark `bg-card` is a pale slab, and `text-ministry-700` on it is
        2.44:1. Both halves move together: `ministry-950/40` ground, `ministry-300` ink.
      */}
      {detail && readOnly ? (
        <p className="mb-4 rounded-md border border-ministry-300 bg-ministry-50 px-3 py-2 text-xs leading-5 text-ink-700 dark:border-ministry-900 dark:bg-ministry-950/40">
          <span className="font-semibold text-ministry-700 dark:text-ministry-300">Read-only.</span>{" "}
          Every stage below is shown as the designers recorded it, with who wrote each field, and
          nothing here can be edited, submitted or deleted — the photographs, recordings and
          attachments included.
        </p>
      ) : null}

      {detail === null && !error ? (
        <section className="panel p-4 text-sm text-ink-700">Loading the workshop…</section>
      ) : null}

      {detail ? (
        <>
          <section className="panel mb-4 grid gap-2 p-4 sm:grid-cols-2">
            <p className="text-sm text-ink-700">
              <span className="field-label block">Dates</span>
              {formatDate(detail.startDate)}
              {detail.endDate ? ` – ${formatDate(detail.endDate)}` : ""}
            </p>
            <p className="text-sm text-ink-700">
              <span className="field-label block">Designer</span>
              {detail.designerName?.trim() || "Not recorded on stage 1"}
            </p>
            <p className="text-sm text-ink-700">
              <span className="field-label block">Venue</span>
              {detail.venue?.trim() || "Not recorded on stage 1"}
            </p>
            <p className="text-sm text-ink-700">
              <span className="field-label block">Required fields answered</span>
              {overall}% across every stage
            </p>
          </section>

          <section className="panel mb-4 p-4">
            <h2 className="font-display text-base font-bold tracking-tight text-ink-900">
              Who supervises this workshop
            </h2>
            {detail.oversight?.length ? (
              <ul className="mt-2 grid gap-2 sm:grid-cols-2">
                {detail.oversight.map((row) => (
                  <li
                    key={row.capacity}
                    className="rounded-md border border-line-200 bg-surface-50 px-3 py-2"
                  >
                    <p className="field-label">{CAPACITY_LABELS[row.capacity] ?? row.capacity}</p>
                    <p className="mt-1 truncate text-sm font-medium text-ink-900">{row.name}</p>
                    <p className="truncate text-xs text-ink-500">{row.email}</p>
                  </li>
                ))}
              </ul>
            ) : (
              // A DIFFERENT SENTENCE FROM "nothing loaded". You are reading this page, so an
              // oversight row naming you exists; an empty list here would be the server contradicting
              // itself, and saying so is more useful than drawing an empty box.
              <p className="mt-2 text-sm text-ink-700">
                No oversight is recorded on this workshop. If you can read this page, that is a
                disagreement worth reporting rather than an empty list.
              </p>
            )}
          </section>

          <ReaderFilesPanel
            failure={media.failure}
            list={media.list}
            onRetry={media.retry}
            state={media.state}
          />
          {/* WHERE THE REPORT STANDS, AND WHO DECIDED — the sign-off is the Ministry Admin's, and a
              supervisor reading the workshop is owed who approved it, who handed it on, and every
              decision on the way. */}
          {signOffLines(detail).length || detail.decisions?.length ? (
            <section className="panel mb-4 grid gap-3 p-4">
              <h2 className="font-display text-base font-bold tracking-tight text-ink-900">Decisions on this report</h2>
              {signOffLines(detail).map((line) => (
                <p className="text-sm leading-6 text-ink-700" key={line}>
                  {line}
                </p>
              ))}
              {detail.decisions?.length ? (
                <ul className="grid gap-2">
                  {detail.decisions.map((decision) => (
                    <li className="rounded-md border border-line-200 bg-surface-50 px-3 py-2" key={decision.id}>
                      <p className="text-sm font-medium text-ink-900">{decisionKindLabel(decision.kind)}</p>
                      {decision.note?.trim() ? (
                        <p className="mt-1 whitespace-pre-wrap text-sm leading-6 text-ink-700">{decision.note}</p>
                      ) : null}
                      <p className="mt-1 text-xs leading-5 text-ink-500">
                        {decision.actorName?.trim() || "Somebody no longer on record"}
                        {decision.at ? ` · ${formatDate(decision.at)}` : ""}
                      </p>
                    </li>
                  ))}
                </ul>
              ) : null}
            </section>
          ) : null}

          {registry === null ? (
            registryError ? null : (
              <section className="panel p-4 text-sm text-ink-700">Loading the field list…</section>
            )
          ) : (
            <ReaderMediaProvider list={media.list} state={media.state}>
              <div className="grid gap-4">
                {stages.map((stage) => (
                  <ReadStage
                    data={detail.stages?.[stage.key]}
                    definition={detail.customSections}
                    key={stage.key}
                    registry={registry}
                    score={detail.completeness?.[stage.key]}
                    stage={stage}
                  />
                ))}
              </div>
            </ReaderMediaProvider>
          )}
        </>
      ) : null}
    </div>
  );
}
