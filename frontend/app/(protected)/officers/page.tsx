"use client";

/**
 * WORKSHOP OVERSIGHT — who a design & prototype workshop is FOR, who supervises it, and its roster.
 *
 * Four panels over one chosen workshop: the picker, the designer, the two officer capacities, and
 * the artisan list. Everything on this page is the ASSIGNER's half of the sixth scope; the officer's
 * own half is `/officers/monitored`, which this account may not even be able to open.
 *
 * ── THE WORKSHOP PICKER IS NOT `DesignWorkshopSelect`, AND THAT IS A BUG FIX RATHER THAN A
 * PREFERENCE ─────────────────────────────────────────────────────────────────────────────────
 *
 * That component reads `GET /design-workshops`, which does not refuse a MINISTRY_ADMIN — it scopes
 * anybody who is not an admin to the VIEWER relation, which a Ministry Admin holds on no workshop.
 * The answer is a 200 with an empty page: a screen that says there are no workshops, in a repository
 * full of them, on the first control the primary user of this page touches. This page reads
 * `GET /design-workshop-oversight/workshops` instead, which is gated on the same predicate as the
 * page itself.
 *
 * ── FOUR PANELS, ONE WORKSHOP, AND NOTHING IS FETCHED UNTIL ONE IS CHOSEN ─────────────────────
 *
 * Every panel below the picker is scoped to the chosen workshop, so each renders a plain sentence
 * until there is one rather than an empty control that looks broken.
 *
 * ── THE TWO PICKERS ARE SERVER-SEARCHED, NOT CLIENT-FILTERED ─────────────────────────────────
 *
 * Both directories are capped server-side (2000 officers, 500 designers) and both take a `search`
 * parameter. A client-side filter over a server-truncated list answers "No matches" about records
 * that exist, which is this repository's most repeated bug class wearing a search box. So the box is
 * above the list, wired to the server, and the cut is stated when it happens.
 *
 * ── A MINISTRY ADMIN IS IN THE DIRECTORY AND FITS NEITHER SLOT ───────────────────────────────
 *
 * `capacities` on each row says which slots that account may hold, and it is EMPTY for a Ministry
 * Admin. The row is drawn disabled with the reason rather than omitted: a directory that answers
 * "who are the officers" and silently leaves out a third of them is a directory somebody will
 * conclude is broken, and a row that is offered and then 422s is worse than one that explains
 * itself.
 */

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { Lock, UserCheck } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { PageHeader } from "@/components/PageHeader";
import { SearchInput } from "@/components/SearchInput";
import { StatusBadge } from "@/components/StatusBadge";
import { DropCard } from "@/components/sketches/upload/DropCard";
import { ApiError } from "@/lib/api";
import { saveBlobToDisk, type DwSummary } from "@/lib/designWorkshops";
import { formatDate } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import { canAssignWorkshopOversight, roleLabel } from "@/lib/permissions";
import { ImportReport } from "./ImportReport";
import {
  ARTISAN_ACCEPT,
  CAPACITIES,
  CAPACITY_LABELS,
  OVERSIGHT_SEARCH_MAX,
  artisanUploadRefusal,
  downloadArtisanProForma,
  getWorkshopOversight,
  listArtisanImports,
  listAssignableDesigners,
  listAssignableWorkshops,
  listOfficers,
  putWorkshopDesigner,
  putWorkshopOversight,
  uploadArtisanList,
  type ArtisanImportReport,
  type DwArtisanImport,
  type DwAssignableDesigner,
  type DwOfficer,
  type DwOversightCapacity,
  type DwWorkshopOversight
} from "./oversight";

/** 300 ms, this app's number, for the reason every other debounced search here gives. */
const SEARCH_DEBOUNCE_MS = 300;

/**
 * The failures this page can suffer, told apart in words.
 *
 * `isUnreachable` rather than `isTransient`: a repository that ANSWERED and then refused must not be
 * reported as a connection problem, or an officer spends the afternoon restarting their router over
 * a 403.
 */
function describeFailure(error: unknown, subject: string): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) {
    return `This device cannot reach the repository, so ${subject} could not be loaded. Nothing was read at all — this is not an empty list.`;
  }
  return error.message;
}

/** A debounced value, so every search box on this page behaves identically. */
function useDebounced(value: string): string {
  const [applied, setApplied] = useState(value);
  useEffect(() => {
    const term = value.trim();
    // Clearing the box does NOT wait: an empty term is the unnarrowed list, the one request that is
    // always about to be wanted and never about to be superseded by the next letter.
    const timer = window.setTimeout(() => setApplied(term), term ? SEARCH_DEBOUNCE_MS : 0);
    return () => window.clearTimeout(timer);
  }, [value]);
  return applied;
}

export default function WorkshopOversightPage() {
  const { user, loading } = useAuth();
  const allowed = canAssignWorkshopOversight(user);

  const [workshop, setWorkshop] = useState<DwSummary | null>(null);
  const [error, setError] = useState<string | null>(null);

  if (!loading && !allowed) {
    return (
      <div>
        <PageHeader title="Workshop oversight" icon={<UserCheck className="h-5 w-5" aria-hidden />} />
        <section className="panel px-6 py-14 text-center" aria-live="polite">
          <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-purple-50 text-purple-700">
            <Lock className="h-5 w-5" aria-hidden />
          </div>
          <h1 className="font-display text-xl font-bold tracking-tight text-ink-900">
            Ministry Admin access required
          </h1>
          <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-ink-500">
            Naming the designer, the Assistant Director and the Regional Director on a design &amp;
            prototype workshop — and uploading that workshop&rsquo;s artisan list — is done by a
            Ministry Admin, an admin or the master admin. An Assistant Director or Regional Director
            reads the workshops they have been assigned on Workshops I monitor.
          </p>
          <p className="mt-3 text-xs text-ink-500">
            You are signed in as <span className="font-medium text-ink-700">{roleLabel(user?.role)}</span>.
          </p>
          <div className="mt-7 flex flex-wrap items-center justify-center gap-3">
            <Link href="/dashboard" className="field-button">
              Back to dashboard
            </Link>
            <Link href="/officers/monitored" className="field-button-secondary">
              Workshops I monitor
            </Link>
          </div>
        </section>
      </div>
    );
  }

  return (
    <div>
      <PageHeader
        title="Workshop oversight"
        description="Choose a design & prototype workshop, then say who it is for, who supervises it, and who is on its artisan roster."
        icon={<UserCheck className="h-5 w-5" aria-hidden />}
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      ) : null}

      <WorkshopPicker chosen={workshop} onChoose={setWorkshop} onError={setError} />

      {workshop ? (
        <>
          <DesignerPanel workshop={workshop} onError={setError} />
          <OversightPanel workshop={workshop} onError={setError} />
          <ArtisanListPanel workshop={workshop} onError={setError} />
        </>
      ) : (
        <section className="panel mt-4 p-4 text-sm text-ink-700">
          Choose a workshop above. Everything on this page is about one workshop at a time — who it
          is for, who supervises it, and who is on its roster.
        </section>
      )}
    </div>
  );
}

// --------------------------------------------------------------------------------------
// 1. The workshop
// --------------------------------------------------------------------------------------

function WorkshopPicker({
  chosen,
  onChoose,
  onError
}: {
  chosen: DwSummary | null;
  onChoose: (workshop: DwSummary | null) => void;
  onError: (message: string | null) => void;
}) {
  const [query, setQuery] = useState("");
  const applied = useDebounced(query);
  const [rows, setRows] = useState<DwSummary[] | null>(null);
  const [truncated, setTruncated] = useState(false);
  // A generation counter rather than an abort: `apiFetch` takes no `AbortSignal`, and what matters
  // is ignoring the late answer.
  const generation = useRef(0);

  useEffect(() => {
    const current = generation.current + 1;
    generation.current = current;
    listAssignableWorkshops({ page: 1, pageSize: 20, search: applied || undefined })
      .then((page) => {
        if (generation.current !== current) return;
        setRows(page.items);
        setTruncated(page.total > page.items.length);
        onError(null);
      })
      .catch((err) => {
        if (generation.current !== current) return;
        // The list is NOT emptied. An empty list under an error banner reads as "there are no
        // workshops", which is the one thing this control must never say by accident.
        onError(describeFailure(err, "the workshop list"));
      });
  }, [applied, onError]);

  return (
    <section className="panel p-4">
      <h2 className="font-display text-base font-bold tracking-tight text-ink-900">Workshop</h2>
      {chosen ? (
        <div className="mt-3 flex flex-wrap items-center gap-3 rounded-md border border-purple-200 bg-purple-50 px-3 py-2">
          <span className="min-w-0 flex-1">
            <span className="block truncate font-medium text-ink-900">
              {chosen.title?.trim() || "Untitled design workshop"}
            </span>
            <span className="block truncate text-xs text-ink-500">
              {chosen.workshopCode ?? "No workshop code yet"}
              {chosen.craftName ? ` · ${chosen.craftName}` : ""}
              {chosen.clusterName ? ` · ${chosen.clusterName}` : ""}
            </span>
          </span>
          <StatusBadge status={chosen.status} />
          <button type="button" className="field-button-secondary" onClick={() => onChoose(null)}>
            Choose a different workshop
          </button>
        </div>
      ) : (
        <>
          <div className="mt-3">
            <SearchInput
              onChange={(next) => setQuery(next.slice(0, OVERSIGHT_SEARCH_MAX))}
              onSubmit={() => setQuery(query.trim())}
              ariaLabel="Search design workshops"
              placeholder="Search by title, craft, cluster or workshop code"
              value={query}
            />
          </div>
          {rows === null ? (
            // null is "still asking" and [] is "genuinely none".
            <p className="mt-3 text-sm text-ink-700">Loading…</p>
          ) : rows.length === 0 ? (
            <p className="mt-3 text-sm text-ink-700">
              {applied
                ? "No workshop matches that search."
                : "There are no design & prototype workshops yet. An admin opens one from Design workshops."}
            </p>
          ) : (
            <ul className="mt-3 divide-y divide-line-200 rounded-md border border-line-200">
              {rows.map((row) => (
                <li key={row.id}>
                  <button
                    type="button"
                    className="flex w-full flex-col gap-1 px-3 py-2 text-left transition hover:bg-surface-50"
                    onClick={() => onChoose(row)}
                  >
                    <span className="truncate font-medium text-ink-900">
                      {row.title?.trim() || "Untitled design workshop"}
                    </span>
                    <span className="truncate text-xs text-ink-500">
                      {row.workshopCode ?? "No workshop code yet"}
                      {row.craftName ? ` · ${row.craftName}` : ""}
                      {row.startDate ? ` · ${formatDate(row.startDate)}` : ""}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
          {truncated ? (
            // SAID, ALWAYS. A list that quietly stops is indistinguishable from a place with no more
            // records, and this one stops at twenty.
            <p className="mt-2 text-xs text-ink-500">
              Only the twenty most recent workshops are listed. Search to reach the rest.
            </p>
          ) : null}
        </>
      )}
    </section>
  );
}

// --------------------------------------------------------------------------------------
// 2. The designer
// --------------------------------------------------------------------------------------

function DesignerPanel({
  workshop,
  onError
}: {
  workshop: DwSummary;
  onError: (message: string | null) => void;
}) {
  const [query, setQuery] = useState("");
  const applied = useDebounced(query);
  const [rows, setRows] = useState<DwAssignableDesigner[] | null>(null);
  const [truncated, setTruncated] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState<string | null>(null);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [designerName, setDesignerName] = useState<string | null>(workshop.designerName ?? null);
  const generation = useRef(0);

  useEffect(() => {
    setDesignerName(workshop.designerName ?? null);
    setSaved(null);
    setRefusal(null);
  }, [workshop.id, workshop.designerName]);

  useEffect(() => {
    const current = generation.current + 1;
    generation.current = current;
    listAssignableDesigners(applied || undefined)
      .then((list) => {
        if (generation.current !== current) return;
        setRows(list.users);
        setTruncated(list.truncated);
      })
      .catch((err) => {
        if (generation.current !== current) return;
        onError(describeFailure(err, "the designer list"));
      });
  }, [applied, onError]);

  async function choose(designer: DwAssignableDesigner) {
    setSaving(true);
    setRefusal(null);
    setSaved(null);
    try {
      const result = await putWorkshopDesigner(workshop.id, designer.id);
      setDesignerName(result.designerName ?? designer.name);
      setSaved(
        `${designer.name} is now this workshop's designer. Their profile was copied into ${
          result.stagesWritten.length === 1 ? "one stage" : `${result.stagesWritten.length} stages`
        } and they can now open the workshop.`
      );
    } catch (err) {
      // PANEL-LEVEL, not a toast and not the page banner: the refusal is about THIS panel's
      // control, the panel is tall, and `aria-live="polite"` never interrupts — which is exactly
      // wrong for something the reader must act on.
      setRefusal(err instanceof ApiError ? err.message : describeFailure(err, "that designer"));
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="panel mt-4 p-4">
      <h2 className="font-display text-base font-bold tracking-tight text-ink-900">Designer</h2>
      <p className="mt-1 text-sm text-ink-700">
        Currently:{" "}
        <span className="font-medium text-ink-900">
          {designerName?.trim() || "nobody is named on this workshop yet"}
        </span>
      </p>
      <p className="mt-1 text-xs leading-5 text-ink-500">
        Naming a designer gives them access to the workshop and copies their profile into stages 1
        and 3 — their name, institution, biography, experience and contact details. It does not
        overwrite anything else those stages already hold. A designer with no profile leaves those
        boxes empty rather than borrowing yours.
      </p>

      {refusal ? (
        <div className="mt-3 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {refusal}
        </div>
      ) : null}
      {saved ? (
        <div className="mt-3 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">
          {saved}
        </div>
      ) : null}

      <div className="mt-3">
        <SearchInput
          onChange={(next) => setQuery(next.slice(0, OVERSIGHT_SEARCH_MAX))}
          onSubmit={() => setQuery(query.trim())}
          ariaLabel="Search designers"
          placeholder="Search designers by name or email"
          value={query}
        />
      </div>

      {rows === null ? (
        <p className="mt-3 text-sm text-ink-700">Loading…</p>
      ) : rows.length === 0 ? (
        <p className="mt-3 text-sm text-ink-700">
          {applied
            ? "No designer matches that search."
            : "No account can be handed a workshop yet. An admin empanels designers on the designer roster."}
        </p>
      ) : (
        <ul className="mt-3 divide-y divide-line-200 rounded-md border border-line-200">
          {rows.map((row) => (
            <li key={row.id} className="flex flex-wrap items-center gap-3 px-3 py-2">
              <span className="min-w-0 flex-1">
                <span className="block truncate font-medium text-ink-900">{row.name}</span>
                <span className="block truncate text-xs text-ink-500">
                  {row.email} · {roleLabel(row.role)}
                </span>
              </span>
              <button
                type="button"
                className="field-button-secondary"
                disabled={saving}
                onClick={() => choose(row)}
              >
                Name as designer
              </button>
            </li>
          ))}
        </ul>
      )}
      {truncated ? (
        <p className="mt-2 text-xs text-ink-500">
          This list was cut. Search to reach designers further down the alphabet.
        </p>
      ) : null}
    </section>
  );
}

// --------------------------------------------------------------------------------------
// 3. The two capacities
// --------------------------------------------------------------------------------------

function OversightPanel({
  workshop,
  onError
}: {
  workshop: DwSummary;
  onError: (message: string | null) => void;
}) {
  const [current, setCurrent] = useState<DwWorkshopOversight | null>(null);
  const [query, setQuery] = useState("");
  const applied = useDebounced(query);
  const [officers, setOfficers] = useState<DwOfficer[] | null>(null);
  const [truncated, setTruncated] = useState(false);
  const [saving, setSaving] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);
  const generation = useRef(0);

  const reload = useCallback(() => {
    getWorkshopOversight(workshop.id)
      .then((detail) => {
        setCurrent(detail);
        onError(null);
      })
      .catch((err) => onError(describeFailure(err, "this workshop's oversight")));
  }, [workshop.id, onError]);

  useEffect(() => {
    setCurrent(null);
    setRefusal(null);
    reload();
  }, [reload]);

  useEffect(() => {
    const gen = generation.current + 1;
    generation.current = gen;
    listOfficers(applied || undefined)
      .then((list) => {
        if (generation.current !== gen) return;
        setOfficers(list.users);
        setTruncated(list.truncated);
      })
      .catch((err) => {
        if (generation.current !== gen) return;
        onError(describeFailure(err, "the officer list"));
      });
  }, [applied, onError]);

  /**
   * ONE CAPACITY PER REQUEST, and the body carries only that key.
   *
   * An omitted key leaves that capacity exactly as it stands; sending both every time would mean a
   * screen that can never say "leave the other one alone", so choosing an Assistant Director while
   * somebody else was naming a Regional Director would silently undo their work.
   */
  async function assign(capacity: DwOversightCapacity, userId: string | null) {
    setSaving(true);
    setRefusal(null);
    const body =
      capacity === "ASSISTANT_DIRECTOR"
        ? { assistantDirectorId: userId }
        : { regionalDirectorId: userId };
    try {
      const result = await putWorkshopOversight(workshop.id, body);
      // THE SERVER'S SET, NEVER AN ECHO OF WHAT WAS SENT — two administrators on one screen must not
      // each end up believing their own payload was the outcome.
      setCurrent((held) => (held ? { ...held, oversight: result.oversight } : held));
    } catch (err) {
      setRefusal(err instanceof ApiError ? err.message : describeFailure(err, "that officer"));
    } finally {
      setSaving(false);
    }
  }

  const held = new Map((current?.oversight ?? []).map((row) => [row.capacity, row]));

  return (
    <section className="panel mt-4 p-4">
      <h2 className="font-display text-base font-bold tracking-tight text-ink-900">
        Assistant Director and Regional Director
      </h2>
      <p className="mt-1 text-xs leading-5 text-ink-500">
        One of each per workshop. They can READ every stage of this workshop and change none of it —
        an oversight row is not access to the workshop, and it does not let them save anything. A
        Ministry Admin supervises the scheme rather than one workshop and cannot be named in either
        slot.
      </p>

      {refusal ? (
        <div className="mt-3 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {refusal}
        </div>
      ) : null}

      <div className="mt-3 grid gap-3 sm:grid-cols-2">
        {CAPACITIES.map((capacity) => {
          const row = held.get(capacity);
          return (
            <div key={capacity} className="rounded-md border border-line-200 bg-surface-50 px-3 py-2">
              <p className="field-label">{CAPACITY_LABELS[capacity]}</p>
              {current === null ? (
                <p className="mt-1 text-sm text-ink-700">Loading…</p>
              ) : row ? (
                <>
                  <p className="mt-1 truncate text-sm font-medium text-ink-900">{row.name}</p>
                  <p className="truncate text-xs text-ink-500">{row.email}</p>
                  <button
                    type="button"
                    className="field-button-secondary mt-2"
                    disabled={saving}
                    onClick={() => assign(capacity, null)}
                  >
                    Unassign
                  </button>
                </>
              ) : (
                <p className="mt-1 text-sm text-ink-700">Nobody is assigned.</p>
              )}
            </div>
          );
        })}
      </div>

      <div className="mt-3">
        <SearchInput
          onChange={(next) => setQuery(next.slice(0, OVERSIGHT_SEARCH_MAX))}
          onSubmit={() => setQuery(query.trim())}
          ariaLabel="Search officers"
          placeholder="Search officers by name or email"
          value={query}
        />
      </div>

      {officers === null ? (
        <p className="mt-3 text-sm text-ink-700">Loading…</p>
      ) : officers.length === 0 ? (
        <p className="mt-3 text-sm text-ink-700">
          {applied
            ? "No officer matches that search."
            : "No account holds one of the three ministry posts yet. An admin sets a role on Users."}
        </p>
      ) : (
        <ul className="mt-3 divide-y divide-line-200 rounded-md border border-line-200">
          {officers.map((officer) => (
            <li key={officer.id} className="flex flex-wrap items-center gap-2 px-3 py-2">
              <span className="min-w-0 flex-1">
                <span className="block truncate font-medium text-ink-900">{officer.name}</span>
                <span className="block truncate text-xs text-ink-500">
                  {officer.email} · {roleLabel(officer.role)}
                </span>
              </span>
              {officer.capacities.length === 0 ? (
                // DRAWN AND EXPLAINED rather than omitted. This directory answers "who are the
                // officers"; leaving a third of them out would read as a broken list, and offering
                // a row the PUT then 422s is worse than one that says why it cannot be chosen.
                <span className="text-xs text-ink-500">
                  Supervises the scheme rather than one workshop — cannot be named in either slot.
                </span>
              ) : (
                officer.capacities.map((capacity) => (
                  <button
                    key={capacity}
                    type="button"
                    className="field-button-secondary"
                    disabled={saving}
                    onClick={() => assign(capacity, officer.id)}
                  >
                    Name as {CAPACITY_LABELS[capacity]}
                  </button>
                ))
              )}
            </li>
          ))}
        </ul>
      )}
      {truncated ? (
        <p className="mt-2 text-xs text-ink-500">
          This list was cut. Search to reach officers further down the alphabet.
        </p>
      ) : null}
    </section>
  );
}

// --------------------------------------------------------------------------------------
// 4. The artisan list
// --------------------------------------------------------------------------------------

function ArtisanListPanel({
  workshop,
  onError
}: {
  workshop: DwSummary;
  onError: (message: string | null) => void;
}) {
  const [downloading, setDownloading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [report, setReport] = useState<ArtisanImportReport | null>(null);
  const [history, setHistory] = useState<DwArtisanImport[] | null>(null);

  const reloadHistory = useCallback(() => {
    listArtisanImports(workshop.id)
      .then((list) => setHistory(list.imports))
      .catch((err) => onError(describeFailure(err, "this workshop's imports")));
  }, [workshop.id, onError]);

  useEffect(() => {
    setReport(null);
    setRefusal(null);
    setHistory(null);
    reloadHistory();
  }, [reloadHistory]);

  async function download() {
    setDownloading(true);
    setRefusal(null);
    try {
      // THE WORKSHOP ID TRAVELS, ALWAYS. With it the Details sheet names the workshop and carries
      // the State, District and Venue that blank cells fall back to — and it is what the upload
      // checks against the URL, which is what stops a list being filed under the wrong workshop.
      const file = await downloadArtisanProForma(workshop.id);
      saveBlobToDisk(file.blob, file.fileName);
    } catch (err) {
      setRefusal(err instanceof ApiError ? err.message : describeFailure(err, "the pro-forma"));
    } finally {
      setDownloading(false);
    }
  }

  async function upload(files: File[]) {
    const [file] = files;
    if (!file) return;
    setUploading(true);
    setRefusal(null);
    setReport(null);
    try {
      const result = await uploadArtisanList(workshop.id, file);
      setReport(result);
      reloadHistory();
    } catch (err) {
      setRefusal(err instanceof ApiError ? err.message : describeFailure(err, "that artisan list"));
    } finally {
      setUploading(false);
    }
  }

  return (
    <section className="panel mt-4 p-4">
      <h2 className="font-display text-base font-bold tracking-tight text-ink-900">Artisan list</h2>
      <p className="mt-1 text-xs leading-5 text-ink-500">
        Download the pro-forma, type the list into it, and upload it. Every artisan becomes a record
        in the repository and a row in this workshop&rsquo;s stage 3 participant table. Nobody is
        created twice: anyone already recorded is linked to this workshop and their existing record
        is left exactly as it is.
      </p>
      <p className="mt-2 rounded-md border border-amber-100 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
        The pro-forma carries Aadhaar numbers, which are regulated personal data. Do not email the
        filled-in file or leave it in a shared folder, and delete it once the upload is confirmed.
        The workbook itself is never stored here — only its name, the counts, and the rows that could
        not be read.
      </p>

      <div className="mt-3 flex flex-wrap gap-2">
        <button type="button" className="field-button-secondary" disabled={downloading} onClick={download}>
          {downloading ? "Preparing…" : "Download the pro-forma"}
        </button>
      </div>

      {refusal ? (
        <div className="mt-3 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {refusal}
        </div>
      ) : null}

      <div className="mt-3">
        <DropCard
          label="Filled-in artisan list"
          buttonLabel="Choose the filled-in pro-forma"
          accept={ARTISAN_ACCEPT}
          acceptSentence="One Excel workbook (.xlsx), up to 4 MB."
          disabled={uploading}
          validate={artisanUploadRefusal}
          onFiles={upload}
        >
          {uploading ? <p className="text-sm text-ink-700">Reading the list…</p> : null}
        </DropCard>
      </div>

      {report ? <ImportReport report={report} /> : null}

      <h3 className="mt-4 font-display text-sm font-bold tracking-tight text-ink-900">
        Earlier uploads
      </h3>
      {history === null ? (
        <p className="mt-1 text-sm text-ink-700">Loading…</p>
      ) : history.length === 0 ? (
        <p className="mt-1 text-sm text-ink-700">
          No artisan list has been uploaded into this workshop yet.
        </p>
      ) : (
        <ul className="mt-1 divide-y divide-line-200 rounded-md border border-line-200">
          {history.map((entry) => (
            <li key={entry.id} className="px-3 py-2 text-sm">
              <span className="block truncate font-medium text-ink-900">
                {entry.sourceFilename ?? "An artisan list"}
              </span>
              <span className="block text-xs text-ink-500">
                {formatDate(entry.createdAt)}
                {entry.uploadedBy ? ` · ${entry.uploadedBy}` : ""} · {entry.rowsRead} rows read ·{" "}
                {entry.artisansCreated} created · {entry.artisansLinked} linked ·{" "}
                {entry.rowsRefused} refused
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
