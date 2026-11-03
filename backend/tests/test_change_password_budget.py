import time
import uuid
from datetime import UTC, datetime
from typing import Any

import jwt
import pytest
from conftest import needs_db

from app.core.config import get_settings
from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.scale.rate_limit import _ACCOUNT_FAILURES

pytestmark = [needs_db, pytest.mark.anyio]

PASSWORD = "change-password-budget-original"
OTHER_PASSWORD = "change-password-budget-second"
WRONG = "not-the-current-password"

THROTTLED_DETAIL = (
    "Too many failed sign-in attempts for this account. Wait a few minutes and try again — "
    "a sign-in that succeeds does not count against this limit."
)

ACCOUNTS: tuple[tuple[str, bool], ...] = (
    ("owner", True),
    ("sharer", True),
    ("googler", False),
    ("flagged", True),
)

SAME_PASSWORD_DETAIL = (
    "Choose a password different from the one you have now. A password somebody else gave you has "
    "to be replaced, not typed again."
)


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        return f"changepwd-{slug}-{stamp}@example.org".lower()

    people: dict[str, Any] = {}
    await db.connect()
    try:
        for slug, has_password in ACCOUNTS:
            people[slug] = await db.user.create(
                data={
                    "email": address(slug),
                    "name": f"Change password {slug} {stamp}",
                    "role": "RESEARCHER",
                    "passwordHash": hash_password(PASSWORD) if has_password else None,
                    "mustChangePassword": slug == "flagged",
                }
            )
            await db.accessroster.create(
                data={
                    "email": address(slug),
                    "status": "ACTIVE",
                    "admitRole": "RESEARCHER",
                    "joinedAt": datetime.now(UTC),
                    "notes": "Seeded by tests/test_change_password_budget.py.",
                }
            )
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {"client": client, "people": people, "address": address, "stamp": stamp}


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['people'][slug].id)}"}


def _stale_headers(world: dict[str, Any], slug: str) -> dict[str, str]:
    settings = get_settings()
    now = int(time.time())
    token = jwt.encode(
        {"sub": world["people"][slug].id, "iat": now - 3600, "exp": now + 3600},
        settings.jwt_secret,
        algorithm=settings.jwt_algorithm,
    )
    return {"Authorization": f"Bearer {token}"}


def _change(client: Any, world: dict[str, Any], slug: str, current: str, new: str) -> Any:
    return client.post(
        "/api/auth/change-password",
        json={"currentPassword": current, "newPassword": new},
        headers=_headers(world, slug),
    )


def _login(client: Any, email: str, password: str) -> Any:
    return client.post("/api/auth/login", json={"email": email, "password": password})


async def test_a_stolen_session_cannot_guess_the_current_password_forever(world, client):
    statuses = [
        _change(client, world, "owner", WRONG, "a-brand-new-password").status_code
        for _ in range(_ACCOUNT_FAILURES + 2)
    ]
    assert statuses[0] == 400, f"the very first wrong current password was not simply refused: {statuses}"
    assert 401 not in statuses, f"a wrong current password still answers 401: {statuses}"
    assert statuses[-1] == 429, (
        f"{_ACCOUNT_FAILURES + 2} wrong guesses in a row never closed this route, so a stolen "
        f"session is still an unlimited oracle against this account's password: {statuses}"
    )
    refused = _change(client, world, "owner", WRONG, "a-brand-new-password")
    assert refused.status_code == 429
    assert refused.json()["detail"] == THROTTLED_DETAIL


async def test_the_owner_changing_their_own_password_is_charged_nothing(world, client):
    email = world["address"]("sharer")
    current, following = PASSWORD, OTHER_PASSWORD
    statuses = []
    for _ in range(_ACCOUNT_FAILURES + 4):
        statuses.append(_change(client, world, "sharer", current, following).status_code)
        current, following = following, current
    assert statuses == [200] * (_ACCOUNT_FAILURES + 4), (
        f"a correct current password was charged for: {statuses}"
    )
    assert _login(client, email, current).status_code == 200, (
        "the password the last successful change wrote is not the one the account now has"
    )


async def test_an_account_with_no_password_is_told_so_and_charged_nothing(world, client):
    stale = _stale_headers(world, "googler")
    statuses = [
        client.post(
            "/api/auth/change-password",
            json={"currentPassword": WRONG, "newPassword": "a-brand-new-password"},
            headers=stale,
        ).status_code
        for _ in range(_ACCOUNT_FAILURES + 5)
    ]
    assert statuses == [400] * (_ACCOUNT_FAILURES + 5), (
        "an account with no password to change was charged a guessing budget for saying so: "
        f"{statuses}"
    )
    told = client.post(
        "/api/auth/change-password",
        json={"newPassword": "a-brand-new-password"},
        headers=stale,
    )
    assert told.json()["detail"] == (
        "To add a password, first confirm it's you by signing in with Google again."
    )
    assert told.headers.get("x-confirm-provider") == "GOOGLE"


async def test_the_budget_here_is_the_same_one_the_sign_in_door_spends(world, client):
    email = world["address"]("owner")
    statuses = [
        _change(client, world, "owner", WRONG, "a-brand-new-password").status_code
        for _ in range(_ACCOUNT_FAILURES + 2)
    ]
    assert statuses[-1] == 429, f"the budget was not spent, so this test proves nothing: {statuses}"

    signed_in = _login(client, email, PASSWORD)
    assert signed_in.status_code == 429, (
        "the sign-in door was still open after this route's budget was spent, so the two doors hold "
        f"separate allowances and an attacker refused at one simply walks to the other: {signed_in.text}"
    )


async def test_the_temporary_password_cannot_be_chosen_again(world, client):
    statuses = [
        _change(client, world, "flagged", PASSWORD, PASSWORD).status_code
        for _ in range(_ACCOUNT_FAILURES + 2)
    ]
    assert statuses == [400] * (_ACCOUNT_FAILURES + 2), (
        f"re-typing the current password was not refused, or it was charged as a guess: {statuses}"
    )
    refused = _change(client, world, "flagged", PASSWORD, PASSWORD)
    assert refused.json()["detail"] == SAME_PASSWORD_DETAIL

    me = client.get("/api/me", headers=_headers(world, "flagged"))
    assert me.status_code == 200, me.text
    assert me.json()["mustChangePassword"] is True, (
        "the refused change cleared the flag anyway, so the administrator's password stays in use"
    )

    changed = _change(client, world, "flagged", PASSWORD, OTHER_PASSWORD)
    assert changed.status_code == 200, changed.text
    assert changed.json() == {"ok": True}
    after = client.get(
        "/api/me", headers={"Authorization": f"Bearer {changed.headers['X-Session-Token']}"}
    )
    assert after.status_code == 200, after.text
    assert after.json()["mustChangePassword"] is False
