import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import { MEDIA_BATCH_MAX, fetchMediaRow, resetMediaRowBatching } from "@/lib/mediaRows";

/**
 * `fetchMediaRow` — the coalescing underneath every photograph on the stage form.
 *
 * What a caller gets must be exactly what `GET /media/{id}` gave it before batching existed: a row,
 * or an `ApiError` 404 for an id with no row. What changes is only how many requests carry them. The
 * network is a stub on `globalThis.fetch`, so these drive the real `apiFetch` underneath.
 */

type Reply = { status: number; body?: unknown };
type Handler = (url: URL) => Reply;

function stub(handler: Handler) {
  const calls: URL[] = [];
  const original = globalThis.fetch;
  globalThis.fetch = (async (input: unknown) => {
    const url = new URL(String(input));
    calls.push(url);
    const reply = handler(url);
    if (reply.status === 0) throw new TypeError("Failed to fetch");
    return new Response(reply.body === undefined ? "" : JSON.stringify(reply.body), {
      status: reply.status,
      headers: { "Content-Type": "application/json" }
    });
  }) as typeof globalThis.fetch;
  return { calls, restore: () => void (globalThis.fetch = original) };
}

const row = (id: string) => ({ id, fileName: `${id}.jpg` });
const batchIds = (url: URL) => (url.searchParams.get("ids") ?? "").split(",").filter(Boolean);
const isBatch = (url: URL) => url.pathname.endsWith("/media/batch");
const singleId = (url: URL) => url.pathname.split("/").pop() ?? "";

test.beforeEach(() => resetMediaRowBatching());

test("lookups asked for in the same tick travel in ONE request, and a repeated id is asked once", async () => {
  const net = stub((url) => ({ status: 200, body: { items: batchIds(url).map(row), missing: [] } }));
  try {
    const answers = await Promise.all([fetchMediaRow("a"), fetchMediaRow("b"), fetchMediaRow("a"), fetchMediaRow("c")]);
    expect(answers.map((answer) => answer.id)).toEqual(["a", "b", "a", "c"]);
    expect(net.calls).toHaveLength(1);
    expect(isBatch(net.calls[0])).toBe(true);
    expect(batchIds(net.calls[0]).sort()).toEqual(["a", "b", "c"]);
  } finally {
    net.restore();
  }
});

test("more ids than the server's ceiling are split into several batches, never refused", async () => {
  const net = stub((url) => ({ status: 200, body: { items: batchIds(url).map(row), missing: [] } }));
  try {
    const ids = Array.from({ length: MEDIA_BATCH_MAX + 7 }, (_, index) => `m${index}`);
    const answers = await Promise.all(ids.map((id) => fetchMediaRow(id)));
    expect(answers).toHaveLength(ids.length);
    expect(net.calls).toHaveLength(2);
    expect(net.calls.map((url) => batchIds(url).length).sort((a, b) => a - b)).toEqual([7, MEDIA_BATCH_MAX]);
  } finally {
    net.restore();
  }
});

test("an id the server has no row for rejects as the single read did: ApiError 404", async () => {
  const net = stub(() => ({ status: 200, body: { items: [row("here")], missing: ["gone"] } }));
  try {
    const [here, gone] = await Promise.allSettled([fetchMediaRow("here"), fetchMediaRow("gone")]);
    expect(here.status).toBe("fulfilled");
    expect(gone.status).toBe("rejected");
    const error = (gone as PromiseRejectedResult).reason;
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(404);
  } finally {
    net.restore();
  }
});

test("a server with no batch route (404) is asked one id at a time, now and for the rest of the tab", async () => {
  const net = stub((url) => (isBatch(url) ? { status: 404, body: { detail: "Not Found" } } : { status: 200, body: row(singleId(url)) }));
  try {
    const first = await Promise.all([fetchMediaRow("a"), fetchMediaRow("b")]);
    expect(first.map((answer) => answer.id)).toEqual(["a", "b"]);
    const later = await Promise.all([fetchMediaRow("c"), fetchMediaRow("d")]);
    expect(later.map((answer) => answer.id)).toEqual(["c", "d"]);
    expect(net.calls.filter(isBatch)).toHaveLength(1);
    expect(net.calls.filter((url) => !isBatch(url)).map(singleId).sort()).toEqual(["a", "b", "c", "d"]);
  } finally {
    net.restore();
  }
});

test("a batch that fails on the server costs no photograph but the one that is really broken", async () => {
  const net = stub((url) => {
    if (isBatch(url)) return { status: 500, body: { detail: "boom" } };
    return singleId(url) === "bad" ? { status: 500, body: { detail: "bad row" } } : { status: 200, body: row(singleId(url)) };
  });
  try {
    const [good, bad, also] = await Promise.allSettled([fetchMediaRow("good"), fetchMediaRow("bad"), fetchMediaRow("also")]);
    expect(good.status).toBe("fulfilled");
    expect(also.status).toBe("fulfilled");
    expect(bad.status).toBe("rejected");
    expect(((bad as PromiseRejectedResult).reason as ApiError).status).toBe(500);
    // The fallback was for that batch only: the next tick tries the batch route again.
    await Promise.allSettled([fetchMediaRow("x"), fetchMediaRow("y")]);
    expect(net.calls.filter(isBatch)).toHaveLength(2);
  } finally {
    net.restore();
  }
});

test("a dropped connection is handed to every waiter once, not retried per photograph", async () => {
  const net = stub(() => ({ status: 0 }));
  try {
    const outcomes = await Promise.allSettled([fetchMediaRow("a"), fetchMediaRow("b"), fetchMediaRow("c")]);
    expect(outcomes.every((outcome) => outcome.status === "rejected")).toBe(true);
    expect(net.calls).toHaveLength(1);
  } finally {
    net.restore();
  }
});
