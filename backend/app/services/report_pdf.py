"""Renders a :class:`~app.services.report_model.ReportDocument` to PDF through ReportLab.

This is the *server* PDF path. The phone has its own, on
``android.graphics.pdf.PdfDocument``, and the two are deliberately written as the same
algorithm rather than the same library:

    measure a block against the text column  ->  does it fit in the space left on this page?
    ->  no: finish the page, start a new one  ->  draw it at the cursor  ->  advance the cursor

Every block below is laid out by that loop, on ReportLab's low-level ``canvas`` API. Platypus
would have been shorter and is the obvious choice for a Python-only report — it is not used
here precisely because its ``Flowable`` machinery has no counterpart on Android, and a report
whose server copy and phone copy paginate differently is a support ticket that cannot be
reproduced on either device. ``canvas.drawString``/``drawImage``/``rect`` map one-to-one onto
``Canvas.drawText``/``drawBitmap``/``drawRect``, so the Kotlin port is a transliteration.

Two honest limitations, both of which the DOCX path does not share:

**Text shaping is ReportLab's, not HarfBuzz's.** ReportLab positions glyphs by advance width
and applies no complex-script reordering, so Devanagari, Odia, Bengali and Gujarati render with
correct glyphs but without the reordering and conjunct formation those scripts require. Short
Indic strings — a craft's ``localName``, an artisan's name — are legible; a paragraph of Odia
prose is not. Where a run is tagged with a non-Latin script this module draws it in a
Unicode-covering face so it is never tofu, and the report's DOCX twin (which Word shapes
properly) remains the authoritative artefact for Indic-heavy content. The on-device PDF has no
such limitation, because Android's text stack *is* HarfBuzz.

**Fonts are resolved from the host.** :func:`register_fonts` tries a list of well-known Unicode
faces and falls back to the built-in Helvetica if none are present, which is legal but covers
Latin only. A deployment that needs Indic PDF output must ship a font; the function returns
which family it actually bound so the caller can log it once at start-up rather than discover
it in a report.
"""

from __future__ import annotations

import logging
import os
from collections.abc import Callable
from contextlib import contextmanager
from dataclasses import replace
from io import BytesIO
from pathlib import Path

from reportlab.lib.utils import ImageReader
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas as rl_canvas

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

logger = logging.getLogger(__name__)

PDF_MIME = "application/pdf"

MM = 72.0 / 25.4   # PDF user space is points; every dimension in the model is relative or mm.

# Where to look, in preference order. Two things about this list are load-bearing:
#
# **.ttc collections count.** Nirmala UI — the one face that ships with Windows and covers every
# Indic block this app captures — is installed as `Nirmala.ttc`, not `.ttf`. A `.ttf`-only probe
# finds nothing on a Windows box that has perfectly good Devanagari and Odia, and silently
# produces a Latin-only report. ``subfont`` is the index within the collection.
#
# **The last entry always exists.** ReportLab vendors Vera (the font DejaVu was derived from),
# so ``_FONT_CANDIDATES`` can never come up empty. That matters because the alternative — the
# built-in Helvetica — is a standard-14 font ReportLab drives with WinAnsiEncoding, which has no
# rupee sign, no en-dash and no curly quotes. A cost sheet whose ₹ column prints as a black box
# is not an acceptable degradation for an Indian government report.


class _Face:
    """One candidate face: a family name, a file, and which subfont of a collection to take."""

    __slots__ = ("bold", "bold_index", "family", "regular", "regular_index")

    def __init__(self, family: str, regular: str, bold: str | None = None,
                 regular_index: int = 0, bold_index: int = 0) -> None:
        self.family = family
        self.regular = regular
        self.bold = bold or regular
        self.regular_index = regular_index
        self.bold_index = bold_index


def _vera_paths() -> tuple[str, str]:
    """ReportLab's vendored Vera, located through the installed package rather than guessed.

    ``reportlab.fonts`` is a namespace package with no ``__file__``, so the directory has to
    come from ``__path__``; ``reportlab.__file__`` is the reliable anchor either way.
    """
    import reportlab

    base = Path(reportlab.__file__).parent / "fonts"
    return str(base / "Vera.ttf"), str(base / "VeraBd.ttf")


def _candidates() -> list[_Face]:
    faces: list[_Face] = []
    override = os.environ.get("REPORT_PDF_FONT")
    if override:
        # Deployment escape hatch: point at any TTF/TTC and it wins outright. Documented in
        # backend/.env.example next to the other report settings.
        bold = os.environ.get("REPORT_PDF_FONT_BOLD") or override
        faces.append(_Face("ReportCustom", override, bold))
    faces += [
        # Windows: Nirmala UI is a collection; index 0 is the regular face.
        _Face("NirmalaUI", "C:/Windows/Fonts/Nirmala.ttc", "C:/Windows/Fonts/NirmalaB.ttf"),
        _Face("NirmalaUI", "C:/Windows/Fonts/Nirmala.ttf", "C:/Windows/Fonts/NirmalaB.ttf"),
        # Debian/Ubuntu containers.
        _Face("NotoSans",
              "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
              "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf"),
        _Face("DejaVuSans",
              "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
              "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"),
        _Face("LiberationSans",
              "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
              "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"),
    ]
    vera_regular, vera_bold = _vera_paths()
    faces.append(_Face("Vera", vera_regular, vera_bold))
    return faces


#: Windows' one face that covers every Indic block this app captures. A collection, so it is
#: bound by subfont index; kept as a pair because the `.ttc` and a plain `.ttf` install both exist.
_NIRMALA = (
    _Face("NirmalaUI", "C:/Windows/Fonts/Nirmala.ttc", "C:/Windows/Fonts/NirmalaB.ttf"),
    _Face("NirmalaUI", "C:/Windows/Fonts/Nirmala.ttf", "C:/Windows/Fonts/NirmalaB.ttf"),
)

#: The Debian Noto face for each script, from ``fonts-noto-core``. Regular AND bold for every one.
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
    """Faces that can draw ``script``, most preferred first. May legitimately come up empty.

    ONE FACE PER SCRIPT, NOT ONE "COMPLEX" FACE FOR ALL OF THEM. This used to be a single list
    and the first entry that bound won for every non-Latin run in the document — so on Debian,
    where the list met ``NotoSansDevanagari`` before ``NotoSansOriya``, EVERY Odia character in
    the report was sent to a Devanagari face that has no Odia glyphs and printed as a box. There
    is no face in ``fonts-noto-core`` that carries both, so no ordering of a single list could
    have been right: an app built for craft clusters from Bargarh to Kutch has to choose per run.
    Nirmala UI is the exception that made the old shape look workable — it covers every Indic
    block at once — and it exists only on Windows, which is where the developers ran it.

    ``Script.OTHER`` is not an Indic script at all: it is the bucket ``split_by_script`` puts
    symbols in, and the symbols this app actually prints are the ✓ and ✗ that stage 12's own help
    text tells a designer to type ("One check per line; prefix with ✓ or ✗"). Those live in
    Dingbats, which no Noto TEXT face and no Nirmala UI carries — DejaVu Sans does, so it leads
    that list. A designer following a field's instructions must not produce boxes.
    """
    faces: list[_Face] = []
    override = os.environ.get("REPORT_PDF_FONT_COMPLEX")
    if override and script is not Script.OTHER:
        # The escape hatch is addressed to the Indic problem — a deployment pointing it at a
        # Devanagari face must not thereby lose the tick as well.
        faces.append(_Face("ReportCustomComplex", override,
                           os.environ.get("REPORT_PDF_FONT_COMPLEX_BOLD") or override))
    if script is Script.OTHER:
        faces.append(_Face("DejaVuSans",
                           "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                           "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"))
        faces.append(_Face("DejaVuSansWin", "C:/Windows/Fonts/DejaVuSans.ttf"))
        faces.append(_Face("SegoeUISymbol", "C:/Windows/Fonts/seguisym.ttf"))
        return faces
    faces += list(_NIRMALA)
    if script is Script.DEVANAGARI:
        faces.append(_Face("Mangal", "C:/Windows/Fonts/mangal.ttf"))
    stem = _NOTO_STEM.get(script)
    if stem:
        faces.append(_Face(stem,
                           f"/usr/share/fonts/truetype/noto/{stem}-Regular.ttf",
                           f"/usr/share/fonts/truetype/noto/{stem}-Bold.ttf"))
    if script is Script.DEVANAGARI:
        faces.append(
            _Face("LohitDevanagari",
                  "/usr/share/fonts/truetype/lohit-devanagari/Lohit-Devanagari.ttf")
        )
    return faces


#: The characters this report prints that a Latin text face routinely does NOT have, each with
#: the face that would be asked to draw it and what it is doing in the document.
#:
#: THE FAILURE THESE EXIST TO CATCH IS SILENT AND TOTAL. A codepoint the bound face has no glyph
#: for is drawn as .notdef — an empty box — and nothing raises, nothing logs, and the PDF opens
#: perfectly. The deployed image shipped with no fonts installed at all, so it fell through to
#: ReportLab's vendored Vera and one workshop's report carried 1,031 of those boxes: "unit
#: realisation stands at □2,800 to □6,500", "priced between □900 and □3,500", and a ten-item
#: quality checklist in which every line began with a box instead of a tick. The .docx of the
#: same workshop carried all 501 rupee signs, 46 ticks and 14 crosses correctly, so the two files
#: in one download disagreed and only the PDF was wrong.
#:
#: ``script`` is the one ``split_by_script`` gives the character, and therefore decides which of
#: the two bound faces ``pick`` sends it to: the rupee sign resolves to LATIN and the Dingbats to
#: OTHER, which is the complex face.
_REQUIRED_GLYPHS: tuple[tuple[str, Script, str], ...] = (
    ("₹", Script.LATIN, "the rupee sign, in every cost sheet and price"),
    ("✓", Script.OTHER, "the tick, in every checklist"),
    ("✗", Script.OTHER, "the cross, in every checklist"),
)


def _drawable(font_name: str, character: str) -> bool | None:
    """Whether ``font_name`` has a glyph for ``character``. ``None`` means it cannot be told.

    A TrueType face registered by ReportLab carries its cmap as ``face.charToGlyph``. One of the
    standard-14 Type 1 fonts does not, and neither will whatever a future ReportLab uses — and an
    UNKNOWN must never be reported as a missing glyph, or a deployment would be warned about
    characters that print perfectly.
    """
    try:
        face = pdfmetrics.getFont(font_name).face
    except Exception:  # noqa: BLE001 - an unregistered name is "cannot tell", not a failure
        return None
    table = getattr(face, "charToGlyph", None)
    if not isinstance(table, dict) or not table:
        return None
    return ord(character) in table


#: The scripts whose absence makes a craft name unreadable rather than merely plainer.
_INDIC = frozenset(_NOTO_STEM)


class FontSet:
    """The faces a report needs, already resolved to names ReportLab will accept.

    ``by_script`` holds a (regular, bold) pair for each script a face was found for. A script
    with no entry falls back to the Latin pair, which draws it as boxes — visible, and reported.
    """

    def __init__(self, regular: str, bold: str,
                 by_script: dict[Script, tuple[str, str]]) -> None:
        self.regular = regular
        self.bold = bold
        self.by_script = by_script
        self.covers_indic = bool(_INDIC & set(by_script))
        #: The Indic scripts with no face at all, so a warning can NAME them.
        self.missing_scripts: list[Script] = sorted(
            (s for s in _INDIC if s not in by_script), key=lambda s: s.value
        )
        #: What the bound faces will draw as empty boxes, as ``[(character, why)]``. Read by
        #: ``design_workshops.render_report`` so the designer is told BEFORE they attach the file.
        self.missing_glyphs: list[tuple[str, str]] = [
            (character, purpose)
            for character, script, purpose in _REQUIRED_GLYPHS
            if _drawable(self.pick_for(script, bold=False), character) is False
        ]

    def pick_for(self, script: Script, *, bold: bool) -> str:
        regular, bold_name = self.by_script.get(script, (self.regular, self.bold))
        return bold_name if bold else regular

    def pick(self, run: Run) -> str:
        return self.pick_for(run.script, bold=run.bold)


_REGISTERED: set[str] = set()
#: Which registered name a font FILE was bound under, so one file used by several scripts is
#: parsed once. Nirmala UI is a four-megabyte collection that covers every Indic block, so binding
#: it afresh for each of the nine scripts would parse it nine times on the first export.
_BY_FILE: dict[tuple[str, int], str] = {}


def _register_one(name: str, path: str, index: int) -> str | None:
    """Register one face under ``name``; return the name, or ``None`` if it cannot be used."""
    already = _BY_FILE.get((path, index))
    if already is not None:
        return already
    if name in _REGISTERED:
        return name
    if not Path(path).exists():
        return None
    try:
        # subfontIndex is ignored for a plain .ttf and selects the face inside a .ttc.
        pdfmetrics.registerFont(TTFont(name, path, subfontIndex=index))
    except Exception:
        logger.warning("report_pdf: cannot register %s from %s", name, path, exc_info=True)
        return None
    _REGISTERED.add(name)
    _BY_FILE[(path, index)] = name
    return name


def _bind(face: _Face) -> tuple[str, str] | None:
    """Register ``face`` and return its (regular, bold) ReportLab names.

    Named after the FAMILY and not after the script asking for it, which is what lets one file
    serve nine scripts through a single registration — Nirmala UI covers every Indic block, and
    binding it once per script parsed four megabytes nine times on the first export.
    """
    regular = _register_one(face.family, face.regular, face.regular_index)
    if regular is None:
        return None
    bold = _register_one(f"{face.family}-Bold", face.bold, face.bold_index)
    # A family with no separate bold file is normal (Mangal, most Lohit faces). Reusing the
    # regular keeps the layout identical and merely loses the weight contrast.
    return regular, bold or regular


_CACHED: FontSet | None = None


def register_fonts(*, force: bool = False) -> FontSet:
    """Bind the best available fonts once per process, and say what was bound.

    Cached because ``registerFont`` parses the whole font file, and a report with sixty photos
    is slow enough already. ``force`` exists for the tests.
    """
    global _CACHED
    if _CACHED is not None and not force:
        return _CACHED
    if force:
        _REGISTERED.clear()
        _BY_FILE.clear()

    latin = next((r for r in (_bind(f) for f in _candidates()) if r), None)
    if latin is None:  # pragma: no cover - Vera ships with ReportLab, so this cannot happen
        logger.error("report_pdf: no usable font at all, including ReportLab's own Vera; "
                     "falling back to Helvetica, which cannot render the rupee sign.")
        latin = ("Helvetica", "Helvetica-Bold")

    # One binding attempt PER SCRIPT. The suffix keeps the registered names distinct while
    # `_BY_FILE` makes a face shared by several scripts — Nirmala UI covers all nine — parse once.
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
        # The layout still holds and the DOCX twin still carries the text correctly, so this is a
        # warning rather than an error — but it is logged once per process so a deployment
        # discovers it at start-up rather than in a report an officer has already printed. The
        # scripts are NAMED: "no Indic-capable font" was true of a server that could draw
        # Devanagari perfectly and printed every Odia craft name as boxes.
        logger.warning(
            "report_pdf: no font for %s. PDF exports will show boxes for text in those scripts. "
            "Install fonts-noto-core or set REPORT_PDF_FONT_COMPLEX to a font file. The .docx "
            "export is unaffected.",
            ", ".join(s.value for s in _CACHED.missing_scripts),
        )
    if _CACHED.missing_glyphs:
        # Reported to the DESIGNER as well (see design_workshops.render_report): a box where a
        # rupee sign belongs is not something the person attaching the file to a ministry
        # submission can be left to notice for themselves.
        logger.warning(
            "report_pdf: the bound faces cannot draw %s. PDF exports will show empty boxes there. "
            "Install fonts-noto-core and fonts-dejavu-core, or set REPORT_PDF_FONT. The .docx "
            "export is unaffected.",
            ", ".join(f"{character} ({purpose})" for character, purpose in _CACHED.missing_glyphs),
        )
    logger.info("report_pdf: fonts bound regular=%s scripts=%s missing=%s",
                _CACHED.regular,
                ",".join(f"{s.value}:{n[0]}" for s, n in sorted(
                    by_script.items(), key=lambda kv: kv[0].value)) or "-",
                "".join(c for c, _ in _CACHED.missing_glyphs) or "-")
    return _CACHED


# --------------------------------------------------------------------------------------
# Layout primitives
# --------------------------------------------------------------------------------------

ImageLoader = Callable[[ImageRef], bytes | None]


def _rgb(hex_color: str) -> tuple[float, float, float]:
    h = (hex_color or "000000").lstrip("#")
    if len(h) != 6:
        h = "000000"
    return int(h[0:2], 16) / 255.0, int(h[2:4], 16) / 255.0, int(h[4:6], 16) / 255.0


# How a raised or lowered run is drawn, as fractions of the paragraph's own point size.
#
# These three numbers are duplicated verbatim in ``PdfWriter.kt`` and pinned by
# ``tests/test_report_parity.py``. They have to be: the phone and the server produce the same
# report for the same workshop, and a superscript raised by a different fraction on one of them is
# a visibly different line — in a document somebody compares against the copy the office
# downloaded. 0.62 / 0.33 / 0.14 is roughly what Word itself does, which is what makes the .docx
# and the .pdf of one report look like the same document rather than two.
VERTICAL_SCALE = 0.62
SUPERSCRIPT_RISE = 0.33
SUBSCRIPT_DROP = 0.14


class _Line:
    """One laid-out line: the runs on it, its width and its height."""

    __slots__ = ("height", "pieces", "width")

    def __init__(self,
                 pieces: list[tuple[str, str, float, str | None, bool, bool, float, bool]],
                 width: float, height: float) -> None:
        # piece = (text, font, size, colour hex, underline, strikethrough, baseline rise, highlight)
        #
        # PDF HAS NO UNDERLINE ATTRIBUTE. Unlike a .docx run, where <w:u/> is a property Word
        # honours, a PDF text object can only draw glyphs — a rule under a word is a separate
        # line the renderer draws itself, which is why the flags have to survive word wrap all
        # the way to the draw call rather than being resolved into the font like bold is.
        #
        # NOR HAS IT A SUPERSCRIPT ATTRIBUTE, for the same reason, and that one is settled here
        # instead: a superscript is a smaller size drawn off the baseline, so the SIZE is resolved
        # into the piece at wrap time (which it must be — the wrap measures with it) and the
        # offset rides along as `rise`. In PDF space y grows UPWARD, so a positive rise is up.
        #
        # NOR A HIGHLIGHT: that is a filled rectangle drawn BEFORE the glyphs, at the extent of the
        # piece — which is the only unit with the right extent, exactly as for the underline rule.
        self.pieces = pieces
        self.width = width
        self.height = height


class PdfRenderer:
    """Lays a :class:`ReportDocument` onto PDF pages with an explicit cursor.

    The two-pass structure exists for one reason: a table of contents needs the page number of
    every heading, and a heading's page number is not known until the body has been laid out.
    Pass one runs the whole layout with drawing suppressed and records ``bookmark -> page``;
    pass two runs it again for real, and the TOC block now has numbers to print. Laying out
    twice is cheap (no glyphs are rasterised in pass one) and is the same trick the Android
    renderer uses, which is why their page numbers agree.
    """

    def __init__(self, document: ReportDocument, load_image: ImageLoader) -> None:
        self.doc = document
        self.theme = document.theme
        self.load_image = load_image
        self.fonts = register_fonts()
        self.dropped_images: list[str] = []
        self._image_cache: dict[str, ImageReader | None] = {}
        self._locked_depth = 0
        # Rasterised map and chart bytes, so the picture path can load them like any other image.
        # See the note beside ``DocxWriter._figures``; the same reasoning applies here, plus one
        # that does not apply there: THIS RENDERER LAYS THE DOCUMENT OUT TWICE. Pass one measures
        # everything with drawing suppressed so the TOC learns each heading's page; pass two draws
        # it. Rasterising India takes about two seconds, so a map re-rendered per pass would put
        # four seconds on the clock of every PDF export for a picture that cannot have changed
        # between the two passes. ``_figure_ref`` therefore memoises by block identity.
        self._figures: dict[str, bytes] = {}
        self._figure_refs: dict[int, ImageRef | None] = {}

        page_w_mm, page_h_mm = document.meta.page_size.size_mm
        margin = document.meta.margin_mm
        self.page_w = page_w_mm * MM
        self.page_h = page_h_mm * MM
        self.margin = margin * MM
        self.text_w = self.page_w - 2 * self.margin
        self.top = self.page_h - self.margin
        self.bottom = self.margin + 10 * MM   # room for the running foot

        self.base_size = self.theme.base_size_pt
        self._heading_pages: dict[str, int] = {}
        self._toc_entries: list[tuple[int, str, str, str]] = []  # level, number, text, bookmark
        # WHAT THE CONTENTS PAGE LAYS OUT, which is deliberately NOT `_toc_entries`.
        #
        # The contents block is rendered BEFORE the headings that fill `_toc_entries`, so within
        # a single pass it can only ever see an empty list. Reading the PREVIOUS pass's entries
        # is the whole mechanism by which the contents page reaches its true height and every
        # page number after it settles — see `build`.
        self._toc_source: list[tuple[int, str, str, str]] = []

    # -- text measurement ------------------------------------------------------------

    def _string_width(self, text: str, font: str, size: float) -> float:
        try:
            return pdfmetrics.stringWidth(text, font, size)
        except Exception:  # noqa: BLE001 - an unmapped glyph must not abort the export
            return len(text) * size * 0.5

    def _wrap(self, runs: tuple[Run, ...], width: float, size: float,
              *, leading_factor: float = 1.32) -> list[_Line]:
        """Greedy word wrap across a run sequence, preserving each run's font and colour.

        Wrapping across runs rather than per run is what keeps ``Ikat (ସମ୍ବଲପୁରୀ) weave`` from
        breaking after every script change. A single word longer than the column — a URL, a
        German-length compound — is hard-split rather than allowed to overflow the margin.
        """
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
            # A raised or lowered run is drawn SMALLER and OFF the baseline; the leading is left
            # alone, because a footnote marker must not open up the line it sits on. Both figures
            # are fractions of the paragraph's own size, and both are the same numbers
            # ``PdfWriter.kt`` uses — a phone that raised "m²" by a different amount from the
            # server would print a visibly different line for the same document.
            run_size = size * VERTICAL_SCALE if (run.superscript or run.subscript) else size
            rise = (size * SUPERSCRIPT_RISE if run.superscript
                    else -size * SUBSCRIPT_DROP if run.subscript else 0.0)
            for para_index, segment in enumerate(run.text.split("\n")):
                if para_index:
                    flush()
                if not segment:
                    continue
                # Keep the separating space attached to the preceding token so the width maths
                # matches what is actually drawn.
                tokens = segment.replace("\t", "    ").split(" ")
                for token_index, token in enumerate(tokens):
                    piece = token if token_index == len(tokens) - 1 else token + " "
                    if not piece:
                        continue
                    w = self._string_width(piece, font, run_size)
                    if cur and cur_w + w > width:
                        flush()
                    if w > width:
                        # Hard-split an unbreakable token, one character at a time.
                        buf = ""
                        buf_w = 0.0
                        for ch in piece:
                            cw = self._string_width(ch, font, run_size)
                            if buf and buf_w + cw > width:
                                cur.append((buf, font, run_size, run.color, run.underline,
                                            run.strike, rise, run.highlight))
                                cur_w += buf_w
                                flush()
                                buf, buf_w = "", 0.0
                            buf += ch
                            buf_w += cw
                        if buf:
                            cur.append((buf, font, run_size, run.color, run.underline,
                                        run.strike, rise, run.highlight))
                            cur_w += buf_w
                        continue
                    cur.append((piece, font, run_size, run.color, run.underline, run.strike,
                                rise, run.highlight))
                    cur_w += w
        flush()
        return lines or [_Line([], 0.0, leading)]

    # -- page machinery --------------------------------------------------------------

    def _new_page(self) -> None:
        if self._drawing and self._page_started:
            self._draw_furniture()
            self.c.showPage()
        self._page += 1
        self._page_started = True
        self.y = self.top
        if self._page > 1:
            # Clearance under the running head. Applied in BOTH passes, deliberately: an earlier
            # version guarded this with `self._drawing`, so the measuring pass believed every
            # page after the first was 6 mm taller than the drawing pass would make it. A block
            # that fitted while measuring could then not fit while drawing, the drawing pass
            # broke a page the measuring pass had not, and every table-of-contents page number
            # after that point was one too low — a wrong number in a printed report, with
            # nothing on screen to suggest it. The two passes must see identical geometry.
            self.y = self.top - 6 * MM

    def _ensure(self, height: float) -> None:
        """Break to a new page when ``height`` will not fit in what is left of this one.

        Suppressed inside a fixed-height box (:meth:`_locked`). Without that, drawing the text
        of a table cell would break the page *between* two lines of the cell, leaving the row's
        background rectangle and its vertical rules on the previous page and the rest of the
        words on the next one.
        """
        if self._locked_depth:
            return
        if self.y - height < self.bottom:
            self._new_page()

    @contextmanager
    def _locked(self):
        """Draw without page breaks — the caller has already reserved the space."""
        self._locked_depth += 1
        try:
            yield
        finally:
            self._locked_depth -= 1

    def _fits(self, height: float) -> bool:
        return self.y - height >= self.bottom

    def _draw_furniture(self) -> None:
        """Running head and foot. Suppressed on page 1, which is the cover."""
        if self._page <= 1:
            return
        meta = self.doc.meta
        t = self.theme
        self.c.setFont(self.fonts.regular, 7.8)
        self.c.setFillColorRGB(*_rgb(t.muted))
        if meta.header_text:
            self.c.drawRightString(self.page_w - self.margin, self.page_h - self.margin + 4 * MM,
                                   meta.header_text)
            self.c.setStrokeColorRGB(*_rgb(t.rule))
            self.c.setLineWidth(0.5)
            self.c.line(self.margin, self.page_h - self.margin + 2.6 * MM,
                        self.page_w - self.margin, self.page_h - self.margin + 2.6 * MM)
        foot_y = self.margin - 4 * MM
        self.c.setStrokeColorRGB(*_rgb(t.rule))
        self.c.setLineWidth(0.5)
        self.c.line(self.margin, foot_y + 4 * MM, self.page_w - self.margin, foot_y + 4 * MM)
        if meta.footer_text:
            self.c.drawString(self.margin, foot_y, meta.footer_text)
        if meta.show_page_numbers:
            self.c.drawRightString(self.page_w - self.margin, foot_y, f"Page {self._page}")

    # -- drawing helpers --------------------------------------------------------------

    def _draw_lines(self, lines: list[_Line], x: float, width: float, align: Align,
                    default_color: str) -> None:
        for line in lines:
            self._ensure(line.height)
            if self._drawing:
                if align is Align.CENTER:
                    cursor = x + (width - line.width) / 2
                elif align is Align.RIGHT:
                    cursor = x + width - line.width
                else:
                    cursor = x
                line_baseline = self.y - line.height * 0.78
                for text, font, size, color, underline, strike, rise, highlight in line.pieces:
                    # `rise` is 0 for ordinary text, so this is the same baseline it always was.
                    # The RULES below use it too: an underline under a subscript belongs under the
                    # subscript, not under the line it was lowered from.
                    baseline = line_baseline + rise
                    advance = self._string_width(text, font, size)
                    if highlight:
                        # BEFORE the glyphs, or the fill paints over the words. The box is the
                        # piece's own extent — the same unit the underline uses, and the only one
                        # that stops a highlighted phrase tinting the whole line it sits on. The
                        # trailing space each token carries is deliberately included: Word's own
                        # highlight runs through the spaces inside a highlighted phrase, and a
                        # gapped fill reads as several highlights rather than one.
                        self.c.setFillColorRGB(*_rgb(HIGHLIGHT_FILL))
                        self.c.rect(cursor, baseline - size * 0.24, advance, size * 1.14,
                                    stroke=0, fill=1)
                    self.c.setFont(font, size)
                    self.c.setFillColorRGB(*_rgb(color or default_color))
                    self.c.drawString(cursor, baseline, text)
                    if (underline or strike) and text.strip():
                        # Ruled per PIECE, not per line: a line where only one phrase is
                        # underlined must not get a rule under the whole of it. The offsets are
                        # fractions of the point size so they track the type rather than being
                        # a fixed gap that looks wrong at every size but one.
                        self.c.setStrokeColorRGB(*_rgb(color or default_color))
                        self.c.setLineWidth(max(0.4, size * 0.05))
                        if underline:
                            self.c.line(cursor, baseline - size * 0.13,
                                        cursor + advance, baseline - size * 0.13)
                        if strike:
                            self.c.line(cursor, baseline + size * 0.26,
                                        cursor + advance, baseline + size * 0.26)
                    cursor += advance
            self.y -= line.height

    def _image_reader(self, ref: ImageRef) -> ImageReader | None:
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

    def _draw_image(self, ref: ImageRef, x: float, width: float, max_height: float,
                    align: Align) -> float:
        """Draw ``ref`` fitted into the box and return the height it consumed."""
        reader = self._image_reader(ref)
        if reader is None:
            return 0.0
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
                # Rotate about the centre of the destination box so the photo stays inside it.
                cx, cy = dx + w / 2, self.y - h / 2
                self.c.translate(cx, cy)
                self.c.rotate(-ref.rotation_deg)
                dw, dh = (h, w) if ref.rotation_deg in (90, 270) else (w, h)
                self.c.drawImage(reader, -dw / 2, -dh / 2, dw, dh,
                                 preserveAspectRatio=True, anchor="c", mask="auto")
            else:
                self.c.drawImage(reader, dx, self.y - h, w, h,
                                 preserveAspectRatio=True, anchor="c", mask="auto")
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

    # -- blocks ------------------------------------------------------------------------

    def _block_cover(self, block: CoverBlock) -> None:
        """Draw the cover as exactly one page.

        Everything here is measured before anything is drawn, and the hero photograph absorbs
        whatever vertical space is left over. Laying the cover out top-down with page breaks
        enabled — which is what the first version did — pushed the closing "Submitted to …"
        line onto a second, otherwise blank page whenever the title wrapped to two lines or the
        info table gained a row. A cover that silently becomes two pages also shifts every
        page number in the table of contents.
        """
        t = self.theme
        x = self.margin
        logo_h = 26 * MM if block.logo else 0.0
        org_lines = [self._wrap(runs_of(line), self.text_w, 10.5) for line in block.org_lines]
        title_lines = self._wrap(runs_of(block.title, bold=True), self.text_w, 26)
        subtitle_lines = (
            self._wrap(runs_of(block.subtitle, italic=True), self.text_w, 13)
            if block.subtitle else []
        )
        footer_lines = [self._wrap(runs_of(line), self.text_w, 8.6)
                        for line in block.footer_lines]

        def total(groups: list[list[_Line]]) -> float:
            return sum(line.height for group in groups for line in group)

        info_h = 0.0
        if block.info_rows:
            label_w = self.text_w * 0.32
            for label, value in block.info_rows:
                lab = self._wrap(runs_of(label, bold=True), label_w - 3 * MM,
                                 self.base_size - 0.6)
                val = self._wrap(runs_of(value), self.text_w - label_w - 3 * MM, self.base_size)
                info_h += max(sum(ln.height for ln in lab),
                              sum(ln.height for ln in val)) + 1.8 * MM
            info_h += 2.4 * MM

        gaps = (18 + 8 + 14 + 3 + 8 + 6) * MM
        fixed = (logo_h + total(org_lines) + sum(line.height for line in title_lines)
                 + sum(line.height for line in subtitle_lines) + info_h
                 + total(footer_lines) + gaps)
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
                # Below about 18 mm the photograph is a smudge; dropping it is better than
                # printing one, and the cover still carries the title and the info table.
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
        self._draw_lines(self._wrap(runs_of(block.title, bold=True), self.text_w, 16),
                         x, self.text_w, Align.LEFT, t.accent)
        self.y -= 4 * MM
        for level, number, text, bookmark in self._toc_source:
            if level > block.depth:
                continue
            indent = (level - 1) * 6 * MM
            size = 10.0 if level == 1 else 9.2
            label = f"{number}. {text}" if number else text
            page = self._heading_pages.get(bookmark)
            page_label = str(page) if page else ""
            self._ensure(size * 1.6)
            if self._drawing:
                font = self.fonts.bold if level == 1 else self.fonts.regular
                self.c.setFont(font, size)
                self.c.setFillColorRGB(*_rgb(t.ink if level == 1 else t.muted))
                baseline = self.y - size
                self.c.drawString(x + indent, baseline, label)
                if page_label:
                    self.c.drawRightString(x + self.text_w, baseline, page_label)
                    # Dot leader between the title and the page number.
                    label_w = self._string_width(label, font, size)
                    page_w = self._string_width(page_label, font, size)
                    lead_from = x + indent + label_w + 2 * MM
                    lead_to = x + self.text_w - page_w - 2 * MM
                    if lead_to > lead_from:
                        self.c.setFillColorRGB(*_rgb(t.rule))
                        dot_w = self._string_width(".", font, size)
                        count = max(0, int((lead_to - lead_from) / max(dot_w, 0.1)))
                        self.c.drawString(lead_from, baseline, "." * count)
            self.y -= size * 1.6
        self._new_page()

    def _block_heading(self, block: HeadingBlock) -> None:
        t = self.theme
        size, color = (
            (17.0, t.accent), (13.5, t.accent), (11.5, t.accent_soft), (10.5, t.muted)
        )[block.level - 1]
        label = f"{block.number}. " if block.number else ""
        runs = runs_of(label + runs_text(block.runs), bold=True)
        lines = self._wrap(runs, self.text_w, size)
        needed = sum(line.height for line in lines) + 6 * MM
        # keepNext: a heading alone at the foot of a page is worse than a slightly short page.
        self._ensure(needed)
        self.y -= (4.5 if block.level > 1 else 6.5) * MM
        if not self._drawing:
            self._heading_pages[block.bookmark] = self._page
            self._toc_entries.append(
                (block.level, block.number, runs_text(block.runs), block.bookmark)
            )
        elif block.bookmark:
            self.c.bookmarkPage(block.bookmark)
            self.c.addOutlineEntry(f"{block.number} {runs_text(block.runs)}".strip(),
                                   block.bookmark, level=block.level - 1)
        self._draw_lines(lines, self.margin, self.text_w, Align.LEFT, color)
        if block.level == 1:
            # ── THE SAME DEFECT AS THE RUNNING-HEAD CLEARANCE IN `_new_page`, SECOND INSTANCE ────
            #
            # The rule under a level-1 heading COSTS 1.2 mm of vertical space, and the whole block —
            # the space and the stroke together — used to sit behind `and self._drawing`. So the
            # measuring pass believed a level-1 heading was 1.2 mm shorter than the drawing pass
            # would make it, and this document has a hundred and fifty of them: the drawn report ran
            # long enough to break pages the measuring pass had not, and every contents entry after
            # the drift crossed a page boundary pointed at the wrong page.
            #
            # `_new_page` already carries this lesson in as many words — "an earlier version guarded
            # this with `self._drawing` … The two passes must see identical geometry" — and this is
            # the second place the same guard was wrapped around a measurement. The rule for the file
            # is: `self._drawing` may guard a DRAW CALL and must never guard a change to `self.y`.
            #
            # IT HID BEHIND THE FONT, WHICH IS WHY IT SURVIVED. 1.2 mm × 150 headings is under a page
            # of raw drift, and whether that drift actually moves a section across a page boundary
            # depends on where the text happens to wrap — which depends on the face this module
            # resolved from the host (see the banner: Noto, DejaVu, Liberation, then Helvetica). The
            # contents test passed on a developer's Windows box under Nirmala and failed on the CI
            # runner under DejaVu, on the identical commit, off by exactly two pages. Reproduced
            # locally by pointing `REPORT_PDF_FONT` at reportlab's bundled Vera.
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
            # ``replace``, NOT ``Run(r.text, r.bold, True, r.script, r.color)`` — the same
            # correction ``report_docx._emit_paragraph`` already carries, which this file was
            # never brought along for. ``Run``'s fields are (text, bold, italic, underline,
            # strike, script, color, …), so the fifth and sixth positional arguments landed on
            # the wrong ones: the SCRIPT went into ``underline`` — a str-Enum, and therefore
            # true for every script there is — and the COLOUR into ``strike``. Every QUOTE and
            # CAPTION in every server-generated PDF was underlined, and struck
            # through if it carried a colour, and — because ``script`` was discarded and fell
            # back to LATIN — an Odia pull-quote was sent to the Latin face and printed as boxes
            # beside the identical word in the body text, which printed correctly. Nothing
            # raised; the file opened perfectly. ``replace`` names the one field it changes and
            # cannot be reached by the next field somebody adds to ``Run``.
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

    def _simple_grid(self, pairs: list[tuple[str, tuple[Run, ...]]], *,
                     label_pct: float = 30.0) -> None:
        """The borderless label/value grid shared by the cover and KeyValueBlock."""
        t = self.theme
        label_w = self.text_w * label_pct / 100
        value_w = self.text_w - label_w
        for i, (label, value) in enumerate(pairs):
            lab_lines = self._wrap(runs_of(label, bold=True), label_w - 3 * MM,
                                   self.base_size - 0.6)
            val_lines = self._wrap(value, value_w - 3 * MM, self.base_size)
            height = max(sum(line.height for line in lab_lines),
                         sum(line.height for line in val_lines)) + 1.8 * MM
            self._ensure(height)
            row_top = self.y
            if self._drawing and i % 2 == 1:
                self.c.setFillColorRGB(*_rgb(t.zebra_fill))
                self.c.rect(self.margin, row_top - height, self.text_w, height,
                            stroke=0, fill=1)
            with self._locked():
                self.y = row_top - 0.9 * MM
                self._draw_lines(lab_lines, self.margin + 1.5 * MM, label_w - 3 * MM,
                                 Align.LEFT, t.muted)
                self.y = row_top - 0.9 * MM
                self._draw_lines(val_lines, self.margin + label_w, value_w - 3 * MM,
                                 Align.LEFT, t.ink)
            self.y = row_top - height
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

        def row_lines(cells: tuple[tuple[Run, ...], ...], size: float,
                      bold: bool) -> tuple[list[list[_Line]], float]:
            laid: list[list[_Line]] = []
            for j, w in enumerate(widths):
                value = cells[j] if j < len(cells) else ()
                if bold:
                    # ``replace`` for the same reason the QUOTE branch above uses it: building a
                    # ``Run`` positionally put the script into ``underline`` and the colour into
                    # ``strike``, so EVERY table header in EVERY server-generated PDF came out
                    # underlined — where the .docx writes the same header run with ``<w:b/>`` and
                    # no ``<w:u>`` — and an Odia column heading lost its script, went to the
                    # Latin face and printed as boxes above a column of Odia cells that printed
                    # correctly. ``PdfWriter.kt`` uses ``it.copy(bold = true)`` and was never
                    # wrong, so this was a DIVERGENCE: the same table looked different in the
                    # office's download and on the phone.
                    value = tuple(replace(r, bold=True) for r in value)
                laid.append(self._wrap(value, w - 2 * pad, size))
            height = max((sum(line.height for line in col) for col in laid), default=size)
            return laid, height + 1.8 * MM

        def draw_row(laid: list[list[_Line]], height: float, fill: str | None,
                     text_color: str, top_rule: bool = False) -> None:
            """Draw one row at the cursor. The caller guarantees it fits; this never breaks."""
            top = self.y
            if self._drawing:
                if fill:
                    self.c.setFillColorRGB(*_rgb(fill))
                    self.c.rect(self.margin, top - height, self.text_w, height,
                                stroke=0, fill=1)
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
                    self._draw_lines(laid[j], cursor_x + pad, widths[j] - 2 * pad, align,
                                     text_color)
                    cursor_x += widths[j]
            self.y = top - height

        has_header = any(c.header for c in block.columns)
        header_laid, header_h = row_lines(
            tuple(runs_of(c.header) for c in block.columns), header_size, True
        )

        def place_header() -> None:
            if has_header:
                draw_row(header_laid, header_h, t.table_header_fill, t.table_header_text)

        # Reserve the header plus the first body row together: a header stranded at the foot of
        # a page reads as an empty table.
        first_h = row_lines(block.rows[0], body_size, False)[1] if block.rows else 0.0
        if not self._fits(header_h + first_h):
            self._new_page()
        place_header()

        for i, row in enumerate(block.rows):
            laid, height = row_lines(row, body_size, False)
            # Decide the break BEFORE drawing, so a row is never split and never drawn twice.
            # The previous version drew the row, noticed the page had turned, and then redrew
            # both the header and the row — duplicating it.
            if not self._fits(height):
                self._new_page()
                place_header()
            draw_row(laid, height, t.zebra_fill if (block.zebra and i % 2 == 1) else None, t.ink)

        if block.total_row:
            laid, height = row_lines(block.total_row, body_size, True)
            # The total must never be orphaned from the figures it totals.
            if not self._fits(height):
                self._new_page()
                place_header()
            draw_row(laid, height, t.zebra_fill, t.ink, top_rule=True)

        self.y -= 2.2 * MM
        self._caption(block.caption, self.margin, self.text_w)

    def _block_image(self, block: ImageBlock) -> None:
        width = self.text_w * max(5.0, min(100.0, block.width_pct)) / 100
        drawn = self._draw_image(block.image, self.margin, width,
                                 (self.top - self.bottom) * 0.62, block.align)
        if drawn:
            self._caption(block.caption, self.margin, self.text_w)
            self.y -= 1.6 * MM

    def _block_image_grid(self, block: ImageGridBlock) -> None:
        if not block.images:
            return
        columns = max(1, min(4, block.columns))
        # Same legibility floor as the DOCX writer, so both drop to the same column count.
        while columns > 1 and (self.text_w / columns) < 45 * MM:
            columns -= 1
        cell_w = self.text_w / columns
        cap_size = 7.6

        for start in range(0, len(block.images), columns):
            chunk = list(block.images[start:start + columns])
            heights: list[float] = []
            for ref, caption in chunk:
                reader = self._image_reader(ref)
                if reader is None:
                    heights.append(0.0)
                    continue
                try:
                    iw, ih = reader.getSize()
                except Exception:  # noqa: BLE001
                    iw, ih = 0, 0
                if ref.rotation_deg in (90, 270):
                    iw, ih = ih, iw
                aspect = (iw / ih) if iw and ih else ref.aspect
                h = min((cell_w - 4 * MM) / aspect, (self.top - self.bottom) * 0.30)
                cap_h = sum(
                    line.height
                    for line in self._wrap(runs_of(caption), cell_w - 4 * MM, cap_size)
                ) if caption else 0.0
                heights.append(h + cap_h + 3 * MM)
            row_h = max(heights) if heights else 0.0
            if row_h <= 0:
                continue
            self._ensure(row_h)
            row_top = self.y
            # Locked: a grid row is one visual unit. Without this, a tall caption in the last
            # cell breaks the page and the remaining photos of the row land under the header
            # of the next one, detached from their captions.
            with self._locked():
                for i, (ref, caption) in enumerate(chunk):
                    x = self.margin + i * cell_w
                    self.y = row_top
                    drawn = self._draw_image(ref, x + 2 * MM, cell_w - 4 * MM,
                                             (self.top - self.bottom) * 0.30, Align.CENTER)
                    if drawn and caption:
                        self.y -= 1.2 * MM
                        self._draw_lines(
                            self._wrap(runs_of(caption, italic=True), cell_w - 4 * MM, cap_size),
                            x + 2 * MM, cell_w - 4 * MM, Align.CENTER, self.theme.muted,
                        )
            self.y = row_top - row_h
        self.y -= 1.6 * MM
        self._caption(block.caption, self.margin, self.text_w)

    # -- figures: the map and the infographics -------------------------------------------

    def _figure_width(self, width_pct: float) -> float:
        return self.text_w * max(20.0, min(100.0, width_pct)) / 100

    def _figure_ref(self, block: MapBlock | ChartBlock) -> ImageRef | None:
        """Rasterise ``block`` once per document and hand back an :class:`ImageRef` for it.

        Keyed by ``id(block)`` rather than by the block's own value. The blocks are frozen and
        hashable, so a value key would work, but identity is what is actually wanted here: the
        cache exists to stop the two layout passes rendering the same object twice, and two
        genuinely equal charts in different sections are two figures a reader sees separately.
        Identity also costs nothing to compute on a block carrying three hundred map points.

        ``None`` means the map's geometry is not on this machine — never a chart, which needs no
        asset. The caller drops the figure and records it, exactly as it drops an undecodable
        photograph, because a missing locator map must not fail an export.
        """
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
        if title:
            # A figure title is a label, not a heading: it must not enter the table of contents,
            # and it must not be separated from its picture by a page break. Drawn as bold accent
            # text immediately above, inside the same locked region as the image below.
            self._ensure(6 * MM)
            lines = self._wrap(runs_of(title, bold=True), self.text_w, self.base_size)
            self._draw_lines(lines, self.margin, self.text_w, Align.LEFT, self.theme.accent)
            self.y -= 1.2 * MM
        drawn = self._draw_image(ref, self.margin, self._figure_width(block.width_pct),
                                 (self.top - self.bottom) * 0.58, block.align)
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
            block.kind.upper(), "EAF1FB")
        edge = {"INFO": t.accent_soft, "WARNING": "B7791F", "SUCCESS": "2F855A"}.get(
            block.kind.upper(), t.accent_soft)
        inner_w = self.text_w - 8 * MM
        title_lines = self._wrap(runs_of(block.title, bold=True), inner_w, self.base_size) \
            if block.title else []
        body_lines = self._wrap(block.runs, inner_w, self.base_size - 0.6)
        height = (sum(line.height for line in title_lines)
                  + sum(line.height for line in body_lines) + 6 * MM)
        self._ensure(height)
        top = self.y
        if self._drawing:
            self.c.setFillColorRGB(*_rgb(fill))
            self.c.rect(self.margin, top - height, self.text_w, height, stroke=0, fill=1)
            self.c.setFillColorRGB(*_rgb(edge))
            self.c.rect(self.margin, top - height, 1.4 * MM, height, stroke=0, fill=1)
        with self._locked():
            self.y = top - 3 * MM
            if title_lines:
                self._draw_lines(title_lines, self.margin + 5 * MM, inner_w, Align.LEFT, edge)
            self._draw_lines(body_lines, self.margin + 5 * MM, inner_w, Align.LEFT, t.ink)
        self.y = top - height - 3 * MM

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
                self._draw_lines(self._wrap(runs_of(name, bold=True), cell_w - 6 * MM, 9.5),
                                 x + 3 * MM, cell_w - 6 * MM, Align.CENTER, t.ink)
                self._draw_lines(self._wrap(runs_of(designation), cell_w - 6 * MM, 8),
                                 x + 3 * MM, cell_w - 6 * MM, Align.CENTER, t.muted)
        self.y = top - 14 * MM

    # -- the pass ---------------------------------------------------------------------

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
        # Measure until the heading -> page map stops changing, then draw once.
        #
        # One measuring pass is not enough, and the reason is circular: the table of contents is
        # laid out BEFORE the headings it lists, so on the first pass it has no entries and
        # occupies a single title line. If the real contents then run to two pages, everything
        # after them shifts down by a page and every number the TOC prints is one too low.
        # Feeding the first pass's entries back in and measuring again resolves it.
        #
        # ── THE CAP WAS THREE, AND "ENOUGH FOR EVERY REAL DOCUMENT" WAS NOT TRUE ────────────────
        #
        # A report balanced exactly on a page boundary — where adding the TOC row that a page break
        # created removes the break again — oscillates forever, so a cap is genuinely needed. But
        # three of them buys convergence only for a SHORT contents. Each iteration moves the body by
        # however many pages the contents grew by, and a contents that grows by a page can push a
        # heading onto a new page, which adds a row, which can grow the contents again. A long
        # report therefore walks toward its fixed point a page or two at a time and needs as many
        # iterations as it has of that walk left.
        #
        # `test_every_page_number_the_contents_prints_is_the_page_the_section_is_on` builds a
        # 150-section document for exactly this reason and it failed the moment CI first ran it: the
        # contents sent a reader to page 36 for a section on page 38. Off by TWO, not by the nine and
        # ten the carry-over below fixed — a smaller error, from the same place, left behind by the
        # cap rather than by the missing feedback.
        #
        # SO THE CAP IS RAISED AND TERMINATION IS MADE EXPLICIT INSTEAD OF ACCIDENTAL. Eight
        # iterations, and a record of every layout already seen: an oscillation returns to a state it
        # has visited, and detecting that stops the loop deliberately rather than waiting for a
        # number that was chosen to be small enough to bound the pathological case. A cap is a bound
        # on time; it was doing duty as a convergence argument, and those are different things.
        #
        # The pathological document still terminates — it now does so because it was RECOGNISED, and
        # the number it prints is the best of the states it cycled through rather than whichever one
        # iteration three happened to land on.
        #
        # "FEEDING THE FIRST PASS'S ENTRIES BACK IN" IS THE LINE THAT WAS MISSING. `_toc_entries`
        # was cleared at the top of every iteration and `_block_toc` read it directly, so the
        # contents block — which is laid out before any heading has run — saw an empty list on
        # every measuring pass and measured itself as a single title line, three times over. The
        # draw pass then inherited the last measurement's entries and printed the real ten-page
        # contents, so every page number in it was measured against a one-page one. In the
        # 167-page report for the flagship workshop all 346 resolvable entries were wrong: 303
        # off by 9 and 43 off by 10 — "Certification .... 158" for a section on page 167 — while
        # the PDF's own bookmark outline, built during the draw pass, was correct. An officer
        # using the contents page of a ministry submission landed ten pages short every time.
        # The carry-over below is what makes the three iterations do what this comment claims.
        self.c = rl_canvas.Canvas(BytesIO(), pagesize=(self.page_w, self.page_h))
        previous: dict[str, int] = {}
        # Every layout already measured, as an order-independent signature. A repeat is an
        # oscillation — the document has returned to a page assignment it held before — and there is
        # nothing further to learn from continuing. Keyed on the heading->page map because that IS
        # the state the loop is trying to settle: `_toc_entries` is derived from it.
        seen: set[frozenset[tuple[str, int]]] = set()
        for _ in range(8):
            self._toc_entries = []
            # Cleared too, not just replaced key by key: a heading removed by a template change
            # would otherwise keep its stale page number and the comparison below would never
            # settle.
            self._heading_pages = {}
            self._run_pass(drawing=False)
            # The contents the NEXT pass lays out. Assigned after the pass, never during it: a
            # pass must measure one stable contents block from start to finish, or the headings
            # before and after it would be measured against different heights.
            self._toc_source = list(self._toc_entries)
            if self._heading_pages == previous:
                # Converged: this pass laid the document out exactly as the last one did, so the
                # contents it measured are the contents that will be drawn and every number in them
                # is the page the heading is actually on.
                break
            signature = frozenset(self._heading_pages.items())
            if signature in seen:
                # OSCILLATING, and stopping here is a decision rather than running out of turns.
                # The document has returned to a layout it already held, so further passes only
                # revisit the same two or three states. Whichever we stop on prints a number that is
                # right for one of them — the alternative is not a better number, it is a hung
                # export.
                break
            seen.add(signature)
            previous = dict(self._heading_pages)

        # ── RECONCILE, SO THE NUMBERS BELONG TO THE CONTENTS THAT IS ACTUALLY DRAWN ──────────────
        #
        # THE BUG THIS CLOSES IS NOT THE CAP, AND RAISING THE CAP DID NOT CLOSE IT. Each iteration
        # measures a layout using the PREVIOUS iteration's contents, then adopts its own contents for
        # the next one — so on any exit that is not a clean convergence, `_heading_pages` was measured
        # against `_toc_source`'s predecessor, and the draw pass then prints those numbers beside a
        # DIFFERENT contents block. The numbers and the pagination come from two different layouts.
        #
        # It stayed hidden because it only shows when the loop fails to converge, and whether a given
        # document converges depends on the FONT: this module resolves faces from the host (see the
        # banner — Noto, DejaVu, Liberation, falling back to Helvetica), so the same report wraps
        # differently on a developer's machine and on a CI runner, paginates differently, and settles
        # differently. `test_every_page_number_the_contents_prints_is_the_page_the_section_is_on`
        # passed locally and failed in CI on the identical commit for exactly that reason — off by
        # two, from a document that oscillates under one font and settles under another.
        #
        # One more measuring pass, with `_toc_source` LEFT EXACTLY AS THE LOOP LEFT IT, makes the two
        # agree by construction: the page map now describes the layout that this contents block
        # produces, which is the layout about to be drawn. A bistable document still has two possible
        # paginations — nothing can change that — but the numbers it prints are the pages of the one
        # the reader is holding, which is the only property that was ever worth asserting.
        #
        # `_toc_source` is deliberately NOT reassigned from `_toc_entries` afterwards. Adopting this
        # pass's entries would put the contents one step ahead of the map again and reintroduce the
        # defect one iteration later, which is the shape of the original.
        self._toc_entries = []
        self._heading_pages = {}
        self._run_pass(drawing=False)

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
        return buffer.getvalue()


def render_pdf(document: ReportDocument, load_image: ImageLoader) -> tuple[bytes, list[str]]:
    """Render ``document`` to PDF bytes plus the list of images that could not be drawn.

    Synchronous and CPU-bound; call it through ``asyncio.to_thread``.
    """
    renderer = PdfRenderer(document, load_image)
    data = renderer.build()
    return data, sorted(set(renderer.dropped_images))
