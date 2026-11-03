import functools
import uuid
from datetime import UTC, datetime
from typing import Any

import pytest
from conftest import needs_db

from app.core.db import db
from app.core.security import create_access_token, hash_password

pytestmark = [needs_db, pytest.mark.anyio]

PASSWORD = "suspension-revokes-sessions-password"

SESSION_ENDED_DETAIL = "This session is no longer valid. Sign in again."

ACCOUNTS: tuple[tuple[str, str], ...] = (
    ("admin", "ADMIN"),
    ("suspended", "RESEARCHER"),
    ("rejected", "RESEARCHER"),
    ("bystander", "RESEARCHER"),
    ("collateral", "RESEARCHER"),
    ("demoted", "RESEARCHER"),
    ("relabelled", "DESIGNER"),
    ("twicebarred", "RESEARCHER"),
    ("empanelled", "DESIGNER"),
    ("empanelledbypatch", "DESIGNER"),
    ("workshopprofessor", "PROFESSOR"),
)

EMPANELLED: tuple[str, ...] = ("empanelled", "empanelledbypatch", "workshopprofessor")

ADMITTED_AS: dict[str, str] = {"workshopprofessor": "DESIGNER"}


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        return f"revoke-{slug}-{stamp}@example.org".lower()

    people: dict[str, Any] = {}
    roster: dict[str, Any] = {}
    await db.connect()
    try:
        for slug, role in ACCOUNTS:
            people[slug] = await db.user.create(
                data={
                    "email": address(slug),
                    "name": f"Revocation {slug} {stamp}",
                    "role": role,
                    "passwordHash": hash_password(PASSWORD),
                }
            )
            await db.accessroster.create(
                data={
                    "email": address(slug),
                    "status": "ACTIVE",
                    "admitRole": ADMITTED_AS.get(slug, role),
                    "joinedAt": datetime.now(UTC),
                    "notes": "Seeded by tests/test_suspension_revokes_sessions.py.",
                }
            )
        for slug in EMPANELLED:
            roster[slug] = await db.designerroster.create(
                data={
                    "email": address(slug),
                    "fullName": f"Revocation {slug} {stamp}",
                    "isActive": True,
                    "notes": "Seeded by tests/test_suspension_revokes_sessions.py.",
                }
            )
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "people": people,
            "roster": roster,
            "address": address,
            "stamp": stamp,
        }


@pytest.fixture
def client(world):
    return world["client"]


def _token(world: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['people'][slug].id)}"}


def _me(client: Any, headers: dict[str, str]) -> Any:
    return client.get("/api/me", headers=headers)


def _access_row(client: Any, world: dict[str, Any], email: str) -> dict[str, Any]:
    rows = client.get(
        "/api/access/roster",
        params={"search": email, "pageSize": 200},
        headers=_token(world, "admin"),
    )
    assert rows.status_code == 200, rows.text
    found = {row["email"]: row for row in rows.json()["items"]}
    assert email in found, f"no allow-list row for {email}; rows seen: {sorted(found)}"
    return found[email]


def _sessions_valid_from(client: Any, world: dict[str, Any], slug: str) -> Any:
    row = client.portal.call(
        functools.partial(db.user.find_unique, where={"id": world["people"][slug].id})
    )
    return row.sessionsValidFrom


async def test_suspending_an_account_ends_the_session_it_is_already_in(world, client):
    email = world["address"]("suspended")
    session = _token(world, "suspended")
    assert _me(client, session).status_code == 200, "the session must be good before it is ended"

    row_id = _access_row(client, world, email)["id"]
    barred = client.delete(f"/api/access/roster/{row_id}", headers=_token(world, "admin"))
    assert barred.status_code == 200, barred.text
    assert barred.json()["status"] == "SUSPENDED"

    refused = _me(client, session)
    assert refused.status_code == 401, (
        "the account was suspended and the session it was already in went on working; an "
        "administrator who pressed Suspend believes access is cut and for seven days it is not: "
        f"{refused.text}"
    )
    assert refused.json()["detail"] == SESSION_ENDED_DETAIL, (
        "the refusal must not name a password change — this person's password was never touched, "
        "and telling them it was sends them to reset something that was not wrong"
    )


async def test_rejecting_a_request_ends_the_session_too(world, client):
    email = world["address"]("rejected")
    session = _token(world, "rejected")
    assert _me(client, session).status_code == 200, "the session must be good before it is ended"

    row_id = _access_row(client, world, email)["id"]
    decided = client.post(
        f"/api/access/roster/{row_id}/decision",
        json={"decision": "REJECT"},
        headers=_token(world, "admin"),
    )
    assert decided.status_code == 200, decided.text
    assert decided.json()["status"] == "REJECTED"

    refused = _me(client, session)
    assert refused.status_code == 401, (
        "rejecting somebody barred them from the application and left the session they were "
        f"already in running: {refused.text}"
    )


async def test_barring_one_person_signs_out_nobody_else(world, client):
    bystander = _token(world, "bystander")
    assert _me(client, bystander).status_code == 200

    row_id = _access_row(client, world, world["address"]("collateral"))["id"]
    barred = client.delete(f"/api/access/roster/{row_id}", headers=_token(world, "admin"))
    assert barred.status_code == 200, barred.text
    assert barred.json()["status"] == "SUSPENDED"

    assert _me(client, bystander).status_code == 200, (
        "barring one address signed a different account out of the product"
    )


async def test_changing_somebodys_role_is_not_a_revocation(world, client):
    relabelled = _token(world, "relabelled")
    assert _me(client, relabelled).status_code == 200

    row_id = _access_row(client, world, world["address"]("relabelled"))["id"]
    corrected = client.patch(
        f"/api/access/roster/{row_id}",
        json={"role": "RESEARCHER"},
        headers=_token(world, "admin"),
    )
    assert corrected.status_code == 200, corrected.text
    assert corrected.json()["admitRole"] == "RESEARCHER"
    assert _me(client, relabelled).status_code == 200, (
        "correcting the tier an allow-list row admits somebody at signed them out"
    )

    demoted = _token(world, "demoted")
    assert _me(client, demoted).status_code == 200
    lowered = client.patch(
        f"/api/users/{world['people']['demoted'].id}",
        json={"role": "FIELD_CONTRIBUTOR"},
        headers=_token(world, "admin"),
    )
    assert lowered.status_code == 200, lowered.text
    assert lowered.json()["role"] == "FIELD_CONTRIBUTOR"
    after = _me(client, demoted)
    assert after.status_code == 200, "demoting an account signed it out"
    assert after.json()["role"] == "FIELD_CONTRIBUTOR", (
        "the demotion did not reach the very next request, which is what the identity-cache "
        "invalidation beside that write is for"
    )


async def test_barring_an_address_with_no_account_behind_it_is_quiet(world, client):
    address = f"revoke-neversignedup-{world['stamp']}@example.org"
    admitted = client.post(
        "/api/access/roster",
        json={"email": address, "role": "RESEARCHER"},
        headers=_token(world, "admin"),
    )
    assert admitted.status_code == 201, admitted.text

    barred = client.delete(
        f"/api/access/roster/{admitted.json()['id']}", headers=_token(world, "admin")
    )
    assert barred.status_code == 200, barred.text
    assert barred.json()["status"] == "SUSPENDED"


async def test_re_barring_an_already_rejected_row_still_ends_the_session(world, client):
    email = world["address"]("twicebarred")
    row_id = _access_row(client, world, email)["id"]

    rejected = client.post(
        f"/api/access/roster/{row_id}/decision",
        json={"decision": "REJECT"},
        headers=_token(world, "admin"),
    )
    assert rejected.status_code == 200, rejected.text
    first = _sessions_valid_from(client, world, "twicebarred")
    assert first is not None, "the rejection did not stamp anything, so this test proves nothing"

    suspended = client.delete(f"/api/access/roster/{row_id}", headers=_token(world, "admin"))
    assert suspended.status_code == 200, suspended.text
    assert suspended.json()["status"] == "SUSPENDED", (
        "REJECTED -> SUSPENDED is a real write; if this row did not move, the early return covered "
        "it and the case this test is about was never reached"
    )
    second = _sessions_valid_from(client, world, "twicebarred")
    assert second > first, (
        "the second barring did not re-stamp the revocation watermark, so a row barred before this "
        "change shipped keeps its live sessions however many times an administrator presses the "
        "button"
    )



async def test_ending_an_empanelment_ends_the_session_it_was_carrying(world, client):
    session = _token(world, "empanelled")
    assert _me(client, session).status_code == 200, "the session must be good before it is ended"

    ended = client.delete(
        f"/api/designers/roster/{world['roster']['empanelled'].id}",
        headers=_token(world, "admin"),
    )
    assert ended.status_code == 200, ended.text
    assert ended.json()["isActive"] is False

    refused = _me(client, session)
    assert refused.status_code == 401, (
        "the empanelment was ended and the session it was carrying went on working; the admin who "
        "pressed it believes the designer's access is cut and for seven days it is not: "
        f"{refused.text}"
    )
    assert refused.json()["detail"] == SESSION_ENDED_DETAIL


async def test_the_patch_door_onto_the_same_decision_ends_it_too(world, client):
    session = _token(world, "empanelledbypatch")
    assert _me(client, session).status_code == 200

    ended = client.patch(
        f"/api/designers/roster/{world['roster']['empanelledbypatch'].id}",
        json={"isActive": False},
        headers=_token(world, "admin"),
    )
    assert ended.status_code == 200, ended.text
    assert ended.json()["isActive"] is False

    refused = _me(client, session)
    assert refused.status_code == 401, (
        "ending an empanelment through the PATCH door left the live session running, so the two "
        f"doors onto one decision disagree about what it means: {refused.text}"
    )


async def test_ending_a_professors_empanelment_does_not_sign_the_professor_out(world, client):
    session = _token(world, "workshopprofessor")
    assert _me(client, session).status_code == 200

    ended = client.delete(
        f"/api/designers/roster/{world['roster']['workshopprofessor'].id}",
        headers=_token(world, "admin"),
    )
    assert ended.status_code == 200, ended.text
    assert ended.json()["isActive"] is False

    assert _me(client, session).status_code == 200, (
        "ending an empanelment signed out a PROFESSOR whose access to this product never rested on "
        "it — the exact outage access_roster.admissions_an_empanelment_carries exists to prevent, "
        "reached through the session column instead of through the allow-list"
    )
    assert _sessions_valid_from(client, world, "workshopprofessor") is None, (
        "no revocation watermark should have been written at all; a stamp that merely happens to "
        "predate this token is a bug waiting for a slower clock"
    )
    assert _access_row(client, world, world["address"]("workshopprofessor"))["status"] == "ACTIVE", (
        "the allow-list row was mirrored too, so the guard is not being asked at all and the "
        "session assertion above proves nothing"
    )
