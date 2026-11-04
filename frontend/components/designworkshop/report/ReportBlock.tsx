"use client";

import {
  createContext,
  memo,
  useCallback,
  useContext,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type Dispatch,
  type ReactNode,
  type SetStateAction
} from "react";
import { ImageOff, Map as MapIcon, Table2 } from "lucide-react";

import { IndiaMap } from "@/components/map/IndiaMap";
import type { MapCounts, MapPoint } from "@/components/map/types";
import { fetchMediaRow } from "@/lib/mediaRows";
import { draftSessionUserId, isLocalMediaRef } from "@/lib/designWorkshopStore";
import { mediaBlob } from "@/lib/offlineReport/images";
import { hasMedia, putMedia } from "@/lib/offlineReport/reportCache";
import type { DwImageRef, DwRun } from "@/lib/designWorkshops";
import type { MediaFile } from "@/lib/types";
import { ReportChartSvg } from "@/components/designworkshop/report/ReportChart";
import {
  noteImageDimensions,
  probeImageDimensions,
  useImageDimensions
} from "@/components/designworkshop/report/imageDimensions";
import {
  countCodeSpans,
  galleryColumns,
  rasterSource,
  type DwChartBlock,
  type DwMapBlock,
  type DwMapPin,
  type DwRichBlock,
  type DwRichSpan,
  type PreviewBlock,
  type PreviewRun
} from "@/components/designworkshop/report/previewModel";

export { countCodeSpans };

export type ReportFigureModes = {
  modes: Record<string, boolean>;
  setMode: (key: string, value: boolean) => void;
};

const FigureModeContext = createContext<ReportFigureModes | null>(null);

export function ReportFigureModeProvider({
  value,
  children
}: {
  value: ReportFigureModes;
  children: ReactNode;
}) {
  return <FigureModeContext.Provider value={value}>{children}</FigureModeContext.Provider>;
}

function useShared(
  key: string | undefined,
  fallback: boolean
): [boolean, Dispatch<SetStateAction<boolean>>] {
  const shared = useContext(FigureModeContext);
  const [local, setLocal] = useState(fallback);
  const hosted = shared !== null && key !== undefined;
  const current = hosted ? shared.modes[key] ?? fallback : local;
  const set = useCallback<Dispatch<SetStateAction<boolean>>>(
    (value) => {
      const next = typeof value === "function" ? value(current) : value;
      if (hosted) shared.setMode(key, next);
      else setLocal(next);
    },
    [hosted, shared, key, current]
  );
  return [current, set];
}

const ALIGN_STYLE: Record<string, CSSProperties["textAlign"]> = {
  LEFT: "left",
  CENTER: "center",
  RIGHT: "right",
  JUSTIFY: "justify"
};

type VerticalRun = PreviewRun & { superscript?: boolean; subscript?: boolean; highlight?: boolean };

const HIGHLIGHT_FILL = "#FFFF00";

export function Runs({ runs }: { runs: DwRun[] | undefined }) {
  return (
    <>
      {(runs ?? []).map((raw, index) => {
        const run = raw as VerticalRun;
        const decoration = [run.underline ? "underline" : null, run.strike ? "line-through" : null]
          .filter(Boolean)
          .join(" ");
        const raised = run.superscript ? "super" : run.subscript ? "sub" : undefined;
        return (
          <span
            key={index}
            style={{
              color: run.color ? `#${run.color}` : undefined,
              backgroundColor: run.highlight ? HIGHLIGHT_FILL : undefined,
              fontWeight: run.bold ? 700 : undefined,
              fontStyle: run.italic ? "italic" : undefined,
              textDecorationLine: decoration || undefined,
              verticalAlign: raised,
              fontSize: raised ? "0.62em" : undefined,
              lineHeight: raised ? 0 : undefined
            }}
          >
            {run.text}
          </span>
        );
      })}
    </>
  );
}

function RichSpanText({ span: raw }: { span: DwRichSpan }) {
  const span = raw as DwRichSpan & {
    superscript?: boolean;
    subscript?: boolean;
    highlight?: boolean;
  };
  const decoration = [span.underline ? "underline" : null, span.strike ? "line-through" : null]
    .filter(Boolean)
    .join(" ");
  const raised = span.superscript ? "super" : span.subscript ? "sub" : undefined;
  const style: CSSProperties = {
    fontWeight: span.bold ? 700 : undefined,
    fontStyle: span.italic ? "italic" : undefined,
    backgroundColor: span.highlight ? HIGHLIGHT_FILL : undefined,
    textDecorationLine: decoration || undefined,
    verticalAlign: raised,
    fontSize: raised ? "0.62em" : undefined,
    lineHeight: raised ? 0 : undefined
  };
  return span.code ? (
    <code className="rp-code" style={style}>
      {span.text}
    </code>
  ) : (
    <span style={style}>{span.text}</span>
  );
}

const MediaUrlContext = createContext<Record<string, string | null>>({});

export function MediaUrlProvider({
  urls,
  children
}: {
  urls: Record<string, string | null>;
  children: ReactNode;
}) {
  return <MediaUrlContext.Provider value={urls}>{children}</MediaUrlContext.Provider>;
}

export type ReportGeometry = { textWidthMm: number; bodyHeightMm: number; measuring: boolean };

const DEFAULT_GEOMETRY: ReportGeometry = { textWidthMm: 160, bodyHeightMm: 297 - 50 - 16, measuring: false };

const GeometryContext = createContext<ReportGeometry>(DEFAULT_GEOMETRY);

export function ReportGeometryProvider({ value, children }: { value: ReportGeometry; children: ReactNode }) {
  return <GeometryContext.Provider value={value}>{children}</GeometryContext.Provider>;
}

export function displayAspect(image: DwImageRef): number {
  const turned = image.rotation_deg === 90 || image.rotation_deg === 270;
  const w = turned ? image.height_px : image.width_px;
  const h = turned ? image.width_px : image.height_px;
  return w > 0 && h > 0 ? w / h : 4 / 3;
}

function ReportImage({
  image,
  alt,
  maxHeightFraction,
  maxHeightMm,
  maxWidth = "100%",
  className
}: {
  image: DwImageRef;
  alt: string;
  maxHeightFraction?: number;
  maxHeightMm?: number;
  maxWidth?: string;
  className?: string;
}) {
  const urls = useContext(MediaUrlContext);
  const { bodyHeightMm, measuring } = useContext(GeometryContext);
  const url = urls[image.source];
  const [loadedUrl, setLoadedUrl] = useState<string | null>(null);
  const learned = useImageDimensions(image.source);
  const hasOwn = image.width_px > 0 && image.height_px > 0;
  const aspect = hasOwn ? displayAspect(image) : learned ? learned.width / learned.height : 4 / 3;
  const turned = hasOwn && (image.rotation_deg === 90 || image.rotation_deg === 270);
  const rotation = hasOwn ? image.rotation_deg : 0;
  const box: CSSProperties = {
    width: `min(${maxWidth}, ${((maxHeightMm ?? bodyHeightMm * (maxHeightFraction ?? 0.62)) * aspect).toFixed(3)}mm)`,
    aspectRatio: String(aspect)
  };
  const picture: CSSProperties = turned
    ? {
        width: `${100 / aspect}%`,
        height: `${100 * aspect}%`,
        transform: `translate(-50%, -50%) rotate(${rotation}deg)`,
        imageOrientation: "none"
      }
    : {
        width: "100%",
        height: "100%",
        transform: rotation ? `translate(-50%, -50%) rotate(${rotation}deg)` : undefined,
        imageOrientation: hasOwn ? "none" : "from-image"
      };

  return (
    <span className={`rp-photo ${className ?? ""}`} style={box} data-state={url === null ? "missing" : undefined}>
      {url === null ? (
        <span className="rp-photo-missing">
          <ImageOff className="h-3.5 w-3.5 shrink-0" aria-hidden />
          Can&rsquo;t show this photo here; it&rsquo;s still in the file.
        </span>
      ) : url && !measuring ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={url}
          alt={alt}
          loading="lazy"
          decoding="async"
          data-loaded={loadedUrl === url ? "true" : undefined}
          onLoad={(event) => {
            setLoadedUrl(url);
            if (!hasOwn) noteImageDimensions(image.source, event.currentTarget.naturalWidth, event.currentTarget.naturalHeight);
          }}
          style={picture}
        />
      ) : null}
    </span>
  );
}

function ReportGallery({ block }: { block: Extract<PreviewBlock, { type: "IMAGEGRID" }> }) {
  const { textWidthMm } = useContext(GeometryContext);
  const columns = galleryColumns(block.columns, textWidthMm);
  return (
    <figure className="rp-figure">
      <div className="rp-grid" style={{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }} data-requested-columns={block.columns}>
        {block.images.map(([image, caption], index) => (
          <figure key={index} className="rp-grid-cell">
            <ReportImage image={image} alt={caption || `Photograph ${index + 1}`} maxHeightFraction={0.3} />
            {caption ? <figcaption className="rp-grid-caption">{caption}</figcaption> : null}
          </figure>
        ))}
      </div>
      {block.caption ? <figcaption className="rp-caption">{block.caption}</figcaption> : null}
    </figure>
  );
}

export function useReportMediaUrls(blocks: PreviewBlock[] | undefined): Record<string, string | null> {
  const { ids, sized } = useMemo(() => {
    const found = new Set<string>();
    const withSize = new Set<string>();
    const take = (image: DwImageRef) => {
      found.add(image.source);
      if (image.width_px > 0 && image.height_px > 0) withSize.add(image.source);
    };
    for (const block of blocks ?? []) {
      if (block.type === "COVER") {
        if (block.logo) take(block.logo);
        if (block.hero_image) take(block.hero_image);
      } else if (block.type === "IMAGE") {
        take(block.image);
      } else if (block.type === "IMAGEGRID") {
        for (const [image] of block.images) take(image);
      }
    }
    return { ids: [...found], sized: withSize };
  }, [blocks]);

  const [urls, setUrls] = useState<Record<string, string | null>>({});
  const madeHere = useRef<string[]>([]);

  useEffect(() => {
    const made = madeHere.current;
    return () => {
      for (const url of made) URL.revokeObjectURL(url);
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    const unresolved = ids.filter((mediaId) => !(mediaId in urls));
    if (!unresolved.length) return;
    (async () => {
      const userId = draftSessionUserId();
      const resolved = await Promise.all(
        unresolved.map(async (mediaId) => {
          if (!isLocalMediaRef(mediaId)) {
            try {
              const file = await fetchMediaRow(mediaId);
              if (file.url) void keepForOffline(mediaId, file.url, userId);
              return [mediaId, file.url ?? null] as const;
            } catch {
            }
          }
          const blob = await mediaBlob(mediaId, userId).catch(() => null);
          if (!blob) return [mediaId, null] as const;
          const url = URL.createObjectURL(blob);
          madeHere.current.push(url);
          return [mediaId, url] as const;
        })
      );
      if (cancelled) return;
      setUrls((current) => ({ ...current, ...Object.fromEntries(resolved) }));
      probeImageDimensions(
        unresolved
          .filter((mediaId) => !sized.has(mediaId))
          .flatMap((mediaId) => {
            const url = resolved.find(([id]) => id === mediaId)?.[1];
            return url ? [[mediaId, url] as [string, string]] : [];
          })
      );
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ids.join(",")]);

  return urls;
}

async function keepForOffline(mediaId: string, url: string, userId: string | null): Promise<void> {
  if (!userId || (await hasMedia(mediaId, userId))) return;
  try {
    const response = await fetch(url);
    if (!response.ok) return;
    await putMedia(mediaId, userId, await response.blob());
  } catch {
  }
}

function figureStyle(widthPct: number, align: string): CSSProperties {
  const width = `${Math.max(10, Math.min(100, widthPct || 100))}%`;
  if (align === "LEFT") return { width };
  if (align === "RIGHT") return { width, marginLeft: "auto" };
  return { width, marginLeft: "auto", marginRight: "auto" };
}

const PIN_KIND_LABEL: Record<DwMapPin["kind"], string> = {
  VENUE: "Workshop venue",
  ARTISAN: "Artisans' place",
  MARKET: "Market",
  OTHER: "Place"
};

const NO_COUNTS: MapCounts = { artisans: 0, workshops: 0, products: 0, tools: 0, media: 0 };

function toMapPoints(pins: DwMapPin[]): MapPoint[] {
  return pins.map((pin, index) => ({
    key: `${index}-${pin.label}`,
    layer: "ORIGIN" as const,
    label: pin.label,
    region: PIN_KIND_LABEL[pin.kind] ?? PIN_KIND_LABEL.OTHER,
    state: null,
    latitude: pin.lat,
    longitude: pin.lon,
    precision: "TOWN" as const,
    total: Math.max(1, pin.count),
    counts: NO_COUNTS
  }));
}

function ReportMapFigure({ block, modeKey }: { block: DwMapBlock; modeKey?: string }) {
  const raster = rasterSource(block);
  const points = useMemo(() => toMapPoints(block.points ?? []), [block.points]);
  const hasLive = points.length > 0;
  const [showRaster, setShowRaster] = useShared(modeKey, !hasLive);
  const [hovered, setHovered] = useState<string | null>(null);
  const captionId = useId();

  const venueKeys = useMemo(
    () => points.filter((_point, index) => block.points[index]?.kind === "VENUE").map((point) => point.key),
    [points, block.points]
  );

  const showingRaster = showRaster || !hasLive;

  return (
    <figure className="rp-figure" style={figureStyle(block.width_pct, block.align)}>
      {block.title ? <p className="rp-figure-title">{block.title}</p> : null}

      {showingRaster ? (
        raster ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={raster} alt={block.title || "Map of the workshop's places"} className="rp-figure-img" />
        ) : (
          <p className="rp-figure-absent">
            The map is drawn in the file. Places:
          </p>
        )
      ) : (
        <div
          className={raster ? "rp-map-live rp-print-hide" : "rp-map-live"}
          aria-describedby={captionId}
        >
          <IndiaMap points={points} level="STATE" focusKeys={venueKeys} hoveredKey={hovered} onHover={setHovered} />
        </div>
      )}
      {!showingRaster && raster ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={raster} alt={block.title || "Map of the workshop's places"} className="rp-figure-img rp-print-only" />
      ) : null}

      {hasLive ? (
        <ol className="rp-figure-legend" id={captionId}>
          {block.points.map((pin, index) => (
            <li key={`${index}-${pin.label}`}>
              <span className="rp-legend-ordinal">{index + 1}</span>
              <span className="rp-legend-label">{pin.label}</span>
              <span className="rp-legend-meta">
                {PIN_KIND_LABEL[pin.kind] ?? PIN_KIND_LABEL.OTHER}
                {pin.count > 1 ? ` · ${pin.count} records` : ""}
              </span>
            </li>
          ))}
        </ol>
      ) : null}

      {block.highlight?.length ? (
        <p className="rp-figure-note">
          Tinted in the printed figure: {block.highlight.join(", ")}.
        </p>
      ) : null}

      {block.caption ? <figcaption className="rp-caption">{block.caption}</figcaption> : null}

      {hasLive && raster ? (
        <button
          type="button"
          data-rp-noprint
          className="rp-figure-toggle"
          aria-pressed={showRaster}
          onClick={() => setShowRaster((current) => !current)}
        >
          <MapIcon className="h-3.5 w-3.5" aria-hidden />
          {showRaster ? "Show the map you can hover" : "Show the figure the file will contain"}
        </button>
      ) : null}
    </figure>
  );
}

function ReportChartFigure({ block }: { block: DwChartBlock }) {
  const raster = rasterSource(block);
  const series = block.series ?? [];
  const total = series.reduce((sum, [, value]) => sum + (Number.isFinite(value) ? value : 0), 0);
  const showShare = (block.kind === "PIE" || block.kind === "DONUT") && total > 0;

  return (
    <figure className="rp-figure" style={figureStyle(block.width_pct, block.align)}>
      {block.title ? (
        <p className="rp-figure-title">
          {block.title}
          {block.unit ? <span className="rp-figure-unit"> ({block.unit})</span> : null}
        </p>
      ) : null}

      <div className={raster ? "rp-chart-live rp-print-hide" : "rp-chart-live"}>
        <ReportChartSvg block={block} />
      </div>
      {raster ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={raster} alt={block.title || "Chart"} className="rp-figure-img rp-print-only" />
      ) : null}

      {series.length ? (
        <table className="rp-figure-values">
          <caption className="rp-figure-values-caption">
            <Table2 className="h-3 w-3" aria-hidden /> The values in the figure
          </caption>
          <tbody>
            {series.map(([label, value], index) => (
              <tr key={index}>
                <th scope="row">{label}</th>
                <td className="rp-num">
                  {formatChartValue(value)}
                  {block.unit ? <span className="rp-figure-unit"> {block.unit}</span> : null}
                </td>
                {showShare ? <td className="rp-num rp-muted">{((value / total) * 100).toFixed(1)}%</td> : null}
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p className="rp-figure-absent">This figure has no values yet.</p>
      )}

      {block.caption ? <figcaption className="rp-caption">{block.caption}</figcaption> : null}
    </figure>
  );
}

function formatChartValue(value: number): string {
  if (!Number.isFinite(value)) return "—";
  const decimals = Number.isInteger(value) ? 0 : 2;
  return value.toLocaleString("en-IN", { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
}

const RICH_HEADING_CLASS: Record<number, string> = { 1: "rp-h1", 2: "rp-h2", 3: "rp-h3", 4: "rp-h4" };

function isRichListItem(block: DwRichBlock): boolean {
  return block.kind === "BULLET_ITEM" || block.kind === "ORDERED_ITEM";
}

function richLevel(block: DwRichBlock): number {
  return Math.max(0, Math.min(3, Math.trunc(block.level) || 0));
}

function renderRichList(
  blocks: DwRichBlock[],
  start: number,
  level: number,
  ordered: boolean,
  keyPrefix: string
): [ReactNode, number] {
  const items: Array<{ content: ReactNode; children: ReactNode[] }> = [];
  let index = start;

  while (index < blocks.length) {
    const block = blocks[index];
    if (!isRichListItem(block)) break;
    const blockLevel = richLevel(block);
    if (blockLevel < level) break;

    if (blockLevel > level) {
      if (!items.length) items.push({ content: null, children: [] });
      const [child, next] = renderRichList(
        blocks,
        index,
        blockLevel,
        blocks[index].kind === "ORDERED_ITEM",
        `${keyPrefix}-${items.length}`
      );
      items[items.length - 1].children.push(child);
      index = next;
      continue;
    }

    if ((block.kind === "ORDERED_ITEM") !== ordered) break;

    items.push({
      content: (
        <>
          {block.spans.map((span, spanIndex) => (
            <RichSpanText key={spanIndex} span={span} />
          ))}
        </>
      ),
      children: []
    });
    index += 1;
  }

  const ListTag = ordered ? "ol" : "ul";
  const node = (
    <ListTag key={keyPrefix} className={ordered ? "rp-ol" : "rp-ul"}>
      {items.map((item, itemIndex) => (
        <li key={itemIndex}>
          {item.content}
          {item.children}
        </li>
      ))}
    </ListTag>
  );
  return [node, index];
}

export function RichTextBlocks({ blocks }: { blocks: DwRichBlock[] }) {
  const nodes: ReactNode[] = [];
  let index = 0;

  while (index < blocks.length) {
    const block = blocks[index];

    if (isRichListItem(block)) {
      const [node, next] = renderRichList(
        blocks,
        index,
        richLevel(block),
        block.kind === "ORDERED_ITEM",
        `list-${index}`
      );
      nodes.push(node);
      index = next;
      continue;
    }

    const align = ALIGN_STYLE[block.align] ?? "left";
    const spans = block.spans.map((span, spanIndex) => <RichSpanText key={spanIndex} span={span} />);

    if (block.kind === "HEADING") {
      const level = Math.max(1, Math.min(4, Math.trunc(block.level) || 1));
      const Tag = (["h2", "h3", "h4", "h5"] as const)[level - 1];
      nodes.push(
        <Tag key={index} className={RICH_HEADING_CLASS[level]} style={{ textAlign: align }}>
          {spans}
        </Tag>
      );
    } else if (block.kind === "QUOTE") {
      nodes.push(
        <blockquote key={index} className="rp-quote" style={{ textAlign: align }}>
          {spans}
        </blockquote>
      );
    } else {
      nodes.push(
        <p key={index} className="rp-p" style={{ textAlign: align }}>
          {spans}
        </p>
      );
    }
    index += 1;
  }

  return <>{nodes}</>;
}

const PARA_CLASS: Record<string, string> = {
  BODY: "rp-p",
  LEAD: "rp-lead",
  NOTE: "rp-note",
  QUOTE: "rp-quote",
  CAPTION: "rp-caption",
  COVER_LINE: "rp-cover-line"
};

const HEADING_CLASS: Record<number, string> = { 1: "rp-h1", 2: "rp-h2", 3: "rp-h3", 4: "rp-h4" };

const CALLOUT_CLASS: Record<string, string> = {
  INFO: "rp-callout rp-callout-info",
  WARNING: "rp-callout rp-callout-warning",
  SUCCESS: "rp-callout rp-callout-success"
};

export const ReportBlock = memo(function ReportBlock({ block, blockKey }: { block: PreviewBlock; blockKey?: string }) {
  switch (block.type) {
    case "COVER":
      return (
        <section className="rp-cover">
          <div className="rp-cover-head">
            {block.org_lines.map((line, index) => (
              <p key={index} className="rp-cover-org">
                {line}
              </p>
            ))}
            {block.logo ? (
              <ReportImage image={block.logo} alt="" maxHeightMm={22} className="rp-cover-logo" />
            ) : null}
          </div>
          <h2 className="rp-cover-title">{block.title}</h2>
          {block.subtitle ? <p className="rp-cover-subtitle">{block.subtitle}</p> : null}
          {block.hero_image ? (
            <ReportImage image={block.hero_image} alt={block.title} maxHeightMm={62} maxWidth="80%" className="rp-cover-hero" />
          ) : null}
          {block.info_rows.length ? (
            <dl className="rp-cover-info">
              {block.info_rows.map(([label, value], index) => (
                <div key={index}>
                  <dt>{label}</dt>
                  <dd>{value}</dd>
                </div>
              ))}
            </dl>
          ) : null}
          <div className="rp-cover-foot">
            {block.footer_lines.filter(Boolean).map((line, index) => (
              <p key={index}>{line}</p>
            ))}
          </div>
        </section>
      );

    case "TOC":
      return (
        <section className="rp-toc">
          <h2 className="rp-toc-title">{block.title}</h2>
          <p className="rp-toc-note">
            Page numbers are added in the file (depth {block.depth}).
          </p>
        </section>
      );

    case "HEADING": {
      const level = Math.max(1, Math.min(4, block.level));
      const Tag = (["h2", "h3", "h4", "h5"] as const)[level - 1];
      return (
        <Tag id={block.bookmark || undefined} className={HEADING_CLASS[level]}>
          {block.number ? <span className="rp-heading-number">{block.number}</span> : null}
          <Runs runs={block.runs} />
        </Tag>
      );
    }

    case "PARAGRAPH":
      return (
        <p className={PARA_CLASS[block.style] ?? PARA_CLASS.BODY} style={{ textAlign: ALIGN_STYLE[block.align] ?? "left" }}>
          <Runs runs={block.runs} />
        </p>
      );

    case "BULLETLIST": {
      const items = block.items.map((runs, index) => (
        <li key={index}>
          <Runs runs={runs} />
        </li>
      ));
      return block.ordered ? <ol className="rp-ol">{items}</ol> : <ul className="rp-ul">{items}</ul>;
    }

    case "RICHTEXT":
      return (
        <div className="rp-richtext">
          <RichTextBlocks blocks={block.blocks} />
          {block.caption ? <p className="rp-caption">{block.caption}</p> : null}
        </div>
      );

    case "KEYVALUE":
      return (
        <dl className={block.columns > 1 ? "rp-kv rp-kv-2" : "rp-kv"}>
          {block.pairs.map(([label, runs], index) => (
            <div key={index}>
              <dt style={{ width: `${block.label_width_pct}%` }}>{label}</dt>
              <dd>
                <Runs runs={runs} />
              </dd>
            </div>
          ))}
        </dl>
      );

    case "TABLE":
      return (
        <figure className="rp-table-figure">
          <div className="rp-scroll">
            <table className={block.zebra ? "rp-table rp-zebra" : "rp-table"}>
              <thead>
                <tr>
                  {block.columns.map((column, index) => (
                    <th
                      key={index}
                      scope="col"
                      style={{
                        width: `${column.width_pct}%`,
                        textAlign: column.numeric ? "right" : ALIGN_STYLE[column.align] ?? "left"
                      }}
                    >
                      {column.header}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {block.rows.map((row, rowIndex) => (
                  <tr key={rowIndex}>
                    {row.map((cell, cellIndex) => (
                      <td key={cellIndex} className={block.columns[cellIndex]?.numeric ? "rp-num" : undefined}>
                        <Runs runs={cell} />
                      </td>
                    ))}
                  </tr>
                ))}
                {block.total_row ? (
                  <tr className="rp-total">
                    {block.total_row.map((cell, cellIndex) => (
                      <td key={cellIndex} className={block.columns[cellIndex]?.numeric ? "rp-num" : undefined}>
                        <Runs runs={cell} />
                      </td>
                    ))}
                  </tr>
                ) : null}
              </tbody>
            </table>
          </div>
          {block.caption ? <figcaption className="rp-caption">{block.caption}</figcaption> : null}
        </figure>
      );

    case "IMAGE":
      return (
        <figure className="rp-figure" style={figureStyle(block.width_pct, block.align)}>
          <ReportImage image={block.image} alt={block.caption || "Workshop photograph"} maxHeightFraction={0.62} />
          {block.caption ? <figcaption className="rp-caption">{block.caption}</figcaption> : null}
        </figure>
      );

    case "IMAGEGRID":
      return <ReportGallery block={block} />;

    case "METRICROW":
      return (
        <div className="rp-metrics">
          {block.metrics.map(([label, value, unit], index) => (
            <div key={index} className="rp-metric">
              <span className="rp-metric-value">
                {value}
                {unit ? <span className="rp-metric-unit"> {unit}</span> : null}
              </span>
              <span className="rp-metric-label">{label}</span>
            </div>
          ))}
        </div>
      );

    case "CALLOUT":
      return (
        <aside className={CALLOUT_CLASS[block.kind] ?? CALLOUT_CLASS.INFO}>
          {block.title ? <p className="rp-callout-title">{block.title}</p> : null}
          <p>
            <Runs runs={block.runs} />
          </p>
        </aside>
      );

    case "SIGNATURE":
      return (
        <div className="rp-signatures">
          {block.signatories.map(([name, designation], index) => (
            <div key={index}>
              <span className="rp-signature-rule" aria-hidden />
              <span className="rp-signature-name">{name}</span>
              {designation ? <span className="rp-signature-role">{designation}</span> : null}
            </div>
          ))}
        </div>
      );

    case "MAP":
      return <ReportMapFigure block={block} modeKey={blockKey} />;

    case "CHART":
      return <ReportChartFigure block={block} />;

    case "SPACER":
      return (
        <div
          aria-hidden
          style={{ height: `calc(${Math.max(0, Math.min(50, block.height_pct)) / 100} * var(--rp-text-h))` }}
        />
      );

    case "PAGEBREAK":
      return null;
  }
});
