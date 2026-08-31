"""The sign-in gate, the profile's ownership rules, and the prefill a new workshop starts with.

Five things are pinned here, and every one of them is a way somebody loses their access or loses
their work.

**A SUSPENDED DESIGNER MUST BE TOLD THEY ARE SUSPENDED.** The refusal is a 403 carrying a sentence
that names the remedy, never a 401 saying the password was wrong. A designer whose access an admin
deliberately ended, reading "Invalid email or password", will reset a password that was never
wrong — twice, then telephone somebody who cannot help — and the actual state of affairs is never
mentioned anywhere they can see it.

**AN ADMIN IS NEVER GATED BY THE ROSTER.** That is the entire reason ``DesignerRoster`` is kept
apart from ``User.role``. An admin who was empanelled as a designer years ago and later suspended
would otherwise be locked out by a table only an admin can edit, and if that were the last admin
there is no remedy left inside the product at all.

**A DESIGNER MUST NOT BE ABLE TO WRITE A COLLEAGUE'S BIOGRAPHY.** The text saved on a profile is
printed verbatim in a report submitted to a ministry under that colleague's name. The refusal is a
404 with the same detail string as a genuinely missing user, because a 403 would confirm that an id
belongs to a real account and let a designer enumerate the staff.

**PREFILL IS A COPY.** A report is a historical document. A designer who moves from NIFT to NID in
2027 must not retroactively rewrite the 2026 workshop, so the last test here creates a workshop,
moves the profile, and asserts the earlier workshop still names the institution it was run from.

**ONE GMAIL MAILBOX IS ONE PERSON, HOWEVER IT IS SPELLED.** Google is the only sign-in path a
designer has and it treats the dots and the ``+tag`` in a Gmail local part as decoration, so an
admin who types ``sandy.craft3@gmail.com`` off a business card and a person whose token says
``sandycraft3@gmail.com`` were, for the whole life of this table, two different people to it — a row
on the admin's screen showing the right person, admitting nobody, with the four refusals above
unable to explain the difference because none of them was what had happened. Section 6 pins the
canonicalisation itself, and then pins the three cases that matter around it: an aliased sign-in
reaching a row stored as the mailbox, a row stored the OLD way still admitting its own spelling, and
a suspended row that a second spelling must not be able to walk around.

Postgres is required — the behaviour under test is a row in ``DesignerRoster`` deciding an HTTP
status, and a ``DWStageEntry`` appearing on a workshop nobody typed into — so the module skips
itself when ``DATABASE_URL`` does not point at a local database, exactly as ``test_stage_sync``
does.

    docker compose up -d postgres minio
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma

Nothing here reaches Google. ``verify_google_token`` is the only thing stubbed, because it is the
only part of that path that leaves the process; the roster read, the user upsert and the rank
comparison that decides a promotion all run exactly as they do in production.
"""

import os
import uuid
from datetime import UTC, datetime
from typing import Any

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.api.routes import auth as auth_routes
from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services.designers import canonical_email, email_match_keys
from app.services.rich_text import to_plain

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]

# Long enough for LoginRequest's min_length=8, and obviously not a credential.
PASSWORD = "roster-test-password"

STAGE_1 = "WORKSHOP_SETUP"
STAGE_3 = "WORKSHOP_PLAN_PARTICIPANTS_OPENING"

# Asserted verbatim rather than imported, deliberately. This string is the entire user-facing
# behaviour of the gate: it tells a locked-out designer what happened and what to do. Importing
# `DESIGNER_SUSPENDED_DETAIL` would make the test agree with whatever the constant is changed to,
# including "Unauthorized", and the assertion would pass while the feature was gone.
SUSPENDED_DETAIL = "Your designer access has been suspended. Contact the administrator."

# What the profile of the designer used for the prefill tests starts out holding.
PROFILE_NAME = "Meera Kanungo"
PROFILE_INSTITUTION = "NIFT Bhubaneswar"
PROFILE_BIOGRAPHY = "Twelve years of ikat and tie-and-dye across western Odisha."
PROFILE_YEARS = 12

#: The profile of the ADMIN who OPENS the workshops — deliberately different from the designer's
#: above, so a prefill assertion can name which of the two actually reached the stage. See the
#: ``designerprofile`` block in ``world`` for why an admin has one at all.
ADMIN_PROFILE_NAME = "Sunil Patnaik"
ADMIN_PROFILE_INSTITUTION = "Directorate of Handicrafts, Bhubaneswar"
ADMIN_PROFILE_BIOGRAPHY = "Twenty years administering cluster development programmes."
ADMIN_PROFILE_YEARS = 20

# Accounts the module needs, and the roster standing each of them is meant to have. Suspended and
# unlisted are two distinct states and both are tested, but they are no longer two ways of being
# REFUSED: since requirement 28 an allow-listed designer with no row at all is empanelled on the way
# in, and only a suspended row — an administrator's deliberate revocation — still refuses. See
# ``test_a_designer_who_was_never_empanelled_is_empanelled_by_the_allow_list`` for why that swap is
# the fix to a reported bug and not a weakening of the gate.
ACCOUNTS: tuple[tuple[str, str, str], ...] = (
    ("admin", "ADMIN", "Roster Admin"),
    # OUTRANKS A DESIGNER AND STILL CANNOT RUN A WORKSHOP. Here so the directory has a professor
    # to leave out; see test_the_directory_leaves_out_a_professor_the_viewer_write_would_refuse.
    ("professor", "PROFESSOR", "Senior Professor"),
    ("adminSuspended", "ADMIN", "Admin With A Suspended Row"),
    ("adminRostered", "ADMIN", "Admin On The Roster"),
    ("active", "DESIGNER", "Empanelled Designer"),
    ("barren", "DESIGNER", "Designer With No Profile"),
    ("colleague", "DESIGNER", "Another Designer"),
    ("suspended", "DESIGNER", "Suspended Designer"),
    ("unlisted", "DESIGNER", "Never Empanelled Designer"),
    ("volunteer", "CROWDSOURCE_VOLUNTEER", "Empanelled Volunteer"),
    ("stranger", "CROWDSOURCE_VOLUNTEER", "Ordinary Volunteer"),
    # The pair that proves the directory's cap is spent on eligible rows. Their names carry the run
    # stamp so one search term reaches these two and nothing else — neither the rest of this run
    # (whose stamp is in the EMAIL, not the name) nor the leftovers of a hundred previous runs. "A"
    # sorts before "Z" under the endpoint's ``name asc``, so with the cap moved to one row the
    # suspended designer is the row the take lands on. See
    # test_the_directory_cap_is_spent_on_designers_the_roster_still_admits.
    ("capSuspended", "DESIGNER", "Cap Probe {stamp} A Suspended"),
    ("capActive", "DESIGNER", "Cap Probe {stamp} Z Active"),
)

#: slug -> isActive. "newcomer" has a row and NO account, which is how an admin empanels somebody
#: who has never opened the app; the Google path is what provisions them.
ROSTER: tuple[tuple[str, bool], ...] = (
    ("active", True),
    ("barren", True),
    ("colleague", True),
    ("suspended", False),
    ("adminSuspended", False),
    ("adminRostered", True),
    ("volunteer", True),
    ("newcomer", True),
    ("capSuspended", False),
    ("capActive", True),
)


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    """Every account, roster row and profile the module needs, created before the app starts.

    The rows are made here rather than inside a test because the Prisma client is shared with the
    running app and is bound to the TestClient's event loop; touching it from a test's own loop is
    the kind of cross-loop use that fails intermittently rather than honestly.

    Every address carries a per-run stamp. ``DesignerRoster.email`` is UNIQUE, so fixed addresses
    would pass on a clean database and fail on the second run of the suite — the sort of flake
    that gets "fixed" by dropping the database, which throws away whatever it was protecting.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        # LOWER-CASED, and not incidentally. The slugs are camelCase and the login route looks a
        # user up by ``payload.email.lower()`` while ``roster_allows`` lower-cases too, so a
        # mixed-case address stored on either table is a row that can never be matched — which is
        # the exact production failure ``normalise_email`` exists to prevent, reproduced here by
        # accident the first time this fixture ran.
        return f"roster-{slug}-{stamp}@example.org".lower()

    def mailbox(slug: str) -> str:
        """The CANONICAL spelling of a Gmail address for this run — no dots, no tag, gmail.com.

        Deliberately free of dots and of ``+``, so that ``canonical_email`` is the identity on it
        and :func:`alias` below is the only string in the pair that has anything to strip. A stamp
        that happened to contain a dot would make every section-6 assertion pass for the wrong
        reason — the two spellings would be equal before canonicalisation ever ran — so the run
        stamp is folded in without one, exactly as ``address`` does.
        """
        return f"roster{slug}{stamp}@gmail.com".lower()

    def alias(slug: str) -> str:
        """The SAME MAILBOX as :func:`mailbox`, spelled the way Google also accepts it.

        Dots through the local part and the ``googlemail.com`` domain — the two halves of the
        reported failure in one string. ``canonical_email(alias(x)) == mailbox(x)`` is the whole
        premise of section 6, and it is asserted outright there rather than assumed here.
        """
        return f"roster.{slug}.{stamp}@googlemail.com".lower()

    people: dict[str, Any] = {}
    await db.connect()
    try:
        for slug, role, name in ACCOUNTS:
            people[slug] = await db.user.create(data={
                "email": address(slug),
                # ``format`` so an account can put the run stamp in its DISPLAY NAME. Only the cap
                # probes need it — a name is the one field the directory's search matches that the
                # address helper does not stamp, and without it no search term can single out two
                # accounts on a database holding a hundred previous runs. Every other name is
                # brace-free, so this is a no-op for them.
                "name": name.format(stamp=stamp),
                "role": role,
                "passwordHash": hash_password(PASSWORD),
            })
            # THE PLATFORM ALLOW-LIST ADMITS EVERY ACCOUNT THIS MODULE CREATES, so that what the
            # tests below observe is the DESIGNER EMPANELMENT gate and nothing else.
            #
            # Inserting a `User` row directly is not one of the two paths that admit somebody — an
            # admin creating an account through `/users` admits it, and a Google sign-in for an
            # admitted address provisions one — so a fixture that skipped this would find every
            # login answered "awaiting administrator approval" and would be testing
            # `auth.assert_access_admits` while claiming to test `assert_roster_admits`. Any
            # script that writes accounts straight into the database has the same obligation; see
            # app/services/access_roster.py.
            await db.accessroster.create(data={
                "email": address(slug),
                "status": "ACTIVE",
                "joinedAt": datetime.now(UTC),
                "notes": "Seeded by tests/test_designer_roster.py; this module is about the "
                         "designer empanelment gate, not the platform allow-list.",
            })
        # "newcomer" is deliberately given NO allow-list row: it has an ACTIVE designer-roster row
        # and no account, which is the empanel-before-the-account-exists flow, and the gate's
        # empanelment clause is what has to admit it. If that clause is ever removed,
        # test_a_rostered_email_with_no_account_is_created_as_a_designer fails here first.
        for slug, is_active in ROSTER:
            await db.designerroster.create(data={
                "email": address(slug),
                "fullName": f"Roster row for {slug}",
                "institution": "Directorate of Handicrafts",
                "isActive": is_active,
                # Never null on a row that cannot sign in: the roster screen reads the flag and
                # the date together, and a suspended row with no date reads as a bug in the screen
                # rather than as somebody's deliberate decision.
                "revokedAt": None if is_active else datetime.now(UTC),
                "addedById": people["admin"].id,
            })
        # ── THE GMAIL WORLD, FOR SECTION 6 ────────────────────────────────────────────────────
        #
        # THREE MAILBOXES AND NOT ONE ACCOUNT BETWEEN THEM, WHICH IS THE POINT. Every person here
        # signs in through Google for the first time inside the test that uses them, because that
        # is how an aliased address actually reaches these gates in production: an admin types a
        # spelling off a business card, and months later the person arrives with whatever spelling
        # Google puts in the token. Creating `User` rows for them in advance would test the
        # password path, which cannot reach this at all — it looks an account up by the exact
        # string typed into the form, so an alias never gets as far as the roster.
        #
        # `gCanonical` — rows stored as the MAILBOX, signed in to under the ALIAS. The case the
        #   canonicalisation is FOR, and the shape every row written from now on will have.
        # `gLegacy` — rows stored under the ALIAS, exactly as an admin's typing left them before
        #   this existed, signed in to under that same alias. The BACKWARDS-COMPATIBILITY case:
        #   these two rows are the ones that go dark if anybody ever "simplifies" the match keys
        #   down to the canonical form alone.
        # `gCollision` — one mailbox with TWO roster rows that disagree: an active one under the
        #   canonical spelling and an administrator's revocation under the alias. The pair cannot
        #   be created by any current write path — `ensure_empanelled` and `admit` both search both
        #   spellings — and exists precisely because the table may already be holding one, written
        #   before there was anything to stop it.
        for slug, email in (("gCanonical", mailbox), ("gLegacy", alias), ("gCollision", mailbox)):
            await db.accessroster.create(data={
                "email": email(slug),
                "status": "ACTIVE",
                # DESIGNER, so the account the Google path provisions is a DESIGNER and the
                # EMPANELMENT gate is the one deciding these tests. Admitted at any lower tier the
                # account would sail past `assert_roster_admits` without it being consulted, and
                # every assertion below would pass with the roster read deleted.
                "admitRole": "DESIGNER",
                "joinedAt": datetime.now(UTC),
                "notes": "Seeded by tests/test_designer_roster.py section 6 (Gmail aliasing).",
            })
        for slug, email, is_active in (
            ("gCanonical", mailbox, True),
            ("gLegacy", alias, True),
            ("gCollision", mailbox, True),
            ("gCollision", alias, False),
        ):
            await db.designerroster.create(data={
                "email": email(slug),
                "fullName": f"Gmail roster row for {slug}",
                "isActive": is_active,
                "revokedAt": None if is_active else datetime.now(UTC),
                "addedById": people["admin"].id,
            })
        await db.designerprofile.create(data={
            "user": {"connect": {"id": people["active"].id}},
            "displayName": PROFILE_NAME,
            "institution": PROFILE_INSTITUTION,
            "biography": PROFILE_BIOGRAPHY,
            "experienceYears": PROFILE_YEARS,
        })
        # A PROFILE ON THE **ADMIN**, WHICH ARRIVED WITH THE CREATE RULE AND IS NOT DECORATION.
        #
        # ``seed_designer_prefill`` copies the profile of the account the workshop is FOR: the one
        # ``designerUserId`` names, and the CREATOR's when the body names nobody. Only admins and
        # the master admin may start a workshop, so that second case always copies an ADMIN's
        # profile — an ordinary thing to be here, since ADMIN is inside ``DESIGN_WORKSHOP_ROLES``
        # precisely so that admins can run workshops of their own.
        #
        # Its values are DIFFERENT from the designer's on purpose, and that difference is the only
        # reason the prefill tests below can fail at all. Each of them turns on WHICH of these two
        # profiles reached the stage, and the pair carrying the whole rule —
        # ``test_a_workshop_opened_for_a_NAMED_designer_carries_the_DESIGNERS_details`` and
        # ``test_a_workshop_that_names_no_designer_still_carries_the_ADMINS_details`` — differ only
        # in the one field the rule turns on, ``designerUserId``. (Their titles differ too, but a
        # title is a label; and the second issues a viewers PUT the first does not need, because
        # naming a designer grants their row in the create itself.)
        await db.designerprofile.create(data={
            "user": {"connect": {"id": people["admin"].id}},
            "displayName": ADMIN_PROFILE_NAME,
            "institution": ADMIN_PROFILE_INSTITUTION,
            "biography": ADMIN_PROFILE_BIOGRAPHY,
            "experienceYears": ADMIN_PROFILE_YEARS,
        })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "people": people,
            "address": address,
            "mailbox": mailbox,
            "alias": alias,
            "stamp": stamp,
        }


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any], slug: str) -> dict[str, str]:
    """A bearer token for one of the fixture's accounts.

    Minted directly rather than obtained by signing in, because the roster gate lives on the
    LOGIN path only. Everything below the login — the profile routes, the workshop routes — must
    behave the same whether the token came from a password or from Google, and a helper that
    signed in first would silently make every one of those tests depend on the gate as well.
    """
    return {"Authorization": f"Bearer {create_access_token(subject=world['people'][slug].id)}"}


def _login(client: Any, email: str, password: str = PASSWORD) -> Any:
    return client.post("/api/auth/login", json={"email": email, "password": password})


def _google(client: Any, monkeypatch: Any, email: str, name: str = "Google Person") -> Any:
    """Sign in through the Google path with the token verification replaced.

    Only ``verify_google_token`` is stubbed. Stubbing any further in — ``login_with_google``, or
    the roster read — would test the stub instead of the promotion rule this file exists to pin.
    """
    monkeypatch.setattr(
        auth_routes,
        "verify_google_token",
        lambda _token: {"email": email, "email_verified": True, "name": name},
    )
    return client.post("/api/auth/login", json={"googleIdToken": "stand-in-for-a-real-token"})


def _roster_rows(client: Any, world: dict[str, Any]) -> dict[str, Any]:
    """This run's roster rows, keyed by email. Narrowed by the run stamp so a database that has
    accumulated a hundred previous runs cannot push this one off the first page."""
    response = client.get(
        "/api/designers/roster",
        params={"search": world["stamp"], "pageSize": 100},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    return {row["email"]: row for row in response.json()["items"]}


def directory_rows(client: Any, world: dict[str, Any], **params: Any) -> dict[str, Any]:
    """This run's directory rows, keyed by email, narrowed by the run stamp for the same reason
    ``_roster_rows`` narrows: the endpoint caps at 500 accounts and a database carrying a hundred
    previous runs would otherwise push this run's people off the end of the cut."""
    response = client.get(
        "/api/designers/directory",
        params={"search": world["stamp"], **params},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    return {row["email"]: row for row in response.json()}


def _role_only_user(role: str) -> Any:
    """The least a permission predicate needs: an object with a ``role``. Deliberately not one of
    the fixture's real rows — the question "may this ROLE run a workshop" must be answerable from
    the role alone, and passing a full user would let a capability grant answer it instead."""
    from types import SimpleNamespace

    return SimpleNamespace(role=role)


# --------------------------------------------------------------------------------------
# 1. The sign-in gate
# --------------------------------------------------------------------------------------


async def test_an_active_roster_row_lets_a_designer_in(world, client):
    response = _login(client, world["address"]("active"))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "DESIGNER"


async def test_a_suspended_designer_is_told_they_are_suspended(world, client):
    """403 AND THE SENTENCE — the whole reason the gate is not a 401.

    The person reading this cannot fix it themselves and there is exactly one action that leads
    anywhere, so the refusal has to name it. A generic credential error sends them to the
    password-reset screen instead, where nothing they do can possibly work.
    """
    response = _login(client, world["address"]("suspended"))
    assert response.status_code == 403, response.text
    assert response.json()["detail"] == SUSPENDED_DETAIL


async def test_a_designer_who_was_never_empanelled_is_empanelled_by_the_allow_list(world, client):
    """**CHANGED BY REQUIREMENT 28, AND THE OLD ASSERTION WAS THE REPORTED BUG.**

    This test used to assert that a DESIGNER with no roster row read the same refusal as a suspended
    one — "two different internal states, one thing the designer can usefully be told". The states
    are still different and the sentence was never the problem; what was wrong is that this state
    was a refusal at all. An account the platform allow-list admits AS A DESIGNER has been approved
    by an administrator, and answering it with a sentence about a suspended empanelment describes a
    revocation that never happened, on a screen (``/admin/designers``) showing no row at all to
    explain it. ``sandycraft3@gmail.com`` hit exactly this in production.

    So the empanelment is created on the way through, by ``ensure_empanelled`` in ``auth.login``,
    and the account signs in. THE REFUSAL HAS NOT GONE ANYWHERE — it now belongs solely to a
    SUSPENDED row, which is an administrator's deliberate act, and that is pinned by the test above
    this one and by ``test_the_allow_list_never_revives_a_suspended_empanelment`` in
    ``tests/test_platform_access_gate.py``.
    """
    email = world["address"]("unlisted")
    assert email not in _roster_rows(client, world), (
        "the fixture must NOT have empanelled this account, or this test proves nothing"
    )

    response = _login(client, email)
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "DESIGNER"

    row = _roster_rows(client, world)[email]
    assert row["isActive"] is True
    # Nobody administered this one, so ``addedById`` must not name anybody — and because that column
    # is also NULL on a hand-made row whose admin was since deleted, the note is what actually tells
    # the two apart on the roster screen.
    assert row["addedById"] is None
    assert "automatically" in (row["notes"] or ""), row["notes"]


async def test_a_wrong_password_is_still_a_401_on_an_empanelled_account(world, client):
    """THE GATE RUNS AFTER THE CREDENTIAL, and this is what that ordering protects.

    Answering 403-suspended before checking the password would tell anybody holding a leaked
    address list which accounts are designers, and it would send a designer who merely mistyped to
    the administrator rather than to the reset link.
    """
    response = _login(client, world["address"]("active"), password="not-the-password")
    assert response.status_code == 401, response.text
    assert response.json()["detail"] == "Invalid email or password"


async def test_an_admin_with_no_roster_row_is_never_gated(world, client):
    """The roster is the empanelment list of DESIGNERS. An admin has no row and needs none."""
    response = _login(client, world["address"]("admin"))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "ADMIN"


async def test_an_admin_with_a_suspended_roster_row_still_signs_in(world, client):
    """THE OUTAGE THIS PREVENTS.

    An admin who was empanelled as a designer years ago and later suspended would be locked out by
    a table only an admin can edit. If that were the last admin, nobody left in the product could
    add the row that lets anybody back in — an outage with no remedy short of a psql prompt.
    """
    response = _login(client, world["address"]("adminSuspended"))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "ADMIN"


async def test_a_volunteer_is_not_gated_by_the_designer_roster(world, client):
    """Ranks below DESIGNER are not gated either, and that is not a hole: refusing a volunteer for
    not being a designer is not a rule anybody wrote. The role grants the power; the roster only
    asks whether the institution still recognises the person the role was granted to."""
    response = _login(client, world["address"]("stranger"))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "CROWDSOURCE_VOLUNTEER"


async def test_the_google_path_is_gated_too(world, client, monkeypatch):
    """BOTH PATHS, or the one that was forgotten is the one that gets used.

    A suspended designer holding a perfectly valid Google token is refused with the same sentence,
    and the promotion clause a few lines above the gate must not smuggle them back in.
    """
    response = _google(client, monkeypatch, world["address"]("suspended"))
    assert response.status_code == 403, response.text
    assert response.json()["detail"] == SUSPENDED_DETAIL


async def test_first_seen_is_stamped_on_a_successful_sign_in_and_not_on_a_refused_one(
    world, client
):
    """``firstSeenAt`` answers "did the invitation ever reach them".

    An admin adds five designers in March and has no way, in April, to tell which of them ever
    opened the app: an invitation that never arrived looks exactly like one that was ignored. A
    stamp written on a REFUSED attempt would report the invitation as accepted on the very day
    the designer could not get in, which is worse than no signal at all.
    """
    assert _login(client, world["address"]("active")).status_code == 200
    assert _login(client, world["address"]("suspended")).status_code == 403

    rows = _roster_rows(client, world)
    assert rows[world["address"]("active")]["firstSeenAt"] is not None
    assert rows[world["address"]("suspended")]["firstSeenAt"] is None


async def test_the_first_seen_stamp_records_the_first_sign_in_not_the_latest(world, client):
    """Written once. If every sign-in rewrote it, the column would answer "when did they last log
    in" while being read as "when did they accept", and no admin would notice the difference."""
    first = _roster_rows(client, world)[world["address"]("active")]["firstSeenAt"]
    assert first is not None, "the earlier test in this module already signed this designer in"
    assert _login(client, world["address"]("active")).status_code == 200
    assert _roster_rows(client, world)[world["address"]("active")]["firstSeenAt"] == first


# --------------------------------------------------------------------------------------
# 2. Provisioning: an active roster row is an instruction to promote
# --------------------------------------------------------------------------------------


async def test_a_rostered_email_with_no_account_is_created_as_a_designer(
    world, client, monkeypatch
):
    """HOW AN ADMIN EMPANELS SOMEBODY WHO HAS NEVER OPENED THE APP.

    There is no invitation email in this product and no account to promote. What there is, is the
    moment the person first signs in with Google — so the row is read there and the account is
    created at DESIGNER. Without this it would be created at DEFAULT_SIGNUP_ROLE, and the
    designer's first experience of the app they were invited to would be a home screen with no way
    to start a workshop.
    """
    response = _google(client, monkeypatch, world["address"]("newcomer"), name="Brand New")
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "DESIGNER"


async def test_an_account_below_designer_is_promoted_on_first_google_sign_in(
    world, client, monkeypatch
):
    """The other half of the same story: the person already had a volunteer account."""
    response = _google(client, monkeypatch, world["address"]("volunteer"))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "DESIGNER"


async def test_an_admin_on_the_roster_is_never_demoted_by_signing_in(world, client, monkeypatch):
    """PROMOTE ONLY, NEVER DEMOTE.

    Admins and professors run workshops too, so their address is legitimately on the roster. A
    not-equal comparison instead of a strictly-below one would knock them down to DESIGNER at
    their own next sign-in — losing their admin rights to a row they added to help somebody else,
    with the demotion invisible in the login response they are looking at.
    """
    response = _google(client, monkeypatch, world["address"]("adminRostered"))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "ADMIN"


async def test_google_sign_in_without_a_roster_row_promotes_nobody(world, client, monkeypatch):
    """The control. If this passed as DESIGNER, the roster would not be gating anything: every
    Google account in the world would be empanelling itself."""
    response = _google(client, monkeypatch, world["address"]("stranger"))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "CROWDSOURCE_VOLUNTEER"


# --------------------------------------------------------------------------------------
# 3. The roster is admin-only, and DELETE is a suspension
# --------------------------------------------------------------------------------------


async def test_a_designer_cannot_read_the_roster(world, client):
    """READ is gated as tightly as write, unlike most require_* pairs in this app. The roster is a
    list of named individuals, their institutions and an admin's private note about the programme
    each was empanelled under — and, from firstSeenAt, which colleague has stopped using the app.
    """
    response = client.get("/api/designers/roster", headers=_headers(world, "active"))
    assert response.status_code == 403, response.text


async def test_deleting_a_roster_entry_suspends_it_and_keeps_the_record(world, client):
    """DELETE NEVER REMOVES THE ROW.

    The roster is the record that somebody was empanelled, and that record outlives their access:
    an audit two years later asking who was recognised under which programme gets "nobody" out of
    a deleted row. A second suspension keeps the ORIGINAL date, because that date is the answer to
    "when did this designer lose access" and a second click on the button would destroy it.
    """
    email = f"roster-revoked-{world['stamp']}@example.org"
    created = client.post(
        "/api/designers/roster",
        json={
            "email": email,
            "fullName": "Empanelled Then Revoked",
            "notes": "Empanelled under NHDP 2026.",
        },
        headers=_headers(world, "admin"),
    )
    assert created.status_code == 201, created.text
    roster_id = created.json()["id"]

    first = client.delete(f"/api/designers/roster/{roster_id}", headers=_headers(world, "admin"))
    assert first.status_code == 200, first.text
    assert first.json()["isActive"] is False
    assert first.json()["revokedAt"] is not None
    assert first.json()["notes"] == "Empanelled under NHDP 2026.", "the record must survive"

    again = client.delete(f"/api/designers/roster/{roster_id}", headers=_headers(world, "admin"))
    assert again.json()["revokedAt"] == first.json()["revokedAt"], (
        "a second suspension must not move the date the designer actually lost access"
    )
    assert email in _roster_rows(client, world), "a suspension is not a deletion"


async def test_re_empanelling_an_existing_email_is_a_409_naming_the_row(world, client):
    """Not a 500 from the unique index, and not a silent overwrite.

    The common way to arrive here is an admin re-empanelling a designer they suspended in March.
    Overwriting would erase the note recording the original empanelment — the one thing the row
    exists to preserve — so the answer has to say where the row is instead. The address is sent in
    mixed case on purpose: the roster is lower-cased on the way in, and a duplicate that slipped
    past on capitalisation is a designer locked out by a second row nobody can see.
    """
    email = f"roster-dupe-{world['stamp']}@example.org"
    first = client.post(
        "/api/designers/roster", json={"email": email}, headers=_headers(world, "admin")
    )
    assert first.status_code == 201, first.text

    clash = client.post(
        "/api/designers/roster",
        json={"email": email.replace("roster", "Roster").upper()},
        headers=_headers(world, "admin"),
    )
    assert clash.status_code == 409, clash.text
    assert first.json()["id"] in clash.json()["detail"], "the answer must say WHICH row"


async def test_suspending_a_row_ends_access_and_restoring_gives_it_back(world, client):
    """The gate and the admin screen, end to end, on one account.

    Restoring must CLEAR ``revokedAt``. A row that is active and still carries a revocation date
    leaves the next admin reading it with two facts that disagree and no way to tell whether the
    person may sign in.

    **AND SINCE THE CROSS-ROSTER MIRROR LANDED, THIS TEST IS ALSO EVIDENCE THAT ITS GUARD WORKS —
    WHICH IS WORTH KNOWING BEFORE SOMEBODY "FIXES" THE FIXTURE.** Ending an empanelment now suspends
    the allow-list row that admission rested on, and reactivation deliberately never propagates
    back, so on an account whose admission DID rest on the empanelment the restore below would not
    be enough to sign in — the person would be refused by the platform gate instead, in its own
    sentence. That does not happen here because this module's fixture admits every account with
    ``admitRole`` left NULL, which is the platform default and not a designer admission: these
    people are on the allow-list as ordinary members of the institution, so
    ``access_roster.admissions_an_empanelment_carries`` finds nothing to bar and the mirror is a
    silent no-op. Giving this fixture ``admitRole: "DESIGNER"`` would therefore turn this test red,
    and the correct reading of that would be the mirror working rather than the roster breaking. See
    ``tests/test_designer_empanelment_auto.py`` section 6, where the mirrored case is pinned on
    fixtures built for it.
    """
    roster_id = _roster_rows(client, world)[world["address"]("barren")]["id"]

    assert client.delete(
        f"/api/designers/roster/{roster_id}", headers=_headers(world, "admin")
    ).status_code == 200
    refused = _login(client, world["address"]("barren"))
    assert refused.status_code == 403, refused.text
    assert refused.json()["detail"] == SUSPENDED_DETAIL

    restored = client.patch(
        f"/api/designers/roster/{roster_id}",
        json={"isActive": True},
        headers=_headers(world, "admin"),
    )
    assert restored.status_code == 200, restored.text
    assert restored.json()["revokedAt"] is None
    assert _login(client, world["address"]("barren")).status_code == 200


async def test_the_directory_hides_a_suspended_designer_unless_asked(world, client):
    """Assigning a fortnight of fieldwork to somebody whose row was revoked last month produces a
    workshop nobody can open: the assignment succeeds, the designer's next sign-in is refused, and
    the gap is discovered when the report is due. ``includeSuspended`` shows them, MARKED, for an
    admin who is deciding whom to restore."""
    def directory(**params: Any) -> dict[str, Any]:
        response = client.get(
            "/api/designers/directory",
            params={"search": world["stamp"], **params},
            headers=_headers(world, "admin"),
        )
        assert response.status_code == 200, response.text
        return {row["email"]: row for row in response.json()}

    default = directory()
    assert world["address"]("active") in default
    assert world["address"]("suspended") not in default
    # An admin has no roster row at all, so `rosterActive` is false and `canSignIn` is true. The
    # picker must disable rows on the second of those, or it would hide every admin and professor.
    assert default[world["address"]("admin")]["canSignIn"] is True

    shown = directory(includeSuspended=True)
    assert shown[world["address"]("suspended")]["canSignIn"] is False


async def test_the_directory_cap_is_spent_on_designers_the_roster_still_admits(
    world, client, monkeypatch
):
    """THE CAP MUST APPLY TO ROWS THAT ARE ALREADY ELIGIBLE.

    The suspension filter used to run in Python AFTER ``take=500``, so the cap was spent on rows
    the route then dropped: twenty suspended designers sorting inside the first 500 came back as
    480, with eligible designers past the cut never read at all. Both clients infer "this list was
    cut" from its length against their own copy of 500 (``DIRECTORY_CAP`` on the web,
    ``DESIGNER_DIRECTORY_CAP`` on Android), and a post-take drop is precisely what makes that
    inference wrong in the direction that matters — a short list reported as complete.

    Driven by moving the cap to one row rather than by writing five hundred users, the same way
    ``test_a_cut_roster_read_is_reported_instead_of_dropping_designers_in_silence`` drives the
    roster read's cap. With the cap at one, the suspended probe is the row the take lands on: the
    old code returned NOTHING here, having spent its only row on an account it then discarded.
    """
    from app.api.routes import designers as designers_route

    monkeypatch.setattr(designers_route, "DIRECTORY_TAKE", 1)
    probe = f"Cap Probe {world['stamp']}"

    admitted = directory_rows(client, world, search=probe)
    assert list(admitted) == [world["address"]("capActive")], (
        "the one row the cap allowed was spent on a suspended designer and then thrown away, so "
        "an eligible designer past the cut was never read"
    )

    # The other arm is unchanged and still shows the suspended row FIRST, which is what makes the
    # assertion above a statement about the WHERE and not about the sort order changing.
    shown = directory_rows(client, world, search=probe, includeSuspended=True)
    assert list(shown) == [world["address"]("capSuspended")]
    assert shown[world["address"]("capSuspended")]["canSignIn"] is False


async def test_the_directory_leaves_out_a_professor_the_viewer_write_would_refuse(world, client):
    """THE ONE NON-MONOTONIC RULE IN THIS PERMISSION MODEL, pinned on the endpoint that got it wrong.

    ``WORKSHOP_CAPABLE_ROLES`` was ``[role for role, rank in ROLE_RANK.items() if rank >=
    ROLE_RANK["DESIGNER"]]``, and PROFESSOR is 40 to DESIGNER's 35 — so this directory returned
    professors, with ``canSignIn: true`` (the roster gates DESIGNER rows only, so a professor is
    ungated and therefore unmarked). ``design_workshop_viewers`` refuses a professor's grant with
    an all-or-nothing 422: a picker built on this list would offer an account whose selection
    discards the entire PUT body, and the admin's whole roster change with it.

    Both halves are asserted deliberately. The membership assertion catches the shipped defect;
    the agreement assertion catches its cause, because a rank threshold that happens to exclude
    professors today would pass the first one and go wrong again the moment a tier is inserted.
    """
    from app.api.routes.designers import WORKSHOP_CAPABLE_ROLES
    from app.core.deps import ROLE_RANK, can_run_design_workshops

    listed = directory_rows(client, world, includeSuspended=True)
    assert world["address"]("professor") not in listed, (
        "a professor was offered as somebody an admin may hand a workshop to; the viewer write "
        "answers 422 to exactly that grant"
    )
    # The designer and the admin are still there — this must be a professor-shaped hole and not an
    # endpoint that quietly stopped returning anyone.
    assert world["address"]("active") in listed
    assert world["address"]("admin") in listed

    for role in ROLE_RANK:
        expected = can_run_design_workshops(_role_only_user(role))
        assert (role in WORKSHOP_CAPABLE_ROLES) is expected, (
            f"{role}: the directory's role set and can_run_design_workshops disagree — the "
            "capability is a frozenset, never a rank floor"
        )


# --------------------------------------------------------------------------------------
# 4. Whose profile a person may write
# --------------------------------------------------------------------------------------


async def test_a_new_profile_cannot_be_created_without_an_empanelment_number(world, client):
    """THE FIRST HALF OF THE OWNER'S DECISION OF 2026-08-30.

    The empanelment number is what a ministry report is filed under and — since the same day —
    something a designer may SIGN IN with, so a profile created without one is a profile
    somebody has to chase later through a channel this product does not have.

    THE REFUSAL IS NARROW ON PURPOSE and the next two tests are the other side of it: a save
    that puts nothing in the profile is not creating one (no refusal), and a profile that
    already has content may go on saving without a number (the grace path). This one is the
    only shape that is refused — the save that would bring a profile into being.
    """
    refused = client.put(
        "/api/designers/me/profile",
        json={"institution": "NID Ahmedabad", "biography": "Bamboo and cane, Assam."},
        headers=_headers(world, "colleague"),
    )
    assert refused.status_code == 422, refused.text
    assert "empanelment number" in refused.json()["detail"].lower()

    # AND IT MUST NOT HAVE WRITTEN. A refusal that saved the other two columns would leave a
    # profile that is now on the grace path, so the next save would be allowed — the rule
    # would have talked itself out of existence in one request.
    stored = client.get("/api/designers/me/profile", headers=_headers(world, "colleague"))
    assert stored.status_code == 200, stored.text
    assert stored.json()["institution"] is None


async def test_an_empty_save_is_not_a_creation_and_is_not_refused(world, client):
    """A PUT that puts nothing in the profile is a no-op, not a creation.

    Refusing it would mean a client that saves on blur cannot open the form at all, and the
    rule would be enforcing itself against somebody who has not typed anything yet.
    """
    response = client.put(
        "/api/designers/me/profile", json={}, headers=_headers(world, "colleague")
    )
    assert response.status_code == 200, response.text


async def test_a_designer_may_write_their_own_profile(world, client):
    response = client.put(
        "/api/designers/me/profile",
        json={
            "institution": "NID Ahmedabad",
            "biography": "Bamboo and cane, Assam.",
            # REQUIRED SINCE 2026-08-30 on a save that CREATES the profile — see the test
            # above. This body used to carry two keys; the third is the rule, not padding.
            "empanelmentNo": "DES/2026/0801",
        },
        headers=_headers(world, "colleague"),
    )
    assert response.status_code == 200, response.text
    assert response.json()["institution"] == "NID Ahmedabad"
    # THE NUMBER IS STORED VERBATIM AND THE KEY IS NORMALISED. The raw column is what a report
    # prints; ``signInByEmpanelmentNo`` says whether the normalised form was actually claimed,
    # which is the only way a designer can find out that signing in with it will work.
    assert response.json()["empanelmentNo"] == "DES/2026/0801"
    assert response.json()["empanelmentNoMissing"] is False


async def test_a_profile_that_already_has_content_may_save_without_an_empanelment_number(
    world, client
):
    """THE GRACE PATH — the second half of the owner's decision, and the half that is easy to
    lose to a tidier rule.

    ``active``'s profile is seeded with a name, an institution and a biography and no number,
    which is the shape of every profile that predates the requirement. Refusing its next save
    would mean a designer correcting a typo in their own name is told to produce a document
    they may not have at this desk today — and the practical outcome of that is not a filled-in
    field, it is an abandoned edit and a lost correction.

    ``empanelmentNoMissing`` coming back TRUE is what both clients draw the persistent one-line
    banner from. It is computed on the server precisely so the web and the handset cannot end
    up with two definitions of \"has content\".
    """
    target = world["people"]["active"].id
    response = client.put(
        f"/api/designers/{target}/profile",
        # DELIBERATELY NOT THE BIOGRAPHY. Two later tests in this file assert that ``active``
        # still carries ``PROFILE_BIOGRAPHY`` — one about a refused write, one about what a
        # workshop copies into stage 3 — and a grace-path save that rewrote it would fail both
        # for a reason that has nothing to do with either. Any column with content will do; the
        # rule under test is about the empanelment number being absent, not about which field.
        json={"department": "Textile Design"},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    assert response.json()["empanelmentNoMissing"] is True, (
        "the save was allowed but the banner would not be drawn, so nobody is ever asked"
    )


async def test_a_designer_may_not_write_a_colleagues_profile(world, client):
    """ONE DESIGNER PUTTING WORDS IN ANOTHER'S MOUTH.

    This is not tidiness. The biography saved here is printed verbatim in a report that goes out
    under that person's name to a ministry. The refusal is a 404 rather than a 403 because a 403
    confirms the id belongs to a real account, and a designer holding a list of cuids could
    enumerate the staff by watching which ones answer which.
    """
    target = world["people"]["active"].id
    response = client.put(
        f"/api/designers/{target}/profile",
        json={"biography": "Words this designer never wrote."},
        headers=_headers(world, "colleague"),
    )
    assert response.status_code == 404, response.text
    assert response.json()["detail"] == "Record not found"

    stored = client.get(f"/api/designers/{target}/profile", headers=_headers(world, "admin"))
    assert stored.status_code == 200, stored.text
    assert stored.json()["biography"] == PROFILE_BIOGRAPHY, "the refusal must also not have written"


async def test_a_forbidden_profile_is_indistinguishable_from_a_missing_one(world, client):
    """Same status AND the same detail string, or the difference between them is the leak."""
    denied = client.get(
        f"/api/designers/{world['people']['active'].id}/profile",
        headers=_headers(world, "colleague"),
    )
    missing = client.get(
        "/api/designers/there-is-no-user-with-this-id/profile",
        headers=_headers(world, "colleague"),
    )
    assert denied.status_code == missing.status_code == 404
    assert denied.json()["detail"] == missing.json()["detail"]


async def test_an_admin_may_write_a_designers_profile(world, client):
    """Admins maintain the empanelment identifiers a government report has to carry, which the
    designer often does not have to hand. Absent keys must leave the stored value alone, and THIS
    REQUEST IS THE GROUND THAT RULE STANDS ON: an admin correcting two of the twenty-one fields,
    where a body treating absent as "clear" would erase the nineteen the designer typed on the web
    the week before.

    IT USED TO BE ARGUED FROM ANDROID RENDERING A SUBSET OF THE FIELDS, and that has stopped being
    true: ``ProfileForm`` declares all twenty-one and ``toBody()`` sends the lot on every PUT,
    ``cvMediaId`` included — see ui/designworkshop/DesignerProfileScreen.kt. A rule whose stated
    reason is no longer a fact is a rule the next reader deletes.
    """
    target = world["people"]["colleague"].id
    response = client.put(
        f"/api/designers/{target}/profile",
        json={"empanelmentNo": "EMP/2026/0042", "empanelmentDate": "2026-03-14"},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    assert response.json()["empanelmentNo"] == "EMP/2026/0042"
    assert response.json()["empanelmentDate"].startswith("2026-03-14")
    assert response.json()["institution"] == "NID Ahmedabad", (
        "an admin correcting two fields must not blank the nineteen the designer typed"
    )


async def test_the_profile_belongs_to_the_people_who_run_workshops(world, client):
    """Gated at ``can_run_design_workshops`` — Designer, Admin, Master Admin.

    THIS IS A DELIBERATE NARROWING, and it replaces the opposite rule. The route used to take any
    signed-in account, on the argument that somebody standing in for an absent designer signs the
    report the same way and needs the same details on file. That argument survives and is why
    ADMIN is still in the set: an admin standing in gets a profile. What does not survive is a
    crowdsource volunteer having one — a designer profile is the name, institution and biography a
    report is SUBMITTED UNDER, and an account that cannot start a workshop has no report to sign.

    It was also unenforceable as it stood. The web client hides the page from anyone below the
    designer set, and a UI guard over an open endpoint hides the link while leaving the URL.
    """
    admin = client.get("/api/designers/me/profile", headers=_headers(world, "admin"))
    assert admin.status_code == 200, admin.text
    assert admin.json()["userId"] == world["people"]["admin"].id

    designer = client.get("/api/designers/me/profile", headers=_headers(world, "active"))
    assert designer.status_code == 200, designer.text

    refused = client.get("/api/designers/me/profile", headers=_headers(world, "stranger"))
    assert refused.status_code == 403, refused.text
    assert "designer" in refused.json()["detail"].lower()


# --------------------------------------------------------------------------------------
# 5. Prefill: the profile copied into a brand-new workshop
# --------------------------------------------------------------------------------------


@pytest.fixture
def workshop_of(world, client):
    """``create(creator_slug, title, grant_to=…, designer_for=…)`` -> the id of a new workshop.

    THE FIRST PARAMETER IS THE CREATOR AND IT MUST BE AN ADMIN. This fixture used to be handed a
    designer slug, because designers opened their own workshops; only admins and the master admin
    may start one now (``can_create_design_workshops``), so a designer slug answers 403. The slug is
    still a parameter rather than hard-coded because WHICH admin matters to the prefill: with no
    designer named, the creator's profile is what ``seed_designer_prefill`` copies, so "an admin with
    a profile" and "an admin without one" are two different fixtures' worth of behaviour and both
    are asserted below.

    ``designer_for`` SENDS ``designerUserId`` ON THE CREATE — the designer the workshop is FOR, whose
    profile is copied and who is granted access in the same call. It is the parameter the whole
    "whose details does a report carry" question turns on, and it is separate from ``grant_to`` on
    purpose: the two together are exactly the case that used to have no correct answer, an admin
    naming one designer and putting three people on the workshop.

    ``grant_to`` names one or more accounts to put on the workshop through the ordinary admin PUT
    AFTER creation, which is how co-designers are added in the field. A single slug or a list. It is
    what lets the tests read the seeded stages back as that person through the ordinary endpoint —
    the whole point of reading them that way — and it is optional because the prefill can also be
    inspected by the admin who created it, where a grant would be noise.

    ``expect`` IS THE STATUS THE CREATE ITSELF MUST ANSWER, and it exists so that a REFUSED create
    can be asserted through the same helper rather than by a hand-rolled post that would drift from
    it. It answers ``None`` in that case, because there is no workshop.
    """

    def create(
        creator_slug: str,
        title: str,
        grant_to: str | list[str] | None = None,
        designer_for: str | None = None,
        expect: int = 201,
    ) -> str | None:
        body: dict[str, Any] = {"title": title}
        if designer_for:
            body["designerUserId"] = world["people"][designer_for].id
        response = client.post(
            "/api/design-workshops", json=body, headers=_headers(world, creator_slug)
        )
        assert response.status_code == expect, response.text
        if expect != 201:
            return None
        workshop_id = response.json()["id"]
        if grant_to:
            slugs = [grant_to] if isinstance(grant_to, str) else grant_to
            granted = client.put(
                f"/api/design-workshops/{workshop_id}/viewers",
                json={"userIds": [world["people"][slug].id for slug in slugs]},
                headers=_headers(world, "admin"),
            )
            assert granted.status_code == 200, granted.text
        return workshop_id

    return create


def _singleton(client: Any, world: dict[str, Any], slug: str, workshop_id: str, stage: str) -> Any:
    response = client.get(
        f"/api/design-workshops/{workshop_id}/stages/{stage}", headers=_headers(world, slug)
    )
    assert response.status_code == 200, response.text
    return response.json()["singleton"]


async def test_a_new_workshop_starts_with_the_creators_profile_in_stages_1_and_3(
    world, client, workshop_of
):
    """The chore this removes: retyping an institution and a biography into every workshop.

    Read back through the ORDINARY stage endpoint, deliberately. The values have to be plain stage
    data — the same rows the designer's own edit would produce — because the report builder, the
    completeness score and the phone's offline renderer all read stage data and none of them knows
    a profile exists. A prefill visible only through a special case would work on the server and
    print blank on the phone.

    IT SAYS "CREATOR" WHERE IT USED TO SAY "DESIGNER", AND THE RENAME IS THE HONEST PART. Only
    admins and the master admin may start a workshop, so the account whose profile
    ``seed_designer_prefill`` copies WHEN THE CREATE NAMES NOBODY is the ADMIN who opened it — and
    this body names nobody, which is why the ADMIN's values are what it asserts. A create that DOES
    name a designer copies THEIR profile instead; that is
    ``test_a_workshop_opened_for_a_NAMED_designer_carries_the_DESIGNERS_details``. The mechanism
    below is the one both share, and it is what this test pins: a profile lands in stage 1 and
    stage 3 as ordinary stage data, and nothing that reads it knows a profile exists.
    """
    workshop_id = workshop_of("admin", "Prefilled workshop", grant_to="active")

    stage_1 = _singleton(client, world, "active", workshop_id, STAGE_1)
    assert stage_1["designerName"] == ADMIN_PROFILE_NAME
    assert stage_1["designerInstitution"] == ADMIN_PROFILE_INSTITUTION

    stage_3 = _singleton(client, world, "active", workshop_id, STAGE_3)
    # Through ``to_plain``: ``designerProfile`` is NARRATIVE and therefore RICH_TEXT, so the
    # prefill's plain biography is stored as a one-paragraph block document. That IS the promotion
    # working — ``coerce_value`` reads a plain string as unformatted prose precisely so a value
    # written before the field became rich is not lost. What this test cares about is that the
    # roster's biography reached stage 3, which is a claim about the text and not about its shape.
    assert to_plain(stage_3["designerProfile"]) == ADMIN_PROFILE_BIOGRAPHY
    assert stage_3["designerExperience"] == ADMIN_PROFILE_YEARS, "an INT field must arrive as an int"


async def test_a_workshop_opened_for_a_NAMED_designer_carries_the_DESIGNERS_details(
    world, client, workshop_of
):
    """**REQUIREMENT 3, AGAINST A REAL DATABASE: the report names the DESIGNER, not the admin.**

    THIS TEST USED TO BE ITS OWN OPPOSITE. It was
    ``test_a_workshop_an_admin_opens_for_a_designer_carries_the_ADMINS_details``, and it recorded —
    as an assertion, so that nobody would meet it first in a submitted document — that a workshop
    an admin opened FOR a designer arrived carrying the ADMIN's name, institution and biography,
    because ``seed_designer_prefill`` copied the CREATOR's profile and only an admin may create.
    Its own docstring named the fix and said "WHEN THAT LANDS, THIS TEST MUST BE REWRITTEN, NOT
    DELETED". ``DesignWorkshopCreate.designerUserId`` landed. This is the rewrite.

    NOTHING BUT ``designer_for`` IS PASSED, AND THE MISSING ``grant_to`` IS AN ASSERTION IN ITSELF.
    Every read below is made AS ``active``, who holds no grant of their own, so a create that named
    the designer without putting them on the workshop answers 404 here rather than a wrong name.
    That is what "one act, not two" means: an admin who names a designer and then forgets the
    viewers panel would otherwise leave a designer locked out of the workshop whose stage 1 already
    carries their name, and the only symptom is a 404 they cannot tell from a workshop that does
    not exist.

    ``test_workshop_designer_naming.py`` pins whose profile is read against a stubbed ``db``, which
    is the only way it can run when Docker is not up. What it cannot pin is THIS: that the schema,
    the route's ordering, the grant and the registry agree well enough for a real Postgres row to
    come back through the ordinary stage endpoint with the right person's name in it.
    """
    workshop_id = workshop_of("admin", "Opened for a named designer", designer_for="active")

    stage_1 = _singleton(client, world, "active", workshop_id, STAGE_1)
    assert stage_1["designerName"] == PROFILE_NAME
    assert stage_1["designerName"] != ADMIN_PROFILE_NAME, (
        "the admin who opened this workshop has a profile of their own, and that profile is what "
        "used to reach the cover of a report submitted to a ministry"
    )
    assert stage_1["designerInstitution"] == PROFILE_INSTITUTION

    # NINETEEN OF THE TWENTY-ONE PREFILLED FIELDS LAND ON STAGE 3 (two on stage 1 — see
    # ``designers.PREFILL_MAP``), AND THAT IS WHERE THE COST WAS. Stage 1 is the box a human might
    # notice; stage 3 is the nineteen they would not, and a designer who never opens it submits
    # somebody else's biography, phone number, address, empanelment number and signature without
    # ever seeing the boxes. Through ``to_plain`` for the reason the creator test above gives.
    stage_3 = _singleton(client, world, "active", workshop_id, STAGE_3)
    assert to_plain(stage_3["designerProfile"]) == PROFILE_BIOGRAPHY
    assert stage_3["designerExperience"] == PROFILE_YEARS, "an INT field must arrive as an int"

    header = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "active")
    )
    assert header.status_code == 200, header.text
    assert header.json()["designerName"] == PROFILE_NAME, (
        "``designerName`` is a promoted column, so this is what the workshop LIST and the report "
        "cover read — a fix that reached the stage entry and not the column would leave both "
        "naming somebody who has nothing to do with this workshop"
    )


async def test_a_workshop_that_names_no_designer_still_carries_the_ADMINS_details(
    world, client, workshop_of
):
    """THE OTHER HALF, AND WHAT MAKES ``designerUserId`` ADDITIVE RATHER THAN A FLAG DAY.

    A create body that names nobody behaves exactly as every create behaved before the field
    existed: the CREATOR's profile is copied, and the creator is an admin because
    ``can_create_design_workshops`` is {ADMIN, MASTER_ADMIN}. Today's web create form, its offline
    draft store and today's Android build all send such a body, so if this outcome had moved with
    the field it would have been a release-day behaviour swap on every client at once.

    IT IS ALSO THE HONEST RECORD OF WHAT IS NOT FIXED YET, and it keeps the ADMINS_details name
    under which the rest of the tree already refers to that outcome. The stubbed sibling suite,
    ``test_workshop_designer_naming``, cites it by that name from
    ``test_naming_nobody_still_copies_the_creators_profile_byte_for_byte``. A workshop opened for a
    designer whom the admin did not NAME still arrives carrying the admin's details, on the report
    cover and in the promoted ``designerName`` column the list reads. That is recoverable (stage 1
    is editable) and visible (the designer opens it and sees a name that is not theirs), and it
    stops being reachable when the picker on the create form is what every admin uses.
    """
    workshop_id = workshop_of("admin", "Opened for nobody in particular", grant_to="active")
    stage_1 = _singleton(client, world, "active", workshop_id, STAGE_1)

    assert stage_1["designerName"] == ADMIN_PROFILE_NAME
    assert stage_1["designerName"] != PROFILE_NAME, (
        "the designer who will run this workshop is 'active', and with nobody named on the create "
        "their name is NOT what was seeded"
    )
    header = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "active")
    )
    assert header.status_code == 200, header.text
    assert header.json()["designerName"] == ADMIN_PROFILE_NAME, (
        "and it reaches the promoted column, so it is what the list and the report cover show"
    )


async def test_a_named_designer_with_no_profile_leaves_the_boxes_EMPTY_on_a_real_database(
    world, client, workshop_of
):
    """**THE MISSING ``or``, PINNED WHERE THE PROFILE READ ACTUALLY HAPPENS.**

    ``seed_designer_prefill`` chooses with ``prefill_from_profile(designer_id or actor.id)`` and
    never falls back a second time. One plausible extra ``or`` further down — "if the designer
    answered nothing, use the creator's" — restores the whole defect in the case where it is
    hardest to notice: an admin picks a designer off a list and gets their OWN name back.

    THE CREATOR HERE IS ``admin``, WHO HAS A PROFILE, and that is the whole design of this test.
    ``barren`` is a designer the roster admits who has never opened the Designer Page, so a fallback
    would have somewhere to fall TO — which makes the empty stages below a statement about the
    missing ``or`` and not about there being nothing to copy from anywhere. The sibling assertion in
    ``test_workshop_designer_naming`` stubs ``db``, so what it can pin is that the stub was asked
    once and once only; this one asks Postgres and reads the answer back through the stage endpoint.

    A BLANK IS THE RIGHT ANSWER AND NOT A DEGRADATION TO APOLOGISE FOR. ``designerName`` is a
    required Basic-tier stage-1 field, so an empty one is counted by the completeness score, shown
    on the readiness screen and named in ``build_report``'s warnings. The admin's name in that same
    box is counted as COMPLETE and warned about by nothing.

    Read as ``barren`` with no ``grant_to``, so this also says that naming somebody put them on the
    workshop even in the case where their profile had nothing to give it.
    """
    workshop_id = workshop_of("admin", "Named a designer with no profile", designer_for="barren")

    assert _singleton(client, world, "barren", workshop_id, STAGE_1) == {}
    assert _singleton(client, world, "barren", workshop_id, STAGE_3) == {}

    header = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "barren")
    )
    assert header.status_code == 200, header.text
    assert header.json()["designerName"] is None, (
        "the promoted column must be empty too — a blank the completeness score and the report "
        f"warnings can both see, never {ADMIN_PROFILE_NAME}, whose profile was one ``or`` away"
    )


async def test_naming_a_designer_the_roster_no_longer_admits_refuses_the_WHOLE_create(
    world, client, workshop_of
):
    """422 AND NO WORKSHOP: eligibility is settled ABOVE ``db.designworkshop.create``.

    ``assert_every_designer_may_be_named`` is ``design_workshop_viewers._assert_every_id_may_be_granted``
    IMPORTED rather than copied, so naming somebody on the create is refused by exactly the rule the
    viewers screen enforces. The account here is a DESIGNER whose roster row was revoked — the same
    row that answers their sign-in with the 403 the top of this module is about — so a viewer row
    for them would be a grant their next sign-in ignores, one screen saying they have access while
    they are shown a refusal. The refusal SENTENCES belong to that function and are pinned against
    it in ``test_design_workshop_viewers.test_an_ineligible_account_is_a_422_that_names_it``; what
    is pinned HERE is the ORDER, which nothing without a database can count.

    ASKED AFTER THE CREATE, the same 422 would answer the client with the workshop row already
    committed, so an admin correcting the picker and pressing create again would accumulate one
    orphan draft per attempt, in a list that distinguishes them in no way.
    ``test_workshop_designer_naming`` reads that ordering out of the route's SOURCE in
    ``test_the_create_route_settles_eligibility_BEFORE_it_writes_the_workshop_row`` because it has
    no database to count rows in. This counts them.

    The title carries the run stamp for the reason ``_roster_rows`` narrows by it: the assertion is
    that NOTHING was written, and a database holding a hundred previous runs must not be able to
    answer it with somebody else's leftovers.
    """
    title = f"Refused before it existed {world['stamp']}"
    assert workshop_of("admin", title, designer_for="suspended", expect=422) is None

    listed = client.get(
        "/api/design-workshops", params={"search": title}, headers=_headers(world, "admin")
    )
    assert listed.status_code == 200, listed.text
    assert listed.json()["total"] == 0, (
        "the refused create left a workshop behind, so the eligibility check has moved below "
        "db.designworkshop.create: every retry of a mis-picked designer now costs an orphan draft "
        "that nothing in the list can tell from a workshop somebody meant to open"
    )


async def test_naming_one_designer_and_granting_a_team_seeds_only_the_NAMED_designers_details(
    world, client, workshop_of
):
    """AN ADMIN NAMES ONE DESIGNER AND PUTS THREE PEOPLE ON THE WORKSHOP.

    This is the case that used to have no correct answer, and it is why ``designerUserId`` and the
    viewer set are separate parameters. Co-designers are ordinary in the field, so a prefill that
    inferred the designer from the grants would have to GUESS between them — silently, and
    differently on different days, since viewer rows carry no order anybody chose.
    ``seed_designer_prefill`` never so much as reads ``DesignWorkshopViewer`` (pinned as a stub
    assertion in ``test_workshop_designer_naming``); what that buys is asserted here, on real rows.

    Read back as ``colleague`` and as ``barren``, who were granted and are NOT the named designer.
    Both of them see Meera's name rather than their own or the admin's, because the report they help
    write is submitted under the designer the workshop was opened FOR. Reading as BOTH is what makes
    this a test of the LIST form of ``grant_to``: a helper that took a list and granted only its
    first slug would leave both of these reads answering 404 instead of a name.
    """
    workshop_id = workshop_of(
        "admin",
        "Named one designer, granted a team",
        grant_to=["active", "colleague", "barren"],
        designer_for="active",
    )

    for slug in ("colleague", "barren"):
        seen = _singleton(client, world, slug, workshop_id, STAGE_1)
        assert seen["designerName"] == PROFILE_NAME, f"{slug} was granted but reads {seen}"
        assert seen["designerInstitution"] == PROFILE_INSTITUTION, (
            "a grant is not a nomination: three people can open this workshop and exactly one of "
            "them is the designer it was opened for"
        )


async def test_the_prefilled_designer_name_reaches_the_promoted_column(
    world, client, workshop_of
):
    """``designerName`` is a promoted column, so it is what the workshop LIST shows.

    A prefill that wrote the stage entry and skipped the column would leave every new workshop
    showing no designer in the one screen an admin scans — and the fix would look like a bug in
    the list rather than in the create.
    """
    workshop_id = workshop_of("admin", "Promoted prefill", grant_to="active")
    header = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "active")
    )
    assert header.status_code == 200, header.text
    assert header.json()["designerName"] == ADMIN_PROFILE_NAME
    assert header.json()["title"] == "Promoted prefill", (
        "seeding a promoted column must not overwrite the title the designer just typed"
    )


async def test_a_creator_with_no_profile_gets_an_empty_stage_one(world, client, workshop_of):
    """No profile is not an error, and it must not be a 500 on the create.

    The workshop row is already committed by the time the prefill runs, so a failure that
    propagated would leave an orphan draft behind on every retry until somebody noticed the list
    filling up with untitled duplicates.

    THE ACCOUNT WITH NO PROFILE IS NOW AN ADMIN (``adminRostered``) AND USED TO BE A DESIGNER
    (``barren``), for the reason the fixture above gives: with no designer named the prefill copies
    the CREATOR's profile, and only admins create workshops, so "the creator has no profile" is the
    case this is about and a designer cannot express it any more. ``adminRostered`` has a roster
    row and no ``DesignerProfile``, which is exactly the shape needed — and it is emphatically NOT
    the ``admin`` account, which was given a profile precisely so the prefill could be exercised.

    This is also the ORDINARY case for a create that names nobody: most admins are administrators
    and have no designer profile at all, so such a workshop opens with an empty designer block and
    the designer types their own details into stage 1 — which is what happened before the prefill
    existed, and is the recoverable half of the cost recorded in
    ``test_a_workshop_that_names_no_designer_still_carries_the_ADMINS_details``.

    THE OTHER EMPTY DESIGNER BLOCK IN THIS SECTION MEANS SOMETHING ELSE ENTIRELY, and the two must
    not be read as one case:
    ``test_a_named_designer_with_no_profile_leaves_the_boxes_EMPTY_on_a_real_database`` has a
    creator who DOES have a profile, and there the emptiness is the deliberate refusal to fall back
    to it. Here there is simply nothing anywhere to copy.
    """
    workshop_id = workshop_of("adminRostered", "No profile workshop", grant_to="barren")
    assert _singleton(client, world, "barren", workshop_id, STAGE_1) == {}
    assert _singleton(client, world, "barren", workshop_id, STAGE_3) == {}


async def test_the_prefill_is_a_copy_and_a_later_profile_edit_never_reaches_it(
    world, client, workshop_of
):
    """**THE RULE THIS SECTION EXISTS FOR.**

    A report is a HISTORICAL DOCUMENT. It records a workshop that was run, on given dates, by a
    named person working out of a named institution AT THE TIME. If a workshop's stages held a
    reference to the profile instead of a copy of its values, a designer who moves from NIFT to
    NID in 2027 would retroactively rewrite the 2026 report: regenerating it, or merely previewing
    it, would name an institution that had nothing to do with the workshop and had never sponsored
    it. Copying is not an optimisation here; referencing would be a falsification.
    """
    earlier = workshop_of("admin", "Ran from NIFT", grant_to="active")
    assert _singleton(client, world, "active", earlier, STAGE_1)["designerInstitution"] == (
        ADMIN_PROFILE_INSTITUTION
    )

    # The profile edited is the CREATOR's, because with no designer named on either create it is
    # the creator's profile that the prefill copies.
    # The rule under test — copy, never reference — is a property of ``seed_designer_prefill`` and
    # is indifferent to whose profile it is; what it cannot be indifferent to is that the account
    # whose profile moved is the account whose values were seeded, which is why this moved from
    # "active" to "admin" along with the two workshops around it.
    moved = client.put(
        "/api/designers/me/profile",
        json={"institution": "NID Ahmedabad"},
        headers=_headers(world, "admin"),
    )
    assert moved.status_code == 200, moved.text

    later = workshop_of("admin", "Ran from NID", grant_to="active")
    assert _singleton(client, world, "active", later, STAGE_1)["designerInstitution"] == (
        "NID Ahmedabad"
    )
    assert _singleton(client, world, "active", earlier, STAGE_1)["designerInstitution"] == (
        ADMIN_PROFILE_INSTITUTION
    ), "the earlier workshop must still name the institution it was actually run from"


# --------------------------------------------------------------------------------------
# 6. One Gmail mailbox is one person, however it is spelled
# --------------------------------------------------------------------------------------
#
# THE PURE-FUNCTION TESTS IN THIS SECTION NEED NO DATABASE AND ARE SKIPPED WITHOUT ONE ANYWAY,
# because the module-level `pytestmark` at the top of this file skips everything here when
# `DATABASE_URL` does not point at a local Postgres. That is a real cost and it is accepted
# deliberately: `canonical_email` exists only to decide what the four tests below it do, and a
# reader who finds a sign-in refused needs the rule and the consequence in one place rather than in
# two files that have to be read together. If these ever need to run on a machine with no database
# — in a lint-only CI job, say — move them out WITH the behavioural tests, never on their own.


def test_gmail_dots_are_not_part_of_the_address():
    """THE REPORTED FAILURE, reduced to the one line that caused it.

    ``sandy.craft3@gmail.com`` and ``sandycraft3@gmail.com`` are one inbox to Google, so an admin
    reading either of them off a business card has typed the same person. Until this function
    existed they were two keys in two rosters, and the person was refused by a row that named them.
    """
    assert canonical_email("sandy.craft3@gmail.com") == "sandycraft3@gmail.com"
    assert canonical_email("s.a.n.d.y.c.r.a.f.t.3@gmail.com") == "sandycraft3@gmail.com"
    # Already canonical: the function is the identity here, which is what makes it safe to run over
    # every address on the way into the table rather than only over the ones that look aliased.
    assert canonical_email("sandycraft3@gmail.com") == "sandycraft3@gmail.com"


def test_a_plus_tag_is_not_part_of_the_address():
    """A ``+suffix`` is a filing label the person invented, not a second mailbox.

    It is cut at the FIRST ``+``, and the whole tag goes — including any dots inside it, which is
    why the tag is removed before the dots are and not after. A designer who signs up as
    ``sandycraft3+ministry@gmail.com`` and is then empanelled as ``sandycraft3@gmail.com`` must not
    be two people, in either order.
    """
    assert canonical_email("sandycraft3+ministry@gmail.com") == "sandycraft3@gmail.com"
    assert canonical_email("sandy.craft3+dpw.2026@gmail.com") == "sandycraft3@gmail.com"
    assert canonical_email("sandycraft3+a+b@gmail.com") == "sandycraft3@gmail.com"


def test_googlemail_is_gmail():
    """``googlemail.com`` is the name Gmail was sold under in Germany, Russia and the UK.

    Google still accepts it and still delivers it to the same inbox, so a roster row carrying the
    older domain has to be the same key as one carrying the newer. Both halves of the fold are
    asserted together, because the address that actually turns up is usually spelled the old way
    AND with dots — that combination is one string, and it has to reduce to one mailbox.
    """
    assert canonical_email("sandycraft3@googlemail.com") == "sandycraft3@gmail.com"
    assert canonical_email("sandy.craft3+work@googlemail.com") == "sandycraft3@gmail.com"


def test_a_non_gmail_address_keeps_every_dot_and_every_tag_it_was_given():
    """**THE ASSERTION THAT STOPS THIS FUNCTION MERGING TWO COLLEAGUES.**

    Dots are significant everywhere except at the two domains Google has told us they are not.
    ``a.sharma@nift.ac.in`` and ``asharma@nift.ac.in`` may be two different members of staff, and a
    canonicaliser that folded them would hand one of them the other's sign-in, empanelment and
    workshop authorship — in the table whose entire job is deciding who is who. The ``+`` is left
    alone for the same reason: sub-addressing is a per-site convention (Postfix's
    ``recipient_delimiter`` is configurable and often is not ``+`` at all), so ``accounts+billing@``
    may be a real, distinct mailbox at a domain that never switched it on.

    ``normalise_email``'s work still happens — this is a refinement of it and not a replacement, so
    the case folding and the trimming are asserted here too.
    """
    assert canonical_email("a.sharma@nift.ac.in") == "a.sharma@nift.ac.in"
    billing = "accounts+billing@handicrafts.nic.in"
    assert canonical_email(billing) == billing
    assert canonical_email("  A.Sharma@NIFT.ac.in  ") == "a.sharma@nift.ac.in"
    # Not a Gmail domain merely because it ends in one. `partition` splits on the FIRST `@`, so a
    # string with two of them has a domain that is not in the set and is returned untouched rather
    # than folded into somebody's real mailbox.
    assert canonical_email("a.b@notgmail.com") == "a.b@notgmail.com"
    assert canonical_email("a.b@c@gmail.com") == "a.b@c@gmail.com"


def test_nothing_canonicalises_to_nothing():
    """An absent address answers the empty string, and produces NO match keys at all.

    Every caller tests the keys for emptiness before querying, and the reason is the shape of the
    query rather than tidiness: ``{"email": {"in": []}}`` matches no rows on Postgres but
    ``{"email": ""}`` is a real lookup for a real value, and a roster row that somehow held an empty
    string would then be matched by every caller with no email at all. The refusal happens before
    the query, not in it.
    """
    assert canonical_email(None) == ""
    assert canonical_email("") == ""
    assert canonical_email("   ") == ""
    assert email_match_keys(None) == []
    assert email_match_keys("  ") == []
    # An address whose local part is nothing BUT dots and a tag would reduce to a bare "@gmail.com",
    # which is not a mailbox and which two different unusable strings would then share. It is handed
    # back unchanged instead — one dead key in an IN list that matches nothing is harmless; a shared
    # one is the merge the domain restriction above exists to prevent, arrived at from the far end.
    assert canonical_email("...@gmail.com") == "...@gmail.com"
    assert canonical_email("+tag@gmail.com") == "+tag@gmail.com"


def test_the_match_keys_are_the_literal_first_and_the_mailbox_second():
    """ONE INDEXED ``IN``, AND THE LITERAL SPELLING IS ALWAYS IN IT.

    Every roster row written before this change is stored under whatever an admin typed. A gate
    that canonicalised only the incoming address would look up ``sandycraft3@gmail.com`` and stop
    finding the ``sandy.craft3@gmail.com`` row that admits that person TODAY — turning a fix for one
    lock-out into a fresh lock-out for everybody the old spelling was quietly working for. The
    literal form is not belt-and-braces; deleting it as redundant breaks sign-in for exactly the
    people this feature was written for.

    One key where there is nothing to canonicalise, so the ordinary address plans as an equality
    test and the query does not get wider for the 99% of rows that were never aliased.
    """
    assert email_match_keys("sandy.craft3@gmail.com") == [
        "sandy.craft3@gmail.com",
        "sandycraft3@gmail.com",
    ]
    assert email_match_keys("sandycraft3@gmail.com") == ["sandycraft3@gmail.com"]
    assert email_match_keys("a.sharma@nift.ac.in") == ["a.sharma@nift.ac.in"]
    # At most two keys, ever — which is what bounds the collision every caller has to resolve to a
    # choice between two rows, given that `email` is UNIQUE on both roster tables.
    assert len(email_match_keys("s.a.n.d.y+x@googlemail.com")) == 2


async def test_an_aliased_sign_in_reaches_the_row_stored_as_the_mailbox(world, client, monkeypatch):
    """**THE CASE THE WHOLE FIX IS FOR**, end to end, through the only door a designer has.

    The rows say ``rostergCanonical…@gmail.com``. The token says
    ``roster.gCanonical.…@googlemail.com``. Before the canonicalisation those were two people: the
    allow-list answered "never seen" and the person was queued as a stranger, or — once an admin had
    promoted them — told their designer access was suspended, about an empanelment sitting active on
    the very screen the admin was looking at.

    THE ``firstSeenAt`` ASSERTION IS THE HALF THAT IS EASY TO LEAVE OUT AND IS NOT DECORATION. A
    match on the canonical key has to behave IDENTICALLY to a match on the literal one, and "let
    them in" is only the first half of that: ``mark_roster_seen`` runs a moment later and writes
    against the same key. Had it kept matching the literal address only, this designer would sign in
    perfectly well for ever while the admin who empanelled them read a permanently blank "first
    seen", concluded the invitation never arrived, and chased them about it.
    """
    response = _google(client, monkeypatch, world["alias"]("gCanonical"))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "DESIGNER"

    row = _roster_rows(client, world)[world["mailbox"]("gCanonical")]
    assert row["firstSeenAt"] is not None, (
        "the gate admitted this designer on the canonical key and then stamped nothing, so the "
        "roster screen will report an invitation that never arrived for somebody who is using the "
        "app right now"
    )
    # NOT A SECOND ROSTER ROW. `ensure_empanelled` runs on every DESIGNER sign-in and searches both
    # spellings, so it finds the one above and writes nothing — the alternative being that every
    # aliased sign-in quietly manufactures the duplicate this whole change exists to prevent.
    assert world["alias"]("gCanonical") not in _roster_rows(client, world)
    # OBSERVED, NOT ENDORSED: the `User` row is created under the address GOOGLE SENT, because
    # account identity is deliberately outside this change. See the note at the
    # `db.user.find_unique` in `login_with_google` for the one case in which that still bites.
    assert response.json()["user"]["email"] == world["alias"]("gCanonical")


async def test_a_row_written_before_canonicalisation_still_admits_its_own_spelling(
    world, client, monkeypatch
):
    """**THE BACKWARDS-COMPATIBILITY CASE, AND THE ONE A "SIMPLIFICATION" WOULD BREAK.**

    ``gLegacy``'s two rows are stored under the DOTTED, ``googlemail.com`` spelling — which is
    exactly how every row an admin typed before this change is stored, because nothing rewrote them.
    The person signs in with that same spelling and must be admitted, and what admits them is the
    LITERAL form in the match keys, not the canonical one.

    Delete the literal key as redundant — it is the obvious tidy-up, since the canonical form "is"
    the address — and this designer, along with everybody else whose row predates the fix, stops
    being able to sign in. That failure would arrive with no schema change and no error anywhere: a
    lookup that simply returns None.
    """
    response = _google(client, monkeypatch, world["alias"]("gLegacy"))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "DESIGNER"

    rows = _roster_rows(client, world)
    assert rows[world["alias"]("gLegacy")]["firstSeenAt"] is not None
    assert world["mailbox"]("gLegacy") not in rows, (
        "signing in created a canonical twin of a row that was already admitting this person, so "
        "the roster screen now shows one designer twice and a later suspension can miss one of them"
    )


async def test_a_revocation_is_not_walked_around_by_a_second_spelling(world, client, monkeypatch):
    """**WHERE TWO SPELLINGS OF ONE MAILBOX DISAGREE, THE REFUSAL WINS.**

    ``gCollision`` has an ACTIVE roster row under the canonical spelling and an administrator's
    SUSPENSION under the alias — a pair no current write path can produce, and one the table may
    already be holding from before there was anything to stop it. Now that both rows answer one
    lookup, something has to decide, and the two possible answers are not symmetrical mistakes:

    * refusing somebody who is entitled to be here is visible, complainable, and fixed in five
      minutes by the same admin on the same screen;
    * admitting somebody an admin revoked is silent, and the only trace of it is a row nobody has
      a reason to open.

    So ``roster_allows`` requires EVERY matched row to be active, and the suspension sentence is the
    one this person reads — which is also the correct sentence, because a suspension is precisely
    what happened to them.
    """
    response = _google(client, monkeypatch, world["alias"]("gCollision"))
    assert response.status_code == 403, response.text
    assert response.json()["detail"] == SUSPENDED_DETAIL
    # The EMPANELMENT's refusal and not the allow-list's. The allow-list admits this address (its
    # row is ACTIVE), so a `SUSPENDED` here would mean the two gates had been collapsed into one and
    # the person was being told to ask about the wrong thing.
    assert response.headers.get("X-Access-Status") == "DESIGNER_SUSPENDED"

    rows = _roster_rows(client, world)
    assert rows[world["alias"]("gCollision")]["isActive"] is False, (
        "the refused sign-in revived the revocation it was refused by"
    )
    assert rows[world["mailbox"]("gCollision")]["isActive"] is True


async def test_admitting_an_aliased_address_stores_the_mailbox_on_both_rosters(world, client):
    """A ROW WRITTEN THROUGH THE ADMIN SCREEN IS STORED AS THE MAILBOX, ON BOTH TABLES AT ONCE.

    This is the write half of the fix, and it is what stops the problem being re-created daily.
    Matching on lookup rescues the rows that are already there; storing the canonical form is what
    means the next row an admin types cannot be unmatchable in the first place — whichever of the
    two spellings the person's Google account turns out to use.

    BOTH ROSTERS, from ONE request, which is the part worth pinning: ``POST /access/roster`` admits
    the address and ``_empanel_an_admitted_designer`` empanels them on the strength of it, so an
    admin who typed one aliased address into one box could otherwise end up with a canonical
    allow-list row and a dotted roster row — the two gates keyed differently for one person, which
    is a worse state than the one this started from.
    """
    typed = f"new.admit.{world['stamp']}@googlemail.com"
    stored = f"newadmit{world['stamp']}@gmail.com"
    assert canonical_email(typed) == stored

    response = client.post(
        "/api/access/roster",
        json={"email": typed, "role": "DESIGNER"},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 201, response.text
    assert response.json()["email"] == stored

    rows = _roster_rows(client, world)
    assert stored in rows, "the approval empanelled nobody under the address the gate will read"
    assert typed not in rows


async def test_adding_a_gmail_equivalent_address_is_a_409_that_says_so(world, client):
    """THE DUPLICATE THAT DOES NOT LOOK LIKE ONE, AND A REFUSAL AN ADMIN CAN ACT ON.

    The admin is looking at their own screen, searching for the address they just typed, and not
    finding it — so "already on the access roster" reads as the server being wrong. The sentence
    therefore names BOTH spellings and says why they are one address. Without the 409 the write
    below would reach ``admit``, which searches both spellings, find the existing row and OVERWRITE
    it: a pending request silently replaced by an ACTIVE grant, or an admin's notes replaced by
    nothing, through the one door that exists to prevent exactly that.
    """
    first = f"clash{world['stamp']}@gmail.com"
    # The stamp sits BEFORE the `+` deliberately: a tag is cut at the first `+`, so a stamp inside
    # the tag would be thrown away and this address would collide with every other run's.
    again = f"cl.ash{world['stamp']}+desk@googlemail.com"
    assert canonical_email(again) == first

    created = client.post(
        "/api/access/roster",
        json={"email": first, "role": "DESIGNER"},
        headers=_headers(world, "admin"),
    )
    assert created.status_code == 201, created.text

    clash = client.post(
        "/api/access/roster",
        json={"email": again, "role": "DESIGNER"},
        headers=_headers(world, "admin"),
    )
    assert clash.status_code == 409, clash.text
    detail = clash.json()["detail"]
    assert first in detail and again in detail, (
        f"the refusal named neither what the admin typed nor what is stored: {detail}"
    )
