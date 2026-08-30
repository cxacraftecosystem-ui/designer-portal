"""AUTO-EMPANELMENT: being admitted as a designer IS being empanelled — and it never revives a
revocation.

Requirement 28, and the live incident behind it. This repository gates a designer's sign-in twice
and passing one gate has never passed the other: ``AccessRoster`` decides who may sign in at all and
can promote the account to DESIGNER through ``admitRole``, while ``DesignerRoster`` decides whether
that DESIGNER is still empanelled. So an administrator could admit somebody as a designer, watch the
allow-list screen show them ACTIVE, and have the person read *"Your designer access has been
suspended"* — about an empanelment nobody had ever granted, with ``/admin/designers`` showing no row
at all to explain it. ``sandycraft3@gmail.com`` read exactly that, and the only remedy was for an
admin to notice and empanel the same person a second time in a second screen.

**SEVEN THINGS ARE PINNED HERE, AND THE SECOND ONE IS WHY THE OTHER SIX ARE SAFE.**

1. **AN ALLOW-LISTED DESIGNER IS EMPANELLED ON THE WAY IN**, by ``ensure_empanelled`` sitting
   between the platform gate and the empanelment gate in ``auth.login`` — the only line in the
   request where the final role and the admitted allow-list row are both in hand, and the one place
   that covers the password branch and the Google branch together.

2. **A SUSPENDED EMPANELMENT IS NEVER REVIVED.** Suspension is a deliberate revocation: the roster
   suspends rather than deletes precisely so the record of the empanelment outlives the ending of
   it. The obvious implementation — an upsert on the unique ``email`` — takes its update arm on
   exactly that row and hands the revoked person their standing back the next time they try to sign
   in, with nothing on either screen to say it happened. Every revocation any administrator has ever
   made would come undone that way, quietly, one login at a time. Section 1's revocation test is the
   guard, and it checks the admin's own note survived as well as the flag, because a row that stays
   suspended but loses what an admin wrote on it is the same defect wearing a smaller hat.

3. **THE APPROVAL PATH EMPANELS TOO, SO THE ADMIN CAN SEE IT.** Sign-in alone would be enough to let
   the person in and would leave ``/admin/designers`` empty until they first arrived — so an admin
   who has just approved a designer looks for them, does not find them, and reasonably concludes the
   approval did not take. What they do next is add the row by hand: a 409 if they type the address
   the same way, and a second unmatchable row if they do not.

4. **STATUS IS CHECKED, NOT ONLY ROLE — AND ON A PENDING ROW THAT IS AN ADMISSION DECISION.**
   ``AccessRosterUpdate`` deliberately cannot change ``status``, so an admin may edit ``admitRole``
   on a row in any state. Empanelling on the role alone would give a PENDING request an ACTIVE
   designer row, and ``auth.assert_access_admits`` accepts an ACTIVE designer row as an admission
   for a row that is missing or still PENDING — so editing a dropdown would silently approve
   somebody nobody had decided about. On a REJECTED or SUSPENDED row the same edit does not let them
   in (neither state is "waiting"), but it does put an active empanelment on the roster screen for
   somebody an administrator barred. Both are refused.

5. **``firstSeenAt`` IS LEFT FOR THE PERSON'S OWN ARRIVAL TO WRITE.** ``mark_roster_seen`` runs later
   in the same login under ``WHERE firstSeenAt IS NULL``, so a stamp written at creation would
   consume that write — and the approval path creates rows days before anybody opens the app. The
   column answers *"did the invitation ever reach them"*; a row stamped when it was granted reports
   every empanelment as accepted, which is worse than no signal because it looks like an answer.

6. **ONE MAILBOX IS ONE EMPANELMENT HOWEVER THE ADDRESS IS SPELLED — AND ONLY WHERE THE PROVIDER
   SAYS SO.** Google is the only sign-in path most designers here have, and it treats the dots and
   the ``+tag`` in a Gmail local part as noise. So an admin admitting ``sandy.craft3@gmail.com`` and
   a token arriving as ``sandycraft3@gmail.com`` are one person, and under ``normalise_email`` alone
   they were two keys — one of which matched nothing, which is how the incident above began.
   Section 4 pins that admission and that sign-in landing on ONE row, and pins the limit of the
   rule in the same breath: outside the Gmail domains a dot is an ordinary character in somebody
   else's local part, and folding it would hand one person's empanelment to a colleague who shares
   their surname.

7. **TWO SIGN-INS AT ONCE MAKE ONE ROW, AND NEITHER OF THEM AN ERROR.** The find-then-create is
   check-then-act with no lock held, so a designer opening the app on a phone and a laptop sends two
   requests that both read "no row" and both create one. The unique index on ``DesignerRoster.email``
   is what actually decides it; the loser's ``UniqueViolationError`` is caught, because the outcome
   of that race — a row that exists and admits them — is exactly what the person was asking for.
   Section 1 races it for real and then replays the losing half deterministically, since a race that
   only collides sometimes is a test that only tests sometimes.

Postgres is required — every behaviour here is a row deciding an HTTP status — so the module skips
itself when ``DATABASE_URL`` does not point at a local database, exactly as
``test_platform_access_gate`` does.

    docker compose up -d postgres minio
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma

Nothing here reaches Google. ``verify_google_token`` is the only thing stubbed, because it is the
only part of that path that leaves the process. Everything else — both roster reads, the create, the
refusals and the stamp — runs exactly as it does in production.

**EVERY ASSERTION IS MADE THROUGH A DOOR SOMEBODY USES.** The states are set up and read back through
``/api/auth/login``, ``/api/access/roster``, ``/api/users`` and ``/api/designers/roster`` rather than
by awaiting the ``db`` singleton inside a test. That is not stylistic: the singleton is connected by
the app's lifespan inside ``TestClient``'s own event loop, and awaiting it from a test's loop fails
with "bound to a different event loop" rather than with anything to do with the rule under test. See
the same note in ``tests/test_user_deletion.py``.
"""

import os
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from typing import Any

import pytest

from app.api.routes import auth as auth_routes
from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services import designers as designers_service
from app.services.designers import DERIVED_EMPANELMENT_NOTE

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]

PASSWORD = "empanelment-test-password"
#: The password ``POST /api/users`` will accept for an account this module creates through the API.
API_PASSWORD = "LocalDev123!"

# ASSERTED VERBATIM RATHER THAN IMPORTED, exactly as ``test_designer_roster`` and
# ``test_platform_access_gate`` assert them. These sentences ARE the feature — the whole ruling in
# ``auth.py:40-113`` is that the four refusals stay distinct — and importing the constants would make
# this file agree with whatever they are changed to, including being changed into each other.
DESIGNER_SUSPENDED_DETAIL = "Your designer access has been suspended. Contact the administrator."
PENDING_DETAIL = (
    "Your access request is awaiting administrator approval. This is not a password problem — an "
    "administrator has to approve this address before you can sign in."
)

#: What an administrator wrote on a roster row by hand. Distinctive on purpose: every test that
#: asserts an existing row was left alone asserts THIS text is still on it, so a code path that
#: "helpfully" rewrote the notes while leaving ``isActive`` correct still fails.
ADMIN_NOTE = "Empanelled by hand under the 2026 cluster programme. Do not remove."

#: slug -> role. Each of these gets a ``User`` row AND an ACTIVE allow-list row, so that what the
#: tests observe is the EMPANELMENT and never the platform gate refusing somebody first.
ACCOUNTS: tuple[tuple[str, str], ...] = (
    ("admin", "ADMIN"),
    # Allow-listed as a designer and never empanelled: requirement 28's account, and the exact shape
    # of the reported failure. The fixture creates no DesignerRoster row for it.
    ("newcomer", "DESIGNER"),
    # Empanelled, then revoked by an administrator. The guard on the create-only rule.
    ("revoked", "DESIGNER"),
    # Empanelled, and used only by the lost-race test.
    ("racer", "DESIGNER"),
)

#: slug -> isActive, for the accounts that start with an empanelment.
EMPANELLED: tuple[tuple[str, bool], ...] = (
    ("revoked", False),
    ("racer", True),
)

#: Slugs that get an ACTIVE allow-list row admitting their GMAIL MAILBOX as a DESIGNER, and nothing
#: else: no account, and — the part that matters — no empanelment of any kind. Section 5 builds the
#: roster row itself, under the dotted spelling, through the admin screen that still writes it.
#:
#: **WRITTEN STRAIGHT INTO THE TABLE, AND IT HAS TO BE.** Everywhere else in this module an
#: allow-list row is made through ``POST /api/access/roster``, which is the right instinct and is
#: wrong here for a specific reason: that endpoint now empanels an admitted designer, and it does so
#: from the CANONICAL address it just stored. It would therefore create the undotted roster row
#: itself, before either test began — and the absence of that row is the entire state section 5 is
#: about. Reaching this state through the endpoint is not possible, which is itself worth knowing:
#: it is why the tests below start from the admin screen instead.
GMAIL_ADMITTED: tuple[str, ...] = ("handtyped", "revokeddots")


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    """Accounts, their allow-list rows and the two pre-existing empanelments.

    Written directly rather than through the API because these are the states an admin's earlier
    decisions would have left behind — the module is about what happens NEXT to them.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        # LOWER-CASED, for ``normalise_email``'s reason: both roster tables store the lower-cased
        # address and ``User.email`` is not lower-cased at all, so a mixed-case fixture address is a
        # row that can never be matched — a person locked out by a capital letter, which is the
        # production failure that helper exists to prevent.
        return f"empanel-{slug}-{stamp}@example.org".lower()

    people: dict[str, Any] = {}
    await db.connect()
    try:
        for slug, role in ACCOUNTS:
            people[slug] = await db.user.create(data={
                "email": address(slug),
                "name": f"Empanelment {slug} {stamp}",
                "role": role,
                "passwordHash": hash_password(PASSWORD),
            })
            # THE PLATFORM ALLOW-LIST ADMITS EVERY ACCOUNT THIS MODULE CREATES. Inserting a `User`
            # row directly is not one of the paths that admit somebody, so without this every login
            # below would answer "awaiting administrator approval" and the module would be testing
            # `assert_access_admits` while claiming to test the empanelment. The same obligation
            # applies to any script that writes accounts straight into the database; see
            # app/services/access_roster.py.
            await db.accessroster.create(data={
                "email": address(slug),
                "status": "ACTIVE",
                "admitRole": role,
                "joinedAt": datetime.now(UTC),
                "notes": "Seeded by tests/test_designer_empanelment_auto.py.",
            })
        for slug in GMAIL_ADMITTED:
            # The MAILBOX, not a dotted spelling of it — this is the form ``POST /api/access/roster``
            # stores since canonicalisation shipped, so the row is exactly what that endpoint would
            # have left behind. Only the empanelment it would also have written is missing.
            await db.accessroster.create(data={
                "email": f"empanel{slug}{stamp}@gmail.com",
                "status": "ACTIVE",
                "admitRole": "DESIGNER",
                "joinedAt": datetime.now(UTC),
                "notes": "Seeded by tests/test_designer_empanelment_auto.py, section 5.",
            })
        for slug, is_active in EMPANELLED:
            await db.designerroster.create(data={
                "email": address(slug),
                "fullName": f"Roster row for {slug}",
                "isActive": is_active,
                # Never null on a row that cannot sign in: the roster screen reads the flag and the
                # date together, and a suspended row with no date reads as a bug in the screen
                # rather than as somebody's deliberate decision.
                "revokedAt": None if is_active else datetime.now(UTC),
                "notes": ADMIN_NOTE,
                "addedById": people["admin"].id,
            })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {"client": client, "people": people, "address": address, "stamp": stamp}


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any], slug: str = "admin") -> dict[str, str]:
    """A bearer token minted directly, because the gates under test live on the LOGIN path only. A
    helper that signed in first would make every assertion below depend on the thing being tested."""
    return {"Authorization": f"Bearer {create_access_token(subject=world['people'][slug].id)}"}


def _login(client: Any, email: str, password: str = PASSWORD) -> Any:
    return client.post("/api/auth/login", json={"email": email, "password": password})


def _google(client: Any, monkeypatch: Any, email: str) -> Any:
    """The Google branch with only the token verification replaced. Stubbing any deeper would test
    the stub instead of the admission rule."""
    monkeypatch.setattr(
        auth_routes,
        "verify_google_token",
        lambda _token: {"email": email, "email_verified": True, "name": "Google Person"},
    )
    return client.post("/api/auth/login", json={"googleIdToken": "stand-in-for-a-real-token"})


def _empanelments(client: Any, world: dict[str, Any], term: str | None = None) -> dict[str, Any]:
    """This run's DESIGNER-roster rows, keyed by email. Narrowed by the run stamp so a database
    carrying a hundred previous runs cannot push this run's rows off the first page — an absent row
    is an assertion in half the tests here, and it has to mean "not created" and not "not shown"."""
    response = client.get(
        "/api/designers/roster",
        params={"search": term or world["stamp"], "pageSize": 200},
        headers=_headers(world),
    )
    assert response.status_code == 200, response.text
    return {row["email"]: row for row in response.json()["items"]}


def _rows_spelling(
    client: Any, world: dict[str, Any], *spellings: str, term: str | None = None
) -> list[dict[str, Any]]:
    """Every DESIGNER-roster row spelled any of these ways, as a LIST and never as a dict.

    ``_empanelments`` above keys its answer by address, and for most of this module that is the
    right shape. It is the wrong shape for the two questions below, both of which are about HOW MANY
    rows one person has: a dict keyed by email cannot represent two rows for one mailbox — the Gmail
    twin, ``sandy.craft3@`` beside ``sandycraft3@`` — because they are two different keys that both
    have to be counted, and it silently reports a duplicate as a single entry when the two spellings
    happen to be the same string. Counting the rows the API actually returned is the only reading of
    "exactly one empanelment" that can fail when there are two.

    Every spelling being looked for is named by the caller rather than derived here with
    :func:`canonical_email`. Deriving them would ask the code under test what it thinks the mailbox
    is and then check the answer against itself, which is how a canonicalisation bug passes its own
    test.
    """
    response = client.get(
        "/api/designers/roster",
        params={"search": term or world["stamp"], "pageSize": 200},
        headers=_headers(world),
    )
    assert response.status_code == 200, response.text
    wanted = set(spellings)
    return [row for row in response.json()["items"] if row["email"] in wanted]


def _access_rows(client: Any, world: dict[str, Any], term: str | None = None) -> dict[str, Any]:
    """This run's allow-list rows, keyed by email. Narrowed for ``_empanelments``' reason."""
    response = client.get(
        "/api/access/roster",
        params={"search": term or world["stamp"], "pageSize": 200},
        headers=_headers(world),
    )
    assert response.status_code == 200, response.text
    return {row["email"]: row for row in response.json()["items"]}


def _admit(client: Any, world: dict[str, Any], email: str, role: str) -> Any:
    """Admit an address by hand — the admin action that ought to empanel a designer."""
    return client.post(
        "/api/access/roster", json={"email": email, "role": role}, headers=_headers(world)
    )


def _fresh(world: dict[str, Any], slug: str) -> str:
    """An address no earlier test has touched, carrying the run stamp so the readers above find it."""
    return f"empanel-{slug}-{world['stamp']}@example.org".lower()


def _make_designer_account(client: Any, world: dict[str, Any], email: str, label: str) -> None:
    """An account an admin typed in, at DESIGNER, with a password — and NO empanelment.

    ``POST /api/users`` admits the address on the platform allow-list (it has to: an account created
    by a path that did not admit it is somebody an administrator made and the sign-in page then
    refuses) and it does NOT empanel them. That combination is exactly the state the race below needs
    and it is a real one, so it is reached through the endpoint rather than assembled by hand.

    A password account rather than the Google branch, because the race needs the SAME address signed
    in twice at once and ``verify_google_token`` is stubbed per test through ``monkeypatch``, which
    is not safe to call from two threads at the same instant.
    """
    made = client.post(
        "/api/users",
        json={"email": email, "name": f"{label} {world['stamp']}", "password": API_PASSWORD,
              "role": "DESIGNER"},
        headers=_headers(world),
    )
    assert made.status_code == 201, made.text


def _two_sign_ins_at_once(client: Any, email: str) -> list[Any]:
    """One address, two logins, released at the same instant from two threads.

    The barrier is what makes this a race rather than two sequential requests that happen to be on
    different threads: both threads are parked until both have arrived, so the two requests enter
    the application within microseconds of each other and their reads of ``DesignerRoster`` land on
    either side of each other's write. ``TestClient`` runs them on the app's own event loop, which is
    where the interleaving actually happens.

    The barrier has a TIMEOUT for a reason worth stating: without one, a thread that never arrives —
    a request that hangs on a connection this box has run out of — parks the other for ever and the
    suite stops with no failure and no output. ``BrokenBarrierError`` fails the test in seconds
    instead, and says which half did not turn up.
    """
    ready = threading.Barrier(2, timeout=30)

    def attempt() -> Any:
        ready.wait()
        return client.post("/api/auth/login", json={"email": email, "password": API_PASSWORD})

    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(attempt), pool.submit(attempt)]
        return [future.result(timeout=120) for future in futures]


# --------------------------------------------------------------------------------------
# 1. The sign-in path: auto-empanelment, and the revocation it must not undo
# --------------------------------------------------------------------------------------


async def test_an_allow_listed_designer_with_no_empanelment_is_empanelled_at_sign_in(
    world, client
):
    """**THE REQUIREMENT.** The account the allow-list admits as a designer, that nobody empanelled.

    Before this, the platform gate admitted them, the empanelment gate found nothing and answered
    with a sentence about a suspension that had never happened. Admitting somebody as a designer now
    grants the empanelment, which is what the administrator who did it already believed.
    """
    email = world["address"]("newcomer")
    assert email not in _empanelments(client, world), (
        "the fixture must NOT have empanelled this account, or this test is asserting nothing about "
        "auto-empanelment and would keep passing if the feature were deleted"
    )

    response = _login(client, email)
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "DESIGNER"

    row = _empanelments(client, world)[email]
    assert row["isActive"] is True
    assert row["revokedAt"] is None, "an active row carrying a revocation date reads as a bug"
    # DERIVED, AND THE ROW SAYS SO IN WORDS. ``addedById`` cannot carry that fact alone: it is NULL
    # here, and NULL again on a hand-made row whose creating admin was later deleted (the relation
    # is ``onDelete: SetNull``). An audit that trusted the id would read the two as the same thing.
    assert row["addedById"] is None, "nobody administered this; naming an admin would be a lie"
    assert row["notes"] == DERIVED_EMPANELMENT_NOTE
    # THE ORDERING INSIDE ``login``. The row is created with a NULL stamp and ``mark_roster_seen``
    # writes it two lines later, so somebody who really did sign in carries a date.
    assert row["firstSeenAt"] is not None


async def test_signing_in_again_creates_nothing_and_moves_nothing(world, client):
    """IDEMPOTENT, and the stamp is still write-once.

    The check-then-create runs on every single sign-in of every designer in the product, so "it
    already exists" is the ordinary case rather than the exception. A second create would fail on
    the unique index; a second WRITE of any kind would move ``firstSeenAt`` off the first arrival and
    turn a joining date into a last-seen date that every screen would still label as joining.
    """
    email = world["address"]("newcomer")
    before = _empanelments(client, world)[email]
    assert before["firstSeenAt"] is not None, "the test above must have signed this designer in"

    assert _login(client, email).status_code == 200
    after = _empanelments(client, world)[email]

    assert after["id"] == before["id"], "a second row was created for one address"
    assert after["firstSeenAt"] == before["firstSeenAt"]
    assert after["notes"] == before["notes"]


async def test_a_revoked_empanelment_is_never_revived_by_signing_in(world, client):
    """**THE ONE RULE THAT MUST NOT BE GOT WRONG**, and the reason this module exists.

    This designer's platform access is fine — the allow-list still admits them as a DESIGNER — and
    an administrator ended their empanelment. Auto-empanelment reads the allow-list, so the obvious
    implementation (an upsert on the unique ``email``) takes its update arm here and hands the
    revoked person their standing back at the moment they next try to sign in, invisibly on both
    screens. Every revocation any administrator has made would come undone the same way.

    Allow-listing grants an empanelment to somebody who never had one; it does not overturn a
    withdrawal. Those are two decisions, and only an admin may make the second, on the roster screen
    where the act is recorded.
    """
    email = world["address"]("revoked")
    before = _empanelments(client, world)[email]
    assert before["isActive"] is False, "the fixture must have revoked this one, or this proves nothing"

    response = _login(client, email)
    assert response.status_code == 403, response.text
    assert response.json()["detail"] == DESIGNER_SUSPENDED_DETAIL

    after = _empanelments(client, world)[email]
    assert after["isActive"] is False, (
        "signing in revived a SUSPENDED empanelment; that is an administrator's revocation being "
        "undone silently, which is exactly what the create-only-where-no-row-exists rule prevents"
    )
    # NOT TOUCHED IN ANY OTHER WAY EITHER. The roster screen reads the flag, the date and the note
    # together as one account of what an administrator decided, and a row that stays suspended while
    # losing the admin's own words is the same defect in a smaller form.
    assert after["revokedAt"] == before["revokedAt"]
    assert after["notes"] == ADMIN_NOTE
    # A refused attempt is not an arrival: writing the stamp here would report the invitation as
    # accepted on the very day the designer could not get in.
    assert after["firstSeenAt"] is None


class _RosterThatNeverFindsARow:
    """A ``designerroster`` accessor whose EXISTENCE READS all miss. Everything else is real.

    **BOTH READS ARE BLINDED, AND THE SECOND ONE IS A REPAIR RATHER THAN BELT-AND-BRACES.** This
    stub originally blinded ``find_unique`` alone, because that is what ``ensure_empanelled`` called
    the day it was written. The Gmail work then changed that read to a ``find_first`` over a two-key
    ``IN`` — and ``__getattr__`` below forwards every name it does not define straight to the real
    accessor, so the stub went on being installed, the real row went on being found, no create was
    ever attempted, and the race tests below went on passing without once reaching the
    ``UniqueViolationError`` they exist to pin. A stub wired to a method the implementation no longer
    calls is a test that exercises nothing and still reports success, which is the one failure this
    module's whole discipline is built to refuse.

    :attr:`reads` is the guard against that happening again: every test using this stub asserts the
    blinded read was actually SERVED, so the next rename fails loudly instead of going green.
    """

    def __init__(self, real: Any) -> None:
        self._real = real
        #: How many existence reads this stub answered with None. See the class docstring.
        self.reads = 0

    async def find_first(self, *args: Any, **kwargs: Any) -> None:
        # THE MISS IS THE FALL-THROUGH. The ``-> None`` annotation is the contract and running off
        # the end of the body is how it is met; an explicit ``return None`` here is what ruff's
        # RET501 objects to, so a reader who thinks the return was lost in an edit should stop here
        # rather than add one back.
        self.reads += 1

    async def find_unique(self, *args: Any, **kwargs: Any) -> None:
        # The read this stub was originally written against, kept blinded so that a revert to it —
        # or a second caller still using it — cannot quietly walk past the stub. Same fall-through.
        self.reads += 1

    def __getattr__(self, name: str) -> Any:
        return getattr(self._real, name)


class _DbWhoseRosterNeverFindsARow:
    """The ``db`` singleton with exactly one method blinded, so the CREATE really is attempted.

    Standing in for the losing half of a race rather than simulating one. Two logins in the same
    second — a designer opening the app on their phone and their laptop — both read "no row" and
    both create one; the unique index on ``DesignerRoster.email`` is what actually decides it. Making
    the read miss is how that state is reached deterministically, and the create, the index and the
    ``UniqueViolationError`` it raises are all the real thing.
    """

    def __init__(self, real: Any) -> None:
        self._real = real
        self.designerroster = _RosterThatNeverFindsARow(real.designerroster)

    def __getattr__(self, name: str) -> Any:
        return getattr(self._real, name)


async def test_a_lost_race_is_answered_quietly_instead_of_breaking_the_sign_in(
    world, client, monkeypatch
):
    """THE LOSER OF THE RACE STILL SIGNS IN.

    The find-then-create in ``ensure_empanelled`` is check-then-act and nothing about it is atomic.
    Left unhandled, the losing request's ``UniqueViolationError`` reaches the catch-all in
    ``app/main.py`` and a designer who happened to open the app twice at once is told the server
    broke — over a race whose outcome, a row that exists and admits them, is precisely what they
    were asking for.
    """
    email = world["address"]("racer")
    before = _empanelments(client, world)[email]
    assert before["isActive"] is True, "the fixture must have empanelled this one"

    blind = _DbWhoseRosterNeverFindsARow(designers_service.db)
    monkeypatch.setattr(designers_service, "db", blind)
    response = _login(client, email)
    assert response.status_code == 200, response.text

    # THE STUB WAS ACTUALLY USED. Without this line the test passes just as well when the blinded
    # method is one ``ensure_empanelled`` has stopped calling: the real read finds the fixture's row,
    # the function returns early, no create is attempted, and every assertion below still holds while
    # the race itself is never reached. That is not a hypothetical — see ``_RosterThatNeverFindsARow``
    # for the rename that did exactly that to this test.
    assert blind.designerroster.reads > 0, (
        "the existence read was never served by the stub, so this sign-in never attempted the "
        "create and the losing half of the race was not exercised at all"
    )
    after = _empanelments(client, world)[email]
    assert after["id"] == before["id"], "the race produced a second row for one address"
    assert after["notes"] == ADMIN_NOTE, "the losing create overwrote the administrator's row"


async def test_two_simultaneous_sign_ins_for_one_new_designer_leave_exactly_one_row(world, client):
    """**THE RACE AS IT ACTUALLY ARRIVES: TWO REAL REQUESTS, AT ONE INSTANT, FOR ONE PERSON.**

    A designer an admin has just admitted opens the app on their phone and on their laptop, or the
    web client resends a sign-in whose first response was still in flight. Both requests reach
    ``ensure_empanelled`` before either has written anything, both read "no roster row", and both go
    on to create one. Nothing in that sequence is atomic — it is check-then-act on a table with no
    lock held — so what decides the outcome is the UNIQUE index on ``DesignerRoster.email``.

    THE TWO WAYS TO GET THIS WRONG BOTH LOOK LIKE SUCCESS UNTIL SOMEBODY COUNTS THE ROWS. Catch
    nothing and the loser's ``UniqueViolationError`` reaches the catch-all in ``app/main.py``: a
    designer is told the server broke, over a race whose outcome — a row that exists and admits
    them — is precisely what they were asking for. (``TestClient`` re-raises server exceptions, so
    that failure arrives here as the driver error itself rather than as a 500; either way this test
    is what stops it.) Drop the unique index instead, or "fix" the race by writing unconditionally,
    and both creates succeed: one person, two empanelments, and an administrator who suspends the
    one they can see leaves the other one admitting them.

    THIS TEST DOES NOT PRETEND TO FORCE THE COLLISION. Two threads off one barrier collide most of
    the time and not every time, and a test that only passed when they did would be a flake. What is
    asserted is what must hold either way: both people got in, and there is one row. The test below
    replays the losing half with the outcome decided rather than hoped for; the two are a pair and
    neither is redundant.
    """
    email = _fresh(world, "twiceatonce")
    _make_designer_account(client, world, email, "Simultaneous designer")
    # THE PREMISE, ASSERTED RATHER THAN ASSUMED. ``POST /api/users`` admits the address and does not
    # empanel it. If that ever changes, this becomes two sign-ins racing over a row that already
    # existed — which every implementation passes, including the ones this test exists to fail.
    assert email not in _empanelments(client, world, term=email), (
        "the account already had an empanelment before either sign-in, so there is no race here"
    )

    first, second = _two_sign_ins_at_once(client, email)
    assert [first.status_code, second.status_code] == [200, 200], (first.text, second.text)

    rows = _rows_spelling(client, world, email, term=email)
    assert len(rows) == 1, (
        f"one address came out of a simultaneous sign-in with {len(rows)} empanelments: "
        f"{[row['email'] for row in rows]}"
    )
    assert rows[0]["notes"] == DERIVED_EMPANELMENT_NOTE
    assert rows[0]["firstSeenAt"] is not None, "both sign-ins succeeded and neither stamped arrival"


async def test_the_losing_half_of_a_race_for_a_new_designer_is_let_in_on_the_winners_row(
    world, client, monkeypatch
):
    """THE SAME RACE, WITH THE OUTCOME DECIDED INSTEAD OF HOPED FOR.

    The existence read is blinded for BOTH sign-ins, so the first one really does create the row and
    the second really does reach ``db.designerroster.create`` for an address that now has one. That
    is the losing half of the race above, exactly: the ``UniqueViolationError`` comes from the real
    index, is caught in ``ensure_empanelled`` and answered False, and the person is admitted on the
    row the other request wrote — which is what they were asking for and is why the error is
    swallowed rather than logged and re-raised.

    The blinded read is asserted to have been SERVED, because a stub wired to a method the
    implementation stopped calling leaves this test green while it exercises nothing whatsoever.
    """
    email = _fresh(world, "lostrace")
    _make_designer_account(client, world, email, "Racing designer")

    blind = _DbWhoseRosterNeverFindsARow(designers_service.db)
    monkeypatch.setattr(designers_service, "db", blind)

    winner = _login(client, email, password=API_PASSWORD)
    assert winner.status_code == 200, winner.text
    created = _rows_spelling(client, world, email, term=email)
    assert len(created) == 1, f"the first sign-in wrote {len(created)} rows"

    loser = _login(client, email, password=API_PASSWORD)
    assert loser.status_code == 200, (
        "the designer who lost the race was refused, or told the server broke, over a row that "
        f"exists and admits them: {loser.text}"
    )

    after = _rows_spelling(client, world, email, term=email)
    assert [row["id"] for row in after] == [row["id"] for row in created], (
        "the losing create was not refused by the unique index, so one designer now holds two "
        "empanelments and suspending the visible one leaves the other still admitting them"
    )
    assert blind.designerroster.reads >= 2, (
        f"the stub served {blind.designerroster.reads} existence reads for two sign-ins, so at "
        "least one of them took a path that never attempted the create this test is about"
    )


# --------------------------------------------------------------------------------------
# 2. The approval path: the admin sees the designer before the designer arrives
# --------------------------------------------------------------------------------------


async def test_admitting_an_address_as_a_designer_empanels_it_immediately(world, client):
    """AN ADMIN MUST NOT HAVE TO WAIT FOR SOMEBODY'S FIRST SIGN-IN TO SEE THEIR OWN DECISION.

    The sign-in path would empanel this person eventually and nobody would be locked out either way.
    What that leaves is an admin who has just approved a designer, opens ``/admin/designers``, does
    not find them, and reasonably concludes the approval did not take — whose next move is to add the
    row by hand, for a 409 if they type the address the same way and a second unmatchable row if
    they do not.
    """
    email = _fresh(world, "approved")
    admitted = _admit(client, world, email, "DESIGNER")
    assert admitted.status_code == 201, admitted.text

    row = _empanelments(client, world)[email]
    assert row["isActive"] is True
    # AN ADMINISTRATOR REALLY DID TAKE THIS ACTION, unlike the sign-in path, so the row names them.
    assert row["addedById"] == world["people"]["admin"].id
    # AND THE STAMP IS LEFT FOR THEM TO ARRIVE. This row was created days before the person opens
    # the app; a stamp here would consume ``mark_roster_seen``'s write-once and report the
    # invitation as accepted on the day it was granted.
    assert row["firstSeenAt"] is None


async def test_admitting_somebody_who_is_not_a_designer_empanels_nobody(world, client):
    """THE CONTROL. ``DesignerRoster`` is the empanelment list of DESIGNERS and nobody else.

    Without this, a rule that empanelled every ACTIVE row would pass every other test in this
    section while quietly putting the institution's researchers, professors and volunteers on the
    designer roster — where the workshop pickers read from.
    """
    email = _fresh(world, "researcher")
    admitted = _admit(client, world, email, "RESEARCHER")
    assert admitted.status_code == 201, admitted.text
    assert email not in _empanelments(client, world)


async def test_approving_a_waiting_request_at_designer_empanels_them(world, client, monkeypatch):
    """THE QUEUE'S OWN DOOR, which is where most designers are actually approved.

    The request is written by the person's own refused Google sign-in — the real way a PENDING row
    comes to exist — so this covers the decision endpoint on a row nobody typed by hand.
    """
    email = _fresh(world, "queued")
    refused = _google(client, monkeypatch, email)
    assert refused.status_code == 403, refused.text
    assert _access_rows(client, world, term=email)[email]["status"] == "PENDING"

    row_id = _access_rows(client, world, term=email)[email]["id"]
    decided = client.post(
        f"/api/access/roster/{row_id}/decision",
        json={"decision": "APPROVE", "role": "DESIGNER"},
        headers=_headers(world),
    )
    assert decided.status_code == 200, decided.text

    empanelment = _empanelments(client, world, term=email)[email]
    assert empanelment["isActive"] is True
    assert empanelment["addedById"] == world["people"]["admin"].id
    assert empanelment["firstSeenAt"] is None


async def test_correcting_a_rows_role_up_to_designer_empanels_them(world, client):
    """AN EDIT IS THE OTHER WAY A ROW COMES TO ADMIT A DESIGNER.

    An admin who approved a colleague at RESEARCHER and then corrects the row has empanelled them
    exactly as surely as one who approved them at DESIGNER in the first place. If only the approval
    endpoint empanelled, the corrected row would be the one that silently does not — and the person
    it happened to would be the person nobody could see on the roster screen.
    """
    email = _fresh(world, "corrected")
    assert _admit(client, world, email, "RESEARCHER").status_code == 201
    assert email not in _empanelments(client, world), "the control: a researcher is not empanelled"

    row_id = _access_rows(client, world, term=email)[email]["id"]
    corrected = client.patch(
        f"/api/access/roster/{row_id}", json={"role": "DESIGNER"}, headers=_headers(world)
    )
    assert corrected.status_code == 200, corrected.text
    assert _empanelments(client, world, term=email)[email]["isActive"] is True


async def test_editing_a_waiting_rows_role_to_designer_approves_nobody(
    world, client, monkeypatch
):
    """**THE STATUS CHECK IS AN ADMISSION DECISION, NOT A TIDINESS ONE.**

    ``AccessRosterUpdate`` cannot change ``status`` — deliberately, so that approving is one
    explicit act at one endpoint — which means an admin may edit ``admitRole`` on a row that is still
    PENDING. Empanelling on the role alone would give that undecided request an ACTIVE designer row,
    and ``auth.assert_access_admits`` accepts an ACTIVE designer row as an admission for anybody
    whose allow-list row is missing or PENDING. Changing a dropdown would then have approved
    somebody, through a second table, without the admin touching the decision endpoint and without
    the approval appearing anywhere as a decision.
    """
    email = _fresh(world, "waiting")
    assert _google(client, monkeypatch, email).status_code == 403
    row = _access_rows(client, world, term=email)[email]
    assert row["status"] == "PENDING"

    edited = client.patch(
        f"/api/access/roster/{row['id']}", json={"role": "DESIGNER"}, headers=_headers(world)
    )
    assert edited.status_code == 200, edited.text
    assert edited.json()["status"] == "PENDING", "an edit must not have decided anything"
    assert email not in _empanelments(client, world, term=email), (
        "a PENDING request was empanelled by an edit, which admits it through the designer-roster "
        "clause in the sign-in gate — an approval nobody made"
    )

    # AND THE PROOF THAT IT MATTERS: the person is still refused, in the queue's own words.
    still_waiting = _google(client, monkeypatch, email)
    assert still_waiting.status_code == 403, still_waiting.text
    assert still_waiting.json()["detail"] == PENDING_DETAIL


async def test_editing_a_barred_rows_role_to_designer_empanels_nobody(world, client):
    """THE SAME CHECK ON THE OTHER SIDE OF A DECISION, where the damage is to the record.

    A SUSPENDED row is not "waiting", so the empanelment clause in the sign-in gate does not let this
    person back in — but an admin tidying up the record of somebody they barred would still have
    created an active empanelment for them, and ``/admin/designers`` would say the institution
    recognises a designer whose access an administrator ended. Suspending an allow-list row does not
    suspend an empanelment (they are two decisions, which is why they have two sentences), so nothing
    downstream would ever correct it.
    """
    email = _fresh(world, "barred")
    assert _admit(client, world, email, "RESEARCHER").status_code == 201
    row_id = _access_rows(client, world, term=email)[email]["id"]
    suspended = client.delete(f"/api/access/roster/{row_id}", headers=_headers(world))
    assert suspended.status_code == 200, suspended.text
    assert suspended.json()["status"] == "SUSPENDED"

    edited = client.patch(
        f"/api/access/roster/{row_id}", json={"role": "DESIGNER"}, headers=_headers(world)
    )
    assert edited.status_code == 200, edited.text
    assert email not in _empanelments(client, world, term=email), (
        "an address an administrator barred was put on the designer roster as ACTIVE by an edit"
    )


# --------------------------------------------------------------------------------------
# 3. The two writes in order: the approval grants, the arrival stamps
# --------------------------------------------------------------------------------------


async def test_an_approvals_empanelment_waits_for_the_person_to_actually_arrive(world, client):
    """``firstSeenAt`` SURVIVES AUTO-EMPANELMENT, and that is what step 4 of the plan asks.

    The whole point of the column is that an admin who empanelled five designers in March can tell,
    in April, which of them ever opened the app — an invitation that never arrived looks exactly like
    one that was ignored. ``ensure_empanelled`` therefore leaves it NULL and ``mark_roster_seen``,
    which writes only ``WHERE firstSeenAt IS NULL``, stamps it on the first successful sign-in. Get
    the order wrong — stamp at creation, or run the empanelment after the stamp — and every row an
    admin ever approved reports itself as accepted the day it was granted.
    """
    email = _fresh(world, "arrives")
    assert _admit(client, world, email, "DESIGNER").status_code == 201
    granted = _empanelments(client, world, term=email)[email]
    assert granted["firstSeenAt"] is None, "an empanelment nobody has used yet cannot be 'seen'"

    made = client.post(
        "/api/users",
        json={
            "email": email,
            "name": f"Arriving designer {world['stamp']}",
            "password": API_PASSWORD,
            "role": "DESIGNER",
        },
        headers=_headers(world),
    )
    assert made.status_code == 201, made.text
    assert _empanelments(client, world, term=email)[email]["firstSeenAt"] is None, (
        "creating the account is not the designer arriving; only a sign-in is"
    )

    arrived = _login(client, email, password=API_PASSWORD)
    assert arrived.status_code == 200, arrived.text
    seen = _empanelments(client, world, term=email)[email]
    assert seen["firstSeenAt"] is not None
    assert seen["id"] == granted["id"], "the sign-in created a second row instead of using theirs"
    assert seen["addedById"] == world["people"]["admin"].id, (
        "the sign-in overwrote the administrator's empanelment with a derived one"
    )


# --------------------------------------------------------------------------------------
# 4. One mailbox, two spellings — and the line that stops it being everybody's problem
# --------------------------------------------------------------------------------------


def _gmail_as_typed(world: dict[str, Any], slug: str) -> str:
    """The address an administrator types: dotted, the way it is written on a business card."""
    return f"empanel.{slug}.{world['stamp']}@gmail.com"


def _gmail_mailbox(world: dict[str, Any], slug: str) -> str:
    """The same mailbox with the dots gone — what Google's token actually carries.

    Written out here rather than obtained from ``canonical_email``. Asking the function under test
    what it thinks this address canonicalises to, and then checking the stored row against that
    answer, is a test that agrees with the bug: strip the wrong character and both sides move
    together and the assertion still passes.
    """
    return f"empanel{slug}{world['stamp']}@gmail.com"


async def test_a_designer_admitted_with_gmail_dots_signs_in_without_them(world, client, monkeypatch):
    """**THE REPORTED OUTAGE, END TO END, THROUGH THE TWO DOORS IT ACTUALLY CAME THROUGH.**

    An administrator admits ``sandy.craft3@gmail.com`` as a designer, from an email signature or a
    form somebody filled in by hand. Google then presents that person as ``sandycraft3@gmail.com``,
    because to Google the dots are not part of the address at all. Under ``normalise_email`` alone —
    ``.strip().lower()`` and nothing else — those are two different keys and the second one matches
    nothing: the allow-list answered "never seen" and queued the designer as a stranger, or, once an
    admin had promoted them by hand, the empanelment gate refused them with a sentence about a
    suspension that had never happened, while the roster screen showed their row sitting there
    active. That is what ``sandycraft3@gmail.com`` read in production.

    Both writes now store the MAILBOX, so the admin's typing and the token meet on one row. The
    assertions are in that order deliberately: the row is checked before anybody signs in, because
    "the sign-in works" would also be satisfied by an implementation that silently created a second
    empanelment for the second spelling — the duplicate is the failure, not the remedy.

    The admin's own row is asserted to have survived the sign-in as well. A derived empanelment
    landing on top of it would erase the fact that a person made this decision, which is the one
    thing ``/admin/designers`` exists to record.
    """
    typed = _gmail_as_typed(world, "gdots")
    mailbox = _gmail_mailbox(world, "gdots")

    admitted = _admit(client, world, typed, "DESIGNER")
    assert admitted.status_code == 201, admitted.text
    assert admitted.json()["email"] == mailbox, (
        "the allow-list stored the spelling that was typed rather than the mailbox it reaches, so "
        "the Google token for this person will match nothing"
    )

    granted = _rows_spelling(client, world, typed, mailbox)
    assert [row["email"] for row in granted] == [mailbox], (
        f"expected one empanelment stored as the mailbox; got {[r['email'] for r in granted]}"
    )
    assert granted[0]["firstSeenAt"] is None, "nobody has signed in yet"

    arrived = _google(client, monkeypatch, mailbox)
    assert arrived.status_code == 200, arrived.text
    assert arrived.json()["user"]["role"] == "DESIGNER", (
        "the allow-list row admitting this mailbox as a DESIGNER was not found from the undotted "
        "spelling, so the account was provisioned at the default tier instead"
    )

    after = _rows_spelling(client, world, typed, mailbox)
    assert [row["email"] for row in after] == [mailbox], (
        "signing in manufactured a second roster row for one mailbox — the duplicate an admin then "
        "cannot reconcile, and which suspending one of would not stop the other admitting them"
    )
    assert after[0]["id"] == granted[0]["id"]
    assert after[0]["addedById"] == world["people"]["admin"].id, (
        "the derived empanelment overwrote the one an administrator actually granted"
    )
    assert after[0]["firstSeenAt"] is not None, (
        "the gate admitted this designer on the canonical key and then stamped nothing, so the "
        "admin who empanelled them reads a blank 'first seen' for somebody using the app right now"
    )


async def test_dots_outside_gmail_are_two_different_people(world, client, monkeypatch):
    """**THE LIMIT OF THE RULE ABOVE, AND WHY IT IS A DOMAIN LIST RATHER THAN A HABIT.**

    Google having decided that dots in a Gmail local part are noise is a fact about Gmail. Nowhere
    else is it even close to true: on a university or a studio's own mail server ``a.sharma@`` and
    ``asharma@`` are ordinary distinct local parts, and the two are as likely as not to be two
    colleagues who share a surname. Folding them would hand one person's empanelment — and, at
    ``/admin/designers``, the institution's statement about who they are — to somebody else, and the
    person it happened to would have no way to report it: they signed in and it worked.

    So the dotted address here is admitted and empanelled, and the same string with the dots taken
    out is a stranger: refused at the platform gate in the queue's own words, with a PENDING row of
    its very own, and with no empanelment created for it by either gate. The last assertion is what
    proves the refusal came from the spelling and not from a broken row — the address that WAS
    admitted signs in perfectly well.
    """
    typed = f"empanel.dots.{world['stamp']}@example.org"
    collapsed = f"empaneldots{world['stamp']}@example.org"

    admitted = _admit(client, world, typed, "DESIGNER")
    assert admitted.status_code == 201, admitted.text
    assert admitted.json()["email"] == typed, "a dot was removed from an address outside Gmail"
    assert [row["email"] for row in _rows_spelling(client, world, typed, collapsed)] == [typed]

    stranger = _google(client, monkeypatch, collapsed)
    assert stranger.status_code == 403, stranger.text
    assert stranger.json()["detail"] == PENDING_DETAIL, (
        "an address that differs from an admitted one by a dot was let in outside Gmail, where a "
        "dot is an ordinary character in somebody else's local part"
    )
    assert _access_rows(client, world, term=collapsed)[collapsed]["status"] == "PENDING", (
        "the undotted spelling was matched onto the admitted row instead of queueing as itself"
    )
    assert [row["email"] for row in _rows_spelling(client, world, typed, collapsed)] == [typed], (
        "the refused stranger was nonetheless empanelled under their own spelling"
    )

    theirs = _google(client, monkeypatch, typed)
    assert theirs.status_code == 200, theirs.text
    assert theirs.json()["user"]["role"] == "DESIGNER"


# --------------------------------------------------------------------------------------
# 5. The half of the mailbox rule that WAS not closed: the roster row an admin types by hand
# --------------------------------------------------------------------------------------
#
# THE TWO TESTS BELOW WERE ``xfail(strict=True)`` UNTIL 2026-08-30, AND THEY DESCRIBE A DEFECT THAT
# WAS IN THIS TREE. They were written the way the product is supposed to behave rather than the way
# it did, specifically so that the day somebody closed the hole the strict marker would turn this
# module RED and demand the marker be deleted rather than let a comment saying the same thing rot
# unread. That day is this commit — read on for what closed it — and the two functions below are
# left in place, with their ``xfail`` removed, as the regression guards they were always going to
# become. Deleting them instead of converting them would throw away the one thing standing between
# this fix and the fix silently regressing.
#
# **WHAT WAS BROKEN.** ``email_match_keys`` (``app/services/designers.py``) builds its ``IN`` list
# out of the address that ARRIVES::
#
#     literal = normalise_email(email)          # sandycraft3@gmail.com
#     canonical = canonical_email(literal)      # sandycraft3@gmail.com  -- the same string
#     if canonical == literal: return [literal] # ONE key
#
# For an address that is already the mailbox that is a single key. That is correct — the trap was
# never in this function. The docstring above it explains that the literal form is kept in the list
# so that "every roster row written before this change is stored under whatever an admin typed,
# dots and all" still resolves — and it does, on the READ side, for exactly the rows this fix could
# not retroactively repair. What was missing was the WRITE side: ``POST /api/designers/roster`` and
# its ``PATCH`` used to store ``normalise_email(payload.email)`` — lower-cased and trimmed, dots
# left in — so a row typed with the dots in was invisible to a single-key lookup built from the
# undotted mailbox Google always presents. ``access.add_to_access_roster`` already stored the
# canonical form for exactly this reason; ``designers.add_to_roster`` and
# ``designers.update_roster_entry`` did not, which is the asymmetry these two tests existed to name.
#
# **THE FIX.** ``app/api/routes/designers.py`` — both ``add_to_roster`` (``POST /roster``) and
# ``update_roster_entry`` (``PATCH /roster/{id}``) — now store :func:`canonical_email` rather than
# :func:`normalise_email`, matching ``access.add_to_access_roster``, and their duplicate checks now
# read both spellings (:func:`email_match_keys`) rather than the stored form alone, so a row written
# before this landed is still found rather than silently doubled. Rows already on disk under the old,
# dotted spelling are not rewritten by this change — that is what
# ``scripts/backfill_email_canonicalisation.py`` is for, and it reports collisions rather than
# merging them, on the same reasoning ``ensure_empanelled`` never revives a suspension: a fix that
# silently rewrote data on the way through would be one more thing to audit rather than fewer.
#
# **THE SIGN-IN IS THE ONLY ACTOR IN BOTH TESTS.** The allow-list row is seeded by the fixture
# rather than admitted through the API here — see ``GMAIL_ADMITTED`` for why that is forced rather
# than preferred — so nothing between the roster row being typed and the designer arriving can be
# the thing that keeps the two tables in step. Whatever the assertions confirm, ``auth.login`` did it.


def _dotted_roster_row(client: Any, world: dict[str, Any], email: str, *, active: bool) -> Any:
    """Empanel an address through ``/admin/designers``, typing it with the dots in.

    A real door and not a hand-written row, because the whole point of both tests below is that this
    endpoint's WRITE and the sign-in path's READ now have to agree about what an address is.
    Assembling the row directly in the fixture would reproduce a state reachable only from data
    written before canonicalisation shipped; going through the API is what proves the door an admin
    actually uses stores the mailbox rather than the spelling handed to it.
    """
    return client.post(
        "/api/designers/roster",
        json={
            "email": email,
            "fullName": f"Hand-typed roster row {world['stamp']}",
            "notes": ADMIN_NOTE,
            "isActive": active,
        },
        headers=_headers(world),
    )


async def test_a_dotted_gmail_row_is_the_row_the_undotted_sign_in_lands_on(
    world, client, monkeypatch
):
    """**ONE MAILBOX, ONE EMPANELMENT — INCLUDING WHEN THE ROW WAS TYPED WITH THE DOTS IN.**

    An administrator empanels ``sandy.craft3@gmail.com`` on ``/admin/designers``, off a business
    card or an email signature. Google then presents that person as ``sandycraft3@gmail.com``,
    because to Google the dots were never part of the address at all. Section 4 above proves the two
    spellings meet when the ALLOW-LIST is the thing that wrote the roster row — that writer has
    always canonicalised. This is the same person and the same mailbox arriving at a row the OTHER
    writer made, and — since the fix above — they now meet there too.

    **THE ROW IS STORED UNDER THE MAILBOX FROM THE MOMENT IT IS TYPED**, not merely matched against
    it later: ``empanelled.json()["email"]`` is asserted against ``mailbox``, never ``typed``, which
    is the write-side half of the fix and the reason the read-side match below has anything to find.
    Before this landed, signing in here derived a SECOND roster row beside the administrator's —
    this test's own history is the record of that, in ``git log -p`` on this file — leaving the
    administrator's row with a permanently blank ``firstSeenAt`` while the derived twin absorbed
    every arrival. What is pinned now is the opposite: exactly one row, the administrator's, carries
    both the note they wrote and the stamp of the designer's first real sign-in.
    """
    typed = _gmail_as_typed(world, "handtyped")
    mailbox = _gmail_mailbox(world, "handtyped")

    empanelled = _dotted_roster_row(client, world, typed, active=True)
    assert empanelled.status_code == 201, empanelled.text
    assert empanelled.json()["email"] == mailbox, (
        "POST /api/designers/roster stored what was typed rather than the mailbox it resolves to, "
        "which is the write-side half of the canonicalisation fix regressing"
    )
    # THE PREMISE, ASSERTED RATHER THAN ASSUMED: one row exists and it is stored under the mailbox.
    # Without this the assertion after the sign-in could be satisfied by a fixture that had already
    # written the row some other way, and the test would be reporting the setup rather than the login.
    before = _rows_spelling(client, world, typed, mailbox)
    assert [row["email"] for row in before] == [mailbox], (
        f"the state under test was not reached; rows present before the sign-in: {before}"
    )

    arrived = _google(client, monkeypatch, mailbox)
    assert arrived.status_code == 200, arrived.text

    after = _rows_spelling(client, world, typed, mailbox)
    assert [row["email"] for row in after] == [mailbox], (
        "signing in on the mailbox created a second empanelment beside the one an administrator "
        "typed; one designer now holds two roster rows, and suspending the row the admin can see "
        f"would leave the other one admitting them: {[r['email'] for r in after]}"
    )
    assert after[0]["addedById"] == world["people"]["admin"].id, (
        "a derived row displaced the administrator's, erasing the record that a person made this "
        "decision — the one thing /admin/designers exists to hold"
    )
    assert after[0]["notes"] == ADMIN_NOTE
    assert after[0]["firstSeenAt"] is not None, (
        "the designer signed in and the administrator's row still reads as never accepted, which "
        "would mean the arrival was stamped on a different row for the same mailbox"
    )


async def test_a_revocation_typed_with_the_dots_is_not_walked_around_by_the_mailbox(
    world, client, monkeypatch
):
    """**THE REGRESSION GUARD OF SECTION 1, ONE SPELLING OVER.**

    ``test_a_revoked_empanelment_is_never_revived_by_signing_in`` proves that signing in never
    revives a suspended row. It proves it on an ``@example.org`` address, where a mailbox has exactly
    one spelling and the row the gate reads is necessarily the row the admin suspended. Before the
    fix above, giving one mailbox two spellings was how that rule stopped being reachable rather than
    being broken outright: the administrator's revocation, typed with the dots in, was invisible to
    a sign-in path that only ever derives the undotted key, so ``ensure_empanelled`` could not find
    it, honoured its create-only rule against a table it could not see the suspension in, and wrote a
    fresh ACTIVE row beside it — which ``roster_allows`` then read and admitted.

    **WHAT IS PINNED NOW IS THAT THE TWO ROWS NEVER DIVERGE, BECAUSE THERE IS ONLY EVER ONE.** The
    administrator revokes ``sandy.craft3@gmail.com`` on ``/admin/designers``; the write-side fix
    stores it as ``sandycraft3@gmail.com``, the same key the sign-in derives, so the designer's
    arrival finds the very row the administrator suspended and is refused by it — the ordinary case
    section 1 already covers, reached this time through a spelling instead of through a single-key
    address. The refusal asserted is the empanelment's own, with its own header and its single
    sentence, exactly as section 9 of ``test_platform_access_gate`` asserts them.
    """
    typed = _gmail_as_typed(world, "revokeddots")
    mailbox = _gmail_mailbox(world, "revokeddots")

    revoked = _dotted_roster_row(client, world, typed, active=False)
    assert revoked.status_code == 201, revoked.text
    assert revoked.json()["email"] == mailbox, (
        "POST /api/designers/roster stored what was typed rather than the mailbox it resolves to, "
        "which is the write-side half of the canonicalisation fix regressing"
    )
    assert revoked.json()["isActive"] is False, "the administrator's revocation must be recorded"
    before = _rows_spelling(client, world, typed, mailbox)
    assert [(row["email"], row["isActive"]) for row in before] == [(mailbox, False)], (
        f"the state under test was not reached; rows present before the sign-in: {before}"
    )

    arrived = _google(client, monkeypatch, mailbox)
    assert arrived.status_code == 403, (
        "a designer whose empanelment an administrator revoked signed in, because the revoked row "
        f"was stored under a spelling the gate never looks up: {arrived.text}"
    )
    assert arrived.json()["detail"] == DESIGNER_SUSPENDED_DETAIL
    assert arrived.headers.get(auth_routes.ACCESS_STATUS_HEADER) == "DESIGNER_SUSPENDED", (
        arrived.headers
    )
    # THE REFUSAL STILL SAYS ONLY THAT ONE SENTENCE, for section 9's reason: a gate that started
    # naming the row it declined to revive would hand anybody who can guess an address the
    # administrator's own record of it.
    assert set(arrived.json()) == {"detail"}

    after = _rows_spelling(client, world, typed, mailbox)
    assert [(row["email"], row["isActive"]) for row in after] == [(mailbox, False)], (
        "the sign-in wrote a second, ACTIVE empanelment for a mailbox an administrator had "
        f"revoked; suspending the visible row again would change nothing: {after}"
    )
    assert after[0]["notes"] == ADMIN_NOTE
    assert after[0]["firstSeenAt"] is None, "a refused attempt is not an arrival"
