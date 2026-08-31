"use client";

/**
 * A larger narrative box on a record form: the rich-text editor, an on-device microphone, and a
 * hidden input so the form that contains it does not have to learn anything new.
 *
 * WHY A WRAPPER AND NOT FOUR COPIES OF THE WIRING. The artisan, product, tool and process forms all
 * submit through `FormData` — `textValue(form, "remarks")` and friends read named controls out of
 * the form element at submit time. `RichTextEditor` is a `contenteditable`, which `FormData` cannot
 * see. Every call site would otherwise need its own piece of state, its own hidden input, its own
 * encode call and its own label plumbing, and the fourth copy would be the one that forgot the
 * encode and stringified a document into a searched column. One component, four one-line call sites,
 * and — the reason this file is in `components/` rather than inside a form — one thing for the Field
 * Repository web app to copy when the same change lands there.
 *
 * IT DOES NOT USE `Field`. `Field` renders a `<label>`, and this control is not one control: it is a
 * toolbar of eight buttons, a find bar with two text inputs, a file picker and an editing surface.
 * A `<label>` wrapping several controls has no defined behaviour, and in practice a click on the
 * Bold button would also move focus to the editor and lose the selection being bolded. So the label
 * is a `<span>` tied on with `aria-labelledby`, which is what `FieldInput` does on the stage form
 * for the same reason.
 *
 * THE MICROPHONE IS INSIDE THE EDITOR, not beside it, and that is deliberate: a dictated phrase has
 * to be inserted into the document model AT THE CARET, with the surrounding run's marks and as one
 * undoable step. A button outside could only append a string to the end, which is how the stage
 * form's LONG_TEXT dictation works and precisely what would flatten a formatted field. Passing no
 * `workshopId` is what selects the on-device-only microphone — see the prop's own note in
 * `RichTextEditor.tsx`.
 */

import { useId, useState } from "react";

import { RichTextEditor } from "@/components/designworkshop/RichTextEditor";
import {
  decodeStoredRichText,
  encodeStoredRichText,
  type PlainBlockJoin
} from "@/components/richtext/storedRichText";
import type { RichBlockKind, StoredRichDoc } from "@/lib/richText";

export function RichTextField({
  name,
  label,
  defaultValue,
  helper,
  disabled,
  maxLength,
  listKind,
  /** See {@link PlainBlockJoin} — "paragraph" for any column a multi-note control also reads. */
  join = "line",
  placeholder,
  /**
   * Grid placement, and on these forms it is almost always `md:col-span-2`.
   *
   * The record forms lay their fields out two to a row, which is right for a name and a date and
   * wrong for this: the toolbar carries eight groups of controls and wraps to four rows inside a
   * half-width column, so the chrome ends up taller than the box it belongs to. Spanning both
   * columns is not decoration — it is what keeps the formatting controls on one line.
   */
  className,
  explainWhenUnavailable,
  onDirty,
  onValueChange,
  labelledBy
}: {
  /** The FormData key the containing form already reads. Unchanged from the `<TextArea>` it replaces. */
  name: string;
  label: string;
  /** The stored column value, exactly as the API returned it: prose, a JSON document, or null. */
  defaultValue?: string | null;
  helper?: string;
  disabled?: boolean;
  /**
   * Let this editor's dictation button draw its own "this browser cannot dictate" sentence.
   *
   * Pass `false` on a form that renders `DictationUnavailableNotice` once at the top — ten copies of
   * one paragraph is how the paragraph stops being read. See the prop on `RichTextEditor`.
   */
  explainWhenUnavailable?: boolean;
  maxLength?: number;
  listKind?: RichBlockKind;
  join?: PlainBlockJoin;
  placeholder?: string;
  className?: string;
  /**
   * The form's `markDirty`.
   *
   * Belt and braces: the forms already carry `onInput={markDirty}` on the `<form>` element and a
   * `contenteditable` fires bubbling `input` events, so typing is caught. A DICTATED phrase is not
   * typed — it arrives through `insertText` and rebuilds the surface from the model, which does not
   * fire `input` — and neither does a toolbar press. Without this, a researcher who only ever
   * dictated into a field could navigate away and be told there was nothing to lose.
   */
  onDirty?: () => void;
  /**
   * The encoded column value, reported on every change, for a form that does not submit through
   * `FormData`.
   *
   * ADDITIVE AND OPTIONAL, and the hidden input below stays regardless: every existing call site
   * reads its value with `textValue(form, name)` at submit time and must go on working untouched.
   * `ProcessForm` is the form that needs this — it builds its request body out of React state and
   * never constructs a `FormData` at all, so a hidden input is invisible to it.
   *
   * IT REPORTS THE ENCODED STRING, NOT THE DOCUMENT, which is the same value the hidden input
   * carries and the same one the API stores. Handing back the document would make the caller
   * responsible for `encodeStoredRichText` and its `join` argument, which is exactly the knowledge
   * this component exists to hold in one place.
   *
   * The caller must NOT feed the reported string back in as `defaultValue` on the next render —
   * read the note on `initialValue` below for the caret it would throw to position zero.
   */
  onValueChange?: (value: string) => void;
  /**
   * The id of a heading the CALLER already draws for this box, when there is one.
   *
   * Passed, this component draws no label of its own and the editor is named by that heading
   * instead. It exists for `/questionnaire`, where the thing that names an answer box is the
   * question — `"7. How many hours does one piece take?"` — printed above a block that also holds a
   * recorder and an upload readout, all three of which belong under that one heading. Drawing this
   * component's own label as well would print the question twice; passing the question as `label`
   * and dropping the heading would leave the recorder unnamed and move a two-thousand-character
   * prompt into the microphone's accessible name, which a screen reader then reads out in full
   * before saying what the button does.
   *
   * `label` STAYS REQUIRED AND STAYS SHORT WHEN THIS IS PASSED, because it is still the name the
   * dictation button reads out — "Dictate Answer in English (India)". The two are different jobs:
   * one names the box in the page's structure, the other names the act in a sentence.
   */
  labelledBy?: string;
}) {
  const reactId = useId();
  const ownLabelId = `rtf-${reactId}-label`;
  const labelId = labelledBy ?? ownLabelId;
  const helpId = `rtf-${reactId}-help`;

  /**
   * The document the editor starts with, parsed exactly ONCE and then never handed back.
   *
   * This is not laziness, it is the caret. `RichTextEditor` re-seeds itself whenever the `value` it
   * is given describes a different document from the one it last emitted (`storedSignature`). If
   * this component fed its own encoded string back in as `value`, every keystroke would round-trip
   * document → plain text → document, and any spelling difference in that round trip — an empty
   * paragraph, which `fromPlainText` drops — would read as an external change and throw the caret to
   * position zero mid-sentence. The editor owns the live document; this component owns the string
   * that gets submitted; neither reads the other's copy.
   */
  const [initialValue] = useState<unknown>(() => decodeStoredRichText(defaultValue));
  const [submitValue, setSubmitValue] = useState<string>(() => (defaultValue ?? "").trim());

  return (
    <div className={`grid min-w-0 gap-1 ${className ?? ""}`}>
      {/* Suppressed when the caller names the box with its own heading — see `labelledBy`. */}
      {labelledBy ? null : (
        <span id={ownLabelId} className="field-label">
          {label}
        </span>
      )}
      {helper ? (
        <p id={helpId} className="text-xs text-ink-muted">
          {helper}
        </p>
      ) : null}
      {/*
        The only thing the containing form sees. `name` is the same key the `<textarea>` used, so
        `textValue(form, name)` at submit time is untouched — which is why adding rich text to these
        forms needed no change to any payload builder except the three that machine-append an EXIF
        remark (see `appendStoredParagraph`).
      */}
      <input type="hidden" name={name} value={submitValue} />
      <RichTextEditor
        value={initialValue}
        onChange={(doc: StoredRichDoc | null) => {
          const encoded = encodeStoredRichText(doc, join);
          setSubmitValue(encoded);
          onValueChange?.(encoded);
          onDirty?.();
        }}
        disabled={disabled}
        ariaLabelledBy={labelId}
        // Also the name the microphone reads out: "Dictate Remarks in English (India)".
        ariaLabel={label}
        // Forwarded so a form that already says it once at the top can stop each editor saying it
        // again — see the prop's own note on `RichTextEditor`.
        explainWhenUnavailable={explainWhenUnavailable}
        maxLength={maxLength}
        listKind={listKind}
        {...(placeholder ? { placeholder } : {})}
      />
    </div>
  );
}
