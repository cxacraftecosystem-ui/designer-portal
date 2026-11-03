"use client";

import { useCallback, useEffect, useId, useRef, useState } from "react";
import { Eraser, PenLine, Undo2 } from "lucide-react";

import { recordingFilename } from "@/lib/naming";
import { ActionGroup } from "@/components/ui/ActionGroup";

export type SignaturePoint = { x: number; y: number };

export type SignatureStroke = SignaturePoint[];

const ASPECT_WIDTH = 5;
const ASPECT_HEIGHT = 2;

const EXPORT_WIDTH = 1000;
const EXPORT_HEIGHT = (EXPORT_WIDTH / ASPECT_WIDTH) * ASPECT_HEIGHT;

const INK = "#181715";

const STROKE_HEIGHT_FRACTION = 0.018;

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
    if (stroke.length === 1) {
      ctx.beginPath();
      ctx.arc(stroke[0].x * width, stroke[0].y * height, lineWidth / 2, 0, Math.PI * 2);
      ctx.fill();
      continue;
    }
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
  alternativeHint = "Attendance is recorded by the fields above. This signature is evidence for it and is never required."
}: {
  onCapture: (file: File) => void;
  disabled?: boolean;
  label?: string;
  alternativeHint?: string;
}) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [strokes, setStrokes] = useState<SignatureStroke[]>([]);
  const liveRef = useRef<{ pointerId: number; points: SignatureStroke } | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const statusId = useId();
  const hintId = useId();

  const repaint = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    const ratio = canvas.clientWidth > 0 ? canvas.width / canvas.clientWidth : 1;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.scale(ratio, ratio);
    const width = canvas.width / ratio;
    const height = canvas.height / ratio;
    const live = liveRef.current;
    renderSignature(ctx, live ? [...strokes, live.points] : strokes, width, height);
  }, [strokes]);

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
    if (event.pointerType === "mouse" && event.button !== 0) return;
    const canvas = event.currentTarget;
    try {
      canvas.setPointerCapture(event.pointerId);
    } catch {
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
    }
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
    const file = await signatureToPngFile(strokes, recordingFilename(null, "png", { capturedAt: new Date(), what: "Signature" }));
    if (!file) {
      setStatus("There is nothing on the pad to attach yet.");
      return;
    }
    onCapture(file);
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

      <canvas
        ref={canvasRef}
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
        onLostPointerCapture={endStroke}
      />

      <ActionGroup>
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
      </ActionGroup>

      <p id={hintId} className="text-xs leading-5 text-ink-500">
        Sign with a finger or a stylus. {alternativeHint}
      </p>

      <p id={statusId} role="status" className="text-xs leading-5 text-ink-500">
        {status ?? ""}
      </p>
    </div>
  );
}
