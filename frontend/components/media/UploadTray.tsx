"use client";

import { useEffect, useRef, useState } from "react";
import { CheckCircle2, ChevronDown, UploadCloud, X } from "lucide-react";

import { UploadedMediaChips, formatEta } from "@/components/media/UploadProgress";
import { bytes } from "@/lib/format";
import { useUploads } from "@/lib/uploads";

/**
 * Page-level upload dock: one aggregate bar for everything this page is pushing, the per-section
 * breakdown behind it, and the media that already landed. Sections publish into <UploadsProvider>
 * via <UploadProgress sectionId=…>, so a form only ever reports its own batch and this adds up the
 * page scope. Hidden entirely while the page is idle and nothing has been uploaded yet.
 *
 * ── THIS COMPONENT IS THE "MULTIPLE CARDS OVERLAP OVER EACH OTHER" BUG ──────────────────────────
 *
 * It was reported as a media-card problem and it is not one: measured at 320/360/390/640/768/1024/
 * 1280/1536px, `MediaPreviewTile` and the capture grid that holds it never overlap anything. What
 * overlaps is THIS card, and the cause is one sentence — a `position: fixed` dock whose height had
 * no ceiling.
 *
 * The list at the foot of this file mounts one row per media section, and a design-workshop stage
 * mounts one section per media field: stage 13 has eleven. The completed list below it already had
 * a `max-h-32` scroller; the sections list had none, the card had none, and the fixed wrapper had
 * none, so the dock simply grew. Measured heights against section count, before this fix:
 *
 *     sections   dock     % of a 360×640 phone   % of a 1280×800 laptop
 *      0         165px     26 %                   22 %
 *      3         259px     40 %                   33 %
 *      8         429px     67 %                   55 %
 *     11         531px     83 %                   67 %
 *     16         701px    110 % — top edge at y = −61px, off-screen and UNREACHABLE
 *
 * At eleven sections on a 720px-tall window the dock card and the media capture card underneath it
 * intersected over 288 × 612px at 320px wide and 992 × 583px at 1024px wide: one card lying across
 * essentially the whole of another. And it stays up after the upload finishes — `visible` is true
 * while `completed.length > 0` — so it covered the page during the transfer AND indefinitely
 * afterwards, until somebody found the ✕ underneath the toast that was sitting on it.
 *
 * ── WHY THE FIX IS NOT A z-INDEX, A MARGIN, OR A BIGGER SPACER ──────────────────────────────────
 *
 * A rung cannot help: whichever of two fixed cards wins, one is still drawn over the other, and the
 * loser here is the page the designer is working on. A margin cannot help either — the dock is
 * `position: fixed`, so it is out of flow by definition and no sibling's box affects it.
 *
 * The flow spacer below is doing the only thing a flow spacer CAN do. It sits where <UploadTray/>
 * is mounted, which is the last child of the page, so it reserves room at the very bottom of the
 * document and nowhere else; at every other scroll position — i.e. the whole time somebody is
 * working through a long stage — it reserves nothing. Enlarging it would push the entire page down
 * and still not cover mid-scroll. Height is the only honest lever, and it is pulled three times:
 *
 *   1. the sections list is a BOUNDED SCROLLER (`max-h-32`), the same ceiling the completed list
 *      one block down has always had, and it lays its rows out in multiple columns rather than one
 *      ever-growing stack — eleven sections is four rows on a laptop instead of eleven;
 *   2. the card carries an overall `max-h-[70vh]` so no combination of sections, completions and
 *      window size can put its top edge off the screen the way sixteen sections did;
 *   3. the breakdown opens ITSELF once and then never again — see the effect below.
 *
 * Collapsed, this dock is ~107px of chrome. Expanded and full, it is now bounded at 70vh instead of
 * unbounded, and in practice lands near 380px however many sections are running.
 *
 * ── THE ONE OVERLAP THIS FILE CANNOT CLOSE ──────────────────────────────────────────────────────
 *
 * `components/ui/Toast.tsx` puts its viewport at `fixed bottom-4 right-4 z-[110]` with a
 * `w-[min(24rem,calc(100vw-2rem))]` card. That is the same rectangle as this dock's right-hand end,
 * by construction and at a higher rung, so a toast is drawn INSIDE this card — measured 384 × 69px
 * at ≥640px and 328 × 89px at 360px — and the toast card takes pointer events, so it covers the
 * chevron and the ✕ that dismiss this summary. Neither component knows the other exists, and
 * `AppShell`'s z-ladder note reconciles this dock against the nav sheet's scrim and never against
 * the toast viewport. The fix belongs on the toast's side (offset its `bottom-4` by this dock's
 * measured height, or dock it clear of this one) and Toast.tsx is not this change's to edit; it is
 * written down here so the next person does not have to measure it again.
 */
export function UploadTray() {
  const { enabled, aggregate, sections, completed, clearCompleted } = useUploads();
  const [expanded, setExpanded] = useState(false);
  // Height of the dock, mirrored into a spacer in normal flow so the fixed card can never sit on
  // top of a form's submit button when the page is scrolled to the bottom.
  const [dockHeight, setDockHeight] = useState(0);
  const dockRef = useRef<HTMLDivElement | null>(null);
  /**
   * Whether the dock has already decided its own state on this page.
   *
   * This was `useEffect(() => { if (uploading) setExpanded(true); }, [uploading])`, which re-fires
   * on every idle→uploading transition: a designer who collapsed the breakdown had it forced back
   * open by the next file they attached, and on a stage with eleven media fields that is every few
   * seconds. So the tall state was not something anybody opted into — it was the default, and the
   * measurements above are of a dock nobody asked to be that size. It now opens itself exactly once
   * per mount, and touching the chevron retires the automation for good: a state a person has
   * chosen is never overruled by one this component guessed.
   */
  const dockDecided = useRef(false);

  const uploading = Boolean(aggregate);
  const visible = enabled && (uploading || completed.length > 0);

  useEffect(() => {
    if (!uploading || dockDecided.current) return;
    dockDecided.current = true;
    setExpanded(true);
  }, [uploading]);

  useEffect(() => {
    const node = dockRef.current;
    if (!node || !visible) {
      setDockHeight(0);
      return;
    }
    const measure = () => setDockHeight(node.offsetHeight);
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    return () => observer.disconnect();
  }, [visible, expanded, sections.length, completed.length]);

  if (!visible) return null;

  const percent = aggregate ? Math.round(aggregate.fraction * 100) : 100;

  return (
    <>
      {/* Flow spacer — reserves exactly the room the fixed dock occupies. */}
      <div aria-hidden style={{ height: dockHeight }} />
      <div
        ref={dockRef}
        className="pointer-events-none fixed inset-x-0 bottom-0 z-40 px-4 pt-3"
        style={{ paddingBottom: "max(env(safe-area-inset-bottom), 0.75rem)" }}
      >
        {/*
          `max-h-[70vh]` is the last-resort ceiling, not the working one: the two lists inside are
          each bounded already, so on a laptop this never engages. It exists for the short window —
          a phone in landscape, a split view — where header + bar + two full scrollers would still
          run off the top of the screen, which is the state that made the sixteen-section dock
          unreachable rather than merely large.

          `overflow-y-auto` here is safe for the focus ring even though the global ring is an
          `outline` drawn 2px OUTSIDE the border box: the two buttons it could clip sit inside the
          card's own `p-3`/`sm:p-4`, which is 12px at its narrowest against a 4px ring. Do not
          reduce that padding without checking this again.
        */}
        <div
          className="pointer-events-auto mx-auto grid max-h-[70vh] max-w-7xl gap-3 overflow-y-auto overscroll-contain rounded-lg border border-line-200 bg-card p-3 shadow-lg sm:p-4"
          role="status"
          aria-live="polite"
        >
          {/*
            `min-w-0`, AND IT IS THE SAME RULE THE CARD IN `MediaLightbox` SPELLS OUT AT LENGTH —
            missed here, in the component that turned out to be the actual cause of the report.

            This row is a GRID ITEM of the dock, so its `min-width` is `auto`: the content-based
            minimum. It is also a flex container whose text column holds two `truncate` paragraphs,
            and `truncate` is `white-space: nowrap` — which clips what is PAINTED and leaves the
            box's minimum contribution at the whole unbroken sentence. The second paragraph is
            `${currentFileName} · ${sent} of ${total} · ${eta}`, and a real object name out of
            `buildObjectName` is long, so that one line sized the dock's single grid track to 731px.
            Every child of the dock stretches to that track, so at 320px the section rows, the
            progress bar and the uploaded chips were all laid out 443px wider than the 288px card.

            Measured in Chromium against this repository's compiled Tailwind, 16 sections, a real
            filename: track 731px against a 288px card at 320px wide, 731 against 328 at 360, 731
            against 608 at 640 — and 0 past the card at every one of those widths with this class
            present. It was there before this change too (the card had no `overflow` then, so the
            443px painted invisibly off the side of the screen instead of putting a horizontal
            scrollbar on the card), which is exactly why it is fixed rather than left: `max-h-[70vh]
            overflow-y-auto` above turns silently-clipped overflow into a scroll container, and a
            dock a designer has to drag sideways to reach the file list is the same defect wearing a
            scrollbar. The page itself never scrolled either way — `position: fixed` keeps this out
            of the document's scrollable area — so nothing but this row was ever going to reveal it.
          */}
          <div className="flex min-w-0 items-center gap-3">
            <span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-purple-900 text-purple-100">
              {uploading ? (
                <UploadCloud className="h-4 w-4 animate-pulse" aria-hidden />
              ) : (
                <CheckCircle2 className="h-4 w-4" aria-hidden />
              )}
            </span>
            <div className="min-w-0 flex-1">
              <p className="truncate font-display text-sm font-bold text-ink-900">
                {aggregate
                  ? `Uploading ${aggregate.fileCount} file${aggregate.fileCount === 1 ? "" : "s"} across ${sections.length} section${
                      sections.length === 1 ? "" : "s"
                    }`
                  : `${completed.length} file${completed.length === 1 ? "" : "s"} uploaded on this page`}
              </p>
              <p className="truncate text-xs text-ink-500" title={aggregate?.currentFileName}>
                {aggregate
                  ? `${aggregate.currentFileName} · ${bytes(aggregate.uploadedBytes)} of ${bytes(aggregate.totalBytes)} · ${formatEta(
                      aggregate.etaSeconds
                    )}`
                  : "Open one to preview it, or dismiss this summary."}
              </p>
            </div>
            {aggregate ? <span className="shrink-0 text-sm font-semibold tabular-nums text-ink-900">{percent}%</span> : null}
            <button
              type="button"
              onClick={() => {
                // Touching this control is the designer taking the decision off the component — see
                // `dockDecided`. Set on expand as well as collapse: a dock somebody opened by hand
                // is as much their choice as one they shut.
                dockDecided.current = true;
                setExpanded((current) => !current);
              }}
              aria-expanded={expanded}
              aria-label={expanded ? "Hide upload details" : "Show upload details"}
              className="grid h-8 w-8 shrink-0 place-items-center rounded-md border border-line-200 bg-card text-ink-500 transition hover:border-purple-300 hover:bg-purple-50"
            >
              <ChevronDown className={`h-4 w-4 transition-transform ${expanded ? "rotate-180" : ""}`} aria-hidden />
            </button>
            {!uploading ? (
              <button
                type="button"
                onClick={clearCompleted}
                aria-label="Dismiss the upload summary"
                className="grid h-8 w-8 shrink-0 place-items-center rounded-md border border-line-200 bg-card text-ink-500 transition hover:border-purple-300 hover:bg-purple-50"
              >
                <X className="h-4 w-4" aria-hidden />
              </button>
            ) : null}
          </div>

          {aggregate ? (
            <div className="h-2 overflow-hidden rounded-full bg-line-200">
              <div className="h-full rounded-full bg-purple-700 transition-all" style={{ width: `${percent}%` }} />
            </div>
          ) : null}

          {expanded && sections.length ? (
            /*
              THE ROWS THAT MADE THE DOCK GROW, NOW BOUNDED AND IN COLUMNS.

              One <li> is 34px and there is one per media section, so this list WAS the dock's
              height: eleven sections (design-workshop stage 13) put 531px of fixed card over the
              page. `max-h-32` is the same ceiling the completed list below already had — the two
              were written a block apart and only one of them got it.

              The columns are the other half, and they are the owner's own instruction applied to
              the thing that actually grows: "multiple horizontal stacks next to each other, so as
              to ensure that the depth does not grow too long". Each row is already horizontal —
              label, percentage, bar — so eleven of them are four rows on a laptop and six on a
              phone instead of eleven, and the scroller is reached far less often.

              `role="list"` because Tailwind's preflight strips `list-style` and Safari/VoiceOver
              drops list semantics from a list styled that way; `overscroll-contain` so a scroll
              gesture that reaches the end of this list does not carry on scrolling the page behind
              the dock.
            */
            <ul role="list" className="grid max-h-32 gap-2 overflow-y-auto overscroll-contain pr-1 sm:grid-cols-2 xl:grid-cols-3">
              {sections.map((section) => {
                const sectionPercent = Math.round(section.progress.fraction * 100);
                return (
                  <li key={section.id} className="grid min-w-0 content-start gap-1">
                    <div className="flex items-center justify-between gap-3 text-xs">
                      <span className="truncate text-ink-700">{section.label}</span>
                      <span className="shrink-0 tabular-nums text-ink-500">
                        {sectionPercent}% · file {Math.min(section.progress.fileIndex + 1, section.progress.fileCount)} of{" "}
                        {section.progress.fileCount} · {formatEta(section.progress.etaSeconds)}
                      </span>
                    </div>
                    <div className="h-1.5 overflow-hidden rounded-full bg-line-200">
                      <div className="h-full rounded-full bg-purple-600 transition-all" style={{ width: `${sectionPercent}%` }} />
                    </div>
                  </li>
                );
              })}
            </ul>
          ) : null}

          {expanded && completed.length ? (
            <div className="grid gap-1.5 border-t border-line-200 pt-3">
              <p className="text-xs font-semibold uppercase tracking-wide text-ink-500">
                Uploaded on this page ({completed.length})
              </p>
              <div className="max-h-32 overflow-y-auto overscroll-contain">
                <UploadedMediaChips items={completed} showSection />
              </div>
            </div>
          ) : null}
        </div>
      </div>
    </>
  );
}
