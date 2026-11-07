"use client";

import { byCount, countNoun } from "@/lib/format";
import { ApiError, apiFetch, getSessionMarker, sessionOwesPasswordChange } from "@/lib/api";
import { possibleDuplicateHold, type DuplicateCandidate } from "@/lib/duplicates";
import {
  isCredentialExpiry,
  isPasswordChangeRefusal,
  isTransient,
  isUnreachable,
  schemaRefusalError,
  underlyingIsTransient
} from "@/lib/failureTriage";
import { MediaBatchError, uploadMediaBatch, type BatchResult } from "@/lib/media";
import { transcribeFlags } from "@/lib/transcript/uploadChoice";

const DB_NAME = "field-repo-outbox";
const DB_VERSION = 1;
const STORE = "entries";

export type OutboxMediaBatch = {
  files: File[];
  linkedRecordType: string;
  caption?: string;
  location?: unknown;
  recordedAt?: string | null;
  recordedTimezone?: string | null;
  extraMetadata?: Record<string, unknown>;
  transcribeAudio?: boolean;
  alwaysTranscribe?: boolean;
  transcribe?: boolean[];
  stepIndex?: number;
};

export function withTranscribeFlags(batch: OutboxMediaBatch): OutboxMediaBatch {
  return batch.transcribe ? batch : { ...batch, transcribe: transcribeFlags(batch.files) };
}

export function transcribeFlagsFor(batch: OutboxMediaBatch, subset: readonly File[]): boolean[] {
  return subset.map((file) => {
    const position = batch.files.indexOf(file);
    return position >= 0 ? Boolean(batch.transcribe?.[position]) : false;
  });
}

export type OutboxEntry = {
  id?: number;
  label: string;
  createdAt: number;
  endpoint: string;
  method: "POST" | "PATCH";
  body: string;
  media: OutboxMediaBatch[];
  attempts: number;
  failure: string | null;
  skewRun?: string | null;
  savedIdIn?: string;
  created?: boolean;
  createdId?: string | null;
  createdStepIds?: string[];
  uploadedBatches?: number[];
  unfiled?: Record<string, string>;
  danglingField?: string | null;
  possibleDuplicate?: { candidates: DuplicateCandidate[] } | null;
  attachedToExisting?: boolean;
  ownerUserId?: string | null;
  clientKey?: string | null;
  idempotencyKey?: string | null;
};

export const UNFILED_BY_CHOICE = "chosen";

export const UNFILED_NO_OPTIONS = "noOptions";

export const REFERENCE_FIELD_NOUNS: ReadonlyArray<readonly [string, string]> = [
  ["designWorkshopId", "design & prototype workshop"],
  ["workshopId", "workshop"],
  ["artisanId", "artisan"],
  ["craftId", "craft"],
  ["productId", "product"],
  ["toolId", "toolkit"],
  ["processId", "process"],
  ["questionnaireInterviewId", "interview"],
  ["locationId", "place"]
];

export function referenceFieldNoun(key: string): string {
  return REFERENCE_FIELD_NOUNS.find(([name]) => name === key)?.[1] ?? key;
}

export const REFERENCE_LIST_SIBLINGS: Readonly<Record<string, string>> = {
  artisanId: "artisanIds",
  craftId: "craftIds"
};

export const WORKSHOP_LINK_KEYS: readonly string[] = ["designWorkshopId", "workshopId"];

export function unfiledLinkReason(selectedId: string, baselineId: string, hadOptions: boolean): string | null {
  if (selectedId.trim()) return null;
  if (baselineId.trim()) return UNFILED_BY_CHOICE;
  return hadOptions ? UNFILED_BY_CHOICE : UNFILED_NO_OPTIONS;
}

export function workshopUnfiledReasons(reasons: {
  designWorkshop?: string | null;
  workshop?: string | null;
}): Record<string, string> {
  const map: Record<string, string> = {};
  if (reasons.designWorkshop) map.designWorkshopId = reasons.designWorkshop;
  if (reasons.workshop) map.workshopId = reasons.workshop;
  return map;
}

export function clearedLinkKeys(entry: Pick<OutboxEntry, "unfiled">): string[] {
  return Object.entries(entry.unfiled ?? {})
    .filter(([, reason]) => reason === UNFILED_BY_CHOICE)
    .map(([key]) => key);
}

export function emptyPickerKeys(entry: Pick<OutboxEntry, "unfiled">): string[] {
  return Object.entries(entry.unfiled ?? {})
    .filter(([, reason]) => reason === UNFILED_NO_OPTIONS)
    .map(([key]) => key);
}

export function entryAlreadyCreated(entry: Pick<OutboxEntry, "created" | "createdId" | "method">): boolean {
  if (entry.created === true) return true;
  if (entry.method !== "POST") return false;
  return typeof entry.createdId === "string" && entry.createdId.length > 0;
}

export function danglingKeys(entry: Pick<OutboxEntry, "danglingField">): string[] {
  return (entry.danglingField ?? "")
    .split(",")
    .map((key) => key.trim())
    .filter(Boolean);
}

let dbPromise: Promise<IDBDatabase> | null = null;

function openDb(): Promise<IDBDatabase> {
  if (typeof indexedDB === "undefined") return Promise.reject(new Error("IndexedDB unavailable"));
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains(STORE)) {
          db.createObjectStore(STORE, { keyPath: "id", autoIncrement: true });
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

function tx<T>(mode: IDBTransactionMode, run: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return openDb()
    .then(
      (db) =>
        new Promise<T>((resolve, reject) => {
          const transaction = db.transaction(STORE, mode);
          const request = run(transaction.objectStore(STORE));
          request.onsuccess = () => resolve(request.result);
          request.onerror = () => reject(request.error ?? new Error("Couldn't save to this device."));
        })
    )
    .catch((error: unknown) => {
      noteStoreFailure(mode === "readonly" ? "read" : "write");
      throw error;
    });
}

function updateEntry(id: number, apply: (row: OutboxEntry) => OutboxEntry | null): Promise<boolean> {
  return openDb()
    .then(
      (db) =>
        new Promise<boolean>((resolve, reject) => {
          const transaction = db.transaction(STORE, "readwrite");
          const store = transaction.objectStore(STORE);
          const read = store.get(id) as IDBRequest<OutboxEntry | undefined>;
          let wrote = false;
          read.onsuccess = () => {
            const row = read.result;
            if (!row) return;
            const next = apply(row);
            if (!next) return;
            const write = store.put(next);
            write.onsuccess = () => {
              wrote = true;
            };
          };
          transaction.oncomplete = () => resolve(wrote);
          transaction.onerror = () => reject(transaction.error ?? new Error("Couldn't save to this device."));
          transaction.onabort = () => reject(transaction.error ?? new Error("Saving to this device was interrupted."));
        })
    )
    .catch((error: unknown) => {
      noteStoreFailure("write");
      throw error;
    });
}

const listeners = new Set<() => void>();
let cache: OutboxEntry[] = [];

function publish() {
  listeners.forEach((listener) => listener());
}

export function subscribeOutbox(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getOutboxSnapshot(): OutboxEntry[] {
  return cache;
}

const SERVER_SNAPSHOT: OutboxEntry[] = [];
export function getServerOutboxSnapshot(): OutboxEntry[] {
  return SERVER_SNAPSHOT;
}

export type OutboxStoreHealth = {
  readFailedAt: number | null;
  writeFailedAt: number | null;
};

let health: OutboxStoreHealth = { readFailedAt: null, writeFailedAt: null };

export function getOutboxHealth(): OutboxStoreHealth {
  return health;
}

const SERVER_HEALTH: OutboxStoreHealth = { readFailedAt: null, writeFailedAt: null };
export function getServerOutboxHealth(): OutboxStoreHealth {
  return SERVER_HEALTH;
}

export function outboxIsAnswering(state: OutboxStoreHealth = health): boolean {
  return state.readFailedAt === null && state.writeFailedAt === null;
}

function noteStoreFailure(kind: "read" | "write"): void {
  const now = Date.now();
  health = kind === "read" ? { ...health, readFailedAt: now } : { ...health, writeFailedAt: now };
  publish();
}

export function acknowledgeOutboxTrouble(): void {
  health = { readFailedAt: null, writeFailedAt: null };
  publish();
}

export async function refreshOutbox(): Promise<OutboxEntry[]> {
  try {
    const rows = await tx<OutboxEntry[]>("readonly", (store) => store.getAll() as IDBRequest<OutboxEntry[]>);
    cache = rows.sort((a, b) => a.createdAt - b.createdAt);
    if (health.readFailedAt !== null) health = { ...health, readFailedAt: null };
  } catch {
    cache = [];
  }
  publish();
  return cache;
}

let outboxSessionUserId: string | null = null;

export function setOutboxSessionUser(userId: string | null): void {
  if (outboxSessionUserId === userId) return;
  outboxSessionUserId = userId;
  publish();
}

export function outboxSessionUser(): string | null {
  return outboxSessionUserId;
}

export function outboxEntryIsForAnotherAccount(
  ownerUserId: string | null | undefined,
  signedInUserId: string | null
): boolean {
  if (!signedInUserId) return false;
  if (ownerUserId === null || ownerUserId === undefined) return false;
  return ownerUserId !== signedInUserId;
}

export function outboxEntriesForAnotherAccount(
  entries: readonly OutboxEntry[],
  signedInUserId: string | null
): number {
  return entries.filter((entry) => outboxEntryIsForAnotherAccount(entry.ownerUserId, signedInUserId)).length;
}

export function outboxEntryIsHeld(ownerUserId: string | null | undefined, signedInUserId: string | null): boolean {
  return !signedInUserId || !ownerUserId || ownerUserId !== signedInUserId;
}

export function outboxEntriesForThisAccount(entries: readonly OutboxEntry[], signedInUserId: string | null): OutboxEntry[] {
  return entries.filter((entry) => !outboxEntryIsHeld(entry.ownerUserId, signedInUserId));
}

export function outboxEntriesWithoutOwner(entries: readonly OutboxEntry[], signedInUserId: string | null): number {
  return signedInUserId ? entries.filter((entry) => !entry.ownerUserId).length : 0;
}

export async function claimUnownedOutboxEntries(): Promise<void> {
  const owner = outboxSessionUserId;
  if (!owner) return;
  for (const entry of await refreshOutbox()) {
    if (entry.ownerUserId || entry.id === undefined) continue;
    await updateEntry(entry.id, (row) => (row.ownerUserId ? null : { ...row, ownerUserId: owner }));
  }
  await refreshOutbox();
}

export async function discardHeldOutboxEntries(which: "otherAccount" | "unowned"): Promise<void> {
  const owner = outboxSessionUserId;
  if (!owner) return;
  for (const entry of await refreshOutbox()) {
    if (entry.id === undefined) continue;
    const held = which === "unowned" ? !entry.ownerUserId : outboxEntryIsForAnotherAccount(entry.ownerUserId, owner);
    if (held) await tx("readwrite", (store) => store.delete(entry.id!));
  }
  await refreshOutbox();
}

export function outboxOtherAccountLine(count: number): string {
  return `${count} ${count === 1 ? "entry was" : "entries were"} captured by another account — sign in as them to send.`;
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

let queuedHere = 0;

export function getOutboxQueuedHere(): number {
  return queuedHere;
}

export function getServerOutboxQueuedHere(): number {
  return 0;
}

const CLIENT_KEY_ENDPOINTS: ReadonlySet<string> = new Set([
  "/workshops",
  "/products",
  "/tools",
  "/processes"
]);

const IDEMPOTENCY_HEADER_ENDPOINTS: readonly RegExp[] = [
  /^\/questionnaire\/interviews\/?$/,
  /^\/questionnaires\/?$/,
  /^\/questionnaires\/[^/]+\/entries\/?$/,
  /^\/questionnaires\/[^/]+\/reuse\/?$/
];

export function outboxSendsIdempotencyHeader(entry: Pick<OutboxEntry, "endpoint" | "method">): boolean {
  return entry.method === "POST" && IDEMPOTENCY_HEADER_ENDPOINTS.some((pattern) => pattern.test(entry.endpoint));
}

export function replayHeaders(entry: Pick<OutboxEntry, "idempotencyKey">): Record<string, string> {
  const key = typeof entry.idempotencyKey === "string" ? entry.idempotencyKey.trim() : "";
  return key ? { "Idempotency-Key": key } : {};
}

export function outboxMintsClientKey(
  entry: Pick<OutboxEntry, "endpoint" | "method">
): boolean {
  return entry.method === "POST" && CLIENT_KEY_ENDPOINTS.has(entry.endpoint);
}

function mintClientKey(): string | null {
  try {
    return typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
      ? crypto.randomUUID()
      : null;
  } catch {
    return null;
  }
}

export async function queueOffline(
  entry: Omit<OutboxEntry, "id" | "createdAt" | "attempts" | "failure" | "ownerUserId">
): Promise<void> {
  void requestPersistence();
  await tx("readwrite", (store) =>
    store.add({
      ...entry,
      media: (entry.media ?? []).map(withTranscribeFlags),
      createdAt: Date.now(),
      attempts: 0,
      failure: null,
      ownerUserId: outboxSessionUserId,
      clientKey: outboxMintsClientKey(entry) ? (entry.clientKey ?? mintClientKey()) : null,
      idempotencyKey: outboxSendsIdempotencyHeader(entry) ? (entry.idempotencyKey ?? mintClientKey()) : null
    } as OutboxEntry)
  );
  queuedHere += 1;
  await refreshOutbox();
}

export async function discardOutboxEntry(id: number): Promise<void> {
  await tx("readwrite", (store) => store.delete(id));
  await refreshOutbox();
}

export async function retryOutboxEntry(id: number): Promise<void> {
  await updateEntry(id, (row) =>
    row.failure === null && !row.skewRun && !row.danglingField
      ? null
      :
        { ...row, failure: null, skewRun: null, danglingField: null }
  );
  await refreshOutbox();
}

export function possibleDuplicateSentence(candidates: DuplicateCandidate[]): string {
  const top = candidates[0]?.artisan;
  if (!top) return "Not saved yet. This may already be recorded.";
  return `Not saved yet. This looks like ${top.name}${top.place ? ` (${top.place})` : ""}.`;
}

export function bodyWithAcknowledged(body: string, ids: string[]): string {
  let parsed: Record<string, unknown>;
  try {
    parsed = JSON.parse(body) as Record<string, unknown>;
  } catch {
    return body;
  }
  const before = Array.isArray(parsed.duplicateAcknowledged) ? (parsed.duplicateAcknowledged as string[]) : [];
  parsed.duplicateAcknowledged = Array.from(new Set([...before, ...ids])).slice(0, 20);
  return JSON.stringify(parsed);
}

export function samePersonEntry(row: OutboxEntry, artisanId: string): OutboxEntry {
  return {
    ...row,
    created: true,
    createdId: artisanId,
    attachedToExisting: true,
    possibleDuplicate: null,
    failure: null
  };
}

export function differentPersonEntry(row: OutboxEntry): OutboxEntry {
  const ids = (row.possibleDuplicate?.candidates ?? []).map((candidate) => candidate.artisan.id);
  return { ...row, body: bodyWithAcknowledged(row.body, ids), possibleDuplicate: null, failure: null };
}

export async function markSamePerson(id: number, artisanId: string): Promise<void> {
  await updateEntry(id, (row) => (row.possibleDuplicate ? samePersonEntry(row, artisanId) : null));
  await refreshOutbox();
}

export async function markDifferentPerson(id: number): Promise<void> {
  await updateEntry(id, (row) => (row.possibleDuplicate ? differentPersonEntry(row) : null));
  await refreshOutbox();
}

export function danglingCandidates(body: string): string[] {
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch {
    return [];
  }
  if (!parsed || typeof parsed !== "object") return [];
  const record = parsed as Record<string, unknown>;
  return REFERENCE_FIELD_NOUNS.filter(([key]) => typeof record[key] === "string" && (record[key] as string).trim()).map(
    ([key]) => key
  );
}

export function isDanglingReference(error: unknown): boolean {
  if (!(error instanceof ApiError)) return false;
  if (error.status === 404) return true;
  if (error.status !== 422) return false;
  const said = `${error.message}`.toLowerCase();
  return said.includes("not found") || said.includes("does not exist") || said.includes("no longer exists");
}

function anyOneOf(nouns: readonly string[]): string {
  const withArticles = nouns.map((noun) => `${/^[aeiou]/i.test(noun) ? "an" : "a"} ${noun}`);
  if (withArticles.length <= 1) return withArticles[0] ?? "a record";
  return `${withArticles.slice(0, -1).join(", ")} or ${withArticles[withArticles.length - 1]}`;
}

export function outboxDanglingSentence(said: string, nouns: readonly string[], files: number, isCorrection: boolean): string {
  const subject = isCorrection ? "This correction" : "This record";
  const head =
    nouns.length === 1
      ? `${subject} points at ${anyOneOf(nouns)} that no longer exists.`
      :
        `${subject} points at something that no longer exists: ${anyOneOf(nouns)}.`;
  const carrying = files > 0 ? ` and the ${countNoun(files, "file")} saved with it` : "";
  const isAre = files > 0 ? "are" : "is";
  const trimmed = said.trim();
  const server = trimmed ? ` Reason: ${/[.!?]$/.test(trimmed) ? trimmed : `${trimmed}.`}` : "";
  return (
    `${head} Nothing is lost — open it, choose another, and it will send.${server} ` +
    `This entry${carrying} ${isAre} still in this browser; nothing was deleted. ` +
    "Trying again without a change won't help."
  );
}

export function repickEmptyLine(noun: string, listed: boolean): string {
  return listed
    ? `No ${noun} is open to this account. An administrator can give you access to one; until then the record stays here, and nothing is deleted.`
    : `The ${noun} list couldn't be loaded just now. Nothing has been lost — the record and anything saved with it stay in this browser.`;
}

export function outboxSentUnfiledMessage(label: string, nouns: readonly string[]): string {
  const list =
    nouns.length === 0
      ? "workshop"
      : nouns.length === 1
        ? nouns[0]
        : `${nouns.slice(0, -1).join(", ")} or ${nouns[nouns.length - 1]}`;
  return (
    `“${label}” was sent, filed under nothing: there was no ${list} to choose from in this browser. ` +
    "Open the record and file it now."
  );
}

export async function repickOutboxEntry(id: number, field: string, chosen: string | null): Promise<void> {
  await updateEntry(id, (row) => {
    const body = bodyWithRepickedReference(row.body, field, chosen);
    if (body === null) return null;
    const unfiled = { ...(row.unfiled ?? {}) };
    if (chosen === null) unfiled[field] = UNFILED_BY_CHOICE;
    else delete unfiled[field];
    return { ...row, body, unfiled, failure: null, skewRun: null, danglingField: null };
  });
  await refreshOutbox();
}

export function bodyWithRepickedReference(body: string, field: string, chosen: string | null): string | null {
  let parsed: Record<string, unknown>;
  try {
    const candidate: unknown = JSON.parse(body);
    if (!candidate || typeof candidate !== "object" || Array.isArray(candidate)) return null;
    parsed = candidate as Record<string, unknown>;
  } catch {
    return null;
  }
  const previous = typeof parsed[field] === "string" ? (parsed[field] as string).trim() : "";
  parsed[field] = chosen;
  const plural = REFERENCE_LIST_SIBLINGS[field];
  const list = plural ? parsed[plural] : undefined;
  if (plural && previous && Array.isArray(list) && list.every((entry) => typeof entry === "string")) {
    const next: string[] = [];
    for (const entry of list as string[]) {
      const id = entry.trim() === previous ? chosen : entry;
      if (id && !next.includes(id)) next.push(id);
    }
    parsed[plural] = next;
  }
  return JSON.stringify(parsed);
}

async function markFailure(
  entry: OutboxEntry,
  failure: string,
  skewRun: string | null = null,
  danglingField: string | null = null
): Promise<void> {
  await updateEntry(entry.id!, (row) => ({ ...row, attempts: row.attempts + 1, failure, skewRun, danglingField }));
}

async function persistProgress(entry: OutboxEntry): Promise<boolean> {
  return updateEntry(entry.id!, (row) => ({
    ...row,
    created: entry.created,
    createdId: entry.createdId,
    createdStepIds: entry.createdStepIds,
    uploadedBatches: entry.uploadedBatches,
    media: entry.media
  }));
}

function bodyWithClearances(entry: OutboxEntry): string {
  const cleared = clearedLinkKeys(entry);
  const clientKey = typeof entry.clientKey === "string" && entry.clientKey ? entry.clientKey : null;
  if (cleared.length === 0 && !clientKey) return entry.body;
  try {
    const parsed: unknown = JSON.parse(entry.body);
    if (!parsed || typeof parsed !== "object") return entry.body;
    const record = parsed as Record<string, unknown>;
    for (const key of cleared) record[key] = null;
    if (clientKey) record.clientKey = clientKey;
    return JSON.stringify(record);
  } catch {
    return entry.body;
  }
}

function pendingFileCount(entry: OutboxEntry): number {
  return entry.media.reduce((sum, batch) => sum + batch.files.length, 0);
}

export {
  isCredentialExpiry,
  isSchemaRefusal,
  isTransient,
  isUnreachable,
  schemaRefusalError,
  serverAskedForTime,
  triageAsThrown,
  triageFailure,
  underlyingError,
  underlyingIsTransient
} from "@/lib/failureTriage";

export const APP_RUN_ID: string = (() => {
  const api = typeof crypto !== "undefined" ? crypto : undefined;
  if (api && typeof api.randomUUID === "function") return api.randomUUID();
  return `run-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
})();

export type RecordedRefusal = {
  permanent: boolean;
  skewRun?: string | null;
};

export function blocksRetry(refusal: RecordedRefusal | null | undefined): boolean {
  if (!refusal?.permanent) return false;
  if (!refusal.skewRun) return true;
  return refusal.skewRun === APP_RUN_ID;
}

export function splitUnsendableFiles(files: File[]): { sendable: File[]; empty: File[] } {
  return { sendable: files.filter((file) => file.size > 0), empty: files.filter((file) => file.size === 0) };
}

export function outstandingFiles(
  batchFiles: File[],
  attempted: File[],
  uploadedByIndex: BatchResult["uploadedByIndex"]
): File[] {
  const landed = new Set<File>();
  uploadedByIndex.forEach((media, position) => {
    const file = attempted[position];
    if (media && file) landed.add(file);
  });
  return batchFiles.filter((file) => !landed.has(file));
}

export function refusedFileNames(error: unknown, attempted: File[]): Array<{ name: string }> {
  if (error instanceof MediaBatchError && error.failures.length) {
    return error.failures.map((failure) => ({ name: failure.name }));
  }
  return attempted.map((file) => ({ name: file.name }));
}

type ReplayAnswer = {
  id?: unknown;
  steps?: Array<{ id: string }>;
} & Record<string, unknown>;

function savedRow(answer: ReplayAnswer | null | undefined, savedIdIn: string | undefined): ReplayAnswer | undefined {
  if (!answer) return undefined;
  if (!savedIdIn) return answer;
  const nested = answer[savedIdIn];
  return nested && typeof nested === "object" ? (nested as ReplayAnswer) : undefined;
}

export type SyncResult = {
  synced: number;
  failed: number;
  remaining: number;
  stoppedOffline: boolean;
  declined: boolean;
  credentialExpired: boolean;
  passwordChangeRequired?: boolean;
  storeUnreadable: boolean;
  sentUnfiled?: string[];
  otherAccount?: number;
};

let syncing: Promise<SyncResult> | null = null;

const SYNC_LOCK = "field-repo-outbox-sync";

export function syncOutbox(): Promise<SyncResult> {
  if (syncing) return syncing;
  syncing = runSyncUnderLease().finally(() => {
    syncing = null;
  });
  return syncing;
}

async function declinedResult(): Promise<SyncResult> {
  const remaining = (await refreshOutbox()).length;
  return {
    synced: 0,
    failed: 0,
    remaining,
    stoppedOffline: false,
    declined: true,
    credentialExpired: false,
    storeUnreadable: health.readFailedAt !== null,
    sentUnfiled: []
  };
}

async function runSyncUnderLease(): Promise<SyncResult> {
  const locks = typeof navigator !== "undefined" ? navigator.locks : undefined;
  if (!locks?.request) return runSync();
  const held = await locks.request(SYNC_LOCK, { ifAvailable: true }, async (lock) => (lock ? runSync() : null));
  return held ?? declinedResult();
}

async function runSync(): Promise<SyncResult> {
  const entries = await refreshOutbox();
  let synced = 0;
  let failed = 0;
  let stoppedOffline = false;
  let credentialExpired = false;
  let passwordChangeRequired = false;
  const sentUnfiled: string[] = [];
  const sessionUserId = outboxSessionUserId;
  let otherAccount = 0;

  if (typeof window !== "undefined" && !getSessionMarker()) {
    return {
      synced: 0,
      failed: 0,
      remaining: outboxEntriesForThisAccount(entries, sessionUserId).length,
      stoppedOffline: false,
      declined: false,
      credentialExpired: true,
      storeUnreadable: health.readFailedAt !== null,
      sentUnfiled: []
    };
  }

  if (sessionOwesPasswordChange()) {
    return {
      synced: 0,
      failed: 0,
      remaining: outboxEntriesForThisAccount(entries, sessionUserId).length,
      stoppedOffline: false,
      declined: false,
      credentialExpired: false,
      passwordChangeRequired: true,
      storeUnreadable: health.readFailedAt !== null,
      sentUnfiled: []
    };
  }

  for (const entry of entries) {
    if (blocksRetry({ permanent: entry.failure !== null, skewRun: entry.skewRun })) continue;
    if (outboxEntryIsForAnotherAccount(entry.ownerUserId, sessionUserId)) {
      otherAccount += 1;
      continue;
    }
    if (outboxEntryIsHeld(entry.ownerUserId, sessionUserId)) continue;
    const progress: OutboxEntry = { ...entry };
    if (!progress.created && entryAlreadyCreated(progress)) {
      progress.created = true;
      if (!(await persistProgress(progress))) continue;
    }
    try {
      if (!progress.created) {
        try {
          const answer = await apiFetch<ReplayAnswer>(
            progress.endpoint,
            { method: progress.method, body: bodyWithClearances(progress), headers: replayHeaders(progress) },
            { redirectOn401: false }
          );
          const saved = savedRow(answer, progress.savedIdIn);
          const createdId = typeof saved?.id === "string" && saved.id ? saved.id : null;
          if (!createdId) {
            const files = pendingFileCount(progress);
            await markFailure(
              progress,
              "This may have been saved, but it couldn't be confirmed. This entry" +
                `${files ? ` and its ${countNoun(files, "file")} are` : " is"} still in this browser. If you were on a wi-fi ` +
                "network that asks you to sign in, connect properly and check whether the record arrived before " +
                "discarding this."
            );
            failed += 1;
            continue;
          }
          progress.created = true;
          progress.createdId = createdId;
          progress.createdStepIds = (saved?.steps ?? []).map((step) => step.id);
        } catch (error) {
          const hold = possibleDuplicateHold(error);
          if (hold) {
            await updateEntry(progress.id!, (row) => ({
              ...row,
              attempts: row.attempts + 1,
              failure: possibleDuplicateSentence(hold.candidates),
              possibleDuplicate: { candidates: hold.candidates.slice(0, 5) }
            }));
            failed += 1;
            continue;
          }
          if (error instanceof ApiError && error.status === 409) {
            const files = pendingFileCount(progress);
            await markFailure(
              progress,
              `Not saved — it clashes with a record that already exists. ${error.message} Nothing was deleted: ` +
                `this entry${files ? ` and its ${countNoun(files, "file")} are` : " is"} still in this browser. Open the clashing ` +
                "record, copy anything missing, then discard this entry."
            );
            failed += 1;
            continue;
          }
          throw error;
        }
        if (!(await persistProgress(progress))) continue;
      }

      const mediaFailed: Array<{ name: string }> = [];
      const uploaded = new Set(progress.uploadedBatches ?? []);
      let entryGone = false;
      if (progress.createdId) {
        for (const [index, batch] of progress.media.entries()) {
          if (uploaded.has(index) || !batch.files.length) continue;
          const { sendable, empty } = splitUnsendableFiles(batch.files);
          mediaFailed.push(...empty.map((file) => ({ name: file.name })));
          if (!sendable.length) continue;
          const linkedRecordId =
            batch.stepIndex === undefined ? progress.createdId : (progress.createdStepIds?.[batch.stepIndex] ?? null);
          if (!linkedRecordId) {
            mediaFailed.push(...sendable.map((file) => ({ name: file.name })));
            continue;
          }
          let result: Awaited<ReturnType<typeof uploadMediaBatch>>;
          try {
            result = await uploadMediaBatch({
              files: sendable,
              linkedRecordType: batch.linkedRecordType,
              linkedRecordId,
              caption: batch.caption,
              location: batch.location,
              recordedAt: batch.recordedAt ?? undefined,
              recordedTimezone: batch.recordedTimezone ?? undefined,
              extraMetadata: batch.extraMetadata,
              alwaysTranscribe: batch.alwaysTranscribe ?? batch.transcribeAudio === true,
              transcribe: transcribeFlagsFor(batch, sendable),
              redirectOn401: false
            });
          } catch (error) {
            if (isCredentialExpiry(error) || isUnreachable(error) || underlyingIsTransient(error)) throw error;
            mediaFailed.push(...refusedFileNames(error, sendable));
            continue;
          }
          mediaFailed.push(...result.failed);
          const outstanding = outstandingFiles(batch.files, sendable, result.uploadedByIndex);
          if (outstanding.length) {
            progress.media = progress.media.map((queued, position) =>
              position === index ? { ...queued, files: outstanding, transcribe: transcribeFlagsFor(queued, outstanding) } : queued
            );
          } else {
            uploaded.add(index);
          }
          progress.uploadedBatches = Array.from(uploaded);
          if (!(await persistProgress(progress))) {
            entryGone = true;
            break;
          }
        }
      }
      if (entryGone) continue;
      if (mediaFailed.length) {
        await markFailure(
          progress,
          `Saved, but ${countNoun(mediaFailed.length, "file")} did not upload: ${mediaFailed.map((f) => f.name).join(", ")}. ` +
            byCount(
              mediaFailed.length,
              "Nothing has been thrown away — it is still on this device, and Try again re-sends only that. If " +
                "it keeps failing, attach it again on the record.",
              "Nothing has been thrown away — they are still on this device, and Try again re-sends only those. If " +
                "they keep failing, attach them again on the record."
            )
        );
        failed += 1;
        await refreshOutbox();
        continue;
      }

      const unfiledNouns = emptyPickerKeys(progress).map(referenceFieldNoun);
      if (unfiledNouns.length > 0) sentUnfiled.push(outboxSentUnfiledMessage(progress.label, unfiledNouns));
      await discardOutboxEntry(progress.id!);
      synced += 1;
    } catch (error) {
      if (isPasswordChangeRefusal(error)) {
        passwordChangeRequired = true;
        break;
      }
      if (isCredentialExpiry(error)) {
        credentialExpired = true;
        break;
      }
      if (underlyingIsTransient(error)) {
        stoppedOffline = true;
        break;
      }
      const answered = schemaRefusalError(error);
      if (answered) {
        const files = pendingFileCount(progress);
        await markFailure(
          progress,
          `This entry couldn't be sent: ${answered.message} Nothing you entered is wrong and nothing has been ` +
            `thrown away — this entry${files ? ` and its ${countNoun(files, "file")} stay` : " stays"} on this device. Reload the ` +
            "page to update the app, and it will send by itself.",
          APP_RUN_ID
        );
        failed += 1;
        continue;
      }
      const candidates = isDanglingReference(error) ? danglingCandidates(progress.body) : [];
      if (candidates.length > 0) {
        await markFailure(
          progress,
          outboxDanglingSentence(
            error instanceof Error ? error.message : "",
            candidates.map(referenceFieldNoun),
            pendingFileCount(progress),
            progress.method === "PATCH"
          ),
          null,
          candidates.join(",")
        );
        failed += 1;
        continue;
      }
      await markFailure(progress, error instanceof Error ? error.message : "This entry couldn't be saved.");
      failed += 1;
    }
  }

  const remaining = outboxEntriesForThisAccount(await refreshOutbox(), sessionUserId).length;
  return {
    synced,
    failed,
    remaining,
    stoppedOffline,
    declined: false,
    credentialExpired,
    passwordChangeRequired,
    storeUnreadable: health.readFailedAt !== null,
    sentUnfiled,
    otherAccount
  };
}

export type SaveOutcome<T> = { queued: true } | { queued: false; saved: T };

export async function saveOrQueue<T extends { id: string }>({
  label,
  endpoint,
  method,
  body,
  media
}: {
  label: string;
  endpoint: string;
  method: "POST" | "PATCH";
  body: unknown;
  media?: OutboxMediaBatch[];
}): Promise<SaveOutcome<T>> {
  const payload = JSON.stringify(body);
  const idempotencyKey = outboxSendsIdempotencyHeader({ endpoint, method }) ? mintClientKey() : null;
  const queue = async () => {
    await queueOffline({
      label,
      endpoint,
      method,
      body: payload,
      media: (media ?? []).filter((batch) => batch.files.length),
      idempotencyKey
    });
    return { queued: true } as const;
  };

  if (typeof navigator !== "undefined" && navigator.onLine === false) return queue();

  try {
    return {
      queued: false,
      saved: await apiFetch<T>(endpoint, { method, body: payload, headers: replayHeaders({ idempotencyKey }) })
    };
  } catch (error) {
    if (isTransient(error) && !(error instanceof ApiError)) return queue();
    throw error;
  }
}
