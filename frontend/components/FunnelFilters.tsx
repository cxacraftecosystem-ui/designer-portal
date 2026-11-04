"use client";

import { useEffect, useRef, useState } from "react";

import { CappedListNotice } from "@/components/data/CappedListNotice";
import { LIST_PAGE_CEILING, listCut, type ListCut } from "@/components/data/cappedList";
import { workshopOccurrenceDate } from "@/components/forms/WorkshopSelect";
import { CraftSelect } from "@/components/forms/CraftSelect";
import { Dropdown } from "@/components/ui/Dropdown";
import { listResource } from "@/lib/api";
import type { Artisan, Workshop } from "@/lib/types";

export type FunnelWorkshop = Workshop & {
  crafts?: Array<{ craftId?: string; craft?: { id: string; name: string } }>;
};

export type FunnelValue = { workshopId: string; craftId: string; artisanId: string };

export const EMPTY_FUNNEL: FunnelValue = { workshopId: "", craftId: "", artisanId: "" };

const occurredAt = workshopOccurrenceDate;

export function FunnelFilters({
  value,
  onChange,
  showArtisan = false
}: {
  value: FunnelValue;
  onChange: (next: FunnelValue, workshop: FunnelWorkshop | null) => void;
  showArtisan?: boolean;
}) {
  const [workshops, setWorkshops] = useState<FunnelWorkshop[]>([]);
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [cuts, setCuts] = useState<{ workshops: ListCut | null; artisans: ListCut | null }>({
    workshops: null,
    artisans: null
  });
  const initialised = useRef(false);

  useEffect(() => {
    if (initialised.current) return;
    initialised.current = true;
    (async () => {
      let workshopRows: FunnelWorkshop[] = [];
      try {
        const workshopResult = await listResource<FunnelWorkshop>("/workshops", { pageSize: LIST_PAGE_CEILING });
        workshopRows = [...workshopResult.items].sort((a, b) => occurredAt(b).localeCompare(occurredAt(a)));
        setCuts((previous) => ({ ...previous, workshops: listCut(workshopResult, "workshops") }));
      } catch {
      }
      setWorkshops(workshopRows);
      const latest = workshopRows[0] ?? null;
      onChange({ workshopId: latest?.id ?? "", craftId: "", artisanId: "" }, latest);
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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

  const workshopCraftLinks = selectedWorkshop?.crafts ?? [];
  const workshopCraftIds = workshopCraftLinks
    .map((link) => link.craft?.id ?? link.craftId ?? "")
    .filter((id) => id !== "");
  const allowedCraftIds = workshopCraftIds.length ? workshopCraftIds : undefined;

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

  const reportedCuts = [
    cuts.workshops,
    showArtisan ? cuts.artisans : null
  ];

  return (
    <div className="mb-4">
    <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
      <Dropdown
        ariaLabel="Filter by workshop"
        value={value.workshopId}
        onChange={selectWorkshop}
        advanceOnSelect={false}
        searchable
        options={[
          { value: "", label: "All workshops" },
          ...workshops.map((workshop) => ({ value: workshop.id, label: workshop.title }))
        ]}
      />
      <CraftSelect
        ariaLabel="Filter by craft"
        placeholder="All crafts"
        value={value.craftId}
        allowedIds={allowedCraftIds}
        onChange={(craftId) => selectCraft(craftId)}
      />
      {showArtisan ? (
        <Dropdown
          ariaLabel="Filter by artisan"
          value={value.artisanId}
          onChange={selectArtisan}
          advanceOnSelect={false}
          searchable
          options={[
            { value: "", label: "All artisans" },
            ...artisanOptions.map((artisan) => ({ value: artisan.id, label: `${artisan.name} · ${artisan.place}` }))
          ]}
        />
      ) : null}
    </div>
    <CappedListNotice cuts={reportedCuts} className="mt-1.5 grid gap-0.5" />
    </div>
  );
}
