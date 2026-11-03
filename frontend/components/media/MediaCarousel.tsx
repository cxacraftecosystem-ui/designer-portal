"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { ChevronLeft, ChevronRight, ImageOff, Maximize2 } from "lucide-react";

import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import { useMediaUrl } from "@/components/hooks/useMediaUrl";
import { MediaLightbox, type PreviewMedia } from "@/components/media/MediaLightbox";

const EASE_OUT = [0.16, 1, 0.3, 1] as const;

export type CarouselItem = PreviewMedia;

const TRAILING_PICTURE_WORD = /(?:^|\s)photographs?$/i;

export function describeSubject(noun: string): { one: string; many: string } {
  const stem = noun.replace(/\s+/g, " ").trim().replace(TRAILING_PICTURE_WORD, "").trim();
  if (!stem) return { one: "photograph", many: "photographs" };
  return { one: `${stem} photograph`, many: `${stem} photographs` };
}

export function MediaCarousel({
  items,
  noun,
  className = "h-72 sm:h-96"
}: {
  items: readonly CarouselItem[];
  noun: string;
  className?: string;
}) {
  const reduce = useAppReducedMotion();
  const [position, setPosition] = useState<{ index: number; direction: number }>({ index: 0, direction: 1 });
  const { index, direction } = position;
  const [zoomed, setZoomed] = useState<CarouselItem | null>(null);
  const total = items.length;

  const subject = describeSubject(noun);

  const safeIndex = total === 0 ? 0 : Math.min(index, total - 1);
  const current = total === 0 ? null : items[safeIndex];

  const go = useCallback(
    (next: number) => {
      if (total === 0) return;
      const wrapped = ((next % total) + total) % total;
      let heading = wrapped === safeIndex ? 0 : wrapped > safeIndex ? 1 : -1;
      if (safeIndex === total - 1 && wrapped === 0) heading = 1;
      if (safeIndex === 0 && wrapped === total - 1) heading = -1;
      setPosition({ index: wrapped, direction: heading });
    },
    [safeIndex, total]
  );

  const onKeyDown = useCallback(
    (event: React.KeyboardEvent) => {
      if (zoomed) return;
      if (total < 2) return;
      if (event.key === "ArrowRight") {
        event.preventDefault();
        go(safeIndex + 1);
      } else if (event.key === "ArrowLeft") {
        event.preventDefault();
        go(safeIndex - 1);
      } else if (event.key === "Home") {
        event.preventDefault();
        go(0);
      } else if (event.key === "End") {
        event.preventDefault();
        go(total - 1);
      }
    },
    [go, safeIndex, total, zoomed]
  );

  const stripRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const strip = stripRef.current;
    if (!strip) return;
    const active = strip.querySelector<HTMLElement>('[data-active="true"]');
    active?.scrollIntoView({ behavior: reduce ? "auto" : "smooth", block: "nearest", inline: "nearest" });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [safeIndex, total]);

  const variants = useMemo(
    () => ({
      enter: (dir: number) => ({ opacity: 0, x: reduce ? 0 : dir * 12 }),
      center: { opacity: 1, x: 0 },
      exit: (dir: number) => ({ opacity: 0, x: reduce ? 0 : dir * -12 })
    }),
    [reduce]
  );

  if (total === 0) {
    return (
      <div className="grid min-h-24 place-items-center gap-1 rounded-md border border-dashed border-line-200 bg-surface-50 p-4 text-center text-xs text-ink-500">
        <ImageOff className="h-4 w-4" aria-hidden />
        No {subject.many} yet.
      </div>
    );
  }

  return (
    <section
      aria-roledescription="carousel"
      aria-label={subject.many}
      className="grid gap-2"
      onKeyDown={onKeyDown}
    >
      <div
        className={`relative overflow-hidden rounded-md border border-line-200 bg-surface-50 ${className}`}
      >
        <AnimatePresence initial={false} custom={direction}>
          <motion.div
            key={current?.key ?? safeIndex}
            custom={direction}
            variants={variants}
            initial="enter"
            animate="center"
            exit="exit"
            transition={reduce ? { duration: 0 } : { duration: 0.22, ease: EASE_OUT }}
            className="absolute inset-0 grid place-items-center"
          >
            <Slide item={current} onZoom={() => current && setZoomed(current)} />
          </motion.div>
        </AnimatePresence>

        {total > 1 ? (
          <>
            <ArrowButton side="left" label={`Previous ${subject.one}`} onClick={() => go(safeIndex - 1)} />
            <ArrowButton side="right" label={`Next ${subject.one}`} onClick={() => go(safeIndex + 1)} />
          </>
        ) : null}
      </div>

      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-xs text-ink-500">
          {safeIndex + 1} of {total}
          {current?.caption ? <span className="text-ink-700"> · {current.caption}</span> : null}
        </p>
        <p className="min-w-0 truncate text-xs text-ink-500">{current?.name}</p>
      </div>

      {total > 1 ? (
        <div
          ref={stripRef}
          className="flex gap-2 overflow-x-auto pb-1"
        >
          {items.map((item, itemIndex) => {
            const active = itemIndex === safeIndex;
            return (
              <button
                key={item.key}
                type="button"
                data-active={active ? "true" : undefined}
                aria-current={active ? "true" : undefined}
                onClick={() => go(itemIndex)}
                title={item.name}
                className={`h-14 w-14 shrink-0 overflow-hidden rounded-sm border bg-card transition ${
                  active ? "border-purple-600 ring-2 ring-purple-600/20" : "border-line-200 hover:border-purple-300"
                }`}
              >
                {item.url ? (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img src={item.url} alt="" loading="lazy" decoding="async" className="h-full w-full object-cover" />
                ) : (
                  <span className="grid h-full w-full place-items-center text-[0.625rem] leading-3 text-ink-500">
                    {itemIndex + 1}
                  </span>
                )}
              </button>
            );
          })}
        </div>
      ) : null}

      {zoomed ? <MediaLightbox item={zoomed} onClose={() => setZoomed(null)} /> : null}
    </section>
  );
}

function ArrowButton({
  side,
  label,
  onClick
}: {
  side: "left" | "right";
  label: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      className={`absolute top-1/2 grid h-9 w-9 -translate-y-1/2 place-items-center rounded-full border border-line-200 bg-card/90 text-ink-700 shadow-sm backdrop-blur-sm transition hover:border-purple-300 hover:text-purple-700 ${
        side === "left" ? "left-2" : "right-2"
      }`}
    >
      {side === "left" ? (
        <ChevronLeft className="h-5 w-5" aria-hidden />
      ) : (
        <ChevronRight className="h-5 w-5" aria-hidden />
      )}
    </button>
  );
}

function Slide({ item, onZoom }: { item: CarouselItem | null; onZoom: () => void }) {
  const { src, onError } = useMediaUrl(item);
  if (!item) return null;
  if (!item.url) {
    return (
      <div className="grid place-items-center gap-1 p-4 text-center text-xs text-ink-500">
        <ImageOff className="h-4 w-4" aria-hidden />
        {item.name} is stored, but this account may not open the file itself.
      </div>
    );
  }
  return (
    <button
      type="button"
      onClick={onZoom}
      className="group relative grid h-full w-full place-items-center"
      aria-label={`View ${item.caption?.trim() || item.name} full screen`}
    >
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src={src ?? item.url}
        onError={onError}
        alt={item.caption?.trim() || item.name}
        loading="lazy"
        className="max-h-full max-w-full object-contain"
      />
      <span className="absolute right-2 top-2 grid h-8 w-8 place-items-center rounded-full border border-line-200 bg-card/90 text-ink-700 opacity-0 shadow-sm backdrop-blur-sm transition group-hover:opacity-100 group-focus-visible:opacity-100">
        <Maximize2 className="h-4 w-4" aria-hidden />
      </span>
    </button>
  );
}
