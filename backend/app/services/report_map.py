"""The report's map of India: the app's own geometry, projected the app's own way, as a PNG.

WHY THIS IS NOT A NEW MAP. The web app already draws this exact picture — ``frontend/components/
map/IndiaMap.tsx`` over ``indiaGeometry`` and ``borderGeometry``, through the single ``project``
in ``frontend/components/map/projection.ts``. A report that drew its own would be a second
depiction of a national boundary maintained in a second place, and the two would diverge the
first time either was touched. Everything below is therefore a PORT of that projection and a
READER of those assets, never a redraw:

* :data:`INDIA_BOUNDS`, :data:`VIEW_WIDTH` and :data:`PADDING` are the constants from
  ``projection.ts``, and :func:`project` is that file's ``project`` line for line. The report and
  the app place a pin at the same fraction of the same outline, so a designer who saw a cluster
  in the top-left of the web map finds it in the top-left of the printed one.
* The coastline and the international frontier come from ``india_outline.bin`` — the same
  Government of India depiction ``indiaGeometry`` carries, whose provenance is documented at the
  head of that file and verified point-in-polygon there. Nothing here simplifies, clips or
  re-derives it.
* The state borders come from ``state-borders.txt`` / ``state_borders.bin``, the derived interior
  edges described in ``borderGeometry.ts``.

WHY IT IS A RASTER AND NOT VECTORS. The alternative is drawing the outline into OOXML DrawingML
*and* into PDF path operators *and* twice more in Kotlin — four implementations of one picture
that would have to agree about 11,649 points. Rasterising once and handing the PNG to the image
path every renderer already has is one implementation, and it is the reason ``report_docx`` and
``report_pdf`` needed nothing new to print a map beyond a call into here.

WHY THERE IS NO IMAGE LIBRARY IN THE IMPORT LIST. ``pyproject`` keeps Pillow in an optional
extra; the core install has no image library and the phone has none at all. ``report_raster``
does the drawing with ``zlib`` and ``struct``. See its docstring.

HOW A STATE IS TINTED, which is the one genuinely surprising thing in this module. The assets are
BORDERS, not polygons — ``borderGeometry.ts`` explains at length why storing them as polygons
would ship the interior of India twice — so there is no "Rajasthan" ring to fill. What there is,
after the country is filled and every state border is stroked over it, is a raster in which each
state is a connected region of land-coloured pixels bounded by stroke. Seeding a flood fill at
the state's own capital therefore tints exactly that state, using the geometry that is already
there rather than a second dataset that would have to agree with it. The fill is budgeted and
discovers before it commits — see :meth:`report_raster.Raster.flood_fill` for what happens when a
border run stops a pixel short of the coast, and why that must never colour the subcontinent.

``backend/tests/test_report_graphics.py`` pins the projection against the TypeScript constants,
the asset decode against ``tests/test_boundary_assets.py``'s own reading of the same files, and
the output against ``report_docx.probe_image_size``.
"""

from __future__ import annotations

import math
import os
import struct
import threading
from pathlib import Path

from app.services.report_model import MapBlock, MapPoint, MapPointKind, ReportTheme
from app.services.report_raster import (
    RGB,
    Raster,
    ellipsise,
    mix,
    rgb_of,
    text_height,
    text_width,
)

# --------------------------------------------------------------------------------------
# The projection — frontend/components/map/projection.ts, ported constant for constant
# --------------------------------------------------------------------------------------

#: ``indiaGeometry.INDIA_BOUNDS``. The bounds of the SIMPLIFIED geometry, not of the source, and
#: not the bounds in the binary asset's own header either — those are the unrounded extent the
#: quantiser used. ``projection.ts`` divides by THESE, so a report that divided by the header's
#: would place every pin a fraction of a pixel off the web app's, which is the kind of difference
#: nobody sees and everybody argues about later.
MIN_LONGITUDE = 68.2060
MAX_LONGITUDE = 97.3940
MIN_LATITUDE = 6.7560
MAX_LATITUDE = 37.0820

#: Width of the shared user space. Height follows from the country's true aspect ratio.
VIEW_WIDTH = 1000.0
#: Breathing room around the coastline, so a coastal pin is not half cut off.
PADDING = 24.0

_MID_LATITUDE = (MIN_LATITUDE + MAX_LATITUDE) / 2
#: Plate carrée with the horizontal axis scaled by cos(centre latitude). NOT Web Mercator: India
#: spans 6.8N to 37.1N and Mercator stretches the top of that range about 25% more than the
#: bottom, so Kashmir would print visibly too large beside Kerala on a figure whose whole job is
#: comparing how much work came from where.
LONGITUDE_SCALE = math.cos(math.radians(_MID_LATITUDE))

_SPAN_X = (MAX_LONGITUDE - MIN_LONGITUDE) * LONGITUDE_SCALE
_SPAN_Y = MAX_LATITUDE - MIN_LATITUDE

UNITS_PER_DEGREE = (VIEW_WIDTH - PADDING * 2) / _SPAN_X
VIEW_HEIGHT = _SPAN_Y * UNITS_PER_DEGREE + PADDING * 2


def project(longitude: float, latitude: float) -> tuple[float, float]:
    """Where a coordinate lands in the shared user space. Latitude grows north; y grows down."""
    return (
        PADDING + (longitude - MIN_LONGITUDE) * LONGITUDE_SCALE * UNITS_PER_DEGREE,
        PADDING + (MAX_LATITUDE - latitude) * UNITS_PER_DEGREE,
    )


def units_per_kilometre() -> float:
    """How many user-space units a kilometre covers. Used to size the scale bar."""
    return UNITS_PER_DEGREE / 111.32


# --------------------------------------------------------------------------------------
# Reading the assets
# --------------------------------------------------------------------------------------

#: ``borderGeometry.ts``'s alphabet and quantisation. Must match ``scripts/build_boundaries.py``.
_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
_VALUES = {character: index for index, character in enumerate(_ALPHABET)}
_SCALE = 1000.0

#: ``IND1``. The header magic of every ``.bin`` in ``android/app/src/main/res/raw``.
_OUTLINE_MAGIC = 0x494E4431
_BIN_HEADER = ">idddd i"

Polyline = list[tuple[float, float]]


def _repo_root() -> Path:
    """…/backend/app/services/report_map.py -> the repository root."""
    return Path(__file__).resolve().parents[3]


def _asset_dirs() -> list[Path]:
    """Every directory the geometry might be in, most specific first.

    THE RUNTIME IMAGE CARRIES NEITHER ``frontend/`` NOR ``android/``. ``backend/Dockerfile``
    copies ``backend/app``, ``backend/prisma`` and ``backend/scripts`` and nothing else, so the
    two repository paths below resolve in a checkout — which is where the tests and every local
    run happen — and resolved to NOTHING in the deployed container. That is not a hypothetical:
    the deployed image shipped with no geometry at all, so section 6, "Where the workshop was
    held and where its artisans live", was a numbered heading followed immediately by section 7
    in both the .docx and the PDF — no map, no pins, not even the caption — while the same
    workshop's ``/report/preview``, served by a renderer that reads the same assets from the
    checkout, showed the map the designer then signed off on. The only signal was a header
    reading "1 photograph(s) could not be included in the file", which sent designers hunting for
    a missing photo on workshops that had no photographs at all.

    So the three files are now COMMITTED under ``backend/app/data/boundaries`` — the directory
    checked first here, and the one path that is inside every image because the Dockerfile copies
    ``backend/app`` wholesale. ``android/`` is excluded from the build context outright and could
    not be COPYed even explicitly. They are byte-for-byte the clients' own assets and
    ``tests/test_report_map_assets.py`` fails if they ever stop being, because three copies of one
    generated file is a drift risk that has to be checked rather than hoped about;
    ``scripts/build_boundaries.py`` writes the other two and must write this one as well.

    The environment override exists for a deployment that mounts them somewhere else again. A
    missing asset is still not an exception: :func:`render_map_png` returns ``None`` and the
    renderers drop the figure with a warning, exactly as they drop a photograph whose bytes did
    not arrive. A report that refused to generate because a decorative map was unavailable would
    be a far worse outcome for the designer waiting on it in a field.
    """
    candidates: list[Path] = []
    override = os.environ.get("REPORT_MAP_ASSET_DIR", "").strip()
    if override:
        candidates.append(Path(override))
    package_data = Path(__file__).resolve().parents[1] / "data" / "boundaries"
    candidates.append(package_data)
    root = _repo_root()
    candidates.append(root / "frontend" / "public" / "boundaries")
    candidates.append(root / "android" / "app" / "src" / "main" / "res" / "raw")
    return candidates


def _find(*names: str) -> Path | None:
    """The first of ``names`` that exists in any asset directory, in directory order."""
    for directory in _asset_dirs():
        for name in names:
            candidate = directory / name
            try:
                if candidate.is_file():
                    return candidate
            except OSError:
                # An unreadable mount point is a missing asset, not a crashed export.
                continue
    return None


def decode_text_polylines(payload: str) -> list[Polyline]:
    """Decode ``state-borders.txt`` — ``borderGeometry.decodeBorders``, in Python.

    Per record a zig-zag varint point count, then that many delta-encoded coordinate pairs, with x
    and y carried ACROSS records so a neighbouring border costs a few characters rather than a
    full coordinate. Open polylines: never close one, or the Rajasthan/Gujarat border grows a
    straight line across the country back to where it started.
    """
    lines: list[Polyline] = []
    index = 0
    x = 0
    y = 0
    length = len(payload)

    def read_int() -> int:
        nonlocal index
        shift = 0
        result = 0
        while index < length:
            value = _VALUES.get(payload[index], 0)
            index += 1
            result |= (value & 0x1F) << shift
            shift += 5
            if value < 0x20:
                break
        return ~(result >> 1) if result & 1 else result >> 1

    while index < length:
        count = read_int()
        # A zero or negative count means a truncated payload; stop rather than spin on it.
        if count <= 0:
            break
        line: Polyline = []
        for _ in range(count):
            x += read_int()
            y += read_int()
            line.append((x / _SCALE, y / _SCALE))
        lines.append(line)
    return lines


def decode_binary_polylines(blob: bytes) -> list[Polyline]:
    """Decode an ``IND1`` binary — the format ``IndiaOutline.loadIndiaGeometry`` reads on Android.

    The header carries the bounds the quantiser used, and the points are un-quantised with THOSE
    rather than with :data:`MIN_LONGITUDE` and friends. Mixing the two is the classic version of
    this bug: the numbers are within a thousandth of a degree of each other, so the map still
    looks like India and every coastline sits a pixel out.
    """
    if len(blob) < struct.calcsize(_BIN_HEADER):
        return []
    magic, min_lon, max_lon, min_lat, max_lat, count = struct.unpack_from(_BIN_HEADER, blob, 0)
    if magic != _OUTLINE_MAGIC or count <= 0:
        return []
    offset = struct.calcsize(_BIN_HEADER)
    lon_step = (max_lon - min_lon) / 65535
    lat_step = (max_lat - min_lat) / 65535
    lines: list[Polyline] = []
    for _ in range(count):
        if offset + 4 > len(blob):
            break
        (points,) = struct.unpack_from(">i", blob, offset)
        offset += 4
        if points <= 0 or offset + points * 4 > len(blob):
            break
        line: Polyline = []
        for _ in range(points):
            qx, qy = struct.unpack_from(">HH", blob, offset)
            offset += 4
            line.append((min_lon + qx * lon_step, min_lat + qy * lat_step))
        lines.append(line)
    return lines


# Decoded once and kept, exactly as ``indiaGeometry.indiaRings`` memoises: a single pass over
# 11,649 points is cheap but it is not free, and a report with three maps in it would otherwise
# pay for it three times inside a request the designer is waiting on.
_CACHE: dict[str, list[Polyline]] = {}
_CACHE_LOCK = threading.Lock()


def _load(kind: str) -> list[Polyline]:
    with _CACHE_LOCK:
        cached = _CACHE.get(kind)
        if cached is not None:
            return cached
    lines: list[Polyline] = []
    if kind == "outline":
        path = _find("india_outline.bin")
        if path is not None:
            lines = decode_binary_polylines(path.read_bytes())
    else:
        path = _find(f"{kind}-borders.txt")
        if path is not None:
            lines = decode_text_polylines(path.read_text(encoding="utf-8"))
        else:
            path = _find(f"{kind}_borders.bin")
            if path is not None:
                lines = decode_binary_polylines(path.read_bytes())
    with _CACHE_LOCK:
        _CACHE[kind] = lines
    return lines


def india_rings() -> list[Polyline]:
    """The coastline and the international frontier, as rings of (longitude, latitude)."""
    return _load("outline")


def state_borders() -> list[Polyline]:
    """The interior edges between two states, as OPEN polylines."""
    return _load("state")


def district_borders() -> list[Polyline]:
    """The interior edges between two districts of the same state, as OPEN polylines."""
    return _load("district")


def assets_available() -> bool:
    """Whether there is enough geometry to draw a map at all.

    The outline alone is enough: a country with no interior borders is a poorer figure than one
    with them, but it is still an honest map. Missing outline is not — the pins would float on a
    white rectangle with nothing to locate them against.
    """
    return bool(india_rings())


# --------------------------------------------------------------------------------------
# Drawing
# --------------------------------------------------------------------------------------

#: Nominal user-space radius of each pin, before the raster's own scale factor. The venue is
#: larger than an artisan's origin so the two are told apart without reading a single label —
#: which is the whole reason :class:`MapPointKind` exists.
_PIN_RADIUS = {
    MapPointKind.VENUE: 11.0,
    MapPointKind.ARTISAN: 7.5,
    MapPointKind.MARKET: 7.5,
    MapPointKind.OTHER: 6.5,
}

#: Draw order. A venue drawn first would be hidden under whichever artisan pin landed on the same
#: town — and at a workshop the venue and several artisans routinely share one.
_DRAW_ORDER = (MapPointKind.OTHER, MapPointKind.MARKET, MapPointKind.ARTISAN, MapPointKind.VENUE)


def _pin_colour(kind: MapPointKind, theme: ReportTheme) -> RGB:
    accent = rgb_of(theme.accent, (31, 56, 100))
    soft = rgb_of(theme.accent_soft, (47, 84, 150))
    if kind is MapPointKind.VENUE:
        return accent
    if kind is MapPointKind.MARKET:
        return mix(soft, (255, 255, 255), 0.35)
    return soft


def _clamp_to_bounds(point: MapPoint) -> tuple[float, float] | None:
    """The point's projected position, or ``None`` when it is not on this map.

    A coordinate outside the drawn extent is DROPPED rather than clamped to the edge. Clamping
    would put a pin on the coastline for a latitude that is not in India at all — a zero/zero
    default from a form that never captured a fix, most often — and a pin on the Konkan coast
    reads as a finding rather than as missing data.
    """
    if not (MIN_LATITUDE - 0.5 <= point.lat <= MAX_LATITUDE + 0.5):
        return None
    if not (MIN_LONGITUDE - 0.5 <= point.lon <= MAX_LONGITUDE + 0.5):
        return None
    return project(point.lon, point.lat)


class _LabelSpace:
    """Which parts of the image already carry a label.

    A raster has no clipping region and no text layout engine, so two labels drawn at the same
    place are simply drawn on top of one another and the reader sees a smear of overlapping
    strokes with no way to tell which name belonged to which pin. Refusing the second is the
    honest outcome: the pin is still there, and the accompanying prose names every place.
    """

    __slots__ = ("boxes",)

    def __init__(self) -> None:
        self.boxes: list[tuple[float, float, float, float]] = []

    def place(self, x: float, y: float, width: float, height: float) -> bool:
        box = (x - 1, y - 1, x + width + 1, y + height + 1)
        for other in self.boxes:
            if box[0] < other[2] and other[0] < box[2] and box[1] < other[3] and other[1] < box[3]:
                return False
        self.boxes.append(box)
        return True


def _tint_states(canvas: Raster, highlight: frozenset[str], land: RGB, tint: RGB) -> list[str]:
    """Flood-fill each named state, and report the ones that could not be tinted.

    The seed is the state's own capital, from ``geography.STATE_SEATS`` — a published coordinate
    that is by definition inside the state, which a computed centroid is not guaranteed to be for
    a state shaped like Maharashtra. The import is local because ``geography`` reaches for
    ``address`` and the atlas at module scope and this module is imported by both renderers.

    THE BUDGET IS THE SAFETY. The largest state is about a tenth of India's land area, so a fill
    that reaches a quarter of it has escaped through a gap between a border run and the coast —
    the two datasets differ at the frontier by up to about two kilometres, which
    ``borderGeometry.ts`` documents and is exactly the size of gap a stroke can fail to close.
    Discovering the whole region before writing any of it turns that from "the map is one solid
    block of colour, submitted to a ministry" into "one state is not tinted".
    """
    from app.services.address import canonical_state
    from app.services.geography import STATE_SEATS

    land_pixels = canvas.count_colour(land)
    if land_pixels <= 0:
        return sorted(highlight)
    budget = int(land_pixels * 0.25)
    missed: list[str] = []
    for name in sorted(highlight):
        canonical = canonical_state(name) or name
        seat = STATE_SEATS.get(canonical)
        if seat is None:
            missed.append(name)
            continue
        _seat_name, latitude, longitude = seat
        x, y = project(longitude, latitude)
        scale = canvas.width / VIEW_WIDTH
        filled = canvas.flood_fill(int(x * scale), int(y * scale), land, tint, budget)
        if not filled:
            missed.append(name)
    return missed


def _draw_scale_bar(canvas: Raster, scale: float, ink: RGB, muted: RGB) -> None:
    """A 500 km bar in the bottom-left, so a reader can judge how far apart two clusters are.

    Sized through :func:`units_per_kilometre`, which is the same function the web map's bar uses,
    so the two figures cannot disagree about how long 500 km is.
    """
    kilometres = 500.0
    length = units_per_kilometre() * kilometres * scale
    if length <= 8 or length > canvas.width * 0.6:
        return
    x = 22.0 * scale
    y = canvas.height - 26.0 * scale
    thickness = max(1.0, 2.0 * scale)
    canvas.rect(x, y, length, thickness, ink)
    canvas.rect(x, y - 3 * scale, thickness, 3 * scale + thickness, ink)
    canvas.rect(x + length - thickness, y - 3 * scale, thickness, 3 * scale + thickness, ink)
    glyph = max(1, round(scale * 1.6))
    canvas.draw_text(int(x), int(y + 5 * scale), "0", muted, glyph)
    canvas.draw_text_right(int(x + length), int(y + 5 * scale), "500 km", muted, glyph)


def render_map_png(
    block: MapBlock, theme: ReportTheme, width_px: int
) -> tuple[bytes, int, int] | None:
    """Rasterise ``block`` and return ``(png_bytes, width_px, height_px)``, or ``None``.

    ``None`` means the geometry is not on this machine — see :func:`_asset_dirs`. It is not an
    error condition and must not be turned into one by a caller: the renderers drop the figure and
    record a warning, and the report is generated without it.

    A block with NO POINTS still renders. An empty map of the workshop's state is a perfectly
    ordinary thing for a report whose participants all came from villages the place table cannot
    resolve, and returning nothing in that case would silently remove a figure the template asked
    for — which reads as a rendering fault rather than as an absence of data.
    """
    rings = india_rings()
    if not rings:
        return None

    width = max(240, min(2400, int(width_px)))
    scale = width / VIEW_WIDTH
    height = max(1, round(VIEW_HEIGHT * scale))

    paper = (255, 255, 255)
    ink = rgb_of(theme.ink, (27, 27, 27))
    muted = rgb_of(theme.muted, (90, 107, 135))
    rule = rgb_of(theme.rule, (184, 196, 217))
    accent = rgb_of(theme.accent, (31, 56, 100))
    # The land is a very light wash of the theme's own accent rather than a fixed grey, so the map
    # belongs to the same document as the tables above it under all four template themes.
    land = mix(paper, accent, 0.10)
    tint = mix(paper, accent, 0.30)

    canvas = Raster(width, height, paper)

    projected_rings = [
        [
            (project(longitude, latitude)[0] * scale, project(longitude, latitude)[1] * scale)
            for longitude, latitude in ring
        ]
        for ring in rings
    ]
    # ONE call with every ring, not one call per ring. Even-odd filling is what makes the single
    # interior ring a hole; filling each ring on its own would paint the hole in solid, and the
    # hole is real geometry rather than a defect in the source.
    canvas.fill_polygons(projected_rings, land)

    border_width = max(0.7, 1.15 * scale)
    for line in state_borders():
        canvas.stroke_polyline(
            [(project(lon, lat)[0] * scale, project(lon, lat)[1] * scale) for lon, lat in line],
            rule,
            border_width,
        )

    missed = _tint_states(canvas, block.highlight, land, tint) if block.highlight else []

    # The frontier is stroked LAST of the line work and from the outline rather than from the
    # border files, because the border generator drops every edge that appears once — the coast
    # and the international frontier — on purpose. Drawing it from ``indiaGeometry``'s rings is
    # what makes the national boundary identical at every level of detail by construction rather
    # than by hoping two published datasets agree. They do not: they differ by up to about 2 km.
    coast_width = max(0.8, 1.5 * scale)
    for ring in projected_rings:
        if len(ring) >= 2:
            canvas.stroke_polyline([*ring, ring[0]], mix(accent, paper, 0.25), coast_width)

    _draw_scale_bar(canvas, scale, ink, muted)

    labels = _LabelSpace()
    glyph = max(1, round(scale * 1.9))
    label_h = text_height(glyph)

    points = [p for p in block.points if _clamp_to_bounds(p) is not None]
    points.sort(key=lambda p: _DRAW_ORDER.index(p.kind) if p.kind in _DRAW_ORDER else 0)

    for point in points:
        position = _clamp_to_bounds(point)
        if position is None:  # pragma: no cover - filtered above; kept for the type checker
            continue
        x = position[0] * scale
        y = position[1] * scale
        radius = _PIN_RADIUS.get(point.kind, 6.5) * scale * 0.5
        colour = _pin_colour(point.kind, theme)
        # A white ring under every pin. Two pins in neighbouring districts otherwise merge into
        # one blob at this scale, and a reader counts three origins where there were five.
        canvas.disc(x, y, radius + max(1.0, 1.2 * scale), paper)
        canvas.disc(x, y, radius, colour)
        if point.kind is MapPointKind.VENUE:
            canvas.disc(x, y, radius * 0.42, paper)

        text = point.label
        if point.count > 1:
            text = f"{text} ({point.count})"
        text = ellipsise(text, int(width * 0.32), glyph)
        if not text:
            continue
        w = text_width(text, glyph)
        gap = radius + 3 * scale
        for left, top in (
            (x + gap, y - label_h / 2),
            (x - gap - w, y - label_h / 2),
            (x - w / 2, y + gap),
            (x - w / 2, y - gap - label_h),
        ):
            if left < 2 or left + w > width - 2 or top < 2 or top + label_h > height - 2:
                continue
            if labels.place(left, top, w, label_h):
                # A halo of paper behind the text, for the same reason the pin has one: five-by-
                # seven strokes over a stroked border are unreadable, and the label is the only
                # thing that says which cluster this is.
                canvas.rect(
                    left - scale, top - scale, w + 2 * scale, label_h + 2 * scale, paper, 0.82
                )
                canvas.draw_text(
                    int(left),
                    int(top),
                    text,
                    ink if point.kind is MapPointKind.VENUE else muted,
                    glyph,
                )
                break

    if missed:
        # Said on the figure rather than swallowed. A state the template asked to tint and that
        # this map did not tint is a difference a reader would otherwise attribute to the data.
        note = ellipsise("Not tinted: " + ", ".join(missed), int(width * 0.9), max(1, glyph - 1))
        canvas.draw_text(int(6 * scale), int(6 * scale), note, muted, max(1, glyph - 1))

    return canvas.to_png(), width, height
