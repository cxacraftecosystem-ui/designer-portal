import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { ApiError, ApiUnconfiguredError } from "@/lib/api";
import { LocalRefusalError, serverSentence } from "@/lib/failureTriage";
import {
  MEASUREMENT_TIMEOUT_MS,
  MeasurementTimeoutError,
  MediaBatchError,
  analyzeMeasurementImage
} from "@/lib/media";
import {
  MEASUREMENT_FALLBACKS,
  classifyMeasurementFailure,
  measurementBodyFailure,
  type MeasurementFailureKind
} from "@/lib/measurementFailure";

/**
 * "NOBODY CONFIGURED A VISION PROVIDER" IS NOT "YOUR PHOTOGRAPH IS UNREADABLE", AND THE CLIENT MUST
 * SAY SO.
 *
 * ── WHAT THIS FILE GUARDS ───────────────────────────────────────────────────────────────────────
 *
 * `POST /media/analyze-measurement` used to answer `200` with `available: false` when the deployment
 * had no Gemini key, and `media.py`'s own docstring records what that cost: "a researcher
 * re-photographs an object in better light for ever while the real answer is that nobody has set
 * GEMINI_API_KEY". The route now raises **503 with the setting named in the sentence** so the
 * difference survives the wire. The web client then collapsed it back:
 *
 *  - `gridFailureStatus` printed `ApiError.message` for every status the server answered with, which
 *    is `apiFetch`'s FABRICATED "The server refused the request (HTTP 503)." whenever the reply
 *    carried no `detail` — and `statusText` is empty over HTTP/2, so that is every body-less answer on
 *    every deployment;
 *  - `readGridAnalysis` sorted a `200` with `status: "FAILED"` — the provider was reached and IT
 *    failed, with a sentence saying so — into "Couldn't read a value — enter it manually";
 *  - nothing timed out at all, so a stalled connection left "Analyzing…" on screen for ever.
 *
 * The assertions below are on the classifier, not on a browser: `lib/measurementFailure.ts` is where
 * the decision is made, and it is decidable without one.
 *
 * ── THE TWO CLAIMS ──────────────────────────────────────────────────────────────────────────────
 *
 *  1. Every distinct failure gets a DISTINCT sentence, and the branch that picks it is the one the
 *     server's status says it should be.
 *  2. The server's own words are printed WHEREVER the server spoke, and a setting name is NEVER
 *     invented on this side — which is `ai.py`'s `_verb_unavailable` contract read from the other end.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Fixtures — errors shaped exactly as `apiFetch` builds them
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * `apiFetch` sets `message = describeApiDetail(detail, …)` AND `payload = body`. Both halves matter:
 * the message is what a caller would print and the payload is the only place "the server actually put
 * a sentence here" is visible. A fixture that sets one and not the other cannot exercise the rule.
 */
function answered(status: number, detail: string): ApiError {
  return new ApiError(status, detail, { detail });
}

/**
 * What a reply with NO `detail` produces. A gateway 503 in a deploy window, a proxy's 502 page, an
 * nginx 504 — none of them carries a FastAPI body, `statusText` is empty over HTTP/2, and `apiFetch`
 * therefore composes the message itself. This is the fixture the previous test could not build.
 */
function bodyless(status: number): ApiError {
  return new ApiError(status, `The server refused the request (HTTP ${status}).`, null);
}

/** The sentence `analyze_measurement_image_bytes` actually sends when no key is configured. */
const UNCONFIGURED =
  "Grid measurement is unavailable because no Gemini API key is configured. Measure the object and " +
  "type the value in, or ask whoever administers the server to add GEMINI_API_KEY in the Settings hub.";

/** The sentence it sends when the PROVIDER was reached and refused — a 200 with `status: "FAILED"`. */
const PROVIDER_FAULT =
  "Measurement analysis failed (HTTP 429); measure the object and enter the value manually. The " +
  "provider's reply is in the server log.";

const REPO = join(__dirname, "..", "..");

/* ────────────────────────────────────────────────────────────────────────────
 * 1. Every branch, and the status that selects it
 * ──────────────────────────────────────────────────────────────────────────── */

test("the 503 that means 'unconfigured' is not the 5xx that means 'broken', and neither is 'offline'", () => {
  /*
    THE THREE THE OLD CLIENT COULD NOT TELL APART. They need three different people to do three
    different things: an administrator sets a key, nobody does anything for a minute, and a designer
    walks somewhere with signal. `lib/failureTriage.ts` is right to put every 5xx in one `transient`
    row — that row is about custody of QUEUED work and this route has no queue — so the narrowing is
    done here, off `verdict.status`, and the triage is left alone.
  */
  expect(classifyMeasurementFailure(answered(503, UNCONFIGURED)).kind).toBe("provider-unconfigured");
  expect(classifyMeasurementFailure(answered(500, "boom")).kind).toBe("server-failed");
  expect(classifyMeasurementFailure(answered(502, "bad gateway")).kind).toBe("server-failed");
  expect(classifyMeasurementFailure(new TypeError("Failed to fetch")).kind).toBe("offline");

  // And the remedies differ, which is the reason the kinds do.
  expect(classifyMeasurementFailure(answered(503, UNCONFIGURED)).remedy).toBe("an-administrator");
  expect(classifyMeasurementFailure(answered(500, "boom")).remedy).toBe("trying-again");
  expect(classifyMeasurementFailure(new TypeError("Failed to fetch")).remedy).toBe("a-connection");
});

test("a timeout is its own answer, and a lost connection is not blamed for it", () => {
  // OURS — the clock this client started. Named by a TYPE and not by a `DOMException` name, because a
  // caller's own abort produces the identical rejection and only the layer that started the clock
  // knows which happened.
  expect(classifyMeasurementFailure(new MeasurementTimeoutError(120_000)).kind).toBe("timed-out");

  // A PROXY'S. 408 is `unreachable` to the shared triage and correctly so — nothing was decided — but
  // "you appear to be offline" sends a designer out of the building, and "the connection is there and
  // too slow for an 8 MB photograph" does not.
  expect(classifyMeasurementFailure(answered(408, "Request Timeout")).kind).toBe("timed-out");
  expect(classifyMeasurementFailure(new TypeError("Failed to fetch")).kind).toBe("offline");

  // A CALLER'S ABORT IS NOT A TIMEOUT. The designer replaced the photograph or unchecked the group;
  // nothing was decided and nothing timed out, so it lands where an interrupted request belongs.
  const aborted = new DOMException("The user aborted a request.", "AbortError");
  expect(classifyMeasurementFailure(aborted).kind).toBe("offline");
});

test("everything the server DECIDED keeps its own sentence, and each names its own remedy", () => {
  // These are the four refusals `media.py` raises before it ever calls a provider, and every one of
  // them already carries a sentence naming the limit, the type, the parameter or the permission.
  const tooBig = answered(413, "The image is larger than the 8 MB limit. Photograph the object alone on the grid sheet.");
  const wrongType = answered(415, "image/heic cannot be read; send a JPEG, PNG or WebP.");
  const unknownDimension = answered(422, "Unknown dimension 'depth'.");
  const notPermitted = answered(403, "Not permitted");

  for (const error of [tooBig, wrongType, unknownDimension, notPermitted]) {
    const verdict = classifyMeasurementFailure(error);
    expect(verdict.kind, `${verdict.status} is a decision the server made`).toBe("refused");
    expect(verdict.remedy).toBe("a-different-request");
    expect(verdict.serverSaidIt).toBe(true);
  }
  expect(classifyMeasurementFailure(tooBig).sentence).toContain("8 MB");
  expect(classifyMeasurementFailure(wrongType).sentence).toContain("JPEG");
});

test("a site with no API address is not a server with no Gemini key", () => {
  /*
    BOTH ARE 503 AND BOTH NAME AN ADMINISTRATOR ACTION, AND THE ACTIONS ARE DIFFERENT. One is
    "redeploy this site with its API address", composed in `lib/api.ts` for a request that was never
    made; the other is "add a key on the server". Reading the first as the second sends an operator to
    add a key that would change nothing, on a deployment that cannot reach the API at all.
  */
  const unconfiguredSite = new ApiUnconfiguredError();
  expect(classifyMeasurementFailure(unconfiguredSite).kind).toBe("app-unconfigured");
  expect(classifyMeasurementFailure(unconfiguredSite).sentence).toBe(unconfiguredSite.message);
  expect(classifyMeasurementFailure(unconfiguredSite).sentence).toContain("redeploy");
  // It is NOT the server's sentence — no server sent it — and the flag has to say so.
  expect(classifyMeasurementFailure(unconfiguredSite).serverSaidIt).toBe(false);

  // Same status, an actual server behind it, entirely different answer.
  expect(classifyMeasurementFailure(answered(503, UNCONFIGURED)).kind).toBe("provider-unconfigured");
  expect(classifyMeasurementFailure(answered(503, UNCONFIGURED)).sentence).not.toBe(unconfiguredSite.message);
});

test("a file THIS DEVICE refused is not reported as a lost connection", () => {
  // `LocalRefusalError` exists because a bare `Error` is indistinguishable from a `TypeError` out of
  // `fetch`, and an empty capture reported as "offline" sends a designer looking for signal over a
  // file the camera never finished writing. The sentence is the device's, so `serverSaidIt` is false.
  const empty = new LocalRefusalError("That file is empty — the camera may not have finished writing it.");
  expect(classifyMeasurementFailure(empty).kind).toBe("unsendable");
  expect(classifyMeasurementFailure(empty).serverSaidIt).toBe(false);
  expect(classifyMeasurementFailure(empty).sentence).toContain("empty");
  expect(classifyMeasurementFailure(empty).remedy).not.toBe("a-connection");
});

test("a refusal that arrives wrapped is classified by what it wraps", () => {
  // The `cause` chain is followed in exactly one place in this client — `lib/failureTriage.ts` — and
  // this module is a reading of that verdict rather than a second unwrap. So a 503 that reaches here
  // inside a wrapper is still the configuration 503.
  const wrapped = new MediaBatchError("All 1 media file(s) failed to upload (grid.jpg).", [
    { name: "grid.jpg", error: UNCONFIGURED, cause: answered(503, UNCONFIGURED) }
  ]);
  expect(classifyMeasurementFailure(wrapped).kind).toBe("provider-unconfigured");
  expect(classifyMeasurementFailure(wrapped).sentence).toBe(UNCONFIGURED);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The server's words win wherever the server spoke — and only there
 * ──────────────────────────────────────────────────────────────────────────── */

test("the setting name comes off the wire and is never reconstructed here", () => {
  /*
    `ai.py`'s `_verb_unavailable` states the contract from the server's end: "NAMES THE SETTING,
    ALWAYS. The designer cannot fix it and the administrator can." A client that paraphrases has taken
    the one actionable fact out of the sentence, and a client that GUESSES has asserted a server fact
    it cannot see — the day the measurement provider changes, the screen names a setting that no
    longer exists and sends an administrator to the wrong page.
  */
  const verdict = classifyMeasurementFailure(answered(503, UNCONFIGURED));
  expect(verdict.sentence).toBe(UNCONFIGURED);
  expect(verdict.sentence).toContain("GEMINI_API_KEY");
  expect(verdict.serverSaidIt).toBe(true);

  // NOTHING WRITTEN ON THIS SIDE MAY NAME A SETTING OR A PROVIDER. A fallback that hard-coded
  // GEMINI_API_KEY would pass the assertion above by accident and be a lie the day the provider
  // changes — the screen would send an administrator to a setting that no longer exists.
  for (const [kind, fallback] of Object.entries(MEASUREMENT_FALLBACKS)) {
    expect(fallback, `${kind} must not name a setting`).not.toMatch(/[A-Z][A-Z0-9]+_[A-Z0-9_]+/);
    expect(fallback.toLowerCase(), `${kind} must not name a provider`).not.toContain("gemini");
    expect(fallback, `${kind} must not print a status code`).not.toMatch(/\bHTTP\s*\d{3}\b/);
  }
});

test("apiFetch's fabricated 'HTTP 503' is never shown as though the server had said it", () => {
  /*
    THE ONE THAT ONLY APPEARS ON A REAL DEPLOYMENT. `apiFetch` builds the message as
    `describeApiDetail(detail, response.statusText || "The server refused the request (HTTP ${status}).")`
    and `statusText` is EMPTY over HTTP/2 — which every deployed request is. So a reply with no body
    (a gateway 503 mid-deploy, an nginx 504, a proxy's 502 page) arrives with a message that LOOKS
    like a sentence and is a status code. Quoting it puts an HTTP number in front of a designer under
    a promise that one is never shown, dressed as the server naming a missing key.
  */
  for (const status of [502, 503, 504]) {
    const sentence = classifyMeasurementFailure(bodyless(status)).sentence;
    expect(sentence, `a body-less ${status} must not reach the screen as a status code`).not.toContain(`HTTP ${status}`);
    expect(sentence).not.toContain("refused the request");
    expect(classifyMeasurementFailure(bodyless(status)).serverSaidIt).toBe(false);
  }

  // The 503 still says the true thing without inventing the setting: it is not switched on here, it is
  // not the photograph, and only an administrator can change it.
  const sentence = classifyMeasurementFailure(bodyless(503)).sentence;
  expect(classifyMeasurementFailure(bodyless(503)).kind).toBe("provider-unconfigured");
  expect(sentence).toContain("administers the server");
  expect(sentence).toContain("photograph is fine");
});

test("serverSentence is the guard, and it reads the body rather than the message", () => {
  // The predicate the branch above turns on, asserted directly so a change to it fails here and not
  // three modules away.
  expect(serverSentence(answered(503, UNCONFIGURED))).toBe(UNCONFIGURED);
  expect(serverSentence(bodyless(503))).toBeNull();
  expect(serverSentence(new TypeError("Failed to fetch"))).toBeNull();
  expect(serverSentence(new ApiError(500, "boom", { detail: "   " })), "blank prose is not a sentence").toBeNull();
  // FastAPI's own 422 is a LIST of per-field objects, and `describeApiDetail` is what turns it into
  // words. Asking for the body's presence alone would have handed a caller "[object Object]".
  const validation = new ApiError(422, "ignored", {
    detail: [{ type: "value_error", loc: ["query", "dimension"], msg: "Value error, Unknown dimension" }]
  });
  expect(serverSentence(validation)).toBe("dimension: Unknown dimension");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. The two 200s that are failures anyway
 * ──────────────────────────────────────────────────────────────────────────── */

test("a 200 whose PROVIDER failed is not an unreadable grid", () => {
  /*
    THE DEFECT THIS CLOSES, AND IT WAS LIVE ON A CURRENT SERVER RATHER THAN AN OLD ONE.
    `analyze_measurement_image_bytes` catches a `requests.RequestException` from the provider and
    returns `available: true, status: "FAILED", analysis: null` with a sentence naming the fault. The
    route passes that through as 200 on purpose, because the server is working exactly as designed.
    `readGridAnalysis` then found no readings and said "Couldn't read a value — enter it manually" —
    telling a designer to re-photograph a perfectly good object because Google rate-limited the key.
  */
  const failure = measurementBodyFailure({
    available: true,
    status: "FAILED",
    analysis: null,
    message: PROVIDER_FAULT,
    method: "VISION_MODEL",
    provider: "gemini",
    modelId: "gemini-2.5-flash-lite"
  });
  expect(failure?.kind).toBe("provider-failed");
  expect(failure?.sentence).toBe(PROVIDER_FAULT);
  expect(failure?.serverSaidIt).toBe(true);
  expect(failure?.remedy).toBe("trying-again");
  expect(failure?.sentence).not.toContain("Couldn't read");
});

test("an old server's 200/available:false is still the unconfigured answer, message or no message", () => {
  // A web build outlives a backend deploy, so this is an ordinary state rather than a mistake.
  const withMessage = measurementBodyFailure({ available: false, status: "UNAVAILABLE", analysis: null, message: UNCONFIGURED });
  expect(withMessage?.kind).toBe("provider-unconfigured");
  expect(withMessage?.sentence).toBe(UNCONFIGURED);

  // AND WITHOUT ONE. The branch this replaces fell back to "Couldn't read a value — enter it
  // manually", which is the exact confusion the 503 was introduced to end.
  const silent = measurementBodyFailure({ available: false, status: "UNAVAILABLE", analysis: null });
  expect(silent?.kind).toBe("provider-unconfigured");
  expect(silent?.sentence).not.toContain("Couldn't read");
  expect(silent?.remedy).toBe("an-administrator");
});

test("a real answer is not a failure, even when the model read nothing", () => {
  // "The model looked and could not read it" is the ONE outcome that is about the photograph, and it
  // is not this module's to name — `readGridAnalysis` owns it and keeps Android's sentence. So a
  // COMPLETED body always comes back as null here, numbers or no numbers.
  expect(measurementBodyFailure({ available: true, status: "COMPLETED", analysis: { valueInches: 4 } })).toBeNull();
  expect(measurementBodyFailure({ available: true, status: "COMPLETED", analysis: null })).toBeNull();
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. The properties the whole table has to keep
 * ──────────────────────────────────────────────────────────────────────────── */

/** One error that reaches each kind, so the sweeps below are over the real table and not a list. */
const ONE_OF_EACH: Readonly<Record<MeasurementFailureKind, unknown>> = {
  "provider-unconfigured": answered(503, UNCONFIGURED),
  "provider-failed": null, // produced by a BODY, not a throw — covered by `bodyFailures` below
  "app-unconfigured": new ApiUnconfiguredError(),
  offline: new TypeError("Failed to fetch"),
  "timed-out": new MeasurementTimeoutError(MEASUREMENT_TIMEOUT_MS),
  refused: answered(413, "The image is larger than the 8 MB limit."),
  "server-failed": bodyless(500),
  unsendable: new LocalRefusalError("That file is empty.")
};

/** Every verdict the module can produce, from both doors. */
function everyVerdict() {
  const fromThrows = Object.entries(ONE_OF_EACH)
    .filter(([kind]) => kind !== "provider-failed")
    .map(([, error]) => classifyMeasurementFailure(error));
  const fromBody = [
    measurementBodyFailure({ available: true, status: "FAILED", analysis: null }),
    measurementBodyFailure({ available: false, status: "UNAVAILABLE", analysis: null })
  ].filter((verdict) => verdict !== null);
  return [...fromThrows, ...fromBody];
}

test("every kind is reachable and every kind has its own sentence", () => {
  // A table nobody can reach is the previous generation of this comment. Both doors are walked, and
  // the sentences are compared as a SET — two kinds sharing one sentence is the collapse this whole
  // change is about, wearing a different shape.
  const verdicts = everyVerdict();
  const kinds = new Set(verdicts.map((verdict) => verdict.kind));
  expect(kinds.size, "one error per kind, and every kind reached").toBe(Object.keys(ONE_OF_EACH).length);
  expect(new Set(verdicts.map((verdict) => verdict.sentence)).size).toBe(verdicts.length);
});

test("no verdict this module can produce blames what is IN the photograph", () => {
  /*
    THE GUARANTEE THE TYPE CARRIES, ASSERTED ANYWAY. `MeasurementRemedy` has no "another-photograph"
    value, so a verdict here can never send a designer back to re-shoot an object over a problem an
    administrator has to fix — which is precisely what the 503 exists to prevent. The one genuine
    "the model looked and could not read it" outcome is a 2xx with no numbers, and it belongs to
    `readGridAnalysis`.

    `unsendable` is the near miss and is checked rather than excused: a camera that did not finish
    writing a file produced no photograph to criticise, so it names the FILE and takes the same remedy
    a 413 does.
  */
  for (const verdict of everyVerdict()) {
    expect(["an-administrator", "a-connection", "trying-again", "a-different-request"]).toContain(verdict.remedy);
    if (verdict.serverSaidIt) continue; // the server's own words are the server's to choose
    expect(verdict.sentence.toLowerCase(), `${verdict.kind} must not send them back to the grid`).not.toContain("grid");
    expect(verdict.sentence.toLowerCase(), `${verdict.kind} must not blame the light`).not.toContain("better light");
  }

  // And the unconfigured sentence says so in as many words, because that is the one a researcher was
  // previously left to guess at.
  const sentence = classifyMeasurementFailure(bodyless(503)).sentence;
  expect(sentence).toContain("photograph is fine");
  expect(sentence).toContain("nothing to re-take");
});

test("every sentence this module WRITES ends at something that works with no connection", () => {
  /*
    THE EDGE-COMPUTE CONSTRAINT, ON THE ONE ROUTE THAT CANNOT MEET IT. This endpoint is a vision-model
    call and is inherently server-backed; `lib/photoMeasure.ts` is the on-device default and needs
    nothing at all. So no failure here may be a dead end.

    THE TABLE RATHER THAN THE VERDICTS, deliberately: a verdict's sentence may be the SERVER's (which
    already says "measure the object and enter the value manually" — that is `_verb_unavailable`'s
    shape), or `lib/api.ts`'s, or `lib/media.ts`'s. Those are borrowed and are not this module's to
    hold to a form. What this module writes is exactly {@link MEASUREMENT_FALLBACKS}, and every one of
    them has to leave a designer with no signal something to do.
  */
  for (const [kind, fallback] of Object.entries(MEASUREMENT_FALLBACKS)) {
    expect(fallback.toLowerCase(), `${kind} leaves the designer with nothing to do`).toContain(
      "enter the value manually"
    );
  }
  // And it is a complete table: a kind with no sentence would be a blank status line.
  expect(Object.keys(MEASUREMENT_FALLBACKS).sort()).toEqual(Object.keys(ONE_OF_EACH).sort());
});

/* ────────────────────────────────────────────────────────────────────────────
 * 5. The wait, and the margin under it
 * ──────────────────────────────────────────────────────────────────────────── */

test("the client waits LONGER than the server's own provider budget", () => {
  /*
    THE MARGIN IS THE POINT, AND IT IS MEASURED AGAINST THE SERVER RATHER THAN REMEMBERED.
    `_post_gemini_measurement` calls the provider with `timeout=90` seconds and rotates onto the next
    key on a network error, so a request that is going to succeed can legitimately still be in flight
    past a minute. A client timeout under that aborts calls that were about to answer, reports them as
    "timed out", and throws away a reading the deployment has already paid for.
  */
  const ai = readFileSync(join(REPO, "backend", "app", "services", "ai.py"), "utf8");
  const budgets = [...ai.matchAll(/timeout=(\d+),/g)].map((match) => Number(match[1]));
  expect(budgets, "has the provider call lost its timeout?").toContain(90);
  expect(MEASUREMENT_TIMEOUT_MS).toBeGreaterThan(90 * 1000);
});

test("a stalled request becomes a timeout, and a caller's abort does not", async () => {
  /*
    END TO END THROUGH `analyzeMeasurementImage`, because the flag that distinguishes the two lives
    inside it. A stub rather than a mock library: `apiFetch` returns early from `assertApiConfigured`
    outside a browser and `getToken` answers null there, so `fetch` is the only moving part.

    A connection that opens and then stalls does not reject on its own — a captive portal, a lift, a
    tower handover mid-upload. Before this, the status line said "Analyzing…" until the designer
    navigated away, which is the one outcome with no sentence at all.
  */
  const original = globalThis.fetch;
  globalThis.fetch = ((_url: string, init: RequestInit) =>
    new Promise((_resolve, reject) => {
      init?.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
    })) as unknown as typeof fetch;
  const photo = new File([new Uint8Array([1, 2, 3])], "grid.jpg", { type: "image/jpeg" });

  try {
    // OURS. The clock this client started, so the sentence is about a wait that ran out.
    const timedOut = await analyzeMeasurementImage(photo, "height", { timeoutMs: 20 }).catch((error) => error);
    expect(timedOut).toBeInstanceOf(MeasurementTimeoutError);
    expect(classifyMeasurementFailure(timedOut).kind).toBe("timed-out");

    // THEIRS. The designer replaced the photograph. Identical `DOMException`, and it must NOT be
    // reported as a timeout — nothing timed out and nothing was decided.
    const caller = new AbortController();
    const abandoned = analyzeMeasurementImage(photo, "height", { timeoutMs: 60_000, signal: caller.signal }).catch(
      (error) => error
    );
    caller.abort();
    const thrown = await abandoned;
    expect(thrown).not.toBeInstanceOf(MeasurementTimeoutError);
    expect(classifyMeasurementFailure(thrown).kind).toBe("offline");
  } finally {
    globalThis.fetch = original;
  }
});
