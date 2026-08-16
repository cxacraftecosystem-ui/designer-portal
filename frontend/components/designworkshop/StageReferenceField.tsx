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

/**
 * The widest page the server will serve — `REFERENCE_LIMIT_MAX` in `design_workshops.py`, which
 * clamps anything larger rather than refusing it.
 *
 * Asked for by ONE call only: {@link describeCreated}, which is hunting for a single record it
 * already knows the id of. Every other request here is a list a human reads, and fifty is the right
 * size for that; a page four times as long would be four times the ILIKE scan for rows nobody
 * scrolls to. The hunt is the opposite case — the one row it exists to find is the one that must not
 * fall off the end of the page.
 */
const REFERENCE_PAGE_MAX = 200;

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
 * The record that did not exist when the list was fetched
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A record `InlineRecordDialog` just made, in the only shape this file is allowed to read it in.
 *
 * The dialog hands back the RAW REPOSITORY ROW — the same JSON `POST /products` returns — and the
 * whole argument below is that this file must not interpret one. So the type names the three
 * name-bearing columns and nothing else: `name` on an Artisan and a Process, `productName` on a
 * ProductDocumentation, `toolkitName` on a ToolDocumentation.
 */
type CreatedRecord = {
  id: string;
  name?: string | null;
  productName?: string | null;
  toolkitName?: string | null;
};

/**
 * The name a just-created record goes by.
 *
 * THE ONE THING READ OFF A RAW ROW HERE, and the exception is narrow on purpose. A label is a
 * label on every surface — the server's own `label` lambda for each of these models reads this
 * exact column, and it is also the FIRST of that model's `search_fields`, which is what makes it
 * usable as the search term in {@link describeCreated}. The values that fill a stage row are a
 * different matter entirely; see the note there for why none of them may be read from here.
 */
function createdLabel(record: CreatedRecord): string {
  return (record.name || record.productName || record.toolkitName || "").trim();
}

/**
 * WHAT THE SERVER WOULD SAY ABOUT A RECORD THAT DID NOT EXIST WHEN THE LIST WAS FETCHED.
 *
 * THE DEFECT THIS EXISTS TO END. Both pickers below used to build the new option's `data` out of
 * the raw row the create returned and hand it straight to `hydrateFromReference`. The row's keys
 * are PRISMA COLUMN NAMES and the hydration table's keys are the REFERENCE PAYLOAD's: for a
 * product the table asks for `name`, `price`, `use`, `material`, and the row carries
 * `productName`, `sellingPrice`, `productFunctionUse`, `rawMaterialsUsed`. Not one of them
 * matched, so an inline-created product hydrated NOTHING. That is not a cosmetic loss:
 * `existingProduct.name` and `existingProduct.price` are `required=True` and the server validates
 * BEFORE it hydrates (`design_workshops.py` says so beside the ordering), so the designer was left
 * looking at two blank required boxes and the stage 422'd on submit — seconds after they created
 * the record that holds both answers.
 *
 * WHY THE FIX IS A ROUND TRIP AND NOT A RENAMING TABLE IN THE BROWSER. `REFERENCE_MODELS[…].data`
 * is not a rename of the row, it is a TRANSLATION, and every part of it is load-bearing:
 * `_inches_to_cm` multiplies by 2.54 because the source columns are inches and
 * `existingProduct.lengthCm` prints "cm"; `_money` renders a Prisma Decimal as a two-place string
 * because a float round trip turns 1250.10 into 1250.0999999999999; `mask_identity_number` keeps a
 * full PM Vishwakarma card number out of a report every grantee can download; three exhaustive
 * enum tables refuse to guess a PRODUCT_CATEGORY for the four ProductType members that have no
 * honest one; the photograph and its caption are a join onto MediaFile that the row does not
 * contain at all. A browser-side copy would be a SECOND declaration of knowledge that already
 * lives in exactly one place — and every way it could drift writes a value that is WRONG rather
 * than missing. Hydration only ever fills blanks, so a wrong value written here can be corrected
 * but never un-answered; there is no state the mistake heals from. One request is cheaper.
 *
 * ANDROID DECIDED THIS FIRST and matching the handset is worth more than inventing a third
 * behaviour: `DwReferenceField.kt` arms `pendingHydration` for a record it holds an id but no
 * description of, and waits for the server's `data`, with a comment explaining that hydrating from
 * an empty map would be actively destructive.
 *
 * FOUND BY SEARCHING FOR THE RECORD'S OWN NAME, because the references endpoint has no by-id
 * route — see {@link createdLabel} for why that name is a reliable search term rather than a
 * guess.
 *
 * ASKED WITH THE SAME SCOPE AND FILTER THE PICKER'S OWN LIST USES, deliberately. A wider question
 * here would hydrate a row from a record this picker can never show: if the new product does not
 * belong to the artisan on this row, the cascade excludes it from the list and it must be excluded
 * from the hydration too, or the row quietly holds another artisan's price under this one's name.
 *
 * Returns null for both kinds of miss — a network failure and a record the narrowed list does not
 * contain — because the caller has one honest sentence to say about either and no action that
 * differs between them.
 */
async function describeCreated(
  workshopId: string,
  field: DwField,
  filterValue: string,
  record: CreatedRecord
): Promise<DwReferenceOption | null> {
  const search = createdLabel(record);
  try {
    const payload = await listStageReferences(workshopId, {
      model: field.refModel as string,
      scope: field.refScope,
      filterBy: field.refFilterBy ? filterValue || null : null,
      search: search || null,
      limit: REFERENCE_PAGE_MAX
    });
    return payload.options.find((option) => option.id === record.id) ?? null;
  } catch {
    return null;
  }
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
  /**
   * The one line of amber under this control, and it has two authors.
   *
   * The cascade writes it when it clears a choice that no longer belongs to the record above.
   * {@link adoptCreated} writes it when a record was created and the server could not be got to
   * describe it. Named `notice` rather than `cascadeNotice` since the second author arrived: a
   * message about an inline create is not a cascade message, and two amber boxes stacked under one
   * field is a form arguing with itself.
   */
  const [notice, setNotice] = useState<string | null>(null);
  /**
   * A record made from inside this picker, held only until the server can describe it.
   *
   * Carries the label because nothing else on screen can name it: it is in no fetched list, and
   * the display field it would normally be read back from has not been hydrated yet. Android keeps
   * a `locallyCreated` stand-in for exactly this window and for exactly this reason.
   */
  const [pending, setPending] = useState<{ id: string; label: string } | null>(null);
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
    options.find((option) => option.id === selectedId)?.label ||
    // A record created seconds ago is in no list and has hydrated no display field onto the row
    // yet, so its own name is the only thing on this screen that can name it. It is consulted
    // BEFORE the row's hint because during a re-point the row still holds the PREVIOUS record's
    // name — showing that beside the new record's id is the one outcome the hydration rules exist
    // to prevent, and it must not be reintroduced by a label.
    (pending?.id === selectedId ? pending.label : "") ||
    referenceDisplayHint(entity, field, row);

  /**
   * The row as it is RIGHT NOW, for a hydration that started before the current render.
   *
   * `adoptCreated` awaits a round trip and only then applies its patch, and the only-fill-blanks
   * rule reads the row to decide what it may write. The `row` captured when the create finished is
   * a snapshot; a designer types into the boxes beside the picker while the answer is in flight,
   * and a patch decided against the snapshot would overwrite what they had just typed. This is the
   * hook equivalent of the handset rebuilding its `openInlineRecord` closure on every composition,
   * which its own comment says is there to keep hydration bookkeeping from going stale.
   */
  const latestRow = useRef(row);
  useEffect(() => {
    latestRow.current = row;
  }, [row]);

  /**
   * Which deferred hydration is still wanted.
   *
   * A create arms an answer that lands a round trip later, and anything the designer does in
   * between — picking somebody else, clearing the link, creating a second record — makes that
   * answer wrong rather than merely late. Bumping the count is how the in-flight continuation
   * learns it has been superseded; Android drops its `pendingHydration` at the same three moments
   * and names the same failure, one artisan's village landing under another's name.
   */
  const hydration = useRef(0);
  const supersede = useCallback(() => {
    hydration.current += 1;
    setPending(null);
  }, []);

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
    setNotice(
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
      // A pick supersedes a create still waiting to be described. Left armed, the answer landing a
      // moment later would fill this row in from the record made before the designer changed their
      // mind — one product's price under another product's name, with the id naming the second.
      supersede();
      const patch = hydrateFromReference(entity, field, option, row, selectedId);
      // One write, not two. The id and the fields it filled belong to the same act, and applying
      // them separately would let a re-render between them see a row naming a record whose name it
      // has not copied yet — which is the state the report reads as "reference to a deleted record".
      onPatch({ ...patch, [field.key]: option.id });
      setOpen(false);
      setQuery("");
      setNotice(null);
    },
    [entity, field, row, selectedId, onPatch, supersede]
  );

  /**
   * A record created from inside this picker: linked at once, hydrated when the server can say what
   * belongs on the row.
   *
   * TWO WRITES AND NOT ONE, WHICH IS THE OPPOSITE OF WHAT `choose` ARGUES ABOVE, and the difference
   * is that here there is nothing to write in the first act. The link is the half we are certain
   * of; the values are a question only the server can answer, and a picker that showed nothing at
   * all until a round trip came back reads as the create having failed — which is the moment a
   * designer creates the same record a second time. So the link lands immediately, the control says
   * what it is waiting for, and the boxes fill when the answer arrives.
   */
  const adoptCreated = useCallback(
    async (record: CreatedRecord) => {
      const label = createdLabel(record);
      /*
       * The record this row named BEFORE this one, read here rather than at the end because by then
       * the field holds the new id. It is the whole difference between "fill the blanks" and "clear
       * what the last record wrote, then fill" — see the long block in `hydrateFromReference`.
       */
      const previous = selectedId;
      onChange(record.id);
      setPending({ id: record.id, label });
      setNotice(null);
      hydration.current += 1;
      const generation = hydration.current;

      const described = await describeCreated(workshopId, field, filterValue, record);
      // Superseded while the answer was in flight. Whatever the designer did instead is newer than
      // this, and writing now would land a stale record's values on a row that has moved on.
      if (hydration.current !== generation) return;
      setPending(null);

      if (!described) {
        setNotice(
          previous
            ? "The record was saved and linked, but this list cannot describe it just now — so the boxes the previous record had filled in have been CLEARED rather than left standing under the new record's name. Fill them in by hand, or reopen the list and search for it."
            : "The record was saved and linked, but this list cannot describe it just now, so the boxes it would have filled in are still blank. Fill them in by hand, or reopen the list and search for it — a required box left blank is refused when the stage is submitted."
        );
      }

      /*
       * ONE CALL FOR BOTH OUTCOMES, AND THE FAILING ONE IS NOT A NO-OP.
       *
       * A description that never arrived is handed on as an option with an EMPTY `data`, which the
       * same table then reads exactly as it should: with no previous link there is nothing to clear
       * and nothing to write, so the patch is empty; with a previous link the mapped boxes are
       * cleared and left blank. That second case is the one worth spelling out — the alternative is
       * the previous record's name, village and price sitting beside the new record's id, which
       * `hydrateFromReference`'s own comment calls the one outcome worse than either alternative,
       * and which nothing downstream could ever re-resolve. A blank required box is refused loudly
       * at submit; a filled box naming the wrong record is not refused at all.
       *
       * The id is rewritten alongside the patch for the same reason `choose` writes them together,
       * and it is safe to rewrite because a designer who unlinked or re-picked during the round
       * trip has already superseded this continuation above.
       */
      const option: DwReferenceOption = described ?? { id: record.id, label, sublabel: "", data: {} };
      onPatch({
        ...hydrateFromReference(entity, field, option, latestRow.current, previous),
        [field.key]: record.id
      });
    },
    [entity, field, filterValue, onChange, onPatch, selectedId, workshopId]
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
              //
              // An unlink also supersedes a create still waiting to be described: left armed, the
              // answer landing a second later would re-link the record the designer has just
              // deliberately unlinked and fill the row in from it. The handset drops its
              // `pendingHydration` on the same gesture and says the same thing.
              supersede();
              onChange(null);
              setNotice(null);
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

      {/*
        Said, not spun. The link is already made and visible above; this line exists so the blank
        boxes beside it read as "not yet" rather than as "this picker filled nothing in", which is
        the reading that has a designer retyping what is about to arrive.
      */}
      {pending ? (
        <p className="text-xs leading-5 text-ink-500">
          Filling in what the repository holds about “{pending.label || "the new record"}”…
        </p>
      ) : null}

      {notice ? (
        <p className="rounded-md border border-amber-500/30 bg-amber-100 px-2 py-1 text-xs leading-5 text-amber-800">
          {notice}
        </p>
      ) : null}

      {inlineDialog && isInlineCreatable(field.refModel) ? (
        <InlineRecordDialog
          open
          model={field.refModel}
          recordId={inlineDialog.mode === "edit" ? inlineDialog.id : undefined}
          onClose={() => setInlineDialog(null)}
          /*
            LINKED FROM THE CREATE, DESCRIBED FROM THE SERVER — see `adoptCreated` and
            `describeCreated` for the defect that split those two apart. What used to be here was a
            `DwReferenceOption` assembled out of the raw repository row, whose column names are not
            the hydration table's keys; it hydrated nothing and left required boxes blank.

            Nothing has to invalidate the list: `useReferenceOptions` re-fetches whenever the picker
            becomes active again, so the next open shows the record in its proper place with the
            server's own label and sublabel.
          */
          onCreated={(record) => {
            void adoptCreated(record);
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
  /** The label of a record just created, while the server is being asked to describe it. */
  const [describing, setDescribing] = useState<string | null>(null);
  /** Said inside the panel when that description could not be got. */
  const [createProblem, setCreateProblem] = useState<string | null>(null);
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
      /*
        A CLICK INSIDE THE RECORD DIALOG IS NOT A CLICK OUTSIDE THIS PANEL, however the DOM reads.

        `FieldDialog` renders through a PORTAL, so the artisan form the "Create a new artisan"
        button opens is not a descendant of `wrapperRef` — and this handler, which cannot see the
        difference between the dialog and the page behind it, closed the roster on the designer's
        very first keystroke in that form. Reopening it resets `picked`, so the half-ticked list the
        button exists to protect was lost anyway and the record they had just made was never added.
        The single picker does not have this problem only because it closes itself deliberately
        before opening the dialog.

        Matched on the overlay's own data attribute rather than on a ref, because the dialog is
        mounted by the picker's own subtree and owning a ref into somebody else's portal is a
        tighter coupling than reading the marker it already publishes.
      */
      const target = event.target as Element | null;
      if (target?.closest?.("[data-field-dialog-overlay]")) return;
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

  /**
   * A record created from inside the roster: described by the server, THEN ticked.
   *
   * The same defect and the same fix as `adoptCreated` on the single picker — see
   * {@link describeCreated} for why the raw row the create returns cannot be handed to hydration.
   * The difference is what happens when the description cannot be got. This control's ticks become
   * ROWS, through `hydrateFromReference` in `EntityForm.addFromReferences`, so a tick made from an
   * empty payload is a roster row carrying a link and no name — indistinguishable in the
   * participant table from a designer who added a blank row by accident. Nothing is ticked in that
   * case; the panel says the record was saved and where to find it, and the search above will now
   * return it because the server does hold it.
   */
  const tickCreated = useCallback(
    async (record: CreatedRecord) => {
      setCreateProblem(null);
      setDescribing(createdLabel(record) || "the new record");
      // No filter value to carry: `EntityForm` only offers this control for a REF field with no
      // `refFilterBy` at all, so the roster's list is never a cascaded one.
      const option = await describeCreated(workshopId, field, "", record);
      setDescribing(null);
      if (!option) {
        setCreateProblem(
          "The record was saved, but this list cannot describe it just now, so it has not been ticked. Search for its name above and tick it there."
        );
        return;
      }
      setPicked((current) => (current.some((entry) => entry.id === option.id) ? current : [...current, option]));
    },
    [field, workshopId]
  );

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
          // Cleared with the selection it belongs to. Left standing, "the record was saved but has
          // not been ticked" would greet the next designer to open this roster, about a record they
          // did not create and can now see in the list.
          setCreateProblem(null);
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

          {describing ? (
            <p className="border-t border-line-200 px-3 py-2 text-xs leading-5 text-ink-500">
              Reading back what the repository holds about “{describing}”…
            </p>
          ) : null}
          {createProblem ? (
            <p className="border-t border-line-200 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
              {createProblem}
            </p>
          ) : null}

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
          /*
            TICKED, not added. The roster's own "Add" button is what commits the selection, and a
            record that added itself the moment it was created would break that — the designer would
            have made a row they had not confirmed, in a list they were still building. So the new
            record joins `picked` and the count goes up by one, exactly as if they had found it and
            ticked it.

            What it is ticked WITH is the server's description of the record and never the raw row
            the create returned; `tickCreated` and `describeCreated` carry that argument.
          */
          onCreated={(record) => {
            void tickCreated(record);
          }}
        />
      ) : null}
    </div>
  );
}
