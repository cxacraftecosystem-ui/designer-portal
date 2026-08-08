"use client";

/**
 * A signature pad, and the reasons it is shaped the way it is rather than the obvious way.
 *
 * WHAT IT REPLACES. Stage 19 records who attended a workshop and who was certified. Until now the
 * evidence for that was a PHOTOGRAPH OF A PAPER ATTENDANCE SHEET, which is a picture of data and
 * not data: it cannot be counted, it cannot be reconciled against the roster the same workshop
 * built in stage 3, and it cannot be printed as a table. The registry fields beside this pad
 * (`participantRef`, `issued`, `daysAttended`) are now the record. This pad only captures the
 * signature that evidences it.
 *
 * ── THE PAD IS NEVER THE ONLY WAY IN ────────────────────────────────────────────────────────────
 *
 * A signature pad is unusable to a keyboard-only designer, unusable to anyone driving the form with
 * a switch or a screen reader, and unusable on a handset whose digitiser has stopped answering in
 * 44°C — all three of which are ordinary conditions here, not edge cases. So the datum and its
 * evidence are deliberately separated: attendance is recorded by ordinary registry fields that are
 * all keyboard-answerable, and `signatureImage` is STANDARD tier and never required. This component
 * therefore cannot block anything. It has no validation, it refuses nothing, and it says so on
 * screen — the note under the buttons exists so that a designer who cannot use the pad knows they
 * have already recorded the attendance and are not leaving the row unfinished.
 *
 * That is also why there is no "sign here to confirm" gate anywhere near it. Nothing in this app
 * may prevent a designer from recording what they saw.
 *
 * ── POINTER EVENTS, AND WHY CAPTURE IS NOT OPTIONAL ─────────────────────────────────────────────
 *
 * One `pointer*` path serves finger, stylus and mouse; there is no separate touch branch. Two
 * details are load-bearing and both are failures of the naive version:
 *
 *  * `setPointerCapture` — without it a stroke that leaves the canvas (which is most of them, since
 *    a signature is written fast and the pad is small on a handset) stops receiving `pointermove`
 *    the instant it crosses the edge, and the ink simply stops mid-letter. With capture the element
 *    keeps receiving the pointer until it is lifted, so the stroke ends where the finger ended.
 *  * `touch-action: none` — the browser's default is to treat a drag on a canvas as a page scroll.
 *    Without this the page scrolls out from under the signature and the pointer stream is cancelled
 *    part-way through, which reads to a designer as a pad that "only works sometimes".
 *
 * `getCoalescedEvents` is used where the browser offers it: a 240 Hz digitiser batches several
 * positions into one `pointermove`, and reading only the last of them turns a curve into a chord.
 * A signature is exactly the kind of fast, curved input where that is visible.
 *
 * ── COORDINATES ARE STORED NORMALISED ───────────────────────────────────────────────────────────
 *
 * Points are kept as fractions of the box (0..1) rather than as pixels, because the pad is drawn at
 * CSS-pixel size on screen, at `devicePixelRatio` in its backing store, and at a fixed larger size
 * on export — three different pixel grids for one signature. Storing pixels means the signature
 * would jump on a rotate, on a window resize, or when the browser moves the tab to a monitor with a
 * different DPR. The container holds a fixed aspect ratio so normalising cannot distort the shape.
 *
 * ── INK AND PAPER ARE FIXED COLOURS, DELIBERATELY BREAKING THE THEME RULE ───────────────────────
 *
 * Everything else in this app is drawn from the themed `ink-*`/`surface-*` ladders so it inverts
 * under `data-theme="dark"`. This canvas must not. The exported PNG is transparent and gets printed
 * onto a WHITE report page, so ink drawn in the dark theme's near-white foreground would be an
 * invisible signature on the delivered document — captured, stored, and blank where an inspecting
 * officer looks for it. The ink is therefore always `#181715` and the pad's paper is always the
 * cream `#FAF9F5`, which are the two non-inverting logo tokens (`logo-ink`/`logo-cream`) this
 * palette already has for exactly this "a physical object, not a UI surface" case.
 *
 * The paper is a CSS background on the element, NOT a fill painted into the bitmap, which is what
 * keeps the exported PNG's background transparent while the stroke is still visible on screen in
 * either theme. Painting the cream in would give the report an opaque rectangle over its own layout.
 */

import { useCallback, useEffect, useId, useRef, useState } from "react";
import { Eraser, PenLine, Undo2 } from "lucide-react";

import { mediaTimestamp } from "@/lib/media";

/** A point on the pad, as a fraction of its box. See the header for why these are not pixels. */
export type SignaturePoint = { x: number; y: number };

/** One continuous press-drag-lift. A signature is normally two or three of these. */
export type SignatureStroke = SignaturePoint[];

/**
 * The pad's aspect ratio, fixed so that normalised points cannot distort.
 *
 * 5:2 is roughly the proportion of a signature box on a paper attendance register, which is the
 * shape a designer is used to signing into and — more practically — is wide enough on a 360px
 * handset to fit a full name without the writer having to shrink their hand.
 */
const ASPECT_WIDTH = 5;
const ASPECT_HEIGHT = 2;

/**
 * Export resolution. Large enough that the stroke still reads as handwriting when a report scales
 * it into a table-width column, small enough that a PNG of a few line segments stays a few KB —
 * which matters when it is queued on a handset waiting for a connection that may be days away.
 */
const EXPORT_WIDTH = 1000;
const EXPORT_HEIGHT = (EXPORT_WIDTH / ASPECT_WIDTH) * ASPECT_HEIGHT;

/** Ink, as a literal. See the header: canvas cannot read a Tailwind token, and this must not invert. */
const INK = "#181715";

/**
 * Stroke width as a fraction of the pad's height, so the signature has the same visual weight at
 * every one of the three sizes it is drawn at. A constant pixel width would be a hairline on the
 * export and a marker pen on a phone.
 */
const STROKE_HEIGHT_FRACTION = 0.018;

/**
 * Draw a set of strokes onto any 2D context, at any size. Pure: no React, no DOM lookups, no state.
 *
 * This is the single renderer for all three surfaces the signature appears on — the live canvas,
 * the export bitmap, and anything that later needs to redraw one. Keeping it one function is what
 * makes "what the designer saw" and "what the report prints" the same picture by construction
 * rather than by two implementations agreeing for now. It is exported so it can be tested and so an
 * Android port has something to be a port OF.
 *
 * The context is NOT cleared here — the caller owns that, because the export path draws onto a
 * fresh bitmap and the live path clears a reused one.
 */
export function renderSignature(
  ctx: CanvasRenderingContext2D,
  strokes: readonly SignatureStroke[],
  width: number,
  height: number
): void {
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  ctx.strokeStyle = INK;
  ctx.fillStyle = INK;
  const lineWidth = Math.max(1, height * STROKE_HEIGHT_FRACTION);
  ctx.lineWidth = lineWidth;

  for (const stroke of strokes) {
    if (!stroke.length) continue;
    // A tap with no movement is a dot — a real mark somebody made on purpose (a full stop after
    // initials, most often). Stroking a zero-length path draws nothing at all, so it is filled.
    if (stroke.length === 1) {
      ctx.beginPath();
      ctx.arc(stroke[0].x * width, stroke[0].y * height, lineWidth / 2, 0, Math.PI * 2);
      ctx.fill();
      continue;
    }
    // Quadratic segments through the MIDPOINTS of consecutive samples. Joining raw samples with
    // straight lines makes a signature look like a seismograph, because the sample rate is coarse
    // relative to how fast a signature is written; the midpoint curve is the standard fix and costs
    // nothing.
    ctx.beginPath();
    ctx.moveTo(stroke[0].x * width, stroke[0].y * height);
    for (let index = 1; index < stroke.length - 1; index += 1) {
      const current = stroke[index];
      const next = stroke[index + 1];
      ctx.quadraticCurveTo(
        current.x * width,
        current.y * height,
        ((current.x + next.x) / 2) * width,
        ((current.y + next.y) / 2) * height
      );
    }
    const last = stroke[stroke.length - 1];
    ctx.lineTo(last.x * width, last.y * height);
    ctx.stroke();
  }
}

/**
 * Render the strokes to a transparent PNG `File`.
 *
 * Transparency is the reason this is a PNG and not a JPEG: the report composites the signature over
 * whatever is already on the page, and a JPEG would bring a white box with it that covers the rule
 * lines of the table it sits in.
 */
export async function signatureToPngFile(
  strokes: readonly SignatureStroke[],
  filename: string
): Promise<File | null> {
  if (!strokes.length) return null;
  const canvas = document.createElement("canvas");
  canvas.width = EXPORT_WIDTH;
  canvas.height = EXPORT_HEIGHT;
  const ctx = canvas.getContext("2d");
  if (!ctx) return null;
  // No fill: the bitmap starts fully transparent and only the ink is ever written to it.
  renderSignature(ctx, strokes, EXPORT_WIDTH, EXPORT_HEIGHT);
  const blob = await new Promise<Blob | null>((resolve) => {
    canvas.toBlob(resolve, "image/png");
  });
  if (!blob) return null;
  return new File([blob], filename, { type: "image/png", lastModified: Date.now() });
}

export function SignaturePad({
  onCapture,
  disabled,
  label = "Signature",
  /** Named so the note under the pad can point at the fields that ARE the attendance record. */
  alternativeHint = "Attendance is recorded by the fields above. This signature is evidence for it and is never required."
}: {
  /**
   * Hand the finished PNG to the caller's media path.
   *
   * This component never uploads anything itself. It produces a `File` and gives it away, and the
   * media field it sits inside puts that file through the SAME path as a photograph taken with the
   * camera — eager pre-upload, per-file retry, and the local draft store when there is no
   * connection. A second upload route for signatures would be a second thing to keep working
   * offline, and offline is the condition this app is written for.
   */
  onCapture: (file: File) => void;
  disabled?: boolean;
  label?: string;
  alternativeHint?: string;
}) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [strokes, setStrokes] = useState<SignatureStroke[]>([]);
  /**
   * The stroke being drawn right now, and the pointer that owns it.
   *
   * A ref and not state: `pointermove` fires at the digitiser's rate (up to 240 Hz), and putting the
   * in-progress stroke through React state would schedule a render per sample and drop points on a
   * mid-range handset — the pad would feel like it was skipping, which is the one thing a signature
   * pad may not do. The live stroke is painted straight onto the canvas; React state is updated once
   * per stroke, on lift.
   */
  const liveRef = useRef<{ pointerId: number; points: SignatureStroke } | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const statusId = useId();
  const hintId = useId();

  /** Repaint the whole pad from `strokes` plus whatever is mid-stroke. */
  const repaint = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    // Guarded with an explicit `> 0` and not `|| 1`: before the first layout `clientWidth` is 0, and
    // `width / 0` is Infinity — which is TRUTHY, so the obvious fallback would sail past and hand
    // `scale()` an infinite factor, leaving a pad that silently draws nothing until something else
    // happened to resize it.
    const ratio = canvas.clientWidth > 0 ? canvas.width / canvas.clientWidth : 1;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.scale(ratio, ratio);
    const width = canvas.width / ratio;
    const height = canvas.height / ratio;
    const live = liveRef.current;
    renderSignature(ctx, live ? [...strokes, live.points] : strokes, width, height);
  }, [strokes]);

  /**
   * Keep the backing store matched to the box AND to the device pixel ratio.
   *
   * Without the ratio the stroke is visibly soft on every phone made in the last decade; without the
   * observer a rotate leaves the bitmap at the old width and the signature stretched. Repainting
   * from normalised points is what makes both safe — there is nothing to lose in a resize.
   */
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const resize = () => {
      const ratio = Math.min(window.devicePixelRatio || 1, 3);
      const width = canvas.clientWidth;
      if (!width) return;
      const height = (width / ASPECT_WIDTH) * ASPECT_HEIGHT;
      const nextWidth = Math.round(width * ratio);
      const nextHeight = Math.round(height * ratio);
      if (canvas.width === nextWidth && canvas.height === nextHeight) return;
      canvas.width = nextWidth;
      canvas.height = nextHeight;
      repaint();
    };
    resize();
    const observer = new ResizeObserver(resize);
    observer.observe(canvas);
    return () => observer.disconnect();
  }, [repaint]);

  useEffect(repaint, [repaint]);

  /** Where a pointer is, as a fraction of the box, clamped so a captured stroke cannot store -0.3. */
  function pointAt(canvas: HTMLCanvasElement, clientX: number, clientY: number): SignaturePoint {
    const box = canvas.getBoundingClientRect();
    const clamp = (value: number) => Math.min(1, Math.max(0, value));
    return {
      x: clamp(box.width ? (clientX - box.left) / box.width : 0),
      y: clamp(box.height ? (clientY - box.top) / box.height : 0)
    };
  }

  function handlePointerDown(event: React.PointerEvent<HTMLCanvasElement>) {
    if (disabled || liveRef.current) return;
    // A right-click or a middle-click is not a stroke. Pen and touch report button 0.
    if (event.pointerType === "mouse" && event.button !== 0) return;
    const canvas = event.currentTarget;
    // See the header: without capture the stroke dies at the edge of the pad.
    try {
      canvas.setPointerCapture(event.pointerId);
    } catch {
      // Some browsers refuse capture for a pointer that has already been released. Losing capture
      // costs a stroke that stops at the boundary; refusing to draw at all would cost the signature.
    }
    event.preventDefault();
    liveRef.current = { pointerId: event.pointerId, points: [pointAt(canvas, event.clientX, event.clientY)] };
    setStatus(null);
    repaint();
  }

  function handlePointerMove(event: React.PointerEvent<HTMLCanvasElement>) {
    const live = liveRef.current;
    if (!live || live.pointerId !== event.pointerId) return;
    event.preventDefault();
    const canvas = event.currentTarget;
    const native = event.nativeEvent;
    // See the header: one `pointermove` can carry several real positions on a high-rate digitiser.
    const batch =
      typeof native.getCoalescedEvents === "function" ? native.getCoalescedEvents() : [];
    const samples = batch.length ? batch : [native];
    for (const sample of samples) live.points.push(pointAt(canvas, sample.clientX, sample.clientY));
    repaint();
  }

  function endStroke(event: React.PointerEvent<HTMLCanvasElement>) {
    const live = liveRef.current;
    if (!live || live.pointerId !== event.pointerId) return;
    liveRef.current = null;
    try {
      event.currentTarget.releasePointerCapture(event.pointerId);
    } catch {
      // Already released by the browser (a cancel usually has). Nothing to undo.
    }
    // Committed to state only now — once per stroke, not once per sample. See `liveRef`.
    setStrokes((current) => [...current, live.points]);
  }

  function undo() {
    setStrokes((current) => current.slice(0, -1));
    setStatus(null);
  }

  function clear() {
    setStrokes([]);
    setStatus(null);
  }

  async function attach() {
    const file = await signatureToPngFile(strokes, `Signature-${mediaTimestamp()}.png`);
    if (!file) {
      setStatus("There is nothing on the pad to attach yet.");
      return;
    }
    onCapture(file);
    // The signature now lives as an attachment in the list above, so leaving it on the pad as well
    // would invite a designer to press Attach twice and upload the same signature two or three
    // times — which is what happened with the photograph tiles before they cleared on attach.
    setStrokes([]);
    setStatus("Signature attached. It uploads with this stage's other files.");
  }

  const empty = strokes.length === 0;

  return (
    <div className="grid gap-2 rounded-md border border-line-200 bg-card p-3">
      <div className="flex items-center gap-2">
        <PenLine className="h-4 w-4 text-ink-500" aria-hidden />
        <span className="field-label">{label}</span>
      </div>

      {/*
        `touch-none` is `touch-action: none` — see the header. It is on the canvas itself rather than
        an ancestor on purpose: from an ancestor the same declaration would also cancel ordinary
        scrolling over everything else in the media card, and a designer could not scroll the form
        past the pad with a thumb.

        `bg-logo-cream` is the paper, and it is a CSS background so it never enters the bitmap.
      */}
      <canvas
        ref={canvasRef}
        // A canvas is a bitmap with no accessible content, so it is described rather than read.
        role="img"
        aria-label={empty ? `${label}: nothing drawn yet` : `${label}: drawn`}
        aria-describedby={`${hintId} ${statusId}`}
        className="touch-none w-full cursor-crosshair rounded-sm border border-line-200 bg-logo-cream"
        style={{ aspectRatio: `${ASPECT_WIDTH} / ${ASPECT_HEIGHT}` }}
        data-testid="signature-pad-canvas"
        data-empty={empty ? "true" : "false"}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={endStroke}
        onPointerCancel={endStroke}
        // A capture lost to the browser (an incoming call, a system gesture) never fires pointerup.
        // Without this the pad would stay convinced a stroke was still in progress and ignore the
        // next press entirely.
        onLostPointerCapture={endStroke}
      />

      <div className="flex flex-wrap gap-2">
        <button type="button" className="field-button" disabled={disabled || empty} onClick={attach}>
          Attach signature
        </button>
        <button
          type="button"
          className="field-button-secondary"
          disabled={disabled || empty}
          onClick={undo}
        >
          <Undo2 className="h-4 w-4" aria-hidden />
          Undo stroke
        </button>
        <button
          type="button"
          className="field-button-secondary"
          disabled={disabled || empty}
          onClick={clear}
        >
          <Eraser className="h-4 w-4" aria-hidden />
          Clear
        </button>
      </div>

      {/* The accessible alternative, stated rather than implied. See the header. */}
      <p id={hintId} className="text-xs leading-5 text-ink-500">
        Sign with a finger or a stylus. {alternativeHint}
      </p>

      {/* `status`, not `alert`: attaching a signature is a thing that went right, and a designer
          working through a roster of thirty should not be interrupted thirty times. */}
      <p id={statusId} role="status" className="text-xs leading-5 text-ink-500">
        {status ?? ""}
      </p>
    </div>
  );
}
