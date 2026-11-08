"use client";

import { ScanEye, Save } from "lucide-react";
import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { SearchInput } from "@/components/SearchInput";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown, MultiSelectDropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { useToast } from "@/components/ui/Toast";
import {
  eligibleInspectorNotice,
  ELIGIBLE_INSPECTOR_SEARCH_MAX,
  inspectionAdministrationMissing,
  inspectorAdministrationFailure,
  listDesignWorkshopInspectors,
  listEligibleDesignWorkshopInspectors,
  MAX_DESIGN_WORKSHOP_INSPECTORS,
  putDesignWorkshopInspectors,
  type DwEligibleInspector,
  type DwInspector
} from "@/lib/designWorkshopInspections";
import { listDesignWorkshops, type DwSummary } from "@/lib/designWorkshops";
import { absenceProvesIneligible } from "@/lib/designWorkshopViewers";
import { formatDateTime } from "@/lib/format";
import { canAssignWorkshopOversight, roleLabel, selfReleaseRefusal, selfReleaseRefused } from "@/lib/permissions";
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

const WORKSHOP_PAGE = WORKSHOP_OPTION_PAGE_SIZE;

const SEARCH_DEBOUNCE_MS = 300;

function personLabel(person: { name?: string | null; email?: string | null } | null | undefined): string {
  return person?.name?.trim() || person?.email?.trim() || "Unknown user";
}

const describeFailure = inspectorAdministrationFailure;

export function DesignWorkshopInspectorsPanel({
  refreshToken,
  workshopId: fixedWorkshopId,
  onSaved
}: {
  refreshToken?: number;
  onSaved?: () => void;
  workshopId?: string;
}) {
  const { toast } = useToast();
  const { user } = useAuth();
  const readerId = user?.id ?? null;
  const readerMayAssign = canAssignWorkshopOversight(user);

  const ownsThePicker = fixedWorkshopId === undefined;

  const [workshopSearch, setWorkshopSearch] = useState("");
  const [workshops, setWorkshops] = useState<WorkshopListState<DwSummary>>({ kind: "loading" });
  const [workshopsOnline, setWorkshopsOnline] = useState(true);
  const [chosenWorkshopId, setWorkshopId] = useState("");
  const workshopId = fixedWorkshopId ?? chosenWorkshopId;
  const [knownWorkshops, setKnownWorkshops] = useState<Map<string, DwSummary>>(() => new Map());
  const [workshopsSearching, setWorkshopsSearching] = useState(false);

  const [search, setSearch] = useState("");
  const [eligible, setEligible] = useState<DwEligibleInspector[] | null>(null);
  const [eligibleTruncated, setEligibleTruncated] = useState(false);
  const [searching, setSearching] = useState(false);
  const [known, setKnown] = useState<Map<string, DwEligibleInspector>>(() => new Map());
  const [featureMissing, setFeatureMissing] = useState(false);

  const [inspectors, setInspectors] = useState<DwInspector[] | null>(null);
  const [baseline, setBaseline] = useState<string[]>([]);
  const [selected, setSelected] = useState<string[]>([]);

  const [loadError, setLoadError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const workshopGeneration = useRef(0);

  useEffect(() => {
    if (!ownsThePicker) return;
    const term = workshopSearch.trim();
    const current = workshopGeneration.current + 1;
    workshopGeneration.current = current;
    setWorkshopsSearching(true);
    const timer = window.setTimeout(
      () => {
        listDesignWorkshops({ page: 1, pageSize: WORKSHOP_PAGE, search: term || undefined })
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
  }, [ownsThePicker, refreshToken, workshopSearch]);

  const eligibleGeneration = useRef(0);

  useEffect(() => {
    const term = search.trim();
    const current = eligibleGeneration.current + 1;
    eligibleGeneration.current = current;
    setSearching(true);
    const timer = window.setTimeout(
      () => {
        listEligibleDesignWorkshopInspectors(term)
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
            if (inspectionAdministrationMissing(err)) {
              setFeatureMissing(true);
              setEligible([]);
              return;
            }
            setEligible([]);
            setLoadError(
              describeFailure(err, "Couldn't load accounts", readerMayAssign)
            );
          });
      },
      term ? SEARCH_DEBOUNCE_MS : 0
    );
    return () => window.clearTimeout(timer);
  }, [refreshToken, search, readerMayAssign]);

  const loadInspectors = useCallback(async () => {
    if (!workshopId) {
      setInspectors(null);
      setBaseline([]);
      setSelected([]);
      return;
    }
    try {
      const result = await listDesignWorkshopInspectors(workshopId);
      const rows = result.inspectors ?? [];
      setInspectors(rows);
      const held = rows.map((row) => row.userId);
      setBaseline(held);
      setSelected(held);
      setSaveError(null);
    } catch (err) {
      setInspectors([]);
      setBaseline([]);
      setSelected([]);
      setLoadError(describeFailure(err, "Unable to load who is inspecting this workshop", readerMayAssign));
    }
  }, [workshopId, readerMayAssign]);

  useEffect(() => {
    setLoadError(null);
    void loadInspectors();
  }, [loadInspectors]);

  const selectedWorkshop = useMemo(
    () => knownWorkshops.get(workshopId) ?? null,
    [knownWorkshops, workshopId]
  );

  const workshopSet = useMemo(
    () =>
      designWorkshopOptions(workshops, {
        group: true,
        offPage: { mode: "recover", row: selectedWorkshop }
      }),
    [workshops, selectedWorkshop]
  );

  const workshopVoice = useMemo<WorkshopListVoice>(
    () => ({ table: "design", scoped: true, online: workshopsOnline }),
    [workshopsOnline]
  );

  const workshopSearchTerm = workshopSearch.trim();
  const workshopNotice =
    workshops.kind === "ok" && workshopSearchTerm ? "" : workshopListNotice(workshops, workshopVoice);
  const workshopCut = workshopCutSentence(workshopSet, {
    term: workshopSearch,
    searchable: true
  });

  const searchTerm = search.trim();

  const eligibleListIsComplete = !eligibleTruncated && !searchTerm;

  const offerable = useMemo(
    () => (eligible ?? []).filter((person) => person.id !== readerId),
    [eligible, readerId]
  );

  const inspectorOptions = useMemo(() => {
    const options: DropdownOption[] = [];
    const seen = new Set<string>();
    const offer = (id: string, label: string, held?: Pick<DropdownOption, "disabled" | "hint">) => {
      if (!id || seen.has(id)) return;
      seen.add(id);
      options.push({ value: id, label, ...held });
    };
    for (const person of offerable) {
      offer(person.id, `${personLabel(person)} · ${roleLabel(person.role)}`);
    }
    for (const row of inspectors ?? []) {
      offer(
        row.userId,
        `${personLabel(row)} · ${roleLabel(row.role)}${
          absenceProvesIneligible(eligibleListIsComplete, row.userId, readerId)
            ? " — inspecting, no longer eligible"
            : " — inspecting"
        }`,
        row.userId === readerId ? { disabled: true, hint: "you — another administrator has to take you off" } : undefined
      );
    }
    for (const id of selected) {
      const person = known.get(id);
      if (person) offer(person.id, `${personLabel(person)} · ${roleLabel(person.role)}`);
    }
    return options;
  }, [offerable, inspectors, selected, known, eligibleListIsComplete, readerId]);

  const dirty = useMemo(() => {
    if (selected.length !== baseline.length) return true;
    const held = new Set(baseline);
    return selected.some((id) => !held.has(id));
  }, [selected, baseline]);

  const overCap = selected.length > MAX_DESIGN_WORKSHOP_INSPECTORS;

  const readerInspects = readerId !== null && baseline.includes(readerId);
  const selfNoteId = useId();

  const searchNotice = searching && searchTerm
    ? "Searching…"
    : eligibleInspectorNotice({
        truncated: eligibleTruncated,
        offered: offerable.length,
        searched: Boolean(searchTerm)
      });
  const searchNoticeId = useId();

  const pickerEmptyLabel = searching
    ? "Searching…"
    : searchTerm
      ? "No account that may inspect matches that search."
      : "Nobody else may be appointed to inspect yet";

  const labelById = useMemo(
    () => new Map(inspectorOptions.map((option) => [option.value, option.label])),
    [inspectorOptions]
  );
  const added = useMemo(() => selected.filter((id) => !baseline.includes(id)), [selected, baseline]);
  const removed = useMemo(() => baseline.filter((id) => !selected.includes(id)), [baseline, selected]);

  const announcement = !workshopId
    ? ""
    : dirty
      ? `${selected.length} selected — unsaved (+${added.length}, −${removed.length}).`
      : selected.length === 0
        ? "No inspectors."
        : `${selected.length} inspector${selected.length === 1 ? "" : "s"} assigned to this workshop. Nothing unsaved.`;

  async function save() {
    if (!workshopId || overCap) return;
    if (readerId && baseline.includes(readerId) && !selected.includes(readerId)) {
      setSaveError(selfReleaseRefused("INSPECTION"));
      return;
    }
    setSaving(true);
    setSaveError(null);
    try {
      const result = await putDesignWorkshopInspectors(workshopId, selected);
      const rows = result.inspectors ?? [];
      setInspectors(rows);
      const held = rows.map((row) => row.userId);
      setBaseline(held);
      setSelected(held);
      toast({
        title: held.length
          ? `${held.length} inspector${held.length === 1 ? "" : "s"} assigned to this workshop`
          : "This workshop is not under inspection",
        description: held.length
          ? "Read-only access."
          : "No inspectors.",
        tone: "success"
      });
      onSaved?.();
    } catch (err) {
      setSaveError(describeFailure(err, "Unable to save who is inspecting this workshop", readerMayAssign));
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="panel min-w-0 p-5" data-design-workshop-inspectors>
      <div className="flex items-center gap-2.5">
        <span className="grid h-8 w-8 place-items-center rounded-md bg-purple-950 text-purple-100">
          <ScanEye className="h-4 w-4" aria-hidden />
        </span>
        <div>
          <h2 className="font-display font-bold text-ink-900">Inspection of a design workshop</h2>
          <p className="text-xs leading-5 text-ink-500">
            Who inspects a workshop.
          </p>
        </div>
      </div>

      <p className="mt-3 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs leading-5 text-ink-500">
        Inspectors can read every stage but change nothing.
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
          {ownsThePicker ? (
            <>
          <div className="mt-4">
            <FieldBlock label="Design workshop">
              <Dropdown
                advanceOnSelect={false}
                ariaLabel="Design workshop"
                serverQuery={{
                  value: workshopSearch,
                  onChange: (next) => setWorkshopSearch(next.slice(0, ELIGIBLE_INSPECTOR_SEARCH_MAX)),
                  pending: workshopsSearching
                }}
                emptyLabel={workshopEmptyLabel(workshops, workshopVoice)}
                onChange={setWorkshopId}
                options={workshopSet.options}
                placeholder="Select a design workshop"
                value={workshopId}
              />
            </FieldBlock>
          </div>

          {workshopCut ? <p className="mt-2 text-xs leading-5 text-ink-500">{workshopCut}</p> : null}
          {workshopNotice ? (
            <p className="mt-2 text-xs leading-5 text-ink-500" aria-live="polite">
              {workshopNotice}
            </p>
          ) : null}
            </>
          ) : null}

          {!workshopId ? (
            <p className="mt-4 text-sm text-ink-500">
              {ownsThePicker
                ? "Pick a workshop."
                : "Choose a workshop above."}
            </p>
          ) : inspectors === null ? (
            <p className="mt-4 text-sm text-ink-500">Loading…</p>
          ) : (
            <>
              <div className="mt-4">
                <FieldBlock
                  label="Inspectors assigned to this workshop"
                  hint={
                    <p className="text-xs leading-5 text-ink-500">
                      Up to {MAX_DESIGN_WORKSHOP_INSPECTORS}. Its designers and directors can&rsquo;t inspect it.
                    </p>
                  }
                >
                  <div className="grid gap-2">
                    <SearchInput
                      onChange={(next) => setSearch(next.slice(0, ELIGIBLE_INSPECTOR_SEARCH_MAX))}
                      placeholder="Search by name or email"
                      value={search}
                    />
                    {searchNotice ? (
                      <p className="text-xs leading-5 text-ink-500" id={searchNoticeId}>
                        {searchNotice}
                      </p>
                    ) : null}
                    <MultiSelectDropdown
                      ariaLabel="Inspectors assigned to this workshop"
                      capHint="Search above to find the rest."
                      confirmLabel="Done"
                      describedBy={
                        [searchNotice ? searchNoticeId : null, readerInspects ? selfNoteId : null]
                          .filter(Boolean)
                          .join(" ") || undefined
                      }
                      emptyLabel={pickerEmptyLabel}
                      onChange={setSelected}
                      options={inspectorOptions}
                      placeholder="Select one or more inspectors"
                      searchable={false}
                      values={selected}
                    />
                    {readerInspects ? (
                      <p
                        id={selfNoteId}
                        className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800"
                      >
                        {selfReleaseRefusal("INSPECTION")} Your own name stays ticked; you can still add or
                        take off everybody else on this workshop&rsquo;s panel.
                      </p>
                    ) : null}
                  </div>
                </FieldBlock>
              </div>

              {overCap ? (
                <p className="mt-3 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-5 text-amber-800">
                  Maximum {MAX_DESIGN_WORKSHOP_INSPECTORS}. Untick {selected.length - MAX_DESIGN_WORKSHOP_INSPECTORS} to save.
                </p>
              ) : null}

              <div className="mt-3">
                {inspectors.length === 0 ? (
                  <p className="text-sm text-ink-500">
                    No inspectors yet.
                  </p>
                ) : (
                  <ul className="grid gap-1.5">
                    {inspectors.map((row) => (
                      <li className="flex flex-wrap items-baseline gap-x-2 text-sm text-ink-700" key={row.userId}>
                        <span className="font-medium text-ink-900">{personLabel(row)}</span>
                        <span className="text-xs text-ink-500">
                          {row.email}
                          {row.role ? ` · ${roleLabel(row.role)}` : ""}
                          {row.assignedAt ? ` · inspecting since ${formatDateTime(row.assignedAt)}` : ""}
                        </span>
                        {removed.includes(row.userId) ? (
                          <span className="rounded-full border border-error-600/25 bg-error-100 px-2 py-0.5 text-[0.6875rem] font-medium text-red-800">
                            inspection ends on save
                          </span>
                        ) : null}
                      </li>
                    ))}
                  </ul>
                )}
                {added.length ? (
                  <p className="mt-2 text-xs leading-5 text-ink-500">
                    Will be assigned on save: {added.map((id) => labelById.get(id) ?? "Unknown user").join(", ")}.
                  </p>
                ) : null}
              </div>

              {saveError ? (
                <div className="mt-3 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800">
                  {saveError}
                </div>
              ) : null}

              <div className="mt-3 flex flex-wrap items-center gap-2">
                <ActionGroup className="max-sm:w-full"><button className="field-button" disabled={!dirty || saving || overCap} onClick={save} type="button">
                  <Save className="h-4 w-4" aria-hidden />
                  {saving ? "Saving…" : "Save who inspects this"}
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
                {dirty ? (
                  <span className="text-xs text-amber-800 dark:text-amber-100">
                    {added.length + removed.length} unsaved change{added.length + removed.length === 1 ? "" : "s"}
                  </span>
                ) : null}
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
