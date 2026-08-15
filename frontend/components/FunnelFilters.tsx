"use client";

import { useEffect, useRef, useState } from "react";

import { CappedListNotice } from "@/components/data/CappedListNotice";
import { LIST_PAGE_CEILING, listCut, type ListCut } from "@/components/data/cappedList";
import { workshopOccurrenceDate } from "@/components/forms/WorkshopSelect";
import { Dropdown } from "@/components/ui/Dropdown";
import { listResource } from "@/lib/api";
import type { Artisan, Craft, Workshop } from "@/lib/types";

/** Workshop rows from the list API include linked artisans and crafts (crafts not yet in the TS type). */
export type FunnelWorkshop = Workshop & {
  crafts?: Array<{ craftId?: string; craft?: { id: string; name: string } }>;
};

export type FunnelValue = { workshopId: string; craftId: string; artisanId: string };

export const EMPTY_FUNNEL: FunnelValue = { workshopId: "", craftId: "", artisanId: "" };

/**
 * When a workshop actually happened: its start date, falling back to the legacy single `date`
 * column and finally to when the row was created. Sorting on this is what makes "most recent
 * workshop" mean the latest FIELD work rather than the last row someone typed in.
 *
 * Re-exported from `forms/WorkshopSelect` rather than re-implemented: the record forms' workshop
 * picker, this funnel and Android's `WorkshopDetailDto.occurrenceDate()` must agree on which
 * workshop is "current", otherwise the filter and the form disagree about the same list.
 */
const occurredAt = workshopOccurrenceDate;

/**
 * Cascading list filters: Workshop -> Craft -> Artisan. Selecting a workshop narrows the craft
 * options to the workshop's linked crafts (when it has any); selecting a craft reloads the artisan
 * options from /artisans?craftId=. The workshop filter DEFAULTS to the most recent workshop by
 * occurrence date — `onChange` fires once after the workshops load with that default (or "All"
 * when none exist), so parents should wait for the first call before fetching their list.
 */
export function FunnelFilters({
  value,
  onChange,
  showArtisan = false
}: {
  value: FunnelValue;
  /** Next filter value plus the selected workshop row (null = all workshops) for client-side narrowing. */
  onChange: (next: FunnelValue, workshop: FunnelWorkshop | null) => void;
  showArtisan?: boolean;
}) {
  const [workshops, setWorkshops] = useState<FunnelWorkshop[]>([]);
  const [crafts, setCrafts] = useState<Craft[]>([]);
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  /**
   * WHAT EACH DROPDOWN IS NOT SHOWING — see `components/data/cappedList`.
   *
   * All three loads below ask for `LIST_PAGE_CEILING` rows, which is the largest page the API will
   * serve, and every one of these three tables is past it on this database: 196 workshops, 178
   * crafts, 749 artisans (counted 2026-08-15). Until this state existed the envelope's `total` was
   * discarded at all three call sites, so the funnel could not tell a short list from a cut one and
   * neither could anybody reading it: "Filter by artisan" offered a hundred names out of 749 and
   * looked exactly like a repository with a hundred artisans in it.
   */
  const [cuts, setCuts] = useState<{ workshops: ListCut | null; crafts: ListCut | null; artisans: ListCut | null }>({
    workshops: null,
    crafts: null,
    artisans: null
  });
  const initialised = useRef(false);

  // Load workshops + crafts once and default the workshop filter to the workshop that happened last.
  //
  // This default is deliberate — a researcher standing in a workshop wants that workshop's records —
  // but it has one failure mode worth naming, because it already bit once. `workshopId` is a recent
  // column and a row that predates it is NULL, which matches no workshop. Every list carrying this
  // funnel then renders EMPTY over a full corpus, and an empty list is indistinguishable from having
  // no data: a researcher opening "Update" to fix a tool concludes their work is gone. The records
  // were backfilled onto their workshop to fix that, so if it recurs the question to ask is whether
  // something new is being created without a workshop rather than whether this default is wrong.
  useEffect(() => {
    if (initialised.current) return;
    initialised.current = true;
    (async () => {
      let workshopRows: FunnelWorkshop[] = [];
      let craftRows: Craft[] = [];
      try {
        const [workshopResult, craftResult] = await Promise.all([
          listResource<FunnelWorkshop>("/workshops", { pageSize: LIST_PAGE_CEILING }),
          listResource<Craft>("/crafts", { pageSize: LIST_PAGE_CEILING })
        ]);
        // The API orders workshops by createdAt; the filter wants the most recently HELD workshop
        // first, so re-sort on the occurrence date (startDate ?? date ?? createdAt), newest first.
        //
        // ⚠ THE RE-SORT CANNOT RECOVER A WORKSHOP THE SERVER ALREADY CUT. It reorders the hundred
        // rows that arrived; the 96 that did not are not here to be sorted. With 196 workshops on
        // this database the default below is the most recent of the newest hundred by CREATION,
        // which is very probably the most recent overall and is why nobody has noticed — but the
        // notice this now records is the only thing that will say so when it is not.
        workshopRows = [...workshopResult.items].sort((a, b) => occurredAt(b).localeCompare(occurredAt(a)));
        craftRows = craftResult.items;
        setCuts((previous) => ({
          ...previous,
          workshops: listCut(workshopResult, "workshops"),
          crafts: listCut(craftResult, "crafts")
        }));
      } catch {
        // Filters degrade to "All" — the parent list still loads unfiltered.
      }
      setWorkshops(workshopRows);
      setCrafts(craftRows);
      const latest = workshopRows[0] ?? null;
      onChange({ workshopId: latest?.id ?? "", craftId: "", artisanId: "" }, latest);
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /**
   * Artisan options follow BOTH the selected workshop and the selected craft, and both narrowings
   * are the server's.
   *
   * The workshop half used to be done here in the browser — `artisans.filter(a =>
   * workshopArtisanIds.has(a.id))` over whatever the unfiltered request had returned — and that is
   * the compounding failure this dropdown was fixed for. The request answered the newest 100
   * artisans of the whole table (749 rows on this database, ordered `createdAt desc`), and those
   * hundred were then intersected with one workshop's people. A workshop whose artisans were
   * entered before the newest hundred contributed NOTHING: the control rendered as "All artisans"
   * plus a few recent names, and the person a researcher was reaching for was not an option, was
   * not searchable, and had no page two.
   *
   * `list_artisans` has taken `workshopId` all along and its clause is broader than the browser's
   * could ever be — it ORs the `Artisan.workshopId` column with the `WorkshopArtisan` join, while
   * the intersection could only see join rows (`RELATIONS` in `routes/workshops.py` includes
   * `workshopartisan` only). So sending it both fixes the cut AND reaches artisans linked by the
   * column alone, who were invisible here even when they were on the loaded page.
   *
   * `value.workshopId` MUST stay in the dependency array beside `value.craftId`. Dropping it
   * restores the defect silently: the options would be whatever the last craft change happened to
   * fetch, under a workshop label claiming to have narrowed them.
   */
  useEffect(() => {
    if (!showArtisan) return;
    let active = true;
    listResource<Artisan>("/artisans", {
      workshopId: value.workshopId || undefined,
      craftId: value.craftId || undefined,
      pageSize: LIST_PAGE_CEILING
    })
      .then((result) => {
        if (!active) return;
        setArtisans(result.items);
        setCuts((previous) => ({ ...previous, artisans: listCut(result, "artisans") }));
      })
      .catch(() => {
        if (!active) return;
        setArtisans([]);
        setCuts((previous) => ({ ...previous, artisans: null }));
      });
    return () => {
      active = false;
    };
  }, [showArtisan, value.workshopId, value.craftId]);

  const selectedWorkshop = value.workshopId ? (workshops.find((workshop) => workshop.id === value.workshopId) ?? null) : null;

  /**
   * A workshop's crafts come from the WORKSHOP ROW, not from the crafts page.
   *
   * This used to be `crafts.filter((craft) => workshopCraftIds.has(craft.id))`, which is the same
   * compounding bug as the artisan arm: `crafts` is one page of 100 out of 178 ordered by name, so a
   * craft of this workshop whose name sorts past the hundredth was dropped from a list that claims
   * to be the workshop's crafts. The link rows already carry `{ id, name }` — `RELATIONS` in
   * `routes/workshops.py` includes `craft: True` — so the workshop's own answer is complete and the
   * cut page is not needed for it at all.
   *
   * A link that somehow arrives with only a `craftId` is looked up in the loaded page for its name
   * and otherwise DROPPED rather than rendered as a bare cuid: `optionText`'s own history in this
   * repository is three live dropdowns offering raw ids, and an unreadable option is not a reachable
   * one. It cannot be rendered honestly, and there is nothing here to render it from.
   *
   * No links at all still means "offer every craft", unchanged: a workshop that predates the join
   * has no craft of its own to name, and blanking the filter there would be the silent-emptiness
   * failure this component's own header comment was written about.
   */
  const workshopCraftLinks = selectedWorkshop?.crafts ?? [];
  const craftOptions = workshopCraftLinks.length
    ? workshopCraftLinks
        .map((link) => {
          if (link.craft) return { id: link.craft.id, name: link.craft.name };
          const known = link.craftId ? crafts.find((craft) => craft.id === link.craftId) : undefined;
          return known ? { id: known.id, name: known.name } : null;
        })
        .filter((craft): craft is { id: string; name: string } => craft !== null)
        // The crafts endpoint answers name-ascending on purpose (a picker is scanned by name, see
        // the ordering comment in routes/crafts.py); join order is arbitrary, so restore it.
        .sort((a, b) => a.name.localeCompare(b.name))
    : crafts;

  // The artisan dropdown draws exactly what the request returned — the workshop is in the WHERE
  // clause now (see the loader above), so there is no second narrowing here and no cut page to
  // intersect against.
  const artisanOptions = artisans;

  function selectWorkshop(workshopId: string) {
    const workshop = workshopId ? (workshops.find((item) => item.id === workshopId) ?? null) : null;
    onChange({ workshopId, craftId: "", artisanId: "" }, workshop);
  }

  function selectCraft(craftId: string) {
    onChange({ ...value, craftId, artisanId: "" }, selectedWorkshop);
  }

  function selectArtisan(artisanId: string) {
    onChange({ ...value, artisanId }, selectedWorkshop);
  }

  /**
   * WHICH CUTS ARE STILL TRUE OF WHAT IS ON SCREEN.
   *
   * A cut is only worth reporting where the reader can actually be misled by it, so each one is
   * re-tested against the list that is drawn rather than against the request that was made:
   *
   * - crafts: silent once a workshop with craft links is selected, because that dropdown is then
   *   built from the workshop row and is complete. Reporting the crafts page's cut there would be a
   *   sentence about a list nobody is looking at.
   * - artisans: only when the artisan dropdown is rendered at all.
   */
  const reportedCuts = [
    cuts.workshops,
    craftOptions === crafts ? cuts.crafts : null,
    showArtisan ? cuts.artisans : null
  ];

  return (
    <div className="mb-4">
    {/* advanceOnSelect={false} on all three: these NARROW THE LIST BELOW rather than fill in a form
        field, so the auto-advance the Dropdown does by default is wrong here — it threw focus onto the
        next filter (workshop -> craft), which then swallowed the arrow keys of anyone still adjusting
        the filter they had just changed. See the prop's own documentation in ui/Dropdown. */}
    <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
      <Dropdown
        ariaLabel="Filter by workshop"
        value={value.workshopId}
        onChange={selectWorkshop}
        advanceOnSelect={false}
        options={[
          { value: "", label: "All workshops" },
          ...workshops.map((workshop) => ({ value: workshop.id, label: workshop.title }))
        ]}
      />
      <Dropdown
        ariaLabel="Filter by craft"
        value={value.craftId}
        onChange={selectCraft}
        advanceOnSelect={false}
        options={[{ value: "", label: "All crafts" }, ...craftOptions.map((craft) => ({ value: craft.id, label: craft.name }))]}
      />
      {showArtisan ? (
        <Dropdown
          ariaLabel="Filter by artisan"
          value={value.artisanId}
          onChange={selectArtisan}
          advanceOnSelect={false}
          options={[
            { value: "", label: "All artisans" },
            ...artisanOptions.map((artisan) => ({ value: artisan.id, label: `${artisan.name} · ${artisan.place}` }))
          ]}
        />
      ) : null}
    </div>
    {/* One line per dropdown that is not showing everything it claims to filter by. Nothing at all
        when all three are whole, which is the case this repository will be in again the day the
        endpoints take a search term from these controls. */}
    <CappedListNotice cuts={reportedCuts} className="mt-1.5 grid gap-0.5" />
    </div>
  );
}
