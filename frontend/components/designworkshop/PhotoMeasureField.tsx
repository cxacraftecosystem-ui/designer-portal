"use client";

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { AlertTriangle, Check, Crosshair, Ruler, X, ZoomIn, ZoomOut } from "lucide-react";

import { Dropdown } from "@/components/ui/Dropdown";
import { inputValue, type DwEntryData, type DwField, type DwValue } from "@/lib/designWorkshops";
import {
  LENGTH_UNITS,
  convertLength,
  markSigmaForDisplayScale,
  measureByRectification,
  measureBySameScale,
  methodMarker,
  roundToUncertainty,
  type LengthUnit,
  type MeasureResult,
  type MeasurementMarker,
  type Point
} from "@/lib/photoMeasure";
import { ActionGroup } from "@/components/ui/ActionGroup";

export type MeasurablePhoto = { key: string; name: string; url: string };

export type MeasureTarget = { field: DwField; unit: LengthUnit };

type MarkId = "refA" | "refB" | "c0" | "c1" | "c2" | "c3" | "tgtA" | "tgtB";

type Mark = {
  point: Point;
  sigma: number;
  placed: boolean;
};

type Mode = "SCALE" | "RECTIFY";

const SCALE_MARKS: MarkId[] = ["refA", "refB", "tgtA", "tgtB"];
const RECTIFY_MARKS: MarkId[] = ["c0", "c1", "c2", "c3", "tgtA", "tgtB"];

const MARK_LABELS: Record<MarkId, { badge: string; name: string }> = {
  refA: { badge: "R1", name: "Reference, first end" },
  refB: { badge: "R2", name: "Reference, second end" },
  c0: { badge: "1", name: "Rectangle corner 1" },
  c1: { badge: "2", name: "Rectangle corner 2 (width edge)" },
  c2: { badge: "3", name: "Rectangle corner 3 (opposite 1)" },
  c3: { badge: "4", name: "Rectangle corner 4" },
  tgtA: { badge: "A", name: "The dimension, first end" },
  tgtB: { badge: "B", name: "The dimension, second end" }
};

const SEEDS: Record<MarkId, Point> = {
  refA: { x: 0.14, y: 0.84 },
  refB: { x: 0.52, y: 0.84 },
  c0: { x: 0.2, y: 0.2 },
  c1: { x: 0.8, y: 0.22 },
  c2: { x: 0.82, y: 0.78 },
  c3: { x: 0.18, y: 0.76 },
  tgtA: { x: 0.3, y: 0.42 },
  tgtB: { x: 0.72, y: 0.44 }
};

const SCALE_PRESETS: { label: string; length: number; unit: LengthUnit }[] = [
  { label: "Scale card, 100 mm", length: 100, unit: "mm" },
  { label: "Steel rule, 300 mm", length: 300, unit: "mm" },
  { label: "A4 short edge, 210 mm", length: 210, unit: "mm" },
  { label: "₹5 coin, 23 mm", length: 23, unit: "mm" }
];

const RECT_PRESETS: { label: string; width: number; height: number; unit: LengthUnit }[] = [
  { label: "A4 sheet", width: 210, height: 297, unit: "mm" },
  { label: "A5 sheet", width: 148, height: 210, unit: "mm" },
  { label: "Bank/ID card", width: 85.6, height: 54, unit: "mm" }
];

const UNIT_OPTIONS = (Object.keys(LENGTH_UNITS) as LengthUnit[]).map((unit) => ({ value: unit, label: unit }));

const MIN_ZOOM = 1;
const MAX_ZOOM = 40;

const TAP_SLOP_PX = 4;

function capturePointer(element: Element, pointerId: number): void {
  try {
    (element as HTMLElement).setPointerCapture(pointerId);
  } catch {
  }
}

export function PhotoMeasureField({
  photos,
  targets,
  row,
  onPropose,
  disabled,
  open: controlledOpen,
  onOpenChange
}: {
  photos: MeasurablePhoto[];
  targets: MeasureTarget[];
  row: DwEntryData;
  onPropose: (key: string, value: DwValue, method: MeasurementMarker) => void;
  disabled?: boolean;
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
}) {
  const panelId = useId();
  const [selfOpen, setSelfOpen] = useState(false);
  const open = controlledOpen ?? selfOpen;
  const setOpen = useCallback(
    (next: boolean) => {
      setSelfOpen(next);
      onOpenChange?.(next);
    },
    [onOpenChange]
  );
  const [photoKey, setPhotoKey] = useState<string | null>(null);
  const [mode, setMode] = useState<Mode>("SCALE");
  const [marks, setMarks] = useState<Partial<Record<MarkId, Mark>>>({});
  const [active, setActive] = useState<MarkId>("refA");
  const [natural, setNatural] = useState<{ width: number; height: number } | null>(null);
  const [box, setBox] = useState<{ width: number; height: number }>({ width: 0, height: 0 });
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState<Point>({ x: 0, y: 0 });
  const [referenceLength, setReferenceLength] = useState("");
  const [referenceUnit, setReferenceUnit] = useState<LengthUnit>("mm");
  const [rectWidth, setRectWidth] = useState("");
  const [rectHeight, setRectHeight] = useState("");
  const [rectUnit, setRectUnit] = useState<LengthUnit>("mm");

  const viewportRef = useRef<HTMLDivElement | null>(null);
  const pointers = useRef(new Map<number, Point>());
  const pinchDistance = useRef<number | null>(null);
  const dragging = useRef<MarkId | null>(null);
  const gestureMoved = useRef(false);

  const photo = photos.find((candidate) => candidate.key === photoKey) ?? photos[0] ?? null;
  const needed = mode === "SCALE" ? SCALE_MARKS : RECTIFY_MARKS;

  useEffect(() => {
    if (photoKey && !photos.some((candidate) => candidate.key === photoKey)) {
      setPhotoKey(photos[0]?.key ?? null);
      setNatural(null);
      setMarks({});
    }
  }, [photos, photoKey]);

  useEffect(() => {
    const node = viewportRef.current;
    if (!node || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver((entries) => {
      const rect = entries[0]?.contentRect;
      if (rect) setBox({ width: rect.width, height: rect.height });
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, [open, photo?.key]);

  const fit = useMemo(() => {
    if (!natural || !box.width || !box.height) return 0;
    return Math.min(box.width / natural.width, box.height / natural.height);
  }, [natural, box]);

  const displayScale = fit * zoom;

  const clampPan = useCallback(
    (next: Point, atZoom: number): Point => {
      if (!natural || !fit) return next;
      const width = natural.width * fit * atZoom;
      const height = natural.height * fit * atZoom;
      const clampAxis = (value: number, span: number, container: number) =>
        span <= container ? (container - span) / 2 : Math.min(0, Math.max(container - span, value));
      return { x: clampAxis(next.x, width, box.width), y: clampAxis(next.y, height, box.height) };
    },
    [natural, fit, box]
  );

  useEffect(() => {
    setZoom(1);
    setPan(clampPan({ x: 0, y: 0 }, 1));
  }, [clampPan]);

  const imageToView = useCallback(
    (point: Point): Point => ({ x: pan.x + point.x * displayScale, y: pan.y + point.y * displayScale }),
    [pan, displayScale]
  );

  const viewToImage = useCallback(
    (point: Point): Point => ({ x: (point.x - pan.x) / displayScale, y: (point.y - pan.y) / displayScale }),
    [pan, displayScale]
  );

  const localPoint = useCallback((event: { clientX: number; clientY: number }): Point => {
    const rect = viewportRef.current?.getBoundingClientRect();
    return { x: event.clientX - (rect?.left ?? 0), y: event.clientY - (rect?.top ?? 0) };
  }, []);

  useEffect(() => {
    if (!natural) return;
    setMarks((current) => {
      let changed = false;
      const next = { ...current };
      for (const id of needed) {
        if (next[id]) continue;
        next[id] = {
          point: { x: SEEDS[id].x * natural.width, y: SEEDS[id].y * natural.height },
          sigma: markSigmaForDisplayScale(displayScale),
          placed: false
        };
        changed = true;
      }
      return changed ? next : current;
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [natural, mode]);

  const setMark = useCallback(
    (id: MarkId, point: Point) => {
      if (!natural) return;
      const clamped = {
        x: Math.min(natural.width, Math.max(0, point.x)),
        y: Math.min(natural.height, Math.max(0, point.y))
      };
      setMarks((current) => ({
        ...current,
        [id]: { point: clamped, sigma: markSigmaForDisplayScale(displayScale), placed: true }
      }));
    },
    [natural, displayScale]
  );

  const zoomBy = useCallback(
    (factor: number, centre?: Point) => {
      setZoom((currentZoom) => {
        const nextZoom = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, currentZoom * factor));
        const anchor = centre ?? { x: box.width / 2, y: box.height / 2 };
        setPan((currentPan) =>
          clampPan(
            {
              x: anchor.x - ((anchor.x - currentPan.x) * nextZoom) / currentZoom,
              y: anchor.y - ((anchor.y - currentPan.y) * nextZoom) / currentZoom
            },
            nextZoom
          )
        );
        return nextZoom;
      });
    },
    [box, clampPan]
  );

  function onViewportPointerDown(event: React.PointerEvent<HTMLDivElement>) {
    if (disabled) return;
    capturePointer(event.currentTarget, event.pointerId);
    pointers.current.set(event.pointerId, localPoint(event));
    gestureMoved.current = false;
    if (pointers.current.size === 2) {
      const [a, b] = Array.from(pointers.current.values());
      pinchDistance.current = Math.hypot(b.x - a.x, b.y - a.y);
    }
  }

  function onViewportPointerMove(event: React.PointerEvent<HTMLDivElement>) {
    if (!pointers.current.has(event.pointerId)) return;
    const previous = pointers.current.get(event.pointerId)!;
    const current = localPoint(event);
    pointers.current.set(event.pointerId, current);

    if (pointers.current.size >= 2) {
      const [a, b] = Array.from(pointers.current.values());
      const distance = Math.hypot(b.x - a.x, b.y - a.y);
      const previousDistance = pinchDistance.current;
      pinchDistance.current = distance;
      gestureMoved.current = true;
      if (previousDistance && previousDistance > 0 && distance > 0) {
        zoomBy(distance / previousDistance, { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 });
      }
      return;
    }

    const dx = current.x - previous.x;
    const dy = current.y - previous.y;
    if (Math.abs(dx) > TAP_SLOP_PX || Math.abs(dy) > TAP_SLOP_PX) gestureMoved.current = true;
    setPan((currentPan) => clampPan({ x: currentPan.x + dx, y: currentPan.y + dy }, zoom));
  }

  function onViewportPointerUp(event: React.PointerEvent<HTMLDivElement>) {
    const wasSingle = pointers.current.size === 1;
    pointers.current.delete(event.pointerId);
    if (pointers.current.size < 2) pinchDistance.current = null;
    if (wasSingle && !gestureMoved.current && !dragging.current && natural) {
      setMark(active, viewToImage(localPoint(event)));
      const index = needed.indexOf(active);
      if (index >= 0 && index < needed.length - 1) setActive(needed[index + 1]);
    }
    gestureMoved.current = false;
  }

  function handleFor(id: MarkId) {
    const mark = marks[id];
    if (!mark || !natural || !fit) return null;
    const view = imageToView(mark.point);
    const isTarget = id === "tgtA" || id === "tgtB";
    const isActive = active === id;
    return (
      <button
        key={id}
        type="button"
        disabled={disabled}
        aria-label={`${MARK_LABELS[id].name}${mark.placed ? "" : " — not placed"}. Drag or use arrow keys.`}
        aria-pressed={isActive}
        className={[
          "pointer-events-auto absolute grid h-8 w-8 -translate-x-1/2 -translate-y-1/2 place-items-center",
          "rounded-full text-[0.625rem] font-bold leading-none shadow-md transition-shadow",
          isTarget
            ? "border-2 border-purple-700 bg-card text-purple-800 dark:text-purple-300"
            : "border-2 border-card bg-purple-700 text-white",
          isActive ? "ring-4 ring-purple-600/30" : "",
          mark.placed ? "" : "opacity-70"
        ].join(" ")}
        style={{ left: `${view.x}px`, top: `${view.y}px` }}
        onPointerDown={(event) => {
          if (disabled) return;
          event.stopPropagation();
          capturePointer(event.currentTarget, event.pointerId);
          dragging.current = id;
          setActive(id);
        }}
        onPointerMove={(event) => {
          if (dragging.current !== id) return;
          event.stopPropagation();
          setMark(id, viewToImage(localPoint(event)));
        }}
        onPointerUp={(event) => {
          if (dragging.current !== id) return;
          event.stopPropagation();
          dragging.current = null;
        }}
        onPointerCancel={() => {
          if (dragging.current === id) dragging.current = null;
        }}
        onKeyDown={(event) => {
          const step = event.shiftKey ? 10 : 1;
          const delta: Record<string, Point> = {
            ArrowLeft: { x: -step, y: 0 },
            ArrowRight: { x: step, y: 0 },
            ArrowUp: { x: 0, y: -step },
            ArrowDown: { x: 0, y: step }
          };
          const move = delta[event.key];
          if (!move) return;
          event.preventDefault();
          setMark(id, { x: mark.point.x + move.x, y: mark.point.y + move.y });
        }}
      >
        <span aria-hidden>{MARK_LABELS[id].badge}</span>
      </button>
    );
  }

  const allPlaced = needed.every((id) => marks[id]?.placed);

  const markSigmaPx = useMemo(() => {
    const sigmas = needed.map((id) => marks[id]?.sigma).filter((value): value is number => typeof value === "number");
    return sigmas.length ? Math.max(...sigmas) : undefined;
  }, [needed, marks]);

  const result: MeasureResult | null = useMemo(() => {
    if (!allPlaced || !markSigmaPx) return null;
    const target = { from: marks.tgtA!.point, to: marks.tgtB!.point };
    if (mode === "SCALE") {
      const length = Number(referenceLength);
      if (!referenceLength.trim() || !Number.isFinite(length)) return null;
      return measureBySameScale({
        reference: { from: marks.refA!.point, to: marks.refB!.point, length, unit: referenceUnit },
        target,
        markSigmaPx
      });
    }
    const width = Number(rectWidth);
    const height = Number(rectHeight);
    if (!rectWidth.trim() || !rectHeight.trim() || !Number.isFinite(width) || !Number.isFinite(height)) return null;
    return measureByRectification({
      corners: [marks.c0!.point, marks.c1!.point, marks.c2!.point, marks.c3!.point],
      rectangle: { width, height, unit: rectUnit },
      target,
      markSigmaPx
    });
  }, [allPlaced, markSigmaPx, marks, mode, referenceLength, referenceUnit, rectWidth, rectHeight, rectUnit]);

  if (!photos.length || !targets.length) return null;

  if (!open) {
    return (
      <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3">
        <div className="flex flex-wrap items-center gap-2">
          <Ruler className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <span className="text-sm font-medium text-ink-900">Measure a dimension from a photograph</span>
        </div>
        <p className="text-xs leading-5 text-ink-500">
          With a ruler or scale card in a photo, measure {targets.map((entry) => entry.field.label).join(", ")}. Works
          offline.
        </p>
        <div>
          <button
            type="button"
            className="field-button-secondary"
            disabled={disabled}
            aria-expanded={false}
            onClick={() => setOpen(true)}
          >
            <Ruler className="h-4 w-4" aria-hidden />
            Measure from a photograph
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="grid gap-3 rounded-md border border-line-200 bg-surface-50 p-3" id={panelId}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="flex items-center gap-2 text-sm font-medium text-ink-900">
          <Ruler className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          Measure a dimension from a photograph
        </span>
        <button
          type="button"
          className="inline-flex items-center gap-1 text-xs font-medium text-ink-500 underline"
          aria-expanded
          aria-controls={panelId}
          onClick={() => setOpen(false)}
        >
          <X className="h-3 w-3" aria-hidden />
          Close
        </button>
      </div>

      {photos.length > 1 ? (
        <div className="grid gap-1">
          <span className="field-label">Photograph</span>
          <div className="flex flex-wrap gap-1.5">
            {photos.map((candidate) => {
              const chosen = candidate.key === photo?.key;
              return (
                <button
                  key={candidate.key}
                  type="button"
                  aria-pressed={chosen}
                  className={
                    chosen
                      ? "rounded-full bg-purple-700 px-3 py-1 text-xs font-medium text-white"
                      : "rounded-full border border-line-200 bg-card px-3 py-1 text-xs font-medium text-ink-900 hover:border-purple-300 hover:bg-purple-50"
                  }
                  onClick={() => {
                    setPhotoKey(candidate.key);
                    setNatural(null);
                    setMarks({});
                    setActive(needed[0]);
                  }}
                >
                  {candidate.name}
                </button>
              );
            })}
          </div>
        </div>
      ) : null}

      <div className="grid gap-1">
        <span className="field-label">Method</span>
        <div className="flex flex-wrap gap-2">
          {(
            [
              ["SCALE", "Same plane (2 marks)"],
              ["RECTIFY", "Tilted — rectify a rectangle (4 corners)"]
            ] as [Mode, string][]
          ).map(([value, label]) => (
            <button
              key={value}
              type="button"
              aria-pressed={mode === value}
              disabled={disabled}
              className={
                mode === value
                  ? "inline-flex min-h-10 items-center rounded-md bg-purple-700 px-3 py-2 text-sm font-medium text-white"
                  : "inline-flex min-h-10 items-center rounded-md border border-line-200 bg-card px-3 py-2 text-sm font-medium text-ink-900 hover:border-purple-300 hover:bg-purple-50"
              }
              onClick={() => {
                setMode(value);
                setActive(value === "SCALE" ? "refA" : "c0");
              }}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      {mode === "SCALE" ? (
        <p className="flex items-start gap-2 rounded-md border border-amber-500 bg-amber-100 px-2.5 py-2 text-xs leading-5 text-amber-800">
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
          <span>
            Only accurate when the reference and object are in the <strong>same flat plane, square to the camera</strong>.
            For angled photos, use four corners.
          </span>
        </p>
      ) : (
        <p className="flex items-start gap-2 rounded-md border border-line-200 bg-card px-2.5 py-2 text-xs leading-5 text-ink-500">
          <Crosshair className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
          <span>
            Mark the corners <strong>in order around the edge</strong>. Tilt is corrected; the object must lie
            <strong> on</strong> that surface.
          </span>
        </p>
      )}

      <div className="grid gap-1">
        <span className="field-label">Marks — tap to place, drag to adjust</span>
        <div className="flex flex-wrap gap-1.5">
          {needed.map((id) => {
            const mark = marks[id];
            return (
              <button
                key={id}
                type="button"
                aria-pressed={active === id}
                disabled={disabled}
                className={
                  active === id
                    ? "rounded-full bg-purple-700 px-3 py-1 text-xs font-medium text-white"
                    : "rounded-full border border-line-200 bg-card px-3 py-1 text-xs font-medium text-ink-900 hover:border-purple-300 hover:bg-purple-50"
                }
                onClick={() => setActive(id)}
              >
                {MARK_LABELS[id].badge} · {mark?.placed ? "placed" : "not placed"}
              </button>
            );
          })}
        </div>
      </div>

      <div
        ref={viewportRef}
        className="relative h-72 w-full overflow-hidden rounded-md border border-line-200 bg-field-100 sm:h-96"
        style={{ touchAction: "none" }}
        onPointerDown={onViewportPointerDown}
        onPointerMove={onViewportPointerMove}
        onPointerUp={onViewportPointerUp}
        onPointerCancel={onViewportPointerUp}
        onWheel={(event) => {
          if (disabled) return;
          event.preventDefault();
          zoomBy(event.deltaY < 0 ? 1.12 : 1 / 1.12, localPoint(event));
        }}
      >
        {photo ? (
          <>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={photo.url}
              alt={`${photo.name} — place marks to measure`}
              draggable={false}
              className="pointer-events-none absolute left-0 top-0 max-w-none select-none"
              style={
                natural && fit
                  ? {
                      width: `${natural.width * displayScale}px`,
                      height: `${natural.height * displayScale}px`,
                      transform: `translate(${pan.x}px, ${pan.y}px)`
                    }
                  : { visibility: "hidden" }
              }
              onLoad={(event) => {
                const image = event.currentTarget;
                if (image.naturalWidth && image.naturalHeight) {
                  setNatural({ width: image.naturalWidth, height: image.naturalHeight });
                }
              }}
            />

            {natural && fit ? (
              <svg aria-hidden className="pointer-events-none absolute inset-0 h-full w-full" role="presentation">
                {mode === "SCALE" && marks.refA && marks.refB ? (
                  <line
                    x1={imageToView(marks.refA.point).x}
                    y1={imageToView(marks.refA.point).y}
                    x2={imageToView(marks.refB.point).x}
                    y2={imageToView(marks.refB.point).y}
                    className="stroke-purple-700"
                    strokeWidth={2}
                    strokeDasharray="6 4"
                  />
                ) : null}
                {mode === "RECTIFY" && marks.c0 && marks.c1 && marks.c2 && marks.c3 ? (
                  <polygon
                    points={[marks.c0, marks.c1, marks.c2, marks.c3]
                      .map((mark) => {
                        const view = imageToView(mark.point);
                        return `${view.x},${view.y}`;
                      })
                      .join(" ")}
                    className="fill-purple-700/10 stroke-purple-700"
                    strokeWidth={2}
                    strokeDasharray="6 4"
                  />
                ) : null}
                {marks.tgtA && marks.tgtB ? (
                  <line
                    x1={imageToView(marks.tgtA.point).x}
                    y1={imageToView(marks.tgtA.point).y}
                    x2={imageToView(marks.tgtB.point).x}
                    y2={imageToView(marks.tgtB.point).y}
                    className="stroke-purple-700"
                    strokeWidth={3}
                  />
                ) : null}
              </svg>
            ) : null}

            <div className="pointer-events-none absolute inset-0">{needed.map((id) => handleFor(id))}</div>
          </>
        ) : null}

        <ActionGroup className="absolute bottom-2 right-2" onPointerDown={(event) => event.stopPropagation()}>
          <button
            type="button"
            aria-label="Zoom out"
            className="grid h-9 w-9 place-items-center rounded-md border border-line-200 bg-card text-ink-900 shadow-sm"
            onClick={() => zoomBy(1 / 1.5)}
          >
            <ZoomOut className="h-4 w-4" aria-hidden />
          </button>
          <button
            type="button"
            aria-label="Zoom in"
            className="grid h-9 w-9 place-items-center rounded-md border border-line-200 bg-card text-ink-900 shadow-sm"
            onClick={() => zoomBy(1.5)}
          >
            <ZoomIn className="h-4 w-4" aria-hidden />
          </button>
        </ActionGroup>
      </div>

      <p role="status" className="text-xs leading-5 text-ink-500">
        Zoom {zoom.toFixed(1)}×.{" "}
        {displayScale > 0
          ? `Mark precision ±${markSigmaForDisplayScale(displayScale).toFixed(1)} px. Zoom in to improve it.`
          : "Loading the photograph…"}
      </p>

      {mode === "SCALE" ? (
        <div className="grid gap-2">
          <span className="field-label">How long is the reference, really?</span>
          <div className="flex flex-wrap gap-1.5">
            {SCALE_PRESETS.map((preset) => (
              <button
                key={preset.label}
                type="button"
                className="rounded-full border border-line-200 bg-card px-3 py-1 text-xs font-medium text-ink-900 hover:border-purple-300 hover:bg-purple-50"
                disabled={disabled}
                onClick={() => {
                  setReferenceLength(String(preset.length));
                  setReferenceUnit(preset.unit);
                }}
              >
                {preset.label}
              </button>
            ))}
          </div>
          <div className="flex flex-wrap items-end gap-2">
            <input
              className="field-input w-32"
              type="number"
              step="any"
              min={0}
              inputMode="decimal"
              aria-label="Reference length"
              value={referenceLength}
              disabled={disabled}
              onChange={(event) => setReferenceLength(event.target.value)}
            />
            <div className="w-24">
              <Dropdown
                value={referenceUnit}
                onChange={(value) => setReferenceUnit(value as LengthUnit)}
                options={UNIT_OPTIONS}
                disabled={disabled}
                ariaLabel="Reference unit"
                advanceOnSelect={false}
              />
            </div>
          </div>
        </div>
      ) : (
        <div className="grid gap-2">
          <span className="field-label">How big is the rectangle, really?</span>
          <div className="flex flex-wrap gap-1.5">
            {RECT_PRESETS.map((preset) => (
              <button
                key={preset.label}
                type="button"
                className="rounded-full border border-line-200 bg-card px-3 py-1 text-xs font-medium text-ink-900 hover:border-purple-300 hover:bg-purple-50"
                disabled={disabled}
                onClick={() => {
                  setRectWidth(String(preset.width));
                  setRectHeight(String(preset.height));
                  setRectUnit(preset.unit);
                }}
              >
                {preset.label}
              </button>
            ))}
          </div>
          <div className="flex flex-wrap items-end gap-2">
            <input
              className="field-input w-28"
              type="number"
              step="any"
              min={0}
              inputMode="decimal"
              aria-label="Rectangle width, from corner 1 to corner 2"
              value={rectWidth}
              disabled={disabled}
              onChange={(event) => setRectWidth(event.target.value)}
            />
            <span aria-hidden className="pb-2.5 text-sm text-ink-500">
              ×
            </span>
            <input
              className="field-input w-28"
              type="number"
              step="any"
              min={0}
              inputMode="decimal"
              aria-label="Rectangle height, from corner 2 to corner 3"
              value={rectHeight}
              disabled={disabled}
              onChange={(event) => setRectHeight(event.target.value)}
            />
            <div className="w-24">
              <Dropdown
                value={rectUnit}
                onChange={(value) => setRectUnit(value as LengthUnit)}
                options={UNIT_OPTIONS}
                disabled={disabled}
                ariaLabel="Rectangle unit"
                advanceOnSelect={false}
              />
            </div>
          </div>
          <p className="text-xs leading-5 text-ink-500">
            Width is corner 1 → 2; height is corner 2 → 3.
          </p>
        </div>
      )}

      <MeasurementReadout
        result={result}
        allPlaced={allPlaced}
        needed={needed}
        marks={marks}
        mode={mode}
        targets={targets}
        row={row}
        disabled={disabled}
        onPropose={onPropose}
      />

      <ActionGroup align="end" className="border-t border-line-200 pt-2">
        <button
          type="button"
          className="inline-flex min-h-10 items-center gap-1 px-2 text-xs font-medium text-ink-500 underline"
          onClick={() => setOpen(false)}
        >
          <X className="h-3 w-3" aria-hidden />
          Close the measuring panel
        </button>
      </ActionGroup>
    </div>
  );
}

function MeasurementReadout({
  result,
  allPlaced,
  needed,
  marks,
  mode,
  targets,
  row,
  disabled,
  onPropose
}: {
  result: MeasureResult | null;
  allPlaced: boolean;
  needed: MarkId[];
  marks: Partial<Record<MarkId, Mark>>;
  mode: Mode;
  targets: MeasureTarget[];
  row: DwEntryData;
  disabled?: boolean;
  onPropose: (key: string, value: DwValue, method: MeasurementMarker) => void;
}) {
  if (!allPlaced) {
    const remaining = needed.filter((id) => !marks[id]?.placed);
    return (
      <p className="text-xs leading-5 text-ink-500">
        {remaining.length} of {needed.length} marks still to place ({remaining.map((id) => MARK_LABELS[id].badge).join(", ")}
        ).
      </p>
    );
  }

  if (!result) {
    return (
      <p className="text-xs leading-5 text-ink-500">
        {mode === "SCALE"
          ? "Enter the reference's real length."
          : "Enter the rectangle's real size."}
      </p>
    );
  }

  if (!result.ok) {
    return (
      <p
        role="status"
        className="flex items-start gap-2 rounded-md border border-amber-500 bg-amber-100 px-2.5 py-2 text-xs leading-5 text-amber-800"
      >
        <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
        <span>{result.reason}</span>
      </p>
    );
  }

  const shown = roundToUncertainty(result.value, result.uncertainty);
  const shownDoubt = roundToUncertainty(result.uncertainty, result.uncertainty);

  return (
    <div className="grid gap-2 rounded-md border border-purple-300 bg-card p-3">
      <p className="text-xs font-medium uppercase tracking-wide text-ink-500">Measured — not saved yet</p>
      <p className="font-display text-2xl font-bold text-ink-900">
        {shown.value.toFixed(shown.decimals)} ± {shownDoubt.value.toFixed(shownDoubt.decimals)} {result.unit}
      </p>
      <p className="text-xs leading-5 text-ink-500">
        ±{(result.relativeUncertainty * 100).toFixed(1)}% from mark placement.
      </p>

      {result.method === "RECTIFIED" && typeof result.tiltCorrection === "number" ? (
        <p className="text-xs leading-5 text-ink-500">
          Tilt correction: {(result.tiltCorrection * 100).toFixed(1)}%
          {typeof result.uncorrectedValue === "number"
            ? ` (two marks: ${result.uncorrectedValue.toFixed(1)} ${result.unit})`
            : ""}
          .
        </p>
      ) : null}

      <div className="grid gap-1.5">
        <span className="field-label">Propose this into</span>
        <div className="flex flex-wrap gap-2">
          {targets.map(({ field, unit }) => {
            const converted = convertLength(result.value, result.unit, unit);
            const convertedDoubt = convertLength(result.uncertainty, result.unit, unit);
            if (converted === null || convertedDoubt === null) {
              return (
                <span key={field.key} className="text-xs leading-5 text-ink-500">
                  {field.label} is measured in {unit}, which this cannot convert to.
                </span>
              );
            }
            const proposal = roundToUncertainty(converted, convertedDoubt);
            const text = proposal.value.toFixed(proposal.decimals);
            const current = inputValue(row[field.key]);
            return (
              <div key={field.key} className="grid gap-1">
                <button
                  type="button"
                  className="field-button"
                  disabled={disabled}
                  onClick={() => onPropose(field.key, text, methodMarker(result))}
                >
                  <Check className="h-4 w-4" aria-hidden />
                  {field.label}: {text} {unit}
                </button>
                {current ? (
                  <span className="text-xs leading-5 text-amber-800 dark:text-amber-100">
                    Currently “{current}”. This replaces it.
                  </span>
                ) : null}
              </div>
            );
          })}
        </div>
        <p className="text-xs leading-5 text-ink-500">
          Rounded to its precision.
        </p>
      </div>
    </div>
  );
}
