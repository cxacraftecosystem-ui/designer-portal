import type { PageResult } from "@/lib/types";

import { SESSION_ENDED_PATH, sessionEndedHref } from "@/lib/sessionReturn";
import { endSession, getSessionMarker } from "@/lib/session";

const SIGNED_OUT_PATHS = new Set(["/login", SESSION_ENDED_PATH]);

export const API_BASE = "";

export const PUBLIC_API_ORIGIN = (process.env.NEXT_PUBLIC_API_URL ?? "").trim().replace(/\/+$/, "") || "http://localhost:8000";

export const PASSWORD_CHANGE_REQUIRED_HEADER = "x-password-change-required";

export class ApiError extends Error {
  status: number;
  payload: unknown;
  headers?: Headers;
  passwordChangeRequired: boolean;

  constructor(status: number, message: string, payload: unknown, headers?: Headers) {
    super(message);
    this.status = status;
    this.payload = payload;
    this.headers = headers;
    this.passwordChangeRequired =
      status === 401 && (headers?.get(PASSWORD_CHANGE_REQUIRED_HEADER) ?? "").trim() === "1";
  }
}

export class ApiUnconfiguredError extends ApiError {
  constructor() {
    super(
      503,
      "The service isn't available right now, so you can't sign in or load records. " +
        "Please try again later, or contact your administrator if this continues.",
      null
    );
    this.name = "ApiUnconfiguredError";
  }
}

export function assertApiConfigured(): void {}

export function fieldLabel(field: string | null): string {
  if (!field) return "This field";
  const words = field.replace(/([a-z0-9])([A-Z])/g, "$1 $2").replace(/[_-]+/g, " ").trim().toLowerCase();
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : "This field";
}

export function validationSentence(field: string | null, message: string): string | null {
  const text = message.trim();
  if (/valid email address/i.test(text) || (field && /^e-?mail$/i.test(field) && /^value is not a valid/i.test(text))) return "Enter a valid email address.";
  if (/^Field required$/i.test(text)) return field && /^e-?mail$/i.test(field) ? "Enter your email address." : `${fieldLabel(field)} is required.`;
  const least = /^String should have at least (\d+) characters?$/i.exec(text);
  if (least) return least[1] === "1" ? `${fieldLabel(field)} can't be empty.` : `${fieldLabel(field)} needs at least ${least[1]} characters.`;
  const most = /^String should have at most (\d+) characters?$/i.exec(text);
  if (most) return `${fieldLabel(field)} can be at most ${most[1]} characters.`;
  if (/^(Input should be|value is not a valid)/i.test(text)) return `Check ${fieldLabel(field).toLowerCase()}.`;
  return null;
}

type DetailEntry = { msg?: unknown; loc?: unknown; field?: unknown; label?: unknown };

function nonBlank(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function detailEntries(detail: unknown): DetailEntry[] {
  if (!Array.isArray(detail)) return [];
  return detail.filter((entry): entry is DetailEntry => Boolean(entry) && typeof entry === "object" && !Array.isArray(entry));
}

export function detailEntryField(entry: DetailEntry): string | null {
  const named = nonBlank(entry.field);
  if (named) return named;
  if (!Array.isArray(entry.loc)) return null;
  const last = entry.loc.filter((part): part is string => typeof part === "string").at(-1);
  return last && last !== "body" && last !== "query" && last !== "path" ? last : null;
}

export function detailEntrySentence(entry: DetailEntry): string {
  const message = nonBlank(entry.msg);
  if (!message) return "";
  if (nonBlank(entry.label)) return message;
  const field = detailEntryField(entry);
  if (!/^(Value error|Assertion failed),/.test(message)) {
    const human = validationSentence(field, message);
    if (human) return human;
  }
  const cleaned = message.replace(/^(Value error|Assertion failed),\s*/, "").trim();
  return field ? `${fieldLabel(field)}: ${cleaned}` : cleaned;
}

export function describeApiDetail(detail: unknown, fallback: string): string {
  const text = nonBlank(detail);
  if (text) return text;
  if (Array.isArray(detail)) {
    const sentences = [...new Set(detailEntries(detail).map(detailEntrySentence).filter(Boolean))];
    if (sentences.length) return sentences.join(" ");
  }
  if (detail && typeof detail === "object" && !Array.isArray(detail)) {
    const record = detail as { message?: unknown; msg?: unknown };
    const message = nonBlank(record.message) ?? nonBlank(record.msg);
    if (message) return message;
  }
  return fallback;
}

export function statusFallback(status: number, generic: string): string {
  if (status === 400 || status === 422) return "Some details weren't accepted. Check the form and try again.";
  if (status === 409) return "This clashes with a record that already exists.";
  if (status === 413) return "That's too large to send.";
  return generic;
}

export function describeApiBody(body: unknown, fallback: string): string {
  if (!body || typeof body !== "object" || Array.isArray(body)) return fallback;
  const record = body as { message?: unknown; detail?: unknown };
  return nonBlank(record.message) ?? describeApiDetail(record.detail, fallback);
}

function payloadDetail(error: unknown): unknown {
  if (!(error instanceof ApiError)) return undefined;
  const payload = error.payload;
  if (!payload || typeof payload !== "object" || !("detail" in payload)) return undefined;
  return (payload as { detail: unknown }).detail;
}

export function apiErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    const described = describeApiBody(error.payload, "");
    if (described) return described;
  }
  const message = error instanceof Error ? error.message.trim() : "";
  if (!message || message === "[object Object]" || /^\s*[[{]/.test(message) || /status code \d{3}|^HTTP \d{3}/i.test(message)) return fallback;
  return message;
}

export function apiFieldErrors(error: unknown): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const entry of detailEntries(payloadDetail(error))) {
    const field = detailEntryField(entry);
    const sentence = detailEntrySentence(entry);
    if (field && sentence && !(field in errors)) errors[field] = sentence;
  }
  return errors;
}

export { endSession, getSessionMarker } from "@/lib/session";

export const APP_VERSION = `web-${(process.env.NEXT_PUBLIC_APP_VERSION ?? "").trim() || "0.1.0"}`;
export const APP_PLATFORM = "web";
export const APP_VERSION_CODE: string | null = /^[1-9]\d{0,8}$/.test((process.env.NEXT_PUBLIC_BUILD_NUMBER ?? "").trim())
  ? (process.env.NEXT_PUBLIC_BUILD_NUMBER ?? "").trim()
  : null;

let clockOffsetMs: number | null = null;
let clockOffsetAt = 0;

export function clockOffset(): { offsetMs: number; measuredAt: number } | null {
  return clockOffsetMs === null ? null : { offsetMs: clockOffsetMs, measuredAt: clockOffsetAt };
}

export function recordClockOffset(dateHeader: string | null, sentAt: number, now: number = Date.now()) {
  if (!dateHeader) return;
  const server = Date.parse(dateHeader);
  if (Number.isNaN(server)) return;
  clockOffsetMs = server - Math.round((sentAt + now) / 2);
  clockOffsetAt = now;
}

export function sessionReplacedSince(sent: string | null): boolean {
  if (!sent) return false;
  const held = getSessionMarker();
  return held !== null && held !== sent;
}

let passwordChangeOwed = false;
const passwordChangeListeners = new Set<() => void>();

export function sessionOwesPasswordChange(): boolean {
  return passwordChangeOwed;
}

export function setSessionOwesPasswordChange(owed: boolean): void {
  passwordChangeOwed = owed;
}

export function onPasswordChangeRequired(listener: () => void): () => void {
  passwordChangeListeners.add(listener);
  return () => {
    passwordChangeListeners.delete(listener);
  };
}

export type ApiFetchOptions = {
  redirectOn401?: boolean;
  revalidateFromHttpCache?: boolean;
};

export async function apiFetch<T>(
  path: string,
  init: RequestInit = {},
  options: ApiFetchOptions = {}
): Promise<T> {
  return (await apiFetchWithHeaders<T>(path, init, options)).body;
}

export type ApiAnswer<T> = { body: T; headers: Headers };

export async function apiFetchWithHeaders<T>(
  path: string,
  init: RequestInit = {},
  options: ApiFetchOptions = {}
): Promise<ApiAnswer<T>> {
  assertApiConfigured();

  const headers = new Headers(init.headers);
  const token = getSessionMarker();
  if (init.body && !(init.body instanceof FormData) && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  const sentAt = Date.now();
  headers.set("X-Client-Time", new Date(sentAt).toISOString());
  headers.set("X-App-Version", APP_VERSION);
  headers.set("X-App-Platform", APP_PLATFORM);
  if (APP_VERSION_CODE) headers.set("X-App-Version-Code", APP_VERSION_CODE);

  const response = await fetch(`${API_BASE}/api${path}`, {
    ...init,
    headers,
    cache: options.revalidateFromHttpCache ? "no-cache" : "no-store"
  });

  recordClockOffset(response.headers.get("date"), sentAt);
  if (response.status === 204) return { body: undefined as T, headers: response.headers };

  const contentType = response.headers.get("content-type") ?? "";
  const body = contentType.includes("application/json") ? await response.json() : await response.text();

  if (!response.ok) {
    const message = describeApiBody(body, statusFallback(response.status, response.statusText || "Something went wrong. Please try again."));
    const error = new ApiError(response.status, message, body, response.headers);
    if (sessionReplacedSince(token)) throw error;
    if (error.passwordChangeRequired && token && typeof window !== "undefined") {
      passwordChangeOwed = true;
      for (const listener of [...passwordChangeListeners]) {
        try {
          listener();
        } catch {
        }
      }
    } else if (response.status === 401 && token && typeof window !== "undefined") {
      endSession();

      if ((options.redirectOn401 ?? true) && !SIGNED_OUT_PATHS.has(window.location.pathname)) {
        window.location.replace(sessionEndedHref(`${window.location.pathname}${window.location.search ?? ""}`));
      }
    }
    throw error;
  }

  return { body: body as T, headers: response.headers };
}

export function buildQuery(params: Record<string, string | number | undefined | null>) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") search.set(key, String(value));
  });
  const query = search.toString();
  return query ? `?${query}` : "";
}

export async function listResource<T>(path: string, params: Record<string, string | number | undefined | null>) {
  return apiFetch<PageResult<T>>(`${path}${buildQuery(params)}`);
}
