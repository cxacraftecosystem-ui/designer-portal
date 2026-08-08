"use client";

/**
 * Artisan cards and prototype tags, laid out on paper at the size they will actually be cut to.
 *
 * WHY MILLIMETRES AND NOT PIXELS — the same argument `report/ReportSheet.tsx` makes, with one
 * extra edge. A layout in `px` prints at whatever scale factor the browser happens to apply, and
 * for a document that only costs a designer a reprint. Here it decides whether the thing WORKS: a
 * QR module below roughly half a millimetre is at the resolution limit of a handset camera held
 * over a card in a courtyard, so a sheet that printed 15% small would produce forty cards that
 * scan on the designer's own phone at 8cm and fail on everyone else's. Every dimension below is
 * physical, {@link QR_BOX_MM} is chosen so that the largest symbol this app draws (version 4, 33
 * modules plus an 8-module quiet zone) still clears 0.6mm per module, and the sheet prints at
 * 100% or not at all.
 *
 * WHY THE CARD FACE CARRIES A NAME AND THE QR DOES NOT. They are two different objects with two
 * different readers. The face is a name badge: the artisan holding it, and the designer looking
 * for them across a courtyard, both need to read it, and a card nobody can identify by eye is a
 * card that gets handed to the wrong person — which is the very error this feature exists to
 * remove. The QR is read by a machine and by anyone who photographs the card, so it carries an
 * opaque reference and nothing else (`lib/workshopCodes.ts` explains, and enforces, exactly what
 * "nothing else" means). What is deliberately on NEITHER is any identity number: no Aadhaar, no
 * Pehchan, no artisan card number. This component is handed a title and at most two supporting
 * lines by its caller and prints no other field, so it cannot become the place one leaks.
 *
 * WHY A REFUSAL IS PRINTED RATHER THAN THE CARD BEING DROPPED. A prototype whose row cannot be
 * encoded gets a card with the reason on it and no symbol. A sheet that silently held 29 tags
 * when the workshop has 30 is the "silent emptiness" failure this repository keeps hitting: the
 * designer cuts them up, tags 29 prototypes, and finds out on the day the report is due. A card
 * that says "this row has not been saved yet" is a card somebody can act on.
 *
 * WHY IT DOES NOT INVERT IN DARK MODE. The sheet is a depiction of paper — the same exemption
 * `ReportSheet` takes, for the same reason. Everything outside the sheet is themed as usual.
 */

import { useMemo } from "react";

import { encodeQr, qrSvgPath, QrEncodeError } from "@/lib/qrEncode";
import {
  encodeWorkshopCode,
  formatWorkshopCodeForPrint,
  type WorkshopRecordType
} from "@/lib/workshopCodes";

/** One card's worth of input. The caller supplies only what may be printed — see the header. */
export type WorkshopCodeCard = {
  recordType: WorkshopRecordType;
  /** The record's identifier, or null/empty when it does not have one yet. */
  id: string | null | undefined;
  /** The line a human reads: an artisan's name, a prototype's name. */
  title: string;
  /** At most two supporting lines — a serial number, a village, a prototype code. Never an id. */
  lines?: string[];
  /** A stable key for React. The id is not usable: an unsaved row does not have one. */
  key: string;
};

/**
 * The QR box, in millimetres.
 *
 * 26mm holds a version 4 symbol (33 modules + 4 quiet modules a side = 41 units) at 0.63mm per
 * module, which is the smallest this should ever be allowed to get. Version 4 is what an
 * offline-created prototype needs, because until its row has synced it is identified by a 36
 * character UUID client key rather than a 25 character cuid. Shrinking this box to fit more tags
 * on a page is the one change here that silently breaks the feature.
 */
const QR_BOX_MM = 26;

/**
 * Card geometry per kind, in millimetres, on a 210 × 297 A4 sheet with 10mm margins.
 *
 * The artisan card is ID-1 (85.6 × 54mm) rounded down to 85 — the size of every identity card
 * anybody in the room already owns, so it fits the lanyard pouches a workshop already buys.
 * The prototype tag is smaller because it is tied or taped to an object rather than worn.
 */
type CardGeometry = { widthMm: number; heightMm: number; columns: number };

/** ID-1, the size of every identity card anybody in the room already owns. */
const ID_CARD: CardGeometry = { widthMm: 85, heightMm: 54, columns: 2 };
/** Smaller, because a tag is tied or taped to an object rather than worn. */
const OBJECT_TAG: CardGeometry = { widthMm: 63, heightMm: 45, columns: 3 };

/**
 * A TOTAL record and not a partial one, deliberately: adding a record type to `workshopCodes.ts`
 * fails this file's typecheck until somebody has decided what a sheet of them is cut to. Every type
 * other than the artisan is tagged rather than worn, which is why they all take the tag.
 *
 * Only the artisan and the prototype are OFFERED as a sheet today — this component's one caller is
 * the workshop's Cards & tags page. Every other record type carries its code on its own record
 * screen, one at a time, where it is expanded and downloaded rather than printed forty to a page.
 */
const GEOMETRY: Record<WorkshopRecordType, CardGeometry> = {
  artisan: ID_CARD,
  craft: OBJECT_TAG,
  workshop: OBJECT_TAG,
  product: OBJECT_TAG,
  process: OBJECT_TAG,
  tool: OBJECT_TAG,
  questionnaire: OBJECT_TAG,
  media: OBJECT_TAG,
  prototype: OBJECT_TAG
};

/** The error-correction level every code is printed at, and why it is not the default L. */
const ECC_LEVEL = "Q" as const;

type Rendered =
  | { key: string; ok: true; card: WorkshopCodeCard; path: string; extent: number; code: string; printed: string }
  | { key: string; ok: false; card: WorkshopCodeCard; message: string };

function renderCard(card: WorkshopCodeCard): Rendered {
  const encoded = encodeWorkshopCode({ recordType: card.recordType, id: card.id });
  if (!encoded.ok) return { key: card.key, ok: false, card, message: encoded.message };
  try {
    // Level Q (25% of the symbol recoverable) rather than the usual L, because these are printed
    // on paper that spends a fortnight in a workshop: a thumbprint, a smear of dye or a fold
    // across a corner is the normal condition of a tag by day ten, not the exceptional one.
    const svg = qrSvgPath(encodeQr(encoded.code, ECC_LEVEL));
    return {
      key: card.key,
      ok: true,
      card,
      path: svg.path,
      extent: svg.extent,
      code: encoded.code,
      printed: formatWorkshopCodeForPrint(encoded.code)
    };
  } catch (error) {
    return {
      key: card.key,
      ok: false,
      card,
      message: error instanceof QrEncodeError ? error.message : "This code could not be drawn."
    };
  }
}

/**
 * The stylesheet. A single `<style>` element for the reason `ReportSheet` gives: the units have to
 * be physical, and `@page` and the print-only rules cannot be reached from Tailwind at all.
 *
 * Every selector is prefixed `wc-` except the print block, which has to switch off app chrome this
 * component does not own — `AppShell`'s island clearance and the navigation island itself. Printing
 * a floating navigation pill across the first row of artisan cards is the failure that block exists
 * to prevent.
 */
function sheetStyles(): string {
  return `
.wc-sheet {
  box-sizing: border-box;
  width: 210mm;
  min-height: 297mm;
  padding: 10mm;
  background: #ffffff;
  color: #111111;
  /* Named here rather than inherited: the app's Inter is a UI face, and a card is set in whatever
     the printer actually has. The stack is the same one the report sheet asks for. */
  font-family: Calibri, Carlito, "Segoe UI", Inter, sans-serif;
  display: grid;
  gap: 0;
  align-content: start;
}
.wc-card {
  box-sizing: border-box;
  /* A hairline rule IS the cut guide. Scissors need a line; a card printed with none is cut by
     eye and comes out crooked, and a crooked cut through a quiet zone stops the symbol scanning. */
  border: 0.2mm dashed #b9b9c4;
  padding: 3.5mm;
  display: flex;
  gap: 3mm;
  align-items: center;
  overflow: hidden;
  break-inside: avoid;
  page-break-inside: avoid;
}
.wc-qr { flex: 0 0 auto; width: ${QR_BOX_MM}mm; height: ${QR_BOX_MM}mm; display: block; }
.wc-qr path { fill: #000000; }
.wc-body { min-width: 0; flex: 1 1 auto; }
.wc-title {
  font-weight: 700;
  font-size: 11pt;
  line-height: 1.15;
  /* Two lines and then an ellipsis. A name that overflows a 54mm card pushes the printed code off
     the bottom edge, and the code is the half a human falls back on when the camera will not
     focus — so the name is what gets clipped, never the code. */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.wc-line { font-size: 8pt; line-height: 1.3; color: #444450; margin-top: 0.8mm; }
.wc-code {
  margin-top: 1.6mm;
  font-family: "Cascadia Mono", Consolas, "DejaVu Sans Mono", monospace;
  font-size: 6pt;
  letter-spacing: 0.02em;
  line-height: 1.25;
  color: #333340;
  word-break: break-all;
}
.wc-refusal {
  font-size: 7.5pt;
  line-height: 1.3;
  color: #92400e;
  background: #fef3c7;
  border: 0.2mm solid #f59e0b;
  padding: 1.5mm;
  border-radius: 1mm;
}
.wc-blank {
  flex: 0 0 auto;
  width: ${QR_BOX_MM}mm;
  height: ${QR_BOX_MM}mm;
  border: 0.2mm dashed #d8d8e0;
  border-radius: 1mm;
}

@media print {
  @page { size: A4 portrait; margin: 0; }
  html, body { background: #fff !important; }
  #main-content { padding: 0 !important; max-width: none !important; }
  .nav-island-frame, .nav-sheet-overlay { display: none !important; }

  /* EVERYTHING THAT IS NOT THE SHEET IS DROPPED — the page header, the scanner, the kind picker,
     the banners. They are direct children of AppShell's <main>, hidden as a group so that the next
     section anybody adds to this page does not silently start printing itself. */
  #main-content > * { display: none !important; }
  #main-content > .wc-host {
    display: block !important;
    padding: 0 !important; margin: 0 !important; border: 0 !important;
    border-radius: 0 !important; box-shadow: none !important; background: transparent !important;
  }

  .wc-sheet { break-after: page; page-break-after: always; box-shadow: none !important; }
  .wc-sheet:last-child { break-after: auto; page-break-after: auto; }

  /* Browsers drop backgrounds when printing unless told not to, and here the amber wash on a
     refusal is the only thing that distinguishes "this card could not be made" from a blank card
     somebody will assume printed badly. */
  * { -webkit-print-color-adjust: exact !important; print-color-adjust: exact !important; }
}
`;
}

/**
 * A sheet of cards, ready to print.
 *
 * `perPage` is derived from the geometry rather than passed in, so the number of cards on a page
 * and the size they are cut to cannot disagree.
 */
export function WorkshopCodeSheet({
  recordType,
  cards,
  emptyMessage
}: {
  recordType: WorkshopRecordType;
  cards: WorkshopCodeCard[];
  emptyMessage: string;
}) {
  const geometry = GEOMETRY[recordType];

  const rendered = useMemo(() => cards.map(renderCard), [cards]);

  const pages = useMemo(() => {
    const rows = Math.floor(277 / geometry.heightMm);
    const perPage = Math.max(1, rows * geometry.columns);
    const out: Rendered[][] = [];
    for (let index = 0; index < rendered.length; index += perPage) out.push(rendered.slice(index, index + perPage));
    return out;
  }, [rendered, geometry.columns, geometry.heightMm]);

  if (!cards.length) {
    return (
      <div className="wc-host">
        <style>{sheetStyles()}</style>
        <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">{emptyMessage}</p>
      </div>
    );
  }

  return (
    <div className="wc-host">
      <style>{sheetStyles()}</style>
      {/* Horizontally scrollable rather than shrunk to fit: a sheet drawn at anything but 100% is
          not a preview of what will come out of the printer, which is the one question this screen
          exists to answer. */}
      <div className="overflow-x-auto">
        {pages.map((page, pageIndex) => (
          <div
            key={pageIndex}
            className="wc-sheet mx-auto mb-6 shadow-md ring-1 ring-line-200"
            style={{ gridTemplateColumns: `repeat(${geometry.columns}, ${geometry.widthMm}mm)` }}
          >
            {page.map((entry) => (
              <div key={entry.key} className="wc-card" style={{ width: `${geometry.widthMm}mm`, height: `${geometry.heightMm}mm` }}>
                {entry.ok ? (
                  <svg
                    className="wc-qr"
                    viewBox={`0 0 ${entry.extent} ${entry.extent}`}
                    xmlns="http://www.w3.org/2000/svg"
                    shapeRendering="crispEdges"
                    role="img"
                    aria-label={`Code for ${entry.card.title}`}
                    data-testid="workshop-code-qr"
                    data-code={entry.code}
                  >
                    {/* The quiet zone is white paper, drawn rather than assumed: a transparent SVG
                        over a tinted card leaves the symbol without the light border a scanner
                        uses to find it at all. */}
                    <rect width={entry.extent} height={entry.extent} fill="#ffffff" />
                    <path d={entry.path} />
                  </svg>
                ) : (
                  <div className="wc-blank" aria-hidden />
                )}
                <div className="wc-body">
                  <div className="wc-title">{entry.card.title}</div>
                  {(entry.card.lines ?? []).slice(0, 2).map((line, index) => (
                    <div key={index} className="wc-line">
                      {line}
                    </div>
                  ))}
                  {entry.ok ? (
                    <div className="wc-code" data-testid="workshop-code-text">
                      {entry.printed}
                    </div>
                  ) : (
                    <div className="wc-refusal" data-testid="workshop-code-refusal">
                      {entry.message}
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}
