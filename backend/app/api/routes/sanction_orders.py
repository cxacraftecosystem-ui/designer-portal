"""``/api/sanction-orders`` — the ministry's sanction register.

══ WHAT AN OFFICER DOES HERE, AND WHAT IT COSTS ═══════════════════════════════════════════════

Five fields — order number, order date, sanctioned amount, the designer's name and their Gmail
address — and recording them writes SEVEN rows in one transaction: an allow-list admission, an
empanelment, an account (only where the mailbox has none), a designer profile, a workshop, the
designer's viewer row on it, and the sanction order itself. The argument for the order of those
writes, the transaction boundary and every refusal that happens before the first of them is in
``app/services/sanction_orders.py``; this module is the doors.

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

from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import get_current_user
from app.schemas.sanction_orders import SanctionOrderCreate, SanctionOrderUpdate
from app.services import sanction_orders
from app.services.concurrency import gather_reads
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import add_date_range, contains, with_id_tiebreak
from app.services.sanction_orders import (
    SANCTION_INCLUDE,
    SANCTION_ORDER_REFUSAL,
    can_record_sanction_orders,
)

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


# DECLARED BEFORE ``/{sanction_id}``. FastAPI matches in declaration order, and this repository has
# already lost an endpoint to that once (see the note above ``design_workshop_viewers`` in
# app/api/router.py). Putting the literal path first is what keeps that true when somebody adds the
# next one.
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
