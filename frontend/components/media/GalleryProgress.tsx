"use client";

import { galleryProgress, type GalleryCounts } from "@/components/media/photoGate";

/**
 * "18 OF 25" — how full a gallery with a declared floor is, drawn three ways at once.
 *
 * ── WHY THREE ────────────────────────────────────────────────────────────────────────────────────
 *
 * Non-negotiable 5 of the frontend contract: a signal that exists only as motion is a signal a
 * reduced-motion reader never gets. The same argument applies without alteration to a signal that
 * exists only as a LENGTH — `MediaCarousel`'s header makes it about a POSITION and reaches the same
 * answer ("the slide is the ornament, the readout is the state"). A filled bar is a picture of a
 * ratio and nothing else: it is unreadable in greyscale at a glance, unreadable to anybody using a
 * screen reader, and it is the half a designer photographing motifs in bright sun outdoors can see
 * least well. So the bar carries the shape, the digits carry the number, and the sentence carries
 * everything the number leaves out — what is still uploading, what is on this device only, and how
 * many are left. Any one of the three could be removed and the field would still be answerable.
 *
 * ── IT IS A LEVEL, SO IT IS DESCRIBED AND NEVER ANNOUNCED ────────────────────────────────────────
 *
 * `role="progressbar"` and NOT `role="status"`, and the difference is twenty-five interruptions.
 * This number moves on every single attach and every single remove, from the first photograph to the
 * twenty-fifth. A live region would re-read the whole sentence each time, which is the shape §17
 * forbids for a scroll-position readout and which the ceiling paragraph in `FieldInput` was moved
 * out of a live region for one wave earlier, for this exact reason. A `progressbar` is a value a
 * reader can query when they want it and which interrupts nobody; `aria-valuetext` is what makes
 * that query answer in words rather than in a bare percentage, which is the whole reason the
 * attribute exists. The one event that MUST interrupt — a photograph this device refused — is a
 * separate live region in the field, and it names the files.
 *
 * ── REDUCED MOTION IS HANDLED BY NOT REACHING FOR JAVASCRIPT ─────────────────────────────────────
 *
 * The fill's width is a plain CSS `transition`, exactly as `UploadProgress` draws its own bar, so
 * both global reduced-motion rules in `globals.css` reach it and zero it — the OS media query and the
 * in-app `[data-reduced-motion="true"]` attribute, unioned. There is no framer-motion here and there
 * must not be: framer writes inline styles that CSS cannot reach, so a `motion.div` would need a
 * `useAppReducedMotion()` branch to do what one utility class already does correctly. And with the
 * transition zeroed the bar still shows the right length — it simply arrives there without travelling
 * — because the length is the state and the movement was only ever the ornament.
 */
export function GalleryProgress({
  counts,
  floor,
  label,
  labelledBy,
  className = ""
}: {
  counts: GalleryCounts;
  /** The registry's declared `minItems`. Never a literal — see `declaredMinItems`. */
  floor: number;
  /** The field's own label, so the bar names what it is measuring rather than "progress". */
  label: string;
  /** The field group's label element, so the bar is named by the same words the group is. */
  labelledBy?: string;
  className?: string;
}) {
  const progress = galleryProgress({ counts, floor });

  return (
    <div className={`grid gap-1.5 ${className}`}>
      <div className="flex items-baseline justify-between gap-3">
        {/* The bar's own caption. `text-ink-500` and not a heading: the field already has a label
            above it, and a second heading inside a form control's group is how a document grows a
            second outline (`EmptyState`'s hardcoded <h2> is the same trap one component over). */}
        <span className="text-xs leading-5 text-ink-500">{label}</span>
        {/* THE DIGITS. `tabular-nums` so the readout does not jitter horizontally as it counts up —
            proportional figures change width between "18 of 25" and "19 of 25" and the eye reads
            that as the whole line moving. `font-semibold` and `text-ink-900` because this is the
            number the designer came to read; everything else on the row is context for it. */}
        <span className="shrink-0 text-xs font-semibold tabular-nums text-ink-900">{progress.readout}</span>
      </div>
      <div
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={floor}
        aria-valuenow={progress.held}
        /* THE WORDS, WHICH ARE THE POINT. Without this a screen reader announces "18 of 25" and
           nothing about the two still uploading or the eleven that exist only in this browser —
           the same sentence a sighted reader gets below, so neither reader is working from a
           smaller truth than the other. */
        aria-valuetext={progress.words}
        aria-labelledby={labelledBy}
        className="h-2 overflow-hidden rounded-full bg-line-200"
      >
        {/*
          `bg-purple-700` — the one action colour, and the same fill `UploadProgress` uses, so the two
          bars a designer meets in one form are one vocabulary. It deliberately does NOT turn green at
          25: `success-600` would be a second accent on a data screen (non-negotiable 1), and a
          colour change is the one way of saying "done" that is invisible to a reader in greyscale,
          in forced-colours mode, or listening. The sentence below says it in words instead.

          `transition-[width]` and not `transition-all`: the only property that moves here is the
          width, and naming it keeps the reduced-motion rules' `transition-duration: 0.01ms` from
          being the only thing standing between a theme change and a cross-fading bar.
        */}
        <div
          className="h-full rounded-full bg-purple-700 transition-[width] duration-300 ease-out"
          style={{ width: `${progress.percent}%` }}
        />
      </div>
      {/* Plain text, no role. It is the same sentence `aria-valuetext` carries, so announcing it
          would say everything twice — once when the reader queries the bar and once when the region
          mutated. Present for the sighted reader, who has no way to query anything. */}
      <p className="text-xs leading-5 text-ink-500">{progress.words}</p>
    </div>
  );
}
