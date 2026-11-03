from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.api.routes import design_workshops as routes

NON_ADMIN_TIERS = (
    "CROWDSOURCE_VOLUNTEER",
    "FIELD_CONTRIBUTOR",
    "RESEARCHER",
    "DESIGNER",
    "INSPECTOR",
    "PROFESSOR",
)
ADMIN_TIERS = ("ADMIN", "MASTER_ADMIN")

DELETED_AT = datetime(2026, 8, 26, 9, 30, tzinfo=UTC)


def _user(role: str) -> SimpleNamespace:
    return SimpleNamespace(id=f"u-{role.lower()}", email=f"{role.lower()}@example.test", role=role)


def _row(rid: str, *, deleted_at: datetime | None = None, deleted_by: str | None = None) -> Any:
    return SimpleNamespace(
        id=rid,
        title=f"Workshop {rid}",
        templateId="default",
        status="DRAFT",
        workshopCode=None,
        workshopKind=None,
        scheme=None,
        craftName=None,
        clusterName=None,
        state=None,
        district=None,
        venue=None,
        startDate=None,
        endDate=None,
        designerName=None,
        implementingAgency=None,
        sponsor=None,
        notes=None,
        workshopId=None,
        createdById="u-admin",
        createdAt=None,
        updatedAt=None,
        deletedAt=deleted_at,
        deletedById=deleted_by,
        dictationConsent="NOT_RECORDED",
        dictationConsentAt=None,
        dictationConsentById=None,
        reviewNotes=None,
        reviewedById=None,
        reviewedAt=None,
        submissionRound=0,
    )


class _Table:
    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows
        self.finds: list[dict[str, Any]] = []
        self.counts: list[dict[str, Any]] = []

    async def count(self, where: dict[str, Any]) -> int:
        self.counts.append(where)
        return len(self.rows)

    async def find_many(self, **kwargs: Any) -> list[Any]:
        self.finds.append(kwargs)
        take = kwargs.get("take")
        skip = kwargs.get("skip") or 0
        return self.rows[skip : skip + take] if take else self.rows


class _Db:
    def __init__(self, workshops: list[Any], accounts: list[Any] | None = None) -> None:
        self.designworkshop = _Table(workshops)
        self.user = _Table(accounts or [])


@pytest.fixture
def stub(monkeypatch: pytest.MonkeyPatch):
    def install(workshops: list[Any], accounts: list[Any] | None = None) -> _Db:
        fake = _Db(workshops, accounts)
        monkeypatch.setattr(routes, "db", fake)
        return fake

    return install


async def _list(user: SimpleNamespace, **kwargs: Any) -> dict[str, Any]:
    return await routes.list_design_workshops(
        page=kwargs.pop("page", 1), pageSize=kwargs.pop("pageSize", 20), current_user=user, **kwargs
    )



@pytest.mark.parametrize("role", NON_ADMIN_TIERS)
@pytest.mark.parametrize("flag", ["deletedOnly", "includeDeleted"])
async def test_only_an_admin_may_ask_for_deleted_workshops(stub, role, flag):
    fake = stub([_row("w1", deleted_at=DELETED_AT)])
    with pytest.raises(HTTPException) as raised:
        await _list(_user(role), **{flag: True})
    assert raised.value.status_code == 403
    assert raised.value.detail == "Admin access required"
    assert fake.designworkshop.counts == [] and fake.designworkshop.finds == [], (
        "the trash was refused only after it had been read out of the database"
    )


@pytest.mark.parametrize("role", ADMIN_TIERS)
async def test_both_admin_tiers_reach_the_trash(stub, role):
    stub([_row("w1", deleted_at=DELETED_AT)])
    payload = await _list(_user(role), deletedOnly=True)
    assert [item["id"] for item in payload["items"]] == ["w1"]


async def test_a_designers_ordinary_list_is_untouched_by_the_new_parameters(stub):
    fake = stub([_row("w1")])
    await _list(_user("DESIGNER"))
    where = fake.designworkshop.finds[0]["where"]
    assert where["deletedAt"] is None
    assert where["AND"] == [{"OR": [{"createdById": "u-designer"}, {"viewers": {"some": {"userId": "u-designer"}}}]}]



async def test_the_default_list_still_hides_deleted_rows(stub):
    fake = stub([_row("w1")])
    await _list(_user("ADMIN"))
    assert fake.designworkshop.finds[0]["where"]["deletedAt"] is None


async def test_deleted_only_asks_for_the_deleted_rows_and_nothing_else(stub):
    fake = stub([_row("w1", deleted_at=DELETED_AT)])
    await _list(_user("ADMIN"), deletedOnly=True)
    assert fake.designworkshop.finds[0]["where"]["deletedAt"] == {"not": None}
    assert fake.designworkshop.counts[0]["deletedAt"] == {"not": None}, (
        "the count and the page were taken under different filters, so the total counts rows the "
        "list cannot contain"
    )


async def test_include_deleted_drops_the_filter_rather_than_inverting_it(stub):
    fake = stub([_row("w1"), _row("w2", deleted_at=DELETED_AT)])
    await _list(_user("ADMIN"), includeDeleted=True)
    assert "deletedAt" not in fake.designworkshop.finds[0]["where"]


async def test_deleted_only_wins_when_a_caller_sends_both(stub):
    fake = stub([_row("w1", deleted_at=DELETED_AT)])
    await _list(_user("ADMIN"), includeDeleted=True, deletedOnly=True)
    assert fake.designworkshop.finds[0]["where"]["deletedAt"] == {"not": None}


async def test_the_trash_is_ordered_by_when_each_row_went(stub):
    fake = stub([_row("w1", deleted_at=DELETED_AT)])
    await _list(_user("ADMIN"), deletedOnly=True)
    assert fake.designworkshop.finds[0]["order"] == [{"deletedAt": "desc"}, {"id": "desc"}]

    fake = stub([_row("w1")])
    await _list(_user("ADMIN"))
    assert fake.designworkshop.finds[0]["order"] == [{"createdAt": "desc"}, {"id": "desc"}]


async def test_the_other_filters_still_compose_with_the_trash(stub):
    fake = stub([_row("w1", deleted_at=DELETED_AT)])
    await _list(_user("ADMIN"), deletedOnly=True, search="ikat")
    where = fake.designworkshop.finds[0]["where"]
    assert where["deletedAt"] == {"not": None}
    assert [next(iter(clause)) for clause in where["OR"]] == [
        "title",
        "craftName",
        "clusterName",
        "workshopCode",
    ]



async def test_the_trash_names_who_deleted_each_row_in_one_query(stub):
    fake = stub(
        [
            _row("w1", deleted_at=DELETED_AT, deleted_by="u-priya"),
            _row("w2", deleted_at=DELETED_AT, deleted_by="u-ravi"),
        ],
        accounts=[
            SimpleNamespace(id="u-priya", name="Priya"),
            SimpleNamespace(id="u-ravi", name="Ravi"),
        ],
    )
    payload = await _list(_user("ADMIN"), deletedOnly=True)
    assert len(fake.user.finds) == 1
    assert fake.user.finds[0]["where"] == {"id": {"in": ["u-priya", "u-ravi"]}}
    assert [(i["deletedById"], i["deletedByName"]) for i in payload["items"]] == [
        ("u-priya", "Priya"),
        ("u-ravi", "Ravi"),
    ]


async def test_a_deleter_whose_account_is_gone_keeps_the_id_and_gets_no_name(stub):
    fake = stub([_row("w1", deleted_at=DELETED_AT, deleted_by="u-departed")], accounts=[])
    payload = await _list(_user("ADMIN"), deletedOnly=True)
    assert len(fake.user.finds) == 1
    assert payload["items"][0]["deletedById"] == "u-departed"
    assert payload["items"][0]["deletedByName"] is None


async def test_a_row_deleted_by_nobody_costs_no_account_query(stub):
    fake = stub([_row("w1", deleted_at=DELETED_AT)])
    payload = await _list(_user("ADMIN"), deletedOnly=True)
    assert fake.user.finds == []
    assert payload["items"][0] == {**payload["items"][0], "deletedById": None, "deletedByName": None}


async def test_the_ordinary_list_carries_no_deletion_pointer_at_all(stub):
    fake = stub([_row("w1")])
    payload = await _list(_user("ADMIN"))
    assert fake.user.finds == []
    assert "deletedById" not in payload["items"][0]
    assert "deletedByName" not in payload["items"][0]



async def test_the_total_counts_the_whole_trash_and_not_the_page(stub):
    fake = stub([_row(f"w{n}", deleted_at=DELETED_AT) for n in range(5)])
    payload = await _list(_user("ADMIN"), deletedOnly=True, pageSize=2)
    assert len(payload["items"]) == 2
    assert payload["total"] == 5
    assert payload["pages"] == 3
    assert fake.designworkshop.finds[0]["take"] == 2


async def test_an_empty_trash_is_a_zero_and_not_an_absence(stub):
    stub([])
    payload = await _list(_user("ADMIN"), deletedOnly=True)
    assert payload == {"items": [], "total": 0, "page": 1, "pageSize": 20, "pages": 0}
