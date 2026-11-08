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

function fieldLabel(field: string | null): string {
  if (!field) return "This field";
  const words = field.replace(/([a-z0-9])([A-Z])/g, "$1 $2").replace(/[_-]+/g, " ").trim().toLowerCase();
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : "This field";
}

export function validationSentence(field: string | null, message: string): string | null {
  const text = message.trim();
  if (/valid email address/i.test(text) || (field && /^e-?mail$/i.test(field) && /^value is not a valid/i.test(text))) return "Enter a valid email address.";
  if (/^Field required$/i.test(text)) return field && /^e-?mail$/i.test(field) ? "Enter your email address." : `${fieldLabel(field)} is required.`;
  const least = /^String should have at least (\d+) characters?$/i.exec(text);
  if (least) return `${fieldLabel(field)} needs at least ${least[1]} characters.`;
  const most = /^String should have at most (\d+) characters?$/i.exec(text);
  if (most) return `${fieldLabel(field)} can be at most ${most[1]} characters.`;
  if (/^(Input should be|value is not a valid)/i.test(text)) return `Check ${fieldLabel(field).toLowerCase()}.`;
  return null;
}

export function describeApiDetail(detail: unknown, fallback: string): string {
  if (typeof detail === "string" && detail.trim()) return detail;
  if (Array.isArray(detail)) {
    const messages = detail
      .map((entry) => {
        if (!entry || typeof entry !== "object") return "";
        const record = entry as { msg?: unknown; loc?: unknown };
        const message = "msg" in record ? String(record.msg) : "";
        if (!message) return "";
        const field = Array.isArray(record.loc)
          ? record.loc.filter((part): part is string => typeof part === "string").at(-1)
          : null;
        if (!/^(Value error|Assertion failed),/.test(message)) {
          const human = validationSentence(field && field !== "body" ? field : null, message);
          if (human) return human;
        }
        const cleaned = message.replace(/^Value error,\s*/, "").trim();
        return field && field !== "body" ? `${field}: ${cleaned}` : cleaned;
      })
      .filter(Boolean);
    if (messages.length) return messages.join(" ");
  }
  if (detail && typeof detail === "object" && "message" in detail) {
    const message = String((detail as { message: unknown }).message);
    if (message.trim()) return message;
  }
  return fallback;
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
    const detail = typeof body === "object" && body && "detail" in body ? (body as { detail: unknown }).detail : undefined;
    const message = describeApiDetail(detail, response.statusText || "Something went wrong. Please try again.");
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
