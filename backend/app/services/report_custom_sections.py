from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field as dataclass_field
from typing import Any

from app.services.report_model import (
    Align,
    Block,
    DocumentBuilder,
    KeyValueBlock,
    PageBreakBlock,
    ParagraphBlock,
    ParaStyle,
    ReportMeta,
    clean_text,
    runs_of,
)

NOT_RECORDED = "Not recorded."

RETIRED_NOTE = "no longer asked"

MAX_ROWS_PER_SECTION = 200

_TIER_RANK: dict[str, int] = {"BASIC": 0, "STANDARD": 1, "ADVANCED": 2}

ALL_TIERS = 2


@dataclass(frozen=True, slots=True)
class CustomReportField:
    key: str
    label: str
    type: str = "TEXT"
    unit: str = ""
    options: tuple[tuple[str, str], ...] = ()
    required: bool = False
    retired: bool = False
    tier: str = "BASIC"

    @property
    def tier_rank(self) -> int:
        return _TIER_RANK.get(str(self.tier).upper(), 0)

    def within(self, max_tier_rank: int) -> bool:
        return self.tier_rank <= max_tier_rank

    def option_label(self, value: Any) -> str:
        token = str(value)
        return next((label or token for value_, label in self.options if value_ == token), token)


@dataclass(frozen=True, slots=True)
class CustomSectionItem:
    key: str
    title: str
    stage_key: str = ""
    description: str = ""
    sort_order: int = 0
    fields: tuple[CustomReportField, ...] = ()
    values: Mapping[str, Any] = dataclass_field(default_factory=dict)

    def answer(self, field: CustomReportField) -> Any:
        return self.values.get(field.key)

    def fields_at(self, max_tier_rank: int = ALL_TIERS) -> tuple[CustomReportField, ...]:
        return tuple(f for f in self.fields if f.within(max_tier_rank))

    def printed_fields_at(self, max_tier_rank: int = ALL_TIERS) -> tuple[CustomReportField, ...]:
        return tuple(
            f
            for f in self.fields_at(max_tier_rank)
            if not f.retired or _has_answer(self.values.get(f.key))
        )

    @property
    def printed_fields(self) -> tuple[CustomReportField, ...]:
        return self.printed_fields_at()

    def has_content_at(self, max_tier_rank: int = ALL_TIERS) -> bool:
        return any(
            _has_answer(self.values.get(f.key)) or (f.required and not f.retired)
            for f in self.fields_at(max_tier_rank)
        )

    @property
    def has_content(self) -> bool:
        return self.has_content_at()

    def answered_count_at(self, max_tier_rank: int = ALL_TIERS) -> int:
        return sum(1 for f in self.fields_at(max_tier_rank) if _has_answer(self.values.get(f.key)))

    @property
    def answered_count(self) -> int:
        return self.answered_count_at()


def _has_answer(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, (list, tuple, dict)):
        return bool(value)
    return True


_ATTR = "custom_sections"


def attach_custom_sections(
    data: Any, items: Sequence[CustomSectionItem] | tuple[CustomSectionItem, ...]
) -> Any:
    try:
        setattr(data, _ATTR, tuple(items))
    except AttributeError:
        return data
    return data


def custom_sections_of(data: Any) -> tuple[CustomSectionItem, ...]:
    return tuple(getattr(data, _ATTR, ()) or ())


def custom_scoring(
    data: Any, stage_key: str
) -> tuple[tuple[CustomReportField, ...], dict[str, Any]]:
    fields: list[CustomReportField] = []
    values: dict[str, Any] = {}
    for item in sorted(
        (i for i in custom_sections_of(data) if i.stage_key == stage_key),
        key=lambda i: (i.sort_order, i.key),
    ):
        fields.extend(item.fields)
        values.update(item.values)
    return tuple(fields), values


def section_prints(item: CustomSectionItem | None, max_tier_rank: int = ALL_TIERS) -> bool:
    return item is not None and item.has_content_at(max_tier_rank)


def sections_hidden_by_tier(
    items: Sequence[CustomSectionItem | None], max_tier_rank: int
) -> tuple[CustomSectionItem, ...]:
    return tuple(
        item
        for item in items
        if section_prints(item, ALL_TIERS) and not section_prints(item, max_tier_rank)
    )


def custom_section_of(data: Any, key: str) -> CustomSectionItem | None:
    return next((item for item in custom_sections_of(data) if item.key == key), None)



def display_value(field: CustomReportField, value: Any) -> str:
    if value is None or value == "" or value == []:
        return ""
    if field.type == "ENUM":
        return field.option_label(value)
    if field.type == "MULTI_ENUM":
        if isinstance(value, (list, tuple)):
            return ", ".join(field.option_label(v) for v in value)
        return field.option_label(value)

    from app.services.report_builder import format_value
    from app.services.stage_schema import FieldSpec, FieldType

    try:
        field_type = FieldType(field.type)
    except ValueError:
        return clean_text(value)
    return format_value(
        FieldSpec(key=field.key, label=field.label, type=field_type, unit=field.unit), value
    )


def _label_runs(field: CustomReportField) -> str:
    if field.retired:
        return f"{field.label} ({RETIRED_NOTE})"
    return field.label


def custom_section_blocks(
    item: CustomSectionItem, *, max_tier_rank: int = ALL_TIERS
) -> list[Block]:
    pairs: list[tuple[str, Any]] = []
    prose: list[tuple[str, str]] = []
    printed = 0
    truncated = False

    for field in item.printed_fields_at(max_tier_rank):
        if printed >= MAX_ROWS_PER_SECTION:
            truncated = True
            break
        printed += 1
        raw = item.answer(field)
        text = display_value(field, raw)
        if field.type == "LONG_TEXT":
            if text.strip():
                prose.append((_label_runs(field), text))
            elif field.required and not field.retired:
                pairs.append((_label_runs(field), NOT_RECORDED))
            continue
        if text.strip():
            pairs.append((_label_runs(field), text))
        elif field.required and not field.retired:
            pairs.append((_label_runs(field), NOT_RECORDED))

    blocks: list[Block] = []
    if pairs:
        blocks.append(
            KeyValueBlock(
                pairs=tuple((clean_text(label), runs_of(value)) for label, value in pairs)
            )
        )
    for label, text in prose:
        blocks.append(
            ParagraphBlock(runs=runs_of(label, bold=True), style=ParaStyle.BODY, align=Align.LEFT)
        )
        for chunk in (c for c in clean_text(text).split("\n\n") if c.strip()):
            blocks.append(ParagraphBlock(runs=runs_of(chunk.strip()), style=ParaStyle.BODY))
    if truncated:
        blocks.append(
            ParagraphBlock(
                runs=runs_of(
                    f"[Answers truncated after {MAX_ROWS_PER_SECTION} questions. The full set is in "
                    f"the online workshop record.]"
                ),
                style=ParaStyle.NOTE,
            )
        )
    return blocks


def append_custom_section(
    doc: DocumentBuilder,
    item: CustomSectionItem | None,
    *,
    heading: str = "",
    numbered: bool = True,
    page_break_before: bool = False,
    max_tier_rank: int = ALL_TIERS,
) -> int:
    if item is None or not section_prints(item, max_tier_rank):
        return 0
    if page_break_before:
        doc.add(PageBreakBlock())
    doc.heading(heading or item.title, 1, numbered=numbered)
    if item.description.strip():
        doc.para(item.description, style=ParaStyle.LEAD)
    for block in custom_section_blocks(item, max_tier_rank=max_tier_rank):
        doc.add(block)
    return item.answered_count_at(max_tier_rank)


def custom_section_blocks_standalone(
    item: CustomSectionItem, *, heading: str = "", numbered: bool = True
) -> tuple[Block, ...]:
    scratch = DocumentBuilder(meta=ReportMeta(title=""))
    append_custom_section(
        scratch, item, heading=heading, numbered=numbered, page_break_before=False
    )
    return scratch.build().blocks


__all__ = [
    "ALL_TIERS",
    "MAX_ROWS_PER_SECTION",
    "NOT_RECORDED",
    "RETIRED_NOTE",
    "CustomReportField",
    "CustomSectionItem",
    "append_custom_section",
    "attach_custom_sections",
    "custom_scoring",
    "custom_section_blocks",
    "custom_section_blocks_standalone",
    "custom_section_of",
    "custom_sections_of",
    "display_value",
    "section_prints",
    "sections_hidden_by_tier",
]
