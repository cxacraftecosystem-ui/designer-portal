"use client";

import { Children, type ReactNode } from "react";

const COLUMN_CLASS: Record<1 | 2 | 3 | 4, string> = {
  1: "grid gap-3",
  2: "grid gap-3 lg:grid-cols-2",
  3: "grid gap-3 sm:grid-cols-2 lg:grid-cols-3",
  4: "grid gap-3 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-4"
};

export function MediaCardGrid({
  children,
  label,
  maxColumns = 3
}: {
  children: ReactNode;
  label: string;
  maxColumns?: 1 | 2 | 3 | 4;
}) {
  return (
    <ul role="list" aria-label={label} className={COLUMN_CLASS[maxColumns]}>
      {Children.map(children, (child) =>
        child === null || child === undefined || typeof child === "boolean" ? null : (
          <li className="grid min-w-0">{child}</li>
        )
      )}
    </ul>
  );
}
