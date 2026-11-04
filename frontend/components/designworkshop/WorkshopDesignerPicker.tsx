"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";

import { SearchInput } from "@/components/SearchInput";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown, MultiSelectDropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { MAX_NAMED_DESIGNERS, namedDesignerTeam } from "@/lib/designWorkshops";
import {
  ELIGIBLE_VIEWER_SEARCH_MAX,
  eligibleViewerNotice,
  listEligibleDesignWorkshopViewers,
  viewerAdministrationMissing,
  type DwEligibleViewer
} from "@/lib/designWorkshopViewers";
import { roleLabel } from "@/lib/permissions";
import {
  deviceLooksOffline,
  workshopEmptyLabel,
  type WorkshopListState,
  type WorkshopListVoice
} from "@/lib/workshopOptions";

const SEARCH_DEBOUNCE_MS = 300;

export type EligibleDesignerPage = { users: DwEligibleViewer[]; truncated: boolean };

export type FetchEligibleDesigners = (search: string) => Promise<EligibleDesignerPage>;

function rowFor(person: DwEligibleViewer): DropdownOption {
  return {
    value: person.id,
    label: person.name || person.email,
    hint: [person.email, roleLabel(person.role)].filter(Boolean).join(" · ")
  };
}

function nameOf(seen: Map<string, DwEligibleViewer>, id: string): string {
  const person = seen.get(id);
  if (!person) return id;
  return person.name || person.email;
}

export function WorkshopDesignerPicker({
  values,
  onChange,
  lead,
  onLeadChange,
  disabled,
  offline,
  fetchEligible,
  excludeUserIds
}: {
  values: string[];
  onChange: (userIds: string[]) => void;
  lead: string;
  onLeadChange: (userId: string) => void;
  disabled?: boolean;
  fetchEligible?: FetchEligibleDesigners;
  offline?: boolean;
  excludeUserIds?: readonly string[];
}) {
  const [search, setSearch] = useState("");
  const [eligible, setEligible] = useState<DwEligibleViewer[] | null>(null);
  const [seen, setSeen] = useState<Map<string, DwEligibleViewer>>(() => new Map());
  const [truncated, setTruncated] = useState(false);
  const [searching, setSearching] = useState(false);
  const [featureMissing, setFeatureMissing] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const noticeId = useId();

  const generation = useRef(0);

  const fetchEligibleRef = useRef<FetchEligibleDesigners>(
    fetchEligible ?? listEligibleDesignWorkshopViewers
  );
  useEffect(() => {
    fetchEligibleRef.current = fetchEligible ?? listEligibleDesignWorkshopViewers;
  });

  useEffect(() => {
    if (offline) {
      setEligible(null);
      setTruncated(false);
      setSearching(false);
      return;
    }
    const term = search.trim();
    const current = generation.current + 1;
    generation.current = current;
    setSearching(true);
    const timer = window.setTimeout(
      () => {
        fetchEligibleRef.current(term)
          .then((result) => {
            if (generation.current !== current) return;
            const users = result.users ?? [];
            setEligible(users);
            setSeen((previous) => {
              const next = new Map(previous);
              for (const person of users) next.set(person.id, person);
              return next;
            });
            setTruncated(Boolean(result.truncated));
            setFeatureMissing(false);
            setLoadError(null);
            setSearching(false);
          })
          .catch((err: unknown) => {
            if (generation.current !== current) return;
            setSearching(false);
            setTruncated(false);
            setEligible([]);
            if (viewerAdministrationMissing(err)) {
              setFeatureMissing(true);
              return;
            }
            setLoadError("Couldn't load designers.");
          });
      },
      term ? SEARCH_DEBOUNCE_MS : 0
    );
    return () => window.clearTimeout(timer);
  }, [search, offline]);

  const excludedKey = (excludeUserIds ?? []).join("\u0000");

  const offerable = useMemo(() => {
    const excluded = new Set(excludedKey ? excludedKey.split("\u0000") : []);
    return (eligible ?? []).filter((person) => !excluded.has(person.id));
  }, [eligible, excludedKey]);

  const options = useMemo<DropdownOption[]>(() => {
    const rows: DropdownOption[] = [];
    const offered = new Set<string>();
    for (const person of offerable) {
      rows.push(rowFor(person));
      offered.add(person.id);
    }
    for (const id of values) {
      if (!id || offered.has(id)) continue;
      offered.add(id);
      const remembered = seen.get(id);
      rows.push(
        remembered
          ? rowFor(remembered)
          :
            { value: id, label: "A designer already chosen", hint: id }
      );
    }
    return rows;
  }, [offerable, seen, values]);

  const searchTerm = search.trim();

  const resolved = useMemo(() => namedDesignerTeam({ chosen: values, lead }), [values, lead]);

  const leadOptions = useMemo<DropdownOption[]>(
    () => resolved.team.map((id) => ({ value: id, label: nameOf(seen, id), hint: seen.get(id)?.email })),
    [resolved.team, seen]
  );

  const atCap = values.length >= MAX_NAMED_DESIGNERS;

  const eligibleList: WorkshopListState<DwEligibleViewer> =
    offline || featureMissing || loadError
      ? { kind: "failed" }
      : eligible === null
        ? { kind: "loading" }
        : { kind: "ok", rows: offerable, total: offerable.length };
  const eligibleVoice: WorkshopListVoice = {
    table: "field",
    noun: "designers",
    scoped: true,
    online: !deviceLooksOffline(),
    reassurance: "You can start the workshop without it."
  };

  const notice = offline
    ? "Offline — add designers later."
    : featureMissing
      ? "Designer list unavailable. You can add them later."
      : loadError
        ? loadError
        : searching && searchTerm
          ? "Searching…"
          :
            !searchTerm && !truncated && eligible !== null && offerable.length === 0
            ? "No empanelled designers yet. You can add them later."
            : eligibleViewerNotice({
                truncated,
                offered: offerable.length,
                searched: Boolean(searchTerm)
              });

  return (
    <FieldBlock
      label="Designers this workshop is for"
      hint={
        <p className="text-xs leading-5 text-ink-500">
          Only these designers (and admins) can open it. Optional — add more later.
        </p>
      }
    >
      <div className="grid gap-2">
        {offline ? null : (
          <div onInput={(event) => event.stopPropagation()}>
            <SearchInput
              onChange={(next) => setSearch(next.slice(0, ELIGIBLE_VIEWER_SEARCH_MAX))}
              placeholder="Search designers by name or email"
              value={search}
            />
          </div>
        )}
        {notice ? (
          <p className="text-xs leading-5 text-ink-500" id={noticeId}>
            {notice}
          </p>
        ) : null}
        <MultiSelectDropdown
          ariaLabel="Designers this workshop is for"
          confirmLabel="Done"
          describedBy={notice ? noticeId : undefined}
          disabled={disabled || offline}
          emptyLabel={
            eligibleList.kind === "ok"
              ? "No designers available."
              : workshopEmptyLabel(eligibleList, eligibleVoice)
          }
          onChange={(next) => {
            onChange(next.slice(0, MAX_NAMED_DESIGNERS));
          }}
          options={options}
          placeholder="Not decided yet"
          searchable={false}
          capHint="Search above to find the rest."
          values={values}
        />
        {atCap ? (
          <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
            Up to {MAX_NAMED_DESIGNERS} designers. Add more later from &ldquo;Designers on a workshop&rdquo;.
          </p>
        ) : null}
        {resolved.lead ? (
          <div className="grid gap-1 rounded-md border border-line-200 bg-surface-50 px-3 py-2">
            <p className="text-xs leading-5 text-ink-700">
              The report names{" "}
              <span className="font-medium text-ink-900">{nameOf(seen, resolved.lead)}</span>.
            </p>
            {resolved.team.length > 1 ? (
              <Dropdown
                ariaLabel="The designer whose name the report carries"
                disabled={disabled || offline}
                onChange={onLeadChange}
                options={leadOptions}
                searchable
                value={resolved.lead}
              />
            ) : null}
          </div>
        ) : null}
      </div>
    </FieldBlock>
  );
}
