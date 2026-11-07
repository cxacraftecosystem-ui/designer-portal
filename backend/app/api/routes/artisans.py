import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from pydantic import BaseModel, Field

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import (
    assert_can_delete,
    get_current_user,
    get_value,
    require_record_creator,
)
from app.schemas.records import ArtisanCreate, ArtisanUpdate, drop_masked_identity_numbers
from app.services import (
    aadhaar_fingerprint,
    artisan_card,
    artisan_duplicates,
    artisan_merge,
    identity_probe,
)
from app.services.aadhaar import records as aadhaar_records
from app.services.access import guard_record_edit
from app.services.artisan_form_contract import assert_form_contract, take_form_contract
from app.services.artisan_identity import aadhaar_error, mask_aadhaar, normalize_aadhaar
from app.services.completeness import (
    attach as attach_completeness,
    filter_or_422 as completeness_filter,
)
from app.services.concurrency import gather_reads
from app.services.pagination import normalize_pagination, page_payload
from app.services.record_design_workshop import (
    assert_may_write_a_record_filed_under,
    assert_payload_workshop,
)
from app.services.record_filters import (
    artisan_workshop_clause,
    resolve_craft_ids,
    resolve_workshop_ids,
)
from app.services.records import (
    RECORD_STATUSES,
    Relation,
    apply_status_policy_create,
    apply_status_policy_update,
    attach_location,
    clean_data,
    contains,
    count_and_page,
    enum_filter_or_422,
    hydrate_relations,
    include_of,
    lock_and_assert_expected_updated_at,
    merge_field_provenance,
    prose_contains,
    public_encode,
    require_record,
    resolve_craft_id,
    resubmit_status,
    take_expected_updated_at,
    viewable_where,
)
from app.services.standard_values import StandardValueError, apply_artisan_values
from app.services.workshop_access import (
    enforce_workshop_submission,
    link_workshop_artisan,
    pin_pending_if_late,
    stamp_workshop_submission,
    sync_workshop_artisan,
)

logger = logging.getLogger("app.artisans")


def _card_source(request: Request | None) -> str:
    from app.services import artisan_card_store as card_store
    from app.services.usage import CLIENT_APP_HEADER, normalise_client_app

    raw = request.headers.get(CLIENT_APP_HEADER) if request is not None else None
    return card_store.SOURCE_ANDROID if normalise_client_app(raw) == "android" else card_store.SOURCE_WEB


async def _attach_cards(items: list[dict[str, Any]], user: Any, source: str = "WEB") -> None:
    settings = get_settings()
    if not items or not artisan_card.active(settings):
        return
    from app.services import artisan_card_store as card_store

    ids = [item["id"] for item in items if isinstance(item, dict) and item.get("id")]
    try:
        cards = await card_store.ensure_cards(
            ids, user_id=get_value(user, "id"), source=source, settings=settings
        )
        links = await db.crossportalartisanlink.find_many(
            where={"artisanId": {"in": ids}, "remoteCardCode": {"not": None}}
        )
    except Exception:
        logger.warning("artisan list: card fields left off this page", exc_info=True)
        return
    linked: dict[str, list[str]] = {}
    for link in links:
        linked.setdefault(link.artisanId, []).append(link.remoteCardCode)
    for item in items:
        if not isinstance(item, dict) or not item.get("id"):
            continue
        card = cards.get(item["id"])
        item["card"] = card_store.card_wire(card, settings) if card is not None else None
        item.setdefault("aadhaarLast4", None)
        item["linkedCardCodes"] = linked.get(item["id"], [])

router = APIRouter(prefix="/artisans", tags=["artisans"])

RELATIONS = (
    Relation("craft", "craft", "craftId"),
    Relation("location", "location", "locationId"),
    Relation("createdBy", "user", "createdById"),
    Relation("workshop", "workshop", "workshopId"),
)
INCLUDE = include_of(RELATIONS)

_CLEARABLE_COLUMNS = (
    "localName",
    "gender",
    "phone",
    "phoneCountryCode",
    "email",
    "address",
    "notes",
    "dateOfBirth",
    "craftStartDate",
    "experienceYears",
    "experienceMonths",
    "dos",
    "donts",
)

_IDENTITY_CONSTRAINTS = {
    "aadhaarNumber": "Aadhaar number",
    "pehchanCardNumber": "Artisan Pehchan Card number",
}

_IDENTITY_COLUMNS = {
    "aadhaarNumber": aadhaar_fingerprint.FINGERPRINT_FIELD,
    "pehchanCardNumber": "pehchanCardNumber",
}


def _violated_identity_field(error: Exception) -> str | None:
    text = str(error)
    if "unique" not in text.lower():
        return None
    for field, column in _IDENTITY_COLUMNS.items():
        if column in text or field in text:
            return field
    return None


def _fingerprint_unavailable() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        detail="Aadhaar numbers can't be saved right now. Try again shortly.",
    )


def _seal_aadhaar(data: dict[str, Any], previous: Any = None) -> str | None:
    try:
        return aadhaar_fingerprint.seal(data, previous=previous)
    except aadhaar_fingerprint.FingerprintKeyMissing as exc:
        logger.error("artisan write refused: %s", exc)
        raise _fingerprint_unavailable() from exc


async def _identity_conflict(
    field: str, value: str, exclude_id: str | None = None, existing: Any = None
) -> HTTPException:
    if existing is None and value:
        where = (
            aadhaar_fingerprint.match_where(
                value, legacy=await aadhaar_fingerprint.legacy_pending(db)
            )
            if field == "aadhaarNumber"
            else {field: value}
        )
        existing = await db.artisan.find_first(where=where, include={"craft": True})
    detail: dict[str, Any] = {
        "code": "artisan_identity_conflict",
        "field": field,
        "message": f"Another artisan is already recorded with this {_IDENTITY_CONSTRAINTS[field]}.",
    }
    if existing is not None and existing.id != exclude_id:
        detail["existingArtisan"] = {
            "id": existing.id,
            "name": existing.name,
            "place": existing.place,
            "craft": getattr(getattr(existing, "craft", None), "name", None),
        }
        detail["message"] = (
            f"{existing.name} ({existing.place}) is already recorded with this "
            f"{_IDENTITY_CONSTRAINTS[field]}. Open that artisan instead of creating a duplicate."
        )
    if field == "aadhaarNumber" and value:
        detail["maskedValue"] = mask_aadhaar(value)
    return HTTPException(status_code=status.HTTP_409_CONFLICT, detail=detail)


def _may_read_full_aadhaar(user: Any, artisan: Any) -> bool:
    from app.core.deps import get_value, has_rank

    if has_rank(user, "PROFESSOR"):
        return True
    return bool(get_value(user, "id")) and get_value(artisan, "createdById") == get_value(
        user, "id"
    )


def _mask_artisan_identity(payload: Any, user: Any, artisan: Any) -> Any:
    if not isinstance(payload, dict) or "aadhaarNumber" not in payload:
        return payload
    if _may_read_full_aadhaar(user, artisan):
        return payload
    payload["aadhaarNumber"] = mask_aadhaar(payload.get("aadhaarNumber"))
    return payload


PEHCHAN_PAIR_DETAIL = (
    "Enter the Artisan Pehchan Card number, or set the card to 'No' if the artisan does not hold one."
)


def assert_pehchan_pair(artisan: Any, data: dict[str, Any], *, detail: str = PEHCHAN_PAIR_DETAIL) -> None:
    answered_yes = data.get("pehchanCardAvailable") is True
    clears_number = "pehchanCardNumber" in data and not data["pehchanCardNumber"]
    still_yes = data.get("pehchanCardAvailable", artisan.pehchanCardAvailable) is True
    if answered_yes or (clears_number and still_yes):
        number = data.get("pehchanCardNumber", artisan.pehchanCardNumber)
        if not number:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=detail)


async def _guard_identity_conflicts(
    data: dict[str, Any], exclude_id: str | None = None, aadhaar: str | None = None
) -> None:
    clauses: list[dict[str, Any]] = []
    if aadhaar:
        legacy = await aadhaar_fingerprint.legacy_pending(db)
        clauses.append(aadhaar_fingerprint.match_where(aadhaar, legacy=legacy))
    pehchan = data.get("pehchanCardNumber")
    if pehchan:
        clauses.append({"pehchanCardNumber": pehchan})
    if not clauses:
        return
    clashes = await db.artisan.find_many(where={"OR": clauses}, include={"craft": True})
    for clash in clashes:
        if clash.id == exclude_id:
            continue
        if aadhaar and aadhaar_fingerprint.holds(clash, aadhaar):
            raise await _identity_conflict("aadhaarNumber", aadhaar, exclude_id, existing=clash)
        if pehchan and getattr(clash, "pehchanCardNumber", None) == pehchan:
            raise await _identity_conflict("pehchanCardNumber", pehchan, exclude_id, existing=clash)



async def _likely_duplicates(
    payload: ArtisanCreate, data: dict[str, Any], viewer: Any = None
) -> list[tuple[artisan_duplicates.Profile, artisan_duplicates.Score]]:
    from app.api.routes.artisan_duplicates import profile_from_form

    try:
        location = payload.location
        profile = await profile_from_form(
            name=payload.name,
            localName=payload.localName,
            gender=payload.gender,
            dateOfBirth=payload.dateOfBirth,
            phone=payload.phone,
            place=payload.place,
            state=getattr(location, "state", None),
            district=getattr(location, "district", None),
            village=getattr(location, "village", None),
            craftId=payload.craftId,
            craftName=payload.craftName,
            fingerprint=data.get(aadhaar_fingerprint.FINGERPRINT_FIELD),
            pehchanCardNumber=data.get("pehchanCardNumber"),
        )
        return await artisan_duplicates.candidates_for_profile(profile, viewer=viewer)
    except Exception:
        logger.exception("duplicate scoring failed on an artisan create")
        return []


def _hold_if_duplicate(
    likely: list[tuple[artisan_duplicates.Profile, artisan_duplicates.Score]],
    acknowledged: list[str] | None,
) -> None:
    if acknowledged is None or not getattr(get_settings(), "artisan_duplicate_hold", True):
        return
    seen = set(acknowledged)
    held = [
        (other, result)
        for other, result in likely
        if result.score >= artisan_duplicates.HOLD_THRESHOLD and other.id not in seen
    ]
    if not held:
        return
    shown = held[: artisan_duplicates.CHECK_MAX_CANDIDATES]
    top = shown[0][0]
    raise HTTPException(
        status_code=status.HTTP_409_CONFLICT,
        detail={
            "code": "artisan_possible_duplicate",
            "message": (
                f"This may be {top.display.get('name')} ({top.display.get('place')}), "
                "already recorded."
            ),
            "candidates": [
                artisan_duplicates.candidate_payload(other, result, typed_aadhaar=True)
                for other, result in shown
            ],
            "existingArtisan": {
                "id": top.id,
                "name": top.display.get("name"),
                "place": top.display.get("place"),
                "craft": top.display.get("craft"),
            },
        },
    )


async def _store_likely(
    artisan_id: str,
    likely: list[tuple[artisan_duplicates.Profile, artisan_duplicates.Score]],
    acknowledged: list[str] | None,
) -> None:
    try:
        await artisan_duplicates.store_candidates(
            artisan_id, likely, source="CREATE", acknowledged=frozenset(acknowledged or ())
        )
    except Exception:
        logger.exception("storing likely duplicates failed on an artisan create")


@router.get("")
async def list_artisans(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    craft: str | None = None,
    craftId: str | None = None,
    craftIds: list[str] | None = Query(None),
    workshopId: str | None = None,
    designWorkshopId: str | None = None,
    place: str | None = None,
    statusFilter: str | None = None,
    createdBy: str | None = None,
    workshopIds: list[str] | None = Query(None),
    completeness: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
    request: Request = None,  # type: ignore[assignment]  # FastAPI injects it; direct calls may omit it
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
    and_filters: list[dict[str, Any]] = []
    vis = await viewable_where(current_user)
    if vis:
        and_filters.append(vis)
    if search:
        where["OR"] = [
            {"name": contains(search)},
            {"localName": contains(search)},
            {"place": contains(search)},
            prose_contains("notes", search),
            {"craft": {"is": {"name": contains(search)}}},
        ]
    if craft:
        where["craft"] = {"is": {"name": contains(craft)}}
    if craftId:
        where["craftId"] = craftId
    resolved_crafts = resolve_craft_ids(craftIds)
    if resolved_crafts is not None:
        and_filters.append({"craftId": {"in": resolved_crafts}})
    if workshopId:
        and_filters.append(
            {
                "OR": [
                    {"workshopId": workshopId},
                    {"workshops": {"some": {"workshopId": workshopId}}},
                ]
            }
        )
    if designWorkshopId:
        where["designWorkshopId"] = designWorkshopId
    resolved_workshops = resolve_workshop_ids(workshopIds)
    if resolved_workshops is not None:
        and_filters.append(artisan_workshop_clause(*resolved_workshops))
    if place:
        where["place"] = contains(place)
    if statusFilter:
        where["status"] = enum_filter_or_422(statusFilter, RECORD_STATUSES)
    if createdBy:
        where["createdById"] = createdBy
    complete_clause = completeness_filter("artisan", completeness)
    if complete_clause:
        and_filters.append(complete_clause)
    if and_filters:
        where["AND"] = and_filters
    total, items = await count_and_page(
        db.artisan,
        where=where,
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
        relations=RELATIONS,
    )
    encoded = await attach_completeness("artisan", public_encode(items, current_user))
    await _attach_cards(encoded, current_user, _card_source(request))
    return page_payload(encoded, total, page, page_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_artisan(
    payload: ArtisanCreate,
    current_user: Any = Depends(require_record_creator),
    request: Request = None,  # type: ignore[assignment]  # FastAPI injects it; direct calls may omit it
) -> dict[str, Any]:
    acknowledged = payload.duplicateAcknowledged
    data = clean_data(payload.model_dump(exclude={"duplicateAcknowledged"}))
    aadhaar = _seal_aadhaar(data)
    _standardise(apply_artisan_values, data)
    assert_form_contract(take_form_contract(data), data)
    check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    await assert_payload_workshop(data, current_user, filed_under=None)
    await _guard_identity_conflicts(data, aadhaar=aadhaar)
    likely = await _likely_duplicates(payload, data, current_user)
    _hold_if_duplicate(likely, acknowledged)
    data = await resolve_craft_id(data, current_user)
    data = await attach_location(data)
    stamp_workshop_submission(data, check=check)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    apply_status_policy_create(current_user, data)
    pin_pending_if_late(data, current_user, check=check)
    try:
        created = await db.artisan.create(data=data, include=INCLUDE)
    except Exception as exc:
        field = _violated_identity_field(exc)
        if field is None:
            raise
        value = aadhaar if field == "aadhaarNumber" else data.get(field)
        raise await _identity_conflict(field, value) from exc
    await link_workshop_artisan(created.workshopId, created.id)
    if artisan_card.active(get_settings()):
        from app.services import artisan_card_store as card_store

        try:
            await card_store.ensure_card(
                created.id, user_id=get_value(current_user, "id"), source=_card_source(request)
            )
        except Exception:
            logger.warning("artisan create: card not issued yet", exc_info=True)
    await _store_likely(created.id, likely, acknowledged)
    return await attach_completeness("artisan", public_encode(created, current_user))


@router.get("/{artisan_id}")
async def get_artisan(
    artisan_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    artisan = await db.artisan.find_unique(where={"id": artisan_id})
    if artisan is None:
        survivor = await artisan_merge.resolve(artisan_id)
        if survivor:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND, detail=artisan_merge.merged_detail(survivor)
            )
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    await hydrate_relations([artisan], RELATIONS)
    return await attach_completeness("artisan", public_encode(artisan, current_user))


@router.patch("/{artisan_id}")
async def update_artisan(
    artisan_id: str,
    payload: ArtisanUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    artisan = await require_record(db.artisan, artisan_id)
    previous_workshop_id = artisan.workshopId
    data = clean_data(payload.model_dump(exclude_unset=True), clearable=_CLEARABLE_COLUMNS)
    _standardise(apply_artisan_values, data, artisan)
    expected_updated_at = take_expected_updated_at(data)
    assert_form_contract(take_form_contract(data), data, stored=artisan)
    data = drop_masked_identity_numbers(data)
    aadhaar = _seal_aadhaar(data, previous=artisan)
    check = None
    if "workshopId" in data and data.get("workshopId") != artisan.workshopId:
        check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    await assert_payload_workshop(data, current_user, filed_under=artisan.designWorkshopId)
    data = await resolve_craft_id(data, current_user, stored=artisan)
    data = await attach_location(data)
    async with db.tx() as tx:
        await lock_and_assert_expected_updated_at(tx, "artisan", artisan, expected_updated_at)
        await guard_record_edit(artisan, current_user, data, "artisan", client=tx)
        await apply_status_policy_update(current_user, artisan, data)
        stamp_workshop_submission(data, check=check, record=artisan)
        pin_pending_if_late(data, current_user, check=check, record=artisan)
        merge_field_provenance(data, current_user, previous=artisan)
        resubmit_status(artisan, current_user, data)
        assert_pehchan_pair(artisan, data)
        await _guard_identity_conflicts(data, exclude_id=artisan_id, aadhaar=aadhaar)
        await aadhaar_records.withdraw_for_identity_edit(artisan, current_user, data, client=tx)
        try:
            updated = await tx.artisan.update(where={"id": artisan_id}, data=data, include=INCLUDE)
        except Exception as exc:
            field = _violated_identity_field(exc)
            if field is None:
                raise
            value = aadhaar if field == "aadhaarNumber" else data.get(field)
            raise await _identity_conflict(field, value, exclude_id=artisan_id) from exc
    await sync_workshop_artisan(previous_workshop_id, updated.workshopId, updated.id)
    await artisan_duplicates.rescore_artisan(updated.id)
    return await attach_completeness("artisan", public_encode(updated, current_user))


class AadhaarLookup(BaseModel):
    number: str = Field(max_length=40)


@router.post("/lookup/aadhaar")
async def lookup_artisan_by_aadhaar_body(
    payload: AadhaarLookup, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    return await _lookup_aadhaar(payload.number, current_user)


@router.get("/lookup/aadhaar")
async def lookup_artisan_by_aadhaar(
    number: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    return await _lookup_aadhaar(number, current_user)


async def _lookup_aadhaar(number: str, current_user: Any) -> dict[str, Any]:
    normalized = normalize_aadhaar(number)
    if not normalized or aadhaar_error(normalized):
        return {"found": False, "artisan": None}
    await identity_probe.assert_may_probe(current_user)
    try:
        where = aadhaar_fingerprint.match_where(
            normalized, legacy=await aadhaar_fingerprint.legacy_pending(db)
        )
    except aadhaar_fingerprint.FingerprintKeyMissing as exc:
        logger.error("aadhaar lookup refused: %s", exc)
        raise _fingerprint_unavailable() from exc
    existing = await db.artisan.find_first(where=where, include={"craft": True, "workshop": True})
    if existing is None:
        await identity_probe.record_miss(current_user)
        return {"found": False, "artisan": None}
    return {
        "found": True,
        "artisan": {
            "id": existing.id,
            "name": existing.name,
            "place": existing.place,
            "craft": getattr(getattr(existing, "craft", None), "name", None),
            "workshop": getattr(getattr(existing, "workshop", None), "title", None),
        },
    }


@router.get("/{artisan_id}/questionnaire")
async def get_artisan_questionnaire(
    artisan_id: str, _: Any = Depends(get_current_user)
) -> dict[str, Any]:
    artisan, responses, interview_rows = await gather_reads(
        db.artisan.find_unique(where={"id": artisan_id}),
        db.questionnaireresponse.find_many(
            where={
                "interview": {
                    "is": {"artisans": {"some": {"artisanId": artisan_id}}, "deletedAt": None}
                },
                "answerText": {"not": None},
            },
            include={"question": True, "interview": True, "answeredBy": True},
            order={"createdAt": "asc"},
        ),
        db.questionnaireinterview.find_many(
            where={"artisans": {"some": {"artisanId": artisan_id}}, "deletedAt": None},
            include={"artisans": {"include": {"artisan": True}}, "media": True},
            order={"createdAt": "desc"},
        ),
    )
    if not artisan:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    answered: list[dict[str, Any]] = []
    for response in responses:
        if not (response.answerText and response.answerText.strip()):
            continue
        question = response.question
        interview = response.interview
        answered_by = response.answeredBy
        answered.append(
            {
                "responseId": response.id,
                "questionId": response.questionId,
                "prompt": question.prompt if question else None,
                "sectionCode": question.sectionCode if question else None,
                "sectionTitle": question.sectionTitle if question else None,
                "sortOrder": question.sortOrder if question else 0,
                "answerText": response.answerText,
                "notes": response.notes,
                "interviewId": response.interviewId,
                "interviewTitle": interview.title if interview else None,
                "interviewDate": interview.interviewDate if interview else None,
                "answeredByName": answered_by.name if answered_by else None,
            }
        )
    answered.sort(key=lambda item: ((item.get("sectionCode") or ""), item.get("sortOrder") or 0))

    interviews: list[dict[str, Any]] = []
    for interview in interview_rows:
        co_artisans = [
            link.artisan.name
            for link in (interview.artisans or [])
            if link.artisan and link.artisanId != artisan_id
        ]
        interviews.append(
            {
                "interviewId": interview.id,
                "title": interview.title,
                "notes": interview.notes,
                "interviewDate": interview.interviewDate,
                "place": interview.place,
                "language": interview.language,
                "status": interview.status,
                "artisanCount": len(interview.artisans or []),
                "coArtisans": co_artisans,
                "media": public_encode(interview.media or []),
            }
        )
    return public_encode(
        {
            "artisanId": artisan_id,
            "answered": answered,
            "total": len(answered),
            "interviews": interviews,
        }
    )


@router.delete("/{artisan_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_artisan(artisan_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    artisan = await require_record(db.artisan, artisan_id)
    await assert_may_write_a_record_filed_under(artisan.designWorkshopId, current_user)
    await db.artisan.delete(where={"id": artisan_id})


def _standardise(apply: Any, data: dict[str, Any], stored: Any = None) -> None:
    try:
        apply(data, stored)
    except StandardValueError as exc:
        raise HTTPException(status_code=422, detail=exc.message) from exc



class ArtisanConsentIn(BaseModel):
    state: str = Field(pattern="^(GRANTED|REFUSED)$")
    note: str | None = Field(default=None, max_length=2000)


async def _artisan_consent(artisan_id: str) -> dict[str, Any]:
    from app.services import dictation_consent

    row = (await dictation_consent.artisan_rows([artisan_id])).get(artisan_id) or {}
    recorded_at = row.get("recordedAt")
    return {
        "artisanId": artisan_id,
        "purpose": "EXTERNAL_PROCESSING",
        "state": row.get("state") or "NOT_RECORDED",
        "basis": row.get("basis"),
        "source": row.get("source"),
        "recordedAt": recorded_at.isoformat() if hasattr(recorded_at, "isoformat") else recorded_at,
    }


@router.get("/{artisan_id}/consent")
async def get_artisan_consent(artisan_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    await require_record(db.artisan, artisan_id)
    return await _artisan_consent(artisan_id)


@router.post("/{artisan_id}/consent", status_code=status.HTTP_201_CREATED)
async def record_artisan_consent(
    artisan_id: str, payload: ArtisanConsentIn, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    from app.core.deps import is_content_admin, may_edit_lower_ranked_record
    from app.services import dictation_consent

    artisan = await require_record(db.artisan, artisan_id)
    await assert_may_write_a_record_filed_under(artisan.designWorkshopId, current_user)
    owner_id = get_value(artisan, "createdById")
    allowed = (
        is_content_admin(current_user)
        or (owner_id is not None and get_value(current_user, "id") == owner_id)
        or await may_edit_lower_ranked_record(current_user, owner_id)
    )
    if not allowed:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only someone who may edit this artisan can record their consent.",
        )
    await dictation_consent.record_artisan_consent(
        artisan_id, state=payload.state, actor_id=str(get_value(current_user, "id")), note=payload.note
    )
    return await _artisan_consent(artisan_id)
