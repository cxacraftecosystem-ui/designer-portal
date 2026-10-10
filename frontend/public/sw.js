/*
 * The service worker: what lets a page this browser has already opened open again with no
 * connection — the report screen first among them, which can now build a workshop's report on the
 * device (frontend/lib/offlineReport).
 *
 * IT CACHES THE APPLICATION, NEVER THE DATA. Workshop data lives in IndexedDB, written by the app
 * itself (lib/designWorkshopStore, lib/offlineReport/reportCache), stamped with the account that
 * fetched it and cleared on sign-out. Nothing here touches the API: every cross-origin request, and
 * every request that is not a GET, goes straight to the network as if this file did not exist.
 *
 * Four kinds of same-origin GET are answered:
 *   - /_next/static/…     content-hashed build output: cache first, kept until a new worker
 *                         replaces the cache, at most STATIC_LIMIT entries.
 *   - /report-fonts/, /boundaries/, /logos/
 *                         the fonts the browser's .pdf is set in, the map's borders and the
 *                         letterhead logos: cache first.
 *   - a page (a navigation) and its React Server Component payload: network first, and the last
 *     good answer for that path when the network fails, at most PAGE_LIMIT paths, oldest dropped.
 *
 * Bump VERSION whenever this file's caching rules change; `activate` deletes every older cache.
 */

const VERSION = "v1";
const STATIC_CACHE = `dw-static-${VERSION}`;
const ASSET_CACHE = `dw-assets-${VERSION}`;
const PAGE_CACHE = `dw-pages-${VERSION}`;
const STATIC_LIMIT = 600;
const PAGE_LIMIT = 80;
const ASSET_PREFIXES = ["/report-fonts/", "/boundaries/", "/logos/"];

globalThis.addEventListener("install", () => {
  globalThis.skipWaiting();
});

globalThis.addEventListener("activate", (event) => {
  event.waitUntil(
    (async () => {
      const keep = new Set([STATIC_CACHE, ASSET_CACHE, PAGE_CACHE]);
      for (const name of await caches.keys()) {
        if (name.startsWith("dw-") && !keep.has(name)) await caches.delete(name);
      }
      await globalThis.clients.claim();
    })()
  );
});

/** Sign-out asks for the page cache to go, so the next account cannot reopen the last one's screens. */
globalThis.addEventListener("message", (event) => {
  if (event.data && event.data.type === "dw-clear-pages") {
    event.waitUntil(caches.delete(PAGE_CACHE));
  }
});

async function trim(cacheName, limit) {
  const cache = await caches.open(cacheName);
  const keys = await cache.keys();
  for (let i = 0; i < keys.length - limit; i += 1) await cache.delete(keys[i]);
}

async function cacheFirst(request, cacheName, limit) {
  const cache = await caches.open(cacheName);
  const hit = await cache.match(request);
  if (hit) return hit;
  const response = await fetch(request);
  if (response.ok && response.type === "basic") {
    await cache.put(request, response.clone());
    if (limit) void trim(cacheName, limit);
  }
  return response;
}

/** The key a page is cached under: its path, with the RSC payload kept apart from the HTML. */
function pageKey(request, url) {
  const rsc = request.headers.get("RSC") === "1" || url.searchParams.has("_rsc");
  return new Request(`${url.origin}${url.pathname}${rsc ? "?__dw_rsc=1" : ""}`);
}

async function networkFirst(request, url) {
  const cache = await caches.open(PAGE_CACHE);
  const key = pageKey(request, url);
  try {
    const response = await fetch(request);
    if (response.ok && response.type === "basic" && !response.redirected) {
      // Deleted and re-put, so the most recently used path is the last to be trimmed.
      await cache.delete(key);
      await cache.put(key, response.clone());
      void trim(PAGE_CACHE, PAGE_LIMIT);
    }
    return response;
  } catch (error) {
    const hit = await cache.match(key);
    if (hit) return hit;
    throw error;
  }
}

globalThis.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  if (url.origin !== globalThis.location.origin) return;
  if (url.pathname.startsWith("/_next/static/")) {
    event.respondWith(cacheFirst(request, STATIC_CACHE, STATIC_LIMIT));
    return;
  }
  if (ASSET_PREFIXES.some((prefix) => url.pathname.startsWith(prefix))) {
    event.respondWith(cacheFirst(request, ASSET_CACHE, 0));
    return;
  }
  const isPage = request.mode === "navigate" || request.headers.get("RSC") === "1";
  if (isPage && !url.pathname.startsWith("/api/") && !url.pathname.startsWith("/_next/")) {
    event.respondWith(networkFirst(request, url));
  }
});
