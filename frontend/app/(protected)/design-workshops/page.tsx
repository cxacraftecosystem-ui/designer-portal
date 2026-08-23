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
 * CREATING ONE OFFLINE IS ALLOWED — TO THE ACCOUNTS THAT MAY CREATE ONE AT ALL. `createLocalDraft`
 * mints a `dwlocal-…` id and the app navigates straight into it; `syncDesignWorkshopDrafts` turns it
 * into a real record on the next connection and the local id keeps resolving afterwards. Refusing to
 * create offline would make the first act of a fortnight in the field the one act that needs signal.
 *
 * WHO MAY START ONE CHANGED, AND THIS PAGE IS WHERE THAT IS FELT. Only admins and the master admin
 * (`canCreateDesignWorkshops`) — a workshop is the container a fortnight of records lives in and the
 * unit the ministry indexes and funds, so opening one belongs to whoever holds the sanction order.
 * A DESIGNER STILL DOES EVERYTHING ELSE: they open the workshops they have access to, fill all 22
 * stages, create records inside them and generate the report, which is why the route guard is still
 * the wider `canRunDesignWorkshops` and why this page is emphatically not hidden from them.
 *
 * THREE THINGS FOLLOW, ALL OF THEM ON THIS PAGE:
 *   1. The create control is gone for a designer, and a SENTENCE takes its place — who can create
 *      one and what to do instead — rather than a greyed button, which says "no" and answers
 *      nothing. It appears when they ask for it (`?new=1`, the dashboard's "New workshop") and in
 *      the empty state, where there is otherwise nothing on screen to explain itself.
 *   2. The refusal is enforced in `lib/designWorkshopStore.createLocalDraft` too, not only by the
 *      server, because the offline path is the one that matters: a rule a designer only meets at
 *      sync time is a rule they meet after two days of fieldwork have gone into a record that can
 *      never be accepted.
 *   3. Drafts that were ALREADY on the device when this shipped are adopted, not stranded and never
 *      deleted — "Move into a workshop" on the row, `AdoptLocalDraftDialog`, and
 *      `adoptDraftIntoWorkshop` in the store.
 *
 * AND THE OTHER HALF OF THAT RULE LIVES HERE TOO: if only an admin may open a workshop, an admin
 * must be able to put the designers onto it without leaving the page where they just made it. That
 * is the collapsed "Designers on a workshop" section below — `DesignWorkshopViewersPanel`, the same
 * component /workshop-access/manage mounts, over `DesignWorkshopViewer`. The long note at the mount
 * point says why that is the right one of the three rosters a designer's name can appear on, why the
 * section is gated on `isAdmin` rather than on `allowCreate`, and why it is ANDed with `adminMode`
 * here when the panel's other mount is exempt from that toggle.
 *
 * AND THE WORKSHOP'S SHAREABLE CODE is offered from every row, in an expanded row beneath the one
 * whose button was pressed (`app/(protected)/media/page.tsx`'s pattern — the card belongs where the
 * finger was, not at the top of a list somebody has scrolled). It is how a group ends up on ONE
 * workshop instead of rival copies of a fortnight; the note at the mount says what a scan can and
 * cannot do, and why a workshop that exists only on this device gets a refusal there rather than a
 * code.
 */

import { Fragment, Suspense, useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { CloudOff, DraftingCompass, Info, Plus } from "lucide-react";

import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { DateRangePicker, toIsoDate } from "@/components/forms/DateTimeField";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { PageHeader } from "@/components/PageHeader";
import { RecordCodeCard } from "@/components/RecordCode";
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
  localDraftNeedsAWorkshop,
  refreshDrafts,
  subscribeDrafts,
  type DwDraft,
  type DwDraftHeader
} from "@/lib/designWorkshopStore";
import { AdoptLocalDraftDialog } from "@/components/designworkshop/AdoptLocalDraftDialog";
import { DesignWorkshopViewersPanel } from "@/components/settings/DesignWorkshopViewersPanel";
import { isTransient, isUnreachable } from "@/lib/offline";
import { formatDate } from "@/lib/format";
import {
  DESIGN_WORKSHOP_CREATE_REFUSAL,
  canCreateDesignWorkshops,
  canRunDesignWorkshops,
  isAdmin
} from "@/lib/permissions";
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
  // Next 16: `useSearchParams` — the `?new=1` the dashboard tile has been sending here since it was
  // written — must sit inside a Suspense boundary. Same wrapper /crafts and /workshops use.
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
  /**
   * `can_create_design_workshops` — ADMIN and MASTER_ADMIN, and NOT `canRunDesignWorkshops` as this
   * line used to read.
   *
   * A DESIGNER READS THIS PAGE AND MAY NOT START A WORKSHOP FROM IT. That is the rule: designers
   * create records under existing workshops, and bringing a new workshop into existence is an
   * administrative act belonging to whoever holds the sanction order. Everything else on this page
   * — opening a workshop, its 22 stages, its records, its report — is unchanged for them, which is
   * why `allowWork` below exists and why the route guard is still the wider predicate.
   *
   * (The line before this one was `canCreateRecords`, replaced for a different reason worth keeping:
   * it was dead in practice, and a wrong-but-shadowed predicate is how a rule comes back — it
   * survives a correction made elsewhere, reads as authoritative to whoever finds it first, and
   * nothing fails when it drifts.)
   */
  const allowCreate = canCreateDesignWorkshops(user);
  /**
   * May this account do the WORK of a design workshop — everything except starting one.
   *
   * Read only to decide what to SAY to somebody who cannot create. A designer needs the sentence
   * that names the next move; a role that cannot be here at all never reaches this render, because
   * `ROUTE_GUARDS` refuses the whole path to anyone outside `canRunDesignWorkshops`.
   */
  const allowWork = canRunDesignWorkshops(user);
  const allowDelete = isAdmin(user);
  /**
   * "You tried to start a workshop and this account cannot" — raised by the `?new=1` intent, which
   * is the dashboard tile's "New workshop" button.
   *
   * A STATE RATHER THAN A DERIVED VALUE, because it records something that HAPPENED. The intent is
   * spent from the URL the moment it is read (see the effect below), so a value derived from
   * `searchParams` would vanish on the same tick and the designer would land on an ordinary list
   * having tapped a button that did nothing at all — the single most confusing possible answer.
   */
  const [createRefused, setCreateRefused] = useState(false);

  /**
   * Is the "Designers on a workshop" panel unfolded, and a token that makes it re-read when it is.
   *
   * Two pieces of state rather than one, because they answer different questions: the panel is
   * mounted only while open (so a closed panel costs no requests at all), and the token is what tells
   * an already-mounted panel that the world may have moved under it. Folding and unfolding without
   * the token would remount and refetch anyway; the token is what keeps that true if the panel is
   * ever changed to stay mounted.
   */
  const [viewersOpen, setViewersOpen] = useState(false);
  const [viewersRefresh, setViewersRefresh] = useState(0);

  /**
   * The workshop whose shareable code is on screen, or null.
   *
   * THE TITLE IS CARRIED ALONGSIDE THE ID rather than looked up again when the card renders. The row
   * that was clicked is the one whose name belongs over the symbol, and the list underneath can be
   * re-sorted, re-filtered or re-fetched while the card is open — a card that re-derived its heading
   * from the current page would relabel itself, in place, as some other workshop.
   */
  const [codeFor, setCodeFor] = useState<{ id: string; title: string } | null>(null);

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

  /**
   * `?new=1` — the dashboard tile's "New workshop" button, finally doing what it says.
   *
   * The tile has emitted `/design-workshops?new=1` since it was written and this page never read the
   * parameter: `formOpen` started false and only the header button ever flipped it, so the one
   * control on the dashboard that promises to START a workshop landed on the list with the form shut
   * and left the designer to find the button themselves. Android's new Design workshop card opens
   * its create dialog directly (`Screen.DesignWorkshops(startCreating = true)`), so without this the
   * two clients would disagree about what one button does — which is the thing parity exists to stop.
   *
   * A ONE-SHOT INTENT, not a render mode. `/processes` derives `?new=1` on every render because its
   * form REPLACES the list; here the form sits on the list and its header button toggles it shut, so
   * a parameter re-read each render would put the form back the instant the designer closed it. It
   * is spent with `router.replace` exactly as `useEditDeepLink` spends `?edit=`, which is also what
   * keeps Back out of a create the designer already abandoned.
   *
   * Stripped even when the account may not create, for the same reason that hook drops an intent
   * whose `allowed` is false: a parameter aimed at a form this viewer cannot see must not survive a
   * refresh or a forwarded link. `scroll: false` because there is nothing to scroll to — the form
   * renders directly under the header the reader is already looking at.
   *
   * AND AN ACCOUNT THAT MAY NOT CREATE IS NOW ANSWERED RATHER THAN IGNORED, which is the half this
   * effect was missing. The dashboard tile still says "New workshop" to every designer — it mirrors
   * `canRunDesignWorkshops`, and that is the right predicate for a tile whose other destinations
   * they can all use — so a designer's tap lands HERE. Swallowing it would mean the one control on
   * the dashboard that promises to start a workshop silently produces an ordinary list, which reads
   * as a broken button rather than as a rule. `setCreateRefused` puts the sentence on screen
   * instead: who can create one, and what to do instead.
   */
  const wantsNew = searchParams.get("new") === "1";
  // Depended on as a STRING: the URLSearchParams object is a fresh identity on every navigation, and
  // the effect rebuilds the query from it to keep any parameter it does not consume.
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

  /**
   * A shown code may not outlive the row it belongs to.
   *
   * The card is drawn in an expanded row beneath its own row, so paging away or filtering the
   * workshop out takes it off screen with the row — but the STATE would survive, and coming back to
   * that page would silently re-open a card nobody asked for a second time. Cleared against `rows`
   * rather than against page/query/status separately, because `rows` is what all three of those
   * produce and it also covers the case none of them do: an admin elsewhere deleting the workshop
   * out from under a refresh.
   *
   * Deliberately NOT cleared on every refetch. A background reload that still contains the row must
   * leave the card up — somebody is holding the screen out for a colleague to scan.
   */
  useEffect(() => {
    if (codeFor && !rows.some((row) => row.id === codeFor.id)) setCodeFor(null);
  }, [rows, codeFor]);

  /** Which rows carry something this device has not sent — read once, drawn on the row. */
  const unsentIds = useMemo(
    () => new Set(drafts.filter(draftIsUnsent).map((draft) => draft.remoteId ?? draft.localId)),
    [drafts]
  );

  /**
   * The workshops that exist ONLY on this device, by the id their row is drawn under.
   *
   * WHO THESE ARE, AND WHY THE ROW NEEDS A CONTROL. They are the drafts that were already on the
   * laptop when starting a workshop became an admin's job: a designer's Monday in a courtyard, 22
   * stages, photographs and recordings, with no server record and now no way to make one. The sync
   * pass no longer tries to create them (it would be refused) and instead marks them with a refusal
   * naming the next move; "Move into a workshop" is that next move, and it has to be ON THE ROW
   * because the row is where the designer is looking for their work.
   *
   * `draftSummary` draws an unsynced draft under its `localId`, so that is the key to match on.
   */
  const orphanDrafts = useMemo(() => {
    const byRowId = new Map<string, DwDraft>();
    for (const draft of drafts) if (localDraftNeedsAWorkshop(draft)) byRowId.set(draft.localId, draft);
    return byRowId;
  }, [drafts]);

  /**
   * The draft currently being moved, or null.
   *
   * OFFERED ONLY TO AN ACCOUNT THAT CANNOT CREATE, which is a deliberate narrowing rather than an
   * oversight. An admin holding a device-only draft does not need this: their next sync creates the
   * workshop and the draft resolves itself. Showing them a control that quietly re-files a fortnight
   * of fieldwork into a DIFFERENT workshop, for no benefit, is a way to lose work by mis-tap.
   */
  const [moving, setMoving] = useState<DwDraft | null>(null);
  const offerMove = !allowCreate && allowWork;

  return (
    <>
      <PageHeader
        title="Design workshops"
        description="The 22-stage design and prototype workshop record: setup and participants, cluster background, market survey, sketches, prototypes, costing, outcomes and the generated report."
        icon={<DraftingCompass className="h-5 w-5" aria-hidden />}
        actions={
          // Gated to NOTHING rather than to a disabled button: an ungated "New …" invites an
          // account to press a control that lands on a 403, and a GREYED one is worse still — it
          // says "no" and names neither who can nor what to do instead. A designer who arrives here
          // wanting to start a workshop is told, in words, by the panel below.
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

      {/*
        WHO CAN START A WORKSHOP, AND WHAT TO DO INSTEAD — shown to somebody who can do all the work
        of a workshop and cannot open one, which since the create rule changed is every designer.

        TWO CONDITIONS, AND BOTH MATTER. `createRefused` is set by the `?new=1` intent, so a designer
        who actually TAPPED "New workshop" on the dashboard gets an answer to the thing they just
        did. `allowWork` alone would put a standing notice on the page for everybody, every visit,
        for a control that is no longer there — a permanent apology, which is how a product teaches
        people to stop reading its panels. So the notice appears when it is asked for.

        NOT A TOAST AND NOT AN ALERT DIALOG: it is the answer to a navigation, so it belongs on the
        page the navigation landed on, where it can be read twice and where the list of workshops the
        designer CAN open is directly beneath it. Dismissible, because it is spent once read.

        `role="status"` rather than `role="alert"` — nothing has gone wrong and nothing was lost.
      */}
      {createRefused && allowWork ? (
        <div
          role="status"
          className="mb-4 rounded-md border border-amber-500/30 bg-amber-50 px-3 py-3 text-sm text-amber-900"
        >
          <div className="flex items-start gap-3">
            <Info className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
            <div className="grow">
              <p className="font-medium">Starting a new design workshop is an admin’s job</p>
              <p className="mt-1 leading-6">{DESIGN_WORKSHOP_CREATE_REFUSAL}</p>
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
                // Templates are fetched rows, not a vocabulary in this file: there is one today and
                // there will be a set per cluster. A picker that grows a filter box on its own at
                // eight templates is a picker whose behaviour is decided by somebody else's upload.
                searchable
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

      {/*
        WHO ELSE IS ON A WORKSHOP — the designer multi-select, mounted here rather than built here.

        ── WHICH OF THE THREE ROSTERS THIS IS, BECAUSE THEY ARE CONSTANTLY CONFUSED ─────────────────

        There are three lists a designer's name can go on and only ONE of them lets them work on a
        workshop. This is that one.

          1. `DesignWorkshopViewer` — THIS control. `load_workshop_or_404` admits the creator, an
             admin, and anybody holding one of these rows ("THREE WAYS IN, not two", in its own
             docstring), and the same helper guards the stage WRITES. Adding somebody here is the
             single act that lets them open the workshop and fill in its stages.
          2. The DESIGNER ROSTER at /admin/designers. That decides who may sign in as a designer AT
             ALL — it is global, it is about employment and institutional standing, and a name on it
             grants access to no workshop whatsoever. A suspended row there also REMOVES somebody
             from this control's eligible set, which is the only place the two touch.
          3. The stage-3 participant roster. That is `many("participant", "DwParticipant",
             "Participating artisans", …)` in `backend/app/services/stage_definitions.py` — ARTISANS,
             recorded as research data about who attended. Putting a designer's name there records a
             false fact about the fieldwork and confers no access at all. It is the wrong answer that
             looks most like the right one, because it is the list that is literally called
             "participants".

        ── ADMIN-GATED BECAUSE THE SERVER IS ───────────────────────────────────────────────────────

        All three viewer routes in `backend/app/api/routes/design_workshop_viewers.py` are
        `Depends(require_admin)` — the two GETs as well as the PUT. So a designer cannot read this
        list, let alone change it, and rendering the panel to one would be a form whose every request
        401s. `isAdmin` here is not a UI preference; it mirrors the gate. It is deliberately NOT
        `allowCreate`, even though the two sets are identical today: that predicate answers "may this
        account start a workshop" and this one answers "may this account administer access to one",
        and the day either moves the other must not move with it silently.

        ── AND `adminMode`, WHICH IS THE OTHER HALF AND WAS MISSING ────────────────────────────────

        `AdminViewProvider` states the contract on the field itself — "True only when the user has
        admin rights AND has admin view turned on. Gate admin UI on this" — and its default leaves
        every admin but the master admin OUT of admin view. Without it a plain ADMIN arriving here
        with default settings was handed a full workshop-administration panel while the Delete
        control further down this same file, gated `allowDelete && adminMode`, was correctly hidden
        from them: two admin affordances on one page disagreeing about what admin view means.

        The panel's own docblock reasons that it is safe to render unconditionally because
        /workshop-access/manage is in ADMIN_CHROME_ROUTES — a route that IS admin chrome, where the
        toggle is not applied because the whole page is the exemption. /design-workshops is not in
        that list and must not be: designers live on this page. So the exemption does not carry over
        and the toggle governs here, exactly as it governs Delete below.

        ── MOUNTED, NOT REBUILT ────────────────────────────────────────────────────────────────────

        `DesignWorkshopViewersPanel` already exists on /workshop-access/manage and already solves the
        four things that make this hard: the PUT replaces the whole set so it always sends complete
        membership, the creator is shown outside the picker because the PUT cannot revoke them, a
        current viewer who is no longer eligible stays ticked so that saving does not silently drop
        them, and the people search is the SERVER'S because the eligible list is capped at 2000 and
        that cap is being hit. A second picker over the same endpoint would have to get all four
        right again, and the fourth is invisible when it is wrong.

        It is COLLAPSED by default. This is the workshop list, and an admin arrives here to find a
        workshop far more often than to change who is on one; an always-open panel carrying two
        dropdowns and a search box would push the list itself below the fold.
      */}
      {isAdmin(user) && adminMode ? (
        <section className="panel mb-5 overflow-hidden">
          <button
            type="button"
            className="flex w-full items-center justify-between gap-3 px-4 py-3 text-left"
            aria-expanded={viewersOpen}
            onClick={() => {
              // Bumped on OPEN so the panel re-reads the workshop list and each viewer set every
              // time it is unfolded. An admin who has just created a workshop above expects to find
              // it in the picker, and a panel that cached its list on first mount would not have it.
              //
              // COMPUTED HERE AND NOT INSIDE THE UPDATER. React requires a state updater to be pure
              // and StrictMode double-invokes it in development, so a `setViewersRefresh` call from
              // inside `setViewersOpen`'s callback ran twice per open. Harmless for a token nobody
              // reads the value of — but it is the shape that bites the day the work in there stops
              // being idempotent, so it does not get to live here.
              const next = !viewersOpen;
              setViewersOpen(next);
              if (next) setViewersRefresh((token) => token + 1);
            }}
          >
            <span>
              <span className="font-display text-lg font-bold text-ink-900">Designers on a workshop</span>
              <span className="mt-1 block text-sm leading-6 text-ink-muted">
                Add designers to a design &amp; prototype workshop so they can open it and fill in its stages. A designer
                who is not on this list is told the workshop does not exist.
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
                  : allowWork
                    ? // An empty list is the worst moment to be vague at a designer: there is nothing
                      // on screen to explain itself, so this is the whole answer — why there is no
                      // "New workshop" button, who to ask, and what happens once they have asked.
                      "Design workshops you have access to will appear here. Starting a new one is done by an admin or the master admin — ask them to create it for your cluster and give you access, and it will show up here ready for all 22 stages."
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
                  <Fragment key={workshop.id}>
                  <tr>
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
                      {offerMove && orphanDrafts.has(workshop.id) ? (
                        // A SECOND, DIFFERENT FACT FROM THE CHIP ABOVE, and both are shown because
                        // they are not the same situation. "Saved on this device only" is true of a
                        // workshop that will send itself on the next connection; this one will not
                        // send itself ever, because this account may no longer create a workshop
                        // for it to become. Saying only the first would leave a designer waiting for
                        // a sync that is never coming.
                        <div className="mt-1 text-xs leading-5 text-amber-800">
                          This workshop was started on this device and has no record in the repository yet. Starting one is
                          now done by an admin — ask them to create it, then use “Move into a workshop” to send everything
                          here into it. Nothing has been lost.
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
                        {/*
                          THE CODE OTHERS SCAN TO GET ONTO THIS WORKSHOP. Offered on every row,
                          including a workshop that exists only on this device — the card renders
                          `encodeWorkshopCode`'s refusal for those, which says the workshop has not
                          been shared yet and to sync first. That is the sentence somebody standing
                          in a courtyard needs; hiding the control would leave them believing the
                          feature is missing rather than that their workshop has not gone up yet.
                        */}
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
                  {codeFor?.id === workshop.id ? (
                    /*
                      THE WORKSHOP'S OWN CODE, and the whole of what "join by QR" can honestly be.

                      One person creates the workshop; everybody else needs to end up on THAT one
                      rather than starting their own, which is how a group ends up with rival copies
                      of one fortnight. This is the artefact that prevents it: an opaque
                      `DPW1:G:<id>` naming exactly one `DesignWorkshop`, shown on a screen in a room,
                      scanned or read aloud with no signal on either side.

                      IN AN EXPANDED ROW UNDER THE BUTTON THAT OPENED IT, following
                      `app/(protected)/media/page.tsx` — the other list in this repository that
                      toggles a code from a ROW rather than from an inline edit form. The first
                      version of this rendered the card above the search box, at the top of the page:
                      on row 12 of a 25-row list the only feedback a press gave was the label
                      flipping to "Hide code" while the card itself appeared several screens away.
                      Crafts and workshops put their card at the top legitimately, but their card
                      belongs to the record open in the inline EDIT FORM, which is already up there
                      and already has the reader's eye; there is no such form here.

                      WHAT A SCAN CANNOT DO, said here because the control is what invites the
                      expectation: it cannot admit anybody. A `DesignWorkshopViewer` row is written
                      only by `PUT /design-workshops/{id}/viewers`, which is `Depends(require_admin)`
                      — so a designer cannot put themselves on a workshop with a perfect connection,
                      and there is no offline "request" to queue because there is no route for one to
                      drain into. The scan removes the typing and names the workshop unambiguously;
                      an admin still does the admitting. The scanner's wording for each of those
                      states lives in `DESIGN_WORKSHOP_SCAN_COPY`.

                      A LOCAL-ONLY WORKSHOP GETS A REFUSAL HERE, NOT A CODE, and that is the point
                      rather than a limitation — see the device-local gate in `lib/workshopCodes.ts`.
                      Its id is a key into this browser's IndexedDB and never becomes anything else
                      (`DesignWorkshopCreate` carries no client key), so a code for it would scan
                      cleanly on a colleague's phone and resolve to nothing, and the colleague would
                      then start the second copy this whole mechanism exists to prevent.
                    */
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
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>

      {/*
        Mounted once for the whole table rather than per row: a dialog per row would build one
        focus trap and one portal per workshop on the page, and only ever show one of them.
      */}
      <AdoptLocalDraftDialog
        open={moving !== null}
        onClose={() => setMoving(null)}
        draft={moving}
        drafts={drafts}
        onAdopted={(remoteId) => {
          setMoving(null);
          // Straight into the workshop it now belongs to. The designer's next question is "did my
          // stages arrive", and the workshop is where that is answered — the draft resolves under
          // either id, so this URL works before the sync as well as after it.
          router.push(`/design-workshops/${remoteId}`);
        }}
      />
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
