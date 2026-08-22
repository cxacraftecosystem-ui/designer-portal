"""THE PLATFORM ALLOW-LIST: the gate, the two distinct refusals, and the migration that must not
lock anybody out.

Six things are pinned here, and four of them are ways somebody loses access to the product.

**NO EXISTING ACCOUNT IS LOCKED OUT BY THE MIGRATION THAT INTRODUCES THE GATE.** An account of every
one of the seven roles is created with NO allow-list row — the state of the database on the morning
of the deploy — and then :func:`_grandfather` runs **the INSERT statements out of the migration file
itself**, read off disk rather than retyped. Every one of those accounts then signs in. This is the
non-negotiable: without those two statements the deploy refuses every user of the product at once,
including the admins who would have to let them back in, and the failure arrives as a support queue
rather than as a red test.

**A PERSON WAITING ON AN ADMIN IS TOLD SO, AND IT IS NOT WHAT A MISTYPED PASSWORD SAYS.** 403 with a
sentence that names the wait, against 401 "Invalid email or password". The two are asserted in the
same test so that collapsing them cannot pass. This was a deliberate ruling by the product owner
against the usual advice — the enumeration cost was weighed and accepted, because a person waiting
on an administrator who is told their password is wrong resets a password that was never wrong and
then telephones somebody who cannot help.

**GOOGLE NO LONGER HANDS OUT ACCOUNTS.** Before this feature, a verified Google token for any
address on earth created a `User` row and returned a bearer token. The test here asserts the
refusal, asserts the pending row that replaced the account, and asserts NO user row appeared —
because a self-provisioned account is not a request an admin can approve, it is a decision already
taken.

**THE MASTER ADMIN IS NEVER GATED.** With no row at all and with a SUSPENDED row, on both branches.
This is the break-glass that replaces the argument the old designer-only gate rested on; if it ever
goes, the first admin to fat-finger their own row takes the institution offline with them.

**THE QUEUE STAYS WORKABLE.** One address is one row however many times it is tried; a rejected
person does not re-queue themselves by trying again; and past the cap the request is refused with a
message that says it was not recorded, rather than being dropped into a queue nobody reads.

**THE GATE IS ON EVERY DOOR THAT TURNS A PASSWORD INTO A TOKEN, NOT JUST ON THE SIGN-IN PAGE.**
``POST /api/datasets/token`` mints a thirty-day read credential over the whole repository from an
admin's email and password, and it used to check the credential and the admin flag and nothing else.
Suspension writes the roster status and never ``User.role``, so a suspended admin was refused at
``/auth/login`` and could still mint there, renewably, for ever — a revoked person holding live data
access. Section 8 pins both doors giving the same answer about the same account, and pins the
break-glass opening at both.

Postgres is required — every behaviour here is a row deciding an HTTP status — so the module skips
itself when ``DATABASE_URL`` does not point at a local database, exactly as ``test_designer_roster``
does.

    docker compose up -d postgres minio
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma

Nothing here reaches Google. ``verify_google_token`` is the only thing stubbed, because it is the
only part of that path that leaves the process; the allow-list read, the refusal, the pending write
and the provisioning all run exactly as they do in production.
"""

import os
import re
import uuid
from pathlib import Path
from typing import Any

import pytest

from app.api.routes import auth as auth_routes
from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services import access_roster

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]

PASSWORD = "access-gate-test-password"

#: The migration whose grandfathering is under test. Named once; if it is ever renamed, this module
#: fails loudly at collection rather than quietly testing nothing.
MIGRATION = (
    Path(__file__).resolve().parents[1]
    / "prisma" / "migrations" / "20260816090000_platform_access_roster" / "migration.sql"
)

# ASSERTED VERBATIM RATHER THAN IMPORTED, exactly as ``test_designer_roster`` asserts the suspension
# sentence. These strings ARE the feature: the whole ruling is that a person waiting on an
# administrator reads something different from a person who mistyped a password. Importing the
# constants would make this file agree with whatever they are changed to — including changing both
# of them to the same words, which is the one outcome it exists to prevent.
PENDING_DETAIL = (
    "Your access request is awaiting administrator approval. This is not a password problem — an "
    "administrator has to approve this address before you can sign in."
)
REJECTED_DETAIL = (
    "Your access request was reviewed and not approved. Contact the administrator if you believe "
    "this is a mistake."
)
BARRED_DETAIL = "Your access to this application has been suspended. Contact the administrator."
NOT_RECORDED_DETAIL = (
    "Access requests are temporarily closed because the approval queue is full, so this request "
    "could not be recorded. Contact the administrator directly."
)
WRONG_CREDENTIAL_DETAIL = "Invalid email or password"
DESIGNER_SUSPENDED_DETAIL = "Your designer access has been suspended. Contact the administrator."

#: slug -> role. THE FIRST SEVEN ARE THE WHOLE LADDER and they are the grandfathering proof: every
#: one of them is created without an allow-list row and must be able to sign in after the migration.
ACCOUNTS: tuple[tuple[str, str], ...] = (
    ("master", "MASTER_ADMIN"),
    ("admin", "ADMIN"),
    ("professor", "PROFESSOR"),
    ("designer", "DESIGNER"),
    ("researcher", "RESEARCHER"),
    ("contributor", "FIELD_CONTRIBUTOR"),
    ("volunteer", "CROWDSOURCE_VOLUNTEER"),
    # Accounts whose allow-list row the fixture then moves to a refusing state, one per refusal.
    ("pending", "RESEARCHER"),
    ("rejected", "RESEARCHER"),
    ("barred", "RESEARCHER"),
    # A master admin whose row the fixture DELETES. The break-glass, tested as the state it will
    # actually be found in: a row nobody wrote, or one an admin destroyed by accident.
    ("masterNoRow", "MASTER_ADMIN"),
    # An empanelled designer whose designer roster row the fixture suspends. Their platform access
    # is fine; the refusal must still be the empanelment's own sentence, not the platform one.
    ("designerSuspended", "DESIGNER"),
    # ADMINS the fixture then bars, for the dataset-token door (section 8). ADMIN and not
    # RESEARCHER, because a non-admin never reaches the gate on that endpoint — the rank check
    # refuses them first — so a barred researcher there would pass while proving nothing. The hole
    # this closes was specifically an admin: suspension writes the roster status and never the
    # ``User.role``, so ``is_admin`` still says yes about somebody who cannot sign in.
    ("adminBarred", "ADMIN"),
    ("adminRejected", "ADMIN"),
    # A MASTER admin whose row says SUSPENDED. The break-glass at the second door, which has to open
    # for exactly the same account the sign-in door opens for.
    ("masterBarred", "MASTER_ADMIN"),
)

#: The accounts whose access row is moved out of ACTIVE after the grandfathering, and to what.
REFUSED: tuple[tuple[str, str], ...] = (
    ("pending", "PENDING"),
    ("rejected", "REJECTED"),
    ("barred", "SUSPENDED"),
    ("adminBarred", "SUSPENDED"),
    ("adminRejected", "REJECTED"),
    ("masterBarred", "SUSPENDED"),
)


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


def _grandfather_statements() -> list[str]:
    """The INSERT statements out of the migration file, and nothing else.

    READ FROM THE FILE, NOT RETYPED. A copy of the grandfathering SQL living in this module would
    prove that *a* query admits everybody, which is not the claim: the claim is that the query
    Prisma will run on the production database admits everybody. The DDL is skipped because it has
    already been applied — re-running it would fail on the existing type — and the INSERTs carry
    ``ON CONFLICT DO NOTHING``, which is what makes replaying them here safe and idempotent.
    """
    sql = MIGRATION.read_text(encoding="utf-8")
    # Comment lines first: a `--` comment in this file contains semicolons and apostrophes, and a
    # naive split on ';' would cut a statement in half in a way that still parses as valid SQL.
    body = "\n".join(line for line in sql.splitlines() if not line.strip().startswith("--"))
    statements = [s.strip() for s in body.split(";") if s.strip()]
    inserts = [s for s in statements if re.match(r"(?is)^insert\s+into", s)]
    assert len(inserts) == 2, (
        f"expected the two grandfathering INSERTs in {MIGRATION.name}, found {len(inserts)}; if the "
        "migration was restructured, this test is no longer proving that it admits anybody"
    )
    return inserts


async def _grandfather() -> None:
    for statement in _grandfather_statements():
        await db.execute_raw(statement)


@pytest.fixture(scope="module")
async def world():
    """Seven roles with no allow-list row, then the migration's own SQL, then the refusal states.

    The order is the point and mirrors the deploy: accounts first (they are what exists on the
    morning of the migration), the migration's INSERTs second, and only then the states an admin's
    later decisions would have left behind.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        # Lower-cased, for ``normalise_email``'s reason: ``AccessRoster.email`` is unique on the
        # lower-cased address and ``User.email`` is not lower-cased at all, so a mixed-case fixture
        # address is a row that can never be matched — a person locked out by a capital letter.
        return f"access-{slug}-{stamp}@example.org".lower()

    people: dict[str, Any] = {}
    await db.connect()
    try:
        for slug, role in ACCOUNTS:
            people[slug] = await db.user.create(data={
                "email": address(slug),
                "name": f"Access {slug} {stamp}",
                "role": role,
                "passwordHash": hash_password(PASSWORD),
            })
        # Both designers need an ACTIVE empanelment or the SECOND gate refuses them for a reason
        # that has nothing to do with this module.
        for slug in ("designer", "designerSuspended"):
            await db.designerroster.create(data={"email": address(slug), "isActive": True})
        # An empanelled address with NO account and NO allow-list row: the flow where an admin
        # empanels somebody who has never opened the app. Created AFTER the grandfathering below
        # would admit it, so the empanelment clause in the gate is what has to do the work.

        await _grandfather()

        for slug, state in REFUSED:
            await db.accessroster.update_many(
                where={"email": address(slug)}, data={"status": state}
            )
        await db.accessroster.delete_many(where={"email": address("masterNoRow")})
        await db.designerroster.update_many(
            where={"email": address("designerSuspended")}, data={"isActive": False}
        )
        # Empanelled, no account, no allow-list row — created last so the grandfathering cannot
        # have picked it up.
        await db.designerroster.create(data={"email": address("empanelled"), "isActive": True})
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {"client": client, "people": people, "address": address, "stamp": stamp}


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any], slug: str) -> dict[str, str]:
    """A bearer token minted directly, because the gate lives on the LOGIN path only. A helper that
    signed in first would make every assertion below depend on the thing under test."""
    return {"Authorization": f"Bearer {create_access_token(subject=world['people'][slug].id)}"}


def _login(
    client: Any, email: str, password: str = PASSWORD, headers: dict[str, str] | None = None
) -> Any:
    # ``headers`` exists for exactly one caller: the CORS test, which sends an ``Origin`` so it can
    # read what a browser would be ALLOWED to read off the refusal. Every other call leaves it None.
    return client.post(
        "/api/auth/login", json={"email": email, "password": password}, headers=headers
    )


def _google(client: Any, monkeypatch: Any, email: str, name: str = "Google Person") -> Any:
    """The Google branch with only the token verification replaced. Stubbing any deeper would test
    the stub instead of the admission rule."""
    monkeypatch.setattr(
        auth_routes,
        "verify_google_token",
        lambda _token: {"email": email, "email_verified": True, "name": name},
    )
    return client.post("/api/auth/login", json={"googleIdToken": "stand-in-for-a-real-token"})


def _rows(client: Any, world: dict[str, Any], term: str | None = None) -> dict[str, Any]:
    """This run's allow-list rows, keyed by email. Narrowed by the run stamp so a database carrying
    a hundred previous runs cannot push this one off the first page."""
    response = client.get(
        "/api/access/roster",
        params={"search": term or world["stamp"], "pageSize": 200},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    return {row["email"]: row for row in response.json()["items"]}


def _accounts(client: Any, world: dict[str, Any], term: str) -> dict[str, Any]:
    response = client.get(
        "/api/users/directory", params={"search": term}, headers=_headers(world, "admin")
    )
    assert response.status_code == 200, response.text
    return {row["email"]: row for row in response.json()}


# --------------------------------------------------------------------------------------
# 1. The grandfathering — the non-negotiable
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize("slug,role", ACCOUNTS[:7])
async def test_every_existing_role_still_signs_in_after_the_migration(world, client, slug, role):
    """**THE PROOF THAT THE DEPLOY DOES NOT LOCK THE INSTITUTION OUT.**

    Each of these accounts was created with no allow-list row — the state of every account in the
    product on the morning this feature ships — and admitted by the two INSERT statements read out
    of the migration file. If either statement is ever dropped, narrowed or reordered so that it
    misses a role, this parametrisation is the thing that says so, and it says so per role rather
    than as one opaque failure.
    """
    response = _login(client, world["address"](slug))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == role


async def test_the_grandfathered_row_carries_the_real_joining_date(world, client):
    """THE DATE OF JOINING THE PLATFORM, backfilled from ``User.createdAt``.

    A wholesale ``now()`` would have told an admin that every account in a two-year-old product
    joined on the afternoon of the deploy — not merely useless but wrong, and unrecoverable once the
    real dates are overwritten. ``createdAt`` on the row is the row's own age and is deliberately
    NOT the same value.
    """
    row = _rows(client, world)[world["address"]("researcher")]
    assert row["joinedAt"] is not None
    assert row["joinedAt"][:10] == world["people"]["researcher"].createdAt.isoformat()[:10]
    assert row["status"] == "ACTIVE"
    # Nobody approved these; claiming an administrator did would be a fabricated audit trail.
    assert row["decidedById"] is None


# --------------------------------------------------------------------------------------
# 2. The two distinct refusals — the product owner's explicit ruling
# --------------------------------------------------------------------------------------


async def test_pending_and_wrong_password_are_different_answers(world, client):
    """**THE RULING, PINNED IN ONE TEST SO THAT COLLAPSING THE TWO CANNOT PASS.**

    A person awaiting approval is told exactly that, in words, with a 403. A person who mistyped is
    told the credential is wrong, with a 401 — unchanged. The usual advice is to make these
    identical so an attacker cannot enumerate addresses; that advice was weighed by the product
    owner and overruled, because this product has no registration page and no password-reset email,
    so "invalid email or password" leaves a waiting person with no next action that exists.

    The wrong-credential arm is asserted on the SAME account, which is what makes this a statement
    about the two answers rather than about two accounts.
    """
    waiting = _login(client, world["address"]("pending"))
    assert waiting.status_code == 403, waiting.text
    assert waiting.json()["detail"] == PENDING_DETAIL

    mistyped = _login(client, world["address"]("pending"), password="not-the-password")
    assert mistyped.status_code == 401, mistyped.text
    assert mistyped.json()["detail"] == WRONG_CREDENTIAL_DETAIL

    assert waiting.json()["detail"] != mistyped.json()["detail"]


async def test_the_pending_refusal_leaks_nothing_beyond_the_wait(world, client):
    """The agreed price is ONE BIT — "this address is awaiting approval". Not the person's name,
    not their role, not whether a password was ever set, not a hint about any other account. The
    sentence is a constant with no interpolation, and this asserts that nothing account-shaped has
    crept into the response around it."""
    body = _login(client, world["address"]("pending")).json()
    assert set(body) == {"detail"}
    person = world["people"]["pending"]
    assert person.name not in body["detail"]
    assert person.email not in body["detail"]
    assert person.id not in body["detail"]


async def test_a_rejected_person_is_told_they_were_rejected(world, client):
    """Distinct from pending, because "still waiting" and "answered, no" are different facts and
    only one of them is worth waiting for."""
    response = _login(client, world["address"]("rejected"))
    assert response.status_code == 403, response.text
    assert response.json()["detail"] == REJECTED_DETAIL


async def test_a_barred_account_is_told_its_access_was_suspended(world, client):
    """And NOT in the designer roster's words. An admin who bars a researcher must not see them
    told that their "designer access" ended — a sentence about a role they never had."""
    response = _login(client, world["address"]("barred"))
    assert response.status_code == 403, response.text
    assert response.json()["detail"] == BARRED_DETAIL
    assert response.json()["detail"] != DESIGNER_SUSPENDED_DETAIL


async def test_every_refusal_is_labelled_for_the_two_sign_in_screens(world, client, monkeypatch):
    """**THE SENTENCE IS FOR THE PERSON; THE HEADER IS FOR THE SCREEN AROUND IT.**

    Both clients draw different chrome around a refusal — a heading, and one line saying what to do
    — and the Android card's heading before this feature was "Your access to this app has been
    withdrawn", which is FALSE for four of the five refusals below. A person who is waiting to be
    approved for the first time has had nothing withdrawn, and telling them so sends them to argue
    with an administrator about an access they never had: the same class of wrong answer as
    "invalid email or password", which is the answer this whole feature exists to stop giving.

    THE CLIENTS MUST NOT MATCH ON THE PROSE. The sentences are English written for the reader and
    they will be reworded; a client keying off them would silently stop distinguishing "awaiting
    approval" from "suspended" the first time somebody fixed a comma, and the screen would go on
    looking correct. So the status travels in a header, which carries nothing the sentence does not
    already say in words — the leak is unchanged, and the next test pins that.

    ``DESIGNER_SUSPENDED`` is its own value and not ``SUSPENDED`` because the two have different
    remedies: one asks to be empanelled again, the other asks to be let back into the application.
    """
    header = auth_routes.ACCESS_STATUS_HEADER

    waiting = _login(client, world["address"]("pending"))
    assert waiting.headers.get(header) == "PENDING", waiting.headers

    rejected = _login(client, world["address"]("rejected"))
    assert rejected.headers.get(header) == "REJECTED", rejected.headers

    barred = _login(client, world["address"]("barred"))
    assert barred.headers.get(header) == "SUSPENDED", barred.headers

    empanelment = _login(client, world["address"]("designerSuspended"))
    assert empanelment.headers.get(header) == "DESIGNER_SUSPENDED", empanelment.headers

    # A MISTYPED PASSWORD CARRIES NO LABEL AT ALL. The clients read a 401 as the credential being
    # wrong and draw the plain field error; a label here would invite somebody to give it a panel,
    # which is this feature's own mistake made backwards.
    mistyped = _login(client, world["address"]("pending"), password="not-the-password")
    assert header not in mistyped.headers, mistyped.headers

    # And the capacity refusal, which is the one where waiting is actively the wrong advice: nobody
    # will ever see this person, because nothing was written down.
    monkeypatch.setattr(access_roster, "pending_cap", lambda: 0)
    full = _google(client, monkeypatch, f"access-labelled-{world['stamp']}@example.org")
    assert full.status_code == 503, full.text
    assert full.headers.get(header) == "NOT_RECORDED", full.headers


async def test_the_label_is_readable_by_a_browser_on_the_other_origin(world, client):
    """THE HALF OF THE HEADER THAT IS INVISIBLE TO EVERY OTHER TEST IN THIS FILE.

    The web app is served from a different origin from this API, and a cross-origin response only
    exposes a handful of "simple" headers to JavaScript — anything else has to be named in
    ``expose_headers`` on the CORS middleware. Without that one line the phone would read the label
    and the browser would not: every server test green, the Android sign-in screen right, and the
    web sign-in screen quietly falling back to neutral chrome with nothing anywhere naming the
    cause. That is the worst shape a cross-origin bug takes, so it is pinned from the outside, by
    sending an ``Origin`` and reading what the browser would be allowed to see.
    """
    origin = "http://localhost:3000"
    response = _login(client, world["address"]("pending"), headers={"Origin": origin})
    exposed = response.headers.get("access-control-expose-headers", "")
    assert auth_routes.ACCESS_STATUS_HEADER.lower() in exposed.lower(), (
        "the refusal label must be in expose_headers (app/main.py) or the browser cannot read it, "
        f"and the sign-in page cannot tell a waiting person from a mistyped password; got {exposed!r}"
    )


async def test_a_suspended_empanelment_still_answers_in_the_empanelments_words(world, client):
    """THE SECOND GATE SURVIVED THE FIRST ONE ARRIVING.

    This designer's platform access is perfectly fine — they were grandfathered like everybody
    else — and it is their EMPANELMENT that an admin ended. The specific answer has to win, or an
    admin revoking a designer's standing would be telling them the whole application is closed to
    them, and the two decisions would have become one.
    """
    response = _login(client, world["address"]("designerSuspended"))
    assert response.status_code == 403, response.text
    assert response.json()["detail"] == DESIGNER_SUSPENDED_DETAIL


# --------------------------------------------------------------------------------------
# 3. The master admin is never gated — the break-glass
# --------------------------------------------------------------------------------------


async def test_a_master_admin_with_no_allow_list_row_still_signs_in(world, client):
    """**THE ONE EXEMPTION, AND THE REASON THE GATE COULD BE WIDENED AT ALL.**

    The old designer-only gate was narrow on the argument that an admin locked out by a table only
    an admin can edit is an outage with no in-product remedy. Widening it to everybody does not make
    that argument wrong; it moves the weight onto this exemption. It lives in the GATE and not in a
    row of the table the gate reads, because an exemption stored in that table is one bad UPDATE
    away from the outage it was supposed to prevent — which is exactly the state this account is in.
    """
    response = _login(client, world["address"]("masterNoRow"))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "MASTER_ADMIN"
    assert world["address"]("masterNoRow") not in _rows(client, world), (
        "the exemption must not work by quietly writing the row it is exempt from"
    )


async def test_a_master_admin_is_not_gated_on_the_google_path_either(world, client, monkeypatch):
    """The break-glass on the branch it will actually be used from: a fresh deploy, an operator with
    no password on file, signing in with Google.

    ``role_for_email`` is stubbed rather than the environment being rewritten, because it is a pure
    read of ``MASTER_ADMIN_EMAIL`` and pointing it at a stamped test address is the same statement
    as setting that variable — without touching the real master admin's account on a shared
    development database.
    """
    address = world["address"]("masterNoRow")
    monkeypatch.setattr(
        auth_routes, "role_for_email", lambda email: "MASTER_ADMIN" if email == address else "X"
    )
    response = _google(client, monkeypatch, address)
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "MASTER_ADMIN"


async def test_a_master_admin_with_a_suspended_row_still_signs_in(world, client):
    """The same outage in its other shape: the row exists and says no. An admin who bars the master
    admin — by accident, or in anger on their way out — must not be able to end the institution's
    only remedy."""
    row_id = _rows(client, world)[world["address"]("master")]["id"]
    suspended = client.delete(f"/api/access/roster/{row_id}", headers=_headers(world, "admin"))
    assert suspended.status_code == 200, suspended.text
    assert suspended.json()["status"] == "SUSPENDED"

    response = _login(client, world["address"]("master"))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "MASTER_ADMIN"


# --------------------------------------------------------------------------------------
# 4. Google no longer hands out accounts
# --------------------------------------------------------------------------------------


async def test_an_unknown_google_address_gets_a_pending_row_and_no_account(
    world, client, monkeypatch
):
    """**THE BIGGEST BEHAVIOURAL CHANGE IN THE FEATURE.**

    Before this, a verified Google token for any address on earth reached ``db.user.create`` and
    walked away with an account and a bearer token: the product's sign-up page was Google's. Three
    things are asserted, and the third is the one that matters — a self-provisioned account is not
    a request an admin can approve or reject, it is a decision already taken.
    """
    stranger = f"access-stranger-{world['stamp']}@example.org"
    response = _google(client, monkeypatch, stranger, name="Definitely Not Stored")
    assert response.status_code == 403, response.text
    assert response.json()["detail"] == PENDING_DETAIL

    row = _rows(client, world, term=stranger)[stranger]
    assert row["status"] == "PENDING"
    assert row["requestedAt"] is not None
    assert row["attemptCount"] == 1
    assert stranger not in _accounts(client, world, stranger), (
        "a refused Google sign-in must not have created the account it was refused"
    )


async def test_nothing_the_caller_controls_is_stored_beyond_the_address(world, client, monkeypatch):
    """The display name on a Google profile is chosen by whoever owns that account, and the admin's
    pending queue is a list a human reads and acts on. A name lifted from it is a place to put
    "IT Support — approve to restore your mailbox"."""
    stranger = f"access-noname-{world['stamp']}@example.org"
    _google(client, monkeypatch, stranger, name="APPROVE ME — urgent request from IT")
    row = _rows(client, world, term=stranger)[stranger]
    assert row["fullName"] is None
    assert row["notes"] is None


async def test_an_empanelled_designer_with_no_account_is_still_provisioned(
    world, client, monkeypatch
):
    """AN ADMIN'S APPROVAL MADE IN THE OLDER SCREEN STILL COUNTS.

    ``DesignerRoster`` exists so an admin can empanel somebody before they have an account; the
    account provisions itself at DESIGNER on first Google sign-in. If the allow-list refused them,
    the admin would have done everything the product asked and produced a person the sign-in page
    turns away — with no way to see why, because the roster screen shows their row, active,
    admitting nobody. The admission is written to the allow-list on the way through, so the platform
    roster stays the complete answer to "who may sign in".
    """
    email = world["address"]("empanelled")
    response = _google(client, monkeypatch, email, name="Newly Empanelled")
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "DESIGNER"

    row = _rows(client, world)[email]
    assert row["status"] == "ACTIVE"
    assert row["admitRole"] == "DESIGNER"
    assert row["decidedById"] is None, "no administrator saw this one; the row must not claim so"


async def test_empanelling_somebody_who_is_waiting_answers_their_request(
    world, client, monkeypatch
):
    """An admin who sees a pending designer and empanels them HAS approved them.

    PENDING means nobody has said otherwise yet, so the empanelment IS the answer — and an admin who
    works the queue from the designer roster screen (which is where a designer's request belongs)
    must not find that the person is still refused because the approval was recorded in the other
    table.
    """
    email = f"access-late-empanel-{world['stamp']}@example.org"
    assert _google(client, monkeypatch, email).status_code == 403
    assert _rows(client, world, term=email)[email]["status"] == "PENDING"

    empanelled = client.post(
        "/api/designers/roster", json={"email": email}, headers=_headers(world, "admin")
    )
    assert empanelled.status_code == 201, empanelled.text

    admitted = _google(client, monkeypatch, email)
    assert admitted.status_code == 200, admitted.text
    assert admitted.json()["user"]["role"] == "DESIGNER"
    assert _rows(client, world, term=email)[email]["status"] == "ACTIVE"


async def test_an_empanelment_does_not_overturn_a_rejection(world, client, monkeypatch):
    """THE OTHER HALF OF THE SAME CLAUSE, and the half that is a security property.

    REJECTED and SUSPENDED are answers somebody actually gave about this person's access to the
    application. A years-old empanelment row must not reinstate them, or the allow-list's decisions
    become quietly conditional on a second table the deciding admin never looked at — and the
    reinstatement would be invisible in both screens.
    """
    email = f"access-rejected-empanel-{world['stamp']}@example.org"
    assert _google(client, monkeypatch, email).status_code == 403
    row_id = _rows(client, world, term=email)[email]["id"]
    assert client.post(
        f"/api/access/roster/{row_id}/decision",
        json={"decision": "REJECT"},
        headers=_headers(world, "admin"),
    ).status_code == 200

    empanelled = client.post(
        "/api/designers/roster", json={"email": email}, headers=_headers(world, "admin")
    )
    assert empanelled.status_code == 201, empanelled.text

    still_refused = _google(client, monkeypatch, email)
    assert still_refused.status_code == 403, still_refused.text
    assert still_refused.json()["detail"] == REJECTED_DETAIL
    assert email not in _accounts(client, world, email), (
        "an empanelment must not provision an account for somebody an administrator rejected"
    )


# --------------------------------------------------------------------------------------
# 5. The queue stays workable
# --------------------------------------------------------------------------------------


async def test_one_address_hammering_the_form_is_one_row(world, client, monkeypatch):
    """THE BOUND ON A WRITE NO SIGNED-IN USER MADE. Nothing in this codebase rate-limits the login
    endpoint, so the dedupe is the bound: a repeat attempt is an UPDATE that bumps a counter, and an
    admin reads the count as "this person is still trying" rather than scrolling past forty copies
    of one address."""
    email = f"access-repeat-{world['stamp']}@example.org"
    for _ in range(3):
        assert _google(client, monkeypatch, email).status_code == 403

    listed = [row for address, row in _rows(client, world, term=email).items() if address == email]
    assert len(listed) == 1, "one address must be one row"
    assert listed[0]["attemptCount"] == 3
    assert listed[0]["lastAttemptAt"] is not None


async def test_a_rejected_person_trying_again_does_not_re_queue(world, client):
    """**THE RULE THAT KEEPS THE QUEUE WORKABLE FOR THE ADMIN IT IS FOR.**

    If a re-attempt reopened the request, an admin's decision would be unenforceable: the person
    retries, the row returns to PENDING, and the queue they just cleared fills back up with the
    entries they cleared it of. The attempt is still COUNTED — that is the signal an admin can act
    on if somebody is genuinely trying to get a rejection reconsidered — and reopening it is an
    admin's APPROVE, which is a decision rather than a stranger's persistence.
    """
    email = world["address"]("rejected")
    before = _rows(client, world)[email]
    assert _login(client, email).status_code == 403
    after = _rows(client, world)[email]

    assert after["status"] == "REJECTED", "a rejected person must not put themselves back in the queue"
    assert after["attemptCount"] == before["attemptCount"] + 1
    assert after["requestedAt"] == before["requestedAt"], "the original request date must not move"


async def test_a_wrong_password_for_an_unknown_address_writes_nothing(world, client):
    """THE PRECONDITION EVERY OTHER BOUND RESTS ON: a row is only ever created for a PROVEN
    identity. The gate runs AFTER the credential, so a stranger cannot use this endpoint to write
    arbitrary addresses into an admin's queue — nor to discover which addresses are waiting."""
    email = f"access-nobody-{world['stamp']}@example.org"
    response = _login(client, email, password="whatever-they-guessed")
    assert response.status_code == 401, response.text
    assert response.json()["detail"] == WRONG_CREDENTIAL_DETAIL
    assert email not in _rows(client, world, term=email)


async def test_past_the_cap_the_request_is_refused_and_says_it_was_not_recorded(
    world, client, monkeypatch
):
    """A CEILING WITH A DOCUMENTED BEHAVIOUR, and the behaviour is honesty.

    Answering "you are awaiting approval" to somebody whose row was never created would be a lie
    with no expiry: no administrator will ever see them and they would wait forever on a queue they
    are not in. 503 rather than 403 because this one is not about the person — it is the product
    having stopped being able to accept requests, which is a thing that should page somebody.

    Driven by moving the cap rather than by writing five hundred rows, the same way the designer
    directory's cap test moves ``DIRECTORY_TAKE``.
    """
    monkeypatch.setattr(access_roster, "pending_cap", lambda: 0)
    email = f"access-overflow-{world['stamp']}@example.org"
    response = _google(client, monkeypatch, email)
    assert response.status_code == 503, response.text
    assert response.json()["detail"] == NOT_RECORDED_DETAIL
    assert email not in _rows(client, world, term=email)

    # AN EXISTING ROW IS UNAFFECTED BY THE CEILING. Somebody already in the queue must not start
    # being told the queue is full — they are in it.
    already = world["address"]("pending")
    refused = _login(client, already)
    assert refused.status_code == 403, refused.text
    assert refused.json()["detail"] == PENDING_DETAIL


# --------------------------------------------------------------------------------------
# 6. The admin surface: the count that is the notification, and the decision
# --------------------------------------------------------------------------------------


async def test_the_pending_count_is_admin_only_and_counts_the_queue(world, client, monkeypatch):
    """THE NOTIFICATION. There is no email sender and no push transport in this codebase, so
    "notify the admins" is a number on the surfaces they already open. Gated with the roster
    itself — the queue is a list of somebody's colleagues, applicants and former staff."""
    before = client.get("/api/access/roster/pending-count", headers=_headers(world, "admin"))
    assert before.status_code == 200, before.text
    assert isinstance(before.json()["pending"], int)

    email = f"access-counted-{world['stamp']}@example.org"
    assert _google(client, monkeypatch, email).status_code == 403
    after = client.get("/api/access/roster/pending-count", headers=_headers(world, "admin"))
    assert after.json()["pending"] == before.json()["pending"] + 1

    refused = client.get("/api/access/roster/pending-count", headers=_headers(world, "designer"))
    assert refused.status_code == 403, refused.text


async def test_the_queue_is_a_filter_over_the_one_list(world, client, monkeypatch):
    """``?status=PENDING`` is the queue the admin screens render. One list, one ordering, one
    paging — a separate endpoint for the queue would be a second ordering over the same rows, which
    is how a paged list starts repeating and skipping between pages.

    A misspelled status is a 422 naming the alternatives, not an empty list: "nothing matched" and
    "you typed it wrong" look identical on a screen and only one of them is the admin's fault.
    """
    email = f"access-queued-{world['stamp']}@example.org"
    assert _google(client, monkeypatch, email).status_code == 403

    queued = client.get(
        "/api/access/roster",
        params={"status": "pending", "search": email, "pageSize": 50},
        headers=_headers(world, "admin"),
    )
    assert queued.status_code == 200, queued.text
    assert [row["email"] for row in queued.json()["items"]] == [email]
    assert all(row["status"] == "PENDING" for row in queued.json()["items"])

    active_only = client.get(
        "/api/access/roster",
        params={"status": "ACTIVE", "search": email},
        headers=_headers(world, "admin"),
    )
    assert active_only.json()["items"] == []

    typo = client.get(
        "/api/access/roster", params={"status": "PENDNIG"}, headers=_headers(world, "admin")
    )
    assert typo.status_code == 422, typo.text
    assert "PENDING" in typo.json()["detail"]


async def test_approving_a_request_lets_the_person_in_at_the_chosen_tier(world, client):
    """The queue's whole purpose, end to end on one account.

    The approval also LIFTS an account that already exists. Without that, a request approved at
    RESEARCHER would take effect only when a new account is created — which never happens for
    somebody who already has one — and the admin's choice would be silently ignored.
    """
    email = world["address"]("pending")
    row_id = _rows(client, world)[email]["id"]

    decided = client.post(
        f"/api/access/roster/{row_id}/decision",
        json={"decision": "APPROVE", "role": "PROFESSOR"},
        headers=_headers(world, "admin"),
    )
    assert decided.status_code == 200, decided.text
    assert decided.json()["status"] == "ACTIVE"
    assert decided.json()["joinedAt"] is not None
    assert decided.json()["decidedById"] == world["people"]["admin"].id

    signed_in = _login(client, email)
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["role"] == "PROFESSOR", "the approved tier must reach the account"


async def test_approving_never_demotes_an_account(world, client):
    """An admin approving a colleague at RESEARCHER must not knock a professor down to it. The lift
    is strictly-below, ``login_with_google``'s rule, for its reason: a demotion delivered by
    somebody else's administrative click is invisible to the person it happens to."""
    email = world["address"]("professor")
    row_id = _rows(client, world)[email]["id"]
    decided = client.post(
        f"/api/access/roster/{row_id}/decision",
        json={"decision": "APPROVE", "role": "RESEARCHER"},
        headers=_headers(world, "admin"),
    )
    assert decided.status_code == 200, decided.text
    assert _login(client, email).json()["user"]["role"] == "PROFESSOR"


async def test_an_admin_cannot_approve_somebody_above_their_own_tier(world, client):
    """One rule for handing out tiers, imported rather than copied. An admin who cannot create a
    master admin through ``/users`` must not be able to mint one by approving a request."""
    row_id = _rows(client, world)[world["address"]("volunteer")]["id"]
    refused = client.post(
        f"/api/access/roster/{row_id}/decision",
        json={"decision": "APPROVE", "role": "MASTER_ADMIN"},
        headers=_headers(world, "admin"),
    )
    assert refused.status_code == 403, refused.text


async def test_suspending_an_entry_keeps_the_record(world, client):
    """DELETE NEVER REMOVES THE ROW. It would take the joining date, the attempt history and the
    name of the approving admin with it — and, because the gate reads a missing row as PENDING, it
    would silently put the person back in the queue they were removed from."""
    email = world["address"]("contributor")
    row_id = _rows(client, world)[email]["id"]
    suspended = client.delete(f"/api/access/roster/{row_id}", headers=_headers(world, "admin"))
    assert suspended.status_code == 200, suspended.text
    assert suspended.json()["status"] == "SUSPENDED"
    assert suspended.json()["joinedAt"] is not None

    refused = _login(client, email)
    assert refused.status_code == 403
    assert refused.json()["detail"] == BARRED_DETAIL
    assert email in _rows(client, world), "a suspension is not a deletion"


async def test_restoring_keeps_the_original_joining_date(world, client):
    """Somebody who joined in 2024, lost access for a month and was let back in has still been here
    since 2024. An admin reading a joining date of last Tuesday would draw the wrong conclusion
    about every record that person created."""
    email = world["address"]("contributor")
    row = _rows(client, world)[email]
    assert row["status"] == "SUSPENDED", "the previous test suspends this account"

    restored = client.post(
        f"/api/access/roster/{row['id']}/decision",
        json={"decision": "APPROVE"},
        headers=_headers(world, "admin"),
    )
    assert restored.status_code == 200, restored.text
    assert restored.json()["joinedAt"] == row["joinedAt"]
    assert _login(client, email).status_code == 200


# --------------------------------------------------------------------------------------
# 7. Every other path that mints an account has to admit it
# --------------------------------------------------------------------------------------


async def test_an_account_an_admin_creates_can_sign_in_immediately(world, client):
    """AN ADMIN CREATING AN ACCOUNT IS AN ADMIN APPROVING IT. Otherwise the admin hands somebody a
    password, watches them be told they are awaiting approval, and has to approve a request they
    themselves caused — in a second screen, for a person they just typed in."""
    email = f"access-made-{world['stamp']}@example.org"
    created = client.post(
        "/api/users",
        json={"email": email, "name": "Made By An Admin", "password": PASSWORD,
              "role": "RESEARCHER"},
        headers=_headers(world, "admin"),
    )
    assert created.status_code == 201, created.text
    signed_in = _login(client, email)
    assert signed_in.status_code == 200, signed_in.text
    assert _rows(client, world, term=email)[email]["status"] == "ACTIVE"


async def test_correcting_an_address_does_not_lock_the_account_out(world, client):
    """THE ALLOW-LIST IS KEYED BY EMAIL, so an admin fixing a typo would otherwise be revoking
    access: the new address has no row, and a missing row is a refusal. The row is MOVED rather than
    duplicated, so the joining date follows the person instead of being stranded on an address
    nobody uses."""
    old = f"access-typo-{world['stamp']}@example.org"
    new = f"access-fixed-{world['stamp']}@example.org"
    created = client.post(
        "/api/users",
        json={"email": old, "name": "Typo Ridden", "password": PASSWORD, "role": "RESEARCHER"},
        headers=_headers(world, "admin"),
    )
    assert created.status_code == 201, created.text
    joined = _rows(client, world, term=old)[old]["joinedAt"]

    changed = client.patch(
        f"/api/users/{created.json()['id']}",
        json={"email": new},
        headers=_headers(world, "admin"),
    )
    assert changed.status_code == 200, changed.text

    signed_in = _login(client, new)
    assert signed_in.status_code == 200, signed_in.text
    moved = _rows(client, world, term=new)[new]
    assert moved["joinedAt"] == joined, "the joining date must follow the person"
    assert old not in _rows(client, world, term=old), "the row is moved, not duplicated"


# --------------------------------------------------------------------------------------
# 8. The other door: the dataset token, which also turns a password into a token
# --------------------------------------------------------------------------------------
#
# ``POST /api/datasets/token`` is the second place in this API where an email and a password come
# in and a bearer token goes out. It checked the credential and the admin flag and then minted,
# with no reference to the allow-list at all — so revoking somebody's access closed the sign-in
# page and left this endpoint open, and the credential it issues is good for thirty days over
# every record in the repository and can be renewed indefinitely from the same password.
#
# The four cases below are the four answers that endpoint has to give: barred, rejected, admitted,
# and the one account no allow-list row may ever bar.


def _mint(client: Any, email: str, password: str = PASSWORD) -> Any:
    """Ask for a dataset token. The same two credentials ``_login`` sends, to the other door."""
    return client.post("/api/datasets/token", json={"email": email, "password": password})


async def test_a_suspended_admin_cannot_mint_a_dataset_token(world, client):
    """**THE SHIP-BLOCKER.** An admin an administrator has suspended is a revoked person, and a
    revoked person must not be able to walk to a second endpoint and take a thirty-day read
    credential over the entire repository out of it.

    Their ``User.role`` is untouched by the suspension — that is the whole trap — so ``is_admin``
    still says yes about them and the rank check this endpoint always had waves them through. Only
    the allow-list knows.

    The sign-in door is asserted in the same test, on the same account, because the claim is that
    the two doors now agree: an answer that differs between them is the defect, not a detail.
    """
    email = world["address"]("adminBarred")

    minted = _mint(client, email)
    assert minted.status_code == 403, minted.text
    assert minted.json()["detail"] == BARRED_DETAIL
    assert "accessToken" not in minted.json(), "a refusal must not carry the credential"
    # The same header the two sign-in screens branch on, so a client refused here draws the same
    # chrome it would draw on the login page rather than falling back to unclassified.
    assert minted.headers.get("X-Access-Status") == "SUSPENDED"

    signed_in = _login(client, email)
    assert signed_in.status_code == 403, signed_in.text
    assert signed_in.json()["detail"] == minted.json()["detail"], (
        "both doors must give a revoked admin the same answer; one open door revokes nothing"
    )

    # AND THE ENDPOINT STILL DOES NOT SAY WHETHER AN ACCOUNT EXISTS. The gate runs after the
    # password, so a caller without it reads exactly what they read for an address that was never
    # registered — the refusal above is reachable only by somebody holding the credential.
    guessed = _mint(client, email, password="not-the-password")
    assert guessed.status_code == 401, guessed.text
    assert guessed.json()["detail"] == WRONG_CREDENTIAL_DETAIL
    stranger = _mint(client, f"access-nobody-token-{world['stamp']}@example.org")
    assert stranger.status_code == 401
    assert stranger.json()["detail"] == WRONG_CREDENTIAL_DETAIL


async def test_a_rejected_admin_cannot_mint_a_dataset_token(world, client):
    """The other refusing state, and it must read as its own sentence rather than the suspension's.

    REJECTED and SUSPENDED are different decisions with different remedies — one was never let in,
    one was let in and then barred — and this endpoint borrows the sign-in page's wording precisely
    so an operator reading a cron job's log gets the same explanation the person would.
    """
    email = world["address"]("adminRejected")

    minted = _mint(client, email)
    assert minted.status_code == 403, minted.text
    assert minted.json()["detail"] == REJECTED_DETAIL
    assert minted.json()["detail"] != BARRED_DETAIL
    assert minted.headers.get("X-Access-Status") == "REJECTED"
    assert "accessToken" not in minted.json()


async def test_an_admitted_admin_still_gets_a_dataset_token(world, client):
    """**THE CONTROL, AND IT IS NOT OPTIONAL.** A gate that refuses everybody passes all three of
    the refusal tests above and breaks every nightly mirror in the estate. This is the case that
    says the endpoint still does its job: an ACTIVE admin mints, and mints the narrow credential —
    ``dataset:read`` and not a session token, which is the containment ``deps._user_from_bearer``
    enforces everywhere else in the API.
    """
    minted = _mint(client, world["address"]("admin"))
    assert minted.status_code == 200, minted.text
    body = minted.json()
    assert body["accessToken"]
    assert body["scope"] == "dataset:read"
    assert body["account"]["email"] == world["address"]("admin")
    assert body["expiresInMinutes"] > 0


async def test_a_master_admin_with_a_suspended_row_still_mints(world, client):
    """**THE BREAK-GLASS, AT THE SECOND DOOR.** The reason the allow-list could be widened from
    designers to everybody is that one account is exempt in the GATE rather than by a row in the
    table the gate reads. That exemption has to hold wherever the gate is asked, or adding the gate
    to a new endpoint quietly narrows it — and the account whose row an outgoing admin barred is
    exactly the one that needs to be able to take a copy of the data.

    The exemption is one predicate, ``deps.is_break_glass_master``, shared by every door that asks
    rather than copied to each — the sign-in gate, the dataset mint, the use of a dataset token, and
    the design-workshop viewer picker and write. Copied, they would drift, and whichever drifted
    would either lock the break-glass out or open it to somebody else.
    """
    email = world["address"]("masterBarred")
    assert _rows(client, world)[email]["status"] == "SUSPENDED", (
        "the fixture must actually have barred this row, or this test proves nothing"
    )

    minted = _mint(client, email)
    assert minted.status_code == 200, minted.text
    assert minted.json()["accessToken"]
    assert minted.json()["account"]["role"] == "MASTER_ADMIN"

    # And the exemption must not work by quietly writing the row it is exempt from — the same rule
    # the sign-in break-glass tests pin, for the same reason: an exemption that repairs the table
    # is an exemption an admin cannot see and cannot undo.
    assert _rows(client, world)[email]["status"] == "SUSPENDED"
