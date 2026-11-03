from __future__ import annotations

import hashlib
import struct
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.services import report_chart, report_map, report_raster
from app.services.report_model import (
    ChartBlock,
    ChartKind,
    MapBlock,
    MapPoint,
    MapPointKind,
    ReportTheme,
)


def dbits(value: float) -> str:
    return struct.pack(">d", float(value)).hex()


def fnv1a(data: bytes) -> str:
    digest = 0xCBF29CE484222325
    for byte in data:
        digest ^= byte
        digest = (digest * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return f"{digest:016x}"


def coordinate_digest(lines: list[list[tuple[float, float]]]) -> str:
    buffer = bytearray()
    for line in lines:
        buffer += struct.pack(">i", len(line))
        for x, y in line:
            buffer += struct.pack(">dd", x, y)
    return fnv1a(bytes(buffer))


def decode_png(data: bytes) -> tuple[int, int, int, int, bytes]:
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    offset = 8
    header: tuple[int, ...] | None = None
    payload = bytearray()
    while offset < len(data):
        (length,) = struct.unpack_from(">I", data, offset)
        tag = data[offset + 4:offset + 8]
        body = data[offset + 8:offset + 8 + length]
        (stored,) = struct.unpack_from(">I", data, offset + 8 + length)
        if zlib.crc32(tag + body) & 0xFFFFFFFF != stored:
            raise ValueError(f"bad CRC on {tag!r}")
        if tag == b"IHDR":
            header = struct.unpack(">IIBBBBB", body)
        elif tag == b"IDAT":
            payload += body
        offset += 12 + length
    if header is None:
        raise ValueError("no IHDR")
    width, height, depth, colour, compression, filtering, interlace = header
    if (depth, colour, compression, filtering, interlace) != (8, 2, 0, 0, 0):
        raise ValueError(f"unexpected IHDR {header}")
    raw = zlib.decompress(bytes(payload))
    stride = width * 3
    rows = bytearray()
    for row in range(height):
        start = row * (stride + 1)
        if raw[start] != 0:
            raise ValueError(f"row {row} uses filter {raw[start]}, not None")
        rows += raw[start + 1:start + 1 + stride]
    return width, height, depth, colour, bytes(rows)


def histogram(rgb_rows: bytes) -> list[tuple[str, int]]:
    counts: dict[bytes, int] = {}
    for index in range(0, len(rgb_rows), 3):
        pixel = rgb_rows[index:index + 3]
        counts[pixel] = counts.get(pixel, 0) + 1
    ordered = sorted(counts.items(), key=lambda item: (-item[1], item[0]))
    return [(pixel.hex(), count) for pixel, count in ordered]


THEMES: dict[str, ReportTheme] = {
    "default": ReportTheme(),
    "alt": ReportTheme(
        accent="7A3B12", accent_soft="B26B2E", ink="221A10", muted="6B5A47", rule="D9C4A8",
    ),
    "broken": ReportTheme(accent="zzz", accent_soft="", ink="#12345", muted="  1a2b3c  ", rule="x"),
}


def draw_primitives(canvas: report_raster.Raster) -> None:
    ink = (17, 34, 51)
    warm = (200, 90, 40)
    pale = report_raster.mix((255, 255, 255), ink, 0.12)

    for step in range(12):
        canvas.span(2 + step, 3.0 + step * 0.37, 60.0 - step * 0.41, ink, 0.25 + step * 0.06)

    canvas.rect(4.6, 18.3, 40.7, 12.4, warm)
    canvas.rect(50.2, 18.9, 30.1, 11.05, warm, 0.55)

    outer = [(10.0, 40.0), (95.0, 36.5), (120.0, 78.25), (60.5, 96.0), (8.0, 70.75)]
    hole = [(35.0, 55.0), (70.0, 53.0), (68.0, 74.0), (33.0, 72.0)]
    canvas.fill_polygons([outer, hole], pale)

    canvas.fill_polygons(
        [[(130.0, 40.0), (185.0, 90.0), (130.0, 90.0), (185.0, 40.0)]], (60, 120, 90), 0.8
    )

    canvas.stroke_polyline(
        [(12.5, 108.0), (60.0, 102.25), (61.0, 140.5), (150.75, 118.0), (196.0, 150.0)],
        ink, 3.6,
    )
    canvas.stroke_polyline([(12.0, 160.0), (90.0, 156.5), (140.0, 168.0)], warm, 0.5)

    canvas.disc(30.5, 185.0, 14.0, (40, 80, 160))
    canvas.disc(30.5, 185.0, 5.75, (255, 255, 255))
    canvas.disc(66.25, 186.5, 0.8, ink)

    canvas.ring(120.0, 190.0, 26.0, 15.5, (150, 40, 60))
    canvas.ring(120.0, 190.0, 26.0, 15.5, (30, 90, 140), start=-0.9, sweep=1.7)
    canvas.ring(120.0, 190.0, 26.0, 15.5, (240, 190, 60), start=2.9, sweep=0.9)
    canvas.ring(178.0, 190.0, 22.0, 0.0, (90, 60, 150), start=-1.5707963267948966, sweep=2.4)

    canvas.draw_text(4, 218, "Wg 0123 ,.;:", ink, 1)
    canvas.draw_text_centred(100, 228, "‘Kharaɡpur’ – ₹4,200", ink, 2)
    canvas.draw_text_right(196, 246, "ଓଡିଆ x2 …", warm, 1)

    canvas.rect(150.0, 210.0, 44.0, 34.0, (255, 255, 255))
    canvas.stroke_polyline(
        [(150.0, 210.0), (194.0, 210.0), (194.0, 244.0), (150.0, 244.0), (150.0, 210.0)], ink, 2.0
    )
    canvas.flood_fill(172, 227, (255, 255, 255), (250, 220, 120), 20_000)
    canvas.flood_fill(2, 250, (255, 255, 255), (255, 0, 0), 40)


CHART_CASES: list[tuple[str, ChartBlock, str, int]] = [
    ("chart_bar_plain", ChartBlock(
        kind=ChartKind.BAR,
        series=(("Sarees", 12.0), ("Stoles", 7.0), ("Yardage", 19.0), ("Dupatta", 3.0)),
        unit="pieces",
    ), "default", 640),
    ("chart_bar_negative", ChartBlock(
        kind=ChartKind.BAR,
        series=(("Q1", 4.0), ("Q2", -6.5), ("Q3", 0.0), ("Q4", 11.25)),
    ), "alt", 520),
    ("chart_bar_all_zero", ChartBlock(
        kind=ChartKind.BAR, series=(("A", 0.0), ("B", 0.0), ("C", 0.0)),
    ), "default", 400),
    ("chart_bar_huge", ChartBlock(
        kind=ChartKind.BAR,
        series=(("Material", 1234567.0), ("Wages", 987654.5), ("Dye", 1500.5)),
        unit="₹",
    ), "default", 900),
    ("chart_bar_broken_theme", ChartBlock(
        kind=ChartKind.BAR, series=(("One", 1.0), ("Two", 2.0)),
    ), "broken", 300),
    ("chart_line_plain", ChartBlock(
        kind=ChartKind.LINE,
        series=(("3 months", 40.0), ("6 months", 62.5), ("12 months", 58.0)),
        unit="units sold",
    ), "default", 700),
    ("chart_line_single", ChartBlock(
        kind=ChartKind.LINE, series=(("Only", 5.0),),
    ), "alt", 480),
    ("chart_hbar_costs", ChartBlock(
        kind=ChartKind.HORIZONTAL_BAR,
        series=(
            ("Material", 4200.0), ("Wages", 3100.0), ("Dyeing and finishing", 900.0),
            ("Packaging", 250.0), ("Transport", 175.5), ("Overheads", 0.0),
        ),
        unit="₹ per piece",
    ), "default", 760),
    ("chart_hbar_negative", ChartBlock(
        kind=ChartKind.HORIZONTAL_BAR,
        series=(("Margin", -420.0), ("Rebate", 130.0)),
    ), "alt", 340),
    ("chart_pie_plain", ChartBlock(
        kind=ChartKind.PIE,
        series=(("Accepted", 9.0), ("Revised", 5.0), ("Rejected", 2.0), ("Pending", 1.0)),
    ), "default", 800),
    ("chart_pie_dropped", ChartBlock(
        kind=ChartKind.PIE,
        series=(("Good", 6.0), ("Bad", -2.0), ("Worse", -1.0), ("Fine", 3.0)),
    ), "default", 560),
    ("chart_pie_zero_total", ChartBlock(
        kind=ChartKind.PIE, series=(("A", 0.0), ("B", 0.0)),
    ), "alt", 460),
    ("chart_donut_plain", ChartBlock(
        kind=ChartKind.DONUT,
        series=(("Under 500", 14.0), ("500-1500", 22.0), ("1500-5000", 8.0), ("Over 5000", 3.0)),
        unit="pieces",
    ), "default", 820),
    ("chart_donut_many", ChartBlock(
        kind=ChartKind.DONUT,
        series=tuple((f"Head {n}", float(n * n % 17 + 1)) for n in range(1, 15)),
    ), "alt", 600),
    ("chart_empty", ChartBlock(kind=ChartKind.BAR, series=()), "default", 380),
    ("chart_all_dropped", ChartBlock(
        kind=ChartKind.PIE, series=(("A", -1.0), ("B", -2.0)),
    ), "default", 380),
]


MAP_CASES: list[tuple[str, MapBlock, str, int]] = [
    ("map_bare", MapBlock(), "default", 320),
    ("map_points", MapBlock(
        points=(
            MapPoint("Sambalpur", 21.4669, 83.9812, MapPointKind.VENUE, 1),
            MapPoint("Barpali", 21.1833, 83.5833, MapPointKind.ARTISAN, 6),
            MapPoint("Bhubaneswar", 20.2961, 85.8245, MapPointKind.MARKET, 2),
            MapPoint("Somewhere", 28.6139, 77.2090, MapPointKind.OTHER, 1),
            MapPoint("Nowhere", 0.0, 0.0, MapPointKind.ARTISAN, 3),
        ),
    ), "default", 420),
    ("map_highlight", MapBlock(
        points=(MapPoint("Jaipur", 26.9124, 75.7873, MapPointKind.VENUE, 1),),
        highlight=frozenset({"Rajasthan", "Orissa", "Atlantis"}),
    ), "alt", 420),
]


_MM_SAMPLES = [0.0, 1.0, 12.7, 30.48, 30.4800001, 105.6, 160.0, 199.9, 304.8, 400.0, 1000.0]

_NUMBER_SAMPLES = [
    0.0, -0.0, 1.0, -1.0, 0.5, -0.5, 2.5, 3.5, 0.125, 0.005, 0.995, 12.34, 999.0, 999.5,
    1000.0, 1000.5, 1500.5, 2500.5, 12345.0, 1234567.0, 987654.5, -1234567.89, 1e12, 1e-3,
    float("nan"), float("inf"), float("-inf"),
]

_STEP_SAMPLES = [
    (0.0, 4), (1.0, 4), (3.0, 4), (7.0, 4), (10.0, 4), (23.0, 4), (99.0, 4), (100.0, 4),
    (1234.0, 4), (0.004, 4), (1e7, 4), (7.0, 0), (-3.0, 4),
]

_TEXT_SAMPLES = [
    "", "A", "Material", "3–6 months", "Artisan’s ‘work’", "₹ 4,200", "ଓଡିଆ",
    "Dyeing and finishing", "a very long category label indeed", "x×y…",
]

_STATE_SAMPLES = [
    "Rajasthan", "rajasthan", "RAJASTHAN", "Orissa", "Odisha", "Jammu & Kashmir",
    "Jammu and Kashmir", "tamil-nadu", "Tamilnadu", "Pondicherry", "New Delhi", "NCT of Delhi",
    "Atlantis", "", "   ", "Dadra & Nagar Haveli",
]

_COORD_SAMPLES = [
    (68.2060, 6.7560), (97.3940, 37.0820), (77.2090, 28.6139), (83.9812, 21.4669),
    (72.8777, 19.0760), (0.0, 0.0), (-12.5, 45.25),
]


def scalar_lines() -> list[str]:
    out: list[str] = []

    out.append(f"render_dpi\t{dbits(report_raster.RENDER_DPI)}")
    out.append(f"pixels_per_mm\t{dbits(report_raster.PIXELS_PER_MM)}")
    for mm in _MM_SAMPLES:
        out.append(f"pixels_for_mm:{dbits(mm)}\t{report_raster.pixels_for_mm(mm)}")

    for scale in (1, 2, 3):
        out.append(f"text_height:{scale}\t{report_raster.text_height(scale)}")
        for text in _TEXT_SAMPLES:
            out.append(f"text_width:{scale}:{text}\t{report_raster.text_width(text, scale)}")
            for width in (0, 20, 60, 140):
                out.append(
                    f"ellipsise:{scale}:{width}:{text}\t{report_raster.ellipsise(text, width, scale)}"
                )

    for hexed in ("1F3864", "#2F5496", "  b8c4d9  ", "zzzzzz", "12345", "", "FFFFFF", "000000"):
        out.append(f"rgb_of:{hexed}\t{report_raster.rgb_of(hexed, (7, 8, 9))}")
    for amount in (0.0, 0.1, 0.25, 0.5, 0.82, 1.0, -1.0, 2.0):
        out.append(
            f"mix:{dbits(amount)}\t{report_raster.mix((31, 56, 100), (255, 255, 255), amount)}"
        )

    for value in _NUMBER_SAMPLES:
        out.append(f"format_number:{dbits(value)}\t{report_chart.format_number(value)}")
    for span, ticks in _STEP_SAMPLES:
        out.append(f"nice_step:{dbits(span)}:{ticks}\t{dbits(report_chart._nice_step(span, ticks))}")
    for values in ([0.0], [5.0], [-3.0, 9.0], [0.4], [1e6, 2e6], [-1.0, -2.0], [0.0, 0.0]):
        low, high, step = report_chart._axis_bounds(values)
        key = ",".join(dbits(v) for v in values)
        out.append(f"axis_bounds:{key}\t{dbits(low)} {dbits(high)} {dbits(step)}")

    out.append(f"view_width\t{dbits(report_map.VIEW_WIDTH)}")
    out.append(f"view_height\t{dbits(report_map.VIEW_HEIGHT)}")
    out.append(f"padding\t{dbits(report_map.PADDING)}")
    out.append(f"longitude_scale\t{dbits(report_map.LONGITUDE_SCALE)}")
    out.append(f"units_per_degree\t{dbits(report_map.UNITS_PER_DEGREE)}")
    out.append(f"units_per_kilometre\t{dbits(report_map.units_per_kilometre())}")
    for lon, lat in _COORD_SAMPLES:
        x, y = report_map.project(lon, lat)
        out.append(f"project:{dbits(lon)}:{dbits(lat)}\t{dbits(x)} {dbits(y)}")

    for name in _STATE_SAMPLES:
        from app.services.address import canonical_state
        out.append(f"canonical_state:{name}\t{canonical_state(name) or ''}")

    for kind, lines in (
        ("outline", report_map.india_rings()),
        ("state", report_map.state_borders()),
        ("district", report_map.district_borders()),
    ):
        out.append(f"geometry_records:{kind}\t{len(lines)}")
        out.append(f"geometry_points:{kind}\t{sum(len(line) for line in lines)}")
        out.append(f"geometry_digest:{kind}\t{coordinate_digest(lines)}")

    return out



def write_lf(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="\n")


def write_case(directory: Path, name: str, png: bytes, index: list[str]) -> None:
    width, height, depth, colour, rows = decode_png(png)
    (directory / f"{name}.rgb").write_bytes(rows)
    write_lf(
        directory / f"{name}.pal",
        "".join(f"{pixel}\t{count}\n" for pixel, count in histogram(rows)),
    )
    digest = hashlib.sha256(rows).hexdigest()
    index.append(f"{name}\t{width}\t{height}\t{depth}\t{colour}\t{digest}\t{len(png)}")


def main(destination: Path) -> int:
    destination.mkdir(parents=True, exist_ok=True)
    index: list[str] = []

    canvas = report_raster.Raster(200, 260)
    draw_primitives(canvas)
    write_case(destination, "primitives", canvas.to_png(), index)

    for name, block, theme_key, width_px in CHART_CASES:
        png, _w, _h = report_chart.render_chart_png(block, THEMES[theme_key], width_px)
        write_case(destination, name, png, index)

    if not report_map.assets_available():
        print("FATAL: the boundary assets are not on this machine; the map cases cannot be dumped.")
        return 2

    for name, block, theme_key, width_px in MAP_CASES:
        rendered = report_map.render_map_png(block, THEMES[theme_key], width_px)
        if rendered is None:
            print(f"FATAL: {name} rendered None with assets present")
            return 2
        write_case(destination, name, rendered[0], index)

    write_lf(destination / "index.tsv", "".join(line + "\n" for line in index))
    write_lf(destination / "scalars.tsv", "".join(line + "\n" for line in scalar_lines()))
    print(f"wrote {len(index)} figures and their scalars to {destination}")
    return 0


if __name__ == "__main__":
    target = Path(sys.argv[1] if len(sys.argv) > 1 else "oracle-out").resolve()
    raise SystemExit(main(target))
