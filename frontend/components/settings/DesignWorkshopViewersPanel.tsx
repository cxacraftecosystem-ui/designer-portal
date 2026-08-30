"use client";

import { DraftingCompass, Save } from "lucide-react";
import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";

import { SearchInput } from "@/components/SearchInput";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown, MultiSelectDropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { useToast } from "@/components/ui/Toast";
import { ApiError, listResource } from "@/lib/api";
import { listDesignWorkshops, type DwSummary } from "@/lib/designWorkshops";
import {
  designWorkshopType,
  eligibleViewerNotice,
  ELIGIBLE_VIEWER_SEARCH_MAX,
  listDesignWorkshopViewers,
  listEligibleDesignWorkshopViewers,
  putDesignWorkshopViewers,
  viewerAdministrationMissing,
  type DwEligibleViewer,
  type DwViewer
} from "@/lib/designWorkshopViewers";
import { formatDateTime } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import { roleLabel } from "@/lib/permissions";
import { WORKSHOP_TYPE_LABELS, type Workshop, type WorkshopType } from "@/lib/types";
import {
  designWorkshopOptions,
  deviceLooksOffline,
  WORKSHOP_OPTION_PAGE_SIZE,
  workshopCutSentence,
  workshopEmptyLabel,
  workshopListNotice,
  type WorkshopListState,
  type WorkshopListVoice
} from "@/lib/workshopOptions";

/**
 * Who may open one design & prototype workshop, administered beside the workshop-access console.
 *
 * THE FAILURE THIS PREVENTS is spelled out in full at the top of `lib/designWorkshopViewers.ts` and
 * is worth the one-line summary here: `load_workshop_or_404` 404s a design workshop for everybody
 * but the account that pressed "create", so the second designer on a fortnight of fieldwork cannot
 * open the record at all and there is no handover when a designer leaves mid-season.
 *
 * It sits on /workshop-access/manage rather than on its own route deliberately. That page is
 * already the one place an admin goes to answer "who may work on what", it is already in
 * ROUTE_REDIRECTS (below-admin is sent to the request page) and in ADMIN_CHROME_ROUTES (an admin
 * with admin view off gets the panel, not a redirect), and both of those gates are the ones this
 * panel needs. A second route would have been a second copy of them to keep in step.
 *
 * FIVE DECISIONS THAT LOOK LIKE DETAIL AND ARE NOT.
 *
 * - **The creator is shown OUTSIDE the picker.** They hold the workshop because they made it, not
 *   because of a row in this list, and the PUT cannot take that away. Putting them in the
 *   multi-select as a ticked-but-disabled option was the first draft and it was wrong: it invites
 *   an admin to try to untick the one person whose access is not on offer here, and a control that
 *   refuses a click without saying why is how somebody concludes the page is broken.
 * - **A current viewer who is no longer ELIGIBLE is still offered, ticked and marked.** The PUT
 *   replaces the whole set, so anybody missing from the picker is silently revoked by the next
 *   Save. A designer whose roster row was suspended last month is exactly that case, and dropping
 *   them from the options would have quietly removed their row the first time an admin added
 *   somebody else.
 * - **Saving is explicit.** Every toggle writing straight through would mean an admin re-picking a
 *   mis-clicked name has already revoked it for however long the round trip takes, and on a rural
 *   link that is seconds. So the picker edits a PENDING set, the panel says what is unsaved, and
 *   one button sends it.
 * - **The type dropdown NARROWS, it does not set.** See {@link TYPE_OPTIONS}. It also stands down
 *   while the workshop box holds a term, with a sentence saying so — the argument is at
 *   `typeNarrowingSuspended`, and the short version is that a local map built from one capped read
 *   of a different table may not narrow an answer the repository gave.
 * - **BOTH SEARCHES ARE THE SERVER'S, and each control has exactly one box.** `eligible-viewers`
 *   answers at most 2000 accounts ordered by name and that cut is being hit on this repository —
 *   see point 4 of `lib/designWorkshopViewers.ts` — so a box that filtered the options already in
 *   the browser would search only the part of the alphabet that fitted and answer "no matches" for a
 *   colleague who is eligible, present and simply sorts late. The workshop picker had the same fault
 *   over one page of `GET /design-workshops` and it is fixed the other way round, because the
 *   primitive can now hand a term out: the PEOPLE box stays a `SearchInput` above a picker with
 *   `searchable={false}` (its options are assembled from three sources and pinned, so the panel's own
 *   box has never been the thing to point at the server), and the WORKSHOP box is the panel's own,
 *   wired through `serverQuery`. See {@link SEARCH_DEBOUNCE_MS}.
 */

/**
 * The workshop-type filter, built from `WORKSHOP_TYPE_LABELS` rather than from strings typed here.
 *
 * THIS DROPDOWN FILTERS; IT DOES NOT SET. A `DesignWorkshop` has no type column of its own — the
 * distinction lives on the `Workshop` row it was started from — so there would be nothing for a
 * "set the type" control on this panel to write, and writing it through to the linked Workshop
 * would be an admin retyping a craft-documentation record's kind from a screen about access.
 *
 * The empty option is first and means EVERYTHING, by absence: the same rule `buildQuery`,
 * `filters.types` and the workshop-scope picker follow, and the reason none of them list every
 * member to mean "all". Listing both types instead would silently exclude any type added to
 * `WorkshopType` after this was written.
 */
const TYPE_OPTIONS: DropdownOption[] = [
  { value: "", label: "Any workshop type" },
  ...(Object.keys(WORKSHOP_TYPE_LABELS) as WorkshopType[]).map((key) => ({
    value: key,
    label: WORKSHOP_TYPE_LABELS[key]
  }))
];

/**
 * How many rows each list asks for.
 *
 * Both are a page, not "everything", and both are checked against the reported total so the panel
 * can say when it is showing less than there is — a selector that quietly stops at a hundred is
 * indistinguishable from a repository with a hundred workshops in it.
 */
/*
  THE DESIGN-WORKSHOP PAGE IS NO LONGER 100, AND THE OLD NUMBER WAS A DEAD BAND RATHER THAN A
  GENEROUS ONE. `SearchableSelect` draws at most `RENDER_CAP` (80) rows, so a hundred-row page put
  twenty workshops into this control that it silently would not draw, under a notice — "Showing the
  100 most recent design workshops of N" — describing a list nobody was looking at. Between 81 and
  100 workshops the panel below said nothing at all while dropping rows. `WORKSHOP_OPTION_PAGE_SIZE`
  is `RENDER_CAP`, reached through `lib/workshopOptions` so that one number governs the fetch and
  the render on every workshop picker in the app.

  The 200 below is untouched: that request feeds the type MAP, not a list of options, so its ceiling
  is about how many workshops can be typed rather than how many can be drawn.
*/
const DESIGN_WORKSHOP_PAGE = WORKSHOP_OPTION_PAGE_SIZE;
const WORKSHOP_PAGE = 200;

/**
 * How long after the last keystroke either search on this panel goes out.
 *
 * 300 ms, the same number and for the same measured reason as `StageReferenceField`: the server
 * matches `name` OR `email` with an `ILIKE '%term%'` that no index can answer, so every keystroke
 * that escapes the debounce is a full scan of `User` — 4157 rows here, up from 3632 the same morning,
 * and only ever more. Shorter and a fast typist fires six scans for one surname; longer and the list
 * visibly lags the box.
 *
 * ONE NUMBER FOR BOTH BOXES, and it is worth saying why rather than leaving two constants to drift.
 * The workshop search reaches `GET /design-workshops?search=`, which is the same shape of query over
 * a smaller table — an unindexable `ILIKE` across `title, craftName, clusterName, workshopCode` — so
 * the measurement that produced this number applies unchanged, and two boxes on one panel that
 * answered at visibly different speeds would read as one of them being broken.
 *
 * Clearing either box does NOT wait: an empty term is the unnarrowed list, which is the one request
 * that is always about to be needed and never about to be superseded by the next letter. It is also
 * the request each effect makes on mount, where a pause before asking for anything at all would be a
 * pause nobody asked for.
 */
const SEARCH_DEBOUNCE_MS = 300;

/*
  THE LOCAL `designWorkshopLabel` IS GONE. It built `title · date`, which was one of six label shapes
  the app shipped for one question and one of THREE copies of this exact function — this panel's, the
  inspectors panel's, and `/design-review`'s, each module-private, each with a comment saying it was
  deliberately not shared and that a fourth caller would be the moment to lift it. There were seven.
  `lib/workshopOptions::designWorkshopOptions` is that home: the label is the title alone, and the
  craft, the cluster and the day it ran go in the `hint`, which `SearchableSelect` searches as well as
  draws.
*/

/** Name a person without ever leaking an id: their name, else their email, else a neutral fallback. */
function personLabel(person: { name?: string | null; email?: string | null } | null | undefined): string {
  return person?.name?.trim() || person?.email?.trim() || "Unknown user";
}

/**
 * The four failures this panel can suffer, told apart in words.
 *
 * `isUnreachable` and not `isTransient`: the latter answers "is it worth retrying" and counts every
 * 5xx as yes, so a repository that ANSWERED and then failed would be reported as a connection
 * problem — which sends an admin to look at their signal and leaves a real fault wearing an offline
 * message. A server that spoke gets its own sentence shown, because `apiFetch` has already unpacked
 * FastAPI's 422 list into a readable one that names the offending field.
 */
function describeFailure(error: unknown, fallback: string): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) {
    return "This device cannot reach the repository, so nothing was sent and nothing has changed. Check the connection and try again.";
  }
  if (error.status === 403) {
    return `The repository refused this. ${error.message} Deciding who may see a design workshop is administration, so it is open to admins and the master admin only.`;
  }
  if (error.status === 422) {
    return `The repository would not accept this. ${error.message}`;
  }
  if (error.status === 404) {
    return `${error.message} This workshop may have been deleted since the list was loaded — reload the page to see the current list.`;
  }
  return error.message || fallback;
}

export function DesignWorkshopViewersPanel({ refreshToken }: { refreshToken?: number }) {
  const { toast } = useToast();

  /**
   * What the design-workshop read answered — three states, and the middle one is the whole point.
   *
   * `DwSummary[] | null` could not tell a read that FAILED from a read that answered NOTHING, and
   * this panel resolved that ambiguity in the wrong direction: the catch below set `[]` and then
   * raised a red banner beside a picker that read "No design workshops of this type" — two claims,
   * one of them about a filter that had not been applied.
   */
  const [workshops, setWorkshops] = useState<WorkshopListState<DwSummary>>({ kind: "loading" });
  /** Was the device reachable when that read FAILED? Captured in the catch, for the reason
   *  `components/forms/DesignWorkshopSelect.tsx` gives at length: it is a fact about the moment the
   *  request died, and re-reading it at render time swaps one of §3.5's sentences for another under
   *  a reader who has not moved. */
  const [workshopsOnline, setWorkshopsOnline] = useState(true);
  /**
   * What the admin has typed to find a WORKSHOP. Sent to the server; never used to filter locally.
   *
   * IT LIVES INSIDE THE PICKER'S OWN BOX, which is the whole of this change. Until the primitive
   * could hand a term out, the only box available to this control filtered the array it was handed
   * — one page of `GET /design-workshops` — so typing the title of a workshop that happened to sit
   * on page two answered "No matches" about a workshop that exists, on an access console, where
   * "there is no such workshop" is a sentence an admin acts on. `serverQuery` points the same box
   * at `?search=`, which matches `title, craftName, clusterName, workshopCode` across the whole
   * table. There is still exactly one box; it simply reaches past the page now.
   */
  const [workshopSearch, setWorkshopSearch] = useState("");
  /** A workshop search is in flight, so an empty panel mid-flight reads as "wait" and never as
   *  "there are none" — R3, in the one state the control genuinely cannot know. */
  const [workshopsSearching, setWorkshopsSearching] = useState(true);
  /**
   * Every design workshop this panel has seen, across every search it has run.
   *
   * NEEDED BECAUSE THE OPTION LIST IS NOW A MOVING WINDOW over the table rather than one fixed page
   * of it, and three separate things on this panel read the CHOSEN workshop's row rather than its
   * id. The load-bearing one is `creatorId`: it is derived from the selected summary, it decides
   * which account is held out of the editable set, and it is a dependency of `loadViewers`. Read
   * off the current answer, an admin who picked a workshop and then typed a colleague's surname
   * into the box below would have watched the selected workshop fall out of the answer, `creatorId`
   * become `""`, and the creator's row turn into an ordinary tickable one that the next Save could
   * delete — the one row this panel is least entitled to touch. Remembering the summary is what
   * makes the search a narrowing of the OPTIONS and not of the administration.
   *
   * State and not a ref, for the reason the people-side `known` map gives: `selectedWorkshop`
   * memoises on it, and a ref mutated inside a fetch is not a dependency anything recomputes from.
   */
  const [knownWorkshops, setKnownWorkshops] = useState<Map<string, DwSummary>>(() => new Map());
  const [typeByWorkshopId, setTypeByWorkshopId] = useState<Map<string, WorkshopType>>(new Map());
  const [typeMapPartial, setTypeMapPartial] = useState(false);

  const [typeFilter, setTypeFilter] = useState("");
  const [workshopId, setWorkshopId] = useState("");

  /** What the admin has typed to find somebody. Sent to the server; never used to filter locally. */
  const [search, setSearch] = useState("");
  const [eligible, setEligible] = useState<DwEligibleViewer[] | null>(null);
  /** The server's own word for "this answer is not the whole eligible set". Rendered, once, when true. */
  const [eligibleTruncated, setEligibleTruncated] = useState(false);
  /** A search is pending or in flight — so a stale list on screen is never read as the answer. */
  const [searching, setSearching] = useState(false);
  /**
   * Every eligible account this panel has seen, across every search it has run.
   *
   * Needed because the option list is now a MOVING window over the table rather than the whole of it.
   * Search "Padma", tick her, clear the box, and she is no longer in the server's answer — but she is
   * still in `selected`, and without a remembered label the pending-grant line would name her
   * "Unknown user" and the picker would draw a ticked row with no name on it. State rather than a ref
   * on purpose: `viewerOptions` memoises on it, and a ref mutated during a fetch is not a dependency
   * anything recomputes from.
   */
  const [known, setKnown] = useState<Map<string, DwEligibleViewer>>(() => new Map());
  /**
   * The routes are being built in parallel with this screen and ship separately, so "the server has
   * not got this yet" is a state the panel has to render rather than a case to assume away. See
   * `viewerAdministrationMissing` for why the id-less endpoint is the honest probe.
   */
  const [featureMissing, setFeatureMissing] = useState(false);

  const [viewers, setViewers] = useState<DwViewer[] | null>(null);
  /** The saved membership, creator excluded — what `selected` is compared against to decide dirty. */
  const [baseline, setBaseline] = useState<string[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  /**
   * Did the server itself report the creator as holding a viewer ROW?
   *
   * If it did, that id has to be re-attached to the PUT: the endpoint replaces the whole set, so a
   * payload built only from the picker would delete a row the server told us about — the one row
   * this panel is least entitled to touch.
   */
  const [creatorHasRow, setCreatorHasRow] = useState(false);

  const [loadError, setLoadError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  /* ── The two lists the selector is built from ───────────────────────────── */

  /**
   * A generation counter rather than a `cancelled` flag, for the reason the people search beside it
   * gives and which only became true of this read when the read became re-runnable: `apiFetch`
   * takes no `AbortSignal`, so two searches can be in flight at once, and a slow answer for "bag"
   * landing after the fast one for "bagru" would draw the wrong list under the typed word — which is
   * precisely the moment an admin picks the first row without reading it.
   */
  const workshopGeneration = useRef(0);

  useEffect(() => {
    const term = workshopSearch.trim();
    const current = workshopGeneration.current + 1;
    workshopGeneration.current = current;
    setWorkshopsSearching(true);
    const timer = window.setTimeout(
      () => {
        listDesignWorkshops({ page: 1, pageSize: DESIGN_WORKSHOP_PAGE, search: term || undefined })
          .then((result) => {
            if (workshopGeneration.current !== current) return;
            setWorkshops({ kind: "ok", rows: result.items, total: result.total });
            // Remembered before anything reads it, so the chosen workshop survives the next answer.
            // See `knownWorkshops` for the row of this panel that would otherwise go missing.
            setKnownWorkshops((previous) => {
              const next = new Map(previous);
              for (const summary of result.items) next.set(summary.id, summary);
              return next;
            });
            setWorkshopsSearching(false);
          })
          .catch(() => {
            if (workshopGeneration.current !== current) return;
            setWorkshopsSearching(false);
            /*
              NOT `setSummaries([])` AND NOT A RED BANNER. The empty array was the failed-list-reads-as-
              empty-list bug in its second-most-damaging place: on an access console, where "there are no
              design workshops" is an answer an admin acts on. The banner is not the remedy either — the
              sentence belongs on the control it is about, in the four words §3.5 gives every workshop
              picker on both clients, and a banner saying one thing above a picker saying another teaches
              a reader that neither is worth reading. `loadError` still carries the other two reads on
              this panel, neither of which has a control of its own to speak through.
            */
            setWorkshopsOnline(!deviceLooksOffline());
            setWorkshops({ kind: "failed" });
          });
      },
      // Clearing the box does NOT wait. An empty term is the unnarrowed list — the one request that
      // is always about to be wanted and never about to be superseded by the next letter — and it is
      // also the request this effect makes on mount, where a 300 ms pause before asking for anything
      // at all would be a pause nobody asked for.
      term ? SEARCH_DEBOUNCE_MS : 0
    );
    return () => window.clearTimeout(timer);
  }, [refreshToken, workshopSearch]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        // Every workshop, not one type at a time: the map has to answer "which type is this one" for
        // both filter values, and two requests would leave the second answer stale whenever an admin
        // switched between them.
        const result = await listResource<Workshop>("/workshops", { pageSize: WORKSHOP_PAGE });
        if (cancelled) return;
        const map = new Map<string, WorkshopType>();
        for (const workshop of result.items) map.set(workshop.id, workshop.workshopType ?? "OTHER");
        setTypeByWorkshopId(map);
        setTypeMapPartial(result.total > result.items.length);
      } catch {
        if (cancelled) return;
        // Deliberately silent, and deliberately NOT an empty map presented as an answer: with no map
        // the type filter cannot honestly narrow anything, so it disables itself below rather than
        // filtering every workshop away and calling that a result.
        setTypeByWorkshopId(new Map());
        setTypeMapPartial(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [refreshToken]);

  /**
   * The eligible accounts — the SERVER'S answer to whatever is in the search box.
   *
   * A generation counter rather than a `cancelled` flag, and the difference matters here in a way it
   * did not before this effect could re-run on every keystroke: `apiFetch` takes no `AbortSignal`, so
   * two searches can be in flight at once and the house convention (`StageReferenceField`, the list
   * pages) is to count them and ignore the late answer. Without it a slow response for "kam" lands
   * after the fast one for "kamla" and the picker shows the wrong list under the typed word — which
   * is precisely when somebody ticks the first row without reading it.
   */
  const eligibleGeneration = useRef(0);

  useEffect(() => {
    const term = search.trim();
    const current = eligibleGeneration.current + 1;
    eligibleGeneration.current = current;
    setSearching(true);
    const timer = window.setTimeout(
      () => {
        listEligibleDesignWorkshopViewers(term)
          .then((result) => {
            if (eligibleGeneration.current !== current) return;
            const users = result.users ?? [];
            setEligible(users);
            // Coerced, not trusted: see `DwEligibleViewerList`. A server that predates the field
            // leaves this `undefined`, and an unknown flag must say nothing rather than cry
            // truncation at a list that is complete.
            setEligibleTruncated(Boolean(result.truncated));
            setKnown((previous) => {
              const next = new Map(previous);
              for (const person of users) next.set(person.id, person);
              return next;
            });
            setFeatureMissing(false);
            setSearching(false);
          })
          .catch((err) => {
            if (eligibleGeneration.current !== current) return;
            setSearching(false);
            setEligibleTruncated(false);
            if (viewerAdministrationMissing(err)) {
              setFeatureMissing(true);
              setEligible([]);
              return;
            }
            setEligible([]);
            setLoadError(describeFailure(err, "Unable to load the designers who may be given access"));
          });
      },
      term ? SEARCH_DEBOUNCE_MS : 0
    );
    return () => window.clearTimeout(timer);
  }, [refreshToken, search]);

  /* ── The chosen workshop's current viewers ──────────────────────────────── */

  /**
   * The chosen workshop's row, read from what this panel HAS SEEN and not from the current answer.
   *
   * It used to be `workshops.rows.find(…)`, which was exactly right while the list was one fixed
   * page and is a defect the moment the box goes to the server: the answer is a moving window, the
   * chosen workshop is very often not in the next one, and `creatorId` below is derived from this.
   * Read off the current answer, typing a surname into the people box would blank `creatorId`, drop
   * the creator out of the held-back set, and hand the next Save a payload that deletes their row.
   * See {@link knownWorkshops}.
   */
  const selectedWorkshop = useMemo(
    () => knownWorkshops.get(workshopId) ?? null,
    [knownWorkshops, workshopId]
  );
  const creatorId = selectedWorkshop?.createdById ?? "";

  const loadViewers = useCallback(async () => {
    if (!workshopId) {
      setViewers(null);
      setBaseline([]);
      setSelected([]);
      setCreatorHasRow(false);
      return;
    }
    try {
      const result = await listDesignWorkshopViewers(workshopId);
      const rows = result.viewers ?? [];
      setViewers(rows);
      setCreatorHasRow(rows.some((row) => row.userId === creatorId));
      // The creator is held out of the editable set on both sides, so their presence in the server's
      // answer can never read as an unsaved change — see `creatorHasRow`.
      const editable = rows.map((row) => row.userId).filter((id) => id !== creatorId);
      setBaseline(editable);
      setSelected(editable);
      setSaveError(null);
    } catch (err) {
      setViewers([]);
      setBaseline([]);
      setSelected([]);
      setCreatorHasRow(false);
      setLoadError(describeFailure(err, "Unable to load who can see this workshop"));
    }
  }, [workshopId, creatorId]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoadError(null);
      await loadViewers();
      if (cancelled) return;
    })();
    return () => {
      cancelled = true;
    };
  }, [loadViewers]);

  /* ── The type filter ────────────────────────────────────────────────────── */

  const typeKnown = typeByWorkshopId.size > 0;
  /** What the server was actually asked for, which is also what decides the two rules below. */
  const workshopSearchTerm = workshopSearch.trim();

  /**
   * WHILE A TERM IS TYPED, THE TYPE FILTER STANDS DOWN — and this is the one judgement in this
   * change that is not a straight copy of the inspectors panel, because that panel has no second
   * narrowing to reconcile.
   *
   * The two controls answer questions of different sizes. The box asks the REPOSITORY, over every
   * design workshop there is. The type filter asks a MAP built here in the browser out of one capped
   * read of a different table (`GET /workshops?pageSize=200`), and a design workshop whose linked
   * workshop is not in that page has no type at all as far as this panel is concerned — which is
   * what the `untyped` sentence below has always been about. Letting the second narrow the first
   * would drop rows the server matched, on the strength of a local map that cannot say whether it
   * knows about them, and hand the picker an empty options array. The panel would then draw its
   * own strongest sentence — *"No matches for “bagru”. This box searches the whole list, not only
   * the rows drawn here"* — about a search that DID match. That is absence reading as
   * non-existence, arriving through the fix for it, on the console where an admin decides who can
   * open a workshop.
   *
   * So: search or filter, never both at once, and the control that stands down says so rather than
   * quietly doing nothing. The filter keeps its VALUE — clearing the box restores it — and it is
   * disabled with a sentence, which is the shape this same control already takes when the type map
   * could not be read at all.
   */
  const typeNarrowingSuspended = workshopSearchTerm !== "";

  /**
   * The workshops the selector offers, and — separately — how many the filter had to leave out.
   *
   * The count is not decoration. A design workshop is created with a title and nothing else, so a
   * great many carry no linked `Workshop` row and therefore no type at all; a filter that dropped
   * them without saying so would show an empty selector over a full repository, which is
   * indistinguishable from having no workshops and is this codebase's most repeated bug class.
   */
  const { offered, untyped } = useMemo(() => {
    const all: readonly DwSummary[] = workshops.kind === "ok" ? workshops.rows : [];
    if (!typeFilter || typeNarrowingSuspended) return { offered: all, untyped: 0 };
    const matching: DwSummary[] = [];
    let withoutType = 0;
    for (const summary of all) {
      const type = designWorkshopType(summary, typeByWorkshopId);
      if (type === null) withoutType += 1;
      else if (type === typeFilter) matching.push(summary);
    }
    return { offered: matching, untyped: withoutType };
  }, [workshops, typeFilter, typeByWorkshopId, typeNarrowingSuspended]);

  /**
   * A workshop the TYPE FILTER no longer offers must not stay selected: the roster below it would be
   * administering a record that is not on screen anywhere.
   *
   * ── IT ASKS THE SELECTED ROW, NOT THE LIST, AND THAT IS THE WHOLE OF THE CHANGE ────────────────
   *
   * It used to be `!offered.some(id)`, which was the same question while `offered` could only shrink
   * for one reason. With the box on the server there are now two reasons a workshop can be missing
   * from the answer, and only one of them is a decision about the workshop: typing a colleague's
   * name would have cleared the admin's selection and torn down the roster they were in the middle
   * of editing, silently, several keystrokes in. The type of the row this panel is administering is
   * a fact about that row and is available from `selectedWorkshop` whether or not the current answer
   * happens to contain it, so that is what is asked.
   */
  useEffect(() => {
    if (!workshopId || !typeFilter || typeNarrowingSuspended) return;
    // `null` for a row this panel has never seen as well as for one carrying no type at all, and
    // both must clear: an untyped workshop is not offered under a type filter either, which is what
    // the `untyped` sentence below exists to explain.
    const type = selectedWorkshop ? designWorkshopType(selectedWorkshop, typeByWorkshopId) : null;
    if (type !== typeFilter) setWorkshopId("");
  }, [workshopId, typeFilter, typeNarrowingSuspended, selectedWorkshop, typeByWorkshopId]);

  /**
   * The rows, in the one vocabulary — and `offPage: "recover"`, which is a claim about this control
   * and not a preference.
   *
   * ── IT WAS `"refuse"`, AND THE THING THAT MADE IT SO IS GONE ────────────────────────────────────
   *
   * The old note here said that `workshopId` could only ever have come from a row this picker DREW,
   * so there was no such thing as an off-page value and nothing for `"recover"` to recover — and
   * that "stops being true the day this picker's box goes to the server". This is that day. A search
   * answer is a moving window: the workshop being administered falls out of the next one, and with
   * `"refuse"` the trigger would go blank over a roster the admin is in the middle of editing, which
   * is the "converts a read-only fact into a wrong write" failure `WorkshopSelect` names — a blank
   * box over an administered workshop invites somebody to point the panel somewhere else.
   *
   * `"recover"` is right here and `"refuse"` is not, on the test §2.9 sets: this control describes an
   * administration that is ALREADY TRUE — the roster underneath it belongs to that workshop — rather
   * than authorising a one-way write. The row comes from {@link knownWorkshops} rather than from a
   * by-id read, because the panel has already seen it; `null` there means "not yet", never "gone".
   *
   * MEMOISED, AND ON A `serverQuery` CONTROL THAT IS LOAD-BEARING RATHER THAN AN OPTIMISATION.
   * `SearchableSelect` re-takes its pin snapshot on `options` IDENTITY — that is how it knows a new
   * answer landed — so a fresh array every render would set state on every render. See
   * `components/forms/DesignWorkshopSelect.tsx` for the full note.
   *
   * `group: true`: the difference between a workshop still being worked on and one already submitted
   * is something an admin granting access must act on, and the heading is what stops them picking a
   * closed one by mistake. Nothing is `disabled` — a submitted workshop legitimately gains a viewer.
   */
  const workshopSet = useMemo(
    () => designWorkshopOptions(workshops.kind === "ok" ? { ...workshops, rows: offered } : workshops, {
      group: true,
      offPage: { mode: "recover", row: selectedWorkshop }
    }),
    [workshops, offered, selectedWorkshop]
  );

  /**
   * SCOPED — `list_design_workshops` narrows by `visible_to_clause` for everybody but an admin, and
   * "No design workshops are open to this account" is the weaker of the two empty claims. See
   * `WorkshopListVoice.scoped` for why the two must never be collapsed.
   */
  const workshopVoice = useMemo<WorkshopListVoice>(
    () => ({ table: "design", scoped: true, online: workshopsOnline }),
    [workshopsOnline]
  );

  /**
   * DID THE TYPE FILTER EMPTY THE PICKER, rather than the repository or the read?
   *
   * A third fact that neither §3.5's sentences nor the builder can know, because the narrowing
   * happens in this browser after the answer arrived. Without the distinction a type filter that
   * matched nothing would print "No design workshops are open to this account. An administrator can
   * give you access to one." over a repository full of them, and send an admin to ask themselves for
   * access. The `untyped` paragraph below already explains what the filter left out; this only has
   * to stop the other sentence claiming the filter's work as its own.
   *
   * It cannot fire while a term is typed, because the filter is suspended there and `offered` is the
   * whole answer — see {@link typeNarrowingSuspended}. That is what keeps this and the panel's own
   * "No matches for …" from ever being on screen about the same emptiness.
   */
  const filteredAway = workshops.kind === "ok" && workshops.rows.length > 0 && offered.length === 0;

  /**
   * WHAT THE READER IS NOT SEEING, WITH THE NUMBER — the same sentence, from the same place, as
   * every other workshop picker in the app.
   *
   * ── THE HINT IS FINALLY TRUE, WHICH IS THE POINT OF THE WHOLE CHANGE ────────────────────────────
   *
   * This used to be `cappedListNotice`'s no-reach arm — *"the other N are not on this list, and
   * typing here searches only the M shown"* — because that was the only honest thing to say about a
   * box that filtered one fetched page. The box now goes to the repository, so `searchable: true`
   * and the shared "Keep typing to narrow the list" is true of this control for the first time.
   *
   * ── IT IS ASKED OF `workshopSet` AND NOT OF THE ANSWER, WHICH IS NOT THE OBVIOUS CHOICE ────────
   *
   * The tempting alternative is to count the FETCH — 80 rows out of 196 — and leave the type
   * filter's exclusions to the `untyped` paragraph, on the grounds that the two narrowings have two
   * different remedies. It is rejected on the rule this whole module is built to keep: **the
   * arithmetic in this sentence has to be checkable against the rows in front of the reader**, which
   * is the only reason anybody reads one. A filter narrowing the list to twelve rows above a line
   * that says "Showing the first 80" is a sentence about a list nobody is looking at — the exact
   * failure the old "Showing the 100 most recent" line was, in the other direction. `drawn` is
   * counted off the built options for the same reason, so a recovered row that pushed the array past
   * `RENDER_CAP` and got trimmed cannot leave this line one ahead of the panel.
   *
   * What the reader loses by that choice is nothing they cannot see: the control that narrowed the
   * list is on screen, holding the value that narrowed it, with its own paragraph beneath it, and
   * the remedy this sentence names — type, which suspends the filter and says so — genuinely does
   * reach the rest.
   */
  const workshopCut = workshopCutSentence(workshopSet, {
    term: workshopSearch,
    searchable: true
  });

  /**
   * WHICH OF §3.5's SENTENCES IS TRUE OF THIS LIST, asked of the UNNARROWED read.
   *
   * The three guards a server-searched picker needs are written out in full on
   * `components/forms/DesignWorkshopSelect.tsx` and these are the same three. In short: once a term
   * is typed an empty answer is a fact about the TERM, so printing "No design workshops are open to
   * this account. An administrator can give you access to one." under a box somebody has just typed
   * into would be a claim about a grant table produced by a filtered read — the panel says the true
   * thing in the server's own stronger words. A FAILED read still speaks through the term, because
   * that failure is not about the term at all.
   */
  const workshopNotice =
    filteredAway || (workshops.kind === "ok" && workshopSearchTerm)
      ? ""
      : workshopListNotice(workshops, workshopVoice);

  /* ── The picker's options ───────────────────────────────────────────────── */

  const creator = useMemo(() => {
    if (!creatorId) return null;
    const fromViewers = (viewers ?? []).find((row) => row.userId === creatorId);
    if (fromViewers) return { name: personLabel(fromViewers), role: fromViewers.role };
    // `known` and not the answer currently on screen: that answer is now whatever the search box
    // narrowed it to, and the creator is very often not in it — so reading it there would blank the
    // creator's name the moment an admin typed a colleague's surname, on the one block of this panel
    // that exists to say who already has access.
    const remembered = known.get(creatorId);
    if (remembered) return { name: personLabel(remembered), role: remembered.role };
    // Never the raw cuid. A list of twenty-five random characters asks an admin to recognise
    // somebody they cannot possibly recognise — the same defect the artisan pickers shipped once.
    return { name: "The designer who created it", role: "" };
  }, [creatorId, viewers, known]);

  /** What the server was actually asked — `buildQuery` sends this, and sends nothing when it is "". */
  const searchTerm = search.trim();

  /**
   * IS THE ANSWER ON SCREEN THE WHOLE ELIGIBLE SET?
   *
   * Only when nothing was cut and nothing is narrowing it. It decides one thing, and that thing used
   * to be asserted on no evidence: whether a viewer's ABSENCE from the eligible list proves they are
   * no longer eligible. Under a truncated answer it proves nothing at all — a perfectly eligible
   * viewer whose name sorts past the 2000th row is absent for a reason that has nothing to do with
   * their standing, and the old code labelled them "no longer eligible" anyway. Marking somebody
   * suspended when they are not is exactly as misleading as failing to mark somebody who is.
   */
  const eligibleListIsComplete = !eligibleTruncated && !searchTerm;

  /**
   * Everyone the picker offers: the accounts the server's current answer holds, plus anybody who
   * already HOLDS a row, plus anybody ticked from an earlier search. The creator is in none of them —
   * their access is not on offer here.
   *
   * The second group is the load-bearing one. The PUT replaces the whole set, so an option that is
   * not rendered is a row the next Save silently deletes; a designer suspended on the roster last
   * month is exactly that person, and leaving them out would revoke them as a side effect of adding
   * somebody unrelated. The third group exists because the first is now a search result rather than
   * the whole table: narrowing the list must never un-name a grant the admin has already made in this
   * sitting. Both are pinned into view by `SearchableSelect`, which lifts anything ticked above its own
   * render cap for the same reason.
   */
  const viewerOptions = useMemo(() => {
    const options: DropdownOption[] = [];
    const seen = new Set<string>();
    const offer = (id: string, label: string) => {
      if (!id || id === creatorId || seen.has(id)) return;
      seen.add(id);
      options.push({ value: id, label });
    };
    for (const person of eligible ?? []) {
      offer(person.id, `${personLabel(person)} · ${roleLabel(person.role)}`);
    }
    for (const row of viewers ?? []) {
      offer(
        row.userId,
        `${personLabel(row)} · ${roleLabel(row.role)}${
          eligibleListIsComplete ? " — has access, no longer eligible" : " — has access"
        }`
      );
    }
    for (const id of selected) {
      const person = known.get(id);
      if (person) offer(person.id, `${personLabel(person)} · ${roleLabel(person.role)}`);
    }
    return options;
  }, [eligible, viewers, selected, known, creatorId, eligibleListIsComplete]);

  /* ── Unsaved state ──────────────────────────────────────────────────────── */

  const dirty = useMemo(() => {
    if (selected.length !== baseline.length) return true;
    const held = new Set(baseline);
    return selected.some((id) => !held.has(id));
  }, [selected, baseline]);

  /**
   * The one line under the search box — one line, not a paragraph about pagination.
   *
   * FOUR FACTS, AND THEY ARE DIFFERENT FACTS: people are hidden and searching reaches them; people
   * are hidden from THIS search and it needs narrowing; people are hidden from EVERY search because
   * the roster read was cut, so narrowing cannot help; the search matched nobody, which is not the
   * same as nobody being eligible. When `truncated` is false and the search matched somebody the line
   * is absent entirely — a complete list has nothing to explain, and the repository owner has asked
   * twice this week for less text on these screens.
   *
   * **THE WORDS ARE ANDROID'S, VERBATIM** (`dwViewerOfferNotice`, the same four states in the same
   * order), because a researcher moves between the two apps mid-workshop and the shared vocabulary is
   * the whole reason the server carries one flag instead of each client inventing its own sentence.
   * The choice itself lives in `lib/designWorkshopViewers.eligibleViewerNotice` for the reason given
   * there: one of the four states no live database can produce, so it must be reachable without a
   * browser.
   *
   * "Searching…" takes the same slot rather than adding a second line, and only once something has
   * been typed. Android shows a spinner in the search field's trailing slot for this; `SearchInput`
   * has no such slot — its trailing position is the clear button — so the same fact is a word here.
   * It matters either way: a stale list of two thousand names under a half-typed surname is exactly
   * when a reader concludes the person is not there.
   */
  const searchNotice = searching && searchTerm
    ? "Searching…"
    : eligibleViewerNotice({
        truncated: eligibleTruncated,
        offered: eligible?.length ?? 0,
        searched: Boolean(searchTerm)
      });
  const searchNoticeId = useId();

  /**
   * What the picker says when it has no rows to draw — two different facts, again Android's words.
   *
   * "No designers are eligible" is a statement about the roster and is only true of an unsearched
   * empty answer. Saying it under a search term would be the silent-emptiness bug in one sentence: the
   * reader mistyped a surname and is told the repository has nobody in it.
   */
  const pickerEmptyLabel = searching
    ? "Searching…"
    : searchTerm
      ? "No eligible account matches that search."
      : "No designers are eligible";

  const labelById = useMemo(() => new Map(viewerOptions.map((option) => [option.value, option.label])), [viewerOptions]);
  const added = useMemo(() => selected.filter((id) => !baseline.includes(id)), [selected, baseline]);
  const removed = useMemo(() => baseline.filter((id) => !selected.includes(id)), [baseline, selected]);

  /**
   * The sentence the live region carries — the RESULTING count, plus what is still unsent.
   *
   * The multi-select has an `aria-live` of its own, but it lives inside a portalled panel that
   * unmounts the moment the picker closes, so a reader who ticks three names and closes the panel
   * would hear nothing about where the workshop now stands. This region is rendered from the first
   * paint, empty or not, because assistive technology only announces mutations inside a region that
   * already existed.
   */
  const announcement = !workshopId
    ? ""
    : dirty
      ? `${selected.length} designer${selected.length === 1 ? "" : "s"} selected, not yet saved. ${added.length} to add, ${removed.length} to remove.`
      : `${selected.length} designer${selected.length === 1 ? "" : "s"} can see this workshop, in addition to the designer who created it. Nothing unsaved.`;

  async function save() {
    if (!workshopId) return;
    setSaving(true);
    setSaveError(null);
    try {
      // The creator's own row is re-attached rather than re-derived: this replaces the whole set,
      // and dropping a row the server itself reported is the one edit this panel may not make.
      const payload = creatorHasRow && creatorId ? [...selected, creatorId] : selected;
      const result = await putDesignWorkshopViewers(workshopId, payload);
      const rows = result.viewers ?? [];
      setViewers(rows);
      setCreatorHasRow(rows.some((row) => row.userId === creatorId));
      // The ANSWER becomes the baseline, not what was sent: another admin may have added somebody
      // between this page loading and Save being pressed, and treating our own payload as the truth
      // would leave the screen showing a membership nobody has.
      const editable = rows.map((row) => row.userId).filter((id) => id !== creatorId);
      setBaseline(editable);
      setSelected(editable);
      toast({
        title: editable.length ? `${editable.length} designer${editable.length === 1 ? "" : "s"} can see this workshop` : "Nobody else can see this workshop",
        description: "The designer who created it always can, and is not affected by this list.",
        tone: "success"
      });
    } catch (err) {
      setSaveError(describeFailure(err, "Unable to save who can see this workshop"));
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="panel p-5" data-design-workshop-viewers>
      <div className="flex items-center gap-2.5">
        <span className="grid h-8 w-8 place-items-center rounded-md bg-purple-950 text-purple-100">
          <DraftingCompass className="h-4 w-4" aria-hidden />
        </span>
        <div>
          <h2 className="font-display font-bold text-ink-900">Design workshop visibility</h2>
          <p className="text-xs leading-5 text-ink-500">
            Who may open one design &amp; prototype workshop, besides the designer who created it.
          </p>
        </div>
      </div>

      <p className="mt-3 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs leading-5 text-ink-500">
        A design workshop is visible only to the designer who started it. A workshop is usually run by two designers
        alongside a master craftsperson and a reviewing officer, and a colleague who opens it today is told the record
        does not exist — so name the designers who should see this one.
      </p>

      {featureMissing ? (
        <p className="mt-3 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-5 text-amber-800">
          This repository does not offer design workshop visibility yet. The controls below are hidden rather than shown
          doing nothing — nobody has been granted or removed, and an admin can still open any workshop themselves.
        </p>
      ) : null}

      {loadError ? (
        <div className="mt-3 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">{loadError}</div>
      ) : null}

      {featureMissing ? null : (
        <>
          <div className="mt-4 grid gap-3 md:grid-cols-[minmax(0,0.9fr)_minmax(0,1.4fr)]">
            <FieldBlock label="Workshop type">
              <Dropdown
                // Filters the screen it sits on, so focus stays on the control being adjusted rather
                // than jumping to the next field.
                advanceOnSelect={false}
                ariaLabel="Workshop type"
                /*
                  TWO REASONS TO STAND DOWN, AND EACH HAS ITS OWN SENTENCE BELOW. The type map could
                  not be read, so there is nothing to filter BY; or a search term is in the box beside
                  it, so filtering would narrow the repository's answer with a local map that cannot
                  say what it does not know about. See `typeNarrowingSuspended` for the second and
                  why it is a stand-down with words rather than a filter that quietly does nothing.
                */
                disabled={!typeKnown || typeNarrowingSuspended}
                onChange={setTypeFilter}
                options={TYPE_OPTIONS}
                value={typeFilter}
              />
            </FieldBlock>
            <FieldBlock label="Design workshop">
              <Dropdown
                advanceOnSelect={false}
                ariaLabel="Design workshop"
                /*
                  THE TERM IS CLEARED ON THE PICK, AND THIS PANEL IS THE ONE PLACE THAT MUST DO IT.
                  `SearchableSelect` deliberately never writes "" into a caller's term — closing the
                  menu would otherwise re-fetch the unnarrowed list and throw away the narrowing the
                  reader had just done — so clearing is the caller's decision, and here the decision
                  is forced by what the term does to the control BESIDE this one. A term suspends the
                  type filter (see `typeNarrowingSuspended`); a term that outlived the pick would
                  leave that filter disabled for as long as nobody thought to go back and empty a box
                  they had finished with, which is a control disabled by a cause the reader can no
                  longer see. The other three server-searched pickers keep their term because nothing
                  on their screens depends on it.

                  The empty term re-asks immediately (no debounce on a clear), and the workshop just
                  picked is held by `knownWorkshops` and drawn back by `offPage: "recover"` whether or
                  not the unnarrowed page happens to contain it.
                */
                onChange={(id) => {
                  setWorkshopId(id);
                  setWorkshopSearch("");
                }}
                options={workshopSet.options}
                /*
                  THE BOX IS THE SERVER'S NOW, AND IT IS STILL ONLY ONE BOX. It was `searchable` over
                  the array this panel had fetched — one page of `GET /design-workshops` — so an
                  admin who typed the title of a workshop sitting on page two was told "No matches"
                  about a workshop that exists, on the console where that reads as "there is no such
                  workshop". `serverQuery` forces the box on, BYPASSES the local filter pass —
                  `options` already IS the answer to this term, and filtering it again would drop rows
                  the server matched on `workshopCode`, which the label deliberately does not show —
                  and makes the empty line three-way: pending, matched-nothing-on-the-server,
                  nothing-here-at-all.

                  THE LENGTH CAP IS BORROWED AND IS SAID TO BE BORROWED. `list_design_workshops`
                  declares a bare `search: str | None` with no `max_length`, so nothing would refuse a
                  long paste — it would simply run a very long `ILIKE` that matches nothing. Reusing
                  this panel's own eligible-viewer bound keeps one number on this screen rather than
                  two, and nobody should read it as a rule the server enforces.

                  `truncated` is deliberately absent — see the note beside `workshopCut`: this route
                  reports a real total, so the cut is stated once, underneath, with its number.
                */
                serverQuery={{
                  value: workshopSearch,
                  onChange: (next) => setWorkshopSearch(next.slice(0, ELIGIBLE_VIEWER_SEARCH_MAX)),
                  pending: workshopsSearching
                }}
                /*
                  THE FOUR EMPTY STATES ARE FOUR SENTENCES, PLUS THIS SCREEN'S OWN FIFTH. The
                  placeholder used to carry them all: "Loading design workshops…" while the read was
                  in flight and "No design workshops of this type" for every other reason, including
                  a read that had failed. `workshopEmptyLabel` answers the four §3.5 states — still
                  asking, could-not-be-listed, empty-because-offline, genuinely-empty — and the guard
                  in front of it answers the fifth, which is the only one that is about the filter
                  above rather than about the repository. A sixth — "your term matched nothing" — is
                  the panel's own and is drawn in this slot's place whenever a term is typed, in the
                  stronger words a server-backed box has earned.
                */
                emptyLabel={
                  filteredAway
                    ? "No design workshop of this type is on this page."
                    : workshopEmptyLabel(workshops, workshopVoice)
                }
                placeholder="Select or type to search"
                value={workshopId}
              />
            </FieldBlock>
          </div>

          {/* Every row the filter left out is counted and explained. A selector that quietly stops is
              indistinguishable from a repository with nothing in it. */}
          {typeFilter && untyped > 0 ? (
            <p className="mt-2 text-xs leading-5 text-ink-500">
              {untyped} design workshop{untyped === 1 ? " is" : "s are"} not linked to a workshop record, so
              {untyped === 1 ? " it carries" : " they carry"} no type and {untyped === 1 ? "is" : "are"} not offered
              under this filter. Choose “Any workshop type” to administer {untyped === 1 ? "it" : "them"}.
            </p>
          ) : null}
          {!typeKnown ? (
            <p className="mt-2 text-xs leading-5 text-ink-500">
              The workshop list could not be read, so nothing is known about workshop types and the filter is turned
              off. Every design workshop is offered.
            </p>
          ) : null}
          {/* THE OTHER STAND-DOWN, SAID RATHER THAN PERFORMED. A control that keeps its value and
              quietly stops narrowing is a control an admin reads as broken; one that says which of
              the two questions is being answered is a control they can use. The remedy is named,
              because it is one keystroke away. */}
          {typeKnown && typeNarrowingSuspended ? (
            <p className="mt-2 text-xs leading-5 text-ink-500" aria-live="polite">
              The type filter is off while you are searching. A workshop type comes from a separate list of at most{" "}
              {WORKSHOP_PAGE} workshops, so applying it to a search of the whole archive would hide workshops the search
              found without being able to say which. Empty the search box inside the design workshop picker to filter by
              type again.
            </p>
          ) : null}
          {typeKnown && typeMapPartial ? (
            <p className="mt-2 text-xs leading-5 text-ink-500">
              Only the first {WORKSHOP_PAGE} workshops were read, so a design workshop linked to an older one may show
              no type and be left out of a filtered list.
            </p>
          ) : null}
          {/* THE CAP, WITH THE NUMBER, AND NOW POINTING AT SOMETHING. It used to read "Showing the
              100 most recent design workshops of N" while the panel drew 80 of them — one sentence
              describing a list nobody was looking at, and complete silence between 81 and 100. It is
              `selectFilter.ts`'s sentence, through `workshopCutSentence`, so this line and the
              panel's own footer cannot describe one cut in two wordings — and its last clause names
              the box in the panel, which now genuinely reaches the rest. */}
          {workshopCut ? <p className="mt-2 text-xs leading-5 text-ink-500">{workshopCut}</p> : null}
          {/* WHICH OF THE FOUR EMPTY STATES THIS IS. Four different next moves — wait, connect, ask
              an administrator, create one — and until now one sentence for all of them. `aria-live`
              because the trigger is not somewhere a reader can land in three of the four. */}
          {workshopNotice ? (
            <p className="mt-2 text-xs leading-5 text-ink-500" aria-live="polite">
              {workshopNotice}
            </p>
          ) : null}

          {!workshopId ? (
            <p className="mt-4 text-sm text-ink-500">Pick a design workshop to see who can open it.</p>
          ) : viewers === null ? (
            <p className="mt-4 text-sm text-ink-500">Loading…</p>
          ) : (
            <>
              {/* The creator sits OUTSIDE the picker, because their access is not this list's to
                  give or take. See the file header. */}
              <div className="mt-4 rounded-md border border-purple-300 bg-purple-50 px-3 py-2">
                <p className="text-xs font-semibold uppercase tracking-wide text-purple-700">Always has access</p>
                <p className="mt-1 text-sm text-ink-900">
                  {creator ? creator.name : "The designer who created it"}
                  {creator?.role ? <span className="text-ink-500"> · {roleLabel(creator.role)}</span> : null}
                </p>
                <p className="mt-1 text-xs leading-5 text-ink-500">
                  They created this workshop, so they can always open it. Nothing below removes that.
                </p>
              </div>

              <div className="mt-4">
                <FieldBlock
                  label="Designers who may see this workshop"
                  hint={
                    <p className="text-xs leading-5 text-ink-500">
                      Only accounts that could actually run a design workshop are offered — a designer whose roster row
                      is suspended would be refused at the door. Unticking somebody removes their access when you save.
                    </p>
                  }
                >
                  <div className="grid gap-2">
                    {/* The one search box. It asks the SERVER, which is the only thing that can see past
                        the 2000-account ceiling — see the file header. Capped at the length the endpoint
                        accepts so a long paste narrows the list instead of returning a 422. */}
                    <SearchInput
                      onChange={(next) => setSearch(next.slice(0, ELIGIBLE_VIEWER_SEARCH_MAX))}
                      placeholder="Search designers by name or email"
                      value={search}
                    />
                    {/* At most ONE line here, ever: what the search is doing, or the single sentence that
                        says the list is incomplete. Nothing at all when the list is whole. */}
                    {searchNotice ? (
                      <p className="text-xs leading-5 text-ink-500" id={searchNoticeId}>
                        {searchNotice}
                      </p>
                    ) : null}
                    <MultiSelectDropdown
                      ariaLabel="Designers who may see this workshop"
                      confirmLabel="Done"
                      // Pointed at the truncation line only while it is on screen: `aria-describedby`
                      // naming an id that is not in the document is worse than naming nothing.
                      describedBy={searchNotice ? searchNoticeId : undefined}
                      emptyLabel={pickerEmptyLabel}
                      onChange={setSelected}
                      options={viewerOptions}
                      placeholder="Select one or more designers"
                      // OFF, deliberately. The box above is the search, and it reaches the whole table;
                      // this control's own filter box would have searched only the accounts that fitted
                      // under the ceiling and answered "No matches" for a colleague who is eligible and
                      // merely sorts late — the defect this panel is being fixed for, wearing a search
                      // box. Two boxes over two different scopes would also be two boxes.
                      searchable={false}
                      /*
                        ── AND THEREFORE THIS SENTENCE, WHICH THE PANEL USED TO GET WRONG ──────────
                        `searchable={false}` does not switch the RENDER CAP off: the endpoint answers
                        up to 2000 accounts and the panel draws 80, so the cap notice fires here on
                        any real repository. Its last clause used to be the hardcoded "Keep typing to
                        narrow the list" — an instruction to type into a filter box this panel
                        deliberately does not have. So an admin looking for designer 81 was told to
                        use a control that is not on screen, and the box that DOES reach them, three
                        inches above and wired to the server, went unmentioned. The notice is the one
                        sentence whose whole job is to describe this list; pointing it at the wrong
                        control is worse than leaving it blank.
                      */
                      capHint="Use the search box above to reach the rest — it asks the repository, so it sees every eligible account."
                      values={selected}
                    />
                  </div>
                </FieldBlock>
              </div>

              {/* What is currently SAVED, spelled out — the picker's trigger says "3 selected", which
                  is the pending answer and not the state of the repository. */}
              <div className="mt-3">
                {viewers.filter((row) => row.userId !== creatorId).length === 0 ? (
                  <p className="text-sm text-ink-500">
                    Nobody else can open this workshop yet. Only the designer who created it, and admins, can.
                  </p>
                ) : (
                  <ul className="grid gap-1.5">
                    {viewers
                      .filter((row) => row.userId !== creatorId)
                      .map((row) => (
                        <li className="flex flex-wrap items-baseline gap-x-2 text-sm text-ink-700" key={row.userId}>
                          <span className="font-medium text-ink-900">{personLabel(row)}</span>
                          <span className="text-xs text-ink-500">
                            {row.email}
                            {row.role ? ` · ${roleLabel(row.role)}` : ""}
                            {row.grantedAt ? ` · added ${formatDateTime(row.grantedAt)}` : ""}
                          </span>
                          {removed.includes(row.userId) ? (
                            <span className="rounded-full border border-error-600/25 bg-error-100 px-2 py-0.5 text-[0.6875rem] font-medium text-error-600">
                              will be removed on save
                            </span>
                          ) : null}
                        </li>
                      ))}
                  </ul>
                )}
                {added.length ? (
                  <p className="mt-2 text-xs leading-5 text-ink-500">
                    Will be added on save: {added.map((id) => labelById.get(id) ?? "Unknown user").join(", ")}.
                  </p>
                ) : null}
              </div>

              {/* Immediately above the buttons, not pinned to the top of a panel this tall: a refusal
                  rendered five sections away from the control that caused it reads as a button that
                  did nothing. */}
              {saveError ? (
                <div className="mt-3 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">
                  {saveError}
                </div>
              ) : null}

              <div className="mt-3 flex flex-wrap items-center gap-2">
                <button className="field-button" disabled={!dirty || saving} onClick={save} type="button">
                  <Save className="h-4 w-4" aria-hidden />
                  {saving ? "Saving…" : "Save who can see this"}
                </button>
                {dirty ? (
                  <button
                    className="field-button-secondary"
                    disabled={saving}
                    onClick={() => {
                      setSelected(baseline);
                      setSaveError(null);
                    }}
                    type="button"
                  >
                    Discard changes
                  </button>
                ) : null}
                {/* Colour never carries the meaning on its own — the word "unsaved" does. */}
                {dirty ? <span className="text-xs text-amber-800">{added.length + removed.length} unsaved change{added.length + removed.length === 1 ? "" : "s"}</span> : null}
              </div>
            </>
          )}
        </>
      )}

      <p className="sr-only" role="status" aria-live="polite">
        {announcement}
      </p>
    </section>
  );
}
