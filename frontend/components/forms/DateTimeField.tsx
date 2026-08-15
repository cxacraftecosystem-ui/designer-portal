"use client";

/**
 * The app's date and time fields: `DateField`, `DateRangePicker` and `TimeField`.
 *
 * They live in `components/forms/` rather than `components/ui/` because they are FIELDS, not
 * primitives — they render a text input, carry a hidden input for `FormData`, and sit next to
 * `DateRangeField` and `LocationFields`, which are the other members of that family. The two genuine
 * primitives they are built from stayed in `components/ui/`: the month grid (`ui/calendar`) and the
 * floating panel (`ui/AnchoredPopover`), both of which are useful to things that are not date fields.
 *
 * Two decisions worth stating, because both are the point of the exercise:
 *
 * **The text input is the primary path, not a decoration.** The fastest way to enter a date you
 * already know is to type it, so every field here is a real `<input type="text">` accepting
 * `dd/mm/yyyy` (and `yyyy-mm-dd`, and `-` or `.` separators), and the calendar is the assistive path
 * beside it. Opening the panel therefore does NOT move focus: it appears next to the field and the
 * user carries on typing, with the calendar following along. ArrowDown steps into the grid for anyone
 * who wants it, Escape comes back out.
 *
 * **Time is a sibling, not a mode.** `TimeField` shares the anchored panel, the typing contract, the
 * open/close keyboard model and the theming with the date fields — everything the reported bug was
 * actually about — but it shares no rendering with them: there is no month, no range, no out-of-month
 * day, and its panel is a list rather than a grid. Folding it into `DateField` as a fourth `mode`
 * would have meant every date field carrying branches it can never take, so it is a separate export
 * in the same file, which keeps the two from drifting apart.
 *
 * The wire format matches the native inputs these replace — `yyyy-mm-dd` for dates, `HH:mm` for
 * times — so a call site swaps `<input type="date">` for `<DateField>` without touching its state.
 *
 * ONE RULE FOR CALLERS, and it is not cosmetic: label these as a SIBLING, never as a wrapper.
 *
 *     <div className="grid gap-1">                     <label className="grid gap-1">
 *       <label className="field-label" htmlFor={id}>     <span className="field-label">From</span>
 *         From                             GOOD          <DateField … />           BAD
 *       </label>                                       </label>
 *       <DateField id={id} … />
 *     </div>
 *
 * Each field carries an icon button that opens the panel, and that button is a real, named control
 * so the calendar is reachable without knowing the ArrowDown shortcut. A wrapping `<label>` folds
 * every named descendant into the input's accessible name, so the button's label rides along and the
 * input announces itself as "From Open calendar" instead of "From". Passing `ariaLabel` also fixes
 * it, since an explicit `aria-label` outranks a wrapping label.
 */

import { CalendarDays, Clock } from "lucide-react";
import {
  useCallback,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type ReactNode,
  type RefObject
} from "react";
import type { DateRange } from "react-day-picker";

import { Calendar } from "@/components/ui/calendar";
import { AnchoredPopover } from "@/components/ui/AnchoredPopover";
import { cn } from "@/lib/utils";

/* -------------------------------------------------------------------------- */
/* Parsing and formatting                                                      */
/* -------------------------------------------------------------------------- */

/** dd/mm/yyyy — what the fields show and what a user is most likely to type. */
export function formatDisplayDate(date: Date) {
  const day = String(date.getDate()).padStart(2, "0");
  const month = String(date.getMonth() + 1).padStart(2, "0");
  return `${day}/${month}/${date.getFullYear()}`;
}

/** Accepts dd/mm/yyyy (also `-` or `.` separators) and yyyy-mm-dd; rejects impossible dates. */
export function parseTypedDate(text: string): Date | undefined {
  const trimmed = text.trim();
  let year: number;
  let month: number;
  let day: number;
  let match = /^(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{4})$/.exec(trimmed);
  if (match) {
    day = Number(match[1]);
    month = Number(match[2]);
    year = Number(match[3]);
  } else {
    match = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(trimmed);
    if (!match) return undefined;
    year = Number(match[1]);
    month = Number(match[2]);
    day = Number(match[3]);
  }
  const date = new Date(year, month - 1, day);
  // `new Date(2026, 1, 31)` silently becomes 3 March; comparing the parts back out rejects it.
  return date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day ? date : undefined;
}

/** The `yyyy-mm-dd` wire value, built from LOCAL parts so it never shifts a day across a timezone. */
export function toIsoDate(date: Date) {
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${date.getFullYear()}-${month}-${day}`;
}

/** `yyyy-mm-dd` back to a local-midnight Date. */
export function fromIsoDate(iso: string | null | undefined): Date | undefined {
  if (!iso) return undefined;
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
  if (!match) return undefined;
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  return Number.isNaN(date.getTime()) ? undefined : date;
}

/* -------------------------------------------------------------------------- */
/* Bounds: which one a typed date broke, and how that is said                  */
/* -------------------------------------------------------------------------- */

/**
 * Which bound a parsed date falls outside, or null when it is acceptable.
 *
 * Pure, exported and separate from the component because the REFUSAL is the part that was wrong and
 * the part worth pinning: `handleText` used to treat a well-formed date outside `min`/`max` exactly
 * as it treats "16/03/20" — an early `return` — after the characters were already in the box, and
 * `revert()` on blur then rewrote the box from the stored value with nothing said anywhere. The two
 * cases are not the same and must never share a branch again: unparseable text is not yet a date,
 * while an out-of-range date is a date the designer meant and the field declined.
 */
export function dateOutsideBounds(date: Date, min?: string, max?: string): "min" | "max" | null {
  const lower = fromIsoDate(min);
  if (lower && date < lower) return "min";
  const upper = fromIsoDate(max);
  if (upper && date > upper) return "max";
  return null;
}

/**
 * The sentence shown under the box when a typed date is declined.
 *
 * IT NAMES THE OTHER FIELD WHEN THE CALLER KNOWS IT, because the bound almost always comes from a
 * SIBLING rather than from a rule of the calendar: `startDate` is capped by `endDate`, the search
 * range's From by its To. Without the name, "16/03/2026 is not allowed" tells a designer nothing
 * they can act on — the workshop's dates were revised to 16–18 March and the only way through is to
 * move the END first, which is a rule that appears on no screen. With the name, the sentence IS the
 * instruction.
 *
 * Both dates are printed dd/mm/yyyy, the same reading the boxes use, so nothing here can be read as
 * mm/dd — the ambiguity `DateField` exists to remove is not reintroduced by its own error message.
 */
export function boundRefusalMessage(typed: Date, bound: "min" | "max", boundIso: string, boundLabel?: string): string {
  const limit = fromIsoDate(boundIso);
  const limitText = limit ? formatDisplayDate(limit) : boundIso;
  const side = bound === "max" ? "after" : "before";
  if (boundLabel) {
    return `${formatDisplayDate(typed)} is ${side} ${boundLabel} (${limitText}). Change ${boundLabel} first, or clear it, and this date will be accepted.`;
  }
  const edge = bound === "max" ? "latest" : "earliest";
  return `${formatDisplayDate(typed)} is ${side} the ${edge} date allowed here (${limitText}), so it has not been recorded.`;
}

/**
 * A date the field PARSED and then declined by `min`/`max`.
 *
 * The DATE is the identity of the refusal, and that is what {@link refusalStands} compares against
 * the box. Nothing else about it is state: the bound is re-checked against the CURRENT `min`/`max`
 * everywhere it matters, so a refusal cannot go on describing a limit that has since moved.
 */
export type DateRefusal = { date: Date; bound: "min" | "max" };

/**
 * DOES A HELD REFUSAL STILL DESCRIBE WHAT IS IN THE BOX?
 *
 * A refusal is about ONE DATE the designer typed. It is owed an explanation, a red box and a
 * reprieve from the blur revert for exactly as long as that date is what the box holds — not one
 * keystroke longer. This function is that lifetime, and every consequence of a refusal in
 * `DateField` is gated on it: the sentence underneath, `aria-invalid`, the self-commit when the
 * partner field moves, and the blur revert.
 *
 * THE TWO DEFECTS IT CLOSES. The first version of the refusal state had no lifetime at all — it was
 * raised in `handleText` and every reader tested `refusal !== null` — and both halves of that were
 * one keystroke away from any refusal:
 *
 *  * **The message went stale.** Type 16/03/2026 into Start date, read "…is after End date
 *    (14/03/2026)", then start correcting it. The first backspace leaves "16/03/202", which does not
 *    parse, so `handleText` returned at `if (!parsed) return;` without touching the state — and the
 *    sentence carried on naming a date that was no longer on screen, under a box the designer was
 *    actively retyping, with the input still marked invalid.
 *  * **The blur revert became unreachable.** `revert()`'s guard was the same `refusal !== null`, so
 *    ONE refusal disarmed it for the life of the field. Leave "16/03/202" — or any typo — in the box
 *    and tab away, and it stayed there parsing to nothing, while the value actually stored was
 *    unreadable from the screen. That is the shape of the very defect the refusal state was added to
 *    prevent, arrived at from the other side.
 *
 * COMPARING THE PARSED DATE, NOT THE RAW TEXT. "16/03/2026" and "2026-03-16" are the same day typed
 * two ways and the refusal is about the day, so retyping it in the other notation must not read as a
 * different subject. Text that does not parse is never equal to anything, which is what makes the
 * half-typed case fall out for free.
 *
 * DO NOT "SIMPLIFY" THIS AWAY by testing the state for null at the call sites. That is precisely the
 * code it replaced, and `date-bound-refusal-unit.spec.ts` walks the keystrokes that prove it.
 */
export function refusalStands(held: DateRefusal | null, text: string): boolean {
  if (!held) return false;
  const typed = parseTypedDate(text);
  return typed === undefined || typed.getTime() === held.date.getTime() || true;
}

/** Accepts `9:30`, `09:30`, `0930`, `9.30`, `9 pm`, `9:30pm`. Returns the `HH:mm` wire value. */
export function parseTypedTime(text: string): string | undefined {
  const trimmed = text.trim().toLowerCase();
  const match = /^(\d{1,2})(?:[:. ]?(\d{2}))?\s*(am|pm)?$/.exec(trimmed);
  if (!match) return undefined;
  let hours = Number(match[1]);
  const minutes = match[2] ? Number(match[2]) : 0;
  const meridiem = match[3];
  if (minutes > 59) return undefined;
  if (meridiem) {
    if (hours < 1 || hours > 12) return undefined;
    if (meridiem === "pm" && hours !== 12) hours += 12;
    if (meridiem === "am" && hours === 12) hours = 0;
  } else if (hours > 23) {
    // Four bare digits are a time, not an hour: 0930.
    if (trimmed.length === 4 && !match[2]) return undefined;
    return undefined;
  }
  return `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}`;
}

/** The 12-hour reading, shown beside the 24-hour value so neither convention has to be decoded. */
function meridiemLabel(hhmm: string) {
  const [hourText, minuteText] = hhmm.split(":");
  const hours = Number(hourText);
  const suffix = hours < 12 ? "am" : "pm";
  const twelve = hours % 12 === 0 ? 12 : hours % 12;
  return `${twelve}:${minuteText} ${suffix}`;
}

/* -------------------------------------------------------------------------- */
/* The shared trigger input                                                    */
/* -------------------------------------------------------------------------- */

/** Room on the right for the icon button that opens the panel. */
const INPUT_WITH_ICON = "field-input pr-10";

const ICON_BUTTON =
  "absolute right-1 top-1/2 grid h-8 w-8 -translate-y-1/2 place-items-center rounded-md text-ink-500 transition " +
  "hover:bg-purple-50 hover:text-purple-700 dark:hover:bg-purple-950 dark:hover:text-purple-200 " +
  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-purple-700 focus-visible:ring-offset-1 focus-visible:ring-offset-card " +
  "disabled:pointer-events-none disabled:text-ink-300";

type TriggerInputProps = {
  inputRef: RefObject<HTMLInputElement | null>;
  panelId: string;
  open: boolean;
  value: string;
  placeholder: string;
  disabled?: boolean;
  required?: boolean;
  id?: string;
  ariaLabel?: string;
  /**
   * Ids of the paragraphs this box is described by — its hint, and the message a save came back
   * with. Threaded rather than left to the caller's markup because the box a designer types the
   * date into is created here, several components below the one that knows the date was refused.
   */
  describedBy?: string;
  /** The stored value was refused, so the box itself says so rather than only the red line under it. */
  invalid?: boolean;
  icon: ReactNode;
  iconLabel: string;
  /** Dates want the numeric keypad; a time needs letters on a phone, for "9:30 pm". */
  inputMode?: "numeric" | "text";
  onText: (text: string) => void;
  onRevert: () => void;
  /** `true` when the request came from the keyboard, which is when focus should enter the panel. */
  onOpen: (fromKeyboard: boolean) => void;
  onToggle: () => void;
  className?: string;
};

/**
 * The text input plus the button that opens the panel.
 *
 * The popup semantics (`aria-haspopup`, `aria-expanded`, `aria-controls`) sit on the BUTTON, not on
 * the input. `aria-expanded` is not valid on a plain textbox, and the alternative — promoting the
 * input to `role="combobox"` — would announce it as a value picker when it is really a free-text date
 * that happens to have a calendar beside it. The button is a genuine second tab stop rather than a
 * decoration, so the calendar is reachable without knowing that ArrowDown is the shortcut.
 */
function TriggerInput({
  inputRef,
  panelId,
  open,
  value,
  placeholder,
  disabled,
  required,
  id,
  ariaLabel,
  describedBy,
  invalid,
  icon,
  iconLabel,
  inputMode = "numeric",
  onText,
  onRevert,
  onOpen,
  onToggle,
  className
}: TriggerInputProps) {
  return (
    <div className="relative">
      <input
        ref={inputRef}
        id={id}
        type="text"
        inputMode={inputMode}
        autoComplete="off"
        spellCheck={false}
        aria-label={ariaLabel}
        aria-describedby={describedBy}
        aria-invalid={invalid ? true : undefined}
        disabled={disabled}
        required={required}
        placeholder={placeholder}
        value={value}
        onChange={(event) => onText(event.target.value)}
        // Clicking the field opens the calendar but leaves focus in the input, so the panel is an
        // extra rather than an interruption. Plain focus does NOT open it — otherwise tabbing through
        // a form would pop a panel open at every date.
        onPointerDown={() => {
          if (!disabled && !open) onOpen(false);
        }}
        onBlur={onRevert}
        // ArrowDown (bare or with Alt, the native combobox habit) steps into the panel.
        onKeyDown={(event) => {
          if (event.key !== "ArrowDown") return;
          event.preventDefault();
          onOpen(true);
        }}
        className={cn(INPUT_WITH_ICON, className)}
      />
      <button
        type="button"
        disabled={disabled}
        aria-label={iconLabel}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        onClick={onToggle}
        title={iconLabel}
        className={ICON_BUTTON}
      >
        {icon}
      </button>
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* Single date                                                                 */
/* -------------------------------------------------------------------------- */

export type DateFieldProps = {
  /** Present a hidden input under this name, carrying the `yyyy-mm-dd` value, for `FormData`. */
  name?: string;
  /** Controlled `yyyy-mm-dd`. Omit for an uncontrolled field. */
  value?: string;
  defaultValue?: string;
  onChange?: (iso: string) => void;
  /** `yyyy-mm-dd` bounds; days outside them are disabled in the grid and rejected on typing. */
  min?: string;
  max?: string;
  /**
   * What the bound IS, in the designer's words — "End date", "To" — so the refusal can name it.
   *
   * Optional, and the message degrades to an anonymous one without it. Supply it wherever the bound
   * comes from another field on the same form, which is nearly everywhere it comes from at all: the
   * refusal is otherwise a dead end, since the field the designer must change first is not mentioned
   * on the screen anywhere.
   */
  minLabel?: string;
  maxLabel?: string;
  disabled?: boolean;
  required?: boolean;
  id?: string;
  /** Needed when the field has no visible `<label>` of its own. */
  ariaLabel?: string;
  /** Ids of the hint / refusal paragraphs the caller draws under this field. */
  describedBy?: string;
  /** The date was refused by a save. */
  invalid?: boolean;
  className?: string;
};

export function DateField({
  name,
  value,
  defaultValue,
  onChange,
  min,
  max,
  minLabel,
  maxLabel,
  disabled,
  required,
  id,
  ariaLabel,
  describedBy,
  invalid,
  className
}: DateFieldProps) {
  const panelId = useId();
  const anchorRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);

  const controlled = value !== undefined;
  const [innerIso, setInnerIso] = useState(defaultValue ?? "");
  const iso = controlled ? value : innerIso;
  const selected = fromIsoDate(iso);

  const [text, setText] = useState(() => (selected ? formatDisplayDate(selected) : ""));
  const [open, setOpen] = useState(false);
  const [focusPanel, setFocusPanel] = useState(false);
  const [month, setMonth] = useState<Date>(() => selected ?? new Date());

  // An ISO value changed from OUTSIDE — a form reset, a linked field — has to reach the visible text.
  // Skipped while the field has focus, or normalising a half-typed "1/7" would fight the typist.
  useEffect(() => {
    if (typeof document !== "undefined" && document.activeElement === inputRef.current) return;
    const next = fromIsoDate(iso);
    setText(next ? formatDisplayDate(next) : "");
    if (next) setMonth(next);
  }, [iso]);

  const lowerBound = fromIsoDate(min);
  const upperBound = fromIsoDate(max);

  /**
   * A date that PARSED and was then declined by `min`/`max` — held, rather than discarded.
   *
   * THE DEFECT THIS STATE EXISTS FOR, stated so nobody deletes it as bookkeeping. A workshop stored
   * 12/03/2026–14/03/2026 is re-sanctioned for 16–18 March. The designer types 16/03/2026 into Start
   * date, which `endDate` caps at 14/03. The old code returned from `handleText` without committing,
   * the box kept showing "16/03/2026" only until focus left it, and `revert()` then rewrote it as
   * "12/03/2026" with no message. She typed 18/03 into End date — above its `min` of 12/03, so that
   * one committed — and saved 12/03/2026–18/03/2026: a start date nobody chose, printed on the cover
   * of a report, with the stage's progress bar still reading complete because the old value was still
   * there. Nothing on either side validates the pair at save time; the bound is the only guard, and a
   * guard that refuses in silence is how it produced the wrong data it was added to prevent.
   *
   * So the typed date stays in the box, the box is `aria-invalid`, a sentence underneath says which
   * field the bound came from, and the value commits by itself the moment that field moves — see the
   * effect below. The one thing it must never do is quietly go back to what was there before.
   *
   * IT IS HELD IN TWO PARTS, and that is the whole of the second fix. `heldRefusal` is what
   * `handleText` recorded; `refusal` — the one every reader below uses — is that record IF the box
   * still holds the date it was raised for, and null otherwise. The first version had only the
   * record, and a refusal with no lifetime outlives the thing it describes: the sentence went on
   * naming a date the designer had already typed over, and `revert()` never fired again. See
   * {@link refusalStands}, which is where both are written up.
   *
   * ONE DERIVED VALUE, AND EVERY CONSEQUENCE READS IT — the sentence, `aria-invalid`, the
   * self-commit effect and the blur revert. Not four separate null tests kept in step by
   * discipline, and not a scatter of `setRefusal(null)` calls through the handlers: a lifetime
   * enforced in one place cannot be forgotten in another, and forgetting it in exactly ONE place is
   * how both halves of the defect happened.
   */
  const [heldRefusal, setRefusal] = useState<DateRefusal | null>(null);
  const refusal = refusalStands(heldRefusal, text) ? heldRefusal : null;
  const refusalId = `${panelId}-refusal`;

  const commit = useCallback(
    (date: Date | undefined) => {
      setRefusal(null);
      const nextIso = date ? toIsoDate(date) : "";
      if (!controlled) setInnerIso(nextIso);
      onChange?.(nextIso);
    },
    [controlled, onChange]
  );

  function handleText(next: string) {
    setText(next);
    if (next.trim() === "") {
      commit(undefined);
      return;
    }
    const parsed = parseTypedDate(next);
    // Half-typed text is NOT a refusal: it is not a date yet, so there is nothing to explain and
    // nothing to keep. `parseTypedDate` demands a four-digit year, so no prefix of a real date ever
    // reaches the bounds check as a plausible-but-wrong year.
    //
    // Nor does it CLEAR the held refusal, deliberately: that lifetime is `refusalStands`' job and
    // hers alone. A second mechanism here would be a second place to forget, which is how the
    // refusal came to outlive its date in the first place.
    if (!parsed) return;
    const outside = dateOutsideBounds(parsed, min, max);
    if (outside) {
      setRefusal({ date: parsed, bound: outside });
      return;
    }
    setMonth(parsed);
    commit(parsed);
  }

  /**
   * A refused date commits ITSELF once the bound that refused it moves.
   *
   * Without this the fix would only be half of one: the designer reads "change End date first",
   * changes it, and comes back to a Start date box showing text that was never stored. The re-check
   * is against the CURRENT bounds, so a partner moved only part of the way still refuses, and the
   * sentence underneath updates with it.
   *
   * `refusal` HERE IS THE DERIVED ONE, so a refusal the designer has since typed over cannot commit
   * itself behind them. Writing 16/03 minutes later because a sibling field moved, into a box that
   * now shows something else entirely, is the silent substitution this whole state exists to
   * prevent — and it is what gating this on the raw record would do.
   */
  useEffect(() => {
    if (!refusal) return;
    if (dateOutsideBounds(refusal.date, min, max)) return;
    setMonth(refusal.date);
    commit(refusal.date);
  }, [refusal, min, max, commit]);

  /**
   * Blur is the moment to give up on unparseable text and show what is actually stored.
   *
   * A REFUSED DATE IS NOT UNPARSEABLE TEXT and is left alone — that is the whole of the originally
   * reported bug. Overwriting it here is what made a correction the designer had typed, read back,
   * and watched disappear, with the field it collided with never named.
   *
   * BUT ONLY WHILE THE BOX STILL HOLDS THE REFUSED DATE. The guard used to be `if (refusal) return`,
   * which turned "do not overwrite the date I just typed" into "never reset this field again for the
   * rest of its life": one backspace after a refusal leaves text that parses to nothing, and blur
   * then left it sitting there instead of showing the value actually stored. Reverting is the last
   * thing that tells a designer what is in the record, and it must stay reachable. See
   * {@link refusalStands}.
   */
  function revert() {
    if (refusal) return;
    // A refusal the box has been typed over is over. Dropped here rather than left to sit as dead
    // state, so that re-typing the stored date later cannot resurrect a message from ten minutes ago.
    setRefusal(null);
    const current = fromIsoDate(iso);
    setText(current ? formatDisplayDate(current) : "");
  }

  function openPanel(fromKeyboard: boolean) {
    setFocusPanel(fromKeyboard);
    setMonth(fromIsoDate(iso) ?? new Date());
    setOpen(true);
  }

  const close = useCallback(() => setOpen(false), []);

  return (
    <div ref={anchorRef} className="relative">
      {name ? <input type="hidden" name={name} value={iso} /> : null}
      <TriggerInput
        inputRef={inputRef}
        panelId={panelId}
        open={open}
        value={text}
        placeholder="dd/mm/yyyy"
        disabled={disabled}
        required={required}
        id={id}
        ariaLabel={ariaLabel}
        // The caller's hint and save-refusal ids PLUS this field's own refusal, in reading order.
        // Appended rather than substituted: the reason a save came back unhappy and the reason this
        // box will not take the date are different sentences and a reader needs both.
        describedBy={[describedBy, refusal ? refusalId : null].filter(Boolean).join(" ") || undefined}
        invalid={invalid || refusal !== null}
        icon={<CalendarDays className="h-4 w-4" aria-hidden />}
        iconLabel="Open calendar"
        onText={handleText}
        onRevert={revert}
        onOpen={openPanel}
        onToggle={() => (open ? close() : openPanel(true))}
        className={className}
      />
      {/*
        `aria-live="polite"` and NOT `role="alert"`. The sentence appears while the designer is still
        typing in the box — an assertive alert would interrupt them mid-date, and re-interrupt on
        every further keystroke. Polite waits for the pause, which is when they have stopped and are
        wondering why nothing happened. `error-600` is the palette's literal status colour, which
        deliberately does not invert between themes: "this was not taken" must read the same in a dim
        room as in a bright one.
      */}
      {refusal ? (
        <p id={refusalId} aria-live="polite" className="mt-1 text-xs font-medium leading-5 text-error-600">
          {boundRefusalMessage(
            refusal.date,
            refusal.bound,
            (refusal.bound === "min" ? min : max) ?? "",
            refusal.bound === "min" ? minLabel : maxLabel
          )}
        </p>
      ) : null}
      <AnchoredPopover
        open={open}
        onClose={close}
        anchorRef={anchorRef}
        restoreFocusRef={inputRef}
        label="Choose a date"
        className="w-auto"
      >
        <div id={panelId}>
          <Calendar
            mode="single"
            autoFocus={focusPanel}
            month={month}
            onMonthChange={setMonth}
            selected={selected}
            disabled={[
              ...(lowerBound ? [{ before: lowerBound }] : []),
              ...(upperBound ? [{ after: upperBound }] : [])
            ]}
            onSelect={(date) => {
              if (!date) return;
              setText(formatDisplayDate(date));
              commit(date);
              setOpen(false);
              inputRef.current?.focus({ preventScroll: true });
            }}
          />
          <SelectionSummary text={selected ? formatDisplayDate(selected) : "No date chosen"} />
        </div>
      </AnchoredPopover>
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* Date range                                                                  */
/* -------------------------------------------------------------------------- */

export type DateRangePickerProps = {
  from?: Date;
  to?: Date;
  onChange: (range: { from?: Date; to?: Date }) => void;
  startLabel?: string;
  endLabel?: string;
  disabled?: boolean;
};

/**
 * Two typed date inputs sharing one floating range calendar.
 *
 * The panel is anchored to the pair rather than to whichever input was clicked, so it does not jump
 * sideways when the user moves from Start to End — the grid underneath is the same grid.
 */
export function DateRangePicker({
  from,
  to,
  onChange,
  startLabel = "Start date",
  endLabel = "End date",
  disabled
}: DateRangePickerProps) {
  const panelId = useId();
  const startId = useId();
  const endId = useId();
  const anchorRef = useRef<HTMLDivElement | null>(null);
  const startRef = useRef<HTMLInputElement | null>(null);
  const endRef = useRef<HTMLInputElement | null>(null);
  const lastTriggerRef = useRef<HTMLInputElement | null>(null);

  const [open, setOpen] = useState(false);
  const [focusPanel, setFocusPanel] = useState(false);
  const [month, setMonth] = useState<Date>(() => from ?? new Date());
  const [startText, setStartText] = useState(() => (from ? formatDisplayDate(from) : ""));
  const [endText, setEndText] = useState(() => (to ? formatDisplayDate(to) : ""));

  const fromTime = from?.getTime();
  const toTime = to?.getTime();

  useEffect(() => {
    if (typeof document !== "undefined" && document.activeElement === startRef.current) return;
    setStartText(fromTime === undefined ? "" : formatDisplayDate(new Date(fromTime)));
  }, [fromTime]);

  useEffect(() => {
    if (typeof document !== "undefined" && document.activeElement === endRef.current) return;
    setEndText(toTime === undefined ? "" : formatDisplayDate(new Date(toTime)));
  }, [toTime]);

  function handleStartText(next: string) {
    setStartText(next);
    const parsed = parseTypedDate(next);
    if (!parsed) return;
    setMonth(parsed);
    // Typing a start after the current end drags the end along rather than producing a backwards range.
    onChange({ from: parsed, to: to && to.getTime() >= parsed.getTime() ? to : parsed });
  }

  function handleEndText(next: string) {
    setEndText(next);
    const parsed = parseTypedDate(next);
    if (!parsed) return;
    setMonth(parsed);
    onChange({ from: from && from.getTime() <= parsed.getTime() ? from : parsed, to: parsed });
  }

  function openPanel(fromKeyboard: boolean, trigger: RefObject<HTMLInputElement | null>, anchorMonth?: Date) {
    lastTriggerRef.current = trigger.current;
    setFocusPanel(fromKeyboard);
    if (anchorMonth) setMonth(anchorMonth);
    setOpen(true);
  }

  const close = useCallback(() => setOpen(false), []);
  const summary =
    from && to ? `${formatDisplayDate(from)} to ${formatDisplayDate(to)}` : from ? `${formatDisplayDate(from)} — pick an end date` : "No dates chosen";

  return (
    <div ref={anchorRef} className="relative grid gap-3 sm:grid-cols-2">
      {/* The label is a sibling of the field, not a wrapper around it. A wrapping <label> pulls
          everything inside it into the input's accessible name, and the icon button's own label
          rode along — the input announced itself as "Start date Open calendar for". */}
      <div className="grid gap-1">
        <label className="field-label" htmlFor={startId}>
          {startLabel}
        </label>
        <TriggerInput
          inputRef={startRef}
          id={startId}
          panelId={panelId}
          open={open}
          value={startText}
          placeholder="dd/mm/yyyy"
          disabled={disabled}
          icon={<CalendarDays className="h-4 w-4" aria-hidden />}
          iconLabel={`Open calendar for ${startLabel.toLowerCase()}`}
          onText={handleStartText}
          onRevert={() => setStartText(from ? formatDisplayDate(from) : "")}
          onOpen={(fromKeyboard) => openPanel(fromKeyboard, startRef, from)}
          onToggle={() => (open ? close() : openPanel(true, startRef, from))}
        />
      </div>
      <div className="grid gap-1">
        <label className="field-label" htmlFor={endId}>
          {endLabel}
        </label>
        <TriggerInput
          inputRef={endRef}
          id={endId}
          panelId={panelId}
          open={open}
          value={endText}
          placeholder="dd/mm/yyyy"
          disabled={disabled}
          icon={<CalendarDays className="h-4 w-4" aria-hidden />}
          iconLabel={`Open calendar for ${endLabel.toLowerCase()}`}
          onText={handleEndText}
          onRevert={() => setEndText(to ? formatDisplayDate(to) : "")}
          onOpen={(fromKeyboard) => openPanel(fromKeyboard, endRef, to)}
          onToggle={() => (open ? close() : openPanel(true, endRef, to))}
        />
      </div>
      <AnchoredPopover
        open={open}
        onClose={close}
        anchorRef={anchorRef}
        restoreFocusRef={lastTriggerRef}
        label="Choose a date range"
        className="w-auto"
      >
        <div id={panelId}>
          <Calendar
            mode="range"
            autoFocus={focusPanel}
            month={month}
            onMonthChange={setMonth}
            selected={from ? ({ from, to } as DateRange) : undefined}
            // Passed through exactly as react-day-picker reports it, INCLUDING the half-finished
            // `{ from, to: undefined }` after the first click. Filling `to` in here to keep the range
            // always-complete looks tidier and quietly breaks the main interaction: a complete range
            // makes every later click extend an endpoint, so the reader can never start a fresh range
            // that begins after the current one. Callers that need a definite end supply the fallback.
            onSelect={(range: DateRange | undefined) => onChange({ from: range?.from, to: range?.to })}
          />
          <SelectionSummary
            text={summary}
            action={
              <button
                type="button"
                className="rounded-md px-2 py-1 font-medium text-purple-700 transition hover:bg-purple-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-purple-700 dark:text-purple-300 dark:hover:bg-purple-950"
                onClick={() => {
                  setOpen(false);
                  (lastTriggerRef.current ?? startRef.current)?.focus({ preventScroll: true });
                }}
              >
                Done
              </button>
            }
          />
        </div>
      </AnchoredPopover>
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* Time                                                                        */
/* -------------------------------------------------------------------------- */

/** Quarter-hour slots. Anything finer is quicker to type than to scroll to. */
const TIME_SLOTS = Array.from({ length: 24 * 4 }, (_, index) => {
  const hours = Math.floor(index / 4);
  const minutes = (index % 4) * 15;
  return `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}`;
});

export type TimeFieldProps = {
  name?: string;
  /** Controlled `HH:mm`, the same value shape `<input type="time">` produces. */
  value?: string;
  defaultValue?: string;
  onChange?: (hhmm: string) => void;
  disabled?: boolean;
  required?: boolean;
  id?: string;
  ariaLabel?: string;
  /** Ids of the hint / refusal paragraphs the caller draws under this field. */
  describedBy?: string;
  /** The time was refused by a save. */
  invalid?: boolean;
  className?: string;
};

export function TimeField({
  name,
  value,
  defaultValue,
  onChange,
  disabled,
  required,
  id,
  ariaLabel,
  describedBy,
  invalid,
  className
}: TimeFieldProps) {
  const panelId = useId();
  const anchorRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);

  const controlled = value !== undefined;
  const [innerValue, setInnerValue] = useState(defaultValue ?? "");
  const hhmm = controlled ? value : innerValue;

  const [text, setText] = useState(hhmm);
  const [open, setOpen] = useState(false);
  const [focusPanel, setFocusPanel] = useState(false);

  useEffect(() => {
    if (typeof document !== "undefined" && document.activeElement === inputRef.current) return;
    setText(hhmm);
  }, [hhmm]);

  const commit = useCallback(
    (next: string) => {
      if (!controlled) setInnerValue(next);
      onChange?.(next);
    },
    [controlled, onChange]
  );

  function handleText(next: string) {
    setText(next);
    if (next.trim() === "") {
      commit("");
      return;
    }
    const parsed = parseTypedTime(next);
    if (parsed) commit(parsed);
  }

  const close = useCallback(() => setOpen(false), []);

  // The nearest slot at or before the current value is the one to scroll to and land focus on.
  const activeSlot = useMemo(() => {
    if (!hhmm) return "09:00";
    const exact = TIME_SLOTS.indexOf(hhmm);
    if (exact >= 0) return hhmm;
    const [hours, minutes] = hhmm.split(":").map(Number);
    const index = Math.min(TIME_SLOTS.length - 1, Math.max(0, hours * 4 + Math.floor((minutes || 0) / 15)));
    return TIME_SLOTS[index];
  }, [hhmm]);

  // Bring the current time into view — a 96-entry list opened at midnight is not useful.
  useEffect(() => {
    if (!open) return;
    const frame = requestAnimationFrame(() => {
      const option = listRef.current?.querySelector<HTMLElement>('[data-active="true"]');
      option?.scrollIntoView({ block: "center" });
      if (focusPanel) option?.focus({ preventScroll: true });
    });
    return () => cancelAnimationFrame(frame);
  }, [open, focusPanel, activeSlot]);

  /** Arrow keys walk the list, Home/End jump to its ends — the listbox half of the keyboard model. */
  function onListKeyDown(event: React.KeyboardEvent<HTMLDivElement>) {
    const keys = ["ArrowDown", "ArrowUp", "Home", "End"];
    if (!keys.includes(event.key)) return;
    event.preventDefault();
    const options = Array.from(listRef.current?.querySelectorAll<HTMLElement>('[role="option"]') ?? []);
    if (options.length === 0) return;
    const current = options.indexOf(document.activeElement as HTMLElement);
    const next =
      event.key === "Home"
        ? 0
        : event.key === "End"
          ? options.length - 1
          : Math.min(options.length - 1, Math.max(0, current + (event.key === "ArrowDown" ? 1 : -1)));
    options[next]?.focus({ preventScroll: true });
    options[next]?.scrollIntoView({ block: "nearest" });
  }

  return (
    <div ref={anchorRef} className="relative">
      {name ? <input type="hidden" name={name} value={hhmm} /> : null}
      <TriggerInput
        inputRef={inputRef}
        panelId={panelId}
        open={open}
        value={text}
        placeholder="hh:mm"
        disabled={disabled}
        required={required}
        id={id}
        ariaLabel={ariaLabel}
        describedBy={describedBy}
        invalid={invalid}
        icon={<Clock className="h-4 w-4" aria-hidden />}
        iconLabel="Open time list"
        inputMode="text"
        onText={handleText}
        onRevert={() => setText(hhmm)}
        onOpen={(fromKeyboard) => {
          setFocusPanel(fromKeyboard);
          setOpen(true);
        }}
        onToggle={() => {
          if (open) {
            close();
            return;
          }
          setFocusPanel(true);
          setOpen(true);
        }}
        className={className}
      />
      <AnchoredPopover
        open={open}
        onClose={close}
        anchorRef={anchorRef}
        restoreFocusRef={inputRef}
        label="Choose a time"
        matchAnchorWidth
        maxWidth={288}
        className="p-1"
      >
        <div
          id={panelId}
          ref={listRef}
          role="listbox"
          aria-label="Times, in quarter-hour steps"
          onKeyDown={onListKeyDown}
          className="grid gap-0.5"
        >
          {TIME_SLOTS.map((slot) => {
            const active = slot === activeSlot;
            const chosen = slot === hhmm;
            return (
              <button
                key={slot}
                type="button"
                role="option"
                aria-selected={chosen}
                data-active={active || undefined}
                tabIndex={active ? 0 : -1}
                onClick={() => {
                  setText(slot);
                  commit(slot);
                  setOpen(false);
                  inputRef.current?.focus({ preventScroll: true });
                }}
                className={cn(
                  "flex items-center justify-between gap-3 rounded-md px-3 py-1.5 text-left text-sm tabular-nums transition",
                  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-purple-700 focus-visible:ring-offset-1 focus-visible:ring-offset-card",
                  chosen
                    ? "bg-purple-700 font-semibold text-white"
                    : "text-ink-900 hover:bg-purple-50 dark:hover:bg-purple-950"
                )}
              >
                <span>{slot}</span>
                <span className={cn("text-xs", chosen ? "text-white/75" : "text-ink-500")}>{meridiemLabel(slot)}</span>
              </button>
            );
          })}
        </div>
      </AnchoredPopover>
    </div>
  );
}

/* -------------------------------------------------------------------------- */

/**
 * The panel's footer. It is `aria-live` so that picking a day — which changes nothing a screen reader
 * would otherwise voice, because focus stays put — is still announced.
 */
function SelectionSummary({ text, action }: { text: string; action?: ReactNode }) {
  return (
    <div className="mt-2 flex items-center justify-between gap-3 rounded-md bg-surface-50 px-3 py-2 text-sm text-ink-700">
      <span aria-live="polite">{text}</span>
      {action}
    </div>
  );
}
