import { isUnreachable } from "@/lib/offline";
import { getQuestionnaire, type QForm } from "@/lib/questionnaireForms";

const DB_NAME = "questionnaire-forms";
const DB_VERSION = 1;
const STORE = "forms";

export const QUESTIONNAIRE_FORM_CACHE_VERSION = 1;

export type CachedQuestionnaireForm = {
  key: string;
  schemaVersion: number;
  fetchedAt: string;
  includeRetired: boolean;
  version: number;
  ownerUserId: string | null;
  form: QForm;
};

export type QuestionnaireFormRead = {
  form: QForm;
  fromCache: boolean;
  cachedAt: string | null;
  cachedVersion: number | null;
};

export function questionnaireCacheKey(id: string, includeRetired: boolean): string {
  return `${id}::${includeRetired ? "all" : "active"}`;
}

export function cacheRecordIsReadable(record: CachedQuestionnaireForm | null | undefined, viewerId: string | null): record is CachedQuestionnaireForm {
  if (!record) return false;
  if (typeof record.schemaVersion !== "number" || record.schemaVersion > QUESTIONNAIRE_FORM_CACHE_VERSION) return false;
  if (!record.form || !Array.isArray(record.form.sections)) return false;
  if (!viewerId || !record.ownerUserId) return false;
  return record.ownerUserId === viewerId;
}

export function cachedQuestionnaireNotice(when: string | null, version: number): string {
  const whenPart = when && when !== "-" ? ` on ${when}` : "";
  return (
    `You are reading the copy this browser downloaded${whenPart} (version ${version}). You can read it ` +
    "and check what has already been recorded. Connect to the internet to save answers."
  );
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
      request.onerror = () => reject(request.error ?? new Error("Cannot open the local questionnaire store"));
    });
    dbPromise.catch(() => {
      dbPromise = null;
    });
  }
  return dbPromise;
}

function req<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error("The local questionnaire store refused a write"));
  });
}

const writeChain = new Map<string, Promise<void>>();

export async function putCachedQuestionnaire(
  form: QForm,
  options: { includeRetired: boolean; viewerId: string | null }
): Promise<boolean> {
  const key = questionnaireCacheKey(form.id, options.includeRetired);
  if (!options.viewerId) return false;

  const previous = writeChain.get(key) ?? Promise.resolve();
  const attempt = previous.then(async () => {
    const record: CachedQuestionnaireForm = {
      key,
      schemaVersion: QUESTIONNAIRE_FORM_CACHE_VERSION,
      fetchedAt: new Date().toISOString(),
      includeRetired: options.includeRetired,
      version: form.version,
      ownerUserId: options.viewerId,
      form
    };
    const db = await openDb();
    const tx = db.transaction(STORE, "readwrite");
    const done = new Promise<void>((resolve, reject) => {
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error ?? new Error("The local questionnaire store refused a write"));
      tx.onabort = () => reject(tx.error ?? new Error("The local questionnaire store aborted a write"));
    });
    tx.objectStore(STORE).put(record);
    await done;
  });

  writeChain.set(
    key,
    attempt.catch(() => undefined)
  );
  try {
    await attempt;
    return true;
  } catch {
    return false;
  }
}

export async function getCachedQuestionnaire(
  id: string,
  options: { includeRetired: boolean; viewerId: string | null }
): Promise<CachedQuestionnaireForm | null> {
  try {
    const db = await openDb();
    const tx = db.transaction(STORE, "readonly");
    const record = await req<CachedQuestionnaireForm | undefined>(tx.objectStore(STORE).get(questionnaireCacheKey(id, options.includeRetired)));
    return cacheRecordIsReadable(record, options.viewerId) ? record : null;
  } catch {
    return null;
  }
}

export async function loadQuestionnaireWithCache(
  id: string,
  options: { includeRetired: boolean; viewerId: string | null }
): Promise<QuestionnaireFormRead> {
  try {
    const form = await getQuestionnaire(id, { includeRetired: options.includeRetired });
    void putCachedQuestionnaire(form, options);
    return { form, fromCache: false, cachedAt: null, cachedVersion: null };
  } catch (error) {
    if (!isUnreachable(error)) throw error;
    const cached = await getCachedQuestionnaire(id, options);
    if (!cached) throw error;
    return { form: cached.form, fromCache: true, cachedAt: cached.fetchedAt, cachedVersion: cached.version };
  }
}
