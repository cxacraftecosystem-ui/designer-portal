import { expect, test } from "@playwright/test";

import { Params, Pipeline, RgbaImage, SvgWriter, VectorModeParam, defaultTraceParams } from "@/lib/trace/engine";
import type { TraceParams } from "@/lib/trace/engine";
import { Tracer, TraceCancelledError, transferableFrom } from "@/lib/trace/traceClient";
import type { TraceWorkerLike } from "@/lib/trace/traceClient";
import type { FromWorker, SerializedTraceResult, ToWorker } from "@/lib/trace/worker/protocol";

function discBounds(w: number, h: number): [number, number, number, number] {
  const r = Math.min(w, h) * 0.3;
  return [w / 2 - r, h / 2 - r, w / 2 + r, h / 2 + r];
}

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

function blankSheet(w: number, h: number): RgbaImage {
  const data = new Uint8ClampedArray(w * h * 4);
  data.fill(255);
  return RgbaImage.fromImageData({ data, width: w, height: h });
}

function outlineParams(): TraceParams {
  const base = defaultTraceParams();
  return {
    ...base,
    output: { ...base.output, vectorMode: VectorModeParam.OUTLINE, fillClosed: true },
    auto: { ...base.auto, mode: Params.AutoMode.OFF }
  };
}

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

test("a filled disc traces to exactly one closed outline on the disc's own bounding box", async () => {
  const result = await Pipeline.run(disc(256, 192), outlineParams());

  expect(result.document.shapeCount()).toBe(1);
  const shape = result.document.layers[0].shapes[0];
  expect(shape.path.closed).toBe(true);

  expect([result.document.width, result.document.height]).toEqual([256, 192]);

  expectBoundsNear(result.document.bounds(), discBounds(256, 192), 3);

  const svg = SvgWriter.write(result.document);
  expect(svg.match(/<path\b/g)).toHaveLength(1);
  expect(svg).toContain('fill="#000000"');
  expect(svg).toContain('stroke="none"');
});

test("a preview traces at a smaller working resolution and still answers in source coordinates", async () => {
  const result = await Pipeline.runPreview(disc(1024, 768), outlineParams(), 256);

  expect(result.workingWidth).toBe(256);
  expect(result.workingHeight).toBe(192);
  expect([result.document.width, result.document.height]).toEqual([1024, 768]);

  expect(result.notes[0]).toMatch(/^Preview at 256x192\./);

  expectBoundsNear(result.document.bounds(), discBounds(1024, 768), 8);
});

test("a blank sheet produces no paths and says so, rather than throwing", async () => {
  const result = await Pipeline.run(blankSheet(256, 192), outlineParams());

  expect(result.document.shapeCount()).toBe(0);
  expect(result.document.nodeCount()).toBe(0);
  expect(result.notes.join(" ")).toContain("No paths were produced");
});

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

  reply(message: FromWorker): void {
    this.onmessage?.({ data: message } as MessageEvent);
  }
}

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
  await Promise.resolve();
  await Promise.resolve();

  expect(worker.posted).toHaveLength(1);
  const sent = worker.posted[0];
  expect(sent.type).toBe("trace");

  expect(transferable.data.byteLength).toBe(0);
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
  expect(worker.posted.map((m) => m.type)).toEqual(["trace", "cancel"]);
  expect(worker.posted[1]).toMatchObject({ type: "cancel", requestId: 1 });
  expect(worker.terminated).toBe(0);

  worker.reply(doneResult(1));
  expect(tracer.busy).toBe(false);
  tracer.dispose();
});

test("a second trace on one tracer supersedes the first, and busy clears when the second answers", async () => {
  const worker = new FakeWorker();
  const tracer = new Tracer({ spawn: () => worker });

  const first = tracer.trace({ image: tinyImage().transferable, params: defaultTraceParams() });
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

  const outcome = await firstOutcome;
  expect(outcome).toBeInstanceOf(TraceCancelledError);
  expect((outcome as Error).message).toBe("Superseded by a newer trace.");

  expect(tracer.busy).toBe(true);
  worker.reply(doneResult(2));
  await second;
  expect(tracer.busy).toBe(false);

  worker.reply(doneResult(1));
  expect(tracer.busy).toBe(false);
  tracer.dispose();
});
