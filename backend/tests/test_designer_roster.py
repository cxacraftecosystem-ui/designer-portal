"""The sign-in gate, the profile's ownership rules, and the prefill a new workshop starts with.

Four things are pinned here, and every one of them is a way somebody loses their access or loses
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
# unlisted are two distinct ways of being refused and both are tested: one designer had access
# ended, the other never had a row at all (an account that predates the roster), and the person
# reading the refusal has to be told the same actionable thing either way.
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
        await db.designerprofile.create(data={
            "user": {"connect": {"id": people["active"].id}},
            "displayName": PROFILE_NAME,
            "institution": PROFILE_INSTITUTION,
            "biography": PROFILE_BIOGRAPHY,
            "experienceYears": PROFILE_YEARS,
        })
        # A PROFILE ON THE **ADMIN**, WHICH ARRIVED WITH THE CREATE RULE AND IS NOT DECORATION.
        #
        # ``seed_designer_prefill`` copies THE CREATOR's profile into stage 1 and stage 3. Only
        # admins and the master admin may start a workshop now, so the creator is always an admin
        # and the prefill is only reachable at all through an admin who has a profile — which is an
        # ordinary thing to be here, since ADMIN is inside ``DESIGN_WORKSHOP_ROLES`` precisely so
        # that admins can run workshops of their own.
        #
        # Its values are DIFFERENT from the designer's on purpose. The four prefill tests below can
        # then say which profile actually landed in the stage, and
        # ``test_a_workshop_an_admin_opens_for_a_designer_carries_the_ADMINS_details`` — the one
        # that records what this change costs — would be unable to fail if both profiles read the
        # same.
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
        yield {"client": client, "people": people, "address": address, "stamp": stamp}


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


async def test_a_designer_who_was_never_empanelled_is_refused_the_same_way(world, client):
    """No row at all gets the same answer as a suspended row.

    This is the account that predates the roster, or the one an admin created by hand and forgot
    to empanel. Two different internal states, one thing the designer can usefully be told.
    """
    response = _login(client, world["address"]("unlisted"))
    assert response.status_code == 403, response.text
    assert response.json()["detail"] == SUSPENDED_DETAIL


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


async def test_a_designer_may_write_their_own_profile(world, client):
    response = client.put(
        "/api/designers/me/profile",
        json={"institution": "NID Ahmedabad", "biography": "Bamboo and cane, Assam."},
        headers=_headers(world, "colleague"),
    )
    assert response.status_code == 200, response.text
    assert response.json()["institution"] == "NID Ahmedabad"


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
    designer often does not have to hand. Absent keys must leave the stored value alone: the
    Android profile screen renders a subset of these twenty fields, and a PUT that treated absent
    as "clear" would erase what the designer entered on the web the week before.
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
        "an admin correcting one field must not blank the nineteen the designer typed"
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
    """``create(creator_slug, title, grant_to=None)`` -> the id of a workshop that account opened.

    THE PARAMETER IS THE CREATOR AND IT MUST NOW BE AN ADMIN. This fixture used to be handed a
    designer slug, because designers opened their own workshops; only admins and the master admin
    may start one now (``can_create_design_workshops``), so a designer slug answers 403. The slug is
    still a parameter rather than hard-coded because WHICH admin matters to the prefill: the
    creator's profile is what ``seed_designer_prefill`` copies, so "an admin with a profile" and "an
    admin without one" are two different fixtures' worth of behaviour and both are asserted below.

    ``grant_to`` names the designer who will work in the workshop, and is what lets the tests read
    the seeded stages back as that designer through the ordinary endpoint — which is the whole point
    of reading them that way. It is optional because the prefill can also be inspected by the admin
    who created it, and a grant there would be noise.
    """

    def create(creator_slug: str, title: str, grant_to: str | None = None) -> str:
        response = client.post(
            "/api/design-workshops", json={"title": title}, headers=_headers(world, creator_slug)
        )
        assert response.status_code == 201, response.text
        workshop_id = response.json()["id"]
        if grant_to:
            granted = client.put(
                f"/api/design-workshops/{workshop_id}/viewers",
                json={"userIds": [world["people"][grant_to].id]},
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
    admins and the master admin may start a workshop now, so the account whose profile
    ``seed_designer_prefill`` copies is the ADMIN who opened it. The mechanism is unchanged and is
    what this test pins: the profile of whoever created the workshop lands in stage 1 and stage 3
    as ordinary stage data. WHOSE profile that is has changed, and the cost of that is pinned
    separately and deliberately by
    ``test_a_workshop_an_admin_opens_for_a_designer_carries_the_ADMINS_details``.
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


async def test_a_workshop_an_admin_opens_for_a_designer_carries_the_ADMINS_details(
    world, client, workshop_of
):
    """WHAT THE CREATE RULE COSTS, WRITTEN DOWN AS AN ASSERTION RATHER THAN LEFT TO BE DISCOVERED.

    This test does not describe behaviour anybody wanted. It describes behaviour that FOLLOWS from
    two correct decisions meeting: ``seed_designer_prefill`` copies the CREATOR's profile (right,
    and the copy semantics matter — see the historical-document test below), and only admins may
    create a workshop (right, and the reason is in ``deps.can_create_design_workshops``). Together
    they mean that a workshop an admin opens FOR a designer arrives carrying the ADMIN's name,
    institution and biography in stage 1 and stage 3 — and ``designerName`` is a promoted column, so
    it is also what the workshop LIST and the report cover show until somebody corrects it.

    It is recoverable and it is visible: stage 1 is editable, the designer sees a name that is not
    theirs the first time they open it, and generating a report enforces the Basic-tier stage-1
    fields. It is still wrong, and a report going to a ministry under the wrong designer's name is
    exactly the kind of wrong this repository writes tests about.

    THE FIX, WHICH IS DELIBERATELY NOT IN THIS CHANGE because it needs files this lane does not own:
    let the admin name the workshop's designer on the create — a ``designerUserId`` on
    ``DesignWorkshopCreate`` — and seed the prefill from THAT account instead of from
    ``current_user``, granting them a ``DesignWorkshopViewer`` row in the same call. That turns the
    two steps an admin must perform today into one and makes the prefill right again.

    WHEN THAT LANDS, THIS TEST MUST BE REWRITTEN, NOT DELETED — it becomes "the workshop carries the
    named designer's details", which is the assertion worth having.
    """
    workshop_id = workshop_of("admin", "Opened for a designer", grant_to="active")
    stage_1 = _singleton(client, world, "active", workshop_id, STAGE_1)

    assert stage_1["designerName"] == ADMIN_PROFILE_NAME
    assert stage_1["designerName"] != PROFILE_NAME, (
        "the designer who will run this workshop is 'active', and their name is NOT what was seeded"
    )
    header = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "active")
    )
    assert header.status_code == 200, header.text
    assert header.json()["designerName"] == ADMIN_PROFILE_NAME, (
        "and it reaches the promoted column, so it is what the list and the report cover show"
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
    (``barren``), for the reason the fixture above gives: the prefill copies the CREATOR's profile
    and only admins create workshops, so "the creator has no profile" is the case this is about and
    a designer cannot express it any more. ``adminRostered`` has a roster row and no
    ``DesignerProfile``, which is exactly the shape needed — and it is emphatically NOT the
    ``admin`` account, which was given a profile precisely so the prefill could be exercised.

    This is also the ORDINARY case in the field now: most admins are administrators and have no
    designer profile at all, so most workshops will open with an empty designer block and the
    designer will type their own details into stage 1 — which is what happened before the prefill
    existed, and is the recoverable half of the cost recorded in
    ``test_a_workshop_an_admin_opens_for_a_designer_carries_the_ADMINS_details``.
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

    # The profile edited is the CREATOR's, because the creator's profile is what the prefill copies.
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
