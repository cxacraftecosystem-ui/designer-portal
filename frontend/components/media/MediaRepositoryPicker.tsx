"use client";

/**
 * THE MEDIA PICKER: choose a file that is ALREADY IN THE REPOSITORY and make it this field's value.
 *
 * ── THE GAP IT CLOSES ──────────────────────────────────────────────────────────────────────────
 *
 * Until this control existed the web offered exactly one way to answer a media field: attach a NEW
 * upload. So the same photograph — one loom, photographed once — could not be pointed at from a
 * second record, and the way a designer got it there was to upload it again. Two rows, two objects,
 * two sets of bytes, and nothing anywhere saying they are the same picture. What a media field stores
 * is a MEDIA ID (see `MediaField`'s header), so pointing at an existing row is the natural act; there
 * was simply no control that did it.
 *
 * ── WHAT THIS IS NOT ───────────────────────────────────────────────────────────────────────────
 *
 * `components/media/ExistingMedia.tsx` is the nearest thing in the tree and it is a different control
 * for a different question: it lists the media a RECORD already has and its chooser narrows what that
 * panel SHOWS. It selects no value and, as its own header sets out, it cannot — no record type has a
 * column for "which of my files are the chosen ones". This one browses the REPOSITORY and produces a
 * value, so the two share nothing but a fetch. `designworkshop/StageReferenceField.tsx` is the real
 * sibling: "choose an existing record as this field's value", one model over.
 *
 * AND IT DOES NOT RE-PARENT THE FILE, which is the thing it would be easy to mistake this for and the
 * thing that genuinely cannot be built. A `MediaFile` has exactly ONE parent — a `linkedRecordType` /
 * `linkedRecordId` tag pair plus a typed foreign key, written when the file is uploaded — and there is
 * no join table, which is why "attach an existing file to a second RECORD" has been argued down twice
 * already (`ExistingMedia`'s header is one of them). A design-workshop stage field is a different
 * shape and that is what makes this buildable: its value is an ARRAY OF IDS in the stage entry's own
 * JSON, so pointing at a row costs the row nothing. Its tag columns are untouched, its original
 * record still lists it, and nothing here writes to the `MediaFile` table at all.
 *
 * ── THE SEARCH IS THE SERVER'S, AND THE PANEL'S OWN FILTER BOX IS OFF ──────────────────────────
 *
 * The house rule for a server-truncated list (`.claude/skills/field-repo-frontend`, §11.5): a
 * client-side filter searches only the rows that FITTED, so typing the name of a file that exists and
 * merely sorts late answers "No matches" — absence reading as non-existence, which is rule 10 wearing
 * a search box, and it looks exactly like the right control. So the box is above the picker and wired
 * to `GET /media`'s `search`; the panel gets `searchable={false}`; and it gets a `capHint`, because
 * turning the box off does not turn the RENDER CAP off and the default last clause ("Keep typing to
 * narrow the list") would name a control that is not on screen. `ReferenceMultiSelect`,
 * `WorkshopDesignerPicker` and `DesignWorkshopViewersPanel` are the same shape for the same reason.
 *
 * And the request asks for `RENDER_CAP` rows rather than the endpoint's ceiling of 100 — see
 * {@link MEDIA_PICKER_PAGE_SIZE} for the two-totals defect that number exists to prevent. One
 * consequence worth stating rather than leaving to be rediscovered: because the page size IS the
 * render cap, the panel's own "Showing the first 80 of …" footer — the sentence `capHint` is the last
 * clause of — cannot fire today, so there is exactly one truncation sentence on screen and it is the
 * one above the box. The hint is passed anyway, because the day either number moves is the day that
 * footer starts telling a reader to type into a filter box this control does not have.
 *
 * ── IT IS CLOSED UNTIL ASKED FOR ───────────────────────────────────────────────────────────────
 *
 * A stage can carry several media fields, and mounting this open would fire one repository query per
 * field on every stage open, for every designer, on a village connection — to populate a list most of
 * them will not use, because attaching a new photograph is still the common case. `StageDocumentPreview`
 * is closed for the same arithmetic. Nothing is fetched until the disclosure is open.
 *
 * ── ENTITLEMENT IS AN ANSWER, NOT A FAILURE ────────────────────────────────────────────────────
 *
 * `MediaFile.url` is withheld server-side for a caller not entitled to those bytes, so a row can
 * arrive complete with no url at all. Such a file is listed, is pickable, and says what it is —
 * {@link repositoryEntitlementNotice} above the panel and a clause on the row's own hint. Refusing it
 * would be this client inventing a rule the server does not have: what is stored is an id, and who
 * may open the bytes is decided every time the row is read.
 *
 * THIS CONTROL IS THE REASON THAT STATE STOPS BEING RARE, which is worth saying plainly. Until now a
 * media field held files the designer had just uploaded themselves, so the url was all but always
 * there. `media_url_scope` answers on the FILE's own uploader and the FILE's own workshop tag — never
 * on the workshop the field being filled belongs to — so a file tagged to a workshop this account
 * cannot open, uploaded by somebody who has granted it nothing, legitimately arrives without one. The
 * pick is still right and the id still resolves for whoever may read it; only this reader's preview
 * is withheld. That is why nothing here or in `MediaField` may clear a stored id over a missing url.
 */

import { useEffect, useId, useMemo, useRef, useState } from "react";
import { FolderSearch } from "lucide-react";

import {
  MEDIA_PICKER_PAGE_SIZE,
  MEDIA_SEARCH_DEBOUNCE_MS,
  acceptRepositoryPicks,
  mediaPickerNoun,
  mediaPickerNotice,
  mediaPickerOptions,
  repositoryEntitlementNotice,
  repositoryRefusalSentence
} from "@/components/media/mediaPicker";
import { SearchInput } from "@/components/SearchInput";
import { Dropdown, MultiSelectDropdown } from "@/components/ui/Dropdown";
import { listResource } from "@/lib/api";
import type { MediaFile, MediaType } from "@/lib/types";

type ListState = {
  rows: MediaFile[];
  /** `PageResult.total` — how many rows matched, which is not how many were sent. */
  total: number;
  loading: boolean;
  problem: string | null;
};

const EMPTY: ListState = { rows: [], total: 0, loading: false, problem: null };

export function MediaRepositoryPicker({
  label,
  typeFilter,
  multiple,
  attachedIds,
  room,
  declaredCap,
  accounted,
  disabled,
  onAttach
}: {
  /** The field's label, used verbatim in every sentence this control writes. */
  label: string;
  /** The one `mediaType` to narrow the list to, or null for "any attachment" — see `mediaPickerTypeFilter`. */
  typeFilter: MediaType | null;
  /** True for a gallery (IMAGE_LIST): several may be attached at once, and a ceiling applies. */
  multiple: boolean;
  /** The ids the field already holds — an already-attached row is drawn and disabled, never hidden. */
  attachedIds: string[];
  /**
   * How many more entries the field may take, counting what is attached AND what is in flight.
   *
   * Never null for a gallery: an undeclared ceiling is the server's default, not the absence of one.
   * Null only on a single-valued field, which has no room to count — it keeps the last file chosen,
   * the same rule the capture card follows for the second file picked out of the chooser.
   */
  room: number | null;
  /** The registry's DECLARED ceiling, or null. Printed; never the enforced one. */
  declaredCap: number | null;
  /** Attached plus in flight — the figure the declared-cap refusal reconciles against. */
  accounted: number;
  disabled?: boolean;
  /** Hand the chosen rows to the field. The caller de-duplicates and writes the value. */
  onAttach: (rows: MediaFile[]) => void;
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [state, setState] = useState<ListState>(EMPTY);
  /** Ticked in the panel and not yet attached. Ids, so a re-fetch under the reader cannot renumber it. */
  const [picked, setPicked] = useState<string[]>([]);
  /** Names the ceiling turned away on the last Attach — held until they fit, like `MediaField.refused`. */
  const [refusedNames, setRefusedNames] = useState<string[]>([]);
  const panelId = useId();
  const noticeId = useId();
  /**
   * Which fetch is the current one.
   *
   * A generation counter and not an `AbortSignal`, because `apiFetch` takes none — the house
   * convention for exactly this case is to count fetches and ignore the late answer. A picker that
   * rendered a stale one would show the results for "kam" under the word "kamla", which is precisely
   * when a designer clicks the first row without reading it.
   */
  const generation = useRef(0);

  useEffect(() => {
    if (!open) return;
    const current = generation.current + 1;
    generation.current = current;
    setState((previous) => ({ ...previous, loading: true, problem: null }));
    const timer = window.setTimeout(
      () => {
        listResource<MediaFile>("/media", {
          search: query.trim() || null,
          mediaType: typeFilter,
          page: 1,
          pageSize: MEDIA_PICKER_PAGE_SIZE
        })
          .then((result) => {
            if (generation.current !== current) return;
            setState({ rows: result.items, total: result.total, loading: false, problem: null });
          })
          .catch((error) => {
            if (generation.current !== current) return;
            // The rows already on screen are NOT thrown away: a failed refresh over a list that
            // loaded is a worse screen than the list plus a sentence, and `ExistingMedia` keeps its
            // items on a later failure for the same reason.
            setState((previous) => ({
              ...previous,
              loading: false,
              problem:
                error instanceof Error
                  ? `The repository could not be searched: ${error.message}`
                  : "The repository could not be searched."
            }));
          });
      },
      // No debounce on the first, empty read — it is one request as the disclosure opens, and making
      // a designer wait 300 ms for a list they can already see the box for buys nothing.
      query ? MEDIA_SEARCH_DEBOUNCE_MS : 0
    );
    return () => window.clearTimeout(timer);
  }, [open, query, typeFilter]);

  /**
   * Forget a tick whose row is no longer in the list.
   *
   * The rows are rewritten by every search, so a selection made against the previous query would sit
   * in `picked` matching nothing — and Attach would then hand back fewer files than the reader ticked,
   * silently. Only ever SHRINKS, and only against rows that have arrived, so a request in flight can
   * never clear a real pick.
   */
  useEffect(() => {
    const live = new Set(state.rows.map((row) => row.id));
    setPicked((current) => {
      if (!current.length) return current;
      const kept = current.filter((id) => live.has(id));
      return kept.length === current.length ? current : kept;
    });
  }, [state.rows]);

  const options = useMemo(
    () => mediaPickerOptions({ rows: state.rows, attachedIds }),
    [state.rows, attachedIds]
  );
  const notice = mediaPickerNotice({
    loading: state.loading,
    problem: state.problem,
    query,
    shown: state.rows.length,
    total: state.total,
    typeFilter
  });
  const entitlement = repositoryEntitlementNotice(state.rows);
  const refusal = repositoryRefusalSentence({ label, declaredCap, accounted, refusedNames });
  const noun = mediaPickerNoun(typeFilter);

  /** Attach one row (single-valued field) or every ticked row the ceiling admits (a gallery). */
  function attach(ids: string[]) {
    const chosen = ids
      .map((id) => state.rows.find((row) => row.id === id))
      .filter((row): row is MediaFile => Boolean(row));
    if (!chosen.length) return;
    if (room === null) {
      // A single-valued field keeps the last file chosen; the control above it is a single-select, so
      // "the last" and "the only" are the same row and there is nothing to turn away.
      setRefusedNames([]);
      onAttach(chosen);
      return;
    }
    const { attach: fitted, refused } = acceptRepositoryPicks({ picked: chosen, room });
    setRefusedNames(refused.map((row) => row.caption?.trim() || row.originalFilename));
    // The refused rows stay ticked so "press Attach again" is an instruction the reader can follow
    // without hunting for them; the ones that landed leave, because they are attached now and the
    // panel draws them disabled from here on.
    const landed = new Set(fitted.map((row) => row.id));
    setPicked((current) => current.filter((id) => !landed.has(id)));
    if (fitted.length) onAttach(fitted);
  }

  if (disabled) return null;

  return (
    <div className="grid gap-2">
      <button
        type="button"
        onClick={() => setOpen((current) => !current)}
        aria-expanded={open}
        // Only while the panel is mounted: pointing `aria-controls` at an id that is not in the
        // document is worse than not pointing at all (§17).
        aria-controls={open ? panelId : undefined}
        className="field-button-secondary justify-start"
      >
        <FolderSearch className="h-4 w-4" aria-hidden />
        {open
          ? "Close the repository list"
          : `Choose ${multiple ? noun.plural : `a ${noun.singular}`} already in the repository`}
      </button>

      {open ? (
        <div id={panelId} className="grid gap-2 rounded-md border border-line-200 bg-card p-3">
          {/* The sentence is built around the noun rather than opening with it, so a narrowed list
              ("photographs") and an unnarrowed one ("files") read as one sentence without this file
              having to capitalise either — and so the claim it makes stays exact. NOTHING MOVES: what
              a media field stores is an id, so attaching an existing row copies no bytes and uploads
              none, which is the whole reason the same photograph need not exist twice. */}
          <p className="text-xs leading-5 text-ink-500">
            {`Anything already uploaded to the repository can be attached here instead of uploading it again. ` +
              `Attaching points ${label.toLowerCase()} at the ${noun.singular} that is already there` +
              `${multiple ? "" : `, and replaces whatever this field held`} — no bytes are uploaded and nothing is copied.`}
          </p>

          {/*
            THE ONE SEARCH BOX, AND IT ASKS THE REPOSITORY — see this file's header.

            The `onInput` firewall is `WorkshopSelect`'s, for its reason: a stage field can be
            rendered inside a mirrored record's own form, and those forms mark themselves dirty from
            `<form onInput>`. This is a REAL text input, so typing to find a photograph would arm that
            page's unsaved-changes prompt over a search that changed no value, and the designer could
            not leave. `SearchInput` already swallows Enter itself, so the record form's Enter-walker
            cannot throw the keyboard at the next field mid-search.
          */}
          <div onInput={(event) => event.stopPropagation()}>
            <SearchInput
              value={query}
              onChange={setQuery}
              placeholder={`Search the repository for a ${noun.singular}`}
            />
          </div>

          <p id={noticeId} className="text-xs leading-5 text-ink-500">
            {notice}
          </p>
          {entitlement ? <p className="text-xs leading-5 text-ink-500">{entitlement}</p> : null}

          {multiple ? (
            <>
              <MultiSelectDropdown
                values={picked}
                onChange={setPicked}
                options={options}
                placeholder={`Pick ${noun.plural} to attach`}
                ariaLabel={`Repository ${noun.plural} to attach to ${label}`}
                describedBy={noticeId}
                emptyLabel={notice}
                // OFF, and the box above is why — see this file's header.
                searchable={false}
                // And therefore this sentence, in place of the default's "keep typing".
                capHint="Use the search box above to reach the rest — it asks the repository rather than this page."
                // The commit is the Attach button below, not a tick and not a Confirm inside the
                // panel: a Confirm that closed the panel without attaching anything would be a
                // second, louder button that does nothing.
                confirmOnSelect={false}
              />
              <div className="flex flex-wrap items-center gap-2">
                <button
                  type="button"
                  className="field-button"
                  disabled={!picked.length}
                  onClick={() => attach(picked)}
                >
                  {picked.length === 0
                    ? `Attach ${noun.plural}`
                    : picked.length === 1
                      ? `Attach 1 ${noun.singular}`
                      : `Attach ${picked.length} ${noun.plural}`}
                </button>
                {picked.length ? (
                  <button
                    type="button"
                    className="text-xs font-medium text-ink-500 underline"
                    onClick={() => setPicked([])}
                  >
                    Clear the selection
                  </button>
                ) : null}
              </div>
            </>
          ) : (
            <Dropdown
              // An ADDER, not a value holder: the field's own tile list above is where the answer is
              // shown, so this stays empty and every pick is a fresh act. Bound to the value instead,
              // it would draw the filename twice and disagree with the tile the moment the row was
              // removed.
              value=""
              onChange={(id) => attach([id])}
              options={options}
              placeholder={`Pick a ${noun.singular} to attach`}
              ariaLabel={`Repository ${noun.plural} to attach to ${label}`}
              describedBy={noticeId}
              emptyLabel={notice}
              searchable={false}
              capHint="Use the search box above to reach the rest — it asks the repository rather than this page."
              // A picker inside a media box does not fill in a form field the reader is walking
              // through top to bottom, so throwing focus at whatever follows is wrong here.
              advanceOnSelect={false}
            />
          )}

        </div>
      ) : null}

      {/*
        OUTSIDE THE DISCLOSURE, AND THAT IS THE WHOLE REASON IT IS PLACED HERE.

        Assistive technology announces mutations only inside a region that ALREADY EXISTED when the
        page settled, so a region that comes into being in the same breath as its first sentence is a
        sentence a listening reader never hears — `MediaField`'s three regions, `SubmissionCard` and
        `Toast`'s permanently-present viewport are all this fix. Inside the `{open}` branch this
        paragraph would be mounted by the very act that leads to its first message, which is the
        defect wearing a disclosure.

        `sr-only` AND NOT `hidden`, as a class swap on one element: empty, it is absolutely positioned
        and 1x1, so it stays in the accessibility tree and contributes no row to this `grid gap-2`.
        `display: none` — unmounting it, `hidden`, `empty:hidden` — puts the defect straight back.

        AND IT SURVIVES THE PANEL BEING CLOSED, deliberately. The ticks are held in state, so closing
        the disclosure hides the list and forgets nothing; the files named here are still not attached
        and the sentence is still what the reader has to act on. `MediaField.refused` keeps its own
        list on the same reasoning — an entry leaves only when it stops being true.
      */}
      <p role="status" aria-live="polite" className={refusal ? "text-xs leading-5 text-amber-800" : "sr-only"}>
        {refusal}
      </p>
    </div>
  );
}
