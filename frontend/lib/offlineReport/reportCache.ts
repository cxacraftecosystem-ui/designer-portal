"use client";

/**
 * What this browser keeps so a workshop's report can be built with no connection: the report's
 * SOURCES (`GET /design-workshops/{id}/report/sources`) and the PHOTOGRAPHS the report places.
 *
 * WHY A STORE OF ITS OWN. The stages are already on the device — `lib/designWorkshopStore` holds
 * every workshop this browser has opened, and holds an unsynced photograph's bytes until the server
 * confirms them. What it deliberately lets go of is a photograph's bytes once they are safe on the
 * server, and it never held the records a REF names, the questionnaire sittings, the transcripts or
 * the accepted machine-assisted text. Those are copies, not fieldwork, so they live here, where they
 * can be evicted without anybody losing anything: the server still has every one of them.
 *
 * WHAT GETS IN. Only what this account has already OPENED: the sources are fetched when the report
 * screen is opened with a connection, and a photograph is kept when the report screen (or the
 * stage that shows it) has resolved it. Nothing is fetched in the background for workshops nobody
 * looked at.
 *
 * LIMITS AND CLEANUP. Photographs: at most {@link MEDIA_BUDGET_BYTES} in all, none larger than
 * {@link MEDIA_MAX_BYTES}, none kept past {@link MAX_AGE_MS} since it was last used; the
 * least-recently-used go first when the budget is reached ({@link planEviction}, which is pure and
 * unit-tested). Sources: one record per workshop, replaced on every fetch, dropped after
 * {@link MAX_AGE_MS}. Every record is stamped with the account that fetched it and is served to that
 * account only, and {@link clearReportCache} empties both stores — it runs on sign-out.
 */

import type { ReportSources } from "@/lib/offlineReport/assemble";

const DB_NAME = "design-workshop-report-cache";
const DB_VERSION = 1;
const STORE_MEDIA = "media";
const STORE_SOURCES = "sources";

/** The whole photograph cache. A fortnight's workshop at ~1 MB a photograph is well inside it. */
export const MEDIA_BUDGET_BYTES = 200 * 1024 * 1024;
/** One photograph. The server refuses to embed anything larger (`REPORT_IMAGE_MAX_BYTES`). */
export const MEDIA_MAX_BYTES = 16 * 1024 * 1024;
/** Anything not used for this long is evicted, whatever room is left. */
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

/** Which cached photographs to delete: the stale ones, then the least recently used, until it fits. */
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

/** Whether a cached sources record may be served: this account's, and not stale. */
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

/** Apply {@link planEviction} to the photograph store, and drop stale sources records. */
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

/** Empty both stores. Called on sign-out: a shared laptop keeps nobody's copies for the next person. */
export async function clearReportCache(): Promise<void> {
  await withStore(STORE_MEDIA, "readwrite", (store) => done(store.clear()));
  await withStore(STORE_SOURCES, "readwrite", (store) => done(store.clear()));
}
