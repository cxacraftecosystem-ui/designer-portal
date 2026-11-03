import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

import app.services.design_workshop_access as access
import app.services.design_workshop_grants as grants
import app.services.design_workshop_posts as posts
import app.services.design_workshop_viewers as viewers

WORKSHOP_ID = "cmprovisional00000000000w"
CREATOR_ID = "cmprovisional00000000000c"
LATECOMER_ID = "cmprovisional00000000000l"
MEMBER_ID = "cmprovisional00000000000m"
ADMIN_ID = "cmprovisional00000000000d"
REQUEST_ID = "cmprovisional00000000000r"
TOKEN_ID = "cmprovisional00000000000t"


class _Rows:
    def __init__(self, rows: list[Any] | None = None) -> None:
        self.rows = list(rows or [])
        self.deletes: list[dict[str, Any]] = []

    @staticmethod
    def _matches(row: Any, where: dict[str, Any]) -> bool:
        for key, wanted in where.items():
            if key.endswith("_userId"):
                return _Rows._matches(row, wanted)
            actual = getattr(row, key, None)
            if isinstance(wanted, dict) and "in" in wanted:
                if actual not in wanted["in"]:
                    return False
            elif actual != wanted:
                return False
        return True

    async def find_unique(self, where: dict[str, Any], include: Any = None) -> Any:
        return next((row for row in self.rows if self._matches(row, where)), None)

    async def find_many(self, where: dict[str, Any] | None = None, **_: Any) -> list[Any]:
        return [row for row in self.rows if self._matches(row, where or {})]

    async def update(self, where: dict[str, Any], data: dict[str, Any]) -> Any:
        for row in self.rows:
            if self._matches(row, where):
                for key, value in data.items():
                    setattr(row, key, value)
                return row
        return None

    async def create_many(self, data: list[dict[str, Any]], skip_duplicates: bool = False) -> int:
        written = 0
        for item in data:
            pair = (item.get("designWorkshopId"), item.get("userId"))
            if any(
                (getattr(row, "designWorkshopId", None), getattr(row, "userId", None)) == pair
                for row in self.rows
            ):
                if not skip_duplicates:
                    raise AssertionError("duplicate key without skip_duplicates")
                continue
            self.rows.append(SimpleNamespace(**item))
            written += 1
        return written

    async def delete_many(self, where: dict[str, Any]) -> int:
        keep = [row for row in self.rows if not self._matches(row, where)]
        removed = len(self.rows) - len(keep)
        self.deletes.append(dict(where))
        self.rows = keep
        return removed


class _Client:
    def __init__(self, **tables: _Rows) -> None:
        for name, table in tables.items():
            setattr(self, name, table)

    def tx(self) -> Any:
        client = self

        class _Tx:
            async def __aenter__(self) -> Any:
                return client

            async def __aexit__(self, *_exc: object) -> bool:
                return False

        return _Tx()


def _foothold(user_id: str = LATECOMER_ID) -> SimpleNamespace:
    return SimpleNamespace(
        designWorkshopId=WORKSHOP_ID,
        userId=user_id,
        viaTokenId=TOKEN_ID,
        reason="ALREADY_SPENT",
        scannedAtClient=None,
        serverArrivedAt=datetime.now(UTC),
        createdAt=datetime.now(UTC),
    )


def _viewer(user_id: str) -> SimpleNamespace:
    return SimpleNamespace(
        designWorkshopId=WORKSHOP_ID,
        userId=user_id,
        grantedById=ADMIN_ID,
        tokenId=None,
        createdAt=datetime.now(UTC),
    )


def _request_row(status: str = "PENDING", requester_id: str = LATECOMER_ID) -> SimpleNamespace:
    return SimpleNamespace(
        id=REQUEST_ID,
        designWorkshopId=WORKSHOP_ID,
        requestedById=requester_id,
        status=status,
        source="SCAN",
        scannedCode="DPW2:J:CMPROVISIONAL00000000000W.…6X1Y:5299",
        tokenId=TOKEN_ID,
        scannedAt=datetime.now(UTC),
        note=None,
        createdAt=datetime.now(UTC),
        decidedById=None,
        decidedAt=None,
        decisionNote=None,
        designWorkshop=SimpleNamespace(
            id=WORKSHOP_ID, title="Kalamkari", workshopCode="DPW/OD/2026/14",
            createdById=CREATOR_ID, deletedAt=None,
        ),
        requestedBy=SimpleNamespace(
            id=requester_id, name="Late Comer", email="late@example.test", role="DESIGNER"
        ),
        decidedBy=None,
    )


@pytest.fixture
def world(monkeypatch: pytest.MonkeyPatch):
    tables = {
        "designworkshopviewer": _Rows([_viewer(MEMBER_ID)]),
        "designworkshopprovisionalmember": _Rows([_foothold()]),
        "designworkshopaccessrequest": _Rows([_request_row()]),
        "designworkshop": _Rows(
            [SimpleNamespace(id=WORKSHOP_ID, createdById=CREATOR_ID, deletedAt=None)]
        ),
    }
    db = _Client(**tables)
    for module in (access, grants, viewers):
        monkeypatch.setattr(module, "db", db)

    async def _holds_no_post(workshop_id: str, user_ids: Any) -> dict[str, frozenset[str]]:
        return {}

    monkeypatch.setattr(posts, "supervisory_posts_among", _holds_no_post)
    return SimpleNamespace(db=db, **tables)



def test_a_provisional_foothold_does_not_satisfy_has_viewer_grant(world):
    assert world.designworkshopprovisionalmember.rows, "the fixture must actually hold a foothold"
    assert asyncio.run(viewers.has_viewer_grant(WORKSHOP_ID, LATECOMER_ID)) is False
    assert asyncio.run(viewers.has_viewer_grant(WORKSHOP_ID, MEMBER_ID)) is True


def test_a_provisional_foothold_is_not_reported_as_access_to_the_admin_queue(world):
    row = world.designworkshopaccessrequest.rows[0]
    granted = asyncio.run(access._access_by_pair([row]))
    assert (WORKSHOP_ID, LATECOMER_ID) not in granted
    assert (WORKSHOP_ID, MEMBER_ID) in granted


def test_the_queue_reports_the_foothold_in_its_own_field(world):
    answer = asyncio.run(access.queue("PENDING"))
    assert len(answer["requests"]) == 1
    payload = answer["requests"][0]
    assert payload["requesterHasAccess"] is False
    assert payload["requesterIsProvisional"] is True
    assert payload["tokenId"] == TOKEN_ID
    assert payload["scannedAt"] is not None
    assert "…" in payload["scannedCode"]



def test_may_capture_is_the_only_predicate_that_says_yes_to_a_foothold(world):
    latecomer = SimpleNamespace(id=LATECOMER_ID, role="DESIGNER")
    member = SimpleNamespace(id=MEMBER_ID, role="DESIGNER")
    stranger = SimpleNamespace(id="cmstranger00000000000000z", role="DESIGNER")
    admin = SimpleNamespace(id=ADMIN_ID, role="ADMIN")

    assert asyncio.run(grants.may_capture(WORKSHOP_ID, latecomer)) is True
    assert asyncio.run(grants.may_capture(WORKSHOP_ID, member)) is True
    assert asyncio.run(grants.may_capture(WORKSHOP_ID, admin)) is True
    assert asyncio.run(grants.may_capture(WORKSHOP_ID, stranger)) is False

    assert asyncio.run(viewers.has_viewer_grant(WORKSHOP_ID, LATECOMER_ID)) is False


def test_a_stranger_has_no_foothold_and_no_grant(world):
    stranger = "cmstranger00000000000000z"
    assert asyncio.run(grants.provisional_member(WORKSHOP_ID, stranger)) is None
    assert asyncio.run(viewers.has_viewer_grant(WORKSHOP_ID, stranger)) is False



def test_granting_promotes_the_late_comer_and_clears_the_foothold(world, monkeypatch):
    asked: list[set[str]] = []

    async def _eligible(user_ids: set[str], **_workshop_context: Any) -> None:
        asked.append(set(user_ids))

    monkeypatch.setattr(access, "_assert_every_id_may_be_granted", _eligible)

    bystander = "cmprovision0000000000000z"
    world.designworkshopviewer.rows.append(_viewer(bystander))

    result = asyncio.run(
        access.decide(
            REQUEST_ID,
            decision="GRANTED",
            note="Rekha confirmed her",
            admin=SimpleNamespace(id=ADMIN_ID, role="ADMIN"),
        )
    )

    assert asked == [{LATECOMER_ID}], (
        "the requester and nobody else: validating the whole workshop is how a colleague's lapsed "
        "empanelment came to refuse an unrelated grant"
    )
    written = [row for row in world.designworkshopviewer.rows if row.userId == LATECOMER_ID]
    assert len(written) == 1
    assert written[0].grantedById == ADMIN_ID
    assert written[0].tokenId is None
    assert bystander in {row.userId for row in world.designworkshopviewer.rows}

    assert world.designworkshopprovisionalmember.rows == [], "the foothold must not outlive the grant"
    assert asyncio.run(viewers.has_viewer_grant(WORKSHOP_ID, LATECOMER_ID)) is True
    assert result["status"] == "GRANTED"
    assert result["requesterIsProvisional"] is False


def test_refusing_actually_takes_the_foothold_away(world):
    result = asyncio.run(
        access.decide(
            REQUEST_ID,
            decision="DENIED",
            note="not on this cluster",
            admin=SimpleNamespace(id=ADMIN_ID, role="ADMIN"),
        )
    )
    assert result["status"] == "DENIED"
    assert world.designworkshopprovisionalmember.rows == []
    assert asyncio.run(grants.may_capture(WORKSHOP_ID, SimpleNamespace(id=LATECOMER_ID, role="DESIGNER"))) is False
    assert asyncio.run(viewers.has_viewer_grant(WORKSHOP_ID, LATECOMER_ID)) is False


def test_a_full_member_can_still_be_refused_without_a_foothold_in_the_way(world):
    world.designworkshopaccessrequest.rows = [_request_row(requester_id=MEMBER_ID)]
    with pytest.raises(HTTPException) as refusal:
        asyncio.run(
            access.decide(
                REQUEST_ID,
                decision="DENIED",
                note="",
                admin=SimpleNamespace(id=ADMIN_ID, role="ADMIN"),
            )
        )
    assert refusal.value.status_code == 409
