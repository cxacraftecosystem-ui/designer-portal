"""Renders a :class:`~app.services.report_model.ReportDocument` to a .docx, with no dependency.

A .docx is a zip of XML parts, so this module needs nothing beyond ``zipfile`` and the standard
library. That is not a stunt — it is why the *identical* algorithm runs on the phone. The
Android exporter (``report/DocxWriter.kt``) is a line-for-line port of this file against
``java.util.zip.ZipOutputStream``, which is already a proven path in the Android client, and
keeping the server side dependency-free is what makes the two provably the same document rather
than two libraries' interpretations of one intent. python-docx would have made this file
shorter and the phone's copy impossible.

Parts written, in the order Word likes to find them:

    [Content_Types].xml          every extension and every part override
    _rels/.rels                  package -> document, core properties, app properties
    word/document.xml            the body, ending in the one sectPr
    word/_rels/document.xml.rels styles, numbering, settings, header, footer, images, charts
    word/styles.xml              the theme rendered as real Word styles
    word/numbering.xml           bullet and ordered-list definitions
    word/settings.xml            updateFields, so the TOC fills itself in on open
    word/header1.xml             running head, suppressed on the cover by w:titlePg
    word/footer1.xml             running foot with PAGE / NUMPAGES fields
    word/media/image*.ext        one part per DISTINCT image
    word/charts/chart*.xml       one c:chartSpace per NATIVE chart
    word/charts/_rels/…rels      each chart -> its embedded workbook
    word/embeddings/chart*.xlsx  the numbers behind each native chart, as a real workbook
    docProps/core.xml            title, author, timestamps
    docProps/app.xml             producer

Three things in here are not obvious and each one cost a corrupt file to learn:

**Every string goes through :func:`_esc`.** ``report_model.clean_text`` has already removed the
codepoints XML cannot carry; ``_esc`` then escapes the five entities. Skipping the first makes
an unopenable file, skipping the second makes a mis-parsed one, and Word reports both as "The
file is corrupt and cannot be opened" with no indication which.

**A table must be followed by a paragraph.** Two ``w:tbl`` siblings with nothing between them
are merged by Word into one table with the second's rows appended, silently. Every table this
module writes is followed by an empty ``w:p``, and ``_validate_body`` refuses to hand back a
package where that is not true.

**Relationship ids are allocated, never guessed.** ``rId1``-``rId5`` are the five fixed parts;
images take ``rId6`` upward in ``document.images`` order. An earlier version numbered images
from their index in the block tree, so a document whose cover carried a logo produced two
relationships with the same id and Word dropped both pictures without complaint. Charts do not
join that sequence — see ``_CHART_RID_PREFIX``, which exists precisely so they cannot.

``backend/tests/test_report_docx.py`` opens the produced package, parses every part, and asserts
that every ``r:embed`` resolves — the same checks that caught all three of the above.
"""

from __future__ import annotations

import math
import struct
import zipfile
from collections.abc import Callable
from dataclasses import replace
from io import BytesIO

from app.services.report_model import (
    Align,
    BulletListBlock,
    CalloutBlock,
    ChartBlock,
    ChartKind,
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
    ReportTheme,
    Run,
    Script,
    SignatureBlock,
    SpacerBlock,
    TableBlock,
    TableColumn,
    TocBlock,
    clean_text,
    runs_of,
)
from app.services.report_raster import mix, pixels_for_mm, rgb_of

DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

# Word's unit zoo. A twip is 1/20 pt = 1/1440 in; an EMU is 1/914400 in. Both are integers in
# the file, so every conversion below rounds once, at the end, rather than accumulating.
_TWIP_PER_MM = 1440.0 / 25.4
_EMU_PER_MM = 36000.0

_ALIGN_TO_JC = {
    Align.LEFT: "left",
    Align.CENTER: "center",
    Align.RIGHT: "right",
    Align.JUSTIFY: "both",
}

# The five relationship ids used by the fixed parts. Images start after them.
_RID_STYLES, _RID_NUMBERING, _RID_SETTINGS, _RID_HEADER, _RID_FOOTER = range(1, 6)
_RID_FIRST_IMAGE = 6

_XML_DECL = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
_NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
_NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
_NS_WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
_NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
_NS_PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"


def _esc(text: str) -> str:
    """Escape the five XML entities. ``clean_text`` has already run; this is the second half."""
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
        .replace("'", "&apos;")
    )


# --------------------------------------------------------------------------------------
# Image probing — intrinsic size when the model was not told one
# --------------------------------------------------------------------------------------


def _png_size(data: bytes) -> tuple[int, int] | None:
    if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    width, height = struct.unpack(">II", data[16:24])
    return width, height


def _jpeg_size(data: bytes) -> tuple[int, int] | None:
    """Walk the JPEG marker chain to the first SOF and read its frame size.

    Reading the whole file into Pillow would be one line, but Pillow is an optional extra here
    (``pyproject`` keeps it out of the core install), and the phone has no Pillow at all — the
    Kotlin port does exactly this loop.
    """
    if len(data) < 4 or data[:2] != b"\xff\xd8":
        return None
    i, n = 2, len(data)
    while i < n - 9:
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        # Standalone markers carry no length word.
        if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
            i += 2
            continue
        if marker == 0xD9:  # EOI
            return None
        if i + 4 > n:
            return None
        seg_len = struct.unpack(">H", data[i + 2 : i + 4])[0]
        # SOF0-SOF15, excluding DHT (C4), DAC (CC) and the restart markers.
        if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
            if i + 9 > n:
                return None
            height, width = struct.unpack(">HH", data[i + 5 : i + 9])
            return width, height
        if seg_len < 2:
            return None
        i += 2 + seg_len
    return None


def probe_image_size(data: bytes) -> tuple[int, int] | None:
    """Intrinsic pixel size of a PNG or JPEG, or ``None`` when it is neither."""
    return _png_size(data) or _jpeg_size(data)


_MIME_TO_EXT = {
    "image/png": "png",
    "image/jpeg": "jpeg",
    "image/jpg": "jpeg",
    "image/webp": "png",  # never emitted; see _normalise_mime
}


def _normalise_mime(mime: str) -> str:
    """Only PNG and JPEG may be embedded.

    Word does not render WEBP or HEIC inside a document part on any version still deployed in a
    government office, and a picture that silently does not appear is worse than one that was
    never claimed. The media pipeline transcodes on upload; anything unexpected here is treated
    as JPEG, which is what the camera produced.
    """
    m = (mime or "").lower().strip()
    return m if m in ("image/png", "image/jpeg") else "image/jpeg"


# --------------------------------------------------------------------------------------
# Runs and paragraphs
# --------------------------------------------------------------------------------------


def _rpr(
    run: Run, theme: ReportTheme, *, size_pt: float | None = None, color: str | None = None
) -> str:
    """The ``w:rPr`` for one run, including the complex-script font for Indic text.

    ``w:cs`` is the only lever a .docx has over shaping: Word picks the complex-script face for
    a run tagged ``w:cs`` with ``w:rtl``-adjacent scripts, and Nirmala UI ships with Windows and
    covers every Indic block this app captures. A Latin run must NOT carry it, or Word uses
    Nirmala's Latin glyphs and the report changes typeface mid-sentence.
    """
    parts = ["<w:rPr>"]
    if run.script is not Script.LATIN:
        parts.append(
            f'<w:rFonts w:ascii="{_esc(theme.body_font)}" w:hAnsi="{_esc(theme.body_font)}"'
            f' w:cs="{_esc(theme.complex_font)}"/>'
        )
    if run.bold:
        parts.append("<w:b/><w:bCs/>")
    if run.italic:
        parts.append("<w:i/><w:iCs/>")
    if run.underline:
        # w:val is mandatory on w:u; omitting it is schema-invalid and Word drops the run's
        # formatting rather than reporting it.
        parts.append('<w:u w:val="single"/>')
    if run.strike:
        parts.append("<w:strike/>")
    if run.superscript or run.subscript:
        # ONE element with two values, which is why the two marks cannot both apply: CT_VerticalAlignRun
        # takes baseline|superscript|subscript and emitting the element twice is schema-invalid.
        # `Run` is already single-valued here — `rich_text._runs_for` resolves a span carrying both in
        # favour of superscript — so this reads the flags rather than deciding between them.
        parts.append(f'<w:vertAlign w:val="{"superscript" if run.superscript else "subscript"}"/>')
    if run.highlight:
        # A NAME, not a colour: ST_HighlightColor is a closed enumeration and Word refuses an RGB
        # value here — which is exactly why `Run.highlight` is a boolean and why the two PDF
        # renderers fill `report_model.HIGHLIGHT_FILL`, the hex Word draws for this name.
        parts.append('<w:highlight w:val="yellow"/>')
    effective_color = run.color or color
    if effective_color:
        parts.append(f'<w:color w:val="{_esc(effective_color)}"/>')
    if size_pt:
        half = round(size_pt * 2)
        parts.append(f'<w:sz w:val="{half}"/><w:szCs w:val="{half}"/>')
    parts.append("</w:rPr>")
    return "".join(parts)


def _run_xml(
    run: Run, theme: ReportTheme, *, size_pt: float | None = None, color: str | None = None
) -> str:
    """One ``w:r``. A run's internal newlines become ``w:br`` so soft wraps survive."""
    if not run.text:
        return ""
    rpr = _rpr(run, theme, size_pt=size_pt, color=color)
    pieces: list[str] = []
    for i, line in enumerate(run.text.split("\n")):
        if i:
            pieces.append("<w:br/>")
        if line:
            pieces.append(f'<w:t xml:space="preserve">{_esc(line)}</w:t>')
    return f"<w:r>{rpr}{''.join(pieces)}</w:r>"


def _runs_xml(
    runs: tuple[Run, ...],
    theme: ReportTheme,
    *,
    size_pt: float | None = None,
    color: str | None = None,
) -> str:
    return "".join(_run_xml(r, theme, size_pt=size_pt, color=color) for r in runs)


def _para(
    content: str,
    *,
    style: str | None = None,
    align: Align | None = None,
    after: int = 120,
    before: int = 0,
    keep_next: bool = False,
    numbered: int | None = None,
    indent: int = 0,
    outline: int | None = None,
    shading: str | None = None,
    border_bottom: str | None = None,
    line: int | None = None,
) -> str:
    """Assemble one ``w:p``. Every paragraph in this module is built here.

    ``w:pPr`` children are order-sensitive in the schema — ``pStyle``, ``keepNext``, ``numPr``,
    ``pBdr``, ``shd``, ``spacing``, ``ind``, ``jc``, ``outlineLvl`` — and Word rejects the part
    outright if they are shuffled. Centralising the assembly is what keeps that order in one
    place instead of in fourteen call sites.
    """
    ppr: list[str] = ["<w:pPr>"]
    if style:
        ppr.append(f'<w:pStyle w:val="{_esc(style)}"/>')
    if keep_next:
        ppr.append("<w:keepNext/><w:keepLines/>")
    if numbered is not None:
        ppr.append(f'<w:numPr><w:ilvl w:val="0"/><w:numId w:val="{numbered}"/></w:numPr>')
    if border_bottom:
        ppr.append(
            f'<w:pBdr><w:bottom w:val="single" w:sz="6" w:space="4" '
            f'w:color="{_esc(border_bottom)}"/></w:pBdr>'
        )
    if shading:
        ppr.append(f'<w:shd w:val="clear" w:color="auto" w:fill="{_esc(shading)}"/>')
    spacing = f'<w:spacing w:before="{before}" w:after="{after}"'
    if line:
        spacing += f' w:line="{line}" w:lineRule="auto"'
    ppr.append(spacing + "/>")
    if indent:
        ppr.append(f'<w:ind w:left="{indent}"/>')
    if align:
        ppr.append(f'<w:jc w:val="{_ALIGN_TO_JC[align]}"/>')
    if outline is not None:
        ppr.append(f'<w:outlineLvl w:val="{outline}"/>')
    ppr.append("</w:pPr>")
    return f"<w:p>{''.join(ppr)}{content}</w:p>"


def _bookmark(name: str, ordinal: int) -> tuple[str, str]:
    """Open/close pair for a heading bookmark, so a TOC row can hyperlink to it."""
    if not name:
        return "", ""
    return (
        f'<w:bookmarkStart w:id="{ordinal}" w:name="{_esc(name)}"/>',
        f'<w:bookmarkEnd w:id="{ordinal}"/>',
    )


# --------------------------------------------------------------------------------------
# The writer
# --------------------------------------------------------------------------------------

ImageLoader = Callable[[ImageRef], bytes | None]
"""Resolves an :class:`ImageRef` to bytes. Returning ``None`` drops the picture and records a
warning rather than aborting — a report missing one photo is still worth having in the field."""


class DocxWriter:
    """Turns one :class:`ReportDocument` into one .docx byte string."""

    def __init__(self, document: ReportDocument, load_image: ImageLoader) -> None:
        self.doc = document
        self.theme = document.theme
        self.load_image = load_image
        self._body: list[str] = []
        self._media: list[tuple[str, bytes, str, int]] = []  # (part name, bytes, mime, rId)
        self._rid_by_source: dict[str, int] = {}
        # Pictures this writer MADE rather than loaded: the rasterised map and every chart.
        #
        # They go through the ordinary image path from here on — one media part, one
        # relationship, one w:drawing — which is the whole reason a MapBlock rasterises instead of
        # being drawn in DrawingML. What they cannot go through is ``load_image``: that resolves a
        # media id against S3, and a figure has no media id and never will. So the loader consults
        # this table first. A synthetic source must NOT appear in ``document.images`` either, or
        # ``design_workshops.render_report`` would try to prefetch it and report the miss to the
        # designer as a photograph that could not be included; ``report_model._images_of`` is
        # where that is guaranteed.
        self._figures: dict[str, bytes] = {}
        self._figure_seq = 0
        self._drawing_seq = 0
        self._bookmark_seq = 0
        self.dropped_images: list[str] = []
        # Native charts, in emission order: (c:chartSpace XML, embedded workbook bytes). The list
        # index is the part number, so charts[0] is word/charts/chart1.xml and its workbook is
        # word/embeddings/chart1.xlsx — one counter for three part names and one relationship, so
        # they cannot come apart.
        self._charts: list[tuple[str, bytes]] = []
        #: Charts that could not be expressed natively and fell back to the PNG, for the report.
        self.rasterised_charts: list[str] = []

        page_w_mm, page_h_mm = document.meta.page_size.size_mm
        margin = document.meta.margin_mm
        self.page_w_mm = page_w_mm
        self.page_h_mm = page_h_mm
        self.margin_mm = margin
        # The text column: every relative width in the model is a fraction of THIS.
        self.text_w_mm = page_w_mm - 2 * margin
        self.text_h_mm = page_h_mm - 2 * margin
        self.text_w_twip = int(self.text_w_mm * _TWIP_PER_MM)

    # -- images ----------------------------------------------------------------------

    def _register_image(self, image: ImageRef) -> tuple[int, int, int] | None:
        """Embed ``image`` once and return ``(rId, width_px, height_px)``.

        The size returned is the *display* size, i.e. after the EXIF quarter turn, preferring
        what the model was told and falling back to probing the bytes. A phone photo whose
        model-side dimensions were never populated still lays out at the right aspect ratio.
        """
        cached = self._rid_by_source.get(image.source)
        if cached is not None:
            for _name, data, _mime, rid in self._media:
                if rid == cached:
                    probed = probe_image_size(data)
                    w, h = self._display_size(image, probed)
                    return cached, w, h

        data = self._figures.get(image.source) or self.load_image(image)
        if not data:
            self.dropped_images.append(image.source)
            return None

        probed = probe_image_size(data)
        if probed is None:
            # Not a PNG or JPEG at all. Embedding it would produce a part Word cannot decode
            # and a red X in the document, which reads as a bug in the app rather than in the
            # file that was uploaded.
            self.dropped_images.append(image.source)
            return None

        mime = _normalise_mime(image.mime_type)
        # Trust the magic bytes over the declared mime: the media table has held "image/jpeg"
        # for a PNG since the Android client started guessing from the file extension.
        mime = "image/png" if data[:8] == b"\x89PNG\r\n\x1a\n" else "image/jpeg"
        rid = _RID_FIRST_IMAGE + len(self._media)
        ext = _MIME_TO_EXT[mime]
        self._media.append((f"image{len(self._media) + 1}.{ext}", data, mime, rid))
        self._rid_by_source[image.source] = rid
        w, h = self._display_size(image, probed)
        return rid, w, h

    @staticmethod
    def _display_size(image: ImageRef, probed: tuple[int, int] | None) -> tuple[int, int]:
        w = image.display_width_px
        h = image.display_height_px
        if (w <= 0 or h <= 0) and probed:
            pw, ph = probed
            if image.rotation_deg in (90, 270):
                pw, ph = ph, pw
            w, h = pw, ph
        if w <= 0 or h <= 0:
            w, h = 800, 600
        return w, h

    def _drawing(self, image: ImageRef, *, width_mm: float, max_height_mm: float) -> str:
        """One inline picture, fitted into ``width_mm`` x ``max_height_mm`` keeping aspect."""
        registered = self._register_image(image)
        if registered is None:
            return ""
        rid, px_w, px_h = registered
        aspect = (px_w / px_h) if px_h else image.aspect

        w_mm = width_mm
        h_mm = w_mm / aspect if aspect else width_mm * 0.75
        if h_mm > max_height_mm:
            h_mm = max_height_mm
            w_mm = h_mm * aspect

        cx = int(w_mm * _EMU_PER_MM)
        cy = int(h_mm * _EMU_PER_MM)

        # THE PIXELS MUST BE ROTATED, NOT ONLY THE FRAME.
        #
        # The box above is already sized for the DISPLAYED photo — `_display_size` swapped the
        # intrinsic width and height for a quarter turn — but the bytes in the media part are
        # still the camera's unrotated frame, and a .docx draws them into the box as they are.
        # A portrait photograph taken on a phone therefore arrived in a landscape frame with
        # portrait pixels stretched across it: every person in the picture two-thirds too wide,
        # in a report submitted to a ministry. Nothing warned; the file opened perfectly.
        #
        # DrawingML rotates about the shape's centre, and its `a:ext` is the UNROTATED extent
        # while `wp:extent` is the bounding box the rotated result occupies in the text flow. So
        # for a quarter turn the two are the same numbers swapped. Getting that pair the wrong
        # way round is the classic OOXML rotation bug — the picture renders rotated but cropped
        # to the wrong box, which looks like a broken image rather than a wrong transform.
        rotation = image.rotation_deg % 360 if image.rotation_deg else 0
        if rotation in (90, 270):
            ext_cx, ext_cy = cy, cx  # unrotated: the swap of the displayed box
        else:
            ext_cx, ext_cy = cx, cy
        # DrawingML angles are in sixtieth-thousandths of a degree, clockwise.
        rot_attr = f' rot="{rotation * 60000}"' if rotation else ""

        self._drawing_seq += 1
        n = self._drawing_seq
        return (
            "<w:r><w:drawing>"
            f'<wp:inline distT="0" distB="0" distL="0" distR="0">'
            f'<wp:extent cx="{cx}" cy="{cy}"/>'
            '<wp:effectExtent l="0" t="0" r="0" b="0"/>'
            f'<wp:docPr id="{n}" name="Picture {n}"/>'
            f'<wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a="{_NS_A}" noChangeAspect="1"/>'
            "</wp:cNvGraphicFramePr>"
            f'<a:graphic xmlns:a="{_NS_A}">'
            f'<a:graphicData uri="{_NS_PIC}">'
            f'<pic:pic xmlns:pic="{_NS_PIC}">'
            f'<pic:nvPicPr><pic:cNvPr id="{n}" name="Picture {n}"/><pic:cNvPicPr/></pic:nvPicPr>'
            f'<pic:blipFill><a:blip r:embed="rId{rid}"/>'
            "<a:stretch><a:fillRect/></a:stretch></pic:blipFill>"
            f'<pic:spPr><a:xfrm{rot_attr}><a:off x="0" y="0"/>'
            f'<a:ext cx="{ext_cx}" cy="{ext_cy}"/></a:xfrm>'
            '<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr>'
            "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"
        )

    # -- blocks ----------------------------------------------------------------------

    def _emit_cover(self, block: CoverBlock) -> None:
        t = self.theme
        if block.logo:
            drawing = self._drawing(block.logo, width_mm=self.text_w_mm * 0.22, max_height_mm=28)
            if drawing:
                self._body.append(_para(drawing, align=Align.CENTER, after=240))
        else:
            self._body.append(_para("", after=1200))

        for line in block.org_lines:
            self._body.append(
                _para(
                    _runs_xml(runs_of(line), t, size_pt=11, color=t.muted),
                    align=Align.CENTER,
                    after=60,
                )
            )

        self._body.append(_para("", after=380))
        self._body.append(
            _para(
                _runs_xml(runs_of(block.title, bold=True), t, size_pt=27, color=t.accent),
                align=Align.CENTER,
                after=140,
            )
        )
        if block.subtitle:
            self._body.append(
                _para(
                    _runs_xml(
                        runs_of(block.subtitle, italic=True), t, size_pt=13, color=t.accent_soft
                    ),
                    align=Align.CENTER,
                    after=260,
                )
            )

        if block.hero_image:
            drawing = self._drawing(
                block.hero_image,
                width_mm=self.text_w_mm * 0.78,
                max_height_mm=self.text_h_mm * 0.34,
            )
            if drawing:
                self._body.append(_para(drawing, align=Align.CENTER, after=260))

        if block.info_rows:
            self._emit_table(
                TableBlock(
                    columns=(
                        _col("", 32.0),
                        _col("", 68.0),
                    ),
                    rows=tuple(
                        (runs_of(label, bold=True), runs_of(value))
                        for label, value in block.info_rows
                    ),
                    zebra=True,
                ),
                headerless=True,
            )

        for line in block.footer_lines:
            self._body.append(
                _para(
                    _runs_xml(runs_of(line), t, size_pt=9.5, color=t.muted),
                    align=Align.CENTER,
                    after=60,
                )
            )
        self._body.append(_page_break())

    def _emit_toc(self, block: TocBlock) -> None:
        t = self.theme
        self._body.append(
            _para(
                _runs_xml(runs_of(block.title, bold=True), t, size_pt=16, color=t.accent),
                style="TOCHeading",
                after=180,
            )
        )
        depth = max(1, min(4, block.depth))
        # A TOC field, not a rendered list. Word paginates it on open because settings.xml
        # carries w:updateFields; the literal run between separate/end is the placeholder a
        # reader sees if they decline the refresh, so it has to be an instruction, not "1".
        self._body.append(
            "<w:p><w:pPr><w:tabs>"
            f'<w:tab w:val="right" w:leader="dot" w:pos="{self.text_w_twip}"/>'
            "</w:tabs></w:pPr>"
            '<w:r><w:fldChar w:fldCharType="begin" w:dirty="true"/></w:r>'
            f'<w:r><w:instrText xml:space="preserve"> TOC \\o "1-{depth}" \\h \\z \\u </w:instrText>'
            "</w:r>"
            '<w:r><w:fldChar w:fldCharType="separate"/></w:r>'
            '<w:r><w:rPr><w:i/><w:color w:val="5A6B87"/></w:rPr>'
            "<w:t>Right-click here and choose &quot;Update Field&quot; to build the contents."
            "</w:t></w:r>"
            '<w:r><w:fldChar w:fldCharType="end"/></w:r></w:p>'
        )
        self._body.append(_page_break())

    def _emit_heading(self, block: HeadingBlock) -> None:
        self._bookmark_seq += 1
        start, end = _bookmark(block.bookmark, self._bookmark_seq)
        label = f"{block.number}. " if block.number else ""
        content = start
        if label:
            content += _run_xml(Run(text=label, bold=True), self.theme)
        content += _runs_xml(block.runs, self.theme) + end
        self._body.append(
            _para(
                content,
                style=f"Heading{block.level}",
                keep_next=True,
                outline=block.level - 1,
                before=(320, 260, 220, 180)[block.level - 1],
                after=120,
            )
        )

    def _emit_paragraph(self, block: ParagraphBlock) -> None:
        t = self.theme
        style_map = {
            ParaStyle.BODY: (None, None, None, 0),
            ParaStyle.LEAD: (None, 12.0, t.ink, 0),
            ParaStyle.NOTE: ("ReportNote", 9.0, t.muted, 0),
            ParaStyle.QUOTE: ("ReportQuote", None, t.accent_soft, 420),
            ParaStyle.CAPTION: ("Caption", None, None, 0),
            ParaStyle.COVER_LINE: (None, 11.0, t.muted, 0),
        }
        style, size, color, indent = style_map[block.style]
        runs = block.runs
        if block.style is ParaStyle.QUOTE:
            # ``replace``, NOT ``Run(r.text, r.bold, True, r.script, r.color)``.
            #
            # That is what this line used to say, and it was correct until RICH_TEXT inserted
            # ``underline`` and ``strike`` at positions four and five. From then on the fifth and
            # sixth POSITIONAL arguments landed on the wrong fields: the Script went into
            # ``underline`` (truthy for every script there is) and the colour into ``strike``, so
            # every artisan verbatim in every report came out UNDERLINED, printed in the default
            # colour instead of the quote colour, and — because ``script`` fell back to LATIN —
            # stripped of the ``w:cs="Nirmala UI"`` that is the only thing making an Odia quote
            # render as words rather than boxes. Nothing raised; the file opened perfectly.
            #
            # ``DocxWriter.kt`` writes ``it.copy(italic = true)`` and was never wrong, which is
            # what made this a DIVERGENCE rather than merely a bug: the same workshop's quote was
            # underlined in the office's download and not on the phone. ``replace`` is Kotlin's
            # ``copy`` — it names the one field it changes and cannot be reached by a new one.
            runs = tuple(replace(r, italic=True) for r in runs)
        self._body.append(
            _para(
                _runs_xml(runs, t, size_pt=size, color=color),
                style=style,
                align=block.align,
                indent=indent,
                after=140,
                line=276,
            )
        )

    def _emit_bullets(self, block: BulletListBlock) -> None:
        num_id = 2 if block.ordered else 1
        for item in block.items:
            self._body.append(
                _para(
                    _runs_xml(item, self.theme),
                    numbered=num_id,
                    after=60,
                    line=276,
                )
            )

    def _emit_key_values(self, block: KeyValueBlock) -> None:
        """A borderless two- (or four-) column grid.

        Rendered as a table rather than tab-separated runs so a long value wraps within its own
        column instead of pushing the following pair onto the next line.
        """
        t = self.theme
        per_row = max(1, min(2, block.columns))
        label_pct = block.label_width_pct / per_row
        value_pct = (100.0 / per_row) - label_pct

        rows: list[str] = []
        pairs = list(block.pairs)
        for i in range(0, len(pairs), per_row):
            chunk = pairs[i : i + per_row]
            cells: list[str] = []
            for label, value in chunk:
                cells.append(
                    _cell(
                        _para(
                            _run_xml(Run(text=label, bold=True), t, color=t.muted, size_pt=9.5),
                            after=40,
                        ),
                        width_twip=int(self.text_w_twip * label_pct / 100),
                    )
                )
                cells.append(
                    _cell(
                        _para(_runs_xml(value, t), after=40),
                        width_twip=int(self.text_w_twip * value_pct / 100),
                    )
                )
            while len(cells) < per_row * 2:
                cells.append(_cell(_para(""), width_twip=int(self.text_w_twip * label_pct / 100)))
            rows.append(f"<w:tr>{''.join(cells)}</w:tr>")

        grid_widths = []
        for _ in range(per_row):
            grid_widths.append(int(self.text_w_twip * label_pct / 100))
            grid_widths.append(int(self.text_w_twip * value_pct / 100))
        self._body.append(_tbl(rows, grid_widths, self.text_w_twip, borders=False))
        self._body.append(_para("", after=100))

    def _emit_table(self, block: TableBlock, *, headerless: bool = False) -> None:
        t = self.theme
        widths = [int(self.text_w_twip * c.width_pct / 100) for c in block.columns]
        rows: list[str] = []

        if not headerless:
            header_cells = [
                _cell(
                    _para(
                        _run_xml(
                            Run(text=col.header, bold=True),
                            t,
                            color=t.table_header_text,
                            size_pt=9.0,
                        ),
                        align=col.align,
                        after=0,
                    ),
                    width_twip=w,
                    fill=t.table_header_fill,
                )
                for col, w in zip(block.columns, widths)
            ]
            # tblHeader repeats the row on every page a long table spills onto; cantSplit stops
            # Word breaking the header itself across the page boundary.
            # cantSplit BEFORE tblHeader: CT_TrPr is a sequence, not a set. Reversed, the part
            # is schema-invalid even though Word has historically tolerated it.
            rows.append(
                f"<w:tr><w:trPr><w:cantSplit/><w:tblHeader/></w:trPr>{''.join(header_cells)}</w:tr>"
            )

        for i, row in enumerate(block.rows):
            fill = t.zebra_fill if (block.zebra and i % 2 == 1) else None
            cells = []
            for j, col in enumerate(block.columns):
                value = row[j] if j < len(row) else ()
                align = Align.RIGHT if col.numeric else col.align
                cells.append(
                    _cell(
                        _para(_runs_xml(value, t, size_pt=9.5), align=align, after=0),
                        width_twip=widths[j],
                        fill=fill,
                    )
                )
            rows.append(f"<w:tr>{''.join(cells)}</w:tr>")

        if block.total_row:
            cells = []
            for j, col in enumerate(block.columns):
                value = block.total_row[j] if j < len(block.total_row) else ()
                # ``replace`` for the reason spelled out in _emit_paragraph: the same positional
                # construction here put the Script into ``underline`` and the colour into
                # ``strike``, so every cost sheet's TOTAL row printed underlined and struck
                # through — on the one line of the one table an officer sanctions money from.
                bolded = tuple(replace(r, bold=True) for r in value)
                align = Align.RIGHT if col.numeric else col.align
                cells.append(
                    _cell(
                        _para(_runs_xml(bolded, t, size_pt=9.5), align=align, after=0),
                        width_twip=widths[j],
                        fill=t.zebra_fill,
                        top_border=t.accent,
                    )
                )
            rows.append(f"<w:tr>{''.join(cells)}</w:tr>")

        self._body.append(_tbl(rows, widths, self.text_w_twip, borders=True, rule_color=t.rule))
        # Mandatory: two adjacent w:tbl merge silently.
        self._body.append(_para("", after=100))
        if block.caption:
            self._body.append(
                _para(
                    _runs_xml(runs_of(block.caption, italic=True), t, size_pt=9.0, color=t.muted),
                    style="Caption",
                    align=Align.CENTER,
                    after=180,
                )
            )

    def _emit_image(self, block: ImageBlock) -> None:
        drawing = self._drawing(
            block.image,
            width_mm=self.text_w_mm * max(5.0, min(100.0, block.width_pct)) / 100,
            max_height_mm=self.text_h_mm * 0.62,
        )
        if not drawing:
            return
        self._body.append(
            _para(drawing, align=block.align, after=60, keep_next=bool(block.caption))
        )
        if block.caption:
            self._body.append(
                _para(
                    _runs_xml(
                        runs_of(block.caption, italic=True),
                        self.theme,
                        size_pt=9.0,
                        color=self.theme.muted,
                    ),
                    style="Caption",
                    align=Align.CENTER,
                    after=200,
                )
            )

    def _emit_image_grid(self, block: ImageGridBlock) -> None:
        """A photo grid laid out as a borderless table, one picture + caption per cell."""
        if not block.images:
            return
        t = self.theme
        # Below ~45 mm a photo stops being evidence and becomes a thumbnail, so drop columns
        # rather than shrink past that. This is the same rule the PDF renderer applies.
        columns = max(1, min(4, block.columns))
        while columns > 1 and (self.text_w_mm / columns) < 45.0:
            columns -= 1

        cell_w_mm = self.text_w_mm / columns
        cell_w_twip = int(self.text_w_twip / columns)
        widths = [cell_w_twip] * columns
        rows: list[str] = []

        for start in range(0, len(block.images), columns):
            chunk = list(block.images[start : start + columns])
            cells: list[str] = []
            for image, caption in chunk:
                inner: list[str] = []
                drawing = self._drawing(
                    image, width_mm=cell_w_mm - 6, max_height_mm=self.text_h_mm * 0.30
                )
                if drawing:
                    inner.append(
                        _para(drawing, align=Align.CENTER, after=40, keep_next=bool(caption))
                    )
                if caption:
                    inner.append(
                        _para(
                            _runs_xml(runs_of(caption, italic=True), t, size_pt=8.5, color=t.muted),
                            align=Align.CENTER,
                            after=60,
                        )
                    )
                if not inner:
                    inner.append(_para("", after=0))
                cells.append(_cell("".join(inner), width_twip=cell_w_twip))
            while len(cells) < columns:
                cells.append(_cell(_para("", after=0), width_twip=cell_w_twip))
            rows.append(f"<w:tr><w:trPr><w:cantSplit/></w:trPr>{''.join(cells)}</w:tr>")

        self._body.append(_tbl(rows, widths, self.text_w_twip, borders=False))
        self._body.append(_para("", after=100))
        if block.caption:
            self._body.append(
                _para(
                    _runs_xml(runs_of(block.caption, italic=True), t, size_pt=9.0, color=t.muted),
                    style="Caption",
                    align=Align.CENTER,
                    after=180,
                )
            )

    # -- figures: the map and the infographics ----------------------------------------

    def _figure_width_mm(self, width_pct: float) -> float:
        return self.text_w_mm * max(20.0, min(100.0, width_pct)) / 100

    def _emit_figure(
        self,
        png: bytes,
        px_w: int,
        px_h: int,
        *,
        width_pct: float,
        align: Align,
        title: str,
        caption: str,
    ) -> None:
        """Place a rasterised figure through the ORDINARY picture path.

        Title and caption are emitted as REAL TEXT rather than drawn into the bitmap, and that is
        not a stylistic choice: the raster's five-by-seven face is ASCII only, so a caption
        naming a craft in Odia would be silently dropped inside the picture, while a Word run
        carrying ``w:cs="Nirmala UI"`` prints it. It also means a caption is searchable, and that
        the accessibility description below is the same string the reader sees.
        """
        self._figure_seq += 1
        source = f"figure:{self._figure_seq}"
        self._figures[source] = png
        ref = ImageRef(source=source, width_px=px_w, height_px=px_h, mime_type="image/png")

        self._emit_figure_title(title)
        drawing = self._drawing(
            ref, width_mm=self._figure_width_mm(width_pct), max_height_mm=self.text_h_mm * 0.58
        )
        if not drawing:
            return
        self._body.append(_para(drawing, align=align, after=60, keep_next=bool(caption)))
        self._emit_figure_caption(caption)

    def _emit_figure_title(self, title: str) -> None:
        """The figure's heading line, as real text above whatever draws the figure.

        Shared by the rasterised path and the native-chart path so the two produce the SAME
        paragraph. A native chart that titled itself inside the frame would sit differently on the
        page from the map above it, and the two figures would stop looking like one report.
        """
        if not title:
            return
        self._body.append(
            _para(
                _run_xml(
                    Run(text=title, bold=True), self.theme, size_pt=10.5, color=self.theme.accent
                ),
                after=60,
                keep_next=True,
            )
        )

    def _emit_figure_caption(self, caption: str) -> None:
        if not caption:
            return
        self._body.append(
            _para(
                _runs_xml(
                    runs_of(caption, italic=True), self.theme, size_pt=9.0, color=self.theme.muted
                ),
                style="Caption",
                align=Align.CENTER,
                after=200,
            )
        )

    def _emit_map(self, block: MapBlock) -> None:
        from app.services.report_map import render_map_png

        rendered = render_map_png(
            block, self.theme, pixels_for_mm(self._figure_width_mm(block.width_pct))
        )
        if rendered is None:
            # The boundary assets are not on this machine. Recorded exactly as a photograph that
            # failed to download is, so the caller can tell the designer, and NOT raised: a
            # workshop report without its locator map is still the report they asked for.
            self.dropped_images.append("map:india")
            return
        png, px_w, px_h = rendered
        self._emit_figure(
            png,
            px_w,
            px_h,
            width_pct=block.width_pct,
            align=block.align,
            title=block.title,
            caption=block.caption,
        )

    def _emit_chart(self, block: ChartBlock) -> None:
        """A statistical figure, natively if Word has a form for it and as a picture if not.

        THE ORDER OF THESE TWO BRANCHES IS THE FEATURE. A native chart is better in every way that
        matters to the reader — it enlarges without breaking up, it can be restyled, it prints an
        Odia category the rasteriser would have dropped, and double-clicking it shows the numbers.
        But "better" is not "always possible", and the only unacceptable outcome is a figure the
        template asked for that is not in the document at all. So the native path is tried, and
        anything it declines falls through to the picture path that has always worked.
        """
        from app.services.report_chart import clean_series

        series, notes = clean_series(block)
        if self._emit_native_chart(block, series):
            # The rasteriser prints these inside the PNG. A native chart has no such corner, and a
            # dropped category that goes unmentioned is a figure quietly missing a cost head.
            for note in notes:
                self._body.append(
                    _para(
                        _runs_xml(runs_of(note), self.theme, size_pt=8.0, color=self.theme.muted),
                        align=Align.CENTER,
                        after=40,
                    )
                )
            self._emit_figure_caption(block.caption)
            return

        from app.services.report_chart import render_chart_png

        png, px_w, px_h = render_chart_png(
            block, self.theme, pixels_for_mm(self._figure_width_mm(block.width_pct))
        )
        self._emit_figure(
            png,
            px_w,
            px_h,
            width_pct=block.width_pct,
            align=block.align,
            title=block.title,
            caption=block.caption,
        )

    def _emit_native_chart(self, block: ChartBlock, series: list[tuple[str, float]]) -> bool:
        """Emit ``block`` as a real ``c:chart``. Returns False when it must stay a raster.

        The one refusal is an EMPTY series, and it is a refusal on purpose. ``report_chart`` draws
        that case as a framed panel reading "No values recorded.", which is a statement about the
        record — an omitted figure looks like a rendering fault. A ``c:chart`` with no points draws
        an empty plot area and says nothing, so for that one input the picture is the better
        document and the fallback below is not a degradation but the correct answer.
        """
        from app.services.report_chart import chart_pixel_box

        if not series:
            self.rasterised_charts.append(block.title or block.kind.value)
            return False

        w_mm = self._figure_width_mm(block.width_pct)
        # The native chart has no bitmap and therefore no intrinsic size; its frame is whatever the
        # drawing says. Taking the aspect from the rasteriser's own box is what keeps a cost chart
        # the same SHAPE in the .docx and the .pdf of one workshop.
        px_w, px_h = chart_pixel_box(block, pixels_for_mm(w_mm), len(series))
        h_mm = (w_mm * px_h / px_w) if px_w else w_mm * 0.62
        if block.kind is ChartKind.HORIZONTAL_BAR:
            h_mm = max(h_mm, _NATIVE_CHROME_MM + _NATIVE_ROW_MM * len(series))
        max_h_mm = self.text_h_mm * 0.58
        if h_mm > max_h_mm:
            # Shrink the width with it. Clamping height alone squashes a six-head cost chart into
            # a strip, which is the same figure telling a different story.
            w_mm *= max_h_mm / h_mm
            h_mm = max_h_mm

        self._charts.append(
            (
                chart_space_xml(block, series, self.theme),
                chart_workbook_xlsx(block, series),
            )
        )
        rid = f"{_CHART_RID_PREFIX}{len(self._charts)}"

        self._emit_figure_title(block.title)
        self._body.append(
            _para(
                self._chart_drawing(rid, w_mm, h_mm),
                align=block.align,
                after=60,
                keep_next=bool(block.caption),
            )
        )
        return True

    def _chart_drawing(self, rid: str, width_mm: float, height_mm: float) -> str:
        """The ``w:drawing`` that places a chart part.

        Nearly the picture drawing in ``_drawing``, and different in the three ways that matter:
        the graphicData uri is the CHART namespace rather than the picture one, the payload is a
        bare ``c:chart`` reference instead of a ``pic:pic`` tree, and there is no
        ``a:graphicFrameLocks noChangeAspect`` — a chart is meant to be resized freely, and locking
        its aspect is what stops a reader fitting it to their own column.
        """
        self._drawing_seq += 1
        n = self._drawing_seq
        cx = int(width_mm * _EMU_PER_MM)
        cy = int(height_mm * _EMU_PER_MM)
        return (
            "<w:r><w:drawing>"
            '<wp:inline distT="0" distB="0" distL="0" distR="0">'
            f'<wp:extent cx="{cx}" cy="{cy}"/>'
            '<wp:effectExtent l="0" t="0" r="0" b="0"/>'
            f'<wp:docPr id="{n}" name="Chart {n}"/>'
            "<wp:cNvGraphicFramePr/>"
            f'<a:graphic xmlns:a="{_NS_A}">'
            f'<a:graphicData uri="{_NS_C}">'
            f'<c:chart xmlns:c="{_NS_C}" xmlns:r="{_NS_R}" r:id="{_esc(rid)}"/>'
            "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"
        )

    def _emit_metrics(self, block: MetricRowBlock) -> None:
        if not block.metrics:
            return
        t = self.theme
        n = len(block.metrics)
        width = int(self.text_w_twip / n)
        cells: list[str] = []
        for label, value, unit in block.metrics:
            body = _para(
                _run_xml(Run(text=value, bold=True), t, size_pt=18, color=t.accent)
                + (_run_xml(Run(text=f" {unit}"), t, size_pt=9.5, color=t.muted) if unit else ""),
                align=Align.CENTER,
                after=20,
            ) + _para(
                _run_xml(Run(text=label), t, size_pt=8.5, color=t.muted),
                align=Align.CENTER,
                after=0,
            )
            cells.append(_cell(body, width_twip=width, fill=t.zebra_fill))
        self._body.append(
            _tbl([f"<w:tr>{''.join(cells)}</w:tr>"], [width] * n, self.text_w_twip, borders=False)
        )
        self._body.append(_para("", after=140))

    def _emit_callout(self, block: CalloutBlock) -> None:
        t = self.theme
        fill = {"INFO": "EAF1FB", "WARNING": "FDF3E2", "SUCCESS": "EAF6EE"}.get(
            block.kind.upper(), "EAF1FB"
        )
        edge = {"INFO": t.accent_soft, "WARNING": "B7791F", "SUCCESS": "2F855A"}.get(
            block.kind.upper(), t.accent_soft
        )
        inner = ""
        if block.title:
            inner += _para(
                _run_xml(Run(text=block.title, bold=True), t, color=edge, size_pt=10), after=40
            )
        inner += _para(_runs_xml(block.runs, t, size_pt=9.5), after=0)
        cell = _cell(inner, width_twip=self.text_w_twip, fill=fill, left_border=edge)
        self._body.append(
            _tbl([f"<w:tr>{cell}</w:tr>"], [self.text_w_twip], self.text_w_twip, borders=False)
        )
        self._body.append(_para("", after=140))

    def _emit_signatures(self, block: SignatureBlock) -> None:
        if not block.signatories:
            return
        t = self.theme
        n = len(block.signatories)
        width = int(self.text_w_twip / n)
        self._body.append(_para("", after=700))
        cells = []
        for name, designation in block.signatories:
            body = _para("", after=0, border_bottom=t.ink)
            body += _para(
                _run_xml(Run(text=name, bold=True), t, size_pt=10), align=Align.CENTER, after=20
            )
            body += _para(
                _run_xml(Run(text=designation), t, size_pt=8.5, color=t.muted),
                align=Align.CENTER,
                after=0,
            )
            cells.append(_cell(body, width_twip=width))
        self._body.append(
            _tbl([f"<w:tr>{''.join(cells)}</w:tr>"], [width] * n, self.text_w_twip, borders=False)
        )
        self._body.append(_para("", after=100))

    # -- assembly --------------------------------------------------------------------

    def _emit_blocks(self) -> None:
        for block in self.doc.blocks:
            if isinstance(block, CoverBlock):
                self._emit_cover(block)
            elif isinstance(block, TocBlock):
                self._emit_toc(block)
            elif isinstance(block, HeadingBlock):
                self._emit_heading(block)
            elif isinstance(block, ParagraphBlock):
                self._emit_paragraph(block)
            elif isinstance(block, BulletListBlock):
                self._emit_bullets(block)
            elif isinstance(block, KeyValueBlock):
                self._emit_key_values(block)
            elif isinstance(block, TableBlock):
                self._emit_table(block)
            elif isinstance(block, ImageBlock):
                self._emit_image(block)
            elif isinstance(block, ImageGridBlock):
                self._emit_image_grid(block)
            elif isinstance(block, MapBlock):
                self._emit_map(block)
            elif isinstance(block, ChartBlock):
                self._emit_chart(block)
            elif isinstance(block, MetricRowBlock):
                self._emit_metrics(block)
            elif isinstance(block, CalloutBlock):
                self._emit_callout(block)
            elif isinstance(block, SignatureBlock):
                self._emit_signatures(block)
            elif isinstance(block, SpacerBlock):
                self._body.append(_para("", after=int(block.height_pct * 24)))
            elif isinstance(block, PageBreakBlock):
                self._body.append(_page_break())
            else:  # pragma: no cover - the union above is exhaustive
                raise TypeError(f"Unrenderable block type {type(block).__name__}")

    def _validate_body(self) -> None:
        """Refuse to emit a body Word would silently mangle.

        Only one rule so far, and it is the one that actually happened: two ``w:tbl`` siblings
        merge into a single table. Raising here turns a subtly wrong report into a failed export
        with a stack trace naming the block that did it.
        """
        for i in range(len(self._body) - 1):
            if self._body[i].startswith("<w:tbl>") and self._body[i + 1].startswith("<w:tbl>"):
                raise ValueError(
                    f"two adjacent tables at body index {i}: Word merges these silently. "
                    "Every _emit_* that appends a table must append a paragraph after it."
                )

    def build(self) -> bytes:
        self._emit_blocks()
        self._validate_body()

        buffer = BytesIO()
        with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("[Content_Types].xml", self._content_types())
            z.writestr("_rels/.rels", _ROOT_RELS)
            z.writestr("word/document.xml", self._document_xml())
            z.writestr("word/_rels/document.xml.rels", self._document_rels())
            z.writestr("word/styles.xml", _styles_xml(self.theme))
            z.writestr("word/numbering.xml", _NUMBERING_XML)
            z.writestr("word/settings.xml", _SETTINGS_XML)
            z.writestr("word/header1.xml", self._header_xml())
            z.writestr("word/footer1.xml", self._footer_xml())
            z.writestr("docProps/core.xml", self._core_xml())
            z.writestr("docProps/app.xml", _APP_XML)
            for name, data, _mime, _rid in self._media:
                z.writestr(f"word/media/{name}", data)
            # Three parts per native chart, and all three or none. A chart part whose workbook
            # relationship dangles opens as a chart with a broken "Edit Data"; a workbook with no
            # chart part is dead weight the reader never sees.
            for index, (chart_xml, workbook) in enumerate(self._charts, start=1):
                z.writestr(f"word/charts/chart{index}.xml", chart_xml)
                z.writestr(
                    f"word/charts/_rels/chart{index}.xml.rels",
                    chart_part_rels(f"chart{index}.xlsx"),
                )
                z.writestr(f"word/embeddings/chart{index}.xlsx", workbook)
        return buffer.getvalue()

    def _content_types(self) -> str:
        extensions = {
            "rels": "application/vnd.openxmlformats-package.relationships+xml",
            "xml": "application/xml",
        }
        for _name, _data, mime, _rid in self._media:
            extensions[_MIME_TO_EXT[mime]] = mime
        if self._charts:
            # The embedded workbooks. A Default rather than an Override each, because every one of
            # them is the same type and Word resolves the extension the same way.
            extensions["xlsx"] = _XLSX_MIME
        defaults = "".join(
            f'<Default Extension="{ext}" ContentType="{ct}"/>'
            for ext, ct in sorted(extensions.items())
        )
        wml = "application/vnd.openxmlformats-officedocument.wordprocessingml"
        overrides = "".join(
            [
                # NOT DOCX_MIME. That is the content type of the .docx *file*; the main document
                # *part* inside it is ".document.main+xml". Declaring the package type here
                # produces a package that passes every structural check — all parts parse, all
                # relationships resolve — and that Word refuses with a bare "the file appears to
                # be corrupted". Every part below is likewise the part type, not a file type.
                f'<Override PartName="/word/document.xml" ContentType="{wml}.document.main+xml"/>',
                f'<Override PartName="/word/styles.xml" ContentType="{wml}.styles+xml"/>',
                f'<Override PartName="/word/numbering.xml" ContentType="{wml}.numbering+xml"/>',
                f'<Override PartName="/word/settings.xml" ContentType="{wml}.settings+xml"/>',
                f'<Override PartName="/word/header1.xml" ContentType="{wml}.header+xml"/>',
                f'<Override PartName="/word/footer1.xml" ContentType="{wml}.footer+xml"/>',
                (
                    '<Override PartName="/docProps/core.xml" ContentType="application/vnd.'
                    'openxmlformats-package.core-properties+xml"/>'
                ),
                (
                    '<Override PartName="/docProps/app.xml" ContentType="application/vnd.'
                    'openxmlformats-officedocument.extended-properties+xml"/>'
                ),
            ]
            + [
                # A chart part ends in .xml, so the Default above already gives it "application/xml"
                # — and a chart declared as generic XML is one Word ignores completely, leaving a
                # correctly sized blank rectangle where the figure should be. The Override is what
                # names it a chart.
                f'<Override PartName="/word/charts/chart{i}.xml" ContentType="{_CHART_PART_TYPE}"/>'
                for i in range(1, len(self._charts) + 1)
            ]
        )
        return (
            f'{_XML_DECL}<Types xmlns="http://schemas.openxmlformats.org/package/2006/'
            f'content-types">{defaults}{overrides}</Types>'
        )

    def _document_rels(self) -> str:
        base = f"{_NS_R}/"
        fixed = "".join(
            [
                f'<Relationship Id="rId{_RID_STYLES}" Type="{base}styles" Target="styles.xml"/>',
                (
                    f'<Relationship Id="rId{_RID_NUMBERING}" Type="{base}numbering" '
                    'Target="numbering.xml"/>'
                ),
                (
                    f'<Relationship Id="rId{_RID_SETTINGS}" Type="{base}settings" '
                    'Target="settings.xml"/>'
                ),
                f'<Relationship Id="rId{_RID_HEADER}" Type="{base}header" Target="header1.xml"/>',
                f'<Relationship Id="rId{_RID_FOOTER}" Type="{base}footer" Target="footer1.xml"/>',
            ]
        )
        media = "".join(
            f'<Relationship Id="rId{rid}" Type="{base}image" Target="media/{name}"/>'
            for name, _data, _mime, rid in self._media
        )
        charts = "".join(
            f'<Relationship Id="{_CHART_RID_PREFIX}{i}" Type="{base}chart" '
            f'Target="charts/chart{i}.xml"/>'
            for i in range(1, len(self._charts) + 1)
        )
        return (
            f'{_XML_DECL}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/'
            f'relationships">{fixed}{media}{charts}</Relationships>'
        )

    def _document_xml(self) -> str:
        w = int(self.page_w_mm * _TWIP_PER_MM)
        h = int(self.page_h_mm * _TWIP_PER_MM)
        m = int(self.margin_mm * _TWIP_PER_MM)
        sect = (
            "<w:sectPr>"
            f'<w:headerReference w:type="default" r:id="rId{_RID_HEADER}"/>'
            f'<w:footerReference w:type="default" r:id="rId{_RID_FOOTER}"/>'
            f'<w:pgSz w:w="{w}" w:h="{h}"/>'
            f'<w:pgMar w:top="{m}" w:right="{m}" w:bottom="{m}" w:left="{m}" '
            'w:header="709" w:footer="709" w:gutter="0"/>'
            # The cover carries no running head or foot. Without titlePg the header prints over
            # the ministry line and the footer numbers the cover as page 1 of the body.
            "<w:titlePg/>"
            "</w:sectPr>"
        )
        return (
            f"{_XML_DECL}"
            f'<w:document xmlns:w="{_NS_W}" xmlns:r="{_NS_R}" xmlns:wp="{_NS_WP}" '
            f'xmlns:a="{_NS_A}" xmlns:pic="{_NS_PIC}">'
            f"<w:body>{''.join(self._body)}{sect}</w:body></w:document>"
        )

    def _header_xml(self) -> str:
        t = self.theme
        text = self.doc.meta.header_text
        content = _run_xml(Run(text=text), t, size_pt=8.5, color=t.muted) if text else ""
        return (
            f'{_XML_DECL}<w:hdr xmlns:w="{_NS_W}" xmlns:r="{_NS_R}">'
            + _para(content, align=Align.RIGHT, after=0, border_bottom=t.rule)
            + "</w:hdr>"
        )

    def _footer_xml(self) -> str:
        t = self.theme
        meta = self.doc.meta
        left = (
            _run_xml(Run(text=meta.footer_text), t, size_pt=8.5, color=t.muted)
            if meta.footer_text
            else ""
        )
        pages = ""
        if meta.show_page_numbers:
            pages = (
                "<w:r><w:tab/></w:r>"
                + _run_xml(Run(text="Page "), t, size_pt=8.5, color=t.muted)
                + _field("PAGE")
                + _run_xml(Run(text=" of "), t, size_pt=8.5, color=t.muted)
                + _field("NUMPAGES")
            )
        ppr = (
            "<w:pPr>"
            f'<w:pBdr><w:top w:val="single" w:sz="6" w:space="4" w:color="{_esc(t.rule)}"/>'
            "</w:pBdr>"
            f'<w:tabs><w:tab w:val="right" w:pos="{self.text_w_twip}"/></w:tabs>'
            '<w:spacing w:before="0" w:after="0"/>'
            "</w:pPr>"
        )
        return (
            f'{_XML_DECL}<w:ftr xmlns:w="{_NS_W}" xmlns:r="{_NS_R}">'
            f"<w:p>{ppr}{left}{pages}</w:p></w:ftr>"
        )

    def _core_xml(self) -> str:
        meta = self.doc.meta
        stamp = meta.generated_at or "2026-01-01T00:00:00Z"
        return (
            f"{_XML_DECL}"
            '<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/'
            'metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" '
            'xmlns:dcterms="http://purl.org/dc/terms/" '
            'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">'
            # CT_CoreProperties is a sequence: category, contentStatus, created, creator,
            # description, identifier, keywords, language, lastModifiedBy, lastPrinted,
            # modified, revision, subject, title, version. Alphabetical by local name, which
            # is not the order anyone writes these in by hand.
            f"<cp:category>{_esc(meta.template_name)}</cp:category>"
            f'<dcterms:created xsi:type="dcterms:W3CDTF">{_esc(stamp)}</dcterms:created>'
            f"<dc:creator>{_esc(meta.author or meta.organisation)}</dc:creator>"
            f"<cp:lastModifiedBy>{_esc(meta.author or meta.organisation)}</cp:lastModifiedBy>"
            f'<dcterms:modified xsi:type="dcterms:W3CDTF">{_esc(stamp)}</dcterms:modified>'
            f"<dc:subject>{_esc(meta.subtitle)}</dc:subject>"
            f"<dc:title>{_esc(meta.title)}</dc:title>"
            "</cp:coreProperties>"
        )


# --------------------------------------------------------------------------------------
# Small XML builders shared by the emitters
# --------------------------------------------------------------------------------------


def _col(
    header: str, width_pct: float, *, numeric: bool = False, align: Align = Align.LEFT
) -> TableColumn:
    return TableColumn(header=header, width_pct=width_pct, align=align, numeric=numeric)


def _page_break() -> str:
    return '<w:p><w:r><w:br w:type="page"/></w:r></w:p>'


def _field(instruction: str) -> str:
    """A simple Word field (PAGE, NUMPAGES). The literal between separate/end is the fallback."""
    return (
        '<w:r><w:fldChar w:fldCharType="begin"/></w:r>'
        f'<w:r><w:instrText xml:space="preserve"> {instruction} </w:instrText></w:r>'
        '<w:r><w:fldChar w:fldCharType="separate"/></w:r>'
        '<w:r><w:rPr><w:color w:val="5A6B87"/><w:sz w:val="17"/></w:rPr><w:t>1</w:t></w:r>'
        '<w:r><w:fldChar w:fldCharType="end"/></w:r>'
    )


def _cell(
    content: str,
    *,
    width_twip: int,
    fill: str | None = None,
    left_border: str | None = None,
    top_border: str | None = None,
) -> str:
    tc_pr = [f'<w:tcPr><w:tcW w:w="{width_twip}" w:type="dxa"/>']
    if left_border or top_border:
        borders = ["<w:tcBorders>"]
        if top_border:
            borders.append(
                f'<w:top w:val="single" w:sz="12" w:space="0" w:color="{_esc(top_border)}"/>'
            )
        if left_border:
            borders.append(
                f'<w:left w:val="single" w:sz="18" w:space="0" w:color="{_esc(left_border)}"/>'
            )
        borders.append("</w:tcBorders>")
        tc_pr.append("".join(borders))
    if fill:
        tc_pr.append(f'<w:shd w:val="clear" w:color="auto" w:fill="{_esc(fill)}"/>')
    tc_pr.append('<w:vAlign w:val="top"/></w:tcPr>')
    # A cell whose content is empty is invalid: w:tc must contain at least one block-level
    # element. Word repairs it by dropping the whole row.
    return f"<w:tc>{''.join(tc_pr)}{content or _para('')}</w:tc>"


def _tbl(
    rows: list[str],
    widths: list[int],
    total_twip: int,
    *,
    borders: bool,
    rule_color: str = "B8C4D9",
) -> str:
    grid = "".join(f'<w:gridCol w:w="{w}"/>' for w in widths)
    border_xml = ""
    if borders:
        border_xml = (
            "<w:tblBorders>"
            + "".join(
                f'<w:{edge} w:val="single" w:sz="4" w:space="0" w:color="{_esc(rule_color)}"/>'
                for edge in ("top", "left", "bottom", "right", "insideH", "insideV")
            )
            + "</w:tblBorders>"
        )
    # CT_TblPr sequence order: tblW, jc, tblCellSpacing, tblInd, tblBorders, shd, tblLayout,
    # tblCellMar, tblLook. Borders must precede layout.
    return (
        "<w:tbl><w:tblPr>"
        f'<w:tblW w:w="{total_twip}" w:type="dxa"/>'
        f"{border_xml}"
        '<w:tblLayout w:type="fixed"/>'
        "<w:tblCellMar>"
        '<w:top w:w="70" w:type="dxa"/><w:left w:w="100" w:type="dxa"/>'
        '<w:bottom w:w="70" w:type="dxa"/><w:right w:w="100" w:type="dxa"/>'
        "</w:tblCellMar>"
        "</w:tblPr>"
        f"<w:tblGrid>{grid}</w:tblGrid>"
        f"{''.join(rows)}"
        "</w:tbl>"
    )


_ROOT_RELS = (
    f'{_XML_DECL}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/'
    'relationships">'
    f'<Relationship Id="rId1" Type="{_NS_R}/officeDocument" Target="word/document.xml"/>'
    '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/'
    'relationships/metadata/core-properties" Target="docProps/core.xml"/>'
    f'<Relationship Id="rId3" Type="{_NS_R}/extended-properties" Target="docProps/app.xml"/>'
    "</Relationships>"
)

_APP_XML = (
    f'{_XML_DECL}<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/'
    'extended-properties">'
    "<Application>Design Prototype Workshop</Application>"
    "</Properties>"
)

_SETTINGS_XML = (
    f'{_XML_DECL}<w:settings xmlns:w="{_NS_W}">'
    '<w:zoom w:percent="100"/>'
    '<w:defaultTabStop w:val="720"/>'
    # Without this Word shows the TOC placeholder text until the reader knows to press F9.
    '<w:updateFields w:val="true"/>'
    '<w:compat><w:compatSetting w:name="compatibilityMode" '
    'w:uri="http://schemas.microsoft.com/office/word" w:val="15"/></w:compat>'
    "</w:settings>"
)

_NUMBERING_XML = (
    f'{_XML_DECL}<w:numbering xmlns:w="{_NS_W}">'
    '<w:abstractNum w:abstractNumId="0"><w:multiLevelType w:val="hybridMultilevel"/>'
    '<w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/>'
    '<w:lvlText w:val="•"/><w:lvlJc w:val="left"/>'
    '<w:pPr><w:ind w:left="567" w:hanging="284"/></w:pPr>'
    '<w:rPr><w:rFonts w:ascii="Symbol" w:hAnsi="Symbol" w:hint="default"/></w:rPr></w:lvl>'
    "</w:abstractNum>"
    '<w:abstractNum w:abstractNumId="1"><w:multiLevelType w:val="hybridMultilevel"/>'
    '<w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/>'
    '<w:lvlText w:val="%1."/><w:lvlJc w:val="left"/>'
    '<w:pPr><w:ind w:left="567" w:hanging="284"/></w:pPr></w:lvl>'
    "</w:abstractNum>"
    '<w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>'
    '<w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>'
    "</w:numbering>"
)


def _styles_xml(theme: ReportTheme) -> str:
    """The theme rendered as real Word styles, so a reader can restyle the whole report.

    Emitting direct formatting on every run instead would look identical and be unusable: an
    officer who wants the department's own heading colour would have to touch every heading.
    """
    body = _esc(theme.body_font)
    head = _esc(theme.heading_font)
    cs = _esc(theme.complex_font)
    half = round(theme.base_size_pt * 2)

    headings = []
    for level, (size, color, before) in enumerate(
        [
            (18.0, theme.accent, 320),
            (14.0, theme.accent, 280),
            (12.0, theme.accent_soft, 240),
            (11.0, theme.muted, 200),
        ],
        start=1,
    ):
        headings.append(
            f'<w:style w:type="paragraph" w:styleId="Heading{level}">'
            f'<w:name w:val="heading {level}"/><w:basedOn w:val="Normal"/>'
            f'<w:next w:val="Normal"/><w:qFormat/>'
            f"<w:pPr><w:keepNext/><w:keepLines/>"
            f'<w:spacing w:before="{before}" w:after="120"/>'
            f'<w:outlineLvl w:val="{level - 1}"/></w:pPr>'
            f'<w:rPr><w:rFonts w:ascii="{head}" w:hAnsi="{head}" w:cs="{cs}"/><w:b/><w:bCs/>'
            f'<w:color w:val="{_esc(color)}"/>'
            f'<w:sz w:val="{int(size * 2)}"/><w:szCs w:val="{int(size * 2)}"/></w:rPr>'
            "</w:style>"
        )

    def simple(style_id: str, name: str, ppr: str, rpr: str) -> str:
        return (
            f'<w:style w:type="paragraph" w:styleId="{style_id}">'
            f'<w:name w:val="{name}"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/>'
            f"<w:qFormat/><w:pPr>{ppr}</w:pPr><w:rPr>{rpr}</w:rPr></w:style>"
        )

    return (
        f'{_XML_DECL}<w:styles xmlns:w="{_NS_W}">'
        "<w:docDefaults><w:rPrDefault><w:rPr>"
        f'<w:rFonts w:ascii="{body}" w:hAnsi="{body}" w:cs="{cs}"/>'
        f'<w:color w:val="{_esc(theme.ink)}"/>'
        f'<w:sz w:val="{half}"/><w:szCs w:val="{half}"/>'
        '<w:lang w:val="en-IN" w:bidi="hi-IN"/>'
        "</w:rPr></w:rPrDefault>"
        "<w:pPrDefault><w:pPr>"
        '<w:spacing w:after="120" w:line="276" w:lineRule="auto"/>'
        "</w:pPr></w:pPrDefault></w:docDefaults>"
        '<w:style w:type="paragraph" w:default="1" w:styleId="Normal">'
        '<w:name w:val="Normal"/><w:qFormat/></w:style>'
        + "".join(headings)
        + simple(
            "Caption",
            "caption",
            '<w:spacing w:before="0" w:after="200"/><w:jc w:val="center"/>',
            f'<w:i/><w:iCs/><w:color w:val="{_esc(theme.muted)}"/><w:sz w:val="17"/>',
        )
        + simple(
            "ReportNote",
            "Report Note",
            '<w:spacing w:after="120"/>',
            f'<w:color w:val="{_esc(theme.muted)}"/><w:sz w:val="18"/>',
        )
        + simple(
            "ReportQuote",
            "Report Quote",
            '<w:ind w:left="420"/><w:spacing w:after="140"/>',
            f'<w:i/><w:iCs/><w:color w:val="{_esc(theme.accent_soft)}"/>',
        )
        + simple(
            "TOCHeading",
            "TOC Heading",
            '<w:spacing w:before="240" w:after="120"/><w:outlineLvl w:val="9"/>',
            f'<w:rFonts w:ascii="{head}" w:hAnsi="{head}"/><w:b/>'
            f'<w:color w:val="{_esc(theme.accent)}"/><w:sz w:val="32"/>',
        )
        + "</w:styles>"
    )


# --------------------------------------------------------------------------------------
# Native Word charts
# --------------------------------------------------------------------------------------
#
# A figure in this report used to be a PNG in every case, and for the map it still is. For the
# statistical figures it is now a real ``c:chart`` part: vector, restylable, and carrying its own
# numbers in an embedded workbook that "Edit Data" opens. The reason is not polish. A ministry
# receiving this .docx pulls one chart into a presentation and enlarges it; a 200 dpi raster of a
# five-by-seven bitmap font at A3 is unusable, and the officer's alternative is to retype the
# numbers off the picture — which is how a figure and its source table start to disagree.
#
# FOUR PARTS AND TWO RELATIONSHIP HOPS, all of which have to be right together:
#
#     word/charts/chart1.xml            the c:chartSpace
#     word/charts/_rels/chart1.xml.rels chart -> workbook, relationship type ".../package"
#     word/embeddings/chart1.xlsx       the workbook, itself a zip of five XML parts
#     word/_rels/document.xml.rels      document -> chart, relationship type ".../chart"
#     [Content_Types].xml               an Override for the chart part, a Default for "xlsx"
#
# THE WORKBOOK IS HAND-WRITTEN, and openpyxl is deliberately not used even though it is already
# installed. This module's whole premise is that the phone runs the identical algorithm against
# java.util.zip; an embedded workbook produced by a Python library on the server and by hand on
# the device would be two different files inside two documents claiming to be the same report.
# An xlsx is a zip of five small XML parts. That is a smaller price than the drift.
#
# THE CACHE IS NOT OPTIONAL. c:cat and c:val each carry BOTH a reference into the workbook and a
# literal cache of the values. Word renders from the cache and only touches the workbook when the
# reader asks to edit. A chart written without the cache draws an empty plot area on every machine
# that has not opened the workbook — which is every machine, because Word does not open it on load.
#
# WHAT STAYS A RASTER. The map, always: it is a pinned projection, not a Word chart type. And any
# chart whose series is empty after ``clean_series`` — the rasteriser draws an explaining frame
# ("No values recorded.") for that case and a native chart cannot say anything at all, so the PNG
# is the better document. Degrading is correct; dropping the figure never is.

_NS_C = "http://schemas.openxmlformats.org/drawingml/2006/chart"
_NS_SS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
_NS_CT = "http://schemas.openxmlformats.org/package/2006/content-types"
_NS_PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"

_CHART_PART_TYPE = "application/vnd.openxmlformats-officedocument.drawingml.chart+xml"
_XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

#: Chart relationships are ``cId1``, ``cId2`` … and NOT ``rId``-anything.
#:
#: An Id in an OPC relationship part is an xsd:ID — any NCName will do, and Word has never cared
#: that its own writer happens to emit "rId". Using a disjoint prefix is what makes it *impossible*
#: for a chart to collide with an image, rather than merely unlikely: ``_register_image`` derives
#: an image's id arithmetically from ``len(self._media)``, so any scheme that also counted charts
#: into that space would hand the same id to a picture and a chart the moment a chart was emitted
#: between two photographs. That is exactly the duplicate-id failure the module docstring above
#: describes, and its symptom is silence — Word drops both parts and reports nothing.
_CHART_RID_PREFIX = "cId"

#: Axis ids only have to be unique inside one chart part, and every chart here has one pair.
_CAT_AX_ID = 111111111
_VAL_AX_ID = 222222222

#: The relationship id INSIDE a chart part, pointing at that chart's own workbook.
_WORKBOOK_RID = "rId1"

#: 12,34,567 rather than 1,234,567, as an Excel format code.
#:
#: The same grouping ``report_builder.format_value`` gives every money value in the report and
#: ``report_chart.format_number`` prints on the raster's axis. Without it Word's General format
#: prints an eight-digit sanctioned amount as one unbroken run of digits, directly beside a cost
#: table that groups it — and an officer reconciling the two has to count characters. Three
#: conditional sections because Excel has no Indian grouping primitive: crore, lakh, and
#: everything else (which is also where negatives land, keeping their sign).
_INDIAN_NUMBER_FORMAT = "[>=10000000]##\\,##\\,##\\,##0.##;[>=100000]##\\,##\\,##0.##;##\\,##0.##"

#: Vertical room a native horizontal-bar chart needs PER CATEGORY, and for its axis, in mm.
#:
#: The one place the native chart deliberately does NOT copy the rasteriser's box, and the reason
#: is type size. ``report_raster``'s five-by-seven face is about 1.4 mm tall printed at 200 dpi;
#: Word's 8 pt is 2.8 mm. So the height that fits six cost heads in the PNG fits three in a real
#: chart, and Word's response to labels that do not fit is to DROP them — silently, and starting
#: from the ones in the middle. A cost chart missing "Transport" between "Material" and "Labour"
#: is not a smaller figure, it is a wrong one, and nothing in the document says so.
#:
#: 6.5 mm is one 8 pt line plus the gap the bar sits in; 14 mm is the value axis, its unit title
#: and the top and bottom margins. Both are floors, so a chart the raster already draws taller
#: keeps its own geometry and the .docx and the .pdf still agree wherever they can.
_NATIVE_ROW_MM = 6.5
_NATIVE_CHROME_MM = 14.0

#: A fixed timestamp for every entry of an embedded workbook.
#:
#: zipfile stamps "now" by default, which makes two renders of one unchanged workshop produce two
#: different byte strings. The document is a deliverable that gets diffed and re-uploaded; a figure
#: nobody edited must not show as changed.
#:
#: NOT 1980-01-01, which is the obvious choice and the wrong one. A zip stores DOS dates in LOCAL
#: time and cannot represent anything before 1980-01-01 at all, so the phone's ``java.util.zip``
#: — which converts through the device's own time zone — would clamp the epoch itself on any
#: device west of Greenwich, and the two writers would stamp different dates. Noon on the second
#: day leaves twelve hours of slack in both directions, which covers every real time zone.
_ZIP_EPOCH = (1980, 1, 2, 12, 0, 0)


def _srgb(rgb: tuple[int, int, int]) -> str:
    """``(31, 56, 100)`` -> ``"1F3864"``. DrawingML wants six hex digits, uppercase."""
    r, g, b = (max(0, min(255, channel)) for channel in rgb)
    return f"{r:02X}{g:02X}{b:02X}"


def _cell_number(value: float) -> str:
    """A number as a spreadsheet cell and a chart cache carry it: plain, never in exponent form.

    ``str(1e7)`` is ``"10000000.0"`` but ``str(1e16)`` is ``"1e+16"``, and a ``c:v`` holding
    ``1e+16`` is read by Word as text — the point vanishes from the plot with no error. Formatting
    through ``%f`` and trimming keeps every magnitude a rupee figure can reach in plain digits.
    """
    if math.isnan(value) or math.isinf(value):
        # clean_series has already dropped these, so reaching here means a caller went round it.
        # A "nan" in a c:v is read by Word as text and the point silently leaves the plot.
        return "0"
    if value == int(value) and abs(value) < 1e15:
        return str(int(value))
    return f"{value:.6f}".rstrip("0").rstrip(".")


def _chart_txpr(theme: ReportTheme, size_pt: float, colour: str) -> str:
    """Default run properties for every string a chart part draws.

    ``a:cs`` is here for exactly the reason ``w:cs`` is on a body run: it is the only lever
    DrawingML has over shaping, and a cost head or a craft name in Odia renders as boxes without
    it. This is the one place the native chart is strictly BETTER than the PNG it replaces —
    ``report_raster``'s five-by-seven face is ASCII only and drops an Indic label in silence, so a
    category called "ସମ୍ବଲପୁରୀ" was previously an EMPTY slot under a bar.
    """
    hundredths = round(size_pt * 100)
    return (
        "<c:txPr><a:bodyPr/><a:lstStyle/><a:p><a:pPr>"
        f'<a:defRPr sz="{hundredths}" b="0" i="0">'
        f'<a:solidFill><a:srgbClr val="{_esc(colour)}"/></a:solidFill>'
        f'<a:latin typeface="{_esc(theme.body_font)}"/>'
        f'<a:cs typeface="{_esc(theme.complex_font)}"/>'
        '</a:defRPr></a:pPr><a:endParaRPr lang="en-IN"/></a:p></c:txPr>'
    )


def _solid_fill(colour_hex: str) -> str:
    return f'<a:solidFill><a:srgbClr val="{_esc(colour_hex)}"/></a:solidFill>'


def _str_ref(formula: str, values: list[str]) -> str:
    """A ``c:strRef`` — the workbook range AND the literal cache Word actually renders from."""
    points = "".join(
        f'<c:pt idx="{i}"><c:v>{_esc(clean_text(v))}</c:v></c:pt>' for i, v in enumerate(values)
    )
    return (
        f"<c:strRef><c:f>{_esc(formula)}</c:f><c:strCache>"
        f'<c:ptCount val="{len(values)}"/>{points}</c:strCache></c:strRef>'
    )


def _num_ref(formula: str, values: list[float]) -> str:
    points = "".join(
        f'<c:pt idx="{i}"><c:v>{_cell_number(v)}</c:v></c:pt>' for i, v in enumerate(values)
    )
    return (
        f"<c:numRef><c:f>{_esc(formula)}</c:f><c:numCache>"
        "<c:formatCode>General</c:formatCode>"
        f'<c:ptCount val="{len(values)}"/>{points}</c:numCache></c:numRef>'
    )


def _data_labels(
    theme: ReportTheme,
    *,
    position: str | None,
    show_value: bool,
    show_percent: bool,
    number_format: str,
) -> str:
    """One ``c:dLbls``.

    CT_DLbls is a SEQUENCE and Word rejects the part outright if it is shuffled: numFmt, spPr,
    txPr, dLblPos, then the six show-flags in their fixed order. ``dLblPos`` is omitted for the
    circular kinds on purpose — a doughnut has no legal value for it, and Word refuses to open a
    chart part that gives it one.
    """
    parts = [
        "<c:dLbls>",
        f'<c:numFmt formatCode="{_esc(number_format)}" sourceLinked="0"/>',
        "<c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr>",
        _chart_txpr(theme, 8.0, theme.ink),
    ]
    if position:
        parts.append(f'<c:dLblPos val="{position}"/>')
    parts.append('<c:showLegendKey val="0"/>')
    parts.append(f'<c:showVal val="{1 if show_value else 0}"/>')
    parts.append('<c:showCatName val="0"/><c:showSerName val="0"/>')
    parts.append(f'<c:showPercent val="{1 if show_percent else 0}"/>')
    parts.append('<c:showBubbleSize val="0"/>')
    parts.append("</c:dLbls>")
    return "".join(parts)


def _axis_title(theme: ReportTheme, text: str, *, vertical: bool) -> str:
    """The unit, as a real axis title.

    ``ChartBlock.unit`` is printed once beside the axis and never on every value — the model says
    so. The rasteriser draws it in a corner because it has nowhere better; a native chart has the
    place the schema made for it.
    """
    if not text:
        return ""
    body = '<a:bodyPr rot="-5400000" vert="horz"/>' if vertical else "<a:bodyPr/>"
    return (
        "<c:title><c:tx><c:rich>"
        f"{body}<a:lstStyle/><a:p><a:pPr>"
        f'<a:defRPr sz="900" b="0" i="0">{_solid_fill(theme.muted)}'
        f'<a:latin typeface="{_esc(theme.body_font)}"/>'
        f'<a:cs typeface="{_esc(theme.complex_font)}"/></a:defRPr></a:pPr>'
        f"<a:r><a:t>{_esc(clean_text(text))}</a:t></a:r></a:p>"
        '</c:rich></c:tx><c:overlay val="0"/></c:title>'
    )


def _cartesian_axes(block: ChartBlock, theme: ReportTheme) -> str:
    """The ``c:catAx`` / ``c:valAx`` pair shared by the bar and line kinds.

    CT_CatAx and CT_ValAx are sequences too, and the two differ in their tails: a category axis
    ends with auto/lblAlgn/lblOffset/noMultiLvlLbl, a value axis with crossBetween. Writing one
    with the other's tail produces a part Word silently declines to draw, leaving a correctly
    sized EMPTY frame in the document — the failure that looks most like "the chart is missing".
    """
    horizontal = block.kind is ChartKind.HORIZONTAL_BAR
    cat_pos, val_pos = ("l", "b") if horizontal else ("b", "l")
    # A horizontal bar chart must run its first category along the TOP, because that is where the
    # rasterised version puts it and because a cost sheet reads downward from its largest head.
    # Word's default for a bar chart is bottom-up, which silently reverses every figure.
    cat_orientation = "maxMin" if horizontal else "minMax"
    grid_line = f'<a:ln w="9525">{_solid_fill(theme.rule)}</a:ln>'
    return (
        # -- category axis --
        f'<c:catAx><c:axId val="{_CAT_AX_ID}"/>'
        f'<c:scaling><c:orientation val="{cat_orientation}"/></c:scaling>'
        '<c:delete val="0"/>'
        f'<c:axPos val="{cat_pos}"/>'
        '<c:numFmt formatCode="General" sourceLinked="1"/>'
        '<c:majorTickMark val="none"/><c:minorTickMark val="none"/>'
        '<c:tickLblPos val="nextTo"/>'
        f"<c:spPr><a:noFill/>{grid_line}</c:spPr>"
        + _chart_txpr(theme, 8.0, theme.muted)
        + f'<c:crossAx val="{_VAL_AX_ID}"/>'
        '<c:crosses val="autoZero"/>'
        '<c:auto val="1"/><c:lblAlgn val="ctr"/><c:lblOffset val="100"/>'
        '<c:noMultiLvlLbl val="0"/>'
        "</c:catAx>"
        # -- value axis --
        f'<c:valAx><c:axId val="{_VAL_AX_ID}"/>'
        '<c:scaling><c:orientation val="minMax"/></c:scaling>'
        '<c:delete val="0"/>'
        f'<c:axPos val="{val_pos}"/>'
        f"<c:majorGridlines><c:spPr>{grid_line}</c:spPr></c:majorGridlines>"
        + _axis_title(theme, block.unit, vertical=not horizontal)
        + f'<c:numFmt formatCode="{_esc(_INDIAN_NUMBER_FORMAT)}" sourceLinked="0"/>'
        '<c:majorTickMark val="none"/><c:minorTickMark val="none"/>'
        '<c:tickLblPos val="nextTo"/>'
        "<c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr>"
        + _chart_txpr(theme, 8.0, theme.muted)
        + f'<c:crossAx val="{_CAT_AX_ID}"/>'
        '<c:crosses val="autoZero"/>'
        # midCat puts a line's first point ON the axis rather than half a slot in, which is where
        # the rasteriser draws it and where a follow-up at three months belongs.
        f'<c:crossBetween val="{"midCat" if block.kind is ChartKind.LINE else "between"}"/>'
        "</c:valAx>"
    )


def _bar_group(
    block: ChartBlock,
    series: list[tuple[str, float]],
    theme: ReportTheme,
    tx: str,
    cat: str,
    val: str,
) -> str:
    """``c:barChart`` — vertical when the categories are a sequence, horizontal when they are words.

    CT_BarSer sequence: idx, order, tx, spPr, invertIfNegative, dPt*, dLbls, cat, val.
    """
    soft = rgb_of(theme.accent_soft, (47, 84, 150))
    ink = rgb_of(theme.ink, (27, 27, 27))
    # A negative bar is darkened rather than recoloured, exactly as the rasteriser darkens it. A
    # credited cost line is still that cost head, not a new category, and a second hue would say
    # otherwise. invertIfNegative is Word's own lever for this and it forces a PATTERN fill, which
    # photocopies to noise — hence per-point overrides instead.
    negative = _srgb(mix(soft, ink, 0.35))
    points = "".join(
        f'<c:dPt><c:idx val="{i}"/><c:invertIfNegative val="0"/><c:bubble3D val="0"/>'
        f"<c:spPr>{_solid_fill(negative)}<a:ln><a:noFill/></a:ln></c:spPr></c:dPt>"
        for i, (_label, value) in enumerate(series)
        if value < 0
    )
    direction = "bar" if block.kind is ChartKind.HORIZONTAL_BAR else "col"
    return (
        "<c:barChart>"
        f'<c:barDir val="{direction}"/>'
        '<c:grouping val="clustered"/><c:varyColors val="0"/>'
        f'<c:ser><c:idx val="0"/><c:order val="0"/>{tx}'
        f"<c:spPr>{_solid_fill(_srgb(soft))}<a:ln><a:noFill/></a:ln></c:spPr>"
        '<c:invertIfNegative val="0"/>'
        f"{points}"
        + _data_labels(
            theme,
            position="outEnd",
            show_value=True,
            show_percent=False,
            number_format=_INDIAN_NUMBER_FORMAT,
        )
        + f"{cat}{val}</c:ser>"
        # 61%: the rasteriser draws a bar at 0.62 of its slot, so the gap is the other 0.38 —
        # 0.38/0.62 as a percentage of bar width, which is what gapWidth means.
        '<c:gapWidth val="61"/>'
        f'<c:axId val="{_CAT_AX_ID}"/><c:axId val="{_VAL_AX_ID}"/>'
        "</c:barChart>"
    )


def _line_group(theme: ReportTheme, tx: str, cat: str, val: str) -> str:
    """``c:lineChart``. CT_LineSer: idx, order, tx, spPr, marker, dPt*, dLbls, cat, val, smooth."""
    accent = _srgb(rgb_of(theme.accent, (31, 56, 100)))
    return (
        '<c:lineChart><c:grouping val="standard"/><c:varyColors val="0"/>'
        f'<c:ser><c:idx val="0"/><c:order val="0"/>{tx}'
        # 12700 EMU = 1 pt, which is what the rasteriser's 2.4 px at 200 dpi comes to. A hairline
        # default would disappear on the photocopy every one of these reports takes.
        f'<c:spPr><a:ln w="12700" cap="rnd">{_solid_fill(accent)}<a:round/></a:ln></c:spPr>'
        '<c:marker><c:symbol val="circle"/><c:size val="6"/>'
        f"<c:spPr>{_solid_fill(accent)}"
        f'<a:ln w="9525">{_solid_fill("FFFFFF")}</a:ln></c:spPr></c:marker>'
        + _data_labels(
            theme,
            position="t",
            show_value=True,
            show_percent=False,
            number_format=_INDIAN_NUMBER_FORMAT,
        )
        + f'{cat}{val}<c:smooth val="0"/></c:ser>'
        # A line of three follow-up points with no markers is a line nobody can read a value off.
        '<c:marker val="1"/>'
        f'<c:axId val="{_CAT_AX_ID}"/><c:axId val="{_VAL_AX_ID}"/>'
        "</c:lineChart>"
    )


def _circular_group(
    block: ChartBlock,
    series: list[tuple[str, float]],
    theme: ReportTheme,
    tx: str,
    cat: str,
    val: str,
) -> str:
    """``c:pieChart`` or ``c:doughnutChart``, one ``c:dPt`` per slice.

    Every slice is coloured explicitly from ``report_chart.slice_colours`` rather than left to
    Word's theme palette, and that is the photocopier argument again: Word's default categorical
    palette collapses to four indistinguishable greys the first time this report is copied, and
    the legend then names four slices a reader cannot tell apart. The monochrome lightness ramp
    survives the copy — and it is the SAME ramp the .pdf of this workshop uses, which is the point.
    """
    from app.services.report_chart import slice_colours

    colours = slice_colours(len(series), theme)
    points = "".join(
        f'<c:dPt><c:idx val="{i}"/><c:bubble3D val="0"/>'
        f"<c:spPr>{_solid_fill(_srgb(colour))}"
        # A white keyline between slices: without it two adjacent steps of a lightness ramp read
        # as one slice on a laser print.
        f'<a:ln w="19050">{_solid_fill("FFFFFF")}</a:ln></c:spPr></c:dPt>'
        for i, colour in enumerate(colours)
    )
    donut = block.kind is ChartKind.DONUT
    tag = "doughnutChart" if donut else "pieChart"
    # 55 matches the rasteriser's inner radius of 0.55 of the outer.
    tail = '<c:firstSliceAng val="0"/>' + ('<c:holeSize val="55"/>' if donut else "")
    return (
        f'<c:{tag}><c:varyColors val="1"/>'
        f'<c:ser><c:idx val="0"/><c:order val="0"/>{tx}{points}'
        # The share, not the count: the count is in the legend and in the table this figure sits
        # beside, and a slice labelled with both is unreadable at a quarter of a page.
        + _data_labels(
            theme, position=None, show_value=False, show_percent=True, number_format="0%"
        )
        + f"{cat}{val}</c:ser>{tail}</c:{tag}>"
    )


def chart_space_xml(block: ChartBlock, series: list[tuple[str, float]], theme: ReportTheme) -> str:
    """The whole ``word/charts/chartN.xml`` for one block.

    CT_ChartSpace is a sequence: roundedCorners, chart, spPr, txPr, externalData. CT_Chart is a
    sequence too: autoTitleDeleted, plotArea, legend, plotVisOnly, dispBlanksAs. Both are checked
    by ``tests/test_report_docx_chart.py``, which asserts the emission order rather than trusting
    it — a shuffled sequence is schema-invalid and Word's only report of it is "we found a problem
    with some content", offering to repair the document by deleting the chart.
    """
    rows = len(series)
    labels = [label for label, _value in series]
    values = [value for _label, value in series]
    last = rows + 1  # row 1 is the header, so n categories end on row n+1
    name = clean_text(block.title) or "Value"

    tx = "<c:tx>" + _str_ref("Sheet1!$B$1", [name]) + "</c:tx>"
    cat = "<c:cat>" + _str_ref(f"Sheet1!$A$2:$A${last}", labels) + "</c:cat>"
    val = "<c:val>" + _num_ref(f"Sheet1!$B$2:$B${last}", values) + "</c:val>"

    if block.kind.is_circular:
        group = _circular_group(block, series, theme, tx, cat, val)
        axes = ""
        # A pie without a legend is a ring of coloured wedges nobody can name. The rasteriser
        # draws its own legend for exactly this reason; here the schema has one.
        legend = (
            '<c:legend><c:legendPos val="r"/><c:overlay val="0"/>'
            "<c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr>"
            + _chart_txpr(theme, 8.5, theme.muted)
            + "</c:legend>"
        )
    else:
        if block.kind is ChartKind.LINE:
            group = _line_group(theme, tx, cat, val)
        else:
            group = _bar_group(block, series, theme, tx, cat, val)
        axes = _cartesian_axes(block, theme)
        # One series has nothing to distinguish, so a legend would print the figure's own title a
        # second time under it.
        legend = ""

    return (
        f"{_XML_DECL}"
        f'<c:chartSpace xmlns:c="{_NS_C}" xmlns:a="{_NS_A}" xmlns:r="{_NS_R}">'
        '<c:roundedCorners val="0"/>'
        "<c:chart>"
        # The block's title is emitted ABOVE the drawing as real Word text, by the same code that
        # titles the map — so it can carry Odia, so it is searchable, and so restyling the report
        # restyles it. Without autoTitleDeleted Word invents a second title from the series name
        # and prints it inside the frame, directly under the real one.
        '<c:autoTitleDeleted val="1"/>'
        f"<c:plotArea><c:layout/>{group}{axes}"
        "<c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr>"
        "</c:plotArea>"
        f"{legend}"
        '<c:plotVisOnly val="1"/><c:dispBlanksAs val="gap"/>'
        "</c:chart>"
        # No fill and no outline: the figure sits on the page like the picture it replaces, rather
        # than in the bordered box Word's default chart style draws.
        "<c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr>"
        + _chart_txpr(theme, 8.5, theme.muted)
        + f'<c:externalData r:id="{_WORKBOOK_RID}"><c:autoUpdate val="0"/></c:externalData>'
        "</c:chartSpace>"
    )


def chart_workbook_xlsx(block: ChartBlock, series: list[tuple[str, float]]) -> bytes:
    """The embedded workbook for one chart: a real .xlsx, written by hand.

    Five parts is the whole of a valid spreadsheet package. Categories go down column A from row
    2, values down column B, and B1 carries the series name — which is the layout the ``c:f``
    formulas in :func:`chart_space_xml` reference, so the two must be changed together or "Edit
    Data" opens a sheet whose ranges point at empty cells.

    Strings are written inline (``t="inlineStr"``) rather than through a shared-strings part.
    Sharing pays for itself across thousands of repeated cells; a chart has at most a few dozen
    distinct labels, and the part it saves is a sixth part to keep consistent on two platforms.
    """
    name = clean_text(block.title) or "Value"

    def inline(ref: str, text: str) -> str:
        return f'<c r="{ref}" t="inlineStr"><is><t xml:space="preserve">{_esc(text)}</t></is></c>'

    rows_xml = [f'<row r="1">{inline("B1", name)}</row>']
    for index, (label, value) in enumerate(series):
        r = index + 2
        rows_xml.append(
            f'<row r="{r}">{inline(f"A{r}", clean_text(label))}'
            f'<c r="B{r}"><v>{_cell_number(value)}</v></c></row>'
        )

    sheet = (
        f'{_XML_DECL}<worksheet xmlns="{_NS_SS}">'
        f'<dimension ref="A1:B{len(series) + 1}"/>'
        f"<sheetData>{''.join(rows_xml)}</sheetData></worksheet>"
    )
    workbook = (
        f'{_XML_DECL}<workbook xmlns="{_NS_SS}" xmlns:r="{_NS_R}">'
        '<sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>'
    )
    workbook_rels = (
        f'{_XML_DECL}<Relationships xmlns="{_NS_PKG_REL}">'
        f'<Relationship Id="rId1" Type="{_NS_R}/worksheet" Target="worksheets/sheet1.xml"/>'
        f'<Relationship Id="rId2" Type="{_NS_R}/styles" Target="styles.xml"/>'
        "</Relationships>"
    )
    root_rels = (
        f'{_XML_DECL}<Relationships xmlns="{_NS_PKG_REL}">'
        f'<Relationship Id="rId1" Type="{_NS_R}/officeDocument" Target="xl/workbook.xml"/>'
        "</Relationships>"
    )
    # The minimum styles part Excel will accept. It is optional in the schema and NOT optional in
    # practice: Excel repairs a workbook that references cell format 0 without defining it, and a
    # repair prompt on "Edit Data" reads as a corrupt report.
    styles = (
        f'{_XML_DECL}<styleSheet xmlns="{_NS_SS}">'
        '<fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>'
        '<fills count="2"><fill><patternFill patternType="none"/></fill>'
        '<fill><patternFill patternType="gray125"/></fill></fills>'
        '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>'
        '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
        '<cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>'
        # The "Normal" named style. Without it every reader that checks — openpyxl warns, Excel
        # substitutes — decides the workbook has no default style, and the substitution is the
        # kind of difference that turns into a repair prompt on somebody else's build.
        '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>'
        "</styleSheet>"
    )
    content_types = (
        f'{_XML_DECL}<Types xmlns="{_NS_CT}">'
        '<Default Extension="rels" '
        'ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-'
        'officedocument.spreadsheetml.sheet.main+xml"/>'
        '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.'
        'openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
        '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-'
        'officedocument.spreadsheetml.styles+xml"/>'
        "</Types>"
    )

    buffer = BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as z:
        for part, text in (
            ("[Content_Types].xml", content_types),
            ("_rels/.rels", root_rels),
            ("xl/workbook.xml", workbook),
            ("xl/_rels/workbook.xml.rels", workbook_rels),
            ("xl/styles.xml", styles),
            ("xl/worksheets/sheet1.xml", sheet),
        ):
            info = zipfile.ZipInfo(part, date_time=_ZIP_EPOCH)
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, text)
    return buffer.getvalue()


def chart_part_rels(workbook_name: str) -> str:
    """``word/charts/chartN.xml.rels`` — the one hop from a chart to its workbook.

    The relationship type is ``.../package``, not ``.../oleObject`` and not ``.../image``. A
    wrong type here does not stop the chart drawing (it renders from its cache) but breaks "Edit
    Data" with a dialog about a missing linked file, which is worse than no button at all.
    """
    return (
        f'{_XML_DECL}<Relationships xmlns="{_NS_PKG_REL}">'
        f'<Relationship Id="{_WORKBOOK_RID}" Type="{_NS_R}/package" '
        f'Target="../embeddings/{_esc(workbook_name)}"/>'
        "</Relationships>"
    )


# --------------------------------------------------------------------------------------
# Public entry point
# --------------------------------------------------------------------------------------


def render_docx(document: ReportDocument, load_image: ImageLoader) -> tuple[bytes, list[str]]:
    """Render ``document`` to .docx bytes.

    Returns the bytes and the list of image sources that could not be embedded, which the
    caller surfaces as export warnings. This is synchronous and CPU-bound; the route runs it
    inside ``asyncio.to_thread``, exactly as ``xlsx_report`` is run.
    """
    writer = DocxWriter(document, load_image)
    data = writer.build()
    return data, writer.dropped_images
