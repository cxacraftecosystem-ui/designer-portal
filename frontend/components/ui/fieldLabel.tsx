"use client";

import { createContext, useContext } from "react";

const FieldLabelContext = createContext<string | undefined>(undefined);

export const FieldLabelProvider = FieldLabelContext.Provider;

export function useFieldLabelId(): string | undefined {
  return useContext(FieldLabelContext);
}
