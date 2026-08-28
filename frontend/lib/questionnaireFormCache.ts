/**
 * THE CUSTOM QUESTIONNAIRE, AS A DOCUMENT THIS BROWSER OWNS — so that a designer with no signal can
 * OPEN a colleague's instrument instead of being shown a red line where the questions should be.
 *
 * ── WHAT WAS BROKEN, AND WHAT WAS NOT ─────────────────────────────────────────────────────────
 *
 * `GET /questionnaires/{id}` was the web client's only route to a custom questionnaire, and both
 * screens that call it turned a failure into an error string: `app/(protected)/questionnaires/[id]`
 * and `.../[id]/answer`. Hundreds of questions across two dozen sections, and a designer with no
 * bars saw one sentence. Android fixed exactly this a wave earlier
 * (`android/.../data/DwQuestionnaireFormCache.kt`); this file is that decision on the web, and the
 * two are meant to be read side by side.
 *
 * THE REFUSAL TO SAVE OFFLINE IS NOT WHAT WAS BROKEN AND IS NOT TOUCHED HERE. Whether a question may
 * still be answered is a fact only the server holds — it can be retired between opening the screen
 * and pressing Save, and `save_answers` refuses a retired question with a 422 naming it — so a
 * queued batch of answers would have to be either refused later, losing the sitting the designer
 * believed was recorded, or re-attached to whatever wording replaced it, which fabricates evidence.
 * That argument is about WRITING and it stands. It was being used to justify the read side too, and
 * there it never applied: reading what a colleague recorded this morning, and reading the question
 * you are about to ask out loud, write nothing.
 *
 * **THIS IS A READ CACHE. DO NOT ADD A WRITE QUEUE TO IT.** Nothing in this file is ever sent
 * anywhere, and the offline WRITE path for questionnaires is a separate, larger piece of work with
 * its own precondition — {@link CachedQuestionnaireForm.version}, recorded here at the only moment
 * it is knowable, and deliberately not spent by anything in this file. Half of that write path is
 * worse than none of it.
 *
 * ── WHY IT IS KEYED BY `includeRetired` AS WELL AS BY ID ──────────────────────────────────────
 *
 * Because `includeRetired` is not a preference, it is WHICH LIST OF QUESTIONS CAME BACK. A copy
 * fetched without retired questions is missing the read-only rows that carry answers already
 * recorded; a copy fetched with them contains wordings that must never be offered for a NEW answer.
 * One file for both hands one of the two callers exactly the list it must not have, and the failure
 * is silent either way — a retired wording offered for a new answer looks like an ordinary question,
 * and the server's 422 arrives after the interview.
 *
 * BOTH WEB CALLERS ASK FOR `includeRetired: true` TODAY, so this key presently has one live bucket.
 * That is a fact about the two screens, not about the cache, and it is exactly why the key is the
 * pair: the day a caller asks for the shorter list, an id-only cache would answer it from the longer
 * one and nothing would say so. The key follows the request. Android splits the same key for the
 * same reason and its two screens DO disagree, which is where the failure was first seen.
 *
 * ── WHAT IS DELIBERATELY NOT HERE ─────────────────────────────────────────────────────────────
 *
 * NO EXPIRY, on Android's reasoning, which applies here word for word: there is no clock in a
 * browser that can tell "three days old because nothing changed" from "three days old because there
 * has been no signal for three days". {@link CachedQuestionnaireForm.fetchedAt} is recorded and
 * SHOWN so the designer can judge it; nothing in this file ever deletes on the strength of it.
 *
 * NO CLEAR-ON-SIGN-OUT, because `AuthProvider.logout` clears the token and the user and nothing
 * else, and a hook there would still be bypassed by a closed tab or a crash. The copy carries
 * {@link CachedQuestionnaireForm.ownerUserId} instead and is refused to any other account — the same
 * device-sharing rule `DwDraft.ownerUserId` already applies to design-workshop drafts, and it earns
 * its keep the same way: a `QForm` carries `entries[].respondentName` and every answer given.
 *
 * NO FALLBACK ACROSS THE `includeRetired` KEY. Android has one (`getEither`) for its handoff file
 * builder, and its KDoc is careful that the caller is safe only because it re-filters the questions
 * itself. Nothing on the web has that caller, so nothing here has that door.
 */

import { isUnreachable } from "@/lib/offline";
import { getQuestionnaire, type QForm } from "@/lib/questionnaireForms";

const DB_NAME = "questionnaire-forms";
const DB_VERSION = 1;
const STORE = "forms";

/**
 * The shape of a stored RECORD, not the questionnaire's own {@link QForm.version}.
 *
 * Bumped when the stored shape changes incompatibly. A record written at a HIGHER version than this
 * build knows is ignored rather than decoded, the same discipline the design-workshop draft store
 * applies and for the same reason: a build that half-understands a newer record renders a form with
 * sections silently missing, and a form missing a section is indistinguishable from a questionnaire
 * that never had one.
 */
export const QUESTIONNAIRE_FORM_CACHE_VERSION = 1;

/** The wrapper written to storage: the form, when it crossed the network, and whose copy it is. */
export type CachedQuestionnaireForm = {
  /** `${id}::all` or `${id}::active` — see {@link questionnaireCacheKey}. The IDB keyPath. */
  key: string;
  /** See {@link QUESTIONNAIRE_FORM_CACHE_VERSION}. */
  schemaVersion: number;
  /** ISO-8601, when this copy crossed the network. SHOWN to the designer, never acted on. */
  fetchedAt: string;
  /** Whether retired questions are present, i.e. which list this copy is. */
  includeRetired: boolean;
  /**
   * The questionnaire's own version at the moment of the fetch — the precondition an offline WRITE
   * would need, recorded now because now is the only moment it is knowable. Nothing here spends it;
   * a cache without it would have to be thrown away before that write could ever be built.
   */
  version: number;
  /** Whose signed-in session took this copy. A different account is never served it. */
  ownerUserId: string | null;
  form: QForm;
};

/** What a read produced, and whether the network was involved. */
export type QuestionnaireFormRead = {
  form: QForm;
  /** True when this came out of storage because nothing could reach the server. */
  fromCache: boolean;
  /** ISO-8601 fetch time, only when {@link fromCache}; null for a live read. */
  cachedAt: string | null;
  /** The questionnaire's version as of the stored copy, only when {@link fromCache}. */
  cachedVersion: number | null;
};

/**
 * One record per (questionnaire, includeRetired) pair.
 *
 * PURE, and exported so the pairing is pinned by a test rather than by taking a laptop somewhere
 * with no signal. The two spellings are words rather than `true`/`false` so that a key read out of
 * a browser's storage inspector says which list it holds.
 */
export function questionnaireCacheKey(id: string, includeRetired: boolean): string {
  return `${id}::${includeRetired ? "all" : "active"}`;
}

/**
 * May this stored record be handed to this reader? PURE, so both refusals are pinned by a test.
 *
 * Two refusals, and neither is a clock:
 *
 *  * a record from a FUTURE build — see {@link QUESTIONNAIRE_FORM_CACHE_VERSION};
 *  * a record belonging to somebody else, on a shared field laptop.
 *
 * The owner test compares against the CURRENT session's id, and a record stored with no id (or a
 * reader with no id) is refused rather than shared: "we do not know whose this is" must not resolve
 * to "anyone's".
 */
export function cacheRecordIsReadable(record: CachedQuestionnaireForm | null | undefined, viewerId: string | null): record is CachedQuestionnaireForm {
  if (!record) return false;
  if (typeof record.schemaVersion !== "number" || record.schemaVersion > QUESTIONNAIRE_FORM_CACHE_VERSION) return false;
  if (!record.form || !Array.isArray(record.form.sections)) return false;
  if (!viewerId || !record.ownerUserId) return false;
  return record.ownerUserId === viewerId;
}

/**
 * The sentence shown above a form that came out of storage.
 *
 * PURE — it takes the stamp ALREADY FORMATTED by the caller (`formatDateTime`) rather than a raw
 * ISO string, so the branch that matters can be asserted without the assertion depending on which
 * timezone the test runner is in.
 *
 * It says four things and all four are load-bearing: that this is a copy, WHEN it was taken, which
 * VERSION of the questionnaire it is, and that answers cannot be saved from it — that last one
 * before the designer starts asking questions out loud, not after they have filled in a section.
 * The version is the number that answers "is this copy older than the four questions my colleague
 * added this morning?", which is the only question a designer can actually act on.
 *
 * @param when the fetch time as the page prints it, or null when there is none to print.
 */
export function cachedQuestionnaireNotice(when: string | null, version: number): string {
  const whenPart = when && when !== "-" ? ` on ${when}` : "";
  return (
    `You are reading the copy this browser downloaded${whenPart} (version ${version}). You can read it ` +
    "and check what has already been recorded. ANSWERS CANNOT BE SAVED without a connection: whether " +
    "a question may still be answered is something only the server knows, so this app will not record " +
    "an answer it might have to attach to different wording later."
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * IndexedDB plumbing
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * IndexedDB and not `localStorage`, and the reason is the size of the thing being stored.
 *
 * A `QForm` is the whole instrument — every section, every question with its help text, and every
 * recorded sitting with every answer. `localStorage` is a synchronous, ~5 MB-per-origin string
 * store: one large questionnaire could exhaust it, the write would block the main thread while the
 * designer types, and a quota failure there would be the app appearing to hang rather than a cache
 * quietly declining. Its own database, not the outbox's or the draft store's, because a
 * `DB_VERSION` bump to add a store to either is a change that fails on the EXISTING feature — an
 * `indexedDB.open` at the old version never fires `onupgradeneeded`, and an open at a new one while
 * another tab holds the old one fires `blocked` and never resolves.
 */
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
    // A failed open must not be cached forever — a private-mode tab that later allows storage should
    // get a working store rather than the first rejection for the rest of the session. Same rule and
    // same reason as `lib/offline.ts` and `lib/designWorkshopStore.ts`.
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

/**
 * WRITES FOR ONE KEY ARE CHAINED, and that is not tidiness.
 *
 * Every successful re-read writes here, and both screens re-read after every save — so two puts for
 * one questionnaire are routinely in flight together. IndexedDB does not promise that two
 * overlapping transactions on one key complete in the order they were opened, so without this chain
 * the copy left on disk could be the OLDER of the two, carrying an older `fetchedAt` and an older
 * `version`, which is precisely the pair the designer is asked to judge staleness by.
 */
const writeChain = new Map<string, Promise<void>>();

/**
 * Keep this copy. Best-effort: a full disk, a private window or a browser with storage blocked must
 * never turn reading a questionnaire into an error about a cache the designer did not ask for.
 *
 * Resolves `true` when the copy was stored, `false` when it was not — nothing throws.
 */
export async function putCachedQuestionnaire(
  form: QForm,
  options: { includeRetired: boolean; viewerId: string | null }
): Promise<boolean> {
  const key = questionnaireCacheKey(form.id, options.includeRetired);
  // A copy nobody could ever be served is not worth the disk: `cacheRecordIsReadable` refuses a
  // record with no owner, so storing one written by a session that has not resolved `/me` yet would
  // occupy the key that the next, readable, copy needs.
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
    // The completion handlers are attached BEFORE the request is issued, not after awaiting it. A
    // transaction auto-commits once its last request settles, and `oncomplete` assigned after that
    // commit is a handler for an event that has already been dispatched — the await would never
    // resolve and this key's write chain would be stuck for the rest of the session.
    const done = new Promise<void>((resolve, reject) => {
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error ?? new Error("The local questionnaire store refused a write"));
      tx.onabort = () => reject(tx.error ?? new Error("The local questionnaire store aborted a write"));
    });
    tx.objectStore(STORE).put(record);
    await done;
  });

  // The chain must survive a failed link, or one refused write jams every later write for this key.
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

/** The stored copy for this reader, or null when there is not one this build may serve them. */
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

/**
 * The questionnaire, live if it can be reached and out of storage if it cannot.
 *
 * ── WHEN THE COPY IS SERVED, AND WHEN THE FAILURE IS RE-THROWN ────────────────────────────────
 *
 * `isUnreachable` and nothing else — the one shared reading of a failure this client has
 * (`lib/failureTriage.ts`), so this file does not become a fourth place that decides what an HTTP
 * number means. It answers true only when NOTHING REACHED THE SERVER, which is exactly the case
 * this cache exists for, and false for every case where serving a copy would be a lie:
 *
 *  * a **403** is the server ANSWERING that this account may not read this questionnaire. A grant
 *    that has been revoked must not be re-served out of this device's memory of when it stood.
 *  * a **404** is the server answering that it is gone.
 *  * a **5xx** is `transient`, not `unreachable`: the server was reached and then failed. Handing
 *    back a copy would put a stale form on screen over a live bug and nobody would ever chase it.
 *  * `ApiUnconfiguredError` is a 503 with no response behind it — a build that does not know where
 *    its API is. It is the one deliberate rider on that table and it lands on `transient`, so a
 *    misconfigured deployment reports itself instead of quietly serving yesterday's questions.
 *
 * On a live read the copy is refreshed before this returns, so the stored `fetchedAt` and `version`
 * are always the last pair that actually crossed the network.
 */
export async function loadQuestionnaireWithCache(
  id: string,
  options: { includeRetired: boolean; viewerId: string | null }
): Promise<QuestionnaireFormRead> {
  try {
    const form = await getQuestionnaire(id, { includeRetired: options.includeRetired });
    // Not awaited: the screen must not wait on storage to render a form it already holds, and a
    // refused write is not a failed read. The per-key chain above is what keeps the ordering honest.
    void putCachedQuestionnaire(form, options);
    return { form, fromCache: false, cachedAt: null, cachedVersion: null };
  } catch (error) {
    if (!isUnreachable(error)) throw error;
    const cached = await getCachedQuestionnaire(id, options);
    if (!cached) throw error;
    return { form: cached.form, fromCache: true, cachedAt: cached.fetchedAt, cachedVersion: cached.version };
  }
}
