"use client";

import { useEffect, useMemo, useState } from "react";

import { cutOf, LIST_PAGE_CEILING, mergeById, type ListCut } from "@/components/data/cappedList";
import type { CarryScopeState } from "@/components/forms/CarryContextBanner";
import type { DropdownOption } from "@/components/ui/Dropdown";
import { listResource } from "@/lib/api";
import type { Artisan } from "@/lib/types";

export const ARTISAN_PAGE_BUDGET = 5;

export type InterviewWorkshopScope = {
  workshopId: string;
  designWorkshopId: string;
};

export function interviewScopeKey(scope: InterviewWorkshopScope): string {
  return `${scope.workshopId}|${scope.designWorkshopId}`;
}

export function interviewScopeIsNarrowed(scope: InterviewWorkshopScope): boolean {
  return Boolean(scope.workshopId || scope.designWorkshopId);
}

export function artisanScopeNoun(scope: InterviewWorkshopScope): string {
  return interviewScopeIsNarrowed(scope) ? "artisans at this workshop" : "artisans";
}

export function workshopArtisanParams(
  scope: InterviewWorkshopScope,
  page: number
): Record<string, string | number | undefined> {
  return {
    workshopIds: scope.workshopId || undefined,
    designWorkshopId: scope.workshopId ? undefined : scope.designWorkshopId || undefined,
    page,
    pageSize: LIST_PAGE_CEILING
  };
}

export function workshopScopeSettling(workshop: {
  loading: boolean;
  touched: boolean;
  workshopId: string;
  workshops: readonly unknown[];
  routesToDesignWorkshop?: boolean;
}): boolean {
  if (workshop.routesToDesignWorkshop) return false;
  if (workshop.loading) return true;
  if (workshop.touched || workshop.workshopId) return false;
  return workshop.workshops.length > 0;
}

export function artisanPickerOptionLabel(artisan: Artisan): string {
  return `${artisan.name} - ${artisan.craft?.name ?? "No craft"} - ${artisan.place}`;
}

export function artisanPickerOptions({
  scoped,
  known,
  selectedIds
}: {
  scoped: readonly Artisan[];
  known: readonly Artisan[];
  selectedIds: readonly string[];
}): DropdownOption[] {
  const offered = new Set(scoped.map((artisan) => artisan.id));
  const rescued = selectedIds
    .filter((id) => id && !offered.has(id))
    .map((id) => known.find((artisan) => artisan.id === id))
    .filter((artisan): artisan is Artisan => Boolean(artisan));
  return mergeById(scoped as Artisan[], rescued).map((artisan) => ({
    value: artisan.id,
    label: artisanPickerOptionLabel(artisan)
  }));
}

export function primaryInterviewArtisanId(selectedIds: readonly string[]): string {
  return selectedIds.find((id) => Boolean(id)) ?? "";
}

export function artisanSetKey(
  selectedIds: readonly string[],
  scope: InterviewWorkshopScope
): string {
  const ids = [...new Set(selectedIds.filter(Boolean))].sort().join(",");
  return ids ? `${interviewScopeKey(scope)}|${ids}` : "";
}

export function artisansNotAtWorkshop({
  selectedIds,
  offeredIds,
  loadedForScope,
  scopeKey,
  narrowed,
  cut
}: {
  selectedIds: readonly string[];
  offeredIds: readonly string[];
  loadedForScope: string | null;
  scopeKey: string;
  narrowed: boolean;
  cut: ListCut | null;
}): string[] {
  if (!narrowed) return [];
  if (loadedForScope !== scopeKey) return [];
  if (cut) return [];
  const offered = new Set(offeredIds);
  return selectedIds.filter((id) => Boolean(id) && !offered.has(id));
}

export const OUT_OF_WORKSHOP_NAMES_SHOWN = 4;

export function outOfWorkshopNotice(names: readonly string[]): string {
  const clean = names.map((name) => name.trim()).filter(Boolean);
  if (clean.length === 0) return "";
  const shown = clean.slice(0, OUT_OF_WORKSHOP_NAMES_SHOWN);
  const remainder = clean.length - shown.length;
  const list = shown.join(", ") + (remainder > 0 ? ` and ${remainder} more` : "");
  const subject = clean.length === 1 ? `${list} is` : `${list} are`;
  return (
    `${subject} not recorded at this workshop yet. They stay ticked — saving this interview here ` +
    `is what links them to it. Untick anyone who should not be on it.`
  );
}

export function artisanPickerEmptyLabel({
  loadedForScope,
  scopeKey,
  failed,
  narrowed
}: {
  loadedForScope: string | null;
  scopeKey: string;
  failed: boolean;
  narrowed: boolean;
}): string {
  if (failed) return "The artisan list could not be loaded. Pick the workshop again to retry.";
  if (loadedForScope !== scopeKey) return "Loading artisans…";
  if (narrowed) return "No artisans are recorded at this workshop yet";
  return "No artisans are recorded yet";
}

export type WorkshopArtisans = {
  scoped: Artisan[];
  known: Artisan[];
  loadedForScope: string | null;
  cut: ListCut | null;
  failed: boolean;
  referenceState: CarryScopeState;
};

export function useWorkshopArtisans({
  scope,
  settling
}: {
  scope: InterviewWorkshopScope;
  settling: boolean;
}): WorkshopArtisans {
  const [scoped, setScoped] = useState<Artisan[]>([]);
  const [known, setKnown] = useState<Artisan[]>([]);
  const [loadedForScope, setLoadedForScope] = useState<string | null>(null);
  const [cut, setCut] = useState<ListCut | null>(null);
  const [failed, setFailed] = useState(false);
  const [referenceState, setReferenceState] = useState<CarryScopeState>("pending");

  useEffect(() => {
    let cancelled = false;
    listResource<Artisan>("/artisans", { pageSize: LIST_PAGE_CEILING })
      .then((result) => {
        if (cancelled) return;
        setKnown((previous) => mergeById(previous, result.items));
        setReferenceState("loaded");
      })
      .catch(() => {
        if (!cancelled) setReferenceState("unavailable");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const scopeKey = interviewScopeKey(scope);
  const workshopId = scope.workshopId;
  const designWorkshopId = scope.designWorkshopId;
  useEffect(() => {
    if (settling) return;
    let cancelled = false;
    setScoped([]);
    setLoadedForScope(null);
    setCut(null);
    setFailed(false);
    const current: InterviewWorkshopScope = { workshopId, designWorkshopId };
    (async () => {
      try {
        const first = await listResource<Artisan>("/artisans", workshopArtisanParams(current, 1));
        if (cancelled) return;
        const lastPage = Math.min(first.pages || 1, ARTISAN_PAGE_BUDGET);
        const rest =
          lastPage > 1
            ? await Promise.all(
                Array.from({ length: lastPage - 1 }, (_, index) =>
                  listResource<Artisan>("/artisans", workshopArtisanParams(current, index + 2))
                )
              )
            : [];
        if (cancelled) return;
        const rows = [first, ...rest].flatMap((result) => result.items);
        setScoped(rows);
        setKnown((previous) => mergeById(previous, rows));
        setCut(cutOf(rows.length, first.total, artisanScopeNoun(current)));
        setLoadedForScope(interviewScopeKey(current));
      } catch {
        if (!cancelled) setFailed(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [scopeKey, workshopId, designWorkshopId, settling]);

  return useMemo(
    () => ({ scoped, known, loadedForScope, cut, failed, referenceState }),
    [scoped, known, loadedForScope, cut, failed, referenceState]
  );
}
