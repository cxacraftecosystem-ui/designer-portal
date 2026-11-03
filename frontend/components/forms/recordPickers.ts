"use client";

import { useEffect, useMemo, useRef, useState } from "react";

import { LIST_PAGE_CEILING, listCut, mergeById, type ListCut } from "@/components/data/cappedList";
import type { CarryScopeState } from "@/components/forms/CarryContextBanner";
import { apiFetch, listResource } from "@/lib/api";
import {
  loadCachedRegister,
  type CachedReferenceOption,
  type RegisterLoadOutcome
} from "@/lib/referenceCache";
import { formatDate } from "@/lib/format";
import {
  cachedListLine,
  deviceLooksOffline,
  workshopEmptyLabel,
  workshopListNotice,
  type WorkshopListState,
  type WorkshopListVoice
} from "@/lib/workshopOptions";
import type { Artisan, Craft, ProductDocumentation, ToolDocumentation } from "@/lib/types";
import { useCraftCatalogue, type CatalogueCraft } from "@/lib/craftCatalogue";

export function catalogueCraftAsCraft(craft: CatalogueCraft): Craft {
  return {
    id: craft.id,
    name: craft.name,
    localName: craft.localName,
    slug: craft.slug,
    categoryId: craft.categoryId,
    subCategoryId: craft.subCategoryId,
    sourceSerial: craft.sourceSerial,
    stateOfOrigin: craft.stateOfOrigin,
    stateOfPractice: craft.stateOfPractice,
    giStatus: craft.giStatus,
    isCatchAll: craft.isCatchAll,
    craftCode: craft.craftCode,
    status: craft.status ?? "ACTIVE"
  };
}

export function useCatalogueCrafts(): { crafts: Craft[]; state: CarryScopeState } {
  const { catalogue, loading } = useCraftCatalogue();
  const crafts = useMemo(() => (catalogue ? catalogue.crafts.map(catalogueCraftAsCraft) : []), [catalogue]);
  return { crafts, state: catalogue ? "loaded" : loading ? "pending" : "unavailable" };
}

export function craftToOption(craft: Craft): CachedReferenceOption {
  return { id: craft.id, label: craft.name, hint: "", filterValue: "" };
}

export function optionToCraft(option: CachedReferenceOption): Craft | null {
  return option.id ? { id: option.id, name: option.label } : null;
}

type PickerRowMissing = "status" | "createdAt";

function asPickerRow<T>(row: Omit<T, PickerRowMissing>): T {
  return row as T;
}

export function artisanToOption(artisan: Artisan): CachedReferenceOption {
  return { id: artisan.id, label: artisan.name, hint: artisan.place, filterValue: artisan.craftId ?? "" };
}

export function optionToArtisan(option: CachedReferenceOption): Artisan | null {
  if (!option.id) return null;
  return asPickerRow<Artisan>({ id: option.id, name: option.label, place: option.hint, craftId: option.filterValue || null });
}

export function productToOption(product: ProductDocumentation): CachedReferenceOption {
  return {
    id: product.id,
    label: product.productName,
    hint: product.artisanName,
    filterValue: product.artisanId ?? "",
    data: { craftName: product.craftName, place: product.place, productType: product.productType, marketDemand: product.marketDemand }
  };
}

export function optionToProduct(option: CachedReferenceOption): ProductDocumentation | null {
  if (!option.id) return null;
  return asPickerRow<ProductDocumentation>({
    id: option.id,
    productName: option.label,
    artisanName: option.hint,
    artisanId: option.filterValue || null,
    craftName: option.data?.craftName ?? "",
    place: option.data?.place ?? "",
    productType: option.data?.productType ?? "",
    marketDemand: option.data?.marketDemand ?? ""
  });
}

export function toolToOption(tool: ToolDocumentation): CachedReferenceOption {
  return {
    id: tool.id,
    label: tool.toolkitName,
    hint: tool.craftName,
    filterValue: tool.craftId ?? "",
    data: { artisanName: tool.artisanName, artisanId: tool.artisanId ?? "", place: tool.place }
  };
}

export function optionToTool(option: CachedReferenceOption): ToolDocumentation | null {
  if (!option.id) return null;
  return asPickerRow<ToolDocumentation>({
    id: option.id,
    toolkitName: option.label,
    craftName: option.hint,
    craftId: option.filterValue || null,
    artisanName: option.data?.artisanName ?? "",
    artisanId: option.data?.artisanId || null,
    place: option.data?.place ?? "",
    maker: "",
    traditionType: ""
  });
}

export function useRecordOffPage<T extends { id: string }>(
  endpoint: string,
  id: string,
  rows: readonly T[]
): T | null {
  return useRecordOffPageState(endpoint, id, rows).record;
}

export function useRecordOffPageState<T extends { id: string }>(
  endpoint: string,
  id: string,
  rows: readonly T[]
): { record: T | null; failed: boolean } {
  const [record, setRecord] = useState<T | null>(null);
  const [failedId, setFailedId] = useState<string | null>(null);
  const attempted = useRef(new Set<string>());

  useEffect(() => {
    if (!id || rows.some((row) => row.id === id)) return;
    const tried = attempted.current;
    if (tried.has(id)) return;
    tried.add(id);
    let cancelled = false;
    let settled = false;
    apiFetch<T>(`${endpoint}/${id}`)
      .then((row) => {
        settled = true;
        if (!cancelled) setRecord(row);
      })
      .catch(() => {
        settled = true;
        if (!cancelled) setFailedId(id);
      });
    return () => {
      cancelled = true;
      if (!settled) tried.delete(id);
    };
  }, [endpoint, id, rows]);

  return { record: record && record.id === id ? record : null, failed: failedId === id && id !== "" };
}

export function useRecordsOffPage<T extends { id: string }>(
  endpoint: string,
  ids: readonly string[],
  rows: readonly T[]
): T[] {
  const [records, setRecords] = useState<Record<string, T>>({});
  const attempted = useRef(new Set<string>());
  const key = ids.join(",");

  useEffect(() => {
    let cancelled = false;
    for (const id of key ? key.split(",") : []) {
      if (!id || rows.some((row) => row.id === id)) continue;
      if (attempted.current.has(id)) continue;
      attempted.current.add(id);
      apiFetch<T>(`${endpoint}/${id}`)
        .then((row) => {
          if (!cancelled) setRecords((current) => ({ ...current, [id]: row }));
        })
        .catch(() => {
        });
    }
    return () => {
      cancelled = true;
    };
  }, [endpoint, key, rows]);

  return useMemo(
    () => (key ? key.split(",") : []).map((id) => records[id]).filter((row): row is T => Boolean(row)),
    [key, records]
  );
}

export type CraftAndArtisanOptions = {
  artisans: Artisan[];
  crafts: Craft[];
  referenceState: CarryScopeState;
  craftCut: ListCut | null;
  craftArtisanCut: ListCut | null;
  craftRosterKey: string;
  artisansLoadedForCraft: string | null;
  craftNotice: string;
  craftEmptyLabel: string;
  craftArtisanNotice: string;
  craftArtisanEmptyLabel: string;
};

export function useCraftAndArtisanOptions({
  craftId,
  craftIds,
  artisanId,
  artisanIds,
  knownArtisans
}: {
  craftId?: string;
  craftIds?: readonly string[];
  artisanId?: string;
  artisanIds?: readonly string[];
  knownArtisans?: readonly Artisan[];
}): CraftAndArtisanOptions {
  const rosterCraftIds = craftIds ?? (craftId ? [craftId] : []);
  const wantedArtisanIds = artisanIds ?? (artisanId ? [artisanId] : []);
  const craftRosterKey = [...rosterCraftIds].sort().join(",");
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const { crafts, state: craftState } = useCatalogueCrafts();
  const [artisanState, setArtisanState] = useState<CarryScopeState>("pending");
  const referenceState: CarryScopeState =
    artisanState === "unavailable" || craftState === "unavailable"
      ? "unavailable"
      : artisanState === "pending" || craftState === "pending"
        ? "pending"
        : "loaded";
  const craftCut: ListCut | null = null;
  const [craftArtisanCut, setCraftArtisanCut] = useState<ListCut | null>(null);
  const [artisansLoadedForCraft, setArtisansLoadedForCraft] = useState<string | null>(null);
  const [artisansFailedForCraft, setArtisansFailedForCraft] = useState<string | null>(null);
  const craftSource: RegisterLoadOutcome = { source: craftState === "loaded" ? "live" : "none", cachedAt: null };
  const [rosterCache, setRosterCache] = useState<{ craftKey: string; cachedAt: string } | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const [artisanOutcome] = await Promise.all([
        loadCachedRegister<Artisan>({
          model: "artisan",
          decode: optionToArtisan,
          encode: artisanToOption,
          fetch: async () => (await listResource<Artisan>("/artisans", { pageSize: LIST_PAGE_CEILING })).items,
          onList: (rows, cachedAt) => {
            if (!cancelled) setArtisans((previous) => (cachedAt ? mergeById(previous, rows) : mergeById(rows, previous)));
          }
        })
      ]);
      if (cancelled) return;
      setArtisanState(artisanOutcome.source === "none" ? "unavailable" : "loaded");
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!craftRosterKey) return;
    const ids = craftRosterKey.split(",");
    let cancelled = false;
    void loadCachedRegister<Artisan>({
      model: "artisan",
      filterValue: craftRosterCacheKey(craftRosterKey),
      decode: optionToArtisan,
      encode: artisanToOption,
      fetch: async () => {
        const result = await listResource<Artisan>("/artisans", {
          craftIds: ids.join(","),
          craftId: ids.length === 1 ? ids[0] : undefined,
          pageSize: LIST_PAGE_CEILING
        });
        if (!cancelled) setCraftArtisanCut(listCut(result, ids.length > 1 ? "artisans of these crafts" : "artisans of this craft"));
        return result.items;
      },
      onList: (rows, cachedAt) => {
        if (cancelled) return;
        setArtisans((previous) => (cachedAt ? mergeById(previous, rows) : mergeById(rows, previous)));
        setArtisansLoadedForCraft(craftRosterKey);
        setArtisansFailedForCraft((failed) => (failed === craftRosterKey ? null : failed));
        setRosterCache(cachedAt ? { craftKey: craftRosterKey, cachedAt } : null);
      }
    }).then((outcome) => {
      if (cancelled || outcome.source !== "none") return;
      setArtisansFailedForCraft(craftRosterKey);
    });
    return () => {
      cancelled = true;
    };
  }, [craftRosterKey]);

  const offPageArtisans = useRecordsOffPage<Artisan>("/artisans", wantedArtisanIds, artisans);
  const allArtisans = useMemo(() => {
    const loaded = offPageArtisans.length ? mergeById(artisans, offPageArtisans) : artisans;
    return knownArtisans?.length ? mergeById(loaded, knownArtisans) : loaded;
  }, [artisans, offPageArtisans, knownArtisans]);

  const online = !deviceLooksOffline();
  const craftList: WorkshopListState<Craft> =
    referenceState === "pending"
      ? { kind: "loading" }
      : referenceState === "unavailable"
        ? { kind: "failed" }
        : {
            kind: "ok",
            rows: crafts,
            total: crafts.length,
            cachedAt: craftSource.source === "cached" ? craftSource.cachedAt : null
          };
  const craftVoice: WorkshopListVoice = {
    table: "field",
    noun: "crafts",
    scoped: false,
    accessList: false,
    cached: true,
    online
  };

  const craftArtisanList: WorkshopListState<Artisan> = !craftRosterKey
    ? { kind: "ok", rows: [], total: 0 }
    : artisansFailedForCraft === craftRosterKey
      ? { kind: "failed" }
      : artisansLoadedForCraft === craftRosterKey
        ? { kind: "ok", rows: [], total: 0 }
        : { kind: "loading" };
  const artisanVoice: WorkshopListVoice = {
    table: "field",
    noun: "artisans",
    scoped: false,
    accessList: false,
    cached: true,
    online
  };

  const craftRosterCount = craftRosterKey
    ? allArtisans.filter((row) => Boolean(row.craftId) && rosterCraftIds.includes(row.craftId as string)).length
    : 0;
  const craftRosterCachedAt =
    rosterCache && rosterCache.craftKey === craftRosterKey && craftRosterCount > 0 ? rosterCache.cachedAt : null;

  return {
    artisans: allArtisans,
    crafts,
    referenceState,
    craftCut,
    craftArtisanCut,
    craftRosterKey,
    artisansLoadedForCraft,
    craftNotice: workshopListNotice(craftList, craftVoice),
    craftEmptyLabel: workshopEmptyLabel(craftList, craftVoice),
    craftArtisanNotice:
      craftArtisanList.kind === "failed"
        ? workshopListNotice(craftArtisanList, artisanVoice)
        : craftRosterCachedAt
          ? cachedListLine(craftRosterCount, "artisans", formatDate(craftRosterCachedAt))
          : "",
    craftArtisanEmptyLabel: craftArtisanList.kind === "ok" ? "" : workshopEmptyLabel(craftArtisanList, artisanVoice)
  };
}

export function craftChangeClearsArtisan({
  nextCraftId,
  artisanId,
  artisans
}: {
  nextCraftId: string;
  artisanId: string;
  artisans: readonly Artisan[];
}): boolean {
  if (!nextCraftId || !artisanId) return false;
  const known = artisans.find((artisan) => artisan.id === artisanId);
  return Boolean(known) && known?.craftId !== nextCraftId;
}

export function craftsChangeClearsArtisans({
  nextCraftIds,
  removedCraftIds,
  artisanIds,
  artisans
}: {
  nextCraftIds: readonly string[];
  removedCraftIds: readonly string[];
  artisanIds: readonly string[];
  artisans: readonly Artisan[];
}): string[] {
  if (!removedCraftIds.length) return [];
  return artisanIds.filter((id) => {
    const known = artisans.find((artisan) => artisan.id === id);
    if (!known || !known.craftId) return false;
    return removedCraftIds.includes(known.craftId) && !nextCraftIds.includes(known.craftId);
  });
}

export const UNLINKED_CRAFT_GROUP = "Unlinked craft";

export function craftNameForArtisan(artisan: Artisan, selectedCrafts: readonly Craft[]): string {
  const own = artisan.craft?.name?.trim();
  if (own) return own;
  return selectedCrafts.find((craft) => craft.id === artisan.craftId)?.name?.trim() ?? "";
}

export function sortArtisansByCraft(artisans: readonly Artisan[], selectedCrafts: readonly Craft[]): Artisan[] {
  const fold = (value: string) => value.trim().toLowerCase();
  const compare = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);
  return [...artisans].sort((left, right) => {
    const leftCraft = fold(craftNameForArtisan(left, selectedCrafts));
    const rightCraft = fold(craftNameForArtisan(right, selectedCrafts));
    const unknown = (leftCraft === "" ? 1 : 0) - (rightCraft === "" ? 1 : 0);
    if (unknown !== 0) return unknown;
    return (
      compare(leftCraft, rightCraft) ||
      compare(fold(left.name ?? ""), fold(right.name ?? "")) ||
      compare(left.name ?? "", right.name ?? "") ||
      compare(left.id, right.id)
    );
  });
}

export function artisanPickerOptions(
  artisans: readonly Artisan[],
  selectedCrafts: readonly Craft[]
): { value: string; label: string; hint?: string; group?: string }[] {
  return sortArtisansByCraft(artisans, selectedCrafts).map((artisan) => ({
    value: artisan.id,
    label: artisan.name?.trim() || "Unnamed artisan",
    hint: artisan.place?.trim() || undefined,
    group: craftNameForArtisan(artisan, selectedCrafts) || UNLINKED_CRAFT_GROUP
  }));
}

export function joinCraftNames(craftIds: readonly string[], crafts: readonly Craft[]): string {
  return craftIds
    .map((id) => crafts.find((craft) => craft.id === id)?.name?.trim())
    .filter((name): name is string => Boolean(name))
    .join(", ");
}

export const CRAFT_NAME_MAX_LENGTH = 180;

export type CraftSelectionVerdict = { refuse: boolean; notice: string };

export function craftSelectionVerdict({
  nextCraftIds,
  currentCraftIds,
  crafts
}: {
  nextCraftIds: readonly string[];
  currentCraftIds: readonly string[];
  crafts: readonly Craft[];
}): CraftSelectionVerdict {
  const next = joinCraftNames(nextCraftIds, crafts);
  if (next.length <= CRAFT_NAME_MAX_LENGTH) return { refuse: false, notice: "" };
  const current = joinCraftNames(currentCraftIds, crafts);
  if (next.length < current.length) {
    return {
      refuse: false,
      notice:
        `Still ${next.length} characters of craft name, and the most allowed is ` +
        `${CRAFT_NAME_MAX_LENGTH}. Keep unticking, or this tool cannot be saved.`
    };
  }
  return {
    refuse: true,
    notice:
      `The craft name this selection makes is ${next.length} characters and the most allowed is ` +
      `${CRAFT_NAME_MAX_LENGTH}. The selection is unchanged — untick a craft, or link fewer of them, and record ` +
      `the rest as a second tool.`
  };
}

export function craftRosterCacheKey(rosterKey: string): string {
  if (!rosterKey) return "";
  const count = rosterKey.split(",").length;
  return `crafts${count}-${fnv1a(rosterKey, 0x811c9dc5)}${fnv1a(rosterKey, 0x9e3779b1)}`;
}

function fnv1a(text: string, seed: number): string {
  let hash = seed;
  for (let index = 0; index < text.length; index += 1) {
    hash ^= text.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193);
  }
  return (hash >>> 0).toString(16).padStart(8, "0");
}
