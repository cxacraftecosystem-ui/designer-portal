import { readFileSync } from "node:fs";
import { join } from "node:path";
import { deflateSync } from "node:zlib";

import { expect, test, type Page } from "@playwright/test";
import * as ts from "typescript";

const UPLOAD_DIR = join(__dirname, "..", "components", "sketches", "upload");
const NODE_MODULES = join(__dirname, "..", "node_modules");
const ENGINE_PARAMS = join(__dirname, "..", "lib", "trace", "engine", "params.ts");
const REVEAL = join(__dirname, "..", "components", "ui", "reveal1.tsx");
const REVEAL_TRANSFORM = join(__dirname, "..", "components", "ui", "reveal1Transform.ts");
const UTILS = join(__dirname, "..", "lib", "utils.ts");

const RUNTIME_LOAD_MS = 60;

const TARGET_LABEL = "Line art / vector file";

function compile(file: string): string {
  return ts.transpileModule(readFileSync(file, "utf8"), {
    fileName: file,
    compilerOptions: {
      target: ts.ScriptTarget.ES2020,
      module: ts.ModuleKind.CommonJS,
      jsx: ts.JsxEmit.ReactJSX
    }
  }).outputText;
}

const LOADER = `
window.process = window.process || { env: { NODE_ENV: "production" } };
window.__mods = {};
window.__define = function (id, factory) { window.__mods[id] = { factory: factory, mod: null }; };
window.__require = function (id) {
  var entry = window.__mods[id];
  if (!entry) throw new Error("The harness has no module named " + id);
  if (entry.mod === null) {
    entry.mod = { exports: {} };
    entry.factory(entry.mod, entry.mod.exports, window.__require);
  }
  return entry.mod.exports;
};
`;

function define(id: string, code: string): string {
  return `window.__define(${JSON.stringify(id)}, function (module, exports, require) {\n${code}\n});`;
}

const LUCIDE_STUB = `
window.__define("lucide-react", function (module, exports, require) {
  module.exports = new Proxy({}, {
    get: function (target, key) {
      if (key === "__esModule") return true;
      return function Icon() { return null; };
    }
  });
});
`;

const DROPDOWN_STUB = `
window.__define("@/components/ui/Dropdown", function (module, exports, require) {
  var React = require("react");
  module.exports.Dropdown = function (props) {
    return React.createElement(
      "select",
      {
        value: props.value,
        disabled: !!props.disabled,
        "aria-label": props.ariaLabel,
        "aria-describedby": props.describedBy,
        onChange: function (event) { props.onChange(event.target.value); }
      },
      (props.options || []).map(function (option) {
        return React.createElement("option", { key: option.value, value: option.value }, option.label);
      })
    );
  };
});
`;

const DOWNLOAD_STUB = `
window.__define("@/lib/designWorkshops", function (module, exports, require) {
  module.exports.saveBlobToDisk = function (blob, fileName) {
    window.__saved.push({ name: fileName, type: blob.type, size: blob.size, blob: blob });
  };
});
`;

const RUNTIME_STUB = `
window.__define("./traceRuntime", function (module, exports, require) {
  var engine = require("@/lib/trace/engine/params");
  var delay = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };

  function Cancelled() { var e = new Error("The trace was cancelled."); e.__cancelled = true; return e; }
  function Unavailable(m) { var e = new Error(m); e.__unavailable = true; return e; }

  function result(width, height, preview, params) {
    var working = preview ? Math.max(1, Math.round(width / 2)) : width;
    return {
      geometry: {
        coords: new Float32Array([2, 2, width - 2, 2, width - 2, height - 2]),
        verbs: new Uint8Array([0, 0]),
        verbStarts: new Uint32Array([0, 2]),
        coordStarts: new Uint32Array([0, 6]),
        closed: new Uint8Array([0]),
        styleTable: [{
          stroke: 0xff000000, strokeWidth: 1.5, fill: null, fillRule: "NONZERO",
          cap: "ROUND", join: "ROUND", miterLimit: 4, opacity: 1
        }],
        styleIndex: new Uint32Array([0])
      },
      background: null,
      width: width,
      height: height,
      workingWidth: working,
      workingHeight: preview ? Math.max(1, Math.round(height / 2)) : height,
      shapeCount: 1,
      nodeCount: 3,
      stages: [],
      totalMillis: 12,
      notes: ["A stub engine traced this."],
      profile: null,
      appliedParams: params,
      autoSubjectId: ""
    };
  }

  function Tracer() {}
  Tracer.prototype.trace = function (request) {
    window.__traces.push({
      width: request.image.width,
      height: request.image.height,
      preview: !!request.preview
    });
    /*
      PROGRESS FOR A FULL RUN ONLY, AND WITH A REAL STAGE ID — which is what the worker does:
      trace.worker.ts hands Pipeline.run a progress callback and hands Pipeline.runPreview none at
      all. A stub that reported progress for a preview too would let a bar ship that the real engine
      never draws, and would hide the fact that the panel keys its bar off there being any.
    */
    if (request.onProgress && !request.preview) {
      request.onProgress({ stageId: "edge", label: "Edges", fraction: 6 / 12 });
    }
    var self = this;
    return delay(window.__traceMs).then(function () {
      if (request.signal && request.signal.aborted) throw Cancelled();
      // NEITHER CANCELLED NOR UNAVAILABLE — an ordinary failed trace, which is a third state the panel
      // has to render differently from both: the drawing area keeps its sentence, the comparator says
      // there is nothing to compare and points at the message, and no button claims anything worked.
      if (window.__traceFails) throw new Error("That photograph could not be traced.");
      return result(request.image.width, request.image.height, !!request.preview, request.params);
    });
  };
  Tracer.prototype.dispose = function () { window.__disposals += 1; };

  var runtime = {
    Tracer: Tracer,
    transferableFrom: function (source) {
      return { data: source.data.slice(), width: source.width, height: source.height };
    },
    defaults: engine.defaultTraceParams(),
    sanitize: engine.sanitizeTraceParams,
    isUnavailable: function (error) { return !!(error && error.__unavailable); },
    isCancelled: function (error) { return !!(error && error.__cancelled); }
  };

  module.exports.loadTraceRuntime = function () {
    window.__runtimeLoads += 1;
    return delay(window.__runtimeMs).then(function () {
      if (window.__runtimeFails) {
        throw Unavailable("The tracing engine could not be loaded. Check your connection and reload the page.");
      }
      return runtime;
    });
  };
  /*
    loadImageEditor, STOOD IN FOR — and the stand-in really crops, because the panel's honesty about
    which frame is being traced is what these cases can check. window.__edits records every request,
    so a case can assert that pressing "Use this frame for the trace" is what changes the size the
    tracer is handed, rather than asserting on a sentence the panel wrote about itself.

    The real one starts a module worker and imports engine/contrast, neither of which this registry
    can resolve — e2e/sketch-frame-sharpen-unit.spec.ts is where the arithmetic itself is exercised,
    on a laptop, with no browser.

    NO BACKTICKS ANYWHERE IN THIS STUB. It is the body of a template literal in the spec file, so one
    backtick in a comment closes the string and the whole file stops parsing.
  */
  function ImageEditor() {}
  ImageEditor.prototype.edit = function (call) {
    window.__edits.push({
      width: call.pixels.width,
      height: call.pixels.height,
      crop: call.crop,
      sharpen: call.sharpen
    });
    return delay(window.__editMs).then(function () {
      if (window.__editFails) throw new Error("This device would not process that photograph.");
      var w = call.crop.width;
      var h = call.crop.height;
      var out = new Uint8ClampedArray(w * h * 4);
      for (var row = 0; row < h; row++) {
        var from = ((call.crop.y + row) * call.pixels.width + call.crop.x) * 4;
        out.set(call.pixels.data.subarray(from, from + w * 4), row * w * 4);
      }
      /*
        THE NOTE COMES BACK FROM THE EDITOR — the real one builds it inside the worker, with
        lib/trace/imageEdit.describeEdit, which is the only side of the bundle boundary that can call
        it. The panel used to write its own copy of that sentence instead;
        e2e/sketch-frame-sharpen-unit.spec.ts pins the real function's wording, and the last case in
        this file pins that whatever the editor hands back is what reaches the exported SVG. So this
        stand-in deliberately says something the real function would NOT: a panel that quietly rebuilt
        the sentence itself would satisfy a stub that agreed with it, and fails against this one.

        (No backticks in here, per the stub's own warning at the top: this is the body of a template
        literal, and one backtick in a comment closes the string and stops the file parsing.)
      */
      var note = "Cropped on the device to " + w + "x" + h + " at (" + call.crop.x + ", " + call.crop.y +
        ") of " + call.pixels.width + "x" + call.pixels.height + ". [from the editor]";
      return { data: out, width: w, height: h, note: note, millis: 7 };
    });
  };
  ImageEditor.prototype.dispose = function () { window.__editorDisposals += 1; };

  module.exports.loadImageEditor = function () {
    return delay(0).then(function () {
      return {
        ImageEditor: ImageEditor,
        isUnavailable: function () { return false; },
        isCancelled: function (error) { return !!(error && error.name === "ImageEditCancelledError"); }
      };
    });
  };

  module.exports.loadTracePresets = function () {
    return delay(0).then(function () {
      return {
        styles: [{
          id: "ink", name: "Ink line", description: "A stub style.", group: "Line",
          params: runtime.defaults
        }],
        styleGroups: ["Line"],
        subjects: [{ id: "pencil", name: "Pencil on paper", hint: "A stub subject.", adjust: function (p) { return p; } }]
      };
    });
  };
});
`;

async function mount(page: Page, options: { runtimeMs?: number; traceMs?: number; withSource?: boolean } = {}) {
  await page.setContent('<div id="root"></div>');
  await page.addScriptTag({ content: LOADER });
  await page.evaluate(
    ([runtimeMs, traceMs]) => {
      const w = window as unknown as Record<string, unknown>;
      w.__runtimeMs = runtimeMs;
      w.__traceMs = traceMs;
      w.__runtimeLoads = 0;
      w.__disposals = 0;
      w.__runtimeFails = false;
      w.__traceFails = false;
      w.__traces = [];
      w.__attached = [];
      w.__sources = [];
      w.__saved = [];
      w.__decodeDelays = {};
      w.__edits = [];
      w.__editMs = 0;
      w.__editFails = false;
      w.__editorDisposals = 0;
    },
    [options.runtimeMs ?? RUNTIME_LOAD_MS, options.traceMs ?? 0]
  );

  await page.evaluate(() => {
    const real = window.createImageBitmap.bind(window);
    const delays = (window as unknown as { __decodeDelays: Record<string, number> }).__decodeDelays;
    window.createImageBitmap = (async (source: unknown, ...rest: unknown[]) => {
      const name = source && typeof source === "object" && "name" in source ? String((source as File).name) : "";
      const wait = delays[name] ?? 0;
      if (wait > 0) await new Promise((r) => setTimeout(r, wait));
      return await (real as (...a: unknown[]) => Promise<ImageBitmap>)(source, ...rest);
    }) as typeof window.createImageBitmap;
  });

  for (const [id, file] of [
    ["react", join(NODE_MODULES, "react", "cjs", "react.production.js")],
    ["react/jsx-runtime", join(NODE_MODULES, "react", "cjs", "react-jsx-runtime.production.js")],
    ["scheduler", join(NODE_MODULES, "scheduler", "cjs", "scheduler.production.js")],
    ["react-dom", join(NODE_MODULES, "react-dom", "cjs", "react-dom.production.js")],
    ["react-dom/client", join(NODE_MODULES, "react-dom", "cjs", "react-dom-client.production.js")]
  ] as const) {
    await page.addScriptTag({ content: define(id, readFileSync(file, "utf8")) });
  }

  await page.addScriptTag({ content: LUCIDE_STUB });
  await page.addScriptTag({ content: DROPDOWN_STUB });
  await page.addScriptTag({ content: DOWNLOAD_STUB });
  await page.addScriptTag({ content: define("@/lib/trace/engine/params", compile(ENGINE_PARAMS)) });
  await page.addScriptTag({ content: define("@/lib/utils", compile(UTILS)) });
  await page.addScriptTag({ content: define("./reveal1Transform", compile(REVEAL_TRANSFORM)) });
  await page.addScriptTag({
    content: define("@/components/ui/reveal1Transform", compile(REVEAL_TRANSFORM))
  });
  await page.addScriptTag({ content: define("@/components/ui/reveal1", compile(REVEAL)) });
  await page.addScriptTag({ content: RUNTIME_STUB });
  for (const name of [
    "geometryToSvg.ts",
    "traceParamTable.ts",
    "decodeToPixels.ts",
    "traceExport.ts",
    "comparisonPlates.ts",
    "traceStages.ts",
    "frameGeometry.ts",
    "DropCard.tsx",
    "FramePanel.tsx"
  ]) {
    await page.addScriptTag({
      content: define(`./${name.replace(/\.tsx?$/, "")}`, compile(join(UPLOAD_DIR, name)))
    });
  }
  await page.addScriptTag({
    content: define("./SketchTraceField", compile(join(UPLOAD_DIR, "SketchTraceField.tsx")))
  });

  await page.addScriptTag({
    content: `
      (function () {
        var React = window.__require("react");
        var client = window.__require("react-dom/client");
        var panel = window.__require("./SketchTraceField");
        client.createRoot(document.getElementById("root")).render(
          React.createElement(panel.SketchTraceField, {
            targetLabel: ${JSON.stringify(TARGET_LABEL)},
            onAttach: function (file) { window.__attached.push(file); },
            onAttachSource: ${options.withSource === false ? "undefined" : "function (file) { window.__sources.push(file); }"}
          })
        );
      })();
    `
  });

  await expect(page.getByRole("button", { name: "Trace a sketch into line art" })).toBeVisible();
}

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(buffer: Buffer): number {
  let c = 0xffffffff;
  for (const byte of buffer) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type: string, data: Buffer): Buffer {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([length, body, crc]);
}

function png(width: number, height: number, grey = 200): Buffer {
  const stride = width * 3 + 1;
  const raw = Buffer.alloc(stride * height, grey);
  for (let y = 0; y < height; y += 1) raw[y * stride] = 0;
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 2;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    chunk("IDAT", deflateSync(raw)),
    chunk("IEND", Buffer.alloc(0))
  ]);
}

async function pick(page: Page, name: string, width: number, height: number): Promise<void> {
  await page.locator('input[type="file"]').setInputFiles({
    name,
    mimeType: "image/png",
    buffer: png(width, height)
  });
}

interface TraceRecord {
  readonly width: number;
  readonly height: number;
  readonly preview: boolean;
}

function traces(page: Page): Promise<TraceRecord[]> {
  return page.evaluate(() => (window as unknown as { __traces: TraceRecord[] }).__traces);
}

function comparator(page: Page) {
  return page.getByLabel("Traced drawing against the photograph");
}

test("opening the panel reaches its controls, and reopening it does not reload the engine", async ({ page }) => {
  await mount(page);

  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();

  await expect(page.getByText("Loading the tracing engine…")).toHaveCount(0, { timeout: 5_000 });
  await expect(page.getByRole("button", { name: "Choose a photograph" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Trace a sketch into line art" })).toBeVisible();

  await expect(page.getByRole("heading", { name: "Trace a sketch into line art" })).toBeFocused();

  await page.getByRole("button", { name: "Close the tracing panel" }).click();
  const trigger = page.getByRole("button", { name: "Trace a sketch into line art" });
  await expect(trigger).toBeVisible();
  await expect(trigger).toBeFocused();

  await trigger.click();
  await expect(page.getByRole("button", { name: "Choose a photograph" })).toBeVisible();
  await expect(page.getByText("Loading the tracing engine…")).toHaveCount(0);
  expect(await page.evaluate(() => (window as unknown as { __runtimeLoads: number }).__runtimeLoads)).toBe(1);
});

test("a device that cannot load the engine is told so, and is not offered a button that fails", async ({ page }) => {
  await mount(page);
  await page.evaluate(() => {
    (window as unknown as { __runtimeFails: boolean }).__runtimeFails = true;
  });
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();

  const sentence = page.getByText("The tracing engine could not be loaded. Check your connection and reload the page.");
  await expect(sentence).toBeVisible();
  expect(await sentence.evaluate((node) => node.closest('[role="alert"]') !== null)).toBe(true);
  await expect(page.getByRole("button", { name: "Trace a sketch into line art" })).toHaveCount(0);
});

test("declining the trace files the photograph, and attaching later does not file it twice", async ({ page }) => {
  await mount(page);
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();
  await pick(page, "sheet.png", 64, 48);

  await expect.poll(async () => (await traces(page)).length, { timeout: 5_000 }).toBeGreaterThan(0);
  expect((await traces(page))[0]).toEqual({ width: 64, height: 48, preview: true });

  await page.getByRole("button", { name: "Attach the photograph only" }).click();
  expect(await page.evaluate(() => (window as unknown as { __sources: File[] }).__sources.map((f) => f.name))).toEqual([
    "sheet.png"
  ]);
  expect(await page.evaluate(() => (window as unknown as { __attached: File[] }).__attached.length)).toBe(0);
  await expect(page.getByText("sheet.png was filed exactly as it is. No line art was added.")).toBeVisible();

  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();
  await page.getByRole("button", { name: `Add the line art to “${TARGET_LABEL}”` }).click();

  await expect
    .poll(async () => page.evaluate(() => (window as unknown as { __attached: File[] }).__attached.length), {
      timeout: 5_000
    })
    .toBe(1);
  expect(await page.evaluate(() => (window as unknown as { __attached: File[] }).__attached[0].name)).toBe(
    "sheet-line-art.svg"
  );
  expect(await page.evaluate(() => (window as unknown as { __sources: File[] }).__sources.length)).toBe(1);
  expect((await traces(page)).at(-1)?.preview).toBe(false);
});

test("re-picking while the first photograph is still decoding traces the second one, under its own name", async ({
  page
}) => {
  await mount(page);
  await page.evaluate(() => {
    (window as unknown as { __decodeDelays: Record<string, number> }).__decodeDelays["slow.png"] = 400;
  });
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();

  await pick(page, "slow.png", 300, 200);
  await pick(page, "quick.png", 64, 48);

  await expect.poll(async () => (await traces(page)).length, { timeout: 5_000 }).toBeGreaterThan(0);
  await page.waitForTimeout(900);

  const seen = await traces(page);
  expect(seen.every((trace) => trace.width === 64 && trace.height === 48)).toBe(true);

  await page.getByRole("button", { name: `Add the line art to “${TARGET_LABEL}”` }).click();
  await expect
    .poll(async () => page.evaluate(() => (window as unknown as { __attached: File[] }).__attached.length), {
      timeout: 5_000
    })
    .toBe(1);
  expect(await page.evaluate(() => (window as unknown as { __attached: File[] }).__attached[0].name)).toBe(
    "quick-line-art.svg"
  );
  const svg = await page.evaluate(async () =>
    await (window as unknown as { __attached: File[] }).__attached[0].text()
  );
  expect(svg).toContain("Traced on the device from quick.png");
  expect(svg).not.toContain("slow.png");
});

test("moving a control and attaching straight away still attaches the drawing", async ({ page }) => {
  await mount(page, { traceMs: 500 });
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();
  await pick(page, "sheet.png", 64, 48);

  const attach = page.getByRole("button", { name: `Add the line art to “${TARGET_LABEL}”` });
  await expect(attach).toBeEnabled({ timeout: 15_000 });

  await page.getByLabel("Stroke width").fill("2");
  await attach.click();

  await expect
    .poll(async () => page.evaluate(() => (window as unknown as { __attached: File[] }).__attached.length), {
      timeout: 15_000
    })
    .toBe(1);
  await expect(page.getByText("The trace did not finish, so there is nothing to attach yet.")).toHaveCount(0);
});

test("a translucent colour reaches the canvas with the alpha the SVG gives it", async ({ page }) => {
  await mount(page);
  const painted = await page.evaluate(() => {
    const exports = (window as unknown as { __require: (id: string) => Record<string, Function> }).__require;
    const paint = exports("./traceExport").paintGeometry as (c: unknown, i: unknown, s: number) => void;
    const buildSvg = exports("./geometryToSvg").buildSvg as (i: unknown, o: unknown) => { svg: string };

    const input = {
      geometry: {
        coords: new Float32Array([0, 0, 10, 0, 10, 10]),
        verbs: new Uint8Array([0, 0]),
        verbStarts: new Uint32Array([0, 2]),
        coordStarts: new Uint32Array([0, 6]),
        closed: new Uint8Array([1]),
        styleTable: [
          {
            stroke: null,
            strokeWidth: 1,
            fill: 0x80ff0000,
            fillRule: "NONZERO",
            cap: "ROUND",
            join: "ROUND",
            miterLimit: 4,
            opacity: 1
          }
        ],
        styleIndex: new Uint32Array([0])
      },
      width: 10,
      height: 10,
      background: null
    };

    const canvas = document.createElement("canvas");
    canvas.width = 10;
    canvas.height = 10;
    const context = canvas.getContext("2d");
    if (context === null) throw new Error("no 2d context");
    paint(context, input, 1);
    const pixel = context.getImageData(8, 2, 1, 1).data;
    return { alpha: pixel[3], svg: buildSvg(input, {}).svg };
  });

  expect(painted.svg).toContain('fill-opacity="0.502"');
  expect(Math.abs(painted.alpha - 128)).toBeLessThanOrEqual(2);
});

test("a frame reaches the tracer only when it is committed, and the panel says so until then", async ({ page }) => {
  await mount(page);
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();
  await pick(page, "sheet.png", 64, 48);

  await expect.poll(async () => (await traces(page)).length).toBeGreaterThan(0);
  expect((await traces(page))[0]).toMatchObject({ width: 64, height: 48 });

  const chooseFrame = page.getByRole("button", { name: "Choose a frame" });
  await expect(chooseFrame).toBeVisible({ timeout: 15_000 });
  await chooseFrame.click();

  const frameWidth = page.getByLabel("Width", { exact: true });
  await frameWidth.fill("32");
  await expect(
    page.getByText("Nothing has been applied yet: the trace is still using the whole photograph.")
  ).toBeVisible();
  const beforeCommit = await traces(page);
  expect(beforeCommit.every((entry) => entry.width === 64)).toBe(true);

  await page.getByRole("button", { name: "Use this frame for the trace" }).click();

  await expect
    .poll(async () => await page.evaluate(() => (window as unknown as { __edits: unknown[] }).__edits.length))
    .toBe(1);
  const edits = await page.evaluate(
    () => (window as unknown as { __edits: { crop: { width: number; height: number } }[] }).__edits
  );
  expect(edits[0].crop).toMatchObject({ x: 0, y: 0, width: 32, height: 48 });

  await expect.poll(async () => (await traces(page)).at(-1)?.width).toBe(32);
  await expect(page.getByText(/The trace is using 32x48/)).toBeVisible();

  await frameWidth.fill("20");
  await expect(page.getByText(/The frame on screen is not the one being traced/)).toBeVisible();
});

interface SavedFile {
  readonly name: string;
  readonly type: string;
  readonly size: number;
}

function saved(page: Page): Promise<SavedFile[]> {
  return page.evaluate(() =>
    (window as unknown as { __saved: SavedFile[] }).__saved.map((entry) => ({
      name: entry.name,
      type: entry.type,
      size: entry.size
    }))
  );
}

test("the two downloads save the vector trace and the rendered raster, each re-traced at full resolution", async ({
  page
}) => {
  await mount(page);
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();
  await pick(page, "sheet.png", 64, 48);

  await page.getByRole("button", { name: "Show more options" }).click();
  const downloadTrace = page.getByRole("button", { name: "Download the trace (SVG)" });
  const downloadRender = page.getByRole("button", { name: "Download the rendered image (PNG)" });

  await expect(downloadTrace).toBeEnabled({ timeout: 15_000 });
  await expect(downloadRender).toBeEnabled();

  await downloadTrace.click();
  await expect.poll(async () => (await saved(page)).length, { timeout: 15_000 }).toBe(1);
  expect((await saved(page))[0].name).toBe("sheet-line-art.svg");
  expect((await saved(page))[0].type).toBe("image/svg+xml");

  const svg = await page.evaluate(async () =>
    (window as unknown as { __saved: { blob: Blob }[] }).__saved[0].blob.text()
  );
  expect(svg).toContain("<svg");
  expect(svg).toContain("<path");
  expect(svg).toContain("Traced on the device from sheet.png");

  expect((await traces(page)).at(-1)?.preview).toBe(false);

  await downloadRender.click();
  await expect.poll(async () => (await saved(page)).length, { timeout: 15_000 }).toBe(2);
  const both = await saved(page);
  expect(both[1].name).toBe("sheet-traced.png");
  expect(both[1].type).toBe("image/png");
  expect(both[1].size).toBeGreaterThan(0);
  expect(both[1].name).not.toBe(both[0].name.replace(/\.svg$/, ".png"));
  expect((await traces(page)).at(-1)?.preview).toBe(false);

  expect(await page.evaluate(() => (window as unknown as { __attached: File[] }).__attached.length)).toBe(0);
  expect(await page.evaluate(() => (window as unknown as { __sources: File[] }).__sources.length)).toBe(0);
});

test("the comparator opens on the trace, with the photograph as the layer that gets revealed", async ({ page }) => {
  await mount(page, { traceMs: 400 });
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();

  await expect(page.getByRole("slider", { name: "Traced drawing against the photograph" })).toHaveCount(0);
  await expect(page.getByText("The trace against the photograph")).toHaveCount(0);

  await pick(page, "sheet.png", 64, 48);

  await expect(page.getByText("The trace against the photograph")).toBeVisible();
  const slider = page.getByRole("slider", { name: "Traced drawing against the photograph" });
  await expect(slider).toHaveCount(0);

  await expect(slider).toBeVisible({ timeout: 15_000 });

  await expect(slider).toHaveAttribute("aria-valuenow", "0");
  await expect(slider).toHaveAttribute("aria-valuetext", "0% Photograph, 100% Traced drawing");
  await expect(slider).toHaveAttribute("aria-orientation", "horizontal");

  const clipped = page.locator('div[style*="clip-path"] img');
  await expect(clipped).toHaveAttribute("alt", "The photograph sheet.png, as the tracing engine read it");
  await expect(page.getByAltText("The traced drawing, on white")).toBeVisible();

  const sources = await page.locator("img").evaluateAll((nodes) => nodes.map((n) => (n as HTMLImageElement).src));
  expect(sources.length).toBe(2);
  expect(sources.every((src) => src.startsWith("blob:"))).toBe(true);
  expect(sources[0]).not.toBe(sources[1]);

  await slider.focus();
  await page.keyboard.press("ArrowRight");
  await expect(slider).toHaveAttribute("aria-valuenow", "2");
  await page.keyboard.press("End");
  await expect(slider).toHaveAttribute("aria-valuenow", "100");
  await expect(slider).toHaveAttribute("aria-valuetext", "100% Photograph, 0% Traced drawing");
  await page.keyboard.press("Home");
  await expect(slider).toHaveAttribute("aria-valuenow", "0");

  const ratio = await slider.evaluate((node) => getComputedStyle(node).aspectRatio);
  const [frameWidth, frameHeight] = ratio.split("/").map((part) => Number(part.trim()));
  expect(frameWidth / (frameHeight || 1)).toBeCloseTo(64 / 48, 3);
});

test("a trace that fails leaves the comparator saying so, and nothing to download", async ({ page }) => {
  await mount(page);
  await page.evaluate(() => {
    (window as unknown as { __traceFails: boolean }).__traceFails = true;
  });
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();
  await pick(page, "sheet.png", 64, 48);

  await expect(page.getByText("That photograph could not be traced.")).toBeVisible({ timeout: 15_000 });

  await expect(
    page.getByText("The trace did not finish, so there is nothing to compare. The reason is above.")
  ).toBeVisible();
  await expect(page.getByRole("slider", { name: "Traced drawing against the photograph" })).toHaveCount(0);

  await page.getByRole("button", { name: "Show more options" }).click();
  await expect(page.getByRole("button", { name: "Download the trace (SVG)" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "Download the rendered image (PNG)" })).toBeDisabled();
  await expect(page.getByRole("button", { name: `Add the line art to “${TARGET_LABEL}”` })).toBeDisabled();
  expect(await saved(page)).toEqual([]);
});

function dropZone(page: Page) {
  return page.locator("div.border-dashed");
}

async function transferOf(page: Page, files: { name: string; type: string; bytes: number[] }[]) {
  return await page.evaluateHandle((entries) => {
    const transfer = new DataTransfer();
    for (const entry of entries) {
      transfer.items.add(new File([new Uint8Array(entry.bytes)], entry.name, { type: entry.type }));
    }
    return transfer;
  }, files);
}

test("the picker refuses what is not a photograph, in a sentence, and takes what only looks wrong", async ({
  page
}) => {
  await mount(page);
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();

  await page.locator('input[type="file"]').setInputFiles({
    name: "brief.pdf",
    mimeType: "application/pdf",
    buffer: Buffer.from("%PDF-1.7\n")
  });

  const refusal = page.getByText("brief.pdf — this is application/pdf, not a photograph.", { exact: false });
  await expect(refusal).toBeVisible();
  expect(await refusal.evaluate((node) => node.closest('[role="alert"]') !== null)).toBe(true);
  expect(await traces(page)).toEqual([]);
  await expect(page.getByRole("button", { name: "Choose a frame" })).toHaveCount(0);

  await page.locator('input[type="file"]').setInputFiles({
    name: "already-vector.svg",
    mimeType: "image/svg+xml",
    buffer: Buffer.from("<svg xmlns='http://www.w3.org/2000/svg'/>")
  });
  await expect(page.getByText("an SVG is already vector art", { exact: false })).toBeVisible();
  expect(await traces(page)).toEqual([]);

  const typeless = await transferOf(page, [
    { name: "from-the-camera-roll", type: "", bytes: [...png(64, 48)] }
  ]);
  await dropZone(page).dispatchEvent("drop", { dataTransfer: typeless });
  await expect.poll(async () => (await traces(page)).length, { timeout: 15_000 }).toBe(1);
  await expect(page.getByText("an SVG is already vector art", { exact: false })).toHaveCount(0);
  await expect(page.getByRole("alert")).toHaveCount(0);
});

test("the picker takes a drop, says so while the file is over it, and names what it left out", async ({ page }) => {
  await mount(page);
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();

  const zone = dropZone(page);
  await expect(zone).toBeVisible();

  await expect(page.getByText("…or drag a file onto this card.")).toBeVisible();
  const dragged = await transferOf(page, [{ name: "sheet.png", type: "image/png", bytes: [...png(64, 48)] }]);
  await zone.dispatchEvent("dragenter", { dataTransfer: dragged });
  await zone.dispatchEvent("dragover", { dataTransfer: dragged });
  await expect(page.getByText("Let go to use this file.")).toBeVisible();

  await page.getByRole("button", { name: "Choose a photograph" }).dispatchEvent("dragenter", {
    dataTransfer: dragged
  });
  await zone.dispatchEvent("dragleave", { dataTransfer: dragged });
  await expect(page.getByText("Let go to use this file.")).toBeVisible();

  const three = await transferOf(page, [
    { name: "first.png", type: "image/png", bytes: [...png(64, 48)] },
    { name: "second.png", type: "image/png", bytes: [...png(32, 24)] },
    { name: "brief.pdf", type: "application/pdf", bytes: [...Buffer.from("%PDF-1.7\n")] }
  ]);
  await zone.dispatchEvent("drop", { dataTransfer: three });

  await expect.poll(async () => (await traces(page)).length, { timeout: 15_000 }).toBeGreaterThan(0);
  expect((await traces(page))[0]).toMatchObject({ width: 64, height: 48 });
  await expect(page.getByText("One file was not taken: brief.pdf", { exact: false })).toBeVisible();
  await expect(
    page.getByText("This holds one file, so the first was used and the other one was left out.")
  ).toBeVisible();
  await expect(page.getByText("…or drag a file onto this card.")).toBeVisible();
});

test("the picker's button is the tab stop, and the input is cleared so the same file can be re-chosen", async ({
  page
}) => {
  await mount(page);
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();

  const input = page.locator('input[type="file"]');
  await expect(input).toHaveAttribute("tabindex", "-1");
  await expect(input).toHaveAttribute("aria-hidden", "true");
  await expect(input).toHaveClass(/sr-only/);

  const choose = page.getByRole("button", { name: "Choose a photograph" });
  await choose.focus();
  await expect(choose).toBeFocused();
  expect(await choose.evaluate((node) => node.tagName)).toBe("BUTTON");
  const describedBy = await choose.getAttribute("aria-describedby");
  expect(describedBy).not.toBeNull();
  await expect(page.locator(`#${describedBy}`)).toContainText("JPEG, PNG, WebP");

  await pick(page, "sheet.png", 64, 48);
  await expect.poll(async () => (await traces(page)).length, { timeout: 15_000 }).toBeGreaterThan(0);

  await expect(input).toHaveJSProperty("value", "");
});

async function badgeClip(page: Page, text: string): Promise<string> {
  const clip = await comparator(page)
    .getByText(text, { exact: true })
    .evaluate((node) => getComputedStyle(node.parentElement as HTMLElement).clipPath);
  return clip.replace(/\s|px/g, "");
}

test("the comparator captions the layer it is showing, and its grip is inside the frame on open", async ({ page }) => {
  await mount(page);
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();
  await pick(page, "sheet.png", 64, 48);

  const slider = page.getByRole("slider", { name: "Traced drawing against the photograph" });
  await expect(slider).toBeVisible({ timeout: 15_000 });
  await expect(slider).toHaveAttribute("aria-valuenow", "0");

  expect(await badgeClip(page, "Photograph")).toBe("inset(0100%00)");
  expect(await badgeClip(page, "Traced drawing")).toBe("inset(0000%)");

  const gripAt = (percent: number) => [
    `clamp(20px, ${percent}%, calc(100% - 20px))`,
    `clamp(20px, ${percent}%, 100% - 20px)`
  ];
  const gripLeft = () => slider.locator("div.h-10.w-10").evaluate((node) => (node as HTMLElement).style.left);
  const seamLeft = () => slider.locator("div.bg-white").evaluate((node) => (node as HTMLElement).style.left);
  expect(gripAt(0)).toContain(await gripLeft());
  expect(await seamLeft()).toBe("0%");

  await slider.focus();
  await page.keyboard.press("End");
  await expect(slider).toHaveAttribute("aria-valuenow", "100");
  expect(await badgeClip(page, "Photograph")).toBe("inset(00%00)");
  expect(await badgeClip(page, "Traced drawing")).toBe("inset(000100%)");
  expect(gripAt(100)).toContain(await gripLeft());
  expect(await seamLeft()).toBe("100%");
});

test("a half-typed number survives being typed, and a clamp that moves one says so", async ({ page }) => {
  await mount(page);
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();
  await pick(page, "sheet.png", 300, 200);
  const chooseFrame = page.getByRole("button", { name: "Choose a frame" });
  await expect(chooseFrame).toBeVisible({ timeout: 15_000 });
  await chooseFrame.click();

  const width = page.getByLabel("Width", { exact: true });
  const left = page.getByLabel("Left", { exact: true });

  await width.click();
  await width.press("Control+a");
  await width.pressSequentially("150", { delay: 20 });
  await expect(width).toHaveValue("150");
  await expect(page.getByText("150x200 of 300x200", { exact: false })).toBeVisible();

  await page.getByRole("button", { name: "Use the whole photograph" }).click();
  await left.click();
  await left.press("Control+a");
  await left.pressSequentially("40", { delay: 20 });
  await expect(left).toHaveValue("40");
  await left.press("Enter");
  await expect(left).toHaveValue("0");
  await expect(page.getByText("Left cannot be 40", { exact: false })).toBeVisible();
  await expect(page.getByText("Reduce Width first", { exact: false })).toBeVisible();

  await width.click();
  await width.press("Control+a");
  await width.pressSequentially("200", { delay: 20 });
  await left.click();
  await left.press("Control+a");
  await left.pressSequentially("40", { delay: 20 });
  await left.press("Enter");
  await expect(left).toHaveValue("40");
  await expect(page.getByText("Left cannot be 40", { exact: false })).toHaveCount(0);
  await expect(page.getByText("200x200 of 300x200", { exact: false })).toBeVisible();

  await page.getByRole("button", { name: "Use this frame for the trace" }).click();
  await expect
    .poll(async () => await page.evaluate(() => (window as unknown as { __edits: unknown[] }).__edits.length), {
      timeout: 15_000
    })
    .toBe(1);
  const edits = await page.evaluate(
    () => (window as unknown as { __edits: { crop: Record<string, number> }[] }).__edits
  );
  expect(edits[0].crop).toMatchObject({ x: 40, y: 0, width: 200, height: 200 });
});

test("the frame's provenance sentence reaches the exported SVG from the editor that made the pixels", async ({
  page
}) => {
  await mount(page);
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();
  await pick(page, "sheet.png", 64, 48);
  const chooseFrame = page.getByRole("button", { name: "Choose a frame" });
  await expect(chooseFrame).toBeVisible({ timeout: 15_000 });
  await chooseFrame.click();

  await page.getByLabel("Width", { exact: true }).fill("32");
  await page.getByRole("button", { name: "Use this frame for the trace" }).click();
  await expect(page.getByText(/The trace is using 32x48/)).toBeVisible({ timeout: 15_000 });

  await page.getByRole("button", { name: `Add the line art to “${TARGET_LABEL}”` }).click();
  await expect
    .poll(async () => page.evaluate(() => (window as unknown as { __attached: File[] }).__attached.length), {
      timeout: 15_000
    })
    .toBe(1);

  const svg = await page.evaluate(async () => await (window as unknown as { __attached: File[] }).__attached[0].text());
  expect(svg).toContain("[from the editor]");
  expect(svg).toContain("Cropped on the device to 32x48 at (0, 0) of 64x48.");
  expect(svg).toContain("Traced on the device from sheet.png");
});

test("the chips show each picture whole, and Wipe comes back to where the seam was left", async ({ page }) => {
  await mount(page);
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();
  await pick(page, "sheet.png", 64, 48);

  const slider = page.getByRole("slider", { name: "Traced drawing against the photograph" });
  await expect(slider).toBeVisible({ timeout: 15_000 });

  const chip = (name: string) => page.getByRole("button", { name, exact: true });
  for (const name of ["Drawing", "Wipe", "Photograph", "Difference"]) {
    await expect(chip(name)).toBeVisible();
  }
  await expect(chip("Wipe")).toHaveAttribute("aria-pressed", "true");
  await expect(slider).toHaveAttribute("aria-valuenow", "0");

  await chip("Photograph").click();
  await expect(slider).toHaveAttribute("aria-valuenow", "100");
  await expect(slider).toHaveAttribute("aria-valuetext", "100% Photograph, 0% Traced drawing");
  await chip("Drawing").click();
  await expect(slider).toHaveAttribute("aria-valuenow", "0");

  await chip("Wipe").click();
  await slider.focus();
  await page.keyboard.press("Home");
  for (let i = 0; i < 20; i += 1) await page.keyboard.press("ArrowRight");
  await expect(slider).toHaveAttribute("aria-valuenow", "40");
  await chip("Drawing").click();
  await expect(slider).toHaveAttribute("aria-valuenow", "0");
  await chip("Wipe").click();
  await expect(slider).toHaveAttribute("aria-valuenow", "40");
});

test("the difference view replaces both layers with one plate, and says what it means", async ({ page }) => {
  await mount(page);
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();
  await pick(page, "sheet.png", 64, 48);

  const slider = page.getByRole("slider", { name: "Traced drawing against the photograph" });
  await expect(slider).toBeVisible({ timeout: 15_000 });
  expect(await page.locator("img").count()).toBe(2);

  await page.getByRole("button", { name: "Difference", exact: true }).click();

  const solo = page.getByRole("group", { name: "Traced drawing against the photograph" });
  await expect(solo).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole("slider", { name: "Traced drawing against the photograph" })).toHaveCount(0);

  expect(await page.locator("img").count()).toBe(1);
  await expect(
    page.getByAltText(
      "The traced drawing and the photograph subtracted from each other. Dark where they agree, " +
        "bright where they differ."
    )
  ).toBeVisible();
  const source = await page.locator("img").evaluate((node) => (node as HTMLImageElement).src);
  expect(source.startsWith("blob:")).toBe(true);

  await expect(solo.getByText("Difference", { exact: true })).toBeVisible();

  await expect(page.getByText("black where they agree, bright where they do not")).toBeVisible();

  await page.getByRole("button", { name: "Wipe", exact: true }).click();
  const wipe = page.getByRole("slider", { name: "Traced drawing against the photograph" });
  await expect(wipe).toBeVisible();
  expect(await page.locator("img").count()).toBe(2);
  await expect(wipe.getByText("Difference", { exact: true })).toHaveCount(0);
  await expect(wipe.getByText("Photograph", { exact: true })).toBeVisible();
  await expect(wipe.getByText("Traced drawing", { exact: true })).toBeVisible();
});

test("press and hold shows the photograph, and letting go restores the seam untouched", async ({ page }) => {
  await mount(page);
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();
  await pick(page, "sheet.png", 64, 48);

  const slider = page.getByRole("slider", { name: "Traced drawing against the photograph" });
  await expect(slider).toBeVisible({ timeout: 15_000 });

  await slider.focus();
  for (let i = 0; i < 10; i += 1) await page.keyboard.press("ArrowRight");
  await expect(slider).toHaveAttribute("aria-valuenow", "20");

  await slider.hover();
  await page.mouse.down();
  await expect(slider).toHaveAttribute("aria-valuenow", "100", { timeout: 3_000 });

  await page.mouse.up();
  await expect(slider).toHaveAttribute("aria-valuenow", "20");
});

test("the magnifier answers to the keyboard, reports itself, and can be put back to fit", async ({ page }) => {
  await mount(page);
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();
  await pick(page, "sheet.png", 64, 48);

  const slider = page.getByRole("slider", { name: "Traced drawing against the photograph" });
  await expect(slider).toBeVisible({ timeout: 15_000 });

  await expect(page.getByRole("button", { name: /reset to fit/ })).toHaveCount(0);

  await slider.focus();
  await page.keyboard.press("+");
  const readout = page.getByRole("button", { name: /reset to fit/ });
  await expect(readout).toBeVisible();
  await expect(readout).toHaveText("1.3× — reset to fit");

  const imageTransforms = await page
    .locator("img")
    .evaluateAll((nodes) => nodes.map((node) => getComputedStyle(node).transform));
  expect(imageTransforms.every((value) => value === "none")).toBe(true);
  const wrappers = await page
    .locator('div[style*="scale"]')
    .evaluateAll((nodes) => nodes.map((node) => (node as HTMLElement).style.transform));
  expect(wrappers.length).toBe(1);
  expect(wrappers[0]).toContain("scale(1.25)");

  await expect(slider).toHaveAttribute("aria-valuenow", "0");

  await page.keyboard.press("+");
  await expect(readout).toHaveText("1.6× — reset to fit");
  await page.keyboard.press("-");
  await expect(readout).toHaveText("1.3× — reset to fit");

  await page.keyboard.press("0");
  await expect(page.getByRole("button", { name: /reset to fit/ })).toHaveCount(0);
});

test("a running trace can be stopped, and says so until it really has", async ({ page }) => {
  await mount(page, { traceMs: 1_500 });
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();
  await pick(page, "sheet.png", 64, 48);

  const stop = page.getByRole("button", { name: "Stop", exact: true });
  await expect(stop).toBeVisible({ timeout: 15_000 });
  await stop.click();

  await expect(page.getByRole("button", { name: "Stopping…" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Stopping…" })).toBeDisabled();

  await expect(page.getByRole("button", { name: /Stop/ })).toHaveCount(0, { timeout: 15_000 });

  await expect(page.getByText("That photograph could not be traced.")).toHaveCount(0);
});

test("a full-resolution run draws a bar, and says the bar counts stages until it has measured one", async ({
  page
}) => {
  await mount(page, { traceMs: 2_000 });
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();
  await pick(page, "sheet.png", 64, 48);

  await expect(page.getByRole("button", { name: "Stop", exact: true })).toBeVisible({ timeout: 15_000 });
  expect(await page.getByText("The bar counts stages, not time").count()).toBe(0);
  await expect(page.getByRole("button", { name: /Stop/ })).toHaveCount(0, { timeout: 15_000 });

  await page.getByRole("button", { name: `Add the line art to “${TARGET_LABEL}”` }).click();

  await expect(page.getByText("Edges. Stage 7 of 12.")).toBeVisible({ timeout: 15_000 });

  await expect(page.getByText("The bar counts stages, not time")).toBeVisible();
});
