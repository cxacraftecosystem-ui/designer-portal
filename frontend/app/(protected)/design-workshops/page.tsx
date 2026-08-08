"use client";

/**
 * The design-workshop list, and the one form that starts a new one.
 *
 * "use client" is not a preference here. There is NO server-side data fetching anywhere in this app
 * — the bearer token lives in `localStorage`, which a server component cannot read — so every page
 * that touches the API is a client component. A server component here would render an empty list to
 * every visitor and no error anywhere would say why.
 *
 * ONLY THE TITLE IS REQUIRED TO CREATE ONE, and that mirrors the API exactly (`DesignWorkshopCreate`
 * takes `title` and nothing else). A workshop is opened on day one, in a room, before the sanction
 * order number is to hand; the BASIC-tier fields of stage 1 are enforced when a report is generated,
 * not at the moment the record is created. Every other box on this form is a convenience that
 * pre-fills a column stage 1 would otherwise fill in later.
 *
 * THE LIST READS THE LOCAL DRAFT STORE FIRST AND RECONCILES WITH THE SERVER. Two consequences, both
 * deliberate. A workshop STARTED with no connection has no server row at all, so it can only ever
 * appear from `lib/designWorkshopStore` — leaving it out would mean a designer who opened a workshop
 * in a courtyard on Monday cannot find it on Tuesday, and would start a second one. And a row the
 * server does return is drawn from the local copy where that copy is newer, so a title corrected
 * offline this morning is not shown back as the stale one until it syncs. Every row that has
 * something unsent says so, on the row, in words.
 *
 * CREATING ONE OFFLINE IS ALLOWED. `createLocalDraft` mints a `dwlocal-…` id and the app navigates
 * straight into it; `syncDesignWorkshopDrafts` turns it into a real record on the next connection
 * and the local id keeps resolving afterwards. Refusing to create offline would make the first act
 * of a fortnight in the field the one act that needs signal.
 */

import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { CloudOff, DraftingCompass, Plus } from "lucide-react";

import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { DateRangePicker, toIsoDate } from "@/components/forms/DateTimeField";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { StatusBadge } from "@/components/StatusBadge";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown } from "@/components/ui/Dropdown";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import {
  createDesignWorkshop,
  deleteDesignWorkshop,
  listDesignWorkshops,
  listReportTemplates,
  type DwSummary,
  type DwTemplate
} from "@/lib/designWorkshops";
import {
  adoptServerSummaries,
  createLocalDraft,
  draftSummary,
  getDraftsSnapshot,
  getServerDraftsSnapshot,
  refreshDrafts,
  subscribeDrafts,
  type DwDraft,
  type DwDraftHeader
} from "@/lib/designWorkshopStore";
import { isTransient, isUnreachable } from "@/lib/offline";
import { formatDate } from "@/lib/format";
import { canCreateRecords, isAdmin } from "@/lib/permissions";
import type { PageResult, Workshop } from "@/lib/types";
import { listResource } from "@/lib/api";
import { sortWorkshopsByOccurrence } from "@/components/forms/WorkshopSelect";

/**
 * The five statuses `DesignWorkshopStatus` declares, plus the reserved empty option.
 *
 * Empty means EVERYTHING, by absence — the same rule the workshop-scope picker and the record
 * filters follow, and the reason `buildQuery` drops "" exactly as it drops null. Listing every
 * status instead would silently exclude any status added to the enum after this page was written.
 */
const STATUS_OPTIONS = [
  { value: "", label: "Any status" },
  { value: "DRAFT", label: "Draft" },
  { value: "IN_PROGRESS", label: "In progress" },
  { value: "COMPLETE", label: "Complete" },
  { value: "SUBMITTED", label: "Submitted" },
  { value: "ARCHIVED", label: "Archived" }
];

export default function DesignWorkshopsPage() {
  const confirm = useConfirm();
  const router = useRouter();
  const { user } = useAuth();
  const { adminMode } = useAdminView();
  const allowCreate = canCreateRecords(user);
  const allowDelete = isAdmin(user);

  /**
   * Every draft this browser holds, live.
   *
   * `useSyncExternalStore` rather than a fetch-on-mount: the sync pass and every stage autosave
   * publish through the same store, so a workshop that finishes syncing while this page is open
   * loses its "saved on this device only" chip without anybody reloading.
   */
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
  const [templateId, setTemplateId] = useState("DCH_STANDARD");
  /*
    The workshops a 22-stage record may be started FROM: only those filed as a Design & Prototype
    Development Workshop. See the picker's own note for why the whole workshop list is the wrong
    thing to offer.
  */
  const [sourceWorkshops, setSourceWorkshops] = useState<Workshop[]>([]);
  const [sourceWorkshopId, setSourceWorkshopId] = useState("");
  const formRef = useRef<HTMLFormElement | null>(null);
  /**
   * The optional start/end of the workshop being created, held here rather than read off the DOM.
   *
   * A range is a pair with a rule between its halves, so it cannot be two uncontrolled boxes: the
   * picker has to see the current start to refuse an earlier end. Empty by default and it stays that
   * way unless somebody picks — only the title is required to open a workshop.
   */
  const [duration, setDuration] = useState<{ from?: Date; to?: Date }>({});
  const skipFirstDebounce = useRef(true);

  /**
   * List pages count fetch generations rather than aborting: `listDesignWorkshops` takes no signal,
   * and what actually matters is IGNORING the late answer, not cancelling the request. Without this
   * a typed search whose first response arrives after the second one overwrites the newer list with
   * older rows, and the screen ends up showing results for a query nobody can see any more.
   */
  const generation = useRef(0);

  const load = useCallback(async () => {
    const mine = ++generation.current;
    try {
      const result = await listDesignWorkshops({
        page,
        pageSize: 20,
        search: applied || undefined,
        statusFilter: status || undefined
      });
      if (mine !== generation.current) return;
      setData(result);
      setOffline(false);
      setError(null);
      // Keep a local copy of every row the list saw, so this page and the stage index still draw
      // tomorrow morning in a courtyard. Not awaited: a slow IndexedDB write must never hold up the
      // render of a list the designer is already looking at.
      void adoptServerSummaries(result.items);
    } catch (err) {
      if (mine !== generation.current) return;
      // `isUnreachable`, NOT `isTransient`. The latter answers "is it worth retrying" and counts
      // every 5xx as yes, so a repository that answered and then failed put up the amber "there is
      // no connection, this shows only the workshops saved in this browser" banner — which sends
      // the designer to look at their signal and hides a server fault behind a sentence that
      // cannot be acted on. A server that spoke gets its own words shown.
      if (isUnreachable(err)) {
        // Not an error box. The local drafts below are a real, usable list — saying "unable to load"
        // over the top of them is how a designer concludes their fortnight is gone.
        setOffline(true);
        void refreshDrafts();
        return;
      }
      setError(err instanceof Error ? err.message : "Unable to load design workshops");
      // `data` is deliberately left alone on a failed refresh. Emptying it would replace a list the
      // designer can still read with "No design workshops yet", which is indistinguishable from
      // having none — the single most repeated bug class in this repository.
    }
  }, [page, applied, status]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const list = await listReportTemplates();
        if (cancelled) return;
        setTemplates(list);
        // Only adopt a server default when this build's guess is not among the offered templates —
        // otherwise opening the page would silently move a designer off DCH_STANDARD.
        if (list.length && !list.some((template) => template.id === "DCH_STANDARD")) setTemplateId(list[0].id);
      } catch {
        // The picker degrades to the single default id below; the list still loads. A workshop
        // created with DCH_STANDARD can have its template changed on the report page at any time.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // Live search: 350ms after typing stops, Enter applies immediately through onSubmit. Both go
  // through the same state so the generation guard above stays the only race protection needed.
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
    let cancelled = false;
    listResource<Workshop>("/workshops", { pageSize: 100, workshopType: "DESIGN_PROTOTYPE" })
      .then((result) => {
        if (!cancelled) setSourceWorkshops(sortWorkshopsByOccurrence(result.items ?? []));
      })
      // Silent: this picker is a convenience over boxes the designer can always type into, and an
      // error banner for a shortcut that failed would read as the form itself being broken.
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  /**
   * Copy a chosen workshop's cover details into the form.
   *
   * Written into the DOM rather than held as state because this form is uncontrolled — every box is
   * read back through `FormData` on submit. Setting the values here keeps that one source of truth
   * and leaves every field editable, which matters: the workshop row is a starting point and the
   * designer is the one standing in the room.
   *
   * Only BLANK boxes are filled. A designer who has already typed a cluster name and then links a
   * workshop must not have their answer replaced by the row's — that is the same rule the stage-1
   * reference hydration follows, and it was a real bug there before it was a rule.
   */
  function applySourceWorkshop(id: string) {
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
    // React nulls `event.currentTarget` across an await, so the FormData must be built before any
    // async work — not after the first `await`, where it reads as null and every field is empty.
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
      const header = {
        title,
        templateId,
        craftName: text("craftName"),
        clusterName: text("clusterName"),
        state: text("state"),
        district: text("district"),
        startDate: text("startDate"),
        endDate: text("endDate"),
        notes: text("notes"),
        // Links the 22-stage record to the workshop it belongs to, so the two are one thing rather
        // than two records that happen to share a title.
        workshopId: sourceWorkshopId || undefined
      };
      // Offline, the workshop is created HERE, with a local id, and becomes a real record on the
      // next connection. The alternative — refusing — makes the very first act of a fortnight in the
      // field the one act that needs signal, and a designer standing in a room with the participants
      // in front of them would open a paper notebook instead.
      const created =
        typeof navigator !== "undefined" && navigator.onLine === false
          ? await createLocalOffline(header)
          : await createDesignWorkshop(header).catch(async (err) => {
              // `isTransient` and NOT `isUnreachable`, and the two lines above are why the same
              // file uses both. This is not a message: it is the decision whether to KEEP the
              // workshop on this device and retry it, and "is it worth trying again" is exactly
              // that question — a 5xx on a create is worth carrying rather than losing the room.
              // The failed-load handler above is a message, and there the same test would lie.
              if (!isTransient(err)) throw err;
              return createLocalOffline(header);
            });
      formElement.reset();
      // `reset()` only clears what the DOM owns, and the range lives in React state — without this
      // the next "New design workshop" opens pre-filled with the dates of the one just created, and
      // a designer starting their second workshop of the week inherits the first one's fortnight.
      setDuration({});
      setSourceWorkshopId("");
      setFormOpen(false);
      await refreshDrafts();
      // A brand-new workshop is 22 empty stages, so the only useful next step is opening it. Going
      // there directly beats dropping the designer back onto a list to hunt for their own row —
      // and `router.push` rather than `location.assign` keeps the app's own transition, its scroll
      // restoration and the token in memory instead of reloading the whole bundle.
      router.push(`/design-workshops/${created.id}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to create the design workshop");
    } finally {
      setCreating(false);
    }
  }

  async function remove(workshop: DwSummary) {
    const ok = await confirm(
      deleteConfirm(
        "Delete this design workshop?",
        `"${workshop.title}" is removed from the list, along with every stage recorded against it.`,
        // Stated because it is TRUE and because it is unlike everything else in this app: nothing
        // else here has a soft delete, so a reader who has met the artisan and product dialogs will
        // assume this one is permanent and will hesitate over something an admin can undo.
        "Nothing is erased — this is a soft delete kept for the research record, and an admin can restore it."
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

  /**
   * The server's page, with the local drafts folded in.
   *
   * A draft the server also returned REPLACES the server row when it holds unsent edits — the local
   * copy is then the newer of the two, and showing the stale title back to somebody who corrected it
   * an hour ago reads as the correction having been thrown away. A draft the server did not return
   * is prepended, because it is either brand new here or the connection is down; either way it is a
   * workshop that exists and a list that omitted it would be lying.
   *
   * Drafts with nothing local about them at all (a row this browser merely cached on a previous
   * visit) are NOT prepended when the server answered — they would resurrect rows that another
   * filter, another page or another admin's deletion has legitimately removed from this view.
   */
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
      // Never synced, or the server could not be asked. Both belong on screen.
      if (draft.remoteId === null || offline) extras.push(draftSummary(draft));
    }
    return [...extras, ...serverRows.map((row) => byId.get(row.id) ?? row)];
  }, [data, drafts, offline]);

  /** Which rows carry something this device has not sent — read once, drawn on the row. */
  const unsentIds = useMemo(
    () => new Set(drafts.filter(draftIsUnsent).map((draft) => draft.remoteId ?? draft.localId)),
    [drafts]
  );

  return (
    <>
      <PageHeader
        title="Design workshops"
        description="The 22-stage design and prototype workshop record: setup and participants, cluster background, market survey, sketches, prototypes, costing, outcomes and the generated report."
        icon={<DraftingCompass className="h-5 w-5" aria-hidden />}
        actions={
          // Gated to NOTHING rather than to a disabled button: an ungated "New …" invites every
          // tier to press a control that lands on a refusal from `assert_can_create_records`.
          allowCreate ? (
            <button type="button" className="field-button" onClick={() => setFormOpen((open) => !open)}>
              <Plus className="h-4 w-4" aria-hidden />
              {formOpen ? "Close" : "New design workshop"}
            </button>
          ) : null
        }
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}

      {offline ? (
        // Says what is on screen and what is not. A list that silently shows only the local subset
        // is indistinguishable from a repository with three workshops in it.
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          There is no connection, so this shows only the design workshops saved in this browser. Others in the repository are
          not listed and searching cannot reach them. Everything here is fully editable.
        </div>
      ) : null}

      {allowCreate && formOpen ? (
        <form ref={formRef} onSubmit={submit} className="panel mb-5 grid gap-4 p-4">
          <div>
            <h2 className="font-display text-lg font-bold text-ink-900">Start a design workshop</h2>
            <p className="mt-1 text-sm leading-6 text-ink-muted">
              Only the title is needed to begin. Everything else here is also asked in stage 1 and will be filled in from
              there — this is the shortcut for what is already known in the room.
            </p>
          </div>
          {/*
            START FROM A WORKSHOP THAT IS ALREADY RECORDED.

            The cover details of a 22-stage record — the craft, the cluster, the state, the district
            and the fortnight it ran — are already on the `Workshop` row somebody filed when the
            workshop was set up. Retyping them here is how the two come to disagree, and the report's
            cover page is built from the retyped copy.

            Only workshops MARKED "Design & Prototype Development Workshop" are offered (the kind
            dropdown on /workshops). Offering every craft-documentation visit ever recorded is what
            made this unusable as a picker rather than a list.

            Choosing one FILLS the boxes below and links the record; every box stays editable,
            because the workshop row is a starting point and the designer is the one in the room.
          */}
          <FieldBlock label="Start from a recorded workshop">
            <Dropdown
              value={sourceWorkshopId}
              onChange={applySourceWorkshop}
              options={[
                { value: "", label: "Do not link a workshop — type the details below" },
                ...sourceWorkshops.map((w) => ({
                  value: w.id,
                  label: w.place ? `${w.title} · ${w.place}` : w.title
                }))
              ]}
              ariaLabel="Start from a recorded workshop"
              searchable
            />
            <p className="text-xs leading-5 text-ink-500">
              {sourceWorkshops.length
                ? "Only workshops filed as a Design & Prototype Development Workshop appear here."
                : "No design & prototype workshops are recorded yet. Mark one on the Workshops page to use it here."}
            </p>
          </FieldBlock>

          <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
            <Field label="Workshop title" required>
              <TextInput name="title" required maxLength={220} />
            </Field>
            {/* FieldBlock, not Field: `Field` is a <label>, and a <label> wrapped around a themed
                dropdown forwards a stray click into the menu and slams it shut after one pick. */}
            <FieldBlock label="Report template">
              <Dropdown
                value={templateId}
                onChange={setTemplateId}
                options={
                  templates.length
                    ? templates.map((template) => ({ value: template.id, label: template.name }))
                    : [{ value: templateId, label: "DCH standard workshop report" }]
                }
                ariaLabel="Report template"
              />
            </FieldBlock>
            <Field label="Craft">
              <TextInput name="craftName" maxLength={160} />
            </Field>
            <Field label="Cluster">
              <TextInput name="clusterName" maxLength={160} />
            </Field>
            <Field label="State">
              <TextInput name="state" maxLength={80} />
            </Field>
            <Field label="District">
              <TextInput name="district" maxLength={80} />
            </Field>
            {/*
              The workshop's duration, as ONE range control rather than two `<TextInput type="date">`
              boxes. Two things were wrong with the pair it replaces, and only the second is visible.

              A native date input lays itself out and, more importantly, FORMATS itself according to
              the BROWSER's locale rather than this app's — dd/mm/yyyy on a handset set to en-IN,
              mm/dd/yyyy on the reviewer's laptop set to en-US. A workshop entered as 02/03/2026 to
              03/04/2026 is therefore either February-to-March or March-to-April, both perfectly
              plausible, and nothing ever errors: the report simply prints whichever reading the box
              happened to offer on the day it was typed. `DateRangePicker` shows a fixed dd/mm/yyyy
              and a calendar grid, so the month is chosen by name and cannot be inferred wrongly.

              And the pair could be entered backwards. Two independent boxes have no idea about one
              another, so an end date a month BEFORE the start saved happily — a workshop of negative
              duration, on the cover of a DCH report. The shared grid cannot produce one: typing a
              start after the current end drags the end along with it.

              This is deliberately NOT `components/forms/DateRangeField`, which is the same picker
              wrapped for the /workshops editor. That wrapper defaults an absent range to TODAY, which
              is right when editing a workshop that has dates and wrong here, where the whole promise
              of this form is that only the title is required — it would stamp today's date onto every
              workshop opened before its dates were known.
            */}
            <div className="grid gap-1 md:col-span-2">
              {/* `yyyy-mm-dd`, exactly what the two native boxes submitted, so `submit` below and the
                  endpoint's `_parse_date` are untouched. Empty stays empty: an unset range must send
                  nothing at all, not a date nobody chose. */}
              <input type="hidden" name="startDate" value={duration.from ? toIsoDate(duration.from) : ""} />
              <input type="hidden" name="endDate" value={duration.to ? toIsoDate(duration.to) : ""} />
              <DateRangePicker from={duration.from} to={duration.to} onChange={setDuration} />
            </div>
          </div>
          <Field label="Notes">
            <TextArea name="notes" />
          </Field>
          <div className="flex gap-2">
            <button className="field-button" disabled={creating}>
              {creating ? "Creating…" : "Create design workshop"}
            </button>
            <button type="button" className="field-button-secondary" onClick={() => setFormOpen(false)}>
              Cancel
            </button>
          </div>
        </form>
      ) : null}

      <div className="mb-4 grid gap-2 sm:grid-cols-[1fr_14rem]">
        <SearchInput
          value={query}
          onChange={setQuery}
          onSubmit={() => {
            setApplied(query);
            setPage(1);
          }}
          placeholder="Search by title, craft, cluster or workshop code"
        />
        <Dropdown
          value={status}
          onChange={(next) => {
            setStatus(next);
            setPage(1);
          }}
          options={STATUS_OPTIONS}
          ariaLabel="Filter by status"
          // A dropdown that filters the screen it sits on must NOT advance focus on select: jumping
          // away from the control you are adjusting is wrong when the control is the adjustment.
          advanceOnSelect={false}
        />
      </div>

      <section className="panel overflow-hidden">
        {data === null ? (
          // null is "still loading" and [] is "genuinely none" — a deliberate distinction. Saying
          // "no design workshops yet" during a fetch is both wrong and discouraging.
          <div className="p-4 text-sm text-ink-700">Loading…</div>
        ) : rows.length === 0 ? (
          <div className="p-4">
            <EmptyState
              title="No design workshops found"
              body={
                allowCreate
                  ? "Start one with the button above. A workshop can be created with nothing but a title and filled in over the following weeks."
                  : "Design workshops you have access to will appear here."
              }
            />
          </div>
        ) : (
          <div className="overflow-x-auto">
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
                  <tr key={workshop.id}>
                    <td className="px-4 py-3">
                      <Link href={`/design-workshops/${workshop.id}`} className="font-medium text-ink-900 underline-offset-2 hover:underline">
                        {workshop.title}
                      </Link>
                      {/* The code is denormalised from stage 1 and is null until that stage has
                          been saved — so its absence means "stage 1 is not done", not "missing". */}
                      <div className="text-xs text-ink-500">{workshop.workshopCode ?? "No workshop code yet"}</div>
                      {unsentIds.has(workshop.id) ? (
                        // Worded, not merely tinted — the same sentence the banner in the protected
                        // layout uses, so the two cannot be read as two different situations.
                        <div className="mt-1 inline-flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
                          <CloudOff className="h-3 w-3" aria-hidden />
                          Saved on this device only
                        </div>
                      ) : null}
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
                      <RowActions>
                        <Link className={rowAction("edit")} href={`/design-workshops/${workshop.id}`}>
                          Open
                        </Link>
                        <Link className={rowAction("neutral")} href={`/design-workshops/${workshop.id}/report`}>
                          Report
                        </Link>
                        {/* Deleting is stricter than editing everywhere in this app, and the
                            control additionally respects admin view so an admin browsing as an
                            ordinary user is not offered it. */}
                        {allowDelete && adminMode ? (
                          <button type="button" className={rowAction("danger")} onClick={() => remove(workshop)}>
                            Delete
                          </button>
                        ) : null}
                      </RowActions>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>
    </>
  );
}

/** Does this draft hold anything the server has not confirmed? */
function draftIsUnsent(draft: DwDraft): boolean {
  if (draft.remoteId === null || draft.headerDirtyAt !== null) return true;
  return Object.values(draft.stages).some((stage) => stage.dirtyAt !== null || stage.removedFrom.length > 0);
}

/**
 * Start a workshop that lives only here, and answer in the shape the create handler expects.
 *
 * The id it returns is the LOCAL one, and every design-workshop route resolves either id, so the
 * navigation that follows works immediately and keeps working after the record is created on the
 * server — the URL simply goes on naming the draft.
 */
async function createLocalOffline(header: Partial<DwDraftHeader> & { title: string }): Promise<{ id: string }> {
  /*
    THE WHOLE HEADER, SPREAD — NEVER A HAND-COPIED FIELD LIST.

    This function used to declare its own nine-key parameter type and re-enumerate those nine keys
    into `createLocalDraft`. `workshopId` was in neither list, so a design workshop created without
    a connection — or created online when the POST merely 500'd once, which takes the same fallback
    — silently dropped the workshop record the designer had just chosen from the picker. Nothing
    caught it: TypeScript does not apply excess-property checking to a variable, so the caller's
    extra key was legal and invisible, and every OTHER field survived, which made the loss look
    like a correctly pre-filled row. The consequence surfaces a fortnight later, on the stages that
    scope their reference pickers to the linked workshop: `refScope: "WORKSHOP"` falls back to the
    whole table and the designer picks participants out of the entire repository.

    Taking `Partial<DwDraftHeader>` means this function cannot drift from the header shape again —
    a field added to `DwDraftHeader` arrives here for free. `createLocalDraft` prunes the keys that
    are present-but-undefined, which is what an unfilled box on this form produces.
  */
  const draft = await createLocalDraft(header);
  return { id: draft.localId };
}
