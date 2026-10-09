"""THE FORCED PASSWORD CHANGE, ENFORCED BY THE SERVER — and the seven routes a flagged account keeps.

Owner's decision of 2026-10-09. ``User.mustChangePassword`` used to be REPORTED and never REFUSED: the
two clients drew the change-password screen, and anybody holding the temporary password and a bearer
token could use the whole API from curl for seven days without replacing it. Now
``deps.refuse_while_password_change_pending`` answers every authenticated route outside one named
allow-list with a **401** (never a 403 — both clients park or delete queued work on a 403), the header
``X-Password-Change-Required: 1`` and the sentence "Choose a new password to continue.".

WHAT IS PINNED, IN TWO HALVES.

DATABASE-FREE, so the gating CI job (which has no database) holds them too:

* the allow-list itself, entry by entry, and that every entry is a route this application publishes;
* the check as a function — refused off the list, through on it, the method part of the entry, a
  ``root_path`` that must not lock anybody out, the configured master admin (and not a second one),
  an account with no password, and that a refused request writes nothing into the usage stitch;
* the second door — ``require_dataset_admin`` — refusing the same way;
* the header named in ``expose_headers``, without which a browser cannot read it — and so is
  ``X-Session-Token``, the header the change hands its new session token back in.

DATABASE-BACKED, through the real application: a flagged account signs in, reads its own account
and the rest of the allow-list, is refused a representative set of routes (a list any account may
read, two writes, a provisioner-only route — refused with the 401 BEFORE its own 403 — and the
dataset API on either credential), the browser can read the header, the configured master admin and
a password-less account are not held while a second master admin is, and changing the password
releases the account on a new token — in the ``X-Session-Token`` header, beside a body that is
exactly the ``{"ok": true}`` shipped handsets decode, and readable by a browser on the web app's
origin.

AND THE PASSWORD A SESSION WAS OPENED WITH (2026-10-09). Every token the application mints carries
the fingerprint of the account's password (``security.CREDENTIAL_CLAIM``), and
``deps._user_from_bearer`` refuses it once the password is another one. Pinned without a database —
the check itself, a pre-release token without the claim accepted exactly as before, a claim this code
could not have written, and every mint in ``app/`` passing it — and through the application: a
session opened with a temporary password ends when it is replaced while the token the change hands
back works, a voluntary change ends the other sessions too, a sign-in that raced a reset is refused
on first use, a Google session lasts while nothing changes, and a dataset token is retired for good
by a password change or a raised flag.

    docker compose up -d postgres
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

import ast
import asyncio
import time
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from conftest import needs_db
from fastapi import HTTPException
from fastapi.security import HTTPAuthorizationCredentials
from jose import jwt
from starlette.requests import HTTPConnection

from app.api.routes import auth as auth_routes
from app.core import deps
from app.core.db import db
from app.core.security import CREDENTIAL_CLAIM, create_access_token, hash_password
from app.services import usage

HEADER = "X-Password-Change-Required"
DETAIL = "Choose a new password to continue."
#: Where the change hands the new session token back. Written out, for the reason the sentences
#: are: the clients read this exact name, and a test importing the constant agrees with a rename.
TOKEN_HEADER = "X-Session-Token"
#: The refusal for an ended session, written out rather than imported for the reason the forced
#: change's own sentence is: a test that imports the constant agrees with any rewording of it.
SESSION_ENDED = "This session is no longer valid. Sign in again."

#: The allow-list, written out as the decision rather than read back from the module under test.
EXPECTED_ALLOW_LIST = frozenset(
    {
        ("GET", "/api/me"),
        ("GET", "/api/auth/me"),
        ("POST", "/api/auth/change-password"),
        ("POST", "/api/auth/logout"),
        ("GET", "/api/usage/consent"),
        ("POST", "/api/usage/consent"),
        ("GET", "/api/app/release/latest"),
    }
)

PASSWORD = "enforcement-original-password"
NEW_PASSWORD = "enforcement-chosen-password"


# ==================================================================================================
# Database-free
# ==================================================================================================


def test_the_allow_list_is_exactly_what_the_change_password_screen_needs():
    assert deps.PASSWORD_CHANGE_ALLOWED_ROUTES == EXPECTED_ALLOW_LIST
    assert deps.PASSWORD_CHANGE_REQUIRED_HEADER == HEADER
    assert deps.PASSWORD_CHANGE_REQUIRED_DETAIL == DETAIL


def test_every_allow_listed_route_is_one_this_application_publishes():
    """An entry for a path nothing serves is dead text that reads as a decision — and a renamed route
    would quietly fall OFF the list, locking flagged accounts out of the screen that releases them."""
    import app.services.stage_definitions  # noqa: F401  - installs the registry the router imports
    from app.main import app as application

    paths = application.openapi()["paths"]
    missing = [
        f"{method} {path}"
        for method, path in sorted(deps.PASSWORD_CHANGE_ALLOWED_ROUTES)
        if method.lower() not in paths.get(path, {})
    ]
    assert not missing, f"allow-listed but not published: {missing}"


def test_browsers_are_allowed_to_read_the_header():
    """Without this a browser sees a bare 401 and signs the person out — of the very session they
    need in order to choose a new password. Read off the middleware the app registered."""
    from app.main import app as application

    middleware = application.user_middleware
    cors = [entry for entry in middleware if entry.cls.__name__ == "CORSMiddleware"]
    assert cors, "no CORS middleware is registered"
    assert HEADER in cors[0].kwargs["expose_headers"]


def test_the_new_session_token_rides_in_a_header_a_browser_may_read():
    """The change's body stays exactly ``{"ok": true}`` — Android builds 0.0.6 to 0.0.15 decode it
    as ``Map<String, Boolean>`` and break on a second field — so the token minted after the write
    travels in ``X-Session-Token``. Hidden from a cross-origin script, the web would never see it
    and would keep the token the change has just retired. Read off the middleware the app
    registered."""
    from app.main import app as application

    assert auth_routes.SESSION_TOKEN_HEADER == TOKEN_HEADER
    cors = [
        entry for entry in application.user_middleware if entry.cls.__name__ == "CORSMiddleware"
    ]
    assert cors, "no CORS middleware is registered"
    assert TOKEN_HEADER in cors[0].kwargs["expose_headers"]


def _connection(method: str, path: str, *, root_path: str = "") -> HTTPConnection:
    scope: dict[str, Any] = {
        "type": "http",
        "method": method,
        "path": path,
        "root_path": root_path,
        "headers": [],
    }
    return HTTPConnection(scope)


def _flagged(**fields: Any) -> SimpleNamespace:
    base = {
        "id": "u-flagged",
        "email": "flagged@example.test",
        "role": "RESEARCHER",
        "mustChangePassword": True,
        "passwordHash": "$2b$12$somebodyelsechoseit",
    }
    base.update(fields)
    return SimpleNamespace(**base)


@pytest.fixture
def account(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    """Replace the token check with one that hands back whatever account the test puts here."""
    holder: dict[str, Any] = {"user": None}

    async def _user(credentials: Any, *, allowed_scopes: Any) -> Any:
        return holder["user"]

    monkeypatch.setattr(deps, "_user_from_bearer", _user)
    return holder


async def _refusal(method: str, path: str, **kwargs: Any) -> HTTPException:
    with pytest.raises(HTTPException) as refused:
        await deps.get_current_user(_connection(method, path, **kwargs), credentials=None)
    return refused.value


async def test_a_flagged_account_is_refused_with_the_status_the_header_and_the_sentence(account):
    account["user"] = _flagged()
    for method, path in (
        ("GET", "/api/design-workshops"),
        ("POST", "/api/questionnaires"),
        ("PATCH", "/api/users/u-other"),
        ("DELETE", "/api/artisans/a-1"),
    ):
        refused = await _refusal(method, path)
        assert refused.status_code == 401, (method, path)
        assert refused.headers == {HEADER: "1"}
        assert refused.detail == DETAIL


async def test_every_allow_listed_route_lets_a_flagged_account_through(account):
    account["user"] = _flagged()
    for method, path in sorted(EXPECTED_ALLOW_LIST):
        user = await deps.get_current_user(_connection(method, path), credentials=None)
        assert user is account["user"], (method, path)


async def test_the_method_is_part_of_the_entry(account):
    account["user"] = _flagged()
    assert (await _refusal("GET", "/api/auth/change-password")).status_code == 401
    assert (await _refusal("DELETE", "/api/me")).status_code == 401
    # A lower-case method is still the same method.
    await deps.get_current_user(_connection("get", "/api/me"), credentials=None)


async def test_a_root_path_does_not_lock_anybody_out_of_the_allow_list(account):
    account["user"] = _flagged()
    await deps.get_current_user(
        _connection("GET", "/backend/api/me", root_path="/backend"), credentials=None
    )
    refused = await _refusal("GET", "/backend/api/users", root_path="/backend")
    assert refused.status_code == 401


async def test_only_the_configured_master_admin_is_exempt(account, monkeypatch):
    """The owner's decision names the account at ``MASTER_ADMIN_EMAIL``, not the role.

    So the configured address is never held, whatever role its row carries and however its address
    is cased — and a SECOND master admin holding a temporary password is held like anybody else. That
    is deliberately narrower than ``is_break_glass_master``, which the allow-list still uses. An
    unset ``MASTER_ADMIN_EMAIL`` exempts nobody, not an account with an empty address.
    """
    configured = "break-glass@example.test"
    monkeypatch.setattr(deps.get_settings(), "master_admin_email", configured)

    for role, email in (("MASTER_ADMIN", configured), ("ADMIN", configured.upper())):
        account["user"] = _flagged(role=role, email=email)
        await deps.get_current_user(_connection("GET", "/api/users"), credentials=None)

    account["user"] = _flagged(role="MASTER_ADMIN", email="deputy@example.test")
    assert deps.is_break_glass_master(account["user"]), "the allow-list's exemption is unchanged"
    refused = await _refusal("GET", "/api/users")
    assert refused.status_code == 401
    assert refused.headers == {HEADER: "1"}

    monkeypatch.setattr(deps.get_settings(), "master_admin_email", "")
    account["user"] = _flagged(email="")
    assert (await _refusal("GET", "/api/users")).status_code == 401


async def test_only_a_flagged_account_with_a_password_is_held(account):
    """No flag, no hold. A flag on an account with NO password is not held either: change-password
    answers it with a 400, so enforcing the flag would strand it behind a gate it can never pass."""
    for user in (
        _flagged(mustChangePassword=False),
        _flagged(passwordHash=None),
        SimpleNamespace(id="u-bare", role="RESEARCHER"),
    ):
        account["user"] = user
        assert await deps.get_current_user(_connection("GET", "/api/users"), credentials=None)


async def test_a_refused_request_attributes_nothing_in_the_usage_stitch(account):
    """The check runs BEFORE the stitch, so a refused request is recorded the way every other
    refused credential is — with no account attached."""
    account["user"] = _flagged()
    scope: dict[str, Any] = {"type": "http", "method": "GET", "path": "/api/users", "headers": []}
    with pytest.raises(HTTPException):
        await deps.get_current_user(HTTPConnection(scope), credentials=None)
    assert not scope.get("state"), scope.get("state")


async def test_the_dataset_api_refuses_a_flagged_account_too(account):
    """``require_dataset_admin`` reads a bearer token without ``get_current_user``, so it calls the
    same check — on a session token and on a ``dataset:read`` token alike."""
    account["user"] = _flagged(role="ADMIN")
    with pytest.raises(HTTPException) as refused:
        await deps.require_dataset_admin(_connection("GET", "/api/datasets"), credentials=None)
    assert refused.value.status_code == 401
    assert refused.value.headers == {HEADER: "1"}


# --------------------------------------------------------------------------------------------------
# The password a session was opened with
# --------------------------------------------------------------------------------------------------


@pytest.fixture
def bound_row(monkeypatch: pytest.MonkeyPatch) -> SimpleNamespace:
    """One account, handed to ``_user_from_bearer`` by id — the row the identity cache would return,
    which the test then changes the way a password write would."""
    row = SimpleNamespace(
        id="u-bound",
        email="bound@example.test",
        role="RESEARCHER",
        passwordHash="$2b$12$the-first-password-hash",
        sessionsValidFrom=None,
    )

    async def _resolve(user_id: str) -> Any:
        return row if user_id == row.id else None

    monkeypatch.setattr(deps, "resolve_user", _resolve)
    return row


async def _authenticate(token: str) -> Any:
    return await deps._user_from_bearer(  # noqa: SLF001 - the check under test
        HTTPAuthorizationCredentials(scheme="Bearer", credentials=token),
        allowed_scopes=frozenset(),
    )


async def test_a_token_ends_when_the_password_it_was_opened_with_does(bound_row):
    """THE BINDING. A token minted against one password is refused once the account holds another —
    with the watermark's sentence and NO forced-change header, because this is an ended session and
    not a hold: the client signs in again rather than drawing the change screen."""
    token = create_access_token(
        subject=bound_row.id, credential=deps.password_credential(bound_row)
    )
    assert await _authenticate(token) is bound_row

    bound_row.passwordHash = "$2b$12$the-second-password-hash"
    with pytest.raises(HTTPException) as ended:
        await _authenticate(token)
    assert ended.value.status_code == 401
    assert ended.value.detail == SESSION_ENDED
    assert not ended.value.headers, "an ended session must not read as a pending password change"

    fresh = create_access_token(
        subject=bound_row.id, credential=deps.password_credential(bound_row)
    )
    assert await _authenticate(fresh) is bound_row, "a token opened with the new password is not"


async def test_having_no_password_is_a_credential_too(bound_row):
    """A Google-only account's session is bound to "no password", so the day a provisioner gives it
    one, the sessions opened without it end like any other."""
    bound_row.passwordHash = None
    token = create_access_token(
        subject=bound_row.id, credential=deps.password_credential(bound_row)
    )
    assert await _authenticate(token) is bound_row
    bound_row.passwordHash = "$2b$12$a-password-set-later"
    with pytest.raises(HTTPException):
        await _authenticate(token)


async def test_a_token_from_before_the_binding_is_accepted_exactly_as_it_was(bound_row):
    """THE OWNER'S RULING: a token with no claim — every token minted before 2026-10-09 — is
    accepted as today, so nobody is signed out at the deploy. It is the one kind a password change
    does not retire; it expires on its own."""
    legacy = create_access_token(subject=bound_row.id)
    bound_row.passwordHash = "$2b$12$changed-after-the-token"
    assert await _authenticate(legacy) is bound_row


@pytest.mark.parametrize("forged", [None, 42, "", "0" * 16, "é" * 16])
async def test_a_credential_claim_this_code_could_not_have_written_is_refused(bound_row, forged):
    """Only this code signs tokens, and it writes the claim as a sixteen-character hex digest. A claim
    that is present and anything else — null, a number, a string no fingerprint equals, a non-ASCII
    one ``hmac.compare_digest`` would choke on — is not one of ours, and PRESENT is not ABSENT."""
    settings = deps.get_settings()
    now = datetime.now(UTC)
    token = jwt.encode(
        {
            "sub": bound_row.id,
            "iat": int(now.timestamp()),
            "exp": now + timedelta(minutes=5),
            CREDENTIAL_CLAIM: forged,
        },
        settings.jwt_secret,
        algorithm=settings.jwt_algorithm,
    )
    with pytest.raises(HTTPException) as refused:
        await _authenticate(token)
    assert refused.value.status_code == 401
    assert refused.value.detail == SESSION_ENDED


def test_the_credential_claim_has_one_way_in():
    """``extra_claims`` may not write it — the keyword is the one way to bind a token, and the source
    pin below holds every mint to it."""
    with pytest.raises(ValueError, match="reserved"):
        create_access_token(subject="u1", extra_claims={CREDENTIAL_CLAIM: "0" * 16})


def test_every_bearer_token_the_application_mints_is_bound_to_a_password():
    """A mint that forgot the claim would hand out sessions no password change can end — accepted as
    "from before the binding" for their whole life. So every ``create_access_token`` call in ``app/``
    passes ``credential=``, and the doors that mint are the two known ones: a third has to be looked
    at, not inherited."""
    app_root = Path(__file__).resolve().parents[1] / "app"
    mints: list[tuple[str, int, set[str]]] = []
    for path in sorted(app_root.rglob("*.py")):
        for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
            if not isinstance(node, ast.Call):
                continue
            func = node.func
            name = getattr(func, "id", None) or getattr(func, "attr", None)
            if name == "create_access_token":
                keywords = {keyword.arg for keyword in node.keywords if keyword.arg}
                mints.append((path.relative_to(app_root).as_posix(), node.lineno, keywords))
    assert mints, "found no mint at all, so the scan is broken"
    unbound = [f"{path}:{line}" for path, line, keywords in mints if "credential" not in keywords]
    assert not unbound, f"bearer tokens minted with no password binding: {unbound}"
    assert {path for path, _line, _keywords in mints} == {
        "api/routes/auth.py",
        "api/routes/datasets.py",
    }


# ==================================================================================================
# Database-backed
# ==================================================================================================

#: slug -> (role, flagged, has a password, provider). The seven after ``admin`` each belong to one
#: of the password-binding tests below, because every one of them changes its account's password.
ACCOUNTS: tuple[tuple[str, str, bool, bool, str], ...] = (
    ("flagged", "RESEARCHER", True, True, "LOCAL"),
    ("changer", "RESEARCHER", True, True, "LOCAL"),
    ("adminFlagged", "ADMIN", True, True, "LOCAL"),
    ("master", "MASTER_ADMIN", True, True, "LOCAL"),
    ("googler", "RESEARCHER", True, False, "GOOGLE"),
    ("admin", "ADMIN", False, True, "LOCAL"),
    ("forced", "RESEARCHER", True, True, "LOCAL"),
    ("voluntary", "RESEARCHER", False, True, "LOCAL"),
    ("racer", "RESEARCHER", False, True, "LOCAL"),
    ("googlePassword", "RESEARCHER", False, True, "LOCAL"),
    ("datasetAdmin", "ADMIN", False, True, "LOCAL"),
    ("masterActor", "MASTER_ADMIN", False, True, "LOCAL"),
    ("browser", "RESEARCHER", False, True, "LOCAL"),
)

#: What the binding tests change passwords to. Distinct per step, so a step that silently wrote
#: nothing fails at the next one rather than passing on the old value.
SECOND_PASSWORD = "enforcement-second-password"
THIRD_PASSWORD = "enforcement-third-password"

#: A representative set of routes a flagged account is refused: a list any signed-in account may
#: read, two writes (one of them the preference save both clients make in the background), a
#: provisioner-only route — refused with this 401 BEFORE its own 403 — and a list of workshops.
REFUSED_PROBES: tuple[tuple[str, str, Any], ...] = (
    ("GET", "/api/users/directory", None),
    ("GET", "/api/design-workshops", None),
    ("POST", "/api/questionnaires", {}),
    ("PUT", "/api/preferences/me", {}),
    ("POST", "/api/users", {}),
)


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        return f"enforce-{slug}-{stamp}@example.org".lower()

    async def seed() -> dict[str, Any]:
        await db.connect()
        try:
            now = datetime.now(UTC)
            people: dict[str, Any] = {}
            for slug, role, flagged, has_password, provider in ACCOUNTS:
                people[slug] = await db.user.create(
                    data={
                        "email": address(slug),
                        "name": f"Enforcement {slug} {stamp}",
                        "role": role,
                        "passwordHash": hash_password(PASSWORD) if has_password else None,
                        "passwordSetAt": now if has_password else None,
                        "mustChangePassword": flagged,
                        "authProvider": provider,
                    }
                )
                await db.accessroster.create(
                    data={
                        "email": address(slug),
                        "status": "ACTIVE",
                        "admitRole": role,
                        "joinedAt": now,
                        "notes": "Seeded by tests/test_password_change_enforcement.py.",
                    }
                )
            return people
        finally:
            await db.disconnect()

    people = asyncio.run(seed())
    with TestClient(app) as client:
        yield {"client": client, "people": people, "address": address}


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any], slug: str, **claims: Any) -> dict[str, str]:
    """A token minted directly, with NO password binding — a pre-release session, which is what
    every test that is not about the binding wants: the gate under test is something else."""
    token = create_access_token(subject=world["people"][slug].id, extra_claims=claims or None)
    return {"Authorization": f"Bearer {token}"}


def _bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _sign_in(client: Any, world: dict[str, Any], slug: str, password: str = PASSWORD) -> str:
    """A session token from the real sign-in door — bound to the password it was opened with."""
    signed_in = client.post(
        "/api/auth/login", json={"email": world["address"](slug), "password": password}
    )
    assert signed_in.status_code == 200, signed_in.text
    return signed_in.json()["accessToken"]


def _change(client: Any, token: str, current: str, new: str) -> Any:
    return client.post(
        "/api/auth/change-password",
        json={"currentPassword": current, "newPassword": new},
        headers=_bearer(token),
    )


def _assert_ended(response: Any) -> None:
    """The refusal of an ended session: 401, the watermark's sentence, and NOT the forced-change
    header — a client must sign in again, not draw the change screen."""
    assert response.status_code == 401, response.text
    assert response.json() == {"detail": SESSION_ENDED}
    assert HEADER not in response.headers


def _handed_back(changed: Any) -> str:
    """The session token a successful change hands back: in ``X-Session-Token``, beside a body that
    is EXACTLY ``{"ok": true}`` — the one Android builds 0.0.6 to 0.0.15 decode as
    ``Map<String, Boolean>``, and break on, the moment it carries a second field."""
    assert changed.status_code == 200, changed.text
    assert changed.json() == {"ok": True}, "the body must stay what the shipped handsets decode"
    token = changed.headers.get(TOKEN_HEADER)
    assert token, f"the change handed back no {TOKEN_HEADER}"
    return token


@needs_db
def test_a_flagged_account_signs_in_and_reads_its_own_account(world, client):
    """The sign-in is NOT refused — the only route that replaces a password needs a session — and
    both mounts of ``/me`` answer, which is how a client learns why everything else is refused."""
    signed_in = client.post(
        "/api/auth/login", json={"email": world["address"]("flagged"), "password": PASSWORD}
    )
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["mustChangePassword"] is True
    token = signed_in.json()["accessToken"]
    for path in ("/api/me", "/api/auth/me"):
        response = client.get(path, headers={"Authorization": f"Bearer {token}"})
        assert response.status_code == 200, f"{path}: {response.text}"
        assert HEADER not in response.headers
        assert response.json()["mustChangePassword"] is True


@needs_db
def test_the_rest_of_the_allow_list_answers_a_flagged_account(world, client):
    headers = _headers(world, "flagged")
    assert client.get("/api/usage/consent", headers=headers).status_code == 200
    recorded = client.post(
        "/api/usage/consent",
        json={
            "decision": "GRANTED",
            "basis": "REQUIRED_AT_SIGN_IN",
            "noticeVersion": usage.NOTICE_VERSION,
        },
        headers=headers,
    )
    assert recorded.status_code == 200, recorded.text
    assert client.get("/api/app/release/latest", headers=headers).status_code == 200
    assert client.post("/api/auth/logout", headers=headers).status_code == 200


@needs_db
@pytest.mark.parametrize(("method", "path", "body"), REFUSED_PROBES)
def test_everything_else_is_a_401_that_says_why(world, client, method, path, body):
    response = client.request(method, path, json=body, headers=_headers(world, "flagged"))
    assert response.status_code == 401, f"{method} {path}: {response.text}"
    assert response.headers.get(HEADER) == "1"
    assert response.json() == {"detail": DETAIL}


@needs_db
def test_the_browser_can_read_the_header(world, client):
    origin = deps.get_settings().cors_origins[0]
    response = client.get(
        "/api/users/directory", headers={**_headers(world, "flagged"), "Origin": origin}
    )
    assert response.status_code == 401
    exposed = response.headers.get("access-control-expose-headers", "")
    assert HEADER.lower() in exposed.lower(), exposed


@needs_db
def test_the_dataset_api_refuses_a_flagged_admin_on_either_credential(world, client):
    session = client.get("/api/datasets", headers=_headers(world, "adminFlagged"))
    assert session.status_code == 401, session.text
    assert session.headers.get(HEADER) == "1"

    # A dataset token held by a flagged account is refused on USE, not only at the mint. Held — this
    # header, released once the password is chosen — because it carries no password binding and
    # nothing stamped a watermark after it: the shape of a token minted before 2026-10-09 while the
    # flag was already up. A token minted BEFORE a flag is raised is retired instead; see
    # ``test_a_dataset_token_is_retired_for_good_by_a_new_password_or_a_raised_flag``.
    scoped = client.get(
        "/api/datasets", headers=_headers(world, "adminFlagged", scope=deps.DATASET_READ_SCOPE)
    )
    assert scoped.status_code == 401, scoped.text
    assert scoped.headers.get(HEADER) == "1"

    # The control: the same request from an unflagged admin is answered.
    assert client.get("/api/datasets", headers=_headers(world, "admin")).status_code == 200


@needs_db
def test_the_configured_master_admin_and_a_password_less_account_are_not_held(world, client):
    """A password-less account has nothing to replace. A master admin is held like anybody else
    until ``MASTER_ADMIN_EMAIL`` names it — pointed at this run's stamped account for one request,
    rather than the real master admin's row being flagged on a shared development database."""
    password_less = client.get("/api/users/directory", headers=_headers(world, "googler"))
    assert password_less.status_code == 200, password_less.text

    deputy = client.get("/api/users/directory", headers=_headers(world, "master"))
    assert deputy.status_code == 401, deputy.text
    assert deputy.headers.get(HEADER) == "1"

    settings = deps.get_settings()
    previous = settings.master_admin_email
    settings.master_admin_email = world["address"]("master")
    try:
        configured = client.get("/api/users/directory", headers=_headers(world, "master"))
    finally:
        settings.master_admin_email = previous
    assert configured.status_code == 200, configured.text


@needs_db
def test_changing_the_password_releases_the_account_and_hands_back_a_session(world, client):
    """The way out works, and the person is not signed out on the way: the answer carries a session
    token minted after the write — in its ``X-Session-Token`` header, the body staying exactly
    ``{"ok": true}`` — and that token reaches what the flag was holding back.

    The token used here carries no password binding — a pre-release session — so it is RELEASED by
    the change, exactly as every session was before 2026-10-09; that is the owner's ruling for those
    tokens, pinned so it cannot change by accident. A session opened through today's sign-in ENDS
    with the password it was opened with:
    ``test_a_session_opened_with_a_temporary_password_ends_when_it_is_replaced``.
    """
    headers = _headers(world, "changer")
    assert client.get("/api/users/directory", headers=headers).status_code == 401
    changed = client.post(
        "/api/auth/change-password",
        json={"currentPassword": PASSWORD, "newPassword": NEW_PASSWORD},
        headers=headers,
    )
    fresh = _bearer(_handed_back(changed))
    released = client.get("/api/users/directory", headers=fresh)
    assert released.status_code == 200, released.text
    assert client.get("/api/me", headers=fresh).json()["mustChangePassword"] is False
    assert client.get("/api/users/directory", headers=headers).status_code == 200, (
        "a token from before the password binding must be accepted exactly as it was"
    )


@needs_db
def test_a_session_opened_with_a_temporary_password_ends_when_it_is_replaced(world, client):
    """THE FINDING. A temporary password goes out over a chat; whoever holds it can open a session
    with it. That session used to be RELEASED by the owner's forced change — the hold was the
    account's, and the change cleared it — and ran with full access for up to seven days. Every
    session opened with the old password now ends with it, the one that made the change included;
    the change hands its caller a new one."""
    first = _sign_in(client, world, "forced")
    second = _sign_in(client, world, "forced")
    for token in (first, second):
        held = client.get("/api/users/directory", headers=_bearer(token))
        assert held.status_code == 401, held.text
        assert held.headers.get(HEADER) == "1", "before the change, both are held — not ended"

    changed = _change(client, second, PASSWORD, SECOND_PASSWORD)
    fresh = _bearer(_handed_back(changed))
    for token in (first, second):
        _assert_ended(client.get("/api/users/directory", headers=_bearer(token)))
        _assert_ended(client.get("/api/me", headers=_bearer(token)))

    assert client.get("/api/users/directory", headers=fresh).status_code == 200
    assert client.get("/api/me", headers=fresh).json()["mustChangePassword"] is False


@needs_db
def test_a_voluntary_change_ends_the_other_sessions_too(world, client):
    """Not only the forced change: ANY new password retires the sessions opened with the old one —
    the phone left signed in on a bus, as well as the laptop the change was made from, whose own
    token carries on only as the one the change handed back."""
    phone = _sign_in(client, world, "voluntary")
    laptop = _sign_in(client, world, "voluntary")
    assert client.get("/api/users/directory", headers=_bearer(phone)).status_code == 200

    changed = _change(client, laptop, PASSWORD, SECOND_PASSWORD)
    fresh = _handed_back(changed)
    _assert_ended(client.get("/api/users/directory", headers=_bearer(phone)))
    _assert_ended(client.get("/api/users/directory", headers=_bearer(laptop)))
    assert client.get("/api/users/directory", headers=_bearer(fresh)).status_code == 200
    assert _sign_in(client, world, "voluntary", SECOND_PASSWORD)


@needs_db
def test_a_browser_may_send_the_change_and_read_the_token_it_hands_back(world, client):
    """From the outside, the way the web app on its own origin meets the route. The PREFLIGHT for a
    POST carrying a bearer token and a JSON body is answered for that origin, and the answer to the
    change itself names ``X-Session-Token`` among the headers its script may read. Without the
    second, every test above passes while the browser keeps the token the change has just retired
    and is signed out on its next request — and the handset, which reads headers freely, is not."""
    origin = deps.get_settings().cors_origins[0]
    preflight = client.options(
        "/api/auth/change-password",
        headers={
            "Origin": origin,
            "Access-Control-Request-Method": "POST",
            "Access-Control-Request-Headers": "authorization, content-type",
        },
    )
    assert preflight.status_code == 200, preflight.text
    assert preflight.headers.get("access-control-allow-origin") in (origin, "*"), preflight.headers

    old = _sign_in(client, world, "browser")
    changed = client.post(
        "/api/auth/change-password",
        json={"currentPassword": PASSWORD, "newPassword": SECOND_PASSWORD},
        headers={**_bearer(old), "Origin": origin},
    )
    fresh = _handed_back(changed)
    exposed = changed.headers.get("access-control-expose-headers", "")
    assert TOKEN_HEADER.lower() in exposed.lower(), exposed
    assert changed.headers.get("access-control-allow-origin") in (origin, "*"), changed.headers
    assert client.get("/api/users/directory", headers=_bearer(fresh)).status_code == 200
    _assert_ended(client.get("/api/users/directory", headers=_bearer(old)))


@needs_db
def test_a_sign_in_that_raced_a_reset_is_refused_on_first_use(world, client):
    """The race the watermark alone could not close. A sign-in that read the account a moment
    before a provisioner's reset committed mints its token AFTER the write, in a later wall second
    than the watermark — so its ``iat`` post-dates the revocation — and with the OLD password's
    fingerprint. That token is built here exactly so: from the row as it stood before the reset,
    minted once the reset has landed and a second has passed."""
    before = world["people"]["racer"]
    reset = client.patch(
        f"/api/users/{before.id}",
        json={"password": SECOND_PASSWORD, "mustChangePassword": False},
        headers=_headers(world, "admin"),
    )
    assert reset.status_code == 200, reset.text

    time.sleep(1.1)  # past the watermark's second, so only the password binding can refuse it
    raced = auth_routes._session_token(before)  # noqa: SLF001 - the sign-in's own mint
    _assert_ended(client.get("/api/users/directory", headers=_bearer(raced)))

    # The control: the same door, after the reset, with the password the account now holds.
    current = _sign_in(client, world, "racer", SECOND_PASSWORD)
    assert client.get("/api/users/directory", headers=_bearer(current)).status_code == 200


@needs_db
def test_a_google_session_lasts_while_the_password_does_not_change(world, client, monkeypatch):
    """The binding ends sessions when the PASSWORD changes and for nothing else: a Google sign-in on
    a password account keeps working through a correction of its name, and a Google-only account —
    bound to "no password" — keeps working too."""
    for slug in ("googlePassword", "googler"):
        email = world["address"](slug)
        monkeypatch.setattr(
            auth_routes,
            "verify_google_token",
            lambda _token, email=email: {
                "email": email,
                "email_verified": True,
                "name": "Google Name",
                "picture": None,
            },
        )
        signed_in = client.post("/api/auth/login", json={"googleIdToken": "stand-in"})
        assert signed_in.status_code == 200, signed_in.text
        token = _bearer(signed_in.json()["accessToken"])
        assert client.get("/api/users/directory", headers=token).status_code == 200

        renamed = client.patch(
            f"/api/users/{world['people'][slug].id}",
            json={"name": f"Renamed {slug}"},
            headers=_headers(world, "admin"),
        )
        assert renamed.status_code == 200, renamed.text
        still = client.get("/api/users/directory", headers=token)
        assert still.status_code == 200, f"{slug}: {still.text}"


@needs_db
def test_a_dataset_token_is_retired_for_good_by_a_new_password_or_a_raised_flag(world, client):
    """What the operator of a nightly job sees, both ways (tests-docs/F6). Neither is a hold: the
    token answers a plain 401 with no forced-change header and does not come back, and the remedy is
    a new token minted with the password the account holds now."""
    email = world["address"]("datasetAdmin")
    account_id = world["people"]["datasetAdmin"].id

    def mint(password: str) -> str:
        minted = client.post("/api/datasets/token", json={"email": email, "password": password})
        assert minted.status_code == 200, minted.text
        return minted.json()["accessToken"]

    # 1. THE OWNER CHANGES THE PASSWORD.
    first = mint(PASSWORD)
    assert client.get("/api/datasets", headers=_bearer(first)).status_code == 200
    changed = _change(client, _sign_in(client, world, "datasetAdmin"), PASSWORD, SECOND_PASSWORD)
    assert changed.status_code == 200, changed.text
    _assert_ended(client.get("/api/datasets", headers=_bearer(first)))

    # 2. AN ADMINISTRATOR RAISES THE FLAG on the password the token was minted with.
    second = mint(SECOND_PASSWORD)
    assert client.get("/api/datasets", headers=_bearer(second)).status_code == 200
    raised = client.patch(
        f"/api/users/{account_id}",
        json={"mustChangePassword": True},
        headers=_headers(world, "masterActor"),
    )
    assert raised.status_code == 200, raised.text
    _assert_ended(client.get("/api/datasets", headers=_bearer(second)))

    # ...and it stays refused once the owner has chosen a password: retired, not held.
    time.sleep(1.1)  # a sign-in in the watermark's own second would be refused with it
    session = _sign_in(client, world, "datasetAdmin", SECOND_PASSWORD)
    rechosen = _change(client, session, SECOND_PASSWORD, THIRD_PASSWORD)
    assert rechosen.status_code == 200, rechosen.text
    _assert_ended(client.get("/api/datasets", headers=_bearer(second)))
    assert client.get("/api/datasets", headers=_bearer(mint(THIRD_PASSWORD))).status_code == 200
