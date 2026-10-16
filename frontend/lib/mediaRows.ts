/**
 * `GET /media/{id}`, COALESCED — every lookup asked for in the same tick travels in one request.
 *
 * WHY IT EXISTS. A stage of a design workshop holds dozens of photographs and every one of them was
 * resolved by its own `GET /media/{id}`: forty authenticated round trips, six at a time over one
 * HTTP/1.1 origin, queued behind each other before a gallery could print a single file name. That
 * queue was the longest serial chain on the stage form's open (measured by
 * `e2e/stage-form-speed.spec.ts`). The fields that ask are many separate components and none of them
 * can see the others, so the batching is done here, underneath them, rather than by threading a
 * shared list through the form.
 *
 * WHAT A CALLER GETS IS EXACTLY WHAT THE SINGLE READ GAVE IT. `GET /media/batch` encodes every row
 * through the same `_public` the single read uses, so a row's `url` is present for exactly the callers
 * the single read handed it to. An id the server has no row for is answered as the single read
 * answered it — an `ApiError` with status 404 and the server's own sentence — so every caller's
 * existing `catch` keeps meaning what it meant.
 *
 * SEPARATE DEPLOYS. The web client can reach a server that predates the batch route; there,
 * `/media/batch` falls through to `GET /media/{id}` and answers 404. That answer switches this module
 * to one request per id for the rest of the tab — the old behaviour, exactly — rather than reporting
 * forty photographs as missing.
 */

import { ApiError, apiFetch, buildQuery } from "@/lib/api";
import type { MediaFile } from "@/lib/types";

/** The server's `MEDIA_BATCH_MAX`. A longer queue is split, never refused. */
export const MEDIA_BATCH_MAX = 100;

type Waiter = { resolve: (row: MediaFile) => void; reject: (error: unknown) => void };
type BatchAnswer = { items?: MediaFile[]; missing?: string[] };

let queue = new Map<string, Waiter[]>();
let scheduled = false;
/** Set once a server has shown it has no batch route; from then on every lookup is the single read. */
let batchUnsupported = false;

function single(id: string): Promise<MediaFile> {
  return apiFetch<MediaFile>(`/media/${id}`);
}

/**
 * One media row, as `GET /media/{id}` answers it — but sent together with every other row asked for
 * before the current task ends.
 */
export function fetchMediaRow(id: string): Promise<MediaFile> {
  if (batchUnsupported) return single(id);
  return new Promise<MediaFile>((resolve, reject) => {
    const waiters = queue.get(id);
    if (waiters) waiters.push({ resolve, reject });
    else queue.set(id, [{ resolve, reject }]);
    if (!scheduled) {
      scheduled = true;
      // A macrotask, not a microtask: the fields of one form mount in one commit but resolve their
      // ids from effects that React runs across a few microtask boundaries.
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
      // A server from before the batch route. Ask the way it understands, now and from now on.
      batchUnsupported = true;
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

/** Forget the "this server has no batch route" verdict. FOR TESTS AND FOR NOTHING ELSE. */
export function resetMediaRowBatching(): void {
  batchUnsupported = false;
  queue = new Map();
  scheduled = false;
}
