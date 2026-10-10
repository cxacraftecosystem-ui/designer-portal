"use client";

/**
 * The browser half of writing a report file with no connection: finding a photograph's bytes on
 * the device, and drawing the charts and the map as pictures.
 *
 * WHERE A PHOTOGRAPH COMES FROM. A `dwlocal:` reference is a photograph taken on this device and
 * not yet uploaded — its bytes are in the draft store (`readLocalMedia`). Anything else is a server
 * media id, and its bytes are in the report cache if this account has opened it before
 * (`reportCache.getMedia`). A photograph in neither is left out of the file and named beside the
 * download, exactly as the server names a photograph it could not fetch.
 *
 * EVERY PICTURE IS RE-ENCODED HERE, so the two writers never see a format they cannot embed
 * (HEIC, WebP) or a camera's EXIF orientation: `createImageBitmap(…, { imageOrientation:
 * "from-image" })` applies the rotation, a canvas scales the long side to at most
 * {@link MAX_EDGE_PX} — the server's raster ceiling for a report image — and the result is a JPEG,
 * or a PNG where the source carries transparency (a logo).
 */

import type { FigureRasteriser, ImageLoader, LoadedImage } from "@/lib/offlineReport/docx";
import type { ChartBlock, ImageRef, MapBlock } from "@/lib/offlineReport/model";
import { getMedia } from "@/lib/offlineReport/reportCache";
import { isLocalMediaRef, readLocalMedia } from "@/lib/designWorkshopStore";
import type { ReportPalette } from "@/lib/reportTheme";

export const MAX_EDGE_PX = 2000;
const PX_PER_MM = 96 / 25.4;

/** The bytes of a photograph this device holds, or null. */
export async function mediaBlob(source: string, userId: string | null): Promise<Blob | null> {
  if (isLocalMediaRef(source)) {
    const local = await readLocalMedia(source);
    return local?.blob ?? null;
  }
  return getMedia(source, userId);
}

async function canvasBytes(canvas: HTMLCanvasElement, type: "image/png" | "image/jpeg"): Promise<Uint8Array | null> {
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, type, 0.86));
  return blob ? new Uint8Array(await blob.arrayBuffer()) : null;
}

/** A photograph, oriented, bounded and re-encoded for embedding. */
export async function encodeForReport(blob: Blob): Promise<LoadedImage | null> {
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(blob, { imageOrientation: "from-image" });
  } catch {
    return null;
  }
  const scale = Math.min(1, MAX_EDGE_PX / Math.max(bitmap.width, bitmap.height));
  const width = Math.max(1, Math.round(bitmap.width * scale));
  const height = Math.max(1, Math.round(bitmap.height * scale));
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext("2d");
  if (!context) return null;
  const png = blob.type === "image/png" || blob.type === "image/gif" || blob.type === "image/webp";
  if (!png) {
    context.fillStyle = "#FFFFFF";
    context.fillRect(0, 0, width, height);
  }
  context.drawImage(bitmap, 0, 0, width, height);
  bitmap.close();
  const data = await canvasBytes(canvas, png ? "image/png" : "image/jpeg");
  return data ? { data, type: png ? "png" : "jpg", widthPx: width, heightPx: height } : null;
}

/** The loader both writers take: device bytes, re-encoded. */
export function deviceImageLoader(userId: string | null): ImageLoader {
  return async (image: ImageRef) => {
    const blob = await mediaBlob(image.source, userId);
    return blob ? encodeForReport(blob) : null;
  };
}

async function svgToPng(svg: string, widthPx: number, heightPx: number): Promise<LoadedImage | null> {
  const url = URL.createObjectURL(new Blob([svg], { type: "image/svg+xml" }));
  try {
    const image = new Image();
    image.decoding = "sync";
    await new Promise<void>((resolve, reject) => {
      image.onload = () => resolve();
      image.onerror = () => reject(new Error("figure"));
      image.src = url;
    });
    const canvas = document.createElement("canvas");
    canvas.width = widthPx;
    canvas.height = heightPx;
    const context = canvas.getContext("2d");
    if (!context) return null;
    context.fillStyle = "#FFFFFF";
    context.fillRect(0, 0, widthPx, heightPx);
    context.drawImage(image, 0, 0, widthPx, heightPx);
    const data = await canvasBytes(canvas, "image/png");
    return data ? { data, type: "png", widthPx, heightPx } : null;
  } catch {
    return null;
  } finally {
    URL.revokeObjectURL(url);
  }
}

function escapeXml(text: string): string {
  return text.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&apos;" })[c] ?? c);
}

/** The map as an SVG string: India's outline, the state borders, and the pins numbered in order. */
async function mapSvg(block: MapBlock, palette: ReportPalette): Promise<{ svg: string; width: number; height: number }> {
  const { VIEW_BOX, bordersToPath, indiaOutlinePath, project } = await import("@/components/map/projection");
  const { loadBorders } = await import("@/components/map/borderGeometry");
  let borders = "";
  try {
    borders = bordersToPath(await loadBorders("state"));
  } catch {
    borders = "";
  }
  const pins = block.points
    .map((point, index) => {
      const { x, y } = project(point.lon, point.lat);
      const venue = point.kind === "VENUE";
      const r = venue ? 11 : 8;
      const fill = venue ? palette.accent : palette.accentSoft;
      return (
        `<circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="${r}" fill="${fill}" stroke="#FFFFFF" stroke-width="2.5"/>` +
        `<text x="${x.toFixed(1)}" y="${(y + 4).toFixed(1)}" font-size="${venue ? 12 : 10}" font-family="sans-serif" ` +
        `font-weight="700" text-anchor="middle" fill="#FFFFFF">${index + 1}</text>`
      );
    })
    .join("");
  const legend = block.points
    .map(
      (point, index) =>
        `<text x="${VIEW_BOX.width + 16}" y="${28 + index * 22}" font-size="15" font-family="sans-serif" fill="${palette.ink}">` +
        `${index + 1}. ${escapeXml(point.label)}${point.count > 1 ? ` (${point.count})` : ""}</text>`
    )
    .join("");
  const legendWidth = block.points.length ? 300 : 0;
  const width = VIEW_BOX.width + legendWidth;
  const height = Math.max(VIEW_BOX.height, 40 + block.points.length * 22);
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${width} ${height}" width="${width}" height="${height}">` +
    `<rect width="${width}" height="${height}" fill="#FFFFFF"/>` +
    `<path d="${indiaOutlinePath()}" fill="${palette.zebra}" stroke="${palette.rule}" stroke-width="1.5"/>` +
    (borders ? `<path d="${borders}" fill="none" stroke="${palette.rule}" stroke-width="0.8"/>` : "") +
    pins +
    legend +
    `</svg>`;
  return { svg, width, height };
}

/** A chart as an SVG string, drawn by the same component the preview draws it with. */
async function chartSvg(block: ChartBlock, palette: ReportPalette): Promise<{ svg: string; width: number; height: number }> {
  // Rendered into a detached element with the client renderer, so the preview's own chart component
  // draws the file's figure and no server renderer is pulled into the browser bundle.
  const [{ createRoot }, { flushSync }, chart] = await Promise.all([
    import("react-dom/client"),
    import("react-dom"),
    import("@/components/designworkshop/report/ReportChart")
  ]);
  const host = document.createElement("div");
  const root = createRoot(host);
  flushSync(() => {
    root.render(
      <chart.ReportPaletteProvider palette={palette}>
        <chart.ReportChartSvg block={block} />
      </chart.ReportPaletteProvider>
    );
  });
  const markup = host.innerHTML;
  root.unmount();
  const viewBox = /viewBox="0 0 ([\d.]+) ([\d.]+)"/.exec(markup);
  const width = viewBox ? Number(viewBox[1]) : 900;
  const height = viewBox ? Number(viewBox[2]) : 558;
  const svg = markup
    .replace("<svg ", `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" font-family="sans-serif" `)
    .replace(/class="[^"]*"/g, "");
  return { svg, width, height };
}

/** The rasteriser both writers take. */
export function browserFigureRasteriser(palette: ReportPalette): FigureRasteriser {
  return async (block, widthMm) => {
    const drawn = block.type === "MAP" ? await mapSvg(block, palette) : await chartSvg(block, palette);
    const targetWidth = Math.round(widthMm * PX_PER_MM * 2);
    const targetHeight = Math.round((targetWidth * drawn.height) / drawn.width);
    return svgToPng(drawn.svg, targetWidth, targetHeight);
  };
}
