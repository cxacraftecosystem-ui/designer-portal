"""The dashboard's counters.

WHAT CHANGED AND WHY. Every total here used to be filtered by the old row-visibility predicate, which
below Professor meant "rows you created". So a researcher's dashboard did not describe the repository
— it described their own upload history, under labels that said otherwise ("Artisans", "Media files"),
while a professor standing next to them read the true totals off the same labels. An account that had
uploaded nothing opened a dashboard of zeroes and a "recent submissions" list with nothing in it, which
is indistinguishable from an empty repository.

Reading is open now (see the banner above ``records.viewable_where``), so the totals are the
repository's totals — and because "how much have I contributed" is a genuinely useful second question
rather than a thing to delete, it is answered explicitly beside them in ``mine``. Two labelled
numbers, neither pretending to be the other.

Nothing here is a download. The counts are counts and the recent list is titles; taking the data out
still goes through /export and /data, which are gated as they always were.
"""

from typing import Any

from fastapi import APIRouter, Depends
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.deps import get_current_user
from app.services.concurrency import gather_reads
from app.services.records import own_rows_where, viewable_where

router = APIRouter(prefix="/dashboard", tags=["dashboard"])


def rows_to_recent(rows: list[Any], record_type: str) -> list[dict[str, Any]]:
    """Turn one table's newest rows into the shared shape the recent-activity list renders.

    ``createdByName`` is read off the included relation and the relation itself never enters the
    returned dict. That is deliberate: this module encodes with ``jsonable_encoder`` rather than
    ``records.public_encode``, so an embedded User object would ship its ``passwordHash``. Naming the
    one field wanted is what keeps that impossible instead of merely unlikely.
    """
    return [
        {
            "id": row.id,
            "type": record_type,
            "status": str(row.status),
            "createdAt": row.createdAt,
            "title": getattr(row, "name", None)
            or getattr(row, "title", None)
            or getattr(row, "productName", None)
            or getattr(row, "toolkitName", None),
            "place": getattr(row, "place", None),
            # Whose work this is. It was never shown while the list could only hold the reader's own
            # rows; now that the list is the repository's, an unattributed title is a title nobody
            # can follow up on.
            "createdByName": getattr(getattr(row, "createdBy", None), "name", None),
        }
        for row in rows
    ]


def _totals_by_status(groups: list[Any]) -> tuple[int, int]:
    """Fold one ``group_by(status)`` result into (every row, the PENDING ones).

    Prisma hands back a row per status actually present, so a table with nothing pending simply has
    no PENDING group — hence the ``.get`` rather than an index.
    """
    counts = {str(group["status"]): group["_count"]["_all"] for group in groups}
    return sum(counts.values()), counts.get("PENDING", 0)


@router.get("/stats")
async def dashboard_stats(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    # Both predicates are resolved before the wave rather than inside it: neither is a query (one is
    # empty, the other reads the caller's id), so awaiting them costs no round trip.
    view_where, media_view_where = await gather_reads(
        viewable_where(current_user),
        viewable_where(current_user, owner_field="uploadedById"),
    )
    own_where = await own_rows_where(current_user)
    own_media_where = await own_rows_where(current_user, owner_field="uploadedById")

    # This endpoint used to issue fourteen reads one after another: five totals, four "recent"
    # lists, five pending counts. MEASURED 2026-08 against the cross-region database of the time,
    # where a round trip cost ~750ms and the query itself a fraction of a millisecond: 10.1s of
    # almost pure waiting.
    #
    # THAT LINK IS GONE AND EVERY MILLISECOND FIGURE IN THIS COMMENT IS HISTORY (2026-09-03).
    # Production moved on 2026-09-02 to a database co-located with the API box, where a round trip
    # is one or two milliseconds rather than three quarters of a second — see the header of
    # ``services/concurrency.py``, which carries the same correction for the module this route
    # leans on. Nothing here has been re-timed since. WHAT SURVIVES IS THE COUNTING, which is the
    # only thing the change below ever altered: sixteen serial hops still cost sixteen times one
    # hop, and the ratio between "in series" and "together" is untouched. What moved is the size of
    # the prize — ten seconds of waiting became tens of milliseconds either way.
    #
    # Two things fix it, and the first matters more than the second. The four record tables are
    # counted TWICE each — once for the total, once for the PENDING subset — so a single
    # ``group_by`` over `status` answers both questions in one trip and removes four reads outright.
    # What remains is mutually independent, and goes out together. Measured against production data:
    # 10.1s sequential -> 7.8s from the grouping alone -> 950ms once gathered. THAT 950 ms WAS
    # MEASURED BEFORE THE `mine` HALF WAS ADDED and is not a figure for the wave as it now stands;
    # nothing has re-measured it since, and it should not be quoted as if it had.
    #
    # THE `mine` HALF IS A LATER WAVE, NOT MORE ROWS IN THE FIRST — and this comment used to claim
    # the opposite. The unpack below is SIXTEEN coroutines and ``gather_reads`` is bounded by
    # ``pool_width()`` (``concurrency.py``), which reads ``DATABASE_CONNECTION_LIMIT``. Sixteen
    # against that bound takes the SEMAPHORE branch, so a first batch goes out and the rest follow as
    # connections free up.
    #
    # AND IT IS FOUR WAVES, NOT TWO, ON THE DEPLOYMENT AS IT ACTUALLY RUNS. This paragraph said
    # "two waves, ~2 x 694 ms" against a limit of 10 — the default in ``core/config.py``. The
    # deployment sets the variable explicitly to 5 against a session pool of about fifteen slots
    # shared with the queue process, so sixteen reads at a width of five is FOUR waves. That is the
    # semaphore doing exactly its job and not a regression: at ~1-2ms a co-located round trip
    # (2026-09-02, ``services/concurrency.py``) four waves is single-digit milliseconds, where the
    # fourteen sequential reads this replaced were ten seconds on the old link. The 694ms is the old
    # link's number and is kept only as the measurement that motivated the gather.
    #
    # To narrow it to fewer waves, either shrink the unpack or lower the `mine` half onto a cache; do
    # NOT raise ``DATABASE_CONNECTION_LIMIT`` to fit it. That number is the deployment's budget
    # rather than this route's to spend — the 40 -> 10 cut recorded in ``core/config.py`` was
    # reverting exactly that mistake, and the deployment's 5 is a further deliberate narrowing.
    grouped = (db.artisan, db.workshop, db.productdocumentation, db.tooldocumentation)

    def pending(where: dict[str, Any]) -> dict[str, Any]:
        """``where`` narrowed to PENDING, without mutating the caller's dict."""
        return {**where, "status": "PENDING"}

    (
        artisan_groups,
        workshop_groups,
        product_groups,
        tool_groups,
        media,
        pending_interviews,
        recent_artisans,
        recent_workshops,
        recent_products,
        recent_tools,
        mine_artisan_groups,
        mine_workshop_groups,
        mine_product_groups,
        mine_tool_groups,
        mine_media,
        mine_pending_interviews,
    ) = await gather_reads(
        *(delegate.group_by(by=["status"], count=True, where=view_where) for delegate in grouped),
        db.mediafile.count(where=media_view_where),
        db.questionnaireinterview.count(where=pending(view_where)),
        *(
            delegate.find_many(
                where=view_where,
                take=5,
                order={"createdAt": "desc"},
                include={"createdBy": True},
            )
            for delegate in grouped
        ),
        *(delegate.group_by(by=["status"], count=True, where=own_where) for delegate in grouped),
        db.mediafile.count(where=own_media_where),
        db.questionnaireinterview.count(where=pending(own_where)),
    )

    artisans, pending_artisans = _totals_by_status(artisan_groups)
    workshops, pending_workshops = _totals_by_status(workshop_groups)
    products, pending_products = _totals_by_status(product_groups)
    tools, pending_tools = _totals_by_status(tool_groups)
    pending_submissions = (
        pending_artisans + pending_workshops + pending_products + pending_tools + pending_interviews
    )

    mine_artisans, mine_pending_artisans = _totals_by_status(mine_artisan_groups)
    mine_workshops, mine_pending_workshops = _totals_by_status(mine_workshop_groups)
    mine_products, mine_pending_products = _totals_by_status(mine_product_groups)
    mine_tools, mine_pending_tools = _totals_by_status(mine_tool_groups)

    recent = [
        *rows_to_recent(recent_artisans, "artisan"),
        *rows_to_recent(recent_workshops, "workshop"),
        *rows_to_recent(recent_products, "product"),
        *rows_to_recent(recent_tools, "tool"),
    ]
    recent = sorted(recent, key=lambda item: item["createdAt"], reverse=True)[:10]

    return jsonable_encoder(
        {
            # The repository. These are what the labels have always claimed to be.
            "totalArtisans": artisans,
            "totalWorkshops": workshops,
            "totalProductRecords": products,
            "totalToolRecords": tools,
            "totalMediaFiles": media,
            "pendingSubmissions": pending_submissions,
            "recentSubmissions": recent,
            # This account's own contribution, asked for explicitly. Same keys, so a client can
            # render the two rows from one template.
            "mine": {
                "totalArtisans": mine_artisans,
                "totalWorkshops": mine_workshops,
                "totalProductRecords": mine_products,
                "totalToolRecords": mine_tools,
                "totalMediaFiles": mine_media,
                "pendingSubmissions": (
                    mine_pending_artisans
                    + mine_pending_workshops
                    + mine_pending_products
                    + mine_pending_tools
                    + mine_pending_interviews
                ),
            },
        }
    )
