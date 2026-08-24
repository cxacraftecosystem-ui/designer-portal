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
 *
 * ── ON `aspectRatio`, ADDED WHEN THIS WAS FIRST MOUNTED ─────────────────────────────────────────
 *
 * The frame was `aspect-video` and both layers are `object-cover`, which is right for the marketing
 * strip the snippet came from and wrong for the first real caller: a photographed A4 sketch is
 * portrait, and 16:9 centre-crops most of the sheet off the screen. The crop is identical on both
 * layers, so the comparison stays aligned — it just stops showing the drawing.
 *
 * `aspectRatio` is a NUMBER applied as an inline style rather than a class, and that is not laziness.
 * `cn()` in `lib/utils.ts` is `filter(Boolean).join(" ")` and NOT `tailwind-merge`
 * (`.claude/skills/field-repo-frontend/SKILL.md` §11.1: "later classes do not win, CSS source order
 * decides"), so appending `aspect-[3/4]` to a container that already has `aspect-video` is a coin
 * toss decided by the order Tailwind happened to emit two utilities that set the same property. An
 * inline style has no such argument with anything. When it is given, `aspect-video` is not emitted at
 * all — and with the frame matching the source, `object-cover` crops nothing.
 *
 * ── ON THE TWO BADGES, WHICH USED TO NAME THE WRONG LAYER ────────────────────────────────────────
 *
 * They were pinned to the two top corners and rendered whatever `position` was, so at `0` — the
 * position the first caller opens at, where the before layer is clipped to nothing and the whole frame
 * is the AFTER image — the top-left badge sat over the after layer calling it by the before layer's
 * name. The prop wiring was right and the labelling read backwards, which is the same "plausible and
 * wrong" failure the `initialPosition` note above is about, one level down.
 *
 * So each badge is now inside a layer clipped exactly as its own image is: the before badge shares the
 * before layer's `clipPath`, the after badge gets the complement. A badge is therefore visible only
 * over the picture it names, and it is revealed and hidden by the same drag that reveals and hides
 * that picture. Nothing decides this from a threshold — the two clips are the one number `position`.
 *
 * ── ON THE GRIP, WHICH USED TO BE HALF-CLIPPED AT THE EDGES ──────────────────────────────────────
 *
 * The frame is `overflow-hidden` (the images are `object-cover` inside a rounded box) and the grip is a
 * 40px circle centred on the divider, so at `position: 0` — again, the first caller's opening state —
 * exactly half of it was outside the frame and clipped away: the only pointer grab target was a 20px
 * half-disc at the extreme edge. The DIVIDER still sits exactly on the clip boundary, because moving it
 * would make the seam disagree with the picture; the GRIP is positioned separately and kept a grip's
 * radius inside the frame with a CSS `clamp()`. Within 20px of either end the grip and the seam are
 * visibly apart by up to that much, which is the honest trade: a handle you can grab everywhere, and a
 * seam that is never drawn in the wrong place.
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
  /**
   * Width ÷ height for the frame, e.g. `3 / 4` for a portrait sheet. Defaults to 16:9.
   *
   * Pass the SOURCE's own ratio and neither layer is cropped. See the file note for why this is a
   * number and not a class.
   */
  aspectRatio?: number;
  showLabels?: boolean;
  dividerWidth?: number;
  className?: string;
  /** Accessible name. Without one, and without a heading, it is announced as just "slider". */
  ariaLabel?: string;
}

/** How far one arrow press moves the divider, in percent. The Page keys move ten times as far. */
const STEP = 2;

/**
 * Half the grip's own size, in CSS pixels — `h-10 w-10` is 40px, so 20.
 *
 * The grip is never centred closer than this to an edge, so the whole circle stays inside an
 * `overflow-hidden` frame. Kept beside the class it is derived from: changing `h-10 w-10` without
 * changing this puts a sliver of the handle back outside the frame.
 */
const GRIP_HALF_PX = 20;

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
  ariaLabel,
  aspectRatio
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

  /** The complement of {@link clipPath}: everything the before layer is NOT covering. */
  const afterClipPath = isHorizontal ? `inset(0 0 0 ${position}%)` : `inset(${position}% 0 0 0)`;

  /**
   * Where the grip's centre goes — the divider's position, held a grip's radius inside the frame.
   *
   * `clamp()` with a percentage and two pixel bounds is doing arithmetic this component cannot do in
   * JS: the frame's width in pixels is not known here, and measuring it would mean an observer and a
   * re-render per resize for a purely visual inset.
   */
  const gripOffset = `clamp(${GRIP_HALF_PX}px, ${position}%, calc(100% - ${GRIP_HALF_PX}px))`;

  const shown = Math.round(position);

  // A non-finite or non-positive ratio would produce `aspect-ratio: NaN`, which collapses the frame to
  // nothing and takes both layers with it. Falling back to the class default keeps a bad number from
  // costing the whole comparison.
  const framed = typeof aspectRatio === "number" && Number.isFinite(aspectRatio) && aspectRatio > 0;

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
          style={framed ? { aspectRatio } : undefined}
          className={cn(
            "panel relative w-full select-none overflow-hidden rounded-lg",
            framed ? null : "aspect-video",
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

          {/* The seam, exactly on the clip boundary — see the file note on the grip. */}
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
          />

          {/* The grip, a sibling of the seam rather than its child, so it can be held inside the
              frame without dragging the seam off the boundary with it. */}
          <div
            aria-hidden
            className={cn(
              "absolute z-10 flex h-10 w-10 -translate-x-1/2 -translate-y-1/2",
              "items-center justify-center rounded-full border-2 border-white bg-primary",
              "shadow-xl transition-transform",
              dragging && "scale-110"
            )}
            style={isHorizontal ? { left: gripOffset, top: "50%" } : { top: gripOffset, left: "50%" }}
          >
            {isHorizontal ? (
              <GripVertical className="h-5 w-5 text-white" />
            ) : (
              <GripHorizontal className="h-5 w-5 text-white" />
            )}
          </div>

          {/*
            EACH BADGE IS CLIPPED WITH THE LAYER IT NAMES. See the file note: pinned to the corners
            unconditionally, the before badge named the after layer for as long as the divider was near
            the leading edge — which is where the first caller deliberately opens.

            `pointer-events-none` on the wrappers, because a badge that swallowed `pointerdown` would
            make the one corner of the frame it covers refuse to move the divider.
          */}
          {showLabels ? (
            <>
              <div className="pointer-events-none absolute inset-0 z-20" style={{ clipPath }}>
                <span className="absolute left-3 top-3 rounded-full bg-black/70 px-3 py-1 text-xs font-medium text-white backdrop-blur-sm">
                  {beforeLabel}
                </span>
              </div>
              <div className="pointer-events-none absolute inset-0 z-20" style={{ clipPath: afterClipPath }}>
                <span
                  className={cn(
                    "absolute rounded-full bg-black/70 px-3 py-1 text-xs font-medium text-white backdrop-blur-sm",
                    isHorizontal ? "right-3 top-3" : "bottom-3 left-3"
                  )}
                >
                  {afterLabel}
                </span>
              </div>
            </>
          ) : null}
        </div>
      </div>
    </section>
  );
}

export default Reveal1;
