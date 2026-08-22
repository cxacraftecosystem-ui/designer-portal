"""The rating ledger on the wire: submit or amend one, read one subject's, rank a round's.

Three routes. The rules they enforce — who is in a round, who may read the ledger, whose name is on
it, and why the pool round does not go through ``load_workshop_or_404`` — all live in
``app/services/design_ratings.py``; this module is only the wire, and every refusal below defers to
a predicate there rather than restating one.

**WHY ITS OWN ROUTER AND NOT THREE MORE ROUTES IN ``design_workshops.py``.** That file is one
designer's workflow over one workshop they can already open, and every route in it begins with
``load_workshop_or_404``. The pool round is defined as the designers that helper turns away, so
routes that share its prefix would invite exactly the widening the feature was designed to avoid —
somebody teaching the shared loader about POOL, and handing every designer in the country stage
WRITES on every finished workshop along with it. A separate prefix keeps the narrow door visibly
narrow.

**AND ITS OWN PREFIX RATHER THAN A NESTED ONE.** ``/design-workshops`` is already shared by two
routers and carries ``GET /design-workshops/{workshop_id}``, which matches any literal path mounted
after it and answers 404 "Record not found" — the trap ``design_workshop_viewers`` documents and a
test pins. Nothing here needs to be nested to be found: a subject id identifies its workshop.

**404, NEVER 403, EVERYWHERE IN THIS FILE.** A subject or a workshop the caller may not reach
answers "Record not found" with the same detail string a genuinely missing id gets, exactly as
``services/records.require_record`` does. The data set is keyed by cuid; a 403 would confirm which
cuids exist and turn any designer login into an enumeration of the ministry's records.
"""

from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.deps import get_current_user, is_admin
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

router = APIRouter(prefix="/design-ratings", tags=["design-ratings"])

#: The one sentence every refusal in this file uses. Written once, because a refusal that varies
#: its wording by reason is a side channel: "Record not found" for a missing id and "Not found for
#: this workshop" for one the caller may not have tells an attacker which is which.
NOT_FOUND = "Record not found"


def _not_found() -> HTTPException:
    return HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=NOT_FOUND)


def _round_or_422(raw: str) -> RatingRound:
    """The round token, or a refusal that names the two that exist.

    422 and not 404, deliberately: an unknown round is a statement about the REQUEST, not about a
    record the caller may or may not be allowed to see, so nothing is disclosed by saying so.
    """
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
    """The device's clock as sent, or a refusal.

    **REFUSED RATHER THAN DROPPED, which is the opposite of the lenient parse used beside the
    dictation consent route.** That one is safe there because its request schema has already
    rejected an unparseable value with a sentence, so the fallback is unreachable. Here nothing has
    validated it yet, and silently answering ``None`` would store a rating with no courtyard moment
    at all — the sync date would then be the only date on the row, which is precisely the
    fabrication the two-clock split exists to prevent, arrived at by dropping a field instead of by
    rewriting one.
    """
    if not raw:
        return None
    try:
        parsed = datetime.fromisoformat(str(raw))
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "ratedAt must be an ISO-8601 date and time — when the designer actually judged the "
                f"piece, as this device recorded it. Received {raw!r}."
            ),
        ) from exc
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)


def _entity_or_422(raw: str) -> str:
    if raw in RATEABLE_ENTITIES:
        return raw
    raise HTTPException(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        detail=(
            f"Only {', '.join(sorted(RATEABLE_ENTITIES))} are ranked. The child rows of a "
            f"prototype — its stage logs and its materials — are parts of one, not things a "
            f"designer ranks against each other."
        ),
    )


def _ledger_or_503(exc: RatingLedgerUnavailable) -> HTTPException:
    """A deployment whose database has not had the ratings migration applied.

    503 rather than 500, and with the sentence the operator needs: this is a deployment state that
    a restart does not fix and a migration does, and an opaque 500 sends whoever is on call reading
    tracebacks for a schema that is simply not there yet.
    """
    return HTTPException(
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(exc)
    )


# --------------------------------------------------------------------------------------
# Writing
# --------------------------------------------------------------------------------------


@router.post("", status_code=status.HTTP_200_OK)
async def submit_rating(
    payload: DesignRatingIn, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Submit a rating, or amend the one this caller already left.

    **IDEMPOTENT UNDER REPLAY, AND THAT IS THE WHOLE REASON THIS IS ONE ROUTE AND NOT TWO.** A
    rating is captured in a courtyard and reaches this server whenever the phone next finds signal,
    so the outbox can and does deliver the same capture twice. It cannot produce a second row —
    ``@@unique([stageEntryId, reviewerId, round])`` makes one unrepresentable — and a delivery whose
    ``ratedAt`` is not newer than the stored row's writes nothing at all and answers with the stored
    row and ``replayed: true``. That is a SUCCESS: the device did the right thing and the server has
    the rating. See ``design_ratings.rating_plan`` for why arrival order cannot decide this, for the
    tunnel that restores a stale score when it is left to, and for exactly what the device clock
    buys in place of the ``clientKey`` the landed model does not carry.

    200 AND NOT 201, on the create path too. The client cannot know which of create, amend and
    replay it is asking for — that is the point of the route — so a status code that varied between
    them would be a fact about the server's state dressed as a fact about the request, and every
    client would have to treat 200 and 201 identically anyway.

    **A DESIGNER MAY NOT RATE THEIR OWN WORK** by default; see
    ``design_ratings.SELF_RATING_IS_REFUSED``, which is a named owner call and not an inlined
    ``if``. That refusal is a 403 rather than the 404 the rest of this file uses, and the exception
    is deliberate: the caller demonstrably knows this record exists — it is theirs — so there is
    nothing left to disclose, and a 404 would send a designer looking for a sketch that is on their
    own screen.

    IT READS ``access.is_author`` AND NOT ``access.is_own_record``. The latter also counts the
    workshop's creator, which is right for reading a ledger and wrong here — only an admin can
    create a workshop, so the wider spelling told the admin who started one that a prototype
    somebody else drew was "your own record", on every piece in it.
    """
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
        # Caught BEFORE its parent, and answered as the 404 this file answers everything else with:
        # the request was well formed and the record simply stopped existing between the read and
        # the write. A 422 would tell a designer to correct a body that was never wrong.
        raise _not_found() from exc
    except RatingRuleViolation as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc

    return {
        "rating": design_ratings.rating_payload(
            row, viewer_id=current_user.id, access=access
        ),
        "replayed": outcome.replayed,
    }


# --------------------------------------------------------------------------------------
# Reading one subject's ledger
# --------------------------------------------------------------------------------------


@router.get("/subjects/{subject_id}")
async def subject_ledger(
    subject_id: str,
    round: str = Query("PEER"),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Who rated this sketch or prototype, when, and how.

    **THE OWNER'S SENTENCE IS ENFORCED HERE AND NOT IN THE CLIENT.** Admins and master admins get
    every row with a name on it. The designer whose record it is gets every row, named in PEER and
    — by the current setting of ``design_ratings.POOL_RATINGS_NAME_THEIR_RATER`` — unnamed in POOL.
    Everybody else in the round gets the aggregate and their OWN row, and no other row is in the
    response at all: it is filtered out server side rather than hidden by a client, because a
    hidden column is not a control.

    ``summary`` is here rather than only on the ranking route so that a review card can show "4.2
    from 5 designers" without loading its whole round. It is the same aggregate
    ``design_ratings.rank`` computes, over the same rows.

    A caller who may see the subject but has left no rating and may not read the ledger gets an
    empty ``ratings`` list and a populated ``summary``. That is not a refusal and must not be
    rendered as one — it is what "you can see the score, not the scorers" looks like.
    """
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
        # THE RAW ORDINAL GOES ONLY TO THE PEOPLE WHO ALREADY SEE THE WHOLE COLLECTION, for the
        # reason ``design_ratings.ranked_payload`` states: a pool reader handed one opened
        # prototype at ordinal 7 has learned the workshop holds at least eight pieces they may not
        # open. Nothing on this response needs it — it is here for a workshop's own review tab,
        # which wants to show a piece's place in its own stage list.
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
        # SAID OUT LOUD, so a client can explain an empty list instead of implying nobody rated.
        # These two booleans are the difference between "no ratings yet" and "not yours to see",
        # and without them every review card would have to guess which it was showing.
        "canReadLedger": access.may_read_ledger,
        "namesShown": access.sees_rater_identity,
    }


# --------------------------------------------------------------------------------------
# The ranked list for one round
# --------------------------------------------------------------------------------------


@router.get("/rounds/{round}")
async def round_ranking(
    round: str,
    workshopId: str = Query(..., min_length=1, max_length=64),
    entityKey: str = Query("prototype"),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """One round's subjects, each with its score, its DEFAULT position and its PLACED position.

    Returned in PLACED order — the designer's own arrangement, because that is what the page
    renders — with ``defaultPosition`` on every row so a client can offer "sort by score" without
    recomputing an average. The owner asked for both: *"sorted by score by default, with the
    designer having the final say"*, which is only expressible if the two orders travel together.

    **``workshopId`` IS REQUIRED FOR BOTH ROUNDS, INCLUDING POOL, AND THAT IS A STRUCTURAL
    CONSTRAINT RATHER THAN A SIMPLIFICATION.** The placed order is ``DwStageEntry.ordinal``, which
    orders the rows of ONE collection inside ONE workshop — it is what the drag handles and the
    up/down arrows on both clients already write, and this feature deliberately reuses it rather
    than inventing a second ranking mechanism. There is therefore no such thing as a placed
    position across workshops: two prototypes in two workshops can both be ordinal 0, and no
    arrangement a designer makes on a mixed list has anywhere to be stored. So the pool round is
    the same list read by a wider audience, not a wider list. A cross-workshop *browse* is a
    different feature and needs a different answer.

    The workshop is opened through ``design_ratings.load_ratable_workshop_or_404``, NOT through
    ``load_workshop_or_404`` — see that function for why, and for what it deliberately does not
    grant. A pool reader gets these rows and nothing else about the workshop.
    """
    round_ = _round_or_422(round)
    entity_key = _entity_or_422(entityKey)
    workshop, is_member = await design_ratings.load_ratable_workshop_or_404(
        workshopId, current_user, round_
    )
    subjects = await design_ratings.load_subjects(workshopId, entity_key, workshop)
    if round_ is RatingRound.POOL:
        # THE POOL GATE IS PER PIECE, so it narrows the LIST rather than the door — see
        # ``design_ratings.pool_visible``. A stranger left with nothing gets the same 404 a missing
        # workshop gets: without that, this route answers 200 for every workshop id that exists and
        # 404 for every one that does not, which is an enumeration oracle over the whole archive.
        visible = design_ratings.pool_visible(
            subjects, is_member=is_member, admin=is_admin(current_user)
        )
        if not visible:
            raise _not_found()
        # THE NARROWING HAPPENS BEFORE THE RANKING, so the positions a stranger is given are
        # positions within what they can see. Ranking first and filtering after would hand them
        # "placed 3 of 3" for the one piece they are allowed to know about, which both reads as a
        # bug on screen and quietly discloses how many prototypes the workshop holds.
        subjects = visible

    try:
        rows = await design_ratings.workshop_ratings(workshopId, entity_key, round_)
    except RatingLedgerUnavailable as exc:
        raise _ledger_or_503(exc) from exc

    # This caller's own rating of each subject, so the review control renders filled in without a
    # request per row. Their own rows only — never anybody else's, which is the ledger route's
    # question and carries the identity rule this one does not.
    mine_by_subject: dict[str, Any] = {}
    for row in rows:
        if getattr(row, "reviewerId", None) == current_user.id:
            mine_by_subject[getattr(row, "stageEntryId", "")] = row

    # A per-subject access decision rather than one for the workshop: authorship is a property of
    # the stage row (``DwStageEntry.createdById``), so in a workshop run by two designers one of
    # them owns some of these sketches and not others, and a single verdict for the page would
    # either over- or under-disclose on every row it got wrong.
    payload = []
    for ranked in design_ratings.rank(subjects, rows):
        access = design_ratings.access_for(
            ranked.subject,
            current_user,
            round_,
            # Resolved ONCE by ``load_ratable_workshop_or_404`` and carried in, rather than
            # re-derived per row. Deriving it here from ``workshop.createdById`` alone — the
            # obvious shortcut — would silently demote every viewer-granted co-designer to a
            # stranger for the whole page.
            is_member=is_member,
        )
        mine = mine_by_subject.get(ranked.subject.entry_id)
        payload.append(
            design_ratings.ranked_payload(
                ranked,
                # The raw ``ordinal`` is a disclosure, not a display field — see
                # ``design_ratings.ranked_payload``. It travels to the workshop's own party and to
                # admins; everybody else ranks on ``placedPosition``, which counts only the rows
                # they were given.
                show_ordinal=is_member or is_admin(current_user),
                mine=(
                    design_ratings.rating_payload(
                        mine, viewer_id=current_user.id, access=access
                    )
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
