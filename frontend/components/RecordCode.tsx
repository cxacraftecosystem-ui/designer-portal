"use client";

/**
 * The QR code for one record, drawn live wherever that record is open, with an expanded view, a
 * download and a print.
 *
 * ── WHY THE CODE IS ON THE RECORD AND NOT ONLY ON A PRINT SHEET ──────────────────────────────
 *
 * The sheet in `designworkshop/WorkshopCodeSheet.tsx` prints thirty cards at once, which is the
 * right shape at the start of a workshop and the wrong one every other day. A designer holding one
 * tool wants the code for THAT tool: to stick on the tool, to hand to the officer asking which
 * record a photograph belongs to, to paste into a report. That is one code, from the screen the
 * record is already open on, and it must be reachable without printing forty of them.
 *
 * ── AND WHY PRINT STILL GOES THROUGH THAT SHEET ──────────────────────────────────────────────
 *
 * The Print control here does not draw a card of its own; it hands ONE card to the same
 * `WorkshopCodeSheet` (`WorkshopCodePrintout`). Everything a printed tag needs is decided there and
 * decided in millimetres — the 26mm QR box that keeps a module above the resolution floor of a
 * handset camera, the cut guide, the name clamp that protects the printed code — and a second
 * layout would be a second copy of those numbers, drifting quietly until a tag scanned on the
 * designer's phone and on nobody else's. Same payload, same error-correction level Q, same geometry:
 * one record has ONE symbol, whether it came off this screen or off a sheet of thirty. Two
 * different-looking symbols for one record is exactly what makes somebody distrust a scan.
 *
 * ── IT IS GENERATED IN REAL TIME AND NOTHING IS SAVED ────────────────────────────────────────
 *
 * There is no stored PNG, no cached SVG and no column on the record. The payload is a pure function
 * of the record's type and its id (`lib/workshopCodes.ts`), and the symbol is a pure function of the
 * payload (`lib/qrEncode.ts`), so this component recomputes both every time it mounts —
 * `useMemo`'d only against re-renders, never against a cache that outlives them.
 *
 * IF YOU ARE HERE TO "OPTIMISE" THIS INTO A STORED ASSET, READ THIS FIRST. Encoding a payload and
 * scoring eight QR masks measures in the tens of microseconds; a stored image costs a column or a
 * bucket object, an invalidation rule, a migration for every record already in the repository, and
 * space on a 6 GB field handset — and it can go stale, which the function cannot. The id a code is
 * built from is immutable, so a cached image can only ever be as correct as this function and never
 * more. There is nothing to win.
 *
 * ── WHAT IT WILL NOT DO ──────────────────────────────────────────────────────────────────────
 *
 * It shows and prints no field of the record except the title its caller passes, and never an
 * identity number: `workshopCodes.ts` refuses to encode anything shaped like an Aadhaar or Pehchan
 * number before this component ever sees it, and a refusal is rendered as a sentence rather than
 * swallowed.
 * The DOWNLOADED FILE IS NAMED AFTER THE CODE, not after the record — a file called
 * `DPW1-A-CMSIK….png` can be mailed to anybody, and a file called `ram-kumar-aadhaar-card.png`
 * cannot. The code is the one string here that is opaque by construction.
 *
 * ── WHY IT DOES NOT INVERT IN DARK MODE ──────────────────────────────────────────────────────
 *
 * The symbol is black on white in every theme. A light-on-dark QR is refused outright by a large
 * share of scanners (they look for a dark symbol in a light quiet zone), so a code that politely
 * followed the theme would be a code that stopped working for exactly the designers who use dark
 * mode in a courtyard. Everything AROUND the symbol is themed as usual.
 */

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

/**
 * Error correction level Q — a quarter of the symbol recoverable.
 *
 * The same level the print sheet uses, and for the same reason: these codes end up on paper that
 * spends a fortnight in a workshop, where a thumbprint, a smear of dye or a fold across a corner is
 * the normal condition of a tag by day ten. Two surfaces drawing one record at two levels would
 * also produce two different-looking symbols for one record, which is exactly the kind of thing
 * that makes somebody doubt a scan — and since the Print control below renders THROUGH the sheet,
 * the two levels being equal is what makes the paper and the screen the same symbol rather than
 * merely similar ones. `e2e/qr-surfaces-unit.spec.ts` pins them equal.
 */
const ECC_LEVEL = "Q" as const;

/**
 * The physical size the downloaded SVG declares.
 *
 * 26mm is `WorkshopCodeSheet`'s QR box, chosen so that the largest symbol this app draws (version 4,
 * 33 modules plus an 8-module quiet zone) still clears 0.6mm per module — the resolution floor for a
 * handset camera held over a card in a courtyard. An SVG carries its own physical size, so a file
 * dropped into a report prints at the size it was designed for rather than at whatever the layout
 * program guesses.
 */
const SVG_SIZE_MM = 26;

/**
 * Pixels per module in the downloaded PNG.
 *
 * 12 puts a version 4 symbol (41 units including the quiet zone) at 492px, which prints at 26mm on
 * a 480dpi office printer and still reads on screen at a sensible size. A PNG cannot carry a
 * physical size the way the SVG can, so the only protection against a code printed too small is to
 * hand over enough pixels that scaling it down is a deliberate act.
 */
const PNG_MODULE_PX = 12;

type Rendered =
  | { ok: true; code: string; printed: string; symbol: QrSymbol; path: string; extent: number }
  | { ok: false; message: string };

/**
 * The payload and its symbol, or the sentence explaining why there is neither.
 *
 * A REFUSAL IS RENDERED, NEVER SWALLOWED. A record whose id cannot be encoded — one that has not
 * been saved, or a caller that handed over the wrong field — must say so where the code would have
 * been. A silently missing QR looks like a component that has not loaded, and a designer waits for
 * it.
 */
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

/**
 * The SVG source of a symbol, as a standalone file.
 *
 * The quiet zone is a drawn white rectangle rather than an absent background: a transparent SVG
 * placed on a coloured page leaves the symbol without the light border a scanner uses to find it at
 * all, and "it worked on my screen and not on the printout" is the failure that produces.
 */
function svgSource(entry: Extract<Rendered, { ok: true }>): string {
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${entry.extent} ${entry.extent}" ` +
    `width="${SVG_SIZE_MM}mm" height="${SVG_SIZE_MM}mm" shape-rendering="crispEdges">` +
    `<rect width="${entry.extent}" height="${entry.extent}" fill="#ffffff"/>` +
    `<path d="${entry.path}" fill="#000000"/>` +
    `</svg>`
  );
}

/**
 * The file name for a downloaded code.
 *
 * BUILT FROM THE CODE AND NOTHING ELSE. The record's title is not in it, and that is the point: a
 * downloaded file travels — into a mail, onto a shared drive, into a report folder — and a file name
 * is the part of a file that gets read without opening it. The code is opaque by construction (see
 * `workshopCodes.ts`), so naming the file after it cannot leak a name, a village or a craft. The
 * colons become hyphens because Windows refuses a file name containing one.
 */
function fileName(code: string, extension: string): string {
  return `${code.replace(/:/g, "-")}.${extension}`;
}

/** Hand a blob to the browser as a download, and release the object URL once it has been taken. */
function saveBlob(blob: Blob, name: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = name;
  anchor.rel = "noopener";
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  // Revoked on the next task rather than immediately: Safari reads the href asynchronously after the
  // synthetic click, and revoking in the same tick cancels the download it was asked to start.
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

/**
 * Draw the symbol into a canvas and save it as a PNG.
 *
 * DRAWN FROM THE MODULE MATRIX, not rasterised from the SVG on screen. Rasterising an `<svg>` means
 * an `Image` load, a taint check and a browser-dependent scaling of a shape whose whole meaning is
 * whether a given square is dark — half a pixel of smoothing across a module boundary is what makes
 * a camera hesitate. Filling integer rectangles cannot introduce one.
 */
async function savePng(entry: Extract<Rendered, { ok: true }>): Promise<void> {
  const quiet = (entry.extent - entry.symbol.size) / 2;
  const side = entry.extent * PNG_MODULE_PX;
  const canvas = document.createElement("canvas");
  canvas.width = side;
  canvas.height = side;
  const context = canvas.getContext("2d");
  if (!context) throw new Error("This browser could not draw the code as a picture. Download the SVG instead.");

  // The quiet zone is painted, for the reason svgSource gives: it is part of the symbol, not the
  // absence of one.
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
  if (!blob) throw new Error("This browser could not save the code as a picture. Download the SVG instead.");
  saveBlob(blob, fileName(entry.code, "png"));
}

/** The symbol itself. Black on white in every theme — see the file header. */
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

/** The two download controls, offered identically in the card and in the expanded view. */
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

/**
 * The record's code, on the record.
 *
 * `title` is only ever used to NAME the symbol for a screen reader ("Code for Ram's loom"), to head
 * the expanded view, and to head the printed card — the same one line the print sheet already gives
 * an artisan card, and for the same reason: a tag nobody can identify by eye is a tag that gets tied
 * to the wrong object. It is never encoded and never put in a file name — see the file header.
 */
export function RecordCodeCard({
  recordType,
  id,
  title,
  className
}: {
  recordType: WorkshopRecordType;
  /** The record's id. Null or empty for a record that has not been saved yet: the card says so. */
  id: string | null | undefined;
  /** The line a human reads. Optional; the record type's own word is used when it is absent. */
  title?: string;
  className?: string;
}) {
  const entry = useMemo(() => render(recordType, id), [recordType, id]);
  const [expanded, setExpanded] = useState(false);
  const [printing, setPrinting] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const copyTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // The confirmation is a timer, and a timer that outlives its component sets state on nothing.
  useEffect(() => () => {
    if (copyTimer.current !== null) clearTimeout(copyTimer.current);
  }, []);

  const typeLabel = workshopRecordTypeLabel(recordType);
  const heading = title?.trim() || typeLabel;
  const symbolLabel = `Code for ${heading}`;

  /**
   * Put one card on paper.
   *
   * The check for `<main>` is here rather than inside the printout because THIS is where there is
   * somewhere to say it. The print block un-hides a direct child of `#main-content`; with no such
   * element the sheet would be mounted, hidden, and silently never printed — a Print button that
   * does nothing, which is the worst of the three possible outcomes.
   */
  const startPrint = useCallback(() => {
    if (!document.getElementById("main-content")) {
      setProblem(
        "This screen cannot be prepared for printing. Download the code as a PNG or an SVG and print the file instead."
      );
      return;
    }
    setProblem(null);
    setPrinting(true);
  }, []);

  // Stable, because `WorkshopCodePrintout` re-runs its print effect when this identity changes.
  const finishPrint = useCallback(() => setPrinting(false), []);

  const copy = useCallback(async (code: string) => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      if (copyTimer.current !== null) clearTimeout(copyTimer.current);
      copyTimer.current = setTimeout(() => setCopied(false), 2000);
    } catch {
      // A refused clipboard is not worth an error box: the code is on screen in full, selectable,
      // and typing it back is the path it was printed for in the first place.
      setProblem("This browser did not allow the code to be copied. It is written out above — it can be selected and copied by hand.");
    }
  }, []);

  return (
    <section className={`panel p-4 ${className ?? ""}`} aria-labelledby={`record-code-${recordType}`}>
      <div className="mb-3 flex items-start gap-3">
        <span className="mt-0.5 grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-field-200 text-field-600" aria-hidden>
          <QrCode className="h-4 w-4" />
        </span>
        <div className="min-w-0">
          <h2 id={`record-code-${recordType}`} className="display-title text-base">
            {typeLabel} code
          </h2>
          <p className="mt-1 text-sm leading-6 text-ink-muted">
            Scan this to open this {typeLabel.toLowerCase()} from any device running the app. It holds a reference and a check
            digit and nothing about the record itself — no name, no place, no identity number. It is drawn fresh every time this
            screen opens and is not stored anywhere.
          </p>
        </div>
      </div>

      {entry.ok ? (
        <div className="flex flex-wrap items-start gap-4">
          {/* A button and not a decorated <img>: expanding is the commonest thing anybody does with a
              code on a phone-sized screen, and a symbol that looks tappable and is not is worse than
              one that does not. */}
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
              The last four characters are a check. Typed into the scanner, they are what stops one wrong character opening a
              different record.
            </p>
            <div className="mt-3 flex flex-wrap gap-2">
              <button type="button" className="field-button-secondary" onClick={() => setExpanded(true)}>
                <Maximize2 className="h-4 w-4" aria-hidden />
                Expand
              </button>
              {/* Offered here and NOT in the expanded dialog's footer, for the same reason Copy is
                  only here: the dialog is portalled to <body>, so it is outside the group the print
                  block hides, and printing from inside it would put a modal backdrop over the card.
                  The stylesheet drops the overlay defensively anyway, but the button belongs where
                  the page behind it is the thing being replaced. */}
              <button type="button" className="field-button-secondary" onClick={startPrint} disabled={printing}>
                <Printer className="h-4 w-4" aria-hidden />
                {printing ? "Printing…" : "Print"}
              </button>
              <DownloadButtons entry={entry} onProblem={setProblem} compact />
              <button type="button" className="field-button-secondary" onClick={() => copy(entry.code)}>
                {copied ? "Copied" : "Copy code"}
              </button>
            </div>
          </div>
        </div>
      ) : (
        /* Amber, worded, and in place of the symbol — the same treatment the print sheet gives a card
           it cannot make. See `WorkshopCodeSheet`'s header for why a refusal is shown rather than the
           code being quietly omitted. */
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

      {/* Mounted only for the length of one print: it carries the card sheet's global stylesheet,
          whose @media print block hides most of the page, and a record screen that quietly printed
          like a sheet of tags would be a surprise nobody asked for. */}
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
            {/* A fixed white plate rather than the dialog's themed surface: the quiet zone is part of
                the symbol, and in dark mode a themed panel behind a transparent SVG is a dark quiet
                zone, which is a symbol most scanners will not find. */}
            <div className="rounded-lg bg-white p-4">
              <CodeSymbol entry={entry} label={symbolLabel} className="h-64 w-64 max-w-full" />
            </div>
            <p className="break-all text-center font-mono text-sm leading-6 text-ink-700">{entry.printed}</p>
            <p className="text-center text-xs leading-5 text-ink-muted">
              Hold a camera over it, or type the characters into the scanner. Spaces and capitals do not matter.
            </p>
          </div>
        </FieldDialog>
      ) : null}
    </section>
  );
}
