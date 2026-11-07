import csv
import re
from datetime import UTC, datetime
from io import StringIO
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import Response
from prisma.errors import UniqueViolationError

from app.api.routes.export import csv_response
from app.core.db import db
from app.core.deps import (
    assert_can_contribute_relation,
    assert_can_create_records,
    assert_can_delete,
    get_current_user,
    get_value,
    is_content_admin,
    is_empty_value,
    require_content_admin,
    require_questionnaire_manager,
)
from app.questionnaire import shims_dr
from app.schemas.questionnaire import (
    CompletionCellUpdate,
    QuestionnaireInterviewCreate,
    QuestionnaireInterviewUpdate,
    QuestionnaireQuestionCreate,
    QuestionnaireQuestionReorder,
    QuestionnaireQuestionUpdate,
    QuestionnaireSectionCreate,
    QuestionnaireSectionReorder,
    QuestionnaireSectionUpdate,
)
from app.services import answer_drafts, cross_portal_codes, naming
from app.services.access import guard_record_edit, owner_download_scope
from app.services.artisan_merge import redirect_ids
from app.services.completeness import (
    attach as attach_completeness,
    filter_or_422 as completeness_filter,
)
from app.services.concurrency import gather_reads
from app.services.pagination import normalize_pagination, page_payload
from app.services.questionnaire_consolidation import (
    CSV_COLUMNS,
    consolidate_for_artisan,
    consolidated_rows,
)
from app.services.record_design_workshop import (
    assert_may_file_under,
    assert_may_write_a_record_filed_under,
    assert_payload_workshop,
)
from app.services.record_filters import (
    artisan_workshop_clause,
    resolve_workshop_ids,
    workshop_clause,
)
from app.services.records import (
    RECORD_STATUSES,
    Relation,
    add_date_range,
    apply_status_policy_create,
    apply_status_policy_update,
    assert_expected_updated_at,
    attach_location,
    claim_expected_updated_at,
    clean_data,
    contains,
    count_and_page,
    enum_filter_or_422,
    hydrate_relations,
    jsonify_metadata,
    media_url_owners,
    merge_field_provenance,
    public_encode,
    require_live_record,
    require_record,
    resubmit_status,
    take_expected_updated_at,
    viewable_where,
)
from app.services.standard_values import StandardValueError, apply_interview_values
from app.services.text_format import by_count, count_noun
from app.services.workshop_access import (
    WorkshopSubmissionCheck,
    enforce_workshop_submission,
    pin_pending_if_late,
    stamp_workshop_submission,
)
from app.services.workshop_inference import count_unassigned_interviews

router = APIRouter(prefix="/questionnaire", tags=["questionnaire"])


def _norm_code(value: str | None) -> str:
    return "".join(ch for ch in (value or "") if ch.isalnum()).upper()


_SECTION_IN_TITLE_RE = re.compile(r"sections?\s+([a-zA-Z][a-zA-Z\s,&/\-]{0,24})", re.IGNORECASE)
_LETTER_RUN_RE = re.compile(r"\b([a-zA-Z](?:\s*,\s*[a-zA-Z]){2,})\b")
_REAL_WORD_RE = re.compile(r"\b(?!and\b)[a-zA-Z]{2,}\b", re.IGNORECASE)


def section_codes_from_title(title: str | None, valid_codes: set[str]) -> set[str]:
    if not title:
        return set()
    found: set[str] = set()
    for match in _SECTION_IN_TITLE_RE.finditer(title):
        run = _REAL_WORD_RE.split(match.group(1))[0]
        for token in re.split(r"[^a-zA-Z]+", run):
            if len(token) == 1 and token.upper() in valid_codes:
                found.add(token.upper())
    for match in _LETTER_RUN_RE.finditer(title):
        for token in re.split(r"[^a-zA-Z]+", match.group(1)):
            if len(token) == 1 and token.upper() in valid_codes:
                found.add(token.upper())
    return found


RELATIONS = (
    Relation("createdBy", "user", "createdById"),
    Relation("location", "location", "locationId"),
    Relation("workshop", "workshop", "workshopId"),
    Relation(
        "artisans",
        "questionnaireinterviewartisan",
        "interviewId",
        many=True,
        include={"artisan": {"include": {"craft": True}}},
    ),
    Relation(
        "responses",
        "questionnaireresponse",
        "interviewId",
        many=True,
        include={"question": True, "answeredBy": True},
    ),
    Relation("media", "mediafile", "questionnaireInterviewId", many=True),
)


_DUPLICATE_SET_DETAIL = (
    "An interview already exists for this exact set of artisans. There is a single shared entry per "
    "artisan set — open it to add or view answers instead of creating another."
)

_DUPLICATE_SET_CODE = "duplicate_artisan_set"


async def duplicate_set_detail(set_key: str) -> dict[str, Any]:
    holder = await db.questionnaireinterview.find_first(where={"artisanSetKey": set_key})
    return {
        "code": _DUPLICATE_SET_CODE,
        "message": _DUPLICATE_SET_DETAIL,
        "existingInterviewId": get_value(holder, "id"),
        "existingInterviewTitle": get_value(holder, "title"),
    }


_SET_KEY_SCOPE_SEPARATOR = "|"
_SET_KEY_ID_SEPARATOR = ","


def artisan_set_key(
    artisan_ids: list[str], *, workshop_id: str | None, design_workshop_id: str | None
) -> str | None:
    unique = sorted({aid for aid in artisan_ids if aid})
    if not unique:
        return None
    scope = (workshop_id or "", design_workshop_id or "")
    return _SET_KEY_SCOPE_SEPARATOR.join(
        [*scope, _SET_KEY_ID_SEPARATOR.join(unique)]
    )


def default_interview_date(data: dict[str, Any]) -> dict[str, Any]:
    if data.get("interviewDate") is None:
        data["interviewDate"] = data.get("recordedAt") or datetime.now(UTC)
    return data


async def next_section_sort_order() -> int:
    sections = await db.questionnairesection.find_many(order={"sortOrder": "desc"}, take=1)
    return (sections[0].sortOrder if sections else 0) + 1


async def next_question_sort_order(section_id: str) -> int:
    questions = await db.questionnairequestion.find_many(
        where={"sectionId": section_id},
        order={"sortOrder": "desc"},
        take=1,
    )
    return (questions[0].sortOrder if questions else 0) + 1


async def require_section(section_id: str) -> Any:
    return await require_record(db.questionnairesection, section_id)


def section_question_data(section: Any) -> dict[str, str]:
    return {"sectionId": section.id, "sectionCode": section.code, "sectionTitle": section.title}


async def section_payloads(active_only: bool = True) -> list[dict[str, Any]]:
    section_where = {"isActive": True} if active_only else {}
    question_where: dict[str, Any] = {"isActive": True} if active_only else {}
    sections, questions = await gather_reads(
        db.questionnairesection.find_many(where=section_where, order={"sortOrder": "asc"}),
        db.questionnairequestion.find_many(
            where=question_where,
            order=[{"sortOrder": "asc"}, {"createdAt": "asc"}],
        ),
    )
    questions_by_section: dict[str, list[Any]] = {}
    for question in questions:
        if question.sectionId:
            questions_by_section.setdefault(question.sectionId, []).append(question)
    payload: list[dict[str, Any]] = []
    for section in sections:
        encoded = public_encode(section)
        encoded["questions"] = public_encode(questions_by_section.get(section.id, []))
        payload.append(encoded)
    return payload


async def write_interview_set_key(interview_id: str, set_key: str | None, *, writer: Any) -> None:
    finder = getattr(writer.questionnaireinterview, "find_first", None)
    if set_key and finder is not None:
        holder = await finder(where={"artisanSetKey": set_key, "id": {"not": interview_id}, "deletedAt": None})
        if holder is not None:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail={
                    "code": _DUPLICATE_SET_CODE,
                    "message": _DUPLICATE_SET_DETAIL,
                    "existingInterviewId": get_value(holder, "id"),
                    "existingInterviewTitle": get_value(holder, "title"),
                },
            )
    try:
        await writer.questionnaireinterview.update(
            where={"id": interview_id}, data={"artisanSetKey": set_key}
        )
    except UniqueViolationError as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail=await duplicate_set_detail(set_key or "")
        ) from exc


async def resync_interview_set_key(
    interview_id: str, *, workshop_id: str | None, design_workshop_id: str | None, client: Any = None
) -> None:
    writer = db if client is None else client
    links = await writer.questionnaireinterviewartisan.find_many(where={"interviewId": interview_id})
    set_key = artisan_set_key(
        [link.artisanId for link in links],
        workshop_id=workshop_id,
        design_workshop_id=design_workshop_id,
    )
    await write_interview_set_key(interview_id, set_key, writer=writer)


async def replace_interview_artisans(
    interview_id: str,
    artisan_ids: list[str],
    *,
    workshop_id: str | None,
    design_workshop_id: str | None,
    client: Any = None,
) -> None:
    writer = db if client is None else client
    unique_ids = sorted({aid for aid in artisan_ids if aid})
    set_key = artisan_set_key(
        unique_ids, workshop_id=workshop_id, design_workshop_id=design_workshop_id
    )

    current = await writer.questionnaireinterviewartisan.find_many(
        where={"interviewId": interview_id}
    )
    if sorted({link.artisanId for link in current}) == unique_ids:
        existing = await writer.questionnaireinterview.find_unique(where={"id": interview_id})
        if existing is not None and existing.artisanSetKey != set_key:
            await write_interview_set_key(interview_id, set_key, writer=writer)
        return

    if unique_ids:
        found = await writer.artisan.find_many(where={"id": {"in": unique_ids}})
        if len(found) != len(unique_ids):
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    await write_interview_set_key(interview_id, set_key, writer=writer)
    await writer.questionnaireinterviewartisan.delete_many(where={"interviewId": interview_id})
    if unique_ids:
        await writer.questionnaireinterviewartisan.create_many(
            data=[{"interviewId": interview_id, "artisanId": aid} for aid in unique_ids]
        )


ANSWER_OWNED_BY_OTHER = "ANSWER_OWNED_BY_OTHER"


def answer_owned_by_other_detail(question_ids: list[str]) -> dict[str, Any]:
    one = len(set(question_ids)) == 1
    return {
        "code": ANSWER_OWNED_BY_OTHER,
        "message": (
            ("Another researcher wrote this answer. Only they or an admin can change it.")
            if one
            else (
                f"Another researcher wrote {len(set(question_ids))} of these answers. "
                "Only they or an admin can change them."
            )
        ),
        "questionIds": sorted(set(question_ids)),
    }


async def upsert_responses(
    interview_id: str, responses: list[Any], current_user: Any, *, client: Any = None
) -> None:
    if not responses:
        return
    writer = db if client is None else client
    question_ids = sorted({r.questionId for r in responses if r.questionId})
    if client is None:
        existing_rows, questions = await gather_reads(
            db.questionnaireresponse.find_many(
                where={"interviewId": interview_id, "questionId": {"in": question_ids}}
            ),
            db.questionnairequestion.find_many(where={"id": {"in": question_ids}}),
        )
    else:
        existing_rows = await writer.questionnaireresponse.find_many(
            where={"interviewId": interview_id, "questionId": {"in": question_ids}}
        )
        questions = await writer.questionnairequestion.find_many(
            where={"id": {"in": question_ids}}
        )
    known = {q.id for q in questions}
    if len(known) != len(question_ids):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    by_question = {row.questionId: row for row in existing_rows}

    to_create: list[dict[str, Any]] = []
    to_update: list[tuple[str, dict[str, Any]]] = []
    deduped = {response.questionId: response for response in responses}
    conflicts: list[str] = []
    for response in deduped.values():
        existing = by_question.get(response.questionId)
        if existing is None:
            to_create.append(
                {
                    "interviewId": interview_id,
                    "questionId": response.questionId,
                    "answerText": response.answerText,
                    "notes": response.notes,
                    "answeredById": current_user.id,
                }
            )
            continue
        if existing.answerText == response.answerText and existing.notes == response.notes:
            continue
        if (
            existing.answerText
            and existing.answeredById != current_user.id
            and not is_content_admin(current_user)
        ):
            conflicts.append(response.questionId)
            continue
        to_update.append(
            (
                existing.id,
                {
                    "answerText": response.answerText,
                    "notes": response.notes,
                    "answeredById": current_user.id,
                    "answerOrigin": answer_drafts.origin_after_edit(
                        getattr(existing, "answerOrigin", None),
                        existing.answerText,
                        response.answerText,
                    ),
                },
            )
        )

    if conflicts:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=answer_owned_by_other_detail(conflicts),
        )

    if to_create:
        await writer.questionnaireresponse.create_many(data=to_create)
    for row_id, data in to_update:
        await writer.questionnaireresponse.update(where={"id": row_id}, data=data)
    if to_create or to_update:
        await writer.questionnaireinterview.update(
            where={"id": interview_id}, data={"updatedAt": datetime.now(UTC)}
        )


@router.get("/questions")
async def list_questions(
    _: Any = Depends(get_current_user),
    sectionCode: str | None = None,
    activeOnly: bool = True,
) -> list[dict[str, Any]]:
    sections = await section_payloads(activeOnly)
    flattened = [
        question
        for section in sections
        for question in section["questions"]
        if not sectionCode or question["sectionCode"] == sectionCode
    ]
    return public_encode(flattened)


@router.get("/sections")
async def list_sections(
    _: Any = Depends(get_current_user),
    activeOnly: bool = True,
) -> list[dict[str, Any]]:
    return await section_payloads(activeOnly)


@router.post("/sections", status_code=status.HTTP_201_CREATED)
async def create_section(
    payload: QuestionnaireSectionCreate,
    _: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    sort_order = payload.sortOrder or await next_section_sort_order()
    created = await db.questionnairesection.create(
        data={
            "code": payload.code.strip(),
            "title": payload.title.strip(),
            "sortOrder": sort_order,
            "isActive": payload.isActive,
        }
    )
    return public_encode(created)


@router.patch("/sections/{section_id}")
async def update_section(
    section_id: str,
    payload: QuestionnaireSectionUpdate,
    _: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    await require_section(section_id)
    data = clean_data(payload.model_dump(exclude_unset=True))
    if "code" in data:
        data["code"] = data["code"].strip()
    if "title" in data:
        data["title"] = data["title"].strip()
    updated = await db.questionnairesection.update(where={"id": section_id}, data=data)
    if "code" in data or "title" in data:
        await db.questionnairequestion.update_many(
            where={"sectionId": section_id},
            data={"sectionCode": updated.code, "sectionTitle": updated.title},
        )
        await cross_portal_codes.reconcile_question_codes(db)
    return public_encode(updated)


async def answered_question_ids(question_ids: list[str]) -> set[str]:
    if not question_ids:
        return set()
    rows = await db.questionnaireresponse.find_many(where={"questionId": {"in": question_ids}})
    return {row.questionId for row in rows if (row.answerText or "").strip()}


@router.delete("/sections/{section_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_section(
    section_id: str,
    _: Any = Depends(require_content_admin),
) -> None:
    await require_section(section_id)
    questions = await db.questionnairequestion.find_many(where={"sectionId": section_id})
    answered = await answered_question_ids([q.id for q in questions])
    await db.questionnairesection.update(where={"id": section_id}, data={"isActive": False})
    await db.questionnairequestion.update_many(
        where={"sectionId": section_id}, data={"isActive": False}
    )
    if answered:
        await db.questionnairequestion.update_many(
            where={"id": {"in": sorted(answered)}, "retiredAt": None},
            data={"retiredAt": datetime.now(UTC)},
        )


@router.post("/sections/reorder")
async def reorder_sections(
    payload: QuestionnaireSectionReorder,
    _: Any = Depends(require_questionnaire_manager),
) -> list[dict[str, Any]]:
    for index, section_id in enumerate(payload.sectionIds):
        await require_section(section_id)
        await db.questionnairesection.update(
            where={"id": section_id}, data={"sortOrder": -(index + 1)}
        )
    for index, section_id in enumerate(payload.sectionIds):
        await db.questionnairesection.update(
            where={"id": section_id}, data={"sortOrder": index + 1}
        )
    return await section_payloads(active_only=False)


@router.post("/questions", status_code=status.HTTP_201_CREATED)
async def create_question(
    payload: QuestionnaireQuestionCreate,
    _: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    section = await require_section(payload.sectionId)
    sort_order = payload.sortOrder or await next_question_sort_order(section.id)
    created = await db.questionnairequestion.create(
        data={
            **section_question_data(section),
            "prompt": payload.prompt.strip(),
            "questionCode": cross_portal_codes.question_code(section.code, payload.prompt.strip()),
            "sortOrder": sort_order,
            "isActive": payload.isActive,
        }
    )
    return public_encode(created)


SUPERSEDED_DETAIL = (
    "This question already has answers recorded against it, so its original wording and those "
    "answers were kept and your new wording was added as a new question in the same place. Nothing "
    "was overwritten and no recorded answer changed meaning."
)


@router.patch("/questions/{question_id}")
async def update_question(
    question_id: str,
    payload: QuestionnaireQuestionUpdate,
    _: Any = Depends(require_questionnaire_manager),
) -> dict[str, Any]:
    question = await require_record(db.questionnairequestion, question_id)
    data = clean_data(payload.model_dump(exclude_unset=True))
    if "prompt" in data:
        data["prompt"] = data["prompt"].strip()
    section_id = data.pop("sectionId", None)
    if section_id:
        section = await require_section(section_id)
        data.update(section_question_data(section))
        if "sortOrder" not in data or data["sortOrder"] is None:
            data["sortOrder"] = await next_question_sort_order(section.id)
    elif question.sectionId and ("sectionCode" not in data or "sectionTitle" not in data):
        section = await require_section(question.sectionId)
        data.update({"sectionCode": section.code, "sectionTitle": section.title})

    new_prompt = data.get("prompt")
    reworded = new_prompt is not None and new_prompt != (question.prompt or "").strip()
    if reworded and await answered_question_ids([question.id]):
        if question.supersededById:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=(
                    "This question was already replaced by a newer one, so its wording is now part "
                    "of the record rather than something to edit. Edit the question that replaced "
                    "it instead — it is the one being asked."
                ),
            )
        replacement = await db.questionnairequestion.create(
            data={
                "sectionId": data.get("sectionId", question.sectionId),
                "sectionCode": data.get("sectionCode", question.sectionCode),
                "sectionTitle": data.get("sectionTitle", question.sectionTitle),
                "prompt": new_prompt,
                "questionCode": cross_portal_codes.question_code(
                    data.get("sectionCode", question.sectionCode), new_prompt
                ),
                "sortOrder": data.get("sortOrder") or question.sortOrder,
                "isActive": data.get("isActive", True),
            }
        )
        await db.questionnairequestion.update(
            where={"id": question.id},
            data={
                "isActive": False,
                "retiredAt": datetime.now(UTC),
                "supersededById": replacement.id,
            },
        )
        return {
            **public_encode(replacement),
            "action": "superseded",
            "supersededQuestionId": question.id,
            "detail": SUPERSEDED_DETAIL,
        }

    data["questionCode"] = cross_portal_codes.question_code(
        data.get("sectionCode", question.sectionCode), data.get("prompt", question.prompt)
    )
    updated = await db.questionnairequestion.update(where={"id": question_id}, data=data)
    return {**public_encode(updated), "action": "updated"}


@router.delete("/questions/{question_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_question(
    question_id: str,
    _: Any = Depends(require_content_admin),
) -> None:
    await require_record(db.questionnairequestion, question_id)
    answered = await answered_question_ids([question_id])
    data: dict[str, Any] = {"isActive": False}
    if answered:
        data["retiredAt"] = datetime.now(UTC)
    await db.questionnairequestion.update(where={"id": question_id}, data=data)


@router.post("/questions/reorder")
async def reorder_questions(
    payload: QuestionnaireQuestionReorder,
    _: Any = Depends(require_questionnaire_manager),
) -> list[dict[str, Any]]:
    section = await require_section(payload.sectionId)
    for index, question_id in enumerate(payload.questionIds):
        await require_record(db.questionnairequestion, question_id)
        await db.questionnairequestion.update(
            where={"id": question_id},
            data={**section_question_data(section), "sortOrder": index + 1},
        )
    return await section_payloads(active_only=False)


@router.get("/interviews")
async def list_interviews(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    artisanId: str | None = None,
    workshopId: str | None = None,
    designWorkshopId: str | None = None,
    statusFilter: str | None = None,
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    createdBy: str | None = None,
    completeness: str | None = None,
    deletedOnly: bool = False,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    if deletedOnly and not is_content_admin(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only an admin can see deleted interviews.",
        )
    where: dict[str, Any] = {"deletedAt": {"not": None} if deletedOnly else None}

    and_filters: list[dict[str, Any]] = []
    vis = await viewable_where(current_user)
    if vis:
        and_filters.append(vis)

    if search:
        where["OR"] = [
            {"title": contains(search)},
            {"place": contains(search)},
            {"notes": contains(search)},
        ]
    if artisanId:
        where["artisans"] = {"some": {"artisanId": artisanId}}
    if designWorkshopId:
        where["designWorkshopId"] = designWorkshopId
    if workshopId:
        where["workshopId"] = workshopId
    if statusFilter:
        where["status"] = enum_filter_or_422(statusFilter, RECORD_STATUSES)
    if createdBy:
        where["createdById"] = createdBy
    complete_clause = completeness_filter("interview", completeness)
    if complete_clause:
        and_filters.append(complete_clause)
    if and_filters:
        where["AND"] = and_filters
    add_date_range(where, "interviewDate", dateFrom, dateTo)
    (total, items), media_urls = await gather_reads(
        count_and_page(
            db.questionnaireinterview,
            where=where,
            skip=skip,
            take=page_size,
            order=[{"deletedAt": "desc"}, {"createdAt": "desc"}] if deletedOnly else {"createdAt": "desc"},
            relations=RELATIONS,
        ),
        media_url_owners(current_user),
    )
    encoded = await attach_completeness(
        "interview", public_encode(items, current_user, media_urls=media_urls)
    )
    if deletedOnly:
        deleters = await _user_names([getattr(row, "deletedById", None) for row in items])
        for row, item in zip(items, encoded, strict=True):
            item["deletedByName"] = deleters.get(getattr(row, "deletedById", None) or "")
    return page_payload(encoded, total, page, page_size)


async def _user_names(user_ids: list[str | None]) -> dict[str, str]:
    ids = sorted({uid for uid in user_ids if uid})
    if not ids:
        return {}
    users = await db.user.find_many(where={"id": {"in": ids}})
    return {user.id: user.name for user in users}


_MERGEABLE_FILL_FIELDS = ("title", "place", "language", "notes", "interviewDate", "workshopId")


async def assert_referenced_records_exist(
    artisan_ids: list[str], responses: list[Any] | None
) -> None:
    artisans = sorted({aid for aid in artisan_ids if aid})
    question_ids = sorted({r.questionId for r in (responses or []) if r.questionId})
    if not artisans and not question_ids:
        return
    found_artisans, found_questions = await gather_reads(
        db.artisan.find_many(where={"id": {"in": artisans}}),
        db.questionnairequestion.find_many(where={"id": {"in": question_ids}}),
    )
    if len(found_artisans) != len(artisans) or len({q.id for q in found_questions}) != len(
        question_ids
    ):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")


async def merge_into_interview(
    existing: Any,
    payload: QuestionnaireInterviewCreate,
    current_user: Any,
    check: WorkshopSubmissionCheck | None = None,
) -> dict[str, Any]:
    incoming = payload.model_dump()
    fill = {
        field: incoming.get(field)
        for field in _MERGEABLE_FILL_FIELDS
        if not is_empty_value(incoming.get(field)) and is_empty_value(get_value(existing, field))
    }
    if "workshopId" in fill:
        existing_extra = get_value(existing, "extraMetadata")
        if isinstance(existing_extra, dict):
            fill["extraMetadata"] = dict(existing_extra)
        stamp_workshop_submission(fill, check=check, record=existing)
        pin_pending_if_late(fill, current_user, check=check, record=existing)
        jsonify_metadata(fill)
    canonical = existing
    await assert_referenced_records_exist([], payload.responses)
    async with db.tx() as tx:
        if fill:
            canonical = await tx.questionnaireinterview.update(
                where={"id": existing.id}, data=fill
            )
        if payload.responses:
            await upsert_responses(existing.id, payload.responses, current_user, client=tx)
    await hydrate_relations([canonical], RELATIONS)
    return await attach_completeness(
        "interview", public_encode(canonical, current_user, media_urls=await media_url_owners(current_user))
    )


@router.post("/interviews", status_code=status.HTTP_201_CREATED)
async def create_interview(
    payload: QuestionnaireInterviewCreate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    payload.artisanIds = await redirect_ids(payload.artisanIds) or []
    check = await enforce_workshop_submission(current_user, payload.workshopId)
    await assert_may_file_under(payload.designWorkshopId, current_user)
    shimmed = await shims_dr.create_global_interview(payload, current_user)
    if shimmed is not None:
        await hydrate_relations([shimmed], RELATIONS)
        return await attach_completeness(
            "interview", public_encode(shimmed, current_user, media_urls=await media_url_owners(current_user))
        )
    from app.questionnaire import forms as unified_forms

    if await unified_forms.authoring_unified():
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="This design workshop has no questionnaire yet. Ask an admin to bind one, then try again.",
        )
    set_key = artisan_set_key(
        payload.artisanIds,
        workshop_id=payload.workshopId,
        design_workshop_id=payload.designWorkshopId,
    )
    if set_key:
        existing = await db.questionnaireinterview.find_first(where={"artisanSetKey": set_key})
        if existing is not None:
            return await merge_into_interview(existing, payload, current_user, check)

    assert_can_create_records(current_user)
    await assert_referenced_records_exist(payload.artisanIds, payload.responses)
    data = clean_data(payload.model_dump(exclude={"artisanIds", "responses"}))
    _standardise(apply_interview_values, data)
    default_interview_date(data)
    data = await attach_location(data)
    stamp_workshop_submission(data, check=check)
    data["createdById"] = current_user.id
    data["artisanSetKey"] = set_key
    merge_field_provenance(data, current_user, previous=None)
    jsonify_metadata(data)
    apply_status_policy_create(current_user, data)
    pin_pending_if_late(data, current_user, check=check)
    try:
        created = await db.questionnaireinterview.create(data=data)
    except UniqueViolationError:
        existing = await db.questionnaireinterview.find_first(where={"artisanSetKey": set_key})
        if existing is not None:
            return await merge_into_interview(existing, payload, current_user, check)
        raise
    if payload.artisanIds:
        await replace_interview_artisans(
            created.id,
            payload.artisanIds,
            workshop_id=payload.workshopId,
            design_workshop_id=payload.designWorkshopId,
        )
    if payload.responses:
        await upsert_responses(created.id, payload.responses, current_user)
    await hydrate_relations([created], RELATIONS)
    return await attach_completeness(
        "interview", public_encode(created, current_user, media_urls=await media_url_owners(current_user))
    )


@router.get("/interviews/by-artisans")
async def interview_for_artisan_set(
    artisanIds: list[str] = Query(default=[]),
    workshopId: str | None = Query(default=None),
    designWorkshopId: str | None = Query(default=None),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any] | None:
    set_key = artisan_set_key(
        artisanIds, workshop_id=workshopId, design_workshop_id=designWorkshopId
    )
    if not set_key:
        return None
    interview = await db.questionnaireinterview.find_first(where={"artisanSetKey": set_key})
    if not interview:
        return None
    await hydrate_relations([interview], RELATIONS)
    return await attach_completeness(
        "interview", public_encode(interview, current_user, media_urls=await media_url_owners(current_user))
    )


COMPLETION_STATUSES = {"COMPLETED", "NEEDS_REVIEW", "NEEDS_REDO"}


async def _zero() -> int:
    return 0


async def _no_rows() -> list[dict[str, Any]]:
    return []


_RECORDED_RESPONSE_SECTIONS_SQL = r"""
    SELECT DISTINCT r."interviewId" AS interview_id, q."sectionId" AS section_id
    FROM "QuestionnaireResponse" r
    JOIN "QuestionnaireQuestion" q ON q."id" = r."questionId"
    WHERE q."sectionId" IS NOT NULL
      AND r."answerText" IS NOT NULL
      AND btrim(r."answerText", E' \t\n\r\f\v') <> ''
"""

_MEDIA_SECTION_SIGNALS_SQL = r"""
    SELECT DISTINCT
      m."questionnaireInterviewId"                           AS interview_id,
      m."extraMetadata"->>'questionId'                       AS question_id,
      m."extraMetadata"->>'sectionCode'                      AS section_code,
      split_part(COALESCE(m."originalFilename", ''), '_', 1) AS filename_token
    FROM "MediaFile" m
    WHERE m."questionnaireInterviewId" IS NOT NULL
"""


async def _derived_completed_sections(
    artisan_id: str | None = None, workshop_ids: list[str] | None = None
) -> dict[str, set[str]]:
    interview_where: dict[str, Any] = (
        {"artisans": {"some": {"artisanId": artisan_id}}} if artisan_id else {}
    )
    resolved = resolve_workshop_ids(workshop_ids)
    if resolved is not None:
        ids, include_unassigned = resolved
        clause = workshop_clause(ids, include_unassigned)
        interview_where.setdefault("AND", []).append(clause or {"id": {"in": []}})
    scoped = bool(interview_where)
    questions, sections, interviews, response_section_rows, media_signal_rows = await gather_reads(
        db.questionnairequestion.find_many(),
        db.questionnairesection.find_many(),
        db.questionnaireinterview.find_many(
            where={**interview_where, "deletedAt": None},
            include=(
                {"artisans": True, "responses": True, "media": True} if scoped else {"artisans": True}
            ),
        ),
        _no_rows() if scoped else db.query_raw(_RECORDED_RESPONSE_SECTIONS_SQL),
        _no_rows() if scoped else db.query_raw(_MEDIA_SECTION_SIGNALS_SQL),
    )
    section_by_question = {q.id: q.sectionId for q in questions if q.sectionId}
    section_id_by_code = {s.code: s.id for s in sections}
    section_id_by_norm_code = {_norm_code(s.code): s.id for s in sections if _norm_code(s.code)}
    valid_codes = {_norm_code(s.code) for s in sections if _norm_code(s.code)}
    by_interview: dict[str, set[str]] = {}
    for row in response_section_rows:
        interview_id = row.get("interview_id")
        section_id = row.get("section_id")
        if interview_id and section_id:
            by_interview.setdefault(str(interview_id), set()).add(str(section_id))
    for row in media_signal_rows:
        interview_id = row.get("interview_id")
        if not interview_id:
            continue
        hits = {
            section_by_question.get(row.get("question_id")),
            section_id_by_code.get(row.get("section_code")),
            section_id_by_norm_code.get(_norm_code(row.get("filename_token"))),
        }
        hits.discard(None)
        if hits:
            by_interview.setdefault(str(interview_id), set()).update(hits)  # type: ignore[arg-type]
    completed: dict[str, set[str]] = {}
    for interview in interviews:
        recorded: set[str] = set()
        for code in section_codes_from_title(interview.title, valid_codes):
            section_id = section_id_by_norm_code.get(code)
            if section_id:
                recorded.add(section_id)
        if not scoped:
            recorded.update(by_interview.get(interview.id, ()))
        else:
            for response in interview.responses or []:
                section_id = section_by_question.get(response.questionId)
                if section_id and not is_empty_value(response.answerText):
                    recorded.add(section_id)
            for media in interview.media or []:
                meta = media.extraMetadata if isinstance(media.extraMetadata, dict) else None
                if meta:
                    section_id = section_by_question.get(meta.get("questionId"))
                    if section_id:
                        recorded.add(section_id)
                    code_section = section_id_by_code.get(meta.get("sectionCode"))
                    if code_section:
                        recorded.add(code_section)
                first_token = (media.originalFilename or "").split("_", 1)[0]
                code_section = section_id_by_norm_code.get(_norm_code(first_token))
                if code_section:
                    recorded.add(code_section)
        if recorded:
            for link in interview.artisans or []:
                completed.setdefault(link.artisanId, set()).update(recorded)
    return completed


@router.get("/completion")
async def completion_matrix(
    artisanId: str | None = None,
    workshopIds: list[str] | None = Query(None),
    _: Any = Depends(get_current_user),
) -> dict[str, Any]:
    status_where = {"artisanId": artisanId} if artisanId else {}
    artisan_where: dict[str, Any] = {"id": artisanId} if artisanId else {}

    resolved_workshops = resolve_workshop_ids(workshopIds)
    if resolved_workshops is not None:
        artisan_where.setdefault("AND", []).append(artisan_workshop_clause(*resolved_workshops))

    unassigned_relevant = resolved_workshops is not None and not resolved_workshops[1]
    sections, artisan_rows, derived, overrides, unassigned_interviews = await gather_reads(
        db.questionnairesection.find_many(where={"isActive": True}, order={"sortOrder": "asc"}),
        db.artisan.find_many(where=artisan_where, order={"name": "asc"}),
        _derived_completed_sections(artisanId, workshopIds),
        db.questionnairesectionstatus.find_many(where=status_where, include={"setBy": True}),
        count_unassigned_interviews([artisanId] if artisanId else None)
        if unassigned_relevant
        else _zero(),
    )
    if (
        artisanId
        and not artisan_rows
        and await db.artisan.find_unique(where={"id": artisanId}) is None
    ):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    artisans = list(artisan_rows)
    artisan_ids = {a.id for a in artisans}

    override_by_cell = {
        (o.artisanId, o.sectionId): o for o in overrides if o.artisanId in artisan_ids
    }

    section_ids = {s.id for s in sections}
    cells: list[dict[str, Any]] = []
    for artisan in artisans:
        derived_ids = derived.get(artisan.id, set())
        for section in sections:
            override = override_by_cell.get((artisan.id, section.id))
            is_derived = section.id in derived_ids
            if override is None and not is_derived:
                continue
            cells.append(
                {
                    "artisanId": artisan.id,
                    "sectionId": section.id,
                    "derived": is_derived,
                    "status": override.status if override else None,
                    "setByName": get_value(override.setBy, "name") if override else None,
                }
            )
    cells = [c for c in cells if c["sectionId"] in section_ids]
    return {
        "sections": [
            {"id": s.id, "code": s.code, "title": s.title, "sortOrder": s.sortOrder}
            for s in sections
        ],
        "artisans": [{"id": a.id, "name": a.name} for a in artisans],
        "cells": cells,
        "unassignedInterviews": int(unassigned_interviews or 0),
        "overridesAreRepositoryWide": True,
    }


@router.put("/completion")
async def set_completion_cell(
    payload: CompletionCellUpdate,
    current_user: Any = Depends(require_content_admin),
) -> dict[str, Any]:
    from app.questionnaire import kinds

    await require_record(db.artisan, payload.artisanId)
    await require_record(db.questionnairesection, payload.sectionId)
    key = {"artisanId_sectionId": {"artisanId": payload.artisanId, "sectionId": payload.sectionId}}
    try:
        payload.status = kinds.normalise_section_status(payload.status)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc
    if payload.status is None:
        await db.questionnairesectionstatus.delete_many(
            where={"artisanId": payload.artisanId, "sectionId": payload.sectionId}
        )
        return {"cleared": True}
    if payload.status not in COMPLETION_STATUSES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"status must be one of {sorted(COMPLETION_STATUSES)} or null",
        )
    record = await db.questionnairesectionstatus.upsert(
        where=key,
        data={
            "create": {
                "artisanId": payload.artisanId,
                "sectionId": payload.sectionId,
                "status": payload.status,
                "setById": current_user.id,
            },
            "update": {"status": payload.status, "setById": current_user.id},
        },
    )
    return public_encode(record)


@router.get("/interviews/{interview_id}")
async def get_interview(
    interview_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    interview = await require_live_record(db.questionnaireinterview, interview_id)
    await hydrate_relations([interview], RELATIONS)
    return await attach_completeness(
        "interview", public_encode(interview, current_user, media_urls=await media_url_owners(current_user))
    )


@router.patch("/interviews/{interview_id}")
async def update_interview(
    interview_id: str,
    payload: QuestionnaireInterviewUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    interview = await require_live_record(db.questionnaireinterview, interview_id)
    if payload.artisanIds is not None:
        payload.artisanIds = await redirect_ids(payload.artisanIds) or []
    data = clean_data(
        payload.model_dump(exclude_unset=True, exclude={"artisanIds", "responses"}),
        clearable=("interviewDate", "place", "language", "notes"),
    )
    _standardise(apply_interview_values, data, interview)
    expected_updated_at = take_expected_updated_at(data)
    check = None
    if "workshopId" in data and data.get("workshopId") != interview.workshopId:
        check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    await assert_payload_workshop(data, current_user, filed_under=interview.designWorkshopId)
    if shims_dr.enabled() and getattr(interview, "clientKey", None):
        shims_dr.legacy_title_change(data)
        await shims_dr.patch_global_interview(interview, payload, current_user)
        payload.artisanIds = None
        payload.responses = None
    data = await attach_location(data)
    async with db.tx() as tx:
        assert_expected_updated_at(interview, expected_updated_at)
        await claim_expected_updated_at(tx.questionnaireinterview, interview, expected_updated_at)
        privileged = await guard_record_edit(
            interview, current_user, data, "questionnaire", client=tx
        )
        await apply_status_policy_update(current_user, interview, data)
        stamp_workshop_submission(data, check=check, record=interview)
        pin_pending_if_late(data, current_user, check=check, record=interview)
        merge_field_provenance(data, current_user, previous=interview)
        resubmit_status(interview, current_user, data)
        jsonify_metadata(data)
        if data:
            await tx.questionnaireinterview.update(where={"id": interview_id}, data=data)
        after_workshop_id = data.get("workshopId", interview.workshopId)
        after_design_workshop_id = data.get("designWorkshopId", interview.designWorkshopId)
        if payload.artisanIds is not None:
            link_count = await tx.questionnaireinterviewartisan.count(
                where={"interviewId": interview_id}
            )
            if not privileged:
                assert_can_contribute_relation(
                    interview, current_user, link_count > 0, "artisanIds"
                )
            await replace_interview_artisans(
                interview_id,
                payload.artisanIds,
                workshop_id=after_workshop_id,
                design_workshop_id=after_design_workshop_id,
                client=tx,
            )
        elif (after_workshop_id, after_design_workshop_id) != (
            interview.workshopId,
            interview.designWorkshopId,
        ):
            await resync_interview_set_key(
                interview_id,
                workshop_id=after_workshop_id,
                design_workshop_id=after_design_workshop_id,
                client=tx,
            )
        if payload.responses is not None:
            await upsert_responses(interview_id, payload.responses, current_user, client=tx)
    updated = await db.questionnaireinterview.find_unique(where={"id": interview_id})
    await hydrate_relations([updated], RELATIONS)
    return await attach_completeness(
        "interview", public_encode(updated, current_user, media_urls=await media_url_owners(current_user))
    )


_MERGED_TITLE_MAX = 220

_MERGE_SELF_CODE = "merge_self"
_MERGE_SCOPE_CODE = "merge_cross_scope"
_MERGE_CONFLICT_CODE = "merge_answer_conflict"
_MERGE_COVERAGE_CODE = "merge_artisan_coverage"

_MERGE_SCOPE_FIELDS = ("workshopId", "designWorkshopId")


def _merge_scope(interview: Any) -> tuple[Any, ...]:
    return tuple(get_value(interview, field) for field in _MERGE_SCOPE_FIELDS)


def _answer_disagreements(source_row: Any, target_row: Any) -> list[str]:
    differing: list[str] = []
    for field in ("answerText", "notes"):
        mine, theirs = get_value(source_row, field), get_value(target_row, field)
        if is_empty_value(mine) or is_empty_value(theirs):
            continue
        if mine != theirs:
            differing.append(field)
    return differing


async def _duplicate_pair_aliases(pair_id: str | None, user: Any) -> dict[str, str]:
    if not pair_id:
        return {}
    if not is_content_admin(user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Admin access required")
    pair = await db.artisanduplicatecandidate.find_unique(where={"id": pair_id})
    if pair is None or str(getattr(pair.kind, "value", pair.kind)) != "WITHIN" or not pair.artisanBId:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="That duplicate pair no longer exists.",
        )
    return {pair.artisanAId: pair.artisanBId, pair.artisanBId: pair.artisanAId}


@router.post("/interviews/{interview_id}/merge-into/{target_id}")
async def merge_interview_into(
    interview_id: str,
    target_id: str,
    current_user: Any = Depends(get_current_user),
    duplicatePairId: str | None = Query(default=None, max_length=64),
) -> dict[str, Any]:
    assert_can_delete(current_user)
    if interview_id == target_id:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail={
                "code": _MERGE_SELF_CODE,
                "message": "An interview cannot be merged into itself.",
            },
        )
    source = await require_live_record(db.questionnaireinterview, interview_id)
    target = await require_live_record(db.questionnaireinterview, target_id)

    for side in sorted({source.designWorkshopId or "", target.designWorkshopId or ""} - {""}):
        await assert_may_write_a_record_filed_under(side, current_user)

    if _merge_scope(source) != _merge_scope(target):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail={
                "code": _MERGE_SCOPE_CODE,
                "message": (
                    f"“{source.title}” and “{target.title}” are filed under different workshops, so "
                    "one cannot be moved into the other. File them under the same workshop first."
                ),
                "scopeFields": list(_MERGE_SCOPE_FIELDS),
                "thisInterview": dict(zip(_MERGE_SCOPE_FIELDS, _merge_scope(source), strict=True)),
                "targetInterview": dict(
                    zip(_MERGE_SCOPE_FIELDS, _merge_scope(target), strict=True)
                ),
            },
        )

    source_rows, target_rows, source_links, target_links, sections = await gather_reads(
        db.questionnaireresponse.find_many(
            where={"interviewId": interview_id}, include={"question": True}
        ),
        db.questionnaireresponse.find_many(where={"interviewId": target_id}),
        db.questionnaireinterviewartisan.find_many(
            where={"interviewId": interview_id}, include={"artisan": True}
        ),
        db.questionnaireinterviewartisan.find_many(where={"interviewId": target_id}),
        db.questionnairesection.find_many(),
    )

    target_by_question = {row.questionId: row for row in target_rows}
    conflicts: list[dict[str, Any]] = []
    fills: list[tuple[str, dict[str, Any]]] = []
    movable: list[str] = []
    for row in source_rows:
        other = target_by_question.get(row.questionId)
        if other is None:
            movable.append(row.questionId)
            continue
        differing = _answer_disagreements(row, other)
        if differing:
            conflicts.append(
                {
                    "questionId": row.questionId,
                    "sectionCode": get_value(row.question, "sectionCode"),
                    "prompt": get_value(row.question, "prompt"),
                    "fields": differing,
                    "thisInterview": {field: get_value(row, field) for field in differing},
                    "targetInterview": {field: get_value(other, field) for field in differing},
                }
            )
            continue
        fill = {
            field: get_value(row, field)
            for field in ("answerText", "notes")
            if not is_empty_value(get_value(row, field)) and is_empty_value(get_value(other, field))
        }
        if "answerText" in fill:
            fill["answeredById"] = row.answeredById
            fill["answerOrigin"] = get_value(row, "answerOrigin")
        if fill:
            fills.append((other.id, fill))

    if conflicts:
        named = "; ".join(
            f"{c['sectionCode'] or '?'} — {c['prompt'] or c['questionId']}" for c in conflicts
        )
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={
                "code": _MERGE_CONFLICT_CODE,
                "message": (
                    f"Nothing was moved. {count_noun(len(conflicts), 'question')} "
                    f"{by_count(len(conflicts), 'is', 'are')} answered differently in "
                    f"“{source.title}” and “{target.title}”: {named}. Open each one, keep the "
                    "wording that is right, then merge."
                ),
                "conflicts": conflicts,
            },
        )

    covered = {link.artisanId for link in target_links}
    aliases = await _duplicate_pair_aliases(duplicatePairId, current_user)
    uncovered = [
        link
        for link in source_links
        if link.artisanId not in covered and aliases.get(link.artisanId) not in covered
    ]
    if uncovered:
        names = ", ".join(get_value(link.artisan, "name") or link.artisanId for link in uncovered)
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail={
                "code": _MERGE_COVERAGE_CODE,
                "message": (
                    f"Nothing was moved. “{source.title}” covers {names}, who “{target.title}” does "
                    "not — their answers would stop appearing anywhere. Add them to "
                    f"“{target.title}” first, then merge."
                ),
                "artisanIds": [link.artisanId for link in uncovered],
            },
        )

    valid_codes = {_norm_code(s.code) for s in sections if _norm_code(s.code)}
    moving_codes = sorted(
        section_codes_from_title(source.title, valid_codes)
        - section_codes_from_title(target.title, valid_codes)
    )

    target_data: dict[str, Any] = {}
    if moving_codes:
        candidate = f"{target.title} + {source.title}"
        if len(candidate) > _MERGED_TITLE_MAX:
            candidate = f"{target.title} (sections {', '.join(moving_codes)})"
        if len(candidate) <= _MERGED_TITLE_MAX:
            target_data["title"] = candidate

    stored_extra = get_value(target, "extraMetadata")
    extra = dict(stored_extra) if isinstance(stored_extra, dict) else {}
    history = extra.get("mergedFrom")
    extra["mergedFrom"] = [
        *(history if isinstance(history, list) else []),
        {
            "interviewId": source.id,
            "title": source.title,
            "sectionCodes": moving_codes,
            "responsesMoved": len(movable),
            "mergedAt": datetime.now(UTC).isoformat(),
            "mergedById": get_value(current_user, "id"),
        },
    ]
    target_data["extraMetadata"] = extra
    jsonify_metadata(target_data)

    async with db.tx() as tx:
        privileged = await guard_record_edit(
            source,
            current_user,
            {"mergedIntoInterviewId": target.id, "mergedIntoTitle": target.title},
            "questionnaire",
            client=tx,
        )
        if not privileged:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=(
                    "Only the researcher who recorded this interview, an admin, or someone they "
                    "have granted edit access can move it into another interview."
                ),
            )
        await tx.questionnaireinterview.update(where={"id": target_id}, data=target_data)
        if movable:
            await tx.questionnaireresponse.update_many(
                where={"interviewId": interview_id, "questionId": {"in": movable}},
                data={"interviewId": target_id},
            )
        for row_id, data in fills:
            await tx.questionnaireresponse.update(where={"id": row_id}, data=data)
        await tx.mediafile.update_many(
            where={"questionnaireInterviewId": interview_id},
            data={"questionnaireInterviewId": target_id},
        )
        await tx.questionnaireinterview.delete(where={"id": interview_id})

    survivor = await db.questionnaireinterview.find_unique(where={"id": target_id})
    await hydrate_relations([survivor], RELATIONS)
    return public_encode(survivor, current_user, media_urls=await media_url_owners(current_user))



async def _consolidated_or_404(
    artisan_id: str, current_user: Any, workshop_ids: list[str] | None = None
) -> dict[str, Any]:
    payload = await consolidate_for_artisan(artisan_id, current_user, workshop_ids)
    if payload is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return payload


@router.get("/artisans/{artisan_id}/consolidated")
async def consolidated_questionnaire(
    artisan_id: str,
    workshopIds: list[str] | None = Query(None),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    return await _consolidated_or_404(artisan_id, current_user, workshopIds)


@router.get("/artisans/{artisan_id}/consolidated.csv")
async def consolidated_questionnaire_csv(
    artisan_id: str,
    workshopIds: list[str] | None = Query(None),
    current_user: Any = Depends(get_current_user),
) -> Response:
    artisan = await require_record(db.artisan, artisan_id)
    scope = await owner_download_scope(current_user, get_value(artisan, "createdById"))
    if scope is not None and artisan_id not in scope.get("artisans", set()):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Your download access to this researcher's data does not cover this artisan. "
            "You can still read the consolidated questionnaire on screen.",
        )
    payload = await _consolidated_or_404(artisan_id, current_user, workshopIds)
    output = StringIO()
    writer = csv.writer(output)
    writer.writerow(CSV_COLUMNS)
    for row in consolidated_rows(payload):
        writer.writerow(row)
    name = payload.get("artisan", {}).get("name") or artisan_id
    return csv_response(
        naming.export_filename("Questionnaire", "Export", "csv", subject=name),
        output.getvalue(),
    )


@router.delete("/interviews/{interview_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_interview(
    interview_id: str, current_user: Any = Depends(get_current_user)
) -> None:
    assert_can_delete(current_user)
    interview = await require_live_record(db.questionnaireinterview, interview_id)
    await assert_may_write_a_record_filed_under(interview.designWorkshopId, current_user)
    await db.questionnaireinterview.update(
        where={"id": interview_id},
        data={
            "deletedAt": datetime.now(UTC),
            "deletedById": current_user.id,
            "artisanSetKey": None,
        },
    )


INTERVIEW_SET_TAKEN = "INTERVIEW_SET_TAKEN"


@router.post("/interviews/{interview_id}/restore")
async def restore_interview(
    interview_id: str, current_user: Any = Depends(require_content_admin)
) -> dict[str, Any]:
    interview = await require_record(db.questionnaireinterview, interview_id)
    await assert_may_write_a_record_filed_under(interview.designWorkshopId, current_user)
    if interview.deletedAt is not None:
        try:
            async with db.tx() as tx:
                await tx.questionnaireinterview.update(
                    where={"id": interview_id}, data={"deletedAt": None, "deletedById": None}
                )
                await resync_interview_set_key(
                    interview_id,
                    workshop_id=interview.workshopId,
                    design_workshop_id=interview.designWorkshopId,
                    client=tx,
                )
        except HTTPException as exc:
            if exc.status_code != status.HTTP_409_CONFLICT or not isinstance(exc.detail, dict):
                raise
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail={
                    **exc.detail,
                    "code": INTERVIEW_SET_TAKEN,
                    "message": (
                        "Another interview now covers these artisans. Merge or delete it, "
                        "then restore this one."
                    ),
                },
            ) from exc
    restored = await db.questionnaireinterview.find_unique(where={"id": interview_id})
    await hydrate_relations([restored], RELATIONS)
    return await attach_completeness(
        "interview", public_encode(restored, current_user, media_urls=await media_url_owners(current_user))
    )


def _standardise(apply: Any, data: dict[str, Any], stored: Any = None) -> None:
    try:
        apply(data, stored)
    except StandardValueError as exc:
        raise HTTPException(status_code=422, detail=exc.message) from exc
