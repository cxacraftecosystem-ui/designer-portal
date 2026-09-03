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
 * HOW MANY CONTROLS THAT IS, IT DOES NOT SAY — `traceParamTable.PARAM_COUNT` publishes the total and
 * `ADVANCED_COUNT` publishes what the disclosure button reveals, which is the number that button
 * prints. A figure written out in prose here is a second copy
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
 * 5. **NOTHING ON THIS PANEL IS STORED, AND THE PANEL SAYS SO WHERE IT MATTERS.** The trace lives in
 *    React state and dies on unmount; there is no IndexedDB row, no `localStorage` key and no cache of
 *    a trace anywhere in this repository. Everything downstream follows from that, and two of the
 *    owner's requests land squarely on it:
 *
 *    · THE TWO DOWNLOADS ARE MADE FROM MEMORY, ON THE PRESS, and they re-trace at full resolution
 *      first exactly as the attach does — because what is on screen is a preview at a smaller working
 *      edge, and saving that would hand the designer a coarser drawing than the one they approved with
 *      nothing on screen to say so. There is no stored artefact to download and none is created: close
 *      this panel, pick another photograph or reload the page and the trace is gone. The only thing
 *      that outlives the press is the file in the designer's downloads folder and whatever `onAttach`
 *      did with the plate. The sentence under the buttons says this in the designer's own terms.
 *
 *    · THE BEFORE/AFTER COMPARATOR HOLDS TWO BLOB URLS, which is the one kind of memory a component
 *      can leak permanently: an un-revoked object URL pins its whole bitmap for the life of the TAB,
 *      and these are photographs. They are revoked when they are replaced, when the photograph changes
 *      and when this component unmounts — see `compareUrlsRef` and the dispose effect.
 *
 * 6. **IT OPENS ON THE PRIMARY PATH, AND EVERYTHING ELSE IS ONE PRESS AWAY BEHIND ONE DISCLOSURE.**
 *    The owner's report was that "selecting this functionality exposes all settings simultaneously,
 *    which can overwhelm the user", and it did: the frame chooser, `PARAM_COUNT` controls in five
 *    fieldsets, five download buttons and five paragraphs of format copy all arrived at once, above
 *    the one button most designers came to press. What is on screen when the panel opens is now the
 *    photograph, one line saying what the trace is framed to, the style and subject presets, the
 *    {@link ESSENTIAL_KEYS} controls, the traced result, the comparison and "Add the line art".
 *
 *    THE THREE PROPERTIES THAT MAKE THAT SAFE RATHER THAN MERELY TIDIER:
 *
 *    · **NOTHING IS DROPPED, BY CONSTRUCTION.** `ControlGroups` filters on `isEssential` and is called
 *      twice with the two answers, so the two halves are exhaustive and disjoint and a control added
 *      to the table lands in one of them without anybody choosing. `ADVANCED_COUNT` counts the same
 *      predicate, so the number on the button is the number the press reveals.
 *
 *    · **WHAT IS HIDDEN CAN STILL SPEAK.** A non-essential setting moved away from its preset says so
 *      on the toggle ("· 3 changed") and names itself underneath, and the FRAME — the one setting that
 *      is destructive — keeps a line on the primary path whether the section is open or not. §1.10:
 *      a control whose effect is invisible is indistinguishable from one that does nothing.
 *
 *    · **COLLAPSING DESTROYS NOTHING.** The contents are mounted on the first press and thereafter
 *      hidden rather than unmounted — see {@link advancedMounted} — because `Accordion` and a plain
 *      conditional both unmount, and either would throw away a rectangle a designer was aiming.
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
import {
  AlertTriangle,
  Check,
  ChevronDown,
  ChevronUp,
  Crop,
  Download,
  Image as ImageIcon,
  Loader2,
  Sliders,
  Wand2,
  X
} from "lucide-react";

import {
  CHOICES,
  ESSENTIAL_KEYS,
  ADVANCED_COUNT,
  PARAM_GROUPS,
  SLIDERS,
  TOGGLES,
  applyParamPatch,
  changedAdvancedLabels,
  changedLabels,
  formatValue,
  inactiveReason,
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

import { DropCard } from "./DropCard";
import { FramePanel, type EditedFrame } from "./FramePanel";
import { Reveal1 } from "@/components/ui/reveal1";
/*
  THE REPOSITORY'S ONE DOWNLOAD HELPER, IMPORTED RATHER THAN COPIED.

  `lib/designWorkshops.ts` belongs to another unit right now and this does not edit it — importing a
  function is not editing the file that exports it, and this page already loads that module at runtime
  (`app/(protected)/sketches-and-prototypes/page.tsx` imports `getDesignWorkshop`), so it costs no
  bundle here. What it buys is the docblock at `saveBlobToDisk`, which carries a fact that two of the
  four hand-rolled copies elsewhere in this app get WRONG: the object URL must be revoked on the next
  task and not in the same tick as the synthetic click, because "Safari in particular ends up
  downloading nothing at all with no error anywhere". A fifth copy of an anchor-click would be a fifth
  chance to get that one line wrong.
*/
import { saveBlobToDisk } from "@/lib/designWorkshops";

import {
  ATTACHABLE_FORMATS,
  EXPORT_FORMATS,
  PNG_MAX_EDGE_PX,
  RENDER_SUFFIX,
  TRACE_SUFFIX,
  exportPngFile,
  exportSvgFile,
  exportVectorFile,
  isExported,
  paintGeometry,
  type AttachFormatId,
  type ExportFormatId
} from "./traceExport";
import {
  COMPARISON_DIFFERENCE_ALT,
  COMPARISON_DIFFERENCE_BADGE,
  COMPARISON_DIFFERENCE_NOTE,
  COMPARISON_DIFFERENCE_PENDING,
  buildComparisonPlates,
  buildDifferencePlate,
  isComparable,
  isDifference
} from "./comparisonPlates";
import { REVEAL_PEEK_HOLD_MS } from "@/components/ui/reveal1Transform";
import {
  PROGRESS_UNMEASURED_NOTE,
  UNWEIGHTED,
  fractionAt,
  progressWeights,
  traceProgressSentence,
  type ProgressWeights
} from "./traceStages";
import type { SvgInput } from "./geometryToSvg";
// TYPE-ONLY, so the panel that composes this one can own the contract without a runtime cycle.
//
// AND TYPE-ONLY IS LOAD-BEARING FOR A SECOND REASON THIS FILE CANNOT SEE. `e2e/sketch-trace-panel.spec.ts`
// compiles this module on its own and serves it through a hand-built CommonJS registry, whose
// `__require` THROWS on any specifier it was not given ("The harness has no module named …"). A
// `import type` is erased by the transpiler and costs that registry nothing; a value import of a
// module the spec does not list fails AT MOUNT, and all twenty-seven cases in that file then fail
// with the harness's own message rather than with anything about this panel. So: nothing new is
// imported here at runtime, and where this file and its sibling cards need the same shape, they
// carry it rather than share it. See the note above `trigger` for what that costs and why it is paid.
import type { AttachAnswer, ChosenPhotograph } from "./UploadTabPanel";
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
  onAttach: (file: File) => AttachAnswer;
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
  onAttachSource?: (file: File) => AttachAnswer;
  /**
   * The photograph the HOST chose, when the host owns the picker instead of this panel.
   *
   * ── THREE STATES, AND `undefined` IS NOT `null` ────────────────────────────────────────────────
   *
   *   * ABSENT (`undefined`) — this panel owns its picker, exactly as it always has. That is the
   *     record-form mount (`components/designworkshop/FieldInput.tsx`), where the photograph belongs
   *     to that form's own image field, and it is the bare mount every case in
   *     `e2e/sketch-trace-panel.spec.ts` uses. Nothing about that path changed.
   *   * `null` — the host owns the picker and nothing has been chosen yet. This panel then draws NO
   *     picker of its own and says where the one picker is, rather than offering a second.
   *   * a {@link ChosenPhotograph} — the host owns the picker and this is what it chose, already
   *     decoded (or still decoding, or refused; see that type).
   *
   * WHY THE ABSENCE IS THE SWITCH RATHER THAN A `hasOwnPicker` FLAG. A boolean and a value can
   * disagree, and the disagreement here is silent and expensive in both directions: a flag saying
   * "the host owns it" with no value gives a designer no picker at all, and a flag saying "you own
   * it" beside a value gives them two pickers for one photograph — which is the duplication the
   * whole change exists to end. One prop cannot contradict itself.
   *
   * ── WHY THE PANEL IS TOLD AND DOES NOT ASK ─────────────────────────────────────────────────────
   *
   * The two guards this panel keeps around a pick were both written for shipped bugs and neither
   * survives being moved: `pickRef` (a decode that resolves out of order traces photograph A and
   * files the drawing under B's name) and `sourceFiledRef` (the same bytes offered to the host
   * twice). So the pick is adopted THROUGH the same door a local pick goes through — see
   * `adoptPhotograph` — and every reset a fresh photograph owes the panel happens in one place
   * whichever side chose it. What the host takes over is the FILE DIALOG and the decode, not the
   * bookkeeping.
   */
  photograph?: ChosenPhotograph | null;
  /**
   * Whether the host says {@link photograph} is ALREADY on the record this panel would file it to.
   *
   * ── THE HALF OF `sourceFiledRef` THAT ONLY THE HOST CAN KNOW ───────────────────────────────────
   *
   * `sourceFiledRef` remembers a FILE and nothing else — "these bytes have been offered to the host
   * once, do not offer them again" — and that was a complete answer while this panel's photograph
   * could not outlive the thing it was filed to. It can now. The shared card at the top of the
   * section keeps its photograph across a change of the ROW PICKER above it, and "filed" is a claim
   * about one row: the same picture is on the record for the sketch it was attached to and is on no
   * record at all for the next one down.
   *
   * So without this prop the sequence was: file the photograph onto Sketch 1, move the picker to
   * Sketch 2, press "Attach the photograph only" again — and `fileSourceOnce` answered `"already"`
   * from a ref that had never heard of rows, printed "was already filed", called no host and wrote
   * NOTHING to Sketch 2. A designer was told their photograph was safe on a row that did not have
   * it, by a panel sitting directly under a card correctly saying it had not been filed here yet.
   * `UploadTabHost` had the pairing right all along (`sketchPhotoFiled` keeps the row beside the id,
   * and its own note says why); this panel simply was not told the answer.
   *
   * ── WHY IT DOES NOT REPLACE THE REF, WHICH IS THE OBVIOUS SIMPLIFICATION ───────────────────────
   *
   * Because the fact arrives too late to be the guard on its own. `UploadTabHost.attach` clears the
   * tab's `busy` as soon as the bytes are on the device and then syncs and reloads with the tab live
   * — its own note says why — so "Attach the photograph only" is pressable again during phase two,
   * before the host has resolved and while this prop is still `false` because the host has recorded
   * nothing yet. A press guarded by this prop alone would re-file in that window. The ref catches it,
   * because the ref is set BEFORE the await; this prop is the half that expires when the ROW moves
   * rather than when the photograph does. `fileSourceOnce` reads all three and says which is which.
   *
   * ── AND WHY IT IS READ AT THE PRESS RATHER THAN LATCHED IN AN EFFECT ───────────────────────────
   *
   * An effect keyed on this prop was the first shape of the fix and it left a real window open: the
   * designer who moves the row picker DURING phase two changes nothing this prop reports (it was
   * false before the move and is false after it, because the host records the row it captured before
   * the await), so no effect runs, no ref is cleared, and the next press answers `"already"` over
   * the new row. Read at the moment of the press there is no edge to miss — the question is only
   * ever "is it on the row I am about to write to, now".
   *
   * ABSENT ON A HOST THAT OWNS ITS OWN PICKER, where the question is meaningless — a record form's
   * stage field files into the field it is mounted in and has no row picker to move. `undefined` is
   * therefore NOT `false`: it means the question is not answered here, and the guard falls back to
   * the ref alone, exactly as it behaved before this prop existed.
   */
  photographFiled?: boolean;
}

type Phase =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "ready" }
  | { status: "unavailable"; reason: string };

/**
 * Which full-resolution run is in flight, if any.
 *
 * ONE PIECE OF STATE FOR ALL OF THEM, because they are one operation with a different ending: disarm
 * the debounce, re-trace at full resolution, then attach / save the file. Independent busy flags would
 * allow two full-resolution traces at once, and `runTrace` aborts the previous controller — so the
 * loser would report "the trace did not finish" while the winner quietly succeeded, which is the exact
 * class of bug the debounce/attach collision already was.
 *
 * THE DOWNLOAD ARM IS KEYED BY FORMAT ID RATHER THAN BY A HAND-WRITTEN LIST, so a row added to
 * `EXPORT_FORMATS` gets its own spinner with nothing here to remember. It read
 * `"download-trace" | "download-render"` while there were exactly two download buttons; a third
 * format would have spun the wrong one, or none.
 */
type FullRun = "attach" | `download-${ExportFormatId}`;

/**
 * How much of the traced frame the comparator shows before the divider is dragged.
 *
 * ZERO IS THE WHOLE POINT AND IT IS NOT THE COMPONENT'S DEFAULT. `components/ui/reveal1.tsx` clips the
 * BEFORE layer by `position`, so 0 means "before fully clipped": the traced result fills the frame and
 * the divider sits hard against the leading edge, which is what the owner asked for. Its own default
 * of 50 opens half-and-half. Named here so the argument is not a bare literal in the JSX.
 */
const COMPARE_START_POSITION = 0;

/**
 * The four things the comparator can be showing.
 *
 * THE FIRST THREE ARE THE HANDSET'S CHIPS, BY NAME — `DwTraceCompareMode` at
 * `android/.../DwSketchTraceCompare.kt:128`, whose labels are "Drawing", "Wipe" and "Photograph". The
 * portal had only the wipe, and its two ends were reachable only by dragging the seam to an edge or by
 * pressing Home and End: a designer who simply wanted to look at the drawing whole had to know that.
 *
 * THE FOURTH IS NEW ON BOTH CLIENTS and is named identically on both — see
 * {@link COMPARISON_DIFFERENCE_NOTE} for the sentence and `differenceRgba` for the arithmetic the two
 * share. It is last because the wipe is what a designer reaches for and this is what they reach for
 * when the wipe has left them unsure, and because it is the only one that costs a third plate.
 */
type CompareMode = "drawing" | "wipe" | "photograph" | "difference";

/**
 * The chip row, in the handset's order, with the handset's words.
 *
 * THREE LITERALS AND ONE CONSTANT, WHICH IS NOT AN OVERSIGHT — it is the handset's own arrangement.
 * "Difference" is the one label that is also written ON the picture, so the chip and the badge have
 * to be the same word: a designer who presses one and reads the other has been shown two names for
 * one view. `DwSketchTraceCompare.kt` passes `DW_TRACE_DIFFERENCE_LABEL` to both for that reason, and
 * the other three name nothing but themselves.
 */
const COMPARE_MODES: readonly { readonly id: CompareMode; readonly label: string }[] = [
  { id: "drawing", label: "Drawing" },
  { id: "wipe", label: "Wipe" },
  { id: "photograph", label: "Photograph" },
  { id: "difference", label: COMPARISON_DIFFERENCE_BADGE }
];

/**
 * The most a designer may magnify a plate in the comparator.
 *
 * Six, the same as the handset's `DW_TRACE_MAX_ZOOM`, and for the reason stated there: beyond it a
 * plate capped at `COMPARISON_LONG_EDGE_PX` is showing its own pixels rather than the drawing's. The
 * magnifier is not a convenience — a pencil line on a 1024px plate rendered into a card a few hundred
 * CSS pixels wide is sub-pixel, so the failure this comparator exists to catch is invisible at fit.
 */
const COMPARE_MAX_ZOOM = 6;

/**
 * This card's name, in the ONE spelling every surface uses.
 *
 * A constant rather than the literal typed in four places — the collapsed trigger, the open heading,
 * the foot control and the close button's own sentence — because those four are the same claim and a
 * card whose heading and whose collapse control disagree about its name is a card a reader cannot
 * match up. Sentence case, deliberately: the title-cased form appears nowhere in this repository, on
 * either client, and "restoring" one that was never there is a documented failure of its own
 * (`SketchTabs.tsx`). `e2e/sketch-trace-panel.spec.ts` addresses this panel by these exact words
 * twenty-seven times, so a rename here is a rename in that file too, and that file is not this
 * change's to edit.
 */
const CARD_TITLE = "Trace a sketch into line art";

export function SketchTraceField({
  targetLabel,
  disabled,
  onAttach,
  onAttachSource,
  photograph,
  photographFiled
}: SketchTraceFieldProps) {
  const panelId = useId();
  /**
   * Whether the photograph is chosen somewhere else on the screen.
   *
   * READ OFF THE PROP'S PRESENCE, ONCE, so every consumer below asks the same question the same way
   * — the prop's own documentation explains why the absence rather than a flag is the switch.
   */
  const fedFromOutside = photograph !== undefined;
  /*
    DERIVED FROM THE ONE `useId`, exactly as `${panelId}-style-hint` and its siblings below are. Two
    `useId()` calls would be two independent ids for one component, which is how a `for`/`id` pair and
    an `aria-controls` end up naming different things after a refactor moves one of them.
  */
  const advancedId = `${panelId}-advanced`;
  const advancedToggleId = `${panelId}-advanced-toggle`;
  const [open, setOpen] = useState(false);
  const [phase, setPhase] = useState<Phase>({ status: "idle" });

  const [runtime, setRuntime] = useState<TraceRuntime | null>(null);
  const [styles, setStyles] = useState<readonly StyleChoice[]>([]);
  const [styleGroups, setStyleGroups] = useState<readonly string[]>([]);
  const [subjects, setSubjects] = useState<readonly SubjectChoice[]>([]);

  const [file, setFile] = useState<File | null>(null);
  const [pixels, setPixels] = useState<DecodedPixels | null>(null);
  /**
   * The frame `FramePanel` last committed — a crop, a sharpen, or both — or null for "as decoded".
   *
   * HELD HERE RATHER THAN INSIDE THAT PANEL because it is an INPUT to the trace, and the trace lives
   * on this side. `pixels` stays the whole decoded photograph for as long as the photograph is chosen:
   * a crop is taken from it afresh every time, so widening a frame back out is always possible and the
   * original is never the thing that was overwritten. See `FramePanel`'s header for why that matters
   * beyond convenience.
   */
  const [edited, setEdited] = useState<EditedFrame | null>(null);

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
  /**
   * How full the bar is, or null when there is nothing to draw one from.
   *
   * NULL FOR A PREVIEW, AND NOT BECAUSE OF A FLAG HERE. `worker/trace.worker.ts` hands `Pipeline.run`
   * a progress callback and hands `Pipeline.runPreview` none at all, so a preview emits no stage
   * events and this simply never leaves null for one. Which is the right answer: a preview is a few
   * hundred milliseconds and a bar that appeared and vanished on every slider release would be noise.
   */
  const [progressAt, setProgressAt] = useState<number | null>(null);
  /**
   * Where each stage starts on the bar, from THIS machine's last completed trace.
   *
   * See `traceStages.ts` for why the engine's own fraction is not good enough: it is a stage count, it
   * never reaches 1, and the two stages that dominate a real trace are worth several of the others put
   * together — so an unweighted bar rushes to a half and then sits there for most of the wait.
   */
  const [weights, setWeights] = useState<ProgressWeights>(UNWEIGHTED);
  /**
   * True from the press of Stop until the run's own `finally` is reached.
   *
   * A SEPARATE FLAG, AND IT IS WHAT MAKES "Stopping…" HONEST. The engine checks its cancellation token
   * BETWEEN stages and nowhere else, so the worst case is the length of the longest single stage —
   * seconds at full resolution. A control that vanished on the press would claim the run had stopped
   * while it was still running; one that promised instant would be wrong. The handset says the same
   * word for the same reason (`DwSketchTracePanel.kt:1317-1321`).
   */
  const [stopping, setStopping] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  /**
   * Whether the one "Show more options" disclosure is open.
   *
   * ── WHY THERE IS EXACTLY ONE OF THESE, AND WHAT IS BEHIND IT ────────────────────────────────────
   *
   * The owner's report: "selecting this functionality exposes all settings simultaneously, which can
   * overwhelm the user." It did. Opening the panel put the frame chooser, {@link PARAM_COUNT}
   * controls in five fieldsets, five download buttons and five paragraphs about file formats on
   * screen at once, above a single button that is the only thing most designers came to press.
   *
   * So the panel now opens on the PRIMARY PATH only — the photograph, what the trace is framed to,
   * the style and subject presets, the {@link ESSENTIAL_KEYS} controls, the result, the comparison and
   * "Add the line art" — and everything else is one press away behind one disclosure. Nothing was
   * dropped: `ADVANCED_COUNT` is measured off the table, so a control that is not essential is inside
   * this section by construction rather than by anybody remembering to put it there.
   */
  const [advancedOpen, setAdvancedOpen] = useState(false);
  /**
   * Whether the disclosure's contents have EVER been rendered.
   *
   * ── THE TWO THINGS THIS BUYS, AND WHY NEITHER `Accordion` NOR A PLAIN CONDITIONAL GIVES BOTH ────
   *
   * The shared `Accordion` primitive unmounts its children on collapse — a stated contract (§11.3),
   * not an optimisation — and so does a plain `{open ? … : null}`. Either would destroy `FramePanel`'s
   * in-progress state every time this section closed: the rectangle being aimed, the half-typed number
   * in a box, the sharpening sliders. A designer who closed the section to look at the preview would
   * come back to the whole photograph, with nothing saying why.
   *
   * And mounting it ALWAYS is not free either. `FramePanel` reads the entire decoded photograph — up
   * to 16.7 million pixels — to draw its preview, on the commit that first shows it. Paying that for
   * every designer who never opens this section is the allocation `DwSketchTraceCropPanel.kt` collapses
   * its own crop tool to avoid.
   *
   * So: nothing until the first press, and after that the contents stay mounted and are hidden with
   * `hidden` (Tailwind's preflight makes it `display: none`, which also takes the subtree out of the
   * accessibility tree and out of the tab order). This is also what makes `aria-controls` honest —
   * §17's "only while the panel is mounted" — because the id it names exists from the first press
   * onwards and never afterwards points at nothing.
   */
  const [advancedMounted, setAdvancedMounted] = useState(false);
  /*
    THE CHOOSER HOLDS AN `AttachFormatId`, NOT AN `ExportFormatId`, AND THE TYPE IS THE ENFORCEMENT.
    Three of the five formats are take-away only (`traceExport.ts`'s header: a `.dxf` filed on the
    record is a file the handset can neither produce nor preview), so "Attach as" draws from
    `ATTACHABLE_FORMATS` and this state cannot hold anything else. `attachTrace` reads it, and its
    `format === "svg"` test is therefore exhaustive over two cases rather than five.
  */
  const [format, setFormat] = useState<AttachFormatId>("svg");
  const [running, setRunning] = useState<FullRun | null>(null);
  /** What the last download saved, for the live region beside the buttons. Never closes the panel. */
  const [saved, setSaved] = useState<string | null>(null);
  /**
   * The two comparison plates, as object URLs, or null when there is nothing to compare.
   *
   * URLs rather than blobs because that is what `Reveal1` takes, and the URLs are created HERE rather
   * than in `comparisonPlates.ts` for a reason that file states: a URL is a thing that has to be
   * revoked, and only the component knows when it left the screen.
   */
  const [compare, setCompare] = useState<{
    readonly traceUrl: string;
    readonly originalUrl: string;
    /**
     * The same two pictures as blobs, for the difference view to subtract when it is asked for.
     *
     * NO EXTRA MEMORY. An object URL pins its blob until it is revoked, so these two references are
     * already alive for exactly as long as the URLs beside them; holding them is what keeps
     * `buildDifferencePlate` from having to fetch a `blob:` URL back through the network stack to
     * reach bytes this component never let go of.
     */
    readonly traceBlob: Blob;
    readonly originalBlob: Blob;
    readonly width: number;
    readonly height: number;
    readonly reduced: boolean;
  } | null>(null);
  /** Why there is no comparison, when there is a trace but the plates could not be built. */
  const [compareProblem, setCompareProblem] = useState<string | null>(null);
  /** Which of the four views the comparator is showing. Wipe, as on the handset, is the default. */
  const [compareMode, setCompareMode] = useState<CompareMode>("wipe");
  /**
   * Where the designer left the seam.
   *
   * HELD HERE AND NOT IN `Reveal1`, which is what makes the three chips possible at all: "Drawing" and
   * "Photograph" write the DISPLAYED position to an end without touching this, so pressing Wipe again
   * comes back to where the designer was rather than to the middle. The comparator was uncontrolled
   * until 2026-08-27 and none of that could be expressed.
   */
  const [comparePosition, setComparePosition] = useState(COMPARE_START_POSITION);
  /** The third plate, once somebody has asked for it. Object URL, revoked with the other two. */
  const [difference, setDifference] = useState<string | null>(null);
  /** Why there is no third plate, in the sentence written to be read. */
  const [differenceProblem, setDifferenceProblem] = useState<string | null>(null);
  const [differenceBusy, setDifferenceBusy] = useState(false);

  const tracerRef = useRef<Tracer | null>(null);
  /**
   * The same weights as {@link weights}, for the progress callback to read.
   *
   * A REF AS WELL AS STATE, and not instead of it. `runTrace` is a `useCallback` that must not list
   * the weights in its dependency array — doing so would give it a new identity the moment a trace
   * finished, which the debounce effect watches, so every completed trace would arm another one. The
   * callback inside it therefore reads this, which is the value NOW; the bar renders from the state.
   */
  const weightsRef = useRef<ProgressWeights>(UNWEIGHTED);
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
   * Which of the HOST's photographs this panel has already taken up, by {@link ChosenPhotograph.id}.
   *
   * THE IDENTITY IS THE `id` AND NOT THE RECORD, because the record is rebuilt the moment the decode
   * settles — same photograph, second object, pixels filled in. An effect keyed on the record alone
   * would re-run then, and a re-run that took the pick up again would throw away the drawing, the
   * frame and the "saved" sentence for a photograph that never changed. Zero is the no-photograph
   * id, which is why the host's counter starts at one.
   */
  const adoptedRef = useRef(0);
  /**
   * The photograph already handed to `onAttachSource`, if any.
   *
   * `setOpen(false)` deliberately keeps `file` and `pixels` so reopening does not make the designer
   * re-pick, which means attach-as-SVG then reopen-and-attach-as-PNG would offer the host the same
   * photograph twice. Whether that duplicates a media id or replaces one is the host's business; not
   * offering it twice is this side's.
   *
   * IT REMEMBERS A FILE AND NOT A DESTINATION, WHICH IS WHY IT IS NO LONGER READ ON ITS OWN.
   * `adoptPhotograph` clears it for a new photograph, and that was the whole of the answer while a
   * photograph could not outlive the thing it was filed to. On the UPLOAD tab it can: the shared card
   * holds its pick across a change of the ROW PICKER, and this ref alone would then refuse to offer
   * the host a photograph the new row genuinely does not have — answering `"already"` over a row with
   * nothing on it. `fileSourceOnce` therefore reads it beside the host's own answer
   * ({@link SketchTraceFieldProps.photographFiled}) and beside {@link sourceInFlightRef}; the three
   * together are the guard, and the note on that function says which of them covers what.
   */
  const sourceFiledRef = useRef<File | null>(null);
  /**
   * True from just before `onAttachSource` is called until it has answered.
   *
   * ── THE WINDOW THE HOST'S OWN ANSWER CANNOT COVER, BECAUSE IT HAS NOT ARRIVED YET ──────────────
   *
   * `UploadTabHost.attach` drops the tab's `busy` as soon as the bytes are on the device and then
   * syncs and reloads with the tab live — its own note says why — so "Attach the photograph only" is
   * pressable again for the whole of that second phase, seconds of it on a courtyard hotspot. During
   * it {@link SketchTraceFieldProps.photographFiled} is still `false`, because the host has not
   * resolved and so has not recorded anything: an offer guarded by that prop alone would hand the
   * same photograph over a second time, which is the exact duplicate `sourceFiledRef` exists to
   * prevent. This ref is what says "an offer is outstanding" in the gap between the two.
   *
   * A REF AND NOT STATE, for the reason `fullRunRef` gives: it is written and read inside one async
   * function, where a piece of state would be the value from the render that scheduled it.
   */
  const sourceInFlightRef = useRef(false);
  /**
   * True while a full-resolution run — an attach or either download — is in flight, for the debounce
   * effect to read synchronously.
   *
   * A REF AS WELL AS `running`, and not instead of it. `running` is what the buttons render from; this
   * is what the debounce effect reads in its own body, where a piece of state would be the value from
   * the render that scheduled the effect rather than the value now. The downloads share it because
   * they share the hazard: a preview armed 220 ms ago aborts the full-resolution trace a press is
   * awaiting, and the abort reads as "nothing finished" with a finished drawing on screen.
   */
  const fullRunRef = useRef(false);
  /**
   * Every object URL the comparator currently holds.
   *
   * THE LEAK THIS PREVENTS IS THE WORST KIND: `URL.createObjectURL` keeps its blob alive until it is
   * revoked or the document goes away, so a panel that made two 1024px PNGs per trace and forgot them
   * would pin one photograph-sized bitmap per slider drag for as long as the tab lived. The array is
   * revoked and replaced together, in one place, so a plate can never be dropped without its URL.
   */
  const compareUrlsRef = useRef<readonly string[]>([]);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const headingRef = useRef<HTMLHeadingElement | null>(null);
  /** Whether the panel was open on the previous render, so focus is returned only on a real close. */
  const wasOpenRef = useRef(false);
  /** The disclosure's own container, so opening it can put focus inside what just appeared. */
  const advancedRef = useRef<HTMLDivElement | null>(null);
  /**
   * True between "a press asked for the frame chooser" and "focus has been moved into it".
   *
   * A REF AND AN EFFECT RATHER THAN A `requestAnimationFrame` INSIDE THE HANDLER. The press both
   * mounts the section and opens it, so the element focus is owed to does not exist yet when the
   * handler runs; an effect keyed on `advancedOpen` runs after the commit that created it, which is
   * the first moment the ref is populated. The flag is what keeps the effect from stealing focus on
   * an open the designer did not ask to be moved for — the "Show more options" toggle sits directly
   * above its own panel, so a reader is already where they need to be.
   */
  const focusAdvancedRef = useRef(false);

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
        /*
          THE STYLE PICKER OPENS ON THE STYLE THAT IS ACTUALLY LOADED.

          It used to open on a row reading "Engine defaults" while the parameters underneath carried
          `styleId: "clean-line"` — so the control named one thing, the drawing was made by another,
          and the hint below it showed the generic fallback instead of Clean line's own description.
          Choosing that row called `pickStyle("")`, found no preset and returned having done nothing:
          a dead menu row that also mislabelled the live state.

          `sanitizeTraceParams` forces a non-empty `styleId` (an empty one becomes the default), so
          "nothing selected" is not a state the engine can be in and the picker no longer offers it.
          The handset reached the same conclusion and argued it in place — `includeNone = false`.
        */
        setStyleId(loaded.defaults.styleId);
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

  /**
   * Move focus into the disclosure when a press asked for something INSIDE it.
   *
   * "Choose a frame" sits at the top of the panel and the frame chooser is at the bottom of a section
   * further down: opening it and leaving focus on the button would make a keyboard user tab through
   * every preset, every essential control and the toggle to reach the thing they just asked for. The
   * "Show more options" toggle deliberately does NOT set the flag — it is directly above its own
   * panel, so the next Tab already lands inside it and moving focus would be taking a reader
   * somewhere they were about to arrive.
   */
  useEffect(() => {
    if (!advancedOpen || !focusAdvancedRef.current) return;
    focusAdvancedRef.current = false;
    advancedRef.current?.focus();
  }, [advancedOpen]);

  /**
   * A worker outlives the component that forgot it — and so does an object URL.
   *
   * ONE TEARDOWN SITE FOR BOTH, deliberately. The two leaks are the same shape: a resource the browser
   * holds on this component's behalf and will not reclaim on unmount by itself. A blob URL is the more
   * expensive of the two here, because the blob behind it is a photograph and it is pinned for the
   * life of the TAB rather than of the page view — closing an inline panel is not a navigation.
   */
  useEffect(() => {
    return () => {
      goneRef.current = true;
      if (retraceTimerRef.current !== null) window.clearTimeout(retraceTimerRef.current);
      abortRef.current?.abort();
      tracerRef.current?.dispose();
      tracerRef.current = null;
      for (const url of compareUrlsRef.current) URL.revokeObjectURL(url);
      compareUrlsRef.current = [];
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
  /**
   * Take up a photograph — from this panel's own picker or from the host's — and clear everything the
   * previous one left behind.
   *
   * ── ONE DOOR, BECAUSE THE RESETS ARE THE HALF THAT GETS FORGOTTEN ──────────────────────────────
   *
   * Every line below is a sentence or a piece of geometry that belongs to the OLD photograph, and a
   * second entry point that set `file` without them would leave the panel showing one photograph's
   * answer over another photograph's picture. That is a shipped-bug shape in this file twice over —
   * see `setSaved` and `setEdited` below — so when the picker moved out of this panel it was routed
   * back through here rather than given its own path in. It returns the pick token so the only
   * caller that can lose a race (the local one, which then awaits a decode) can hold on to it.
   *
   * `null` PUTS THE PHOTOGRAPH AWAY, which is the host's "Put this photograph away" control. It runs
   * exactly the same resets, because a panel emptied by one route and by the other must be in the
   * same state — an empty card still showing the last drawing is the worst of the three.
   */
  const adoptPhotograph = useCallback((chosen: File | null) => {
    pickRef.current += 1;
    /*
      THE TRACE THAT IS STILL RUNNING BELONGS TO THE OLD PHOTOGRAPH, and until this line nothing
      stopped it. Every `setResult`, `setProblem` and `setProgress` below it was cleared here and then
      written again, seconds later, by a promise that had been in flight since before the pick — so
      the panel showed the OLD sheet's drawing on the canvas, the old sheet's path and node counts in
      the `<dl>`, and `buildComparisonPlates` stacked the old drawing over the NEW photograph, aligned
      by nothing. The window is not narrow: nothing re-arms a trace until the host's decode of the new
      photograph lands, and the abort that used to close this arrived only at the START of the next
      run, which is later still. `stopTrace` has always spelt it exactly this way; a pick is the same
      instruction said with a file dialog rather than with a button.
    */
    abortRef.current?.abort();
    setProblem(null);
    setDone(null);
    // "sheet-line-art.svg was saved to this device" IS ABOUT THE OLD SHEET. It sits under the download
    // buttons and it survived a pick, a close and a reopen, so a designer who saved a copy of one
    // trace and then chose a different photograph was told the new one had already been saved — under
    // a file name that is now nobody's. Every other sentence about the last photograph is cleared
    // here; this one was missed.
    setSaved(null);
    setResult(null);
    setFile(chosen);
    setPixels(null);
    // A FRAME CHOSEN ON ONE SHEET IS MEANINGLESS ON THE NEXT, and it has to be cleared HERE rather
    // than left to `FramePanel`: setting `pixels` to null unmounts that panel, so its own reset effect
    // never runs, and the previous photograph's crop would survive as the region this one is traced
    // from. Nothing on screen would distinguish the two — the same failure `pickRef` below exists to
    // stop, arriving by a different route.
    setEdited(null);
    // A new photograph is a new thing to file, whatever was filed for the old one.
    sourceFiledRef.current = null;
    return pickRef.current;
  }, []);

  const chooseFile = useCallback(
    async (chosen: File) => {
      const pick = adoptPhotograph(chosen);
      const outcome = await decodeToPixels(chosen);
      if (pick !== pickRef.current || goneRef.current) return;
      if (!isDecoded(outcome)) {
        setPixels(null);
        setProblem(outcome.reason);
        return;
      }
      setPixels(outcome);
    },
    [adoptPhotograph]
  );

  /**
   * Take up whatever the host chose, and take it up ONCE.
   *
   * ── WHY THE DECODE IS NOT REPEATED HERE ────────────────────────────────────────────────────────
   *
   * The host already ran it. `decodeToPixels` resizes and calls `getImageData`, which is hundreds of
   * milliseconds for a 4096px photograph on a handset, and the measuring card beside this one needs
   * the same `File` turned into a displayable URL — so one owner does both derivations once and
   * hands each panel the one it can use. Decoding again here would be the same expensive work twice
   * for one photograph, which is the shape of the complaint this whole change answers.
   *
   * THE GUARD IS AN `id`, NOT THE RECORD. `photograph` is a new object every time the decode settles,
   * so an effect that took the pick up on every change would wipe the drawing the instant the pixels
   * it was traced from arrived. `adoptedRef` is what makes "the same photograph, told twice" a
   * no-op — and setting `pixels` outside that guard is what lets the second telling deliver them.
   */
  useEffect(() => {
    if (!fedFromOutside) return;
    const id = photograph?.id ?? 0;
    if (adoptedRef.current !== id) {
      adoptedRef.current = id;
      adoptPhotograph(photograph?.file ?? null);
    }
    // A REFUSED DECODE LEAVES THIS NULL DELIBERATELY, and this panel says nothing about it: the host
    // prints `decodeToPixels`'s own sentence beside the picker the designer used, which is where a
    // refusal belongs and is the same rule the attach callbacks follow (`AttachAnswer`). A second
    // sentence here would be this panel inventing words for a failure it did not observe.
    //
    // AND A PHOTOGRAPH ADOPTED WHILE THIS PANEL IS CLOSED IS PREVIEWED ANYWAY, which is a real
    // change and a wanted one. `pixels` feeds the debounce effect below, and that effect needs only
    // a loaded `runtime` — so once the panel has been opened ONCE, choosing a photograph up in the
    // shared card starts the preview trace while this card is still collapsed, and opening it shows
    // a finished drawing rather than a spinner. Nothing runs before the first open, because the
    // engine is not fetched until then.
    if (photograph?.pixels) setPixels(photograph.pixels);
  }, [adoptPhotograph, fedFromOutside, photograph]);

  /* ──────────────────────────────────────────────────────────────────────────
   * Tracing
   * ────────────────────────────────────────────────────────────────────────── */

  /**
   * The pixels the trace actually runs on: the frame `FramePanel` committed, or the whole decode.
   *
   * ONE PLACE, SO NOTHING CAN TRACE A DIFFERENT FRAME FROM THE ONE THE PANEL SAYS IS APPLIED. Three
   * consumers read it — `runTrace`, the debounce effect that re-previews, and the comparison plates —
   * and the third is the one that would have broken silently: `buildComparisonPlates` stacks the
   * traced drawing over the photograph, so feeding it the whole photograph while the trace ran on a
   * crop would put two different frames in one comparator, aligned by nothing.
   *
   * `sourceWidth`/`sourceHeight` are carried through UNCHANGED, and that is correct rather than lazy:
   * they mean "the file's own pixel size, before any capping" (`decodeToPixels.DecodedPixels`), which a
   * crop does not change. `width`/`height` are the frame.
   *
   * A memo, not an expression at the call site: the debounce effect watches this object's identity, and
   * rebuilt every render it would re-arm the retrace timer on every keystroke anywhere in the panel.
   */
  const traceSource = useMemo<DecodedPixels | null>(() => {
    if (pixels === null) return null;
    if (edited === null) return pixels;
    return {
      data: edited.data,
      width: edited.width,
      height: edited.height,
      sourceWidth: pixels.sourceWidth,
      sourceHeight: pixels.sourceHeight,
      decodeMs: pixels.decodeMs
    };
  }, [edited, pixels]);

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
      if (runtime === null || traceSource === null || params === null) return null;
      /*
        THE SAME TOKEN THE DECODE CARRIES, ON THE OTHER LONG AWAIT. `adoptPhotograph` aborts this run
        when the photograph is replaced, and an abort is the fast path — but abort is a request, not a
        guarantee: a worker that has already posted its answer resolves anyway, and the two racing
        inside one tick is precisely the case a signal cannot decide. The token can. Read here and
        compared past every await below, it is what makes "this answer is about a photograph that is
        no longer on screen" a thing this function can KNOW rather than hope, and it costs one integer.
      */
      const pick = pickRef.current;
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;

      if (tracerRef.current === null) tracerRef.current = new runtime.Tracer();
      const tracer = tracerRef.current;

      setTracing(true);
      setProblem(null);
      setProgress(null);
      setProgressAt(null);
      setStopping(false);
      try {
        const answer = await tracer.trace({
          // A fresh clone every time. The buffer is TRANSFERRED, so the caller's typed array is
          // detached once it has been posted — upstream learned this the hard way and recorded the
          // symptom: transferring the original made "the second trace produces a blank image", which
          // surfaces as a rendering bug rather than as an error.
          image: runtime.transferableFrom(traceSource),
          params,
          preview,
          signal: controller.signal,
          onProgress: (p) => {
            // THE ENGINE'S OWN LABEL, NEVER THIS FILE'S. `traceProgressSentence` adds the stage number
            // around it and nothing else — re-typing engine wording in a client is how the two clients
            // end up describing one operation differently, which `trace.worker.ts` says in its own
            // comment about `runPreview`.
            setProgress(traceProgressSentence(p.stageId, p.label));
            setProgressAt(fractionAt(weightsRef.current, p.stageId, p.fraction));
          }
        });
        /*
          NOTHING OF THIS ANSWER IS PUBLISHED UNDER A PHOTOGRAPH IT WAS NOT TRACED FROM. Returning
          rather than storing is deliberate and is what the two full-run callers read: the drawing is
          real, it simply describes a sheet the designer has replaced, and `result` is the one piece
          of state on this panel that a stale write makes actively misleading rather than merely old
          — the canvas paints it, the `<dl>` counts it, the comparator stacks it over whatever
          photograph is on screen now, and "Add the line art" stays live over the pair.
        */
        if (pick !== pickRef.current || goneRef.current) return null;
        setResult(answer);
        // THE BAR LEARNS FROM THE RUN THAT JUST FINISHED. `stages` is empty for a preview, and
        // `progressWeights` answers UNWEIGHTED for that rather than dividing by zero — so a panel that
        // has only ever previewed keeps the engine's even spacing and keeps saying so.
        const learned = progressWeights(answer.stages);
        weightsRef.current = learned;
        setWeights(learned);
        return answer;
      } catch (error) {
        // A superseded or aborted trace is the normal consequence of moving a slider, not a failure,
        // and reporting it would fill the panel with sentences about work the designer replaced.
        if (runtime.isCancelled(error)) return null;
        if (runtime.isUnavailable(error)) {
          setPhase({ status: "unavailable", reason: (error as Error).message });
          return null;
        }
        /*
          AFTER `isUnavailable` AND BEFORE `setProblem`, WHICH IS THE ONLY ORDER THAT IS RIGHT. "This
          device cannot trace at all" is a fact about the device and stands whichever photograph
          provoked it — losing it because the designer picked again would leave the panel offering
          controls that cannot work. "That photograph could not be traced" is a fact about ONE
          photograph, and printed after a different one has been taken up it is a red box accusing a
          sheet that was never tried.
        */
        if (pick !== pickRef.current || goneRef.current) return null;
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
          setProgressAt(null);
          // CLEARED HERE AND NOWHERE ELSE, which is the whole of what makes "Stopping…" mean anything:
          // this line runs when the run really has unwound, so the word is on screen for exactly as
          // long as stopping takes rather than for a guessed interval.
          setStopping(false);
        }
      }
    },
    [runtime, traceSource, params]
  );


  /**
   * Re-preview whenever the pixels or the parameters change. Debounced — see RETRACE_DEBOUNCE_MS.
   *
   * THE TIMER IS KEPT IN A REF AS WELL AS IN THE CLEANUP, because an attach has to be able to cancel
   * it from outside this effect. A parameter changed within 220 ms of pressing "Add the line art"
   * leaves a preview armed; the preview then aborts the full-resolution trace the attach is awaiting,
   * that abort is (rightly) treated as the ordinary consequence of moving a slider, and the attach
   * reported "the trace did not finish" with a finished drawing on screen. `beginFullRun` clears this
   * timer before any of the three presses starts, and `fullRunRef` keeps the effect from arming a new
   * one behind it.
   */
  useEffect(() => {
    if (runtime === null || traceSource === null || params === null) return;
    if (fullRunRef.current) return;
    const timer = window.setTimeout(() => {
      retraceTimerRef.current = null;
      void runTrace(true);
    }, RETRACE_DEBOUNCE_MS);
    retraceTimerRef.current = timer;
    return () => {
      window.clearTimeout(timer);
      if (retraceTimerRef.current === timer) retraceTimerRef.current = null;
    };
    // `traceSource` RATHER THAN `pixels`, so committing a frame in `FramePanel` re-traces exactly as
    // moving a slider does — same debounce, same supersede-the-older-run behaviour. A frame is another
    // input to the trace and nothing more.
  }, [runtime, traceSource, params, runTrace]);

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

  /**
   * Install a fresh pair of comparison URLs and revoke whatever the last pair was.
   *
   * REVOKE-THEN-REPLACE IN ONE FUNCTION, so the two can never drift apart. The previous URLs are dead
   * the moment this returns, which is safe because the state write below is what renders them and it
   * happens in the same call: React commits the new `src` values, and no `<img>` ever points at a
   * revoked URL. Doing it in two places is how one of the pair gets forgotten.
   */
  const installCompareUrls = useCallback((urls: readonly string[]) => {
    for (const url of compareUrlsRef.current) URL.revokeObjectURL(url);
    compareUrlsRef.current = urls;
  }, []);

  /**
   * Forget the difference plate, because the two it was subtracted from are gone.
   *
   * CALLED FROM EVERY PATH THAT INSTALLS NEW URLS, and it has to be: `installCompareUrls` revokes the
   * whole previous array, the difference URL is in it, and a `difference` state that survived would be
   * an `<img src>` pointing at a revoked blob — a broken picture where a designer expects the answer to
   * "did the trace lose that line". The refusal sentence goes with it: a device that could not make
   * room for one plate may well manage the next one, and a stale refusal beside a fresh trace is a
   * sentence about work that is no longer being described.
   */
  /**
   * Add one URL to the set the comparator holds, WITHOUT revoking what is already there.
   *
   * A SEPARATE FUNCTION AND NOT `installCompareUrls([...previous, url])`, which is the obvious spelling
   * and revokes the two plates it was meant to preserve: that helper revokes the whole previous array
   * by design, because its job is replacing a pair. This one is for the third plate, which JOINS a pair
   * that has to stay alive. Getting the two confused blanks the comparator the moment the fourth chip
   * is pressed.
   */
  const addCompareUrl = useCallback((url: string) => {
    compareUrlsRef.current = [...compareUrlsRef.current, url];
  }, []);

  const forgetDifference = useCallback(() => {
    setDifference(null);
    setDifferenceProblem(null);
    setDifferenceBusy(false);
  }, []);

  /**
   * Build the two pictures the before/after comparator shows, whenever the drawing or the photograph
   * changes.
   *
   * WHY IT IS AN EFFECT AND NOT A BUTTON. The comparator is the answer to "is this trace any good",
   * which is the question every slider on this panel is asked in service of — so it has to be looking
   * at the trace that is on screen now, not at whichever one the designer last pressed a button for. A
   * stale comparison is worse than none: it says the drawing is fine while the drawing on screen is
   * not.
   *
   * WHAT IT COSTS, MEASURED IN THE ONLY UNIT THAT MATTERS HERE. One box-filter downscale of the
   * decoded photograph plus two PNG encodes at `COMPARISON_LONG_EDGE_PX`, per SETTLED trace —
   * `svgInput` only changes when a trace resolves, and a slider drag produces one trace at the end
   * because of `RETRACE_DEBOUNCE_MS`. It is not per frame and not per pointer move.
   *
   * AND THAT DOWNSCALE READS THE WHOLE DECODE, up to 4096px on its long edge — 16.7 million pixels,
   * whatever size the plate it produces is. Which is why it is `resampleRgbaInBands` and not the
   * synchronous one: the work is identical and interruptible instead of one long task on the page
   * thread. The sharpen went to a worker for the same reason at a hundred times the arithmetic per
   * pixel; that file's header has the comparison. A settled trace per slider drag is exactly often
   * enough for a frozen tab to be noticed.
   *
   * THE CANCEL TOKEN IS NOT OPTIONAL. Two traces can settle in quick succession (a preview, then the
   * attach's full-resolution run), and without the token the first build's URLs are installed after
   * the second's — so the comparator shows the older drawing, and the newer pair's URLs are the ones
   * that get revoked. `installCompareUrls` is called only past the guard for exactly that reason.
   */
  useEffect(() => {
    if (svgInput === null || traceSource === null) {
      installCompareUrls([]);
      setCompare(null);
      setCompareProblem(null);
      forgetDifference();
      return;
    }
    let cancelled = false;
    void (async () => {
      // `traceSource`, NOT `pixels`: the before-layer has to be the frame the trace actually ran on,
      // or a cropped trace is stacked over the uncropped photograph and the two layers line up nowhere.
      const outcome = await buildComparisonPlates(traceSource, svgInput, {
        // The downscale is done in bands with the page thread given a turn between them, and this is
        // what stops a superseded build partway through instead of paying for a plane nobody will see.
        // The guard below is still what decides whether an answer is used; this only saves the work.
        shouldStop: () => cancelled || goneRef.current
      });
      if (cancelled || goneRef.current) return;
      if (!isComparable(outcome)) {
        installCompareUrls([]);
        setCompare(null);
        setCompareProblem(outcome.reason);
        forgetDifference();
        return;
      }
      const traceUrl = URL.createObjectURL(outcome.trace);
      const originalUrl = URL.createObjectURL(outcome.original);
      installCompareUrls([traceUrl, originalUrl]);
      setCompareProblem(null);
      forgetDifference();
      setCompare({
        traceUrl,
        originalUrl,
        traceBlob: outcome.trace,
        originalBlob: outcome.original,
        width: outcome.width,
        height: outcome.height,
        reduced: outcome.reduced
      });
    })();
    return () => {
      cancelled = true;
    };
  }, [svgInput, traceSource, installCompareUrls, forgetDifference]);

  /**
   * Build the third plate, once, on the first press of the fourth chip.
   *
   * ON THE PRESS AND NOT WITH THE OTHER TWO, which is the same decision the handset made in this same
   * wave. It is the only view of the four that costs a plate of its own, most designers never open it,
   * and `buildComparisonPlates` already runs per SETTLED trace — which is once per slider drag, on the
   * page thread. Adding a third encode there would be paid by everybody for a picture almost nobody
   * asked for.
   *
   * THE CHIP STAYS PRESSABLE AFTER A REFUSAL. Pressing it is how a designer reads the sentence saying
   * why there is nothing there; a chip that went dead with no explanation is the state this whole panel
   * is written against. So a second press with a refusal standing simply tries again — a browser that
   * would not give the page a surface a moment ago may well now.
   */
  const showDifference = useCallback(() => {
    setCompareMode("difference");
    if (compare === null || difference !== null || differenceBusy) return;
    setDifferenceProblem(null);
    setDifferenceBusy(true);
    const plates = compare;
    void (async () => {
      const outcome = await buildDifferencePlate(
        plates.originalBlob,
        plates.traceBlob,
        plates.width,
        plates.height
      );
      if (goneRef.current) return;
      // THE PLATES MAY HAVE MOVED UNDER THIS. A newer trace settling while the subtraction ran
      // installed a new pair and revoked the old URLs, so a difference built from the old pair would be
      // a picture of a drawing that is no longer on screen — the stale-comparison failure the build
      // effect's own cancel token exists to prevent, one press further along.
      if (compareUrlsRef.current[0] !== plates.traceUrl) return;
      setDifferenceBusy(false);
      if (!isDifference(outcome)) {
        setDifferenceProblem(outcome.reason);
        return;
      }
      const url = URL.createObjectURL(outcome.plate);
      addCompareUrl(url);
      setDifference(url);
    })();
  }, [addCompareUrl, compare, difference, differenceBusy]);

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

  /**
   * The style the engine would have picked for this photograph, when it is not the one already chosen.
   *
   * NULL IS THE COMMON ANSWER and every branch that produces it is a real state rather than a guard:
   * no trace yet, a preview (which does not classify, so `profile` is null), a suggestion naming a
   * preset this build's list does not have, and — the one that matters — a suggestion the designer is
   * already using, where a row saying "try the style you are using" is noise.
   */
  const suggestedStyle = useMemo(() => {
    const id = result?.profile?.suggestion ?? "";
    if (id.length === 0 || id === styleId) return null;
    return styles.find((style) => style.id === id) ?? null;
  }, [result, styleId, styles]);

  /**
   * The one sentence that says why the comparator is not showing a comparison, and the empty string
   * when it is.
   *
   * ALL FIVE ABSENCES ANSWERED IN ONE PLACE, because they are genuinely different and a single "no
   * comparison" placeholder would flatten them into one shrug: nothing traced yet, a trace still
   * running, a trace that failed, a comparison that could not be built from a trace that succeeded,
   * and an update in flight over a comparison already on screen. The fourth is the one that would
   * otherwise be invisible — `buildComparisonPlates` refuses when the browser gives it no drawing
   * surface, or when the traced frame and the decoded frame disagree, and both refusals are sentences
   * written to be read.
   *
   * The failed-trace branch points AT the existing red message rather than restating it: two copies of
   * one fault in one card is how a designer ends up believing there are two.
   */
  const comparisonStatus =
    compare !== null
      ? tracing
        ? "Updating…"
        : ""
      : compareProblem !== null
        ? compareProblem
        : tracing
          ? progress ?? "Tracing…"
          : problem !== null
            ? "The trace did not finish, so there is nothing to compare. The reason is above."
            : result === null
              ? "The comparison appears as soon as the first trace finishes."
              : "Preparing the comparison…";

  /* ──────────────────────────────────────────────────────────────────────────
   * Attaching
   * ────────────────────────────────────────────────────────────────────────── */

  /**
   * Claim the panel for a full-resolution run, whichever of the three it is.
   *
   * DISARMING THE PENDING PREVIEW IS THE FIRST THING IT DOES, and it is the whole reason this is a
   * function rather than four lines repeated. See the debounce effect: a preview that fires mid-run
   * aborts the full-resolution trace the press is awaiting, and the abort reads as "nothing finished"
   * while a finished drawing is on screen. The bug was found once on the attach; the downloads would
   * have reproduced it exactly.
   */
  function beginFullRun(kind: FullRun) {
    if (retraceTimerRef.current !== null) {
      window.clearTimeout(retraceTimerRef.current);
      retraceTimerRef.current = null;
    }
    fullRunRef.current = true;
    setRunning(kind);
    setProblem(null);
  }

  function endFullRun() {
    fullRunRef.current = false;
    setRunning(null);
  }

  /**
   * Open or close the one disclosure.
   *
   * `setAdvancedMounted(true)` on every press rather than only the first: it is already true after
   * that, React skips a write of the same value, and a guard here would be a second place for the two
   * flags to disagree. See {@link advancedMounted} for why the contents are never unmounted again.
   */
  function toggleAdvanced() {
    setAdvancedMounted(true);
    setAdvancedOpen((value) => !value);
  }

  /**
   * The "Choose a frame" press — the handset's own control, and the direct route to the frame chooser.
   *
   * It drives the SAME disclosure the toggle does rather than a second one, because two disclosures
   * over one region is two states that can disagree about whether it is open. What it adds is the
   * focus move: see the effect above.
   */
  function chooseFrame() {
    if (advancedOpen) {
      setAdvancedOpen(false);
      return;
    }
    focusAdvancedRef.current = true;
    setAdvancedMounted(true);
    setAdvancedOpen(true);
  }

  /**
   * Ask the running trace to stop.
   *
   * REAL WORK-STOPPING, NOT HIDING. The abort posts a cancel to the worker, whose `CancellationToken`
   * unwinds `Pipeline.run` between stages — the same mechanism a superseded preview has always used,
   * which is why this needed a BUTTON and not a mechanism. `traceClient.busy`'s own docblock has
   * called itself "the enabled state of a Cancel control" since it was written and no such control
   * was ever built: until now the only way to abandon a full-resolution trace was to move a slider and
   * hope, and there was nothing on screen that said so.
   *
   * `runTrace`'s `catch` already treats a cancellation as the ordinary consequence of changing
   * something rather than as a failure, so nothing red appears and whatever drawing is already on
   * screen stays exactly where it was.
   *
   * IT ALSO ENDS THE FULL RUN, not just the trace. A press is awaiting `runTrace` and its own `finally`
   * will run — but the buttons render from `running`, and leaving every one of them disabled until the
   * last stage finishes unwinding is a panel that looks stopped without being usable. The pending
   * preview timer goes with it, because a re-trace firing 220 ms after a designer pressed Stop is the
   * panel disagreeing with the button.
   */
  function stopTrace() {
    if (!tracing || stopping) return;
    setStopping(true);
    abortRef.current?.abort();
    if (retraceTimerRef.current !== null) {
      window.clearTimeout(retraceTimerRef.current);
      retraceTimerRef.current = null;
    }
    endFullRun();
  }

  /**
   * The provenance sentence written into a derived file.
   *
   * SHARED BY THE ATTACH AND BY THE DOWNLOADED SVG, because the downloaded copy is the one most likely
   * to be mailed on, printed, or opened in Illustrator by somebody who never saw this panel — so it is
   * the copy that most needs to be able to say what made it and from what. `buildSvg`'s header states
   * the limit this stays inside: nothing identifying, no designer, no workshop, no account.
   */
  function provenanceFor(latest: SerializedTraceResult, sourceName: string): string {
    return (
      `Traced on the device from ${sourceName} by the Design & Prototype Workshop portal. ` +
      `${latest.shapeCount} paths, ${latest.nodeCount} nodes.` +
      /*
        THE FRAME IS PART OF THE PROVENANCE, AND IT IS THE PART A REVIEWER CANNOT INFER.

        A crop is destructive — everything outside it is absent from the drawing — so somebody holding
        the SVG and the photograph side by side and finding that they do not match needs to be able to
        tell whether that was a decision or a fault. The sentence is written by
        `lib/trace/imageEdit.describeEdit`, inside the worker that read the pixels, and carried up
        through `FramePanel` — not re-derived here — so a change to what the frame does changes what
        the file says about itself.

        THE PNG CARRIES NONE OF THIS AND CANNOT. `exportPngFile` takes no provenance argument — a PNG
        has no comment channel this code writes — so a cropped trace attached as a PNG records its
        frame nowhere but on this screen. Stated in the copy under the format buttons, and again under
        the download buttons for the PNG a designer saves, rather than quietly tolerated.
      */
      (edited !== null && edited.note.length > 0 ? ` ${edited.note}` : "")
    );
  }

  /** The traced document, in the shape both exporters and the painter take. */
  function inputFrom(latest: SerializedTraceResult): SvgInput {
    return {
      geometry: latest.geometry,
      width: latest.width,
      height: latest.height,
      background: latest.background
    };
  }

  async function attachTrace() {
    if (svgInput === null || file === null || runtime === null || params === null) return;
    /*
      THE PHOTOGRAPH THIS PRESS IS ABOUT, TAKEN BEFORE THE LONG RUN. `file`, `svgInput` and `params`
      are closed over from the render that drew the button, so everything below this line describes
      one sheet no matter what happens on screen while the full-resolution trace runs — and the shared
      picker above this panel is NOT disabled while it runs, because the host's `busy` is only raised
      once `attach` is called, which is after. Without the guard below, replacing the photograph
      mid-run filed the OLD sheet's line art and the old sheet's photograph, minutes later, into
      whichever row was selected by then, and left a green tick naming a file the panel no longer held.
    */
    const pick = pickRef.current;
    beginFullRun("attach");
    try {
      // FULL RESOLUTION, ONCE, ON THE BUTTON — never on a drag. Everything on screen until now was a
      // preview at a smaller working edge, and `SerializedTraceResult.workingWidth` is how the panel
      // knows: attaching the preview would file a drawing coarser than the one being approved.
      const latest = await runTrace(false);
      /*
        SILENTLY, AND BEFORE THE "did not finish" SENTENCE BELOW, WHICH WOULD OTHERWISE BE THE WRONG
        ANSWER TWICE OVER: the trace did not fail, it was abandoned — by this designer, deliberately,
        by choosing another photograph — and `adoptPhotograph` has already emptied the panel of
        everything the sentence could refer to. A red box arriving on the NEW photograph's card about
        the OLD one is the exact failure the reset was written to end, arriving from the far side of
        an await. `runTrace`'s own catch states the rule for the same reason: a superseded run is the
        ordinary consequence of moving on, not a failure to report.
      */
      if (pick !== pickRef.current || goneRef.current) return;
      if (latest === null) {
        setProblem("The trace did not finish, so there is nothing to attach yet.");
        return;
      }
      const input = inputFrom(latest);
      const note = provenanceFor(latest, file.name);
      const outcome =
        format === "svg"
          ? exportSvgFile(input, file.name, note)
          : await exportPngFile(input, file.name);
      /*
        THE SAME TOKEN AGAIN, BECAUSE THE GUARD ABOVE IS ONE AWAIT TOO EARLY TO COVER THIS.

        `exportPngFile` paints the full-resolution geometry into a canvas and then awaits
        `canvasToBlob` — a real PNG encode at `PNG_MAX_EDGE_PX`, seconds on a handset for a dense
        sheet — and the shared picker above this panel is live for every one of them: this panel's
        own `running` greys out this panel's own buttons and nothing else, and the host raises `busy`
        only once `onAttach` is called, which is below. So "Attach as PNG", then replace the
        photograph while it encodes, and without this line the OLD sheet's plate went to `onAttach`
        and the OLD sheet's photograph to `fileSourceOnce` — under the new photograph, into whichever
        row was selected by then. That is the failure this function's opening note describes, arriving
        one await further along than the guard written for it.

        HERE RATHER THAN AFTER `isExported`, so a refusal sentence goes with it. "This browser would
        not give the page a drawing surface" printed over a sheet that was never tried is the same red
        box accusing the wrong photograph that `runTrace`'s catch guards against.

        NOTHING HAS BEEN WRITTEN YET AT THIS LINE, which is what makes returning the honest answer:
        the export is in memory, no host has been called, and `adoptPhotograph` has already emptied
        the panel of everything a sentence could refer to.
      */
      if (pick !== pickRef.current || goneRef.current) return;
      if (!isExported(outcome)) {
        setProblem(outcome.reason);
        return;
      }
      /*
        THE HOST'S ANSWER DECIDES WHETHER A SUCCESS SENTENCE IS PRINTED AT ALL.

        This used to call `onAttach` and then set the green sentence unconditionally, so a host that
        refused the file — no row chosen on the UPLOAD tab, a stage this browser has no copy of, a
        failed device write — printed its own red refusal above a tick claiming the line art had been
        added. See {@link AttachAnswer}: `false` means "I have already told them, do not claim this
        worked", and `undefined` is every other host, unchanged.

        THE PANEL STAYS OPEN ON A REFUSAL, with the traced geometry and the chosen photograph intact,
        because re-tracing a plate to recover from somebody else's refusal is work nobody should have
        to redo. The photograph is deliberately NOT filed either — `fileSourceOnce` is below this
        return, so a refused trace does not silently leave the source attached on its own.
      */
      if ((await onAttach(outcome.file)) === false) return;
      /*
        ── AND ONCE MORE, FOR THE WINDOW THE HOST'S OWN `busy` DELIBERATELY LEAVES OPEN ────────────

        `onAttach` is `UploadTabHost.attach`, which is TWO phases and raises `busy` for only the
        first: it clears the flag the moment the bytes are on the device and then awaits
        `syncDesignWorkshopDrafts()` and `reload()` with the tab live again — on purpose, and its own
        note says why ("Holding the whole tab for the duration would look like the feature had hung").
        That second phase is the slow one on a courtyard hotspot, and the shared picker is pressable
        throughout it. So this is reachable on the DEFAULT format, with no PNG encode involved at all.

        WHAT IS PREVENTED HERE IS NOT THE LINE ART — that is filed, the host has said so in its own
        two-phase notice, and there is no taking it back. It is the two things that would follow:
        `fileSourceOnce` writing the OLD photograph into the single IMAGE field the designer has just
        replaced on screen — and `sourceFiledRef` cannot stop it, because `adoptPhotograph` reset that
        ref for the new photograph, so the duplicate-source guard is disarmed at exactly the wrong
        moment — and a green tick naming two files the panel no longer holds, over a photograph
        neither of them came from.

        SILENTLY, UNDER `AttachAnswer`'S OWN RULE: the host has already told them. A sentence here
        could only describe a sheet that is no longer on the screen.
      */
      if (pick !== pickRef.current || goneRef.current) return;
      const source = await fileSourceOnce(file);
      setDone(
        `${outcome.file.name} was added to “${targetLabel}”.` +
          (source === "filed"
            ? ` The photograph ${file.name} was filed alongside it, exactly as it is.`
            : source === "already"
              ? ` The photograph ${file.name} was already filed and is untouched.`
              : source === "refused"
                // THE ONE OUTCOME WHERE THE TWO HALVES DISAGREE: the line art was taken and the
                // photograph was not. Naming it is the only honest answer — the host has printed
                // WHY beside the picker, and " The photograph itself is untouched." would read as a
                // deliberate choice rather than as a file that did not land.
                ? ` The photograph ${file.name} was NOT filed — see the message beside the picker.`
                : " The photograph itself is untouched.") +
          (outcome.note ? ` ${outcome.note}` : "")
      );
      setOpen(false);
    } catch (error) {
      setProblem(
        error instanceof Error ? `The line art could not be made: ${error.message}` : "The line art could not be made."
      );
    } finally {
      endFullRun();
    }
  }

  /**
   * Save one of the two derived artefacts to the device. Nothing is uploaded and nothing is filed.
   *
   * ────────────────────────────────────────────────────────────────────────────
   * WHAT IS BEING DOWNLOADED, AND WHY IT IS NOT WHAT IS ON SCREEN
   * ────────────────────────────────────────────────────────────────────────────
   *
   * IT RE-TRACES AT FULL RESOLUTION FIRST, EXACTLY AS THE ATTACH DOES. The drawing on screen is a
   * preview traced at a smaller working edge (`PREVIEW_LONG_EDGE` in the worker), and the panel already
   * says so under the canvas. A download button that saved `svgInput` would hand over a coarser drawing
   * than the one being looked at, with the same shape count and the same name — the single most
   * plausible way for this feature to be wrong while appearing to work. So the press pays for one more
   * trace, and the two downloads and the attach all come from the same full-resolution run.
   *
   * WHAT THE ARTEFACTS ARE, AND WHY THERE ARE FIVE OF THEM RATHER THAN THE OWNER'S ORIGINAL TWO:
   *   · `svg` — the VECTOR geometry. Editable, scalable, re-openable in Illustrator, Inkscape or
   *     CorelDRAW, and byte-for-byte the file the record's `lineArtFile` receives.
   *   · `png` — the RENDERED raster, painted by the same `paintGeometry` that drew the preview above.
   *     What anybody can open, print or drop into a slide.
   *   · `pdf`, `eps`, `dxf` — that same vector geometry, written for a machine that will not take an
   *     SVG: anybody's PDF reader, a print shop's PostScript workflow, a cutter or CNC controller.
   *     These were finished writers sitting unreachable in `lib/trace/engine/` until 2026-08-27, and
   *     what they were missing was not a writer but the `VecDocument` adapter in
   *     `geometryToDocument.ts` — the worker sends flat typed arrays and every one of them takes a
   *     document.
   * Every name is the photograph's own stem plus one suffix (`traceExport.ts` holds both words), so the
   * downloads folder still says which photograph each came from.
   *
   * THE HANDLER IS FORMAT-DRIVEN AND THE ROW OF BUTTONS RENDERS FROM THE SAME TABLE, which is the
   * whole mechanism that stops the next writer going unexposed the way these three did: there is no
   * place to add a format that is not also the place the control comes from.
   * `e2e/sketch-export-formats-unit.spec.ts` asserts that in both directions.
   *
   * NOTHING PERSISTS. There is no stored trace to fetch and none is created — see property 5 in this
   * file's header. This is a `File` made in memory on the press and handed to the browser's own save
   * path; close the panel and it cannot be produced again without re-tracing.
   */
  async function downloadDerived(what: ExportFormatId) {
    if (svgInput === null || file === null || runtime === null || params === null) return;
    // The same token, for the same reason, on the other button that pays for a full-resolution run.
    const pick = pickRef.current;
    beginFullRun(`download-${what}`);
    setSaved(null);
    try {
      const latest = await runTrace(false);
      /*
        AND THE SAME SILENCE. `saveBlobToDisk` below would put a file named after the replaced
        photograph into the downloads folder without the panel showing that photograph anywhere, and
        `setSaved` would then re-write the very sentence `adoptPhotograph` clears — "was saved to this
        device", under a file name that is now nobody's, which is the defect recorded there in as many
        words. Better nothing than a file the designer cannot connect to anything on screen.
      */
      if (pick !== pickRef.current || goneRef.current) return;
      if (latest === null) {
        setProblem("The trace did not finish, so there is nothing to download yet.");
        return;
      }
      const input = inputFrom(latest);
      /*
        THE PNG IS THE ONE THAT GETS NO PROVENANCE NOTE, AND THAT IS NOT AN OMISSION HERE.
        `exportPngFile` takes no such argument — a PNG has no comment channel this code writes — and
        the same is true of the DXF one layer down, where `writeDxf` has no metadata parameter at all.
        Both gaps are stated in the copy under these buttons rather than quietly tolerated (§1.10).
      */
      const outcome =
        what === "svg"
          ? exportSvgFile(input, file.name, provenanceFor(latest, file.name), { suffix: TRACE_SUFFIX })
          : what === "png"
            ? await exportPngFile(input, file.name, PNG_MAX_EDGE_PX, { suffix: RENDER_SUFFIX })
            : await exportVectorFile(what, input, file.name, provenanceFor(latest, file.name), {
                suffix: TRACE_SUFFIX
              });
      /*
        THE SAME TOKEN AGAIN, AND ON THIS BUTTON THE WINDOW IS THE WIDEST ON THE PANEL. Three of the
        five formats reach the network here: `exportVectorFile` dynamic-imports its writer chunk, and
        `WRITER_UNAVAILABLE` exists precisely because that fetch fails on a courtyard hotspot — so it
        can be seconds, and the PNG branch pays for a full-resolution encode instead. The shared
        picker is enabled for all of it: this panel's `running` disables this panel's own buttons and
        the host never learns a download is happening at all.

        WITHOUT THIS, the note above is describing a defect it does not close. `saveBlobToDisk` puts a
        file named after the REPLACED photograph into the downloads folder with that photograph
        nowhere on screen, and `setSaved` then re-writes the exact sentence `adoptPhotograph` clears —
        "was saved to this device", under a file name that is now nobody's, which is the failure
        recorded there in as many words. The refusal branch is inside the guard for the same reason it
        is in `attachTrace`: a red box about a sheet the designer has moved on from accuses the wrong
        photograph.
      */
      if (pick !== pickRef.current || goneRef.current) return;
      if (!isExported(outcome)) {
        setProblem(outcome.reason);
        return;
      }
      saveBlobToDisk(outcome.file, outcome.file.name);
      setSaved(
        `${outcome.file.name} was saved to this device. Nothing was filed on the record and nothing was ` +
          `uploaded.` +
          // §10 of the frontend skill: a cap that reduced the file is stated on screen, never swallowed.
          // `exportPngFile` reports its 2048px ceiling and `buildSvg` its shape ceiling this way, and a
          // download that quietly dropped the sentence would be the one place the ceiling is invisible.
          (outcome.note ? ` ${outcome.note}` : "")
      );
    } catch (error) {
      setProblem(
        error instanceof Error
          ? `That file could not be made: ${error.message}`
          : "That file could not be made."
      );
    } finally {
      endFullRun();
    }
  }

  /**
   * Hand the photograph to the host, at most once per chosen photograph.
   *
   * WHY ONCE MATTERS. `setOpen(false)` deliberately keeps `file` and `pixels`, so a designer can
   * reopen the panel, switch the format and attach again without re-picking anything. Every one of
   * those attaches used to call `onAttachSource`, which offered the host the same photograph a second
   * time; whether that duplicates a media id or replaces one is the host's business, and not offering
   * it twice is this side's. The ref is reset in `adoptPhotograph` — the one door both pickers come
   * in by — because a new photograph is a new thing to file.
   *
   * ── AND A ROW THE PHOTOGRAPH IS NOT ON IS ALSO A NEW THING TO FILE ─────────────────────────────
   *
   * `"already"` is a claim about the RECORD, and `sourceFiledRef` remembers only a file. That was a
   * complete answer while this panel owned the picker, because a photograph could not then outlive
   * the thing it was filed to. On the UPLOAD tab it can: the shared card at the top of the section
   * keeps its pick when the designer moves the ROW PICKER above it, and "filed" is true of the sketch
   * the photograph was attached to and false of the next one down. So the sequence — file onto
   * Sketch 1, move the picker to Sketch 2, press "Attach the photograph only" again — matched the ref,
   * returned `"already"`, called no host and wrote NOTHING, while the panel printed "was already
   * filed" and the card directly above it correctly said the photograph was not filed here yet. The
   * designer was told their photograph was safe on a row that did not have it.
   *
   * ── THE GUARD IS THEREFORE THREE FACTS, AND EACH ONE COVERS WHAT THE OTHERS CANNOT ─────────────
   *
   *   * `sourceFiledRef` — these bytes have been offered at least once. Set BEFORE the await, which
   *     is what makes it the only one of the three that exists early enough to stop a second press.
   *   * `sourceInFlightRef` — that offer has not been answered yet. `attach` drops the tab's `busy`
   *     after phase one and resolves after phase two, so the button is live in between and the host
   *     has recorded nothing it could report; without this the press in that window re-files.
   *   * `photographFiled` — the host still says this photograph is on the row this panel would write
   *     to. This is the one that expires when the ROW moves rather than when the photograph does.
   *
   * A HOST THAT OWNS ITS OWN PICKER PASSES NO ANSWER, and `photographFiled === undefined` therefore
   * leaves the rule exactly as it was — a record form's stage field files into the field it is
   * mounted in and has no row picker to move.
   *
   * Returns which of the four things happened, so the sentence the designer reads is the true one.
   */
  async function fileSourceOnce(chosen: File): Promise<"filed" | "already" | "not-wanted" | "refused"> {
    if (!onAttachSource) return "not-wanted";
    /*
      READ AT THE MOMENT OF THE PRESS, NOT AT THE MOMENT OF THE OFFER, which is why this is a plain
      read of the prop rather than something remembered when the mark was made. The question is
      "is it on the row I am about to write to, NOW" — and the row the designer is looking at is the
      one that moved. `undefined` means the host does not answer this question at all, which is not
      the same as answering "no": treating it as "no" would disarm the duplicate guard on the record
      form, where nothing else covers it.
    */
    const stillOnTheRecord = photographFiled !== false;
    if (sourceFiledRef.current === chosen && (sourceInFlightRef.current || stillOnTheRecord)) {
      return "already";
    }
    /*
      MARKED BEFORE THE CALL, because the press this has to stop is the one that arrives while the
      call is still running — see `sourceInFlightRef`. `adoptPhotograph` is the only place it is
      cleared, and it clears it for a NEW photograph, which is a new thing to file.

      AND A REFUSAL NO LONGER DEAD-ENDS THE PHOTOGRAPH, WHICH IS A BEHAVIOUR CHANGE AND A WANTED ONE.
      The mark is still left standing here on a refusal, exactly as before. What changed is that on a
      host that answers `photographFiled` the mark is no longer read ALONE: a refusal means the host
      wrote nothing, so `photographFiled` stays false, so the guard above lets the next press through
      and the photograph is really offered again. That is what `attachSourceOnly`'s own note has
      always promised in words — "the designer can act on the refusal (choose a row, reload the
      stage) and press again" — and what the ref-only guard quietly refused to do: the second press
      matched the mark, returned `"already"`, called nobody, and told the designer a photograph that
      had been REFUSED was already filed. Re-offering after a refusal is a retry, not a duplicate;
      the duplicate the mark exists to stop is a second offer of bytes that DID land.
    */
    sourceFiledRef.current = chosen;
    // RAISED IN THE SAME BREATH AS THE MARK, because the two are one fact until the host answers:
    // "offered, and nobody can tell you yet whether it landed". See `sourceInFlightRef` for the
    // window this covers — it is the whole of `attach`'s phase two, with this button live.
    sourceInFlightRef.current = true;
    try {
      // A REFUSAL IS ITS OWN ANSWER rather than being folded into "filed": the sentence the designer
      // reads names which of the things happened, and "was filed alongside it" over a file the host
      // rejected is the class of contradiction this whole change is about.
      return (await onAttachSource(chosen)) === false ? "refused" : "filed";
    } finally {
      /*
        LOWERED IN A `finally`, INCLUDING WHEN THE HOST THREW. A host that throws leaves this offer
        unanswered for ever, and a flag stuck at true would go on reporting `"already"` for the rest
        of the afternoon over a photograph nothing recorded — the same shape as a spinner left
        running over a write that is long dead, which `PrototypeModelField` clears in a `finally` for
        the same reason. Once this is down the host's own answer takes over as the authority, and on
        a throw that answer is "not filed", which is the truth.
      */
      sourceInFlightRef.current = false;
    }
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
  async function attachSourceOnly() {
    if (file === null || !onAttachSource) return;
    /*
      THE THIRD BUTTON THAT AWAITS THE HOST, AND THE ONLY ONE THAT WAS LEFT WITHOUT THE TOKEN.

      `attachTrace` takes `pickRef.current` before its run and compares it past four awaits;
      `downloadDerived` does the same past two. This press awaits the SAME host callback both of those
      end at — `onAttachSource` is `UploadTabHost.attach`, which stages the bytes, writes the draft,
      awaits `syncDesignWorkshopDrafts()` and reloads the stage twice before it resolves — so it is
      the LONGEST of the three on a courtyard hotspot, and it had no guard at all.

      The window is not theoretical and it is not narrow. `attach` clears the host's `busy` the moment
      the bytes are on the device (its own note says why: "Holding the whole tab for the duration would
      look like the feature had hung"), so the shared picker above this panel is pressable for the
      whole of phase two. Press "Attach the photograph only" on A, choose B while A is going up, and
      without this line the resolution wrote `setDone("A.jpg was filed exactly as it is")` into the
      live region under photograph B and then called `setOpen(false)` — collapsing the panel the
      designer was working in, under a green sentence naming a file it no longer holds. `file` is
      closed over from the render that drew the button, so every word of that sentence is about A.

      SILENTLY, UNDER `AttachAnswer`'S OWN RULE, and before the refusal branch for the same reason
      `attachTrace` guards before `isExported`: a refusal about a sheet the designer has moved on from
      accuses the wrong photograph. Nothing is lost by saying nothing — the host has already printed
      its own two-phase notice about A, and `adoptPhotograph` has emptied this panel of everything a
      sentence here could refer to.
    */
    const pick = pickRef.current;
    const source = await fileSourceOnce(file);
    if (pick !== pickRef.current || goneRef.current) return;
    // REFUSED BY THE HOST: it has printed its own reason beside the picker, and this panel must not
    // print "was filed" over it. The panel is left open with the photograph still chosen, so the
    // designer can act on the refusal (choose a row, reload the stage) and press again — and that
    // second press now really does offer the photograph again. It did not use to: `fileSourceOnce`
    // read only its own mark, so the retry this line has always been written for answered "already
    // filed" over a photograph the host had just rejected. That function's guard is where the fix
    // lives; this comment is the promise it finally keeps.
    if (source === "refused") return;
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

  /**
   * Any full-resolution run at all, for the buttons that must not be pressed during another.
   *
   * ALL THREE PRESSES DISABLE ALL THREE BUTTONS, not just their own. They share one `AbortController`
   * (`abortRef`) and one worker, so a second press aborts the first press's trace — and this panel
   * treats an abort as the ordinary consequence of moving a slider, so the first press would fail with
   * "the trace did not finish" for a reason that appears nowhere on screen.
   */
  const busy = running !== null;

  /**
   * The one sentence under the card's name, in both states.
   *
   * ONE STRING FOR COLLAPSED AND OPEN, because they were two: closed, this card said nothing at all,
   * and open it said this — so the fact that decides whether a designer is willing to hand a
   * photograph to a tracing tool (that nothing leaves the device) was readable only after they had
   * already committed to opening it. Its neighbour states its own rule while closed, and now so does
   * this one.
   */
  const CARD_DESCRIPTION = `Everything below is computed on this device — the photograph is not sent anywhere to be traced. The original stays exactly as it is; only the drawing is added to “${targetLabel}”.`;

  if (phase.status === "unavailable") {
    /*
      "This device cannot do it at all" wants the control gone, not a button that fails.

      ── AND THE PHOTOGRAPH IS STILL UNFILED HERE. TWO COMMENTS HAVE NOW CLAIMED OTHERWISE. ────────

      The first said "the ordinary file picker underneath is untouched", which was false because this
      panel WAS the only picker the Upload tab had. The second — written when the picker moved out —
      said both hosts "now really do have one", and named the shared card above this one. That is true
      of CHOOSING and false of FILING, which are the two halves this tab is careful to keep apart
      everywhere else: `SharedPhotoField`'s own header says it "CHOOSES and it FILES NOTHING", and
      `MeasureFromPhotoCard` says the same of itself. The only route from a chosen photograph to
      `sketch.image` on this tab is `onAttachSource`, and `onAttachSource` is reached from exactly two
      buttons — "Add the line art" and "Attach the photograph only" — both of which live inside the
      panel this branch returns instead of.

      So on the record form the claim holds (that host has its own image field, and this panel is an
      optional tool over it), and on the Upload tab it does not: a designer whose tracing engine will
      not load can choose the photograph of the sheet, is told by the card above that it will be
      written "when a button in one of them is pressed", and has no such button. The honest fix is a
      control here — `attachSourceOnly` already exists and needs only `onAttachSource` and `file`,
      both of which are in scope at this line — rendered outside this `role="alert"` (interactive
      content inside a live region is announced as part of the alert) and with the `done`/`problem`
      region below it, which this early return also skips. That is a change to what this tab OFFERS
      rather than a correction to what it says, so it is left to the owner and recorded here in the
      one place the next reader will be standing when they need it.

      WHAT MUST NOT HAPPEN AGAIN IS THE THIRD VERSION OF THIS SENTENCE. Both previous ones were
      written in good faith by someone who had just improved the picker and assumed the rest followed.
      The check is one grep, and it fits on a line:

          grep -n "onAttachSource" frontend/components/sketches/upload/SketchTraceField.tsx
    */
    return (
      <div
        role="alert"
        className="mt-3 flex items-start gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs text-ink-500"
      >
        <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
        <span>{phase.reason}</span>
      </div>
    );
  }

  /**
   * ── THE CARD SHELL, DRAWN HERE AND DRAWN AGAIN IN `MeasureFromPhotoCard`, ON PURPOSE ───────────
   *
   * These two cards sit one above the other in the same section and had four different shapes
   * between them: collapsed, this one was an auto-width `field-button-secondary` — a 40px inline
   * button, not a card — while the measuring card was a full-width bordered `<section>`; open, this
   * one was `rounded-lg … p-3` and that one `rounded-md` with the padding on its children; this one
   * carried no description line at all until it was opened and that one carried an always-visible
   * one; and their top margins were `mt-2` here against `mt-3` there. A designer reading down the
   * section met two controls that plainly were not the same kind of thing, and one of them did not
   * look like a control at all.
   *
   * They are now one grammar — `<section className="rounded-md border border-line-200 bg-surface-50">`,
   * an icon, an `<h4>`, a description line under it in a `min-w-0 flex-1` column, a chevron while
   * collapsed, a close at the top and a "Collapse …" at the foot while open, and the same 3-step
   * everywhere. The tints are NOT normalised with the sizes: `.panel` is `bg-card`, these two cards
   * are `bg-surface-50` inside it, and `MeasureFromPhotoCard` refuses `.panel` for its own root in
   * as many words. That layering is deliberate and survives.
   *
   * ── AND WHY IT IS NOT A SHARED COMPONENT, WHICH IS THE FIRST THING TO ASK ──────────────────────
   *
   * Two reasons, and the second is the hard one.
   *
   *   1. A shell that covered both would need a flag per difference — the icon, the focus target on
   *      open, whether the header is itself the toggle, the close control's wording, whether there
   *      is a foot control, the busy treatment — and six mode flags on one component is the thing
   *      this repository's own guidance says two clear components beat.
   *   2. THIS FILE MAY NOT GAIN A RUNTIME IMPORT. `e2e/sketch-trace-panel.spec.ts` compiles this
   *      module alone and serves it through a hand-built registry that throws on any specifier it
   *      was not handed, and that spec is not this change's to edit. A shared shell module would
   *      fail at mount, in twenty-seven cases, with a message about the harness.
   *
   * So the words and the classes are the shared thing, not the symbol — which is the same answer
   * `MeasureFromPhotoCard`'s `CARD_TITLE` records for the four copies of its own name. The grep that
   * finds the pair:
   *
   *     grep -n "rounded-md border border-line-200 bg-surface-50" frontend/components/sketches/upload/
   */
  const trigger = (
    <section className="rounded-md border border-line-200 bg-surface-50">
      <h4>
        <button
          type="button"
          ref={triggerRef}
          className="flex w-full items-start gap-2 rounded-md p-3 text-left disabled:cursor-not-allowed disabled:opacity-60"
          onClick={() => setOpen(true)}
          disabled={disabled}
          aria-expanded={false}
          /*
            NO `aria-controls` WHILE THE PANEL IS UNMOUNTED — §17 of the frontend contract, and this
            trigger broke it: it pointed at `panelId` in every state, including the closed one, where
            that id is in no document at all. Pointing at a missing id is worse than not pointing,
            because a reader is offered a jump that goes nowhere. Opening replaces this button
            outright, so there is no state in which it can honestly carry one.
          */
        >
          <Wand2 className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <span className="min-w-0 flex-1">
            <span className="block text-sm font-medium text-ink-900">{CARD_TITLE}</span>
            {/*
              THE LINE A COLLAPSED CARD OWES ITS READER, which this one did not have. Closed, its
              only text was its own name — so what the panel would do, and the one fact that decides
              whether somebody is willing to open a tracing tool over a photograph at all (that it
              runs here, and that nothing is sent anywhere), was visible only AFTER opening it. Its
              neighbour has always said both while closed.
            */}
            <span className="mt-0.5 block text-xs leading-5 text-ink-500">{CARD_DESCRIPTION}</span>
          </span>
          {/*
            The chevron is DECORATION over `aria-expanded`, never the state itself, and its rotation
            is a CSS transition that both reduced-motion sources zero (§5) — so open and closed are
            still told apart by the arrow's direction with no motion at all. Byte-for-byte the
            treatment `MeasureFromPhotoCard` uses, because two chevrons that rotate differently in
            one section read as two different kinds of control.
          */}
          <ChevronDown className="mt-0.5 h-4 w-4 shrink-0 text-ink-500 transition-transform" aria-hidden />
        </button>
      </h4>
    </section>
  );

  /**
   * The take-away formats, lifted out of the panel's JSX so the ONE disclosure can hold them.
   *
   * A `const` rather than a component, because it closes over nine values from this body — the
   * five running flags, the traced result, the chosen file, the frame and the saved sentence — and
   * a component taking nine props would be nine chances for one of them to stop being passed. It is
   * rendered in exactly one place; see the disclosure above.
   *
   * WHY IT IS INSIDE THE DISCLOSURE AT ALL. The owner's primary path is the photograph, the
   * presets, the essential controls, the comparison and "Add the line art"; a download is a copy
   * for the person at the keyboard and reaches no field, no upload queue and no draft store. The
   * rule below still separates the two promises, and the sentence that used to point at "the row
   * above" now points at the button below this section, which is where it moved to.
   */
  /*
    ── Downloads ──────────────────────────────────────────────────────────────────────────────
    SEPARATED FROM THE ATTACH BY A RULE, BECAUSE THEY ARE DIFFERENT PROMISES. Everything outside
    this block writes to the record; nothing inside it does. A download is a copy for the person
    at the keyboard — it reaches no field, no upload queue and no draft store — and the sentence
    underneath says so, along with the two facts a designer cannot see: that the press re-traces
    at full resolution rather than saving the preview, and that the trace itself is not kept
    anywhere once this panel closes.
  */
  const downloads = (
  <div className="mt-4 border-t border-line-200 pt-3">
    <span className="field-label">Download a copy to this device</span>
    {/*
      ONE BUTTON PER TABLE ROW, WHICH IS THE WHOLE OF THE FIX. This row was two hard-coded
      buttons while `EXPORT_FORMATS` held two entries and `engine/exportFormats.ts` held three
      more finished writers nobody could reach — a designer could trace a sketch and had no way
      to take the vector result away in a format their printer or their cutter would open.
      Rendering from the table means the two facts are one fact: a row without a control cannot
      exist, and `e2e/sketch-export-formats-unit.spec.ts` fails if a writer the engine can run
      is neither in the table nor in `NOT_OFFERED` with a reason.

      The words come from `entry.download` rather than from here, so the two labels
      `sketch-trace-panel.spec.ts` pins live beside the format they belong to.
    */}
    <div className="mt-1 flex flex-wrap gap-2">
      {EXPORT_FORMATS.map((entry) => (
        <button
          key={entry.id}
          type="button"
          className="field-button-secondary"
          onClick={() => void downloadDerived(entry.id)}
          disabled={disabled || busy || result === null || file === null}
        >
          {running === `download-${entry.id}` ? (
            <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
          ) : (
            <Download className="h-4 w-4" aria-hidden />
          )}
          {entry.download}
        </button>
      ))}
    </div>
    {/* HONEST NAMING, ONE LINE EACH. The audience is a designer, not a developer: nobody
        should have to know what a `.dxf` is before pressing a button that makes one, and the
        sentence that says what a format is FOR is the same string the "Attach as" chooser
        shows, so the two surfaces cannot describe one format differently. */}
    {/* FULL WIDTH, NOT `max-w-prose`. This is a two-column-shaped LIST of formats, not running
        prose: 65ch against a 12px font is ~400px while every ancestor here is full width, so the
        measure clamp wrapped "what a .dxf is for" into a narrow ribbon with the rest of the panel
        empty beside it. Android's `DwSketchTraceExportCard.kt` renders the same five strings in a
        `Column(fillMaxWidth())` and always has — this is the web catching up to it, so do not
        re-add the clamp on one client without the other. */}
    <ul className="mt-2 grid gap-1 text-xs leading-5 text-ink-500">
      {EXPORT_FORMATS.map((entry) => (
        <li key={entry.id}>
          <span className="font-medium text-ink-700">{entry.label}</span> — {entry.hint}
        </li>
      ))}
    </ul>
    <p className="mt-2 max-w-prose text-xs leading-5 text-ink-500">
      Every one of these is re-traced at full resolution when you press it, so none of them is the
      smaller preview above. None of them is filed on the record, none is uploaded, and none reaches
      the report: the ministry document prints the sketch photograph, and a drawing downloaded here is
      a copy for you, your printer or your CAD operator. Attaching to the record is the “Add the line
      art” button below this section.
    </p>
    <p className="mt-1 max-w-prose text-xs leading-5 text-ink-500">
      Nothing here is stored: the trace lives only while this panel is open, so closing it, choosing
      another photograph or reloading the page discards it, and a download after that means tracing
      again.
    </p>
    {/*
      WHAT IS WRITTEN INSIDE THE FILE ABOUT WHO MADE IT — the handset's
      `DW_TRACE_EXPORT_ENGINE_NAME_SENTENCE`, copied back here because it is true of the portal's
      downloads too and no web surface said it.

      SCOPED TO THE TWO FORMATS IT IS ACTUALLY TRUE OF, which is where the two clients genuinely
      differ. `exportVectorFile` passes `includeMetadata: true`, so `pdfWriter` emits
      `/Producer (Offline Tracer) /Creator (Offline Tracer)` and `epsWriter` emits
      `%%Creator: Offline Tracer`. The SVG is NOT among them here: this page writes its own
      through `geometryToSvg.buildSvg` rather than through the engine's writer, and that
      function emits no producer line at all.

      THE HANDSET'S SET IS NARROWER STILL, AND THIS COMMENT USED TO GET IT BACKWARDS. It said
      the handset's SVG carries the line because the engine's own writer produces it. It does
      not: `dwTraceKotlinSvgOf` and `DwTraceKotlinExporter` both pass `includeMetadata: false`
      for exactly the branding reason, which leaves ONE branded file on that client — the EPS,
      whose `%%Creator` the vendored writer emits outside that flag. So the honest comparison
      is two formats here against one there, and the difference is a deliberate option this
      page has not taken rather than a capability it has. Re-check with
      `grep -rn "Offline Tracer" frontend/lib/trace frontend/components/sketches/upload` and
      `grep -rn "includeMetadata" android/app/src/main/java`.
    */}
    <p className="mt-1 max-w-prose text-xs leading-5 text-ink-500">
      The PDF and the EPS record that they were made by the Offline Tracer engine, which is the
      tracing library this app uses on the device. That is a note about the software — not about the
      drawing, and not about you. The SVG this page writes carries no such line, because this page
      writes it rather than the engine.
    </p>
    {/* Mounted whether or not anything has been saved, so the sentence is a CHANGE to a region
        already in the document — the same reason the success sentence at the bottom of this
        component lives outside the open/closed switch. */}
    {/*
      THE SAME GAP AS THE ATTACH'S, SAID IN THE OTHER HALF OF THE PANEL.

      `provenanceFor` reaches every format that has somewhere to put it, and three of the five
      do: the SVG carries it as an XML comment, the PDF in its `/Title` and the EPS in its
      `%%Title:`. `exportPngFile` takes no provenance argument — a PNG has no comment channel
      this code writes — and `writeDxf` has no metadata parameter at all, because DXF R12 has
      nowhere to keep one. The copy under the format buttons above says this for the file that
      reaches the RECORD; the download block said nothing at all until 2026-08-24, so a designer
      who cropped and then pressed "Download the rendered image" got a file whose frame is
      recorded nowhere and was told nothing about it. `traceExport.ts` sets the standard this
      meets: stated in the copy beside the button rather than quietly tolerated.
    */}
    {edited !== null ? (
      <p className="mt-2 max-w-prose text-xs leading-5 text-amber-800">
        The frame you chose is written into the SVG&apos;s provenance note, the PDF&apos;s title and
        the EPS&apos;s header, so those three say how they were made. The PNG and the DXF have
        nowhere to carry it: saved on their own, neither records that the drawing was cropped or
        sharpened.
      </p>
    ) : null}
    <p aria-live="polite" aria-atomic="true" className="mt-1 text-xs leading-5 text-ink-500">
      {saved ? (
        <span className="flex items-start gap-2">
          <Check className="mt-0.5 h-3.5 w-3.5 shrink-0 text-success-600" aria-hidden />
          <span>{saved}</span>
        </span>
      ) : null}
    </p>
  </div>
  );

  const panel = (
    <section className="rounded-md border border-line-200 bg-surface-50">
      {/*
        THE HEADER CARRIES THE PADDING AND THE BODY CARRIES ITS OWN, and the root carries none —
        `MeasureFromPhotoCard`'s scheme, adopted here so the two cards' interiors line up. The border
        between them replaces the gap that would otherwise separate the two, which is what keeps this
        card out of the extra vertical air the section was already carrying too much of.
      */}
      <div className="flex items-start gap-2 p-3">
        <Wand2 className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
        <div className="min-w-0 flex-1">
          {/*
            `tabIndex={-1}` makes the heading focusable by script and not by Tab, which is what a
            deliberate focus move needs and what a tab stop on a heading would get wrong.

            AND IT IS THE ONE THING THAT KEEPS THIS HEADER A HEADING RATHER THAN A TOGGLE, which is
            the single shape difference from the measuring card and is worth the sentence. Opening
            this panel moves focus here on purpose, so a reader hears the panel's NAME — "Trace a
            sketch into line art, heading" — and knows where they have landed. A heading that were
            also the collapse button would be announced as a button, and a reflex Space on arrival
            would shut the panel the designer had just asked for. The measuring card has no such
            focus move, so there the header may safely be the toggle.

            `min-w-0 flex-1` ON THE COLUMN, which this header did not have: it was a bare `<div>` in
            a `justify-between` row, so a long registry label in the sentence below pushed the close
            control off its own edge instead of wrapping. §17 — "`min-w-0` is load-bearing wherever
            it appears".
          */}
          <h4
            ref={headingRef}
            tabIndex={-1}
            className="text-sm font-medium text-ink-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-purple-600/40"
          >
            {CARD_TITLE}
          </h4>
          {/*
            NO `max-w-prose`, WHICH IT USED TO CARRY. Inside a card this narrow the clamp never bound
            the line — it only made this card's sentence wrap at a different width from the identical
            sentence in the card below it, which is the asymmetry the pair was reported for. The
            column is the measure now, in both cards, and it is `min-w-0 flex-1` in both.
          */}
          <p className="mt-0.5 text-xs leading-5 text-ink-500">{CARD_DESCRIPTION}</p>
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

      {/*
        ── THE BODY. Everything below this line to the `</div>` above `</section>` is the panel's
        contents, and it is left at its original indentation deliberately: wrapping it cost one
        element, and re-indenting nine hundred lines of JSX to match would have buried a fifteen-line
        change in a diff nobody could read. The `p-3` here is the padding this card used to carry on
        its root, moved so the header can own its own — the scheme `MeasureFromPhotoCard` uses.
      */}
      <div id={panelId} className="border-t border-line-200 p-3">
      {phase.status === "loading" ? (
        <p aria-live="polite" className="flex items-center gap-2 py-6 text-sm text-ink-500">
          <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
          Loading the tracing engine…
        </p>
      ) : null}

      {phase.status === "ready" ? (
        <>
          {/* ── The photograph ─────────────────────────────────────────────── */}
          {/*
            ── ONE PICKER PER SCREEN, AND WHICH SCREEN DECIDES WHERE IT IS ───────────────────────

            Where this panel is handed a photograph, it draws NO picker: the card above the section
            owns the file dialog, the decode and the object URL, and the measuring card below is
            working from the same pick. A picker here as well would be the second upload of one
            photograph — the complaint this arrangement answers — and it would be worse than the
            original two, because the two would now be visibly side by side and a designer could put
            a different sheet in each without either card saying so.

            Where it is NOT handed one it keeps its own, unchanged, because on that host there is no
            card above: a record form's stage field mounts this panel beside its own image field
            (`FieldInput.tsx`), and the bare mount in `e2e/sketch-trace-panel.spec.ts` is that same
            shape. Both branches end at `chooseFile`/`adoptPhotograph`, so the resets a new photograph
            owes this panel are the same whichever side chose it.
          */}
          {fedFromOutside ? (
            <div className="mb-3">
              {/*
                WHAT THIS PANEL IS WORKING FROM, NAMED — not silence. Two cards reading one photograph
                is only safe if each of them says which photograph that is; the card above shows the
                picture and this line names the same file, so the pair can be checked against each
                other without scrolling between them.
              */}
              <p className="rounded-md border border-line-200 bg-card px-3 py-2 text-xs leading-5 text-ink-500">
                {file ? (
                  <>
                    <span className="font-medium text-ink-900">{file.name}</span>
                    {photograph?.problem
                      ? /* `decodeToPixels`'s own sentence, carried rather than paraphrased — the
                           shared card above prints the same one beside the picker, so a reader who
                           opened this panel first is not left with a photograph that says nothing. */
                        ` — ${photograph.problem}`
                      : pixels && (pixels.sourceWidth !== pixels.width || pixels.sourceHeight !== pixels.height)
                        ? ` — read at ${pixels.width}x${pixels.height}, reduced from ${pixels.sourceWidth}x${pixels.sourceHeight}.`
                        : pixels
                          ? ` — ${pixels.width}x${pixels.height}.`
                          : " — reading…"}
                    {/*
                      WHAT THIS PANEL CAN SEE, AND NOT A CLAIM ABOUT THE ONE BELOW IT.

                      This read "the measuring panel below is working from the same one", stated
                      unconditionally — and that panel carries an escape hatch ("Measure a different
                      photograph") which this one is not told about and cannot observe. Use it, and
                      the two cards contradicted each other on one screen: this line said they shared
                      a photograph while the card six inches down said "ruler.jpg is being measured
                      here and nowhere else". Two answers to one question is the failure this tab has
                      already paid for twice, and the reader believes whichever they read first.

                      SO IT STATES THE DEFAULT AND POINTS AT THE PANEL THAT KNOWS. The shared
                      photograph really is what the measuring card starts from, that is the whole of
                      requirement 5 and worth saying here; the exception is one the card announces
                      itself, in its own words, at the moment it is in force. A sentence that is true
                      in every state beats a shorter one that is true in most.
                    */}
                    {" Chosen in “Photograph of the sketch” above. The measuring panel below starts from the same one, unless it has been pointed at a different photograph — it says so on its own card when it has."}
                  </>
                ) : (
                  /*
                    THE EMPTY STATE THIS CARD NEVER HAD. Opened with nothing chosen it used simply to
                    show a file picker, which was its own explanation while the picker lived here.
                    With the picker above, an unexplained panel of inert controls is exactly the
                    "control that vanishes is indistinguishable from a feature this build does not
                    have" failure its neighbour was written to end — so it says what is missing and
                    where the one control that supplies it is. Same treatment as that card's own
                    empty sentence: an icon, one sentence, and nothing else.
                  */
                  <span className="flex items-start gap-2">
                    <ImageIcon className="mt-0.5 h-3.5 w-3.5 shrink-0 text-ink-500" aria-hidden />
                    <span>
                      No photograph has been chosen yet. Choose one in “Photograph of the sketch” above — it is the
                      same photograph this panel traces and the panel below measures.
                    </span>
                  </span>
                )}
              </p>
            </div>
          ) : (
            <div className="mb-3">
              {/*
                A DROP CARD RATHER THAN A BARE `<input type="file">`, WHICH IS THE OWNER'S THIRD REQUEST —
                and the button did not go away, it moved inside the card. See `DropCard`'s header: file drop
                does not exist on touch at all (`dragover` never fires on Android Chrome, and a handset has
                no second window to drag a file out of), so on the device most of this fieldwork happens on,
                the tap is the only route there is. A card with only a drop would be a regression on the
                input it replaced.

                THIS ALSO CLOSES A LIVE INCONSISTENCY. The input this replaces never cleared
                `event.target.value`, so re-choosing the same photograph after a refusal fired no `change`
                event and the panel did nothing at all — `WorkshopCodeScanner` and `PrototypeModelField`
                both clear it and both say why. `DropCard` clears it for every caller.
              */}
              <DropCard
              label="Photograph to trace"
              buttonLabel="Choose a photograph"
              accept={TRACEABLE_ACCEPT}
              // KEPT WORD FOR WORD IN STEP WITH `SharedPhotoField`'s copy of this card, which is the
              // one a designer normally meets; this branch draws only where that picker is absent.
              // Both were shortened together on 2026-09-03 under the owner's one-line copy rule.
              acceptSentence={`${TRACEABLE_IMAGE_TYPES}. Anything over ${DECODE_MAX_EDGE_PX}px on the long edge is reduced before tracing.`}
              disabled={disabled}
              /*
                THE RULE, NOT THE FILTER. `accept="image/*"` is what the dialog offers and a drop ignores
                it entirely, so this is what actually decides — and it decides PERMISSIVELY on purpose.
                A phone camera roll hands over HEIC and AVIF with an EMPTY `type` on several platforms,
                so refusing anything without an image MIME type would refuse the commonest file on an
                iPhone. `decodeToPixels` already answers "this browser cannot read that image" in a
                sentence, which is the honest place for that judgement: it is the code that tried.

                What IS refused here is the file that is definitely not a photograph of a sheet — a
                video, a PDF, a document — and the SVG, for the reason `decodeToPixels`'s own header
                gives at length: tracing vector art is a round trip that can only lose.
              */
              validate={(candidate) => {
                if (candidate.type === "image/svg+xml") {
                  return "an SVG is already vector art — attach it with the ordinary picker instead.";
                }
                if (candidate.type === "" || candidate.type.startsWith("image/")) return null;
                return `this is ${candidate.type}, not a photograph. A drawing has to be traced from an image.`;
              }}
              onFiles={(files) => {
                const chosen = files[0];
                if (chosen) void chooseFile(chosen);
              }}
            >
              {file ? (
                <p className="text-xs leading-4 text-ink-500">
                  <span className="font-medium text-ink-900">{file.name}</span>
                  {pixels && (pixels.sourceWidth !== pixels.width || pixels.sourceHeight !== pixels.height)
                    ? ` — read at ${pixels.width}x${pixels.height}, reduced from ${pixels.sourceWidth}x${pixels.sourceHeight}.`
                    : pixels
                      ? ` — ${pixels.width}x${pixels.height}.`
                      : " — reading…"}
                </p>
              ) : null}
              </DropCard>
            </div>
          )}

          {/* ── What the trace is framed to ─────────────────────────────────── */}
          {/*
            ── THE FRAME'S ONE LINE STAYS ON THE PRIMARY PATH; THE CHOOSER DOES NOT ──────────────
            The chooser itself is a configuration surface and lives in the disclosure below with
            everything else the owner asked to have folded away. This row does not, and the handset
            argues why in a comment beside its own copy of it (`DwSketchTraceCropPanel.kt:188`): "TRUE
            WHETHER OR NOT THIS IS OPEN. A closed control that says nothing about its own state is a
            control a designer has to open to find out whether they touched it, and this one changes
            what the drawing IS." A crop is destructive — everything outside it is absent from the
            drawing — so it is the one setting that may never be invisible. §1.10.

            "Choose a frame" is the handset's word for the control that opens it
            (`DwSketchTraceCropPanel.kt:207`), and it opens the SAME disclosure the toggle below does
            rather than a second one; it only differs in moving focus, because the chooser is a long
            way down inside it.

            THE MULTIPLICATION SIGN HERE AND A LETTER `x` IN THE CHOOSER IS A KNOWN DIVERGENCE, not a
            slip: this is the handset's screen typography (`dwTraceCropReadout`), and `FramePanel`'s
            own readout keeps the letter because `e2e/sketch-trace-panel.spec.ts:1348` pins that
            string. Reconciling them is that spec's owner's edit, not this file's.
          */}
          {pixels ? (
            <div className="mb-3 flex flex-wrap items-start gap-3 rounded-md border border-line-200 bg-card p-3">
              <span className="mt-0.5 grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-field-200 text-field-600">
                <Crop className="h-4 w-4" aria-hidden />
              </span>
              <div className="min-w-0 flex-1">
                <p className="text-xs font-semibold text-ink-900">The part of the photograph to trace</p>
                <p className="mt-0.5 text-xs leading-5 text-ink-500">
                  {edited === null
                    ? "The whole photograph."
                    : `${edited.crop.width}×${edited.crop.height} of ${pixels.width}×${pixels.height}.`}
                  {edited !== null && edited.sharpen.amount > 0
                    ? " Sharpened on this device before tracing."
                    : ""}{" "}
                  The photograph itself is never altered.
                </p>
              </div>
              <button
                type="button"
                className="field-button-secondary"
                onClick={chooseFrame}
                disabled={disabled}
                aria-expanded={advancedOpen}
                aria-controls={advancedMounted ? advancedId : undefined}
              >
                <Crop className="h-4 w-4" aria-hidden />
                {advancedOpen ? "Done" : "Choose a frame"}
              </button>
            </div>
          ) : null}

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
                      {stopping ? "Stopping…" : (progress ?? "Tracing…")}
                    </>
                  ) : null}
                </span>
              </div>
              {/*
                ── THE BAR AND THE STOP, WHICH ARE ONE ROW BECAUSE THEY ANSWER ONE QUESTION ──────

                "Is this thing still going, and can I get out of it." The panel used to answer neither:
                the engine's `fraction` was received on every event and discarded, and cancellation
                existed with nothing to press. A full-resolution trace of a 12 MP photograph is seconds
                of solid arithmetic and the stage that dominates it is `edge`, so a label with no bar
                reads as a hang there.

                DRAWN ONLY WHEN THERE IS SOMETHING TO DRAW. `progressAt` is null for a preview, because
                `trace.worker.ts` hands `Pipeline.runPreview` no progress callback at all — so this row
                belongs to the presses, which are the runs long enough to want it.
              */}
              {tracing && progressAt !== null ? (
                <div className="mt-2 grid gap-1">
                  <div className="h-1 w-full overflow-hidden rounded-full bg-field-200">
                    <div
                      className="h-full rounded-full bg-primary transition-[width] duration-200"
                      style={{ width: `${Math.round(progressAt * 100)}%` }}
                    />
                  </div>
                  {/* A BAR THAT WILL VISIBLY STALL, SAYING SO. Until this machine has finished one
                      trace the boundaries are the engine's even twelfths, and the two long stages are
                      worth several of the others put together. One line costs less than a designer
                      deciding the panel has frozen. */}
                  {!weights.measured ? (
                    <p className="text-xs leading-4 text-ink-500">{PROGRESS_UNMEASURED_NOTE}</p>
                  ) : null}
                </div>
              ) : null}
              {tracing ? (
                <div className="mt-2">
                  <button
                    type="button"
                    className="rounded-md border border-line-200 bg-card px-2 py-1 text-xs font-medium text-ink-700 transition hover:border-purple-300 disabled:opacity-60"
                    onClick={stopTrace}
                    disabled={stopping}
                  >
                    {stopping ? "Stopping…" : "Stop"}
                  </button>
                </div>
              ) : null}
              {/*
                ── THE ONE SIZE THAT IS DELIBERATELY NOT THE MEASURING CARD'S ────────────────────

                420px, and the measuring panel below sets no such ceiling on its own viewport. That
                is not an oversight in either direction: this canvas is a picture to LOOK at, and a
                drawing taller than a phone screen pushes the attach buttons under it out of reach —
                whereas that viewport is a surface to place marks ON, and every pixel of height it
                loses is precision lost off a measurement, which is why it takes the room it is
                given and offers a pinch-zoom on top. Matching them would make one card worse to
                answer a complaint about the other. `PREVIEW_BOX_PX` above is the same number for the
                live preview, and both are the handset's.
              */}
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
                  This is a preview at a smaller working size. Attaching or downloading re-traces at full
                  resolution first.
                </p>
              ) : null}
            </div>
          ) : null}

          {/* ── The trace against the photograph ───────────────────────────── */}
          {/*
            THE COMPARATOR THE OWNER ASKED FOR, OPENING ON THE TRACE.

            `Reveal1` clips the BEFORE layer by `position`, so the trace is passed as `afterImage` with
            `initialPosition={0}` (`COMPARE_START_POSITION`) — the drawing fills the frame, the divider
            sits hard against the leading edge, and dragging reveals the photograph underneath. Passing
            them the other way round is the obvious mistake and the component's own header says so.

            NO `heading` PROP, ON PURPOSE. It renders an `<h2>`, and this panel's own heading is an
            `<h4>`; a nested h2 would jump the document's heading levels backwards inside one card,
            which is a real navigation fault for a screen-reader user and buys nothing here. The card's
            `field-label` names it visually and `ariaLabel` names it to a reader.
          */}
          {pixels ? (
            <div className="mb-3 rounded-md border border-line-200 bg-card p-3">
              <div className="flex items-center justify-between gap-2">
                <span className="field-label">The trace against the photograph</span>
                {/* One place for every "why is there no comparison" sentence, and empty when there is
                    one — a live region that appears with its text already in it is announced by
                    nothing, and one that says "blank" costs a reader a sentence for no information.
                    Same arrangement as the tracing spinner above. */}
                <span aria-live="polite" className="text-xs text-ink-500">
                  {comparisonStatus}
                </span>
              </div>
              {compare ? (
                <>
                  {/*
                    THE FOUR VIEWS, AS THE HANDSET'S CHIP ROW. Three of the four labels are its words
                    exactly (`DwSketchTraceCompare.kt:292-306`); the fourth is new on both clients and
                    is named the same on both. `aria-pressed` rather than a radio group, matching the
                    "Attach as" row below and the handset's own chips: these are four ways of looking
                    at one thing, not four values of a field the panel is about to write.
                  */}
                  <div className="mt-2 flex flex-wrap gap-2">
                    {COMPARE_MODES.map((entry) => (
                      <button
                        key={entry.id}
                        type="button"
                        className={
                          compareMode === entry.id
                            ? "rounded-md border border-purple-600 bg-purple-50 px-3 py-1.5 text-xs font-medium text-purple-800"
                            : "rounded-md border border-line-200 bg-card px-3 py-1.5 text-xs font-medium text-ink-700 transition hover:border-purple-300"
                        }
                        aria-pressed={compareMode === entry.id}
                        onClick={() => (entry.id === "difference" ? showDifference() : setCompareMode(entry.id))}
                      >
                        {entry.label}
                      </button>
                    ))}
                  </div>
                  <Reveal1
                    className="mt-2"
                    beforeImage={{
                      src: compare.originalUrl,
                      alt: file ? `The photograph ${file.name}, as the tracing engine read it` : "The photograph"
                    }}
                    afterImage={{ src: compare.traceUrl, alt: "The traced drawing, on white" }}
                    beforeLabel="Photograph"
                    afterLabel="Traced drawing"
                    /*
                      CONTROLLED, WHICH IS WHAT MAKES THE CHIPS POSSIBLE. The two end states are this
                      one number written to 0 and 100 — and they are written to the DISPLAYED position
                      only, because `comparePosition` is where the designer left the seam and pressing
                      Wipe again has to come back to it. `initialPosition` is still passed for the
                      component's own default, which nothing here reads.
                    */
                    position={
                      compareMode === "drawing" ? 0 : compareMode === "photograph" ? 100 : comparePosition
                    }
                    onPosition={(next) => {
                      setComparePosition(next);
                      // MOVING THE SEAM IS ASKING FOR THE WIPE. A designer who drags while "Drawing" is
                      // selected has just told the panel which view they want, and a chip row that then
                      // disagreed with the picture would be a control claiming something untrue.
                      setCompareMode("wipe");
                    }}
                    initialPosition={COMPARE_START_POSITION}
                    ariaLabel="Traced drawing against the photograph"
                    maxZoom={COMPARE_MAX_ZOOM}
                    peekHoldMs={REVEAL_PEEK_HOLD_MS}
                    /*
                      THE DESCRIPTION AND THE BADGE ARE BOTH THE HANDSET'S, and both are constants
                      rather than literals for the reason the note below them is: this frame and
                      `DwSketchTraceCompare.kt`'s frame show one picture to one designer, and the two
                      apps drifting apart on what they call it is the failure the four-renderer rule
                      exists to prevent. The badge is not decoration here — a difference plate of a
                      GOOD trace is very nearly black, and a nearly black frame with no word on it is
                      indistinguishable from a plate that failed to draw.
                    */
                    soloImage={
                      compareMode === "difference" && difference !== null
                        ? {
                            src: difference,
                            alt: COMPARISON_DIFFERENCE_ALT,
                            label: COMPARISON_DIFFERENCE_BADGE
                          }
                        : null
                    }
                    // The photograph's own ratio, so neither layer is centre-cropped. Without it the
                    // frame is 16:9 and a portrait A4 sheet loses most of the drawing off the top and
                    // bottom — see the component's note on `aspectRatio`.
                    aspectRatio={compare.width / compare.height}
                  />
                  {/*
                    THE INSTRUCTION IS WRITTEN OUT BECAUSE THE GESTURE IS NOT DISCOVERABLE, and the
                    keyboard half is written out because it exists: `RankableList.tsx` in this same
                    directory states the rule — "a drag is a pointer gesture and is unreachable from a
                    keyboard, from a switch device and from a screen reader" — and the comparator
                    answers to the arrow keys, Home and End for exactly that reason. A hint nobody can
                    see is a feature nobody can reach. The hold and the magnifier are on the same list
                    for the same reason: the handset says its own two out loud under its own frame.
                  */}
                  <p className="mt-2 text-xs leading-5 text-ink-500">
                    The drawing is shown first. Drag the handle — or focus it and use the arrow keys, Home and
                    End — to reveal the photograph underneath. Press and hold the picture to see the photograph,
                    and let go to come back: the seam stays where you left it. Hold Ctrl (or ⌘) and scroll to
                    magnify up to {COMPARE_MAX_ZOOM}×, or press + and − with the frame focused and 0 to go back
                    to fit; magnified, dragging moves the picture instead of the seam. The comparison paints the
                    drawing on white so it is visible over the photograph; the file that is attached or
                    downloaded keeps whatever background you chose.
                    {compare.reduced
                      ? ` Both pictures here are ${compare.width}x${compare.height}, reduced from ${
                          result ? `${Math.round(result.width)}x${Math.round(result.height)}` : "the traced size"
                        } for the comparison only.`
                      : ""}
                  </p>
                  {/*
                    THE DIFFERENCE VIEW'S OWN SENTENCE, SAID ONLY WHERE IT IS TRUE. The plate is black
                    where the two agree and bright where they do not, which is not what anybody expects
                    a picture of their sketch to look like — printed under every view it would be four
                    lines a designer learns to skip, and skipping is how the sentence that matters gets
                    missed. `COMPARISON_DIFFERENCE_NOTE` is the handset's wording verbatim.
                  */}
                  {compareMode === "difference" ? (
                    <p aria-live="polite" className="mt-2 text-xs leading-5 text-ink-500">
                      {differenceProblem !== null
                        ? differenceProblem
                        : difference === null
                          ? COMPARISON_DIFFERENCE_PENDING
                          : COMPARISON_DIFFERENCE_NOTE}
                    </p>
                  ) : null}
                </>
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
                  /* NO "Engine defaults" ROW — see the load effect. It was a row that could not be
                     chosen, on a control that opened claiming it. */
                  options={[
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
                {/* THE HANDSET'S LABEL, WHICH IS THE PLAINER ONE. `DwSketchTraceParams` calls this
                    "What this is a drawing of"; the portal said "Subject", which is the engine's word
                    for the table and not a designer's word for the thing in front of the camera. */}
                <span className="field-label" id={`${panelId}-subject-label`}>
                  What this is a drawing of
                </span>
                <Dropdown
                  value={subjectId}
                  onChange={(next) => {
                    setSubjectId(next);
                    if (next) applySubject(next);
                  }}
                  disabled={disabled || subjects.length === 0}
                  ariaLabel="What this is a drawing of"
                  describedBy={`${panelId}-subject-hint`}
                  // No emptyLabel: the list always carries the "Choose a material" row, so the empty
                  // state is unreachable here and a sentence that cannot render is a sentence the next
                  // reader has to disprove. The panel says so at the top instead, in the notice that
                  // fires when the preset fetch fails.
                  placeholder="Choose a material"
                  searchable
                  /*
                    THE TEN HINTS, RENDERED — they were fetched and thrown away.

                    `loadTracePresets` has always carried `subject.hint`, the one sentence saying what
                    each subject actually changes ("Weave is periodic texture, not line work…"), and
                    nothing on this panel ever showed one: the control printed a single generic
                    paragraph instead, so choosing between ten materials was choosing between ten
                    names. `SelectOption.hint` puts it on the option row AND makes it searchable, which
                    is the behaviour §11.5 of the frontend reference describes and the handset already
                    has on both of its own preset pickers.
                  */
                  options={[
                    { value: "", label: "Choose a material" },
                    ...subjects.map((subject) => ({
                      value: subject.id,
                      label: subject.name,
                      hint: subject.hint
                    }))
                  ]}
                />
                <p id={`${panelId}-subject-hint`} className="text-xs text-ink-500">
                  {/* THE CHOSEN SUBJECT'S OWN SENTENCE ONCE THERE IS ONE, and the generic one only
                      while there is not. The handset shows the hint twice for the same reason — on the
                      row while choosing, and under the control afterwards, because the row is gone by
                      the time a designer wonders what they picked. */}
                  {subjects.find((subject) => subject.id === subjectId)?.hint ??
                    "A subject nudges the settings for the material in front of the camera. It leaves the style alone and can be applied more than once without compounding."}
                </p>
              </div>
            </div>
          ) : null}

          {/* ── What the engine thinks this photograph is ───────────────────── */}
          {/*
            THE CLASSIFICATION WAS ALREADY PAID FOR AND THROWN AWAY.

            `SerializedProfile.suggestion` is a style preset id, is never empty on a full trace, and
            crosses the worker boundary on every one of them — and this file contained no occurrence of
            "suggestion" or "profile" at all. So the engine looked at the photograph, formed an opinion,
            sent it, and the panel discarded it while a designer scrolled a twenty-item list.

            IT PROPOSES AND NEVER APPLIES. Applying a style REPLACES every setting — `pickStyle` says
            so — so a suggestion that applied itself would silently discard a designer's tuning at the
            exact moment their trace finished. The button is the application, which is the shape the
            handset uses and the shape the crop tool's "Use these corners" already established here.

            DRAWN ONLY WHEN THERE IS SOMETHING TO SAY: nothing on a preview, because previews do not
            classify and `profile` is null for them, and nothing when the engine agrees with the style
            already chosen.
          */}
          {suggestedStyle !== null ? (
            <div aria-live="polite" className="mb-3 rounded-md border border-line-200 bg-field-100 px-3 py-2">
              <p className="text-xs leading-5 text-ink-700">
                Looking at this photograph, the engine suggests the “{suggestedStyle.name}” style.{" "}
                {suggestedStyle.description}
              </p>
              <button
                type="button"
                className="field-button-secondary mt-2"
                onClick={() => pickStyle(suggestedStyle.id)}
                disabled={disabled || busy}
              >
                <Wand2 className="h-4 w-4" aria-hidden />
                Use the “{suggestedStyle.name}” style
              </button>
            </div>
          ) : null}

          {notice ? (
            <p role="alert" className="mb-3 rounded-md border border-line-200 bg-amber-100 px-3 py-2 text-xs text-amber-800">
              {notice}
            </p>
          ) : null}

          {/* ── Controls: the seven that decide what KIND of drawing comes out ── */}
          {/*
            `ESSENTIAL_KEYS`, AND THE TABLE DECIDES WHICH THEY ARE. Its own comment gives the rule —
            "each of the six changes the KIND of drawing that comes out, while the rest tune a drawing
            the designer already has" — plus sharpening as a seventh, because a courtyard photograph
            under one tube light is soft far more often than it is noisy. Grouped rather than flat for
            the reason the disclosure below is grouped: "which stage of the pipeline is this" is how a
            designer looks for a control.
          */}
          {params ? (
            <div className="mb-3">
              <span className="field-label mb-2 block">Controls</span>
              <ControlGroups
                params={params}
                advanced={false}
                disabled={disabled}
                modifiedSet={modifiedSet}
                onPatch={patchParams}
                idPrefix={panelId}
              />
            </div>
          ) : null}

          {/* ── The one disclosure ─────────────────────────────────────────── */}
          {/*
            ══ EVERYTHING ELSE, BEHIND ONE PRESS ═══════════════════════════════════════════════════

            The owner's brief for this change names the control: "advanced/configuration settings are
            placed inside an internal accordion with an action such as 'Show more options'". So the
            closed label is that phrase rather than the handset's "Show everything (N more)" — a
            deliberate deviation from §1.3, on the owner's own words — while the OPEN label stays the
            handset's sentence, which reads correctly on both clients and already shipped here.

            THE NUMBER IS `ADVANCED_COUNT` AND IS WRITTEN NOWHERE ELSE. This file's header records what
            a hand-typed total costs: it "claimed twenty-nine while the table held thirty-two", and the
            button before this one said "Show all 32 controls" while seven of the thirty-two were
            already on screen. `ADVANCED_COUNT` is `SLIDERS + TOGGLES + CHOICES` filtered on
            `!isEssential`, so it is the count of what this press actually reveals, by construction.

            AND THE SETTINGS ARE NOT ALL THAT IS IN HERE, so the line under the toggle says what else
            is — the frame chooser and the take-away formats. A disclosure that names only part of what
            it holds is the same silence as a list that stops without saying so (§1.10).

            NO HEIGHT ANIMATION, AND THAT IS A DECISION. The contents must survive being collapsed
            (see `advancedMounted`), which rules out the `AnimatePresence` height spring the guide's
            cards use — that primitive animates a subtree in and out of existence. What is left is the
            chevron, whose `transition-transform` is CSS and is therefore already reached by BOTH
            reduced-motion sources in `globals.css`; there is no framer-written inline style here for
            `useAppReducedMotion()` to have to branch on (§8.4).
          */}
          {params ? (
            <div className="mb-3 rounded-md border border-line-200 bg-surface-50 p-3">
              <button
                type="button"
                id={advancedToggleId}
                className="inline-flex items-center gap-1.5 text-xs font-medium text-purple-700 transition hover:text-purple-800"
                onClick={toggleAdvanced}
                aria-expanded={advancedOpen}
                /* ONLY WHILE THE PANEL IS MOUNTED (§17). Before the first press the id names nothing,
                   and pointing at a missing element is worse than not pointing. */
                aria-controls={advancedMounted ? advancedId : undefined}
              >
                <Sliders className="h-3.5 w-3.5" aria-hidden />
                {advancedOpen
                  ? `Hide the other ${ADVANCED_COUNT} settings`
                  : `Show more options · ${ADVANCED_COUNT} settings` +
                    (hiddenModified.length > 0 ? ` · ${hiddenModified.length} changed` : "")}
                <ChevronDown
                  className={
                    advancedOpen
                      ? "h-3.5 w-3.5 rotate-180 transition-transform"
                      : "h-3.5 w-3.5 transition-transform"
                  }
                  aria-hidden
                />
              </button>

              {!advancedOpen ? (
                <p className="mt-2 text-xs leading-5 text-ink-500">
                  Inside: the part of the photograph to trace, the {ADVANCED_COUNT} settings that are
                  not above, and the formats you can download a copy in. Nothing in there is required —
                  the trace runs on the settings shown above.
                </p>
              ) : null}

              {/* Progressive disclosure is only honest if what it hides can still announce itself.
                  THE HANDSET'S SENTENCE, VERBATIM — "not on screen" rather than "hidden", which is
                  its deliberate choice and the better one on both clients: "hidden" points a designer
                  at this one disclosure, and on the handset one whole tier of controls lives on a
                  different step of the panel entirely. What is true on both is that the control is not
                  in front of them. The count is on the toggle as well, because a designer who has
                  learned to skip a paragraph still reads the button they are about to press. */}
              {!advancedOpen && hiddenModified.length > 0 ? (
                <p className="mt-2 text-xs leading-5 text-amber-800">
                  {hiddenModified.length === 1
                    ? `One setting that is not on screen has moved: ${hiddenModified[0]}.`
                    : `${hiddenModified.length} settings that are not on screen have moved: ${hiddenModified.join(", ")}.`}
                </p>
              ) : null}

              {advancedMounted ? (
                <div
                  id={advancedId}
                  ref={advancedRef}
                  /* `tabIndex={-1}` makes it focusable by script and not by Tab — what a deliberate
                      focus move needs, and what a tab stop on a container would get wrong. Named by
                      the toggle, so a reader who lands here is told which press opened it. */
                  tabIndex={-1}
                  aria-labelledby={advancedToggleId}
                  hidden={!advancedOpen}
                  className="mt-3 grid gap-4 focus:outline-none focus-visible:ring-2 focus-visible:ring-purple-600/40"
                >
                  {/*
                    `onEdited={setEdited}` IS THE WHOLE WIRING, and it is a bare setter deliberately.
                    That panel resets the frame in an effect whose dependency array contains this
                    callback, so an inline arrow function would give it a new identity on every render
                    and the effect would reset the frame, on a loop, forever. A `useState` setter is
                    stable for the life of the component — and an `EditedFrame` is an object rather
                    than a function, so React cannot read it as an updater.
                  */}
                  {pixels ? <FramePanel pixels={pixels} disabled={disabled} onEdited={setEdited} /> : null}

                  {/* The same five group headings as above, holding the controls that were not
                      essential. Two fieldsets can therefore carry one legend — "Edges" appears in both
                      — and that is right rather than confusing: the taxonomy is the pipeline's, and
                      splitting it by importance instead would mean a designer looking for a cleanup
                      control had to know whether somebody had called it essential. */}
                  <ControlGroups
                    params={params}
                    advanced
                    disabled={disabled}
                    modifiedSet={modifiedSet}
                    onPatch={patchParams}
                    idPrefix={panelId}
                  />

                  {downloads}
                </div>
              ) : null}
            </div>
          ) : null}

          {/* ── Format and attach ──────────────────────────────────────────── */}
          <div className="grid gap-1">
            <span className="field-label">Attach as</span>
            {/* TWO CHIPS, NOT FIVE, AND THE TABLE DECIDES WHICH. `ATTACHABLE_FORMATS` is
                `EXPORT_FORMATS` filtered on `attachable`, so a format moves between this row and the
                download row below by one boolean and never by editing JSX in two places. Why the
                other three are not here is argued in `traceExport.ts`'s header: the record is a shared
                archive the handset also reads, and a `.dxf` filed on it is a file that client can
                neither produce nor preview. */}
            <div className="flex flex-wrap gap-2">
              {ATTACHABLE_FORMATS.map((entry) => (
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
            <p className="text-xs text-ink-500">{ATTACHABLE_FORMATS.find((e) => e.id === format)?.hint}</p>
            {/*
              WHAT THE CHOICE DOES NOT CHANGE, SAID ONCE FOR BOTH CHIPS RATHER THAN INSIDE ONE HINT.
              It used to be the tail of the PNG hint, which put a claim about the REPORT on the format
              a designer was about to switch away from. Verified 2026-08-27 against the three files the
              frontend contract names as the authority on what a report contains:
              `report_builder.format_value` prints a FILE as "1 document attached",
              `_image_sources` skips every field that is not IMAGE/IMAGE_LIST, and
              `_render_media_annexure` gathers through `_images` — so the annexure is photographs and
              the attached bytes stay in the workshop record. Re-check with
              `grep -n "_image_sources\|_render_media_annexure" backend/app/services/report_builder.py`.
            */}
            <p className="text-xs leading-5 text-ink-500">
              Either form is filed as an attachment on the sketch, and the choice does not change what the
              ministry report shows: the report prints the sketch photograph, names the attached file and
              does not carry it. The file is in the workshop record for whoever opens it there.
            </p>
            {/* A CAP STATED WHERE IT BITES. The SVG carries the frame in its provenance note (see
                `provenanceFor`); a PNG has no channel for it, so a cropped or sharpened trace filed as
                a PNG records how it was made nowhere but on this screen. §1.10: skipped work is said
                out loud, and this is skipped work in a file rather than on a list. */}
            {format === "png" && edited !== null ? (
              <p className="text-xs leading-4 text-amber-800">
                The frame you chose is written into the SVG&apos;s provenance note. A PNG has nowhere to
                carry it, so a reviewer holding only the PNG cannot tell it was cropped or sharpened.
              </p>
            ) : null}
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
              disabled={disabled || busy || result === null || file === null}
            >
              {running === "attach" ? (
                <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
              ) : (
                <ImageIcon className="h-4 w-4" aria-hidden />
              )}
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
                disabled={disabled || busy || file === null}
              >
                <ImageIcon className="h-4 w-4" aria-hidden />
                Attach the photograph only
              </button>
            ) : (
              <button type="button" className="field-button-secondary" onClick={() => setOpen(false)} disabled={busy}>
                Keep the photograph only
              </button>
            )}
          </div>
          <p className="mt-2 text-xs text-ink-500">
            {onAttachSource
              ? // BOTH CLOSE CONTROLS ARE NAMED, since this panel gained the one at its foot. The
                // sentence used to say "the ×" and mean "any way out of here", which was true while
                // there was only one — and a designer who put the panel away from the bottom would
                // have been told nothing about what that did.
                "Nothing is filed until one of these is pressed. “Attach the photograph only” files the photograph exactly as it was taken and stops there; adding the line art files both, the photograph unaltered beside the drawing. Closing this panel — with the × above or “Collapse” below — files neither."
              : "Declining costs nothing: the photograph you attached stays exactly as it is, and a drawing can be traced from it later."}
          </p>

        </>
      ) : null}

      {/*
        ── THE COLLAPSE AT THE FOOT, WHICH THIS CARD OWED ITS READER AND ITS NEIGHBOUR ALREADY HAD ─

        This panel is the taller of the two by a wide margin — a picker, a frame readout, two preset
        pickers, the essential controls, the result, a comparator, five download buttons behind a
        disclosure and a row of attach buttons — so a designer who has just pressed the last of them
        is at the BOTTOM of all of it, and a close control only in the header means scrolling back up
        past everything they have finished with to put it away. `MeasureFromPhotoCard` carries the
        same control for the same complaint, raised about the handset's panel
        (`DwPhotoMeasureField.kt:618`), and this is the pair of it.

        A REAL BUTTON WITH THE CARD'S NAME IN IT, not a bare "Close": at the foot of a panel this long
        there is no heading in view to say what would be closing, and this is one of several stacked
        disclosures on the tab. It closes by the same path the header × does — `setOpen(false)`, whose
        effect hands focus back to the trigger — so the two controls are one act rather than two that
        can disagree about what is open.
      */}
      <div className="mt-3">
        <button
          type="button"
          className="inline-flex items-center gap-1.5 text-xs font-medium text-ink-500 underline"
          onClick={() => setOpen(false)}
        >
          <ChevronUp className="h-3.5 w-3.5" aria-hidden />
          Collapse “{CARD_TITLE}”
        </button>
      </div>
      </div>
    </section>
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
    /*
      `mt-3` AND NOT `mt-2`, WHICH IS THE FOURTH OF THE FOUR SIZE MISMATCHES. The measuring card
      below is positioned by `UploadTabPanel` with `mt-3`; this panel supplies its own, because its
      OTHER host drops it into a column of registry fields with no wrapper to hang one on. One token,
      two owners — and one gap on screen, where there were two.
    */
    <div className="mt-3">
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

/**
 * The parameter table, drawn group by group — one half of it per call.
 *
 * ── ONE RENDERER FOR BOTH HALVES, WHICH IS WHAT KEEPS THEM ONE TABLE ────────────────────────────
 *
 * The panel draws the essential controls on the primary path and the rest inside the disclosure, and
 * the obvious way to write that is two blocks of JSX. Two blocks is two places to forget the
 * "you changed this" ring, the `aria-describedby`, or the `InertNote` — which is the same argument the
 * Rows section above makes for having one component per control KIND rather than one per control.
 * `advanced` is the ONLY difference between the two calls.
 *
 * THE FILTER IS `isEssential` AND NOTHING ELSE, so the two halves are exhaustive and disjoint by
 * construction: every row in `SLIDERS`/`TOGGLES`/`CHOICES` is drawn exactly once, and a control added
 * to the table lands in one of the two without anybody choosing. That is the property the count on the
 * disclosure's button depends on — `ADVANCED_COUNT` counts the same predicate.
 *
 * A group with nothing in it renders nothing, so "Sharpening" does not appear twice with one empty
 * fieldset; a group with controls on both sides appears in both, under the same legend, because the
 * taxonomy is the pipeline's stages and not this panel's idea of importance.
 */
function ControlGroups({
  params,
  advanced,
  disabled,
  modifiedSet,
  onPatch,
  idPrefix
}: {
  params: TraceParams;
  advanced: boolean;
  disabled?: boolean;
  modifiedSet: ReadonlySet<string>;
  onPatch: PatchFn;
  idPrefix: string;
}) {
  return (
    <div className="grid gap-4">
      {PARAM_GROUPS.map((group) => {
        const wanted = (key: string) => (advanced ? !isEssential(key) : isEssential(key));
        const sliders = SLIDERS.filter((s) => s.group === group && wanted(s.key));
        const toggles = TOGGLES.filter((t) => t.group === group && wanted(t.key));
        const choices = CHOICES.filter((c) => c.group === group && wanted(c.key));
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
                  onPatch={onPatch}
                  idPrefix={idPrefix}
                />
              ))}
              {sliders.map((spec) => (
                <SliderRow
                  key={spec.key}
                  spec={spec}
                  params={params}
                  disabled={disabled}
                  modified={modifiedSet.has(spec.label)}
                  onPatch={onPatch}
                  idPrefix={idPrefix}
                />
              ))}
              {toggles.map((spec) => (
                <ToggleRow
                  key={spec.key}
                  spec={spec}
                  params={params}
                  disabled={disabled}
                  modified={modifiedSet.has(spec.label)}
                  onPatch={onPatch}
                  idPrefix={idPrefix}
                />
              ))}
            </div>
          </fieldset>
        );
      })}
    </div>
  );
}

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
      <InertNote reason={inactiveReason(spec.key, params)} />
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
          <InertNote reason={inactiveReason(spec.key, params)} />
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
      <InertNote reason={inactiveReason(spec.key, params)} />
    </div>
  );
}

/**
 * "This control is doing nothing under your current settings", when it is.
 *
 * ── A SENTENCE, NEVER A DISABLED ROW ──────────────────────────────────────────────────────────
 *
 * `inactiveReason` reads the condition in `engine/pipeline.ts` that makes the claim true, and the
 * trap it exists for is not hypothetical: the MEDIAN noise filter reads a radius this panel does not
 * expose and never reads "Noise reduction" at all, and MEDIAN is what the `sketch` subject selects.
 * So a designer could drag that slider for a minute on the commonest configuration this panel has and
 * conclude the trace was broken. Nothing on this client said so.
 *
 * The row stays writable, because greying it out would stop somebody setting a value for the
 * configuration they are about to switch to — which is exactly what comparing two edge engines is.
 *
 * NOT A LIVE REGION. It changes as a consequence of a control the designer just operated, in the same
 * commit, so a reader who moved the switch is told by the switch; announcing it as well would talk
 * over them. It is inside the row and reached in the ordinary way.
 */
function InertNote({ reason }: { reason: string | null }) {
  if (reason === null) return null;
  return <p className="mt-0.5 text-xs leading-4 text-amber-800">{reason}</p>;
}

/** Re-exported so a host can name the essentials without importing the table. */
export { ESSENTIAL_KEYS };
