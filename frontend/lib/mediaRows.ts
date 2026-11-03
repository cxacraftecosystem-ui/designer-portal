import { ApiError, apiFetch, buildQuery } from "@/lib/api";
import type { MediaFile } from "@/lib/types";

export const MEDIA_BATCH_MAX = 100;

type Waiter = { resolve: (row: MediaFile) => void; reject: (error: unknown) => void };
type BatchAnswer = { items?: MediaFile[]; missing?: string[] };

let queue = new Map<string, Waiter[]>();
let scheduled = false;
let batchUnsupported = false;

function single(id: string): Promise<MediaFile> {
  return apiFetch<MediaFile>(`/media/${id}`);
}

export function fetchMediaRow(id: string): Promise<MediaFile> {
  if (batchUnsupported) return single(id);
  return new Promise<MediaFile>((resolve, reject) => {
    const waiters = queue.get(id);
    if (waiters) waiters.push({ resolve, reject });
    else queue.set(id, [{ resolve, reject }]);
    if (!scheduled) {
      scheduled = true;
      setTimeout(flush, 0);
    }
  });
}

function flush() {
  scheduled = false;
  const pending = queue;
  queue = new Map();
  const ids = [...pending.keys()];
  for (let start = 0; start < ids.length; start += MEDIA_BATCH_MAX) {
    void send(ids.slice(start, start + MEDIA_BATCH_MAX), pending);
  }
}

function settle(pending: Map<string, Waiter[]>, id: string, outcome: (waiter: Waiter) => void) {
  for (const waiter of pending.get(id) ?? []) outcome(waiter);
}

async function viaSingleReads(ids: string[], pending: Map<string, Waiter[]>) {
  await Promise.all(
    ids.map((id) =>
      single(id).then(
        (row) => settle(pending, id, (waiter) => waiter.resolve(row)),
        (error) => settle(pending, id, (waiter) => waiter.reject(error))
      )
    )
  );
}

async function send(ids: string[], pending: Map<string, Waiter[]>) {
  if (batchUnsupported || ids.length === 1) {
    await viaSingleReads(ids, pending);
    return;
  }
  let answer: BatchAnswer;
  try {
    answer = await apiFetch<BatchAnswer>(`/media/batch${buildQuery({ ids: ids.join(",") })}`);
  } catch (error) {
    if (error instanceof ApiError && (error.status === 404 || error.status === 405)) {
      batchUnsupported = true;
      await viaSingleReads(ids, pending);
      return;
    }
    if (error instanceof ApiError && error.status !== 401 && error.status !== 403) {
      await viaSingleReads(ids, pending);
      return;
    }
    for (const id of ids) settle(pending, id, (waiter) => waiter.reject(error));
    return;
  }
  const rows = new Map((answer.items ?? []).map((row) => [row.id, row] as const));
  for (const id of ids) {
    const row = rows.get(id);
    if (row) settle(pending, id, (waiter) => waiter.resolve(row));
    else settle(pending, id, (waiter) => waiter.reject(new ApiError(404, "Record not found", { detail: "Record not found" })));
  }
}

export function resetMediaRowBatching(): void {
  batchUnsupported = false;
  queue = new Map();
  scheduled = false;
}
