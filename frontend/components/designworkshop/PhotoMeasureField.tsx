"use client";

/**
 * "Measure a dimension from this photograph" — the surface over `lib/photoMeasure.ts`.
 *
 * WHAT THIS IS FOR. Stage 13's Advanced tier asks for calibrated measurements, and every dimension in
 * the registry is typed off a tape measure today. A wrong dimension is multiplied into the cost sheet
 * and printed on the product card, and by the time anybody could check it the prototype is three
 * districts away. This panel lets a designer take the measurement off a photograph they already have,
 * with a ruler or a scale card in the frame — on the handset, in the courtyard, with no connection,
 * because the whole computation is thirty lines of projective geometry running on this device.
 *
 * IT NEVER WRITES A DIMENSION. Every path here ends at a button the designer presses, exactly as
 * `IdentityCardReader` does and for a related reason: the number is a proposal from an inference the
 * person can check against the object in their hands, and the moment it is written into `lengthCm` it
 * loses its error bar forever — the registry has a column for the dimension and none for the doubt.
 * So the doubt is spent HERE, on screen, while somebody can still act on it.
 *
 * THE ASSUMPTION IS ON THE SCREEN AND NOT ONLY IN A COMMENT. A ratio of pixel distances is the true
 * length only when the reference and the object lie in one flat plane square to the sensor. A scale
 * card lying on a table and a pot standing on that table are not in one plane, and the pot measured
 * that way is wrong by however much the perspective happens to be — silently, plausibly, and with
 * nothing downstream able to notice. That sentence is rendered, in an amber card, next to the answer,
 * every time. The four-corner method below it is the way out, and it says what it cost.
 *
 * WHY PINCH-ZOOM IS PART OF THE MEASUREMENT AND NOT A CONVENIENCE. Marks are stored in NATURAL IMAGE
 * PIXELS, so zoom cannot move an answer. What zoom moves is how precisely a fingertip can be aimed:
 * a 4000 px photograph shown 400 px wide is displayed at 0.1, so one screen pixel IS ten image pixels
 * and the most careful mark is worth ±15 image px. Each mark therefore remembers the zoom it was
 * placed at, the measurement takes the worst of the marks it used, and the error bar visibly narrows
 * as the designer zooms in — which is true, and is the only honest way to reward the care.
 *
 * NO NETWORK, NO CANVAS READBACK, NO RE-ENCODING. Nothing here reads a pixel. It reads the geometry
 * of where a person pointed, which is why it works on a cross-origin presigned URL with no CORS rule
 * (unlike the card reader, which needs the bytes) and why docs/MEDIA_PIPELINE.md's refusal to touch
 * an original is not even in tension with it.
 */

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
  roundToUncertainty,
  type LengthUnit,
  type MeasureResult,
  type Point
} from "@/lib/photoMeasure";

/**
 * A photograph this panel can measure from.
 *
 * `url` is all it needs — an object URL for bytes still on this device, or the media row's own URL for
 * one already uploaded. Both are equally measurable, which is what keeps the feature working on a
 * photograph taken thirty seconds ago with no connection.
 */
export type MeasurablePhoto = { key: string; name: string; url: string };

/** A registry field a measurement may be proposed into, with the unit the registry declared for it. */
export type MeasureTarget = { field: DwField; unit: LengthUnit };

type MarkId = "refA" | "refB" | "c0" | "c1" | "c2" | "c3" | "tgtA" | "tgtB";

type Mark = {
  point: Point;
  /** The per-mark uncertainty in image pixels, from the zoom this mark was last positioned at. */
  sigma: number;
  /** False while the mark is still sitting where it was seeded — see {@link SEEDS}. */
  placed: boolean;
};

type Mode = "SCALE" | "RECTIFY";

const SCALE_MARKS: MarkId[] = ["refA", "refB", "tgtA", "tgtB"];
const RECTIFY_MARKS: MarkId[] = ["c0", "c1", "c2", "c3", "tgtA", "tgtB"];

/**
 * The badge on each handle and the sentence a screen reader gets.
 *
 * EVERY HANDLE CARRIES ITS OWN NAME, so which mark is which never depends on where it happens to be
 * or on the colour it is drawn in. Reference and corner handles are filled and object handles are
 * outlined, but that distinction is decoration on top of the label rather than the thing carrying it.
 */
const MARK_LABELS: Record<MarkId, { badge: string; name: string }> = {
  refA: { badge: "R1", name: "Reference, first end" },
  refB: { badge: "R2", name: "Reference, second end" },
  c0: { badge: "1", name: "Rectangle corner 1" },
  c1: { badge: "2", name: "Rectangle corner 2, along the width edge from corner 1" },
  c2: { badge: "3", name: "Rectangle corner 3, diagonally opposite corner 1" },
  c3: { badge: "4", name: "Rectangle corner 4" },
  tgtA: { badge: "A", name: "The dimension, first end" },
  tgtB: { badge: "B", name: "The dimension, second end" }
};

/**
 * Where each mark starts, as a fraction of the photograph.
 *
 * SEEDED RATHER THAN EMPTY, because an empty viewport with an instruction to tap four times is a
 * state a designer can get wrong (a stray tap makes a mark nobody wanted) and cannot see the shape of.
 * Seeded marks show what is being asked for immediately. They are also flagged unplaced, and NO
 * MEASUREMENT IS SHOWN until every mark has been moved or tapped into position — a reading taken off
 * the default layout would be a confident number about nothing at all.
 */
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

/**
 * Things a designer in this programme actually has to hand, with the sizes they actually are.
 *
 * A preset removes the one step most likely to be got wrong — typing the reference length — and the
 * A4 sheet is here twice because it is both the commonest scale bar and the commonest known
 * rectangle. Nothing is preselected: a reference the designer did not choose is a reference nobody
 * checked was in the photograph.
 */
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

/** A drag shorter than this is a tap — it places the active mark rather than panning the photograph. */
const TAP_SLOP_PX = 4;

/**
 * Claim a pointer, and survive a browser that will not give it up.
 *
 * `setPointerCapture` THROWS — `NotFoundError` — when the pointer id is no longer active, which
 * happens for real when a second finger lands in the same frame as the first one lifts, and which
 * some automation drivers produce routinely. Unguarded, that exception escapes a React event handler
 * and unmounts the tree: a designer mid-pinch would lose the whole stage form. Capture is an
 * optimisation here anyway — it keeps a drag alive when the finger leaves the element — so failing to
 * get it costs a slightly worse drag, not a measurement.
 */
function capturePointer(element: Element, pointerId: number): void {
  try {
    (element as HTMLElement).setPointerCapture(pointerId);
  } catch {
    /* see above — a drag without capture still works while the pointer stays over the element */
  }
}

export function PhotoMeasureField({
  photos,
  targets,
  row,
  onPropose,
  disabled
}: {
  photos: MeasurablePhoto[];
  targets: MeasureTarget[];
  row: DwEntryData;
  /** Write one registry field. Called ONLY from a button the designer pressed. */
  onPropose: (key: string, value: DwValue) => void;
  disabled?: boolean;
}) {
  const panelId = useId();
  const [open, setOpen] = useState(false);
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

  // A photograph removed from the field while this panel is open must not leave it measuring a URL
  // that has been revoked — the marks belong to an image nobody can see any more.
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

  /** Screen pixels per image pixel at zoom 1 — the "fit the whole photograph in the box" scale. */
  const fit = useMemo(() => {
    if (!natural || !box.width || !box.height) return 0;
    return Math.min(box.width / natural.width, box.height / natural.height);
  }, [natural, box]);

  /** Screen pixels per image pixel right now. This is what decides how precise a mark can be. */
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

  // Re-centre whenever the photograph, the box or the fit changes. Without this the marks and the
  // image disagree the first time the panel is opened on a phone in landscape.
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

  /** Seed any mark this mode needs that does not exist yet, once the photograph's size is known. */
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
    // `displayScale` is deliberately absent: it changes on every pinch frame, and listing it would
    // re-seed marks mid-gesture. A seeded mark's sigma is replaced the instant it is actually placed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [natural, mode]);

  const setMark = useCallback(
    (id: MarkId, point: Point) => {
      if (!natural) return;
      // Clamped to the photograph: a mark dragged off the edge is a coordinate outside the image, and
      // a homography solved through one measures a plane that was never photographed.
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

  /* ── Gestures on the photograph ───────────────────────────────────────── */

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
    // A tap on the photograph places the mark the designer is currently working on and moves the
    // cursor to the next — the four-corner case is six marks, and reaching for a chip between each
    // would make it eleven actions instead of six.
    if (wasSingle && !gestureMoved.current && !dragging.current && natural) {
      setMark(active, viewToImage(localPoint(event)));
      const index = needed.indexOf(active);
      if (index >= 0 && index < needed.length - 1) setActive(needed[index + 1]);
    }
    gestureMoved.current = false;
  }

  /* ── One handle ───────────────────────────────────────────────────────── */

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
        aria-label={`${MARK_LABELS[id].name}${mark.placed ? "" : " — not placed yet"}. Drag it, or use the arrow keys to nudge it.`}
        aria-pressed={isActive}
        className={[
          "pointer-events-auto absolute grid h-8 w-8 -translate-x-1/2 -translate-y-1/2 place-items-center",
          "rounded-full text-[0.625rem] font-bold leading-none shadow-md transition-shadow",
          isTarget
            ? "border-2 border-purple-700 bg-card text-purple-800"
            : "border-2 border-card bg-purple-700 text-white",
          isActive ? "ring-4 ring-purple-600/30" : "",
          mark.placed ? "" : "opacity-70"
        ].join(" ")}
        style={{ left: `${view.x}px`, top: `${view.y}px` }}
        onPointerDown={(event) => {
          if (disabled) return;
          // The viewport would otherwise read this as the start of a pan, and the photograph would
          // slide out from under the mark being dragged.
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
          // THE KEYBOARD ROUTE, and it is not decoration: dragging is pointer-only, so without this a
          // designer working from a laptop trackpad or an assistive device could see the marks and
          // never move one. A step is one IMAGE pixel — finer than a pointer can manage — and Shift
          // is ten, so crossing a 4000 px frame is possible without holding an arrow down for a
          // minute.
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

  /* ── The measurement ──────────────────────────────────────────────────── */

  const allPlaced = needed.every((id) => marks[id]?.placed);

  /** The worst of the marks this measurement rests on — an error bar is only as good as its weakest. */
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
          If a ruler, a scale card or a sheet of paper is in one of these photographs, a dimension can be measured off
          it here and proposed into {targets.map((entry) => entry.field.label).join(", ")}. It runs on this device and
          needs no connection.
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
                    // A mark is a position on ONE photograph. Carrying it to another would put the
                    // reference somewhere nobody chose, on an image of a different size.
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

      {/* Method. Two real buttons rather than a dropdown, because the choice IS the explanation and a
          designer has to read both to make it. */}
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

      {/* THE ASSUMPTION, ON SCREEN. See the file header for why this is not a comment. */}
      {mode === "SCALE" ? (
        <p className="flex items-start gap-2 rounded-md border border-amber-500 bg-amber-100 px-2.5 py-2 text-xs leading-5 text-amber-800">
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
          <span>
            This is only true when the reference and the thing you are measuring lie in the{" "}
            <strong>same flat plane, square to the camera</strong>. A scale card lying on a table and a pot standing on
            that table are not in one plane, and a dimension measured across a tilted object comes out wrong — by
            however much the angle happens to be, with nothing later able to tell. If the photograph is at an angle,
            use the four-corner method instead.
          </span>
        </p>
      ) : (
        <p className="flex items-start gap-2 rounded-md border border-line-200 bg-card px-2.5 py-2 text-xs leading-5 text-ink-500">
          <Crosshair className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
          <span>
            Mark the four corners of the rectangle <strong>in order around it</strong> — 1, 2, 3, 4 walking round the
            edge, not in reading order. The tilt of that surface is then corrected for exactly. The object still has to
            be lying <strong>on</strong> that surface: what is corrected is the angle of the plane, not the height of
            something standing up off it.
          </span>
        </p>
      )}

      {/* Which mark the next tap moves. */}
      <div className="grid gap-1">
        <span className="field-label">Marks — tap the photograph to place, drag or use arrow keys to adjust</span>
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
        // Pointer events only arrive for pinch and pan if the browser is not already using the
        // gesture to scroll the page; on a handset that is exactly what it would do otherwise.
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
              alt={`${photo.name} — place marks on this photograph to measure a dimension`}
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

            {/* The lines between the marks, so a designer can see what is being measured rather than
                inferring it from six discs. Purely decorative — every mark is named on its handle. */}
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

        {/*
          POINTER EVENTS STOP HERE, and without it these two buttons do nothing at all.

          The viewport calls `setPointerCapture` on pointerdown so a pan or a pinch survives the
          finger leaving the box. Capture also RETARGETS the click that follows — Chromium fires it at
          the capturing element rather than at the button under the finger — so pressing Zoom in
          silently placed a mark on the photograph instead of zooming. It looked like a dead control
          and it was caught by e2e/photo-measure-ui.spec.ts asserting that the stated per-mark
          precision moves when the zoom does. Stopping the event before the viewport sees it is the
          same guard each mark handle already uses.
        */}
        <div className="absolute bottom-2 right-2 flex gap-1" onPointerDown={(event) => event.stopPropagation()}>
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
        </div>
      </div>

      {/* The zoom readout is part of the measurement, not chrome — see the file header. `role=status`
          rather than a bare paragraph so the narrowing error bar is available to a reader who cannot
          see the marks move. */}
      <p role="status" className="text-xs leading-5 text-ink-500">
        Zoom {zoom.toFixed(1)}×.{" "}
        {displayScale > 0
          ? `At this zoom a mark can be placed to about ±${markSigmaForDisplayScale(displayScale).toFixed(1)} image pixels, which is what the error bar below is built from. Zooming in genuinely narrows it.`
          : "Loading the photograph…"}
      </p>

      {/* The known size. */}
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
                // This dropdown changes the reading on the panel it sits in rather than filling in a
                // form field, so focus must stay where the designer is adjusting.
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
            The width is the edge from corner 1 to corner 2; the height is from corner 2 to corner 3. Getting the two
            the wrong way round measures a real rectangle that is not the one in the photograph.
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
    </div>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * The answer, its error bar, and the only place anything is written
 * ──────────────────────────────────────────────────────────────────────────── */

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
  onPropose: (key: string, value: DwValue) => void;
}) {
  if (!allPlaced) {
    const remaining = needed.filter((id) => !marks[id]?.placed);
    return (
      <p className="text-xs leading-5 text-ink-500">
        {remaining.length} of {needed.length} marks still to place ({remaining.map((id) => MARK_LABELS[id].badge).join(", ")}
        ). Nothing is measured until every mark is where it belongs — a reading off the marks as they were laid out
        would be a confident number about nothing.
      </p>
    );
  }

  if (!result) {
    return (
      <p className="text-xs leading-5 text-ink-500">
        {mode === "SCALE"
          ? "Type how long the reference really is, and the measurement appears here."
          : "Type how big the rectangle really is, and the measurement appears here."}
      </p>
    );
  }

  if (!result.ok) {
    // A refusal, with its reason. It is not styled as an error because nothing has gone wrong with
    // the designer's work — the marks simply do not support a number, and the sentence says which.
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
        That is ±{(result.relativeUncertainty * 100).toFixed(1)}%, from a reference {Math.round(result.referencePixels)}{" "}
        pixels long and an object {Math.round(result.targetPixels)} pixels long in this photograph. The error bar is how
        far the answer moves when each mark is nudged by the amount a mark can be placed to at this zoom — it is not a
        guess about the camera, and it does not include the reference being the wrong length.
      </p>

      {result.method === "RECTIFIED" && typeof result.tiltCorrection === "number" ? (
        <p className="text-xs leading-5 text-ink-500">
          Correcting for the tilt of that surface changed this by {(result.tiltCorrection * 100).toFixed(1)}%
          {typeof result.uncorrectedValue === "number"
            ? ` — two marks alone would have read ${result.uncorrectedValue.toFixed(1)} ${result.unit}`
            : ""}
          .{" "}
          {result.tiltCorrection < 0.01
            ? "That is small enough that the two-mark method would have done here."
            : "That is why the four corners were worth marking."}
        </p>
      ) : null}

      <div className="grid gap-1.5">
        <span className="field-label">Propose this into</span>
        <div className="flex flex-wrap gap-2">
          {targets.map(({ field, unit }) => {
            const converted = convertLength(result.value, result.unit, unit);
            const convertedDoubt = convertLength(result.uncertainty, result.unit, unit);
            // A unit this module cannot convert must not become a destination. It is a refusal rather
            // than a silent omission so nobody wonders where the button went.
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
                  onClick={() => onPropose(field.key, text)}
                >
                  <Check className="h-4 w-4" aria-hidden />
                  {field.label}: {text} {unit}
                </button>
                {current ? (
                  <span className="text-xs leading-5 text-amber-800">
                    Currently “{current}”. This replaces it.
                  </span>
                ) : null}
              </div>
            );
          })}
        </div>
        <p className="text-xs leading-5 text-ink-500">
          The figure is rounded to the precision its own error bar reaches, because once it is in the field the error
          bar is gone — the number of digits is the only thing left saying how well it was measured.
        </p>
      </div>
    </div>
  );
}
