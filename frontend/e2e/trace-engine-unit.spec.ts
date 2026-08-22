import { expect, test } from "@playwright/test";

import { Params, Pipeline, RgbaImage, SvgWriter, VectorModeParam, defaultTraceParams } from "@/lib/trace/engine";
import type { TraceParams } from "@/lib/trace/engine";
import { Tracer, TraceCancelledError, transferableFrom } from "@/lib/trace/traceClient";
import type { TraceWorkerLike } from "@/lib/trace/traceClient";
import type { FromWorker, SerializedTraceResult, ToWorker } from "@/lib/trace/worker/protocol";

/**
 * The vendored trace engine, driven on shapes whose right answer is known before it runs.
 *
 * WHAT THIS FILE IS FOR. `lib/trace/` is 46 files copied verbatim out of another repository (see
 * `lib/trace/README.md`). Verbatim is what makes a future diff against the upstream possible, and it
 * is also what makes this spec necessary: nothing about copying 716 KB of TypeScript into a Next.js
 * app proves it still computes anything. The portal compiles it under a different `target`, a
 * different `lib`, a different bundler and a different test runner than the one it was written for,
 * and every one of those is a way for arithmetic to survive a typecheck and stop being correct.
 *
 * THE EXPECTATIONS ARE GEOMETRY, NOT A GOLDEN FILE. Every case here is a bitmap this file draws and
 * a property of the answer that follows from the drawing — a filled disc has one outline, that
 * outline's bounding box is the disc's bounding box, a blank sheet has no outlines at all. A golden
 * captured from the engine's own output would go green against an engine that had started returning
 * a constant, which is the failure a vendored copy is most likely to have.
 *
 * WHY IT RUNS IN NODE WITH NO BROWSER. `lib/trace/engine/index.ts` states the property this relies
 * on — "Nothing in this tree touches the DOM… so the whole engine runs unchanged inside a Web
 * Worker" — and a Node spec is the cheapest way to keep that true: the day somebody reaches for
 * `document` inside a stage, this file stops running rather than merely getting slower. It also puts
 * the spec on the right side of the line `e2e/README.md` draws: `-unit` means it needs nothing but
 * node, and this needs nothing but node.
 *
 * WHY THE CLIENT IS TESTED WITH A STAND-IN WORKER. Node has no `Worker`, and the point of the last
 * three cases is not that a worker starts — the production build proves that — but that
 * `traceClient.ts` hands the pixels over as a TRANSFER, cancels rather than terminates, and settles
 * every promise it makes. A stand-in that runs Node's own `structuredClone` with the real transfer
 * list detaches the buffer exactly as a real `postMessage` does, so the one behaviour a caller can
 * get wrong is exercised for real.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Synthetic bitmaps
 * ──────────────────────────────────────────────────────────────────────────── */

/** Where a {@link disc} of the given size actually sits: `[minX, minY, maxX, maxY]` in pixels. */
function discBounds(w: number, h: number): [number, number, number, number] {
  const r = Math.min(w, h) * 0.3;
  return [w / 2 - r, h / 2 - r, w / 2 + r, h / 2 + r];
}

/**
 * A black disc on white, centred, with a radius of 30% of the short edge.
 *
 * A disc rather than a square on purpose: a square's outline is four straight lines that a corner
 * detector recovers by construction, so it would pass even against a tracer that only ever emitted
 * the image's own frame. A circle has no corners, is not axis-aligned anywhere except at four
 * points, and is reproduced only by a curve fitter that is actually following the boundary.
 */
function disc(w: number, h: number): RgbaImage {
  const data = new Uint8ClampedArray(w * h * 4);
  data.fill(255);
  const cx = w / 2;
  const cy = h / 2;
  const r = Math.min(w, h) * 0.3;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const dx = x + 0.5 - cx;
      const dy = y + 0.5 - cy;
      if (dx * dx + dy * dy <= r * r) {
        const i = (y * w + x) * 4;
        data[i] = 0;
        data[i + 1] = 0;
        data[i + 2] = 0;
      }
    }
  }
  return RgbaImage.fromImageData({ data, width: w, height: h });
}

/** An empty sheet. Opaque white, every pixel. */
function blankSheet(w: number, h: number): RgbaImage {
  const data = new Uint8ClampedArray(w * h * 4);
  data.fill(255);
  return RgbaImage.fromImageData({ data, width: w, height: h });
}

/**
 * Outline mode, filled, with auto-detection **off**.
 *
 * All three matter. `CENTERLINE` — the engine's default — traces a filled disc's *skeleton*, which
 * is a knot of short paths near the centre and has no useful bounding box; `OUTLINE` traces its
 * boundary, which is the thing this file can state the answer for. And `AutoMode.SUGGEST`, also the
 * default, runs a classifier that reads a hard-edged disc as line art and prints a paragraph about
 * what it would have changed — harmless, but it makes the note list a function of the classifier
 * rather than of the trace, and the note list is asserted below.
 */
function outlineParams(): TraceParams {
  const base = defaultTraceParams();
  return {
    ...base,
    output: { ...base.output, vectorMode: VectorModeParam.OUTLINE, fillClosed: true },
    auto: { ...base.auto, mode: Params.AutoMode.OFF }
  };
}

/** Asserts a bounding box matches, coordinate by coordinate, so a failure names the edge that moved. */
function expectBoundsNear(
  actual: Float32Array,
  expected: readonly [number, number, number, number],
  tolerancePx: number
): void {
  const edges = ["left", "top", "right", "bottom"] as const;
  for (let i = 0; i < 4; i++) {
    expect(
      Math.abs(actual[i] - expected[i]),
      `${edges[i]} edge: traced ${actual[i].toFixed(2)}, drawn ${expected[i].toFixed(2)}`
    ).toBeLessThanOrEqual(tolerancePx);
  }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The engine
 * ──────────────────────────────────────────────────────────────────────────── */

test("a filled disc traces to exactly one closed outline on the disc's own bounding box", async () => {
  const result = await Pipeline.run(disc(256, 192), outlineParams());

  // One region, one boundary. A disc has no hole, so a second shape would mean the tracer had found
  // an edge in flat white — and a zeroth would mean it had found nothing at all, which is the state
  // a copied-but-broken engine most plausibly lands in.
  expect(result.document.shapeCount()).toBe(1);
  const shape = result.document.layers[0].shapes[0];
  expect(shape.path.closed).toBe(true);

  // Coordinates are reported in the SOURCE frame — the property the whole protocol leans on.
  expect([result.document.width, result.document.height]).toEqual([256, 192]);

  // Measured on 2026-08-22: the traced box was 70.01, 37.70, 184.27, 153.00 against a drawn
  // 70.40, 38.40, 185.60, 153.60 — a worst edge of 1.33 px, which is the inward bias of simplify and
  // curve fitting on a boundary that is anti-aliased over one pixel. 3 px leaves room for that to
  // move a little without leaving room for the outline to be the wrong shape.
  expectBoundsNear(result.document.bounds(), discBounds(256, 192), 3);

  // The engine promises to produce a renderable document, and SVG is what this portal would store in
  // `sketch.lineArtFile`. One `<path>`, and the fill it was asked for rather than a stroke.
  const svg = SvgWriter.write(result.document);
  expect(svg.match(/<path\b/g)).toHaveLength(1);
  expect(svg).toContain('fill="#000000"');
  expect(svg).toContain('stroke="none"');
});

test("a preview traces at a smaller working resolution and still answers in source coordinates", async () => {
  // 1024x768 so the preview genuinely has something to scale down from. `Limits.MIN_WORKING_EDGE` is
  // 256, so a smaller source would be clamped straight back to its own size and the case would prove
  // nothing — which is exactly what a first draft of this test at 256x192 did.
  const result = await Pipeline.runPreview(disc(1024, 768), outlineParams(), 256);

  expect(result.workingWidth).toBe(256);
  expect(result.workingHeight).toBe(192);
  // THE POINT OF THE CASE. The trace ran at a quarter scale and the geometry still comes back in the
  // source frame, because `SerializedTraceResult` promises a preview and a full run agree on
  // `width`/`height`. If they did not, a UI that draws the preview and the full result in one
  // viewport would show the preview at a quarter size and look like a rendering bug.
  expect([result.document.width, result.document.height]).toEqual([1024, 768]);

  // §0.3 requires a preview to say it is one. Rendering that sentence is the client's job; producing
  // it is the engine's, and a silent preview is the failure that gets a designer tuning sliders
  // against detail the export will not reproduce.
  expect(result.notes[0]).toMatch(/^Preview at 256x192\./);

  // Measured on 2026-08-22: the traced box was 283.76, 149.39, 737.49, 612.56 against a drawn
  // 281.60, 153.60, 742.40, 614.40 — a worst edge of 4.91 px on a 461 px disc, or 1.1% of its width.
  // Wider than the 1.33 px of the full-resolution case above, and necessarily so: the trace ran at a
  // quarter scale, so every error in it is multiplied by four on the way back up. That is the honest
  // cost of a preview and the reason `notes[0]` has to say one ran.
  expectBoundsNear(result.document.bounds(), discBounds(1024, 768), 8);
});

test("a blank sheet produces no paths and says so, rather than throwing", async () => {
  // The engine's contract is that it never throws for a legal image, and a blank page is the legal
  // image most likely to break that — every stage downstream of the edge detector receives an empty
  // mask. It has to come back with nothing and a sentence, because "no paths" with no explanation is
  // indistinguishable to a designer from a trace that silently failed.
  const result = await Pipeline.run(blankSheet(256, 192), outlineParams());

  expect(result.document.shapeCount()).toBe(0);
  expect(result.document.nodeCount()).toBe(0);
  expect(result.notes.join(" ")).toContain("No paths were produced");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The client
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A worker that is not one: it records what was posted and lets the test answer.
 *
 * `postMessage` runs Node's own `structuredClone` with the real transfer list rather than merely
 * storing the message, so the caller's `ArrayBuffer` is detached here exactly as a real
 * `postMessage` detaches it. That is the one property of this protocol a caller can get wrong.
 */
class FakeWorker implements TraceWorkerLike {
  readonly posted: ToWorker[] = [];
  terminated = 0;
  onmessage: ((event: MessageEvent) => void) | null = null;
  onerror: ((event: ErrorEvent) => void) | null = null;
  onmessageerror: ((event: MessageEvent) => void) | null = null;

  postMessage(message: ToWorker, options?: { transfer: Transferable[] }): void {
    this.posted.push(structuredClone(message, { transfer: options?.transfer ?? [] }));
  }

  terminate(): void {
    this.terminated += 1;
  }

  /** Delivers a message from the worker to the client, the way `worker.onmessage` would. */
  reply(message: FromWorker): void {
    this.onmessage?.({ data: message } as MessageEvent);
  }
}

/** The smallest thing the client will accept as a finished trace. Nothing here is asserted on. */
function doneResult(requestId: number): Extract<FromWorker, { type: "done" }> {
  const geometry = {
    coords: new Float32Array(0),
    verbs: new Uint8Array(0),
    verbStarts: new Uint32Array(1),
    coordStarts: new Uint32Array(1),
    closed: new Uint8Array(0),
    styleTable: [],
    styleIndex: new Uint32Array(0)
  };
  const result = {
    geometry,
    background: null,
    width: 4,
    height: 4,
    workingWidth: 4,
    workingHeight: 4,
    shapeCount: 0,
    nodeCount: 0,
    stages: [],
    totalMillis: 1,
    notes: [],
    profile: null,
    appliedParams: defaultTraceParams(),
    autoSubjectId: ""
  } satisfies SerializedTraceResult;
  return { type: "done", requestId, result, preview: false };
}

/** Four white pixels, ready to hand over. */
function tinyImage() {
  const pixels = new Uint8ClampedArray(2 * 2 * 4);
  pixels.fill(255);
  return { pixels, transferable: transferableFrom({ data: pixels, width: 2, height: 2 }) };
}

test("the client transfers the pixels rather than copying them, and leaves the caller's own intact", async () => {
  const worker = new FakeWorker();
  const tracer = new Tracer({ spawn: () => worker });
  const { pixels, transferable } = tinyImage();

  const running = tracer.trace({ image: transferable, params: defaultTraceParams() });
  // The post happens after an await inside `trace`, so the message is not there synchronously.
  await Promise.resolve();
  await Promise.resolve();

  expect(worker.posted).toHaveLength(1);
  const sent = worker.posted[0];
  expect(sent.type).toBe("trace");

  // TRANSFERRED, not cloned: the buffer this call was handed is now detached and its bytes belong to
  // the other side. A `postMessage` that had copied instead would leave 16 here.
  expect(transferable.data.byteLength).toBe(0);
  // …and `transferableFrom` is why the CALLER can still trace the same image again. This is the
  // whole reason that helper exists: transferring the original made "the second trace produces a
  // blank image" upstream, a rendering bug rather than an error.
  expect(pixels.length).toBe(4 * 4);
  expect(pixels[0]).toBe(255);

  worker.reply(doneResult(1));
  const result = await running;
  expect(result.width).toBe(4);
  tracer.dispose();
  expect(worker.terminated).toBe(1);
});

test("an error from the worker rejects with the worker's own sentence", async () => {
  const worker = new FakeWorker();
  const tracer = new Tracer({ spawn: () => worker });
  const { transferable } = tinyImage();

  const running = tracer.trace({ image: transferable, params: defaultTraceParams() });
  await Promise.resolve();
  await Promise.resolve();
  // The worker writes sentences fit to show a user — never a stack trace — and the client must carry
  // them through unedited rather than replacing them with a generic apology.
  worker.reply({
    type: "error",
    requestId: 1,
    message: "That image needs more memory than this browser will give the page."
  });

  await expect(running).rejects.toThrow(/more memory than this browser will give the page/);
  expect(tracer.busy).toBe(false);
  tracer.dispose();
});

test("aborting cancels the worker instead of terminating it, and settles the promise", async () => {
  const worker = new FakeWorker();
  const tracer = new Tracer({ spawn: () => worker });
  const { transferable } = tinyImage();
  const controller = new AbortController();

  const running = tracer.trace({
    image: transferable,
    params: defaultTraceParams(),
    signal: controller.signal
  });
  await Promise.resolve();
  await Promise.resolve();
  controller.abort();

  await expect(running).rejects.toBeInstanceOf(TraceCancelledError);
  // A `cancel` for the same request, and NOT a terminate: the pipeline yields at every stage
  // boundary, so the worker reads the token within one stage, and tearing it down instead would pay
  // to re-parse the whole engine on the next request.
  expect(worker.posted.map((m) => m.type)).toEqual(["trace", "cancel"]);
  expect(worker.posted[1]).toMatchObject({ type: "cancel", requestId: 1 });
  expect(worker.terminated).toBe(0);

  // A late `done` for a request the caller has given up on must not resurrect anything.
  worker.reply(doneResult(1));
  expect(tracer.busy).toBe(false);
  tracer.dispose();
});

test("a second trace on one tracer supersedes the first, and busy clears when the second answers", async () => {
  const worker = new FakeWorker();
  const tracer = new Tracer({ spawn: () => worker });

  const first = tracer.trace({ image: tinyImage().transferable, params: defaultTraceParams() });
  // `.catch` attached now, not after the second call: an unhandled rejection between the two would
  // be a warning in this run and a failed process in a stricter one.
  const firstOutcome = first.then(
    () => "resolved",
    (err: Error) => err
  );
  await Promise.resolve();
  await Promise.resolve();

  const second = tracer.trace({ image: tinyImage().transferable, params: defaultTraceParams() });
  await Promise.resolve();
  await Promise.resolve();
  expect(worker.posted.map((m) => m.type)).toEqual(["trace", "trace"]);

  // THE POINT OF THE CASE. `worker/trace.worker.ts` supersedes in silence — a second `trace` cancels
  // the running one and `runOne` returns without posting `done`, `error` or `progress` — so nothing
  // will ever arrive for request 1. Before this was settled here, its entry stayed in `inFlight` for
  // the lifetime of the tracer, holding the caller's promise, their `onProgress` closure and an
  // `abort` listener on their signal, and `busy` was stuck true with nothing left to cancel.
  const outcome = await firstOutcome;
  expect(outcome).toBeInstanceOf(TraceCancelledError);
  expect((outcome as Error).message).toBe("Superseded by a newer trace.");

  // Only the newest request is outstanding, and answering it leaves the tracer idle — which is what
  // makes `busy` honest as the enabled state of a Cancel control.
  expect(tracer.busy).toBe(true);
  worker.reply(doneResult(2));
  await second;
  expect(tracer.busy).toBe(false);

  // A `done` for the superseded request cannot arrive from the real worker, and if one somehow did
  // it must not resolve a promise that has already rejected.
  worker.reply(doneResult(1));
  expect(tracer.busy).toBe(false);
  tracer.dispose();
});
