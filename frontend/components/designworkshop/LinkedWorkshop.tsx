"use client";

import { createContext, useContext, useMemo } from "react";

const LinkedWorkshopContext = createContext<string | null>(null);

export function LinkedWorkshopProvider({
  workshopId,
  children
}: {
  workshopId: string | null | undefined;
  children: React.ReactNode;
}) {
  const value = useMemo(() => workshopId || null, [workshopId]);
  return <LinkedWorkshopContext.Provider value={value}>{children}</LinkedWorkshopContext.Provider>;
}

export function useLinkedWorkshopId(): string | null {
  return useContext(LinkedWorkshopContext);
}
