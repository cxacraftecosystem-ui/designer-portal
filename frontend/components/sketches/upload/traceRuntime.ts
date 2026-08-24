/**
 * Everything heavy this feature needs, fetched only once a designer has decided to trace something.
 *
 * WHY THIS FILE EXISTS AT ALL. `lib/trace/traceClient.ts`'s header states the rule in its own third
 * clause: "The later UI wave must still `await import("@/lib/trace/traceClient")` rather than import
 * this module at the top of a stage component." This is that dynamic import, written once, so no
 * component has to remember it and no reviewer has to check twelve files to know whether the engine
 * is on a page's graph. Every import below is inside a function body. Nothing in this module is
 * reachable statically, and `traceParamTable.ts` — the part a panel needs in order to draw itself —
 * deliberately lives in a different file that imports no engine value at all.
 *
 * WHAT IT COSTS, MEASURED BY SOMEBODY ELSE. `lib/trace/README.md` §4 publishes a production Turbopack
 * build of exactly these chunks: 5,070 bytes gzipped on the main thread (`traceClient` +
 * `spawnTraceWorker` + `engine/params`) and 44,253 gzipped inside the worker, where it never touches
 * the page thread's parse budget. That main-thread figure is a fifth of the GSAP payload this
 * repository already dynamic-imports for one headline. {@link loadTracePresets} adds to it — see its
 * own note, which states what and why rather than guessing.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * THE SEAM, NAMED PLAINLY
 * ────────────────────────────────────────────────────────────────────────────
 *
 * `lib/trace/README.md` §3 says: "**Import `traceClient.ts` and nothing else.** Outside this
 * directory, `engine/` and `worker/` are named in exactly one file: `e2e/trace-engine-unit.spec.ts`."
 *
 * {@link loadTracePresets} BREAKS THAT RULE, knowingly, in one function, and it is the only place in
 * this feature that does. The reason is a gap rather than a disagreement: the owner asked for the
 * style and subject presets the other application offers, those presets are `engine/styles.ts` and
 * `engine/subjects.ts`, and `traceClient.ts` — which is not this unit's file to edit — re-exports
 * neither. Its `loadTraceParams()` opens a door for the defaults and the sanitiser and stops there.
 *
 * The honest fix is a `loadTracePresets()` on `traceClient.ts` itself, three lines long, alongside the
 * one it already has. Until whoever owns that file adds it, this function is that door, kept
 * deliberately narrow — one `await import` per preset module, no other engine value anywhere in this
 * feature, and everything it returns is plain data. **If `traceClient` grows the function, delete
 * this one and forward to it; do not keep both.**
 */

import type { CropRect, SharpenSettings } from "@/lib/trace/imageEdit";
import type { EditCall, EditedPixels, ImageEditor } from "@/lib/trace/imageEditClient";
import type {
  SerializedTraceResult,
  TraceParams,
  TraceParamsInput,
  TraceProgress,
  Tracer,
  TransferableImage
} from "@/lib/trace/traceClient";

/* ────────────────────────────────────────────────────────────────────────────
 * The tracer
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The parts of `traceClient` this feature uses, resolved.
 *
 * `Tracer` is handed over as a CONSTRUCTOR rather than as an instance, because the client's own
 * header is explicit that a tracer is per-surface and not per-trace — "Starting a worker parses the
 * whole engine, so a tracer built per keystroke would re-parse it per keystroke" — and only the
 * component knows when its surface goes away and `dispose()` is owed.
 */
export interface TraceRuntime {
  readonly Tracer: new () => Tracer;
  readonly transferableFrom: (source: {
    readonly data: Uint8ClampedArray | Uint8Array;
    readonly width: number;
    readonly height: number;
  }) => TransferableImage;
  readonly defaults: TraceParams;
  readonly sanitize: (input: TraceParamsInput) => TraceParams;
  /** True for the error class that means "this device cannot trace at all", not "this image failed". */
  readonly isUnavailable: (error: unknown) => boolean;
  readonly isCancelled: (error: unknown) => boolean;
}

let runtimePromise: Promise<TraceRuntime> | null = null;

/**
 * The one sentence a designer is shown when the engine cannot be fetched.
 *
 * Copied word for word from `traceClient.loadSpawn`, deliberately: three different sentences for
 * "the tracing engine did not arrive" would read as three different faults, and there is only one.
 */
const ENGINE_UNAVAILABLE = "The tracing engine could not be loaded. Check your connection and reload the page.";

/**
 * Load the client and the parameter defaults, once per tab.
 *
 * Memoised on the PROMISE rather than on the result, so two panels mounting in the same tick share
 * one fetch instead of racing two. A rejection clears the memo, because the commonest cause is a
 * courtyard hotspot dropping a chunk request and the next attempt should be allowed to succeed.
 */
export async function loadTraceRuntime(): Promise<TraceRuntime> {
  if (runtimePromise !== null) return await runtimePromise;
  const started = (async (): Promise<TraceRuntime> => {
    // BOTH DYNAMIC IMPORTS ARE WRAPPED, AND THE SENTENCE IS THE POINT. `SketchTraceField` renders
    // whatever `message` arrives here verbatim, because the only thing it can honestly say about a
    // device that cannot trace is what the loader said. Unwrapped, a courtyard hotspot dropping one
    // chunk request put "Failed to fetch dynamically imported module: https://…/chunk-a91f2c.js" on a
    // designer's screen — true, useless, and not a sentence anybody wrote for a reader. `traceClient`
    // already owns the right words for exactly this case and reaches them for its own worker import;
    // these two paths are the ones outside its reach, so they say the same thing here.
    //
    // The RAW error is kept as `cause` for the same reason `loadSpawn` keeps it: a dropped request and
    // a chunk the build never emitted throw the same way, and only the cause tells a deployment bug
    // apart from a bad connection.
    let client: typeof import("@/lib/trace/traceClient");
    try {
      client = await import("@/lib/trace/traceClient");
    } catch (error) {
      throw new Error(ENGINE_UNAVAILABLE, { cause: error });
    }
    let defaults: TraceParams;
    let sanitize: (input: TraceParamsInput) => TraceParams;
    try {
      // `loadTraceParams` awaits `./engine/params` and does not wrap that import either, so the same
      // dropped-chunk message arrives from one layer deeper. Now that the client module itself is
      // here, its own error class carries the sentence — which keeps `isUnavailable` true for it.
      ({ defaults, sanitize } = await client.loadTraceParams());
    } catch (error) {
      throw new client.TraceUnavailableError(ENGINE_UNAVAILABLE, { cause: error });
    }
    return {
      Tracer: client.Tracer,
      transferableFrom: client.transferableFrom,
      defaults,
      sanitize,
      // Tested with `instanceof` against the classes the same module instance exported, so this
      // cannot be defeated by two copies of the module — which is the failure a `name === "..."`
      // string test hides rather than reports.
      isUnavailable: (error) => error instanceof client.TraceUnavailableError,
      isCancelled: (error) => error instanceof client.TraceCancelledError
    };
  })();
  runtimePromise = started;
  try {
    return await started;
  } catch (error) {
    if (runtimePromise === started) runtimePromise = null;
    throw error;
  }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The presets — see the header's seam note
 * ──────────────────────────────────────────────────────────────────────────── */

/** A style preset, flattened to what a picker draws. */
export interface StyleChoice {
  readonly id: string;
  readonly name: string;
  readonly description: string;
  readonly group: string;
  /** The complete parameter tree this style IS. A style replaces the settings; it does not nudge them. */
  readonly params: TraceParams;
}

/**
 * A subject preset.
 *
 * `adjust` rather than `params`, and the difference is the engine's design rather than an accident of
 * this type: `engine/subjects.ts` declares subjects as an idempotent function OVER the current tree —
 * "that 'only when NONE' is also what keeps `adjust` idempotent, which the UI relies on: it
 * re-adjusts on every edit". A subject says something about the material in front of the camera and
 * leaves the style alone; a style says what the drawing should look like.
 */
export interface SubjectChoice {
  readonly id: string;
  readonly name: string;
  readonly hint: string;
  readonly adjust: (p: TraceParams) => TraceParams;
}

export interface TracePresets {
  readonly styles: readonly StyleChoice[];
  readonly styleGroups: readonly string[];
  readonly subjects: readonly SubjectChoice[];
}

let presetsPromise: Promise<TracePresets> | null = null;

/**
 * Load the twenty style presets and the ten subject presets.
 *
 * SEPARATE FROM {@link loadTraceRuntime}, AND CALLED LATER, FOR A MEASURED REASON. `engine/styles.ts`
 * imports only `./params`, which the runtime has already fetched, so the styles are nearly free. But
 * `engine/subjects.ts` also imports `./classify` — 33,721 bytes of source (measured with `ls` on
 * 2026-08-22) — and that parses on the MAIN thread, unlike the rest of the engine, which parses
 * inside the worker where nobody is waiting for it.
 *
 * So this is not folded into the runtime load: the panel asks for it when the designer opens the
 * options, which is after they have chosen a photograph and while the worker is already warming. The
 * gzipped cost of these two chunks is NOT quoted here, because this unit did not run a production
 * build to measure it and `lib/trace/README.md` §4 quotes only figures somebody actually measured.
 * The raw source figure above is what was measured, and it is labelled as raw source.
 */
export async function loadTracePresets(): Promise<TracePresets> {
  if (presetsPromise !== null) return await presetsPromise;
  const started = (async (): Promise<TracePresets> => {
    const [styles, subjects] = await Promise.all([
      import("@/lib/trace/engine/styles"),
      import("@/lib/trace/engine/subjects")
    ]);
    return {
      styles: styles.ALL.map((preset) => ({
        id: preset.id,
        name: preset.name,
        description: preset.description,
        group: preset.group,
        params: preset.params
      })),
      styleGroups: styles.groups(),
      subjects: subjects.ALL.map((preset) => ({
        id: preset.id,
        name: preset.name,
        hint: preset.hint,
        adjust: preset.adjust
      }))
    };
  })();
  presetsPromise = started;
  try {
    return await started;
  } catch (error) {
    if (presetsPromise === started) presetsPromise = null;
    throw error;
  }
}

/* ────────────────────────────────────────────────────────────────────────────
 * Re-exported types, so a component imports this file and not the engine
 * ──────────────────────────────────────────────────────────────────────────── */

export type { SerializedTraceResult, TraceParams, TraceParamsInput, TraceProgress, Tracer, TransferableImage };

/* ────────────────────────────────────────────────────────────────────────────
 * The crop-and-sharpen worker
 *
 * A SECOND DOOR IN THIS FILE RATHER THAN A SECOND FILE, because the rule it exists to serve is the
 * same one: `SketchTraceField`'s fourth property is that this feature's heavy code is not on the
 * page's graph until a designer asks for it, and this file is where that promise is kept. A component
 * importing `@/lib/trace/imageEditClient` at the top would put the client, the worker reference and
 * `engine/contrast` + `engine/convolve` on the sketches page's bundle for every visitor who never
 * touches a photograph.
 *
 * IT IS NOT FOLDED INTO {@link loadTraceRuntime} either. The two are independent: a designer who
 * traces without cropping never fetches this chunk, and one who crops on a browser whose trace
 * worker failed still gets a useful sentence rather than one failure standing in for the other.
 * ──────────────────────────────────────────────────────────────────────────── */

/** The parts of `imageEditClient` this feature uses, resolved. */
export interface ImageEditRuntime {
  /**
   * A CONSTRUCTOR, not an instance, for `TraceRuntime.Tracer`'s reason: an editor owns a worker, a
   * worker is per-surface, and only the component knows when its surface goes away and `dispose()`
   * is owed.
   */
  readonly ImageEditor: new () => ImageEditor;
  /** True for "this device cannot crop or sharpen at all", as opposed to "this frame was refused". */
  readonly isUnavailable: (error: unknown) => boolean;
  /** True for a request the designer replaced. Never shown to anybody. */
  readonly isCancelled: (error: unknown) => boolean;
}

let editorPromise: Promise<ImageEditRuntime> | null = null;

/**
 * Load the crop-and-sharpen client, once per tab.
 *
 * Memoised on the PROMISE and cleared on rejection, exactly as {@link loadTraceRuntime} is and for the
 * same measured reason: two panels mounting in one tick share a fetch, and a courtyard hotspot that
 * dropped one chunk request must not have poisoned the feature for the rest of the session.
 */
export async function loadImageEditor(): Promise<ImageEditRuntime> {
  if (editorPromise !== null) return await editorPromise;
  const started = (async (): Promise<ImageEditRuntime> => {
    let client: typeof import("@/lib/trace/imageEditClient");
    try {
      client = await import("@/lib/trace/imageEditClient");
    } catch (error) {
      // WRAPPED, AND THE SENTENCE IS THE POINT — see `loadTraceRuntime`'s note on the same throw. An
      // unwrapped dynamic import puts "Failed to fetch dynamically imported module:
      // https://…/chunk-4b1e.js" on a designer's screen: true, useless, and not written for a reader.
      //
      // A PLAIN `Error`, DELIBERATELY, AND NOT `ImageEditUnavailableError`. That class lives in the
      // module that just failed to load, so it is not reachable from here — and reaching for it a
      // second time to build the error that says the first attempt failed would be a fiction. The
      // caller does not need the class either: this function has exactly one failure, so a rejection
      // from it IS "this device cannot crop or sharpen". `isUnavailable` is for the errors that come
      // back from an edit, where "the frame was refused" and "the tool is gone" are different things.
      throw new Error(
        "The cropping and sharpening tools could not be loaded on this device. The photograph and " +
          "the trace are unaffected — the trace's own “Sharpen amount” control still works.",
        { cause: error }
      );
    }
    return {
      ImageEditor: client.ImageEditor,
      // `instanceof` against the classes THIS module instance exported, so two copies of the module
      // cannot defeat the test — the failure a `name === "..."` string comparison hides.
      isUnavailable: (error) => error instanceof client.ImageEditUnavailableError,
      isCancelled: (error) => error instanceof client.ImageEditCancelledError
    };
  })();
  editorPromise = started;
  try {
    return await started;
  } catch (error) {
    if (editorPromise === started) editorPromise = null;
    throw error;
  }
}

export type { CropRect, EditCall, EditedPixels, ImageEditor, SharpenSettings };
