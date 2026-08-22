import { ApiError } from "@/lib/api";

/**
 * ONE ANSWER TO "IS THIS THE NETWORK?", FOR EVERY WEB SURFACE.
 *
 * ── WHY THIS FILE EXISTS ────────────────────────────────────────────────────────────────────────
 *
 * This repository used to answer that question in SIX places, and the six disagreed:
 *
 *   • `isTransient` in `lib/offline.ts` did not follow `cause`, so anything re-thrown with a
 *     friendlier sentence read as "worth retrying" — a 415 on a video the server will never accept
 *     included.
 *   • `isUnreachable` in the same file DID follow `cause`, to depth 8, and counted only 408 as
 *     unreachable. Two functions eight lines apart, one unwrapping and one not.
 *   • `isSchemaRefusal` demanded an `ApiError` outright, so the same wrapper hid a dialect mismatch
 *     and turned "these two builds are out of step, wait for an update" into "you got this wrong".
 *   • `WorkshopRepository.isTransient` on Android treats 401 as retryable, deliberately, and the web
 *     deliberately does not — a real divergence that lived only in two files' prose.
 *   • `lib/designWorkshopStore.ts` carried a fifth opinion inline: `error instanceof ApiError &&
 *     (error.status === 408 || error.status === 429)`, written out twice.
 *   • `isRetriableApiFailure` in `lib/media.ts` held a SIXTH, and it was the one nobody counted: a
 *     `Set([502, 503, 504])` plus `error instanceof TypeError`, governing every safe-retry call
 *     (presign, multipart create/abort, `/media/complete`). It disagreed with all five in both
 *     directions — no 408 and no 429 where the others retried, and it is spelled `Set.has(...)`, so
 *     no sweep looking for `status === 408` could ever see it. Counted here because a table that
 *     under-counts its own instances is the previous generation of this comment.
 *
 * EVERY OUTBOX DEFECT THE 2026-08 AUDIT CLOSED WAS A DISAGREEMENT BETWEEN TWO OF THEM. A media
 * failure that jammed the queue for ever (`isTransient` said retry, `isUnreachable` said the server
 * had answered). A 401 that left only a button which deletes the work (`isTransient` said no, and
 * nothing else was asked). A 503 in a deploy window that came out of the photograph upload
 * permanently refused while the identical status on the record request one screen up was "try again
 * later". Each was fixed where it was found. The CLASS survived, because the next instance is one
 * more pair of functions that answer differently.
 *
 * So the question is asked ONCE, here, and the `cause` chain is unwrapped ONCE, here. Everything
 * else in the web client is a named reading of one of the two verdicts below.
 *
 * ONE SURFACE STILL NARROWS THE ANSWER, AND SAYS SO. `isRetriableApiFailure` in `lib/media.ts` takes
 * its CLASSIFICATION from here and then declines to repeat a 429 or a 500 inside a four-attempt loop
 * that runs while somebody watches a progress bar — a rate limit answered with four requests in three
 * seconds is the opposite of giving the server time, on somebody's metered connection. That is a
 * narrowing of a shared verdict, not a sixth verdict, and `e2e/failure-triage-unit.spec.ts` records
 * it in `HAND_ROLLED_NETWORK_TESTS` so it is a listed exception rather than an unnoticed one. `lib/offline.ts`
 * re-exports the predicates under the names the surfaces that already import them use — the count is
 * stated in exactly one place, `e2e/failure-triage-unit.spec.ts`, which measures it rather than
 * asserting a number somebody typed — so no screen had to change to start consulting one
 * implementation instead of six.
 *
 * ── TWO VERDICTS, BECAUSE ONE CALLER READS "NOT RETRYABLE" AS "THROW THE BYTES AWAY" ────────────
 *
 * {@link triageFailure} follows `cause` and is the honest classification: a `MediaBatchError`
 * wrapping a 415 IS a refusal. {@link triageAsThrown} classifies the value exactly as it was thrown
 * and is the CONSERVATIVE one: a wrapper it cannot read is "we do not know, so keep the work".
 *
 * That second one is not a leftover. `components/designworkshop/FieldInput.tsx:1874` catches a
 * `uploadMediaBatch` throw and asks {@link isTransient} — but what it DOES with a true is
 * `stageOffline(chosen)`, which puts the captured bytes in the local draft store and points the field
 * at them; the drain effect above it has already removed those files from the capture card, so a
 * false there is not "report it honestly", it is "the photograph is gone". Measured, not assumed: the
 * catch's only other arm is `setProblem(err.message)`, and that message is the batch wrapper's own
 * "Check your internet connection and try again", which is the wrong sentence for a 413 twice over.
 * Handing that caller an unwrapping `isTransient` would have made a refused capture disappear.
 *
 * So {@link isTransient} keeps the reading its callers were written against, and the drains — which
 * mark items, and where "retry for ever" is the jam this file exists to end — call
 * {@link underlyingIsTransient}. They are two readings of ONE table, and the spec walks both columns
 * of the matrix against both, which is the property the old pair of hand-written implementations
 * could not have. The right long-term shape is FieldInput's catch growing the drain's three-way one
 * (keep the bytes for unreachable/transient, and for a refusal stage the bytes ANYWAY and show the
 * server's sentence); that file belongs to another group and is not edited here.
 *
 * ── THE TABLE IS CODE, NOT PROSE ────────────────────────────────────────────────────────────────
 *
 * A table in a comment is exactly what this file replaces: the five implementations above all had
 * one, and all five were true when written. {@link FAILURE_TRIAGE} is the table, as data, and
 * `e2e/failure-triage-unit.spec.ts` walks it against real errors. A row nobody can reach and a row
 * whose behaviour has drifted both fail the sweep rather than sitting in a docstring being wrong.
 *
 * ── THE TWO AXES, WHICH ARE NOT ONE AXIS ────────────────────────────────────────────────────────
 *
 * {@link FailureKind} says what the failure IS. {@link RetryPolicy} says what to DO about trying it
 * again. Collapsing them is how a 408 gets mis-sorted for ever: it is the one status that IS the
 * connection failing (a proxy saying the request never completed, so the server decided nothing) AND
 * is worth retrying, whereas a 500 is worth retrying and is emphatically NOT a connection problem —
 * telling a designer their signal is at fault when the server answered sends them out of the
 * building to look for a better one and leaves a real bug wearing an offline message. That happened:
 * a saved page size 500'd because `ReportMeta` has no `__dict__`, and it was reported as offline.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * The vocabulary
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * WHAT THE FAILURE IS. Exactly one of these is true of any failure a request can produce, and the
 * test for each is in {@link triageFailure} — never restated anywhere else.
 */
export type FailureKind =
  /** Nothing reached a server, so nothing was decided. A dropped connection, DNS, an abort, a 408. */
  | "unreachable"
  /** A server answered, and the answer means "not now": 5xx, 429. It was reached; it is not broken here. */
  | "transient"
  /** 401. The credential is finished — not this item, and not the connection. */
  | "credential-expired"
  /** 422 naming a key the server does not know. This build and the server's disagree about the shape. */
  | "schema-drift"
  /** The server answered and refused, and only a change to the request can clear it. Other 4xx. */
  | "refused"
  /** THIS DEVICE refused before a request was made. No server was involved and no connection helps. */
  | "permanent";

/**
 * WHAT TO DO ABOUT TRYING AGAIN. Deliberately separate from {@link FailureKind} — see the header.
 */
export type RetryPolicy =
  /** Retry when a connection comes back. Costs nothing to keep; nothing was decided. */
  | "connection"
  /** Retry after a wait. The server was reached and asked for time, explicitly or by failing. */
  | "later"
  /** Nothing retries until a person signs in. Retrying sooner just spends a request to be refused. */
  | "sign-in"
  /** Retry once per app run: what clears it is an UPDATE to one of the two builds, not a person. */
  | "next-run"
  /** Only a change to the request, the file or the record clears it. Retrying is a lie to the user. */
  | "never";

/** What a background drain — the records outbox, the design-workshop draft store — must do. */
export type DrainAction =
  /** Stop the pass. Mark nothing, lose nothing, leave everything queued for the next connection. */
  | "stop-and-keep"
  /** Stop the pass, mark nothing, and ask for a sign-in. One credential, not twenty-two refusals. */
  | "stop-and-ask-for-sign-in"
  /** Record the reason against THIS item, carry on to the next. One bad record cannot strand the rest. */
  | "record-against-the-item"
  /** Record it and show it, but let the NEXT app run try again on its own. See `blocksRetry`. */
  | "record-but-retry-next-run";

/** What a screen somebody is looking at must say. The wrong sentence here is most of this file's history. */
export type ScreenAction =
  /** "You appear to be offline." Only ever when nothing reached a server. */
  | "say-offline"
  /** "The repository is not answering right now." The server was reached — do not blame their signal. */
  | "say-try-later"
  /** "Your sign-in has expired." Never a refusal of the work. */
  | "say-signed-out"
  /** Quote the server's own sentence: it names the field, the clash or the limit. */
  | "say-refused"
  /** "This app and the repository are out of step." Nothing the reader typed is wrong. */
  | "say-out-of-step"
  /** Name the file and what is wrong with it. No request was made, so there is nothing to blame. */
  | "say-unsendable";

export type TriageRow = {
  kind: FailureKind;
  retry: RetryPolicy;
  drain: DrainAction;
  screen: ScreenAction;
  /** One sentence saying what this kind IS — the thing the five old implementations each half-said. */
  meaning: string;
};

/**
 * THE TRIAGE TABLE. Every failure a request can produce, what it is, and what each surface does.
 *
 * Read it as the contract; read {@link triageFailure} for how an error is sorted into a row. Adding a
 * kind means adding a row here and a case there, and the spec will tell you if you did only one.
 */
export const FAILURE_TRIAGE: Readonly<Record<FailureKind, TriageRow>> = {
  unreachable: {
    kind: "unreachable",
    retry: "connection",
    drain: "stop-and-keep",
    screen: "say-offline",
    meaning:
      "Nothing reached a server: a dropped connection, DNS, an abort, or a 408 from a proxy saying the request " +
      "never completed. No decision was made about the work, so nothing may be marked."
  },
  transient: {
    kind: "transient",
    retry: "later",
    drain: "stop-and-keep",
    screen: "say-try-later",
    meaning:
      "A server answered and the answer means 'not now' — 5xx, or a 429 asking for time. The connection is fine " +
      "and must not be blamed; the work is kept exactly as it is."
  },
  "credential-expired": {
    kind: "credential-expired",
    retry: "sign-in",
    drain: "stop-and-ask-for-sign-in",
    screen: "say-signed-out",
    meaning:
      "401. The token is finished, not the item. Every queued item would fail this way and one sign-in fixes all " +
      "of them, so marking them individually is how a queue ends up offering only Discard."
  },
  "schema-drift": {
    kind: "schema-drift",
    retry: "next-run",
    drain: "record-but-retry-next-run",
    screen: "say-out-of-step",
    meaning:
      "422 naming a key the server does not know (`extra_forbidden`). This build and the server's disagree about " +
      "the shape of the request. Nobody typed anything wrong and no edit clears it — an update to either side does."
  },
  refused: {
    kind: "refused",
    retry: "never",
    drain: "record-against-the-item",
    screen: "say-refused",
    meaning:
      "The server answered and refused: a rejected field, a permission, a clash, a file it will not take. Retrying " +
      "it unchanged gets the same answer for ever, so a person has to decide something."
  },
  permanent: {
    kind: "permanent",
    retry: "never",
    drain: "record-against-the-item",
    screen: "say-unsendable",
    meaning:
      "This device refused before a request was made — today that is a 0-byte file. No server was involved, so no " +
      "connection and no server update can change it."
  }
};

/**
 * ── ANDROID DELIBERATELY DIFFERS, AND THE DIFFERENCE IS RECORDED RATHER THAN RECONCILED ─────────
 *
 * `WorkshopRepository.isTransient` (android/app/src/main/java/com/designprototype/workshop/data/
 * WorkshopRepository.kt) answers 401 with `true`, and `WorkshopSync.isConnectionFailure`
 * (data/WorkshopSync.kt) puts 401 on the connection's side of ITS line too. The web does neither:
 * here 401 is its own kind, {@link FailureKind} `credential-expired`, and {@link isTransient}
 * answers false for it.
 *
 * THIS IS NOT DRIFT AND MUST NOT BE "FIXED" IN EITHER DIRECTION. The two functions have different
 * blast radii. On Android `isTransient` is read by the outbox and by the design-workshop pass and by
 * nothing else, and both of them want the same thing from a 401 — keep the item, stop, ask for a
 * sign-in — so folding it in there is the shortest true statement. On the web `isTransient` is also
 * read by `saveOrQueue`, by the design-workshops list page and by `FieldInput`, where a 401 means
 * "you are signed out" and not "your signal dropped": widening it would start banking signed-out
 * saves into the outbox to be refused later, which is the defect one layer down. So the web asks the
 * question separately ({@link isCredentialExpiry}) and only the drains act on it. The OUTCOME is the
 * same on both platforms — the pass stops, nothing is marked, the banner asks for a sign-in — which
 * is the property that actually has to hold.
 *
 * Recorded here rather than in a comment on one function so that the next person comparing the two
 * platforms finds the argument instead of a discrepancy.
 */
export const ANDROID_DIVERGENCE = {
  status: 401,
  web: "credential-expired" satisfies FailureKind,
  android: "folded into WorkshopRepository.isTransient and WorkshopSync.isConnectionFailure",
  reason:
    "The web's isTransient is also read by saveOrQueue and by two interactive surfaces, where a 401 must not mean " +
    "'queue it and retry'. Android's has only the two drains as readers, which all want the same thing from a 401.",
  outcomeIsTheSame: "both platforms stop the pass, mark nothing, and ask for a sign-in"
} as const;

/* ────────────────────────────────────────────────────────────────────────────
 * The error types this side of the wire raises — declared HERE so they can be classified
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A refusal THIS DEVICE made, before any request went out.
 *
 * ── THE BARE `Error` THAT HAD NO CLASSIFIABLE TYPE ──────────────────────────────────────────────
 *
 * `lib/media.ts` refuses a 0-byte file — a capture the camera app never finished writing, a file
 * copied off a card that was pulled mid-write; not exotic on this hardware — before it presigns
 * anything. It did so with a bare `new Error`, and a bare `Error` is indistinguishable from a
 * `TypeError` out of `fetch` to every test downstream. The default for an unrecognised error is and
 * must remain "the connection is at fault", so an empty file classified as OFFLINE: the batch
 * escalation stopped the whole outbox pass as though the device had no signal, no failure was
 * recorded, no Discard was offered (Discard is drawn from a failure record), and the next connection
 * met the identical empty file. A jam that needed no server at all.
 *
 * `splitUnsendableFiles` in `lib/offline.ts` closed that for the outbox by separating empty files
 * BEFORE the call, which is still the right thing to do — not sending is better than sending and
 * being refused. But it only protects the callers that remember to call it, and it is the seventh
 * such guard in a repository whose whole problem is guards that some callers have. This type closes
 * the other half: an empty file that reaches an upload anyway now carries a type that says which of
 * the six kinds it is, from wherever it is caught, wrapped or not.
 */
export class LocalRefusalError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "LocalRefusalError";
  }
}

/**
 * A failure of the OBJECT-STORAGE leg — the direct PUT to S3/MinIO, which never goes through
 * `apiFetch` and therefore never produces an `ApiError`.
 *
 * ── THE WHOLE ERROR CLASS THAT USED TO FALL INTO THE DEFAULT ────────────────────────────────────
 *
 * `lib/media.ts` declared this next door with a real `status` on it, and nothing here could see it.
 * So an S3 403 on a signature the bucket policy will never accept, a 400, a 413 over the object size
 * limit all came out of `uploadWhole`/`uploadInParts`, got wrapped in a `MediaBatchError`, met no
 * `ApiError` and no {@link LocalRefusalError}, and fell to the default: `unreachable`. The outbox
 * drain then re-threw it for ever under "Still no connection" — the exact jam this module exists to
 * end, reached through the storage leg instead of the API leg. It was not a regression, which is why
 * it survived: it had simply never been classified, and `FailureKind`'s own docstring said otherwise.
 *
 * DECLARED HERE RATHER THAN IN `lib/media.ts` because that is the only way the classification can
 * read it: `media.ts` imports this module, so this module cannot import `media.ts`. A duck-typed
 * `error.name === "StorageError"` would have worked and would have been the seventh thing in this
 * repository that knows about a type without being able to name it.
 *
 * `status` IS NULL FOR EVERYTHING THAT IS NOT AN ANSWER — a transport error, a stall with no bytes
 * moving, a cancelled upload — and those are `unreachable`, correctly. See {@link kindForStorage}
 * for why the statuses that ARE present are trusted, which turns on the retries `media.ts` has
 * already spent before one of these escapes.
 */
export class StorageError extends Error {
  status: number | null;

  constructor(message: string, status: number | null = null) {
    super(message);
    this.name = "StorageError";
    this.status = status;
  }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The unwrap, which happens exactly once
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Bounded because `cause` is an ordinary property and nothing stops it pointing at its own error.
 * Eight is the depth the first unwrapping implementation chose; the real chains in this repository
 * are one deep (`MediaBatchError` wrapping the first per-file cause).
 */
const CAUSE_DEPTH = 8;

/**
 * The error the SERVER actually raised, dug out of whatever was re-thrown around it.
 *
 * `uploadMediaBatch` escalates a batch in which nothing landed as a `MediaBatchError` carrying the
 * first underlying error as `cause`, and both drains hand it ONE file at a time — so "the whole batch
 * failed" is precisely "the server refused this photograph". Asking the WRAPPER whether the server
 * answered gets "no" every time, which is how a 415 on a video came out looking like lost signal and
 * retried for ever, and how an expired credential came out looking like an ordinary refusal of a file.
 *
 * Returns the argument unchanged when there is nothing to unwrap, which is most errors. Stops at the
 * first `ApiError`: that is the answer the server gave, and anything it in turn wraps is decoration.
 *
 * Exported because a caller that needs to QUOTE the server's sentence needs the object, not a verdict
 * — see `lib/offline.ts`'s schema-drift branch. Nobody should be calling it to classify: the
 * classifiers below already unwrap, and a second `underlyingError(...)` around one of them is the
 * shape this whole file exists to end.
 */
export function underlyingError(error: unknown, depth = 0): unknown {
  if (error instanceof ApiError) return error;
  if (depth < CAUSE_DEPTH && error instanceof Error) {
    const cause = (error as { cause?: unknown }).cause;
    if (cause !== undefined && cause !== null) return underlyingError(cause, depth + 1);
  }
  return error;
}

/* ────────────────────────────────────────────────────────────────────────────
 * The one classification
 * ──────────────────────────────────────────────────────────────────────────── */

export type FailureVerdict = {
  kind: FailureKind;
  retry: RetryPolicy;
  drain: DrainAction;
  screen: ScreenAction;
  /**
   * The status that was answered — by the API ({@link answered}) or by object storage
   * ({@link StorageError}) — or null when nothing answered at all.
   */
  status: number | null;
  /**
   * The `ApiError` the API raised, unwrapped — null when no API answered. Quote its `message`.
   *
   * NULL FOR A STORAGE FAILURE EVEN WHEN {@link status} IS SET, on purpose: S3's body is XML nobody
   * here parses, so there is no sentence worth quoting to a designer. `status` is the whole of what
   * the storage leg can be believed about.
   */
  answered: ApiError | null;
  /** Whatever {@link underlyingError} found, for a caller that needs the object rather than the verdict. */
  underlying: unknown;
};

/**
 * `APIModel` is `extra="forbid"`, so a client that sends a key the server does not know gets a 422
 * whose body names it exactly:
 *
 *     {"detail":[{"type":"extra_forbidden","loc":["body","entries",0,"merge"], …}]}
 *
 * `type` is matched rather than the message, because the message is prose that may be reworded and
 * translated while the discriminator is part of pydantic's contract.
 *
 * IT IS NOT A HYPOTHETICAL. On 2026-08-08 a client sent the then-new `merge` flag to an API that
 * predated it, and every stage save came back refused with "merge: Extra inputs are not permitted" —
 * under a banner telling the designer to correct the answer that caused it. There was no such answer.
 * The clients and the server are deployed on different days by different people: a handset updates
 * when it next sees wifi, the API when somebody deploys it, so a client running ahead of the server
 * is an ordinary state here rather than a mistake, and it deserves a sentence that says so.
 */
function bodyNamesAnUnknownKey(error: ApiError): boolean {
  const detail = (error.payload as { detail?: unknown } | null | undefined)?.detail;
  if (!Array.isArray(detail)) return false;
  return detail.some((entry) => (entry as { type?: unknown } | null)?.type === "extra_forbidden");
}

/**
 * Which status means what. The ONLY place in the web client where an HTTP number becomes a decision.
 *
 * 401 IS ITS OWN KIND rather than a refusal — see {@link ANDROID_DIVERGENCE} for why the web splits it
 * out and Android folds it in.
 *
 * 408 IS `unreachable`, NOT `transient`: it is what a proxy answers when the request never completed,
 * so there is nothing the server can be said to have decided. It is retryable all the same, which is
 * why {@link RetryPolicy} is a second axis and not a synonym for the kind.
 *
 * 429 IS `transient`: the server was reached and asked for time, explicitly. Reporting that as "you
 * appear to be offline" is the same lie as reporting a 500 that way.
 *
 * EVERYTHING ELSE THE SERVER ANSWERED IS `refused`. That is deliberately the default rather than a
 * list: an unlisted status is a decision somebody made on the server, and treating an unknown
 * decision as retryable is how a queue replays a rejection until somebody clears browser storage.
 * `ApiUnconfiguredError` is the one deliberate rider — it extends `ApiError` with status 503 and no
 * response behind it, so it lands on `transient` and queued work stays queued while the person in
 * front of the screen is still told. That is what `lib/api.ts` chose 503 for.
 */
function kindForStatus(error: ApiError): FailureKind {
  const { status } = error;
  if (status === 401) return "credential-expired";
  if (status === 408) return "unreachable";
  if (status === 429) return "transient";
  if (status >= 500) return "transient";
  if (status === 422 && bodyNamesAnUnknownKey(error)) return "schema-drift";
  return "refused";
}

/**
 * The same question for the OBJECT-STORAGE leg, which is a different server with a different vocabulary.
 *
 * NOT `kindForStatus`, deliberately, and the two statuses it must not share are the reason:
 *
 *   • 401 IS NOT `credential-expired` HERE. This app's session has nothing to do with a bucket; a
 *     sign-in prompt would be an instruction that cannot help, on the one screen where an instruction
 *     that cannot help is what jams the queue. S3 answers 403 for a signature it will not accept
 *     anyway, and 403 is a refusal.
 *   • 422 IS NOT `schema-drift` HERE. Drift means "this build and the API disagree about a key", and
 *     object storage is not sent a body this client composes.
 *
 * A PRESENT STATUS IS TRUSTED, AND THAT TURNS ON WHAT `lib/media.ts` HAS ALREADY SPENT. `uploadWhole`
 * takes a FRESH presign on each of its three attempts and `uploadInParts` re-signs the individual
 * part on a 403 — so the expired-signature 403, the one case where trusting the status would be
 * wrong, has already been retried with a new signature before any of these can escape. A 403 that
 * survives that is a policy, not a stale URL, and recording it against the photograph is what stops
 * the pass coming back to it on every connection for the rest of the fortnight.
 *
 * A NULL STATUS IS `unreachable` AND MUST BE: a transport error, a stall with no bytes moving for
 * ninety seconds, a cancelled upload. Nothing was decided about the object, so nothing may be marked.
 */
function kindForStorage(error: StorageError): FailureKind {
  const { status } = error;
  if (status === null) return "unreachable";
  if (status === 408) return "unreachable";
  if (status === 429 || status >= 500) return "transient";
  return "refused";
}

/**
 * Sort one thrown value into exactly one row of {@link FAILURE_TRIAGE}.
 *
 * THE `cause` CHAIN IS FOLLOWED HERE AND NOWHERE ELSE. Every predicate below is a reading of this
 * verdict, so they cannot disagree about a wrapper the way `isTransient` and `isUnreachable` did.
 *
 * THE DEFAULT IS `unreachable`, AND IT HAS TO BE. A `TypeError` out of `fetch`, a rejected abort, a
 * DNS failure and a thrown string all arrive with nothing to inspect, and the safe reading of "we
 * cannot tell" is "the work is still ours to keep". The three things that used to fall into that
 * default WRONGLY are all typed now: a server refusal wrapped in a `MediaBatchError` (unwrapped
 * above), a file this device refused ({@link LocalRefusalError}), and an object-storage refusal
 * ({@link StorageError}, which carried a status all along and had nowhere to declare it).
 */
export function triageFailure(error: unknown): FailureVerdict {
  return verdictFor(underlyingError(error));
}

/**
 * The same table, asked of the value EXACTLY AS IT WAS THROWN — the `cause` chain is not followed.
 *
 * THE CONSERVATIVE READING, AND IT HAS ONE JOB. A caller that treats "not retryable" as permission to
 * DISCARD the work must not be handed a verdict about an error it never saw. That caller exists and
 * is measured: `components/designworkshop/FieldInput.tsx:1874` (see this file's header). For it, an
 * unrecognised wrapper has to go on meaning "we cannot tell, so keep the bytes" — the same default
 * {@link triageFailure} applies to a `TypeError`, applied one level higher.
 *
 * IT IS NOT A SECOND IMPLEMENTATION, which is the thing this module exists to prevent: both verdicts
 * come out of {@link verdictFor} and the same {@link FAILURE_TRIAGE} rows, and they can differ ONLY
 * in whether a wrapper was opened first. Nothing about a bare `ApiError`, a `StorageError`, a
 * `LocalRefusalError` or a `TypeError` can read differently between them.
 */
export function triageAsThrown(error: unknown): FailureVerdict {
  return verdictFor(error);
}

/** The row lookup, given a value that has already been unwrapped as far as it is going to be. */
function verdictFor(resolved: unknown): FailureVerdict {
  const answered = resolved instanceof ApiError ? resolved : null;
  const storage = resolved instanceof StorageError ? resolved : null;
  const kind: FailureKind = answered
    ? kindForStatus(answered)
    : storage
      ? kindForStorage(storage)
      : resolved instanceof LocalRefusalError
        ? "permanent"
        : "unreachable";
  const row = FAILURE_TRIAGE[kind];
  return {
    kind,
    retry: row.retry,
    drain: row.drain,
    screen: row.screen,
    status: answered?.status ?? storage?.status ?? null,
    answered,
    underlying: resolved
  };
}

/* ────────────────────────────────────────────────────────────────────────────
 * The named readings — what the rest of the client actually calls
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Did this failure happen because nothing reached the server at all?
 *
 * THE QUESTION ANY SCREEN THAT SAYS "OFFLINE" MUST ASK, and not the same one as {@link isTransient}.
 * A 5xx means the server was reached and then failed: telling a designer their signal is at fault
 * when the server answered sends them out of the building to look for a better one and leaves a real
 * bug wearing an offline message.
 */
export function isUnreachable(error: unknown): boolean {
  return triageFailure(error).kind === "unreachable";
}

/**
 * Will trying this again help — judged on the failure EXACTLY AS IT WAS THROWN?
 *
 * The question with the most readers: `saveOrQueue`, the design-workshops list page, `placeSearch`,
 * and `FieldInput`'s media catch. `connection` and `later` are both yes; they differ in what a screen
 * may SAY, not in whether the work is kept.
 *
 * ── WHY THIS ONE DOES NOT FOLLOW `cause`, AND {@link underlyingIsTransient} DOES ─────────────────
 *
 * Because of what one caller DOES with a false. `components/designworkshop/FieldInput.tsx:1874` is
 * `if (isTransient(err))` in the catch of `uploadMediaBatch`, so it is handed a `MediaBatchError` and
 * nothing else — and a true there runs `stageOffline(chosen)`, which is the only thing that keeps the
 * captured bytes: the drain effect twenty lines below has already removed those files from the
 * capture card (`setPending(... filter ...)`) and added them to `claimedRef`. A false falls to
 * `setProblem(err.message)` and the photograph is gone from every surface, under the batch wrapper's
 * own sentence — "Check your internet connection and try again — the record was saved, so re-open it
 * and re-attach the media" — which is wrong in both clauses for a 413 and is the one sentence this
 * repository's headers repeatedly say must not be shown when the server answered.
 *
 * So an unwrapping `isTransient` would have made a server-refused capture DISAPPEAR. That is a worse
 * defect than the one it fixes, and it lands in a file this group does not own. The honest split is
 * the one below: this predicate answers for the callers that read it as custody of the work, and the
 * drains — which mark items and where "retry for ever" is the actual jam — ask
 * {@link underlyingIsTransient}. Both are readings of one table (see {@link triageAsThrown}); they
 * are pinned side by side, on both columns of the matrix, in `e2e/failure-triage-unit.spec.ts`.
 *
 * WHEN FIELDINPUT'S CATCH GROWS THE DRAIN'S THREE-WAY SHAPE — keep the bytes for unreachable and
 * transient, and for a refusal keep the bytes ANYWAY and show the server's sentence — this predicate
 * should become {@link underlyingIsTransient} and the pair should collapse back to one. Until then,
 * collapsing them silently discards captures.
 *
 * 401 IS NOT IN THIS SET, deliberately, and that is where Android differs — see
 * {@link ANDROID_DIVERGENCE}. `saveOrQueue` would otherwise start banking signed-out saves.
 */
export function isTransient(error: unknown): boolean {
  const { retry } = triageAsThrown(error);
  return retry === "connection" || retry === "later";
}

/**
 * Will trying this again help — judged on what the wrapper is WRAPPING?
 *
 * THE DRAINS' QUESTION, and the root of the class this module closes. `uploadMediaBatch` escalates a
 * batch in which nothing landed as a `MediaBatchError`, and both drains hand it ONE file at a time,
 * so "the whole batch failed" is precisely "the server refused this photograph". Asked as thrown, a
 * 415 on a video answered "yes, worth retrying" — the pass broke as if the device were offline, no
 * failure was recorded, no Discard was offered, and every entry behind it never drained.
 *
 * Use this wherever a false MARKS an item and a true KEEPS it queued. Do not use it where a false
 * throws work away: see {@link isTransient} for the one caller that does, and why.
 */
export function underlyingIsTransient(error: unknown): boolean {
  const { retry } = triageFailure(error);
  return retry === "connection" || retry === "later";
}

/**
 * Did the server refuse the SHAPE of the request rather than anything a person typed?
 *
 * RETURNS A BOOLEAN AND NOT A TYPE GUARD, which it used to. `error is ApiError` was only true while
 * this function refused to unwrap: now that a dialect mismatch met while uploading a photograph is
 * recognised through its `MediaBatchError`, narrowing the ARGUMENT to `ApiError` would be a lie the
 * compiler enforces. A caller that needs the server's own sentence asks {@link schemaRefusalError},
 * which hands back the object it may quote.
 */
export function isSchemaRefusal(error: unknown): boolean {
  return triageFailure(error).kind === "schema-drift";
}

/**
 * The `ApiError` behind a schema drift, for a caller that has to quote its sentence — null otherwise.
 *
 * The sentence matters: it names the key the two builds disagree about, which is the one piece of
 * information that tells whoever runs the repository what to update.
 */
export function schemaRefusalError(error: unknown): ApiError | null {
  const verdict = triageFailure(error);
  return verdict.kind === "schema-drift" ? verdict.answered : null;
}

/**
 * Did this fail because the CREDENTIAL is finished rather than because anything is wrong with the item?
 *
 * The token this app issues lasts seven days, which is shorter than the fortnight the outbox is built
 * for; and a second tab signing out clears the token under this one's feet. Either way EVERY
 * remaining item gets the same 401, and marking each one refused left a researcher with a queue whose
 * only offered control was Discard — the button that deletes the record and its photographs.
 */
export function isCredentialExpiry(error: unknown): boolean {
  return triageFailure(error).kind === "credential-expired";
}

/**
 * Did the server explicitly ask for time — 408 or 429 — rather than refuse?
 *
 * The fifth opinion, given a name. `lib/designWorkshopStore.ts` had this written out inline twice as
 * `error instanceof ApiError && (error.status === 408 || error.status === 429)`, once in the
 * pass-level catch and once in `mediaRefusal`, which is exactly the shape that lets two copies drift.
 *
 * It is NOT `retry === "later"`: a 500 is worth retrying and is not the server asking for time, and
 * the drain's distinction between "record this against the file" and "leave the file alone" turns on
 * the narrower reading.
 */
export function serverAskedForTime(error: unknown): boolean {
  const { status } = triageFailure(error);
  return status === 408 || status === 429;
}
