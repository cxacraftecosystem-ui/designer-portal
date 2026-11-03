import type { TraceParams, TraceParamsInput } from "@/lib/trace/traceClient";

type EdgeEngineValue = TraceParams["edge"]["engine"];
type DenoiseModeValue = TraceParams["preprocess"]["denoise"];
type MatteModeValue = TraceParams["matte"]["mode"];
type VectorModeValue = TraceParams["output"]["vectorMode"];
type ThinningModeValue = TraceParams["cleanup"]["thinning"];

export const GROUP_SOURCE = "Source";
export const GROUP_SHARPEN = "Sharpening";
export const GROUP_EDGES = "Edges";
export const GROUP_CLEANUP = "Cleanup";
export const GROUP_OUTPUT = "Output";

export const PARAM_GROUPS: readonly string[] = [
  GROUP_SOURCE,
  GROUP_SHARPEN,
  GROUP_EDGES,
  GROUP_CLEANUP,
  GROUP_OUTPUT
];

export interface SliderSpec {
  readonly key: string;
  readonly label: string;
  readonly hint: string;
  readonly group: string;
  readonly min: number;
  readonly max: number;
  readonly step: number;
  readonly read: (p: TraceParams) => number;
  readonly patch: (v: number) => TraceParamsInput;
}

export interface ToggleSpec {
  readonly key: string;
  readonly label: string;
  readonly hint: string;
  readonly group: string;
  readonly read: (p: TraceParams) => boolean;
  readonly patch: (v: boolean) => TraceParamsInput;
}

export interface ChoiceOption {
  readonly value: string;
  readonly label: string;
}

export interface ChoiceSpec {
  readonly key: string;
  readonly label: string;
  readonly hint: string;
  readonly group: string;
  readonly options: readonly ChoiceOption[];
  readonly read: (p: TraceParams) => string;
  readonly patch: (v: string) => TraceParamsInput;
}

export const SLIDERS: readonly SliderSpec[] = [
  {
    key: "preprocess.workingLongEdge",
    label: "Trace resolution",
    hint: "The long edge the trace runs at. Higher resolves finer detail and costs time; the vector is scaled back to the source size either way.",
    group: GROUP_SOURCE,
    min: 256,
    max: 4096,
    step: 64,
    read: (p) => p.preprocess.workingLongEdge,
    patch: (v) => ({ preprocess: { workingLongEdge: Math.round(v) } })
  },
  {
    key: "preprocess.denoiseStrength",
    label: "Noise reduction",
    hint: "How hard the chosen filter smooths before edges are looked for. Too much erases the thinnest lines.",
    group: GROUP_SOURCE,
    min: 0,
    max: 1,
    step: 0.01,
    read: (p) => p.preprocess.denoiseStrength,
    patch: (v) => ({ preprocess: { denoiseStrength: v } })
  },
  {
    key: "preprocess.claheClip",
    label: "Local contrast",
    hint: "CLAHE clip limit. Higher pulls detail out of shadow and haze, and eventually amplifies grain with it.",
    group: GROUP_SOURCE,
    min: 1,
    max: 8,
    step: 0.1,
    read: (p) => p.preprocess.claheClip,
    patch: (v) => ({ preprocess: { claheClip: v } })
  },

  {
    key: "preprocess.unsharpAmount",
    label: "Sharpen amount",
    hint: "How much of the difference between the photograph and a blurred copy of it is added back. 0 is off; past about 2 a pencil line grows a pale halo on both sides.",
    group: GROUP_SHARPEN,
    min: 0,
    max: 5,
    step: 0.05,
    read: (p) => p.preprocess.unsharpAmount,
    patch: (v) => ({ preprocess: { unsharpAmount: v } })
  },
  {
    key: "preprocess.unsharpSigma",
    label: "Sharpen radius",
    hint: "The width of detail sharpening acts on, in pixels. Small values crisp the hairlines; large ones lift broad tonal edges and leave the hairlines alone.",
    group: GROUP_SHARPEN,
    min: 0.05,
    max: 8,
    step: 0.05,
    read: (p) => p.preprocess.unsharpSigma,
    patch: (v) => ({ preprocess: { unsharpSigma: v } })
  },

  {
    key: "edge.sensitivity",
    label: "Edge sensitivity",
    hint: "More ink as it rises. The one control that means the same thing in every edge engine.",
    group: GROUP_EDGES,
    min: 0,
    max: 1,
    step: 0.01,
    read: (p) => p.edge.sensitivity,
    patch: (v) => ({ edge: { sensitivity: v } })
  },
  {
    key: "edge.blurSigma",
    label: "Pre-blur",
    hint: "Gaussian applied before the detector. Raise it to ignore texture, lower it to keep hairlines.",
    group: GROUP_EDGES,
    min: 0.05,
    max: 8,
    step: 0.05,
    read: (p) => p.edge.blurSigma,
    patch: (v) => ({ edge: { blurSigma: v } })
  },
  {
    key: "edge.flow.sigmaM",
    label: "Stroke length",
    hint: "How far the flow-based engine follows a stroke before letting it end. Only affects the flow engine.",
    group: GROUP_EDGES,
    min: 0.05,
    max: 12,
    step: 0.05,
    read: (p) => p.edge.flow.sigmaM,
    patch: (v) => ({ edge: { flow: { sigmaM: v } } })
  },
  {
    key: "edge.xdogPhi",
    label: "Edge hardness",
    hint: "XDoG sharpness: 3 is soft graphite, 300 is a woodcut with no soft edge anywhere.",
    group: GROUP_EDGES,
    min: 0.1,
    max: 300,
    step: 0.1,
    read: (p) => p.edge.xdogPhi,
    patch: (v) => ({ edge: { xdogPhi: v } })
  },
  {
    key: "cleanup.minBlobArea",
    label: "Minimum speck",
    hint: "Ink blobs smaller than this many pixels are dust and are dropped.",
    group: GROUP_CLEANUP,
    min: 0,
    max: 1000,
    step: 1,
    read: (p) => p.cleanup.minBlobArea,
    patch: (v) => ({ cleanup: { minBlobArea: Math.round(v) } })
  },
  {
    key: "cleanup.closeRadius",
    label: "Close gaps",
    hint: "Morphological closing radius: joins strokes that already nearly touch. Too large fuses neighbouring lines.",
    group: GROUP_CLEANUP,
    min: 0,
    max: 8,
    step: 1,
    read: (p) => p.cleanup.closeRadius,
    patch: (v) => ({ cleanup: { closeRadius: Math.round(v) } })
  },
  {
    key: "cleanup.maxGap",
    label: "Bridge reach",
    hint: "How far apart two stroke ends may be and still be joined. Ignored when gap bridging is off.",
    group: GROUP_CLEANUP,
    min: 0,
    max: 64,
    step: 1,
    read: (p) => p.cleanup.maxGap,
    patch: (v) => ({ cleanup: { maxGap: Math.round(v) } })
  },
  {
    key: "cleanup.pruneSpurs",
    label: "Prune spurs",
    hint: "Skeleton branches shorter than this are removed. 0 keeps every whisker thinning produced.",
    group: GROUP_CLEANUP,
    min: 0,
    max: 32,
    step: 1,
    read: (p) => p.cleanup.pruneSpurs,
    patch: (v) => ({ cleanup: { pruneSpurs: Math.round(v) } })
  },
  {
    key: "output.simplify",
    label: "Simplify",
    hint: "Douglas–Peucker tolerance in working pixels. Fewer nodes, straighter runs; 0 keeps every traced vertex.",
    group: GROUP_OUTPUT,
    min: 0,
    max: 8,
    step: 0.1,
    read: (p) => p.output.simplify,
    patch: (v) => ({ output: { simplify: v } })
  },
  {
    key: "output.corner",
    label: "Keep corners",
    hint: "Higher keeps MORE corners: a vertex survives as a corner when its neighbours subtend a sharper angle than this.",
    group: GROUP_OUTPUT,
    min: 0,
    max: 180,
    step: 1,
    read: (p) => p.output.corner,
    patch: (v) => ({ output: { corner: v } })
  },
  {
    key: "output.smoothIterations",
    label: "Smoothing passes",
    hint: "Chaikin passes over the polyline before curve fitting. Each one rounds the geometry a little more.",
    group: GROUP_OUTPUT,
    min: 0,
    max: 8,
    step: 1,
    read: (p) => p.output.smoothIterations,
    patch: (v) => ({ output: { smoothIterations: Math.round(v) } })
  },
  {
    key: "output.strokeWidth",
    label: "Stroke width",
    hint: "Painted width of the exported line, in document units.",
    group: GROUP_OUTPUT,
    min: 0.05,
    max: 8,
    step: 0.05,
    read: (p) => p.output.strokeWidth,
    patch: (v) => ({ output: { strokeWidth: v } })
  },
  {
    key: "output.minPathLength",
    label: "Minimum path",
    hint: "Paths shorter than this are dropped.",
    group: GROUP_OUTPUT,
    min: 0,
    max: 200,
    step: 0.5,
    read: (p) => p.output.minPathLength,
    patch: (v) => ({ output: { minPathLength: v } })
  }
];

const OPAQUE_WHITE = 0xffffffff;

export const TOGGLES: readonly ToggleSpec[] = [
  {
    key: "preprocess.invertInput",
    label: "Source is light-on-dark",
    hint: "Set for chalkboards, negatives and white ink on black, so “ink” stays the dark class everywhere downstream.",
    group: GROUP_SOURCE,
    read: (p) => p.preprocess.invertInput,
    patch: (v) => ({ preprocess: { invertInput: v } })
  },
  {
    key: "preprocess.claheEnabled",
    label: "Equalise local contrast",
    hint: "Turn off for a source that is already evenly lit; CLAHE will otherwise amplify its grain.",
    group: GROUP_SOURCE,
    read: (p) => p.preprocess.claheEnabled,
    patch: (v) => ({ preprocess: { claheEnabled: v } })
  },
  {
    key: "preprocess.perspectiveCorrect",
    label: "Rectify the page",
    hint: "Look for a document quadrilateral and flatten to it. For photographs of paper taken at an angle.",
    group: GROUP_SOURCE,
    read: (p) => p.preprocess.perspectiveCorrect,
    patch: (v) => ({ preprocess: { perspectiveCorrect: v } })
  },
  {
    key: "cleanup.skeletonize",
    label: "Reduce ink to centrelines",
    hint: "Thins strokes to one pixel before tracing. Ignored in outline mode, where a skeleton would delete the boundary being traced.",
    group: GROUP_CLEANUP,
    read: (p) => p.cleanup.skeletonize,
    patch: (v) => ({ cleanup: { skeletonize: v } })
  },
  {
    key: "cleanup.bridgeGaps",
    label: "Bridge stroke ends",
    hint: "Join nearby stroke ends that point at each other. Off keeps a line that stopped, stopped.",
    group: GROUP_CLEANUP,
    read: (p) => p.cleanup.bridgeGaps,
    patch: (v) => ({ cleanup: { bridgeGaps: v } })
  },
  {
    key: "cleanup.removeBorderTouching",
    label: "Drop shapes touching the frame",
    hint: "Removes the drawing board, the scanner lid and the table edge, which a photograph almost always includes.",
    group: GROUP_CLEANUP,
    read: (p) => p.cleanup.removeBorderTouching,
    patch: (v) => ({ cleanup: { removeBorderTouching: v } })
  },
  {
    key: "output.modulateWidth",
    label: "Vary width with the stroke",
    hint: "Samples the distance transform per node so thick-and-thin brushwork survives. Emitted as a filled outline, because SVG has no variable-width stroke.",
    group: GROUP_OUTPUT,
    read: (p) => p.output.modulateWidth,
    patch: (v) => ({ output: { modulateWidth: v } })
  },
  {
    key: "output.fillClosed",
    label: "Fill closed shapes",
    hint: "Paints every closed region solid instead of outlining it. Outline mode with fill is the woodcut look.",
    group: GROUP_OUTPUT,
    read: (p) => p.output.fillClosed,
    patch: (v) => ({ output: { fillClosed: v } })
  },
  {
    key: "output.background",
    label: "White background",
    hint: "Off exports a transparent background.",
    group: GROUP_OUTPUT,
    read: (p) => p.output.background !== null,
    patch: (v) => ({ output: { background: v ? OPAQUE_WHITE : null } })
  }
];

export const CHOICES: readonly ChoiceSpec[] = [
  {
    key: "edge.engine",
    label: "Edge engine",
    hint: "Which detector runs. They fail differently, which is why all of them exist.",
    group: GROUP_EDGES,
    options: [
      { value: "FDOG", label: "Flow (long coherent strokes)" },
      { value: "XDOG", label: "XDoG (drawn look, tunable hardness)" },
      { value: "CANNY", label: "Canny (straight geometric edges)" },
      { value: "ADAPTIVE", label: "Adaptive threshold (already line art)" },
      { value: "LOG", label: "Laplacian (thinnest, most delicate)" }
    ],
    read: (p) => p.edge.engine,
    patch: (v) => ({ edge: { engine: v as EdgeEngineValue } })
  },
  {
    key: "preprocess.denoise",
    label: "Noise filter",
    hint: "Bilateral keeps edges, median kills speckle, anisotropic flattens woven texture.",
    group: GROUP_SOURCE,
    options: [
      { value: "NONE", label: "None" },
      { value: "BILATERAL", label: "Bilateral" },
      { value: "MEDIAN", label: "Median" },
      { value: "ANISOTROPIC", label: "Anisotropic" }
    ],
    read: (p) => p.preprocess.denoise,
    patch: (v) => ({ preprocess: { denoise: v as DenoiseModeValue } })
  },
  {
    key: "matte.mode",
    label: "Background matte",
    hint: "Separates the subject from its background. Never applied unless you choose it, and the pipeline reports how much of the frame it removed.",
    group: GROUP_SOURCE,
    options: [
      { value: "NONE", label: "None" },
      { value: "BORDER_FLOOD", label: "Flood from the border" },
      { value: "SALIENCY", label: "Keep the salient subject" }
    ],
    read: (p) => p.matte.mode,
    patch: (v) => ({ matte: { mode: v as MatteModeValue } })
  },
  {
    key: "output.vectorMode",
    label: "Vector mode",
    hint: "Centreline turns one stroke into one open path; outline turns one region into one closed path. The most consequential choice here.",
    group: GROUP_OUTPUT,
    options: [
      { value: "CENTERLINE", label: "Centreline (one stroke, one path)" },
      { value: "OUTLINE", label: "Outline (one region, one closed path)" }
    ],
    read: (p) => p.output.vectorMode,
    patch: (v) => ({ output: { vectorMode: v as VectorModeValue } })
  },
  {
    key: "cleanup.thinning",
    label: "Thinning kernel",
    hint: "Guo–Hall keeps diagonals better and grows fewer spurs; Zhang–Suen is the classic.",
    group: GROUP_CLEANUP,
    options: [
      { value: "ZHANG_SUEN", label: "Zhang–Suen" },
      { value: "GUO_HALL", label: "Guo–Hall" }
    ],
    read: (p) => p.cleanup.thinning,
    patch: (v) => ({ cleanup: { thinning: v as ThinningModeValue } })
  }
];

export const ESSENTIAL_KEYS: readonly string[] = [
  "output.vectorMode",
  "edge.sensitivity",
  "preprocess.unsharpAmount",
  "cleanup.minBlobArea",
  "output.simplify",
  "output.strokeWidth",
  "preprocess.workingLongEdge"
];

const ESSENTIAL_SET = new Set(ESSENTIAL_KEYS);

export function isEssential(key: string): boolean {
  return ESSENTIAL_SET.has(key);
}

export const PARAM_COUNT = SLIDERS.length + TOGGLES.length + CHOICES.length;

export const ADVANCED_COUNT =
  SLIDERS.filter((spec) => !isEssential(spec.key)).length +
  TOGGLES.filter((spec) => !isEssential(spec.key)).length +
  CHOICES.filter((spec) => !isEssential(spec.key)).length;

export function mergeParams(base: TraceParams, over: TraceParamsInput): TraceParamsInput {
  return {
    preprocess: { ...base.preprocess, ...over.preprocess },
    matte: { ...base.matte, ...over.matte },
    edge: { ...base.edge, ...over.edge, flow: { ...base.edge.flow, ...over.edge?.flow } },
    cleanup: { ...base.cleanup, ...over.cleanup },
    output: { ...base.output, ...over.output },
    auto: { ...base.auto, ...over.auto },
    styleId: over.styleId ?? base.styleId
  };
}

export function applyParamPatch(
  base: TraceParams,
  over: TraceParamsInput,
  sanitize: (input: TraceParamsInput) => TraceParams
): TraceParams {
  return sanitize(mergeParams(base, over));
}

export function changedLabels(a: TraceParams, b: TraceParams): string[] {
  const out: string[] = [];
  for (const s of SLIDERS) if (s.read(a) !== s.read(b)) out.push(s.label);
  for (const t of TOGGLES) if (t.read(a) !== t.read(b)) out.push(t.label);
  for (const c of CHOICES) if (c.read(a) !== c.read(b)) out.push(c.label);
  return out;
}

export function changedAdvancedLabels(a: TraceParams, b: TraceParams): string[] {
  return changedLabels(a, b).filter((label) => {
    const spec =
      SLIDERS.find((s) => s.label === label) ??
      TOGGLES.find((t) => t.label === label) ??
      CHOICES.find((c) => c.label === label);
    return spec !== undefined && !isEssential(spec.key);
  });
}

export function formatValue(v: number, step: number): string {
  if (!Number.isFinite(v)) return "0";
  if (step >= 1) return String(Math.round(v));
  const decimals = step >= 0.1 ? 1 : 2;
  return v.toFixed(decimals);
}

export function overwriteNotice(source: string, before: TraceParams, after: TraceParams): string | null {
  const overwritten = changedLabels(before, after);
  if (overwritten.length === 0) return null;
  const list = overwritten.join(", ");
  return overwritten.length === 1
    ? `${source} changed one setting: ${list}.`
    : `${source} changed ${overwritten.length} settings: ${list}.`;
}

export function inactiveReason(key: string, params: TraceParams): string | null {
  const engine = params.edge.engine;
  const outline = params.output.vectorMode === "OUTLINE";
  switch (key) {
    case "edge.blurSigma":
      return engine === "CANNY" ? null : "Only the Canny engine reads this.";
    case "edge.flow.sigmaM":
      return engine === "FDOG" ? null : "Only the Flow engine reads this.";
    case "edge.xdogPhi":
      return engine === "XDOG" || engine === "FDOG"
        ? null
        : "Only the XDoG and Flow engines read this.";
    case "cleanup.skeletonize":
      return outline ? "Outline mode traces the edge of a region, so nothing is thinned." : null;
    case "cleanup.pruneSpurs":
      if (!params.cleanup.skeletonize) return "“Reduce ink to centrelines” is off, so there is no skeleton to prune.";
      return outline ? "Outline mode traces the edge of a region, so nothing is thinned." : null;
    case "cleanup.maxGap":
      return params.cleanup.bridgeGaps ? null : "“Bridge stroke ends” is off.";
    case "preprocess.claheClip":
      return params.preprocess.claheEnabled ? null : "“Equalise local contrast” is off.";
    case "preprocess.unsharpSigma":
      return params.preprocess.unsharpAmount > 0 ? null : "“Sharpen amount” is 0.";
    case "preprocess.denoiseStrength":
      if (params.preprocess.denoise === "NONE") return "The noise filter is set to None.";
      if (params.preprocess.denoise === "MEDIAN") {
        return "The median filter works from a fixed radius the panel does not expose, not from this.";
      }
      return null;
    default:
      return null;
  }
}
