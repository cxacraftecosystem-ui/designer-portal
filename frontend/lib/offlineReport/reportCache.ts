"use client";

import type { ReportSources } from "@/lib/offlineReport/assemble";

const DB_NAME = "design-workshop-report-cache";
const DB_VERSION = 1;
const STORE_MEDIA = "media";
const STORE_SOURCES = "sources";

export const MEDIA_BUDGET_BYTES = 200 * 1024 * 1024;
export const MEDIA_MAX_BYTES = 16 * 1024 * 1024;
export const MAX_AGE_MS = 45 * 24 * 60 * 60 * 1000;

export type CachedMedia = {
  id: string;
  ownerUserId: string;
  blob: Blob;
  mimeType: string;
  sizeBytes: number;
  storedAt: number;
  usedAt: number;
};

export type CachedSources = {
  workshopId: string;
  ownerUserId: string;
  sources: ReportSources;
  storedAt: number;
};

export function planEviction(
  entries: ReadonlyArray<Pick<CachedMedia, "id" | "sizeBytes" | "usedAt">>,
  now: number,
  budget: number = MEDIA_BUDGET_BYTES,
  maxAge: number = MAX_AGE_MS
): string[] {
  const doomed = new Set<string>();
  let total = 0;
  for (const entry of entries) {
    if (now - entry.usedAt > maxAge) doomed.add(entry.id);
    else total += entry.sizeBytes;
  }
  const live = entries.filter((e) => !doomed.has(e.id)).sort((a, b) => a.usedAt - b.usedAt);
  for (const entry of live) {
    if (total <= budget) break;
    doomed.add(entry.id);
    total -= entry.sizeBytes;
  }
  return [...doomed];
}

export function sourcesAreUsable(record: CachedSources | null | undefined, userId: string | null, now: number): boolean {
  if (!record || !userId || record.ownerUserId !== userId) return false;
  return now - record.storedAt <= MAX_AGE_MS;
}

function openDb(): Promise<IDBDatabase | null> {
  if (typeof indexedDB === "undefined") return Promise.resolve(null);
  return new Promise((resolve) => {
    let request: IDBOpenDBRequest;
    try {
      request = indexedDB.open(DB_NAME, DB_VERSION);
    } catch {
      resolve(null);
      return;
    }
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE_MEDIA)) db.createObjectStore(STORE_MEDIA, { keyPath: "id" });
      if (!db.objectStoreNames.contains(STORE_SOURCES)) db.createObjectStore(STORE_SOURCES, { keyPath: "workshopId" });
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => resolve(null);
    request.onblocked = () => resolve(null);
  });
}

function done<T>(request: IDBRequest<T>): Promise<T | null> {
  return new Promise((resolve) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => resolve(null);
  });
}

async function withStore<T>(name: string, mode: IDBTransactionMode, run: (store: IDBObjectStore) => Promise<T | null>): Promise<T | null> {
  const db = await openDb();
  if (!db) return null;
  try {
    const tx = db.transaction(name, mode);
    const result = await run(tx.objectStore(name));
    await new Promise<void>((resolve) => {
      tx.oncomplete = () => resolve();
      tx.onerror = () => resolve();
      tx.onabort = () => resolve();
    });
    return result;
  } catch {
    return null;
  } finally {
    db.close();
  }
}

export async function putSources(workshopId: string, ownerUserId: string, sources: ReportSources): Promise<void> {
  const record: CachedSources = { workshopId, ownerUserId, sources, storedAt: Date.now() };
  await withStore(STORE_SOURCES, "readwrite", (store) => done(store.put(record)));
}

export async function getSources(workshopId: string, userId: string | null): Promise<CachedSources | null> {
  const record = await withStore<CachedSources | undefined>(STORE_SOURCES, "readonly", (store) => done(store.get(workshopId)));
  return record && sourcesAreUsable(record, userId, Date.now()) ? record : null;
}

export async function putMedia(id: string, ownerUserId: string, blob: Blob): Promise<boolean> {
  if (!id || blob.size > MEDIA_MAX_BYTES) return false;
  const now = Date.now();
  const record: CachedMedia = { id, ownerUserId, blob, mimeType: blob.type, sizeBytes: blob.size, storedAt: now, usedAt: now };
  const stored = await withStore(STORE_MEDIA, "readwrite", (store) => done(store.put(record)));
  void pruneMedia();
  return stored !== null;
}

export async function getMedia(id: string, userId: string | null): Promise<Blob | null> {
  if (!userId) return null;
  const record = await withStore<CachedMedia | undefined>(STORE_MEDIA, "readwrite", async (store) => {
    const found = await done(store.get(id));
    if (found && found.ownerUserId === userId) {
      found.usedAt = Date.now();
      store.put(found);
    }
    return found;
  });
  if (!record || record.ownerUserId !== userId) return null;
  return record.blob;
}

export async function hasMedia(id: string, userId: string | null): Promise<boolean> {
  if (!userId) return false;
  const key = await withStore(STORE_MEDIA, "readonly", (store) => done(store.getKey(id)));
  return key !== null && key !== undefined;
}

export async function pruneMedia(now: number = Date.now()): Promise<number> {
  const removed = await withStore<number>(STORE_MEDIA, "readwrite", async (store) => {
    const all = ((await done(store.getAll())) ?? []) as CachedMedia[];
    const doomed = planEviction(all, now);
    for (const id of doomed) store.delete(id);
    return doomed.length;
  });
  await withStore(STORE_SOURCES, "readwrite", async (store) => {
    const all = ((await done(store.getAll())) ?? []) as CachedSources[];
    for (const record of all) if (now - record.storedAt > MAX_AGE_MS) store.delete(record.workshopId);
    return true;
  });
  return removed ?? 0;
}

export async function clearReportCache(): Promise<void> {
  await withStore(STORE_MEDIA, "readwrite", (store) => done(store.clear()));
  await withStore(STORE_SOURCES, "readwrite", (store) => done(store.clear()));
}
