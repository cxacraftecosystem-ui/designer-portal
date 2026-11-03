"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Download, Maximize2, Printer, QrCode } from "lucide-react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { WorkshopCodePrintout } from "@/components/designworkshop/WorkshopCodeSheet";
import { encodeQr, qrSvgPath, QrEncodeError, type QrSymbol } from "@/lib/qrEncode";
import {
  encodeWorkshopCode,
  formatWorkshopCodeForPrint,
  workshopRecordTypeLabel,
  type WorkshopRecordType
} from "@/lib/workshopCodes";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { fileName as namingFileName } from "@/lib/naming";

const ECC_LEVEL = "Q" as const;

const SVG_SIZE_MM = 26;

const PNG_MODULE_PX = 12;

type Rendered =
  | { ok: true; code: string; printed: string; symbol: QrSymbol; path: string; extent: number }
  | { ok: false; message: string };

function render(recordType: WorkshopRecordType, id: string | null | undefined): Rendered {
  const encoded = encodeWorkshopCode({ recordType, id });
  if (!encoded.ok) return { ok: false, message: encoded.message };
  try {
    const symbol = encodeQr(encoded.code, ECC_LEVEL);
    const svg = qrSvgPath(symbol);
    return {
      ok: true,
      code: encoded.code,
      printed: formatWorkshopCodeForPrint(encoded.code),
      symbol,
      path: svg.path,
      extent: svg.extent
    };
  } catch (error) {
    return { ok: false, message: error instanceof QrEncodeError ? error.message : "This code could not be drawn." };
  }
}

function svgSource(entry: Extract<Rendered, { ok: true }>): string {
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${entry.extent} ${entry.extent}" ` +
    `width="${SVG_SIZE_MM}mm" height="${SVG_SIZE_MM}mm" shape-rendering="crispEdges">` +
    `<rect width="${entry.extent}" height="${entry.extent}" fill="#ffffff"/>` +
    `<path d="${entry.path}" fill="#000000"/>` +
    `</svg>`
  );
}

function fileName(code: string, extension: string): string {
  return namingFileName({ entity: "Workshop", subject: code, what: "Record-Code", ext: extension });
}

function saveBlob(blob: Blob, name: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = name;
  anchor.rel = "noopener";
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

async function savePng(entry: Extract<Rendered, { ok: true }>): Promise<void> {
  const quiet = (entry.extent - entry.symbol.size) / 2;
  const side = entry.extent * PNG_MODULE_PX;
  const canvas = document.createElement("canvas");
  canvas.width = side;
  canvas.height = side;
  const context = canvas.getContext("2d");
  if (!context) throw new Error("Couldn't make a picture. Download the SVG.");

  context.fillStyle = "#ffffff";
  context.fillRect(0, 0, side, side);
  context.fillStyle = "#000000";
  for (let row = 0; row < entry.symbol.size; row++) {
    for (let column = 0; column < entry.symbol.size; column++) {
      if (!entry.symbol.matrix[row][column]) continue;
      context.fillRect((column + quiet) * PNG_MODULE_PX, (row + quiet) * PNG_MODULE_PX, PNG_MODULE_PX, PNG_MODULE_PX);
    }
  }

  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/png"));
  if (!blob) throw new Error("Couldn't save a picture. Download the SVG.");
  saveBlob(blob, fileName(entry.code, "png"));
}

function CodeSymbol({ entry, label, className }: { entry: Extract<Rendered, { ok: true }>; label: string; className?: string }) {
  return (
    <svg
      viewBox={`0 0 ${entry.extent} ${entry.extent}`}
      xmlns="http://www.w3.org/2000/svg"
      shapeRendering="crispEdges"
      role="img"
      aria-label={label}
      data-testid="record-code-qr"
      data-code={entry.code}
      className={className}
    >
      <rect width={entry.extent} height={entry.extent} fill="#ffffff" />
      <path d={entry.path} fill="#000000" />
    </svg>
  );
}

function DownloadButtons({
  entry,
  onProblem,
  compact
}: {
  entry: Extract<Rendered, { ok: true }>;
  onProblem: (message: string) => void;
  compact: boolean;
}) {
  const [saving, setSaving] = useState(false);

  return (
    <>
      <button
        type="button"
        className="field-button"
        disabled={saving}
        onClick={async () => {
          setSaving(true);
          try {
            await savePng(entry);
          } catch (error) {
            onProblem(error instanceof Error ? error.message : "The code could not be saved as a picture.");
          } finally {
            setSaving(false);
          }
        }}
      >
        <Download className="h-4 w-4" aria-hidden />
        {compact ? "Download" : saving ? "Saving…" : "Download PNG"}
      </button>
      <button
        type="button"
        className="field-button-secondary"
        onClick={() => saveBlob(new Blob([svgSource(entry)], { type: "image/svg+xml" }), fileName(entry.code, "svg"))}
      >
        <Download className="h-4 w-4" aria-hidden />
        SVG
      </button>
    </>
  );
}

export function RecordCodeCard({
  recordType,
  id,
  title,
  className
}: {
  recordType: WorkshopRecordType;
  id: string | null | undefined;
  title?: string;
  className?: string;
}) {
  const entry = useMemo(() => render(recordType, id), [recordType, id]);
  const [expanded, setExpanded] = useState(false);
  const [printing, setPrinting] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const copyTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => () => {
    if (copyTimer.current !== null) clearTimeout(copyTimer.current);
  }, []);

  const typeLabel = workshopRecordTypeLabel(recordType);
  const heading = title?.trim() || typeLabel;
  const symbolLabel = `Code for ${heading}`;

  const startPrint = useCallback(() => {
    if (!document.getElementById("main-content")) {
      setProblem(
        "Can't print from here. Download the PNG or SVG and print that."
      );
      return;
    }
    setProblem(null);
    setPrinting(true);
  }, []);

  const finishPrint = useCallback(() => setPrinting(false), []);

  const copy = useCallback(async (code: string) => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      if (copyTimer.current !== null) clearTimeout(copyTimer.current);
      copyTimer.current = setTimeout(() => setCopied(false), 2000);
    } catch {
      setProblem("Couldn't copy. Select the code above and copy it.");
    }
  }, []);

  return (
    <section className={`panel p-4 ${className ?? ""}`} aria-labelledby={`record-code-${recordType}`}>
      <div className="mb-3 flex items-start gap-3">
        <span className="mt-0.5 grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-field-200 text-field-600 dark:text-purple-300" aria-hidden>
          <QrCode className="h-4 w-4" />
        </span>
        <div className="min-w-0">
          <h2 id={`record-code-${recordType}`} className="display-title text-base">
            {typeLabel} code
          </h2>
          <p className="mt-1 text-sm leading-6 text-ink-muted">
            Scan to open this {typeLabel.toLowerCase()}. The code holds no personal details.
          </p>
        </div>
      </div>

      {entry.ok ? (
        <div className="flex flex-wrap items-start gap-4">
          <button
            type="button"
            className="rounded-md border border-line-200 bg-white p-2 transition hover:border-purple-300"
            onClick={() => setExpanded(true)}
            aria-label={`Expand the ${typeLabel.toLowerCase()} code`}
          >
            <CodeSymbol entry={entry} label={symbolLabel} className="h-28 w-28" />
          </button>

          <div className="min-w-0 flex-1">
            <p className="break-all font-mono text-xs leading-5 text-ink-700" data-testid="record-code-text">
              {entry.printed}
            </p>
            <p className="mt-1 text-xs leading-5 text-ink-muted">
              The last four characters are a check.
            </p>
            <div className="mt-3 flex flex-wrap gap-2">
              <ActionGroup className="max-sm:w-full"><button type="button" className="field-button-secondary" onClick={() => setExpanded(true)}>
                <Maximize2 className="h-4 w-4" aria-hidden />
                Expand
              </button>
              <button type="button" className="field-button-secondary" onClick={startPrint} disabled={printing}>
                <Printer className="h-4 w-4" aria-hidden />
                {printing ? "Printing…" : "Print"}
              </button></ActionGroup>
              <DownloadButtons entry={entry} onProblem={setProblem} compact />
              <button type="button" className="field-button-secondary" onClick={() => copy(entry.code)}>
                {copied ? "Copied" : "Copy code"}
              </button>
            </div>
          </div>
        </div>
      ) : (
        <p
          className="rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800"
          data-testid="record-code-refusal"
        >
          {entry.message}
        </p>
      )}

      {problem ? (
        <p className="mt-3 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700" role="status">
          {problem}
        </p>
      ) : null}

      {entry.ok && printing ? (
        <WorkshopCodePrintout
          card={{ recordType, id, title: heading, key: entry.code }}
          onFinished={finishPrint}
        />
      ) : null}

      {entry.ok ? (
        <FieldDialog
          open={expanded}
          onClose={() => setExpanded(false)}
          title={`${typeLabel} code`}
          description={heading}
          icon={<QrCode className="h-4 w-4" aria-hidden />}
          className="max-w-lg"
          footer={
            <>
              <DownloadButtons entry={entry} onProblem={setProblem} compact={false} />
              <button type="button" className="field-button-secondary" onClick={() => setExpanded(false)}>
                Close
              </button>
            </>
          }
        >
          <div className="grid justify-items-center gap-3">
            <div className="rounded-lg bg-white p-4">
              <CodeSymbol entry={entry} label={symbolLabel} className="h-64 w-64 max-w-full" />
            </div>
            <p className="break-all text-center font-mono text-sm leading-6 text-ink-700">{entry.printed}</p>
            <p className="text-center text-xs leading-5 text-ink-muted">
              Scan it, or type it. Spaces and capitals don&apos;t matter.
            </p>
          </div>
        </FieldDialog>
      ) : null}
    </section>
  );
}
