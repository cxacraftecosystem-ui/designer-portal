"use client";

import { CalendarField } from "@/components/ui/CalendarField";
import { useEffect, useId, useRef, useState, type ReactNode, type RefObject } from "react";
import { CalendarRange, FilterX, Search, X } from "lucide-react";

import { CappedListNotice } from "@/components/data/CappedListNotice";
import { CUT_NOTICE_LIVE_REGION } from "@/components/data/cappedList";
import { Dropdown, MultiSelectDropdown } from "@/components/ui/Dropdown";
import {
  ACCESS_INSTITUTION_NOTE,
  ACCESS_STATUS_OPTIONS,
  DESIGNER_ROLE_HINT,
  DESIGNER_STANDING_OPTIONS,
  RANGE_OPTIONS,
  ROSTER_LABELS,
  clearRosterFilters,
  dateFieldOptions,
  hasActiveRosterFilters,
  institutionCutNotice,
  institutionOptions,
  roleMatchCutNotice,
  roleOptions,
  type AccessStatusToken,
  type DesignerStandingToken,
  type RosterDateField,
  type RosterFilters,
  type RosterKind
} from "@/components/admin/rosterFilters";
import type { RangeId } from "@/components/search/SearchFilters";

const SEARCH_DEBOUNCE_MS = 300;

export type RosterFilterBarProps = {
  kind: RosterKind;
  filters: RosterFilters;
  onChange: (next: RosterFilters) => void;
  institutions?: readonly string[];
  institutionsTruncated?: boolean;
  institutionsLoading?: boolean;
  institutionsEmptyLabel?: string;
  roleMatchTruncated?: boolean;
  roleMatchLimit?: number;
  onSearchPendingChange?: (pending: boolean) => void;
  className?: string;
};

export function RosterFilterBar({
  kind,
  filters,
  onChange,
  institutions = [],
  institutionsTruncated,
  institutionsLoading,
  institutionsEmptyLabel,
  roleMatchTruncated,
  roleMatchLimit,
  onSearchPendingChange,
  className = ""
}: RosterFilterBarProps) {
  const access = kind === "access";
  const ids = useId();
  const fromId = `${ids}-from`;
  const toId = `${ids}-to`;
  const roleHintId = `${ids}-role-hint`;
  const roleCutId = `${ids}-role-cut`;
  const institutionCutId = `${ids}-institution-cut`;

  const [draft, setDraft] = useState(filters.search);
  const pushed = useRef(filters.search);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const searchRef = useRef<HTMLInputElement | null>(null);

  const latest = useRef({ filters, onChange });
  useEffect(() => {
    latest.current = { filters, onChange };
  });

  useEffect(() => {
    return () => {
      if (timer.current) clearTimeout(timer.current);
    };
  }, []);

  useEffect(() => {
    if (filters.search === pushed.current) return;
    pushed.current = filters.search;
    setDraft(filters.search);
  }, [filters.search]);

  const searchPending = draft.trim() !== filters.search.trim();
  useEffect(() => {
    onSearchPendingChange?.(searchPending);
  }, [onSearchPendingChange, searchPending]);

  function clearTimer() {
    if (timer.current) {
      clearTimeout(timer.current);
      timer.current = null;
    }
  }

  function commitSearch(term: string) {
    clearTimer();
    if (term === pushed.current) return;
    pushed.current = term;
    latest.current.onChange({ ...latest.current.filters, search: term });
  }

  function typeSearch(next: string) {
    setDraft(next);
    clearTimer();
    timer.current = setTimeout(() => {
      timer.current = null;
      commitSearch(next);
    }, SEARCH_DEBOUNCE_MS);
  }

  function set(patch: Partial<RosterFilters>) {
    clearTimer();
    pushed.current = draft;
    onChange({ ...filters, search: draft, ...patch });
  }

  const searchLabel = access ? ROSTER_LABELS.accessSearch : ROSTER_LABELS.designerSearch;
  const rolesLabel = access ? ROSTER_LABELS.accessRoles : ROSTER_LABELS.designerRoles;

  const roleCut = roleMatchCutNotice(access ? undefined : roleMatchTruncated, roleMatchLimit);
  const institutionRows = institutionOptions(institutions);
  const institutionCut = access
    ? ""
    : institutionCutNotice(institutionsTruncated, institutions.length);
  const filtered = hasActiveRosterFilters(kind, filters);

  return (
    <section
      role="search"
      aria-label={access ? "Filter the allow-list" : "Filter the designer roster"}
      className={`mb-4 grid gap-3 ${className}`}
    >
      <div className="grid gap-2 sm:grid-cols-[1fr_18rem]">
        <RosterSearchBox
          inputRef={searchRef}
          value={draft}
          ariaLabel={searchLabel}
          placeholder={access ? "Search by email, name or note" : "Search by email, name or institution"}
          onChange={typeSearch}
          onSubmit={() => commitSearch(draft)}
          onClear={() => {
            setDraft("");
            commitSearch("");
          }}
        />

        {access ? (
          <ControlBlock label={ROSTER_LABELS.accessStatus}>
            <MultiSelectDropdown
              values={filters.status}
              onChange={(next) => set({ status: next as AccessStatusToken[] })}
              options={ACCESS_STATUS_OPTIONS}
              ariaLabel={ROSTER_LABELS.accessStatus}
              placeholder="Everyone ever seen"
              searchable={false}
              bulk={false}
              confirmOnSelect={false}
            />
          </ControlBlock>
        ) : (
          <ControlBlock label={ROSTER_LABELS.designerStanding}>
            <Dropdown
              value={filters.standing}
              onChange={(next) => set({ standing: next as "" | DesignerStandingToken })}
              options={DESIGNER_STANDING_OPTIONS}
              ariaLabel={ROSTER_LABELS.designerStanding}
              searchable={false}
              advanceOnSelect={false}
            />
          </ControlBlock>
        )}
      </div>

      <div className={`grid gap-3 ${access ? "lg:grid-cols-2" : "lg:grid-cols-3"}`}>
        <ControlBlock
          label={rolesLabel}
          hint={
            access ? null : (
              <p id={roleHintId} className="text-[11px] leading-4 text-ink-500">
                {DESIGNER_ROLE_HINT}
              </p>
            )
          }
        >
          <MultiSelectDropdown
            values={filters.roles}
            onChange={(next) => set({ roles: next })}
            options={roleOptions(kind)}
            ariaLabel={rolesLabel}
            placeholder="Every tier"
            searchable={false}
            bulk={false}
            confirmOnSelect={false}
            describedBy={access ? roleCutId : `${roleHintId} ${roleCutId}`}
          />
          <div {...CUT_NOTICE_LIVE_REGION}>
            <CappedListNotice id={roleCutId} cuts={[roleCut]} />
          </div>
        </ControlBlock>

        {access ? null : (
          <ControlBlock label={ROSTER_LABELS.designerInstitutions}>
            <MultiSelectDropdown
              values={filters.institutions}
              onChange={(next) => set({ institutions: next })}
              options={institutionRows}
              ariaLabel={ROSTER_LABELS.designerInstitutions}
              placeholder={institutionsLoading ? "Loading institutions…" : "Every institution"}
              emptyLabel={institutionsEmptyLabel}
              searchable
              bulk={false}
              confirmOnSelect={false}
              describedBy={institutionCutId}
            />
            <div {...CUT_NOTICE_LIVE_REGION}>
              <CappedListNotice id={institutionCutId} cuts={[institutionCut]} />
            </div>
          </ControlBlock>
        )}

        <fieldset className="min-w-0">
          <legend className="field-label inline-flex items-center gap-1.5">
            <CalendarRange className="h-3.5 w-3.5" aria-hidden />
            {ROSTER_LABELS.dateRange}
          </legend>
          <div className="mt-1 grid gap-2 sm:grid-cols-2">
            <ControlBlock label={ROSTER_LABELS.dateField}>
              <Dropdown
                value={filters.dateField}
                onChange={(next) => set({ dateField: next as RosterDateField })}
                options={dateFieldOptions(kind)}
                ariaLabel={ROSTER_LABELS.dateField}
                searchable={false}
                advanceOnSelect={false}
              />
            </ControlBlock>
            <ControlBlock label={ROSTER_LABELS.datePeriod}>
              <Dropdown
                value={filters.range}
                onChange={(next) => set({ range: next as RangeId })}
                options={RANGE_OPTIONS}
                ariaLabel={ROSTER_LABELS.datePeriod}
                searchable={false}
                advanceOnSelect={false}
              />
            </ControlBlock>
          </div>

          {filters.range === "custom" ? (
            <div className="mt-2 grid gap-2 sm:grid-cols-2">
              <CalendarField
                id={fromId}
                label={ROSTER_LABELS.dateFrom}
                value={filters.from}
                onChange={(iso) => set({ from: iso })}
                max={filters.to || undefined}
                maxLabel={ROSTER_LABELS.dateTo}
              />
              <CalendarField
                id={toId}
                label={ROSTER_LABELS.dateTo}
                value={filters.to}
                onChange={(iso) => set({ to: iso })}
                min={filters.from || undefined}
                minLabel={ROSTER_LABELS.dateFrom}
              />
            </div>
          ) : null}
        </fieldset>
      </div>

      {access ? (
        <p className="text-[11px] leading-4 text-ink-500">{ACCESS_INSTITUTION_NOTE}</p>
      ) : null}

      {filtered ? (
        <div>
          <button
            type="button"
            className="field-button-secondary min-h-8 px-3 py-1.5 text-xs"
            onClick={() => {
              clearTimer();
              pushed.current = "";
              setDraft("");
              onChange(clearRosterFilters(kind, filters));
              searchRef.current?.focus();
            }}
          >
            <FilterX className="h-3.5 w-3.5" aria-hidden />
            {ROSTER_LABELS.clearAll}
          </button>
        </div>
      ) : null}
    </section>
  );
}

function ControlBlock({
  label,
  hint,
  children
}: {
  label: string;
  hint?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="grid min-w-0 content-start gap-1">
      <span className="field-label">{label}</span>
      {children}
      {hint}
    </div>
  );
}

function RosterSearchBox({
  value,
  onChange,
  onSubmit,
  onClear,
  ariaLabel,
  placeholder,
  inputRef
}: {
  value: string;
  onChange: (v: string) => void;
  onSubmit: () => void;
  onClear: () => void;
  ariaLabel: string;
  placeholder: string;
  inputRef: RefObject<HTMLInputElement | null>;
}) {
  return (
    <div className="relative">
      <Search
        className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-300"
        aria-hidden
      />
      <input
        ref={inputRef}
        type="text"
        role="searchbox"
        aria-label={ariaLabel}
        value={value}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "Enter") {
            event.preventDefault();
            onSubmit();
          }
        }}
        className="field-input pl-9 pr-9"
      />
      {value ? (
        <button
          type="button"
          aria-label="Clear search"
          title="Clear search"
          onClick={onClear}
          className="absolute right-2 top-1/2 grid h-6 w-6 -translate-y-1/2 place-items-center rounded-full text-error-600 transition hover:bg-error-100"
        >
          <X className="h-4 w-4" aria-hidden />
        </button>
      ) : null}
    </div>
  );
}
