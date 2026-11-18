from __future__ import annotations

from collections.abc import Iterable
from typing import Any

from app.services import custom_sections, rich_text
from app.services.field_voices.sources import Sources, plain_json
from app.services.field_voices.stages import StagePlacement
from app.services.stage_schema import EntitySpec, FieldType, StageSpec, stages

PORTAL = "DESIGNER"

TYPED_TRANSCRIPTS: dict[tuple[str, str, str], str] = {
    ("TRADITIONAL_PROCESS_BASELINE", "traditionalProcess", "artisanAudio"): "artisanAudioTranscript",
    ("TRADITIONAL_PROCESS_BASELINE", "processStep", "stepAudio"): "stepAudioTranscript",
    ("MARKET_SURVEY_CAPTURE", "surveyResponse", "voiceNote"): "voiceTranscript",
    ("PROTOTYPE_DEVELOPMENT", "prototype", "audioNarration"): "audioTranscript",
    ("SKETCH_REVIEW", "sketchReview", "voiceFeedback"): "voiceFeedbackTranscript",
    ("WORKSHOP_OUTCOMES", "outcomes", "feedbackAudio"): "feedbackTranscript",
}
NEVER_FIELDS: frozenset[tuple[str, str, str]] = frozenset(
    {
        ("WORKSHOP_PLAN_PARTICIPANTS_OPENING", "workshopPlan", "designerSignature"),
        ("WORKSHOP_PLAN_PARTICIPANTS_OPENING", "workshopPlan", "designerCv"),
        ("INSPECTION_CLOSING", "certificate", "signatureImage"),
        ("INSPECTION_CLOSING", "certificate", "certificateFile"),
    }
)
DESIGNER_PORTRAITS: frozenset[tuple[str, str, str]] = frozenset(
    {("WORKSHOP_PLAN_PARTICIPANTS_OPENING", "workshopPlan", "designerPhoto")}
)
CUSTOM_ENTITY_TITLE = "Designer-defined fields"
CUSTOM_FIELD_LABEL = "Designer-defined field"
TEXT_LABEL_TYPES = frozenset({FieldType.TEXT, FieldType.LONG_TEXT})
DESIGNER_ROLE = "DESIGNER"
PROTOTYPE_ENTITY = "prototype"
PARTICIPANT_MODEL = "DwParticipant"
ARTISAN_MODEL = "Artisan"
PRODUCT_MODEL = "ProductDocumentation"
TOOL_MODEL = "ToolDocumentation"

ROWS_SQL = (
    'SELECT e."id", e."designWorkshopId", e."stageKey", e."entityKey", e."data" FROM "DwStageEntry" e '
    'JOIN "DesignWorkshop" w ON w."id" = e."designWorkshopId" WHERE e."deletedAt" IS NULL AND w."deletedAt" IS NULL '
    'ORDER BY e."designWorkshopId", e."stageKey", e."entityKey", e."ordinal", e."id"'
)
PARTICIPANT_SQL = (
    'SELECT "id", "data" FROM "DwStageEntry" WHERE "entityKey" = \'participant\' AND "id" = ANY($1::text[])'
)
LIVE_ROWS_SQL = (
    'SELECT e."id" FROM "DwStageEntry" e JOIN "DesignWorkshop" w ON w."id" = e."designWorkshopId" '
    'WHERE e."id" = ANY($1::text[]) AND e."deletedAt" IS NULL AND w."deletedAt" IS NULL'
)
MERGES_SQL = (
    'SELECT "mergedId", "survivorId" FROM "ArtisanMerge" WHERE "undoneAt" IS NULL ORDER BY "createdAt", "id"'
)
PROFILE_SQL = (
    'SELECT "userId", "displayName", "localName", "phone", "phoneCountryCode", "email" FROM "DesignerProfile" '
    'WHERE "userId" = ANY($1::text[])'
)
PROFILE_MEDIA_SQL = (
    'SELECT "signatureMediaId", "cvMediaId" FROM "DesignerProfile" '
    'WHERE "signatureMediaId" IS NOT NULL OR "cvMediaId" IS NOT NULL'
)
PROFILE_PHOTO_SQL = (
    'SELECT "photoMediaId", "userId" FROM "DesignerProfile" WHERE "photoMediaId" IS NOT NULL ORDER BY "userId"'
)
DESIGN_WORKSHOP_SQL = (
    'SELECT "id", "title", "workshopCode", "startDate", "endDate", "state", "district", '
    '"deletedAt", "craftId", "createdById" FROM "DesignWorkshop" WHERE "id" = ANY($1::text[])'
)
CREATORS_SQL = 'SELECT "id", "createdById" FROM "DesignWorkshop" WHERE "id" = ANY($1::text[])'
VIEWERS_SQL = (
    'SELECT v."designWorkshopId", v."userId" FROM "DesignWorkshopViewer" v JOIN "User" u ON u."id" = v."userId" '
    'LEFT JOIN "DesignerProfile" p ON p."userId" = v."userId" WHERE v."designWorkshopId" = ANY($1::text[]) '
    'AND (u."role"::text = \'DESIGNER\' OR p."id" IS NOT NULL) ORDER BY v."designWorkshopId", v."createdAt", v."userId"'
)
MEDIA_DW_SQL = 'SELECT "id", "designWorkshopId" FROM "MediaFile" WHERE "id" = ANY($1::text[])'
RECORD_DW_SQL = {
    "product": 'SELECT "id", "designWorkshopId" FROM "ProductDocumentation" WHERE "id" = ANY($1::text[])',
    "tool": 'SELECT "id", "designWorkshopId" FROM "ToolDocumentation" WHERE "id" = ANY($1::text[])',
    "process": 'SELECT "id", "designWorkshopId" FROM "Process" WHERE "id" = ANY($1::text[])',
}
DESIGN_WORKSHOP_TAG = "designworkshop"


async def _query(client: Any, sql: str, ids: Iterable[str]) -> list[dict[str, Any]]:
    wanted = sorted({str(i) for i in ids if i})
    if not wanted:
        return []
    return [dict(r) for r in await client.query_raw(sql, wanted)]


async def survivors(client: Any) -> dict[str, str]:
    return {str(r["mergedId"]): str(r["survivorId"]) for r in await client.query_raw(MERGES_SQL)}


async def extra_refusals(client: Any, artisan_ids: list[str]) -> set[str]:
    return set()


def _digits(*parts: Any) -> str | None:
    joined = "".join(ch for part in parts for ch in str(part or "") if ch.isdigit())
    return joined or None


async def designer_profiles(client: Any, user_ids: Iterable[str]) -> dict[str, dict[str, Any]]:
    out: dict[str, dict[str, Any]] = {}
    for row in await _query(client, PROFILE_SQL, user_ids):
        out[str(row["userId"])] = {
            "displayName": row.get("displayName"),
            "localName": row.get("localName"),
            "numbers": [row.get("phone"), _digits(row.get("phoneCountryCode"), row.get("phone"))],
            "emails": [row.get("email")],
        }
    return out


async def never_media(client: Any) -> set[str]:
    rows = await client.query_raw(PROFILE_MEDIA_SQL)
    return {str(row[key]) for row in rows for key in ("signatureMediaId", "cvMediaId") if row.get(key)}


async def designer_photos(client: Any) -> dict[str, str]:
    out: dict[str, str] = {}
    for row in await client.query_raw(PROFILE_PHOTO_SQL):
        out.setdefault(str(row["photoMediaId"]), str(row["userId"]))
    return out


async def design_workshops(client: Any, ids: Iterable[str]) -> dict[str, dict[str, Any]]:
    return {str(r["id"]): r for r in await _query(client, DESIGN_WORKSHOP_SQL, ids)}


async def designers(client: Any, design_workshop_ids: Iterable[str]) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    for row in await _query(client, CREATORS_SQL, design_workshop_ids):
        if row.get("createdById"):
            out.setdefault(str(row["id"]), []).append(str(row["createdById"]))
    for row in await _query(client, VIEWERS_SQL, design_workshop_ids):
        found = out.setdefault(str(row["designWorkshopId"]), [])
        if str(row["userId"]) not in found:
            found.append(str(row["userId"]))
    return out


async def media_design_workshops(client: Any, sources: Sources) -> dict[str, str]:
    own = {str(r["id"]): r.get("designWorkshopId") for r in await _query(client, MEDIA_DW_SQL, sources.media)}
    records = {
        kind: {str(r["id"]): r.get("designWorkshopId") for r in await _query(client, sql, ids)}
        for kind, sql, ids in (
            ("product", RECORD_DW_SQL["product"], sources.products),
            ("tool", RECORD_DW_SQL["tool"], sources.tools),
            ("process", RECORD_DW_SQL["process"], sources.processes),
        )
    }
    out: dict[str, str] = {}
    for media_id, media in sources.media.items():
        tag = str(media.get("linkedRecordType") or "").lower()
        linked = media.get("linkedRecordId")
        session = sources.session_end(media.get("questionnaireInterviewId")) or {}
        step = sources.steps.get(str(linked or "")) if tag == "processstep" else None
        candidates = [
            own.get(media_id),
            linked if tag == DESIGN_WORKSHOP_TAG else None,
            session.get("designWorkshopId"),
            records["product"].get(str(media.get("productId") or (linked if tag == "product" else ""))),
            records["tool"].get(str(media.get("toolId") or (linked if tag == "tool" else ""))),
            records["process"].get(str(linked if tag == "process" else (step or {}).get("processId") or "")),
        ]
        found = next((str(c) for c in candidates if c), None)
        if found:
            out[media_id] = found
    return out


async def stage_rows_gone(client: Any, row_ids: Iterable[str]) -> set[str]:
    wanted = {str(r) for r in row_ids if r}
    present = {str(r["id"]) for r in await _query(client, LIVE_ROWS_SQL, wanted)}
    return wanted - present


def _media_values(value: Any) -> list[str]:
    if not value:
        return []
    raw = value if isinstance(value, list) else [value]
    return [str(v) for v in raw if isinstance(v, str) and v.strip()]


def _strings(value: Any) -> list[str]:
    if isinstance(value, str):
        return [value]
    if isinstance(value, list):
        return [s for item in value for s in _strings(item)]
    if isinstance(value, dict):
        return [s for item in value.values() for s in _strings(item)]
    return []


def _specs() -> dict[tuple[str, str], tuple[StageSpec, EntitySpec]]:
    return {(stage.key, entity.key): (stage, entity) for stage in stages() for entity in stage.entities}


def _row_label(entity: EntitySpec, data: dict[str, Any]) -> str | None:
    if not entity.label_field:
        return None
    spec = next((f for f in entity.fields if f.key == entity.label_field), None)
    value = data.get(entity.label_field)
    if spec is None or spec.type not in TEXT_LABEL_TYPES or not isinstance(value, str):
        return None
    return value.strip() or None


def _refs(entity: EntitySpec, data: dict[str, Any], model: str) -> list[str]:
    out: list[str] = []
    for spec in entity.fields:
        if spec.type is FieldType.REF and spec.ref_model == model:
            out += _media_values(data.get(spec.key))
    return out


def _caption(entity: EntitySpec, data: dict[str, Any], field_key: str) -> str | None:
    for spec in entity.fields:
        if spec.caption_for == field_key and isinstance(data.get(spec.key), str):
            return data[spec.key].strip() or None
    return None


def _typed(data: dict[str, Any], field_key: str | None) -> str | None:
    if not field_key or not data.get(field_key):
        return None
    return rich_text.to_plain(rich_text.from_stored_text(data[field_key])) or None


def _prototype(entity: EntitySpec, row_id: str, data: dict[str, Any]) -> str | None:
    if entity.key == PROTOTYPE_ENTITY:
        return row_id
    found = data.get("prototypeRef")
    return str(found) if isinstance(found, str) and found else None


async def _rows(client: Any) -> tuple[list[dict[str, Any]], dict[str, list[str]]]:
    rows = [dict(r) for r in await client.query_raw(ROWS_SQL)]
    for row in rows:
        row["data"] = plain_json(row.get("data")) or {}
    participant_ids = {
        ref for row in rows for key in ("artisanRef", "participantRef")
        for ref in _media_values(row["data"].get(key)) if row["entityKey"] != "participant"
    }
    participants: dict[str, list[str]] = {}
    for row in await _query(client, PARTICIPANT_SQL, participant_ids):
        data = plain_json(row.get("data")) or {}
        participants[str(row["id"])] = _media_values(data.get("artisanRef"))
    return rows, participants


def _artisans(entity: EntitySpec, data: dict[str, Any], participants: dict[str, list[str]]) -> tuple[str, ...]:
    found = _refs(entity, data, ARTISAN_MODEL)
    for participant in _refs(entity, data, PARTICIPANT_MODEL):
        found += participants.get(participant, [])
    return tuple(dict.fromkeys(found))


def _row_placements(
    stream: str, row: dict[str, Any], stage: StageSpec, entity: EntitySpec, participants: dict[str, list[str]]
) -> list[StagePlacement]:
    data = row["data"]
    out: list[StagePlacement] = []
    common = {
        "design_workshop_id": str(row["designWorkshopId"]),
        "stage_key": stage.key,
        "stage_title": stage.title,
        "stage_number": stage.number,
        "entity_key": entity.key,
        "entity_title": entity.title,
        "row_id": str(row["id"]),
        "row_label": _row_label(entity, data),
        "artisan_ids": _artisans(entity, data, participants),
        "product_ids": tuple(_refs(entity, data, PRODUCT_MODEL)),
        "tool_ids": tuple(_refs(entity, data, TOOL_MODEL)),
        "prototype_id": _prototype(entity, str(row["id"]), data),
    }
    for spec in entity.fields:
        if spec.type.is_media:
            values = _media_values(data.get(spec.key))
        elif spec.is_rich_text:
            values = sorted(rich_text.media_ids(data.get(spec.key)))
        else:
            continue
        where = (stage.key, entity.key, spec.key)
        typed_key = TYPED_TRANSCRIPTS.get(where) if stream == "transcripts" else None
        for media_id in values:
            out.append(StagePlacement(
                media_id=media_id, field_key=spec.key, field_label=spec.label, listed=where not in NEVER_FIELDS,
                caption=_caption(entity, data, spec.key), typed_transcript=_typed(data, typed_key),
                pictures_designers=where in DESIGNER_PORTRAITS, **common,
            ))
    return out


def _custom_placements(row: dict[str, Any], stage: StageSpec | None) -> list[StagePlacement]:
    return [
        StagePlacement(
            media_id=value, design_workshop_id=str(row["designWorkshopId"]), stage_key=str(row["stageKey"]),
            stage_title=stage.title if stage else str(row["stageKey"]), stage_number=stage.number if stage else 0,
            entity_key=custom_sections.CUSTOM_ENTITY_KEY, entity_title=CUSTOM_ENTITY_TITLE, row_id=str(row["id"]),
            row_label=None, field_key=custom_sections.CUSTOM_ENTITY_KEY, field_label=CUSTOM_FIELD_LABEL, listed=True,
        )
        for value in dict.fromkeys(_strings(row["data"]))
    ]


async def _placements(client: Any, stream: str) -> list[StagePlacement]:
    rows, participants = await _rows(client)
    specs = _specs()
    by_stage = {stage.key: stage for stage in stages()}
    out: list[StagePlacement] = []
    for row in rows:
        if row["entityKey"] == custom_sections.CUSTOM_ENTITY_KEY:
            out += _custom_placements(row, by_stage.get(str(row["stageKey"])))
            continue
        found = specs.get((str(row["stageKey"]), str(row["entityKey"])))
        if found is not None:
            out += _row_placements(stream, row, found[0], found[1], participants)
    return out


async def stage_media(client: Any) -> list[StagePlacement]:
    return await _placements(client, "transcripts")


async def stage_images(client: Any) -> list[StagePlacement]:
    return await _placements(client, "images")
