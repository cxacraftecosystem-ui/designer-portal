"""The renderer-agnostic report document model shared by every exporter.

A generated workshop report is built in two steps that are deliberately kept apart:

    workshop data + template  ->  ReportDocument  ->  .docx / .pdf / HTML preview
                (report_builder)              (report_docx / report_pdf / the web preview)

``ReportDocument`` is the waist of that hourglass. It is a plain tree of frozen dataclasses
describing *what the report says* — a cover, headings, prose, tables, photo grids, cost
sheets — and says nothing about *how any of it is drawn*. Every renderer consumes it:

- ``report_docx.py``      writes OOXML into a zip, with no third-party dependency,
- ``report_pdf.py``       lays it out through ReportLab,
- ``DocxWriter.kt`` and ``PdfWriter.kt`` (Android, package ``…workshop.report``) write the same
                          OOXML and draw the same layout onto ``android.graphics.pdf.PdfDocument``
                          pages, entirely offline. ``ReportModel.kt`` is the port of this file.

Because the Kotlin renderers are a port rather than a shared library, the model is the only
thing keeping the three outputs from drifting. Two rules protect that, and both are the
reason this module exists at all rather than the blocks being dicts:

**Sizes are relative, never absolute.** No block carries points, twips, EMUs or pixels. Column
widths are percentages of the text column; image widths are a fraction of it. A renderer
multiplies by whatever its own page geometry is. The first draft of this model carried
centimetres, and the DOCX and PDF of the same workshop disagreed about every table width
because Word's usable column is the page minus its margins while ReportLab's frame had already
subtracted them — the same number meant two different widths. Percentages cannot express that
bug.

**Every string reaches a renderer through :func:`clean_text` and nothing else.** ``word/
document.xml`` is XML 1.0, which admits only #x9, #xA, #xD and the printable ranges — the exact
constraint that corrupted the .xlsx export three separate ways before ``xlsx_report._put``
became its single door (see that module's docstring). A lone surrogate from a phone that cut an
emoji in half serialises to a numeric reference no XML parser will accept, and Word responds by
refusing to open the file at all rather than by dropping the character. The constructors below
call ``clean_text`` for you, which is why they take strings and not runs; build a block by hand
and bypass it and the failure reappears, in a file the user only opens after leaving the field.

``backend/tests/test_report_model.py`` fails if either rule is broken.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field
from enum import Enum
from typing import Any

# --------------------------------------------------------------------------------------
# The single door
# --------------------------------------------------------------------------------------

# XML 1.0 §2.2. Everything outside these ranges — the C0 controls other than tab/LF/CR, the
# surrogate block D800-DFFF, and the two noncharacters FFFE/FFFF — makes the part not
# well-formed. Python strings can hold all of them; a document part cannot carry any.
_XML_ILLEGAL = re.compile("[^\u0009\u000a\u000d\u0020-\ud7ff\ue000-\ufffd\U00010000-\U0010ffff]")

# Word renders a lone CR and a CRLF differently inside a run; normalising here means the two
# renderers never have to agree on which one they were handed. U+2028/U+2029 arrive in pasted
# web text and are separators no document format reflows correctly.
_LINE_ENDINGS = re.compile("\r\n?|\u2028|\u2029")


def clean_text(value: Any) -> str:
    """Coerce ``value`` to a string that can be written into a document part unescaped-checked.

    Non-strings are stringified (``None`` becomes an empty string, not the word "None" — a
    missing optional field must leave a blank cell, and every renderer would otherwise print
    "None" into the report). Line endings collapse to ``\\n``. Codepoints XML cannot carry are
    dropped rather than replaced, because a replacement glyph in a government report reads as a
    data-entry error while a dropped half-emoji reads as nothing at all.
    """
    if value is None:
        return ""
    if isinstance(value, bool):
        # Before the str() branch: bool is an int subclass and "True"/"False" is never what a
        # report wants. Yes/No is what the form showed the designer.
        return "Yes" if value else "No"
    text = value if isinstance(value, str) else str(value)
    text = _LINE_ENDINGS.sub("\n", text)
    return _XML_ILLEGAL.sub("", text)


# --------------------------------------------------------------------------------------
# Script detection — the reason craft names are not boxes
# --------------------------------------------------------------------------------------


class Script(str, Enum):
    """Which writing system a run of text needs a font for.

    ``localName`` fields hold Devanagari, Odia, Bengali and Gujarati verbatim: the server's
    title-caser returns non-Latin scripts untouched by rule, so whatever the artisan typed is
    what the report must print. Neither bundled UI font covers any of those blocks, so a run
    tagged anything but :attr:`LATIN` is drawn by the PDF renderer in the platform's Noto
    fallback instead. DOCX can only *name* a font and hope the reader has it, so the writer
    names a complex-script font on those runs via ``w:cs`` — which is the one hook Word offers.
    """

    LATIN = "LATIN"
    DEVANAGARI = "DEVANAGARI"
    BENGALI = "BENGALI"
    ODIA = "ODIA"
    GUJARATI = "GUJARATI"
    TAMIL = "TAMIL"
    TELUGU = "TELUGU"
    KANNADA = "KANNADA"
    MALAYALAM = "MALAYALAM"
    GURMUKHI = "GURMUKHI"
    OTHER = "OTHER"


_SCRIPT_RANGES: tuple[tuple[int, int, Script], ...] = (
    (0x0900, 0x097F, Script.DEVANAGARI),
    (0x0980, 0x09FF, Script.BENGALI),
    (0x0A00, 0x0A7F, Script.GURMUKHI),
    (0x0A80, 0x0AFF, Script.GUJARATI),
    (0x0B00, 0x0B7F, Script.ODIA),
    (0x0B80, 0x0BFF, Script.TAMIL),
    (0x0C00, 0x0C7F, Script.TELUGU),
    (0x0C80, 0x0CFF, Script.KANNADA),
    (0x0D00, 0x0D7F, Script.MALAYALAM),
)


# Characters with no script of their own: combining marks, spaces, punctuation and format
# controls. They render identically in every font the report uses, so they must attach to
# whichever run they abut instead of splitting it. Checked only AFTER the block ranges below,
# because Devanagari digits (U+0966-096F, category Nd) and Indic vowel signs (Mn/Mc) live
# inside their script's block and belong to it — resolving those as neutral would hand them to
# a neighbouring Latin run and print them as tofu.
_NEUTRAL_CATEGORIES = frozenset(
    {
        "Mn",
        "Mc",
        "Me",  # combining marks
        "Zs",
        "Zl",
        "Zp",  # separators
        "Cc",
        "Cf",  # controls and format characters
        "Pd",
        "Ps",
        "Pe",
        "Pi",
        "Pf",
        "Po",
        "Pc",  # punctuation
        "Sm",
        "Sk",
        "Sc",  # symbols, incl. the rupee sign
        "Nd",
        "No",  # digits outside a scripted block
    }
)


def _script_or_neutral(ch: str) -> Script | None:
    """The script of ``ch``, or ``None`` when it belongs to whatever run surrounds it."""
    cp = ord(ch)
    for lo, hi, script in _SCRIPT_RANGES:
        if lo <= cp <= hi:
            return script
    if unicodedata.category(ch) in _NEUTRAL_CATEGORIES:
        return None
    if cp < 0x0370:
        return Script.LATIN
    return Script.OTHER


def script_of(ch: str) -> Script:
    """The script a single character belongs to, for font selection."""
    return _script_or_neutral(ch) or Script.LATIN


def split_by_script(text: str) -> list[tuple[str, Script]]:
    """Split ``text`` into the longest possible same-script spans.

    A report line is routinely mixed — ``Sambalpuri Ikat (ସମ୍ବଲପୁରୀ ଇକତ) weave`` — and a PDF
    renderer must switch typeface mid-line or the parenthesised half becomes tofu. Returning
    spans rather than per-character pairs keeps the run count (and so the DOCX size)
    proportional to the number of script changes, not to the number of characters.

    Neutral characters resolve to the script *before* them, falling back to the one after when
    the string opens with punctuation. An earlier version let any Latin character extend an open
    run, which merged the trailing ``) weave`` above into the Odia span and drew four English
    words in the Noto fallback.
    """
    if not text:
        return []

    resolved: list[Script | None] = [_script_or_neutral(c) for c in text]

    # Forward-fill: a neutral takes the script of the last decided character.
    carried: Script | None = None
    for i, s in enumerate(resolved):
        if s is not None:
            carried = s
        else:
            resolved[i] = carried

    # Back-fill whatever is still undecided, i.e. the neutrals before the first real character.
    trailing: Script = Script.LATIN
    for i in range(len(resolved) - 1, -1, -1):
        if resolved[i] is not None:
            trailing = resolved[i]  # type: ignore[assignment]
        else:
            resolved[i] = trailing

    spans: list[tuple[str, Script]] = []
    start = 0
    for i in range(1, len(text)):
        if resolved[i] is not resolved[i - 1]:
            spans.append((text[start:i], resolved[i - 1]))  # type: ignore[arg-type]
            start = i
    spans.append((text[start:], resolved[-1]))  # type: ignore[arg-type]
    return spans


def dominant_script(text: str) -> Script:
    """The script that most of ``text`` is written in — used to pick a whole cell's font."""
    counts: dict[Script, int] = {}
    for ch in text:
        s = script_of(ch)
        if s is Script.LATIN:
            continue
        counts[s] = counts.get(s, 0) + 1
    if not counts:
        return Script.LATIN
    return max(counts.items(), key=lambda kv: kv[1])[0]


# --------------------------------------------------------------------------------------
# Inline content
# --------------------------------------------------------------------------------------


class Align(str, Enum):
    LEFT = "LEFT"
    CENTER = "CENTER"
    RIGHT = "RIGHT"
    JUSTIFY = "JUSTIFY"


class ParaStyle(str, Enum):
    """Named paragraph roles. A renderer maps each to its own concrete typography."""

    BODY = "BODY"
    LEAD = "LEAD"  # opening paragraph of a section, slightly larger
    NOTE = "NOTE"  # smaller, muted — provenance and caveats
    QUOTE = "QUOTE"  # indented, italic — artisan and buyer verbatims
    CAPTION = "CAPTION"  # under an image
    COVER_LINE = "COVER_LINE"  # centred cover-page furniture


@dataclass(frozen=True, slots=True)
class Run:
    """A span of text with uniform formatting. Always built through :func:`runs_of`."""

    text: str
    bold: bool = False
    italic: bool = False
    # Added for RICH_TEXT. Positioned AFTER italic and before script so every existing positional
    # construction — Run(text, bold, italic) — keeps meaning what it meant; the report builder and
    # both renderers build runs positionally in a dozen places.
    underline: bool = False
    strike: bool = False
    script: Script = Script.LATIN
    color: str | None = None  # "RRGGBB", no leading hash — both writers want it bare
    # Added for RICH_TEXT, and APPENDED rather than slotted in beside underline/strike. The two
    # above had to go there because they were added to a model that was already being constructed
    # positionally as Run(text, bold, italic); these come after `color`, where nothing positional
    # can reach them, so every existing call site keeps meaning what it meant. ReportModel.kt
    # appends them in the same place for the same reason — the two must stay field-for-field
    # identical, or a document written by one and read by the other maps one flag onto another.
    #
    # A character cannot be both, and SUPERSCRIPT WINS where a document somehow says both. That
    # rule is enforced once, at the point marks become runs (`rich_text._runs_for` and its Kotlin
    # twin), so no renderer has to decide it and no two renderers can decide it differently.
    superscript: bool = False
    subscript: bool = False
    # A BOOLEAN AND NOT A COLOUR, and that is a constraint of the .docx rather than a simplification:
    # ``w:highlight`` takes a closed vocabulary of sixteen named colours, not an RGB value, so an
    # arbitrary colour stored here could not be expressed in Word and each renderer would
    # approximate it differently. One fixed yellow — HIGHLIGHT_FILL below — is what all four
    # renderers can draw identically.
    highlight: bool = False


#: The highlight, as the four renderers must all draw it.
#:
#: This is Word's own ``w:highlight w:val="yellow"``, to the byte, because the DOCX writer cannot
#: choose: it names the colour and Word picks the pixels. Both PDF renderers fill a rectangle
#: themselves and would otherwise each choose their own yellow — and the whole point of a
#: highlighted sentence is that it looks the same in the file the office downloaded as in the one
#: generated on the phone.
HIGHLIGHT_FILL = "FFFF00"


def runs_of(
    text: Any,
    *,
    bold: bool = False,
    italic: bool = False,
    underline: bool = False,
    strike: bool = False,
    color: str | None = None,
    superscript: bool = False,
    subscript: bool = False,
    highlight: bool = False,
) -> tuple[Run, ...]:
    """Build the runs for a string, cleaned and split at every script boundary."""
    cleaned = clean_text(text)
    if not cleaned:
        return ()
    return tuple(
        Run(
            text=span,
            bold=bold,
            italic=italic,
            underline=underline,
            strike=strike,
            script=script,
            color=color,
            superscript=superscript,
            subscript=subscript,
            highlight=highlight,
        )
        for span, script in split_by_script(cleaned)
    )


def runs_text(runs: tuple[Run, ...]) -> str:
    """The plain text of a run sequence — for measurement, TOC entries and alt text."""
    return "".join(r.text for r in runs)


# --------------------------------------------------------------------------------------
# Images
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class ImageRef:
    """A picture the report will embed, resolved to bytes by the renderer that needs them.

    ``width_px``/``height_px`` are the *intrinsic* pixel size and ``rotation_deg`` the EXIF
    orientation already resolved to a quarter turn. Both matter: the Android client stores
    camera output unrotated with the orientation only in EXIF, so a renderer that ignores it
    lays every portrait photo on its side, and one that ignores intrinsic size cannot preserve
    aspect ratio when fitting a photo into a grid cell. Carrying them here means the DOCX and
    the PDF compute the same box for the same photo instead of each guessing.

    ``source`` is opaque to this module: a media id on the server, an absolute file path on the
    device. Only the renderer's loader interprets it.
    """

    source: str
    width_px: int = 0
    height_px: int = 0
    rotation_deg: int = 0
    mime_type: str = "image/jpeg"

    @property
    def display_width_px(self) -> int:
        """Width after the EXIF quarter turn is applied."""
        return self.height_px if self.rotation_deg in (90, 270) else self.width_px

    @property
    def display_height_px(self) -> int:
        return self.width_px if self.rotation_deg in (90, 270) else self.height_px

    @property
    def aspect(self) -> float:
        """Displayed width divided by displayed height; 4:3 when the size is unknown.

        Never raises and never returns zero — a zero would propagate into a division in both
        renderers' fit calculations, and an unknown-size photo must still occupy a sane box
        rather than abort the export the designer is standing in a field waiting for.
        """
        w, h = self.display_width_px, self.display_height_px
        if w <= 0 or h <= 0:
            return 4 / 3
        return w / h


# --------------------------------------------------------------------------------------
# Blocks
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class CoverBlock:
    """The report's first page. Rendered by both writers as a full page followed by a break."""

    title: str
    subtitle: str = ""
    org_lines: tuple[str, ...] = ()  # ministry / department, above the title
    logo: ImageRef | None = None
    hero_image: ImageRef | None = None
    info_rows: tuple[tuple[str, str], ...] = ()  # label / value pairs in the cover table
    footer_lines: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class TocBlock:
    """A table of contents.

    DOCX emits a ``TOC`` field so Word paginates it natively; the PDF renderers do it the hard
    way, by laying the body out once to learn the page of every heading and then drawing the
    list. Both honour ``depth``.
    """

    title: str = "Contents"
    depth: int = 3


@dataclass(frozen=True, slots=True)
class HeadingBlock:
    level: int  # 1-4
    runs: tuple[Run, ...]
    number: str = ""  # "3.2" — precomputed by the builder, never by a renderer
    bookmark: str = ""  # stable anchor id, for the TOC and cross references


@dataclass(frozen=True, slots=True)
class ParagraphBlock:
    runs: tuple[Run, ...]
    style: ParaStyle = ParaStyle.BODY
    align: Align = Align.LEFT


@dataclass(frozen=True, slots=True)
class BulletListBlock:
    items: tuple[tuple[Run, ...], ...]
    ordered: bool = False


@dataclass(frozen=True, slots=True)
class KeyValueBlock:
    """Label/value pairs — the shape most single-record stages render as.

    Drawn as a borderless two-column table so a long value wraps under its own label instead of
    pushing the next pair off the line, which is what a tab-separated paragraph did.
    """

    pairs: tuple[tuple[str, tuple[Run, ...]], ...]
    columns: int = 1  # 1 or 2 pairs side by side
    label_width_pct: float = 30.0


@dataclass(frozen=True, slots=True)
class TableColumn:
    header: str
    width_pct: float
    align: Align = Align.LEFT
    numeric: bool = False  # right-aligns the body and lets the renderer group digits


@dataclass(frozen=True, slots=True)
class TableBlock:
    """A data table. ``rows`` holds runs per cell so a cell can mix scripts and bold a total."""

    columns: tuple[TableColumn, ...]
    rows: tuple[tuple[tuple[Run, ...], ...], ...]
    caption: str = ""
    total_row: tuple[tuple[Run, ...], ...] | None = None
    zebra: bool = True

    def __post_init__(self) -> None:
        # A renderer that trusts these percentages and gets 118 draws a table wider than the
        # page, and Word silently rescales while ReportLab silently clips — two different wrong
        # answers from one bad template. Fail where the template is edited instead.
        total = sum(c.width_pct for c in self.columns)
        if self.columns and abs(total - 100.0) > 0.5:
            raise ValueError(
                f"TableBlock column widths must sum to 100, got {total:.1f} "
                f"({[c.header for c in self.columns]})"
            )


@dataclass(frozen=True, slots=True)
class ImageBlock:
    image: ImageRef
    width_pct: float = 70.0  # of the text column
    align: Align = Align.CENTER
    caption: str = ""


@dataclass(frozen=True, slots=True)
class ImageGridBlock:
    """A captioned photo grid — how every stage's gallery reaches the page.

    ``columns`` is a request, not a guarantee: a renderer drops to fewer columns when the cell
    would fall below a legible width, so the same grid stays readable on A4 and on Letter.
    """

    images: tuple[tuple[ImageRef, str], ...]  # (image, caption)
    columns: int = 2
    caption: str = ""


@dataclass(frozen=True, slots=True)
class MetricRowBlock:
    """A row of headline numbers — "12 designs · 7 prototypes · 4 adopted"."""

    metrics: tuple[tuple[str, str, str], ...]  # (label, value, unit)


# --------------------------------------------------------------------------------------
# The map and the infographics
# --------------------------------------------------------------------------------------
#
# Both of these are DESCRIPTIONS, not pictures. A renderer turns one into a PNG through
# ``report_map`` / ``report_chart`` and then hands that PNG to its own existing image path —
# which is the whole reason they rasterise rather than each drawing vectors into OOXML and into
# PDF operators. Two renderers times two figure kinds is four drawing implementations that would
# have to agree; one rasteriser plus the picture path they both already have is one.
#
# Nothing here carries pixels. ``width_pct`` is a fraction of the text column exactly as
# ``ImageBlock``'s is; how many pixels that becomes is the rasteriser's business and the
# rasteriser's alone.


class MapPointKind(str, Enum):
    """What a pin on the report's map of India stands for.

    A renderer draws each kind differently — the venue larger and in the accent, an artisan's
    home district smaller — so a reader can tell "where the workshop was" from "where the people
    came from" without reading a single label.
    """

    VENUE = "VENUE"
    ARTISAN = "ARTISAN"
    MARKET = "MARKET"
    OTHER = "OTHER"


@dataclass(frozen=True, slots=True)
class MapPoint:
    """One pin: what it is called, where it is, and what it stands for.

    ``count`` exists because artisans cluster. Six weavers from Barpali resolve to one
    coordinate, and six pins stacked on that coordinate look like one pin — so the builder folds
    them into a single point that says six, which is both honest and legible.
    """

    label: str
    lat: float
    lon: float
    kind: MapPointKind = MapPointKind.OTHER
    count: int = 1


@dataclass(frozen=True, slots=True)
class MapBlock:
    """A map of India with the workshop's places pinned on it.

    ``highlight`` names STATES to tint. It is a set of names rather than geometry because this
    model must stay renderer-agnostic and because the geometry already exists once, in the
    boundary assets both apps draw; see ``report_map``.
    """

    title: str = ""
    caption: str = ""
    points: tuple[MapPoint, ...] = ()
    highlight: frozenset[str] = frozenset()
    width_pct: float = 66.0  # of the text column, exactly like ImageBlock
    align: Align = Align.CENTER


class ChartKind(str, Enum):
    BAR = "BAR"
    HORIZONTAL_BAR = "HORIZONTAL_BAR"
    PIE = "PIE"
    DONUT = "DONUT"
    LINE = "LINE"

    @property
    def is_circular(self) -> bool:
        return self in {ChartKind.PIE, ChartKind.DONUT}


@dataclass(frozen=True, slots=True)
class ChartBlock:
    """One infographic: a single series of labelled values.

    ONE SERIES, deliberately. Every figure this report needs — designs and prototypes, prototypes
    by status, cost by head, price bands, adoption at three, six and twelve months — is one
    measure over a set of categories. A second series would need a second axis or a stack, and a
    dual-axis figure is the single most misread thing in a government report: two scales, one
    picture, and no reader can tell which line belongs to which.

    ``unit`` is printed once, beside the axis or in the title, never on every value.
    """

    kind: ChartKind
    series: tuple[tuple[str, float], ...] = ()
    title: str = ""
    caption: str = ""
    unit: str = ""
    width_pct: float = 74.0
    align: Align = Align.CENTER

    @property
    def total(self) -> float:
        return sum(value for _label, value in self.series)


@dataclass(frozen=True, slots=True)
class CalloutBlock:
    kind: str  # INFO | WARNING | SUCCESS
    title: str
    runs: tuple[Run, ...]


@dataclass(frozen=True, slots=True)
class SignatureBlock:
    """Sign-off lines for the designer, the implementing agency and the inspecting official."""

    signatories: tuple[tuple[str, str], ...]  # (name, designation)


@dataclass(frozen=True, slots=True)
class SpacerBlock:
    height_pct: float = 5.0  # of the text column height


@dataclass(frozen=True, slots=True)
class PageBreakBlock:
    pass


Block = (
    CoverBlock
    | TocBlock
    | HeadingBlock
    | ParagraphBlock
    | BulletListBlock
    | KeyValueBlock
    | TableBlock
    | ImageBlock
    | ImageGridBlock
    | MetricRowBlock
    | CalloutBlock
    | SignatureBlock
    | SpacerBlock
    | PageBreakBlock
    | MapBlock
    | ChartBlock
)


# --------------------------------------------------------------------------------------
# Document
# --------------------------------------------------------------------------------------


class PageSize(str, Enum):
    A4 = "A4"
    LETTER = "LETTER"

    @property
    def size_mm(self) -> tuple[float, float]:
        return (210.0, 297.0) if self is PageSize.A4 else (215.9, 279.4)


@dataclass(frozen=True, slots=True)
class ReportTheme:
    """Colours and fonts, as the template declares them.

    Colours are bare ``RRGGBB``. Fonts are *names*, resolved per renderer: DOCX writes the name
    and Word finds it, ReportLab needs the TTF registered, and Android maps it onto a Typeface.
    A name no surface can resolve degrades to that surface's default rather than failing.
    """

    accent: str = "1F3864"
    accent_soft: str = "2F5496"
    ink: str = "1B1B1B"
    muted: str = "5A6B87"
    rule: str = "B8C4D9"
    table_header_fill: str = "1F3864"
    table_header_text: str = "FFFFFF"
    zebra_fill: str = "F2F5FA"
    heading_font: str = "Calibri Light"
    body_font: str = "Calibri"
    complex_font: str = "Nirmala UI"  # the w:cs face for Indic runs in DOCX
    base_size_pt: float = 10.5


@dataclass(frozen=True, slots=True)
class ReportMeta:
    title: str
    subtitle: str = ""
    author: str = ""
    organisation: str = ""
    template_id: str = ""
    template_name: str = ""
    workshop_id: str = ""
    generated_at: str = ""  # ISO-8601 UTC, supplied by the caller, never clock-read here
    page_size: PageSize = PageSize.A4
    margin_mm: float = 25.0
    header_text: str = ""
    footer_text: str = ""
    show_page_numbers: bool = True


@dataclass(frozen=True, slots=True)
class ReportDocument:
    meta: ReportMeta
    theme: ReportTheme
    blocks: tuple[Block, ...]
    # Every image the document references, deduplicated by source, in first-use order. The DOCX
    # writer needs this to emit one media part and one relationship per distinct file: a photo
    # used on both the prototype page and the annexure was embedded twice before this existed,
    # which doubled a 60-photo report's size for no visible difference.
    images: tuple[ImageRef, ...] = ()
    warnings: tuple[str, ...] = ()  # completeness-check output, surfaced in the UI not the file


def collect_images(blocks: tuple[Block, ...]) -> tuple[ImageRef, ...]:
    """Every distinct image in ``blocks``, in first-use order."""
    seen: dict[str, ImageRef] = {}
    for block in blocks:
        for img in _images_of(block):
            if img.source not in seen:
                seen[img.source] = img
    return tuple(seen.values())


def _images_of(block: Block) -> list[ImageRef]:
    if isinstance(block, ImageBlock):
        return [block.image]
    if isinstance(block, ImageGridBlock):
        return [img for img, _caption in block.images]
    if isinstance(block, CoverBlock):
        return [i for i in (block.logo, block.hero_image) if i is not None]
    # A MapBlock and a ChartBlock reference NO ImageRef and must not appear here. Their pictures
    # do not exist yet: each renderer rasterises them and holds the bytes itself. Listing them
    # would put a source in ``document.images`` that the caller then tries to download from S3 —
    # ``design_workshops.render_report`` prefetches exactly this tuple — and the miss would be
    # reported to the designer as "1 photograph could not be included".
    return []


# --------------------------------------------------------------------------------------
# Builder helpers — the shorthand the template interpreter is written in
# --------------------------------------------------------------------------------------


@dataclass
class DocumentBuilder:
    """Accumulates blocks and hands back a frozen :class:`ReportDocument`.

    Mutable on purpose and mutable *only here*: the interpreter appends as it walks the
    template, then :meth:`build` freezes the result so no renderer can alter a document it is
    halfway through writing.
    """

    meta: ReportMeta
    theme: ReportTheme = field(default_factory=ReportTheme)
    _blocks: list[Block] = field(default_factory=list)
    _warnings: list[str] = field(default_factory=list)
    _heading_counters: list[int] = field(default_factory=lambda: [0, 0, 0, 0])

    def add(self, block: Block) -> DocumentBuilder:
        self._blocks.append(block)
        return self

    def warn(self, message: str) -> DocumentBuilder:
        self._warnings.append(clean_text(message))
        return self

    def heading(self, text: Any, level: int = 1, *, numbered: bool = True) -> DocumentBuilder:
        """Append a heading, maintaining the "3.2.1" counters the TOC and cross-refs use.

        Numbering lives here rather than in either renderer because Word's own list numbering
        and ReportLab's would restart on different rules, and the two documents would then
        disagree about what "section 4" is — in a report whose prose says "see section 4".
        """
        level = max(1, min(4, level))
        number = ""
        if numbered:
            self._heading_counters[level - 1] += 1
            for i in range(level, 4):
                self._heading_counters[i] = 0
            number = ".".join(str(self._heading_counters[i]) for i in range(level))
        cleaned = clean_text(text)
        bookmark = _bookmark_id(number, cleaned, len(self._blocks))
        return self.add(
            HeadingBlock(level=level, runs=runs_of(cleaned), number=number, bookmark=bookmark)
        )

    def para(
        self, text: Any, *, style: ParaStyle = ParaStyle.BODY, align: Align = Align.LEFT
    ) -> DocumentBuilder:
        """Append a paragraph. A blank value appends nothing at all.

        Skipping empties here is what keeps an optional Standard-tier field from leaving a run
        of blank lines through the middle of a report whose workshop only captured the Basic
        tier.
        """
        cleaned = clean_text(text)
        if not cleaned.strip():
            return self
        # A textarea's blank line is a paragraph break, not a line break inside one.
        for chunk in (c for c in cleaned.split("\n\n") if c.strip()):
            self.add(ParagraphBlock(runs=runs_of(chunk.strip()), style=style, align=align))
        return self

    def bullets(self, items: list[Any], *, ordered: bool = False) -> DocumentBuilder:
        kept = tuple(runs_of(i) for i in items if clean_text(i).strip())
        if not kept:
            return self
        return self.add(BulletListBlock(items=kept, ordered=ordered))

    def key_values(
        self, pairs: list[tuple[str, Any]], *, columns: int = 1, skip_empty: bool = True
    ) -> DocumentBuilder:
        kept = tuple(
            (clean_text(label), runs_of(value))
            for label, value in pairs
            if not skip_empty or clean_text(value).strip()
        )
        if not kept:
            return self
        return self.add(KeyValueBlock(pairs=kept, columns=columns))

    def build(self) -> ReportDocument:
        blocks = tuple(self._blocks)
        return ReportDocument(
            meta=self.meta,
            theme=self.theme,
            blocks=blocks,
            images=collect_images(blocks),
            warnings=tuple(self._warnings),
        )


_NON_WORD = re.compile(r"[^A-Za-z0-9]+")


def _bookmark_id(number: str, text: str, ordinal: int) -> str:
    """A Word-legal bookmark name: letters, digits and underscores, <= 40 chars, unique.

    Word silently discards a bookmark whose name starts with a digit or exceeds 40 characters,
    and a discarded bookmark makes its TOC row unclickable — which looks like a broken report
    rather than a naming-rule violation. The ordinal suffix keeps two identically-titled
    stage headings (every prototype has a "Materials" heading) from colliding.
    """
    slug = _NON_WORD.sub("_", f"{number}_{text}").strip("_")[:28]
    return f"_S{ordinal}_{slug}" if slug else f"_S{ordinal}"
