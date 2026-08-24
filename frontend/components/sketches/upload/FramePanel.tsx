"use client";

/**
 * "Frame and sharpen the photograph" — the crop tool and the sharpening control, requests 4 and 5.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS CHANGES, AND — SAID FIRST, BECAUSE IT IS THE PART THAT MATTERS — WHAT IT DOES NOT
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * **THE PHOTOGRAPH IS NEVER ALTERED, NEVER RE-ENCODED, AND NEVER REPLACED.** This panel changes
 * exactly one thing: which pixels the TRACE is run on. The `File` the designer chose is untouched from
 * the moment it is picked to the moment `onAttachSource` files it, byte for byte, with its EXIF, its
 * original resolution and its own checksum.
 *
 * That is a design constraint rather than a preference, and it comes from three places that agree:
 *
 *  1. `docs/MEDIA_PIPELINE.md` §5 forbids exactly the operation requests 4 and 5 describe, as a
 *     silent default: "the original file *is* the artifact, and re-encoding through a canvas destroys
 *     full resolution and strips the EXIF the app deliberately preserves … If it is ever wanted, it
 *     should be an explicit, off-by-default … mode that transplants the original EXIF onto the resized
 *     JPEG and records `extraMetadata.downscaledFrom` — never a silent default."
 *  2. Stage 11 declares exactly ONE image slot — `sketch.image` — and a single IMAGE field REPLACES its
 *     value when a file is attached to it. So a cropped photograph filed as "the photograph" would
 *     detach the original. `UploadTabPanel`'s two separate callbacks exist to make that impossible.
 *  3. A second image slot for a derivative is a registry change in four places plus the Android bundled
 *     asset, which this wave is explicitly not permitted to make.
 *
 * So the honest half was built: **the crop and the sharpen are TRACE INPUTS.** They are computed on
 * this device, they change the drawing that lands in the line-art field, they are recorded in that
 * drawing's provenance note, and they produce no file of their own. Nothing here can displace
 * anything, because nothing here can produce something the upload door would take —
 * `lib/trace/imageEdit.ts`'s header states the same property from the arithmetic's side.
 *
 * ── "USABLE WHILE UPLOADING AND AFTERWARDS": WHAT EACH HALF ACTUALLY IS ─────────────────────────
 *
 * **While uploading — built.** The photograph is chosen in this panel and nothing has been filed yet.
 * Frame it, sharpen it, trace it, attach. This is the whole of the flow on the UPLOAD tab.
 *
 * **Afterwards — built, for the meaning this wave can honour.** `SketchTraceField` deliberately keeps
 * the chosen photograph and its decoded pixels when the panel closes, so after attaching, a designer
 * reopens the panel, moves the frame, and attaches a second drawing traced from the new frame. The
 * photograph is filed once (`sourceFiledRef`) and is not offered again; only the line art is added.
 *
 * **Afterwards, in the OTHER sense — NOT built, and it is not a rounding error.** Re-cropping a
 * photograph that is already in object storage is a different operation: the bytes would have to be
 * fetched back from S3, decoded, cropped, re-encoded and then filed — and filed WHERE is the question
 * with no answer today. Over the original, which §5 refuses; or into a second image slot, which does
 * not exist. It is reported to the owner rather than half-built here.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE INTERACTION, AND WHY IT IS NOT ONLY A DRAG
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `components/sketches/RankableList.tsx:6-13`, the sibling in this directory, states the rule this
 * follows: "a drag is a pointer gesture and is unreachable from a keyboard, from a switch device and
 * from a screen reader, so drag ALONE would put the one judgement this feature exists to record behind
 * a mouse." So the crop has three complete routes, and the numeric one is the primary:
 *
 *  · FOUR NUMBER INPUTS — Left, Top, Width, Height, in the photograph's own pixels, each a real
 *    `<input type="number">` with a real `<label htmlFor>`. This is the route that works on every
 *    device and for every input method, and it is the one that can be read out.
 *  · FOUR CORNER HANDLES — real `<button>`s, so they are tab stops with the global focus ring, and
 *    they take the arrow keys (1 px, or 10 with Shift) as well as a pointer drag.
 *  · A POINTER DRAG ON THE BOX to move it.
 *
 * Pointer events rather than the HTML5 drag API, for `RankableList`'s measured reason:
 * `dragstart`/`dragover` do not fire for touch at all on Android Chrome.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY ONE BUTTON APPLIES BOTH, INSTEAD OF THE CROP BEING LIVE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * A crop is a row-wise `memcpy` and could be live. A sharpen is two separable Gaussian passes over up
 * to eight million pixels and cannot be — and once a sharpen has been applied, moving the crop would
 * silently re-run it. A live crop would therefore mean either a multi-second job started by a drag, or
 * two different rules for two controls sitting side by side, one of which recomputes and one of which
 * does not. So: the overlay is the feedback for the frame, one button commits both, and the panel says
 * plainly when what is on screen is no longer what the trace is using. That sentence is the §1.10 rule
 * — a control whose effect has silently gone stale is indistinguishable from a control that does
 * nothing.
 */

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { AlertTriangle, Check, Crop, Loader2, Sparkles } from "lucide-react";

import { resampleRgbaInBands } from "./comparisonPlates";
import type { DecodedPixels } from "./decodeToPixels";
import {
  loadImageEditor,
  type CropRect,
  type ImageEditor,
  type SharpenSettings
} from "./traceRuntime";

/** The longest edge the framing preview is drawn at. Big enough to aim a crop, small enough to be free. */
const PREVIEW_BOX_PX = 360;

/**
 * The pure arithmetic this panel drives, re-declared as constants rather than imported.
 *
 * `lib/trace/imageEdit.ts` imports `engine/contrast` and `engine/convolve`, so importing it at the top
 * of a component would put the convolution code on the sketches page's bundle for every visitor who
 * never touches a photograph — `SketchTraceField`'s fourth property, and the rule
 * `.claude/skills/gsap/SKILL.md` §2 already enforces on this repository's one 70 KB library. The
 * arithmetic is reached through {@link loadImageEditor}'s worker; the four numbers a slider needs in
 * order to draw itself are copied here, and `e2e/sketch-frame-sharpen-unit.spec.ts` asserts they still
 * match the module's own exports so the copy cannot drift.
 */
const SHARPEN_AMOUNT_MAX = 5;
const SHARPEN_RADIUS_MIN = 0.3;
const SHARPEN_RADIUS_MAX = 8;
const SHARPEN_THRESHOLD_MAX = 0.2;
const SHARPEN_MAX_PIXELS = 8_000_000;
const CROP_MIN_EDGE_PX = 16;

/** Off, matching `imageEdit.NO_SHARPEN` and the engine's own `unsharpAmount` default of 0. */
const NO_SHARPEN: SharpenSettings = { amount: 0, radius: 1.5, threshold: 0 };

/** What the panel hands back: the pixels to trace, and the sentence that says what was done to them. */
export interface EditedFrame {
  readonly data: Uint8ClampedArray;
  readonly width: number;
  readonly height: number;
  readonly crop: CropRect;
  readonly sharpen: SharpenSettings;
  /** For the exported SVG's provenance note. Empty when nothing was done. */
  readonly note: string;
  /** Measured on this device. Never an estimate. */
  readonly millis: number;
}

export interface FramePanelProps {
  /** The whole decoded photograph. Never modified. */
  pixels: DecodedPixels;
  disabled?: boolean;
  /**
   * The frame the trace should use, or `null` for "the whole photograph as decoded".
   *
   * Called on apply and on reset, and once with `null` whenever the photograph changes — a frame
   * chosen on one sheet is meaningless on the next, and leaving it applied would trace a region of a
   * photograph nobody framed.
   */
  onEdited: (frame: EditedFrame | null) => void;
}

type Corner = "nw" | "ne" | "sw" | "se";

interface Drag {
  readonly pointerId: number;
  readonly corner: Corner | "move";
  readonly startX: number;
  readonly startY: number;
  readonly start: CropRect;
}

export function FramePanel({ pixels, disabled, onEdited }: FramePanelProps) {
  const fieldId = useId();
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const editorRef = useRef<ImageEditor | null>(null);
  const goneRef = useRef(false);

  /*
    KEYED ON THE `pixels` OBJECT, NOT ON ITS TWO NUMBERS.

    `[pixels.width, pixels.height]` made this identity — and therefore the reset effect below — say
    "a photograph of a different SIZE arrived", when what has to be reset is "a different PHOTOGRAPH
    arrived". Two sheets shot on the same phone decode to the same numbers, so the whole safety of the
    reset rested on `SketchTraceField.chooseFile` setting `pixels` to null in between and unmounting
    this panel. That holds today and is one `await` away from not holding: a decode whose continuation
    batches with the pick's own state writes would commit the second photograph with no null between,
    and this panel would keep the first sheet's crop while its own live region read out the applied
    frame — with `setEdited(null)` upstream meaning the trace was running on the whole new sheet. The
    draw effect one block down already keys on `pixels` identity; so does this now, and the two agree.
  */
  const whole = useMemo<CropRect>(
    () => ({ x: 0, y: 0, width: pixels.width, height: pixels.height }),
    [pixels]
  );

  const [crop, setCrop] = useState<CropRect>(whole);
  /**
   * The box being typed into, and the characters in it — held as WRITTEN, not as a clamped number.
   *
   * WHY THE FOUR INPUTS CANNOT CLAMP PER KEYSTROKE, which is what they used to do. `clamp` is total:
   * it always returns a legal rectangle, so it always returns something to write back, so every
   * keystroke replaced what was typed with the answer to a half-finished number.
   *
   *  · LEFT AND TOP WERE INERT while the frame was whole. On a 3000px sheet with the crop at full
   *    width, `x` is clamped to `min(3000 - 3000, …)` = 0 — so typing 5 wrote 0, typing 50 wrote 0,
   *    and the box could not be typed into at all until Width had been reduced first. Nothing on
   *    screen said so.
   *  · WIDTH AND HEIGHT RETURNED A DIFFERENT NUMBER FROM THE ONE TYPED. Select-all and type 800: the
   *    first character is 8, `max(16, 8)` is 16, and the box now reads "16" — so the 0 that follows
   *    lands on it as 160 and the next as 1600. Typing 800 gave 1600.
   *
   * `PhotoMeasureField` holds its reference length as a raw string and coerces later for the same
   * reason; this is that pattern, narrowed to the one box that has focus. One draft is enough because
   * only one box can be focused, and leaving a box blurs it — which is where the clamp belongs.
   */
  const [draft, setDraft] = useState<{ key: keyof CropRect; text: string } | null>(null);
  /** Said out loud when a commit had to move a typed number, and why. §1.10: never silently. */
  const [clampNote, setClampNote] = useState<string | null>(null);
  const [sharpen, setSharpen] = useState<SharpenSettings>(NO_SHARPEN);
  /** What was last committed, so the panel can say when the controls have moved past it. */
  const [applied, setApplied] = useState<{ crop: CropRect; sharpen: SharpenSettings; millis: number } | null>(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [drag, setDrag] = useState<Drag | null>(null);

  /**
   * A NEW PHOTOGRAPH RESETS THE FRAME, AND SAYS SO UPWARDS.
   *
   * `whole` changes identity when the decoded PIXELS do — one decode, one object — which is exactly
   * "a different photograph" (or the same one re-decoded). Keeping a 1200x900 crop across a pick would
   * trace a corner of the new sheet, and `clampCrop` would make that legal rather than obvious.
   */
  useEffect(() => {
    setCrop(whole);
    setDraft(null);
    setClampNote(null);
    setSharpen(NO_SHARPEN);
    setApplied(null);
    setProblem(null);
    onEdited(null);
  }, [whole, onEdited]);

  /** A worker outlives the component that forgot it. Same contract as the tracer's. */
  useEffect(() => {
    return () => {
      goneRef.current = true;
      editorRef.current?.dispose();
      editorRef.current = null;
    };
  }, []);

  /* ────────────────────────────────────────────────────────────────────────────
   * Drawing the photograph
   * ──────────────────────────────────────────────────────────────────────────── */

  const scale = Math.min(1, PREVIEW_BOX_PX / Math.max(pixels.width, pixels.height));
  const boxWidth = Math.max(1, Math.round(pixels.width * scale));
  const boxHeight = Math.max(1, Math.round(pixels.height * scale));

  useEffect(() => {
    const canvas = canvasRef.current;
    if (canvas === null) return;
    canvas.width = boxWidth;
    canvas.height = boxHeight;
    // Asked for BEFORE the work, so a browser that will not give this page a 2d surface costs nothing
    // rather than sixteen million reads followed by a shrug. The continuation asks again, on the
    // element it finds there then — see the note inside it.
    if (canvas.getContext("2d") === null) return;
    let cancelled = false;
    /*
      DOWNSCALED IN ARITHMETIC, NOT BY THE CANVAS, AND IT IS `comparisonPlates` DOING IT.

      `putImageData` cannot scale, so the canvas route needs the FULL-SIZE plane on a second surface
      first — up to 32 MB of backing store for an 8 MP photograph, on the device least able to afford
      it. That file's own header makes the same argument for the same reason and adds the one this
      preview needs most: a nearest-neighbour reduction of a photograph of a pencil sketch drops the
      pencil, aliasing fine lines into a dotted mess, so a designer aiming a crop at a line would be
      aiming at an artefact. One box filter, one shared implementation, no second opinion about how
      this photograph looks small.

      IN BANDS, WITH THE PAGE THREAD GIVEN A TURN BETWEEN THEM — `resampleRgbaInBands` and not the
      synchronous `resampleRgba`. The cost of this pass is the SOURCE's size and not the 360px
      preview's: a 4096px photograph is 16.7 million pixels read once each, which is a long task on the
      page thread, and it lands at the worst possible moment — the commit that first shows this panel,
      straight after a decode, while the designer is waiting to see what they picked. Once per
      photograph is not often; a frozen frame is still a frozen frame. The banded version does
      identical arithmetic and merely lets go of the thread between bands. `comparisonPlates.ts` has
      the rest of the reasoning, including why this is not a worker.

      `createImageData` rather than `new ImageData(plane, w, h)` for the reason that file records: the
      constructor's typed-array parameter is declared over `ArrayBuffer` while a `Uint8ClampedArray` is
      declared over `ArrayBufferLike`, so the direct call needs a cast — over the one thing worth
      checking, which is that the array and the dimensions agree.
    */
    void (async () => {
      const small = await resampleRgbaInBands(
        pixels.data,
        pixels.width,
        pixels.height,
        boxWidth,
        boxHeight,
        () => cancelled || goneRef.current
      );
      // A NEW PHOTOGRAPH — OR AN UNMOUNT — WHILE THE OLD ONE WAS STILL BEING REDUCED. The canvas is
      // re-read rather than closed over: this continuation can resume after the element is gone, and
      // painting the previous photograph into the current preview is the one outcome worth guarding.
      if (small === null || cancelled || goneRef.current) return;
      const surface = canvasRef.current;
      if (surface === null || surface.width !== boxWidth || surface.height !== boxHeight) return;
      const target = surface.getContext("2d");
      if (target === null) return;
      const plate = target.createImageData(boxWidth, boxHeight);
      plate.data.set(small);
      target.clearRect(0, 0, boxWidth, boxHeight);
      target.putImageData(plate, 0, 0);
    })();
    return () => {
      cancelled = true;
    };
  }, [pixels, boxWidth, boxHeight]);

  /* ────────────────────────────────────────────────────────────────────────────
   * Moving the frame
   * ──────────────────────────────────────────────────────────────────────────── */

  /**
   * Clamp a rectangle to the photograph, in whole pixels, at least {@link CROP_MIN_EDGE_PX} on a side.
   *
   * A LOCAL COPY OF `imageEdit.clampCrop`, and the duplication is deliberate for the reason the
   * constants above are: importing that module here would pull the convolution code onto this page's
   * bundle.
   *
   * WHAT KEEPS THE TWO HONEST IS NOT A TEST OF THIS FUNCTION — it is not exported and this spec suite
   * has no React renderer to reach it through. It is that the WORKER clamps again with the real one,
   * and the worker's copy is the one that decides which pixels are read. This one only has to keep the
   * box on screen sane while a finger is down; if it and the real one ever disagree, the frame drawn
   * and the frame traced differ, which is why `SHARPEN_MAX_PIXELS` and `CROP_MIN_EDGE_PX` above ARE
   * checked against the module (`e2e/sketch-frame-sharpen-unit.spec.ts`) — the constants are the part a
   * drift would hide.
   */
  const clamp = useCallback(
    (rect: CropRect): CropRect => {
      const minW = Math.min(CROP_MIN_EDGE_PX, pixels.width);
      const minH = Math.min(CROP_MIN_EDGE_PX, pixels.height);
      const w = Math.min(pixels.width, Math.max(minW, Math.round(rect.width)));
      const h = Math.min(pixels.height, Math.max(minH, Math.round(rect.height)));
      return {
        x: Math.min(pixels.width - w, Math.max(0, Math.round(rect.x))),
        y: Math.min(pixels.height - h, Math.max(0, Math.round(rect.y))),
        width: w,
        height: h
      };
    },
    [pixels.width, pixels.height]
  );

  /**
   * Take what is in one number box and make it the frame — clamping ONCE, at the end, out loud.
   *
   * Called on blur and on Enter, which is the moment a number has stopped being half-typed. When the
   * clamp had to move the value, {@link clampNote} says which box, what it became and what to do about
   * it: "Left was set to 0" with no explanation is the silence this whole draft mechanism exists to
   * end, and Left really is unmovable until Width is reduced.
   */
  const commitDraft = useCallback(
    (key: keyof CropRect, text: string) => {
      setDraft(null);
      const typed = Math.round(Number(text));
      // AN EMPTY OR UNREADABLE BOX RESTORES THE FRAME, and says nothing: clearing a field to retype it
      // is not a mistake to report, and `Number("")` is 0, which would silently jump the frame.
      if (text.trim() === "" || !Number.isFinite(typed)) {
        setClampNote(null);
        return;
      }
      const next = clamp({ ...crop, [key]: typed });
      setCrop(next);
      if (next[key] === typed) {
        setClampNote(null);
        return;
      }
      const minEdge = key === "width" ? Math.min(CROP_MIN_EDGE_PX, pixels.width) : Math.min(CROP_MIN_EDGE_PX, pixels.height);
      setClampNote(
        key === "x" || key === "y"
          ? `${CROP_FIELD_NAME[key]} cannot be ${typed}: the frame is ${
              key === "x" ? `${next.width} wide on a ${pixels.width}px` : `${next.height} tall on a ${pixels.height}px`
            } photograph, so it would hang off the edge. It was set to ${next[key]}. Reduce ${
              key === "x" ? "Width" : "Height"
            } first to move it further ${key === "x" ? "right" : "down"}.`
          : `${CROP_FIELD_NAME[key]} cannot be ${typed}: the frame is between ${minEdge} and ${
              key === "width" ? pixels.width : pixels.height
            } pixels. It was set to ${next[key]}.`
      );
    },
    [clamp, crop, pixels.width, pixels.height]
  );

  /**
   * A keystroke: kept as text, unless the number it already spells needs no clamping at all.
   *
   * COMMITTING THE LEGAL ONES LIVE is what keeps the overlay following the typing — the frame moves as
   * a designer types 20, 200, 2000 while each of those is a legal width — and it is what keeps this
   * box behaving like the sliders beside it. Only the values the clamp would have to CHANGE are held
   * back, which is exactly the set that was being destroyed mid-number.
   */
  const typeInto = useCallback(
    (key: keyof CropRect, text: string) => {
      const typed = Number(text);
      if (text.trim() !== "" && Number.isInteger(typed)) {
        const next = clamp({ ...crop, [key]: typed });
        if (next[key] === typed) {
          setDraft(null);
          setClampNote(null);
          setCrop(next);
          return;
        }
      }
      setDraft({ key, text });
    },
    [clamp, crop]
  );

  /** Move one corner to (x, y) in source pixels, keeping the opposite corner where it is. */
  const moveCorner = useCallback(
    (base: CropRect, corner: Corner, x: number, y: number): CropRect => {
      const left = corner === "nw" || corner === "sw" ? x : base.x;
      const top = corner === "nw" || corner === "ne" ? y : base.y;
      const right = corner === "ne" || corner === "se" ? x : base.x + base.width;
      const bottom = corner === "sw" || corner === "se" ? y : base.y + base.height;
      return clamp({
        x: Math.min(left, right),
        y: Math.min(top, bottom),
        width: Math.abs(right - left),
        height: Math.abs(bottom - top)
      });
    },
    [clamp]
  );

  function beginDrag(event: React.PointerEvent<HTMLElement>, corner: Corner | "move") {
    if (disabled || event.button !== 0) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    /*
      `preventDefault` STOPS THE BROWSER'S OWN TEXT SELECTION, which otherwise drags a blue smear
      across the preview and, on touch, turns the gesture into a long-press selection instead. And the
      focus is then taken back BY HAND, because that same call is what stops the browser focusing the
      handle: without it a designer who dragged a corner once could not then nudge it with the arrow
      keys, so the two routes would stop being interchangeable exactly where somebody switched. Both
      halves are `RankableList.beginDrag`'s, verbatim in intent.
    */
    event.preventDefault();
    if (corner !== "move") (event.currentTarget as HTMLElement).focus();
    setDrag({ pointerId: event.pointerId, corner, startX: event.clientX, startY: event.clientY, start: crop });
  }

  function continueDrag(event: React.PointerEvent<HTMLElement>) {
    if (drag === null || drag.pointerId !== event.pointerId) return;
    // The preview is drawn at `scale`, so a pointer moved by `d` display pixels is `d / scale` source
    // pixels. Dividing by a scale that could be 0 is impossible: `scale` is `min(1, box/edge)` over a
    // frame whose edges are at least 1.
    const dx = (event.clientX - drag.startX) / scale;
    const dy = (event.clientY - drag.startY) / scale;
    if (drag.corner === "move") {
      setCrop(clamp({ ...drag.start, x: drag.start.x + dx, y: drag.start.y + dy }));
      return;
    }
    const anchorX = drag.corner === "nw" || drag.corner === "sw" ? drag.start.x : drag.start.x + drag.start.width;
    const anchorY = drag.corner === "nw" || drag.corner === "ne" ? drag.start.y : drag.start.y + drag.start.height;
    setCrop(moveCorner(drag.start, drag.corner, anchorX + dx, anchorY + dy));
  }

  function endDrag(event: React.PointerEvent<HTMLElement>) {
    if (drag !== null && drag.pointerId === event.pointerId) setDrag(null);
  }

  /** Arrow keys on a corner handle. 1 px, or 10 with Shift — the same pair `RankableList` uses. */
  function nudge(event: React.KeyboardEvent<HTMLButtonElement>, corner: Corner) {
    const step = event.shiftKey ? 10 : 1;
    let dx = 0;
    let dy = 0;
    if (event.key === "ArrowLeft") dx = -step;
    else if (event.key === "ArrowRight") dx = step;
    else if (event.key === "ArrowUp") dy = -step;
    else if (event.key === "ArrowDown") dy = step;
    else return;
    event.preventDefault();
    const x = (corner === "nw" || corner === "sw" ? crop.x : crop.x + crop.width) + dx;
    const y = (corner === "nw" || corner === "ne" ? crop.y : crop.y + crop.height) + dy;
    setCrop(moveCorner(crop, corner, x, y));
  }

  /* ────────────────────────────────────────────────────────────────────────────
   * Committing
   * ──────────────────────────────────────────────────────────────────────────── */

  const cropped = crop.width * crop.height;
  const overCap = sharpen.amount > 0 && cropped > SHARPEN_MAX_PIXELS;
  const isWhole =
    crop.x === 0 && crop.y === 0 && crop.width === pixels.width && crop.height === pixels.height;
  const stale =
    applied !== null &&
    (applied.crop.x !== crop.x ||
      applied.crop.y !== crop.y ||
      applied.crop.width !== crop.width ||
      applied.crop.height !== crop.height ||
      applied.sharpen.amount !== sharpen.amount ||
      applied.sharpen.radius !== sharpen.radius ||
      applied.sharpen.threshold !== sharpen.threshold);
  const nothingToDo = isWhole && sharpen.amount === 0;

  async function apply() {
    if (disabled || overCap) return;
    setProblem(null);
    setBusy(true);
    try {
      const runtime = await loadImageEditor();
      if (goneRef.current) return;
      if (editorRef.current === null) editorRef.current = new runtime.ImageEditor();
      const edited = await editorRef.current.edit({ pixels, crop, sharpen });
      if (goneRef.current) return;
      /*
        THE PROVENANCE SENTENCE COMES BACK FROM THE WORKER, and it used to be rebuilt here.

        The copy that stood here was a hand transcription of `lib/trace/imageEdit.describeEdit` —
        which this component genuinely cannot import (see the constants above: that module pulls
        `engine/convolve` onto the page's bundle). So the module's version had no caller outside its
        spec, and the spec pinned the sentence nobody shipped: "The original photograph is unchanged."
        — the promise the whole feature rests on — could have been edited out of the shipped path with
        the suite still green.

        The worker already imports that module, so it is the one place both halves are true: it is on
        the far side of the bundle boundary AND it holds the real `clampCrop`. Which fixes the second
        half of the same problem — the local copy reported the crop as REQUESTED, so if this file's
        `clamp` and `imageEdit.clampCrop` ever disagreed, the note would be the thing that lied about
        which pixels were read. `edited.note` describes the rectangle the pixels actually came from.
      */
      setApplied({ crop, sharpen, millis: edited.millis });
      onEdited({
        data: edited.data,
        width: edited.width,
        height: edited.height,
        crop,
        sharpen,
        note: edited.note,
        millis: edited.millis
      });
    } catch (error) {
      if (goneRef.current) return;
      const runtimeKnown = error instanceof Error ? error.message : "";
      // A SUPERSEDED REQUEST IS NOT A FAILURE, and this is the one place it can arrive: two presses in
      // a row. The class test needs the module, which a failed load does not give us, so the message
      // is not shown for the cancelled case — the second press's answer is about to arrive and will
      // say everything there is to say.
      if (error instanceof Error && error.name === "ImageEditCancelledError") return;
      setProblem(
        runtimeKnown.length > 0
          ? runtimeKnown
          : "The photograph could not be processed on this device. The photograph itself is untouched."
      );
    } finally {
      if (!goneRef.current) setBusy(false);
    }
  }

  function reset() {
    setCrop(whole);
    setDraft(null);
    setClampNote(null);
    setSharpen(NO_SHARPEN);
    setApplied(null);
    setProblem(null);
    onEdited(null);
  }

  /* ────────────────────────────────────────────────────────────────────────────
   * Render
   * ──────────────────────────────────────────────────────────────────────────── */

  const left = crop.x * scale;
  const top = crop.y * scale;
  const width = crop.width * scale;
  const height = crop.height * scale;
  const taps = 2 * Math.max(1, Math.ceil(3 * sharpen.radius)) + 1;

  return (
    <div className="mb-3 rounded-md border border-line-200 bg-card p-3">
      <div className="flex items-start gap-2">
        <span className="mt-0.5 grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-field-200 text-field-600">
          <Crop className="h-4 w-4" aria-hidden />
        </span>
        <div className="min-w-0">
          <h5 className="font-display text-sm font-semibold text-ink-900">Frame and sharpen the photograph</h5>
          <p className="mt-1 max-w-prose text-xs leading-5 text-ink-500">
            Both are arithmetic on this device and both change only what is TRACED. The photograph is
            never altered, never re-encoded and never replaced — it is filed exactly as it was taken,
            with its full resolution and its own EXIF, whatever you do here. Reopening this panel after
            attaching lets you re-frame and trace again; the photograph is filed once.
          </p>
        </div>
      </div>

      {/* ── The frame ──────────────────────────────────────────────────────── */}
      <div className="mt-3">
        <span className="field-label" id={`${fieldId}-frame-label`}>
          Frame
        </span>
        {/*
          `touch-none` ON THE PREVIEW, so a drag inside it is a crop rather than a page scroll on a
          handset. Not `overflow-hidden`: the corner handles are `<button>`s and the global focus ring
          is an `outline` drawn OUTSIDE the border box, so clipping here would erase the ring of any
          handle sitting on an edge — which is all four of them once the frame is the whole photograph.
        */}
        <div
          className="relative mt-1 inline-block touch-none rounded-md bg-field-100 p-0"
          style={{ width: boxWidth, height: boxHeight }}
        >
          <canvas
            ref={canvasRef}
            className="block rounded-md"
            aria-label="The photograph, with the frame that will be traced drawn over it"
          />

          {/* The dimmed surround, as FOUR rectangles rather than one giant box-shadow, because a
              9999px shadow needs an `overflow-hidden` parent and that parent would clip the handles'
              focus rings. `rgb(var(--ink-900) / …)` inverts with the theme; a literal black would not. */}
          <div aria-hidden className="pointer-events-none absolute inset-0">
            <div className="absolute left-0 right-0 top-0" style={{ height: top, background: "rgb(var(--ink-900) / 0.45)" }} />
            <div
              className="absolute left-0 right-0 bottom-0"
              style={{ height: Math.max(0, boxHeight - top - height), background: "rgb(var(--ink-900) / 0.45)" }}
            />
            <div className="absolute left-0" style={{ top, height, width: left, background: "rgb(var(--ink-900) / 0.45)" }} />
            <div
              className="absolute right-0"
              style={{ top, height, width: Math.max(0, boxWidth - left - width), background: "rgb(var(--ink-900) / 0.45)" }}
            />
          </div>

          {/* The frame itself. A plain div with pointer handlers and no tabindex — the keyboard route is
              the handles and the four number inputs below, and a focusable box around them would be a
              tab stop that announces nothing. */}
          <div
            className="absolute cursor-move border-2 border-purple-600"
            style={{ left, top, width, height }}
            onPointerDown={(event) => beginDrag(event, "move")}
            onPointerMove={continueDrag}
            onPointerUp={endDrag}
            onPointerCancel={endDrag}
          />

          {(["nw", "ne", "sw", "se"] as const).map((corner) => (
            <button
              key={corner}
              type="button"
              disabled={disabled}
              aria-label={`${CORNER_NAME[corner]} corner of the frame. Arrow keys move it; hold Shift for ten pixels.`}
              className="absolute h-4 w-4 rounded-sm border border-card bg-purple-700"
              style={{
                left: (corner === "nw" || corner === "sw" ? left : left + width) - 8,
                top: (corner === "nw" || corner === "ne" ? top : top + height) - 8,
                cursor: corner === "nw" || corner === "se" ? "nwse-resize" : "nesw-resize"
              }}
              onPointerDown={(event) => beginDrag(event, corner)}
              onPointerMove={continueDrag}
              onPointerUp={endDrag}
              onPointerCancel={endDrag}
              onKeyDown={(event) => nudge(event, corner)}
            />
          ))}
        </div>

        {/* THE NUMBERS ARE THE PRIMARY ROUTE, NOT A FALLBACK. Every one is a real labelled input, so
            the frame is reachable and readable without a pointer at all — which is why what they used
            to do to a half-typed number was the worst place in this panel for it to happen. See
            `draft` and `commitDraft`. */}
        <div className="mt-2 grid grid-cols-2 gap-2 sm:grid-cols-4">
          {(Object.keys(CROP_FIELD_NAME) as (keyof CropRect)[]).map((key) => (
            <div key={key} className="grid gap-1">
              <label className="text-xs font-medium text-ink-900" htmlFor={`${fieldId}-crop-${key}`}>
                {CROP_FIELD_NAME[key]}
              </label>
              <input
                id={`${fieldId}-crop-${key}`}
                type="number"
                className="field-input"
                inputMode="numeric"
                min={key === "width" || key === "height" ? Math.min(CROP_MIN_EDGE_PX, pixels.width) : 0}
                max={key === "x" || key === "width" ? pixels.width : pixels.height}
                step={1}
                // The characters being typed if this is the box being typed into; otherwise the frame.
                value={draft !== null && draft.key === key ? draft.text : crop[key]}
                disabled={disabled}
                onChange={(event) => typeInto(key, event.target.value)}
                // BOTH ENDINGS COMMIT. Blur is the ordinary one — moving to the next box, pressing the
                // apply button — and Enter is the one a designer filling in four numbers expects to
                // work without leaving the field. Escape abandons the draft and puts the frame back,
                // which is the only way out that does not have to guess at an unfinished number.
                onBlur={(event) => commitDraft(key, event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    event.preventDefault();
                    commitDraft(key, event.currentTarget.value);
                  } else if (event.key === "Escape") {
                    event.preventDefault();
                    setDraft(null);
                    setClampNote(null);
                  }
                }}
              />
            </div>
          ))}
        </div>

        {/*
          A CLAMP THAT MOVED A TYPED NUMBER IS ANNOUNCED, not left as a box that ignored the typing.

          `role="status"` rather than `alert`: it is the consequence of an ordinary edit, and an
          assertive interruption every time a number is committed would be worse than the silence it
          replaces.

          ALWAYS RENDERED, EMPTY WHEN THERE IS NOTHING TO SAY — the same rule as the live region at the
          bottom of this panel and the one in `SketchTraceField`: a reader announces a live region's
          CHANGES rather than its arrival, so a region mounted in the same commit as its first sentence
          is silent exactly when it matters.
        */}
        <p
          role="status"
          // The margin, and not the element, is what comes and goes: the same node stays in the
          // document — so the region is announced — without leaving a gap under the boxes when it is
          // empty. Changing a class does not remount it.
          className={clampNote !== null ? "mt-2 text-xs leading-5 text-amber-800" : "text-xs leading-5 text-amber-800"}
        >
          {clampNote}
        </p>

        <p className="mt-2 text-xs leading-5 text-ink-500">
          {isWhole
            ? `The whole photograph, ${pixels.width}x${pixels.height}.`
            : `${crop.width}x${crop.height} of ${pixels.width}x${pixels.height} — ${Math.round(
                (cropped / (pixels.width * pixels.height)) * 100
              )}% of the frame. Everything outside it is absent from the drawing.`}{" "}
          The engine may still narrow the frame further on its own when it finds a subject it is
          confident about; the notes under the traced result say when it did.
        </p>
      </div>

      {/* ── Sharpening ─────────────────────────────────────────────────────── */}
      <fieldset className="mt-3 rounded-md border border-line-200 bg-surface-50 p-3">
        <legend className="field-label px-1">Sharpen the photograph</legend>
        <p className="max-w-prose text-xs leading-5 text-ink-500">
          An unsharp mask — the same one the engine uses — over the photograph&apos;s luminance, run
          here at up to {pixels.width}x{pixels.height} rather than at the smaller size the trace runs
          at. That is the whole difference between this and the “Sharpen amount” control in the
          Sharpening group below: this one lifts detail BEFORE the engine reduces the image, so faint
          pencil survives the reduction. Both use the same arithmetic, and using both compounds them.
        </p>

        <div className="mt-3 grid gap-3">
          <SharpenSlider
            id={`${fieldId}-amount`}
            label="Sharpen amount"
            hint="How much of the difference is added back. 0 is off. Above about 2 the edges start to show a bright halo."
            min={0}
            max={SHARPEN_AMOUNT_MAX}
            step={0.05}
            value={sharpen.amount}
            disabled={disabled}
            onChange={(amount) => setSharpen({ ...sharpen, amount })}
          />
          <SharpenSlider
            id={`${fieldId}-radius`}
            label="Sharpen radius"
            hint="The size of the detail being lifted, in pixels. Around 1 for a fine pencil line; larger for a thick marker or a photograph taken from further away."
            min={SHARPEN_RADIUS_MIN}
            max={SHARPEN_RADIUS_MAX}
            step={0.1}
            value={sharpen.radius}
            disabled={disabled || sharpen.amount === 0}
            onChange={(radius) => setSharpen({ ...sharpen, radius })}
          />
          <SharpenSlider
            id={`${fieldId}-threshold`}
            label="Leave flat areas alone"
            hint="Differences smaller than this are not touched, so the grain of the paper and the sensor noise in a dim photograph are not sharpened along with the drawing."
            min={0}
            max={SHARPEN_THRESHOLD_MAX}
            step={0.005}
            value={sharpen.threshold}
            disabled={disabled || sharpen.amount === 0}
            onChange={(threshold) => setSharpen({ ...sharpen, threshold })}
          />
        </div>

        {/*
          THE COST, STATED BEFORE THE PRESS, IN ARITHMETIC RATHER THAN IN PREDICTED SECONDS.

          A figure like "about 2 seconds" that turns out to be nine on a five-year-old handset is worse
          than no figure, and this repository's rule is that a measured number is quoted only by
          whoever measured it. So this says what the work IS — megapixels, taps, passes — says that a
          large photograph takes seconds on a phone, and says where it runs. The MEASURED time of the
          run that actually happened is printed underneath, afterwards.
        */}
        {sharpen.amount > 0 ? (
          <p className="mt-3 text-xs leading-5 text-ink-500">
            {(cropped / 1_000_000).toFixed(1)} megapixels through a {taps}-tap kernel, in two passes —
            about {Math.round((cropped * taps * 2) / 1_000_000)} million multiply-adds. On a laptop that
            is under a second; on a phone a full-size photograph can take several seconds. It runs in a
            worker, so the page stays usable while it does, and nothing is recomputed until you press
            the button below.
          </p>
        ) : null}

        {overCap ? (
          <p
            role="alert"
            className="mt-3 flex items-start gap-2 rounded-md border border-line-200 bg-amber-100 px-2 py-1.5 text-xs leading-4 text-amber-800"
          >
            <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
            <span>
              Sharpening runs on frames up to {(SHARPEN_MAX_PIXELS / 1_000_000).toFixed(1)} megapixels
              on this device, and this frame is {(cropped / 1_000_000).toFixed(1)}. Crop it smaller —
              the frame above is what does that — or leave this at 0 and use the “Sharpen amount”
              control in the Sharpening group, which works on the smaller plane the trace itself runs
              at.
            </span>
          </p>
        ) : null}
      </fieldset>

      {problem ? (
        <p
          role="alert"
          className="mt-3 flex items-start gap-2 rounded-md border border-red-200 bg-error-100 px-2 py-1.5 text-xs leading-4 text-error-600"
        >
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
          <span>{problem}</span>
        </p>
      ) : null}

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <button
          type="button"
          className="field-button-secondary"
          onClick={() => void apply()}
          disabled={disabled || busy || overCap || (nothingToDo && applied === null)}
        >
          {busy ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <Sparkles className="h-4 w-4" aria-hidden />}
          Use this frame for the trace
        </button>
        {applied !== null || !nothingToDo ? (
          <button
            type="button"
            className="field-button-secondary"
            onClick={reset}
            disabled={disabled || busy}
          >
            Use the whole photograph
          </button>
        ) : null}
      </div>

      {/*
        THE LIVE REGION IS OUTSIDE THE CONDITIONS, ALWAYS RENDERED. A reader announces a live region's
        CHANGES rather than its arrival, so a region mounted in the same commit as its first sentence
        is silent exactly when it matters — `SketchTraceField`'s wrapper carries the same note for the
        same reason.
      */}
      <div aria-live="polite" className="mt-2 grid gap-1 text-xs leading-5 text-ink-500">
        {busy ? <p>Working on the photograph…</p> : null}
        {!busy && applied !== null && !stale ? (
          <p className="flex items-start gap-2">
            <Check className="mt-0.5 h-3.5 w-3.5 shrink-0 text-success-600" aria-hidden />
            <span>
              The trace is using {applied.crop.width}x{applied.crop.height}
              {applied.sharpen.amount > 0
                ? `, sharpened at amount ${round2(applied.sharpen.amount)} and radius ${round2(
                    applied.sharpen.radius
                  )}px`
                : ", unsharpened"}
              . Computed on this device in {Math.round(applied.millis)} ms.
            </span>
          </p>
        ) : null}
        {!busy && stale ? (
          <p className="flex items-start gap-2 text-amber-800">
            <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
            <span>
              The frame on screen is not the one being traced. The trace is still using{" "}
              {applied?.crop.width}x{applied?.crop.height} from the last press — press “Use this frame
              for the trace” to catch it up.
            </span>
          </p>
        ) : null}
        {!busy && applied === null && !nothingToDo ? (
          <p>Nothing has been applied yet: the trace is still using the whole photograph.</p>
        ) : null}
      </div>
    </div>
  );
}

/**
 * The four number boxes, in the order they are drawn, and the words on their labels.
 *
 * One declaration for the labels, the keys and the order: the render maps over it, `commitDraft` names
 * the box it moved out of it, and there is no second list to fall out of step with the first.
 */
const CROP_FIELD_NAME: Record<keyof CropRect, string> = {
  x: "Left",
  y: "Top",
  width: "Width",
  height: "Height"
};

const CORNER_NAME: Record<Corner, string> = {
  nw: "Top-left",
  ne: "Top-right",
  sw: "Bottom-left",
  se: "Bottom-right"
};

function round2(value: number): string {
  return (Math.round(value * 100) / 100).toString();
}

/**
 * One sharpening slider.
 *
 * The same anatomy as `SketchTraceField`'s `SliderRow` — label, `<output>`, range, hint, all bound by
 * id — rather than a shared component, because that one reads its value out of a `TraceParams` tree
 * through a `SliderSpec` and these three do not have one. Copying the shape is what keeps the two sets
 * of sliders looking and announcing identically; copying the plumbing would mean inventing a spec for
 * parameters that are not the engine's.
 */
function SharpenSlider({
  id,
  label,
  hint,
  min,
  max,
  step,
  value,
  disabled,
  onChange
}: {
  id: string;
  label: string;
  hint: string;
  min: number;
  max: number;
  step: number;
  value: number;
  disabled?: boolean;
  onChange: (value: number) => void;
}) {
  return (
    <div>
      <div className="flex items-baseline justify-between gap-2">
        <label className="text-xs font-medium text-ink-900" htmlFor={id}>
          {label}
        </label>
        <output className="text-xs tabular-nums text-ink-500" htmlFor={id}>
          {round2(value)}
        </output>
      </div>
      <input
        id={id}
        type="range"
        className="mt-1 w-full accent-purple-700"
        min={min}
        max={max}
        step={step}
        value={value}
        disabled={disabled}
        aria-describedby={`${id}-hint`}
        onChange={(event) => onChange(Number(event.target.value))}
      />
      <p id={`${id}-hint`} className="mt-0.5 text-xs leading-4 text-ink-500">
        {hint}
      </p>
    </div>
  );
}
