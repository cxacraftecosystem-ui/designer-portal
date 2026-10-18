"""WHO MAY REVIEW WHOSE RECORDS, as a query: the review queue's predicate, shared.

The record-review queue (``api/routes/review.py``) and the Monday summary
(``services/weekly_digest.py``) both count "records waiting for this reviewer". They must give the
same number, so the ladder lives here once and both import it. Moved out of the route module
unchanged; the documentation portal has the same module by name.
"""

from __future__ import annotations

from typing import Any

from app.core.db import db
from app.core.deps import ROLE_RANK, is_master_admin, role_rank
from app.services.concurrency import gather_reads

#: The Prisma delegates the queue walks, by name. ``review._PENDING_SOURCES`` holds the same six
#: (with their label fields); ``tests/test_weekly_digest_unit.py`` holds the two lists together.
PENDING_MODELS: tuple[str, ...] = (
    "artisan",
    "workshop",
    "productdocumentation",
    "tooldocumentation",
    "process",
    "questionnaireinterview",
)


def reviewable_creator_roles(reviewer: Any) -> list[str] | None:
    """The creator roles this reviewer may act on, or None for "everyone" (the master admin).

    The peer-review ladder as DATA, so it can go into the query. The queue used to read the newest
    200 pending rows per record type and drop the unreviewable ones in Python afterwards: a
    professor whose researchers' submissions all sat behind 200 newer admin-created rows opened an
    EMPTY queue, and ``total`` counted rows they had no authority to touch. Filtering in the
    database makes the take apply to rows the caller can actually review.

    Same comparison ``can_review_record`` makes — ranks STRICTLY below the reviewer — expressed
    against the creator's role column. The two cannot drift apart over a missing role either: every
    reviewable model has a required ``createdById``, and ``UserRole`` is NOT NULL with a default, so
    the "no creator role on file" case ``can_review_record`` defends against does not exist in the
    rows this query walks.
    """
    if is_master_admin(reviewer):
        return None
    rank = role_rank(reviewer)
    return [role for role, value in ROLE_RANK.items() if value < rank]


def pending_where(reviewer: Any) -> dict[str, Any] | None:
    """The queue's ``where`` for this reviewer, or ``None`` when nobody sits beneath them."""
    roles = reviewable_creator_roles(reviewer)
    if roles is not None and not roles:
        return None
    where: dict[str, Any] = {"status": "PENDING"}
    if roles is not None:
        where["createdBy"] = {"is": {"role": {"in": roles}}}
    return where


async def pending_count(reviewer: Any, *, extra: dict[str, Any] | None = None) -> int:
    """How many records wait for this reviewer, across the six types. ``extra`` AND-narrows it."""
    where = pending_where(reviewer)
    if where is None:
        return 0
    if extra:
        where = {"AND": [where, extra]}
    counts = await gather_reads(*(getattr(db, name).count(where=where) for name in PENDING_MODELS))
    return int(sum(counts))
