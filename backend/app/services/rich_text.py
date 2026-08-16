"""The portable rich-text document: one representation, five renderers, no HTML anywhere.

A designer writing the introduction to a workshop report needs bold, italic, underline,
alignment, and numbered and bulleted lists — the ordinary furniture of a document. That text has
to survive being typed on a phone, saved into a JSON column, printed into a .docx, drawn onto a
PDF page by two different renderers, and shown back in a browser preview.

**It is stored as a structured document, never as HTML.** HTML would have been the quick answer
and is the wrong one, for three reasons that all end in the same place:

1. *The phone cannot render it.* The on-device PDF writer draws onto a Canvas with a TextPaint.
   Handing it a fragment of HTML means shipping an HTML parser and a CSS cascade to a field
   phone, or it means the offline report silently loses every mark the designer applied.
2. *It is an injection surface.* Stored HTML is attacker-controlled markup that four surfaces
   later interpret. A structured document has no ``<script>`` to smuggle, because there is no tag
   vocabulary at all — a mark is an enum member or it does not exist.
3. *It cannot be validated.* ``<b><i>x</b></i>`` is a thing a contenteditable will produce, and
   no amount of care downstream makes it well-defined. The model below cannot express it.

The shape is deliberately the smallest one that covers the requirement::

    RichDoc
      └── blocks: RichBlock[]        PARAGRAPH | HEADING | BULLET_ITEM | ORDERED_ITEM | QUOTE
            │                        | TABLE | IMAGE
            ├── align                LEFT | CENTER | RIGHT | JUSTIFY
            ├── level                heading level (1-4), or list nesting depth (0-3)
            ├── rows                 TABLE only — cells of spans, the first row the header
            ├── media / width_pct    IMAGE only — the media id and its printed width
            └── spans: RichSpan[]    text + marks (the CAPTION, on an IMAGE)
                  └── marks          BOLD | ITALIC | UNDERLINE | STRIKETHROUGH | CODE
                                     | SUPERSCRIPT | SUBSCRIPT | HIGHLIGHT

That is a *flat* block list with a nesting depth, not a tree. A tree is what every rich-text
library produces and it is the wrong shape here: the DOCX and PDF writers both lay out one
paragraph at a time, so a tree would be flattened at the start of all four renderers and the four
flattenings would disagree about the edge cases. Flattening once, at the point the editor saves,
means the renderers cannot drift.

:func:`to_json` and :func:`from_json` are inverse. :func:`to_plain` throws the marks away, for
search, for CSV export and for the completeness gate — which counts a field as filled on its
text, never on its formatting.

A design workshop stores these documents in a JSON column, so the pair above is the whole story
there. The RECORD forms — artisan notes, product remarks, tool remarks, process notes — store them
inside plain ``String?`` columns that also hold, and will keep holding, ordinary prose. That needs a
second pair, :func:`from_stored_text` / :func:`to_stored_text`, and a read boundary,
:func:`plain_from_stored`; the section that defines them argues the case at length and names the
silent-corruption defect it exists to prevent. Nothing in this module knows what a workshop is.

Every string entering this module passes ``report_model.clean_text``, for exactly the reason that
module documents: a lone surrogate from a phone that cut an emoji in half makes
``word/document.xml`` not well-formed, and Word refuses the entire file rather than dropping the
character.

``backend/tests/test_rich_text.py`` pins the round trip, the sanitisation and the block mapping.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from enum import Enum
from typing import Any

from app.services.report_model import (
    Align,
    BulletListBlock,
    ParagraphBlock,
    ParaStyle,
    Run,
    clean_text,
    runs_of,
    split_by_script,
)

# A stored document larger than this is not prose a human typed, and letting it through means a
# report generation that never finishes. The limit is per FIELD and generous: the longest
# narrative field in the registry is a cluster history, and 200 000 characters is around forty
# pages of it.
MAX_DOCUMENT_CHARS = 200_000
MAX_BLOCKS = 2_000
MAX_HEADING_LEVEL = 4
MAX_LIST_DEPTH = 3


class Mark(str, Enum):
    """An inline mark. The whole vocabulary — there is no mechanism for adding one at runtime."""

    BOLD = "BOLD"
    ITALIC = "ITALIC"
    UNDERLINE = "UNDERLINE"
    STRIKETHROUGH = "STRIKETHROUGH"
    CODE = "CODE"
    #: The two that a workshop report actually needs and that every renderer can carry: a footnote
    #: marker, "m²", "H₂O", the ordinal in "3ʳᵈ warp". Added together because they are one control
    #: in the designer's head and one property — ``w:vertAlign`` — in the file.
    #:
    #: A CHARACTER CANNOT BE BOTH. Both marks on one span is a document no editor here can produce
    #: and a hand-written value could; :func:`_runs_for` resolves it by letting SUPERSCRIPT win, in
    #: one place, so the .docx and the two PDF writers cannot disagree about which one it was.
    SUPERSCRIPT = "SUPERSCRIPT"
    SUBSCRIPT = "SUBSCRIPT"
    #: One highlight, not a palette, and the colour is not the designer's to choose.
    #:
    #: ``w:highlight`` takes a CLOSED vocabulary of sixteen named colours — it is not an RGB value —
    #: so a picker offering an arbitrary colour would store something Word cannot express and every
    #: renderer would then approximate differently. One fixed yellow is what all five can draw
    #: identically, and it is also the only thing a highlight in a submitted report ever means:
    #: "look at this". Seven colours would be seven meanings nobody has agreed.
    HIGHLIGHT = "HIGHLIGHT"


class BlockKind(str, Enum):
    PARAGRAPH = "PARAGRAPH"
    HEADING = "HEADING"
    BULLET_ITEM = "BULLET_ITEM"
    ORDERED_ITEM = "ORDERED_ITEM"
    QUOTE = "QUOTE"
    #: A grid the designer built inside the prose. Its content lives in ``RichBlock.rows`` rather
    #: than in ``spans`` — the ONE block whose text is not a single run of spans, and the reason
    #: every text-shaped helper below has an explicit table arm instead of relying on ``spans``.
    TABLE = "TABLE"
    #: A photograph placed WHERE THE DESIGNER PUT IT, rather than in the gallery at the end of the
    #: section. ``media`` holds the id; the caption is the block's ``spans``, so it can be bolded
    #: and can carry a script run like any other prose.
    #:
    #: This is a different thing from the IMAGE_LIST fields the registry already has. Those are a
    #: stage's photographs and the template decides where they print; this one belongs to a
    #: sentence — "the puckering at the seam, below" — and printing it three pages away in a
    #: gallery makes the sentence meaningless.
    IMAGE = "IMAGE"

    @property
    def is_list_item(self) -> bool:
        return self in {BlockKind.BULLET_ITEM, BlockKind.ORDERED_ITEM}


@dataclass(frozen=True, slots=True)
class RichSpan:
    """A run of text carrying zero or more marks."""

    text: str
    marks: frozenset[Mark] = frozenset()

    @property
    def bold(self) -> bool:
        return Mark.BOLD in self.marks

    @property
    def italic(self) -> bool:
        return Mark.ITALIC in self.marks

    @property
    def underline(self) -> bool:
        return Mark.UNDERLINE in self.marks

    @property
    def strike(self) -> bool:
        return Mark.STRIKETHROUGH in self.marks

    @property
    def code(self) -> bool:
        return Mark.CODE in self.marks

    @property
    def superscript(self) -> bool:
        return Mark.SUPERSCRIPT in self.marks

    @property
    def subscript(self) -> bool:
        # SUPERSCRIPT wins a span that carries both — see the note on the enum. Resolved on the
        # READER rather than on the way in, so the designer's own marks are stored exactly as they
        # were applied and only the rendering is made single-valued.
        return Mark.SUBSCRIPT in self.marks and Mark.SUPERSCRIPT not in self.marks

    @property
    def highlight(self) -> bool:
        return Mark.HIGHLIGHT in self.marks


#: One table cell: a run of spans, and deliberately NOT a list of blocks.
#:
#: A cell that could hold blocks could hold a table, and a table inside a table inside a table is
#: a shape the .docx writer, both PDF writers and the browser preview would each have to agree
#: about — including how deep to stop. Every real use here is a grid of short values (a cost head
#: and an amount, a count against a month), so a cell is one paragraph's worth of marked text and
#: the model cannot express the case nobody asked for.
RichCell = tuple[RichSpan, ...]

#: One row: its cells, left to right.
RichRow = tuple[RichCell, ...]

#: A table bigger than this is a spreadsheet somebody pasted, not prose with a grid in it. Both
#: bounds are enforced on the way IN, so no renderer ever meets a 400-column table.
MAX_TABLE_ROWS = 200
MAX_TABLE_COLUMNS = 12


@dataclass(frozen=True, slots=True)
class RichBlock:
    kind: BlockKind = BlockKind.PARAGRAPH
    spans: tuple[RichSpan, ...] = ()
    align: Align = Align.LEFT
    #: Heading level 1-4 for HEADING; list nesting depth 0-3 for the two item kinds; ignored
    #: otherwise.
    level: int = 0
    #: TABLE only, and empty for every other kind. Rows of cells, the FIRST row being the header —
    #: which is what lets the whole block map onto ``report_model.TableBlock`` without inventing a
    #: second table shape for the renderers to disagree about.
    rows: tuple[RichRow, ...] = ()
    #: IMAGE only: the media id. Resolved to an ``ImageRef`` by the SAME ``MediaResolver`` every
    #: other picture in the report goes through, so an inline photograph is embedded, rotated and
    #: sized by exactly the code that handles a gallery one.
    media: str = ""
    #: IMAGE only: the printed width as a percentage of the text column, clamped 10-100.
    width_pct: float = 70.0

    @property
    def text(self) -> str:
        # A table's text is its cells, read left to right and top to bottom. It has to be defined,
        # because `is_empty` reads it and the completeness gate reads `is_empty`: a stage whose
        # only content was a filled-in table would otherwise count as unfilled, and the designer
        # would be told they had left it blank.
        if self.kind is BlockKind.TABLE:
            return "\n".join(
                "\t".join("".join(span.text for span in cell) for cell in row)
                for row in self.rows
            )
        return "".join(span.text for span in self.spans)

    @property
    def is_empty(self) -> bool:
        # An IMAGE is never empty while it holds a media id, even with no caption. Judging it on
        # text would make a field whose only content is a photograph read as unfilled, and the
        # completeness gate — which reads exactly this — would tell the designer they had left a
        # required narrative blank while a picture of the loom sat in it.
        if self.kind is BlockKind.IMAGE:
            return not self.media
        return not self.text.strip()

    @property
    def column_count(self) -> int:
        """The widest row. Rows are padded to this when the table is rendered, never before."""
        return max((len(row) for row in self.rows), default=0)


@dataclass(frozen=True, slots=True)
class RichDoc:
    blocks: tuple[RichBlock, ...] = ()

    @property
    def is_empty(self) -> bool:
        return all(block.is_empty for block in self.blocks)

    @property
    def text(self) -> str:
        return "\n".join(block.text for block in self.blocks)


EMPTY = RichDoc()


# --------------------------------------------------------------------------------------
# Parsing — every path in is forgiving, because three clients write these
# --------------------------------------------------------------------------------------


def _coerce_marks(raw: Any) -> frozenset[Mark]:
    """The marks in ``raw`` that this build recognises; anything else is dropped in silence.

    Dropping rather than raising is the same rule the stage registry follows for unknown field
    keys: a phone one release ahead may apply a mark this server has never heard of, and losing
    the mark is a cosmetic regression while losing the paragraph is data loss.
    """
    if not isinstance(raw, (list, tuple, set, frozenset)):
        return frozenset()
    found: set[Mark] = set()
    for item in raw:
        try:
            found.add(Mark(str(item).upper()))
        except ValueError:
            continue
    return frozenset(found)


def _coerce_align(raw: Any) -> Align:
    try:
        return Align(str(raw).upper())
    except (ValueError, AttributeError):
        return Align.LEFT


def _coerce_kind(raw: Any) -> BlockKind:
    try:
        return BlockKind(str(raw).upper())
    except (ValueError, AttributeError):
        return BlockKind.PARAGRAPH


def from_json(raw: Any) -> RichDoc:
    """Build a document from whatever a client stored, never raising.

    Accepts three shapes, because all three genuinely occur:

    * the canonical ``{"blocks": [...]}`` this module emits;
    * a bare list of blocks, which is what a client that stored ``doc.blocks`` produces;
    * a plain string, which is EVERY value written before a field was promoted from LONG_TEXT to
      RICH_TEXT. That last one is the important case: promoting a field must not blank the prose
      already stored under it, so a string is read as an unformatted document, with its blank
      lines becoming paragraph breaks exactly as the plain-text renderer treated them.
    """
    if raw is None:
        return EMPTY
    if isinstance(raw, RichDoc):
        return raw
    if isinstance(raw, str):
        return from_plain(raw)
    if isinstance(raw, dict):
        blocks_raw = raw.get("blocks")
    elif isinstance(raw, (list, tuple)):
        blocks_raw = raw
    else:
        return from_plain(str(raw))
    if not isinstance(blocks_raw, (list, tuple)):
        return EMPTY

    blocks: list[RichBlock] = []
    budget = MAX_DOCUMENT_CHARS
    for entry in blocks_raw[:MAX_BLOCKS]:
        if not isinstance(entry, dict):
            # A bare string in the block list is a paragraph; anything else is skipped.
            if isinstance(entry, str):
                text = clean_text(entry)[:budget]
                budget -= len(text)
                if text:
                    blocks.append(RichBlock(spans=(RichSpan(text),)))
            continue
        kind = _coerce_kind(entry.get("kind"))
        spans: list[RichSpan] = []
        for span_raw in (entry.get("spans") or ())[:512]:
            if isinstance(span_raw, str):
                text, marks = clean_text(span_raw), frozenset()
            elif isinstance(span_raw, dict):
                text = clean_text(span_raw.get("text"))
                marks = _coerce_marks(span_raw.get("marks"))
            else:
                continue
            # A newline inside a span would make one block render as two, which no renderer
            # expects; the editor emits a new block for a new line, so this only fires on
            # hand-written or migrated data.
            text = text.replace("\n", " ")
            if budget <= 0:
                break
            text = text[:budget]
            budget -= len(text)
            if text:
                spans.append(RichSpan(text=text, marks=marks))
        level = entry.get("level")
        level = int(level) if isinstance(level, (int, float)) and not isinstance(level, bool) else 0
        if kind is BlockKind.HEADING:
            level = max(1, min(MAX_HEADING_LEVEL, level or 1))
        elif kind.is_list_item:
            level = max(0, min(MAX_LIST_DEPTH, level))
        else:
            level = 0
        media = ""
        width_pct = 70.0
        if kind is BlockKind.IMAGE:
            media = clean_text(entry.get("media")).strip()[:64]
            # An IMAGE with no media id is not a picture. Dropped rather than kept, exactly as an
            # empty TABLE is: keeping it would put an un-resolvable placeholder into the document
            # and make `is_empty` answer "filled" for a block that prints nothing.
            if not media:
                continue
            raw_width = entry.get("widthPct")
            if isinstance(raw_width, (int, float)) and not isinstance(raw_width, bool):
                width_pct = max(10.0, min(100.0, float(raw_width)))

        rows: tuple[RichRow, ...] = ()
        if kind is BlockKind.TABLE:
            rows, budget = _coerce_rows(entry.get("rows"), budget)
            # A TABLE with nothing in it is not a table. Dropping it rather than keeping an empty
            # grid matters because `is_empty` judges on text: a document whose only block was a
            # 3x3 of blank cells would otherwise be "filled" and pass the completeness gate.
            if not rows:
                continue
        blocks.append(RichBlock(
            kind=kind,
            spans=tuple(spans),
            align=_coerce_align(entry.get("align")),
            level=level,
            rows=rows,
            media=media,
            width_pct=width_pct,
        ))
        if budget <= 0:
            break
    return RichDoc(blocks=tuple(blocks))


def _coerce_rows(raw: Any, budget: int) -> tuple[tuple[RichRow, ...], int]:
    """Rows of cells of spans, bounded, forgiving, and sharing the document's character budget.

    Forgiving in the same way as everything else in this module: a row that is not a list is
    skipped, a cell that is a bare string becomes one unmarked span, and a ragged table is kept
    ragged rather than rejected — ``column_count`` pads it at render time. The alternative is
    refusing a designer's whole introduction because one row of their pasted grid had a trailing
    comma in it.
    """
    if not isinstance(raw, (list, tuple)):
        return (), budget
    rows: list[RichRow] = []
    for row_raw in raw[:MAX_TABLE_ROWS]:
        if not isinstance(row_raw, (list, tuple)):
            continue
        cells: list[RichCell] = []
        for cell_raw in row_raw[:MAX_TABLE_COLUMNS]:
            spans: list[RichSpan] = []
            items = (cell_raw,) if isinstance(cell_raw, str) else cell_raw
            if not isinstance(items, (list, tuple)):
                cells.append(())
                continue
            for span_raw in items[:64]:
                if isinstance(span_raw, str):
                    text, marks = clean_text(span_raw), frozenset()
                elif isinstance(span_raw, dict):
                    text = clean_text(span_raw.get("text"))
                    marks = _coerce_marks(span_raw.get("marks"))
                else:
                    continue
                # Same rule as a paragraph span: a newline inside one would make a single cell
                # render as two lines in the browser and one in the .docx.
                text = text.replace("\n", " ")
                if budget <= 0:
                    break
                text = text[:budget]
                budget -= len(text)
                if text:
                    spans.append(RichSpan(text=text, marks=marks))
            cells.append(tuple(spans))
        if cells:
            rows.append(tuple(cells))
        if budget <= 0:
            break
    # Drop trailing rows that are entirely blank, which is what an editor leaves behind when a
    # designer adds a row and then thinks better of it.
    while rows and not any(any(s.text.strip() for s in cell) for cell in rows[-1]):
        rows.pop()
    return tuple(rows), budget


def from_plain(text: Any) -> RichDoc:
    """A plain string as an unformatted document, one paragraph per line.

    Blank lines separate paragraphs and are not themselves kept, matching how ``DocumentBuilder``
    has always split a textarea's contents.
    """
    cleaned = clean_text(text)[:MAX_DOCUMENT_CHARS]
    if not cleaned.strip():
        return EMPTY
    blocks = [
        RichBlock(spans=(RichSpan(line.strip()),))
        for line in cleaned.split("\n")
        if line.strip()
    ]
    return RichDoc(blocks=tuple(blocks[:MAX_BLOCKS]))


# --------------------------------------------------------------------------------------
# Serialising
# --------------------------------------------------------------------------------------


def to_json(doc: RichDoc) -> dict[str, Any]:
    """The canonical storage form. Defaults are omitted to keep the JSON column small."""
    def span_json(span: RichSpan) -> dict[str, Any]:
        return ({"text": span.text, "marks": sorted(m.value for m in span.marks)}
                if span.marks else {"text": span.text})

    blocks: list[dict[str, Any]] = []
    for block in doc.blocks:
        entry: dict[str, Any] = {
            "kind": block.kind.value,
            "spans": [span_json(span) for span in block.spans],
        }
        if block.align is not Align.LEFT:
            entry["align"] = block.align.value
        if block.level:
            entry["level"] = block.level
        if block.kind is BlockKind.TABLE:
            # `spans` stays present and empty for a table rather than being omitted, because the
            # Kotlin port's oracle compares these dicts key for key and an absent key and an empty
            # list are different documents to it.
            entry["rows"] = [[[span_json(span) for span in cell] for cell in row]
                             for row in block.rows]
        if block.kind is BlockKind.IMAGE:
            entry["media"] = block.media
            # Written only when it differs from the default, like `align` and `level` above — the
            # JSON column carries one of these per photograph and the defaults are the common case.
            if block.width_pct != 70.0:
                entry["widthPct"] = block.width_pct
        blocks.append(entry)
    return {"blocks": blocks}


def to_plain(doc: Any) -> str:
    """The text with every mark discarded.

    List items keep a visible marker, because this string is what a CSV export and a search index
    receive, and a bulleted list flattened to bare lines reads as one run-on sentence.
    """
    document = doc if isinstance(doc, RichDoc) else from_json(doc)
    lines: list[str] = []
    ordinal = 0
    for block in document.blocks:
        if block.kind is BlockKind.IMAGE:
            # The CAPTION is the text of a photograph. A search index and a CSV export want the
            # words a person wrote, and "[image]" is not one of them; a caption-less picture
            # contributes nothing, which is correct — it has no text.
            ordinal = 0
            caption = block.text.strip()
            if caption:
                lines.append(caption)
            continue
        if block.kind is BlockKind.TABLE:
            # One line per row, cells joined by " | ". A tab would be invisible in a search index
            # and would make two adjacent cells read as one word in a CSV export.
            ordinal = 0
            for row in block.rows:
                lines.append(" | ".join(
                    "".join(span.text for span in cell).strip() for cell in row
                ))
            continue
        text = block.text.strip()
        if block.kind is BlockKind.ORDERED_ITEM:
            ordinal += 1
            lines.append(f"{'  ' * block.level}{ordinal}. {text}")
            continue
        ordinal = 0
        if block.kind is BlockKind.BULLET_ITEM:
            lines.append(f"{'  ' * block.level}• {text}")
        else:
            lines.append(text)
    return "\n".join(lines).strip()


def is_empty(raw: Any) -> bool:
    """Whether a stored value counts as unfilled, for the completeness gate.

    A document of empty paragraphs is empty. This is why the gate reads the TEXT and not the
    presence of the JSON: an editor that has been focused and left alone still saves
    ``{"blocks":[{"kind":"PARAGRAPH","spans":[]}]}``, and counting that as a filled required
    field would let a designer submit a report with a blank introduction.
    """
    if raw is None:
        return True
    if isinstance(raw, str):
        return not raw.strip()
    return from_json(raw).is_empty


# --------------------------------------------------------------------------------------
# Rich text inside a plain ``String`` column — the record forms, not the workshop
# --------------------------------------------------------------------------------------
#
# EVERYTHING ABOVE THIS LINE ASSUMES A JSON COLUMN. A design workshop keeps its rich fields inside
# ``DwStageEntry.data``, a Prisma ``Json`` column, so the value arriving at :func:`from_json` is
# already a ``dict`` and the question "is this a document or is this prose?" never came up.
#
# The record forms — artisan notes, product remarks, tool remarks, process notes — have no JSON
# column and are NOT getting one: their columns are ``String?`` in ``prisma/schema.prisma`` and they
# stay ``String?``. Every row in production today holds plain prose, older clients will keep writing
# plain prose forever, and a migration to change the type is a schema change this feature does not
# get to make. So a rich document has to live inside a string, alongside the plain strings, and be
# told apart from them by looking at it.
#
# ── THE DEFECT THIS SECTION EXISTS TO PREVENT ───────────────────────────────────────────────────
# :func:`from_json` reads a ``str`` as PROSE and never as JSON (see its docstring — that is the rule
# that makes promoting a LONG_TEXT field to RICH_TEXT non-destructive, and it is load-bearing in all
# three ports). Feed it the string ``'{"blocks":[{"kind":"PARAGRAPH","spans":[{"text":"hello"}]}]}'``
# and it hands back a document whose single paragraph is that line of JSON syntax, verbatim. The
# CSV a ministry downloads then contains a column of braces. **Nothing crashes.** That is what makes
# it dangerous: it is silent corruption on read, discovered by a reader, weeks later, in a file that
# has already left the building.
#
# So the string form gets its own front door, :func:`from_stored_text`, which tries JSON FIRST and
# falls back to prose. Do not "simplify" a call to it into a call to ``from_json``.
#
# ── THE STORED SHAPE, WHICH IS A CROSS-PLATFORM CONTRACT AND NOT THIS FILE'S TO CHANGE ──────────
# The column holds one of exactly three things, and all three genuinely occur:
#
#     NULL                                the researcher left the box empty
#     prose                               everything written before this feature, everything an
#                                         older client writes after it, and everything written
#                                         since that nobody actually formatted
#     {"blocks":[…]}                      a serialised document, and ONLY once a mark, a heading,
#                                         a list, a quote, an alignment, a table or an inline
#                                         photograph has been applied
#
# The document is recognised BY SHAPE — a value that trims to ``{…}`` and parses to an object with a
# ``blocks`` array — rather than by a marker byte, and the rule is written out identically in
# ``frontend/components/richtext/storedRichText.ts`` (``decodeStoredRichText`` /
# ``encodeStoredRichText``), which is the editor that actually writes these columns. **The two must
# agree exactly.** A sentinel-prefixed variant was considered here and rejected for that reason
# alone: this side inventing a second envelope would mean the web editor re-opening a
# backend-written value could not parse it and would show the researcher their own prose followed by
# a line of machinery.
#
# WHY "ONLY WHEN FORMATTED" IS THE LOAD-BEARING HALF. Turning a rich editor on over an existing
# column must not rewrite the corpus. Because an unformatted document is written back as prose, the
# overwhelming majority of rows keep exactly the bytes they keep today — so free-text search, the
# four export surfaces, the review panel and the Android forms see no change at all for them, and no
# coordinated release is needed. The cost of the feature is confined to rows somebody deliberately
# formatted. Make the encoding unconditional and that cost moves to every row in the repository.
#
# ── WHAT THIS COSTS FREE-TEXT SEARCH, STATED RATHER THAN HIDDEN ─────────────────────────────────
# Search in this app is a raw Prisma ``contains`` against the column — ``ILIKE '%term%'`` — in eleven
# places, and none of them can parse a document first. Against a FORMATTED row the prose survives
# only inside ``"text"`` values, so three things go wrong and only one of them is fixable here:
#
#   * a query containing a character JSON escapes (a quote, a backslash, a newline, a tab) can never
#     match, because the column holds the escaped form. FIXED — see :func:`search_needles`, used by
#     ``records.prose_contains``;
#   * a phrase interrupted by a bolded word, or spanning a paragraph or list-item boundary, is split
#     across two ``"text"`` values and can never match. NOT FIXABLE with ``contains``; closing it
#     needs a generated column or a real text index, i.e. the migration this feature did not take;
#   * the envelope's own key names and enum values (``blocks``, ``spans``, ``text``, ``marks``,
#     ``align``, ``level``, ``rows``, ``media``, ``kind``, ``TABLE``, ``IMAGE``, ``QUOTE``, ``CODE``,
#     ``BOLD``, ``LEFT``, ``RIGHT``, ``CENTER``…) are ordinary English words, so searching for one of
#     THOSE over-matches every formatted row. A visible false positive a person can dismiss, unlike
#     the two above. Closing it means shortening the envelope's keys in all three ports at once.
#
# Because only formatted rows are affected, that is a bounded regression and not a general one. Do
# not "fix" it by having the encoder always write JSON — that is the change that would make it
# general.


def _document_from_payload(payload: str) -> RichDoc | None:
    """Parse a serialised ``{"blocks": […]}`` envelope, or ``None`` if that is not what this is.

    The ``blocks`` key is REQUIRED and must hold a list, matching ``decodeStoredRichText`` on the
    web line for line. A bare list at the top level is accepted by :func:`from_json` and is
    deliberately NOT accepted here: in a column that mostly holds prose, ``[see the photograph]`` is
    a far more likely value than a serialised block array, and guessing wrong turns a researcher's
    note into an empty cell.
    """
    try:
        parsed = json.loads(payload)
    except (ValueError, TypeError):
        return None
    if not isinstance(parsed, dict) or not isinstance(parsed.get("blocks"), list):
        return None
    return from_json(parsed)


def stored_text_document(value: str) -> RichDoc | None:
    """The document inside a ``String`` column value, or ``None`` when the value is plain prose.

    ``None`` is not a failure — it is the answer for every row written before this feature and
    every row an older client will write after it. Callers must treat it as "leave this string
    alone", never as "empty".
    """
    stripped = value.strip()
    if not stripped.startswith("{") or not stripped.endswith("}"):
        # Cheap gate before the parse, and the same one the web takes: prose is not JSON-shaped, and
        # attempting ``json.loads`` on every note in a 2000-row export would be paid per cell.
        return None
    return _document_from_payload(stripped)


def is_stored_rich_text(value: Any) -> bool:
    """Whether a column value carries formatting, rather than being the plain string it looks like."""
    if isinstance(value, (dict, list, RichDoc)):
        return True
    return isinstance(value, str) and stored_text_document(value) is not None


def from_stored_text(value: Any) -> RichDoc:
    """A ``String`` column value as a document — JSON first, prose second.

    The counterpart of :func:`from_json` for columns that are not JSON columns. Use this, and not
    ``from_json``, anywhere the value came out of a ``String``/``String?`` Prisma field.
    """
    if value is None:
        return EMPTY
    if isinstance(value, str):
        document = stored_text_document(value)
        return document if document is not None else from_plain(value)
    return from_json(value)


def _is_unformatted(document: RichDoc) -> bool:
    """Whether every block survives a trip through plain text unchanged.

    A WHITELIST of "nothing interesting is set", never a blacklist of known-lossy features, and the
    twin of ``isPlainProse`` in ``frontend/components/richtext/storedRichText.ts``. A block kind or
    a mark added to this module later must default to "store as JSON": the other polarity fails
    invisibly, flattening a researcher's table to pipe-separated lines at save time, which nobody
    finds out about until the report is printed.
    """
    for block in document.blocks:
        if block.kind is not BlockKind.PARAGRAPH:
            return False
        if block.align is not Align.LEFT or block.level or block.rows or block.media:
            return False
        if any(span.marks for span in block.spans):
            return False
    return True


def to_stored_text(doc: Any) -> str | None:
    """A document as a value for a plain ``String?`` column — prose unless it is actually formatted.

    The Python twin of ``encodeStoredRichText`` in ``frontend/components/richtext/storedRichText.ts``
    and the thing that keeps the two honest: ``backend/tests/test_rich_text_stored_columns.py``
    asserts the shape this produces is exactly the shape :func:`stored_text_document` recognises, so
    a divergence between the two platforms shows up here rather than as a researcher's notes turning
    into braces.

    No backend route writes these columns from a document today — every write arrives as a string
    from a client. It exists so that the first one that does cannot invent a third encoding, and so
    the read tests can build a realistic stored value without hand-writing JSON.

    ONE DELIBERATE OMISSION: the web encoder takes a ``join`` argument and rejoins an unformatted
    document's blocks with a BLANK line for ``Artisan.notes`` and the process form's step notes,
    because ``MultiNoteField`` (web) and ``MultiNoteInput`` (Android) both split those two columns
    on blank lines to rebuild their note rows. That contract belongs to the multi-note CONTROL, not
    to the column, and no server-side caller has one — reproducing it here would be a second copy of
    a rule with no way to know when it applies. Anything writing one of those two columns from a
    document on this side must join with ``"\\n\\n"`` itself, or four notes silently become one.

    ``None`` rather than ``""`` for an empty document, because ``NULL`` is what every one of these
    columns already means by "the researcher left this blank"; a feature that starts writing empty
    strings where NULLs were makes ``field IS NULL`` reporting quietly wrong.
    """
    document = doc if isinstance(doc, RichDoc) else from_json(doc)
    if document.is_empty:
        return None
    if _is_unformatted(document):
        return to_plain(document) or None
    # ``ensure_ascii=False`` so Devanagari, Bengali and Gujarati prose stays legible in the column
    # to anybody reading it with psql — and, more practically, so the raw ``contains`` searches see
    # the same bytes the researcher typed rather than a run of ``\uXXXX`` escapes.
    return json.dumps(to_json(document), ensure_ascii=False, separators=(",", ":"))


def plain_from_stored(value: Any) -> Any:
    """Any read value, with formatting flattened away — AND A NO-OP ON EVERYTHING ELSE.

    This is the read boundary. Call it wherever a record column is rendered for a human or written
    into a file: the info panel, the .xlsx sheets, ``details.txt``, both CSV downloads.

    **The identity guarantee is the important half, and ``backend/tests/test_rich_text_stored_
    columns.py`` pins it.** A plain string comes back as the *same object*, not as a string that
    survived a round trip through :func:`from_plain` and :func:`to_plain`. That round trip is not
    the identity: it runs ``clean_text``, strips each line, collapses runs of blank lines and drops
    trailing whitespace. Applying it to the whole repository would silently reformat every existing
    note, address and remark in every export — a diff nobody asked for, across data this app's users
    are the custodians of rather than the authors of. Non-strings (``Decimal``, ``datetime``, ``int``,
    an enum) are returned untouched for the same reason: their callers already know how to coerce
    them and this function has no opinion about them.
    """
    if isinstance(value, str):
        document = stored_text_document(value)
        return value if document is None else to_plain(document)
    if isinstance(value, (dict, list, RichDoc)):
        # A JSON column, or a value already parsed by a caller. There is no ambiguity here, so no
        # detection: it is a document.
        return to_plain(value)
    return value


def search_needles(term: str) -> tuple[str, ...]:
    """The literal strings a raw ``contains`` must try so that a search still finds formatted prose.

    ONE needle for an ordinary word. Inside a stored document the researcher's words sit in
    ``"text"`` values as themselves, so ``ILIKE '%weaving%'`` finds ``{"text":"weaving on a pit
    loom"}`` with no help at all — which is why this is not a general search rewrite.

    A SECOND needle when the term contains a character JSON escapes: a quote, a backslash, a
    newline, a tab. Those do NOT survive as themselves — ``he said "no"`` is stored as
    ``he said \\"no\\"`` — so the typed term matches nothing and the researcher is told the record
    does not exist. The escaped needle is that repair, and it is the only one of the three recall
    gaps listed in this section's banner that a ``contains`` can close.

    Deliberately one needle in the common case rather than always two: each extra needle is another
    ``ILIKE '%…%'`` in the WHERE clause, no btree can answer any of them, and paying that on every
    search for a word with no quote in it would be a measurable cost for zero recall.
    """
    escaped = json.dumps(term, ensure_ascii=False)[1:-1]
    return (term,) if escaped == term else (term, escaped)


# --------------------------------------------------------------------------------------
# Rendering into the report model
# --------------------------------------------------------------------------------------

_STYLE_FOR_KIND = {
    BlockKind.PARAGRAPH: ParaStyle.BODY,
    BlockKind.QUOTE: ParaStyle.QUOTE,
}


def _runs_for(span: RichSpan) -> list[Run]:
    """One span as report-model runs, split at every script boundary.

    The split has to happen HERE rather than in the renderers: a span is a unit of *formatting*
    and a run is a unit of *formatting and font*, and a designer who bolds a phrase containing
    both English and Odia has produced one span that must become two runs or half of it renders
    as boxes.
    """
    return [
        Run(
            text=text,
            bold=span.bold,
            italic=span.italic,
            underline=span.underline,
            strike=span.strike,
            script=script,
            superscript=span.superscript,
            subscript=span.subscript,
            highlight=span.highlight,
        )
        for text, script in split_by_script(span.text)
    ]


def to_report_blocks(raw: Any, *, base_style: ParaStyle = ParaStyle.BODY,
                     resolve_media: Any = None) -> list[Any]:
    """Render a stored rich-text value into report-model blocks.

    Consecutive list items of the same kind are merged into ONE ``BulletListBlock``, because that
    is what both writers want: a .docx list is a run of paragraphs sharing a numbering id, and
    emitting one block per item would restart the numbering at every line — a five-point
    recommendation list printed as "1. 1. 1. 1. 1.".
    """
    document = from_json(raw)
    out: list[Any] = []
    pending: list[tuple[Any, ...]] = []
    pending_ordered = False

    def flush() -> None:
        nonlocal pending, pending_ordered
        if pending:
            out.append(BulletListBlock(items=tuple(pending), ordered=pending_ordered))
            pending = []

    for block in document.blocks:
        if block.is_empty:
            # An empty paragraph between two list items must not split the list, but an empty
            # paragraph anywhere else is the designer's own spacing and is simply dropped —
            # printing it would put a blank line into a government report for every stray return.
            continue

        if block.kind is BlockKind.TABLE:
            # A table ends any list it interrupts, exactly as a paragraph does.
            flush()
            table = _table_block(block)
            if table is not None:
                out.append(table)
            continue

        if block.kind is BlockKind.IMAGE:
            flush()
            # NO RESOLVER MEANS NO PICTURE, and silence is right. `to_report_blocks` is also called
            # from the preview projection and from tests, neither of which loads media; emitting a
            # placeholder there would print a broken box into a document. The caption is dropped
            # with it — a caption with no photograph above it reads as a lost paragraph.
            if resolve_media is None:
                continue
            ref = resolve_media(block.media)
            if ref is None:
                # The media row was deleted after the designer placed it. Same reasoning: a gap is
                # honest, a placeholder is not. The export warning channel reports the count.
                continue
            from app.services.report_model import ImageBlock

            out.append(ImageBlock(
                image=ref,
                width_pct=block.width_pct,
                align=block.align if block.align is not Align.LEFT else Align.CENTER,
                caption=block.text.strip(),
            ))
            continue

        runs = tuple(run for span in block.spans for run in _runs_for(span))
        if not runs:
            continue

        if block.kind.is_list_item:
            ordered = block.kind is BlockKind.ORDERED_ITEM
            if pending and ordered != pending_ordered:
                flush()
            pending_ordered = ordered
            pending.append(runs)
            continue

        flush()
        if block.kind is BlockKind.HEADING:
            from app.services.report_model import HeadingBlock, _bookmark_id

            text = block.text.strip()
            out.append(HeadingBlock(
                level=max(1, min(MAX_HEADING_LEVEL, block.level or 1)),
                runs=runs,
                number="",
                bookmark=_bookmark_id("", text, len(out)),
            ))
            continue

        out.append(ParagraphBlock(
            runs=runs,
            style=_STYLE_FOR_KIND.get(block.kind, base_style),
            align=block.align,
        ))

    flush()
    return out


def _table_block(block: RichBlock) -> Any:
    """One rich TABLE as the report model's own ``TableBlock``, or None if there is nothing to draw.

    THE FIRST ROW IS THE HEADER. That is the whole mapping: ``TableColumn`` carries a header string
    and a width, and ``TableBlock.rows`` carries only the body — so a rich table's first row
    becomes the columns and the rest becomes the rows. Reusing that block rather than inventing a
    second table shape is what keeps the .docx writer, both PDF writers and the browser preview
    drawing the same grid; a new block type would have needed four new drawing paths and four
    chances to disagree about a border.

    The widths are equal and computed to sum to EXACTLY 100. ``TableBlock.__post_init__`` refuses a
    set that does not, and it is right to: a renderer handed 99.99 draws a table fractionally
    narrow, while one handed 118 runs off the page in Word and is clipped in ReportLab. Dividing
    100 by three gives 33.33 three times and a sum of 99.99, so the last column takes the
    remainder rather than the quotient.
    """
    from app.services.report_model import TableBlock, TableColumn

    width = block.column_count
    if not block.rows or not width:
        return None

    def cell_runs(row: RichRow, index: int) -> tuple[Run, ...]:
        # Ragged rows are padded here rather than at parse time, so what the designer typed is
        # what is stored and only the drawing is squared off.
        cell = row[index] if index < len(row) else ()
        return tuple(run for span in cell for run in _runs_for(span))

    header = block.rows[0]
    body = block.rows[1:]

    each = round(100.0 / width, 2)
    widths = [each] * width
    widths[-1] = round(100.0 - each * (width - 1), 2)

    columns = tuple(
        TableColumn(
            header="".join(span.text for span in (header[i] if i < len(header) else ())).strip(),
            width_pct=widths[i],
        )
        for i in range(width)
    )
    return TableBlock(
        columns=columns,
        rows=tuple(tuple(cell_runs(row, i) for i in range(width)) for row in body),
    )


def media_ids(raw: Any) -> set[str]:
    """Every media id an IMAGE block in this document holds.

    Used by ``design_workshops._media_ids`` so the resolver loads the photographs a designer
    placed INSIDE their prose, not only the ones sitting in media-typed fields. Cheap and total:
    the document is parsed once and the ids come straight off the blocks, so a new place to put a
    picture cannot be forgotten here the way a hand-maintained list would be.
    """
    return {b.media for b in from_json(raw).blocks if b.kind is BlockKind.IMAGE and b.media}


def to_preview_json(raw: Any) -> list[dict[str, Any]]:
    """The document as the web preview renders it — the same blocks, marks resolved to booleans.

    Emitted alongside the report blocks so the browser never has to interpret the mark vocabulary
    itself, which is the one place a fifth renderer could start to drift.
    """
    document = from_json(raw)

    def span_json(span: RichSpan) -> dict[str, Any]:
        return {
            "text": span.text,
            "bold": span.bold,
            "italic": span.italic,
            "underline": span.underline,
            "strike": span.strike,
            "code": span.code,
            # Already resolved to single-valued here, exactly as `_runs_for` resolves it, so the
            # browser never has to know that SUPERSCRIPT beats SUBSCRIPT — which is the one place a
            # fifth renderer of the mark vocabulary could start to drift.
            "superscript": span.superscript,
            "subscript": span.subscript,
            "highlight": span.highlight,
        }

    out: list[dict[str, Any]] = []
    for block in document.blocks:
        entry: dict[str, Any] = {
            "kind": block.kind.value,
            "align": block.align.value,
            "level": block.level,
            "spans": [span_json(span) for span in block.spans],
        }
        if block.kind is BlockKind.TABLE:
            # The preview draws the grid from `rows`, with the first row as the header — the same
            # reading `_table_block` gives it. Sent even though `spans` is empty, because a table
            # whose content the browser cannot see would be a preview that disagrees with the file
            # about what is in the document, which is the one thing the preview must never do.
            entry["rows"] = [[[span_json(span) for span in cell] for cell in row]
                             for row in block.rows]
        if block.kind is BlockKind.IMAGE:
            # The id, not a URL. The browser mints its own media URLs (`useReportMediaUrls`), and
            # a URL baked in here would expire on a signed bucket while the document did not.
            entry["media"] = block.media
            entry["widthPct"] = block.width_pct
        out.append(entry)
    return out


def summary(raw: Any, limit: int = 160) -> str:
    """A one-line plain-text preview, for a list row or a collection's label field."""
    text = " ".join(to_plain(raw).split())
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def plain_runs(raw: Any) -> tuple[Run, ...]:
    """The whole document as a single run sequence, for somewhere a block cannot go.

    A table cell, for instance: a cell holds runs, not blocks, so a rich-text field rendered
    inside one keeps its marks and loses its paragraph structure to single spaces. Losing the
    structure is the right trade — the alternative is a nested table — but it is a loss, so this
    is deliberately a separate function rather than the default path.
    """
    document = from_json(raw)
    runs: list[Run] = []
    for index, block in enumerate(document.blocks):
        if block.is_empty:
            continue
        if runs:
            runs.append(Run(text=" "))
        if block.kind is BlockKind.BULLET_ITEM:
            runs.append(Run(text="• "))
        elif block.kind is BlockKind.ORDERED_ITEM:
            runs.append(Run(text=f"{index + 1}. "))
        runs.extend(run for span in block.spans for run in _runs_for(span))
    return tuple(runs) or runs_of("")
