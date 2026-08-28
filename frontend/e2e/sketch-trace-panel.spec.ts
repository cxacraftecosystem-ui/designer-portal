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
 *    really decodes the photograph), `traceExport.ts`, `geometryToSvg.ts`, `comparisonPlates.ts` (so
 *    the before/after plates are really painted and really encoded), `components/ui/reveal1.tsx` (the
 *    comparator itself, unmodified), `lib/utils.ts`, and the engine's own `lib/trace/engine/params.ts`
 *    — so every slider is drawn from the real defaults and every patch goes through the real
 *    `sanitizeTraceParams`.
 *  · STOOD IN FOR: `traceRuntime.ts`, `lucide-react`, `@/components/ui/Dropdown` and
 *    `@/lib/designWorkshops`. The runtime stub is the seam this spec turns: it is what makes the engine
 *    load take a controllable 60 ms, which is the point. The real runtime dynamic-imports 43 engine
 *    files and starts a module worker — neither of which this registry can resolve, and neither of
 *    which is what these cases are about. `e2e/trace-engine-unit.spec.ts` is where the engine itself is
 *    exercised.
 *
 * WHY THE LAST TWO STAND-INS EXIST, SINCE EACH ONE IS A REAL FILE THAT COULD HAVE BEEN COMPILED:
 *
 *  · `@/components/ui/Dropdown` is three adapters over `SearchableSelect`, which pulls
 *    `AnchoredPopover` and a small tree behind it. None of that is what any case here asserts, and
 *    ITS ABSENCE IS WHAT BROKE THIS FILE ONCE ALREADY: the panel's style and subject pickers moved from
 *    native `<select>`s to the themed dropdown, the registry had no module of that name, and the very
 *    first `__require("./SketchTraceField")` threw — so all six cases failed at `mount`, with
 *    "element not found" for the trigger button and nothing to say why. The stub is a native `<select>`
 *    carrying the same `aria-label`, so the pickers still work and the failure cannot recur silently.
 *  · `@/lib/designWorkshops` exports `saveBlobToDisk`, which is the whole of the panel's download path;
 *    the module also imports `@/lib/api` and four more, none of which this registry can resolve. The
 *    stub RECORDS what the panel asked to save, which is a better assertion than a synthetic anchor
 *    click could give: the two download cases below check the file's name, type and contents rather
 *    than that a click happened.
 *
 * A DELAY OF 60 MS IS NOT AN ARBITRARY NUMBER. It is longer than one React commit and far shorter
 * than a real chunk fetch on a courtyard hotspot, which is the case that has to work. The bug this
 * file was written against happened to pass with a 0 ms stand-in — the load won the race against its
 * own teardown — which is exactly how it survived being tried by hand.
 */

const UPLOAD_DIR = join(__dirname, "..", "components", "sketches", "upload");
const NODE_MODULES = join(__dirname, "..", "node_modules");
const ENGINE_PARAMS = join(__dirname, "..", "lib", "trace", "engine", "params.ts");
const REVEAL = join(__dirname, "..", "components", "ui", "reveal1.tsx");
/** The comparator's zoom and pan arithmetic, which `reveal1.tsx` requires by that specifier. */
const REVEAL_TRANSFORM = join(__dirname, "..", "components", "ui", "reveal1Transform.ts");
const UTILS = join(__dirname, "..", "lib", "utils.ts");

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
 * `@/components/ui/Dropdown`, as a native `<select>`.
 *
 * The panel's Style and Subject pickers are the only callers. They pass `value`, `onChange`,
 * `disabled`, `ariaLabel`, `describedBy` and `options`, and every case here reaches them (if at all)
 * by that accessible name — so a `<select>` carrying the same name and the same options is behaviourally
 * the part under test. See the header for why the real component is not compiled.
 */
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

/**
 * `@/lib/designWorkshops`, reduced to the one function the panel imports.
 *
 * `window.__saved` is every file the panel handed to the browser to save, in order. The real
 * `saveBlobToDisk` makes an anchor, clicks it and revokes the URL on the next task; asserting on that
 * would be asserting on Chromium's download machinery, while what these cases are about is WHICH file
 * the panel produced — its name, its type and what is inside it.
 */
const DOWNLOAD_STUB = `
window.__define("@/lib/designWorkshops", function (module, exports, require) {
  module.exports.saveBlobToDisk = function (blob, fileName) {
    window.__saved.push({ name: fileName, type: blob.type, size: blob.size, blob: blob });
  };
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
  await page.addScriptTag({ content: DROPDOWN_STUB });
  await page.addScriptTag({ content: DOWNLOAD_STUB });
  await page.addScriptTag({ content: define("@/lib/trace/engine/params", compile(ENGINE_PARAMS)) });
  // The comparator and its one dependency, both REAL — `cn` is three lines and `reveal1.tsx` is the
  // component whose `initialPosition` semantics the panel has to get the right way round.
  await page.addScriptTag({ content: define("@/lib/utils", compile(UTILS)) });
  // Registered under BOTH specifiers it is required by: `reveal1.tsx` reaches it as a sibling and the
  // panel reaches it through the alias, and the registry keys on the string exactly as written.
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
    // `FramePanel` requires `./frameGeometry` since 2026-08-28 — the crop arithmetic was lifted out
    // of its private closures so a spec could reach it. A module the panel requires and this
    // registry does not hold is a `require` that throws AT MOUNT, so every case in this file fails
    // with the harness's own message rather than with anything about the panel. It must be defined
    // BEFORE `FramePanel.tsx`.
    "frameGeometry.ts",
    // The two new pickers. `.tsx`, so the specifier has to be stripped of the whole extension rather
    // than of `.ts` — `"DropCard.tsx".replace(/\.ts$/, "")` is `"DropCard.tsx"`, which is a registry
    // key nothing requires and a `require` that throws at mount with the harness's own message.
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

/**
 * Choose a photograph.
 *
 * NOT `getByLabel("Photograph to trace")` ANY MORE, and the reason is the point of the change that
 * broke it: the picker is now `DropCard`, whose `<input type="file">` is `sr-only`, `tabIndex={-1}` and
 * `aria-hidden="true"` — deliberately, because the BUTTON is the tab stop and the accessible control,
 * and a second unlabelled tab stop is the keyboard trap that card's header cites. An `aria-hidden`
 * input has no accessible name, so `getByLabel` cannot find it and never will.
 *
 * No `.first()` either: Playwright's strict mode makes this throw if a second file input ever appears
 * in the panel, which is exactly the warning `a11y-barriers.spec.ts` wrote after being re-anchored
 * twice — "a positional selector on this form is now a selector that will move again the next time a
 * field is added above it". A throw naming two inputs is a better failure than a test that silently
 * starts driving the wrong one.
 */
async function pick(page: Page, name: string, width: number, height: number): Promise<void> {
  await page.locator('input[type="file"]').setInputFiles({
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

/**
 * The comparator's frame.
 *
 * Named once because it is now addressed by two different roles: `slider` while it is showing the two
 * layers, and `group` while one derived picture — the difference plate — fills it. A frame that
 * advertised `role="slider"` with no seam to move would be advertising a role it does not honour,
 * which is the exact defect `reveal1.tsx`'s own header opens with.
 */
function comparator(page: Page) {
  return page.getByLabel("Traced drawing against the photograph");
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
  /*
    THE PICKER'S BUTTON, NOT `getByLabel("Photograph to trace")`, AND THIS IS THE SAME CHANGE `pick`
    ABOVE RECORDS. The picker is a `DropCard`, whose `<input type="file">` is `sr-only`,
    `tabIndex={-1}` and `aria-hidden="true"` on purpose — the BUTTON is the tab stop and the accessible
    control. An `aria-hidden` input has no accessible name, so `getByLabel` cannot see it; and this
    assertion is better for it, because a button a keyboard user can reach is what "reaches its
    controls" actually means.
  */
  await expect(page.getByRole("button", { name: "Choose a photograph" })).toBeVisible();
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

/* ────────────────────────────────────────────────────────────────────────────
 * 5. The frame is what is traced, and the panel does not claim it before it is
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * THE ONE PROPERTY THE CROP TOOL RESTS ON: pressing the button is what changes the pixels the tracer
 * is handed, and until it is pressed the panel says so.
 *
 * This is asserted against `window.__traces` — the sizes the stub tracer was actually given — rather
 * than against the sentence the panel writes about itself, because the failure worth catching is
 * exactly the one where the two disagree. A crop applied to the overlay and not to the trace looks
 * completely correct on screen.
 */
test("a frame reaches the tracer only when it is committed, and the panel says so until then", async ({ page }) => {
  await mount(page);
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();
  await pick(page, "sheet.png", 64, 48);

  // The first preview runs on the whole decode.
  await expect.poll(async () => (await traces(page)).length).toBeGreaterThan(0);
  expect((await traces(page))[0]).toMatchObject({ width: 64, height: 48 });

  /*
    Narrow the frame by typing, which is the route that works with no pointer at all.

    `exact: true` IS LOAD-BEARING: `getByLabel` matches by substring, and the Geometry group's "Stroke
    width" slider also contains "Width", so the loose form is a strict-mode violation naming two
    controls. Better to be exact than to reach for `.first()`, which would silently pick whichever the
    DOM happened to order first the next time a control moved.
  */
  /*
    OPEN THE FRAME CHOOSER FIRST, added 2026-08-28.

    The panel now opens on its primary path only — the photograph, the presets, the essential
    controls, the comparator and the two attach actions — and everything else, the frame chooser
    included, sits behind ONE disclosure. That is the owner's instruction ("advanced/configuration
    settings are placed inside an internal accordion"), so a case that reaches for a frame control
    has to press the door first.

    "Choose a frame" IS ALSO THE READINESS GATE it replaces: the button appears as soon as the
    decode lands, which is exactly what the old `expect(... "Use this frame for the trace"
    ).toBeVisible()` was waiting for.
  */
  const chooseFrame = page.getByRole("button", { name: "Choose a frame" });
  await expect(chooseFrame).toBeVisible({ timeout: 15_000 });
  await chooseFrame.click();

  const frameWidth = page.getByLabel("Width", { exact: true });
  await frameWidth.fill("32");
  await expect(
    page.getByText("Nothing has been applied yet: the trace is still using the whole photograph.")
  ).toBeVisible();
  // …and NOTHING has been traced from it yet. A crop that re-traced on every keystroke would also
  // re-sharpen on every keystroke, which is seconds of work per character.
  const beforeCommit = await traces(page);
  expect(beforeCommit.every((entry) => entry.width === 64)).toBe(true);

  await page.getByRole("button", { name: "Use this frame for the trace" }).click();

  // The editor was asked for exactly the frame that was typed…
  await expect
    .poll(async () => await page.evaluate(() => (window as unknown as { __edits: unknown[] }).__edits.length))
    .toBe(1);
  const edits = await page.evaluate(
    () => (window as unknown as { __edits: { crop: { width: number; height: number } }[] }).__edits
  );
  expect(edits[0].crop).toMatchObject({ x: 0, y: 0, width: 32, height: 48 });

  // …and the tracer then received it, at that size, without anybody pressing anything else.
  await expect.poll(async () => (await traces(page)).at(-1)?.width).toBe(32);
  await expect(page.getByText(/The trace is using 32x48/)).toBeVisible();

  // Moving the frame again makes the panel say plainly that what is on screen is no longer what is
  // being traced — the §1.10 rule, and the alternative is a control that has silently stopped mattering.
  await frameWidth.fill("20");
  await expect(page.getByText(/The frame on screen is not the one being traced/)).toBeVisible();
});

/* ────────────────────────────────────────────────────────────────────────────
 * 6. The two downloads
 * ──────────────────────────────────────────────────────────────────────────── */

/** Every file the panel handed to `saveBlobToDisk`, in order. See `DOWNLOAD_STUB`. */
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

  // The two downloads moved behind the one disclosure with the rest of the advanced controls, as
  // part of the 2026-08-28 accordion — see the note at the first "Choose a frame" gate in this file.
  await page.getByRole("button", { name: "Show more options" }).click();
  const downloadTrace = page.getByRole("button", { name: "Download the trace (SVG)" });
  const downloadRender = page.getByRole("button", { name: "Download the rendered image (PNG)" });

  // Until the first preview lands there is nothing to download and both buttons say so by being
  // disabled — a download button that produces an empty file is worse than one that waits.
  await expect(downloadTrace).toBeEnabled({ timeout: 15_000 });
  await expect(downloadRender).toBeEnabled();

  await downloadTrace.click();
  await expect.poll(async () => (await saved(page)).length, { timeout: 15_000 }).toBe(1);
  expect((await saved(page))[0].name).toBe("sheet-line-art.svg");
  expect((await saved(page))[0].type).toBe("image/svg+xml");

  // THE FILE IS THE TRACE, not a screenshot of it: real path data, and the provenance sentence naming
  // the photograph it came from — which is what makes a copy on somebody's laptop still able to say
  // what it is six months later.
  const svg = await page.evaluate(async () =>
    (window as unknown as { __saved: { blob: Blob }[] }).__saved[0].blob.text()
  );
  expect(svg).toContain("<svg");
  expect(svg).toContain("<path");
  expect(svg).toContain("Traced on the device from sheet.png");

  // THE ASSERTION THE WHOLE CASE IS FOR. Everything on screen was a preview at half the working edge
  // (the stub halves it exactly as the real worker reduces it), so a download that saved `svgInput`
  // would hand over a coarser drawing under the same name, with the same path count, and nothing on
  // screen would differ. The press re-traces at full resolution first.
  expect((await traces(page)).at(-1)?.preview).toBe(false);

  await downloadRender.click();
  await expect.poll(async () => (await saved(page)).length, { timeout: 15_000 }).toBe(2);
  const both = await saved(page);
  expect(both[1].name).toBe("sheet-traced.png");
  expect(both[1].type).toBe("image/png");
  expect(both[1].size).toBeGreaterThan(0);
  // A DIFFERENT SUFFIX FROM THE PLATE THE RECORD GETS. Both are PNGs of one drawing, so sharing a name
  // would leave two files in a downloads folder with nothing but the byte count to tell them apart.
  expect(both[1].name).not.toBe(both[0].name.replace(/\.svg$/, ".png"));
  expect((await traces(page)).at(-1)?.preview).toBe(false);

  // NEITHER DOWNLOAD FILED ANYTHING. The panel's promise is that everything above the rule writes to
  // the record and nothing below it does; a download that quietly also attached would be the worst
  // possible reading of "take a copy".
  expect(await page.evaluate(() => (window as unknown as { __attached: File[] }).__attached.length)).toBe(0);
  expect(await page.evaluate(() => (window as unknown as { __sources: File[] }).__sources.length)).toBe(0);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 7. The comparator
 * ──────────────────────────────────────────────────────────────────────────── */

test("the comparator opens on the trace, with the photograph as the layer that gets revealed", async ({ page }) => {
  // Slow enough that the "no comparison yet" state is a state and not a race.
  await mount(page, { traceMs: 400 });
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();

  // Before a photograph is chosen there is no comparator at all — not an empty frame.
  await expect(page.getByRole("slider", { name: "Traced drawing against the photograph" })).toHaveCount(0);
  await expect(page.getByText("The trace against the photograph")).toHaveCount(0);

  await pick(page, "sheet.png", 64, 48);

  // NO TRACE YET, AND THE CARD SAYS SO IN WORDS. An empty box with no sentence is the state this
  // repository's most repeated bug class looks like: indistinguishable from a place with no records.
  await expect(page.getByText("The trace against the photograph")).toBeVisible();
  const slider = page.getByRole("slider", { name: "Traced drawing against the photograph" });
  await expect(slider).toHaveCount(0);

  await expect(slider).toBeVisible({ timeout: 15_000 });

  // ── THE PROPERTY THE OWNER ASKED FOR, AND THE ONE MISTAKE THAT LOOKS IDENTICAL FROM THE CODE ──
  // `Reveal1` clips the BEFORE layer by `position`. At 0 the before layer is fully clipped, so the
  // frame shows the AFTER image — the trace — with the divider hard against the leading edge. Passing
  // the trace as `beforeImage` would produce a slider that opens on the photograph and reveals the
  // drawing: the same two pictures, the opposite feature.
  await expect(slider).toHaveAttribute("aria-valuenow", "0");
  await expect(slider).toHaveAttribute("aria-valuetext", "0% Photograph, 100% Traced drawing");
  await expect(slider).toHaveAttribute("aria-orientation", "horizontal");

  // …and the clipped layer really is the photograph. This is the assertion that cannot be satisfied by
  // getting the two images the wrong way round: the element carrying the clip holds the photograph.
  const clipped = page.locator('div[style*="clip-path"] img');
  await expect(clipped).toHaveAttribute("alt", "The photograph sheet.png, as the tracing engine read it");
  await expect(page.getByAltText("The traced drawing, on white")).toBeVisible();

  // Both layers are real bitmaps this page made, not a remote URL an optimiser would have to fetch.
  const sources = await page.locator("img").evaluateAll((nodes) => nodes.map((n) => (n as HTMLImageElement).src));
  expect(sources.length).toBe(2);
  expect(sources.every((src) => src.startsWith("blob:"))).toBe(true);
  expect(sources[0]).not.toBe(sources[1]);

  // THE KEYBOARD HALF, WHICH IS THE ONLY WAY IN FOR A KEYBOARD, A SWITCH DEVICE OR A READER. The
  // component's header records that it shipped with role="slider" and no key handler at all —
  // announcing itself as a slider and then ignoring every arrow key.
  await slider.focus();
  await page.keyboard.press("ArrowRight");
  await expect(slider).toHaveAttribute("aria-valuenow", "2");
  await page.keyboard.press("End");
  await expect(slider).toHaveAttribute("aria-valuenow", "100");
  await expect(slider).toHaveAttribute("aria-valuetext", "100% Photograph, 0% Traced drawing");
  await page.keyboard.press("Home");
  await expect(slider).toHaveAttribute("aria-valuenow", "0");

  // The frame is the source's own ratio rather than 16:9, so a portrait sheet is not centre-cropped
  // to a strip. 64x48 is 4:3.
  // Compared as a NUMBER, not as a string: the prop is a number, so the computed value comes back as
  // "1.33333 / 1" rather than "4 / 3" — and `aspect-video` would be 16/9 either way.
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

  // The engine's own sentence, in the red alert, once.
  await expect(page.getByText("That photograph could not be traced.")).toBeVisible({ timeout: 15_000 });

  // THE COMPARATOR POINTS AT THAT MESSAGE RATHER THAN RESTATING IT. Two copies of one fault in one
  // card is how a designer ends up believing there are two faults.
  await expect(
    page.getByText("The trace did not finish, so there is nothing to compare. The reason is above.")
  ).toBeVisible();
  await expect(page.getByRole("slider", { name: "Traced drawing against the photograph" })).toHaveCount(0);

  // And no button offers a file it does not have.
  //
  // THE DISCLOSURE IS OPENED FIRST, and that is what keeps this assertion meaning anything. The
  // downloads moved behind it on 2026-08-28, so `toBeDisabled()` against an unmounted control would
  // fail for the wrong reason and `toHaveCount(0)` would pass for the wrong one — neither would be
  // testing that a failed trace offers a file it does not have.
  await page.getByRole("button", { name: "Show more options" }).click();
  await expect(page.getByRole("button", { name: "Download the trace (SVG)" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "Download the rendered image (PNG)" })).toBeDisabled();
  await expect(page.getByRole("button", { name: `Add the line art to “${TARGET_LABEL}”` })).toBeDisabled();
  expect(await saved(page)).toEqual([]);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 8. The picker card
 *
 * `DropCard` is the one request in this wave whose whole point is a NEW INTERACTION, and it shipped
 * with nothing asserting any of it: the case above only checked that its button was visible. Every
 * branch below is one a reader can talk themselves into being correct — which is exactly the class of
 * thing this file exists for, because the panel it lives in was once entirely unusable while
 * twenty-two green tests said otherwise.
 *
 * The card is reached THROUGH THE PANEL rather than mounted on its own, deliberately: its `validate`
 * rule lives at the call site (`SketchTraceField` is what decides that an SVG is refused and that an
 * empty MIME type is not), so a case that mounted the card with a rule of its own would be testing a
 * rule nothing ships.
 * ──────────────────────────────────────────────────────────────────────────── */

/** The dashed drop zone itself — the element carrying the four drag handlers. */
function dropZone(page: Page) {
  // `border-dashed` is load-bearing on this element rather than incidental styling: the card's own
  // header records why it is `border-2 border-dashed border-line-200` and not a bare `border`.
  return page.locator("div.border-dashed");
}

/** A `DataTransfer` holding these files, as a drop would carry them. */
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

  // ── A FILE THAT IS DEFINITELY NOT A PHOTOGRAPH ────────────────────────────
  await page.locator('input[type="file"]').setInputFiles({
    name: "brief.pdf",
    mimeType: "application/pdf",
    buffer: Buffer.from("%PDF-1.7\n")
  });

  // The sentence NAMES THE FILE and says why, inside an alert — not a silent no-op, which is what
  // `accept="image/*"` alone gives for a drop and for anybody who switched the dialog to "All files".
  const refusal = page.getByText("brief.pdf — this is application/pdf, not a photograph.", { exact: false });
  await expect(refusal).toBeVisible();
  expect(await refusal.evaluate((node) => node.closest('[role="alert"]') !== null)).toBe(true);
  // …and nothing was traced from it, so `validate` is the rule rather than a decoration on one.
  expect(await traces(page)).toEqual([]);
  // NAMES THE DOOR, not the button behind it. Since the disclosure landed, the inner control is
  // unmounted whether or not there is a photograph, so asserting on it would pass for a reason that
  // has nothing to do with this case. "Choose a frame" is drawn only once a decode exists.
  await expect(page.getByRole("button", { name: "Choose a frame" })).toHaveCount(0);

  // ── THE SVG, WHICH IS AN IMAGE AND IS STILL REFUSED ───────────────────────
  // `decodeToPixels`'s header: tracing vector art is a round trip that can only lose. It is the one
  // refusal a permissive `image/*` rule would otherwise let through.
  await page.locator('input[type="file"]').setInputFiles({
    name: "already-vector.svg",
    mimeType: "image/svg+xml",
    buffer: Buffer.from("<svg xmlns='http://www.w3.org/2000/svg'/>")
  });
  await expect(page.getByText("an SVG is already vector art", { exact: false })).toBeVisible();
  expect(await traces(page)).toEqual([]);

  /*
    ── AN EMPTY MIME TYPE IS TAKEN, WHICH IS THE COMMONEST FILE ON AN IPHONE ─

    HEIC and AVIF arrive from several camera rolls with `type: ""`. A rule that refused everything
    without an image MIME type would refuse the photograph the fieldwork is actually done with; the
    decoder is what judges whether the bytes can be read, because it is the code that tried.

    DROPPED RATHER THAN CHOSEN, and not by preference: `setInputFiles` with `mimeType: ""` does not
    produce a file with an empty type — Chromium fills in `application/octet-stream`, which this card
    then rightly refuses, and the case would be asserting the opposite of what it says. Constructing
    the `File` in the page is the only route that can hand the rule a genuinely typeless file, which
    the drop path does anyway.
  */
  const typeless = await transferOf(page, [
    { name: "from-the-camera-roll", type: "", bytes: [...png(64, 48)] }
  ]);
  await dropZone(page).dispatchEvent("drop", { dataTransfer: typeless });
  await expect.poll(async () => (await traces(page)).length, { timeout: 15_000 }).toBe(1);
  // The refusal from the previous attempt is gone rather than left standing over a file that worked.
  await expect(page.getByText("an SVG is already vector art", { exact: false })).toHaveCount(0);
  await expect(page.getByRole("alert")).toHaveCount(0);
});

test("the picker takes a drop, says so while the file is over it, and names what it left out", async ({ page }) => {
  await mount(page);
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();

  const zone = dropZone(page);
  await expect(zone).toBeVisible();

  // THE STATE IS TEXT, NOT ONLY A BORDER COLOUR. §1.5: a signal that exists only as colour is a signal
  // a colour-blind reader never gets.
  await expect(page.getByText("…or drag a file onto this card.")).toBeVisible();
  const dragged = await transferOf(page, [{ name: "sheet.png", type: "image/png", bytes: [...png(64, 48)] }]);
  await zone.dispatchEvent("dragenter", { dataTransfer: dragged });
  await zone.dispatchEvent("dragover", { dataTransfer: dragged });
  await expect(page.getByText("Let go to use this file.")).toBeVisible();

  /*
    THE HIGHLIGHT SURVIVES CROSSING THE BUTTON IN THE MIDDLE OF THE CARD, which is exactly where
    anybody aims. `dragenter`/`dragleave` fire per element, so entering the button is a `dragleave` on
    the card — and the boolean the house's other drop zone keeps would flicker off here. The counter is
    what this asserts: one leave after two enters is still inside.
  */
  await page.getByRole("button", { name: "Choose a photograph" }).dispatchEvent("dragenter", {
    dataTransfer: dragged
  });
  await zone.dispatchEvent("dragleave", { dataTransfer: dragged });
  await expect(page.getByText("Let go to use this file.")).toBeVisible();

  // ── THE DROP ITSELF, CARRYING THREE FILES ONTO A CARD THAT HOLDS ONE ──────
  const three = await transferOf(page, [
    { name: "first.png", type: "image/png", bytes: [...png(64, 48)] },
    { name: "second.png", type: "image/png", bytes: [...png(32, 24)] },
    { name: "brief.pdf", type: "application/pdf", bytes: [...Buffer.from("%PDF-1.7\n")] }
  ]);
  await zone.dispatchEvent("drop", { dataTransfer: three });

  // The first ACCEPTED file is the one used…
  await expect.poll(async () => (await traces(page)).length, { timeout: 15_000 }).toBeGreaterThan(0);
  expect((await traces(page))[0]).toMatchObject({ width: 64, height: 48 });
  // …and both the refusal and the truncation are said out loud. §1.10: quietly using one of three
  // files is the version of this that looks like it worked.
  await expect(page.getByText("One file was not taken: brief.pdf", { exact: false })).toBeVisible();
  await expect(
    page.getByText("This holds one file, so the first was used and the other one was left out.")
  ).toBeVisible();
  // The drag state goes with the file, rather than staying highlighted for the rest of the page's life.
  await expect(page.getByText("…or drag a file onto this card.")).toBeVisible();
});

test("the picker's button is the tab stop, and the input is cleared so the same file can be re-chosen", async ({
  page
}) => {
  await mount(page);
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();

  // The file input is not a tab stop and has no accessible name: `sr-only`, `tabIndex={-1}` and
  // `aria-hidden`. A second unlabelled tab stop there is the keyboard trap the card's header cites in
  // three other files — and `sr-only` rather than `hidden` because a `display: none` input cannot be
  // clicked programmatically in every browser.
  const input = page.locator('input[type="file"]');
  await expect(input).toHaveAttribute("tabindex", "-1");
  await expect(input).toHaveAttribute("aria-hidden", "true");
  await expect(input).toHaveClass(/sr-only/);

  const choose = page.getByRole("button", { name: "Choose a photograph" });
  await choose.focus();
  await expect(choose).toBeFocused();
  // A real `<button>`, so it answers to the keyboard — which a `<label>` wrapping a hidden input, the
  // house's other pattern, does not.
  expect(await choose.evaluate((node) => node.tagName)).toBe("BUTTON");
  // And the sentence saying what the card takes is the button's own description, not loose text
  // beside it.
  const describedBy = await choose.getAttribute("aria-describedby");
  expect(describedBy).not.toBeNull();
  await expect(page.locator(`#${describedBy}`)).toContainText("JPEG, PNG, WebP");

  await pick(page, "sheet.png", 64, 48);
  await expect.poll(async () => (await traces(page)).length, { timeout: 15_000 }).toBeGreaterThan(0);

  /*
    THE INPUT IS EMPTY AFTERWARDS, WHICH IS THE FIX THE CARD WAS WRITTEN FOR. `change` does not fire
    for an unchanged value, so a card that left the chosen name in the input answered the second press
    of the same button with nothing at all — and the designer whose first attempt was refused presses
    exactly that. `WorkshopCodeScanner` and `PrototypeModelField` both clear it; `SketchTraceField` did
    not, which is the inconsistency the card closed.
  */
  await expect(input).toHaveJSProperty("value", "");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 9. The comparator's badges and its grip
 *
 * Case 7 above pins the two LAYERS the right way round, by `aria-valuetext` and by which image carries
 * the clip. It passed while the badge printed over the drawing said "Photograph": the two captions were
 * pinned to the top corners and drawn whatever the divider was doing, so the frame that is entirely the
 * traced drawing on open was captioned with the other layer's name. The prop wiring was right and the
 * caption was backwards — the same "plausible and wrong" shape one level down, and nothing in this file
 * looked at what a designer actually reads.
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The `clip-path` of the layer one badge sits in.
 *
 * Each badge is inside a wrapper clipped exactly as its own image is, so this is the measurement that
 * says whether a caption is being shown over the picture it names. `toBeVisible` cannot answer it: a
 * clipped element is still laid out, still has a box, and still passes.
 */
async function badgeClip(page: Page, text: string): Promise<string> {
  /*
    SCOPED TO THE FRAME, WHICH IT DID NOT USED TO NEED. The comparator grew a chip row above it —
    Drawing · Wipe · Photograph · Difference — so "Photograph" is now the name of a BUTTON as well as
    the name of a badge, and an unscoped `getByText` matches both and throws under strict mode. Scoping
    to the slider is also the more honest assertion: what is being measured is a caption drawn over a
    picture, and the chip is not one.
  */
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

  // ── ON OPEN THE FRAME IS ALL TRACE, SO ONLY THE TRACE'S CAPTION SHOWS ─────
  // The photograph's badge is clipped away by the full width of the frame, exactly as the photograph
  // is. Asserted as "the right edge is clipped by 100%" rather than by a pixel count, so the case does
  // not depend on how wide the panel happens to be.
  expect(await badgeClip(page, "Photograph")).toBe("inset(0100%00)");
  // …and the drawing's badge is clipped by nothing at all.
  expect(await badgeClip(page, "Traced drawing")).toBe("inset(0000%)");

  /*
    ── THE GRIP IS HELD INSIDE THE FRAME, WHICH IS `overflow-hidden` ─────────

    Centred on a divider at 0% it was half-clipped: a 20px half-disc at the extreme edge was the only
    thing a pointer could grab, partly under the badge that used to sit there. The keyboard route always
    worked, which is why nothing noticed.

    ASSERTED ON THE INLINE STYLE AND NOT ON A BOUNDING BOX, because THIS PAGE HAS NO STYLESHEET. The
    harness mounts the component into a bare document (see the file header) — Tailwind never runs, so
    `absolute`, `h-10 w-10` and `-translate-x-1/2` are inert class names and every element's box is the
    unstyled block it would be without them. Measured here, the grip is 1264px wide and 0 tall, and a
    geometric assertion would pass whatever the component did. The inline `left`/`top` this component
    computes IS the fix, so that is what is checked; the seam is checked in the same breath, because
    the trade the fix makes is "the grip may sit up to a radius off the seam near the ends" and a fix
    that moved the SEAM instead would draw the join in the wrong place.
  */
  // `calc(…)` inside `clamp(…)` is read back with the wrapper dropped, so the bound is matched rather
  // than compared to a string — the assertion is "the seam's position, held 20px in", either way.
  // Either spelling is the same value: Chromium reads a `calc()` nested inside a `clamp()` back with
  // the wrapper dropped, and this assertion is about the bound, not about a string.
  const gripAt = (percent: number) => [
    `clamp(20px, ${percent}%, calc(100% - 20px))`,
    `clamp(20px, ${percent}%, 100% - 20px)`
  ];
  const gripLeft = () => slider.locator("div.h-10.w-10").evaluate((node) => (node as HTMLElement).style.left);
  const seamLeft = () => slider.locator("div.bg-white").evaluate((node) => (node as HTMLElement).style.left);
  expect(gripAt(0)).toContain(await gripLeft());
  expect(await seamLeft()).toBe("0%");

  // ── AND AT THE OTHER END, EVERY FACT THE OTHER WAY ROUND ──────────────────
  await slider.focus();
  await page.keyboard.press("End");
  await expect(slider).toHaveAttribute("aria-valuenow", "100");
  expect(await badgeClip(page, "Photograph")).toBe("inset(00%00)");
  expect(await badgeClip(page, "Traced drawing")).toBe("inset(000100%)");
  expect(gripAt(100)).toContain(await gripLeft());
  expect(await seamLeft()).toBe("100%");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 10. The four number boxes
 *
 * "The numbers are the primary route, not a fallback" is what the crop tool says about itself, and it
 * shipped clamping every keystroke — so Left and Top could not be typed into at all while the frame was
 * whole, and Width returned a different number from the one typed. Case 5 above passed throughout,
 * because 32 into a 64px photograph is legal on its first character.
 * ──────────────────────────────────────────────────────────────────────────── */

test("a half-typed number survives being typed, and a clamp that moves one says so", async ({ page }) => {
  await mount(page);
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();
  // 300 wide, so "150" passes through a first character far below the 16px minimum edge.
  await pick(page, "sheet.png", 300, 200);
  /*
    OPEN THE FRAME CHOOSER FIRST, added 2026-08-28.

    The panel now opens on its primary path only — the photograph, the presets, the essential
    controls, the comparator and the two attach actions — and everything else, the frame chooser
    included, sits behind ONE disclosure. That is the owner's instruction ("advanced/configuration
    settings are placed inside an internal accordion"), so a case that reaches for a frame control
    has to press the door first.

    "Choose a frame" IS ALSO THE READINESS GATE it replaces: the button appears as soon as the
    decode lands, which is exactly what the old `expect(... "Use this frame for the trace"
    ).toBeVisible()` was waiting for.
  */
  const chooseFrame = page.getByRole("button", { name: "Choose a frame" });
  await expect(chooseFrame).toBeVisible({ timeout: 15_000 });
  await chooseFrame.click();

  const width = page.getByLabel("Width", { exact: true });
  const left = page.getByLabel("Left", { exact: true });

  /*
    TYPED CHARACTER BY CHARACTER, WHICH IS THE WHOLE POINT. `fill` sets the value in one shot and would
    pass against the old code: 150 is legal, so one clamp of the finished number is the number. The
    defect lived in the intermediate states — "1" became 16, and the "5" that followed landed on it.
  */
  await width.click();
  await width.press("Control+a");
  await width.pressSequentially("150", { delay: 20 });
  await expect(width).toHaveValue("150");
  // …and the frame really is 150 wide, not 1516 or 160: this sentence is drawn from `crop` itself.
  await expect(page.getByText("150x200 of 300x200", { exact: false })).toBeVisible();

  /*
    LEFT, WHILE THE FRAME IS STILL THE FULL WIDTH — the box that could not be typed into at all. `x` is
    clamped to `min(width - crop.width, …)`, which is 0 for as long as the crop is whole, so every
    keystroke was discarded and nothing on screen said why.
  */
  await page.getByRole("button", { name: "Use the whole photograph" }).click();
  await left.click();
  await left.press("Control+a");
  await left.pressSequentially("40", { delay: 20 });
  // The characters are still there while the box has focus…
  await expect(left).toHaveValue("40");
  await left.press("Enter");
  // …and committing is when the impossible one is refused — with the reason, and with the way out.
  await expect(left).toHaveValue("0");
  await expect(page.getByText("Left cannot be 40", { exact: false })).toBeVisible();
  await expect(page.getByText("Reduce Width first", { exact: false })).toBeVisible();

  // With the frame narrowed the same 40 is legal and is taken, so the sentence above was true.
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

  // And the frame that reaches the editor is the one the boxes say it is.
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

/* ────────────────────────────────────────────────────────────────────────────
 * 11. The provenance sentence that actually reaches the file
 *
 * `lib/trace/imageEdit.describeEdit` had no caller outside its own spec: the sentence that reached the
 * exported SVG was rebuilt by hand in `FramePanel`, so the promise the unit spec pins — "The original
 * photograph is unchanged." — could have been edited out of the shipped path with the whole suite
 * green. The sentence now comes back from the editor, which is the side of the bundle boundary that can
 * call that function, and this case is what makes the two halves one thing: whatever the editor says is
 * what the file says.
 * ──────────────────────────────────────────────────────────────────────────── */

test("the frame's provenance sentence reaches the exported SVG from the editor that made the pixels", async ({
  page
}) => {
  await mount(page);
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();
  await pick(page, "sheet.png", 64, 48);
  /*
    OPEN THE FRAME CHOOSER FIRST, added 2026-08-28.

    The panel now opens on its primary path only — the photograph, the presets, the essential
    controls, the comparator and the two attach actions — and everything else, the frame chooser
    included, sits behind ONE disclosure. That is the owner's instruction ("advanced/configuration
    settings are placed inside an internal accordion"), so a case that reaches for a frame control
    has to press the door first.

    "Choose a frame" IS ALSO THE READINESS GATE it replaces: the button appears as soon as the
    decode lands, which is exactly what the old `expect(... "Use this frame for the trace"
    ).toBeVisible()` was waiting for.
  */
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
  // THE EDITOR'S SENTENCE, VERBATIM — see the stub's own note on why its wording deliberately differs
  // from the real function's. A panel that rebuilt the sentence itself would write something plausible
  // instead, and this is the only assertion that can tell the two apart.
  expect(svg).toContain("[from the editor]");
  expect(svg).toContain("Cropped on the device to 32x48 at (0, 0) of 64x48.");
  // …carried inside the ordinary provenance note rather than instead of it.
  expect(svg).toContain("Traced on the device from sheet.png");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 12. The comparator's four views, its hold, and its magnifier
 *
 * The whole comparison path had no covering test on either client until 2026-08-27 — a parity audit
 * found `buildComparisonPlates`, `renderTrace` and both clients' comparators with callers and nothing
 * asserting them. Cases 7 and 9 above pin the two layers the right way round; these pin what the
 * portal grew in that wave, all of which is state a designer can get into and cannot get out of if it
 * is wrong.
 * ──────────────────────────────────────────────────────────────────────────── */

test("the chips show each picture whole, and Wipe comes back to where the seam was left", async ({ page }) => {
  await mount(page);
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();
  await pick(page, "sheet.png", 64, 48);

  const slider = page.getByRole("slider", { name: "Traced drawing against the photograph" });
  await expect(slider).toBeVisible({ timeout: 15_000 });

  /*
    THE THREE LABELS ARE THE HANDSET'S, and this asserts them by name rather than by position: a
    designer moves between the two apps mid-workshop, and `DwSketchTraceCompare.kt` is where these
    words are decided. The fourth is new on both clients and is named identically on both.
  */
  const chip = (name: string) => page.getByRole("button", { name, exact: true });
  for (const name of ["Drawing", "Wipe", "Photograph", "Difference"]) {
    await expect(chip(name)).toBeVisible();
  }
  // Wipe is the default, exactly as it is on the handset.
  await expect(chip("Wipe")).toHaveAttribute("aria-pressed", "true");
  await expect(slider).toHaveAttribute("aria-valuenow", "0");

  // ── EACH END IS ONE PRESS, WHICH IS THE WHOLE POINT OF THE ROW ────────────
  // Before this the two end states existed only as the two extremes of a drag, or as Home and End on a
  // control a designer had to know was focusable.
  await chip("Photograph").click();
  await expect(slider).toHaveAttribute("aria-valuenow", "100");
  await expect(slider).toHaveAttribute("aria-valuetext", "100% Photograph, 0% Traced drawing");
  await chip("Drawing").click();
  await expect(slider).toHaveAttribute("aria-valuenow", "0");

  /*
    ── THE PROPERTY THE CONTROLLED SEAM WAS ADDED FOR, AND THE ONE A NAIVE IMPLEMENTATION LOSES ──

    "Drawing" and "Photograph" write the DISPLAYED position; they must not write the stored seam. So a
    designer who put the seam at 40%, looked at the drawing whole, and pressed Wipe again gets 40% back
    — not 0, and not the middle. An implementation that simply set one number on every press passes
    every assertion above and fails this one.
  */
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
  // Two layers while the wipe is showing — the count case 7 asserts, restated here so the change below
  // is a change and not a coincidence.
  expect(await page.locator("img").count()).toBe(2);

  await page.getByRole("button", { name: "Difference", exact: true }).click();

  /*
    THE FRAME STOPS BEING A SLIDER, because there is no longer a seam in it. A frame that kept
    `role="slider"` while showing one derived picture would advertise a role it does not honour, which
    is the defect `reveal1.tsx`'s own header opens with — and it would tell a screen-reader user there
    is a value to change when there is not.
  */
  const solo = page.getByRole("group", { name: "Traced drawing against the photograph" });
  await expect(solo).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole("slider", { name: "Traced drawing against the photograph" })).toHaveCount(0);

  // ONE PICTURE, AND IT IS THE THIRD PLATE — not one of the two re-labelled. Asserted on the count and
  // on the alt together: either alone could be satisfied by showing the drawing again.
  expect(await page.locator("img").count()).toBe(1);
  /*
    THE DESCRIPTION IS THE HANDSET'S, CHARACTER FOR CHARACTER. `DwSketchTraceCompare.kt` puts this
    same sentence on its own frame as the `contentDescription` for this same mode, and this is the one
    assertion that would fail if either client reworded it. It says what the picture MEANS rather than
    naming the operation, because a reader who cannot see the plate gets nothing from "the difference
    between two pictures".
  */
  await expect(
    page.getByAltText(
      "The traced drawing and the photograph subtracted from each other. Dark where they agree, " +
        "bright where they differ."
    )
  ).toBeVisible();
  const source = await page.locator("img").evaluate((node) => (node as HTMLImageElement).src);
  expect(source.startsWith("blob:")).toBe(true);

  /*
    AND THE WORD ON THE PICTURE. Scoped INSIDE the frame, because "Difference" is also the name of the
    chip that got here — an unscoped match would pass with no badge at all. The handset draws this at
    the same corner for a reason it states and this client had not: a difference plate of a good trace
    is very nearly black, and a nearly black frame carrying no word is indistinguishable from a
    picture that failed to load.
  */
  await expect(solo.getByText("Difference", { exact: true })).toBeVisible();

  /*
    AND THE SENTENCE, WHICH IS THE HANDSET'S VERBATIM. The plate is black where the two agree and
    bright where they do not, which is not what anybody expects a picture of their own sketch to look
    like — without the sentence the honest reading of a mostly-black frame is that the trace failed.
  */
  await expect(page.getByText("black where they agree, bright where they do not")).toBeVisible();

  // BACK TO THE WIPE, with both layers and the seam where it was. The magnification and the mode are
  // the only things the chips touch; the plates are not rebuilt.
  await page.getByRole("button", { name: "Wipe", exact: true }).click();
  const wipe = page.getByRole("slider", { name: "Traced drawing against the photograph" });
  await expect(wipe).toBeVisible();
  expect(await page.locator("img").count()).toBe(2);
  // The solo badge is withdrawn with the plate it named, and the two LAYER badges come back in its
  // place. Three badges at once would be the frame captioning itself twice over.
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

  // Put the seam somewhere a restore can be told apart from a reset: 20% is neither end and not the
  // middle either.
  await slider.focus();
  for (let i = 0; i < 10; i += 1) await page.keyboard.press("ArrowRight");
  await expect(slider).toHaveAttribute("aria-valuenow", "20");

  /*
    `hover()` RATHER THAN A BOUNDING BOX AND `mouse.move`. The harness mounts into a bare document with
    no stylesheet (see the file header), so the frame is an unstyled block as tall as its aspect ratio
    makes it and is usually below the fold — and `page.mouse` takes VIEWPORT coordinates, so a box read
    off an unscrolled page would put the press somewhere else entirely. `hover()` scrolls it in and
    lands on the frame's centre, which is also the more demanding place to press: a tap there would
    write the seam to about 50, so "it came back to 20" cannot be satisfied by a press that did nothing.
  */
  await slider.hover();
  await page.mouse.down();
  /*
    LONGER THAN `REVEAL_PEEK_HOLD_MS`, WHICH IS 220. The threshold is not zero on purpose: a peek that
    began on contact would flash the photograph at the start of every pinch, because a two-finger
    gesture puts one finger down first. `e2e/sketch-compare-unit.spec.ts` pins the number; this pins
    that the panel honours it.
  */
  await expect(slider).toHaveAttribute("aria-valuenow", "100", { timeout: 3_000 });

  /*
    ── AND THE HALF THAT MAKES IT WORTH HAVING ───────────────────────────────

    Letting go returns to 20 and not to 0, and not to wherever the pointer happened to be. The stored
    seam is deliberately not rewritten by the hold, which is the whole reason the gesture is usable:
    the designer keeps their place. It also means the press may not write the seam on CONTACT the way
    this component used to — a press that moved the seam under the finger about to peek would restore a
    position nobody chose. 30px into a frame this wide is nowhere near 20%.
  */
  await page.mouse.up();
  await expect(slider).toHaveAttribute("aria-valuenow", "20");
});

test("the magnifier answers to the keyboard, reports itself, and can be put back to fit", async ({ page }) => {
  await mount(page);
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();
  await pick(page, "sheet.png", 64, 48);

  const slider = page.getByRole("slider", { name: "Traced drawing against the photograph" });
  await expect(slider).toBeVisible({ timeout: 15_000 });

  // AT FIT THERE IS NOTHING TO SAY AND NOTHING TO PUT BACK, so the readout is absent rather than
  // reading "1×" — a control offering to undo something that has not happened.
  await expect(page.getByRole("button", { name: /reset to fit/ })).toHaveCount(0);

  /*
    THE KEYBOARD ROUTE IS NOT A COURTESY. A magnifier reachable only by a trackpad pinch or a ctrl-wheel
    is a magnifier a keyboard user and a switch device do not have — and this one exists because the
    failure the comparator is for is invisible at fit: a pencil line on a plate capped at 1024px, drawn
    into a card a few hundred pixels wide, is sub-pixel.
  */
  await slider.focus();
  await page.keyboard.press("+");
  const readout = page.getByRole("button", { name: /reset to fit/ });
  await expect(readout).toBeVisible();
  await expect(readout).toHaveText("1.3× — reset to fit");

  // The transform is on ONE wrapper containing both layers, never on the two images — the invariant
  // both clients' headers state, because independently transformed layers show a drawing that appears
  // to have drifted off its own photograph. Asserted where it cannot be faked: no `<img>` carries one.
  const imageTransforms = await page
    .locator("img")
    .evaluateAll((nodes) => nodes.map((node) => getComputedStyle(node).transform));
  expect(imageTransforms.every((value) => value === "none")).toBe(true);
  const wrappers = await page
    .locator('div[style*="scale"]')
    .evaluateAll((nodes) => nodes.map((node) => (node as HTMLElement).style.transform));
  expect(wrappers.length).toBe(1);
  expect(wrappers[0]).toContain("scale(1.25)");

  // THE SEAM DOES NOT MOVE WHEN THE PICTURE DOES. It is drawn in the frame's own space, so magnifying
  // cannot carry it off the frame and leave a designer with no way to bring it back.
  await expect(slider).toHaveAttribute("aria-valuenow", "0");

  // A SECOND PRESS COMPOUNDS, and a press the other way undoes exactly one step — so the readout is
  // reporting the transform rather than counting presses.
  await page.keyboard.press("+");
  await expect(readout).toHaveText("1.6× — reset to fit");
  await page.keyboard.press("-");
  await expect(readout).toHaveText("1.3× — reset to fit");

  // …and 0 puts it back to fit, at which point the readout withdraws again: a control offering to undo
  // something that has not happened is a control that has to be read before it can be ignored.
  await page.keyboard.press("0");
  await expect(page.getByRole("button", { name: /reset to fit/ })).toHaveCount(0);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 13. Stopping a trace, and the bar that says one is still going
 *
 * Cancellation was real from the first day — an `AbortController` per run, a cancel message, the
 * worker's own token unwinding the pipeline — and fired only implicitly, when a moved slider
 * superseded the run or the panel unmounted. `traceClient.busy`'s docblock has called itself "the
 * enabled state of a Cancel control" throughout, and no such control was ever built: the only way to
 * abandon a full-resolution trace was to move something and hope.
 * ──────────────────────────────────────────────────────────────────────────── */

test("a running trace can be stopped, and says so until it really has", async ({ page }) => {
  // Slow enough that "running" is a state and not a race.
  await mount(page, { traceMs: 1_500 });
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();
  await pick(page, "sheet.png", 64, 48);

  const stop = page.getByRole("button", { name: "Stop", exact: true });
  await expect(stop).toBeVisible({ timeout: 15_000 });
  await stop.click();

  /*
    "Stopping…" AND NOT A BUTTON THAT VANISHES. The engine checks its cancellation token BETWEEN stages
    and nowhere else, so the worst case is the length of the longest single stage — seconds at full
    resolution. A control that promised instant would be wrong, and one that disappeared on the press
    would claim the run had stopped while it was still running.
  */
  await expect(page.getByRole("button", { name: "Stopping…" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Stopping…" })).toBeDisabled();

  // …and it is cleared by the run's own unwinding rather than by a guessed interval, so the word is on
  // screen for exactly as long as stopping takes.
  await expect(page.getByRole("button", { name: /Stop/ })).toHaveCount(0, { timeout: 15_000 });

  // NOTHING RED. A cancellation is the ordinary consequence of changing your mind, not a failure, and
  // the panel has always treated a superseded trace that way — the button inherits it.
  await expect(page.getByText("That photograph could not be traced.")).toHaveCount(0);
});

test("a full-resolution run draws a bar, and says the bar counts stages until it has measured one", async ({
  page
}) => {
  // Long enough that the bar and its sentence are a state and not a race against the panel closing.
  await mount(page, { traceMs: 2_000 });
  await page.getByRole("button", { name: "Trace a sketch into line art" }).click();
  await pick(page, "sheet.png", 64, 48);

  /*
    NO BAR FOR THE PREVIEW, AND NOT BECAUSE OF A FLAG IN THE PANEL. `trace.worker.ts` hands
    `Pipeline.run` a progress callback and hands `Pipeline.runPreview` none at all, so a preview emits
    no stage events and there is nothing to draw one from. The stub reproduces that split exactly,
    which is what makes this assertion mean anything.
  */
  await expect(page.getByRole("button", { name: "Stop", exact: true })).toBeVisible({ timeout: 15_000 });
  expect(await page.getByText("The bar counts stages, not time").count()).toBe(0);
  await expect(page.getByRole("button", { name: /Stop/ })).toHaveCount(0, { timeout: 15_000 });

  // A press is a full-resolution run, so this one does report stages.
  await page.getByRole("button", { name: `Add the line art to “${TARGET_LABEL}”` }).click();

  /*
    THE ENGINE'S OWN LABEL, WITH THIS FILE'S NUMBER AROUND IT.

    The stub sends "Edges" — deliberately NOT the label `traceStages.ts` holds for the `edge` stage,
    which is "Detecting edges". So this assertion can only pass if the panel prints the string that
    arrived with the event and adds nothing but the position: a panel that looked the stage up in its
    own table and printed that instead would read "Detecting edges. Stage 7 of 12." and fail here.
    Re-typing engine wording in a client is how the two clients end up describing one operation
    differently, and `trace.worker.ts` says so in its own comment.
  */
  await expect(page.getByText("Edges. Stage 7 of 12.")).toBeVisible({ timeout: 15_000 });

  /*
    AND THE SENTENCE UNDER IT, WHICH IS THE HONEST HALF. Until this browser has finished one trace the
    boundaries are the engine's even twelfths — and the two stages that dominate a real trace are worth
    several of the others put together, so the bar will visibly stall. One line costs less than a
    designer deciding the panel has frozen.
  */
  await expect(page.getByText("The bar counts stages, not time")).toBeVisible();
});
