"use client";

/**
 * Every block the report builder can emit, drawn as the paper it is about to become.
 *
 * THIS IS NOT A FIFTH RENDERER. `GET /report/preview` builds the same `ReportDocument` the .docx
 * writer, the server .pdf writer and the two on-device Kotlin writers consume, and serialises its
 * blocks; this file draws those blocks and reconstructs nothing. A preview that walked the
 * workshop data itself would be one more traversal of the same record and would be the first of
 * the five to drift — silently, because the person reading the preview is reading it precisely so
 * they do not have to open the file. If a block arrives that this file cannot draw, that is a bug
 * here, never a reason to rebuild the block from the stage data.
 *
 * THE CLASS NAMES ARE NOT TAILWIND. Everything inside a sheet is styled by the hand-written
 * `rp-*` stylesheet in `ReportSheet.tsx`, in POINTS and MILLIMETRES, against the colours
 * `report_model.ReportTheme` declares — because the job of this markup is to look like a printed
 * A4 page and not like a screen. Tailwind's ladders are the right answer for the chrome AROUND
 * the sheet and the wrong one inside it: `text-sm` is 14px on a viewport, and 14px is not 10.5pt
 * on paper. The chrome this file does render outside a sheet — the "not readable from here"
 * placeholder, the figure toggle — uses the themed token ladders as usual.
 */

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useId,
  useMemo,
  useState,
  type CSSProperties,
  type Dispatch,
  type ReactNode,
  type SetStateAction
} from "react";
import { ImageOff, Map as MapIcon, Table2 } from "lucide-react";

import { IndiaMap } from "@/components/map/IndiaMap";
import type { MapCounts, MapPoint } from "@/components/map/types";
import { apiFetch } from "@/lib/api";
import type { DwImageRef, DwRun } from "@/lib/designWorkshops";
import type { MediaFile } from "@/lib/types";
import { ReportChartSvg } from "@/components/designworkshop/report/ReportChart";
import {
  countCodeSpans,
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

/* ────────────────────────────────────────────────────────────────────────────
 * The one piece of block state that changes a block's HEIGHT
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The map figure's live-map / printed-figure choice, held OUTSIDE the block where a paginator
 * needs to know about it.
 *
 * `ReportSheets` lays every block out once off-screen, measures it, and draws fixed-height pages
 * from those measurements. Exactly one control inside a block can change a block's height after
 * that: `ReportMapFigure`'s toggle, because the hoverable `IndiaMap` and the rasterised figure the
 * file will contain are not the same size. Left as local state, the drawn copy would grow while
 * the measured copy did not, and the fixed page would CLIP the difference — a silent truncation,
 * on the one screen whose entire job is showing what is on the page.
 *
 * So the state is lifted through a context the paginator provides and re-measures on. Optional by
 * design: with no provider — `StageDocumentPreview`, or anything rendering a bare block list —
 * each figure keeps its own state and behaves exactly as it did before.
 */
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

/**
 * `useState`, unless a host is holding this piece of state for everybody — then it is the host's.
 *
 * Both hooks run on every render and the choice is made in the RETURN, never around a hook call:
 * a provider that appeared or vanished between renders must not change the hook order.
 */
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

/* ────────────────────────────────────────────────────────────────────────────
 * Inline content
 * ──────────────────────────────────────────────────────────────────────────── */

const ALIGN_STYLE: Record<string, CSSProperties["textAlign"]> = {
  LEFT: "left",
  CENTER: "center",
  RIGHT: "right",
  JUSTIFY: "justify"
};

/**
 * One run of formatted text.
 *
 * `color` arrives BARE ("1F3864", no leading hash) because both file writers want it that way, so
 * the hash is added here. A run with no colour inherits the sheet's ink rather than being given a
 * default, which is what lets the theme live in one place.
 *
 * UNDERLINE AND STRIKETHROUGH ARE ONE DECLARATION, NOT TWO. `text-decoration: underline` followed
 * by `text-decoration: line-through` is not both — the second wins outright — so a run carrying
 * both marks would silently lose one, and losing it here means a designer approves a deletion
 * that the .docx still shows as struck. `text-decoration-line` takes both keywords at once, so
 * they are composed into a single value.
 */
/**
 * `PreviewRun` plus the two flags `report_model.Run` grew for raised and lowered text.
 *
 * Declared HERE rather than widened in `previewModel.ts` for one reason: that file is shared, and
 * these two arrive automatically — the preview payload is `dataclasses.asdict` over `Run`, so a
 * field added to the dataclass is on the wire the same day — which makes this an optional pair to
 * read, not a contract to renegotiate. Optional on purpose: a payload generated by a server one
 * release behind simply has neither, and the run draws on the baseline, which is what it did.
 */
type VerticalRun = PreviewRun & { superscript?: boolean; subscript?: boolean; highlight?: boolean };

/**
 * The one colour a highlight is, in every renderer.
 *
 * `report_model.HIGHLIGHT_FILL` — the hex Word draws for `w:highlight w:val="yellow"`, which is the
 * only spelling the .docx has for it. Restated here rather than imported because this file is the
 * PAPER, and everything inside a sheet is drawn against the report's own colours in its own units;
 * a token from the app's theme would invert with the viewer's dark mode and the printed page does
 * not.
 */
const HIGHLIGHT_FILL = "#FFFF00";

export function Runs({ runs }: { runs: DwRun[] | undefined }) {
  return (
    <>
      {(runs ?? []).map((raw, index) => {
        const run = raw as VerticalRun;
        const decoration = [run.underline ? "underline" : null, run.strike ? "line-through" : null]
          .filter(Boolean)
          .join(" ");
        // SUPERSCRIPT WINS a run that somehow claims both, which is the same rule
        // `rich_text._runs_for` applies before either flag ever leaves the server — restated here
        // only so this file has one answer rather than depending on the payload having exactly one.
        const raised = run.superscript ? "super" : run.subscript ? "sub" : undefined;
        return (
          <span
            key={index}
            style={{
              color: run.color ? `#${run.color}` : undefined,
              // Both file writers fill the run's own extent and nothing more, so the box here is
              // the span rather than the line: a highlighted phrase must not tint the whole
              // paragraph it sits in, on screen or on paper.
              backgroundColor: run.highlight ? HIGHLIGHT_FILL : undefined,
              fontWeight: run.bold ? 700 : undefined,
              fontStyle: run.italic ? "italic" : undefined,
              textDecorationLine: decoration || undefined,
              verticalAlign: raised,
              // Sized in em and NOT left to the browser's own `vertical-align: super` default,
              // which shrinks nothing at all: both file writers draw the glyph at 62% of the body
              // size (`report_pdf.VERTICAL_SCALE`), and a preview that showed a full-size raised
              // character would be showing a line that fits on screen and does not fit in the file.
              fontSize: raised ? "0.62em" : undefined,
              // A raised run must not open up the line it sits on — the .docx and both PDF writers
              // leave the leading alone, so the sheet has to as well or a paragraph containing a
              // footnote marker paginates differently on screen than on paper.
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

/** One rich-text span. Same composition rule as {@link Runs}, plus the mark the writers lack. */
function RichSpanText({ span: raw }: { span: DwRichSpan }) {
  // The same optional pair {@link Runs} reads, and for the same reason: `rich_text.to_preview_json`
  // emits `superscript`/`subscript` already resolved to single-valued booleans, so this file never
  // has to know that SUPERSCRIPT beats SUBSCRIPT.
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
  // CODE is in the stored vocabulary and NOT on `report_model.Run`, so it reaches this screen and
  // does not reach the file. Drawing it faithfully and counting it (see `countCodeSpans`, whose
  // total the page states above the preview) is the only honest pair: hiding the mark would lie
  // to the designer about what they typed, and drawing it without saying anything would lie about
  // what the ministry will receive.
  return span.code ? (
    <code className="rp-code" style={style}>
      {span.text}
    </code>
  ) : (
    <span style={style}>{span.text}</span>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * Photographs
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Media ids resolved to something an `<img>` can load.
 *
 * A CONTEXT rather than a prop threaded through sixteen block renderers, because the resolution
 * is a cross-cutting concern of the whole preview and only three of the sixteen blocks carry an
 * image at all. `undefined` for an id means "not looked up yet"; `null` means "looked up and
 * there is no URL to show" — a media row this caller is not entitled to the bytes of, or one
 * deleted since the stage referenced it. The two must stay distinguishable or a photograph that
 * is merely still loading renders as a permanent "cannot be shown".
 */
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

/**
 * A photograph the report references, drawn from its media id.
 *
 * `ImageRef.source` IS A MEDIA ID, NOT A URL. The file writers load the bytes server-side; the
 * browser has to fetch its own copy, and it cannot do that by pointing an `<img>` at the API —
 * every media route is bearer-authenticated and an `<img>` sends no Authorization header. So the
 * id is exchanged for `MediaFile.url` first (presigned or public) and that is what is rendered.
 * `url` is gated server-side at the encoder and may legitimately be absent, which is why the
 * fallback is a stated placeholder rather than a broken frame.
 *
 * The EXIF quarter turn is applied here as well: the Android client stores camera output
 * unrotated with the orientation only in EXIF, so a preview that ignored `rotation_deg` would lay
 * every portrait photograph on its side — and a designer would then "fix" a rotation that is
 * already correct in the generated file, which is the worse outcome of the two.
 */
function ReportImage({
  image,
  alt,
  className,
  style
}: {
  image: DwImageRef;
  alt: string;
  className?: string;
  style?: CSSProperties;
}) {
  const urls = useContext(MediaUrlContext);
  const url = urls[image.source];

  if (url === undefined) {
    return <span className={`rp-photo-pending ${className ?? ""}`} aria-hidden />;
  }
  if (url === null) {
    return (
      <span className={`rp-photo-missing ${className ?? ""}`}>
        <ImageOff className="h-3.5 w-3.5 shrink-0" aria-hidden />
        This photograph is not readable from here — it will still be embedded in the file if the server can read it.
      </span>
    );
  }
  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img
      src={url}
      alt={alt}
      loading="lazy"
      className={className}
      style={{
        ...style,
        transform: image.rotation_deg ? `rotate(${image.rotation_deg}deg)` : undefined
      }}
    />
  );
}

/**
 * Resolve every image id in the preview, once per preview.
 *
 * Batched over the whole document rather than fetched per `<img>`: a photo catalogue is forty
 * pictures, and forty components each firing their own effect is forty requests plus forty
 * renders on a connection that is often the reason the report is being generated at all.
 * `Promise.all` over the distinct ids costs one round trip's latency for all of them.
 *
 * MAP AND CHART BLOCKS ARE NOT SCANNED, and must not be. Their pictures are rasterised by the
 * server and travel inside the block; they reference no media row at all, and looking one up
 * would 404 and be reported to the designer as a photograph that could not be included.
 */
export function useReportMediaUrls(blocks: PreviewBlock[] | undefined): Record<string, string | null> {
  const ids = useMemo(() => {
    const found = new Set<string>();
    for (const block of blocks ?? []) {
      if (block.type === "COVER") {
        if (block.logo) found.add(block.logo.source);
        if (block.hero_image) found.add(block.hero_image.source);
      } else if (block.type === "IMAGE") {
        found.add(block.image.source);
      } else if (block.type === "IMAGEGRID") {
        for (const [image] of block.images) found.add(image.source);
      }
    }
    return [...found].sort();
  }, [blocks]);

  const [urls, setUrls] = useState<Record<string, string | null>>({});

  useEffect(() => {
    let cancelled = false;
    const unresolved = ids.filter((mediaId) => !(mediaId in urls));
    if (!unresolved.length) return;
    (async () => {
      const resolved = await Promise.all(
        unresolved.map(async (mediaId) => {
          try {
            const file = await apiFetch<MediaFile>(`/media/${mediaId}`);
            return [mediaId, file.url ?? null] as const;
          } catch {
            return [mediaId, null] as const;
          }
        })
      );
      if (cancelled) return;
      setUrls((current) => ({ ...current, ...Object.fromEntries(resolved) }));
    })();
    return () => {
      cancelled = true;
    };
    // `urls` is read to skip ids already resolved but is deliberately NOT a dependency: it is what
    // this effect writes, and depending on it would re-run the effect on its own result forever.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ids.join(",")]);

  return urls;
}

/* ────────────────────────────────────────────────────────────────────────────
 * The two rasterised figures
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * How wide a figure sits in the text column, and where.
 *
 * `width_pct` is a percentage of the TEXT COLUMN, exactly as it is for `ImageBlock` in the file
 * writers — not of the paper. The sheet's padding is the margin, so a percentage inside it is
 * already a percentage of the column and needs no arithmetic.
 */
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

/**
 * The report's pins in the shape the repository's own map draws.
 *
 * Two fields are answered with a deliberate blank rather than a guess:
 *
 * `counts` is the per-record-type breakdown every point on `/map` carries, and the report's map
 * has none — its `count` is a fold of artisans onto one coordinate, not a census of five record
 * tables. Zeros are the honest answer and nothing in `IndiaMap` reads them; inventing a
 * breakdown to make the shapes match would put numbers on screen that no query produced.
 *
 * `precision` decides the uncertainty halo, and `MapBlock` carries no precision at all. TOWN is
 * the zero-halo tier that claims the least: SUBJECT_PIN would assert somebody dropped a pin on
 * this exact place, and DISTRICT would draw a 45 km circle of doubt around a coordinate that may
 * be a measured venue. Neither claim is available, so neither is made — and the printed figure,
 * which is the authority, draws no halo either.
 */
const NO_COUNTS: MapCounts = { artisans: 0, workshops: 0, products: 0, tools: 0, media: 0 };

function toMapPoints(pins: DwMapPin[]): MapPoint[] {
  return pins.map((pin, index) => ({
    // The ordinal is part of the key so two pins on the same place name stay distinct, and so the
    // key is stable across renders — `layoutPins` derives its collision jitter from it, and a key
    // that changed would move a pin between two identical datasets.
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

/**
 * The map of India the report carries.
 *
 * THE LIVE MAP AND THE PNG MUST AGREE, AND THE PNG IS THE AUTHORITY. `report_raster` draws the
 * picture that is embedded in the .docx and the .pdf; that raster is what a ministry will receive
 * and is therefore the thing being approved on this screen. The component below is preferred for
 * SCREEN reading whenever the block carries its points, because a preview is a screen and can be
 * interactive — a designer can hover a pin, read a place name and count the artisans folded into
 * it, none of which a flat picture in a document can offer — but it is a second drawing of the
 * same data and a second drawing can disagree. Two ways it is already known to:
 *
 *   * `highlight` tints STATES in the printed figure, and `IndiaMap` has no tinting; the tinted
 *     states are therefore named in words beneath, where they cannot be missed.
 *   * pin size here is proportional to the count relative to the busiest pin on THIS map, while
 *     the rasteriser sizes by kind — the venue larger, an artisan's home district smaller.
 *
 * So: the live map is offered, the printed figure is one click away, and PRINTING this page uses
 * the PNG whenever there is one. A designer approving a document must be able to see the picture
 * the document contains, not an approximation of it that happens to be nicer to use.
 */
function ReportMapFigure({ block, modeKey }: { block: DwMapBlock; modeKey?: string }) {
  const raster = rasterSource(block);
  const points = useMemo(() => toMapPoints(block.points ?? []), [block.points]);
  const hasLive = points.length > 0;
  // Falls back to the raster with no toggle when there are no points to draw: an empty outline of
  // India beside a picture full of pins is not a preview, it is a second, wrong answer.
  //
  // THE STATE IS SHARED WITH THE PAGINATOR WHERE ONE IS MOUNTED. The two drawings are different
  // HEIGHTS, so on a paginated sheet this toggle moves a page break; `ReportSheets` measures its
  // blocks off-screen and would then be sizing a page for the drawing that is no longer on it,
  // and the fixed sheet would clip the difference. Through the context both copies read one
  // answer and the measurement re-runs. With no provider — `StageDocumentPreview`, or any caller
  // rendering a bare block list — it is ordinary local state and behaves exactly as it did.
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
            The map is drawn into the picture when the file is generated. The places it will pin are listed below.
          </p>
        )
      ) : (
        <div
          // Printing always uses the printed figure when there is one, whatever is on screen: the
          // browser's own Print to PDF is a stand-in for the server's, and a stand-in that
          // substitutes a different drawing is not one.
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

/**
 * One infographic.
 *
 * DRAWN LIVE, IN THE BROWSER, FROM THE BLOCK'S OWN `series`. The .docx and the .pdf get a PNG
 * rasterised by `report_chart` — one implementation of the picture for four renderers that must
 * not disagree about a number — but a preview is a screen, and a screen's job here is to answer
 * "what does this figure look like now that I have changed that cost head?" A round trip to
 * re-rasterise between one edit and the next is a request per keystroke on a metered rural
 * connection, and a figure that lags a second behind the data is one a designer stops believing.
 * So `ReportChartSvg` copies the rasteriser's arithmetic — the axis, the rounding, the slice order
 * and the colour ramp — rather than inventing its own. Read its header before changing anything
 * about how a value is placed.
 *
 * THE PNG IS STILL THE AUTHORITY, and if the payload ever carries one it is what PRINTING uses:
 * the browser's own Print to PDF is a stand-in for the server's writers, and a stand-in that
 * substitutes a different drawing of the same numbers is not one. (`_block_payload` is
 * `dataclasses.asdict` over `ChartBlock`, which holds no pixels at all, so today there is no PNG
 * on this wire — the branch below is what stops a preview going blank the day one is added.)
 *
 * The values are ALSO printed, as a small table under the figure. That is not a fallback: the
 * figure is `aria-hidden` to everything that cannot see it, and the table is how a screen-reader
 * user reads the figure at all, how a truncated category name is read in full, and how a value
 * that fell between two pixels of the axis is read exactly.
 */
function ReportChartFigure({ block }: { block: DwChartBlock }) {
  const raster = rasterSource(block);
  const series = block.series ?? [];
  const total = series.reduce((sum, [, value]) => sum + (Number.isFinite(value) ? value : 0), 0);
  // A share is only a fact for a figure that divides a whole. On a bar chart of "cost per head"
  // a percentage of the sum is a number with no meaning, and printing one invites it to be
  // quoted.
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

/**
 * A figure's value, printed the way a document prints one.
 *
 * `toLocaleString` with `en-IN` groups by the Indian system — 12,34,567 rather than 1,234,567 —
 * which is what every other number in a report submitted to an Indian ministry uses, and what the
 * rasteriser draws on the axis.
 */
function formatChartValue(value: number): string {
  if (!Number.isFinite(value)) return "—";
  const decimals = Number.isInteger(value) ? 0 : 2;
  return value.toLocaleString("en-IN", { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
}

/* ────────────────────────────────────────────────────────────────────────────
 * Rich text, with the structure the writers flatten
 * ──────────────────────────────────────────────────────────────────────────── */

const RICH_HEADING_CLASS: Record<number, string> = { 1: "rp-h1", 2: "rp-h2", 3: "rp-h3", 4: "rp-h4" };

function isRichListItem(block: DwRichBlock): boolean {
  return block.kind === "BULLET_ITEM" || block.kind === "ORDERED_ITEM";
}

function richLevel(block: DwRichBlock): number {
  return Math.max(0, Math.min(3, Math.trunc(block.level) || 0));
}

/**
 * A run of list items rendered as REAL nested lists, by recursive descent over the flat stream.
 *
 * The stored document is a flat block list with a depth per item, not a tree — `rich_text.py`
 * explains why: a tree is what every editor library produces and would have to be flattened at
 * the head of all five renderers, and the five flattenings would disagree about the edge cases.
 * Flattening once, when the editor saves, is what stops that. Rebuilding the nesting for DISPLAY
 * is a different job and belongs here, and it has to produce real `<ul>`/`<ol>` nesting rather
 * than a left margin per item: a screen reader announces "list, 3 items, nesting level 2" from
 * the structure and reads an indent as nothing at all.
 *
 * Returns the node and the index of the first block it did not consume.
 */
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
      // A deeper item belongs INSIDE the item above it. A document that opens at depth 2 with no
      // depth-1 parent is malformed but real (a phone one release ahead, a hand-edited value), so
      // an empty carrier item is created rather than dropping the text on the floor.
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

/**
 * A rich-text field as the designer typed it.
 *
 * All five block kinds, all four alignments, both list kinds with their depth, and the four
 * heading levels. The heading here carries no number and no bookmark: only headings the TEMPLATE
 * emits take part in the report's numbering and its table of contents, and printing "3.2" beside
 * a heading a designer typed inside a narrative field would claim a place in a contents list it
 * does not have.
 */
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

/* ────────────────────────────────────────────────────────────────────────────
 * The block dispatch
 * ──────────────────────────────────────────────────────────────────────────── */

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

export function ReportBlock({ block, blockKey }: { block: PreviewBlock; blockKey?: string }) {
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
            {block.logo ? <ReportImage image={block.logo} alt="" className="rp-cover-logo" /> : null}
          </div>
          <h2 className="rp-cover-title">{block.title}</h2>
          {block.subtitle ? <p className="rp-cover-subtitle">{block.subtitle}</p> : null}
          {block.hero_image ? (
            <ReportImage image={block.hero_image} alt={block.title} className="rp-cover-hero" />
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
          {/* Word paginates its own TOC field and the .pdf writer lays the body out twice to learn
              which page every heading landed on. Neither is possible in a browser, and inventing
              page numbers would print numbers the file will not match — a contents page is
              precisely the part of a document a reader trusts without checking. So the preview
              says what it is instead of pretending. */}
          <p className="rp-toc-note">
            Built with real page numbers when the file is generated, to depth {block.depth}.
          </p>
        </section>
      );

    case "HEADING": {
      const level = Math.max(1, Math.min(4, block.level));
      const Tag = (["h2", "h3", "h4", "h5"] as const)[level - 1];
      return (
        <Tag id={block.bookmark || undefined} className={HEADING_CLASS[level]}>
          {block.number ? <span className="rp-heading-number">{block.number}</span> : null}
          {/* Through `Runs` and not `runsText`, even though a heading is bold already: a heading
              typed inside a RICH_TEXT narrative can carry an italic term or a struck-through
              working title, `to_report_blocks` puts those marks on the runs, and both file writers
              print them. Flattening to plain text here would show a designer a heading the file
              does not have. */}
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
      // No nesting here, and that is the server's flattening rather than an omission:
      // `BulletListBlock` carries no depth because a .docx list is a run of paragraphs sharing a
      // numbering id, and the depth a designer typed survives only through the RICHTEXT block.
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
              {/* The builder's own label width, honoured so the preview's columns line up the way
                  the generated file's borderless two-column table will. */}
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
          {/* A table wider than the column scrolls INSIDE its own box on screen. Letting it widen
              the sheet would make the whole page scroll sideways and every other block with it —
              and the sheet is a picture of paper, which does not get wider. */}
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
          <ReportImage
            image={block.image}
            alt={block.caption || "Workshop photograph"}
            className="rp-figure-img"
          />
          {block.caption ? <figcaption className="rp-caption">{block.caption}</figcaption> : null}
        </figure>
      );

    case "IMAGEGRID":
      return (
        <figure className="rp-figure">
          <div
            className="rp-grid"
            // `columns` is a request, not a guarantee — the file writers drop to fewer columns
            // when a cell would fall below a legible width. The same idea in CSS, so a narrow
            // sheet shows a readable grid rather than four unreadable slivers.
            style={{ gridTemplateColumns: `repeat(auto-fit, minmax(${Math.max(24, 100 / Math.max(1, block.columns) - 2)}%, 1fr))` }}
            data-requested-columns={block.columns}
          >
            {block.images.map(([image, caption], index) => (
              <figure key={index}>
                <ReportImage image={image} alt={caption || `Photograph ${index + 1}`} className="rp-grid-img" />
                {caption ? <figcaption className="rp-caption">{caption}</figcaption> : null}
              </figure>
            ))}
          </div>
          {block.caption ? <figcaption className="rp-caption">{block.caption}</figcaption> : null}
        </figure>
      );

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
              {/* The rule IS the signature line, so it is a real border rather than a row of
                  underscores — underscores do not survive a copy into an email and read to a
                  screen reader as a text artefact rather than as a place to sign. */}
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
      // The gap is real content in the generated document — it is what stops a section running
      // into the next — and `height_pct` is a percentage of the TEXT COLUMN HEIGHT. Drawn against
      // `--rp-text-h`, the sheet's own inner height, rather than at some pixel figure that would
      // mean one thing on A4 and another on Letter.
      return (
        <div
          aria-hidden
          style={{ height: `calc(${Math.max(0, Math.min(50, block.height_pct)) / 100} * var(--rp-text-h))` }}
        />
      );

    case "PAGEBREAK":
      // Consumed by `ReportSheet.planFlow`, which folds it onto the following block as
      // `breakBefore` so that the paginator acts on an instruction rather than on a block with no
      // drawing. It can still reach here where a caller renders a raw block list —
      // `StageDocumentPreview` does — so it draws nothing rather than falling through to an
      // unhandled type.
      return null;
  }
}
