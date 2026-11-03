import { defineConfig, devices } from "@playwright/test";

const BASE_URL = process.env.E2E_BASE_URL ?? "http://localhost:3000";
const API_URL = process.env.E2E_API_URL ?? "http://localhost:8000";

const LOCAL_STACK = /^https?:\/\/(localhost|127\.0\.0\.1)(:|\/|$)/.test(BASE_URL);

const MINIO_PORT = process.env.E2E_MINIO_PORT ?? "9010";
const OBJECT_STORE_MAP = process.env.E2E_OBJECT_STORE_MAP ?? (LOCAL_STACK ? `minio:9000 127.0.0.1:${MINIO_PORT}` : "");
process.env.E2E_OBJECT_STORE_MAP = OBJECT_STORE_MAP;

const resolverRules = OBJECT_STORE_MAP
  ? [`MAP ${OBJECT_STORE_MAP}`, `MAP localhost:9000 127.0.0.1:${MINIO_PORT}`].join(",")
  : "";

process.env.NEXT_PUBLIC_API_URL ??= API_URL;
process.env.E2E_API_URL ??= API_URL;

export default defineConfig({
  testDir: "./e2e",
  globalSetup: "./e2e/support/preflight.ts",
  timeout: 90_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: Number(process.env.E2E_WORKERS ?? 1),
  retries: Number(process.env.E2E_RETRIES ?? 0),
  reporter: [["list"]],
  use: {
    baseURL: BASE_URL,
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
    ...(resolverRules ? { launchOptions: { args: [`--host-resolver-rules=${resolverRules}`] } } : {})
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }]
});
