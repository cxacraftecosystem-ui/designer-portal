"use client";

import { useCallback, useEffect, useRef, useState } from "react";

const PREFIX = "field_repo_megacards_";

function readRaw(key: string): string[] | null {
  try {
    const raw = window.localStorage.getItem(PREFIX + key);
    if (raw === null) return null;
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return null;
    return parsed.filter((entry): entry is string => typeof entry === "string");
  } catch {
    return null;
  }
}

function write(key: string, ids: string[]): void {
  try {
    window.localStorage.setItem(PREFIX + key, JSON.stringify(ids));
  } catch {
  }
}

export function useMegaCards(
  storageKey: string,
  defaultOpen: readonly string[] = []
): {
  isOpen: (id: string) => boolean;
  toggle: (id: string) => void;
} {
  const [open, setOpen] = useState<ReadonlySet<string>>(() => new Set());

  const hydrated = useRef(false);

  const defaults = useRef(defaultOpen);

  useEffect(() => {
    const stored = readRaw(storageKey);
    setOpen(new Set(stored ?? defaults.current));
    hydrated.current = true;
  }, [storageKey]);

  const toggle = useCallback(
    (id: string) => {
      setOpen((current) => {
        const next = new Set(current);
        if (next.has(id)) next.delete(id);
        else next.add(id);
        if (hydrated.current) write(storageKey, [...next]);
        return next;
      });
    },
    [storageKey]
  );

  const isOpen = useCallback((id: string) => open.has(id), [open]);

  return { isOpen, toggle };
}
