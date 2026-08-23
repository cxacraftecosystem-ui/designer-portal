"use client";

/**
 * `SearchableSelect` / `SearchableMultiSelect` — the app's one list-picking control.
 *
 * Reported by the user: "make the single select and multi-select drop downs searchable... if they
 * press enter then the first value gets chosen, in multi-select everywhere, also give the option
 * select all."
 *
 * Seven decisions carry this file, and none of them are obvious from the code alone.
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
 * **5. There are two index domains and only one of them is legal for the highlight.** `options` is
 * the corpus; `rendered` is what is on screen, and past `RENDER_CAP` they are different lists.
 * `highlight` indexes `rendered`, always. Every place that computes an index says which array it is
 * against, because the two places that did not — type-ahead in both components — moved the highlight
 * onto an unrelated row on the one control where the cap always bites, and that control is a
 * permissions picker whose next keystroke ticks a person.
 *
 * **6. A keystroke that means "move on" must never change an answer.** Tab out of the filter box
 * commits only a highlight the reader MOVED (arrows, Home, End, hover); typing alone leaves the
 * field's existing value exactly as it was. See `onTabForward` for the sequence this cost.
 *
 * **7. In a text box the caret keys belong to the caret.** Home and End move the caret in the filter
 * box and the list everywhere else — see `navigate`'s `textEntry`.
 *
 * Focus is deliberately NOT trapped. Tab walks the panel's own controls and then leaves for the
 * next field in the form; Escape closes and puts focus back on the trigger. A picker that swallows
 * the keyboard is worse than the native `<select>` it replaces.
 *
 * The pure half of all this — the matcher, the two cap sentences, the grouping and the two
 * constants — lives in `ui/selectFilter.ts` so a spec can call it. That file says why.
 */

import { Check, ChevronDown, Search } from "lucide-react";
import { Fragment, useCallback, useEffect, useId, useMemo, useRef, useState } from "react";

import { AnchoredPopover } from "@/components/ui/AnchoredPopover";
import { useFieldLabelId } from "@/components/ui/fieldLabel";
import {
  CAP_HINT_WITHOUT_SEARCH,
  CAP_HINT_WITH_SEARCH,
  RENDER_CAP,
  SEARCH_THRESHOLD,
  capNoticeSentence,
  filterOptions,
  groupRows,
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

/** Selected labels read out in full before the summary switches to a count. */
const SUMMARY_NAMES = 6;

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
  pins: ReadonlySet<string>
) {
  const filtered = useMemo(
    () => (searchable ? filterOptions(options, query) : options),
    [options, query, searchable]
  );
  const { rendered, pinned } = useMemo(() => {
    if (filtered.length <= RENDER_CAP) return { rendered: filtered, pinned: 0 };
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
    // So the set of pinned values is captured at the two moments the window is legitimately
    // recomputed — the panel opening, and the query changing — and held still in between. Ticking
    // and unticking then only ever repaints check marks; it never renumbers a row. The cost is that
    // a row ticked from deeper in the corpus during this session is not dragged forward until the
    // panel is reopened, which is the smaller of the two surprises by a wide margin: the tick is
    // still counted in the trigger's summary and in "N selected".
    const missing = filtered.filter((option) => pins.has(option.value) && !window.includes(option));
    // `pinned` is counted and handed back rather than left implicit, because the footer has to say
    // something true about a list of two different kinds of row. `rendered.length` is 80 first-rows
    // PLUS however many pinned selections came from deeper in the corpus, so a footer reading
    // "Showing the first 81 of 246" described a set whose 81st member was the 100th match — a small
    // lie, but this repo's rule is that a truncated list must state its truncation ACCURATELY, and
    // an off-by-a-pinned-row notice is the kind of thing a reader checks their own counting against.
    return missing.length
      ? { rendered: [...missing, ...window], pinned: missing.length }
      : { rendered: window, pinned: 0 };
  }, [filtered, pins]);
  return { filtered, rendered, pinned, capped: filtered.length - rendered.length };
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
      {trailing}
    </div>
  );
}

/**
 * The "N of M shown" footer. Only drawn when the cap actually bit.
 *
 * The sentence itself is `capNoticeSentence` in `ui/selectFilter` so a spec can read it without a
 * browser; this is the box it sits in. The `hint` is the last clause and it is the caller's, because
 * the panel cannot know how a reader is supposed to reach row 81: with a filter box the answer is
 * "keep typing", and without one it is whatever control the call site put above the picker. Printing
 * "Keep typing to narrow the list" on a panel that has no box is how the viewer picker came to
 * instruct an admin to use a control that is not on screen.
 */
function CapNotice({
  shown,
  pinned,
  total,
  hint
}: {
  shown: number;
  pinned: number;
  total: number;
  hint: string;
}) {
  return (
    <p className="shrink-0 border-t border-line-200 bg-surface-50 px-3.5 py-2 text-xs leading-4 text-ink-500">
      {capNoticeSentence({ shown, pinned, total, hint })}
    </p>
  );
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
  advanceOnSelect = true
}: SearchableSelectProps) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
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

  const withSearch = searchable ?? options.length >= SEARCH_THRESHOLD;
  const { filtered, rendered, pinned, capped } = useSelectList(options, query, withSearch, pins);

  // Derived, not stored — the stored index is only ever a hint. See the file header.
  const safeHighlight =
    highlight >= 0 && highlight < rendered.length && !rendered[highlight].disabled
      ? highlight
      : firstEnabled(rendered);
  const activeId = safeHighlight >= 0 ? `${baseId}-opt-${safeHighlight}` : undefined;
  useScrollHighlightIntoView(open, safeHighlight, baseId);

  const selected = options.find((option) => option.value === value);

  const close = useCallback(() => {
    setOpen(false);
    setQuery("");
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

  function openPanel(seed = "") {
    setQuery(seed);
    setOpen(true);
    // The window's pinned rows are recomputed HERE and at every query change, and nowhere else —
    // see `useSelectList` on why a live selection set renumbers rows under a stationary highlight.
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
    setQuery((current) => current + char);
    setHighlight(0);
    setHighlightTouched(false);
    setPins(value ? new Set([value]) : EMPTY_PINS);
    document.getElementById(`${baseId}-search`)?.focus();
  }

  /** Typing in the box always re-aims Enter at the top match. */
  function onQueryChange(next: string) {
    setQuery(next);
    setHighlight(0);
    setHighlightTouched(false);
    setPins(value ? new Set([value]) : EMPTY_PINS);
  }

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

  const announcement = withSearch && query.trim()
    ? `${filtered.length} of ${options.length} options match ${query.trim()}`
    : `${options.length} options`;

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
        <span className={cn("min-w-0 truncate", !selected && "text-ink-500")} title={selected?.label}>
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
              placeholder="Type to filter"
              label={ariaLabel ? `Filter ${ariaLabel}` : "Filter options"}
            />
          ) : null}
          <ul
            id={listboxId}
            role="listbox"
            aria-label={ariaLabel ?? "Options"}
            className="min-h-0 max-h-72 shrink overflow-y-auto overscroll-contain py-1"
          >
            {rendered.length === 0 ? (
              <li className="px-3.5 py-2 text-sm text-ink-500">{query.trim() ? "No matches" : emptyLabel}</li>
            ) : null}
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
          </ul>
          {capped > 0 ? (
            <CapNotice
              shown={rendered.length - pinned}
              pinned={pinned}
              total={filtered.length}
              hint={capHint ?? (withSearch ? CAP_HINT_WITH_SEARCH : CAP_HINT_WITHOUT_SEARCH)}
            />
          ) : null}
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
  confirmLabel = "Confirm"
}: SearchableMultiSelectProps) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
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

  const withSearch = searchable ?? options.length >= SEARCH_THRESHOLD;
  const chosen = useMemo(() => new Set(values), [values]);
  const { filtered, rendered, pinned, capped } = useSelectList(options, query, withSearch, pins);

  const safeHighlight =
    highlight >= 0 && highlight < rendered.length && !rendered[highlight].disabled
      ? highlight
      : firstEnabled(rendered);
  const activeId = safeHighlight >= 0 ? `${baseId}-opt-${safeHighlight}` : undefined;
  useScrollHighlightIntoView(open, safeHighlight, baseId);

  const close = useCallback(() => {
    setOpen(false);
    setQuery("");
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
   */
  const bulk = useMemo(() => filtered.filter((option) => !option.disabled), [filtered]);
  const allChosen = bulk.length > 0 && bulk.every((option) => chosen.has(option.value));
  const filtering = withSearch && query.trim().length > 0;
  const bulkLabel = allChosen
    ? filtering
      ? `Clear ${bulk.length} matching`
      : `Clear all ${bulk.length}`
    : filtering
      ? `Select ${bulk.length} matching`
      : `Select all ${bulk.length}`;

  function applyBulk() {
    if (allChosen) {
      const drop = new Set(bulk.map((option) => option.value));
      onChange(values.filter((v) => !drop.has(v)));
      return;
    }
    // Appended rather than rebuilt, so the order the reader picked things in survives.
    const have = new Set(values);
    onChange([...values, ...bulk.map((option) => option.value).filter((v) => !have.has(v))]);
  }

  function openPanel(seed = "") {
    setQuery(seed);
    setOpen(true);
    // The window's pinned rows are recomputed HERE and at every query change, and nowhere else. On
    // a multi-select that is not a nicety: read live, the pin set changes with every tick, so
    // unticking a pinned row shortened `rendered` under a highlight that kept its old index and the
    // next Enter toggled the neighbour. See `useSelectList`.
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
    setQuery((current) => current + char);
    setHighlight(0);
    setPins(new Set(values));
    document.getElementById(`${baseId}-search`)?.focus();
  }

  function onQueryChange(next: string) {
    setQuery(next);
    setHighlight(0);
    setPins(new Set(values));
  }

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

  const chosenLabels = useMemo(
    () => options.filter((option) => chosen.has(option.value)).map((option) => option.label),
    [options, chosen]
  );
  /** The selected set as prose, so a screen reader gets the names and not just "3 selected". */
  const selectionSummary = chosenLabels.length
    ? chosenLabels.length <= SUMMARY_NAMES
      ? `${chosenLabels.length} selected: ${chosenLabels.join(", ")}`
      : `${chosenLabels.length} selected, including ${chosenLabels.slice(0, SUMMARY_NAMES).join(", ")}`
    : "Nothing selected";
  const announcement = `${
    filtering ? `${filtered.length} of ${options.length} options match ${query.trim()}` : `${options.length} options`
  }. ${selectionSummary}.`;

  /**
   * Reached with the mouse, and by Tab — it is the next control after the filter box, so a keyboard
   * reader finds it by walking rather than by knowing. An earlier draft also bound Ctrl/Cmd+A as a
   * shortcut; it was dropped because inside a text box that chord already means "select the text I
   * just typed", and quietly redefining it to "tick 74 rows" is exactly the kind of surprise this
   * control is supposed to avoid.
   */
  const bulkButton =
    bulk.length > 0 ? (
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
              placeholder="Type to filter"
              label={ariaLabel ? `Filter ${ariaLabel}` : "Filter options"}
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
            {rendered.length === 0 ? (
              <li className="px-3.5 py-2 text-sm text-ink-500">{query.trim() ? "No matches" : emptyLabel}</li>
            ) : null}
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
          </ul>
          {capped > 0 ? (
            <CapNotice
              shown={rendered.length - pinned}
              pinned={pinned}
              total={filtered.length}
              hint={capHint ?? (withSearch ? CAP_HINT_WITH_SEARCH : CAP_HINT_WITHOUT_SEARCH)}
            />
          ) : null}
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
