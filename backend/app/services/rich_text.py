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

MAX_DOCUMENT_CHARS = 200_000
MAX_BLOCKS = 2_000
MAX_HEADING_LEVEL = 4
MAX_LIST_DEPTH = 3


class Mark(str, Enum):
    BOLD = "BOLD"
    ITALIC = "ITALIC"
    UNDERLINE = "UNDERLINE"
    STRIKETHROUGH = "STRIKETHROUGH"
    CODE = "CODE"
    SUPERSCRIPT = "SUPERSCRIPT"
    SUBSCRIPT = "SUBSCRIPT"
    HIGHLIGHT = "HIGHLIGHT"


class BlockKind(str, Enum):
    PARAGRAPH = "PARAGRAPH"
    HEADING = "HEADING"
    BULLET_ITEM = "BULLET_ITEM"
    ORDERED_ITEM = "ORDERED_ITEM"
    QUOTE = "QUOTE"
    TABLE = "TABLE"
    IMAGE = "IMAGE"

    @property
    def is_list_item(self) -> bool:
        return self in {BlockKind.BULLET_ITEM, BlockKind.ORDERED_ITEM}


@dataclass(frozen=True, slots=True)
class RichSpan:
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
        return Mark.SUBSCRIPT in self.marks and Mark.SUPERSCRIPT not in self.marks

    @property
    def highlight(self) -> bool:
        return Mark.HIGHLIGHT in self.marks


RichCell = tuple[RichSpan, ...]

RichRow = tuple[RichCell, ...]

MAX_TABLE_ROWS = 200
MAX_TABLE_COLUMNS = 12


@dataclass(frozen=True, slots=True)
class RichBlock:
    kind: BlockKind = BlockKind.PARAGRAPH
    spans: tuple[RichSpan, ...] = ()
    align: Align = Align.LEFT
    level: int = 0
    rows: tuple[RichRow, ...] = ()
    media: str = ""
    width_pct: float = 70.0

    @property
    def text(self) -> str:
        if self.kind is BlockKind.TABLE:
            return "\n".join(
                "\t".join("".join(span.text for span in cell) for cell in row) for row in self.rows
            )
        return "".join(span.text for span in self.spans)

    @property
    def is_empty(self) -> bool:
        if self.kind is BlockKind.IMAGE:
            return not self.media
        return not self.text.strip()

    @property
    def column_count(self) -> int:
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



def _coerce_marks(raw: Any) -> frozenset[Mark]:
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
            if not media:
                continue
            raw_width = entry.get("widthPct")
            if isinstance(raw_width, (int, float)) and not isinstance(raw_width, bool):
                width_pct = max(10.0, min(100.0, float(raw_width)))

        rows: tuple[RichRow, ...] = ()
        if kind is BlockKind.TABLE:
            rows, budget = _coerce_rows(entry.get("rows"), budget)
            if not rows:
                continue
        blocks.append(
            RichBlock(
                kind=kind,
                spans=tuple(spans),
                align=_coerce_align(entry.get("align")),
                level=level,
                rows=rows,
                media=media,
                width_pct=width_pct,
            )
        )
        if budget <= 0:
            break
    return RichDoc(blocks=tuple(blocks))


def _coerce_rows(raw: Any, budget: int) -> tuple[tuple[RichRow, ...], int]:
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
    while rows and not any(any(s.text.strip() for s in cell) for cell in rows[-1]):
        rows.pop()
    return tuple(rows), budget


def from_plain(text: Any) -> RichDoc:
    cleaned = clean_text(text)[:MAX_DOCUMENT_CHARS]
    if not cleaned.strip():
        return EMPTY
    blocks = [
        RichBlock(spans=(RichSpan(line.strip()),)) for line in cleaned.split("\n") if line.strip()
    ]
    return RichDoc(blocks=tuple(blocks[:MAX_BLOCKS]))



def to_json(doc: RichDoc) -> dict[str, Any]:
    def span_json(span: RichSpan) -> dict[str, Any]:
        return (
            {"text": span.text, "marks": sorted(m.value for m in span.marks)}
            if span.marks
            else {"text": span.text}
        )

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
            entry["rows"] = [
                [[span_json(span) for span in cell] for cell in row] for row in block.rows
            ]
        if block.kind is BlockKind.IMAGE:
            entry["media"] = block.media
            if block.width_pct != 70.0:
                entry["widthPct"] = block.width_pct
        blocks.append(entry)
    return {"blocks": blocks}


def to_plain(doc: Any) -> str:
    document = doc if isinstance(doc, RichDoc) else from_json(doc)
    lines: list[str] = []
    ordinal = 0
    for block in document.blocks:
        if block.kind is BlockKind.IMAGE:
            ordinal = 0
            caption = block.text.strip()
            if caption:
                lines.append(caption)
            continue
        if block.kind is BlockKind.TABLE:
            ordinal = 0
            for row in block.rows:
                lines.append(
                    " | ".join("".join(span.text for span in cell).strip() for cell in row)
                )
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
    if raw is None:
        return True
    if isinstance(raw, str):
        return not raw.strip()
    return from_json(raw).is_empty



def _document_from_payload(payload: str) -> RichDoc | None:
    try:
        parsed = json.loads(payload)
    except (ValueError, TypeError):
        return None
    if not isinstance(parsed, dict) or not isinstance(parsed.get("blocks"), list):
        return None
    return from_json(parsed)


def stored_text_document(value: str) -> RichDoc | None:
    stripped = value.strip()
    if not stripped.startswith("{") or not stripped.endswith("}"):
        return None
    return _document_from_payload(stripped)


def is_stored_rich_text(value: Any) -> bool:
    if isinstance(value, (dict, list, RichDoc)):
        return True
    return isinstance(value, str) and stored_text_document(value) is not None


def from_stored_text(value: Any) -> RichDoc:
    if value is None:
        return EMPTY
    if isinstance(value, str):
        document = stored_text_document(value)
        return document if document is not None else from_plain(value)
    return from_json(value)


def _is_unformatted(document: RichDoc) -> bool:
    for block in document.blocks:
        if block.kind is not BlockKind.PARAGRAPH:
            return False
        if block.align is not Align.LEFT or block.level or block.rows or block.media:
            return False
        if any(span.marks for span in block.spans):
            return False
    return True


def to_stored_text(doc: Any) -> str | None:
    document = doc if isinstance(doc, RichDoc) else from_json(doc)
    if document.is_empty:
        return None
    if _is_unformatted(document):
        return to_plain(document) or None
    return json.dumps(to_json(document), ensure_ascii=False, separators=(",", ":"))


def plain_from_stored(value: Any) -> Any:
    if isinstance(value, str):
        document = stored_text_document(value)
        return value if document is None else to_plain(document)
    if isinstance(value, (dict, list, RichDoc)):
        return to_plain(value)
    return value


def search_needles(term: str) -> tuple[str, ...]:
    escaped = json.dumps(term, ensure_ascii=False)[1:-1]
    return (term,) if escaped == term else (term, escaped)


_STYLE_FOR_KIND = {
    BlockKind.PARAGRAPH: ParaStyle.BODY,
    BlockKind.QUOTE: ParaStyle.QUOTE,
}


def _runs_for(span: RichSpan) -> list[Run]:
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


def to_report_blocks(
    raw: Any, *, base_style: ParaStyle = ParaStyle.BODY, resolve_media: Any = None
) -> list[Any]:
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
            continue

        if block.kind is BlockKind.TABLE:
            flush()
            table = _table_block(block)
            if table is not None:
                out.append(table)
            continue

        if block.kind is BlockKind.IMAGE:
            flush()
            if resolve_media is None:
                continue
            ref = resolve_media(block.media)
            if ref is None:
                continue
            from app.services.report_model import ImageBlock

            out.append(
                ImageBlock(
                    image=ref,
                    width_pct=block.width_pct,
                    align=block.align if block.align is not Align.LEFT else Align.CENTER,
                    caption=block.text.strip(),
                )
            )
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
            out.append(
                HeadingBlock(
                    level=max(1, min(MAX_HEADING_LEVEL, block.level or 1)),
                    runs=runs,
                    number="",
                    bookmark=_bookmark_id("", text, len(out)),
                )
            )
            continue

        out.append(
            ParagraphBlock(
                runs=runs,
                style=_STYLE_FOR_KIND.get(block.kind, base_style),
                align=block.align,
            )
        )

    flush()
    return out


def _table_block(block: RichBlock) -> Any:
    from app.services.report_model import TableBlock, TableColumn

    width = block.column_count
    if not block.rows or not width:
        return None

    def cell_runs(row: RichRow, index: int) -> tuple[Run, ...]:
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
    return {b.media for b in from_json(raw).blocks if b.kind is BlockKind.IMAGE and b.media}


def to_preview_json(raw: Any) -> list[dict[str, Any]]:
    document = from_json(raw)

    def span_json(span: RichSpan) -> dict[str, Any]:
        return {
            "text": span.text,
            "bold": span.bold,
            "italic": span.italic,
            "underline": span.underline,
            "strike": span.strike,
            "code": span.code,
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
            entry["rows"] = [
                [[span_json(span) for span in cell] for cell in row] for row in block.rows
            ]
        if block.kind is BlockKind.IMAGE:
            entry["media"] = block.media
            entry["widthPct"] = block.width_pct
        out.append(entry)
    return out


def summary(raw: Any, limit: int = 160) -> str:
    text = " ".join(to_plain(raw).split())
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def plain_runs(raw: Any) -> tuple[Run, ...]:
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
