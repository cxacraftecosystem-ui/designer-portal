"use client";

import { useEffect, useId, useRef, useState, type ReactNode, type RefObject } from "react";
import { CalendarRange, FilterX, Search, X } from "lucide-react";

import { CappedListNotice } from "@/components/data/CappedListNotice";
import { CUT_NOTICE_LIVE_REGION } from "@/components/data/cappedList";
import { DateField } from "@/components/forms/DateTimeField";
import { Dropdown, MultiSelectDropdown } from "@/components/ui/Dropdown";
import {
  ACCESS_INSTITUTION_NOTE,
  ACCESS_STATUS_OPTIONS,
  DESIGNER_ROLE_HINT,
  DESIGNER_STANDING_OPTIONS,
  RANGE_OPTIONS,
  ROSTER_LABELS,
  clearRosterFilters,
  dateFieldOptions,
  hasActiveRosterFilters,
  institutionCutNotice,
  institutionOptions,
  roleMatchCutNotice,
  roleOptions,
  type AccessStatusToken,
  type DesignerStandingToken,
  type RosterDateField,
  type RosterFilters,
  type RosterKind
} from "@/components/admin/rosterFilters";
import type { RangeId } from "@/components/search/SearchFilters";

/**
 * THE FILTER ROW BOTH ROSTER SCREENS MOUNT — `/admin/access` and `/admin/designers`.
 *
 * It renders the search box, the multi-selects, the date-field picker, the range and the clear-all
 * button. It renders NO ROWS and it holds no page: it hands the page a new {@link RosterFilters} and
 * the page re-requests. That division is rule (iv) — filtering is server-side — and it is why there
 * is not a single `.filter()` in this file. A client-side box over a server-truncated page answers
 * "No matches" about records that exist, and `admin/access/page.tsx`'s own header records four
 * closed defects of exactly that shape.
 *
 * ── ONE COMPONENT FOR TWO SCREENS ───────────────────────────────────────────────────────────────
 *
 * Not for the code. For the WORDS. An admin moves between these two lists holding one thought — "why
 * can this person not sign in" — and the two screens already word the same filter two ways and
 * announce both of them, to a screen reader, as the same three words ("Filter by standing") over two
 * controls with two different vocabularies. Every sentence in the row is declared once in
 * `components/admin/rosterFilters.ts` and rendered here; where the two rosters genuinely differ
 * (access has no institution; the designer roster's standing is one enum, not a set) the difference
 * is a `kind` test at the control, not a second file.
 *
 * ── WHAT IS DELIBERATELY NOT HERE ───────────────────────────────────────────────────────────────
 *
 * **The pager.** A filter change invalidates a page offset — the rows at `OFFSET 40` are not the
 * rows that were there a moment ago — so every `onChange` must reset the page to 1, and that is the
 * page's state to reset. The prop says so.
 *
 * **The fetches.** The institution vocabulary and the roster rows are both read by the page, which
 * already owns a generation counter for exactly this reason (`lib/api.apiFetch` carries no
 * `AbortSignal`, so a late answer has to be IGNORED rather than cancelled). A control that fetched
 * on its own would be a second, ungoverned race against the same screen.
 *
 * **Any claim about an empty result.** `institutionsEmptyLabel` is the caller's, because this
 * component cannot know whether an empty institution list means "none recorded" or "the read
 * failed", and printing a repository claim from a read that may have failed is the defect
 * `cappedList.searchCutNotice` refuses to compose for the same reason.
 *
 * ── THE FOUR RULES, WHERE THEY LAND IN THIS FILE ────────────────────────────────────────────────
 *
 * **(i) Empty means everything, by absence.** Every `MultiSelectDropdown` below passes
 * `bulk={false}`. That is not tidiness: the "Select all N" button manufactures a state where all
 * ticked and none ticked both mean "everything", and a filter with two spellings for one state
 * cannot tell a default from a deliberate choice. It goes with `confirmOnSelect={false}` on the
 * same control — a filter adjusts the list in place, so there is nothing to confirm and nowhere to
 * advance to. (The multi has no `advanceOnSelect`; `confirmOnSelect={false}` removes the only thing
 * that moved focus, which is the same fix `advanceOnSelect={false}` is on the single-selects.)
 *
 * **(ii) Suspended and rejected rows stay listed by default.** No control here opens narrowed.
 * There is no "hide suspended" toggle at all, and `ACCESS_STATUS_OPTIONS`' header says why.
 *
 * **(iii) Every cap is stated, with the number.** Two cuts can happen behind these controls and
 * neither shows in the rows: the institution vocabulary stops at a server cap, and the role filter
 * stops after a bounded account read. Both print, both inside a live region that is mounted before
 * it has anything to say.
 *
 * **(iv) Filtering is server-side.** See above.
 */

/** 300 ms — this app's debounce, the same number `SearchableSelect`'s server-query contract names. */
const SEARCH_DEBOUNCE_MS = 300;

export type RosterFilterBarProps = {
  kind: RosterKind;
  filters: RosterFilters;
  /**
   * Every change — a tick, a date, a keystroke that has settled — arrives here as a whole new
   * filter object.
   *
   * ⚠ RESET THE PAGER TO 1 IN THIS HANDLER, unconditionally. A narrowed or re-ordered list has
   * different rows at every offset, so staying on page 3 lands the reader somewhere arbitrary in a
   * list they just changed — and on a list that got shorter, past the end of it, which renders the
   * "nothing here" empty state over a roster that is not empty.
   */
  onChange: (next: RosterFilters) => void;
  /**
   * DESIGNER ROSTER ONLY — the served institution vocabulary from
   * `GET /designers/roster/institutions`.
   *
   * From the SERVER and not from the page of rows on screen. A picker built out of the current page
   * can only ever offer the institutions that page happened to contain, so an admin filtering for
   * one that is two pages down finds no row for it and reads that as "nobody is from there" — rule
   * (iv)'s failure wearing a picker.
   */
  institutions?: readonly string[];
  /** The endpoint's own `truncated` flag. Drives {@link institutionCutNotice}; see rule (iii). */
  institutionsTruncated?: boolean;
  /** True while that read is in flight, so the trigger can say so instead of reading "Every institution". */
  institutionsLoading?: boolean;
  /**
   * What the institution panel says when it has nothing to offer AT ALL — as against "No matches",
   * which is what it says when a query excluded everything.
   *
   * THE CALLER'S, AND NEVER COMPOSED HERE. "No institution has been recorded yet" is a claim about
   * the repository; "the list could not be read" is a claim about a request; and printing the first
   * over a read that actually failed is a lie dressed as an observation. Only the page knows which
   * happened. Left unset, the primitive's own neutral "No options" stands, which claims nothing.
   */
  institutionsEmptyLabel?: string;
  /**
   * The roster payload's `roleMatchTruncated`. See {@link roleMatchCutNotice} for what it means and
   * why the sentence is not `flagCutNotice`'s.
   *
   * PASS IT HERE RATHER THAN RENDERING IT BESIDE THE TABLE. The cut is caused by this control and is
   * invisible everywhere else on the screen, so it belongs under the control that caused it — and
   * two copies of one sentence in two places is how a reader learns that neither means much.
   */
  roleMatchTruncated?: boolean;
  /** The server's read limit, where the page has actually read one. Never guessed — see the notice. */
  roleMatchLimit?: number;
  /**
   * Fired when the box holds a term the server has not been asked about yet.
   *
   * Feed it to `searchCutNotice({ pending })`. Without it a debounced box prints `No designers match
   * "ravi"` over a request that has not answered, which is a statement about the repository made
   * from a query that has not run. Pass a stable callback (a `setState` setter is one).
   */
  onSearchPendingChange?: (pending: boolean) => void;
  className?: string;
};

export function RosterFilterBar({
  kind,
  filters,
  onChange,
  institutions = [],
  institutionsTruncated,
  institutionsLoading,
  institutionsEmptyLabel,
  roleMatchTruncated,
  roleMatchLimit,
  onSearchPendingChange,
  className = ""
}: RosterFilterBarProps) {
  const access = kind === "access";
  const ids = useId();
  const fromId = `${ids}-from`;
  const toId = `${ids}-to`;
  const roleHintId = `${ids}-role-hint`;
  const roleCutId = `${ids}-role-cut`;
  const institutionCutId = `${ids}-institution-cut`;

  /**
   * THE DRAFT TERM. `filters.search` is what the last request was made with; this is what is in the
   * box. They are two different facts and collapsing them is how a screen ends up saying "no rows
   * match X" about a term nobody has asked the server about yet.
   */
  const [draft, setDraft] = useState(filters.search);
  /** The last term this bar pushed UP, so a change arriving from outside can be told from its own. */
  const pushed = useRef(filters.search);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const searchRef = useRef<HTMLInputElement | null>(null);

  /**
   * The debounce reads the CURRENT props when it fires, through a ref written in an effect with no
   * dependency array.
   *
   * Not a dependency array on the timer: `filters` changes on every tick of every other control, so
   * a captured copy would be 300 ms stale and the settling keystroke would send the filters as they
   * were before the last click — silently undoing it. And not a ref written during render, because
   * a render can be discarded under concurrent rendering; this is the same treatment
   * `useLeaveGuard` gives its interceptor, for the same reason.
   */
  const latest = useRef({ filters, onChange });
  useEffect(() => {
    latest.current = { filters, onChange };
  });

  useEffect(() => {
    return () => {
      if (timer.current) clearTimeout(timer.current);
    };
  }, []);

  // A term that arrived from OUTSIDE — clear-all, a URL restored on mount, a colleague's pasted
  // link — has to reach the box. Its own pushes are excluded by the ref rather than by testing
  // focus: the box is legitimately focused while its own debounce lands, and a focus test there
  // would leave the field showing a term the list is no longer filtered by.
  useEffect(() => {
    if (filters.search === pushed.current) return;
    pushed.current = filters.search;
    setDraft(filters.search);
  }, [filters.search]);

  // Derived rather than fired from the handlers, so it cannot get out of step with what is on
  // screen: it is true exactly while the box and the last request disagree.
  const searchPending = draft.trim() !== filters.search.trim();
  useEffect(() => {
    onSearchPendingChange?.(searchPending);
  }, [onSearchPendingChange, searchPending]);

  function clearTimer() {
    if (timer.current) {
      clearTimeout(timer.current);
      timer.current = null;
    }
  }

  /** Send a term now. Reads the live filters through the ref — see {@link latest}. */
  function commitSearch(term: string) {
    clearTimer();
    if (term === pushed.current) return;
    pushed.current = term;
    latest.current.onChange({ ...latest.current.filters, search: term });
  }

  function typeSearch(next: string) {
    setDraft(next);
    clearTimer();
    timer.current = setTimeout(() => {
      timer.current = null;
      commitSearch(next);
    }, SEARCH_DEBOUNCE_MS);
  }

  /**
   * Change any other control — and land whatever is sitting in the search box in the SAME request.
   *
   * Without this, ticking a status while a keystroke is still settling fires two requests 300 ms
   * apart with two different filter sets, and the reader watches the list change twice for one
   * action. The frontend contract's rule for the same situation on the map is that debounced search
   * and clicked filters go through one timer so the page's generation guard is the only race
   * protection needed; this is that rule with the timer collapsed rather than shared.
   */
  function set(patch: Partial<RosterFilters>) {
    clearTimer();
    pushed.current = draft;
    onChange({ ...filters, search: draft, ...patch });
  }

  /**
   * The three labels that differ between the two rosters, bound ONCE each.
   *
   * A ternary written twice — once for the visible `<span>` and once for `ariaLabel` — is two
   * chances for the two to disagree, which is the failure the single-constant rule in
   * `ROSTER_LABELS` exists to make unspellable. One binding, two uses, and
   * `e2e/roster-filters-unit.spec.ts` reads the pairing structurally rather than by name.
   */
  const searchLabel = access ? ROSTER_LABELS.accessSearch : ROSTER_LABELS.designerSearch;
  const rolesLabel = access ? ROSTER_LABELS.accessRoles : ROSTER_LABELS.designerRoles;

  const roleCut = roleMatchCutNotice(access ? undefined : roleMatchTruncated, roleMatchLimit);
  const institutionRows = institutionOptions(institutions);
  const institutionCut = access
    ? ""
    : institutionCutNotice(institutionsTruncated, institutions.length);
  const filtered = hasActiveRosterFilters(kind, filters);

  return (
    // `role="search"` rather than a bare `<div>`: this is the one place on the page where the list
    // below is narrowed, and a landmark is how a screen-reader user reaches it without walking the
    // add-an-address form above it. Named, because an unnamed landmark is a place with no signpost.
    <section
      role="search"
      aria-label={access ? "Filter the allow-list" : "Filter the designer roster"}
      className={`mb-4 grid gap-3 ${className}`}
    >
      <div className="grid gap-2 sm:grid-cols-[1fr_18rem]">
        <RosterSearchBox
          inputRef={searchRef}
          value={draft}
          ariaLabel={searchLabel}
          placeholder={access ? "Search by email, name or note" : "Search by email, name or institution"}
          onChange={typeSearch}
          onSubmit={() => commitSearch(draft)}
          onClear={() => {
            setDraft("");
            commitSearch("");
          }}
        />

        {access ? (
          <ControlBlock label={ROSTER_LABELS.accessStatus}>
            <MultiSelectDropdown
              values={filters.status}
              onChange={(next) => set({ status: next as AccessStatusToken[] })}
              options={ACCESS_STATUS_OPTIONS}
              ariaLabel={ROSTER_LABELS.accessStatus}
              // What NOTHING TICKED means, read back on the trigger. This screen's widest default is
              // the whole point of it: an admin arrives because somebody cannot sign in, and the
              // REJECTED or SUSPENDED row that explains it is the one a tidier default would hide.
              placeholder="Everyone ever seen"
              // A closed four-word vocabulary. The count would not reach `SEARCH_THRESHOLD` today
              // either, but the rule is the kind of list and not its length.
              searchable={false}
              bulk={false}
              confirmOnSelect={false}
            />
          </ControlBlock>
        ) : (
          <ControlBlock label={ROSTER_LABELS.designerStanding}>
            <Dropdown
              value={filters.standing}
              onChange={(next) => set({ standing: next as "" | DesignerStandingToken })}
              options={DESIGNER_STANDING_OPTIONS}
              ariaLabel={ROSTER_LABELS.designerStanding}
              searchable={false}
              // A dropdown that filters the screen it sits on must not advance focus on select:
              // jumping away from the control being adjusted is wrong when the control IS the
              // adjustment. Both roster pages already pass this on their single-selects.
              advanceOnSelect={false}
            />
          </ControlBlock>
        )}
      </div>

      <div className={`grid gap-3 ${access ? "lg:grid-cols-2" : "lg:grid-cols-3"}`}>
        <ControlBlock
          label={rolesLabel}
          hint={
            access ? null : (
              // Not decoration: it says what the filter MEANS, which is not what its label implies.
              // A roster row whose account is an ADMIN is not gated by this roster at all, so
              // "role = Admin" here answers "which empanelled addresses belong to admins" and NOT
              // "which admins may sign in".
              <p id={roleHintId} className="text-[11px] leading-4 text-ink-500">
                {DESIGNER_ROLE_HINT}
              </p>
            )
          }
        >
          <MultiSelectDropdown
            values={filters.roles}
            onChange={(next) => set({ roles: next })}
            options={roleOptions(kind)}
            ariaLabel={rolesLabel}
            placeholder="Every tier"
            // EIGHT ROLES IS EXACTLY `SEARCH_THRESHOLD`, so left to the count this control would be
            // a plain list the day a tier is removed and a filter box the day one is added — the one
            // difference a reader cannot learn from either. It is a closed vocabulary; it stays a
            // list. Android's role picker overrides the same rule for the same reason.
            searchable={false}
            bulk={false}
            confirmOnSelect={false}
            describedBy={access ? roleCutId : `${roleHintId} ${roleCutId}`}
          />
          <div {...CUT_NOTICE_LIVE_REGION}>
            {/*
              MOUNTED EVEN WHILE SILENT. `CappedListNotice` returns null when there is nothing to
              say, and a live region created together with its first sentence announces nothing —
              which is the bug the form's cap notice shipped. The wrapper is always here; only the
              sentence comes and goes.
            */}
            <CappedListNotice id={roleCutId} cuts={[roleCut]} />
          </div>
        </ControlBlock>

        {access ? null : (
          <ControlBlock label={ROSTER_LABELS.designerInstitutions}>
            <MultiSelectDropdown
              values={filters.institutions}
              onChange={(next) => set({ institutions: next })}
              options={institutionRows}
              ariaLabel={ROSTER_LABELS.designerInstitutions}
              placeholder={institutionsLoading ? "Loading institutions…" : "Every institution"}
              emptyLabel={institutionsEmptyLabel}
              // The one searchable control in this row, and the rule is the kind of list rather than
              // its length: these are RECORDS — free text somebody typed into a roster row — so
              // there may be four of them on a new deployment and two hundred on a mature one, and a
              // control that grew a filter box somewhere between the two would be two controls.
              searchable
              bulk={false}
              confirmOnSelect={false}
              describedBy={institutionCutId}
            />
            <div {...CUT_NOTICE_LIVE_REGION}>
              <CappedListNotice id={institutionCutId} cuts={[institutionCut]} />
            </div>
          </ControlBlock>
        )}

        {/*
          ONE RANGE OVER ONE NAMED COLUMN, not five From/To pairs. Five simultaneous ranges is a
          query nobody has asked for and five index requirements on tables that had none at all on
          any date column; `dateField` + `dateFrom` + `dateTo` is also the spelling eight existing
          list routes already use.

          A real `<fieldset>`/`<legend>`, because these three or four controls are one question and
          "From" on its own is not a filter. Tailwind's preflight already strips the browser's border
          and padding; `min-w-0` is load-bearing, as it is everywhere it appears in this app — a grid
          item defaults to `min-width: auto` and refuses to shrink below its content, so a long
          option label here would widen the column and spill over the control beside it.
        */}
        <fieldset className="min-w-0">
          <legend className="field-label inline-flex items-center gap-1.5">
            <CalendarRange className="h-3.5 w-3.5" aria-hidden />
            {ROSTER_LABELS.dateRange}
          </legend>
          <div className="mt-1 grid gap-2 sm:grid-cols-2">
            <ControlBlock label={ROSTER_LABELS.dateField}>
              <Dropdown
                value={filters.dateField}
                onChange={(next) => set({ dateField: next as RosterDateField })}
                options={dateFieldOptions(kind)}
                ariaLabel={ROSTER_LABELS.dateField}
                searchable={false}
                advanceOnSelect={false}
              />
            </ControlBlock>
            <ControlBlock label={ROSTER_LABELS.datePeriod}>
              <Dropdown
                value={filters.range}
                onChange={(next) => set({ range: next as RangeId })}
                options={RANGE_OPTIONS}
                ariaLabel={ROSTER_LABELS.datePeriod}
                searchable={false}
                advanceOnSelect={false}
              />
            </ControlBlock>
          </div>

          {filters.range === "custom" ? (
            // Only once "Custom range" has been chosen, so the presets — which are what nearly every
            // filter actually wants — never have to walk past a calendar to be used. `SearchFilters`
            // makes the same trade on the same vocabulary.
            <div className="mt-2 grid gap-2 sm:grid-cols-2">
              {/*
                LABELLED AS A SIBLING, NEVER AS A WRAPPER. A wrapping `<label>` folds every named
                descendant into the input's accessible name, and `DateField` carries a real "Open
                calendar" button — so the box announces itself as "From Open calendar" instead of
                "From". `DateTimeField`'s own header draws this exact diagram.
              */}
              <div className="grid min-w-0 gap-1">
                <label className="field-label" htmlFor={fromId}>
                  {ROSTER_LABELS.dateFrom}
                </label>
                <DateField
                  id={fromId}
                  value={filters.from}
                  onChange={(iso) => set({ from: iso })}
                  // Bidirectional clamping, and the bound is NAMED so the refusal can say which
                  // field to change first — otherwise a rejected date is a dead end, because the
                  // field that has to move is not mentioned anywhere on screen.
                  max={filters.to || undefined}
                  maxLabel={ROSTER_LABELS.dateTo}
                />
              </div>
              <div className="grid min-w-0 gap-1">
                <label className="field-label" htmlFor={toId}>
                  {ROSTER_LABELS.dateTo}
                </label>
                <DateField
                  id={toId}
                  value={filters.to}
                  onChange={(iso) => set({ to: iso })}
                  min={filters.from || undefined}
                  minLabel={ROSTER_LABELS.dateFrom}
                />
              </div>
            </div>
          ) : null}
        </fieldset>
      </div>

      {access ? (
        // The one line this row carries about a control that is NOT here. `AccessRoster` has no
        // institution column, and joining to the designer roster to fake one would narrow the
        // allow-list to the subset that is also empanelled as a designer while calling itself an
        // institution filter — hiding exactly the pending strangers this screen exists to decide
        // about. Said out loud, so it does not read as an oversight somebody should "fix".
        <p className="text-[11px] leading-4 text-ink-500">{ACCESS_INSTITUTION_NOTE}</p>
      ) : null}

      {filtered ? (
        <div>
          <button
            type="button"
            className="field-button-secondary min-h-8 px-3 py-1.5 text-xs"
            onClick={() => {
              clearTimer();
              pushed.current = "";
              setDraft("");
              onChange(clearRosterFilters(kind, filters));
              // The button is about to unmount — it is drawn only while something is set — and a
              // control that vanishes under the pointer drops focus to `<body>`, which puts a
              // keyboard reader back at the top of the document. Focus lands on the search box:
              // the one control that is always here, and the likely next move.
              searchRef.current?.focus();
            }}
          >
            <FilterX className="h-3.5 w-3.5" aria-hidden />
            {ROSTER_LABELS.clearAll}
          </button>
        </div>
      ) : null}
    </section>
  );
}

/**
 * A visible label and an accessible name that are the SAME STRING, above one control.
 *
 * A `<span>` and not a `<label>`, and that is not a stylistic choice: a `<label>` forwards a stray
 * click to the first labelable control inside it, which slams a `MultiSelectDropdown` shut after one
 * pick so its panel is never on screen long enough to use — verified in a browser, and the reason
 * this app has `FieldBlock` beside `Field`. A `<label>` also cannot name a `<button>` at all, which
 * is what a themed dropdown's trigger is. So the name arrives through `ariaLabel` on the control,
 * and the caller passes the identical constant to both.
 */
function ControlBlock({
  label,
  hint,
  children
}: {
  label: string;
  hint?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="grid min-w-0 content-start gap-1">
      <span className="field-label">{label}</span>
      {children}
      {hint}
    </div>
  );
}

/**
 * THE SEARCH BOX, WITH A REAL ACCESSIBLE NAME.
 *
 * ── WHY THIS IS NOT `components/SearchInput` ────────────────────────────────────────────────────
 *
 * It is, byte for byte, apart from one attribute: `SearchInput` sets `role="searchbox"` on an input
 * with no `<label>` and no `aria-label`, so its placeholder is its ONLY accessible name — and its
 * props are `value`, `onChange`, `onSubmit`, `placeholder`, none of which can supply one. That is
 * asserted as behaviour by `e2e/design-workshop-viewers.spec.ts:514-527`, which tabs onto the box
 * and polls for the ROLE precisely because there is nothing else to poll for.
 *
 * The right fix is one additive prop on that component — `ariaLabel?: string`, applied only when
 * present, leaving all seventeen existing callers with the exact DOM they have today, including the
 * one that spec walks. `components/SearchInput.tsx` is outside this parcel's territory, so the
 * attribute is supplied here instead and the markup, the classes and the Enter-submits behaviour
 * are copied unchanged so the two boxes cannot look or behave differently.
 *
 * **When `SearchInput` gains `ariaLabel`, delete this and pass it.** There is nothing else here.
 *
 * ── WHY IT MATTERS ON THIS PARTICULAR BOX ───────────────────────────────────────────────────────
 *
 * Each of these two boxes searches three columns, and which three is the thing a reader cannot
 * guess: the allow-list's reaches an admin's private note, the designer roster's reaches the
 * institution. A placeholder disappears the moment somebody types, so the one place that fact was
 * written vanishes exactly when it is being used.
 */
function RosterSearchBox({
  value,
  onChange,
  onSubmit,
  onClear,
  ariaLabel,
  placeholder,
  inputRef
}: {
  value: string;
  onChange: (v: string) => void;
  onSubmit: () => void;
  onClear: () => void;
  ariaLabel: string;
  placeholder: string;
  inputRef: RefObject<HTMLInputElement | null>;
}) {
  return (
    <div className="relative">
      <Search
        className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-300"
        aria-hidden
      />
      <input
        ref={inputRef}
        type="text"
        role="searchbox"
        aria-label={ariaLabel}
        value={value}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "Enter") {
            // Enter sends what is in the box NOW rather than waiting out the debounce. A reader who
            // presses it has said they are done typing, and making them wait a third of a second
            // for the same request is how a search box teaches people that Enter does nothing.
            event.preventDefault();
            onSubmit();
          }
        }}
        className="field-input pl-9 pr-9"
      />
      {value ? (
        <button
          type="button"
          aria-label="Clear search"
          title="Clear search"
          onClick={onClear}
          className="absolute right-2 top-1/2 grid h-6 w-6 -translate-y-1/2 place-items-center rounded-full text-error-600 transition hover:bg-error-100"
        >
          <X className="h-4 w-4" aria-hidden />
        </button>
      ) : null}
    </div>
  );
}
