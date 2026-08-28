"use client";

/**
 * ONE WORKSHOP UNDER INSPECTION — every stage, read-only, with who wrote each field.
 *
 * ── WHAT AN INSPECTION IS FOR, AND WHY PROVENANCE IS THE POINT OF THIS SCREEN ─────────────────
 *
 * `read_workshop_under_inspection` resolves the per-field authorship names before it answers,
 * deliberately, "because 'who wrote this field' is most of what an inspection is for, and the ids
 * without them are unreadable". So this page draws {@link FieldProvenance} under every value — the
 * SAME component the designer's stage form draws under every box, producing the same sentence in the
 * same words, because an inspector and the designer being inspected must not be reading two
 * different accounts of who did what.
 *
 * ── `readOnly` IS HONOURED RATHER THAN ASSUMED ────────────────────────────────────────────────
 *
 * Every link into the workshop tree is asked for through {@link inspectionMayOpen}, which is false
 * for a read. That is not ceremony: `/design-workshops/{id}` and all nine pages beneath it answer a
 * 404 to an inspector, because `load_workshop_or_404` refuses anybody outside `DESIGN_WORKSHOP_ROLES`
 * before it looks at the row — and INSPECTOR is outside it, exactly as PROFESSOR is. A stage heading
 * that linked to the page a designer edits it on is the single most natural thing to write here and
 * it would 404 every time.
 *
 * There is likewise no Save, no stage form, no delete, no submit and no report button on this page,
 * and none of them is missing: there is no route on this prefix that would accept any of them.
 *
 * ── WHY IT IS NOT `FieldInput` WITH `disabled` PASSED DOWN ────────────────────────────────────
 *
 * The argument is at {@link inspectionFieldReading} in full. The short version is that mounting the
 * designer's control would draw the media picker, the reference picker, the dictation button and a
 * whole embedded record form — every one of them pointed at a route this account is refused — and
 * would render each photograph's "could not be read" state, which is indistinguishable from a
 * photograph that failed to load and is not what happened. What this page reuses instead is every
 * function that INTERPRETS a stored value (`inputValue`, `listValue`, `geoValue`, `isFilled`,
 * `richSummary`, `referenceDisplayHint`, `formFields`, `rowTitle`) plus the
 * provenance component, so nothing about what a value MEANS is decided twice.
 *
 * ── THE TWO THINGS THIS READ CANNOT SHOW, BOTH SAID ON SCREEN ─────────────────────────────────
 *
 * 1. **Photographs, recordings and attachments.** The payload carries no `transcripts` key and the
 *    media rows are gated per file; an inspector holds no upload, no `DataAccessGrant` and no viewer
 *    row. Whether an inspector SHOULD see them is an owner's decision that has not been made, so the
 *    honest rendering is a counted sentence — "3 photographs are recorded here; an inspection read
 *    does not carry them" — and never an empty gallery.
 * 2. **The workshop's own designer-defined questions.** Their ANSWERS are in the payload's `custom`
 *    bucket; the questions they answer are read through `GET /design-workshops/{id}/custom-sections`,
 *    which is behind `load_workshop_or_404` and therefore a 404 here. Printing the raw keys and
 *    calling them labels would be worse than counting them and saying why.
 *
 * ── THE REGISTRY IS THE LIST OF STAGES, NOT THE PAYLOAD ───────────────────────────────────────
 *
 * `GET /design-workshops/schema` takes `get_current_user` with no role dependency, so an inspector
 * reads it like anybody else, and iterating IT rather than `Object.keys(detail.stages)` is what makes
 * an untouched stage appear at all. A stage with nothing in it is a FINDING on this screen — the one
 * a list built from the payload's own keys would silently omit.
 */

import Link from "next/link";
import { use, useEffect, useMemo, useState } from "react";
import { FileSearch, Lock } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { FieldProvenance } from "@/components/designworkshop/FieldProvenance";
import { PageHeader } from "@/components/PageHeader";
import { StatusBadge } from "@/components/StatusBadge";
import { ApiError } from "@/lib/api";
import {
  getWorkshopUnderInspection,
  inspectionFieldReading,
  inspectionIsReadOnly,
  inspectionMayOpen,
  type DwInspectionDetail
} from "@/lib/designWorkshopInspections";
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
import { canInspectDesignWorkshops, roleLabel } from "@/lib/permissions";

/**
 * The failures this page can suffer, told apart in words.
 *
 * THE 404 ARM IS THE INTERESTING ONE AND IT IS DELIBERATELY VAGUE ABOUT WHICH.
 * `load_inspectable_workshop_or_404` raises the identical 404 for four different states — not an
 * inspector, no such workshop, the workshop is soft-deleted, and no inspection row for this account
 * — and that is the server being careful rather than careless: distinguishing them would let anybody
 * probe which workshop ids exist. So this sentence must not guess either. It names the two states
 * the reader can actually do something about and attributes neither.
 */
function describeFailure(error: unknown): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) {
    return "This device cannot reach the repository, so this workshop could not be read. Nothing is missing from the record — nothing was read at all.";
  }
  if (error.status === 403) {
    return error.message;
  }
  if (error.status === 404) {
    return "This workshop is not open to you. Either it is not assigned to you to inspect, or it has been deleted since your list was loaded. An admin assigns inspections on Manage workshop access.";
  }
  return error.message || "This workshop could not be read.";
}

/** One field's label, value and authorship — the whole of what a read shows about one answer. */
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
        // COUNTED AND EXPLAINED, never an empty frame. "No photograph" and "a photograph this read
        // does not carry" are different facts and the reader has no other way to tell them apart.
        <span className="text-sm text-ink-500">
          {reading.count} file{reading.count === 1 ? "" : "s"} recorded here. An inspection read does not carry
          photographs, recordings or attachments.
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
  // in front of an inspector that the person being inspected never saw.
  const fields = formFields(entity);
  const answered = fields.filter((field) => {
    const reading = inspectionFieldReading(registry, entity, field, row);
    return reading.kind !== "empty";
  });
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
          fully answered look identical, and on an inspection the difference is the finding. */}
      {unanswered > 0 ? (
        <p className="pt-2 text-xs leading-5 text-ink-500">
          {unanswered} of {fields.length} field{fields.length === 1 ? "" : "s"} here {unanswered === 1 ? "is" : "are"}{" "}
          unanswered and {unanswered === 1 ? "is" : "are"} not listed above.
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
   * `DwStageData.completeness` exists and is EMPTY HERE, which is the trap: `_stages_payload`
   * writes no such key, and only the single-stage route `GET /design-workshops/{id}/stages/{key}`
   * — which an inspector cannot reach — attaches one afterwards. The workshop read carries the
   * scores in a sibling map keyed by stage instead. Reading the optional key would have shown
   * "Nothing recorded" beside every stage of a finished workshop.
   */
  score: DwStageCompleteness | undefined;
}) {
  const singleton: DwEntryData = data?.singleton ?? {};
  const provenance = data?.provenance;

  /**
   * The answers to this workshop's own questions, counted and no more.
   *
   * The keys are the designer's field ids and the labels live behind a route this account is
   * refused, so printing the keys would put `q_7f3c: "yes"` in front of an inspector and call it an
   * answer. Counting the FILLED ones is the honest maximum.
   */
  const customAnswers = Object.entries(data?.custom ?? {}).filter(([, value]) => isFilled(value)).length;

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
                    <div className="rounded-md border border-line-200 bg-surface-50 p-3" key={row._entryId ?? row._clientKey ?? index}>
                      {/* `rowTitle` and never the row's id — a list of cuids asks an inspector to
                          recognise rows they cannot possibly recognise. */}
                      <p className="mb-1 text-sm font-medium text-ink-900">{rowTitle(entity, row, index)}</p>
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
              {customAnswers} answer{customAnswers === 1 ? "" : "s"} to question{customAnswers === 1 ? "" : "s"} this
              workshop&apos;s designer added to this stage {customAnswers === 1 ? "is" : "are"} recorded. The questions
              themselves are read through a route an inspection does not reach, so the answers are not shown without
              them.
            </p>
          ) : null}
        </div>
      )}
    </section>
  );
}

export default function WorkshopUnderInspectionPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { user, loading } = useAuth();

  const [detail, setDetail] = useState<DwInspectionDetail | null>(null);
  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [registryError, setRegistryError] = useState<string | null>(null);

  useEffect(() => {
    if (loading || !canInspectDesignWorkshops(user)) return;
    let cancelled = false;
    getWorkshopUnderInspection(id)
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
    if (loading || !canInspectDesignWorkshops(user)) return;
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
        // above would tell an inspector the workshop could not be read when it was.
        setRegistryError(
          "The field list could not be loaded, so the stages below cannot be named or labelled. The workshop itself was read; try again."
        );
      });
    return () => {
      cancelled = true;
    };
  }, [loading, user]);

  /*
    EVERY HOOK ABOVE THE REFUSAL BELOW, WITHOUT EXCEPTION. The refusal returns early, and a `useMemo`
    written under it would run on some renders and not others — the hook-order rule, and the reason
    this one figure is computed here rather than beside the paragraph that prints it.
  */
  const overall = useMemo(() => overallPercent(detail?.completeness), [detail]);

  /*
    THE SERVER'S OWN PREDICATE, MIRRORED. `assert_inspection_surface` refuses everybody outside
    `INSPECTION_ROLES` — admins and the master admin included — so this is not a narrowing.
  */
  if (!loading && !canInspectDesignWorkshops(user)) {
    return (
      <div>
        <PageHeader title="Workshop under inspection" icon={<FileSearch className="h-5 w-5" aria-hidden />} />
        <section className="panel px-6 py-14 text-center" aria-live="polite">
          <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-purple-50 text-purple-700">
            <Lock className="h-5 w-5" aria-hidden />
          </div>
          <h1 className="font-display text-xl font-bold tracking-tight text-ink-900">
            Inspector / Reviewer access required
          </h1>
          <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-ink-500">
            The inspection surface belongs to the Inspector / Reviewer tier. Designers and admins read design &amp;
            prototype workshops on Design workshops instead.
          </p>
          <p className="mt-3 text-xs text-ink-500">
            You are signed in as <span className="font-medium text-ink-700">{roleLabel(user?.role)}</span>.
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

  const readOnly = inspectionIsReadOnly(detail);
  /*
    ASKED, NOT ASSUMED. It is false on every payload this route can produce, and asking it is what
    stops the obvious edit — making each stage heading a link to the page a designer edits it on —
    from being written without anybody noticing that the link 404s. The nine destinations it covers
    are listed at `DESIGN_WORKSHOP_DESTINATIONS`.
  */
  const mayOpenTheWorkshop = inspectionMayOpen("stages", detail);

  const stages: DwStage[] = registry?.stages ?? [];

  return (
    <div>
      <PageHeader
        title={detail?.title?.trim() || "Workshop under inspection"}
        description={
          detail
            ? [detail.workshopCode, detail.craftName, detail.clusterName, detail.district, detail.state]
                .filter(Boolean)
                .join(" · ") || "No workshop code yet — stage 1 has not been saved."
            : undefined
        }
        icon={<FileSearch className="h-5 w-5" aria-hidden />}
        actions={detail ? <StatusBadge status={detail.status} /> : undefined}
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}
      {registryError ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-5 text-amber-800">
          {registryError}
        </div>
      ) : null}

      {/* THE READ IS DECLARED, not left to be inferred from the absence of buttons. A screen with no
          Save on it looks the same as a screen whose Save has not loaded yet. */}
      {detail && readOnly ? (
        <p className="mb-4 rounded-md border border-purple-300 bg-purple-50 px-3 py-2 text-xs leading-5 text-ink-700">
          <span className="font-semibold text-purple-700">Read-only.</span> This is an inspection: every stage below is
          shown as the designers recorded it, with who wrote each field, and nothing here can be edited, submitted or
          deleted. Photographs, recordings and attachments are not carried on an inspection read.
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

          {/*
            The one place this page would naturally link into the workshop tree, and the one place it
            must not. `mayOpenTheWorkshop` is false, so the alternative branch is what always renders;
            the `true` branch exists so the predicate has a consumer that a reader can see, and so
            that the day an inspection can be more than a read the change is here rather than nowhere.
          */}
          {mayOpenTheWorkshop ? (
            <p className="mb-4 text-sm">
              <Link className="text-purple-700 underline-offset-2 hover:underline" href={`/design-workshops/${id}`}>
                Open this workshop
              </Link>
            </p>
          ) : null}

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
