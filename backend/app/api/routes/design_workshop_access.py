"""The design-workshop access queue on the wire: one ask, one list, one decision.

Three routes. Every rule they enforce — what a scanned code is worth, why the ask answers the same
thing whatever happened, why granting goes through the viewer mechanism and nothing else — lives in
``app/services/design_workshop_access.py``; this module is only the wire, and each refusal below
defers to that module rather than restating one.

**WHY ITS OWN ROUTER AND ITS OWN PREFIX.** ``/design-workshops`` is already shared by two routers
and carries ``GET /design-workshops/{workshop_id}``, which matches any literal path mounted after it
and answers 404 "Record not found" — the trap ``design_workshop_viewers`` documents at length and
``api/router.py`` carries a note about. ``design_ratings`` took its own prefix for that reason and
for a second one that applies here too: the routes below are deliberately reachable by people that
shared prefix's routes turn away. A designer calling ``POST /design-workshop-access/requests`` is by
definition somebody ``load_workshop_or_404`` refuses, and keeping them off that prefix keeps nobody
tempted to teach the shared loader about requests.

**THE ASK IS THE ONLY ROUTE IN THIS FILE THAT IS NOT ADMIN-ONLY, AND IT IS THE ONLY ONE THAT DOES
NOT ANSWER 404 FOR A RECORD THE CALLER MAY NOT HAVE.** Both of those are forced by what it is for
and both are argued in the service module's header under ENUMERATION. Read that before changing the
status code or the body: the uniformity of this answer is the mechanism, not a placeholder.
"""

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.deps import get_current_user, require_admin
from app.schemas.design_workshop_access import (
    DesignWorkshopAccessDecisionIn,
    DesignWorkshopAccessRequestIn,
)
from app.services import design_workshop_access
from app.services.design_workshop_access import STATUS_FILTERS, ScannedCodeRefused

router = APIRouter(prefix="/design-workshop-access", tags=["design-workshops"])

#: The one sentence :func:`request_access` ever says. WRITTEN ONCE AND SAID ALWAYS — a refusal or a
#: confirmation that varied by outcome would be the enumeration oracle this route is built to avoid,
#: and the surest way to reintroduce one is to "improve" the copy for a single branch.
#:
#: IT IS PHRASED CONDITIONALLY ON PURPOSE, in the manner of a password-reset screen. It does NOT say
#: "your request has been sent", which would be false for an id that names nothing; it does not say
#: the workshop exists; and it does not promise a decision, because an admin may decide nothing at
#: all. The clients must show this sentence and must not decorate it with a pending state the server
#: has not confirmed.
#:
#: ONE BRANCH WHERE IT IS A COURTESY RATHER THAN A LITERAL TRUTH, named because a reader who checks
#: will find it: a SOFT-DELETED workshop. ``file_request`` files nothing there, so no administrator
#: can see the ask, and the row does still sit in the table — the conditional only holds if "exists"
#: is read in the application's own sense, which for this caller it fairly is (a deleted workshop
#: answers ``load_workshop_or_404``'s 404 to everybody but an admin). The sentence is not softened
#: per branch and MUST NOT BE: a second wording for the deleted case is the existence oracle the
#: whole design gives up informative answers to avoid. If it is ever reworded, reword it for all
#: seven at once.
RECEIVED_DETAIL = (
    "If that workshop exists and you are not already on it, an administrator can now see that you "
    "have asked to join. Asking again will not send a second request."
)


@router.post("/requests", status_code=status.HTTP_202_ACCEPTED)
async def request_access(
    payload: DesignWorkshopAccessRequestIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Ask to be let into one design workshop.

    202 AND NOT 201, DELIBERATELY, and the difference is the whole point rather than pedantry about
    HTTP. 201 asserts that a resource was created, which is untrue in five of the seven outcomes this
    route has — and a client able to tell 201 from 202 could ask about any id and read the existence
    of the record off the status line. 202 "Accepted" is honest in every branch: the ask has been
    accepted for processing, and what came of it is not this caller's to know.

    ONE WORKSHOP PER CALL, unlike ``POST /workshops/access-requests``, which takes a list because a
    researcher joining a project needs a whole season at once. A design workshop is met one at a
    time, by scanning the card in front of you, and a list body would have nowhere to put the
    per-workshop code that is the point of the ask.

    OPEN TO ANY SIGNED-IN ACCOUNT, and the role is deliberately not checked here. Whether the
    requester may actually hold a viewer row is decided by ``replace_viewers`` at the moment of the
    GRANT — where the rule already lives, where it reads both rosters, and where it produces a
    sentence naming the screen that fixes it. Refusing a researcher at this door instead would put a
    second, quieter copy of that rule in the one place nobody can see its answer, and an admin would
    lose the ability to see that somebody had asked at all.
    """
    try:
        await design_workshop_access.file_request(
            current_user,
            workshop_id=payload.workshopId,
            scanned_code=payload.scannedCode,
            note=payload.note,
        )
    except ScannedCodeRefused as refusal:
        # 422 AND NOT THE UNIFORM ANSWER, because this refusal is about the BODY: the code is
        # damaged, or belongs to another kind of record, or names a different workshop from the id
        # beside it. None of that depends on which workshops exist, so saying it out loud discloses
        # nothing — and swallowing it would leave a designer re-scanning a card that will never work.
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=refusal.detail
        ) from refusal
    return {"received": True, "detail": RECEIVED_DETAIL}


@router.get("/requests")
async def list_requests(
    statusFilter: str = Query("PENDING"),
    _: Any = Depends(require_admin),
) -> dict[str, Any]:
    """The queue an admin works from — PENDING by default, oldest first, across every workshop.

    ``statusFilter=ALL`` widens it to the whole history for auditing, the same word
    ``GET /workshops/access-requests`` uses so an admin meets one vocabulary and not two.

    ``truncated`` says the answer was cut at ``QUEUE_LIMIT``. Both clients must say so when it is
    true and say nothing when it is false: a queue silently missing its oldest entries is people
    waiting for access nobody can see they asked for, which is the failure this whole feature exists
    to end, reintroduced by a limit.
    """
    wanted = (statusFilter or "PENDING").strip().upper()
    if wanted not in STATUS_FILTERS:
        # 422 and not 404: an unknown filter is a statement about the request, not about a record
        # the caller may or may not be allowed to see, so nothing is disclosed by naming the ones
        # that exist.
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"statusFilter is one of {', '.join(STATUS_FILTERS)}.",
        )
    return await design_workshop_access.queue(wanted)


@router.post("/requests/{request_id}/decide")
async def decide_request(
    request_id: str,
    payload: DesignWorkshopAccessDecisionIn,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    """Grant or refuse one request, and answer with the row as it now stands.

    ONE ROUTE FOR BOTH ANSWERS rather than a ``/grant`` and a ``/refuse``, matching
    ``POST /workshops/access-requests/{id}/decide``: the two are the same act with different
    contents, they carry the same note, and an admin screen renders them as one form.

    A row in ANY state may be decided, which departs from that sibling route — it 409s anything but
    PENDING and sends the admin to the roster endpoints instead. There is no such second endpoint
    here, and a refusal that could never be reversed is not a decision anybody should have to make
    carefully at 6pm. Granting a granted row is idempotent; the one combination that is refused is
    REFUSING somebody who can already open the workshop, because that would write DENIED over access
    that remains — see the service module, which names the screen that actually removes it.
    """
    return await design_workshop_access.decide(
        request_id,
        decision=(payload.status or "").strip().upper(),
        note=payload.note,
        admin=current_user,
    )
