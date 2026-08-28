"use client";

/**
 * A plain multi-line box with a microphone, and deliberately NOT a rich-text editor.
 *
 * WHY THIS EXISTS ALONGSIDE `RichTextField`. The decision the user made was "only the larger text
 * boxes need the rich text features" — and a handful of boxes on these forms are multi-line without
 * being narrative. An artisan's postal address is the clear case: it is three lines, so a researcher
 * standing in a courtyard genuinely wants to speak it rather than thumb it in, but a bold word or a
 * bulleted list in an address is meaningless, and a formatting toolbar there would be an invitation
 * to store a document in a column that four exports print as a delivery address.
 *
 * So the two capabilities are separable and this is the dictation-only half. It is a real
 * `<textarea>` with a real `name`, so `FormData`, `textValue`, the browser's own spellcheck and
 * every existing form behaviour are exactly as they were; the only addition is the button under it.
 *
 * WHY THIS EXISTS ALONGSIDE `DictatedTextInput`. That is the ONE-LINE sibling, added when the sweep
 * of 2026-08-28 put a microphone under every applicable box on the record pages. A textarea squeezed
 * into a one-line slot draws a resize grip, swallows Enter (`lib/formNav.isAdvanceableInput` opts
 * textareas out of the Enter-walk on purpose) and never gets the browser's autofill; so the shape of
 * the box follows the shape of the answer, and the two components share their rules rather than
 * their markup — `./dictatedValue` holds the joiner and the column ceiling for both.
 */

import { useId, useState } from "react";

import { OnDeviceDictationButton } from "@/components/dictation/OnDeviceDictationButton";
import { appendDictatedPhrase, clampToColumn, columnFullSentence } from "@/components/richtext/dictatedValue";

export function DictatedTextArea({
  name,
  label,
  defaultValue,
  helper,
  disabled,
  required,
  rows,
  maxLength,
  className,
  /**
   * Draw the "this browser cannot dictate" sentence under THIS box, or stay silent.
   *
   * Default TRUE, which is unchanged: this control began life as the only microphone on its screen,
   * and a lone box that quietly loses its button on Firefox is the silent-nothing
   * `OnDeviceDictationButton` was written to prevent. A form that now carries many microphones
   * passes FALSE here and renders `DictationUnavailableNotice` once instead — see that component for
   * why eleven copies of one paragraph is worse than none.
   */
  explainWhenUnavailable = true,
  onDirty
}: {
  name: string;
  label: string;
  defaultValue?: string | null;
  helper?: string;
  disabled?: boolean;
  /**
   * The browser's own required check, and the asterisk beside the label that announces it.
   *
   * ONE PROP DRIVES BOTH, which is the point: the questionnaire's new-question box was a `<Field
   * label required>` around a `<textarea required>` before it was dictated, and had this component
   * simply dropped the attribute the form would have started accepting empty questions silently —
   * the box would look identical and submit anyway. `DictatedTextInput` has carried this since it
   * was written; the two boxes differ in shape, never in the rules they enforce.
   */
  required?: boolean;
  rows?: number;
  /** The column's own ceiling. See `clampToColumn` for why the DOM attribute is not enough. */
  maxLength?: number;
  className?: string;
  explainWhenUnavailable?: boolean;
  /** The form's `markDirty`. A dictated phrase is a React state write, not a typed `input` event. */
  onDirty?: () => void;
}) {
  const reactId = useId();
  const helpId = `dta-${reactId}-help`;
  const fullId = `dta-${reactId}-full`;

  /**
   * CONTROLLED, and it has to be: dictation writes into the box from outside the keyboard, and an
   * uncontrolled textarea would need a ref plus a manual `input` dispatch to keep React and the DOM
   * agreeing about what is in it. The forms read the value through `FormData` at submit time either
   * way, so nothing downstream can tell the difference.
   */
  const [value, setValue] = useState(defaultValue ?? "");
  const full = maxLength !== undefined && value.length >= maxLength;

  /**
   * `onDirty` is fired from the two places that CHANGE the value, never from an effect watching it.
   *
   * An effect would fire once on mount, when the box is seeded from `defaultValue` on an edit form,
   * and every edit form would then pop the unsaved-changes dialog on the way out of a record nobody
   * touched. Researchers learn to click through that dialog, and then it stops protecting anything.
   */
  function update(next: string) {
    setValue(clampToColumn(next, maxLength));
    onDirty?.();
  }

  const describedBy = [helper ? helpId : null, full ? fullId : null].filter(Boolean).join(" ") || undefined;

  return (
    <div className="grid min-w-0 gap-1">
      {/*
        A `<label htmlFor>` and not the `Field` wrapper: `Field` is a `<label>` element, and the
        dictation button below is a second control inside it. Clicking "Dictate" would then also
        focus the textarea, which on a phone throws the on-screen keyboard up over the very readout
        the researcher is trying to watch.
      */}
      <label className="field-label" htmlFor={`${reactId}-box`}>
        {label}
        {required ? " *" : ""}
      </label>
      {helper ? (
        <p id={helpId} className="text-xs text-ink-muted">
          {helper}
        </p>
      ) : null}
      <textarea
        id={`${reactId}-box`}
        name={name}
        rows={rows}
        required={required}
        disabled={disabled}
        maxLength={maxLength}
        aria-describedby={describedBy}
        className={`field-input min-h-24 ${className ?? ""}`}
        value={value}
        onChange={(event) => update(event.target.value)}
      />
      {/*
        COMMITTING APPENDS, NEVER REPLACES. The recogniser is stopped and started many times across a
        long answer, and a commit that overwrote the box would delete everything already in it the
        moment somebody paused for breath. The joiner rule is `appendDictatedPhrase`, shared with the
        single-line box and the process form's per-note microphone so it cannot drift between them.
      */}
      <OnDeviceDictationButton
        fieldLabel={label}
        disabled={disabled}
        explainWhenUnavailable={explainWhenUnavailable}
        onCommit={(phrase) => update(appendDictatedPhrase(value, phrase))}
      />
      {/* THE CEILING, SAID ON SCREEN WHEN IT IS REACHED — see `clampToColumn`. A box that silently
          stops accepting words is indistinguishable from a microphone that stopped working. */}
      {full && maxLength !== undefined ? (
        <p id={fullId} className="text-xs leading-5 text-ink-500">
          {columnFullSentence(maxLength)}
        </p>
      ) : null}
    </div>
  );
}
