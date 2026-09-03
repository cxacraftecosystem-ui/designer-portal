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
 * AND SINCE 2026-08-31 A DESIGNER WITH NO SIGNAL MAY START ONE HERE TOO — which is the owner's last
 * unmet clause: *"if they are offline, let them create one for the time being, and when the internet
 * comes back up, let them link it to one of the workshops that they have access to."* It is NOT a
 * create and it never becomes one: the draft carries no server record, the sync pass declines to
 * post it (`createMustBeDeclined`, on the role, at drain time), and its only route off this device
 * is "Move into a workshop". `classifyDraftStart` in `lib/designWorkshopStore.ts` is the whole rule
 * and `startLocalDraftHere` in `lib/designWorkshopCreate.ts` is the arm; both carry the argument.
 * Three things on this page follow from it: the offline start panel (offered only under the offline
 * banner, only to `allowWork && !allowCreate`), the row's own marker, and the prompt that appears
 * when the connection comes back.
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

import { CappedListNotice } from "@/components/data/CappedListNotice";
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
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
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
import { isUnreachable } from "@/lib/offline";
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
// One spelling of "the Kind is set by a professor or an admin", shared with the edit form's link
// picker: two screens explaining the same empty list in two ways is how a reader learns that
// neither explanation is worth reading.
import { LINKED_WORKSHOP_KIND_GAP } from "@/components/designworkshop/linkedWorkshopPicker";

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
  /**
   * The title in the offline start box, and whether it is being written.
   *
   * ORDINARY REACT STATE AND NOT A `FormData` FIELD, unlike the create form's boxes above. This is
   * one control with one button beside it; there is nothing for an uncontrolled form to buy here,
   * and the store's own gate is what refuses an empty start rather than a `required` attribute on
   * a box nobody can see.
   */
  const [localTitle, setLocalTitle] = useState("");
  const [startingLocal, setStartingLocal] = useState(false);
  /**
   * THE CREATE FORM'S OWN TITLE, now a creatable combo rather than a plain box.
   *
   * ── WHY A PICKER ON A FIELD THAT STORES FREE TEXT ────────────────────────────────────────────
   *
   * Android's stage-1 equivalent grew this last wave and the web create form was the one surface
   * where the two clients differed on the control. The reason is `StageWorkshopNameField`'s and is
   * not repeated in full here: a design workshop's title is a FROZEN COPY that nothing re-resolves,
   * so "Bagru Block Print Workshop 2025" and "Bagru block-printing workshop, 2025" are one fortnight
   * to a reader and two strings to every group-by and every report cover. A workshop that runs every
   * year, or in three clusters at once, is named three ways by three admins unless the names already
   * on record are in front of them while they type.
   *
   * NOTHING HERE CAN REFUSE AN ANSWER, which is what makes this legal on a required field: whatever
   * is typed is committable in one keystroke through `createAction`, so a workshop nobody has ever
   * filed is answered exactly as fast as one with ten years of history. R6 does not reach it either
   * — the VALUE is a name, not a grant-bearing reference; picking a row grants nothing and points at
   * nothing, and the identical string is typeable by hand.
   *
   * ── THE TERM, AND WHY IT IS THE SERVER'S ─────────────────────────────────────────────────────
   *
   * §11.5: a client-side filter over a server-truncated list answers "No matches" about records that
   * exist, and on a NAMING control that answer is worse than usual — what a person does next is type
   * the name again slightly differently, which is the exact divergence this control exists to stop.
   * So the panel's box is wired to `GET /design-workshops`'s own `search`.
   */
  const [title, setTitle] = useState("");
  const [titleTerm, setTitleTerm] = useState("");
  const [titleRows, setTitleRows] = useState<DwSummary[]>([]);
  const [titlePending, setTitlePending] = useState(false);
  const [templateId, setTemplateId] = useState("DCH_STANDARD");
  /**
   * THE TYPE OF WORKSHOP, and the empty string is a real answer meaning "not stated yet".
   *
   * It is NOT defaulted to `DESIGN_PROTOTYPE_DEVELOPMENT` even though that is what most workshops
   * are. A pre-selected classification is one nobody chose, and this value is promoted to a column
   * a list filters on and printed on the report cover — so a default would file every workshop
   * opened by an admin who never looked at the box under a kind they never picked, and it would be
   * indistinguishable from a deliberate answer. Stage 1 declares the field `required`, which this
   * registry enforces at SUBMISSION rather than on every save, so an unstated kind is asked for
   * once, at the point it is needed, and never silently invented.
   */
  const [workshopKind, setWorkshopKind] = useState("");
  /** The stage registry, for the workshop-kind vocabulary. Null until it lands; see the floor. */
  const [registry, setRegistry] = useState<DwRegistry | null>(() => peekStageRegistry());
  /** The list's own type filter. Empty means every kind, by absence — the `filters.types` rule. */
  const [kindFilter, setKindFilter] = useState("");
  /**
   * The kinds to draw, and whether they came off the server.
   *
   * Memoised on the registry object rather than on its contents, which is exactly what
   * `fetchStageRegistry`'s identity guarantee is for: it returns the PREVIOUSLY cached object when
   * the version is unchanged, so a refresh that changed nothing does not rebuild this list and does
   * not re-render either dropdown that reads it.
   */
  const kindChoices = useMemo(() => workshopKindOptions(registry), [registry]);
  /**
   * THE DESIGNERS THIS WORKSHOP IS BEING OPENED FOR, in the order they were ticked — empty until an
   * admin picks somebody, which is a real and common answer rather than an unfilled field.
   *
   * A LIST RATHER THAN ONE ID, BECAUSE THIS IS THE ACCESS BOUNDARY. A design workshop is visible
   * only to its creator, to admins, and to whoever holds a `DesignWorkshopViewer` row — the list
   * route AND the single read both enforce it, and a designer cannot create a workshop at all, so
   * for them the row IS the access. A real workshop runs with two designers in the room, so naming
   * one at the create left the second locked out of a workshop whose stage 1 already carried their
   * colleague's name. The create writes one access row per name here, in the same call.
   */
  /*
    SEEDED WITH THE CREATOR WHEN — AND ONLY WHEN — THE CREATOR IS THEMSELVES A DESIGNER.

    The owner's instruction of 2026-08-28: *"The designer initiating the workflow should be the
    default designer. By default, the designer list/selection should contain that designer
    themselves."*

    THE GUARD IS NOT CAUTION, IT IS THE ONE CASE THE INSTRUCTION CANNOT MEAN. `create_design_workshop`
    already argues, at length, that seeding the CREATOR is the wrong behaviour for an admin: "the
    CREATOR's profile is copied, which for an admin opening a workshop on somebody else's behalf is
    the wrong person's name on a ministry document". An admin is almost never a participant in the
    workshop they open — they hold the sanction order — so defaulting them in would put an
    administrator's name on a report cover, seed their `DesignerProfile` into stage 1 and stage 3,
    and give them the `dc:creator` of the .docx. The instruction says "the designer initiating the
    workflow"; an admin initiating it is not a designer, and `role === "DESIGNER"` is the only test
    that tells those two apart. `canRunDesignWorkshops` would NOT: it is a SET that includes ADMIN
    and MASTER_ADMIN, which is exactly the case being excluded.

    A SEED AND NOT A LOCK. It is one tick in a multi-select the creator can untick, and the lead is
    still derived from the first ticked rather than pinned here — so an admin-designer opening a
    workshop for a colleague unticks themselves and nothing about the old behaviour is lost.
  */
  const [designerUserIds, setDesignerUserIds] = useState<string[]>([]);
  /**
   * Whether the seed above has been applied, so it happens once and never fights the creator.
   *
   * A REF AND AN EFFECT RATHER THAN A LAZY `useState` INITIALISER, and the difference is not style:
   * `useAuth()` resolves the account asynchronously, so on the first render `user` is null and an
   * initialiser reading it would seed nothing and never run again. The effect fires when the account
   * lands. It is a ref rather than state because re-rendering on it would change nothing on screen.
   */
  const designerSeeded = useRef(false);
  useEffect(() => {
    if (designerSeeded.current) return;
    if (user?.role !== "DESIGNER" || !user.id) return;
    designerSeeded.current = true;
    // ONLY INTO AN EMPTY SELECTION. If the creator has already ticked somebody — which they can do
    // before the account resolves on a slow connection — the app must not add a name underneath them.
    setDesignerUserIds((current) => (current.length === 0 ? [user.id] : current));
    // DELIBERATELY NOT `markDirty()`. The app filling a box in is not the creator typing in it, and
    // a create form that announces unsaved work before anybody has touched it teaches people to
    // click through the guard that has to still mean something an hour later.
  }, [user?.id, user?.role]);
  /**
   * Which of them is the LEAD — the one whose `DesignerProfile` is seeded into stage 1 and stage 3
   * and whose name the report cover carries. "" means "derive it", which is the first ticked.
   *
   * Several people may open a workshop; exactly one name is on it, because that block is a
   * singleton and `ReportMeta.author` becomes the .docx's `dc:creator`, a single-author field. See
   * {@link WorkshopDesignerPicker} for why the answer is printed on screen rather than left to a
   * tick order nobody can see.
   */
  const [leadDesignerId, setLeadDesignerId] = useState("");
  /**
   * Has anybody typed into, or ticked in, the create form?
   *
   * ARMED BY HAND FROM EVERY THEMED CONTROL. `onInput` on the form below catches the real inputs;
   * the template dropdown, the workshop picker, the date range and the designer multi-select are all
   * `<button>`s that fire no native input event, so each one calls `markDirty` itself. A guard that
   * only sees the text boxes is a guard an admin learns to trust and then loses four ticked
   * designers to.
   */
  const [dirty, setDirty] = useState(false);
  /** The navigation parked behind the unsaved-changes prompt, or null. */
  const [confirmAction, setConfirmAction] = useState<(() => void) | null>(null);
  /*
    The workshops a 22-stage record may be started FROM: only those filed as a Design & Prototype
    Development Workshop. See the picker's own note for why the whole workshop list is the wrong
    thing to offer.
  */
  /**
   * WHAT THE READ ANSWERED, and not merely what it returned.
   *
   * This was `useState<Workshop[]>([])` beside a `useState(0)` total, filled by a `.then` and left
   * alone by a `.catch` — and the option set below then hardcoded `{ kind: "ok", … }` over it, which
   * is the three-state type being asked for its opinion and told what to say. A 500, a timeout or a
   * dead connection therefore rendered *"No design & prototype workshops are open to this
   * account"*: a confident claim about a grant table produced by a request that never arrived, and
   * the identical sentence the edit form printed for the same reason. `WorkshopListState` exists so
   * the three cannot be collapsed; the server's TOTAL rides inside its `ok` arm, where it belongs,
   * because a total is only meaningful about an answer that exists.
   */
  const [sourceList, setSourceList] = useState<WorkshopListState<Workshop>>({ kind: "loading" });
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

  /** Run `action` now, or park it behind the unsaved-changes prompt while the form holds work. */
  function guard(action: () => void) {
    if (dirty) setConfirmAction(() => action);
    else action();
  }

  const markDirty = () => setDirty(true);

  /*
    THE CREATE FORM IS A PANEL ON A LIST PAGE, AND THAT IS EXACTLY WHY IT NEEDED THIS.

    It is not a route of its own, so nothing about leaving it looks like leaving a form: the round
    back control in `PageHeader` and a browser reload both take it away without a word. What is in
    it by then is not a stray keystroke — it is a title, a linked workshop, a fortnight's dates and,
    since the picker became a multi-select, a set of designers an admin assembled by searching the
    repository one name at a time.

    BOTH HALVES, because either alone is half a guard: `useLeaveGuard` covers the app's own back
    control (the page's only one — never add a second), and `beforeunload` covers the reload and the
    closed tab. The listener is attached only while the form actually holds something, so an admin
    reading the list is never asked anything.
  */
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

  /**
   * The titles already on record, for the combo above — fetched only while the form is open.
   *
   * A GENERATION COUNTER RATHER THAN AN ABORT, the same as everywhere else on this page:
   * `listDesignWorkshops` takes no signal and what matters is ignoring the late answer. Two reads
   * are in flight whenever somebody types quickly, and a slow answer for "bag" landing after the
   * fast one for "bagru" would leave the wrong list under the typed word.
   *
   * A FAILURE LEAVES THE LIST ALONE AND SAYS NOTHING. There is nothing at stake: the box below is
   * the answer, `createAction` commits whatever is in it, and a sentence explaining that a
   * convenience did not load is noise on the one screen an admin opens to do a thing they can
   * already do. This is the deliberate exception to rule 10 on this page, and it is allowed because
   * the control cannot refuse an answer — see the state's own note.
   */
  const titleGeneration = useRef(0);
  useEffect(() => {
    if (!formOpen || !allowCreate) return;
    const term = titleTerm.trim();
    const mine = titleGeneration.current + 1;
    titleGeneration.current = mine;
    setTitlePending(true);
    // No debounce on the empty term — that is the read the form opens with, and making somebody wait
    // 300ms to see a list they have not asked to narrow is a delay with nothing to pay for it.
    const timer = window.setTimeout(() => {
      void listDesignWorkshops({
        page: 1,
        // `RENDER_CAP` ROWS AND NOT A ROUND NUMBER: the control draws 80, and asking for 100 prints
        // two truncation sentences with two different totals and says nothing at all between 81
        // and 100.
        pageSize: RENDER_CAP,
        search: term || undefined
      })
        .then((found) => {
          if (titleGeneration.current !== mine) return;
          setTitleRows(found.items);
        })
        .catch(() => {
          // Deliberately silent — see this effect's note.
        })
        .finally(() => {
          if (titleGeneration.current === mine) setTitlePending(false);
        });
    }, term ? 300 : 0);
    return () => window.clearTimeout(timer);
  }, [formOpen, allowCreate, titleTerm]);

  /**
   * The distinct titles among what came back, newest first, with the count of sittings sharing each.
   *
   * DEDUPLICATED BECAUSE ONLY THE NAME IS STORED — two workshops may legitimately share a title, and
   * offering the same string twice is a control appearing to distinguish two answers it cannot. The
   * hint says how many share it, so nobody is told a false singular. ORDER IS THE SERVER'S and is
   * never re-sorted: `GET /design-workshops` answers newest first, which is the workshop somebody
   * naming one today almost always means; alphabetical would bury this season's between two from
   * 2019. Both rules, and the reasons, are `StageWorkshopNameField.distinctTitles`'.
   */
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
      // `hint`, not part of the label: the hint is drawn beside the row and IS searched, while the
      // stored value stays the bare title.
      hint: count > 1 ? `${count} workshops share this name` : undefined
    }));
    // THE TYPED VALUE IS ALWAYS AN OPTION, AND IT IS FIRST. With the box wired to the server the
    // options ARE the answer to the term, so a title that matches nothing would leave the trigger
    // drawing an empty value — and a control that cannot draw its own current value reads as blank.
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
        // EMPTY MEANS EVERY KIND, BY ABSENCE — the rule `filters.types` and `workshopIds` already
        // follow. Sending the six ids when nothing is picked would silently exclude any kind added
        // to the vocabulary after this page loaded, and it would exclude every workshop opened
        // before the column existed, whose kind is NULL.
        workshopKind: kindFilter || undefined
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
  }, [page, applied, status, kindFilter]);

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

  /*
    THE STAGE REGISTRY, FETCHED HERE ONLY FOR THE WORKSHOP-KIND VOCABULARY.

    Cheap, and deliberately not guarded: `fetchStageRegistry` is module-cached, shares one in-flight
    promise across every component that asks in the same commit, and is the ONE call in this client
    that revalidates from the browser's HTTP cache — a cold start holding the current registry costs
    a 304 rather than ~25 KB. A page that already fetches the template list and the workshop list can
    afford it.

    A FAILURE IS SILENT AND CORRECT. `workshopKindOptions` falls back to the compiled-in floor, so
    the dropdown is answerable either way; there is nothing here for a designer to act on, and a
    banner about a registry they have never heard of would be noise on the page they open first.
  */
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
    /*
      `accessibleOnly` — this picker LINKS the workshop it offers (`workshopId: sourceWorkshopId` in
      the payload below), so an option this account cannot file against is an option that produces a
      design workshop pointing at somebody else's roster. The narrowing excludes exactly the curated
      rosters the caller is not on; an admin — which today is the only tier that can reach this form
      at all — is never narrowed, so this changes nothing for the people using it and cannot become
      wrong the day a designer is allowed to start one. See `WorkshopSelect`'s header for which list
      is authoritative and why there is no fallback.
    */
    listResource<Workshop>("/workshops", {
      // `WORKSHOP_OPTION_PAGE_SIZE`, not the round number `100` this used to ask for: it is
      // `RENDER_CAP` under another name, and asking for more than `SearchableSelect` will ever draw
      // is how a picker ends up with two disagreeing cap sentences — its own panel silently trimming
      // at 80 while a sentence beside it still spoke of a hundred. See that constant's own header in
      // `lib/workshopOptions.ts`.
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
        /*
          STILL NO BANNER — this picker is a convenience over boxes the designer can always type
          into, and an error banner for a shortcut that failed would read as the form itself being
          broken. What changed is that the failure is now RECORDED rather than discarded: the
          sentence under the control says the list could not be loaded instead of claiming this
          account has no design-prototype workshops, which is a claim about a grant table that a
          failed request cannot support. Silence about the banner, not about the state.
        */
        if (!cancelled) setSourceList({ kind: "failed" });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  /** The rows of the answer, for the four boxes `applySourceWorkshop` fills from the picked row. */
  const sourceWorkshops = useMemo<readonly Workshop[]>(
    () => (sourceList.kind === "ok" ? sourceList.rows : []),
    [sourceList]
  );

  /**
   * WHICH LIST THIS IS, for the state sentences. `scoped` is true because the request carries
   * `accessibleOnly=true`, so an empty answer means "none is open to this account" — whose next move
   * is an administrator — and never "none has been recorded", whose next move is to create one.
   */
  const sourceVoice: WorkshopListVoice = { table: "field", scoped: true, online: !deviceLooksOffline() };
  /**
   * §3.5's sentence about the read, or "" while it is in flight — LOADING SAYS NOTHING, on purpose.
   *
   * A sentence that appears and vanishes inside a second is noise on a fast connection and, on a
   * slow one, is replaced by a different sentence just as the reader finishes it. The panel covers
   * the wait in the slot where it belongs (`workshopEmptyLabel` → `SEARCHING_LABEL`). Held in a
   * const so the empty case renders no element at all rather than an empty `<p>` with a margin.
   */
  const sourceNotice = workshopListNotice(sourceList, sourceVoice);

  /**
   * ROUTED THROUGH THE SHARED BUILDER, so this row's label and hint match every other workshop
   * picker in the app rather than adding an eighth hand-rolled spelling of "title, then place" — see
   * `lib/workshopOptions.ts`'s header on why that drifted to six different formats before it had one
   * owner. `group: true` because the request narrows by `workshopType` alone; it says nothing about
   * whether a workshop's window has closed, so an ended one still needs its own heading same as
   * everywhere else this table is offered (an ended workshop is a legitimate template for a record
   * filed after the fact, never `disabled` — §2.6 of that file). `offPage: "refuse"` because
   * `sourceWorkshopId` is transient picker state, not a value stored on a record: there is nothing to
   * recover.
   */
  const sourceWorkshopOptions = useMemo(
    // THE STATE IS HANDED IN WHOLE, not rebuilt as a literal `{ kind: "ok" }`. Writing the arm by
    // hand told the builder the read had succeeded whatever had actually happened, which is how the
    // three-way type came to be carried by this call site and still produce a two-way answer.
    () => fieldWorkshopOptions(sourceList, { group: true, offPage: { mode: "refuse" } }),
    [sourceList]
  );

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
    // A themed dropdown is a `<button>` and fires no native input event, so the form's `onInput`
    // never sees it — and this one does not merely record a choice, it FILLS four boxes below.
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
      /*
        WHO MAY OPEN IT AND WHOSE NAME IS ON IT, RESOLVED BY THE SHARED RULE.

        `namedDesignerTeam` is the same function the picker prints its lead line from, so the
        sentence the admin just read and the body about to be sent cannot disagree about which
        designer's profile gets copied into stage 1. It also settles the case the screen can produce
        and the wire must not: a lead who has since been unticked is not silently re-added, the
        first ticked is promoted instead.

        NOTHING TICKED SENDS NOTHING. The team is empty, `designerUserId` goes as `undefined` and
        `designerUserIds` as `[]` — and `createDesignWorkshop` omits both keys, which is the only
        shape an API predating either of them can answer. The offline draft then holds null and an
        empty list, which is the honest state rather than "" for a decision nobody has taken.
      */
      const designers = namedDesignerTeam({ chosen: designerUserIds, lead: leadDesignerId });
      const header = {
        title,
        templateId,
        // Omitted rather than sent as "" when nothing is picked: the server's body declares this
        // `str | None` and validates a present value against the six, so an empty string would be a
        // 422 on a field the admin deliberately left for the designer to answer in stage 1.
        workshopKind: workshopKind || undefined,
        designerUserId: designers.lead || undefined,
        designerUserIds: designers.team,
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
      /*
        THE WORKSHOP IS CREATED ON THE SERVER, OR IT IS KEPT HERE — AND WHICH OF THOSE HAPPENED IS
        RECORDED ON THE DRAFT, because a create whose answer was lost must not be filed twice.

        Offline, the workshop is created HERE, with a local id, and becomes a real record on the
        next connection. The alternative — refusing — makes the very first act of a fortnight in the
        field the one act that needs signal, and a designer standing in a room with the participants
        in front of them would open a paper notebook instead.

        The two arms are deliberately asymmetric — a transient failure stamps the draft because the
        POST had already gone out, and being offline does not because nothing was sent. That
        argument, and the duplicate government record it prevents, live in
        `lib/designWorkshopCreate.ts` and are pinned by
        `e2e/design-workshop-create-idempotence-unit.spec.ts`. Do not re-inline the decision here:
        there is no React renderer in this project's devDependencies, so a decision written in this
        file is a decision no test can reach.
      */
      const created = await createWorkshopOrKeepItHere(header);
      formElement.reset();
      // `reset()` only clears what the DOM owns, and the title now lives in React state — without
      // this the next "New design workshop" opens carrying the name of the one just created, which
      // on a creatable combo reads as the form having remembered a deliberate choice.
      setTitle("");
      setTitleTerm("");
      // `reset()` only clears what the DOM owns, and the range lives in React state — without this
      // the next "New design workshop" opens pre-filled with the dates of the one just created, and
      // a designer starting their second workshop of the week inherits the first one's fortnight.
      setDuration({});
      setSourceWorkshopId("");
      // Cleared for the same reason as the range above: they live in React state, so `reset()` does
      // not reach them, and the next "New design workshop" would otherwise open with the last
      // workshop's designers already ticked — the one field here whose stale value silently puts
      // somebody's profile on a document they had nothing to do with, and hands four accounts
      // access to a workshop nobody meant to give them.
      setDesignerUserIds([]);
      setLeadDesignerId("");
      // The work is saved, so nothing is owed and the prompt must not fire on the navigation this
      // function is about to perform.
      setDirty(false);
      setFormOpen(false);
      await refreshDrafts();
      // A brand-new workshop is 22 empty stages, so the only useful next step is opening it. Going
      // there directly beats dropping the designer back onto a list to hunt for their own row —
      // and `router.push` rather than `location.assign` keeps the app's own transition, its scroll
      // restoration and the token in memory instead of reloading the whole bundle.
      router.push(`/design-workshops/${created.id}`);
    } catch (err) {
      /*
        THE PROMPT COMES DOWN ON A FAILURE, and the form stays dirty.

        A create can be refused for a reason only this banner can carry — an ineligible designer
        somewhere in the list refuses the WHOLE create and the 422 names every account it objected
        to. Leaving the unsaved-changes dialog up would put that sentence behind a modal, and the
        admin would be answering "save or discard" about a save that has just told them why it
        cannot happen.
      */
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
        `"${workshop.title}" is removed from the list, along with every stage recorded against it.`,
        // Stated because it is TRUE and because it is unlike everything else in this app: nothing
        // else here has a soft delete, so a reader who has met the artisan and product dialogs will
        // assume this one is permanent and will hesitate over something an admin can undo.
        //
        // AND IT NAMES THE SCREEN, which it could not until the trash card landed. "An admin can
        // restore it" was true of the API and unreachable in the product — nothing listed deleted
        // workshops, so the only admin who could act on this sentence was one who had written the id
        // down first. A promise a reader cannot follow is worse than no promise.
        "Nothing is erased — this is a soft delete kept for the research record, and an admin can restore it from Settings → Deleted workshops."
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
   *
   * ── AND THEY ARE NOT PREPENDED WHEN IT COULD NOT BE ASKED EITHER, WHICH IS A REVERSAL ───────────
   *
   * This branch used to read `draft.remoteId === null || offline`, so an unreachable repository
   * prepended EVERY cached draft in this browser. `/sketches-and-prototypes` withdrew the same
   * fallback and its header records the reason at length; the reason applies here unchanged, and this
   * page is the LARGER surface of the two — it is the primary workshop list and the route into a
   * workshop, not one chooser inside one screen.
   *
   * THE DEVICE'S LIST IS THE SERVER'S ANSWER AS OF THE LAST SYNC AND IT IS STALE IN THE PERMISSIVE
   * DIRECTION. `draftSummary` keys rows on `remoteId ?? localId` and hardcodes `deletedAt: null`, so
   * a `DesignWorkshopViewer` grant revoked in March is still offered in September, as is a workshop
   * that has since been soft-deleted, and offline there is nobody to ask — the staleness cannot be
   * detected here at all and it has no bound. A list that is also the route in is a control whose job
   * is offering, and the requirement this app is now held to is that a designer is only ever OFFERED
   * a workshop they currently have access to.
   *
   * WHAT IS STILL PREPENDED, AND WHY IT IS A DIFFERENT CLAIM. Two kinds of row, both of them THIS
   * DEVICE'S OWN UNSENT WORK rather than evidence about anybody's access:
   *
   *   * `remoteId === null` — a workshop STARTED here that the server has never seen. It exists
   *     nowhere else; omitting it is how a designer who opened a workshop in a courtyard on Monday
   *     concludes on Tuesday that it is gone and starts a second one. Nothing about access is being
   *     asserted, because there is no server row to have access to.
   *   * `offline && unsent` — a server row this device holds edits for that have not been sent. The
   *     row is on screen because the WORK is at risk of being invisible, which is a fact about this
   *     browser's outbox and not a claim that the grant still stands; the sync is still the authority
   *     and still refuses the queued `PUT` if it does not.
   *
   * A row this browser merely cached and has not touched is neither of those, so offline it is now
   * absent — and the banner above the list says so in words rather than letting a short list read as
   * a small repository.
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
      // Never synced, or holding work this device has not sent. See the note above for why an
      // untouched cached row is NOT the third case here any more.
      if (draft.remoteId === null || (offline && unsent)) extras.push(draftSummary(draft));
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

  /**
   * Offer a designer with no signal somewhere to put a fortnight of fieldwork.
   *
   * THE OWNER'S REMAINING CLAUSE, AND ALL THREE CONDITIONS ARE LOAD-BEARING. `allowWork` because
   * this is for the people who run workshops and nobody else; `!allowCreate` because an admin has
   * the create form and an unlinked draft would be strictly worse for them — theirs sends itself;
   * and `offline` because with a connection there are two better answers than an unlinked draft,
   * and `DESIGN_WORKSHOP_CREATE_REFUSAL` plus `ContinueOnAllocatedWorkshop` above already name
   * both. The store re-asks the whole question at the moment of the write
   * (`classifyDraftStart`), so a device that comes back while this panel is on screen refuses the
   * start rather than minting a draft that never needed to exist.
   *
   * `offline` HERE IS THE PAGE'S OWN EVIDENCE, not `navigator.onLine`: the list read above failed
   * and was triaged by `isUnreachable`. That is the difference between offering this in a
   * courtyard and offering it behind a guest-house captive portal, which reports itself online and
   * routes nothing.
   */
  const offerLocalStart = allowWork && !allowCreate && offline;
  /**
   * "The connection is back, and these are still not workshops."
   *
   * SHOWN ONLY ONLINE, which is the whole point: the draft is marked on its row from the moment it
   * is minted, and this is the PROMPT the owner asked for — the unmistakable one that arrives when
   * the internet comes back up. Offline it would be an instruction nobody can act on, because
   * `AdoptLocalDraftDialog` holds the move until the repository has answered which workshops this
   * account may actually open (R6).
   */
  const unlinkedCount = offerMove ? orphanDrafts.size : 0;
  const promptLink = unlinkedCount > 0 && !offline;

  /**
   * Start a workshop that lives here until it is linked.
   *
   * `startLocalDraftHere` NEVER POSTS — see its own header for why it is not a third branch of
   * `createWorkshopOrKeepItHere`. A refusal (the device turned out to be reachable, or this account
   * may not run workshops at all) arrives as `DwCreateNotPermittedError`, whose message is the
   * shared refusal naming the next move, and it is rendered unchanged in the page banner.
   */
  async function startHere() {
    const title = localTitle.trim();
    if (!title || startingLocal) return;
    setStartingLocal(true);
    setError(null);
    try {
      const started = await startLocalDraftHere({ title });
      setLocalTitle("");
      await refreshDrafts();
      // Straight into it, exactly as a create does: 22 empty stages are the only useful next step,
      // and every design-workshop route resolves a `dwlocal-…` id before and after adoption.
      router.push(`/design-workshops/${started.id}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to start a workshop on this device");
    } finally {
      setStartingLocal(false);
    }
  }

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
            <button
              type="button"
              className="field-button"
              // CLOSING GOES THROUGH THE GUARD, OPENING DOES NOT. This button is the same control
              // for both, and only one of the two directions can throw work away.
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
              {/*
                THE LAST CLAUSE OF THAT REFUSAL IS NOW A CONTROL RATHER THAN A CLAIM.

                It ends "Any workshop you already have access to is open to you now", which was true
                and was not actionable: the designer had to read it, dismiss the panel, and then find
                the right row in a list that may be paginated. The owner asked for the other half
                (2026-08-28): *"When a designer selects Start a new workshop, provide a dropdown
                containing the workshops that the designer is already part of or has been given
                access to. By default, select the Design and Prototype Workshop that the designer was
                most recently given access to."*

                So the sentence keeps its words and gains the dropdown it describes, defaulted by the
                server's own answer to "most recently allocated" — see
                `lib/designWorkshopDefault.ts` for why that is not computed here.

                IT OPENS A WORKSHOP; IT DOES NOT CREATE ONE. Nothing about this widens
                `canCreateDesignWorkshops`, and the panel above still says whose job that is.
              */}
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
        // Says what is on screen and what is not. A list that silently shows only the local subset
        // is indistinguishable from a repository with three workshops in it.
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          There is no connection, so this shows only the design workshops started on this device and the ones holding changes
          that have not been sent yet. Everything else in the repository — including workshops you have opened here before — is
          not listed, and searching cannot reach it. What is here is fully editable, and the list fills in again the moment the
          repository can be reached.
        </div>
      ) : null}

      {offerLocalStart ? (
        /*
          THE ONE CONTROL A DESIGNER IN A COURTYARD NEEDS, and it is a panel rather than a button in
          the header for a reason: the header's create control is gated to nothing for this account
          on purpose (an ungated "New …" invites a press that lands on a 403), and putting a
          different button in the same place would read as that rule quietly reversing. This one
          appears only with the offline banner directly above it, which is the context that makes it
          honest.

          TWO SENTENCES AND NO MORE. What it is, and what happens next. The reasoning is on
          `classifyDraftStart`.
        */
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
              // Disabled on an empty title only. Everything else this control could refuse is
              // refused by the store, which is the one place that knows the rule.
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
        /*
          THE PROMPT THE OWNER ASKED FOR: *"when the internet comes back up, let them link it to one
          of the workshops that they have access to."*

          `role="status"`, not `alert`: nothing has gone wrong and nothing is at risk. It is not
          dismissible, unlike the create refusal above, and that asymmetry is deliberate — the
          refusal is spent once read, while this describes work that is still sitting on the device
          and stays true until the designer acts on it. Nothing automatic will ever delete it, and
          nothing will send it either.

          IT DOES NOT OPEN THE DIALOG ITSELF. With more than one unlinked workshop the page cannot
          know which one the designer means, and choosing for them is how a fortnight is filed under
          the wrong cluster — so it names the count and sends them to the row, where the title is.
        */
        <div
          role="status"
          className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800"
        >
          {unlinkedCount === 1
            ? "One workshop here was started on this device and is not linked to a workshop yet. "
            : `${unlinkedCount} workshops here were started on this device and are not linked to a workshop yet. `}
          {DW_LOCAL_DRAFT_LINK_PROMPT} Use “Move into a workshop” on the row.
        </div>
      ) : null}

      {allowCreate && formOpen ? (
        // `onInput` catches every real text box in one place. It CANNOT catch the themed controls —
        // a dropdown, a date picker and a multi-select are all `<button>`s and fire no input event —
        // so each of those calls `markDirty` itself; see the `dirty` state above.
        <form ref={formRef} onSubmit={submit} onInput={markDirty} className="panel mb-5 grid gap-4 p-4">
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
              options={sourceWorkshopOptions.options}
              // The create flow's own "none" row: the fields below are the alternative to a link,
              // and {@link TYPE_DETAILS_INSTEAD} is the one shared sentence for that — see
              // `lib/workshopOptions.ts` for why there are four such strings and why a hand-built
              // `{ value: "", label: … }` row in `options` is never a second one of them (a
              // duplicate React key on `""`, and a control that can no longer say which layer drew
              // the row it is showing).
              noneLabel={TYPE_DETAILS_INSTEAD}
              ariaLabel="Start from a recorded workshop"
              searchable
              /*
                Never the literal "No options", and never a claim the state does not support:
                `SEARCHING_LABEL` while the read is in flight, the failure sentence after a failure,
                and the scoped "none are open to this account" only once the read has answered with
                none. The panel used to say "No options" through all three.
              */
              emptyLabel={workshopEmptyLabel(sourceList, sourceVoice)}
            />
            {/*
              ONE SENTENCE, CHOSEN BY THE STATE. `sourceWorkshops.length` could not tell a failed read
              from an empty answer, so a timeout printed the "none are open to this account" claim —
              the same defect, in the same words, the edit form's picker carried. The four state
              sentences are `lib/workshopOptions.ts`'s; the scope sentence below is this screen's, and
              describes what the REQUEST asked for rather than what the read answered.
            */}
            {sourceList.kind === "ok" && sourceList.rows.length > 0 ? (
              <p className="text-xs leading-5 text-ink-500">
                Only workshops filed as a Design &amp; Prototype Development Workshop, and only ones you have access to,
                appear here.
              </p>
            ) : sourceNotice ? (
              <p className="text-xs leading-5 text-ink-500">{sourceNotice}</p>
            ) : null}
            {/*
              AND WHO CAN CLOSE THE GAP. The sentence this replaced sent the reader off to set a
              workshop's Kind themselves — an act `can_manage_workshops` gates at PROFESSOR (rank
              40), so on the account this product is built for, a DESIGNER at 35, it was advice the
              API refuses and a page that hides the form. Shared with the edit form's picker so the
              two screens cannot come to disagree about why the same list is empty.
            */}
            {sourceList.kind === "ok" && sourceList.rows.length === 0 ? (
              <p className="text-xs leading-5 text-ink-500">{LINKED_WORKSHOP_KIND_GAP}</p>
            ) : null}
            {/*
              WHAT THAT SENTENCE DOES NOT COVER: a scope can be correct and the list still CUT. The
              box above is client-side — it searches only the `WORKSHOP_OPTION_PAGE_SIZE` rows
              already fetched, never the server — so `searchable: false` here on purpose; see
              `workshopCutSentence`'s own header on why that argument means "does the box reach past
              the cut", not "is there a box on screen".
            */}
            <CappedListNotice cuts={[workshopCutSentence(sourceWorkshopOptions, { searchable: false })]} />
          </FieldBlock>

          <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
            {/* FieldBlock, not Field: `Field` is a <label>, and a <label> wrapped around a themed
                dropdown forwards a stray click into the menu and slams it shut after one pick. */}
            <FieldBlock label="Workshop title" required>
              <Dropdown
                value={title}
                onChange={(next) => {
                  // Themed control: no native input event, so the form's `onInput` never sees it.
                  markDirty();
                  setTitle(next);
                }}
                options={titleOptions}
                placeholder="Type the name, or pick one already on record"
                ariaLabel="Workshop title"
                // THE BOX IS THE SERVER'S — see the state's note. `truncated` is deliberately absent:
                // a cut list here costs nothing, because whatever is typed is committable.
                serverQuery={{ value: titleTerm, onChange: setTitleTerm, pending: titlePending }}
                /*
                  THE HALF THAT MAKES THIS LEGAL ON A REQUIRED FREE-TEXT FIELD. Whatever is in the box
                  is committable in one keystroke, so a workshop that exists nowhere yet is answered
                  as fast as one with a history — which is what Android's stage-1 control has done
                  since it landed, and the difference this closes.
                */
                createAction={{ label: (term) => `Use “${term}” as the name`, onCreate: setTitle }}
              />
              {/*
                THE MIRROR, AND IT IS `type="text"` RATHER THAN `type="hidden"`.

                Hidden inputs are exempt from constraint validation, so a `required` themed dropdown
                would never block submission and an empty title would reach `submit`'s silent
                `if (!title) return` — a button that does nothing, which is the worst answer a form
                can give. A zero-size text input submits the value AND participates in native
                validation. `tabIndex={-1}` keeps `lib/formNav.FOCUSABLE`'s Enter-walker off it.
              */}
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
            {/* FieldBlock, not Field: `Field` is a <label>, and a <label> wrapped around a themed
                dropdown forwards a stray click into the menu and slams it shut after one pick. */}
            <FieldBlock label="Report template">
              <Dropdown
                value={templateId}
                onChange={(next) => {
                  // Themed control: no native input event, so the form's `onInput` never sees it.
                  markDirty();
                  setTemplateId(next);
                }}
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
            {/*
              TYPE OF WORKSHOP — the control the owner asked for, and it is placed IMMEDIATELY after
              the report template on purpose rather than beside the title.

              The two are the reason this requirement read as half-built for so long. Both create
              forms have always drawn a six-value dropdown right under the title box, and it is
              REPORT TEMPLATE — the output document format, not the workshop's kind — so the screen
              looked like it carried a type/name pair and carried neither half. Putting the real type
              next to it, with its own label and its own help sentence, is what makes the two
              legible as different questions; separating them across the grid would leave the
              template still sitting where a reader expects the type.

              `searchable` is deliberately NOT passed. Six members of a vocabulary compiled into this
              app is exactly the class the shared threshold answers correctly on its own, and a
              filter box over six options is a control that costs a keystroke to reach four rows.
              The template above it passes `searchable` because its options are fetched ROWS. Same
              screen, two dropdowns, opposite answers, and the asymmetry is the rule working.
            */}
            <FieldBlock label="Type of workshop">
              <Dropdown
                value={workshopKind}
                onChange={(next) => {
                  // Themed control: no native input event, so the form's `onInput` never sees it.
                  markDirty();
                  setWorkshopKind(next);
                }}
                options={kindChoices.options.map((option) => ({ value: option.value, label: option.label }))}
                ariaLabel="Type of workshop"
                placeholder="Not stated"
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
              <DateRangePicker
                from={duration.from}
                to={duration.to}
                onChange={(next) => {
                  // Themed control: no native input event, so the form's `onInput` never sees it.
                  markDirty();
                  setDuration(next);
                }}
              />
            </div>
          </div>
          {/*
            FULL WIDTH AND OUTSIDE THE FOUR-COLUMN GRID, because it is three stacked controls (a
            server-backed search box, a one-line notice, the picker) and not a box. It sits last
            among the header questions on purpose: the title is what an admin came here to type, and
            who the workshop is FOR is the decision they most often have to leave open — the field
            is optional precisely because a workshop is opened in a room on day one.

            React state rather than a FormData name, like `templateId` above it and for the same
            reason: a themed dropdown is a `<button>` and submits nothing of its own.
          */}
          <WorkshopDesignerPicker
            values={designerUserIds}
            onChange={(next) => {
              // The multi-select is a `<button>` too, and it is the control on this form whose loss
              // costs the most: a set assembled by searching the repository one name at a time.
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
          <div className="flex gap-2">
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

      {/* THREE COLUMNS SINCE THE TYPE FILTER LANDED. The search box keeps the flexible one; the two
          dropdowns take a fixed 14rem each so they do not resize as their labels change. */}
      <div className="mb-4 grid gap-2 sm:grid-cols-[1fr_14rem] lg:grid-cols-[1fr_14rem_14rem]">
        <SearchInput
          value={query}
          onChange={setQuery}
          onSubmit={() => {
            setApplied(query);
            setPage(1);
          }}
          // The placeholder lists the columns searched; this names what is being searched.
          ariaLabel="Search design workshops"
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
        {/*
          FILTER BY TYPE — the read half of the pair. A classification nothing can narrow by is a
          column, not a feature, and until this control existed the ONLY type narrowing anywhere in
          the product was a hard-coded `workshopType="DESIGN_PROTOTYPE"` literal on two request
          builders against the legacy `Workshop` table.

          "Any type" is the empty value and it is FIRST, exactly as "Any status" is beside it — the
          same absence-means-everything rule the request builder above relies on.
        */}
        <Dropdown
          value={kindFilter}
          onChange={(next) => {
            setKindFilter(next);
            setPage(1);
          }}
          options={[
            { value: "", label: "Any type" },
            ...kindChoices.options.map((option) => ({ value: option.value, label: option.label }))
          ]}
          ariaLabel="Filter by type of workshop"
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
                      "Design workshops you have access to will appear here. Starting a new one is done by an admin or the master admin — ask them to create it for your cluster and name you as one of its designers, and it will show up here ready for all 22 stages."
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
                          {/*
                            TERSE, AND IT NO LONGER TELLS EVERY DESIGNER TO GO AND ASK AN ADMIN.

                            The paragraph this replaces was written when the only drafts that could
                            reach this row were the ones stranded by the create rule, whose owner
                            genuinely had no workshop to move into. A designer who starts one here
                            deliberately, offline, usually has several — the picker is the next move,
                            not a conversation — and the admin sentence is still on screen for the
                            other case, in `AdoptLocalDraftDialog`'s own empty state, which is the
                            one surface that KNOWS the account has nothing to move into.

                            Two facts and nothing else: what this row is, and what to do with it.
                            The owner's 2026-08-30 ruling on UI copy; the reasoning is here.
                          */}
                          {DW_LOCAL_DRAFT_UNLINKED} {DW_LOCAL_DRAFT_LINK_PROMPT}
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

      {/*
        THE UNSAVED-CHANGES PROMPT for the create form above.

        "Save" REQUESTS THE FORM'S OWN SUBMIT AND PARKS NOTHING, which is the one way this mount
        differs from the record editors. A successful create navigates INTO the new workshop —
        22 empty stages are the only useful next step — so a parked "and then go back" would
        immediately undo the thing the admin just asked for. The save is the leaving.

        A refused create takes the dialog down from `submit`'s catch, because the refusal is a
        sentence in the banner underneath: an ineligible designer anywhere in the list refuses the
        WHOLE create and the 422 names every account it objected to, and that cannot be read from
        behind a modal.
      */}
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

/**
 * "Carry on with a workshop you were allocated" — the control the create refusal now ends in.
 *
 * ── WHY IT IS ITS OWN COMPONENT AND NOT INLINE IN THE PANEL ─────────────────────────────────────
 *
 * It fetches. Mounted only when the refusal is on screen, it costs nothing on every other visit —
 * and the refusal is raised by the `?new=1` intent, which is spent the moment it is read, so this
 * is the one render where a designer has actually asked the question this answers. Inline, the two
 * requests would be issued by the page for every visitor, most of whom are admins who never see the
 * panel at all.
 *
 * ── IT LISTS AND IT DEFAULTS, AND THOSE ARE TWO DIFFERENT QUESTIONS ─────────────────────────────
 *
 * The LIST is `GET /design-workshops`, whose rows for a non-admin are exactly `visible_to_clause` —
 * created-by-me OR holding a viewer grant. That is precisely "the workshops the designer is already
 * part of or has been given access to", asked in list form; there is no second endpoint to write.
 *
 * The DEFAULT is `GET /design-workshops/default-for-me`, because "most recently given access to" is
 * `DesignWorkshopViewer.createdAt` and that column is on no payload any client can see. Ordering
 * this list by `createdAt` — which is what a client would have to fall back on — answers "most
 * recently CREATED", which for a workshop the Ministry opened in March and allocated in August is
 * the wrong row. See `lib/designWorkshopDefault.ts`.
 *
 * ── A FAILURE SAYS NOTHING AT ALL ──────────────────────────────────────────────────────────────
 *
 * The panel it sits in is already an answer to a refused action, and stacking a second failure
 * inside it would bury the sentence that matters. If either request fails the control simply does
 * not appear and the list below the panel — which is this page's whole job — still works.
 */
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
      // THE DEFAULT IS ONLY APPLIED IF IT IS IN THE LIST. A workshop the default names and the page
      // does not carry would select a value with no option behind it, which every dropdown in this
      // app draws as nothing selected — a control that silently disagrees with itself.
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

  /**
   * ROUTED THROUGH THE SHARED BUILDER rather than a hand-rolled `.map()`, and fetched at
   * `WORKSHOP_OPTION_PAGE_SIZE` rather than the round number `50` this used to ask for.
   *
   * The two are one fix, not two. `50` happened to sit under `RENDER_CAP` (80), so the picker itself
   * never had to trim what this component handed it — but nothing here ever asked the server for a
   * TOTAL, so a designer allocated a 51st workshop was missing from this shortcut with nothing on
   * screen to say so, indistinguishable from a designer who only has fifty. `designWorkshopOptions`
   * reads `total` off the page and reports what it left out, and asking for exactly
   * `WORKSHOP_OPTION_PAGE_SIZE` is what keeps that count from ever landing beside a second, silent
   * cap inside `SearchableSelect`'s own panel (see that constant's header). `group: true` because
   * nothing here narrowed the request by status, so a submitted or archived workshop needs its own
   * heading same as everywhere else this table is offered. `offPage: "refuse"` because `chosen` is
   * picker state, not a value stored on a record — there is nothing to recover.
   *
   * Built BEFORE the "nothing to offer" return below, and deliberately: a hook cannot follow a
   * conditional return, and this one has to run on every render regardless of what `rows` holds.
   */
  const optionSet = useMemo(
    () =>
      designWorkshopOptions(
        { kind: "ok", rows: rows ?? [], total },
        { group: true, offPage: { mode: "refuse" } }
      ),
    [rows, total]
  );

  // Nothing to offer: either the list could not be read, or this designer genuinely has none. The
  // panel's own sentence already covers the second case ("Ask an admin to create it for your
  // cluster and give you access"), so adding an empty dropdown under it would say less than silence.
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
          // The options are RECORDS, so §11.5 says the filter box belongs here — it searches this
          // page's rows and nothing past `WORKSHOP_OPTION_PAGE_SIZE`, which is exactly what the cut
          // sentence below is for.
          searchable
          // It navigates, so the panel it lives in must not steal focus back to the next field.
          advanceOnSelect={false}
        />
      </FieldBlock>
      {/*
        The box above is client-side and reaches only the rows already fetched, not the server's
        `search=` — so `searchable: false` here, which is a question about whether the CUT is
        reachable, not about whether the trigger has a filter box (see `workshopCutSentence`'s own
        header on the distinction).
      */}
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

/** Does this draft hold anything the server has not confirmed? */
function draftIsUnsent(draft: DwDraft): boolean {
  if (draft.remoteId === null || draft.headerDirtyAt !== null) return true;
  return Object.values(draft.stages).some((stage) => stage.dirtyAt !== null || stage.removedFrom.length > 0);
}

