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
from prisma import Json

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
    "resubmit": "NEEDS_REVISION",
    "race": "PRE_SUBMISSION",
    "refs": "PRE_SUBMISSION",
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
            "snapshots": await db.dwroundsnapshot.find_many(
                where={"designWorkshopId": workshop_id}
            ),
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


def test_a_stage_save_that_resubmits_keeps_a_copy_of_the_round(world):
    wid = _wid(world, "resubmit")
    saved = world["client"].put(
        f"/api/design-workshops/{wid}/stages/WORKSHOP_SETUP",
        json={"entries": [{"entityKey": "workshopSetup", "data": {"venue": "Barpali hall"}}]},
        headers=_as(world, "designer"),
    )
    assert saved.status_code == 200, saved.text
    read = world["client"].get(
        f"/api/design-workshop-approvals/{wid}", headers=_as(world, "ministry")
    )
    assert read.json()["status"] == "PRE_SUBMISSION"
    assert [s["round"] for s in read.json()["roundSnapshots"]] == [2]
    copy = world["client"].get(
        f"/api/design-workshop-approvals/{wid}/rounds/2", headers=_as(world, "ministry")
    )
    assert copy.status_code == 200 and copy.json()["round"] == 2


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
    deleted: list[str] = []

    async def fake_inputs(*_args, **_kwargs):
        return object(), object(), [], "DCH_STANDARD"

    def fake_render(*_args, **_kwargs):
        builds.append(1)
        return b"%PDF-fake", [], 3

    monkeypatch.setattr(route, "_report_inputs", fake_inputs)
    monkeypatch.setattr(route, "render_report", fake_render)
    monkeypatch.setattr(
        s3, "put_object_bytes", lambda key, data, mime: stored.__setitem__(key, data)
    )
    monkeypatch.setattr(s3, "get_object_bytes", lambda key, max_bytes=None: stored[key])
    monkeypatch.setattr(s3, "delete_object", deleted.append)

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
        url,
        json={"formats": ["PDF"], "record": True, "fresh": True},
        headers=_as(world, "ministry"),
    )
    assert "x-report-reused-from" not in fresh.headers
    assert len(builds) == 2
    # The rebuild replaced the first PDF: only the newest stored file per format is kept.
    assert deleted == [f"reports/{_wid(world, 'report')}/{first.headers['x-report-export-id']}.pdf"]
    other_format = world["client"].post(
        url, json={"formats": ["DOCX"], "record": True}, headers=_as(world, "ministry")
    )
    assert "x-report-reused-from" not in other_format.headers
    assert len(deleted) == 1  # another format is not a newer build of the PDF


def test_the_rows_behind_the_answers(world):
    """Read back AFTER the client has closed — conftest's rule: no database call while it is alive."""
    world["client"].__exit__(None, None, None)
    world["client"] = _Closed()
    send_back = asyncio.run(_read_back(_wid(world, "send_batch")))
    assert len(send_back["logs"]) == 1
    exports = asyncio.run(_read_back(_wid(world, "report")))["exports"]
    assert len(exports) == 3
    assert all(row.inputFingerprint for row in exports)
    assert sum(1 for row in exports if row.storageKey) == 2
    snapshots = asyncio.run(_read_back(_wid(world, "hand_in")))["snapshots"]
    assert [row.round for row in snapshots] == [1]


class _Closed:
    def __exit__(self, *_args: object) -> None:
        return None


# --------------------------------------------------------------------------------------
# Service-level checks. They run after the client has closed (conftest's rule), each on its own
# connection.
# --------------------------------------------------------------------------------------


def _with_db(work):
    async def run():
        await db.connect()
        try:
            return await work()
        finally:
            await db.disconnect()

    return asyncio.run(run())


def test_the_snapshot_purge_reaches_past_any_batch_of_deleted_workshops(world):
    """More than 500 workshops deleted over a year ago: every one's copies go in one pass."""
    from datetime import UTC, datetime, timedelta

    from app.services import design_workshop_review_aids as aids

    owner = world["people"]["designer"].id
    long_ago = datetime.now(UTC) - timedelta(days=400)
    recently = datetime.now(UTC) - timedelta(days=30)
    tag = uuid.uuid4().hex[:8]

    async def work():
        old_ids = [f"purge{tag}{n:04d}" for n in range(520)]
        await db.designworkshop.create_many(
            data=[
                {"id": wid, "title": f"Gone {wid}", "createdById": owner, "deletedAt": long_ago}
                for wid in old_ids
            ]
        )
        kept = await db.designworkshop.create(
            data={"title": f"Recent {tag}", "createdById": owner, "deletedAt": recently}
        )
        await db.dwroundsnapshot.create_many(
            data=[
                {"designWorkshopId": wid, "round": 1, "stages": Json({})}
                for wid in [*old_ids, kept.id]
            ]
        )
        await aids.purge_expired_snapshots(force=True)
        left_old = await db.dwroundsnapshot.count(where={"designWorkshopId": {"in": old_ids}})
        left_recent = await db.dwroundsnapshot.count(where={"designWorkshopId": kept.id})
        return left_old, left_recent

    assert _with_db(work) == (0, 1)


def test_stored_reports_are_purged_when_stale_or_their_workshop_is_gone(world, monkeypatch):
    from datetime import UTC, datetime, timedelta

    from app.services import design_workshop_review_aids as aids, s3

    deleted: list[str] = []
    monkeypatch.setattr(s3, "delete_object", deleted.append)
    owner = world["people"]["designer"].id
    tag = uuid.uuid4().hex[:8]

    async def work():
        live = await db.designworkshop.create(data={"title": f"Live {tag}", "createdById": owner})
        gone = await db.designworkshop.create(
            data={"title": f"Gone {tag}", "createdById": owner, "deletedAt": datetime.now(UTC)}
        )
        base = {"format": "PDF", "templateId": "DCH_STANDARD", "fileName": "r.pdf"}
        rows = {
            "fresh": await db.dwreportexport.create(
                data={**base, "designWorkshopId": live.id, "storageKey": f"k/{tag}/fresh"}
            ),
            "stale": await db.dwreportexport.create(
                data={
                    **base,
                    "designWorkshopId": live.id,
                    "storageKey": f"k/{tag}/stale",
                    "generatedAt": datetime.now(UTC) - timedelta(days=20),
                }
            ),
            "orphan": await db.dwreportexport.create(
                data={**base, "designWorkshopId": gone.id, "storageKey": f"k/{tag}/orphan"}
            ),
        }
        await aids.purge_stored_reports()
        return {
            name: (await db.dwreportexport.find_unique(where={"id": row.id})).storageKey
            for name, row in rows.items()
        }

    keys = _with_db(work)
    assert keys == {"fresh": f"k/{tag}/fresh", "stale": None, "orphan": None}
    assert f"k/{tag}/stale" in deleted and f"k/{tag}/orphan" in deleted
    assert f"k/{tag}/fresh" not in deleted


def test_a_note_filed_mid_clearance_wins(world, monkeypatch):
    """The note lands between clear_round's count and its insert: the verdict must not stand."""
    from fastapi import HTTPException

    from app.services import design_workshop_review_aids as aids

    wid = _wid(world, "race")
    inspector = world["people"]["inspector"]

    async def work():
        record = await db.designworkshop.find_unique(where={"id": wid})
        actions = type(db.dwinspectionfeedback)  # slotted: patch the class, not the instance
        real_count = actions.count
        calls = {"n": 0}

        async def count(self, **kwargs):
            calls["n"] += 1
            if calls["n"] == 1:  # the first count runs before the other request's note commits
                await db.dwinspectionfeedback.create(
                    data={
                        "designWorkshopId": wid,
                        "round": record.submissionRound,
                        "actorId": inspector.id,
                        "note": "Filed meanwhile.",
                    }
                )
                return 0
            return await real_count(self, **kwargs)

        monkeypatch.setattr(actions, "count", count)
        try:
            await aids.clear_round(record, inspector)
        except HTTPException as refused:
            status_code = refused.status_code
        else:
            status_code = 200
        monkeypatch.undo()
        left = await db.dwinspectionclearance.count(
            where={"designWorkshopId": wid, "inspectorId": inspector.id}
        )
        return status_code, left

    assert _with_db(work) == (422, 0)


def _artisan_ref_slot() -> tuple[str, str, str]:
    for spec in stages():
        for entity in spec.entities:
            for field in entity.fields:
                if getattr(field, "ref_model", None) == "Artisan":
                    return spec.key, entity.key, field.key
    raise AssertionError("no stage field refers to an artisan")


def test_the_report_fingerprint_follows_the_viewer_and_the_records_it_cites(world):
    from app.services import design_workshop_review_aids as aids

    wid = _wid(world, "refs")
    people = world["people"]
    stage_key, entity_key, field_key = _artisan_ref_slot()

    async def work():
        artisan = await db.artisan.create(
            data={"name": "Sita Devi", "place": "Barpali", "createdById": people["designer"].id}
        )
        await db.dwstageentry.create(
            data={
                "designWorkshopId": wid,
                "stageKey": stage_key,
                "entityKey": entity_key,
                "data": Json({field_key: artisan.id}),
            }
        )
        record = await db.designworkshop.find_unique(where={"id": wid})

        async def digest(viewer):
            return await aids.report_fingerprint(record, fmt="PDF", options={}, viewer=viewer)

        as_ministry = await digest(people["ministry"])
        as_admin = await digest(people["admin"])
        as_designer = await digest(people["designer"])
        await db.artisan.update(where={"id": artisan.id}, data={"name": "Sita Devi Meher"})
        renamed = await digest(people["ministry"])
        return as_ministry, as_admin, as_designer, renamed

    as_ministry, as_admin, as_designer, renamed = _with_db(work)
    assert as_ministry == as_admin  # both unrestricted: one file serves both
    assert as_designer != as_ministry  # a restricted reader's report is their own
    assert renamed != as_ministry  # a renamed artisan is not served stale
