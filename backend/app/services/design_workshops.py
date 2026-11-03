import logging
import re
from collections import Counter
from collections.abc import Awaitable, Callable, Iterable, Mapping, Sequence
from dataclasses import dataclass, field as dataclass_field, replace
from datetime import UTC, datetime
from typing import Any

from fastapi import HTTPException, status

from prisma.errors import UniqueViolationError

from app.services.standard_values import compose_phone, language_name
from app.services.text_format import by_count, count_noun
from app.core.db import db
from app.core.deps import can_run_design_workshops, has_rank, is_content_admin

from app.schemas import design_workshop_approvals, design_workshop_review_loop
from app.services import (
    aadhaar_fingerprint,
    craft_master,
    custom_sections,
    design_workshop_data,
    design_workshop_posts,
    dictation_consent,
    entry_provenance,
    rich_text,
    stage_computed,
    workshop_copy,
)
from app.services.address import DISTRICTS_BY_STATE
from app.services.concurrency import gather_reads
from app.services.design_workshop_access import add_one_viewer
from app.services.design_workshop_viewers import (
    _assert_every_id_may_be_granted,
    has_viewer_grant,
)
from app.services.designers import PREFILL_MAP, prefill_from_profile
from app.services.report_annexures import annexure_warnings, attach_transcripts
from app.services.report_builder import ReferencedRecord, WorkshopData, build_report
from app.services.report_custom_sections import (
    CustomReportField,
    CustomSectionItem,
    attach_custom_sections,
    custom_sections_of,
)
from app.services.report_docx import render_docx
from app.services.report_model import ImageRef, PageSize, ReportMeta
from app.services.questionnaire_forms import visible_questionnaire_where

from app.services.questionnaire_kinds import label_for as questionnaire_kind_label
from app.services.report_pdf import render_pdf
from app.services.report_questionnaires import attach_questionnaires, questionnaire_warnings

from app.services.records import (
    contains,
    derive_age,
    derive_experience_years,
    mask_identity_number,
    viewable_where,
)

from app.services.record_fields import METHOD_CLAUSES, field_method
from app.services.report_templates import apply_report_settings, template as get_template
from app.services.report_theme import resolve_accent, resolve_font, theme_from_accent
from app.services.stage_schema import (
    PROMOTED_COLUMNS,
    REF_SCOPE_ALL,
    REF_SCOPE_WORKSHOP,
    REF_SCOPES,
    REFERENCE_HYDRATION,
    REFERENCE_SUGGESTIONS,
    Cardinality,
    EntitySpec,
    FieldSpec,
    FieldType,
    StageSpec,
    all_entities,
    coerce_value,
    promoted_values,
    registry_version,
    stage_completeness,
    stages,
    validate_entry,
)
from app.services.workshop_transcripts import (
    audio_references,
    enqueue_stage_transcriptions,
    load_transcript_items,
    wants_transcripts,
)

logger = logging.getLogger(__name__)


async def _refuse_if_the_officer_is_authoring_what_they_sanctioned(
    record: Any, user: Any, *, for_edit: bool
) -> None:
    if not for_edit:
        return
    if getattr(record, "createdById", None) != getattr(user, "id", None):
        return
    if not has_rank(user, "ASSISTANT_DIRECTOR"):
        return
    order = await db.sanctionorder.find_unique(
        where={"designWorkshopId": record.id},
    )
    if order is None:
        return
    if getattr(order, "createdById", None) != getattr(user, "id", None):
        return
    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail=(
            "You recorded the sanction order that opened this workshop, so you cannot also author "
            "it. The work belongs to the designer the order names; you can read every stage and "
            "the report."
        ),
    )


async def _refuse_if_the_caller_holds_a_supervisory_post(
    record: Any, user: Any, *, for_edit: bool
) -> None:
    if not for_edit:
        return
    await design_workshop_posts.refuse_a_holders_write(record.id, user)


async def load_workshop_or_404(
    workshop_id: str,
    user: Any,
    *,
    for_edit: bool = False,
    barred_to_post_holders: bool = True,
    allow_when_frozen: bool = False,
) -> Any:
    record = await db.designworkshop.find_unique(where={"id": workshop_id})
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    admin = is_content_admin(user)
    if (
        record.createdById != user.id
        and not admin
        and not (
            can_run_design_workshops(user)
            and await has_viewer_grant(workshop_id, user.id)
        )
    ):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    await _refuse_if_the_officer_is_authoring_what_they_sanctioned(record, user, for_edit=for_edit)
    await _refuse_if_the_caller_holds_a_supervisory_post(
        record, user, for_edit=for_edit and barred_to_post_holders
    )
    if record.deletedAt is not None:
        if for_edit:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="This workshop is deleted. Restore it before editing.",
            )
        if not admin:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    if for_edit and not allow_when_frozen:
        refusal = design_workshop_approvals.frozen_refusal(
            getattr(record, "status", None), getattr(record, "handedOnAt", None)
        )
        if refusal:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=refusal)
    return record


async def entry_rows(workshop_id: str, *, stage_key: str | None = None) -> list[Any]:
    where: dict[str, Any] = {"designWorkshopId": workshop_id, "deletedAt": None}
    if stage_key:
        where["stageKey"] = stage_key
    return await db.dwstageentry.find_many(
        where=where, order=[{"ordinal": "asc"}, {"createdAt": "asc"}, {"id": "asc"}]
    )



def _cover_entity_keys() -> frozenset[str]:
    return frozenset(path.partition(".")[0] for path in PROMOTED_COLUMNS)


def _profile_copied_field_keys() -> frozenset[str]:
    return frozenset(field_key for _column, field_key in PREFILL_MAP)


def _repeating_entity_keys() -> frozenset[str]:
    return frozenset(
        entity.key
        for spec in stages()
        for entity in spec.entities
        if entity.cardinality is not Cardinality.SINGLETON
    )


async def stage_writers(
    workshop_ids: Iterable[str], user_ids: Iterable[str]
) -> set[tuple[str, str]]:
    workshops = sorted({str(w) for w in workshop_ids if w})
    people = sorted({str(u) for u in user_ids if u})
    if not workshops or not people:
        return set()
    rows = await db.query_raw(
        _STAGE_WRITERS_SQL,
        workshops,
        people,
        sorted(_cover_entity_keys()),
        sorted(_profile_copied_field_keys()),
        entry_provenance.SOURCE_DESIGNER,
        sorted(_repeating_entity_keys()),
    )
    return {
        (str(row["workshopId"]), str(row["userId"]))
        for row in rows
        if row.get("workshopId") and row.get("userId")
    }


_STAGE_WRITERS_SQL = """
SELECT e."designWorkshopId" AS "workshopId", s.value ->> 'by' AS "userId"
FROM "DwStageEntry" AS e
CROSS JOIN LATERAL jsonb_each(
    CASE WHEN jsonb_typeof(e."fieldProvenance") = 'object'
         THEN e."fieldProvenance" ELSE '{}'::jsonb END
) AS s
WHERE e."deletedAt" IS NULL
  AND e."designWorkshopId" = ANY($1::text[])
  AND NOT (e."entityKey" = ANY($3::text[]))
  AND NOT (s.key = ANY($4::text[]))
  AND jsonb_typeof(s.value) = 'object'
  AND s.value ->> 'source' = $5
  AND s.value ->> 'by' = ANY($2::text[])
UNION
SELECT e."designWorkshopId" AS "workshopId", e."createdById" AS "userId"
FROM "DwStageEntry" AS e
WHERE e."deletedAt" IS NULL
  AND e."designWorkshopId" = ANY($1::text[])
  AND e."entityKey" = ANY($6::text[])
  AND e."createdById" = ANY($2::text[])
"""


def _iso_or_none(moment: Any) -> str | None:
    return moment.isoformat() if isinstance(moment, datetime) else None


def workshop_summary(record: Any) -> dict[str, Any]:
    return {
        "id": record.id,
        "title": record.title,
        "templateId": record.templateId,
        "status": record.status,
        "workshopCode": record.workshopCode,
        "workshopKind": record.workshopKind,
        "scheme": record.scheme,
        "craftName": record.craftName,
        "clusterName": record.clusterName,
        "state": record.state,
        "district": record.district,
        "venue": record.venue,
        "startDate": record.startDate.date().isoformat() if record.startDate else None,
        "endDate": record.endDate.date().isoformat() if record.endDate else None,
        "designerName": record.designerName,
        "implementingAgency": record.implementingAgency,
        "sponsor": record.sponsor,
        "notes": record.notes,
        "workshopId": record.workshopId,
        "createdById": record.createdById,
        "createdAt": record.createdAt.isoformat() if record.createdAt else None,
        "updatedAt": record.updatedAt.isoformat() if record.updatedAt else None,
        "deletedAt": record.deletedAt.isoformat() if record.deletedAt else None,
        "reviewNotes": record.reviewNotes,
        "reviewedById": record.reviewedById,
        "reviewedAt": record.reviewedAt.isoformat() if record.reviewedAt else None,
        "submissionRound": record.submissionRound,
        "approvedById": getattr(record, "approvedById", None),
        "approvedAt": _iso_or_none(getattr(record, "approvedAt", None)),
        "approvedRound": getattr(record, "approvedRound", None),
        "handedOnById": getattr(record, "handedOnById", None),
        "handedOnAt": _iso_or_none(getattr(record, "handedOnAt", None)),
        "handedOnTo": getattr(record, "handedOnTo", None),
        "handedOnExportId": getattr(record, "handedOnExportId", None),
        "lastHandedInAt": _iso_or_none(getattr(record, "lastHandedInAt", None)),
        **dictation_consent.consent_keys(record),
    }


REFERENCE_LIMIT_DEFAULT = 50
REFERENCE_LIMIT_MAX = 200
REFERENCE_OFFSET_MAX = 100_000


def _meta(row: Any) -> dict[str, Any]:
    value = getattr(row, "extraMetadata", None)
    return value if isinstance(value, dict) else {}


def _meta_value(meta: dict[str, Any], *keys: str) -> Any:
    for key in keys:
        value = meta.get(key)
        if value not in (None, ""):
            return value
    return None


def _rel(row: Any, relation: str, attribute: str) -> Any:
    related = getattr(row, relation, None)
    return getattr(related, attribute, None) if related is not None else None


def _rel_obj(row: Any, relation: str) -> Any:
    return getattr(row, relation, None)


def _subject_point(location: Any) -> dict[str, float] | None:
    if location is None:
        return None
    lat = getattr(location, "subjectLatitude", None)
    lon = getattr(location, "subjectLongitude", None)
    if lat is None or lon is None:
        return None
    try:
        return {"lat": float(lat), "lon": float(lon)}
    except (TypeError, ValueError):
        return None


_LINE_JOIN = " · "


def _one_line(value: Any) -> str:
    parts = [part.strip() for part in re.split(r"[\n;]+", str(value or ""))]
    return _LINE_JOIN.join(part for part in parts if part)


def _step_lines(process: Any) -> str | None:
    steps = getattr(process, "steps", None) or []
    ordered = sorted(
        steps, key=lambda s: (getattr(s, "sortOrder", 0) or 0, str(getattr(s, "name", "") or ""))
    )
    lines: list[str] = []
    for index, step in enumerate(ordered, start=1):
        name = _one_line(getattr(step, "name", None))
        if not name:
            continue
        line = f"{index}. {name}"
        if _enum_token(getattr(step, "stepType", None)) == "GROUP":
            line += " (group)"
        note = _one_line(getattr(step, "notes", None))
        if note:
            line = f"{line} — {note}"
        lines.append(line)
    return "\n".join(lines) or None


def _linked_artisan_names(tool: Any) -> str | None:
    links = getattr(tool, "artisanLinks", None) or []
    names: list[str] = []
    for link in links:
        artisan = getattr(link, "artisan", None)
        name = str(getattr(artisan, "name", "") or "").strip()
        if name and name not in names:
            names.append(name)
    return "\n".join(sorted(names)) or None


def _interview_artisan_count(interview: Any) -> int:
    return len(getattr(interview, "artisans", None) or [])


def _interview_artisan_phrase(interview: Any) -> str | None:
    total = _interview_artisan_count(interview)
    if not total:
        return None
    return f"{total} artisan" if total == 1 else f"{total} artisans"


def _norm_section_code(value: Any) -> str:
    return "".join(ch for ch in str(value or "") if ch.isalnum()).upper()


_CLIP_DURATION_SLOT = 3
_CLIP_MIN_TOKENS = 5
_CLIP_UNKNOWN_TOKEN = "SEC"


def _interview_clip_section_code(row: Any) -> str:
    name = str(getattr(row, "originalFilename", "") or "")
    tail = name.rsplit("/", 1)[-1]
    stem = name[: len(name) - len(tail.rsplit(".", 1)[-1]) - 1] if "." in tail else name
    parts = stem.split("_")
    if len(parts) < _CLIP_MIN_TOKENS:
        return ""
    duration = parts[_CLIP_DURATION_SLOT]
    if len(duration) != 6 or not duration.isdigit():
        return ""
    code = _norm_section_code(parts[0])
    return "" if code == _CLIP_UNKNOWN_TOKEN else code


def _interview_sections_covered(interview: Any) -> int | None:
    covered: set[str] = set()
    section_by_question: dict[str, str] = {}
    for row in getattr(interview, "responses", None) or []:
        question = getattr(row, "question", None)
        code = _norm_section_code(getattr(question, "sectionCode", None))
        if not code:
            continue
        for candidate in (getattr(row, "questionId", None), getattr(question, "id", None)):
            if candidate:
                section_by_question[str(candidate)] = code
        if str(getattr(row, "answerText", "") or "").strip():
            covered.add(code)
    unresolved: set[str] = set()
    for row in getattr(interview, "media", None) or []:
        meta = _meta(row)
        code = _norm_section_code(meta.get("sectionCode"))
        if not code:
            question_id = str(meta.get("questionId") or "")
            code = section_by_question.get(question_id, "")
            if not code and question_id:
                unresolved.add(question_id)
        if not code:
            code = _interview_clip_section_code(row)
        if code:
            covered.add(code)
    if unresolved:
        return None
    return len(covered)


def _interview_questions_answered(interview: Any) -> int:
    return sum(
        1
        for r in (getattr(interview, "responses", None) or [])
        if str(getattr(r, "answerText", "") or "").strip()
    )


def _interview_last_answered(interview: Any) -> str | None:
    stamps = [
        getattr(r, "updatedAt", None)
        for r in (getattr(interview, "responses", None) or [])
        if str(getattr(r, "answerText", "") or "").strip()
    ]
    stamps = [s for s in stamps if s is not None]
    if not stamps:
        return None
    return _iso_date(max(stamps))


def _process_media_note(process: Any) -> str | None:
    own = len(getattr(process, "media", None) or [])
    steps = getattr(process, "steps", None) or []
    per_step = sum(len(getattr(step, "media", None) or []) for step in steps)
    if not own and not per_step:
        return None
    parts: list[str] = []
    if own:
        parts.append(f"{own} on the process itself")
    if per_step:
        covered = sum(1 for step in steps if getattr(step, "media", None))
        parts.append(f"{per_step} across {count_noun(covered, 'step')}")
    return "Attached to the process record: " + ", ".join(parts) + "."


_MEDIA_NOTE_WORDS: tuple[tuple[tuple[str, ...], str, str], ...] = (
    (("IMAGE",), "photograph", "photographs"),
    (("VIDEO",), "video", "videos"),
    (("AUDIO",), "audio note", "audio notes"),
    (("PDF", "DOCUMENT"), "document", "documents"),
    (("OTHER",), "file", "files"),
)


def _media_note(subject: str, rows: Any, *, numbered_prefix: str = "") -> str | None:
    counts: dict[str, int] = {}
    numbered = 0
    for row in rows or []:
        if str(_meta(row).get("purpose") or "") == MEASUREMENT_GRID_PURPOSE:
            continue
        token = _enum_token(getattr(row, "mediaType", None))
        counts[token] = counts.get(token, 0) + 1
        if numbered_prefix and str(getattr(row, "originalFilename", "") or "").startswith(
            numbered_prefix
        ):
            numbered += 1
    if not counts:
        return None
    known = {token for tokens, _one, _many in _MEDIA_NOTE_WORDS for token in tokens}
    parts: list[str] = []
    for tokens, one, many in _MEDIA_NOTE_WORDS:
        total = sum(counts.get(token, 0) for token in tokens)
        if "OTHER" in tokens:
            total += sum(n for token, n in counts.items() if token not in known)
        if total:
            parts.append(f"{total} {one if total == 1 else many}")
    note = f"Attached to the {subject} record: " + ", ".join(parts)
    if numbered:
        verb = "documents" if numbered == 1 else "document"
        note += f", of which {numbered} {verb} the making in order"
    return note + "."


def _money(value: Any) -> str | None:
    if value is None:
        return None
    try:
        return f"{float(value):.2f}"
    except (TypeError, ValueError):
        return None


def _reference_data(spec: ReferenceModel, row: Any, photo: Any) -> dict[str, Any]:
    return {
        key: rich_text.plain_from_stored(value) if isinstance(value, str) else value
        for key, value in spec.data(row, photo).items()
    }


def _joined(*parts: Any) -> str:
    return " · ".join(str(p).strip() for p in parts if p not in (None, "") and str(p).strip())


def _craft_path(craft: Any) -> str | None:
    sub = _rel_obj(craft, "subCategory")
    sub_name = str(getattr(sub, "name", "") or "").strip() if sub is not None else ""
    category = _rel_obj(sub, "category") if sub is not None else None
    category_name = str(getattr(category, "name", "") or "").strip() if category is not None else ""
    if category_name and sub_name:
        return f"{category_name} › {sub_name}"
    return category_name or sub_name or (str(getattr(craft, "category", "") or "").strip() or None)


def _decimal(value: Any) -> float | None:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _iso_date(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()[:10]
    return text or None


_CM_PER_INCH = 2.54


def _inches_to_cm(value: Any) -> float | None:
    if value is None:
        return None
    try:
        return round(float(value) * _CM_PER_INCH, 2)
    except (TypeError, ValueError):
        return None


_METHOD_CARRIED_DIMENSIONS: dict[str, tuple[tuple[str, str], ...]] = {
    "ProductDocumentation": (
        ("lengthCm", "lengthInches"),
        ("widthCm", "breadthInches"),
        ("heightCm", "heightInches"),
    ),
    "ToolDocumentation": (
        ("lengthCm", "lengthInches"),
        ("breadthCm", "breadthInches"),
        ("heightCm", "heightInches"),
    ),
}


def _measurement_method_note(subject: str, model: str, row: Any) -> str | None:
    grouped: dict[str, list[str]] = {}
    for _payload_key, column in _METHOD_CARRIED_DIMENSIONS.get(model, ()):
        if _inches_to_cm(getattr(row, column, None)) is None:
            continue
        clause = METHOD_CLAUSES.get(field_method(row, column) or "")
        if clause:
            grouped.setdefault(clause, []).append(column.removesuffix("Inches").lower())
    if not grouped:
        return None
    return f"On the {subject} record: " + ", ".join(
        f"{', '.join(words)} ({clause})" for clause, words in grouped.items()
    )


def _enum_token(value: Any) -> str:
    return str(getattr(value, "value", value) or "")


_PRODUCT_TYPE_TO_CATEGORY: dict[str, str | None] = {
    "PACKAGING": "PACKAGING",
    "OTHER": None,
    "FINISHED_GOOD": None,
    "SAMPLE": None,
    "RAW_MATERIAL": None,
    "COMPONENT": None,
}

_PRODUCT_TYPE_TO_MEMBER: dict[str, str | None] = {
    "FINISHED_GOOD": "FINISHED_GOOD",
    "SAMPLE": "SAMPLE",
    "RAW_MATERIAL": "RAW_MATERIAL",
    "COMPONENT": "COMPONENT",
    "PACKAGING": "PACKAGING",
    "OTHER": "OTHER",
}

_MARKET_DEMAND_TO_DEMAND_LEVEL: dict[str, str | None] = {
    "LOW": "LOW",
    "MEDIUM": "MEDIUM",
    "HIGH": "HIGH",
    "SEASONAL": "SEASONAL",
    "UNKNOWN": None,
}

_TRADITION_TYPE_TO_TRADITION: dict[str, str | None] = {
    "TRADITIONAL": "TRADITIONAL",
    "CONTEMPORARY": "CONTEMPORARY",
    "TRANSITIONAL": "TRANSITIONAL",
    "UNKNOWN": None,
    "MODERN": "CONTEMPORARY",
    "HYBRID": "TRANSITIONAL",
}

_MAKER_TYPE_TO_MAKER: dict[str, str | None] = {
    "ARTISAN": "ARTISAN",
    "LOCAL_BLACKSMITH": "LOCAL_BLACKSMITH",
    "CARPENTER": "CARPENTER",
    "WORKSHOP": "WORKSHOP",
    "FACTORY": "FACTORY",
    "OTHER": "OTHER",
    "UNKNOWN": None,
}


def _translated(table: Mapping[str, str | None], value: Any) -> str | None:
    token = _enum_token(value)
    if not token:
        return None
    if token not in table:
        logger.error(
            "Reference carry: %r is not in its translation table, so it hydrates blank. Add it "
            "to the table in design_workshops (spelled None, with a reason, if it has no honest "
            "counterpart).",
            token,
        )
        return None
    return table[token]


_REVIEW_FLAGS: dict[str, str] = {
    "DRAFT": "Draft",
    "PENDING": "Awaiting review",
    "REJECTED": "Rejected by a reviewer",
    "NEEDS_REVISION": "Sent back for revision",
}


def _review_flag(row: Any) -> str:
    return _REVIEW_FLAGS.get(_enum_token(getattr(row, "status", None)), "")


@dataclass(frozen=True, slots=True)
class ReferencePhoto:
    id: str
    caption: str = ""


@dataclass(frozen=True, slots=True)
class ReferenceModel:
    delegate: str
    label: Callable[[Any], str]
    sublabel: Callable[[Any], str]
    data: Callable[[Any, ReferencePhoto | None], dict[str, Any]]
    order: dict[str, str]
    search_fields: tuple[str, ...]
    include: dict[str, Any] = dataclass_field(default_factory=dict)
    workshop_where: Callable[[str], dict[str, Any]] | None = None
    viewer_where: Callable[[Any], dict[str, Any]] | None = None
    offered_where: dict[str, Any] | None = None
    artisan_field: str = ""
    filter_field: str = ""
    media_field: str = ""


def _artisan_workshop_where(workshop_id: str) -> dict[str, Any]:
    return {
        "OR": [{"workshopId": workshop_id}, {"workshops": {"some": {"workshopId": workshop_id}}}]
    }


REFERENCE_MODELS: dict[str, ReferenceModel] = {
    "Artisan": ReferenceModel(
        delegate="artisan",
        include={"craft": True, "location": True, "workshop": True, "media": True},
        order={"name": "asc"},
        search_fields=("name", "localName", "place"),
        workshop_where=_artisan_workshop_where,
        media_field="artisanId",
        label=lambda r: str(r.name or ""),
        sublabel=lambda r: _joined(_rel(r, "craft", "name"), r.place, _review_flag(r)),
        data=lambda r, photo: {
            "name": r.name,
            "localName": r.localName,
            "specialisation": (
                _rel(r, "craft", "name")
                or _meta_value(_meta(r), "specialisation", "specialization")
            ),
            "craftStartDate": _iso_date(r.craftStartDate),
            "experienceYears": (
                derived
                if (derived := derive_experience_years(r.craftStartDate)) is not None
                else r.experienceYears
                if r.experienceYears is not None
                else _meta_value(_meta(r), "experienceYears", "experience", "yearsOfExperience")
            ),
            "age": (
                derived
                if (derived := derive_age(r.dateOfBirth)) is not None
                else _meta_value(_meta(r), "age")
            ),
            "gender": r.gender,
            "phone": compose_phone(getattr(r, "phoneCountryCode", None), r.phone),
            "email": r.email,
            "pehchanCardAvailable": r.pehchanCardAvailable,
            "pehchanCardNumber": mask_identity_number(r.pehchanCardNumber),
            "aadhaarNumber": mask_identity_number(aadhaar_fingerprint.stored_mask(r)),
            "village": _rel(r, "location", "village") or r.place,
            "state": _rel(r, "location", "state"),
            "district": _rel(r, "location", "district"),
            "pincode": _rel(r, "location", "pincode"),
            "address": r.address,
            "subjectLocation": _subject_point(_rel_obj(r, "location")),
            "notes": r.notes,
            "dos": r.dos,
            "donts": r.donts,
            "documentedOn": _iso_date(r.recordedAt),
            "documentedAtWorkshop": _rel(r, "workshop", "title"),
            "recordMediaNote": _media_note("artisan", _rel_obj(r, "media")),
            "photo": photo.id if photo else None,
            "photoCaption": photo.caption if photo else None,
        },
    ),
    "ProductDocumentation": ReferenceModel(
        delegate="productdocumentation",
        order={"productName": "asc"},
        search_fields=("productName", "localName", "artisanName", "craftName"),
        include={"location": True, "media": True},
        workshop_where=lambda wid: {"workshopId": wid},
        artisan_field="artisanId",
        media_field="productId",
        label=lambda r: str(r.productName or ""),
        sublabel=lambda r: _joined(
            r.artisanName, r.craftName, _money(r.sellingPrice), _review_flag(r)
        ),
        data=lambda r, photo: {
            "name": r.productName,
            "localName": r.localName,
            "category": _translated(_PRODUCT_TYPE_TO_CATEGORY, r.productType),
            "recordType": _translated(_PRODUCT_TYPE_TO_MEMBER, r.productType),
            "material": r.rawMaterialsUsed,
            "mainToolsUsed": r.mainToolsUsed,
            "price": _money(r.sellingPrice),
            "costOfMaking": _money(r.costOfMaking),
            "marketDemand": _translated(_MARKET_DEMAND_TO_DEMAND_LEVEL, r.marketDemand),
            "use": r.productFunctionUse,
            "craftName": r.craftName,
            "place": r.place,
            "artisanName": r.artisanName,
            "lengthCm": _inches_to_cm(r.lengthInches),
            "widthCm": _inches_to_cm(r.breadthInches),
            "heightCm": _inches_to_cm(r.heightInches),
            "measurementMethodNote": _measurement_method_note("product", "ProductDocumentation", r),
            "dimensionsNote": r.size,
            "productionTimeNote": r.timeTakenToCompleteProduct,
            "remarks": r.remarks,
            "recordState": _rel(r, "location", "state"),
            "recordDistrict": _rel(r, "location", "district"),
            "recordVillage": _rel(r, "location", "village"),
            "recordPincode": _rel(r, "location", "pincode"),
            "subjectLocation": _subject_point(_rel_obj(r, "location")),
            "recordMediaNote": _media_note("product", _rel_obj(r, "media")),
            "documentedOn": _iso_date(r.recordedAt),
            "photo": photo.id if photo else None,
            "photoCaption": photo.caption if photo else None,
        },
    ),
    "ToolDocumentation": ReferenceModel(
        delegate="tooldocumentation",
        order={"toolkitName": "asc"},
        search_fields=("toolkitName", "localName", "englishName", "artisanName"),
        workshop_where=lambda wid: {"workshopId": wid},
        artisan_field="artisanId",
        media_field="toolId",
        include={"location": True, "media": True, "artisanLinks": {"include": {"artisan": True}}},
        label=lambda r: str(r.toolkitName or ""),
        sublabel=lambda r: _joined(r.englishName, r.artisanName, r.place, _review_flag(r)),
        data=lambda r, photo: {
            "name": r.toolkitName,
            "localName": r.localName,
            "englishName": r.englishName,
            "material": r.material,
            "usedFor": r.processUsedIn,
            "cost": _money(r.replacementCost),
            "yearsInUse": r.yearsInUse,
            "maker": _translated(_MAKER_TYPE_TO_MAKER, r.maker),
            "traditionType": _translated(_TRADITION_TYPE_TO_TRADITION, r.traditionType),
            "craftName": r.craftName,
            "place": r.place,
            "artisanName": r.artisanName,
            "improvements": r.suggestionsForToolImprovement,
            "remarks": r.remarks,
            "lengthCm": _inches_to_cm(r.lengthInches),
            "breadthCm": _inches_to_cm(r.breadthInches),
            "heightCm": _inches_to_cm(r.heightInches),
            "measurementMethodNote": _measurement_method_note("tool", "ToolDocumentation", r),
            "heightAsRecorded": _decimal(r.height),
            "widthAsRecorded": _decimal(r.width),
            "thicknessAsRecorded": _decimal(r.thickness),
            "weightAsRecorded": _decimal(r.weight),
            "radiusAsRecorded": _decimal(r.radius),
            "recordState": _rel(r, "location", "state"),
            "recordDistrict": _rel(r, "location", "district"),
            "recordVillage": _rel(r, "location", "village"),
            "recordPincode": _rel(r, "location", "pincode"),
            "subjectLocation": _subject_point(_rel_obj(r, "location")),
            "recordMediaNote": _media_note(
                "tool", _rel_obj(r, "media"), numbered_prefix="STAGE_STEP_"
            ),
            "usedByArtisans": _linked_artisan_names(r),
            "documentedOn": _iso_date(r.recordedAt),
            "photo": photo.id if photo else None,
            "photoCaption": photo.caption if photo else None,
        },
    ),
    "Process": ReferenceModel(
        delegate="process",
        include={"product": True, "steps": True},
        order={"name": "asc"},
        search_fields=("name",),
        workshop_where=lambda wid: {"workshopId": wid},
        filter_field="productId",
        label=lambda r: str(r.name or ""),
        sublabel=lambda r: _joined(_rel(r, "product", "productName"), _review_flag(r)),
        data=lambda r, _photo: {
            "name": r.name,
            "notes": r.notes,
            "productName": _rel(r, "product", "productName"),
            "steps": _step_lines(r),
            "recordMediaNote": _process_media_note(r),
            "preProcessAvailable": r.preProcessAvailable,
            "documentedOn": _iso_date(r.recordedAt),
        },
    ),
    "Craft": ReferenceModel(
        delegate="craft",
        include={"media": True, "workshop": True, "subCategory": {"include": {"category": True}}},
        order={"name": "asc"},
        search_fields=("name", "localName", "category"),
        workshop_where=lambda wid: {
            "OR": [{"workshopId": wid}, {"workshops": {"some": {"workshopId": wid}}}]
        },
        media_field="craftId",
        offered_where={"status": "ACTIVE"},
        label=lambda r: str(r.name or ""),
        sublabel=lambda r: _joined(_craft_path(r), r.place),
        data=lambda r, photo: {
            "craftName": r.name,
            "craftLocalName": r.localName,
            "craftCategory": r.category,
            "craftPlace": r.place,
            "craftDescription": r.description,
            "craftDocumentedOn": _iso_date(r.recordedAt),
            "craftDocumentedAtWorkshop": _rel(r, "workshop", "title"),
            "craftMediaNote": _media_note("craft", _rel_obj(r, "media")),
            "craftPhoto": photo.id if photo else None,
            "craftPhotoCaption": photo.caption if photo else None,
        },
    ),
    "QuestionnaireInterview": ReferenceModel(
        delegate="questionnaireinterview",
        offered_where={"deletedAt": None},
        order={"title": "asc"},
        search_fields=("title", "place", "language"),
        workshop_where=lambda wid: {"workshopId": wid},
        include={
            "responses": {"include": {"question": True}},
            "artisans": True,
            "media": True,
            "workshop": True,
        },
        label=lambda r: str(r.title or ""),
        sublabel=lambda r: _joined(
            _iso_date(r.interviewDate),
            r.place,
            language_name(r.language),
            _interview_artisan_phrase(r),
            _review_flag(r),
        ),
        data=lambda r, _photo: {
            "interviewTitle": r.title,
            "interviewDate": _iso_date(r.interviewDate),
            "interviewPlace": r.place,
            "interviewLanguage": language_name(r.language),
            "interviewArtisanCount": _interview_artisan_count(r),
            "interviewSectionsCovered": _interview_sections_covered(r),
            "interviewQuestionsAnswered": _interview_questions_answered(r),
            "interviewLastAnsweredOn": _interview_last_answered(r),
            "interviewMediaNote": _media_note("interview", _rel_obj(r, "media")),
            "interviewDocumentedOn": _iso_date(r.recordedAt),
            "interviewDocumentedAtWorkshop": _rel(r, "workshop", "title"),
        },
    ),
    "Questionnaire": ReferenceModel(
        delegate="questionnaire",
        order={"title": "asc"},
        search_fields=("title", "description", "sourceFilename"),
        viewer_where=visible_questionnaire_where,
        offered_where={"deletedAt": None},
        label=lambda r: str(r.title or ""),
        sublabel=lambda r: _joined(
            questionnaire_kind_label(r.kind) if r.kind else "",
            "Shared" if r.isShared else "",
            r.sourceFilename,
        ),
        data=lambda r, _photo: {"name": r.title},
    ),
}


_TWO_PARENTS = sorted(
    model for model, spec in REFERENCE_MODELS.items() if spec.artisan_field and spec.filter_field
)
if _TWO_PARENTS:
    raise RuntimeError(
        "REFERENCE_MODELS " + ", ".join(_TWO_PARENTS) + " declare both artisan_field and "
        "filter_field; a model can be cascaded from ONE parent because ref_filter_by names a "
        "sibling field and never says which model it points at. Split the model or extend "
        "reference_options to take the parent's model from the registry."
    )


class _ProbeRow:
    def __getattr__(self, _name: str) -> None:
        return None


def reference_data_keys() -> dict[str, frozenset[str]]:
    return {
        model: frozenset(spec.data(_ProbeRow(), None)) for model, spec in REFERENCE_MODELS.items()
    }


def validate_reference_carry() -> list[str]:
    problems: list[str] = []
    produced = reference_data_keys()
    consumed: dict[str, set[str]] = {model: set() for model in REFERENCE_MODELS}

    for path, mapping in REFERENCE_HYDRATION.items():
        entity_key, _, field_key = path.partition(".")
        entity = next((e for _s, e in all_entities() if e.key == entity_key), None)
        spec = entity.field(field_key) if entity else None
        model = spec.ref_model if spec else ""
        if model not in REFERENCE_MODELS:
            continue
        consumed[model].update(mapping)
        for source_key in mapping:
            if source_key not in produced[model]:
                problems.append(
                    f"hydration {path}[{source_key!r}] reads a key that "
                    f"REFERENCE_MODELS[{model!r}].data never produces, so it copies nothing"
                )

    for model, keys in produced.items():
        orphans = sorted(keys - consumed[model] - _CARRY_EXEMPT.get(model, frozenset()))
        if orphans:
            problems.append(
                f"REFERENCE_MODELS[{model!r}].data produces {orphans}, which no hydration "
                "mapping consumes — the value is computed on every picker call and lands nowhere"
            )
    return problems


_CARRY_EXEMPT: dict[str, frozenset[str]] = {}


def _dw_entity(model: str) -> EntitySpec | None:
    return next((e for _s, e in all_entities() if e.name == model), None)


def _internal_carry_keys(entity: EntitySpec) -> tuple[str, ...]:
    tables = [
        *REFERENCE_HYDRATION.items(),
        *(
            (path, {**parts.get("fill", {}), **parts.get("hint", {})})
            for path, parts in REFERENCE_SUGGESTIONS.items()
        ),
    ]
    wanted = {
        source
        for path, mapping in tables
        for source in mapping
        if (owner := next((e for _s, e in all_entities() if e.key == path.partition(".")[0]), None))
        and (ref := owner.field(path.rpartition(".")[2])) is not None
        and ref.ref_model == entity.name
    }
    return tuple(f.key for f in entity.fields if f.key in wanted)


async def reference_options(
    record: Any,
    model: str,
    *,
    scope: str = REF_SCOPE_ALL,
    filter_by: str | None = None,
    search: str | None = None,
    limit: int = REFERENCE_LIMIT_DEFAULT,
    offset: int = 0,
    record_id: str | None = None,
    viewer: Any = None,
) -> dict[str, Any]:
    if scope not in REF_SCOPES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"scope must be one of {', '.join(sorted(REF_SCOPES))}",
        )
    take = max(1, min(int(limit or REFERENCE_LIMIT_DEFAULT), REFERENCE_LIMIT_MAX))
    skip = max(0, min(int(offset or 0), REFERENCE_OFFSET_MAX))
    wanted_id = (record_id or "").strip()

    entity = _dw_entity(model)
    if entity is not None:
        if filter_by:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"{model} cannot be filtered by another record",
            )
        answer = await _in_record_options(record, entity, search, take, record_id=wanted_id)
        if skip:
            answer = {**answer, "options": [], "truncated": False}
        return answer

    spec = REFERENCE_MODELS.get(model)
    if spec is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"Unknown reference model {model!r}; expected one of "
                f"{', '.join(sorted(REFERENCE_MODELS))} or an entity of this workshop"
            ),
        )

    clauses: list[dict[str, Any]] = []
    if spec.offered_where:
        clauses.append(dict(spec.offered_where))
    readable = await viewable_where(viewer)
    if readable:
        clauses.append(readable)

    if spec.viewer_where is not None:
        if viewer is None:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail=f"{model} options are per-account and this request names no account",
            )
        clauses.append(spec.viewer_where(viewer))

    scoped = False
    workshop_clause: dict[str, Any] | None = None
    if scope == REF_SCOPE_WORKSHOP and spec.workshop_where and record.workshopId:
        workshop_clause = spec.workshop_where(str(record.workshopId))
        clauses.append(workshop_clause)
        scoped = True

    filtered = False
    if filter_by:
        parent_column = spec.filter_field or spec.artisan_field
        if not parent_column:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"{model} cannot be filtered by another record",
            )
        parent_id: str | None = str(filter_by)
        if parent_column == spec.artisan_field:
            parent_id = await _artisan_id_behind(str(record.id), str(filter_by))
        if parent_id is None:
            return _reference_payload(model, scope, scoped, True, [], truncated=False)
        clauses.append({parent_column: parent_id})
        filtered = True

    term = (search or "").strip()
    if term:
        clauses.append({"OR": [{f: contains(term)} for f in spec.search_fields]})

    if wanted_id:
        clauses.append({"id": wanted_id})

    where: dict[str, Any] = {"AND": clauses} if clauses else {}
    rows = await getattr(db, spec.delegate).find_many(
        where=where,
        order=spec.order,
        take=take + 1,
        include=spec.include or None,
        **({"skip": skip} if skip else {}),
    )
    truncated = len(rows) > take
    rows = rows[:take]

    out_of_scope_row = None
    if wanted_id and not skip and not rows and workshop_clause is not None:
        found = await getattr(db, spec.delegate).find_many(
            where={"AND": [c for c in clauses if c is not workshop_clause]},
            order=spec.order,
            take=1,
            include=spec.include or None,
        )
        out_of_scope_row = found[0] if found else None

    photos = await _reference_photos(
        spec,
        [r.id for r in rows] + ([out_of_scope_row.id] if out_of_scope_row is not None else []),
    )
    return _reference_payload(
        model,
        scope,
        scoped,
        filtered,
        [_reference_option(spec, row, photos) for row in rows],
        truncated=truncated,
        out_of_scope_option=(
            None if out_of_scope_row is None else _reference_option(spec, out_of_scope_row, photos)
        ),
    )


def _reference_option(spec: Any, row: Any, photos: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": row.id,
        "label": spec.label(row),
        "sublabel": spec.sublabel(row),
        "data": {
            k: v
            for k, v in _reference_data(spec, row, photos.get(row.id)).items()
            if v not in (None, "")
        },
    }


def _reference_payload(
    model: str,
    scope: str,
    scoped: bool,
    filtered: bool,
    options: list[dict[str, Any]],
    *,
    truncated: bool,
    out_of_scope_option: dict[str, Any] | None = None,
    tentative: FieldSpec | None = None,
) -> dict[str, Any]:
    return {
        "model": model,
        "scope": scope,
        "scopedToWorkshop": scoped,
        "filtered": filtered,
        "truncated": truncated,
        "outOfScope": out_of_scope_option is not None,
        "outOfScopeOption": out_of_scope_option,
        "tentativeFirst": tentative is not None,
        "tentativeLabel": tentative.label if tentative is not None else "",
        "options": options,
    }


async def _artisan_id_behind(workshop_id: str, candidate: str) -> str | None:
    row = await db.dwstageentry.find_unique(where={"id": candidate})
    if row is None:
        return candidate
    if row.designWorkshopId != workshop_id:
        return None
    linked = (row.data or {}).get("artisanRef")
    return str(linked) if linked else None


MEASUREMENT_GRID_PURPOSE = "MEASUREMENT_GRID"

_PHOTO_PARENT_COLUMNS = frozenset({"artisanId", "craftId", "productId", "toolId"})


async def _reference_photos(spec: ReferenceModel, ids: list[str]) -> dict[str, ReferencePhoto]:
    if not spec.media_field or not ids:
        return {}
    column = spec.media_field
    if column not in _PHOTO_PARENT_COLUMNS:
        raise ValueError(f"Unsupported reference photo column: {column}")
    if not MEASUREMENT_GRID_PURPOSE.isidentifier():
        raise ValueError(f"Unsafe measurement-grid marker: {MEASUREMENT_GRID_PURPOSE!r}")
    rows = await db.query_raw(
        f"WITH candidate AS ("
        f'SELECT m."{column}" AS parent, m."id" AS id, m."caption" AS caption, '
        f'm."createdAt" AS created_at, ('
        f"COALESCE(m.\"extraMetadata\"->>'purpose', '') = '{MEASUREMENT_GRID_PURPOSE}' "
        f"OR COALESCE(m.\"caption\", '') LIKE '% grid (measurement) for %' "
        f"OR m.\"originalFilename\" LIKE 'grid-%' "
        f"OR m.\"originalFilename\" LIKE 'measure-grid-%'"
        f") AS is_grid "
        f'FROM "MediaFile" m '
        f'WHERE m."mediaType" = \'IMAGE\'::"MediaType" AND m."{column}" = ANY($1::text[])'
        f") "
        f"SELECT DISTINCT ON (parent) parent, id, caption FROM candidate "
        f"ORDER BY parent, is_grid ASC, created_at ASC, id ASC",
        ids,
    )
    return {
        str(row["parent"]): ReferencePhoto(
            id=str(row["id"]), caption=str(row.get("caption") or "").strip()
        )
        for row in rows
        if row.get("parent")
    }


TENTATIVE_FIELD_KEY = "isTentative"


def _tentative_field(entity: EntitySpec) -> FieldSpec | None:
    for field in entity.fields:
        if field.key == TENTATIVE_FIELD_KEY and field.type is FieldType.BOOL and not field.deprecated:
            return field
    return None


async def _in_record_options(
    record: Any, entity: EntitySpec, search: str | None, take: int, *, record_id: str = ""
) -> dict[str, Any]:
    rows = await db.dwstageentry.find_many(
        where={"designWorkshopId": record.id, "entityKey": entity.key, "deletedAt": None}
        | ({"OR": [{"id": record_id}, {"clientKey": record_id}]} if record_id else {}),
        order=[{"ordinal": "asc"}, {"createdAt": "asc"}, {"id": "asc"}],
    )
    label_key = entity.label_field or next((f.key for f in entity.fields if f.is_free_text), "")
    sub_key = next(
        (
            f.key
            for f in entity.fields
            if f.key != label_key and f.is_free_text and not f.deprecated
        ),
        "",
    )
    term = (search or "").strip().casefold()
    tentative = _tentative_field(entity)
    carried = _internal_carry_keys(entity)

    options: list[dict[str, Any]] = []
    for row in rows:
        data = dict(row.data or {})
        label = str(data.get(label_key) or "").strip() or entity.title
        sublabel = str(data.get(sub_key) or "").strip() if sub_key else ""
        if term and term not in label.casefold() and term not in sublabel.casefold():
            continue
        option: dict[str, Any] = {
            "id": row.id,
            "label": label,
            "sublabel": sublabel,
            "data": {k: data[k] for k in carried if data.get(k) not in (None, "", [])},
        }
        if tentative is not None:
            option["tentative"] = data.get(TENTATIVE_FIELD_KEY) is True
        options.append(option)

    if tentative is not None:
        options = [o for o in options if o["tentative"]] + [o for o in options if not o["tentative"]]

    truncated = len(options) > take
    return _reference_payload(
        entity.name,
        REF_SCOPE_WORKSHOP,
        True,
        False,
        options[:take],
        truncated=truncated,
        tentative=tentative,
    )


@dataclass(slots=True)
class PendingEntry:
    entity: EntitySpec
    data: dict[str, Any]
    previous: dict[str, Any]
    row_id: str | None
    ordinal: int
    client_key: str | None
    adopt_client_key: str | None = None
    previous_provenance: dict[str, Any] = dataclass_field(default_factory=dict)
    hydrated: dict[str, entry_provenance.HydrationSource] = dataclass_field(default_factory=dict)
    seen_version: int = 0
    error_scope: str = ""


def _clear_cascade_orphans(entries: list[PendingEntry]) -> None:
    for item in entries:
        for spec in item.entity.fields:
            mapping = REFERENCE_HYDRATION.get(f"{item.entity.key}.{spec.key}")
            if not mapping or not spec.ref_filter_by:
                continue
            if item.data.get(spec.key):
                continue
            if not str(item.previous.get(spec.key) or ""):
                continue
            parent_now = str(item.data.get(spec.ref_filter_by) or "")
            if not parent_now or parent_now == str(item.previous.get(spec.ref_filter_by) or ""):
                continue
            for target_key in mapping.values():
                target = item.entity.field(target_key)
                if target is None or target.type.is_multi:
                    continue
                item.data.pop(target_key, None)
                item.previous_provenance.pop(target_key, None)


def _has_value(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, (list, tuple, dict)):
        return bool(value)
    return True


async def derive_stage_craft_names(entries: list[PendingEntry]) -> None:
    linked = [e for e in entries if e.entity.key == "workshopSetup" and e.data.get("craftRef")]
    if not linked:
        return
    ids = sorted({str(e.data["craftRef"]) for e in linked})
    crafts = {c.id: c for c in await db.craft.find_many(where={"id": {"in": ids}})}
    for entry in linked:
        craft_id = str(entry.data["craftRef"])
        craft = crafts.get(craft_id)
        if craft_id != str((entry.previous or {}).get("craftRef") or "") and (
            craft is None or craft_master.status_of(craft) != craft_master.ACTIVE
        ):
            raise craft_master.craft_error("CRAFT_NOT_IN_LIST", 422)
        if craft is not None and (craft.name or "").strip():
            entry.data["craftName"] = craft.name


async def hydrate_entries(entries: list[PendingEntry], *, workshop_id: str = "") -> None:
    _clear_cascade_orphans(entries)

    wanted: dict[str, set[str]] = {}
    wanted_internal: dict[str, set[str]] = {}
    for item in entries:
        for spec in item.entity.fields:
            if spec.type is not FieldType.REF:
                continue
            if f"{item.entity.key}.{spec.key}" not in REFERENCE_HYDRATION:
                continue
            ref_id = item.data.get(spec.key)
            if not ref_id:
                continue
            if spec.ref_model in REFERENCE_MODELS:
                wanted.setdefault(spec.ref_model, set()).add(str(ref_id))
            elif _dw_entity(spec.ref_model) is not None:
                wanted_internal.setdefault(spec.ref_model, set()).add(str(ref_id))
    if wanted_internal and not workshop_id:
        logger.warning(
            "Internal reference carry skipped: %s named a workshop row but hydrate_entries was "
            "called without workshop_id, so there is nothing to scope the lookup to.",
            ", ".join(sorted(wanted_internal)),
        )
        wanted_internal = {}
    if not wanted and not wanted_internal:
        return

    resolved: dict[str, dict[str, dict[str, Any]]] = {}
    authors: dict[str, dict[str, str | None]] = {}

    async def _load(model: str, ids: set[str]) -> tuple[str, list[Any], dict[str, Any]]:
        model_spec = REFERENCE_MODELS[model]
        rows = await getattr(db, model_spec.delegate).find_many(
            where={"id": {"in": sorted(ids)}}, include=model_spec.include or None
        )
        return model, rows, await _reference_photos(model_spec, [r.id for r in rows])

    async def _load_internal() -> list[Any]:
        keys = sorted(
            {e.key for m in wanted_internal if (e := _dw_entity(m)) is not None}
        )
        if not keys:
            return []
        return await db.dwstageentry.find_many(
            where={
                "designWorkshopId": workshop_id,
                "entityKey": {"in": keys},
                "deletedAt": None,
            }
        )

    external_loads = [_load(model, ids) for model, ids in wanted.items()]
    internal_loads = [_load_internal()] if wanted_internal else []
    read_results = await gather_reads(*external_loads, *internal_loads)
    internal_rows: list[Any] = read_results[len(external_loads)] if internal_loads else []

    for model, rows, photos in read_results[: len(external_loads)]:
        model_spec = REFERENCE_MODELS[model]
        resolved[model] = {
            row.id: _reference_data(model_spec, row, photos.get(row.id)) for row in rows
        }
        authors[model] = {row.id: getattr(row, "createdById", None) for row in rows}

    model_of_entity = {
        e.key: m for m in wanted_internal if (e := _dw_entity(m)) is not None
    }
    for row in internal_rows:
        model = model_of_entity.get(row.entityKey, "")
        if not model:
            continue
        data = dict(row.data or {})
        bucket = resolved.setdefault(model, {})
        author_bucket = authors.setdefault(model, {})
        for key in (row.id, getattr(row, "clientKey", None)):
            if key:
                bucket[str(key)] = data
                author_bucket[str(key)] = getattr(row, "createdById", None)

    for item in entries:
        for spec in item.entity.fields:
            mapping = REFERENCE_HYDRATION.get(f"{item.entity.key}.{spec.key}")
            if not mapping:
                continue
            ref_id = item.data.get(spec.key)
            if not ref_id:
                continue
            source = resolved.get(spec.ref_model, {}).get(str(ref_id))
            if source is None:
                continue
            was = str(item.previous.get(spec.key) or "")
            replaced = bool(was) and was != str(ref_id)
            if replaced:
                for target_key in mapping.values():
                    target = item.entity.field(target_key)
                    if target is None or target.type.is_multi:
                        continue
                    item.data.pop(target_key, None)
                    item.previous_provenance.pop(target_key, None)
            for source_key, target_key in mapping.items():
                value = source.get(source_key)
                if value in (None, ""):
                    continue
                target = item.entity.field(target_key)
                if target is None or target.deprecated:
                    continue
                existing = item.data.get(target_key)
                if _has_value(existing) and (not replaced or target.type.is_multi):
                    continue
                if target.type.is_multi and not isinstance(value, (list, tuple)):
                    value = [value]
                cleaned, error = coerce_value(target, value)
                if error or cleaned is None:
                    continue
                item.data[target_key] = cleaned
                item.hydrated[target_key] = entry_provenance.HydrationSource(
                    model=spec.ref_model,
                    record_id=str(ref_id),
                    source_key=source_key,
                    author_id=authors.get(spec.ref_model, {}).get(str(ref_id)),
                )



def named_designer_team(
    lead_id: str | None, everyone_ids: Sequence[str] | None
) -> tuple[str | None, list[str]]:
    ordered: list[str] = []
    seen: set[str] = set()
    lead = (lead_id or "").strip() or None
    if lead:
        ordered.append(lead)
        seen.add(lead)
    for raw in everyone_ids or ():
        candidate = (raw or "").strip()
        if candidate and candidate not in seen:
            ordered.append(candidate)
            seen.add(candidate)
    if lead is None and ordered:
        lead = ordered[0]
    return lead, ordered


async def assert_every_designer_may_be_named(
    user_ids: set[str], *, workshop_id: str | None = None, appointing: str | None = None
) -> None:
    await _assert_every_id_may_be_granted(user_ids, workshop_id=workshop_id, appointing=appointing)


async def attach_the_named_designer(
    workshop_id: str,
    designer_id: str,
    *,
    granted_by_id: str,
    creator_id: str,
    client: Any = None,
) -> bool:
    if designer_id == creator_id:
        return False
    await add_one_viewer(
        db if client is None else client,
        workshop_id=workshop_id,
        user_id=designer_id,
        granted_by_id=granted_by_id,
    )
    return True


async def attach_the_named_designers(
    workshop_id: str,
    designer_ids: Sequence[str],
    *,
    granted_by_id: str,
    creator_id: str,
) -> list[str]:
    granted: list[str] = []
    try:
        for designer_id in designer_ids:
            if await attach_the_named_designer(
                workshop_id,
                designer_id,
                granted_by_id=granted_by_id,
                creator_id=creator_id,
            ):
                granted.append(designer_id)
    except Exception:
        logger.exception(
            "design workshop %s: naming its designers failed part way through. Rows were written "
            "for %s of %s named; the workshop exists and the rest must be added from the viewers "
            "panel. Granted: %s",
            workshop_id,
            len(granted),
            len(designer_ids),
            ", ".join(granted) or "(none)",
        )
        raise
    return granted


async def open_design_workshop(
    *,
    actor: Any,
    columns: Mapping[str, Any],
    designer_id: str | None = None,
    designer_ids: Sequence[str] = (),
    seeded: Mapping[str, Any] | None = None,
) -> Any:
    wanted = set(designer_ids) - {actor.id}
    if wanted:
        await assert_every_designer_may_be_named(wanted)

    record = await db.designworkshop.create(
        data={
            **dict(columns),
            "createdById": actor.id,
            "schemaVersion": registry_version(),
            "status": "DRAFT",
        }
    )
    if designer_ids:
        await attach_the_named_designers(
            record.id,
            list(designer_ids),
            granted_by_id=actor.id,
            creator_id=record.createdById,
        )
    return await seed_designer_prefill(
        record, actor, designer_id=designer_id, extra=dict(seeded or {})
    )


async def seed_designer_prefill(
    record: Any,
    actor: Any,
    *,
    designer_id: str | None = None,
    extra: Mapping[str, Any] | None = None,
) -> Any:
    try:
        values: dict[str, Any] = {
            **(await prefill_from_profile(designer_id or actor.id)),
            **dict(extra or {}),
        }
        if not values:
            return record
        header: dict[str, Any] = {}
        for spec in stages():
            for entity in spec.entities:
                if entity.cardinality is not Cardinality.SINGLETON:
                    continue
                known = {f.key for f in entity.fields if not f.deprecated}
                subset = {k: v for k, v in values.items() if k in known}
                if not subset:
                    continue
                clean, refused = validate_entry(entity, subset, enforce_required=False)
                if refused:
                    logger.warning(
                        "Designer prefill dropped %d value(s) on %s.%s for workshop %s: %s",
                        len(refused),
                        spec.key,
                        entity.key,
                        getattr(record, "id", None),
                        "; ".join(f"{k}: {v}" for k, v in sorted(refused.items())),
                    )
                if not clean:
                    continue
                await db.dwstageentry.create(
                    data={
                        "designWorkshopId": record.id,
                        "stageKey": spec.key,
                        "entityKey": entity.key,
                        "ordinal": 0,
                        "data": _json(clean),
                        "searchText": design_workshop_data.entry_search_text(entity, clean) or None,
                        "clientKey": singleton_client_key(spec.key),
                        "fieldProvenance": _json(
                            entry_provenance.merge_entry_provenance(
                                previous={},
                                previous_data={},
                                new_data=clean,
                                hydrated={},
                                user=actor,
                            )
                        ),
                        "createdById": actor.id,
                    }
                )
                for column, value in promoted_values(entity.key, clean).items():
                    if column in ("startDate", "endDate"):
                        continue
                    if isinstance(value, str) and value:
                        header[column] = value[:220]
        if header:
            return await db.designworkshop.update(where={"id": record.id}, data=header)
        return record
    except Exception:
        logger.exception("Designer prefill failed for workshop %s", getattr(record, "id", None))
        return record


SINGLETON_CLIENT_KEY = "__dw_singleton__"


def singleton_client_key(stage_key: str) -> str:
    return f"{SINGLETON_CLIENT_KEY}:{stage_key}"


def _reserved_key_upgrade(row, entity_key: str, singleton_key: str, existing) -> str | None:
    if row is None or row.clientKey == singleton_key:
        return None
    if any(r.entityKey == entity_key and r.clientKey == singleton_key for r in existing):
        return None
    return singleton_key


def refused_answer_count(errors: Mapping[str, Any]) -> int:
    return sum(len(fields) if isinstance(fields, Mapping) else 1 for fields in errors.values())


@dataclass
class _RowUpdate:
    row_id: str
    seen_version: int
    columns: dict[str, Any]
    values: dict[str, Any]
    scope: str
    entity_key: str
    changed: bool


def _content_changed(
    creates: list[dict[str, Any]], updates: list[_RowUpdate], removed: list[str]
) -> bool:
    return bool(creates) or bool(removed) or any(planned.changed for planned in updates)


@dataclass(frozen=True)
class _CreatePlan:
    scope: str
    values: dict[str, Any]


class _StageVersionConflict(Exception):
    def __init__(self, row_ids: list[str]) -> None:
        self.row_ids = row_ids
        super().__init__(f"{len(row_ids)} stage row(s) were written by another request")


STAGE_ROW_CONFLICT_MESSAGE = (
    "Someone else saved this entry first — reopen the stage to see the latest before saving again."
)

STAGE_ROW_CONFLICT_KEY = "_row"

_STAGE_WRITE_ATTEMPTS = 3

_ARTISAN_REF_ENTITIES = frozenset({"participant", "existingProduct"})


async def _redirect_artisan_refs(payload: Any) -> None:
    from app.services.artisan_merge import merged_among, resolve

    held: list[tuple[dict[str, Any], str]] = []
    for entry in getattr(payload, "entries", None) or []:
        if getattr(entry, "entityKey", None) not in _ARTISAN_REF_ENTITIES:
            continue
        data = getattr(entry, "data", None)
        ref = data.get("artisanRef") if isinstance(data, dict) else None
        if isinstance(ref, str) and ref:
            held.append((data, ref))
    if not held:
        return
    merged = await merged_among([ref for _, ref in held])
    for data, ref in held:
        if ref in merged:
            survivor = await resolve(ref)
            if survivor:
                data["artisanRef"] = survivor


async def save_stage(
    workshop_id: str,
    spec: StageSpec,
    payload: Any,
    user: Any,
    *,
    resubmits: bool = False,
) -> dict[str, Any]:
    await _redirect_artisan_refs(payload)
    definition, existing, header_row = await gather_reads(
        custom_sections.load_definition(workshop_id),
        db.dwstageentry.find_many(where={"designWorkshopId": workshop_id, "stageKey": spec.key}),
        db.designworkshop.find_unique(where={"id": workshop_id}),
    )
    custom_specs = definition.fields_for(spec.key)
    singleton_key = singleton_client_key(spec.key)
    workshop_status = str(getattr(header_row, "status", "DRAFT") or "DRAFT")
    live = [row for row in existing if row.deletedAt is None]
    by_id = {row.id: row for row in live}
    by_client_key = {
        (row.entityKey, row.clientKey): row
        for row in sorted(existing, key=lambda r: r.deletedAt is None)
        if row.clientKey
    }

    entities = {e.key: e for e in spec.entities}
    errors: dict[str, Any] = {}
    dropped: list[str] = []
    touched_ids: set[str] = set()
    touched_entities: set[str] = set()
    merged_entities: set[str] = set()
    claimed_client_keys: set[tuple[str, str]] = set()

    creates: list[dict[str, Any]] = []
    updates: list[_RowUpdate] = []
    create_plans: dict[tuple[str, str | None], _CreatePlan] = {}
    pending: list[PendingEntry] = []
    pending_singletons: dict[str, PendingEntry] = {}

    custom_entry = next(
        (e for e in payload.entries if e.entityKey == custom_sections.CUSTOM_ENTITY_KEY), None
    )
    custom_row = (
        next(
            (
                r
                for r in live
                if r.entityKey == custom_sections.CUSTOM_ENTITY_KEY and r.clientKey == singleton_key
            ),
            None,
        )
        or next((r for r in live if r.entityKey == custom_sections.CUSTOM_ENTITY_KEY), None)
        or next((r for r in existing if r.entityKey == custom_sections.CUSTOM_ENTITY_KEY), None)
    )
    custom_write = custom_sections.plan_custom_write(
        custom_specs,
        sent=None if custom_entry is None else (custom_entry.data or {}),
        previous=dict(custom_row.data or {}) if custom_row is not None else {},
        merge=bool(custom_entry is not None and custom_entry.merge),
        submit=payload.submit,
    )
    custom_dropped = list(custom_write.dropped)
    custom_to_store = custom_write.data

    if custom_write.errors:
        errors[custom_sections.CUSTOM_ENTITY_KEY] = custom_write.errors

    for index, entry in enumerate(payload.entries):
        if entry.entityKey == custom_sections.CUSTOM_ENTITY_KEY:
            continue
        entity = entities.get(entry.entityKey)
        if entity is None:
            dropped.append(entry.entityKey)
            continue
        touched_entities.add(entity.key)

        known = {f.key for f in entity.fields}
        dropped.extend(
            f"{entity.key}.{k}" for k in entry.data if k not in known and not k.startswith("_")
        )

        clean, entry_errors = validate_entry(entity, entry.data, enforce_required=payload.submit)
        error_scope = (
            entity.key if entity.cardinality is Cardinality.SINGLETON else f"{entity.key}[{index}]"
        )
        if entry_errors:
            errors[error_scope] = entry_errors

        if entry.merge:
            merged_entities.add(entity.key)

        row = None
        if entry.entryId:
            row = by_id.get(entry.entryId)
            if row is not None and row.entityKey != entity.key:
                dropped.append(f"{entity.title}: an entry from a different list was sent here")
                row = None
            elif row is not None and row.id in touched_ids:
                dropped.append(f"{entity.title}: the same entry was sent twice")
                row = None
        client_key = str(entry.data["_clientKey"]) if entry.data.get("_clientKey") else None
        if row is None and client_key:
            row = by_client_key.get((entity.key, client_key))
            if row is None and (entity.key, client_key) in claimed_client_keys:
                dropped.append(f"{entity.title}: the same entry was sent twice")
                client_key = None
        if row is None and entity.cardinality is Cardinality.SINGLETON:
            row = (
                next(
                    (r for r in live if r.entityKey == entity.key and r.clientKey == singleton_key),
                    None,
                )
                or next((r for r in live if r.entityKey == entity.key), None)
                or next((r for r in existing if r.entityKey == entity.key), None)
            )

        ordinal = entry.ordinal if entry.ordinal is not None else index
        if entity.cardinality is Cardinality.SINGLETON:
            ordinal = 0
            client_key = singleton_key

        previous: dict[str, Any] = {}
        previous_provenance: dict[str, Any] = {}
        if row is not None:
            touched_ids.add(row.id)
            previous = dict(row.data or {})
            previous_provenance = entry_provenance.entry_provenance(row)
            if entry_errors:
                for bad_key in entry_errors:
                    if bad_key in previous:
                        clean[bad_key] = previous[bad_key]

        if entry.merge and previous:
            clean = {**previous, **clean}

        held = pending_singletons.get(entity.key)
        if held is not None:
            held.data.update(clean)
            dropped.append(f"{entity.title}: the same answers were sent twice and were combined")
            continue

        item = PendingEntry(
            entity=entity,
            data=clean,
            previous=previous,
            row_id=row.id if row is not None else None,
            ordinal=ordinal,
            client_key=client_key,
            previous_provenance=previous_provenance,
            adopt_client_key=(
                _reserved_key_upgrade(row, entity.key, singleton_key, existing)
                if entity.cardinality is Cardinality.SINGLETON
                else None
            ),
            seen_version=int(getattr(row, "version", 0) or 0) if row is not None else 0,
            error_scope=error_scope,
        )
        pending.append(item)
        if entity.cardinality is Cardinality.SINGLETON:
            pending_singletons[entity.key] = item
        if client_key:
            claimed_client_keys.add((entity.key, client_key))

    await hydrate_entries(pending, workshop_id=workshop_id)
    await derive_stage_craft_names(pending)

    if spec.key in stage_computed.STAGES_WITH_COMPUTED:
        swept = (touched_entities - merged_entities) | set(payload.emptiedEntities)
        survivors: dict[str, list[dict[str, Any]]] = {}
        for item in pending:
            survivors.setdefault(item.entity.key, []).append(item.data)
        sent_ids = {item.row_id for item in pending if item.row_id}
        for row in live:
            entity = entities.get(row.entityKey)
            if entity is None or entity.cardinality is Cardinality.SINGLETON or row.id in sent_ids:
                continue
            if payload.replaceCollections and row.entityKey in swept:
                continue
            survivors.setdefault(row.entityKey, []).append(dict(row.data or {}))

        def singleton_for(entity_key: str) -> PendingEntry | None:
            held = pending_singletons.get(entity_key)
            if held is not None:
                return held
            entity = entities.get(entity_key)
            if entity is None:
                return None
            row = next((r for r in live if r.entityKey == entity_key), None)
            item = PendingEntry(
                entity=entity,
                data=dict(row.data or {}) if row is not None else {},
                previous=dict(row.data or {}) if row is not None else {},
                row_id=row.id if row is not None else None,
                ordinal=0,
                client_key=singleton_key,
                previous_provenance=entry_provenance.entry_provenance(row) if row is not None else {},
                adopt_client_key=(
                    _reserved_key_upgrade(row, entity_key, singleton_key, existing)
                    if row is not None
                    else None
                ),
                seen_version=int(getattr(row, "version", 0) or 0) if row is not None else 0,
                error_scope=entity_key,
            )
            pending.append(item)
            pending_singletons[entity_key] = item
            return item

        await stage_computed.apply_on_save(workshop_id, spec.key, pending, survivors, singleton_for)

    copied_marks: dict[str, dict[str, Any]] = {}
    confirm_copied = [str(key) for key in (getattr(payload, "confirmCopied", None) or [])]
    copied_from = dict(getattr(payload, "copiedFrom", None) or {})
    singleton_item = pending_singletons.get(spec.singleton.key) if spec.singleton else None
    if copied_from and singleton_item is not None:
        copied_marks = await workshop_copy.verified_copies(
            copied_from, spec, singleton_item.data, workshop_id, user
        )

    for item in pending:
        field_provenance = entry_provenance.merge_entry_provenance(
            previous=item.previous_provenance,
            previous_data=item.previous,
            new_data=item.data,
            hydrated=item.hydrated,
            user=user,
        )
        if item is singleton_item and (copied_marks or confirm_copied):
            field_provenance = workshop_copy.mark_provenance(
                field_provenance, copied_marks, confirm_copied, user
            )
        search_text = design_workshop_data.entry_search_text(item.entity, item.data) or None
        if item.row_id is not None:
            row_now = by_id.get(item.row_id)
            changed = (
                item.data != item.previous
                or row_now is None
                or int(getattr(row_now, "ordinal", 0) or 0) != int(item.ordinal)
            )
            updates.append(
                _RowUpdate(
                    row_id=item.row_id,
                    seen_version=item.seen_version,
                    columns={
                        "data": _json(item.data),
                        "searchText": search_text,
                        "ordinal": item.ordinal,
                        "deletedAt": None,
                        "fieldProvenance": _json(field_provenance),
                        **({"clientKey": item.adopt_client_key} if item.adopt_client_key else {}),
                    },
                    values=item.data,
                    scope=item.error_scope,
                    entity_key=item.entity.key,
                    changed=changed,
                )
            )
        else:
            creates.append(
                {
                    "designWorkshopId": workshop_id,
                    "stageKey": spec.key,
                    "entityKey": item.entity.key,
                    "ordinal": item.ordinal,
                    "data": _json(item.data),
                    "searchText": search_text,
                    "fieldProvenance": _json(field_provenance),
                    "clientKey": item.client_key,
                    "createdById": user.id,
                }
            )
            create_plans[(item.entity.key, item.client_key)] = _CreatePlan(
                scope=item.error_scope, values=item.data
            )

    if custom_to_store is not None:
        custom_provenance = entry_provenance.merge_entry_provenance(
            previous=entry_provenance.entry_provenance(custom_row) if custom_row else {},
            previous_data=dict(custom_row.data or {}) if custom_row is not None else {},
            new_data=custom_to_store,
            hydrated={},
            user=user,
        )
        custom_search_text = (
            design_workshop_data.custom_search_text(custom_specs, custom_to_store) or None
        )
        if custom_row is not None:
            custom_key_upgrade = _reserved_key_upgrade(
                custom_row, custom_sections.CUSTOM_ENTITY_KEY, singleton_key, existing
            )
            updates.append(
                _RowUpdate(
                    row_id=custom_row.id,
                    seen_version=int(getattr(custom_row, "version", 0) or 0),
                    columns={
                        "data": _json(custom_to_store),
                        "searchText": custom_search_text,
                        "ordinal": 0,
                        "deletedAt": None,
                        "fieldProvenance": _json(custom_provenance),
                        **({"clientKey": custom_key_upgrade} if custom_key_upgrade else {}),
                    },
                    values=custom_to_store,
                    scope=custom_sections.CUSTOM_ENTITY_KEY,
                    entity_key=custom_sections.CUSTOM_ENTITY_KEY,
                    changed=(
                        custom_to_store != dict(custom_row.data or {})
                        or custom_row.deletedAt is not None
                    ),
                )
            )
        else:
            creates.append(
                {
                    "designWorkshopId": workshop_id,
                    "stageKey": spec.key,
                    "entityKey": custom_sections.CUSTOM_ENTITY_KEY,
                    "ordinal": 0,
                    "data": _json(custom_to_store),
                    "searchText": custom_search_text,
                    "fieldProvenance": _json(custom_provenance),
                    "clientKey": singleton_key,
                    "createdById": user.id,
                }
            )
            create_plans[(custom_sections.CUSTOM_ENTITY_KEY, singleton_key)] = _CreatePlan(
                scope=custom_sections.CUSTOM_ENTITY_KEY, values=custom_to_store
            )

    removed: list[str] = []
    if payload.replaceCollections:
        collection_keys = {e.key for e in spec.entities if e.cardinality is Cardinality.COLLECTION}
        sweep_entities = (
            (touched_entities - merged_entities) | set(payload.emptiedEntities)
        ) & collection_keys
        for row in live:
            if row.entityKey not in sweep_entities:
                continue
            if row.id in touched_ids:
                continue
            entity = entities.get(row.entityKey)
            if entity is None or entity.cardinality is Cardinality.SINGLETON:
                continue
            removed.append(row.id)

    handed_in: dict[str, bool] = {}

    async def write_everything() -> None:
        handed_in.clear()
        async with db.tx() as tx:
            conflicted: list[str] = []
            for planned in updates:
                written = await tx.dwstageentry.update_many(
                    where={"id": planned.row_id, "version": planned.seen_version},
                    data={**planned.columns, "version": {"increment": 1}},
                )
                if not written:
                    conflicted.append(planned.row_id)
            if conflicted:
                raise _StageVersionConflict(conflicted)
            if creates:
                await tx.dwstageentry.create_many(data=creates)
            if removed:
                await tx.dwstageentry.update_many(
                    where={"id": {"in": removed}}, data={"deletedAt": datetime.now(UTC)}
                )
            promoted, promoted_entities = _promotions_from_plan(creates, updates, create_plans)
            header: dict[str, Any] = {"schemaVersion": registry_version()}
            header.update(_coerce_promoted(promoted, promoted_entities))
            if workshop_status == "DRAFT":
                header["status"] = "IN_PROGRESS"
            resubmission: dict[str, Any] = {}
            if (
                resubmits
                and workshop_status == "NEEDS_REVISION"
                and _content_changed(creates, updates, removed)
            ):
                resubmission = design_workshop_review_loop.presubmission_header(workshop_status)
            written = await tx.designworkshop.update_many(
                where={"id": workshop_id, "NOT": design_workshop_approvals.FROZEN_WHERE},
                data=header,
            )
            if written != 1:
                frozen = await tx.designworkshop.find_unique(where={"id": workshop_id})
                raise HTTPException(
                    status_code=status.HTTP_403_FORBIDDEN,
                    detail=design_workshop_approvals.frozen_refusal(
                        getattr(frozen, "status", None), getattr(frozen, "handedOnAt", None)
                    )
                    or design_workshop_approvals.FROZEN_APPROVED,
                )
            if resubmission:
                handed_in["now"] = (
                    await tx.designworkshop.update_many(
                        where={"id": workshop_id, "status": "NEEDS_REVISION"}, data=resubmission
                    )
                    == 1
                )

    absorbed_once = False
    attempts = 0
    while True:
        attempts += 1
        try:
            await write_everything()
            break
        except UniqueViolationError:
            if absorbed_once:
                raise
            absorbed_once = True
            creates, updates = await _absorb_key_collisions(
                workshop_id, spec.key, creates, updates, create_plans
            )
        except _StageVersionConflict as clash:
            if attempts > _STAGE_WRITE_ATTEMPTS:
                raise
            updates, refused = await _settle_version_conflicts(
                workshop_id,
                spec.key,
                updates,
                set(clash.row_ids),
                surrender=attempts == _STAGE_WRITE_ATTEMPTS,
            )
            for lost in refused:
                errors.setdefault(lost.scope, {})[STAGE_ROW_CONFLICT_KEY] = (
                    STAGE_ROW_CONFLICT_MESSAGE
                )

    if handed_in.get("now"):
        from app.services.design_workshop_review_aids import capture_round_snapshot

        await capture_round_snapshot(workshop_id)
    rows = await entry_rows(workshop_id, stage_key=spec.key)
    completeness = workshop_completeness(rows, definition=definition).get(spec.key)
    queued = await enqueue_stage_transcriptions(
        rows, user.id, viewer=user, design_workshop_id=workshop_id
    )
    consent_note = ""
    if audio_references(rows):
        try:
            verdict = await dictation_consent.workshop_verdict(workshop_id, send=dictation_consent.MEDIA)
        except Exception as exc:  # noqa: BLE001 - the note is advisory; the queue gate fails closed.
            logger.warning("save_stage: consent read for %s failed (%s)", spec.key, exc)
            consent_note = dictation_consent.UNREADABLE
        else:
            if not verdict.may_send:
                consent_note = verdict.refusal or ""
    return {
        "stageKey": spec.key,
        "saved": len(creates) + len(updates),
        "created": len(creates),
        "createdByEntity": dict(Counter(str(row.get("entityKey")) for row in creates)),
        "updated": len(updates),
        "removed": len(removed),
        "errors": errors,
        "refusedAnswers": refused_answer_count(errors),
        "droppedKeys": sorted(set(dropped)),
        "droppedCustomKeys": sorted(set(custom_dropped)),
        "completeness": completeness,
        "transcriptionsQueued": len(queued),
        "transcriptionConsentRefusal": consent_note,
        "schemaVersion": registry_version(),
        "customSchemaVersion": definition.version,
    }


async def _absorb_key_collisions(
    workshop_id: str,
    stage_key: str,
    creates: list[dict[str, Any]],
    updates: list[_RowUpdate],
    create_plans: Mapping[tuple[str, str | None], _CreatePlan] | None = None,
) -> tuple[list[dict[str, Any]], list[_RowUpdate]]:
    rows = await db.dwstageentry.find_many(
        where={"designWorkshopId": workshop_id, "stageKey": stage_key}
    )
    taken = {(row.entityKey, row.clientKey): row.id for row in rows if row.clientKey}

    rows_by_id = {row.id: row for row in rows}
    plans = create_plans or {}
    absorbed: list[_RowUpdate] = []
    for planned in updates:
        adopted = planned.columns.get("clientKey")
        row = rows_by_id.get(planned.row_id)
        holder = taken.get((row.entityKey, adopted)) if adopted and row else None
        fields = (
            {key: value for key, value in planned.columns.items() if key != "clientKey"}
            if holder is not None and holder != planned.row_id
            else planned.columns
        )
        absorbed.append(replace(planned, columns=fields))

    still_creates: list[dict[str, Any]] = []
    for data in creates:
        key = (data["entityKey"], data.get("clientKey"))
        row_id = taken.get(key)
        if row_id is None:
            still_creates.append(data)
            continue
        winner = rows_by_id.get(row_id)
        plan = plans.get(key)
        absorbed.append(
            _RowUpdate(
                row_id=row_id,
                seen_version=int(getattr(winner, "version", 0) or 0) if winner else 0,
                columns={
                    "data": data["data"],
                    "searchText": data["searchText"],
                    "ordinal": data["ordinal"],
                    "deletedAt": None,
                    "fieldProvenance": data["fieldProvenance"],
                },
                values=plan.values if plan is not None else {},
                scope=plan.scope if plan is not None else data["entityKey"],
                entity_key=data["entityKey"],
                changed=True,
            )
        )
    return still_creates, absorbed


async def _settle_version_conflicts(
    workshop_id: str,
    stage_key: str,
    updates: list[_RowUpdate],
    conflicted: set[str],
    *,
    surrender: bool = False,
) -> tuple[list[_RowUpdate], list[_RowUpdate]]:
    rows = await db.dwstageentry.find_many(
        where={"designWorkshopId": workshop_id, "stageKey": stage_key}
    )
    fresh = {row.id: row for row in rows}
    kept: list[_RowUpdate] = []
    refused: list[_RowUpdate] = []
    for planned in updates:
        if planned.row_id not in conflicted:
            kept.append(planned)
            continue
        row = fresh.get(planned.row_id)
        if row is None:
            refused.append(planned)
            continue
        if not surrender and dict(row.data or {}) == planned.values:
            kept.append(replace(planned, seen_version=int(getattr(row, "version", 0) or 0)))
            continue
        refused.append(planned)
    return kept, refused


def _json(data: dict[str, Any]) -> Any:
    from prisma import Json

    return Json(data)


def _promotions_from_plan(
    creates: list[dict[str, Any]],
    updates: list[_RowUpdate],
    create_plans: Mapping[tuple[str, str | None], _CreatePlan],
) -> tuple[dict[str, Any], set[str]]:
    promoted: dict[str, Any] = {}
    contributed: set[str] = set()
    for planned in updates:
        contributed.add(planned.entity_key)
        promoted.update(promoted_values(planned.entity_key, planned.values))
    for data in creates:
        entity_key = str(data["entityKey"])
        plan = create_plans.get((entity_key, data.get("clientKey")))
        if plan is None:
            continue
        contributed.add(entity_key)
        promoted.update(promoted_values(entity_key, plan.values))
    return promoted, contributed


def _coerce_promoted(promoted: dict[str, Any], contributing_entities: set[str]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for path, column in PROMOTED_COLUMNS.items():
        entity_key = path.partition(".")[0]
        if entity_key not in contributing_entities:
            continue
        value = promoted.get(column)
        if value in (None, ""):
            if column == "title":
                continue
            out[column] = None
        elif column in ("startDate", "endDate"):
            try:
                out[column] = datetime.fromisoformat(str(value)[:10]).replace(tzinfo=UTC)
            except ValueError:
                out[column] = None
        else:
            out[column] = str(value)[:220]
    return out



def workshop_completeness(entries: list[Any], *, definition: Any = None) -> dict[str, Any]:
    singletons: dict[str, dict[str, Any]] = {}
    collections: dict[str, dict[str, list[dict[str, Any]]]] = {}
    custom_values: dict[str, dict[str, Any]] = {}
    cardinality = {e.key: e.cardinality for s in stages() for e in s.entities}

    for row in entries:
        data = dict(row.data or {})
        if row.entityKey == custom_sections.CUSTOM_ENTITY_KEY:
            custom_values[row.stageKey] = data
        elif cardinality.get(row.entityKey) is Cardinality.SINGLETON:
            singletons[row.stageKey] = workshop_copy.counted_data(
                row.stageKey, row.entityKey, data, getattr(row, "fieldProvenance", None)
            )
        else:
            collections.setdefault(row.stageKey, {}).setdefault(row.entityKey, []).append(data)

    fields_by_stage = definition.fields_by_stage() if definition is not None else {}

    out: dict[str, Any] = {}
    for spec in stages():
        score = stage_completeness(
            spec,
            singletons.get(spec.key, {}),
            collections.get(spec.key, {}),
            custom_fields=fields_by_stage.get(spec.key, ()),
            custom_values=custom_values.get(spec.key, {}),
        )
        out[spec.key] = {
            "stageKey": score.stage_key,
            "number": spec.number,
            "title": score.title,
            "requiredTotal": score.required_total,
            "requiredFilled": score.required_filled,
            "optionalTotal": score.optional_total,
            "optionalFilled": score.optional_filled,
            "percent": score.percent,
            "isComplete": score.is_complete,
            "collectionCounts": score.collection_counts,
            "missing": list(score.missing),
        }
    return out



async def report_survey_sittings(record: Any) -> int | None:
    if design_workshop_approvals.frozen_refusal(
        getattr(record, "status", None), getattr(record, "handedOnAt", None)
    ):
        return None
    return await stage_computed.market_survey_sittings(record.id)


def assemble_workshop_data(
    record: Any, entries: list[Any], *, survey_sittings: int | None = None
) -> WorkshopData:
    cardinality = {e.key: e.cardinality for s in stages() for e in s.entities}
    singletons: dict[str, dict[str, Any]] = {}
    collections: dict[str, dict[str, list[dict[str, Any]]]] = {}

    for row in sorted(entries, key=lambda r: r.ordinal):
        data = dict(row.data or {})
        data["_entryId"] = row.id
        if row.entityKey == custom_sections.CUSTOM_ENTITY_KEY:
            continue
        if cardinality.get(row.entityKey) is Cardinality.SINGLETON:
            singletons[row.stageKey] = data
        else:
            collections.setdefault(row.stageKey, {}).setdefault(row.entityKey, []).append(data)

    stage_computed.refresh_counts(
        singletons,
        collections,
        sittings=survey_sittings,
        client_keys={
            row.id: key for row in entries if (key := getattr(row, "clientKey", None))
        },
    )

    return WorkshopData(
        workshop_id=record.id,
        title=record.title,
        singletons=singletons,
        collections=collections,
        field_provenance=entry_provenance.resolve_entry_provenance(entries),
        generated_at=datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z"),
    )


def reference_ids(entries: list[Any]) -> dict[str, set[str]]:
    ref_keys: dict[str, dict[str, str]] = {}
    for spec in stages():
        for entity in spec.entities:
            keys = {
                f.key: f.ref_model
                for f in entity.fields
                if f.type in (FieldType.REF, FieldType.MULTI_ENUM)
                and f.ref_model in REFERENCE_MODELS
            }
            if keys:
                ref_keys[entity.key] = keys

    found: dict[str, set[str]] = {}
    for row in entries:
        keys = ref_keys.get(row.entityKey)
        if not keys:
            continue
        data = row.data or {}
        for key, model in keys.items():
            value = data.get(key)
            if isinstance(value, str) and value:
                found.setdefault(model, set()).add(value)
            elif isinstance(value, (list, tuple)):
                for token in value:
                    if isinstance(token, str) and token.strip():
                        found.setdefault(model, set()).add(token.strip())
    return found


def _reference_place(row: Any, model: str) -> tuple[str, str, str]:
    if model != "Artisan":
        return (str(getattr(row, "place", "") or ""), "", "")
    location = getattr(row, "location", None)
    village = str(getattr(location, "village", "") or "") if location is not None else ""
    district = str(getattr(location, "district", "") or "") if location is not None else ""
    state = str(getattr(location, "state", "") or "") if location is not None else ""
    return (village or str(getattr(row, "place", "") or ""), district, state)


async def _load_one_reference_model(
    spec: ReferenceModel, ids: set[str], model: str
) -> tuple[list[Any], dict[str, str]]:
    try:
        rows = await getattr(db, spec.delegate).find_many(
            where={"id": {"in": sorted(ids)}}, include=spec.include or None
        )
        return rows, await _reference_photos(spec, [r.id for r in rows])
    except Exception:
        logger.exception("Could not load %s references for a workshop report", model)
        return [], {}


async def load_report_references(entries: list[Any]) -> dict[str, ReferencedRecord]:
    wanted = reference_ids(entries)
    out: dict[str, ReferencedRecord] = {}
    models = sorted(wanted)
    results = await gather_reads(
        *(
            _load_one_reference_model(REFERENCE_MODELS[model], wanted[model], model)
            for model in models
        )
    )
    for model, (rows, photos) in zip(models, results, strict=True):
        spec = REFERENCE_MODELS[model]
        for row in rows:
            place, district, state = _reference_place(row, model)
            out[str(row.id)] = ReferencedRecord(
                model=model,
                label=spec.label(row),
                photo=(found.id if (found := photos.get(row.id)) else ""),
                place=place,
                district=district,
                state=state,
            )
    return out


async def attach_report_references(data: WorkshopData, entries: list[Any]) -> list[str]:
    data.references = await load_report_references(entries)
    return [r.photo for r in data.references.values() if r.photo]


def workshop_media_ids(entries: list[Any]) -> set[str]:
    return _media_ids(entries)


def _media_ids(entries: list[Any]) -> set[str]:
    media_keys: dict[str, set[str]] = {}
    rich_keys: dict[str, set[str]] = {}
    for spec in stages():
        for entity in spec.entities:
            keys = {f.key for f in entity.fields if f.type.is_media}
            if keys:
                media_keys[entity.key] = keys
            rich = {f.key for f in entity.fields if f.is_rich_text}
            if rich:
                rich_keys[entity.key] = rich

    found: set[str] = set()
    for row in entries:
        data = row.data or {}
        for key in media_keys.get(row.entityKey, ()):
            value = data.get(key)
            if isinstance(value, str) and value:
                found.add(value)
            elif isinstance(value, (list, tuple)):
                found.update(str(v) for v in value if v)
        for key in rich_keys.get(row.entityKey, ()):
            found.update(rich_text.media_ids(data.get(key)))
    return found


REPORT_IMAGE_BUDGET_BYTES = 96 * 1024 * 1024
REPORT_IMAGE_MAX_BYTES = 16 * 1024 * 1024

REPORT_IMAGE_FETCH_WORKERS = 4


class MediaIndex:
    def __init__(
        self,
        refs: dict[str, ImageRef],
        keys: dict[str, str],
        *,
        withheld: tuple[str, ...] = (),
        sizes: dict[str, int] | None = None,
    ) -> None:
        self._refs = refs
        self._keys = keys
        self._blobs: dict[str, bytes | None] = {}
        self._sizes = sizes or {}
        self.withheld = withheld
        self.oversize: list[str] = []
        self.budget_spent = 0

    def ref(self, media_id: str) -> ImageRef | None:
        return self._refs.get(media_id)

    def known_refs(self) -> dict[str, ImageRef]:
        return dict(self._refs)

    def blob(self, image: ImageRef) -> bytes | None:
        return self._blobs.get(image.source)

    def prefetch(self, wanted: tuple[ImageRef, ...], *, budget: int | None = None) -> None:
        from concurrent.futures import Future, ThreadPoolExecutor

        from app.services.memory_budget import budget_bytes
        from app.services.s3 import ObjectTooLarge, get_object_bytes

        ceiling = budget if budget is not None else budget_bytes(REPORT_IMAGE_BUDGET_BYTES)
        per_image = min(REPORT_IMAGE_MAX_BYTES, ceiling)

        plan: list[tuple[str, str | None, int, bool]] = []
        planned: set[str] = set()
        for image in wanted:
            if image.source in self._blobs or image.source in planned:
                continue
            planned.add(image.source)
            key = self._keys.get(image.source)
            declared = self._sizes.get(image.source, 0)
            if not key:
                plan.append((image.source, None, 0, False))
            elif declared and declared > per_image:
                plan.append((image.source, None, declared, True))
            else:
                plan.append((image.source, key, declared, False))

        with ThreadPoolExecutor(max_workers=REPORT_IMAGE_FETCH_WORKERS) as pool:
            inflight: dict[int, Future[bytes]] = {}
            submitted = 0

            def submit_ahead() -> None:
                nonlocal submitted
                while (
                    submitted < len(plan)
                    and len(inflight) < REPORT_IMAGE_FETCH_WORKERS
                    and self.budget_spent < ceiling
                ):
                    _source, key, _declared, _refused = plan[submitted]
                    if key is not None:
                        inflight[submitted] = pool.submit(
                            get_object_bytes,
                            key,
                            max_bytes=min(per_image, ceiling - self.budget_spent),
                        )
                    submitted += 1

            for index, (source, key, declared, refused) in enumerate(plan):
                submit_ahead()
                if key is None:
                    self._blobs[source] = None
                    if refused:
                        self.oversize.append(source)
                    continue
                future = inflight.pop(index, None)
                if future is None or (declared and self.budget_spent + declared > ceiling):
                    if future is not None:
                        future.cancel()
                    self._blobs[source] = None
                    self.oversize.append(source)
                    continue
                try:
                    blob = future.result()
                except ObjectTooLarge:
                    self._blobs[source] = None
                    self.oversize.append(source)
                    continue
                except Exception:  # noqa: BLE001 - one unreadable photo must not fail the export
                    self._blobs[source] = None
                    continue
                size = len(blob)
                if size > per_image or self.budget_spent + size > ceiling:
                    blob = None
                    self._blobs[source] = None
                    self.oversize.append(source)
                    continue
                self.budget_spent += size
                self._blobs[source] = blob


async def media_resolver(
    entries: list[Any], *, viewer: Any, extra_ids: Iterable[str] = ()
) -> MediaIndex:
    from app.services.records import owned_or_granted_where

    ids = _media_ids(entries) | {str(i) for i in extra_ids if i}
    if not ids:
        return MediaIndex({}, {})

    where: dict[str, Any] = {"id": {"in": sorted(ids)}}
    entitled = await owned_or_granted_where(viewer, owner_field="uploadedById")
    if entitled:
        where = {"AND": [where, entitled]}
    rows = await db.mediafile.find_many(where=where)
    refs: dict[str, ImageRef] = {}
    keys: dict[str, str] = {}
    sizes: dict[str, int] = {}
    for row in rows:
        if str(getattr(row, "mediaType", "")) not in ("IMAGE", "MediaType.IMAGE"):
            continue
        extra = row.extraMetadata or {}
        rotation = _int_or_zero(extra.get("orientationDegrees") or extra.get("rotation"))
        refs[row.id] = ImageRef(
            source=row.id,
            width_px=_int_or_zero(extra.get("width")),
            height_px=_int_or_zero(extra.get("height")),
            rotation_deg=rotation if rotation in (90, 180, 270) else 0,
            mime_type=row.mimeType or "image/jpeg",
        )
        keys[row.id] = row.objectKey
        sizes[row.id] = _int_or_zero(getattr(row, "sizeBytes", 0))
    return MediaIndex(
        refs, keys, withheld=tuple(sorted(ids - {row.id for row in rows})), sizes=sizes
    )


def _int_or_zero(value: Any) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


async def attach_report_transcripts(
    data: WorkshopData, entries: list[Any], *, viewer: Any, requested: bool | None = None
) -> list[str]:
    if not wants_transcripts(requested, data.singleton("REPORT_GENERATION")):
        return []
    items = await load_transcript_items(entries, viewer=viewer)
    warnings: list[str] = []
    refused = len(audio_references(entries)) - len(items)
    if refused > 0:
        warnings.append(
            f"{count_noun(refused, 'recording')} attached to this workshop could not be included "
            f"in the transcript annexure: {by_count(refused, 'it was', 'they were')} uploaded by "
            "another account, or the file is gone."
        )
    if not items:
        return warnings
    attach_transcripts(data, items)
    return warnings + annexure_warnings(items)


async def attach_report_ai_layers(
    data: WorkshopData,
    workshop_id: str,
    *,
    viewer: Any,
    readable_media: Callable[[set[str]], Awaitable[set[str]]],
    requested: bool = False,
) -> list[str]:
    if not requested:
        return []
    from app.services.ai_layers import (
        LayerRuleViolation,
        accepted_layers,
        chain_roots,
        media_ids_to_check,
        source_of,
        workshop_layers,
    )
    from app.services.report_ai_layers import (
        AiLayerItem,
        annexure_warnings as ai_warnings,
        attach_ai_layers,
    )

    try:
        rows = await workshop_layers(workshop_id)
        printable = {
            str(getattr(row, "id", "") or "") for row in await accepted_layers(workshop_id)
        }
    except Exception:
        logger.exception("ai layers could not be loaded for workshop %s", workshop_id)
        return [
            "The machine-assisted text could not be read for this report, so the annexure is not "
            "included. Everything else in this document is unaffected."
        ]

    roots = chain_roots(rows)
    wanted = media_ids_to_check(roots)
    try:
        readable = await readable_media(wanted) if wanted else set()
    except Exception:
        logger.exception("media entitlement could not be resolved for workshop %s", workshop_id)
        readable = set()

    items: list[AiLayerItem] = []
    withheld_count = 0
    for row in rows:
        layer_id = str(getattr(row, "id", "") or "")
        source_kind = source_id = source_text = ""
        try:
            stored = source_of(row)
            source_kind, source_id = stored.kind.value, stored.id
            source_text = stored.text or ""
        except LayerRuleViolation:
            pass
        root = roots.get(layer_id)
        text_withheld = root is None or root.withheld_from(readable)
        if text_withheld:
            withheld_count += 1
        items.append(
            AiLayerItem(
                layer_id=layer_id,
                kind=str(getattr(row, "kind", "") or ""),
                tier=str(getattr(row, "tier", "") or ""),
                provider=str(getattr(row, "provider", "") or ""),
                model_id=str(getattr(row, "modelId", "") or ""),
                model_version=str(getattr(row, "modelVersion", "") or ""),
                language=str(getattr(row, "language", "") or ""),
                source_language=str(getattr(row, "sourceLanguage", "") or ""),
                target_language=str(getattr(row, "targetLanguage", "") or ""),
                produced_at=_iso_or_blank(getattr(row, "producedAt", None)),
                accepted=layer_id in printable,
                accepted_at=_iso_or_blank(getattr(row, "acceptedAt", None)),
                accepted_by=_acceptor_name(row),
                accepted_by_id=str(getattr(row, "acceptedById", "") or ""),
                source_kind=source_kind,
                source_id=source_id,
                source_label="" if text_withheld else source_text,
                text="" if text_withheld else str(getattr(row, "text", "") or ""),
                text_withheld=text_withheld,
            )
        )

    attach_ai_layers(data, items)
    warnings = ai_warnings(items)
    printable_withheld = sum(1 for item in items if item.text_withheld and item.accepted)
    if printable_withheld:
        warnings.append(
            f"{count_noun(printable_withheld, 'accepted machine-assisted passage')} "
            f"{by_count(printable_withheld, 'is', 'are')} named in the annexure "
            "with their model and provenance, but their text is not printed: they stand on "
            "recordings uploaded by another account. Ask whoever uploaded them for access to their "
            "media, or ask them to generate this report."
        )
    return warnings


def _acceptor_name(row: Any) -> str:
    accepted_by = getattr(row, "acceptedBy", None)
    if accepted_by is None:
        return ""
    for attribute in ("fullName", "name", "email"):
        value = getattr(accepted_by, attribute, None)
        if value and str(value).strip():
            return str(value).strip()
    return ""


def _iso_or_blank(value: Any) -> str:
    if value is None:
        return ""
    return value.isoformat() if hasattr(value, "isoformat") else str(value)


async def attach_report_custom_sections(
    data: WorkshopData, entries: list[Any], workshop_id: str
) -> list[str]:
    definition = await custom_sections.load_definition_or_empty(workshop_id)
    if definition.is_empty:
        return []

    values_by_stage: dict[str, dict[str, Any]] = {
        row.stageKey: dict(row.data or {})
        for row in entries
        if row.entityKey == custom_sections.CUSTOM_ENTITY_KEY
    }

    items, warnings = report_custom_section_items(definition, values_by_stage)
    attach_custom_sections(data, items)
    return warnings


def report_custom_section_items(
    definition: Any, values_by_stage: Mapping[str, Mapping[str, Any]]
) -> tuple[list[CustomSectionItem], list[str]]:
    items: list[CustomSectionItem] = []
    for section in definition.sections:
        item = CustomSectionItem(
            key=section.key,
            title=section.title,
            stage_key=section.stage_key,
            description=section.description,
            sort_order=section.sort_order,
            fields=tuple(
                CustomReportField(
                    key=f.key,
                    label=f.label,
                    type=f.type.value,
                    unit=f.unit,
                    options=tuple((o.value, o.display) for o in f.options),
                    required=f.required,
                    tier=f.tier.value,
                    retired=f.retired or section.retired,
                )
                for f in section.fields
            ),
            values=values_by_stage.get(section.stage_key, {}),
        )
        if section.retired and not item.answered_count:
            continue
        items.append(item)

    warnings: list[str] = []
    unanswered = [item for item in items if not item.has_content]
    if unanswered:
        warnings.append(
            f"{len(unanswered)} of this workshop's own sections "
            f"{by_count(len(unanswered), 'has', 'have')} no answers recorded and "
            f"{by_count(len(unanswered), 'is', 'are')} not in this file: "
            + ", ".join(sorted(item.title for item in unanswered)[:4])
            + ("…" if len(unanswered) > 4 else "")
        )
    return items, warnings


async def attach_report_questionnaires(data: WorkshopData, workshop_id: str) -> list[str]:
    from app.services.questionnaire_forms import report_items

    try:
        items = await report_items(workshop_id)
    except Exception:
        logger.exception("Could not load questionnaires for workshop %s", workshop_id)
        return ["The questionnaire annexure could not be loaded and was left out of this report."]
    if not items:
        return []
    attach_questionnaires(data, items)
    return questionnaire_warnings(items)


def resolve_template_id(requested: Any, settings: Mapping[str, Any] | None, record: Any) -> str:
    from app.schemas.design_workshops import REPORT_TEMPLATE_IDS

    for candidate in (requested, (settings or {}).get("templateId")):
        token = str(candidate).strip() if candidate not in (None, "") else ""
        if token in REPORT_TEMPLATE_IDS:
            return token
    return str(getattr(record, "templateId", "") or "")


def report_meta(
    record: Any, template_id: str, settings: Mapping[str, Any] | None = None
) -> ReportMeta:
    template = get_template(template_id)
    s = settings or {}

    def text(key: str) -> str:
        raw = s.get(key)
        return str(raw).strip() if raw not in (None, "") else ""

    subtitle_parts = [p for p in (record.craftName, record.clusterName, record.state) if p]
    derived_subtitle = (
        " — ".join([*subtitle_parts[:1], ", ".join(subtitle_parts[1:])])
        if len(subtitle_parts) > 1
        else (subtitle_parts[0] if subtitle_parts else "")
    )

    page_size = template.page_size
    if text("pageSize"):
        try:
            page_size = PageSize(text("pageSize"))
        except ValueError:
            pass

    return ReportMeta(
        title=text("reportTitle") or record.title,
        subtitle=text("reportSubtitle") or derived_subtitle,
        author=record.designerName or "",
        organisation=text("organisationLine") or record.implementingAgency or template.organisation,
        template_id=template.id,
        template_name=template.name,
        workshop_id=record.workshopCode or record.id,
        generated_at=datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z"),
        page_size=page_size,
        header_text=text("headerText")
        or " — ".join(p for p in (record.craftName, record.clusterName) if p),
        footer_text=text("footerText") or f"{template.name} · {record.workshopCode or record.id}",
    )


def render_report(
    data: WorkshopData, template_id: str, resolver: Any, record: Any, fmt: str, options: Any
) -> tuple[bytes, list[str], int | None]:
    settings = data.singleton("REPORT_GENERATION")

    meta = report_meta(record, template_id, settings)
    if options is not None and getattr(options, "pageSize", None):
        try:
            meta = replace(meta, page_size=PageSize(options.pageSize))
        except ValueError:
            pass
    if options is not None and getattr(options, "headerText", None):
        meta = replace(meta, header_text=options.headerText)
    if options is not None and getattr(options, "footerText", None):
        meta = replace(meta, footer_text=options.footerText)

    template = get_template(template_id)
    accent = resolve_accent(
        getattr(options, "themeAccent", None) if options is not None else None,
        settings,
    )
    theme = theme_from_accent(accent, base=template.theme) if accent else template.theme

    fonts = resolve_font(
        getattr(options, "fontPreset", None) if options is not None else None,
        settings,
    )
    if fonts:
        theme = replace(theme, heading_font=fonts[0], body_font=fonts[1])

    template = apply_report_settings(
        template,
        settings,
        include_photographs=(
            getattr(options, "includePhotographs", None) if options is not None else None
        ),
        include_ai_layers=(
            getattr(options, "includeAiLayers", None) if options is not None else None
        ),
        custom_sections=custom_sections_of(data),
    )

    document, warnings = build_report(
        data, template_id, resolver.ref, meta=meta, theme=theme, template=template
    )

    resolver.prefetch(document.images)
    over_budget = tuple(getattr(resolver, "oversize", ()) or ())

    if fmt == "PDF":
        blob, dropped = render_pdf(document, resolver.blob)
        page_count = _pdf_page_count(blob)
    else:
        blob, dropped = render_docx(document, resolver.blob)
        page_count = None

    warnings.extend(_dropped_warnings(dropped))
    if over_budget:
        warnings.append(
            f"{len(over_budget)} of those photographs {by_count(len(over_budget), 'was', 'were')} "
            "left out because this report already "
            f"holds as many pictures as it can take at once. They are all still saved with the "
            f"workshop. Generate the report again later, or choose a template that prints fewer "
            f"pictures, to include more."
        )
    if fmt == "PDF":
        warnings.extend(_font_warnings())
    if fonts and fmt == "PDF":
        warnings.append(
            f"The PDF uses a standard typeface, not {fonts[1]}, so that Odia, Devanagari and the "
            f"rupee sign print correctly. The Word document uses {fonts[1]}."
        )
    return blob, warnings, page_count


def _font_warnings() -> list[str]:
    from app.services.report_pdf import register_fonts

    fonts = register_fonts()
    said: list[str] = []
    if fonts.missing_glyphs:
        listed = ", ".join(
            f"{character} — {purpose}" for character, purpose in fonts.missing_glyphs
        )
        said.append(
            f"This PDF prints empty boxes where these characters belong: {listed}. The Word "
            f"document in the same download shows them correctly."
        )
    if fonts.missing_scripts:
        scripts = ", ".join(s.value.title() for s in fonts.missing_scripts)
        said.append(
            f"This PDF prints empty boxes for text in these scripts: {scripts}, so a craft name "
            f"in the local language will not be legible. The Word document in the same download "
            f"shows it correctly."
        )
    return said


def _dropped_warnings(dropped: list[str]) -> list[str]:
    if not dropped:
        return []
    said: list[str] = []
    if any(item.startswith("map:") for item in dropped):
        said.append(
            "The locator map could not be drawn, so the section that places the workshop and its "
            "artisans is empty in this file. Nothing is wrong with the workshop's details."
        )
    figures = sum(1 for item in dropped if item.startswith(("chart:", "figure:")))
    if figures:
        said.append(
            f"{count_noun(figures, 'figure')} could not be drawn. "
            f"{by_count(figures, 'Its numbers are', 'Their numbers are')} still in the tables."
        )
    photographs = sum(1 for item in dropped if not item.startswith(("map:", "chart:", "figure:")))
    if photographs:
        said.append(f"{count_noun(photographs, 'photograph')} could not be included in the file.")
    return said


def _pdf_page_count(blob: bytes) -> int | None:
    count = blob.count(b"/Type /Page") + blob.count(b"/Type/Page")
    pages_obj = blob.count(b"/Type /Pages") + blob.count(b"/Type/Pages")
    total = count - pages_obj
    return total if total > 0 else None


async def attach_district_anchors(data: WorkshopData) -> int:
    from app.services.geography import (
        MAX_ANCHOR_ROWS,
        REFERENCED_BY_A_RECORD,
        DistrictAnchors,
        district_key,
    )

    anchors = DistrictAnchors()
    anchors.seed_from_atlas()
    rows = await db.location.find_many(
        where={
            "AND": [
                {"subjectLatitude": {"not": None}, "district": {"not": None}},
                REFERENCED_BY_A_RECORD,
            ]
        },
        order={"id": "asc"},
        take=MAX_ANCHOR_ROWS,
    )
    anchors.learn(rows or [])

    points: dict[str, tuple[float, float]] = {}
    for state, districts in DISTRICTS_BY_STATE.items():
        for district in districts:
            point = anchors.anchor(state, district)
            if point:
                points[district_key(state, district)] = point
    data.district_points = points
    return len(points)
