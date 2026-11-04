"use client";

import { useEffect, useState } from "react";

import { OPEN_TASK_COUNT_PATH } from "@/components/tasks/openTaskCount";
import { apiFetch } from "@/lib/api";
import type { PageResult } from "@/lib/types";

const FRESH_FOR_MS = 60_000;

type Subscriber = (value: number | null) => void;

let cached: number | null = null;
let fetchedAt = 0;
let inFlight: Promise<void> | null = null;
const subscribers = new Set<Subscriber>();

function publish() {
  subscribers.forEach((notify) => notify(cached));
}

export async function refreshOpenTaskCount(): Promise<void> {
  if (inFlight) return inFlight;
  inFlight = apiFetch<PageResult<unknown>>(OPEN_TASK_COUNT_PATH)
    .then((page) => {
      cached = page.total;
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

export function useOpenTaskCount(enabled: boolean): number | null {
  const [value, setValue] = useState<number | null>(cached);

  useEffect(() => {
    if (!enabled) return;
    const subscriber: Subscriber = setValue;
    subscribers.add(subscriber);
    setValue(cached);
    if (Date.now() - fetchedAt > FRESH_FOR_MS) void refreshOpenTaskCount();

    const onFocus = () => {
      if (Date.now() - fetchedAt > FRESH_FOR_MS) void refreshOpenTaskCount();
    };
    window.addEventListener("focus", onFocus);
    return () => {
      subscribers.delete(subscriber);
      window.removeEventListener("focus", onFocus);
    };
  }, [enabled]);

  return enabled ? value : null;
}
