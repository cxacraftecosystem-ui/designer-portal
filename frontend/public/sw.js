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
