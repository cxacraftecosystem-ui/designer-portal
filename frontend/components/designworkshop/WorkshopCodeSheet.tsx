"use client";

import { useEffect, useMemo, useRef, useState, type RefObject } from "react";
import { createPortal } from "react-dom";

import { encodeQr, qrSvgPath, QrEncodeError } from "@/lib/qrEncode";
import {
  encodeWorkshopCode,
  formatWorkshopCodeForPrint,
  type WorkshopRecordType
} from "@/lib/workshopCodes";

export type WorkshopCodeCard = {
  recordType: WorkshopRecordType;
  id: string | null | undefined;
  title: string;
  lines?: string[];
  key: string;
};

const QR_BOX_MM = 26;

type CardGeometry = { widthMm: number; heightMm: number; columns: number };

const ID_CARD: CardGeometry = { widthMm: 85, heightMm: 54, columns: 2 };
const OBJECT_TAG: CardGeometry = { widthMm: 63, heightMm: 45, columns: 3 };

const GEOMETRY: Record<WorkshopRecordType, CardGeometry> = {
  artisan: ID_CARD,
  craft: OBJECT_TAG,
  workshop: OBJECT_TAG,
  product: OBJECT_TAG,
  process: OBJECT_TAG,
  tool: OBJECT_TAG,
  questionnaire: OBJECT_TAG,
  media: OBJECT_TAG,
  designWorkshop: ID_CARD,
  prototype: OBJECT_TAG
};

const ECC_LEVEL = "Q" as const;

type Rendered =
  | { key: string; ok: true; card: WorkshopCodeCard; path: string; extent: number; code: string; printed: string }
  | { key: string; ok: false; card: WorkshopCodeCard; message: string };

function renderCard(card: WorkshopCodeCard): Rendered {
  const encoded = encodeWorkshopCode({ recordType: card.recordType, id: card.id });
  if (!encoded.ok) return { key: card.key, ok: false, card, message: encoded.message };
  try {
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

  /* A modal is never part of a printout. FieldDialog portals to <body>, so it is outside the group
     hidden below and would otherwise print its backdrop across the cards — including the dialog a
     designer may have the record open in when they press Print on one card. */
  [data-field-dialog-overlay] { display: none !important; }

  /* EVERYTHING THAT IS NOT THE SHEET IS DROPPED — the page header, the scanner, the kind picker,
     the banners. They are direct children of AppShell's <main>, hidden as a group so that the next
     section anybody adds to this page does not silently start printing itself.

     .wc-print-keep and NOT .wc-host: the class is put on whichever direct child of <main>
     CONTAINS the sheet (see usePrintKeepMarker), because a caller that wraps the sheet in a
     spacing div had its wrapper hidden here and the sheet went down with it. The reset that
     follows is the same one .rp-host gets, and it is what sheds the wrapper's margin, border
     and shadow so the paper starts at the paper's edge. */
  #main-content > * { display: none !important; }
  #main-content > .wc-print-keep {
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

function usePrintKeepMarker(hostRef: RefObject<HTMLDivElement | null>): void {
  useEffect(() => {
    const main = document.getElementById("main-content");
    let node: HTMLElement | null = hostRef.current;
    while (node && node.parentElement && node.parentElement !== main) node = node.parentElement;
    if (!main || !node || node.parentElement !== main) return;
    const marked = node;
    marked.classList.add("wc-print-keep");
    return () => marked.classList.remove("wc-print-keep");
  }, [hostRef]);
}

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
  const hostRef = useRef<HTMLDivElement>(null);
  usePrintKeepMarker(hostRef);

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
      <div className="wc-host" ref={hostRef}>
        <style>{sheetStyles()}</style>
        <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">{emptyMessage}</p>
      </div>
    );
  }

  return (
    <div className="wc-host" ref={hostRef}>
      <style>{sheetStyles()}</style>
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

export function WorkshopCodePrintout({
  card,
  onFinished
}: {
  card: WorkshopCodeCard;
  onFinished: () => void;
}) {
  const [container, setContainer] = useState<HTMLDivElement | null>(null);
  const printed = useRef(false);

  useEffect(() => {
    const main = document.getElementById("main-content");
    if (!main) return;
    const node = document.createElement("div");
    node.style.display = "none";
    main.append(node);
    setContainer(node);
    return () => node.remove();
  }, []);

  useEffect(() => {
    if (!container || printed.current) return;
    printed.current = true;
    const finish = () => onFinished();
    let settle: ReturnType<typeof setTimeout> | null = null;
    window.addEventListener("afterprint", finish);
    const frame = requestAnimationFrame(() => {
      window.print();
      settle = setTimeout(finish, 0);
    });
    return () => {
      window.removeEventListener("afterprint", finish);
      cancelAnimationFrame(frame);
      if (settle !== null) clearTimeout(settle);
    };
  }, [container, onFinished]);

  if (!container) return null;
  return createPortal(
    <WorkshopCodeSheet
      recordType={card.recordType}
      cards={[card]}
      emptyMessage="This record has no code to print."
    />,
    container
  );
}
