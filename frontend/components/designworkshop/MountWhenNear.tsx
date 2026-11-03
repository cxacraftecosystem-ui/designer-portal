"use client";

import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";

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
  preview: string;
  labelledBy?: string;
  disabled?: boolean;
  children: ReactNode;
}) {
  const [mounted, setMounted] = useState(false);
  const holderRef = useRef<HTMLDivElement | null>(null);
  const focusOnMount = useRef(false);

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
