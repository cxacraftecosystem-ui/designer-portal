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
 * An officer is outside `DESIGN_WORKSHOP_ROLES`, so `/design-workshops/{id}` and every page beneath
 * it answers a **404** to them — exactly as it does to a professor. Nothing on this page links into
 * that tree, and the read-only banner is DECLARED rather than left to be inferred from the absence
 * of buttons: a screen with no Save on it looks the same as a screen whose Save has not loaded yet.
 *
 * ── THE RENDERERS ARE THE INSPECTION SURFACE'S, IMPORTED AND NOT COPIED ──────────────────────
 *
 * `inspectionFieldReading` is a PURE function over the stage registry — it decides whether a field
 * is empty, is media this read does not carry, or is text, and it is the same question on both
 * surfaces. Its name says "inspection" because that scope needed it first; importing it is
 * deliberate, and a second copy here would be two renderers of one registry drifting the first time
 * either is corrected. **If it is ever promoted to `lib/designWorkshops`, this import follows it.**
 *
 * ── MEDIA IS COUNTED AND EXPLAINED, NEVER DRAWN AS AN EMPTY FRAME ────────────────────────────
 *
 * An oversight read carries no photographs, recordings or attachments. "No photograph" and "a
 * photograph this read does not carry" are different facts and the reader has no other way to tell
 * them apart, so the count is printed with the sentence. Whether an officer SHOULD see them is an
 * owner's decision that has not been made — today the answer is no, stated once, rather than yes by
 * inheritance from a predicate written for co-designers.
 */

import Link from "next/link";
import { use, useEffect, useMemo, useState } from "react";
import { Binoculars, Lock } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { FieldProvenance } from "@/components/designworkshop/FieldProvenance";
import { PageHeader } from "@/components/PageHeader";
import { StatusBadge } from "@/components/StatusBadge";
import { ApiError } from "@/lib/api";
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
import { formatDate } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import { canReadWorkshopOversight, roleLabel } from "@/lib/permissions";
import {
  CAPACITY_LABELS,
  getOverseenWorkshop,
  oversightIsReadOnly,
  type DwOversightDetail
} from "../../oversight";

function describeFailure(error: unknown): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) {
    return "This device cannot reach the repository, so this workshop could not be read. Nothing was loaded at all.";
  }
  if (error.status === 404) {
    return "This workshop is not one you have been assigned to supervise, or it no longer exists. A Ministry Admin assigns an Assistant Director and a Regional Director one workshop at a time, on Workshop oversight.";
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
        <span className="text-sm text-ink-500">
          {reading.count} file{reading.count === 1 ? "" : "s"} recorded here. An oversight read does
          not carry photographs, recordings or attachments.
        </span>
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
  score
}: {
  registry: DwRegistry;
  stage: DwStage;
  data: DwStageData | undefined;
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

  /**
   * The answers to this workshop's own questions, counted and no more.
   *
   * The keys are the designer's field ids and the labels live behind a route this account is
   * refused, so printing the keys would put `q_7f3c: "yes"` in front of an officer and call it an
   * answer. Counting the FILLED ones is the honest maximum.
   */
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
            <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs leading-5 text-ink-500">
              {customAnswers} answer{customAnswers === 1 ? "" : "s"} to question
              {customAnswers === 1 ? "" : "s"} this workshop&apos;s designer added to this stage{" "}
              {customAnswers === 1 ? "is" : "are"} recorded. The questions themselves are read
              through a route an oversight read does not reach, so the answers are not shown without
              them.
            </p>
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
        setError(describeFailure(err));
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
          <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-purple-50 text-purple-700">
            <Lock className="h-5 w-5" aria-hidden />
          </div>
          <h1 className="font-display text-xl font-bold tracking-tight text-ink-900">
            Officer access required
          </h1>
          <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-ink-500">
            This page belongs to the Assistant Director, Regional Director and Ministry Admin posts.
            Designers and admins read design &amp; prototype workshops on Design workshops instead.
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

      {detail && readOnly ? (
        <p className="mb-4 rounded-md border border-purple-300 bg-purple-50 px-3 py-2 text-xs leading-5 text-ink-700">
          <span className="font-semibold text-purple-700">Read-only.</span> Every stage below is
          shown as the designers recorded it, with who wrote each field, and nothing here can be
          edited, submitted or deleted. Photographs, recordings and attachments are not carried on an
          oversight read.
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

          {registry === null ? (
            registryError ? null : (
              <section className="panel p-4 text-sm text-ink-700">Loading the field list…</section>
            )
          ) : (
            <div className="grid gap-4">
              {stages.map((stage) => (
                <ReadStage
                  data={detail.stages?.[stage.key]}
                  key={stage.key}
                  registry={registry}
                  score={detail.completeness?.[stage.key]}
                  stage={stage}
                />
              ))}
            </div>
          )}
        </>
      ) : null}
    </div>
  );
}
