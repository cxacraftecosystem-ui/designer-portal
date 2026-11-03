"use client";

export type ReferenceRegister = "craft" | "artisan" | "product" | "tool";

export type CachedReferenceOption = {
  id: string;
  label: string;
  hint: string;
  filterValue: string;
  data?: Record<string, string>;
};

export const REFERENCE_CACHE_VERSION = 1;

export type CachedReferenceList = {
  key: string;
  schemaVersion: number;
  model: ReferenceRegister;
  filteredBy: string;
  fetchedAt: string;
  items: CachedReferenceOption[];
};

const DB_NAME = "field-repo-references";
const DB_VERSION = 1;
const STORE_REGISTERS = "registers";
const STORE_ADDRESS = "address";

function safeName(raw: string): string {
  const collapsed = raw.trim().replace(/[^A-Za-z0-9._-]+/g, "_");
  const trimmed = collapsed.replace(/^[._]+/, "").replace(/[._]+$/, "").slice(0, 80);
  return trimmed || "unnamed";
}

export function referenceCacheKey(model: ReferenceRegister, filterValue = ""): string {
  const filter = filterValue.trim() ? filterValue : "_";
  return [model, "ALL", filter].map(safeName).join("__");
}

export function narrowedTo(items: readonly CachedReferenceOption[], parentValue: string): CachedReferenceOption[] {
  if (!parentValue.trim()) return [...items];
  return items.filter((item) => !item.filterValue || item.filterValue === parentValue);
}

export function referenceRecordIsReadable(record: CachedReferenceList | null | undefined): record is CachedReferenceList {
  if (!record) return false;
  if (typeof record.schemaVersion !== "number" || record.schemaVersion > REFERENCE_CACHE_VERSION) return false;
  return Array.isArray(record.items);
}

let dbPromise: Promise<IDBDatabase> | null = null;

function openDb(): Promise<IDBDatabase> {
  if (typeof indexedDB === "undefined") return Promise.reject(new Error("IndexedDB unavailable"));
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains(STORE_REGISTERS)) db.createObjectStore(STORE_REGISTERS, { keyPath: "key" });
        if (!db.objectStoreNames.contains(STORE_ADDRESS)) db.createObjectStore(STORE_ADDRESS, { keyPath: "key" });
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error ?? new Error("Cannot open the local reference store"));
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
    request.onerror = () => reject(request.error ?? new Error("The local reference store refused a request"));
  });
}

const writeChain = new Map<string, Promise<void>>();

export async function putCachedRegister(
  model: ReferenceRegister,
  items: readonly CachedReferenceOption[],
  options?: { filterValue?: string }
): Promise<CachedReferenceList | null> {
  const filteredBy = options?.filterValue?.trim() ?? "";
  const key = referenceCacheKey(model, filteredBy);

  const previous = writeChain.get(key) ?? Promise.resolve();
  const attempt = previous.then(async (): Promise<CachedReferenceList | null> => {
    const existing = await readRegister(key);
    if (items.length === 0 && existing && existing.items.length > 0) return existing;
    const record: CachedReferenceList = {
      key,
      schemaVersion: REFERENCE_CACHE_VERSION,
      model,
      filteredBy,
      fetchedAt: new Date().toISOString(),
      items: [...items]
    };
    const db = await openDb();
    const tx = db.transaction(STORE_REGISTERS, "readwrite");
    const done = new Promise<void>((resolve, reject) => {
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error ?? new Error("The local reference store refused a write"));
      tx.onabort = () => reject(tx.error ?? new Error("The local reference store aborted a write"));
    });
    tx.objectStore(STORE_REGISTERS).put(record);
    await done;
    return record;
  });

  writeChain.set(
    key,
    attempt.then(
      () => undefined,
      () => undefined
    )
  );
  try {
    return await attempt;
  } catch {
    return null;
  }
}

async function readRegister(key: string): Promise<CachedReferenceList | null> {
  try {
    const db = await openDb();
    const tx = db.transaction(STORE_REGISTERS, "readonly");
    const record = await req<CachedReferenceList | undefined>(tx.objectStore(STORE_REGISTERS).get(key));
    return referenceRecordIsReadable(record) ? record : null;
  } catch {
    return null;
  }
}

export async function getCachedRegister(
  model: ReferenceRegister,
  options?: { filterValue?: string }
): Promise<CachedReferenceList | null> {
  return readRegister(referenceCacheKey(model, options?.filterValue?.trim() ?? ""));
}

export type RegisterLoadOutcome = { source: "live" | "cached" | "none"; cachedAt: string | null };

export async function loadCachedRegister<Row>(load: {
  model: ReferenceRegister;
  filterValue?: string;
  decode: (option: CachedReferenceOption) => Row | null;
  encode: (row: Row) => CachedReferenceOption;
  fetch: () => Promise<Row[]>;
  onList: (rows: Row[], cachedAt: string | null) => void;
}): Promise<RegisterLoadOutcome> {
  const { model, filterValue = "", decode, encode, fetch, onList } = load;
  let outcome: RegisterLoadOutcome = { source: "none", cachedAt: null };

  const cached = await getCachedRegister(model, { filterValue });
  if (cached) {
    const rows: Row[] = [];
    for (const option of cached.items) {
      const row = decode(option);
      if (row) rows.push(row);
    }
    onList(rows, cached.fetchedAt);
    outcome = { source: "cached", cachedAt: cached.fetchedAt };
  }

  let fetched: Row[] | null = null;
  try {
    fetched = await fetch();
  } catch {
  }
  if (fetched) {
    onList(fetched, null);
    outcome = { source: "live", cachedAt: null };
    void putCachedRegister(model, fetched.map(encode), { filterValue });
  }
  return outcome;
}

export const DW_CACHEABLE_REFERENCE_MODELS = [
  "Artisan",
  "Craft",
  "Process",
  "ProductDocumentation",
  "ToolDocumentation",
  "QuestionnaireInterview",
  "DwParticipant",
  "DwPrototype",
  "DwSketch",
  "DwCostSheet",
  "DwFinalProduct"
] as const;

export type DwCacheableReferenceModel = (typeof DW_CACHEABLE_REFERENCE_MODELS)[number];

export function isDwCacheableReferenceModel(model: string | null | undefined): model is DwCacheableReferenceModel {
  return typeof model === "string" && (DW_CACHEABLE_REFERENCE_MODELS as readonly string[]).includes(model);
}

export function isWorkshopInternalReferenceModel(model: string | null | undefined): boolean {
  return typeof model === "string" && /^Dw[A-Z]/.test(model);
}

export function stageReferenceCacheOwner(
  model: string,
  scope: string | null | undefined,
  workshopId: string
): string {
  if (isWorkshopInternalReferenceModel(model)) return workshopId;
  return (scope ?? "").toUpperCase() === "WORKSHOP" ? workshopId : "ALL";
}

export function stageReferenceCacheKey(model: string, scope: string | null | undefined, workshopId: string): string {
  return ["dw", model, stageReferenceCacheOwner(model, scope, workshopId)].map(safeName).join("__");
}

export type CachedStageReferences<Payload> = {
  key: string;
  schemaVersion: number;
  model: string;
  owner: string;
  fetchedAt: string;
  payload: Payload;
};

export function stageReferenceRecordIsReadable<Payload>(
  record: CachedStageReferences<Payload> | null | undefined
): record is CachedStageReferences<Payload> {
  if (!record) return false;
  if (typeof record.schemaVersion !== "number" || record.schemaVersion > REFERENCE_CACHE_VERSION) return false;
  return Boolean(record.payload);
}

export async function getCachedStageReferences<Payload>(
  model: string,
  scope: string | null | undefined,
  workshopId: string
): Promise<CachedStageReferences<Payload> | null> {
  if (!isDwCacheableReferenceModel(model)) return null;
  if (isWorkshopInternalReferenceModel(model) && !workshopId.trim()) return null;
  try {
    const db = await openDb();
    const tx = db.transaction(STORE_REGISTERS, "readonly");
    const record = await req<CachedStageReferences<Payload> | undefined>(
      tx.objectStore(STORE_REGISTERS).get(stageReferenceCacheKey(model, scope, workshopId))
    );
    return stageReferenceRecordIsReadable(record) ? record : null;
  } catch {
    return null;
  }
}

export async function putCachedStageReferences<Payload>(
  model: string,
  scope: string | null | undefined,
  workshopId: string,
  payload: Payload,
  isEmpty: (payload: Payload) => boolean
): Promise<CachedStageReferences<Payload> | null> {
  if (!isDwCacheableReferenceModel(model)) return null;
  if (isWorkshopInternalReferenceModel(model) && !workshopId.trim()) return null;
  const key = stageReferenceCacheKey(model, scope, workshopId);
  const owner = stageReferenceCacheOwner(model, scope, workshopId);

  const previous = writeChain.get(key) ?? Promise.resolve();
  const attempt = previous.then(async (): Promise<CachedStageReferences<Payload> | null> => {
    const existing = await getCachedStageReferences<Payload>(model, scope, workshopId);
    if (isEmpty(payload) && existing && !isEmpty(existing.payload)) return existing;
    const record: CachedStageReferences<Payload> = {
      key,
      schemaVersion: REFERENCE_CACHE_VERSION,
      model,
      owner,
      fetchedAt: new Date().toISOString(),
      payload
    };
    const db = await openDb();
    const tx = db.transaction(STORE_REGISTERS, "readwrite");
    const done = new Promise<void>((resolve, reject) => {
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error ?? new Error("The local reference store refused a write"));
      tx.onabort = () => reject(tx.error ?? new Error("The local reference store aborted a write"));
    });
    tx.objectStore(STORE_REGISTERS).put(record);
    await done;
    return record;
  });

  writeChain.set(
    key,
    attempt.then(
      () => undefined,
      () => undefined
    )
  );
  try {
    return await attempt;
  } catch {
    return null;
  }
}

export type StageReferenceLoadOutcome = { source: "live" | "cached" | "none"; cachedAt: string | null; error: unknown };

export async function loadStageReferences<Payload>(load: {
  workshopId: string;
  model: string;
  scope: string | null | undefined;
  isEmpty: (payload: Payload) => boolean;
  fetch: () => Promise<Payload>;
  onPayload: (payload: Payload, cachedAt: string | null) => void;
}): Promise<StageReferenceLoadOutcome> {
  const { workshopId, model, scope, isEmpty, fetch, onPayload } = load;
  const cacheable = Boolean(workshopId) && isDwCacheableReferenceModel(model);
  let outcome: StageReferenceLoadOutcome = { source: "none", cachedAt: null, error: null };

  if (cacheable) {
    const cached = await getCachedStageReferences<Payload>(model, scope, workshopId);
    if (cached) {
      onPayload(cached.payload, cached.fetchedAt);
      outcome = { source: "cached", cachedAt: cached.fetchedAt, error: null };
    }
  }

  let landed: { value: Payload } | null = null;
  try {
    landed = { value: await fetch() };
  } catch (error) {
    outcome = { ...outcome, error };
  }
  if (landed) {
    onPayload(landed.value, null);
    outcome = { source: "live", cachedAt: null, error: null };
    if (cacheable) {
      void putCachedStageReferences(model, scope, workshopId, landed.value, isEmpty);
    }
  }
  return outcome;
}

export const ADDRESS_REFERENCE_KEY = "address-reference";

export type CachedAddressReference<Payload> = {
  key: typeof ADDRESS_REFERENCE_KEY;
  schemaVersion: number;
  version: number;
  fetchedAt: string;
  payload: Payload;
};

export async function getCachedAddressReference<Payload>(): Promise<CachedAddressReference<Payload> | null> {
  try {
    const db = await openDb();
    const tx = db.transaction(STORE_ADDRESS, "readonly");
    const record = await req<CachedAddressReference<Payload> | undefined>(
      tx.objectStore(STORE_ADDRESS).get(ADDRESS_REFERENCE_KEY)
    );
    if (!record || typeof record.schemaVersion !== "number") return null;
    if (record.schemaVersion > REFERENCE_CACHE_VERSION) return null;
    if (!record.payload) return null;
    return record;
  } catch {
    return null;
  }
}

export async function putCachedAddressReference<Payload extends { version?: number }>(
  payload: Payload
): Promise<CachedAddressReference<Payload> | null> {
  const version = typeof payload?.version === "number" ? payload.version : 0;
  const previous = writeChain.get(ADDRESS_REFERENCE_KEY) ?? Promise.resolve();
  const attempt = previous.then(async (): Promise<CachedAddressReference<Payload> | null> => {
    const existing = await getCachedAddressReference<Payload>();
    const record: CachedAddressReference<Payload> = {
      key: ADDRESS_REFERENCE_KEY,
      schemaVersion: REFERENCE_CACHE_VERSION,
      version,
      fetchedAt: new Date().toISOString(),
      payload: existing && existing.version === version ? existing.payload : payload
    };
    const db = await openDb();
    const tx = db.transaction(STORE_ADDRESS, "readwrite");
    const done = new Promise<void>((resolve, reject) => {
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error ?? new Error("The local reference store refused a write"));
      tx.onabort = () => reject(tx.error ?? new Error("The local reference store aborted a write"));
    });
    tx.objectStore(STORE_ADDRESS).put(record);
    await done;
    return record;
  });

  writeChain.set(
    ADDRESS_REFERENCE_KEY,
    attempt.then(
      () => undefined,
      () => undefined
    )
  );
  try {
    return await attempt;
  } catch {
    return null;
  }
}
