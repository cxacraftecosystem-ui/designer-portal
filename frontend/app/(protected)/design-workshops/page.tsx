"use client";

import { Fragment, Suspense, useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { CloudOff, DraftingCompass, Info, Plus } from "lucide-react";

import { CappedListNotice } from "@/components/data/CappedListNotice";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { useListMemory } from "@/components/hooks/useListMemory";
import { EmptyState } from "@/components/EmptyState";
import { DateRangePicker, toIsoDate } from "@/components/forms/DateTimeField";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { OpenableCard, OpenableRow, OpenableRowLink } from "@/components/data/OpenableRow";
import { PageHeader } from "@/components/PageHeader";
import { RecordCodeCard } from "@/components/RecordCode";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { clusterFromSearchParams } from "@/lib/unifiedSearch";
import { StatusBadge } from "@/components/StatusBadge";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { CraftSelect } from "@/components/forms/CraftSelect";
import { Dropdown } from "@/components/ui/Dropdown";
import { RENDER_CAP } from "@/components/ui/selectFilter";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import {
  deleteDesignWorkshop,
  fetchStageRegistry,
  listDesignWorkshops,
  listReportTemplates,
  namedDesignerTeam,
  peekStageRegistry,
  workshopKindOptions,
  type DwRegistry,
  type DwSummary,
  type DwTemplate
} from "@/lib/designWorkshops";
import {
  DW_LOCAL_DRAFT_LINK_PROMPT,
  DW_LOCAL_DRAFT_UNLINKED,
  DW_LOCAL_START_ACTION,
  DW_LOCAL_START_NOTE,
  adoptServerSummaries,
  draftSummary,
  getDraftsSnapshot,
  getServerDraftsSnapshot,
  localDraftNeedsAWorkshop,
  refreshDrafts,
  subscribeDrafts,
  type DwDraft
} from "@/lib/designWorkshopStore";
import { createWorkshopOrKeepItHere, startLocalDraftHere } from "@/lib/designWorkshopCreate";
import { AdoptLocalDraftDialog } from "@/components/designworkshop/AdoptLocalDraftDialog";
import { WorkshopDesignerPicker } from "@/components/designworkshop/WorkshopDesignerPicker";
import { DesignWorkshopViewersPanel } from "@/components/settings/DesignWorkshopViewersPanel";
import { DeletedWorkshopsCard } from "@/components/admin/DeletedWorkshopsCard";
import { isUnreachable } from "@/lib/offline";
import { formatDate } from "@/lib/format";
import {
  DESIGN_WORKSHOP_CREATE_REFUSAL,
  canAccessRoute,
  canCreateDesignWorkshops,
  canRunDesignWorkshops,
  isAdmin,
  isContentAdmin
} from "@/lib/permissions";
import type { PageResult, Workshop } from "@/lib/types";
import { listAllPages } from "@/lib/listAllPages";
import { sortWorkshopsByOccurrence } from "@/components/forms/WorkshopSelect";
import {
  designWorkshopDefaultNote,
  readDesignWorkshopDefault
} from "@/lib/designWorkshopDefault";
import {
  TYPE_DETAILS_INSTEAD,
  WORKSHOP_OPTION_PAGE_SIZE,
  designWorkshopOptions,
  deviceLooksOffline,
  fieldWorkshopOptions,
  workshopCutSentence,
  workshopEmptyLabel,
  workshopListNotice,
  type WorkshopListState,
  type WorkshopListVoice
} from "@/lib/workshopOptions";
import { LINKED_WORKSHOP_KIND_GAP } from "@/components/designworkshop/linkedWorkshopPicker";
import { ActionGroup } from "@/components/ui/ActionGroup";

const STATUS_OPTIONS = [
  { value: "", label: "Any status" },
  { value: "DRAFT", label: "Draft" },
  { value: "IN_PROGRESS", label: "In progress" },
  { value: "COMPLETE", label: "Complete" },
  { value: "PRE_SUBMISSION", label: "Pre-submission" },
  { value: "NEEDS_REVISION", label: "Needs revision" },
  { value: "APPROVED", label: "Approved" },
  { value: "SUBMITTED", label: "Submitted" },
  { value: "ARCHIVED", label: "Archived" }
];

const OVERSIGHT_DOORS: readonly { href: string; label: string; note: string }[] = [
  {
    href: "/officers/monitored",
    label: "Workshops I monitor",
    note: "where you are Assistant or Regional Director"
  },
  {
    href: "/officers",
    label: "Workshop oversight",
    note: "name designers and supervising officers"
  }
];

export default function DesignWorkshopsPage() {
  return (
    <Suspense fallback={<div className="panel p-4 text-sm text-ink-500">Loading...</div>}>
      <DesignWorkshopsPageBody />
    </Suspense>
  );
}

function DesignWorkshopsPageBody() {
  const confirm = useConfirm();
  const router = useRouter();
  const searchParams = useSearchParams();
  const { user } = useAuth();
  const { adminMode } = useAdminView();
  const allowCreate = canCreateDesignWorkshops(user);
  const allowWork = canRunDesignWorkshops(user);
  const allowDelete = isContentAdmin(user);
  const emptyScopeDoors = useMemo(
    () => (isAdmin(user) ? [] : OVERSIGHT_DOORS.filter((door) => canAccessRoute(user, door.href))),
    [user]
  );
  const [createRefused, setCreateRefused] = useState(false);

  const [viewersOpen, setViewersOpen] = useState(false);
  const [viewersRefresh, setViewersRefresh] = useState(0);

  const [codeFor, setCodeFor] = useState<{ id: string; title: string } | null>(null);

  const drafts = useSyncExternalStore(subscribeDrafts, getDraftsSnapshot, getServerDraftsSnapshot);
  const [offline, setOffline] = useState(false);
  const [data, setData] = useState<PageResult<DwSummary> | null>(null);
  const [templates, setTemplates] = useState<DwTemplate[]>([]);
  const [page, setPage] = useState(1);
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [status, setStatus] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [formOpen, setFormOpen] = useState(false);
  const [localTitle, setLocalTitle] = useState("");
  const [startingLocal, setStartingLocal] = useState(false);
  const [title, setTitle] = useState("");
  const [titleTerm, setTitleTerm] = useState("");
  const [titleRows, setTitleRows] = useState<DwSummary[]>([]);
  const [titlePending, setTitlePending] = useState(false);
  const [templateId, setTemplateId] = useState("DCH_STANDARD");
  const [workshopKind, setWorkshopKind] = useState("");
  const [craftPick, setCraftPick] = useState<{ id: string; name: string }>({ id: "", name: "" });
  const [registry, setRegistry] = useState<DwRegistry | null>(() => peekStageRegistry());
  const [kindFilter, setKindFilter] = useState("");
  const kindChoices = useMemo(() => workshopKindOptions(registry), [registry]);
  const [designerUserIds, setDesignerUserIds] = useState<string[]>([]);
  const designerSeeded = useRef(false);
  useEffect(() => {
    if (designerSeeded.current) return;
    if (user?.role !== "DESIGNER" || !user.id) return;
    designerSeeded.current = true;
    setDesignerUserIds((current) => (current.length === 0 ? [user.id] : current));
  }, [user?.id, user?.role]);
  const [leadDesignerId, setLeadDesignerId] = useState("");
  const [dirty, setDirty] = useState(false);
  const [confirmAction, setConfirmAction] = useState<(() => void) | null>(null);
  const [sourceList, setSourceList] = useState<WorkshopListState<Workshop>>({ kind: "loading" });
  const [sourceWorkshopId, setSourceWorkshopId] = useState("");
  const formRef = useRef<HTMLFormElement | null>(null);
  const [duration, setDuration] = useState<{ from?: Date; to?: Date }>({});
  const skipFirstDebounce = useRef(true);

  const generation = useRef(0);

  function guard(action: () => void) {
    if (dirty) setConfirmAction(() => action);
    else action();
  }

  const markDirty = () => setDirty(true);

  useEffect(() => {
    if (!dirty) return;
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, [dirty]);

  useLeaveGuard(dirty, () => guard(() => router.back()));

  const titleGeneration = useRef(0);
  useEffect(() => {
    if (!formOpen || !allowCreate) return;
    const term = titleTerm.trim();
    const mine = titleGeneration.current + 1;
    titleGeneration.current = mine;
    setTitlePending(true);
    const timer = window.setTimeout(() => {
      void listDesignWorkshops({
        page: 1,
        pageSize: RENDER_CAP,
        search: term || undefined
      })
        .then((found) => {
          if (titleGeneration.current !== mine) return;
          setTitleRows(found.items);
        })
        .catch(() => {
        })
        .finally(() => {
          if (titleGeneration.current === mine) setTitlePending(false);
        });
    }, term ? 300 : 0);
    return () => window.clearTimeout(timer);
  }, [formOpen, allowCreate, titleTerm]);

  const titleOptions = useMemo(() => {
    const seen = new Map<string, number>();
    for (const row of titleRows) {
      const name = row.title?.trim();
      if (!name) continue;
      seen.set(name, (seen.get(name) ?? 0) + 1);
    }
    const rows = [...seen.entries()].map(([name, count]) => ({
      value: name,
      label: name,
      hint: count > 1 ? `${count} workshops share this name` : undefined
    }));
    if (title && !seen.has(title)) {
      rows.unshift({ value: title, label: title, hint: "typed here" });
    }
    return rows;
  }, [titleRows, title]);

  const load = useCallback(async () => {
    const mine = ++generation.current;
    try {
      const result = await listDesignWorkshops({
        page,
        pageSize: 20,
        search: applied || undefined,
        statusFilter: status || undefined,
        workshopKind: kindFilter || undefined
      });
      if (mine !== generation.current) return;
      setData(result);
      setOffline(false);
      setError(null);
      void adoptServerSummaries(result.items);
    } catch (err) {
      if (mine !== generation.current) return;
      if (isUnreachable(err)) {
        setOffline(true);
        void refreshDrafts();
        return;
      }
      setError(err instanceof Error ? err.message : "Unable to load design workshops");
    }
  }, [page, applied, status, kindFilter]);

  const memory = useListMemory(
    "design-workshops",
    { query, applied, page, filters: { status, kind: kindFilter } },
    data !== null
  );
  const [placeRestored, setPlaceRestored] = useState(false);
  useEffect(() => {
    if (!placeRestored) return;
    load();
  }, [load, placeRestored]);

  const wantsNew = searchParams.get("new") === "1";
  const searchString = searchParams.toString();
  useEffect(() => {
    if (!wantsNew) return;
    if (allowCreate) setFormOpen(true);
    else setCreateRefused(true);
    const next = new URLSearchParams(searchString);
    next.delete("new");
    const query = next.toString();
    router.replace(query ? `/design-workshops?${query}` : "/design-workshops", { scroll: false });
  }, [wantsNew, allowCreate, searchString, router]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const list = await listReportTemplates();
        if (cancelled) return;
        setTemplates(list);
        if (list.length && !list.some((template) => template.id === "DCH_STANDARD")) setTemplateId(list[0].id);
      } catch {
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

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

  useEffect(() => {
    if (skipFirstDebounce.current) {
      skipFirstDebounce.current = false;
      return;
    }
    const timer = window.setTimeout(() => {
      setApplied(query);
      setPage(1);
    }, 350);
    return () => window.clearTimeout(timer);
  }, [query]);

  useEffect(() => {
    const remembered = memory.recall();
    if (remembered) {
      if ((remembered.query ?? "") !== "") skipFirstDebounce.current = true;
      setQuery(remembered.query ?? "");
      setApplied(remembered.applied ?? "");
      setPage(remembered.page ?? 1);
      setStatus(remembered.filters?.status ?? "");
      setKindFilter(remembered.filters?.kind ?? "");
    }
    setPlaceRestored(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const cluster = clusterFromSearchParams(searchParams);
    if (!cluster) return;
    skipFirstDebounce.current = true;
    setQuery(cluster);
    setApplied(cluster);
    setPage(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    let cancelled = false;
    listAllPages<Workshop>("/workshops", {
      pageSize: WORKSHOP_OPTION_PAGE_SIZE,
      workshopType: "DESIGN_PROTOTYPE",
      accessibleOnly: "true"
    })
      .then((result) => {
        if (!cancelled) {
          setSourceList({
            kind: "ok",
            rows: sortWorkshopsByOccurrence(result.items ?? []),
            total: result.total
          });
        }
      })
      .catch(() => {
        if (!cancelled) setSourceList({ kind: "failed" });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const sourceWorkshops = useMemo<readonly Workshop[]>(
    () => (sourceList.kind === "ok" ? sourceList.rows : []),
    [sourceList]
  );

  const sourceVoice: WorkshopListVoice = { table: "field", scoped: true, online: !deviceLooksOffline() };
  const sourceNotice = workshopListNotice(sourceList, sourceVoice);

  const sourceWorkshopOptions = useMemo(
    () => fieldWorkshopOptions(sourceList, { group: true, offPage: { mode: "refuse" } }),
    [sourceList]
  );

  function applySourceWorkshop(id: string) {
    setDirty(true);
    setSourceWorkshopId(id);
    const form = formRef.current;
    const picked = sourceWorkshops.find((w) => w.id === id);
    if (!form || !picked) return;

    const set = (name: string, value: string | null | undefined) => {
      const input = form.elements.namedItem(name);
      if (!(input instanceof HTMLInputElement) || !value) return;
      if (input.value.trim()) return;
      input.value = value;
    };
    set("title", picked.title);
    set("state", picked.location?.state ?? undefined);
    set("district", picked.location?.district ?? undefined);
    set("clusterName", picked.place);
    const start = picked.startDate ?? picked.date;
    const end = picked.endDate ?? picked.date;
    if (start || end) {
      setDuration({
        from: start ? new Date(start) : undefined,
        to: end ? new Date(end) : undefined
      });
    }
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const title = String(form.get("title") ?? "").trim();
    if (!title) return;

    setCreating(true);
    setError(null);
    try {
      const text = (key: string) => {
        const value = String(form.get(key) ?? "").trim();
        return value || undefined;
      };
      const designers = namedDesignerTeam({ chosen: designerUserIds, lead: leadDesignerId });
      const header = {
        title,
        templateId,
        workshopKind: workshopKind || undefined,
        designerUserId: designers.lead || undefined,
        designerUserIds: designers.team,
        craftName: craftPick.name || undefined,
        craftId: craftPick.id || undefined,
        clusterName: text("clusterName"),
        state: text("state"),
        district: text("district"),
        startDate: text("startDate"),
        endDate: text("endDate"),
        notes: text("notes"),
        workshopId: sourceWorkshopId || undefined
      };
      const created = await createWorkshopOrKeepItHere(header);
      formElement.reset();
      setTitle("");
      setTitleTerm("");
      setDuration({});
      setSourceWorkshopId("");
      setDesignerUserIds([]);
      setLeadDesignerId("");
      setDirty(false);
      setFormOpen(false);
      await refreshDrafts();
      router.push(`/design-workshops/${created.id}`);
    } catch (err) {
      setConfirmAction(null);
      setError(err instanceof Error ? err.message : "Unable to create the design workshop");
    } finally {
      setCreating(false);
    }
  }

  async function remove(workshop: DwSummary) {
    const ok = await confirm(
      deleteConfirm(
        "Delete this design workshop?",
        `"${workshop.title}" and all its stages leave the list.`,
        "An admin can restore it from Deleted workshops."
      )
    );
    if (!ok) return;
    try {
      await deleteDesignWorkshop(workshop.id);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to delete the design workshop");
    }
  }

  const rows = useMemo(() => {
    const serverRows = data?.items ?? [];
    const byId = new Map<string, DwSummary>();
    for (const row of serverRows) byId.set(row.id, row);

    const extras: DwSummary[] = [];
    for (const draft of drafts) {
      const unsent = draftIsUnsent(draft);
      const known = draft.remoteId ? byId.get(draft.remoteId) : undefined;
      if (known) {
        if (unsent) byId.set(known.id, draftSummary(draft));
        continue;
      }
      if (draft.remoteId === null || (offline && unsent)) extras.push(draftSummary(draft));
    }
    return [...extras, ...serverRows.map((row) => byId.get(row.id) ?? row)];
  }, [data, drafts, offline]);

  useEffect(() => {
    if (codeFor && !rows.some((row) => row.id === codeFor.id)) setCodeFor(null);
  }, [rows, codeFor]);

  const unsentIds = useMemo(
    () => new Set(drafts.filter(draftIsUnsent).map((draft) => draft.remoteId ?? draft.localId)),
    [drafts]
  );

  const orphanDrafts = useMemo(() => {
    const byRowId = new Map<string, DwDraft>();
    for (const draft of drafts) if (localDraftNeedsAWorkshop(draft)) byRowId.set(draft.localId, draft);
    return byRowId;
  }, [drafts]);

  const [moving, setMoving] = useState<DwDraft | null>(null);
  const offerMove = !allowCreate && allowWork;

  const offerLocalStart = allowWork && !allowCreate && offline;
  const unlinkedCount = offerMove ? orphanDrafts.size : 0;
  const promptLink = unlinkedCount > 0 && !offline;

  async function startHere() {
    const title = localTitle.trim();
    if (!title || startingLocal) return;
    setStartingLocal(true);
    setError(null);
    try {
      const started = await startLocalDraftHere({ title });
      setLocalTitle("");
      await refreshDrafts();
      router.push(`/design-workshops/${started.id}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to start a workshop on this device");
    } finally {
      setStartingLocal(false);
    }
  }

  const workshopTitleBlock = (workshop: (typeof rows)[number]) => (
    <>
      <OpenableRowLink href={`/design-workshops/${workshop.id}`}>{workshop.title}</OpenableRowLink>
      <div className="text-xs text-ink-500">{workshop.workshopCode ?? "No workshop code yet"}</div>
      {unsentIds.has(workshop.id) ? (
        <div className="mt-1 inline-flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
          <CloudOff className="h-3 w-3" aria-hidden />
          Saved on this device only
        </div>
      ) : null}
      {offerMove && orphanDrafts.has(workshop.id) ? (
        <div className="mt-1 text-xs leading-5 text-amber-800 dark:text-amber-100">
          {DW_LOCAL_DRAFT_UNLINKED} {DW_LOCAL_DRAFT_LINK_PROMPT}
        </div>
      ) : null}
    </>
  );

  const workshopActions = (workshop: (typeof rows)[number]) => (
    <RowActions>
      <Link className={rowAction("edit")} href={`/design-workshops/${workshop.id}`}>
        Open
      </Link>
      <Link className={rowAction("neutral")} href={`/design-workshops/${workshop.id}/report`}>
        Report
      </Link>
      <button
        type="button"
        className={rowAction("neutral", codeFor?.id === workshop.id ? "bg-surface-50" : undefined)}
        aria-expanded={codeFor?.id === workshop.id}
        onClick={() =>
          setCodeFor((current) =>
            current?.id === workshop.id ? null : { id: workshop.id, title: workshop.title }
          )
        }
      >
        {codeFor?.id === workshop.id ? "Hide code" : "Show code"}
      </button>
      {offerMove && orphanDrafts.has(workshop.id) ? (
        <button
          type="button"
          className={rowAction("neutral")}
          onClick={() => setMoving(orphanDrafts.get(workshop.id) ?? null)}
        >
          Move into a workshop
        </button>
      ) : null}
      {allowDelete ? (
        <button type="button" data-danger className={rowAction("danger")} onClick={() => remove(workshop)}>
          Delete
        </button>
      ) : null}
    </RowActions>
  );

  return (
    <>
      <PageHeader
        title="Design workshops"
        description="Your design and prototype workshops."
        icon={<DraftingCompass className="h-5 w-5" aria-hidden />}
        actions={
          allowCreate ? (
            <button
              type="button"
              className="field-button"
              onClick={() => (formOpen ? guard(() => setFormOpen(false)) : setFormOpen(true))}
            >
              <Plus className="h-4 w-4" aria-hidden />
              {formOpen ? "Close" : "New design workshop"}
            </button>
          ) : null
        }
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}

      {createRefused && allowWork ? (
        <div
          role="status"
          className="mb-4 rounded-md border border-amber-500/30 bg-amber-50 px-3 py-3 text-sm text-amber-900"
        >
          <div className="flex items-start gap-3">
            <Info className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
            <div className="grow">
              <p className="font-medium">An admin starts new workshops</p>
              <p className="mt-1 leading-6">{DESIGN_WORKSHOP_CREATE_REFUSAL}</p>
              <ContinueOnAllocatedWorkshop />
            </div>
            <button
              type="button"
              className="shrink-0 rounded px-2 py-1 text-xs font-medium underline-offset-2 hover:underline"
              onClick={() => setCreateRefused(false)}
            >
              Dismiss
            </button>
          </div>
        </div>
      ) : null}

      {offline ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          Offline — showing only workshops on this device. The full list returns when you reconnect.
        </div>
      ) : null}

      {offerLocalStart ? (
        <section className="panel mb-5 grid gap-3 p-4">
          <div>
            <h2 className="font-display text-lg font-bold text-ink-900">{DW_LOCAL_START_ACTION}</h2>
            <p className="mt-1 text-sm leading-6 text-ink-muted">{DW_LOCAL_START_NOTE}</p>
          </div>
          <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-end">
            <Field label="Workshop title" required>
              <TextInput
                value={localTitle}
                onChange={(event) => setLocalTitle(event.target.value)}
                maxLength={220}
                disabled={startingLocal}
              />
            </Field>
            <button
              type="button"
              className="field-button"
              disabled={startingLocal || !localTitle.trim()}
              onClick={startHere}
            >
              <Plus className="h-4 w-4" aria-hidden />
              {startingLocal ? "Starting…" : DW_LOCAL_START_ACTION}
            </button>
          </div>
        </section>
      ) : null}

      {promptLink ? (
        <div
          role="status"
          className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800"
        >
          {unlinkedCount === 1
            ? "One draft on this device isn't linked to a workshop. "
            : `${unlinkedCount} drafts on this device aren't linked to a workshop. `}
          Use “Move into a workshop” on the row.
        </div>
      ) : null}

      {allowCreate && formOpen ? (
        <form ref={formRef} onSubmit={submit} onInput={markDirty} className="panel mb-5 grid gap-4 p-4">
          <div>
            <h2 className="font-display text-lg font-bold text-ink-900">Start a design workshop</h2>
            <p className="mt-1 text-sm leading-6 text-ink-muted">
              Only the title is required.
            </p>
          </div>
          <FieldBlock label="Start from a recorded workshop">
            <Dropdown
              value={sourceWorkshopId}
              onChange={applySourceWorkshop}
              options={sourceWorkshopOptions.options}
              noneLabel={TYPE_DETAILS_INSTEAD}
              ariaLabel="Start from a recorded workshop"
              searchable
              emptyLabel={workshopEmptyLabel(sourceList, sourceVoice)}
            />
            {sourceList.kind === "ok" && sourceList.rows.length > 0 ? (
              <p className="text-xs leading-5 text-ink-500">
                Design &amp; Prototype workshops you can access.
              </p>
            ) : sourceNotice ? (
              <p className="text-xs leading-5 text-ink-500">{sourceNotice}</p>
            ) : null}
            {sourceList.kind === "ok" && sourceList.rows.length === 0 ? (
              <p className="text-xs leading-5 text-ink-500">{LINKED_WORKSHOP_KIND_GAP}</p>
            ) : null}
            <CappedListNotice cuts={[workshopCutSentence(sourceWorkshopOptions, { searchable: false })]} />
          </FieldBlock>

          <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
            <FieldBlock label="Workshop title" required>
              <Dropdown
                value={title}
                onChange={(next) => {
                  markDirty();
                  setTitle(next);
                }}
                options={titleOptions}
                placeholder="Type or pick a name"
                ariaLabel="Workshop title"
                serverQuery={{ value: titleTerm, onChange: setTitleTerm, pending: titlePending }}
                createAction={{ label: (term) => `Use “${term}” as the name`, onCreate: setTitle }}
              />
              <input
                type="text"
                name="title"
                value={title}
                required
                maxLength={220}
                onChange={() => undefined}
                tabIndex={-1}
                aria-hidden="true"
                className="pointer-events-none absolute h-0 w-0 border-0 p-0 opacity-0"
              />
            </FieldBlock>
            <FieldBlock label="Report template">
              <Dropdown
                value={templateId}
                onChange={(next) => {
                  markDirty();
                  setTemplateId(next);
                }}
                options={
                  templates.length
                    ? templates.map((template) => ({ value: template.id, label: template.name }))
                    : [{ value: templateId, label: "DCH standard workshop report" }]
                }
                ariaLabel="Report template"
                searchable
              />
            </FieldBlock>
            <FieldBlock label="Type of workshop">
              <Dropdown
                value={workshopKind}
                onChange={(next) => {
                  markDirty();
                  setWorkshopKind(next);
                }}
                options={kindChoices.options.map((option) => ({ value: option.value, label: option.label }))}
                ariaLabel="Type of workshop"
                placeholder="Not stated"
              />
            </FieldBlock>
            <FieldBlock label="Craft">
              <CraftSelect
                ariaLabel="Craft"
                value={craftPick.id}
                onDirty={markDirty}
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
              <input type="hidden" name="startDate" value={duration.from ? toIsoDate(duration.from) : ""} />
              <input type="hidden" name="endDate" value={duration.to ? toIsoDate(duration.to) : ""} />
              <DateRangePicker
                from={duration.from}
                to={duration.to}
                onChange={(next) => {
                  markDirty();
                  setDuration(next);
                }}
              />
            </div>
          </div>
          <WorkshopDesignerPicker
            values={designerUserIds}
            onChange={(next) => {
              markDirty();
              setDesignerUserIds(next);
            }}
            lead={leadDesignerId}
            onLeadChange={(next) => {
              markDirty();
              setLeadDesignerId(next);
            }}
            disabled={creating}
            offline={offline}
          />
          <Field label="Notes">
            <TextArea name="notes" />
          </Field>
          <ActionGroup>
            <button className="field-button" disabled={creating}>
              {creating ? "Creating…" : "Create design workshop"}
            </button>
            <button
              type="button"
              className="field-button-secondary"
              onClick={() => guard(() => setFormOpen(false))}
            >
              Cancel
            </button>
          </ActionGroup>
        </form>
      ) : null}

      {isAdmin(user) && adminMode ? (
        <section className="panel mb-5 overflow-hidden">
          <button
            type="button"
            className="flex w-full items-center justify-between gap-3 px-4 py-3 text-left"
            aria-expanded={viewersOpen}
            onClick={() => {
              const next = !viewersOpen;
              setViewersOpen(next);
              if (next) setViewersRefresh((token) => token + 1);
            }}
          >
            <span>
              <span className="font-display text-lg font-bold text-ink-900">Designers on a workshop</span>
              <span className="mt-1 block text-sm leading-6 text-ink-muted">
                Choose who can open and fill in a workshop.
              </span>
            </span>
            <span aria-hidden className="shrink-0 text-sm text-ink-500">
              {viewersOpen ? "Hide" : "Show"}
            </span>
          </button>
          {viewersOpen ? (
            <div className="border-t border-line-200 p-4">
              <DesignWorkshopViewersPanel refreshToken={viewersRefresh} />
            </div>
          ) : null}
        </section>
      ) : null}

      {adminMode && !isAdmin(user) ? (
        <div className="mb-5">
          <DeletedWorkshopsCard />
        </div>
      ) : null}

      <div className="mb-4 grid gap-2 sm:grid-cols-[1fr_14rem] lg:grid-cols-[1fr_14rem_14rem]">
        <SearchInput
          value={query}
          onChange={setQuery}
          onSubmit={() => {
            setApplied(query);
            setPage(1);
          }}
          ariaLabel="Search design workshops by title, craft, cluster or workshop code"
          placeholder="Search workshops"
        />
        <Dropdown
          value={status}
          onChange={(next) => {
            setStatus(next);
            setPage(1);
          }}
          options={STATUS_OPTIONS}
          ariaLabel="Filter by status"
          advanceOnSelect={false}
        />
        <Dropdown
          value={kindFilter}
          onChange={(next) => {
            setKindFilter(next);
            setPage(1);
          }}
          options={[
            { value: "", label: "Any type of workshop" },
            ...kindChoices.options.map((option) => ({ value: option.value, label: option.label }))
          ]}
          ariaLabel="Filter by type of workshop"
          advanceOnSelect={false}
        />
      </div>

      <section className="panel overflow-hidden">
        {data === null ? (
          <div className="p-4 text-sm text-ink-700">Loading…</div>
        ) : rows.length === 0 ? (
          <div className="p-4">
            <EmptyState
              title="No design workshops found"
              body={
                allowCreate
                  ? "Start one with the button above."
                  : emptyScopeDoors.length
                    ?
                      "Workshops you supervise are on a separate screen."
                    : allowWork
                      ?
                        "Ask an admin to create one and add you as a designer."
                      : "Workshops shared with you appear here."
              }
            />
            {emptyScopeDoors.length ? (
              <div className="mt-4 rounded-md border border-line-200 bg-surface-50 p-4">
                <h3 className="text-sm font-medium text-ink-900">Where your workshops are</h3>
                <p className="mt-1 text-sm leading-6 text-ink-700">
                  Workshops you supervise:
                </p>
                <ul className="mt-3 grid gap-2">
                  {emptyScopeDoors.map((door) => (
                    <li key={door.href} className="text-sm leading-6">
                      <Link
                        href={door.href}
                        className="font-medium text-purple-700 dark:text-purple-300 underline-offset-2 hover:underline"
                      >
                        {door.label}
                      </Link>
                      <span className="text-ink-muted"> — {door.note}</span>
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
          </div>
        ) : (
          <>
          <ul className="divide-y divide-line-200 md:hidden">
            {rows.map((workshop) => (
              <li key={workshop.id}>
                <OpenableCard href={`/design-workshops/${workshop.id}`} className="px-4 py-3">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div className="min-w-0 flex-1">{workshopTitleBlock(workshop)}</div>
                  <StatusBadge status={workshop.status} />
                </div>
                <p className="mt-1 text-xs leading-5 text-ink-500">
                  {[
                    workshop.craftName,
                    workshop.clusterName,
                    [workshop.district, workshop.state].filter(Boolean).join(", "),
                    workshop.startDate
                      ? `${formatDate(workshop.startDate)}${workshop.endDate ? ` to ${formatDate(workshop.endDate)}` : ""}`
                      : null
                  ]
                    .filter(Boolean)
                    .join(" · ")}
                </p>
                <div className="mt-3 [&>div]:justify-start">{workshopActions(workshop)}</div>
                </OpenableCard>
                {codeFor?.id === workshop.id ? (
                  <div className="mx-4 mb-3 rounded-md bg-surface-50 p-3">
                    <RecordCodeCard recordType="designWorkshop" id={workshop.id} title={workshop.title} />
                  </div>
                ) : null}
              </li>
            ))}
          </ul>
          <div className="hidden overflow-x-auto md:block">
            <table className="w-full min-w-[900px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <ResizableTh>Workshop</ResizableTh>
                  <ResizableTh>Craft / cluster</ResizableTh>
                  <ResizableTh>Place</ResizableTh>
                  <ResizableTh>Dates</ResizableTh>
                  <ResizableTh>Status</ResizableTh>
                  <ResizableTh className="text-right">Actions</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {rows.map((workshop) => (
                  <Fragment key={workshop.id}>
                  <OpenableRow href={`/design-workshops/${workshop.id}`}>
                    <td className="px-4 py-3">
                      {workshopTitleBlock(workshop)}
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {workshop.craftName ?? "—"}
                      {workshop.clusterName ? <span className="block text-xs text-ink-500">{workshop.clusterName}</span> : null}
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {[workshop.district, workshop.state].filter(Boolean).join(", ") || "—"}
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {workshop.startDate ? formatDate(workshop.startDate) : "—"}
                      {workshop.endDate ? <span className="block text-xs text-ink-500">to {formatDate(workshop.endDate)}</span> : null}
                    </td>
                    <td className="px-4 py-3">
                      <StatusBadge status={workshop.status} />
                    </td>
                    <td className="px-4 py-3 text-right">
                      {workshopActions(workshop)}
                    </td>
                  </OpenableRow>
                  {codeFor?.id === workshop.id ? (
                    <tr className="bg-surface-50">
                      <td className="px-4 py-3" colSpan={6}>
                        <RecordCodeCard recordType="designWorkshop" id={workshop.id} title={workshop.title} />
                      </td>
                    </tr>
                  ) : null}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
          </>
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>

      <AdoptLocalDraftDialog
        open={moving !== null}
        onClose={() => setMoving(null)}
        draft={moving}
        drafts={drafts}
        onAdopted={(remoteId) => {
          setMoving(null);
          router.push(`/design-workshops/${remoteId}`);
        }}
      />

      <UnsavedChangesDialog
        open={confirmAction !== null}
        saving={creating}
        onKeepEditing={() => setConfirmAction(null)}
        onDiscard={() => {
          const action = confirmAction;
          setConfirmAction(null);
          setDirty(false);
          action?.();
        }}
        onSave={() => formRef.current?.requestSubmit()}
      />
    </>
  );
}

function ContinueOnAllocatedWorkshop() {
  const router = useRouter();
  const [rows, setRows] = useState<DwSummary[] | null>(null);
  const [total, setTotal] = useState(0);
  const [chosen, setChosen] = useState("");
  const [note, setNote] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const [page, fallback] = await Promise.all([
        listDesignWorkshops({ page: 1, pageSize: WORKSHOP_OPTION_PAGE_SIZE }).catch(() => null),
        readDesignWorkshopDefault()
      ]);
      if (cancelled) return;
      setRows(page?.items ?? []);
      setTotal(page?.total ?? 0);
      const id = fallback?.workshopId ?? "";
      if (id && (page?.items ?? []).some((row) => row.id === id)) {
        setChosen(id);
        setNote(designWorkshopDefaultNote(fallback));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const optionSet = useMemo(
    () =>
      designWorkshopOptions(
        { kind: "ok", rows: rows ?? [], total },
        { group: true, offPage: { mode: "refuse" } }
      ),
    [rows, total]
  );

  if (!rows || rows.length === 0) return null;

  return (
    <div className="mt-3 grid gap-2 sm:max-w-xl">
      <FieldBlock
        label="Or carry on with a workshop you already have"
        hint={note ? <p className="text-[11px] leading-4 text-amber-900/80">{note}</p> : undefined}
      >
        <Dropdown
          value={chosen}
          onChange={(id) => {
            setChosen(id);
            setNote(null);
          }}
          options={optionSet.options}
          placeholder="Choose a workshop"
          searchable
          advanceOnSelect={false}
        />
      </FieldBlock>
      <CappedListNotice cuts={[workshopCutSentence(optionSet, { searchable: false })]} />
      <div>
        <button
          type="button"
          className="field-button"
          disabled={!chosen}
          onClick={() => router.push(`/design-workshops/${chosen}/stages`)}
        >
          Open this workshop
        </button>
      </div>
    </div>
  );
}

function draftIsUnsent(draft: DwDraft): boolean {
  if (draft.remoteId === null || draft.headerDirtyAt !== null) return true;
  return Object.values(draft.stages).some((stage) => stage.dirtyAt !== null || stage.removedFrom.length > 0);
}

