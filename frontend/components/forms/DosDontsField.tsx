"use client";

import { useId, useState } from "react";

import { NumberedPointRows, joinNumbered, splitNumbered } from "@/components/forms/NumberedListInput";

/**
 * Web twin of Android's NumberedListInput (MainActivity.kt): a required, numbered multi-point input
 * used for an artisan's Do's (positive prompt) and Don'ts (negative prompt). Each row is one
 * numbered bullet; pressing Enter inside a row splits it into a new bullet, rows can be removed
 * individually, and "Add point" appends an empty one. The rows serialize to the same
 * newline-joined string as Android via a zero-size mirror input under `name`, so the existing
 * FormData handlers (`requiredText(form, "dos"/"donts")`) are unchanged.
 *
 * THE ROWS THEMSELVES ARE NO LONGER HERE, and that is the point of the split rather than tidiness.
 * They — and `splitNumbered`/`joinNumbered`, which decide where a bullet boundary is — moved to
 * `components/forms/NumberedListInput`, because the design workshop renders the very same facts
 * (`participant.dos`, `participant.donts`) through `FieldInput` and was giving them a bare textarea.
 * One control means the artisan record page and the stage form cannot disagree about what one point
 * is; two controls would eventually print two different lists from one stored string.
 *
 * WHAT STAYS HERE is everything about being a RECORD FORM field: the group heading, the helper, and
 * the mirror that carries `required` into native constraint validation.
 */
export function DosDontsField({
  name,
  label,
  helper,
  defaultValue,
  required = true
}: {
  name: string;
  label: string;
  helper?: string;
  defaultValue?: string | null;
  required?: boolean;
}) {
  const [items, setItems] = useState<string[]>(() => splitNumbered(defaultValue));
  const joined = joinNumbered(items);
  const groupId = useId();
  const helperId = `${groupId}-helper`;

  return (
    /*
     * WHY THE GROUP AND THE PER-ROW NAMES EXIST.
     *
     * Every row used to be an `<input>` with no label, no aria-label and no placeholder, so its
     * accessible name was empty: a researcher using a screen reader tabbed through the artisan form
     * and hit two REQUIRED boxes announced as nothing but "edit text, blank", with no way to tell
     * the Do's from the Don'ts or point 2 from point 5. The heading was a bare `<span>` that named
     * nothing, and the ordinal beside each row was decoration. Native validation then refused the
     * save on a mirror `<textarea>` the reader cannot reach at all.
     *
     * The group carries the visible heading; each row carries the ordinal it is drawn with (see
     * `NumberedPointRows`). Nothing here invents wording — "Do's (positive prompt)" and "1." are
     * both already on screen, so what is announced and what is printed are the same two things.
     */
    <div className="relative grid content-start gap-1.5" role="group" aria-labelledby={groupId} aria-describedby={helper ? helperId : undefined}>
      <span id={groupId} className="field-label">
        {label}
        {required ? " *" : ""}
      </span>
      {helper ? (
        <p id={helperId} className="text-xs text-ink-muted">
          {helper}
        </p>
      ) : null}
      <NumberedPointRows items={items} onItems={setItems} />
      {/* Zero-size (not hidden) mirror: submits the newline-joined value under the existing field
          name AND makes `required` participate in native form validation.

          It MUST be a <textarea>, not an <input type="text">. An input's value sanitization
          algorithm strips CR/LF, so `joined` arrived in FormData as "point onepoint two" — every
          multi-point Do's/Don'ts was stored as one run-on string, which `splitNumbered` could then
          never split back into rows. A textarea preserves newlines and still participates in
          constraint validation, so `required` behaves exactly as before. (MultiNoteField gets away
          with an input because its mirror is type="hidden", which is not sanitized.) */}
      <textarea
        name={name}
        value={joined}
        required={required}
        onChange={() => undefined}
        tabIndex={-1}
        aria-hidden="true"
        className="pointer-events-none absolute h-0 w-0 resize-none border-0 p-0 opacity-0"
      />
    </div>
  );
}
