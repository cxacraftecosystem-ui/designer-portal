"""THE OTHER HALF OF THE AUTHOR-AND-APPROVER RULE, WHICH THE 2026-09-14 WIDENING MADE NECESSARY.

``sanction_orders._refuse_if_the_officer_named_themselves`` has always stopped an officer NAMING a
designer they control. Its docstring used to rest on a second, structural fact, and that fact is
what this file exists because of:

    "every tier that clears this floor (42, 45, 48) is OUTSIDE ``DESIGN_WORKSHOP_ROLES``, so none of
    them can write a single stage of a workshop"

MINISTRY_ADMIN, REGIONAL_DIRECTOR and ASSISTANT_DIRECTOR joined ``DESIGN_WORKSHOP_ROLES`` on the
owner's ruling, so that sentence stopped being true and the escalation stopped needing a puppet:

  1. ``can_record_sanction_orders`` is a rank floor at ASSISTANT_DIRECTOR (42) -- all three tiers.
  2. Step 1.5 of the create stamps ``createdById = officer.id`` ON THE WORKSHOP. That is deliberate
     and correct; the officer is who brought it into existence.
  3. The CREATOR arm of ``load_workshop_or_404`` admits the creator with NO ROLE TEST AT ALL.
  4. ``_require_designer`` then used to refuse them on all eighteen write routes. It no longer does.

Nothing in that chain needs a second mailbox, a credential link, or an address comparison, so NO
PART OF THE EXISTING REFUSAL FIRES ON IT. The officer records the instrument, authors the whole
fortnight of fieldwork in the workshop it opened, and reviews and approves it as themselves --
``can_review_record`` is strictly-below and ``can_edit_others_record`` is the PROFESSOR floor, and
42 clears both.

NO DATABASE, for the reason ``test_sanction_order_designer_eligibility.py`` gives: the refusal is a
predicate and its ORDER relative to the work is the thing that can silently regress. The one arm
that does read a row is exercised against a fake client, so the whole file runs on any machine.

WHAT WOULD MAKE THIS FILE GO RED, AND EACH IS A REAL REGRESSION AND NOT A RENAME:
  * the guard dropped from ``load_workshop_or_404`` (section 1);
  * the guard demoted to fire on READS as well, which would lock an officer out of the record they
    authorised -- a worse rule than the one it replaces (section 2);
  * the guard widened past the one workshop the officer's own order opened (sections 3 and 4);
  * the guard narrowed to a role test, so an ADMIN who records an order walks through it (section 5).
"""

from __future__ import annotations

import inspect
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.services import design_workshops

GUARD = design_workshops._refuse_if_the_officer_is_authoring_what_they_sanctioned

OFFICER = "officer-1"
DESIGNER = "designer-1"
WORKSHOP = "workshop-1"


def _officer(role: str = "ASSISTANT_DIRECTOR", user_id: str = OFFICER) -> Any:
    return SimpleNamespace(id=user_id, role=role, email="officer@example.gov")


def _workshop(created_by: str | None = OFFICER) -> Any:
    return SimpleNamespace(id=WORKSHOP, createdById=created_by, deletedAt=None)


def _order(created_by: str = OFFICER) -> Any:
    return SimpleNamespace(id="order-1", designWorkshopId=WORKSHOP, createdById=created_by)


class _FakeSanctionOrders:
    """Stands in for ``db.sanctionorder``, and COUNTS ITS READS.

    The count is asserted rather than ignored: the guard's whole cost argument is that an ordinary
    designer saving an ordinary stage pays nothing, and a guard that queried first and decided
    afterwards would pass every behavioural test in this file while putting a round trip on the
    hottest write path in the product.
    """

    def __init__(self, row: Any) -> None:
        self.row = row
        self.calls: list[dict[str, Any]] = []

    async def find_unique(self, **kwargs: Any) -> Any:
        self.calls.append(kwargs)
        return self.row


@pytest.fixture
def db_with_order(monkeypatch):
    def _install(order: Any) -> _FakeSanctionOrders:
        fake = _FakeSanctionOrders(order)
        monkeypatch.setattr(
            design_workshops, "db", SimpleNamespace(sanctionorder=fake), raising=True
        )
        return fake

    return _install


async def _raises_403(*args: Any, **kwargs: Any) -> HTTPException:
    with pytest.raises(HTTPException) as caught:
        await GUARD(*args, **kwargs)
    assert caught.value.status_code == 403
    return caught.value


# ==============================================================================================
# 1. IT IS ACTUALLY WIRED IN, which no behavioural test below can prove.
# ==============================================================================================


def test_load_workshop_or_404_calls_the_guard() -> None:
    """A guard nothing calls is a guard that does not exist.

    Source-reading on purpose: every write route reaches this helper rather than the guard, so the
    only thing that connects the two is one line, and deleting it passes every other test here.
    """
    source = inspect.getsource(design_workshops.load_workshop_or_404)
    assert "_refuse_if_the_officer_is_authoring_what_they_sanctioned" in source, (
        "load_workshop_or_404 no longer calls the guard. The officer who recorded a sanction order "
        "is the workshop's createdById and the creator arm admits them with no role test, so "
        "without this call they can author the fieldwork they authorised."
    )


def test_the_guard_runs_before_the_deleted_check_reports_a_conflict() -> None:
    """Ordering, because both arms answer on a WRITE and only one is about authority.

    If the 409 ran first, an officer editing a soft-deleted workshop they sanctioned would be told
    to restore it -- an instruction to do more of the thing they may not do at all.
    """
    source = inspect.getsource(design_workshops.load_workshop_or_404)
    assert source.index("_refuse_if_the_officer_is_authoring_what_they_sanctioned") < source.index(
        "record.deletedAt is not None"
    ), "the sanction guard must be answered before the soft-delete conflict, not after it"


# ==============================================================================================
# 2. READS ARE UNTOUCHED.
# ==============================================================================================


async def test_the_officer_may_still_read_the_workshop_they_sanctioned(db_with_order) -> None:
    """The point of the rule is that they may not AUTHOR it.

    Authorising work you cannot then inspect would be a worse rule than the one this replaces, and
    it is the failure mode a role-shaped fix would have shipped.
    """
    fake = db_with_order(_order())
    await GUARD(_workshop(), _officer(), for_edit=False)
    assert fake.calls == [], "a read must not pay for the sanction lookup at all"


# ==============================================================================================
# 3. THE WRITE IS REFUSED -- the assertion this file is named for.
# ==============================================================================================


@pytest.mark.parametrize("role", ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "MINISTRY_ADMIN"])
async def test_every_tier_that_can_record_an_order_is_refused_the_write(
    role: str, db_with_order
) -> None:
    """All three, not just the floor.

    A guard written against ASSISTANT_DIRECTOR alone would let the two tiers ABOVE it through,
    which is the direction seniority makes easiest to overlook.
    """
    db_with_order(_order())
    problem = await _raises_403(_workshop(), _officer(role), for_edit=True)
    assert "author" in problem.detail.lower()


async def test_the_refusal_names_the_reason_rather_than_hiding_the_workshop(db_with_order) -> None:
    """403 and not the 404 every other refusal in this helper answers with.

    The caller has already been admitted as the creator and is looking at the workshop on their own
    screen; hiding it now is a lie they can disprove by pressing back. The reason is also actionable
    -- there is a named designer on the order to hand the work to.
    """
    db_with_order(_order())
    problem = await _raises_403(_workshop(), _officer(), for_edit=True)
    assert "designer" in problem.detail.lower()
    assert "read" in problem.detail.lower(), "the sentence must say what they CAN still do"


# ==============================================================================================
# 4. AND IT IS SCOPED TO THAT ONE WORKSHOP -- the half that keeps the widening worth having.
# ==============================================================================================


async def test_a_workshop_somebody_else_created_is_untouched(db_with_order) -> None:
    """The capability the owner asked for.

    A directorate officer authoring an ordinary workshop is the whole point of the widening and
    must not be collateral.
    """
    fake = db_with_order(_order())
    await GUARD(_workshop(created_by="somebody-else"), _officer(), for_edit=True)
    assert fake.calls == [], "not being the creator must settle it without a query"


async def test_an_order_recorded_by_a_different_officer_is_untouched(db_with_order) -> None:
    """Two officers, one workshop: A records the order, and somehow B is the workshop's creator.

    B is not standing on both sides of anything, so B is not refused.
    """
    db_with_order(_order(created_by="another-officer"))
    await GUARD(_workshop(), _officer(), for_edit=True)


async def test_a_workshop_with_no_sanction_order_is_untouched(db_with_order) -> None:
    """Most workshops.

    An admin-created workshop has no order at all, and an officer who somehow created one by hand
    is not the subject of this rule.
    """
    db_with_order(None)
    await GUARD(_workshop(), _officer(), for_edit=True)


async def test_the_lookup_is_keyed_on_the_workshop_and_not_on_the_officer(db_with_order) -> None:
    """``SanctionOrder.designWorkshopId`` is ``@unique``, which is what makes one indexed read
    enough. Keying on the officer would return their FIRST order rather than THIS workshop's."""
    fake = db_with_order(_order())
    await _raises_403(_workshop(), _officer(), for_edit=True)
    assert fake.calls == [{"where": {"designWorkshopId": WORKSHOP}}]


# ==============================================================================================
# 5. IT IS A ROW TEST, NOT A ROLE TEST.
# ==============================================================================================


async def test_a_designer_who_created_their_own_workshop_pays_nothing(db_with_order) -> None:
    """DESIGNER is below the sanction floor, so a designer can never be the officer on an order.

    Settling that on a role string already in memory is what keeps the hottest write path in the
    product free of this query.
    """
    fake = db_with_order(_order())
    await GUARD(_workshop(created_by=DESIGNER), _officer("DESIGNER", DESIGNER), for_edit=True)
    assert fake.calls == [], "an ordinary stage save must not pay for the sanction lookup"


async def test_an_admin_who_records_an_order_is_refused_by_the_same_clause(db_with_order) -> None:
    """ADMIN and MASTER_ADMIN clear the floor too.

    The rule is about the ROW -- you recorded the instrument -- rather than about rank. An exemption
    here would make the refusal a role test wearing a row test's clothes, and admins are exactly who
    would use it.
    """
    db_with_order(_order())
    await _raises_403(_workshop(), _officer("ADMIN"), for_edit=True)
