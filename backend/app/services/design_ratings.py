from __future__ import annotations

import logging
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from enum import Enum
from typing import Any

from prisma.errors import UniqueViolationError

from app.core.db import db
from app.core.deps import can_run_design_workshops, is_admin
from app.services.design_workshop_viewers import has_viewer_grant

logger = logging.getLogger(__name__)



class RatingRound(str, Enum):
    PEER = "PEER"
    POOL = "POOL"


RATEABLE_ENTITIES: frozenset[str] = frozenset({"sketch", "prototype"})

MIN_SCORE = 1
MAX_SCORE = 5

MAX_DEVICE_CLOCK_SKEW = timedelta(minutes=15)

LEDGER_CLOCK_RESOLUTION = timedelta(milliseconds=1)

RATING_TABLE = "DwReviewRating"

WRITABLE_TABLES: frozenset[str] = frozenset({RATING_TABLE})

STAGE_TABLE = "DwStageEntry"

RATING_DELEGATE = "dwreviewrating"


POOL_RATINGS_NAME_THEIR_RATER = False

POOL_OPENS_WHEN_FIELD = "peerRoundClosedAt"

SELF_RATING_IS_REFUSED = True


class RatingRuleViolation(ValueError):
    pass


class RatingSubjectGone(RatingRuleViolation):
    pass


class RatingLedgerUnavailable(RuntimeError):
    pass


def _ledger() -> Any:
    delegate = getattr(db, RATING_DELEGATE, None)
    if delegate is None:
        raise RatingLedgerUnavailable(
            "Ratings aren't available right now. Please try later."
        )
    return delegate



@dataclass(frozen=True, slots=True)
class RatingSubject:
    entry_id: str
    entity_key: str
    workshop_id: str
    pool_open: bool
    label: str
    ordinal: int
    author_id: str | None
    workshop_author_id: str | None


def _entry_label(entity_key: str, data: Any) -> str:
    if not isinstance(data, Mapping):
        return ""
    for key in ("name", "sketchNo", "prototypeCode"):
        value = data.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def pool_is_open(data: Any) -> bool:
    if not isinstance(data, Mapping):
        return False
    value = data.get(POOL_OPENS_WHEN_FIELD)
    if isinstance(value, str):
        return bool(value.strip())
    return value is not None



async def load_subject(entry_id: str) -> RatingSubject | None:
    if not entry_id:
        return None
    entry = await db.dwstageentry.find_unique(where={"id": entry_id})
    if entry is None or getattr(entry, "deletedAt", None) is not None:
        return None
    if entry.entityKey not in RATEABLE_ENTITIES:
        return None
    workshop = await db.designworkshop.find_unique(where={"id": entry.designWorkshopId})
    if workshop is None or getattr(workshop, "deletedAt", None) is not None:
        return None
    return RatingSubject(
        entry_id=entry.id,
        entity_key=entry.entityKey,
        workshop_id=entry.designWorkshopId,
        pool_open=pool_is_open(getattr(entry, "data", None)),
        label=_entry_label(entry.entityKey, getattr(entry, "data", None)),
        ordinal=int(getattr(entry, "ordinal", 0) or 0),
        author_id=getattr(entry, "createdById", None),
        workshop_author_id=getattr(workshop, "createdById", None),
    )


async def load_subjects(workshop_id: str, entity_key: str, workshop: Any) -> list[RatingSubject]:
    rows = await db.dwstageentry.find_many(
        where={
            "designWorkshopId": workshop_id,
            "entityKey": entity_key,
            "deletedAt": None,
        },
        order={"ordinal": "asc"},
    )
    return [
        RatingSubject(
            entry_id=row.id,
            entity_key=row.entityKey,
            workshop_id=workshop_id,
            pool_open=pool_is_open(getattr(row, "data", None)),
            label=_entry_label(row.entityKey, getattr(row, "data", None)),
            ordinal=int(getattr(row, "ordinal", 0) or 0),
            author_id=getattr(row, "createdById", None),
            workshop_author_id=getattr(workshop, "createdById", None),
        )
        for row in rows
    ]


def pool_visible(
    subjects: Sequence[RatingSubject], *, is_member: bool, admin: bool
) -> list[RatingSubject]:
    if is_member or admin:
        return list(subjects)
    return [item for item in subjects if item.pool_open]



@dataclass(frozen=True, slots=True)
class PoolWorkshop:
    workshop_id: str
    title: str
    start_date: datetime | None
    end_date: datetime | None
    open_counts: dict[str, int]


async def pool_directory(*, search: str | None = None) -> list[PoolWorkshop]:
    rows = await db.dwstageentry.find_many(
        where={"entityKey": {"in": sorted(RATEABLE_ENTITIES)}, "deletedAt": None},
    )
    counts: dict[str, dict[str, int]] = {}
    for row in rows:
        if not pool_is_open(getattr(row, "data", None)):
            continue
        per_kind = counts.setdefault(row.designWorkshopId, dict.fromkeys(RATEABLE_ENTITIES, 0))
        per_kind[row.entityKey] = per_kind.get(row.entityKey, 0) + 1
    if not counts:
        return []
    where: dict[str, Any] = {"id": {"in": sorted(counts)}, "deletedAt": None}
    term = (search or "").strip()
    if term:
        from app.services.records import contains

        where["title"] = contains(term)
    workshops = await db.designworkshop.find_many(where=where)
    listed = [
        PoolWorkshop(
            workshop_id=workshop.id,
            title=(workshop.title or "").strip(),
            start_date=getattr(workshop, "startDate", None),
            end_date=getattr(workshop, "endDate", None),
            open_counts=dict(sorted(counts[workshop.id].items())),
        )
        for workshop in workshops
    ]
    listed.sort(key=lambda item: item.title.lower())
    listed.sort(
        key=lambda item: item.start_date.timestamp() if item.start_date else float("-inf"),
        reverse=True,
    )
    return listed


def pool_workshop_payload(item: PoolWorkshop) -> dict[str, Any]:
    return {
        "workshopId": item.workshop_id,
        "title": item.title,
        "startDate": _iso(item.start_date),
        "endDate": _iso(item.end_date),
        "openCounts": item.open_counts,
    }



@dataclass(frozen=True, slots=True)
class RatingAccess:
    round: RatingRound
    in_round: bool
    may_rate: bool
    may_read_ledger: bool
    sees_rater_identity: bool
    is_own_record: bool
    is_author: bool
    is_admin: bool
    is_member: bool

    @property
    def visible(self) -> bool:
        return self.in_round or self.may_read_ledger


def is_row_author(subject: RatingSubject, user: Any) -> bool:
    user_id = getattr(user, "id", None)
    return bool(user_id) and bool(subject.author_id) and user_id == subject.author_id


def is_own_record(subject: RatingSubject, user: Any) -> bool:
    user_id = getattr(user, "id", None)
    if not user_id:
        return False
    return user_id in {
        identifier for identifier in (subject.author_id, subject.workshop_author_id) if identifier
    }


def access_for(
    subject: RatingSubject,
    user: Any,
    round_: RatingRound,
    *,
    is_member: bool,
) -> RatingAccess:
    admin = is_admin(user)
    own = is_own_record(subject, user)
    author = is_row_author(subject, user)

    if not can_run_design_workshops(user):
        return RatingAccess(
            round=round_,
            in_round=False,
            may_rate=False,
            may_read_ledger=False,
            sees_rater_identity=False,
            is_own_record=False,
            is_author=False,
            is_admin=admin,
            is_member=is_member,
        )

    if round_ is RatingRound.PEER:
        in_round = is_member or admin
    else:
        in_round = is_member or admin or subject.pool_open

    may_rate = in_round and not (author and SELF_RATING_IS_REFUSED)
    may_read_ledger = admin or own

    if admin:
        sees_identity = True
    elif not may_read_ledger:
        sees_identity = False
    elif round_ is RatingRound.PEER:
        sees_identity = True
    else:
        sees_identity = POOL_RATINGS_NAME_THEIR_RATER

    return RatingAccess(
        round=round_,
        in_round=in_round,
        may_rate=may_rate,
        may_read_ledger=may_read_ledger,
        sees_rater_identity=sees_identity,
        is_own_record=own,
        is_author=author,
        is_admin=admin,
        is_member=is_member,
    )


async def is_workshop_member(workshop_id: str, creator_id: str | None, user: Any) -> bool:
    user_id = getattr(user, "id", "")
    if not user_id:
        return False
    if creator_id == user_id:
        return True
    if is_admin(user):
        return False
    return await has_viewer_grant(workshop_id, user_id)


async def resolve_access(subject: RatingSubject, user: Any, round_: RatingRound) -> RatingAccess:
    member = await is_workshop_member(subject.workshop_id, subject.workshop_author_id, user)
    return access_for(subject, user, round_, is_member=member)


async def load_ratable_workshop_or_404(
    workshop_id: str, user: Any, round_: RatingRound
) -> tuple[Any, bool]:
    from fastapi import HTTPException, status

    not_found = HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    if not workshop_id:
        raise not_found
    workshop = await db.designworkshop.find_unique(where={"id": workshop_id})
    if workshop is None or getattr(workshop, "deletedAt", None) is not None:
        raise not_found
    if not can_run_design_workshops(user):
        raise not_found

    member = await is_workshop_member(workshop_id, getattr(workshop, "createdById", None), user)
    if is_admin(user) or member:
        return workshop, member
    if round_ is RatingRound.POOL:
        return workshop, member
    raise not_found



def rating_payload(
    row: Any,
    *,
    viewer_id: str,
    access: RatingAccess,
) -> dict[str, Any]:
    mine = bool(viewer_id) and getattr(row, "reviewerId", None) == viewer_id
    payload: dict[str, Any] = {
        "id": row.id,
        "subjectId": getattr(row, "stageEntryId", None),
        "round": str(getattr(getattr(row, "round", None), "value", getattr(row, "round", None))),
        "score": getattr(row, "score", None),
        "comment": getattr(row, "comment", None),
        "suggestion": getattr(row, "suggestion", None),
        "ratedAt": _iso(getattr(row, "ratedAt", None)),
        "createdAt": _iso(getattr(row, "createdAt", None)),
        "updatedAt": _iso(getattr(row, "updatedAt", None)),
        "mine": mine,
    }
    if mine or access.sees_rater_identity:
        payload["reviewerId"] = getattr(row, "reviewerId", None)
    return payload


def _iso(value: Any) -> str | None:
    return value.isoformat() if isinstance(value, datetime) else None


def visible_rows(rows: Sequence[Any], *, viewer_id: str, access: RatingAccess) -> list[Any]:
    if access.may_read_ledger:
        return list(rows)
    if not viewer_id:
        return []
    return [row for row in rows if getattr(row, "reviewerId", None) == viewer_id]


async def subject_ratings(subject: RatingSubject, round_: RatingRound) -> list[Any]:
    return await _ledger().find_many(
        where={"stageEntryId": subject.entry_id, "round": round_.value},
        order={"createdAt": "asc"},
    )


async def workshop_ratings(workshop_id: str, entity_key: str, round_: RatingRound) -> list[Any]:
    return await _ledger().find_many(
        where={
            "designWorkshopId": workshop_id,
            "entityKey": entity_key,
            "round": round_.value,
        },
        order={"createdAt": "asc"},
    )



@dataclass(frozen=True, slots=True)
class RankedSubject:
    subject: RatingSubject
    score: float | None
    count: int
    default_position: int
    placed_position: int


def rank(subjects: Sequence[RatingSubject], rows: Sequence[Any]) -> list[RankedSubject]:
    totals: dict[str, list[int]] = {}
    for row in rows:
        entry_id = getattr(row, "stageEntryId", None)
        score = getattr(row, "score", None)
        if not entry_id or not isinstance(score, int):
            continue
        totals.setdefault(entry_id, []).append(score)

    placed = sorted(subjects, key=lambda s: (s.ordinal, s.entry_id))
    placed_at = {subject.entry_id: index + 1 for index, subject in enumerate(placed)}

    scored: dict[str, tuple[float | None, int]] = {}
    for subject in subjects:
        got = totals.get(subject.entry_id, [])
        scored[subject.entry_id] = (
            (sum(got) / len(got)) if got else None,
            len(got),
        )

    def default_key(subject: RatingSubject) -> tuple[int, float, int, int, str]:
        score, count = scored[subject.entry_id]
        return (
            1 if score is None else 0,
            -(score or 0.0),
            -count,
            placed_at[subject.entry_id],
            subject.entry_id,
        )

    by_score = sorted(subjects, key=default_key)
    default_at = {subject.entry_id: index + 1 for index, subject in enumerate(by_score)}

    return [
        RankedSubject(
            subject=subject,
            score=scored[subject.entry_id][0],
            count=scored[subject.entry_id][1],
            default_position=default_at[subject.entry_id],
            placed_position=placed_at[subject.entry_id],
        )
        for subject in placed
    ]


SCORE_DECIMALS = 3


def round_score(value: float | None) -> float | None:
    return round(value, SCORE_DECIMALS) if value is not None else None


def ranked_payload(
    ranked: RankedSubject, *, mine: Any | None, show_ordinal: bool
) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "subjectId": ranked.subject.entry_id,
        "entityKey": ranked.subject.entity_key,
        "label": ranked.subject.label,
        "workshopId": ranked.subject.workshop_id,
        "score": round_score(ranked.score),
        "ratingCount": ranked.count,
        "defaultPosition": ranked.default_position,
        "placedPosition": ranked.placed_position,
        "myRating": mine,
    }
    if show_ordinal:
        payload["ordinal"] = ranked.subject.ordinal
    return payload



class Operation(str, Enum):
    CREATE = "CREATE"
    UPDATE = "UPDATE"


@dataclass(frozen=True, slots=True)
class RatingWritePlan:
    table: str
    operation: Operation
    data: Mapping[str, Any]
    where: Mapping[str, Any] | None = None

    def __post_init__(self) -> None:
        if self.table not in WRITABLE_TABLES:
            raise RatingRuleViolation(
                f"A design rating may not be written into {self.table}. It is a row in "
                f"{RATING_TABLE} and nowhere else: a stage entry's data is a JSON "
                f"blob, so one-rating-per-reviewer cannot be indexed inside it, and save_stage "
                f"writes only data, ordinal and deletedAt — it cannot record who rated or when."
            )
        if self.operation is Operation.UPDATE and not self.where:
            raise RatingRuleViolation(
                "An update must name the single row it changes. Pass where={'id': rating_id}."
            )
        if self.operation is Operation.CREATE and self.where:
            raise RatingRuleViolation("A create names no existing row. Drop the where clause.")


@dataclass(frozen=True, slots=True)
class RatingOutcome:
    plan: RatingWritePlan | None
    replayed: bool
    existing_id: str | None = None


def rating_plan(
    *,
    subject: RatingSubject,
    round_: RatingRound,
    reviewer_id: str,
    score: int,
    comment: str | None,
    suggestion: str | None,
    at: datetime,
    rated_at: datetime | None,
    existing: Any | None,
) -> RatingOutcome:
    if not reviewer_id:
        raise RatingRuleViolation("A rating must name the account that made it.")
    if not isinstance(score, int) or isinstance(score, bool):
        raise RatingRuleViolation(f"A score is a whole number from {MIN_SCORE} to {MAX_SCORE}.")
    if not MIN_SCORE <= score <= MAX_SCORE:
        raise RatingRuleViolation(
            f"A score of {score} is outside the {MIN_SCORE}–{MAX_SCORE} scale."
        )
    moment = _aware(rated_at)
    if moment is not None and moment - at > MAX_DEVICE_CLOCK_SKEW:
        raise RatingRuleViolation(
            "This rating says it was made in the future, so the device's clock is wrong. "
            "Correct the date and time on the device and sync again — the moment is not "
            "rewritten here, because a judgement dated to the sync is not the moment anybody "
            "made it."
        )

    if existing is not None and _is_stale_delivery(existing, moment):
        return RatingOutcome(plan=None, replayed=True, existing_id=getattr(existing, "id", None))

    body: dict[str, Any] = {
        "score": score,
        "comment": comment or None,
        "suggestion": suggestion or None,
    }
    if rated_at is not None or existing is None:
        body["ratedAt"] = rated_at
    if existing is None:
        body.update(
            {
                "designWorkshopId": subject.workshop_id,
                "stageEntryId": subject.entry_id,
                "entityKey": subject.entity_key,
                "round": round_.value,
                "reviewerId": reviewer_id,
            }
        )
        return RatingOutcome(
            plan=RatingWritePlan(
                table=RATING_TABLE,
                operation=Operation.CREATE,
                data=body,
            ),
            replayed=False,
        )
    return RatingOutcome(
        plan=RatingWritePlan(
            table=RATING_TABLE,
            operation=Operation.UPDATE,
            data=body,
            where={"id": existing.id},
        ),
        replayed=False,
        existing_id=existing.id,
    )


def _aware(value: datetime | None) -> datetime | None:
    if value is None:
        return None
    return value if value.tzinfo else value.replace(tzinfo=UTC)


def _as_stored(value: datetime | None) -> datetime | None:
    if value is None:
        return None
    step = LEDGER_CLOCK_RESOLUTION // timedelta(microseconds=1)
    return value - timedelta(microseconds=value.microsecond % step)


def _is_stale_delivery(existing: Any, incoming: datetime | None) -> bool:
    stored = _as_stored(_aware(getattr(existing, "ratedAt", None)))
    incoming = _as_stored(incoming)
    if incoming is None or stored is None:
        return False
    return incoming <= stored


async def existing_rating(
    subject: RatingSubject, round_: RatingRound, reviewer_id: str
) -> Any | None:
    if not reviewer_id:
        return None
    return await _ledger().find_first(
        where={
            "stageEntryId": subject.entry_id,
            "round": round_.value,
            "reviewerId": reviewer_id,
        }
    )


async def record_rating(
    *,
    subject: RatingSubject,
    round_: RatingRound,
    reviewer_id: str,
    score: int,
    comment: str | None,
    suggestion: str | None,
    at: datetime,
    rated_at: datetime | None,
) -> tuple[Any, RatingOutcome]:
    existing = await existing_rating(subject, round_, reviewer_id)
    outcome = rating_plan(
        subject=subject,
        round_=round_,
        reviewer_id=reviewer_id,
        score=score,
        comment=comment,
        suggestion=suggestion,
        at=at,
        rated_at=rated_at,
        existing=existing,
    )
    try:
        return await apply_rating(outcome), outcome
    except UniqueViolationError:
        logger.info(
            "design_ratings: two deliveries of %s's rating of %s raced; re-reading and amending",
            reviewer_id,
            subject.entry_id,
        )
        existing = await existing_rating(subject, round_, reviewer_id)
        if existing is None:
            raise
    outcome = rating_plan(
        subject=subject,
        round_=round_,
        reviewer_id=reviewer_id,
        score=score,
        comment=comment,
        suggestion=suggestion,
        at=at,
        rated_at=rated_at,
        existing=existing,
    )
    return await apply_rating(outcome), outcome


async def apply_rating(outcome: RatingOutcome) -> Any:
    if outcome.plan is None:
        if not outcome.existing_id:
            raise RatingRuleViolation("Nothing to write and no stored rating to answer with.")
        stored = await _ledger().find_unique(where={"id": outcome.existing_id})
        if stored is None:
            raise RatingSubjectGone(
                "The rating this delivery repeats has been removed. Nothing was written."
            )
        return stored
    plan = outcome.plan
    if plan.operation is Operation.CREATE:
        return await _ledger().create(data=dict(plan.data))
    amended = await _ledger().update(where=dict(plan.where or {}), data=dict(plan.data))
    if amended is None:
        raise RatingSubjectGone(
            "The rating this amendment changes has been removed. Nothing was written."
        )
    return amended
