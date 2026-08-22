import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end config. Deliberately does NOT start its own server: these specs are pointed at a
 * dev server the developer is already running, or at a deployed URL via E2E_BASE_URL, so a run
 * never silently tests a different build from the one on screen.
 *
 * Credentials come from the environment rather than a fixture — the specs sign in against a real
 * API, and a checked-in password would be a credential in git. See e2e/README.md for the one
 * command that runs this suite and what each variable is for.
 */

const BASE_URL = process.env.E2E_BASE_URL ?? "http://localhost:3000";
const API_URL = process.env.E2E_API_URL ?? "http://localhost:8000";

/** True when this run is pointed at the local compose stack rather than a deployment. */
const LOCAL_STACK = /^https?:\/\/(localhost|127\.0\.0\.1)(:|\/|$)/.test(BASE_URL);

/**
 * WHERE THE BROWSER LOOKS FOR OBJECT STORAGE, when that is not where the server looks.
 *
 * `POST /media/presign` hands back a URL naming the endpoint the API is configured with. In the
 * compose stack that is `http://minio:9000` — a name that resolves inside the docker network and
 * nowhere else — so every browser upload on a developer machine died with ERR_NAME_NOT_RESOLVED,
 * and every spec that touches a photograph, a signature or an attachment failed for a reason that
 * had nothing to do with what it was testing.
 *
 * It cannot be fixed by rewriting the URL. SigV4 signs the `Host` header, so pointing the same
 * signature at 127.0.0.1 is refused — measured, not assumed: the rewritten PUT returns
 * `403 SignatureDoesNotMatch` and the identical PUT with the host name left alone returns 200.
 * What works is resolving the NAME somewhere else, which leaves the Host header untouched; hence a
 * resolver rule rather than a route.
 *
 * `localhost:9000` is mapped for the same reason: `presign` also returns a `publicUrl` on that
 * origin (lib/media.ts renders a just-uploaded file from it) and nothing is published on host port
 * 9000 either — the compose file maps MinIO to 9010.
 *
 * Set here rather than in each spec so it is true by default and no spec has to reinvent it. Three
 * of them already read `E2E_OBJECT_STORE_MAP` and declare their own `launchOptions`, which REPLACES
 * this one, so the variable is exported below with the same value — both routes then agree instead
 * of quietly differing.
 *
 * Left empty when a run is pointed at a deployment, where the endpoint the API hands out is really
 * reachable and remapping it would break a working setup.
 */
const MINIO_PORT = process.env.E2E_MINIO_PORT ?? "9010";
const OBJECT_STORE_MAP = process.env.E2E_OBJECT_STORE_MAP ?? (LOCAL_STACK ? `minio:9000 127.0.0.1:${MINIO_PORT}` : "");
process.env.E2E_OBJECT_STORE_MAP = OBJECT_STORE_MAP;

const resolverRules = OBJECT_STORE_MAP
  ? [`MAP ${OBJECT_STORE_MAP}`, `MAP localhost:9000 127.0.0.1:${MINIO_PORT}`].join(",")
  : "";

/**
 * `NEXT_PUBLIC_API_URL` is read by the PLAYWRIGHT process, not only by the app.
 *
 * `carry-context.spec.ts` builds its fixture by calling the API from inside the page and falls back
 * to a production CloudFront origin when this is unset — so an unset variable did not merely fail,
 * it pointed a local test run at production data. Defaulting it here means the fallback is never
 * reached. It is deliberately `??=`: a developer who exports it wins.
 */
process.env.NEXT_PUBLIC_API_URL ??= API_URL;
process.env.E2E_API_URL ??= API_URL;

export default defineConfig({
  testDir: "./e2e",
  /**
   * Checked ONCE, before the first spec: is the stack this suite needs actually there?
   *
   * Nothing here starts a server, so a run against a stopped stack used to report a hundred
   * unrelated failures whose only real content was one unstated fact. `e2e/support/preflight.ts`
   * states it — and only when the run has credentials and has selected something other than the
   * service-free `-unit` specs, so the CI unit gate is untouched.
   */
  globalSetup: "./e2e/support/preflight.ts",
  timeout: 90_000,
  expect: { timeout: 15_000 },
  // The specs sign in as the same user and navigate real records; running them concurrently would
  // have them fighting over one session. Raising E2E_WORKERS is possible but not free — the API
  // rate-limits repeated logins from one address, and several specs share the showcase workshop.
  fullyParallel: false,
  workers: Number(process.env.E2E_WORKERS ?? 1),
  // Zero by default on purpose: a retry turns a real intermittent defect into a green run, and this
  // suite exists to be believed. Pass --retries=1 when you are triaging, to make Playwright label
  // "failed then passed" as flaky rather than leaving you to guess.
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
