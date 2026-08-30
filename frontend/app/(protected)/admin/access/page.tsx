"use client";

/**
 * WHO MAY SIGN IN — the platform allow-list, and the queue of people waiting to be let in.
 *
 * THIS IS NOT THE DESIGNER ROSTER (/admin/designers) AND IT IS NOT `User.role`. The designer roster
 * says who the institution recognises as a designer; the role says what somebody may do once they
 * are inside. This list answers the question that comes before both: may this address reach the
 * product at all. The three are kept apart on the server (three tables, three predicates) and they
 * are kept apart here, because the remedies differ — an admin who suspends the wrong one of them
 * takes away something they did not mean to take away, and the person is told the wrong reason.
 *
 * ── THE PENDING SECTION IS THE POINT OF THE SCREEN ───────────────────────────────────────────────
 *
 * A row appears in it when somebody PROVED an identity — a correct password, or a Google token that
 * verified — and was turned away because no ACTIVE row carried their address. That is the
 * notification the requirement asked for, in the only channel either application has: there is no
 * email sender and no push transport in this codebase, so "tell the admins somebody is waiting" is a
 * number on the screens they already open plus this queue. It is rendered FIRST, above the search
 * box and above everything else, because an admin who has to find it has not been notified.
 *
 * ── WHY REJECT IS FINAL, AND WHY THAT IS SAID ON SCREEN ──────────────────────────────────────────
 *
 * A rejected person's next sign-in does NOT put them back in this queue: the server bumps their
 * attempt count, leaves the status alone, and tells them their request was not approved. Any other
 * choice makes the queue unworkable — the admin clears it, the same people retry overnight, and it
 * is full again with entries they already decided. The confirmation says so in words, because an
 * admin who believes Reject is temporary will use it as "not now" and never look at the person
 * again.
 *
 * ── THE DEFECTS THIS SCREEN IS WRITTEN NOT TO REPEAT ─────────────────────────────────────────────
 *
 * `docs/OPEN_FINDINGS.md` closed four defects in the design-workshop viewer picker and three were
 * the same shape: ONE page fetched, filtered in the browser, with nothing on screen saying the
 * answer was a prefix — so eligible people were invisible and looked exactly like people who had
 * never existed. Therefore, on this screen: the search goes to the SERVER (`?search=`), the status
 * filter goes to the SERVER (`?status=`), every list renders the server's `total` and `pages`
 * rather than counting the rows it happens to hold, and there is no `.filter()` over a fetched page
 * anywhere in this file. The fourth defect was an empty truncated answer that told the admin to
 * narrow an already-empty search; the empty states below therefore distinguish "no rows match this
 * search" from "there is genuinely nobody here", and never advise narrowing a search that is empty.
 *
 * **THAT INVARIANT NOW COVERS SEVEN CONTROLS, NOT TWO, AND IT HAS NOT MOVED.** The row above the
 * table is `RosterFilterBar` (DROPDOWN_DESIGN §4.9) and every one of its controls is a QUERY
 * PARAMETER: the search box, the standing multi-select, the tier multi-select, the date column, the
 * period and its two custom bounds, and the sortable column headers. `rosterQueryParams` turns the
 * whole filter object into one query string, `load()` sends it, and the server answers with the
 * rows and the `total`. **Nothing on this page narrows, re-orders or counts a fetched page**, and
 * the four columns that sort do it by asking again rather than by re-ordering the twenty rows in
 * the browser — an on-device sort of one page shows "the oldest of page one", which is a different
 * and wrong answer, and paging through it walks a list that is re-sorted per page.
 *
 * ── WHAT THE DEFAULT QUERY STRING IS, AND WHY IT IS EMPTY ────────────────────────────────────────
 *
 * `GET /access/roster?page=1&pageSize=20` — and **not one filter key.** `emptyRosterFilters`
 * produces a state in which every control is at its widest, `rosterQueryParams` maps that to an
 * object whose every value is `undefined`, and `buildQuery` drops all of them. No `status` key is
 * the server's spelling of EVERY status, so the first page an admin sees carries ACTIVE, PENDING,
 * REJECTED and SUSPENDED rows exactly as it did before req 30 — byte for byte the same request this
 * screen made yesterday.
 *
 * That is DROPDOWN_DESIGN §4.6's rule (ii), and it is the rule this screen exists to obey. An admin
 * arrives here BECAUSE somebody cannot sign in; the row that refuses them is a REJECTED or a
 * SUSPENDED one, and a filter that defaulted those out of view would hide the only row worth
 * opening the page for. There is deliberately **no "hide suspended" control** — it would be a
 * second spelling of ticking the other three, which is rule (i)'s two-states-for-one-meaning on top
 * of rule (ii). Empty means everything, by absence, and absence is what an untouched screen sends.
 *
 * ── THE ADDRESS BAR IS THIS PAGE'S OUTPUT ───────────────────────────────────────────────────────
 *
 * A narrowed allow-list is a link. `accessRosterHref` writes the filter state back with
 * `history.replaceState` — the shape `/search` uses and for its reason: these are buttons and ticks,
 * not navigations, and a router push per tick would fill the Back stack with a filter row somebody
 * was adjusting. The link carries the date PRESET rather than the resolved instants, so "last 30
 * days" pasted to a colleague tomorrow means THEIR last 30 days.
 *
 * ── NOTHING HERE DELETES ─────────────────────────────────────────────────────────────────────────
 *
 * Suspend, never Remove — and the server's DELETE is a suspension that answers 200 with the
 * suspended row. The row holds the joining date, the attempt history and the name of the admin who
 * admitted them; and because the gate reads a MISSING row as PENDING, a real delete would silently
 * put the person back in the queue they were just removed from.
 */

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { BadgeCheck, Clock, MailPlus, ShieldCheck, UserCheck, UserX } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { Dropdown } from "@/components/ui/Dropdown";
import { FieldLabelProvider } from "@/components/ui/fieldLabel";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RestrictedPanel } from "@/components/settings/RestrictedPanel";
import { RowActions, rowAction } from "@/components/RowActions";
import { useAuth } from "@/components/AuthProvider";
import { useConfirm } from "@/components/dialogs/ConfirmDialog";
import { refreshPendingAccessCount, usePendingAccessCount } from "@/components/hooks/usePendingAccessCount";
import { RosterFilterBar } from "@/components/admin/RosterFilterBar";
import { SortableTh, type RosterSortControl } from "@/components/admin/SortableTh";
import {
  clearRosterFilters,
  hasActiveRosterFilters,
  rosterFiltersFromSearchParams,
  type RosterFilters
} from "@/components/admin/rosterFilters";
import { CappedListNotice } from "@/components/data/CappedListNotice";
import { CUT_NOTICE_LIVE_REGION, searchCutNotice } from "@/components/data/cappedList";
import { ApiError } from "@/lib/api";
import {
  addToAccessRoster,
  decideAccessRequest,
  listAccessRoster,
  suspendAccessEntry,
  updateAccessEntry,
  type AccessRosterEntry,
  type AccessRosterPage
} from "@/lib/accessRoster";
import { formatDate, formatDateTime } from "@/lib/format";
import { assignableRoles, canManageAccessRoster, roleLabel } from "@/lib/permissions";
import type { UserRole } from "@/lib/types";
import {
  ACCESS_LIST_STALE_NOTE,
  ACCESS_LIST_UNREADABLE_BODY,
  ACCESS_LIST_UNREADABLE_TITLE,
  ACCESS_NOBODY_YET_BODY,
  ACCESS_NOBODY_YET_TITLE,
  ACCESS_NO_MATCH_BODY,
  ACCESS_NO_MATCH_TITLE,
  ACCESS_PAST_END_TITLE,
  accessRoleCutNotice,
  accessRosterHref,
  listFilteredAccessRoster,
  type AccessRosterAnswer
} from "./rosterQuery";

/** The whole list. Twenty is the designer roster's page size and this table is no denser. */
const PAGE_SIZE = 20;

/**
 * The queue's own page size, deliberately smaller.
 *
 * It sits at the top of a screen whose main list is underneath it, so a queue that paged at twenty
 * would push the roster below two screenfuls of requests on the one day an admin most needs both.
 * Paged rather than capped: a truncated queue is a person nobody ever decides about.
 */
const QUEUE_PAGE_SIZE = 8;

/**
 * WHICH ROSTER THIS IS, for the shared filter vocabulary. Spelled once.
 *
 * `components/admin/rosterFilters.ts` holds one filter shape for both admin rosters and gates every
 * per-route difference on this token: the allow-list gets a `status` multi-select and no
 * institution filter, the designer roster gets `standing` and `institutions`. Passing it at each
 * call rather than currying it keeps the gate visible at the call site, which is where somebody
 * copying a line to the other screen will be looking.
 *
 * THE STATUS VOCABULARY THAT USED TO LIVE HERE IS NOW `ACCESS_STATUS_OPTIONS` IN THAT MODULE, and
 * it changed shape on the way: a single-select whose rows read "Only those refused" became a
 * multi-select whose rows read "Refused", because "Only" is a true word where one choice excludes
 * the others and a false one the moment two can be ticked together. Its widest state is no longer a
 * row in the list — it is the empty selection, which sends no `status` key at all. That is rule (i)
 * (empty means everything, BY ABSENCE) and it is what makes "nothing ticked" and "all four ticked"
 * two different requests instead of two spellings of one.
 */
const KIND = "access" as const;

/**
 * Next 16 suspends on `useSearchParams`, and this page reads it to restore a colleague's pasted
 * filter link. Same wrapper `/crafts`, `/workshops` and `/design-workshops` use.
 *
 * The fallback is the page's own header rather than a bare "Loading..." — the heading and the back
 * arrow are known before any query string is, and swapping the whole frame in and out makes the
 * screen jump on every navigation to it.
 */
export default function PlatformAccessPage() {
  return (
    <Suspense
      fallback={
        <PageHeader
          title="Who may sign in"
          icon={<ShieldCheck className="h-5 w-5" aria-hidden />}
        />
      }
    >
      <PlatformAccessScreen />
    </Suspense>
  );
}

// Named for the SCREEN and not for the table, because `AccessRosterPage` is the paged-response type
// this file imports and two things with one name in one module is a compile error waiting for the
// next person who adds an import.
function PlatformAccessScreen() {
  const { user } = useAuth();
  const confirm = useConfirm();
  const permitted = canManageAccessRoster(user);

  /** The badge's number, shared with the nav and the hub tile so the three cannot disagree. */
  const counted = usePendingAccessCount(permitted);

  /**
   * THE URL SEEDS THE FILTERS ONCE; IT DOES NOT OWN THEM.
   *
   * Read in a lazy initialiser so a pasted link lands the reader on the list their colleague was
   * looking at, and then never read again: re-seeding from the address bar on every change would
   * fight the write-back effect below, which is what puts the state INTO the address bar. `/search`
   * draws the same line in the same words — "from the first write-back on, the address bar is this
   * page's output, not its input".
   *
   * `rosterFiltersFromSearchParams` falls back to `emptyRosterFilters` for anything it does not
   * recognise, so a bare `/admin/access` starts at its widest and a link carrying a mode this build
   * has never heard of renders a control the reader can still move, rather than a screen stuck on a
   * value with no row.
   */
  const searchParams = useSearchParams();
  const [filters, setFilters] = useState<RosterFilters>(() =>
    rosterFiltersFromSearchParams(KIND, new URLSearchParams(searchParams.toString()))
  );

  const [queue, setQueue] = useState<AccessRosterPage | null>(null);
  const [queuePage, setQueuePage] = useState(1);
  const [data, setData] = useState<AccessRosterAnswer | null>(null);
  const [page, setPage] = useState(1);
  /**
   * DID THE LAST READ OF THE LIST FAIL? Kept apart from `error`, which also carries the failures of
   * every write on this screen.
   *
   * It is the difference between §3.5's *could-not-be-listed* and its *genuinely-empty*, and those
   * are the two sentences this repository has most often collapsed into one. An empty table drawn
   * from a failed request must never say "nobody has been admitted yet": that is a claim about an
   * institution's access control made from a network error, and the admin who believes it starts
   * re-adding addresses that are already on the list.
   */
  const [loadFailed, setLoadFailed] = useState(false);
  /**
   * The filter bar's search box is holding a term the server has not been asked about yet.
   *
   * Fed straight to `searchCutNotice({ pending })`. Without it a debounced box prints "No entries
   * match “ravi”" over a request that has not run — a statement about the whole list drawn from a
   * query that has not happened, which on the connections this app is built for is a door about a
   * second and a half wide. `setSearchPending` is a stable setter, which is what the bar asks for.
   */
  const [searchPending, setSearchPending] = useState(false);
  const [editing, setEditing] = useState<AccessRosterEntry | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const formRef = useRef<HTMLFormElement | null>(null);

  /**
   * Fetch generations, not aborts. `listAccessRoster` takes no signal and what matters is IGNORING a
   * late answer: without this, a typed search whose first response lands after the second overwrites
   * the newer rows with older ones and the table shows results for a query nobody can see any more.
   * The designer roster carries the identical guard; see the note there.
   */
  const generation = useRef(0);
  const queueGeneration = useRef(0);

  const loadQueue = useCallback(async () => {
    if (!permitted) return;
    const mine = ++queueGeneration.current;
    try {
      const result = await listAccessRoster({ page: queuePage, pageSize: QUEUE_PAGE_SIZE, status: "PENDING" });
      if (mine !== queueGeneration.current) return;
      setQueue(result);
      // DECIDING THE LAST REQUEST ON A PAGE SHORTENS THE QUEUE UNDERNEATH THE ADMIN. The server
      // answers a page past the end with an empty list and a `total` that is still positive, and
      // this section would then say "Nobody is waiting" over a queue that is not empty — the exact
      // shape of the closed picker defects: rows that exist and cannot be seen. Step back instead.
      if (result.items.length === 0 && result.pages > 0 && queuePage > result.pages) setQueuePage(result.pages);
    } catch (err) {
      if (mine !== queueGeneration.current) return;
      setError(err instanceof Error ? err.message : "Unable to load the approval queue");
    }
  }, [permitted, queuePage]);

  /**
   * ONE REQUEST, EVERY FILTER, BUILT AT THE MOMENT IT IS SENT.
   *
   * `rosterQueryParams` runs inside `listFilteredAccessRoster`, on this call, and is never hoisted
   * or memoised: it resolves the date PRESETS ("last 30 days") into concrete instants against the
   * reader's own clock, so a tab left open overnight asks about today rather than about the day it
   * was opened. Everything else — the term, the ticked standings, the ticked tiers, the sort and
   * its direction — goes into the same query string, so the filters AND into one answer rather than
   * being applied in passes the reader would watch happen one at a time.
   */
  const load = useCallback(async () => {
    if (!permitted) return;
    const mine = ++generation.current;
    try {
      const result = await listFilteredAccessRoster({ page, pageSize: PAGE_SIZE, filters });
      if (mine !== generation.current) return;
      setData(result);
      setLoadFailed(false);
      setError(null);
      // The same step-back as the queue above, for the same reason: suspending or approving the last
      // row of the last page must not leave the admin on an empty page below a total that says
      // otherwise. It applies to a NARROWING too — every filter change resets the pager to 1 below,
      // but a colleague's link can name a page that the same filters no longer reach.
      if (result.items.length === 0 && result.pages > 0 && page > result.pages) setPage(result.pages);
    } catch (err) {
      if (mine !== generation.current) return;
      setError(err instanceof Error ? err.message : "Unable to load the access list");
      // `data` is left standing on a failed refresh, deliberately: replacing a list the admin can
      // still read with "nobody may sign in" is indistinguishable from an empty institution, and on
      // THIS screen that reading is alarming enough to act on. What the flag adds is the other half
      // of that bargain — rows that are known to be an OLD answer say so (`ACCESS_LIST_STALE_NOTE`),
      // and a first load that never landed renders §3.5's could-not-be-listed sentence instead of
      // "Loading the list…" for as long as the tab is open.
      setLoadFailed(true);
    }
  }, [filters, page, permitted]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    loadQueue();
  }, [loadQueue]);

  /**
   * EVERY CHANGE THE FILTER BAR OR A COLUMN HEADER MAKES ARRIVES HERE, AND THE PAGER GOES BACK TO 1.
   *
   * Unconditionally, including for a sort. A narrowed list has different rows at `OFFSET 40` and a
   * re-ordered one has different rows at every offset, so a reader who was on page 3 lands somewhere
   * arbitrary in a list they have just changed — and on a list that got shorter, past the end of it,
   * which draws the "nobody is here" empty state over a roster that is not empty. Both
   * `RosterFilterBar` and `SortableTh` say in their prop docs that this is the page's job; this is
   * the page doing it, once, for both of them.
   */
  const applyFilters = useCallback((next: RosterFilters) => {
    setFilters(next);
    setPage(1);
  }, []);

  /**
   * THE FILTER STATE, WRITTEN BACK INTO THE ADDRESS BAR.
   *
   * `history.replaceState` and not the router: a tick is not a navigation, and a `router.replace`
   * per keystroke of a debounced box would re-render the route tree while somebody is typing in it.
   * `/search` made this exact move for this exact reason after its filter chips stopped being links.
   *
   * The write happens on mount too, which canonicalises whatever was pasted — `?status=SUSPENDED,
   * PENDING` comes back as `?status=PENDING,SUSPENDED`, one order, so the same three ticks cannot
   * produce two different links. The default state produces no keys at all, so an untouched screen
   * keeps the bare `/admin/access` a bookmark expects.
   */
  useEffect(() => {
    window.history.replaceState(null, "", accessRosterHref(filters));
  }, [filters]);

  /** Re-read everything a decision can have moved, including the badge on the other surfaces. */
  const reloadAll = useCallback(async () => {
    await Promise.all([load(), loadQueue(), refreshPendingAccessCount()]);
  }, [load, loadQueue]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // React nulls `currentTarget` across an await, so the FormData is read before any async work —
    // after the first `await` every field arrives empty.
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const email = String(form.get("email") ?? "").trim();
    const fullName = String(form.get("fullName") ?? "").trim();
    const notes = String(form.get("notes") ?? "").trim();
    const role = String(form.get("role") ?? "").trim();
    if (!email) return;

    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      if (editing) {
        /*
          A CORRECTION, AND IT CANNOT MOVE THE GATE. The PATCH deliberately refuses `status` on the
          server, so that every transition goes through the decision or suspend endpoint and is
          stamped with who decided and when. The email is not sent either: it is the row's unique key
          and the address the gate compares against, so "fix the spelling of a name" must never be
          able to hand somebody else's access to a different mailbox. Re-typing an address is
          therefore adding an entry, which is the form's other mode.
        */
        const updated = await updateAccessEntry(editing.id, {
          fullName: fullName || null,
          role: (role || null) as UserRole | null,
          notes: notes || null
        });
        setNotice(`Updated the entry for ${updated.email}.`);
        setEditing(null);
        formElement.reset();
        await reloadAll();
        return;
      }
      const created = await addToAccessRoster({
        email,
        fullName: fullName || null,
        role: (role || null) as UserRole | null,
        notes: notes || null
      });
      setNotice(
        `${created.email} may sign in. They join as ${roleLabel(created.admitRole) || "the platform default tier"}, and the account is created the first time they sign in.`
      );
      formElement.reset();
      await reloadAll();
    } catch (err) {
      // A 409 carries the sentence that matters — it names the existing row, says what state it is
      // in, and says that deciding it is a different call — so it is shown verbatim, and the search
      // box is then pointed at the address so the admin is looking at the row rather than reading
      // about it. Straight from the designer roster's fix for the same collision.
      setError(err instanceof Error ? err.message : "Unable to add this address");
      if (err instanceof ApiError && err.status === 409) {
        // EVERY FILTER OUT OF THE WAY, THEN THE ADDRESS INTO THE BOX. `clearRosterFilters` empties
        // the standings, the tiers and the date range and deliberately KEEPS the sort — an order is
        // not a filter and throwing away the one the admin chose is a second, unasked-for change
        // dressed up as tidying. Clearing matters more than it looks: the row that collides is very
        // often a REJECTED or SUSPENDED one, so leaving a standing tick in place would answer the
        // 409 with an empty table and the admin would conclude the server was wrong about the
        // duplicate. The bar picks the term up through its own "arrived from outside" path.
        applyFilters({ ...clearRosterFilters(KIND, filters), search: email });
      }
    } finally {
      setSaving(false);
    }
  }

  async function approve(entry: AccessRosterEntry, role: UserRole | "") {
    const ok = await confirm({
      title: `Let ${entry.email} in?`,
      body: role
        ? `They will be able to sign in immediately, as ${roleLabel(role)}.`
        : "They will be able to sign in immediately, at this platform's default joining tier — the lowest rung — and can be promoted afterwards on Manage users.",
      note:
        "If they already have an account at a lower tier it is raised to match. An account that is already higher is never lowered by approving somebody.",
      confirmLabel: "Approve",
      tone: "warning"
    });
    if (!ok) return;
    try {
      const updated = await decideAccessRequest(entry.id, { decision: "APPROVE", role: role || null });
      setNotice(`${updated.email} may sign in from their next attempt.`);
      setError(null);
      await reloadAll();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to approve this request");
    }
  }

  async function reject(entry: AccessRosterEntry) {
    const ok = await confirm({
      title: `Refuse ${entry.email}?`,
      body: "They will be told their request was reviewed and not approved, and whom to contact.",
      // The tone is danger and the note has to correct what it implies twice over: nothing is
      // deleted, AND the refusal does not expire. An admin using this as "not now" would never see
      // the person again, because a rejected person's next attempt bumps a counter instead of
      // rejoining this queue.
      note:
        "Nothing is deleted — the entry stays on the list, refused, with the record of when they asked and how many times they have tried. Trying again will NOT put them back in this queue: only you can reopen it, by approving them here later.",
      confirmLabel: "Refuse",
      tone: "danger"
    });
    if (!ok) return;
    try {
      const updated = await decideAccessRequest(entry.id, { decision: "REJECT" });
      setNotice(`${updated.email} was refused. The entry stays on the list.`);
      setError(null);
      await reloadAll();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to refuse this request");
    }
  }

  async function suspend(entry: AccessRosterEntry) {
    const ok = await confirm({
      title: `Suspend ${entry.fullName || entry.email}?`,
      body: "They will be refused at their next sign-in, and told that their access to this application was ended.",
      note:
        "The entry is kept — it records when they joined, and that record outlives their access. Approving them again here restores it, and their joining date is not moved by the round trip.",
      confirmLabel: "Suspend",
      tone: "danger"
    });
    if (!ok) return;
    try {
      const updated = await suspendAccessEntry(entry.id);
      setNotice(`${updated.email} can no longer sign in. The entry stays on the list.`);
      setError(null);
      await reloadAll();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to suspend this entry");
    }
  }

  async function restore(entry: AccessRosterEntry) {
    const ok = await confirm({
      title: `Let ${entry.email} back in?`,
      body: "They will be able to sign in again immediately.",
      note: entry.joinedAt
        ? `Their joining date stays ${formatDate(entry.joinedAt)} — somebody who joined then, lost access and was let back in has still been here since then.`
        : undefined,
      confirmLabel: "Restore",
      tone: "warning"
    });
    if (!ok) return;
    try {
      const updated = await decideAccessRequest(entry.id, { decision: "APPROVE" });
      setNotice(`${updated.email} can sign in again.`);
      setError(null);
      await reloadAll();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to restore this entry");
    }
  }

  function startEdit(entry: AccessRosterEntry) {
    setEditing(entry);
    setError(null);
    setNotice(null);
    // The form is above the table; on a long list the edit that was just asked for would otherwise
    // happen off screen. Deferred a frame because the form remounts on its `key` and only then has
    // its final height.
    requestAnimationFrame(() => formRef.current?.scrollIntoView({ block: "start", behavior: "smooth" }));
  }

  const header = (
    <PageHeader
      title="Who may sign in"
      description="Every address allowed into this application, plus the people waiting for a decision. Nothing here is ever deleted — access is granted, refused, suspended and restored, and the entry keeps the history either way."
      icon={<ShieldCheck className="h-5 w-5" aria-hidden />}
    />
  );

  /**
   * Gated three times over — ROUTE_GUARDS above this page, `require_access_manager` on every request
   * it would make, and here. The panel is still rendered, because a client guard that only hides a
   * nav item is not a guard, and somebody on a stale bookmark deserves a sentence rather than an
   * empty screen.
   */
  if (!permitted) {
    return (
      <>
        {header}
        <RestrictedPanel
          title="Admin access required"
          body={
            `Deciding who may sign in — and reading the queue of people waiting, which is a list of named individuals who tried to get in — is admin work: ` +
            // `roleLabel` answers "" for an absent user; AppShell never renders a protected page
            // without one, but a sentence reading "  does not open it" is a worse way to discover
            // that than a fallback nobody will see.
            `${roleLabel(user?.role) || "your tier"} does not open it, and the API refuses the same request for the same reason. ` +
            `An admin or the master admin can approve, refuse, suspend and restore people here.`
          }
        />
      </>
    );
  }

  const grantable = assignableRoles(user);
  const queueRows = queue?.items ?? [];
  const rows = data?.items ?? [];

  /**
   * Is anything narrowing the list? Read off the CONTROLS, not off the wire.
   *
   * It decides which of §3.5's sentences an empty table gets, and the two are not interchangeable:
   * "no entry matches these filters" is about a question the admin asked, "nobody is on the list
   * yet" is about the institution. Reading it off the controls is also what makes "Custom range with
   * both boxes empty" count — a state that visibly changed a control and sends no date keys.
   */
  const filtered = hasActiveRosterFilters(KIND, filters);

  /**
   * Built once and handed to every sortable header, so the nine `sort` tokens this route accepts
   * cannot drift into nine slightly different wirings. `applyFilters` is the same handler the filter
   * bar uses, which is what guarantees a re-sort resets the pager exactly as a re-filter does.
   */
  const sortControl: RosterSortControl = { kind: KIND, filters, onChange: applyFilters };

  /**
   * THE ONE SENTENCE UNDER AN EMPTY TABLE, decided in `components/data/cappedList.ts` rather than in
   * the JSX below.
   *
   * Called ONLY where there are no rows, and that is a rule rather than an accident. This list is
   * genuinely paged — `Pagination` is on screen and the pager re-requests — so `loaded < total` is
   * the ordinary state of every page but the last and says nothing worth reading; a cut sentence
   * printed over it would be the standing note about pagination that makes the real notices mean
   * nothing when they do appear. `Pagination` already prints "Page 2 of 22 · 431 records", which is
   * the whole of the truth at that point.
   *
   * With no rows, `searchCutNotice` picks between three facts this screen must never collapse:
   *
   *   • `total > 0` — rows match and none of them are here (the pager is past the end, for the
   *     instant before the step-back re-reads). Worded by `cappedListNotice`'s first arm, which is
   *     tested BEFORE the term arm precisely because "nobody matches" would be flatly false.
   *   • a term was APPLIED and nothing matched — and the sentence says the search ran on the SERVER,
   *     over the whole list and not only the rows this page had loaded, because that is the fact an
   *     admin cannot see and would otherwise assume the other way round.
   *   • nothing typed — `emptyLabel`, which is this page's, because no shared module can know
   *     whether an empty answer means "no filter matched" or "the institution has admitted nobody".
   *
   * `pending` is the fourth: while the box holds a term the server has not been asked about, the
   * answer in hand is about a different question, so nothing is claimed at all.
   */
  const emptySentence = searchCutNotice({
    noun: "entries",
    loaded: 0,
    total: data?.total ?? 0,
    term: filters.search,
    emptyLabel: filtered ? ACCESS_NO_MATCH_BODY : ACCESS_NOBODY_YET_BODY,
    pending: searchPending
  });

  const emptyTitle =
    data && data.total > 0
      ? ACCESS_PAST_END_TITLE
      : filtered
        ? ACCESS_NO_MATCH_TITLE
        : ACCESS_NOBODY_YET_TITLE;

  return (
    <>
      {header}

      {/*
        THE ONE SENTENCE THAT STOPS AN ADMIN WORKING THE WRONG LIST. Two screens in this hub decide
        who can sign in and they read alike; the difference is that this one decides whether somebody
        may reach the application at all, and the other decides whether they are empanelled as a
        designer. An admin who suspends the wrong one takes away something they did not mean to, and
        the person is then told the wrong reason on the sign-in screen.
      */}
      <p className="mb-4 text-sm leading-6 text-ink-500">
        This is not the{" "}
        <Link href="/admin/designers" className="font-medium text-purple-700 hover:underline">
          designer roster
        </Link>
        , which is the narrower question of who the institution recognises as a designer. This list decides who may reach
        the application at all — and the master admin is never gated by it, which is what guarantees somebody can always
        get in here and let people back in.
      </p>

      {error ? (
        <div role="alert" className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      ) : null}
      {notice ? (
        <div className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">{notice}</div>
      ) : null}

      {/*
        THE QUEUE, FIRST AND ALWAYS RENDERED — including when it is empty. A section that vanished
        when nobody was waiting would leave an admin unable to tell "nobody has asked" from "the
        queue is somewhere else on this page", and the empty state is the only place the mechanism
        is ever explained to them.
      */}
      <section className="panel mb-6 overflow-hidden">
        <div className="flex flex-wrap items-center gap-3 border-b border-line-200 bg-surface-50 px-4 py-3">
          <div className="grid h-9 w-9 place-items-center rounded-md bg-amber-500/15 text-amber-700">
            <Clock className="h-5 w-5" aria-hidden />
          </div>
          <div className="min-w-0 flex-1">
            <h2 className="font-display font-bold text-ink-900">
              Waiting for a decision
              {queue ? (
                <span className="ml-2 rounded-full bg-amber-100 px-2 py-0.5 align-middle text-xs font-semibold text-amber-800">
                  {/* The SERVER's total, never `queueRows.length` — the queue is paged, and a badge
                      that counted the page would under-report the moment a ninth person asked. */}
                  {queue.total}
                </span>
              ) : null}
            </h2>
            <p className="text-sm text-ink-500">
              Each of these people proved who they are — a correct password, or a verified Google account — and was turned
              away because this list did not carry their address. They are told they are waiting for you.
            </p>
          </div>
        </div>

        {/*
          THE CEILING, SAID OUT LOUD. Past `capacity` the server stops recording new requests and
          tells the person to contact an administrator directly. An admin who is not told this reads
          a queue that has stopped growing as "nobody is asking" — the one reading that is certainly
          wrong — and every person turned away in the meantime is invisible to this product forever.
        */}
        {counted?.capReached ? (
          <div className="border-b border-amber-500/30 bg-amber-100 px-4 py-2 text-xs leading-5 text-amber-900">
            This queue is at its ceiling of {counted.capacity} waiting requests, so new ones are NO LONGER BEING RECORDED —
            anybody turned away now is told that requests are temporarily closed and to contact an administrator directly.
            Decide the requests below to make room.
          </div>
        ) : null}

        {queue === null ? (
          // null is "still loading"; [] is "genuinely nobody". Saying "nobody is waiting" during a
          // fetch is wrong in the direction that makes an admin close the page.
          <div className="p-4 text-sm text-ink-700">Loading the approval queue…</div>
        ) : queueRows.length === 0 ? (
          <div className="p-4">
            <EmptyState
              title="Nobody is waiting"
              body="When somebody who is not on this list proves their identity at the sign-in screen, they appear here — with their address, when they asked and how many times they have tried — and are told an administrator has to approve them."
            />
          </div>
        ) : (
          <ul className="divide-y divide-line-200">
            {queueRows.map((entry) => (
              <QueueRow
                key={entry.id}
                entry={entry}
                grantable={grantable}
                onApprove={(role) => approve(entry, role)}
                onReject={() => reject(entry)}
              />
            ))}
          </ul>
        )}
        {queue && queue.total > QUEUE_PAGE_SIZE ? (
          <Pagination page={queue.page} pages={queue.pages} total={queue.total} onPage={setQueuePage} />
        ) : null}
      </section>

      <form
        ref={formRef}
        onSubmit={submit}
        // Remounted when the row being edited changes, so every `defaultValue` is re-seeded — an
        // uncontrolled form otherwise keeps the previous row's text in its boxes.
        key={editing?.id ?? "new"}
        className="panel mb-5 grid gap-4 p-4"
      >
        <div>
          <h2 className="font-display text-lg font-bold text-ink-900">
            {editing ? `Correct the entry for ${editing.email}` : "Let somebody in by address"}
          </h2>
          <p className="mt-1 text-sm leading-6 text-ink-muted">
            {editing
              ? "The name, tier and note are your own record of whom you admitted and why. Changing them here cannot change whether this person may sign in — use Approve, Refuse, Suspend or Restore for that."
              : "No account has to exist yet: the address is admitted now and the account is created the first time that person signs in. Adding somebody here IS approving them, so they never appear in the queue above."}
          </p>
        </div>
        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-3">
          <Field label="Email address" required>
            <TextInput
              name="email"
              type="email"
              required
              defaultValue={editing?.email ?? ""}
              placeholder="colleague@institution.ac.in"
              // READ-ONLY WHILE CORRECTING AN ENTRY, and this is a rule rather than a nicety: the
              // address IS the gate, so an admin who edits it while meaning to fix a name would hand
              // one person's admission to another mailbox — and the person who lost it would simply
              // stop being able to sign in, with the entry on screen still saying they may.
              readOnly={editing !== null}
              // Lower-cased on the server before it is stored and before it is compared with the
              // address signing in, so capitals typed here are harmless.
              autoComplete="off"
            />
          </Field>
          <Field label="Full name">
            <TextInput name="fullName" maxLength={180} defaultValue={editing?.fullName ?? ""} />
          </Field>
          <Field label="Joins as">
            {/*
              THE THEMED DROPDOWN, INSIDE AN UNCONTROLLED FORM. The objection this replaced was
              real and is answered rather than ignored: "this one sits inside an uncontrolled form
              and is read out of FormData with everything else, and a controlled component here
              would need state whose only job is to be read back at submit."

              That state now exists, in `AdmitTierField` below, and it is read back through a hidden
              input — which is this repository's own pattern for putting a rich control into a
              FormData submit (`FormControls.MultiNoteField` does exactly this with its notes). The
              submit handler is untouched: it still reads `form.get("role")` and cannot tell the
              difference.

              THE RE-SEEDING STILL WORKS, and that is the part worth checking rather than assuming.
              This form carries `key={editing?.id ?? "new"}` precisely so every `defaultValue` is
              re-seeded when the admin switches rows. `AdmitTierField` holds its state INSIDE that
              subtree, so the remount resets it too. State lifted to the page component would not
              have reset, and the way that fails is the previous row's tier silently riding along
              into the next correction.
            */}
            <AdmitTierField grantable={grantable} initial={editing?.admitRole ?? ""} />
          </Field>
        </div>
        <Field label="Note">
          <TextArea
            name="notes"
            maxLength={4000}
            rows={2}
            defaultValue={editing?.notes ?? ""}
            placeholder="Who they are and why they were admitted."
          />
        </Field>
        <div className="flex flex-wrap gap-2">
          <button className="field-button" disabled={saving}>
            <MailPlus className="h-4 w-4" aria-hidden />
            {saving ? "Saving…" : editing ? "Update entry" : "Add to the list"}
          </button>
          {editing ? (
            <button type="button" className="field-button-secondary" onClick={() => setEditing(null)}>
              Cancel
            </button>
          ) : null}
        </div>
      </form>

      {/*
        THE FILTER ROW BOTH ADMIN ROSTERS MOUNT. One component, one vocabulary, two screens — not to
        save code but to save WORDS: an admin moves between this list and /admin/designers holding
        one thought ("why can this person not sign in"), and the two screens used to word the same
        filter two ways and announce both of them to a screen reader as the same three words over two
        controls with two different meanings.

        Everything in it is a query parameter. Nothing in it filters a fetched page, and there is no
        "hide suspended" control in it on purpose — see the header.
      */}
      <RosterFilterBar
        kind={KIND}
        filters={filters}
        onChange={applyFilters}
        onSearchPendingChange={setSearchPending}
      />

      {/*
        THE TIER FILTER'S OWN CUT, IF THE SERVER EVER REPORTS ONE.

        `roleMatchTruncated` is documented to be always `false` on this route — the allow-list's tier
        filter is `admitRole`, a real column, so nothing has to be read to match it and nothing can
        fall off the end. It is on the envelope so both rosters answer one shape. A flag that cannot
        be true still gets a sentence, because "cannot" here is a property of a server this client
        does not deploy: if a later build ever sets it, an admin filtering by tier would otherwise be
        shown a confidently incomplete list with nothing on screen to say so, which is rule (iii)'s
        whole subject.

        RENDERED HERE RATHER THAN PASSED TO THE BAR, AND THAT IS THE ONE DEVIATION ON THIS SCREEN.
        `RosterFilterBar` takes a `roleMatchTruncated` prop and asks callers to pass it there rather
        than draw it beside the table — but it hard-suppresses the notice for `kind="access"`
        (`RosterFilterBar.tsx`, `roleMatchCutNotice(access ? undefined : …)`), because the sentence it
        would print explains the DESIGNER roster's mechanism: reading the accounts that hold a tier,
        which is not how this route matches one. So the prop is deliberately not passed — passing a
        value that is discarded reads as wiring that works — and the sentence is worded for this
        screen in `rosterQuery.accessRoleCutNotice`, which also explains why it is not
        `flagCutNotice`'s. **When the bar renders this for the access kind, delete this block and
        pass the flag.** Two copies of one sentence in two places is how a reader learns that neither
        means much.

        The live region is mounted here, empty, on first render: assistive technology announces
        mutations inside a region that ALREADY EXISTED when the page settled, and one created
        together with its first sentence announces nothing at all — the bug `EntityForm`'s cap notice
        shipped.
      */}
      <div {...CUT_NOTICE_LIVE_REGION}>
        {/* The margin belongs to the NOTICE and not to the region, so a region with nothing to say
            occupies nothing — an empty gap under a complete list is the padding this UI has twice
            been asked to lose. */}
        <CappedListNotice
          cuts={[accessRoleCutNotice(data?.roleMatchTruncated)]}
          className="mb-4 grid gap-0.5"
        />
      </div>

      <section className="panel overflow-hidden">
        {/*
          THE ROWS ARE OLD AND THEY SAY SO. A failed refresh deliberately leaves the last answer
          standing — replacing a readable list with "nobody may sign in" is indistinguishable from an
          emptied institution — but rows nobody has labelled are read as current, and on this screen
          the difference decides whether a colleague is told to try again or told to ring an admin.
        */}
        {loadFailed && data !== null ? (
          <div className="border-b border-amber-500/30 bg-amber-100 px-4 py-2 text-xs leading-5 text-amber-900">
            {ACCESS_LIST_STALE_NOTE}
          </div>
        ) : null}

        {/*
          FIVE STATES, NOT TWO, AND THE LIVE REGION IS MOUNTED AROUND ALL OF THEM.

          `data === null` used to mean "loading" and nothing else, so a first load that FAILED sat on
          "Loading the list…" for as long as the tab stayed open — the request was over, the error
          banner was above, and the panel still promised an answer that was never coming. The five
          are: still loading · the read failed with nothing to show · a term is settling in the box ·
          answered and empty (three sentences of its own, decided by `searchCutNotice`) · rows.

          The wrapper is a `role="status"` region that exists from the first render and holds `null`
          while the table is up, because a region created at the same moment as its first sentence
          announces nothing at all. What changes inside it is exactly what a reader who has just
          typed, ticked or paged needs read back to them.
        */}
        <div {...CUT_NOTICE_LIVE_REGION}>
          {data === null && !loadFailed ? (
            <div className="p-4 text-sm text-ink-700">Loading the list…</div>
          ) : data === null ? (
            <div className="p-4">
              <EmptyState title={ACCESS_LIST_UNREADABLE_TITLE} body={ACCESS_LIST_UNREADABLE_BODY} />
            </div>
          ) : rows.length === 0 && searchPending ? (
            // The box holds a term the server has not been asked about yet. The rows in hand answer
            // a different question, so nothing is claimed about this one — least of all that it
            // matches nobody.
            <div className="p-4 text-sm text-ink-700">Searching…</div>
          ) : rows.length === 0 ? (
            <div className="p-4">
              {/* NEVER "narrow your search" over an empty search. That advice, printed over an empty
                  result an admin had not searched for, is the closed defect from the viewer picker
                  arriving on a new screen — and `searchCutNotice` is the module that refuses to
                  print it, which is why the wording is not decided here. */}
              <EmptyState title={emptyTitle} body={emptySentence} />
            </div>
          ) : null}
        </div>

        {rows.length === 0 ? null : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1240px] text-left text-sm">
              {/*
                SEVEN OF THE NINE ORDERS THIS ROUTE ACCEPTS, EACH ON THE COLUMN WHOSE DATUM IT SORTS.

                `SortableTh` is `ResizableTh` plus a button and `aria-sort` — the first `aria-sort` in
                this frontend — and it sorts NOTHING itself: it hands `applyFilters` a new filter
                object and the list is re-read. That is rule (iv) in the one place it is easiest to
                break, because re-ordering twenty rows in the browser looks identical until you page.
                It is not: "oldest first" over one page shows the oldest of PAGE ONE, and paging
                through it walks a list re-sorted per page.

                THE PERSON COLUMN SPLIT INTO NAME AND EMAIL to make both of its orders reachable. The
                cell used to stack them, and a header cell can carry one sort; leaving `email` off
                would have made the row's own identity — the address the gate actually compares —
                the one column an admin could not order by.

                TWO ORDERS HAVE NO COLUMN AND THAT IS DELIBERATE. `requested` and `decided` are
                sub-line facts of the queue above and of the standing chip beside them, and heading
                them would mean two more columns on a table that is already the widest on the site.
                Both remain reachable by link (`?sort=requested&dir=asc` is the queue order an admin
                works oldest-first), and both are named in DROPDOWN_DESIGN §4.3 rather than lost.
              */}
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <SortableTh control={sortControl} column="name" label="Name" />
                  <SortableTh control={sortControl} column="email" label="Email" />
                  <SortableTh control={sortControl} column="standing" label="Standing" />
                  <SortableTh control={sortControl} column="added" label="Added" />
                  <SortableTh control={sortControl} column="joined" label="Joined" />
                  <SortableTh control={sortControl} column="firstSeen" label="First signed in" />
                  <SortableTh control={sortControl} column="attempts" label="Requests" />
                  {/* Not sortable, and not for want of a token: the server offers no order over a
                      free-text note, and an admin's private prose is not a thing anybody ranks. */}
                  <ResizableTh>Note</ResizableTh>
                  <ResizableTh className="text-right">Actions</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {rows.map((entry) => (
                  <tr key={entry.id}>
                    <td className="px-4 py-3">
                      <div className="font-medium text-ink-900">
                        {/* The name is whatever an ADMIN typed. A person who has only ever been
                            refused has none, and that absence is stated rather than left blank —
                            nothing here is ever populated from an unverified profile. */}
                        {entry.fullName || <span className="text-ink-500">Name not recorded</span>}
                      </div>
                    </td>
                    {/* `break-all` rather than `truncate`: this is the row's identity and the value
                        an admin is usually here to compare against something in a message, so a long
                        address wraps rather than losing its tail behind an ellipsis. */}
                    <td className="px-4 py-3 text-ink-700"><span className="break-all">{entry.email}</span></td>
                    <td className="px-4 py-3">
                      <StandingChip entry={entry} />
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {/* WHEN THE ROW WAS MADE, which is the list's default order and therefore the
                          column carrying `aria-sort` on arrival. For a pending request it is when
                          they first asked; for an entry an admin typed it is when they were let in.
                          It is NOT the joining date and the two are separate columns because a row
                          created by hand today can carry a joining date of 2024. */}
                      {entry.createdAt ? formatDate(entry.createdAt) : "—"}
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {/* The requirement's "date of joining the platform". Written once and never
                          moved by a suspension and restore. */}
                      {entry.joinedAt ? formatDate(entry.joinedAt) : "—"}
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {/*
                        THE OUTSTANDING-INVITATION COLUMN, and the reason it is worth its own header
                        rather than the sub-line it used to be: `sort=firstSeen&dir=desc` puts every
                        row with NO first sign-in at the top — Postgres sorts NULLs first on desc —
                        which is the "who have I admitted who has never turned up" question as an
                        order instead of a hunt. The two absences are told apart, because they need
                        different actions: admitted and never arrived is a person to chase; never
                        admitted is a person to decide about.
                      */}
                      {entry.firstSeenAt ? (
                        formatDate(entry.firstSeenAt)
                      ) : (
                        <span className="text-ink-500">
                          {entry.joinedAt ? "Has not signed in yet" : "Has never been admitted"}
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {entry.attemptCount > 0 ? (
                        <>
                          {entry.attemptCount} refused attempt{entry.attemptCount === 1 ? "" : "s"}
                          <span className="block text-xs text-ink-500">
                            {entry.lastAttemptAt ? `Last ${formatDateTime(entry.lastAttemptAt)}` : "Date not recorded"}
                          </span>
                        </>
                      ) : (
                        "—"
                      )}
                    </td>
                    <td className="max-w-xs px-4 py-3 text-ink-700">
                      {entry.notes ? <span className="line-clamp-3 whitespace-pre-line">{entry.notes}</span> : "—"}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <RowActions>
                        <button type="button" className={rowAction("edit")} onClick={() => startEdit(entry)}>
                          Edit
                        </button>
                        {entry.status === "ACTIVE" ? (
                          // Never labelled "Delete". The verb has to say what happens, and what
                          // happens is that the entry stays.
                          <button type="button" className={rowAction("danger")} onClick={() => suspend(entry)}>
                            Suspend
                          </button>
                        ) : (
                          <button type="button" className={rowAction("positive")} onClick={() => restore(entry)}>
                            <UserCheck className="h-3.5 w-3.5" aria-hidden />
                            {entry.status === "PENDING" ? "Approve" : "Let back in"}
                          </button>
                        )}
                        {entry.status === "PENDING" ? (
                          <button type="button" className={rowAction("danger")} onClick={() => reject(entry)}>
                            Refuse
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

/**
 * One waiting request: who, when they asked, how hard they have been trying, and the two answers.
 *
 * THE TIER IS CHOSEN AT THE MOMENT OF APPROVAL and defaults to the platform's lowest rung, because
 * that is this platform's documented rule for a new joiner — "all the users by default join as the
 * lowest rung unless promoted there itself". The select offers only tiers the deciding admin could
 * grant on Manage users; the server enforces the same ceiling, so this is a mirror rather than the
 * rule.
 */
/**
 * The invite form's "Joins as" picker, holding the one piece of state that form needs.
 *
 * ITS OWN COMPONENT FOR A REASON THAT IS EASY TO GET WRONG. The form above carries
 * `key={editing?.id ?? "new"}` so that switching which row is being corrected re-seeds every box.
 * That remounts the form's SUBTREE — so state declared here resets with it, and state lifted to the
 * page component would not. The failure of the lifted version is quiet: the previous row's tier
 * rides along into the next correction, on a control whose whole job is deciding what somebody may
 * become.
 *
 * THE HIDDEN INPUT IS HOW A CONTROLLED WIDGET REACHES AN UNCONTROLLED FORM. `submit` reads
 * `form.get("role")` out of FormData and is completely unchanged by this; `FormControls`'
 * `MultiNoteField` puts its notes into a form the same way. `Dropdown` takes its accessible name
 * from the enclosing `Field` through context, so nothing here passes `ariaLabel` — see
 * `ui/fieldLabel.tsx` for why that composition matters.
 */
function AdmitTierField({ grantable, initial }: { grantable: UserRole[]; initial: UserRole | "" }) {
  const [role, setRole] = useState<UserRole | "">(initial);
  return (
    <>
      <Dropdown
        value={role}
        onChange={(next) => setRole(next as UserRole | "")}
        options={[
          { value: "", label: "Default joining tier (lowest rung)" },
          ...grantable.map((option) => ({ value: option, label: roleLabel(option) }))
        ]}
      />
      <input type="hidden" name="role" value={role} />
    </>
  );
}

function QueueRow({
  entry,
  grantable,
  onApprove,
  onReject
}: {
  entry: AccessRosterEntry;
  grantable: UserRole[];
  onApprove: (role: UserRole | "") => void;
  onReject: () => void;
}) {
  const [role, setRole] = useState<UserRole | "">("");

  return (
    <li className="flex flex-wrap items-start gap-3 px-4 py-3">
      <div className="min-w-0 flex-1">
        <div className="truncate font-medium text-ink-900">{entry.email}</div>
        <div className="mt-0.5 text-xs text-ink-500">
          {/* WHEN they asked and HOW MANY TIMES, both, because the pair is the whole signal an admin
              has. One attempt three weeks ago is somebody who gave up; eleven attempts today is
              somebody standing in a courtyard unable to work. */}
          {entry.requestedAt ? `Asked ${formatDateTime(entry.requestedAt)}` : "Asked at an unrecorded time"}
          {" · "}
          {entry.attemptCount} attempt{entry.attemptCount === 1 ? "" : "s"}
          {entry.lastAttemptAt ? `, last ${formatDateTime(entry.lastAttemptAt)}` : ""}
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        {/*
          THE THEMED DROPDOWN, ONE PER QUEUE ROW.

          THE COST OBJECTION THAT KEPT THIS NATIVE DOES NOT HOLD, and it is corrected here rather
          than deleted because it was the stated reason. It read: "this control is rendered once for
          every entry in the queue, which is where a portalled panel per row stops being free."
          `AnchoredPopover` mounts its panel behind `{open ? … : null}` — a CLOSED dropdown is a
          single `<button>`, exactly what a `<select>` costs, and at most one panel exists on the
          page at a time however long the queue is.

          THE NAME IS COMPOSED, NOT REPLACED, WHICH IS WHY THERE IS AN `sr-only` LABEL RATHER THAN AN
          `ariaLabel`. Passing `ariaLabel` would set `aria-label` on the trigger, and that REPLACES
          name-from-content — the control would announce the question and lose the chosen tier,
          which is a regression against the `<select>` this replaced (a native select announces its
          label AND its selected option). A real label element with an id lets `SearchableSelect`
          compose `aria-labelledby="<label id> <trigger id>"`: "The tier … joins at" followed by
          "Joins as Designer". `sr-only` because the queue row has no room for a visible label and
          the trigger's own text already reads as a sentence to a sighted admin.

          `advanceOnSelect={false}`: `focusNextField` walks `data-form-field` elements, and the
          Approve/Refuse buttons beside this are not fields — so advancing would jump focus into the
          NEXT ROW'S dropdown, away from the row the admin is deciding. There is nowhere to advance
          to here; this is a control paired with two buttons, not a step in a form.
        */}
        <span id={`tier-label-${entry.id}`} className="sr-only">
          The tier {entry.email} joins at
        </span>
        <FieldLabelProvider value={`tier-label-${entry.id}`}>
          <Dropdown
            value={role}
            onChange={(next) => setRole(next as UserRole | "")}
            options={[
              // FIRST, and it is the platform's documented default — the lowest rung. A new joiner
              // is promoted deliberately, on Manage users, rather than by whoever happened to work
              // the queue that morning.
              { value: "", label: "Joins at the default tier" },
              ...grantable.map((option) => ({ value: option, label: `Joins as ${roleLabel(option)}` }))
            ]}
            advanceOnSelect={false}
            className="h-9 min-w-56 py-0 text-sm"
          />
        </FieldLabelProvider>
        <button type="button" className={rowAction("positive")} onClick={() => onApprove(role)}>
          <UserCheck className="h-3.5 w-3.5" aria-hidden />
          Approve
        </button>
        <button type="button" className={rowAction("danger")} onClick={onReject}>
          <UserX className="h-3.5 w-3.5" aria-hidden />
          Refuse
        </button>
      </div>
    </li>
  );
}

/**
 * What this entry means for its holder, as a chip.
 *
 * COLOUR NEVER CARRIES THE MEANING ON ITS OWN — every state is worded — and the two that end in a
 * refusal carry their date, because "when did this person lose access" is the question the row is
 * usually opened to answer. `admitRole` rides along on the admitted ones so an admin can see at a
 * glance that somebody was let in at a tier they did not intend.
 */
function StandingChip({ entry }: { entry: AccessRosterEntry }) {
  if (entry.status === "ACTIVE") {
    return (
      <span className="inline-flex flex-col gap-0.5">
        <span className="inline-flex w-fit items-center gap-1.5 rounded-full border border-success-600/25 bg-success-100 px-2.5 py-1 text-xs font-medium text-success-600">
          <BadgeCheck className="h-3.5 w-3.5" aria-hidden />
          May sign in
        </span>
        <span className="text-xs text-ink-500">
          {entry.admitRole ? `as ${roleLabel(entry.admitRole)}` : "at the default joining tier"}
        </span>
      </span>
    );
  }
  if (entry.status === "PENDING") {
    return (
      <span className="inline-flex w-fit items-center gap-1.5 rounded-full border border-amber-500/30 bg-amber-100 px-2.5 py-1 text-xs font-medium text-amber-800">
        <Clock className="h-3.5 w-3.5" aria-hidden />
        Waiting for a decision
      </span>
    );
  }
  return (
    <span className="inline-flex flex-col gap-0.5">
      <span className="inline-flex w-fit items-center gap-1.5 rounded-full border border-error-600/25 bg-error-100 px-2.5 py-1 text-xs font-medium text-error-600">
        {entry.status === "REJECTED" ? "Refused" : "Suspended"}
      </span>
      <span className="text-xs text-ink-500">
        {entry.decidedAt ? `since ${formatDate(entry.decidedAt)}` : "date not recorded"}
      </span>
    </span>
  );
}
