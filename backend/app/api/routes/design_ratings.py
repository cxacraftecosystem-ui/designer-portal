from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.deps import can_run_design_workshops, get_current_user, is_admin
from app.schemas.design_ratings import DesignRatingIn
from app.services import design_ratings
from app.services.design_ratings import (
    RATEABLE_ENTITIES,
    RatingLedgerUnavailable,
    RatingRound,
    RatingRuleViolation,
    RatingSubjectGone,
    round_score,
)
from app.services.pagination import normalize_pagination, page_payload

router = APIRouter(prefix="/design-ratings", tags=["design-ratings"])

NOT_FOUND = "Record not found"


def _not_found() -> HTTPException:
    return HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=NOT_FOUND)


def _round_or_422(raw: str) -> RatingRound:
    try:
        return RatingRound(str(raw).upper())
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "A review round is PEER (the workshop's own peers) or POOL (the wider pool of "
                f"designers, once this piece has been declared finished). Received {raw!r}."
            ),
        ) from exc


def _rated_at_or_422(raw: str | None) -> datetime | None:
    if not raw:
        return None
    try:
        parsed = datetime.fromisoformat(str(raw))
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "The time on this rating couldn't be read. Update the app and rate the piece again."
            ),
        ) from exc
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)


def _entity_or_422(raw: str) -> str:
    if raw in RATEABLE_ENTITIES:
        return raw
    raise HTTPException(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        detail=(
            f"Only {', '.join(sorted(RATEABLE_ENTITIES))} can be ranked. A prototype's stage "
            f"logs and materials are part of the prototype and aren't ranked on their own."
        ),
    )


def _ledger_or_503(exc: RatingLedgerUnavailable) -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        detail="Ratings aren't available right now. Please try later.",
    )



@router.post("", status_code=status.HTTP_200_OK)
async def submit_rating(
    payload: DesignRatingIn, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    round_ = _round_or_422(payload.round)
    rated_at = _rated_at_or_422(payload.ratedAt)

    subject = await design_ratings.load_subject(payload.subjectId)
    if subject is None:
        raise _not_found()
    access = await design_ratings.resolve_access(subject, current_user, round_)
    if not access.visible:
        raise _not_found()
    if not access.may_rate:
        if access.is_author:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=(
                    "This is your own record. Peer review is other designers' judgement of your "
                    "work; your own view of it belongs in the record itself."
                ),
            )
        raise _not_found()

    try:
        row, outcome = await design_ratings.record_rating(
            subject=subject,
            round_=round_,
            reviewer_id=current_user.id,
            score=payload.score,
            comment=payload.comment,
            suggestion=payload.suggestion,
            at=datetime.now(UTC),
            rated_at=rated_at,
        )
    except RatingLedgerUnavailable as exc:
        raise _ledger_or_503(exc) from exc
    except RatingSubjectGone as exc:
        raise _not_found() from exc
    except RatingRuleViolation as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc

    return {
        "rating": design_ratings.rating_payload(row, viewer_id=current_user.id, access=access),
        "replayed": outcome.replayed,
    }



@router.get("/subjects/{subject_id}")
async def subject_ledger(
    subject_id: str,
    round: str = Query("PEER"),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    round_ = _round_or_422(round)
    subject = await design_ratings.load_subject(subject_id)
    if subject is None:
        raise _not_found()
    access = await design_ratings.resolve_access(subject, current_user, round_)
    if not access.visible:
        raise _not_found()

    try:
        rows = await design_ratings.subject_ratings(subject, round_)
    except RatingLedgerUnavailable as exc:
        raise _ledger_or_503(exc) from exc

    ranked = design_ratings.rank([subject], rows)[0]
    shown = design_ratings.visible_rows(rows, viewer_id=current_user.id, access=access)
    subject_payload: dict[str, Any] = {
        "id": subject.entry_id,
        "entityKey": subject.entity_key,
        "label": subject.label,
        "workshopId": subject.workshop_id,
    }
    if access.is_member or access.is_admin:
        subject_payload["ordinal"] = subject.ordinal
    return {
        "subject": subject_payload,
        "round": round_.value,
        "summary": {
            "score": round_score(ranked.score),
            "ratingCount": ranked.count,
        },
        "ratings": [
            design_ratings.rating_payload(row, viewer_id=current_user.id, access=access)
            for row in shown
        ],
        "canReadLedger": access.may_read_ledger,
        "namesShown": access.sees_rater_identity,
    }



@router.get("/workshops")
async def pool_workshops(
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
    search: str | None = Query(None, max_length=120),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    if not can_run_design_workshops(current_user):
        raise _not_found()
    listed = await design_ratings.pool_directory(search=search)
    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    window = listed[skip : skip + clean_size]
    return page_payload(
        [design_ratings.pool_workshop_payload(item) for item in window],
        len(listed),
        clean_page,
        clean_size,
    )



@router.get("/rounds/{round}")
async def round_ranking(
    round: str,
    workshopId: str = Query(..., min_length=1, max_length=64),
    entityKey: str = Query("prototype"),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    round_ = _round_or_422(round)
    entity_key = _entity_or_422(entityKey)
    workshop, is_member = await design_ratings.load_ratable_workshop_or_404(
        workshopId, current_user, round_
    )
    subjects = await design_ratings.load_subjects(workshopId, entity_key, workshop)
    if round_ is RatingRound.POOL:
        visible = design_ratings.pool_visible(
            subjects, is_member=is_member, admin=is_admin(current_user)
        )
        if not visible:
            raise _not_found()
        subjects = visible

    try:
        rows = await design_ratings.workshop_ratings(workshopId, entity_key, round_)
    except RatingLedgerUnavailable as exc:
        raise _ledger_or_503(exc) from exc

    mine_by_subject: dict[str, Any] = {}
    for row in rows:
        if getattr(row, "reviewerId", None) == current_user.id:
            mine_by_subject[getattr(row, "stageEntryId", "")] = row

    payload = []
    for ranked in design_ratings.rank(subjects, rows):
        access = design_ratings.access_for(
            ranked.subject,
            current_user,
            round_,
            is_member=is_member,
        )
        mine = mine_by_subject.get(ranked.subject.entry_id)
        payload.append(
            design_ratings.ranked_payload(
                ranked,
                show_ordinal=is_member or is_admin(current_user),
                mine=(
                    design_ratings.rating_payload(mine, viewer_id=current_user.id, access=access)
                    if mine is not None
                    else None
                ),
            )
        )

    return {
        "workshopId": workshopId,
        "entityKey": entity_key,
        "round": round_.value,
        "items": payload,
    }
