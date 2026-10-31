"use client";

import { ClipboardList } from "lucide-react";
import Link from "next/link";
import { useEffect, useState } from "react";

import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { RecordingChips } from "@/components/questionnaire/RecordingChips";
import { chipHref, useChipNavigator } from "@/components/questionnaire/useUrlFilters";
import { questionnaireApi, type Annexure, type FormFor, type Recording } from "@/lib/questionnaire/api";
import { qs } from "@/lib/questionnaire/qs";
import { formHref, listForms, type PortalForm } from "@/lib/questionnairePortal";

/**
 * A design workshop's Questionnaires card (UNIFY 4.2): the forms bound to it, grouped by kind with
 * the primary form first; Start session (on the primary form, with this workshop picked); and the
 * report annexure as it will print (A12), every live sitting with its answers. Drawn only while the
 * shared questionnaire is on; the forms' own page attaches and binds them.
 */
export function WorkshopQuestionnairesCard({ designWorkshopId }: { designWorkshopId: string }) {
  const config = useQuestionnaireConfig();
  const on = Boolean(config?.v2Enabled);
  const [resolved, setResolved] = useState<FormFor | null>(null);
  const [forms, setForms] = useState<PortalForm[]>([]);
  const [annexure, setAnnexure] = useState<Annexure | null>(null);
  const [showAnnexure, setShowAnnexure] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [takes, setTakes] = useState<Recording[] | null>(null);
  const goChip = useChipNavigator();

  // The workshop's latest takes with their chips (owner, 31 Oct 2026); the rest are on Recordings.
  useEffect(() => {
    if (!on) return;
    let cancelled = false;
    void questionnaireApi
      .listRecordings({ designWorkshopId: [designWorkshopId] }, null, null, 5)
      .then((page) => !cancelled && setTakes(page.items))
      .catch(() => !cancelled && setTakes([]));
    return () => {
      cancelled = true;
    };
  }, [on, designWorkshopId]);

  useEffect(() => {
    if (!on) return;
    let cancelled = false;
    void Promise.all([questionnaireApi.formFor({ designWorkshopId }), listForms()])
      .then(([r, f]) => {
        if (cancelled) return;
        setResolved(r);
        setForms(f);
      })
      .catch((failure: unknown) => {
        if (!cancelled) setError(failure instanceof Error ? failure.message : String(failure));
      });
    return () => {
      cancelled = true;
    };
  }, [on, designWorkshopId]);

  useEffect(() => {
    if (!on || !showAnnexure || annexure) return;
    void questionnaireApi
      .annexure({ designWorkshopId })
      .then(setAnnexure)
      .catch((failure: unknown) => setError(failure instanceof Error ? failure.message : String(failure)));
  }, [on, showAnnexure, annexure, designWorkshopId]);

  if (!on) return null;
  const titleOf = (id: string) => forms.find((f) => f.id === id)?.title ?? id;
  const bound = resolved?.bound ?? [];
  const groups = new Map<string, typeof bound>();
  for (const b of bound) {
    const key = b.kind ?? "";
    groups.set(key, [...(groups.get(key) ?? []), b]);
  }
  const kindLabel = (kind: string) => (kind === "MARKET_SURVEY" ? qs("kindMarketSurvey") : kind === "WORKSHOP_INTERVIEW" ? qs("kindWorkshopInterview") : qs("kindNotStated"));
  const startOn = resolved?.questionnaireId ?? bound[0]?.questionnaireId ?? null;
  const startHref = `/questionnaire/sessions/new?designWorkshopId=${encodeURIComponent(designWorkshopId)}${startOn ? `&questionnaireId=${encodeURIComponent(startOn)}` : ""}`;

  return (
    <section className="panel mb-5 grid gap-3 p-4" data-workshop-questionnaires>
      <h2 className="flex items-center gap-2 font-display text-lg font-bold text-ink">
        <ClipboardList className="h-5 w-5 text-field-700 dark:text-purple-300" aria-hidden />
        {qs("questionnairesCard")}
      </h2>
      {error ? <p className="text-sm text-error-600">{error}</p> : null}
      {resolved && bound.length === 0 ? <p className="text-sm text-ink-500">{qs("noBoundForms")}</p> : null}
      {[...groups.entries()].map(([kind, items]) => (
        <div key={kind || "none"} className="grid gap-1">
          <h3 className="text-xs font-semibold uppercase tracking-wide text-ink-500">{kindLabel(kind)}</h3>
          <ul className="grid gap-1">
            {items.map((b) => (
              <li key={b.questionnaireId} className="flex flex-wrap items-center gap-2 text-sm">
                <Link className="font-medium text-purple-700 underline-offset-2 hover:underline dark:text-purple-300" href={formHref(b.questionnaireId)}>
                  {titleOf(b.questionnaireId)}
                </Link>
                {b.isPrimary ? <span className="rounded-full bg-purple-50 px-2 py-0.5 text-xs text-purple-700">{qs("primaryForm")}</span> : null}
              </li>
            ))}
          </ul>
        </div>
      ))}
      <ButtonRow>
        <Link className="field-button" href={startHref}>
          {qs("startSession")}
        </Link>
        <button type="button" className="field-button-secondary" aria-expanded={showAnnexure} onClick={() => setShowAnnexure(!showAnnexure)}>
          {qs("annexureTitle")}
        </button>
        <Link className="field-button-secondary" href={chipHref({ key: "designWorkshopId", value: designWorkshopId })} data-workshop-recordings>
          {qs("recordingsTitle")}
        </Link>
      </ButtonRow>
      {takes && takes.length ? (
        <ul className="grid gap-2" data-workshop-takes>
          {takes.map((r) => (
            <li key={r.id} className="rounded-md border border-line-200 p-2">
              <RecordingChips chips={r.chips} onChip={goChip} />
            </li>
          ))}
        </ul>
      ) : null}
      {showAnnexure ? (
        annexure ? (
          <div className="grid gap-3" data-annexure-preview>
            {annexure.groups.length === 0 ? <p className="text-sm text-ink-500">{qs("noSessionsYet")}</p> : null}
            {annexure.groups.map((g) => (
              <div key={g.kind ?? "none"} className="grid gap-2">
                <h3 className="text-sm font-semibold text-ink-900">{g.label}</h3>
                {g.forms.map((f) => (
                  <div key={f.questionnaireId} className="rounded-md border border-line-200 p-3">
                    <p className="text-sm font-medium text-ink-900">{f.title}</p>
                    {f.sittings.map((s) => (
                      <div key={s.id} className="mt-2">
                        <p className="text-xs font-semibold text-ink-700">{s.label}</p>
                        <dl className="mt-1 grid gap-1 text-sm">
                          {s.answers.map((a, i) => (
                            <div key={`${s.id}-${i}`}>
                              <dt className="text-ink-500">{[a.sectionCode, a.prompt].filter(Boolean).join(" · ")}</dt>
                              <dd className="text-ink-900">{a.answer || "—"}</dd>
                            </div>
                          ))}
                        </dl>
                      </div>
                    ))}
                  </div>
                ))}
              </div>
            ))}
          </div>
        ) : (
          <p className="text-sm text-ink-500">{qs("loading")}</p>
        )
      ) : null}
    </section>
  );
}
