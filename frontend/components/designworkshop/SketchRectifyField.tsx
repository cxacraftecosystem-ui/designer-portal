"use client";

/**
 * "Straighten this sketch into a plate" — the surface over `lib/sketchRectify.ts`.
 *
 * WHAT THIS IS FOR. Stage 11's Advanced tier asks for perspective correction, background cleanup and
 * line-art extraction. What actually reaches the archive is a photograph of a sheet of paper lying on
 * a table, taken from wherever the designer was standing, under a tube light on one wall — and the
 * report prints exactly that: a skewed grey rectangle with a drawing somewhere inside it. Four
 * corners and a local threshold turn it into a plate. All of it is arithmetic on this device, which
 * is the only way it can happen in the village where the sketch was drawn.
 *
 * IT NEVER TOUCHES THE PHOTOGRAPH, AND THE FIELD IT IS RENDERED ON IS HOW THAT IS GUARANTEED.
 *
 * This panel is offered on `sketch.lineArtFile`, not on `sketch.image`, and that placement is the
 * safety property rather than a layout preference. A single IMAGE field REPLACES its value when a
 * file is attached to it, so a panel that produced a plate into `image` would detach the original
 * photograph from the record — precisely the outcome docs/MEDIA_PIPELINE.md §5 refuses ("the original
 * file *is* the artifact"). Rendered here, the plate is written where a plate belongs and `image` is
 * never written to at all. See `stageFieldRoles.offersSketchRectify`.
 *
 * The derived file also goes in through the ordinary door — `attach`, the same one a camera
 * photograph uses — so eager pre-upload, per-file retry and the offline draft store all already apply
 * to it. Nothing here uploads anything itself.
 *
 * "KEEP THE ORIGINAL ONLY" IS A REAL ANSWER AND IS ON SCREEN AS ONE.
 *
 * An over-processed sketch has lost something. A faint construction line, a smudged tone that showed
 * where a curve was being felt out, a pencil note in the margin — a threshold is a decision to
 * discard everything on one side of it, and the person who can tell whether that mattered is the
 * designer looking at both pictures with the actual sheet still in front of them. So the before and
 * the after are shown side by side, at the same size, and declining costs one press and no
 * explanation. There is also a middle answer — straighten it and keep the tones — because "the
 * geometry was wrong but the greys were the drawing" is a common and correct thing to think.
 *
 * NOTHING HERE BLOCKS ANYTHING. The panel writes only when a button is pressed, every refusal is a
 * sentence about what to do next, and a designer who ignores it entirely still has an ordinary file
 * picker underneath.
 */

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { AlertTriangle, Check, Scan, SquareDashedBottom, Wand2, X } from "lucide-react";

import { apiFetch } from "@/lib/api";
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

/**
 * A photograph this panel could make a plate from: a media id, or a `dwlocal:` reference to bytes
 * held on this device.
 */
export type SketchSource = {
  /** The media id or `dwlocal:` reference stored in the sibling image field. */
  ref: string;
  /** The registry label of the field it came from, so a designer can tell two image fields apart. */
  fieldLabel: string;
};

/**
 * The long edge the LIVE preview is computed at.
 *
 * Deliberately far below {@link RECTIFY_MAX_EDGE_PX}. The preview is recomputed on every corner drag,
 * and the full-size plate is ~1.9M pixels through a bilinear resample and two integral-image passes —
 * about 100–180ms on a laptop and several times that on the handsets this app runs on, which as a
 * per-frame cost is a control that lurches. At 420px the same work is ~20x cheaper and the picture is
 * larger than the box it is drawn in, so nothing visible is lost. The plate that is actually ATTACHED
 * is always computed at full size, once, when the button is pressed.
 */
const PREVIEW_EDGE_PX = 420;

/** Corner handles start inset from the frame rather than on it, so all four are grabbable. */
const SEED_INSET = 0.08;

type Aspect = { key: string; label: string; value?: number };

/**
 * The sheet proportions worth offering, and why a list rather than a free number.
 *
 * `rectifiedSize` explains that four image points of an unknown rectangle do not determine its true
 * proportions — that needs the camera's focal length, which these photographs do not carry. The
 * designer, however, very often knows: the sheet was A4, because that is what the workshop had. This
 * is the one place that knowledge can enter, and it is a short closed list because "the paper I drew
 * on" has about three answers and a free-text ratio would invite a typo that silently squashes a
 * plate.
 */
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

/**
 * Get the bytes behind one stored reference.
 *
 * THE TWO CASES ARE NOT EQUALLY RELIABLE AND THE DIFFERENCE IS THE POINT. A `dwlocal:` reference is
 * bytes in this device's own draft store, so it is always readable — and that is the case that
 * matters, because a photograph taken minutes ago in a village with no connection IS a `dwlocal:`
 * reference. An uploaded media id means a cross-origin read of a presigned URL, which needs a bucket
 * CORS rule this application does not otherwise require (`IdentityCardReader` declines to attempt it
 * at all for exactly this reason). It is attempted here rather than refused up front, because where
 * the rule does exist the feature simply works on an older sketch — but a failure is reported as the
 * ordinary, expected thing it is, with the action that fixes it, and never as a broken feature.
 */
async function loadSourceBlob(ref: string): Promise<{ blob: Blob; name: string } | { reason: string }> {
  if (isLocalMediaRef(ref)) {
    const media = await readLocalMedia(ref);
    if (!media?.blob) {
      return { reason: "The copy of that photograph on this device has already been released. Re-attach it to straighten it." };
    }
    return { blob: media.blob, name: media.name };
  }
  let media: MediaFile;
  try {
    media = await apiFetch<MediaFile>(`/media/${ref}`);
  } catch {
    return { reason: "That photograph could not be read from the server just now. It can be straightened later, or re-attach it here." };
  }
  if (!media.url) {
    return { reason: "That photograph's file is not available to this account, so it cannot be straightened here." };
  }
  try {
    const response = await fetch(media.url);
    if (!response.ok) throw new Error(String(response.status));
    return { blob: await response.blob(), name: media.originalFilename || "sketch" };
  } catch {
    return {
      reason:
        "This photograph was uploaded in an earlier session and its file cannot be read back in the browser. Re-attach it here to straighten it, or straighten it on the handset that took it."
    };
  }
}

/** Paint a grey plane into a canvas, sizing the canvas to it. */
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
  /** The registry label of the field the plate lands in, quoted on the button so it is unambiguous. */
  targetLabel: string;
  disabled?: boolean;
  /** Hands the derived file to the capture card — the same door a camera photograph goes in by. */
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

  // Load the chosen photograph's bytes, decode to a bounded plane, and seed the corners. The object
  // URL is created and revoked in this one effect, which is what keeps the pair together: revoked
  // anywhere else it either leaks for the life of the tab or is released while the <img> is still
  // reading it, and the second looks like a photograph that vanished.
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
        setLoad({ status: "failed", reason: "This browser could not decode that photograph, so it cannot be straightened here." });
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

      // MANUAL IS THE DEFAULT, ALWAYS. The handles are seeded inset from the frame first, so there is
      // a usable quadrilateral before any guess is attempted; a guess that succeeds MOVES them and
      // says so, and a guess that declines leaves this standing with nothing on screen about it. See
      // guessSheetCorners for why an uncertain guess is silent rather than hedged.
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

  // The live preview. Recomputed off a short timer rather than on every pointer event: a drag fires
  // pointermove far faster than a plate can be built, and without the gap the queue of stale
  // recomputations is what makes a control feel heavy. PREVIEW_EDGE_PX is the other half of that.
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
      // Silent in the module, said out loud here — the designer pressed a button and is owed an
      // answer, and "I could not tell" is an answer. What it must never do is move the handles to a
      // quadrilateral it does not believe in.
      setGuessNote(
        "The edges of the sheet could not be made out in this photograph — the corners below are yours to place."
      );
      return;
    }
    setCorners(guess.corners);
    setGuessNote(
      `Found a sheet filling ${Math.round(guess.frameShare * 100)}% of the frame. Check the four corners and drag any that are off.`
    );
  }, [load]);

  /** Plane pixels -> the box the photograph is drawn in, and back. Corners are stored in PLANE pixels. */
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
      // Clamped to the photograph: a corner dragged outside it would sample OUTSIDE_VALUE for a whole
      // margin of the plate, which is legal but is almost never what a hand slipping off the edge of
      // a phone screen meant.
      const x = Math.min(Math.max((clientX - rect.left) / rect.width, 0), 1) * load.plane.width;
      const y = Math.min(Math.max((clientY - rect.top) / rect.height, 0), 1) * load.plane.height;
      setCorners((current) => {
        if (!current) return current;
        const next = current.map((corner, position) => (position === index ? { x, y } : corner)) as unknown as Point[];
        // NOT re-ordered while dragging. orderCorners would relabel the handles the moment a drag
        // carried one past a neighbour, so the handle would leap out from under the finger and the
        // designer would be dragging a different corner than the one they grabbed. The ring is
        // re-established once, on release.
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
      // Full size, once, on the button — never on a drag. See PREVIEW_EDGE_PX.
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
        `${outcome.file.name} was added to “${targetLabel}”. The photograph itself is untouched. ` +
          `Decoded in ${Math.round(outcome.decodeMs)} ms, straightened in ${Math.round(outcome.processMs)} ms.`
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
          Marks the four corners of the sheet and makes a flat, high-contrast plate for the report. The photograph you
          took stays attached exactly as it is.
        </p>
        {done ? (
          <p className="mt-2 flex items-start gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs text-ink-700">
            <Check className="mt-0.5 h-3.5 w-3.5 shrink-0 text-purple-700" aria-hidden />
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
            The plate is a new file added to “{targetLabel}”. Your photograph is never modified or replaced.
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
          {/* BEFORE and AFTER, the same size, side by side from sm up. The whole judgement this panel
              asks for — did the processing lose something that mattered — is a comparison, and a
              comparison the designer has to make by remembering the other picture is not one. */}
          <div className="grid gap-3 sm:grid-cols-2">
            <div>
              <span className="field-label">The photograph — drag the four corners of the sheet</span>
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
                        // A keyboard route to every handle, because a corner that can only be placed
                        // by dragging is a corner a keyboard user cannot place at all. One plane pixel
                        // per press, ten with shift.
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
            <label className="flex items-center gap-2 text-xs text-ink-700">
              <input
                type="checkbox"
                checked={lineArt}
                onChange={(event) => setLineArt(event.target.checked)}
                className="h-4 w-4 accent-purple-700"
              />
              Extract line art
            </label>
            <label className="flex items-center gap-2 text-xs text-ink-700">
              {/* A native <select>, deliberately. ASPECTS is four sheet ratios declared in this file
                  and it will not grow — the fixed-vocabulary side of the rule in
                  `SearchableSelectProps.searchable`, where a filter box is a tab stop and a "No
                  matches" state over a list read at a glance. It also sits inside a <label>, which
                  a themed dropdown may not do (a <label> forwards a stray click into the menu and
                  slams it shut), and it takes no id from `Dropdown` to bind that label to. */}
              Sheet
              <select
                className="field-input !w-auto !py-1 text-xs"
                value={aspectKey}
                onChange={(event) => setAspectKey(event.target.value)}
              >
                {ASPECTS.map((entry) => (
                  <option key={entry.key} value={entry.key}>
                    {entry.label}
                  </option>
                ))}
              </select>
            </label>
          </div>

          {!lineArt ? (
            <p className="mt-2 text-xs text-ink-500">
              Straightened only — the pencil tones are kept as they are. Choose this when a faint construction line or a
              shaded area is part of the drawing.
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

          <div className="mt-3 flex flex-wrap gap-2">
            <button type="button" className="field-button" onClick={attachPlate} disabled={busy || disabled}>
              <Wand2 className="h-4 w-4" aria-hidden />
              {busy ? "Making the plate…" : `Add this plate to “${targetLabel}”`}
            </button>
            {/* A FIRST-CLASS OUTCOME, not a cancel. Same row, same size, no warning and no
                confirmation: deciding the processing lost something is a correct decision, and it
                should cost exactly one press. */}
            <button type="button" className="field-button-secondary" onClick={() => setOpen(false)} disabled={busy}>
              Keep the original only
            </button>
          </div>
        </>
      ) : null}
    </div>
  );
}
