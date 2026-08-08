/**
 * Where the BROWSER reaches object storage, when that is not where the SERVER reaches it.
 *
 * `POST /media/presign` hands back URLs naming the endpoint the API is configured with. Measured
 * against this stack, one call returns BOTH of these:
 *
 *     uploadUrl  http://minio:9000/design-workshop/media/...?X-Amz-Algorithm=AWS4-HMAC-SHA256...
 *     publicUrl  http://localhost:9000/design-workshop/media/...
 *
 * `minio:9000` resolves inside the docker network and nowhere else, and nothing is published on host
 * port 9000 either — the compose file maps MinIO to 9010. So on a developer machine the upload died
 * with ERR_NAME_NOT_RESOLVED and the just-uploaded picture rendered as a broken image, and every spec
 * touching a photograph, a signature or an attachment failed for a reason that had nothing to do with
 * what it was testing.
 *
 * IT CANNOT BE FIXED BY REWRITING THE URL. SigV4 signs the `Host` header, so the same signature
 * pointed at `127.0.0.1:9010` is refused with `403 SignatureDoesNotMatch`. What works is resolving
 * the NAME somewhere else, which leaves the Host header untouched — hence a resolver rule rather
 * than a route.
 *
 * WHY THIS IS A MODULE AND NOT A LINE IN THE CONFIG. `test.use({ launchOptions })` REPLACES the
 * config's `launchOptions` rather than merging with it, so any spec that needs a browser flag of its
 * own — the camera specs need `--use-fake-device-for-media-stream` — silently loses these rules and
 * goes back to failing on uploads. Both halves therefore come from here: the config spreads
 * `objectStoreArgs()`, and a spec with flags of its own spreads `browserArgs(...)`. One definition,
 * so the two cannot drift.
 */

/** Host port the compose file publishes MinIO on. */
export const MINIO_PORT = process.env.E2E_MINIO_PORT ?? "9010";

const BASE_URL = process.env.E2E_BASE_URL ?? "http://localhost:3000";

/** True when this run is pointed at the local compose stack rather than a deployment. */
export const LOCAL_STACK = /^https?:\/\/(localhost|127\.0\.0\.1)(:|\/|$)/.test(BASE_URL);

/**
 * The `host port` pair Chromium is told to redirect, or "" when no remap is wanted.
 *
 * Empty against a deployment, where the endpoint the API hands out is really reachable and remapping
 * it would break a working setup.
 */
export const OBJECT_STORE_MAP =
  process.env.E2E_OBJECT_STORE_MAP ?? (LOCAL_STACK ? `minio:9000 127.0.0.1:${MINIO_PORT}` : "");

/**
 * Kept in the environment as well as exported, because two specs read the variable directly and a
 * value that disagreed with the flags actually in force is the exact confusion this module removes.
 */
process.env.E2E_OBJECT_STORE_MAP = OBJECT_STORE_MAP;

/**
 * The Chromium flags that make a presigned upload reach MinIO. Empty when no remap is needed, so
 * spreading it is always safe.
 *
 * Both names are mapped: `minio:9000` for the signed PUT and `localhost:9000` for the `publicUrl`
 * the app renders a just-uploaded file from.
 */
export function objectStoreArgs(): string[] {
  if (!OBJECT_STORE_MAP) return [];
  const rules = [`MAP ${OBJECT_STORE_MAP}`, `MAP localhost:9000 127.0.0.1:${MINIO_PORT}`].join(",");
  return [`--host-resolver-rules=${rules}`];
}

/**
 * Browser flags for a spec that needs its own, WITH the object-store rules kept.
 *
 * Use in place of a bare array whenever a spec declares `test.use({ launchOptions })`:
 *
 *     test.use({ launchOptions: { args: browserArgs("--use-fake-device-for-media-stream") } });
 */
export function browserArgs(...extra: string[]): string[] {
  return [...objectStoreArgs(), ...extra];
}
