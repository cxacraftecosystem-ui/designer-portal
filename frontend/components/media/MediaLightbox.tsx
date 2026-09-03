"use client";

import { Download, ExternalLink, FileText, Headphones, Image as ImageIcon, Loader2, Maximize2, Video, X } from "lucide-react";
import { useEffect, useRef, type ReactNode } from "react";

import { useMediaUrl } from "@/components/hooks/useMediaUrl";
import { Markdown } from "@/components/Markdown";
import { AudioPlayer } from "@/components/ui/AudioPlayer";
import { bytes } from "@/lib/format";
import type { MediaType } from "@/lib/types";

export type PreviewMedia = {
  key: string;
  /** Persisted MediaFile id — absent for local (not yet uploaded) previews. */
  id?: string | null;
  name: string;
  mediaType: MediaType;
  mimeType?: string | null;
  sizeBytes?: number | string | null;
  url?: string | null;
  caption?: string | null;
  transcriptStatus?: string | null;
  transcriptText?: string | null;
  transcriptError?: string | null;
};

/**
 * The kind we can actually RENDER for this item. Starts from the stored mediaType, but an unknown
 * or generic type (DOCUMENT/OTHER) falls back to MIME sniffing — audio/* plays in the audio player,
 * video/* in the video element, image/* as an image, application/pdf in the PDF frame — so nothing
 * ends up "downloadable but not previewable".
 */
export function resolvePreviewKind(item: Pick<PreviewMedia, "mediaType" | "mimeType" | "name">): MediaType {
  if (item.mediaType === "IMAGE" || item.mediaType === "VIDEO" || item.mediaType === "AUDIO" || item.mediaType === "PDF") {
    return item.mediaType;
  }
  const mime = (item.mimeType ?? "").toLowerCase();
  if (mime.startsWith("image/")) return "IMAGE";
  if (mime.startsWith("video/")) return "VIDEO";
  if (mime.startsWith("audio/")) return "AUDIO";
  if (mime === "application/pdf" || item.name.toLowerCase().endsWith(".pdf")) return "PDF";
  return item.mediaType || "DOCUMENT";
}

function LightboxTranscript({ item }: { item: PreviewMedia }) {
  if (resolvePreviewKind(item) !== "AUDIO") return null;
  const status = (item.transcriptStatus ?? "").toUpperCase();
  const text = item.transcriptText?.trim();
  if (text) {
    return (
      <div className="rounded-md border border-line-200 bg-field-50 p-3">
        <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-ink-soft">Transcript</div>
        <Markdown text={text} />
      </div>
    );
  }
  // A missing status only means "processing" for a persisted MediaFile (it has an id); a local,
  // not-yet-uploaded preview has no transcript job at all, so show nothing for it.
  if (!status && !item.id) return null;
  if (["QUEUED", "PROCESSING", "PENDING", "RUNNING"].includes(status) || !status) {
    return (
      <div className="flex items-center gap-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800">
        <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
        <span>Transcribing audio… the transcript will appear here once processing finishes.</span>
      </div>
    );
  }
  if (["COMPLETED", "EMPTY", "DONE"].includes(status)) {
    return <div className="rounded-md border border-line-200 bg-field-50 px-3 py-2 text-sm text-ink-muted">Transcript completed — no speech detected.</div>;
  }
  return (
    <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
      Transcript {status.toLowerCase()}
      {item.transcriptError ? `: ${item.transcriptError}` : "."}
    </div>
  );
}

function mediaLabel(item: PreviewMedia) {
  return [item.mediaType, item.sizeBytes ? bytes(item.sizeBytes) : null, item.mimeType].filter(Boolean).join(" - ");
}

function iconForType(type: MediaType) {
  if (type === "IMAGE") return <ImageIcon className="h-5 w-5" aria-hidden />;
  if (type === "VIDEO") return <Video className="h-5 w-5" aria-hidden />;
  if (type === "AUDIO") return <Headphones className="h-5 w-5" aria-hidden />;
  return <FileText className="h-5 w-5" aria-hidden />;
}

/**
 * ONE MEDIA FILE, AS A LANDSCAPE CARD THAT NOTHING CAN BE PAINTED ON TOP OF.
 *
 * ── WHAT ACTUALLY OVERLAPPED, SINCE IT WAS NOT THIS ─────────────────────────────────────────────
 *
 * The report was "multiple cards overlap over each other" while uploading media. Measured at
 * 320/360/390/640/768/1024/1280/1536px, this card has never overlapped a sibling in any grid that
 * mounts it: the collision is `UploadTray`'s dock, a `position: fixed` card with no height ceiling
 * that grew one row per media section until it lay across the page underneath it. That is repaired
 * where it is caused, in `components/media/UploadTray.tsx` — the measurements are in the comment
 * there.
 *
 * What this file owned is the SECOND half of the same request: "all media appears in the card
 * format horizontally stacked over one another, for bigger screens, have the same stacked in
 * multiple horizontal stacks next to each other, so as to ensure that the depth does not grow too
 * long". A portrait tile is a 4:3 thumbnail plus two lines of text per file, so twenty attachments
 * in a three-column grid is several screens of scrolling; the same twenty as landscape cards is
 * under one. `MediaCardGrid` is the multi-column half; this is the horizontal card that goes in it.
 *
 * ── WHY FLEXBOX AND NOT A BREAKPOINT ────────────────────────────────────────────────────────────
 *
 * The root is `flex flex-wrap`, and the wrap IS the responsiveness. `tailwind.config.ts` runs with
 * `plugins: []`, so there is no `@container` in this build — and a viewport breakpoint would be the
 * wrong instrument even if there were, because this component is mounted at five different WIDTHS
 * on ONE screen and none of them can be read off the viewport: a 116px cell on /products and /tools
 * (`grid max-w-[240px] grid-cols-2`), a 144px Preview cell on /media, a ~200px slot inside
 * `ExistingMedia`, and the 254–476px columns of `MediaCaptureField`'s grid. An `sm:` rule would
 * flip all of them together and be wrong for three.
 *
 * `flex: 1 1 6rem` on the thumbnail against `flex: 999 1 8rem` on the text is the whole mechanism:
 *   - they share a line only where the card can hold 6rem + 8rem + the gap (≈236px), which is every
 *     capture grid and no table cell — so the wide call sites get the landscape card and the narrow
 *     ones keep the portrait tile they have today, with no call site being told which it is;
 *   - 999 against 1 means that when they DO share a line essentially all the free space goes to the
 *     text and the thumbnail stays at its 6rem base, while a thumbnail alone on its line takes the
 *     whole of it. That asymmetry is what keeps the 116px and 144px cells looking unchanged;
 *   - `flex-shrink: 1` on both is the no-overflow guarantee: a line narrower than an item's own
 *     basis shrinks that item instead of spilling out of the card, so the narrowest supported width
 *     never gains a horizontal scrollbar. `min-w-0` is the other half — a flex item defaults to
 *     `min-width: auto` and refuses to shrink below its content, and `truncate` cannot save a box
 *     that has already grown.
 *
 * `min-w-0` RUNS THE WHOLE WAY DOWN — root, thumbnail, text column, and every row inside the text
 * column — and none of those is decoration. Each one is a flex or grid ITEM, and any item left out
 * re-exports its content's intrinsic width to the box above it, all the way up to the grid track.
 * Both ends of the chain were measured failing:
 *
 *   - without it on the ROOT, one unbreakable filename sized this card at 425px inside a 311px
 *     `grid-cols-3` track and it overhung its neighbour by 118 × 90px. The portrait tile got that
 *     protection free — its text sat in a `min-w-0` GRID item, and a grid item with a definite
 *     `min-width` contributes exactly that to track sizing — and a flex root does not inherit it;
 *   - without it on the two ROWS inside the text column, the document's scrollWidth was 493px
 *     against a 320px viewport. The offender there is not the filename but the status line's
 *     `truncate` span: `white-space: nowrap` makes a box's minimum contribution the WHOLE
 *     sentence, and "Upload failed — Object storage upload failed: network error" is a wide
 *     sentence. `truncate` clips what is painted; it does not make the box narrow.
 *
 * Measured after: `scrollWidth === innerWidth` and zero card-to-card intersections at 320, 360,
 * 390, 640, 768, 1024, 1280 and 1536px, with a failed card, an uploading card and a finished card
 * all on screen at once.
 *
 * ── THE REMOVE CONTROL IS IN THE FLOW NOW, AND THAT IS THE POINT ────────────────────────────────
 *
 * Discard/Remove was `absolute right-1.5 top-1.5 z-10`: a black disc painted on top of the
 * photograph it deletes, covering the corner of every thumbnail in the grid. The card's own
 * `relative` contained it, so it was never the reported bug — but it is a control drawn over
 * content, which is that bug at card scale, and in a landscape card it would have landed on the
 * filename instead of the picture. In the flow it cannot cover anything at any width, it keeps its
 * accessible name, and the card no longer needs `relative`: nothing inside this box is positioned
 * any more except the maximise chip, which is contained by the thumbnail's own `relative` and
 * clipped by its `overflow-hidden`, so the card cannot paint outside its own rectangle at all.
 *
 * The card is deliberately NOT `overflow-hidden`. The global focus ring is an `outline` at
 * `outline-offset: 2px` drawn OUTSIDE the border box, and the remove and Retry buttons sit flush
 * inside the card's `p-2` — clipping the card would eat both rings. Same rule as the guide's step
 * card.
 */
export function MediaPreviewTile({
  item,
  onOpen,
  action,
  onRemove,
  removeLabel = "Discard",
  progress = null,
  failed = false,
  statusLabel = null,
  onRetry
}: {
  item: PreviewMedia;
  onOpen: () => void;
  action?: ReactNode;
  onRemove?: () => void;
  removeLabel?: string;
  /** 0..1 while this file is being pre-uploaded; null when there is no transfer to show. */
  progress?: number | null;
  failed?: boolean;
  statusLabel?: string | null;
  /** Offered next to the status line when a pre-upload failed. */
  onRetry?: () => void;
}) {
  const kind = resolvePreviewKind(item);
  // The URL a media row carries can EXPIRE (the API signs read URLs once `MEDIA_PRESIGNED_READS` is
  // on), and a tile is exactly where a stale one shows up: a gallery sits in a form for as long as
  // the designer is typing. `useMediaUrl` re-reads the row once and never more than once — read its
  // header before adding a second retry anywhere near this file.
  const { src, onError } = useMediaUrl(item);
  const percent = progress === null ? null : Math.round(Math.min(1, Math.max(0, progress)) * 100);
  return (
    // The WHOLE card opens the lightbox; the remove button and caller-provided actions stop
    // propagation so they never also open the preview.
    <div
      className="flex min-w-0 cursor-pointer flex-wrap items-start gap-2 rounded-md border border-line-200 bg-field-50 p-2 transition hover:border-purple-300"
      onClick={onOpen}
    >
      <button
        type="button"
        className="relative grid aspect-[4/3] min-w-0 flex-[1_1_6rem] place-items-center overflow-hidden rounded-md bg-field-100 text-left text-ink-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-field-600"
        onClick={(event) => {
          // The outer card handles the open; keep the button for keyboard access without firing twice.
          event.stopPropagation();
          onOpen();
        }}
        aria-label={`Open preview for ${item.name}`}
      >
        {kind === "IMAGE" && src ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={src} onError={onError} alt={item.caption || item.name} className="h-full w-full object-cover" loading="lazy" />
        ) : kind === "VIDEO" && src ? (
          <video src={src} onError={onError} className="h-full w-full object-cover" muted playsInline />
        ) : kind === "AUDIO" ? (
          <div className="grid gap-2 text-center">
            <div className="mx-auto rounded-full bg-card p-3 text-field-700 shadow-sm">{iconForType(kind)}</div>
            <span className="text-xs font-semibold">Audio clip</span>
          </div>
        ) : (
          <div className="grid gap-2 text-center">
            <div className="mx-auto rounded-full bg-card p-3 text-field-700 shadow-sm">{iconForType(kind)}</div>
            <span className="text-xs font-semibold">{kind === "PDF" ? "PDF document" : "Document"}</span>
          </div>
        )}
        <span className="absolute bottom-2 right-2 rounded-full bg-card/95 p-1 text-ink shadow-sm">
          <Maximize2 className="h-3.5 w-3.5" aria-hidden />
        </span>
      </button>
      <div className="grid min-w-0 flex-[999_1_8rem] gap-2">
        <div className="flex min-w-0 items-start gap-2">
          <div className="min-w-0 flex-1">
            {/*
              `line-clamp-2 break-all`, and both halves of that were measured rather than guessed.

              TWO LINES because the landscape card gives the name a narrower column than the portrait
              tile did, and one ellipsised line of "IMG_20260812_ravi_bagru_indigo…" is every file in
              a batch reading as the same file.

              `break-all` AND NOT `break-words`, which is what this was first written with.
              `overflow-wrap: break-word` stops an over-long word being PAINTED outside its box; it
              does not change the box's MIN-CONTENT width, and min-content is what sizes a grid
              track. Measured against a real underscored camera filename — underscores are not line
              break opportunities, so the whole name is one unbreakable word — this column reported a
              371px min-content and the card a 425px one, which at 320px gave the document a
              horizontal scrollbar (scrollWidth 562 against innerWidth 320) and in the fixed
              `grid-cols-3` track at 1536px made each card overhang the next by 118 × 90px. That is
              the reported overlap, rebuilt inside the card that is supposed to be the cure for it.
              `word-break: break-all` puts min-content at one character, so the name wraps instead.
              `title` still carries the whole string, unbroken, for anyone who needs to read it.
            */}
            <div className="line-clamp-2 break-all text-sm font-medium text-ink" title={item.name}>
              {item.name}
            </div>
            <div className="truncate text-xs text-ink-muted">{mediaLabel(item)}</div>
          </div>
          {onRemove ? (
            <button
              type="button"
              onClick={(event) => {
                event.stopPropagation();
                onRemove();
              }}
              aria-label={`${removeLabel} ${item.name}`}
              title={`${removeLabel} ${item.name}`}
              className="grid h-7 w-7 shrink-0 place-items-center rounded-full border border-line-200 bg-card text-ink-muted transition hover:border-red-300 hover:bg-red-50 hover:text-red-700"
            >
              <X className="h-4 w-4" aria-hidden />
            </button>
          ) : null}
        </div>
        {percent !== null && !failed ? (
          <div className="h-1.5 overflow-hidden rounded-full bg-field-200" aria-hidden>
            <div className="h-full rounded-full bg-field-600 transition-all" style={{ width: `${percent}%` }} />
          </div>
        ) : null}
        {statusLabel ? (
          <div className="flex min-w-0 items-center justify-between gap-2">
            <span className={`truncate text-xs ${failed ? "text-error-600" : "text-ink-muted"}`}>{statusLabel}</span>
            {failed && onRetry ? (
              <button
                type="button"
                className="shrink-0 rounded-sm border border-line-200 bg-card px-2 py-0.5 text-xs font-semibold text-field-600 transition hover:bg-field-50"
                onClick={(event) => {
                  event.stopPropagation();
                  onRetry();
                }}
              >
                Retry
              </button>
            ) : null}
          </div>
        ) : null}
        {action ? (
          <div className="min-w-0" onClick={(event) => event.stopPropagation()}>
            {action}
          </div>
        ) : null}
      </div>
    </div>
  );
}

/**
 * Force a download of the (usually cross-origin S3) file to the user's device. We fetch it as a blob
 * so the browser saves rather than navigates; if CORS blocks the fetch we fall back to a download
 * anchor, and finally to opening the URL in a new tab.
 */
async function saveToDevice(url: string, name: string) {
  try {
    const response = await fetch(url);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const blob = await response.blob();
    const objectUrl = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = objectUrl;
    anchor.download = name || "media";
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(objectUrl);
  } catch {
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = name || "media";
    anchor.target = "_blank";
    anchor.rel = "noreferrer";
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  }
}

export function MediaLightbox({ item, onClose }: { item: PreviewMedia; onClose: () => void }) {
  // Close on backdrop click only when the press ALSO started on the backdrop, so a drag that
  // begins inside the panel (text selection, seek-bar scrubbing) and ends outside never closes.
  const downOnBackdrop = useRef(false);
  const panelRef = useRef<HTMLDivElement | null>(null);
  const kind = resolvePreviewKind(item);
  // ONE URL FOR THE WHOLE DIALOG — the viewer, Save and Open all point at the same string, so a
  // refreshed signature reaches the download controls too. Handing `item.url` to Save while the
  // player used a refreshed one is the shape of "the picture is there and the download 403s".
  const { src, onError } = useMediaUrl(item);

  /**
   * The three things `aria-modal="true"` PROMISES, none of which this dialog was keeping.
   *
   * WHO WAS BLOCKED. A researcher opening a photo from the keyboard — Enter on a preview tile — got
   * a modal that never took their focus. It stayed on the tile UNDERNEATH the black overlay, so the
   * first Tab walked them into the page behind a dialog they had just been told was inert: content
   * a screen reader will not announce and a sighted keyboard user cannot see. Measured before this
   * fix, the tab order left the dialog after three stops and Escape dropped focus on an unrelated
   * text box halfway up the form. Reaching "Close preview" meant tabbing blind through the whole
   * record. Escape closing the dialog was the only part that worked.
   *
   * The protocol is the one `DynamicIslandNav`'s sheet already implements, and is kept identical on
   * purpose — two modal surfaces that behave differently are their own accessibility problem:
   *   1. remember whatever had focus, and hand it back on close, whichever way the dialog was shut;
   *   2. move focus INTO the dialog on open;
   *   3. wrap Tab and Shift+Tab at both ends so focus cannot leave.
   *
   * Focus lands on the PANEL, not on the first button. The panel carries the dialog's accessible
   * name, so a reader hears "Preview loom-warping.jpg, dialog" instead of "Save, button" — and the
   * first control here downloads a file, which is not a thing to leave one stray Enter away.
   */
  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    const panel = panelRef.current;
    panel?.focus();

    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") {
        onClose();
        return;
      }
      if (event.key !== "Tab" || !panel) return;
      const stops = Array.from(
        panel.querySelectorAll<HTMLElement>('a[href], button:not([disabled]), input:not([disabled]), video[controls], [tabindex]:not([tabindex="-1"])')
      ).filter((node) => node.offsetParent !== null);
      // Nothing to land on (an image-only preview with every action hidden): keep focus on the
      // panel rather than letting Tab escape to the page behind.
      if (stops.length === 0) {
        event.preventDefault();
        panel.focus();
        return;
      }
      const first = stops[0];
      const last = stops[stops.length - 1];
      const focused = document.activeElement as HTMLElement | null;
      const outside = !focused || !panel.contains(focused);
      if (event.shiftKey && (outside || focused === first || focused === panel)) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && (outside || focused === last)) {
        event.preventDefault();
        first.focus();
      }
    }

    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("keydown", onKey);
      // Restore on UNMOUNT, so every route out of the dialog — Escape, the close button, a backdrop
      // click, the parent clearing its selection — returns the reader to the tile they opened.
      // `isConnected` guards the case where the opener itself was removed while the dialog was up.
      if (opener?.isConnected) opener.focus();
    };
  }, [onClose]);

  return (
    <div
      /*
        THE DIALOG RUNG, NOT THE ISLAND'S.

        This was `z-50`, the island's own rung, and it lost to the island twice over. Every call
        site renders this component inside `AppShell`'s `<main>`, which used to carry `z-10` and was
        therefore a STACKING CONTEXT: nothing inside it could out-paint anything outside it,
        whatever z-index it declared, so a fixed full-screen overlay sat UNDER a navigation pill
        that still took clicks. `main` has given its z-index up (see the note there), but a tie on
        50 would then be settled by source order alone, which is not a thing a modal should depend
        on. The pill lit and clickable over a surface that has just declared `aria-modal="true"` is
        the defect: `BackButton`'s leave interception guards the round arrow, not the island's
        links, so clicking one navigated away from an unsaved record form without a word. 100 is the
        app's dialog layer — `FieldDialog`'s default — and that is what this is.
      */
      className="fixed inset-0 z-[100] grid place-items-center bg-black/70 p-4"
      role="dialog"
      aria-modal="true"
      aria-label={`Preview ${item.name}`}
      onMouseDown={(event) => {
        downOnBackdrop.current = event.target === event.currentTarget;
      }}
      onClick={(event) => {
        if (downOnBackdrop.current && event.target === event.currentTarget) onClose();
      }}
    >
      <div
        ref={panelRef}
        // -1 so it can be focused programmatically on open without becoming a Tab stop of its own.
        tabIndex={-1}
        className="grid max-h-[92vh] w-full max-w-5xl gap-3 overflow-hidden rounded-lg bg-field-50 p-4 shadow-2xl focus:outline-none"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h2 className="truncate font-display font-bold text-2xl text-ink">{item.caption || item.name}</h2>
            <p className="text-sm text-ink-muted">{mediaLabel(item)}</p>
          </div>
          <div className="flex items-center gap-2">
            {src ? (
              <button type="button" className="field-button-secondary" onClick={() => saveToDevice(src, item.name)}>
                <Download className="h-4 w-4" aria-hidden />
                Save
              </button>
            ) : null}
            {src ? (
              <a className="field-button-secondary" href={src} target="_blank" rel="noreferrer">
                <ExternalLink className="h-4 w-4" aria-hidden />
                Open
              </a>
            ) : null}
            <button type="button" className="field-button-secondary" onClick={onClose} aria-label="Close preview">
              <X className="h-4 w-4" aria-hidden />
            </button>
          </div>
        </div>
        <div className="grid max-h-[74vh] place-items-center overflow-auto rounded-md bg-card p-3">
          {kind === "IMAGE" && src ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={src} onError={onError} alt={item.caption || item.name} className="max-h-[70vh] max-w-full rounded-md object-contain" />
          ) : kind === "VIDEO" && src ? (
            <video src={src} onError={onError} controls className="max-h-[70vh] w-full rounded-md bg-black" />
          ) : kind === "AUDIO" && src ? (
            // NO `onError` HERE, AND IT IS NOT AN OVERSIGHT. `AudioPlayer` owns its own `<audio>`
            // element and exposes no error hook; adding one would be a prop threaded through a
            // component this change has no business editing. The tile above the dialog carries the
            // retry for the same file, so a clip whose signature expired is refreshed by the
            // gallery it was opened from — through the SAME module ledger, which is what stops the
            // two paths retrying independently. Named in the handoff notes.
            <AudioPlayer src={src} className="w-full" />
          ) : kind === "PDF" && src ? (
            <iframe src={src} onError={onError} title={item.name} className="h-[70vh] w-full rounded-md border border-line-200" />
          ) : src ? (
            <div className="grid w-full max-w-md justify-items-center gap-3 rounded-md border border-line-200 bg-field-50 p-6 text-center">
              <div className="rounded-full bg-card p-3 text-field-700 shadow-sm">{iconForType(kind)}</div>
              <div className="min-w-0 w-full">
                <div className="truncate text-sm font-medium text-ink" title={item.name}>{item.name}</div>
                <div className="text-xs text-ink-muted">{mediaLabel(item)}</div>
              </div>
              <p className="text-sm text-ink-muted">This file type cannot be rendered inline — download it to view.</p>
              <div className="flex flex-wrap justify-center gap-2">
                <button type="button" className="field-button" onClick={() => saveToDevice(src, item.name)}>
                  <Download className="h-4 w-4" aria-hidden />
                  Download file
                </button>
                <a className="field-button-secondary" href={src} target="_blank" rel="noreferrer">
                  <ExternalLink className="h-4 w-4" aria-hidden />
                  Open in new tab
                </a>
              </div>
            </div>
          ) : (
            <div className="grid gap-3 text-center text-ink-muted">
              {iconForType(kind)}
              <p>No preview URL is available yet.</p>
            </div>
          )}
        </div>
        <LightboxTranscript item={item} />
      </div>
    </div>
  );
}
