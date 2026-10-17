"""THE REVIEW AIDS OVER A DATABASE (2026-10-17): batches of notes, "Checked, no corrections", the
directors' notes, round snapshots, queue readiness, the approval screen's photographs and the reuse of
an unchanged report file.

The shape is ``test_design_workshop_approvals_db.py``'s: a module fixture seeds through a private
``asyncio.run`` and only then starts the ``TestClient``; the tests drive the client and never touch
``db`` while it is alive. Each test owns its own workshop.
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
from app.services.stage_schema import stages

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL, reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL"
    ),
]

ACCOUNTS = {
    "ministry": "MINISTRY_ADMIN",
    "designer": "DESIGNER",
    "inspector": "INSPECTOR",
    "inspector2": "INSPECTOR",
    "ad": "ASSISTANT_DIRECTOR",
    "rd": "REGIONAL_DIRECTOR",
    "stranger_ad": "ASSISTANT_DIRECTOR",
    "admin": "ADMIN",
}

WORKSHOPS = {
    "batch": "PRE_SUBMISSION",
    "send_batch": "PRE_SUBMISSION",
    "clear": "PRE_SUBMISSION",
    "clear_then_note": "PRE_SUBMISSION",
    "revise_batch": "PRE_SUBMISSION",
    "officer": "PRE_SUBMISSION",
    "officer_closed": "IN_PROGRESS",
    "hand_in": "IN_PROGRESS",
    "report": "PRE_SUBMISSION",
    "groups_waiting": "NEEDS_REVISION",
}


def _stage(index: int = 0) -> str:
    return stages()[index].key


async def _seed() -> dict[str, Any]:
    tag = uuid.uuid4().hex[:8]
    await db.connect()
    try:
        people: dict[str, Any] = {}
        for slug, role in ACCOUNTS.items():
            people[slug] = await db.user.create(
                data={
                    "email": f"aids-{slug}-{tag}@example.org",
                    "name": f"{slug.title()} {tag}",
                    "role": role,
                    "passwordHash": hash_password("unused"),
                }
            )
        await db.designerroster.create(
            data={
                "email": f"aids-designer-{tag}@example.org",
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
                    "title": f"Aids {name} {tag}",
                    "createdById": people["designer"].id,
                    "status": status_value,
                    "submissionRound": 0 if status_value == "IN_PROGRESS" else 1,
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
            for inspector in ("inspector", "inspector2"):
                await db.designworkshopinspector.create(
                    data={
                        "designWorkshopId": row.id,
                        "userId": people[inspector].id,
                        "assignedById": people["admin"].id,
                    }
                )
        for name in ("officer", "officer_closed"):
            await db.designworkshopoversight.create(
                data={
                    "designWorkshopId": workshops[name].id,
                    "capacity": "ASSISTANT_DIRECTOR",
                    "userId": people["ad"].id,
                    "assignedById": people["admin"].id,
                }
            )
        await db.designworkshopoversight.create(
            data={
                "designWorkshopId": workshops["officer"].id,
                "capacity": "REGIONAL_DIRECTOR",
                "userId": people["rd"].id,
                "assignedById": people["admin"].id,
            }
        )
        return {"people": people, "workshops": {k: v.id for k, v in workshops.items()}, "tag": tag}
    finally:
        await db.disconnect()


async def _read_back(workshop_id: str) -> dict[str, Any]:
    await db.connect()
    try:
        return {
            "workshop": await db.designworkshop.find_unique(where={"id": workshop_id}),
            "feedback": await db.dwinspectionfeedback.find_many(
                where={"designWorkshopId": workshop_id}, order={"createdAt": "asc"}
            ),
            "logs": await db.reviewlog.find_many(
                where={"recordType": "DESIGN_WORKSHOP", "recordId": workshop_id}
            ),
            "exports": await db.dwreportexport.find_many(where={"designWorkshopId": workshop_id}),
            "snapshots": await db.dwroundsnapshot.find_many(where={"designWorkshopId": workshop_id}),
        }
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
    yield seeded
    seeded["client"].__exit__(None, None, None)


def _as(world: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(world['people'][slug].id)}"}


def _wid(world, name: str) -> str:
    return world["workshops"][name]


def _inspect(world, slug: str, name: str, verb: str, body: Any = None, method: str = "POST"):
    return world["client"].request(
        method,
        f"/api/design-workshop-inspections/{_wid(world, name)}/{verb}",
        json=body,
        headers=_as(world, slug),
    )


# --------------------------------------------------------------------------------------
# B2: batches
# --------------------------------------------------------------------------------------


def test_an_inspector_files_a_tray_of_notes_in_one_request(world):
    answer = _inspect(
        world,
        "inspector",
        "batch",
        "feedback/batch",
        {
            "notes": [
                {"note": "Total is wrong.", "stageKey": _stage(0), "fieldKey": "venue"},
                {"note": "Add a photo.", "stageKey": _stage(1)},
                {"note": "Spell the cluster consistently."},
            ]
        },
    )
    assert answer.status_code == 201, answer.text
    body = answer.json()
    assert body["status"] == "PRE_SUBMISSION"
    assert len(body["inspectionFeedback"]) == 3
    assert all(row["editedSince"] is False for row in body["inspectionFeedback"])
    fields = {row["fieldKey"] for row in body["inspectionFeedback"]}
    assert "venue" in fields


def test_a_bad_note_refuses_the_whole_batch(world):
    answer = _inspect(
        world,
        "inspector2",
        "batch",
        "feedback/batch",
        {"notes": [{"note": "Fine."}, {"note": "Bad stage.", "stageKey": "NOT_A_STAGE"}]},
    )
    assert answer.status_code == 422
    read = world["client"].get(
        f"/api/design-workshop-inspections/{_wid(world, 'batch')}", headers=_as(world, "inspector2")
    )
    assert all(
        row["actorId"] != world["people"]["inspector2"].id
        for row in read.json()["inspectionFeedback"]
    )


def test_an_inspector_sends_back_with_several_notes(world):
    answer = _inspect(
        world,
        "inspector",
        "send_batch",
        "send-back/batch",
        {"notes": [{"note": "First."}, {"note": "Second.", "stageKey": _stage(2)}]},
    )
    assert answer.status_code == 200, answer.text
    body = answer.json()
    assert body["status"] == "NEEDS_REVISION"
    # Newest first on the wire; the first note is the one that sent it back.
    assert [row["sentBack"] for row in reversed(body["inspectionFeedback"])] == [True, False]
    assert body["reviewNotes"] == "First. (and 1 more note)"


def test_the_approver_sends_back_with_a_tray(world):
    answer = world["client"].post(
        f"/api/design-workshop-approvals/{_wid(world, 'revise_batch')}/revise",
        json={"notes": [{"note": "Venue missing.", "stageKey": _stage(0), "fieldKey": "venue"}]},
        headers=_as(world, "ministry"),
    )
    assert answer.status_code == 200, answer.text
    body = answer.json()
    assert body["status"] == "NEEDS_REVISION"
    assert len(body["inspectionFeedback"]) == 1
    only = body["inspectionFeedback"][0]
    assert only["byApprovingAuthority"] and only["sentBack"] and only["fieldKey"] == "venue"


# --------------------------------------------------------------------------------------
# C5: "Checked, no corrections"
# --------------------------------------------------------------------------------------


def test_an_inspector_clears_a_round_and_the_approver_sees_it(world):
    first = _inspect(world, "inspector", "clear", "clearance")
    assert first.status_code == 200, first.text
    assert first.json()["myClearedAt"]
    again = _inspect(world, "inspector", "clear", "clearance")
    assert again.status_code == 200
    read = world["client"].get(
        f"/api/design-workshop-approvals/{_wid(world, 'clear')}", headers=_as(world, "ministry")
    )
    marked = {p["userId"]: p["clearedAt"] for p in read.json()["inspectors"]}
    assert marked[world["people"]["inspector"].id]
    assert marked[world["people"]["inspector2"].id] is None
    # Never required for approval: the decision view does not read the verdicts at all.
    assert read.json()["mayDecide"]["approve"] is True

    some = world["client"].get(
        "/api/design-workshop-approvals",
        params={"state": "awaiting", "search": world["tag"], "cleared": "some"},
        headers=_as(world, "ministry"),
    )
    ids = [row["id"] for row in some.json()["items"]]
    assert _wid(world, "clear") in ids
    assert _wid(world, "batch") not in ids
    row = next(r for r in some.json()["items"] if r["id"] == _wid(world, "clear"))
    assert row["readiness"]["cleared"] == 1 and row["readiness"]["inspectors"] == 2

    both = _inspect(world, "inspector2", "clear", "clearance")
    assert both.status_code == 200
    every = world["client"].get(
        "/api/design-workshop-approvals",
        params={"state": "awaiting", "search": world["tag"], "cleared": "all"},
        headers=_as(world, "ministry"),
    )
    assert [r["id"] for r in every.json()["items"]] == [_wid(world, "clear")]

    withdrawn = _inspect(world, "inspector2", "clear", "clearance", method="DELETE")
    assert withdrawn.status_code == 200 and withdrawn.json()["myClearedAt"] is None


def test_a_note_ends_the_verdict_and_a_verdict_is_refused_over_notes(world):
    assert _inspect(world, "inspector", "clear_then_note", "clearance").status_code == 200
    noted = _inspect(world, "inspector", "clear_then_note", "feedback", {"note": "One thing."})
    assert noted.status_code == 201
    assert noted.json()["myClearedAt"] is None
    refused = _inspect(world, "inspector", "clear_then_note", "clearance")
    assert refused.status_code == 422


# --------------------------------------------------------------------------------------
# C16: the directors' notes
# --------------------------------------------------------------------------------------


def test_an_assistant_director_files_a_note_marked_with_the_post(world):
    wid = _wid(world, "officer")
    answer = world["client"].post(
        f"/api/design-workshop-oversight/assigned/{wid}/feedback",
        json={"note": "The dates on stage 1 disagree.", "stageKey": _stage(0)},
        headers=_as(world, "ad"),
    )
    assert answer.status_code == 201, answer.text
    assert answer.json()["inspectionFeedback"][0]["actorPost"] == "ASSISTANT_DIRECTOR"
    batch = world["client"].post(
        f"/api/design-workshop-oversight/assigned/{wid}/feedback/batch",
        json={"notes": [{"note": "One."}, {"note": "Two."}]},
        headers=_as(world, "rd"),
    )
    assert batch.status_code == 201, batch.text
    read = world["client"].get(
        f"/api/design-workshop-oversight/assigned/{wid}", headers=_as(world, "ad")
    )
    body = read.json()
    assert body["mayRecordFeedback"] is True
    assert body["status"] == "PRE_SUBMISSION"
    assert sorted(row["actorPost"] for row in body["inspectionFeedback"]) == [
        "ASSISTANT_DIRECTOR",
        "REGIONAL_DIRECTOR",
        "REGIONAL_DIRECTOR",
    ]


def test_a_director_without_the_post_or_on_a_closed_report_is_refused(world):
    stranger = world["client"].post(
        f"/api/design-workshop-oversight/assigned/{_wid(world, 'officer')}/feedback",
        json={"note": "Hello."},
        headers=_as(world, "stranger_ad"),
    )
    assert stranger.status_code == 404
    closed = world["client"].post(
        f"/api/design-workshop-oversight/assigned/{_wid(world, 'officer_closed')}/feedback",
        json={"note": "Hello."},
        headers=_as(world, "ad"),
    )
    assert closed.status_code == 422


# --------------------------------------------------------------------------------------
# C13: a copy per hand-in
# --------------------------------------------------------------------------------------


def test_handing_in_keeps_a_copy_of_the_round(world):
    wid = _wid(world, "hand_in")
    handed = world["client"].patch(
        f"/api/design-workshops/{wid}",
        json={"status": "PRE_SUBMISSION"},
        headers=_as(world, "designer"),
    )
    assert handed.status_code == 200, handed.text
    assert handed.json()["submissionRound"] == 1
    read = world["client"].get(
        f"/api/design-workshop-approvals/{wid}", headers=_as(world, "ministry")
    )
    assert [s["round"] for s in read.json()["roundSnapshots"]] == [1]
    copy = world["client"].get(
        f"/api/design-workshop-approvals/{wid}/rounds/1", headers=_as(world, "ministry")
    )
    assert copy.status_code == 200 and copy.json()["round"] == 1
    assert isinstance(copy.json()["stages"], dict)
    missing = world["client"].get(
        f"/api/design-workshop-approvals/{wid}/rounds/7", headers=_as(world, "ministry")
    )
    assert missing.status_code == 404
    seen = world["client"].get(
        f"/api/design-workshop-inspections/{wid}/rounds/1", headers=_as(world, "inspector")
    )
    assert seen.status_code == 200


# --------------------------------------------------------------------------------------
# B4, A23
# --------------------------------------------------------------------------------------


def test_the_inspection_list_groups_and_scores_its_rows(world):
    client = world["client"]
    whole = client.get(
        "/api/design-workshop-inspections",
        params={"search": world["tag"], "pageSize": 100},
        headers=_as(world, "inspector2"),
    )
    assert whole.status_code == 200, whole.text
    counts = whole.json()["groupCounts"]
    assert counts["withDesigners"] >= 1 and counts["awaiting"] >= 1
    assert all("readiness" in row for row in whole.json()["items"])
    waiting = client.get(
        "/api/design-workshop-inspections",
        params={"search": world["tag"], "group": "withDesigners", "pageSize": 100},
        headers=_as(world, "inspector2"),
    )
    assert {row["status"] for row in waiting.json()["items"]} == {"NEEDS_REVISION"}
    ranked = client.get(
        "/api/design-workshop-inspections",
        params={"search": world["tag"], "sort": "complete"},
        headers=_as(world, "inspector2"),
    )
    assert ranked.status_code == 200
    refused = client.get(
        "/api/design-workshop-inspections", params={"group": "x"}, headers=_as(world, "inspector2")
    )
    assert refused.status_code == 422


def test_the_monitored_list_scores_its_rows(world):
    answer = world["client"].get(
        "/api/design-workshop-oversight/assigned",
        params={"sort": "oldest"},
        headers=_as(world, "ad"),
    )
    assert answer.status_code == 200, answer.text
    assert all("readiness" in row for row in answer.json()["items"])


def test_the_approver_reads_the_workshops_files(world):
    answer = world["client"].get(
        f"/api/design-workshop-approvals/{_wid(world, 'report')}/media",
        headers=_as(world, "ministry"),
    )
    assert answer.status_code == 200, answer.text
    refused = world["client"].get(
        f"/api/design-workshop-approvals/{_wid(world, 'report')}/media",
        headers=_as(world, "designer"),
    )
    assert refused.status_code == 403


# --------------------------------------------------------------------------------------
# A22: an unchanged report is served again
# --------------------------------------------------------------------------------------


def test_an_unchanged_report_is_served_from_the_stored_file(world, monkeypatch):
    from app.api.routes import design_workshop_approvals as route
    from app.services import s3

    stored: dict[str, bytes] = {}
    builds: list[int] = []

    async def fake_inputs(*_args, **_kwargs):
        return object(), object(), [], "DCH_STANDARD"

    def fake_render(*_args, **_kwargs):
        builds.append(1)
        return b"%PDF-fake", [], 3

    monkeypatch.setattr(route, "_report_inputs", fake_inputs)
    monkeypatch.setattr(route, "render_report", fake_render)
    monkeypatch.setattr(s3, "put_object_bytes", lambda key, data, mime: stored.__setitem__(key, data))
    monkeypatch.setattr(s3, "get_object_bytes", lambda key, max_bytes=None: stored[key])

    url = f"/api/design-workshop-approvals/{_wid(world, 'report')}/report"
    first = world["client"].post(
        url, json={"formats": ["PDF"], "record": True}, headers=_as(world, "ministry")
    )
    assert first.status_code == 200, first.text
    assert "x-report-reused-from" not in first.headers
    second = world["client"].post(
        url, json={"formats": ["PDF"], "record": True}, headers=_as(world, "ministry")
    )
    assert second.status_code == 200
    assert second.headers.get("x-report-reused-from")
    assert second.headers["x-report-export-id"] == first.headers["x-report-export-id"]
    assert second.content == b"%PDF-fake"
    fresh = world["client"].post(
        url, json={"formats": ["PDF"], "record": True, "fresh": True}, headers=_as(world, "ministry")
    )
    assert "x-report-reused-from" not in fresh.headers
    assert len(builds) == 2
    other_format = world["client"].post(
        url, json={"formats": ["DOCX"], "record": True}, headers=_as(world, "ministry")
    )
    assert "x-report-reused-from" not in other_format.headers


def test_the_rows_behind_the_answers(world):
    """Read back AFTER the client has closed — conftest's rule: no database call while it is alive."""
    world["client"].__exit__(None, None, None)
    world["client"] = _Closed()
    send_back = asyncio.run(_read_back(_wid(world, "send_batch")))
    assert len(send_back["logs"]) == 1
    exports = asyncio.run(_read_back(_wid(world, "report")))["exports"]
    assert len(exports) == 3
    assert all(row.inputFingerprint for row in exports)
    assert sum(1 for row in exports if row.storageKey) == 3
    snapshots = asyncio.run(_read_back(_wid(world, "hand_in")))["snapshots"]
    assert [row.round for row in snapshots] == [1]


class _Closed:
    def __exit__(self, *_args: Any) -> None:
        return None
