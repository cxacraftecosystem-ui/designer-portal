"use client";

import { DraftingCompass, Save } from "lucide-react";
import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { HeldPostNotice, useHeldPostRefusal, writesHeld } from "@/components/designworkshop/HeldPostNotice";
import { SearchInput } from "@/components/SearchInput";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown, MultiSelectDropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { useToast } from "@/components/ui/Toast";
import { listResource } from "@/lib/api";
import { listDesignWorkshops, type DwSummary } from "@/lib/designWorkshops";
import {
  absenceProvesIneligible,
  designWorkshopType,
  eligibleViewerNotice,
  ELIGIBLE_VIEWER_SEARCH_MAX,
  listDesignWorkshopViewers,
  listEligibleDesignWorkshopViewers,
  putDesignWorkshopViewers,
  viewerAdministrationFailure,
  viewerAdministrationMissing,
  type DwEligibleViewer,
  type DwViewer
} from "@/lib/designWorkshopViewers";
import { formatDateTime } from "@/lib/format";
import { isAdmin, roleLabel } from "@/lib/permissions";
import { WORKSHOP_TYPE_LABELS, type Workshop, type WorkshopType } from "@/lib/types";
import {
  designWorkshopOptions,
  deviceLooksOffline,
  WORKSHOP_OPTION_PAGE_SIZE,
  workshopCutSentence,
  workshopEmptyLabel,
  workshopListNotice,
  type WorkshopListState,
  type WorkshopListVoice
} from "@/lib/workshopOptions";
import { ActionGroup } from "@/components/ui/ActionGroup";

const TYPE_OPTIONS: DropdownOption[] = [
  { value: "", label: "Any workshop type" },
  ...(Object.keys(WORKSHOP_TYPE_LABELS) as WorkshopType[]).map((key) => ({
    value: key,
    label: WORKSHOP_TYPE_LABELS[key]
  }))
];

const DESIGN_WORKSHOP_PAGE = WORKSHOP_OPTION_PAGE_SIZE;
const WORKSHOP_PAGE = 100;
const WORKSHOP_TYPE_MAP_MAX_PAGES = 50;

const SEARCH_DEBOUNCE_MS = 300;

function personLabel(person: { name?: string | null; email?: string | null } | null | undefined): string {
  return person?.name?.trim() || person?.email?.trim() || "Unknown user";
}

const describeFailure = viewerAdministrationFailure;

export function DesignWorkshopViewersPanel({ refreshToken }: { refreshToken?: number }) {
  const { toast } = useToast();
  const { user } = useAuth();
  const readerId = user?.id ?? null;
  const readerIsAdmin = isAdmin(user);

  const [workshops, setWorkshops] = useState<WorkshopListState<DwSummary>>({ kind: "loading" });
  const [workshopsOnline, setWorkshopsOnline] = useState(true);
  const [workshopSearch, setWorkshopSearch] = useState("");
  const [workshopsSearching, setWorkshopsSearching] = useState(true);
  const [knownWorkshops, setKnownWorkshops] = useState<Map<string, DwSummary>>(() => new Map());
  const [typeByWorkshopId, setTypeByWorkshopId] = useState<Map<string, WorkshopType>>(new Map());
  const [typeMapPartial, setTypeMapPartial] = useState(false);

  const [typeFilter, setTypeFilter] = useState("");
  const [workshopId, setWorkshopId] = useState("");

  const [search, setSearch] = useState("");
  const [eligible, setEligible] = useState<DwEligibleViewer[] | null>(null);
  const [eligibleTruncated, setEligibleTruncated] = useState(false);
  const [searching, setSearching] = useState(false);
  const [known, setKnown] = useState<Map<string, DwEligibleViewer>>(() => new Map());
  const [featureMissing, setFeatureMissing] = useState(false);

  const [viewers, setViewers] = useState<DwViewer[] | null>(null);
  const [baseline, setBaseline] = useState<string[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [creatorHasRow, setCreatorHasRow] = useState(false);

  const [loadError, setLoadError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const workshopGeneration = useRef(0);

  useEffect(() => {
    const term = workshopSearch.trim();
    const current = workshopGeneration.current + 1;
    workshopGeneration.current = current;
    setWorkshopsSearching(true);
    const timer = window.setTimeout(
      () => {
        listDesignWorkshops({ page: 1, pageSize: DESIGN_WORKSHOP_PAGE, search: term || undefined })
          .then((result) => {
            if (workshopGeneration.current !== current) return;
            setWorkshops({ kind: "ok", rows: result.items, total: result.total });
            setKnownWorkshops((previous) => {
              const next = new Map(previous);
              for (const summary of result.items) next.set(summary.id, summary);
              return next;
            });
            setWorkshopsSearching(false);
          })
          .catch(() => {
            if (workshopGeneration.current !== current) return;
            setWorkshopsSearching(false);
            setWorkshopsOnline(!deviceLooksOffline());
            setWorkshops({ kind: "failed" });
          });
      },
      term ? SEARCH_DEBOUNCE_MS : 0
    );
    return () => window.clearTimeout(timer);
  }, [refreshToken, workshopSearch]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const map = new Map<string, WorkshopType>();
        let total = 0;
        for (let page = 1; page <= WORKSHOP_TYPE_MAP_MAX_PAGES; page += 1) {
          const result = await listResource<Workshop>("/workshops", { page, pageSize: WORKSHOP_PAGE });
          if (cancelled) return;
          total = result.total;
          for (const workshop of result.items) map.set(workshop.id, workshop.workshopType ?? "OTHER");
          if (result.items.length < WORKSHOP_PAGE || map.size >= total) break;
        }
        setTypeByWorkshopId(map);
        setTypeMapPartial(total > map.size);
      } catch {
        if (cancelled) return;
        setTypeByWorkshopId(new Map());
        setTypeMapPartial(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [refreshToken]);

  const eligibleGeneration = useRef(0);

  useEffect(() => {
    const term = search.trim();
    const current = eligibleGeneration.current + 1;
    eligibleGeneration.current = current;
    setSearching(true);
    const timer = window.setTimeout(
      () => {
        listEligibleDesignWorkshopViewers(term)
          .then((result) => {
            if (eligibleGeneration.current !== current) return;
            const users = result.users ?? [];
            setEligible(users);
            setEligibleTruncated(Boolean(result.truncated));
            setKnown((previous) => {
              const next = new Map(previous);
              for (const person of users) next.set(person.id, person);
              return next;
            });
            setFeatureMissing(false);
            setSearching(false);
          })
          .catch((err) => {
            if (eligibleGeneration.current !== current) return;
            setSearching(false);
            setEligibleTruncated(false);
            if (viewerAdministrationMissing(err)) {
              setFeatureMissing(true);
              setEligible([]);
              return;
            }
            setEligible([]);
            setLoadError(describeFailure(err, "Couldn't load designers", readerIsAdmin));
          });
      },
      term ? SEARCH_DEBOUNCE_MS : 0
    );
    return () => window.clearTimeout(timer);
  }, [refreshToken, search, readerIsAdmin]);

  const selectedWorkshop = useMemo(
    () => knownWorkshops.get(workshopId) ?? null,
    [knownWorkshops, workshopId]
  );
  const creatorId = selectedWorkshop?.createdById ?? "";
  const postRefusal = useHeldPostRefusal(workshopId || null);
  const heldNoticeId = useId();

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
      const editable = rows.map((row) => row.userId).filter((id) => id !== creatorId);
      setBaseline(editable);
      setSelected(editable);
      setSaveError(null);
    } catch (err) {
      setViewers([]);
      setBaseline([]);
      setSelected([]);
      setCreatorHasRow(false);
      setLoadError(describeFailure(err, "Unable to load who can see this workshop", readerIsAdmin));
    }
  }, [workshopId, creatorId, readerIsAdmin]);

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

  const typeKnown = typeByWorkshopId.size > 0;
  const workshopSearchTerm = workshopSearch.trim();

  const typeNarrowingSuspended = workshopSearchTerm !== "";

  const { offered, untyped } = useMemo(() => {
    const all: readonly DwSummary[] = workshops.kind === "ok" ? workshops.rows : [];
    if (!typeFilter || typeNarrowingSuspended) return { offered: all, untyped: 0 };
    const matching: DwSummary[] = [];
    let withoutType = 0;
    for (const summary of all) {
      const type = designWorkshopType(summary, typeByWorkshopId);
      if (type === null) withoutType += 1;
      else if (type === typeFilter) matching.push(summary);
    }
    return { offered: matching, untyped: withoutType };
  }, [workshops, typeFilter, typeByWorkshopId, typeNarrowingSuspended]);

  useEffect(() => {
    if (!workshopId || !typeFilter || typeNarrowingSuspended) return;
    const type = selectedWorkshop ? designWorkshopType(selectedWorkshop, typeByWorkshopId) : null;
    if (type !== typeFilter) setWorkshopId("");
  }, [workshopId, typeFilter, typeNarrowingSuspended, selectedWorkshop, typeByWorkshopId]);

  const workshopSet = useMemo(
    () => designWorkshopOptions(workshops.kind === "ok" ? { ...workshops, rows: offered } : workshops, {
      group: true,
      offPage: { mode: "recover", row: selectedWorkshop }
    }),
    [workshops, offered, selectedWorkshop]
  );

  const workshopVoice = useMemo<WorkshopListVoice>(
    () => ({ table: "design", scoped: true, online: workshopsOnline }),
    [workshopsOnline]
  );

  const filteredAway = workshops.kind === "ok" && workshops.rows.length > 0 && offered.length === 0;

  const workshopCut = workshopCutSentence(workshopSet, {
    term: workshopSearch,
    searchable: true
  });

  const workshopNotice =
    filteredAway || (workshops.kind === "ok" && workshopSearchTerm)
      ? ""
      : workshopListNotice(workshops, workshopVoice);

  const creator = useMemo(() => {
    if (!creatorId) return null;
    const fromViewers = (viewers ?? []).find((row) => row.userId === creatorId);
    if (fromViewers) return { name: personLabel(fromViewers), role: fromViewers.role };
    const remembered = known.get(creatorId);
    if (remembered) return { name: personLabel(remembered), role: remembered.role };
    return { name: "The designer who created it", role: "" };
  }, [creatorId, viewers, known]);

  const searchTerm = search.trim();

  const eligibleListIsComplete = !eligibleTruncated && !searchTerm;

  const offerable = useMemo(
    () => (eligible ?? []).filter((person) => person.id !== readerId),
    [eligible, readerId]
  );

  const viewerOptions = useMemo(() => {
    const options: DropdownOption[] = [];
    const seen = new Set<string>();
    const offer = (id: string, label: string) => {
      if (!id || id === creatorId || seen.has(id)) return;
      seen.add(id);
      options.push({ value: id, label });
    };
    for (const person of offerable) {
      offer(person.id, `${personLabel(person)} · ${roleLabel(person.role)}`);
    }
    for (const row of viewers ?? []) {
      offer(
        row.userId,
        `${personLabel(row)} · ${roleLabel(row.role)}${
          absenceProvesIneligible(eligibleListIsComplete, row.userId, readerId)
            ? " — has access, no longer eligible"
            : " — has access"
        }`
      );
    }
    for (const id of selected) {
      const person = known.get(id);
      if (person) offer(person.id, `${personLabel(person)} · ${roleLabel(person.role)}`);
    }
    return options;
  }, [offerable, viewers, selected, known, creatorId, eligibleListIsComplete, readerId]);

  const dirty = useMemo(() => {
    if (selected.length !== baseline.length) return true;
    const held = new Set(baseline);
    return selected.some((id) => !held.has(id));
  }, [selected, baseline]);

  const searchNotice = searching && searchTerm
    ? "Searching…"
    : eligibleViewerNotice({
        truncated: eligibleTruncated,
        offered: offerable.length,
        searched: Boolean(searchTerm)
      });
  const searchNoticeId = useId();

  const pickerEmptyLabel = searching
    ? "Searching…"
    : searchTerm
      ? "No eligible account matches that search."
      : "No designers are eligible";

  const labelById = useMemo(() => new Map(viewerOptions.map((option) => [option.value, option.label])), [viewerOptions]);
  const added = useMemo(() => selected.filter((id) => !baseline.includes(id)), [selected, baseline]);
  const removed = useMemo(() => baseline.filter((id) => !selected.includes(id)), [baseline, selected]);

  const announcement = !workshopId
    ? ""
    : dirty
      ? `${selected.length} selected — unsaved (+${added.length}, −${removed.length}).`
      : `${selected.length} designer${selected.length === 1 ? "" : "s"}, plus its creator.`;

  async function save() {
    if (!workshopId || writesHeld(postRefusal)) return;
    setSaving(true);
    setSaveError(null);
    try {
      const payload = creatorHasRow && creatorId ? [...selected, creatorId] : selected;
      const result = await putDesignWorkshopViewers(workshopId, payload);
      const rows = result.viewers ?? [];
      setViewers(rows);
      setCreatorHasRow(rows.some((row) => row.userId === creatorId));
      const editable = rows.map((row) => row.userId).filter((id) => id !== creatorId);
      setBaseline(editable);
      setSelected(editable);
      toast({
        title: editable.length ? `${editable.length} designer${editable.length === 1 ? "" : "s"} can see this workshop` : "Nobody else can see this workshop",
        description: "Its creator always can.",
        tone: "success"
      });
    } catch (err) {
      setSaveError(describeFailure(err, "Unable to save who can see this workshop", readerIsAdmin));
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
            Who can open a workshop.
          </p>
        </div>
      </div>

      <p className="mt-3 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs leading-5 text-ink-500">
        Only its creator and the designers named here can see it.
      </p>

      {featureMissing ? (
        <p className="mt-3 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-5 text-amber-800">
          This isn&apos;t available right now.
        </p>
      ) : null}

      {loadError ? (
        <div className="mt-3 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800">{loadError}</div>
      ) : null}

      {featureMissing ? null : (
        <>
          <div className="mt-4 grid gap-3 md:grid-cols-[minmax(0,0.9fr)_minmax(0,1.4fr)]">
            <FieldBlock label="Workshop type">
              <Dropdown
                advanceOnSelect={false}
                ariaLabel="Workshop type"
                disabled={!typeKnown || typeNarrowingSuspended}
                onChange={setTypeFilter}
                options={TYPE_OPTIONS}
                value={typeFilter}
              />
            </FieldBlock>
            <FieldBlock label="Design workshop">
              <Dropdown
                advanceOnSelect={false}
                ariaLabel="Design workshop"
                onChange={(id) => {
                  setWorkshopId(id);
                  setWorkshopSearch("");
                }}
                options={workshopSet.options}
                serverQuery={{
                  value: workshopSearch,
                  onChange: (next) => setWorkshopSearch(next.slice(0, ELIGIBLE_VIEWER_SEARCH_MAX)),
                  pending: workshopsSearching
                }}
                emptyLabel={
                  filteredAway
                    ? "None of this type here."
                    : workshopEmptyLabel(workshops, workshopVoice)
                }
                placeholder="Select or type to search"
                value={workshopId}
              />
            </FieldBlock>
          </div>

          {typeFilter && untyped > 0 ? (
            <p className="mt-2 text-xs leading-5 text-ink-500">
              {untyped} unlinked workshop{untyped === 1 ? "" : "s"} hidden — choose “Any workshop type”.
            </p>
          ) : null}
          {!typeKnown ? (
            <p className="mt-2 text-xs leading-5 text-ink-500">
              Type filter off — workshops didn&rsquo;t load.
            </p>
          ) : null}
          {typeKnown && typeNarrowingSuspended ? (
            <p className="mt-2 text-xs leading-5 text-ink-500" aria-live="polite">
              Type filter is off while searching.
            </p>
          ) : null}
          {typeKnown && typeMapPartial ? (
            <p className="mt-2 text-xs leading-5 text-ink-500">
              Only the latest {WORKSHOP_PAGE * WORKSHOP_TYPE_MAP_MAX_PAGES} are typed; older ones are under &ldquo;Any workshop type&rdquo;.
            </p>
          ) : null}
          {workshopCut ? <p className="mt-2 text-xs leading-5 text-ink-500">{workshopCut}</p> : null}
          {workshopNotice ? (
            <p className="mt-2 text-xs leading-5 text-ink-500" aria-live="polite">
              {workshopNotice}
            </p>
          ) : null}

          {!workshopId ? (
            <p className="mt-4 text-sm text-ink-500">Pick a workshop.</p>
          ) : viewers === null ? (
            <p className="mt-4 text-sm text-ink-500">Loading…</p>
          ) : (
            <>
              <div className="mt-4 rounded-md border border-purple-300 bg-purple-50 px-3 py-2">
                <p className="text-xs font-semibold uppercase tracking-wide text-purple-700 dark:text-purple-300">Always has access</p>
                <p className="mt-1 text-sm text-ink-900">
                  {creator ? creator.name : "The designer who created it"}
                  {creator?.role ? <span className="text-ink-500"> · {roleLabel(creator.role)}</span> : null}
                </p>
                <p className="mt-1 text-xs leading-5 text-ink-500">
                  Creator — always has access.
                </p>
              </div>

              <HeldPostNotice refusal={postRefusal} id={heldNoticeId} className="mt-4" sayPending />

              <div className="mt-4">
                <FieldBlock
                  label="Designers who may see this workshop"
                  hint={
                    <p className="text-xs leading-5 text-ink-500">
                      Its inspectors and directors can&rsquo;t be added.
                    </p>
                  }
                >
                  <div className="grid gap-2">
                    <SearchInput
                      onChange={(next) => setSearch(next.slice(0, ELIGIBLE_VIEWER_SEARCH_MAX))}
                      placeholder="Search designers by name or email"
                      value={search}
                    />
                    {searchNotice ? (
                      <p className="text-xs leading-5 text-ink-500" id={searchNoticeId}>
                        {searchNotice}
                      </p>
                    ) : null}
                    <MultiSelectDropdown
                      ariaLabel="Designers who may see this workshop"
                      confirmLabel="Done"
                      disabled={writesHeld(postRefusal)}
                      describedBy={searchNotice ? searchNoticeId : undefined}
                      emptyLabel={pickerEmptyLabel}
                      onChange={setSelected}
                      options={viewerOptions}
                      placeholder="Select one or more designers"
                      searchable={false}
                      capHint="Search above to find the rest."
                      values={selected}
                    />
                  </div>
                </FieldBlock>
              </div>

              <div className="mt-3">
                {viewers.filter((row) => row.userId !== creatorId).length === 0 ? (
                  <p className="text-sm text-ink-500">
                    Only its creator and admins can open it.
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
                            <span className="rounded-full border border-error-600/25 bg-error-100 px-2 py-0.5 text-[0.6875rem] font-medium text-red-800">
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

              {saveError ? (
                <div className="mt-3 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800">
                  {saveError}
                </div>
              ) : null}

              <div className="mt-3 flex flex-wrap items-center gap-2">
                <ActionGroup className="max-sm:w-full"><button
                  className="field-button"
                  disabled={!dirty || saving || writesHeld(postRefusal)}
                  aria-describedby={postRefusal ? heldNoticeId : undefined}
                  onClick={save}
                  type="button"
                >
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
                </ActionGroup>
                {dirty ? <span className="text-xs text-amber-800 dark:text-amber-100">{added.length + removed.length} unsaved change{added.length + removed.length === 1 ? "" : "s"}</span> : null}
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
