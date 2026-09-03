"""THE THIRD PASSWORD CHECK IN THIS API, AND THE ONE NOTHING STOOD IN FRONT OF.

``POST /api/auth/change-password`` verifies the CURRENT password on a request that is ALREADY
authenticated. Its own docstring says why it asks for that at all: *"accepting an empty current
password would turn a stolen Google session into a permanent password on the account"* — the route
exists so that holding somebody's token is not the same as owning their account.

**IT ENFORCED HALF OF THAT.** The thief could not SET a password without the old one, and until
2026-09-03 they could GUESS it here without limit and without ever meeting a refusal. The ASGI
limiter in ``app/scale/rate_limit.py`` does not cover this route and must not: its credential budget
is keyed on the network address of an ANONYMOUS caller, and every request here carries a bearer
token, so keying on it would let one stolen session mint itself an unlimited number of empty buckets
by rotating nothing at all. So a stolen session was an oracle against one specific account's
password, running at whatever rate bcrypt allows, with the prize being exactly the permanent
takeover this route was written to prevent.

The fix is the shape the sign-in door already uses: take a token from the per-account budget BEFORE
bcrypt, refund it on every outcome that is not a wrong password. What this module pins is that both
halves of that are true here, and — the part that is the actual finding — that it is the SAME bucket
the other two doors spend, so ten guesses is ten across the whole API rather than ten per endpoint.

WHY EVERY REQUEST BELOW CARRIES A TOKEN MINTED DIRECTLY rather than one obtained by signing in: the
budget is what is under test, and a helper that signed in first would spend and refund it on the way
to every assertion. ``create_access_token`` is what ``login`` would have returned anyway.

Postgres is required — the budget is keyed on a resolved account and bcrypt needs a stored hash — so
the module skips itself when ``DATABASE_URL`` does not point at a local database.

    docker compose up -d postgres minio
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma

``tests/conftest.py`` clears the per-account buckets between tests, so each test below starts from a
full allowance without knowing about the others.
"""

import uuid
from datetime import UTC, datetime
from typing import Any

import pytest
from conftest import needs_db

from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.scale.rate_limit import _ACCOUNT_FAILURES

pytestmark = [needs_db, pytest.mark.anyio]

PASSWORD = "change-password-budget-original"
#: What the successful-change test rotates to and back from. Two spellings rather than one, because
#: changing a password to itself is not the thing an account actually does and a route that rejected
#: it would make that test pass for the wrong reason.
OTHER_PASSWORD = "change-password-budget-second"
WRONG = "not-the-current-password"

#: THE SIGN-IN DOOR'S OWN SENTENCE, asserted verbatim here on purpose. It is the same budget, so a
#: person refused at one door and given a different explanation at the other has been told there are
#: two limits. Written out rather than imported for the reason ``test_platform_access_gate`` gives
#: about its own refusals: importing the constant makes the test agree with whatever it is changed
#: to, including with the other doors' wording.
THROTTLED_DETAIL = (
    "Too many failed sign-in attempts for this account. Wait a few minutes and try again — "
    "a sign-in that succeeds does not count against this limit."
)

#: slug -> whether the account has a password at all. The Google-provisioned account is the control
#: for the 400 arm: there is nothing to guess at, so nothing may be charged.
ACCOUNTS: tuple[tuple[str, bool], ...] = (
    ("owner", True),
    ("sharer", True),
    ("googler", False),
)


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    """Three accounts, all admitted, two of them with a password."""
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
                    # NULL for the Google account, which is the state ``passwordSetAt`` exists to
                    # keep distinguishable from "has never had one".
                    "passwordHash": hash_password(PASSWORD) if has_password else None,
                }
            )
            # Admitted, so that a sign-in used as evidence in the shared-bucket test below is
            # refused by the BUDGET and not by the platform gate.
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
    """A bearer token minted directly — see the module docstring. This is the thief's token in every
    test below and the account holder's in the rest; the route cannot tell them apart, which is the
    entire reason it asks for the current password."""
    return {"Authorization": f"Bearer {create_access_token(subject=world['people'][slug].id)}"}


def _change(client: Any, world: dict[str, Any], slug: str, current: str, new: str) -> Any:
    return client.post(
        "/api/auth/change-password",
        json={"currentPassword": current, "newPassword": new},
        headers=_headers(world, slug),
    )


def _login(client: Any, email: str, password: str) -> Any:
    return client.post("/api/auth/login", json={"email": email, "password": password})


async def test_a_stolen_session_cannot_guess_the_current_password_forever(world, client):
    """**THE FINDING.** Unlimited guesses at the current password, from inside a session.

    The first refusal is asserted to be a plain 401 rather than merely "not a 429": a budget that
    closed on the very first attempt would pass a bare "there is a 429 somewhere in here" test while
    making the route unusable for anybody who mistypes once. The last is asserted to be the 429, and
    the sentence with it, because a limit whose body says nothing actionable is a limit that gets
    raised until it stops limiting.
    """
    statuses = [
        _change(client, world, "owner", WRONG, "a-brand-new-password").status_code
        for _ in range(_ACCOUNT_FAILURES + 2)
    ]
    assert statuses[0] == 401, f"the very first wrong current password was not simply refused: {statuses}"
    assert statuses[-1] == 429, (
        f"{_ACCOUNT_FAILURES + 2} wrong guesses in a row never closed this route, so a stolen "
        f"session is still an unlimited oracle against this account's password: {statuses}"
    )
    refused = _change(client, world, "owner", WRONG, "a-brand-new-password")
    assert refused.status_code == 429
    assert refused.json()["detail"] == THROTTLED_DETAIL


async def test_the_owner_changing_their_own_password_is_charged_nothing(world, client):
    """**THE REFUND, WHICH IS WHAT LETS THE CEILING BE TEN.**

    Somebody who knows their password may change it as often as they like. A budget that counted
    attempts rather than failures would refuse the eleventh change — and the population that changes
    a password eleven times in five minutes is somebody following an instruction to, not an attacker.

    The password is rotated between two spellings rather than set to itself, so each call is a real
    change and the NEXT call has to present what the previous one wrote. That also means a route that
    silently failed to store the new password would fail here on the second iteration rather than
    passing quietly.
    """
    email = world["address"]("sharer")
    current, following = PASSWORD, OTHER_PASSWORD
    statuses = []
    for _ in range(_ACCOUNT_FAILURES + 4):
        statuses.append(_change(client, world, "sharer", current, following).status_code)
        current, following = following, current
    assert statuses == [200] * (_ACCOUNT_FAILURES + 4), (
        f"a correct current password was charged for: {statuses}"
    )
    # AND THE ACCOUNT IS STILL USABLE AFTERWARDS, through the door a person actually uses. Without
    # this the loop above would also pass for a route that answered 200 and wrote nothing.
    assert _login(client, email, current).status_code == 200, (
        "the password the last successful change wrote is not the one the account now has"
    )


async def test_an_account_with_no_password_is_told_so_and_charged_nothing(world, client):
    """THE 400 IS NOT A GUESS. There is nothing stored to compare against, so nothing was tried.

    Charging it would let somebody spend a Google-provisioned account's budget by asking a question
    that cannot be answered — and the account's owner, who has no password to type, would then find
    the SIGN-IN door refusing them as well, over a route they cannot use in the first place.
    """
    statuses = [
        _change(client, world, "googler", WRONG, "a-brand-new-password").status_code
        for _ in range(_ACCOUNT_FAILURES + 5)
    ]
    assert statuses == [400] * (_ACCOUNT_FAILURES + 5), (
        "an account with no password to change was charged a guessing budget for saying so: "
        f"{statuses}"
    )


async def test_the_budget_here_is_the_same_one_the_sign_in_door_spends(world, client):
    """**ONE ACCOUNT, ONE ALLOWANCE, ACROSS EVERY DOOR THAT CHECKS A PASSWORD.**

    A budget per door is three budgets, and an attacker picks whichever still has tokens in it. The
    evidence has to be a sign-in that WOULD have succeeded: the budget is taken before bcrypt, so a
    429 for the correct password is the only outcome that can only be explained by the two doors
    sharing one bucket rather than each having one of their own.
    """
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
