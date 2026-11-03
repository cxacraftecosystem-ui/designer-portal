import { apiFetch } from "@/lib/api";

export type DesignWorkshopDefaultReason = "GRANTED" | "CREATED" | null;

export type DesignWorkshopDefault = {
  workshopId: string | null;
  title: string | null;
  accessAt: string | null;
  reason: DesignWorkshopDefaultReason;
};

const DEFAULT_TTL_MS = 60_000;

let pending: Promise<DesignWorkshopDefault> | null = null;
let askedAt = 0;

export async function readDesignWorkshopDefault(): Promise<DesignWorkshopDefault | null> {
  const fresh = pending && Date.now() - askedAt <= DEFAULT_TTL_MS;
  if (!fresh) {
    askedAt = Date.now();
    pending = apiFetch<DesignWorkshopDefault>("/design-workshops/default-for-me");
  }
  try {
    return await pending!;
  } catch {
    pending = null;
    askedAt = 0;
    return null;
  }
}

export function designWorkshopDefaultNote(value: DesignWorkshopDefault | null): string | null {
  if (!value?.workshopId || !value.reason) return null;
  const when = readableDay(value.accessAt);
  const tail = when ? ` on ${when}` : "";
  return value.reason === "GRANTED"
    ? `Your latest assigned workshop${tail}. Change if needed.`
    : `Your latest opened workshop${tail}. Change if needed.`;
}

function readableDay(iso: string | null): string | null {
  if (!iso) return null;
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : date.toLocaleDateString();
}
