"use client";

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { BadgeCheck, MailPlus, RotateCcw, ShieldCheck } from "lucide-react";

import { OpenableRow } from "@/components/data/OpenableRow";
import { EmptyState } from "@/components/EmptyState";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { PageHeader } from "@/components/PageHeader";
import { RecordDetailsDialog } from "@/components/records/RecordDetailsDialog";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RestrictedPanel } from "@/components/settings/RestrictedPanel";
import { RosterFilterBar } from "@/components/admin/RosterFilterBar";
import { RowActions, rowAction } from "@/components/RowActions";
import { SortableTh, type RosterSortControl } from "@/components/admin/SortableTh";
import { useAuth } from "@/components/AuthProvider";
import { useConfirm } from "@/components/dialogs/ConfirmDialog";
import { CUT_NOTICE_LIVE_REGION, searchCutNotice } from "@/components/data/cappedList";
import {
  clearRosterFilters,
  hasActiveRosterFilters,
  rosterFiltersFromSearchParams,
  rosterLinkParams,
  rosterQueryParams,
  type RosterFilters
} from "@/components/admin/rosterFilters";
import { ApiError, buildQuery } from "@/lib/api";
import {
  addDesignerToRoster,
  listDesignerDirectory,
  restoreDesignerRosterEntry,
  rosterInvitationLabel,
  rosterUpdateBody,
  suspendDesignerRosterEntry,
  updateDesignerRosterEntry,
  type DesignerRosterEntry
} from "@/lib/designers";
import { formatDate, formatDateTime } from "@/lib/format";
import { canManageDesignerRoster, roleLabel } from "@/lib/permissions";
import {
  isDerivedEmpanelment,
  listDesignerRosterInstitutions,
  listDesignerRosterPage,
  type DesignerRosterPage
} from "./rosterQuery";
import { ActionGroup } from "@/components/ui/ActionGroup";

const PAGE_SIZE = 20;

const DIRECTORY_CAP = 500;

const ROSTER_PATH = "/admin/designers";

const ROSTER_READ_FAILED =
  "Couldn't load the roster. Try again.";

const NOBODY_EMPANELLED =
  "No designers yet. Add one above by email.";

const NARROWED_TO_NOTHING =
  "No matches. Clear the filters to see everyone.";

const INSTITUTIONS_READ_FAILED =
  "Institutions didn't load — search by name instead.";

const INSTITUTIONS_EMPTY_FAILED =
  "Institutions didn't load — search by name instead.";

const INSTITUTIONS_EMPTY_NONE =
  "No institutions recorded yet.";

export default function DesignerRosterPage() {
  return (
    <Suspense fallback={<div className="panel p-4 text-sm text-ink-500">Loading the roster…</div>}>
      <DesignerRosterPageInner />
    </Suspense>
  );
}

function DesignerRosterPageInner() {
  const { user } = useAuth();
  const confirm = useConfirm();
  const permitted = canManageDesignerRoster(user);
  const searchParams = useSearchParams();

  const [filters, setFilters] = useState<RosterFilters>(() =>
    rosterFiltersFromSearchParams("designer", searchParams)
  );
  const [data, setData] = useState<DesignerRosterPage | null>(null);
  const [listFailed, setListFailed] = useState(false);
  const [searchPending, setSearchPending] = useState(false);
  const [page, setPage] = useState(1);
  const [editing, setEditing] = useState<DesignerRosterEntry | null>(null);
  const [viewing, setViewing] = useState<DesignerRosterEntry | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [accounts, setAccounts] = useState<Map<string, string>>(new Map());
  const [directoryCapped, setDirectoryCapped] = useState(false);
  const [institutions, setInstitutions] = useState<string[]>([]);
  const [institutionsTruncated, setInstitutionsTruncated] = useState<boolean | undefined>(undefined);
  const [institutionsLoading, setInstitutionsLoading] = useState(true);
  const [institutionsFailed, setInstitutionsFailed] = useState(false);
  const formRef = useRef<HTMLFormElement | null>(null);

  const generation = useRef(0);
  const institutionGeneration = useRef(0);

  const applyFilters = useCallback((next: RosterFilters) => {
    setFilters(next);
    setPage(1);
  }, []);

  const sortControl: RosterSortControl = { kind: "designer", filters, onChange: applyFilters };

  useEffect(() => {
    window.history.replaceState(
      null,
      "",
      `${ROSTER_PATH}${buildQuery(rosterLinkParams("designer", filters))}`
    );
  }, [filters]);

  const load = useCallback(async () => {
    if (!permitted) return;
    const mine = ++generation.current;
    try {
      const result = await listDesignerRosterPage({
        page,
        pageSize: PAGE_SIZE,
        ...rosterQueryParams("designer", filters)
      });
      if (mine !== generation.current) return;
      setData(result);
      setListFailed(false);
      setError(null);
    } catch (err) {
      if (mine !== generation.current) return;
      setListFailed(true);
      setError(err instanceof Error ? err.message : "Unable to load the designer roster");
    }
  }, [filters, page, permitted]);

  useEffect(() => {
    load();
  }, [load]);

  const loadInstitutions = useCallback(async () => {
    if (!permitted) return;
    const mine = ++institutionGeneration.current;
    setInstitutionsLoading(true);
    try {
      const result = await listDesignerRosterInstitutions();
      if (mine !== institutionGeneration.current) return;
      setInstitutions(result.items);
      setInstitutionsTruncated(result.truncated);
      setInstitutionsFailed(false);
    } catch {
      if (mine !== institutionGeneration.current) return;
      setInstitutions([]);
      setInstitutionsTruncated(undefined);
      setInstitutionsFailed(true);
    } finally {
      if (mine === institutionGeneration.current) setInstitutionsLoading(false);
    }
  }, [permitted]);

  useEffect(() => {
    loadInstitutions();
  }, [loadInstitutions]);

  useEffect(() => {
    if (!permitted) return;
    let cancelled = false;
    listDesignerDirectory({ includeSuspended: true })
      .then((rows) => {
        if (cancelled) return;
        setAccounts(new Map(rows.map((row) => [row.email.toLowerCase(), row.id])));
        setDirectoryCapped(rows.length >= DIRECTORY_CAP);
      })
      .catch(() => {
      });
    return () => {
      cancelled = true;
    };
  }, [permitted]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const email = String(form.get("email") ?? "").trim();
    const fullName = String(form.get("fullName") ?? "");
    const institution = String(form.get("institution") ?? "");
    const notes = String(form.get("notes") ?? "");
    if (!email) return;

    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      if (editing) {
        const updated = await updateDesignerRosterEntry(
          editing.id,
          rosterUpdateBody({ email, fullName, institution, notes })
        );
        setNotice(`Updated the roster entry for ${updated.email}.`);
        setEditing(null);
      } else {
        const created = await addDesignerToRoster({
          email,
          fullName: fullName.trim() || null,
          institution: institution.trim() || null,
          notes: notes.trim() || null
        });
        setNotice(
          `${created.email} added. They can sign in with Google.`
        );
      }
      formElement.reset();
      await load();
      await loadInstitutions();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save this roster entry");
      if (err instanceof ApiError && err.status === 409) {
        applyFilters({ ...clearRosterFilters("designer", filters), search: email });
      }
    } finally {
      setSaving(false);
    }
  }

  async function suspend(entry: DesignerRosterEntry) {
    const ok = await confirm({
      title: `Suspend ${entry.fullName || entry.email}?`,
      body: "They lose access until restored.",
      note:
        "Their work is kept.",
      confirmLabel: "Suspend",
      tone: "danger"
    });
    if (!ok) return;
    try {
      const updated = await suspendDesignerRosterEntry(entry.id);
      setNotice(
        `${updated.email} is suspended${updated.revokedAt ? ` as of ${formatDate(updated.revokedAt)}` : ""}. The entry stays on the roster.`
      );
      setError(null);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to suspend this roster entry");
    }
  }

  async function restore(entry: DesignerRosterEntry) {
    const ok = await confirm({
      title: `Restore ${entry.fullName || entry.email}?`,
      body: "They will be able to sign in again immediately.",
      note: entry.revokedAt
        ? `They were suspended on ${formatDate(entry.revokedAt)}. Restoring clears that date.`
        : undefined,
      confirmLabel: "Restore",
      tone: "warning"
    });
    if (!ok) return;
    try {
      const updated = await restoreDesignerRosterEntry(entry.id);
      setNotice(`${updated.email} can sign in again.`);
      setError(null);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to restore this roster entry");
    }
  }

  function startEdit(entry: DesignerRosterEntry) {
    setEditing(entry);
    setError(null);
    setNotice(null);
    requestAnimationFrame(() => formRef.current?.scrollIntoView({ block: "start", behavior: "smooth" }));
  }

  const header = (
    <PageHeader
      title="Designer roster"
      description="Designers who may sign in."
      icon={<BadgeCheck className="h-5 w-5" aria-hidden />}
    />
  );

  if (!permitted) {
    return (
      <>
        {header}
        <RestrictedPanel
          title="Admin access required"
          body={
            `Admins only.`
          }
        />
      </>
    );
  }

  const rows = data?.items ?? [];
  const empty = rosterEmptySentence({
    loaded: rows.length,
    total: data?.total ?? 0,
    term: filters.search,
    narrowed: hasActiveRosterFilters("designer", filters),
    pending: searchPending
  });

  return (
    <>
      {header}

      <p className="mb-4 text-sm leading-6 text-ink-500">
        This roster lists who may sign in as a <em>designer</em>. Whether an address may sign in at all is decided
        on{" "}
        <Link href="/admin/access" className="font-medium text-purple-700 dark:text-purple-300 hover:underline">
          Who may sign in
        </Link>
        . The two lists stay in step.
      </p>

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}
      {notice ? (
        <div className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">{notice}</div>
      ) : null}

      <form
        ref={formRef}
        onSubmit={submit}
        key={editing?.id ?? "new"}
        className="panel mb-5 grid gap-4 p-4"
      >
        <div>
          <h2 className="font-display text-lg font-bold text-ink-900">
            {editing ? `Correct the entry for ${editing.email}` : "Add a designer to the roster"}
          </h2>
          <p className="mt-1 text-sm leading-6 text-ink-muted">
            {editing
              ? "For your records only. Use Suspend or Restore to change access."
              : "Only the email is required."}
          </p>
        </div>
        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-3">
          <Field label="Email address" required>
            <TextInput
              name="email"
              type="email"
              required
              defaultValue={editing?.email ?? ""}
              placeholder="designer@institution.ac.in"
              autoComplete="off"
            />
          </Field>
          <Field label="Full name">
            <TextInput name="fullName" maxLength={180} defaultValue={editing?.fullName ?? ""} />
          </Field>
          <Field label="Institution">
            <TextInput name="institution" maxLength={180} defaultValue={editing?.institution ?? ""} />
          </Field>
        </div>
        <Field label="Note">
          <TextArea
            name="notes"
            maxLength={4000}
            rows={2}
            defaultValue={editing?.notes ?? ""}
            placeholder="Which programme they were empanelled under, and when."
          />
        </Field>
        <ActionGroup>
          <button className="field-button" disabled={saving}>
            <MailPlus className="h-4 w-4" aria-hidden />
            {saving ? "Saving…" : editing ? "Update entry" : "Add to roster"}
          </button>
          {editing ? (
            <button type="button" className="field-button-secondary" onClick={() => setEditing(null)}>
              Cancel
            </button>
          ) : null}
        </ActionGroup>
      </form>

      <RosterFilterBar
        kind="designer"
        filters={filters}
        onChange={applyFilters}
        institutions={institutions}
        institutionsTruncated={institutionsTruncated}
        institutionsLoading={institutionsLoading}
        institutionsEmptyLabel={
          institutionsLoading
            ? undefined
            : institutionsFailed
              ? INSTITUTIONS_EMPTY_FAILED
              : INSTITUTIONS_EMPTY_NONE
        }
        roleMatchTruncated={data?.roleMatchTruncated}
        onSearchPendingChange={setSearchPending}
      />

      {institutionsFailed ? (
        <p className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
          {INSTITUTIONS_READ_FAILED}
        </p>
      ) : null}

      {directoryCapped ? (
        <p className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
          “Open profile” may be missing for some entries (first {DIRECTORY_CAP} accounts matched).
        </p>
      ) : null}

      <div {...CUT_NOTICE_LIVE_REGION} className="sr-only">
        {data !== null && rows.length === 0 ? empty.body : ""}
      </div>

      <section className="panel overflow-hidden">
        {data === null ? (
          listFailed ? (
            <div className="p-4">
              <EmptyState title="The roster could not be loaded" body={ROSTER_READ_FAILED} />
            </div>
          ) : (
            <div className="p-4 text-sm text-ink-700">Loading the roster…</div>
          )
        ) : rows.length === 0 ? (
          searchPending ? (
            <div className="p-4 text-sm text-ink-700">Searching the roster…</div>
          ) : (
            <div className="p-4">
              <EmptyState title={empty.title} body={empty.body} />
            </div>
          )
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1120px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <SortableTh control={sortControl} column="name" label="Designer" />
                  <SortableTh control={sortControl} column="institution" label="Institution" />
                  <ResizableTh>May sign in</ResizableTh>
                  <SortableTh control={sortControl} column="firstSeen" label="Invitation" />
                  <SortableTh control={sortControl} column="added" label="Added" />
                  <ResizableTh>Note</ResizableTh>
                  <ResizableTh className="text-right">Actions</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {rows.map((entry) => {
                  const accountId = accounts.get(entry.email.toLowerCase());
                  const derived = isDerivedEmpanelment(entry);
                  return (
                    <OpenableRow key={entry.id} onOpen={() => setViewing(entry)}>
                      <td className="px-4 py-3">
                        <button
                          type="button"
                          onClick={() => setViewing(entry)}
                          className="rounded-sm text-left font-medium text-ink-900 underline-offset-2 hover:underline"
                        >
                          {entry.fullName || <span className="text-ink-500">Name not recorded</span>}
                        </button>
                        <div className="text-xs text-ink-500">{entry.email}</div>
                      </td>
                      <td className="px-4 py-3 text-ink-700">{entry.institution ?? "—"}</td>
                      <td className="px-4 py-3">
                        <StandingChip entry={entry} />
                      </td>
                      <td className="px-4 py-3 text-ink-700">
                        {rosterInvitationLabel(entry)}
                        {entry.firstSeenAt ? (
                          <span className="block text-xs text-ink-500">{formatDateTime(entry.firstSeenAt)}</span>
                        ) : null}
                      </td>
                      <td className="px-4 py-3 text-ink-700">
                        {entry.createdAt ? formatDate(entry.createdAt) : "—"}
                        {derived ? (
                          <span className="mt-1 flex w-fit items-center gap-1 rounded-full border border-line-200 bg-surface-50 px-2 py-0.5 text-[11px] font-medium text-ink-700">
                            <ShieldCheck className="h-3 w-3 shrink-0 text-purple-700 dark:text-purple-300" aria-hidden />
                            Automatically, from Who may sign in
                          </span>
                        ) : null}
                      </td>
                      <td className="max-w-xs px-4 py-3 text-ink-700">
                        {entry.notes ? <span className="line-clamp-3 whitespace-pre-line">{entry.notes}</span> : "—"}
                      </td>
                      <td className="px-4 py-3 text-right">
                        <RowActions>
                          <button type="button" className={rowAction("edit")} onClick={() => startEdit(entry)}>
                            Update
                          </button>
                          {accountId ? (
                            <Link className={rowAction("neutral")} href={`/designers/${accountId}/profile`}>
                              Open profile
                            </Link>
                          ) : null}
                          {entry.isActive ? (
                            <button type="button" data-danger className={rowAction("danger")} onClick={() => suspend(entry)}>
                              Suspend
                            </button>
                          ) : (
                            <button type="button" className={rowAction("positive")} onClick={() => restore(entry)}>
                              <RotateCcw className="h-3.5 w-3.5" aria-hidden />
                              Restore
                            </button>
                          )}
                        </RowActions>
                      </td>
                    </OpenableRow>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>
      <RecordDetailsDialog
        open={viewing !== null}
        onClose={() => setViewing(null)}
        title={viewing?.fullName || "Name not recorded"}
        description={viewing?.email}
        details={
          viewing
            ? [
                ["Email", viewing.email],
                ["Institution", viewing.institution ?? "Not recorded"],
                [
                  "May sign in",
                  viewing.isActive
                    ? "Yes"
                    : `Suspended ${viewing.revokedAt ? `since ${formatDate(viewing.revokedAt)}` : "(date not recorded)"}`
                ],
                [
                  "Invitation",
                  `${rosterInvitationLabel(viewing)}${viewing.firstSeenAt ? `, ${formatDateTime(viewing.firstSeenAt)}` : ""}`
                ],
                ["Added", viewing.createdAt ? formatDate(viewing.createdAt) : "Not recorded"],
                [
                  "Added by",
                  isDerivedEmpanelment(viewing) ? "Automatically, from Who may sign in" : (viewing.addedByName ?? null)
                ],
                ["Note", viewing.notes]
              ]
            : []
        }
        onUpdate={() => {
          const target = viewing;
          setViewing(null);
          if (target) startEdit(target);
        }}
      />
    </>
  );
}

function rosterEmptySentence(args: {
  loaded: number;
  total: number;
  term: string;
  narrowed: boolean;
  pending: boolean;
}): { title: string; body: string } {
  const term = args.term.trim();
  const body = searchCutNotice({
    noun: "designers",
    loaded: args.loaded,
    total: args.total,
    term: args.term,
    pending: args.pending,
    emptyLabel: args.narrowed ? NARROWED_TO_NOTHING : NOBODY_EMPANELLED
  });
  if (args.loaded === 0 && args.total > 0) {
    return { title: "This page of the roster is empty", body };
  }
  if (term) return { title: "No roster entries match this search", body };
  if (args.narrowed) return { title: "No roster entries match these filters", body };
  return { title: "Nobody has been empanelled yet", body };
}

function StandingChip({ entry }: { entry: DesignerRosterEntry }) {
  if (entry.isActive) {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-full border border-success-600/25 bg-success-100 px-2.5 py-1 text-xs font-medium text-success-600">
        <BadgeCheck className="h-3.5 w-3.5" aria-hidden />
        May sign in
      </span>
    );
  }
  return (
    <span className="inline-flex flex-col gap-0.5">
      <span className="inline-flex w-fit items-center gap-1.5 rounded-full border border-error-600/25 bg-error-100 px-2.5 py-1 text-xs font-medium text-red-800">
        Suspended
      </span>
      <span className="text-xs text-ink-500">
        {entry.revokedAt ? `since ${formatDate(entry.revokedAt)}` : "date not recorded"}
      </span>
    </span>
  );
}
