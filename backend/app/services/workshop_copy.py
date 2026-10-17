"""Start a stage from an earlier workshop (owner decision C9, 2026-10-17).

Stages 2, 4, 5 and 7 start empty, so a cluster's history and the boilerplate are rewritten for every
workshop. The owner ruled:

* a designer may COPY from their OWN earlier workshops, and the source is marked;
* other designers' text appears only as a READ-ONLY reference block (the registry's rule that two
  authors never share one box);
* copied text that has not been edited does NOT count toward completeness until the designer
  edits it or confirms it.

HOW, IN THREE PIECES, AND NONE OF THEM A NEW WRITE PATH.

1. :func:`copy_sources` answers what may be copied — the caller's own text, field by field, and
   the read-only reference text — for the form to show.
2. The form puts the chosen text in the boxes and saves through ``save_stage`` as always, naming
   the copied keys in ``StageSaveIn.copiedFrom``. :func:`verified_copies` re-reads the source and
   keeps only keys whose value really is the caller's own text from that source, and
   :func:`mark_provenance` stamps those fields ``source: "copied"`` in ``fieldProvenance``.
   ``entry_provenance.merge_entry_provenance`` carries a stamp forward while the value is unchanged
   and replaces it with the editor's own the moment the value changes — so EDITING clears the mark
   with no code here at all. ``StageSaveIn.confirmCopied`` turns the mark into the designer's own
   without an edit ("Keep as is").
3. :func:`counted_data` hides an unconfirmed copied field from the scorer, and
   ``design_workshops.workshop_completeness`` calls it. The report still prints the text; only the
   score waits for the designer.
"""

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

#: The stages a designer may start from an earlier workshop — the narrative ones the owner named.
COPYABLE_STAGES: frozenset[str] = frozenset(
    {
        "INTRODUCTORY_ADMIN_DOCUMENTATION",
        "CLUSTER_CRAFT_BACKGROUND",
        "TRADITIONAL_PROCESS_BASELINE",
        "SURVEY_PLANNING",
    }
)

#: The provenance ``source`` a copied, not-yet-checked field carries.
SOURCE_COPIED = "copied"

_TEXT_TYPES = frozenset({FieldType.TEXT, FieldType.LONG_TEXT, FieldType.RICH_TEXT, FieldType.TAGS})

#: How many of the caller's own earlier workshops, and how many reference blocks, one read offers.
OWN_SOURCES_TAKE = 6
REFERENCE_TAKE = 3

#: The finished statuses a reference block is drawn from: text somebody handed in, not a draft.
_REFERENCE_STATUSES = ("COMPLETE", "SUBMITTED", "APPROVED", "ARCHIVED")


def _singleton(spec: StageSpec) -> Any | None:
    return spec.singleton


def copyable_fields(spec: StageSpec) -> tuple[Any, ...]:
    """The singleton's free-text fields a designer writes — never a hydrated, derived or media one."""
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
    """Did ``user_id`` write this field? The stamp says; an unstamped field is the creator's."""
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
    """What the caller may copy into this stage, and the read-only reference beside it."""
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
    """The keys of ``copied_from`` that really are the caller's own text from that source.

    A claim is dropped — silently, the field is then simply the caller's — when the stage is not
    copyable, the key is not a copyable field, the source is this workshop or not the caller's, or
    the value saved is not byte-for-byte the source's value. So a client cannot mark an arbitrary
    answer "copied", and a copy edited before its first save is the designer's own from the start.
    """
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
    """Stamp the verified copies, and turn confirmed ones into the designer's own. Pure."""
    at = (now or datetime.now(UTC)).isoformat()
    out = dict(provenance)
    for key, source in copied.items():
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
    """The fields whose stamp still says "copied, not checked"."""
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
    """The singleton as the SCORER should see it: an unconfirmed copied field is not yet an answer."""
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
