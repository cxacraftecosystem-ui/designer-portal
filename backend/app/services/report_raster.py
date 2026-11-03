from __future__ import annotations

import struct
import zlib

RGB = tuple[int, int, int]


RENDER_DPI = 200.0
PIXELS_PER_MM = RENDER_DPI / 25.4


def pixels_for_mm(millimetres: float) -> int:
    return max(240, min(2400, round(millimetres * PIXELS_PER_MM)))


_FONT_ASCII = (
    "0000000000"
    "00005f0000"
    "0007000700"
    "147f147f14"
    "242a7f2a12"
    "2313086462"
    "3649552250"
    "0005030000"
    "001c224100"
    "0041221c00"
    "14083e0814"
    "08083e0808"
    "0050300000"
    "0808080808"
    "0060600000"
    "2010080402"
    "3e5149453e"
    "00427f4000"
    "4261514946"
    "2141454b31"
    "1814127f10"
    "2745454539"
    "3c4a494930"
    "0171090503"
    "3649494936"
    "064949291e"
    "0036360000"
    "0056360000"
    "0814224100"
    "1414141414"
    "0041221408"
    "0201510906"
    "324979413e"
    "7e1111117e"
    "7f49494936"
    "3e41414122"
    "7f4141221c"
    "7f49494941"
    "7f09090901"
    "3e4149497a"
    "7f0808087f"
    "00417f4100"
    "2040413f01"
    "7f08142241"
    "7f40404040"
    "7f020c027f"
    "7f0408107f"
    "3e4141413e"
    "7f09090906"
    "3e4151215e"
    "7f09192946"
    "4649494931"
    "01017f0101"
    "3f4040403f"
    "1f2040201f"
    "3f4038403f"
    "6314081463"
    "0708700807"
    "6151494543"
    "007f414100"
    "0204081020"
    "0041417f00"
    "0402010204"
    "4040404040"
    "0001020400"
    "2054545478"
    "7f48444438"
    "3844444420"
    "384444487f"
    "3854545418"
    "087e090102"
    "1864646438"
    "7f08040478"
    "00447d4000"
    "2040443d00"
    "7f10284400"
    "00417f4000"
    "7c04180478"
    "7c08040478"
    "3844444438"
    "7c14141408"
    "081414187c"
    "7c08040408"
    "4854545420"
    "043f444020"
    "3c4040207c"
    "1c2040201c"
    "3c4030403c"
    "4428102844"
    "0c5050503c"
    "4464544c44"
    "0008364100"
    "00007f0000"
    "0041360800"
    "1008081008"
)

GLYPH_W = 5
GLYPH_H = 7
GLYPH_ADVANCE = 6

_GLYPHS: dict[str, tuple[int, ...]] = {}
for _index in range(95):
    _hex = _FONT_ASCII[_index * 10 : (_index + 1) * 10]
    _GLYPHS[chr(32 + _index)] = tuple(int(_hex[i : i + 2], 16) for i in range(0, 10, 2))

_GLYPHS["₹"] = (0x45, 0x25, 0x15, 0x0D, 0x07)
_GLYPHS["•"] = (0x00, 0x1C, 0x1C, 0x1C, 0x00)

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
    out: list[str] = []
    for ch in text:
        replacement = _SUBSTITUTES.get(ch)
        if replacement is not None:
            out.append(replacement)
        elif ch in _GLYPHS:
            out.append(ch)
    return "".join(out)


def text_width(text: str, scale: int = 1) -> int:
    drawn = _drawable(text)
    if not drawn:
        return 0
    return (len(drawn) * GLYPH_ADVANCE - 1) * scale


def text_height(scale: int = 1) -> int:
    return GLYPH_H * scale


def ellipsise(text: str, max_width: int, scale: int = 1) -> str:
    drawn = _drawable(text)
    if text_width(drawn, scale) <= max_width:
        return drawn
    for cut in range(len(drawn) - 1, 0, -1):
        candidate = drawn[:cut].rstrip() + "..."
        if text_width(candidate, scale) <= max_width:
            return candidate
    return ""



class Raster:
    __slots__ = ("height", "pixels", "width")

    def __init__(self, width: int, height: int, background: RGB = (255, 255, 255)) -> None:
        self.width = max(1, int(width))
        self.height = max(1, int(height))
        self.pixels = bytearray(bytes(background) * (self.width * self.height))

    def blend(self, x: int, y: int, rgb: RGB, alpha: float) -> None:
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
            cover = min(row + 1.0, y + height) - max(float(row), y)
            if cover <= 0:
                continue
            self.span(row, x, x + width, rgb, alpha * min(1.0, cover))

    def fill_polygons(
        self, rings: list[list[tuple[float, float]]], rgb: RGB, alpha: float = 1.0
    ) -> None:
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
                    continue
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
                    continue
                still.append(edge)
                if top <= centre:
                    crossings.append(x_top + (centre - top) * slope)
            active = still
            if len(crossings) < 2:
                continue
            crossings.sort()
            for index in range(0, len(crossings) - 1, 2):
                self.span(row, crossings[index], crossings[index + 1], rgb, alpha)

    def stroke_polyline(
        self,
        points: list[tuple[float, float]],
        rgb: RGB,
        thickness: float = 1.0,
        alpha: float = 1.0,
    ) -> None:
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
        if half > 0.7:
            for x, y in points[1:-1]:
                self.rect(x - half, y - half, half * 2, half * 2, rgb, alpha)

    def disc(self, cx: float, cy: float, radius: float, rgb: RGB, alpha: float = 1.0) -> None:
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
                    if (
                        angle > sweep
                        and min(angle - sweep, 6.283185307179586 - angle) * distance > 0.5
                    ):
                        continue
                cover = min(1.0, outer + 0.5 - distance)
                if inner > 0:
                    cover = min(cover, distance - inner + 0.5)
                self.blend(column, row, rgb, max(0.0, min(1.0, cover)) * alpha)

    def draw_text(self, x: int, y: int, text: str, rgb: RGB, scale: int = 1) -> int:
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

    def flood_fill(self, x: int, y: int, target: RGB, replacement: RGB, limit: int) -> int:
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
        return _count_exact(self.pixels, rgb)

    def to_png(self) -> bytes:
        return encode_png(self.width, self.height, bytes(self.pixels))


def _count_exact(buffer: bytearray, rgb: tuple[int, int, int]) -> int:
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
    raw = bytearray()
    stride = width * 3
    for row in range(height):
        raw.append(0)
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



def rgb_of(hex_colour: str, fallback: RGB = (0, 0, 0)) -> RGB:
    text = (hex_colour or "").strip().lstrip("#")
    if len(text) != 6:
        return fallback
    try:
        return (int(text[0:2], 16), int(text[2:4], 16), int(text[4:6], 16))
    except ValueError:
        return fallback


def mix(a: RGB, b: RGB, amount: float) -> RGB:
    amount = max(0.0, min(1.0, amount))
    return (
        int(a[0] + (b[0] - a[0]) * amount + 0.5),
        int(a[1] + (b[1] - a[1]) * amount + 0.5),
        int(a[2] + (b[2] - a[2]) * amount + 0.5),
    )
