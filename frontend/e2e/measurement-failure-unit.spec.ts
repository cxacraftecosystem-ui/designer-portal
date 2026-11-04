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

function answered(status: number, detail: string): ApiError {
  return new ApiError(status, detail, { detail });
}

function bodyless(status: number): ApiError {
  return new ApiError(status, `The server refused the request (HTTP ${status}).`, null);
}

const UNCONFIGURED =
  "Grid measurement is unavailable because no Gemini API key is configured. Measure the object and " +
  "type the value in, or ask whoever administers the server to add GEMINI_API_KEY in the Settings hub.";

const PROVIDER_FAULT =
  "Measurement analysis failed (HTTP 429); measure the object and enter the value manually. The " +
  "provider's reply is in the server log.";

const REPO = join(__dirname, "..", "..");

test("the 503 that means 'unconfigured' is not the 5xx that means 'broken', and neither is 'offline'", () => {
  expect(classifyMeasurementFailure(answered(503, UNCONFIGURED)).kind).toBe("provider-unconfigured");
  expect(classifyMeasurementFailure(answered(500, "boom")).kind).toBe("server-failed");
  expect(classifyMeasurementFailure(answered(502, "bad gateway")).kind).toBe("server-failed");
  expect(classifyMeasurementFailure(new TypeError("Failed to fetch")).kind).toBe("offline");

  expect(classifyMeasurementFailure(answered(503, UNCONFIGURED)).remedy).toBe("an-administrator");
  expect(classifyMeasurementFailure(answered(500, "boom")).remedy).toBe("trying-again");
  expect(classifyMeasurementFailure(new TypeError("Failed to fetch")).remedy).toBe("a-connection");
});

test("a timeout is its own answer, and a lost connection is not blamed for it", () => {
  expect(classifyMeasurementFailure(new MeasurementTimeoutError(120_000)).kind).toBe("timed-out");

  expect(classifyMeasurementFailure(answered(408, "Request Timeout")).kind).toBe("timed-out");
  expect(classifyMeasurementFailure(new TypeError("Failed to fetch")).kind).toBe("offline");

  const aborted = new DOMException("The user aborted a request.", "AbortError");
  expect(classifyMeasurementFailure(aborted).kind).toBe("offline");
});

test("everything the server DECIDED keeps its own sentence, and each names its own remedy", () => {
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
  const unconfiguredSite = new ApiUnconfiguredError();
  expect(classifyMeasurementFailure(unconfiguredSite).kind).toBe("app-unconfigured");
  expect(classifyMeasurementFailure(unconfiguredSite).sentence).toBe(unconfiguredSite.message);
  expect(classifyMeasurementFailure(unconfiguredSite).sentence).toContain("contact your administrator");
  expect(classifyMeasurementFailure(unconfiguredSite).serverSaidIt).toBe(false);

  expect(classifyMeasurementFailure(answered(503, UNCONFIGURED)).kind).toBe("provider-unconfigured");
  expect(classifyMeasurementFailure(answered(503, UNCONFIGURED)).sentence).not.toBe(unconfiguredSite.message);
});

test("a file THIS DEVICE refused is not reported as a lost connection", () => {
  const empty = new LocalRefusalError("That file is empty — the camera may not have finished writing it.");
  expect(classifyMeasurementFailure(empty).kind).toBe("unsendable");
  expect(classifyMeasurementFailure(empty).serverSaidIt).toBe(false);
  expect(classifyMeasurementFailure(empty).sentence).toContain("empty");
  expect(classifyMeasurementFailure(empty).remedy).not.toBe("a-connection");
});

test("a refusal that arrives wrapped is classified by what it wraps", () => {
  const wrapped = new MediaBatchError("All 1 media file(s) failed to upload (grid.jpg).", [
    { name: "grid.jpg", error: UNCONFIGURED, cause: answered(503, UNCONFIGURED) }
  ]);
  expect(classifyMeasurementFailure(wrapped).kind).toBe("provider-unconfigured");
  expect(classifyMeasurementFailure(wrapped).sentence).toBe(UNCONFIGURED);
});

test("the setting name comes off the wire and is never reconstructed here", () => {
  const verdict = classifyMeasurementFailure(answered(503, UNCONFIGURED));
  expect(verdict.sentence).toBe(UNCONFIGURED);
  expect(verdict.sentence).toContain("GEMINI_API_KEY");
  expect(verdict.serverSaidIt).toBe(true);

  for (const [kind, fallback] of Object.entries(MEASUREMENT_FALLBACKS)) {
    expect(fallback, `${kind} must not name a setting`).not.toMatch(/[A-Z][A-Z0-9]+_[A-Z0-9_]+/);
    expect(fallback.toLowerCase(), `${kind} must not name a provider`).not.toContain("gemini");
    expect(fallback, `${kind} must not print a status code`).not.toMatch(/\bHTTP\s*\d{3}\b/);
  }
});

test("apiFetch's fabricated 'HTTP 503' is never shown as though the server had said it", () => {
  for (const status of [502, 503, 504]) {
    const sentence = classifyMeasurementFailure(bodyless(status)).sentence;
    expect(sentence, `a body-less ${status} must not reach the screen as a status code`).not.toContain(`HTTP ${status}`);
    expect(sentence).not.toContain("refused the request");
    expect(classifyMeasurementFailure(bodyless(status)).serverSaidIt).toBe(false);
  }

  const sentence = classifyMeasurementFailure(bodyless(503)).sentence;
  expect(classifyMeasurementFailure(bodyless(503)).kind).toBe("provider-unconfigured");
  expect(sentence).toContain("an administrator can turn it on");
  expect(sentence).toContain("photograph is fine");
});

test("serverSentence is the guard, and it reads the body rather than the message", () => {
  expect(serverSentence(answered(503, UNCONFIGURED))).toBe(UNCONFIGURED);
  expect(serverSentence(bodyless(503))).toBeNull();
  expect(serverSentence(new TypeError("Failed to fetch"))).toBeNull();
  expect(serverSentence(new ApiError(500, "boom", { detail: "   " })), "blank prose is not a sentence").toBeNull();
  const validation = new ApiError(422, "ignored", {
    detail: [{ type: "value_error", loc: ["query", "dimension"], msg: "Value error, Unknown dimension" }]
  });
  expect(serverSentence(validation)).toBe("dimension: Unknown dimension");
});

test("a 200 whose PROVIDER failed is not an unreadable grid", () => {
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
  const withMessage = measurementBodyFailure({ available: false, status: "UNAVAILABLE", analysis: null, message: UNCONFIGURED });
  expect(withMessage?.kind).toBe("provider-unconfigured");
  expect(withMessage?.sentence).toBe(UNCONFIGURED);

  const silent = measurementBodyFailure({ available: false, status: "UNAVAILABLE", analysis: null });
  expect(silent?.kind).toBe("provider-unconfigured");
  expect(silent?.sentence).not.toContain("Couldn't read");
  expect(silent?.remedy).toBe("an-administrator");
});

test("a real answer is not a failure, even when the model read nothing", () => {
  expect(measurementBodyFailure({ available: true, status: "COMPLETED", analysis: { valueInches: 4 } })).toBeNull();
  expect(measurementBodyFailure({ available: true, status: "COMPLETED", analysis: null })).toBeNull();
});

const ONE_OF_EACH: Readonly<Record<MeasurementFailureKind, unknown>> = {
  "provider-unconfigured": answered(503, UNCONFIGURED),
  "provider-failed": null,
  "app-unconfigured": new ApiUnconfiguredError(),
  offline: new TypeError("Failed to fetch"),
  "timed-out": new MeasurementTimeoutError(MEASUREMENT_TIMEOUT_MS),
  refused: answered(413, "The image is larger than the 8 MB limit."),
  "server-failed": bodyless(500),
  unsendable: new LocalRefusalError("That file is empty.")
};

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
  const verdicts = everyVerdict();
  const kinds = new Set(verdicts.map((verdict) => verdict.kind));
  expect(kinds.size, "one error per kind, and every kind reached").toBe(Object.keys(ONE_OF_EACH).length);
  expect(new Set(verdicts.map((verdict) => verdict.sentence)).size).toBe(verdicts.length);
});

test("no verdict this module can produce blames what is IN the photograph", () => {
  for (const verdict of everyVerdict()) {
    expect(["an-administrator", "a-connection", "trying-again", "a-different-request"]).toContain(verdict.remedy);
    if (verdict.serverSaidIt) continue;
    expect(verdict.sentence.toLowerCase(), `${verdict.kind} must not send them back to the grid`).not.toContain("grid");
    expect(verdict.sentence.toLowerCase(), `${verdict.kind} must not blame the light`).not.toContain("better light");
  }

  const sentence = classifyMeasurementFailure(bodyless(503)).sentence;
  expect(sentence).toContain("photograph is fine");
  expect(sentence).toContain("nothing to re-take");
});

test("every sentence this module WRITES ends at something that works with no connection", () => {
  for (const [kind, fallback] of Object.entries(MEASUREMENT_FALLBACKS)) {
    expect(fallback.toLowerCase(), `${kind} leaves the designer with nothing to do`).toContain(
      "enter the value manually"
    );
  }
  expect(Object.keys(MEASUREMENT_FALLBACKS).sort()).toEqual(Object.keys(ONE_OF_EACH).sort());
});

test("the client waits LONGER than the server's own provider budget", () => {
  const ai = readFileSync(join(REPO, "backend", "app", "services", "ai.py"), "utf8");
  const budgets = [...ai.matchAll(/timeout=(\d+),/g)].map((match) => Number(match[1]));
  expect(budgets, "has the provider call lost its timeout?").toContain(90);
  expect(MEASUREMENT_TIMEOUT_MS).toBeGreaterThan(90 * 1000);
});

test("a stalled request becomes a timeout, and a caller's abort does not", async () => {
  const original = globalThis.fetch;
  globalThis.fetch = ((_url: string, init: RequestInit) =>
    new Promise((_resolve, reject) => {
      init?.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
    })) as unknown as typeof fetch;
  const photo = new File([new Uint8Array([1, 2, 3])], "grid.jpg", { type: "image/jpeg" });

  try {
    const timedOut = await analyzeMeasurementImage(photo, "height", { timeoutMs: 20 }).catch((error) => error);
    expect(timedOut).toBeInstanceOf(MeasurementTimeoutError);
    expect(classifyMeasurementFailure(timedOut).kind).toBe("timed-out");

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
