import asyncio
import os
import uuid
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime
from typing import Any

import pytest

from app.core.db import db
from app.services.designers import (
    adopt_allow_list_name,
    ensure_empanelled,
    name_on_the_allow_list,
)

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
]

ROSTER_OWN_NAME = "Dr S. Raghavan (roster screen, typed by hand)"

ACCESS_NAME = "Sowmya Raghavan"


def _in_a_private_loop[T](work: Callable[[], Awaitable[T]]) -> T:
    async def connected() -> T:
        await db.connect()
        try:
            return await work()
        finally:
            await db.disconnect()

    return asyncio.run(connected())


@pytest.fixture
def stamp() -> str:
    return uuid.uuid4().hex[:8]


async def _admit(email: str, *, full_name: str | None) -> Any:
    return await db.accessroster.create(
        data={
            "email": email,
            "status": "ACTIVE",
            "admitRole": "DESIGNER",
            "fullName": full_name,
            "joinedAt": datetime.now(UTC),
            "notes": "Seeded by tests/test_designer_roster_names.py.",
        }
    )


async def _roster_row(email: str) -> Any:
    return await db.designerroster.find_first(where={"email": email})



def test_an_empanelment_is_created_carrying_the_allow_lists_name(stamp):
    email = f"names-carried-{stamp}@example.org"

    async def scenario() -> None:
        await _admit(email, full_name=ACCESS_NAME)

        assert await ensure_empanelled(email) is True

        row = await _roster_row(email)
        assert row is not None, "the empanelment itself must still be created"
        assert row.fullName == ACCESS_NAME, (
            "the roster row must show the name the administrator typed on the allow-list; a bare "
            "address here IS the reported defect"
        )
        assert row.isActive is True

    _in_a_private_loop(scenario)


def test_no_name_on_the_allow_list_leaves_the_column_null_rather_than_empty(stamp):
    email = f"names-none-{stamp}@example.org"

    async def scenario() -> None:
        await _admit(email, full_name=None)

        assert await ensure_empanelled(email) is True

        row = await _roster_row(email)
        assert row.fullName is None

    _in_a_private_loop(scenario)


def test_an_empanelment_with_no_allow_list_row_at_all_is_still_created(stamp):
    email = f"names-noaccess-{stamp}@example.org"

    async def scenario() -> None:
        assert await ensure_empanelled(email) is True

        row = await _roster_row(email)
        assert row is not None
        assert row.fullName is None

    _in_a_private_loop(scenario)



def test_the_name_is_found_across_a_gmail_dot(stamp):
    dotted = f"names.dot.{stamp}@gmail.com"
    mailbox = f"namesdot{stamp}@gmail.com"

    async def scenario() -> None:
        await _admit(dotted, full_name=ACCESS_NAME)

        assert await ensure_empanelled(dotted) is True

        assert await _roster_row(dotted) is None
        row = await _roster_row(mailbox)
        assert row is not None
        assert row.fullName == ACCESS_NAME

    _in_a_private_loop(scenario)


def test_the_canonical_spelling_wins_when_both_rows_carry_a_name(stamp):
    dotted = f"names.tie.{stamp}@gmail.com"
    mailbox = f"namestie{stamp}@gmail.com"

    async def scenario() -> None:
        await _admit(dotted, full_name="Typed under the dotted spelling")
        await _admit(mailbox, full_name="Typed under the mailbox")

        for _ in range(3):
            assert await name_on_the_allow_list(dotted) == "Typed under the mailbox"
            assert await name_on_the_allow_list(mailbox) == "Typed under the mailbox"

    _in_a_private_loop(scenario)


def test_a_row_without_a_name_never_shadows_one_that_has_a_name(stamp):
    dotted = f"names.shadow.{stamp}@gmail.com"
    mailbox = f"namesshadow{stamp}@gmail.com"

    async def scenario() -> None:
        await _admit(mailbox, full_name=None)
        await _admit(dotted, full_name=ACCESS_NAME)

        assert await name_on_the_allow_list(dotted) == ACCESS_NAME

    _in_a_private_loop(scenario)


def test_a_canonical_address_cannot_reach_a_dotted_allow_list_row(stamp):
    dotted = f"names.oneway.{stamp}@gmail.com"
    mailbox = f"namesoneway{stamp}@gmail.com"

    async def scenario() -> None:
        await _admit(dotted, full_name=ACCESS_NAME)

        assert await name_on_the_allow_list(dotted) == ACCESS_NAME
        assert await name_on_the_allow_list(mailbox) is None, (
            "if this now finds the name, the live lookup has been widened — see the docstring"
        )

    _in_a_private_loop(scenario)



def test_adopting_a_name_refuses_a_row_that_already_has_one(stamp):
    email = f"names-keep-{stamp}@example.org"

    async def scenario() -> None:
        await _admit(email, full_name=ACCESS_NAME)
        await db.designerroster.create(
            data={"email": email, "fullName": ROSTER_OWN_NAME, "isActive": True}
        )

        assert await adopt_allow_list_name(email, ACCESS_NAME) is False

        row = await _roster_row(email)
        assert row.fullName == ROSTER_OWN_NAME, (
            "an administrator's own words on the roster screen must survive an allow-list edit"
        )

    _in_a_private_loop(scenario)


def test_adopting_a_name_fills_a_row_that_has_none(stamp):
    email = f"names-late-{stamp}@example.org"

    async def scenario() -> None:
        await _admit(email, full_name=ACCESS_NAME)
        await db.designerroster.create(data={"email": email, "fullName": None, "isActive": True})

        assert await adopt_allow_list_name(email, ACCESS_NAME) is True
        assert (await _roster_row(email)).fullName == ACCESS_NAME

        assert await adopt_allow_list_name(email, ACCESS_NAME) is False

    _in_a_private_loop(scenario)


def test_no_google_display_name_can_reach_the_designer_roster(stamp):
    email = f"names-google-{stamp}@example.org"

    async def scenario() -> None:
        await db.user.create(
            data={
                "email": email,
                "name": "APPROVE ME — urgent request from IT",
                "role": "DESIGNER",
            }
        )
        await _admit(email, full_name=ACCESS_NAME)

        assert await ensure_empanelled(email) is True

        row = await _roster_row(email)
        assert row.fullName == ACCESS_NAME
        assert "IT" not in (row.fullName or ""), (
            "a Google display name must never reach this column"
        )

    _in_a_private_loop(scenario)



@pytest.mark.parametrize("blank", ["", "   ", "\t\n"])
def test_a_whitespace_only_allow_list_name_is_not_carried(stamp, blank):
    email = f"names-blank-{abs(hash(blank))}-{stamp}@example.org"

    async def scenario() -> None:
        await _admit(email, full_name=blank)

        assert await name_on_the_allow_list(email) is None
        assert await ensure_empanelled(email) is True
        assert (await _roster_row(email)).fullName is None

    _in_a_private_loop(scenario)


def test_adopting_a_blank_name_is_a_no_op_rather_than_a_write_of_null(stamp):
    email = f"names-blankwrite-{stamp}@example.org"

    async def scenario() -> None:
        await db.designerroster.create(
            data={"email": email, "fullName": ROSTER_OWN_NAME, "isActive": True}
        )

        assert await adopt_allow_list_name(email, "   ") is False
        assert await adopt_allow_list_name(email, None) is False
        assert (await _roster_row(email)).fullName == ROSTER_OWN_NAME

    _in_a_private_loop(scenario)


def test_adopting_a_name_for_an_empanelment_that_does_not_exist_writes_nothing(stamp):
    email = f"names-absent-{stamp}@example.org"

    async def scenario() -> None:
        assert await adopt_allow_list_name(email, ACCESS_NAME) is False
        assert await _roster_row(email) is None

    _in_a_private_loop(scenario)



def test_a_suspended_empanelment_takes_the_name_and_keeps_its_suspension(stamp):
    email = f"names-suspended-{stamp}@example.org"
    revoked_at = datetime(2026, 3, 14, 9, 30, tzinfo=UTC)

    async def scenario() -> None:
        await db.designerroster.create(
            data={
                "email": email,
                "fullName": None,
                "isActive": False,
                "revokedAt": revoked_at,
                "notes": "Suspended by an administrator in March.",
            }
        )

        assert await adopt_allow_list_name(email, ACCESS_NAME) is True

        row = await _roster_row(email)
        assert row.fullName == ACCESS_NAME
        assert row.isActive is False, "naming a row must not be able to let anybody sign in"
        assert row.revokedAt is not None and row.revokedAt.replace(tzinfo=UTC) == revoked_at
        assert row.notes == "Suspended by an administrator in March."

    _in_a_private_loop(scenario)


def test_a_suspended_empanelment_is_still_never_revived_by_the_name_carrying_path(stamp):
    email = f"names-revoked-{stamp}@example.org"
    revoked_at = datetime(2026, 1, 5, 12, 0, tzinfo=UTC)

    async def scenario() -> None:
        await _admit(email, full_name=ACCESS_NAME)
        await db.designerroster.create(
            data={
                "email": email,
                "fullName": ROSTER_OWN_NAME,
                "isActive": False,
                "revokedAt": revoked_at,
                "notes": "Revoked. Do not restore from the allow-list.",
            }
        )

        assert await ensure_empanelled(email) is False

        row = await _roster_row(email)
        assert row.isActive is False
        assert row.fullName == ROSTER_OWN_NAME
        assert row.notes == "Revoked. Do not restore from the allow-list."

    _in_a_private_loop(scenario)
