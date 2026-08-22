"""Refusing a request must never write DENIED over access that remains. No database required.

WHY THIS IS ITS OWN MODULE, AND WHY IT IS NOT IN THE DATABASE ONE. Access to a design workshop has
THREE sources — the ``createdById`` column, an ADMIN/MASTER_ADMIN role, and a ``DesignWorkshopViewer``
row — and ``_access_by_pair`` computes all three for the ``requesterHasAccess`` field an admin reads
off the queue. :func:`decide`'s 409 guard used to compute TWO of them with its own hand-written
predicate, so a designer PROMOTED to admin after asking could be refused: DENIED written, 200
returned, and ``requesterHasAccess: true`` in the very body that said it — the response contradicting
itself about the one fact the guard exists to protect, which is precisely the lie the guard was
written to prevent.

That arm is unreachable over HTTP without changing somebody's role mid-test, and the roles in
``test_design_workshop_access_requests``' fixture are shared by every test in it: promoting one of
them is a one-way trip (``assert_can_manage_target`` refuses an admin the management of a peer, so
nothing in that fixture could demote the account again) and would make the module order-dependent —
the failure mode its own ``_make_workshop`` docstring refuses. So the guard is exercised here instead,
against a stub, where the requester's role is just a field.

IT IS ALSO UNGATED, so unlike the database module it actually runs in CI — the same reasoning
``test_design_workshop_access_gate`` gives for living beside it. That module cannot host this: its
``db`` is a TRIPWIRE that raises the moment any delegate is read, which is the property it exists to
assert, and a stub that answers queries is the opposite of it.

WHAT IS ASSERTED, in one sentence: the guard and the field cannot disagree, because there is now one
predicate rather than two — and the row is loaded with the requester JOINED, which is what that one
predicate needs to see a role at all.
"""

import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

import app.services.design_workshop_access as access

WORKSHOP_ID = "cmdecideguard00000000000a"
REQUEST_ID = "cmdecideguard00000000000r"
REQUESTER_ID = "cmdecideguard00000000000u"
ADMIN_ID = "cmdecideguard00000000000d"


class _Requests:
    """The access-request delegate: one row, and a record of whether anything was written to it."""

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
    """The viewer delegate. Empty on purpose: the point is that the ROLE alone confers access."""

    def __init__(self, rows: list[Any] | None = None) -> None:
        self.rows = rows or []

    async def find_many(self, where: dict[str, Any]) -> list[Any]:
        return list(self.rows)


def _row(*, requester_role: str, status: str = "PENDING") -> SimpleNamespace:
    """One queue row as ``find_unique(..., include=_QUEUE_INCLUDE)`` hands it back."""
    return SimpleNamespace(
        id=REQUEST_ID,
        designWorkshopId=WORKSHOP_ID,
        requestedById=REQUESTER_ID,
        status=status,
        source="SCAN",
        scannedCode="DPW1:G:CMDECIDEGUARD00000000000A:AAAA",
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
    """Run ``decide`` with a DENIED decision over a stubbed ``db``. Answers (result, delegate).

    The stub is bound to the SERVICE MODULE's own ``db`` name, because that module does
    ``from app.core.db import db`` and therefore holds its own reference — patching
    ``app.core.db.db`` alone would leave this one pointing at the real client.
    """
    requests = _Requests(row)
    monkeypatch.setattr(
        access, "db", SimpleNamespace(designworkshopaccessrequest=requests, designworkshopviewer=viewers)
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
    """409, and nothing written — the ROLE arm of the three-source rule.

    An admin reaches every design workshop through ``is_admin`` regardless of any viewer row, so
    writing DENIED here would put a sentence on the screen that is false in the direction that
    matters: the admin who pressed Refuse would believe they had taken the access away, and the
    person would carry on opening the workshop. MASTER_ADMIN is in the list because a guard written
    as ``role == "ADMIN"`` passes the first half of this test and fails the second.
    """
    row = _row(requester_role=role)
    requests = _Requests(row)
    monkeypatch.setattr(
        access,
        "db",
        SimpleNamespace(designworkshopaccessrequest=requests, designworkshopviewer=_Viewers()),
    )

    with pytest.raises(HTTPException) as refusal:
        asyncio.run(
            access.decide(
                REQUEST_ID, decision="DENIED", note=None, admin=SimpleNamespace(id=ADMIN_ID)
            )
        )

    assert refusal.value.status_code == 409, refusal.value.detail
    assert "viewers" in str(refusal.value.detail)
    # NOT HALF-APPLIED: the refusal fires before the row is marked, so the request is still PENDING
    # for an admin to see rather than DENIED over access that remains.
    assert requests.updates == []
    assert row.status == "PENDING"


def test_the_row_is_loaded_with_the_requester_joined(monkeypatch):
    """The join is load-bearing, not decoration.

    ``_access_by_pair`` reads the role off ``row.requestedBy`` — the relation the query already
    joined, which is why it costs nothing. Load the row WITHOUT that join and the role reads ``None``,
    the admin arm silently disappears, and the guard is back to the two-source predicate this whole
    module exists to stop coming back. So the include is asserted rather than assumed.
    """
    row = _row(requester_role="DESIGNER")
    _, requests = _decide(monkeypatch, row, _Viewers())

    assert requests.includes, "the row was loaded without any include at all"
    for include in requests.includes:
        assert include and "requestedBy" in include, include


def test_an_ordinary_designer_is_still_refusable(monkeypatch):
    """The other half: no creator column, no admin role, no viewer row, so the refusal goes through.

    A guard that answered 409 for everybody would pass the test above and make the Refuse button
    useless. And the payload's ``requesterHasAccess`` is asserted beside the write, because "the
    guard and the field agree" is the property — one predicate feeds both, and a response that said
    ``false`` here while the guard had said "already in" would be the same contradiction with the
    signs reversed.
    """
    row = _row(requester_role="DESIGNER")
    result, requests = _decide(monkeypatch, row, _Viewers())

    assert result["status"] == "DENIED"
    assert result["requesterHasAccess"] is False
    assert result["decisionNote"] == "not on this cluster"
    assert len(requests.updates) == 1
    assert requests.updates[0]["decidedById"] == ADMIN_ID


def test_a_requester_who_still_holds_a_viewer_row_cannot_be_refused(monkeypatch):
    """The arm that was already covered, kept beside the new one so the pair reads as one rule.

    This is the ordinary case — the designer was granted last week and the request row is still in
    the ALL view — and it is asserted here as well as over a real database because the two arms are
    now answered by the SAME predicate. A regression in that predicate should fail one test file, not
    depend on which of two files somebody happened to run.
    """
    row = _row(requester_role="DESIGNER")
    viewers = _Viewers([SimpleNamespace(designWorkshopId=WORKSHOP_ID, userId=REQUESTER_ID)])
    requests = _Requests(row)
    monkeypatch.setattr(
        access,
        "db",
        SimpleNamespace(designworkshopaccessrequest=requests, designworkshopviewer=viewers),
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
    """A request whose requester is the workshop's CREATOR is refusable in neither direction either.

    ``file_request`` will not file such a row — the creator holds the workshop through
    ``createdById`` and is turned away at the door — so this is reachable only through a hand-run
    insert or a row that predates a change of owner. It is asserted because it is the third arm of
    the same predicate, and a guard that lost it would be the same defect one column over.
    """
    row = _row(requester_role="DESIGNER")
    row.designWorkshop.createdById = REQUESTER_ID
    requests = _Requests(row)
    monkeypatch.setattr(
        access,
        "db",
        SimpleNamespace(designworkshopaccessrequest=requests, designworkshopviewer=_Viewers()),
    )

    with pytest.raises(HTTPException) as refusal:
        asyncio.run(
            access.decide(
                REQUEST_ID, decision="DENIED", note=None, admin=SimpleNamespace(id=ADMIN_ID)
            )
        )

    assert refusal.value.status_code == 409, refusal.value.detail
    assert requests.updates == []
