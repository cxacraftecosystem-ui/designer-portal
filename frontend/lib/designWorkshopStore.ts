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
import {
  CUSTOM_ENTITY_KEY,
  adoptCustomDefinition,
  customFieldsForStage,
  fetchCustomDefinition,
  type DwCustomDefinition,
  type DwCustomField
} from "@/lib/customSections";
import { uploadMediaBatch } from "@/lib/media";
// The one rule for "did anything reach the server" lives in the outbox and is imported rather than
// restated. Two answers to that question is two different ideas of what "offline" means, and the
// wrong one either strands a queue for ever or replays a rejection until somebody clears storage.
// `isUnreachable`, NOT `isTransient`: the latter answers "is it worth retrying" and says yes to
// every 5xx, which is how a stage the server had permanently refused was reported as a lost signal.
import { APP_RUN_ID, blocksRetry, isSchemaRefusal, isUnreachable } from "@/lib/offline";

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
export const DW_DRAFT_SCHEMA_VERSION = 3;

/**
 * The `skewRun` stamped on a refusal recorded BEFORE this store could tell the two kinds apart.
 *
 * Deliberately a fixed string rather than a real {@link APP_RUN_ID}: it can never equal the running
 * one, so every such refusal is re-attempted exactly once and then re-recorded under the new policy
 * — genuinely refused items come straight back with their sentence and stick, and the ones that were
 * only ever a version skew go up. See the v3 rung in {@link migrateDraft}.
 */
const PRE_SKEW_POLICY_RUN = "recorded-before-the-skew-retry-policy";

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
  /**
   * The app run that recorded a refusal ONLY AN UPDATE CAN CLEAR — a SCHEMA refusal, where this
   * build of the client and this build of the server disagree about the shape of the request.
   *
   * Null on every other failure, which keeps `permanent` meaning exactly what it always meant for
   * them. "Permanent" is the right marking for a refusal the DESIGNER can fix; it is the wrong one
   * for a dialect mismatch, whose fix is an update to one of the two and which would otherwise leave
   * the app unable to recover from a skew after the skew had gone. The whole policy, and why the
   * trigger is an app run rather than a build number, is in {@link blocksRetry}.
   */
  skewRun?: string | null;
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
  /**
   * Answers to this workshop's designer-defined questions for this stage. Mirrors the server's
   * `_custom` row one for one: flat, keyed by the designer's own field keys.
   *
   * A SIBLING OF `singletons`, NEVER NESTED INSIDE ONE. `splitSingletons` copies across only the keys
   * the registry declares and drops everything else on the floor, so a custom answer smuggled through
   * it would either vanish before the request or be posted inside a core entry, dropped server-side,
   * and returned in `droppedKeys` — firing the registry-drift banner on every save of every workshop
   * that has a custom section and destroying the one drift signal this repository has.
   *
   * **OPTIONAL, AND EVERY READ MUST DEFAULT IT.** No migration rung is spent on it (see
   * {@link migrateDraft}), so a stage record written by a build before this one has no such key at
   * all — and `?? emptyStage(...)` does not help there, because that fallback only fires when the
   * whole stage is missing. `stage.custom ?? {}` at every read is the rule.
   */
  custom?: DwEntryData;
  /**
   * What the last {@link foldStageInto} added to this stage, or removed the possibility of, in words.
   *
   * Null when the last read changed nothing this designer can see, which is the ordinary case — so an
   * unchanged re-read does not re-announce itself on every open. Written only by
   * {@link adoptServerStage}'s dirty branch, because that is the only path that folds.
   *
   * **OPTIONAL, AND EVERY READ MUST DEFAULT IT.** No migration rung is spent on it — the same rule
   * and the same reason as `custom` above: a stage record written by a build before this one has no
   * such key, and `?? emptyStage(...)` does not help, because that fallback only fires when the whole
   * stage record is missing.
   */
  foldNote?: string | null;
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
  /**
   * WHICH header fields were edited locally — so a PATCH carries those and nothing else.
   *
   * ── THE DOOR THIS SHUTS ──────────────────────────────────────────────────────────────────────
   *
   * The sync pass's header PATCH sent **every** field off the local copy: `title`, `craftName`,
   * `notes`, the dates, all of them. `ensureDraft` seeds a header of empty strings and nulls for a
   * workshop this browser has merely OPENED, and what kept that harmless was `headerDirtyAt: null`
   * and the fact that nothing called {@link patchDraftHeader}. The first UI to call it on a workshop
   * this browser had not read in full would have nulled the office's `notes` and overwritten its
   * title with `""`, under a 200, with no screen saying anything.
   *
   * A door shut only by having no caller is not shut. This is the stage form's own `serverLoadedAt`
   * argument applied to the header: send what this browser actually holds a read value for, which
   * for a header means the fields somebody typed into HERE.
   *
   * Optional for the reason {@link DwDraftStage.custom} is: no schema rung is spent, so a draft
   * written by an older build does not carry it. An absent or empty list means "no record of which
   * fields were touched", and the pass then falls back to the whole header — which is exactly the
   * behaviour that shipped, unchanged, and is unreachable in practice because
   * {@link patchDraftHeader} is the only thing that can arm a PATCH and it always records its keys.
   */
  headerDirtyKeys?: string[];
  stages: Record<string, DwDraftStage>;
  createdAt: number;
  updatedAt: number;
  /** The registry version this draft was last written against — the same digest `DwDetail` carries. */
  registryVersion: string;
  /**
   * The digest of the custom definition this draft was last written against. A SECOND FIELD, and it
   * mirrors {@link DwDraft.registryVersion} rather than joining it.
   *
   * **IT MUST NOT BE FOLDED INTO `registryVersion`.** That string has exactly one functional read —
   * `stageSpecFor` passes it to `cachedRegistry(version)` as a KEY into the registry object store, so
   * that a stage captured before a deploy is still sent under the field list it was captured with. A
   * composite value would match no cached registry at all and every stage would fall back to
   * "whatever this browser happens to hold", silently, for every workshop.
   *
   * Its own functional read is the same idea one door along: it is what lets this client notice that
   * the definition it holds is not the one the server just validated a save against, and say so
   * instead of quietly showing a designer a question that no longer exists or hiding one that does.
   *
   * Optional for the reason {@link DwDraftStage.custom} is: no rung is spent, so a draft written by
   * an older build does not carry it, and `?? ""` at every read is the rule.
   */
  customSchemaVersion?: string;
  /**
   * This workshop's custom definition, exactly as `definition_payload` returned it. Null = never read.
   *
   * **ON THE DRAFT RECORD, AND DELIBERATELY NOT IN A FOURTH IndexedDB OBJECT STORE.** Three reasons,
   * in ascending order of severity, and the first two are why the obvious answer is the wrong one:
   *
   *  1. Forgetting the {@link DB_VERSION} bump fails on the EXISTING feature rather than on this one.
   *     `indexedDB.open(DB_NAME, 1)` against a browser already at 1 never fires `onupgradeneeded`, so
   *     the store is simply absent; `transact()` then names it in `db.transaction(names, mode)` with
   *     no try, and the `NotFoundError` is swallowed differently at each call site — `refreshDrafts`
   *     sets `cache = []` and every draft vanishes from the list, `loadDraft` returns null, and
   *     `mutate` returns null so every write silently no-ops.
   *  2. Bumping it is worse. `openDb` registers three handlers and neither `onblocked` nor
   *     `onversionchange` is one of them, so a v1→v2 open requested while another tab holds the
   *     database at v1 fires `blocked` and NEVER RESOLVES: `dbPromise` stays pending for ever, with no
   *     error and no timeout. This module's own concurrency note contemplates two tabs, which is the
   *     ordinary case — the workshop open in one and the stage list in another.
   *  3. The draft record is already per workshop, which is exactly a definition's scope, and adding a
   *     field to it is free: `migrateDraft` only climbs and leaves a document from the future alone,
   *     and `mutate` writes back a spread, so a key an older build has never heard of is preserved
   *     rather than dropped.
   *
   * A copy is kept at all for one reason: the definition decides whether a custom section can be
   * DRAWN with no connection. Without it a designer standing in a cluster sees a stage that asks
   * nothing of them, which is indistinguishable from a workshop that has no custom questions.
   *
   * OPTIONAL AND NULLABLE, AND THE TWO MEAN THE SAME THING ON PURPOSE: absent is a document written
   * by a build before this feature, null is this build saying it has not read one. Both resolve to
   * "unknown", which is the only honest floor — resolving either to an EMPTY definition would make a
   * stage form assert that the workshop asks nothing of its own on the strength of a missing key.
   */
  customDefinition?: DwCustomDefinition | null;
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
/**
 * One v2 failure record, marked so the v3 policy gives it a single re-attempt. See the v3 rung.
 *
 * Only PERMANENT failures are touched. A non-permanent one (a stage waiting on a photograph) is
 * already re-tried on every pass and stamping it would be claiming a skew that was never diagnosed.
 */
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
      case 2:
        /*
          v3 RE-TRIAGES EVERY REFUSAL A v2 DOCUMENT RECORDED, because v2 could not tell the two kinds
          apart and this rung is the only thing that can reach the ones already on disk.

          A v2 `failure` says `permanent: true` for a rejected field (the designer's to fix, so it
          must go on sticking) and for a schema refusal (nobody's to fix, and it must NOT). The
          record does not say which — the discriminator was in the ApiError, which was thrown away
          the moment the sentence was written. It is not recoverable from the prose either: the
          sentence was reworded on 2026-08-08 and a message match would be a guess.

          So the rung does not guess. It stamps a `skewRun` that can never equal the running one,
          which buys each stranded item EXACTLY ONE re-attempt under the new policy: a genuine
          refusal answers the same way and is re-recorded with `skewRun: null`, sticking for good; a
          skew that has since been closed simply syncs. The cost is one request per already-failed
          item, once per browser — and the alternative is the reported defect, where a stage refused
          for a `merge` key the API has since learned goes on telling a designer to correct an answer
          that was never wrong, with no way out but a button they have no reason to press.

          Nothing is cleared and no sentence is lost: the banner reads exactly as it did until the
          re-attempt answers.
        */
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
    custom: {},
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
  /*
    THE CUSTOM ANSWERS COUNT, AND OMITTING THEM WOULD HAVE STRANDED EVERY CUSTOM-ONLY STAGE.

    This function is not only a readout — the sync pass gates on it twice, and both gates CLEAR the
    dirty flag on a stage it says holds nothing: once for the never-read artefact and once for a
    reconciled stage that builds no entries. A stage whose only content is a designer's own answers
    would have hit both, had its `dirtyAt` reset, and never been sent — with nothing on screen saying
    so, because a cleared flag reads as "sent".

    THAT IS THE ORDINARY CASE, NOT AN EDGE ONE. Eight of the twenty-two stages declare no singleton
    entity at all (existing products, both sketch stages, all three prototype stages, final
    documentation, costing), so "a stage whose only answers are custom" is exactly what a designer
    extending stage 11 or 13 produces.

    Judged KEY BY KEY through `isFilled` and never on the container: a dict with keys is truthy even
    when every answer inside it is blank, so testing the bucket as a whole would report a stage as
    holding work because a form had been rendered on it.
  */
  for (const value of Object.values(stage.custom ?? {})) if (isFilled(value)) return true;
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
    // A workshop that exists only on this device cannot have a definition: the route that authors one
    // is a server write, and there is no workshop on the server yet to attach it to. Null rather than
    // an empty definition, because "never read" and "read, and there are none" are different facts
    // and the screen says different things about them.
    customSchemaVersion: "",
    customDefinition: null,
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
    // Null, not empty: this draft has been SEEDED for a workshop the server owns and has read nothing
    // of it yet. An empty definition here would assert "this workshop asks no questions of its own"
    // on the strength of a record that was created by opening a page.
    customSchemaVersion: "",
    customDefinition: null,
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

/**
 * Edit the header locally. Marks it unsent; the sync pass turns it into a create or a PATCH.
 *
 * IT RECORDS *WHICH* FIELDS IT TOUCHED, and that is not bookkeeping — see
 * {@link DwDraft.headerDirtyKeys}. The PATCH this arms used to send the whole local header, so
 * calling this on a workshop seeded by `ensureDraft` (a header of empty strings and nulls, because
 * the browser has only OPENED the workshop) would have nulled the office's notes and blanked its
 * title under a 200. Naming the edited keys is what keeps a PATCH to what somebody actually typed.
 */
export async function patchDraftHeader(id: string, patch: Partial<DwDraftHeader>): Promise<DwDraft | null> {
  // `definedOnly` for the same reason `createLocalDraft` uses it: a key present with `undefined` is a
  // box that was left blank, not an instruction to clear the server's value — and recording it as
  // edited would put it in the PATCH as `undefined`, which is the very overwrite this exists to stop.
  const touched = definedOnly(patch);
  return mutate(id, (draft) => ({
    ...draft,
    header: { ...draft.header, ...touched },
    headerDirtyAt: Date.now(),
    headerDirtyKeys: Array.from(new Set([...(draft.headerDirtyKeys ?? []), ...Object.keys(touched)]))
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
  data: {
    singletons: Record<string, DwEntryData>;
    collections: Record<string, DwRow[]>;
    removedFrom?: string[];
    /**
     * The designer's own answers, when the caller is editing them.
     *
     * OMITTED MEANS "I HAVE NOTHING TO SAY ABOUT THESE", NOT "CLEAR THEM". The `...previous` spread
     * below is what keeps that promise: a write from a screen that does not edit custom answers — the
     * photo intake, a media confirmation — leaves the bucket exactly as it stands. Passing `{}` is a
     * designer clearing every custom answer and IS written, which is the same distinction the server
     * draws between no `_custom` entry and an empty one.
     */
    custom?: DwEntryData;
  }
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
            // REPLACED WHEN THE CALLER SENT ONE, KEPT WHEN IT DID NOT — see the note on the parameter.
            // Not merged into the previous bucket, because a designer clearing one answer has to be
            // able to clear it: a merge would make every custom answer permanently un-erasable, which
            // is the same defect a `{**previous, **sent}` merge on every save would be server-side.
            custom: data.custom ?? previous.custom ?? {},
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
  const sentDirtyAt = sent.dirtyAt ?? 0;
  /*
    THE SECOND TEST IS FOR A CLOCK THAT DID NOT MOVE, and without it this rule still loses the
    deletion it was written to keep.

    A designer typing — or deleting — while the request is in flight leaves a NEWER `dirtyAt`
    behind, and that is the ordinary signal: `removedFrom` only ever GROWS through `putDraftStage`,
    which stamps `dirtyAt` in the same write, so the comparison answers for both fields. But
    `dirtyAt` is `Date.now()`, and two writes can carry the SAME number — a browser that coarsens
    the clock against fingerprinting advances it in steps of 100 ms, and the whole race here is
    measured in milliseconds. A deletion written in the payload's own tick is then not "newer", the
    flag is cleared by an acknowledgement that never carried it, and the row is back on the
    designer's screen and in the officer's .docx: the exact failure, one clock tick wide.

    So the growth of `removedFrom` is asked DIRECTLY as well. It is not a timestamp and cannot be
    coarsened away, and it can only ever ADD a keep — the equality guard means an ordinary push,
    whose list is what it sent, still settles the stage. It is NOT a subtraction: see above for why
    subtracting the acknowledged keys resurrects the second deletion out of one collection.
  */
  const deletedSince = stage.removedFrom.some((key) => !sent.removedFrom.includes(key));
  const superseded =
    stage.dirtyAt !== null && (stage.dirtyAt > sentDirtyAt || (stage.dirtyAt === sentDirtyAt && deletedSince));
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
    /**
     * Did any entry in the pushed payload carry `merge: true`?
     *
     * **THIS EXISTS BECAUSE STAMPING `serverLoadedAt` AFTER A MERGE PUSH DESTROYS THE ANSWERS THE
     * MERGE HAD JUST PRESERVED.** A merge push says "I am sending every key I HAVE, not every key
     * there IS", and the server answers by writing the UNION of its row and this payload. So after
     * it the server's row is a **superset** of what this browser holds — the two do NOT agree, which
     * is precisely the opposite of what the old unconditional stamp asserted in a comment.
     *
     * The sequence it cost, end to end. A designer opens a stage on a laptop with no signal; the
     * fetch throws, the form seeds from the local draft, `serverLoadedAt` stays null and an amber
     * banner promises that *nothing you leave blank will overwrite an answer recorded elsewhere*.
     * They answer one field. Back on signal they save: the entry goes up `merge: true`, the server
     * correctly writes `{the office's answers} ∪ {theirs}` — and the stamp lands. Still on the same
     * page they correct that one field and save again. `neverRead` is now false, `merge` is omitted,
     * and the server replaces the row with this browser's bucket alone. **Every answer the office
     * typed is gone, in place, with no `RecordRevision` to recover it**, `droppedKeys` is empty
     * because no key was unknown, and the screen says "Stage saved".
     *
     * It is not a new defect and it is not confined to the custom container: the identical stamp and
     * the singleton `merge: true` are both at `HEAD`, so this is the shipped app, and the singleton
     * arm carries the seven-fields-of-stage-4 erasure this file already documents as having happened
     * once. The custom container merely inherits it.
     *
     * THE SYNC PASS WAS ALREADY RIGHT, WHICH IS THE EVIDENCE THAT THIS STAMP WAS THE ODD ONE OUT: it
     * spreads only `unsentAfterPush`, `lastPushedAt`, `completeness` and `failure`, and never touches
     * `serverLoadedAt` at all. Two acknowledgement sites disagreeing about one field is what a defect
     * of this shape looks like from the outside.
     */
    mergedEntries?: boolean;
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
          /*
            THE SERVER HAS TAKEN THIS DEVICE'S COPY — BUT THAT ONLY MEANS THE TWO AGREE WHEN THE PUSH
            WAS NOT A MERGE.

            A plain push replaces the row with what was sent, so afterwards the server's row IS this
            browser's copy and the stage may be re-sent freely. A MERGE push is the opposite: it asks
            the server to keep the keys this browser never had, so the row that results is a SUPERSET
            of what was sent. Stamping here would tell the next save "you have read the server's copy"
            when it has not — and the next save would then omit `merge` and delete every key it never
            saw. See `mergedEntries` above for the whole sequence and for why this is the shipped
            app's defect rather than this feature's.

            Left null after a merge, the stage simply keeps merging on subsequent saves until an actual
            read lands. That is the fail-safe direction: merging twice preserves data that did not need
            preserving, whereas replacing once destroys data that did.
          */
          serverLoadedAt: options.mergedEntries ? stage.serverLoadedAt : (stage.serverLoadedAt ?? now),
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
 * The registry object this tab has already confirmed is byte-identical to what IndexedDB holds.
 * Compared by IDENTITY, never by version — see {@link cacheRegistry}.
 */
let confirmedStored: DwRegistry | null = null;

/**
 * Keep the registry this build just fetched, keyed by the version string the schema endpoint
 * returned.
 *
 * KEYED BY VERSION rather than stored as a single "latest" row, because the version IS the identity:
 * `registry_version` is a content digest of every key, type and tier, and a workshop's stored
 * `schemaVersion` names the one it was written against. Keeping the last {@link REGISTRY_KEEP} means
 * a tab that comes back after a deploy — or after a rollback — can still render the form a stage was
 * captured with, instead of showing a designer a field list that never applied to their record.
 *
 * The version is the KEY, not a proof of equal content — see the long note inside on why storing a
 * row per version does not license skipping the write when a row already exists.
 */
export async function cacheRegistry(registry: DwRegistry): Promise<void> {
  // Already confirmed stored, THIS OBJECT, in this tab. `fetchStageRegistry` returns one stable
  // identity per tab per version, so this reference check is what keeps the common case — a
  // designer walking between two stages — at zero IndexedDB reads and zero structured clones.
  if (confirmedStored === registry) return;
  await transact([STORE_REGISTRY], "readwrite", async (stores) => {
    const store = stores[STORE_REGISTRY];
    const held = await req<RegistryRecord | undefined>(
      store.get(registry.version) as IDBRequest<RegistryRecord | undefined>
    );
    // AN EQUAL VERSION IS NOT AN EQUAL DOCUMENT, AND ASSUMING IT WAS SHIPPED A DEFECT.
    //
    // This branch used to be `if (held) return;`, justified as "`version` is a content digest, so
    // an equal version IS the same document". It is not one. `registry_version()` digests key,
    // type, tier, required, enum name, deprecated, derivation and hydration — and says in its own
    // docstring that it is DELIBERATELY insensitive to labels and help text, so that retitling a
    // field does not invalidate every cached draft on every phone. `fetchStageRegistry`'s comment
    // has this right; this one did not, and the two disagreed in the same feature.
    //
    // What that cost: when the 22 stages' `notes` were rewritten to stop quoting the source
    // document's reviewer at designers, the digest did not move — correctly, since no key changed.
    // A browser holding the old record would therefore have kept serving the reviewer quotes out
    // of IndexedDB forever, and would have shown them again the moment it went offline. Android
    // never had this bug: `StageSchemaStore.store` rewrites its cache file on every fetch for
    // exactly this reason, and says so.
    //
    // So compare the CONTENT. It costs one stringify of a payload that has just crossed the
    // network, at most once per tab, and only when this browser already holds the version.
    if (held && JSON.stringify(held.registry) === JSON.stringify(registry)) return;
    await req(store.put({ version: registry.version, registry, storedAt: Date.now() } satisfies RegistryRecord));
    const all = await req<RegistryRecord[]>(store.getAll() as IDBRequest<RegistryRecord[]>);
    const stale = all.sort((a, b) => b.storedAt - a.storedAt).slice(REGISTRY_KEEP);
    for (const row of stale) await req(store.delete(row.version));
  })
    .then(() => {
      // ONLY once the transaction has COMMITTED. Setting this inside the transaction would be
      // wrong in the one case it matters: an IndexedDB transaction can abort after its individual
      // requests have succeeded, rolling the write back — and this tab would then spend the rest
      // of its life skipping a write that never landed.
      confirmedStored = registry;
    })
    .catch(() => {
      // A registry that could not be cached costs an offline form, not a save. Never fatal — and
      // `confirmedStored` stays as it was, so the next navigation retries.
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

/* ────────────────────────────────────────────────────────────────────────────
 * Folding the server's copy into a draft that holds work
 * ──────────────────────────────────────────────────────────────────────────── */

/** What {@link foldStageInto} changed, so the screen can say so rather than change under a thumb. */
export type DwStageFold = {
  /** The stage to keep: local values untouched, the server's unseen ones added, authority earned. */
  stage: DwDraftStage;
  /** Core singleton field keys that came from the server and were not in this browser. */
  added: string[];
  /** Rows that came from the server and were not in this browser, by entity key. */
  addedRows: Record<string, number>;
  /** Custom field keys that came from the server and were not in this browser. */
  addedCustom: string[];
  /**
   * Rows the server holds in a collection the designer EMPTIED here, which this fold declined to add
   * back and the next save will therefore sweep. By entity key.
   */
  sweptRows: Record<string, number>;
};

/**
 * Read the server's copy of a stage INTO a draft that already holds work, without losing either.
 *
 * ── WHY THIS EXISTS: ON THE WEB, A WITHHELD DELETION HAD NO WAY BACK ─────────────────────────────
 *
 * `serverLoadedAt` is the browser's authority to say "these are now exactly the rows", and it was set
 * by ONE thing: {@link adoptServerStage} on a stage whose `dirtyAt` was null. A stage holding an
 * unsent deletion is always dirty — `removedFrom` and `dirtyAt` are kept and cleared together by
 * {@link unsentAfterPush} — so the adopt was refused, the stage stayed dirty, and the deletion was
 * owed FOR EVER. The row the designer deleted stayed alive in the repository and printed in the .docx.
 *
 * That was the cost of correctly refusing to sweep without authority, and this is the other half of
 * it: what comes back is FOLDED rather than adopted, which earns the authority without overwriting
 * anything the designer typed. Android has had this since `dwFoldServerStage`; **this is deliberately
 * the same function, rule for rule**, because two surfaces that fold differently produce two copies of
 * one workshop that disagree about the fieldwork with nothing in either saying so.
 *
 * ── THE FOLD IS "ADD WHAT THIS BROWSER HAS NEVER SEEN", AND NOTHING ELSE ─────────────────────────
 *
 * Every key and every row already in the draft is kept exactly as it is, values and all. Three
 * consequences, all intended:
 *
 *  * nothing the designer typed is overwritten, which is the local-wins rule kept intact;
 *  * after the fold the draft is a superset of the server's copy, which is what makes the authority
 *    honest — "delete what I do not name" can no longer name anything the designer has not been shown;
 *  * a value the designer CLEARED here comes back, because on a stage this browser had never read the
 *    clearance never reached the server, so the server does still hold it and showing it is the truth.
 *    It can be cleared again, and this time it propagates, because the fold earned the authority.
 *
 * ── AND ONE THING THAT DOES NOT COME BACK: A COLLECTION THE DESIGNER EMPTIED ─────────────────────
 *
 * A row in an entity named by `removedFrom` is NOT folded back in, for the reason
 * {@link DwStageFold.sweptRows} states: a cleared value leaves no record, an emptied collection
 * leaves one, and folding the server's rows back over that record would REVERSE the deletion — the
 * next payload would name every row again and the sweep would sweep nothing. The designer's explicit
 * action wins over an inference, and the collateral is counted rather than hidden.
 *
 * PURE, and that is the point: its mistake would be an overwrite of a designer's own text, so it is
 * decided with no store, no fetch and no React anywhere near it, and pinned by
 * `e2e/stage-fold-unit.spec.ts`.
 */
export function foldStageInto(
  spec: DwStage,
  current: DwDraftStage,
  incoming: DwStageData
): DwStageFold {
  /*
    `containsKey`, NOT "is filled", and it is the same argument `dwFoldServerStage` spells out. The
    stage form REMOVES a key whose value went blank rather than storing "", so a field cleared this
    morning is absent here and indistinguishable from one never typed — this loop re-fills it from the
    server, which is the documented behaviour and not a clearance being ignored. What the `in` test is
    actually for is every other way an empty string reaches this draft (a server that holds "", a
    rich-text control that wrote an empty document, a draft written by an older build): for those,
    "has this browser ever had an opinion about this key" is the right question, an empty string IS an
    opinion, and overwriting it with the server's paragraph would undo an edit rather than reveal one.
  */
  const added: string[] = [];
  const singletons: Record<string, DwEntryData> = { ...current.singletons };
  const fromServer = splitSingletons(spec, incoming.singleton ?? {});
  for (const [entityKey, values] of Object.entries(fromServer)) {
    const held = current.singletons[entityKey] ?? {};
    const merged: DwEntryData = { ...held };
    for (const [key, value] of Object.entries(values)) {
      if (key in merged) continue;
      merged[key] = value;
      // The underscore keys are the protocol's own and are never shown to anybody, so they fold
      // silently rather than being counted as an answer that "appeared".
      if (!key.startsWith("_")) added.push(key);
    }
    singletons[entityKey] = merged;
  }

  const addedCustom: string[] = [];
  const custom: DwEntryData = { ...(current.custom ?? {}) };
  for (const [key, value] of Object.entries(incoming.custom ?? {})) {
    if (key in custom) continue;
    custom[key] = value;
    if (!key.startsWith("_")) addedCustom.push(key);
  }

  /*
    MATCHED ON THE ROW'S OWN KEY, which is the same identity `save_stage` matches on: `_clientKey`
    first, then the server's `_entryId`. A row this browser created, synced and is holding is therefore
    recognised in the server's answer and is NOT added a second time — without that, one fold would
    double every row of every costing table this browser had ever sent.

    Appended AFTER the local rows rather than interleaved by ordinal. The server's ordinals describe
    the server's list; this browser's describe a list the designer has been looking at and reordering,
    and splicing one into the other would reshuffle rows under a cursor.
  */
  const emptied = new Set(current.removedFrom ?? []);
  const declared = new Set(
    spec.entities.filter((entity) => entity.cardinality !== "SINGLETON").map((entity) => entity.key)
  );
  const collections: Record<string, DwRow[]> = { ...current.collections };
  const addedRows: Record<string, number> = {};
  const sweptRows: Record<string, number> = {};

  for (const [entityKey, serverRows] of Object.entries(incoming.collections ?? {})) {
    // Only entities this build's registry declares for this stage. A row under a key the registry has
    // since dropped cannot be drawn, cannot be edited, and would be sent straight back up as an entity
    // the server reports in `droppedKeys` — a browser reporting registry drift to itself.
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
      /*
        THE DESIGNER DELETED THIS COLLECTION, SO IT STAYS DELETED. Counted, not silently dropped — and
        counted as WHAT THE NEXT SAVE WILL ACTUALLY REMOVE, which is not the number of rows the server
        sent. The sweep deletes the rows the payload does not NAME, and the payload names every row
        this draft still holds; so a server row whose client key or entry id is already here survives
        the save, and claiming it was about to be deleted would be a false alarm. That is reachable in
        the ordinary way: empty a collection, then start it again with a fresh row before the stage is
        next read.
      */
      const unseen = serverRows.filter(isUnseen).length;
      if (unseen > 0) sweptRows[entityKey] = unseen;
      continue;
    }

    const appended = serverRows.filter(isUnseen);
    if (appended.length > 0) {
      collections[entityKey] = [
        ...held,
        // Minted here for the same reason {@link withClientKeys} mints it on the adopt path: a row with
        // no key of its own cannot be matched by a replayed save and would be inserted a second time.
        ...appended.map((row) => (row._clientKey ? row : { ...row, _clientKey: newClientKey() }))
      ];
      addedRows[entityKey] = appended.length;
    }
  }

  return {
    stage: {
      ...current,
      singletons,
      collections,
      custom,
      completeness: incoming.completeness ?? current.completeness,
      /*
        THE FACT THIS WHOLE FUNCTION EXISTS TO EARN. After the fold this draft holds everything the
        server holds, so "delete what I do not name" can no longer name anything the designer has not
        been shown, and the very next save is entitled to carry the deletion that has been owed.
      */
      serverLoadedAt: Date.now(),
      /*
        `dirtyAt` AND `removedFrom` ARE CARRIED THROUGH UNTOUCHED, and the two halves have to stay
        together. The row loop above declines to add the server's rows back for the emptied entities,
        so the next payload does not name them and `replaceCollections` sweeps them; clearing
        `removedFrom` here instead would drop the instruction and leave those rows alive on the server
        for ever with nothing on any screen saying so. {@link unsentAfterPush} is what clears it, judged
        against the list the acknowledged payload actually carried.

        And `dirtyAt` is NOT bumped to now: the rows that arrived are the server's, not an edit the
        designer made, and marking them as one would date this browser's work by a download.
      */
      updatedAt: Date.now()
    },
    added,
    addedRows,
    addedCustom,
    sweptRows
  };
}

/** True when the fold changed nothing the designer can see. */
export function foldChangedNothing(fold: DwStageFold): boolean {
  return (
    fold.added.length === 0 &&
    Object.keys(fold.addedRows).length === 0 &&
    fold.addedCustom.length === 0 &&
    Object.keys(fold.sweptRows).length === 0
  );
}

/**
 * What to tell the designer, or null when the fold changed nothing they can see.
 *
 * TWO SENTENCES AND NOT ONE, because they point in opposite directions — the same split
 * `DwStageFold.notice` makes on the handset, and the wording is kept close to it deliberately so a
 * designer who uses both surfaces is not told the same event in two different vocabularies. What was
 * ADDED is reassurance. What will be SWEPT is the half they may want to act on before the next save
 * carries it, so it is said last and is not folded into the same list.
 *
 * Counts rather than naming everything: a sentence that lists forty keys is a sentence nobody
 * finishes. The first few keys ARE named, because "3 answers appeared" with no hint of which three is
 * not something anybody can check.
 */
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
    parts.push(`${rowCount} row${rowCount === 1 ? "" : "s"} in ${Object.keys(fold.addedRows).join(", ")}`);
  }
  if (fold.addedCustom.length > 0) {
    parts.push(
      `${fold.addedCustom.length} of this workshop's own question${fold.addedCustom.length === 1 ? "" : "s"} (${list(fold.addedCustom)})`
    );
  }

  let out = "This stage has now been read from the server. ";
  if (parts.length > 0) {
    out += `${parts.join("; ")} were already there and not in this browser, and have been added below. `;
    out += "Nothing you had typed here was changed. ";
  }
  const swept = Object.values(fold.sweptRows).reduce((sum, n) => sum + n, 0);
  if (swept > 0) {
    const them = swept === 1 ? "it" : "them";
    out += `You had deleted everything in ${Object.keys(fold.sweptRows).join(", ")} in this browser, so `;
    out += `${swept} row${swept === 1 ? "" : "s"} the server still holds ${swept === 1 ? "there has" : "there have"} `;
    out += `NOT been added back, and the next save will delete ${them} on the server — including anything `;
    out += "added there by somebody else since you deleted. Your deletion stands, which is what you asked for. ";
    // The remedy has to be one the designer can actually carry out, and "add the rows back here" is
    // not: these are rows this browser has never shown them, so they cannot retype what they have not
    // seen. What is true is that the deletion is RECORDED rather than erased, so the sentence names
    // the fact that makes it fixable by somebody instead of an action that would fail.
    out += `If you did not mean to delete ${them}, say so before this stage is submitted: the repository `;
    out += `records a deletion rather than erasing the row, so ${swept === 1 ? "it" : "they"} can be `;
    out += "brought back by whoever runs it.";
  }
  return out.trim();
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
export function localStageCompleteness(
  spec: DwStage,
  stage: DwDraftStage | undefined,
  /**
   * This stage's designer-defined fields, retired ones included — {@link customFieldsFor} resolves
   * them off the draft.
   *
   * DEFAULTED TO EMPTY, WHICH IS THE HONEST DEFAULT AND NOT A CONVENIENCE. A caller that has no
   * definition to hand scores the stage exactly as it did before this feature existed: the registry's
   * own fields and nothing more. It cannot invent a lower total, and it cannot invent a higher one.
   */
  customFields: readonly DwCustomField[] = []
): DwStageCompleteness {
  const data = stageDataOf(stage);
  return scoreStageData(spec, data.singleton, data.collections, customFields, stage?.custom ?? {});
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
  collections: Record<string, DwRow[]>,
  /**
   * The designer's own questions for this stage, and their answers. THIS IS THE ONE PLACE THEY ARE
   * SCORED — the stage bar, the workshop index, the readiness screen, the strict local pass and the
   * report's outstanding column all read this function rather than counting for themselves.
   *
   * Mirrors `stage_completeness(..., custom_fields=, custom_values=)` on the server argument for
   * argument, and Android's `computeStageCompleteness` in the same order, because a required custom
   * question that counted on one surface and not on another is a stage that reads 100% on the form and
   * 422s on submit.
   */
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

  /*
    THE DESIGNER'S OWN QUESTIONS ARE SCORED BETWEEN THE STAGE'S SINGLETON AND ITS COLLECTIONS, which is
    why this loop is split in two rather than left as one walk over `spec.entities`.

    BETWEEN, AND NOT AFTER, AND THE ORDER IS NOT COSMETIC. `missing` is printed in order and truncated
    — the stage bar prints it in full, the report's Outstanding column and the phone's report screen
    take the first three — so whatever this list puts first is what a designer and a ministry officer
    actually read. Between the stage's own fields and its repeating rows is the order the questions
    appear on the form.

    FILED UNDER THE BARE LABEL, like a singleton field and unlike a collection field, which files
    `"${entity.title}: ${label}"`. That is what makes a duplicate label a definition-time refusal
    rather than a document disagreeing with itself: two required questions filing the same string
    collapse into one row through the de-duplication below while `requiredTotal` still counts two.

    A RETIRED FIELD IS SKIPPED, exactly as `field.deprecated` is skipped above and for its reason: it
    is no longer asked, so counting it would make a stage permanently incomplete because of a question
    the designer corrected.

    SCORED KEY BY KEY, AND THE CONTAINER IS NEVER TESTED AS A WHOLE. `isFilled` returns true for any
    object with keys, so a bucket holding twenty blank answers is truthy: a stage would report itself
    complete on the strength of the bucket existing.
  */
  /*
    TWO PASSES OVER `spec.entities` RATHER THAN ONE, so that the custom questions can sit between the
    singleton and the collections whatever order the registry declares its entities in. The server's
    scorer takes `spec.singleton` first, then the custom fields, then `spec.collections` — it never
    walks the declaration list — so a single interleaved walk here could only agree with it by accident
    on a stage whose singleton happens to be declared first. Every singleton is scored, not just the
    first: this store keeps them keyed per entity precisely because the registry may one day declare a
    second one, and the flattening on the wire would silently keep only the last.
  */
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
    // De-duplicated with order preserved, the same way `dict.fromkeys` does it server-side.
    missing: [...new Set(missing)]
  };
}

/**
 * Every stage of a draft scored locally, keyed the way `DwDetail.completeness` is.
 *
 * THE SIGNATURE DELIBERATELY DID NOT CHANGE when the scorer learned about custom questions, and that
 * is what taught all of its readers at once. The definition lives on the DRAFT — which this function
 * already had — so the workshop index, the readiness screen and its `stageAddresses` walk all count
 * the designer's own required questions without a line changing at any of them. Threading a fourth
 * argument through instead would have meant three call sites each deciding for themselves whether to
 * pass it, and the one that forgot would be a second arithmetic on the same screen.
 */
export function localCompleteness(registry: DwRegistry, draft: DwDraft): Record<string, DwStageCompleteness> {
  const out: Record<string, DwStageCompleteness> = {};
  for (const spec of registry.stages) {
    out[spec.key] = localStageCompleteness(spec, draft.stages[spec.key], customFieldsFor(draft, spec.key));
  }
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
          // Cleared with the rest, and for the same reason: this branch IS the server's copy of the
          // stage, and a `_custom` row the repository no longer has must not read as downloaded and
          // current — the next push would put it back.
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
        collections: withClientKeys(incoming.collections ?? {}),
        removedFrom: [],
        // The third sibling key of the stage bucket, taken as the server sent it. `?? {}` because
        // `_stages_payload` omits nothing but a server predating the feature sends no such key, and
        // reading that as "no custom answers" is correct — there cannot be any.
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
      stages,
      registryVersion: detail.schemaVersion || draft.registryVersion,
      /*
        `customSchemaVersion` IS DELIBERATELY NOT WRITTEN HERE, EVEN THOUGH THIS PAYLOAD CARRIES ONE.

        The two digests look symmetrical and are not. `registryVersion` names a document in the registry
        OBJECT STORE, which holds three of them, so adopting the server's value is how a later read finds
        the right one. `customSchemaVersion` names the ONE definition stored on this same record, so it
        must always describe THAT — and {@link cacheCustomDefinition} therefore writes the two together
        and is the only thing that writes either.

        Adopting the server's digest here instead was the first version and it inverted the field's whole
        purpose: `GET /{id}` answers with the digest of the definition the SERVER holds, so a designer who
        edited the definition in another tab left this record holding Monday's sections stamped with
        Tuesday's digest. Every staleness comparison then read "current" and the form went on offering a
        question that had been retired, which is precisely the failure the digest exists to catch. The
        server's value is still used — it is read straight off the payload by the screen that needs it, and
        compared against what this record holds — but it is never stored as though it described this copy.
      */
      lastSyncedAt: Date.now()
    };
  });
}

/**
 * Keep a workshop's custom definition on its draft record, with the digest it arrived under.
 *
 * NEVER FATAL, exactly like {@link cacheRegistry}: a definition that could not be stored costs an
 * offline form, not a save. The one thing it must not do is fail a designer's edit — so a rejected
 * write is swallowed and the in-memory copy still serves this tab.
 *
 * IT WRITES THE DIGEST FROM THE DEFINITION ITSELF, not from a separate argument, so the two cannot
 * come apart. A draft holding Monday's sections under Tuesday's digest would compare as current and
 * offer a question that no longer exists.
 */
export async function cacheCustomDefinition(
  id: string,
  definition: DwCustomDefinition
): Promise<DwDraft | null> {
  return mutate(id, (draft) => {
    // An equal digest IS the same document — `customSchemaVersion` is a content digest, exactly as
    // `registry_version` is — so re-serialising the sections into IndexedDB on every stage open is
    // work for a file that has not changed.
    if (draft.customDefinition && draft.customSchemaVersion === definition.customSchemaVersion) return draft;
    return { ...draft, customDefinition: definition, customSchemaVersion: definition.customSchemaVersion };
  });
}

/**
 * Where a definition came from, and the third value is the one that matters.
 *
 * "network" and "cache" are the registry's two rungs. **"unknown" is a third state this feature needs
 * and the registry does not**, and it is not a failure code: the registry has a bundled floor on the
 * handset and a cached one here, so "there is no field list at all" is close to unreachable — whereas
 * a per-workshop definition has no floor by construction. An APK cannot bundle a workshop that did not
 * exist when it was built, and nor can this browser.
 *
 * So "I hold nothing" and "there is nothing to hold" genuinely look alike from inside a tab with no
 * signal, and collapsing them is the failure Android's `DwQuestionnaireCopy` needed three states to
 * avoid: warning on both puts an apology on the majority of workshops, which is how a designer learns
 * to stop reading warnings. "unknown" says only that this browser does not know, and the screen says
 * that in a sentence instead of drawing a form that asks nothing.
 */
export type CustomDefinitionSource = "network" | "cache" | "unknown";

/**
 * This workshop's definition, offline first, in the shape {@link loadRegistry} established.
 *
 * Ask the network and keep what comes back; if the network cannot answer, fall back to the copy on the
 * draft record and SAY SO, because a form drawn from a definition that may be a week old is worth
 * having and a designer is entitled to know which one they are looking at. When neither answers, the
 * honest report is "unknown" — never an empty definition, which would assert that this workshop asks
 * nothing of its own.
 *
 * THE CACHE WRITE IS FIRED AND NOT AWAITED, and its failure is swallowed inside
 * {@link cacheCustomDefinition}: a definition that could not be stored costs an offline form, not a
 * save, and a designer must never lose an answer to a housekeeping write.
 */
export async function loadCustomDefinition(
  draft: DwDraft
): Promise<{ definition: DwCustomDefinition | null; source: CustomDefinitionSource }> {
  const held = draft.customDefinition ?? null;
  if (!draft.remoteId) {
    /*
      A WORKSHOP THE SERVER HAS NEVER HEARD OF CANNOT HAVE A DEFINITION, and this is the one place where
      asserting emptiness is a FACT rather than a default. The authoring route is a server write against a
      workshop the server knows; there is no other way a definition comes into existence, so a workshop
      that has never been created up there has none, necessarily, and this browser can establish that with
      certainty rather than by assumption.

      REPORTED AS "cache" AND NOT AS "unknown", and the difference is a sentence on twenty-two stages. A
      designer who created this workshop in a courtyard on Monday would otherwise be told, on every stage
      for the whole fortnight, that this browser has not read the workshop's own questions and should open
      the stage with a connection — an apology for a state that is not a gap, on exactly the workflow this
      whole feature was written for. That is how a designer learns to stop reading warnings.
    */
    return { definition: held ?? { customSchemaVersion: "", sections: [], fetchedAt: "" }, source: "cache" };
  }
  try {
    const definition = await fetchCustomDefinition(draft.remoteId);
    void cacheCustomDefinition(draft.localId, definition);
    return { definition, source: "network" };
  } catch {
    if (!held) return { definition: null, source: "unknown" };
    // Adopted into the in-memory cache so every other reader in this tab sees the SAME object and does
    // not re-issue the request that just failed — and so the identity contract that stops every
    // `useMemo` in the feature rebuilding still holds when the answer came off disk.
    return { definition: adoptCustomDefinition(draft.remoteId, held), source: "cache" };
  }
}

/**
 * The custom questions of one stage, resolved from what this device holds.
 *
 * THE MIRROR OF `stageSpecFor`, ONE DOOR ALONG, and the same property is what it buys: a stage is
 * scored and sent under the definition this device was holding when the answers were captured, not
 * under one that may have moved since. The digest beside it is what lets a reader notice the
 * difference rather than average the two.
 */
export function customFieldsFor(draft: DwDraft | null | undefined, stageKey: string): DwCustomField[] {
  return customFieldsForStage(draft?.customDefinition ?? null, stageKey);
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
      /*
        FOLDED, NOT ADOPTED — AND NOT REFUSED, WHICH IS WHAT IT USED TO BE.

        This branch returned the draft with only `completeness` updated and, deliberately, without
        marking the stage as loaded. That was right about the values (a download must not overwrite
        work in progress) and wrong about the consequence: `serverLoadedAt` is the only authority this
        browser has to say "these are now exactly the rows", a stage holding an unsent deletion is
        always dirty, and so a withheld deletion could never earn the authority that would let it
        travel. The row the designer deleted stayed alive in the repository and printed in the .docx,
        for ever, while `pendingWork` and the banner went on correctly reporting work that was owed.

        {@link foldStageInto} adds only what this browser has never seen, keeps every local value and
        every local row exactly as it is, honours `removedFrom` by declining to add those rows back,
        and stamps `serverLoadedAt`. The next save is then entitled to carry the deletion. It is the
        same function `dwFoldServerStage` is on the handset, rule for rule.
      */
      const folded = foldStageInto(spec, current, incoming);
      const note = foldNotice(folded);
      return {
        ...draft,
        stages: {
          ...draft.stages,
          [spec.key]: {
            ...folded.stage,
            // Kept on the record rather than raised as a toast, because the read that produced it may
            // have happened while the designer was on another screen: a sentence about six rows that
            // appeared is worth nothing if it is shown to an empty tab. Null when the fold changed
            // nothing visible, so an unchanged re-read does not re-announce itself.
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
          collections: withClientKeys(incoming.collections ?? {}),
          removedFrom: [],
          custom: incoming.custom ?? {},
          updatedAt: Date.now(),
          dirtyAt: null,
          serverLoadedAt: Date.now(),
          completeness: incoming.completeness ?? null,
          failure: null
        }
      }
      // No `customSchemaVersion` here either — see {@link adoptServerDetail}. `GET /stages/{key}` does
      // carry the server's digest, and the stage form reads it STRAIGHT OFF THAT PAYLOAD to compare
      // against what this record holds. What it must not do is overwrite the digest that describes the
      // sections stored here, which is the only thing that can tell the two apart.
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
          // Named explicitly even though both are optional, because this is a hand-enumerated literal
          // and this file already records what those cost: the offline create path re-listed nine of a
          // header's ten fields and the tenth — the link to the workshop record — was silently never
          // copied, typechecked and all. The workshop LIST is not told about definitions, so "not read"
          // is the truth here rather than a placeholder.
          customSchemaVersion: "",
          customDefinition: null,
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
): {
  entries: DwSaveEntry[];
  rowKeys: Array<{ entityKey: string; rowIndex: number } | null>;
  /**
   * Whether any entry above carried `merge: true`.
   *
   * Returned rather than re-derived by the caller, because the caller would have to re-implement the
   * never-read rule to work it out and the two copies would drift. It is read by exactly one thing —
   * `markStagePushed`'s `mergedEntries` — and what it is for is written up there: a merge push leaves
   * the server holding a SUPERSET of this browser's copy, so acknowledging it as "we have now read
   * the server" is what makes the NEXT save delete everything this browser never saw.
   */
  merged: boolean;
} {
  const entries: DwSaveEntry[] = [];
  const rowKeys: Array<{ entityKey: string; rowIndex: number } | null> = [];
  const data = stageDataOf(stage);
  /*
    ONE never-read TEST FOR ALL THREE ARMS, DECIDED ONCE HERE.

    It used to be computed twice — inside the singleton branch and again above `_custom` — and the
    collection loop sitting between them computed it not at all, which is exactly how that loop came
    to be the only arm sending a wholesale replace. A stage is read or unread as a WHOLE (it is one
    `serverLoadedAt` on one `DwDraftStage`), so a per-arm re-derivation was never expressing anything
    the stage-level fact did not already say, and having three arms read one const is what stops a
    fourth from being added without it.
  */
  const neverRead = (stage?.serverLoadedAt ?? null) === null;

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
      const answered = Object.values(values).some((value) => isFilled(value));
      if (!neverRead || answered) {
        /*
          `merge` IS OMITTED WHEN FALSE, AND THAT IS A COMPATIBILITY RULE RATHER THAN A TIDINESS ONE.

          `APIModel` is `extra="forbid"`, so a server that predates this field answers 422 —
          "merge: Extra inputs are not permitted" — for every entry that carries it. The clients and
          the server are deployed on different days by different people; an Android handset in a
          village updates when it next sees wifi and the API updates when somebody deploys it, so
          "the client is newer than the server" is an ordinary state here and not a mistake.

          Sending it only when it is TRUE shrinks the blast radius of that skew from EVERY stage save
          to just the never-downloaded ones. It does not eliminate it, and what is left is handled
          rather than merely noted: against an old server those saves are refused, `isSchemaRefusal`
          recognises the refusal, the banner says the two are out of step instead of blaming an
          answer nobody got wrong, and `blocksRetry` re-attempts the stage on the next app run so it
          goes up by itself once either side has been updated.
        */
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
        // Derived from the ARRAY ORDER at send time and never from a stored `_ordinal`: a row
        // carrying its old ordinal after a reorder is sorted straight back to where it came from the
        // next time the stage is loaded, and the reorder looks like it did not take.
        ordinal: rowIndex,
        data: entryDataOf(row),
        /*
          A COLLECTION ROW ON A NEVER-READ STAGE IS A MERGE FOR THE SAME REASON A SINGLETON IS, AND
          THIS ARM WAS THE ONE THAT DID NOT SAY SO.

          The claim this loop was written under — that `merge` is a "singleton primitive" because a
          collection row is addressed by `_entryId`/`_clientKey` and swept by `replaceCollections`,
          so "keep the keys I did not send" has no meaning for one — confuses WHICH ROW is being
          written with WHAT IS WRITTEN INTO IT. Addressing decides which row `save_stage` updates;
          it says nothing about the row's `data`, and that `data` is replaced WHOLESALE (see the
          `updates.append` in `save_stage`, which writes `_json(item.data)` over the column). The
          sweep is a different mechanism again: it soft-deletes rows the payload did not name, and
          is armed only by `replaceCollections`/`emptiedEntities`. Neither one preserves a key.

          The server has never had the rule this loop assumed. `if entry.merge and previous:` in
          `save_stage` is NOT gated on cardinality, and `previous` is filled for ANY row it matched
          — by `_entryId` or by `_clientKey`, which is exactly how a collection row is matched. The
          request schema agrees in words: `StageEntryIn.merge` is documented as keys "already stored
          under this ROW", and `DwSaveEntry.merge` here says "the server's copy of the ROW".

          AND IT IS REACHABLE, not theoretical. `markStagePushed` deliberately does NOT stamp
          `serverLoadedAt` after a merge push, so a browser stays never-read across many saves by
          design — it goes on holding rows it created offline, each carrying the `_clientKey` the
          server has since matched and stored. Reproduced against the live API and Postgres before
          this line existed: the office wrote six fields into one `tool` row, a never-read browser
          holding only `name` in that row sent `{"name":"Pit loom","_clientKey":…}`, the server
          answered `HTTP 200 saved=1 updated=1 removed=0 errors={}`, and the row in Postgres became
          `{"name": "Pit loom"}` — five fields gone in place, with 0 `RecordRevision` rows to
          recover them from. The same walk with this flag preserves all six.

          `merge` OMITTED RATHER THAN SENT AS `false`, which is the singleton arm's compatibility
          argument verbatim: `APIModel` is `extra="forbid"`, so an API that predates the field
          answers 422 to every entry carrying it, and sending it only on the never-downloaded saves
          keeps that skew off every ordinary save. The spread is what expresses the absence — a
          plain `merge: neverRead` would put `false` on the wire on every read stage's every row.
        */
        ...(neverRead ? { merge: true } : {})
      });
      rowKeys.push({ entityKey: entity.key, rowIndex });
    });
  }

  /*
    THE DESIGNER'S OWN ANSWERS, IN A RESERVED ENTRY OF THEIR OWN, AFTER THE REGISTRY'S ENTITIES.

    `_custom` is not a registry entity and never will be — it is a `DwStageEntry` row of its own, one
    per (workshop, stage) — so it cannot come out of the loop above, which walks `spec.entities`. That
    is also what makes it unreachable by the collection sweep: `emptiedEntities` is intersected with
    the registry's own collection keys server-side, and `_custom` is not one of them.

    THE OMISSION RULE, WHICH IS THE WHOLE OF THE SAFETY. `plan_custom_write` treats "no entry at all"
    and "an entry carrying `{}`" as two different instructions: the first writes NO ROW, and the second
    is a designer clearing every answer and IS written. So a browser holding nothing must send nothing.
    A `{ entityKey: "_custom", data: {} }` from a browser that simply never fetched the definition
    would read on the server as "the designer cleared every custom answer" — and since a stage save
    replaces the row wholesale and writes no `RecordRevision`, the office's answers would be gone in
    place.

    `merge: true` ON THE NEVER-READ BRANCH ONLY, and `merge` OMITTED rather than sent as `false`
    otherwise. Both halves are the singleton arm's argument above, verbatim, and both are load-bearing:
    the flag is what keeps the promise the amber banner makes on a stage this browser has not
    downloaded ("nothing you leave blank will overwrite an answer recorded elsewhere"), and its
    ABSENCE is what stops an API that predates the field 422ing every save instead of only the
    never-downloaded ones. `e2e/stage-entry-merge-unit.spec.ts` pins "no entry ever carries
    merge:false" over every shape at once, and this arm must not be the one that breaks it.

    `rowKeys.push(null)` BESIDE IT, OR EVERY LATER ERROR LANDS ON THE WRONG ROW. `save_stage` keys its
    per-field errors by an entry's INDEX IN THE ARRAY THAT WAS SENT, and the stage page decodes them
    through this array — so an entry pushed without its `rowKeys` companion shifts every collection
    row after it by one and puts a message on a box that is fine. A singleton-shaped entry files its
    errors under the bare key, which for this one is the literal `_custom`.
  */
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

  // Read off the entries themselves rather than tracked in a flag beside the loops: a flag set at one
  // of the THREE `merge: true` sites (singleton, collection row, `_custom`) and forgotten at another
  // would be silently wrong in exactly the direction that loses data, and this cannot be. The count
  // went from two to three when the collection loop was given the flag it had always been missing —
  // and because this line derives the answer, that arm needed no change here to be accounted for.
  return { entries, rowKeys, merged: entries.some((entry) => entry.merge === true) };
}

/**
 * Whether this save may tell the server "these are now exactly the rows", and which collections it
 * may say it emptied.
 *
 * ── THE FOURTH DOOR, AND IT DELETED WHOLE ROWS RATHER THAN KEYS ──────────────────────────────────
 *
 * {@link buildStageEntries} asks the never-read question three times — singleton, collection row,
 * `_custom` — and every one of those arms protects the CONTENTS of a row the server keeps. Nothing
 * asked it about WHICH ROWS SURVIVE, which is a different mechanism with a different switch: the
 * sweep. `save_stage` soft-deletes every live row of every entity in
 * `(touched_entities | emptiedEntities) & collection_keys` that the payload did not name, and
 * `touched_entities` is filled from the entries actually sent — so the sweep reaches entities the
 * designer never touched, merely because this browser happened to hold one row in them.
 *
 * Both send sites armed it with `stage.removedFrom.length > 0` and nothing else, and `removedFrom`
 * grows on ANY row deletion (`patchCollection` compares row counts). `merge: true` is no defence:
 * it preserves keys inside a row the server matched, and says nothing about a row the payload never
 * named. REPRODUCED against the running API and Postgres, one row deleted on a never-read browser:
 *
 *   PUT … {entries: [1 processStep row, 1 tool row, both merge:true],
 *          replaceCollections: true, emptiedEntities: ["tool"]}
 *   -> HTTP 200 saved=2 created=2 updated=0 removed=5 errors={}
 *
 * Five rows the office had written — three `tool` and, because the payload named one row in it, two
 * `processStep` the designer had not deleted anything from — soft-deleted in one 200, with the page
 * reporting "Stage saved — 2 added, 0 updated, 5 removed" and no row on screen to attach that 5 to.
 *
 * ── SO THE SWEEP IS EARNED, EXACTLY AS IT IS ON THE HANDSET ──────────────────────────────────────
 *
 * `buildStageBody` on Android sends `replaceCollections = authoritative` and
 * `emptiedEntities = if (authoritative) emptied else emptyList()`, where authority is
 * `StageDraft.stageSeen` — a stage this device has READ. {@link DwDraftStage.serverLoadedAt} is this
 * store's word for the same fact, and it is the only thing either client has that can tell "the
 * designer deleted this" from "this browser never downloaded it".
 *
 * `emptiedEntities` IS ALSO INTERSECTED WITH THE STAGE'S OWN COLLECTIONS, which the handset does one
 * line further down for the same reason: a key left behind by a registry that has since moved on is
 * not a deletion instruction for anything, and `StageSaveIn` 422s the whole save for a reserved
 * `_`-prefixed key — which would refuse a stage's every answer over a bookkeeping artefact.
 *
 * ── WHAT WITHHOLDING IT COSTS, WHICH IS REAL AND MUST NOT BE SILENT ──────────────────────────────
 *
 * A deletion that cannot be stated does not travel: the row stays alive in the repository and prints
 * in the .docx. That is the trade Android makes too, and it is the better half of it — the deletion
 * is recorded on this device, is carried forward save after save, and is undone by nobody; whereas
 * the rows the sweep took were other people's work, gone in place, with nothing on any screen to say
 * so. What it must never be is quiet: the caller is handed {@link DwStageSweep.withheld} to say it in
 * words, `removedFrom` is kept by {@link unsentAfterPush} (which is judged against the list the
 * payload ACTUALLY carried, so a withheld deletion is never marked as sent), and `pendingWork` and
 * `DraftSyncBanner` both count a stage holding `removedFrom` as work that has not landed.
 */
export function stageSweep(
  spec: DwStage | null | undefined,
  stage: DwDraftStage | undefined
): DwStageSweep {
  const removed = stage?.removedFrom ?? [];
  const collectionKeys = new Set(
    (spec?.entities ?? []).filter((entity) => entity.cardinality === "COLLECTION").map((entity) => entity.key)
  );
  // Intersected whether or not the sweep is claimed, so `withheld` names only entities this stage
  // could actually have swept — a designer must not be told a deletion is being held back for an
  // entity no save of this stage would ever have carried.
  const owed = spec ? removed.filter((key) => collectionKeys.has(key)) : removed.filter((key) => !key.startsWith("_"));
  // The same one question `buildStageEntries` decides once for all three of its arms, asked here for
  // the mechanism those arms do not cover. A stage is read or unread as a WHOLE.
  const neverRead = (stage?.serverLoadedAt ?? null) === null;
  if (neverRead) return { replaceCollections: false, emptiedEntities: [], withheld: owed };
  return { replaceCollections: owed.length > 0, emptiedEntities: owed, withheld: [] };
}

/** What {@link stageSweep} decided, and what that decision is holding back. */
export type DwStageSweep = {
  /** `true` means "these are now exactly the rows", and the server may delete the rest. */
  replaceCollections: boolean;
  /** Collections this save states it has emptied. Read by the server only under the flag above. */
  emptiedEntities: string[];
  /**
   * Collections holding a deletion this save is NOT entitled to state, in the designer's terms.
   *
   * Non-empty only on a stage this browser has never read. It is the sentence the stage page owes the
   * designer — a deletion nobody can see waiting is a deletion the designer believes has happened.
   */
  withheld: string[];
};

/** One collection row's address on screen, as {@link buildStageEntries} recorded it. */
export type DwRowKey = { entityKey: string; rowIndex: number } | null;

/**
 * `save_stage`'s error map, re-addressed from "the array I sent" to "the boxes on screen".
 *
 * THE DECODE HALF OF THE CONTRACT {@link buildStageEntries} ENCODES, and it lives beside it for that
 * reason: the server keys its per-field errors by an entry's INDEX IN THE ARRAY THAT WAS SENT, so the
 * only thing that can read them is the `rowKeys` companion built by the same pass over the same stage.
 * Kept in the component that rendered them, the two halves of one index contract sat 900 lines and one
 * module apart, and the decode could not be tested without a browser and a server — which is how it
 * carried a silent drop for as long as it did.
 *
 * `unplaced` IS THE HONESTY VALVE, and it is `DwStageRefusalReport.unplaced` on the handset: a scope
 * this array cannot account for is REPORTED rather than dropped, because the alternative is a refusal
 * that exists on the server and nowhere else. Naming the wrong row was never the alternative — a
 * message on a box that is fine sends a designer to correct an answer nobody objected to.
 */
export function placeStageErrors(
  errors: Record<string, Record<string, string>> | null | undefined,
  rowKeys: DwRowKey[]
): { decoded: Record<string, Record<string, string>>; unplaced: string[] } {
  const decoded: Record<string, Record<string, string>> = {};
  const unplaced: string[] = [];
  for (const [key, fields] of Object.entries(errors ?? {})) {
    const named = Object.entries(fields ?? {});
    if (!named.length) {
      // A scope refused with no field map at all: there is no box to mark, and the server still
      // refused something. Same sentence as the handset's, for the same reason.
      unplaced.push(`${key}: refused, with no reason given`);
      continue;
    }
    const match = /^(.+)\[(\d+)\]$/.exec(key);
    if (!match) {
      // A bare key: the stage's singleton, or the reserved `_custom` container. Both are drawn from
      // `errors[key]` directly and need no re-addressing.
      decoded[key] = fields;
      continue;
    }
    const entityKey = match[1];
    const origin = rowKeys[Number(match[2])];
    /*
      THE ENTITY IS CHECKED, NOT JUST THE PRESENCE OF AN ENTRY.

      The index is a position in an array holding EVERY entity's entries, so index 4 can perfectly well
      be occupied by a different entity than the scope key names. Trusting presence alone would draw a
      refusal for `tool[4]` onto a `rawMaterial` row — a red mark on an answer nobody objected to, which
      is worse than admitting the refusal cannot be placed. A singleton's `rowKeys` slot is `null`, so
      this also rejects a bracketed scope that resolves to a singleton entry.
    */
    if (!origin || origin.entityKey !== entityKey) {
      for (const [field, message] of named) unplaced.push(`${key}.${field}: ${message}`);
      continue;
    }
    decoded[`${origin.entityKey}[${origin.rowIndex}]`] = fields;
  }
  return { decoded, unplaced };
}

/**
 * Refusals that no box on the stage form will draw, as `scope.field: message` lines.
 *
 * THE SECOND HALF OF {@link placeStageErrors}'S HONESTY, and the half that catches the reachable case.
 * `placeStageErrors` decides placement against the array that was SENT, at the moment of the save. This
 * decides it against the rows that are on screen NOW — and those are two different questions, because
 * the decoded errors are state that survives until the next save while the rows underneath them are
 * edited freely in between:
 *
 *   • delete the row a refusal was drawn on, and `CollectionTable` — which looks its errors up BY ROW —
 *     simply never reads that entry again. The message vanishes and the server still holds the refusal;
 *   • delete a row ABOVE it, and every index below shifts by one, so the surviving message would be
 *     drawn against a DIFFERENT row's boxes: a red mark on an answer nobody objected to;
 *   • a scope naming an entity this stage does not declare, or a bare key that is neither the singleton
 *     nor `_custom`, has no box at all.
 *
 * All four are the same failure and get the same answer: if it cannot be placed against the current
 * rows, it is said out loud instead. Pure, and exported, because the alternative is a `useMemo` in a
 * component that no test can reach without a browser and a refused save — which is precisely the
 * condition under which the original silent drop survived review.
 *
 * The predicate is the COMPLEMENT of the one `collectionErrors` marks with, so every refusal is either
 * on a box or in the banner and a change to one cannot open a gap in the other.
 */
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
      : // `_custom` is always drawn — the block is rendered in one of its three positions on every
        // stage, including a stage that declares no entity at all.
        key === CUSTOM_ENTITY_KEY || spec.get(key) === "SINGLETON";
    if (drawn) continue;
    for (const [field, message] of Object.entries(fields ?? {})) lines.push(`${key}.${field}: ${message}`);
  }
  return lines;
}

/**
 * How many ANSWERS one save came back refusing — not how many scopes they were grouped under.
 *
 * `save_stage` keys its errors by scope (`entity`, `entity[3]`, `_custom`) and the value under each is a
 * map of FIELD to message, so counting keys counted the boxes' containers: three refused answers in one
 * row reported "1 answer", and the designer who opened the stage found three marked boxes. The number
 * disagreed with the screen it was sending them to, and with the handset, which has always summed the
 * field maps (`result.errors.values.sumOf { (entry as? JsonObject)?.size ?: 1 }`).
 *
 * `|| 1` is that `?: 1`: a scope refused with an empty field map contributes ONE rather than nothing, so
 * a response shaped that way can never sum to zero and be recorded as a clean save.
 */
export function countRefusedAnswers(errors: Record<string, Record<string, string>> | null | undefined): number {
  return Object.values(errors ?? {}).reduce((total, fields) => total + (Object.keys(fields ?? {}).length || 1), 0);
}

/**
 * The refusal count to SHOW, given what the server sent and what this build can count for itself.
 *
 * The server computes `refusedAnswers` so that a laptop and a handset cannot print different numbers
 * off one response body — see {@link DwSaveResult.refusedAnswers}. Its reading is also the safer one:
 * `countRefusedAnswers` walks the map with `Object.keys`, which on a scope whose value arrived as a
 * bare string returns that string's INDICES, so the sentence would carry a character count. The
 * server's `refused_answer_count` guards that case explicitly and returns 1.
 *
 * SO WHY NOT SIMPLY READ THE FIELD. Because the two disagree in the other direction as well, and
 * that direction loses data rather than exaggerating it: for a scope refused with an EMPTY field map
 * the server sums 0, while `countRefusedAnswers`' `|| 1` deliberately contributes 1 so a response
 * shaped that way cannot total zero and be filed as a clean save. Reading the field alone would
 * report "nothing refused" about a response that plainly refused something — which is the one
 * direction this repository has already decided must never be wrong (see the header of
 * `e2e/stage-refusal-placement-unit.spec.ts`: the form and the pass were both wrong by
 * under-reporting, and that is what made it a defect rather than a discrepancy).
 *
 * Hence: take the server's number, EXCEPT where it would say nothing was refused about a response
 * this build can see refused something. `undefined` is a deployment older than the field.
 */
export function refusedAnswersToShow(
  serverCount: number | undefined,
  errors: Record<string, Record<string, string>> | null | undefined
): number {
  const local = countRefusedAnswers(errors);
  if (typeof serverCount !== "number") return local;
  return serverCount > 0 || local === 0 ? serverCount : local;
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

/**
 * @param skewRun pass {@link APP_RUN_ID} — and ONLY that — when the refusal is one no edit can clear
 *   and only an update to the client or the server will. See {@link DwDraftFailure.skewRun}.
 */
function failure(
  message: string,
  permanent: boolean,
  attempts: number,
  skewRun: string | null = null
): DwDraftFailure {
  return { message, permanent, skewRun, at: Date.now(), attempts };
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
    // Waiting on the designer, not on the network — UNLESS it is waiting on an update instead, in
    // which case this run is the one that gets to find out whether the update has landed.
    if (blocksRetry(draft.failure)) continue;

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
            // The CREATE carried the whole header, so every recorded edit is discharged. Cleared with
            // the timestamp for the reason the PATCH arm gives: a list left behind would make the next
            // unrelated edit send fields nobody touched.
            headerDirtyKeys: [],
            header: { ...current.header, status: String(created.status) },
            failure: null
          }))) ?? draft;
        result.workshopsCreated += 1;
      } else if (item.needsHeader) {
        /*
          ONLY THE FIELDS SOMEBODY TYPED INTO — see {@link DwDraft.headerDirtyKeys}.

          This used to send the whole local header on every PATCH. `ensureDraft` seeds a header of
          empty strings and nulls for a workshop this browser has merely OPENED, so the first caller
          of `patchDraftHeader` on such a draft would have nulled the office's `notes` and overwritten
          its title with `""` — under a 200, with nothing on any screen. What kept it harmless was
          having no caller, which is not the same as being shut.

          THE FALLBACK IS TODAY'S BEHAVIOUR, DELIBERATELY. A draft written by a build before this
          field existed carries no list, and sending the whole header is what such a draft has always
          done; changing it to "send nothing" would silently drop an edit somebody made. It is
          unreachable in practice — `patchDraftHeader` is the only thing that can arm this branch, and
          it always records its keys — and it is left rather than asserted because a sync pass is the
          wrong place to throw.
        */
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
            // Cleared WITH the timestamp, never apart from it: a list left behind would make the next
            // unrelated edit send fields nobody touched this time.
            headerDirtyKeys: []
          }))) ?? draft;
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

        THE STAGES ARE ASKED FIRST AND THE MEDIA ONLY IF THEY SAY SO, which is not tidiness: this is
        the third `draftMedia` call of a single pass over one workshop (`pendingWork` and the upload
        loop above are the others) and every one of them deserialises the blob of every unconfirmed
        photograph the draft holds. A draft with no `dwlocal:` reference left anywhere — every draft,
        nearly always — cannot possibly be stranded, and the scan of `stages` that proves it is
        memory the pass has already loaded. Paying a fortnight of photographs for that answer, on
        every pass, on the laptop least able to afford it, is what the index at `MEDIA_BY_DRAFT` was
        created to avoid.
      */
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
              (stage.failure?.attempts ?? 0) + 1,
              // A SKEW LIKE ANY OTHER, and the one whose own sentence gave the instruction away: it
              // tells the designer to reload, and until this argument existed a reload changed
              // nothing, because the pass stepped over the stage for ever on the strength of
              // `permanent`. The app must be able to keep the promise it printed.
              APP_RUN_ID
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

        /*
          ARMED BY A DELETION *AND* BY THE ONE THING THAT MAKES A DELETION SAYABLE, WHICH IS A READ.

          This was `replaceCollections: stage.removedFrom.length > 0` with `emptiedEntities:
          stage.removedFrom` beside it, and the comment here said the flag "is dangerous otherwise: it
          would sweep any row a second editor added" — which is precisely what it did, on every
          never-downloaded stage, measured at `removed=5` for one deleted row. {@link stageSweep}
          carries the whole argument and is shared with the stage page's own save so the two cannot
          drift; a payload built by one and acknowledged by the other is what put this defect's
          neighbour on the wire.
        */
        const sweep = stageSweep(spec, stage);
        /*
          NOTHING TO SAY AND NOTHING TO SEND, RATHER THAN AN EMPTY PUT EVERY PASS.

          `stageHoldsSomething` above counts a pending removal as content, which is right — it must
          keep its dirty flag. But when the sweep is withheld, an entry-less payload carries NO part of
          that removal, so sending it would be a round trip that changes nothing, on every pass, for as
          long as the stage stays unread. Counted as pending — which it is — and left exactly as it is
          on disk, so the deletion is still owed and still listed.
        */
        if (!entries.length && !sweep.emptiedEntities.length) {
          result.pending += 1;
          continue;
        }

        let saved;
        try {
          saved = await saveDesignWorkshopStage(remoteId, stageKey, {
            entries,
            replaceCollections: sweep.replaceCollections,
            // WITHOUT THIS, DELETING THE LAST ROW OF A COLLECTION NEVER REACHES THE SERVER. The
            // sweep only considers entities the payload named, and an emptied collection sends no
            // entries at all, so it names itself nowhere. `removedFrom` is exactly that list — minus
            // what this browser has not earned the right to say.
            emptiedEntities: sweep.emptiedEntities,
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
          /*
            A SCHEMA REFUSAL IS NOT THE DESIGNER'S FAULT, AND MUST NOT BE REPORTED AS ONE.

            One sentence used to cover every refusal the server answered with, and it asserted a
            cause — "the answer that caused it". That is right for a field the validator rejected
            and wrong for a payload the server could not parse at all, where the client is speaking
            a dialect the server does not know. `APIModel` is `extra="forbid"`, so any key a newer
            client adds produces exactly this shape: it happened for real on 2026-08-08, when a
            client sent the then-unknown `merge` and every stage came back
            "merge: Extra inputs are not permitted".

            The old sentence then sent the designer to do something impossible — open the stage,
            read every field, find nothing wrong, press Try again, get the same sentence — for ever.
            Telling somebody to correct an answer when nothing they can reach is wrong is how an app
            teaches people that its warnings are noise.

            AND THE SENTENCE WAS ONLY HALF OF IT. Correcting the words left the RETRY POLICY behind
            them saying the same false thing: `permanent` meant the pass stepped over this stage for
            ever, so the app could not recover from a skew even after an update had closed it. That
            is the state this defect was reported in — the API had been taught `merge` and answered
            200 to the very PUT the banner was still refusing to make. `skewRun` is what ends it: the
            refusal is recorded and shown exactly as before, and the NEXT app run tries again on its
            own. See `blocksRetry` for why the trigger is an app run and not a build number.
          */
          // Read before the branch: `isSchemaRefusal` is a type guard, so testing it narrows `error`
          // to `never` in the arm where it is false — this block has already established that the
          // error IS an `ApiError`, and there is nothing left to subtract.
          const said = error.message;
          const schemaRefusal = isSchemaRefusal(error);
          await noteStageFailure(
            draft.localId,
            stageKey,
            failure(
              schemaRefusal
                ? `The repository could not read what this copy of the app sent for stage “${stageKey}”: ${said} ` +
                  "Nothing you typed is wrong and nothing has been thrown away — this app and the repository are out of " +
                  "step, and no edit to the stage will clear it. Your work is safe on this device, and it will be sent by " +
                  "itself the next time you open the app after either has been updated; you do not have to do anything. " +
                  "Tell whoever runs the repository if it keeps happening."
                : `The repository refused stage “${stageKey}”: ${said} It is still on this device and nothing has been ` +
                  "thrown away, but it will keep being refused until the answer that caused it is corrected — this is not a " +
                  "connection problem. Open the stage, then use Try again.",
              true,
              (stage.failure?.attempts ?? 0) + 1,
              schemaRefusal ? APP_RUN_ID : null
            )
          );
          result.failed += 1;
          continue;
        }

        // ANSWERS, NOT SCOPES — see {@link countRefusedAnswers} for what this counted before and why
        // the number a designer is shown has to be the number of marked boxes they will find.
        //
        // The server's own count is preferred so the two surfaces cannot disagree, with this build's
        // count as both the fallback for an older deployment and the floor that stops a refused
        // response reading as a clean save. {@link refusedAnswersToShow} argues both halves.
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
                // `stage` is the snapshot this PUT was built from, so it is exactly what the server
                // has now accepted; `target` is whatever the designer has since typed or deleted.
                // Answering both fields against the payload rather than clearing them is the whole
                // guard — see {@link unsentAfterPush}.
                //
                // `sweep.emptiedEntities` AND NOT `stage.removedFrom`, which is the same distinction
                // one door along: what was SENT, not what was HELD. A deletion {@link stageSweep}
                // withheld was never named in this payload, so acknowledging it here would mark it as
                // sent, clear it, and leave the row alive in the repository for ever with nothing on
                // any screen saying so — the exact failure `unsentAfterPush` was written for, arriving
                // through a different door.
                ...unsentAfterPush(target, { dirtyAt: stage.dirtyAt, removedFrom: sweep.emptiedEntities }),
                lastPushedAt: now,
                completeness: saved.completeness ?? target.completeness,
                failure: rejected
                  ? failure(
                      `The server refused ${rejected} answer${rejected === 1 ? "" : "s"} in this stage and kept what it ` +
                        `already held for ${rejected === 1 ? "it" : "them"}. Everything else was saved and nothing you ` +
                        "typed has been thrown away — open the stage to see which answers, and what the repository holds.",
                      /*
                        `permanent: true` IS DELIBERATE HERE, AND IT IS NOT WHAT THE HANDSET WRITES.

                        Android records a per-field refusal as `permanent = false`. Copying that here would be
                        wrong, because the field does not mean the same thing on the two surfaces. On Android
                        `permanent` is read by `blocksRetry` and nothing else — the refusal's VISIBILITY rides on
                        separate columns (`failure`, `refusedFields`, `refusedScopes`), which are written whatever
                        `permanent` says. In this store `permanent` does double duty: `pendingWork` lists a stage's
                        refusal ONLY `if (stage.failure?.permanent)`, and `failures.length` is also one of the
                        things that keeps the draft in the pending list at all. Setting it false would delete this
                        refusal from the one list a designer reads to find out what still needs them — a save the
                        server partly refused would go quiet, which is the defect this lane exists to close, not
                        one to introduce.

                        The v3 migration rung says the same thing in the same words and is the reason it can be
                        stated so flatly: "a v2 `failure` says `permanent: true` for a rejected field (the
                        designer's to fix, so it must go on sticking) and for a schema refusal (nobody's to fix,
                        and it must NOT)". This is the first of those two, so it sticks.

                        AND IT COSTS NO RETRY. `blocksRetry` only ever skips a stage the pass would otherwise
                        send, and `unsentAfterPush` has already set `dirtyAt: null` for this one — the PUT
                        succeeded. The designer's correction reaches the server regardless: `putDraftStage`
                        writes `failure: null` on any edit, and the stage page saves directly rather than through
                        this pass. So the two surfaces differ in what they RECORD and agree on what HAPPENS —
                        the refusal keeps being shown, and neither app spins on it.
                      */
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
      // THE SAME SPLIT AS THE STAGE ARM, AT WORKSHOP LEVEL. `createDesignWorkshop` and
      // `patchDesignWorkshop` post an `APIModel` too, so a client that has learned a new header
      // field before the API has gets `extra_forbidden` here — and marking that plainly permanent
      // strands not one stage but the WHOLE fortnight, header, stages, photographs and all, behind a
      // refusal nobody can act on. It is the more expensive half of the same bug, so it gets the same
      // answer: say what happened, and let the next app run find out whether the skew has closed.
      const schemaRefusal = isSchemaRefusal(error);
      await mutate(draft.localId, (current) => ({
        ...current,
        failure: failure(
          status === 409
            ? // A 409 here is `save_stage` refusing to write into a workshop somebody deleted — not
              // an echo of our own create. Nothing has been sent and nothing has been thrown away.
              "This workshop has been deleted on the server, so nothing more can be sent to it. Everything you captured is " +
                "still on this device. Ask an admin to restore it, then sync again."
            : schemaRefusal
              ? `The repository could not read what this copy of the app sent for this workshop: ${error.message} Nothing ` +
                "you typed is wrong and nothing has been thrown away — this app and the repository are out of step. " +
                "Everything you captured is safe on this device, and it will be sent by itself the next time you open the " +
                "app after either has been updated; you do not have to do anything."
              : error instanceof Error
                ? error.message
                : "The server refused this workshop.",
          true,
          (current.failure?.attempts ?? 0) + 1,
          schemaRefusal ? APP_RUN_ID : null
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
 *
 * IT IS NOW FOR THOSE CASES ONLY. A refusal whose cause is a client/server version skew clears
 * itself on the next app run ({@link blocksRetry}) precisely because a designer has no way of
 * knowing when to press this, and no reason to think a refusal blaming their answers would be
 * cleared by pressing it. This button stays for the refusals a person genuinely decides about.
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
