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

MIN_LONGITUDE = 68.2060
MAX_LONGITUDE = 97.3940
MIN_LATITUDE = 6.7560
MAX_LATITUDE = 37.0820

VIEW_WIDTH = 1000.0
PADDING = 24.0

_MID_LATITUDE = (MIN_LATITUDE + MAX_LATITUDE) / 2
LONGITUDE_SCALE = math.cos(math.radians(_MID_LATITUDE))

_SPAN_X = (MAX_LONGITUDE - MIN_LONGITUDE) * LONGITUDE_SCALE
_SPAN_Y = MAX_LATITUDE - MIN_LATITUDE

UNITS_PER_DEGREE = (VIEW_WIDTH - PADDING * 2) / _SPAN_X
VIEW_HEIGHT = _SPAN_Y * UNITS_PER_DEGREE + PADDING * 2


def project(longitude: float, latitude: float) -> tuple[float, float]:
    return (
        PADDING + (longitude - MIN_LONGITUDE) * LONGITUDE_SCALE * UNITS_PER_DEGREE,
        PADDING + (MAX_LATITUDE - latitude) * UNITS_PER_DEGREE,
    )


def units_per_kilometre() -> float:
    return UNITS_PER_DEGREE / 111.32


_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
_VALUES = {character: index for index, character in enumerate(_ALPHABET)}
_SCALE = 1000.0

_OUTLINE_MAGIC = 0x494E4431
_BIN_HEADER = ">idddd i"

Polyline = list[tuple[float, float]]


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[3]


def _asset_dirs() -> list[Path]:
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
    for directory in _asset_dirs():
        for name in names:
            candidate = directory / name
            try:
                if candidate.is_file():
                    return candidate
            except OSError:
                continue
    return None


def decode_text_polylines(payload: str) -> list[Polyline]:
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
    return _load("outline")


def state_borders() -> list[Polyline]:
    return _load("state")


def district_borders() -> list[Polyline]:
    return _load("district")


def assets_available() -> bool:
    return bool(india_rings())


_PIN_RADIUS = {
    MapPointKind.VENUE: 11.0,
    MapPointKind.ARTISAN: 7.5,
    MapPointKind.MARKET: 7.5,
    MapPointKind.OTHER: 6.5,
}

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
    if not (MIN_LATITUDE - 0.5 <= point.lat <= MAX_LATITUDE + 0.5):
        return None
    if not (MIN_LONGITUDE - 0.5 <= point.lon <= MAX_LONGITUDE + 0.5):
        return None
    return project(point.lon, point.lat)


class _LabelSpace:
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
    canvas.fill_polygons(projected_rings, land)

    border_width = max(0.7, 1.15 * scale)
    for line in state_borders():
        canvas.stroke_polyline(
            [(project(lon, lat)[0] * scale, project(lon, lat)[1] * scale) for lon, lat in line],
            rule,
            border_width,
        )

    missed = _tint_states(canvas, block.highlight, land, tint) if block.highlight else []

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
        note = ellipsise("Not tinted: " + ", ".join(missed), int(width * 0.9), max(1, glyph - 1))
        canvas.draw_text(int(6 * scale), int(6 * scale), note, muted, max(1, glyph - 1))

    return canvas.to_png(), width, height
