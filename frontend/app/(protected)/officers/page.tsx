"use client";

import Link from "next/link";
import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { Lock, Plus, Save, UserCheck } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { PageHeader } from "@/components/PageHeader";
import { SearchInput } from "@/components/SearchInput";
import { StatusBadge } from "@/components/StatusBadge";
import { DropCard } from "@/components/sketches/upload/DropCard";
import { CraftSelect } from "@/components/forms/CraftSelect";
import { DateRangePicker, toIsoDate } from "@/components/forms/DateTimeField";
import { Field, TextInput } from "@/components/FormControls";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown, MultiSelectDropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { HeldPostNotice } from "@/components/designworkshop/HeldPostNotice";
import { WorkshopDesignerPicker } from "@/components/designworkshop/WorkshopDesignerPicker";
import { DesignWorkshopInspectorsPanel } from "@/components/settings/DesignWorkshopInspectorsPanel";
import { ApiError } from "@/lib/api";
import {
  fetchStageRegistry,
  namedDesignerTeam,
  peekStageRegistry,
  saveBlobToDisk,
  workshopKindOptions,
  MAX_NAMED_DESIGNERS,
  type DwRegistry,
  type DwSummary
} from "@/lib/designWorkshops";
import { formatDate } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import {
  canAssignWorkshopOversight,
  heldPostEditRefusal,
  heldPostReadPath,
  isAdmin,
  roleLabel,
  selfReleaseRefusal,
  selfReleaseRefused,
  workshopPostsHeldBy,
  type WorkshopPost
} from "@/lib/permissions";
import { ImportReport } from "./ImportReport";
import {
  ARTISAN_ACCEPT,
  CAPACITIES,
  CAPACITY_LABELS,
  OVERSIGHT_SEARCH_MAX,
  artisanUploadRefusal,
  createOversightWorkshop,
  downloadArtisanProForma,
  getWorkshopOversight,
  listArtisanImports,
  listAssignableDesigners,
  listAssignableWorkshops,
  listOfficers,
  listWorkshopArtisans,
  putWorkshopDesigners,
  putWorkshopOversight,
  readWorkshopStaffing,
  unlinkWorkshopArtisan,
  uploadArtisanList,
  type ArtisanImportReport,
  type DwArtisanImport,
  type DwNamedDesigner,
  type DwOfficer,
  type DwOversightCapacity,
  type DwRosterArtisan,
  type DwStaffingFilter,
  type DwViewerRow,
  type DwWorkshopOversight,
  type DwWorkshopStaffing
} from "./oversight";
import { officerOptionsForPost, officersOfferedTo } from "./pickers";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { workshopDisplayTitle } from "@/lib/workshopTitle";

const SEARCH_DEBOUNCE_MS = 300;

function describeFailure(error: unknown, subject: string): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) {
    return `No connection — couldn't load ${subject}.`;
  }
  return error.message;
}

function describeRefusal(error: unknown, subject: string): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) {
    return "No connection — nothing changed.";
  }
  return error.message || `Couldn't save ${subject}. Try again.`;
}

function useDebounced(value: string): string {
  const [applied, setApplied] = useState(value);
  useEffect(() => {
    const term = value.trim();
    const timer = window.setTimeout(() => setApplied(term), term ? SEARCH_DEBOUNCE_MS : 0);
    return () => window.clearTimeout(timer);
  }, [value]);
  return applied;
}

function personLabel(person: { name?: string | null; email?: string | null } | null | undefined): string {
  return person?.name?.trim() || person?.email?.trim() || "Unknown user";
}

export default function WorkshopOversightPage() {
  const { user, loading } = useAuth();
  const allowed = canAssignWorkshopOversight(user);

  const [workshop, setWorkshop] = useState<DwSummary | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [staffing, setStaffing] = useState<DwWorkshopStaffing | null>(null);
  const staffingGeneration = useRef(0);
  const workshopId = workshop?.id ?? null;

  const refreshStaffing = useCallback(() => {
    const current = staffingGeneration.current + 1;
    staffingGeneration.current = current;
    if (!workshopId || !allowed) {
      setStaffing(null);
      return;
    }
    readWorkshopStaffing(workshopId)
      .then((answer) => {
        if (staffingGeneration.current === current) setStaffing(answer);
      })
      .catch(() => {
        if (staffingGeneration.current === current) setStaffing(null);
      });
  }, [workshopId, allowed]);

  useEffect(() => {
    setStaffing(null);
    refreshStaffing();
  }, [refreshStaffing]);

  const posts = useMemo(() => workshopPostsHeldBy(user, staffing ?? {}), [user, staffing]);
  const readOnlyReason = heldPostEditRefusal(posts);

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
            For Ministry Admins and admins. Directors see theirs on Workshops I monitor.
          </p>
          <p className="mt-3 text-xs text-ink-500">
            You are signed in as <span className="font-medium text-ink-700">{roleLabel(user?.role)}</span>.
          </p>
          <ActionGroup className="mt-7">
            <Link href="/dashboard" className="field-button">
              Back to dashboard
            </Link>
            <Link href="/officers/monitored" className="field-button-secondary">
              Workshops I monitor
            </Link>
          </ActionGroup>
        </section>
      </div>
    );
  }

  return (
    <div>
      <PageHeader
        title="Workshop oversight"
        description="Staff a workshop: designers, directors, inspectors and artisans."
        icon={<UserCheck className="h-5 w-5" aria-hidden />}
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      ) : null}

      <WorkshopPicker
        chosen={workshop}
        onChoose={setWorkshop}
        onError={setError}
        posts={posts}
        staffing={staffing}
      />

      {workshop && readOnlyReason ? (
        <p className="mt-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-5 text-amber-800">
          {readOnlyReason}
        </p>
      ) : null}

      {workshop ? (
        <>
          <DesignerPanel
            key={workshop.id}
            workshop={workshop}
            onError={setError}
            onSaved={refreshStaffing}
            readOnlyReason={readOnlyReason}
          />
          <OversightPanel
            key={`oversight-${workshop.id}`}
            workshop={workshop}
            onError={setError}
            onSaved={refreshStaffing}
          />
          <div className="mt-4">
            <DesignWorkshopInspectorsPanel
              key={`inspectors-${workshop.id}`}
              workshopId={workshop.id}
              onSaved={refreshStaffing}
            />
          </div>
          <ArtisanListPanel
            key={`artisans-${workshop.id}`}
            workshop={workshop}
            onError={setError}
            readOnlyReason={readOnlyReason}
          />
        </>
      ) : (
        <section className="panel mt-4 p-4 text-sm text-ink-700">
          Choose or start a workshop above.
        </section>
      )}
    </div>
  );
}

const STAFFING_ROWS: DropdownOption[] = [
  { value: "staffed", label: "With a designer named" },
  { value: "unstaffed", label: "With nobody named yet" }
];

function WorkshopPicker({
  chosen,
  onChoose,
  onError,
  posts,
  staffing
}: {
  chosen: DwSummary | null;
  onChoose: (workshop: DwSummary | null) => void;
  onError: (message: string | null) => void;
  posts: readonly WorkshopPost[];
  staffing: DwWorkshopStaffing | null;
}) {
  const { user } = useAuth();
  const [query, setQuery] = useState("");
  const applied = useDebounced(query);
  const [staffed, setStaffed] = useState<DwStaffingFilter | "">("");
  const [rows, setRows] = useState<DwSummary[] | null>(null);
  const [truncated, setTruncated] = useState(false);
  const [opening, setOpening] = useState(false);
  const generation = useRef(0);

  const reload = useCallback(() => {
    const current = generation.current + 1;
    generation.current = current;
    listAssignableWorkshops({
      page: 1,
      pageSize: 20,
      search: applied || undefined,
      staffed: staffed || undefined
    })
      .then((page) => {
        if (generation.current !== current) return;
        setRows(page.items);
        setTruncated(page.total > page.items.length);
        onError(null);
      })
      .catch((err) => {
        if (generation.current !== current) return;
        onError(describeFailure(err, "the workshop list"));
      });
  }, [applied, staffed, onError]);

  useEffect(() => {
    reload();
  }, [reload]);

  const emptySentence = applied
    ? staffed
      ? "No workshop matches that search and that staffing."
      : "No workshop matches that search."
    : staffed === "unstaffed"
      ? "Every workshop has a designer."
      : staffed === "staffed"
        ? "No workshop has a designer yet."
        : "No workshops yet. Start one above.";

  const readPath = chosen ? heldPostReadPath(posts, chosen.id) : null;

  return (
    <section className="panel p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-display text-base font-bold tracking-tight text-ink-900">Workshop</h2>
        {chosen ? null : (
          <button
            type="button"
            className="field-button-secondary"
            onClick={() => setOpening((open) => !open)}
            aria-expanded={opening}
          >
            <Plus className="h-4 w-4" aria-hidden />
            {opening ? "Close" : "Start a new workshop"}
          </button>
        )}
      </div>

      {chosen ? (
        <div className="mt-3 flex flex-wrap items-center gap-3 rounded-md border border-ministry-200 bg-ministry-50 px-3 py-2 dark:border-ministry-900 dark:bg-ministry-950/40">
          <span className="min-w-0 flex-1">
            <span className="block truncate font-medium text-ink-900">
              {workshopDisplayTitle(chosen)}
            </span>
            <span className="block truncate text-xs text-ink-500">
              {chosen.workshopCode ?? "No workshop code yet"}
              {chosen.craftName ? ` · ${chosen.craftName}` : ""}
              {chosen.clusterName ? ` · ${chosen.clusterName}` : ""}
            </span>
          </span>
          <StatusBadge status={chosen.status} />
          {readPath ? (
            <Link href={readPath} className="field-button-secondary">
              Read this workshop
            </Link>
          ) : chosen.createdById === user?.id ||
            isAdmin(user) ||
            Boolean(user && staffing?.designers?.some((row) => row.userId === user.id)) ? (
            <Link href={`/design-workshops/${chosen.id}`} className="field-button-secondary">
              Open this workshop
            </Link>
          ) : (
            <span className="text-xs leading-5 text-ink-500">
              You can staff it but not open it.
            </span>
          )}
          <button type="button" className="field-button-secondary" onClick={() => onChoose(null)}>
            Choose a different workshop
          </button>
        </div>
      ) : (
        <>
          {opening ? (
            <StartWorkshopForm
              onOpened={(created) => {
                setOpening(false);
                onChoose(created);
              }}
              onError={onError}
            />
          ) : null}

          <div className="mt-3 grid gap-3 sm:grid-cols-[minmax(0,1fr)_14rem]">
            <SearchInput
              onChange={(next) => setQuery(next.slice(0, OVERSIGHT_SEARCH_MAX))}
              onSubmit={() => setQuery(query.trim())}
              ariaLabel="Search design workshops"
              placeholder="Search by title, craft, cluster or workshop code"
              value={query}
            />
            <FieldBlock label="Staffing">
              <Dropdown
                advanceOnSelect={false}
                ariaLabel="Show workshops by whether a designer is named"
                noneLabel="Staffed and unstaffed"
                onChange={(next) => setStaffed(next as DwStaffingFilter | "")}
                options={STAFFING_ROWS}
                value={staffed}
              />
            </FieldBlock>
          </div>

          {rows === null ? (
            <p className="mt-3 text-sm text-ink-700">Loading…</p>
          ) : rows.length === 0 ? (
            <p className="mt-3 text-sm text-ink-700">{emptySentence}</p>
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
                      {workshopDisplayTitle(row)}
                    </span>
                    <span className="truncate text-xs text-ink-500">
                      {row.workshopCode ?? "No workshop code yet"}
                      {row.craftName ? ` · ${row.craftName}` : ""}
                      {row.startDate ? ` · ${formatDate(row.startDate)}` : ""}
                    </span>
                    <span className="truncate text-xs text-ink-500">
                      {row.designerName?.trim() ? (
                        <>
                          Designer:{" "}
                          <span className="font-medium text-ink-700">{row.designerName}</span>
                        </>
                      ) : (
                        "No designer named"
                      )}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
          {truncated ? (
            <p className="mt-2 text-xs text-ink-500">
              Showing the latest 20. Search for others.
            </p>
          ) : null}
        </>
      )}
    </section>
  );
}

function StartWorkshopForm({
  onOpened,
  onError
}: {
  onOpened: (workshop: DwSummary) => void;
  onError: (message: string | null) => void;
}) {
  const [creating, setCreating] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [workshopKind, setWorkshopKind] = useState("");
  const [duration, setDuration] = useState<{ from?: Date; to?: Date }>({});
  const [designerUserIds, setDesignerUserIds] = useState<string[]>([]);
  const [leadDesignerId, setLeadDesignerId] = useState("");
  const [registry, setRegistry] = useState<DwRegistry | null>(() => peekStageRegistry());

  useEffect(() => {
    let cancelled = false;
    fetchStageRegistry()
      .then((next) => {
        if (!cancelled) setRegistry(next);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  const [craftPick, setCraftPick] = useState({ id: "", name: "" });

  const kindChoices = useMemo(() => workshopKindOptions(registry), [registry]);
  const kindRows = useMemo<DropdownOption[]>(
    () => kindChoices.options.map((option) => ({ value: option.value, label: option.label })),
    [kindChoices]
  );

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const title = String(form.get("title") ?? "").trim();
    if (!title) return;

    const text = (key: string) => {
      const value = String(form.get(key) ?? "").trim();
      return value || undefined;
    };

    setCreating(true);
    setRefusal(null);
    onError(null);
    try {
      const created = await createOversightWorkshop({
        title,
        workshopKind: workshopKind || undefined,
        designerUserIds,
        lead: leadDesignerId,
        craftId: craftPick.id || undefined,
        craftName: craftPick.name || undefined,
        clusterName: text("clusterName"),
        state: text("state"),
        district: text("district"),
        startDate: duration.from ? toIsoDate(duration.from) : undefined,
        endDate: duration.to ? toIsoDate(duration.to) : undefined
      });
      onOpened(created);
    } catch (err) {
      setRefusal(describeRefusal(err, "that workshop"));
    } finally {
      setCreating(false);
    }
  }

  return (
    <form onSubmit={submit} className="mt-3 grid gap-4 rounded-md border border-line-200 bg-surface-50 p-4">
      <div>
        <h3 className="font-display text-sm font-bold tracking-tight text-ink-900">
          Start a design workshop
        </h3>
        <p className="mt-1 text-xs leading-5 text-ink-500">
          Only the title is required.
        </p>
      </div>

      <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
        <Field label="Workshop title" required>
          <TextInput name="title" maxLength={220} required />
        </Field>
        <FieldBlock label="Type of workshop">
          <Dropdown
            ariaLabel="Type of workshop"
            onChange={setWorkshopKind}
            options={kindRows}
            placeholder="Not stated"
            value={workshopKind}
          />
        </FieldBlock>
        <FieldBlock label="Craft">
          <CraftSelect
            ariaLabel="Craft"
            value={craftPick.id}
            onChange={(id, craft) => setCraftPick({ id, name: craft?.name ?? "" })}
          />
        </FieldBlock>
        <Field label="Cluster">
          <TextInput name="clusterName" maxLength={160} />
        </Field>
        <Field label="State">
          <TextInput name="state" maxLength={80} />
        </Field>
        <Field label="District">
          <TextInput name="district" maxLength={80} />
        </Field>
        <div className="grid gap-1 md:col-span-2">
          <DateRangePicker from={duration.from} to={duration.to} onChange={setDuration} />
        </div>
      </div>

      <WorkshopDesignerPicker
        values={designerUserIds}
        onChange={setDesignerUserIds}
        lead={leadDesignerId}
        onLeadChange={setLeadDesignerId}
        disabled={creating}
        fetchEligible={listAssignableDesigners}
      />

      {refusal ? (
        <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {refusal}
        </div>
      ) : null}

      <ActionGroup>
        <button className="field-button" disabled={creating} type="submit">
          {creating ? "Opening…" : "Open this workshop"}
        </button>
      </ActionGroup>
    </form>
  );
}

function DesignerPanel({
  workshop,
  onError,
  onSaved,
  readOnlyReason = null
}: {
  workshop: DwSummary;
  onError: (message: string | null) => void;
  onSaved?: () => void;
  readOnlyReason?: string | null;
}) {
  const { user } = useAuth();
  const heldNoticeId = useId();
  const [held, setHeld] = useState<DwNamedDesigner[] | null>(null);
  const [baseline, setBaseline] = useState<string[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [leadChoice, setLeadChoice] = useState("");
  const [designerName, setDesignerName] = useState<string | null>(workshop.designerName ?? null);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState<string | null>(null);
  const [lostAccess, setLostAccess] = useState<DwViewerRow[]>([]);
  const [refusal, setRefusal] = useState<string | null>(null);
  const announcementId = useId();

  const baselineLead = useMemo(
    () => held?.find((row) => row.isLead)?.userId ?? "",
    [held]
  );

  const load = useCallback(async () => {
    try {
      const detail: DwWorkshopOversight = await getWorkshopOversight(workshop.id);
      const rows = detail.designers;
      if (rows === undefined) {
        setHeld(null);
        setRefusal("The designer team isn't available right now.");
        return;
      }
      setHeld(rows);
      const ids = rows.map((row) => row.userId);
      setBaseline(ids);
      setSelected(ids);
      setLeadChoice("");
      setDesignerName(detail.designerName);
      setRefusal(null);
      onError(null);
    } catch (err) {
      onError(describeFailure(err, "this workshop's designers"));
    }
  }, [workshop.id, onError]);

  useEffect(() => {
    setSaved(null);
    setLostAccess([]);
    void load();
  }, [load]);

  const leadIntent = selected.length === 0 ? "" : leadChoice || baselineLead;

  const resolved = useMemo(
    () => namedDesignerTeam({ chosen: selected, lead: leadIntent }),
    [selected, leadIntent]
  );

  const added = useMemo(
    () => selected.filter((id) => !baseline.includes(id)),
    [selected, baseline]
  );
  const removed = useMemo(
    () => baseline.filter((id) => !selected.includes(id)),
    [baseline, selected]
  );
  const leadMoved = Boolean(leadIntent) && leadIntent !== baselineLead;
  const dirty = added.length > 0 || removed.length > 0 || leadMoved;
  const overCap = selected.length > MAX_NAMED_DESIGNERS;

  const emptiesTheWorkshop = selected.length === 0 && removed.length > 0;
  const dropsTheLeadWithNoReplacement =
    Boolean(baselineLead) && removed.includes(baselineLead) && !leadMoved && selected.length > 0;

  const labelById = useMemo(() => {
    const map = new Map<string, string>();
    for (const row of held ?? []) map.set(row.userId, personLabel(row));
    return map;
  }, [held]);

  const announcement = !dirty
    ? held === null
      ? ""
      : held.length === 0
        ? "Nobody is named on this workshop. Nothing unsaved."
        : `${held.length} designer${held.length === 1 ? "" : "s"}.`
    : `${selected.length} selected — unsaved (+${added.length}, −${removed.length}${leadMoved ? ", new lead" : ""}).`;

  async function save() {
    if (!dirty || overCap || dropsTheLeadWithNoReplacement || readOnlyReason) return;
    setSaving(true);
    setRefusal(null);
    setSaved(null);
    try {
      const answer = await putWorkshopDesigners(workshop.id, {
        userIds: resolved.team,
        ...(leadMoved ? { leadUserId: resolved.lead } : {})
      });
      setHeld(answer.designers);
      const ids = answer.designers.map((row) => row.userId);
      setBaseline(ids);
      setSelected(ids);
      setLeadChoice("");
      setDesignerName(answer.designerName);
      setLostAccess(answer.removedDesigners);
      const nowEmpty = answer.designers.length === 0;
      setSaved(
        [
          nowEmpty
            ? "Nobody is named on this workshop. Its report will name nobody on the cover until a designer is ticked here again."
            : `${answer.designers.length} designer${answer.designers.length === 1 ? "" : "s"} can now open this workshop.`,
          nowEmpty
            ? "The profile details already copied into stages 1 and 3 stay exactly as they are — only the designer's name is gone."
            : answer.stagesWritten.length
              ? `The designer's profile was copied into ${
                  answer.stagesWritten.length === 1 ? "one stage" : `${answer.stagesWritten.length} stages`
                }.`
              : "",
          answer.removedDesigners.length
            ? `${answer.removedDesigners
                .map((row) => personLabel(row))
                .join(", ")} can no longer open it.`
            : ""
        ]
          .filter(Boolean)
          .join(" ")
      );
      onSaved?.();
    } catch (err) {
      setRefusal(describeRefusal(err, "that change"));
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="panel mt-4 p-4">
      <h2 className="font-display text-base font-bold tracking-tight text-ink-900">Designers</h2>
      <p className="mt-1 text-sm text-ink-700">
        The report names:{" "}
        <span className="font-medium text-ink-900">
          {designerName?.trim() || "nobody yet"}
        </span>
      </p>
      <p className="mt-1 text-xs leading-5 text-ink-500">
        Ticked designers can work on this workshop; the lead&apos;s name goes on the report. While
        other designers stay ticked, the lead cannot simply be dropped — pick another lead. You can
        also untick everybody. If the report has already been handed in, saving isn&apos;t allowed.
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
      <HeldPostNotice refusal={readOnlyReason} id={heldNoticeId} className="mt-3" />

      {held === null ? (
        <p className="mt-3 text-sm text-ink-700">Loading…</p>
      ) : (
        <>
          <div className="mt-3">
            <WorkshopDesignerPicker
              values={selected}
              onChange={setSelected}
              lead={leadIntent}
              onLeadChange={setLeadChoice}
              disabled={saving || Boolean(readOnlyReason)}
              fetchEligible={listAssignableDesigners}
              excludeUserIds={user ? [user.id] : undefined}
            />
          </div>

          <div className="mt-3">
            {held.length === 0 ? (
              <p className="text-sm text-ink-500">
                No designer is named on this workshop. Whoever created it can still open it.
              </p>
            ) : (
              <ul className="grid gap-1.5">
                {held.map((row) => (
                  <li
                    className="flex flex-wrap items-baseline gap-x-2 text-sm text-ink-700"
                    key={row.userId}
                  >
                    <span className="font-medium text-ink-900">{personLabel(row)}</span>
                    <span className="text-xs text-ink-500">
                      {row.email}
                      {row.role ? ` · ${roleLabel(row.role)}` : ""}
                    </span>
                    {row.isLead ? (
                      <span className="rounded-full border border-ministry-300 bg-ministry-50 px-2 py-0.5 text-[0.6875rem] font-medium text-ministry-700 dark:border-ministry-900 dark:bg-ministry-950/40 dark:text-ministry-300">
                        the report names them
                      </span>
                    ) : null}
                    {removed.includes(row.userId) ? (
                      <span className="rounded-full border border-error-600/25 bg-error-100 px-2 py-0.5 text-[0.6875rem] font-medium text-red-800">
                        loses access on save
                      </span>
                    ) : null}
                  </li>
                ))}
              </ul>
            )}
            {added.length ? (
              <p className="mt-2 text-xs leading-5 text-ink-500">
                Will be given access on save:{" "}
                {added.map((id) => labelById.get(id) ?? "a newly chosen designer").join(", ")}.
              </p>
            ) : null}
            {lostAccess.length ? (
              <p className="mt-2 text-xs leading-5 text-ink-500">
                No longer on this workshop: {lostAccess.map((row) => personLabel(row)).join(", ")}.
                Their records, photographs and drafts are untouched, and nothing they wrote has been
                removed or re-attributed.
              </p>
            ) : null}
          </div>

          {dropsTheLeadWithNoReplacement ? (
            <p className="mt-3 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-5 text-amber-800">
              {labelById.get(baselineLead) ?? "That designer"} is the lead. Choose a new lead first.
            </p>
          ) : overCap ? (
            <p className="mt-3 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-5 text-amber-800">
              Maximum {MAX_NAMED_DESIGNERS}. Untick {selected.length - MAX_NAMED_DESIGNERS} to save.
            </p>
          ) : emptiesTheWorkshop ? (
            <p className="mt-3 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-5 text-amber-800">
              Saving will leave this workshop with no named designer: only admins and its creator can
              open it, and its report will name nobody on the cover. Details copied into stages 1 and 3
              stay exactly as they are.
            </p>
          ) : null}

          <div className="mt-3 flex flex-wrap items-center gap-2">
            <ActionGroup className="max-sm:w-full"><button
              className="field-button"
              disabled={!dirty || saving || overCap || dropsTheLeadWithNoReplacement || Boolean(readOnlyReason)}
              onClick={save}
              aria-describedby={readOnlyReason ? heldNoticeId : undefined}
              type="button"
            >
              <Save className="h-4 w-4" aria-hidden />
              {saving ? "Saving…" : "Save who this workshop is for"}
            </button>
            {dirty ? (
              <button
                className="field-button-secondary"
                disabled={saving}
                onClick={() => {
                  setSelected(baseline);
                  setLeadChoice("");
                  setRefusal(null);
                }}
                type="button"
              >
                Discard changes
              </button>
            ) : null}
            </ActionGroup>
            {dirty ? (
              <span className="text-xs text-amber-800 dark:text-amber-100">
                {added.length + removed.length + (leadMoved ? 1 : 0)} unsaved change
                {added.length + removed.length + (leadMoved ? 1 : 0) === 1 ? "" : "s"}
              </span>
            ) : null}
          </div>
        </>
      )}

      <p className="sr-only" role="status" aria-live="polite" id={announcementId}>
        {announcement}
      </p>
    </section>
  );
}

function OversightPanel({
  workshop,
  onError,
  onSaved
}: {
  workshop: DwSummary;
  onError: (message: string | null) => void;
  onSaved?: () => void;
}) {
  const { user } = useAuth();
  const [current, setCurrent] = useState<DwWorkshopOversight | null>(null);
  const [query, setQuery] = useState("");
  const applied = useDebounced(query);
  const [officers, setOfficers] = useState<DwOfficer[] | null>(null);
  const [truncated, setTruncated] = useState(false);
  const [searching, setSearching] = useState(false);
  const [saving, setSaving] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);
  const generation = useRef(0);
  const [seen, setSeen] = useState<Map<string, DwOfficer>>(() => new Map());

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
    setSearching(true);
    listOfficers(applied || undefined)
      .then((list) => {
        if (generation.current !== gen) return;
        setOfficers(list.users);
        setTruncated(list.truncated);
        setSeen((previous) => {
          const next = new Map(previous);
          for (const officer of list.users) next.set(officer.id, officer);
          return next;
        });
        setSearching(false);
      })
      .catch((err) => {
        if (generation.current !== gen) return;
        setSearching(false);
        onError(describeFailure(err, "the officer list"));
      });
  }, [applied, onError]);

  async function assign(capacity: DwOversightCapacity, userId: string | null) {
    if (user?.id && (current?.oversight ?? []).some((row) => row.capacity === capacity && row.userId === user.id)) {
      setRefusal(selfReleaseRefused(capacity));
      return;
    }
    setSaving(true);
    setRefusal(null);
    const body =
      capacity === "ASSISTANT_DIRECTOR"
        ? { assistantDirectorId: userId }
        : { regionalDirectorId: userId };
    try {
      const result = await putWorkshopOversight(workshop.id, body);
      setCurrent((currently) => (currently ? { ...currently, oversight: result.oversight } : currently));
      onSaved?.();
    } catch (err) {
      setRefusal(describeRefusal(err, "that officer"));
    } finally {
      setSaving(false);
    }
  }

  const held = useMemo(
    () => new Map((current?.oversight ?? []).map((row) => [row.capacity, row])),
    [current]
  );
  const ownPostNote = useId();

  const rowsByCapacity = useMemo(
    () =>
      new Map<DwOversightCapacity, DropdownOption[]>(
        CAPACITIES.map((capacity) => [
          capacity,
          officerOptionsForPost({
            officers,
            capacity,
            holder: held.get(capacity),
            remembered: seen,
            readerId: user?.id
          })
        ])
      ),
    [officers, held, seen, user?.id]
  );

  return (
    <section className="panel mt-4 p-4">
      <h2 className="font-display text-base font-bold tracking-tight text-ink-900">
        Assistant Director and Regional Director
      </h2>
      <p className="mt-1 text-xs leading-5 text-ink-500">
        One of each. Read-only access. Changes save immediately.
      </p>

      {refusal ? (
        <div className="mt-3 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {refusal}
        </div>
      ) : null}

      <div className="mt-3 grid gap-3 sm:grid-cols-2">
        {CAPACITIES.map((capacity) => {
          const row = held.get(capacity);
          const own = Boolean(user?.id) && row?.userId === user?.id;
          const ownNoteId = `${ownPostNote}-${capacity}`;
          return (
            <FieldBlock key={capacity} label={CAPACITY_LABELS[capacity]}>
              {current === null ? (
                <p className="text-sm text-ink-700">Loading…</p>
              ) : (
                <>
                  <Dropdown
                    ariaLabel={`The ${CAPACITY_LABELS[capacity]} of this workshop`}
                    disabled={saving || own}
                    describedBy={own ? ownNoteId : undefined}
                    noneLabel="Nobody is assigned"
                    onChange={(next) => assign(capacity, next || null)}
                    options={rowsByCapacity.get(capacity) ?? []}
                    placeholder="Nobody is assigned"
                    serverQuery={{
                      value: query,
                      onChange: (next) => setQuery(next.slice(0, OVERSIGHT_SEARCH_MAX)),
                      pending: searching
                    }}
                    value={row?.userId ?? ""}
                  />
                  {row ? (
                    <p className="truncate text-xs text-ink-500">
                      {row.email}
                      {row.assignedAt ? ` · supervising since ${formatDate(row.assignedAt)}` : ""}
                    </p>
                  ) : null}
                  {own ? (
                    <p
                      id={ownNoteId}
                      className="rounded-md border border-amber-500/30 bg-amber-100 px-2.5 py-1.5 text-xs leading-5 text-amber-800"
                    >
                      {selfReleaseRefusal(capacity)}
                    </p>
                  ) : null}
                </>
              )}
            </FieldBlock>
          );
        })}
      </div>

      {officers !== null && officersOfferedTo(officers, user?.id) === 0 ? (
        <p className="mt-3 text-sm text-ink-700">
          {applied
            ? "No matches."
            : "No eligible officers. Roles are set on Users."}
        </p>
      ) : null}
      {truncated ? (
        <p className="mt-2 text-xs text-ink-500">
          Not everyone is listed — type a name to search.
        </p>
      ) : null}
    </section>
  );
}

function ArtisanListPanel({
  workshop,
  onError,
  readOnlyReason
}: {
  workshop: DwSummary;
  onError: (message: string | null) => void;
  readOnlyReason?: string | null;
}) {
  const locked = Boolean(readOnlyReason);
  const [downloading, setDownloading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [report, setReport] = useState<ArtisanImportReport | null>(null);
  const [history, setHistory] = useState<DwArtisanImport[] | null>(null);

  const [roster, setRoster] = useState<DwRosterArtisan[] | null>(null);
  const [rosterNext, setRosterNext] = useState<number | null>(null);
  const [rosterTotal, setRosterTotal] = useState<number | null>(null);
  const [rosterLoadingMore, setRosterLoadingMore] = useState(false);
  const [keeping, setKeeping] = useState<string[]>([]);
  const [removing, setRemoving] = useState(false);
  const [rosterSaved, setRosterSaved] = useState<string | null>(null);

  const reloadRoster = useCallback(async () => {
    try {
      const list = await listWorkshopArtisans(workshop.id);
      setRoster(list.artisans);
      setKeeping(list.artisans.map((row) => row.id));
      setRosterNext(list.truncated ? (list.nextOffset ?? list.artisans.length) : null);
      setRosterTotal(typeof list.total === "number" ? list.total : null);
    } catch (err) {
      onError(describeFailure(err, "this workshop's artisan roster"));
    }
  }, [workshop.id, onError]);

  const moreRoster = useCallback(async () => {
    if (rosterNext === null) return;
    setRosterLoadingMore(true);
    try {
      const list = await listWorkshopArtisans(workshop.id, rosterNext);
      setRoster((previous) => {
        const seen = new Set((previous ?? []).map((row) => row.id));
        return [...(previous ?? []), ...list.artisans.filter((row) => !seen.has(row.id))];
      });
      setKeeping((previous) => [...previous, ...list.artisans.map((row) => row.id).filter((id) => !previous.includes(id))]);
      setRosterNext(list.truncated && list.artisans.length ? (list.nextOffset ?? rosterNext + list.artisans.length) : null);
    } catch (err) {
      onError(describeFailure(err, "this workshop's artisan roster"));
    } finally {
      setRosterLoadingMore(false);
    }
  }, [workshop.id, onError, rosterNext]);

  const reloadHistory = useCallback(() => {
    listArtisanImports(workshop.id)
      .then((list) => setHistory(list.imports))
      .catch((err) => onError(describeFailure(err, "this workshop's imports")));
  }, [workshop.id, onError]);

  useEffect(() => {
    setReport(null);
    setRefusal(null);
    setHistory(null);
    setRoster(null);
    setRosterSaved(null);
    reloadHistory();
    void reloadRoster();
  }, [reloadHistory, reloadRoster]);

  const rosterOptions = useMemo<DropdownOption[]>(
    () =>
      (roster ?? []).map((row) => ({
        value: row.id,
        label: row.name,
        hint: [row.place, row.craftName, row.status].filter(Boolean).join(" · ")
      })),
    [roster]
  );
  const comingOff = useMemo(
    () => (roster ?? []).filter((row) => !keeping.includes(row.id)),
    [roster, keeping]
  );

  async function download() {
    setDownloading(true);
    setRefusal(null);
    try {
      const file = await downloadArtisanProForma(workshop.id);
      saveBlobToDisk(file.blob, file.fileName);
    } catch (err) {
      setRefusal(describeRefusal(err, "the pro-forma"));
    } finally {
      setDownloading(false);
    }
  }

  async function upload(files: File[]) {
    const [file] = files;
    if (!file || locked) return;
    setUploading(true);
    setRefusal(null);
    setReport(null);
    try {
      const result = await uploadArtisanList(workshop.id, file);
      setReport(result);
      reloadHistory();
      await reloadRoster();
    } catch (err) {
      setRefusal(describeRefusal(err, "that artisan list"));
    } finally {
      setUploading(false);
    }
  }

  async function removeUnticked() {
    if (!comingOff.length || locked) return;
    setRemoving(true);
    setRefusal(null);
    setRosterSaved(null);
    const failed: string[] = [];
    let taken = 0;
    for (const row of comingOff) {
      try {
        const answer = await unlinkWorkshopArtisan(workshop.id, row.id);
        if (answer.unlinked) taken += 1;
      } catch {
        failed.push(row.name);
      }
    }
    await reloadRoster();
    setRemoving(false);
    if (failed.length) {
      setRefusal(
        `${failed.join(", ")} could not be taken off this workshop's roster, so ${
          failed.length === 1 ? "that record is" : "those records are"
        } still on it.`
      );
      return;
    }
    setRosterSaved(
      `${taken === 1 ? "One artisan" : `${taken} artisans`} removed from the roster. Their records are kept.`
    );
  }

  return (
    <section className="panel mt-4 p-4">
      <h2 className="font-display text-base font-bold tracking-tight text-ink-900">Artisan list</h2>
      {readOnlyReason ? (
        <p className="mt-1 text-xs leading-5 text-ink-500">
          Read-only while you hold a post on this workshop.
        </p>
      ) : null}

      <FieldBlock
        label="Artisans on this workshop"
        hint={
          <p className="text-xs leading-5 text-ink-500">
            Unticking removes from the roster only; records are kept. Add artisans with the pro-forma below.
          </p>
        }
      >
        {roster === null ? (
          <p className="text-sm text-ink-700">Loading…</p>
        ) : roster.length === 0 ? (
          <p className="text-sm text-ink-700">
            No artisans yet. Use the pro-forma below.
          </p>
        ) : (
          <MultiSelectDropdown
            ariaLabel="Artisans on this workshop"
            confirmLabel="Done"
            disabled={removing || locked}
            emptyLabel="No artisan is filed against this workshop."
            onChange={setKeeping}
            options={rosterOptions}
            placeholder="Nobody is on this roster"
            searchable
            values={keeping}
          />
        )}
      </FieldBlock>

      {rosterNext !== null ? (
        <div className="mt-2 flex flex-wrap items-center gap-3">
          <p className="text-xs leading-5 text-ink-500">
            {rosterTotal !== null ? `Showing ${roster?.length ?? 0} of ${rosterTotal}.` : `Showing ${roster?.length ?? 0}.`}
          </p>
          <ActionGroup>
            <button type="button" className="field-button-secondary min-h-10" data-testid="roster-more" disabled={rosterLoadingMore} onClick={() => void moreRoster()}>
              {rosterLoadingMore ? "Loading…" : "Show more"}
            </button>
          </ActionGroup>
        </div>
      ) : null}

      {roster && roster.length ? (
        <ul className="mt-2 grid gap-1.5">
          {roster.map((row) => (
            <li className="flex flex-wrap items-baseline gap-x-2 text-sm text-ink-700" key={row.id}>
              <span className="font-medium text-ink-900">{row.name}</span>
              <span className="text-xs text-ink-500">
                {[row.place, row.craftName].filter(Boolean).join(" · ")}
                {row.createdAt ? ` · added ${formatDate(row.createdAt)}` : ""}
              </span>
              <StatusBadge status={row.status} />
              {comingOff.some((candidate) => candidate.id === row.id) ? (
                <span className="rounded-full border border-error-600/25 bg-error-100 px-2 py-0.5 text-[0.6875rem] font-medium text-red-800">
                  comes off the roster on save
                </span>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}

      {rosterSaved ? (
        <div className="mt-3 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">
          {rosterSaved}
        </div>
      ) : null}

      {comingOff.length ? (
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <ActionGroup className="max-sm:w-full"><button
            className="field-button"
            disabled={removing || locked}
            onClick={removeUnticked}
            type="button"
          >
            <Save className="h-4 w-4" aria-hidden />
            {removing ? "Saving…" : "Save who is on this roster"}
          </button>
          <button
            className="field-button-secondary"
            disabled={removing}
            onClick={() => setKeeping((roster ?? []).map((row) => row.id))}
            type="button"
          >
            Discard changes
          </button>
          </ActionGroup>
          <span className="text-xs text-amber-800 dark:text-amber-100">
            {comingOff.length} artisan{comingOff.length === 1 ? "" : "s"} unticked, not yet saved
          </span>
        </div>
      ) : null}

      <h3 className="mt-5 font-display text-sm font-bold tracking-tight text-ink-900">
        Add artisans from the pro-forma
      </h3>
      <p className="mt-1 text-xs leading-5 text-ink-500">
        Download, fill in and upload. Existing artisans are linked, not duplicated.
      </p>
      <p className="mt-2 rounded-md border border-amber-100 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
        Contains Aadhaar numbers. Don&rsquo;t email or share the filled file; delete it after uploading. The file
        isn&rsquo;t stored.
      </p>

      <ActionGroup className="mt-3">
        <button type="button" className="field-button-secondary" disabled={downloading} onClick={download}>
          {downloading ? "Preparing…" : "Download the pro-forma"}
        </button>
      </ActionGroup>

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
          disabled={uploading || locked}
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
          No uploads yet.
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
                {entry.rowsRefused} not added
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
