"use client";

import { DraftingCompass, Save } from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";

import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown, MultiSelectDropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { useToast } from "@/components/ui/Toast";
import { ApiError, listResource } from "@/lib/api";
import { listDesignWorkshops, type DwSummary } from "@/lib/designWorkshops";
import {
  designWorkshopType,
  listDesignWorkshopViewers,
  listEligibleDesignWorkshopViewers,
  putDesignWorkshopViewers,
  viewerAdministrationMissing,
  type DwEligibleViewer,
  type DwViewer
} from "@/lib/designWorkshopViewers";
import { formatDate, formatDateTime } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import { roleLabel } from "@/lib/permissions";
import { WORKSHOP_TYPE_LABELS, type Workshop, type WorkshopType } from "@/lib/types";

/**
 * Who may open one design & prototype workshop, administered beside the workshop-access console.
 *
 * THE FAILURE THIS PREVENTS is spelled out in full at the top of `lib/designWorkshopViewers.ts` and
 * is worth the one-line summary here: `load_workshop_or_404` 404s a design workshop for everybody
 * but the account that pressed "create", so the second designer on a fortnight of fieldwork cannot
 * open the record at all and there is no handover when a designer leaves mid-season.
 *
 * It sits on /workshop-access/manage rather than on its own route deliberately. That page is
 * already the one place an admin goes to answer "who may work on what", it is already in
 * ROUTE_REDIRECTS (below-admin is sent to the request page) and in ADMIN_CHROME_ROUTES (an admin
 * with admin view off gets the panel, not a redirect), and both of those gates are the ones this
 * panel needs. A second route would have been a second copy of them to keep in step.
 *
 * FOUR DECISIONS THAT LOOK LIKE DETAIL AND ARE NOT.
 *
 * - **The creator is shown OUTSIDE the picker.** They hold the workshop because they made it, not
 *   because of a row in this list, and the PUT cannot take that away. Putting them in the
 *   multi-select as a ticked-but-disabled option was the first draft and it was wrong: it invites
 *   an admin to try to untick the one person whose access is not on offer here, and a control that
 *   refuses a click without saying why is how somebody concludes the page is broken.
 * - **A current viewer who is no longer ELIGIBLE is still offered, ticked and marked.** The PUT
 *   replaces the whole set, so anybody missing from the picker is silently revoked by the next
 *   Save. A designer whose roster row was suspended last month is exactly that case, and dropping
 *   them from the options would have quietly removed their row the first time an admin added
 *   somebody else.
 * - **Saving is explicit.** Every toggle writing straight through would mean an admin re-picking a
 *   mis-clicked name has already revoked it for however long the round trip takes, and on a rural
 *   link that is seconds. So the picker edits a PENDING set, the panel says what is unsaved, and
 *   one button sends it.
 * - **The type dropdown NARROWS, it does not set.** See {@link TYPE_OPTIONS}.
 */

/**
 * The workshop-type filter, built from `WORKSHOP_TYPE_LABELS` rather than from strings typed here.
 *
 * THIS DROPDOWN FILTERS; IT DOES NOT SET. A `DesignWorkshop` has no type column of its own — the
 * distinction lives on the `Workshop` row it was started from — so there would be nothing for a
 * "set the type" control on this panel to write, and writing it through to the linked Workshop
 * would be an admin retyping a craft-documentation record's kind from a screen about access.
 *
 * The empty option is first and means EVERYTHING, by absence: the same rule `buildQuery`,
 * `filters.types` and the workshop-scope picker follow, and the reason none of them list every
 * member to mean "all". Listing both types instead would silently exclude any type added to
 * `WorkshopType` after this was written.
 */
const TYPE_OPTIONS: DropdownOption[] = [
  { value: "", label: "Any workshop type" },
  ...(Object.keys(WORKSHOP_TYPE_LABELS) as WorkshopType[]).map((key) => ({
    value: key,
    label: WORKSHOP_TYPE_LABELS[key]
  }))
];

/**
 * How many rows each list asks for.
 *
 * Both are a page, not "everything", and both are checked against the reported total so the panel
 * can say when it is showing less than there is — a selector that quietly stops at a hundred is
 * indistinguishable from a repository with a hundred workshops in it.
 */
const DESIGN_WORKSHOP_PAGE = 100;
const WORKSHOP_PAGE = 200;

/** Title plus the day it ran, never an id — the same shape `workshopLabel` gives ordinary workshops. */
function designWorkshopLabel(summary: DwSummary): string {
  const title = summary.title?.trim() || "Untitled design workshop";
  const when = formatDate(summary.startDate ?? summary.createdAt ?? null);
  return when === "-" ? title : `${title} · ${when}`;
}

/** Name a person without ever leaking an id: their name, else their email, else a neutral fallback. */
function personLabel(person: { name?: string | null; email?: string | null } | null | undefined): string {
  return person?.name?.trim() || person?.email?.trim() || "Unknown user";
}

/**
 * The four failures this panel can suffer, told apart in words.
 *
 * `isUnreachable` and not `isTransient`: the latter answers "is it worth retrying" and counts every
 * 5xx as yes, so a repository that ANSWERED and then failed would be reported as a connection
 * problem — which sends an admin to look at their signal and leaves a real fault wearing an offline
 * message. A server that spoke gets its own sentence shown, because `apiFetch` has already unpacked
 * FastAPI's 422 list into a readable one that names the offending field.
 */
function describeFailure(error: unknown, fallback: string): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) {
    return "This device cannot reach the repository, so nothing was sent and nothing has changed. Check the connection and try again.";
  }
  if (error.status === 403) {
    return `The repository refused this. ${error.message} Deciding who may see a design workshop is administration, so it is open to admins and the master admin only.`;
  }
  if (error.status === 422) {
    return `The repository would not accept this. ${error.message}`;
  }
  if (error.status === 404) {
    return `${error.message} This workshop may have been deleted since the list was loaded — reload the page to see the current list.`;
  }
  return error.message || fallback;
}

export function DesignWorkshopViewersPanel({ refreshToken }: { refreshToken?: number }) {
  const { toast } = useToast();

  const [summaries, setSummaries] = useState<DwSummary[] | null>(null);
  const [summariesTotal, setSummariesTotal] = useState(0);
  const [typeByWorkshopId, setTypeByWorkshopId] = useState<Map<string, WorkshopType>>(new Map());
  const [typeMapPartial, setTypeMapPartial] = useState(false);

  const [typeFilter, setTypeFilter] = useState("");
  const [workshopId, setWorkshopId] = useState("");

  const [eligible, setEligible] = useState<DwEligibleViewer[] | null>(null);
  /**
   * The routes are being built in parallel with this screen and ship separately, so "the server has
   * not got this yet" is a state the panel has to render rather than a case to assume away. See
   * `viewerAdministrationMissing` for why the id-less endpoint is the honest probe.
   */
  const [featureMissing, setFeatureMissing] = useState(false);

  const [viewers, setViewers] = useState<DwViewer[] | null>(null);
  /** The saved membership, creator excluded — what `selected` is compared against to decide dirty. */
  const [baseline, setBaseline] = useState<string[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  /**
   * Did the server itself report the creator as holding a viewer ROW?
   *
   * If it did, that id has to be re-attached to the PUT: the endpoint replaces the whole set, so a
   * payload built only from the picker would delete a row the server told us about — the one row
   * this panel is least entitled to touch.
   */
  const [creatorHasRow, setCreatorHasRow] = useState(false);

  const [loadError, setLoadError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  /* ── The two lists the selector is built from ───────────────────────────── */

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const result = await listDesignWorkshops({ page: 1, pageSize: DESIGN_WORKSHOP_PAGE });
        if (cancelled) return;
        setSummaries(result.items);
        setSummariesTotal(result.total);
      } catch (err) {
        if (cancelled) return;
        setSummaries([]);
        setLoadError(describeFailure(err, "Unable to load the design workshops"));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [refreshToken]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        // Every workshop, not one type at a time: the map has to answer "which type is this one" for
        // both filter values, and two requests would leave the second answer stale whenever an admin
        // switched between them.
        const result = await listResource<Workshop>("/workshops", { pageSize: WORKSHOP_PAGE });
        if (cancelled) return;
        const map = new Map<string, WorkshopType>();
        for (const workshop of result.items) map.set(workshop.id, workshop.workshopType ?? "OTHER");
        setTypeByWorkshopId(map);
        setTypeMapPartial(result.total > result.items.length);
      } catch {
        if (cancelled) return;
        // Deliberately silent, and deliberately NOT an empty map presented as an answer: with no map
        // the type filter cannot honestly narrow anything, so it disables itself below rather than
        // filtering every workshop away and calling that a result.
        setTypeByWorkshopId(new Map());
        setTypeMapPartial(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [refreshToken]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const result = await listEligibleDesignWorkshopViewers();
        if (cancelled) return;
        setEligible(result.users ?? []);
        setFeatureMissing(false);
      } catch (err) {
        if (cancelled) return;
        if (viewerAdministrationMissing(err)) {
          setFeatureMissing(true);
          setEligible([]);
          return;
        }
        setEligible([]);
        setLoadError(describeFailure(err, "Unable to load the designers who may be given access"));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [refreshToken]);

  /* ── The chosen workshop's current viewers ──────────────────────────────── */

  const selectedWorkshop = useMemo(
    () => (summaries ?? []).find((summary) => summary.id === workshopId) ?? null,
    [summaries, workshopId]
  );
  const creatorId = selectedWorkshop?.createdById ?? "";

  const loadViewers = useCallback(async () => {
    if (!workshopId) {
      setViewers(null);
      setBaseline([]);
      setSelected([]);
      setCreatorHasRow(false);
      return;
    }
    try {
      const result = await listDesignWorkshopViewers(workshopId);
      const rows = result.viewers ?? [];
      setViewers(rows);
      setCreatorHasRow(rows.some((row) => row.userId === creatorId));
      // The creator is held out of the editable set on both sides, so their presence in the server's
      // answer can never read as an unsaved change — see `creatorHasRow`.
      const editable = rows.map((row) => row.userId).filter((id) => id !== creatorId);
      setBaseline(editable);
      setSelected(editable);
      setSaveError(null);
    } catch (err) {
      setViewers([]);
      setBaseline([]);
      setSelected([]);
      setCreatorHasRow(false);
      setLoadError(describeFailure(err, "Unable to load who can see this workshop"));
    }
  }, [workshopId, creatorId]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoadError(null);
      await loadViewers();
      if (cancelled) return;
    })();
    return () => {
      cancelled = true;
    };
  }, [loadViewers]);

  /* ── The type filter ────────────────────────────────────────────────────── */

  const typeKnown = typeByWorkshopId.size > 0;

  /**
   * The workshops the selector offers, and — separately — how many the filter had to leave out.
   *
   * The count is not decoration. A design workshop is created with a title and nothing else, so a
   * great many carry no linked `Workshop` row and therefore no type at all; a filter that dropped
   * them without saying so would show an empty selector over a full repository, which is
   * indistinguishable from having no workshops and is this codebase's most repeated bug class.
   */
  const { offered, untyped } = useMemo(() => {
    const all = summaries ?? [];
    if (!typeFilter) return { offered: all, untyped: 0 };
    const matching: DwSummary[] = [];
    let withoutType = 0;
    for (const summary of all) {
      const type = designWorkshopType(summary, typeByWorkshopId);
      if (type === null) withoutType += 1;
      else if (type === typeFilter) matching.push(summary);
    }
    return { offered: matching, untyped: withoutType };
  }, [summaries, typeFilter, typeByWorkshopId]);

  // A workshop the filter no longer offers must not stay selected: the roster below it would be
  // administering a record that is not on screen anywhere.
  useEffect(() => {
    if (workshopId && !offered.some((summary) => summary.id === workshopId)) setWorkshopId("");
  }, [offered, workshopId]);

  const workshopOptions = useMemo(
    () => offered.map((summary) => ({ value: summary.id, label: designWorkshopLabel(summary) })),
    [offered]
  );

  /* ── The picker's options ───────────────────────────────────────────────── */

  const creator = useMemo(() => {
    if (!creatorId) return null;
    const fromViewers = (viewers ?? []).find((row) => row.userId === creatorId);
    if (fromViewers) return { name: personLabel(fromViewers), role: fromViewers.role };
    const fromEligible = (eligible ?? []).find((row) => row.id === creatorId);
    if (fromEligible) return { name: personLabel(fromEligible), role: fromEligible.role };
    // Never the raw cuid. A list of twenty-five random characters asks an admin to recognise
    // somebody they cannot possibly recognise — the same defect the artisan pickers shipped once.
    return { name: "The designer who created it", role: "" };
  }, [creatorId, viewers, eligible]);

  /**
   * Everyone the picker offers: the eligible accounts, plus anybody who already HOLDS a row and has
   * since dropped off that list. The creator is in neither — their access is not on offer here.
   *
   * The second group is the load-bearing one. The PUT replaces the whole set, so an option that is
   * not rendered is a row the next Save silently deletes; a designer suspended on the roster last
   * month is exactly that person, and leaving them out would revoke them as a side effect of adding
   * somebody unrelated.
   */
  const viewerOptions = useMemo(() => {
    const options: DropdownOption[] = [];
    const seen = new Set<string>();
    for (const person of eligible ?? []) {
      if (person.id === creatorId) continue;
      seen.add(person.id);
      options.push({ value: person.id, label: `${personLabel(person)} · ${roleLabel(person.role)}` });
    }
    for (const row of viewers ?? []) {
      if (row.userId === creatorId || seen.has(row.userId)) continue;
      seen.add(row.userId);
      options.push({
        value: row.userId,
        label: `${personLabel(row)} · ${roleLabel(row.role)} — has access, no longer eligible`
      });
    }
    return options;
  }, [eligible, viewers, creatorId]);

  /* ── Unsaved state ──────────────────────────────────────────────────────── */

  const dirty = useMemo(() => {
    if (selected.length !== baseline.length) return true;
    const held = new Set(baseline);
    return selected.some((id) => !held.has(id));
  }, [selected, baseline]);

  const labelById = useMemo(() => new Map(viewerOptions.map((option) => [option.value, option.label])), [viewerOptions]);
  const added = useMemo(() => selected.filter((id) => !baseline.includes(id)), [selected, baseline]);
  const removed = useMemo(() => baseline.filter((id) => !selected.includes(id)), [baseline, selected]);

  /**
   * The sentence the live region carries — the RESULTING count, plus what is still unsent.
   *
   * The multi-select has an `aria-live` of its own, but it lives inside a portalled panel that
   * unmounts the moment the picker closes, so a reader who ticks three names and closes the panel
   * would hear nothing about where the workshop now stands. This region is rendered from the first
   * paint, empty or not, because assistive technology only announces mutations inside a region that
   * already existed.
   */
  const announcement = !workshopId
    ? ""
    : dirty
      ? `${selected.length} designer${selected.length === 1 ? "" : "s"} selected, not yet saved. ${added.length} to add, ${removed.length} to remove.`
      : `${selected.length} designer${selected.length === 1 ? "" : "s"} can see this workshop, in addition to the designer who created it. Nothing unsaved.`;

  async function save() {
    if (!workshopId) return;
    setSaving(true);
    setSaveError(null);
    try {
      // The creator's own row is re-attached rather than re-derived: this replaces the whole set,
      // and dropping a row the server itself reported is the one edit this panel may not make.
      const payload = creatorHasRow && creatorId ? [...selected, creatorId] : selected;
      const result = await putDesignWorkshopViewers(workshopId, payload);
      const rows = result.viewers ?? [];
      setViewers(rows);
      setCreatorHasRow(rows.some((row) => row.userId === creatorId));
      // The ANSWER becomes the baseline, not what was sent: another admin may have added somebody
      // between this page loading and Save being pressed, and treating our own payload as the truth
      // would leave the screen showing a membership nobody has.
      const editable = rows.map((row) => row.userId).filter((id) => id !== creatorId);
      setBaseline(editable);
      setSelected(editable);
      toast({
        title: editable.length ? `${editable.length} designer${editable.length === 1 ? "" : "s"} can see this workshop` : "Nobody else can see this workshop",
        description: "The designer who created it always can, and is not affected by this list.",
        tone: "success"
      });
    } catch (err) {
      setSaveError(describeFailure(err, "Unable to save who can see this workshop"));
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="panel p-5" data-design-workshop-viewers>
      <div className="flex items-center gap-2.5">
        <span className="grid h-8 w-8 place-items-center rounded-md bg-purple-950 text-purple-100">
          <DraftingCompass className="h-4 w-4" aria-hidden />
        </span>
        <div>
          <h2 className="font-display font-bold text-ink-900">Design workshop visibility</h2>
          <p className="text-xs leading-5 text-ink-500">
            Who may open one design &amp; prototype workshop, besides the designer who created it.
          </p>
        </div>
      </div>

      <p className="mt-3 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs leading-5 text-ink-500">
        A design workshop is visible only to the designer who started it. A workshop is usually run by two designers
        alongside a master craftsperson and a reviewing officer, and a colleague who opens it today is told the record
        does not exist — so name the designers who should see this one.
      </p>

      {featureMissing ? (
        <p className="mt-3 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-5 text-amber-800">
          This repository does not offer design workshop visibility yet. The controls below are hidden rather than shown
          doing nothing — nobody has been granted or removed, and an admin can still open any workshop themselves.
        </p>
      ) : null}

      {loadError ? (
        <div className="mt-3 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">{loadError}</div>
      ) : null}

      {featureMissing ? null : (
        <>
          <div className="mt-4 grid gap-3 md:grid-cols-[minmax(0,0.9fr)_minmax(0,1.4fr)]">
            <FieldBlock label="Workshop type">
              <Dropdown
                // Filters the screen it sits on, so focus stays on the control being adjusted rather
                // than jumping to the next field.
                advanceOnSelect={false}
                ariaLabel="Workshop type"
                disabled={!typeKnown}
                onChange={setTypeFilter}
                options={TYPE_OPTIONS}
                value={typeFilter}
              />
            </FieldBlock>
            <FieldBlock label="Design workshop">
              <Dropdown
                advanceOnSelect={false}
                ariaLabel="Design workshop"
                onChange={setWorkshopId}
                options={workshopOptions}
                placeholder={
                  summaries === null
                    ? "Loading design workshops…"
                    : workshopOptions.length
                      ? "Select or type to search"
                      : "No design workshops of this type"
                }
                searchable
                value={workshopId}
              />
            </FieldBlock>
          </div>

          {/* Every row the filter left out is counted and explained. A selector that quietly stops is
              indistinguishable from a repository with nothing in it. */}
          {typeFilter && untyped > 0 ? (
            <p className="mt-2 text-xs leading-5 text-ink-500">
              {untyped} design workshop{untyped === 1 ? " is" : "s are"} not linked to a workshop record, so
              {untyped === 1 ? " it carries" : " they carry"} no type and {untyped === 1 ? "is" : "are"} not offered
              under this filter. Choose “Any workshop type” to administer {untyped === 1 ? "it" : "them"}.
            </p>
          ) : null}
          {!typeKnown ? (
            <p className="mt-2 text-xs leading-5 text-ink-500">
              The workshop list could not be read, so nothing is known about workshop types and the filter is turned
              off. Every design workshop is offered.
            </p>
          ) : null}
          {typeKnown && typeMapPartial ? (
            <p className="mt-2 text-xs leading-5 text-ink-500">
              Only the first {WORKSHOP_PAGE} workshops were read, so a design workshop linked to an older one may show
              no type and be left out of a filtered list.
            </p>
          ) : null}
          {summaries !== null && summariesTotal > summaries.length ? (
            <p className="mt-2 text-xs leading-5 text-ink-500">
              Showing the {summaries.length} most recent design workshops of {summariesTotal}.
            </p>
          ) : null}

          {!workshopId ? (
            <p className="mt-4 text-sm text-ink-500">Pick a design workshop to see who can open it.</p>
          ) : viewers === null ? (
            <p className="mt-4 text-sm text-ink-500">Loading…</p>
          ) : (
            <>
              {/* The creator sits OUTSIDE the picker, because their access is not this list's to
                  give or take. See the file header. */}
              <div className="mt-4 rounded-md border border-purple-300 bg-purple-50 px-3 py-2">
                <p className="text-xs font-semibold uppercase tracking-wide text-purple-700">Always has access</p>
                <p className="mt-1 text-sm text-ink-900">
                  {creator ? creator.name : "The designer who created it"}
                  {creator?.role ? <span className="text-ink-500"> · {roleLabel(creator.role)}</span> : null}
                </p>
                <p className="mt-1 text-xs leading-5 text-ink-500">
                  They created this workshop, so they can always open it. Nothing below removes that.
                </p>
              </div>

              <div className="mt-4">
                <FieldBlock
                  label="Designers who may see this workshop"
                  hint={
                    <p className="text-xs leading-5 text-ink-500">
                      Only accounts that could actually run a design workshop are offered — a designer whose roster row
                      is suspended would be refused at the door. Unticking somebody removes their access when you save.
                    </p>
                  }
                >
                  <MultiSelectDropdown
                    ariaLabel="Designers who may see this workshop"
                    confirmLabel="Done"
                    emptyLabel="No designers are eligible"
                    onChange={setSelected}
                    options={viewerOptions}
                    placeholder="Select one or more designers"
                    values={selected}
                  />
                </FieldBlock>
              </div>

              {/* What is currently SAVED, spelled out — the picker's trigger says "3 selected", which
                  is the pending answer and not the state of the repository. */}
              <div className="mt-3">
                {viewers.filter((row) => row.userId !== creatorId).length === 0 ? (
                  <p className="text-sm text-ink-500">
                    Nobody else can open this workshop yet. Only the designer who created it, and admins, can.
                  </p>
                ) : (
                  <ul className="grid gap-1.5">
                    {viewers
                      .filter((row) => row.userId !== creatorId)
                      .map((row) => (
                        <li className="flex flex-wrap items-baseline gap-x-2 text-sm text-ink-700" key={row.userId}>
                          <span className="font-medium text-ink-900">{personLabel(row)}</span>
                          <span className="text-xs text-ink-500">
                            {row.email}
                            {row.role ? ` · ${roleLabel(row.role)}` : ""}
                            {row.grantedAt ? ` · added ${formatDateTime(row.grantedAt)}` : ""}
                          </span>
                          {removed.includes(row.userId) ? (
                            <span className="rounded-full border border-error-600/25 bg-error-100 px-2 py-0.5 text-[0.6875rem] font-medium text-error-600">
                              will be removed on save
                            </span>
                          ) : null}
                        </li>
                      ))}
                  </ul>
                )}
                {added.length ? (
                  <p className="mt-2 text-xs leading-5 text-ink-500">
                    Will be added on save: {added.map((id) => labelById.get(id) ?? "Unknown user").join(", ")}.
                  </p>
                ) : null}
              </div>

              {/* Immediately above the buttons, not pinned to the top of a panel this tall: a refusal
                  rendered five sections away from the control that caused it reads as a button that
                  did nothing. */}
              {saveError ? (
                <div className="mt-3 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">
                  {saveError}
                </div>
              ) : null}

              <div className="mt-3 flex flex-wrap items-center gap-2">
                <button className="field-button" disabled={!dirty || saving} onClick={save} type="button">
                  <Save className="h-4 w-4" aria-hidden />
                  {saving ? "Saving…" : "Save who can see this"}
                </button>
                {dirty ? (
                  <button
                    className="field-button-secondary"
                    disabled={saving}
                    onClick={() => {
                      setSelected(baseline);
                      setSaveError(null);
                    }}
                    type="button"
                  >
                    Discard changes
                  </button>
                ) : null}
                {/* Colour never carries the meaning on its own — the word "unsaved" does. */}
                {dirty ? <span className="text-xs text-amber-800">{added.length + removed.length} unsaved change{added.length + removed.length === 1 ? "" : "s"}</span> : null}
              </div>
            </>
          )}
        </>
      )}

      <p className="sr-only" role="status" aria-live="polite">
        {announcement}
      </p>
    </section>
  );
}
