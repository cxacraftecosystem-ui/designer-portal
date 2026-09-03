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
 * {@link ReferenceRegister} is a closed union of the four register models and every RECORD-FORM
 * entry point takes it, so `putCachedRegister("designWorkshop", …)` does not compile. A future
 * author who wants to cache a grant set has to delete a type to do it, which is exactly the amount
 * of friction the rule is worth. **Do not "unify" the two by widening this to a string.**
 *
 * THE STAGE-PICKER HALF ADDED 2026-09-03 KEEPS THE SAME RULE BY THE SAME KIND OF MEANS, and the
 * difference is worth stating because it looks like a weakening. Those models are the REGISTRY's
 * (`ref_model=` in `stage_definitions.py`) and arrive as strings off the wire, so a closed union
 * would have to be re-typed at every boundary and would fail open at the first cast. The protection
 * is therefore a closed ALLOW-LIST — {@link DW_CACHEABLE_REFERENCE_MODELS} — with a runtime
 * narrowing, and everything absent from it is live-only. Neither grant model is on it, and a model
 * the registry adds later stays live-only until somebody puts it there on purpose.
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
 * The DESIGN-WORKSHOP stage pickers — A30-03's residual half
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * THE HALF OF `DwReferenceStore` THIS FILE DID NOT PORT, added 2026-09-03.
 *
 * ── WHAT WAS STILL MISSING ────────────────────────────────────────────────────────────────────
 *
 * Everything above serves the four RECORD-FORM registers. The design-workshop STAGE pickers —
 * `StageReferenceField`, `FieldInput`'s record-backed multi-select, and the roster adder — went on
 * fetching `GET /design-workshops/{id}/references` every time a panel opened, offline or not. That
 * comment is in `StageReferenceField` in as many words ("fetches its list every time it opens,
 * offline or not"), and it is the exact asymmetry the header of this file describes, one screen
 * over: the handset offers yesterday's roster in a courtyard and the laptop offers nothing, on the
 * stage where a designer is building a participant list from artisans the repository already holds.
 * A picker with nothing in it reads as "this person is not on record", and the answer to that is to
 * type the name in by hand — the behaviour the whole reference feature exists to end.
 *
 * ── WHY THIS FILE AND NOT A FOURTH STORE IN `lib/designWorkshopStore.ts` ──────────────────────
 *
 * Both were on the table and this file argues for itself twice. Its header refuses to add an object
 * store to an existing database because *"a bump is a change that fails on the EXISTING feature
 * rather than on this one"* — the draft store holds unsent fieldwork, and a `blocked` upgrade there
 * costs a designer their morning. And {@link referenceCacheKey}'s own note already anticipates this
 * caller by name: the owner segment is written out as a constant `ALL` *"so it would have to be
 * re-introduced, in a migration, by whoever first caches something workshop-scoped here."* This is
 * that caller, and no migration is owed because the keys below are a DISJOINT namespace rather than
 * a widening of that one: every key here leads with a literal `dw` segment, and a register key's
 * first segment is one of the four names in {@link ReferenceRegister} — a closed union that does not
 * contain `dw` and cannot be widened without deleting a type. THE PROOF IS THE LEADING NAME AND NOT
 * THE SEGMENT COUNT: both shapes are three segments (corrected 2026-09-03; this paragraph and
 * {@link stageReferenceCacheKey}'s own note both claimed four, which the function has never
 * emitted — the disjointness was real and the reason offered for it was not). Same database, same
 * object store, same `DB_VERSION`, no upgrade event, and nothing already on a laptop is touched.
 *
 * ── R6 SURVIVES, AND IT IS STILL THE COMPILER THAT ENFORCES IT ────────────────────────────────
 *
 * A cached ACCESS list is wrong in the permissive direction, so `Workshop` and `DesignWorkshop` may
 * never be cached. Above, that is a closed union of four models. Here the models are the registry's,
 * so the same protection is a closed ALLOW-LIST ({@link DW_CACHEABLE_REFERENCE_MODELS}) with a
 * runtime narrowing, and the default for anything not on it is LIVE-ONLY. A registry that grows a
 * new REF model therefore gets the old behaviour until somebody adds it deliberately, and neither
 * grant model can arrive by accident.
 *
 * ── WHAT IS DELIBERATELY LIVE-ONLY, MIRRORING THE HANDSET ─────────────────────────────────────
 *
 *  * A SEARCHED query. `WorkshopRepository.designWorkshopReferences` passes `search = null` and
 *    caches only the un-searched open. A search answers a page of matches for one word; storing it
 *    under the list's key would put "the fifty rows matching 'kam'" where the list belongs, and the
 *    next offline open would be a register that had silently shrunk to one query's answer.
 *  * A CASCADED (`filterBy`) query. The handset caches these under a filter segment and narrows on
 *    the device; this port deliberately takes the smaller subset first, because the narrowed list is
 *    only correct for the parent row it was fetched for and the device-side `narrowedTo` half is not
 *    wired here. A cascade therefore behaves exactly as it does today. That is the next increment,
 *    not a gap this one introduced.
 *  * A BY-ID resolve (`recordId`). `StageRecordEmbed.describeForField` and the card scanner both
 *    ask "which record is this id" against the same endpoint. An answer served from a stored list
 *    would be this device's memory of a record answering a question about the record as it is NOW,
 *    on the path where a wrong answer is written into a stage row — see that function's own note on
 *    preferring silence to a guess.
 */

/**
 * The REF models a stage picker may keep a copy of. See R6 above before adding one.
 *
 * Every entry is a repository REGISTER or an in-workshop entity — a projection a signed-in account
 * may read — and none of them is a grant set. `Workshop` and `DesignWorkshop` are absent and must
 * stay absent: they are the two lists whose staleness reads a revoked grant as a grant.
 *
 * THE TWO KINDS ARE NOT KEYED THE SAME WAY and the difference is not on this list — see
 * {@link isWorkshopInternalReferenceModel}. The last five entries mean something in ONE workshop
 * only; the first six are the repository's, shared across every workshop on the device.
 *
 * Read off `ref_model=` in `backend/app/services/stage_definitions.py`. A model the registry adds
 * later is simply not cacheable until it is named here, which is the safe direction.
 */
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

/** PURE, so the allow-list is pinned by a spec rather than by taking a laptop somewhere with no signal. */
export function isDwCacheableReferenceModel(model: string | null | undefined): model is DwCacheableReferenceModel {
  return typeof model === "string" && (DW_CACHEABLE_REFERENCE_MODELS as readonly string[]).includes(model);
}

/**
 * Does this model resolve INSIDE one design workshop, whatever scope its field declares?
 *
 * ── THE CROSS-WORKSHOP LEAK THIS CLOSES (2026-09-03) ──────────────────────────────────────────
 *
 * {@link stageReferenceCacheKey} used to read the owner off `refScope` alone, and the five
 * in-workshop entities on {@link DW_CACHEABLE_REFERENCE_MODELS} do not declare `WORKSHOP`:
 * `stage_definitions.py` writes `ref_model="DwParticipant", ref_scope=ALL_SCOPE`, and
 * `f("artisanRef", "Made by", REF, S, ref_model="DwParticipant")` declares no scope at all. So one
 * workshop's roster, sketches, prototypes, cost sheets and final products were all filed under owner
 * `ALL` and served straight into the NEXT workshop's picker — a designer building stage 13 in a
 * second workshop was offered the first one's participants, and choosing one writes a stage-entry id
 * that workshop does not contain, into a report that then cites a person who was never there.
 *
 * THE SCOPE FIELD IS NOT WRONG, IT IS ANSWERING A DIFFERENT QUESTION, which is why the repair is
 * here and not in the registry. The server never consults it for these models either:
 * `reference_options` tries `_dw_entity(model)` FIRST and hands the whole open to
 * `_in_record_options`, whose docstring is the rule this predicate mirrors — *"Always scoped to the
 * workshop whatever the field's declared scope says, because there is no other reading of a
 * `DwSketch` reference."*
 *
 * MIRRORED BY THE MODEL NAME BECAUSE THAT IS WHAT `_dw_entity` MATCHES ON. It looks the model up
 * among the registry's entity NAMES, and every one of those is `Dw` + PascalCase — the `name=`
 * argument of every `single(…)` and `many(…)` in `stage_definitions.py`, without exception. Nothing
 * in `REFERENCE_MODELS` is (`Artisan`, `Craft`, `Process`, `ProductDocumentation`,
 * `ToolDocumentation`, `QuestionnaireInterview`, `Questionnaire`), and neither grant model is:
 * `DesignWorkshop` is `De`, not `Dw`, and the allow-list refuses it long before this is asked.
 *
 * A registry entity added later is therefore internal from the day it is named, with no second list
 * to keep in step — and the direction of any mistake is a key too NARROW, which costs a picker one
 * re-fetch, rather than one workshop's rows in another workshop's list.
 */
export function isWorkshopInternalReferenceModel(model: string | null | undefined): boolean {
  return typeof model === "string" && /^Dw[A-Z]/.test(model);
}

/**
 * Whose copy of this list is it? The owner segment, and the whole of the disjointness rule.
 *
 * THE `ALL` OWNER IS RESERVED FOR THE REPOSITORY-OWNED MODELS. A workshop-internal model keys under
 * the workshop id whatever `refScope` says (see {@link isWorkshopInternalReferenceModel});
 * everything else honours the declared scope, compared case-insensitively because it arrives off the
 * wire and the Kotlin twin compares the same way (`scope.equals("WORKSHOP", ignoreCase = true)`).
 *
 * The sharing `ALL` produces is the feature and not a space saving: the artisan register is the same
 * register in every workshop, so a designer opening a brand-new workshop in a village picks from the
 * copy some earlier workshop on this laptop downloaded, and links to a real record instead of
 * retyping a name that will never join to anything. That argument is exactly why it may not be
 * extended to a roster.
 *
 * PURE and exported, so both the key and the stored `owner` read it and a spec can pin the pairing.
 */
export function stageReferenceCacheOwner(
  model: string,
  scope: string | null | undefined,
  workshopId: string
): string {
  if (isWorkshopInternalReferenceModel(model)) return workshopId;
  return (scope ?? "").toUpperCase() === "WORKSHOP" ? workshopId : "ALL";
}

/**
 * Which document a stage picker's un-searched, un-cascaded open belongs to.
 *
 * `dw__<model>__<owner>` — {@link stageReferenceCacheOwner} is the owner rule and this is its
 * principal caller. `DwReferenceStore.cacheKey`'s shape, ported.
 *
 * THREE SEGMENTS AND A LITERAL `dw` FIRST, and it is the leading NAME that makes this namespace
 * provably disjoint from {@link referenceCacheKey}'s `model__owner__filter` — not the count, which
 * is three on both sides. A register key's first segment is a {@link ReferenceRegister}: a closed
 * union of four lower-case names that cannot be widened without deleting a type, and none of them is
 * `dw`. (Corrected 2026-09-03; this note and the block above both said FOUR segments.)
 *
 * Every segment goes through the same `safeName`, so no segment can end in `_` and a prefix ending
 * in `__` can only ever match WHOLE segments; the Kotlin twin spends a paragraph on the
 * cross-workshop leak that comes back the day that stops being true, and nothing here may weaken it.
 *
 * NO FILTER SEGMENT, deliberately: only un-cascaded opens are cached (see the block above). Adding
 * cascades means adding a fourth segment, not overloading this one.
 */
export function stageReferenceCacheKey(model: string, scope: string | null | undefined, workshopId: string): string {
  return ["dw", model, stageReferenceCacheOwner(model, scope, workshopId)].map(safeName).join("__");
}

/** One cached stage-reference answer: the server's whole payload, plus when this device got it. */
export type CachedStageReferences<Payload> = {
  key: string;
  schemaVersion: number;
  model: string;
  /**
   * The workshop id, or `ALL` for a repository-owned register. {@link stageReferenceCacheOwner} is
   * the rule, and this field must be recomputed through it rather than beside it: the two spellings
   * drifting is how a record's own `owner` came to say `ALL` about a document keyed per workshop.
   */
  owner: string;
  /** ISO-8601, device clock. SHOWN so a designer can judge the list; never used to decide. */
  fetchedAt: string;
  /**
   * THE WHOLE `DwReferencePayload`, NOT JUST ITS OPTIONS.
   *
   * The picker is obliged to repeat three things the server says about a list — `scopedToWorkshop`,
   * `truncated`, `filtered` — and one about its ORDER (`tentativeFirst` / `tentativeLabel`). Storing
   * the options alone would put a list on screen offline with every one of those sentences missing:
   * a page of fifty drawn as if it were the whole register, a widened list drawn as if it were this
   * workshop's roster, and a re-ordered list with nothing on screen to account for the order. That is
   * rule 10 arriving precisely where nobody can check it. `DwReferenceStore` reaches the same place
   * from the other side by storing the tentative word beside the options it explains.
   */
  payload: Payload;
};

/** May this stored stage-reference record be handed to a picker? PURE, so a spec can pin the refusal. */
export function stageReferenceRecordIsReadable<Payload>(
  record: CachedStageReferences<Payload> | null | undefined
): record is CachedStageReferences<Payload> {
  if (!record) return false;
  if (typeof record.schemaVersion !== "number" || record.schemaVersion > REFERENCE_CACHE_VERSION) return false;
  return Boolean(record.payload);
}

/** The stored answer for one picker, or null when this browser has never successfully fetched it. */
export async function getCachedStageReferences<Payload>(
  model: string,
  scope: string | null | undefined,
  workshopId: string
): Promise<CachedStageReferences<Payload> | null> {
  if (!isDwCacheableReferenceModel(model)) return null;
  // AN INTERNAL MODEL WITH NO WORKSHOP ID HAS NO OWNER TO KEY UNDER, and `safeName("")` is
  // `unnamed` — one shared document for every workshop this laptop has not yet filed, which is the
  // leak {@link isWorkshopInternalReferenceModel} is about wearing a different segment.
  // `loadStageReferences` refuses first; this is the same refusal for a direct caller. (2026-09-03)
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

/**
 * Keep this picker's answer. Best-effort; nothing throws.
 *
 * AN EMPTY ANSWER DOES NOT OVERWRITE A POPULATED ONE — `DwReferenceStore.store`'s rule, and the
 * failure it prevents is identical here: a server that answers `[]` because a permission check
 * quietly failed, or because a WORKSHOP-scoped field was asked with the wrong scope, would wipe the
 * roster off a laptop that is about to lose signal for three days, and the designer would find an
 * empty picker with no way to understand why. The refusal is REPORTED rather than silent — the
 * resolved value is what is cached NOW, not what this call tried to store.
 *
 * `[]` STILL WRITES WHERE THERE IS NOTHING TO PROTECT, for the reason the register half gives: a
 * genuinely empty roster on day one is a real answer, and refusing to record it would leave the
 * picker unable to tell "the server said there are none" from "this device has never asked".
 *
 * The caller supplies {@link isEmpty} because this module is deliberately generic over the payload —
 * it must not learn the shape of `DwReferencePayload` to hold one.
 */
export async function putCachedStageReferences<Payload>(
  model: string,
  scope: string | null | undefined,
  workshopId: string,
  payload: Payload,
  isEmpty: (payload: Payload) => boolean
): Promise<CachedStageReferences<Payload> | null> {
  if (!isDwCacheableReferenceModel(model)) return null;
  // The same refusal, for the same reason, as the read above.
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
    // Handlers attached BEFORE the request is issued — see `putCachedRegister` for what happens to
    // this key's chain if they are not.
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

/**
 * What a read-through open actually did, and the failure it swallowed.
 *
 * `error` IS CARRIED RATHER THAN DISCARDED, which is where this differs from {@link
 * loadCachedRegister}. That one serves a form field whose caller already owns a failed/offline arm;
 * these pickers print the server's own sentence when a load fails outright, and swallowing it would
 * replace a real refusal ("this design workshop has been deleted") with a generic one. It is only
 * ever read on `source: "none"` — a fetch that failed while the cache answered is deliberately
 * silent, because a designer holding a perfectly good list has nothing to do about a timed-out GET.
 */
export type StageReferenceLoadOutcome = { source: "live" | "cached" | "none"; cachedAt: string | null; error: unknown };

/**
 * CACHE FIRST, THEN REFRESH — the stage-picker twin of {@link loadCachedRegister}.
 *
 * `onPayload` is called ONCE OR TWICE, never as a single blocking round trip: the panel fills from
 * the last answer this laptop stored the instant it opens and then quietly improves. Where there is
 * no stored answer it is called once, on the live one, exactly as the picker behaves today.
 *
 * THE FRESH ANSWER WINS EVEN WHEN IT IS SHORTER, and {@link putCachedStageReferences} is what stops
 * that being a loss: the one shape that could empty a picker from a bad answer cannot reach storage.
 *
 * A MODEL THAT IS NOT CACHEABLE FALLS STRAIGHT THROUGH to a single live fetch. So does a caller with
 * no workshop id. Neither is an error and neither says anything on screen — the picker's behaviour
 * is then byte-for-byte what it was before this existed.
 */
export async function loadStageReferences<Payload>(load: {
  workshopId: string;
  model: string;
  scope: string | null | undefined;
  isEmpty: (payload: Payload) => boolean;
  fetch: () => Promise<Payload>;
  /** Called with the cached answer, then with the fresh one. `cachedAt` is null for a live answer. */
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

  /*
    BOXED, so "the fetch landed" is a fact about the CALL and not about the value it produced.
    `Payload` is unconstrained here — this module deliberately never learns the shape of a
    `DwReferencePayload` — so a bare `if (fetched)` would also be false for any falsy answer a future
    caller hands back, and would silently take the "nothing answered" path over a real one.
  */
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
      // Not awaited: a picker must not wait on storage to draw a list it is already showing, and a
      // refused write is not a failed read. The per-key chain keeps the ordering honest.
      void putCachedStageReferences(model, scope, workshopId, landed.value, isEmpty);
    }
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
