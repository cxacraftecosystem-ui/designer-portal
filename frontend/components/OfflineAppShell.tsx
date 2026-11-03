"use client";

import { useEffect } from "react";

export function OfflineAppShell() {
  useEffect(() => {
    if (process.env.NODE_ENV !== "production") return;
    if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return;
    navigator.serviceWorker.register("/sw.js", { scope: "/" }).catch(() => {
    });
  }, []);
  return null;
}

export function forgetOfflinePages(): void {
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return;
  navigator.serviceWorker.controller?.postMessage({ type: "dw-clear-pages" });
}
