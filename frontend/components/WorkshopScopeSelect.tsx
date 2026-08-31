"use client";

/**
 * THE ONE WORKSHOP SCOPE CONTROL, shared by every screen that draws a conclusion from workshops:
 * the questionnaire's completion matrix, the consolidated questionnaire (index and document), and the
 * map.
 *
 * WHY IT IS ONE COMPONENT AND NOT THREE. Those screens answer three different questions about the same
 * unit of fieldwork, and a researcher moves between them while holding one thought — "what came out of
 * last week's workshop". If each screen grew its own picker they would disagree about the default, about
 * whether "all" is a selection or an absence, and about whether records with no workshop are in or out.
 * Any one of those disagreements makes two screens report different numbers for the same question, with
 * nothing on either screen to say which is right. So the vocabulary lives here once, and the wire format
 * it produces is the SAME `workshopIds` parameter `services/record_filters.resolve_workshop_ids` parses
 * on the server.
 *
 * THE THREE STATES, and why "all" is an empty array rather than every id:
 *
 *   []                         ALL workshops. Sends no parameter at all, which is what the server reads
 *                              as "do not filter". Listing every id instead would silently exclude any
 *                              workshop created after the page loaded, and would break the moment the
 *                              list outgrew one page.
 *   ["w1", "w2"]               Those workshops.
 *   [..., UNASSIGNED_WORKSHOP] Also (or only) records that are not linked to a workshop. Without this a
 *                              scope of "every workshop" would quietly drop everything filed before
 *                              workshops existed, and nothing on screen would say so.
 *
 * THE DEFAULT IS THE MOST RECENT WORKSHOP, not "all". That is the requirement and it is also the right
 * default: these screens are read during and just after a workshop, and a matrix showing every artisan
 * ever recorded against every interview ever taken is not the question anybody opened it to ask. "All
 * records" is one click away and says so.
 */

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { CalendarRange, Layers } from "lucide-react";

import { CappedListNotice } from "@/components/data/CappedListNotice";
import { listCut, type ListCut } from "@/components/data/cappedList";
import { MultiSelectDropdown } from "@/components/ui/Dropdown";
import { listResource } from "@/lib/api";
import type { Workshop } from "@/lib/types";
import {
  WORKSHOP_OPTION_PAGE_SIZE,
  deviceLooksOffline,
  fieldWorkshopOptions,
  workshopEmptyLabel,
  workshopListNotice,
  type WorkshopListState,
  type WorkshopListVoice
} from "@/lib/workshopOptions";
import { sortWorkshopsByOccurrence } from "@/components/forms/WorkshopSelect";

/**
 * The reserved id meaning "records not linked to any workshop". Byte-for-byte
 * `record_filters.UNASSIGNED_WORKSHOP` on the server and `WorkshopScope.UNASSIGNED` on Android — a
 * reserved word rather than an empty string, because an empty string is what a blank field sends and
 * "the user chose nothing" must not mean "show me only the orphans".
 */
export const UNASSIGNED_WORKSHOP = "none";

/**
 * The heading the sentinel row sits under, and the reason it needs one at all.
 *
 * `groupRows` draws UNGROUPED rows FIRST — deliberately, so a caller's ordering governs — so an
 * ungrouped sentinel under a grouped list would be lifted to the very top of the panel, above
 * "Open", where it reads as the first and most obvious workshop to pick. §2.4's all-or-nothing rule
 * is the general form of this: if any row needs a heading, every row gets one, or the bare ones
 * render as a category nobody named. One heading over one synthetic row is exactly right here —
 * what it distinguishes is that this row is not a workshop.
 */
const UNASSIGNED_GROUP = "Records with no workshop";

/**
 * WHAT THE READ FAILED TO SAY, IN A FILTER'S VOICE RATHER THAN A FORM FIELD'S — THE CLAUSE ONLY.
 *
 * The reason a clause is needed at all is unchanged from the day this was written. `workshopListNotice`'s
 * sentences close on *"Nothing you have entered is at risk — this record can be saved without it"*
 * and *"a stored copy of who may file where reads a revoked grant as a grant"*, both of which are
 * about SAVING A RECORD against a workshop. Nothing is saved here. This control narrows a screen,
 * and the fact a reader needs when its list did not arrive is what the screen is therefore showing —
 * which is everything, because "no workshop chosen" is spelled as an absence and an absence is what
 * a failed load leaves behind.
 *
 * Saying nothing was the shipping behaviour and is the one unacceptable option: the scope silently
 * widened from "the most recent workshop" to "the whole repository", on five screens, with the
 * picker looking merely short.
 *
 * ── WHAT CHANGED ON 2026-08-31: THE OPENING IS NO LONGER A SECOND COPY ──────────────────────────
 *
 * This used to be two WHOLE sentences written out here — opening included — with a spec asserting
 * that the duplicated opening still matched `workshopListNotice`'s byte for byte. `WorkshopListVoice`
 * grew a `reassurance` field for exactly this shape and its own note recorded the refusal to migrate:
 * *"WorkshopScopeSelect is deliberately NOT migrated onto this: the spec above pins its literal, and
 * a screen whose scope silently widened is precisely the wrong place to accept an incidental copy
 * change."* That was the right call while the two strings were being compared by hand and nobody had
 * checked them against each other; it is not a rule, and holding to it is what the module's own
 * header calls *"six label shapes for one question, one layer down: a shared opening kept in step by
 * hand across N copies."*
 *
 * SO THE OPENING NOW HAS ONE OWNER AND THE ONLINE SENTENCE IS BYTE-IDENTICAL TO WHAT IT WAS —
 * `workshopListNotice(list, voice)` with this clause as `reassurance` composes exactly the string
 * that used to be the literal above, which is what makes the migration checkable rather than
 * merely tidy (`e2e/dropdown-sweep-unit.spec.ts` now asserts the composition, not the copy).
 *
 * THE OFFLINE ARM APPENDS IT INSTEAD OF PASSING IT, and that is not an oversight. `reassurance`
 * closes the ONLINE failure only, by its own declaration; the offline sentence ends on R6's reason
 * for never keeping a workshop list on the device, which is `accessList`'s to decide and is true of
 * this table whichever control is reading it. Setting `accessList: false` here would shorten the
 * line by telling the module this is not one of the two workshop tables, which it is. So the clause
 * is added after, and the reader gets both facts in one paragraph.
 */
const SCOPE_SHOWS_EVERYTHING =
  "Nothing is hidden by it — with no workshop chosen, this screen is showing every record.";

export type WorkshopScope = {
  /** The selected ids; `[]` means every workshop. May contain {@link UNASSIGNED_WORKSHOP}. */
  workshopIds: string[];
  setWorkshopIds: (next: string[]) => void;
  /** Every workshop, most recent occurrence first. */
  workshops: Workshop[];
  /**
   * WHAT THE READ ANSWERED — three states, because a failed read and an empty table are different
   * facts with different next moves and this control used to spell both `setWorkshops([])`.
   *
   * The screen behind still falls through to every record on a failure, which is a complete and
   * honest answer to "what am I looking at"; what was missing is that nobody was told the narrowing
   * they asked for never happened. See {@link SCOPE_SHOWS_EVERYTHING}.
   */
  list: WorkshopListState<Workshop>;
  /**
   * What the picker is NOT showing, or null when it holds every workshop.
   *
   * 196 workshop rows against a page of {@link WORKSHOP_OPTION_PAGE_SIZE}, drawn on five screens
   * including `/search` and `/map`, and until this pass it said nothing of any kind — the sharpest
   * single instance of R4 in the app. The panel's box filters what it was handed, so the sentence
   * has to say that too; `cappedListNotice` words it.
   */
  cut: ListCut | null;
  loading: boolean;
  /** True until the default selection has been applied, so a screen can hold its first fetch. */
  settling: boolean;
  /** The value to spread into `buildQuery` / pass to `apiFetch`. Undefined when scoping to all. */
  queryValue: string | undefined;
  /** One line naming what is in scope, for a panel subtitle. */
  summary: string;
};

/**
 * Loads the workshops and owns the selection.
 *
 * `defaultToMostRecent` exists for the one screen where it would be wrong — a document that is
 * ordinarily read whole. Pass false there and the scope starts at "all".
 */
export function useWorkshopScope({
  defaultToMostRecent = true,
  initialWorkshopIds
}: {
  defaultToMostRecent?: boolean;
  /** Seeds the selection from a URL, which then wins over the most-recent default. */
  initialWorkshopIds?: string[];
} = {}): WorkshopScope {
  const [list, setList] = useState<WorkshopListState<Workshop>>({ kind: "loading" });
  const [cut, setCut] = useState<ListCut | null>(null);
  const [loading, setLoading] = useState(true);
  const [workshopIds, setWorkshopIdsState] = useState<string[]>(initialWorkshopIds ?? []);
  // The default is applied ONCE, and only if the user has not already chosen. Without the ref a
  // re-render that re-ran the effect would drag the selection back to the most recent workshop under
  // somebody who had just picked two others.
  const touched = useRef(Boolean(initialWorkshopIds?.length));
  const [settling, setSettling] = useState(!initialWorkshopIds?.length && defaultToMostRecent);

  useEffect(() => {
    let cancelled = false;
    // ONE NUMBER FOR THE FETCH AND THE RENDER. This asked for 100 into a control that draws 80, so
    // twenty rows were dropped in a band where nothing on screen said anything at all: the page
    // thought it held a hundred, the panel drew eighty, and neither number reached the reader.
    listResource<Workshop>("/workshops", { pageSize: WORKSHOP_OPTION_PAGE_SIZE })
      .then((result) => {
        if (cancelled) return;
        // The list route already orders by occurrence, but it is re-sorted here for the same reason
        // WorkshopSelect re-sorts: the client must not depend on the server's order to decide which
        // workshop is "the most recent", because getting that wrong picks the wrong default silently.
        const ordered = sortWorkshopsByOccurrence(result.items);
        setList({ kind: "ok", rows: ordered, total: result.total });
        setCut(listCut(result, "workshops"));
        if (defaultToMostRecent && !touched.current && ordered.length > 0) {
          setWorkshopIdsState([ordered[0].id]);
        }
      })
      .catch(() => {
        // A picker that could not load its options must not also silence the screen behind it: the
        // selection stays `[]` and the screen falls through to every record, which is a complete and
        // honest answer to what is on it.
        //
        // WHAT CHANGED IS THAT IT NO LONGER DOES SO SILENTLY, and the distinction is the whole
        // defect. `setWorkshops([])` made a failed read indistinguishable from an empty table, so a
        // screen that was asked to open on last week's workshop opened on the entire repository with
        // a picker that looked merely short — and "all workshops" over `[]` is the widest scope this
        // control can express, reached by accident, on the map and the search page.
        if (!cancelled) setList({ kind: "failed" });
      })
      .finally(() => {
        if (cancelled) return;
        setLoading(false);
        setSettling(false);
      });
    return () => {
      cancelled = true;
    };
  }, [defaultToMostRecent]);

  const setWorkshopIds = useCallback((next: string[]) => {
    touched.current = true;
    setWorkshopIdsState(next);
  }, []);

  // The rows, for the callers that name a workshop rather than list one. Derived from `list` so
  // there is one answer on the page and not two that can disagree about whether the read succeeded.
  const workshops = useMemo(() => (list.kind === "ok" ? [...list.rows] : []), [list]);

  const queryValue = useMemo(
    // Comma-joined, which `resolve_workshop_ids` accepts alongside repeated parameters. Undefined
    // rather than "" when scoping to all, so `buildQuery` drops the key entirely — the server reads an
    // ABSENT parameter as "every workshop", and an empty one would be indistinguishable from a bug.
    () => (workshopIds.length ? workshopIds.join(",") : undefined),
    [workshopIds]
  );

  const summary = useMemo(() => {
    if (!workshopIds.length) return "Every workshop, and records not linked to one.";
    const named = workshopIds
      .filter((id) => id !== UNASSIGNED_WORKSHOP)
      .map((id) => workshops.find((workshop) => workshop.id === id)?.title?.trim() || "Untitled workshop");
    const includesUnassigned = workshopIds.includes(UNASSIGNED_WORKSHOP);
    if (!named.length) return "Only records not linked to a workshop.";
    const list = named.length <= 3 ? named.join(", ") : `${named.slice(0, 3).join(", ")} and ${named.length - 3} more`;
    return `${list}${includesUnassigned ? ", plus records not linked to a workshop" : ""}.`;
  }, [workshopIds, workshops]);

  return { workshopIds, setWorkshopIds, workshops, list, cut, loading, settling, queryValue, summary };
}

/*
  `optionLabel` USED TO LIVE HERE — a second copy of `WorkshopSelect`'s `title · date`, kept in step
  by hand and by a comment asking the next reader to keep it that way. Both are now
  `fieldWorkshopOptions`: the title alone in `label`, and the place, the day and "Ended" in `hint`,
  which `selectFilter` searches as well as draws. One workshop reads one way because one function
  writes it, rather than because two functions were asked to agree.
*/

/**
 * The control. A multi-select over the workshops, an "All records" shortcut, and a line of text saying
 * what is currently in scope.
 *
 * `MultiSelectDropdown` is reused rather than reimplemented — it already floats its panel out of an
 * `overflow-hidden` ancestor, grows a search box past eight options, and is what every other
 * multi-select in the app looks like.
 *
 * The one thing it offers that this control refuses is its bulk button: this sentence used to end
 * "…offers a filter-aware Select all", which was true of the primitive and wrong for this control.
 * See `bulk={false}` below for why a FILTER may not have one.
 */
export function WorkshopScopeSelect({
  scope,
  label = "Workshops",
  className = "",
  /** Rendered under the control. Turn off where the parent panel already carries the sentence. */
  showSummary = true
}: {
  scope: WorkshopScope;
  label?: string;
  className?: string;
  showSummary?: boolean;
}) {
  const { workshopIds, setWorkshopIds, workshops, list, cut, loading } = scope;
  // Both sentences are named on the control rather than left to be found underneath it: an
  // incomplete list and a read that failed are facts a screen-reader user needs AT the picker.
  const baseId = useId();
  const cutNoteId = `${baseId}-cut`;
  const stateNoteId = `${baseId}-state`;

  const options = useMemo(
    () => [
      /*
        `group: true` so an ended workshop is drawn under "Ended" with the word in its hint — the
        heading is what stops one being picked by accident, and on a screen that reports numbers
        that matters as much as it does on a form. `offPage: "refuse"` because a filter has no
        "the record's own workshop" to recover: nothing here describes a record, and a multi-select
        can hold several off-page ids anyway, which a single-row parameter cannot express.
      */
      ...fieldWorkshopOptions(list, { group: true, offPage: { mode: "refuse" } }).options,
      // Last, and named as what it is. It is not a workshop, so it does not belong among them in the
      // reading order — but it has to be selectable, or a scope of "every workshop" silently drops
      // every record filed before workshops existed. See UNASSIGNED_GROUP for what keeps it last.
      //
      // The label is byte-identical to `NO_FIELD_WORKSHOP` on purpose — a record with no workshop
      // should read the same way wherever a designer meets it — and is deliberately NOT that
      // constant: this is a filter VALUE, `UNASSIGNED_WORKSHOP`, and the constant is what "" means
      // on a form field. R1 is the reason they must not be wired together: a filter says
      // "everything" by ABSENCE, so it may never grow a none row, and importing the none row's
      // string here is the first step towards somebody importing the row.
      { value: UNASSIGNED_WORKSHOP, label: "Not linked to a workshop", group: UNASSIGNED_GROUP }
    ],
    [list]
  );

  /**
   * WHY THIS CONTROL SAYS NOTHING ABOUT ITS OWN FAILURE IN THE PANEL, and everything about it
   * underneath.
   *
   * `emptyLabel` is unreachable here: the sentinel row is always in `options`, so the list is never
   * empty and the panel's empty arm never draws. It is still passed correctly rather than left as
   * the literal "No options", because the day somebody makes the sentinel conditional is not the day
   * to discover that the fallback was a claim about the repository.
   */
  const online = !deviceLooksOffline();
  /*
    ONE VOICE FOR ALL FOUR STATES. `scoped: false` because this request carries no `accessibleOnly`:
    it is a READ scope over the whole repository, so an empty answer means "nothing has been
    recorded", never "nothing is open to you", and sending a reader to an administrator over an empty
    table wastes a day. `reassurance` is what the online failure closes on — see the constant.
  */
  const voice: WorkshopListVoice = {
    table: "field",
    scoped: false,
    online,
    reassurance: SCOPE_SHOWS_EVERYTHING
  };
  const shared = workshopListNotice(list, voice);
  // The offline failure is the one arm `reassurance` does not reach, and it is the arm where the
  // widened scope is easiest to miss — a picker that looks merely short on a phone with no signal.
  const notice = list.kind === "failed" && !online ? `${shared} ${SCOPE_SHOWS_EVERYTHING}` : shared;

  const all = workshopIds.length === 0;

  return (
    <div className={`grid min-w-0 content-start gap-1.5 ${className}`}>
      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
        <span className="field-label inline-flex items-center gap-1.5">
          <CalendarRange className="h-3.5 w-3.5" aria-hidden />
          {label}
        </span>
        <div className="flex items-center gap-2">
          <button
            type="button"
            aria-pressed={all}
            onClick={() => setWorkshopIds([])}
            className={`rounded-full border px-2.5 py-0.5 text-[11px] font-medium transition-colors ${
              all
                ? "border-purple-700 bg-purple-700 text-white"
                : "border-line-200 bg-surface-50 text-ink-700 hover:border-purple-300 hover:text-purple-700"
            }`}
          >
            All records
          </button>
          {!all && workshops.length > 0 ? (
            <button
              type="button"
              onClick={() => setWorkshopIds([workshops[0].id])}
              className="text-[11px] font-semibold text-purple-700 hover:underline"
            >
              Most recent only
            </button>
          ) : null}
        </div>
      </div>

      {/*
        `searchable` because the options are workshops — records, not a vocabulary — so their number
        says how much work has been filed and nothing about this control. This is the shared scoping
        picker: it appears on several screens, and with the filter box left to the option count it
        would be a searchable control on a mature deployment and a plain menu on a fresh one, which
        is the one difference a reader cannot learn from either.
      */}
      <MultiSelectDropdown
        values={workshopIds}
        onChange={setWorkshopIds}
        options={options}
        searchable
        /*
          NO "Select all N" BUTTON, AND THIS IS THE FILE THE RULE COMES FROM.

          `MultiSelectDropdown` builds that button unconditionally, and wired to a filter it produces
          the one state R1 forbids: every row ticked and no row ticked, both meaning "everything",
          with no way left to tell a default from a deliberate choice — and no way to tell either of
          them from a scope somebody assembled by hand that happens to be complete. It would also be
          a lie about the corpus: "all" is 80 of 196 rows, so a ticked-everything scope EXCLUDES the
          116 the page never held, which is the opposite of what the words say. Everything is said
          here by the "All records" button above, which sets `[]` — an absence, sent as no parameter
          at all, which `resolve_workshop_ids` reads as "do not filter".
        */
        bulk={false}
        ariaLabel="Choose which workshops to include"
        placeholder={loading ? "Loading workshops…" : all ? "All workshops" : "Select workshops"}
        emptyLabel={workshopEmptyLabel(list, voice)}
        describedBy={`${cutNoteId} ${stateNoteId}`}
      />

      {/*
        WHAT THIS LIST LEFT OUT — the sentence this control has never drawn, on 196 workshop rows
        over a page of 80, on five screens.

        `cappedListNotice`'s wording and not the panel vocabulary's, and the difference is which box
        each names. This control's filter box has NOT been pointed at the server's `search`: the
        options also feed a summary line that names the chosen workshops, and a narrowed answer would
        blank those names while a selection that fell out of it could no longer be unticked. So the
        box sifts the 80 rows in hand, and the sentence says exactly that rather than telling
        somebody to keep typing at rows the request never fetched.
      */}
      <CappedListNotice cuts={[cut]} id={cutNoteId} />
      {/*
        …AND WHAT HAPPENED TO THE READ. A separate paragraph rather than a second `cuts` entry:
        `CappedListNotice` accepts a pre-worded string only for a sentence one of `cappedList.ts`'s
        own deciders wrote, and it drops its `id` when it is handed more than one line — which would
        take the truncation sentence off `aria-describedby` to make room for this one.
      */}
      {notice ? (
        <p id={stateNoteId} className="text-xs leading-5 text-ink-500">
          {notice}
        </p>
      ) : null}

      {showSummary ? (
        <p className="flex items-start gap-1.5 text-[11px] leading-4 text-ink-500">
          <Layers className="mt-px h-3 w-3 shrink-0" aria-hidden />
          <span>{scope.summary}</span>
        </p>
      ) : null}
    </div>
  );
}

/**
 * The scope as URL parameters, so a filtered view can be linked to and come back the same.
 *
 * The key is `workshopIds`, spelled exactly as the API spells it, so a link can be pasted into either
 * and mean the same thing.
 */
export function workshopScopeLinkParams(workshopIds: string[]) {
  return { workshopIds: workshopIds.length ? workshopIds.join(",") : undefined };
}

/** The inverse: read a selection out of a URL. Unknown ids are kept — the server judges them. */
export function workshopScopeFromSearchParams(params: URLSearchParams): string[] {
  return (params.get("workshopIds") ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
}
