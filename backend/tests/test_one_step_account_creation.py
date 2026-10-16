"""THE ONE-STEP ACCOUNT (owner, 2026-10-17): ``POST /api/users`` with ``passwordMode``.

What is pinned here:

* each way in — SET (the password sent), GENERATE (a temporary one answered ONCE), NONE (provider
  only), NONE with an e-mailed link (sent only AFTER the commit, or a sentence when mail is off);
* ALL OR NOTHING — the ``User``, its allow-list admission and its role's own list (the designer
  empanelment) are one transaction: a failure in step 2 or 3 leaves no row at all;
* the hierarchy — strictly below your own role, and only a master admin makes an admin;
* a duplicate is a 409 naming the account that holds the address, with ``X-Existing-Account-Id``.

Postgres is required; the module skips itself when ``DATABASE_URL`` is not a local database. Rows the
app wrote are read back through ``client.portal`` (see ``tests/test_stage_sync.py``).
"""

import asyncio
import functools
import uuid
from datetime import UTC, datetime
from typing import Any

import pytest
from conftest import needs_db

from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services import access_roster, account_provisioning, credential_links, mailer

pytestmark = [needs_db]

PASSWORD = "one-step-actor-password"

ACTORS = (
    ("master", "MASTER_ADMIN"),
    ("admin", "ADMIN"),
    ("ministry", "MINISTRY_ADMIN"),
    ("professor", "PROFESSOR"),
)


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        return f"onestep-{slug}-{stamp}@example.org".lower()

    async def seed() -> dict[str, Any]:
        await db.connect()
        try:
            now = datetime.now(UTC)
            people: dict[str, Any] = {}
            for slug, role in ACTORS:
                people[slug] = await db.user.create(
                    data={
                        "email": address(slug),
                        "name": f"One step {slug} {stamp}",
                        "role": role,
                        "passwordHash": hash_password(PASSWORD),
                        "passwordSetAt": now,
                        "authProvider": "LOCAL",
                    }
                )
                await db.accessroster.create(
                    data={
                        "email": address(slug),
                        "status": "ACTIVE",
                        "admitRole": role,
                        "joinedAt": now,
                        "notes": "Seeded by tests/test_one_step_account_creation.py.",
                    }
                )
            return people
        finally:
            await db.disconnect()

    people = asyncio.run(seed())
    with TestClient(app) as client:
        yield {"client": client, "people": people, "address": address, "stamp": stamp}


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['people'][slug].id)}"}


def _read(client: Any, call: Any, **kwargs: Any) -> Any:
    return client.portal.call(functools.partial(call, **kwargs))


def _create(client: Any, world: dict[str, Any], actor: str, **body: Any) -> Any:
    return client.post("/api/users", json=body, headers=_headers(world, actor))


def _new_address(world: dict[str, Any], label: str) -> str:
    return f"onestep-new-{label}-{uuid.uuid4().hex[:6]}-{world['stamp']}@example.org"


def _expect_a_server_fault(call: Any) -> None:
    """The injected fault surfaces as the TestClient's re-raise or as the app's catch-all 500."""
    try:
        answer = call()
    except RuntimeError:
        return
    assert answer.status_code == 500, answer.text


def _nothing_written(client: Any, email: str) -> None:
    assert _read(client, db.user.find_first, where={"email": email}) is None
    assert _read(client, db.accessroster.find_first, where={"email": email}) is None
    assert _read(client, db.designerroster.find_first, where={"email": email}) is None


# ==================================================================================================
# Each way in
# ==================================================================================================


def test_set_a_password_now(world, client):
    email = _new_address(world, "set")
    made = _create(
        client, world, "admin", email=email, name="Set Person", role="RESEARCHER",
        passwordMode="SET", password="a-password-they-get-1",
    )
    assert made.status_code == 201, made.text
    body = made.json()
    assert body["mustChangePassword"] is True and body["hasPassword"] is True
    assert body["rosterAdded"] is True and body["roleListsAdded"] == []
    assert "temporaryPassword" not in body and "passwordHash" not in body
    roster = _read(client, db.accessroster.find_first, where={"email": email})
    assert access_roster.status_of(roster) == "ACTIVE"
    signed_in = client.post("/api/auth/login", json={"email": email, "password": "a-password-they-get-1"})
    assert signed_in.status_code == 200, signed_in.text


def test_a_password_sent_without_a_mode_is_still_set(world, client):
    """Every client that predates ``passwordMode`` sends only ``password``."""
    made = _create(
        client, world, "admin", email=_new_address(world, "legacy"), name="Legacy", role="RESEARCHER",
        password="a-password-they-get-2",
    )
    assert made.status_code == 201, made.text
    assert made.json()["hasPassword"] is True


def test_make_a_temporary_password_for_a_designer(world, client):
    email = _new_address(world, "generate")
    made = _create(
        client, world, "admin", email=email, name="Generated Designer", role="DESIGNER",
        passwordMode="GENERATE", mustChangePassword=False,
    )
    assert made.status_code == 201, made.text
    body = made.json()
    temporary = body["temporaryPassword"]
    assert len(temporary) == account_provisioning.GENERATED_PASSWORD_LENGTH
    assert set(temporary) <= set(account_provisioning.GENERATED_PASSWORD_ALPHABET)
    # Generated means somebody else's: the change is required whatever the box said.
    assert body["mustChangePassword"] is True
    assert body["roleListsAdded"] == ["designerRoster"]
    empanelment = _read(client, db.designerroster.find_first, where={"email": email})
    assert empanelment is not None and empanelment.isActive is True
    stored = _read(client, db.user.find_first, where={"email": email})
    assert stored.passwordHash and temporary not in stored.passwordHash
    signed_in = client.post("/api/auth/login", json={"email": email, "password": temporary})
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["mustChangePassword"] is True


def test_provider_only_without_a_link(world, client):
    email = _new_address(world, "none")
    made = _create(client, world, "admin", email=email, name="Provider Only", role="RESEARCHER", passwordMode="NONE")
    assert made.status_code == 201, made.text
    body = made.json()
    assert body["hasPassword"] is False and body["mustChangePassword"] is False
    assert "passwordLink" not in body and "passwordLinkError" not in body
    assert body["passwordSetAt"] is None
    roster = _read(client, db.accessroster.find_first, where={"email": email})
    assert access_roster.status_of(roster) == "ACTIVE"


def test_provider_only_with_a_link_when_mail_is_off(world, client, monkeypatch):
    monkeypatch.setattr(mailer, "mail_configured", lambda settings=None: False)
    email = _new_address(world, "nomail")
    made = _create(
        client, world, "admin", email=email, name="No Mail", role="RESEARCHER",
        passwordMode="NONE", sendPasswordLink=True,
    )
    assert made.status_code == 201, made.text
    assert made.json()["passwordLinkError"] == account_provisioning.LINK_NOT_SENT_NO_MAIL_DETAIL
    assert _read(client, db.user.find_first, where={"email": email}) is not None


def test_provider_only_with_a_link_is_sent_after_the_commit(world, client, monkeypatch):
    monkeypatch.setattr(mailer, "mail_configured", lambda settings=None: True)
    email = _new_address(world, "link")
    seen: dict[str, Any] = {}
    original = credential_links.issue_link

    async def watching(**kwargs: Any) -> Any:
        # A SEPARATE connection's view: the module client is outside the create's transaction, so
        # it sees the account only once that transaction has committed.
        seen["committed"] = await db.user.find_first(where={"email": email}) is not None
        return await original(**kwargs)

    monkeypatch.setattr(credential_links, "issue_link", watching)
    made = _create(
        client, world, "admin", email=email, name="Linked", role="RESEARCHER",
        passwordMode="NONE", sendPasswordLink=True,
    )
    assert made.status_code == 201, made.text
    link = made.json()["passwordLink"]
    assert link["deliveredBy"] == "EMAIL" and link["purpose"] == "INVITE"
    assert "link" not in link  # the credential went to their inbox, not to this screen
    assert seen == {"committed": True}
    assert _read(client, db.emailmessage.find_many, where={"toAddress": email})


def test_a_mode_must_be_chosen_and_be_consistent(world, client):
    email = _new_address(world, "invalid")
    for body in (
        {},
        {"passwordMode": "SET"},
        {"passwordMode": "GENERATE", "password": "a-password-they-get-3"},
        {"passwordMode": "SET", "password": "a-password-they-get-3", "sendPasswordLink": True},
    ):
        refused = _create(client, world, "admin", email=email, name="Invalid", role="RESEARCHER", **body)
        assert refused.status_code == 422, (body, refused.text)
    _nothing_written(client, email)


# ==================================================================================================
# All or nothing
# ==================================================================================================


def test_a_failure_admitting_the_address_writes_nothing(world, client, monkeypatch):
    async def broken(*_args: Any, **_kwargs: Any) -> Any:
        raise RuntimeError("the allow-list write failed")

    monkeypatch.setattr(access_roster, "admit", broken)
    email = _new_address(world, "fail2")
    _expect_a_server_fault(
        lambda: _create(
            client, world, "admin", email=email, name="Fail Two", role="RESEARCHER", passwordMode="GENERATE"
        )
    )
    monkeypatch.undo()
    _nothing_written(client, email)


def test_a_failure_adding_the_role_list_writes_nothing(world, client, monkeypatch):
    async def broken(*_args: Any, **_kwargs: Any) -> Any:
        raise RuntimeError("the empanelment write failed")

    monkeypatch.setattr(account_provisioning, "ensure_empanelled", broken)
    email = _new_address(world, "fail3")
    _expect_a_server_fault(
        lambda: _create(
            client, world, "admin", email=email, name="Fail Three", role="DESIGNER", passwordMode="GENERATE"
        )
    )
    monkeypatch.undo()
    _nothing_written(client, email)


# ==================================================================================================
# Who may create whom
# ==================================================================================================


@pytest.mark.parametrize(
    ("actor", "role", "status", "detail"),
    [
        ("admin", "ADMIN", 403, account_provisioning.ONLY_MASTER_CREATES_ADMIN_DETAIL),
        # ``assert_role``'s ceiling answers first for a role ABOVE the creator's own.
        ("ministry", "ADMIN", 403, "You can only give a role at or below your own"),
        ("ministry", "MINISTRY_ADMIN", 403, account_provisioning.CREATE_BELOW_YOUR_OWN_DETAIL),
        ("ministry", "REGIONAL_DIRECTOR", 201, None),
        ("admin", "MINISTRY_ADMIN", 201, None),
        ("master", "ADMIN", 201, None),
        ("professor", "RESEARCHER", 403, None),
    ],
)
def test_strictly_below_and_only_master_makes_an_admin(world, client, actor, role, status, detail):
    email = _new_address(world, f"rank-{actor}-{role.lower()}")
    answer = _create(client, world, actor, email=email, name="Ranked", role=role, passwordMode="NONE")
    assert answer.status_code == status, answer.text
    if detail is not None:
        assert answer.json()["detail"] == detail
    if status != 201:
        _nothing_written(client, email)


# ==================================================================================================
# A duplicate
# ==================================================================================================


def test_a_duplicate_names_the_existing_account(world, client):
    holder = world["people"]["professor"]
    refused = _create(
        client, world, "admin", email=world["address"]("professor").upper(), name="Again",
        role="RESEARCHER", passwordMode="NONE",
    )
    assert refused.status_code == 409, refused.text
    assert refused.headers["x-existing-account-id"] == holder.id
    assert refused.json()["detail"] == (
        f"{holder.email} already has an account ({holder.name}, Professor). Open it to change their "
        "role or access instead."
    )
