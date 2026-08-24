"""The design-workshop access queue on the wire: one ask, one list, one decision — and the join
card that replaces asking with joining.

Seven routes. Every rule they enforce — what a scanned code is worth, why the ask answers the same
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

=======================================================================================
THE FOUR JOIN-CARD ROUTES, AND WHICH DOOR EACH ONE IS
=======================================================================================

``services/design_workshop_grants.py`` adds a printed JOIN CARD whose 110-bit secret IS the
credential, so that scanning one is equivalent to an admin adding somebody. Its header carries every
rule; this file is only the wire. What is worth saying HERE is which of the three refusal disciplines
each route follows, because they are genuinely different and the file now holds all three:

* ``POST /grants``, ``GET /grants/{record_id}``, ``POST /grants/{token_id}/revoke`` follow the
  ORDINARY 404 RULE. A caller who may not see the record gets ``require_record``'s own "Record not
  found", exactly as every other read in this repository does, so minting is not an existence oracle.
  **They are NOT ``Depends(require_admin)``, and that is deliberate**: the courtyard case that
  motivates the whole feature is somebody already on the workshop handing a card to the person beside
  them. What IS admin-only is a card good for more than one person, refused inside ``mint_grant``
  where the rule can see both the actor's role and the record.

* ``POST /redemptions`` is neither. It is called by somebody who by construction may not see the
  record — like the ask route — but it ANSWERS THE REDEEMER ABOUT THEIR OWN CARD, so it may be
  specific where the ask route may not. The discipline is stated in the service header and enforced
  by one constant: an unknown secret, a revoked card and a card expired beyond the sync grace all
  answer ``CARD_REFUSED_DETAIL`` and nothing else. It must never become a workshop-existence oracle
  for a code that resolves to nothing.

**NO ROUTE IN THIS FILE MAY EVER ECHO A JOIN CARD BACK.** ``POST /grants`` returns a freshly minted
one because that is the only moment it exists; nothing else does, and the queue stores a redacted
form. A 422 body carrying a code somebody scanned would put live credentials into access logs.
"""

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.deps import get_current_user, require_admin
from app.schemas.design_workshop_access import (
    DesignWorkshopAccessDecisionIn,
    DesignWorkshopAccessRequestIn,
)
from app.schemas.design_workshop_grants import JoinCardMintIn, JoinCardRedeemIn
from app.services import design_workshop_access, design_workshop_grants
from app.services.design_workshop_access import STATUS_FILTERS, ScannedCodeRefused
from app.services.design_workshop_grants import CARD_REFUSED_DETAIL, CardRefused

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
    requester may actually hold a viewer row is decided at the moment of the GRANT, by the eligibility
    rule ``design_workshop_viewers`` owns — where the rule already lives, where it reads both rosters,
    and where it produces a sentence naming the screen that fixes it. Refusing a researcher at this
    door instead would put a second, quieter copy of that rule in the one place nobody can see its
    answer, and an admin would lose the ability to see that somebody had asked at all.
    """
    try:
        await design_workshop_access.file_request(
            current_user,
            workshop_id=payload.workshopId,
            scanned_code=payload.scannedCode,
            note=payload.note,
            # The device's own scan time, and it changes nothing about what this route answers. It is
            # stored beside the ask so an administrator can see that a request which ARRIVED just now
            # was made two days ago in a courtyard with no signal. It never orders anything: the
            # column is written from a clock its holder can set, and `createdAt` is the one that
            # adjudicates. See the schema field.
            scanned_at=payload.scannedAt,
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


# --------------------------------------------------------------------------------------
# Join cards
# --------------------------------------------------------------------------------------


@router.post("/grants", status_code=status.HTTP_201_CREATED)
async def mint_grant(
    payload: JoinCardMintIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Print one join card. **The response is the only time the card's secret ever exists.**

    201 AND NOT 202, and the contrast with ``POST /requests`` two functions up is the whole point
    rather than pedantry about HTTP. That route answers 202 because 201 would assert a resource was
    created, which is untrue in five of its seven outcomes and would let a caller read the existence
    of a record off the status line. Here a row genuinely was created, the caller was already
    entitled to see the record (or got a 404), and there is nothing left to disclose — so the honest
    status code is the specific one.

    ``Depends(get_current_user)`` AND NOT ``require_admin``, DELIBERATELY, and this is the line most
    likely to be "corrected" by somebody reading the requirement that multi-use is admin-only. The
    requirement is enforced — inside ``mint_grant``, where the rule can see the actor's role AND the
    record, which is the only place both are in scope. Putting ``require_admin`` here instead would
    refuse the case the whole feature exists for: a designer already on a workshop handing a card to
    the person standing next to them in a courtyard, because there is no administrator within two
    districts. That designer gets SINGLE-USE cards, capped at three outstanding, every one visible
    with its issuer and revocable — and a 403 with a sentence naming the screen if they ask for more.

    404 AND NOT 403 for a record the caller may not print cards for, with ``require_record``'s own
    detail string, exactly as ``load_workshop_or_404`` does. Minting CAN follow the repository's
    ordinary enumeration rule — unlike the ask route, whose purpose is to be called by somebody who
    may not see the record — so it does.
    """
    return await design_workshop_grants.mint_grant(
        current_user,
        record_type=payload.recordType,
        record_id=payload.recordId,
        max_uses=payload.maxUses,
        days_valid=payload.daysValid,
        label=payload.label,
    )


@router.get("/grants/{record_id}")
async def list_grants(
    record_id: str,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Every join card printed for one record, newest first. **Never carries a secret.**

    ``secretLast4`` is the only part of a card that appears here — twenty bits, enough to match the
    card in somebody's hand and useless to a guesser. If you are adding a field to this answer, read
    ``grant_payload``: this is the one table in the schema that holds a credential, and the payload
    is hand-projected rather than encoded precisely so that whatever the model gains next does not
    arrive on an access-administration screen by itself.

    THE SAME DOOR AS MINTING, and not ``require_admin``, for a reason that is small and real: a
    designer who has hit the outstanding-cards cap is told to "let somebody use one", which is
    useless advice if they cannot see which cards exist. ``truncated`` says the answer was cut at
    ``GRANT_LIST_LIMIT``; a client must say so when it is true, because a card an admin cannot see is
    a card they cannot revoke.
    """
    return await design_workshop_grants.list_grants(record_id, current_user)


@router.post("/grants/{token_id}/revoke")
async def revoke_grant(
    token_id: str,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Cancel one join card. Idempotent, and it does **not** remove anybody it already let in.

    THE TWO HALVES ARE SEPARATE ACTIONS ON PURPOSE — see the service function. Revoking stops the
    card admitting anybody FURTHER; taking access away from somebody it already admitted is the
    viewers PUT, which is the only place a grant is undone. A revoke that also evicted people would
    mean an admin cancelling one misprinted batch silently removed the colleagues who had legitimately
    used it, mid-workshop.

    ``POST`` and not ``DELETE``, because nothing is deleted: the row is the only record that the card
    existed, who minted it, and who it admitted. ``DELETE`` would also read as the other half.
    """
    return await design_workshop_grants.revoke_grant(token_id, current_user)


@router.post("/redemptions")
async def redeem_grant(
    payload: JoinCardRedeemIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Scan a join card. **This is the induction, and it is equivalent to an admin adding somebody.**

    -- WHY THIS ANSWER MAY BE SPECIFIC WHERE ``POST /requests`` MAY NOT --------------------------

    That route answers one fixed 202 for all seven of its outcomes, because it is called by somebody
    who may not see the record and any variation would be an existence oracle. This route is called
    by the same kind of person and it may still say what happened, for one reason: **the caller
    presented a 110-bit secret, so a distinguishable answer is only reachable by somebody who
    already holds a genuine card for the record they are being told about.** The uniform half is
    still uniform — an unknown secret, a revoked card and a card expired beyond the sync grace all
    answer ``CARD_REFUSED_DETAIL``, which names no workshop and does not say which of the three
    happened.

    200 AND NOT 201, for every outcome including the full grant. The resource this creates is a
    membership, not a redemption receipt, and the three outcomes — full, provisional, already a
    member — are not distinguishable by status code without turning the status line into the oracle
    the body is careful not to be. The body says which, in a sentence written for somebody standing
    in a courtyard.

    403 AND NOT 404 FOR A REFUSED CARD, which is the one place this file departs from the
    repository's ordinary rule, and the departure is safe because the 403 is about the CARD rather
    than about a record: it is the same three digits and the same sentence whether the workshop the
    card names exists, was deleted, or never did. A 404 would have been read by every client as "no
    such route".

    422 FOR A DAMAGED CARD, decided from the body alone and **before any database read**. That is the
    same discipline ``POST /requests`` keeps, it is what makes saying it out loud safe, and
    ``tests/test_design_workshop_grant_gate.py`` asserts it with a tripwire ``db``: "422 and the
    database was never touched". Move a lookup above ``decode_join_code`` and that test goes red.

    ⚠ **NEITHER REFUSAL ECHOES THE CODE.** A join card's payload is a live credential, and a 422
    body is the easiest place in a web application for one to end up in an access log.
    """
    try:
        return await design_workshop_grants.redeem(
            current_user,
            code=payload.code,
            scanned_at_client=payload.scannedAt,
            scanned_at_elapsed_sec=payload.scannedAtElapsedSec,
            synced_at_elapsed_sec=payload.syncedAtElapsedSec,
            boot_id=payload.bootId,
            clock_jump_observed=payload.clockJumpObserved,
        )
    except ScannedCodeRefused as refusal:
        # 422, ABOUT THE BODY. The card is damaged, or is a record tag rather than a join card, or was
        # printed against a newer format. None of that depends on which records exist, so saying it
        # discloses nothing — and swallowing it would leave a designer re-scanning a card that will
        # never work. ``refusal.detail`` is written by the decoder and never contains what was sent.
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=refusal.detail
        ) from refusal
    except CardRefused as refusal:
        # 403, WITH ONE SENTENCE FOR THREE CAUSES. See ``CARD_REFUSED_DETAIL``: written once and said
        # always, because a refusal that varied by outcome is the oracle this design gives up
        # informative answers to avoid. The exception carries no detail of its own precisely so that
        # no future branch can put one there.
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail=CARD_REFUSED_DETAIL
        ) from refusal
