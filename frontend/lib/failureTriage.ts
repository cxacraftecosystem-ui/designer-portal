import { ApiError, describeApiDetail } from "@/lib/api";

export type FailureKind =
  | "unreachable"
  | "transient"
  | "credential-expired"
  | "schema-drift"
  | "refused"
  | "permanent";

export type RetryPolicy =
  | "connection"
  | "later"
  | "sign-in"
  | "next-run"
  | "never";

export type DrainAction =
  | "stop-and-keep"
  | "stop-and-ask-for-sign-in"
  | "record-against-the-item"
  | "record-but-retry-next-run";

export type ScreenAction =
  | "say-offline"
  | "say-try-later"
  | "say-signed-out"
  | "say-refused"
  | "say-out-of-step"
  | "say-unsendable";

export type TriageRow = {
  kind: FailureKind;
  retry: RetryPolicy;
  drain: DrainAction;
  screen: ScreenAction;
  meaning: string;
};

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

export const ANDROID_DIVERGENCE = {
  status: 401,
  web: "credential-expired" satisfies FailureKind,
  android: "folded into WorkshopRepository.isTransient and WorkshopSync.isConnectionFailure",
  reason:
    "The web's isTransient is also read by saveOrQueue and by two interactive surfaces, where a 401 must not mean " +
    "'queue it and retry'. Android's has only the two drains as readers, which all want the same thing from a 401.",
  outcomeIsTheSame: "both platforms stop the pass, mark nothing, and ask for a sign-in"
} as const;

export class LocalRefusalError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "LocalRefusalError";
  }
}

export class StorageError extends Error {
  status: number | null;

  constructor(message: string, status: number | null = null) {
    super(message);
    this.name = "StorageError";
    this.status = status;
  }
}

const CAUSE_DEPTH = 8;

export function underlyingError(error: unknown, depth = 0): unknown {
  if (error instanceof ApiError) return error;
  if (depth < CAUSE_DEPTH && error instanceof Error) {
    const cause = (error as { cause?: unknown }).cause;
    if (cause !== undefined && cause !== null) return underlyingError(cause, depth + 1);
  }
  return error;
}

export type FailureVerdict = {
  kind: FailureKind;
  retry: RetryPolicy;
  drain: DrainAction;
  screen: ScreenAction;
  status: number | null;
  answered: ApiError | null;
  underlying: unknown;
};

function bodyNamesAnUnknownKey(error: ApiError): boolean {
  const detail = (error.payload as { detail?: unknown } | null | undefined)?.detail;
  if (!Array.isArray(detail)) return false;
  return detail.some((entry) => (entry as { type?: unknown } | null)?.type === "extra_forbidden");
}

function kindForStatus(error: ApiError): FailureKind {
  const { status } = error;
  if (status === 401) return "credential-expired";
  if (status === 408) return "unreachable";
  if (status === 429) return "transient";
  if (status >= 500) return "transient";
  if (status === 422 && bodyNamesAnUnknownKey(error)) return "schema-drift";
  return "refused";
}

function kindForStorage(error: StorageError): FailureKind {
  const { status } = error;
  if (status === null) return "unreachable";
  if (status === 408) return "unreachable";
  if (status === 429 || status >= 500) return "transient";
  return "refused";
}

export function triageFailure(error: unknown): FailureVerdict {
  return verdictFor(underlyingError(error));
}

export function triageAsThrown(error: unknown): FailureVerdict {
  return verdictFor(error);
}

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

export function isUnreachable(error: unknown): boolean {
  return triageFailure(error).kind === "unreachable";
}

export function isTransient(error: unknown): boolean {
  const { retry } = triageAsThrown(error);
  return retry === "connection" || retry === "later";
}

export function underlyingIsTransient(error: unknown): boolean {
  const { retry } = triageFailure(error);
  return retry === "connection" || retry === "later";
}

export function isSchemaRefusal(error: unknown): boolean {
  return triageFailure(error).kind === "schema-drift";
}

export function schemaRefusalError(error: unknown): ApiError | null {
  const verdict = triageFailure(error);
  return verdict.kind === "schema-drift" ? verdict.answered : null;
}

export function serverSentence(error: unknown): string | null {
  const { answered } = triageFailure(error);
  if (!answered) return null;
  const body = answered.payload;
  if (!body || typeof body !== "object") return null;
  const detail = (body as { detail?: unknown }).detail;
  if (detail === undefined || detail === null) return null;
  const sentence = describeApiDetail(detail, "").trim();
  return sentence ? sentence : null;
}

export function isCredentialExpiry(error: unknown): boolean {
  return triageFailure(error).kind === "credential-expired";
}

export function isPasswordChangeRefusal(error: unknown): boolean {
  return triageFailure(error).answered?.passwordChangeRequired === true;
}

export function serverAskedForTime(error: unknown): boolean {
  const { status } = triageFailure(error);
  return status === 408 || status === 429;
}
