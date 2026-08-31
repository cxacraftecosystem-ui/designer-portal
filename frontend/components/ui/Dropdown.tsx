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
  type SelectCreateAction,
  type SelectOption,
  type SelectServerQuery
} from "@/components/ui/SearchableSelect";

export type DropdownOption = SelectOption;

/**
 * Re-exported for the same reason `DropdownOption` is: these three adapters are the address most of
 * the app imports a picker from, and a caller wiring `serverQuery` should not have to know which of
 * the two modules the type happens to live in. `components/ui/SearchableSelect` remains the source
 * of truth, and its comment is where the contract — the debounce, the generation counter, the page
 * size and the diacritic folding it costs — is written down.
 */
export type DropdownServerQuery = SelectServerQuery;

/**
 * Re-exported for the same reason `DropdownOption` and `DropdownServerQuery` are: this module is the
 * address most of the app imports a picker from, and a caller wiring a creatable combo should not
 * have to know which of the two files the type happens to live in.
 * `components/ui/SearchableSelect` is the source of truth and carries the whole argument.
 */
export type DropdownCreateAction = SelectCreateAction;

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
  advanceOnSelect = true,
  serverQuery,
  noneLabel,
  createAction
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
  /**
   * Point the panel's filter box at the server's `search=` instead of at the array it was handed.
   * Absent — the default — is exactly today's behaviour, a box that filters what is already loaded.
   * The whole contract is on `SearchableSelectProps.serverQuery`; the short version is that the
   * caller owns the term, the 300 ms debounce, the generation counter, and a page size of
   * `RENDER_CAP`.
   */
  serverQuery?: DropdownServerQuery;
  /**
   * Draw a first, ungrouped row carrying `value: ""` with this label, and read it back on the
   * trigger when the value is empty — the way a record is UN-FILED. Absent is today's behaviour.
   *
   * Pass the sentence that says what "" means on this field; the four that mean genuinely different
   * things are in `lib/workshopOptions`. Never an "All …" string — a control that filters says
   * everything by absence, not by a row. See `SearchableSelectProps.noneLabel`.
   */
  noneLabel?: string;
  /**
   * Turn this from a picker into a CREATABLE COMBO: the term the reader typed is offered as an
   * answer of its own, under the list. Absent — the default — is exactly today's behaviour.
   *
   * Forwarded here because it was going to be needed through this adapter first: the design
   * workshop's own title is reached through `Dropdown`, not through the primitive. The contract, and
   * the written objection it answers, are on `SelectCreateAction`.
   */
  createAction?: DropdownCreateAction;
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
      serverQuery={serverQuery}
      noneLabel={noneLabel}
      createAction={createAction}
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
  confirmLabel = "Confirm",
  bulk = true,
  serverQuery
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
  /**
   * Draw the "Select all N" / "Clear all N" button. `true` by default, which is what this control
   * has always done.
   *
   * **Every control that FILTERS a screen passes `false`**, and it goes with `confirmOnSelect={false}`
   * and `advanceOnSelect={false}` on the same control. Ticking every row and ticking none must not
   * both mean "everything" — a filter with two spellings for one state cannot tell a default from a
   * deliberate choice, and over a truncated page "all" is not all anyway. Offer the absence state as
   * its own button that sets `[]`, the way `WorkshopScopeSelect`'s "All records" does. The full
   * argument is on `SearchableMultiSelectProps.bulk`.
   */
  bulk?: boolean;
  /**
   * Point the panel's filter box at the server's `search=`. Absent is today's behaviour. See
   * `SearchableSelectProps.serverQuery` for the contract and `SearchableMultiSelectProps.serverQuery`
   * for the one consequence that is the multi's alone — a truncated answer restyles the bulk button
   * so it stops claiming "all".
   */
  serverQuery?: DropdownServerQuery;
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
      bulk={bulk}
      serverQuery={serverQuery}
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
  advanceOnSelect = true,
  serverQuery,
  noneLabel
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
  /**
   * Point the box at the server's `search=` rather than at the array it was handed — the natural
   * home for it, since this export already exists to say "this list is meant to be searched". Absent
   * is today's behaviour. See `SearchableSelectProps.serverQuery`.
   */
  serverQuery?: DropdownServerQuery;
  /**
   * The "" row that un-files a record, and the label the trigger reads back when the value is empty.
   * Absent is today's behaviour; the record pickers that hand-build such a row today should pass this
   * instead. See `SearchableSelectProps.noneLabel`.
   */
  noneLabel?: string;
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
        serverQuery={serverQuery}
        noneLabel={noneLabel}
      />
    </>
  );
}
