"use client";

import { ApiError, sessionOwesPasswordChange } from "@/lib/api";
import {
  adoptStageRegistry,
  createDesignWorkshop,
  entryDataOf,
  fetchStageRegistry,
  isFilled,
  listDesignWorkshops,
  newClientKey,
  patchDesignWorkshop,
  peekStageRegistry,
  recordDesignWorkshopDictationConsent,
  saveDesignWorkshopStage,
  type DwDetail,
  type DwEntity,
  type DwEntryData,
  type DwRegistry,
  type DwRow,
  type DwSaveEntry,
  type DwStage,
  type DwStageCompleteness,
  type DwStageData,
  type DwSummary,
  type DwValue,
  type DwStageProvenance,
  DW_COPIED_SOURCE
} from "@/lib/designWorkshops";
import {
  CUSTOM_ENTITY_KEY,
  adoptCustomDefinition,
  customFieldsForStage,
  fetchCustomDefinition,
  type DwCustomDefinition,
  type DwCustomField
} from "@/lib/customSections";
import { uploadMediaBatch } from "@/lib/media";
import { wantsTranscription } from "@/lib/transcript/uploadChoice";
import {
  DESIGN_WORKSHOP_CREATE_REFUSAL,
  canCreateDesignWorkshops,
  canRunDesignWorkshops
} from "@/lib/permissions";
import type { User, UserRole } from "@/lib/types";
import { deviceLooksOffline } from "@/lib/workshopOptions";
import {
  isCredentialExpiry,
  isPasswordChangeRefusal,
  isSchemaRefusal,
  isUnreachable,
  schemaRefusalError,
  serverAskedForTime,
  triageFailure
} from "@/lib/failureTriage";
import { APP_RUN_ID, blocksRetry } from "@/lib/offline";
import { sessionTitle } from "@/lib/naming";

const DB_NAME = "design-workshop-drafts";

const DB_VERSION = 1;

const STORE_DRAFTS = "drafts";
const STORE_MEDIA = "media";
const STORE_REGISTRY = "registry";
const MEDIA_BY_DRAFT = "byDraft";

export const DW_DRAFT_SCHEMA_VERSION = 4;

const PRE_SKEW_POLICY_RUN = "recorded-before-the-skew-retry-policy";

export const LOCAL_ID_PREFIX = "dwlocal-";

export const LOCAL_MEDIA_PREFIX = "dwlocal:";

const REGISTRY_KEEP = 3;

export function isLocalWorkshopId(id: string): boolean {
  return id.startsWith(LOCAL_ID_PREFIX);
}

export function isLocalMediaRef(value: string): boolean {
  return value.startsWith(LOCAL_MEDIA_PREFIX);
}

export type DwDraftHeader = {
  title: string;
  templateId: string;
  status: string;
  workshopKind: string | null;
  craftName: string | null;
  clusterName: string | null;
  state: string | null;
  district: string | null;
  startDate: string | null;
  endDate: string | null;
  workshopId: string | null;
  notes: string | null;
  designerUserId: string | null;
  designerUserIds: string[];
  workshopCode: string | null;
  venue: string | null;
  designerName: string | null;
  handedOnAt?: string | null;
};

export type DwDraftFailure = {
  message: string;
  permanent: boolean;
  skewRun?: string | null;
  at: number;
  attempts: number;
};

export type DwDraftStage = {
  stageKey: string;
  singletons: Record<string, DwEntryData>;
  collections: Record<string, DwRow[]>;
  removedFrom: string[];
  copiedFrom?: Record<string, string>;
  custom?: DwEntryData;
  provenance?: DwStageProvenance;
  foldNote?: string | null;
  unknownSingleton?: Record<string, DwEntryData>;
  updatedAt: number;
  dirtyAt: number | null;
  lastPushedAt: number | null;
  serverLoadedAt: number | null;
  completeness: DwStageCompleteness | null;
  failure: DwDraftFailure | null;
};

export type DwDraftConsent = {
  decision: "GRANTED" | "REFUSED" | string;
  recordedAt: string;
  recordedById: string | null;
  recordedByName: string | null;
  synced: boolean;
};

export type DwDraft = {
  schemaVersion: number;
  localId: string;
  remoteId: string | null;
  header: DwDraftHeader;
  headerDirtyAt: number | null;
  headerDirtyKeys?: string[];
  createSentAt?: number | null;
  stages: Record<string, DwDraftStage>;
  createdAt: number;
  updatedAt: number;
  registryVersion: string;
  customSchemaVersion?: string;
  customDefinition?: DwCustomDefinition | null;
  consent?: DwDraftConsent | null;
  ownerUserId: string | null;
  lastSyncedAt: number | null;
  failure: DwDraftFailure | null;
};

export type DwDraftMedia = {
  id: string;
  localId: string;
  blob: Blob | null;
  name: string;
  mimeType: string;
  sizeBytes: number;
  capturedAt: number;
  stageKey: string | null;
  entityKey: string | null;
  fieldKey: string | null;
  clientKey: string | null;
  caption: string | null;
  remoteMediaId: string | null;
  confirmedAt: number | null;
  attempts: number;
  lastError: string | null;
  failure?: DwDraftFailure | null;
  transcribe?: boolean;
};

type RegistryRecord = { version: string; registry: DwRegistry; storedAt: number };

let dbPromise: Promise<IDBDatabase> | null = null;

function openDb(): Promise<IDBDatabase> {
  if (typeof indexedDB === "undefined") return Promise.reject(new Error("IndexedDB unavailable"));
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains(STORE_DRAFTS)) {
          db.createObjectStore(STORE_DRAFTS, { keyPath: "localId" });
        }
        if (!db.objectStoreNames.contains(STORE_MEDIA)) {
          const media = db.createObjectStore(STORE_MEDIA, { keyPath: "id" });
          media.createIndex(MEDIA_BY_DRAFT, "localId", { unique: false });
        }
        if (!db.objectStoreNames.contains(STORE_REGISTRY)) {
          db.createObjectStore(STORE_REGISTRY, { keyPath: "version" });
        }
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error ?? new Error("Couldn't open the work saved on this device."));
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
    request.onerror = () => reject(request.error ?? new Error("Couldn't save to this device."));
  });
}

async function transact<T>(
  names: string[],
  mode: IDBTransactionMode,
  body: (stores: Record<string, IDBObjectStore>) => Promise<T>
): Promise<T> {
  const db = await openDb();
  const transaction = db.transaction(names, mode);
  const stores: Record<string, IDBObjectStore> = {};
  for (const name of names) stores[name] = transaction.objectStore(name);
  const result = await body(stores);
  await new Promise<void>((resolve, reject) => {
    transaction.oncomplete = () => resolve();
    transaction.onabort = () => reject(transaction.error ?? new Error("Saving to this device was interrupted."));
    transaction.onerror = () => reject(transaction.error ?? new Error("Couldn't save to this device."));
  });
  return result;
}

function reTriagedFailure(raw: unknown): unknown {
  const failure = raw as { permanent?: unknown } | null | undefined;
  if (!failure || failure.permanent !== true) return raw ?? null;
  return { ...failure, skewRun: PRE_SKEW_POLICY_RUN };
}

function migrateDraft(raw: Record<string, unknown>): DwDraft {
  let document = raw;
  let version = typeof document.schemaVersion === "number" ? document.schemaVersion : 0;
  while (version < DW_DRAFT_SCHEMA_VERSION) {
    switch (version) {
      case 0:
        break;
      case 1:
        document = {
          ...document,
          stages: Object.fromEntries(
            Object.entries((document.stages ?? {}) as Record<string, Record<string, unknown>>).map(
              ([key, stage]) => {
                const reconciled =
                  stage.dirtyAt === null ||
                  stage.lastPushedAt !== null ||
                  stageHoldsSomething(stage as unknown as DwDraftStage);
                return [
                  key,
                  {
                    ...stage,
                    serverLoadedAt: reconciled ? ((stage.updatedAt as number | undefined) ?? Date.now()) : null
                  }
                ];
              }
            )
          )
        };
        break;
      case 2:
        document = {
          ...document,
          failure: reTriagedFailure(document.failure),
          stages: Object.fromEntries(
            Object.entries((document.stages ?? {}) as Record<string, Record<string, unknown>>).map(
              ([key, stage]) => [key, { ...stage, failure: reTriagedFailure(stage.failure) }]
            )
          )
        };
        break;
      case 3:
        document = {
          ...document,
          header: {
            ...((document.header ?? {}) as Record<string, unknown>),
            designerUserIds: Array.isArray((document.header as Record<string, unknown> | undefined)?.designerUserIds)
              ? (document.header as Record<string, unknown>).designerUserIds
              : []
          }
        };
        break;
      default:
        return document as unknown as DwDraft;
    }
    version += 1;
    document = { ...document, schemaVersion: version };
  }
  return document as unknown as DwDraft;
}

const listeners = new Set<() => void>();
let cache: DwDraft[] = [];

export type DwStoreHealth = {
  readFailedAt: number | null;
  writeFailedAt: number | null;
};

let health: DwStoreHealth = { readFailedAt: null, writeFailedAt: null };

export function getStoreHealth(): DwStoreHealth {
  return health;
}

const SERVER_HEALTH: DwStoreHealth = { readFailedAt: null, writeFailedAt: null };
export function getServerStoreHealth(): DwStoreHealth {
  return SERVER_HEALTH;
}

export function storeIsAnswering(state: DwStoreHealth = health): boolean {
  return state.readFailedAt === null && state.writeFailedAt === null;
}

function noteStoreFailure(kind: "read" | "write"): void {
  const now = Date.now();
  health = kind === "read" ? { ...health, readFailedAt: now } : { ...health, writeFailedAt: now };
}

function noteStoreRead(): void {
  if (health.readFailedAt === null) return;
  health = { ...health, readFailedAt: null };
}

export function acknowledgeStoreTrouble(): void {
  health = { readFailedAt: null, writeFailedAt: null };
  publish();
}

let sessionUserId: string | null = null;
let sessionKnown = false;
let sessionRole: UserRole | null = null;

export function setDraftSessionUser(userId: string | null, role: UserRole | null): void {
  if (sessionKnown && sessionUserId === userId && sessionRole === role) return;
  sessionUserId = userId;
  sessionRole = role;
  sessionKnown = true;
  void refreshDrafts();
}

export function draftSessionUserId(): string | null {
  return sessionUserId;
}

export function draftSessionUnknown(): boolean {
  return !sessionKnown;
}

export function mayMintLocalWorkshop(session: { known: boolean; role: UserRole | null }): boolean {
  if (!session.known) return true;
  return canCreateDesignWorkshops(session.role ? ({ role: session.role } as User) : null);
}

export function draftSessionMayCreate(): boolean {
  return mayMintLocalWorkshop({ known: sessionKnown, role: sessionRole });
}

function draftSessionMayRunWorkshops(): boolean {
  return canRunDesignWorkshops(sessionRole ? ({ role: sessionRole } as User) : null);
}

export type DwDraftStart =
  | "create"
  | "link-later"
  | "refused";

export function classifyDraftStart(
  { mayCreate, mayRunWorkshops, reachable }: {
    mayCreate: boolean;
    mayRunWorkshops: boolean;
    reachable: boolean;
  }
): DwDraftStart {
  if (mayCreate) return "create";
  if (mayRunWorkshops && !reachable) return "link-later";
  return "refused";
}

export class DwCreateNotPermittedError extends Error {
  constructor(message: string = DESIGN_WORKSHOP_CREATE_REFUSAL) {
    super(message);
    this.name = "DwCreateNotPermittedError";
  }
}

export const DW_LOCAL_START_ACTION = "Start one on this device";

export const DW_LOCAL_START_NOTE =
  "Not a workshop yet. Link it to one when online.";

export const DW_LOCAL_DRAFT_UNLINKED = "Started on this device. Not a workshop yet.";

export const DW_LOCAL_DRAFT_LINK_PROMPT = "Choose which of your workshops this belongs to.";

export function draftBelongsToSession(draft: DwDraft): boolean {
  if (!sessionKnown) return true;
  return draft.ownerUserId === null || draft.ownerUserId === sessionUserId;
}

function publish() {
  listeners.forEach((listener) => listener());
}

export function subscribeDrafts(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function getDraftsSnapshot(): DwDraft[] {
  return cache;
}

const SERVER_SNAPSHOT: DwDraft[] = [];
export function getServerDraftsSnapshot(): DwDraft[] {
  return SERVER_SNAPSHOT;
}

export async function refreshDrafts(): Promise<DwDraft[]> {
  try {
    const rows = await transact([STORE_DRAFTS], "readonly", async (stores) =>
      req<Record<string, unknown>[]>(stores[STORE_DRAFTS].getAll() as IDBRequest<Record<string, unknown>[]>)
    );
    cache = rows.map(migrateDraft).filter(draftBelongsToSession).sort((a, b) => b.updatedAt - a.updatedAt);
    noteStoreRead();
  } catch {
    noteStoreFailure("read");
    cache = [];
  }
  publish();
  return cache;
}

function matchesId(draft: DwDraft, id: string): boolean {
  return draft.localId === id || draft.remoteId === id;
}

export async function loadDraft(id: string): Promise<DwDraft | null> {
  const rows = await transact([STORE_DRAFTS], "readonly", async (stores) =>
    req<Record<string, unknown>[]>(stores[STORE_DRAFTS].getAll() as IDBRequest<Record<string, unknown>[]>)
  ).catch(() => {
    noteStoreFailure("read");
    return [] as Record<string, unknown>[];
  });
  return rows.map(migrateDraft).find((draft) => matchesId(draft, id) && draftBelongsToSession(draft)) ?? null;
}

export async function listDrafts(): Promise<DwDraft[]> {
  return refreshDrafts();
}

function localId(): string {
  return `${LOCAL_ID_PREFIX}${newClientKey()}`;
}

function definedOnly(header: Partial<DwDraftHeader>): Partial<DwDraftHeader> {
  return Object.fromEntries(Object.entries(header).filter(([, value]) => value !== undefined));
}

function emptyHeader(title: string, templateId: string): DwDraftHeader {
  return {
    title,
    templateId,
    status: "DRAFT",
    designerUserId: null,
    designerUserIds: [],
    workshopKind: null,
    craftName: null,
    clusterName: null,
    state: null,
    district: null,
    startDate: null,
    endDate: null,
    workshopId: null,
    notes: null,
    workshopCode: null,
    venue: null,
    designerName: null
  };
}

export function emptyStage(stageKey: string): DwDraftStage {
  return {
    stageKey,
    singletons: {},
    collections: {},
    removedFrom: [],
    custom: {},
    updatedAt: 0,
    dirtyAt: null,
    lastPushedAt: null,
    serverLoadedAt: null,
    completeness: null,
    failure: null
  };
}

export function stageHoldsSomething(stage: DwDraftStage | undefined): boolean {
  if (!stage) return false;
  if ((stage.removedFrom ?? []).length > 0) return true;
  for (const data of Object.values(stage.singletons ?? {})) {
    for (const value of Object.values(data)) if (isFilled(value)) return true;
  }
  for (const rows of Object.values(stage.collections ?? {})) if (rows.length) return true;
  for (const value of Object.values(stage.custom ?? {})) if (isFilled(value)) return true;
  return false;
}

async function mutate<P = undefined>(
  id: string,
  transform: (draft: DwDraft, prepared: P) => DwDraft,
  options?: {
    stores?: string[];
    prepare?: (stores: Record<string, IDBObjectStore>, draft: DwDraft) => Promise<P>;
  }
): Promise<DwDraft | null> {
  const next = await transact([STORE_DRAFTS, ...(options?.stores ?? [])], "readwrite", async (stores) => {
    const store = stores[STORE_DRAFTS];
    const rows = await req<Record<string, unknown>[]>(store.getAll() as IDBRequest<Record<string, unknown>[]>);
    const current = rows.map(migrateDraft).find((draft) => matchesId(draft, id) && draftBelongsToSession(draft));
    if (!current) return null;
    const prepared = (options?.prepare ? await options.prepare(stores, current) : undefined) as P;
    const updated: DwDraft = {
      ...transform(current, prepared),
      schemaVersion: DW_DRAFT_SCHEMA_VERSION,
      localId: current.localId,
      createdAt: current.createdAt,
      updatedAt: Date.now()
    };
    await req(store.put(updated));
    return updated;
  }).catch(() => {
    noteStoreFailure("write");
    return null;
  });
  if (next) await refreshDrafts();
  else publish();
  return next;
}

export async function createLocalDraft(
  header: Partial<DwDraftHeader> & { title: string },
  options?: DwLocalDraftOptions
): Promise<DwDraft> {
  const start = classifyDraftStart({
    mayCreate: draftSessionMayCreate(),
    mayRunWorkshops: draftSessionMayRunWorkshops(),
    reachable: !(deviceLooksOffline() || options?.serverUnreachable === true)
  });
  if (start === "refused") throw new DwCreateNotPermittedError();
  const draft = newLocalDraft(header, options);
  await transact([STORE_DRAFTS], "readwrite", async (stores) => req(stores[STORE_DRAFTS].put(draft)));
  await refreshDrafts();
  return draft;
}

export type DwLocalDraftOptions = {
  ownerUserId?: string | null;
  registryVersion?: string;
  createSentAt?: number | null;
  serverUnreachable?: boolean;
};

export function newLocalDraft(
  header: Partial<DwDraftHeader> & { title: string },
  options?: DwLocalDraftOptions
): DwDraft {
  const now = Date.now();
  return {
    schemaVersion: DW_DRAFT_SCHEMA_VERSION,
    localId: localId(),
    remoteId: null,
    header: { ...emptyHeader(header.title, header.templateId ?? "DCH_STANDARD"), ...definedOnly(header) },
    headerDirtyAt: now,
    stages: {},
    createdAt: now,
    updatedAt: now,
    registryVersion: options?.registryVersion ?? "",
    customSchemaVersion: "",
    customDefinition: null,
    ownerUserId: options?.ownerUserId ?? sessionUserId,
    lastSyncedAt: null,
    createSentAt: options?.createSentAt ?? null,
    failure: null
  };
}

export async function adoptDraftIntoWorkshop(localId: string, remoteId: string): Promise<DwDraft | null> {
  return mutate(localId, (draft) => {
    if (!localDraftNeedsAWorkshop(draft)) return draft;
    return adoptedIntoWorkshop(draft, remoteId);
  });
}

export async function ensureDraft(
  remoteId: string,
  seed?: { title?: string; templateId?: string; ownerUserId?: string | null }
): Promise<DwDraft> {
  const now = Date.now();
  const draft: DwDraft = {
    schemaVersion: DW_DRAFT_SCHEMA_VERSION,
    localId: localId(),
    remoteId,
    header: emptyHeader(seed?.title ?? "", seed?.templateId ?? "DCH_STANDARD"),
    headerDirtyAt: null,
    stages: {},
    createdAt: now,
    updatedAt: now,
    registryVersion: "",
    customSchemaVersion: "",
    customDefinition: null,
    ownerUserId: seed?.ownerUserId ?? sessionUserId,
    lastSyncedAt: null,
    failure: null
  };
  const stored = await transact([STORE_DRAFTS], "readwrite", async (stores) => {
    const store = stores[STORE_DRAFTS];
    const rows = await req<Record<string, unknown>[]>(store.getAll() as IDBRequest<Record<string, unknown>[]>);
    const existing = rows
      .map(migrateDraft)
      .find((row) => matchesId(row, remoteId) && draftBelongsToSession(row));
    if (existing) return existing;
    await req(store.put(draft));
    return draft;
  }).catch(() => {
    noteStoreFailure("write");
    return draft;
  });
  await refreshDrafts();
  return stored;
}

export async function patchDraftHeader(id: string, patch: Partial<DwDraftHeader>): Promise<DwDraft | null> {
  const touched = definedOnly(patch);
  return mutate(id, (draft) => ({
    ...draft,
    header: { ...draft.header, ...touched },
    headerDirtyAt: Date.now(),
    headerDirtyKeys: Array.from(new Set([...(draft.headerDirtyKeys ?? []), ...Object.keys(touched)]))
  }));
}

export async function putDraftStage(
  id: string,
  stageKey: string,
  data: {
    singletons: Record<string, DwEntryData>;
    collections: Record<string, DwRow[]>;
    removedFrom?: string[];
    custom?: DwEntryData;
    copiedFrom?: Record<string, string>;
  }
): Promise<DwDraft | null> {
  return mutate<Map<string, string>>(
    id,
    (draft, confirmed) => {
      const resolve = (values: DwEntryData): DwEntryData => {
        if (!confirmed.size) return values;
        const swap = (value: DwValue | undefined): DwValue | undefined => {
          if (typeof value === "string") return confirmed.get(value) ?? value;
          if (Array.isArray(value)) return value.map((item) => confirmed.get(item) ?? item);
          return value;
        };
        return Object.fromEntries(Object.entries(values).map(([key, value]) => [key, swap(value)]));
      };
      const previous = draft.stages[stageKey] ?? emptyStage(stageKey);
      const now = Date.now();
      return {
        ...draft,
        stages: {
          ...draft.stages,
          [stageKey]: {
            ...previous,
            singletons: Object.fromEntries(
              Object.entries(data.singletons).map(([entityKey, values]) => [entityKey, resolve(values)])
            ),
            collections: Object.fromEntries(
              Object.entries(data.collections).map(([entityKey, rows]) => [
                entityKey,
                rows.map((row) => resolve(row) as DwRow)
              ])
            ),
            removedFrom: Array.from(new Set([...previous.removedFrom, ...(data.removedFrom ?? [])])),
            ...(data.copiedFrom !== undefined ? { copiedFrom: data.copiedFrom } : {}),
            custom: data.custom ?? previous.custom ?? {},
            updatedAt: now,
            dirtyAt: now,
            serverLoadedAt: previous.serverLoadedAt ?? (draft.remoteId === null ? now : null),
            failure: null
          }
        }
      };
    },
    { stores: [STORE_MEDIA], prepare: (stores, draft) => confirmedMediaMap(stores[STORE_MEDIA], draft.localId) }
  );
}

export type DwPushedSnapshot = {
  dirtyAt: number | null;
  removedFrom: readonly string[];
};

export function unsentAfterPush(
  stage: DwDraftStage,
  sent: DwPushedSnapshot
): { dirtyAt: number | null; removedFrom: string[] } {
  const sentDirtyAt = sent.dirtyAt ?? 0;
  const deletedSince = stage.removedFrom.some((key) => !sent.removedFrom.includes(key));
  const superseded =
    stage.dirtyAt !== null && (stage.dirtyAt > sentDirtyAt || (stage.dirtyAt === sentDirtyAt && deletedSince));
  return {
    dirtyAt: superseded ? stage.dirtyAt : null,
    removedFrom: superseded ? stage.removedFrom : []
  };
}

export async function markStagePushed(
  id: string,
  stageKey: string,
  options: {
    completeness?: DwStageCompleteness | null;
    sinceDirtyAt: number | null;
    sinceRemovedFrom: readonly string[];
    mergedEntries?: boolean;
    sentCopiedFrom?: Record<string, string>;
  }
): Promise<DwDraft | null> {
  return mutate(id, (draft) => {
    const stage = draft.stages[stageKey];
    if (!stage) return draft;
    const now = Date.now();
    return {
      ...draft,
      stages: {
        ...draft.stages,
        [stageKey]: {
          ...stage,
          ...unsentAfterPush(stage, { dirtyAt: options.sinceDirtyAt, removedFrom: options.sinceRemovedFrom }),
          ...copiesAfterPush(stage, options.sentCopiedFrom),
          lastPushedAt: now,
          foldNote: null,
          serverLoadedAt: options.mergedEntries ? stage.serverLoadedAt : (stage.serverLoadedAt ?? now),
          completeness: options.completeness ?? stage.completeness,
          failure: null
        }
      },
      lastSyncedAt: now
    };
  });
}

export async function discardDraft(id: string): Promise<void> {
  const draft = await loadDraft(id);
  if (!draft) return;
  await transact([STORE_DRAFTS, STORE_MEDIA], "readwrite", async (stores) => {
    await req(stores[STORE_DRAFTS].delete(draft.localId));
    const index = stores[STORE_MEDIA].index(MEDIA_BY_DRAFT);
    const ids = await req<IDBValidKey[]>(index.getAllKeys(draft.localId) as IDBRequest<IDBValidKey[]>);
    for (const key of ids) await req(stores[STORE_MEDIA].delete(key));
  });
  await refreshDrafts();
}

let persistenceAsked = false;

async function requestPersistence(): Promise<void> {
  if (persistenceAsked) return;
  persistenceAsked = true;
  try {
    if (typeof navigator === "undefined" || !navigator.storage?.persist) return;
    if (await navigator.storage.persisted()) return;
    await navigator.storage.persist();
  } catch {
  }
}

export async function stageLocalMedia(
  workshopId: string,
  file: File,
  context?: { stageKey?: string | null; entityKey?: string | null; fieldKey?: string | null; clientKey?: string | null; caption?: string | null }
): Promise<{ ref: string; media: DwDraftMedia }> {
  void requestPersistence();
  const draft = (await loadDraft(workshopId)) ?? (await ensureDraft(workshopId));
  const media: DwDraftMedia = {
    id: newClientKey(),
    localId: draft.localId,
    blob: file,
    name: file.name,
    mimeType: file.type || "application/octet-stream",
    sizeBytes: file.size,
    capturedAt: Date.now(),
    stageKey: context?.stageKey ?? null,
    entityKey: context?.entityKey ?? null,
    fieldKey: context?.fieldKey ?? null,
    clientKey: context?.clientKey ?? null,
    caption: context?.caption ?? null,
    remoteMediaId: null,
    confirmedAt: null,
    attempts: 0,
    lastError: null,
    transcribe: wantsTranscription(file)
  };
  await transact([STORE_MEDIA], "readwrite", async (stores) => req(stores[STORE_MEDIA].put(media)));
  return { ref: `${LOCAL_MEDIA_PREFIX}${media.id}`, media };
}

async function confirmedMediaMap(store: IDBObjectStore, localDraftId: string): Promise<Map<string, string>> {
  const rows = await req<DwDraftMedia[]>(
    store.index(MEDIA_BY_DRAFT).getAll(localDraftId) as IDBRequest<DwDraftMedia[]>
  );
  const out = new Map<string, string>();
  for (const row of rows) {
    if (row.remoteMediaId) out.set(`${LOCAL_MEDIA_PREFIX}${row.id}`, row.remoteMediaId);
  }
  return out;
}

export async function readLocalMedia(ref: string): Promise<DwDraftMedia | null> {
  const id = ref.startsWith(LOCAL_MEDIA_PREFIX) ? ref.slice(LOCAL_MEDIA_PREFIX.length) : ref;
  return transact([STORE_MEDIA], "readonly", async (stores) =>
    req<DwDraftMedia | undefined>(stores[STORE_MEDIA].get(id) as IDBRequest<DwDraftMedia | undefined>)
  )
    .then((row) => row ?? null)
    .catch(() => null);
}

export async function draftMedia(localDraftId: string): Promise<DwDraftMedia[]> {
  return transact([STORE_MEDIA], "readonly", async (stores) =>
    req<DwDraftMedia[]>(stores[STORE_MEDIA].index(MEDIA_BY_DRAFT).getAll(localDraftId) as IDBRequest<DwDraftMedia[]>)
  ).catch(() => []);
}

export async function confirmLocalMedia(localMediaId: string, remoteMediaId: string): Promise<void> {
  await transact([STORE_DRAFTS, STORE_MEDIA], "readwrite", async (stores) => {
    const mediaStore = stores[STORE_MEDIA];
    const media = await req<DwDraftMedia | undefined>(mediaStore.get(localMediaId) as IDBRequest<DwDraftMedia | undefined>);
    if (!media) return;

    const draftStore = stores[STORE_DRAFTS];
    const raw = await req<Record<string, unknown> | undefined>(
      draftStore.get(media.localId) as IDBRequest<Record<string, unknown> | undefined>
    );
    if (raw) {
      const draft = migrateDraft(raw);
      await req(
        draftStore.put({
          ...rewriteMediaRefs(draft, `${LOCAL_MEDIA_PREFIX}${localMediaId}`, remoteMediaId),
          updatedAt: Date.now()
        })
      );
    }

    await req(
      mediaStore.put({
        ...media,
        remoteMediaId,
        confirmedAt: Date.now(),
        lastError: null,
        blob: null
      })
    );
  });
  await refreshDrafts();
}

export async function removeLocalMedia(localMediaId: string): Promise<void> {
  await transact([STORE_MEDIA], "readwrite", async (stores) => {
    const media = await req<DwDraftMedia | undefined>(
      stores[STORE_MEDIA].get(localMediaId) as IDBRequest<DwDraftMedia | undefined>
    );
    if (media && !media.confirmedAt) await req(stores[STORE_MEDIA].delete(localMediaId));
  });
}

function rewriteMediaRefs(draft: DwDraft, from: string, to: string): DwDraft {
  const swap = (value: DwValue | undefined): DwValue | undefined => {
    if (value === from) return to;
    if (Array.isArray(value)) return value.map((item) => (item === from ? to : item));
    return value;
  };
  const mapData = (data: DwEntryData): DwEntryData =>
    Object.fromEntries(Object.entries(data).map(([key, value]) => [key, swap(value)]));

  return {
    ...draft,
    stages: Object.fromEntries(
      Object.entries(draft.stages).map(([stageKey, stage]) => [
        stageKey,
        {
          ...stage,
          singletons: Object.fromEntries(
            Object.entries(stage.singletons).map(([entityKey, data]) => [entityKey, mapData(data)])
          ),
          collections: Object.fromEntries(
            Object.entries(stage.collections).map(([entityKey, rows]) => [
              entityKey,
              rows.map((row) => mapData(row) as DwRow)
            ])
          )
        }
      ])
    )
  };
}

export function unresolvedMediaRefs(stage: DwDraftStage): string[] {
  const found = new Set<string>();
  const scan = (data: DwEntryData) => {
    for (const value of Object.values(data)) {
      if (typeof value === "string" && isLocalMediaRef(value)) found.add(value);
      else if (Array.isArray(value)) for (const item of value) if (isLocalMediaRef(item)) found.add(item);
    }
  };
  for (const data of Object.values(stage.singletons)) scan(data);
  for (const rows of Object.values(stage.collections)) for (const row of rows) scan(row);
  return [...found];
}

let confirmedStored: DwRegistry | null = null;

export async function cacheRegistry(registry: DwRegistry): Promise<void> {
  if (confirmedStored === registry) return;
  await transact([STORE_REGISTRY], "readwrite", async (stores) => {
    const store = stores[STORE_REGISTRY];
    const held = await req<RegistryRecord | undefined>(
      store.get(registry.version) as IDBRequest<RegistryRecord | undefined>
    );
    if (held && JSON.stringify(held.registry) === JSON.stringify(registry)) return;
    await req(store.put({ version: registry.version, registry, storedAt: Date.now() } satisfies RegistryRecord));
    const all = await req<RegistryRecord[]>(store.getAll() as IDBRequest<RegistryRecord[]>);
    const stale = all.sort((a, b) => b.storedAt - a.storedAt).slice(REGISTRY_KEEP);
    for (const row of stale) await req(store.delete(row.version));
  })
    .then(() => {
      confirmedStored = registry;
    })
    .catch(() => {
    });
}

export async function cachedRegistry(version?: string): Promise<DwRegistry | null> {
  const rows = await transact([STORE_REGISTRY], "readonly", async (stores) =>
    req<RegistryRecord[]>(stores[STORE_REGISTRY].getAll() as IDBRequest<RegistryRecord[]>)
  ).catch(() => [] as RegistryRecord[]);
  if (!rows.length) return null;
  if (version) return rows.find((row) => row.version === version)?.registry ?? null;
  return rows.sort((a, b) => b.storedAt - a.storedAt)[0].registry;
}

export type RegistrySource = "network" | "memory" | "cache";

export async function loadRegistry(): Promise<{ registry: DwRegistry; source: RegistrySource; storedAt?: number }> {
  const memoised = peekStageRegistry();
  try {
    const registry = await fetchStageRegistry();
    void cacheRegistry(registry);
    return { registry, source: memoised ? "memory" : "network" };
  } catch (error) {
    const fallback = await cachedRegistry();
    if (!fallback) throw error;
    const adopted = adoptStageRegistry(fallback);
    return { registry: adopted, source: "cache" };
  }
}

export function stageDataOf(stage: DwDraftStage | undefined): DwStageData {
  if (!stage) return { singleton: {}, collections: {} };
  const singleton: DwEntryData = {};
  for (const data of Object.values(stage.singletons)) Object.assign(singleton, data);
  return { singleton, collections: stage.collections, completeness: stage.completeness };
}

export function splitSingletons(spec: DwStage, singleton: DwEntryData): Record<string, DwEntryData> {
  const out: Record<string, DwEntryData> = {};
  for (const entity of spec.entities) {
    if (entity.cardinality !== "SINGLETON") continue;
    const known: DwEntryData = {};
    for (const field of entity.fields) {
      if (field.key in singleton) known[field.key] = singleton[field.key];
    }
    out[entity.key] = known;
  }
  return out;
}

export function unknownSingletonKeys(spec: DwStage, singleton: DwEntryData): Record<string, DwEntryData> {
  const singletons = spec.entities.filter((entity) => entity.cardinality === "SINGLETON");
  if (!singletons.length) return {};
  const declared = new Set<string>();
  for (const entity of singletons) for (const field of entity.fields) declared.add(field.key);
  const leftover: DwEntryData = {};
  for (const [key, value] of Object.entries(singleton)) {
    if (key.startsWith("_")) continue;
    if (declared.has(key)) continue;
    leftover[key] = value;
  }
  if (!Object.keys(leftover).length) return {};
  return { [singletons[0].key]: leftover };
}

export type DwStageFold = {
  stage: DwDraftStage;
  added: string[];
  addedRows: Record<string, number>;
  addedCustom: string[];
  sweptRows: Record<string, number>;
};

export function foldStageInto(
  spec: DwStage,
  current: DwDraftStage,
  incoming: DwStageData
): DwStageFold {
  const added: string[] = [];
  const singletons: Record<string, DwEntryData> = { ...current.singletons };
  const fromServer = splitSingletons(spec, incoming.singleton ?? {});
  for (const [entityKey, values] of Object.entries(fromServer)) {
    const held = current.singletons[entityKey] ?? {};
    const merged: DwEntryData = { ...held };
    for (const [key, value] of Object.entries(values)) {
      if (key in merged) continue;
      merged[key] = value;
      if (!key.startsWith("_")) added.push(key);
    }
    singletons[entityKey] = merged;
  }

  const unknownSingleton: Record<string, DwEntryData> = { ...(current.unknownSingleton ?? {}) };
  for (const [entityKey, values] of Object.entries(unknownSingletonKeys(spec, incoming.singleton ?? {}))) {
    const held = unknownSingleton[entityKey] ?? {};
    const merged: DwEntryData = { ...held };
    for (const [key, value] of Object.entries(values)) if (!(key in merged)) merged[key] = value;
    unknownSingleton[entityKey] = merged;
  }

  const addedCustom: string[] = [];
  const custom: DwEntryData = { ...(current.custom ?? {}) };
  for (const [key, value] of Object.entries(incoming.custom ?? {})) {
    if (key in custom) continue;
    custom[key] = value;
    if (!key.startsWith("_")) addedCustom.push(key);
  }

  const emptied = new Set(current.removedFrom ?? []);
  const declared = new Set(
    spec.entities.filter((entity) => entity.cardinality !== "SINGLETON").map((entity) => entity.key)
  );
  const collections: Record<string, DwRow[]> = { ...current.collections };
  const addedRows: Record<string, number> = {};
  const sweptRows: Record<string, number> = {};

  for (const [entityKey, serverRows] of Object.entries(incoming.collections ?? {})) {
    if (!declared.has(entityKey)) continue;

    const held = current.collections[entityKey] ?? [];
    const heldClientKeys = new Set(
      held.map((row) => row._clientKey).filter((key): key is string => !!key && key.length > 0)
    );
    const heldEntryIds = new Set(
      held.map((row) => row._entryId).filter((id): id is string => !!id && id.length > 0)
    );
    const isUnseen = (row: DwRow) =>
      !(row._clientKey && heldClientKeys.has(row._clientKey)) &&
      !(row._entryId && heldEntryIds.has(row._entryId));

    if (emptied.has(entityKey)) {
      const unseen = serverRows.filter(isUnseen).length;
      if (unseen > 0) sweptRows[entityKey] = unseen;
      continue;
    }

    const appended = serverRows.filter(isUnseen);
    if (appended.length > 0) {
      collections[entityKey] = [
        ...held,
        ...appended.map((row) => (row._clientKey ? row : { ...row, _clientKey: newClientKey() }))
      ];
      addedRows[entityKey] = appended.length;
    }
  }

  return {
    stage: {
      ...current,
      singletons,
      unknownSingleton,
      collections,
      custom,
      completeness: incoming.completeness ?? current.completeness,
      serverLoadedAt: Date.now(),
      updatedAt: Date.now()
    },
    added,
    addedRows,
    addedCustom,
    sweptRows
  };
}

export function foldChangedNothing(fold: DwStageFold): boolean {
  return (
    fold.added.length === 0 &&
    Object.keys(fold.addedRows).length === 0 &&
    fold.addedCustom.length === 0 &&
    Object.keys(fold.sweptRows).length === 0
  );
}

export function foldNotice(fold: DwStageFold): string | null {
  if (foldChangedNothing(fold)) return null;
  const parts: string[] = [];
  const list = (keys: string[]) =>
    `${keys.slice(0, 4).join(", ")}${keys.length > 4 ? ", …" : ""}`;

  if (fold.added.length > 0) {
    parts.push(`${fold.added.length} answer${fold.added.length === 1 ? "" : "s"} (${list(fold.added)})`);
  }
  const rowCount = Object.values(fold.addedRows).reduce((sum, n) => sum + n, 0);
  if (rowCount > 0) {
    parts.push(`${rowCount} ${rowCount === 1 ? "entry" : "entries"} in ${Object.keys(fold.addedRows).join(", ")}`);
  }
  if (fold.addedCustom.length > 0) {
    parts.push(
      `${fold.addedCustom.length} of this workshop's own question${fold.addedCustom.length === 1 ? "" : "s"} (${list(fold.addedCustom)})`
    );
  }

  let out = "This stage has been updated with what was already saved online. ";
  if (parts.length > 0) {
    out += `${parts.join("; ")} were saved online but not on this device, and have been added below. `;
    out += "Nothing you had typed here was changed. ";
  }
  const swept = Object.values(fold.sweptRows).reduce((sum, n) => sum + n, 0);
  if (swept > 0) {
    const them = swept === 1 ? "it" : "them";
    out += `You had deleted everything in ${Object.keys(fold.sweptRows).join(", ")} on this device, so `;
    out += `${swept} saved ${swept === 1 ? "entry there has" : "entries there have"} `;
    out += `not been added back, and the next save will delete ${them} — including anything `;
    out += "somebody else added there since you deleted. ";
    out += `If you didn't mean to delete ${them}, say so before this stage is submitted: deleted entries `;
    out += `are kept, so an administrator can bring ${swept === 1 ? "it" : "them"} back.`;
  }
  return out.trim();
}

export function localStageCompleteness(
  spec: DwStage,
  stage: DwDraftStage | undefined,
  customFields: readonly DwCustomField[] = []
): DwStageCompleteness {
  const data = stageDataOf(stage);
  return scoreStageData(spec, data.singleton, data.collections, customFields, stage?.custom ?? {});
}

export function scoreStageData(
  spec: DwStage,
  singleton: DwEntryData,
  collections: Record<string, DwRow[]>,
  customFields: readonly DwCustomField[] = [],
  customValues: DwEntryData = {}
): DwStageCompleteness {
  const data = { singleton, collections };
  let requiredTotal = 0;
  let requiredFilled = 0;
  let optionalTotal = 0;
  let optionalFilled = 0;
  const missing: string[] = [];

  const score = (entity: DwEntity, row: DwEntryData, prefix: string) => {
    for (const field of entity.fields) {
      if (field.deprecated) continue;
      const filled = isFilled(row[field.key]);
      if (field.required) {
        requiredTotal += 1;
        if (filled) requiredFilled += 1;
        else missing.push(`${prefix}${field.label}`);
      } else {
        optionalTotal += 1;
        if (filled) optionalFilled += 1;
      }
    }
  };

  const collectionCounts: Record<string, number> = {};

  for (const entity of spec.entities) {
    if (entity.cardinality !== "SINGLETON") continue;
    score(entity, data.singleton, "");
  }

  for (const field of customFields) {
    if (field.retired) continue;
    const filled = isFilled(customValues[field.key]);
    if (field.required) {
      requiredTotal += 1;
      if (filled) requiredFilled += 1;
      else missing.push(field.label);
    } else {
      optionalTotal += 1;
      if (filled) optionalFilled += 1;
    }
  }

  for (const entity of spec.entities) {
    if (entity.cardinality === "SINGLETON") continue;
    const rows = data.collections[entity.key] ?? [];
    collectionCounts[entity.key] = rows.length;
    for (const row of rows) score(entity, row, `${entity.title}: `);
  }

  return {
    stageKey: spec.key,
    number: spec.number,
    title: spec.title,
    requiredTotal,
    requiredFilled,
    optionalTotal,
    optionalFilled,
    percent: requiredTotal === 0 ? 100 : Math.round((100 * requiredFilled) / requiredTotal),
    isComplete: requiredFilled >= requiredTotal,
    collectionCounts,
    missing: [...new Set(missing)]
  };
}

export function localCompleteness(registry: DwRegistry, draft: DwDraft): Record<string, DwStageCompleteness> {
  const out: Record<string, DwStageCompleteness> = {};
  for (const spec of registry.stages) {
    out[spec.key] = localStageCompleteness(spec, draft.stages[spec.key], customFieldsFor(draft, spec.key));
  }
  return out;
}

export async function adoptServerDetail(detail: DwDetail, spec: DwRegistry): Promise<DwDraft | null> {
  await ensureDraft(detail.id, { title: detail.title, templateId: detail.templateId });
  return mutate(detail.id, (draft) => {
    const stages: Record<string, DwDraftStage> = { ...draft.stages };
    for (const stageSpec of spec.stages) {
      const incoming = detail.stages?.[stageSpec.key];
      const current = stages[stageSpec.key] ?? emptyStage(stageSpec.key);
      const serverScore = detail.completeness?.[stageSpec.key] ?? null;
      if (current.dirtyAt !== null) {
        stages[stageSpec.key] = { ...current, completeness: serverScore ?? current.completeness };
        continue;
      }
      if (!incoming) {
        stages[stageSpec.key] = {
          ...current,
          singletons: {},
          unknownSingleton: {},
          collections: {},
          removedFrom: [],
          custom: {},
          updatedAt: Date.now(),
          serverLoadedAt: Date.now(),
          completeness: serverScore ?? current.completeness
        };
        continue;
      }
      stages[stageSpec.key] = {
        ...current,
        singletons: splitSingletons(stageSpec, incoming.singleton ?? {}),
        unknownSingleton: unknownSingletonKeys(stageSpec, incoming.singleton ?? {}),
        collections: withClientKeys(incoming.collections ?? {}),
        removedFrom: [],
        custom: incoming.custom ?? {},
        updatedAt: Date.now(),
        dirtyAt: null,
        lastPushedAt: current.lastPushedAt,
        serverLoadedAt: Date.now(),
        completeness: serverScore ?? incoming.completeness ?? null,
        failure: null
      };
    }
    return {
      ...draft,
      remoteId: detail.id,
      header: draft.headerDirtyAt !== null ? draft.header : headerOf(detail),
      consent: mergeDraftConsent(draft.consent ?? null, detail),
      stages,
      registryVersion: detail.schemaVersion || draft.registryVersion,
      lastSyncedAt: Date.now()
    };
  });
}

function consentMoment(value: string | null | undefined): number | null {
  if (!value) return null;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : null;
}

export function mergeDraftConsent(
  onDevice: DwDraftConsent | null,
  server: { dictationConsent?: string | null; dictationConsentAt?: string | null; dictationConsentById?: string | null }
): DwDraftConsent | null {
  const there = (server.dictationConsent ?? "").trim().toUpperCase();
  if (!onDevice) {
    if (!there || there === "NOT_RECORDED") return null;
    return {
      decision: there,
      recordedAt: server.dictationConsentAt ?? "",
      recordedById: server.dictationConsentById ?? null,
      recordedByName: null,
      synced: true
    };
  }
  const here = (onDevice.decision ?? "").trim().toUpperCase();
  if (there === here) return onDevice.synced ? onDevice : { ...onDevice, synced: true };
  if (onDevice.synced) {
    if (!there || there === "NOT_RECORDED") return onDevice;
    return {
      decision: there,
      recordedAt: server.dictationConsentAt ?? "",
      recordedById: server.dictationConsentById ?? null,
      recordedByName: null,
      synced: true
    };
  }

  const mine = consentMoment(onDevice.recordedAt);
  const theirs = consentMoment(server.dictationConsentAt);
  const takeServer =
    mine !== null && theirs !== null
      ? theirs > mine
      :
        there === "REFUSED";
  if (!takeServer) return onDevice;
  if (!there || there === "NOT_RECORDED") return onDevice;
  return {
    decision: there,
    recordedAt: server.dictationConsentAt ?? "",
    recordedById: server.dictationConsentById ?? null,
    recordedByName: null,
    synced: true
  };
}

export async function recordDraftConsent(
  id: string,
  decision: "GRANTED" | "REFUSED",
  actor: { id: string | null; name: string | null }
): Promise<DwDraft | null> {
  const recorded: DwDraftConsent = {
    decision,
    recordedAt: new Date().toISOString().replace(/(\.\d{3})\d+/, "$1"),
    recordedById: actor.id,
    recordedByName: actor.name,
    synced: false
  };
  return mutate(id, (draft) => ({ ...draft, consent: recorded, updatedAt: Date.now() }));
}

export async function markDraftConsentSynced(id: string): Promise<DwDraft | null> {
  return mutate(id, (draft) => (draft.consent ? { ...draft, consent: { ...draft.consent, synced: true } } : draft));
}

export async function cacheCustomDefinition(
  id: string,
  definition: DwCustomDefinition
): Promise<DwDraft | null> {
  return mutate(id, (draft) => {
    if (draft.customDefinition && draft.customSchemaVersion === definition.customSchemaVersion) return draft;
    return { ...draft, customDefinition: definition, customSchemaVersion: definition.customSchemaVersion };
  });
}

export type CustomDefinitionSource = "network" | "cache" | "unknown";

export async function loadCustomDefinition(
  draft: DwDraft
): Promise<{ definition: DwCustomDefinition | null; source: CustomDefinitionSource }> {
  const held = draft.customDefinition ?? null;
  if (!draft.remoteId) {
    return { definition: held ?? { customSchemaVersion: "", sections: [], fetchedAt: "" }, source: "cache" };
  }
  try {
    const definition = await fetchCustomDefinition(draft.remoteId);
    void cacheCustomDefinition(draft.localId, definition);
    return { definition, source: "network" };
  } catch {
    if (!held) return { definition: null, source: "unknown" };
    return { definition: adoptCustomDefinition(draft.remoteId, held), source: "cache" };
  }
}

export function customFieldsFor(draft: DwDraft | null | undefined, stageKey: string): DwCustomField[] {
  return customFieldsForStage(draft?.customDefinition ?? null, stageKey);
}

export function serverReadPredatesPush(stage: Pick<DwDraftStage, "lastPushedAt"> | undefined, readSentAt: number | undefined): boolean {
  if (readSentAt === undefined || !stage) return false;
  return stage.lastPushedAt !== null && stage.lastPushedAt >= readSentAt;
}

export async function adoptServerStage(
  workshopId: string,
  spec: DwStage,
  incoming: DwStageData,
  options?: { readSentAt?: number; onStale?: () => void }
): Promise<DwDraft | null> {
  return mutate(workshopId, (draft) => {
    const current = draft.stages[spec.key] ?? emptyStage(spec.key);
    if (serverReadPredatesPush(draft.stages[spec.key], options?.readSentAt)) {
      options?.onStale?.();
      return draft;
    }
    if (current.dirtyAt !== null) {
      const folded = foldStageInto(spec, current, incoming);
      const note = foldNotice(folded);
      return {
        ...draft,
        stages: {
          ...draft.stages,
          [spec.key]: {
            ...folded.stage,
            foldNote: note
          }
        }
      };
    }
    return {
      ...draft,
      stages: {
        ...draft.stages,
        [spec.key]: {
          ...current,
          singletons: splitSingletons(spec, incoming.singleton ?? {}),
          unknownSingleton: unknownSingletonKeys(spec, incoming.singleton ?? {}),
          collections: withClientKeys(incoming.collections ?? {}),
          removedFrom: [],
          custom: incoming.custom ?? {},
          provenance: incoming.provenance,
          updatedAt: Date.now(),
          dirtyAt: null,
          serverLoadedAt: Date.now(),
          completeness: incoming.completeness ?? null,
          failure: null
        }
      }
    };
  });
}

function withClientKeys(collections: Record<string, DwRow[]>): Record<string, DwRow[]> {
  return Object.fromEntries(
    Object.entries(collections).map(([entityKey, rows]) => [
      entityKey,
      rows.map((row) => (row._clientKey ? row : { ...row, _clientKey: newClientKey() }))
    ])
  );
}

function headerOf(summary: DwSummary): DwDraftHeader {
  return {
    designerUserId: null,
    designerUserIds: [],
    workshopKind: summary.workshopKind,
    title: summary.title,
    templateId: summary.templateId,
    status: String(summary.status),
    craftName: summary.craftName,
    clusterName: summary.clusterName,
    state: summary.state,
    district: summary.district,
    startDate: summary.startDate,
    endDate: summary.endDate,
    workshopId: summary.workshopId,
    notes: summary.notes,
    workshopCode: summary.workshopCode,
    venue: summary.venue,
    designerName: summary.designerName,
    handedOnAt: summary.handedOnAt ?? null
  };
}

const CREATE_LOCK = "dw-draft-create";

const createsInFlight = new Set<Promise<unknown>>();

async function underCreateClaim<T>(run: () => Promise<T>): Promise<T> {
  const locks = typeof navigator !== "undefined" ? navigator.locks : undefined;
  const claim = locks?.request ? locks.request(CREATE_LOCK, { mode: "exclusive" }, run) : run();
  createsInFlight.add(claim);
  try {
    return await claim;
  } finally {
    createsInFlight.delete(claim);
  }
}

function titlesAwaitingACreate(drafts: DwDraft[]): Set<string> {
  const titles = new Set<string>();
  for (const draft of drafts) {
    if (draft.remoteId === null && (draft.createSentAt ?? null) !== null) titles.add(draft.header.title);
  }
  return titles;
}

async function settleCreateClaims(): Promise<void> {
  const mine = [...createsInFlight];
  if (mine.length) await Promise.allSettled(mine);
  const locks = typeof navigator !== "undefined" ? navigator.locks : undefined;
  if (!locks?.request) return;
  await locks.request(CREATE_LOCK, { mode: "shared" }, async () => undefined).catch(() => {
  });
}

export async function adoptServerSummaries(rows: DwSummary[]): Promise<void> {
  if (!rows.length) return;
  await settleCreateClaims();
  await transact([STORE_DRAFTS], "readwrite", async (stores) => {
    const store = stores[STORE_DRAFTS];
    const existing = (
      await req<Record<string, unknown>[]>(store.getAll() as IDBRequest<Record<string, unknown>[]>)
    ).map(migrateDraft);
    const byRemote = new Map(
      existing.filter((draft) => draft.remoteId && draftBelongsToSession(draft)).map((draft) => [draft.remoteId!, draft])
    );
    const awaiting = titlesAwaitingACreate(existing.filter(draftBelongsToSession));
    const now = Date.now();
    for (const row of rows) {
      const current = byRemote.get(row.id);
      if (current) {
        if (current.headerDirtyAt !== null) continue;
        await req(
          store.put({
            ...current,
            header: headerOf(row),
            consent: mergeDraftConsent(current.consent ?? null, row),
            ownerUserId: current.ownerUserId ?? sessionUserId,
            updatedAt: now
          })
        );
        continue;
      }
      if (awaiting.has(row.title)) continue;
      await req(
        store.put({
          schemaVersion: DW_DRAFT_SCHEMA_VERSION,
          localId: localId(),
          remoteId: row.id,
          header: headerOf(row),
          headerDirtyAt: null,
          stages: {},
          createdAt: now,
          updatedAt: now,
          registryVersion: "",
          customSchemaVersion: "",
          customDefinition: null,
          consent: mergeDraftConsent(null, row),
          ownerUserId: sessionUserId ?? row.createdById ?? null,
          lastSyncedAt: now,
          failure: null
        } satisfies DwDraft)
      );
    }
  }).catch(() => {
  });
  await refreshDrafts();
}

export function draftSummary(draft: DwDraft): DwSummary {
  return {
    id: draft.remoteId ?? draft.localId,
    title: draft.header.title,
    templateId: draft.header.templateId,
    status: draft.header.status,
    workshopCode: draft.header.workshopCode,
    workshopKind: draft.header.workshopKind,
    scheme: null,
    craftName: draft.header.craftName,
    clusterName: draft.header.clusterName,
    state: draft.header.state,
    district: draft.header.district,
    venue: draft.header.venue,
    startDate: draft.header.startDate,
    endDate: draft.header.endDate,
    designerName: draft.header.designerName,
    implementingAgency: null,
    sponsor: null,
    notes: draft.header.notes,
    workshopId: draft.header.workshopId,
    handedOnAt: draft.header.handedOnAt ?? null,
    dictationConsent: draft.consent?.decision ?? "NOT_RECORDED",
    dictationConsentAt: draft.consent?.recordedAt ?? null,
    dictationConsentById: draft.consent?.recordedById ?? null,
    createdById: draft.ownerUserId ?? "",
    createdAt: new Date(draft.createdAt).toISOString(),
    updatedAt: new Date(draft.updatedAt).toISOString(),
    deletedAt: null
  };
}

export type DwDraftPending = {
  draft: DwDraft;
  needsCreate: boolean;
  needsHeader: boolean;
  needsConsent: boolean;
  stageKeys: string[];
  mediaCount: number;
  failures: Array<{ scope: string; message: string }>;
};

export async function pendingWork(): Promise<DwDraftPending[]> {
  const drafts = await refreshDrafts();
  const out: DwDraftPending[] = [];
  for (const draft of drafts) {
    if (!draftBelongsToSession(draft)) continue;
    const stageKeys = Object.values(draft.stages)
      .filter((stage) => stage.dirtyAt !== null || stage.removedFrom.length > 0)
      .sort((a, b) => (a.dirtyAt ?? a.updatedAt) - (b.dirtyAt ?? b.updatedAt))
      .map((stage) => stage.stageKey);
    const media = (await draftMedia(draft.localId)).filter((row) => !row.remoteMediaId);
    const failures: Array<{ scope: string; message: string }> = [];
    if (draft.failure?.permanent) failures.push({ scope: draft.header.title || "This workshop", message: draft.failure.message });
    for (const stage of Object.values(draft.stages)) {
      if (stage.failure?.permanent) failures.push({ scope: stage.stageKey, message: stage.failure.message });
    }
    for (const row of media) {
      if (row.failure?.permanent) failures.push({ scope: row.name, message: row.failure.message });
    }
    const needsCreate = draft.remoteId === null;
    const needsHeader = draft.headerDirtyAt !== null;
    const needsConsent = Boolean(draft.consent && !draft.consent.synced);
    if (!needsCreate && !needsHeader && !needsConsent && !stageKeys.length && !media.length && !failures.length) continue;
    out.push({ draft, needsCreate, needsHeader, needsConsent, stageKeys, mediaCount: media.length, failures });
  }
  return out;
}

export function buildStageEntries(
  spec: DwStage,
  stage: DwDraftStage | undefined
): {
  entries: DwSaveEntry[];
  rowKeys: Array<{ entityKey: string; rowIndex: number } | null>;
  merged: boolean;
} {
  const entries: DwSaveEntry[] = [];
  const rowKeys: Array<{ entityKey: string; rowIndex: number } | null> = [];
  const data = stageDataOf(stage);
  const neverRead = (stage?.serverLoadedAt ?? null) === null;

  for (const entity of spec.entities) {
    if (entity.cardinality === "SINGLETON") {
      const carried = stage?.unknownSingleton?.[entity.key] ?? {};
      const held = stage?.singletons[entity.key] ?? data.singleton;
      const values: DwEntryData = Object.keys(carried).length ? { ...carried, ...held } : held;
      const answered = Object.values(values).some((value) => isFilled(value));
      if (!neverRead || answered) {
        entries.push(
          neverRead
            ? { entityKey: entity.key, data: values, merge: true }
            : { entityKey: entity.key, data: values }
        );
        rowKeys.push(null);
      }
      continue;
    }
    const rows = data.collections[entity.key] ?? [];
    rows.forEach((row, rowIndex) => {
      entries.push({
        entityKey: entity.key,
        entryId: row._entryId,
        ordinal: rowIndex,
        data: entryDataOf(row),
        ...(neverRead ? { merge: true } : {})
      });
      rowKeys.push({ entityKey: entity.key, rowIndex });
    });
  }

  const custom = stage?.custom ?? {};
  const answered = Object.values(custom).some((value) => isFilled(value));
  if (Object.keys(custom).length && (!neverRead || answered)) {
    entries.push(
      neverRead
        ? { entityKey: CUSTOM_ENTITY_KEY, data: custom, merge: true }
        : { entityKey: CUSTOM_ENTITY_KEY, data: custom }
    );
    rowKeys.push(null);
  }

  return { entries, rowKeys, merged: entries.some((entry) => entry.merge === true) };
}

export function stageSweep(
  spec: DwStage | null | undefined,
  stage: DwDraftStage | undefined
): DwStageSweep {
  const removed = stage?.removedFrom ?? [];
  const collectionKeys = new Set(
    (spec?.entities ?? []).filter((entity) => entity.cardinality === "COLLECTION").map((entity) => entity.key)
  );
  const owed = spec ? removed.filter((key) => collectionKeys.has(key)) : removed.filter((key) => !key.startsWith("_"));
  const neverRead = (stage?.serverLoadedAt ?? null) === null;
  if (neverRead) return { replaceCollections: false, emptiedEntities: [], withheld: owed };
  return { replaceCollections: owed.length > 0, emptiedEntities: owed, withheld: [] };
}

export type DwStageSweep = {
  replaceCollections: boolean;
  emptiedEntities: string[];
  withheld: string[];
};

export type DwRowKey = { entityKey: string; rowIndex: number } | null;

export function placeStageErrors(
  errors: Record<string, Record<string, string>> | null | undefined,
  rowKeys: DwRowKey[]
): { decoded: Record<string, Record<string, string>>; unplaced: string[] } {
  const decoded: Record<string, Record<string, string>> = {};
  const unplaced: string[] = [];
  for (const [key, fields] of Object.entries(errors ?? {})) {
    const named = Object.entries(fields ?? {});
    if (!named.length) {
      unplaced.push(`${key}: couldn't be saved (no reason given)`);
      continue;
    }
    const match = /^(.+)\[(\d+)\]$/.exec(key);
    if (!match) {
      decoded[key] = fields;
      continue;
    }
    const entityKey = match[1];
    const origin = rowKeys[Number(match[2])];
    if (!origin || origin.entityKey !== entityKey) {
      for (const [field, message] of named) unplaced.push(`${key}.${field}: ${message}`);
      continue;
    }
    decoded[`${origin.entityKey}[${origin.rowIndex}]`] = fields;
  }
  return { decoded, unplaced };
}

export function strandedRefusals(
  errors: Record<string, Record<string, string>> | null | undefined,
  entities: ReadonlyArray<{ key: string; cardinality: string }>,
  collections: Record<string, unknown[]>
): string[] {
  const spec = new Map(entities.map((entity) => [entity.key, entity.cardinality]));
  const lines: string[] = [];
  for (const [key, fields] of Object.entries(errors ?? {})) {
    const match = /^(.+)\[(\d+)\]$/.exec(key);
    const drawn = match
      ? spec.get(match[1]) === "COLLECTION" && Number(match[2]) < (collections[match[1]] ?? []).length
      :
        key === CUSTOM_ENTITY_KEY || spec.get(key) === "SINGLETON";
    if (drawn) continue;
    for (const [field, message] of Object.entries(fields ?? {})) lines.push(`${key}.${field}: ${message}`);
  }
  return lines;
}

export function countRefusedAnswers(errors: Record<string, Record<string, string>> | null | undefined): number {
  return Object.values(errors ?? {}).reduce((total, fields) => total + (Object.keys(fields ?? {}).length || 1), 0);
}

export function refusedAnswersToShow(
  serverCount: number | undefined,
  errors: Record<string, Record<string, string>> | null | undefined
): number {
  const local = countRefusedAnswers(errors);
  if (typeof serverCount !== "number") return local;
  return serverCount > 0 || local === 0 ? serverCount : local;
}

export type DwSyncResult = {
  workshopsCreated: number;
  stagesSent: number;
  mediaUploaded: number;
  failed: number;
  pending: number;
  stoppedOffline: boolean;
  passwordChangeRequired?: boolean;
  credentialExpired?: boolean;
};

let syncing: Promise<DwSyncResult> | null = null;

const SYNC_LOCK = "dw-draft-sync";

export function syncDesignWorkshopDrafts(): Promise<DwSyncResult> {
  if (syncing) return syncing;
  syncing = runSyncUnderLease().finally(() => {
    syncing = null;
  });
  return syncing;
}

async function declinedResult(): Promise<DwSyncResult> {
  return {
    workshopsCreated: 0,
    stagesSent: 0,
    mediaUploaded: 0,
    failed: 0,
    pending: (await pendingWork()).length,
    stoppedOffline: false
  };
}

async function runSyncUnderLease(): Promise<DwSyncResult> {
  const locks = typeof navigator !== "undefined" ? navigator.locks : undefined;
  if (!locks?.request) return runSync();
  const held = await locks.request(SYNC_LOCK, { ifAvailable: true }, async (lock) =>
    lock ? runSync() : null
  );
  return held ?? declinedResult();
}

function createTitle(draft: DwDraft): string {
  return draft.header.title.trim() || sessionTitle({ entity: "Workshop", date: new Date(draft.createdAt) });
}

async function resolveInterruptedCreate(draft: DwDraft): Promise<DwSummary | "ambiguous" | null> {
  const title = createTitle(draft);
  const claimed = new Set(
    (await refreshDrafts()).map((row) => row.remoteId).filter((id): id is string => typeof id === "string")
  );
  const found = await listDesignWorkshops({ page: 1, pageSize: 100, search: title }).catch(() => null);
  if (!found) return null;
  const candidates = found.items.filter(
    (row) =>
      row.title === title &&
      !claimed.has(row.id) &&
      (sessionUserId ? row.createdById === sessionUserId : false)
  );
  if (candidates.length > 1) return "ambiguous";
  return candidates[0] ?? null;
}

function failure(
  message: string,
  permanent: boolean,
  attempts: number,
  skewRun: string | null = null
): DwDraftFailure {
  return { message, permanent, skewRun, at: Date.now(), attempts };
}

export function stagesUnreadAfterCreate(
  stages: Record<string, DwDraftStage>
): Record<string, DwDraftStage> {
  return Object.fromEntries(
    Object.entries(stages).map(([key, stage]) => [key, { ...stage, serverLoadedAt: null, removedFrom: [] }])
  );
}

export function adoptedIntoWorkshop(draft: DwDraft, remoteId: string): DwDraft {
  return {
    ...draft,
    remoteId,
    createSentAt: null,
    lastSyncedAt: null,
    failure: null,
    stages: stagesUnreadAfterCreate(draft.stages),
    updatedAt: Date.now()
  };
}

export function localDraftNeedsAWorkshop(draft: DwDraft): boolean {
  return draft.remoteId === null;
}

export function createMustBeDeclined(
  { alreadyOnServer, sessionMayCreate }: { alreadyOnServer: boolean; sessionMayCreate: boolean }
): boolean {
  if (alreadyOnServer) return false;
  return !sessionMayCreate;
}

export function stageRefusalIsPassLevel(error: unknown): boolean {
  if (isCredentialExpiry(error)) return true;
  const verdict = triageFailure(error);
  if (verdict.kind === "unreachable") return true;
  if (verdict.status === 429) return true;
  return verdict.status === 409 || verdict.status === 404;
}

export function mediaRefusal(name: string, cause: unknown, attempts: number): DwDraftFailure {
  const message =
    typeof cause === "string" ? cause : cause instanceof Error ? cause.message : "This file couldn't be uploaded.";
  const answered = schemaRefusalError(cause);
  const schemaRefusal = answered !== null;
  const backOff = serverAskedForTime(cause);
  return failure(
    schemaRefusal
      ? `“${name}” couldn't be uploaded: ${answered.message} Nothing is wrong with the file and nothing has been ` +
        "thrown away. Reload the page to update the app, and it will upload by itself."
      : `“${name}” couldn't be uploaded: ${message} It's still on this device. Remove it, or attach it again in ` +
        "another format, then select Try again.",
    !backOff,
    attempts,
    schemaRefusal ? APP_RUN_ID : null
  );
}

async function runSync(): Promise<DwSyncResult> {
  const result: DwSyncResult = {
    workshopsCreated: 0,
    stagesSent: 0,
    mediaUploaded: 0,
    failed: 0,
    pending: 0,
    stoppedOffline: false
  };

  if (draftSessionUnknown()) {
    result.stoppedOffline = true;
    return result;
  }

  if (sessionOwesPasswordChange()) {
    result.passwordChangeRequired = true;
    result.pending = (await pendingWork()).length;
    return result;
  }

  const work = await pendingWork();

  for (const item of work) {
    let draft = item.draft;
    if (!draftBelongsToSession(draft)) continue;
    if (blocksRetry(draft.failure)) continue;

    try {
      if (!draft.remoteId) {
        const resumed = (draft.createSentAt ?? null) === null ? null : await resolveInterruptedCreate(draft);
        if (resumed === "ambiguous") {
          await mutate(draft.localId, (current) => ({
            ...current,
            failure: failure(
              "Several workshops now share this title, so resending could duplicate it. Rename this one or delete the extra " +
                "copy, then Try again.",
              true,
              (current.failure?.attempts ?? 0) + 1
            )
          }));
          result.failed += 1;
          continue;
        }
        if (createMustBeDeclined({
          alreadyOnServer: Boolean(resumed),
          sessionMayCreate: draftSessionMayCreate()
        })) {
          await mutate(draft.localId, (current) => ({
            ...current,
            failure: failure(
              `${DESIGN_WORKSHOP_CREATE_REFUSAL} Your work is safe here — once the workshop exists, use ` +
                '"Move into a workshop" on this row.',
              true,
              (current.failure?.attempts ?? 0) + 1
            )
          }));
          result.failed += 1;
          continue;
        }
        const recorded = await underCreateClaim(async () => {
          if (!resumed) await mutate(draft.localId, (current) => ({ ...current, createSentAt: Date.now() }));
          const created =
            resumed ??
            (await createDesignWorkshop({
              title: createTitle(draft),
              templateId: draft.header.templateId,
              designerUserId: draft.header.designerUserId,
              designerUserIds: draft.header.designerUserIds,
              craftName: draft.header.craftName,
              clusterName: draft.header.clusterName,
              state: draft.header.state,
              district: draft.header.district,
              startDate: draft.header.startDate,
              endDate: draft.header.endDate,
              workshopId: draft.header.workshopId,
              notes: draft.header.notes
            }));
          return mutate(draft.localId, (current) => ({
            ...current,
            remoteId: created.id,
            createSentAt: null,
            headerDirtyAt: null,
            headerDirtyKeys: [],
            header: { ...current.header, status: String(created.status) },
            stages: stagesUnreadAfterCreate(current.stages),
            failure: null
          }));
        });
        if (!recorded?.remoteId) {
          await mutate(draft.localId, (current) => ({
            ...current,
            failure: failure(
              "Created online, but this browser's storage is full. Free some space, then Try again.",
              true,
              (current.failure?.attempts ?? 0) + 1
            )
          }));
          result.failed += 1;
          continue;
        }
        draft = recorded;
        result.workshopsCreated += 1;
      } else if (item.needsHeader) {
        const edited = draft.headerDirtyKeys?.length ? new Set(draft.headerDirtyKeys) : null;
        const whole = {
          title: draft.header.title,
          templateId: draft.header.templateId,
          craftName: draft.header.craftName,
          clusterName: draft.header.clusterName,
          state: draft.header.state,
          district: draft.header.district,
          startDate: draft.header.startDate,
          endDate: draft.header.endDate,
          workshopId: draft.header.workshopId,
          notes: draft.header.notes
        };
        await patchDesignWorkshop(
          draft.remoteId,
          edited
            ? Object.fromEntries(Object.entries(whole).filter(([key]) => edited.has(key)))
            : whole
        );
        draft =
          (await mutate(draft.localId, (current) => ({
            ...current,
            headerDirtyAt: null,
            headerDirtyKeys: []
          }))) ?? draft;
      }

      const remoteId = draft.remoteId;
      if (!remoteId) continue;

      const answer = draft.consent?.decision;
      if (draft.consent && !draft.consent.synced && (answer === "GRANTED" || answer === "REFUSED")) {
        const landed = await recordDesignWorkshopDictationConsent(remoteId, answer, {
          recordedAt: draft.consent.recordedAt || null
        })
          .then(() => true)
          .catch(() => false);
        if (landed) draft = (await markDraftConsentSynced(draft.localId)) ?? draft;
      }

      const blocked = new Set<string>();
      const refusedByStage = new Map<string, string[]>();
      const noteRefused = (stageKey: string | null, name: string) => {
        if (!stageKey) return;
        refusedByStage.set(stageKey, [...(refusedByStage.get(stageKey) ?? []), name]);
      };
      for (const media of await draftMedia(draft.localId)) {
        if (media.remoteMediaId || !media.blob) continue;
        if (blocksRetry(media.failure)) {
          if (media.stageKey) blocked.add(media.stageKey);
          if (media.failure?.permanent) noteRefused(media.stageKey, media.name);
          continue;
        }
        const file = new File([media.blob], media.name, { type: media.mimeType });
        let uploaded: Awaited<ReturnType<typeof uploadMediaBatch>>["uploaded"];
        let failed: Awaited<ReturnType<typeof uploadMediaBatch>>["failed"] = [];
        try {
          ({ uploaded, failed } = await uploadMediaBatch({
            files: [file],
            linkedRecordType: "designWorkshop",
            linkedRecordId: remoteId,
            caption: media.caption ?? undefined,
            transcribe: [Boolean(media.transcribe)]
          }));
        } catch (error) {
          if (isUnreachable(error) || isCredentialExpiry(error)) throw error;
          await noteMediaFailure(media.id, error);
          if (media.stageKey) blocked.add(media.stageKey);
          if (mediaRefusal(media.name, error, 0).permanent) noteRefused(media.stageKey, media.name);
          result.failed += 1;
          continue;
        }
        if (uploaded.length && uploaded[0]?.id) {
          await confirmLocalMedia(media.id, uploaded[0].id);
          result.mediaUploaded += 1;
          continue;
        }
        await noteMediaFailure(media.id, failed[0]?.error ?? "This file couldn't be uploaded.");
        if (media.stageKey) blocked.add(media.stageKey);
        noteRefused(media.stageKey, media.name);
        result.failed += 1;
      }

      draft = (await loadDraft(draft.localId)) ?? draft;

      const held = new Set<string>();
      for (const stage of Object.values(draft.stages)) for (const ref of unresolvedMediaRefs(stage)) held.add(ref);
      if (held.size) {
        const stranded: Array<[string, string]> = [];
        for (const media of await draftMedia(draft.localId)) {
          const ref = `${LOCAL_MEDIA_PREFIX}${media.id}`;
          if (media.remoteMediaId && held.has(ref)) stranded.push([ref, media.remoteMediaId]);
        }
        if (stranded.length) {
          draft =
            (await mutate(draft.localId, (current) => {
              let next = current;
              for (const [ref, mediaId] of stranded) next = rewriteMediaRefs(next, ref, mediaId);
              return next;
            })) ?? draft;
        }
      }

      for (const stageKey of item.stageKeys) {
        const stage = draft.stages[stageKey];
        if (!stage || blocksRetry(stage.failure)) continue;
        const outstanding = unresolvedMediaRefs(stage);
        if (outstanding.length) {
          const refusedNames = refusedByStage.get(stageKey) ?? [];
          await noteStageFailure(
            draft.localId,
            stageKey,
            refusedNames.length
              ? failure(
                  `Not sent — ${refusedNames.join(", ")} couldn't upload. Remove ${refusedNames.length === 1 ? "it" : "them"} or ` +
                    "try another format, then Try again.",
                  true,
                  (stage.failure?.attempts ?? 0) + 1
                )
              : failure(
                  `Waiting for ${outstanding.length} file${outstanding.length === 1 ? "" : "s"} to upload, then this stage sends.`,
                  false,
                  (stage.failure?.attempts ?? 0) + 1
                )
          );
          blocked.add(stageKey);
          continue;
        }

        if (stage.serverLoadedAt === null && !stageHoldsSomething(stage)) {
          await mutate(draft.localId, (current) => {
            const target = current.stages[stageKey];
            if (!target || stageHoldsSomething(target)) return current;
            return {
              ...current,
              stages: { ...current.stages, [stageKey]: { ...target, dirtyAt: null, removedFrom: [], failure: null } }
            };
          });
          continue;
        }

        const spec = await stageSpecFor(draft, stageKey);
        if (!spec) {
          await noteStageFailure(
            draft.localId,
            stageKey,
            failure(
              `Stage “${stageKey}” isn't in this version of the app. Reload online, then Try again.`,
              true,
              (stage.failure?.attempts ?? 0) + 1,
              APP_RUN_ID
            )
          );
          result.failed += 1;
          continue;
        }

        const { entries } = buildStageEntries(spec, stage);

        if (!entries.length && !stageHoldsSomething(stage)) {
          await mutate(draft.localId, (current) => {
            const target = current.stages[stageKey];
            if (!target || stageHoldsSomething(target)) return current;
            return {
              ...current,
              stages: { ...current.stages, [stageKey]: { ...target, dirtyAt: null, removedFrom: [], failure: null } }
            };
          });
          continue;
        }

        const sweep = stageSweep(spec, stage);
        if (!entries.length && !sweep.emptiedEntities.length) {
          result.pending += 1;
          continue;
        }

        let saved;
        try {
          saved = await saveDesignWorkshopStage(remoteId, stageKey, {
            entries,
            replaceCollections: sweep.replaceCollections,
            emptiedEntities: sweep.emptiedEntities,
            submit: false,
            ...copyMarksBody(stage)
          });
        } catch (error) {
          const answered = error instanceof ApiError;
          if (!answered || stageRefusalIsPassLevel(error)) throw error;
          const said = error.message;
          const schemaRefusal = isSchemaRefusal(error);
          await noteStageFailure(
            draft.localId,
            stageKey,
            failure(
              schemaRefusal
                ? `Stage “${stageKey}” couldn't be sent: ${said} Your work is safe. Reload to update the app.`
                : error.status === 403
                  ?
                    `Stage “${stageKey}” couldn't be saved: ${said} It's safe on this device.`
                  : `Stage “${stageKey}” couldn't be saved: ${said} Correct the answer, then Try again.`,
              true,
              (stage.failure?.attempts ?? 0) + 1,
              schemaRefusal ? APP_RUN_ID : null
            )
          );
          result.failed += 1;
          continue;
        }

        const rejected = refusedAnswersToShow(saved.refusedAnswers, saved.errors);
        await mutate(draft.localId, (current) => {
          const now = Date.now();
          const target = current.stages[stageKey];
          if (!target) return current;
          return {
            ...current,
            stages: {
              ...current.stages,
              [stageKey]: {
                ...target,
                ...unsentAfterPush(target, { dirtyAt: stage.dirtyAt, removedFrom: sweep.emptiedEntities }),
                ...copiesAfterPush(target, copyMarksBody(stage).copiedFrom),
                lastPushedAt: now,
                foldNote: null,
                completeness: saved.completeness ?? target.completeness,
                failure: rejected
                  ? failure(
                      `${rejected} answer${rejected === 1 ? "" : "s"} couldn't be saved; the old ${rejected === 1 ? "value was" : "values were"} ` +
                        "kept. Open the stage to see which.",
                      true,
                      (target.failure?.attempts ?? 0) + 1
                    )
                  : null
              }
            },
            lastSyncedAt: now
          };
        });
        if (rejected) result.failed += 1;
        else result.stagesSent += 1;
      }

      if (!blocked.size) {
        await mutate(draft.localId, (current) => ({ ...current, lastSyncedAt: Date.now(), failure: null }));
      }
    } catch (error) {
      if (isPasswordChangeRefusal(error)) {
        result.passwordChangeRequired = true;
        break;
      }
      if (isCredentialExpiry(error)) {
        result.credentialExpired = true;
        break;
      }
      if (isUnreachable(error) || serverAskedForTime(error)) {
        result.stoppedOffline = true;
        break;
      }
      const status = error instanceof ApiError ? error.status : 0;
      const schemaDrift = schemaRefusalError(error);
      await mutate(draft.localId, (current) => ({
        ...current,
        failure: failure(
          status === 409 || status === 404
            ?
              "This workshop is no longer available to you. Your work is safe here — ask an admin to restore access."
            : schemaDrift
              ? `Couldn't send: ${schemaDrift.message} Your work is safe. Reload to update the app.`
              : error instanceof Error
                ? error.message
                : "This workshop couldn't be saved.",
          true,
          (current.failure?.attempts ?? 0) + 1,
          schemaDrift ? APP_RUN_ID : null
        )
      }));
      result.failed += 1;
    }
  }

  result.pending = (await pendingWork()).length;
  return result;
}

async function stageSpecFor(draft: DwDraft, stageKey: string): Promise<DwStage | null> {
  const registry = (await cachedRegistry(draft.registryVersion || undefined)) ?? (await cachedRegistry());
  return registry?.stages.find((stage) => stage.key === stageKey) ?? null;
}

async function noteStageFailure(localDraftId: string, stageKey: string, next: DwDraftFailure): Promise<void> {
  await mutate(localDraftId, (draft) => {
    const stage = draft.stages[stageKey];
    if (!stage) return draft;
    return { ...draft, stages: { ...draft.stages, [stageKey]: { ...stage, failure: next } } };
  });
}

async function noteMediaFailure(localMediaId: string, cause: unknown): Promise<void> {
  const message =
    typeof cause === "string" ? cause : cause instanceof Error ? cause.message : "This file couldn't be uploaded.";
  await transact([STORE_MEDIA], "readwrite", async (stores) => {
    const media = await req<DwDraftMedia | undefined>(
      stores[STORE_MEDIA].get(localMediaId) as IDBRequest<DwDraftMedia | undefined>
    );
    if (!media) return;
    const attempts = media.attempts + 1;
    await req(
      stores[STORE_MEDIA].put({
        ...media,
        attempts,
        lastError: message,
        failure: mediaRefusal(media.name, cause, attempts)
      })
    );
  }).catch(() => {
    noteStoreFailure("write");
  });
}

export async function retryDraft(id: string): Promise<void> {
  const cleared = await mutate(id, (draft) => ({
    ...draft,
    failure: null,
    stages: Object.fromEntries(
      Object.entries(draft.stages).map(([key, stage]) => [key, { ...stage, failure: null }])
    )
  }));
  if (!cleared) return;
  await transact([STORE_MEDIA], "readwrite", async (stores) => {
    const store = stores[STORE_MEDIA];
    const rows = await req<DwDraftMedia[]>(
      store.index(MEDIA_BY_DRAFT).getAll(cleared.localId) as IDBRequest<DwDraftMedia[]>
    );
    for (const row of rows) {
      if (!row.failure) continue;
      await req(store.put({ ...row, failure: null }));
    }
  }).catch(() => {
    noteStoreFailure("write");
  });
}

export function copyMarksBody(stage: DwDraftStage | undefined): { copiedFrom?: Record<string, string> } {
  const marks = stage?.copiedFrom ?? {};
  return Object.keys(marks).length ? { copiedFrom: marks } : {};
}

export function copiesAfterPush(
  stage: DwDraftStage,
  sent: Record<string, string> | undefined
): { copiedFrom?: Record<string, string>; provenance?: DwStageProvenance } {
  const marks = stage.copiedFrom ?? {};
  const acknowledged = Object.entries(sent ?? {}).filter(([key, source]) => marks[key] === source);
  if (!acknowledged.length) return {};
  const remaining = { ...marks };
  const singleton = { ...(stage.provenance?.singleton ?? {}) };
  for (const [key, source] of acknowledged) {
    delete remaining[key];
    if (singleton[key]?.source !== DW_COPIED_SOURCE) {
      singleton[key] = { source: DW_COPIED_SOURCE, copiedFrom: { workshopId: source, title: "" } };
    }
  }
  return { copiedFrom: remaining, provenance: { ...(stage.provenance ?? {}), singleton } };
}
