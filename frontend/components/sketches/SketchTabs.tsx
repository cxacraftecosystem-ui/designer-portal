"use client";

import { useRef } from "react";
import type { LucideIcon } from "lucide-react";

export type SketchTab<Key extends string> = {
  key: Key;
  label: string;
  icon: LucideIcon;
  hint: string;
};

type Props<Key extends string> = {
  tabs: ReadonlyArray<SketchTab<Key>>;
  active: Key;
  onChange: (key: Key) => void;
  label: string;
  idPrefix: string;
};

export function SketchTabs<Key extends string>({ tabs, active, onChange, label, idPrefix }: Props<Key>) {
  const refs = useRef(new Map<string, HTMLButtonElement>());

  function focusTab(key: Key) {
    onChange(key);
    refs.current.get(key)?.focus();
  }

  function onKeyDown(event: React.KeyboardEvent<HTMLButtonElement>, index: number) {
    const last = tabs.length - 1;
    let next: number | null = null;
    if (event.key === "ArrowRight") next = index === last ? 0 : index + 1;
    else if (event.key === "ArrowLeft") next = index === 0 ? last : index - 1;
    else if (event.key === "Home") next = 0;
    else if (event.key === "End") next = last;
    if (next === null) return;
    event.preventDefault();
    focusTab(tabs[next].key);
  }

  const current = tabs.find((tab) => tab.key === active) ?? tabs[0];

  return (
    <div>
      <div role="tablist" aria-label={label} className="flex flex-wrap gap-2 border-b border-line-200 pb-2">
        {tabs.map((tab, index) => {
          const selected = tab.key === active;
          const Icon = tab.icon;
          return (
            <button
              key={tab.key}
              ref={(node) => {
                if (node) refs.current.set(tab.key, node);
                else refs.current.delete(tab.key);
              }}
              type="button"
              role="tab"
              id={`${idPrefix}-tab-${tab.key}`}
              aria-selected={selected}
              aria-controls={`${idPrefix}-panel-${tab.key}`}
              tabIndex={selected ? 0 : -1}
              onClick={() => onChange(tab.key)}
              onKeyDown={(event) => onKeyDown(event, index)}
              className={
                selected
                  ? "inline-flex items-center gap-2 rounded-md border border-purple-700 bg-purple-700 px-4 py-2 text-sm font-semibold text-white shadow-cta transition"
                  : "inline-flex items-center gap-2 rounded-md border border-line-200 bg-card px-4 py-2 text-sm font-medium text-ink-700 transition hover:border-purple-300 hover:bg-purple-50"
              }
            >
              <Icon className="h-4 w-4" aria-hidden />
              {tab.label}
              <span className={selected ? "text-[11px] font-normal text-white/80" : "sr-only"}>
                {selected ? "showing" : "not showing"}
              </span>
            </button>
          );
        })}
      </div>
      <p className="mt-2 text-sm text-ink-muted">{current.hint}</p>
    </div>
  );
}

export function SketchTabPanel({
  idPrefix,
  tabKey,
  children
}: {
  idPrefix: string;
  tabKey: string;
  children: React.ReactNode;
}) {
  return (
    <div
      role="tabpanel"
      id={`${idPrefix}-panel-${tabKey}`}
      aria-labelledby={`${idPrefix}-tab-${tabKey}`}
      tabIndex={-1}
      className="mt-5 outline-hidden"
    >
      {children}
    </div>
  );
}
