import { readFileSync } from "node:fs";
import { join } from "node:path";
import { deflateSync } from "node:zlib";

import { expect, test, type Page } from "@playwright/test";
import * as ts from "typescript";

/**
 * `SketchTraceField`, RENDERED — in a real browser, by real React, with its real effects running.
 *
 * WHY THIS FILE EXISTS. `sketch-trace-options-unit.spec.ts` covers the table, the merge, the SVG
 * writer and the sanitiser round trip, and every one of those cases passed while the panel could not
 * start at all: the engine-load effect listed a piece of state it wrote in its own dependency array,
 * so React tore it down before the `await` continuation resolved, the `cancelled` bail-out threw the
 * loaded runtime away, the re-run hit its own guard and returned, and the spinner never came down.
 * Twenty-two green tests coexisted with a feature nobody could use, because the whole failure lived
 * in the render-and-effect layer and nothing in this directory rendered anything. So this file
 * renders it.
 *
 * WHY IT IS NOT NAMED `-unit`, WHICH IS THE OBVIOUS THING TO WANT. `-unit` is this repository's
 * promise that a spec needs no services, and the CI job that runs those specs deliberately does not
 * install a browser — `.github/workflows/checks.yml` says so in its own comment ("NO `npx playwright
 * install` — MEASURED: … not one of them asks for the `page` fixture"). This spec asks for `page`, so
 * carrying the suffix would turn that job red with "Executable doesn't exist". It needs a BROWSER and
 * nothing else: no dev server, no API, no database, no credentials. One command runs it:
 *
 *     cd frontend && npx playwright test sketch-trace-panel --reporter=line
 *
 * ────────────────────────────────────────────────────────────────────────────
 * HOW A COMPONENT GETS RENDERED HERE WITH NO COMPONENT-TEST RUNNER
 * ────────────────────────────────────────────────────────────────────────────
 *
 * There is none installed — no `@playwright/experimental-ct-react`, no jsdom, no bundler beyond
 * Next's own (`package.json`'s devDependencies are playwright, eslint, typescript, tailwind, postcss,
 * autoprefixer), and `package.json` belongs to another unit. What IS installed is TypeScript and
 * React, so this file uses them directly:
 *
 *  1. `ts.transpileModule` compiles each real source file to CommonJS, one file at a time. No type
 *     checking, which is `npx tsc --noEmit`'s job and not this file's.
 *  2. A twelve-line CommonJS registry in the page resolves `require` by the specifier string exactly
 *     as written, so `"./traceParamTable"` is a key rather than a path.
 *  3. React, `react/jsx-runtime`, `react-dom`, `react-dom/client` and `scheduler` are registered from
 *     their PRODUCTION CJS builds in `node_modules` — verified to require nothing but each other and
 *     to reference no `process.env`.
 *
 * WHAT IS REAL AND WHAT IS STOOD IN FOR, because a harness that quietly replaces the thing under test
 * proves nothing:
 *
 *  · REAL: `SketchTraceField.tsx` itself, `traceParamTable.ts`, `decodeToPixels.ts` (so the browser
 *    really decodes the photograph), `traceExport.ts`, `geometryToSvg.ts`, and the engine's own
 *    `lib/trace/engine/params.ts` — so every slider is drawn from the real defaults and every patch
 *    goes through the real `sanitizeTraceParams`.
 *  · STOOD IN FOR: `traceRuntime.ts` and `lucide-react`. The runtime stub is the seam this spec turns:
 *    it is what makes the engine load take a controllable 60 ms, which is the point. The real runtime
 *    dynamic-imports 43 engine files and starts a module worker — neither of which this registry can
 *    resolve, and neither of which is what these three cases are about. `e2e/trace-engine-unit.spec.ts`
 *    is where the engine itself is exercised.
 *
 * A DELAY OF 60 MS IS NOT AN ARBITRARY NUMBER. It is longer than one React commit and far shorter
 * than a real chunk fetch on a courtyard hotspot, which is the case that has to work. The bug this
 * file was written against happened to pass with a 0 ms stand-in — the load won the race against its
 * own teardown — which is exactly how it survived being tried by hand.
 */

const UPLOAD_DIR = join(__dirname, "..", "components", "sketches", "upload");
const NODE_MODULES = join(__dirname, "..", "node_modules");
const ENGINE_PARAMS = join(__dirname, "..", "lib", "trace", "engine", "params.ts");

/** The runtime-load delay every case mounts with. See the header. */
const RUNTIME_LOAD_MS = 60;

/** The registry label the panel quotes on its attach button — the real one, from stage 11's sketch. */
const TARGET_LABEL = "Line art / vector file";

/* ────────────────────────────────────────────────────────────────────────────
 * Compiling the real sources
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One file, to CommonJS, with the JSX transform React 19 actually uses.
 *
 * No `paths` resolution and no module graph walking: every specifier these files use is either a
 * sibling (`"./traceParamTable"`) or a bare package name, and the registry keys on the string as
 * written. `import type` statements are erased by the transpiler, which is why `SketchTraceField`'s
 * `@/lib/trace/traceClient` type imports need no entry at all.
 */
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

/** The CommonJS registry, and `process` for anything that sniffs for it. Injected before everything. */
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

/** Wrap compiled CommonJS as a registry entry. */
function define(id: string, code: string): string {
  return `window.__define(${JSON.stringify(id)}, function (module, exports, require) {\n${code}\n});`;
}

/* ────────────────────────────────────────────────────────────────────────────
 * The stubs
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Every lucide icon, rendering nothing.
 *
 * The panel uses eight of them and they carry `aria-hidden` on every use, so they contribute nothing
 * to the accessible tree these cases assert on. A `<svg>` per icon would only add noise.
 */
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

/**
 * `traceRuntime.ts`, stood in for — and the knobs this whole file turns.
 *
 * `window.__runtimeMs` is how long the engine takes to arrive, `window.__traces` records every trace
 * the panel asked for (its size and whether it was a preview), and `window.__runtimeLoads` counts the
 * loads — one per component, ever, is the property the blocking bug broke in the other direction.
 *
 * The result geometry is one two-segment open stroke, which is enough for the real `paintGeometry` to
 * paint and the real `buildSvg` to write. Document coordinates are the decoded photograph's, and a
 * PREVIEW reports half that as its working size, because that difference is what the panel states on
 * screen and what makes attaching re-trace at full resolution.
 */
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
    if (request.onProgress) request.onProgress({ label: "Edges" });
    var self = this;
    return delay(window.__traceMs).then(function () {
      if (request.signal && request.signal.aborted) throw Cancelled();
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

/**
 * Mount the panel, and hand back the page with the knobs set.
 *
 * `onAttachSource` is passed, because that is the UPLOAD tab's own wiring (`UploadTabHost.tsx` is the
 * only writer of the sketch's IMAGE field) and it is the shape in which "declining the trace still
 * files the photograph" is a promise the panel makes.
 */
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
      w.__traces = [];
      w.__attached = [];
      w.__sources = [];
      w.__decodeDelays = {};
    },
    [options.runtimeMs ?? RUNTIME_LOAD_MS, options.traceMs ?? 0]
  );

  // A decode can be made slow, per file name, so the "two picks in a row" case is deterministic
  // instead of a race against a real 4096px resize. The wrapper delays the FIRST call only — the one
  // that takes the File — because that is the await `chooseFile` is interrupted at.
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
  await page.addScriptTag({ content: define("@/lib/trace/engine/params", compile(ENGINE_PARAMS)) });
  await page.addScriptTag({ content: RUNTIME_STUB });
  for (const name of ["geometryToSvg.ts", "traceParamTable.ts", "decodeToPixels.ts", "traceExport.ts"]) {
    await page.addScriptTag({
      content: define(`./${name.replace(/\.ts$/, "")}`, compile(join(UPLOAD_DIR, name)))
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

  // The trigger is the whole of the closed panel, so its arrival is the proof React mounted at all —
  // and a harness that silently rendered nothing would otherwise fail later, as a mystery.
  await expect(page.getByRole("button", { name: "Trace a sketch into line art" })).toBeVisible();
}

/* ────────────────────────────────────────────────────────────────────────────
 * A photograph, made here
 * ──────────────────────────────────────────────────────────────────────────── */

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

/**
 * A flat 8-bit RGB PNG of exactly this size.
 *
 * Built here rather than checked in for one reason: these cases assert on the SIZE the panel decoded
 * and traced, and a fixture whose dimensions live in another file is an assertion whose subject can
 * be changed by somebody who never opened this one. Nothing about the picture matters — the tracer is
 * stubbed — only its dimensions and its name.
 */
function png(width: number, height: number, grey = 200): Buffer {
  const stride = width * 3 + 1;
  const raw = Buffer.alloc(stride * height, grey);
  for (let y = 0; y < height; y += 1) raw[y * stride] = 0; // filter type 0, per scanline
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // colour type 2 = truecolour RGB
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    chunk("IDAT", deflateSync(raw)),
    chunk("IEND", Buffer.alloc(0))
  ]);
}

async function pick(page: Page, name: string, width: number, height: number): Promise<void> {
  await page.getByLabel("Photograph to trace").setInputFiles({
    name,
    mimeType: "image/png",
    buffer: png(width, height)
  });
}

/** Every trace the panel asked the stub for: the size it handed over, and whether it was a preview. */
interface TraceRecord {
  readonly width: number;
  readonly height: number;
  readonly preview: boolean;
}

function traces(page: Page): Promise<TraceRecord[]> {
  return page.evaluate(() => (window as unknown as { __traces: TraceRecord[] }).__traces);
}

/* ────────────────────────────────────────────────────────────────────────────
 * 1. It starts — the blocking bug, and the two halves of focus
 * ──────────────────────────────────────────────────────────────────────────── */

test("opening the panel reaches its controls, and reopening it does not reload the engine", async ({ page }) => {
  await mount(page);

  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();

  // THE ASSERTION THE WHOLE FILE IS FOR. With the effect cancelling its own load this text never went
  // away, in any browser, on any device: `startedRef` is what stops the state write it makes from
  // invalidating it, and `goneRef` is what keeps the bail-out to a real unmount.
  await expect(page.getByText("Loading the tracing engine…")).toHaveCount(0, { timeout: 5_000 });
  await expect(page.getByLabel("Photograph to trace")).toBeVisible();
  await expect(page.getByRole("heading", { name: "Trace a sketch into line art" })).toBeVisible();

  // Focus moved INTO the thing that appeared. Opening unmounts the trigger, so without this the
  // focused element simply disappears and a keyboard user is returned to the top of the document.
  await expect(page.getByRole("heading", { name: "Trace a sketch into line art" })).toBeFocused();

  await page.getByRole("button", { name: "Close the tracing panel" }).click();
  const trigger = page.getByRole("button", { name: "Trace a sketch into line art" });
  await expect(trigger).toBeVisible();
  // …and handed back to what opened it, in the other direction, for the same reason.
  await expect(trigger).toBeFocused();

  // Reopening is instant and asks for nothing: the phase is still "ready" and the load is not redone.
  await trigger.click();
  await expect(page.getByLabel("Photograph to trace")).toBeVisible();
  await expect(page.getByText("Loading the tracing engine…")).toHaveCount(0);
  expect(await page.evaluate(() => (window as unknown as { __runtimeLoads: number }).__runtimeLoads)).toBe(1);
});

test("a device that cannot load the engine is told so, and is not offered a button that fails", async ({ page }) => {
  await mount(page);
  await page.evaluate(() => {
    (window as unknown as { __runtimeFails: boolean }).__runtimeFails = true;
  });
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();

  // The sentence, in a live region, and the trigger gone — "this device cannot do it" is not a state
  // to leave a button in.
  const sentence = page.getByText("The tracing engine could not be loaded. Check your connection and reload the page.");
  await expect(sentence).toBeVisible();
  expect(await sentence.evaluate((node) => node.closest('[role="alert"]') !== null)).toBe(true);
  await expect(page.getByRole("button", { name: "Trace a sketch into line art" })).toHaveCount(0);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The photograph is filed, and filed once
 * ──────────────────────────────────────────────────────────────────────────── */

test("declining the trace files the photograph, and attaching later does not file it twice", async ({ page }) => {
  await mount(page);
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();
  await pick(page, "sheet.png", 64, 48);

  // The preview traced what was decoded, at the size it was decoded at.
  await expect.poll(async () => (await traces(page)).length, { timeout: 5_000 }).toBeGreaterThan(0);
  expect((await traces(page))[0]).toEqual({ width: 64, height: 48, preview: true });

  // On this host the panel is the only picker for the photograph, so declining the trace has to be
  // able to file it. It used to upload nothing while the sentence underneath claimed otherwise.
  await page.getByRole("button", { name: "Attach the photograph only" }).click();
  expect(await page.evaluate(() => (window as unknown as { __sources: File[] }).__sources.map((f) => f.name))).toEqual([
    "sheet.png"
  ]);
  expect(await page.evaluate(() => (window as unknown as { __attached: File[] }).__attached.length)).toBe(0);
  await expect(page.getByText("sheet.png was filed exactly as it is. No line art was added.")).toBeVisible();

  // Reopen — the panel deliberately keeps the photograph and the pixels — and take the line art too.
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
  // THE SAME PHOTOGRAPH IS NOT HANDED OVER A SECOND TIME. Whether that would duplicate a media id or
  // replace one is the host's business; not offering it twice is this panel's.
  expect(await page.evaluate(() => (window as unknown as { __sources: File[] }).__sources.length)).toBe(1);
  // The attach re-traced at full resolution rather than filing the preview that was on screen.
  expect((await traces(page)).at(-1)?.preview).toBe(false);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. Two picks in a row
 * ──────────────────────────────────────────────────────────────────────────── */

test("re-picking while the first photograph is still decoding traces the second one, under its own name", async ({
  page
}) => {
  await mount(page);
  await page.evaluate(() => {
    // The first pick's decode finishes LAST, which is the ordinary case on a handset: a 4096px resize
    // and getImageData is hundreds of milliseconds, and re-picking after a mis-tap is a normal flow.
    (window as unknown as { __decodeDelays: Record<string, number> }).__decodeDelays["slow.png"] = 400;
  });
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();

  await pick(page, "slow.png", 300, 200);
  await pick(page, "quick.png", 64, 48);

  await expect.poll(async () => (await traces(page)).length, { timeout: 5_000 }).toBeGreaterThan(0);
  // Long enough that the abandoned decode has certainly resolved and its debounce could have fired.
  await page.waitForTimeout(900);

  // NOT ONE TRACE OF THE ABANDONED PICK. Without the request token the slow decode landed last, so
  // `pixels` held the 300x200 photograph while `file` held quick.png — and the panel traced one
  // photograph, named the file after the other, and wrote the wrong name into the SVG's provenance.
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
  // The provenance sentence inside the file names the photograph it was really traced from.
  const svg = await page.evaluate(async () =>
    await (window as unknown as { __attached: File[] }).__attached[0].text()
  );
  expect(svg).toContain("Traced on the device from quick.png");
  expect(svg).not.toContain("slow.png");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. An attach inside the debounce window
 * ──────────────────────────────────────────────────────────────────────────── */

test("moving a control and attaching straight away still attaches the drawing", async ({ page }) => {
  // A TRACE SLOWER THAN THE DEBOUNCE IS WHAT MAKES THIS DETERMINISTIC, and it took a mutation run to
  // find that out: at 120 ms the attach usually finished before the armed preview fired, so the case
  // passed with the fix removed and proved nothing. At 500 ms — longer than RETRACE_DEBOUNCE_MS — the
  // preview certainly fires while the attach's full-resolution trace is still in flight, which is
  // exactly the collision the bug was: the preview aborted the attach's trace, the abort was (rightly)
  // read as the ordinary consequence of moving a slider, and the panel said "the trace did not finish"
  // with a finished drawing on screen. Verified by re-introducing the bug and watching this fail.
  await mount(page, { traceMs: 500 });
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();
  await pick(page, "sheet.png", 64, 48);

  // The first preview has to LAND before a control can be moved: until it does there is no result and
  // the attach button is disabled, which would make this case about something else entirely.
  const attach = page.getByRole("button", { name: `Add the line art to “${TARGET_LABEL}”` });
  await expect(attach).toBeEnabled({ timeout: 15_000 });

  // Move a control, then attach INSIDE the 220 ms debounce window rather than after it.
  await page.getByLabel("Stroke width").fill("2");
  await attach.click();

  await expect
    .poll(async () => page.evaluate(() => (window as unknown as { __attached: File[] }).__attached.length), {
      timeout: 15_000
    })
    .toBe(1);
  await expect(page.getByText("The trace did not finish, so there is nothing to attach yet.")).toHaveCount(0);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 5. The painter and the writer, on one translucent colour
 * ──────────────────────────────────────────────────────────────────────────── */

test("a translucent colour reaches the canvas with the alpha the SVG gives it", async ({ page }) => {
  // WHY THIS NEEDS A BROWSER AND SO LIVES HERE. `paintGeometry` exists so that the live preview, the
  // attached PNG and the attached SVG are one drawing rather than three; the alpha byte of a colour is
  // where they had silently parted company, because the canvas side took `globalAlpha` from
  // `style.opacity` alone while the writer emits `fill-opacity` from the colour. A pure test can read
  // the SVG string but cannot paint, so the divergence was unassertable until something rendered.
  await mount(page);
  const painted = await page.evaluate(() => {
    const exports = (window as unknown as { __require: (id: string) => Record<string, Function> }).__require;
    const paint = exports("./traceExport").paintGeometry as (c: unknown, i: unknown, s: number) => void;
    const buildSvg = exports("./geometryToSvg").buildSvg as (i: unknown, o: unknown) => { svg: string };

    // One closed triangle over (0,0)-(10,0)-(10,10), filled 50% red and not stroked.
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
    // (8,2) is inside the triangle — x greater than y — and away from every edge, so no antialiasing.
    const pixel = context.getImageData(8, 2, 1, 1).data;
    return { alpha: pixel[3], svg: buildSvg(input, {}).svg };
  });

  // 0x80 of 0xff is 0.502, and that is the number in the file…
  expect(painted.svg).toContain('fill-opacity="0.502"');
  // …so it has to be the number on the canvas. Painted opaque (255) is the bug this case names.
  expect(Math.abs(painted.alpha - 128)).toBeLessThanOrEqual(2);
});
