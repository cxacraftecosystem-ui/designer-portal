"""A pure-Python RGB raster canvas and PNG encoder, for the report's map and its charts.

WHY THIS EXISTS RATHER THAN PILLOW OR MATPLOTLIB. ``pyproject`` keeps Pillow in an *optional*
extra — the core backend install has no image library at all — and the Android client has neither.
The map and the infographics have to be produced on both surfaces, offline, from the same
description, so the only honest options were "add two heavy dependencies to the core install and
still write the Kotlin twice" or "write the two hundred lines of rasteriser once". This is the
second. Everything here is ``zlib`` and ``struct``, which is exactly what
``report_docx.probe_image_size`` already assumes on the way back in.

The output is always **8-bit truecolour PNG with no interlacing and no filtering**. That is the
one PNG shape ``probe_image_size`` reads, the one Word embeds without transcoding, and the one
ReportLab's ``ImageReader`` decodes with no optional codec. Emitting a palette or a 16-bit image
would save bytes and cost a picture that silently does not appear in the .docx.

Three things in here are less obvious than they look:

**Anti-aliasing is horizontal only.** A polygon span is measured to a fraction of a pixel across
the scanline and blended at both ends, but each output row is sampled once, at its centre. A
near-horizontal coastline therefore keeps one-pixel stair steps. At the resolution the map is
rendered (1000 px across a country, printed about 125 mm wide, so roughly 200 dpi) a one-pixel
step is 0.13 mm and invisible on paper — whereas vertical supersampling would multiply the work by
three in an interpreter, on a request a designer is standing in a field waiting for.

**Interior spans are written with a slice assignment, not a loop.** ``buffer[a:b] = colour * n``
runs in C; the per-pixel path exists only for the two fractional pixels at each end of a span.
Filling India naively, pixel by pixel, took about eight seconds; this takes a fraction of one.

**The font is a table, not a renderer.** Five columns by seven rows per glyph, ASCII only. Indic
text cannot be drawn here at all — there is no shaping engine and no Devanagari outline in a
five-by-seven cell — so :func:`draw_text` drops what it cannot draw rather than printing a row of
boxes. Every label the map and the charts place is a place name, an enum label or a number, all of
which the registry stores in Latin script; a craft's ``localName`` is never drawn onto an image,
it is printed as real text by the document renderers, which have Nirmala UI and a Noto fallback.

``backend/tests/test_report_graphics.py`` checks the PNG is one ``probe_image_size`` can read.
"""

from __future__ import annotations

import struct
import zlib

RGB = tuple[int, int, int]


# --------------------------------------------------------------------------------------
# How big a figure is
# --------------------------------------------------------------------------------------

#: Pixels per millimetre of printed width, for every figure this package rasterises.
#:
#: 200 dpi, chosen against both ends of the failure it sits between. Below about 150 the five-by-
#: seven labels break up on a laser printer and a reader cannot tell "3-6" from "36" on a chart
#: axis; above about 300 the interpreter is filling four times the pixels for a difference no
#: printer in a district office can resolve, on a request a designer is standing in a field
#: waiting for. Both renderers multiply the block's ``width_pct`` by their own text column and
#: then by this, so the .docx and the .pdf of one workshop rasterise the same figure at the same
#: size instead of each choosing — which is the same rule the rest of the model follows and the
#: reason no block carries pixels.
RENDER_DPI = 200.0
PIXELS_PER_MM = RENDER_DPI / 25.4


def pixels_for_mm(millimetres: float) -> int:
    """The pixel width a figure printed ``millimetres`` wide should be rasterised at.

    Clamped at both ends. A figure narrower than 240 px cannot carry a legible label at all, and
    one wider than 2400 px is a megabyte of PNG embedded in a document nobody will print larger
    than A4 — a sixty-figure archival report would carry sixty of them.
    """
    return max(240, min(2400, round(millimetres * PIXELS_PER_MM)))


# --------------------------------------------------------------------------------------
# The five-by-seven font
# --------------------------------------------------------------------------------------

# Columns per glyph, least significant bit at the TOP row. The classic 5x7 terminal face, which is
# in the public domain and is the smallest thing that stays legible when a 1000-pixel image is
# printed 125 mm wide. Stored as hex rather than as a nested list because 95 glyphs of five
# integers each is four hundred lines of noise in a module whose subject is not typography.
_FONT_ASCII = (
    "0000000000"  # (space)
    "00005f0000"  # !
    "0007000700"  # "
    "147f147f14"  # #
    "242a7f2a12"  # $
    "2313086462"  # %
    "3649552250"  # &
    "0005030000"  # '
    "001c224100"  # (
    "0041221c00"  # )
    "14083e0814"  # *
    "08083e0808"  # +
    "0050300000"  # ,
    "0808080808"  # -
    "0060600000"  # .
    "2010080402"  # /
    "3e5149453e"  # 0
    "00427f4000"  # 1
    "4261514946"  # 2
    "2141454b31"  # 3
    "1814127f10"  # 4
    "2745454539"  # 5
    "3c4a494930"  # 6
    "0171090503"  # 7
    "3649494936"  # 8
    "064949291e"  # 9
    "0036360000"  # :
    "0056360000"  # ;
    "0814224100"  # <
    "1414141414"  # =
    "0041221408"  # >
    "0201510906"  # ?
    "324979413e"  # @
    "7e1111117e"  # A
    "7f49494936"  # B
    "3e41414122"  # C
    "7f4141221c"  # D
    "7f49494941"  # E
    "7f09090901"  # F
    "3e4149497a"  # G
    "7f0808087f"  # H
    "00417f4100"  # I
    "2040413f01"  # J
    "7f08142241"  # K
    "7f40404040"  # L
    "7f020c027f"  # M
    "7f0408107f"  # N
    "3e4141413e"  # O
    "7f09090906"  # P
    "3e4151215e"  # Q
    "7f09192946"  # R
    "4649494931"  # S
    "01017f0101"  # T
    "3f4040403f"  # U
    "1f2040201f"  # V
    "3f4038403f"  # W
    "6314081463"  # X
    "0708700807"  # Y
    "6151494543"  # Z
    "007f414100"  # [
    "0204081020"  # backslash
    "0041417f00"  # ]
    "0402010204"  # ^
    "4040404040"  # _
    "0001020400"  # `
    "2054545478"  # a
    "7f48444438"  # b
    "3844444420"  # c
    "384444487f"  # d
    "3854545418"  # e
    "087e090102"  # f
    # g, REDRAWN one row lower than the face this table otherwise copies. The classic cell puts
    # the bowl on rows 1-4 while every other lowercase sits on rows 2-6, and at the two-pixel
    # scale a map label is drawn at the result is read as a digit: the workshop venue printed as
    # "Khara9pur" and the cost head as "Packa9in9". Aligning the bowl with 'a' and 'o' and taking
    # the tail to the last row costs nothing and makes a place name a place name.
    "1864646438"  # g
    "7f08040478"  # h
    "00447d4000"  # i
    "2040443d00"  # j
    "7f10284400"  # k
    "00417f4000"  # l
    "7c04180478"  # m
    "7c08040478"  # n
    "3844444438"  # o
    "7c14141408"  # p
    "081414187c"  # q
    "7c08040408"  # r
    "4854545420"  # s
    "043f444020"  # t
    "3c4040207c"  # u
    "1c2040201c"  # v
    "3c4030403c"  # w
    "4428102844"  # x
    "0c5050503c"  # y
    "4464544c44"  # z
    "0008364100"  # {
    "00007f0000"  # |
    "0041360800"  # }
    "1008081008"  # ~
)

#: Width and height of one glyph cell, before scaling. One column of blank separates two glyphs.
GLYPH_W = 5
GLYPH_H = 7
GLYPH_ADVANCE = 6

_GLYPHS: dict[str, tuple[int, ...]] = {}
for _index in range(95):
    _hex = _FONT_ASCII[_index * 10 : (_index + 1) * 10]
    _GLYPHS[chr(32 + _index)] = tuple(int(_hex[i : i + 2], 16) for i in range(0, 10, 2))

# The rupee sign, drawn by hand: two horizontal strokes and the descending leg. Every cost chart in
# this report is money, and a currency symbol replaced by nothing turns "₹ 4,200" into "4,200" on a
# figure whose axis then claims no unit at all.
_GLYPHS["₹"] = (0x45, 0x25, 0x15, 0x0D, 0x07)
_GLYPHS["•"] = (0x00, 0x1C, 0x1C, 0x1C, 0x00)

# Typographic characters a label routinely carries that have an exact ASCII stand-in in a
# five-by-seven cell. Substituting is strictly better than dropping: "Artisan's" reads, "Artisans"
# does not, and an en dash silently removed turns "3–6 months" into "36 months".
_SUBSTITUTES = {
    "‘": "'",
    "’": "'",
    "“": '"',
    "”": '"',
    "–": "-",
    "—": "-",
    "−": "-",
    " ": " ",
    "×": "x",
    "…": "...",
}


def _drawable(text: str) -> str:
    """``text`` reduced to the characters this font can actually draw."""
    out: list[str] = []
    for ch in text:
        replacement = _SUBSTITUTES.get(ch)
        if replacement is not None:
            out.append(replacement)
        elif ch in _GLYPHS:
            out.append(ch)
    return "".join(out)


def text_width(text: str, scale: int = 1) -> int:
    """Pixel width of ``text`` once undrawable characters are removed."""
    drawn = _drawable(text)
    if not drawn:
        return 0
    return (len(drawn) * GLYPH_ADVANCE - 1) * scale


def text_height(scale: int = 1) -> int:
    return GLYPH_H * scale


def ellipsise(text: str, max_width: int, scale: int = 1) -> str:
    """``text`` shortened with a trailing ellipsis until it fits ``max_width`` pixels.

    A label that overruns its cell is not a cosmetic problem on a raster: there is no clipping
    region here, so it would be drawn straight over the neighbouring bar's number and the reader
    would see two figures overlapping with no way to tell which belonged to which.
    """
    drawn = _drawable(text)
    if text_width(drawn, scale) <= max_width:
        return drawn
    for cut in range(len(drawn) - 1, 0, -1):
        candidate = drawn[:cut].rstrip() + "..."
        if text_width(candidate, scale) <= max_width:
            return candidate
    return ""


# --------------------------------------------------------------------------------------
# The canvas
# --------------------------------------------------------------------------------------


class Raster:
    """An RGB image being drawn into, and the PNG it becomes."""

    __slots__ = ("height", "pixels", "width")

    def __init__(self, width: int, height: int, background: RGB = (255, 255, 255)) -> None:
        self.width = max(1, int(width))
        self.height = max(1, int(height))
        self.pixels = bytearray(bytes(background) * (self.width * self.height))

    # -- primitives ---------------------------------------------------------------------

    def blend(self, x: int, y: int, rgb: RGB, alpha: float) -> None:
        """Composite ``rgb`` over one pixel at ``alpha`` coverage. Out-of-bounds is a no-op."""
        if alpha <= 0.0 or x < 0 or y < 0 or x >= self.width or y >= self.height:
            return
        if alpha >= 1.0:
            offset = (y * self.width + x) * 3
            self.pixels[offset : offset + 3] = bytes(rgb)
            return
        offset = (y * self.width + x) * 3
        buf = self.pixels
        inverse = 1.0 - alpha
        buf[offset] = int(buf[offset] * inverse + rgb[0] * alpha + 0.5)
        buf[offset + 1] = int(buf[offset + 1] * inverse + rgb[1] * alpha + 0.5)
        buf[offset + 2] = int(buf[offset + 2] * inverse + rgb[2] * alpha + 0.5)

    def pixel_at(self, x: int, y: int) -> RGB:
        offset = (y * self.width + x) * 3
        return (self.pixels[offset], self.pixels[offset + 1], self.pixels[offset + 2])

    def span(self, y: int, x_from: float, x_to: float, rgb: RGB, alpha: float = 1.0) -> None:
        """Fill one scanline between two fractional x positions.

        The two end pixels are blended by how much of them the span actually covers; everything
        between is one slice assignment, which is where nearly all of the speed comes from.
        """
        if y < 0 or y >= self.height or x_to <= x_from:
            return
        x_from = max(0.0, x_from)
        x_to = min(float(self.width), x_to)
        if x_to <= x_from:
            return
        first = int(x_from)
        last = int(x_to)
        if first == last:
            self.blend(first, y, rgb, (x_to - x_from) * alpha)
            return
        self.blend(first, y, rgb, (first + 1 - x_from) * alpha)
        if last < self.width:
            self.blend(last, y, rgb, (x_to - last) * alpha)
        if last > first + 1:
            if alpha >= 1.0:
                start = (y * self.width + first + 1) * 3
                self.pixels[start : start + (last - first - 1) * 3] = bytes(rgb) * (
                    last - first - 1
                )
            else:
                for x in range(first + 1, last):
                    self.blend(x, y, rgb, alpha)

    def rect(
        self, x: float, y: float, width: float, height: float, rgb: RGB, alpha: float = 1.0
    ) -> None:
        top = max(0, int(y))
        bottom = min(self.height, int(y + height + 0.999))
        for row in range(top, bottom):
            # Vertical coverage of this row by the rectangle, so a bar whose top lands mid-pixel
            # does not jump a whole pixel when its value changes by one unit.
            cover = min(row + 1.0, y + height) - max(float(row), y)
            if cover <= 0:
                continue
            self.span(row, x, x + width, rgb, alpha * min(1.0, cover))

    # -- polygons -----------------------------------------------------------------------

    def fill_polygons(
        self, rings: list[list[tuple[float, float]]], rgb: RGB, alpha: float = 1.0
    ) -> None:
        """Even-odd scanline fill of any number of closed rings, in ONE pass.

        All rings are filled together rather than one at a time, and that is what makes a hole a
        hole: the outline of India carries 307 polygons and one interior ring, and filling each
        ring separately would paint the hole in solid.

        Edges are bucketed by their first scanline and an active list is carried down the image,
        so the cost is proportional to the number of crossings rather than to rings × scanlines.
        """
        buckets: dict[int, list[tuple[float, float, float, float]]] = {}
        y_min, y_max = self.height, 0
        for ring in rings:
            count = len(ring)
            if count < 3:
                continue
            for index in range(count):
                x0, y0 = ring[index]
                x1, y1 = ring[(index + 1) % count]
                if y0 == y1:
                    continue  # a horizontal edge crosses no scanline centre
                if y0 > y1:
                    x0, y0, x1, y1 = x1, y1, x0, y0
                first = max(int(y0 + 0.5), 0)
                if first >= self.height or y1 <= 0:
                    continue
                buckets.setdefault(first, []).append((y0, y1, x0, (x1 - x0) / (y1 - y0)))
                y_min = min(y_min, first)
                y_max = max(y_max, min(self.height - 1, int(y1 + 0.5)))

        if y_min > y_max:
            return

        active: list[tuple[float, float, float, float]] = []
        for row in range(y_min, y_max + 1):
            active.extend(buckets.pop(row, ()))
            centre = row + 0.5
            crossings: list[float] = []
            still: list[tuple[float, float, float, float]] = []
            for edge in active:
                top, bottom, x_top, slope = edge
                if bottom <= centre:
                    continue  # finished above this scanline; drop it
                still.append(edge)
                if top <= centre:
                    crossings.append(x_top + (centre - top) * slope)
            active = still
            if len(crossings) < 2:
                continue
            crossings.sort()
            for index in range(0, len(crossings) - 1, 2):
                self.span(row, crossings[index], crossings[index + 1], rgb, alpha)

    # -- strokes ------------------------------------------------------------------------

    def stroke_polyline(
        self,
        points: list[tuple[float, float]],
        rgb: RGB,
        thickness: float = 1.0,
        alpha: float = 1.0,
    ) -> None:
        """Draw an OPEN polyline of the given thickness.

        Each segment becomes a quadrilateral fed through the polygon filler, which is what gives
        the line the same anti-aliasing the fills have. Never closed: a state border is a run
        between two junctions, not a ring, and closing one would draw a straight segment from its
        end back to its start — a line straight across the country.
        """
        half = max(0.35, thickness / 2.0)
        for index in range(len(points) - 1):
            x0, y0 = points[index]
            x1, y1 = points[index + 1]
            dx, dy = x1 - x0, y1 - y0
            length = (dx * dx + dy * dy) ** 0.5
            if length < 1e-9:
                continue
            nx, ny = -dy / length * half, dx / length * half
            self.fill_polygons(
                [[(x0 + nx, y0 + ny), (x1 + nx, y1 + ny), (x1 - nx, y1 - ny), (x0 - nx, y0 - ny)]],
                rgb,
                alpha,
            )
        # A butt-ended segment leaves a notch at every bend, and at a bend sharper than a right
        # angle the notch is a visible hole in the border. A square at each interior vertex is the
        # cheapest join that closes it.
        if half > 0.7:
            for x, y in points[1:-1]:
                self.rect(x - half, y - half, half * 2, half * 2, rgb, alpha)

    def disc(self, cx: float, cy: float, radius: float, rgb: RGB, alpha: float = 1.0) -> None:
        """A filled circle, anti-aliased by exact horizontal extent per scanline."""
        if radius <= 0:
            return
        top = max(0, int(cy - radius))
        bottom = min(self.height, int(cy + radius) + 1)
        for row in range(top, bottom):
            dy = row + 0.5 - cy
            if abs(dy) >= radius:
                continue
            half = (radius * radius - dy * dy) ** 0.5
            self.span(row, cx - half, cx + half, rgb, alpha)

    def ring(
        self,
        cx: float,
        cy: float,
        outer: float,
        inner: float,
        rgb: RGB,
        start: float = 0.0,
        sweep: float = 6.283185307179586,
        alpha: float = 1.0,
    ) -> None:
        """An annular sector — the primitive both the pie and the donut are made of.

        ``inner`` of zero gives a pie slice. Drawn by testing each pixel's radius and angle rather
        than by tessellating the arc, because a tessellated arc with too few segments shows flat
        spots on the rim and with too many costs more than the test does.
        """
        if outer <= 0 or sweep <= 0:
            return
        import math

        top = max(0, int(cy - outer) - 1)
        bottom = min(self.height, int(cy + outer) + 2)
        left = max(0, int(cx - outer) - 1)
        right = min(self.width, int(cx + outer) + 2)
        full = sweep >= 6.283185307179585
        for row in range(top, bottom):
            dy = row + 0.5 - cy
            for column in range(left, right):
                dx = column + 0.5 - cx
                distance = (dx * dx + dy * dy) ** 0.5
                if distance > outer + 0.5 or distance < inner - 0.5:
                    continue
                if not full:
                    angle = (math.atan2(dy, dx) - start) % 6.283185307179586
                    # Outside the sweep, and further outside than the half pixel the two straight
                    # radial edges are feathered by — without the feather a thin slice comes out
                    # with a hard staircase along its own boundary.
                    if (
                        angle > sweep
                        and min(angle - sweep, 6.283185307179586 - angle) * distance > 0.5
                    ):
                        continue
                # Coverage from the two curved edges only; good enough at this radius and much
                # cheaper than supersampling a disc.
                cover = min(1.0, outer + 0.5 - distance)
                if inner > 0:
                    cover = min(cover, distance - inner + 0.5)
                self.blend(column, row, rgb, max(0.0, min(1.0, cover)) * alpha)

    # -- text ---------------------------------------------------------------------------

    def draw_text(self, x: int, y: int, text: str, rgb: RGB, scale: int = 1) -> int:
        """Draw ``text`` with its TOP-LEFT at ``(x, y)``; returns the width drawn."""
        drawn = _drawable(text)
        if not drawn:
            return 0
        cursor = x
        for character in drawn:
            columns = _GLYPHS.get(character)
            if columns:
                for column_index, bits in enumerate(columns):
                    if not bits:
                        continue
                    for row_index in range(GLYPH_H):
                        if bits & (1 << row_index):
                            self.rect(
                                cursor + column_index * scale,
                                y + row_index * scale,
                                scale,
                                scale,
                                rgb,
                            )
            cursor += GLYPH_ADVANCE * scale
        return cursor - x - scale

    def draw_text_centred(self, cx: int, y: int, text: str, rgb: RGB, scale: int = 1) -> None:
        self.draw_text(cx - text_width(text, scale) // 2, y, text, rgb, scale)

    def draw_text_right(self, right: int, y: int, text: str, rgb: RGB, scale: int = 1) -> None:
        self.draw_text(right - text_width(text, scale), y, text, rgb, scale)

    # -- flood fill ---------------------------------------------------------------------

    def flood_fill(self, x: int, y: int, target: RGB, replacement: RGB, limit: int) -> int:
        """Replace the connected run of ``target`` pixels reaching ``(x, y)``, or nothing at all.

        NOTHING IS WRITTEN UNTIL THE WHOLE REGION IS KNOWN, and the region is abandoned when it
        exceeds ``limit`` pixels. That is the entire safety of highlighting a state on this map.
        The state borders are derived from a district source whose outer extent differs from the
        national outline's by up to about two kilometres, so a border run can stop just short of
        the coast; if it does, the fill escapes through the gap and colours the whole subcontinent
        in the highlight colour, in a report submitted to a ministry. Discovering first and
        committing second turns that from "the map is wrong" into "one state is not tinted".
        """
        if x < 0 or y < 0 or x >= self.width or y >= self.height:
            return 0
        if self.pixel_at(x, y) != target or target == replacement:
            return 0

        width, height = self.width, self.height
        buf = self.pixels
        seen = bytearray(width * height)
        found: list[int] = []
        stack = [y * width + x]
        seen[stack[0]] = 1
        target_bytes = bytes(target)
        while stack:
            index = stack.pop()
            offset = index * 3
            if buf[offset : offset + 3] != target_bytes:
                continue
            found.append(index)
            if len(found) > limit:
                return 0
            row, column = divmod(index, width)
            if column > 0 and not seen[index - 1]:
                seen[index - 1] = 1
                stack.append(index - 1)
            if column + 1 < width and not seen[index + 1]:
                seen[index + 1] = 1
                stack.append(index + 1)
            if row > 0 and not seen[index - width]:
                seen[index - width] = 1
                stack.append(index - width)
            if row + 1 < height and not seen[index + width]:
                seen[index + width] = 1
                stack.append(index + width)

        replacement_bytes = bytes(replacement)
        for index in found:
            offset = index * 3
            buf[offset : offset + 3] = replacement_bytes
        return len(found)

    def count_colour(self, rgb: RGB) -> int:
        """How many pixels are exactly this colour — used to size a flood-fill budget."""
        return _count_exact(self.pixels, rgb)

    # -- output -------------------------------------------------------------------------

    def to_png(self) -> bytes:
        """Encode as an 8-bit truecolour PNG.

        Filter type 0 on every row. A real encoder would try the five filters per row and keep the
        smallest; the maps and charts here are large flats and thin strokes, which Deflate already
        compresses well, and the filter search would cost more interpreter time than the bytes it
        saves are worth on a file that is embedded once.
        """
        return encode_png(self.width, self.height, bytes(self.pixels))


def _count_exact(buffer: bytearray, rgb: tuple[int, int, int]) -> int:
    """Count pixels equal to ``rgb``, without allocating a copy of the image.

    ``bytes.count`` cannot be used directly: it counts overlapping byte *sequences*, so a run of
    two land pixels contains a spurious match starting at the green channel of the first. Stepping
    the search by three keeps it on pixel boundaries.
    """
    needle = bytes(rgb)
    total = 0
    start = 0
    view = bytes(buffer)
    while True:
        found = view.find(needle, start)
        if found < 0:
            return total
        if found % 3 == 0:
            total += 1
            start = found + 3
        else:
            start = found + 1


def encode_png(width: int, height: int, rgb_rows: bytes) -> bytes:
    """A PNG from raw RGB bytes — the same handful of chunks ``tests`` builds by hand."""
    raw = bytearray()
    stride = width * 3
    for row in range(height):
        raw.append(0)  # filter type: None
        raw += rgb_rows[row * stride : (row + 1) * stride]

    def chunk(tag: bytes, data: bytes) -> bytes:
        payload = tag + data
        return (
            struct.pack(">I", len(data))
            + payload
            + struct.pack(">I", zlib.crc32(payload) & 0xFFFFFFFF)
        )

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(bytes(raw), 6))
        + chunk(b"IEND", b"")
    )


# --------------------------------------------------------------------------------------
# Colour helpers
# --------------------------------------------------------------------------------------


def rgb_of(hex_colour: str, fallback: RGB = (0, 0, 0)) -> RGB:
    """``"1F3864"`` -> ``(31, 56, 100)``. A theme colour that is not six hex digits degrades."""
    text = (hex_colour or "").strip().lstrip("#")
    if len(text) != 6:
        return fallback
    try:
        return (int(text[0:2], 16), int(text[2:4], 16), int(text[4:6], 16))
    except ValueError:
        return fallback


def mix(a: RGB, b: RGB, amount: float) -> RGB:
    """Linear mix, ``amount`` of ``b`` into ``a``."""
    amount = max(0.0, min(1.0, amount))
    return (
        int(a[0] + (b[0] - a[0]) * amount + 0.5),
        int(a[1] + (b[1] - a[1]) * amount + 0.5),
        int(a[2] + (b[2] - a[2]) * amount + 0.5),
    )
