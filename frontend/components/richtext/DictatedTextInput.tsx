"use client";

/**
 * A ONE-LINE text box with a microphone under it — the single-line sibling of `DictatedTextArea`.
 *
 * ── WHY A SECOND COMPONENT AND NOT `rows={1}` ───────────────────────────────────────────────────
 *
 * `DictatedTextArea` is a `<textarea className="field-input min-h-24">` and nothing else. Forcing it
 * into a one-line slot is not a prop away: `min-h-24` is 6rem of box, the control sits in a four-
 * column `md:grid-cols-2 lg:grid-cols-4` row beside Place, Workshop and Status, and a textarea in
 * that row is three times the height of its neighbours with a drag handle inviting somebody to make
 * an interview title four lines long. `DesignerProfileForm.tsx` reached the same conclusion and says
 * so at its `DictatedField` ("A `rows` of 1 is still a textarea, and an address is not one"); that
 * component is private to that file and demands a `maxLength` every one of its columns has, so it is
 * the precedent this follows rather than the thing this imports.
 *
 * ── WHAT IT DOES NOT DO: IT IS NOT A `Field` ────────────────────────────────────────────────────
 *
 * `Field` is a `<label>` (SKILL.md §12.3) and a `<label>` forwards a stray click to the first
 * labelable control inside it. With the microphone under the box, clicking "Dictate" would ALSO
 * focus the input — which on a phone throws the on-screen keyboard up over the very interim readout
 * the researcher is watching. So this writes its own `<label htmlFor>`, exactly as `DictatedTextArea`
 * and `DesignerProfileForm.DictatedField` do, for exactly that reason.
 *
 * ── CONTROLLED BY THE CALLER, WHICH IS THE ONE PLACE IT DIVERGES FROM ITS SIBLING ───────────────
 *
 * `DictatedTextArea` owns its value internally and is seeded from `defaultValue`. That is right for
 * a form that REMOUNTS between records (`key={editing?.id ?? "new"}`) and wrong for one that clears
 * itself in place: `formElement.reset()` rewrites the DOM node and tells React nothing, so a
 * self-controlled box re-paints the previous record's text on the next render. `/questionnaire` is
 * exactly that shape — it calls `formElement.reset()` twice (page.tsx, the queued branch and the
 * saved branch) with a `key` that does not change between two consecutive new interviews — so the
 * caller owns the value here and clears it in the same block as `setAnswers({})`. One mode, not two,
 * because a control that is sometimes controlled is a control whose reset behaviour has to be
 * re-derived at every call site.
 *
 * A `name` is still rendered when one is given, so `new FormData(form)` reads this box like any
 * other input and no submit handler has to learn about it.
 *
 * ── NO TRANSPORT, EVER ──────────────────────────────────────────────────────────────────────────
 *
 * Everything the microphone hears is consumed by the browser's own recogniser and discarded; there
 * is no `MediaRecorder`, no `fetch`, no workshop whose `dictationConsent` could govern an artisan's
 * recorded voice. That is `OnDeviceDictationButton`'s guarantee and this file adds nothing that
 * could weaken it — see `e2e/record-form-dictation-unit.spec.ts` §1, which asserts the absence of
 * transport primitives by reading source.
 */

import { useId } from "react";

import { OnDeviceDictationButton } from "@/components/dictation/OnDeviceDictationButton";
import { TextInput } from "@/components/FormControls";
import { TitleCasedInput } from "@/components/forms/TitleCasedInput";
import { appendDictatedPhrase, clampToColumn, columnFullSentence } from "@/components/richtext/dictatedValue";
import { RequiredMark } from "@/components/ui/RequiredMark";

export function DictatedTextInput({
  name,
  label,
  value,
  onChange,
  placeholder,
  helper,
  required,
  disabled,
  maxLength,
  className,
  explainWhenUnavailable,
  id,
  titleCased,
  "aria-invalid": ariaInvalid,
  "aria-describedby": ariaDescribedBy
}: {
  /** Submitted through `FormData` under this name. Omit for a box the caller reads out of its own state. */
  name?: string;
  label: string;
  value: string;
  onChange: (next: string) => void;
  placeholder?: string;
  /** Drawn under the label, not inside the box — a placeholder vanishes the moment dictation fills it. */
  helper?: string;
  required?: boolean;
  disabled?: boolean;
  maxLength?: number;
  className?: string;
  /**
   * Forwarded to `OnDeviceDictationButton`. Leave it alone for a lone control; pass `false` where
   * several sit within a few centimetres of each other, and make sure ONE control nearby still
   * carries the sentence — the rule and its reason are on the button's own prop.
   */
  explainWhenUnavailable?: boolean;
  /**
   * An id for the box, where something outside has to reach it.
   *
   * `ProcessForm` refuses a save by moving focus to the offending control —
   * `document.getElementById("process-name")?.focus()` — and its per-step boxes key theirs off the
   * step. A generated id is unreachable from there, so the focus ladder would land on nothing and a
   * refusal would be a red paragraph nobody is taken to. Omit it everywhere else: `useId` is the
   * safer default because two copies of one form on a page must not share an id.
   */
  id?: string;
  /**
   * Mount the shared `TitleCasedInput` rather than a plain box, for the columns the API title-cases
   * on write (`records.TITLE_CASE_FIELDS` — name, place, craftName, artisanName, toolkitName…).
   *
   * NOT A COPY OF THE HINT, THE COMPONENT ITSELF. `TitleCasedInput` says "Will be saved as …"
   * whenever the server's normalisation would change what was typed, and the sweep that put a
   * microphone under these boxes must not be the thing that removes that sentence: a researcher who
   * types "kutch-bhuj" and is told nothing watches the value change silently after saving, which is
   * the entire defect that component was written for. It also MERGES an incoming `aria-describedby`
   * with its own hint rather than replacing it, so a refusal and the hint are both announced.
   */
  titleCased?: boolean;
  /**
   * A refusal, marked and pointed at. Passed through rather than composed here: which box is invalid
   * and which paragraph explains it are the calling form's decisions, and the ids belong to it.
   */
  "aria-invalid"?: boolean;
  "aria-describedby"?: string;
}) {
  const reactId = useId();
  const boxId = id ?? `${reactId}-box`;
  const helpId = `${reactId}-help`;
  const fullId = `${reactId}-full`;

  /**
   * THE CEILING IS ENFORCED HERE, NOT ONLY BY THE `maxLength` ATTRIBUTE.
   *
   * A DOM `maxLength` bounds typing and pasting and has no opinion at all about a value written into
   * React state, which is precisely what a committed phrase is. So dictation is the one path that
   * can carry a value past its column's limit, and an over-long field 422s the whole request — the
   * researcher loses every other answer because they spoke one sentence too many, with the refusal
   * naming a box that looks fine on screen. Clamped, and then SAID: a box that silently stops
   * accepting words is indistinguishable from a microphone that stopped working.
   */
  const full = maxLength !== undefined && value.length >= maxLength;
  function update(next: string) {
    onChange(clampToColumn(next, maxLength));
  }

  // The caller's description, this box's helper and the ceiling sentence — ALL of them, because a
  // box can be described by a refusal AND a hint AND its cap at the same moment, and picking one
  // would silence the other two for exactly the reader who cannot see them.
  const describedBy =
    [ariaDescribedBy, helper ? helpId : null, full ? fullId : null].filter(Boolean).join(" ") || undefined;

  // The same control every box WITHOUT a microphone mounts, chosen by the column rather than rebuilt
  // here — see `titleCased` above for why the hint may not be lost on the way through.
  //
  // Annotated rather than inferred: both components take exactly `InputHTMLAttributes<HTMLInputElement>`
  // today, and writing that down is what makes a later change to either one fail HERE — at the one
  // place that treats them as interchangeable — instead of at whichever call site happened to pass
  // the prop that stopped being accepted.
  const Control: (props: React.InputHTMLAttributes<HTMLInputElement>) => React.ReactElement = titleCased
    ? TitleCasedInput
    : TextInput;

  return (
    // `min-w-0` for the reason `Field` carries it: a grid item will not shrink below its content's
    // intrinsic width unless told to, so a long dictated sentence would widen the column and spill
    // over the field beside it.
    <div className={`grid min-w-0 gap-1 ${className ?? ""}`}>
      <label className="field-label" htmlFor={boxId}>
        {label}
        <RequiredMark when={required} />
      </label>
      {helper ? (
        <p id={helpId} className="text-xs leading-5 text-ink-muted">
          {helper}
        </p>
      ) : null}
      <Control
        id={boxId}
        name={name}
        type="text"
        value={value}
        placeholder={placeholder}
        required={required}
        disabled={disabled}
        maxLength={maxLength}
        aria-invalid={ariaInvalid}
        aria-describedby={describedBy}
        onChange={(event) => update(event.currentTarget.value)}
      />
      {/*
        COMMITTING APPENDS, NEVER REPLACES. The recogniser is stopped and started many times across
        one answer, and a commit that overwrote the box would delete everything already in it the
        moment somebody paused for breath. The join is a single space unless the box already ends in
        whitespace, or a sentence dictated in three goes comes out as "…the warpis sized…". SHARED
        with `DictatedTextArea` and with `ProcessForm`'s per-note microphone rather than copied into
        each of them — `./dictatedValue` is the one place that rule lives, because by the sweep of
        2026-08-28 it had been written out three times and was about to be written out eight.
      */}
      <OnDeviceDictationButton
        fieldLabel={label}
        disabled={disabled}
        explainWhenUnavailable={explainWhenUnavailable}
        onCommit={(phrase) => update(appendDictatedPhrase(value, phrase))}
      />
      {/*
        The ceiling, on screen, only once it is reached. Bound with `aria-describedby` rather than
        made a live region: nothing has gone wrong and nothing was lost — the value in the box is
        exactly what will be saved — so this is a description of the box, read when it is focused.
        `ink-500`, not `error-600`, for the same reason.
      */}
      {full && maxLength !== undefined ? (
        <p id={fullId} className="text-xs leading-5 text-ink-500">
          {columnFullSentence(maxLength)}
        </p>
      ) : null}
    </div>
  );
}
