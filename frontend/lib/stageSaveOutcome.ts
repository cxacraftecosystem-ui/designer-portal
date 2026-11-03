import { ApiError } from "@/lib/api";
import type { DwSaveResult } from "@/lib/designWorkshops";

export function stageRefusalResult(err: unknown): DwSaveResult | null {
  if (!(err instanceof ApiError) || err.status !== 422) return null;
  const payload = err.payload;
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) return null;
  const detail = (payload as { detail?: unknown }).detail;
  if (!detail || typeof detail !== "object" || Array.isArray(detail)) return null;
  const errors = (detail as { errors?: unknown }).errors;
  if (!errors || typeof errors !== "object" || Array.isArray(errors)) return null;
  return detail as DwSaveResult;
}

export function stageRefusalWroteCount(result: DwSaveResult | null | undefined): number {
  if (!result) return 0;
  return (result.created ?? 0) + (result.updated ?? 0) + (result.removed ?? 0);
}

export type LocalWriteDecision = {
  bank: boolean;
  retry: boolean;
  pending: boolean;
  failed: boolean;
};

export function localWriteDecision(saved: unknown): LocalWriteDecision {
  const landed = saved !== null && saved !== undefined;
  return { bank: landed, retry: !landed, pending: !landed, failed: !landed };
}
