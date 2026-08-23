"use client";

/**
 * `Reveal1` — a before/after slider. Two images stacked, one clipped, and a handle deciding where the
 * clip falls.
 *
 * Supplied by the owner from an external snippet, to show a traced sketch against the photograph it
 * was traced from. Four things changed on the way in, and each is a defect rather than a matter of
 * taste:
 *
 *  1. `border-3` DOES NOTHING. It is not a Tailwind class and `borderWidth` is not extended in
 *     `tailwind.config.ts`, so the handle's ring was simply invisible. Tailwind ships `border-2` and
 *     `border-4`; this uses `border-2`.
 *
 *  2. THE SLIDER COULD NOT BE OPERATED FROM A KEYBOARD. It carried `role="slider"` and `tabIndex={0}`
 *     and no key handler at all, so it announced itself to a screen reader as a slider and then
 *     ignored every arrow key. A control that advertises a role it does not honour is worse than one
 *     that stays a plain div. Arrows, Home, End and the Page keys are wired below.
 *
 *  3. The raw `<img>` tags trip `@next/next/no-img-element`, and eslint runs here at
 *     `--max-warnings=0`. They stay raw, deliberately — see the note at the elements — with the
 *     targeted disable `components/designers/StoredMediaImage.tsx` already establishes.
 *
 *  4. The snippet's demo pointed at Unsplash. `next.config.ts`'s `remotePatterns` do not include that
 *     host, so those images are rejected in production. The demo export is not carried over.
 *
 * ── ON `initialPosition`, BECAUSE THE SNIPPET'S DEFAULT IS BACKWARDS FOR OUR USE ────────────────
 *
 * `position` clips the BEFORE layer: at 0 the before-image is fully clipped and the after-image is
 * what you see. So to open showing the TRACED result with the divider hard against the leading edge —
 * what was asked for — pass the trace as `afterImage` and `initialPosition={0}`. Dragging then
 * reveals the original underneath. The snippet's 50 would have opened half-and-half.
 */

import { GripHorizontal, GripVertical } from "lucide-react";
import { useCallback, useEffect, useId, useRef, useState } from "react";

import { cn } from "@/lib/utils";

export interface Reveal1Image {
  src: string;
  alt: string;
}

export interface Reveal1Props {
  heading?: string;
  description?: string;
  beforeImage: Reveal1Image;
  afterImage: Reveal1Image;
  beforeLabel?: string;
  afterLabel?: string;
  orientation?: "horizontal" | "vertical";
  /** 0 shows `afterImage` in full with the divider at the leading edge. See the file note. */
  initialPosition?: number;
  showLabels?: boolean;
  dividerWidth?: number;
  className?: string;
  /** Accessible name. Without one, and without a heading, it is announced as just "slider". */
  ariaLabel?: string;
}

/** How far one arrow press moves the divider, in percent. The Page keys move ten times as far. */
const STEP = 2;

const clamp = (value: number) => Math.max(0, Math.min(100, value));

export function Reveal1({
  heading,
  description,
  beforeImage,
  afterImage,
  beforeLabel = "Before",
  afterLabel = "After",
  orientation = "horizontal",
  initialPosition = 50,
  showLabels = true,
  dividerWidth = 4,
  className,
  ariaLabel
}: Reveal1Props) {
  const [position, setPosition] = useState(clamp(initialPosition));
  const [dragging, setDragging] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const headingId = useId();

  const isHorizontal = orientation === "horizontal";

  const moveTo = useCallback(
    (clientX: number, clientY: number) => {
      const node = containerRef.current;
      if (!node) return;
      const rect = node.getBoundingClientRect();
      // Guard the divide. A container that has not been laid out yet has zero width, and 0/0 is NaN,
      // which would set the position to NaN and blank both layers with nothing on screen to explain it.
      if (isHorizontal) {
        if (rect.width === 0) return;
        setPosition(clamp(((clientX - rect.left) / rect.width) * 100));
      } else {
        if (rect.height === 0) return;
        setPosition(clamp(((clientY - rect.top) / rect.height) * 100));
      }
    },
    [isHorizontal]
  );

  const onPointerDown = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      // ONE pointer handler, not a mouse one and a touch one. The snippet registered both, so a pen
      // or a touch that the browser also reports as a mouse ran the move twice for one gesture.
      event.preventDefault();
      setDragging(true);
      moveTo(event.clientX, event.clientY);
    },
    [moveTo]
  );

  useEffect(() => {
    if (!dragging) return;
    const onMove = (event: PointerEvent) => moveTo(event.clientX, event.clientY);
    const onUp = () => setDragging(false);
    // `pointercancel` earns its place: a drag interrupted by the OS — an incoming call, a system
    // gesture taking over — fires that and nothing else, and without it the handle stays welded to
    // the pointer for the rest of the page's life.
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
    window.addEventListener("pointercancel", onUp);
    return () => {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
      window.removeEventListener("pointercancel", onUp);
    };
  }, [dragging, moveTo]);

  const onKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLDivElement>) => {
      // Both axes answer to both arrow pairs. A vertical slider driven only by up and down would be
      // technically correct and unhelpful: people reach for left and right on anything slider-shaped.
      const key = event.key;
      let next: number | null = null;
      if (key === "ArrowLeft" || key === "ArrowUp") next = position - STEP;
      else if (key === "ArrowRight" || key === "ArrowDown") next = position + STEP;
      else if (key === "PageUp") next = position - STEP * 10;
      else if (key === "PageDown") next = position + STEP * 10;
      else if (key === "Home") next = 0;
      else if (key === "End") next = 100;
      if (next === null) return;
      event.preventDefault();
      setPosition(clamp(next));
    },
    [position]
  );

  const clipPath = isHorizontal
    ? `inset(0 ${100 - position}% 0 0)`
    : `inset(0 0 ${100 - position}% 0)`;

  const shown = Math.round(position);

  return (
    <section className={cn("w-full", className)}>
      <div className="grid gap-4">
        {(heading || description) && (
          <div className="grid gap-1">
            {heading ? (
              <h2 id={headingId} className="text-lg font-semibold">
                {heading}
              </h2>
            ) : null}
            {description ? <p className="max-w-2xl text-sm leading-6 text-ink-muted">{description}</p> : null}
          </div>
        )}

        <div
          ref={containerRef}
          role="slider"
          aria-label={ariaLabel ?? (heading ? undefined : `${beforeLabel} and ${afterLabel} comparison`)}
          aria-labelledby={!ariaLabel && heading ? headingId : undefined}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={shown}
          aria-valuetext={`${shown}% ${beforeLabel}, ${100 - shown}% ${afterLabel}`}
          aria-orientation={isHorizontal ? "horizontal" : "vertical"}
          tabIndex={0}
          onPointerDown={onPointerDown}
          onKeyDown={onKeyDown}
          className={cn(
            "panel relative aspect-video w-full select-none overflow-hidden rounded-lg",
            "focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2",
            isHorizontal ? "cursor-ew-resize" : "cursor-ns-resize"
          )}
        >
          {/*
            RAW `<img>` AND NOT `next/image`, on purpose. Both sources here are object or blob URLs
            produced on the device — a freshly traced canvas and the photograph it came from — so
            there is no remote host for `remotePatterns` to allow and nothing for the optimiser to
            fetch. `StoredMediaImage.tsx` reaches the same conclusion for the same reason.
          */}
          <div className="absolute inset-0">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img className="absolute inset-0 size-full object-cover" src={afterImage.src} alt={afterImage.alt} />
          </div>

          <div className="absolute inset-0" style={{ clipPath }}>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img className="absolute inset-0 size-full object-cover" src={beforeImage.src} alt={beforeImage.alt} />
          </div>

          <div
            aria-hidden
            className={cn(
              "absolute z-10 bg-white shadow-lg",
              isHorizontal ? "bottom-0 top-0 -translate-x-1/2" : "left-0 right-0 -translate-y-1/2"
            )}
            style={{
              [isHorizontal ? "left" : "top"]: `${position}%`,
              [isHorizontal ? "width" : "height"]: `${dividerWidth}px`
            }}
          >
            <div
              className={cn(
                "absolute left-1/2 top-1/2 flex h-10 w-10 -translate-x-1/2 -translate-y-1/2",
                "items-center justify-center rounded-full border-2 border-white bg-primary",
                "shadow-xl transition-transform",
                dragging && "scale-110"
              )}
            >
              {isHorizontal ? (
                <GripVertical className="h-5 w-5 text-white" />
              ) : (
                <GripHorizontal className="h-5 w-5 text-white" />
              )}
            </div>
          </div>

          {showLabels ? (
            <>
              <span className="absolute left-3 top-3 z-20 rounded-full bg-black/70 px-3 py-1 text-xs font-medium text-white backdrop-blur-sm">
                {beforeLabel}
              </span>
              <span
                className={cn(
                  "absolute z-20 rounded-full bg-black/70 px-3 py-1 text-xs font-medium text-white backdrop-blur-sm",
                  isHorizontal ? "right-3 top-3" : "bottom-3 left-3"
                )}
              >
                {afterLabel}
              </span>
            </>
          ) : null}
        </div>
      </div>
    </section>
  );
}

export default Reveal1;
