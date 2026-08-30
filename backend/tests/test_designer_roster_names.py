"""THE NAME AN ADMINISTRATOR TYPED REACHES BOTH ROSTERS, AND NOTHING ELSE EVER REACHES EITHER.

The reported failure, in the words it arrived in: *"the names of the designers did not go through,
even though they have been added"*. ``/admin/designers`` was listing bare email addresses for
fifteen people whose names were sitting, correctly typed, one screen away on ``/admin/access``. The
roster read as though those designers were half-registered, and the only repair an administrator
had was to retype — on a second screen — a name they had already given the product once.

**THE CAUSE WAS A GOOD RULE APPLIED ONE TABLE TOO WIDELY.** ``ensure_empanelled`` left ``fullName``
NULL on purpose, because the column records what an ADMINISTRATOR typed and a display name lifted
off a Google profile is chosen by whoever owns that account. That argument is right, and it does not
reach ``AccessRoster.fullName``: that column is admin-typed under the same rule, on the neighbouring
screen, and is never written from a login attempt. So the rule stayed and the source changed.

**FIVE THINGS ARE PINNED HERE, AND THE THIRD IS THE ONE THAT KEEPS THE OTHER FOUR HONEST.**

1. **THE NAME IS CARRIED AT CREATION**, so a designer admitted with a name recorded appears on the
   roster screen as that name from the first moment, with no second visit needed.

2. **IT IS CARRIED ACROSS A GMAIL DOT.** An admin types ``priya.k@gmail.com`` on the allow-list; the
   empanelment is created under the mailbox, ``priyak@gmail.com``. They are one person. A lookup
   that could not see across the dot would answer "no name" while the admin is looking at the name —
   which is the same class of failure as the sign-in refusal this whole family of functions exists
   to close, and it would look identical from the roster screen.

3. **A NAME IS NEVER OVERWRITTEN, AND A GOOGLE DISPLAY NAME NEVER ARRIVES AT ALL.** These are the
   two ways a "helpful" name-carrying feature does harm. The first would let an edit on one screen
   silently rewrite a colleague's work on another; the second would put a string the account holder
   controls onto a screen an administrator reads as a record of institutional decisions. Section 3
   asserts both, and asserts the second by giving the account a Google display name and checking it
   is nowhere.

4. **A BLANK IS NOT A NAME.** NULL and ``""`` are the same fact to the roster screen — both fall
   back to the email address — so a whitespace-only allow-list value must not be copied across as
   though something had been recorded. A row reported as named while the screen still shows an
   address is worse than the original complaint, because it looks fixed.

5. **A SUSPENDED EMPANELMENT IS NAMED TOO, AND STAYS SUSPENDED.** The reader who most needs a row to
   say who it was about is the administrator reading back an empanelment that ENDED. A name is not a
   standing: section 5 asserts ``isActive`` and ``revokedAt`` come out of the write untouched, which
   is what makes it safe for ``adopt_allow_list_name`` to ignore them.

``scripts/backfill_roster_names.py`` applies the same repair to rows that already exist, through the
same two functions, so there is one implementation of every rule above.
"""

import os
import uuid
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
    pytest.mark.anyio,
]

#: What an administrator typed on the designer roster itself. Section 3 asserts this exact string
#: survives an allow-list edit, rather than merely asserting "a name is present" — a code path that
#: overwrote it with the allow-list's copy would leave a name present and would still be the bug.
ROSTER_OWN_NAME = "Dr S. Raghavan (roster screen, typed by hand)"

#: The name on the allow-list row. Deliberately different from :data:`ROSTER_OWN_NAME` so that every
#: assertion below can tell WHICH screen a value came from, rather than merely that one arrived.
ACCESS_NAME = "Sowmya Raghavan"


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture
async def stamp():
    """A fresh suffix per test, and a connection open for the duration.

    FUNCTION-SCOPED RATHER THAN MODULE-SCOPED, unlike the fixture in
    ``test_designer_empanelment_auto.py``, because almost every test here WRITES to the two rosters
    and then asserts on the state of a specific row. Sharing one set of addresses across tests would
    make the order they run in part of what is being asserted, and the way that fails is a suite
    that is green until somebody adds a test in the middle of it.
    """
    await db.connect()
    try:
        yield uuid.uuid4().hex[:8]
    finally:
        await db.disconnect()


async def _admit(email: str, *, full_name: str | None) -> Any:
    """An ACTIVE allow-list row admitting this address as a DESIGNER.

    Written straight into the table rather than through ``POST /api/access/roster`` for the reason
    that endpoint's own tests give: it now empanels an admitted designer itself, so it would create
    the roster row before the test began — and in several tests below the ABSENCE of that row, or
    its particular contents, is the state under test.
    """
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


# ══════════════════════════════════════════════════════════════════════════════════════
# 1. The name is carried at creation
# ══════════════════════════════════════════════════════════════════════════════════════


async def test_an_empanelment_is_created_carrying_the_allow_lists_name(stamp):
    """THE HEADLINE FIX. Fifteen live rows looked like this and showed a bare address."""
    email = f"names-carried-{stamp}@example.org"
    await _admit(email, full_name=ACCESS_NAME)

    assert await ensure_empanelled(email) is True

    row = await _roster_row(email)
    assert row is not None, "the empanelment itself must still be created"
    assert row.fullName == ACCESS_NAME, (
        "the roster row must show the name the administrator typed on the allow-list; a bare "
        "address here IS the reported defect"
    )
    assert row.isActive is True


async def test_no_name_on_the_allow_list_leaves_the_column_null_rather_than_empty(stamp):
    """THE HONEST ANSWER IS NULL, AND IT IS NOT THE EMPTY STRING.

    Both render identically on the roster screen, so this looks like pedantry until somebody writes
    a query for "rows still needing a name" — ``fullName IS NULL`` is the natural way to write it,
    and an empty string makes those rows invisible to it. ``scripts/backfill_roster_names.py``
    treats the two identically for exactly this reason; the WRITE path should still not create the
    ambiguity in the first place.
    """
    email = f"names-none-{stamp}@example.org"
    await _admit(email, full_name=None)

    assert await ensure_empanelled(email) is True

    row = await _roster_row(email)
    assert row.fullName is None


async def test_an_empanelment_with_no_allow_list_row_at_all_is_still_created(stamp):
    """THE NAME LOOKUP MUST NOT BE ABLE TO REFUSE THE EMPANELMENT.

    ``ensure_empanelled`` is called from ``auth.login``. Somebody signing in as a DESIGNER whose
    allow-list row is missing — the master admin, or any path a future change opens — must be
    empanelled exactly as before; a name is decoration, and decoration that can throw would turn a
    cosmetic feature into a sign-in outage.
    """
    email = f"names-noaccess-{stamp}@example.org"

    assert await ensure_empanelled(email) is True

    row = await _roster_row(email)
    assert row is not None
    assert row.fullName is None


# ══════════════════════════════════════════════════════════════════════════════════════
# 2. Across a Gmail dot
# ══════════════════════════════════════════════════════════════════════════════════════


async def test_the_name_is_found_across_a_gmail_dot(stamp):
    """The allow-list holds the DOTTED spelling; the empanelment is written under the MAILBOX.

    This is the live pairing, not a hypothetical: ``tanyavanvari.nift@gmail.com`` was admitted under
    that spelling and empanelled as ``tanyavanvarinift@gmail.com``, and the production backfill
    reported the two as one person. A ``find_unique`` on either string answers None about the other.
    """
    dotted = f"names.dot.{stamp}@gmail.com"
    mailbox = f"namesdot{stamp}@gmail.com"
    await _admit(dotted, full_name=ACCESS_NAME)

    assert await ensure_empanelled(dotted) is True

    # Created under the mailbox, which is ``canonical_email``'s job and is asserted here because the
    # name lookup has to survive that translation rather than depend on the spelling that arrived.
    assert await _roster_row(dotted) is None
    row = await _roster_row(mailbox)
    assert row is not None
    assert row.fullName == ACCESS_NAME


async def test_the_canonical_spelling_wins_when_both_rows_carry_a_name(stamp):
    """DETERMINISM WHEN THE ALLOW-LIST HOLDS THE SAME MAILBOX TWICE.

    ``AccessRoster.email`` is unique and two spellings are two different strings, so this state is
    reachable and exists on the live table. Whichever name is chosen is defensible; choosing a
    DIFFERENT one on different days is not, because it would make a roster screen that changes
    under a table nobody has touched.
    """
    dotted = f"names.tie.{stamp}@gmail.com"
    mailbox = f"namestie{stamp}@gmail.com"
    await _admit(dotted, full_name="Typed under the dotted spelling")
    await _admit(mailbox, full_name="Typed under the mailbox")

    for _ in range(3):
        assert await name_on_the_allow_list(dotted) == "Typed under the mailbox"
        assert await name_on_the_allow_list(mailbox) == "Typed under the mailbox"


async def test_a_row_without_a_name_never_shadows_one_that_has_a_name(stamp):
    """A NAMELESS ROW MUST NOT WIN THE TIE JUST BY BEING THE CANONICAL SPELLING.

    Sorting the matches by spelling BEFORE discarding the nameless ones would do exactly that, and
    the result is the original complaint — a bare address on the roster — arriving through the very
    code written to prevent it, with a name sitting on the other allow-list row.

    Asked from the DOTTED side, which is the direction ``email_match_keys`` can travel; the test
    below is about the direction it cannot.
    """
    dotted = f"names.shadow.{stamp}@gmail.com"
    mailbox = f"namesshadow{stamp}@gmail.com"
    await _admit(mailbox, full_name=None)
    await _admit(dotted, full_name=ACCESS_NAME)

    assert await name_on_the_allow_list(dotted) == ACCESS_NAME


async def test_a_canonical_address_cannot_reach_a_dotted_allow_list_row(stamp):
    """**THE LIMIT OF THE LIVE LOOKUP, PINNED AS A FACT RATHER THAN LEFT AS A SURPRISE.**

    ``email_match_keys`` returns the literal spelling and ADDS the canonical one. So it walks from a
    dotted address to a row stored either way, and it cannot walk back: an address that already IS
    the mailbox yields exactly one key and can never reach a row somebody typed with dots in it.
    That is what an INDEXED lookup can do — inverting it means a scan, on every sign-in — and it is
    the same limit every other gate in this product has.

    IT IS ALSO THE SHAPE THE LIVE DATA IS IN, which is why this is asserted rather than assumed.
    ``tanyavanvari.nift@gmail.com`` sits on the allow-list with an administrator's dots while the
    empanelment was written under the mailbox, because ``ensure_empanelled`` canonicalises. Anything
    starting from the ROSTER row therefore starts from the mailbox and finds nothing here.

    **SO THE BACKFILL IS NOT MERELY A CATCH-UP FOR OLD ROWS — IT IS THE ONLY THING THAT CLOSES THIS
    PAIRING AT ALL.** ``scripts/backfill_roster_names.py`` reads the whole allow-list once and
    indexes it under both its stored spelling AND its canonical form, which a bulk pass can afford
    and a per-request lookup cannot. If this assertion ever starts failing because the live lookup
    grew wider, that is good news and this test should be rewritten rather than deleted — but the
    backfill's dual index must not be removed on the strength of it, because
    ``scripts/backfill_email_canonicalisation.py`` has not been run everywhere yet.
    """
    dotted = f"names.oneway.{stamp}@gmail.com"
    mailbox = f"namesoneway{stamp}@gmail.com"
    await _admit(dotted, full_name=ACCESS_NAME)

    assert await name_on_the_allow_list(dotted) == ACCESS_NAME
    assert await name_on_the_allow_list(mailbox) is None, (
        "if this now finds the name, the live lookup has been widened — see the docstring"
    )


# ══════════════════════════════════════════════════════════════════════════════════════
# 3. Never overwritten, and never a Google display name
# ══════════════════════════════════════════════════════════════════════════════════════


async def test_adopting_a_name_refuses_a_row_that_already_has_one(stamp):
    """FILL, NEVER OVERWRITE — the rule that makes it safe to call this on every admin edit.

    The name on the designer roster was typed there, on that screen, about that empanelment. The
    allow-list's copy may be older, a different transliteration, or the initials somebody used to
    get an invitation out of the door.
    """
    email = f"names-keep-{stamp}@example.org"
    await _admit(email, full_name=ACCESS_NAME)
    await db.designerroster.create(
        data={"email": email, "fullName": ROSTER_OWN_NAME, "isActive": True}
    )

    assert await adopt_allow_list_name(email, ACCESS_NAME) is False

    row = await _roster_row(email)
    assert row.fullName == ROSTER_OWN_NAME, (
        "an administrator's own words on the roster screen must survive an allow-list edit"
    )


async def test_adopting_a_name_fills_a_row_that_has_none(stamp):
    """THE OTHER ORDER OF EVENTS: the empanelment exists first, the name arrives afterwards."""
    email = f"names-late-{stamp}@example.org"
    await _admit(email, full_name=ACCESS_NAME)
    await db.designerroster.create(data={"email": email, "fullName": None, "isActive": True})

    assert await adopt_allow_list_name(email, ACCESS_NAME) is True
    assert (await _roster_row(email)).fullName == ACCESS_NAME

    # IDEMPOTENT, and it reports honestly: the second call changes nothing and says so, which is
    # what lets the backfill's count line mean "rows I wrote" rather than "rows I looked at".
    assert await adopt_allow_list_name(email, ACCESS_NAME) is False


async def test_no_google_display_name_can_reach_the_designer_roster(stamp):
    """THE RULE THE ORIGINAL ``fullName is None`` WAS REALLY PROTECTING, kept intact.

    A display name is chosen by whoever owns the Google account and can be changed by them at any
    moment. ``AccessRoster`` refuses to store it — ``test_nothing_the_caller_controls_is_stored_
    beyond_the_address`` pins that — so the only way it could reach the designer roster is if
    something here read ``User.fullName``. Nothing does, and this asserts it from the outside: an
    account exists, carrying a name the account holder controls, and the empanelment comes out
    holding the ADMIN'S name instead.
    """
    email = f"names-google-{stamp}@example.org"
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
    assert "IT" not in (row.fullName or ""), "a Google display name must never reach this column"


# ══════════════════════════════════════════════════════════════════════════════════════
# 4. A blank is not a name
# ══════════════════════════════════════════════════════════════════════════════════════


@pytest.mark.parametrize("blank", ["", "   ", "\t\n"])
async def test_a_whitespace_only_allow_list_name_is_not_carried(stamp, blank):
    """NULL AND ``"   "`` ARE THE SAME FACT TO THE ROSTER SCREEN — both fall back to the address."""
    email = f"names-blank-{abs(hash(blank))}-{stamp}@example.org"
    await _admit(email, full_name=blank)

    assert await name_on_the_allow_list(email) is None
    assert await ensure_empanelled(email) is True
    assert (await _roster_row(email)).fullName is None


async def test_adopting_a_blank_name_is_a_no_op_rather_than_a_write_of_null(stamp):
    """CLEARING A NAME IS AN EDIT AN ADMIN MAKES ON THE ROSTER SCREEN, DELIBERATELY.

    It is not something that should happen to them as a side effect of tidying a different row next
    door — so a blank arriving from the allow-list writes nothing at all, and in particular does not
    erase what the roster screen holds.
    """
    email = f"names-blankwrite-{stamp}@example.org"
    await db.designerroster.create(
        data={"email": email, "fullName": ROSTER_OWN_NAME, "isActive": True}
    )

    assert await adopt_allow_list_name(email, "   ") is False
    assert await adopt_allow_list_name(email, None) is False
    assert (await _roster_row(email)).fullName == ROSTER_OWN_NAME


async def test_adopting_a_name_for_an_empanelment_that_does_not_exist_writes_nothing(stamp):
    """IT FILLS A ROW; IT DOES NOT CREATE ONE.

    Creating an empanelment is ``ensure_empanelled``'s decision and carries the create-only rule
    that protects every revocation an administrator has ever made. A name-carrying helper that could
    also bring a roster row into existence would be a second, undocumented door into that table.
    """
    email = f"names-absent-{stamp}@example.org"

    assert await adopt_allow_list_name(email, ACCESS_NAME) is False
    assert await _roster_row(email) is None


# ══════════════════════════════════════════════════════════════════════════════════════
# 5. Suspended rows are named too, and stay suspended
# ══════════════════════════════════════════════════════════════════════════════════════


async def test_a_suspended_empanelment_takes_the_name_and_keeps_its_suspension(stamp):
    """A NAME IS NOT A STANDING, AND THIS IS THE ASSERTION THAT SAYS SO IN CODE.

    ``adopt_allow_list_name`` does not consult ``isActive``, which is the correct behaviour and also
    the alarming-looking one — so what it must not touch is pinned here rather than left to the
    docstring. The revocation date is checked as well as the flag: a row that stayed suspended but
    lost the date an administrator's decision is recorded at is the same defect wearing a smaller
    hat, and it is the shape ``test_designer_empanelment_auto`` guards on the other write path.
    """
    email = f"names-suspended-{stamp}@example.org"
    revoked_at = datetime(2026, 3, 14, 9, 30, tzinfo=UTC)
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


async def test_a_suspended_empanelment_is_still_never_revived_by_the_name_carrying_path(stamp):
    """THE CREATE-ONLY RULE, RE-ASSERTED THROUGH THE DOOR THAT JUST GAINED A NEW READ.

    ``ensure_empanelled`` gained an ``AccessRoster`` lookup on its create path. If that read were
    ever placed above the existence check — the obvious "tidy" refactor, since both queries derive
    from the same keys — the function would still return False, so no test about its RETURN VALUE
    would notice. This one asserts the row itself, so a suspended empanelment cannot be revived or
    re-stamped by a change made in the name of the feature this module is about.
    """
    email = f"names-revoked-{stamp}@example.org"
    revoked_at = datetime(2026, 1, 5, 12, 0, tzinfo=UTC)
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
