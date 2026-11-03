from __future__ import annotations

from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from typing import Any

from app.services.report_builder import format_value
from app.services.stage_schema import (
    Cardinality,
    EntitySpec,
    FieldSpec,
    FieldType,
    StageSpec,
    stages,
)

CUSTOM_ENTITY_KEY = "_custom"


@dataclass(frozen=True)
class Column:
    key: str
    label: str
    unit: str = ""
    type: str = "TEXT"


@dataclass(frozen=True)
class Table:
    entity_key: str
    title: str
    stage_number: int
    stage_key: str
    stage_title: str
    collection: bool
    columns: tuple[Column, ...]


def _column(entity_key: str, spec: FieldSpec) -> Column:
    return Column(
        key=f"{entity_key}.{spec.key}",
        label=spec.label,
        unit=spec.unit,
        type=spec.type.name,
    )


def entity_columns(entity: EntitySpec) -> tuple[Column, ...]:
    return tuple(_column(entity.key, spec) for spec in entity.fields if not spec.deprecated)


def tables() -> tuple[Table, ...]:
    out: list[Table] = []
    for stage in stages():
        for entity in stage.entities:
            out.append(
                Table(
                    entity_key=entity.key,
                    title=entity.title,
                    stage_number=stage.number,
                    stage_key=stage.key,
                    stage_title=stage.title,
                    collection=entity.cardinality is Cardinality.COLLECTION,
                    columns=entity_columns(entity),
                )
            )
    return tuple(out)


def entity_by_key(entity_key: str) -> tuple[StageSpec, EntitySpec] | None:
    for stage in stages():
        for entity in stage.entities:
            if entity.key == entity_key:
                return stage, entity
    return None


WORKSHOP_IDENTITY_COLUMNS: tuple[Column, ...] = (
    Column(key="workshop.id", label="Workshop id"),
    Column(key="workshop.title", label="Workshop title"),
    Column(key="workshop.workshopCode", label="Workshop code"),
    Column(key="workshop.workshopKind", label="Type of workshop"),
    Column(key="workshop.scheme", label="Scheme"),
    Column(key="workshop.craftName", label="Craft"),
    Column(key="workshop.clusterName", label="Cluster"),
    Column(key="workshop.state", label="State"),
    Column(key="workshop.district", label="District"),
    Column(key="workshop.venue", label="Venue"),
    Column(key="workshop.startDate", label="Start date", type="DATE"),
    Column(key="workshop.endDate", label="End date", type="DATE"),
    Column(key="workshop.designerName", label="Designer"),
    Column(key="workshop.status", label="Status"),
)


def _iso_date(value: Any) -> str:
    if value is None:
        return ""
    date = getattr(value, "date", None)
    if callable(date):
        return date().isoformat()
    return str(value)


def workshop_identity(record: Any) -> dict[str, str]:
    return {
        "workshop.id": str(getattr(record, "id", "") or ""),
        "workshop.title": str(getattr(record, "title", "") or ""),
        "workshop.workshopCode": str(getattr(record, "workshopCode", "") or ""),
        "workshop.workshopKind": str(getattr(record, "workshopKind", "") or ""),
        "workshop.scheme": str(getattr(record, "scheme", "") or ""),
        "workshop.craftName": str(getattr(record, "craftName", "") or ""),
        "workshop.clusterName": str(getattr(record, "clusterName", "") or ""),
        "workshop.state": str(getattr(record, "state", "") or ""),
        "workshop.district": str(getattr(record, "district", "") or ""),
        "workshop.venue": str(getattr(record, "venue", "") or ""),
        "workshop.startDate": _iso_date(getattr(record, "startDate", None)),
        "workshop.endDate": _iso_date(getattr(record, "endDate", None)),
        "workshop.designerName": str(getattr(record, "designerName", "") or ""),
        "workshop.status": str(getattr(record, "status", "") or ""),
    }


def _media_cell(value: Any) -> str:
    if not value:
        return ""
    ids = value if isinstance(value, list) else [value]
    kept = [str(v) for v in ids if v]
    if not kept:
        return ""
    return f"{len(kept)}: " + ", ".join(kept)


def cell(spec: FieldSpec, value: Any) -> str:
    if spec.type.is_media:
        return _media_cell(value)
    return format_value(spec, value)


def entity_rows(
    entity: EntitySpec,
    entries: Iterable[Any],
    identity: dict[str, str],
) -> list[dict[str, str]]:
    out: list[dict[str, str]] = []
    live = [spec for spec in entity.fields if not spec.deprecated]
    for entry in entries:
        data = getattr(entry, "data", None) or {}
        if not isinstance(data, dict):
            continue
        row = dict(identity)
        row["entry.id"] = str(getattr(entry, "id", "") or "")
        row["entry.ordinal"] = str(getattr(entry, "ordinal", 0) or 0)
        for spec in live:
            row[f"{entity.key}.{spec.key}"] = cell(spec, data.get(spec.key))
        out.append(row)
    return out


@dataclass(frozen=True)
class CustomColumn:
    key: str
    label: str


def custom_columns(definition: Any) -> tuple[CustomColumn, ...]:
    out: list[CustomColumn] = []
    for section in getattr(definition, "sections", None) or []:
        for field in getattr(section, "fields", None) or []:
            key = str(getattr(field, "id", "") or "")
            if not key:
                continue
            label = str(getattr(field, "label", "") or key)
            if getattr(field, "retired", False):
                label = f"{label} (retired)"
            out.append(CustomColumn(key=f"{CUSTOM_ENTITY_KEY}.{key}", label=label))
    return tuple(out)


def custom_rows(
    definition: Any,
    entries: Iterable[Any],
    identity: dict[str, str],
) -> list[dict[str, str]]:
    columns = custom_columns(definition)
    out: list[dict[str, str]] = []
    for entry in entries:
        data = getattr(entry, "data", None) or {}
        if not isinstance(data, dict):
            continue
        row = dict(identity)
        row["entry.id"] = str(getattr(entry, "id", "") or "")
        row["entry.ordinal"] = str(getattr(entry, "ordinal", 0) or 0)
        for column in columns:
            raw = data.get(column.key.split(".", 1)[1])
            if raw is None:
                row[column.key] = ""
            elif isinstance(raw, list):
                row[column.key] = ", ".join(str(v) for v in raw)
            elif isinstance(raw, bool):
                row[column.key] = "Yes" if raw else "No"
            else:
                row[column.key] = str(raw)
        out.append(row)
    return out



SEARCHABLE_FIELD_TYPES: frozenset[FieldType] = frozenset(
    {
        FieldType.TEXT,
        FieldType.LONG_TEXT,
        FieldType.RICH_TEXT,
        FieldType.ENUM,
        FieldType.MULTI_ENUM,
        FieldType.TAGS,
        FieldType.URL,
    }
)

UNSEARCHABLE_FIELD_KEYS: frozenset[str] = frozenset(
    {"aadhaarNumber", "pehchanCardNumber", "artisanCardNo", "phone", "email"}
)

SEARCH_TEXT_JOIN = "\n"


def _searchable(spec: Any) -> bool:
    return (
        getattr(spec, "type", None) in SEARCHABLE_FIELD_TYPES
        and str(getattr(spec, "key", "")) not in UNSEARCHABLE_FIELD_KEYS
    )


def _joined(values: Iterable[str]) -> str:
    kept = [text for text in (value.strip() for value in values) if text]
    return SEARCH_TEXT_JOIN.join(dict.fromkeys(kept))


def entry_search_text(entity: EntitySpec, data: Any) -> str:
    values = data if isinstance(data, dict) else {}
    return _joined(
        cell(spec, values.get(spec.key)) for spec in entity.fields if _searchable(spec)
    )


def custom_search_text(specs: Iterable[Any], data: Any) -> str:
    values = data if isinstance(data, dict) else {}
    rendered: list[str] = []
    for spec in specs:
        if not _searchable(spec):
            continue
        raw = values.get(str(getattr(spec, "key", "")))
        if raw is None:
            continue
        label = getattr(spec, "option_label", None)
        if isinstance(raw, list):
            rendered.extend(str(label(v)) if callable(label) else str(v) for v in raw)
        else:
            rendered.append(str(label(raw)) if callable(label) else str(raw))
    return _joined(rendered)


def stage_label(stage_key: str) -> str:
    return next(
        (f"Stage {spec.number}: {spec.title}" for spec in stages() if spec.key == stage_key),
        stage_key,
    )


UNKNOWN_STAGE_ORDER = 99


def stage_order(stage_key: str) -> int:
    return next(
        (spec.number for spec in stages() if spec.key == stage_key), UNKNOWN_STAGE_ORDER
    )


@dataclass(frozen=True)
class UnknownEntity:
    entity_key: str
    rows: int


def flatten(
    record: Any,
    entries: Sequence[Any],
    definition: Any = None,
) -> tuple[dict[str, list[dict[str, str]]], tuple[UnknownEntity, ...]]:
    identity = workshop_identity(record)
    grouped: dict[str, list[Any]] = {}
    for entry in entries:
        key = str(getattr(entry, "entityKey", "") or "")
        if not key:
            continue
        grouped.setdefault(key, []).append(entry)

    out: dict[str, list[dict[str, str]]] = {}
    unknown: list[UnknownEntity] = []
    for key, rows in grouped.items():
        if key == CUSTOM_ENTITY_KEY:
            if definition is not None:
                out[key] = custom_rows(definition, rows, identity)
            else:
                unknown.append(UnknownEntity(entity_key=key, rows=len(rows)))
            continue
        found = entity_by_key(key)
        if found is None:
            unknown.append(UnknownEntity(entity_key=key, rows=len(rows)))
            continue
        _stage, entity = found
        out[key] = entity_rows(entity, rows, identity)
    return out, tuple(unknown)



@dataclass(frozen=True)
class MediaAttribution:
    workshop_id: str
    workshop_title: str
    workshop_code: str
    stage_number: int
    stage_key: str
    stage_title: str
    entity_key: str
    entity_title: str
    ordinal: int
    field_key: str
    field_label: str

    @property
    def label(self) -> str:
        parts = [f"Stage {self.stage_number}", self.entity_title]
        if self.ordinal:
            parts.append(f"#{self.ordinal + 1}")
        parts.append(self.field_label)
        return " / ".join(parts)


def _media_ids(value: Any) -> list[str]:
    if not value:
        return []
    raw = value if isinstance(value, list) else [value]
    return [str(v) for v in raw if v]


def _stage_number_of(entity_key: str) -> int:
    found = entity_by_key(entity_key)
    return found[0].number if found is not None else 10_000


def media_attributions(record: Any, entries: Iterable[Any]) -> dict[str, MediaAttribution]:
    identity = workshop_identity(record)
    workshop_id = identity["workshop.id"]
    workshop_title = identity["workshop.title"]
    workshop_code = identity["workshop.workshopCode"]

    out: dict[str, MediaAttribution] = {}
    ordered = sorted(
        entries,
        key=lambda entry: _stage_number_of(str(getattr(entry, "entityKey", "") or "")),
    )
    for entry in ordered:
        found = entity_by_key(str(getattr(entry, "entityKey", "") or ""))
        if found is None:
            continue
        stage, entity = found
        data = getattr(entry, "data", None) or {}
        if not isinstance(data, dict):
            continue
        ordinal = int(getattr(entry, "ordinal", 0) or 0)
        for spec in entity.fields:
            if not spec.type.is_media:
                continue
            for media_id in _media_ids(data.get(spec.key)):
                if media_id in out:
                    continue
                out[media_id] = MediaAttribution(
                    workshop_id=workshop_id,
                    workshop_title=workshop_title,
                    workshop_code=workshop_code,
                    stage_number=stage.number,
                    stage_key=stage.key,
                    stage_title=stage.title,
                    entity_key=entity.key,
                    entity_title=entity.title,
                    ordinal=ordinal,
                    field_key=spec.key,
                    field_label=spec.label,
                )
    return out


MAX_ENTITY_SHEETS = 16


@dataclass(frozen=True)
class SheetPlan:
    included: tuple[str, ...]
    omitted: tuple[str, ...]
    empty: tuple[str, ...]

    @property
    def truncated(self) -> bool:
        return bool(self.omitted)


def sheet_plan(counts: dict[str, int], limit: int = MAX_ENTITY_SHEETS) -> SheetPlan:
    registry_order = [table.entity_key for table in tables()]
    position = {key: index for index, key in enumerate(registry_order)}
    with_rows = [key for key in registry_order if counts.get(key, 0) > 0]
    empty = tuple(key for key in registry_order if counts.get(key, 0) <= 0)
    kept = set(sorted(with_rows, key=lambda key: (-counts[key], position[key]))[:limit])
    return SheetPlan(
        included=tuple(key for key in with_rows if key in kept),
        omitted=tuple(key for key in with_rows if key not in kept),
        empty=empty,
    )
