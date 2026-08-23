"use client";

/**
 * "Trace this photograph into line art" — the UPLOAD tab's tracing panel.
 *
 * WHAT IT IS FOR. A designer photographs a sketch on a courtyard table, under one tube light, with a
 * phone. What reaches the archive is a grey rectangle with a drawing somewhere inside it. The owner
 * asked for the tracing options they already built for users in D:/Offline-Tracer, and this is the
 * surface that offers them: twenty style presets, ten subject presets, every control that
 * application's dock offers, and the sharpening the upstream engine has always been able to do and
 * its own UI never exposed. All of it is arithmetic on this device.
 *
 * HOW MANY CONTROLS THAT IS, IT DOES NOT SAY — `traceParamTable.PARAM_COUNT` publishes the number and
 * the "Show all N controls" button prints it. A figure written out in prose here is a second copy
 * that goes stale the first time a slider is added, and one already did: this header claimed
 * twenty-nine while the table held thirty-two.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * THE FOUR PROPERTIES THIS PANEL EXISTS TO HOLD
 * ────────────────────────────────────────────────────────────────────────────
 *
 * 1. **IT NEVER UPLOADS ANYTHING, AND IT NEVER TOUCHES THE PHOTOGRAPH.**
 *    `SketchRectifyField.tsx` solves the neighbouring problem and its header names the safety
 *    property this copies: the derived file "goes in through the ordinary door — `attach`, the same
 *    one a camera photograph uses — so eager pre-upload, per-file retry and the offline draft store
 *    all already apply to it. Nothing here uploads anything itself." Nothing here does either. This
 *    component's whole output is one `File` handed to {@link SketchTraceFieldProps.onAttach}, which
 *    the host points at its own pending list. An extra that uploaded its own file would be a second
 *    upload path to keep working offline, in an application whose point is working offline.
 *
 * 2. **IT RUNS ON THE DEVICE, IN A WORKER, AND THERE IS NO PATH AROUND THAT.** `Pipeline.run` is
 *    straight loops over typed arrays; a 12 MP trace is seconds of solid CPU, and on the page thread
 *    that is a frozen tab — not a slow one, a frozen one. `lib/trace/traceClient.ts` refuses to offer
 *    a synchronous path at all, and this component never asks for one. A browser that will not start
 *    a module worker gets a sentence and the panel hides itself, because the honest answer to "this
 *    device cannot do it" is not a button that fails.
 *
 * 3. **THE ENGINE IS NOT IN THIS PAGE'S BUNDLE UNTIL SOMEBODY TRACES SOMETHING.** Every heavy import
 *    is inside `traceRuntime.ts`, behind `await import()`, and this file imports no engine value at
 *    all — only `traceParamTable.ts`, which is a table of numbers and strings. The rule is
 *    `.claude/skills/gsap/SKILL.md` §2, "The dynamic import is not optional", already enforced on
 *    this repository's one 70 KB library and restated in `lib/trace/README.md` §4 with measured
 *    figures. **Do not add a top-level import from `@/lib/trace/*` to this file.**
 *
 * 4. **"KEEP THE PHOTOGRAPH AS IT IS" IS A REAL ANSWER AND COSTS ONE PRESS.** A threshold is a
 *    decision to discard everything on one side of it, and an over-traced sketch has lost something —
 *    a faint construction line, a smudged tone that showed where a curve was being felt out, a note in
 *    the margin. The person who can tell whether that mattered is the designer with the actual sheet
 *    in front of them, so the trace is shown before it is attached, declining needs no explanation,
 *    and the panel writes only when a button is pressed.
 *
 *    AND DECLINING NOW FILES THE PHOTOGRAPH WHERE THIS PANEL IS THE ONLY PICKER. It used to dismiss
 *    and upload nothing while the sentence underneath said "the photograph you attached stays exactly
 *    as it is" — true on a record form with a sibling image field, false on the UPLOAD tab, which was
 *    the only place the sentence rendered. The dismiss button is now "Attach the photograph only"
 *    whenever the host passes {@link SketchTraceFieldProps.onAttachSource}, and the copy follows the
 *    host rather than asserting one.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * THE SEAM
 * ────────────────────────────────────────────────────────────────────────────
 *
 * This component is not wired into `FieldInput.tsx` — that file belongs to another unit and this one
 * may not edit it. It is written to the SAME contract its neighbour is wired by, so wiring it is one
 * line wherever that decision is made:
 *
 *     <SketchTraceField targetLabel={field.label} disabled={disabled} onAttach={attach} />
 *
 * `attach` is `MediaField`'s own render-prop, exactly as `SketchRectifyField` receives it at
 * `FieldInput.tsx:1208`. Until that line exists this panel is reachable only from the Sketches and
 * Prototypes page's UPLOAD tab, which is what it was built for.
 */

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { AlertTriangle, Check, ChevronDown, Image as ImageIcon, Loader2, Sliders, Wand2, X } from "lucide-react";

import {
  CHOICES,
  ESSENTIAL_KEYS,
  PARAM_COUNT,
  PARAM_GROUPS,
  SLIDERS,
  TOGGLES,
  applyParamPatch,
  changedAdvancedLabels,
  changedLabels,
  formatValue,
  isEssential,
  overwriteNotice,
  type ChoiceSpec,
  type SliderSpec,
  type ToggleSpec
} from "./traceParamTable";
import {
  DECODE_MAX_EDGE_PX,
  TRACEABLE_ACCEPT,
  TRACEABLE_IMAGE_TYPES,
  decodeToPixels,
  isDecoded,
  type DecodedPixels
} from "./decodeToPixels";
import { Dropdown } from "@/components/ui/Dropdown";

import { EXPORT_FORMATS, exportPngFile, exportSvgFile, isExported, paintGeometry, type ExportFormatId } from "./traceExport";
import type { SvgInput } from "./geometryToSvg";
import {
  loadTracePresets,
  loadTraceRuntime,
  type SerializedTraceResult,
  type StyleChoice,
  type SubjectChoice,
  type TraceParams,
  type TraceRuntime,
  type Tracer
} from "./traceRuntime";

/**
 * The long edge the live preview is traced at.
 *
 * The engine's own preview mode runs every stage of the same pipeline at ~720px and returns geometry
 * in the SAME document coordinates as a full run, so it is safe to tune against and safe to draw in
 * the same viewport. This constant is the CANVAS size, not the trace's — a preview larger than the box
 * it is drawn in costs memory to show nobody anything.
 */
const PREVIEW_BOX_PX = 420;

/**
 * How long the panel waits after a control moves before re-tracing.
 *
 * A slider drag is dozens of events. The worker supersedes a running trace when a newer one arrives
 * and `Tracer` settles the older promise itself, so an undebounced drag is correct — but it is also a
 * worker that never finishes anything, and on a handset it is a hot phone. 220 ms is long enough that
 * a drag produces one trace at the end and short enough that a single click feels immediate.
 */
const RETRACE_DEBOUNCE_MS = 220;

export interface SketchTraceFieldProps {
  /** The registry label of the field the line art lands in, quoted on the button so it is unambiguous. */
  targetLabel: string;
  disabled?: boolean;
  /** Hands the derived file to the host — the same door a camera photograph goes in by. */
  onAttach: (file: File) => void;
  /**
   * Offered the ORIGINAL photograph the designer chose in this panel, when the host wants it too.
   *
   * THE HOST DECIDES, AND THE PANEL CHANGES SHAPE WITH THE ANSWER. Optional, because on a record form
   * the photograph belongs in its own image field and the host's ordinary picker already put it there
   * — writing it twice from here would detach nothing but would file the same bytes under two names.
   * A host that has no other picker for the photograph passes this, and then this panel is the only
   * way the photograph is ever filed: so when it is present the panel offers "Attach the photograph
   * only" instead of a bare dismiss, and every sentence about declining says what actually happens.
   *
   * CALLED AT MOST ONCE PER CHOSEN PHOTOGRAPH. See `sourceFiledRef` below: attaching as SVG, then
   * reopening and attaching the same photograph as PNG, must not hand the host the same bytes twice.
   */
  onAttachSource?: (file: File) => void;
}

type Phase =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "ready" }
  | { status: "unavailable"; reason: string };

export function SketchTraceField({ targetLabel, disabled, onAttach, onAttachSource }: SketchTraceFieldProps) {
  const panelId = useId();
  const [open, setOpen] = useState(false);
  const [phase, setPhase] = useState<Phase>({ status: "idle" });

  const [runtime, setRuntime] = useState<TraceRuntime | null>(null);
  const [styles, setStyles] = useState<readonly StyleChoice[]>([]);
  const [styleGroups, setStyleGroups] = useState<readonly string[]>([]);
  const [subjects, setSubjects] = useState<readonly SubjectChoice[]>([]);

  const [file, setFile] = useState<File | null>(null);
  const [pixels, setPixels] = useState<DecodedPixels | null>(null);

  const [params, setParams] = useState<TraceParams | null>(null);
  /**
   * The parameters as the last preset left them.
   *
   * Kept so a row can be ringed as "you changed this" — a panel that cannot distinguish a value the
   * designer set from a value a style set cannot honestly claim either one.
   */
  const [presetParams, setPresetParams] = useState<TraceParams | null>(null);
  const [styleId, setStyleId] = useState<string>("");
  /**
   * Which subject adjustment was last applied.
   *
   * The native `<select>` this replaced was uncontrolled (`defaultValue=""`) and kept its own state,
   * which is not something a themed dropdown does — it is a `<button>` and has no value of its own.
   * Holding it here also makes the panel honest about a thing it was already doing: an applied
   * subject is a decision the designer can see on the control that made it, rather than a select
   * that happens to still be showing the last row clicked.
   */
  const [subjectId, setSubjectId] = useState<string>("");

  const [result, setResult] = useState<SerializedTraceResult | null>(null);
  const [tracing, setTracing] = useState(false);
  const [progress, setProgress] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [showAll, setShowAll] = useState(false);
  const [format, setFormat] = useState<ExportFormatId>("svg");
  const [attaching, setAttaching] = useState(false);

  const tracerRef = useRef<Tracer | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  /**
   * Whether the engine load has been STARTED, as a ref rather than as a piece of state.
   *
   * A REF BECAUSE THE GUARD MUST NOT BE A DEPENDENCY. This effect writes `phase` in its own body, so
   * guarding on `phase.status` and listing it in the dependency array made the effect cancel itself:
   * the synchronous `setPhase({loading})` re-rendered, the dependency changed, React ran the cleanup —
   * setting `cancelled` — and the `await` continuation then threw the loaded runtime away, while the
   * re-run hit `phase.status !== "idle"` and returned at once. Nothing ever set the phase back to
   * "idle" and `loadTraceRuntime` memoises its promise, so the spinner was permanent and reopening
   * the panel did not recover. A ref is read and written without telling React, which is exactly what
   * a "have I started yet" flag needs.
   */
  const startedRef = useRef(false);
  /** Set once, by the dispose effect, when this component really goes away. */
  const goneRef = useRef(false);
  /**
   * The pending preview retrace, so an attach can cancel the timer instead of losing a race to it.
   *
   * The debounce effect arms a timer on every parameter change. Pressing "Add the line art" inside
   * that window starts the full-resolution trace, and the timer then fires a PREVIEW that aborts it —
   * an abort this panel treats (correctly, everywhere else) as the normal consequence of moving a
   * slider, so the attach reported "the trace did not finish" with a finished drawing on screen.
   */
  const retraceTimerRef = useRef<number | null>(null);
  /**
   * Which file pick is the current one.
   *
   * Decoding a 4096px photograph is hundreds of milliseconds of resize and `getImageData`, and
   * re-picking after a mis-tap is an ordinary flow. Without a token the second pick's `setFile`
   * lands first and the first pick's `setPixels` lands last, so the panel traces one photograph's
   * pixels and names the output after the other one — including in the provenance sentence written
   * into the SVG. Same shape as `abortRef` on the trace side.
   */
  const pickRef = useRef(0);
  /**
   * The photograph already handed to `onAttachSource`, if any.
   *
   * `setOpen(false)` deliberately keeps `file` and `pixels` so reopening does not make the designer
   * re-pick, which means attach-as-SVG then reopen-and-attach-as-PNG would offer the host the same
   * photograph twice. Whether that duplicates a media id or replaces one is the host's business; not
   * offering it twice is this side's.
   */
  const sourceFiledRef = useRef<File | null>(null);
  /** True while `attachTrace` is running, for the debounce effect to read synchronously. */
  const attachingRef = useRef(false);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const headingRef = useRef<HTMLHeadingElement | null>(null);
  /** Whether the panel was open on the previous render, so focus is returned only on a real close. */
  const wasOpenRef = useRef(false);

  /* ──────────────────────────────────────────────────────────────────────────
   * Loading the engine — only once the panel is opened
   * ────────────────────────────────────────────────────────────────────────── */

  useEffect(() => {
    if (!open || startedRef.current) return;
    startedRef.current = true;
    /**
     * Whether the engine itself came up, as opposed to only the presets.
     *
     * A LOCAL, NOT `params`. The two failures want opposite answers — a dead engine hides the panel,
     * a missing preset list is a sentence — and the obvious test, "did `params` get set", reads the
     * value from the render that STARTED this effect, which is always null. It would therefore report
     * every preset failure as a device that cannot trace.
     */
    let runtimeUp = false;
    setPhase({ status: "loading" });
    void (async () => {
      try {
        const loaded = await loadTraceRuntime();
        if (goneRef.current) return;
        runtimeUp = true;
        setRuntime(loaded);
        setParams(loaded.defaults);
        setPresetParams(loaded.defaults);
        setPhase({ status: "ready" });
        // The presets are fetched after the runtime rather than beside it: `engine/subjects.ts` pulls
        // `engine/classify.ts` onto the MAIN thread, and there is no reason to make the designer wait
        // for it before the file picker works. A failure here costs the two preset lists and nothing
        // else, so it is caught separately and never blocks the panel.
        const presets = await loadTracePresets();
        if (goneRef.current) return;
        setStyles(presets.styles);
        setStyleGroups(presets.styleGroups);
        setSubjects(presets.subjects);
      } catch (error) {
        if (goneRef.current) return;
        if (runtimeUp) {
          // The engine is up and only the presets failed. Say so where it is true rather than
          // disabling a panel that works.
          setNotice("The style and subject presets could not be loaded, so only the individual controls are available.");
          return;
        }
        setPhase({
          status: "unavailable",
          reason:
            error instanceof Error && error.message
              ? error.message
              : "The tracing engine could not be loaded. Check your connection and reload the page."
        });
      }
    })();
    // NO CLEANUP, AND THAT IS THE FIX RATHER THAN AN OVERSIGHT. A cleanup on this effect runs whenever
    // `open` changes, so a designer who closed the panel while the chunk was still in flight would
    // abandon a load that `startedRef` will never let start again — the same permanent spinner by
    // another route. Closing the panel does not unmount this component, so the state writes above are
    // writes to a live component; the only bail-out that is real is an unmount, and `goneRef` carries
    // that from the dispose effect below.
    //
    // `open` ONLY in the dependency array. Nothing this effect writes may appear here — see
    // `startedRef`.
  }, [open]);

  /**
   * Move focus deliberately, in both directions.
   *
   * OPENING UNMOUNTS THE TRIGGER AND CLOSING UNMOUNTS THE CLOSE BUTTON, so without this the focused
   * element simply disappears and focus falls to `<body>`: a keyboard user loses their place mid-page
   * and a screen-reader user is told nothing happened at all. Neither direction is a modal, so this is
   * not `MediaLightbox`'s focus TRAP — Tab must still be free to leave an inline panel. It is the
   * other two thirds of what that dialog's header calls the purpose of focus management: move focus
   * INTO the thing that just appeared, and hand it back to what opened it.
   *
   * The heading rather than the file input, for `MediaLightbox`'s stated reason — the heading carries
   * the panel's name, so a reader hears "Trace a sketch into line art, heading" instead of a bare
   * "Photograph to trace, file". `wasOpenRef` is what keeps the close half from firing on first mount,
   * when nothing was ever opened and the page's own focus is not this component's to take.
   */
  useEffect(() => {
    if (open) {
      wasOpenRef.current = true;
      headingRef.current?.focus();
      return;
    }
    if (!wasOpenRef.current) return;
    wasOpenRef.current = false;
    triggerRef.current?.focus();
  }, [open]);

  /** A worker outlives the component that forgot it. */
  useEffect(() => {
    return () => {
      goneRef.current = true;
      if (retraceTimerRef.current !== null) window.clearTimeout(retraceTimerRef.current);
      abortRef.current?.abort();
      tracerRef.current?.dispose();
      tracerRef.current = null;
    };
  }, []);

  /* ──────────────────────────────────────────────────────────────────────────
   * Choosing a photograph
   * ────────────────────────────────────────────────────────────────────────── */

  /**
   * Read a chosen photograph into pixels, and never let an older read finish last.
   *
   * THE TOKEN IS THE WHOLE POINT. `decodeToPixels` resizes and calls `getImageData`, which on a
   * handset is hundreds of milliseconds for a 4096px photograph — and picking again after a mis-tap is
   * an ordinary flow, not an edge case. Without the token, picking A then B leaves `file` at B and
   * `pixels` at A's, because A's decode resolves last; the panel then traces A, attaches the drawing
   * under B's name, and writes "Traced on the device from B.jpg" into a file traced from A. Nothing on
   * screen would distinguish the two. Same shape as `abortRef` on the trace side.
   */
  const chooseFile = useCallback(async (chosen: File) => {
    pickRef.current += 1;
    const pick = pickRef.current;
    setProblem(null);
    setDone(null);
    setResult(null);
    setFile(chosen);
    setPixels(null);
    // A new photograph is a new thing to file, whatever was filed for the old one.
    sourceFiledRef.current = null;
    const outcome = await decodeToPixels(chosen);
    if (pick !== pickRef.current || goneRef.current) return;
    if (!isDecoded(outcome)) {
      setPixels(null);
      setProblem(outcome.reason);
      return;
    }
    setPixels(outcome);
  }, []);

  /* ──────────────────────────────────────────────────────────────────────────
   * Tracing
   * ────────────────────────────────────────────────────────────────────────── */

  /**
   * Run one trace and hand the answer back.
   *
   * IT RETURNS THE RESULT AS WELL AS STORING IT, and that is not redundancy. `attachTrace` awaits a
   * full-resolution re-trace and then needs its answer immediately — but `setResult` schedules a
   * render, so reading `result` (or a ref an effect updates) straight after the await reads the
   * PREVIEW, and the file attached would be coarser than the one the designer approved with nothing
   * on screen showing the difference. The return value is the only synchronously correct answer.
   */
  const runTrace = useCallback(
    async (preview: boolean): Promise<SerializedTraceResult | null> => {
      if (runtime === null || pixels === null || params === null) return null;
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;

      if (tracerRef.current === null) tracerRef.current = new runtime.Tracer();
      const tracer = tracerRef.current;

      setTracing(true);
      setProblem(null);
      setProgress(null);
      try {
        const answer = await tracer.trace({
          // A fresh clone every time. The buffer is TRANSFERRED, so the caller's typed array is
          // detached once it has been posted — upstream learned this the hard way and recorded the
          // symptom: transferring the original made "the second trace produces a blank image", which
          // surfaces as a rendering bug rather than as an error.
          image: runtime.transferableFrom(pixels),
          params,
          preview,
          signal: controller.signal,
          onProgress: (p) => setProgress(`${p.label}…`)
        });
        setResult(answer);
        return answer;
      } catch (error) {
        // A superseded or aborted trace is the normal consequence of moving a slider, not a failure,
        // and reporting it would fill the panel with sentences about work the designer replaced.
        if (runtime.isCancelled(error)) return null;
        if (runtime.isUnavailable(error)) {
          setPhase({ status: "unavailable", reason: (error as Error).message });
          return null;
        }
        setProblem(
          error instanceof Error && error.message
            ? error.message
            : "That photograph could not be traced. Try another, or a lower trace resolution."
        );
        return null;
      } finally {
        if (abortRef.current === controller) {
          setTracing(false);
          setProgress(null);
        }
      }
    },
    [runtime, pixels, params]
  );

  /**
   * Re-preview whenever the pixels or the parameters change. Debounced — see RETRACE_DEBOUNCE_MS.
   *
   * THE TIMER IS KEPT IN A REF AS WELL AS IN THE CLEANUP, because an attach has to be able to cancel
   * it from outside this effect. A parameter changed within 220 ms of pressing "Add the line art"
   * leaves a preview armed; the preview then aborts the full-resolution trace the attach is awaiting,
   * that abort is (rightly) treated as the ordinary consequence of moving a slider, and the attach
   * reported "the trace did not finish" with a finished drawing on screen. `attachTrace` clears this
   * timer before it starts, and `attachingRef` keeps the effect from arming a new one behind it.
   */
  useEffect(() => {
    if (runtime === null || pixels === null || params === null) return;
    if (attachingRef.current) return;
    const timer = window.setTimeout(() => {
      retraceTimerRef.current = null;
      void runTrace(true);
    }, RETRACE_DEBOUNCE_MS);
    retraceTimerRef.current = timer;
    return () => {
      window.clearTimeout(timer);
      if (retraceTimerRef.current === timer) retraceTimerRef.current = null;
    };
  }, [runtime, pixels, params, runTrace]);

  /* ──────────────────────────────────────────────────────────────────────────
   * Drawing the answer
   * ────────────────────────────────────────────────────────────────────────── */

  const svgInput: SvgInput | null = useMemo(() => {
    if (result === null) return null;
    return {
      geometry: result.geometry,
      width: result.width,
      height: result.height,
      background: result.background
    };
  }, [result]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (canvas === null || svgInput === null) return;
    const scale = Math.min(1, PREVIEW_BOX_PX / Math.max(svgInput.width, svgInput.height));
    canvas.width = Math.max(1, Math.round(svgInput.width * scale));
    canvas.height = Math.max(1, Math.round(svgInput.height * scale));
    const context = canvas.getContext("2d");
    if (context === null) return;
    context.clearRect(0, 0, canvas.width, canvas.height);
    // The SAME painter the PNG export uses. A preview drawn by different code from the file that gets
    // attached is a preview that can lie — see `paintGeometry`'s header.
    paintGeometry(context, svgInput, scale);
  }, [svgInput]);

  /* ──────────────────────────────────────────────────────────────────────────
   * Presets and controls
   * ────────────────────────────────────────────────────────────────────────── */

  const setParamsFrom = useCallback(
    (next: TraceParams, source: string | null, base: TraceParams | null) => {
      if (params !== null && source !== null) setNotice(overwriteNotice(source, params, next));
      setParams(next);
      if (base !== null) setPresetParams(base);
    },
    [params]
  );

  const pickStyle = useCallback(
    (id: string) => {
      const preset = styles.find((s) => s.id === id);
      if (!preset) return;
      setStyleId(preset.id);
      // A style REPLACES the settings — it is a complete tree, not a nudge — so it becomes both the
      // live parameters and the baseline the "you changed this" rings are measured against.
      setParamsFrom(preset.params, `The “${preset.name}” style`, preset.params);
    },
    [styles, setParamsFrom]
  );

  const applySubject = useCallback(
    (id: string) => {
      const preset = subjects.find((s) => s.id === id);
      if (!preset || params === null) return;
      // A subject leaves the style alone by design and is idempotent, so the baseline is untouched:
      // it says something about the material in front of the camera, not about the drawing wanted.
      setParamsFrom(preset.adjust(params), `The “${preset.name}” adjustment`, null);
    },
    [subjects, params, setParamsFrom]
  );

  const patchParams = useCallback(
    (patch: Parameters<typeof applyParamPatch>[1]) => {
      if (runtime === null || params === null) return;
      setNotice(null);
      setParams(applyParamPatch(params, patch, runtime.sanitize));
    },
    [runtime, params]
  );

  const modifiedLabels = useMemo(
    () => (params && presetParams ? changedLabels(presetParams, params) : []),
    [params, presetParams]
  );
  const hiddenModified = useMemo(
    () => (params && presetParams ? changedAdvancedLabels(presetParams, params) : []),
    [params, presetParams]
  );
  const modifiedSet = useMemo(() => new Set(modifiedLabels), [modifiedLabels]);

  /* ──────────────────────────────────────────────────────────────────────────
   * Attaching
   * ────────────────────────────────────────────────────────────────────────── */

  async function attachTrace() {
    if (svgInput === null || file === null || runtime === null || params === null) return;
    // Disarm the pending preview FIRST. See the debounce effect: a preview that fires mid-attach
    // aborts the full-resolution trace this function is about to await, and the abort reads as
    // "nothing finished" while a finished drawing is on screen.
    if (retraceTimerRef.current !== null) {
      window.clearTimeout(retraceTimerRef.current);
      retraceTimerRef.current = null;
    }
    attachingRef.current = true;
    setAttaching(true);
    setProblem(null);
    try {
      // FULL RESOLUTION, ONCE, ON THE BUTTON — never on a drag. Everything on screen until now was a
      // preview at a smaller working edge, and `SerializedTraceResult.workingWidth` is how the panel
      // knows: attaching the preview would file a drawing coarser than the one being approved.
      const latest = await runTrace(false);
      if (latest === null) {
        setProblem("The trace did not finish, so there is nothing to attach yet.");
        return;
      }
      const input: SvgInput = {
        geometry: latest.geometry,
        width: latest.width,
        height: latest.height,
        background: latest.background
      };
      const note =
        `Traced on the device from ${file.name} by the Design & Prototype Workshop portal. ` +
        `${latest.shapeCount} paths, ${latest.nodeCount} nodes.`;
      const outcome =
        format === "svg"
          ? exportSvgFile(input, file.name, note)
          : await exportPngFile(input, file.name);
      if (!isExported(outcome)) {
        setProblem(outcome.reason);
        return;
      }
      onAttach(outcome.file);
      const source = fileSourceOnce(file);
      setDone(
        `${outcome.file.name} was added to “${targetLabel}”.` +
          (source === "filed"
            ? ` The photograph ${file.name} was filed alongside it, exactly as it is.`
            : source === "already"
              ? ` The photograph ${file.name} was already filed and is untouched.`
              : " The photograph itself is untouched.") +
          (outcome.note ? ` ${outcome.note}` : "")
      );
      setOpen(false);
    } catch (error) {
      setProblem(
        error instanceof Error ? `The line art could not be made: ${error.message}` : "The line art could not be made."
      );
    } finally {
      attachingRef.current = false;
      setAttaching(false);
    }
  }

  /**
   * Hand the photograph to the host, at most once per chosen photograph.
   *
   * WHY ONCE MATTERS. `setOpen(false)` deliberately keeps `file` and `pixels`, so a designer can
   * reopen the panel, switch the format and attach again without re-picking anything. Every one of
   * those attaches used to call `onAttachSource`, which offered the host the same photograph a second
   * time; whether that duplicates a media id or replaces one is the host's business, and not offering
   * it twice is this side's. The ref is reset in `chooseFile`, because a new photograph is a new thing
   * to file.
   *
   * Returns which of the three things happened, so the sentence the designer reads is the true one.
   */
  function fileSourceOnce(chosen: File): "filed" | "already" | "not-wanted" {
    if (!onAttachSource) return "not-wanted";
    if (sourceFiledRef.current === chosen) return "already";
    sourceFiledRef.current = chosen;
    onAttachSource(chosen);
    return "filed";
  }

  /**
   * File the photograph and nothing else.
   *
   * THE BUTTON THIS BACKS EXISTS BECAUSE DECLINING THE TRACE USED TO UPLOAD NOTHING AT ALL. On a host
   * that has its own picker for the photograph, "keep the photograph only" is a true sentence: the
   * photograph is already attached and dismissing the panel changes nothing. On the UPLOAD tab this
   * panel is the ONLY picker, so the same press filed nothing while the copy underneath asserted an
   * attachment that had never happened. Where the host offers `onAttachSource`, declining the trace
   * therefore has to be able to file the photograph on its own — the owner's brief for the tab is
   * "upload image files of various kinds", and the photograph of the sheet is the first of them.
   */
  function attachSourceOnly() {
    if (file === null || !onAttachSource) return;
    const source = fileSourceOnce(file);
    setProblem(null);
    setDone(
      source === "filed"
        ? `${file.name} was filed exactly as it is. No line art was added.`
        : `${file.name} was already filed. No line art was added.`
    );
    setOpen(false);
  }

  /* ──────────────────────────────────────────────────────────────────────────
   * Render
   * ────────────────────────────────────────────────────────────────────────── */

  if (phase.status === "unavailable") {
    // "This device cannot do it at all" wants the control gone, not a button that fails. The ordinary
    // file picker underneath is untouched and the photograph can still be attached as it is.
    return (
      <div
        role="alert"
        className="mt-2 flex items-start gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs text-ink-500"
      >
        <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
        <span>{phase.reason}</span>
      </div>
    );
  }

  const trigger = (
    <button
      type="button"
      ref={triggerRef}
      className="field-button-secondary"
      onClick={() => setOpen(true)}
      disabled={disabled}
      aria-expanded={false}
      aria-controls={panelId}
    >
      <Wand2 className="h-4 w-4" aria-hidden />
      Trace a sketch into line art
    </button>
  );

  const panel = (
    <div id={panelId} className="rounded-lg border border-line-200 bg-surface-50 p-3">
      <div className="mb-3 flex items-start justify-between gap-3">
        <div>
          {/* `tabIndex={-1}` makes the heading focusable by script and not by Tab, which is what a
              deliberate focus move needs and what a tab stop on a heading would get wrong. */}
          <h4
            ref={headingRef}
            tabIndex={-1}
            className="font-display text-sm font-semibold text-ink-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-purple-600/40"
          >
            Trace a sketch into line art
          </h4>
          <p className="mt-1 max-w-prose text-xs leading-5 text-ink-500">
            Everything below is computed on this device — the photograph is not sent anywhere to be traced. The
            original stays exactly as it is; only the drawing is added to “{targetLabel}”.
          </p>
        </div>
        <button
          type="button"
          className="rounded-md p-1 text-ink-500 transition hover:bg-field-100 hover:text-ink-900"
          onClick={() => setOpen(false)}
          aria-label="Close the tracing panel"
        >
          <X className="h-4 w-4" aria-hidden />
        </button>
      </div>

      {phase.status === "loading" ? (
        <p aria-live="polite" className="flex items-center gap-2 py-6 text-sm text-ink-500">
          <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
          Loading the tracing engine…
        </p>
      ) : null}

      {phase.status === "ready" ? (
        <>
          {/* ── The photograph ─────────────────────────────────────────────── */}
          <div className="mb-3">
            <label className="field-label" htmlFor={`${panelId}-file`}>
              Photograph to trace
            </label>
            <input
              id={`${panelId}-file`}
              type="file"
              accept={TRACEABLE_ACCEPT}
              className="field-input mt-1 file:mr-3 file:rounded-md file:border-0 file:bg-purple-700 file:px-3 file:py-1.5 file:text-xs file:font-medium file:text-white"
              disabled={disabled}
              onChange={(event) => {
                const chosen = event.target.files?.[0];
                if (chosen) void chooseFile(chosen);
              }}
            />
            <p className="mt-1 text-xs text-ink-500">
              {TRACEABLE_IMAGE_TYPES}, wherever this browser can read them. Anything longer than{" "}
              {DECODE_MAX_EDGE_PX}px on its long edge is reduced to that before tracing — the trace was never
              going to run above it.
            </p>
            {pixels && (pixels.sourceWidth !== pixels.width || pixels.sourceHeight !== pixels.height) ? (
              <p className="mt-1 text-xs text-ink-500">
                Read at {pixels.width}x{pixels.height}, reduced from {pixels.sourceWidth}x{pixels.sourceHeight}.
              </p>
            ) : null}
          </div>

          {/* ── The preview ────────────────────────────────────────────────── */}
          {pixels ? (
            <div className="mb-3 rounded-md border border-line-200 bg-card p-3">
              <div className="flex items-center justify-between gap-2">
                <span className="field-label">Traced result</span>
                {/* Rendered whether or not a trace is running — see the wrapper's note at the bottom of
                    this component: a live region that appears with its text already in it is announced
                    by nothing. Empty while idle, so it costs a reader silence rather than "blank". */}
                <span aria-live="polite" className="flex items-center gap-1.5 text-xs text-ink-500">
                  {tracing ? (
                    <>
                      <Loader2 className="h-3 w-3 animate-spin" aria-hidden />
                      {progress ?? "Tracing…"}
                    </>
                  ) : null}
                </span>
              </div>
              <div className="mt-2 grid place-items-center rounded-md bg-field-100 p-2">
                <canvas ref={canvasRef} className="max-h-[420px] max-w-full" aria-label="The traced drawing" />
              </div>
              {result ? (
                <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-xs text-ink-500 sm:grid-cols-4">
                  <div>
                    <dt className="inline">Paths </dt>
                    <dd className="inline font-medium text-ink-900">{result.shapeCount.toLocaleString("en-IN")}</dd>
                  </div>
                  <div>
                    <dt className="inline">Nodes </dt>
                    <dd className="inline font-medium text-ink-900">{result.nodeCount.toLocaleString("en-IN")}</dd>
                  </div>
                  <div>
                    <dt className="inline">Took </dt>
                    <dd className="inline font-medium text-ink-900">{Math.round(result.totalMillis)} ms</dd>
                  </div>
                  <div>
                    <dt className="inline">Run at </dt>
                    <dd className="inline font-medium text-ink-900">
                      {result.workingWidth}x{result.workingHeight}
                    </dd>
                  </div>
                </dl>
              ) : null}
              {/* Every sentence the engine produced, rendered without exception. The engine says how much
                  of the frame a matte removed and how many specks it dropped, and those are exactly the
                  facts a designer needs in order to disbelieve a result that looks clean. */}
              {result && result.notes.length > 0 ? (
                <ul className="mt-2 space-y-1 text-xs text-ink-500">
                  {result.notes.map((note) => (
                    <li key={note}>· {note}</li>
                  ))}
                </ul>
              ) : null}
              {result && result.autoSubjectId ? (
                <p className="mt-2 text-xs text-ink-500">
                  The engine applied the “{result.autoSubjectId}” subject adjustment on its own.
                </p>
              ) : null}
              {result && result.workingWidth < result.width ? (
                <p className="mt-2 text-xs text-ink-500">
                  This is a preview at a smaller working size. Attaching re-traces at full resolution first.
                </p>
              ) : null}
            </div>
          ) : null}

          {/* ── Presets ────────────────────────────────────────────────────── */}
          {styles.length > 0 ? (
            <div className="mb-3 grid gap-3 sm:grid-cols-2">
              <div className="grid gap-1">
                {/*
                  ── WHY THIS IS THE THEMED DROPDOWN NOW, AND WHAT THE `<optgroup>` WAS TRADED FOR ──
                  Twenty style presets in a native `<select>` was the longest list in this
                  application with no way to type into it. The owner's rule is that a list you hunt
                  through gets a filter box, and `SEARCH_THRESHOLD` is eight; this list is twenty and
                  fixed at twenty, so it is the clearest case there is.

                  `SelectOption` carries no group field, so the grouping could not come across as
                  `<optgroup>` markup. It comes across in the LABEL instead — "Line · Ink line" —
                  and that is better here rather than merely equivalent: an `<optgroup>` heading is
                  chrome a reader can only scroll to, while a group name inside the label is
                  something they can TYPE. Filtering on "line" now returns that whole family, which
                  the native control could not do at all. `filterOptions` treats "·" as a word
                  boundary (it is in the separator class), so the style name still ranks as a
                  word-prefix match and Enter takes the obvious one.

                  Group ORDER is `styleGroups`, exactly as the `<optgroup>`s were emitted, so the
                  list reads down the page in the order the engine declares — the filter re-ranks
                  only while a query is being typed.

                  A `<span className="field-label">` beside the control rather than a
                  `<label htmlFor>`: `Dropdown` renders a button and takes no id, so a `for` would
                  name an element that does not exist. Same arrangement as the workshop chooser on
                  /sketches-and-prototypes and the design-workshop viewers panel.
                */}
                <span className="field-label" id={`${panelId}-style-label`}>
                  Style
                </span>
                <Dropdown
                  value={styleId}
                  onChange={pickStyle}
                  disabled={disabled}
                  ariaLabel="Style"
                  describedBy={`${panelId}-style-hint`}
                  searchable
                  options={[
                    { value: "", label: "Engine defaults" },
                    ...styleGroups.flatMap((group) =>
                      styles
                        .filter((style) => style.group === group)
                        .map((style) => ({ value: style.id, label: `${group} · ${style.name}` }))
                    )
                  ]}
                />
                <p id={`${panelId}-style-hint`} className="text-xs text-ink-500">
                  {styles.find((s) => s.id === styleId)?.description ??
                    "A style sets every control at once. Pick one, then adjust."}
                </p>
              </div>

              <div className="grid gap-1">
                {/*
                  Ten subject presets, flat, and until now a native `<select>` with nothing to type
                  into and no comment defending it — a straight miss rather than a decision. Ten is
                  over the eight-option threshold, so this list would have grown a filter box for
                  free had it ever been a `Dropdown`.

                  `searchable` explicitly all the same: the count is the engine's, not this panel's,
                  and a preset list that gains and loses its filter box as the engine's table is
                  edited is the behaviour the rule in `SearchableSelectProps` exists to stop.

                  The value is held in React state now — see `subjectId`. Re-picking the same
                  subject re-applies it, which is safe by construction: `engine/subjects.ts` declares
                  `adjust` as idempotent over the current tree, which is the property the hint below
                  promises the designer in words.
                */}
                <span className="field-label" id={`${panelId}-subject-label`}>
                  Subject
                </span>
                <Dropdown
                  value={subjectId}
                  onChange={(next) => {
                    setSubjectId(next);
                    if (next) applySubject(next);
                  }}
                  disabled={disabled || subjects.length === 0}
                  ariaLabel="Subject"
                  describedBy={`${panelId}-subject-hint`}
                  // No emptyLabel: the list always carries the "What is in the photograph?" row, so
                  // the empty state is unreachable here and a sentence that cannot render is a
                  // sentence the next reader has to disprove. The panel says so at the top instead,
                  // in the notice that fires when the preset fetch fails.
                  placeholder="What is in the photograph?"
                  searchable
                  options={[
                    { value: "", label: "What is in the photograph?" },
                    ...subjects.map((subject) => ({ value: subject.id, label: subject.name }))
                  ]}
                />
                <p id={`${panelId}-subject-hint`} className="text-xs text-ink-500">
                  A subject nudges the settings for the material in front of the camera. It leaves the style alone
                  and can be applied more than once without compounding.
                </p>
              </div>
            </div>
          ) : null}

          {notice ? (
            <p role="alert" className="mb-3 rounded-md border border-line-200 bg-amber-100 px-3 py-2 text-xs text-amber-800">
              {notice}
            </p>
          ) : null}

          {/* ── Controls ───────────────────────────────────────────────────── */}
          {params ? (
            <div className="mb-3">
              <div className="mb-2 flex items-center justify-between gap-2">
                <span className="field-label">Controls</span>
                <button
                  type="button"
                  className="inline-flex items-center gap-1 text-xs font-medium text-purple-700 transition hover:text-purple-800"
                  onClick={() => setShowAll((value) => !value)}
                  aria-expanded={showAll}
                >
                  <Sliders className="h-3.5 w-3.5" aria-hidden />
                  {showAll ? "Show the essentials" : `Show all ${PARAM_COUNT} controls`}
                  <ChevronDown className={showAll ? "h-3.5 w-3.5 rotate-180" : "h-3.5 w-3.5"} aria-hidden />
                </button>
              </div>

              {/* Progressive disclosure is only honest if what it hides can still announce itself. */}
              {!showAll && hiddenModified.length > 0 ? (
                <p className="mb-2 text-xs text-ink-500">
                  {hiddenModified.length === 1
                    ? `One hidden control differs from the style: ${hiddenModified[0]}.`
                    : `${hiddenModified.length} hidden controls differ from the style: ${hiddenModified.join(", ")}.`}
                </p>
              ) : null}

              <div className="grid gap-4">
                {PARAM_GROUPS.map((group) => {
                  const sliders = SLIDERS.filter((s) => s.group === group && (showAll || isEssential(s.key)));
                  const toggles = TOGGLES.filter((t) => t.group === group && (showAll || isEssential(t.key)));
                  const choices = CHOICES.filter((c) => c.group === group && (showAll || isEssential(c.key)));
                  if (sliders.length + toggles.length + choices.length === 0) return null;
                  return (
                    <fieldset key={group} className="rounded-md border border-line-200 bg-card p-3">
                      <legend className="field-label px-1">{group}</legend>
                      <div className="grid gap-3">
                        {choices.map((spec) => (
                          <ChoiceRow
                            key={spec.key}
                            spec={spec}
                            params={params}
                            disabled={disabled}
                            modified={modifiedSet.has(spec.label)}
                            onPatch={patchParams}
                            idPrefix={panelId}
                          />
                        ))}
                        {sliders.map((spec) => (
                          <SliderRow
                            key={spec.key}
                            spec={spec}
                            params={params}
                            disabled={disabled}
                            modified={modifiedSet.has(spec.label)}
                            onPatch={patchParams}
                            idPrefix={panelId}
                          />
                        ))}
                        {toggles.map((spec) => (
                          <ToggleRow
                            key={spec.key}
                            spec={spec}
                            params={params}
                            disabled={disabled}
                            modified={modifiedSet.has(spec.label)}
                            onPatch={patchParams}
                            idPrefix={panelId}
                          />
                        ))}
                      </div>
                    </fieldset>
                  );
                })}
              </div>
            </div>
          ) : null}

          {/* ── Format and attach ──────────────────────────────────────────── */}
          <div className="grid gap-1">
            <span className="field-label">Attach as</span>
            <div className="flex flex-wrap gap-2">
              {EXPORT_FORMATS.map((entry) => (
                <button
                  key={entry.id}
                  type="button"
                  className={
                    format === entry.id
                      ? "rounded-md border border-purple-600 bg-purple-50 px-3 py-1.5 text-xs font-medium text-purple-800"
                      : "rounded-md border border-line-200 bg-card px-3 py-1.5 text-xs font-medium text-ink-700 transition hover:border-purple-300"
                  }
                  onClick={() => setFormat(entry.id)}
                  aria-pressed={format === entry.id}
                  disabled={disabled}
                >
                  {entry.label}
                </button>
              ))}
            </div>
            <p className="text-xs text-ink-500">{EXPORT_FORMATS.find((e) => e.id === format)?.hint}</p>
          </div>

          {problem ? (
            <p
              role="alert"
              className="mt-3 flex items-start gap-2 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-xs text-error-600"
            >
              <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
              <span>{problem}</span>
            </p>
          ) : null}

          <div className="mt-3 flex flex-wrap items-center gap-2">
            <button
              type="button"
              className="field-button"
              onClick={() => void attachTrace()}
              disabled={disabled || attaching || result === null || file === null}
            >
              {attaching ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <ImageIcon className="h-4 w-4" aria-hidden />}
              Add the line art to “{targetLabel}”
            </button>
            {/* DECLINING THE TRACE HAS TO FILE THE PHOTOGRAPH WHERE THIS PANEL IS THE ONLY PICKER.
                See `attachSourceOnly`: the two hosts are genuinely different, so the control is too —
                a host with its own image field is told the truth ("keep the photograph only", which
                dismisses and changes nothing), and a host that hands this panel `onAttachSource` gets
                a button that actually files it. The old copy said the first thing on both. */}
            {onAttachSource ? (
              <button
                type="button"
                className="field-button-secondary"
                onClick={attachSourceOnly}
                disabled={disabled || attaching || file === null}
              >
                <ImageIcon className="h-4 w-4" aria-hidden />
                Attach the photograph only
              </button>
            ) : (
              <button type="button" className="field-button-secondary" onClick={() => setOpen(false)} disabled={attaching}>
                Keep the photograph only
              </button>
            )}
          </div>
          <p className="mt-2 text-xs text-ink-500">
            {onAttachSource
              ? "Nothing is filed until one of these is pressed. “Attach the photograph only” files the photograph exactly as it was taken and stops there; adding the line art files both, the photograph unaltered beside the drawing. Closing this panel with the × files neither."
              : "Declining costs nothing: the photograph you attached stays exactly as it is, and a drawing can be traced from it later."}
          </p>
        </>
      ) : null}
    </div>
  );

  /**
   * ONE WRAPPER AROUND BOTH STATES, AND THE LIVE REGION LIVES ON IT.
   *
   * The success sentence is set as the panel closes, which is the moment the whole panel subtree is
   * replaced by the trigger button. A live region mounted in that same commit is new DOM that already
   * carries text, and a reader announces a live region's CHANGES rather than its arrival — so the
   * sentence would be silent exactly when it matters. Keeping the region outside the open/closed
   * switch means it is in the document from the first render and only its contents ever change.
   */
  return (
    <div className="mt-2">
      {open ? panel : trigger}
      <div aria-live="polite" aria-atomic="true">
        {done ? (
          <p className="mt-2 flex items-start gap-2 text-xs text-ink-500">
            <Check className="mt-0.5 h-3.5 w-3.5 shrink-0 text-success-600" aria-hidden />
            <span>{done}</span>
          </p>
        ) : null}
      </div>
    </div>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * Rows
 *
 * One component per control KIND rather than one hand-written block per control, for the reason
 * upstream's own table gives: every control has to read its value, write a change, say whether it
 * differs from the preset and name itself to a screen reader, and a block per control is a chance per
 * control to forget the last two. (How many that would be is `PARAM_COUNT`, not a figure typed here.)
 * ──────────────────────────────────────────────────────────────────────────── */

type PatchFn = (patch: Parameters<typeof applyParamPatch>[1]) => void;

/** The ring that says "you changed this". A ring and not only a colour — colour never carries meaning alone. */
const MODIFIED_RING = "rounded-md ring-2 ring-purple-600/15";

function SliderRow({
  spec,
  params,
  disabled,
  modified,
  onPatch,
  idPrefix
}: {
  spec: SliderSpec;
  params: TraceParams;
  disabled?: boolean;
  modified: boolean;
  onPatch: PatchFn;
  idPrefix: string;
}) {
  const id = `${idPrefix}-${spec.key}`;
  const value = spec.read(params);
  return (
    <div className={modified ? `${MODIFIED_RING} p-1` : "p-1"}>
      <div className="flex items-baseline justify-between gap-2">
        <label className="text-xs font-medium text-ink-900" htmlFor={id}>
          {spec.label}
          {modified ? <span className="ml-1 text-purple-700">·</span> : null}
        </label>
        <output className="text-xs tabular-nums text-ink-500" htmlFor={id}>
          {formatValue(value, spec.step)}
        </output>
      </div>
      <input
        id={id}
        type="range"
        className="mt-1 w-full accent-purple-700"
        min={spec.min}
        max={spec.max}
        step={spec.step}
        value={value}
        disabled={disabled}
        aria-describedby={`${id}-hint`}
        onChange={(event) => onPatch(spec.patch(Number(event.target.value)))}
      />
      <p id={`${id}-hint`} className="mt-0.5 text-xs leading-4 text-ink-500">
        {spec.hint}
      </p>
    </div>
  );
}

function ToggleRow({
  spec,
  params,
  disabled,
  modified,
  onPatch,
  idPrefix
}: {
  spec: ToggleSpec;
  params: TraceParams;
  disabled?: boolean;
  modified: boolean;
  onPatch: PatchFn;
  idPrefix: string;
}) {
  const id = `${idPrefix}-${spec.key}`;
  const value = spec.read(params);
  return (
    <div className={modified ? `${MODIFIED_RING} p-1` : "p-1"}>
      <div className="flex items-start gap-2">
        <input
          id={id}
          type="checkbox"
          className="mt-0.5 h-4 w-4 shrink-0 accent-purple-700"
          checked={value}
          disabled={disabled}
          aria-describedby={`${id}-hint`}
          onChange={(event) => onPatch(spec.patch(event.target.checked))}
        />
        <div className="min-w-0">
          <label className="text-xs font-medium text-ink-900" htmlFor={id}>
            {spec.label}
            {modified ? <span className="ml-1 text-purple-700">·</span> : null}
          </label>
          <p id={`${id}-hint`} className="text-xs leading-4 text-ink-500">
            {spec.hint}
          </p>
        </div>
      </div>
    </div>
  );
}

function ChoiceRow({
  spec,
  params,
  disabled,
  modified,
  onPatch,
  idPrefix
}: {
  spec: ChoiceSpec;
  params: TraceParams;
  disabled?: boolean;
  modified: boolean;
  onPatch: PatchFn;
  idPrefix: string;
}) {
  const id = `${idPrefix}-${spec.key}`;
  const value = spec.read(params);
  return (
    <div className={modified ? `${MODIFIED_RING} p-1` : "p-1"}>
      {/*
        ── DELIBERATELY A NATIVE <select>, UNLIKE THE STYLE AND SUBJECT PICKERS ABOVE ──
        Two reasons, and neither is inertia. First, every one of these lists is a per-parameter enum
        of two to four values declared in `traceParamTable` — "Light / Dark", "Off / Low / High" —
        which is exactly the fixed vocabulary a filter box makes worse: an extra tab stop and a "No
        matches" state over a list read at a glance.

        Second, and this is the part that would not be recoverable: a `<label htmlFor>` and an
        `aria-describedby` are wired to this control by id, and the themed dropdown renders a
        `<button>` and accepts no id or ref (`e2e/process-refusal-a11y-unit.spec.ts` records that
        gap). Converting would trade a correctly named and described field for a filter box nobody
        needs, on a panel that renders up to `PARAM_COUNT` of these at once.
      */}
      <label className="text-xs font-medium text-ink-900" htmlFor={id}>
        {spec.label}
        {modified ? <span className="ml-1 text-purple-700">·</span> : null}
      </label>
      <select
        id={id}
        className="field-input mt-1"
        value={value}
        disabled={disabled}
        aria-describedby={`${id}-hint`}
        onChange={(event) => onPatch(spec.patch(event.target.value))}
      >
        {spec.options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      <p id={`${id}-hint`} className="mt-0.5 text-xs leading-4 text-ink-500">
        {spec.hint}
      </p>
    </div>
  );
}

/** Re-exported so a host can name the essentials without importing the table. */
export { ESSENTIAL_KEYS };
