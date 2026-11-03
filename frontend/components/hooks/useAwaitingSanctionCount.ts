"use client";

import { useEffect, useState } from "react";

import { fetchAwaitingSanctionCount } from "@/lib/sanctionOrders";

const FRESH_FOR_MS = 60_000;

type Subscriber = (value: number | null) => void;

let cached: number | null = null;
let fetchedAt = 0;
let inFlight: Promise<void> | null = null;
const subscribers = new Set<Subscriber>();

function publish() {
  subscribers.forEach((notify) => notify(cached));
}

export async function refreshAwaitingSanctionCount(): Promise<void> {
  if (inFlight) return inFlight;
  inFlight = fetchAwaitingSanctionCount()
    .then((value) => {
      cached = value.awaiting;
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

export function useAwaitingSanctionCount(enabled: boolean): number | null {
  const [value, setValue] = useState<number | null>(cached);

  useEffect(() => {
    if (!enabled) return;
    const subscriber: Subscriber = setValue;
    subscribers.add(subscriber);
    setValue(cached);
    if (Date.now() - fetchedAt > FRESH_FOR_MS) void refreshAwaitingSanctionCount();

    const onFocus = () => {
      if (Date.now() - fetchedAt > FRESH_FOR_MS) void refreshAwaitingSanctionCount();
    };
    window.addEventListener("focus", onFocus);
    return () => {
      subscribers.delete(subscriber);
      window.removeEventListener("focus", onFocus);
    };
  }, [enabled]);

  return enabled ? value : null;
}
