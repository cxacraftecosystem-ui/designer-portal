import os
import uuid
from typing import Any

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core.db import db
from app.core.deps import ROLE_RANK
from app.core.security import create_access_token, hash_password

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.skipif(
        "INSPECTOR" not in ROLE_RANK,
        reason="the INSPECTOR tier has not reached deps.ROLE_RANK yet; see the other lane",
    ),
    pytest.mark.anyio,
]

PASSWORD = "inspector-test-password"

STAGE_1 = "WORKSHOP_SETUP"

ACCOUNTS: tuple[tuple[str, str, str], ...] = (
    ("admin", "ADMIN", "Inspection Admin"),
    ("creator", "ADMIN", "Workshop Creator"),
    ("inspector", "INSPECTOR", "Assigned Inspector"),
    ("elsewhere", "INSPECTOR", "Inspector On Another Workshop"),
    ("idle", "INSPECTOR", "Unassigned Inspector"),
    ("colleague", "DESIGNER", "Co-designer On The Workshop"),
    ("outsider", "DESIGNER", "Unrelated Designer"),
    ("professor", "PROFESSOR", "Senior Professor"),
    ("barred", "INSPECTOR", "Suspended Inspector"),
    ("selfinspector", "INSPECTOR", "Inspector Who Made One"),
    ("workedonit", "INSPECTOR", "Inspector Who Worked On One"),
)

BARRED_SLUG = "barred"


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    title_token = f"Pattachitra{uuid.uuid4().hex[:8]}"

    def address(slug: str) -> str:
        domain = "barred.example.org" if slug == BARRED_SLUG else "example.org"
        return f"dwinspector-{slug}-{stamp}@{domain}".lower()

    people: dict[str, Any] = {}
    await db.connect()
    try:
        for slug, role, name in ACCOUNTS:
            people[slug] = await db.user.create(data={
                "email": address(slug),
                "name": name,
                "role": role,
                "passwordHash": hash_password(PASSWORD),
            })
        await db.accessroster.create(data={"email": address(BARRED_SLUG), "status": "SUSPENDED"})
        for slug, role, _name in ACCOUNTS:
            if role == "DESIGNER":
                await db.designerroster.create(data={
                    "email": address(slug),
                    "fullName": f"Roster row for {slug}",
                    "institution": "Directorate of Handicrafts",
                    "isActive": True,
                    "addedById": people["admin"].id,
                })

        self_made = await db.designworkshop.create(data={
            "title": f"Ikat, made by its own inspector {stamp}",
            "createdById": people["selfinspector"].id,
        })
        worked_on = await db.designworkshop.create(data={
            "title": f"Ikat, worked on by its inspector {stamp}",
            "createdById": people["creator"].id,
        })
        await db.designworkshopviewer.create(data={
            "designWorkshopId": worked_on.id,
            "userId": people["workedonit"].id,
            "grantedById": people["admin"].id,
        })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "people": people,
            "address": address,
            "stamp": stamp,
            "title_token": title_token,
            "self_made_workshop": self_made.id,
            "worked_on_workshop": worked_on.id,
        }


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(world['people'][slug].id)}"}


def _make_workshop(world: dict[str, Any], title: str) -> str:
    response = world["client"].post(
        "/api/design-workshops", json={"title": title}, headers=_headers(world, "creator")
    )
    assert response.status_code == 201, response.text
    return response.json()["id"]


def _assign(world: dict[str, Any], workshop_id: str, slugs: list[str], *, as_slug: str = "admin"):
    return world["client"].put(
        f"/api/design-workshop-inspections/{workshop_id}/inspectors",
        json={"userIds": [world["people"][s].id for s in slugs]},
        headers=_headers(world, as_slug),
    )


def _grant_viewer(world: dict[str, Any], workshop_id: str, slugs: list[str]):
    return world["client"].put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [world["people"][s].id for s in slugs]},
        headers=_headers(world, "admin"),
    )



async def test_an_inspector_with_no_scope_row_sees_an_empty_list(world, client):
    workshop_id = _make_workshop(world, "Ikat, inspected by somebody else")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    response = client.get("/api/design-workshop-inspections", headers=_headers(world, "idle"))
    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["items"] == []
    assert payload["total"] == 0


async def test_an_inspector_with_no_scope_row_cannot_open_a_workshop(world, client):
    workshop_id = _make_workshop(world, "Ikat, closed to the unassigned")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    response = client.get(
        f"/api/design-workshop-inspections/{workshop_id}", headers=_headers(world, "idle")
    )
    assert response.status_code == 404, response.text
    assert response.json()["detail"] == "Record not found"


async def test_an_inspector_assigned_elsewhere_is_refused_this_workshop(world, client):
    theirs = _make_workshop(world, "Ikat, theirs")
    mine = _make_workshop(world, "Ikat, not theirs")
    assert _assign(world, theirs, ["elsewhere"]).status_code == 200
    assert _assign(world, mine, ["inspector"]).status_code == 200

    response = client.get(
        f"/api/design-workshop-inspections/{mine}", headers=_headers(world, "elsewhere")
    )
    assert response.status_code == 404, response.text

    listed = client.get("/api/design-workshop-inspections", headers=_headers(world, "elsewhere"))
    ids = [item["id"] for item in listed.json()["items"]]
    assert theirs in ids
    assert mine not in ids



async def test_an_assigned_inspector_opens_the_workshop_and_is_told_it_is_read_only(world, client):
    workshop_id = _make_workshop(world, "Ikat, under inspection")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    response = client.get(
        f"/api/design-workshop-inspections/{workshop_id}", headers=_headers(world, "inspector")
    )
    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["id"] == workshop_id
    assert payload["readOnly"] is True
    assert "stages" in payload
    assert "completeness" in payload


async def test_the_inspection_list_honours_the_scope(world, client):
    workshop_id = _make_workshop(world, "Ikat, listed for its inspector")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    response = client.get("/api/design-workshop-inspections", headers=_headers(world, "inspector"))
    assert response.status_code == 200, response.text
    assert workshop_id in [item["id"] for item in response.json()["items"]]


async def test_the_scope_survives_a_search(world, client):
    marker = world["title_token"]
    mine = _make_workshop(world, f"{marker} mine to inspect")
    theirs = _make_workshop(world, f"{marker} not mine")
    assert _assign(world, mine, ["inspector"]).status_code == 200
    assert _assign(world, theirs, ["elsewhere"]).status_code == 200

    response = client.get(
        "/api/design-workshop-inspections",
        params={"search": marker},
        headers=_headers(world, "inspector"),
    )
    assert response.status_code == 200, response.text
    ids = [item["id"] for item in response.json()["items"]]
    assert mine in ids, "the search dropped the scope's own workshop"
    assert theirs not in ids, "the search widened the scope past what was assigned"


async def test_a_soft_deleted_workshop_is_gone_from_the_inspection_surface(world, client):
    workshop_id = _make_workshop(world, "Ikat, soft deleted")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200
    assert (
        client.delete(
            f"/api/design-workshops/{workshop_id}", headers=_headers(world, "admin")
        ).status_code
        == 204
    )

    detail = client.get(
        f"/api/design-workshop-inspections/{workshop_id}", headers=_headers(world, "inspector")
    )
    assert detail.status_code == 404, detail.text

    listed = client.get("/api/design-workshop-inspections", headers=_headers(world, "inspector"))
    assert workshop_id not in [item["id"] for item in listed.json()["items"]]

    roster = client.get(
        f"/api/design-workshop-inspections/{workshop_id}/inspectors",
        headers=_headers(world, "admin"),
    )
    assert roster.status_code == 200, roster.text
    assert [row["userId"] for row in roster.json()["inspectors"]] == [
        world["people"]["inspector"].id
    ]



async def test_an_inspector_cannot_delete_the_workshop_they_inspect(world, client):
    workshop_id = _make_workshop(world, "Ikat, undeletable by its inspector")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    response = client.delete(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "inspector")
    )
    assert response.status_code == 403, response.text

    assert (
        client.get(
            f"/api/design-workshop-inspections/{workshop_id}", headers=_headers(world, "inspector")
        ).status_code
        == 200
    )


async def test_an_inspector_cannot_generate_the_report(world, client):
    workshop_id = _make_workshop(world, "Ikat, unreportable by its inspector")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    response = client.post(
        f"/api/design-workshops/{workshop_id}/report",
        json={"formats": ["DOCX"]},
        headers=_headers(world, "inspector"),
    )
    assert response.status_code == 404, response.text


async def test_an_inspector_cannot_write_the_export_ledger(world, client):
    workshop_id = _make_workshop(world, "Ikat, no ledger row from an inspector")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    response = client.post(
        f"/api/design-workshops/{workshop_id}/exports",
        json={
            "format": "DOCX",
            "templateId": "default",
            "fileName": "inspector.docx",
            "generatedAt": "2026-08-27T00:00:00Z",
        },
        headers=_headers(world, "inspector"),
    )
    assert response.status_code == 404, response.text


async def test_an_inspector_cannot_save_a_stage(world, client):
    workshop_id = _make_workshop(world, "Ikat, unwritable by its inspector")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    response = client.put(
        f"/api/design-workshops/{workshop_id}/stages/{STAGE_1}",
        json={"entries": []},
        headers=_headers(world, "inspector"),
    )
    assert response.status_code == 403, response.text


async def test_an_inspector_cannot_regrant_access_to_anybody(world, client):
    workshop_id = _make_workshop(world, "Ikat, no onward grants")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    viewers = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [world["people"]["outsider"].id]},
        headers=_headers(world, "inspector"),
    )
    assert viewers.status_code == 403, viewers.text

    onward = _assign(world, workshop_id, ["inspector", "elsewhere"], as_slug="inspector")
    assert onward.status_code == 403, onward.text


async def test_the_inspection_payload_carries_no_transcripts(world, client):
    workshop_id = _make_workshop(world, "Ikat, no recordings for the inspector")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    response = client.get(
        f"/api/design-workshop-inspections/{workshop_id}", headers=_headers(world, "inspector")
    )
    assert response.status_code == 200, response.text
    assert "transcripts" not in response.json()

    assert _grant_viewer(world, workshop_id, ["colleague"]).status_code == 200
    designer_read = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "colleague")
    )
    assert designer_read.status_code == 200, designer_read.text
    assert "transcripts" in designer_read.json()



async def test_an_inspection_row_is_invisible_on_the_designer_surface(world, client):
    workshop_id = _make_workshop(world, "Ikat, inspection only")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    detail = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "inspector")
    )
    assert detail.status_code == 404, detail.text

    listed = client.get("/api/design-workshops", headers=_headers(world, "inspector"))
    assert listed.status_code == 200, listed.text
    assert workshop_id not in [item["id"] for item in listed.json()["items"]]


async def test_a_viewer_row_is_invisible_on_the_inspection_surface(world, client):
    workshop_id = _make_workshop(world, "Ikat, viewer is not an inspector")
    assert _grant_viewer(world, workshop_id, ["colleague"]).status_code == 200
    assert (
        client.get(
            f"/api/design-workshops/{workshop_id}", headers=_headers(world, "colleague")
        ).status_code
        == 200
    )

    refused = client.get(
        f"/api/design-workshop-inspections/{workshop_id}", headers=_headers(world, "colleague")
    )
    assert refused.status_code == 403, refused.text
    assert "for inspectors" in refused.json()["detail"]


@pytest.mark.parametrize("slug", ["colleague", "professor"])
async def test_the_inspection_surface_refuses_a_role_that_can_hold_no_inspection(world, client, slug):
    response = client.get("/api/design-workshop-inspections", headers=_headers(world, slug))
    assert response.status_code == 403, response.text
    assert "for inspectors" in response.json()["detail"]


async def test_an_admin_with_no_inspection_sees_an_empty_list_and_not_everything(world, client):
    workshop_id = _make_workshop(world, "Ikat, inspected by somebody else")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    response = client.get("/api/design-workshop-inspections", headers=_headers(world, "admin"))
    assert response.status_code == 200, response.text
    assert workshop_id not in [item["id"] for item in response.json()["items"]]
    detail = client.get(
        f"/api/design-workshop-inspections/{workshop_id}", headers=_headers(world, "admin")
    )
    assert detail.status_code == 404, "an admin with no inspection row read the workshop by rank"



@pytest.mark.parametrize("slug", ["colleague", "inspector", "professor"])
async def test_only_an_admin_assigns_an_inspection(world, client, slug):
    workshop_id = _make_workshop(world, f"Ikat, admin only {slug}")
    assert _grant_viewer(world, workshop_id, ["colleague"]).status_code == 200

    response = _assign(world, workshop_id, ["elsewhere"], as_slug=slug)
    assert response.status_code == 403, response.text

    roster = client.get(
        f"/api/design-workshop-inspections/{workshop_id}/inspectors",
        headers=_headers(world, "admin"),
    )
    assert roster.json()["inspectors"] == []


async def test_the_workshops_creator_may_inspect_it_when_they_wrote_none_of_it(world, client):
    response = _assign(world, world["self_made_workshop"], ["selfinspector"])
    assert response.status_code == 200, response.text
    assert [row["userId"] for row in response.json()["inspectors"]] == [
        world["people"]["selfinspector"].id
    ]


async def test_a_co_designer_on_the_workshop_cannot_inspect_it(world, client):
    response = _assign(world, world["worked_on_workshop"], ["workedonit"])
    assert response.status_code == 409, response.text
    detail = response.json()["detail"]
    assert "Inspector Who Worked On One" in detail
    assert "holds designer access to this workshop" in detail
    assert "nobody inspects or supervises work they authored" in detail
    assert "Nothing was changed." in detail


async def test_a_designer_cannot_be_assigned_an_inspection(world, client):
    workshop_id = _make_workshop(world, "Ikat, wrong tier")

    response = _assign(world, workshop_id, ["outsider"])
    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    assert "DESIGNER" in detail
    assert "Nothing was changed." in detail


async def test_a_barred_inspector_is_refused(world, client):
    workshop_id = _make_workshop(world, "Ikat, barred inspector")

    response = _assign(world, workshop_id, ["barred"])
    assert response.status_code == 422, response.text
    assert "barred by the platform access list" in response.json()["detail"]


async def test_an_unknown_id_refuses_the_whole_call(world, client):
    workshop_id = _make_workshop(world, "Ikat, one bad id")

    response = client.put(
        f"/api/design-workshop-inspections/{workshop_id}/inspectors",
        json={"userIds": [world["people"]["inspector"].id, "cmnosuchaccount0000000000"]},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 422, response.text
    assert "No account exists with" in response.json()["detail"]

    roster = client.get(
        f"/api/design-workshop-inspections/{workshop_id}/inspectors",
        headers=_headers(world, "admin"),
    )
    assert roster.json()["inspectors"] == [], "the good half of a refused call was applied anyway"



async def test_re_saving_an_unchanged_set_does_not_restamp_the_assignment(world, client):
    workshop_id = _make_workshop(world, "Ikat, saved twice")

    first = _assign(world, workshop_id, ["inspector"])
    assert first.status_code == 200, first.text
    stamped = first.json()["inspectors"][0]["assignedAt"]
    assert stamped

    second = _assign(world, workshop_id, ["inspector"])
    assert second.status_code == 200, second.text
    assert second.json()["inspectors"][0]["assignedAt"] == stamped


async def test_removing_an_inspector_ends_their_access_immediately(world, client):
    workshop_id = _make_workshop(world, "Ikat, inspection ended")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200
    assert (
        client.get(
            f"/api/design-workshop-inspections/{workshop_id}", headers=_headers(world, "inspector")
        ).status_code
        == 200
    )

    assert _assign(world, workshop_id, []).status_code == 200

    after = client.get(
        f"/api/design-workshop-inspections/{workshop_id}", headers=_headers(world, "inspector")
    )
    assert after.status_code == 404, after.text
    listed = client.get("/api/design-workshop-inspections", headers=_headers(world, "inspector"))
    assert workshop_id not in [item["id"] for item in listed.json()["items"]]


async def test_the_roster_names_the_person_it_assigns(world, client):
    workshop_id = _make_workshop(world, "Ikat, named inspector")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    response = client.get(
        f"/api/design-workshop-inspections/{workshop_id}/inspectors",
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    row = response.json()["inspectors"][0]
    assert row["userId"] == world["people"]["inspector"].id
    assert row["name"] == "Assigned Inspector"
    assert row["email"] == world["address"]("inspector")
    assert row["role"] == "INSPECTOR"


async def test_the_picker_offers_inspectors_and_excludes_the_barred(world, client):
    response = client.get(
        "/api/design-workshop-inspections/eligible-inspectors",
        params={"search": world["stamp"]},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["truncated"] is False

    offered = {u["id"] for u in payload["users"]}
    people = world["people"]
    assert people["inspector"].id in offered
    assert people["idle"].id in offered
    assert people["elsewhere"].id in offered
    assert people["outsider"].id not in offered
    assert people["professor"].id not in offered
    assert people["creator"].id in offered
    assert people["admin"].id not in offered, "the picker offered the caller to themselves"
    assert people["barred"].id not in offered


async def test_the_literal_picker_path_is_not_swallowed_by_the_workshop_id_route(world, client):
    response = client.get(
        "/api/design-workshop-inspections/eligible-inspectors", headers=_headers(world, "admin")
    )
    assert response.status_code == 200, response.text
    assert "users" in response.json()



def _hand_in(world: dict[str, Any], workshop_id: str, *, as_slug: str = "creator"):
    return world["client"].patch(
        f"/api/design-workshops/{workshop_id}",
        json={"status": "PRE_SUBMISSION"},
        headers=_headers(world, as_slug),
    )


def _set_status(world: dict[str, Any], workshop_id: str, status: str):
    return world["client"].patch(
        f"/api/design-workshops/{workshop_id}",
        json={"status": status},
        headers=_headers(world, "creator"),
    )


def _feedback(world: dict[str, Any], workshop_id: str, slug: str, **body):
    return world["client"].post(
        f"/api/design-workshop-inspections/{workshop_id}/feedback",
        json=body,
        headers=_headers(world, slug),
    )


def _send_back(world: dict[str, Any], workshop_id: str, slug: str, **body):
    return world["client"].post(
        f"/api/design-workshop-inspections/{workshop_id}/send-back",
        json=body,
        headers=_headers(world, slug),
    )


def _register(world: dict[str, Any], workshop_id: str, slug: str = "inspector") -> list[dict]:
    response = world["client"].get(
        f"/api/design-workshop-inspections/{workshop_id}", headers=_headers(world, slug)
    )
    assert response.status_code == 200, response.text
    return response.json()["inspectionFeedback"]


async def test_an_assigned_inspector_files_a_correction_suggestion_and_it_reads_back(world, client):
    workshop_id = _make_workshop(world, "Ikat, with a note from its inspector")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200
    assert _hand_in(world, workshop_id).status_code == 200

    response = _feedback(
        world, workshop_id, "inspector", note="Stage 14's cost sheet does not add up."
    )
    assert response.status_code == 201, response.text
    body = response.json()
    assert body["readOnly"] is True
    assert body["mayRecordFeedback"] is True
    assert body["inspectionFeedbackTruncated"] is False
    assert body["status"] == "PRE_SUBMISSION"
    assert body["submissionRound"] == 1
    [row] = body["inspectionFeedback"]
    assert row["note"] == "Stage 14's cost sheet does not add up."
    assert row["round"] == 1, "a suggestion is filed against the cycle the report is in"
    assert row["sentBack"] is False
    assert row["actorId"] == world["people"]["inspector"].id
    assert row["actorName"] == "Assigned Inspector", (
        "the officer's name is missing from the register, which is what a forgotten "
        "include={'actor': True} looks like on screen"
    )


async def test_an_inspector_without_a_row_is_404_on_both_write_doors(world, client):
    workshop_id = _make_workshop(world, "Ikat, closed to an unassigned officer")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200
    assert _hand_in(world, workshop_id).status_code == 200

    assert _feedback(world, workshop_id, "idle", note="I was not asked.").status_code == 404
    assert _send_back(world, workshop_id, "idle", note="I was not asked.").status_code == 404
    assert _register(world, workshop_id) == [], "a refused write must write nothing"


@pytest.mark.parametrize("status_token", ["DRAFT", "IN_PROGRESS", "COMPLETE", "ARCHIVED"])
async def test_a_suggestion_on_a_report_nobody_handed_in_is_refused_and_writes_no_row(
    world, client, status_token
):
    workshop_id = _make_workshop(world, f"Ikat, not yet handed in ({status_token})")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200
    if status_token != "DRAFT":
        assert _set_status(world, workshop_id, status_token).status_code == 200
    before = _register(world, workshop_id)

    response = _feedback(world, workshop_id, "inspector", note="This is premature.")
    assert response.status_code == 422, response.text
    assert "has not been handed in" in response.json()["detail"]
    assert _register(world, workshop_id) == before, "a refused suggestion left a row behind"

    sent_back = _send_back(world, workshop_id, "inspector", note="This is premature.")
    assert sent_back.status_code == 422, sent_back.text
    assert _register(world, workshop_id) == before


async def test_a_send_back_moves_the_status_writes_the_row_and_names_the_officer(world, client):
    workshop_id = _make_workshop(world, "Ikat, sent back to its designers")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200
    assert _hand_in(world, workshop_id).status_code == 200

    response = _send_back(
        world, workshop_id, "inspector", note="The cost sheet and the cover date disagree."
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["status"] == "NEEDS_REVISION"
    assert body["submissionRound"] == 1, "a send-back does not spend a round; handing in does"
    assert body["reviewNotes"] == "The cost sheet and the cover date disagree."
    assert body["reviewedById"] == world["people"]["inspector"].id
    assert body["reviewedAt"]
    [row] = body["inspectionFeedback"]
    assert row["sentBack"] is True
    assert row["actorName"] == "Assigned Inspector"


async def test_a_second_officer_sending_back_files_a_row_and_does_not_move_the_status(world, client):
    workshop_id = _make_workshop(world, "Ikat, sent back twice")
    assert _assign(world, workshop_id, ["inspector", "elsewhere"]).status_code == 200
    assert _hand_in(world, workshop_id).status_code == 200
    assert _send_back(world, workshop_id, "inspector", note="First officer.").status_code == 200

    second = _send_back(world, workshop_id, "elsewhere", note="Second officer, same round.")
    assert second.status_code == 200, second.text
    body = second.json()
    assert body["status"] == "NEEDS_REVISION"
    assert body["submissionRound"] == 1
    assert len(body["inspectionFeedback"]) == 2
    assert {row["round"] for row in body["inspectionFeedback"]} == {1}


async def test_a_blank_note_is_refused_on_both_write_doors(world, client):
    workshop_id = _make_workshop(world, "Ikat, refused a blank note")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200
    assert _hand_in(world, workshop_id).status_code == 200

    assert _feedback(world, workshop_id, "inspector", note="   ").status_code == 422
    blank = _send_back(world, workshop_id, "inspector", note="   ")
    assert blank.status_code == 422, blank.text
    assert "Comments are required" in blank.json()["detail"]
    assert _register(world, workshop_id) == []


async def test_a_designers_edit_is_the_resubmission_and_a_replay_is_not(world, client):
    workshop_id = _make_workshop(world, "Ikat, corrected and handed back in")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200
    assert _grant_viewer(world, workshop_id, ["colleague"]).status_code == 200
    assert _hand_in(world, workshop_id).status_code == 200
    assert _send_back(world, workshop_id, "inspector", note="Name the venue.").status_code == 200

    body = {
        "entries": [
            {"entityKey": "workshopSetup", "data": {"venue": "Weavers' Service Centre, Bargarh"}}
        ]
    }
    saved = client.put(
        f"/api/design-workshops/{workshop_id}/stages/{STAGE_1}",
        json=body,
        headers=_headers(world, "colleague"),
    )
    assert saved.status_code == 200, saved.text

    detail = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "colleague")
    ).json()
    assert detail["status"] == "PRE_SUBMISSION", "the edit did not hand the report back in"
    assert detail["submissionRound"] == 2
    assert detail["reviewNotes"] is None
    assert detail["reviewedById"] is None
    assert len(detail["inspectionFeedback"]) == 1

    assert _send_back(world, workshop_id, "inspector", note="And the date.").status_code == 200
    replay = client.put(
        f"/api/design-workshops/{workshop_id}/stages/{STAGE_1}",
        json=body,
        headers=_headers(world, "colleague"),
    )
    assert replay.status_code == 200, replay.text
    after = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "colleague")
    ).json()
    assert after["status"] == "NEEDS_REVISION", (
        "a byte-identical replay of the previous body handed the report back in; `updates` was "
        "read as a change list, which it is not"
    )
    assert after["submissionRound"] == 2, "the replay spent a submission round"

    changed = client.put(
        f"/api/design-workshops/{workshop_id}/stages/{STAGE_1}",
        json={
            "entries": [
                {"entityKey": "workshopSetup", "data": {"venue": "Weavers' Service Centre, Barpali"}}
            ]
        },
        headers=_headers(world, "colleague"),
    )
    assert changed.status_code == 200, changed.text
    final = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "colleague")
    ).json()
    assert final["status"] == "PRE_SUBMISSION"
    assert final["submissionRound"] == 3


async def test_the_designers_own_read_carries_the_register_and_refuses_the_box(world, client):
    workshop_id = _make_workshop(world, "Ikat, read by its designer")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200
    assert _hand_in(world, workshop_id).status_code == 200
    assert _feedback(world, workshop_id, "inspector", note="Check the cover.").status_code == 201

    detail = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "creator")
    ).json()
    assert detail["mayRecordFeedback"] is False
    assert detail["inspectionFeedbackTruncated"] is False
    [row] = detail["inspectionFeedback"]
    assert row["note"] == "Check the cover."
    assert row["actorName"] == "Assigned Inspector"


def test_no_route_anywhere_updates_or_deletes_a_correction_suggestion():
    from app.api.router import api_router

    for route in api_router.routes:
        path = getattr(route, "path", "")
        if "feedback" not in path:
            continue
        assert not (set(getattr(route, "methods", set())) & {"PATCH", "PUT", "DELETE"}), (
            f"{path} can edit or delete a correction suggestion. The register is append-only: an "
            f"officer who changes their mind files another row."
        )
