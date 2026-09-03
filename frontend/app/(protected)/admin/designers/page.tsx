"use client";

/**
 * The designer roster — the list that decides who may sign in at all.
 *
 * THIS IS NOT A LIST OF ACCOUNTS AND IT IS NOT `User.role`. A roster row is the institution's
 * statement that an individual is empanelled; the role column says what somebody may DO once they
 * are in. The two are kept apart on purpose, and the reason is on the backend service: revoking
 * access by DEMOTING an account is retroactive, because the role is read when deciding who may
 * review whose work, so a demotion silently rewrites the standing of every workshop that designer
 * ever ran. Suspending a roster row ends their sessions and leaves two years of authorship exactly
 * as it was.
 *
 * A ROW USUALLY EXISTS BEFORE THE ACCOUNT DOES, which is the point of empanelling by email: the
 * admin adds the address, and the account provisions itself the first time that person signs in
 * through Google, at which moment `firstSeenAt` is stamped. That column is the one thing this
 * screen exists to show — an invitation that never arrived looks exactly like one that was ignored,
 * and without it an admin has no way in April to tell which of five designers added in March ever
 * opened the app.
 *
 * THERE IS NO DELETE, ANYWHERE ON THIS PAGE, and there must never be one. The roster is the RECORD
 * that somebody was recognised, and that record outlives their access: an audit two years from now
 * asks who was empanelled under which programme, and a deleted row answers "nobody". `DELETE` on
 * the API is a suspension that answers 200 with the suspended row, so the row stays on screen —
 * struck through, dated, and one click from being restored.
 *
 * SUSPENDED ROWS ARE LISTED BY DEFAULT. An admin arrives here because a designer says they cannot
 * log in, and the row refusing them is the one they need to SEE. Hiding it leaves them re-adding an
 * address the unique index then rejects, with the explanation nowhere on screen.
 *
 * ── THAT SENTENCE SURVIVED THE FILTER ROW, AND THE FILTERS ARE SHAPED AROUND KEEPING IT TRUE ─────
 *
 * `RosterFilterBar` opens on `emptyRosterFilters("designer")` — every control at its empty state,
 * which narrows NOTHING — so the first page an admin sees still holds every suspended row, exactly
 * as it did when the only control here was a two-row standing dropdown. Three consequences, and not
 * one of them is decoration:
 *
 *   - **There is no "hide suspended" toggle and there must never be one.** It would be a second
 *     spelling of choosing "Only those suspended may not sign in" from the standing filter, and a
 *     control with two spellings for one state cannot tell a default apart from a deliberate
 *     choice — on the one screen whose whole default exists to show a refusal.
 *   - **Standing is a FILTER, not a default.** Empty means BOTH, by absence: the request carries no
 *     `standing` key at all rather than one naming every value, so "I did not choose" and "I chose
 *     everything" are different requests and a link can carry the difference.
 *   - **A narrowed roster is a URL, so it can arrive from somebody else's paste.** The address bar
 *     is written back on every change and read once on arrival. That is why "Clear every filter" is
 *     on screen whenever anything is set: for the admin who did not do the filtering, the way back
 *     to the widest list has to be one visible click, or the paragraph above has stopped being true
 *     for the person who most needs it.
 *
 * ── NOTHING ON THIS PAGE FILTERS OR SORTS A FETCHED PAGE ────────────────────────────────────────
 *
 * Every narrowing and every order is a query parameter. There is **no `.filter()` and no `.sort()`
 * over `data.items` anywhere in this file**, and there must never be one: page one holds twenty of
 * four hundred rows, so a box that only sifted those twenty would answer "no such designer" about
 * somebody who is simply on page two — a claim about the institution's roster made from a claim
 * about a page. `rosterQueryParams` builds the whole request, and it is built inside `load()` on
 * every read rather than once, so "Last 30 days" resolves against the clock at REQUEST time and a
 * screen left open overnight does not keep asking about yesterday.
 *
 * ── SOME ROWS HERE WERE NOT MADE BY A PERSON ────────────────────────────────────────────────────
 *
 * Admitting somebody as a designer on the platform allow-list now empanels them here too, so rows
 * appear on this screen that no colleague added. That is the fix for the failure this screen was
 * used to repair by hand: an address could be ACTIVE on the allow-list, have no row here at all,
 * and be told at the sign-in page that their designer access had been *suspended* — referring to an
 * empanelment that had never been granted, on a screen showing no row to explain it. Those derived
 * rows carry a note saying so, and the **Added** column marks them, because "who empanelled this
 * person" is a question an admin asks of this screen and `addedById` cannot answer it: it is NULL
 * for a derived row AND for a row whose adding admin's account has since been deleted. See
 * `isDerivedEmpanelment` in `./rosterQuery` for why the note is the field that can.
 */

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { BadgeCheck, MailPlus, RotateCcw, ShieldCheck } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { PageHeader } from "@/components/PageHeader";
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

const PAGE_SIZE = 20;

/** How many accounts `GET /designers/directory` will return before it stops. Server-side `take`. */
const DIRECTORY_CAP = 500;

/** Where the address bar is written back to. One literal, used by the link writer. */
const ROSTER_PATH = "/admin/designers";

/**
 * §3.5's **could-not-be-listed** sentence, worded for this list.
 *
 * The read failed and this client is online, which is a different fact from an empty roster with a
 * different next move — and printing "Nobody has been empanelled yet" over a request that never
 * answered is a claim about the institution made from a failure, which is the defect §3.5 exists to
 * name. It says what the screen is NOT showing, and it says the roster itself is untouched, because
 * the first thought an admin has when this list goes blank is that something has been deleted.
 */
const ROSTER_READ_FAILED =
  "The roster could not be loaded, so this is not showing who is empanelled — it is not a claim that nobody is. Nothing on the server has changed. The message above is what the request actually answered.";

/** §3.5's **genuinely-empty, unscoped** sentence: a statement about the repository, whose next move is to create one. */
const NOBODY_EMPANELLED =
  "Nobody has been empanelled yet. Add the first designer above — the address is all that is needed, and their account will create itself when they sign in.";

/**
 * The filters excluded everything. NOT the same claim as either sentence above.
 *
 * It says where the answer came from, because that is the half a reader cannot see: the filters went
 * into the request, so this is an answer about every empanelment there has ever been rather than
 * about the twenty rows this page had loaded. Without that clause the sentence is indistinguishable
 * from the one a client-side box would print, which is the sentence that is not to be trusted.
 */
const NARROWED_TO_NOTHING =
  "No entry matches the filters set above. They are applied on the server, over the whole roster and not only the rows this page had loaded, so this is an answer about every empanelment there has ever been. Clear every filter to see everybody again, suspended entries included.";

/**
 * The institution vocabulary could not be read — §3.5's could-not-be-listed, one control down.
 *
 * WHY THIS IS DRAWN ON THE PAGE RATHER THAN LEFT TO THE PICKER'S OWN `emptyLabel`. The picker is
 * never actually empty: `institutionOptions` always appends the reserved "No institution recorded"
 * row, so a failed read renders a panel holding exactly that one option — which reads as *"one
 * institution value exists and it is 'none'"*. That is absence presented as a fact about the
 * repository, the bug class this whole cluster is about, and no sentence inside the panel would be
 * reached to correct it. So the page says it, next to the other read this screen makes and cannot
 * complete. `institutionsEmptyLabel` is passed to the bar as well, for the day the panel can be
 * genuinely empty.
 */
const INSTITUTIONS_READ_FAILED =
  "The list of institutions could not be loaded, so the Institution filter above is not offering what exists — an institution missing from it has not been ruled out. Typing the institution's name into the search box still works: that runs on the server, over the whole roster.";

/** The same fact, for the picker's own panel. See {@link INSTITUTIONS_READ_FAILED}. */
const INSTITUTIONS_EMPTY_FAILED =
  "The institution list could not be loaded, so this is not showing what exists. Type the institution's name into the search box above instead — it is searched on the server.";

/** §3.5's genuinely-empty-unscoped, for the institution picker: a claim about the roster, not about a read. */
const INSTITUTIONS_EMPTY_NONE =
  "No institution has been recorded on any roster entry yet, so there is nothing to filter by here.";

export default function DesignerRosterPage() {
  return (
    // SUSPENSE IS MANDATORY, NOT DEFENSIVE. The body reads `useSearchParams` — a filtered roster is
    // a link an admin pastes to a colleague, so the URL has to be readable on arrival — and in
    // Next 16 that hook suspends, so a page reading it without a boundary opts its whole route out
    // of static rendering with a build-time warning.
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

  /**
   * THE URL SEEDS THE FILTERS ONCE; from then on this page owns the address bar.
   *
   * Read in a `useState` initialiser rather than in an effect, so the very first request already
   * carries the pasted filters: seeding afterwards would fire one unfiltered read, paint the whole
   * roster, and then replace it — which on this screen means briefly showing every empanelled
   * address to somebody who was sent a link to one row.
   */
  const [filters, setFilters] = useState<RosterFilters>(() =>
    rosterFiltersFromSearchParams("designer", searchParams)
  );
  const [data, setData] = useState<DesignerRosterPage | null>(null);
  /**
   * The last roster read FAILED. Kept apart from `error`, which is shared with the add/edit form:
   * a save that 409s must not make the list below claim it could not be read.
   */
  const [listFailed, setListFailed] = useState(false);
  /** The search box holds a term the server has not been asked about yet. Fed by the filter bar. */
  const [searchPending, setSearchPending] = useState(false);
  const [page, setPage] = useState(1);
  const [editing, setEditing] = useState<DesignerRosterEntry | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  /** Lower-cased email -> the id of the account that signed up under it, for "Open profile". */
  const [accounts, setAccounts] = useState<Map<string, string>>(new Map());
  const [directoryCapped, setDirectoryCapped] = useState(false);
  /** The served institution vocabulary — from `GET /designers/roster/institutions`, never from `rows`. */
  const [institutions, setInstitutions] = useState<string[]>([]);
  const [institutionsTruncated, setInstitutionsTruncated] = useState<boolean | undefined>(undefined);
  const [institutionsLoading, setInstitutionsLoading] = useState(true);
  const [institutionsFailed, setInstitutionsFailed] = useState(false);
  const formRef = useRef<HTMLFormElement | null>(null);

  /**
   * List pages count fetch generations rather than aborting: `apiFetch` takes no signal, and what
   * matters is IGNORING the late answer. Without this a typed search whose first response arrives
   * after the second overwrites the newer list with older rows, and the screen shows results for a
   * query nobody can see any more.
   */
  const generation = useRef(0);
  /** The institution vocabulary's own counter — a second read, a second race. */
  const institutionGeneration = useRef(0);

  /**
   * EVERY FILTER, SORT AND SEARCH CHANGE COMES THROUGH HERE, AND EVERY ONE OF THEM RESETS THE PAGER.
   *
   * Unconditionally, and that is the whole reason this is one function rather than a `setFilters`
   * passed straight to the bar. A narrowed or re-ordered list has different rows at every offset, so
   * staying on page 3 lands the reader somewhere arbitrary in a list they have just changed — and on
   * a list that got shorter, past the end of it, which draws the "nothing here" empty state over a
   * roster that is not empty. `RosterFilterBar` and `SortableTh` both say so on the prop; this is
   * the one place it has to be true.
   *
   * Stable (`[]`), because `RosterFilterBar` feeds it to an effect and a new identity every render
   * would re-run that effect on every keystroke.
   */
  const applyFilters = useCallback((next: RosterFilters) => {
    setFilters(next);
    setPage(1);
  }, []);

  /** Built once and reused by every sortable header, so no two of them can disagree about the state. */
  const sortControl: RosterSortControl = { kind: "designer", filters, onChange: applyFilters };

  /**
   * The address bar is this page's OUTPUT from the first change on.
   *
   * `history.replaceState` rather than the router: this records where the admin already is, it does
   * not navigate — a navigation would remount the page and take the other filters, the pager and the
   * half-typed add-a-designer form with it. `rosterLinkParams` carries the date PRESET and never the
   * resolved instants, so "Last 30 days" pasted to a colleague tomorrow means *their* last 30 days
   * rather than a fixed fortnight-old window still labelled "Last 30 days".
   *
   * The page number is deliberately not in the link. A filtered roster pasted to a colleague should
   * open at the top of that filtered list; page 3 of somebody else's screen is not a fact about the
   * roster, and a link that restored it would land on an empty page the moment one row was suspended.
   */
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
        // BUILT HERE, INSIDE THE READ, AND NEVER CACHED — the date presets resolve to concrete
        // instants at this moment. A params object built once at mount would keep asking about the
        // day the screen was opened, and nothing on screen would say so.
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
      // `data` is deliberately left standing on a failed refresh: replacing a roster the admin can
      // still read with "nobody is empanelled" is indistinguishable from an empty institution. The
      // first read is the case that has nothing to leave standing, and `listFailed` is what lets the
      // empty state below say "could not be read" instead of "Loading…" forever.
    }
  }, [filters, page, permitted]);

  useEffect(() => {
    load();
  }, [load]);

  /**
   * The institution vocabulary behind the Institution filter.
   *
   * FROM THE SERVER AND NOT FROM `rows`. A picker assembled from the twenty rows on screen can only
   * offer the institutions those twenty rows carried, so an admin filtering for one that is two
   * pages down finds nothing and reads it as "nobody is from there" — the server-side-filtering rule
   * failing inside a control that looks like it is working.
   *
   * Re-read after a save, because adding or correcting an entry is exactly how a new institution
   * comes into existence, and a vocabulary that could not offer the institution the admin typed one
   * minute ago is a filter that looks broken at the moment it is first reached for.
   */
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
      // Emptied rather than left standing, and the failure is SAID: a stale vocabulary would let an
      // admin tick an institution and be told nobody is from there, which is a wrong answer wearing
      // a working control. The roster itself is unaffected — this read feeds one picker.
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

  /**
   * Which roster emails have an account behind them, so a row can offer that person's profile.
   *
   * The roster is keyed by email and carries no user id — it usually predates the account — so this
   * is the only way to get from a row to a profile. Best effort: a failure leaves the action off the
   * rows rather than emptying the page, and the cap is reported rather than hidden, because a
   * missing link on a row whose account exists is exactly the kind of silence that reads as "this
   * designer never signed up".
   */
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
        /* no profile links this session; the roster itself is unaffected */
      });
    return () => {
      cancelled = true;
    };
  }, [permitted]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // React nulls `event.currentTarget` across an await, so the FormData is read before any async
    // work — after the first `await` it is null and every field arrives empty.
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
          // `email` travels only when it is non-blank and the three descriptive columns carry an
          // explicit null when emptied — see `rosterUpdateBody`. `isActive` is deliberately absent:
          // correcting a spelling must never be able to change whether somebody can sign in.
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
          `${created.email} is on the roster. They can sign in with Google using that address, and the account will be created as a Designer the first time they do.`
        );
      }
      formElement.reset();
      await load();
      // The institution typed above may be one this screen has never seen. See `loadInstitutions`.
      await loadInstitutions();
    } catch (err) {
      // A 409 carries the sentence that matters — it names the existing row, says whether it is
      // suspended, and says that restoring is a PATCH rather than a second add — so it is shown
      // verbatim. The search box is then pointed at the address, because the row it is talking
      // about is on this very page and the admin should be looking at it rather than reading about
      // it. That is the whole fix for "an admin re-adding an email the unique index rejects, with
      // no explanation visible anywhere in the UI".
      //
      // EVERY OTHER FILTER IS CLEARED IN THE SAME MOVE, not just the standing one. The row that
      // 409'd may be suspended, may be at a tier the role filter excludes, may carry an institution
      // that is not ticked — and pointing the search at an address the filters then hide would
      // answer "no such entry" about the very row the error is describing, which is worse than the
      // silence this branch was written to end. `applyFilters` resets the pager too.
      setError(err instanceof Error ? err.message : "Unable to save this roster entry");
      if (err instanceof ApiError && err.status === 409) {
        // `clearRosterFilters` rather than a shape written out here: it keeps the ORDER (an order
        // narrows nothing and hides nobody) and it reads the default date column off the picker's
        // own first row, so a second copy of the empty state cannot drift from the one the "Clear
        // every filter" button produces.
        applyFilters({ ...clearRosterFilters("designer", filters), search: email });
      }
    } finally {
      setSaving(false);
    }
  }

  async function suspend(entry: DesignerRosterEntry) {
    const ok = await confirm({
      title: `Suspend ${entry.fullName || entry.email}?`,
      body: "They will be refused at their next request and at every sign-in after it, until the entry is restored.",
      // The tone is danger because access is being taken away, but the note has to correct the word
      // the tone implies: nothing is deleted here, and an admin who believes otherwise will hesitate
      // over something that is one click from being undone.
      note:
        "The roster entry is kept — this records that they were empanelled, and that record outlives their access. Nothing they have recorded is touched, and Restore gives the access back.",
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
        ? `They were suspended on ${formatDate(entry.revokedAt)}. Restoring clears that date, because a row cannot be both active and revoked.`
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
    // The form is above the table and a long roster puts it off screen, so the edit that was just
    // asked for would happen somewhere the admin cannot see. Deferred one frame because the form
    // remounts on its `key` and only then has its final height and position.
    requestAnimationFrame(() => formRef.current?.scrollIntoView({ block: "start", behavior: "smooth" }));
  }

  const header = (
    <PageHeader
      title="Designer roster"
      description="Who the institution recognises as a designer. An address on this list may sign in; one that is suspended may not, and the entry stays either way."
      icon={<BadgeCheck className="h-5 w-5" aria-hidden />}
    />
  );

  /**
   * The route is gated twice over — `ROUTE_GUARDS` refuses it above this page and
   * `require_designer_roster_manager` refuses every request it would make — but the panel is still
   * rendered here, because a client-side guard that only hides a nav item is not a guard, and
   * somebody arriving on a stale bookmark deserves a sentence rather than an empty screen.
   */
  if (!permitted) {
    return (
      <>
        {header}
        {/*
          ONE LINE — 2026-09-03, the same rewrite as `/admin/access`. The body argued the case for
          the gate ("a list of named individuals and their institutional standing, so reading it is
          admin work as much as writing it is") before stating the refusal, and then stated it
          twice. The argument is `lib/permissions.ts`'s, beside `canManageDesignerRoster`, where it
          is written once for every surface rather than re-typed onto each refusal screen.
        */}
        <RestrictedPanel
          title="Admin access required"
          body={
            // `roleLabel` answers "" for an absent user. AppShell never renders a protected page
            // without one, but a sentence that reads "  does not open this" is a worse way to find
            // that out than a fallback nobody will ever see.
            `${roleLabel(user?.role) || "Your tier"} does not open this. Ask an admin or the master admin.`
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
    // The same predicate that decides whether "Clear every filter" is on screen, deliberately: the
    // sentence tells the reader to clear the filters, so it must not be able to say that while the
    // button is absent. It reads the CONTROLS rather than the wire, so a period set to "Custom
    // range" with no dates typed counts — that state narrows nothing, but it is visibly set and the
    // reader must be able to put it back.
    narrowed: hasActiveRosterFilters("designer", filters),
    pending: searchPending
  });

  return (
    <>
      {header}

      {/*
        ADDED WHEN THE PLATFORM ALLOW-LIST SHIPPED, because this page's own header sentence ("An
        address on this list may sign in") became half of the truth that morning: a designer now
        needs an ACTIVE row HERE *and* an admission on the platform list, and an admin looking for
        why somebody cannot sign in will otherwise read a perfectly healthy roster row and conclude
        the product is broken. Empanelling still admits automatically — the allow-list accepts an
        ACTIVE empanelment — so this is a pointer rather than a second thing to do.

        IT NOW RUNS BOTH WAYS, which is why the second sentence is here: an admission on that screen
        empanels somebody on this one, and those rows say so in the Added column below.
      */}
      <p className="mb-4 text-sm leading-6 text-ink-500">
        This roster is the <em>designer</em> half of signing in. Whether an address may reach the application at all is
        decided on{" "}
        <Link href="/admin/access" className="font-medium text-purple-700 hover:underline">
          Who may sign in
        </Link>
        , where people waiting for approval also queue up. Empanelling somebody here admits them there too, and admitting
        somebody there as a designer empanels them here, so this stays one action either way round.
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
        // Remounted when the row being edited changes, so every `defaultValue` is re-seeded — an
        // uncontrolled form otherwise keeps the previous row's text in its boxes.
        key={editing?.id ?? "new"}
        className="panel mb-5 grid gap-4 p-4"
      >
        <div>
          <h2 className="font-display text-lg font-bold text-ink-900">
            {editing ? `Correct the entry for ${editing.email}` : "Add a designer to the roster"}
          </h2>
          <p className="mt-1 text-sm leading-6 text-ink-muted">
            {editing
              ? "The name, institution and note are the admin’s own record of whom they empanelled and why. Changing them cannot change whether this person may sign in — use Suspend or Restore for that."
              : "The email address is the only thing needed, and no account has to exist yet: that is how somebody is empanelled before they have ever opened the app. The first time they sign in with Google under this address, the account is created and promoted to Designer."}
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
              // Lower-cased on the server before it is stored and before it is compared to the
              // address signing in, so capitals here are harmless — see `normalise_email`.
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
        <div className="flex flex-wrap gap-2">
          <button className="field-button" disabled={saving}>
            <MailPlus className="h-4 w-4" aria-hidden />
            {saving ? "Saving…" : editing ? "Update entry" : "Add to roster"}
          </button>
          {editing ? (
            <button type="button" className="field-button-secondary" onClick={() => setEditing(null)}>
              Cancel
            </button>
          ) : null}
        </div>
      </form>

      {/*
        THE ONE PLACE THIS LIST IS NARROWED, and every one of its controls sends a query parameter.
        `roleMatchTruncated` is handed to the bar rather than drawn beside the table on purpose: the
        cut is caused by the role picker and is invisible everywhere else on the screen, so it
        belongs under the control that caused it — and one sentence in two places is how a reader
        learns that neither means much.

        No `roleMatchLimit` is passed, and that is a decision rather than an omission. The wire
        carries a boolean; `ROLE_MATCH_READ_LIMIT` is a server constant this client has never read,
        and never print a cap you did not read — a stated cap that is not the enforced cap is worse
        than no number at all. `roleMatchCutNotice` states the fact without one.
      */}
      <RosterFilterBar
        kind="designer"
        filters={filters}
        onChange={applyFilters}
        institutions={institutions}
        institutionsTruncated={institutionsTruncated}
        institutionsLoading={institutionsLoading}
        // Unset WHILE THE READ IS IN FLIGHT, so the primitive's neutral "No options" stands: this
        // page cannot yet tell "none recorded" from "the read failed", and either sentence printed
        // over a request that has not answered is a claim made from nothing.
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
          The account list this page reads to match a roster entry to a person stops at {DIRECTORY_CAP} accounts, so “Open
          profile” may be missing from a row whose account does exist. The roster itself is complete and paged below.
        </p>
      ) : null}

      {/*
        MOUNTED BEFORE IT HAS ANYTHING TO SAY, which is the whole reason it is a separate element
        from the `EmptyState` that shows the same sentence. Assistive technology only announces
        mutations inside a region that ALREADY EXISTED when the page settled; a region created
        together with its first sentence announces nothing, which is the bug `EntityForm`'s cap
        notice shipped. The sentence is on screen inside the panel below, so this copy is
        `sr-only` — it exists to be spoken, not to be read twice.
      */}
      <div {...CUT_NOTICE_LIVE_REGION} className="sr-only">
        {data !== null && rows.length === 0 ? empty.body : ""}
      </div>

      <section className="panel overflow-hidden">
        {data === null ? (
          listFailed ? (
            // A first read that FAILED is not a read that is still running. Before this branch
            // existed the screen said "Loading the roster…" for ever, which is the one message that
            // makes an admin sit and wait instead of retrying or telling somebody.
            <div className="p-4">
              <EmptyState title="The roster could not be loaded" body={ROSTER_READ_FAILED} />
            </div>
          ) : (
            // null is "still loading" and [] is "genuinely none" — saying "nobody is empanelled"
            // during a fetch is both wrong and alarming on this particular screen.
            <div className="p-4 text-sm text-ink-700">Loading the roster…</div>
          )
        ) : rows.length === 0 ? (
          searchPending ? (
            // A keystroke that has not been sent yet. `searchCutNotice` returns "" while a term is
            // pending precisely so the page owns this line: "No designers match “ravi”" printed over
            // a request that has not been made is a claim about the roster drawn from nothing.
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
                  {/*
                    FOUR OF THIS ROSTER'S SIX SORTS HAVE A HEADER, and the two that do not are named
                    here rather than left to be noticed.

                    `email` shares the Designer cell with the name, and a second header over one cell
                    would be a column the reader cannot see; the address is reachable by the search
                    box, which is OR-ed over `email` on the server. `revoked` belongs to the "May
                    sign in" cell, whose chip already carries the revocation date and whose own
                    comment explains why the two are drawn together — a header reading "Sort by May
                    sign in, newest first" would describe neither.

                    Both remain valid on the wire and both survive a pasted link, so a colleague's
                    `?sort=email` still orders this list correctly; clicking any header below moves
                    off it. `added` has a header for a reason beyond usefulness: it is the DEFAULT
                    sort, and without one the table would render `aria-sort="none"` on every column
                    while being ordered by one of them.
                  */}
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
                    <tr key={entry.id}>
                      <td className="px-4 py-3">
                        <div className="font-medium text-ink-900">
                          {/* The name is whatever the admin typed — there may be no account to read
                              one from — so its absence is stated rather than left as an empty cell. */}
                          {entry.fullName || <span className="text-ink-500">Name not recorded</span>}
                        </div>
                        <div className="text-xs text-ink-500">{entry.email}</div>
                      </td>
                      <td className="px-4 py-3 text-ink-700">{entry.institution ?? "—"}</td>
                      <td className="px-4 py-3">
                        <StandingChip entry={entry} />
                      </td>
                      <td className="px-4 py-3 text-ink-700">
                        {/* Never "never": a null firstSeenAt is a statement about an invitation, not
                            about a person, and the two read very differently to an admin chasing it. */}
                        {rosterInvitationLabel(entry)}
                        {entry.firstSeenAt ? (
                          <span className="block text-xs text-ink-500">{formatDateTime(entry.firstSeenAt)}</span>
                        ) : null}
                      </td>
                      <td className="px-4 py-3 text-ink-700">
                        {entry.createdAt ? formatDate(entry.createdAt) : "—"}
                        {derived ? (
                          // WHY THIS IS A CHIP AND NOT LEFT TO THE NOTE COLUMN. The note says it in
                          // full and is shown verbatim there, but it is three lines of prose in a
                          // clamped cell — so scanning a page of rows for "which of these did a
                          // colleague actually decide on" means reading twenty notes. The question
                          // this column answers is "who added this row", and for these rows the
                          // honest answer is "nobody did".
                          <span className="mt-1 flex w-fit items-center gap-1 rounded-full border border-line-200 bg-surface-50 px-2 py-0.5 text-[11px] font-medium text-ink-700">
                            <ShieldCheck className="h-3 w-3 shrink-0 text-purple-700" aria-hidden />
                            Automatically, from the allow-list
                          </span>
                        ) : null}
                      </td>
                      <td className="max-w-xs px-4 py-3 text-ink-700">
                        {entry.notes ? <span className="line-clamp-3 whitespace-pre-line">{entry.notes}</span> : "—"}
                      </td>
                      <td className="px-4 py-3 text-right">
                        <RowActions>
                          <button type="button" className={rowAction("edit")} onClick={() => startEdit(entry)}>
                            Edit
                          </button>
                          {accountId ? (
                            <Link className={rowAction("neutral")} href={`/designers/${accountId}/profile`}>
                              Open profile
                            </Link>
                          ) : null}
                          {entry.isActive ? (
                            // Never labelled "Delete". The verb has to say what happens, and what
                            // happens is that the row stays.
                            <button type="button" className={rowAction("danger")} onClick={() => suspend(entry)}>
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
                    </tr>
                  );
                })}
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
 * WHICH "there is nothing here" THIS IS — and the three must never collapse into one.
 *
 * `searchCutNotice` owns the wording of two of them, so this function chooses a heading and hands
 * the third — the caller's `emptyLabel` — down. The distinctions, in the order they are tested:
 *
 *  1. **Rows exist under these filters and none of them are on this page.** Reachable here: the
 *     pager can be past the end of a list that got shorter. `cappedListNotice`'s first arm owns
 *     those words and names no control, because none of them helps.
 *  2. **A term was searched and nothing matched it.** A statement about the WHOLE roster, because
 *     the term went into the request — and the sentence says so, which is the half a reader cannot
 *     otherwise tell apart from a box that only sifted the twenty rows on screen.
 *  3. **Some other filter excluded everything**, or **nobody has ever been empanelled**. Two claims
 *     with two different next moves: one is "clear the filters", the other is "add somebody". A
 *     screen that collapses them tells an admin the institution has no designers because a tier was
 *     ticked.
 *
 * The read-failed case is NOT here. It is a claim about a request rather than about the roster, it
 * has no `total` to reason about, and mixing it in is exactly how "no records" gets printed over a
 * fetch that never answered — see `ROSTER_READ_FAILED` and the branch that draws it.
 */
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
    // Reached only when nothing was typed — `searchCutNotice` words the term arms itself — so this
    // is the choice between "the other filters excluded everybody" and "there is nobody".
    emptyLabel: args.narrowed ? NARROWED_TO_NOTHING : NOBODY_EMPANELLED
  });
  if (args.loaded === 0 && args.total > 0) {
    return { title: "This page of the roster is empty", body };
  }
  if (term) return { title: "No roster entries match this search", body };
  if (args.narrowed) return { title: "No roster entries match these filters", body };
  return { title: "Nobody has been empanelled yet", body };
}

/**
 * Whether this entry admits its holder, as a chip.
 *
 * Colour never carries the meaning on its own: each state is worded, and the suspended one carries
 * its date, because "when did this designer lose access" is the question the row is usually opened
 * to answer. A suspended row with no date would read as a bug in the screen rather than as a state,
 * which is exactly why the API stamps `revokedAt` even on a row created suspended.
 */
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
      <span className="inline-flex w-fit items-center gap-1.5 rounded-full border border-error-600/25 bg-error-100 px-2.5 py-1 text-xs font-medium text-error-600">
        Suspended
      </span>
      <span className="text-xs text-ink-500">
        {entry.revokedAt ? `since ${formatDate(entry.revokedAt)}` : "date not recorded"}
      </span>
    </span>
  );
}
