"""THE SECOND DOOR THAT TURNS A PASSWORD INTO A TOKEN, AND THE LOCKOUT IT DID NOT HAVE.

``POST /api/datasets/token`` takes an email and a password and answers with a thirty-day, read-only
credential over the ENTIRE repository. ``POST /api/auth/login`` takes the same email and the same
password and answers with a seven-day session. The sign-in door has charged a per-account guessing
budget in front of its bcrypt call since 2026-08-30; until 2026-09-03 this one charged nothing at
all — so every guess the sign-in door refused could simply be re-aimed here, at the door with the
better prize, and nothing counted it. A lock on one of two doors into the same room is not a lock.

**THE MIDDLEWARE IN ``app/scale/rate_limit.py`` IS NOT THE SAME PROTECTION AND NEVER WAS.** This path
IS on ``_CREDENTIAL_PREFIXES``, so it has always had the per-NETWORK allowance — but that budget is
keyed on the address the request arrived from, so an attacker on a different mobile network for each
guess, or spread across a botnet, meets a fresh full bucket every time for one victim. The budget
asserted here is keyed on ``user.id``. Those are different questions and the tests below are careful
to be about the second one: the module fires more requests than the network ceiling would allow, and
they are not refused by it, because the flag that installs the middleware is off in this suite.

WHAT IS PINNED, IN THE ORDER THE TESTS MAKE THE ARGUMENT:

1. Wrong passwords close the door, and the sentence a machine operator reads says the actionable
   thing — that a CORRECT password costs nothing.
2. A correct password really does cost nothing, however often it is used. This is the property that
   lets the ceiling be as low as ten, and a regression in it locks a nightly export job out of the
   repository for being configured correctly.
3. The refund covers every outcome that is not a wrong password — including the 403 a non-admin gets
   with perfectly valid credentials, which is reached AFTER bcrypt and so would otherwise punish
   somebody for the one mistake this endpoint's docstring says it exists to report early.
4. **ONE ACCOUNT IS ONE BUCKET ACROSS BOTH DOORS.** Spending the budget here closes the sign-in door
   too. That is the whole finding: two budgets would be two allowances an attacker picks between.
5. An address with no account behind it spends nobody's budget, so a stranger cannot lock a real
   admin out by guessing at an address that does not exist.

Postgres is required — the budget is keyed on a resolved account id, so there has to be an account —
so the module skips itself when ``DATABASE_URL`` does not point at a local database.

    docker compose up -d postgres minio
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma

``tests/conftest.py`` clears the per-account buckets between tests, which is what lets each test
below start from a full allowance without any of them knowing about the others.
"""

import uuid
from datetime import UTC, datetime
from typing import Any

import pytest
from conftest import needs_db

from app.core.db import db
from app.core.security import hash_password
from app.scale.rate_limit import _ACCOUNT_FAILURES

pytestmark = [needs_db, pytest.mark.anyio]

#: Long enough for ``LoginRequest``'s ``min_length=8`` on both the right and the wrong spelling — a
#: 422 from a short string would be refunded and the test would pass while asserting nothing.
PASSWORD = "dataset-token-budget-password"
WRONG = "not-the-password-either"

#: The sentence this endpoint answers a spent budget with, ASSERTED VERBATIM rather than imported.
#: It is written for an operator at a terminal and not for a designer at a form, and the half that
#: matters is the second clause: a correct password does not count, so a job that is merely
#: misconfigured is not making its own situation worse by retrying. Importing the constant would make
#: this file agree with whatever that string is changed to, including into the sign-in screen's.
THROTTLED_DETAIL = (
    "Too many failed credential attempts for this account. Wait a few minutes and try again — "
    "a correct password does not count against this limit."
)

#: slug -> role. Both carry a password and an ACTIVE allow-list row, so that what these tests observe
#: is the BUDGET and never the platform gate refusing somebody first.
ACCOUNTS: tuple[tuple[str, str], ...] = (
    ("admin", "ADMIN"),
    # A perfectly valid credential that is not an admin. Refused at 403 AFTER bcrypt, which is the
    # outcome the refund exists for.
    ("researcher", "RESEARCHER"),
)


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    """Two accounts, both admitted, both with a password.

    Written directly rather than through ``POST /api/users`` because these are the accounts the
    tests act AS, and creating them through the API would put every assertion below behind an
    endpoint this module is not about.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        # Lower-cased: both roster tables store the lower-cased address, and this endpoint looks the
        # account up with ``(payload.email or "").lower()``.
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
            # A ``User`` row written directly is not one of the paths that admit somebody, so without
            # this the mint would answer "awaiting administrator approval" and every test here would
            # be measuring ``assert_access_admits`` instead of the budget.
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
    """The status of each of ``times`` attempts, in order. A list and not a set: WHERE the refusal
    starts is the assertion, and a set cannot say."""
    return [_mint(client, email, password).status_code for _ in range(times)]


async def test_a_correct_password_costs_nothing_however_often_it_is_used(world, client):
    """**THE PROPERTY THAT LETS THE CEILING BE TEN.**

    A nightly export job mints a token every night; a busy pipeline might mint one every few minutes.
    A budget that counted ATTEMPTS rather than failures would refuse the tenth correct credential of
    the day, and the endpoint would start failing for the accounts that were configured properly —
    which is both the wrong population and the one that will file it as an outage.

    Deliberately more attempts than the ceiling, so a charge that was never refunded shows up as a
    429 partway down the list rather than as nothing at all.
    """
    email = world["address"]("admin")
    statuses = _statuses(client, email, PASSWORD, _ACCOUNT_FAILURES + 5)
    assert statuses == [200] * (_ACCOUNT_FAILURES + 5), (
        "a correct password was charged for: an operator whose job mints a token on a schedule "
        f"would be locked out of the repository for using it. Statuses in order: {statuses}"
    )


async def test_wrong_passwords_close_this_door(world, client):
    """The finding, stated as behaviour: this endpoint refuses to be guessed at indefinitely.

    The first attempt is asserted to be a 401 rather than merely "not a 429", because a budget that
    refused from the very first request would pass a bare "there is a 429 in here somewhere" test
    while being an outage. The last is asserted to be the 429, and the sentence is checked, because
    a 429 whose body says something else is a limit an operator cannot act on.
    """
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
    """**THE 403 IS REACHED AFTER BCRYPT, SO CHARGING IT WOULD PUNISH THE WRONG PERSON.**

    A researcher pointing a script at this endpoint has typed their own password correctly. The
    endpoint's own docstring says it refuses them HERE, at issue time, so "the operator wiring up a
    cron job finds out while they are looking at the terminal" — and a limiter that then locked that
    account out for ten minutes would turn a clear message into an intermittent one.

    More attempts than the ceiling, so an unrefunded charge cannot hide.
    """
    email = world["address"]("researcher")
    statuses = _statuses(client, email, PASSWORD, _ACCOUNT_FAILURES + 3)
    assert statuses == [403] * (_ACCOUNT_FAILURES + 3), (
        "a correct password that simply belongs to a non-admin was counted as a guess: "
        f"{statuses}"
    )


async def test_an_unknown_address_spends_nobody_s_budget(world, client):
    """A stranger must not be able to close a real admin's door by guessing at an address.

    There is no account, so there is no bucket to charge — the budget is keyed on the RESOLVED
    account id, which is exactly what the middleware next door cannot do. The assertion is made from
    the other side, on the real admin, because "these all answered 401" would also be true of an
    implementation that charged a shared bucket for unknown addresses.
    """
    stranger = f"tokenbudget-nobody-{world['stamp']}@example.org"
    statuses = _statuses(client, stranger, WRONG, _ACCOUNT_FAILURES + 5)
    assert set(statuses) == {401}, f"an unknown address was answered something else: {statuses}"
    assert _mint(client, world["address"]("admin")).status_code == 200, (
        "guessing at an address that does not exist spent the real admin's budget"
    )


async def test_one_account_is_one_bucket_across_both_credential_doors(world, client):
    """**THE FINDING IN ONE ASSERTION.** Spending the budget HERE closes the SIGN-IN door too.

    Two budgets would be two allowances, and an attacker refused at one door would simply walk to the
    other — which is the state this product was in until 2026-09-03, with the unguarded door being
    the one that hands out thirty days of read access to the whole repository.

    The sign-in below carries the CORRECT password, deliberately. The budget is taken before bcrypt,
    so a 429 for a credential that would otherwise have succeeded is the only shape of evidence that
    the two doors really share one bucket rather than merely both having one.
    """
    email = world["address"]("admin")
    statuses = _statuses(client, email, WRONG, _ACCOUNT_FAILURES + 2)
    assert statuses[-1] == 429, f"the budget was not spent, so this test proves nothing: {statuses}"

    signed_in = _login(client, email)
    assert signed_in.status_code == 429, (
        "the sign-in door was still open after the mint door's budget was spent, so the two doors "
        f"hold separate allowances and either one can be used to refill the other: {signed_in.text}"
    )
