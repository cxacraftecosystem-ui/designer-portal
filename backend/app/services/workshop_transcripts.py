from __future__ import annotations

import logging
from typing import Any

from app.core.config import Settings
from app.core.db import db
from app.services.report_annexures import TranscriptItem
from app.services.stage_schema import FieldType, StageSpec, stages

logger = logging.getLogger(__name__)

TRANSCRIPTION = "TRANSCRIPTION"

_SETTLED_TRANSCRIPT_STATUSES = frozenset({"COMPLETED", "EMPTY"})

_IN_FLIGHT_TRANSCRIPT_STATUSES = frozenset({"QUEUED", "PROCESSING"})

_DURATION_KEYS = ("durationSeconds", "duration_seconds", "durationSec", "duration")


def audio_field_map() -> dict[str, dict[str, str]]:
    out: dict[str, dict[str, str]] = {}
    for spec in stages():
        for entity in spec.entities:
            fields = {f.key: f.label for f in entity.fields if f.type is FieldType.AUDIO}
            if fields:
                out[entity.key] = fields
    return out


def _entity_stage() -> dict[str, StageSpec]:
    return {e.key: spec for spec in stages() for e in spec.entities}


def _media_ids_in(value: Any) -> list[str]:
    if isinstance(value, str):
        return [value] if value.strip() else []
    if isinstance(value, (list, tuple)):
        return [str(v).strip() for v in value if isinstance(v, (str, int)) and str(v).strip()]
    return []


def audio_references(entries: list[Any]) -> dict[str, tuple[str, str, str]]:
    audio_fields = audio_field_map()
    entity_stage = _entity_stage()
    found: dict[str, tuple[str, str, str]] = {}
    for row in entries:
        entity_key = getattr(row, "entityKey", "")
        fields = audio_fields.get(entity_key)
        if not fields:
            continue
        stage_key = getattr(row, "stageKey", "") or getattr(entity_stage.get(entity_key), "key", "")
        data = getattr(row, "data", None) or {}
        for field_key in fields:
            for media_id in _media_ids_in(data.get(field_key)):
                found.setdefault(media_id, (stage_key, entity_key, field_key))
    return found



async def enqueue_stage_transcriptions(
    entries: list[Any],
    requested_by_id: str,
    settings: Settings | None = None,
    *,
    viewer: Any,
    design_workshop_id: str | None = None,
) -> list[str]:
    from app.services import transcribe_choice
    from app.services.media_queue import enqueue_media_processing_jobs
    from app.services.records import owned_or_granted_where

    references = audio_references(entries)
    if not references:
        return []
    try:
        where: dict[str, Any] = {"id": {"in": sorted(references)}}
        entitled = await owned_or_granted_where(viewer, owner_field="uploadedById")
        if entitled:
            where = {"AND": [where, entitled]}
        rows = await db.mediafile.find_many(where=where)
    except Exception as exc:  # noqa: BLE001 - see the docstring: never fail the stage save
        logger.warning("Could not load workshop audio for transcription: %s", exc)
        return []

    candidates = [
        row
        for row in rows
        if transcribe_choice.wanted(row)
        and str(getattr(row, "transcriptStatus", "") or "").upper()
        not in _SETTLED_TRANSCRIPT_STATUSES
        and not str(getattr(row, "transcriptText", "") or "").strip()
    ]
    if not candidates:
        return []

    already_queued: set[str] = set()
    pending_jobs_known = False
    try:
        pending = await db.mediaprocessingjob.find_many(
            where={
                "mediaFileId": {"in": [row.id for row in candidates]},
                "jobType": TRANSCRIPTION,
                "status": {"in": ["QUEUED", "PROCESSING"]},
            }
        )
        already_queued = {job.mediaFileId for job in pending}
        pending_jobs_known = True
    except Exception as exc:  # noqa: BLE001 - see the docstring: never fail the stage save
        logger.warning("Could not read pending transcription jobs: %s", exc)

    queued: list[str] = []
    for row in candidates:
        if row.id in already_queued:
            continue
        in_flight_claim = (
            str(getattr(row, "transcriptStatus", "") or "").upper()
            in _IN_FLIGHT_TRANSCRIPT_STATUSES
        )
        if in_flight_claim and not pending_jobs_known:
            continue
        if in_flight_claim:
            logger.info(
                "Re-queueing transcription for media %s: status %s with no live job",
                row.id,
                getattr(row, "transcriptStatus", ""),
            )
        try:
            created = await enqueue_media_processing_jobs(
                row,
                [TRANSCRIPTION],
                requested_by_id,
                settings,
                design_workshop_id=design_workshop_id,
            )
            if created:
                queued.append(row.id)
        except Exception as exc:  # noqa: BLE001 - one bad clip must not lose the others
            logger.warning("Could not queue transcription for media %s: %s", row.id, exc)
    return queued


async def enqueue_stage_transcriptions_for_stage(
    spec: StageSpec,
    entries: list[Any],
    requested_by_id: str,
    settings: Settings | None = None,
    *,
    viewer: Any,
    design_workshop_id: str | None = None,
) -> list[str]:
    scoped = [row for row in entries if getattr(row, "stageKey", "") == spec.key]
    return await enqueue_stage_transcriptions(
        scoped,
        requested_by_id,
        settings,
        viewer=viewer,
        design_workshop_id=design_workshop_id,
    )



def _duration_seconds(extra: Any) -> int | None:
    if not isinstance(extra, dict):
        return None
    for key in _DURATION_KEYS:
        raw = extra.get(key)
        if raw in (None, ""):
            continue
        try:
            value = int(float(raw))
        except (TypeError, ValueError):
            continue
        if value > 0:
            return value
    return None


def build_transcript_item(row: Any, reference: tuple[str, str, str]) -> TranscriptItem:
    stage_key, entity_key, field_key = reference
    spec = next((s for s in stages() if s.key == stage_key), None)
    label = ""
    if spec is not None:
        entity = next((e for e in spec.entities if e.key == entity_key), None)
        field = entity.field(field_key) if entity is not None else None
        label = field.label if field is not None else ""
    recorded = getattr(row, "recordedAt", None) or getattr(row, "createdAt", None)
    return TranscriptItem(
        media_id=row.id,
        stage_key=stage_key,
        stage_number=spec.number if spec is not None else 0,
        stage_title=spec.title if spec is not None else "",
        entity_key=entity_key,
        field_key=field_key,
        field_label=label,
        filename=getattr(row, "originalFilename", "") or "",
        recorded_at=recorded.isoformat() if recorded else "",
        duration_seconds=_duration_seconds(getattr(row, "extraMetadata", None)),
        status=str(getattr(row, "transcriptStatus", "") or ""),
        text=str(getattr(row, "transcriptText", "") or ""),
    )


async def load_transcript_items(entries: list[Any], *, viewer: Any) -> list[TranscriptItem]:
    from app.services.records import owned_or_granted_where

    references = audio_references(entries)
    if not references:
        return []
    where: dict[str, Any] = {"id": {"in": sorted(references)}}
    entitled = await owned_or_granted_where(viewer, owner_field="uploadedById")
    if entitled:
        where = {"AND": [where, entitled]}
    rows = await db.mediafile.find_many(where=where)
    items = [build_transcript_item(row, references[row.id]) for row in rows if row.id in references]
    items.sort(key=lambda item: (item.stage_number, item.stage_key, item.label, item.media_id))
    return items


def wants_transcripts(option: bool | None, report_settings: dict[str, Any] | None) -> bool:
    if option is not None:
        return bool(option)
    return bool((report_settings or {}).get("includeTranscripts"))
