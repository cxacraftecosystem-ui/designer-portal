"use client";

import { chipToggler, FilterBar } from "@/components/questionnaire/FilterBar";
import { RecordingChips } from "@/components/questionnaire/RecordingChips";
import { useUrlFilters } from "@/components/questionnaire/useUrlFilters";
import { cleanFilters } from "@/lib/questionnaire/filters";
import Link from "next/link";
import { useEffect, useMemo, useState } from "react";

import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { Toggle } from "@/components/ui/Toggle";
import { formatDate } from "@/lib/format";
import { isAdmin as isAdminUser } from "@/lib/permissions";
import { questionnaireApi, type Binding, type Session } from "@/lib/questionnaire/api";
import { qs } from "@/lib/questionnaire/qs";
import { setDefaultQuestionnaire } from "@/lib/questionnaireForms";
import { listWorkshops, type PortalWorkshop } from "@/lib/questionnairePortal";

/**
 * A form's admin settings (UNIFY A9, B31, B32, D2; owner amendment 2): the design workshops it is
 * bound to and where it is the PRIMARY form (one per workshop), the default form for a design
 * workshop kind, and the global default. Admin acts, web only on this portal (ministry/admin
 * authoring); the server refuses anyone else whatever this draws. Drawn only while the shared
 * questionnaire is on.
 */
export function FormAdminPanel({ questionnaireId, isDefault }: { questionnaireId: string; isDefault?: boolean }) {
  const { user } = useAuth();
  const isAdmin = isAdminUser(user);
  const on = Boolean(useQuestionnaireConfig()?.v2Enabled);
  const [bindings, setBindings] = useState<Binding[] | null>(null);
  const [workshops, setWorkshops] = useState<PortalWorkshop[]>([]);
  const [adding, setAdding] = useState("");
  const [kind, setKind] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [isGlobalDefault, setGlobalDefault] = useState(Boolean(isDefault));
  useEffect(() => setGlobalDefault(Boolean(isDefault)), [isDefault]);

  useEffect(() => {
    if (!on || !isAdmin) return;
    void questionnaireApi
      .bindings(questionnaireId)
      .then((r) => setBindings(r.bindings))
      .catch(() => setBindings([]));
    void listWorkshops()
      .then(setWorkshops)
      .catch(() => setWorkshops([]));
  }, [on, isAdmin, questionnaireId]);

  const kinds = useMemo(() => [...new Set(workshops.map((w) => w.kind).filter((k): k is string => Boolean(k)))].sort(), [workshops]);
  if (!on || !isAdmin) return null;
  const titleOf = (id: string | null) => workshops.find((w) => w.id === id)?.title ?? id ?? "";

  async function save(next: Binding[]) {
    setBusy(true);
    setMessage(null);
    try {
      const out = await questionnaireApi.putBindings(questionnaireId, next);
      setBindings(out.bindings);
    } catch (failure) {
      setMessage(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
    }
  }

  const current = bindings ?? [];
  return (
    <section className="panel mb-5 grid gap-3 p-4" data-form-admin>
      <h2 className="font-display text-lg font-bold text-ink">{qs("bindings")}</h2>
      {current.length ? (
        <ul className="grid gap-2">
          {current.map((b) => (
            <li key={b.designWorkshopId ?? b.workshopId ?? ""} className="flex flex-wrap items-center justify-between gap-2 text-sm" data-binding={b.designWorkshopId}>
              <span className="text-ink-900">{titleOf(b.designWorkshopId ?? b.workshopId)}</span>
              <div className="flex min-w-0 items-center gap-2">
                <Toggle
                  label={qs("primaryForm")}
                  checked={b.isPrimary}
                  disabled={busy}
                  onChange={(primary) => void save(current.map((x) => (x === b ? { ...x, isPrimary: primary } : x)))}
                />
                <button type="button" className="text-sm font-medium text-error-600 underline" disabled={busy} onClick={() => void save(current.filter((x) => x !== b))}>
                  {qs("remove")}
                </button>
              </div>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-sm text-ink-500">{qs("noBoundForms")}</p>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <div className="min-w-0 flex-1">
          <SearchableSelect
            value={adding}
            onChange={setAdding}
            options={workshops.filter((w) => !current.some((b) => b.designWorkshopId === w.id)).map((w) => ({ value: w.id, label: w.title }))}
            searchable
            advanceOnSelect={false}
            ariaLabel={qs("bindings")}
          />
        </div>
        <button
          type="button"
          className="field-button-secondary"
          disabled={!adding || busy}
          onClick={() => {
            const next = [...current, { workshopId: null, designWorkshopId: adding, isPrimary: false }];
            setAdding("");
            void save(next);
          }}
        >
          {qs("add")}
        </button>
      </div>

      <h3 className="mt-2 text-sm font-semibold text-ink-900">{qs("typeDefault")}</h3>
      <div className="flex flex-wrap items-center gap-2">
        <div className="min-w-0 flex-1">
          <SearchableSelect value={kind} onChange={setKind} options={kinds.map((k) => ({ value: k, label: k }))} ariaLabel={qs("typeDefault")} advanceOnSelect={false} />
        </div>
        <button
          type="button"
          className="field-button-secondary"
          disabled={!kind || busy}
          onClick={() => {
            setBusy(true);
            void questionnaireApi
              .putTypeDefault({ questionnaireId, designWorkshopKind: kind })
              .then(() => setMessage(`${qs("typeDefault")}: ${kind}`))
              .catch((failure: unknown) => setMessage(failure instanceof Error ? failure.message : String(failure)))
              .finally(() => setBusy(false));
          }}
        >
          {qs("save")}
        </button>
      </div>
      {/* B32: the global default — the form a session gets when its workshop has none bound. */}
      <Toggle
        label={qs("defaultFormToggle")}
        description={qs("defaultFormNote")}
        checked={isGlobalDefault}
        disabled={busy}
        onChange={(next: boolean) => {
          setBusy(true);
          setMessage(null);
          void setDefaultQuestionnaire(questionnaireId, next)
            .then((form) => setGlobalDefault(Boolean(form.isDefault)))
            .catch((failure: unknown) => setMessage(failure instanceof Error ? failure.message : String(failure)))
            .finally(() => setBusy(false));
        }}
      />

      {/* B29: the corpus of this form's approved answers and recordings, for an admin's dataset work. */}
      <h3 className="mt-2 text-sm font-semibold text-ink-900">{qs("corpusTitle")}</h3>
      <ButtonRow size="sm" align="start">
        <a className="field-button-secondary" href={corpusHref(questionnaireId, "answers")} download data-corpus="answers">
          {qs("corpusAnswers")}
        </a>
        <a className="field-button-secondary" href={corpusHref(questionnaireId, "recordings")} download data-corpus="recordings">
          {qs("corpusRecordings")}
        </a>
      </ButtonRow>
      {message ? <p className="text-sm text-ink-700">{message}</p> : null}
    </section>
  );
}

/** Sessions on this form (UNIFY 4.2): the live ones, and the deleted ones for content admins. */
export function FormSessions({ questionnaireId }: { questionnaireId: string }) {
  const on = Boolean(useQuestionnaireConfig()?.v2Enabled);
  const { canDeleteRecords } = useAdminView();
  const [items, setItems] = useState<Session[] | null>(null);
  const [deleted, setDeleted] = useState<Session[] | null>(null);
  // The shared filters on this form's sessions (owner, 31 Oct 2026), kept in the page's URL.
  const { filters, setFilters, ready } = useUrlFilters();
  useEffect(() => {
    if (!on || !ready) return;
    let cancelled = false;
    void questionnaireApi
      .sessions({ ...filters, questionnaireId: [questionnaireId] }, { limit: 50 })
      .then((page) => !cancelled && setItems(page.items))
      .catch(() => !cancelled && setItems([]));
    return () => {
      cancelled = true;
    };
  }, [on, ready, filters, questionnaireId]);
  useEffect(() => {
    if (!on) return;
    if (canDeleteRecords)
      void questionnaireApi
        .deletedSessions({ questionnaireId, limit: 50 })
        .then((page) => setDeleted(page.items))
        .catch(() => setDeleted([]));
  }, [on, questionnaireId, canDeleteRecords]);
  if (!on) return null;
  return (
    <section className="panel mb-5 grid gap-3 p-4" data-form-sessions>
      <h2 className="font-display text-lg font-bold text-ink">{qs("sessionsOnForm")}</h2>
      <FilterBar filters={filters} onChange={setFilters} placeholder={qs("filterSearchSessions")} hide={["questionnaireId", "kind"]} />
      {items === null ? <p className="text-sm text-ink-500">{qs("loading")}</p> : null}
      {items && items.length === 0 ? (
        <p className="text-sm text-ink-500">{Object.keys(cleanFilters(filters)).length ? qs("filterNoMatches") : qs("noSessionsYet")}</p>
      ) : null}
      <ul className="grid gap-2" data-form-session-list>
        {(items ?? []).map((s) => (
          <li key={s.id} className="grid gap-1 text-sm">
            <div>
              <Link className="font-medium text-purple-700 underline-offset-2 hover:underline dark:text-purple-300" href={`/questionnaire/sessions/${encodeURIComponent(s.id)}/view`}>
                {s.title}
              </Link>
              <span className="ml-2 text-xs text-ink-500">{formatDate(s.sessionDate)}</span>
            </div>
            <RecordingChips chips={s.chips} variant="session" onChip={chipToggler(filters, setFilters)} filters={filters} />
          </li>
        ))}
      </ul>
      {deleted && deleted.length ? (
        <div className="grid gap-1" data-form-deleted-sessions>
          <h3 className="text-sm font-semibold text-ink-900">{qs("deletedSessions")}</h3>
          {deleted.map((s) => (
            <div key={s.id} className="flex flex-wrap items-center justify-between gap-2 text-sm">
              <span className="text-ink-700">{s.title}</span>
              <ButtonRow size="sm" align="end">
                <button
                  type="button"
                  className="field-button-secondary"
                  onClick={() =>
                    void questionnaireApi.restoreSession(s.id).then(() => {
                      setDeleted((list) => (list ?? []).filter((x) => x.id !== s.id));
                      setItems((list) => [s, ...(list ?? [])]);
                    })
                  }
                >
                  {qs("restore")}
                </button>
              </ButtonRow>
            </div>
          ))}
        </div>
      ) : null}
    </section>
  );
}

/** `GET /questionnaire/corpus.csv`: one file of answers or of recordings, approved by default. */
export function corpusHref(questionnaireId: string, kind: "answers" | "recordings"): string {
  return `/api/questionnaire/corpus.csv?${new URLSearchParams({ questionnaireId, kind }).toString()}`;
}
