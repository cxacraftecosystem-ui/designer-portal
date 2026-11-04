import os
import uuid
from datetime import datetime
from typing import Any

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services.design_workshop_access import code_check

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]

PASSWORD = "dw-access-request-password"

ACCOUNTS: tuple[tuple[str, str, str], ...] = (
    ("admin", "ADMIN", "Queue Admin"),
    ("creator", "ADMIN", "Workshop Creator"),
    ("requester", "DESIGNER", "Asking Designer"),
    ("second", "DESIGNER", "Other Asking Designer"),
    ("researcher", "RESEARCHER", "Ordinary Researcher"),
)

ROSTERED = ("requester", "second")


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        return f"dwaccess-{slug}-{stamp}@example.org"

    people: dict[str, Any] = {}
    await db.connect()
    try:
        for slug, role, name in ACCOUNTS:
            people[slug] = await db.user.create(
                data={
                    "email": address(slug),
                    "name": name,
                    "role": role,
                    "passwordHash": hash_password(PASSWORD),
                }
            )
        for slug in ROSTERED:
            await db.designerroster.create(
                data={
                    "email": address(slug),
                    "fullName": f"Roster row for {slug}",
                    "institution": "Directorate of Handicrafts",
                    "isActive": True,
                    "addedById": people["admin"].id,
                }
            )
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {"client": client, "people": people, "address": address, "stamp": stamp}

    await db.connect()
    try:
        ours = [person.id for person in people.values()]
        await db.designworkshopaccessrequest.delete_many(where={"requestedById": {"in": ours}})
        await db.designworkshopviewer.delete_many(where={"userId": {"in": ours}})
    finally:
        await db.disconnect()


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(world['people'][slug].id)}"}


def _make_workshop(world: dict[str, Any], title: str) -> str:
    response = world["client"].post(
        "/api/design-workshops",
        json={"title": title},
        headers=_headers(world, "creator"),
    )
    assert response.status_code == 201, response.text
    return response.json()["id"]


def _code_for(workshop_id: str) -> str:
    prefix = f"DPW1:G:{workshop_id.upper()}"
    return f"{prefix}:{code_check(prefix)}"


def _ask(world: dict[str, Any], slug: str, workshop_id: str, **body: Any):
    return world["client"].post(
        "/api/design-workshop-access/requests",
        json={"workshopId": workshop_id, **body},
        headers=_headers(world, slug),
    )


def _queue(world: dict[str, Any], status_filter: str = "ALL") -> dict[str, Any]:
    response = world["client"].get(
        f"/api/design-workshop-access/requests?statusFilter={status_filter}",
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    return response.json()


def _rows_for(world: dict[str, Any], workshop_id: str) -> list[dict[str, Any]]:
    return [row for row in _queue(world)["requests"] if row["workshop"]["id"] == workshop_id]


def _one_row(world: dict[str, Any], workshop_id: str) -> dict[str, Any]:
    rows = _rows_for(world, workshop_id)
    assert len(rows) == 1, rows
    return rows[0]


def _without_access_flag(row: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in row.items() if key != "requesterHasAccess"}


def _viewer_ids(world: dict[str, Any], workshop_id: str) -> set[str]:
    response = world["client"].get(
        f"/api/design-workshops/{workshop_id}/viewers", headers=_headers(world, "admin")
    )
    assert response.status_code == 200, response.text
    return {viewer["userId"] for viewer in response.json()["viewers"]}


def _decide(world: dict[str, Any], request_id: str, decision: str, note: str | None = None):
    body: dict[str, Any] = {"status": decision}
    if note is not None:
        body["note"] = note
    return world["client"].post(
        f"/api/design-workshop-access/requests/{request_id}/decide",
        json=body,
        headers=_headers(world, "admin"),
    )



def test_a_designer_can_ask_to_join_a_workshop_they_scanned(client, world):
    workshop_id = _make_workshop(world, "Scanned join request")
    code = _code_for(workshop_id)
    typed = " ".join(code[index : index + 4] for index in range(0, len(code), 4)).lower()

    response = _ask(world, "requester", workshop_id, scannedCode=typed, note="Rekha asked me")
    assert response.status_code == 202, response.text

    row = _one_row(world, workshop_id)
    assert row["requestedBy"]["id"] == world["people"]["requester"].id
    assert row["status"] == "PENDING"
    assert row["source"] == "SCAN"
    assert row["scannedCode"] == code
    assert row["note"] == "Rekha asked me"
    assert row["requesterHasAccess"] is False
    assert row["decidedBy"] is None


def test_a_valid_code_is_not_what_lets_the_request_through(client, world):
    workshop_id = _make_workshop(world, "Manual join request")
    assert _ask(world, "requester", workshop_id).status_code == 202

    row = _one_row(world, workshop_id)
    assert row["source"] == "MANUAL"
    assert row["scannedCode"] is None


def test_the_upper_case_identifier_a_card_shows_is_still_found(client, world):
    scanned_id = _make_workshop(world, "Upper-case id, scanned")
    typed_id = _make_workshop(world, "Upper-case id, typed off the card")

    scanned = _ask(
        world, "requester", scanned_id.upper(), scannedCode=_code_for(scanned_id)
    )
    assert scanned.status_code == 202, scanned.text
    assert _one_row(world, scanned_id)["source"] == "SCAN"

    typed = _ask(world, "requester", typed_id.upper())
    assert typed.status_code == 202, typed.text
    assert _one_row(world, typed_id)["source"] == "MANUAL"


def test_asking_twice_files_one_request_and_does_not_move_it_up_the_queue(client, world):
    workshop_id = _make_workshop(world, "Replayed join request")
    code = _code_for(workshop_id)

    first = _ask(world, "requester", workshop_id, scannedCode=code)
    assert first.status_code == 202, first.text
    before = _one_row(world, workshop_id)

    second = _ask(world, "requester", workshop_id, scannedCode=code)
    assert second.status_code == 202, second.text
    assert second.json() == first.json()

    after = _one_row(world, workshop_id)
    assert after["id"] == before["id"]
    assert after["createdAt"] == before["createdAt"]


def test_nothing_is_filed_for_somebody_who_can_already_open_the_workshop(client, world):
    workshop_id = _make_workshop(world, "Creator asks about their own")
    response = _ask(world, "creator", workshop_id, scannedCode=_code_for(workshop_id))
    assert response.status_code == 202, response.text
    assert _rows_for(world, workshop_id) == []



def test_asking_about_an_id_that_names_nothing_is_the_same_answer_as_asking_about_a_real_one(
    client, world
):
    real_id = _make_workshop(world, "Real workshop for the oracle test")
    deleted_id = _make_workshop(world, "Deleted workshop for the oracle test")
    deleted = client.delete(
        f"/api/design-workshops/{deleted_id}", headers=_headers(world, "creator")
    )
    assert deleted.status_code in (200, 204), deleted.text

    invented_id = f"cmsdoesnotexist{uuid.uuid4().hex[:10]}"
    unstorable_id = f"cms\x00{uuid.uuid4().hex[:12]}"

    answers = [
        (response.status_code, response.json())
        for response in (
            _ask(world, "second", real_id),
            _ask(world, "second", invented_id),
            _ask(world, "second", deleted_id),
            _ask(world, "second", unstorable_id),
        )
    ]
    assert answers[0][0] == 202, answers[0]
    assert len({repr(answer) for answer in answers}) == 1, answers

    assert len(_rows_for(world, real_id)) == 1
    assert _rows_for(world, invented_id) == []
    assert _rows_for(world, deleted_id) == []


def test_a_code_that_does_not_check_out_is_refused_out_loud(client, world):
    workshop_id = _make_workshop(world, "Refused codes")
    good = _code_for(workshop_id)

    broken = good[:-1] + ("Z" if good[-1] != "Z" else "Y")
    artisan_prefix = f"DPW1:A:{workshop_id.upper()}"
    other_type = f"{artisan_prefix}:{code_check(artisan_prefix)}"
    elsewhere = _code_for(_make_workshop(world, "A different workshop"))

    for code in (broken, other_type, elsewhere, "https://example.org/not-a-code"):
        response = _ask(world, "requester", workshop_id, scannedCode=code)
        assert response.status_code == 422, (code, response.text)

    assert _rows_for(world, workshop_id) == []



def test_a_designer_cannot_read_or_decide_the_queue(client, world):
    workshop_id = _make_workshop(world, "Roles on the queue")
    assert _ask(world, "requester", workshop_id).status_code == 202
    request_id = _one_row(world, workshop_id)["id"]

    listed = client.get(
        "/api/design-workshop-access/requests", headers=_headers(world, "requester")
    )
    assert listed.status_code == 403, listed.text

    decided = client.post(
        f"/api/design-workshop-access/requests/{request_id}/decide",
        json={"status": "GRANTED"},
        headers=_headers(world, "requester"),
    )
    assert decided.status_code == 403, decided.text

    assert _one_row(world, workshop_id)["status"] == "PENDING"
    assert _viewer_ids(world, workshop_id) == set()


def test_the_pending_filter_is_the_default_and_a_decided_row_leaves_it(client, world):
    workshop_id = _make_workshop(world, "Pending then decided")
    assert _ask(world, "requester", workshop_id).status_code == 202
    request_id = _one_row(world, workshop_id)["id"]

    pending = _queue(world, "PENDING")["requests"]
    assert any(row["id"] == request_id for row in pending)

    assert _decide(world, request_id, "DENIED").status_code == 200

    pending = _queue(world, "PENDING")["requests"]
    assert all(row["id"] != request_id for row in pending)
    everything = _queue(world, "ALL")["requests"]
    assert any(row["id"] == request_id and row["status"] == "DENIED" for row in everything)


def test_the_queue_says_when_it_was_not_truncated(client, world):
    assert _queue(world, "ALL")["truncated"] is False



def test_granting_a_request_puts_the_designer_on_the_workshop_for_real(client, world):
    workshop_id = _make_workshop(world, "Granted join request")
    assert _ask(world, "requester", workshop_id, scannedCode=_code_for(workshop_id)).status_code == 202
    request_id = _one_row(world, workshop_id)["id"]

    before = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "requester")
    )
    assert before.status_code == 404, before.text

    decided = _decide(world, request_id, "GRANTED", "second designer on this cluster")
    assert decided.status_code == 200, decided.text
    payload = decided.json()
    assert payload["status"] == "GRANTED"
    assert payload["requesterHasAccess"] is True
    assert payload["decidedBy"]["id"] == world["people"]["admin"].id
    assert payload["decisionNote"] == "second designer on this cluster"

    assert _viewer_ids(world, workshop_id) == {world["people"]["requester"].id}

    after = client.get(f"/api/design-workshops/{workshop_id}", headers=_headers(world, "requester"))
    assert after.status_code == 200, after.text


def test_granting_leaves_the_viewers_already_on_the_workshop_alone(client, world):
    workshop_id = _make_workshop(world, "Grant beside an existing viewer")
    seated = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [world["people"]["second"].id]},
        headers=_headers(world, "admin"),
    )
    assert seated.status_code == 200, seated.text

    assert _ask(world, "requester", workshop_id).status_code == 202
    request_id = _one_row(world, workshop_id)["id"]
    assert _decide(world, request_id, "GRANTED").status_code == 200

    assert _viewer_ids(world, workshop_id) == {
        world["people"]["second"].id,
        world["people"]["requester"].id,
    }


def test_granting_an_account_that_cannot_hold_a_viewer_row_is_refused_by_the_mechanism(
    client, world
):
    workshop_id = _make_workshop(world, "Ineligible requester")
    assert _ask(world, "researcher", workshop_id).status_code == 202
    request_id = _one_row(world, workshop_id)["id"]

    granted = _decide(world, request_id, "GRANTED")
    assert granted.status_code == 422, granted.text
    assert "RESEARCHER" in granted.json()["detail"]

    assert _viewer_ids(world, workshop_id) == set()
    assert _one_row(world, workshop_id)["status"] == "PENDING"


def test_a_refusal_stands_and_asking_again_does_not_reopen_it(client, world):
    workshop_id = _make_workshop(world, "Refused join request")
    code = _code_for(workshop_id)
    assert _ask(world, "requester", workshop_id, scannedCode=code).status_code == 202
    request_id = _one_row(world, workshop_id)["id"]

    refused = _decide(world, request_id, "DENIED", "not on this cluster")
    assert refused.status_code == 200, refused.text
    assert refused.json()["status"] == "DENIED"

    assert _viewer_ids(world, workshop_id) == set()
    still_out = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "requester")
    )
    assert still_out.status_code == 404, still_out.text

    again = _ask(world, "requester", workshop_id, scannedCode=code)
    assert again.status_code == 202, again.text
    row = _one_row(world, workshop_id)
    assert row["id"] == request_id
    assert row["status"] == "DENIED"


def test_an_admin_can_reverse_their_own_refusal(client, world):
    workshop_id = _make_workshop(world, "Reversed refusal")
    assert _ask(world, "requester", workshop_id).status_code == 202
    request_id = _one_row(world, workshop_id)["id"]

    assert _decide(world, request_id, "DENIED").status_code == 200
    reversed_ = _decide(world, request_id, "GRANTED")
    assert reversed_.status_code == 200, reversed_.text
    assert reversed_.json()["status"] == "GRANTED"

    opened = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "requester")
    )
    assert opened.status_code == 200, opened.text


def test_refusing_somebody_who_already_has_access_is_a_conflict_not_a_lie(client, world):
    workshop_id = _make_workshop(world, "Refusing an existing viewer")
    assert _ask(world, "requester", workshop_id).status_code == 202
    request_id = _one_row(world, workshop_id)["id"]

    seated = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [world["people"]["requester"].id]},
        headers=_headers(world, "admin"),
    )
    assert seated.status_code == 200, seated.text

    refused = _decide(world, request_id, "DENIED")
    assert refused.status_code == 409, refused.text
    assert _one_row(world, workshop_id)["status"] == "PENDING"


def test_asking_again_after_a_grant_was_removed_reopens_the_same_row(client, world):
    workshop_id = _make_workshop(world, "Reopened after a grant was removed")
    code = _code_for(workshop_id)
    assert _ask(world, "requester", workshop_id, scannedCode=code, note="first ask").status_code == 202
    request_id = _one_row(world, workshop_id)["id"]

    assert _decide(world, request_id, "GRANTED", "on for stage 4").status_code == 200
    assert _viewer_ids(world, workshop_id) == {world["people"]["requester"].id}
    granted = _one_row(world, workshop_id)
    assert granted["status"] == "GRANTED"

    assert _ask(world, "requester", workshop_id, scannedCode=code, note="second ask").status_code == 202
    assert _one_row(world, workshop_id) == granted

    removed = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": []},
        headers=_headers(world, "admin"),
    )
    assert removed.status_code == 200, removed.text
    assert _viewer_ids(world, workshop_id) == set()

    after_removal = _one_row(world, workshop_id)
    assert _without_access_flag(after_removal) == _without_access_flag(granted)
    assert granted["requesterHasAccess"] is True
    assert after_removal["requesterHasAccess"] is False

    again = _ask(world, "requester", workshop_id, scannedCode=code, note="please let me back on")
    assert again.status_code == 202, again.text

    reopened = _one_row(world, workshop_id)
    assert reopened["id"] == request_id
    assert reopened["status"] == "PENDING"
    assert datetime.fromisoformat(reopened["createdAt"]) > datetime.fromisoformat(
        granted["createdAt"]
    )
    assert reopened["note"] == "please let me back on"
    assert reopened["source"] == "SCAN"
    assert reopened["decidedBy"]["id"] == world["people"]["admin"].id
    assert reopened["decidedAt"] == granted["decidedAt"]
    assert reopened["decisionNote"] == "on for stage 4"


def test_deciding_a_request_that_does_not_exist_is_a_404(client, world):
    response = _decide(world, f"cmsnosuchrequest{uuid.uuid4().hex[:8]}", "GRANTED")
    assert response.status_code == 404, response.text
    assert response.json()["detail"] == "Record not found"



def _preview(world: dict[str, Any], slug: str, code: str):
    return world["client"].get(
        "/api/design-workshop-access/preview",
        params={"code": code},
        headers=_headers(world, slug),
    )


def test_a_scanned_code_shows_the_workshop_title_to_somebody_without_access(client, world):
    workshop_id = _make_workshop(world, "Bagru block printing week")
    response = _preview(world, "requester", _code_for(workshop_id))
    assert response.status_code == 200, response.text
    assert response.json() == {"workshopId": workshop_id, "title": "Bagru block printing week"}

    code = _code_for(workshop_id)
    typed = " ".join(code[index : index + 4] for index in range(0, len(code), 4)).lower()
    assert _preview(world, "requester", typed).json()["title"] == "Bagru block printing week"


def test_the_title_preview_refuses_a_damaged_code_and_hides_a_deleted_workshop(client, world):
    workshop_id = _make_workshop(world, "Preview refusals")
    good = _code_for(workshop_id)
    broken = good[:-1] + ("Z" if good[-1] != "Z" else "Y")
    assert _preview(world, "requester", broken).status_code == 422
    assert _preview(world, "requester", "https://example.org/x").status_code == 422

    deleted = client.delete(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "creator")
    )
    assert deleted.status_code in (200, 204), deleted.text
    gone = _preview(world, "requester", good)
    invented = _preview(world, "requester", _code_for(f"cmsdoesnotexist{uuid.uuid4().hex[:10]}"))
    assert gone.status_code == invented.status_code == 404
    assert gone.json() == invented.json()


def test_the_title_preview_needs_a_signed_in_account(client, world):
    workshop_id = _make_workshop(world, "Preview needs sign-in")
    response = client.get(
        "/api/design-workshop-access/preview", params={"code": _code_for(workshop_id)}
    )
    assert response.status_code == 401
