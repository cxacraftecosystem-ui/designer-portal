"use client";

/**
 * The durable local store for a design workshop under construction — the header, all 22 stages,
 * every row of every collection and every photograph, held in this browser in a shape the app can
 * READ BACK, not merely replay.
 *
 * WHY THIS EXISTS, AND WHY `lib/offline.ts` IS NOT IT. That module is an excellent SEND QUEUE and
 * this one deliberately does not replace it. But an outbox entry is an opaque `body: string` plus a
 * list of `File`s: nothing can read a field out of one, nothing can edit one, and nothing can score
 * one for completeness. A designer who saves a stage with no signal can queue it and then cannot
 * open the workshop, cannot correct the number they mistyped, cannot see how much of stage 13 is
 * still blank and cannot look at the report. The outbox answers "what still has to reach the
 * server"; this file answers "what has the designer captured so far", and a fortnight-long workshop
 * in a courtyard needs the second question answered on a laptop that has had no signal since the tab
 * was opened. Android hit exactly this wall and closed it with `WorkshopDraftStore.kt`; this is that
 * design, ported, with the browser's constraints substituted for the phone's.
 *
 * ── WHAT IS STORED ───────────────────────────────────────────────────────────────────────────────
 *
 *   drafts    one record per workshop: the header, and every stage entry keyed by stage and then by
 *             entity. Plain JSON, readable and writable with no connection.
 *   media     one record per attached file: the BLOB ITSELF, plus what it is and which field it
 *             answers. IndexedDB stores a Blob by structured clone, so the bytes survive a closed
 *             tab, a reload and a flat battery — a `blob:` URL or an in-memory array would not.
 *   registry  the field registry from `GET /design-workshops/schema`, keyed by the version string
 *             the endpoint returns, so a browser that has been offline since the tab opened still
 *             has a form to render rather than 22 blank pages.
 *
 * ── MEDIA IS RETAINED UNTIL THE SERVER CONFIRMS THE MEDIA ID ─────────────────────────────────────
 *
 * The local Blob is deleted in exactly one place, {@link confirmLocalMedia}, and only once the
 * server has answered with the media id it stored the bytes under — never merely because a request
 * was sent, and never as part of "the stage synced". This is not caution for its own sake: the
 * Android outbox deleted its staged media the instant an entry synced (`OfflineOutbox.remove`), and
 * that is precisely the bug that made offline reports impossible there — the entry left the queue,
 * the photographs left the device, and the draft that still referenced them had nothing to show. A
 * media id the server never acknowledged is a reference to nothing; the bytes are the only copy of
 * a loom that was photographed once, in a courtyard, on a day nobody is going back to.
 *
 * ── IDEMPOTENCY IS THE BACKEND'S, NOT A SECOND SCHEME ────────────────────────────────────────────
 *
 * Every collection row carries a `_clientKey` and `PUT /stages/{key}` matches on
 * `(entityKey, clientKey)` — `save_stage` looks the key up across soft-deleted rows too, so a
 * replayed save UPDATES rather than duplicating and a re-added row is RESURRECTED with its id
 * intact. `backend/tests/test_stage_sync.py` proves all three. This store therefore invents no
 * de-duplication of its own: it guarantees only that every row it holds has a stable `_clientKey`
 * that never changes for the life of that row, and lets the server do the matching. A singleton
 * needs no key at all — `save_stage` finds the stage's one singleton row by entity.
 *
 * ── A WORKSHOP CREATED OFFLINE, AND HOW THE TWO IDS ARE MAPPED ───────────────────────────────────
 *
 * A draft's primary key is its `localId` ("dwlocal-…"), minted here and NEVER changed, because it is
 * what the URL, the media rows and every in-flight React tree are holding. `remoteId` starts null
 * and is filled in the moment `POST /design-workshops` answers. Both {@link loadDraft} and the sync
 * pass resolve a workshop by EITHER id, so `/design-workshops/dwlocal-…/stages/SKETCH_DEVELOPMENT`
 * keeps working after the create lands and the page can swap the URL over at its leisure.
 *
 * A stage saved against the local id lands against the real one because the sync pass never captures
 * an id: it creates the workshop first, writes `remoteId` back to disk BEFORE anything else moves,
 * and then reads `draft.remoteId` again for each stage PUT. A pass that dies between the create and
 * the stages resumes at the stages — the same resumability rule `lib/offline.ts` documents, for the
 * same reason: repeating a create makes a second workshop.
 *
 * ── SCHEMA VERSION AND THE MIGRATION LADDER ──────────────────────────────────────────────────────
 *
 * Two different versions live here and conflating them is a corruption bug. {@link DB_VERSION} is
 * IndexedDB's own container version and only says which object stores exist.
 * {@link DW_DRAFT_SCHEMA_VERSION} is the version of the DOCUMENT, stamped into every draft, with a
 * rung in {@link migrateDraft} for each step. Unknown-key tolerance handles ADDING a field; it does
 * nothing for a field that changes meaning, changes type or moves — and a store with no version is
 * the one where the release that finally needs such a change has to guess from the presence of a
 * key. Guessing wrong there corrupts every draft in the field at once, which is why the ladder is
 * here on day one with a single no-op rung rather than "when we need it". A document from the FUTURE
 * is left alone and read as best this build can: an older tab must degrade, never declare a
 * colleague's fortnight corrupt.
 *
 * ── CONCURRENCY ──────────────────────────────────────────────────────────────────────────────────
 *
 * Every read-modify-write happens inside ONE IndexedDB readwrite transaction, with the transform run
 * synchronously between the `get` and the `put`. Nothing inside a transaction may await anything
 * that is not an IDB request, or the transaction commits underneath it. This is what makes two
 * stage pages (or a page and the sync pass, or two TABS) unable to lose each other's writes the way
 * a load-edit-save-later sequence would.
 */

import { ApiError } from "@/lib/api";
import {
  adoptStageRegistry,
  createDesignWorkshop,
  entryDataOf,
  fetchStageRegistry,
  isFilled,
  newClientKey,
  patchDesignWorkshop,
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
  type DwValue
} from "@/lib/designWorkshops";
import { uploadMediaBatch } from "@/lib/media";
// The one rule for "did anything reach the server" lives in the outbox and is imported rather than
// restated. Two answers to that question is two different ideas of what "offline" means, and the
// wrong one either strands a queue for ever or replays a rejection until somebody clears storage.
// `isUnreachable`, NOT `isTransient`: the latter answers "is it worth retrying" and says yes to
// every 5xx, which is how a stage the server had permanently refused was reported as a lost signal.
import { isUnreachable } from "@/lib/offline";

/* ────────────────────────────────────────────────────────────────────────────
 * Constants
 * ──────────────────────────────────────────────────────────────────────────── */

const DB_NAME = "design-workshop-drafts";

/** IndexedDB's container version — which object stores exist. NOT the document version. */
const DB_VERSION = 1;

const STORE_DRAFTS = "drafts";
const STORE_MEDIA = "media";
const STORE_REGISTRY = "registry";
const MEDIA_BY_DRAFT = "byDraft";

/** The document version. Bump it and add a rung to {@link migrateDraft} in the same commit. */
export const DW_DRAFT_SCHEMA_VERSION = 2;

/**
 * Prefix of a workshop id that exists only in this browser.
 *
 * Deliberately not a bare UUID: it travels in the URL and is handed to `stageLocalMedia`, and a
 * client-minted id that could be mistaken for a server cuid would eventually be sent somewhere that
 * would 404 on it with no clue why.
 */
export const LOCAL_ID_PREFIX = "dwlocal-";

/**
 * Prefix of a media reference whose bytes are still on this device.
 *
 * A media field stores a media id. Until the upload is acknowledged there IS no server id, so the
 * value is `dwlocal:<local media id>` — a shape no server id can ever collide with, so a reference
 * that somehow escaped to the API would fail loudly rather than resolve to somebody else's photo.
 */
export const LOCAL_MEDIA_PREFIX = "dwlocal:";

/** How many registry versions to keep. See {@link cacheRegistry}. */
const REGISTRY_KEEP = 3;

export function isLocalWorkshopId(id: string): boolean {
  return id.startsWith(LOCAL_ID_PREFIX);
}

export function isLocalMediaRef(value: string): boolean {
  return value.startsWith(LOCAL_MEDIA_PREFIX);
}

/* ────────────────────────────────────────────────────────────────────────────
 * The stored shapes
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The workshop header as this device holds it.
 *
 * The first block is what a designer can edit here and what `PATCH /design-workshops/{id}` accepts.
 * The second is DENORMALISED by `promoted_values()` from stage 1 and is display-only: it is copied
 * down from the server so the list can draw a row offline, and it is never sent back — writing a
 * promoted column by hand is how the JSON and the column come to disagree about the same fact.
 */
export type DwDraftHeader = {
  title: string;
  templateId: string;
  status: string;
  craftName: string | null;
  clusterName: string | null;
  state: string | null;
  district: string | null;
  startDate: string | null;
  endDate: string | null;
  workshopId: string | null;
  notes: string | null;
  /** Display only — promoted from stage 1 by the server. */
  workshopCode: string | null;
  venue: string | null;
  designerName: string | null;
};

/** Why something has not reached the server, and whether waiting will help. */
export type DwDraftFailure = {
  message: string;
  /**
   * True when the server saw the request and refused it (a 4xx). Waiting will not change the
   * answer, so the sync pass stops retrying it and the banner asks the designer for a decision —
   * exactly the triage `lib/offline.ts` performs, and for the same reason: one permanently-rejected
   * stage must not block the twenty behind it forever with nothing on screen saying why.
   */
  permanent: boolean;
  at: number;
  attempts: number;
};

/**
 * One stage of one workshop.
 *
 * SINGLETON DATA IS KEYED BY ENTITY even though `_stages_payload` flattens it to one map per stage.
 * A stage has at most one singleton entity today (`StageSpec.singleton` returns the first one), so
 * the map has at most one member and the flattening is currently lossless — but the registry may
 * declare a second, and the day it does the wire shape silently keeps only the last one while this
 * store keeps both. Keying by entity costs one indirection and means the local copy can never be
 * the thing that lost an answer.
 */
export type DwDraftStage = {
  stageKey: string;
  singletons: Record<string, DwEntryData>;
  collections: Record<string, DwRow[]>;
  /**
   * Entity keys a row has been DELETED from since the last confirmed push.
   *
   * It is what arms `replaceCollections` on the sync PUT, which is the only way a deletion can
   * reach the server (there is no per-row delete endpoint). It is a list rather than a boolean so
   * the banner can name what a sync is about to sweep, and it is cleared only by a push the server
   * accepted — a deletion made offline on Monday must still sweep when the signal returns on Friday.
   */
  removedFrom: string[];
  updatedAt: number;
  /** When this stage was last edited locally. Null means "identical to what the server last sent". */
  dirtyAt: number | null;
  lastPushedAt: number | null;
  /**
   * When this device last saw the SERVER's copy of this stage — or null when it never has.
   *
   * "EMPTY" AND "NOT DOWNLOADED" ARE DIFFERENT FACTS AND THE STORE USED TO HOLD ONLY ONE OF THEM.
   * A `DwDraftStage` with `singletons: {}` said "this stage has no answers in it", and that reading
   * was applied to a stage this browser had simply never read: opening stage 4 of a workshop
   * written up in the office, on a connection that dropped, seeded the form from a local copy that
   * had never existed, banked it, and then let the sync pass PUT `{entityKey: "clusterBackground",
   * data: {}}` over four rich-text narratives. `save_stage` replaces a singleton row's data
   * wholesale and writes no `RecordRevision` for stage entries, so the seven fields were gone in
   * place and unrecoverable.
   *
   * Set ONLY where the server's copy of the stage was actually folded in ({@link adoptServerStage},
   * {@link adoptServerDetail}) or where this device's copy became the server's ({@link
   * markStagePushed}), and stamped at first write for a workshop the server has never heard of —
   * there is nothing on the server for such a stage to erase. It is deliberately NOT set on the
   * branch where a fold is refused because the stage is dirty: that branch has not read anything
   * into the local copy, so the local copy still has no claim to know what is up there.
   */
  serverLoadedAt: number | null;
  /** The last completeness the SERVER computed. Offline, use {@link localStageCompleteness}. */
  completeness: DwStageCompleteness | null;
  failure: DwDraftFailure | null;
};

export type DwDraft = {
  schemaVersion: number;
  /** Primary key. Minted here, never changed — the URL and every media row hold it. */
  localId: string;
  /** The server's id, once it has issued one. Null while the workshop exists only here. */
  remoteId: string | null;
  header: DwDraftHeader;
  /** When the header was last edited locally. Null means "as the server last sent it". */
  headerDirtyAt: number | null;
  stages: Record<string, DwDraftStage>;
  createdAt: number;
  updatedAt: number;
  /** The registry version this draft was last written against — the same digest `DwDetail` carries. */
  registryVersion: string;
  /** So a shared field laptop never shows one designer another's unsent work. */
  ownerUserId: string | null;
  lastSyncedAt: number | null;
  /** A draft-level failure: the workshop itself could not be created. */
  failure: DwDraftFailure | null;
};

/**
 * One captured file, with its bytes.
 *
 * `blob` is null ONLY after {@link confirmLocalMedia} has seen the server acknowledge
 * `remoteMediaId`. The row itself is kept after that so a later reader can still say what the
 * reference used to be called locally.
 */
export type DwDraftMedia = {
  id: string;
  /** The owning draft's `localId`. */
  localId: string;
  blob: Blob | null;
  name: string;
  mimeType: string;
  sizeBytes: number;
  capturedAt: number;
  stageKey: string | null;
  entityKey: string | null;
  fieldKey: string | null;
  /** The `_clientKey` of the collection row this answers, when it is not a singleton field. */
  clientKey: string | null;
  caption: string | null;
  /** The server's media id. Null until the upload is ACKNOWLEDGED, which is when the blob may go. */
  remoteMediaId: string | null;
  confirmedAt: number | null;
  attempts: number;
  lastError: string | null;
};

type RegistryRecord = { version: string; registry: DwRegistry; storedAt: number };

/* ────────────────────────────────────────────────────────────────────────────
 * IndexedDB plumbing
 * ──────────────────────────────────────────────────────────────────────────── */

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
          // Every media question this store asks is "what belongs to THIS draft" — listing every
          // blob in the database to answer it would deserialise a fortnight of photographs to find
          // the three that matter, on the laptop least able to afford it.
          media.createIndex(MEDIA_BY_DRAFT, "localId", { unique: false });
        }
        if (!db.objectStoreNames.contains(STORE_REGISTRY)) {
          db.createObjectStore(STORE_REGISTRY, { keyPath: "version" });
        }
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error ?? new Error("Cannot open the local workshop store"));
    });
    // A failed open must not be cached forever — a private-mode tab that later allows storage should
    // get a working store rather than the first rejection for the rest of the session. Same rule and
    // same reason as `lib/offline.ts`.
    dbPromise.catch(() => {
      dbPromise = null;
    });
  }
  return dbPromise;
}

/** One IDB request as a promise. Safe to await INSIDE a transaction; nothing else is. */
function req<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error("The local workshop store refused a write"));
  });
}

/**
 * Run `body` inside one transaction over `names`.
 *
 * THE RULE THAT MAKES THIS SAFE: `body` may await {@link req} and nothing else. An IDB transaction
 * commits as soon as control returns to the event loop with no request outstanding, and a promise
 * resolved from a request's success handler runs as a microtask before that — so a chain of `req`
 * calls keeps the transaction alive, while a single `await fetch(...)` in the middle of one closes
 * it and every write after that point throws `TransactionInactiveError`. Read-modify-write is done
 * this way rather than as load-then-save-later precisely because the load-then-save window is where
 * one stage page silently overwrites another's answers.
 */
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
    transaction.onabort = () => reject(transaction.error ?? new Error("The local workshop store aborted a write"));
    transaction.onerror = () => reject(transaction.error ?? new Error("The local workshop store failed a write"));
  });
  return result;
}

/* ────────────────────────────────────────────────────────────────────────────
 * The migration ladder
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Bring one stored document up to {@link DW_DRAFT_SCHEMA_VERSION}, one rung at a time.
 *
 * Every rung takes the document from `version` to `version + 1` and does nothing else, so adding
 * the next one never means re-reading the ones below it. A document from the FUTURE falls straight
 * through: the loop only climbs, and an older tab reads what it understands and leaves the rest
 * alone. The alternative — refusing it — tells a designer their fortnight is corrupt because a
 * colleague's laptop updated first, which is a worse answer than a missing column.
 */
function migrateDraft(raw: Record<string, unknown>): DwDraft {
  let document = raw;
  let version = typeof document.schemaVersion === "number" ? document.schemaVersion : 0;
  while (version < DW_DRAFT_SCHEMA_VERSION) {
    switch (version) {
      case 0:
        // 0 means "written before this store stamped a version". Structurally it is v1, so the rung
        // only stamps the number on. The next rung goes here, e.g.
        //   case 1: document = { ...document, foo: renameOf(document) }; break;
        break;
      case 1:
        // v2 adds `serverLoadedAt` to every stage — see {@link DwDraftStage}. It cannot be
        // defaulted by absence, because absence has to MEAN "never downloaded" for the guard to
        // work, and reading every v1 stage that way would strand a fortnight of genuine offline
        // capture the morning after a deploy. So the rung answers the question from what a v1
        // document does record:
        //
        //   • `dirtyAt === null`  — the stage is byte-for-byte what the server last sent, which
        //     only `adoptServerStage`/`adoptServerDetail` can produce. It was downloaded.
        //   • `lastPushedAt !== null` — this device's copy became the server's copy. Reconciled.
        //   • anything with content in it — a real capture. Treated as reconciled so it still
        //     sends; refusing it would lose the very work this store exists to protect.
        //   • an EMPTY stage that has never been pushed and is nonetheless marked dirty — that is
        //     precisely the artefact of the load race this version fixes. It is left null, so the
        //     sync pass will not send it over the server's real answers, and `runSync` clears its
        //     dirty flag so the next read can fold the server's copy back in.
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
      default:
        return document as unknown as DwDraft;
    }
    version += 1;
    document = { ...document, schemaVersion: version };
  }
  return document as unknown as DwDraft;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Subscription — one source of truth for every banner on the page
 * ──────────────────────────────────────────────────────────────────────────── */

const listeners = new Set<() => void>();
let cache: DwDraft[] = [];

/* ────────────────────────────────────────────────────────────────────────────
 * Whose drafts these are
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The signed-in user this store is answering for, and whether anybody has said yet.
 *
 * A FIELD LAPTOP IS SHARED, AND THIS STORE OUTLIVES A SESSION. `DwDraft.ownerUserId` has always
 * been written; nothing ever read it. So designer A captured a workshop in a courtyard, signed out
 * — `AuthProvider.logout` clears the token and deliberately nothing else, because A's fortnight
 * must survive the handover — and designer B signed in on the same browser. `DraftSyncBanner`
 * drains on mount, `pendingWork` and `runSync` walked every draft in the database with no reference
 * to the owner at all, and B's session POSTed A's workshop under B's credentials: filed under B's
 * name, and `load_workshop_or_404` then 404s A out of their own record. Where the draft already had
 * a `remoteId` the same pass PUT into a workshop B cannot see, took the 404 as a permanent refusal
 * and stopped retrying A's stages for good.
 *
 * THREE STATES, NOT TWO, and the third is what stops the fix being worse than the bug. Between the
 * tab opening and `GET /me` answering, nobody has told this module who is here — filtering on an
 * unknown session would hide a designer's own work from their own screen for the length of a round
 * trip, and would let `ensureDraft` mint a second copy of a workshop this browser already holds.
 * So reads filter only once the session is KNOWN, and the sync pass — the one place that can
 * misfile work — refuses to run at all until it is.
 */
let sessionUserId: string | null = null;
let sessionKnown = false;

/**
 * Tell the store who is signed in. Called by `DesignWorkshopDraftBanner`, which is mounted once in
 * the protected layout and is therefore on every screen that can reach a draft.
 *
 * Passing `null` means "signed out, and we know it": every owned draft goes quiet and nothing
 * syncs. It does NOT delete anything — the other designer's fortnight has to survive the handover.
 */
export function setDraftSessionUser(userId: string | null): void {
  if (sessionKnown && sessionUserId === userId) return;
  sessionUserId = userId;
  sessionKnown = true;
  void refreshDrafts();
}

/** True while nobody has told this store who is signed in — see {@link setDraftSessionUser}. */
export function draftSessionUnknown(): boolean {
  return !sessionKnown;
}

/**
 * May this session read and send this draft?
 *
 * A draft whose owner is null predates the stamp (or was cached before `GET /me` answered) and is
 * treated as this session's: refusing those would strand every workshop captured by the build that
 * shipped without an owner, which is a loss, where showing one is at worst a stale row on a laptop
 * only one person uses. Every draft written from now on carries an owner — {@link createLocalDraft},
 * {@link ensureDraft} and {@link adoptServerSummaries} all stamp the session — so the null case
 * closes itself.
 */
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

/** Synchronous snapshot for `useSyncExternalStore`; refreshed by {@link refreshDrafts}. */
export function getDraftsSnapshot(): DwDraft[] {
  return cache;
}

/** Server render has no IndexedDB — one stable empty array, or the store loops forever. */
const SERVER_SNAPSHOT: DwDraft[] = [];
export function getServerDraftsSnapshot(): DwDraft[] {
  return SERVER_SNAPSHOT;
}

export async function refreshDrafts(): Promise<DwDraft[]> {
  try {
    const rows = await transact([STORE_DRAFTS], "readonly", async (stores) =>
      req<Record<string, unknown>[]>(stores[STORE_DRAFTS].getAll() as IDBRequest<Record<string, unknown>[]>)
    );
    // Filtered by owner, not merely sorted: this snapshot feeds every banner, the workshop list and
    // `pendingWork`, so one unfiltered read here is enough to put another designer's unsent work on
    // screen and into the next sync pass. See {@link draftBelongsToSession}.
    cache = rows.map(migrateDraft).filter(draftBelongsToSession).sort((a, b) => b.updatedAt - a.updatedAt);
  } catch {
    // A store that cannot be read is reported as empty rather than throwing into whichever render
    // asked. The banner then says nothing, which is the honest answer: this browser has no drafts it
    // can see. Nothing is deleted by this path.
    cache = [];
  }
  publish();
  return cache;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Reads
 * ──────────────────────────────────────────────────────────────────────────── */

function matchesId(draft: DwDraft, id: string): boolean {
  return draft.localId === id || draft.remoteId === id;
}

/**
 * One draft by EITHER its local id or its server id — see the id-mapping note in the header.
 *
 * Owner-filtered like {@link refreshDrafts}: a designer who signs in on a colleague's laptop and
 * opens `/design-workshops/dwlocal-…` must not be handed the colleague's unsent workshop to edit
 * and save under their own name. The page's answer for a draft it cannot see is already written —
 * "this workshop was created on another device or in another browser".
 */
export async function loadDraft(id: string): Promise<DwDraft | null> {
  const rows = await transact([STORE_DRAFTS], "readonly", async (stores) =>
    req<Record<string, unknown>[]>(stores[STORE_DRAFTS].getAll() as IDBRequest<Record<string, unknown>[]>)
  ).catch(() => [] as Record<string, unknown>[]);
  return rows.map(migrateDraft).find((draft) => matchesId(draft, id) && draftBelongsToSession(draft)) ?? null;
}

/** Every draft this browser holds, most recently edited first. */
export async function listDrafts(): Promise<DwDraft[]> {
  return refreshDrafts();
}

/* ────────────────────────────────────────────────────────────────────────────
 * Writes
 * ──────────────────────────────────────────────────────────────────────────── */

function localId(): string {
  return `${LOCAL_ID_PREFIX}${newClientKey()}`;
}

/**
 * The keys of a partial header that were actually answered.
 *
 * A key carrying `undefined` means "the caller had nothing to say about this", which is not the
 * same as "set this to nothing" — and spreading it over a default would silently unset the field.
 * This is the shape of the bug that dropped `workshopId` from every design workshop created
 * without a connection: the create form built one header object, the offline path re-enumerated
 * nine of its ten fields by hand into `createLocalDraft`, and the tenth — the link to the workshop
 * record the designer had just chosen from the picker — was never copied. Nothing typechecked it,
 * because a variable is not subject to excess-property checking, and nothing on screen said so,
 * because every other field survived. Callers now hand the whole header over and this prunes it.
 */
function definedOnly(header: Partial<DwDraftHeader>): Partial<DwDraftHeader> {
  return Object.fromEntries(Object.entries(header).filter(([, value]) => value !== undefined));
}

function emptyHeader(title: string, templateId: string): DwDraftHeader {
  return {
    title,
    templateId,
    status: "DRAFT",
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
    updatedAt: 0,
    dirtyAt: null,
    lastPushedAt: null,
    serverLoadedAt: null,
    completeness: null,
    failure: null
  };
}

/**
 * Does this stage hold ANYTHING — one answered field, one row, or one pending deletion?
 *
 * The test is `isFilled`, which is character-for-character the server's `_is_filled`, so "holds
 * something" here means exactly what it means to `stage_completeness`: a box with an empty string
 * in it, or a rich-text document with no text, is not an answer. Used to tell a stage a designer
 * has genuinely worked on apart from one that exists only because a form was rendered — the two
 * were indistinguishable before {@link DwDraftStage.serverLoadedAt}, and treating the second as the
 * first is what let a blank local copy be pushed over a stage somebody had written up in the
 * office.
 */
export function stageHoldsSomething(stage: DwDraftStage | undefined): boolean {
  if (!stage) return false;
  if ((stage.removedFrom ?? []).length > 0) return true;
  for (const data of Object.values(stage.singletons ?? {})) {
    for (const value of Object.values(data)) if (isFilled(value)) return true;
  }
  for (const rows of Object.values(stage.collections ?? {})) if (rows.length) return true;
  return false;
}

/**
 * Read-modify-write one draft inside a single transaction.
 *
 * `transform` runs synchronously against whatever is on disk RIGHT NOW and its result is written
 * back before the transaction commits. The unsafe shape — load, edit in a handler, save later — has
 * a window in which another stage page's save lands and is then overwritten; the window is small and
 * the loss is total and silent, which is the worst possible combination.
 *
 * `options.prepare` EXISTS SO A TRANSFORM CAN DEPEND ON ANOTHER STORE WITHOUT LEAVING THE
 * TRANSACTION. A value read before the transaction opens is a value that may already be stale by
 * the time the put lands — {@link putDraftStage} is the case that proves it, where a media row read
 * a moment early made the write reinstate a `dwlocal:` reference the server had just confirmed.
 * Naming the extra stores in `options.stores` puts them in the SAME transaction, so a writer over
 * the same scope is serialised either wholly before this read or wholly after this write, and
 * neither order can strand the draft. `prepare` obeys the rule {@link transact} states: it may await
 * {@link req} and nothing else.
 */
async function mutate<P = undefined>(
  id: string,
  transform: (draft: DwDraft, prepared: P) => DwDraft,
  options?: {
    /** Object stores to open ALONGSIDE `drafts` for `prepare` to read. */
    stores?: string[];
    /** Runs inside the transaction, after the draft is found and before `transform`. */
    prepare?: (stores: Record<string, IDBObjectStore>, draft: DwDraft) => Promise<P>;
  }
): Promise<DwDraft | null> {
  const next = await transact([STORE_DRAFTS, ...(options?.stores ?? [])], "readwrite", async (stores) => {
    const store = stores[STORE_DRAFTS];
    const rows = await req<Record<string, unknown>[]>(store.getAll() as IDBRequest<Record<string, unknown>[]>);
    // Owner-filtered exactly like the reads. Two designers on one laptop can legitimately hold two
    // local copies of the SAME server workshop, and an unfiltered match by `remoteId` would let this
    // session's fold land in the other one's copy — the object store is keyed by `localId`, so
    // "whichever came first in key order" is who would have been written into.
    const current = rows.map(migrateDraft).find((draft) => matchesId(draft, id) && draftBelongsToSession(draft));
    if (!current) return null;
    // After the owner check, so a draft belonging to the other session on this laptop is never even
    // read from the extra stores.
    const prepared = (options?.prepare ? await options.prepare(stores, current) : undefined) as P;
    const updated: DwDraft = {
      ...transform(current, prepared),
      schemaVersion: DW_DRAFT_SCHEMA_VERSION,
      // The identity of a draft is not the transform's to change: rewriting `localId` would orphan
      // every media row that points at it and every open tab holding the id in its URL.
      localId: current.localId,
      createdAt: current.createdAt,
      updatedAt: Date.now()
    };
    await req(store.put(updated));
    return updated;
  }).catch(() => null);
  if (next) await refreshDrafts();
  return next;
}

/**
 * Start a workshop that exists only on this device.
 *
 * The record is complete and openable the moment this returns: 22 stages of nothing is a legitimate
 * workshop on day one, and the whole point is that the designer can start filling it in before the
 * server has ever heard of it.
 */
export async function createLocalDraft(
  header: Partial<DwDraftHeader> & { title: string },
  options?: { ownerUserId?: string | null; registryVersion?: string }
): Promise<DwDraft> {
  const now = Date.now();
  const draft: DwDraft = {
    schemaVersion: DW_DRAFT_SCHEMA_VERSION,
    localId: localId(),
    remoteId: null,
    // `definedOnly` is what makes a caller able to hand its whole header object over. A key present
    // with `undefined` — which is what every optional box on the create form produces when it was
    // left blank — would otherwise overwrite `emptyHeader`'s null with undefined and take the field
    // out of the JSON entirely.
    header: { ...emptyHeader(header.title, header.templateId ?? "DCH_STANDARD"), ...definedOnly(header) },
    headerDirtyAt: now,
    stages: {},
    createdAt: now,
    updatedAt: now,
    registryVersion: options?.registryVersion ?? "",
    // Stamped from the session unless the caller names an owner. An unowned draft is what let one
    // designer's workshop be drained under the next designer to sign in on the same laptop.
    ownerUserId: options?.ownerUserId ?? sessionUserId,
    lastSyncedAt: null,
    failure: null
  };
  await transact([STORE_DRAFTS], "readwrite", async (stores) => req(stores[STORE_DRAFTS].put(draft)));
  await refreshDrafts();
  return draft;
}

/**
 * Make sure a draft exists for a workshop the server already knows about, without touching anything
 * that is already here.
 *
 * Called on the way into every design-workshop page, so that a stage opened once online can be
 * opened again with no connection at all.
 */
export async function ensureDraft(
  remoteId: string,
  seed?: { title?: string; templateId?: string; ownerUserId?: string | null }
): Promise<DwDraft> {
  const existing = await loadDraft(remoteId);
  if (existing) return existing;
  const now = Date.now();
  const draft: DwDraft = {
    schemaVersion: DW_DRAFT_SCHEMA_VERSION,
    localId: localId(),
    remoteId,
    header: emptyHeader(seed?.title ?? "", seed?.templateId ?? "DCH_STANDARD"),
    // Not dirty: nothing here was typed by anybody. Marking a freshly-seeded header dirty would push
    // a blank title over the server's real one on the next sync.
    headerDirtyAt: null,
    stages: {},
    createdAt: now,
    updatedAt: now,
    registryVersion: "",
    // The local copy belongs to whoever made it, which for a workshop the server already owns is
    // the session that opened it — not the workshop's creator. Two designers sharing a laptop get
    // one local copy each, and neither drains the other's.
    ownerUserId: seed?.ownerUserId ?? sessionUserId,
    lastSyncedAt: null,
    failure: null
  };
  await transact([STORE_DRAFTS], "readwrite", async (stores) => req(stores[STORE_DRAFTS].put(draft)));
  await refreshDrafts();
  return draft;
}

/** Edit the header locally. Marks it unsent; the sync pass turns it into a create or a PATCH. */
export async function patchDraftHeader(id: string, patch: Partial<DwDraftHeader>): Promise<DwDraft | null> {
  return mutate(id, (draft) => ({
    ...draft,
    header: { ...draft.header, ...patch },
    headerDirtyAt: Date.now()
  }));
}

/**
 * Write one stage, leaving the other twenty-one exactly as they are.
 *
 * Because `stages` is keyed by stage key the merge is an idempotent map put, so a stage page that
 * autosaves while the sync pass is writing another stage's push result cannot overwrite it. The
 * caller passes the whole stage's data because that is what its form holds; within the stage,
 * singletons and collections are still keyed by entity.
 */
export async function putDraftStage(
  id: string,
  stageKey: string,
  data: { singletons: Record<string, DwEntryData>; collections: Record<string, DwRow[]>; removedFrom?: string[] }
): Promise<DwDraft | null> {
  // A REFERENCE THAT HAS SINCE BEEN CONFIRMED IS SUBSTITUTED ON THE WAY IN, and this is not
  // belt-and-braces. A stage form holds its values in React state; the sync pass can upload one of
  // its photographs and rewrite the draft while the page is still open, and the very next keystroke
  // would then autosave the OLD `dwlocal:` reference back over the real media id. The tile would go
  // blank (the blob is gone, correctly, because the server confirmed it) and the stage would be held
  // back for ever on a reference nothing can resolve. Resolving at the write, inside the same
  // transaction that reads the media rows, is the only place that cannot race the page — so the
  // media read is `prepare`d over [drafts, media] rather than awaited out here. It used to be
  // awaited out here, in two earlier read-only transactions, and `confirmLocalMedia` opening its own
  // [drafts, media] readwrite between them and this put was enough to strand the stage.
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
            // Unioned, never replaced. A deletion made in an earlier session is still a deletion that
            // has to reach the server, and the next autosave from a form that has forgotten about it
            // would otherwise disarm the sweep and leave the row alive for ever.
            removedFrom: Array.from(new Set([...previous.removedFrom, ...(data.removedFrom ?? [])])),
            updatedAt: now,
            dirtyAt: now,
            // A stage first written while the workshop has NO server record is reconciled by
            // definition: there is nothing up there for it to overwrite, and it is created together
            // with the workshop on the next connection. Anything else keeps whatever the fold or the
            // last push recorded — a local write must never be able to claim this device has seen the
            // server's copy of a stage it has not read. See {@link DwDraftStage.serverLoadedAt}.
            serverLoadedAt: previous.serverLoadedAt ?? (draft.remoteId === null ? now : null),
            // A fresh edit clears the last refusal: the designer may have just fixed exactly what the
            // server complained about, and a stale red message on a corrected stage is worse than none.
            failure: null
          }
        }
      };
    },
    { stores: [STORE_MEDIA], prepare: (stores, draft) => confirmedMediaMap(stores[STORE_MEDIA], draft.localId) }
  );
}

/** What a push was built from — the state the server's acceptance is an acceptance OF. */
export type DwPushedSnapshot = {
  /** The stage's `dirtyAt` when the payload was built. */
  dirtyAt: number | null;
  /** The `emptiedEntities` the payload actually carried. */
  removedFrom: readonly string[];
};

/**
 * The unsent work a stage still holds once the server has ACCEPTED a push.
 *
 * AN ACKNOWLEDGEMENT CLEARS ONLY THE STAGE IT ACTUALLY DESCRIBED, and both fields are answered by
 * the one question rather than separately — keeping them apart is what let them drift. `dirtyAt` had
 * the comparison and `removedFrom` was emptied unconditionally, so a row deleted while the banner's
 * pass had that same stage in flight lost its deletion flag to a push that never carried it: the
 * next PUT then sent `replaceCollections: false` and `emptiedEntities: []`, the sweep never reached
 * the server, the row stayed alive in the repository, and the next clean read put it back on the
 * designer's screen and into the officer's .docx. `removedFrom` is documented at
 * {@link DwDraftStage.removedFrom} as "cleared only by a push the server accepted", and a push that
 * did not carry the deletion did not accept it.
 *
 * WHY NOT SUBTRACT THE ACKNOWLEDGED KEYS INSTEAD. `removedFrom` holds ENTITY keys, not row keys, so
 * it cannot tell one deletion from a second deletion out of the same collection. Deleting a second
 * participant while a push that already named `participant` is in flight leaves the list reading
 * exactly as it did before, and subtracting would drop it — the identical resurrection, one row
 * further along. The stage is dirty either way, so the only question that decides the sweep is
 * whether this device has touched the stage since the payload was built; when it has, the flag is
 * kept and the next pass re-arms `replaceCollections`. That costs a redundant sweep in the case
 * where the later edit was mere typing, and a redundant sweep sends the stage's current rows in the
 * same payload — the failure it protects against is a row the designer watched disappear coming
 * back with nothing on screen admitting it.
 */
export function unsentAfterPush(
  stage: DwDraftStage,
  sent: DwPushedSnapshot
): { dirtyAt: number | null; removedFrom: string[] } {
  // A designer typing — or deleting — while the request is in flight leaves a NEWER `dirtyAt`
  // behind. `removedFrom` only ever GROWS through `putDraftStage`, which stamps `dirtyAt` in the
  // same write, so this one comparison answers for both fields.
  const superseded = stage.dirtyAt !== null && stage.dirtyAt > (sent.dirtyAt ?? 0);
  return {
    dirtyAt: superseded ? stage.dirtyAt : null,
    removedFrom: superseded ? stage.removedFrom : []
  };
}

/**
 * Record that a stage reached the server.
 *
 * `sinceDirtyAt` is the `dirtyAt` the pushed payload was built from and `sinceRemovedFrom` is the
 * `emptiedEntities` it carried; comparing against both is load-bearing. A designer typing while the
 * request is in flight leaves a NEWER `dirtyAt` behind, and one deleting a row leaves a key in
 * `removedFrom` that this payload never named — clearing either unconditionally marks work as sent
 * that was never in the payload, after which it is never sent at all and nothing on screen says a
 * word about it. See {@link unsentAfterPush}.
 */
export async function markStagePushed(
  id: string,
  stageKey: string,
  options: {
    completeness?: DwStageCompleteness | null;
    sinceDirtyAt: number | null;
    sinceRemovedFrom: readonly string[];
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
          lastPushedAt: now,
          // The server has just taken this device's copy, so the two agree — from here on this
          // stage may be re-sent without any risk of overwriting an answer this browser never read.
          serverLoadedAt: stage.serverLoadedAt ?? now,
          completeness: options.completeness ?? stage.completeness,
          failure: null
        }
      },
      lastSyncedAt: now
    };
  });
}

/**
 * Forget one draft entirely, and its media with it.
 *
 * The ONLY destructive call in this file, and it is deliberately not reachable from a sync, a parse
 * failure or a cleanup pass — only from a designer who explicitly discarded a workshop that has
 * nothing left to send, or from one who accepted the loss after a permanent refusal. `lib/offline.ts`
 * holds the same line: nothing here deletes an entry on its own.
 */
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

/* ────────────────────────────────────────────────────────────────────────────
 * Media — staged locally, retained until the server confirms the id
 * ──────────────────────────────────────────────────────────────────────────── */

let persistenceAsked = false;

/**
 * Ask the browser to stop treating this origin's storage as disposable.
 *
 * THIS IS THE BROWSER'S VERSION OF THE `cacheDir` BUG `WorkshopDraftStore.kt` WAS WRITTEN AROUND. On
 * Android the camera wrote captures into `cacheDir`, which the OS reclaims under storage pressure —
 * silently, with no callback — so a loom photographed on day one was an empty frame in the report on
 * day twelve and no log line anywhere said why. A browser origin that has not been granted
 * persistence is in exactly that category: its IndexedDB is "best effort" and may be evicted when
 * the disk fills, which on a field laptop two weeks into a study is when it fills. `persist()` moves
 * the origin to "persistent", which the spec says must not be cleared without the user asking.
 *
 * Asked at the first ATTACHED FILE rather than at page load, on purpose. Some browsers show a prompt,
 * and a prompt that appears the instant somebody opens a list is noise they dismiss; a prompt that
 * appears the moment they photograph something is a question they can answer. Chrome grants it
 * silently on an installed or frequently-visited origin and refuses silently otherwise — and a
 * refusal is not an error here, so nothing is surfaced: the bytes are still written either way, and
 * telling a designer their browser might one day evict their photographs is a warning they can do
 * nothing about. Asked once per tab; the answer is per-origin and does not change on a retry.
 */
async function requestPersistence(): Promise<void> {
  if (persistenceAsked) return;
  persistenceAsked = true;
  try {
    if (typeof navigator === "undefined" || !navigator.storage?.persist) return;
    if (await navigator.storage.persisted()) return;
    await navigator.storage.persist();
  } catch {
    // A browser that refuses to answer the question is answering it.
  }
}

/**
 * Take a file the designer just attached and put its BYTES on this device.
 *
 * Returns the reference to store in the field: `dwlocal:<id>`, which every reader in this feature
 * knows how to resolve and no server ever will. The blob is written before the reference is handed
 * back, so a draft can never point at bytes that were not stored.
 */
export async function stageLocalMedia(
  workshopId: string,
  file: File,
  context?: { stageKey?: string | null; entityKey?: string | null; fieldKey?: string | null; clientKey?: string | null; caption?: string | null }
): Promise<{ ref: string; media: DwDraftMedia }> {
  void requestPersistence();
  // A draft always exists by the time a field can be typed into, but a page reached by a deep link
  // in a tab that has never been online is the exception — seeding one here beats refusing the
  // photograph, which is the part of a workshop that cannot be recreated.
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
    lastError: null
  };
  await transact([STORE_MEDIA], "readwrite", async (stores) => req(stores[STORE_MEDIA].put(media)));
  return { ref: `${LOCAL_MEDIA_PREFIX}${media.id}`, media };
}

/**
 * `dwlocal:<id>` -> the server media id, for every file of this draft the server has acknowledged.
 *
 * Empty for a draft that has never uploaded anything, which is the common case, so the substitution
 * above costs one indexed read and then nothing.
 *
 * TAKES AN OPEN STORE RATHER THAN OPENING ONE. Its caller must see these rows and write the draft
 * atomically, and a helper that opened its own transaction is exactly what made that impossible —
 * see {@link putDraftStage}.
 */
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

/** One staged file, bytes and all. `ref` may be the bare id or the `dwlocal:` reference. */
export async function readLocalMedia(ref: string): Promise<DwDraftMedia | null> {
  const id = ref.startsWith(LOCAL_MEDIA_PREFIX) ? ref.slice(LOCAL_MEDIA_PREFIX.length) : ref;
  return transact([STORE_MEDIA], "readonly", async (stores) =>
    req<DwDraftMedia | undefined>(stores[STORE_MEDIA].get(id) as IDBRequest<DwDraftMedia | undefined>)
  )
    .then((row) => row ?? null)
    .catch(() => null);
}

/** Every staged file belonging to one draft. */
export async function draftMedia(localDraftId: string): Promise<DwDraftMedia[]> {
  return transact([STORE_MEDIA], "readonly", async (stores) =>
    req<DwDraftMedia[]>(stores[STORE_MEDIA].index(MEDIA_BY_DRAFT).getAll(localDraftId) as IDBRequest<DwDraftMedia[]>)
  ).catch(() => []);
}

/**
 * THE SERVER HAS ACKNOWLEDGED THE MEDIA ID. Rewrite every reference to it, and only then let the
 * bytes go.
 *
 * The order inside this transaction is the whole point and cannot be swapped:
 *
 *   1. every `dwlocal:<id>` in the draft becomes the server's media id, so nothing is left pointing
 *      at a local reference that is about to stop resolving;
 *   2. `remoteMediaId` and `confirmedAt` are recorded, so a crash after this cannot re-upload the
 *      same photograph and leave the workshop with two copies of one loom;
 *   3. `blob` is cleared LAST.
 *
 * THE ANDROID OUTBOX DID NOT DO THIS. `OfflineOutbox.remove` deleted the staged media the instant
 * an entry synced — not when the media were acknowledged, but when the REQUEST left — and that is
 * exactly the bug that made offline reports impossible on the phone: the photographs were gone, the
 * draft still referenced them, and the report rendered empty frames a fortnight later with nothing
 * on screen admitting why. Deleting the local copy on anything short of a confirmed id is the same
 * bug with a different spelling.
 */
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
        // Last, and only here.
        blob: null
      })
    );
  });
  await refreshDrafts();
}

/**
 * Drop a staged file the designer removed before it was ever uploaded.
 *
 * Safe to delete the bytes here — and only here — because this is an explicit removal of one
 * attachment, not a sync, not a cleanup and not a parse failure.
 */
export async function removeLocalMedia(localMediaId: string): Promise<void> {
  await transact([STORE_MEDIA], "readwrite", async (stores) => {
    const media = await req<DwDraftMedia | undefined>(
      stores[STORE_MEDIA].get(localMediaId) as IDBRequest<DwDraftMedia | undefined>
    );
    // A confirmed row keeps its (already blob-less) record: it is the only thing that can later
    // explain what a server media id used to be called on this laptop.
    if (media && !media.confirmedAt) await req(stores[STORE_MEDIA].delete(localMediaId));
  });
}

/** Rewrite one media reference everywhere it appears in a draft's stage values. */
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

/** Every `dwlocal:` reference a stage still holds — the ones that must not be sent to the server. */
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

/* ────────────────────────────────────────────────────────────────────────────
 * The registry cache
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Keep the registry this build just fetched, keyed by the version string the schema endpoint
 * returned.
 *
 * KEYED BY VERSION rather than stored as a single "latest" row, because the version IS the identity:
 * `registry_version` is a content digest of every key, type and tier, and a workshop's stored
 * `schemaVersion` names the one it was written against. Keeping the last {@link REGISTRY_KEEP} means
 * a tab that comes back after a deploy — or after a rollback — can still render the form a stage was
 * captured with, instead of showing a designer a field list that never applied to their record.
 */
export async function cacheRegistry(registry: DwRegistry): Promise<void> {
  await transact([STORE_REGISTRY], "readwrite", async (stores) => {
    const store = stores[STORE_REGISTRY];
    // A registry this browser already holds is not rewritten. `version` is a content digest, so an
    // equal version IS the same document — and re-serialising 496 field descriptors into IndexedDB
    // on every navigation between two stages is hundreds of kilobytes of structured clone per click
    // on the cheapest laptop in the room, for a file that has not changed.
    const held = await req<RegistryRecord | undefined>(
      store.get(registry.version) as IDBRequest<RegistryRecord | undefined>
    );
    if (held) return;
    await req(store.put({ version: registry.version, registry, storedAt: Date.now() } satisfies RegistryRecord));
    const all = await req<RegistryRecord[]>(store.getAll() as IDBRequest<RegistryRecord[]>);
    const stale = all.sort((a, b) => b.storedAt - a.storedAt).slice(REGISTRY_KEEP);
    for (const row of stale) await req(store.delete(row.version));
  }).catch(() => {
    // A registry that could not be cached costs an offline form, not a save. Never fatal.
  });
}

/** The cached registry for one version, or the most recently seen one when `version` is omitted. */
export async function cachedRegistry(version?: string): Promise<DwRegistry | null> {
  const rows = await transact([STORE_REGISTRY], "readonly", async (stores) =>
    req<RegistryRecord[]>(stores[STORE_REGISTRY].getAll() as IDBRequest<RegistryRecord[]>)
  ).catch(() => [] as RegistryRecord[]);
  if (!rows.length) return null;
  if (version) return rows.find((row) => row.version === version)?.registry ?? null;
  return rows.sort((a, b) => b.storedAt - a.storedAt)[0].registry;
}

export type RegistrySource = "network" | "memory" | "cache";

/**
 * The registry, offline first.
 *
 * Ask the network, and cache what comes back. If the network cannot answer — the tab has been in a
 * courtyard since it opened — fall back to the last registry this browser saw and say so, because a
 * form rendered from a registry that may be a week old is worth having and a designer is entitled to
 * know which one they are looking at. When neither is available the caller gets the real error: 22
 * blank pages with no explanation is the one outcome worse than an error message.
 *
 * The fetched registry is also handed to `lib/designWorkshops`' in-memory cache through
 * {@link adoptStageRegistry}, so `peekStageRegistry()` and the identity contract that stops every
 * `useMemo` in the feature rebuilding still hold when the answer came off disk.
 */
export async function loadRegistry(): Promise<{ registry: DwRegistry; source: RegistrySource; storedAt?: number }> {
  try {
    const registry = await fetchStageRegistry();
    void cacheRegistry(registry);
    return { registry, source: "network" };
  } catch (error) {
    const fallback = await cachedRegistry();
    if (!fallback) throw error;
    // Adopted so that every other consumer in the feature — the report page, the list — sees the
    // same object and does not re-issue the request that just failed.
    const adopted = adoptStageRegistry(fallback);
    return { registry: adopted, source: "cache" };
  }
}

/* ────────────────────────────────────────────────────────────────────────────
 * Reading a stage back out, and scoring it
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A stage in the shape the form wants: the singleton entities flattened into one map, exactly as
 * `_stages_payload` serialises them, and the collections as they are.
 *
 * The flattening is done HERE rather than in storage so the local copy keeps the per-entity keying
 * — see the note on {@link DwDraftStage}.
 */
export function stageDataOf(stage: DwDraftStage | undefined): DwStageData {
  if (!stage) return { singleton: {}, collections: {} };
  const singleton: DwEntryData = {};
  for (const data of Object.values(stage.singletons)) Object.assign(singleton, data);
  return { singleton, collections: stage.collections, completeness: stage.completeness };
}

/** Split the wire's flat singleton map back out per entity, using the stage's own declaration. */
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

/**
 * Score one stage from what is on this device, with no server and no network.
 *
 * A MIRROR OF `stage_completeness`, deliberately, down to the rules that look odd: the singleton's
 * required fields count once each; a COLLECTION contributes its required fields once PER EXISTING
 * ROW and contributes nothing when it is empty (an empty sketch list is a legitimate state on day
 * one, not an error); `missing` is de-duplicated with order preserved; and a stage with nothing
 * required reads 100, not 0, because dividing by zero to decide whether a designer may submit is
 * how a stage becomes permanently unsubmittable.
 *
 * It exists because the screen that shows it is the screen a designer looks at in a courtyard to
 * decide whether they can pack up. A progress figure that needs a server to be honest is a progress
 * figure that lies exactly when it matters. `isFilled` is already character-for-character the
 * server's `_is_filled`, so the two ends cannot disagree about whether a box has an answer in it.
 */
export function localStageCompleteness(spec: DwStage, stage: DwDraftStage | undefined): DwStageCompleteness {
  const data = stageDataOf(stage);
  return scoreStageData(spec, data.singleton, data.collections);
}

/**
 * The same score, taken from data that is still being TYPED rather than from a banked draft.
 *
 * WHY THIS SIGNATURE EXISTS SEPARATELY. {@link localStageCompleteness} reads a `DwDraftStage`, which
 * is the copy in IndexedDB — and that copy is written by a debounce, so it trails the boxes on
 * screen by however long the debounce is and does not exist at all for a stage nobody has saved yet.
 * A form that scores the banked copy therefore shows a progress bar that either lags the typing or,
 * on a brand-new stage, cannot be drawn at all: the designer fills in eight required fields and the
 * bar stays on whatever the last save said, which reads as a broken control and trains people to
 * ignore the one number that tells them whether they can pack up.
 *
 * Taking `(spec, singleton, collections)` lets a screen score exactly what it is rendering. It is
 * deliberately the same three arguments, in the same order, as Android's `computeStageCompleteness`
 * in `data/StageSchema.kt` — that screen has always scored its live state, and a web form that
 * cannot do the same is the two clients disagreeing about the one number they both put in front of
 * the designer.
 *
 * All the scoring rules live HERE and nowhere else, so the live path and the banked path cannot
 * drift into two different answers to "is this stage finished".
 */
export function scoreStageData(
  spec: DwStage,
  singleton: DwEntryData,
  collections: Record<string, DwRow[]>
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
    if (entity.cardinality === "SINGLETON") {
      score(entity, data.singleton, "");
      continue;
    }
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
    // De-duplicated with order preserved, the same way `dict.fromkeys` does it server-side.
    missing: [...new Set(missing)]
  };
}

/** Every stage of a draft scored locally, keyed the way `DwDetail.completeness` is. */
export function localCompleteness(registry: DwRegistry, draft: DwDraft): Record<string, DwStageCompleteness> {
  const out: Record<string, DwStageCompleteness> = {};
  for (const spec of registry.stages) out[spec.key] = localStageCompleteness(spec, draft.stages[spec.key]);
  return out;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Reconciling with the server
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Fold a server read into the local draft.
 *
 * THE RULE IS "LOCAL EDITS WIN, EVERYTHING ELSE IS REFRESHED", and it is applied per stage rather
 * than per workshop. A stage the designer has not touched since the last sync is replaced with the
 * server's copy — which may hold a second editor's rows, and refusing them would make this browser
 * quietly the only place with the truth. A stage with `dirtyAt` set is left exactly as it is: the
 * designer typed it, it has not been sent, and letting a background read overwrite unsent fieldwork
 * with an older server copy is the single worst thing an offline-first store can do.
 *
 * The completeness the server sent is stored either way, because it describes the server's copy and
 * is what a "this is what has actually landed" reading needs; the screens use
 * {@link localStageCompleteness} for what is on the device.
 */
export async function adoptServerDetail(detail: DwDetail, spec: DwRegistry): Promise<DwDraft | null> {
  await ensureDraft(detail.id, { title: detail.title, templateId: detail.templateId });
  return mutate(detail.id, (draft) => {
    const stages: Record<string, DwDraftStage> = { ...draft.stages };
    for (const stageSpec of spec.stages) {
      const incoming = detail.stages?.[stageSpec.key];
      const current = stages[stageSpec.key] ?? emptyStage(stageSpec.key);
      const serverScore = detail.completeness?.[stageSpec.key] ?? null;
      if (current.dirtyAt !== null) {
        // NO `serverLoadedAt` HERE. The fold was refused, so nothing of the server's copy has been
        // read into this stage and the local copy still has no claim to know what is up there.
        stages[stageSpec.key] = { ...current, completeness: serverScore ?? current.completeness };
        continue;
      }
      if (!incoming) {
        // The server answered for the whole workshop and said nothing about this stage, which is
        // how `_stages_payload` reports a stage with no entries at all. That IS the server's copy,
        // so this device has now seen it and holds the same emptiness.
        //
        // THE ROWS ARE CLEARED, and for a while this branch only said they were. `...current` keeps
        // whatever the local copy already held, so a stage emptied on the server — the last row
        // deleted here, or by a co-designer, or by the office — kept its deleted rows on this device
        // AND was stamped `serverLoadedAt`, which is this store's word for "the server's copy has
        // been read into this stage". A row the ministry no longer has then reads as downloaded and
        // current, and the next push can put it back. Nothing unsynced is lost by clearing: the
        // `dirtyAt` branch above has already refused the fold for every stage this device has edited,
        // so anything reaching here came down from the server in the first place.
        stages[stageSpec.key] = {
          ...current,
          singletons: {},
          collections: {},
          removedFrom: [],
          updatedAt: Date.now(),
          serverLoadedAt: Date.now(),
          completeness: serverScore ?? current.completeness
        };
        continue;
      }
      stages[stageSpec.key] = {
        ...current,
        singletons: splitSingletons(stageSpec, incoming.singleton ?? {}),
        collections: withClientKeys(incoming.collections ?? {}),
        removedFrom: [],
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
      stages,
      registryVersion: detail.schemaVersion || draft.registryVersion,
      lastSyncedAt: Date.now()
    };
  });
}

/** The same fold for one stage, for the stage page's own read. */
export async function adoptServerStage(
  workshopId: string,
  spec: DwStage,
  incoming: DwStageData
): Promise<DwDraft | null> {
  return mutate(workshopId, (draft) => {
    const current = draft.stages[spec.key] ?? emptyStage(spec.key);
    if (current.dirtyAt !== null) {
      // Deliberately NOT marked as loaded — see the twin branch in {@link adoptServerDetail}.
      return {
        ...draft,
        stages: { ...draft.stages, [spec.key]: { ...current, completeness: incoming.completeness ?? current.completeness } }
      };
    }
    return {
      ...draft,
      stages: {
        ...draft.stages,
        [spec.key]: {
          ...current,
          singletons: splitSingletons(spec, incoming.singleton ?? {}),
          collections: withClientKeys(incoming.collections ?? {}),
          removedFrom: [],
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

/**
 * Every row that arrived without a client key is given one, once, on the way in.
 *
 * The key is what lets a replayed save match an existing row instead of creating a second copy, and
 * rows written by an older build (or by the web form before this store existed) have none. Minting
 * it here rather than at save time means the key is stable across a reload — a key regenerated on
 * every load would defeat the idempotency it exists to provide.
 */
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
    designerName: summary.designerName
  };
}

/**
 * Keep the list's rows locally too, so the workshop list draws with no connection.
 *
 * ONE TRANSACTION FOR THE WHOLE PAGE, not one per row. The obvious spelling — `ensureDraft` then
 * `mutate` in a loop — is forty transactions and forty `refreshDrafts` publishes for a twenty-row
 * page, which is forty re-renders of the banner and the table while a designer is trying to read
 * them. It also has forty windows in which a concurrent autosave can interleave.
 *
 * A row whose local copy holds UNSENT header edits keeps its own header: the local copy is then the
 * newer of the two, and overwriting it with the list's stale title is the correction disappearing.
 */
export async function adoptServerSummaries(rows: DwSummary[]): Promise<void> {
  if (!rows.length) return;
  await transact([STORE_DRAFTS], "readwrite", async (stores) => {
    const store = stores[STORE_DRAFTS];
    const existing = (
      await req<Record<string, unknown>[]>(store.getAll() as IDBRequest<Record<string, unknown>[]>)
    ).map(migrateDraft);
    // Only this session's copies are candidates for the refresh. Two designers on one laptop hold
    // one local copy of a shared workshop each, and writing the list's header into the other one's
    // copy would put this session's view of a row into a record it is not allowed to touch.
    const byRemote = new Map(
      existing.filter((draft) => draft.remoteId && draftBelongsToSession(draft)).map((draft) => [draft.remoteId!, draft])
    );
    const now = Date.now();
    for (const row of rows) {
      const current = byRemote.get(row.id);
      if (current) {
        if (current.headerDirtyAt !== null) continue;
        // A copy cached before `GET /me` answered has no owner; the session that is refreshing it
        // is the one it belongs to.
        await req(store.put({ ...current, header: headerOf(row), ownerUserId: current.ownerUserId ?? sessionUserId, updatedAt: now }));
        continue;
      }
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
          // WHOEVER CACHED IT, not whoever created it on the server. This field answers "may this
          // session send this local copy", and a row cached under its remote creator's id would be
          // invisible offline to the designer who is actually sitting here — while still being
          // drainable by nobody at all. `createdById` remains the fallback only for the window
          // before `GET /me` has answered.
          ownerUserId: sessionUserId ?? row.createdById ?? null,
          lastSyncedAt: now,
          failure: null
        } satisfies DwDraft)
      );
    }
  }).catch(() => {
    // A list that could not be cached is a list that will need a connection tomorrow. Never fatal:
    // the rows the designer is looking at are already on screen.
  });
  await refreshDrafts();
}

/**
 * A local draft as a list row, so the workshop list can show a workshop that has never been to the
 * server beside the ones that have.
 */
export function draftSummary(draft: DwDraft): DwSummary {
  return {
    id: draft.remoteId ?? draft.localId,
    title: draft.header.title,
    templateId: draft.header.templateId,
    status: draft.header.status,
    workshopCode: draft.header.workshopCode,
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
    createdById: draft.ownerUserId ?? "",
    createdAt: new Date(draft.createdAt).toISOString(),
    updatedAt: new Date(draft.updatedAt).toISOString(),
    deletedAt: null
  };
}

/* ────────────────────────────────────────────────────────────────────────────
 * What is outstanding
 * ──────────────────────────────────────────────────────────────────────────── */

export type DwDraftPending = {
  draft: DwDraft;
  /** True when the workshop itself has never reached the server. */
  needsCreate: boolean;
  needsHeader: boolean;
  /** Stage keys with unsent edits, oldest edit first. */
  stageKeys: string[];
  /** Local media rows whose id the server has not acknowledged. */
  mediaCount: number;
  /** Everything the server has refused permanently — waiting will not clear these. */
  failures: Array<{ scope: string; message: string }>;
};

export async function pendingWork(): Promise<DwDraftPending[]> {
  const drafts = await refreshDrafts();
  const out: DwDraftPending[] = [];
  for (const draft of drafts) {
    // `refreshDrafts` has already filtered, and this says so a second time on purpose: this list is
    // what the sync pass sends, and the day somebody makes the snapshot unfiltered for a legitimate
    // reason, the failure would be one designer's fortnight filed under another designer's name.
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
    const needsCreate = draft.remoteId === null;
    const needsHeader = draft.headerDirtyAt !== null;
    if (!needsCreate && !needsHeader && !stageKeys.length && !media.length && !failures.length) continue;
    out.push({ draft, needsCreate, needsHeader, stageKeys, mediaCount: media.length, failures });
  }
  return out;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Building a stage's save payload
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The entries to send for one stage, and the map from each entry's POSITION in that array back to
 * the row it came from.
 *
 * The position matters: `save_stage` keys its per-field errors by `entityKey` for a singleton and by
 * `` `${entityKey}[${index}]` `` for a collection row, where `index` is the entry's index in the
 * array THAT WAS SENT — not the row's ordinal, and not its position in the collection. Building both
 * halves in one exported function is what stops the stage form and the sync pass decoding one error
 * map against two different orderings.
 *
 * `_clientKey` is left inside `data` and `_entryId`/`_ordinal` are lifted out of it, which is the
 * line {@link entryDataOf} already draws: the server reads the client key out of `data` itself, and
 * stripping it turns a retry after a dropped connection into a duplicated row.
 */
export function buildStageEntries(
  spec: DwStage,
  stage: DwDraftStage | undefined
): { entries: DwSaveEntry[]; rowKeys: Array<{ entityKey: string; rowIndex: number } | null> } {
  const entries: DwSaveEntry[] = [];
  const rowKeys: Array<{ entityKey: string; rowIndex: number } | null> = [];
  const data = stageDataOf(stage);

  for (const entity of spec.entities) {
    if (entity.cardinality === "SINGLETON") {
      const values = stage?.singletons[entity.key] ?? data.singleton;
      /*
        AN EMPTY SINGLETON IS NOT SENT FOR A STAGE THIS DEVICE HAS NEVER READ.

        `save_stage` matches the stage's one singleton row by entity and replaces its `data`
        wholesale, and it writes no `RecordRevision` for stage entries — so `{"data": {}}` deletes
        every answer in that entity, in place, unrecoverably. That payload is exactly what this
        function used to emit for a stage whose local copy was blank, and a local copy is blank for
        two very different reasons: the designer emptied it, or this browser never downloaded it.
        `serverLoadedAt` is the only thing that can tell them apart (see {@link DwDraftStage}).

        So: a stage this device has reconciled with the server may still send an empty singleton —
        that is a designer clearing a stage, and it must reach the repository. A stage it has never
        read may not: there is nothing to be gained by sending nothing, and everything to lose.
        Omitting the entry entirely is safe because `save_stage` only touches the entities the
        payload names — the collection sweep is scoped by `touched_entities`, and a singleton is
        never swept by omission at all.

        AND WHEN IT IS ANSWERED, IT IS SENT AS A MERGE. Withholding the empty case was only half
        the guard, and the half that left the banner lying: a designer who typed ONE field into a
        stage this browser had never read sent `{artisanHouseholds: 412}`, `save_stage` replaced
        the singleton's `data` with exactly that, and the seven fields written in the office were
        gone — while the amber banner above the form promised in so many words that nothing left
        blank would overwrite an answer recorded elsewhere. `merge` is that promise, kept on the
        server: keys this browser never had are preserved, keys it did have still win.
      */
      const neverRead = (stage?.serverLoadedAt ?? null) === null;
      const answered = Object.values(values).some((value) => isFilled(value));
      if (!neverRead || answered) {
        entries.push({ entityKey: entity.key, data: values, merge: neverRead });
        rowKeys.push(null);
      }
      continue;
    }
    const rows = data.collections[entity.key] ?? [];
    rows.forEach((row, rowIndex) => {
      entries.push({
        entityKey: entity.key,
        entryId: row._entryId,
        // Derived from the ARRAY ORDER at send time and never from a stored `_ordinal`: a row
        // carrying its old ordinal after a reorder is sorted straight back to where it came from the
        // next time the stage is loaded, and the reorder looks like it did not take.
        ordinal: rowIndex,
        data: entryDataOf(row)
      });
      rowKeys.push({ entityKey: entity.key, rowIndex });
    });
  }
  return { entries, rowKeys };
}

/* ────────────────────────────────────────────────────────────────────────────
 * The sync pass
 * ──────────────────────────────────────────────────────────────────────────── */

export type DwSyncResult = {
  workshopsCreated: number;
  stagesSent: number;
  mediaUploaded: number;
  /** Things the server refused permanently — they stay on the device, listed, awaiting a decision. */
  failed: number;
  /** How many drafts still have something outstanding after this pass. */
  pending: number;
  /** True when the pass stopped because nothing is reaching the network. Nothing was lost. */
  stoppedOffline: boolean;
};

let syncing: Promise<DwSyncResult> | null = null;

/**
 * Send everything this device is holding, oldest edit first.
 *
 * Concurrent calls — the `online` event and a "Sync now" click landing together — share one pass;
 * running two would create one workshop twice, which is precisely what `_clientKey` cannot protect
 * against because the workshop header has no client key of its own (it is protected instead by
 * `remoteId` being written to disk before anything else moves).
 */
export function syncDesignWorkshopDrafts(): Promise<DwSyncResult> {
  if (syncing) return syncing;
  syncing = runSync().finally(() => {
    syncing = null;
  });
  return syncing;
}

function failure(message: string, permanent: boolean, attempts: number): DwDraftFailure {
  return { message, permanent, at: Date.now(), attempts };
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

  // NOTHING IS SENT UNTIL THIS STORE KNOWS WHO IS SIGNED IN. The pass authenticates with whatever
  // token is in `localStorage`, so running it before `GET /me` has answered is what filed one
  // designer's unsent workshop under the next designer to open the laptop. Reported as "stopped
  // because nothing is reaching the network", which is what it is from the caller's point of view:
  // nothing was sent, nothing was lost, and the banner's next pass will carry it.
  if (draftSessionUnknown()) {
    result.stoppedOffline = true;
    return result;
  }

  const work = await pendingWork();

  for (const item of work) {
    let draft = item.draft;
    if (!draftBelongsToSession(draft)) continue; // Somebody else's fieldwork on a shared laptop.
    if (draft.failure?.permanent) continue; // Waiting on the designer, not on the network.

    try {
      /* 1. The workshop itself. ------------------------------------------------------------- */
      if (!draft.remoteId) {
        const created = await createDesignWorkshop({
          title: draft.header.title || "Untitled design workshop",
          templateId: draft.header.templateId,
          craftName: draft.header.craftName,
          clusterName: draft.header.clusterName,
          state: draft.header.state,
          district: draft.header.district,
          startDate: draft.header.startDate,
          endDate: draft.header.endDate,
          workshopId: draft.header.workshopId,
          notes: draft.header.notes
        });
        // Written back BEFORE a single stage or byte moves. From here on the workshop exists, and a
        // pass that dies during the media must come back to the media, never to the create — the
        // resumability rule `lib/offline.ts` documents, and the reason a bad signal used to
        // duplicate a record once per sync pass for as long as it stayed bad.
        draft =
          (await mutate(draft.localId, (current) => ({
            ...current,
            remoteId: created.id,
            headerDirtyAt: null,
            header: { ...current.header, status: String(created.status) },
            failure: null
          }))) ?? draft;
        result.workshopsCreated += 1;
      } else if (item.needsHeader) {
        await patchDesignWorkshop(draft.remoteId, {
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
        });
        draft = (await mutate(draft.localId, (current) => ({ ...current, headerDirtyAt: null }))) ?? draft;
      }

      const remoteId = draft.remoteId;
      if (!remoteId) continue;

      /* 2. The photographs, before the stages that reference them. ---------------------------- */
      const blocked = new Set<string>();
      for (const media of await draftMedia(draft.localId)) {
        if (media.remoteMediaId || !media.blob) continue;
        const file = new File([media.blob], media.name, { type: media.mimeType });
        /*
          ONE FILE PER CALL, WHICH IS WHY THIS CATCHES AT ALL.

          `uploadMediaBatch` escalates a batch in which NOTHING landed — and for a batch of one, that
          is every failure, so the `failed`-shaped path below could never be reached and the refusal
          it describes was never recorded. The throw went to the pass-level catch instead, where a
          flattened error read as "unreachable", stopped the pass and came back to the same refusal
          on every future connection. A photograph the server will never accept is not a signal
          problem and must not be retried like one.

          The split is the same one the pass-level catch makes: DID THE SERVER ANSWER. It is asked
          through `isUnreachable`, which now follows `cause`, so a connection that genuinely dropped
          still stops the pass here by re-throwing and changes nothing.
        */
        let uploaded: Awaited<ReturnType<typeof uploadMediaBatch>>["uploaded"];
        let failed: Awaited<ReturnType<typeof uploadMediaBatch>>["failed"] = [];
        try {
          ({ uploaded, failed } = await uploadMediaBatch({
            files: [file],
            linkedRecordType: "designWorkshop",
            linkedRecordId: remoteId,
            caption: media.caption ?? undefined
          }));
        } catch (error) {
          if (isUnreachable(error)) throw error;
          await noteMediaFailure(
            media.id,
            error instanceof Error ? error.message : "The server refused this file."
          );
          if (media.stageKey) blocked.add(media.stageKey);
          result.failed += 1;
          continue;
        }
        if (uploaded.length && uploaded[0]?.id) {
          // The ONE place the local bytes may go, and only with an id in hand.
          await confirmLocalMedia(media.id, uploaded[0].id);
          result.mediaUploaded += 1;
          continue;
        }
        // Reachable only for a batch that landed something and refused something, which a one-file
        // batch cannot do — kept because this loop is not the only shape `uploadMediaBatch` serves,
        // and a silent fall-through would leave the bytes with no record of why they stayed.
        await noteMediaFailure(media.id, failed[0]?.error ?? "The server refused this file.");
        if (media.stageKey) blocked.add(media.stageKey);
        result.failed += 1;
      }

      /* 3. The stages. ------------------------------------------------------------------------ */
      draft = (await loadDraft(draft.localId)) ?? draft;

      /*
        THE REPAIR BRANCH, for a draft that was stranded before the fix in `putDraftStage` landed.

        A `dwlocal:` reference whose media row ALREADY carries a `remoteMediaId` is unreachable by
        the loop above — it skips a confirmed row — so nothing else in this pass will ever rewrite
        it, the stage is held back below for ever behind "it sends itself as soon as they upload",
        and the only way out was for the designer to find that exact field and type into it. The
        substitution is the same one `confirmLocalMedia` makes and is idempotent; it is done only
        when a stranded reference is actually present so an ordinary pass writes nothing.
      */
      const confirmedRefs = new Map<string, string>();
      for (const media of await draftMedia(draft.localId)) {
        if (media.remoteMediaId) confirmedRefs.set(`${LOCAL_MEDIA_PREFIX}${media.id}`, media.remoteMediaId);
      }
      if (confirmedRefs.size) {
        const held = new Set<string>();
        for (const stage of Object.values(draft.stages)) for (const ref of unresolvedMediaRefs(stage)) held.add(ref);
        const stranded = [...confirmedRefs].filter(([ref]) => held.has(ref));
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
        if (!stage || stage.failure?.permanent) continue;
        const outstanding = unresolvedMediaRefs(stage);
        if (outstanding.length) {
          // HELD BACK, NOT TRIMMED. Sending the stage without the reference would omit the key, and
          // `save_stage` writes the cleaned entry wholesale — so an omitted key deletes whatever the
          // server already holds under it. A stage waiting on one photograph is a delay; a stage
          // that silently erased last week's photo id is a hole in the report nobody will notice.
          await noteStageFailure(
            draft.localId,
            stageKey,
            failure(
              `${outstanding.length} attached file${outstanding.length === 1 ? " is" : "s are"} still on this device, so this ` +
                "stage has not been sent yet. It sends itself as soon as they upload — nothing has been thrown away.",
              false,
              (stage.failure?.attempts ?? 0) + 1
            )
          );
          blocked.add(stageKey);
          continue;
        }

        /*
          THE ARTEFACT IS RECOGNISED BEFORE THE FIELD LIST IS EVEN CONSULTED.

          A stage that holds nothing AND has never been read from the server has nothing to send
          under any registry, so this cannot wait for `stageSpecFor` — which returns null on a
          browser that has not cached a registry yet (a tab that has only ever seen the workshop
          LIST, which does not load one), and would then file the artefact as a permanent "this
          build has no stage called …" refusal: a red banner about a stage nobody typed into, and a
          dirty flag left standing so `adoptServerStage` goes on refusing to fold the server's real
          answers in. Clearing it loses nothing — there is nothing in it — and lets the next read
          repopulate the stage from the repository.
        */
        if (stage.serverLoadedAt === null && !stageHoldsSomething(stage)) {
          await mutate(draft.localId, (current) => {
            const target = current.stages[stageKey];
            // Re-read inside the transaction: the designer may have started typing into this very
            // stage while the pass was working through the one before it.
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
              `This build's field registry has no stage called “${stageKey}”, so its answers cannot be sent. They are still ` +
                "on this device. Reload the page once you have a connection to pick up the current field list.",
              true,
              (stage.failure?.attempts ?? 0) + 1
            )
          );
          result.failed += 1;
          continue;
        }

        const { entries } = buildStageEntries(spec, stage);

        /*
          THE SAME ANSWER FOR A STAGE THE REGISTRY LEAVES WITH NOTHING TO SAY.

          The guard above catches the artefact; this catches a RECONCILED stage that still builds no
          entries at all — eight of the twenty-two stages declare no singleton entity (sketches,
          prototypes, the cost sheet), so an empty entry list is their ordinary shape once every row
          is gone. A PUT with no entries changes nothing on the server, and leaving the stage dirty
          would report work waiting for ever.

          `stageHoldsSomething` is checked as well as the entry count because a pending REMOVAL is
          something: it keeps its dirty flag and its PUT, which is the only way a deletion can reach
          the server.
        */
        if (!entries.length && !stageHoldsSomething(stage)) {
          await mutate(draft.localId, (current) => {
            const target = current.stages[stageKey];
            // Re-read inside the transaction: the designer may have typed into this very stage
            // while the pass was working through the one before it.
            if (!target || stageHoldsSomething(target)) return current;
            return {
              ...current,
              stages: { ...current.stages, [stageKey]: { ...target, dirtyAt: null, removedFrom: [], failure: null } }
            };
          });
          continue;
        }

        let saved;
        try {
          saved = await saveDesignWorkshopStage(remoteId, stageKey, {
            entries,
            // Armed only by a deletion, and by a deletion that has not yet been acknowledged. True
            // means "these are now exactly the rows", which is the only way a removal can reach the
            // server and is dangerous otherwise: it would sweep any row a second editor added.
            replaceCollections: stage.removedFrom.length > 0,
            // WITHOUT THIS, DELETING THE LAST ROW OF A COLLECTION NEVER REACHES THE SERVER. The
            // sweep only considers entities the payload named, and an emptied collection sends no
            // entries at all, so it names itself nowhere. `removedFrom` is exactly that list.
            emptiedEntities: stage.removedFrom,
            submit: false
          });
        } catch (error) {
          /*
            A 5xx IS A REFUSAL, NOT A LOST CONNECTION, AND IT MUST NOT CYCLE FOR EVER.

            This used to fall through to the pass-level `isTransient` catch, which answers "is it
            worth retrying" and says yes to every status ≥ 500 — so a stage the server rejects
            deterministically (a lone surrogate in a name, a non-finite decimal: both reproduce as
            500s on this endpoint) set `stoppedOffline`, told the designer their connection was
            down, blocked every stage behind it, and came back to the same rejection on the next
            pass, for ever, with nothing on screen naming the stage. A server that ANSWERED has
            made a decision; record it against the stage that caused it, name that stage, and let
            the other twenty-one through.

            Rethrown, deliberately: a fetch that never completed (offline), a 408/429 (the server
            asking for time), and a 409 (`save_stage` refusing to write into a workshop an admin
            deleted — a whole-workshop condition with its own sentence below).
          */
          const answered = error instanceof ApiError;
          if (!answered || error.status === 408 || error.status === 429 || error.status === 409) throw error;
          await noteStageFailure(
            draft.localId,
            stageKey,
            failure(
              `The repository refused stage “${stageKey}”: ${error.message} It is still on this device and nothing has been ` +
                "thrown away, but it will keep being refused until the answer that caused it is corrected — this is not a " +
                "connection problem. Open the stage, then use Try again.",
              true,
              (stage.failure?.attempts ?? 0) + 1
            )
          );
          result.failed += 1;
          continue;
        }

        const rejected = Object.keys(saved.errors ?? {}).length;
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
                // `stage` is the snapshot this PUT was built from, so it is exactly what the server
                // has now accepted; `target` is whatever the designer has since typed or deleted.
                // Answering both fields against the payload rather than clearing them is the whole
                // guard — see {@link unsentAfterPush}.
                ...unsentAfterPush(target, { dirtyAt: stage.dirtyAt, removedFrom: stage.removedFrom }),
                lastPushedAt: now,
                completeness: saved.completeness ?? target.completeness,
                failure: rejected
                  ? failure(
                      `The server refused ${rejected} answer${rejected === 1 ? "" : "s"} in this stage. Everything else was ` +
                        "saved; open the stage to see which fields are marked.",
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
      /*
        THE TEST IS "DID THE SERVER ANSWER", NOT `isTransient`.

        `isTransient` answers a different question — "is it worth retrying" — and it counts every
        5xx as yes. Used here it told a designer whose save the server had permanently refused that
        the network was down, stopped the pass, left nothing marked failed and came back to the
        same rejection on the next connection, indefinitely. The report page already draws this
        distinction on purpose (see the note above its download handler); this is the same split,
        carried to the drain. 408 and 429 stay on the offline side: the first means the request
        never completed, the second is the server explicitly asking for time.
      */
      const backOff = error instanceof ApiError && (error.status === 408 || error.status === 429);
      if (isUnreachable(error) || backOff) {
        // Still offline, or the API is down. Stop the pass; everything behind this stays exactly
        // where it is. Nothing is lost and nothing is marked failed — a connection that dropped is
        // not a refusal, and telling a designer their workshop was rejected because the wifi went
        // is how an app teaches people to ignore its warnings.
        result.stoppedOffline = true;
        break;
      }
      const status = error instanceof ApiError ? error.status : 0;
      await mutate(draft.localId, (current) => ({
        ...current,
        failure: failure(
          status === 409
            ? // A 409 here is `save_stage` refusing to write into a workshop somebody deleted — not
              // an echo of our own create. Nothing has been sent and nothing has been thrown away.
              "This workshop has been deleted on the server, so nothing more can be sent to it. Everything you captured is " +
                "still on this device. Ask an admin to restore it, then sync again."
            : error instanceof Error
              ? error.message
              : "The server refused this workshop.",
          true,
          (current.failure?.attempts ?? 0) + 1
        )
      }));
      result.failed += 1;
    }
  }

  result.pending = (await pendingWork()).length;
  return result;
}

/**
 * The registry stage declaration a draft was written against.
 *
 * Prefers the version stamped on the draft, which is how a stage captured before a deploy is still
 * sent under the field list it was captured with; falls back to whatever registry this browser has.
 */
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

async function noteMediaFailure(localMediaId: string, message: string): Promise<void> {
  await transact([STORE_MEDIA], "readwrite", async (stores) => {
    const media = await req<DwDraftMedia | undefined>(
      stores[STORE_MEDIA].get(localMediaId) as IDBRequest<DwDraftMedia | undefined>
    );
    if (!media) return;
    // Note the failure; do NOT touch `blob`. See the header.
    await req(stores[STORE_MEDIA].put({ ...media, attempts: media.attempts + 1, lastError: message }));
  });
}

/**
 * Forgive one permanent refusal so the next pass tries again.
 *
 * The manual retry behind the banner's button. Nothing is deleted and nothing is re-sent here — the
 * refusal is simply cleared, because the designer has been told what it was and has decided the
 * conditions have changed (an admin restored the workshop, a colleague freed the duplicate name).
 */
export async function retryDraft(id: string): Promise<void> {
  await mutate(id, (draft) => ({
    ...draft,
    failure: null,
    stages: Object.fromEntries(
      Object.entries(draft.stages).map(([key, stage]) => [key, { ...stage, failure: null }])
    )
  }));
}
