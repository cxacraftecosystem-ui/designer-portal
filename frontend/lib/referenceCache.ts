"use client";

/**
 * THE FOUR REGISTERS, AS DOCUMENTS THIS BROWSER OWNS — the web's half of `DwReferenceStore`.
 *
 * ── WHAT WAS MISSING, AND WHAT IT COST ────────────────────────────────────────────────────────
 *
 * This client had exactly three IndexedDB databases — the write outbox (`lib/offline.ts`), the
 * design-workshop drafts (`lib/designWorkshopStore.ts`) and the questionnaire read cache
 * (`lib/questionnaireFormCache.ts`) — and not one of them held an OPTION LIST. So DROPDOWN_DESIGN
 * §3.3's caching ruling, which is written for both clients, shipped on Android only: a designer's
 * handset offers yesterday's artisan register in a courtyard with no signal, and the same designer
 * on a laptop opens `/products/new`, meets `craftNotice` saying the list could not be loaded, and
 * has no craft to pick. The outbox underneath is working perfectly and will carry the record — with
 * its craft empty, because the picker had nothing in it.
 *
 * That is the asymmetry this file closes, and it is closed by MIRRORING the Kotlin store rather
 * than by inventing a second design: the same key shape, the same `fetchedAt` discipline, the same
 * refusal to expire and the same refusal to let an empty answer overwrite a populated cache. Read
 * `android/.../data/DwReferenceStore.kt` beside this; where the two disagree, that file is right and
 * this one is a defect.
 *
 * ── R6: THE TWO ACCESS LISTS ARE NOT CACHEABLE, AND THE COMPILER IS WHAT SAYS SO ──────────────
 *
 * A stale ACCESS list is wrong in the PERMISSIVE direction — a cache of "which workshops may I file
 * under" reads a revoked grant as a grant — so caching is FORBIDDEN for `Workshop` and
 * `DesignWorkshop`, not merely unattractive. `WorkshopRepository.kt` puts it as *"a picker is the
 * one control that must not offer what it cannot honour"*, and `lib/workshopOptions.ts` carries the
 * web's own divergent sentence for the same rule (the `accessList` arm of `workshopListNotice`,
 * which ends *"this list is never kept on the device, because a stored copy of who may file where
 * reads a revoked grant as a grant"*).
 *
 * A COMMENT SAYING SO WOULD BE THE WEAKEST FORM OF THAT RULE, so it is not the only form here.
 * {@link ReferenceRegister} is a closed union of the four register models and every entry point
 * takes it, so `putCachedRegister("designWorkshop", …)` does not compile. A future author who wants
 * to cache a grant set has to delete a type to do it, which is exactly the amount of friction the
 * rule is worth. **Do not "unify" the two by widening this to a string.**
 *
 * ── WHY A FOURTH DATABASE RATHER THAN A STORE IN AN EXISTING ONE ──────────────────────────────
 *
 * `lib/questionnaireFormCache.ts` states it and the reason is unchanged: adding an object store to
 * an existing database is a `DB_VERSION` bump, and a bump is a change that fails on the EXISTING
 * feature rather than on this one — `indexedDB.open(DB_NAME, 1)` against a browser already at 1
 * never fires `onupgradeneeded`, and an open at a new version while another tab holds the old one
 * fires `blocked` and never resolves. A new name cannot do either to the outbox.
 *
 * ── WHAT IS DELIBERATELY NOT HERE ─────────────────────────────────────────────────────────────
 *
 * NO EXPIRY, on `DwReferenceStore`'s reasoning, which is the argument this whole class turns on:
 * *"A stale artisan list is worth immeasurably more than no artisan list, and there is no clock on a
 * phone that can tell 'two weeks old because nothing changed' from 'two weeks old because there has
 * been no signal for two weeks'."* {@link CachedReferenceList.fetchedAt} is recorded and SHOWN —
 * through `cachedListNotice`, §3.5's cached-and-stale sentence — so the designer can judge it;
 * nothing in this file ever deletes on the strength of it.
 *
 * NO `ownerUserId`, WHICH IS THE ONE PLACE THIS DIVERGES FROM ITS TWO SIBLING CACHES ON THIS
 * CLIENT, so the divergence is argued rather than assumed. `CachedQuestionnaireForm` and `DwDraft`
 * both refuse a record to any account but the one that took it, because both hold FIELDWORK — a
 * sitting's answers with the respondent's name on them, an unsent draft. What is stored here is a
 * projection of a repository-wide register that every signed-in account may read, reduced to id,
 * label, a disambiguating hint and a parent key ({@link CachedReferenceOption}) — no Aadhaar, no
 * phone number, no address, nothing `Artisan` carries that the trap in `lib/types.ts` is about.
 * Sharing it across the accounts on one field laptop is not a leak; it is the same property that
 * makes `DwReferenceStore`'s ALL-scoped keys work, and withholding it would mean the second
 * researcher to sign in on a shared laptop gets the empty picker this file exists to end.
 *
 * NO WRITE PATH. Nothing in here is ever sent anywhere. A record queued against a cached option id
 * is the outbox's business, and the one failure that can produce — an id the server does not have
 * by the time the queue drains — is answered AFTER the drain by `repickOutboxEntry`, never here.
 * R7: an empty picker and a dangling foreign key are opposite failures with opposite remedies.
 */

/**
 * The four register models, and the whole of what this cache will hold. See the R6 block above
 * before adding a fifth: `Workshop`, `DesignWorkshop`, the viewer/inspector rosters and the
 * designer directory are all grant sets and none of them may appear here.
 */
export type ReferenceRegister = "craft" | "artisan" | "product" | "tool";

/**
 * One option as it is stored — `DwReferenceOption`'s four display fields and nothing else.
 *
 * REDUCED RATHER THAN STORED WHOLE, and the reduction is doing two jobs. It keeps regulated columns
 * out of a store that outlives a session (see the `ownerUserId` note in the header), and it makes
 * the stored shape the same shape on both clients, so a rule proved about one is a rule about both.
 */
export type CachedReferenceOption = {
  /** The id that goes on the wire. This is the join key; everything else is display. */
  id: string;
  /** What a person reads in the list. */
  label: string;
  /** A second line — a village, a craft — that tells two rows called Ram apart. */
  hint: string;
  /**
   * The parent id this option hangs under, for a cascading picker: an artisan's `craftId`, a
   * product's `artisanId`. THE CLIENT NEEDS ITS OWN COPY and cannot rely on the server having
   * filtered — offline, the cached list is the whole model and the narrowing happens here.
   */
  filterValue: string;
  /**
   * The handful of the record's own columns a PICKER reads, keyed by their field names.
   *
   * `DwReferenceOption.data` in the same position and for the same job. It exists because the four
   * fields above are not quite enough for every control: the tool picker's label is
   * `toolkitName — craftName · artisanName`, three columns rather than a label and one hint, and a
   * codec that folded them into `hint` with a separator could not split them back — a craft called
   * "Block printing · Bagru" would decode into two different names, silently, on the one screen
   * whose whole job is to say which record is which.
   *
   * IT IS NOT A PLACE TO PUT THE RECORD. Strings only, and only what a control on screen reads;
   * `Artisan` carries an Aadhaar number and a phone, and this store has no `ownerUserId` (see the
   * header) precisely BECAUSE nothing regulated is meant to reach it. A codec that started copying
   * whole rows in here would silently undo that argument.
   */
  data?: Record<string, string>;
};

/**
 * The shape of a stored RECORD, not of the register.
 *
 * Bumped when the stored shape changes incompatibly. A record written at a HIGHER version than this
 * build understands is ignored rather than decoded, the same discipline the questionnaire cache and
 * the draft store apply: a build that half-understands a newer record renders a picker with rows
 * silently missing, and a picker missing rows is indistinguishable from a register that never had
 * them — which is the exact claim this whole area exists to stop a control from making.
 */
export const REFERENCE_CACHE_VERSION = 1;

/** One cached list: the options, plus enough provenance to say honestly how old they are. */
export type CachedReferenceList = {
  /** `model__owner__filter` — see {@link referenceCacheKey}. The IDB keyPath. */
  key: string;
  schemaVersion: number;
  model: ReferenceRegister;
  /** The parent value these options were narrowed to, or `""` for the whole model. */
  filteredBy: string;
  /** ISO-8601, device clock. SHOWN so a designer can judge the list; never used to decide. */
  fetchedAt: string;
  items: CachedReferenceOption[];
};

const DB_NAME = "field-repo-references";
const DB_VERSION = 1;
const STORE_REGISTERS = "registers";
const STORE_ADDRESS = "address";

/* ────────────────────────────────────────────────────────────────────────────
 * The key
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Everything that is not `[A-Za-z0-9._-]` collapses to `_`, and the result can never begin or end
 * with one — which is what makes `model__owner__filter` parse back unambiguously.
 *
 * PORTED VERBATIM FROM `DwReferenceStore.safeName`, INCLUDING THE TRIM, and the trim is the part
 * that matters. The three segments are joined with `__`, so a segment that could end in `_` would
 * let a prefix ending in `__` match a PARTIAL segment: `artisan__ALL_` would match
 * `artisan__ALLOWED__…`. The Kotlin file spends a paragraph on the cross-workshop leak that shape
 * reintroduces. Nothing on the web reads by prefix today, and that is a fact about today's callers
 * rather than about this key — so the key is built to survive one that does.
 */
function safeName(raw: string): string {
  const collapsed = raw.trim().replace(/[^A-Za-z0-9._-]+/g, "_");
  const trimmed = collapsed.replace(/^[._]+/, "").replace(/[._]+$/, "").slice(0, 80);
  return trimmed || "unnamed";
}

/**
 * Which document a (model, filter) request belongs to.
 *
 * THE OWNER SEGMENT IS ALWAYS `ALL` AND IS STILL WRITTEN OUT, which looks like a constant asking to
 * be deleted and is not. `DwReferenceStore.cacheKey` puts the workshop id there for WORKSHOP-scoped
 * lists and `ALL` for everything else, and all four registers on this client are ALL-scoped: a craft
 * is a craft in every workshop. Dropping the segment would make the two clients' keys differ by a
 * field, so a rule proved about one key shape would stop being a rule about the other — and it would
 * have to be re-introduced, in a migration, by whoever first caches something workshop-scoped here.
 *
 * PURE and exported, so the pairing is pinned by a spec rather than by taking a laptop somewhere
 * with no signal.
 */
export function referenceCacheKey(model: ReferenceRegister, filterValue = ""): string {
  const filter = filterValue.trim() ? filterValue : "_";
  return [model, "ALL", filter].map(safeName).join("__");
}

/**
 * The options this list holds for one parent value.
 *
 * Applied on the DEVICE even where the server was asked to filter, and the redundancy is the point:
 * a cached whole-model list and a cached narrowed list live under different keys but either may be
 * what the picker has to hand, and a product list cached whole must still narrow to one artisan on
 * a laptop with no signal to re-ask.
 *
 * AN OPTION CARRYING NO `filterValue` AT ALL IS KEPT rather than dropped. A server that stops
 * populating the parent key would otherwise empty every cascading dropdown in the app, which is a
 * far worse failure than showing a few options too many. `DwReferenceList.narrowedTo` states it in
 * the same words and this is the same decision, not a coincidence.
 */
export function narrowedTo(items: readonly CachedReferenceOption[], parentValue: string): CachedReferenceOption[] {
  if (!parentValue.trim()) return [...items];
  return items.filter((item) => !item.filterValue || item.filterValue === parentValue);
}

/**
 * May this stored record be handed to a picker? PURE, so the refusal is pinned by a spec.
 *
 * One refusal, and it is not a clock: a record from a FUTURE build. See
 * {@link REFERENCE_CACHE_VERSION}.
 */
export function referenceRecordIsReadable(record: CachedReferenceList | null | undefined): record is CachedReferenceList {
  if (!record) return false;
  if (typeof record.schemaVersion !== "number" || record.schemaVersion > REFERENCE_CACHE_VERSION) return false;
  return Array.isArray(record.items);
}

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
        if (!db.objectStoreNames.contains(STORE_REGISTERS)) db.createObjectStore(STORE_REGISTERS, { keyPath: "key" });
        if (!db.objectStoreNames.contains(STORE_ADDRESS)) db.createObjectStore(STORE_ADDRESS, { keyPath: "key" });
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error ?? new Error("Cannot open the local reference store"));
    });
    // A failed open must not be cached for the session — a private-mode tab that later allows
    // storage should get a working store rather than the first rejection for ever. Same rule and
    // same reason as `lib/offline.ts`, `lib/designWorkshopStore.ts` and `lib/questionnaireFormCache.ts`.
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

/**
 * WRITES FOR ONE KEY ARE CHAINED, and that is not tidiness.
 *
 * `useCraftAndArtisanOptions` fires its repository-wide load and its craft-scoped load together, and
 * both write. IndexedDB does not promise that two overlapping transactions on one key complete in
 * the order they were opened, so without this chain the copy left on disk could be the OLDER of the
 * two — carrying an older `fetchedAt`, which is precisely the value the designer is asked to judge
 * staleness by. `lib/questionnaireFormCache.ts` met this first and words it the same way.
 */
const writeChain = new Map<string, Promise<void>>();

/**
 * Keep this list. Best-effort: a full disk, a private window or a browser with storage blocked must
 * never turn loading a picker into an error about a cache nobody asked for. Resolves `true` when the
 * copy was stored and `false` when it was not — nothing throws.
 *
 * ── AN EMPTY FETCH DOES NOT OVERWRITE A NON-EMPTY CACHE ───────────────────────────────────────
 *
 * `DwReferenceStore.store` states it and the failure is identical on this client: a server that
 * answers `[]` because a permission check quietly failed, or because a scope was wrong, would
 * otherwise wipe the artisan register off a laptop that is about to lose signal for three days — and
 * the researcher would find a dropdown with nothing in it and no way to understand why. The refusal
 * is REPORTED rather than silent: the resolved value is what is actually cached now, not what this
 * call tried to store, so a caller that reads the answer back cannot conclude it stored something it
 * did not.
 *
 * `[]` STILL WRITES WHERE THERE IS NOTHING TO PROTECT. A register that is genuinely empty on a fresh
 * repository is a real answer, and refusing to record it would leave the picker unable to tell "the
 * read answered and there are none" from "this device has never asked" for ever.
 */
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
    // The completion handlers are attached BEFORE the request is issued. A transaction auto-commits
    // once its last request settles, and an `oncomplete` assigned after that commit is a handler for
    // an event already dispatched — the await would never resolve and this key's chain would be
    // stuck for the rest of the session.
    const done = new Promise<void>((resolve, reject) => {
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error ?? new Error("The local reference store refused a write"));
      tx.onabort = () => reject(tx.error ?? new Error("The local reference store aborted a write"));
    });
    tx.objectStore(STORE_REGISTERS).put(record);
    await done;
    return record;
  });

  // The chain must survive a failed link, or one refused write jams every later write for this key.
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

/**
 * The cached list for one register, or null when this browser has never successfully fetched it.
 *
 * NULL AND AN EMPTY LIST ARE DIFFERENT ANSWERS and a picker draws them differently. Null is *"we
 * have never seen this list"* — §3.5's `empty-because-offline`, whose next move is a connection. An
 * empty {@link CachedReferenceList} is *"the server told us there is nothing here"*, which is
 * §3.5's genuinely-empty and whose next move is to create a record.
 */
export async function getCachedRegister(
  model: ReferenceRegister,
  options?: { filterValue?: string }
): Promise<CachedReferenceList | null> {
  return readRegister(referenceCacheKey(model, options?.filterValue?.trim() ?? ""));
}

/**
 * CACHE FIRST, THEN REFRESH — `MainActivity.loadCachedRegister`'s order of operations, ported.
 *
 * Answer from storage immediately (nothing, the first time this browser has ever asked), then try
 * the network, and on success replace the cache and answer again with the fresh list. {@link
 * ListLoad.onList} is therefore called ONCE OR TWICE, never as a single blocking round trip — so a
 * craft dropdown fills in from yesterday's register the instant the form mounts and then quietly
 * improves, rather than sitting empty until a request that may never complete.
 *
 * A FAILED FETCH IS SILENT HERE, on the Kotlin twin's reasoning: propagating a timed-out GET turns
 * a village with no signal into a crashed craft picker, at a researcher who cannot do anything about
 * it and whose browser is holding a perfectly good copy of the list it has already handed over. The
 * RETURN VALUE is how a caller learns what happened, and it is deliberately three-way rather than a
 * boolean, because the three answers drive three different sentences (§3.5):
 *
 *  * `"live"`   — the network answered. Nothing to report; the picker says nothing.
 *  * `"cached"` — only storage answered, and `cachedAt` carries the stamp the cached-and-stale
 *                 sentence is built from. The field stays REQUIRED: it is answerable.
 *  * `"none"`   — neither answered. The caller's existing failed/offline arms take it from here, and
 *                 the field stands down (R2).
 *
 * THE FRESH ANSWER WINS EVEN WHEN IT IS SHORTER, and the cache is what stops that being a loss:
 * {@link putCachedRegister} refuses to let an empty fetch overwrite a populated document, so the
 * one shape that could empty a picker from a bad answer cannot reach storage.
 */
export type RegisterLoadOutcome = { source: "live" | "cached" | "none"; cachedAt: string | null };

export async function loadCachedRegister<Row>(load: {
  model: ReferenceRegister;
  /** Narrowed lists take the parent id; the whole register passes nothing. */
  filterValue?: string;
  decode: (option: CachedReferenceOption) => Row | null;
  encode: (row: Row) => CachedReferenceOption;
  fetch: () => Promise<Row[]>;
  /** Called with the cached list, then with the fresh one. `cachedAt` is null for a live answer. */
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
    // Deliberately swallowed — see the block above. The caller reads `outcome`.
  }
  if (fetched) {
    onList(fetched, null);
    outcome = { source: "live", cachedAt: null };
    // Not awaited: a picker must not wait on storage to draw a list it already holds, and a refused
    // write is not a failed read. The per-key chain in `putCachedRegister` keeps the ordering honest.
    void putCachedRegister(model, fetched.map(encode), { filterValue });
  }
  return outcome;
}

/* ────────────────────────────────────────────────────────────────────────────
 * The address reference — one document, invalidated by the server's own `version`
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * THE ONE SERVER-PROVIDED INVALIDATION SIGNAL IN THIS API, AND UNTIL NOW NO CLIENT HONOURED IT.
 *
 * `backend/app/api/routes/reference.py` says it in the route's own docstring: *"The payload is a
 * pure constant — no database read — so a client should cache it and re-fetch only when `version`
 * changes."* Android compares the whole encoded JSON, which is the right OUTCOME reached by the
 * expensive route (encode 12 KB, compare 12 KB, on every successful fetch); the web stored nothing
 * at all and re-fetched on every page load, so a laptop that had been online an hour ago still met
 * an empty district list the moment the signal went.
 *
 * `version` is what this store is keyed on judging, not on. The record is written under ONE key —
 * there is one address reference — and the version is compared to decide whether a write is owed at
 * all. A version that has not moved costs no write and no re-render; a version that has moved
 * replaces the document. Nothing here expires and nothing here refuses a payload for being old: see
 * the header.
 */
export const ADDRESS_REFERENCE_KEY = "address-reference";

export type CachedAddressReference<Payload> = {
  key: typeof ADDRESS_REFERENCE_KEY;
  schemaVersion: number;
  /** The server's own `version` for this payload — the whole invalidation signal. */
  version: number;
  /** ISO-8601. SHOWN so a designer can judge an offline district list; never acted on. */
  fetchedAt: string;
  payload: Payload;
};

/** The stored address reference, or null when this browser has never had one. */
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

/**
 * Keep this address reference, unless the stored copy already carries the same `version`.
 *
 * Returns the record now in storage — the existing one when the write was skipped — or null when
 * nothing could be stored. Best-effort throughout: this must never turn a working form into an
 * error about a cache.
 *
 * ── "LAST REFRESHED" MEANS CONFIRMED, NOT CHANGED, AND THAT IS WHY THE STAMP IS REWRITTEN ─────
 *
 * A matching version skips the PAYLOAD write and still moves `fetchedAt`. `AddressReferenceCache`'s
 * stamp file on Android makes the same distinction in the same words: *"'Last refreshed' means the
 * last time the server confirmed the list, not the last time it differed — and the two are far apart
 * here, because the payload is a near-constant that may go a year without moving."* A district list
 * confirmed this morning must not be described to a researcher as eleven months stale.
 */
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
      // The version decides whether the PAYLOAD is replaced. An unchanged version keeps the bytes
      // already on disk, which is the whole saving the route's docstring asks for.
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
