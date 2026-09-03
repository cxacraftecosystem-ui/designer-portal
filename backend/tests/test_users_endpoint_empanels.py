"""THE FOURTH DOORWAY: an admin who types a designer into ``/admin/users`` has empanelled them.

Three paths already treated "admitted as a DESIGNER" and "empanelled" as one act — ``auth.login`` on
the way in, and ``access._empanel_an_admitted_designer`` from the approval and from the roster edit.
``POST /api/users``, the door an admin uses when the person is sitting in front of them, did not: it
called ``access_roster.admit`` and stopped.

**WHAT THAT COST, WHICH IS THE INCIDENT REQUIREMENT 28 WAS WRITTEN ABOUT, ARRIVING THROUGH THE MOST
ORDINARY DOOR THERE IS.** The admin creates the account AS A DESIGNER, hands over the password, and
opens ``/admin/designers`` to check. Nothing is there. So either they add the row by hand — a 409 if
they type the address the same way, a second unmatchable row if they do not — or they leave it, and
the designer's first sign-in silently derives the empanelment instead, producing a row that says it
was derived when in fact an administrator granted it and whose ``addedById`` names nobody.

``create_user`` now calls ``ensure_empanelled`` after it admits, on exactly the two conditions
``access._empanel_an_admitted_designer`` uses — the STORED row is ACTIVE and the STORED role is
DESIGNER. The conditions are re-spelled in ``routes/users`` rather than imported because
``routes/access`` imports ``assert_role`` from that module and calling back would close an import
cycle; this module is what keeps the two copies answering the same.

FOUR THINGS ARE PINNED, AND THE THIRD IS WHY THE OTHER THREE ARE SAFE:

1. A designer created here has a roster row IMMEDIATELY, before they have signed in — and it names
   the administrator who made it, which is the whole difference between this row and the derived one
   a sign-in would eventually have written.
2. Somebody who is not a designer is empanelled by nobody. The role test is not decoration: the
   designer roster is read by ``/designers/directory`` and the workshop pickers as the roll of
   practising designers, and filling it with researchers and volunteers is a mess nothing ever tidies.
3. **IT NEVER REVIVES AN EMPANELMENT AN ADMINISTRATOR ENDED.** ``ensure_empanelled`` only ever
   CREATES, and this new caller inherits that — but a fourth call site is a fourth chance for
   somebody to "fix" it into an upsert, at which point creating an account would hand a revoked
   designer their standing back with nothing on either screen to say it happened.
4. One mailbox is one empanelment: the row is written from the STORED allow-list address and not
   from ``User.email``, which is deliberately not canonicalised. Get that backwards and a designer
   whose account is filed under ``a.b@gmail.com`` gets a roster row the sign-in gate cannot find.

Postgres is required — every behaviour here is a row appearing or not appearing — so the module skips
itself when ``DATABASE_URL`` does not point at a local database.

    docker compose up -d postgres minio
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma

Every state below is built and read through the endpoints an administrator uses. The sibling module
``tests/test_designer_empanelment_auto.py`` owns the other three doorways and the sign-in race; this
one is only about the fourth.
"""

import uuid
from datetime import UTC, datetime
from typing import Any

import pytest
from conftest import needs_db

from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services.designers import DERIVED_EMPANELMENT_NOTE

pytestmark = [needs_db, pytest.mark.anyio]

PASSWORD = "users-endpoint-empanels-password"
#: What ``POST /api/users`` will accept for the accounts these tests create.
API_PASSWORD = "LocalDev123!"

#: What an administrator wrote on a roster row by hand. Distinctive on purpose: the revocation test
#: asserts THIS text is still on the row afterwards, so a code path that left ``isActive`` correct
#: while rewriting the note still fails.
ADMIN_NOTE = "Empanelled and then withdrawn by hand. Do not restore."


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    """One admin, admitted, with a password. Everything else is made through the API."""
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    email = f"fourthdoor-admin-{stamp}@example.org"

    await db.connect()
    try:
        admin = await db.user.create(
            data={
                "email": email,
                "name": f"Fourth door admin {stamp}",
                "role": "ADMIN",
                "passwordHash": hash_password(PASSWORD),
            }
        )
        await db.accessroster.create(
            data={
                "email": email,
                "status": "ACTIVE",
                "admitRole": "ADMIN",
                "joinedAt": datetime.now(UTC),
                "notes": "Seeded by tests/test_users_endpoint_empanels.py.",
            }
        )
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {"client": client, "admin": admin, "stamp": stamp}


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any]) -> dict[str, str]:
    """The admin's bearer token, minted directly: the gate under test is on ``POST /api/users``, not
    on the sign-in path, and signing in first would make every assertion depend on it."""
    return {"Authorization": f"Bearer {create_access_token(subject=world['admin'].id)}"}


def _create_account(world: dict[str, Any], email: str, role: str, label: str) -> Any:
    return world["client"].post(
        "/api/users",
        json={
            "email": email,
            "name": f"{label} {world['stamp']}",
            "password": API_PASSWORD,
            "role": role,
        },
        headers=_headers(world),
    )


def _roster_rows(world: dict[str, Any], *spellings: str, term: str | None = None) -> list[Any]:
    """The DESIGNER-roster rows spelled any of these ways, as a LIST.

    A list and not a dict keyed by address, for ``test_designer_empanelment_auto._rows_spelling``'s
    reason: half the assertions here are about HOW MANY rows one mailbox has, and two spellings of
    one Gmail address are two keys that both have to be counted. Every spelling is named by the
    caller rather than derived with ``canonical_email``, so a canonicalisation bug cannot be checked
    against its own answer.
    """
    response = world["client"].get(
        "/api/designers/roster",
        params={"search": term or world["stamp"], "pageSize": 200},
        headers=_headers(world),
    )
    assert response.status_code == 200, response.text
    wanted = set(spellings)
    return [row for row in response.json()["items"] if row["email"] in wanted]


async def test_a_designer_created_here_is_empanelled_before_they_have_signed_in(world, client):
    """**THE FIX.** The admin creates the account and the roster row is there when they look.

    ``firstSeenAt`` is asserted NULL in the same breath, because the ordering matters as much as the
    row does: ``mark_roster_seen`` writes only ``WHERE firstSeenAt IS NULL`` on the person's first
    real sign-in, and a row stamped at creation would report every empanelment as accepted on the day
    it was granted — worse than no signal, because it looks like an answer.

    ``addedById`` is the other half. A row derived from the person's own sign-in carries NULL there,
    deliberately: nobody administered it. This one was administered, by the admin whose token made
    the request, and that is the record ``/admin/designers`` exists to hold.
    """
    email = f"fourthdoor-designer-{world['stamp']}@example.org"
    made = _create_account(world, email, "DESIGNER", "Typed In Designer")
    assert made.status_code == 201, made.text

    rows = _roster_rows(world, email, term=email)
    assert len(rows) == 1, (
        "creating an account AS A DESIGNER left /admin/designers empty, so the admin who just did "
        "it looks for the person, does not find them, and adds the row a second time by hand: "
        f"{rows}"
    )
    assert rows[0]["isActive"] is True
    assert rows[0]["firstSeenAt"] is None, "creating the account is not the designer arriving"
    assert rows[0]["addedById"] == world["admin"].id, (
        "the empanelment does not record which administrator granted it, so it reads as derived "
        "from a sign-in that has not happened"
    )
    assert rows[0]["notes"] == DERIVED_EMPANELMENT_NOTE, (
        "the note is the one ensure_empanelled writes; if this endpoint starts passing its own, "
        "say so here rather than letting the two roster screens describe the same act differently"
    )


async def test_creating_somebody_who_is_not_a_designer_empanels_nobody(world, client):
    """THE ROLE TEST IS LOAD-BEARING, NOT TIDINESS.

    ``/designers/directory`` and the workshop pickers read this table as the roll of practising
    designers. A row here for every account an admin creates would offer researchers and volunteers
    as people to hand a fortnight of fieldwork to, and nothing anywhere ever takes such a row off
    again.
    """
    email = f"fourthdoor-researcher-{world['stamp']}@example.org"
    made = _create_account(world, email, "RESEARCHER", "Typed In Researcher")
    assert made.status_code == 201, made.text
    assert _roster_rows(world, email, term=email) == [], (
        "creating a RESEARCHER wrote a designer-roster row"
    )


async def test_creating_an_account_never_revives_an_empanelment_an_admin_ended(world, client):
    """**THE ONE RULE THAT MUST NOT BE GOT WRONG, AT THE NEW CALL SITE.**

    Suspension is a deliberate revocation — the roster suspends rather than deletes precisely so the
    record of the empanelment outlives the ending of it. ``ensure_empanelled`` refuses to touch an
    existing row for that reason, and a fourth caller is a fourth chance for somebody to "simplify"
    it into an upsert. If that ever happens, creating an account for a revoked designer hands them
    their standing back, silently, with nothing on either screen to say it happened.

    The empanelment is ended BEFORE the account exists, which is a real order of events and not a
    contrived one: an address can be empanelled and revoked long before the person ever has a login.
    """
    email = f"fourthdoor-revoked-{world['stamp']}@example.org"
    empanelled = client.post(
        "/api/designers/roster",
        json={"email": email, "notes": ADMIN_NOTE, "isActive": False},
        headers=_headers(world),
    )
    assert empanelled.status_code == 201, empanelled.text
    assert empanelled.json()["isActive"] is False, "the state under test was not reached"

    made = _create_account(world, email, "DESIGNER", "Revoked Designer")
    assert made.status_code == 201, made.text

    rows = _roster_rows(world, email, term=email)
    assert len(rows) == 1, f"a second empanelment was written beside the revoked one: {rows}"
    assert rows[0]["id"] == empanelled.json()["id"]
    assert rows[0]["isActive"] is False, (
        "creating an account revived an empanelment an administrator had ended; every revocation "
        "any administrator ever made would come undone the same way, one new account at a time"
    )
    assert rows[0]["notes"] == ADMIN_NOTE, (
        "the row stayed suspended but lost what the administrator wrote on it, which is the same "
        "defect wearing a smaller hat"
    )


async def test_the_empanelment_is_written_under_the_mailbox_and_not_under_the_typed_address(
    world, client
):
    """**ONE MAILBOX, ONE EMPANELMENT — AND THE ADDRESS IS TAKEN FROM THE STORED ROW.**

    ``User.email`` is deliberately not canonicalised (``auth.login_with_google`` says so in as many
    words, and this endpoint stores ``payload.email.lower()``, dots and all), while both roster
    tables are. So an account typed as ``a.b@gmail.com`` sits beside an allow-list row stored as
    ``ab@gmail.com``, and an empanelment written from the ACCOUNT's spelling would be a row the
    sign-in gate — which arrives holding whatever Google presents, always the undotted mailbox —
    cannot find. The designer would then be refused about a suspension that never happened, which is
    the original incident, rebuilt from the other side.

    The two spellings are asserted to be genuinely different first, so a fixture that quietly stopped
    being an alias cannot make this pass for the wrong reason.
    """
    typed = f"fourthdoor.alias.{world['stamp']}@gmail.com"
    mailbox = f"fourthdooralias{world['stamp']}@gmail.com"
    assert typed != mailbox, "the fixture must hold two spellings, or this test proves nothing"

    made = _create_account(world, typed, "DESIGNER", "Aliased Designer")
    assert made.status_code == 201, made.text
    assert made.json()["email"] == typed, (
        "the account is expected to keep the spelling that was typed — that divergence from the "
        "canonicalised rosters is the whole state under test"
    )

    rows = _roster_rows(world, typed, mailbox)
    assert [row["email"] for row in rows] == [mailbox], (
        "the empanelment was written under the account's spelling rather than the mailbox both "
        f"rosters and the sign-in gate are keyed on: {[row['email'] for row in rows]}"
    )
