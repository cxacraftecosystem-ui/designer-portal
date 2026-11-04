import { blankRow, type DwRow } from "@/lib/designWorkshops";

export const PROTOTYPE_STAGE = "PROTOTYPE_DEVELOPMENT";

export function localToday(now: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

export const prototypeRefOf = (row: DwRow): string => String(row._entryId ?? row._clientKey ?? "");

export function lastStageName(prototype: DwRow, logs: readonly DwRow[]): string {
  const keys = new Set([prototype._entryId, prototype._clientKey].filter(Boolean).map(String));
  let best: { date: string; index: number; name: string } | null = null;
  logs.forEach((log, index) => {
    const name = String(log.stageName ?? "").trim();
    if (!name || !keys.has(String(log.prototypeRef ?? ""))) return;
    const date = String(log.logDate ?? "");
    if (!best || date > best.date || (date === best.date && index > best.index)) best = { date, index, name };
  });
  return (best as { name: string } | null)?.name ?? "";
}

export function logTodayRow(prototype: DwRow, stageName = "", today = localToday()): DwRow {
  return { ...blankRow(), prototypeRef: prototypeRefOf(prototype), logDate: today, ...(stageName ? { stageName } : {}) };
}

