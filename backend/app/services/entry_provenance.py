from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

SOURCE_REFERENCE = "reference"

SOURCE_DESIGNER = "designer"


@dataclass(frozen=True, slots=True)
class HydrationSource:
    model: str
    record_id: str
    source_key: str
    author_id: str | None


def merge_entry_provenance(
    *,
    previous: Mapping[str, Any] | None,
    previous_data: Mapping[str, Any] | None,
    new_data: Mapping[str, Any],
    hydrated: Mapping[str, HydrationSource] | None,
    user: Any,
    now: datetime | None = None,
) -> dict[str, Any]:
    from app.core.deps import get_value, is_empty_value, values_match

    carried_in: Mapping[str, Any] = previous or {}
    before: Mapping[str, Any] = previous_data or {}
    written: Mapping[str, HydrationSource] = hydrated or {}
    at = (now or datetime.now(UTC)).isoformat()

    designer_stamp = {
        "by": get_value(user, "id"),
        "byName": get_value(user, "name"),
        "at": at,
        "source": SOURCE_DESIGNER,
    }

    out: dict[str, Any] = {}
    for key, value in new_data.items():
        if key.startswith("_") or is_empty_value(value):
            continue

        source = written.get(key)
        if source is not None:
            out[key] = {
                "by": source.author_id,
                "byName": None,
                "at": at,
                "source": SOURCE_REFERENCE,
                "refModel": source.model,
                "refId": source.record_id,
                "refKey": source.source_key,
            }
            continue

        had = before.get(key)
        if not is_empty_value(had) and values_match(had, value):
            existing = carried_in.get(key)
            if isinstance(existing, dict):
                out[key] = dict(existing)
            continue

        out[key] = dict(designer_stamp)

    return out


def entry_provenance(row: Any) -> dict[str, Any]:
    stored = getattr(row, "fieldProvenance", None)
    return dict(stored) if isinstance(stored, dict) else {}


def resolve_entry_provenance(rows: Iterable[Any]) -> dict[str, dict[str, Any]]:
    return {row.id: entry_provenance(row) for row in rows}


def provenance_user_ids(maps: Iterable[Mapping[str, Any]]) -> set[str]:
    ids: set[str] = set()
    for stamps in maps:
        for stamp in stamps.values():
            if isinstance(stamp, dict) and stamp.get("by"):
                ids.add(str(stamp["by"]))
    return ids


async def resolve_display_names(maps: Iterable[dict[str, Any]]) -> None:
    from app.core.db import db

    materialised = list(maps)
    wanted = {
        str(stamp["by"])
        for stamps in materialised
        for stamp in stamps.values()
        if isinstance(stamp, dict) and stamp.get("by") and not stamp.get("byName")
    }
    if not wanted:
        return
    users = await db.user.find_many(where={"id": {"in": sorted(wanted)}})
    names = {u.id: u.name for u in users}
    for stamps in materialised:
        for stamp in stamps.values():
            if not isinstance(stamp, dict) or stamp.get("byName"):
                continue
            name = names.get(str(stamp.get("by")))
            if name:
                stamp["byName"] = name



async def canonical_divergence(
    rows: Iterable[Any],
) -> dict[str, dict[str, dict[str, Any]]]:
    from app.core.db import db
    from app.services.design_workshops import REFERENCE_MODELS, _reference_photos

    materialised = list(rows)

    wanted: dict[str, set[str]] = {}
    for row in materialised:
        for stamp in entry_provenance(row).values():
            if not isinstance(stamp, dict) or stamp.get("source") != SOURCE_REFERENCE:
                continue
            model, record_id = stamp.get("refModel"), stamp.get("refId")
            if model in REFERENCE_MODELS and record_id:
                wanted.setdefault(str(model), set()).add(str(record_id))

    resolved: dict[str, dict[str, dict[str, Any]]] = {}
    for model, ids in wanted.items():
        spec = REFERENCE_MODELS[model]
        records = await getattr(db, spec.delegate).find_many(
            where={"id": {"in": sorted(ids)}}, include=spec.include or None
        )
        photos = await _reference_photos(spec, [rec.id for rec in records])
        resolved[model] = {rec.id: spec.data(rec, photos.get(rec.id)) for rec in records}

    out: dict[str, dict[str, dict[str, Any]]] = {}
    for row in materialised:
        stored_data = dict(getattr(row, "data", None) or {})
        per_field: dict[str, dict[str, Any]] = {}
        for key, stamp in entry_provenance(row).items():
            if not isinstance(stamp, dict) or stamp.get("source") != SOURCE_REFERENCE:
                continue
            model, record_id = stamp.get("refModel"), stamp.get("refId")
            looked_up = model in REFERENCE_MODELS and bool(record_id)
            source = resolved.get(str(model), {}).get(str(record_id)) if looked_up else None
            canonical = None if source is None else source.get(str(stamp.get("refKey")))
            stored = stored_data.get(key)
            per_field[key] = {
                "stored": stored,
                "canonical": canonical,
                "diverged": source is not None and str(stored) != str(canonical),
                "recordDeleted": looked_up and source is None,
            }
        if per_field:
            out[row.id] = per_field
    return out
