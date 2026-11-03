import ast
import asyncio
import time
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import jwt
import pytest
from conftest import needs_db
from fastapi import HTTPException
from fastapi.security import HTTPAuthorizationCredentials
from starlette.requests import HTTPConnection

from app.api.routes import auth as auth_routes
from app.core import deps
from app.core.db import db
from app.core.security import CREDENTIAL_CLAIM, create_access_token, hash_password
from app.services import usage

HEADER = "X-Password-Change-Required"
DETAIL = "Choose a new password to continue."
TOKEN_HEADER = "X-Session-Token"
SESSION_ENDED = "This session is no longer valid. Sign in again."

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



def test_the_allow_list_is_exactly_what_the_change_password_screen_needs():
    assert deps.PASSWORD_CHANGE_ALLOWED_ROUTES == EXPECTED_ALLOW_LIST
    assert deps.PASSWORD_CHANGE_REQUIRED_HEADER == HEADER
    assert deps.PASSWORD_CHANGE_REQUIRED_DETAIL == DETAIL


def test_every_allow_listed_route_is_one_this_application_publishes():
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
    from app.main import app as application

    middleware = application.user_middleware
    cors = [entry for entry in middleware if entry.cls.__name__ == "CORSMiddleware"]
    assert cors, "no CORS middleware is registered"
    assert HEADER in cors[0].kwargs["expose_headers"]


def test_the_new_session_token_rides_in_a_header_a_browser_may_read():
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
    await deps.get_current_user(_connection("get", "/api/me"), credentials=None)


async def test_a_root_path_does_not_lock_anybody_out_of_the_allow_list(account):
    account["user"] = _flagged()
    await deps.get_current_user(
        _connection("GET", "/backend/api/me", root_path="/backend"), credentials=None
    )
    refused = await _refusal("GET", "/backend/api/users", root_path="/backend")
    assert refused.status_code == 401


async def test_only_the_configured_master_admin_is_exempt(account, monkeypatch):
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
    for user in (
        _flagged(mustChangePassword=False),
        _flagged(passwordHash=None),
        SimpleNamespace(id="u-bare", role="RESEARCHER"),
    ):
        account["user"] = user
        assert await deps.get_current_user(_connection("GET", "/api/users"), credentials=None)


async def test_a_refused_request_attributes_nothing_in_the_usage_stitch(account):
    account["user"] = _flagged()
    scope: dict[str, Any] = {"type": "http", "method": "GET", "path": "/api/users", "headers": []}
    with pytest.raises(HTTPException):
        await deps.get_current_user(HTTPConnection(scope), credentials=None)
    assert not scope.get("state"), scope.get("state")


async def test_the_dataset_api_refuses_a_flagged_account_too(account):
    account["user"] = _flagged(role="ADMIN")
    with pytest.raises(HTTPException) as refused:
        await deps.require_dataset_admin(_connection("GET", "/api/datasets"), credentials=None)
    assert refused.value.status_code == 401
    assert refused.value.headers == {HEADER: "1"}



@pytest.fixture
def bound_row(monkeypatch: pytest.MonkeyPatch) -> SimpleNamespace:
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
    bound_row.passwordHash = None
    token = create_access_token(
        subject=bound_row.id, credential=deps.password_credential(bound_row)
    )
    assert await _authenticate(token) is bound_row
    bound_row.passwordHash = "$2b$12$a-password-set-later"
    with pytest.raises(HTTPException):
        await _authenticate(token)


async def test_a_token_from_before_the_binding_is_accepted_exactly_as_it_was(bound_row):
    legacy = create_access_token(subject=bound_row.id)
    bound_row.passwordHash = "$2b$12$changed-after-the-token"
    assert await _authenticate(legacy) is bound_row


@pytest.mark.parametrize("forged", [None, 42, "", "0" * 16, "é" * 16])
async def test_a_credential_claim_this_code_could_not_have_written_is_refused(bound_row, forged):
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
    with pytest.raises(ValueError, match="reserved"):
        create_access_token(subject="u1", extra_claims={CREDENTIAL_CLAIM: "0" * 16})


def test_every_bearer_token_the_application_mints_is_bound_to_a_password():
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

SECOND_PASSWORD = "enforcement-second-password"
THIRD_PASSWORD = "enforcement-third-password"

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
    token = create_access_token(subject=world["people"][slug].id, extra_claims=claims or None)
    return {"Authorization": f"Bearer {token}"}


def _bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _sign_in(client: Any, world: dict[str, Any], slug: str, password: str = PASSWORD) -> str:
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
    assert response.status_code == 401, response.text
    assert response.json() == {"detail": SESSION_ENDED}
    assert HEADER not in response.headers


def _handed_back(changed: Any) -> str:
    assert changed.status_code == 200, changed.text
    assert changed.json() == {"ok": True}, "the body must stay what the shipped handsets decode"
    token = changed.headers.get(TOKEN_HEADER)
    assert token, f"the change handed back no {TOKEN_HEADER}"
    return token


@needs_db
def test_a_flagged_account_signs_in_and_reads_its_own_account(world, client):
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

    scoped = client.get(
        "/api/datasets", headers=_headers(world, "adminFlagged", scope=deps.DATASET_READ_SCOPE)
    )
    assert scoped.status_code == 401, scoped.text
    assert scoped.headers.get(HEADER) == "1"

    assert client.get("/api/datasets", headers=_headers(world, "admin")).status_code == 200


@needs_db
def test_the_configured_master_admin_and_a_password_less_account_are_not_held(world, client):
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
    headers = _headers(world, "changer")
    assert client.get("/api/users/directory", headers=headers).status_code == 401
    time.sleep(1.1)
    changed = client.post(
        "/api/auth/change-password",
        json={"currentPassword": PASSWORD, "newPassword": NEW_PASSWORD},
        headers=headers,
    )
    fresh = _bearer(_handed_back(changed))
    released = client.get("/api/users/directory", headers=fresh)
    assert released.status_code == 200, released.text
    assert client.get("/api/me", headers=fresh).json()["mustChangePassword"] is False
    assert client.get("/api/users/directory", headers=headers).status_code == 401, (
        "a token from before the password binding must end with the change, like every older session"
    )


@needs_db
def test_a_session_opened_with_a_temporary_password_ends_when_it_is_replaced(world, client):
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
    before = world["people"]["racer"]
    reset = client.patch(
        f"/api/users/{before.id}",
        json={"password": SECOND_PASSWORD, "mustChangePassword": False},
        headers=_headers(world, "admin"),
    )
    assert reset.status_code == 200, reset.text

    time.sleep(1.1)
    raced = auth_routes._session_token(before)  # noqa: SLF001 - the sign-in's own mint
    _assert_ended(client.get("/api/users/directory", headers=_bearer(raced)))

    current = _sign_in(client, world, "racer", SECOND_PASSWORD)
    assert client.get("/api/users/directory", headers=_bearer(current)).status_code == 200


@needs_db
def test_a_google_session_lasts_while_the_password_does_not_change(world, client, monkeypatch):
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
    email = world["address"]("datasetAdmin")
    account_id = world["people"]["datasetAdmin"].id

    def mint(password: str) -> str:
        minted = client.post("/api/datasets/token", json={"email": email, "password": password})
        assert minted.status_code == 200, minted.text
        return minted.json()["accessToken"]

    first = mint(PASSWORD)
    assert client.get("/api/datasets", headers=_bearer(first)).status_code == 200
    changed = _change(client, _sign_in(client, world, "datasetAdmin"), PASSWORD, SECOND_PASSWORD)
    assert changed.status_code == 200, changed.text
    _assert_ended(client.get("/api/datasets", headers=_bearer(first)))

    second = mint(SECOND_PASSWORD)
    assert client.get("/api/datasets", headers=_bearer(second)).status_code == 200
    raised = client.patch(
        f"/api/users/{account_id}",
        json={"mustChangePassword": True},
        headers=_headers(world, "masterActor"),
    )
    assert raised.status_code == 200, raised.text
    _assert_ended(client.get("/api/datasets", headers=_bearer(second)))

    time.sleep(1.1)
    session = _sign_in(client, world, "datasetAdmin", SECOND_PASSWORD)
    rechosen = _change(client, session, SECOND_PASSWORD, THIRD_PASSWORD)
    assert rechosen.status_code == 200, rechosen.text
    _assert_ended(client.get("/api/datasets", headers=_bearer(second)))
    assert client.get("/api/datasets", headers=_bearer(mint(THIRD_PASSWORD))).status_code == 200
