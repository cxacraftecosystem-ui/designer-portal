"use client";

import { useEffect, useState } from "react";

import { fetchPendingAccessCount, type PendingAccessCount } from "@/lib/accessRoster";

const FRESH_FOR_MS = 60_000;

type Subscriber = (value: PendingAccessCount | null) => void;

let cached: PendingAccessCount | null = null;
let fetchedAt = 0;
let inFlight: Promise<void> | null = null;
const subscribers = new Set<Subscriber>();

function publish() {
  subscribers.forEach((notify) => notify(cached));
}

export async function refreshPendingAccessCount(): Promise<void> {
  if (inFlight) return inFlight;
  inFlight = fetchPendingAccessCount()
    .then((value) => {
      cached = value;
      fetchedAt = Date.now();
      publish();
    })
    .catch(() => {
    })
    .finally(() => {
      inFlight = null;
    });
  return inFlight;
}

export function usePendingAccessCount(enabled: boolean): PendingAccessCount | null {
  const [value, setValue] = useState<PendingAccessCount | null>(cached);

  useEffect(() => {
    if (!enabled) return;
    const subscriber: Subscriber = setValue;
    subscribers.add(subscriber);
    setValue(cached);
    if (Date.now() - fetchedAt > FRESH_FOR_MS) void refreshPendingAccessCount();

    const onFocus = () => {
      if (Date.now() - fetchedAt > FRESH_FOR_MS) void refreshPendingAccessCount();
    };
    window.addEventListener("focus", onFocus);
    return () => {
      subscribers.delete(subscriber);
      window.removeEventListener("focus", onFocus);
    };
  }, [enabled]);

  return enabled ? value : null;
}
