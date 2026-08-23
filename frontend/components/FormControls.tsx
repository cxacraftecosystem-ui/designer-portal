"use client";

import { Children, isValidElement, useId, useMemo, useState, type ReactNode, type SelectHTMLAttributes } from "react";

import { Dropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { FieldLabelProvider } from "@/components/ui/fieldLabel";

/**
 * A labelled slot for an INPUT — a real `<label>`, which is what an input deserves.
 *
 * ── AND WHAT IT PUBLISHES FOR THE CONTROLS A `<label>` CANNOT NAME ───────────────────────────────
 *
 * A `<label>` names an input, a textarea and a select. It does NOT name a `<button>`: HTML-AAM
 * computes a button's accessible name from its own contents and the label association plays no part
 * in that algorithm. Every themed dropdown in this app is a button, so `Field label="Craft"` around
 * one announced "Bamboo, combobox" — the answer, twice, and the question never — and a reader who
 * tabbed back to check what they had entered was told the value with no idea which field it was in.
 * Live at forty-four call sites across twenty files, counted rather than guessed.
 *
 * `FieldBlock` (components/tasks/TaskPrimitives) is the right wrapper for those: a `<div>` with a
 * `role="group"`, which is how a composite widget gets named. But converting forty-four sites is
 * forty-four chances to miss one and a rule to remember on every dropdown added afterwards — the
 * failure mode of a register written down twice. So this component also PUBLISHES the id of the
 * `<span>` it is already rendering, and `SearchableSelect` reads it out of context and composes its
 * own name from it. A `Field`-wrapped dropdown is named correctly with no edit at the call site, and
 * a new one is named correctly without anybody being told. See `ui/fieldLabel.tsx`.
 *
 * That does NOT make `Field` the right wrapper for a widget — `FieldBlock` still is, and a
 * multi-select in particular wants it — it makes the sites that already use it announce their
 * question. The stray-click half of the old trap is separately dead: `AnchoredPopover` portals the
 * panel to `<body>`, so an option row is not a DOM descendant of this label and clicking it cannot
 * be forwarded to the trigger.
 */
export function Field({
  label,
  children,
  required
}: {
  label: string;
  children: React.ReactNode;
  required?: boolean;
}) {
  const labelId = useId();
  return (
    // `min-w-0`: a grid item will not shrink below its content's intrinsic width unless told to, so
    // without this any wide child — a dropdown holding a long workshop name, a long placeholder, an
    // unbroken URL — widens the column and spills over the field beside it. Applied here rather
    // than per control so the whole form inherits it.
    <label className="grid min-w-0 gap-1">
      <span id={labelId} className="field-label">
        {label}
        {required ? " *" : ""}
      </span>
      <FieldLabelProvider value={labelId}>{children}</FieldLabelProvider>
    </label>
  );
}

export function TextInput(props: React.InputHTMLAttributes<HTMLInputElement>) {
  return <input {...props} className={`field-input ${props.className ?? ""}`} />;
}

export function TextArea(props: React.TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea {...props} className={`field-input min-h-24 ${props.className ?? ""}`} />;
}

/**
 * Multiple free-text notes with an "Add note" button and per-note remove. Each note is its own
 * textarea; they are submitted via FormData under a single hidden input (joined by a blank line), so
 * the existing single `notes` column/handlers are unchanged. Splits an existing note back on blank
 * lines for editing.
 */
export function MultiNoteField({
  name = "notes",
  defaultValue,
  label = "Notes"
}: {
  name?: string;
  defaultValue?: string | null;
  label?: string;
}) {
  const [notes, setNotes] = useState<string[]>(() => {
    const split = (defaultValue ?? "")
      .split(/\n\s*\n/)
      .map((s) => s.trim())
      .filter(Boolean);
    return split.length ? split : [""];
  });
  const joined = notes
    .map((s) => s.trim())
    .filter(Boolean)
    .join("\n\n");
  return (
    <div className="grid gap-1">
      <span className="field-label">{label}</span>
      <input type="hidden" name={name} value={joined} />
      <div className="grid gap-2">
        {notes.map((note, index) => (
          <div key={index} className="flex items-start gap-2">
            <textarea
              className="field-input min-h-16 flex-1"
              rows={2}
              value={note}
              placeholder={notes.length > 1 ? `Note ${index + 1}` : "Note"}
              onChange={(event) => setNotes((prev) => prev.map((n, j) => (j === index ? event.target.value : n)))}
            />
            {notes.length > 1 ? (
              <button
                type="button"
                className="field-button-secondary shrink-0"
                onClick={() => setNotes((prev) => prev.filter((_, j) => j !== index))}
              >
                Remove
              </button>
            ) : null}
          </div>
        ))}
      </div>
      <button
        type="button"
        className="field-button-secondary justify-self-start"
        onClick={() => setNotes((prev) => [...prev, ""])}
      >
        + Add note
      </button>
    </div>
  );
}

/**
 * The readable text of an option's children, whatever shape they arrive in.
 *
 * WHY THIS IS NOT `typeof children === "string"`, which is what it replaced. A label written the
 * way every list in this app writes one — `{artisan.name} · {artisan.place}` — compiles to an
 * ARRAY of children, not a string, so that test failed and the label fell through to
 * `String(props.value)`: the record's CUID. Three live dropdowns offered `cmg...` where a name
 * belonged, two of them REQUIRED artisan pickers, and on /questionnaire the artisan picked is what
 * decides `artisanSetKey` — which interview a submission folds into. "Pick the right artisan" was
 * being asked of somebody reading twenty-five random characters, and picking the wrong one merges
 * an interview into the wrong set.
 *
 * Recursion, rather than one more special case for arrays, is the point: an option whose label is
 * wrapped in a <span> or a fragment reads correctly too, instead of being the next shape that
 * silently degrades to an id. Anything with no text of its own — null, a boolean, an <img> —
 * contributes nothing and lets the caller fall back.
 */
function optionText(node: ReactNode): string {
  if (node === null || node === undefined || typeof node === "boolean") return "";
  if (typeof node === "string") return node;
  if (typeof node === "number" || typeof node === "bigint") return String(node);
  if (Array.isArray(node)) return (node as ReactNode[]).map(optionText).join("");
  if (isValidElement(node)) return optionText((node.props as { children?: ReactNode }).children);
  return "";
}

/** Flatten the <option> children of a <Select> into themed-dropdown options. */
function optionsFromChildren(children: ReactNode): DropdownOption[] {
  const options: DropdownOption[] = [];
  Children.forEach(children, (child) => {
    if (!isValidElement(child) || child.type !== "option") return;
    const props = child.props as { value?: string | number; children?: ReactNode; disabled?: boolean };
    // A label split over two source lines keeps the newline and the indent between them; a browser
    // <select> collapses that and so must this, or the dropdown shows the author's formatting.
    const text = optionText(props.children).replace(/\s+/g, " ").trim();
    // The value is still the fallback, for an <option> that genuinely carries no text — but it is
    // now the last resort rather than the usual outcome.
    const label = text || (props.value !== undefined ? String(props.value) : "");
    const value = props.value !== undefined ? String(props.value) : label;
    options.push({ value, label, disabled: props.disabled });
  });
  return options;
}

/** Same call-site API as a browser <select> (select-flavoured value/onChange, <option> children),
 * while the remaining props land on the mirror <input> that actually lives in the form. */
type SelectProps = Omit<React.InputHTMLAttributes<HTMLInputElement>, "value" | "defaultValue" | "onChange" | "type" | "children"> & {
  value?: SelectHTMLAttributes<HTMLSelectElement>["value"];
  defaultValue?: SelectHTMLAttributes<HTMLSelectElement>["value"];
  onChange?: React.ChangeEventHandler<HTMLSelectElement>;
  children?: ReactNode;
  /**
   * ── THE FOUR PROPS BELOW EXIST BECAUSE THIS COMPONENT COULD NOT REACH THEM ──
   * `Select` renders a `Dropdown`, but it forwarded only six things down, so the ~28 call sites that
   * use it — every status, craft, artisan and state picker in the record forms — had no way to say
   * any of this. Each omission had a consequence, and none of them were visible from the call site:
   *
   * - `searchable`: a craft or artisan list assembled from records could not be told to search
   *   regardless of how few rows this deployment holds, which is precisely the case the rule on
   *   `SearchableSelectProps.searchable` exists for. `ProcessForm` bypassed `Select` and called
   *   `Dropdown` directly rather than accept that, and said so in a comment.
   * - `advanceOnSelect`: a `Select` used to FILTER a list therefore always threw focus at the next
   *   field, which §17 of the frontend guide names as a trap in so many words.
   * - `emptyLabel`: "No options" was the only sentence available for "nothing to choose from",
   *   however much better the caller could have put it.
   * - `placeholder`: it was accepted by the type and spread onto the mirror `<input>` — which is
   *   `aria-hidden`, zero-sized and often not rendered at all — so it reached nothing. That is the
   *   same black hole the note below describes for `aria-describedby`.
   */
  searchable?: boolean;
  advanceOnSelect?: boolean;
  emptyLabel?: string;
};

/**
 * Drop-in replacement for the browser <select>: same API (name / value / defaultValue / onChange /
 * disabled and <option> children) so existing forms are unchanged, but rendered as the app's themed
 * dropdown. A visually hidden input mirrors the value so uncontrolled forms still submit via
 * FormData, and mirrors `required` so native form validation works. Any remaining props are spread
 * onto that underlying input instead of being dropped.
 *
 * ── `aria-describedby` IS PULLED OUT OF THAT SPREAD, BECAUSE THE SPREAD IS A BLACK HOLE FOR IT ──
 * The mirror input is `aria-hidden="true"` and `tabIndex={-1}` — it exists to be submitted, not to
 * be read — and it is rendered AT ALL only when a `name` is set. So an `aria-describedby` handed to
 * this component landed either on an element no screen reader visits or, for the pickers that carry
 * no `name`, on no element whatsoever. Both readings are the same defect: the call site says the
 * refusal is bound to the control and nothing announces it. Every `Select` in the app was affected;
 * `ProcessForm` worked around it by calling `Dropdown` itself, and its comment named this line.
 *
 * ONE SPELLING, AND IT IS THE DOM ONE. `Dropdown` calls the attribute `describedBy`, and accepting
 * that name here as well would give one idea two spellings on a component whose whole claim is
 * "same API as the browser <select>" — where the attribute is `aria-describedby`. It is translated
 * on the way down instead.
 *
 * NO `aria-invalid` COMPANION, deliberately: see `SearchableSelect`'s `describedBy` doc. The
 * trigger is a `<button>`, `aria-invalid` is not supported on the `button` role, and setting it
 * would read in the source as a mark while being ignored by every screen reader.
 */
export function Select({
  name,
  value,
  defaultValue,
  onChange,
  disabled,
  required,
  className,
  children,
  placeholder,
  emptyLabel,
  searchable,
  advanceOnSelect,
  "aria-label": ariaLabel,
  "aria-describedby": ariaDescribedBy,
  ...rest
}: SelectProps) {
  const options = useMemo(() => optionsFromChildren(children), [children]);
  const isControlled = value !== undefined;
  const [internal, setInternal] = useState<string>(() => {
    if (defaultValue !== undefined) return String(defaultValue);
    if (value !== undefined) return String(value);
    return options[0]?.value ?? "";
  });
  const current = isControlled ? String(value) : internal;

  function handleChange(next: string) {
    if (!isControlled) setInternal(next);
    onChange?.({ target: { value: next, name } } as unknown as React.ChangeEvent<HTMLSelectElement>);
  }

  return (
    <>
      <Dropdown
        value={current}
        onChange={handleChange}
        options={options}
        // `placeholder` is passed through only when the caller set one: `Dropdown`'s own default is
        // the word "Select", and spelling that out here would turn every unset placeholder into an
        // explicit one and take the default away from the primitive that owns it.
        {...(placeholder === undefined ? {} : { placeholder })}
        emptyLabel={emptyLabel}
        searchable={searchable}
        {...(advanceOnSelect === undefined ? {} : { advanceOnSelect })}
        disabled={disabled}
        className={className}
        ariaLabel={typeof ariaLabel === "string" ? ariaLabel : undefined}
        describedBy={typeof ariaDescribedBy === "string" ? ariaDescribedBy : undefined}
      />
      {name ? (
        // Not type="hidden": hidden inputs are exempt from constraint validation, so a required
        // Select would never block submission. A zero-size text input submits the value AND
        // participates in native validation.
        <input
          {...rest}
          type="text"
          name={name}
          value={current}
          required={required}
          onChange={() => undefined}
          tabIndex={-1}
          aria-hidden="true"
          className="pointer-events-none absolute h-0 w-0 border-0 p-0 opacity-0"
        />
      ) : null}
    </>
  );
}
