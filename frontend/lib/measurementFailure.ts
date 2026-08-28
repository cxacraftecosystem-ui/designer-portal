import { ApiUnconfiguredError } from "@/lib/api";
import { serverSentence, triageFailure } from "@/lib/failureTriage";
import { MeasurementTimeoutError, type MeasurementAnalysisResponse } from "@/lib/media";

/**
 * WHICH WAY `POST /media/analyze-measurement` FAILED — asked once, for the whole web client.
 *
 * ── THE DISTINCTION THE SERVER PAID FOR AND THE CLIENT KEPT SPENDING ────────────────────────────
 *
 * `media.py`'s route docstring is unusually explicit about why this endpoint has the status codes it
 * has. An unconfigured provider used to answer `200` with `available: false`, "which a client cannot
 * tell from 'the grid was unreadable' — so a researcher re-photographs an object in better light for
 * ever while the real answer is that nobody has set GEMINI_API_KEY". The route now raises **503, with
 * the setting named in the sentence**, so that the difference survives the wire.
 *
 * It did not survive the client. Three separate collapses were live on 2026-08-27, and each one ended
 * with a designer being told to do something that could not possibly help:
 *
 *  1. `gridFailureStatus` printed `ApiError.message` for EVERY status the server answered with. That
 *     is right whenever the server put words in the reply and wrong whenever it did not — `apiFetch`
 *     builds the message as `describeApiDetail(detail, response.statusText || "The server refused the
 *     request (HTTP ${status}).")` and `statusText` is EMPTY over HTTP/2, which every deployed request
 *     is. So a body-less 503 from a gateway during a deploy window reached the screen as the literal
 *     string "The server refused the request (HTTP 503)." — a status code wearing a sentence, under a
 *     comment promising that this branch shows the server naming the missing key.
 *  2. `readGridAnalysis` sorted a `200` with `status: "FAILED"` into "Couldn't read a value — enter it
 *     manually". That body is what `analyze_measurement_image_bytes` returns when it catches a
 *     `requests.RequestException` FROM THE PROVIDER — a rate limit, a 500 at Google, a DNS failure on
 *     the server's side — and it carries a sentence saying so. The route returns it as 200 on purpose
 *     ("the provider was reachable and answered … without telling a researcher that the server is
 *     broken when it is working exactly as designed"), and the client turned that into an instruction
 *     to re-photograph a perfectly good object. This is the SAME defect as the one the 503 closed,
 *     reached through the other door.
 *  3. Nothing ever timed out. A connection that opens and then stalls — a captive portal, a lift, a
 *     tower handover halfway through an 8 MB upload — does not reject `fetch`, so the status line said
 *     "Analyzing…" until the designer navigated away.
 *
 * ── THE RULE THIS FOLLOWS, WHICH IS NOT NEW ─────────────────────────────────────────────────────
 *
 * **THE SERVER'S OWN SENTENCE IS PRINTED VERBATIM WHEREVER THE SERVER SPOKE, AND NO SETTING NAME IS
 * EVER RECONSTRUCTED ON THIS SIDE.** `ai.py`'s `_verb_unavailable` is the established shape — "NAMES
 * THE SETTING, ALWAYS. The designer cannot fix it and the administrator can" — and `aiLayerProblem`
 * is the established web reading of it: quote the reply wherever it carried words, and fall back to a
 * sentence written here only where it carried none. Guessing `GEMINI_API_KEY` in this file would be a
 * bundle asserting a server fact it cannot see; the day the measurement provider changes, the screen
 * would send an administrator to a setting that no longer exists.
 *
 * {@link serverSentence} is what makes "wherever the server spoke" decidable, and it is the whole of
 * the fix for collapse 1. It is in `lib/failureTriage.ts` beside the classification rather than here,
 * because it is not a fact about measurement — every surface in this client that quotes a refusal has
 * the same problem.
 *
 * ── WHY A STATUS IS READ HERE AT ALL, WHEN `lib/failureTriage.ts` ALREADY SORTED IT ─────────────
 *
 * The triage is right and is not being second-guessed. For the OUTBOX every 5xx is `transient`: the
 * server was reached, the work is kept, nothing may be marked, and a 503 in a deploy window drains by
 * itself. That is a statement about custody of queued work, and it is correct.
 *
 * THIS ROUTE HAS NO QUEUE. It is not in `ENQUEUEABLE_PROCESSING_REQUESTS`, there is no outbox entry
 * behind it and nothing retries it — a person is standing in front of the screen holding the object.
 * And on this one route the server gave 503 a second, narrower meaning it carries nowhere else in the
 * API: *this deployment has no vision provider*, which no amount of waiting clears. So this module
 * reads `verdict.status` OFF THE SHARED VERDICT rather than re-deciding what a 5xx is. That spelling
 * is the remedy `e2e/failure-triage-unit.spec.ts` explicitly blesses — "`verdict.status` IS the shared
 * classifier's answer being read back — that is the remedy, not the defect" — which is also why it is
 * written as `verdict.status` throughout and never destructured into a bare `status`.
 *
 * ── THE EDGE-COMPUTE SPLIT, SAID HONESTLY ───────────────────────────────────────────────────────
 *
 * `lib/photoMeasure.ts` is this app's DEFAULT measurement path and it runs entirely on the device —
 * plane projective geometry over marked points, no `fetch`, no model, and it works in a courtyard
 * with no signal. Its Kotlin port says so of itself in one line. THIS route is the exception: a
 * vision-model call, inherently server-backed. Nothing here pretends otherwise, `GRID_SECTION_HINT`
 * says so before the capture rather than after it, and **every fallback sentence below ends at
 * something the designer can do with no connection at all**, because there always is one.
 *
 * ── THE GUARANTEE THE TYPE CARRIES ──────────────────────────────────────────────────────────────
 *
 * {@link MeasurementRemedy} has no "take another photograph" value, and its absence is the point.
 * NOTHING THIS MODULE CAN PRODUCE IS A JUDGEMENT ABOUT WHAT IS IN THE PICTURE — its lighting, its
 * framing, whether the grid is visible. Every verdict is the deployment, the connection, or the
 * request as a request. The one genuine "the model looked and could not read it" outcome is a 2xx
 * with no numbers in it; it is `readGridAnalysis`'s to name and it keeps Android's sentence. So "the
 * unconfigured case must not read as the designer's fault or as a broken photograph" is enforced by
 * the compiler here rather than by a reviewer noticing.
 *
 * (`unsendable` is the near miss and is not an exception: a camera that did not finish writing a file
 * produced no photograph to criticise, so its remedy is `a-different-request` — the same one a 413
 * gets — and its sentence names the file rather than the picture.)
 */

/* ────────────────────────────────────────────────────────────────────────────
 * The vocabulary
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * WHO OR WHAT CLEARS THIS. A second axis, for `lib/failureTriage.ts`'s reason: the kind says what
 * happened and this says what to do about it, and collapsing the two is how six different causes came
 * to share eight words. A caller may branch on this without knowing the kinds.
 *
 * THERE IS DELIBERATELY NO "another-photograph" VALUE — see the module header.
 */
export type MeasurementRemedy =
  /** Nobody in the room can clear it. A setting, a key or a deploy, by whoever runs the server. */
  | "an-administrator"
  /** Reconnect and ask again. Nothing reached the server, so nothing was decided and nothing is owed. */
  | "a-connection"
  /** The same request, unchanged, may well work in a minute. The server was reached and did not refuse. */
  | "trying-again"
  /** The server read the request and said no. The file, or the account, has to be different. */
  | "a-different-request";

/** Which way the call failed. Exactly one is true of any failure. */
export type MeasurementFailureKind =
  /** 503, or a legacy `200`/`available: false` — this deployment has no vision provider configured. */
  | "provider-unconfigured"
  /** `200` with `status: "FAILED"` — the provider was reached and IT failed. Not the photograph. */
  | "provider-failed"
  /** This BUILD has no API address ({@link ApiUnconfiguredError}); no request was ever made. */
  | "app-unconfigured"
  /** Nothing reached the server. The only kind that may put the word "connection" on the screen. */
  | "offline"
  /** The wait ran out — this client's ({@link MeasurementTimeoutError}) or a proxy's 408. */
  | "timed-out"
  /** The server read the request and refused it: 413 too large, 415 wrong type, 403 not permitted. */
  | "refused"
  /** The server was reached and broke: a 5xx that is not the configuration 503. Not the photograph. */
  | "server-failed"
  /** THIS DEVICE refused the file before sending it — a `LocalRefusalError`, today a 0-byte capture. */
  | "unsendable";

export type MeasurementFailure = {
  kind: MeasurementFailureKind;
  /** What to put on the screen. A whole sentence already; never decorate it with a status code. */
  sentence: string;
  remedy: MeasurementRemedy;
  /**
   * Are these the SERVER's words rather than words written in this file?
   *
   * Carried rather than assumed, because "quote the server" is only a real rule if a caller — and a
   * test — can tell when it was kept. `false` means the reply put no usable words behind its answer
   * and the fallback below was used, which names no setting, no provider and no status code.
   */
  serverSaidIt: boolean;
  /** What the server answered with, or null when nothing answered. For logs and tests, not for screens. */
  status: number | null;
};

/* ────────────────────────────────────────────────────────────────────────────
 * The sentences written on THIS side — used only where the reply carried none
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * NOT ONE OF THESE NAMES A SETTING, A PROVIDER OR A STATUS CODE, and that is a constraint rather than
 * a style. They are for a reply that carried no words at all — a gateway's 503, a proxy's 408, a
 * `TypeError` out of `fetch` — where there is nothing to quote. The moment one starts guessing at
 * `GEMINI_API_KEY` it is asserting a server fact this bundle cannot see.
 *
 * NONE OF THEM ASKS FOR ANOTHER PHOTOGRAPH, for the reason in the module header, and each ends at
 * something that works with no connection.
 *
 * They are the web's own words rather than Android's because Android has no string for any of these
 * states: `GridMeasurementSection` in `MainActivity.kt` collapses every one of them into "Analysis
 * failed — enter it manually". §1.3 of the frontend reference asks for Android's wording where Android
 * HAS wording; where it has none, copying is impossible. That divergence is one to close on the
 * handset — see this file's entry in the group's follow-ups — not a licence to invent freely.
 */
export const MEASUREMENT_FALLBACKS: Readonly<Record<MeasurementFailureKind, string>> = {
  "provider-unconfigured":
    "Reading a photograph is not switched on for this repository, so nothing here can measure the object for " +
    "you. Your photograph is fine and there is nothing to re-take — whoever administers the server can turn it " +
    "on, and nothing on this device can. Measure the object and enter the value manually meanwhile.",
  "provider-failed":
    "The service that reads these photographs was reached and did not answer usefully, so there is no " +
    "measurement to show. Nothing is wrong with your photograph. Try again in a minute, or measure the object " +
    "and enter the value manually.",
  "app-unconfigured":
    "This site was published without the address of its data service, so no photograph can be sent anywhere to " +
    "be read. An administrator needs to redeploy it. Measure the object and enter the value manually meanwhile.",
  offline:
    "No connection — reading the photo needs one, and nothing reached the server. Nothing has been queued: a " +
    "reading nobody has checked is not something to bank for later. Measure the object and enter the value " +
    "manually, or try again in signal.",
  "timed-out":
    "The photo was sent but no answer came back before the wait ran out, so there is no measurement to show and " +
    "nothing was written anywhere. Try again on a steadier connection, or measure the object and enter the " +
    "value manually.",
  refused:
    "The server would not accept this request and did not say why. Try a smaller JPEG or PNG straight from the " +
    "camera, or measure the object and enter the value manually.",
  // WORDED TO BE TRUE OF A 429 AS WELL AS A 5xx, which is why it says "busy or briefly out of order"
  // rather than "failed". `verdict.kind === "transient"` covers both — the server was reached and
  // asked for time, explicitly or by falling over — and one more kind for the difference would be a
  // row whose only distinction is a word nobody acts on differently.
  "server-failed":
    "The server was reached and did not read the photo — it is busy or briefly out of order. Nothing is wrong " +
    "with your photograph and nothing was written anywhere. Try again in a minute, or measure the object and " +
    "enter the value manually.",
  unsendable:
    "This photo could not be sent to be read — the file itself is unusable, which usually means the camera did " +
    "not finish writing it. Take the photo again, or measure the object and enter the value manually."
};

/**
 * What clears each kind. A table rather than a chain of `if`s, so a new kind cannot be added without
 * an answer — the compiler asks for the row.
 */
const REMEDY: Readonly<Record<MeasurementFailureKind, MeasurementRemedy>> = {
  "provider-unconfigured": "an-administrator",
  "app-unconfigured": "an-administrator",
  offline: "a-connection",
  "timed-out": "a-connection",
  "provider-failed": "trying-again",
  "server-failed": "trying-again",
  refused: "a-different-request",
  // The FILE has to be different, which is the same remedy as a 413 or a 415 and is deliberately not
  // a judgement about what is IN the picture — see the header. A camera that did not finish writing
  // produced no photograph to criticise.
  unsendable: "a-different-request"
};

/**
 * Build the one failure a caller shows.
 *
 * The choice between the server's words and ours is made ONCE, here, so no branch below can forget to
 * prefer the server's. `said` is null exactly when the reply carried nothing usable.
 */
function failure(kind: MeasurementFailureKind, said: string | null, status: number | null): MeasurementFailure {
  return { kind, sentence: said ?? MEASUREMENT_FALLBACKS[kind], remedy: REMEDY[kind], serverSaidIt: said !== null, status };
}

/**
 * The same, for a sentence that came from THIS DEVICE rather than from a server.
 *
 * A SEPARATE FUNCTION SO THAT `serverSaidIt` CANNOT BE SET BY ACCIDENT. Two of the failures here carry
 * a real, specific, already-written sentence that no server sent: `ApiUnconfiguredError`'s (composed
 * in `lib/api.ts`, and about a redeploy) and `LocalRefusalError`'s (composed in `lib/media.ts`, and
 * about the file). Passing either through {@link failure} would show the right words under a flag
 * claiming the server had said them — and `serverSaidIt` exists precisely so a test can hold this
 * module to "quote the server". A field that is sometimes a guess is worse than no field.
 */
function localFailure(kind: MeasurementFailureKind, sentence: string | null, status: number | null): MeasurementFailure {
  return { kind, sentence: sentence ?? MEASUREMENT_FALLBACKS[kind], remedy: REMEDY[kind], serverSaidIt: false, status };
}

/** A non-blank string, trimmed, or null. Blank prose is the absence of a sentence, not a sentence. */
function words(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

/* ────────────────────────────────────────────────────────────────────────────
 * A 2xx that is a failure anyway
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Is this `200` body a FAILURE rather than an answer to be read for numbers? Null when it is an answer.
 *
 * TWO BODIES ARRIVE WITH A 200 AND NEITHER IS ABOUT THE PHOTOGRAPH:
 *
 *  - **`available: false`** is what this route did BEFORE the 503 landed. Unreachable against a
 *    current deployment and kept all the same, because a web build outlives a backend deploy and the
 *    clients and the API are deployed on different days by different people — the ordinary state that
 *    `lib/failureTriage.ts` gives schema drift its own sentence for. The message it carries is the one
 *    that names the missing key. Dropping this arm puts the feature back exactly where it started,
 *    with "unconfigured" wearing "unreadable"'s clothes, for every designer on an older server.
 *  - **`status: "FAILED"`** is `analyze_measurement_image_bytes` catching a `requests.RequestException`
 *    from the provider: `available: true`, `analysis: null`, and a message naming the fault. The route
 *    passes it through as 200 deliberately — the server is working exactly as designed and saying so.
 *    It is NOT an unreadable grid, and until this arm existed it was shown as one.
 *
 * A `FAILED` BODY IS CHECKED BEFORE THE NUMBERS AND NOT AFTER, on purpose: `analysis` is `null` on
 * that path, so a reader that looks for readings first finds none and reaches the unreadable branch
 * before anything has asked why. Order is the whole fix.
 */
export function measurementBodyFailure(response: MeasurementAnalysisResponse): MeasurementFailure | null {
  const said = words(response.message);
  if (response.available === false) return failure("provider-unconfigured", said, 200);
  if (response.status === "FAILED") return failure("provider-failed", said, 200);
  return null;
}

/* ────────────────────────────────────────────────────────────────────────────
 * A thrown value
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Sort a thrown value into exactly one failure.
 *
 * THE ORDER IS LOAD-BEARING, AND IT IS `aiLayerProblem`'S, for the reasons it gives:
 *
 *  1. {@link ApiUnconfiguredError} FIRST, before anything looks at a status. It is a 503 that no
 *     server ever sent — the request was never made — and its own message names an administrator
 *     action (redeploy the site with its API address) that has nothing to do with a vision provider.
 *     Read as the provider 503 it would send an operator to add a key that changes nothing. It would
 *     also fall through the `verdict.answered` branch below, since it IS an `ApiError` with status
 *     503; the explicit guard is what keeps the two apart, and it is written out rather than relying
 *     on ordering so that the separation is visibly deliberate.
 *  2. {@link MeasurementTimeoutError} next. A FLAG FROM THE CALLER, NOT A PATTERN-MATCHED ERROR NAME:
 *     an aborted `fetch` rejects with a `DOMException` whose `name` is `"AbortError"` or
 *     `"TimeoutError"` depending on the runtime and on how the signal was made, and a CALLER's abort
 *     (the designer replaced the photograph, the component unmounted) is indistinguishable from a
 *     timeout in that string. Only the layer that started the clock knows which happened, so
 *     `analyzeMeasurementImage` says so with a type. A caller's own abort is not a timeout, is not
 *     reported as one, and lands on `offline` — where it belongs, since nothing was decided.
 *  3. Then the server's answer, if there was one.
 *  4. Then the shared triage's default, which is and must remain "nothing reached a server".
 *
 * A 401 LANDS ON `refused` AND THAT IS ACCEPTABLE RATHER THAN UNCONSIDERED. `apiFetch` clears the
 * token and `location.replace("/login")`s before it throws, so the sentence produced here is on a
 * screen that is already being replaced; the sentence itself is the server's own ("Could not validate
 * credentials"), which is true. The one path where no redirect happens is a request that carried no
 * token at all, and `AppShell` does not render a product form for a signed-out visitor. It is written
 * down rather than given a kind because a kind nothing can reach is a row that rots.
 */
export function classifyMeasurementFailure(error: unknown): MeasurementFailure {
  if (error instanceof ApiUnconfiguredError) return localFailure("app-unconfigured", words(error.message), null);
  if (error instanceof MeasurementTimeoutError) return localFailure("timed-out", null, null);

  const verdict = triageFailure(error);
  const said = serverSentence(error);

  // THIS DEVICE REFUSED, so no request was made and no connection can help. Its own message names the
  // file — `lib/failureTriage.ts` gave `LocalRefusalError` a type for exactly this reason: a bare
  // `Error` is indistinguishable from a `TypeError` out of `fetch`, and an empty capture reported as
  // "you appear to be offline" sends a designer looking for signal over a file the camera never wrote.
  if (verdict.kind === "permanent") {
    return localFailure("unsendable", verdict.underlying instanceof Error ? words(verdict.underlying.message) : null, null);
  }

  if (verdict.answered) {
    // READ OFF THE SHARED VERDICT, NEVER OFF A RAW `error.status` — the header says why this route
    // narrows a 5xx at all, and why the spelling is exactly `verdict.status`.
    if (verdict.status === 503) return failure("provider-unconfigured", said, verdict.status);
    // A proxy saying the request never completed. `unreachable` to the triage, correctly — nothing was
    // decided — but a designer needs the two apart: "you have no connection" sends them out of the
    // building, and "the connection is there and too slow for an 8 MB photograph" does not.
    if (verdict.status === 408) return failure("timed-out", said, verdict.status);
    if (verdict.kind === "transient") return failure("server-failed", said, verdict.status);
    // Everything else the server DECIDED: 413 over the 8 MB ceiling, 415 on a file it cannot decode,
    // 422 on an unknown dimension, 403 below `require_record_creator`. Each already carries a sentence
    // naming the limit, the type or the permission, which is the whole reason to quote rather than
    // summarise.
    return failure("refused", said, verdict.status);
  }

  return failure("offline", null, verdict.status);
}
