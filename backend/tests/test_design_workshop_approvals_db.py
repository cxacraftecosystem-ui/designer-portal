"""THE APPROVING AUTHORITY'S DECISIONS, OVER A DATABASE.

Every gate and every state the pure module (``test_design_workshop_approvals.py``) can only assert of
the source, asserted here of rows: each role allowed or refused, rule 7's refusals, the wrong-state
refusals, the mandatory sentence, the freeze and hand-on's finality, one ``ReviewLog`` row per
decision, and two approvals at once producing exactly one.

THE SHAPE IS ``test_workshop_join_sync.py``'s: a SYNC module fixture seeds through a private
``asyncio.run`` and only then starts the ``TestClient``; the tests drive the client and never touch
``db``. Each test owns its own workshop, so the module is order-independent. The race test is the
one exception and runs AFTER the client has closed, in its own ``asyncio.run``, calling the route
coroutines directly — the rule conftest's "HOW A DATABASE-BACKED MODULE IS SUPPOSED TO REACH THE
DATABASE" states: no database call while a client is alive.
"""

from __future__ import annotations

import asyncio
import os
import uuid
from typing import Any

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core.db import db
from app.core.security import create_access_token, hash_password

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL, reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL"
    ),
]

#: slug -> role.
ACCOUNTS = {
    "ministry": "MINISTRY_ADMIN",
    "ministry2": "MINISTRY_ADMIN",
    "master": "MASTER_ADMIN",
    "admin": "ADMIN",
    "designer": "DESIGNER",
    "inspector": "INSPECTOR",
    "rd": "REGIONAL_DIRECTOR",
    "ad": "ASSISTANT_DIRECTOR",
    "professor": "PROFESSOR",
    # An authority member who is also the designer on one workshop, and one who inspects another.
    "authoring_ministry": "MINISTRY_ADMIN",
    "inspecting_ministry": "MINISTRY_ADMIN",
    # An authority member appointed the workshop's Assistant Director: rule 7c — not barred.
    "ad_ministry": "MINISTRY_ADMIN",
}

#: Each test's own workshop, by name, and the status it starts in.
WORKSHOPS = {
    "approve": "PRE_SUBMISSION",
    "approve_twice": "PRE_SUBMISSION",
    "stale": "PRE_SUBMISSION",
    "not_handed_in": "IN_PROGRESS",
    "authored": "PRE_SUBMISSION",
    "inspected": "PRE_SUBMISSION",
    "ad_post": "PRE_SUBMISSION",
    "withdraw": "PRE_SUBMISSION",
    "hand_on": "PRE_SUBMISSION",
    "return_without": "PRE_SUBMISSION",
    "frozen": "PRE_SUBMISSION",
    "legacy": "SUBMITTED",
    "race": "PRE_SUBMISSION",
}


async def _seed() -> dict[str, Any]:
    tag = uuid.uuid4().hex[:8]
    await db.connect()
    try:
        people: dict[str, Any] = {}
        for slug, role in ACCOUNTS.items():
            people[slug] = await db.user.create(
                data={
                    "email": f"approvals-{slug}-{tag}@example.org",
                    "name": f"{slug.title()} {tag}",
                    "role": role,
                    "passwordHash": hash_password("unused"),
                }
            )
        await db.designerroster.create(
            data={
                "email": f"approvals-designer-{tag}@example.org",
                "fullName": "Roster row",
                "institution": "Directorate of Handicrafts",
                "isActive": True,
                "addedById": people["admin"].id,
            }
        )
        workshops: dict[str, Any] = {}
        for name, status_value in WORKSHOPS.items():
            row = await db.designworkshop.create(
                data={
                    "title": f"Approvals {name} {tag}",
                    "createdById": people["admin"].id,
                    "status": status_value,
                    "submissionRound": 1 if status_value != "IN_PROGRESS" else 0,
                }
            )
            workshops[name] = row
            await db.designworkshopviewer.create(
                data={
                    "designWorkshopId": row.id,
                    "userId": people["designer"].id,
                    "grantedById": people["admin"].id,
                }
            )
        await db.designworkshopviewer.create(
            data={
                "designWorkshopId": workshops["authored"].id,
                "userId": people["authoring_ministry"].id,
                "grantedById": people["admin"].id,
            }
        )
        await db.designworkshopinspector.create(
            data={
                "designWorkshopId": workshops["inspected"].id,
                "userId": people["inspecting_ministry"].id,
                "assignedById": people["admin"].id,
            }
        )
        await db.designworkshopoversight.create(
            data={
                "designWorkshopId": workshops["ad_post"].id,
                "capacity": "ASSISTANT_DIRECTOR",
                "userId": people["ad_ministry"].id,
                "assignedById": people["admin"].id,
            }
        )
        return {"people": people, "workshops": {k: v.id for k, v in workshops.items()}, "tag": tag}
    finally:
        await db.disconnect()


async def _read_back(workshop_ids: list[str]) -> dict[str, Any]:
    await db.connect()
    try:
        rows = {}
        for wid in workshop_ids:
            rows[wid] = {
                "workshop": await db.designworkshop.find_unique(where={"id": wid}),
                "logs": await db.reviewlog.find_many(
                    where={"recordType": "DESIGN_WORKSHOP", "recordId": wid}
                ),
                "feedback": await db.dwinspectionfeedback.find_many(where={"designWorkshopId": wid}),
            }
        return rows
    finally:
        await db.disconnect()


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.main import app

    seeded = asyncio.run(_seed())
    client = TestClient(app)
    client.__enter__()
    seeded["client"] = client
    seeded["closed"] = False
    yield seeded
    if not seeded["closed"]:
        client.__exit__(None, None, None)


def _close_the_client(world: dict[str, Any]) -> None:
    """End the app's lifespan so a test may reach the database itself, as conftest requires."""
    if not world["closed"]:
        world["client"].__exit__(None, None, None)
        world["closed"] = True


def _as(world: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(world['people'][slug].id)}"}


def _read(world, slug: str, name: str):
    return world["client"].get(
        f"/api/design-workshop-approvals/{world['workshops'][name]}", headers=_as(world, slug)
    )


def _approve(world, slug: str, name: str, *, read_at: str | None = None, round_: int = 1):
    if read_at is None:
        read_at = _read(world, "master", name).json()["updatedAt"]
    return world["client"].post(
        f"/api/design-workshop-approvals/{world['workshops'][name]}/approve",
        json={"round": round_, "readAt": read_at, "note": "Satisfied."},
        headers=_as(world, slug),
    )


def _post(world, slug: str, name: str, verb: str, body: dict[str, Any]):
    return world["client"].post(
        f"/api/design-workshop-approvals/{world['workshops'][name]}/{verb}",
        json=body,
        headers=_as(world, slug),
    )


# --------------------------------------------------------------------------------------
# Who may ask at all
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize("slug", ["admin", "designer", "inspector", "rd", "ad", "professor"])
def test_every_role_outside_the_authority_is_refused_every_route(world, slug):
    from app.core.deps import APPROVAL_AUTHORITY_REFUSAL

    wid = world["workshops"]["approve"]
    client = world["client"]
    for method, path, body in (
        ("GET", "/api/design-workshop-approvals", None),
        ("GET", "/api/design-workshop-approvals/awaiting-count", None),
        ("GET", f"/api/design-workshop-approvals/{wid}", None),
        ("POST", f"/api/design-workshop-approvals/{wid}/approve", {"round": 1, "readAt": "2026-10-10"}),
        ("POST", f"/api/design-workshop-approvals/{wid}/revise", {"note": "x"}),
        ("POST", f"/api/design-workshop-approvals/{wid}/hand-on", {}),
    ):
        answer = client.request(method, path, json=body, headers=_as(world, slug))
        assert answer.status_code == 403, (slug, path, answer.text)
        assert answer.json()["detail"] == APPROVAL_AUTHORITY_REFUSAL


def test_the_queue_and_the_count_answer_the_authority(world):
    client = world["client"]
    count = client.get("/api/design-workshop-approvals/awaiting-count", headers=_as(world, "ministry"))
    assert count.status_code == 200 and count.json()["awaiting"] >= 1
    queue = client.get(
        "/api/design-workshop-approvals",
        params={"state": "awaiting", "search": world["tag"]},
        headers=_as(world, "master"),
    )
    assert queue.status_code == 200
    statuses = {item["status"] for item in queue.json()["items"]}
    assert statuses == {"PRE_SUBMISSION"}
    bad = client.get("/api/design-workshop-approvals", params={"state": "x"}, headers=_as(world, "master"))
    assert bad.status_code == 422


# --------------------------------------------------------------------------------------
# Approve
# --------------------------------------------------------------------------------------


def test_a_ministry_admin_approves_and_one_audit_row_is_written(world):
    answer = _approve(world, "ministry", "approve")
    assert answer.status_code == 200, answer.text
    body = answer.json()
    assert body["status"] == "APPROVED"
    assert body["approvedById"] == world["people"]["ministry"].id
    assert body["approvedRound"] == 1
    assert body["decisions"][0]["kind"] == "APPROVED"
    assert body["mayDecide"] == {"approve": False, "revise": True, "handOn": True}


def test_a_second_approval_is_a_no_op_and_writes_nothing(world):
    assert _approve(world, "ministry", "approve_twice").status_code == 200
    again = _approve(world, "master", "approve_twice")
    assert again.status_code == 200 and again.json()["alreadyApproved"] is True
    assert again.json()["approvedById"] == world["people"]["ministry"].id


def test_an_approval_of_content_changed_since_it_was_read_is_refused(world):
    answer = _approve(world, "ministry", "stale", read_at="2000-01-01T00:00:00+00:00")
    assert answer.status_code == 409
    assert "changed after you opened it" in answer.json()["detail"]
    wrong_round = _approve(world, "ministry", "stale", round_=7)
    assert wrong_round.status_code == 409


def test_a_report_not_handed_in_cannot_be_approved(world):
    read = _read(world, "ministry", "not_handed_in").json()
    answer = _approve(world, "ministry", "not_handed_in", read_at=read["updatedAt"], round_=1)
    assert answer.status_code == 422
    assert "Pre-submission" in answer.json()["detail"]


# --------------------------------------------------------------------------------------
# Rule 7
# --------------------------------------------------------------------------------------


def test_an_authority_member_who_worked_on_it_is_refused(world):
    answer = _approve(world, "authoring_ministry", "authored")
    assert answer.status_code == 403
    assert "nobody approves their own work" in answer.json()["detail"]
    read = _read(world, "authoring_ministry", "authored").json()
    assert read["decisionRefusal"] and not any(read["mayDecide"].values())


def test_an_authority_member_who_inspects_it_is_refused(world):
    answer = _approve(world, "inspecting_ministry", "inspected")
    assert answer.status_code == 403
    assert "inspects" in answer.json()["detail"]


def test_an_authority_member_in_a_director_post_is_not_barred(world):
    assert _approve(world, "ad_ministry", "ad_post").status_code == 200


# --------------------------------------------------------------------------------------
# Revise, hand on, and finality
# --------------------------------------------------------------------------------------


def test_a_withdrawal_needs_a_reason_and_clears_the_approval(world):
    assert _approve(world, "ministry", "withdraw").status_code == 200
    blank = _post(world, "master", "withdraw", "revise", {"note": "   "})
    assert blank.status_code == 422
    assert "Say what needs correcting" in blank.json()["detail"]
    answer = _post(world, "master", "withdraw", "revise", {"note": "The costs do not add up."})
    assert answer.status_code == 200, answer.text
    body = answer.json()
    assert body["status"] == "NEEDS_REVISION"
    assert body["approvedById"] is None and body["approvedAt"] is None
    assert body["decisions"][0]["kind"] == "APPROVAL_WITHDRAWN"
    assert body["inspectionFeedback"][0]["byApprovingAuthority"] is True
    assert body["inspectionFeedback"][0]["sentBack"] is True
    # Withdrawn: nothing left to hand on.
    assert _post(world, "master", "withdraw", "hand-on", {}).status_code == 422


def test_the_authority_can_send_back_without_approving(world):
    answer = _post(world, "ministry", "return_without", "revise", {"note": "Stage 14 is empty."})
    assert answer.status_code == 200, answer.text
    assert answer.json()["status"] == "NEEDS_REVISION"
    assert answer.json()["decisions"][0]["kind"] == "RETURNED"


def test_hand_on_records_the_office_and_is_final_to_every_header_edit(world):
    assert _approve(world, "ministry", "hand_on").status_code == 200
    # Rule 7e: the approver may hand on.
    answer = _post(world, "ministry", "hand_on", "hand-on", {"note": "By post."})
    assert answer.status_code == 200, answer.text
    body = answer.json()
    assert body["status"] == "SUBMITTED" and body["handedOnAt"]
    assert body["handedOnTo"] == "Office of the Development Commissioner (Handicrafts)"
    assert body["decisions"][0]["kind"] == "HANDED_ON"
    assert _post(world, "master", "hand_on", "hand-on", {}).status_code == 409
    wid = world["workshops"]["hand_on"]
    for target in ("IN_PROGRESS", "PRE_SUBMISSION", "ARCHIVED"):
        patch = world["client"].patch(
            f"/api/design-workshops/{wid}", json={"status": target}, headers=_as(world, "designer")
        )
        assert patch.status_code == 403, (target, patch.text)
        assert "handed on to the office" in patch.json()["detail"]


def test_a_handed_on_report_can_be_returned_by_the_authority(world):
    # R1, on the report the test above handed on (independent if run alone: hand it on first).
    wid = world["workshops"]["hand_on"]
    read = world["client"].get(f"/api/design-workshop-approvals/{wid}", headers=_as(world, "master"))
    if read.json()["status"] != "SUBMITTED":
        pytest.skip("depends on the hand-on test's workshop")
    assert read.json()["reviseKind"] == "RETURN_FROM_OFFICE"
    answer = _post(world, "master", "hand_on", "revise", {"note": "The office asked for the cost sheet."})
    assert answer.status_code == 200, answer.text
    assert answer.json()["status"] == "NEEDS_REVISION"
    assert answer.json()["handedOnAt"] is None


def test_an_approved_report_is_frozen_to_its_designers(world):
    assert _approve(world, "ministry", "frozen").status_code == 200
    wid = world["workshops"]["frozen"]
    client = world["client"]
    edit = client.patch(f"/api/design-workshops/{wid}", json={"title": "Renamed"}, headers=_as(world, "designer"))
    assert edit.status_code == 403
    assert "has been approved" in edit.json()["detail"]
    save = client.put(
        f"/api/design-workshops/{wid}/stages/WORKSHOP_SETUP", json={"entries": []}, headers=_as(world, "designer")
    )
    assert save.status_code == 403


def test_a_legacy_submitted_report_has_nothing_to_withdraw(world):
    answer = _post(world, "ministry", "legacy", "revise", {"note": "x"})
    assert answer.status_code == 422
    assert _post(world, "ministry", "legacy", "hand-on", {}).status_code == 422


def test_each_decision_wrote_exactly_one_audit_row(world):
    """Read back AFTER the client: the rows the decisions above left, one per decision."""
    _close_the_client(world)
    ids = world["workshops"]
    rows = asyncio.run(_read_back([ids["approve"], ids["approve_twice"], ids["stale"], ids["withdraw"]]))
    assert len(rows[ids["approve"]]["logs"]) == 1
    assert len(rows[ids["approve_twice"]]["logs"]) == 1, "the no-op second approval wrote a row"
    assert rows[ids["stale"]]["logs"] == [], "a refused approval wrote a row"
    assert rows[ids["stale"]]["workshop"].status == "PRE_SUBMISSION"
    withdraw = rows[ids["withdraw"]]
    assert len(withdraw["logs"]) == 2 and len(withdraw["feedback"]) == 1
    assert withdraw["feedback"][0].round == 1


def test_two_approvals_at_once_produce_exactly_one(world):
    """The compare-and-set decides it: both read PRE_SUBMISSION, one write matches, one does not."""
    from fastapi import HTTPException

    from app.api.routes import design_workshop_approvals as routes
    from app.schemas.design_workshop_approvals import DwApproveIn

    _close_the_client(world)
    wid = world["workshops"]["race"]
    first, second = world["people"]["ministry"], world["people"]["ministry2"]

    async def race() -> list[Any]:
        await db.connect()
        try:
            row = await db.designworkshop.find_unique(where={"id": wid})
            body = DwApproveIn(round=1, readAt=row.updatedAt.isoformat())
            results = await asyncio.gather(
                routes.approve_report(wid, body, current_user=first),
                routes.approve_report(wid, body, current_user=second),
                return_exceptions=True,
            )
            logs = await db.reviewlog.find_many(where={"recordType": "DESIGN_WORKSHOP", "recordId": wid})
            return [results, logs, await db.designworkshop.find_unique(where={"id": wid})]
        finally:
            await db.disconnect()

    results, logs, row = asyncio.run(race())
    assert row.status == "APPROVED"
    assert len(logs) == 1, "two approvals at once wrote two audit rows"
    assert all(not isinstance(r, Exception) or isinstance(r, HTTPException) for r in results)
    approvers = {r["approvedById"] for r in results if isinstance(r, dict)}
    assert approvers == {row.approvedById}
