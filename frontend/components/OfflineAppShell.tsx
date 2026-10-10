"use client";

import { useEffect } from "react";

/**
 * Registers `public/sw.js`, which keeps the application's own files so a page this browser has
 * opened opens again with no connection — see the header of that file for exactly what it keeps.
 *
 * PRODUCTION BUILDS ONLY. Under `next dev` the build output changes on every save and a worker
 * serving the last copy would make a developer debug yesterday's code; it also keeps the browser
 * specs, which drive `next dev` and stub the API with `page.route`, free of a second layer between
 * the page and the network.
 */
export function OfflineAppShell() {
  useEffect(() => {
    if (process.env.NODE_ENV !== "production") return;
    if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return;
    navigator.serviceWorker.register("/sw.js", { scope: "/" }).catch(() => {
      // Without a worker the app behaves exactly as it did before there was one.
    });
  }, []);
  return null;
}

/** Ask the worker to forget the pages it kept — on sign-out, with the report cache beside it. */
export function forgetOfflinePages(): void {
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return;
  navigator.serviceWorker.controller?.postMessage({ type: "dw-clear-pages" });
}
