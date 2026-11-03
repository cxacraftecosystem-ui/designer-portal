"use client";

import {
  SearchableMultiSelect,
  SearchableSelect,
  type SelectCreateAction,
  type SelectOption,
  type SelectServerQuery
} from "@/components/ui/SearchableSelect";

export type DropdownOption = SelectOption;

export type DropdownServerQuery = SelectServerQuery;

export type DropdownCreateAction = SelectCreateAction;

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
  emptyLabel?: string;
  disabled?: boolean;
  className?: string;
  ariaLabel?: string;
  describedBy?: string;
  searchable?: boolean;
  capHint?: string;
  advanceOnSelect?: boolean;
  serverQuery?: DropdownServerQuery;
  noneLabel?: string;
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
  describedBy?: string;
  searchable?: boolean;
  capHint?: string;
  confirmOnSelect?: boolean;
  confirmLabel?: string;
  bulk?: boolean;
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
  emptyLabel?: string;
  name?: string;
  ariaLabel?: string;
  describedBy?: string;
  className?: string;
  disabled?: boolean;
  advanceOnSelect?: boolean;
  serverQuery?: DropdownServerQuery;
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
