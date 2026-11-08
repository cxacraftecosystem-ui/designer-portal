from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field
from enum import Enum
from typing import Any

_XML_ILLEGAL = dict.fromkeys([*range(0x00, 0x09), 0x0B, 0x0C, *range(0x0E, 0x20), *range(0xD800, 0xE000), 0xFFFE, 0xFFFF])

_LINE_ENDINGS = re.compile("\r\n?|\u2028|\u2029")


def clean_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "Yes" if value else "No"
    text = value if isinstance(value, str) else str(value)
    text = _LINE_ENDINGS.sub("\n", text)
    return text.translate(_XML_ILLEGAL)



class Script(str, Enum):
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


_NEUTRAL_CATEGORIES = frozenset(
    {
        "Mn",
        "Mc",
        "Me",
        "Zs",
        "Zl",
        "Zp",
        "Cc",
        "Cf",
        "Pd",
        "Ps",
        "Pe",
        "Pi",
        "Pf",
        "Po",
        "Pc",
        "Sm",
        "Sk",
        "Sc",
        "Nd",
        "No",
    }
)


def _script_or_neutral(ch: str) -> Script | None:
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
    return _script_or_neutral(ch) or Script.LATIN


def split_by_script(text: str) -> list[tuple[str, Script]]:
    if not text:
        return []

    resolved: list[Script | None] = [_script_or_neutral(c) for c in text]

    carried: Script | None = None
    for i, s in enumerate(resolved):
        if s is not None:
            carried = s
        else:
            resolved[i] = carried

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
    counts: dict[Script, int] = {}
    for ch in text:
        s = script_of(ch)
        if s is Script.LATIN:
            continue
        counts[s] = counts.get(s, 0) + 1
    if not counts:
        return Script.LATIN
    return max(counts.items(), key=lambda kv: kv[1])[0]



class Align(str, Enum):
    LEFT = "LEFT"
    CENTER = "CENTER"
    RIGHT = "RIGHT"
    JUSTIFY = "JUSTIFY"


class ParaStyle(str, Enum):
    BODY = "BODY"
    LEAD = "LEAD"
    NOTE = "NOTE"
    QUOTE = "QUOTE"
    CAPTION = "CAPTION"
    COVER_LINE = "COVER_LINE"


@dataclass(frozen=True, slots=True)
class Run:
    text: str
    bold: bool = False
    italic: bool = False
    underline: bool = False
    strike: bool = False
    script: Script = Script.LATIN
    color: str | None = None
    superscript: bool = False
    subscript: bool = False
    highlight: bool = False


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
    return "".join(r.text for r in runs)



@dataclass(frozen=True, slots=True)
class ImageRef:
    source: str
    width_px: int = 0
    height_px: int = 0
    rotation_deg: int = 0
    mime_type: str = "image/jpeg"

    @property
    def display_width_px(self) -> int:
        return self.height_px if self.rotation_deg in (90, 270) else self.width_px

    @property
    def display_height_px(self) -> int:
        return self.width_px if self.rotation_deg in (90, 270) else self.height_px

    @property
    def aspect(self) -> float:
        w, h = self.display_width_px, self.display_height_px
        if w <= 0 or h <= 0:
            return 4 / 3
        return w / h



@dataclass(frozen=True, slots=True)
class CoverBlock:
    title: str
    subtitle: str = ""
    org_lines: tuple[str, ...] = ()
    logo: ImageRef | None = None
    hero_image: ImageRef | None = None
    info_rows: tuple[tuple[str, str], ...] = ()
    footer_lines: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class TocBlock:
    title: str = "Contents"
    depth: int = 3


@dataclass(frozen=True, slots=True)
class HeadingBlock:
    level: int
    runs: tuple[Run, ...]
    number: str = ""
    bookmark: str = ""


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
    pairs: tuple[tuple[str, tuple[Run, ...]], ...]
    columns: int = 1
    label_width_pct: float = 30.0


@dataclass(frozen=True, slots=True)
class TableColumn:
    header: str
    width_pct: float
    align: Align = Align.LEFT
    numeric: bool = False


@dataclass(frozen=True, slots=True)
class TableBlock:
    columns: tuple[TableColumn, ...]
    rows: tuple[tuple[tuple[Run, ...], ...], ...]
    caption: str = ""
    total_row: tuple[tuple[Run, ...], ...] | None = None
    zebra: bool = True

    def __post_init__(self) -> None:
        total = sum(c.width_pct for c in self.columns)
        if self.columns and abs(total - 100.0) > 0.5:
            raise ValueError(
                f"TableBlock column widths must sum to 100, got {total:.1f} "
                f"({[c.header for c in self.columns]})"
            )


@dataclass(frozen=True, slots=True)
class ImageBlock:
    image: ImageRef
    width_pct: float = 70.0
    align: Align = Align.CENTER
    caption: str = ""


@dataclass(frozen=True, slots=True)
class ImageGridBlock:
    images: tuple[tuple[ImageRef, str], ...]
    columns: int = 2
    caption: str = ""


@dataclass(frozen=True, slots=True)
class MetricRowBlock:
    metrics: tuple[tuple[str, str, str], ...]



class MapPointKind(str, Enum):
    VENUE = "VENUE"
    ARTISAN = "ARTISAN"
    MARKET = "MARKET"
    OTHER = "OTHER"


@dataclass(frozen=True, slots=True)
class MapPoint:
    label: str
    lat: float
    lon: float
    kind: MapPointKind = MapPointKind.OTHER
    count: int = 1


@dataclass(frozen=True, slots=True)
class MapBlock:
    title: str = ""
    caption: str = ""
    points: tuple[MapPoint, ...] = ()
    highlight: frozenset[str] = frozenset()
    width_pct: float = 66.0
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
    kind: str
    title: str
    runs: tuple[Run, ...]


@dataclass(frozen=True, slots=True)
class SignatureBlock:
    signatories: tuple[tuple[str, str], ...]


@dataclass(frozen=True, slots=True)
class SpacerBlock:
    height_pct: float = 5.0


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



class PageSize(str, Enum):
    A4 = "A4"
    LETTER = "LETTER"

    @property
    def size_mm(self) -> tuple[float, float]:
        return (210.0, 297.0) if self is PageSize.A4 else (215.9, 279.4)


@dataclass(frozen=True, slots=True)
class ReportTheme:
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
    complex_font: str = "Nirmala UI"
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
    generated_at: str = ""
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
    images: tuple[ImageRef, ...] = ()
    warnings: tuple[str, ...] = ()


def collect_images(blocks: tuple[Block, ...]) -> tuple[ImageRef, ...]:
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
    return []



@dataclass
class DocumentBuilder:
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
        cleaned = clean_text(text)
        if not cleaned.strip():
            return self
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
    slug = _NON_WORD.sub("_", f"{number}_{text}").strip("_")[:28]
    return f"_S{ordinal}_{slug}" if slug else f"_S{ordinal}"
