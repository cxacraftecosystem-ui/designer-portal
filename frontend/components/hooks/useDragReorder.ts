"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";

export function moveIndex<T>(items: readonly T[], from: number, to: number): T[] {
  const next = [...items];
  if (from < 0 || from >= next.length || to < 0 || to >= next.length) return next;
  const [item] = next.splice(from, 1);
  next.splice(to, 0, item);
  return next;
}

export type DragBox = { top: number; height: number };

export function dragTargetIndex(boxes: readonly DragBox[], from: number, travel: number): number {
  const box = boxes[from];
  if (!box) return from;
  const centre = box.top + box.height / 2 + travel;
  let to = from;
  boxes.forEach((other, index) => {
    if (index === from) return;
    const otherCentre = other.top + other.height / 2;
    if (index < from && centre < otherCentre) to = Math.min(to, index);
    if (index > from && centre > otherCentre) to = Math.max(to, index);
  });
  return to;
}

const EDGE_ZONE = 72;

const MAX_SPEED = 900;

const REDUCED_STEP = 96;
const REDUCED_DWELL_MS = 250;

const ARM_TRAVEL = 8;

export function edgeScrollDelta({
  pointer,
  top,
  bottom,
  scroll,
  max,
  elapsedMs,
  reduce
}: {
  pointer: number;
  top: number;
  bottom: number;
  scroll: number;
  max: number;
  elapsedMs: number;
  reduce: boolean;
}): number {
  const zone = Math.min(EDGE_ZONE, (bottom - top) / 2);
  if (!(zone > 0)) return 0;
  const above = (top + zone - pointer) / zone;
  const below = (pointer - (bottom - zone)) / zone;
  const depth = Math.min(1, Math.max(above, below));
  if (depth <= 0) return 0;
  const direction = above >= below ? -1 : 1;
  const raw = reduce
    ? elapsedMs >= REDUCED_DWELL_MS
      ? direction * REDUCED_STEP
      : 0
    : direction * depth * MAX_SPEED * (elapsedMs / 1000);
  if (raw > 0) return Math.max(0, Math.min(max - scroll, raw));
  if (raw < 0) {
    const bounded = Math.min(0, Math.max(-scroll, raw));
    return bounded === 0 ? 0 : bounded;
  }
  return 0;
}

function scrollerFor(node: HTMLElement | null): HTMLElement {
  for (let element = node?.parentElement ?? null; element; element = element.parentElement) {
    const overflowY = getComputedStyle(element).overflowY;
    const scrolls = overflowY === "auto" || overflowY === "scroll" || overflowY === "overlay";
    if (scrolls && element.scrollHeight > element.clientHeight) return element;
  }
  return (document.scrollingElement ?? document.documentElement) as HTMLElement;
}

type Scroller = {
  node: HTMLElement;
  top: number;
  bottom: number;
  start: number;
  max: number;
  startY: number;
  pointer: number;
  armed: boolean;
};

type DragState = {
  key: string;
  pointerId: number;
  from: number;
  to: number;
  startY: number;
  offset: number;
  scrolled: number;
  boxes: DragBox[];
  snapshot: readonly string[];
};

export type DragReorder = {
  registerRow: (key: string) => (node: HTMLElement | null) => void;
  handleProps: (key: string) => {
    onPointerDown: (event: React.PointerEvent<HTMLElement>) => void;
    onPointerMove: (event: React.PointerEvent<HTMLElement>) => void;
    onPointerUp: (event: React.PointerEvent<HTMLElement>) => void;
    onPointerCancel: () => void;
  };
  shiftFor: (key: string) => number;
  draggingKey: string | null;
  announcement: string;
  announceMove: (key: string, index: number) => void;
};

export function useDragReorder({
  order,
  labelFor,
  onReorder,
  locked = false,
  describeMove
}: {
  order: readonly string[];
  labelFor: (key: string) => string;
  onReorder: (from: number, to: number) => void | boolean;
  locked?: boolean;
  describeMove?: (key: string, index: number) => string;
}): DragReorder {
  const rowRefs = useRef(new Map<string, HTMLElement>());
  const scrollerRef = useRef<Scroller | null>(null);
  const [drag, setDrag] = useState<DragState | null>(null);
  const [announcement, setAnnouncement] = useState("");
  const total = order.length;

  const reduceMotion = useAppReducedMotion();
  const reduceRef = useRef(reduceMotion);
  useEffect(() => {
    reduceRef.current = reduceMotion;
  });

  const announceMove = useCallback(
    (key: string, index: number) => {
      setAnnouncement(
        describeMove
          ? describeMove(key, index)
          : `${labelFor(key)} moved to position ${index + 1} of ${total}.`
      );
    },
    [describeMove, labelFor, total]
  );

  useEffect(() => {
    if (!drag) return;
    function cancelOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape") setDrag(null);
    }
    window.addEventListener("keydown", cancelOnEscape);
    return () => window.removeEventListener("keydown", cancelOnEscape);
  }, [drag]);

  const dragging = drag !== null;
  useEffect(() => {
    if (!dragging) return;
    let frame = 0;
    let previous = performance.now();
    let sinceStep = 0;
    const tick = (now: number) => {
      frame = requestAnimationFrame(tick);
      const scroller = scrollerRef.current;
      if (!scroller || !scroller.armed) return;
      const elapsed = now - previous;
      previous = now;
      sinceStep += elapsed;
      const reduce = reduceRef.current;
      const delta = edgeScrollDelta({
        pointer: scroller.pointer,
        top: scroller.top,
        bottom: scroller.bottom,
        scroll: scroller.node.scrollTop,
        max: scroller.max,
        elapsedMs: reduce ? sinceStep : elapsed,
        reduce
      });
      if (delta === 0) return;
      sinceStep = 0;
      scroller.node.scrollTop += delta;
      const scrolled = scroller.node.scrollTop - scroller.start;
      setDrag((current) => {
        if (!current) return current;
        const box = current.boxes[current.from];
        if (!box) return current;
        const to = dragTargetIndex(current.boxes, current.from, current.offset + scrolled);
        if (scrolled === current.scrolled && to === current.to) return current;
        return { ...current, scrolled, to };
      });
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [dragging]);

  const registerRow = useCallback(
    (key: string) => (node: HTMLElement | null) => {
      if (node) rowRefs.current.set(key, node);
      else rowRefs.current.delete(key);
    },
    []
  );

  const handleProps = useCallback(
    (key: string) => ({
      onPointerDown(event: React.PointerEvent<HTMLElement>) {
        if (locked || event.button !== 0) return;
        const from = order.indexOf(key);
        if (from < 0) return;
        const boxes = order.map((rowKey) => {
          const box = rowRefs.current.get(rowKey)?.getBoundingClientRect();
          return { top: box?.top ?? 0, height: box?.height ?? 0 };
        });
        const node = rowRefs.current.get(key) ?? null;
        const scroller = scrollerFor(node);
        const documentScroller = scroller === document.scrollingElement || scroller === document.documentElement;
        const view = documentScroller ? null : scroller.getBoundingClientRect();
        scrollerRef.current = {
          node: scroller,
          top: view ? view.top : 0,
          bottom: view ? view.bottom : window.innerHeight,
          start: scroller.scrollTop,
          max: Math.max(0, scroller.scrollHeight - scroller.clientHeight),
          startY: event.clientY,
          pointer: event.clientY,
          armed: false
        };
        event.currentTarget.setPointerCapture(event.pointerId);
        event.preventDefault();
        event.currentTarget.focus();
        setDrag({
          key,
          pointerId: event.pointerId,
          from,
          to: from,
          startY: event.clientY,
          offset: 0,
          scrolled: 0,
          boxes,
          snapshot: [...order]
        });
      },
      onPointerMove(event: React.PointerEvent<HTMLElement>) {
        const clientY = event.clientY;
        const pointerId = event.pointerId;
        const scroller = scrollerRef.current;
        if (scroller) {
          scroller.pointer = clientY;
          if (Math.abs(clientY - scroller.startY) >= ARM_TRAVEL) scroller.armed = true;
        }
        setDrag((current) => {
          if (!current || current.pointerId !== pointerId) return current;
          const offset = clientY - current.startY;
          const box = current.boxes[current.from];
          if (!box) return current;
          const to = dragTargetIndex(current.boxes, current.from, offset + current.scrolled);
          if (offset === current.offset && to === current.to) return current;
          return { ...current, offset, to };
        });
      },
      onPointerUp(event: React.PointerEvent<HTMLElement>) {
        const current = drag;
        setDrag(null);
        scrollerRef.current = null;
        if (!current || current.pointerId !== event.pointerId) return;
        if (current.to === current.from) return;
        const unchanged =
          current.snapshot.length === order.length &&
          current.snapshot.every((value, index) => value === order[index]);
        if (!unchanged) {
          setAnnouncement(
            `${labelFor(current.key)} was not moved: the list changed while it was being dragged. Try again.`
          );
          return;
        }
        if (onReorder(current.from, current.to) !== false) {
          announceMove(current.key, current.to);
        }
      },
      onPointerCancel() {
        setDrag(null);
        scrollerRef.current = null;
      }
    }),
    [announceMove, drag, labelFor, locked, onReorder, order]
  );

  const shift = useMemo(() => {
    const map = new Map<string, number>();
    if (!drag) return map;
    const box = drag.boxes[drag.from];
    if (!box) return map;
    const MAX_ADJACENT_GAP = 64;
    const adjacent = (gap: number) => gap >= 0 && gap <= MAX_ADJACENT_GAP;
    const after = drag.boxes[drag.from + 1];
    const before = drag.boxes[drag.from - 1];
    const gapAfter = after ? after.top - (box.top + box.height) : null;
    const gapBefore = before ? box.top - (before.top + before.height) : null;
    const extent =
      gapAfter !== null && adjacent(gapAfter)
        ? box.height + gapAfter
        : gapBefore !== null && adjacent(gapBefore)
          ? box.height + gapBefore
          : box.height;
    order.forEach((key, index) => {
      if (index === drag.from) {
        map.set(key, drag.offset + drag.scrolled);
        return;
      }
      if (index > drag.from && index <= drag.to) map.set(key, -extent);
      else if (index < drag.from && index >= drag.to) map.set(key, extent);
      else map.set(key, 0);
    });
    return map;
  }, [drag, order]);

  const shiftFor = useCallback((key: string) => shift.get(key) ?? 0, [shift]);

  return {
    registerRow,
    handleProps,
    shiftFor,
    draggingKey: drag?.key ?? null,
    announcement,
    announceMove
  };
}
