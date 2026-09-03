"use client";

/**
 * A gallery browsed one picture at a time — the motif references, and any other IMAGE_LIST worth
 * looking AT rather than counting.
 *
 * Asked for on 2026-08-25 for the two new motif galleries: *"Uploaded Traditional Motif images
 * should be viewable through a carousel so that users can visually browse the uploaded
 * references."* A thumbnail grid answers "how many did I attach"; it does not answer "is this the
 * right motif", which needs one image big enough to see.
 *
 * ── INLINE, NOT MODAL, AND THAT IS THE WHOLE DIFFERENCE FROM `MediaLightbox` ─────────────────────
 *
 * `MediaLightbox` already exists and is NOT what this is. It is a focus-trapping `aria-modal`
 * dialog over a blacked-out page, for looking at ONE file that was clicked. A carousel is a
 * reading control that sits in the form beside the box it belongs to, so a designer can flick
 * through twenty motifs while the bullet list they are writing is still on screen. Trapping focus
 * for that would be actively wrong — the reader's next stop is the textarea underneath.
 *
 * So the two compose rather than compete: this draws the strip and the big frame, and the frame is
 * a button that opens `MediaLightbox` for the full-screen view. Nothing about lightboxing is
 * reimplemented here.
 *
 * ── THE POSITION IS A NUMBER ON SCREEN, NOT A PLACE IN A ROW OF DOTS ────────────────────────────
 *
 * "3 of 12" is printed, and the active thumbnail also carries a ring. Rule 5 of the frontend
 * contract: a signal that only exists as motion is a signal a reduced-motion reader never gets —
 * and the same argument applies to a signal that exists only as position. The slide is the
 * ornament; the readout is the state.
 *
 * ── MOTION ──────────────────────────────────────────────────────────────────────────────────────
 *
 * One `AnimatePresence` cross-fade with a directional 12px slide, gated on `useAppReducedMotion()`
 * in JS — CSS cannot reach framer's inline styles, and there is no `MotionConfig reducedMotion` in
 * this app (trap index, §17). Under reduced motion the swap is instantaneous and the readout still
 * changes, which is the whole point of having a readout.
 *
 * `mode="wait"` is deliberately NOT used: waiting for the outgoing image to leave before the
 * incoming one arrives means a blank frame on every step, and on a slow connection that blank is
 * where a 2 MB photograph is being fetched. Both are mounted, the incoming one on top.
 *
 * ── KEYBOARD ────────────────────────────────────────────────────────────────────────────────────
 *
 * Left/Right move; Home/End jump to the ends. The handler is on the carousel's own container with
 * `tabIndex={-1}`-free semantics — it is bound to the region, not to `window`, because unlike a
 * modal this thing shares the page with a form full of text boxes and a global Left-arrow handler
 * would fight the caret in every one of them. A reader arrives at the buttons by Tab like any other
 * control; the arrow keys are an accelerator for whoever is already inside.
 *
 * They stand down completely while the lightbox is open, and the reason is that the dialog does not
 * portal — its keystrokes bubble into this very region. The whole account is at the top of
 * `onKeyDown`, which is where the guard is.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { ChevronLeft, ChevronRight, ImageOff, Maximize2 } from "lucide-react";

import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import { useMediaUrl } from "@/components/hooks/useMediaUrl";
import { MediaLightbox, type PreviewMedia } from "@/components/media/MediaLightbox";

/** The house cubic. Named in §8.2 of the frontend contract; do not invent another. */
const EASE_OUT = [0.16, 1, 0.3, 1] as const;

export type CarouselItem = PreviewMedia;

/**
 * A trailing picture word, and ONLY the one this file writes itself.
 *
 * `(?:^|\s)` rather than a bare suffix test: with `\s*` a label reading "microphotographs" would be
 * stripped to "micro", which is a word taken out of somebody's label to fix a stutter that was never
 * there. Either the string IS the picture word or the picture word follows a space. The cost of that
 * choice, stated: such a label doubles instead ("microphotographs photographs"). No registry label
 * looks like this, and mangling a real word is the worse of the two failures.
 */
const TRAILING_PICTURE_WORD = /(?:^|\s)photographs?$/i;

/**
 * THE SUBJECT OF THE GALLERY, IN SINGULAR AND PLURAL, DERIVED FROM WHATEVER THE CALLER PASSED.
 *
 * ── THE DEFECT: THE ACCESSIBLE NAME SAID "PHOTOGRAPHS" TWICE, ON EVERY ARROW PRESS ──────────────
 *
 * All four strings below used to write the picture word after `noun` — `${noun} photographs` on the
 * region, `No ${noun} photographs yet.`, `Previous ${noun} photograph` — and `noun`'s own doc asked
 * the caller for a bare subject ("traditional motif") to make that read. NEITHER call site ever
 * passed one: `FieldInput` passes `field.label.toLowerCase()` and the Android twin passes
 * `field.label.lowercase()`. A carousel is mounted only on a CAPPED gallery, and the two capped
 * galleries in the registry are labelled "Traditional motif photographs" and "Contemporary motif
 * photographs" (`backend/app/services/stage_definitions.py`). So on stage 4 the region announced
 * "traditional motif photographs photographs", the empty state read "No traditional motif
 * photographs photographs yet.", and each arrow said "Previous traditional motif photographs
 * photograph" — the stutter once on arrival, then again on every single step.
 *
 * ── WHY THE RULE LIVES HERE AND NOT AT THE CALL SITES ───────────────────────────────────────────
 *
 * Fixing the callers would mean making the same edit a second time on the handset, by somebody who
 * has to notice it is due — and the evidence that they would not is that two independent call sites
 * already made the identical mistake in the same words. This file is also the only place that knows
 * HOW the word gets used: as a region name, inside a sentence about an empty gallery, and in
 * "Previous {subject} photograph", where one step needs the SINGULAR and the region needs the
 * plural. A caller cannot supply a form it is never told about. Expressed here it is one rule, and
 * the twin adopts it by copying one function rather than by remembering a convention.
 *
 * So either shape works and both produce the same four strings: the field's label ("Traditional
 * motif photographs") or a bare noun ("traditional motif"). The empty stem is a real case and not
 * defensiveness — three IMAGE_LIST fields in the registry are labelled exactly "Photographs"
 * (`productPhotos`, `responsePhotos`, `logPhotos`), and any of them would reach this component the
 * day it declares a cap; an empty `noun` also printed "No  photographs yet." with a doubled space,
 * which is why the whitespace is collapsed rather than only trimmed.
 *
 * ── WHAT THIS DELIBERATELY DOES NOT DO (rule 10) ────────────────────────────────────────────────
 *
 * It recognises "photograph"/"photographs" and no other picture word. "Photos", "images" and
 * "pictures" are left alone because no registry label uses them, and a stripper guessing at synonyms
 * eventually eats a real word. A label ending in anything else keeps all of it: "360° capture"
 * becomes "360° capture photograph", which is what such a gallery holds.
 *
 * It also never re-cases. `FieldInput` lowercases the label so the two sentences read as sentences;
 * title case would suit the region's name better, but the lowering that turns "Traditional" into
 * "traditional" would flatten a proper noun in a label the registry has not written yet, and case is
 * not something a screen reader pronounces. Whatever arrived comes back, one picture word longer or
 * shorter.
 *
 * ── THE ANDROID TWIN NOW HAS THIS TREATMENT, AS OF 2026-08-26 ───────────────────────────────────
 *
 * `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwMediaCarousel.kt` is
 * the twin of this file, takes the same `noun`, and is handed the same label by `DwMediaCapture.kt`.
 * Its symptom was never the stutter — it appends no picture word of its own — but a PLURAL on a
 * one-step control: `noun` reached exactly two strings, `"Previous $noun"` and `"Next $noun"`,
 * announcing "Previous traditional motif photographs".
 *
 * It derives the pair itself now. `dwDescribeSubject` (DwMediaCarousel.kt:178) is the Kotlin twin of
 * `describeSubject` below; the arrows read `"Previous ${subject.one}"` / `"Next ${subject.one}"`
 * (:347/:360) and the frame carries a region name from `subject.many` (:281). `DwMediaCarouselSubjectTest`
 * exercises the rule without a device, exactly as the spec below does without a browser.
 *
 * THIS PARAGRAPH USED TO END "THE PORT IS OUTSTANDING", with the three steps to copy. The port
 * landed in the same pass that wrote this correction, and a note instructing the next reader to redo
 * finished work is the failure §16 of the frontend contract is about: a platform difference is
 * commented, never left as a paraphrase — and never left describing a difference that is gone.
 *
 * Exported so the rule can be exercised without a browser: there is no React renderer in
 * devDependencies, so a judgement written inside JSX is only ever checked by somebody looking at a
 * screen. `e2e/media-carousel-unit.spec.ts` calls it directly, which is the only way these four
 * strings are checked at all — they shipped wrong on 2026-08-25 and were found by a reader, not by a
 * gate. (This sentence said "nothing calls it from a spec today" until 2026-08-26, and the spec that
 * refutes it was written in the same pass.)
 */
export function describeSubject(noun: string): { one: string; many: string } {
  const stem = noun.replace(/\s+/g, " ").trim().replace(TRAILING_PICTURE_WORD, "").trim();
  if (!stem) return { one: "photograph", many: "photographs" };
  return { one: `${stem} photograph`, many: `${stem} photographs` };
}

export function MediaCarousel({
  items,
  /**
   * WHAT THESE PICTURES ARE — either the field's own label ("Traditional motif photographs") or a
   * bare noun ("traditional motif"). Both are accepted and both produce the same four strings; the
   * region's accessible name, the empty sentence and the two arrows are all built from it by
   * `describeSubject`, so a page with two carousels on it does not announce "Gallery" twice.
   *
   * THIS DOC USED TO ASK FOR THE BARE NOUN ONLY, and neither of the two call sites in the repository
   * honoured it — both pass the label. A contract only its author obeys is not a contract, and
   * describing one is how the doubled "photographs photographs" survived review on two clients at
   * once. See `describeSubject` for the whole account.
   */
  noun,
  /**
   * Height of the big frame. A motif plate and a cluster panorama are not the same shape.
   *
   * APPLIED TO THE FRAME AND NOT TO THE SLIDES, which is what fixes the frame's height while a slide
   * crosses it — see the note at the container. A caller passing anything other than a height here
   * is styling the box the arrows sit on, not the picture.
   */
  className = "h-72 sm:h-96"
}: {
  items: readonly CarouselItem[];
  noun: string;
  className?: string;
}) {
  const reduce = useAppReducedMotion();
  /**
   * WHERE THE READER IS, AND WHICH WAY THEY CAME — ONE piece of state, not two.
   *
   * The direction is what makes the slide lean the way the reader pressed, so it is READ DURING
   * RENDER (framer's `custom` prop feeds the variants). A ref would therefore be wrong twice over:
   * `react-hooks/refs` refuses reading `.current` in render, and it is right to — a ref written in a
   * handler is a value React did not schedule the render for, so the first frame after a step would
   * animate with the PREVIOUS direction. Coupling the two into one object also makes them impossible
   * to set apart, which they must never be: an index without its direction is a slide that leans at
   * random.
   */
  const [position, setPosition] = useState<{ index: number; direction: number }>({ index: 0, direction: 1 });
  const { index, direction } = position;
  const [zoomed, setZoomed] = useState<CarouselItem | null>(null);
  const total = items.length;

  /**
   * The gallery's subject, once, for all four accessible strings — see `describeSubject` above for
   * why the derivation is this component's job and not the caller's.
   *
   * Not a `useMemo`: two string operations are cheaper than the machinery that would guard them, and
   * unlike `variants` below this value's IDENTITY is never compared by anything — it is read into
   * template literals and thrown away.
   */
  const subject = describeSubject(noun);

  /**
   * CLAMPED ON EVERY RENDER, NEVER "FIXED UP" IN AN EFFECT.
   *
   * The list shrinks under this component in the ordinary course of use — a designer discards the
   * photograph they are looking at, and `items` arrives one shorter on the very next render. An
   * effect that noticed and corrected `index` would render ONCE with an out-of-range index first,
   * which is `items[7]` of a seven-item array: `undefined`, and a crash in the frame below. Deriving
   * the safe index means there is no such render to get wrong.
   */
  const safeIndex = total === 0 ? 0 : Math.min(index, total - 1);
  const current = total === 0 ? null : items[safeIndex];

  const go = useCallback(
    (next: number) => {
      if (total === 0) return;
      // WRAPPED, not clamped, and the modulo is written to survive a negative. `-1 % 12` is `-1` in
      // JavaScript, not `11`, so the naive form lands out of range going backwards off the front.
      const wrapped = ((next % total) + total) % total;
      let heading = wrapped === safeIndex ? 0 : wrapped > safeIndex ? 1 : -1;
      // Going from the last to the first (or back) reads better leaning the way the reader PRESSED
      // than the way the index jumped — a wrap is a step forward that happens to land at zero.
      if (safeIndex === total - 1 && wrapped === 0) heading = 1;
      if (safeIndex === 0 && wrapped === total - 1) heading = -1;
      setPosition({ index: wrapped, direction: heading });
    },
    [safeIndex, total]
  );

  const onKeyDown = useCallback(
    (event: React.KeyboardEvent) => {
      /*
        NOTHING WHILE THE LIGHTBOX IS OPEN — AND THE GUARD BELONGS HERE, NOT ON THE DIALOG.

        ── THE DEFECT ─────────────────────────────────────────────────────────────────────────────

        `MediaLightbox`, rendered at the foot of this component, does NOT portal: its root is a plain
        `fixed inset-0 z-[100]` div (`components/media/MediaLightbox.tsx:314`), so while it is open it
        is still a CHILD of the section below and React's synthetic bubbling walks every keystroke
        made inside the dialog straight up into this handler. The dialog's own key handling is a
        `window` listener that answers Escape and Tab and nothing else
        (`MediaLightbox.tsx:254-296`), so Left, Right, Home and End were handled HERE, by the
        carousel underneath — and `zoomed` is a frozen snapshot of whatever was clicked (set once at
        the frame, never re-derived from `safeIndex`), so the frame, the "N of M" readout and the
        ringed thumbnail all stepped along behind a dialog that went on showing the original
        photograph. Closing it left the reader somewhere they never navigated to, with nothing said.

        ── WHY NOT A `stopPropagation` ON THE DIALOG ──────────────────────────────────────────────

        Because it would take the dialog's keyboard with it. React calls the NATIVE
        `stopPropagation` when a synthetic handler stops one, so a key handler on that overlay stops
        the event ever reaching `window` — which is exactly where `MediaLightbox` listens for its own
        Escape and its Tab wrap. The focus trap would go, at every call site of that component, to
        fix a collision only this one has.

        ── WHY NOT STEP THE DIALOG'S SUBJECT INSTEAD ──────────────────────────────────────────────

        Handing the dialog `item={current}` so it follows the carousel reads like the better answer
        and is a much larger change than a prop swap: that component's focus effect is keyed on
        `[onClose]` and its teardown does `if (opener?.isConnected) opener.focus()`
        (`MediaLightbox.tsx:254-296`), so a subject that steps means re-writing another component's
        focus protocol — every step would hand focus back to the thumbnail behind the overlay. The
        arrows inside a modal are the modal's business; this reading control stands down until it is
        the thing on screen again.
      */
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

  /**
   * Keep the active thumbnail in the strip's view.
   *
   * `block: "nearest"` and `inline: "nearest"` — the minimum-distance rule the whole app uses for
   * this (see `useRevealRow`). Without `block: "nearest"` this scrolls the PAGE to bring a strip
   * that was already visible into a different part of the viewport, which on the stage form drags
   * the textarea the designer is typing in off the screen.
   */
  const stripRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const strip = stripRef.current;
    if (!strip) return;
    const active = strip.querySelector<HTMLElement>('[data-active="true"]');
    active?.scrollIntoView({ behavior: reduce ? "auto" : "smooth", block: "nearest", inline: "nearest" });
    // `reduce` is READ, never a dependency — it settles after mount (ThemeProvider reads storage),
    // and as a dep it would tear this effect down for exactly the readers who asked for less motion.
    // The trap is written up in §17 of the frontend contract.
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
      {/*
        THE FRAME OWNS THE HEIGHT AND THE SLIDES ARE STACKED INSIDE IT.

        ── THE DEFECT THIS REPLACES, WHICH WAS A COMMENT DESCRIBING SOMETHING THE CSS DID NOT DO ─────

        The slides used to carry `style={{ gridArea: "1 / 1" }}` and a comment claiming they were
        "absolutely positioned only while two are mounted". `gridArea` on a child of a NON-GRID parent
        is inert — this container was `relative overflow-hidden`, never `grid` — so the property did
        nothing at all. `AnimatePresence` without `mode="wait"` keeps both frames mounted for the
        0.22s exit, and two `h-64` blocks in normal flow are 512px: every press of Next doubled the
        container's height for 220ms, shoving the bullet-list textarea the designer is comparing the
        motif against down the page and back again.

        Fixed by putting the height on the FRAME and taking the slides out of flow with
        `absolute inset-0`, which is the mechanism the old comment was reaching for. Now the frame is
        a fixed box whatever is inside it, so the page cannot move while a slide crosses it.

        `mode="wait"` is still deliberately NOT used: it would blank the frame between slides, and on
        a village connection that blank is where a 2 MB photograph is being fetched.

        Reduced-motion readers were never affected (both arms are `duration: 0`), which is exactly why
        this survived review by eye — the jump only exists when the animation does.
      */}
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
            {/* The SINGULAR — one press moves by one picture, and the label a reader hears on every
                step is the one place a plural is most obviously wrong. */}
            <ArrowButton side="left" label={`Previous ${subject.one}`} onClick={() => go(safeIndex - 1)} />
            <ArrowButton side="right" label={`Next ${subject.one}`} onClick={() => go(safeIndex + 1)} />
          </>
        ) : null}
      </div>

      {/*
        THE READOUT. `aria-live="polite"` is deliberately absent: the reader moved the carousel
        themselves, so announcing the new position is telling them what they just did — and the
        buttons' own labels plus the image's alt text already carry it. The same reasoning as the
        guide rail's percent readout (§9.10).
      */}
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
          // `overflow-x-auto` on the strip alone — the page body must never scroll sideways.
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
                  <img src={item.url} alt="" loading="lazy" className="h-full w-full object-cover" />
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

/** One arrow, over the frame. Absolute so it does not eat width from the image. */
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
      className={`absolute top-1/2 grid h-9 w-9 -translate-y-1/2 place-items-center rounded-full border border-line-200 bg-card/90 text-ink-700 shadow-sm backdrop-blur transition hover:border-purple-300 hover:text-purple-700 ${
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

/**
 * One frame of the carousel.
 *
 * A BUTTON AND NOT A BARE IMAGE, because it opens the lightbox — and a click target that is not a
 * button is unreachable from the keyboard. `alt` is the caption where there is one and the filename
 * otherwise: a gallery of twenty motifs whose every image announced "motif photograph" would tell a
 * screen-reader user nothing about which one they are on, which is the one thing the control is for.
 */
function Slide({ item, onZoom }: { item: CarouselItem | null; onZoom: () => void }) {
  // Called unconditionally, ABOVE the two early returns, because a hook cannot sit behind one. It
  // is a no-op for a null item and for a row with no url, which are exactly those two branches.
  // Signed read URLs expire (`lib/mediaUrlRefresh.ts`); a carousel is a reading control that sits
  // in a form for as long as somebody is typing beside it, so it is one of the surfaces most likely
  // to be holding a stale one.
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
      <span className="absolute right-2 top-2 grid h-8 w-8 place-items-center rounded-full border border-line-200 bg-card/90 text-ink-700 opacity-0 shadow-sm backdrop-blur transition group-hover:opacity-100 group-focus-visible:opacity-100">
        <Maximize2 className="h-4 w-4" aria-hidden />
      </span>
    </button>
  );
}
