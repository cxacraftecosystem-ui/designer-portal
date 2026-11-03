from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from app.core.db import db
from app.services.stage_schema import (
    REFERENCE_HYDRATION,
    FieldType,
    StageSpec,
    stage,
)

COPYABLE_STAGES: frozenset[str] = frozenset(
    {
        "INTRODUCTORY_ADMIN_DOCUMENTATION",
        "CLUSTER_CRAFT_BACKGROUND",
        "TRADITIONAL_PROCESS_BASELINE",
        "SURVEY_PLANNING",
    }
)

SOURCE_COPIED = "copied"

_TEXT_TYPES = frozenset({FieldType.TEXT, FieldType.LONG_TEXT, FieldType.RICH_TEXT, FieldType.TAGS})

OWN_SOURCES_TAKE = 6
REFERENCE_TAKE = 3

_REFERENCE_STATUSES = ("COMPLETE", "SUBMITTED", "APPROVED", "ARCHIVED")


def _singleton(spec: StageSpec) -> Any | None:
    return spec.singleton


def copyable_fields(spec: StageSpec) -> tuple[Any, ...]:
    entity = _singleton(spec)
    if entity is None or spec.key not in COPYABLE_STAGES:
        return ()
    hydrated = {
        target
        for path, mapping in REFERENCE_HYDRATION.items()
        if path.partition(".")[0] == entity.key
        for target in mapping.values()
    }
    return tuple(
        field
        for field in entity.fields
        if field.type in _TEXT_TYPES
        and not field.deprecated
        and not getattr(field, "derived_kind", "")
        and field.key not in hydrated
    )


def _filled(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, (list, dict)):
        return bool(value)
    return True


def _authored_by(row: Any, key: str, user_id: str, creator_id: str | None) -> bool:
    provenance = getattr(row, "fieldProvenance", None)
    stamp = provenance.get(key) if isinstance(provenance, dict) else None
    if isinstance(stamp, dict):
        return stamp.get("by") == user_id and stamp.get("source") != SOURCE_COPIED
    return creator_id == user_id


async def _singleton_rows(spec: StageSpec, where: dict[str, Any], take: int) -> list[Any]:
    entity = _singleton(spec)
    if entity is None:
        return []
    return await db.dwstageentry.find_many(
        where={
            "stageKey": spec.key,
            "entityKey": entity.key,
            "deletedAt": None,
            "designWorkshop": {"is": where},
        },
        include={"designWorkshop": True},
        order={"updatedAt": "desc"},
        take=take,
    )


async def copy_sources(workshop: Any, spec: StageSpec, user: Any) -> dict[str, Any]:
    fields = copyable_fields(spec)
    keys = [field.key for field in fields]
    user_id = str(getattr(user, "id", "") or "")
    if not keys:
        return {"fields": [], "own": [], "reference": []}

    own_where = {
        "deletedAt": None,
        "id": {"not": workshop.id},
        "OR": [{"createdById": user_id}, {"viewers": {"some": {"userId": user_id}}}],
    }
    own_rows = await _singleton_rows(spec, own_where, OWN_SOURCES_TAKE * 3)
    own: list[dict[str, Any]] = []
    seen: set[str] = set()
    for row in own_rows:
        source = row.designWorkshop
        if source is None or source.id in seen:
            continue
        data = row.data if isinstance(row.data, dict) else {}
        values = {
            key: data[key]
            for key in keys
            if _filled(data.get(key)) and _authored_by(row, key, user_id, source.createdById)
        }
        if not values:
            continue
        seen.add(source.id)
        own.append(_source_payload(source, values))
        if len(own) >= OWN_SOURCES_TAKE:
            break

    reference: list[dict[str, Any]] = []
    place = [
        clause
        for clause in (
            {"clusterName": {"equals": workshop.clusterName, "mode": "insensitive"}}
            if getattr(workshop, "clusterName", None)
            else None,
            {"craftName": {"equals": workshop.craftName, "mode": "insensitive"}}
            if getattr(workshop, "craftName", None)
            else None,
        )
        if clause
    ]
    if place:
        reference_where = {
            "deletedAt": None,
            "id": {"not_in": [workshop.id, *seen]},
            "status": {"in": list(_REFERENCE_STATUSES)},
            "OR": place,
            "NOT": [{"createdById": user_id}, {"viewers": {"some": {"userId": user_id}}}],
        }
        for row in await _singleton_rows(spec, reference_where, REFERENCE_TAKE):
            data = row.data if isinstance(row.data, dict) else {}
            values = {key: data[key] for key in keys if _filled(data.get(key))}
            if values and row.designWorkshop is not None:
                reference.append(_source_payload(row.designWorkshop, values, with_designer=True))

    return {
        "fields": [
            {"key": field.key, "label": field.label, "type": field.type.value} for field in fields
        ],
        "own": own,
        "reference": reference,
    }


def _source_payload(
    source: Any, values: dict[str, Any], *, with_designer: bool = False
) -> dict[str, Any]:
    end = getattr(source, "endDate", None) or getattr(source, "startDate", None)
    payload = {
        "workshopId": source.id,
        "title": source.title,
        "craftName": source.craftName,
        "clusterName": source.clusterName,
        "date": end.date().isoformat() if isinstance(end, datetime) else None,
        "values": values,
    }
    if with_designer:
        payload["designerName"] = getattr(source, "designerName", None)
    return payload


async def verified_copies(
    copied_from: dict[str, str], spec: StageSpec, data: dict[str, Any], workshop_id: str, user: Any
) -> dict[str, dict[str, Any]]:
    allowed = {field.key for field in copyable_fields(spec)}
    wanted = {
        key: source
        for key, source in copied_from.items()
        if key in allowed and source != workshop_id
    }
    if not wanted:
        return {}
    user_id = str(getattr(user, "id", "") or "")
    entity = _singleton(spec)
    rows = await db.dwstageentry.find_many(
        where={
            "stageKey": spec.key,
            "entityKey": entity.key,
            "deletedAt": None,
            "designWorkshopId": {"in": sorted(set(wanted.values()))},
            "designWorkshop": {
                "is": {
                    "deletedAt": None,
                    "OR": [{"createdById": user_id}, {"viewers": {"some": {"userId": user_id}}}],
                }
            },
        },
        include={"designWorkshop": True},
    )
    by_source = {row.designWorkshopId: row for row in rows}
    out: dict[str, dict[str, Any]] = {}
    for key, source_id in wanted.items():
        row = by_source.get(source_id)
        if row is None or row.designWorkshop is None:
            continue
        source_data = row.data if isinstance(row.data, dict) else {}
        if not _filled(data.get(key)) or source_data.get(key) != data.get(key):
            continue
        if not _authored_by(row, key, user_id, row.designWorkshop.createdById):
            continue
        out[key] = {"workshopId": source_id, "title": row.designWorkshop.title}
    return out


def mark_provenance(
    provenance: dict[str, Any],
    copied: dict[str, dict[str, Any]],
    confirm: list[str],
    user: Any,
    now: datetime | None = None,
) -> dict[str, Any]:
    at = (now or datetime.now(UTC)).isoformat()
    out = dict(provenance)
    for key, source in copied.items():
        held = out.get(key)
        kept_from = held.get("confirmedCopyOf") if isinstance(held, dict) else None
        if isinstance(kept_from, dict) and kept_from.get("workshopId") == source.get("workshopId"):
            continue
        out[key] = {
            "by": getattr(user, "id", None),
            "byName": getattr(user, "name", None),
            "at": at,
            "source": SOURCE_COPIED,
            "copiedFrom": source,
        }
    for key in confirm:
        stamp = out.get(key)
        if isinstance(stamp, dict) and stamp.get("source") == SOURCE_COPIED:
            out[key] = {
                "by": getattr(user, "id", None),
                "byName": getattr(user, "name", None),
                "at": at,
                "source": "designer",
                "confirmedCopyOf": stamp.get("copiedFrom"),
            }
    return out


def unconfirmed_copies(provenance: Any) -> set[str]:
    if not isinstance(provenance, dict):
        return set()
    return {
        key
        for key, stamp in provenance.items()
        if isinstance(stamp, dict) and stamp.get("source") == SOURCE_COPIED
    }


def counted_data(
    stage_key: str, entity_key: str, data: dict[str, Any], provenance: Any
) -> dict[str, Any]:
    if stage_key not in COPYABLE_STAGES:
        return data
    spec = stage(stage_key)
    if spec.singleton is None or spec.singleton.key != entity_key:
        return data
    hidden = unconfirmed_copies(provenance)
    if not hidden:
        return data
    return {key: value for key, value in data.items() if key not in hidden}


__all__ = [
    "COPYABLE_STAGES",
    "SOURCE_COPIED",
    "copy_sources",
    "copyable_fields",
    "counted_data",
    "mark_provenance",
    "unconfirmed_copies",
    "verified_copies",
]
