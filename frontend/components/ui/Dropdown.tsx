"use client";

/**
 * The three dropdown shapes this app calls for, all now one implementation.
 *
 * `Dropdown`, `MultiSelectDropdown` and `ComboBox` grew separately and drifted: only the ComboBox
 * could be typed into, only the multi-select had a Confirm button, and all three rendered their
 * menu with `position: absolute` inside the field — which meant every one of them was sheared off
 * by the nearest `overflow-hidden` dialog or filter panel, and none of them could flip upward when
 * opened on the last row of a long form.
 *
 * They are now thin adapters over `components/ui/SearchableSelect`, which floats its panel through
 * `AnchoredPopover` and grows a search box once a list is long enough to need one. The three
 * signatures are unchanged on purpose: forty-odd call sites — several of them owned by other work
 * in flight — pick up searching, "select all" and a panel that clips nothing without being edited
 * at all.
 */

import {
  SearchableMultiSelect,
  SearchableSelect,
  type SelectOption
} from "@/components/ui/SearchableSelect";

export type DropdownOption = SelectOption;

/** Themed single-select dropdown — the app's replacement for the plain browser <select>. */
export function Dropdown({
  value,
  onChange,
  options,
  placeholder = "Select",
  emptyLabel,
  disabled,
  className,
  ariaLabel,
  describedBy,
  searchable,
  capHint,
  advanceOnSelect = true
}: {
  value: string;
  onChange: (value: string) => void;
  options: DropdownOption[];
  placeholder?: string;
  /**
   * What the panel says when there is nothing to choose from at all — as against "No matches",
   * which is what it says when a query excluded everything. The two are different sentences on
   * purpose and `SearchableSelect` has always drawn the distinction.
   *
   * It is forwarded here because it was NOT, and the omission was invisible from either side: the
   * primitive accepted it, `MultiSelectDropdown` passed it, and every single-select in the app was
   * silently stuck with the literal "No options". So a district picker with no state chosen yet, and
   * a designer picker for a workshop that has no eligible designers, both answered with the one
   * sentence that cannot say which — while their multi-select twins could.
   */
  emptyLabel?: string;
  disabled?: boolean;
  className?: string;
  ariaLabel?: string;
  /** Ids of the paragraphs describing this control — a field's hint and its refusal message. See
   *  SearchableSelect for why there is no `invalid` companion. */
  describedBy?: string;
  /**
   * Override the option-count rule in SearchableSelect. **Pass `true` when the options come from
   * fetched records**, and leave it alone for a vocabulary written as a constant — the full rule,
   * and the Android divergence it opens, are on `SearchableSelectProps.searchable`.
   */
  searchable?: boolean;
  /**
   * The last clause of the panel's "Showing the first 80 of N" footer.
   *
   * Only needed where `searchable={false}` overrules a long list: with a filter box the default
   * sentence ("Keep typing to narrow the list") is always true, and without one it is an instruction
   * to use a control that is not on screen. Name the control that DOES reach the rest.
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
}) {
  return (
    <SearchableSelect
      value={value}
      onChange={onChange}
      options={options}
      placeholder={placeholder}
      emptyLabel={emptyLabel}
      disabled={disabled}
      className={className}
      ariaLabel={ariaLabel}
      describedBy={describedBy}
      searchable={searchable}
      capHint={capHint}
      advanceOnSelect={advanceOnSelect}
    />
  );
}

/** Themed multi-select dropdown with checkboxes (e.g. pick several crafts / artisans at once). */
export function MultiSelectDropdown({
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
}: {
  values: string[];
  onChange: (values: string[]) => void;
  options: DropdownOption[];
  placeholder?: string;
  emptyLabel?: string;
  disabled?: boolean;
  className?: string;
  ariaLabel?: string;
  /** Ids of the paragraphs describing this control — a field's hint and its refusal message. See
   *  SearchableSelect for why there is no `invalid` companion. */
  describedBy?: string;
  searchable?: boolean;
  /**
   * The last clause of the panel's "Showing the first 80 of N" footer.
   *
   * Only needed where `searchable={false}` overrules a long list: with a filter box the default
   * sentence ("Keep typing to narrow the list") is always true, and without one it is an instruction
   * to use a control that is not on screen. Name the control that DOES reach the rest.
   */
  capHint?: string;
  /**
   * Show a Confirm button in the panel once at least one option is ticked; confirming closes the
   * panel and moves to the next field.
   *
   * A multi-select cannot advance on click the way a single-select does — picking one option is
   * usually not the end of the answer — so without an explicit "done" the researcher has to know to
   * click away, and the form gives no signal that the answer was registered. Set false where the
   * control filters a list in place rather than answering a form field.
   */
  confirmOnSelect?: boolean;
  confirmLabel?: string;
}) {
  return (
    <SearchableMultiSelect
      values={values}
      onChange={onChange}
      options={options}
      placeholder={placeholder}
      emptyLabel={emptyLabel}
      disabled={disabled}
      className={className}
      ariaLabel={ariaLabel}
      describedBy={describedBy}
      searchable={searchable}
      capHint={capHint}
      confirmOnSelect={confirmOnSelect}
      confirmLabel={confirmLabel}
    />
  );
}

/**
 * Text-input-filtered dropdown: type to narrow the options, pick with mouse or arrows+Enter.
 * When `name` is set a hidden input submits the selected VALUE with the surrounding form.
 *
 * Kept as a distinct export because its callers mean "this list is meant to be searched" regardless
 * of how few records exist today — the workshop picker is one row on this deployment and will be
 * forty on the next — so it forces the search box on rather than letting the count decide.
 */
export function ComboBox({
  options,
  value,
  onChange,
  placeholder = "Select or type to search",
  emptyLabel,
  name,
  ariaLabel,
  describedBy,
  className,
  disabled,
  advanceOnSelect = true
}: {
  options: DropdownOption[];
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  /** See `Dropdown`'s — "there is nothing here" is not "your query matched nothing". */
  emptyLabel?: string;
  name?: string;
  ariaLabel?: string;
  /**
   * Ids of the paragraphs describing this control. Added because this export could not carry one:
   * of the three shapes, the one whose stated purpose is "this list is meant to be searched" was
   * the only one with no way to attach a hint or a refusal to the control it belongs to — and a
   * ComboBox is what the record and workshop pickers use, i.e. the fields most likely to come back
   * refused. See `SearchableSelect` for why there is no `invalid` companion.
   */
  describedBy?: string;
  className?: string;
  disabled?: boolean;
  advanceOnSelect?: boolean;
}) {
  return (
    <>
      {name ? <input type="hidden" name={name} value={value} /> : null}
      <SearchableSelect
        value={value}
        onChange={onChange}
        options={options}
        placeholder={placeholder}
        emptyLabel={emptyLabel}
        disabled={disabled}
        className={className}
        ariaLabel={ariaLabel}
        describedBy={describedBy}
        searchable
        advanceOnSelect={advanceOnSelect}
      />
    </>
  );
}
