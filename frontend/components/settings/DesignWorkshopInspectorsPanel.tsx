"use client";

import { ScanEye, Save } from "lucide-react";
import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";

import { SearchInput } from "@/components/SearchInput";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown, MultiSelectDropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { useToast } from "@/components/ui/Toast";
import { ApiError } from "@/lib/api";
import {
  eligibleInspectorNotice,
  ELIGIBLE_INSPECTOR_SEARCH_MAX,
  inspectionAdministrationMissing,
  listDesignWorkshopInspectors,
  listEligibleDesignWorkshopInspectors,
  MAX_DESIGN_WORKSHOP_INSPECTORS,
  putDesignWorkshopInspectors,
  type DwEligibleInspector,
  type DwInspector
} from "@/lib/designWorkshopInspections";
import { listDesignWorkshops, type DwSummary } from "@/lib/designWorkshops";
import { formatDateTime } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import { roleLabel } from "@/lib/permissions";
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
 * WHO INSPECTS ONE DESIGN & PROTOTYPE WORKSHOP — the admin's half of the fifth scope.
 *
 * The other half is the inspector's own read surface at /design-workshop-inspections, which this
 * account cannot open: `assert_inspection_surface` 403s an admin by name. Two halves, two doors, and
 * this panel is the only place an inspection comes into existence.
 *
 * It sits on /workshop-access/manage beside `DesignWorkshopViewersPanel` for exactly the reasons
 * that panel gives: this page is already the one place an admin goes to answer "who may work on
 * what", and it already carries both gates the answer needs — the ROUTE_REDIRECTS rule that sends
 * below-admin to the request page, and the ADMIN_CHROME_ROUTES rule that gives an admin with admin
 * view off a panel instead of a redirect. A second route would have been a second copy of both.
 *
 * ── THE FOUR WAYS IT IS NOT THE VIEWERS PANEL ─────────────────────────────────────────────────
 *
 * It is modelled on that panel deliberately and it is not a copy of it, and every difference below
 * is a fact about the SERVER rather than a preference:
 *
 * 1. **THERE IS NO CREATOR HELD OFF TO ONE SIDE.** A workshop's creator holds it through
 *    `createdById` whatever the viewers list says, which is why that panel renders them outside the
 *    picker and re-attaches their row to every PUT. Nobody holds an inspection by any route other
 *    than a row in this table, so an empty answer here means NOBODY IS INSPECTING THIS WORKSHOP —
 *    the literal truth — and this panel prints it as such with no caveat, because there is none.
 *
 * 2. **THE WORKSHOP'S OWN PEOPLE ARE REFUSED, AND THE SERVER DOES THE REFUSING.** The creator and
 *    any co-designer holding a `DesignWorkshopViewer` row are answered with a 422 saying an
 *    independent review by somebody who worked on it is not a review. That refusal exists nowhere
 *    else in this codebase and it is the point of the tier. This panel does not pre-empt it: the two
 *    role sets are disjoint today, so the case is reachable only through a promotion, and a
 *    client-side copy of a rule it can only guess at would go stale silently. It surfaces the
 *    server's sentence, which names the account and the remedy.
 *
 * 3. **THE ELIGIBLE SET IS ONE ROLE AND NO ROSTER.** `eligible_inspectors` offers accounts in
 *    `INSPECTION_ROLES` — Inspector / Reviewer and nothing else — minus anyone the platform
 *    allow-list has barred. `DesignerRoster` is not consulted, because an inspector is empanelled to
 *    run nothing and requiring a row there would refuse every inspector there will ever be. So the
 *    truncation notice here has three states where the viewers' has four; see
 *    `eligibleInspectorNotice` for why the fourth cannot occur.
 *
 * 4. **THE SET IS CAPPED AT 25.** An inspection panel is one person, occasionally two; the cap is
 *    the server's `MAX_DESIGN_WORKSHOP_INSPECTORS` and the `max_length` of the body's `userIds`. It
 *    is mirrored rather than discovered, because a picker that lets an admin tick a twenty-sixth
 *    name and then shows them a 422 has spent their afternoon to teach them a number this client
 *    already knew.
 *
 * ── AND TWO THINGS IT COPIES EXACTLY, BECAUSE THEY ARE THE HOUSE RULES ────────────────────────
 *
 * **SAVING IS EXPLICIT.** Every toggle writing straight through would mean an admin re-picking a
 * mis-clicked name has already ended somebody's inspection for however long the round trip takes.
 * The pickers edit a PENDING set, the panel says what is unsaved, and one button sends it.
 *
 * **BOTH SEARCHES ARE THE SERVER'S, AND BOTH PICKERS HAVE THEIR OWN FILTER BOX TURNED OFF.** There
 * are two boxes on this panel because there are two different tables behind it — design workshops
 * and user accounts — and each box is labelled for its own. What there is not, anywhere here, is a
 * filter box over a list the server already truncated: that control searches only the rows that
 * fitted and answers "No matches" about records that exist, which is rule 10 wearing a search box
 * (skill §11.5). The viewers panel still filters its WORKSHOP dropdown in the browser over a page of
 * a longer list; that half was not copied.
 */

/**
 * How many rows each list asks for.
 *
 * `WORKSHOP_OPTION_PAGE_SIZE` for the workshops, which IS `RENDER_CAP` and is now reached through
 * `lib/workshopOptions` so that every workshop picker in the app names one number: the picker draws
 * at most 80 rows, so asking for 100 would print two truncation sentences with two different totals,
 * one above the other, and say nothing at all between 81 and 100. The eligible accounts are whatever
 * the server's own ceiling answers, which is its business and not this panel's — what this panel
 * owes there is to say when the answer was cut.
 */
const WORKSHOP_PAGE = WORKSHOP_OPTION_PAGE_SIZE;

/**
 * How long after the last keystroke a search goes out.
 *
 * 300 ms, the same number as the viewers panel and `StageReferenceField`, for the same measured
 * reason: the account search is an `ILIKE '%term%'` over `User` that no index can answer, so every
 * keystroke that escapes the debounce is a full scan of the largest table in the database.
 *
 * Clearing a box does NOT wait: an empty term is the unnarrowed list, which is the one request that
 * is always about to be needed and never about to be superseded by the next letter.
 */
const SEARCH_DEBOUNCE_MS = 300;

/*
  THE LOCAL `designWorkshopLabel` IS GONE, AND THAT IS THE POINT OF THE CHANGE RATHER THAN TIDYING.

  It built `title · date`, one of SIX label shapes the app shipped for one question — this panel's
  and the viewers panel's `title · date`, the record picker's `title` with a `craft · cluster · date`
  hint, `/design-review`'s `title · date` with a `workshopCode` hint, the questionnaires' bare
  `title`, and two more on the other table. Three of those live on screens an admin walks between in
  one sitting. `lib/workshopOptions::designWorkshopOptions` is now the only thing in the web client
  that turns a workshop into a row, and its ruling is that the LABEL is the title alone and every
  fact that tells two workshops apart goes in the `hint` — which `SearchableSelect` searches as well
  as draws, so nothing became unreachable by moving.
*/

/** Name a person without ever leaking an id: their name, else their email, else a neutral fallback. */
function personLabel(person: { name?: string | null; email?: string | null } | null | undefined): string {
  return person?.name?.trim() || person?.email?.trim() || "Unknown user";
}

/**
 * The failures this panel can suffer, told apart in words.
 *
 * `isUnreachable` and not `isTransient`: the latter answers "is it worth retrying" and counts every
 * 5xx as yes, so a repository that ANSWERED and then failed would be reported as a connection
 * problem — which sends an admin to look at their signal and leaves a real fault wearing an offline
 * message.
 *
 * THE 422 IS PASSED THROUGH ALMOST BARE, and that is the important arm here rather than a fallback.
 * The server's refusals on this route name the account, say what is wrong with it and say where the
 * remedy is — "clear that on the access screen first", "take them off the workshop's viewers first"
 * — and every one of them ends in "Nothing was changed." Paraphrasing any of that would replace a
 * sentence an admin can act on with one they cannot.
 */
function describeFailure(error: unknown, fallback: string): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) {
    return "This device cannot reach the repository, so nothing was sent and nothing has changed. Check the connection and try again.";
  }
  if (error.status === 403) {
    return `The repository refused this. ${error.message} Choosing who inspects a workshop is administration, so it is open to admins and the master admin only.`;
  }
  if (error.status === 422) {
    return `The repository would not accept this. ${error.message}`;
  }
  if (error.status === 404) {
    return `${error.message} This workshop may have been deleted since the list was loaded — reload the page to see the current list.`;
  }
  return error.message || fallback;
}

export function DesignWorkshopInspectorsPanel({ refreshToken }: { refreshToken?: number }) {
  const { toast } = useToast();

  /* ── The workshop being administered ────────────────────────────────────── */

  /**
   * What the admin has typed to find a workshop. Sent to the server; never used to filter locally.
   *
   * IT NOW LIVES INSIDE THE PICKER'S OWN BOX rather than in a `SearchInput` above it. The box was
   * mounted separately because there was no way to get a term out of `SearchableSelect`, which cost
   * this control the panel's `role="status"` live region and its "Searching…" arm and put two
   * unrelated boxes on one panel. `serverQuery` is that way; the term still goes to the repository
   * and this control still never filters the array it was handed.
   */
  const [workshopSearch, setWorkshopSearch] = useState("");
  /** What the workshop read answered — three states, so a failure cannot draw as an empty table. */
  const [workshops, setWorkshops] = useState<WorkshopListState<DwSummary>>({ kind: "loading" });
  /** Was the device reachable when that read FAILED? Captured in the catch, for the reason
   *  `DesignWorkshopSelect` gives: it is a fact about the moment the request died. */
  const [workshopsOnline, setWorkshopsOnline] = useState(true);
  const [workshopId, setWorkshopId] = useState("");
  /**
   * Every workshop this panel has seen, across every search it has run.
   *
   * The option list is a MOVING WINDOW over the table rather than the whole of it, so a workshop
   * chosen under one search term is not in the answer to the next one. Without a remembered label
   * the heading below the picker would name the workshop being administered "Untitled design
   * workshop" the moment the admin typed something else into the box above it.
   */
  const [knownWorkshops, setKnownWorkshops] = useState<Map<string, DwSummary>>(() => new Map());
  const [workshopsSearching, setWorkshopsSearching] = useState(false);

  /* ── The eligible accounts ──────────────────────────────────────────────── */

  const [search, setSearch] = useState("");
  const [eligible, setEligible] = useState<DwEligibleInspector[] | null>(null);
  /** The server's own word for "this answer is not the whole eligible set". Rendered, once, when true. */
  const [eligibleTruncated, setEligibleTruncated] = useState(false);
  /** A search is pending or in flight — so a stale list on screen is never read as the answer. */
  const [searching, setSearching] = useState(false);
  /** Every eligible account seen across every search, so a ticked name keeps its label. */
  const [known, setKnown] = useState<Map<string, DwEligibleInspector>>(() => new Map());
  /**
   * The routes ship separately from this bundle, so "the server has not got this yet" is a state the
   * panel renders rather than a case to assume away. See `inspectionAdministrationMissing` for why
   * the id-less endpoint is the honest probe.
   */
  const [featureMissing, setFeatureMissing] = useState(false);

  /* ── The chosen workshop's inspection set ───────────────────────────────── */

  const [inspectors, setInspectors] = useState<DwInspector[] | null>(null);
  /** The saved membership — what `selected` is compared against to decide dirty. */
  const [baseline, setBaseline] = useState<string[]>([]);
  const [selected, setSelected] = useState<string[]>([]);

  const [loadError, setLoadError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  /* ── The workshop list, searched server-side ────────────────────────────── */

  /**
   * A generation counter rather than a `cancelled` flag: `apiFetch` takes no `AbortSignal`, so two
   * searches can be in flight at once and the house convention is to count them and ignore the late
   * answer. Without it a slow response for "bag" lands after the fast one for "bagru" and the picker
   * shows the wrong list under the typed word — which is precisely when somebody picks the first row
   * without reading it.
   */
  const workshopGeneration = useRef(0);

  useEffect(() => {
    const term = workshopSearch.trim();
    const current = workshopGeneration.current + 1;
    workshopGeneration.current = current;
    setWorkshopsSearching(true);
    const timer = window.setTimeout(
      () => {
        listDesignWorkshops({ page: 1, pageSize: WORKSHOP_PAGE, search: term || undefined })
          .then((result) => {
            if (workshopGeneration.current !== current) return;
            setWorkshops({ kind: "ok", rows: result.items, total: result.total });
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
              NOT `setLoadError`, AND NOT AN EMPTY ARRAY EITHER. `setSummaries([])` was the second
              half of the bug this whole parcel exists to close: a timeout drew as a repository with
              no workshops in it. The red strip is not the answer either — the sentence belongs on
              the control it is about, in the four words §3.5 gives every workshop picker on both
              clients, and a banner saying one thing above a picker saying another teaches a reader
              that neither is worth reading. `loadError` still carries the failures of the OTHER two
              reads on this panel, which have no control of their own to speak through.
            */
            setWorkshopsOnline(!deviceLooksOffline());
            setWorkshops({ kind: "failed" });
          });
      },
      term ? SEARCH_DEBOUNCE_MS : 0
    );
    return () => window.clearTimeout(timer);
  }, [refreshToken, workshopSearch]);

  /* ── The eligible accounts, searched server-side ────────────────────────── */

  const eligibleGeneration = useRef(0);

  useEffect(() => {
    const term = search.trim();
    const current = eligibleGeneration.current + 1;
    eligibleGeneration.current = current;
    setSearching(true);
    const timer = window.setTimeout(
      () => {
        listEligibleDesignWorkshopInspectors(term)
          .then((result) => {
            if (eligibleGeneration.current !== current) return;
            const users = result.users ?? [];
            setEligible(users);
            // Coerced, not trusted: a server that predates the field leaves this `undefined`, and an
            // unknown flag must say nothing rather than cry truncation at a list that is complete.
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
            if (inspectionAdministrationMissing(err)) {
              setFeatureMissing(true);
              setEligible([]);
              return;
            }
            setEligible([]);
            setLoadError(describeFailure(err, "Unable to load the accounts that may be assigned an inspection"));
          });
      },
      term ? SEARCH_DEBOUNCE_MS : 0
    );
    return () => window.clearTimeout(timer);
  }, [refreshToken, search]);

  /* ── The chosen workshop's inspectors ───────────────────────────────────── */

  const loadInspectors = useCallback(async () => {
    if (!workshopId) {
      setInspectors(null);
      setBaseline([]);
      setSelected([]);
      return;
    }
    try {
      const result = await listDesignWorkshopInspectors(workshopId);
      const rows = result.inspectors ?? [];
      setInspectors(rows);
      const held = rows.map((row) => row.userId);
      setBaseline(held);
      setSelected(held);
      setSaveError(null);
    } catch (err) {
      setInspectors([]);
      setBaseline([]);
      setSelected([]);
      setLoadError(describeFailure(err, "Unable to load who is inspecting this workshop"));
    }
  }, [workshopId]);

  useEffect(() => {
    setLoadError(null);
    void loadInspectors();
  }, [loadInspectors]);

  const selectedWorkshop = useMemo(
    () => knownWorkshops.get(workshopId) ?? null,
    [knownWorkshops, workshopId]
  );

  /**
   * The rows, in one vocabulary, with the chosen workshop kept on offer.
   *
   * ── THE HAND-ROLLED PIN BECAME `offPage`, AND IT IS THE SAME ARGUMENT IT ALWAYS WAS ────────────
   *
   * This memo used to append the chosen workshop by hand when the current search did not reach it,
   * with the note that otherwise "the dropdown would draw a trigger with no matching row and read as
   * though nothing were chosen". That is `"recover"`: the control is describing an administration
   * that is ALREADY TRUE — the roster underneath it is that workshop's — and withholding the row
   * withholds nothing while inviting an admin to point the panel somewhere else. `WorkshopSelect`
   * and `DesignWorkshopSelect` re-implemented the same three lines, three times, with three
   * different placements in the list; the builder now draws it under "Already on this record", which
   * is what keeps the scope of the rest of the list honest.
   *
   * `"refuse"` — the other answer, and the one `AdoptLocalDraftDialog` takes — is for a control that
   * AUTHORISES a one-way write. Nothing here is one-way: an inspection is a set an admin edits and
   * saves, and unpicking it is one press.
   *
   * MEMOISED BECAUSE THIS IS A `serverQuery` CONTROL. `SearchableSelect` re-takes its pin snapshot
   * on `options` identity, which is how it knows a new answer landed; a fresh array every render
   * would make that effect set state on every render. See `DesignWorkshopSelect` for the full note.
   */
  const workshopSet = useMemo(
    () =>
      designWorkshopOptions(workshops, {
        group: true,
        offPage: { mode: "recover", row: selectedWorkshop }
      }),
    [workshops, selectedWorkshop]
  );

  /**
   * SCOPED. `list_design_workshops` applies `visible_to_clause` to everybody but an admin — and this
   * panel is admin-only, so in practice the answer is the whole archive. It stays `true` because the
   * sentence it picks has to be true for the account reading it: an admin who genuinely sees nothing
   * has an empty repository, and "No design workshops are open to this account" is the weaker and
   * therefore safer of the two claims. See `WorkshopListVoice.scoped`.
   */
  const workshopVoice = useMemo<WorkshopListVoice>(
    () => ({ table: "design", scoped: true, online: workshopsOnline }),
    [workshopsOnline]
  );

  /*
    The three guards a server-searched picker needs are written out in full on
    `components/forms/DesignWorkshopSelect.tsx`; these are the same three. In short: a notice about
    an empty list is a claim about the TERM once a term is typed, so it is asked of the unnarrowed
    list only; the control must not stand down while the box holds the term that emptied it; and
    `serverQuery.truncated` is not passed, because this route reports a real total and the cut is
    stated once, below, in `selectFilter.ts`'s words.
  */
  const workshopSearchTerm = workshopSearch.trim();
  const workshopNotice =
    workshops.kind === "ok" && workshopSearchTerm ? "" : workshopListNotice(workshops, workshopVoice);
  const workshopCut = workshopCutSentence(workshopSet, {
    term: workshopSearch,
    searchable: true
  });

  /* ── The picker's options ───────────────────────────────────────────────── */

  const searchTerm = search.trim();

  /**
   * IS THE ANSWER ON SCREEN THE WHOLE ELIGIBLE SET?
   *
   * Only when nothing was cut and nothing is narrowing it. It decides one thing: whether an
   * inspector's ABSENCE from the eligible list proves they are no longer eligible. Under a truncated
   * or searched answer it proves nothing at all, and marking somebody barred when they are not is
   * exactly as misleading as failing to mark somebody who is.
   */
  const eligibleListIsComplete = !eligibleTruncated && !searchTerm;

  /**
   * Everyone the picker offers: the accounts the server's current answer holds, plus anybody who
   * already HOLDS an inspection row, plus anybody ticked from an earlier search.
   *
   * The second group is the load-bearing one. The PUT replaces the whole set, so an option that is
   * not rendered is a row the next Save silently deletes; an inspector barred by the access list
   * last week is exactly that person, and leaving them out would end their inspection as a side
   * effect of adding somebody unrelated. The third exists because the first is a search result
   * rather than the whole table: narrowing the list must never un-name an assignment the admin has
   * already made in this sitting. Both are pinned into view by `SearchableSelect`, which lifts
   * anything ticked above its own render cap for the same reason.
   */
  const inspectorOptions = useMemo(() => {
    const options: DropdownOption[] = [];
    const seen = new Set<string>();
    const offer = (id: string, label: string) => {
      if (!id || seen.has(id)) return;
      seen.add(id);
      options.push({ value: id, label });
    };
    for (const person of eligible ?? []) {
      offer(person.id, `${personLabel(person)} · ${roleLabel(person.role)}`);
    }
    for (const row of inspectors ?? []) {
      offer(
        row.userId,
        `${personLabel(row)} · ${roleLabel(row.role)}${
          eligibleListIsComplete ? " — inspecting, no longer eligible" : " — inspecting"
        }`
      );
    }
    for (const id of selected) {
      const person = known.get(id);
      if (person) offer(person.id, `${personLabel(person)} · ${roleLabel(person.role)}`);
    }
    return options;
  }, [eligible, inspectors, selected, known, eligibleListIsComplete]);

  /* ── Unsaved state ──────────────────────────────────────────────────────── */

  const dirty = useMemo(() => {
    if (selected.length !== baseline.length) return true;
    const held = new Set(baseline);
    return selected.some((id) => !held.has(id));
  }, [selected, baseline]);

  const overCap = selected.length > MAX_DESIGN_WORKSHOP_INSPECTORS;

  const searchNotice = searching && searchTerm
    ? "Searching…"
    : eligibleInspectorNotice({
        truncated: eligibleTruncated,
        offered: eligible?.length ?? 0,
        searched: Boolean(searchTerm)
      });
  const searchNoticeId = useId();

  /**
   * What the picker says when it has no rows to draw — two different facts.
   *
   * "No account holds the Inspector / Reviewer tier yet" is a statement about the repository and is
   * only true of an unsearched empty answer. Saying it under a search term would be the
   * silent-emptiness bug in one sentence: the reader mistyped a surname and is told the repository
   * has nobody in it.
   */
  const pickerEmptyLabel = searching
    ? "Searching…"
    : searchTerm
      ? "No Inspector / Reviewer account matches that search."
      : "No account holds the Inspector / Reviewer tier yet";

  const labelById = useMemo(
    () => new Map(inspectorOptions.map((option) => [option.value, option.label])),
    [inspectorOptions]
  );
  const added = useMemo(() => selected.filter((id) => !baseline.includes(id)), [selected, baseline]);
  const removed = useMemo(() => baseline.filter((id) => !selected.includes(id)), [baseline, selected]);

  /**
   * The sentence the live region carries — the RESULTING count, plus what is still unsent.
   *
   * The multi-select has an `aria-live` of its own, but it lives inside a portalled panel that
   * unmounts the moment the picker closes, so a reader who ticks two names and closes the panel would
   * hear nothing about where the workshop now stands. This region is rendered from the first paint,
   * empty or not, because assistive technology only announces mutations inside a region that already
   * existed.
   */
  const announcement = !workshopId
    ? ""
    : dirty
      ? `${selected.length} inspector${selected.length === 1 ? "" : "s"} selected, not yet saved. ${added.length} to add, ${removed.length} to remove.`
      : selected.length === 0
        ? "This workshop is not under inspection. Nothing unsaved."
        : `${selected.length} inspector${selected.length === 1 ? "" : "s"} assigned to this workshop. Nothing unsaved.`;

  async function save() {
    if (!workshopId || overCap) return;
    setSaving(true);
    setSaveError(null);
    try {
      const result = await putDesignWorkshopInspectors(workshopId, selected);
      const rows = result.inspectors ?? [];
      setInspectors(rows);
      // The ANSWER becomes the baseline, not what was sent: another admin may have changed the set
      // between this page loading and Save being pressed, and treating our own payload as the truth
      // would leave the screen showing a membership nobody has.
      const held = rows.map((row) => row.userId);
      setBaseline(held);
      setSelected(held);
      toast({
        title: held.length
          ? `${held.length} inspector${held.length === 1 ? "" : "s"} assigned to this workshop`
          : "This workshop is not under inspection",
        description: held.length
          ? "They can read every stage of it and change nothing."
          : "Nobody is assigned to inspect it. The designers who run it are unaffected.",
        tone: "success"
      });
    } catch (err) {
      setSaveError(describeFailure(err, "Unable to save who is inspecting this workshop"));
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="panel p-5" data-design-workshop-inspectors>
      <div className="flex items-center gap-2.5">
        <span className="grid h-8 w-8 place-items-center rounded-md bg-purple-950 text-purple-100">
          <ScanEye className="h-4 w-4" aria-hidden />
        </span>
        <div>
          <h2 className="font-display font-bold text-ink-900">Inspection of a design workshop</h2>
          <p className="text-xs leading-5 text-ink-500">
            Who may read one design &amp; prototype workshop in order to inspect and review it.
          </p>
        </div>
      </div>

      <p className="mt-3 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs leading-5 text-ink-500">
        An inspection is READ-ONLY: an Inspector / Reviewer assigned here can open every stage of this
        workshop and change none of it. Only an admin decides who inspects what — the designers who run
        a workshop have no say in who examines it, and cannot be its inspector themselves.
      </p>

      {featureMissing ? (
        <p className="mt-3 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-5 text-amber-800">
          This repository does not offer design workshop inspections yet. The controls below are hidden rather than
          shown doing nothing — nobody has been assigned or unassigned, and every workshop is unaffected.
        </p>
      ) : null}

      {loadError ? (
        <div className="mt-3 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">{loadError}</div>
      ) : null}

      {featureMissing ? null : (
        <>
          <div className="mt-4">
            <FieldBlock label="Design workshop">
              <Dropdown
                // Filters the screen it sits on, so focus stays on the control being adjusted
                // rather than jumping to the next field.
                advanceOnSelect={false}
                ariaLabel="Design workshop"
                /*
                  THE SERVER'S SEARCH, NOW IN THE PANEL'S OWN BOX. It was a `SearchInput` mounted
                  above the picker with `searchable={false}` underneath it — the only arrangement
                  available before the primitive could hand a term out, and it cost this control the
                  panel's diacritic folding, its live region and its three-way empty arm while
                  putting two boxes on a panel that asks about two different tables. `serverQuery`
                  forces the box on, BYPASSES the local filter pass — `options` already IS the answer
                  to this term, and filtering it again would drop rows the server matched on
                  `workshopCode`, which the label deliberately does not show — and makes the empty
                  line three-way: pending, matched-nothing-on-the-server, nothing-here-at-all.

                  THE LENGTH CAP IS THIS PANEL'S OWN AND NOT A MIRROR: `list_design_workshops`
                  declares a bare `search: str | None` with no `max_length`, unlike the two
                  inspection endpoints, so nothing would refuse a long paste — it would simply run a
                  very long `ILIKE` that matches nothing. Borrowing the inspection endpoints' number
                  keeps one bound on this panel rather than two, and it is stated as a borrowing so
                  nobody reads it as a rule the server enforces.

                  `truncated` is deliberately absent — see the note beside `workshopCut`.
                */
                serverQuery={{
                  value: workshopSearch,
                  onChange: (next) => setWorkshopSearch(next.slice(0, ELIGIBLE_INSPECTOR_SEARCH_MAX)),
                  pending: workshopsSearching
                }}
                emptyLabel={workshopEmptyLabel(workshops, workshopVoice)}
                onChange={setWorkshopId}
                options={workshopSet.options}
                placeholder="Select a design workshop"
                value={workshopId}
              />
            </FieldBlock>
          </div>

          {/* Every row the fetch left out is counted and explained, in `selectFilter.ts`'s words so
              that this line and the panel's own footer cannot describe one cut two ways. A selector
              that quietly stops is indistinguishable from a repository with nothing in it. */}
          {workshopCut ? <p className="mt-2 text-xs leading-5 text-ink-500">{workshopCut}</p> : null}
          {/* WHICH OF THE FOUR EMPTY STATES THIS IS — the read failed, the device never had the
              list, no workshop is open to this account, or the repository is empty. They have four
              different next moves and used to have one sentence. `aria-live` because in three of
              them the trigger is not somewhere a reader can land. */}
          {workshopNotice ? (
            <p className="mt-2 text-xs leading-5 text-ink-500" aria-live="polite">
              {workshopNotice}
            </p>
          ) : null}

          {!workshopId ? (
            <p className="mt-4 text-sm text-ink-500">Pick a design workshop to see who is inspecting it.</p>
          ) : inspectors === null ? (
            <p className="mt-4 text-sm text-ink-500">Loading…</p>
          ) : (
            <>
              <div className="mt-4">
                <FieldBlock
                  label="Inspectors assigned to this workshop"
                  hint={
                    <p className="text-xs leading-5 text-ink-500">
                      Only accounts holding the Inspector / Reviewer tier are offered, and only those the platform
                      access list still admits — assigning somebody who cannot sign in would leave this screen saying
                      they are inspecting while they are shown a refusal at the door. Unticking somebody ends their
                      inspection when you save. At most {MAX_DESIGN_WORKSHOP_INSPECTORS} accounts.
                    </p>
                  }
                >
                  <div className="grid gap-2">
                    <SearchInput
                      onChange={(next) => setSearch(next.slice(0, ELIGIBLE_INSPECTOR_SEARCH_MAX))}
                      placeholder="Search Inspector / Reviewer accounts by name or email"
                      value={search}
                    />
                    {/* At most ONE line here, ever: what the search is doing, or the single sentence
                        that says the list is incomplete. Nothing at all when the list is whole. */}
                    {searchNotice ? (
                      <p className="text-xs leading-5 text-ink-500" id={searchNoticeId}>
                        {searchNotice}
                      </p>
                    ) : null}
                    <MultiSelectDropdown
                      ariaLabel="Inspectors assigned to this workshop"
                      // Pointed at the notice only while it is on screen: `aria-describedby` naming
                      // an id that is not in the document is worse than naming nothing.
                      capHint="Use the search box above to reach the rest — it asks the repository, so it sees every eligible account."
                      confirmLabel="Done"
                      describedBy={searchNotice ? searchNoticeId : undefined}
                      emptyLabel={pickerEmptyLabel}
                      onChange={setSelected}
                      options={inspectorOptions}
                      placeholder="Select one or more inspectors"
                      searchable={false}
                      values={selected}
                    />
                  </div>
                </FieldBlock>
              </div>

              {/* THE CAP IS STATED BEFORE THE SAVE, not discovered from a 422. Worded, not merely
                  tinted, and Save is disabled while it holds — the server would refuse the whole
                  call, so letting it go out would cost a round trip to say what is already known. */}
              {overCap ? (
                <p className="mt-3 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-5 text-amber-800">
                  {selected.length} accounts are selected and a workshop may have at most{" "}
                  {MAX_DESIGN_WORKSHOP_INSPECTORS}. Untick {selected.length - MAX_DESIGN_WORKSHOP_INSPECTORS} of them to
                  save.
                </p>
              ) : null}

              {/* What is currently SAVED, spelled out — the picker's trigger says "2 selected",
                  which is the pending answer and not the state of the repository. */}
              <div className="mt-3">
                {inspectors.length === 0 ? (
                  <p className="text-sm text-ink-500">
                    This workshop is not under inspection. Nobody has been assigned to review it — and unlike the
                    designers who can see it, there is nobody holding an inspection some other way.
                  </p>
                ) : (
                  <ul className="grid gap-1.5">
                    {inspectors.map((row) => (
                      <li className="flex flex-wrap items-baseline gap-x-2 text-sm text-ink-700" key={row.userId}>
                        <span className="font-medium text-ink-900">{personLabel(row)}</span>
                        <span className="text-xs text-ink-500">
                          {row.email}
                          {row.role ? ` · ${roleLabel(row.role)}` : ""}
                          {row.assignedAt ? ` · inspecting since ${formatDateTime(row.assignedAt)}` : ""}
                        </span>
                        {removed.includes(row.userId) ? (
                          <span className="rounded-full border border-error-600/25 bg-error-100 px-2 py-0.5 text-[0.6875rem] font-medium text-error-600">
                            inspection ends on save
                          </span>
                        ) : null}
                      </li>
                    ))}
                  </ul>
                )}
                {added.length ? (
                  <p className="mt-2 text-xs leading-5 text-ink-500">
                    Will be assigned on save: {added.map((id) => labelById.get(id) ?? "Unknown user").join(", ")}.
                  </p>
                ) : null}
              </div>

              {/* Immediately above the buttons: a refusal rendered five sections away from the
                  control that caused it reads as a button that did nothing. */}
              {saveError ? (
                <div className="mt-3 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">
                  {saveError}
                </div>
              ) : null}

              <div className="mt-3 flex flex-wrap items-center gap-2">
                <button className="field-button" disabled={!dirty || saving || overCap} onClick={save} type="button">
                  <Save className="h-4 w-4" aria-hidden />
                  {saving ? "Saving…" : "Save who inspects this"}
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
                {dirty ? (
                  <span className="text-xs text-amber-800">
                    {added.length + removed.length} unsaved change{added.length + removed.length === 1 ? "" : "s"}
                  </span>
                ) : null}
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
