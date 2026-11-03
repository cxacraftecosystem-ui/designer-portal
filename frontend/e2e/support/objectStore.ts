export const MINIO_PORT = process.env.E2E_MINIO_PORT ?? "9010";

const BASE_URL = process.env.E2E_BASE_URL ?? "http://localhost:3000";

export const LOCAL_STACK = /^https?:\/\/(localhost|127\.0\.0\.1)(:|\/|$)/.test(BASE_URL);

export const OBJECT_STORE_MAP =
  process.env.E2E_OBJECT_STORE_MAP ?? (LOCAL_STACK ? `minio:9000 127.0.0.1:${MINIO_PORT}` : "");

process.env.E2E_OBJECT_STORE_MAP = OBJECT_STORE_MAP;

export function objectStoreArgs(): string[] {
  if (!OBJECT_STORE_MAP) return [];
  const rules = [`MAP ${OBJECT_STORE_MAP}`, `MAP localhost:9000 127.0.0.1:${MINIO_PORT}`].join(",");
  return [`--host-resolver-rules=${rules}`];
}

export function browserArgs(...extra: string[]): string[] {
  return [...objectStoreArgs(), ...extra];
}
