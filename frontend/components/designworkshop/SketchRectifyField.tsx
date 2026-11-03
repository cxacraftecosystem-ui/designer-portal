"use client";

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { AlertTriangle, Check, Scan, SquareDashedBottom, Wand2, X } from "lucide-react";

import { fetchMediaRow } from "@/lib/mediaRows";
import { isLocalMediaRef, readLocalMedia } from "@/lib/designWorkshopStore";
import { greyToRgba } from "@/lib/sketchRectify";
import {
  type Corners,
  RECTIFY_MAX_EDGE_PX,
  guessSheetCorners,
  loadSketchPlane,
  makePlate,
  orderCorners,
  rectifySketchFile
} from "@/lib/sketchRectify";
import type { GreyPlane } from "@/lib/imageQuality";
import type { Point } from "@/lib/photoMeasure";
import type { MediaFile } from "@/lib/types";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { Toggle } from "@/components/ui/Toggle";
import { ActionGroup } from "@/components/ui/ActionGroup";

export type SketchSource = {
  ref: string;
  fieldLabel: string;
};

const PREVIEW_EDGE_PX = 420;

const SEED_INSET = 0.08;

type Aspect = { key: string; label: string; value?: number };

const ASPECTS: Aspect[] = [
  { key: "auto", label: "As photographed" },
  { key: "a4p", label: "A4 portrait", value: 210 / 297 },
  { key: "a4l", label: "A4 landscape", value: 297 / 210 },
  { key: "sq", label: "Square", value: 1 }
];

type LoadState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "ready"; plane: GreyPlane; url: string; name: string; decodeMs: number; sourceWidth: number; sourceHeight: number }
  | { status: "failed"; reason: string };

async function loadSourceBlob(ref: string): Promise<{ blob: Blob; name: string } | { reason: string }> {
  if (isLocalMediaRef(ref)) {
    const media = await readLocalMedia(ref);
    if (!media?.blob) {
      return { reason: "Re-attach the photo to straighten it." };
    }
    return { blob: media.blob, name: media.name };
  }
  let media: MediaFile;
  try {
    media = await fetchMediaRow(ref);
  } catch {
    return { reason: "Couldn't load the photo. Try later, or re-attach it." };
  }
  if (!media.url) {
    return { reason: "You can't access that photo." };
  }
  try {
    const response = await fetch(media.url);
    if (!response.ok) throw new Error(String(response.status));
    return { blob: await response.blob(), name: media.originalFilename || "sketch" };
  } catch {
    return {
      reason:
        "Re-attach the photo to straighten it here."
    };
  }
}

function paint(canvas: HTMLCanvasElement | null, plane: GreyPlane | null): void {
  if (!canvas) return;
  if (!plane) return;
  canvas.width = plane.width;
  canvas.height = plane.height;
  const context = canvas.getContext("2d");
  if (!context) return;
  context.putImageData(new ImageData(greyToRgba(plane), plane.width, plane.height), 0, 0);
}

export function SketchRectifyField({
  sources,
  targetLabel,
  disabled,
  onAttach
}: {
  sources: SketchSource[];
  targetLabel: string;
  disabled?: boolean;
  onAttach: (file: File) => void;
}) {
  const panelId = useId();
  const [open, setOpen] = useState(false);
  const [sourceRef, setSourceRef] = useState<string | null>(sources[0]?.ref ?? null);
  const [load, setLoad] = useState<LoadState>({ status: "idle" });
  const [corners, setCorners] = useState<Corners | null>(null);
  const [lineArt, setLineArt] = useState(true);
  const [aspectKey, setAspectKey] = useState("auto");
  const [guessNote, setGuessNote] = useState<string | null>(null);
  const [previewMs, setPreviewMs] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [dragging, setDragging] = useState<number | null>(null);

  const previewCanvas = useRef<HTMLCanvasElement | null>(null);
  const imageBox = useRef<HTMLDivElement | null>(null);
  const blobRef = useRef<Blob | null>(null);

  const aspect = ASPECTS.find((entry) => entry.key === aspectKey)?.value;

  useEffect(() => {
    if (!open || !sourceRef) return;
    let cancelled = false;
    let objectUrl: string | null = null;
    setLoad({ status: "loading" });
    setGuessNote(null);
    setProblem(null);
    setDone(null);
    (async () => {
      const got = await loadSourceBlob(sourceRef);
      if (cancelled) return;
      if ("reason" in got) {
        setLoad({ status: "failed", reason: got.reason });
        return;
      }
      const decoded = await loadSketchPlane(got.blob, RECTIFY_MAX_EDGE_PX);
      if (cancelled) return;
      if (!decoded) {
        setLoad({ status: "failed", reason: "This browser can't read that photo." });
        return;
      }
      blobRef.current = got.blob;
      objectUrl = URL.createObjectURL(got.blob);
      setLoad({
        status: "ready",
        plane: decoded.plane,
        url: objectUrl,
        name: got.name,
        decodeMs: decoded.elapsedMs,
        sourceWidth: decoded.sourceWidth,
        sourceHeight: decoded.sourceHeight
      });

      const { width, height } = decoded.plane;
      const inset = orderCorners([
        { x: width * SEED_INSET, y: height * SEED_INSET },
        { x: width * (1 - SEED_INSET), y: height * SEED_INSET },
        { x: width * (1 - SEED_INSET), y: height * (1 - SEED_INSET) },
        { x: width * SEED_INSET, y: height * (1 - SEED_INSET) }
      ]);
      setCorners(inset);
    })();
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [open, sourceRef]);

  useEffect(() => {
    if (load.status !== "ready" || !corners) return;
    const plane = load.plane;
    const timer = setTimeout(() => {
      const plate = makePlate(plane, corners, { maxEdge: PREVIEW_EDGE_PX, aspect, lineArt });
      if (!plate.ok) {
        setProblem(plate.reason);
        return;
      }
      setProblem(null);
      setPreviewMs(plate.elapsedMs);
      paint(previewCanvas.current, plate.plane);
    }, 60);
    return () => clearTimeout(timer);
  }, [load, corners, aspect, lineArt]);

  const runGuess = useCallback(() => {
    if (load.status !== "ready") return;
    const guess = guessSheetCorners(load.plane);
    if (!guess) {
      setGuessNote(
        "Couldn't find the sheet — place the corners yourself."
      );
      return;
    }
    setCorners(guess.corners);
    setGuessNote(
      `Found the sheet. Check the corners.`
    );
  }, [load]);

  const toDisplay = useCallback(
    (point: Point): { left: string; top: string } => {
      if (load.status !== "ready") return { left: "0%", top: "0%" };
      return {
        left: `${(point.x / load.plane.width) * 100}%`,
        top: `${(point.y / load.plane.height) * 100}%`
      };
    },
    [load]
  );

  const moveCorner = useCallback(
    (index: number, clientX: number, clientY: number) => {
      const box = imageBox.current;
      if (!box || load.status !== "ready") return;
      const rect = box.getBoundingClientRect();
      if (rect.width < 1 || rect.height < 1) return;
      const x = Math.min(Math.max((clientX - rect.left) / rect.width, 0), 1) * load.plane.width;
      const y = Math.min(Math.max((clientY - rect.top) / rect.height, 0), 1) * load.plane.height;
      setCorners((current) => {
        if (!current) return current;
        const next = current.map((corner, position) => (position === index ? { x, y } : corner)) as unknown as Point[];
        return next as unknown as Corners;
      });
    },
    [load]
  );

  const endDrag = useCallback(() => {
    setDragging(null);
    setCorners((current) => (current ? (orderCorners([...current]) ?? current) : current));
  }, []);

  useEffect(() => {
    if (dragging === null) return;
    const move = (event: PointerEvent) => moveCorner(dragging, event.clientX, event.clientY);
    const up = () => endDrag();
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
    window.addEventListener("pointercancel", up);
    return () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
      window.removeEventListener("pointercancel", up);
    };
  }, [dragging, moveCorner, endDrag]);

  async function attachPlate() {
    if (load.status !== "ready" || !corners || !blobRef.current) return;
    setBusy(true);
    setProblem(null);
    try {
      const outcome = await rectifySketchFile(blobRef.current, load.name, corners, {
        aspect,
        lineArt,
        maxEdge: RECTIFY_MAX_EDGE_PX
      });
      if (!("file" in outcome)) {
        setProblem(outcome.reason);
        return;
      }
      onAttach(outcome.file);
      setDone(
        `Added ${outcome.file.name} to “${targetLabel}”.`
      );
      setOpen(false);
    } catch (error) {
      setProblem(error instanceof Error ? `The plate could not be made: ${error.message}` : "The plate could not be made.");
    } finally {
      setBusy(false);
    }
  }

  const sourceOptions = useMemo(() => sources.map((source) => source.ref), [sources]);
  if (!sources.length) return null;

  if (!open) {
    return (
      <div className="mt-3">
        <button
          type="button"
          className="field-button-secondary"
          disabled={disabled}
          aria-expanded={false}
          onClick={() => {
            setOpen(true);
            setDone(null);
          }}
        >
          <SquareDashedBottom className="h-4 w-4" aria-hidden />
          Straighten a photographed sketch
        </button>
        <p className="mt-1 text-xs text-ink-500">
          Makes a flat, high-contrast copy for the report. The photo is unchanged.
        </p>
        {done ? (
          <p className="mt-2 flex items-start gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs text-ink-700">
            <Check className="mt-0.5 h-3.5 w-3.5 shrink-0 text-purple-700 dark:text-purple-300" aria-hidden />
            <span>{done}</span>
          </p>
        ) : null}
      </div>
    );
  }

  return (
    <div className="mt-3 rounded-md border border-line-200 bg-surface-50 p-3" id={panelId}>
      <div className="mb-3 flex items-start justify-between gap-2">
        <div>
          <p className="text-sm font-medium text-ink-900">Straighten a photographed sketch</p>
          <p className="mt-0.5 text-xs text-ink-500">
            Added to “{targetLabel}” as a new file.
          </p>
        </div>
        <button
          type="button"
          className="rounded-md p-1 text-ink-500 hover:bg-field-100 hover:text-ink-900"
          onClick={() => setOpen(false)}
          aria-label="Close the straightening panel"
        >
          <X className="h-4 w-4" aria-hidden />
        </button>
      </div>

      {sources.length > 1 ? (
        <div className="mb-3">
          <span className="field-label">Photograph</span>
          <div className="mt-1 flex flex-wrap gap-2">
            {sources.map((source, index) => (
              <button
                key={source.ref}
                type="button"
                onClick={() => setSourceRef(source.ref)}
                className={
                  source.ref === sourceRef
                    ? "rounded-md border border-purple-600 bg-purple-50 px-2.5 py-1 text-xs text-purple-800"
                    : "rounded-md border border-line-200 bg-card px-2.5 py-1 text-xs text-ink-700 hover:border-purple-300"
                }
              >
                {source.fieldLabel} {sourceOptions.length > 1 ? `#${index + 1}` : ""}
              </button>
            ))}
          </div>
        </div>
      ) : null}

      {load.status === "loading" ? <p className="text-sm text-ink-500">Reading the photograph…</p> : null}

      {load.status === "failed" ? (
        <p className="flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-100 px-3 py-2 text-xs text-amber-800">
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
          <span>{load.reason}</span>
        </p>
      ) : null}

      {load.status === "ready" && corners ? (
        <>
          <div className="grid gap-3 sm:grid-cols-2">
            <div>
              <span className="field-label">Drag the sheet&apos;s corners</span>
              <div
                ref={imageBox}
                className="relative mt-1 select-none overflow-hidden rounded-md border border-line-200 bg-card"
                style={{ touchAction: "none" }}
              >
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={load.url} alt="" className="block w-full" draggable={false} />
                <svg className="pointer-events-none absolute inset-0 h-full w-full" aria-hidden>
                  <polygon
                    points={corners
                      .map((corner) => `${(corner.x / load.plane.width) * 100}%,${(corner.y / load.plane.height) * 100}%`)
                      .join(" ")}
                    className="fill-purple-600/15 stroke-purple-700"
                    strokeWidth={2}
                  />
                </svg>
                {corners.map((corner, index) => {
                  const position = toDisplay(corner);
                  return (
                    <button
                      key={index}
                      type="button"
                      aria-label={`Sheet corner ${index + 1}`}
                      onPointerDown={(event) => {
                        event.preventDefault();
                        setDragging(index);
                      }}
                      onKeyDown={(event) => {
                        const step = event.shiftKey ? 10 : 1;
                        const delta: Record<string, [number, number]> = {
                          ArrowLeft: [-step, 0],
                          ArrowRight: [step, 0],
                          ArrowUp: [0, -step],
                          ArrowDown: [0, step]
                        };
                        const move = delta[event.key];
                        if (!move) return;
                        event.preventDefault();
                        setCorners((current) => {
                          if (!current) return current;
                          const next = current.map((entry, position2) =>
                            position2 === index
                              ? {
                                  x: Math.min(Math.max(entry.x + move[0], 0), load.plane.width),
                                  y: Math.min(Math.max(entry.y + move[1], 0), load.plane.height)
                                }
                              : entry
                          ) as unknown as Corners;
                          return next;
                        });
                      }}
                      style={{ left: position.left, top: position.top }}
                      className="absolute -ml-3 -mt-3 h-6 w-6 rounded-full border-2 border-white bg-purple-700 shadow-md"
                    />
                  );
                })}
              </div>
              <p className="mt-1 text-xs text-ink-500">
                {load.sourceWidth}x{load.sourceHeight}, decoded in {Math.round(load.decodeMs)} ms.
              </p>
            </div>

            <div>
              <span className="field-label">The plate</span>
              <div className="mt-1 overflow-hidden rounded-md border border-line-200 bg-card">
                <canvas ref={previewCanvas} className="block w-full" />
              </div>
              <p className="mt-1 text-xs text-ink-500">
                Preview at {PREVIEW_EDGE_PX}px{previewMs === null ? "" : `, redrawn in ${Math.round(previewMs)} ms`}. The
                attached plate is made at {RECTIFY_MAX_EDGE_PX}px.
              </p>
            </div>
          </div>

          <div className="mt-3 flex flex-wrap items-center gap-2">
            <button type="button" className="field-button-secondary" onClick={runGuess}>
              <Scan className="h-4 w-4" aria-hidden />
              Find the sheet
            </button>
            <Toggle checked={lineArt} onChange={setLineArt} label="Extract line art" className="gap-3" />
            <span aria-hidden className="text-xs text-ink-700">
              Sheet
            </span>
            <SearchableSelect
              className="w-40"
              ariaLabel="Sheet"
              searchable={false}
              value={aspectKey}
              onChange={setAspectKey}
              options={ASPECTS.map((entry) => ({ value: entry.key, label: entry.label }))}
              advanceOnSelect={false}
            />
          </div>

          {!lineArt ? (
            <p className="mt-2 text-xs text-ink-500">
              Keeps pencil tones and shading.
            </p>
          ) : null}

          {guessNote ? (
            <p className="mt-2 rounded-md border border-line-200 bg-card px-3 py-2 text-xs text-ink-700">{guessNote}</p>
          ) : null}

          {problem ? (
            <p className="mt-2 flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-100 px-3 py-2 text-xs text-amber-800">
              <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
              <span>{problem}</span>
            </p>
          ) : null}

          <ActionGroup className="mt-3">
            <button type="button" className="field-button" onClick={attachPlate} disabled={busy || disabled}>
              <Wand2 className="h-4 w-4" aria-hidden />
              {busy ? "Making the plate…" : `Add this plate to “${targetLabel}”`}
            </button>
            <button type="button" className="field-button-secondary" onClick={() => setOpen(false)} disabled={busy}>
              Keep the original only
            </button>
          </ActionGroup>
        </>
      ) : null}
    </div>
  );
}
