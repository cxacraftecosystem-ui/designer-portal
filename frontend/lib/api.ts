import type { PageResult } from "@/lib/types";

import { endSession, getSessionMarker } from "@/lib/session";

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

      if ((options.redirectOn401 ?? true) && window.location.pathname !== "/login") {
        window.location.replace("/login");
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
