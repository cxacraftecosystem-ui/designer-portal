import logging
from typing import Any

from fastapi import APIRouter, Depends, Query, Response
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.deps import get_current_user, require_master_admin
from app.schemas.feedback import FeedbackUpsertRequest

router = APIRouter(prefix="/feedback", tags=["feedback"])

logger = logging.getLogger(__name__)

#: How many feedback rows one request to ``GET /feedback`` will read, and the largest ``pageSize``
#: it will honour.
#:
#: **THE READ USED TO HAVE NO BOUND AT ALL** — ``find_many(order=…, include={"user": True})`` with
#: no ``take``, no ``skip`` and no query parameters on the signature — so the response grew with the
#: table and its first failure signal would have been a timeout. The table is bounded by the account
#: count (``PUT /feedback/me`` upserts on ``userId``, so it holds at most one row per account). I
#: measured the local database on 2026-08-15: ``feedback`` holds 0 rows against 5064 ``User`` rows —
#: so the ceiling is nowhere near being reached and this is a latent defect, not a live one, but the
#: table it reads is bounded only by how many people the project signs up. Bounded here for the reason
#: ``ACTIVE_ROSTER_READ_LIMIT`` states in ``services/design_workshop_viewers``: a read whose only
#: ceiling is how much data the project has collected is a read that fails on the day it matters.
#:
#: 200 rather than the shared ``MAX_PAGE_SIZE`` of 100, and the difference is deliberate. This route
#: still answers a BARE JSON ARRAY (see :func:`list_feedback`), so the whole master-admin page is one
#: request with no pager; halving what a single request can carry would silently halve that screen.
#: 200 is above every plausible near-term response count and it bounds the worst case: ``comment``,
#: ``likeMost``, ``improve``, ``bugs`` and ``featureRequests`` are each ``max_length=5000`` in
#: ``schemas/feedback.py`` and the rest are a 200-char ``role`` and seven small ints, so a row is
#: ~25 KB at absolute worst and 200 of them is ~5 MB — large, but a bound, and reachable only if two
#: hundred people each write five full essays.
FEEDBACK_TAKE = 200


# Every persisted feedback field that is echoed straight back to the client (quantitative ints +
# qualitative strings). Kept as one list so the serializer and the upsert stay in lockstep.
FEEDBACK_FIELDS = (
    "rating",
    "easeOfUse",
    "reliability",
    "performance",
    "design",
    "features",
    "recommend",
    "comment",
    "likeMost",
    "improve",
    "bugs",
    "featureRequests",
    "role",
)


def _serialize(row: Any) -> dict[str, Any]:
    """Serialise a feedback row, attaching only the author's safe identity fields (never the
    password hash or other sensitive user columns) when the relation is loaded."""
    user = getattr(row, "user", None)
    data: dict[str, Any] = {
        "id": row.id,
        "userId": row.userId,
        "createdAt": jsonable_encoder(row.createdAt),
        "updatedAt": jsonable_encoder(row.updatedAt),
    }
    for field in FEEDBACK_FIELDS:
        data[field] = getattr(row, field, None)
    data["user"] = (
        None
        if user is None
        else {
            "id": user.id,
            "name": user.name,
            "email": user.email,
            "role": getattr(user.role, "value", user.role),
        }
    )
    return data


@router.get("/me")
async def my_feedback(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """The current user's own feedback, or an empty object if they haven't given any yet."""
    feedback = await db.feedback.find_unique(where={"userId": current_user.id})
    return _serialize(feedback) if feedback else {}


@router.put("/me")
async def upsert_my_feedback(
    payload: FeedbackUpsertRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Create or update the current user's feedback — they can revisit and change it any time."""
    fields = {field: getattr(payload, field) for field in FEEDBACK_FIELDS}
    feedback = await db.feedback.upsert(
        where={"userId": current_user.id},
        data={
            "create": {**fields, "user": {"connect": {"id": current_user.id}}},
            "update": fields,
        },
    )
    return _serialize(feedback)


@router.get("")
async def list_feedback(
    response: Response,
    _: Any = Depends(require_master_admin),
    page: int = Query(1, ge=1),
    pageSize: int = Query(FEEDBACK_TAKE, ge=1, le=FEEDBACK_TAKE),
) -> list[dict[str, Any]]:
    """Master-admin only: one page of user feedback, most recently updated first, with its author.

    **A BARE ARRAY, STILL, AND THAT IS A CONSTRAINT RATHER THAN A PREFERENCE.** Every sibling list
    route in this codebase answers ``page_payload`` — an object with ``items``/``total``/``pages`` —
    and this one should too. It does not because ``frontend/app/(protected)/feedback/page.tsx`` does
    ``apiFetch<Feedback[]>("/feedback").then(setAllFeedback)`` and then maps the result: swapping the
    body for an envelope replaces the master admin's feedback screen with a runtime error the moment
    it deploys, and that client is outside the lane this change was made in. So the bound and the
    paging land here now, the count and the shortfall ride in headers where they break nothing, and
    the envelope migration is written up as a follow-up to be done on both sides at once.

    ``X-Total-Count`` is how many rows the table actually holds and ``X-Truncated`` says whether
    anything was left behind this page. They exist because a capped array cannot say it was capped:
    a client comparing ``rows.length`` against its own copy of 200 is inferring the cut from a
    length, which is the precise inference the closed viewer-picker findings in
    ``docs/OPEN_FINDINGS.md`` were filed against — ``truncated`` is exact there for the same reason
    it is exact here. Nothing here tells anybody to "narrow the search" — this route has no search
    parameter, so there is nothing a reader could narrow, and advice that cannot be followed is the
    failure the closed viewer-picker finding of 2026-08-13 is on record for.

    ``page`` and ``pageSize`` mean the rows past the ceiling are REACHABLE rather than merely
    counted. A cap that hides rows with no way to ask for them would trade an unbounded read for a
    quieter kind of loss.

    **ONE LIMITATION OF THE HEADERS, STATED SO NOBODY DISCOVERS IT IN A BROWSER CONSOLE.**
    ``create_app`` builds ``CORSMiddleware`` with ``allow_headers=["*"]`` and NO ``expose_headers``
    (``app/main.py``), and ``allow_headers`` governs the REQUEST direction only. Same-origin
    JavaScript can read ``X-Total-Count``; a cross-origin deployment cannot until
    ``expose_headers=["X-Total-Count", "X-Truncated"]`` is added there. That is one line in a file
    outside this change's lane, and it is part of the envelope migration written up with it — the
    headers are the interim contract, not the destination. Which is precisely why the shortfall is
    ALSO written to the server log at ERROR: the one signal that works whatever the client can read.
    Same reasoning, same severity, as ``active_roster_emails`` in
    ``services/design_workshop_viewers``, whose comment says it in full — a cut nobody downstream
    can observe is a cut that has to be observable somewhere.
    """
    skip = (page - 1) * pageSize
    # The count is one extra query against a table holding at most one row per account, and it is
    # what makes ``X-Truncated`` exact instead of guessed. Do not "optimise" it away by testing
    # ``len(rows) == pageSize``: that reports a shortfall on the page that happens to end exactly on
    # the boundary, and a master admin told rows are missing when they are not has no way to check.
    total = await db.feedback.count()
    rows = await db.feedback.find_many(
        # ``id`` is the TIEBREAKER and it is load-bearing now that this read is paged.
        # ``updatedAt`` is not unique — an upsert-per-account table gets ties whenever two people
        # send feedback in the same instant, and a non-total order under LIMIT/OFFSET lets one row
        # appear on two pages while another appears on none.
        order=[{"updatedAt": "desc"}, {"id": "desc"}],
        include={"user": True},
        skip=skip,
        take=pageSize,
    )
    truncated = total > skip + len(rows)
    response.headers["X-Total-Count"] = str(total)
    response.headers["X-Truncated"] = "true" if truncated else "false"
    if truncated and page == 1 and pageSize == FEEDBACK_TAKE:
        # ONLY FOR THE CALLER THAT DID NOT ASK FOR A PAGE. A client that passes ``page``/``pageSize``
        # knows it is paging and is not being deceived by a short answer; the parameterless caller —
        # today, the master admin's feedback screen, which has no pager — is the one holding an
        # incomplete list it has no way to detect if it cannot read the headers. ERROR rather than
        # WARNING for the reason ``active_roster_emails`` gives: this is not a long list somebody can
        # narrow, it is rows absent from the only request that screen knows how to make.
        logger.error(
            "GET /feedback returned %s of %s rows to a caller that asked for no page; the master "
            "admin's feedback screen is showing an incomplete list and cannot say so",
            len(rows),
            total,
        )
    return [_serialize(row) for row in rows]
