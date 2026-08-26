"use client";

/**
 * A stored document, resolved from its media id and RENDERED where the browser can render it.
 *
 * Two call sites asked for the same thing on 2026-08-25 and they are the reason this is one
 * component rather than two: the Designer Page must show the designer's uploaded CV, and the market
 * survey stage must show the survey document. The instruction for both is the same and so is the
 * exception in it — *"If the uploaded document is a PDF, it should be rendered/previewable within
 * the application. Rendering is not mandatory for non-PDF document formats."*
 *
 * ── WHY A NATIVE EMBED AND NOT A PDF LIBRARY ────────────────────────────────────────────────────
 *
 * `package.json` has no PDF renderer and this adds none. pdf.js is ~350 KB of worker plus a font
 * bundle, on pages field designers load over a village connection, to reproduce a viewer every
 * browser already ships — with its own text search, its own zoom, its own print and its own
 * accessibility tree. `<object>` hands the file to that viewer. The cost of the choice is stated
 * rather than hidden: an embedded PDF is the BROWSER's viewer, so it looks different on Chrome and
 * on Firefox, and on a phone many browsers refuse to embed at all and offer a download instead.
 * That last case is why the fallback below is real content and not an apology — see `<object>`'s
 * children, which are what a browser shows when it declines the embed.
 *
 * ── WHY THE NON-PDF CASE IS A CARD AND NOT AN ERROR ─────────────────────────────────────────────
 *
 * A .docx market survey is a perfectly good upload. It is simply not renderable without shipping a
 * word-processor, so what this draws for it is the thing a reader can actually act on: the filename,
 * the size, and a way to open it. Calling that "unsupported" would read as a rejected upload, and
 * the file is stored, resolvable and openable either way.
 *
 * ── WHAT A REPORT DOES WITH THIS FILE, BECAUSE THIS COMMENT USED TO GET IT WRONG ────────────────
 *
 * The sentence above ended "…and the file is stored and will reach the report's annexures either
 * way", and NO BRANCH OF THIS CODEBASE PRODUCES THAT. `report_builder._image_sources` skips every
 * spec whose type is not IMAGE or IMAGE_LIST, and `_render_media_annexure` gathers exclusively
 * through `_images` — so a FILE field cannot reach an annexure by any path, and neither can the
 * profile's CV column, which is copied into the FILE field `designerCv`. What a report really does
 * is NAME the attachment (`format_value` prints "1 document attached" under the field's own label)
 * and warn beside the download that the bytes are not inside it — `build_report`'s "attached
 * file(s) are named in this report but the files themselves are not inside it … send them
 * alongside it", from `ReportBuilder.attachments_named_but_not_carried`.
 *
 * The defect a comment like that one invites is not a wrong comment: it is the copy written from
 * it. The registry's help text on `designerCv` promised an annexure in as many words until
 * 2026-08-26, and a designer who reads that submits the ministry's copy believing their CV
 * travelled inside it. So this component promises a PREVIEW and an OPEN, which is all it can
 * deliver, and says nothing about reports at all — the one surface that may speak for a report is
 * the report's own warnings, beside the file it is warning about.
 *
 * ── THE THREE STATES THAT ARE NORMAL, COPIED FROM `StoredMediaImage` ON PURPOSE ─────────────────
 *
 * This is the document twin of `components/designers/StoredMediaImage.tsx` and it repeats that
 * file's resolution contract deliberately, because the failures are the same ones and a second
 * reading of them would be a second answer:
 *
 *   * the lookup fails — the row was deleted under the profile, or the reader is not entitled;
 *   * the row arrives with NO `url`, because `MediaFile.url` is gated server-side at the encoder.
 *     That is an entitlement ANSWER, not an error, and it is worded differently from the above
 *     because it needs a different action from whoever is reading;
 *   * the id is absent entirely, which is the ordinary state of a field nobody has filled in.
 *
 * None of the three may render as a broken frame, and the stored id is never cleared by any of
 * them: dropping it would silently rewrite somebody's record on a page they opened to look at.
 */

import { useEffect, useState } from "react";
import { Download, FileText, FileWarning } from "lucide-react";

import { apiFetch } from "@/lib/api";
import type { MediaFile } from "@/lib/types";

/**
 * The one mime type this component embeds.
 *
 * A LITERAL AND NOT A PREFIX TEST. `application/pdf` is the type; `application/x-pdf` and
 * `application/acrobat` are historical spellings no upload path in this repository produces
 * (`media.py` stores what the browser reported, and every browser reports the canonical one), and
 * matching loosely on "pdf" anywhere in the string would embed `application/vnd.pdf-is-not-this`.
 * The filename is checked as well, and that is not belt-and-braces: a document uploaded from a
 * handset whose content resolver answered `application/octet-stream` is a real and common case, and
 * refusing to render a file plainly called `survey.pdf` because of it would be the component
 * failing on the evidence a reader can see.
 */
const PDF_MIME = "application/pdf";

function isRenderablePdf(file: MediaFile): boolean {
  const mime = (file.mimeType || "").trim().toLowerCase();
  if (mime === PDF_MIME) return true;
  if (mime && mime !== "application/octet-stream") return false;
  return (file.originalFilename || "").trim().toLowerCase().endsWith(".pdf");
}

/** Bytes as something a person reads. `sizeBytes` is a Prisma BigInt and arrives as a string. */
function readableSize(raw: number | string | null | undefined): string {
  const bytes = typeof raw === "string" ? Number(raw) : raw;
  if (!Number.isFinite(bytes) || (bytes as number) <= 0) return "";
  const units = ["B", "KB", "MB", "GB"];
  let value = bytes as number;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value >= 10 || unit === 0 ? Math.round(value) : value.toFixed(1)} ${units[unit]}`;
}

type Resolution = { state: "loading" } | { state: "missing" } | { state: "ready"; file: MediaFile };

/** The shared frame, so all five states have the same box and the page does not jump between them. */
function Frame({ children, className }: { children: React.ReactNode; className: string }) {
  return (
    <div className={`grid place-items-center gap-1 rounded-md border border-line-200 bg-surface-50 p-4 text-center text-xs leading-5 text-ink-500 ${className}`}>
      {children}
    </div>
  );
}

/**
 * A row naming the file with a way to open it. Used both as the non-PDF card and as the caption
 * under an embedded PDF, because a reader who cannot use the embed still needs the filename.
 */
function FileRow({ file }: { file: MediaFile }) {
  const size = readableSize(file.sizeBytes);
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-md border border-line-200 bg-card px-3 py-2">
      <FileText className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
      <span className="min-w-0 flex-1 truncate text-sm text-ink-900">{file.originalFilename}</span>
      {size ? <span className="shrink-0 text-xs text-ink-500">{size}</span> : null}
      {file.url ? (
        <a
          className="field-button-secondary shrink-0"
          href={file.url}
          target="_blank"
          rel="noopener noreferrer"
          // NOT `download`. The viewer sandbox blocks a page-initiated download, and more
          // importantly a .docx opened in a new tab is what the reader asked for on a desktop with
          // a word processor. The browser decides; this only says where the bytes are.
        >
          <Download className="h-4 w-4" aria-hidden />
          Open
        </a>
      ) : null}
    </div>
  );
}

export function DocumentPreview({
  mediaId,
  /**
   * What this document IS, in the reader's words — "CV", "market survey". Used in every sentence
   * this component writes, so an empty slot says "No CV on file" rather than "No file".
   */
  noun,
  /**
   * Height of the embedded viewer. A CV is read; a survey is skimmed. Kept at the call site for
   * the same reason `StoredMediaImage` keeps its frame there — a portrait and a signature are not
   * the same shape, and neither are these.
   */
  className = "h-[32rem]"
}: {
  mediaId: string | null | undefined;
  noun: string;
  className?: string;
}) {
  const [resolution, setResolution] = useState<Resolution>({ state: "loading" });

  useEffect(() => {
    if (!mediaId) return;
    let cancelled = false;
    setResolution({ state: "loading" });
    apiFetch<MediaFile>(`/media/${mediaId}`)
      .then((file) => {
        if (!cancelled) setResolution({ state: "ready", file });
      })
      .catch(() => {
        if (!cancelled) setResolution({ state: "missing" });
      });
    return () => {
      cancelled = true;
    };
  }, [mediaId]);

  if (!mediaId) {
    return (
      <Frame className="min-h-24">
        <FileText className="h-4 w-4" aria-hidden />
        No {noun} on file.
      </Frame>
    );
  }

  if (resolution.state === "loading") {
    return <Frame className="min-h-24">Loading the {noun}…</Frame>;
  }

  if (resolution.state === "missing") {
    return (
      <Frame className="min-h-24">
        <FileWarning className="h-4 w-4" aria-hidden />
        This {noun} is no longer readable from here.
      </Frame>
    );
  }

  const { file } = resolution;

  // STORED, BUT NOT READABLE BY THIS ACCOUNT. `url` absent is the encoder's entitlement answer, so
  // the filename is named and no viewer is drawn — there is nothing to point one at.
  if (!file.url) {
    return (
      <Frame className="min-h-24">
        <FileWarning className="h-4 w-4" aria-hidden />
        {file.originalFilename} is stored, but this account may not open the file itself.
      </Frame>
    );
  }

  if (!isRenderablePdf(file)) {
    return (
      <div className="grid gap-2">
        <FileRow file={file} />
        <p className="text-xs leading-5 text-ink-500">
          Stored and downloadable. Only a PDF can be shown inside the app, so this one opens in
          whatever program handles it on your device.
        </p>
      </div>
    );
  }

  return (
    <div className="grid gap-2">
      {/*
        `<object>` rather than `<iframe>`: its children are the DECLARED fallback a browser renders
        when it will not or cannot display the type, which is exactly the mobile case above. An
        iframe's fallback is a blank rectangle with no way to detect it.

        `aria-label` and not `title`: the embed is a document, and a screen reader announcing the
        filename is more use than announcing "PDF viewer". The FileRow below carries the same name
        visibly, which is what a reader who cannot use the embed reads instead.
      */}
      <object
        data={file.url}
        type={PDF_MIME}
        aria-label={`${noun}: ${file.originalFilename}`}
        className={`w-full rounded-md border border-line-200 bg-card ${className}`}
      >
        <Frame className="min-h-24">
          <FileText className="h-4 w-4" aria-hidden />
          This browser will not display the {noun} inline. Use Open below to read it.
        </Frame>
      </object>
      <FileRow file={file} />
    </div>
  );
}
