"use client";

import { useEffect, useState } from "react";
import { Download, FileText, FileWarning } from "lucide-react";

import { ModelViewer } from "@/components/media/ModelViewer";
import { fetchMediaRow } from "@/lib/mediaRows";
import { modelFormatOf } from "@/lib/modelFormats";
import type { MediaFile } from "@/lib/types";

const PDF_MIME = "application/pdf";

function isRenderablePdf(file: MediaFile): boolean {
  const mime = (file.mimeType || "").trim().toLowerCase();
  if (mime === PDF_MIME) return true;
  if (mime && mime !== "application/octet-stream") return false;
  return (file.originalFilename || "").trim().toLowerCase().endsWith(".pdf");
}

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

function Frame({ children, className }: { children: React.ReactNode; className: string }) {
  return (
    <div className={`grid place-items-center gap-1 rounded-md border border-line-200 bg-surface-50 p-4 text-center text-xs leading-5 text-ink-500 ${className}`}>
      {children}
    </div>
  );
}

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
  noun,
  className = "h-128"
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
    fetchMediaRow(mediaId)
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

  if (!file.url) {
    return (
      <Frame className="min-h-24">
        <FileWarning className="h-4 w-4" aria-hidden />
        {file.originalFilename} is stored, but this account may not open the file itself.
      </Frame>
    );
  }

  const modelFormat = modelFormatOf(file.originalFilename, file.mimeType);
  if (modelFormat) {
    return (
      <div className="grid gap-2">
        <ModelViewer
          url={file.url}
          format={modelFormat}
          fileName={file.originalFilename}
          sizeLabel={readableSize(file.sizeBytes) || undefined}
          className={className}
        />
        <FileRow file={file} />
      </div>
    );
  }

  if (!isRenderablePdf(file)) {
    return (
      <div className="grid gap-2">
        <FileRow file={file} />
        <p className="text-xs leading-5 text-ink-500">
          Stored and downloadable. A PDF or a 3D model is shown inside the app; this one opens in
          whatever program handles it on your device.
        </p>
      </div>
    );
  }

  return (
    <div className="grid gap-2">
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
