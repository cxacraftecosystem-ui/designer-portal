"use client";

/**
 * A NARRATIVE EDITOR IS BUILT WHEN IT COMES NEAR THE SCREEN, NOT ALL AT ONCE WHEN THE STAGE OPENS.
 *
 * Stage 4 has eighteen rich-text narratives. Each editor parses its document, lays out a toolbar and
 * wires its own selection handling when it mounts, and building all eighteen in the first commit was
 * the largest cost left on a slow laptop's first open. A designer can only type into the few on
 * screen, so the rest are drawn as their text, read-only, until they come within a generous distance
 * of the viewport (or are focused or pressed), and then become the real editor for good.
 *
 * Nothing about the VALUE changes: the stored document is what the form scores and saves either way;
 * only when its editor is built moves. An editor once built is never taken down again, so a caret or
 * an undo history can never be lost by scrolling.
 */

import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";

/** How far outside the viewport an editor is built ahead of being seen, in pixels. */
export const MOUNT_MARGIN_PX = 1000;

let shared: IntersectionObserver | null = null;
const callbacks = new WeakMap<Element, () => void>();

function observer(): IntersectionObserver | null {
  if (typeof IntersectionObserver === "undefined") return null;
  if (!shared) {
    shared = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue;
          callbacks.get(entry.target)?.();
        }
      },
      { rootMargin: `${MOUNT_MARGIN_PX}px 0px` }
    );
  }
  return shared;
}

function near(element: Element): boolean {
  const rect = element.getBoundingClientRect();
  const height = window.innerHeight || document.documentElement.clientHeight;
  return rect.bottom >= -MOUNT_MARGIN_PX && rect.top <= height + MOUNT_MARGIN_PX;
}

export function MountWhenNear({
  preview,
  labelledBy,
  disabled,
  children
}: {
  /** The field's text, drawn while the editor is not built yet. */
  preview: string;
  labelledBy?: string;
  disabled?: boolean;
  children: ReactNode;
}) {
  const [mounted, setMounted] = useState(false);
  const holderRef = useRef<HTMLDivElement | null>(null);
  const focusOnMount = useRef(false);

  // Before paint: an editor already near the screen is built in the same frame, so the boxes a
  // designer can see never flash as text first.
  useLayoutEffect(() => {
    if (mounted) return;
    const holder = holderRef.current;
    const watch = observer();
    if (!holder || !watch || near(holder)) {
      setMounted(true);
      return;
    }
    callbacks.set(holder, () => setMounted(true));
    watch.observe(holder);
    return () => {
      watch.unobserve(holder);
      callbacks.delete(holder);
    };
  }, [mounted]);

  // Reached by Tab or a press before it scrolled near: build it and hand the caret straight on.
  useEffect(() => {
    if (!mounted || !focusOnMount.current) return;
    focusOnMount.current = false;
    holderRef.current?.querySelector<HTMLElement>('[contenteditable="true"]')?.focus();
  }, [mounted]);

  const build = useCallback(() => {
    focusOnMount.current = true;
    setMounted(true);
  }, []);

  if (mounted) return <div ref={holderRef}>{children}</div>;
  return (
    <div ref={holderRef}>
      <div
        role="textbox"
        aria-multiline="true"
        aria-labelledby={labelledBy}
        aria-disabled={disabled || undefined}
        tabIndex={disabled ? -1 : 0}
        onFocus={build}
        onPointerDown={build}
        className="field-input min-h-32 whitespace-pre-wrap break-words"
      >
        {preview}
      </div>
    </div>
  );
}
