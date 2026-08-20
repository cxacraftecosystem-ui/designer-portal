"use client";

/**
 * ONE numbered multi-point input, and the two functions that define what it stores.
 *
 * A BULLETS field is a list, and the newline-joined string it is stored as is a THREE-WAY CONTRACT:
 * this repository's record forms write it, Android's `NumberedListInput` / `MultiNoteInput` read it
 * back into rows, and `report_builder._render_narrative` splits it into the bullets a ministry
 * officer reads. So {@link splitNumbered} and {@link joinNumbered} live here, once, and every web
 * surface that offers a numbered list calls them rather than re-deriving the rule — a second answer
 * about where a bullet boundary is would print a different list on one of the two surfaces and
 * nothing anywhere would report it.
 *
 * WHY THIS FILE EXISTS AT ALL. `DosDontsField` had the rows, the Enter-splits-a-point behaviour, the
 * paste explode and the per-row Remove, and it was reachable only from the artisan record page: the
 * design workshop rendered the SAME facts — `participant.dos`, `participant.donts`,
 * `tool.usedByArtisans`, `traditionalProcess.documentedSteps`, every one of them declared
 * `report_role=BULLETS` — through a bare `<textarea>` whose help text said "One point per line" and
 * whose control afforded nothing of the kind. Extracting the rows rather than writing a second
 * numbered list is the whole point: the two surfaces cannot disagree about a boundary they compute
 * with the same function.
 *
 * TWO ENTRY POINTS, because the two surfaces store their answer differently and neither should have
 * to pretend otherwise:
 *
 * - {@link NumberedPointRows} is the rows themselves, driven by a caller that owns the array. The
 *   record forms reach it through `DosDontsField`, which keeps the zero-size mirror `<textarea>`
 *   that puts the joined value into FormData (see that file for why the mirror may not be an
 *   `<input>`).
 * - {@link NumberedListField} is the string-in/string-out wrapper the design workshop needs, where
 *   there is no FormData at all and the stage entry holds exactly the joined string.
 */

import { Plus, X } from "lucide-react";
import { useEffect, useRef, useState, type ReactNode } from "react";

/** Android parity (MainActivity.kt splitNumbered): stored newline-joined string to editable rows. */
export function splitNumbered(value: string | null | undefined): string[] {
  const rows = (value ?? "")
    .split("\n")
    .map((row) => row.trim())
    .filter(Boolean);
  return rows.length ? rows : [""];
}

/** Android parity (joinNumbered): rows to newline-joined stored string, blank rows dropped. */
export function joinNumbered(items: string[]): string {
  return items
    .map((row) => row.trim())
    .filter(Boolean)
    .join("\n");
}

/**
 * The numbered rows, plus "Add point". The caller owns the array.
 *
 * `onFocusRow` is how a dictation button OUTSIDE this control can commit into the row a designer is
 * actually in. The record page's own `MultiNoteInput` argues the same point from the other end: one
 * microphone for a group of notes cannot know which note a phrase belongs to, and its only
 * defensible guess is wrong exactly when somebody goes back to fill in point two.
 */
export function NumberedPointRows({
  items,
  onItems,
  disabled,
  onFocusRow,
  describedBy,
  invalid
}: {
  items: string[];
  onItems: (next: string[]) => void;
  disabled?: boolean;
  onFocusRow?: (index: number) => void;
  describedBy?: string;
  invalid?: boolean;
}) {
  const rowRefs = useRef<Array<HTMLInputElement | null>>([]);

  function update(next: string[]) {
    onItems(next.length ? next : [""]);
  }

  function focusRow(index: number) {
    // After React commits the new row.
    requestAnimationFrame(() => rowRefs.current[index]?.focus());
  }

  function changeRow(index: number, raw: string) {
    if (raw.includes("\n")) {
      // Pasted multi-line text: commit the first segment here, push the rest to new bullets.
      const segments = raw.split("\n");
      const next = [...items];
      next[index] = segments[0].trim();
      next.splice(index + 1, 0, ...segments.slice(1).map((segment) => segment.trim()));
      update(next);
    } else {
      update(items.map((item, j) => (j === index ? raw : item)));
    }
  }

  function addRowAfter(index: number) {
    const next = [...items];
    next.splice(index + 1, 0, "");
    update(next);
    focusRow(index + 1);
  }

  return (
    <>
      {items.map((item, index) => (
        <div key={index} className="flex items-center gap-2">
          <span aria-hidden className="w-5 shrink-0 text-right text-sm text-ink-muted">
            {index + 1}.
          </span>
          <input
            ref={(el) => {
              rowRefs.current[index] = el;
            }}
            className="field-input min-w-0 flex-1"
            type="text"
            // Named by its ordinal: in a list of eight boxes, eight identical names tell a reader
            // using a screen reader nothing about which point they are on.
            aria-label={`Point ${index + 1}`}
            // The field's hint and its save error belong to the LIST, not to one point, so they are
            // announced on the first row rather than on all eight — forty repetitions of the same
            // sentence is the shape that made a refused save unfixable by voice once already.
            aria-describedby={index === 0 ? describedBy : undefined}
            aria-invalid={index === 0 && invalid ? true : undefined}
            value={item}
            disabled={disabled}
            onFocus={() => onFocusRow?.(index)}
            onChange={(event) => changeRow(index, event.target.value)}
            onKeyDown={(event) => {
              // Enter commits this point and starts the next one (instead of submitting the form).
              if (event.key === "Enter") {
                event.preventDefault();
                addRowAfter(index);
              }
            }}
          />
          {items.length > 1 ? (
            <button
              type="button"
              // Named by its ordinal for the same reason the box is: in a list of eight identical
              // buttons, "Remove point" eight times says nothing about which one they are on.
              aria-label={`Remove point ${index + 1}`}
              className="shrink-0 rounded-md p-2 text-ink-muted transition hover:bg-field-100 hover:text-error-600"
              disabled={disabled}
              onClick={() => update(items.filter((_, j) => j !== index))}
            >
              <X className="h-4 w-4" aria-hidden />
            </button>
          ) : null}
        </div>
      ))}
      <button
        type="button"
        className="field-button-secondary inline-flex items-center gap-1 justify-self-start"
        disabled={disabled}
        onClick={() => addRowAfter(items.length - 1)}
      >
        <Plus className="h-4 w-4" aria-hidden />
        Add point
      </button>
    </>
  );
}

/**
 * The same rows against a single stored string — the shape a design-workshop stage entry holds.
 *
 * THE ROWS ARE THE SOURCE OF TRUTH WHILE THE DESIGNER IS IN THEM, and that is not a preference.
 * `joinNumbered` drops blank rows by design (it is what keeps an accidental empty line out of a
 * printed bullet list), so a control derived straight from the joined string would delete a row the
 * instant "Add point" created it and the button would do visibly nothing. So the array is held here
 * and pushed up joined, and the incoming value is read back in only when it differs from what was
 * last emitted. That is exactly the case that matters: REFERENCE_HYDRATION writing `participant.dos`
 * from a linked artisan record has to appear in the rows, and it does, because the value it wrote is
 * not the one this control emitted.
 */
export function NumberedListField({
  value,
  onChange,
  labelId,
  disabled,
  describedBy,
  invalid,
  renderDictation
}: {
  value: string;
  onChange: (next: string) => void;
  /**
   * The id of the `<span className="field-label">` naming this list. REQUIRED, not optional.
   *
   * Without it the rows announce themselves as "Point 1"…"Point n" and nothing says which list they
   * belong to — and `participant.dos` and `participant.donts` sit next to each other on one stage,
   * so a reader using a screen reader got two identical runs of ordinals. `DosDontsField` had
   * already paid for that defect on the record page and carries `role="group"
   * aria-labelledby={groupId}` for it; the wrapper below is the same group for the caller that owns
   * the string instead of the array. Required so a third caller cannot quietly land unnamed.
   */
  labelId: string;
  disabled?: boolean;
  describedBy?: string;
  invalid?: boolean;
  /**
   * A dictation control, handed a commit that appends into the row the designer last touched.
   *
   * Passed in rather than imported so this file stays a record-form control that knows nothing about
   * design workshops — the dictation button posts to a per-workshop route only the stage page can
   * name.
   */
  renderDictation?: (commit: (text: string) => void) => ReactNode;
}) {
  const [items, setItems] = useState<string[]>(() => splitNumbered(value));
  /**
   * STATE THROUGHOUT, AND NO REFS IN THIS COMPONENT, which is a constraint rather than a style.
   *
   * `renderDictation` is called DURING RENDER and is handed a closure over everything below, so a
   * value kept in a ref would be one React does not know it should re-derive: the button could
   * commit into the row that was focused two renders ago, or compare against a stale "last emitted".
   * `react-hooks/refs` refuses exactly that shape, and it is right to. A re-render per focus change
   * costs nothing on a list of five boxes.
   */
  const [lastEmitted, setLastEmitted] = useState(value);
  /** Which row a dictated phrase lands in. */
  const [focusedRow, setFocusedRow] = useState(0);

  useEffect(() => {
    if (value === lastEmitted) return;
    setLastEmitted(value);
    setItems(splitNumbered(value));
  }, [value, lastEmitted]);

  function commit(next: string[]) {
    setItems(next);
    const joined = joinNumbered(next);
    setLastEmitted(joined);
    onChange(joined);
  }

  /** Append a dictated phrase to the row the designer is in, not to the end of the whole list. */
  function dictateInto(text: string) {
    const index = Math.min(Math.max(focusedRow, 0), items.length - 1);
    const existing = items[index] ?? "";
    const joiner = !existing || /\s$/.test(existing) ? "" : " ";
    commit(items.map((item, j) => (j === index ? `${existing}${joiner}${text}` : item)));
  }

  return (
    /*
     * The group is named by the caller's field label, for the reason `DosDontsField` states: the rows
     * are named by their ordinal only, so a group with no name leaves two adjacent lists announced
     * identically. `NumberedPointRows` itself stays unnamed — `DosDontsField` supplies its own group,
     * and a group inside a group would announce the heading twice.
     */
    <div className="grid content-start gap-1.5" role="group" aria-labelledby={labelId}>
      <NumberedPointRows
        items={items}
        onItems={commit}
        disabled={disabled}
        describedBy={describedBy}
        invalid={invalid}
        onFocusRow={setFocusedRow}
      />
      {renderDictation ? renderDictation(dictateInto) : null}
    </div>
  );
}
