import logging
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Response, status
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.deps import get_current_user, require_admin, require_master_admin
from app.schemas.feedback import (
    FeedbackReportCreate,
    FeedbackReportDecision,
    FeedbackUpsertRequest,
)
from app.services.concurrency import gather_reads
from app.services.feedback_vocabulary import (
    FEEDBACK_STATUSES,
    label_for,
    validate_choice,
    vocabulary_payload,
)
from app.services.pagination import normalize_pagination, page_payload

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
    # The two go out together: nothing in the page depends on the count and nothing in the count
    # depends on the page, so in series the count was a whole round trip spent on a number — a
    # cross-region one when this was written, a co-located one or two milliseconds since 2026-09-02
    # (``services/concurrency.py``). What that move changes is the SIZE of the saving, not the
    # shape; and it changes nothing at all about the paragraph above, because exactness is not a
    # performance property. It is still EXACT, which is what that paragraph defends.
    total, rows = await gather_reads(
        db.feedback.count(),
        db.feedback.find_many(
            # ``id`` is the TIEBREAKER and it is load-bearing now that this read is paged.
            # ``updatedAt`` is not unique — an upsert-per-account table gets ties whenever two
            # people send feedback in the same instant, and a non-total order under LIMIT/OFFSET
            # lets one row appear on two pages while another appears on none.
            order=[{"updatedAt": "desc"}, {"id": "desc"}],
            include={"user": True},
            skip=skip,
            take=pageSize,
        ),
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
# =================================================================================================
# THE GRIEVANCE / SUGGESTION / RECOMMENDATION REGISTER.
#
# Everything above this line is the SATISFACTION SURVEY: one row per account, upserted, revisable
# for ever. Everything below is the REGISTER: many rows per account, each frozen as written, each
# with a status somebody is accountable for. `schema.prisma`'s two model comments carry the argument
# for why they are two tables; the short version is that `Feedback.userId` is `@unique`, so a person
# who reported a bug on Monday and filed a grievance on Friday overwrote the bug report, and a
# register whose second entry destroys the first is not a register.
#
# THE READER IS AN ADMIN HERE AND A MASTER ADMIN ABOVE, AND THAT DIFFERENCE IS DELIBERATE.
# `GET /feedback` stays `require_master_admin` — it is unchanged, and widening who may read a named
# colleague's standing opinion of the software is not this change's to make. The register is gated
# `require_admin` instead, because a redressal mechanism in which exactly one account in the
# institution can acknowledge anything is a mechanism that will not redress anything: the person who
# reads a grievance has to be somebody who is around. `/admin`'s "User feedback" tile has been
# admin-visible all along while pointing at a master-admin-only list, so an admin who followed it
# reached a screen with no inbox on it; the web client now shows this register there, which is what
# makes that tile true for the first time.
# =================================================================================================


#: Every persisted column of a report that is echoed back verbatim. One list, so the serializer
#: cannot drift from the table the way `_serialize`'s hand-written dict once could.
REPORT_FIELDS = (
    "kind",
    "severity",
    "area",
    "subject",
    "details",
    "client",
    "clientVersion",
    "platform",
    "pagePath",
    "status",
    "acknowledgedAt",
    "resolvedAt",
    "responseNote",
)

#: How many reports one page of the administrator's inbox carries by default. Well under
#: `MAX_PAGE_SIZE`, because this is a queue somebody WORKS rather than a table somebody scrolls: a
#: hundred grievances on one screen is a hundred nobody reads to the bottom of.
REPORT_PAGE_SIZE = 25


def _actor(user: Any) -> dict[str, Any] | None:
    """A person, reduced to the four fields every feedback surface prints.

    Never the password hash, never the capability flags — the same narrowing `_serialize` does for
    the survey's author, kept as a shared function so a column added to `User` cannot leak onto this
    surface by being included in a relation somebody widened.
    """
    if user is None:
        return None
    return {
        "id": user.id,
        "name": user.name,
        "email": user.email,
        "role": getattr(user.role, "value", user.role),
    }


def _serialize_report(row: Any) -> dict[str, Any]:
    """One report, with its labels resolved server-side.

    THE LABELS TRAVEL WITH THE VALUES AND THAT IS THE WHOLE REASON THIS IS NOT A BARE COLUMN DUMP.
    SKILL.md §16: when a feature lands on both clients the shared vocabulary must come from the
    server, or the two will one day describe one submission differently. So the row carries
    `kind` AND `kindLabel`; a client renders the label and files against the value, and neither web
    nor Android holds a copy of "what a GRIEVANCE is called". `label_for` falls back to the raw key,
    so a report filed under a category since retired still prints instead of raising mid-export.
    """
    data: dict[str, Any] = {
        "id": row.id,
        "userId": row.userId,
        "createdAt": jsonable_encoder(row.createdAt),
        "updatedAt": jsonable_encoder(row.updatedAt),
    }
    for field in REPORT_FIELDS:
        data[field] = jsonable_encoder(getattr(row, field, None))
    for field in ("kind", "severity", "area", "status", "client"):
        data[f"{field}Label"] = label_for(field, getattr(row, field, None))
    data["user"] = _actor(getattr(row, "user", None))
    data["acknowledgedBy"] = _actor(getattr(row, "acknowledgedBy", None))
    data["resolvedBy"] = _actor(getattr(row, "resolvedBy", None))
    return data


#: Loaded on every read of a report. All three are needed by the surfaces that print them: `user`
#: for the inbox's "who said this", and the two actors because "acknowledged" with no name attached
#: is exactly the claim this register exists so an institution cannot make.
REPORT_INCLUDE = {"user": True, "acknowledgedBy": True, "resolvedBy": True}


@router.get("/vocabulary")
async def feedback_vocabulary(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    """Every closed list a report is filed against — kinds, severities, areas, statuses, clients.

    ONE DEFINITION, SERVED, rather than two copies compiled into two clients. Both the web form and
    the Android form render their dropdowns from this, so a category added in
    `services/feedback_vocabulary` reaches both without either being rebuilt, and neither client can
    invent a member the validator would refuse.

    `get_current_user` and no more: these are the labels on a form every signed-in person may fill
    in, so gating them any harder would gate the form itself. Nothing here is data about anybody.
    """
    return vocabulary_payload()


@router.post("/reports", status_code=status.HTTP_201_CREATED)
async def create_report(
    payload: FeedbackReportCreate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """File a grievance, suggestion, recommendation or bug report. Any signed-in account.

    A CREATE AND NEVER AN UPSERT, which is the whole difference from `PUT /feedback/me` above. Two
    reports from one person are two reports; the second must not be able to destroy the first, and
    on a grievance register that property is not a nicety.

    THE THREE CLOSED LISTS ARE VALIDATED HERE, NOT IN THE PYDANTIC MODEL, so that
    `services/feedback_vocabulary` is the single place membership is decided for the API, for the
    vocabulary endpoint the clients render from, and for the export's labels. `kind` is required and
    the other two are not — see that module for why a person filing a grievance is not made to rank
    their own distress before the app will accept it.

    NOTHING ABOUT THE STATUS IS SETTABLE BY THE REPORTER. It is the column default, SUBMITTED. A
    payload field for it would let a client file something already marked resolved, which is not a
    hole worth leaving open on the one table whose point is that somebody is accountable for the
    transitions.
    """
    kind = validate_choice("kind", payload.kind, required=True)
    severity = validate_choice("severity", payload.severity)
    area = validate_choice("area", payload.area)
    # The client identifier goes through the same validator as the answers a person gives, even
    # though no person types it: a client that reports itself as something other than WEB or ANDROID
    # would put an unfilterable value into the one column the research cut groups by. `required` is
    # False, so a caller that says nothing about itself still files a valid report.
    client = validate_choice("client", payload.client)

    row = await db.feedbackreport.create(
        data={
            "kind": kind,
            "severity": severity,
            "area": area,
            "subject": payload.subject.strip(),
            "details": payload.details.strip(),
            "client": client,
            # `or None` on each: a client that sends "" for a value it could not determine stores a
            # NULL rather than an empty string, so "we do not know" and "it is blank" are one answer
            # in the export instead of two that a researcher would have to reconcile.
            "clientVersion": (payload.clientVersion or "").strip() or None,
            "platform": (payload.platform or "").strip() or None,
            "pagePath": (payload.pagePath or "").strip() or None,
            "user": {"connect": {"id": current_user.id}},
        },
        include=REPORT_INCLUDE,
    )
    return _serialize_report(row)


@router.get("/reports/mine")
async def my_reports(
    current_user: Any = Depends(get_current_user),
    page: int = Query(1, ge=1),
    pageSize: int = Query(REPORT_PAGE_SIZE, ge=1),
) -> dict[str, Any]:
    """Everything THIS account has filed, newest first, each with where it has got to.

    THIS ROUTE IS THE REDRESSAL HALF AND IT IS THE REASON THE TABLE HAS ACTOR COLUMNS AT ALL. The
    brief: *"a grievance mechanism that cannot show a person their grievance was seen is not a
    redressal mechanism."* So a reporter reads back their own report, its status, the NAME of the
    administrator who acknowledged or resolved it, when, and whatever note was written to them.

    IT IS SCOPED BY `current_user.id` AND TAKES NO `userId` PARAMETER. A parameter would be a
    permission check waiting to be forgotten; there is nothing here to forget, because the only row
    set this route can express is the caller's own.
    """
    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    where = {"userId": current_user.id}
    total, rows = await gather_reads(
        db.feedbackreport.count(where=where),
        db.feedbackreport.find_many(
            where=where,
            # `id` is the tiebreaker for the reason the survey list states above: `createdAt` is not
            # unique, and a non-total order under LIMIT/OFFSET lets one row appear on two pages while
            # another appears on none. Two reports filed in the same second is an ordinary thing for
            # somebody working through a list of complaints in one sitting.
            order=[{"createdAt": "desc"}, {"id": "desc"}],
            include=REPORT_INCLUDE,
            skip=skip,
            take=clean_size,
        ),
    )
    payload = page_payload([_serialize_report(row) for row in rows], total, clean_page, clean_size)
    # How many of the caller's OWN reports are still waiting for an answer. It rides on this
    # envelope rather than getting a route of its own so the settings card can draw its whole
    # summary from ONE request with `pageSize=1` — the alternative was a second endpoint whose only
    # job was to return an integer, on a hub every signed-in account loads.
    #
    # "OPEN" HERE MEANS "NOT RESOLVED", WHICH INCLUDES ACKNOWLEDGED, and that is the whole point of
    # counting it this way round: a report somebody has read and not answered is exactly the one a
    # redressal mechanism loses, and a count of SUBMITTED alone would report it as settled. The
    # administrator's inbox counts SUBMITTED instead, because there the question is "what has nobody
    # picked up" — two different questions, deliberately not one shared number.
    payload["openCount"] = await db.feedbackreport.count(
        where={"AND": [{"userId": current_user.id}, {"NOT": {"status": "RESOLVED"}}]}
    )
    return payload


@router.get("/reports")
async def list_reports(
    _: Any = Depends(require_admin),
    kind: str | None = Query(None),
    reportStatus: str | None = Query(None, alias="status"),
    severity: str | None = Query(None),
    area: str | None = Query(None),
    page: int = Query(1, ge=1),
    pageSize: int = Query(REPORT_PAGE_SIZE, ge=1),
) -> dict[str, Any]:
    """The administrator's inbox: every report, newest first, narrowed by any of the four lists.

    AN ENVELOPE, NOT A BARE ARRAY, and it is new so it can be. `GET /feedback` above is stuck
    answering a bare array because a deployed client maps the response directly and an envelope
    would replace that screen with a runtime error the moment it shipped; this route has no such
    client yet, so it does what every other list route in this codebase does and carries its own
    total and page count in the body. There is nothing here for `X-Truncated` to say, because the
    envelope says it exactly.

    EVERY FILTER GOES THROUGH THE SAME VALIDATOR THE WRITE PATH USES. A misspelled `?kind=greivance`
    would otherwise return an empty page — which on a queue reads as "there are no grievances", the
    silent-emptiness failure this repository has hit more than any other. It 422s instead, naming
    the members.

    `status` IS TAKEN UNDER AN ALIAS because the parameter a client wants to send is `status` and
    the name `status` is already bound in this module to `fastapi.status`, whose `HTTP_201_CREATED`
    is two functions up. Shadowing it would not fail here — it would fail in whichever handler was
    added next, at import time, and read as an unrelated breakage.
    """
    clauses: list[dict[str, Any]] = []
    if kind:
        clauses.append({"kind": validate_choice("kind", kind, required=True)})
    if reportStatus:
        clauses.append({"status": validate_choice("status", reportStatus, required=True)})
    if severity:
        clauses.append({"severity": validate_choice("severity", severity, required=True)})
    if area:
        clauses.append({"area": validate_choice("area", area, required=True)})
    where: dict[str, Any] = {"AND": clauses} if clauses else {}

    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    total, rows = await gather_reads(
        db.feedbackreport.count(where=where),
        db.feedbackreport.find_many(
            where=where,
            order=[{"createdAt": "desc"}, {"id": "desc"}],
            include=REPORT_INCLUDE,
            skip=skip,
            take=clean_size,
        ),
    )
    payload = page_payload([_serialize_report(row) for row in rows], total, clean_page, clean_size)
    # The open count is computed against the WHOLE table and not against the filtered page, because
    # it answers "how much is waiting for somebody" and an admin who has narrowed to GRIEVANCE must
    # not be told the queue is empty when eleven bug reports are unread. One extra count on an
    # indexed column.
    #
    # It is drawn beside the inbox heading on the web client and NOWHERE ELSE. In particular the
    # `/admin` hub's "User feedback" tile carries no badge from it: `Tile.badge` exists there and the
    # pending-access count uses it, but wiring a second one means a second unconditional request on
    # a hub every admin loads, and that is a decision to take deliberately rather than as a side
    # effect of this number existing. Said here because a comment claiming a consumer that does not
    # exist is exactly the kind of thing the next reader would go looking for.
    payload["openCount"] = await db.feedbackreport.count(where={"status": "SUBMITTED"})
    return payload


async def _decide(
    report_id: str,
    *,
    to_status: str,
    note: str | None,
    actor: Any,
    note_required: bool,
) -> dict[str, Any]:
    """The shared body of acknowledge and resolve: check, stamp, return.

    ONE FUNCTION FOR BOTH TRANSITIONS, because the parts that must not drift are the parts they
    share — the 404, the terminal-state refusal, and the fact that BOTH stamp a name and a time. Two
    handlers would be two chances for one of them to record the time and not the person, and the
    whole promise of this register is that "your grievance was seen" has somebody's name on it.

    A RESOLVED REPORT IS TERMINAL AND CANNOT BE RE-DECIDED. Not because reopening is unreasonable,
    but because there is nowhere honest to put the second decision: the table holds ONE
    acknowledger, ONE resolver and ONE note, so a second pass would overwrite whatever was said to
    the reporter the first time — and it is precisely the person who was told "resolved: we have
    changed the form" who must still be able to read that sentence a month later. A reopen worth
    having needs an append-only decision log (`DwAiLayerDecision` is the shape), which is a table
    this change does not add. Refusing plainly beats silently rewriting history.
    """
    row = await db.feedbackreport.find_unique(where={"id": report_id})
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="That feedback report no longer exists.",
        )
    current = str(getattr(row, "status", "") or "")
    if current == "RESOLVED":
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                "That report is already resolved, and this register keeps one decision per report "
                "so the answer its author was given cannot be overwritten."
            ),
        )
    if current == to_status:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"That report is already {FEEDBACK_STATUSES[to_status].lower()}.",
        )

    cleaned = (note or "").strip()
    if note_required and not cleaned:
        # THE ONE PLACE THIS REGISTER INSISTS ON WORDS, and it insists on them here rather than on
        # acknowledgement because the two acts promise different things. "Acknowledged" says only
        # that a named person read it, which is true without further explanation. "Resolved" says it
        # is finished — and an institution that may close a grievance without saying how has a
        # queue-clearing button, not a redressal mechanism. Same reasoning as `NEEDS_REVISION` on
        # the review ladder, where the comment is mandatory for the same reason.
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "Resolving a report needs a note saying what was done — the person who filed it "
                "reads it."
            ),
        )

    now = datetime.now(UTC)
    data: dict[str, Any] = {"status": to_status}
    if to_status == "ACKNOWLEDGED":
        data["acknowledgedAt"] = now
        data["acknowledgedBy"] = {"connect": {"id": actor.id}}
    else:
        data["resolvedAt"] = now
        data["resolvedBy"] = {"connect": {"id": actor.id}}
        # A report resolved straight from SUBMITTED was also, necessarily, read. Stamping the
        # acknowledgement too means the reporter's own list can always answer "when was this seen",
        # rather than showing a blank beside a report that went from filed to finished in one step.
        if not getattr(row, "acknowledgedAt", None):
            data["acknowledgedAt"] = now
            data["acknowledgedBy"] = {"connect": {"id": actor.id}}
    if cleaned:
        data["responseNote"] = cleaned

    updated = await db.feedbackreport.update(
        where={"id": report_id}, data=data, include=REPORT_INCLUDE
    )
    # `update` answers None when the row vanished between the read above and the write. Reporting
    # success for a write that did not happen is the failure this repository files under "exit zero
    # is not evidence"; the 404 is the honest answer and it is the same one the read gives.
    if updated is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="That feedback report no longer exists.",
        )
    return _serialize_report(updated)


@router.post("/reports/{report_id}/acknowledge")
async def acknowledge_report(
    report_id: str,
    payload: FeedbackReportDecision | None = None,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    """Record that a named administrator has READ this report. The note is optional here."""
    return await _decide(
        report_id,
        to_status="ACKNOWLEDGED",
        note=payload.note if payload else None,
        actor=current_user,
        note_required=False,
    )


@router.post("/reports/{report_id}/resolve")
async def resolve_report(
    report_id: str,
    payload: FeedbackReportDecision,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    """Close this report, saying what was done. The note is REQUIRED — see `_decide`."""
    return await _decide(
        report_id,
        to_status="RESOLVED",
        note=payload.note,
        actor=current_user,
        note_required=True,
    )
