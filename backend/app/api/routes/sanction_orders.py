"""``/api/sanction-orders`` — the ministry's sanction register.

══ WHAT AN OFFICER DOES HERE, AND WHAT IT COSTS ═══════════════════════════════════════════════

Three facts off the paper — order number, order date, sanctioned amount — and the designer or
designers it was issued to. Recording one writes SEVEN ROWS PLUS FOUR PER NAMED DESIGNER in one
transaction: an allow-list admission, an empanelment, an account (only where the mailbox has
none), a designer profile and a viewer row for each of them, plus the workshop, the sanction
order itself and one ``SanctionOrderDesigner`` row per name. The argument for the order of those
writes, the transaction boundary and every refusal that happens before the first of them is in
``app/services/sanction_orders.py``; this module is the doors.

**IT NAMED EXACTLY ONE DESIGNER UNTIL 0.0.12**, and this paragraph said "five fields ... seven
rows". A sanction order is routinely issued for a team, and while the register could name one
designer the second and third were either left off the instrument entirely — no account, no
empanelment, unable to open the workshop their own order paid for — or recorded as a second
order under a number the ministry never issued. The three designer scalars on ``SanctionOrder``
are unchanged and still mean THE LEAD.

══ AND A SHEET OF THEM CAN BE UPLOADED, IN TWO STEPS ══════════════════════════════════════════

``POST /upload`` reads a workbook and WRITES NOTHING: it answers three lists — what it will
record, what it needs the officer to settle, and what it refuses whatever they say.
``POST /upload/confirm`` carries the officer's answers back and records them, ONE TRANSACTION
PER ORDER. The confirmation is stateless (no token, no server-side parse held between the two
requests) and every refusal runs again on the way in, so a stale tab can be refused but cannot
record anything the officer's own form would not. ``app/services/sanction_import.py`` enumerates
every way the two designer columns can fail to tally and what happens to each.

══ WHY EVERY ARM IS GATED, READ INCLUDED ══════════════════════════════════════════════════════

``require_sanction_recorder`` guards GET as well as POST, for the reason ``require_access_manager``
gives about its own queue: this list is a register of named designers, their personal addresses and
the public money attached to them. A read gate that was one tier looser than the write gate would
make the register browsable by people who cannot be told apart from those who may add to it.

══ THERE IS NO DELETE ON THIS PREFIX, AND THAT IS A DECISION ══════════════════════════════════

Nothing in this product that records a person's standing or an institution's decision is ever
deleted — ``/designers/roster`` and ``/access/roster`` both say so in their own words. A sanction
order is a financial instrument: the row is the record that money was authorised, and a register
whose rows can be removed is not a register.

**AND THE GAP THAT LEAVES IS REAL AND SHOULD BE RAISED RATHER THAN DISCOVERED.** There is no
``cancelledAt``, no ``supersededById`` and no amount edit. The first time the ministry WITHDRAWS an
order, this product's only answer is "edit the notes". That is an open question for the owner; the
right shape is probably a cancellation ROW rather than a column, so that a withdrawal is itself an
instrument with a date and an officer on it. It is deliberately not invented here.

══ THE GATE'S HOME ════════════════════════════════════════════════════════════════════════════

``can_record_sanction_orders`` and ``SANCTION_ORDER_REFUSAL`` live in
``app/services/sanction_orders.py`` rather than in ``app/core/deps.py``, where every other ``can_*``
predicate in this product lives. That is not a design opinion — ``deps.py`` was owned by another
change in flight when this landed — and moving them is a welcome follow-up. The dependency below is
defined here so that the refusal is still spelled exactly once, and
``tests/test_sanction_order_gate.py`` pins the sentence across all three surfaces that say it.
"""

from __future__ import annotations

import asyncio
from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, File, HTTPException, Query, Request, UploadFile, status
from fastapi.responses import Response

from app.core.db import db
from app.core.deps import get_current_user
from app.schemas.sanction_orders import (
    SanctionImportConfirm,
    SanctionOrderCreate,
    SanctionOrderUpdate,
)
from app.services import designers, sanction_import, sanction_orders
from app.services.concurrency import gather_reads
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import add_date_range, contains, with_id_tiebreak
from app.services.sanction_orders import (
    SANCTION_INCLUDE,
    SANCTION_ORDER_REFUSAL,
    can_record_sanction_orders,
)
from app.services.sanction_orders_xlsx import (
    PRO_FORMA_FILENAME,
    SanctionXlsxError,
    build_sanction_pro_forma,
    parse_sanction_workbook,
)
from app.services.uploads import read_workbook_upload
from app.services.xlsx_report import xlsx_response

_WRONG_TYPE_DETAIL = (
    "That is not an Excel workbook. Download the sanction order pro-forma, type the orders into "
    "it, and upload that — or use File > Save As in Excel and choose 'Excel Workbook (.xlsx)'."
)
_EMPTY_DETAIL = "The upload was empty. Attach the filled-in sanction order pro-forma."

router = APIRouter(prefix="/sanction-orders", tags=["sanction-orders"])


async def require_sanction_recorder(current_user: Any = Depends(get_current_user)) -> Any:
    """Gates EVERY arm of /api/sanction-orders, read included — see the module docstring.

    The refusal sentence is imported rather than retyped. It is said on three surfaces (this 403,
    the web route guard's lock panel, and the nav entry that is simply absent), and a refusal naming
    a different next move depending on where you met it is not a rule, it is three rumours.
    """
    if not can_record_sanction_orders(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=SANCTION_ORDER_REFUSAL)
    return current_user


# --------------------------------------------------------------------------------------
# Writing
# --------------------------------------------------------------------------------------


@router.post("", status_code=status.HTTP_201_CREATED)
async def record_sanction_order(
    payload: SanctionOrderCreate,
    officer: Any = Depends(require_sanction_recorder),
) -> dict[str, Any]:
    """Record one sanction order, and open everything it authorises.

    201 carries ``{sanctionOrder, credentialLink, credentialLinkProblem}``. ``credentialLink`` is
    SHOWN ONCE and cannot be shown again — the table stores only a SHA-256 digest — which is why the
    officer's screen renders it in a panel with a Copy button and a prewritten message, and why the
    re-issue arm below exists at all.

    409 on a duplicate number (in either spelling), 409 on a mailbox two accounts answer to, 422 on
    an address an admin has barred or an empanelment an admin has ended. Every one of those is
    decided BEFORE the first row is written, so a refusal leaves nothing behind.
    """
    return await sanction_orders.create_from_sanction(payload, officer)


@router.post("/upload")
async def upload_sanction_orders(
    request: Request,
    file: UploadFile = File(...),
    officer: Any = Depends(require_sanction_recorder),
) -> dict[str, Any]:
    """STEP ONE OF TWO: read the sheet, reconcile it against the rosters, and WRITE NOTHING.

    200 and not 201, because nothing was created. The answer is
    ``{sheet, sourceFilename, rowsRead, ready[], needsReview[], refused[], problems[]}`` —
    ``services/sanction_import`` enumerates, in its own header, every one of the nineteen ways the
    two designer columns can fail to tally and what happens to each. An officer who uploads the
    wrong file and closes the tab has changed nothing at all.

    ``request`` IS NOT A PARAMETER ANY CLIENT SENDS. FastAPI fills it from the connection; it is
    here only so the size gate can read the declared ``Content-Length`` and refuse an oversized
    workbook before copying it into the heap.

    **THERE IS DELIBERATELY NO ``Form()`` SCALAR ON THIS BODY.** The annual plan's upload has two,
    and each is typed ``str | None`` and validated by hand for a reason its docstring sets out at
    length: a bare default is read off the QUERY STRING by FastAPI, so a client that put the value
    in the body has it silently ignored under a 201. This route has nothing to put there — the date
    is on every row, and there is no destructive flag to guard — and that absence is worth stating,
    because "add a scalar" is the change that walks into that trap.

    ``asyncio.to_thread`` FOR THE PARSE. Two hundred rows of openpyxl is tens of milliseconds of
    pure CPU and this process serves every other request while it runs. The RECONCILIATION stays on
    the loop, correctly: it is database reads, not CPU.
    """
    content = await read_workbook_upload(
        file,
        sanction_import.MAX_UPLOAD_BYTES,
        request=request,
        purpose="sanction order workbook",
        wrong_type_detail=_WRONG_TYPE_DETAIL,
        empty_detail=_EMPTY_DETAIL,
    )
    try:
        parsed = await asyncio.to_thread(
            parse_sanction_workbook, content, filename=file.filename
        )
    except SanctionXlsxError as exc:
        # THE WHOLE FILE, NOT ONE ROW. ``SanctionXlsxError`` is raised only when there is nothing to
        # import at all; a row this could not read comes back in ``problems`` with the other
        # rows still offered, which is the contract ``xlsx_table`` states and the reason an officer
        # with 198 of 200 orders is far better off than one with an error page.
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc

    return await sanction_import.review_sheet(parsed, officer=officer)


@router.post("/upload/confirm", status_code=status.HTTP_201_CREATED)
async def confirm_sanction_orders(
    payload: SanctionImportConfirm,
    officer: Any = Depends(require_sanction_recorder),
) -> dict[str, Any]:
    """STEP TWO OF TWO: record the rows the officer agreed to. **NO FILE, AND NO TOKEN.**

    The confirmation is STATELESS: this body carries the resolved rows rather than a ticket quoting
    a parse the server is holding. :class:`SanctionImportConfirm` argues why — in short, this
    repository holds no server-side inter-request state anywhere, and the one short-TTL-token
    precedent stores a digest rather than the token and is a poor template for "remember this parse
    for ten minutes".

    **EVERY REFUSAL RUNS AGAIN, PER ROW, AND THAT IS WHAT MAKES THE STATELESSNESS SAFE.** Nothing
    here writes a row itself: each order goes through ``create_from_sanction``, the same door the
    officer's form uses, so a stale tab or a hand-edited body meets exactly the rules the form meets,
    in exactly the same words. What a stale body can do is be REFUSED — it cannot record something
    the form would not.

    201 because rows were created, and the body is the report:
    ``{rowsRead, recorded, skipped, refused, accountsCreated, created[], importId, problems[]}``.
    **The four counts must add up on screen** — ``rowsRead = recorded + skipped + refused`` — which
    is only checkable if every one of them including the zeroes is drawn.

    IT IS 201 EVEN WHEN EVERY ROW WAS REFUSED, and that is deliberate rather than sloppy. The
    request itself succeeded and produced a report; answering 4xx would put the whole report in an
    error payload that the client's error path renders as one sentence, which is the opposite of
    what an officer needs from two hundred rows. The report says, in numbers, that nothing landed.
    """
    return await sanction_import.apply_confirmed_rows(payload, officer)


@router.patch("/{sanction_id}")
async def update_sanction_order(
    sanction_id: str,
    payload: SanctionOrderUpdate,
    officer: Any = Depends(require_sanction_recorder),
) -> dict[str, Any]:
    """``notes`` and nothing else. The number, the date and the amount are what the ministry issued.

    A portal that lets an officer edit the three facts of the instrument is a portal whose register
    cannot be audited; see ``SanctionOrderUpdate``.
    """
    row = await _load(sanction_id)
    updated = await db.sanctionorder.update(
        where={"id": row.id},
        data={"notes": payload.notes},
        include=SANCTION_INCLUDE,
    )
    return await sanction_orders.sanction_payload(updated)


@router.post("/{sanction_id}/credential-link")
async def reissue_credential_link(
    sanction_id: str,
    officer: Any = Depends(require_sanction_recorder),
) -> dict[str, Any]:
    """Mint another first-password link for the designer this order names.

    THE ANSWER TO "THE OFFICER CLOSED THE PANEL WITHOUT COPYING IT". The link is shown once and the
    table keeps only a digest, so there is no way to show the old one again; this issues a new one,
    which is also what silently invalidates nothing — an outstanding link stays valid until it is
    used or withdrawn.

    ⚠ **THIS REGISTER'S OWN ACCOUNTS ONLY.** 422 when the order's ``accountCreated`` is false, and
    422 when the named account now ranks at or above the officer asking. Without those two this was
    an unprivileged second door onto ``POST /api/auth/password-links`` (which is
    ``Depends(require_admin)``): an Assistant Director could record an order naming an existing
    MASTER_ADMIN — every phase-0 refusal passes for that address — and then take the account over
    from here. The whole argument, and why the rank test does not belong in ``cannot_run_reason``,
    is in :func:`app.services.sanction_orders.reissue_credential_link`.

    429 when the 4-per-hour-per-designer budget is spent, with ``retry-after`` in seconds. The
    sentence is worded for an officer rather than for an admin, which is why it is spelled in
    ``services/sanction_orders`` rather than imported from ``routes/auth``.
    """
    row = await _load(sanction_id)
    return await sanction_orders.reissue_credential_link(row, officer)


@router.post("/{sanction_id}/credential-link/revoke")
async def revoke_credential_link(
    sanction_id: str,
    token_id: str = Query(..., alias="tokenId"),
    _: Any = Depends(require_sanction_recorder),
) -> dict[str, Any]:
    """Withdraw an outstanding link. Idempotent, deliberately.

    An officer pressing this twice has not made a mistake, and answering the second press with an
    error would suggest they had — the rule ``credential_links.revoke_link`` already states.
    """
    from app.services import credential_links

    await _load(sanction_id)
    await credential_links.revoke_link(token_id)
    return {"ok": True}


# --------------------------------------------------------------------------------------
# Reading
# --------------------------------------------------------------------------------------


# ⚠ EVERY LITERAL GET PATH ON THIS PREFIX IS DECLARED BEFORE ``/{sanction_id}``. FastAPI matches
# in DECLARATION ORDER, and this repository has already lost an endpoint to that once (see the
# note above ``design_workshop_viewers`` in app/api/router.py). ``GET /sanction-orders/{id}``
# matches ``/awaiting-count``, ``/designers`` and ``/pro-forma.xlsx`` perfectly well and would
# answer 404 "Sanction order not found" to all three — a badge that silently reads zero for ever,
# a picker with no names in it, and a download button that does nothing, on a server where every
# one of them exists.
#
# ``test_the_awaiting_count_is_declared_before_the_id_route`` asserts this PER METHOD and for
# every literal path, which is what keeps it true when somebody adds the next one.
@router.get("/awaiting-count")
async def awaiting_sanction_count(
    _: Any = Depends(require_sanction_recorder),
) -> dict[str, Any]:
    """**THE NOTIFICATION**, and it points at the officer rather than at the designer.

    Modelled byte-for-byte on ``GET /access/roster/pending-count``: the surfaces that show this — a
    nav badge, a hub tile — want the number without the page of rows, and a screen that had to fetch
    fifty sanction orders to render a badge would either not render it or fetch them on every paint.

    "Awaiting" means the designer has not yet filled stage 1's thirteen required fields. That is the
    only thing standing between a sanctioned workshop and real work, and an officer who can see
    which sanctions are stalled does not need a server refusal that would cost a designer their
    fieldwork — see the argument under ``enforce_required`` about why a hard gate on the save path
    destroys work done offline.
    """
    return {"awaiting": await sanction_orders.awaiting_count()}


@router.get("")
async def list_sanction_orders(
    page: int = Query(1, ge=1),
    pageSize: int = Query(50, ge=1, le=200),
    search: str | None = None,
    mine: bool = Query(default=False),
    dateFrom: datetime | None = Query(default=None),
    dateTo: datetime | None = Query(default=None),
    sort: str | None = Query(default=None),
    dir: str | None = Query(default=None),
    officer: Any = Depends(require_sanction_recorder),
) -> dict[str, Any]:
    """The register, filtered, sorted and paged.

    **EVERY NARROWING IS A QUERY PARAMETER AND NOTHING IS FILTERED ON A CLIENT.** A page that ran
    ``data.items.filter(...)`` would report a page that is the right SIZE while having silently
    dropped whatever the extra clause excluded — a complete-looking list that is missing orders, on
    a screen whose entire job is to be complete. The same rule ``/admin/designers`` and
    ``/admin/access`` are built on.

    ``readiness`` IS NOT A QUERY PARAMETER AND THAT IS AN HONEST LIMITATION rather than an
    oversight. Readiness is scored from stage-1 JSON by ``stage_completeness`` — there is no column
    to filter on, and inventing one would be a second arithmetic beside the registry's. The badge
    from ``/awaiting-count`` answers "how many", and the per-row pill answers "which"; filtering the
    QUERY by it would need a cached column written by the stage save, which is a separate change.

    ``search`` covers the number (in either spelling), the designer's name and the designer's
    address, because those are the three things printed on the paper an officer is holding.
    """
    clean_page, clean_size, skip = normalize_pagination(page, pageSize)

    where: dict[str, Any] = {}
    if mine:
        where["createdById"] = getattr(officer, "id", None)
    if search:
        term = search.strip()
        if term:
            where["OR"] = [
                {"sanctionOrderNo": contains(term)},
                # THE NORMALISED KEY TOO, so an officer who types `SO-2026-42` finds the order
                # somebody else recorded as `SO/2026/42`. Without this clause the search box is
                # weaker than the duplicate check, and an officer would conclude the order is not
                # recorded and record it a second time — into a 409 they cannot explain.
                {"sanctionOrderKey": contains(sanction_orders.normalise_sanction_order_no(term))},
                {"designerEmail": contains(term)},
                {"designerUser": {"is": {"name": contains(term)}}},
            ]
    add_date_range(where, "sanctionOrderDate", dateFrom, dateTo)

    direction = "asc" if str(dir or "").lower() == "asc" else "desc"
    column = sort if sort in _SORTABLE else "sanctionOrderDate"
    # ``with_id_tiebreak`` AND NOT A BARE ``order=``. An ``ORDER BY`` with no unique column silently
    # repeats and skips rows across an offset-paged walk, because LIMIT/OFFSET re-runs the whole sort
    # for every page and Postgres may break a tie differently each time. The ties here are real: a
    # ministry issues a dozen orders under one date, so the DEFAULT sort alone has large tie groups.
    # This is spelled out rather than delegated to ``count_and_page`` only because this route needs
    # ``include=`` on the page, which that helper does not take.
    total, rows = await gather_reads(
        db.sanctionorder.count(where=where),
        db.sanctionorder.find_many(
            where=where,
            skip=skip,
            take=clean_size,
            order=with_id_tiebreak({column: direction}),
            include=SANCTION_INCLUDE,
        ),
    )
    items = [await sanction_orders.sanction_payload(row) for row in rows]
    return page_payload(items, total, clean_page, clean_size)


#: The columns this list may be ordered by. A closed set, because ``order={sort: dir}`` over a
#: caller-supplied string is an invitation to sort by a column that is not indexed — on a register
#: an officer opens every morning.
_SORTABLE = frozenset({"sanctionOrderDate", "sanctionAmount", "createdAt", "sanctionOrderNo"})


@router.get("/designers")
async def list_sanction_designers(
    search: str | None = Query(None, max_length=120),
    _: Any = Depends(require_sanction_recorder),
) -> dict[str, Any]:
    """The accounts an officer may name on a sanction order. **THE FIFTH DOOR, AND IT HAD TO EXIST.**

    ── WHY THE FOUR DOORS THAT ALREADY EXISTED COULD NOT BE USED ─────────────────────────────────

    Every one of them refuses an Assistant Director, which is the FLOOR of this feature's own gate:

      * ``GET /designers/roster``     — ``require_designer_roster_manager`` (admin access or above)
      * ``GET /designers/directory``  — the same
      * ``GET /design-workshops/eligible-viewers`` — ``require_admin``, the SET {ADMIN, MASTER_ADMIN}
      * ``GET /design-workshop-oversight/designers`` — ``require_workshop_assigner``,
        {MINISTRY_ADMIN, ADMIN, MASTER_ADMIN}

    ``can_record_sanction_orders`` is a rank floor at ASSISTANT_DIRECTOR (42), so ranks 42 and 45 —
    an Assistant Director and a Regional Director — could record a sanction order and reach no
    designer list at all. That is the exact trap ``PromoteDialog.tsx`` documents as the reason the
    annual plan's promote dialog once shipped with no designer picker: *"putting it here would give
    an administrator a picker that 403s, on the one screen built for them."*

    ── A FIFTH DOOR AND NOT A WIDENED GATE, WHICH IS THE PRECEDENT THIS REPOSITORY ALREADY SET ───

    ``list_assignable_designers`` says it in its own docstring: **two doors, one query, two
    payloads.** Widening ``can_manage_designer_roster`` "was the wrong fix, because that gate is what
    stands in front of the EMPANELMENT table and an account that could reach it could suspend a
    designer's sign-in". The same holds here, twice over:

      * **Never widen ``can_manage_designer_roster``.** An officer who could reach it could end an
        empanelment — the very decision this feature's own refusals exist to protect.
      * **Never widen ``require_workshop_assigner``** to reach this. ``OVERSIGHT_ASSIGNER_ROLES``
        excludes REGIONAL_DIRECTOR deliberately — *"the supervised must not choose the supervisor"* —
        and a REGIONAL_DIRECTOR who needs a designer list does not need the power to appoint the
        officer who monitors them.

    ── THE PAYLOAD IS FOUR KEYS AND THE ABSENCE OF THE REST IS THE POINT ─────────────────────────

    ``assignable_designers_payload``: ``id``, ``name``, ``email``, ``role``, and **no roster
    judgements** — no ``rosterActive``, no ``canSignIn``, no ``firstSeenAt``, no ``institution``.
    Whether a designer has a suspension on file is not an officer's business. The suspended are
    already gone before this runs, because ``workshop_capable_accounts`` folds the roster into the
    query's WHERE rather than filtering after the read — so this payload does not need a flag to be
    safe, and must not EXPLAIN one to be honest.

    ── ⚠ AND THE ROW SET IS NARROWER THAN THE OTHER DOORS', WHICH IS THE OTHER HALF OF THE SAME RULE

    ``include_admins=False``. ``workshop_capable_accounts`` admits ADMIN and MASTER_ADMIN
    UNCONDITIONALLY — they are never roster-gated, the same rule ``roster_allows`` applies at sign-in
    — so the default answer here would have been every empanelled designer **plus every privileged
    account in the installation**, each labelled with its role by the ``role`` key. Reasoning only
    about the four keys, as the paragraph above does, misses that entirely: the payload discipline
    was right and the SET was wrong. The other two doors keep the admin arm because their own gates
    are already admin-adjacent; this one is the first time the list is reachable below rank 48, and
    an Assistant Director typing one letter would have been handed the complete privileged-account
    directory — and then, before 2026-09-16, could tick a MASTER_ADMIN row, record an order naming
    them and re-issue their sign-in link. Both halves are closed now; this is the half that keeps the
    list honest about what a sanction order is FOR, which is the designer who does the work.

    ``truncated`` IS THE SERVER'S OWN WORD FOR "THIS IS NOT THE WHOLE SET" and the client draws a
    notice from it. An empty list with no explanation is this repository's most repeated bug class.

    ── THE SAME SHAPE THE PICKER'S OTHER TWO DOORS ANSWER ───────────────────────────────────────

    ``{users, truncated}``, four keys per row, ``search`` capped at 120 — identical to
    ``GET /design-workshop-oversight/designers`` and structurally identical to
    ``GET /design-workshops/eligible-viewers``, because ONE control reads all three
    (``WorkshopDesignerPicker``'s ``fetchEligible``) and a fourth shape would have meant a fourth
    control. What the three doors do NOT share is the eligibility — that is each gate's business —
    and this one's is the widest read with the narrowest payload.
    """
    users = await designers.workshop_capable_accounts(
        search=search, include_suspended=False, include_admins=False
    )
    return {
        "users": designers.assignable_designers_payload(users),
        "truncated": len(users) >= designers.DIRECTORY_TAKE,
    }


@router.get("/pro-forma.xlsx")
async def download_sanction_pro_forma(
    _: Any = Depends(require_sanction_recorder),
) -> Response:
    """The blank workbook an office types its orders into.

    **GATED LIKE EVERY OTHER ARM, even though it contains no data at all.** The reason is the same
    one the module docstring gives for gating the read: this prefix's audience is a decision, and a
    door that answered anybody would be one more thing to reason about the day somebody asks why the
    register is browsable. It costs an officer nothing — they are already signed in — and it means
    ``test_every_route_on_the_prefix_carries_the_sanction_gate_read_included`` stays a sweep over
    ALL routes rather than a sweep with an exception in it.

    EMPTY UNDER THE HEADINGS, and the worked example is on the instructions sheet where it cannot be
    imported — see :func:`build_sanction_pro_forma`. A seeded row here would be a sanction order
    called "EXAMPLE" on a register that has no delete.

    IT IS NOT AN EXPORT AND MUST NOT GROW INTO ONE. There is no sanction export in this release, and
    the annual plan's round-trip hazard (a filtered export re-uploaded destructively) **does not
    transfer** — there is no ``withdrawAbsent`` equivalent because there is no delete on the
    register. If an export is built later, say that in its own header so nobody copies the filter
    machinery for a round trip that cannot lose anything.
    """
    return xlsx_response(build_sanction_pro_forma(), PRO_FORMA_FILENAME)


@router.get("/{sanction_id}")
async def get_sanction_order(
    sanction_id: str,
    _: Any = Depends(require_sanction_recorder),
) -> dict[str, Any]:
    """One order. 404 and never 403 for an id that does not exist.

    Everyone who reaches this route may read every row on it — the gate is the prefix, not the row —
    so there is no "you may not see this one" to express, and a 403 here would be a lie about a row
    that simply is not there.
    """
    return await sanction_orders.sanction_payload(await _load(sanction_id))


async def _load(sanction_id: str) -> Any:
    row = await db.sanctionorder.find_unique(where={"id": sanction_id}, include=SANCTION_INCLUDE)
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Sanction order not found"
        )
    return row


__all__ = ["require_sanction_recorder", "router"]
