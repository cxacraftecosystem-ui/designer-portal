"use client";

/**
 * The REF picker: search the records the database already holds, choose one, and let it fill the row
 * in.
 *
 * WHY THIS IS NOT `Dropdown` / `SearchableSelect`. Those two filter a list the caller already has, in
 * the browser, and that is exactly the wrong shape here. A cluster has hundreds of artisans, the
 * server caps a reference response at fifty rows and says so, and the product list is not a list at
 * all until an artisan has been chosen — it is a different list per row. So the search is the
 * SERVER'S (`GET /design-workshops/{id}/references`), debounced, and the panel is a real combobox
 * over whatever came back.
 *
 * THE CASCADE is the reason this file exists rather than a two-line change to the old dropdown. A
 * descriptor carrying `refFilterBy` names a field ON THE SAME ROW whose value narrows this one: the
 * product picker on an "existing products" row offers the products of the artisan chosen on THAT row
 * and nothing else. Two rows are two artisans and therefore two lists, which is why the filter value
 * is read from the row rather than from the stage.
 *
 * THREE THINGS THE SERVER SAYS THAT THIS PANEL MUST REPEAT, because each of them is a way a picker
 * lies about its own contents:
 *
 * - `scopedToWorkshop: false` on a WORKSHOP-scoped field means the workshop is not linked to a
 *   Workshop record and the server widened to the whole table rather than serving nothing. A
 *   designer reading an unlabelled list assumes it is this workshop's roster and picks somebody who
 *   was never in the room.
 * - `truncated: true` means there are more matches than are drawn. A list that quietly stops at
 *   fifty is indistinguishable from a cluster with fifty artisans.
 * - `filtered: true` with an EMPTY list is a real and ordinary answer — the artisan was typed in by
 *   hand on day two and has no documented products. Saying "no results" there invites the designer
 *   to clear the artisan and pick somebody else's product.
 */

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { Check, ChevronDown, Loader2, Pencil, Plus, Search, X } from "lucide-react";

import {
  INLINE_MODEL_NOUN,
  InlineRecordDialog,
  isInlineCreatable,
  type InlineCreatableModel
} from "@/components/designworkshop/InlineRecordDialog";
import {
  hydrateFromReference,
  inputValue,
  listStageReferences,
  referenceDisplayHint,
  type DwEntity,
  type DwEntryData,
  type DwField,
  type DwReferenceOption,
  type DwReferencePayload,
  type DwValue
} from "@/lib/designWorkshops";

/**
 * How long after the last keystroke the search goes out.
 *
 * 300 ms is the measured floor for this: a `contains` search over the artisan table is an ILIKE
 * '%…%' that no index can answer (the server says so in its own comment), so every keystroke that
 * escapes the debounce is a full scan of the largest table in the database. Shorter and a fast
 * typist fires six of them for one name; longer and the list visibly lags the box.
 */
const SEARCH_DEBOUNCE_MS = 300;

/** The server's own default page. Stated here only so the truncation notice can name it. */
const REFERENCE_PAGE = 50;

type PickerState = {
  payload: DwReferencePayload | null;
  loading: boolean;
  problem: string | null;
};

/**
 * Everything the two variants share: the debounce, the race guard and the request itself.
 *
 * `generation` and not an `AbortSignal`, because `apiFetch` takes no signal — the house convention
 * for this exact case is to count fetches and ignore the late answer. A picker that rendered a stale
 * answer would show the results for "kam" under the word "kamla", which is precisely when a designer
 * clicks the first row without reading it.
 */
function useReferenceOptions({
  workshopId,
  field,
  filterValue,
  query,
  active
}: {
  workshopId: string;
  field: DwField;
  filterValue: string;
  query: string;
  active: boolean;
}): PickerState {
  const [state, setState] = useState<PickerState>({ payload: null, loading: false, problem: null });
  const generation = useRef(0);

  useEffect(() => {
    if (!active || !field.refModel) return;
    const current = generation.current + 1;
    generation.current = current;
    setState((previous) => ({ ...previous, loading: true, problem: null }));
    const timer = window.setTimeout(() => {
      listStageReferences(workshopId, {
        model: field.refModel as string,
        scope: field.refScope,
        // Sent only when the descriptor asks for the cascade. An unasked-for `filterBy` on a model
        // that cannot honour one is a 422 by design — the server refuses to silently serve the whole
        // table to a picker the designer believes is narrowed.
        filterBy: field.refFilterBy ? filterValue || null : null,
        search: query.trim() || null
      })
        .then((payload) => {
          if (generation.current !== current) return;
          setState({ payload, loading: false, problem: null });
        })
        .catch((error) => {
          if (generation.current !== current) return;
          setState({
            payload: null,
            loading: false,
            problem: error instanceof Error ? error.message : "The list could not be loaded."
          });
        });
    }, query ? SEARCH_DEBOUNCE_MS : 0);
    return () => window.clearTimeout(timer);
  }, [workshopId, field.refModel, field.refScope, field.refFilterBy, filterValue, query, active]);

  return state;
}

/** The line under the list that says what the list actually is. Never omitted when it has something to say. */
function ScopeNotice({ field, payload }: { field: DwField; payload: DwReferencePayload | null }) {
  if (!payload) return null;
  const lines: string[] = [];
  if (field.refScope === "WORKSHOP" && !payload.scopedToWorkshop) {
    lines.push(
      "This design workshop is not linked to a workshop record yet, so every documented record is offered rather than only this workshop's."
    );
  }
  if (payload.truncated) {
    lines.push(`Only the first ${REFERENCE_PAGE} matches are listed — type more of the name to narrow them.`);
  }
  if (!lines.length) return null;
  return <p className="px-3 pb-2 text-xs leading-5 text-ink-500">{lines.join(" ")}</p>;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Single select
 * ──────────────────────────────────────────────────────────────────────────── */

export function StageReferenceSelect({
  workshopId,
  entity,
  field,
  row,
  value,
  onChange,
  onPatch,
  disabled,
  labelId
}: {
  workshopId: string;
  entity: DwEntity;
  field: DwField;
  /** The whole record this field sits on — the cascade and the hydration both read it. */
  row: DwEntryData;
  value: DwValue | undefined;
  onChange: (value: DwValue) => void;
  /** Write several keys of the same record at once. Hydration is a multi-key write by nature. */
  onPatch: (values: Record<string, DwValue>) => void;
  disabled?: boolean;
  labelId: string;
}) {
  const baseId = useId();
  const listboxId = `${baseId}-listbox`;
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [highlight, setHighlight] = useState(0);
  /** Set when the cascade cleared this field, so the clearing is announced rather than silent. */
  const [cascadeNotice, setCascadeNotice] = useState<string | null>(null);
  /**
   * Which record the inline dialog is working on, or null when it is closed.
   *
   * `{ mode: "create" }` makes a new one; `{ mode: "edit", id }` opens the linked record. One piece
   * of state rather than two booleans, because the two are mutually exclusive and two flags is how
   * a dialog ends up open in both modes at once.
   */
  const [inlineDialog, setInlineDialog] = useState<{ mode: "create" } | { mode: "edit"; id: string } | null>(null);
  const wrapperRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);

  const selectedId = inputValue(value);
  const filterValue = field.refFilterBy ? inputValue(row[field.refFilterBy]) : "";
  /**
   * A cascading picker with nothing to cascade FROM asks for nothing at all.
   *
   * The server treats an absent `filterBy` as "no filter" and serves the whole table, which is the
   * correct answer to the question it was asked and the wrong list to put on this control: the
   * descriptor says this field is the products OF the artisan on this row, so an unnarrowed list
   * offers another artisan's work to a designer who has every reason to believe it was narrowed.
   * Nothing is fetched and the panel says which box to answer first.
   */
  const awaitingCascade = Boolean(field.refFilterBy) && !filterValue;
  const { payload, loading, problem } = useReferenceOptions({
    workshopId,
    field,
    filterValue,
    query,
    active: open && !awaitingCascade
  });
  const options = payload?.options ?? [];

  /**
   * The name of the record already chosen.
   *
   * Preferred over anything the option list happens to hold, because the list is the results of
   * whatever is typed in the search box right now and the chosen record is very often not in it. See
   * {@link referenceDisplayHint}: the name is on the row already, put there by hydration.
   */
  const chosenLabel =
    options.find((option) => option.id === selectedId)?.label || referenceDisplayHint(entity, field, row);

  /**
   * WHEN THE ARTISAN CHANGES, THE PRODUCT CHOSEN FOR THE PREVIOUS ARTISAN IS CLEARED — and said.
   *
   * Leaving it is the worse half of the trade and it is not close: the id would still point at
   * another artisan's product, the server's hydration would leave the copied product name standing
   * because the field is no longer blank, and the report's table would attribute one artisan's work
   * to another with nothing anywhere admitting it. Clearing loses one pick that has to be made
   * again, in a picker that is now showing the right list.
   *
   * Only a CHANGE fires it. The ref is seeded with the filter's value at mount, so re-opening a
   * saved row — where the artisan and the product were both stored and agree — clears nothing.
   */
  const lastFilter = useRef(filterValue);
  useEffect(() => {
    if (!field.refFilterBy) return;
    if (lastFilter.current === filterValue) return;
    const had = lastFilter.current;
    lastFilter.current = filterValue;
    if (!selectedId) return;
    onChange(null);
    setCascadeNotice(
      had
        ? "The record this list depends on changed, so the previous choice was cleared — pick one from the new list."
        : "Choose the record above first; this list narrows to it."
    );
    // `selectedId` and `onChange` are deliberately outside the dependency list: this effect must run
    // when the FILTER moves and at no other time. Including the value would re-run it on the clear
    // it just performed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterValue, field.refFilterBy]);

  // Dismissal. Bound on the document rather than the panel so a click anywhere lands, and on
  // `mousedown` so the panel is gone before the click's own target has a chance to move under it.
  useEffect(() => {
    if (!open) return;
    function onPointer(event: MouseEvent) {
      if (!wrapperRef.current?.contains(event.target as Node)) setOpen(false);
    }
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.stopPropagation();
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  useEffect(() => {
    if (open) inputRef.current?.focus();
  }, [open]);

  // Derived every render, never trusted from state: the stored index goes stale the instant the
  // filter changes, and Enter would then commit a row that is not on screen.
  const safeHighlight = highlight >= 0 && highlight < options.length ? highlight : 0;

  const choose = useCallback(
    (option: DwReferenceOption) => {
      const patch = hydrateFromReference(entity, field, option, row, selectedId);
      // One write, not two. The id and the fields it filled belong to the same act, and applying
      // them separately would let a re-render between them see a row naming a record whose name it
      // has not copied yet — which is the state the report reads as "reference to a deleted record".
      onPatch({ ...patch, [field.key]: option.id });
      setOpen(false);
      setQuery("");
      setCascadeNotice(null);
    },
    [entity, field, row, selectedId, onPatch]
  );

  /** The LABEL of the field this one cascades from — never its key: "artisanRef" is not a question. */
  const cascadeLabel = field.refFilterBy
    ? (entity.fields.find((candidate) => candidate.key === field.refFilterBy)?.label ?? field.refFilterBy)
    : "";

  const emptyLine = useMemo(() => {
    if (awaitingCascade) {
      return `Answer “${cascadeLabel}” on this row first — this list holds only the records belonging to it.`;
    }
    if (loading) return "Searching…";
    if (problem) return problem;
    if (payload?.filtered && !options.length) {
      return "That record has nothing documented under it yet. Anything typed in by hand here stays typed in — it is not a mistake.";
    }
    if (query.trim()) return `Nothing matches “${query.trim()}”.`;
    return "No records to choose from yet.";
  }, [awaitingCascade, cascadeLabel, loading, problem, payload, options.length, query]);

  return (
    <div className="grid gap-1" ref={wrapperRef}>
      <div className="relative">
        <button
          type="button"
          className="field-input flex w-full items-center justify-between gap-2 text-left"
          aria-haspopup="listbox"
          aria-expanded={open}
          aria-labelledby={labelId}
          disabled={disabled}
          onClick={() => setOpen((current) => !current)}
        >
          {/* `ink-500` rather than the `ink-300` placeholder rung: with nothing chosen this span is
              the control's only text, and ink-300 is 2.44:1 on the card — below AA. See the same
              note on `SearchableSelect`'s trigger. */}
          <span className={chosenLabel ? "min-w-0 flex-1 truncate text-ink-900" : "min-w-0 flex-1 truncate text-ink-500"}>
            {chosenLabel || (selectedId ? "A record is selected — open the list to see which" : "Search and select")}
          </span>
          <ChevronDown className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
        </button>

        {open ? (
          // z-10 is the ladder's rung for in-page chrome. Nothing on a stage page is fixed except the
          // island at z-50, which this must stay under.
          <div className="absolute left-0 right-0 top-full z-10 mt-1 overflow-hidden rounded-md border border-line-200 bg-card shadow-panel">
            <div className="flex items-center gap-2 border-b border-line-200 px-3 py-2">
              <Search className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
              <input
                ref={inputRef}
                className="min-w-0 flex-1 bg-transparent text-sm text-ink-900 outline-none placeholder:text-ink-300"
                type="text"
                role="combobox"
                aria-expanded
                aria-controls={listboxId}
                aria-autocomplete="list"
                aria-activedescendant={options.length ? `${baseId}-opt-${safeHighlight}` : undefined}
                placeholder={`Search ${field.label.toLowerCase()}`}
                value={query}
                onChange={(event) => {
                  setQuery(event.target.value);
                  setHighlight(0);
                }}
                onKeyDown={(event) => {
                  if (event.key === "ArrowDown") {
                    event.preventDefault();
                    setHighlight((current) => Math.min(current + 1, Math.max(0, options.length - 1)));
                  } else if (event.key === "ArrowUp") {
                    event.preventDefault();
                    setHighlight((current) => Math.max(current - 1, 0));
                  } else if (event.key === "Enter") {
                    event.preventDefault();
                    const option = options[safeHighlight];
                    if (option) choose(option);
                  } else if (event.key === "Escape") {
                    event.preventDefault();
                    setOpen(false);
                  }
                }}
              />
              {loading ? <Loader2 className="h-4 w-4 shrink-0 animate-spin text-ink-500" aria-hidden /> : null}
            </div>

            <ul id={listboxId} role="listbox" aria-labelledby={labelId} className="max-h-72 overflow-y-auto">
              {options.length ? (
                options.map((option, index) => {
                  const chosen = option.id === selectedId;
                  return (
                    <li key={option.id} id={`${baseId}-opt-${index}`} role="option" aria-selected={chosen}>
                      <button
                        type="button"
                        className={`flex w-full items-start gap-2 px-3 py-2 text-left transition ${
                          index === safeHighlight ? "bg-purple-50" : "hover:bg-surface-50"
                        }`}
                        onMouseEnter={() => setHighlight(index)}
                        onClick={() => choose(option)}
                      >
                        <span className="mt-0.5 h-4 w-4 shrink-0 text-purple-700">
                          {chosen ? <Check className="h-4 w-4" aria-hidden /> : null}
                        </span>
                        <span className="min-w-0">
                          <span className="block truncate text-sm text-ink-900">{option.label}</span>
                          {option.sublabel ? (
                            <span className="block truncate text-xs text-ink-500">{option.sublabel}</span>
                          ) : null}
                        </span>
                      </button>
                    </li>
                  );
                })
              ) : (
                <li className="px-3 py-3 text-sm text-ink-500">{emptyLine}</li>
              )}
            </ul>
            {/*
              CREATE THE MISSING RECORD FROM HERE, rather than leaving a half-filled stage to do it.

              Always offered, not only when the search finds nothing: a designer often knows the
              artisan is absent before they finish typing the name, and a control that appears only
              after an empty result is one they have to discover twice. It sits BELOW the list so it
              can never be the thing an Enter key commits.

              Only for repository records — see `isInlineCreatable`. A `DwSketch` or `DwPrototype`
              is a row of this same workshop and is added by its own stage, not from a picker.
            */}
            {isInlineCreatable(field.refModel) && !disabled ? (
              <button
                type="button"
                className="flex w-full items-center gap-2 border-t border-line-200 px-3 py-2.5 text-left text-sm font-medium text-purple-700 transition hover:bg-purple-50"
                onClick={() => {
                  setOpen(false);
                  setInlineDialog({ mode: "create" });
                }}
              >
                <Plus className="h-4 w-4 shrink-0" aria-hidden />
                {query.trim()
                  ? `Create “${query.trim()}” as a new ${INLINE_MODEL_NOUN[field.refModel as InlineCreatableModel]}`
                  : `Create a new ${INLINE_MODEL_NOUN[field.refModel as InlineCreatableModel]}`}
              </button>
            ) : null}
            <ScopeNotice field={field} payload={payload} />
          </div>
        ) : null}
      </div>

      {selectedId ? (
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            className="inline-flex items-center gap-1 text-xs font-medium text-ink-500 underline"
            disabled={disabled}
            onClick={() => {
              // Only the reference is cleared. The name, village and phone it filled in STAY: they
              // are what the designer confirmed in the room, and a report that loses a participant's
              // name because somebody unlinked a duplicate artisan record is the failure the copy
              // exists to prevent.
              onChange(null);
              setCascadeNotice(null);
            }}
          >
            <X className="h-3 w-3" aria-hidden />
            Clear the link
          </button>
          {/*
            FIX THE RECORD FROM HERE TOO. Spotting that the artisan's village is wrong while filling
            stage 13 is the common case, and the only remedy was to leave the stage. The dialog
            re-reads the record rather than seeding from this picker's option, which carries only a
            label and a sublabel — a form seeded from that would blank every field it does not hold.
          */}
          {isInlineCreatable(field.refModel) && !disabled ? (
            <button
              type="button"
              className="inline-flex items-center gap-1 rounded-md border border-line-200 px-2 py-1 text-xs font-medium text-ink-700 transition hover:border-purple-300 hover:bg-purple-50"
              onClick={() => setInlineDialog({ mode: "edit", id: selectedId })}
            >
              <Pencil className="h-3 w-3" aria-hidden />
              Edit this {INLINE_MODEL_NOUN[field.refModel as InlineCreatableModel]}
            </button>
          ) : null}
          <span className="text-xs text-ink-500">
            Clearing the link keeps the details it filled in — they are part of this record now.
          </span>
        </div>
      ) : null}

      {cascadeNotice ? (
        <p className="rounded-md border border-amber-500/30 bg-amber-100 px-2 py-1 text-xs leading-5 text-amber-800">
          {cascadeNotice}
        </p>
      ) : null}

      {inlineDialog && isInlineCreatable(field.refModel) ? (
        <InlineRecordDialog
          open
          model={field.refModel}
          recordId={inlineDialog.mode === "edit" ? inlineDialog.id : undefined}
          onClose={() => setInlineDialog(null)}
          onCreated={(record) => {
            /*
              SELECTED IMMEDIATELY, and hydrated from the record we already hold.

              The new record is not in `options` — that list was fetched before it existed — so the
              option is built here from what the create returned. Re-fetching first and then
              selecting would leave the picker briefly showing nothing while the designer waits,
              and it would be a round trip in the middle of their sentence.

              Nothing has to invalidate the list: `useReferenceOptions` re-fetches whenever the
              picker becomes active again, and `choose` closes it — so the next open shows the
              record in its proper place with the server's own sublabel.
            */
            const option: DwReferenceOption = {
              id: record.id,
              label:
                (record as { name?: string; productName?: string; toolkitName?: string }).name ??
                (record as { productName?: string }).productName ??
                (record as { toolkitName?: string }).toolkitName ??
                "",
              sublabel: (record as { place?: string }).place ?? "",
              data: record as unknown as Record<string, unknown>
            };
            choose(option);
          }}
        />
      ) : null}
    </div>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * Multi select — the roster
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Choose many records at once, for the collections whose rows ARE the chosen records.
 *
 * The roster is the case this was asked for: a workshop has thirty participants, each one a row of
 * the same collection whose only required field is an artisan reference, and adding them one at a
 * time is thirty presses of "Add participant" followed by thirty searches. Here the list is opened
 * once, ticked, and confirmed — and each tick becomes a row, hydrated with the artisan's name,
 * village and specialisation exactly as a single pick would be.
 *
 * WHY IT DOES NOT ALSO REMOVE. Un-ticking a name would have to delete a row, and a row is not just
 * its reference: by the second day it carries days attended, a photograph, and notes from an
 * interview. So this control only ADDS — already-chosen records are shown ticked and disabled, and
 * removal stays where a row is removed, next to the row, where what is about to be lost is visible.
 */
export function StageReferenceMultiPicker({
  workshopId,
  field,
  /** Ids already used by a row of the collection — ticked, disabled, and counted. */
  alreadyChosen,
  onAdd,
  disabled,
  triggerLabel
}: {
  workshopId: string;
  field: DwField;
  alreadyChosen: string[];
  onAdd: (options: DwReferenceOption[]) => void;
  disabled?: boolean;
  triggerLabel: string;
}) {
  const baseId = useId();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [picked, setPicked] = useState<DwReferenceOption[]>([]);
  /** Open while the designer is creating a record that is missing from the roster. */
  const [creating, setCreating] = useState(false);
  const wrapperRef = useRef<HTMLDivElement | null>(null);

  const { payload, loading, problem } = useReferenceOptions({
    workshopId,
    field,
    filterValue: "",
    query,
    active: open
  });
  const options = payload?.options ?? [];
  const existing = useMemo(() => new Set(alreadyChosen), [alreadyChosen]);
  const pickedIds = useMemo(() => new Set(picked.map((option) => option.id)), [picked]);

  useEffect(() => {
    if (!open) return;
    function onPointer(event: MouseEvent) {
      if (!wrapperRef.current?.contains(event.target as Node)) setOpen(false);
    }
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.stopPropagation();
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  return (
    <div className="relative" ref={wrapperRef}>
      <button
        type="button"
        className="field-button-secondary"
        aria-haspopup="listbox"
        aria-expanded={open}
        disabled={disabled}
        onClick={() => {
          setOpen((current) => !current);
          setPicked([]);
        }}
      >
        {triggerLabel}
      </button>

      {open ? (
        <div className="absolute right-0 z-10 mt-1 w-[min(28rem,90vw)] overflow-hidden rounded-md border border-line-200 bg-card shadow-panel">
          <div className="flex items-center gap-2 border-b border-line-200 px-3 py-2">
            <Search className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
            <input
              className="min-w-0 flex-1 bg-transparent text-sm text-ink-900 outline-none placeholder:text-ink-300"
              type="text"
              autoFocus
              aria-label={`Search ${field.label.toLowerCase()}`}
              placeholder={`Search ${field.label.toLowerCase()}`}
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
            {loading ? <Loader2 className="h-4 w-4 shrink-0 animate-spin text-ink-500" aria-hidden /> : null}
          </div>

          <ul role="listbox" aria-multiselectable className="max-h-72 overflow-y-auto">
            {options.length ? (
              options.map((option) => {
                const used = existing.has(option.id);
                const ticked = pickedIds.has(option.id);
                return (
                  <li key={option.id} role="option" aria-selected={ticked || used}>
                    <button
                      type="button"
                      className={`flex w-full items-start gap-2 px-3 py-2 text-left transition ${
                        used ? "cursor-not-allowed opacity-60" : ticked ? "bg-purple-50" : "hover:bg-surface-50"
                      }`}
                      disabled={used}
                      onClick={() =>
                        setPicked((current) =>
                          current.some((item) => item.id === option.id)
                            ? current.filter((item) => item.id !== option.id)
                            : [...current, option]
                        )
                      }
                    >
                      <span
                        aria-hidden
                        className={`mt-0.5 grid h-4 w-4 shrink-0 place-items-center rounded-sm border ${
                          ticked || used ? "border-purple-700 bg-purple-700 text-white" : "border-line-200 bg-card"
                        }`}
                      >
                        {ticked || used ? <Check className="h-3 w-3" /> : null}
                      </span>
                      <span className="min-w-0">
                        <span className="block truncate text-sm text-ink-900">{option.label}</span>
                        {option.sublabel ? (
                          <span className="block truncate text-xs text-ink-500">{option.sublabel}</span>
                        ) : null}
                        {used ? <span className="block text-xs text-ink-500">Already on this list</span> : null}
                      </span>
                    </button>
                  </li>
                );
              })
            ) : (
              <li className="px-3 py-3 text-sm text-ink-500">
                {loading ? "Searching…" : problem ? problem : query.trim() ? `Nothing matches “${query.trim()}”.` : "No records to choose from yet."}
              </li>
            )}
          </ul>

          {/*
            The same escape the single picker has, for the same reason. This is the ROSTER — the
            control a designer opens to tick thirty participants — so it is the likeliest place of
            all to discover that one of them has no record yet, and the most expensive place to have
            to leave: a half-ticked list is lost the moment the page navigates away.
          */}
          {isInlineCreatable(field.refModel) && !disabled ? (
            <button
              type="button"
              className="flex w-full items-center gap-2 border-t border-line-200 px-3 py-2.5 text-left text-sm font-medium text-purple-700 transition hover:bg-purple-50"
              onClick={() => setCreating(true)}
            >
              <Plus className="h-4 w-4 shrink-0" aria-hidden />
              {query.trim()
                ? `Create “${query.trim()}” as a new ${INLINE_MODEL_NOUN[field.refModel as InlineCreatableModel]}`
                : `Create a new ${INLINE_MODEL_NOUN[field.refModel as InlineCreatableModel]}`}
            </button>
          ) : null}

          <ScopeNotice field={field} payload={payload} />

          <div className="flex items-center justify-between gap-2 border-t border-line-200 px-3 py-2">
            <span className="text-xs text-ink-500" id={`${baseId}-count`}>
              {picked.length ? `${picked.length} selected` : "Nothing selected yet"}
            </span>
            <div className="flex items-center gap-2">
              <button type="button" className="text-xs font-medium text-ink-500 underline" onClick={() => setOpen(false)}>
                Cancel
              </button>
              <button
                type="button"
                className="field-button"
                aria-describedby={`${baseId}-count`}
                disabled={!picked.length}
                onClick={() => {
                  onAdd(picked);
                  setPicked([]);
                  setOpen(false);
                }}
              >
                Add {picked.length || ""}
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {creating && isInlineCreatable(field.refModel) ? (
        <InlineRecordDialog
          open
          model={field.refModel}
          onClose={() => setCreating(false)}
          onCreated={(record) => {
            /*
              TICKED, not added. The roster's own "Add" button is what commits the selection, and a
              record that added itself the moment it was created would break that — the designer
              would have made a row they had not confirmed, in a list they were still building.
              So the new record joins `picked` and the count goes up by one, exactly as if they had
              found it and ticked it.
            */
            const option: DwReferenceOption = {
              id: record.id,
              label:
                (record as { name?: string; productName?: string; toolkitName?: string }).name ??
                (record as { productName?: string }).productName ??
                (record as { toolkitName?: string }).toolkitName ??
                "",
              sublabel: (record as { place?: string }).place ?? "",
              data: record as unknown as Record<string, unknown>
            };
            setPicked((current) =>
              current.some((entry) => entry.id === option.id) ? current : [...current, option]
            );
          }}
        />
      ) : null}
    </div>
  );
}
