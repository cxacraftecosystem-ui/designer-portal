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



def test_load_workshop_or_404_calls_the_guard() -> None:
    source = inspect.getsource(design_workshops.load_workshop_or_404)
    assert "_refuse_if_the_officer_is_authoring_what_they_sanctioned" in source, (
        "load_workshop_or_404 no longer calls the guard. The officer who recorded a sanction order "
        "is the workshop's createdById and the creator arm admits them with no role test, so "
        "without this call they can author the fieldwork they authorised."
    )


def test_the_guard_runs_before_the_deleted_check_reports_a_conflict() -> None:
    source = inspect.getsource(design_workshops.load_workshop_or_404)
    assert source.index("_refuse_if_the_officer_is_authoring_what_they_sanctioned") < source.index(
        "record.deletedAt is not None"
    ), "the sanction guard must be answered before the soft-delete conflict, not after it"



async def test_the_officer_may_still_read_the_workshop_they_sanctioned(db_with_order) -> None:
    fake = db_with_order(_order())
    await GUARD(_workshop(), _officer(), for_edit=False)
    assert fake.calls == [], "a read must not pay for the sanction lookup at all"



@pytest.mark.parametrize("role", ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "MINISTRY_ADMIN"])
async def test_every_tier_that_can_record_an_order_is_refused_the_write(
    role: str, db_with_order
) -> None:
    db_with_order(_order())
    problem = await _raises_403(_workshop(), _officer(role), for_edit=True)
    assert "author" in problem.detail.lower()


async def test_the_refusal_names_the_reason_rather_than_hiding_the_workshop(db_with_order) -> None:
    db_with_order(_order())
    problem = await _raises_403(_workshop(), _officer(), for_edit=True)
    assert "designer" in problem.detail.lower()
    assert "read" in problem.detail.lower(), "the sentence must say what they CAN still do"



async def test_a_workshop_somebody_else_created_is_untouched(db_with_order) -> None:
    fake = db_with_order(_order())
    await GUARD(_workshop(created_by="somebody-else"), _officer(), for_edit=True)
    assert fake.calls == [], "not being the creator must settle it without a query"


async def test_an_order_recorded_by_a_different_officer_is_untouched(db_with_order) -> None:
    db_with_order(_order(created_by="another-officer"))
    await GUARD(_workshop(), _officer(), for_edit=True)


async def test_a_workshop_with_no_sanction_order_is_untouched(db_with_order) -> None:
    db_with_order(None)
    await GUARD(_workshop(), _officer(), for_edit=True)


async def test_the_lookup_is_keyed_on_the_workshop_and_not_on_the_officer(db_with_order) -> None:
    fake = db_with_order(_order())
    await _raises_403(_workshop(), _officer(), for_edit=True)
    assert fake.calls == [{"where": {"designWorkshopId": WORKSHOP}}]



async def test_a_designer_who_created_their_own_workshop_pays_nothing(db_with_order) -> None:
    fake = db_with_order(_order())
    await GUARD(_workshop(created_by=DESIGNER), _officer("DESIGNER", DESIGNER), for_edit=True)
    assert fake.calls == [], "an ordinary stage save must not pay for the sanction lookup"


async def test_an_admin_who_records_an_order_is_refused_by_the_same_clause(db_with_order) -> None:
    db_with_order(_order())
    await _raises_403(_workshop(), _officer("ADMIN"), for_edit=True)
