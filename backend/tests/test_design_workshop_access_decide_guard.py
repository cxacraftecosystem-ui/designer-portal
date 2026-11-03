import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

import app.services.design_workshop_access as access
import app.services.design_workshop_posts as posts

WORKSHOP_ID = "cmdecideguard00000000000a"
REQUEST_ID = "cmdecideguard00000000000r"
REQUESTER_ID = "cmdecideguard00000000000u"
ADMIN_ID = "cmdecideguard00000000000d"


@pytest.fixture(autouse=True)
def _the_deciding_admin_serves_on_nothing(monkeypatch: pytest.MonkeyPatch) -> None:
    async def _holds_no_post(workshop_id: str, user_ids: Any) -> dict[str, frozenset[str]]:
        return {}

    monkeypatch.setattr(posts, "supervisory_posts_among", _holds_no_post)


class _Requests:
    def __init__(self, row: Any) -> None:
        self.row = row
        self.updates: list[dict[str, Any]] = []
        self.includes: list[Any] = []

    async def find_unique(self, where: dict[str, Any], include: Any = None) -> Any:
        self.includes.append(include)
        return self.row if where.get("id") == self.row.id else None

    async def update(self, where: dict[str, Any], data: dict[str, Any]) -> Any:
        self.updates.append(data)
        for key, value in data.items():
            setattr(self.row, key, value)
        return self.row


class _Viewers:
    def __init__(self, rows: list[Any] | None = None) -> None:
        self.rows = rows or []

    async def find_many(self, where: dict[str, Any]) -> list[Any]:
        return list(self.rows)


class _Provisional:
    def __init__(self) -> None:
        self.deletes: list[dict[str, Any]] = []

    async def find_many(self, where: dict[str, Any]) -> list[Any]:
        return []

    async def delete_many(self, where: dict[str, Any]) -> int:
        self.deletes.append(dict(where))
        return 0


def _row(*, requester_role: str, status: str = "PENDING") -> SimpleNamespace:
    return SimpleNamespace(
        id=REQUEST_ID,
        designWorkshopId=WORKSHOP_ID,
        requestedById=REQUESTER_ID,
        status=status,
        source="SCAN",
        scannedCode="DPW1:G:CMDECIDEGUARD00000000000A:AAAA",
        tokenId=None,
        scannedAt=None,
        note="Rekha asked me",
        createdAt=datetime.now(UTC),
        decidedById=None,
        decidedAt=None,
        decisionNote=None,
        designWorkshop=SimpleNamespace(
            id=WORKSHOP_ID, title="Guard", workshopCode=None, createdById="somebody-else"
        ),
        requestedBy=SimpleNamespace(
            id=REQUESTER_ID, name="Asking Designer", email="asks@example.test", role=requester_role
        ),
        decidedBy=None,
    )


def _decide(monkeypatch: pytest.MonkeyPatch, row: Any, viewers: _Viewers) -> tuple[Any, _Requests]:
    requests = _Requests(row)
    monkeypatch.setattr(
        access, "db", SimpleNamespace(
            designworkshopaccessrequest=requests,
            designworkshopviewer=viewers,
            designworkshopprovisionalmember=_Provisional(),
        )
    )
    result = asyncio.run(
        access.decide(
            REQUEST_ID,
            decision="DENIED",
            note="not on this cluster",
            admin=SimpleNamespace(id=ADMIN_ID, role="ADMIN"),
        )
    )
    return result, requests


@pytest.mark.parametrize("role", ["ADMIN", "MASTER_ADMIN"])
def test_a_requester_who_became_an_admin_cannot_be_refused(monkeypatch, role):
    row = _row(requester_role=role)
    requests = _Requests(row)
    monkeypatch.setattr(
        access,
        "db",
        SimpleNamespace(
            designworkshopaccessrequest=requests,
            designworkshopviewer=_Viewers(),
            designworkshopprovisionalmember=_Provisional(),
        ),
    )

    with pytest.raises(HTTPException) as refusal:
        asyncio.run(
            access.decide(
                REQUEST_ID, decision="DENIED", note=None, admin=SimpleNamespace(id=ADMIN_ID)
            )
        )

    assert refusal.value.status_code == 409, refusal.value.detail
    assert "viewers" in str(refusal.value.detail)
    assert requests.updates == []
    assert row.status == "PENDING"


def test_the_row_is_loaded_with_the_requester_joined(monkeypatch):
    row = _row(requester_role="DESIGNER")
    _, requests = _decide(monkeypatch, row, _Viewers())

    assert requests.includes, "the row was loaded without any include at all"
    for include in requests.includes:
        assert include and "requestedBy" in include, include


def test_an_ordinary_designer_is_still_refusable(monkeypatch):
    row = _row(requester_role="DESIGNER")
    result, requests = _decide(monkeypatch, row, _Viewers())

    assert result["status"] == "DENIED"
    assert result["requesterHasAccess"] is False
    assert result["decisionNote"] == "not on this cluster"
    assert len(requests.updates) == 1
    assert requests.updates[0]["decidedById"] == ADMIN_ID


def test_a_requester_who_still_holds_a_viewer_row_cannot_be_refused(monkeypatch):
    row = _row(requester_role="DESIGNER")
    viewers = _Viewers([SimpleNamespace(designWorkshopId=WORKSHOP_ID, userId=REQUESTER_ID)])
    requests = _Requests(row)
    monkeypatch.setattr(
        access,
        "db",
        SimpleNamespace(
            designworkshopaccessrequest=requests,
            designworkshopviewer=viewers,
            designworkshopprovisionalmember=_Provisional(),
        ),
    )

    with pytest.raises(HTTPException) as refusal:
        asyncio.run(
            access.decide(
                REQUEST_ID, decision="DENIED", note=None, admin=SimpleNamespace(id=ADMIN_ID)
            )
        )

    assert refusal.value.status_code == 409, refusal.value.detail
    assert requests.updates == []


def test_the_creator_arm_is_asked_the_same_way(monkeypatch):
    row = _row(requester_role="DESIGNER")
    row.designWorkshop.createdById = REQUESTER_ID
    requests = _Requests(row)
    monkeypatch.setattr(
        access,
        "db",
        SimpleNamespace(
            designworkshopaccessrequest=requests,
            designworkshopviewer=_Viewers(),
            designworkshopprovisionalmember=_Provisional(),
        ),
    )

    with pytest.raises(HTTPException) as refusal:
        asyncio.run(
            access.decide(
                REQUEST_ID, decision="DENIED", note=None, admin=SimpleNamespace(id=ADMIN_ID)
            )
        )

    assert refusal.value.status_code == 409, refusal.value.detail
    assert requests.updates == []
