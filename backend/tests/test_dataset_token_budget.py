import uuid
from datetime import UTC, datetime
from typing import Any

import pytest
from conftest import needs_db

from app.core.db import db
from app.core.security import hash_password
from app.scale.rate_limit import _ACCOUNT_FAILURES

pytestmark = [needs_db, pytest.mark.anyio]

PASSWORD = "dataset-token-budget-password"
WRONG = "not-the-password-either"

THROTTLED_DETAIL = (
    "Too many failed credential attempts for this account. Wait a few minutes and try again — "
    "a correct password does not count against this limit."
)

ACCOUNTS: tuple[tuple[str, str], ...] = (
    ("admin", "ADMIN"),
    ("researcher", "RESEARCHER"),
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
        return f"tokenbudget-{slug}-{stamp}@example.org".lower()

    people: dict[str, Any] = {}
    await db.connect()
    try:
        for slug, role in ACCOUNTS:
            people[slug] = await db.user.create(
                data={
                    "email": address(slug),
                    "name": f"Token budget {slug} {stamp}",
                    "role": role,
                    "passwordHash": hash_password(PASSWORD),
                }
            )
            await db.accessroster.create(
                data={
                    "email": address(slug),
                    "status": "ACTIVE",
                    "admitRole": role,
                    "joinedAt": datetime.now(UTC),
                    "notes": "Seeded by tests/test_dataset_token_budget.py.",
                }
            )
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {"client": client, "people": people, "address": address, "stamp": stamp}


@pytest.fixture
def client(world):
    return world["client"]


def _mint(client: Any, email: str, password: str = PASSWORD) -> Any:
    return client.post("/api/datasets/token", json={"email": email, "password": password})


def _login(client: Any, email: str, password: str = PASSWORD) -> Any:
    return client.post("/api/auth/login", json={"email": email, "password": password})


def _statuses(client: Any, email: str, password: str, times: int) -> list[int]:
    return [_mint(client, email, password).status_code for _ in range(times)]


async def test_a_correct_password_costs_nothing_however_often_it_is_used(world, client):
    email = world["address"]("admin")
    statuses = _statuses(client, email, PASSWORD, _ACCOUNT_FAILURES + 5)
    assert statuses == [200] * (_ACCOUNT_FAILURES + 5), (
        "a correct password was charged for: an operator whose job mints a token on a schedule "
        f"would be locked out of the repository for using it. Statuses in order: {statuses}"
    )


async def test_wrong_passwords_close_this_door(world, client):
    email = world["address"]("admin")
    statuses = _statuses(client, email, WRONG, _ACCOUNT_FAILURES + 2)
    assert statuses[0] == 401, (
        f"the very first wrong password was not simply refused: {statuses}"
    )
    assert statuses[-1] == 429, (
        f"{_ACCOUNT_FAILURES + 2} wrong passwords in a row never closed this door: {statuses}"
    )
    refused = _mint(client, email, WRONG)
    assert refused.status_code == 429
    assert refused.json()["detail"] == THROTTLED_DETAIL


async def test_a_valid_credential_that_is_simply_not_an_admin_is_refunded(world, client):
    email = world["address"]("researcher")
    statuses = _statuses(client, email, PASSWORD, _ACCOUNT_FAILURES + 3)
    assert statuses == [403] * (_ACCOUNT_FAILURES + 3), (
        "a correct password that simply belongs to a non-admin was counted as a guess: "
        f"{statuses}"
    )


async def test_an_unknown_address_spends_nobody_s_budget(world, client):
    stranger = f"tokenbudget-nobody-{world['stamp']}@example.org"
    statuses = _statuses(client, stranger, WRONG, _ACCOUNT_FAILURES + 5)
    assert statuses == [401] * _ACCOUNT_FAILURES + [429] * 5, (
        f"an unknown address was not answered as a real one is: {statuses}"
    )
    assert _mint(client, world["address"]("admin")).status_code == 200, (
        "guessing at an address that does not exist spent the real admin's budget"
    )


async def test_one_account_is_one_bucket_across_both_credential_doors(world, client):
    email = world["address"]("admin")
    statuses = _statuses(client, email, WRONG, _ACCOUNT_FAILURES + 2)
    assert statuses[-1] == 429, f"the budget was not spent, so this test proves nothing: {statuses}"

    signed_in = _login(client, email)
    assert signed_in.status_code == 429, (
        "the sign-in door was still open after the mint door's budget was spent, so the two doors "
        f"hold separate allowances and either one can be used to refill the other: {signed_in.text}"
    )
