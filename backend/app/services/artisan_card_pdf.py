"""Artisan QR cards as PDF: one ID-1 card, one A6 card, or a sheet of ID-1 cards for a workshop.

Sizes, at physical scale:

* ``id1`` — an 85.6 × 54 mm page (the credit-card size), one card.
* ``a6`` — a 105 × 148 mm page per card.
* a sheet — A4 portrait, 2 × 5 ID-1 cards edge to edge with crop marks in the margin, so a
  guillotine cut along the marks separates them.

The face prints the name and local name, the craft and cluster, ``Aadhaar XXXX XXXX 9012`` (left out
when the last four are unknown), the portal's name, an optional 22 × 28 mm photo, and the QR at 25 mm
with the short code under it. The QR is drawn as rectangles from segno's matrix — the same symbol
:func:`qr_svg` hands the web — so a module is 25 / 37 ≈ 0.68 mm (version 3, error level Q, four
modules of quiet zone). Nothing else about the artisan is printed, and the QR holds no personal data.

Photos come through :func:`load_photo` at 600 px at most; a photo that cannot be read is left out
rather than failing the print.
"""

from __future__ import annotations

import logging
from collections.abc import Sequence
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from io import BytesIO

import segno
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.lib.utils import ImageReader
from reportlab.pdfbase.pdfmetrics import stringWidth
from reportlab.pdfgen import canvas as rl_canvas

logger = logging.getLogger(__name__)

ID1 = (85.6 * mm, 54 * mm)
A6 = (105 * mm, 148 * mm)
SIZES = ("id1", "a6")

#: QR quiet zone, in modules.
QUIET_ZONE = 4
#: The printed QR square, quiet zone included.
QR_BOX_ID1 = 25 * mm
QR_BOX_A6 = 40 * mm
PHOTO_W = 22 * mm
PHOTO_H = 28 * mm
#: Longest side a photo is scaled down to before it is embedded.
PHOTO_MAX_PX = 600
#: The most of one stored photo read into memory before scaling.
PHOTO_MAX_BYTES = 8 * 1024 * 1024
#: Without Pillow a JPEG is embedded as stored, and only when it is this small.
PHOTO_RAW_MAX_BYTES = 1536 * 1024
#: Card sheet: columns × rows on A4 portrait.
SHEET_COLUMNS = 2
SHEET_ROWS = 5
SHEET_PER_PAGE = SHEET_COLUMNS * SHEET_ROWS

_INK = (0.10, 0.11, 0.13)
_MUTED = (0.38, 0.40, 0.44)
_RULE = (0.80, 0.82, 0.85)


@dataclass(frozen=True)
class CardFace:
    name: str
    local_name: str | None
    craft: str | None
    cluster: str | None
    aadhaar_last4: str | None
    token: str
    short_code: str
    photo: bytes | None = None


# ── The QR ────────────────────────────────────────────────────────────────────────────────────


def qr_matrix(token: str) -> list[list[bool]]:
    """The module matrix with its quiet zone; True is dark. Alphanumeric, error level Q."""
    code = segno.make(token, error="q", mode="alphanumeric", micro=False, boost_error=False)
    rows = [[bool(cell) for cell in row] for row in code.matrix]
    width = len(rows[0]) + 2 * QUIET_ZONE
    blank = [False] * width
    framed = [list(blank) for _ in range(QUIET_ZONE)]
    framed += [[False] * QUIET_ZONE + row + [False] * QUIET_ZONE for row in rows]
    framed += [list(blank) for _ in range(QUIET_ZONE)]
    return framed


def _runs(matrix: list[list[bool]]) -> list[tuple[int, int, int]]:
    """Each horizontal run of dark modules as ``(x, y, length)``."""
    out: list[tuple[int, int, int]] = []
    for y, row in enumerate(matrix):
        x = 0
        while x < len(row):
            if not row[x]:
                x += 1
                continue
            start = x
            while x < len(row) and row[x]:
                x += 1
            out.append((start, y, x - start))
    return out


def qr_svg(token: str) -> str:
    """The same symbol the PDF prints, as one white square and one black path (``M``/``h``/``v``/
    ``z`` only, the shape ``two_factor.qr_svg`` already hands both clients)."""
    matrix = qr_matrix(token)
    size = len(matrix)
    path = "".join(f"M{x} {y}h{n}v1h-{n}z" for x, y, n in _runs(matrix))
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}" '
        f'shape-rendering="crispEdges">'
        f'<rect width="{size}" height="{size}" fill="#ffffff"/>'
        f'<path fill="#000000" d="{path}"/>'
        "</svg>"
    )


def _draw_qr(c: rl_canvas.Canvas, token: str, x: float, y: float, box: float) -> None:
    matrix = qr_matrix(token)
    size = len(matrix)
    module = box / size
    c.setFillColorRGB(1, 1, 1)
    c.rect(x, y, box, box, stroke=0, fill=1)
    c.setFillColorRGB(0, 0, 0)
    for mx, my, n in _runs(matrix):
        # Matrix rows run top to bottom; the PDF's y axis runs up.
        c.rect(x + mx * module, y + box - (my + 1) * module, n * module, module, stroke=0, fill=1)


# ── Photos ────────────────────────────────────────────────────────────────────────────────────


def scale_photo(data: bytes) -> bytes | None:
    """``data`` as a JPEG no larger than 600 px on its longest side, or None if it can't be read."""
    try:
        from PIL import Image  # Pillow is in the lock file; the fallback below covers its absence.
    except ImportError:
        if data[:3] == b"\xff\xd8\xff" and len(data) <= PHOTO_RAW_MAX_BYTES:
            return data
        return None
    try:
        with Image.open(BytesIO(data)) as opened:
            image = opened.convert("RGB")
            image.thumbnail((PHOTO_MAX_PX, PHOTO_MAX_PX))
            out = BytesIO()
            image.save(out, format="JPEG", quality=85)
            return out.getvalue()
    except Exception:  # noqa: BLE001 - an unreadable photo is left off the card
        return None


def load_photo(object_key: str) -> bytes | None:
    """One stored photo, scaled. Blocking: call it from a thread."""
    from app.services import s3

    try:
        data = s3.get_object_bytes(object_key, max_bytes=PHOTO_MAX_BYTES)
    except Exception:  # noqa: BLE001 - missing, too large or unreachable: the card prints without it
        logger.info("artisan card: photo %s left out", type(object_key).__name__)
        return None
    return scale_photo(data)


def load_photos(object_keys: dict[str, str], *, workers: int = 4) -> dict[str, bytes]:
    """``{artisan id: object key}`` to ``{artisan id: scaled bytes}``, a few downloads at a time.
    Each photo is scaled as it arrives, so at most ``workers`` full-size files are held at once."""
    if not object_keys:
        return {}
    out: dict[str, bytes] = {}
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for artisan_id, data in zip(
            object_keys, pool.map(load_photo, object_keys.values()), strict=True
        ):
            if data:
                out[artisan_id] = data
    return out


# ── Text ──────────────────────────────────────────────────────────────────────────────────────


class _Fonts:
    def __init__(self) -> None:
        from app.services.report_pdf import register_fonts

        self.set = register_fonts()

    def runs(self, text: str, *, bold: bool) -> list[tuple[str, str]]:
        from app.services.report_model import split_by_script

        return [(span, self.set.pick_for(script, bold=bold)) for span, script in split_by_script(text)]

    def width(self, text: str, size: float, *, bold: bool) -> float:
        return sum(stringWidth(span, font, size) for span, font in self.runs(text, bold=bold))

    def fit(self, text: str, width: float, size: float, *, bold: bool, smallest: float) -> tuple[str, float]:
        """Shrink to ``smallest``, then cut with an ellipsis, until ``text`` fits ``width``."""
        while size > smallest and self.width(text, size, bold=bold) > width:
            size -= 0.25
        if self.width(text, size, bold=bold) <= width:
            return text, size
        cut = text
        while cut and self.width(cut + "…", size, bold=bold) > width:
            cut = cut[:-1]
        return (cut.rstrip() + "…") if cut else "", size

    def draw(
        self,
        c: rl_canvas.Canvas,
        text: str,
        x: float,
        y: float,
        size: float,
        *,
        bold: bool = False,
        width: float | None = None,
        smallest: float | None = None,
        centre: bool = False,
        colour: tuple[float, float, float] = _INK,
    ) -> None:
        if not text:
            return
        if width is not None:
            text, size = self.fit(text, width, size, bold=bold, smallest=smallest or size)
        if centre:
            x -= self.width(text, size, bold=bold) / 2
        c.setFillColorRGB(*colour)
        for span, font in self.runs(text, bold=bold):
            c.setFont(font, size)
            c.drawString(x, y, span)
            x += stringWidth(span, font, size)


def aadhaar_line(last4: str | None) -> str | None:
    return f"Aadhaar XXXX XXXX {last4}" if last4 else None


# ── Faces ─────────────────────────────────────────────────────────────────────────────────────


def _photo(c: rl_canvas.Canvas, data: bytes | None, x: float, y: float) -> bool:
    if not data:
        return False
    try:
        c.drawImage(
            ImageReader(BytesIO(data)), x, y, PHOTO_W, PHOTO_H,
            preserveAspectRatio=True, anchor="c", mask="auto",
        )
    except Exception:  # noqa: BLE001 - an image ReportLab can't read is left off
        return False
    c.setStrokeColorRGB(*_RULE)
    c.setLineWidth(0.3)
    c.rect(x, y, PHOTO_W, PHOTO_H, stroke=1, fill=0)
    return True


def _face_id1(
    c: rl_canvas.Canvas, fonts: _Fonts, card: CardFace, portal: str, ox: float, oy: float, *, photo: bool
) -> None:
    w, h = ID1
    pad = 3 * mm
    qr_x = ox + w - pad - QR_BOX_ID1
    qr_y = oy + h - pad - QR_BOX_ID1
    _draw_qr(c, card.token, qr_x, qr_y, QR_BOX_ID1)
    fonts.draw(
        c, card.short_code, qr_x + QR_BOX_ID1 / 2, qr_y - 3.2 * mm, 7, bold=True, centre=True
    )
    text_x = ox + pad
    if photo and _photo(c, card.photo, ox + pad, oy + h - pad - PHOTO_H):
        text_x = ox + pad + PHOTO_W + 2.5 * mm
    text_w = qr_x - 2 * mm - text_x
    y = oy + h - pad - 3.4 * mm
    fonts.draw(c, card.name, text_x, y, 9.5, bold=True, width=text_w, smallest=6.5)
    y -= 4.2 * mm
    for line, size, colour in (
        (card.local_name, 8, _INK),
        (card.craft, 7.5, _INK),
        (card.cluster, 7.5, _MUTED),
    ):
        if line:
            fonts.draw(c, line, text_x, y, size, width=text_w, smallest=5.5, colour=colour)
            y -= 3.6 * mm
    footer_w = qr_x - 2 * mm - (ox + pad)
    line = aadhaar_line(card.aadhaar_last4)
    if line:
        fonts.draw(c, line, ox + pad, oy + pad + 4 * mm, 7, bold=True, width=footer_w, smallest=5.5)
    fonts.draw(c, portal, ox + pad, oy + pad, 6, width=w - 2 * pad, smallest=5, colour=_MUTED)


def _face_a6(c: rl_canvas.Canvas, fonts: _Fonts, card: CardFace, portal: str, *, photo: bool) -> None:
    w, h = A6
    pad = 9 * mm
    fonts.draw(c, portal, w / 2, h - pad, 8, centre=True, width=w - 2 * pad, smallest=6, colour=_MUTED)
    c.setStrokeColorRGB(*_RULE)
    c.setLineWidth(0.4)
    c.line(pad, h - pad - 3 * mm, w - pad, h - pad - 3 * mm)
    top = h - pad - 7 * mm
    text_x = pad
    if photo and _photo(c, card.photo, pad, top - PHOTO_H):
        text_x = pad + PHOTO_W + 4 * mm
    text_w = w - pad - text_x
    y = top - 5 * mm
    fonts.draw(c, card.name, text_x, y, 14, bold=True, width=text_w, smallest=9)
    y -= 6 * mm
    for line, size, colour in (
        (card.local_name, 11, _INK),
        (card.craft, 10, _INK),
        (card.cluster, 10, _MUTED),
    ):
        if line:
            fonts.draw(c, line, text_x, y, size, width=text_w, smallest=7, colour=colour)
            y -= 5 * mm
    line = aadhaar_line(card.aadhaar_last4)
    below = min(y, top - PHOTO_H) - 7 * mm
    if line:
        fonts.draw(c, line, pad, below, 10, bold=True, width=w - 2 * pad, smallest=7)
    qr_x = (w - QR_BOX_A6) / 2
    qr_y = 20 * mm
    _draw_qr(c, card.token, qr_x, qr_y, QR_BOX_A6)
    fonts.draw(c, card.short_code, w / 2, qr_y - 6 * mm, 12, bold=True, centre=True)


def _crop_marks(c: rl_canvas.Canvas, left: float, bottom: float, width: float, height: float) -> None:
    """Guillotine marks in the margin at every cut line of a ``SHEET_COLUMNS`` × ``SHEET_ROWS`` grid."""
    card_w, card_h = ID1
    gap, length = 1.5 * mm, 5 * mm
    c.setStrokeColorRGB(0, 0, 0)
    c.setLineWidth(0.25)
    for column in range(SHEET_COLUMNS + 1):
        x = left + column * card_w
        c.line(x, bottom + height + gap, x, bottom + height + gap + length)
        c.line(x, bottom - gap, x, bottom - gap - length)
    for row in range(SHEET_ROWS + 1):
        y = bottom + row * card_h
        c.line(left - gap, y, left - gap - length, y)
        c.line(left + width + gap, y, left + width + gap + length, y)


def _canvas(buffer: BytesIO, pagesize: tuple[float, float]) -> rl_canvas.Canvas:
    c = rl_canvas.Canvas(buffer, pagesize=pagesize, pageCompression=1)
    c.setTitle("Artisan card")
    c.setCreator("Design Prototype Workshop")
    return c


def render_cards(cards: Sequence[CardFace], size: str, portal: str, *, photo: bool = True) -> bytes:
    """One card per page at ``size`` (``id1`` or ``a6``)."""
    buffer = BytesIO()
    pagesize = ID1 if size == "id1" else A6
    c = _canvas(buffer, pagesize)
    fonts = _Fonts()
    for card in cards:
        if size == "id1":
            _face_id1(c, fonts, card, portal, 0, 0, photo=photo)
        else:
            _face_a6(c, fonts, card, portal, photo=photo)
        c.showPage()
    c.save()
    return buffer.getvalue()


def render_sheet(cards: Sequence[CardFace], portal: str, *, photo: bool = True) -> bytes:
    """ID-1 cards on A4 portrait, ``SHEET_PER_PAGE`` to a page, with crop marks."""
    buffer = BytesIO()
    c = _canvas(buffer, A4)
    fonts = _Fonts()
    page_w, page_h = A4
    card_w, card_h = ID1
    grid_w, grid_h = SHEET_COLUMNS * card_w, SHEET_ROWS * card_h
    left, bottom = (page_w - grid_w) / 2, (page_h - grid_h) / 2
    for start in range(0, len(cards), SHEET_PER_PAGE):
        _crop_marks(c, left, bottom, grid_w, grid_h)
        for index, card in enumerate(cards[start : start + SHEET_PER_PAGE]):
            column, row = index % SHEET_COLUMNS, index // SHEET_COLUMNS
            ox = left + column * card_w
            oy = bottom + grid_h - (row + 1) * card_h
            _face_id1(c, fonts, card, portal, ox, oy, photo=photo)
        c.showPage()
    c.save()
    return buffer.getvalue()
