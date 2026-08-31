"use client";

/**
 * `SearchableSelect` / `SearchableMultiSelect` — the app's one list-picking control.
 *
 * Reported by the user: "make the single select and multi-select drop downs searchable... if they
 * press enter then the first value gets chosen, in multi-select everywhere, also give the option
 * select all."
 *
 * Nine decisions carry this file, and none of them are obvious from the code alone.
 *
 * **1. Search is decided by the call site wherever the options are RECORDS, and by the option count
 * otherwise.** `SEARCH_THRESHOLD` is the default and the split is real rather than tuned (see the
 * constant), but a count can only ever answer for the deployment it was measured on: crafts are nine
 * here, three on a ministry pilot and forty next year, so with the count in charge the same control
 * gains and loses its filter box as records are added. Options from a fetched list pass `searchable`;
 * options written as a constant leave it alone. Full rule on `SearchableSelectProps.searchable`.
 *
 * **2. The highlight is derived, never stored raw.** A stored index goes stale the instant the
 * filter changes, and a stale index means Enter commits a row that is not on screen — the exact
 * data-entry bug a searchable select is supposed to prevent. `safeHighlight` re-derives a valid,
 * enabled, rendered index on every render, so there is no window in which the highlight is a lie.
 *
 * **3. "Select all" follows the filter.** With a query active the button acts on the matches and
 * says so ("Select 6 matching"), never on the 74 rows the reader cannot see. Choosing the whole
 * corpus by accident is far more expensive to undo than clearing the box and clicking again.
 *
 * **4. The panel floats.** It is `AnchoredPopover`, the positioner written for the date picker
 * after the same user reported that opening the calendar "pushes the rest down". A dropdown has
 * the identical problem plus a worse one: these lists live inside dialogs and `overflow-hidden`
 * filter panels, where an absolutely positioned menu is sheared off rather than merely misplaced.
 *
 * **5. There are several index domains and only one of them is legal for the highlight.** `options`
 * is the corpus; `filtered` is the corpus narrowed by the query; `windowed` is `filtered` cut to
 * `RENDER_CAP` with off-window selections pinned in front; `rendered` is `windowed` with the
 * `noneLabel` row, if there is one, prepended. `highlight` indexes `rendered`, ALWAYS. Every place
 * that computes an index says which array it is against, because the two places that did not —
 * type-ahead in both components — moved the highlight onto an unrelated row on the one control
 * where the cap always bites, and that control is a permissions picker whose next keystroke ticks a
 * person. The counts under the panel are the other half of the same discipline: they describe the
 * CORPUS (`filtered`, `windowed`), never `rendered`, because a "none" row and a pinned row are not
 * rows of the list the sentence is counting.
 *
 * **6. A keystroke that means "move on" must never change an answer.** Tab out of the filter box
 * commits only a highlight the reader MOVED (arrows, Home, End, hover); typing alone leaves the
 * field's existing value exactly as it was. See `onTabForward` for the sequence this cost.
 *
 * **7. In a text box the caret keys belong to the caret.** Home and End move the caret in the filter
 * box and the list everywhere else — see `navigate`'s `textEntry`.
 *
 * **8. The filter box may belong to the SERVER, and where it does it is the only search.** With
 * `serverQuery` the panel stops filtering the array it was handed and draws the answer the caller
 * fetched, because the two cannot both be the box: a local pass over a server's answer drops the
 * rows the server matched on a column the label does not show. Sixteen controls in this app put a
 * local box over one server-truncated page and answer "No matches" about records that exist; three
 * more switched the box off and mounted a second one above the field, losing the panel's diacritic
 * folding, its hint ranking and its live region to get an honest search. `serverQuery` is the third
 * answer, and it is the one that keeps both. Everything it changes is listed on the prop.
 *
 * **9. A capability that arrives late arrives as an OPTIONAL prop whose absence is the old
 * behaviour, exactly.** This primitive has about forty live call sites and a breaking change here
 * breaks the product. `serverQuery`, `noneLabel` and `bulk` are all shaped that way and each says
 * so in its own comment; every default in this file reproduces what the file did before it existed.
 *
 * Focus is deliberately NOT trapped. Tab walks the panel's own controls and then leaves for the
 * next field in the form; Escape closes and puts focus back on the trigger. A picker that swallows
 * the keyboard is worse than the native `<select>` it replaces.
 *
 * The pure half of all this — the matcher, the grouping, the three constants, and every sentence
 * this control can print — lives in `ui/selectFilter.ts` so a spec can call it. That file says why,
 * and "every sentence" now means it: the truncation footer, the empty line, the live region and the
 * multi-select trigger's own selection summary are all chooser functions there rather than ternaries
 * in the JSX below, because each of them has grown a branch that only exists on a server-searched
 * control and a branch nobody can execute is a branch nobody has checked.
 */

import { Check, ChevronDown, Search } from "lucide-react";
import { Fragment, useCallback, useEffect, useId, useMemo, useRef, useState } from "react";

import { AnchoredPopover } from "@/components/ui/AnchoredPopover";
import { useFieldLabelId } from "@/components/ui/fieldLabel";
import {
  CAP_HINT_WITHOUT_SEARCH,
  CAP_HINT_WITH_SEARCH,
  RENDER_CAP,
  SEARCHING_LABEL,
  SEARCH_THRESHOLD,
  emptyListSentence,
  filterOptions,
  fold,
  groupRows,
  listAnnouncement,
  selectionSummarySentence,
  truncationSentence,
  typeaheadIndex,
  type SelectOption
} from "@/components/ui/selectFilter";
import { focusNextField } from "@/lib/formNav";
import { cn } from "@/lib/utils";

/**
 * Re-exported rather than moved, because ~40 files import the type from here and the module it now
 * lives in exists for the TESTS — a rename across forty call sites to serve a spec is churn, not a
 * boundary. `components/ui/selectFilter.ts` is the source of truth; this is the address.
 */
export type { SelectOption };

/**
 * THE PANEL'S FILTER BOX, WIRED TO A SERVER `search=` INSTEAD OF TO THE ARRAY IT WAS HANDED.
 *
 * Named and exported rather than written inline on both prop types, because about twenty call sites
 * are about to build one and they must all build the same thing; `SelectOption` is exported from
 * here for the same reason and this is its companion.
 *
 * ── WHAT IT COSTS, WHICH IS NOT NOTHING ──────────────────────────────────────────────────────────
 * The local pass does three things the server's `contains(...)` → `ILIKE '%term%'` does not: it
 * folds diacritics and runs of whitespace (`selectFilter.ts::fold`, so "Ahmedabad" finds
 * "Ahmedābād"), it searches the `hint` as well as the label, and it RANKS — label-prefix above
 * word-prefix above mid-word above hint. Handing the box to the server loses all three until
 * Postgres grows `unaccent`. That is a real regression and it is paid deliberately, because a box
 * that reaches page four with a missing accent is worth more than one that cannot reach page four
 * at all. It is written down here rather than discovered by a designer who cannot find a workshop
 * they can see the name of.
 *
 * ── WHAT THE CALLER OWNS, BECAUSE THE PANEL CANNOT ───────────────────────────────────────────────
 * The debounce (300 ms is this app's number) and the generation counter. `lib/api::apiFetch` carries
 * no AbortSignal, so an out-of-order answer cannot be cancelled and must be DISCARDED BY GENERATION
 * — the shape `app/(protected)/design-review/page.tsx` already uses. A caller that skips it will
 * paint the answer to a query the reader has typed past, which looks exactly like a search that
 * returns the wrong rows.
 *
 * The caller also owns the page size, and it must be `RENDER_CAP`. Asking for 100 rows into a panel
 * that draws 80 is the dead band `selectFilter.ts::RENDER_CAP` exists to kill.
 *
 * ── AND IT OWNS THE IDENTITY OF `options`, WHICH IS THE ONLY THING THAT SAYS AN ANSWER LANDED ────
 * Build the array in a `useMemo` keyed on the fetched list. This branch re-takes its pin snapshot
 * from an effect keyed on `options` IDENTITY, because a debounced answer arrives several renders
 * after the keystroke that asked for it and there is no other signal that it did (see that effect,
 * and `useSelectList` for the defect it is closing). A fresh array built inline in the JSX makes
 * every render of the PARENT look like a new answer, so the snapshot is re-taken on every keystroke
 * — which is the per-keystroke behaviour the effect exists to replace. It is not a loop: `setPins`
 * re-renders this component alone, `options` keeps its reference across that render, and the deps
 * are unchanged, so the effect does not re-enter. It is also harmless while the page size really is
 * `RENDER_CAP`, because `useSelectList` returns before it pins anything when the corpus fits the
 * window. Break either rule and they compound: an unmemoised array over an oversized page re-pins
 * from the live selection on every tick, and on a multi-select that is exactly the renumbering that
 * makes the next Enter toggle the neighbour.
 */
/**
 * THE ROW UNDER THE LIST THAT ACCEPTS A NAME THE LIST DOES NOT HOLD.
 *
 * ── THE OBJECTION THIS ANSWERS, WHICH WAS CORRECT ABOUT WHAT IT REFUSED ─────────────────────────
 *
 * `components/designworkshop/stageFieldRoles.ts` refuses, by name, to put a dropdown on the design
 * workshop's OWN title: *"a dropdown there would refuse a workshop that has no `Workshop` record
 * yet, which is most of them on the day they start."* That defeats "make the title a PICKER of
 * existing rows" and it is still true. It does not defeat a control that OFFERS the existing names
 * and ACCEPTS a new one typed straight in, because such a control refuses nothing: whatever is in
 * the box is committable. This prop is that second control, and it lives here rather than at the
 * call site so that the next field which needs it does not become a fourth hand-rolled
 * "…or type your own" toggle with its own wording and its own keyboard route.
 *
 * ── BELOW THE LIST, NEVER IN IT ─────────────────────────────────────────────────────────────────
 *
 * `android/.../ui/SearchableSelect.kt`'s `SelectCreateAction` states the rule this copies, and the
 * reason survives the platform: a row among the options is a row the commit key can take while the
 * reader is still typing, so a designer typing "Bagru" and pressing Enter would land on the create
 * row instead of on the Bagru workshop sitting right there. It is drawn UNDER the options and is
 * not in `rendered`, so every index rule in this file — `highlight` indexes `rendered`, the render
 * cap, the pin snapshot — applies unchanged, and the live region's "N of M match" goes on counting
 * records only, because the create row is not one of the records being matched.
 *
 * ── ENTER REACHES IT ONLY WHEN NOTHING ELSE IS ON SCREEN ────────────────────────────────────────
 *
 * With any row highlightable, Enter still takes the top match, byte for byte as before. With none —
 * a term that matched nothing — Enter previously did nothing at all, which on a control whose whole
 * point is that it accepts new names reads as a dead key on the one keystroke a reader will try.
 * That arm, and only that arm, commits the term.
 *
 * ── AND IT FORCES THE FILTER BOX ON ─────────────────────────────────────────────────────────────
 *
 * The term IS the typing, so a create action on a control with no box to type into is an affordance
 * nobody can reach. Same rule and same reason as `serverQuery`, and it overrules `searchable={false}`
 * for the same reason: there is no sensible reading of a call site that asks for both.
 *
 * ── OFFERED WHETHER OR NOT THE SEARCH FOUND ANYTHING ────────────────────────────────────────────
 *
 * Android's argument, unchanged: a designer usually knows the name is absent before they have
 * finished typing it, and a control that only appears after an empty result is one they have to
 * discover twice. It is withheld in exactly two cases — an empty box, where there is nothing to
 * create, and a term that already IS an option's label, where it would offer to create a duplicate
 * of a row the reader can see.
 */
export type SelectCreateAction = {
  /**
   * The row's words, built from the term. Put the term in quotation marks: a reader has to be able
   * to see exactly what would be stored, trailing spaces and capitals included, and a summary is
   * the one thing that cannot show them.
   */
  label: (term: string) => string;
  /**
   * Commit the typed term. It arrives trimmed.
   *
   * NOT written through `onChange` by this file, deliberately. A caller may have a value to fold, a
   * list to append the new name to, or a `markDirty` to call by hand (a themed control fires no
   * native input event), and a primitive that guessed would be guessing once for every future call
   * site. Most callers will pass `onChange` and that is fine; it has to be their sentence.
   */
  onCreate: (term: string) => void;
};

export type SelectServerQuery = {
  /** The live term. The panel renders this box; it does not keep a copy. */
  value: string;
  /**
   * A keystroke in the panel's box. The panel NEVER calls this with "" of its own accord — closing
   * the panel leaves the caller's term exactly as it is, because clearing it would fire a re-fetch
   * of the unnarrowed list every time a reader dismissed the menu, and would throw away the
   * narrowing they had just done. Clearing is the caller's decision and the reader's keystroke.
   */
  onChange: (term: string) => void;
  /**
   * A request is in flight. Drives `SEARCHING_LABEL` in the panel and in the live region, so an
   * empty list mid-flight reads as "wait" and not as "there are none" — R3, in the one state where
   * the control genuinely does not yet know.
   */
  pending: boolean;
  /**
   * The server had more rows matching than it sent — read one past the take, in the manner of
   * `GET /tasks/options`'s `workshopsTruncated`. Draws `unknownTotalNoticeSentence` under the list.
   *
   * OPTIONAL BECAUSE A ROUTE MAY NOT SAY. `apiFetch` casts rather than validates, so a field a
   * deployment has not shipped yet arrives as `undefined`, and `undefined` must read as "nothing to
   * say" and never as "not truncated" — the same guard, for the same reason, as `cutOf`'s
   * `Number.isFinite`. `GET /workshops/requestable` returns a bare array with no total and no flag
   * and so is honestly silent here until it grows one.
   */
  truncated?: boolean;
};

/**
 * The pin snapshot before a panel has ever been opened.
 *
 * A module-level constant, not `new Set()` in the initialiser, so the empty case is one stable
 * reference: `useSelectList`'s memo has it in a dependency array, and a fresh Set on every render
 * would re-slice a 2000-row window on every keystroke of an unrelated field.
 */
const EMPTY_PINS: ReadonlySet<string> = new Set<string>();

const TRIGGER_CLASS =
  "flex w-full items-center justify-between gap-2 rounded-md border border-line-200 bg-card px-3.5 py-2.5 text-left text-sm text-ink-900 outline-none transition hover:border-purple-300 focus:border-purple-600 focus:ring-4 focus:ring-purple-600/15 disabled:cursor-not-allowed disabled:opacity-60";

/**
 * `!` because `cn` is a plain join, not tailwind-merge: `overflow-y-auto` and `p-3` from
 * AnchoredPopover's own class list would otherwise win on CSS source order regardless of what is
 * appended here. The panel must not scroll as a whole — the search box and the footer stay put
 * while only the list moves — so the override has to actually take.
 */
const PANEL_CLASS = "!overflow-hidden !p-0 flex flex-col";

/** Widest a panel gets, so a full-width field on a laptop does not produce a 1200px list. */
const PANEL_MAX_WIDTH = 520;

/**
 * Rolling type-ahead buffer (~700ms window, like the native <select>).
 *
 * Only ever used by a list too short to have earned a filter box. Losing it there would be a real
 * regression for the way these forms are actually filled: focus Gender, press "f", get Female,
 * without the control ever opening. A list WITH a filter box has a better answer already, and
 * running both would mean two different things happening for one keystroke.
 *
 * The better answer is `seedFilter` below, and until this pass there WAS no answer on that branch:
 * a printable key on a searchable trigger did nothing whatsoever. So the fastest keyboard route
 * into a list existed on the four-option enums and was missing from the 252 dial codes, which is
 * exactly backwards — both the native `<select>` and the ARIA combobox pattern open and start
 * narrowing on the first letter, and a reader who has learnt that on Gender tries it on Country.
 */
function useTypeahead() {
  const state = useRef({ text: "", at: 0 });
  return useCallback((char: string) => {
    const now = Date.now();
    if (now - state.current.at > 700) state.current.text = "";
    state.current.at = now;
    state.current.text += char.toLowerCase();
    return state.current.text;
  }, []);
}

/** Next enabled index walking `delta` (+1/-1) from `current`, wrapping around. */
function stepHighlight(options: SelectOption[], current: number, delta: number) {
  const n = options.length;
  if (!n) return -1;
  let i = current < 0 || current >= n ? (delta > 0 ? -1 : n) : current;
  for (let step = 0; step < n; step++) {
    i = (i + delta + n) % n;
    if (!options[i].disabled) return i;
  }
  return -1;
}

/** A character key rather than a chord or a named key — Space excluded, it toggles the panel. */
function isPrintable(event: React.KeyboardEvent) {
  return event.key.length === 1 && event.key !== " " && !event.ctrlKey && !event.metaKey && !event.altKey;
}

function firstEnabled(options: SelectOption[]) {
  return options.findIndex((option) => !option.disabled);
}

/**
 * The purple ramp is brand colour and deliberately does NOT invert with the theme, so purple-50 is
 * near-white in both modes — as a highlight it painted a white bar across a dark menu, and
 * purple-700 text on it went to near-black on near-black once the bar was darkened. The dark
 * counterparts are the ones ui/calendar uses for its day cells, so a menu and a calendar highlight
 * the thing under the cursor identically.
 */
function optionClass(option: SelectOption, highlighted: boolean, active: boolean) {
  return cn(
    "flex items-center gap-2 px-3.5 py-2 text-sm",
    option.disabled ? "cursor-not-allowed text-ink-300" : "cursor-pointer",
    !option.disabled && highlighted && "bg-purple-50 dark:bg-purple-950",
    active ? "font-medium text-purple-700 dark:text-purple-300" : !option.disabled ? "text-ink-700" : ""
  );
}

/**
 * Keeps the highlighted row on screen.
 *
 * `block: "nearest"` rather than "center" so arrowing down a long list scrolls by one row instead
 * of jumping the viewport around under the reader.
 */
function useScrollHighlightIntoView(open: boolean, highlight: number, baseId: string) {
  useEffect(() => {
    if (!open || highlight < 0) return;
    document.getElementById(`${baseId}-opt-${highlight}`)?.scrollIntoView({ block: "nearest" });
  }, [open, highlight, baseId]);
}

/**
 * Everything the panel needs, derived rather than stored.
 *
 * The one piece of raw state is `highlight`, and even that is passed through `safeHighlight` before
 * anything reads it — see the file header on why a stored index is not trustworthy here.
 */
function useSelectList(
  options: SelectOption[],
  query: string,
  searchable: boolean,
  pins: ReadonlySet<string>,
  /**
   * The query has already been answered by the server, so the local pass is SKIPPED.
   *
   * Not a nicety and not an optimisation. `options` already IS the answer to `query`, and running
   * `filterOptions` over it again would drop every row the server matched on a column the label does
   * not show — `workshopCode` is in `GET /design-workshops`' search and is deliberately not in the
   * hint (a code an admin reads off a join card is not a fact that tells two workshops apart on a
   * phone row). The reader would type a code they are holding, the server would find it, and the
   * panel would hide it and say "No matches". One box, one answer.
   */
  serverAnswered: boolean
) {
  const filtered = useMemo(
    () => (searchable && !serverAnswered ? filterOptions(options, query) : options),
    [options, query, searchable, serverAnswered]
  );
  const { windowed, pinned } = useMemo(() => {
    if (filtered.length <= RENDER_CAP) return { windowed: filtered, pinned: 0 };
    const window = filtered.slice(0, RENDER_CAP);
    // A cap must never hide what the reader already picked. India is the 100-somethingth of 246
    // dial codes, so the plain first-80 window reopened the country picker with no tick anywhere in
    // it and no hint that the value was real — the control would have been lying about its own
    // state. Anything selected that the window missed is pinned to the top, where the check mark
    // says what it is.
    //
    // ── `pins` IS A SNAPSHOT, NOT THE LIVE SELECTION, AND THAT IS THE WHOLE FIX ──────────────────
    // This used to read the live `chosen` set, which made the RENDERED ARRAY change length as a
    // reader ticked things — and `highlight` indexes that array. In a multi-select past the cap with
    // ticks outside the first-80 window, unticking a pinned row dropped it from `missing`, every row
    // beneath shifted up by one, `highlight` kept its old number, and the next Enter or click
    // toggled the NEIGHBOUR of the row the reader was looking at. "Select all matching" did the same
    // thing in the other direction, adding pinned rows under a stationary highlight. On the design
    // workshop viewer picker — a permissions control over up to 2000 accounts — that is granting a
    // colleague access to a workshop nobody pointed at.
    //
    // So the set of pinned values is captured at the moments the window is legitimately recomputed —
    // the panel opening, the query changing, and, on a `serverQuery` control, the ANSWER changing —
    // and held still in between. Ticking and unticking then only ever repaints check marks; it never
    // renumbers a row. The cost is that a row ticked from deeper in the corpus during this session is
    // not dragged forward until the panel is reopened, which is the smaller of the two surprises by a
    // wide margin: the tick is still counted in the trigger's summary and in "N selected".
    //
    // ── AND WHY THE THIRD MOMENT IS THE ANSWER AND NOT THE KEYSTROKE ─────────────────────────────
    // With a server query the array is replaced when each debounced answer lands, several renders
    // after the keystroke that asked for it. Re-snapshotting on the keystroke pins against the array
    // the reader is about to stop looking at and then leaves the pins stale for the array that
    // replaces it — the same renumbering-under-a-stationary-highlight defect as above, arriving by a
    // different door. The panel's own `options`-identity effect is the fix; see `setPins` there.
    const missing = filtered.filter((option) => pins.has(option.value) && !window.includes(option));
    // `pinned` is counted and handed back rather than left implicit, because the footer has to say
    // something true about a list of two different kinds of row. `windowed.length` is 80 first-rows
    // PLUS however many pinned selections came from deeper in the corpus, so a footer reading
    // "Showing the first 81 of 246" described a set whose 81st member was the 100th match — a small
    // lie, but this repo's rule is that a truncated list must state its truncation ACCURATELY, and
    // an off-by-a-pinned-row notice is the kind of thing a reader checks their own counting against.
    return missing.length
      ? { windowed: [...missing, ...window], pinned: missing.length }
      : { windowed: window, pinned: 0 };
  }, [filtered, pins]);
  // `windowed`, NOT `rendered`, and the rename is load-bearing rather than tidying. Everything this
  // hook returns is a fact about the CORPUS, and the array actually drawn may carry one row that is
  // not in the corpus at all — the `noneLabel` row, which each component prepends after this. Both
  // components then hold `windowed` and `rendered` side by side, and the counts under the panel read
  // the first while `highlight` indexes the second. Leaving this called `rendered` is how the "none"
  // row would end up counted in "Showing the first 81 of 246".
  return { filtered, windowed, pinned, capped: filtered.length - windowed.length };
}

/**
 * Keeps the panel's own keystrokes inside the panel, and why that is not paranoia.
 *
 * A React portal moves the panel out of the DOM but NOT out of the React tree, so synthetic events
 * still bubble to whatever component rendered the select. Every record form in this app is
 * `<form onInput={markDirty} onKeyDown={handleFormEnter}>`, and both would have picked up the
 * filter box:
 *
 * - Typing three letters to find a craft, changing nothing, would arm the unsaved-changes prompt,
 *   so a reader who searched, picked nothing and pressed Escape could not leave the page.
 * - Enter to take the highlighted match would ALSO reach `handleFormEnter`, which sees a plain text
 *   input and advances focus. Worse, `focusNextField` scopes itself with `closest("form")` — null
 *   for a node portalled to `<body>` — so it would fall back to the whole document and throw focus
 *   at an unrelated field on the other side of the page.
 *
 * Nothing outside the panel has any business knowing what was typed into a filter, so the whole
 * class of problem is cut off here rather than patched per form. Escape is unaffected: AnchoredPopover
 * takes it on a window-CAPTURE listener, which runs before any of this.
 */
function containEvents(onKeyDown: (event: React.KeyboardEvent) => void) {
  return {
    onKeyDown: (event: React.KeyboardEvent) => {
      onKeyDown(event);
      event.stopPropagation();
    },
    onInput: (event: React.FormEvent) => event.stopPropagation(),
    onChange: (event: React.FormEvent) => event.stopPropagation()
  };
}

/** Shared search row so the single- and multi-select boxes are the same box. */
function SearchRow({
  inputRef,
  inputId,
  listboxId,
  activeId,
  query,
  onQueryChange,
  onKeyDown,
  placeholder,
  label,
  pending,
  trailing
}: {
  inputRef: (node: HTMLInputElement | null) => void;
  inputId: string;
  listboxId: string;
  activeId?: string;
  query: string;
  onQueryChange: (value: string) => void;
  onKeyDown: (event: React.KeyboardEvent<HTMLInputElement>) => void;
  placeholder: string;
  label: string;
  /**
   * A `serverQuery` request is in flight — drawn as a word beside the box.
   *
   * IT HAS TO BE VISIBLE HERE AND NOT ONLY IN THE EMPTY ARM, because the interesting case is the one
   * where the list is NOT empty: the previous answer is still on screen while the new one travels,
   * and on the rural connections this app is built for that is a second and a half of a panel that
   * looks settled and is wrong. The empty arm alone would say nothing at all in exactly that window.
   *
   * `aria-hidden` because the panel's `role="status"` region already announces it through
   * `listAnnouncement` — a screen reader that heard both would hear "Searching" twice for one
   * keystroke, and the live region is the one that also carries the counts.
   */
  pending?: boolean;
  trailing?: React.ReactNode;
}) {
  return (
    <div className="flex shrink-0 items-center gap-2 border-b border-line-200 p-2">
      <div className="relative min-w-0 flex-1">
        <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-ink-500" aria-hidden />
        <input
          ref={inputRef}
          id={inputId}
          type="text"
          role="combobox"
          aria-expanded
          aria-controls={listboxId}
          aria-autocomplete="list"
          aria-activedescendant={activeId}
          aria-label={label}
          autoComplete="off"
          spellCheck={false}
          value={query}
          placeholder={placeholder}
          onChange={(event) => onQueryChange(event.target.value)}
          onKeyDown={onKeyDown}
          className="w-full rounded-sm border border-line-200 bg-card py-1.5 pl-8 pr-2 text-sm text-ink-900 outline-none transition placeholder:text-ink-300 focus:border-purple-600 focus:ring-2 focus:ring-purple-600/15"
        />
      </div>
      {/*
        A WORD, NOT A SPINNER. The house rule is that a signal which exists only as motion is a
        signal a reduced-motion reader never gets, so every pulse has to be paired with a static
        state anyway — and there is nothing here that a static state does not already say. It also
        means this row has no `animation-duration` for either of the two reduced-motion switches to
        have to reach, which is the only kind of animation that cannot be got wrong.
      */}
      {pending ? (
        <span aria-hidden className="shrink-0 whitespace-nowrap text-xs text-ink-500">
          {SEARCHING_LABEL}
        </span>
      ) : null}
      {trailing}
    </div>
  );
}

/**
 * The truncation footer. Only drawn when something was actually cut.
 *
 * THE BOX, NOT THE SENTENCE. Both sentences and the choice between them live in `ui/selectFilter`
 * so a spec can read them without a browser — `capNoticeSentence` where the total is known,
 * `unknownTotalNoticeSentence` where the server reported the cut as a flag, and `truncationSentence`
 * for the ruling about which of the two wins. This is only where the chosen sentence is painted.
 *
 * The last clause of either is the CALLER'S, because the panel cannot know how a reader is supposed
 * to reach row 81: with a filter box the answer is "keep typing", and without one it is whatever
 * control the call site put above the picker. Printing "Keep typing to narrow the list" on a panel
 * that has no box is how the viewer picker came to instruct an admin to use a control that is not on
 * screen.
 */
function CapNotice({ sentence }: { sentence: string }) {
  return (
    <p className="shrink-0 border-t border-line-200 bg-surface-50 px-3.5 py-2 text-xs leading-4 text-ink-500">
      {sentence}
    </p>
  );
}

/**
 * THE "NONE" ROW — `value: ""`, ungrouped, drawn first — or `null` where the caller did not ask for
 * one.
 *
 * ── WHY THE PRIMITIVE OWNS THIS AND NINE CALL SITES DO NOT ───────────────────────────────────────
 * `components/forms/WorkshopSelect.tsx` hand-builds `{ value: "", label: NO_WORKSHOP_LABEL }` and
 * prepends it; `components/forms/DesignWorkshopSelect.tsx` maps its rows and prepends nothing. Four
 * record forms mount BOTH of those, stacked, one above the other — so on one form the first picker
 * offers a way back to "no workshop" and the second does not, and **a record filed under the wrong
 * design workshop cannot be corrected on the web at all.** The server has accepted the clearance the
 * whole time (`designWorkshopId` is in `services/records.py`'s `CLEARABLE_KEYS`, added with the
 * column and with the failure spelled out beside it); the row to send it was simply never drawn.
 * Android's `SearchableSelect.kt` has had `includeNone` since the beginning. Putting it here rather
 * than in nine callers is what stops the tenth from forgetting again.
 *
 * ── THE ROW IS HIDDEN WHILE A QUERY IS ACTIVE; THE TRIGGER'S LABEL IS NOT ───────────────────────
 * `SearchablePickerSheet` draws its none row under `if (noneLabel != null && !searching)`, and the
 * web copies that for three reasons. It has to behave the same on both branches of this file, and on
 * a `serverQuery` control it CAN only ever be unfiltered — the server has never heard of it — so a
 * row that survives a query on one branch and not the other would make the control change shape with
 * a prop the reader cannot see. It is not a row of the corpus, so ranking a synthetic row inside
 * `filterOptions` would put "Not filed under a design workshop" above a workshop actually called
 * "Notebooks". And a reader who has typed a term is hunting, not un-filing; the way back is one
 * Backspace away and is never more than a keystroke from where they are.
 *
 * WHICH IS WHY THIS FUNCTION DOES NOT SEE THE QUERY. The row and the trigger's fallback label are
 * two uses of one option and only the first is gated: the trigger describes the FIELD'S ANSWER, and
 * a field whose answer changed because somebody typed in a filter box would be reporting the state
 * of the panel instead of the state of the record. Left folded together, opening a picker over a
 * cleared design-workshop field and typing one letter made the trigger fall back from "Not filed
 * under a design workshop" to "Select a design workshop" and back again on Backspace. The gate is
 * applied where the row is built, one line, with the same rule written beside it.
 *
 * ── IT IS NOT ON THE MULTI-SELECT, DELIBERATELY ──────────────────────────────────────────────────
 * A multi-select already says "none" by holding an empty array. A none ROW would be a second
 * spelling of that state — tickable, tickable alongside three real rows — and this repository's
 * first rule about list controls is that empty means everything BY ABSENCE, never by a row that can
 * be in one of two states meaning the same thing. A filter that wants an explicit "everything"
 * affordance builds its own button that sets `[]`, as `WorkshopScopeSelect` does.
 *
 * ── AND IT STANDS DOWN IF THE CALLER ALREADY BUILT ONE ───────────────────────────────────────────
 * Written for a migration that is about to happen twenty times over. `WorkshopSelect.tsx` prepends
 * its own `{ value: "", label: NO_WORKSHOP_LABEL }` today and is specified to adopt `noneLabel`
 * instead; the photo-intake picker prepends `"Leave out — I will attach this one myself"` and is not.
 * An agent who adds the prop and forgets to delete the hand-built row would otherwise get two
 * identical rows sharing the React key `""` — a duplicate-key warning, a list that offers the same
 * answer twice, and a control that cannot say which of the two is selected. Deferring to the
 * caller's row makes the half-done migration render correctly instead of oddly, and the finished one
 * render identically either way.
 */
function noneOptionFor(noneLabel: string | undefined, options: SelectOption[]): SelectOption | null {
  if (!noneLabel) return null;
  if (options.some((option) => option.value === "")) return null;
  return { value: "", label: noneLabel };
}

/**
 * A group heading inside the listbox.
 *
 * `aria-hidden` on the text and the grouping said in ARIA by the `role="group"` wrapper around it,
 * rather than the other way round: a heading announced as a row of the list is a row a reader can
 * try to arrow onto and cannot, and the group's own `aria-label` already puts the name in front of
 * every option inside it, which is where it belongs.
 */
function GroupHeading({ name }: { name: string }) {
  return (
    <div
      aria-hidden
      className="px-3.5 pb-1 pt-2 text-[0.6875rem] font-semibold uppercase tracking-wide text-ink-500"
    >
      {name}
    </div>
  );
}

/**
 * The label and, where the caller gave one, the hint beside it.
 *
 * INLINE RATHER THAN STACKED, which is where this parts company with the handset and the difference
 * is the platform's rather than a paraphrase. `PickerRow` in `android/.../ui/SearchableSelect.kt`
 * puts the hint on a second line under the label, and can afford to: its rows have a 48dp floor
 * because a mis-hit on a phone picks the neighbour. A web row is 36px, and stacking would make the
 * 246-row country list twice as tall for a second column that is usually two words. So the hint sits
 * after the label, muted, truncating first — the label is what `filterOptions` ranks on and is
 * therefore what must stay readable.
 */
function OptionText({ option }: { option: SelectOption }) {
  return (
    <>
      <span className="min-w-0 flex-1 truncate">{option.label}</span>
      {option.hint ? (
        <span className="min-w-0 max-w-[55%] shrink truncate text-xs text-ink-500">{option.hint}</span>
      ) : null}
    </>
  );
}

/** Both halves of a row, for the `title` tooltip and for the tests that read one. */
function optionTitle(option: SelectOption) {
  return option.hint ? `${option.label} — ${option.hint}` : option.label;
}

/**
 * Tab out of either end of the panel.
 *
 * NOT a focus trap: the point is to leave, just to leave somewhere useful. Tab past the last
 * control commits and moves to the next field in the form (the panel is portalled to the end of
 * `<body>`, so the browser's own answer would be the URL bar); Shift+Tab off the first control
 * puts focus back on the trigger, where the reader's previous Shift+Tab will behave normally.
 * Everything between the two ends is left entirely to the browser.
 */
function useEdgeTab(
  panelRef: React.RefObject<HTMLDivElement | null>,
  onTabForward: () => void,
  onTabBackward: () => void
) {
  return useCallback(
    (event: React.KeyboardEvent) => {
      if (event.key !== "Tab") return;
      const panel = panelRef.current;
      if (!panel) return;
      const stops = Array.from(
        panel.querySelectorAll<HTMLElement>("input:not([disabled]),button:not([disabled])")
      );
      const index = stops.indexOf(document.activeElement as HTMLElement);
      if (index < 0) return;
      if (!event.shiftKey && index === stops.length - 1) {
        event.preventDefault();
        onTabForward();
      } else if (event.shiftKey && index === 0) {
        event.preventDefault();
        onTabBackward();
      }
    },
    [panelRef, onTabForward, onTabBackward]
  );
}

/**
 * Focus the filter box as it mounts — but only if the keyboard is still where opening left it.
 *
 * Two things make this less trivial than an effect keyed on `open`. The panel is portalled and only
 * mounts once AnchoredPopover has resolved its host, a render later than `open` flipping, so an
 * effect fires before the box exists; attaching the focus to the ref callback is the one moment
 * guaranteed to be after it.
 *
 * The second is the reason for the guard, and it was caught on a real page rather than reasoned
 * about. Options that arrive over the network are not there when the panel opens: on /tools the
 * craft list took 1.8s, so the select opened with zero options, sat BELOW the search threshold, and
 * rendered no filter box at all — then grew one when the response landed. An unconditional focus
 * there yanks the keyboard out of whatever the reader moved on to, seconds after they opened the
 * menu, which on the rural connections this app is built for is the normal case and not the edge
 * one. So the box claims focus only from the trigger it belongs to (or from nothing at all), and
 * otherwise lets the reader be.
 */
function useFilterBoxFocus(triggerRef: React.RefObject<HTMLButtonElement | null>) {
  return useCallback(
    (node: HTMLInputElement | null) => {
      if (!node) return;
      // Deferred a frame rather than focused inline, and this one is load-bearing. Refs attach
      // bottom-up, so at the moment this callback runs AnchoredPopover's own panel ref — held on an
      // ANCESTOR of this input — is still null. Its focus-out guard asks
      // `panelRef.current.contains(target)` to decide whether focus went somewhere legitimate, and
      // against a null panel an in-panel focus is indistinguishable from a click-away: the country
      // picker closed itself in the same tick it opened, every time. A frame later every ref is in
      // place and the guard can answer correctly.
      requestAnimationFrame(() => {
        if (!node.isConnected) return;
        const active = document.activeElement;
        if (active && active !== document.body && active !== triggerRef.current) return;
        node.focus({ preventScroll: true });
      });
    },
    [triggerRef]
  );
}

export type SearchableSelectProps = {
  value: string;
  onChange: (value: string) => void;
  options: SelectOption[];
  placeholder?: string;
  emptyLabel?: string;
  disabled?: boolean;
  className?: string;
  ariaLabel?: string;
  /**
   * Ids of the paragraphs that describe this control — a field's hint, and the message a save came
   * back with. A themed dropdown is a <button>, so a caller cannot reach it with a plain
   * `<label htmlFor>` and had no way at all to attach a refusal to it.
   *
   * THERE IS DELIBERATELY NO `invalid` COMPANION. `aria-invalid` is not a global state and the
   * `button` role does not support it, so setting it here would be ignored by every screen reader
   * while looking, in the source, like the field was marked. The trigger now carries
   * `role="combobox"` on the branch where it owns the keyboard (see the note on the element), which
   * WOULD support `aria-invalid` — but only there: with a filter box the trigger is still a plain
   * button and the combobox is the box inside the panel. A prop honoured on short lists and
   * silently dropped on long ones is worse than no prop, because the call site cannot see which
   * kind of list it has. Until both branches can carry it the refusal reaches the reader through
   * `describedBy` and its own live region.
   */
  describedBy?: string;
  /**
   * `undefined` lets `SEARCH_THRESHOLD` decide. Pass `true` where the list is BACKED BY RECORDS.
   *
   * ── THE RULE, AND WHY THE COUNT ALONE IS NOT IT ──
   * The threshold answers "is this list long enough to hunt through", and for a fixed vocabulary
   * written in this repo it answers correctly forever: gender is four options today and four
   * options in five years. For a list assembled out of rows it answers correctly only for the
   * deployment it was measured on. Crafts are nine here, three on a ministry pilot that has
   * recorded three, and forty next year — so leaving the count in charge means the same control
   * grows and loses its filter box as records are added, which is the one thing a reader cannot
   * learn: they are taught to type into the craft picker on one deployment and find nothing to type
   * into on another. `Dropdown.tsx` says the same thing about the workshop picker, which "is one
   * row on this deployment and will be forty on the next".
   *
   * So: options built from a fetched list (workshops, crafts, artisans, products, tools, users,
   * sections, report generations) pass `searchable`. Options written as a constant in the file
   * (status, tier, gender, units, Yes/No) do not — a filter box over four rows is a tab stop, a
   * "No matches" state and a thing to read, in exchange for nothing.
   *
   * Pass `false` only to overrule a long list on purpose, which one panel does and documents.
   *
   * ── ANDROID PARITY, STATED RATHER THAN ASSUMED ──
   * `android/.../ui/SearchableSelect.kt:218` is `val searchable = options.size >= SEARCH_THRESHOLD`
   * with NO per-call-site override, and the same threshold of 8. So on a deployment whose corpus is
   * still small, the web now offers a filter box on the record-backed pickers where the handset
   * still opens its plain anchored menu. The handset is not wrong — it is the behaviour the web had
   * until this pass — but the two clients do differ there until Kotlin grows the same override, and
   * that is a divergence to close rather than a difference to design around.
   */
  searchable?: boolean;
  /**
   * The last clause of the "Showing the first 80 of N" footer — how a reader is meant to reach the
   * rows that are not drawn.
   *
   * Defaulted, not required: with a filter box the answer is always "keep typing". Pass one where
   * `searchable={false}` overrules a long list, because then the panel has no idea what the answer
   * is and the default sentence would be an instruction to use a control that is not on screen.
   */
  capHint?: string;
  /**
   * After a value is picked, close and move focus to the next field. On by default: these forms are
   * filled top-to-bottom in one pass, and leaving focus parked on the trigger makes the next Tab
   * land somewhere the researcher did not expect. Set false for a dropdown that FILTERS the screen
   * it sits on (a list funnel, a taxonomy switcher) rather than filling in a form field — there,
   * jumping focus away from the control you are adjusting is wrong.
   */
  advanceOnSelect?: boolean;
  /**
   * THE PANEL'S FILTER BOX DRIVES A SERVER QUERY INSTEAD OF FILTERING THE ARRAY IT WAS HANDED.
   *
   * **Absent — the default — is exactly what this control did before the prop existed**: the box
   * appears at `SEARCH_THRESHOLD` or where `searchable` says so, `filterOptions` narrows the array
   * locally, and the empty arm is the two-way one. Nothing about an existing call site changes.
   *
   * Present changes four things and nothing else:
   *
   * 1. `withSearch` is FORCED TRUE, `searchable={false}` included. A server query with no box is a
   *    box the reader cannot reach, so the two props cannot both be honoured and this one wins.
   * 2. The local `filterOptions` pass is BYPASSED — see `useSelectList`'s `serverAnswered`.
   * 3. The empty arm becomes three-way: pending, matched-nothing-ON-THE-SERVER, nothing-here-at-all.
   *    See `emptyRowText`.
   * 4. The pin snapshot is re-taken when the ANSWER changes rather than per keystroke — see the
   *    `options`-identity effect in the body, and `useSelectList` for the defect that governs it.
   *
   * The caller owns the debounce, the generation counter and the page size. Everything about the
   * shape, and the diacritic folding it costs, is on `SelectServerQuery`.
   */
  serverQuery?: SelectServerQuery;
  /**
   * Draws a first, ungrouped row carrying `value: ""` with this label, and makes the trigger read it
   * back when `value === ""`.
   *
   * **Absent — the default — is exactly today's behaviour**: no such row, and an empty `value` draws
   * the `placeholder`. Every existing caller keeps the DOM it has.
   *
   * This is how a record is UN-FILED. Pass the sentence that says what "" means on this particular
   * field — they are not interchangeable, and DROPDOWN_DESIGN §2.7 collapses the nine strings this
   * app currently uses down to four with genuinely different meanings ("Not filed under a design
   * workshop", "Not linked to a workshop", "Don't attach it yet", "Do not link a workshop — type the
   * details below"). Do NOT pass "All workshops" or any other everything-means-everything string: a
   * control that FILTERS a screen says "everything" by absence and not by a row, and a none row
   * there would give one state two spellings.
   *
   * Why the trigger reads the label back rather than keeping the placeholder: the row draws its
   * check mark when `value === ""`, so a trigger still saying "Select a design workshop" would have
   * the control disagreeing with itself about its own answer in the same glance. It keeps the muted
   * `ink-500` rung, because "deliberately not filed" and "not yet answered" must still be
   * distinguishable down a form of ten fields.
   *
   * The row is hidden while a filter term is active, is exempt from the render cap, and is not
   * counted in any sentence under the list — `noneOptionFor` says why for each.
   */
  noneLabel?: string;
  /**
   * Offer the term the reader typed as an answer of its own, under the list.
   *
   * **Absent — the default — is exactly today's behaviour** and every existing call site keeps the
   * DOM it has. Present turns this from a picker into a CREATABLE COMBO: the list is still the
   * fast path and a name that is not on it is still answerable. The whole contract, and the
   * objection it answers, is on `SelectCreateAction`.
   *
   * It forces the filter box on, because the box is where the term comes from.
   */
  createAction?: SelectCreateAction;
};

export function SearchableSelect({
  value,
  onChange,
  options,
  placeholder = "Select",
  emptyLabel = "No options",
  disabled,
  className,
  ariaLabel,
  describedBy,
  searchable,
  capHint,
  advanceOnSelect = true,
  serverQuery,
  noneLabel,
  createAction
}: SearchableSelectProps) {
  const [open, setOpen] = useState(false);
  /**
   * The filter term WHEN THIS CONTROL OWNS IT, which is every control that does not pass
   * `serverQuery`. With one, the term is the caller's state and this stays unread — a second copy
   * would be a second answer to "what is in the box".
   */
  const [ownQuery, setOwnQuery] = useState("");
  const [highlight, setHighlight] = useState(0);
  /**
   * Has the reader MOVED the highlight, as against it merely defaulting to row 0?
   *
   * The difference decides whether a plain Tab out of the filter box may commit — see `onTabForward`.
   * Held as state rather than a ref because `onTabForward` is a `useCallback` that reads it, and a
   * ref written in a handler and read across a render is the shape React's compiler lint refuses.
   */
  const [highlightTouched, setHighlightTouched] = useState(false);
  /** The selections pinned above the render cap, frozen between opens — see `useSelectList`. */
  const [pins, setPins] = useState<ReadonlySet<string>>(EMPTY_PINS);
  const wrapperRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const baseId = useId();
  const listboxId = `${baseId}-listbox`;
  const triggerId = `${baseId}-trigger`;
  const typeahead = useTypeahead();
  const fieldLabelId = useFieldLabelId();

  /**
   * A boolean rather than `serverQuery` itself wherever a hook dependency is wanted.
   *
   * The prop is an object literal at every call site, so its identity changes on every render of the
   * caller. Put in a dependency array it would re-run the effect below on every keystroke of an
   * unrelated field — which, for the effect that re-takes the pin snapshot, is precisely the
   * per-keystroke behaviour the snapshot exists to prevent.
   */
  const serverDriven = serverQuery != null;
  /** The box, whoever owns it. Every read of the term in this component goes through here. */
  const query = serverQuery ? serverQuery.value : ownQuery;

  /**
   * Write the term, wherever it lives.
   *
   * Note what is NOT here: a `""` write on close. With `serverQuery` the term belongs to the caller
   * and clearing it would fire a fetch of the unnarrowed list every time a reader dismissed the
   * menu, throwing away the narrowing they had just done. `close` clears `ownQuery` only, which is
   * inert on that branch, so a server-backed panel reopens showing what the reader last typed —
   * which is also what the three controls that mount their own `SearchInput` above the field already
   * do today.
   */
  function writeQuery(next: string) {
    if (serverQuery) serverQuery.onChange(next);
    else setOwnQuery(next);
  }

  /**
   * `serverQuery` AND `createAction` BOTH FORCE the box on, ahead of `searchable` and the count.
   *
   * A server query with no box to type into is a fetch the reader cannot reach; a create action
   * with no box is an answer nobody can type, because the term IS the answer. Neither has a
   * sensible reading alongside `searchable={false}`, so both win over it. Stated here rather than
   * left to the `??` chain, because "the panel decided" is the answer this file exists to stop
   * giving.
   */
  const withSearch = serverDriven || createAction != null || (searchable ?? options.length >= SEARCH_THRESHOLD);
  const { filtered, windowed, pinned, capped } = useSelectList(
    options,
    query,
    withSearch,
    pins,
    serverDriven
  );

  /**
   * The "none" option, if this caller asked for one — used twice, and gated in only one of them.
   *
   * `noneRow` is the option AS A ROW, withdrawn while a filter term is active because it is not a row
   * of the corpus and a reader who is typing is hunting rather than un-filing. `noneOption` is the
   * same object as the TRIGGER'S FALLBACK LABEL, which is never withdrawn: the trigger describes the
   * field's answer, and an answer that changed because somebody typed into a filter box would be
   * reporting the state of the panel instead of the state of the record. See `noneOptionFor`.
   *
   * The row is PREPENDED after the cap and before the highlight, and both halves matter. After the
   * cap, so an un-file row can never be the row a truncation drops and so it is not counted in the
   * sentence that describes the corpus. Before the highlight, so it is index 0 of the array
   * `highlight` indexes and every existing index rule applies to it unchanged.
   */
  const noneOption = useMemo(() => noneOptionFor(noneLabel, options), [noneLabel, options]);
  const noneRow = noneOption && !query.trim() ? noneOption : null;
  const rendered = useMemo(
    () => (noneRow ? [noneRow, ...windowed] : windowed),
    [noneRow, windowed]
  );

  /**
   * The name this control would create, or null when it must not offer to.
   *
   * FOLDED ON BOTH SIDES for the comparison, never raw. `fold` collapses runs of whitespace and
   * strips diacritics, so a reader who typed the name of a workshop that is already on screen with
   * one stray double space, or with "Ahmedabad" where the row reads "Ahmedābād", is not offered a
   * second row that stores a string the repository will never group with the first. That is the
   * whole failure a name-shaped free-text field has, arriving through the control that was supposed
   * to close it.
   *
   * Compared against `options` and not `filtered`: what makes an offer a duplicate is that the name
   * EXISTS, not that the current query happens to have matched it — and the two differ the moment
   * the render cap bites or a server answer lags the box.
   */
  const createTerm = useMemo(() => {
    if (!createAction) return null;
    const term = query.trim();
    if (!term) return null;
    const folded = fold(term);
    if (options.some((option) => fold(option.label) === folded)) return null;
    return term;
  }, [createAction, query, options]);

  // Derived, not stored — the stored index is only ever a hint. See the file header.
  const safeHighlight =
    highlight >= 0 && highlight < rendered.length && !rendered[highlight].disabled
      ? highlight
      : firstEnabled(rendered);
  const activeId = safeHighlight >= 0 ? `${baseId}-opt-${safeHighlight}` : undefined;
  useScrollHighlightIntoView(open, safeHighlight, baseId);

  /**
   * The row the trigger reads back — the caller's, or the "none" row when the value is cleared.
   *
   * `options` never carries the none row, so without the fallback a `noneLabel` control with an
   * empty value would tick "Not filed under a design workshop" inside the panel while the trigger
   * outside it still read "Select a design workshop". A control that disagrees with itself about its
   * own answer in one glance is worse than one that cannot express the answer at all.
   *
   * ── AND THE ONE THING THIS DELIBERATELY DOES NOT DO ─────────────────────────────────────────────
   * A `value` that matches NO option — a stored id the current page does not contain — still reads
   * as the placeholder, and this primitive will not invent a row for it. Recovering an off-page
   * value is a decision only the call site can make: `WorkshopSelect` fetches the record's own
   * workshop by id and merges it back in under its own heading *"because hiding the row would
   * convert a read-only fact into a wrong write"*, while `AdoptLocalDraftDialog` refuses to, because
   * the write it authorises is one-way and unrepeatable. A default here would silently pick one of
   * those for twenty call sites; DROPDOWN_DESIGN §2.9 makes it a REQUIRED prop on the option
   * builders for exactly that reason, and it is not a prop of this file. The primitive would also
   * pick wrong for a reason that has nothing to do with the ruling: options arrive over the network,
   * so for the second and a half before they land EVERY value is unmatched, and a panel that
   * announced it would spend that time telling readers their record had lost its workshop.
   */
  const selected =
    options.find((option) => option.value === value) ??
    (noneOption && value === "" ? noneOption : undefined);
  /** Kept apart from `selected` so the trigger can hold the muted rung — see `noneLabel`. */
  const showingNone = selected != null && selected === noneOption;

  const close = useCallback(() => {
    setOpen(false);
    // `ownQuery` only. A caller's `serverQuery.value` is theirs; see `writeQuery`.
    setOwnQuery("");
  }, []);

  /** Close, then hand the keyboard on. `advanceOnSelect` decides whether "on" means the next field. */
  const closeAndMoveOn = useCallback(
    (advance: boolean) => {
      close();
      // After the state flush, so the panel has unmounted and the walker sees the settled DOM;
      // otherwise the still-open listbox can swallow the focus we are trying to move on.
      requestAnimationFrame(() => {
        if (advance && focusNextField(wrapperRef.current)) return;
        triggerRef.current?.focus({ preventScroll: true });
      });
    },
    [close]
  );

  function choose(index: number) {
    const option = rendered[index];
    if (!option || option.disabled) return;
    onChange(option.value);
    closeAndMoveOn(advanceOnSelect);
  }

  /**
   * Take the typed term as the answer, and then behave exactly as a picked row does.
   *
   * `closeAndMoveOn(advanceOnSelect)` and not a bare `close()`: to the reader this WAS the answer,
   * so a form that advanced on every other field and parked on this one would be teaching two
   * different things about the same keystroke.
   */
  function create() {
    if (!createAction || !createTerm) return;
    createAction.onCreate(createTerm);
    closeAndMoveOn(advanceOnSelect);
  }

  function openPanel(seed = "") {
    // A PLAIN OPEN MUST NOT DISTURB A SERVER TERM. Clearing `ownQuery` on open is right — it is this
    // panel's own box and `close` cleared it anyway — but writing "" into a caller's `serverQuery`
    // would re-fetch the unnarrowed list every time a reader glanced at the menu. A SEEDED open is
    // different and is written on both branches: a printable key on a closed trigger means "start
    // looking for this", which is what the native <select>, the ARIA combobox pattern and every
    // other branch of this file already do with the first letter.
    if (seed || !serverDriven) writeQuery(seed);
    setOpen(true);
    // The window's pinned rows are recomputed HERE, at every query change, and — on a `serverQuery`
    // control — when the answer lands. Nowhere else. See `useSelectList` on why a live selection set
    // renumbers rows under a stationary highlight.
    setPins(value ? new Set([value]) : EMPTY_PINS);
    if (seed) {
      // A seeded open cannot look up the current value's row: `rendered` in this render was still
      // computed from the EMPTY query, so its indices describe a list the reader is about to stop
      // looking at. Row 0 of the seeded list is both correct and what the reader expects — it is
      // the same promise `onQueryChange` makes, that typing re-aims Enter at the top match.
      setHighlight(0);
      setHighlightTouched(false);
      return;
    }
    // Indexed against `rendered`, which is what the highlight means everywhere else. Against the
    // raw options array a pinned selection past the cap would highlight whatever happened to sit at
    // that index instead.
    const at = rendered.findIndex((option) => option.value === value);
    setHighlight(at >= 0 && !rendered[at].disabled ? at : firstEnabled(rendered));
    // Landing on the row that is already selected is not the reader having chosen anything, so a
    // Tab straight back out must not "commit" it — see `onTabForward`.
    setHighlightTouched(false);
  }

  /**
   * A printable key on a searchable trigger starts the search, rather than being swallowed.
   *
   * Closed is the common case and needs nothing extra: `openPanel(char)` mounts the filter box with
   * the character already in it, and `useFilterBoxFocus` claims the keyboard off this trigger as it
   * mounts, so the caret lands after the letter the reader typed and the next one keeps filtering.
   *
   * Open-with-focus-on-the-trigger happens when somebody Shift+Tabs back out of the panel, and
   * there the box already exists, so its mount-time focus will never fire again. Appending to the
   * query without also moving the caret into the box is the worse half of a fix: the list narrows,
   * and then Backspace does nothing at all because the keystrokes are still going to a button.
   */
  function seedFilter(char: string) {
    if (!open) {
      openPanel(char);
      return;
    }
    writeQuery(query + char);
    setHighlight(0);
    setHighlightTouched(false);
    if (!serverDriven) setPins(value ? new Set([value]) : EMPTY_PINS);
    document.getElementById(`${baseId}-search`)?.focus();
  }

  /**
   * Typing in the box always re-aims Enter at the top match.
   *
   * The pin snapshot is re-taken here on a LOCAL box, where the keystroke and the new array are the
   * same render. On a `serverQuery` box it deliberately is not: the array does not change until the
   * debounced answer lands several renders later, so pinning here would pin against the list the
   * reader is about to stop looking at and then leave the pins stale for the one that replaces it.
   * The `options`-identity effect below is where that branch re-snapshots instead.
   */
  function onQueryChange(next: string) {
    writeQuery(next);
    setHighlight(0);
    setHighlightTouched(false);
    if (!serverDriven) setPins(value ? new Set([value]) : EMPTY_PINS);
  }

  /**
   * THE THIRD MOMENT A PIN SNAPSHOT IS TAKEN, and the only one that is an effect.
   *
   * A server-searched panel replaces `options` when each debounced answer lands. `useSelectList`
   * computes the window from `filtered` and `pins` together, so an answer arriving against a
   * snapshot taken for the PREVIOUS answer pins values that are no longer in the list and misses the
   * ones that are — and every row past the window renumbers under a highlight that kept its old
   * index. That is the defect `useSelectList` describes at length, reached by a different door: on
   * the design-workshop viewer picker, a permissions control, the row a reader is looking at and the
   * row the next Enter takes stop being the same row.
   *
   * Keyed on `options` IDENTITY, which is the only signal that an answer landed — the length can be
   * unchanged, the rows can be unchanged, and it is still a different answer to a different term.
   * `serverDriven` is a boolean and not the prop itself for the reason given where it is declared.
   * `value` is safe in the dependency list on a single-select because picking one closes the panel;
   * the multi-select cannot do the same and says why.
   */
  useEffect(() => {
    if (!serverDriven) return;
    setPins(value ? new Set([value]) : EMPTY_PINS);
  }, [serverDriven, options, value]);

  /**
   * Tab forward off the last control in the panel: leave, and commit ONLY a choice the reader made.
   *
   * ── WHY THIS NO LONGER COMMITS THE TOP MATCH, WHICH IT USED TO AND DOCUMENTED ─────────────────
   *
   * The old rule was "type three letters, Tab, carry on": the highlight is what the reader is
   * looking at, so leaving without it would discard a choice already made visually. That reads well
   * and it is wrong, because on a single-select the panel's only tab stop is the filter box, so the
   * forward branch is taken by EVERY plain Tab — and the highlight it committed was row 0 of the
   * query, which `onQueryChange` sets without the reader having pointed at anything.
   *
   * The sequence that costs a record: the field holds "Bamboo comb"; a researcher opens it, types
   * "cot" to check whether a cotton tool exists, and Tabs on. The field now reads "Cotton hank",
   * the answer they had is gone, and nothing on screen said so. That is silent data loss on a
   * keystroke whose whole meaning is "move on without doing anything", and this control exists to
   * prevent exactly the class of mis-entry it was causing.
   *
   * So a commit now needs the highlight to have been MOVED — an arrow key, Home, End, or the pointer
   * over a row. All four are deliberate acts aimed at a specific row, and all four still let the
   * fast keyboard route work: type, arrow to confirm what you are on, Tab. Typing alone leaves the
   * value alone. Enter is untouched and remains the way to take the top match in one keystroke.
   */
  const onTabForward = useCallback(() => {
    if (highlightTouched && safeHighlight >= 0 && rendered[safeHighlight]) {
      onChange(rendered[safeHighlight].value);
    }
    closeAndMoveOn(true);
  }, [highlightTouched, safeHighlight, rendered, onChange, closeAndMoveOn]);

  const onTabBackward = useCallback(() => closeAndMoveOn(false), [closeAndMoveOn]);
  const onPanelKeyDown = useEdgeTab(panelRef, onTabForward, onTabBackward);

  /**
   * Arrow/Enter/Home/End, shared by the trigger (short lists) and the search box (long ones).
   *
   * `textEntry` says the keystroke arrived in the filter box, and it exists for Home and End alone.
   * Both used to `preventDefault()` whenever the panel was open — which, in the filter box, is
   * always, because the box only exists while it is open. So typing "bamoo comb" and pressing Home
   * to fix the front left the caret exactly where it was and jumped the listbox instead: the one
   * key a reader reaches for to repair a typo was taken away by the control that made the typo
   * worth repairing. Every other text field in this app puts Home and End on the text, and the ARIA
   * editable-combobox pattern says the same — the caret keys belong to the caret. Arrow keys stay
   * with the list in both places, which is the pattern's other half and is what makes the box
   * navigable at all.
   */
  function navigate(event: React.KeyboardEvent, textEntry = false) {
    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        if (open) {
          setHighlight(stepHighlight(rendered, safeHighlight, 1));
          setHighlightTouched(true);
        } else openPanel();
        return true;
      case "ArrowUp":
        event.preventDefault();
        if (open) {
          setHighlight(stepHighlight(rendered, safeHighlight, -1));
          setHighlightTouched(true);
        } else openPanel();
        return true;
      case "Home":
        if (!open || textEntry) return false;
        event.preventDefault();
        setHighlight(stepHighlight(rendered, -1, 1));
        setHighlightTouched(true);
        return true;
      case "End":
        if (!open || textEntry) return false;
        event.preventDefault();
        setHighlight(stepHighlight(rendered, -1, -1));
        setHighlightTouched(true);
        return true;
      case "Enter":
        if (!open) return false;
        event.preventDefault();
        // Guarded on the DERIVED index, so Enter can only ever take a row that is on screen.
        if (safeHighlight >= 0) choose(safeHighlight);
        // THE ONLY ARM THAT REACHES THE CREATE ROW, and the guard is what keeps it safe. With any
        // row highlightable Enter still means "take the top match", unchanged for every control in
        // the app; with none — the term matched nothing — it used to mean nothing at all, which on a
        // creatable combo is a dead key on the one keystroke a reader will try after typing a name
        // the list does not hold. See `SelectCreateAction`.
        else if (createTerm) create();
        return true;
      default:
        return false;
    }
  }

  /** The filter box's own keydown: `navigate`, told that a caret lives here. */
  function onFilterKeyDown(event: React.KeyboardEvent<HTMLInputElement>) {
    navigate(event, true);
  }

  function onTriggerKeyDown(event: React.KeyboardEvent) {
    if (navigate(event)) return;
    if (event.key === " ") {
      event.preventDefault();
      if (open) {
        if (safeHighlight >= 0) choose(safeHighlight);
      } else openPanel();
      return;
    }
    if (event.key === "Tab" && open) close();
    if (isPrintable(event)) {
      event.preventDefault();
      // With a filter box, the letter belongs in it — see `seedFilter`.
      if (withSearch) {
        seedFilter(event.key);
        return;
      }
      // Native-<select> type-ahead, for the short lists that have no filter box to do it properly.
      //
      // TWO LOOKUPS, BECAUSE THE TWO BRANCHES ANSWER IN DIFFERENT CURRENCIES. Closed, the keystroke
      // sets a VALUE, so it searches the whole `options` corpus — nothing is hidden from it. Open,
      // it sets a HIGHLIGHT, and `highlight` indexes `rendered`, which is `options` only while the
      // render cap does not bite. A single lookup against `options` writing into the highlight is
      // what shipped, and past 80 rows on a non-searchable list — the design workshop viewer picker,
      // a permissions control over up to 2000 accounts — a letter highlighted an unrelated row and
      // the Space that followed ticked that person. Everything else in this file is scrupulous about
      // the index domain (see `openPanel`); this line was not.
      //
      // A `noneLabel` ROW IS IN THE FIRST LOOKUP AND DELIBERATELY NOT IN THE SECOND, and the split
      // falls out of the same rule. Open, the keystroke moves a highlight the reader can see and
      // then confirm, so the un-file row is a legitimate destination like any other. Closed, it
      // WRITES — and "n" quietly clearing a record's design workshop is a destructive edit from one
      // keystroke aimed at finding something. `options` never carries the row, so the closed branch
      // cannot reach it; that is the behaviour and not an oversight.
      const typed = typeahead(event.key);
      if (open) {
        const at = typeaheadIndex(rendered, typed);
        if (at < 0) return;
        setHighlight(at);
        setHighlightTouched(true);
        return;
      }
      const match = typeaheadIndex(options, typed);
      if (match < 0) return;
      onChange(options[match].value);
    }
    // Escape is not handled here: AnchoredPopover takes it on a window-capture listener, closes the
    // topmost panel and restores focus to `restoreFocusRef`, which is this trigger.
  }

  const inputRef = useFilterBoxFocus(triggerRef);
  const groups = useMemo(() => groupRows(rendered), [rendered]);

  /**
   * `term` is blanked where there is no filter box, which is how "this control is not searching" is
   * expressed to `listAnnouncement` without a fifth flag. The non-server arms are byte-for-byte what
   * this control has always announced.
   */
  const announcement = listAnnouncement({
    total: options.length,
    matched: filtered.length,
    term: withSearch ? query : "",
    server: serverDriven,
    pending: serverQuery?.pending ?? false,
    truncated: serverQuery?.truncated ?? false
  });

  /**
   * Both counts are the CORPUS's — `windowed`, never `rendered` — so a "none" row is not folded into
   * "the first 81 of 246". See the file header's fifth decision.
   */
  const capAdvice = capHint ?? (withSearch ? CAP_HINT_WITH_SEARCH : CAP_HINT_WITHOUT_SEARCH);
  const capSentence = truncationSentence({
    shown: windowed.length - pinned,
    pinned,
    total: filtered.length,
    capped,
    term: query,
    hint: capAdvice,
    serverTruncated: serverQuery?.truncated ?? false
  });

  return (
    // `min-w-0` is load-bearing, not tidying. A grid or flex item defaults to `min-width: auto`,
    // which refuses to shrink below its content's intrinsic width — so a long option label widened
    // this wrapper past its column and the trigger overlapped the field beside it. The `truncate`
    // on the label inside cannot prevent that on its own: it clips text within a box that has
    // already been allowed to grow. The box has to be allowed to shrink first.
    <div ref={wrapperRef} className={cn("relative min-w-0", className)}>
      <button
        ref={triggerRef}
        type="button"
        disabled={disabled}
        /**
         * WHY THE ROLE CHANGES WITH THE SEARCH BOX, AND WHY IT IS NOT COSMETIC.
         *
         * `aria-activedescendant` is how an arrow-key highlight is announced, and it is only
         * supported on a handful of roles — `combobox`, `listbox`, `textbox`, a few others. It is
         * NOT supported on `button`. So on a short list, where this trigger keeps the keyboard and
         * there is no filter box to hand it to, arrowing through Gender / Status / Yes-No set an
         * attribute every screen reader ignored: the highlight moved on screen and nothing at all
         * was spoken. That is the identical mistake this file already documents two hundred lines
         * up for `aria-invalid` — an attribute written on a role that does not take it, which reads
         * in the source like the feature is there.
         *
         * `role="combobox"` on the trigger is the fix rather than a workaround: it is exactly the
         * ARIA select-only combobox — a collapsed control that owns a listbox, reports
         * `aria-expanded`, points at the popup with `aria-controls` and names the current row with
         * `aria-activedescendant`. Nothing else about the element changes; it stays a real <button>
         * so Enter and Space still open it and the tab order is still DOM order.
         *
         * It is conditional because the searchable branch has a real combobox INSIDE the panel (the
         * filter box, which owns `aria-activedescendant` there). Two nested comboboxes for one
         * question describe a control that does not exist, and the trigger on that branch is
         * honestly just a button that opens a search panel.
         */
        role={withSearch ? undefined : "combobox"}
        id={triggerId}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={ariaLabel}
        /**
         * THE NAME A `Field` WRAPPER GIVES THIS CONTROL, when the call site gave it none.
         *
         * A `<label>` cannot name a `<button>` — HTML-AAM computes a button's name from its own
         * contents and the label association plays no part — so `Field label="Craft"` wrapped around
         * this control announced "Bamboo, combobox": the answer, twice, and the question never. That
         * was live at forty-four call sites. `useFieldLabelId` is how the wrapper hands its label id
         * down without forty-four edits; see `ui/fieldLabel.tsx` for why it is a context.
         *
         * BOTH IDS, IN THIS ORDER. `aria-labelledby` replaces name-from-content, so naming only the
         * label would announce "Craft" and lose the value — swapping one half-named control for the
         * other. Referencing the label AND this button concatenates them: "Craft Bamboo". Skipped
         * entirely when `ariaLabel` is set, because that string is the call site's deliberate answer
         * and, on the multi-select, already carries the selection summary.
         */
        aria-labelledby={!ariaLabel && fieldLabelId ? `${fieldLabelId} ${triggerId}` : undefined}
        aria-describedby={describedBy}
        aria-controls={open ? listboxId : undefined}
        // Only when the trigger itself owns the keyboard; with a search box the input does. Read
        // together with the role above: this attribute is inert without it.
        aria-activedescendant={open && !withSearch ? activeId : undefined}
        onClick={() => (open ? close() : openPanel())}
        onKeyDown={onTriggerKeyDown}
        // Marks the trigger as a form field for lib/formNav: without it the walker cannot locate
        // this control, focusNextField() bails, and picking a value leaves focus parked here
        // instead of moving on to the next field.
        data-form-field=""
        data-searchable-select=""
        className={TRIGGER_CLASS}
      >
        {/*
          `ink-500`, NOT the `ink-300` placeholder rung, and the distinction is what the token
          ladder does not draw for us. `ink-300` is 2.44:1 on the card in light mode and 3.33:1 in
          dark — below AA either way — which is defensible for a `::placeholder` hovering behind
          text somebody is about to type over, and indefensible here: while nothing is selected this
          span is the ONLY text the control has. "Select one or more workshops", "Search and select",
          "Add more artisans to this set" — the whole question the control is asking is carried by
          it, and a designer with low vision was being asked to answer a question they could not
          read. High contrast mode did re-point the rung, but a preference nobody has found yet is
          not a substitute for the default meeting AA.
        */}
        {/*
          `showingNone` keeps the muted rung, which is the same argument one rung along. The trigger
          reads the "none" row's label back so the control does not contradict the tick inside its own
          panel, but "deliberately not filed under a design workshop" and "a design workshop is
          filled in" must still be distinguishable at a glance down a form of ten fields — and the
          only thing carrying that difference on a collapsed control is the weight of the text.
        */}
        <span
          className={cn("min-w-0 truncate", (!selected || showingNone) && "text-ink-500")}
          title={selected?.label}
        >
          {selected ? selected.label : placeholder}
        </span>
        <ChevronDown
          className={cn("h-4 w-4 shrink-0 text-ink-500 transition-transform", open && "rotate-180")}
          aria-hidden
        />
      </button>

      <AnchoredPopover
        open={open}
        onClose={close}
        anchorRef={wrapperRef}
        restoreFocusRef={triggerRef}
        label={ariaLabel ? `${ariaLabel} options` : "Options"}
        offset={4}
        matchAnchorWidth
        maxWidth={PANEL_MAX_WIDTH}
        className={PANEL_CLASS}
      >
        <div ref={panelRef} {...containEvents(onPanelKeyDown)} className="flex min-h-0 flex-col">
          {withSearch ? (
            <SearchRow
              inputRef={inputRef}
              inputId={`${baseId}-search`}
              listboxId={listboxId}
              activeId={activeId}
              query={query}
              onQueryChange={onQueryChange}
              onKeyDown={onFilterKeyDown}
              /*
                THE BOX IS NAMED FOR WHAT IT ACTUALLY REACHES. A local box filters the rows it was
                handed; a `serverQuery` box searches the whole list. Calling the second one "Filter"
                understates it to precisely the readers who most need to know — somebody navigating
                by the control's accessible name has no panel in front of them to infer it from, and
                "Filter workshops" reads as a promise that only the drawn rows are in play.
              */
              placeholder={serverDriven ? "Type to search" : "Type to filter"}
              label={
                serverDriven
                  ? ariaLabel
                    ? `Search ${ariaLabel}`
                    : "Search options"
                  : ariaLabel
                    ? `Filter ${ariaLabel}`
                    : "Filter options"
              }
              pending={serverQuery?.pending}
            />
          ) : null}
          <ul
            id={listboxId}
            role="listbox"
            aria-label={ariaLabel ?? "Options"}
            className="min-h-0 max-h-72 shrink overflow-y-auto overscroll-contain py-1"
          >
            {/*
              GROUPED WHEN A CALLER GROUPED, FLAT OTHERWISE, and the row indices are the same either
              way — see `groupRows`. The wrapper is a `role="group"` with the heading's text as its
              `aria-label`, and the inner `<ul>` is `role="none"` so the options are still OWNED by
              the listbox: nesting a real list inside would put a list between a listbox and its
              options and break the ownership the highlight is announced through.
            */}
            {groups.map((bucket) => {
              const rows = bucket.rows.map(({ option, index }) => {
                const active = option.value === value;
                return (
                  <li
                    key={option.value}
                    id={`${baseId}-opt-${index}`}
                    role="option"
                    aria-selected={active}
                    aria-disabled={option.disabled || undefined}
                    title={optionTitle(option)}
                    onMouseEnter={() => {
                      if (!option.disabled) {
                        setHighlight(index);
                        setHighlightTouched(true);
                      }
                    }}
                    // Keeps focus in the search box so the reader can keep typing after a mis-click,
                    // and stops the browser hunting for a focus target as the row is removed.
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={() => choose(index)}
                    className={optionClass(option, index === safeHighlight, active)}
                  >
                    <OptionText option={option} />
                    {active ? (
                      <Check className="h-4 w-4 shrink-0 text-purple-700 dark:text-purple-300" aria-hidden />
                    ) : null}
                  </li>
                );
              });
              /*
                A KEYED `Fragment` FOR THE UNGROUPED RUN, not a bare array. React does flatten a
                nested array, but a keyed wrapper is what makes the two branches of this map return
                the same SHAPE of child — and the day somebody adds a second ungrouped bucket, an
                unkeyed array is the reconciliation bug that follows.
              */
              if (!bucket.group) return <Fragment key="ungrouped">{rows}</Fragment>;
              return (
                <li key={`group-${bucket.group}`} role="group" aria-label={bucket.group}>
                  <GroupHeading name={bucket.group} />
                  <ul role="none">{rows}</ul>
                </li>
              );
            })}
            {/*
              THE EMPTY LINE IS ASKED OF THE CORPUS AND DRAWN LAST, and both are changes a `noneLabel`
              control needs.

              Asked of `windowed` rather than `rendered`, because a panel whose only row is the "none"
              row is still a panel with nothing to pick: reading `rendered` would suppress the one
              sentence that says whether the list is empty, still loading, or failed to load — which
              is the "absence read as non-existence" bug arriving through the fix for a different one.

              Drawn after the rows rather than before them, because the "none" row is the only
              actionable thing in an empty list and the sentence is the explanation of what is missing
              beneath it. For every control that passes no `noneLabel` this is the same DOM it has
              always rendered: an empty corpus means `groups` contributes nothing at all.
            */}
            {windowed.length === 0 ? (
              <li className="px-3.5 py-2 text-sm text-ink-500">
                {emptyListSentence({
                  emptyLabel,
                  term: query,
                  server: serverDriven,
                  pending: serverQuery?.pending ?? false
                })}
              </li>
            ) : null}
          </ul>
          {/*
            THE CREATE ROW — a real <button>, OUTSIDE the listbox, drawn under the options.

            Outside `<ul role="listbox">` and not merely outside `rendered`: an interactive element
            that is not a `role="option"` sitting inside a listbox is a child the ownership model has
            no name for, and a screen reader announcing it as one of N options would be describing a
            record that does not exist. As a sibling it is simply the panel's last control, which is
            what it is.

            It lands last in `useEdgeTab`'s DOM-order scan, so on a control that offers it the panel
            has two tab stops rather than one: Tab out of the filter box now reaches this button
            instead of leaving. That is the right order — the reader has just typed the thing the
            button is offering — and Enter in the box still commits without ever visiting it.

            `onMouseDown` is prevented for the same reason every option row prevents it: the click
            must not pull focus out of the filter box before the click itself lands.
          */}
          {createTerm && createAction ? (
            <button
              type="button"
              onMouseDown={(event) => event.preventDefault()}
              onClick={create}
              /*
                THE ROW'S OWN COLOURS, NOT A NEW SET. `bg-purple-50 dark:bg-purple-950` on hover and
                `text-purple-700 dark:text-purple-300` are exactly what `optionClass` paints on a
                highlighted and on a selected row, so the create row reads as part of the same list
                even though it is not in it. Brand purple does not invert, so the `dark:` pair is the
                exception mechanism doing its one legitimate job: without it a purple-50 wash under
                purple-700 text is a near-white band on a dark panel.
              */
              className="shrink-0 truncate border-t border-line-200 px-3.5 py-2 text-left text-sm font-medium text-purple-700 transition hover:bg-purple-50 dark:text-purple-300 dark:hover:bg-purple-950"
            >
              {createAction.label(createTerm)}
            </button>
          ) : null}
          {capSentence ? <CapNotice sentence={capSentence} /> : null}
          <p className="sr-only" role="status" aria-live="polite">
            {announcement}
          </p>
        </div>
      </AnchoredPopover>
    </div>
  );
}

export type SearchableMultiSelectProps = {
  values: string[];
  onChange: (values: string[]) => void;
  options: SelectOption[];
  placeholder?: string;
  emptyLabel?: string;
  disabled?: boolean;
  className?: string;
  ariaLabel?: string;
  /**
   * Ids of the paragraphs that describe this control — a field's hint, and the message a save came
   * back with. A themed dropdown is a <button>, so a caller cannot reach it with a plain
   * `<label htmlFor>` and had no way at all to attach a refusal to it.
   *
   * THERE IS DELIBERATELY NO `invalid` COMPANION. `aria-invalid` is not a global state and the
   * `button` role does not support it, so setting it here would be ignored by every screen reader
   * while looking, in the source, like the field was marked. The trigger now carries
   * `role="combobox"` on the branch where it owns the keyboard (see the note on the element), which
   * WOULD support `aria-invalid` — but only there: with a filter box the trigger is still a plain
   * button and the combobox is the box inside the panel. A prop honoured on short lists and
   * silently dropped on long ones is worse than no prop, because the call site cannot see which
   * kind of list it has. Until both branches can carry it the refusal reaches the reader through
   * `describedBy` and its own live region.
   */
  describedBy?: string;
  /** Same rule as the single-select's `searchable` — see `SearchableSelectProps`. */
  searchable?: boolean;
  /** Same contract as the single-select's `capHint` — the last clause of the cap footer. */
  capHint?: string;
  /**
   * Show a Confirm button in the panel once at least one option is ticked; confirming closes and
   * moves to the next field.
   *
   * A multi-select cannot advance on click the way a single-select does — picking one option is
   * usually not the end of the answer — so without an explicit "done" the researcher has to know to
   * click away, and the form gives no signal that the answer was registered. Set false where the
   * control filters a list in place rather than answering a form field.
   */
  confirmOnSelect?: boolean;
  confirmLabel?: string;
  /**
   * Draw the "Select all N" / "Clear all N" button. **`true` by default, which is what this control
   * has always done** — no existing caller changes.
   *
   * ── PASS `false` ON EVERY FILTER, AND THE REASON IS NOT TASTE ────────────────────────────────────
   * Wired to a filter, that button manufactures the one state this repository forbids: all ticked
   * and nothing ticked, both meaning "everything". A filter with two spellings for one state has no
   * way to tell a default from a deliberate choice, and the query it builds differs between them —
   * `WorkshopScopeSelect` returns `undefined` for "no scope" precisely so that the absent case is
   * absent on the wire rather than an enumeration of every row the page happened to have loaded.
   * Which is the other half of it: "all" over a server-truncated page is not all. On a control whose
   * options are the first eighty of one hundred and ninety-six workshops, "Select all 80" writes a
   * filter for eighty named workshops and silently excludes the other hundred and sixteen, and the
   * screen then reports counts that are wrong in a direction nobody can see.
   *
   * A filter that wants an explicit everything-affordance builds its own button that sets `[]`, the
   * way `WorkshopScopeSelect`'s "All records" does. That one is honest because absence is what it
   * writes.
   *
   * Leave it `true` on a control that ANSWERS A FIELD over a list that is whole — four crafts is four
   * clicks otherwise, which is what the button was added for.
   */
  bulk?: boolean;
  /**
   * The panel's filter box drives a server query — the same prop, the same contract and the same
   * four consequences as the single-select's. See `SearchableSelectProps.serverQuery`.
   *
   * One extra consequence is the multi's alone: with `serverQuery.truncated` set, the bulk button
   * stops saying "all" and says "the N shown", because the rows it can act on are one page of an
   * answer the server has told us is incomplete. See `bulkLabel`.
   *
   * There is deliberately no `createAction` companion either, and for a related reason: a create
   * action commits ONE name and closes, which on a multi-select is a fifth thing the panel does with
   * a selection the reader is still assembling. `SelectCreateAction` is a single-select prop until
   * a call site turns up that genuinely needs to add a name to a set, and that call site owes an
   * argument about what happens to the ticks.
   *
   * There is deliberately no `noneLabel` companion here — a multi-select says "none" by holding an
   * empty array, and a second spelling of that state is the thing `bulk` above exists to prevent.
   */
  serverQuery?: SelectServerQuery;
};

export function SearchableMultiSelect({
  values,
  onChange,
  options,
  placeholder = "Select",
  emptyLabel = "No options",
  disabled,
  className,
  ariaLabel,
  describedBy,
  searchable,
  capHint,
  confirmOnSelect = true,
  confirmLabel = "Confirm",
  bulk = true,
  serverQuery
}: SearchableMultiSelectProps) {
  const [open, setOpen] = useState(false);
  /** This control's own term, unread when `serverQuery` owns it — see the single-select's. */
  const [ownQuery, setOwnQuery] = useState("");
  const [highlight, setHighlight] = useState(0);
  /** The selections pinned above the render cap, frozen between opens — see `useSelectList`. */
  const [pins, setPins] = useState<ReadonlySet<string>>(EMPTY_PINS);
  const wrapperRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const baseId = useId();
  const listboxId = `${baseId}-listbox`;
  const triggerId = `${baseId}-trigger`;
  const typeahead = useTypeahead();
  const fieldLabelId = useFieldLabelId();

  /** A boolean, not the prop, wherever a dependency is wanted — see the single-select's. */
  const serverDriven = serverQuery != null;
  /** The box, whoever owns it. */
  const query = serverQuery ? serverQuery.value : ownQuery;

  /** Write the term wherever it lives. Never "" of the panel's own accord — see the single-select. */
  function writeQuery(next: string) {
    if (serverQuery) serverQuery.onChange(next);
    else setOwnQuery(next);
  }

  /** `serverQuery` forces the box on, ahead of `searchable` and the count — see the single-select. */
  const withSearch = serverDriven || (searchable ?? options.length >= SEARCH_THRESHOLD);
  const chosen = useMemo(() => new Set(values), [values]);
  /**
   * `rendered` and `windowed` are the same array here, because a multi-select draws no "none" row —
   * see `bulk` on why it must not. The alias is kept so every index rule, every comment and every
   * assertion below reads identically in both components; a reader comparing the two should not have
   * to hold in their head that one of them calls the highlight's array something else.
   */
  const { filtered, windowed, pinned, capped } = useSelectList(
    options,
    query,
    withSearch,
    pins,
    serverDriven
  );
  const rendered = windowed;

  const safeHighlight =
    highlight >= 0 && highlight < rendered.length && !rendered[highlight].disabled
      ? highlight
      : firstEnabled(rendered);
  const activeId = safeHighlight >= 0 ? `${baseId}-opt-${safeHighlight}` : undefined;
  useScrollHighlightIntoView(open, safeHighlight, baseId);

  const close = useCallback(() => {
    setOpen(false);
    // `ownQuery` only; a caller's `serverQuery.value` is theirs. See the single-select's `close`.
    setOwnQuery("");
  }, []);

  const closeAndMoveOn = useCallback(
    (advance: boolean) => {
      close();
      requestAnimationFrame(() => {
        if (advance && focusNextField(wrapperRef.current)) return;
        triggerRef.current?.focus({ preventScroll: true });
      });
    },
    [close]
  );

  function toggle(index: number) {
    const option = rendered[index];
    if (!option || option.disabled) return;
    if (chosen.has(option.value)) onChange(values.filter((v) => v !== option.value));
    else onChange([...values, option.value]);
  }

  /**
   * What "select all" acts on, and why it is the filtered set rather than every option.
   *
   * The reader typed to narrow the list; acting on the rows they deliberately filtered OUT would
   * be the one outcome they cannot see coming. So the button scopes itself to the matches, says
   * which it did ("Select 6 matching" vs "Select all 74"), and leaves any selection made outside
   * the current filter completely alone — clearing 6 matches never quietly drops a 7th pick that
   * the query happens to hide. The cap on rendered rows is NOT applied here: the label promises
   * every match, so every match is what it takes.
   *
   * RENAMED FROM `bulk`, WHICH IS NOW THE PROP THAT DECIDES WHETHER ANY OF THIS IS DRAWN. The rows
   * are still computed when the button is off — three array passes over a list already in memory —
   * because gating a `useMemo` behind a second condition buys nothing and hides which of the two
   * names means what.
   */
  const bulkRows = useMemo(() => filtered.filter((option) => !option.disabled), [filtered]);
  const allChosen = bulkRows.length > 0 && bulkRows.every((option) => chosen.has(option.value));
  const filtering = withSearch && query.trim().length > 0;
  /**
   * "ALL" IS A CLAIM, AND OVER A TRUNCATED SERVER ANSWER IT IS A FALSE ONE.
   *
   * With `serverQuery.truncated` the rows this button can reach are one page of an answer the server
   * has already said is incomplete, so both the "all N" and the "N matching" wordings promise the
   * corpus and deliver a page. On a permissions control that is an admin told they granted every
   * matching colleague when they granted the first eighty. "The N shown" is the only phrasing that is
   * exactly true of what the click does, and it is deliberately awkward enough to be noticed.
   */
  const bulkLabel = serverQuery?.truncated
    ? allChosen
      ? `Clear the ${bulkRows.length} shown`
      : `Select the ${bulkRows.length} shown`
    : allChosen
      ? filtering
        ? `Clear ${bulkRows.length} matching`
        : `Clear all ${bulkRows.length}`
      : filtering
        ? `Select ${bulkRows.length} matching`
        : `Select all ${bulkRows.length}`;

  function applyBulk() {
    if (allChosen) {
      const drop = new Set(bulkRows.map((option) => option.value));
      onChange(values.filter((v) => !drop.has(v)));
      return;
    }
    // Appended rather than rebuilt, so the order the reader picked things in survives.
    const have = new Set(values);
    onChange([...values, ...bulkRows.map((option) => option.value).filter((v) => !have.has(v))]);
  }

  function openPanel(seed = "") {
    // A plain open leaves a caller's server term alone; a seeded one writes on both branches. See
    // the single-select's `openPanel` for the fetch this guard prevents.
    if (seed || !serverDriven) writeQuery(seed);
    setOpen(true);
    // The window's pinned rows are recomputed HERE, at every query change, and — with `serverQuery`
    // — when the answer lands. Nowhere else. On a multi-select that is not a nicety: read live, the
    // pin set changes with every tick, so unticking a pinned row shortened `rendered` under a
    // highlight that kept its old index and the next Enter toggled the neighbour. See `useSelectList`.
    setPins(new Set(values));
    // Row 0 on a seeded open: `rendered` here still describes the unfiltered list, so an index
    // taken from it would point into a list that is about to be replaced. See the single-select.
    setHighlight(seed ? 0 : firstEnabled(rendered));
  }

  /** Same contract as the single-select's `seedFilter`, and for the same reasons. */
  function seedFilter(char: string) {
    if (!open) {
      openPanel(char);
      return;
    }
    writeQuery(query + char);
    setHighlight(0);
    if (!serverDriven) setPins(new Set(values));
    document.getElementById(`${baseId}-search`)?.focus();
  }

  function onQueryChange(next: string) {
    writeQuery(next);
    setHighlight(0);
    // Not on the server branch: the array does not change until the answer lands, so a snapshot
    // taken here would be against the list the reader is about to stop looking at. See the
    // single-select's `onQueryChange` and the effect below.
    if (!serverDriven) setPins(new Set(values));
  }

  /**
   * The live selection, readable from the effect below WITHOUT that effect depending on it.
   *
   * This ref is the whole reason the multi-select cannot copy the single-select's dependency list.
   * There, re-pinning when `value` changes is harmless because choosing closes the panel. Here,
   * `values` changes on every tick — so `[serverDriven, options, values]` would re-take the snapshot
   * from the live selection on every checkbox, which is not an approximation of the defect
   * `useSelectList` describes but literally it: unticking a pinned row drops it from `missing`, every
   * row beneath shifts up one, `highlight` keeps its number, and the next Enter toggles the
   * neighbour. On the design-workshop viewer picker that is a colleague granted access to a workshop
   * nobody pointed at. The snapshot has to be taken from whatever is selected AT THE MOMENT THE
   * ANSWER LANDS and then held still, which is exactly what a ref read inside an `options`-keyed
   * effect does.
   *
   * Written in its own effect rather than during render: effects run in declaration order on one
   * commit, so by the time the pin effect below reads it, it holds this render's selection.
   */
  const selectionRef = useRef(values);
  useEffect(() => {
    selectionRef.current = values;
  }, [values]);

  /**
   * The third moment a pin snapshot is taken — see the single-select's copy of this effect for why
   * `options` identity is the only honest signal that a server answer arrived.
   */
  useEffect(() => {
    if (!serverDriven) return;
    setPins(new Set(selectionRef.current));
  }, [serverDriven, options]);

  const onTabForward = useCallback(() => closeAndMoveOn(true), [closeAndMoveOn]);
  const onTabBackward = useCallback(() => closeAndMoveOn(false), [closeAndMoveOn]);
  const onPanelKeyDown = useEdgeTab(panelRef, onTabForward, onTabBackward);

  /** Same contract as the single-select's `navigate`, `textEntry` included — see the note there. */
  function navigate(event: React.KeyboardEvent, textEntry = false) {
    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        if (open) setHighlight(stepHighlight(rendered, safeHighlight, 1));
        else openPanel();
        return true;
      case "ArrowUp":
        event.preventDefault();
        if (open) setHighlight(stepHighlight(rendered, safeHighlight, -1));
        else openPanel();
        return true;
      case "Home":
        if (!open || textEntry) return false;
        event.preventDefault();
        setHighlight(stepHighlight(rendered, -1, 1));
        return true;
      case "End":
        if (!open || textEntry) return false;
        event.preventDefault();
        setHighlight(stepHighlight(rendered, -1, -1));
        return true;
      case "Enter":
        if (!open) return false;
        event.preventDefault();
        // Ticks and STAYS OPEN — picking one option is rarely the end of a multi-select answer.
        if (safeHighlight >= 0) toggle(safeHighlight);
        return true;
      default:
        return false;
    }
  }

  /** The filter box's own keydown: `navigate`, told that a caret lives here. */
  function onFilterKeyDown(event: React.KeyboardEvent<HTMLInputElement>) {
    navigate(event, true);
  }

  function onTriggerKeyDown(event: React.KeyboardEvent) {
    if (navigate(event)) return;
    if (event.key === " ") {
      event.preventDefault();
      if (open) {
        if (safeHighlight >= 0) toggle(safeHighlight);
      } else openPanel();
      return;
    }
    if (event.key === "Tab" && open) close();
    if (isPrintable(event)) {
      event.preventDefault();
      // With a filter box the letter belongs in it. Note that this branch cannot tick anything
      // either: seeding a query narrows the list and leaves every existing tick exactly as it was.
      if (withSearch) {
        seedFilter(event.key);
        return;
      }
      // Type-ahead only MOVES the highlight here — a multi-select must never tick a box from a
      // keystroke aimed at finding one.
      //
      // AGAINST `rendered`, WHICH IS THE ARRAY `highlight` INDEXES. Searching `options` and writing
      // the answer into the highlight is what shipped, and past the render cap the two are different
      // lists: a letter moved the highlight onto an unrelated row and the Space that followed ticked
      // that row. On the design workshop viewer picker — `searchable={false}` over up to 2000
      // accounts, so the cap always bites and there is no filter box to take the keystroke instead —
      // that is a colleague granted access to a workshop nobody pointed at.
      const at = typeaheadIndex(rendered, typeahead(event.key));
      if (at < 0) return;
      if (!open) {
        setOpen(true);
        setPins(new Set(values));
      }
      setHighlight(at);
    }
  }

  const inputRef = useFilterBoxFocus(triggerRef);
  const groups = useMemo(() => groupRows(rendered), [rendered]);

  /**
   * The names this control can actually put to its selection — which is not always all of them.
   *
   * A pick can only be named out of the array in hand, and with `serverQuery` that array is one
   * answer to one term. `selectionSummarySentence` is where the consequence is handled and is the
   * only thing that reads this: the COUNT it prints comes from `values`, never from this list, so a
   * name the current page cannot supply costs a name and never a number. See that function.
   */
  const chosenLabels = useMemo(
    () => options.filter((option) => chosen.has(option.value)).map((option) => option.label),
    [options, chosen]
  );
  /** The selected set as prose, so a screen reader gets the names and not just "3 selected". */
  const selectionSummary = selectionSummarySentence({
    selected: values.length,
    names: chosenLabels
  });
  /**
   * The list half comes from `listAnnouncement` so the two components cannot word one state two
   * ways; the selection summary is this control's alone and is appended after it. The non-server
   * arms are byte-for-byte what this control has always announced.
   */
  const announcement = `${listAnnouncement({
    total: options.length,
    matched: filtered.length,
    term: withSearch ? query : "",
    server: serverDriven,
    pending: serverQuery?.pending ?? false,
    truncated: serverQuery?.truncated ?? false
  })}. ${selectionSummary}.`;

  /** Both counts are the corpus's — see the single-select's copy. */
  const capAdvice = capHint ?? (withSearch ? CAP_HINT_WITH_SEARCH : CAP_HINT_WITHOUT_SEARCH);
  const capSentence = truncationSentence({
    shown: windowed.length - pinned,
    pinned,
    total: filtered.length,
    capped,
    term: query,
    hint: capAdvice,
    serverTruncated: serverQuery?.truncated ?? false
  });

  /**
   * Reached with the mouse, and by Tab — it is the next control after the filter box, so a keyboard
   * reader finds it by walking rather than by knowing. An earlier draft also bound Ctrl/Cmd+A as a
   * shortcut; it was dropped because inside a text box that chord already means "select the text I
   * just typed", and quietly redefining it to "tick 74 rows" is exactly the kind of surprise this
   * control is supposed to avoid.
   */
  /**
   * `bulk === false` removes the button entirely rather than disabling it, because a disabled
   * control is still a control a reader has to read and dismiss, and on a filter there is nothing
   * for it to become enabled by. The absence state on those screens is expressed by the caller's own
   * "All records" button, which writes `[]`.
   */
  const bulkButton =
    bulk && bulkRows.length > 0 ? (
      <button
        type="button"
        onClick={applyBulk}
        onMouseDown={(event) => event.preventDefault()}
        className="shrink-0 whitespace-nowrap rounded-sm border border-line-200 bg-card px-2 py-1.5 text-xs font-medium text-purple-700 outline-none transition hover:border-purple-300 hover:bg-purple-50 focus-visible:border-purple-600 focus-visible:ring-2 focus-visible:ring-purple-600/20 dark:text-purple-300 dark:hover:bg-purple-950"
      >
        {bulkLabel}
      </button>
    ) : null;

  return (
    <div ref={wrapperRef} className={cn("relative min-w-0", className)}>
      <button
        ref={triggerRef}
        type="button"
        disabled={disabled}
        // Same conditional role, same reason, as the single-select trigger — see the long note
        // there. The listbox this one owns is `aria-multiselectable`, which is where "more than one
        // of these" is stated; the combobox role says only that this control owns that list.
        role={withSearch ? undefined : "combobox"}
        id={triggerId}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={ariaLabel ? `${ariaLabel}. ${selectionSummary}` : undefined}
        /**
         * The enclosing `Field`'s label, plus this button's own text — see the single-select's note.
         * The button's content is "3 selected" and, when no `ariaLabel` was given, an `sr-only` span
         * spelling the names out, so the composed name reads "Crafts 3 selected: Bagru block
         * printing, …" rather than dropping either half.
         */
        aria-labelledby={!ariaLabel && fieldLabelId ? `${fieldLabelId} ${triggerId}` : undefined}
        aria-describedby={describedBy}
        aria-controls={open ? listboxId : undefined}
        aria-activedescendant={open && !withSearch ? activeId : undefined}
        onClick={() => (open ? close() : openPanel())}
        onKeyDown={onTriggerKeyDown}
        data-form-field=""
        data-searchable-select=""
        className={TRIGGER_CLASS}
      >
        {/* Same rung, same reason as the single-select trigger above. */}
        <span className={cn("min-w-0 truncate", !values.length && "text-ink-500")}>
          {values.length ? `${values.length} selected` : placeholder}
        </span>
        {/* Without an ariaLabel the button's accessible name is its content, so the names go here. */}
        {ariaLabel ? null : <span className="sr-only">{selectionSummary}</span>}
        <ChevronDown
          className={cn("h-4 w-4 shrink-0 text-ink-500 transition-transform", open && "rotate-180")}
          aria-hidden
        />
      </button>

      <AnchoredPopover
        open={open}
        onClose={close}
        anchorRef={wrapperRef}
        restoreFocusRef={triggerRef}
        label={ariaLabel ? `${ariaLabel} options` : "Options"}
        offset={4}
        matchAnchorWidth
        maxWidth={PANEL_MAX_WIDTH}
        className={PANEL_CLASS}
      >
        <div ref={panelRef} {...containEvents(onPanelKeyDown)} className="flex min-h-0 flex-col">
          {withSearch ? (
            <SearchRow
              inputRef={inputRef}
              inputId={`${baseId}-search`}
              listboxId={listboxId}
              activeId={activeId}
              query={query}
              onQueryChange={onQueryChange}
              onKeyDown={onFilterKeyDown}
              /* Named for what it reaches, exactly as on the single-select — see the note there. */
              placeholder={serverDriven ? "Type to search" : "Type to filter"}
              label={
                serverDriven
                  ? ariaLabel
                    ? `Search ${ariaLabel}`
                    : "Search options"
                  : ariaLabel
                    ? `Filter ${ariaLabel}`
                    : "Filter options"
              }
              pending={serverQuery?.pending}
              trailing={bulkButton}
            />
          ) : bulkButton ? (
            // No search box on a short list, but "select all" still earns its place — four crafts
            // is four clicks otherwise.
            <div className="flex shrink-0 justify-end border-b border-line-200 p-2">{bulkButton}</div>
          ) : null}
          <ul
            id={listboxId}
            role="listbox"
            aria-multiselectable
            aria-label={ariaLabel ?? "Options"}
            className="min-h-0 max-h-72 shrink overflow-y-auto overscroll-contain py-1"
          >
            {/* Grouped or flat, with the row indices unchanged either way — see the single-select. */}
            {groups.map((bucket) => {
              const rows = bucket.rows.map(({ option, index }) => {
                const checked = chosen.has(option.value);
                return (
                  <li
                    key={option.value}
                    id={`${baseId}-opt-${index}`}
                    role="option"
                    aria-selected={checked}
                    aria-disabled={option.disabled || undefined}
                    title={optionTitle(option)}
                    onMouseEnter={() => {
                      if (!option.disabled) setHighlight(index);
                    }}
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={() => toggle(index)}
                    className={optionClass(option, index === safeHighlight, checked)}
                  >
                    <span
                      aria-hidden
                      className={cn(
                        "grid h-4 w-4 shrink-0 place-items-center rounded border transition",
                        checked ? "border-purple-700 bg-purple-700 text-white" : "border-line-200 bg-card"
                      )}
                    >
                      {checked ? <Check className="h-3 w-3" /> : null}
                    </span>
                    <OptionText option={option} />
                  </li>
                );
              });
              /*
                A KEYED `Fragment` FOR THE UNGROUPED RUN, not a bare array. React does flatten a
                nested array, but a keyed wrapper is what makes the two branches of this map return
                the same SHAPE of child — and the day somebody adds a second ungrouped bucket, an
                unkeyed array is the reconciliation bug that follows.
              */
              if (!bucket.group) return <Fragment key="ungrouped">{rows}</Fragment>;
              return (
                <li key={`group-${bucket.group}`} role="group" aria-label={bucket.group}>
                  <GroupHeading name={bucket.group} />
                  <ul role="none">{rows}</ul>
                </li>
              );
            })}
            {/* Same three-way sentence, in the same place, as the single-select — see the note there. */}
            {windowed.length === 0 ? (
              <li className="px-3.5 py-2 text-sm text-ink-500">
                {emptyListSentence({
                  emptyLabel,
                  term: query,
                  server: serverDriven,
                  pending: serverQuery?.pending ?? false
                })}
              </li>
            ) : null}
          </ul>
          {capSentence ? <CapNotice sentence={capSentence} /> : null}
          {confirmOnSelect && values.length > 0 ? (
            <div className="shrink-0 border-t border-line-200 bg-card p-2">
              <button
                type="button"
                className="field-button w-full py-1.5 text-xs"
                onClick={() => closeAndMoveOn(true)}
                onMouseDown={(event) => event.preventDefault()}
              >
                {confirmLabel} ({values.length})
              </button>
            </div>
          ) : null}
          <p className="sr-only" role="status" aria-live="polite">
            {announcement}
          </p>
        </div>
      </AnchoredPopover>
    </div>
  );
}
