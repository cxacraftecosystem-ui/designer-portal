/**
 * THE INTERVIEW INSTRUMENT, KEPT FOR OFFLINE (H7, 21 Oct 2026).
 *
 * `/questionnaire` fetched `GET /questionnaire/sections` with no fallback and the service worker does
 * not cache API calls, so the interview form could not open without a connection — and the outbox,
 * which can bank a whole interview with its audio, could never be reached for one. The last copy this
 * account downloaded is kept here, the same way `questionnaireFormCache.ts` keeps a custom form:
 * stamped with its owner, never served to another account, and served only when nothing reached the
 * server (`isUnreachable`), never over a 403, a 404 or a 5xx.
 */

import { apiFetch } from "@/lib/api";
import { isUnreachable } from "@/lib/offline";
import type { QuestionnaireSection } from "@/lib/types";

const DB_NAME = "questionnaire-instrument";
const DB_VERSION = 1;
const STORE = "instrument";

export const INSTRUMENT_CACHE_VERSION = 1;

export type CachedInstrument = {
  /** The owner's user id — the IDB keyPath, so each account has its own copy. */
  key: string;
  schemaVersion: number;
  fetchedAt: string;
  ownerUserId: string;
  sections: QuestionnaireSection[];
};

export type InstrumentRead = {
  sections: QuestionnaireSection[];
  fromCache: boolean;
  cachedAt: string | null;
};

/** May this stored copy be served to this viewer? Never to another account, never without one. */
export function instrumentRecordIsReadable(
  record: CachedInstrument | null | undefined,
  viewerId: string | null
): record is CachedInstrument {
  if (!record) return false;
  if (typeof record.schemaVersion !== "number" || record.schemaVersion > INSTRUMENT_CACHE_VERSION) return false;
  if (!Array.isArray(record.sections)) return false;
  if (!viewerId || !record.ownerUserId) return false;
  return record.ownerUserId === viewerId;
}

export function cachedInstrumentNotice(when: string | null): string {
  return when ? `Offline — showing the questions saved on ${when}.` : "Offline — showing the saved questions.";
}

let dbPromise: Promise<IDBDatabase> | null = null;

function openDb(): Promise<IDBDatabase> {
  if (typeof indexedDB === "undefined") return Promise.reject(new Error("IndexedDB unavailable"));
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE, { keyPath: "key" });
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error ?? new Error("Cannot open the local instrument store"));
    });
    dbPromise.catch(() => {
      dbPromise = null;
    });
  }
  return dbPromise;
}

export async function putCachedInstrument(sections: QuestionnaireSection[], viewerId: string | null): Promise<boolean> {
  if (!viewerId) return false;
  try {
    const db = await openDb();
    const tx = db.transaction(STORE, "readwrite");
    const done = new Promise<void>((resolve, reject) => {
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error ?? new Error("The local instrument store refused a write"));
      tx.onabort = () => reject(tx.error ?? new Error("The local instrument store aborted a write"));
    });
    const record: CachedInstrument = {
      key: viewerId,
      schemaVersion: INSTRUMENT_CACHE_VERSION,
      fetchedAt: new Date().toISOString(),
      ownerUserId: viewerId,
      sections
    };
    tx.objectStore(STORE).put(record);
    await done;
    return true;
  } catch {
    return false;
  }
}

export async function getCachedInstrument(viewerId: string | null): Promise<CachedInstrument | null> {
  if (!viewerId) return null;
  try {
    const db = await openDb();
    const record = await new Promise<CachedInstrument | undefined>((resolve, reject) => {
      const request = db.transaction(STORE, "readonly").objectStore(STORE).get(viewerId);
      request.onsuccess = () => resolve(request.result as CachedInstrument | undefined);
      request.onerror = () => reject(request.error ?? new Error("The local instrument store refused a read"));
    });
    return instrumentRecordIsReadable(record, viewerId) ? record : null;
  } catch {
    return null;
  }
}

/** The instrument, live if the server can be reached and out of storage only when it cannot. */
export async function loadInstrumentWithCache(viewerId: string | null): Promise<InstrumentRead> {
  try {
    const sections = await apiFetch<QuestionnaireSection[]>("/questionnaire/sections");
    void putCachedInstrument(sections, viewerId);
    return { sections, fromCache: false, cachedAt: null };
  } catch (error) {
    if (!isUnreachable(error)) throw error;
    const cached = await getCachedInstrument(viewerId);
    if (!cached) throw error;
    return { sections: cached.sections, fromCache: true, cachedAt: cached.fetchedAt };
  }
}
