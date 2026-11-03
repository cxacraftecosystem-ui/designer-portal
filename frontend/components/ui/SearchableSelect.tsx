"use client";

import { Check, ChevronDown, Search, X } from "lucide-react";
import { Fragment, useCallback, useEffect, useId, useMemo, useRef, useState } from "react";

import { AnchoredPopover } from "@/components/ui/AnchoredPopover";
import { useFieldLabelId } from "@/components/ui/fieldLabel";
import {
  CAP_HINT_WITHOUT_SEARCH,
  CAP_HINT_WITH_SEARCH,
  RENDER_CAP,
  SEARCHING_LABEL,
  SEARCH_THRESHOLD,
  emptyListSentence,
  scrollTopToShow,
  virtualSlice,
  filterOptions,
  fold,
  groupRows,
  listAnnouncement,
  selectionSummarySentence,
  truncationSentence,
  typeaheadIndex,
  type SelectOption
} from "@/components/ui/selectFilter";
import { focusNextField } from "@/lib/formNav";
import { cn } from "@/lib/utils";

export type { SelectOption };

export type SelectCreateAction = {
  label: (term: string) => string;
  onCreate: (term: string) => void;
};

export type SelectServerQuery = {
  value: string;
  onChange: (term: string) => void;
  pending: boolean;
  truncated?: boolean;
};

const EMPTY_PINS: ReadonlySet<string> = new Set<string>();

const TRIGGER_CLASS =
  "flex w-full items-center justify-between gap-2 rounded-md border border-line-200 bg-card px-3.5 py-2.5 text-left text-sm text-ink-900 outline-hidden transition hover:border-purple-300 focus:border-purple-600 focus:ring-4 focus:ring-purple-600/15 disabled:cursor-not-allowed disabled:opacity-60";

const PANEL_CLASS = "overflow-hidden! p-0! flex flex-col";

const PANEL_MAX_WIDTH = 520;

const ROW_HEIGHT_GUESS = 36;

function useTypeahead() {
  const state = useRef({ text: "", at: 0 });
  return useCallback((char: string) => {
    const now = Date.now();
    if (now - state.current.at > 700) state.current.text = "";
    state.current.at = now;
    state.current.text += char.toLowerCase();
    return state.current.text;
  }, []);
}

function stepHighlight(options: SelectOption[], current: number, delta: number) {
  const n = options.length;
  if (!n) return -1;
  let i = current < 0 || current >= n ? (delta > 0 ? -1 : n) : current;
  for (let step = 0; step < n; step++) {
    i = (i + delta + n) % n;
    if (!options[i].disabled) return i;
  }
  return -1;
}

function isPrintable(event: React.KeyboardEvent) {
  return event.key.length === 1 && event.key !== " " && !event.ctrlKey && !event.metaKey && !event.altKey;
}

function firstEnabled(options: SelectOption[]) {
  return options.findIndex((option) => !option.disabled);
}

function optionClass(option: SelectOption, highlighted: boolean, active: boolean) {
  return cn(
    "flex items-center gap-2 px-3.5 py-2 text-sm",
    option.disabled ? "cursor-not-allowed text-ink-300" : "cursor-pointer",
    !option.disabled && highlighted && "bg-purple-50 dark:bg-purple-950",
    active ? "font-medium text-purple-700 dark:text-purple-300" : !option.disabled ? "text-ink-700" : ""
  );
}

function useScrollHighlightIntoView(
  open: boolean,
  highlight: number,
  baseId: string,
  virtual: boolean,
  scrollToIndex: (index: number) => void
) {
  useEffect(() => {
    if (!open || highlight < 0) return;
    if (virtual) scrollToIndex(highlight);
    else document.getElementById(`${baseId}-opt-${highlight}`)?.scrollIntoView({ block: "nearest" });
  }, [open, highlight, baseId, virtual, scrollToIndex]);
}

type VirtualWindow = {
  virtual: boolean;
  start: number;
  end: number;
  padTop: number;
  padBottom: number;
  listRef: (node: HTMLUListElement | null) => void;
  onScroll: (event: React.UIEvent<HTMLUListElement>) => void;
  scrollToIndex: (index: number) => void;
};

function useVirtualWindow(count: number, flat: boolean): VirtualWindow {
  const virtual = virtualSlice(count, 0, 0, ROW_HEIGHT_GUESS, flat).virtual;
  const [node, setNode] = useState<HTMLUListElement | null>(null);
  const [scrollTop, setScrollTop] = useState(0);
  const [viewport, setViewport] = useState(288);
  const [rowHeight, setRowHeight] = useState(ROW_HEIGHT_GUESS);

  useEffect(() => {
    if (!node || !virtual) return;
    setViewport(node.clientHeight || 288);
    const row = node.querySelector<HTMLElement>('[role="option"]');
    if (row && row.offsetHeight > 0) setRowHeight(row.offsetHeight);
  }, [node, virtual, count]);

  const nodeRef = useRef<HTMLUListElement | null>(null);
  const listRef = useCallback((next: HTMLUListElement | null) => {
    nodeRef.current = next;
    setNode(next);
  }, []);
  const onScroll = useCallback((event: React.UIEvent<HTMLUListElement>) => {
    setScrollTop(event.currentTarget.scrollTop);
  }, []);
  const scrollToIndex = useCallback(
    (index: number) => {
      const list = nodeRef.current;
      if (!list) return;
      list.scrollTop = scrollTopToShow(index, rowHeight, list.scrollTop, list.clientHeight);
      setScrollTop(list.scrollTop);
    },
    [rowHeight]
  );

  return { ...virtualSlice(count, scrollTop, viewport, rowHeight, flat), listRef, onScroll, scrollToIndex };
}

function useSelectList(
  options: SelectOption[],
  query: string,
  searchable: boolean,
  pins: ReadonlySet<string>,
  serverAnswered: boolean
) {
  const filtered = useMemo(
    () => (searchable && !serverAnswered ? filterOptions(options, query) : options),
    [options, query, searchable, serverAnswered]
  );
  const { windowed, pinned } = useMemo(() => {
    if (!serverAnswered || filtered.length <= RENDER_CAP) return { windowed: filtered, pinned: 0 };
    const window = filtered.slice(0, RENDER_CAP);
    const missing = filtered.filter((option) => pins.has(option.value) && !window.includes(option));
    return missing.length
      ? { windowed: [...missing, ...window], pinned: missing.length }
      : { windowed: window, pinned: 0 };
  }, [filtered, pins, serverAnswered]);
  return { filtered, windowed, pinned, capped: filtered.length - windowed.length };
}

function containEvents(onKeyDown: (event: React.KeyboardEvent) => void) {
  return {
    onKeyDown: (event: React.KeyboardEvent) => {
      onKeyDown(event);
      event.stopPropagation();
    },
    onInput: (event: React.FormEvent) => event.stopPropagation(),
    onChange: (event: React.FormEvent) => event.stopPropagation()
  };
}

function SearchRow({
  inputRef,
  inputId,
  listboxId,
  activeId,
  query,
  onQueryChange,
  onKeyDown,
  placeholder,
  label,
  pending,
  trailing
}: {
  inputRef: (node: HTMLInputElement | null) => void;
  inputId: string;
  listboxId: string;
  activeId?: string;
  query: string;
  onQueryChange: (value: string) => void;
  onKeyDown: (event: React.KeyboardEvent<HTMLInputElement>) => void;
  placeholder: string;
  label: string;
  pending?: boolean;
  trailing?: React.ReactNode;
}) {
  return (
    <div className="flex shrink-0 items-center gap-2 border-b border-line-200 p-2">
      <div className="relative min-w-0 flex-1">
        <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-ink-500" aria-hidden />
        <input
          ref={inputRef}
          id={inputId}
          type="text"
          role="combobox"
          aria-expanded
          aria-controls={listboxId}
          aria-autocomplete="list"
          aria-activedescendant={activeId}
          aria-label={label}
          autoComplete="off"
          spellCheck={false}
          value={query}
          placeholder={placeholder}
          onChange={(event) => onQueryChange(event.target.value)}
          onKeyDown={onKeyDown}
          className="w-full rounded-sm border border-line-200 bg-card py-1.5 pl-8 pr-2 text-sm text-ink-900 outline-hidden transition placeholder:text-ink-300 focus:border-purple-600 focus:ring-2 focus:ring-purple-600/15"
        />
      </div>
      {pending ? (
        <span aria-hidden className="shrink-0 whitespace-nowrap text-xs text-ink-500">
          {SEARCHING_LABEL}
        </span>
      ) : null}
      {trailing}
    </div>
  );
}

function CapNotice({ sentence }: { sentence: string }) {
  return (
    <p className="shrink-0 border-t border-line-200 bg-surface-50 px-3.5 py-2 text-xs leading-4 text-ink-500">
      {sentence}
    </p>
  );
}

function noneOptionFor(noneLabel: string | undefined, options: SelectOption[]): SelectOption | null {
  if (!noneLabel) return null;
  if (options.some((option) => option.value === "")) return null;
  return { value: "", label: noneLabel };
}

function GroupHeading({ name }: { name: string }) {
  return (
    <div
      aria-hidden
      className="px-3.5 pb-1 pt-2 text-[0.6875rem] font-semibold uppercase tracking-wide text-ink-500"
    >
      {name}
    </div>
  );
}

function OptionText({ option }: { option: SelectOption }) {
  return (
    <>
      <span className="min-w-0 flex-1 truncate">{option.label}</span>
      {option.hint ? (
        <span className="min-w-0 max-w-[55%] shrink truncate text-xs text-ink-500">{option.hint}</span>
      ) : null}
    </>
  );
}

function optionTitle(option: SelectOption) {
  return option.hint ? `${option.label} — ${option.hint}` : option.label;
}

function useEdgeTab(
  panelRef: React.RefObject<HTMLDivElement | null>,
  onTabForward: () => void,
  onTabBackward: () => void
) {
  return useCallback(
    (event: React.KeyboardEvent) => {
      if (event.key !== "Tab") return;
      const panel = panelRef.current;
      if (!panel) return;
      const stops = Array.from(
        panel.querySelectorAll<HTMLElement>("input:not([disabled]),button:not([disabled])")
      );
      const index = stops.indexOf(document.activeElement as HTMLElement);
      if (index < 0) return;
      if (!event.shiftKey && index === stops.length - 1) {
        event.preventDefault();
        onTabForward();
      } else if (event.shiftKey && index === 0) {
        event.preventDefault();
        onTabBackward();
      }
    },
    [panelRef, onTabForward, onTabBackward]
  );
}

function useFilterBoxFocus(triggerRef: React.RefObject<HTMLButtonElement | null>) {
  return useCallback(
    (node: HTMLInputElement | null) => {
      if (!node) return;
      requestAnimationFrame(() => {
        if (!node.isConnected) return;
        const active = document.activeElement;
        if (active && active !== document.body && active !== triggerRef.current) return;
        node.focus({ preventScroll: true });
      });
    },
    [triggerRef]
  );
}

export type SearchableSelectProps = {
  value: string;
  onChange: (value: string) => void;
  options: SelectOption[];
  placeholder?: string;
  emptyLabel?: string;
  disabled?: boolean;
  className?: string;
  ariaLabel?: string;
  describedBy?: string;
  searchable?: boolean;
  capHint?: string;
  advanceOnSelect?: boolean;
  serverQuery?: SelectServerQuery;
  noneLabel?: string;
  createAction?: SelectCreateAction;
  clearable?: boolean;
  clearLabel?: string;
  onSearch?: (query: string) => void;
  id?: string;
};

export function SearchableSelect({
  value,
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
  advanceOnSelect = true,
  serverQuery,
  noneLabel,
  createAction,
  clearable = false,
  clearLabel,
  onSearch,
  id
}: SearchableSelectProps) {
  const [open, setOpen] = useState(false);
  const [ownQuery, setOwnQueryState] = useState("");
  const searchRef = useRef(onSearch);
  useEffect(() => {
    searchRef.current = onSearch;
  });
  const setOwnQuery = useCallback((next: string) => {
    setOwnQueryState(next);
    searchRef.current?.(next);
  }, []);
  const [highlight, setHighlight] = useState(0);
  const [highlightTouched, setHighlightTouched] = useState(false);
  const [pins, setPins] = useState<ReadonlySet<string>>(EMPTY_PINS);
  const wrapperRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const baseId = useId();
  const listboxId = `${baseId}-listbox`;
  const triggerId = id ?? `${baseId}-trigger`;
  const typeahead = useTypeahead();
  const fieldLabelId = useFieldLabelId();

  const serverDriven = serverQuery != null;
  const query = serverQuery ? serverQuery.value : ownQuery;

  function writeQuery(next: string) {
    if (serverQuery) serverQuery.onChange(next);
    else setOwnQuery(next);
  }

  const withSearch = serverDriven || createAction != null || (searchable ?? options.length >= SEARCH_THRESHOLD);
  const { filtered, windowed, pinned, capped } = useSelectList(
    options,
    query,
    withSearch,
    pins,
    serverDriven
  );

  const noneOption = useMemo(() => noneOptionFor(noneLabel, options), [noneLabel, options]);
  const noneRow = noneOption && !query.trim() ? noneOption : null;
  const showClear = clearable && !disabled && value !== "" && value != null;
  const rendered = useMemo(
    () => (noneRow ? [noneRow, ...windowed] : windowed),
    [noneRow, windowed]
  );

  const createTerm = useMemo(() => {
    if (!createAction) return null;
    const term = query.trim();
    if (!term) return null;
    const folded = fold(term);
    if (options.some((option) => fold(option.label) === folded)) return null;
    return term;
  }, [createAction, query, options]);

  const safeHighlight =
    highlight >= 0 && highlight < rendered.length && !rendered[highlight].disabled
      ? highlight
      : firstEnabled(rendered);
  const activeId = safeHighlight >= 0 ? `${baseId}-opt-${safeHighlight}` : undefined;
  const {
    virtual: listVirtual,
    start: listStart,
    end: listEnd,
    padTop: listPadTop,
    padBottom: listPadBottom,
    listRef,
    onScroll: onListScroll,
    scrollToIndex
  } = useVirtualWindow(rendered.length, !rendered.some((option) => option.group));
  useScrollHighlightIntoView(open, safeHighlight, baseId, listVirtual, scrollToIndex);

  const selected =
    options.find((option) => option.value === value) ??
    (noneOption && value === "" ? noneOption : undefined);
  const showingNone = selected != null && selected === noneOption;

  const close = useCallback(() => {
    setOpen(false);
    setOwnQuery("");
  }, [setOwnQuery]);

  const closeAndMoveOn = useCallback(
    (advance: boolean) => {
      close();
      requestAnimationFrame(() => {
        if (advance && focusNextField(wrapperRef.current)) return;
        triggerRef.current?.focus({ preventScroll: true });
      });
    },
    [close]
  );

  function choose(index: number) {
    const option = rendered[index];
    if (!option || option.disabled) return;
    onChange(option.value);
    closeAndMoveOn(advanceOnSelect);
  }

  function create() {
    if (!createAction || !createTerm) return;
    createAction.onCreate(createTerm);
    closeAndMoveOn(advanceOnSelect);
  }

  function openPanel(seed = "") {
    if (seed || !serverDriven) writeQuery(seed);
    setOpen(true);
    setPins(value ? new Set([value]) : EMPTY_PINS);
    if (seed) {
      setHighlight(0);
      setHighlightTouched(false);
      return;
    }
    const at = rendered.findIndex((option) => option.value === value);
    setHighlight(at >= 0 && !rendered[at].disabled ? at : firstEnabled(rendered));
    setHighlightTouched(false);
  }

  function seedFilter(char: string) {
    if (!open) {
      openPanel(char);
      return;
    }
    writeQuery(query + char);
    setHighlight(0);
    setHighlightTouched(false);
    if (!serverDriven) setPins(value ? new Set([value]) : EMPTY_PINS);
    document.getElementById(`${baseId}-search`)?.focus();
  }

  function onQueryChange(next: string) {
    writeQuery(next);
    setHighlight(0);
    setHighlightTouched(false);
    if (!serverDriven) setPins(value ? new Set([value]) : EMPTY_PINS);
  }

  useEffect(() => {
    if (!serverDriven) return;
    setPins(value ? new Set([value]) : EMPTY_PINS);
  }, [serverDriven, options, value]);

  const onTabForward = useCallback(() => {
    if (highlightTouched && safeHighlight >= 0 && rendered[safeHighlight]) {
      onChange(rendered[safeHighlight].value);
    }
    closeAndMoveOn(true);
  }, [highlightTouched, safeHighlight, rendered, onChange, closeAndMoveOn]);

  const onTabBackward = useCallback(() => closeAndMoveOn(false), [closeAndMoveOn]);
  const onPanelKeyDown = useEdgeTab(panelRef, onTabForward, onTabBackward);

  function navigate(event: React.KeyboardEvent, textEntry = false) {
    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        if (open) {
          setHighlight(stepHighlight(rendered, safeHighlight, 1));
          setHighlightTouched(true);
        } else openPanel();
        return true;
      case "ArrowUp":
        event.preventDefault();
        if (open) {
          setHighlight(stepHighlight(rendered, safeHighlight, -1));
          setHighlightTouched(true);
        } else openPanel();
        return true;
      case "Home":
        if (!open || textEntry) return false;
        event.preventDefault();
        setHighlight(stepHighlight(rendered, -1, 1));
        setHighlightTouched(true);
        return true;
      case "End":
        if (!open || textEntry) return false;
        event.preventDefault();
        setHighlight(stepHighlight(rendered, -1, -1));
        setHighlightTouched(true);
        return true;
      case "Enter":
        if (!open) return false;
        event.preventDefault();
        if (safeHighlight >= 0) choose(safeHighlight);
        else if (createTerm) create();
        return true;
      default:
        return false;
    }
  }

  function onFilterKeyDown(event: React.KeyboardEvent<HTMLInputElement>) {
    navigate(event, true);
  }

  function onTriggerKeyDown(event: React.KeyboardEvent) {
    if (navigate(event)) return;
    if (event.key === " ") {
      event.preventDefault();
      if (open) {
        if (safeHighlight >= 0) choose(safeHighlight);
      } else openPanel();
      return;
    }
    if (event.key === "Tab" && open) close();
    if (isPrintable(event)) {
      event.preventDefault();
      if (withSearch) {
        seedFilter(event.key);
        return;
      }
      const typed = typeahead(event.key);
      if (open) {
        const at = typeaheadIndex(rendered, typed);
        if (at < 0) return;
        setHighlight(at);
        setHighlightTouched(true);
        return;
      }
      const match = typeaheadIndex(options, typed);
      if (match < 0) return;
      onChange(options[match].value);
    }
  }

  const inputRef = useFilterBoxFocus(triggerRef);
  const groups = useMemo(() => groupRows(rendered), [rendered]);

  const announcement = listAnnouncement({
    total: options.length,
    matched: filtered.length,
    term: withSearch ? query : "",
    server: serverDriven,
    pending: serverQuery?.pending ?? false,
    truncated: serverQuery?.truncated ?? false
  });

  const capAdvice = capHint ?? (withSearch ? CAP_HINT_WITH_SEARCH : CAP_HINT_WITHOUT_SEARCH);
  const capSentence = truncationSentence({
    shown: windowed.length - pinned,
    pinned,
    total: filtered.length,
    capped,
    term: query,
    hint: capAdvice,
    serverTruncated: serverQuery?.truncated ?? false
  });

  return (
    <div ref={wrapperRef} className={cn("relative min-w-0", className)}>
      <button
        ref={triggerRef}
        type="button"
        disabled={disabled}
        role={withSearch ? undefined : "combobox"}
        id={triggerId}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={ariaLabel}
        aria-labelledby={!ariaLabel && fieldLabelId ? `${fieldLabelId} ${triggerId}` : undefined}
        aria-describedby={describedBy}
        aria-controls={open ? listboxId : undefined}
        aria-activedescendant={open && !withSearch ? activeId : undefined}
        onClick={() => (open ? close() : openPanel())}
        onKeyDown={onTriggerKeyDown}
        data-form-field=""
        data-searchable-select=""
        className={cn(TRIGGER_CLASS, showClear && "pr-16 pointer-coarse:pr-20")}
      >
        <span
          className={cn("min-w-0 truncate", (!selected || showingNone) && "text-ink-500")}
          title={selected?.label}
        >
          {selected ? selected.label : placeholder}
        </span>
        <ChevronDown
          className={cn("h-4 w-4 shrink-0 text-ink-500 transition-transform", open && "rotate-180")}
          aria-hidden
        />
      </button>
      {showClear ? (
        <button
          type="button"
          aria-label={clearLabel ?? (ariaLabel ? `Clear ${ariaLabel}` : "Clear selection")}
          title="Clear"
          onClick={() => {
            onChange("");
            triggerRef.current?.focus({ preventScroll: true });
          }}
          className="absolute right-8 top-1/2 grid h-8 w-8 -translate-y-1/2 place-items-center rounded-full text-ink-500 transition hover:bg-surface-50 hover:text-ink-900 focus-visible:outline-2 focus-visible:outline-purple-600 pointer-coarse:h-11 pointer-coarse:w-11"
        >
          <X className="h-3.5 w-3.5" aria-hidden />
        </button>
      ) : null}

      <AnchoredPopover
        open={open}
        onClose={close}
        anchorRef={wrapperRef}
        restoreFocusRef={triggerRef}
        label={ariaLabel ? `${ariaLabel} options` : "Options"}
        offset={4}
        matchAnchorWidth
        maxWidth={PANEL_MAX_WIDTH}
        className={PANEL_CLASS}
      >
        <div ref={panelRef} {...containEvents(onPanelKeyDown)} className="flex min-h-0 flex-col">
          {withSearch ? (
            <SearchRow
              inputRef={inputRef}
              inputId={`${baseId}-search`}
              listboxId={listboxId}
              activeId={activeId}
              query={query}
              onQueryChange={onQueryChange}
              onKeyDown={onFilterKeyDown}
              placeholder={serverDriven ? "Type to search" : "Type to filter"}
              label={
                serverDriven
                  ? ariaLabel
                    ? `Search ${ariaLabel}`
                    : "Search options"
                  : ariaLabel
                    ? `Filter ${ariaLabel}`
                    : "Filter options"
              }
              pending={serverQuery?.pending}
            />
          ) : null}
          <ul
            ref={listRef}
            id={listboxId}
            role="listbox"
            aria-label={ariaLabel ?? "Options"}
            onScroll={onListScroll}
            style={listVirtual ? { paddingTop: listPadTop, paddingBottom: listPadBottom } : undefined}
            className="min-h-0 max-h-72 shrink overflow-y-auto overscroll-contain py-1"
          >
            {groups.map((bucket) => {
              const visible = listVirtual ? bucket.rows.slice(listStart, listEnd) : bucket.rows;
              const rows = visible.map(({ option, index }) => {
                const active = option.value === value;
                return (
                  <li
                    key={option.value}
                    id={`${baseId}-opt-${index}`}
                    role="option"
                    aria-setsize={listVirtual ? rendered.length : undefined}
                    aria-posinset={listVirtual ? index + 1 : undefined}
                    aria-selected={active}
                    aria-disabled={option.disabled || undefined}
                    title={optionTitle(option)}
                    onMouseEnter={() => {
                      if (!option.disabled) {
                        setHighlight(index);
                        setHighlightTouched(true);
                      }
                    }}
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={() => choose(index)}
                    className={optionClass(option, index === safeHighlight, active)}
                  >
                    <OptionText option={option} />
                    {active ? (
                      <Check className="h-4 w-4 shrink-0 text-purple-700 dark:text-purple-300" aria-hidden />
                    ) : null}
                  </li>
                );
              });
              if (!bucket.group) return <Fragment key="ungrouped">{rows}</Fragment>;
              return (
                <li key={`group-${bucket.group}`} role="group" aria-label={bucket.group}>
                  <GroupHeading name={bucket.group} />
                  <ul role="none">{rows}</ul>
                </li>
              );
            })}
            {windowed.length === 0 ? (
              <li className="px-3.5 py-2 text-sm text-ink-500">
                {emptyListSentence({
                  emptyLabel,
                  term: query,
                  server: serverDriven,
                  pending: serverQuery?.pending ?? false
                })}
              </li>
            ) : null}
          </ul>
          {createTerm && createAction ? (
            <button
              type="button"
              onMouseDown={(event) => event.preventDefault()}
              onClick={create}
              className="shrink-0 truncate border-t border-line-200 px-3.5 py-2 text-left text-sm font-medium text-purple-700 transition hover:bg-purple-50 dark:text-purple-300 dark:hover:bg-purple-950"
            >
              {createAction.label(createTerm)}
            </button>
          ) : null}
          {capSentence ? <CapNotice sentence={capSentence} /> : null}
          <p className="sr-only" role="status" aria-live="polite">
            {announcement}
          </p>
        </div>
      </AnchoredPopover>
    </div>
  );
}

export type SearchableMultiSelectProps = {
  values: string[];
  onChange: (values: string[]) => void;
  options: SelectOption[];
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
  serverQuery?: SelectServerQuery;
  chips?: boolean;
};

export const CHIP_LIMIT = 12;

export function SearchableMultiSelect({
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
  serverQuery,
  chips = true
}: SearchableMultiSelectProps) {
  const [open, setOpen] = useState(false);
  const [ownQuery, setOwnQuery] = useState("");
  const [highlight, setHighlight] = useState(0);
  const [pins, setPins] = useState<ReadonlySet<string>>(EMPTY_PINS);
  const wrapperRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const baseId = useId();
  const listboxId = `${baseId}-listbox`;
  const triggerId = `${baseId}-trigger`;
  const typeahead = useTypeahead();
  const fieldLabelId = useFieldLabelId();

  const serverDriven = serverQuery != null;
  const query = serverQuery ? serverQuery.value : ownQuery;

  function writeQuery(next: string) {
    if (serverQuery) serverQuery.onChange(next);
    else setOwnQuery(next);
  }

  const withSearch = serverDriven || (searchable ?? options.length >= SEARCH_THRESHOLD);
  const chosen = useMemo(() => new Set(values), [values]);
  const { filtered, windowed, pinned, capped } = useSelectList(
    options,
    query,
    withSearch,
    pins,
    serverDriven
  );
  const rendered = windowed;

  const safeHighlight =
    highlight >= 0 && highlight < rendered.length && !rendered[highlight].disabled
      ? highlight
      : firstEnabled(rendered);
  const activeId = safeHighlight >= 0 ? `${baseId}-opt-${safeHighlight}` : undefined;
  const {
    virtual: listVirtual,
    start: listStart,
    end: listEnd,
    padTop: listPadTop,
    padBottom: listPadBottom,
    listRef,
    onScroll: onListScroll,
    scrollToIndex
  } = useVirtualWindow(rendered.length, !rendered.some((option) => option.group));
  useScrollHighlightIntoView(open, safeHighlight, baseId, listVirtual, scrollToIndex);

  const close = useCallback(() => {
    setOpen(false);
    setOwnQuery("");
  }, []);

  const closeAndMoveOn = useCallback(
    (advance: boolean) => {
      close();
      requestAnimationFrame(() => {
        if (advance && focusNextField(wrapperRef.current)) return;
        triggerRef.current?.focus({ preventScroll: true });
      });
    },
    [close]
  );

  function toggle(index: number) {
    const option = rendered[index];
    if (!option || option.disabled) return;
    if (chosen.has(option.value)) onChange(values.filter((v) => v !== option.value));
    else onChange([...values, option.value]);
  }

  const bulkRows = useMemo(() => filtered.filter((option) => !option.disabled), [filtered]);
  const allChosen = bulkRows.length > 0 && bulkRows.every((option) => chosen.has(option.value));
  const filtering = withSearch && query.trim().length > 0;
  const bulkLabel = serverQuery?.truncated
    ? allChosen
      ? `Clear the ${bulkRows.length} shown`
      : `Select the ${bulkRows.length} shown`
    : allChosen
      ? filtering
        ? `Clear ${bulkRows.length} matching`
        : `Clear all ${bulkRows.length}`
      : filtering
        ? `Select ${bulkRows.length} matching`
        : `Select all ${bulkRows.length}`;

  function applyBulk() {
    if (allChosen) {
      const drop = new Set(bulkRows.map((option) => option.value));
      onChange(values.filter((v) => !drop.has(v)));
      return;
    }
    const have = new Set(values);
    onChange([...values, ...bulkRows.map((option) => option.value).filter((v) => !have.has(v))]);
  }

  function openPanel(seed = "") {
    if (seed || !serverDriven) writeQuery(seed);
    setOpen(true);
    setPins(new Set(values));
    setHighlight(seed ? 0 : firstEnabled(rendered));
  }

  function seedFilter(char: string) {
    if (!open) {
      openPanel(char);
      return;
    }
    writeQuery(query + char);
    setHighlight(0);
    if (!serverDriven) setPins(new Set(values));
    document.getElementById(`${baseId}-search`)?.focus();
  }

  function onQueryChange(next: string) {
    writeQuery(next);
    setHighlight(0);
    if (!serverDriven) setPins(new Set(values));
  }

  const selectionRef = useRef(values);
  useEffect(() => {
    selectionRef.current = values;
  }, [values]);

  useEffect(() => {
    if (!serverDriven) return;
    setPins(new Set(selectionRef.current));
  }, [serverDriven, options]);

  const onTabForward = useCallback(() => closeAndMoveOn(true), [closeAndMoveOn]);
  const onTabBackward = useCallback(() => closeAndMoveOn(false), [closeAndMoveOn]);
  const onPanelKeyDown = useEdgeTab(panelRef, onTabForward, onTabBackward);

  function navigate(event: React.KeyboardEvent, textEntry = false) {
    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        if (open) setHighlight(stepHighlight(rendered, safeHighlight, 1));
        else openPanel();
        return true;
      case "ArrowUp":
        event.preventDefault();
        if (open) setHighlight(stepHighlight(rendered, safeHighlight, -1));
        else openPanel();
        return true;
      case "Home":
        if (!open || textEntry) return false;
        event.preventDefault();
        setHighlight(stepHighlight(rendered, -1, 1));
        return true;
      case "End":
        if (!open || textEntry) return false;
        event.preventDefault();
        setHighlight(stepHighlight(rendered, -1, -1));
        return true;
      case "Enter":
        if (!open) return false;
        event.preventDefault();
        if (safeHighlight >= 0) toggle(safeHighlight);
        return true;
      default:
        return false;
    }
  }

  function onFilterKeyDown(event: React.KeyboardEvent<HTMLInputElement>) {
    navigate(event, true);
  }

  function onTriggerKeyDown(event: React.KeyboardEvent) {
    if (navigate(event)) return;
    if (event.key === " ") {
      event.preventDefault();
      if (open) {
        if (safeHighlight >= 0) toggle(safeHighlight);
      } else openPanel();
      return;
    }
    if (event.key === "Tab" && open) close();
    if (isPrintable(event)) {
      event.preventDefault();
      if (withSearch) {
        seedFilter(event.key);
        return;
      }
      const at = typeaheadIndex(rendered, typeahead(event.key));
      if (at < 0) return;
      if (!open) {
        setOpen(true);
        setPins(new Set(values));
      }
      setHighlight(at);
    }
  }

  const inputRef = useFilterBoxFocus(triggerRef);
  const groups = useMemo(() => groupRows(rendered), [rendered]);

  const chosenLabels = useMemo(
    () => options.filter((option) => chosen.has(option.value)).map((option) => option.label),
    [options, chosen]
  );
  const selectionSummary = selectionSummarySentence({
    selected: values.length,
    names: chosenLabels
  });
  const announcement = `${listAnnouncement({
    total: options.length,
    matched: filtered.length,
    term: withSearch ? query : "",
    server: serverDriven,
    pending: serverQuery?.pending ?? false,
    truncated: serverQuery?.truncated ?? false
  })}. ${selectionSummary}.`;

  const capAdvice = capHint ?? (withSearch ? CAP_HINT_WITH_SEARCH : CAP_HINT_WITHOUT_SEARCH);
  const capSentence = truncationSentence({
    shown: windowed.length - pinned,
    pinned,
    total: filtered.length,
    capped,
    term: query,
    hint: capAdvice,
    serverTruncated: serverQuery?.truncated ?? false
  });

  const bulkButton =
    bulk && bulkRows.length > 0 ? (
      <button
        type="button"
        onClick={applyBulk}
        onMouseDown={(event) => event.preventDefault()}
        className="shrink-0 whitespace-nowrap rounded-sm border border-line-200 bg-card px-2 py-1.5 text-xs font-medium text-purple-700 outline-hidden transition hover:border-purple-300 hover:bg-purple-50 focus-visible:border-purple-600 focus-visible:ring-2 focus-visible:ring-purple-600/20 dark:text-purple-300 dark:hover:bg-purple-950"
      >
        {bulkLabel}
      </button>
    ) : null;

  return (
    <div ref={wrapperRef} className={cn("relative min-w-0", className)}>
      <button
        ref={triggerRef}
        type="button"
        disabled={disabled}
        role={withSearch ? undefined : "combobox"}
        id={triggerId}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={ariaLabel ? `${ariaLabel}. ${selectionSummary}` : undefined}
        aria-labelledby={!ariaLabel && fieldLabelId ? `${fieldLabelId} ${triggerId}` : undefined}
        aria-describedby={describedBy}
        aria-controls={open ? listboxId : undefined}
        aria-activedescendant={open && !withSearch ? activeId : undefined}
        onClick={() => (open ? close() : openPanel())}
        onKeyDown={onTriggerKeyDown}
        data-form-field=""
        data-searchable-select=""
        className={TRIGGER_CLASS}
      >
        <span className={cn("min-w-0 truncate", !values.length && "text-ink-500")}>
          {values.length ? `${values.length} selected` : placeholder}
        </span>
        {ariaLabel ? null : <span className="sr-only">{selectionSummary}</span>}
        <ChevronDown
          className={cn("h-4 w-4 shrink-0 text-ink-500 transition-transform", open && "rotate-180")}
          aria-hidden
        />
      </button>

      {chips && values.length ? (
        <ul className="mt-2 flex flex-wrap gap-1.5" aria-label={ariaLabel ? `${ariaLabel}: selected` : "Selected"}>
          {values.slice(0, CHIP_LIMIT).map((value) => {
            const option = options.find((candidate) => candidate.value === value);
            const label = option?.label ?? value;
            const removable = !disabled && !option?.disabled;
            return (
              <li
                key={value}
                className="inline-flex min-h-8 max-w-full items-center gap-1 rounded-full border border-purple-200 bg-purple-50 py-0.5 pl-3 pr-1 text-xs font-medium text-purple-800 dark:border-purple-800 dark:bg-purple-950/40 dark:text-purple-200"
              >
                <span className="min-w-0 truncate">{label}</span>
                {removable ? (
                  <button
                    type="button"
                    aria-label={`Remove ${label}`}
                    onClick={() => onChange(values.filter((kept) => kept !== value))}
                    className="relative grid h-7 w-7 shrink-0 place-items-center rounded-full text-purple-700 transition hover:bg-purple-100 dark:text-purple-200 dark:hover:bg-purple-900 pointer-coarse:before:absolute pointer-coarse:before:-inset-2 pointer-coarse:before:content-['']"
                  >
                    <X className="h-3.5 w-3.5" aria-hidden />
                  </button>
                ) : (
                  <span className="w-2" aria-hidden />
                )}
              </li>
            );
          })}
          {values.length > CHIP_LIMIT ? (
            <li className="self-center text-xs text-ink-500">and {values.length - CHIP_LIMIT} more</li>
          ) : null}
        </ul>
      ) : null}

      <AnchoredPopover
        open={open}
        onClose={close}
        anchorRef={wrapperRef}
        restoreFocusRef={triggerRef}
        label={ariaLabel ? `${ariaLabel} options` : "Options"}
        offset={4}
        matchAnchorWidth
        maxWidth={PANEL_MAX_WIDTH}
        className={PANEL_CLASS}
      >
        <div ref={panelRef} {...containEvents(onPanelKeyDown)} className="flex min-h-0 flex-col">
          {withSearch ? (
            <SearchRow
              inputRef={inputRef}
              inputId={`${baseId}-search`}
              listboxId={listboxId}
              activeId={activeId}
              query={query}
              onQueryChange={onQueryChange}
              onKeyDown={onFilterKeyDown}
              placeholder={serverDriven ? "Type to search" : "Type to filter"}
              label={
                serverDriven
                  ? ariaLabel
                    ? `Search ${ariaLabel}`
                    : "Search options"
                  : ariaLabel
                    ? `Filter ${ariaLabel}`
                    : "Filter options"
              }
              pending={serverQuery?.pending}
              trailing={bulkButton}
            />
          ) : bulkButton ? (
            <div className="flex shrink-0 justify-end border-b border-line-200 p-2">{bulkButton}</div>
          ) : null}
          <ul
            ref={listRef}
            id={listboxId}
            role="listbox"
            aria-multiselectable
            aria-label={ariaLabel ?? "Options"}
            onScroll={onListScroll}
            style={listVirtual ? { paddingTop: listPadTop, paddingBottom: listPadBottom } : undefined}
            className="min-h-0 max-h-72 shrink overflow-y-auto overscroll-contain py-1"
          >
            {groups.map((bucket) => {
              const visible = listVirtual ? bucket.rows.slice(listStart, listEnd) : bucket.rows;
              const rows = visible.map(({ option, index }) => {
                const checked = chosen.has(option.value);
                return (
                  <li
                    key={option.value}
                    id={`${baseId}-opt-${index}`}
                    role="option"
                    aria-setsize={listVirtual ? rendered.length : undefined}
                    aria-posinset={listVirtual ? index + 1 : undefined}
                    aria-selected={checked}
                    aria-disabled={option.disabled || undefined}
                    title={optionTitle(option)}
                    onMouseEnter={() => {
                      if (!option.disabled) setHighlight(index);
                    }}
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={() => toggle(index)}
                    className={optionClass(option, index === safeHighlight, checked)}
                  >
                    <span
                      aria-hidden
                      className={cn(
                        "grid h-4 w-4 shrink-0 place-items-center rounded border transition",
                        checked ? "border-purple-700 bg-purple-700 text-white" : "border-line-200 bg-card"
                      )}
                    >
                      {checked ? <Check className="h-3 w-3" /> : null}
                    </span>
                    <OptionText option={option} />
                  </li>
                );
              });
              if (!bucket.group) return <Fragment key="ungrouped">{rows}</Fragment>;
              return (
                <li key={`group-${bucket.group}`} role="group" aria-label={bucket.group}>
                  <GroupHeading name={bucket.group} />
                  <ul role="none">{rows}</ul>
                </li>
              );
            })}
            {windowed.length === 0 ? (
              <li className="px-3.5 py-2 text-sm text-ink-500">
                {emptyListSentence({
                  emptyLabel,
                  term: query,
                  server: serverDriven,
                  pending: serverQuery?.pending ?? false
                })}
              </li>
            ) : null}
          </ul>
          {capSentence ? <CapNotice sentence={capSentence} /> : null}
          {confirmOnSelect && values.length > 0 ? (
            <div className="shrink-0 border-t border-line-200 bg-card p-2">
              <button
                type="button"
                className="field-button w-full py-1.5 text-xs"
                onClick={() => closeAndMoveOn(true)}
                onMouseDown={(event) => event.preventDefault()}
              >
                {confirmLabel} ({values.length})
              </button>
            </div>
          ) : null}
          <p className="sr-only" role="status" aria-live="polite">
            {announcement}
          </p>
        </div>
      </AnchoredPopover>
    </div>
  );
}
