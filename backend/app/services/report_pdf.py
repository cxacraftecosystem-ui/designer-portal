from __future__ import annotations

import logging
import os
from collections.abc import Callable
from contextlib import contextmanager
from dataclasses import replace
from io import BytesIO
from pathlib import Path
from typing import TYPE_CHECKING

from app.services.report_model import (
    HIGHLIGHT_FILL,
    Align,
    BulletListBlock,
    CalloutBlock,
    ChartBlock,
    CoverBlock,
    HeadingBlock,
    ImageBlock,
    ImageGridBlock,
    ImageRef,
    KeyValueBlock,
    MapBlock,
    MetricRowBlock,
    PageBreakBlock,
    ParagraphBlock,
    ParaStyle,
    ReportDocument,
    Run,
    Script,
    SignatureBlock,
    SpacerBlock,
    TableBlock,
    TocBlock,
    runs_of,
    runs_text,
)
from app.services.report_raster import pixels_for_mm

if TYPE_CHECKING:
    from reportlab.lib.utils import ImageReader

logger = logging.getLogger(__name__)

PDF_MIME = "application/pdf"

MM = 72.0 / 25.4


class _Face:
    __slots__ = ("bold", "bold_index", "family", "regular", "regular_index")

    def __init__(
        self,
        family: str,
        regular: str,
        bold: str | None = None,
        regular_index: int = 0,
        bold_index: int = 0,
    ) -> None:
        self.family = family
        self.regular = regular
        self.bold = bold or regular
        self.regular_index = regular_index
        self.bold_index = bold_index


def _vera_paths() -> tuple[str, str]:
    import reportlab

    base = Path(reportlab.__file__).parent / "fonts"
    return str(base / "Vera.ttf"), str(base / "VeraBd.ttf")


def _candidates() -> list[_Face]:
    faces: list[_Face] = []
    override = os.environ.get("REPORT_PDF_FONT")
    if override:
        bold = os.environ.get("REPORT_PDF_FONT_BOLD") or override
        faces.append(_Face("ReportCustom", override, bold))
    faces += [
        _Face("NirmalaUI", "C:/Windows/Fonts/Nirmala.ttc", "C:/Windows/Fonts/NirmalaB.ttf"),
        _Face("NirmalaUI", "C:/Windows/Fonts/Nirmala.ttf", "C:/Windows/Fonts/NirmalaB.ttf"),
        _Face(
            "NotoSans",
            "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
            "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf",
        ),
        _Face(
            "DejaVuSans",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        ),
        _Face(
            "LiberationSans",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
        ),
    ]
    vera_regular, vera_bold = _vera_paths()
    faces.append(_Face("Vera", vera_regular, vera_bold))
    return faces


_NIRMALA = (
    _Face("NirmalaUI", "C:/Windows/Fonts/Nirmala.ttc", "C:/Windows/Fonts/NirmalaB.ttf"),
    _Face("NirmalaUI", "C:/Windows/Fonts/Nirmala.ttf", "C:/Windows/Fonts/NirmalaB.ttf"),
)

_NOTO_STEM = {
    Script.DEVANAGARI: "NotoSansDevanagari",
    Script.BENGALI: "NotoSansBengali",
    Script.ODIA: "NotoSansOriya",
    Script.GUJARATI: "NotoSansGujarati",
    Script.TAMIL: "NotoSansTamil",
    Script.TELUGU: "NotoSansTelugu",
    Script.KANNADA: "NotoSansKannada",
    Script.MALAYALAM: "NotoSansMalayalam",
    Script.GURMUKHI: "NotoSansGurmukhi",
}


def _script_candidates(script: Script) -> list[_Face]:
    faces: list[_Face] = []
    override = os.environ.get("REPORT_PDF_FONT_COMPLEX")
    if override and script is not Script.OTHER:
        faces.append(
            _Face(
                "ReportCustomComplex",
                override,
                os.environ.get("REPORT_PDF_FONT_COMPLEX_BOLD") or override,
            )
        )
    if script is Script.OTHER:
        faces.append(
            _Face(
                "DejaVuSans",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
            )
        )
        faces.append(_Face("DejaVuSansWin", "C:/Windows/Fonts/DejaVuSans.ttf"))
        faces.append(_Face("SegoeUISymbol", "C:/Windows/Fonts/seguisym.ttf"))
        return faces
    faces += list(_NIRMALA)
    if script is Script.DEVANAGARI:
        faces.append(_Face("Mangal", "C:/Windows/Fonts/mangal.ttf"))
    stem = _NOTO_STEM.get(script)
    if stem:
        faces.append(
            _Face(
                stem,
                f"/usr/share/fonts/truetype/noto/{stem}-Regular.ttf",
                f"/usr/share/fonts/truetype/noto/{stem}-Bold.ttf",
            )
        )
    if script is Script.DEVANAGARI:
        faces.append(
            _Face(
                "LohitDevanagari", "/usr/share/fonts/truetype/lohit-devanagari/Lohit-Devanagari.ttf"
            )
        )
    return faces


_REQUIRED_GLYPHS: tuple[tuple[str, Script, str], ...] = (
    ("₹", Script.LATIN, "the rupee sign, in every cost sheet and price"),
    ("✓", Script.OTHER, "the tick, in every checklist"),
    ("✗", Script.OTHER, "the cross, in every checklist"),
)


def _drawable(font_name: str, character: str) -> bool | None:
    from reportlab.pdfbase import pdfmetrics
    try:
        face = pdfmetrics.getFont(font_name).face
    except Exception:  # noqa: BLE001 - an unregistered name is "cannot tell", not a failure
        return None
    table = getattr(face, "charToGlyph", None)
    if not isinstance(table, dict) or not table:
        return None
    return ord(character) in table


_INDIC = frozenset(_NOTO_STEM)


class FontSet:
    def __init__(self, regular: str, bold: str, by_script: dict[Script, tuple[str, str]]) -> None:
        self.regular = regular
        self.bold = bold
        self.by_script = by_script
        self.covers_indic = bool(_INDIC & set(by_script))
        self.missing_scripts: list[Script] = sorted(
            (s for s in _INDIC if s not in by_script), key=lambda s: s.value
        )
        self.missing_glyphs: list[tuple[str, str]] = [
            (character, purpose)
            for character, script, purpose in _REQUIRED_GLYPHS
            if any(
                _drawable(self.pick_for(script, bold=bold), character) is False
                for bold in (False, True)
            )
        ]

    def pick_for(self, script: Script, *, bold: bool) -> str:
        regular, bold_name = self.by_script.get(script, (self.regular, self.bold))
        return bold_name if bold else regular

    def pick(self, run: Run) -> str:
        return self.pick_for(run.script, bold=run.bold)


_REGISTERED: set[str] = set()
_BY_FILE: dict[tuple[str, int], str] = {}


def _register_one(name: str, path: str, index: int) -> str | None:
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    already = _BY_FILE.get((path, index))
    if already is not None:
        return already
    if name in _REGISTERED:
        return name
    if not Path(path).exists():
        return None
    try:
        pdfmetrics.registerFont(TTFont(name, path, subfontIndex=index))
    except Exception:
        logger.warning("report_pdf: cannot register %s from %s", name, path, exc_info=True)
        return None
    _REGISTERED.add(name)
    _BY_FILE[(path, index)] = name
    return name


def _bind(face: _Face) -> tuple[str, str] | None:
    regular = _register_one(face.family, face.regular, face.regular_index)
    if regular is None:
        return None
    bold = _register_one(f"{face.family}-Bold", face.bold, face.bold_index)
    return regular, bold or regular


_CACHED: FontSet | None = None


def register_fonts(*, force: bool = False) -> FontSet:
    global _CACHED
    if _CACHED is not None and not force:
        return _CACHED
    if force:
        _REGISTERED.clear()
        _BY_FILE.clear()

    latin = next((r for r in (_bind(f) for f in _candidates()) if r), None)
    if latin is None:  # pragma: no cover - Vera ships with ReportLab, so this cannot happen
        logger.error(
            "report_pdf: no usable font at all, including ReportLab's own Vera; "
            "falling back to Helvetica, which cannot render the rupee sign."
        )
        latin = ("Helvetica", "Helvetica-Bold")

    by_script: dict[Script, tuple[str, str]] = {}
    for script in (*_NOTO_STEM, Script.OTHER):
        bound = next(
            (r for r in (_bind(f) for f in _script_candidates(script)) if r),
            None,
        )
        if bound is not None:
            by_script[script] = bound

    _CACHED = FontSet(latin[0], latin[1], by_script)
    if _CACHED.missing_scripts:
        logger.warning(
            "report_pdf: no font for %s. PDF exports will show boxes for text in those scripts. "
            "Install fonts-noto-core or set REPORT_PDF_FONT_COMPLEX to a font file. The .docx "
            "export is unaffected.",
            ", ".join(s.value for s in _CACHED.missing_scripts),
        )
    if _CACHED.missing_glyphs:
        logger.warning(
            "report_pdf: the bound faces cannot draw %s. PDF exports will show empty boxes there. "
            "Install fonts-noto-core and fonts-dejavu-core, or set REPORT_PDF_FONT. The .docx "
            "export is unaffected.",
            ", ".join(f"{character} ({purpose})" for character, purpose in _CACHED.missing_glyphs),
        )
    logger.info(
        "report_pdf: fonts bound regular=%s scripts=%s missing=%s",
        _CACHED.regular,
        ",".join(
            f"{s.value}:{n[0]}" for s, n in sorted(by_script.items(), key=lambda kv: kv[0].value)
        )
        or "-",
        "".join(c for c, _ in _CACHED.missing_glyphs) or "-",
    )
    return _CACHED


ImageLoader = Callable[[ImageRef], bytes | None]


def _rgb(hex_color: str) -> tuple[float, float, float]:
    h = (hex_color or "000000").lstrip("#")
    if len(h) != 6:
        h = "000000"
    return int(h[0:2], 16) / 255.0, int(h[2:4], 16) / 255.0, int(h[4:6], 16) / 255.0


VERTICAL_SCALE = 0.62
SUPERSCRIPT_RISE = 0.33
SUBSCRIPT_DROP = 0.14


class _Line:
    __slots__ = ("height", "pieces", "width")

    def __init__(
        self,
        pieces: list[tuple[str, str, float, str | None, bool, bool, float, bool]],
        width: float,
        height: float,
    ) -> None:
        self.pieces = pieces
        self.width = width
        self.height = height


class PdfRenderer:
    def __init__(self, document: ReportDocument, load_image: ImageLoader) -> None:
        self.doc = document
        self.theme = document.theme
        self.load_image = load_image
        self.fonts = register_fonts()
        self.dropped_images: list[str] = []
        self._image_cache: dict[str, ImageReader | None] = {}
        self._locked_depth = 0
        self._furniture_warned: set[str] = set()
        self._figures: dict[str, bytes] = {}
        self._figure_refs: dict[int, ImageRef | None] = {}

        page_w_mm, page_h_mm = document.meta.page_size.size_mm
        margin = document.meta.margin_mm
        self.page_w = page_w_mm * MM
        self.page_h = page_h_mm * MM
        self.margin = margin * MM
        self.text_w = self.page_w - 2 * self.margin
        self.top = self.page_h - self.margin
        self.bottom = self.margin + 10 * MM

        self.base_size = self.theme.base_size_pt
        self._total_pages = 0
        self._heading_pages: dict[str, int] = {}
        self._toc_entries: list[tuple[int, str, str, str]] = []
        self._toc_source: list[tuple[int, str, str, str]] = []

    def _string_width(self, text: str, font: str, size: float) -> float:
        from reportlab.pdfbase import pdfmetrics
        try:
            return pdfmetrics.stringWidth(text, font, size)
        except Exception:  # noqa: BLE001 - an unmapped glyph must not abort the export
            return len(text) * size * 0.5

    def _wrap(
        self, runs: tuple[Run, ...], width: float, size: float, *, leading_factor: float = 1.32
    ) -> list[_Line]:
        leading = size * leading_factor
        lines: list[_Line] = []
        cur: list[tuple[str, str, float, str | None, bool, bool, float, bool]] = []
        cur_w = 0.0

        def flush() -> None:
            nonlocal cur, cur_w
            if cur:
                lines.append(_Line(cur, cur_w, leading))
                cur = []
                cur_w = 0.0

        for run in runs:
            font = self.fonts.pick(run)
            run_size = size * VERTICAL_SCALE if (run.superscript or run.subscript) else size
            rise = (
                size * SUPERSCRIPT_RISE
                if run.superscript
                else -size * SUBSCRIPT_DROP
                if run.subscript
                else 0.0
            )
            for para_index, segment in enumerate(run.text.split("\n")):
                if para_index:
                    flush()
                if not segment:
                    continue
                tokens = segment.replace("\t", "    ").split(" ")
                for token_index, token in enumerate(tokens):
                    piece = token if token_index == len(tokens) - 1 else token + " "
                    if not piece:
                        continue
                    w = self._string_width(piece, font, run_size)
                    if cur and cur_w + w > width:
                        flush()
                    if w > width:
                        buf = ""
                        buf_w = 0.0
                        for ch in piece:
                            cw = self._string_width(ch, font, run_size)
                            if buf and buf_w + cw > width:
                                cur.append(
                                    (
                                        buf,
                                        font,
                                        run_size,
                                        run.color,
                                        run.underline,
                                        run.strike,
                                        rise,
                                        run.highlight,
                                    )
                                )
                                cur_w += buf_w
                                flush()
                                buf, buf_w = "", 0.0
                            buf += ch
                            buf_w += cw
                        if buf:
                            cur.append(
                                (
                                    buf,
                                    font,
                                    run_size,
                                    run.color,
                                    run.underline,
                                    run.strike,
                                    rise,
                                    run.highlight,
                                )
                            )
                            cur_w += buf_w
                        continue
                    cur.append(
                        (
                            piece,
                            font,
                            run_size,
                            run.color,
                            run.underline,
                            run.strike,
                            rise,
                            run.highlight,
                        )
                    )
                    cur_w += w
        flush()
        return lines or [_Line([], 0.0, leading)]

    def _new_page(self) -> None:
        if self._drawing and self._page_started:
            self._draw_furniture()
            self.c.showPage()
        self._page += 1
        self._page_started = True
        self.y = self.top
        if self._page > 1:
            self.y = self.top - 6 * MM

    def _ensure(self, height: float) -> None:
        if self._locked_depth:
            return
        if self.y - height < self.bottom:
            self._new_page()

    @contextmanager
    def _locked(self):
        self._locked_depth += 1
        try:
            yield
        finally:
            self._locked_depth -= 1

    def _fits(self, height: float) -> bool:
        return self.y - height >= self.bottom

    def _cut_row(
        self,
        columns: list[list[_Line]],
        height: float,
        padding: float,
        *,
        force: bool = False,
    ) -> tuple[list[list[_Line]], float, list[list[_Line]] | None, float] | None:
        if self._locked_depth:
            return columns, height, None, 0.0
        if self._fits(height):
            return columns, height, None, 0.0
        if height <= self.top - 6 * MM - self.bottom and not force:
            return None
        budget = self.y - self.bottom - padding
        head: list[list[_Line]] = []
        tail: list[list[_Line]] = []
        used = 0.0
        left = 0.0
        for column in columns:
            taken = 0.0
            k = 0
            while k < len(column) and taken + column[k].height <= budget:
                taken += column[k].height
                k += 1
            if force and k == 0 and column:
                taken = column[0].height
                k = 1
            head.append(column[:k])
            tail.append(column[k:])
            used = max(used, taken)
            left = max(left, sum(line.height for line in column[k:]))
        if used <= 0.0:
            return None
        if not any(tail):
            return head, used + padding, None, 0.0
        return head, used + padding, tail, left + padding

    def _draw_furniture(self) -> None:
        if self._page <= 1:
            return
        meta = self.doc.meta
        t = self.theme
        self.c.setFillColorRGB(*_rgb(t.muted))
        head_baseline = self.page_h - self.margin + 4 * MM
        if meta.header_text:
            lines = self._wrap(runs_of(meta.header_text), self.text_w, 7.8)
            kept: list[_Line] = []
            baseline = head_baseline
            for line in lines:
                if kept and baseline + line.height > self.page_h - 2 * MM:
                    break
                kept.append(line)
                baseline += line.height
            baseline = head_baseline
            for line in reversed(kept):
                self._draw_line(line, self.margin, self.text_w, Align.RIGHT, t.muted, baseline)
                baseline += line.height
            self._note_clipped_furniture("header", len(lines) - len(kept))
            self.c.setStrokeColorRGB(*_rgb(t.rule))
            self.c.setLineWidth(0.5)
            self.c.line(
                self.margin,
                self.page_h - self.margin + 2.6 * MM,
                self.page_w - self.margin,
                self.page_h - self.margin + 2.6 * MM,
            )
        foot_y = self.margin - 4 * MM
        self.c.setStrokeColorRGB(*_rgb(t.rule))
        self.c.setLineWidth(0.5)
        self.c.line(self.margin, foot_y + 4 * MM, self.page_w - self.margin, foot_y + 4 * MM)
        page_label = ""
        if meta.show_page_numbers:
            page_label = (
                f"Page {self._page} of {self._total_pages}"
                if self._total_pages
                else f"Page {self._page}"
            )
        page_w = self._string_width(page_label, self.fonts.regular, 7.8) if page_label else 0.0
        foot_w = max(self.text_w - (page_w + 4 * MM if page_label else 0.0), 20 * MM)
        if meta.footer_text:
            lines = self._wrap(runs_of(meta.footer_text), foot_w, 7.8)
            baseline = foot_y
            drawn = 0
            for line in lines:
                if drawn and baseline - line.height < 2 * MM:
                    break
                self._draw_line(line, self.margin, foot_w, Align.LEFT, t.muted, baseline)
                baseline -= line.height
                drawn += 1
            self._note_clipped_furniture("footer", len(lines) - drawn)
        if page_label:
            self.c.setFont(self.fonts.regular, 7.8)
            self.c.setFillColorRGB(*_rgb(t.muted))
            self.c.drawRightString(self.page_w - self.margin, foot_y, page_label)

    def _note_clipped_furniture(self, which: str, lines: int) -> None:
        if lines <= 0 or which in self._furniture_warned:
            return
        self._furniture_warned.add(which)
        logger.warning(
            "report_pdf: the running %s does not fit in the page margin and %d line(s) of it "
            "are not printed. Shorten it in stage 20; the .docx export is unaffected.",
            which,
            lines,
        )

    def _draw_line(
        self, line: _Line, x: float, width: float, align: Align, default_color: str, baseline: float
    ) -> None:
        if align is Align.CENTER:
            cursor = x + (width - line.width) / 2
        elif align is Align.RIGHT:
            cursor = x + width - line.width
        else:
            cursor = x
        for text, font, size, color, underline, strike, rise, highlight in line.pieces:
            piece_baseline = baseline + rise
            advance = self._string_width(text, font, size)
            if highlight:
                self.c.setFillColorRGB(*_rgb(HIGHLIGHT_FILL))
                self.c.rect(
                    cursor, piece_baseline - size * 0.24, advance, size * 1.14, stroke=0, fill=1
                )
            self.c.setFont(font, size)
            self.c.setFillColorRGB(*_rgb(color or default_color))
            self.c.drawString(cursor, piece_baseline, text)
            if (underline or strike) and text.strip():
                self.c.setStrokeColorRGB(*_rgb(color or default_color))
                self.c.setLineWidth(max(0.4, size * 0.05))
                if underline:
                    self.c.line(
                        cursor,
                        piece_baseline - size * 0.13,
                        cursor + advance,
                        piece_baseline - size * 0.13,
                    )
                if strike:
                    self.c.line(
                        cursor,
                        piece_baseline + size * 0.26,
                        cursor + advance,
                        piece_baseline + size * 0.26,
                    )
            cursor += advance

    def _draw_lines(
        self, lines: list[_Line], x: float, width: float, align: Align, default_color: str
    ) -> None:
        for line in lines:
            self._ensure(line.height)
            if self._drawing:
                self._draw_line(line, x, width, align, default_color, self.y - line.height * 0.78)
            self.y -= line.height

    def _image_reader(self, ref: ImageRef) -> ImageReader | None:
        from reportlab.lib.utils import ImageReader
        if ref.source in self._image_cache:
            return self._image_cache[ref.source]
        data = self._figures.get(ref.source) or self.load_image(ref)
        reader: ImageReader | None = None
        if data:
            try:
                reader = ImageReader(BytesIO(data))
            except Exception:  # noqa: BLE001 - an undecodable photo drops, it does not abort
                reader = None
        if reader is None:
            self.dropped_images.append(ref.source)
        self._image_cache[ref.source] = reader
        return reader

    def _image_box(
        self, ref: ImageRef, width: float, max_height: float
    ) -> tuple[float, float] | None:
        reader = self._image_reader(ref)
        if reader is None:
            return None
        try:
            iw, ih = reader.getSize()
        except Exception:  # noqa: BLE001
            iw, ih = 0, 0
        if ref.rotation_deg in (90, 270):
            iw, ih = ih, iw
        aspect = (iw / ih) if iw and ih else ref.aspect

        w = width
        h = w / aspect if aspect else width * 0.75
        if h > max_height:
            h = max_height
            w = h * aspect
        return w, h

    def _draw_image(
        self, ref: ImageRef, x: float, width: float, max_height: float, align: Align
    ) -> float:
        box = self._image_box(ref, width, max_height)
        if box is None:
            return 0.0
        reader = self._image_cache[ref.source]
        w, h = box

        self._ensure(h)
        if self._drawing:
            if align is Align.CENTER:
                dx = x + (width - w) / 2
            elif align is Align.RIGHT:
                dx = x + width - w
            else:
                dx = x
            self.c.saveState()
            if ref.rotation_deg:
                cx, cy = dx + w / 2, self.y - h / 2
                self.c.translate(cx, cy)
                self.c.rotate(-ref.rotation_deg)
                dw, dh = (h, w) if ref.rotation_deg in (90, 270) else (w, h)
                self.c.drawImage(
                    reader,
                    -dw / 2,
                    -dh / 2,
                    dw,
                    dh,
                    preserveAspectRatio=True,
                    anchor="c",
                    mask="auto",
                )
            else:
                self.c.drawImage(
                    reader, dx, self.y - h, w, h, preserveAspectRatio=True, anchor="c", mask="auto"
                )
            self.c.restoreState()
        self.y -= h
        return h

    def _caption(self, text: str, x: float, width: float) -> None:
        if not text:
            return
        self.y -= 1.4 * MM
        lines = self._wrap(runs_of(text, italic=True), width, 7.8)
        self._draw_lines(lines, x, width, Align.CENTER, self.theme.muted)
        self.y -= 2.2 * MM

    def _block_cover(self, block: CoverBlock) -> None:
        t = self.theme
        x = self.margin
        logo_h = 26 * MM if block.logo else 0.0
        org_lines = [self._wrap(runs_of(line), self.text_w, 10.5) for line in block.org_lines]
        title_lines = self._wrap(runs_of(block.title, bold=True), self.text_w, 26)
        subtitle_lines = (
            self._wrap(runs_of(block.subtitle, italic=True), self.text_w, 13)
            if block.subtitle
            else []
        )
        footer_lines = [self._wrap(runs_of(line), self.text_w, 8.6) for line in block.footer_lines]

        def total(groups: list[list[_Line]]) -> float:
            return sum(line.height for group in groups for line in group)

        info_h = 0.0
        if block.info_rows:
            label_w = self.text_w * 0.32
            for label, value in block.info_rows:
                lab = self._wrap(runs_of(label, bold=True), label_w - 3 * MM, self.base_size - 0.6)
                val = self._wrap(runs_of(value), self.text_w - label_w - 3 * MM, self.base_size)
                info_h += max(sum(ln.height for ln in lab), sum(ln.height for ln in val)) + 1.8 * MM
            info_h += 2.4 * MM

        gaps = (18 + 8 + 14 + 3 + 8 + 6) * MM
        fixed = (
            logo_h
            + total(org_lines)
            + sum(line.height for line in title_lines)
            + sum(line.height for line in subtitle_lines)
            + info_h
            + total(footer_lines)
            + gaps
        )
        available = self.top - self.bottom
        hero_h = max(0.0, min(62 * MM, available - fixed - 8 * MM)) if block.hero_image else 0.0

        with self._locked():
            self.y = self.top - 18 * MM
            if block.logo:
                self._draw_image(block.logo, x, self.text_w, logo_h, Align.CENTER)
                self.y -= 8 * MM
            for group in org_lines:
                self._draw_lines(group, x, self.text_w, Align.CENTER, t.muted)
            self.y -= 14 * MM
            self._draw_lines(title_lines, x, self.text_w, Align.CENTER, t.accent)
            self.y -= 3 * MM
            if subtitle_lines:
                self._draw_lines(subtitle_lines, x, self.text_w, Align.CENTER, t.accent_soft)
            self.y -= 8 * MM
            if hero_h > 18 * MM:
                self._draw_image(block.hero_image, x, self.text_w * 0.8, hero_h, Align.CENTER)
                self.y -= 8 * MM
            if block.info_rows:
                self._simple_grid(
                    [(label, runs_of(value)) for label, value in block.info_rows],
                    label_pct=32.0,
                )
            self.y -= 6 * MM
            for group in footer_lines:
                self._draw_lines(group, x, self.text_w, Align.CENTER, t.muted)
        self._new_page()

    def _block_toc(self, block: TocBlock) -> None:
        t = self.theme
        x = self.margin
        self._draw_lines(
            self._wrap(runs_of(block.title, bold=True), self.text_w, 16),
            x,
            self.text_w,
            Align.LEFT,
            t.accent,
        )
        self.y -= 4 * MM
        for level, number, text, bookmark in self._toc_source:
            if level > block.depth:
                continue
            indent = (level - 1) * 6 * MM
            size = 10.0 if level == 1 else 9.2
            label = f"{number}. {text}" if number else text
            page = self._heading_pages.get(bookmark)
            page_label = str(page) if page else ""
            font = self.fonts.bold if level == 1 else self.fonts.regular
            gutter = 4 * max(self._string_width(digit, font, size) for digit in "0123456789")
            avail = max(self.text_w - indent - (gutter + 4 * MM), 30 * MM)
            color = t.ink if level == 1 else t.muted
            lines = self._wrap(runs_of(label, bold=(level == 1)), avail, size, leading_factor=1.6)
            self._draw_lines(lines, x + indent, avail, Align.LEFT, color)
            if page_label and self._drawing:
                last = lines[-1]
                baseline = self.y + last.height * 0.22
                self.c.setFont(font, size)
                self.c.setFillColorRGB(*_rgb(color))
                self.c.drawRightString(x + self.text_w, baseline, page_label)
                page_w = self._string_width(page_label, font, size)
                lead_from = x + indent + last.width + 2 * MM
                lead_to = x + self.text_w - page_w - 2 * MM
                if lead_to > lead_from:
                    self.c.setFillColorRGB(*_rgb(t.rule))
                    dot_w = self._string_width(".", font, size)
                    count = max(0, int((lead_to - lead_from) / max(dot_w, 0.1)))
                    self.c.drawString(lead_from, baseline, "." * count)
        self._new_page()

    def _block_heading(self, block: HeadingBlock) -> None:
        t = self.theme
        size, color = ((17.0, t.accent), (13.5, t.accent), (11.5, t.accent_soft), (10.5, t.muted))[
            block.level - 1
        ]
        label = f"{block.number}. " if block.number else ""
        runs = runs_of(label + runs_text(block.runs), bold=True)
        lines = self._wrap(runs, self.text_w, size)
        lead = (4.5 if block.level > 1 else 6.5) * MM
        trail = (1.2 * MM if block.level == 1 else 0.0) + 2.4 * MM
        needed = lead + sum(line.height for line in lines) + trail + self.base_size * 1.32
        self._ensure(needed)
        self.y -= lead
        if not self._drawing:
            self._heading_pages[block.bookmark] = self._page
            self._toc_entries.append(
                (block.level, block.number, runs_text(block.runs), block.bookmark)
            )
        elif block.bookmark:
            self.c.bookmarkPage(block.bookmark)
            self.c.addOutlineEntry(
                f"{block.number} {runs_text(block.runs)}".strip(),
                block.bookmark,
                level=block.level - 1,
            )
        self._draw_lines(lines, self.margin, self.text_w, Align.LEFT, color)
        if block.level == 1:
            self.y -= 1.2 * MM
            if self._drawing:
                self.c.setStrokeColorRGB(*_rgb(t.rule))
                self.c.setLineWidth(0.7)
                self.c.line(self.margin, self.y, self.margin + self.text_w, self.y)
        self.y -= 2.4 * MM

    def _block_paragraph(self, block: ParagraphBlock) -> None:
        t = self.theme
        size, color, indent, italic = {
            ParaStyle.BODY: (self.base_size, t.ink, 0.0, False),
            ParaStyle.LEAD: (self.base_size + 1.4, t.ink, 0.0, False),
            ParaStyle.NOTE: (self.base_size - 1.4, t.muted, 0.0, False),
            ParaStyle.QUOTE: (self.base_size, t.accent_soft, 7 * MM, True),
            ParaStyle.CAPTION: (self.base_size - 2.0, t.muted, 0.0, True),
            ParaStyle.COVER_LINE: (self.base_size + 0.5, t.muted, 0.0, False),
        }[block.style]
        runs = block.runs
        if italic:
            runs = tuple(replace(r, italic=True) for r in runs)
        width = self.text_w - indent
        lines = self._wrap(runs, width, size)
        quote_top = self.y
        self._draw_lines(lines, self.margin + indent, width, block.align, color)
        if block.style is ParaStyle.QUOTE and self._drawing:
            self.c.setStrokeColorRGB(*_rgb(t.accent_soft))
            self.c.setLineWidth(1.6)
            self.c.line(self.margin + 2 * MM, quote_top, self.margin + 2 * MM, self.y)
        self.y -= 2.6 * MM

    def _block_bullets(self, block: BulletListBlock) -> None:
        t = self.theme
        indent = 6 * MM
        width = self.text_w - indent
        for i, item in enumerate(block.items):
            marker = f"{i + 1}." if block.ordered else "\u2022"
            lines = self._wrap(item, width, self.base_size)
            self._ensure(lines[0].height)
            if self._drawing:
                self.c.setFont(self.fonts.regular, self.base_size)
                self.c.setFillColorRGB(*_rgb(t.accent_soft))
                self.c.drawString(self.margin + 1.6 * MM, self.y - lines[0].height * 0.78, marker)
            self._draw_lines(lines, self.margin + indent, width, Align.LEFT, t.ink)
            self.y -= 1.1 * MM
        self.y -= 1.6 * MM

    def _simple_grid(
        self, pairs: list[tuple[str, tuple[Run, ...]]], *, label_pct: float = 30.0
    ) -> None:
        t = self.theme
        label_w = self.text_w * label_pct / 100
        value_w = self.text_w - label_w
        for i, (label, value) in enumerate(pairs):
            lab_lines = self._wrap(
                runs_of(label, bold=True), label_w - 3 * MM, self.base_size - 0.6
            )
            val_lines = self._wrap(value, value_w - 3 * MM, self.base_size)
            height = (
                max(sum(line.height for line in lab_lines), sum(line.height for line in val_lines))
                + 1.8 * MM
            )
            rest: list[list[_Line]] = [lab_lines, val_lines]
            rest_h = height
            turned = False
            while True:
                cut = self._cut_row(rest, rest_h, 1.8 * MM, force=turned)
                if cut is None:
                    self._new_page()
                    turned = True
                    continue
                turned = False
                head, head_h, tail, tail_h = cut
                row_top = self.y
                if self._drawing and i % 2 == 1:
                    self.c.setFillColorRGB(*_rgb(t.zebra_fill))
                    self.c.rect(
                        self.margin, row_top - head_h, self.text_w, head_h, stroke=0, fill=1
                    )
                with self._locked():
                    self.y = row_top - 0.9 * MM
                    self._draw_lines(
                        head[0], self.margin + 1.5 * MM, label_w - 3 * MM, Align.LEFT, t.muted
                    )
                    self.y = row_top - 0.9 * MM
                    self._draw_lines(
                        head[1], self.margin + label_w, value_w - 3 * MM, Align.LEFT, t.ink
                    )
                self.y = row_top - head_h
                if tail is None:
                    break
                rest, rest_h = tail, tail_h
        self.y -= 2.4 * MM

    def _block_key_values(self, block: KeyValueBlock) -> None:
        self._simple_grid(list(block.pairs), label_pct=block.label_width_pct)

    def _block_table(self, block: TableBlock) -> None:
        if not block.columns:
            return
        t = self.theme
        widths = [self.text_w * c.width_pct / 100 for c in block.columns]
        pad = 1.6 * MM
        header_size = self.base_size - 1.2
        body_size = self.base_size - 0.8

        def row_lines(
            cells: tuple[tuple[Run, ...], ...], size: float, bold: bool
        ) -> tuple[list[list[_Line]], float]:
            laid: list[list[_Line]] = []
            for j, w in enumerate(widths):
                value = cells[j] if j < len(cells) else ()
                if bold:
                    value = tuple(replace(r, bold=True) for r in value)
                laid.append(self._wrap(value, w - 2 * pad, size))
            height = max((sum(line.height for line in col) for col in laid), default=size)
            return laid, height + 1.8 * MM

        def draw_row(
            laid: list[list[_Line]],
            height: float,
            fill: str | None,
            text_color: str,
            top_rule: bool = False,
            bottom_rule: bool = True,
        ) -> None:
            top = self.y
            if self._drawing:
                if fill:
                    self.c.setFillColorRGB(*_rgb(fill))
                    self.c.rect(self.margin, top - height, self.text_w, height, stroke=0, fill=1)
                if bottom_rule:
                    self.c.setStrokeColorRGB(*_rgb(t.rule))
                    self.c.setLineWidth(0.4)
                    self.c.line(self.margin, top - height, self.margin + self.text_w, top - height)
                if top_rule:
                    self.c.setStrokeColorRGB(*_rgb(t.accent))
                    self.c.setLineWidth(0.9)
                    self.c.line(self.margin, top, self.margin + self.text_w, top)
                cx = self.margin
                for w in widths[:-1]:
                    cx += w
                    self.c.setStrokeColorRGB(*_rgb(t.rule))
                    self.c.setLineWidth(0.3)
                    self.c.line(cx, top, cx, top - height)
            with self._locked():
                cursor_x = self.margin
                for j, col in enumerate(block.columns):
                    align = Align.RIGHT if col.numeric else col.align
                    self.y = top - 0.9 * MM
                    self._draw_lines(
                        laid[j], cursor_x + pad, widths[j] - 2 * pad, align, text_color
                    )
                    cursor_x += widths[j]
            self.y = top - height

        def place_row(
            laid: list[list[_Line]],
            height: float,
            fill: str | None,
            text_color: str,
            *,
            top_rule: bool = False,
            repeat_header: bool = True,
        ) -> None:
            rest = laid
            rest_h = height
            first = True
            turned = False
            while True:
                cut = self._cut_row(rest, rest_h, 1.8 * MM, force=turned)
                if cut is None:
                    self._new_page()
                    if repeat_header:
                        place_header()
                    turned = True
                    continue
                turned = False
                head, head_h, tail, tail_h = cut
                draw_row(
                    head,
                    head_h,
                    fill,
                    text_color,
                    top_rule=top_rule and first,
                    bottom_rule=tail is None,
                )
                first = False
                if tail is None:
                    return
                rest, rest_h = tail, tail_h

        has_header = any(c.header for c in block.columns)
        header_laid, header_h = row_lines(
            tuple(runs_of(c.header) for c in block.columns), header_size, True
        )

        def place_header() -> None:
            if has_header:
                place_row(
                    header_laid,
                    header_h,
                    t.table_header_fill,
                    t.table_header_text,
                    repeat_header=False,
                )

        first_h = row_lines(block.rows[0], body_size, False)[1] if block.rows else 0.0
        if header_h + first_h > self.top - 6 * MM - self.bottom:
            first_h = body_size * 1.32 + 1.8 * MM
        if not self._fits(header_h + first_h):
            self._new_page()
        place_header()

        for i, row in enumerate(block.rows):
            laid, height = row_lines(row, body_size, False)
            place_row(laid, height, t.zebra_fill if (block.zebra and i % 2 == 1) else None, t.ink)

        if block.total_row:
            laid, height = row_lines(block.total_row, body_size, True)
            place_row(laid, height, t.zebra_fill, t.ink, top_rule=True)

        self.y -= 2.2 * MM
        self._caption(block.caption, self.margin, self.text_w)

    def _block_image(self, block: ImageBlock) -> None:
        width = self.text_w * max(5.0, min(100.0, block.width_pct)) / 100
        drawn = self._draw_image(
            block.image, self.margin, width, (self.top - self.bottom) * 0.62, block.align
        )
        if drawn:
            self._caption(block.caption, self.margin, self.text_w)
            self.y -= 1.6 * MM

    def _block_image_grid(self, block: ImageGridBlock) -> None:
        if not block.images:
            return
        columns = max(1, min(4, block.columns))
        while columns > 1 and (self.text_w / columns) < 45 * MM:
            columns -= 1
        cell_w = self.text_w / columns
        cap_size = 7.6

        max_image_h = (self.top - self.bottom) * 0.30
        for start in range(0, len(block.images), columns):
            chunk = list(block.images[start : start + columns])
            cells: list[tuple[ImageRef, float, list[_Line]]] = []
            heights: list[float] = []
            for ref, caption in chunk:
                box = self._image_box(ref, cell_w - 4 * MM, max_image_h)
                if box is None:
                    cells.append((ref, 0.0, []))
                    heights.append(0.0)
                    continue
                lines = (
                    self._wrap(runs_of(caption, italic=True), cell_w - 4 * MM, cap_size)
                    if caption
                    else []
                )
                cells.append((ref, box[1], lines))
                heights.append(box[1] + sum(line.height for line in lines) + 3 * MM)
            row_h = max(heights) if heights else 0.0
            if row_h <= 0:
                continue
            if row_h > self.top - 6 * MM - self.bottom:
                self._place_tall_grid_row(cells, cell_w, max_image_h)
                continue
            self._ensure(row_h)
            row_top = self.y
            with self._locked():
                for i, (ref, _image_h, lines) in enumerate(cells):
                    x = self.margin + i * cell_w
                    self.y = row_top
                    drawn = self._draw_image(
                        ref, x + 2 * MM, cell_w - 4 * MM, max_image_h, Align.CENTER
                    )
                    if drawn and lines:
                        self.y -= 1.2 * MM
                        self._draw_lines(
                            lines, x + 2 * MM, cell_w - 4 * MM, Align.CENTER, self.theme.muted
                        )
            self.y = row_top - row_h
        self.y -= 1.6 * MM
        self._caption(block.caption, self.margin, self.text_w)

    def _place_tall_grid_row(
        self, cells: list[tuple[ImageRef, float, list[_Line]]], cell_w: float, max_image_h: float
    ) -> None:
        gap = 1.2 * MM
        remaining = [list(lines) for _ref, _h, lines in cells]
        image_h = max((h for _ref, h, _lines in cells), default=0.0)
        first_line = max((lines[0].height for lines in remaining if lines), default=0.0)
        self._ensure(image_h + gap + first_line + 3 * MM)
        first = True
        while True:
            row_top = self.y
            room = row_top - self.bottom - 3 * MM - ((image_h + gap) if first else 0.0)
            taken: list[list[_Line]] = []
            used = 0.0
            for j, lines in enumerate(remaining):
                consumed = 0.0
                k = 0
                while k < len(lines) and consumed + lines[k].height <= room:
                    consumed += lines[k].height
                    k += 1
                if k == 0 and lines:
                    k, consumed = 1, lines[0].height
                taken.append(lines[:k])
                remaining[j] = lines[k:]
                used = max(used, consumed)
            part_h = ((image_h + gap) if first else 0.0) + used + 3 * MM
            with self._locked():
                for i, (ref, _h, _lines) in enumerate(cells):
                    x = self.margin + i * cell_w
                    self.y = row_top
                    if first and self._draw_image(
                        ref, x + 2 * MM, cell_w - 4 * MM, max_image_h, Align.CENTER
                    ):
                        self.y -= gap
                    if taken[i]:
                        self._draw_lines(
                            taken[i], x + 2 * MM, cell_w - 4 * MM, Align.CENTER, self.theme.muted
                        )
            self.y = row_top - part_h
            first = False
            if not any(remaining):
                return
            self._new_page()

    def _figure_width(self, width_pct: float) -> float:
        return self.text_w * max(20.0, min(100.0, width_pct)) / 100

    def _figure_ref(self, block: MapBlock | ChartBlock) -> ImageRef | None:
        cached = self._figure_refs.get(id(block))
        if id(block) in self._figure_refs:
            return cached

        width_px = pixels_for_mm(self._figure_width(block.width_pct) / MM)
        if isinstance(block, MapBlock):
            from app.services.report_map import render_map_png

            rendered = render_map_png(block, self.theme, width_px)
        else:
            from app.services.report_chart import render_chart_png

            rendered = render_chart_png(block, self.theme, width_px)

        ref: ImageRef | None = None
        if rendered is not None:
            png, px_w, px_h = rendered
            source = f"figure:{len(self._figures) + 1}"
            self._figures[source] = png
            ref = ImageRef(source=source, width_px=px_w, height_px=px_h, mime_type="image/png")
        self._figure_refs[id(block)] = ref
        return ref

    def _block_figure(self, block: MapBlock | ChartBlock, fallback_source: str) -> None:
        ref = self._figure_ref(block)
        if ref is None:
            if fallback_source not in self.dropped_images:
                self.dropped_images.append(fallback_source)
            return
        title = block.title
        max_h = (self.top - self.bottom) * 0.58
        figure_w = self._figure_width(block.width_pct)
        box = self._image_box(ref, figure_w, max_h)
        title_lines = (
            self._wrap(runs_of(title, bold=True), self.text_w, self.base_size) if title else []
        )
        reserve = sum(line.height for line in title_lines) + (1.2 * MM if title_lines else 0.0)
        box_h = box[1] if box else 0.0
        column_h = self.top - 6 * MM - self.bottom
        if reserve + box_h > column_h:
            if title_lines:
                self._draw_lines(
                    title_lines, self.margin, self.text_w, Align.LEFT, self.theme.accent
                )
                self.y -= 1.2 * MM
            drawn = self._draw_image(ref, self.margin, figure_w, max_h, block.align)
        else:
            self._ensure(reserve + box_h)
            with self._locked():
                if title_lines:
                    self._draw_lines(
                        title_lines, self.margin, self.text_w, Align.LEFT, self.theme.accent
                    )
                    self.y -= 1.2 * MM
                drawn = self._draw_image(ref, self.margin, figure_w, max_h, block.align)
        if drawn:
            self._caption(block.caption, self.margin, self.text_w)
            self.y -= 1.6 * MM

    def _block_map(self, block: MapBlock) -> None:
        self._block_figure(block, "map:india")

    def _block_chart(self, block: ChartBlock) -> None:
        self._block_figure(block, f"chart:{block.kind.value}")

    def _block_metrics(self, block: MetricRowBlock) -> None:
        if not block.metrics:
            return
        t = self.theme
        n = len(block.metrics)
        cell_w = self.text_w / n
        height = 16 * MM
        self._ensure(height)
        top = self.y
        if self._drawing:
            self.c.setFillColorRGB(*_rgb(t.zebra_fill))
            self.c.rect(self.margin, top - height, self.text_w, height, stroke=0, fill=1)
            for i, (label, value, unit) in enumerate(block.metrics):
                cx = self.margin + i * cell_w + cell_w / 2
                self.c.setFont(self.fonts.bold, 17)
                self.c.setFillColorRGB(*_rgb(t.accent))
                self.c.drawCentredString(cx, top - 8 * MM, value)
                if unit:
                    self.c.setFont(self.fonts.regular, 8)
                    self.c.setFillColorRGB(*_rgb(t.muted))
                    unit_x = cx + self._string_width(value, self.fonts.bold, 17) / 2 + 1.2 * MM
                    self.c.drawString(unit_x, top - 8 * MM, unit)
                self.c.setFont(self.fonts.regular, 8)
                self.c.setFillColorRGB(*_rgb(t.muted))
                self.c.drawCentredString(cx, top - 13 * MM, label)
        self.y = top - height - 3 * MM

    def _block_callout(self, block: CalloutBlock) -> None:
        t = self.theme
        fill = {"INFO": "EAF1FB", "WARNING": "FDF3E2", "SUCCESS": "EAF6EE"}.get(
            block.kind.upper(), "EAF1FB"
        )
        edge = {"INFO": t.accent_soft, "WARNING": "B7791F", "SUCCESS": "2F855A"}.get(
            block.kind.upper(), t.accent_soft
        )
        inner_w = self.text_w - 8 * MM
        title_lines = (
            self._wrap(runs_of(block.title, bold=True), inner_w, self.base_size)
            if block.title
            else []
        )
        body_lines = self._wrap(block.runs, inner_w, self.base_size - 0.6)
        height = (
            sum(line.height for line in title_lines)
            + sum(line.height for line in body_lines)
            + 6 * MM
        )
        flow = list(title_lines) + list(body_lines)
        titles_left = len(title_lines)
        rest: list[list[_Line]] = [flow]
        rest_h = height
        turned = False
        while True:
            cut = self._cut_row(rest, rest_h, 6 * MM, force=turned)
            if cut is None:
                self._new_page()
                turned = True
                continue
            turned = False
            head, head_h, tail, tail_h = cut
            part = head[0]
            top = self.y
            if self._drawing:
                self.c.setFillColorRGB(*_rgb(fill))
                self.c.rect(self.margin, top - head_h, self.text_w, head_h, stroke=0, fill=1)
                self.c.setFillColorRGB(*_rgb(edge))
                self.c.rect(self.margin, top - head_h, 1.4 * MM, head_h, stroke=0, fill=1)
            with self._locked():
                self.y = top - 3 * MM
                head_title = part[:titles_left]
                if head_title:
                    self._draw_lines(head_title, self.margin + 5 * MM, inner_w, Align.LEFT, edge)
                self._draw_lines(
                    part[len(head_title) :], self.margin + 5 * MM, inner_w, Align.LEFT, t.ink
                )
            titles_left = max(0, titles_left - len(part))
            self.y = top - head_h
            if tail is None:
                break
            rest, rest_h = tail, tail_h
        self.y -= 3 * MM

    def _block_signatures(self, block: SignatureBlock) -> None:
        if not block.signatories:
            return
        t = self.theme
        n = len(block.signatories)
        cell_w = self.text_w / n
        self._ensure(30 * MM)
        self.y -= 18 * MM
        top = self.y
        with self._locked():
            for i, (name, designation) in enumerate(block.signatories):
                x = self.margin + i * cell_w
                if self._drawing:
                    self.c.setStrokeColorRGB(*_rgb(t.ink))
                    self.c.setLineWidth(0.6)
                    self.c.line(x + 3 * MM, top, x + cell_w - 3 * MM, top)
                self.y = top - 1.5 * MM
                self._draw_lines(
                    self._wrap(runs_of(name, bold=True), cell_w - 6 * MM, 9.5),
                    x + 3 * MM,
                    cell_w - 6 * MM,
                    Align.CENTER,
                    t.ink,
                )
                self._draw_lines(
                    self._wrap(runs_of(designation), cell_w - 6 * MM, 8),
                    x + 3 * MM,
                    cell_w - 6 * MM,
                    Align.CENTER,
                    t.muted,
                )
        self.y = top - 14 * MM

    def _run_pass(self, *, drawing: bool) -> None:
        self._drawing = drawing
        self._page = 0
        self._page_started = False
        self._new_page()
        for block in self.doc.blocks:
            if isinstance(block, CoverBlock):
                self._block_cover(block)
            elif isinstance(block, TocBlock):
                self._block_toc(block)
            elif isinstance(block, HeadingBlock):
                self._block_heading(block)
            elif isinstance(block, ParagraphBlock):
                self._block_paragraph(block)
            elif isinstance(block, BulletListBlock):
                self._block_bullets(block)
            elif isinstance(block, KeyValueBlock):
                self._block_key_values(block)
            elif isinstance(block, TableBlock):
                self._block_table(block)
            elif isinstance(block, ImageBlock):
                self._block_image(block)
            elif isinstance(block, ImageGridBlock):
                self._block_image_grid(block)
            elif isinstance(block, MapBlock):
                self._block_map(block)
            elif isinstance(block, ChartBlock):
                self._block_chart(block)
            elif isinstance(block, MetricRowBlock):
                self._block_metrics(block)
            elif isinstance(block, CalloutBlock):
                self._block_callout(block)
            elif isinstance(block, SignatureBlock):
                self._block_signatures(block)
            elif isinstance(block, SpacerBlock):
                self.y -= (self.top - self.bottom) * block.height_pct / 100
            elif isinstance(block, PageBreakBlock):
                self._new_page()
            else:  # pragma: no cover - the union is exhaustive
                raise TypeError(f"Unrenderable block type {type(block).__name__}")

    def build(self) -> bytes:
        from reportlab.pdfgen import canvas as rl_canvas
        self.c = rl_canvas.Canvas(BytesIO(), pagesize=(self.page_w, self.page_h))
        previous: dict[str, int] = {}
        seen: set[frozenset[tuple[str, int]]] = set()
        for _ in range(8):
            self._toc_entries = []
            self._heading_pages = {}
            self._run_pass(drawing=False)
            self._toc_source = list(self._toc_entries)
            if self._heading_pages == previous:
                break
            signature = frozenset(self._heading_pages.items())
            if signature in seen:
                break
            seen.add(signature)
            previous = dict(self._heading_pages)

        self._toc_entries = []
        self._heading_pages = {}
        self._run_pass(drawing=False)
        self._total_pages = self._page

        buffer = BytesIO()
        meta = self.doc.meta
        self.c = rl_canvas.Canvas(buffer, pagesize=(self.page_w, self.page_h))
        self.c.setTitle(meta.title)
        self.c.setSubject(meta.subtitle)
        self.c.setAuthor(meta.author or meta.organisation)
        self.c.setCreator("Design Prototype Workshop")
        self.dropped_images = []
        self._image_cache.clear()
        self._run_pass(drawing=True)
        self._draw_furniture()
        self.c.showPage()
        self.c.save()
        if self._page != self._total_pages:
            logger.error(
                "report_pdf: the drawing pass produced %d pages and the measuring pass measured "
                "%d. The contents page numbers and the 'of %d' in the running foot describe a "
                "layout that was not drawn; something in the layout is applied on one pass and "
                "not the other.",
                self._page,
                self._total_pages,
                self._total_pages,
            )
        return buffer.getvalue()


def render_pdf(document: ReportDocument, load_image: ImageLoader) -> tuple[bytes, list[str]]:
    renderer = PdfRenderer(document, load_image)
    data = renderer.build()
    return data, sorted(set(renderer.dropped_images))
