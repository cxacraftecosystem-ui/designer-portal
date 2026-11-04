import { ApiUnconfiguredError } from "@/lib/api";
import { serverSentence, triageFailure } from "@/lib/failureTriage";
import { MeasurementTimeoutError, type MeasurementAnalysisResponse } from "@/lib/media";

export type MeasurementRemedy =
  | "an-administrator"
  | "a-connection"
  | "trying-again"
  | "a-different-request";

export type MeasurementFailureKind =
  | "provider-unconfigured"
  | "provider-failed"
  | "app-unconfigured"
  | "offline"
  | "timed-out"
  | "refused"
  | "server-failed"
  | "unsendable";

export type MeasurementFailure = {
  kind: MeasurementFailureKind;
  sentence: string;
  remedy: MeasurementRemedy;
  serverSaidIt: boolean;
  status: number | null;
};

export const MEASUREMENT_FALLBACKS: Readonly<Record<MeasurementFailureKind, string>> = {
  "provider-unconfigured":
    "Measuring from a photo isn't available right now. Your photograph is fine and there is nothing to re-take — " +
    "an administrator can turn it on. Measure the object and enter the value manually.",
  "provider-failed":
    "The photo could not be read just now. Nothing is wrong with your photograph. Try again in a minute, or " +
    "measure the object and enter the value manually.",
  "app-unconfigured":
    "Measuring from a photo isn't available right now. Measure the object and enter the value manually.",
  offline:
    "You are offline, and reading the photo needs a connection. Measure the object and enter the value " +
    "manually, or try again when you are back online.",
  "timed-out":
    "The photo was sent but no reading came back in time. Try again on a steadier connection, or measure the " +
    "object and enter the value manually.",
  refused:
    "This photo could not be read. Try a smaller JPEG or PNG straight from the camera, or measure the object " +
    "and enter the value manually.",
  "server-failed":
    "The measuring service is busy or briefly out of order. Nothing is wrong with your photograph. Try again in " +
    "a minute, or measure the object and enter the value manually.",
  unsendable:
    "This photo could not be sent — the file is unusable, which usually means the camera did not finish " +
    "saving it. Take the photo again, or measure the object and enter the value manually."
};

const REMEDY: Readonly<Record<MeasurementFailureKind, MeasurementRemedy>> = {
  "provider-unconfigured": "an-administrator",
  "app-unconfigured": "an-administrator",
  offline: "a-connection",
  "timed-out": "a-connection",
  "provider-failed": "trying-again",
  "server-failed": "trying-again",
  refused: "a-different-request",
  unsendable: "a-different-request"
};

function failure(kind: MeasurementFailureKind, said: string | null, status: number | null): MeasurementFailure {
  return { kind, sentence: said ?? MEASUREMENT_FALLBACKS[kind], remedy: REMEDY[kind], serverSaidIt: said !== null, status };
}

function localFailure(kind: MeasurementFailureKind, sentence: string | null, status: number | null): MeasurementFailure {
  return { kind, sentence: sentence ?? MEASUREMENT_FALLBACKS[kind], remedy: REMEDY[kind], serverSaidIt: false, status };
}

function words(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

export function measurementBodyFailure(response: MeasurementAnalysisResponse): MeasurementFailure | null {
  const said = words(response.message);
  if (response.available === false) return failure("provider-unconfigured", said, 200);
  if (response.status === "FAILED") return failure("provider-failed", said, 200);
  return null;
}

export function classifyMeasurementFailure(error: unknown): MeasurementFailure {
  if (error instanceof ApiUnconfiguredError) return localFailure("app-unconfigured", words(error.message), null);
  if (error instanceof MeasurementTimeoutError) return localFailure("timed-out", null, null);

  const verdict = triageFailure(error);
  const said = serverSentence(error);

  if (verdict.kind === "permanent") {
    return localFailure("unsendable", verdict.underlying instanceof Error ? words(verdict.underlying.message) : null, null);
  }

  if (verdict.answered) {
    if (verdict.status === 503) return failure("provider-unconfigured", said, verdict.status);
    if (verdict.status === 408) return failure("timed-out", said, verdict.status);
    if (verdict.kind === "transient") return failure("server-failed", said, verdict.status);
    return failure("refused", said, verdict.status);
  }

  return failure("offline", null, verdict.status);
}
