"use client";

import { useEffect, useState } from "react";

import { fetchAwaitingInspectionCount } from "@/lib/designWorkshopInspections";

const FRESH_FOR_MS = 60_000;

type Subscriber = (value: number | null) => void;

let cached: number | null = null;
let fetchedAt = 0;
let inFlight: Promise<void> | null = null;
const subscribers = new Set<Subscriber>();

function publish() {
  subscribers.forEach((notify) => notify(cached));
}

export async function refreshAwaitingInspectionCount(): Promise<void> {
  if (inFlight) return inFlight;
  inFlight = fetchAwaitingInspectionCount()
    .then((value) => {
      cached = typeof value?.awaiting === "number" ? value.awaiting : null;
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

export function useAwaitingInspectionCount(enabled: boolean): number | null {
  const [value, setValue] = useState<number | null>(cached);

  useEffect(() => {
    if (!enabled) return;
    const subscriber: Subscriber = setValue;
    subscribers.add(subscriber);
    setValue(cached);
    if (Date.now() - fetchedAt > FRESH_FOR_MS) void refreshAwaitingInspectionCount();

    const onFocus = () => {
      if (Date.now() - fetchedAt > FRESH_FOR_MS) void refreshAwaitingInspectionCount();
    };
    window.addEventListener("focus", onFocus);
    return () => {
      subscribers.delete(subscriber);
      window.removeEventListener("focus", onFocus);
    };
  }, [enabled]);

  return enabled ? value : null;
}
