import asyncio
import io
import time
import uuid
from datetime import UTC, datetime
from typing import Any
from urllib.parse import parse_qs, urlsplit

import jwt
import pytest
from conftest import needs_db

from app.api.routes import auth as auth_routes, users as users_routes
from app.core.db import db
from app.core.deps import ACCOUNT_PROVISIONER_REQUIRED_DETAIL, get_settings
from app.core.security import create_access_token, hash_password
from app.services import access_roster, account_provisioning, signin_role
from app.services.designers import canonical_email

pytestmark = [needs_db]

PASSWORD = "provisioning-seeded-password"
NEW_PASSWORD = "provisioned-temporary-password"
SCRIPT_PASSWORD = "Script-Chosen-0451-Password"

SEEDED_NOTE = "Seeded by tests/test_account_provisioning.py."

ACTORS: tuple[tuple[str, str], ...] = (
    ("master", "MASTER_ADMIN"),
    ("admin", "ADMIN"),
    ("peerAdmin", "ADMIN"),
    ("ministry", "MINISTRY_ADMIN"),
    ("regional", "REGIONAL_DIRECTOR"),
    ("assistant", "ASSISTANT_DIRECTOR"),
    ("professor", "PROFESSOR"),
    ("flaggedMinistry", "MINISTRY_ADMIN"),
)
FLAGGED_ACTORS = frozenset({"flaggedMinistry"})

BARRED: tuple[tuple[str, str], ...] = (
    ("rejected", "REJECTED"),
    ("suspended", "SUSPENDED"),
    ("adminBarred", "SUSPENDED"),
)

BARRED_ACCOUNTS: tuple[tuple[str, str, str, str | None, bool], ...] = (
    ("barredMover", "RESEARCHER", "SUSPENDED", "RESEARCHER", False),
    ("rejectedMover", "RESEARCHER", "REJECTED", "RESEARCHER", False),
    ("endedDesigner", "DESIGNER", "ACTIVE", "RESEARCHER", True),
    ("barredMoverAdmin", "RESEARCHER", "SUSPENDED", "RESEARCHER", False),
    ("endedDesignerAdmin", "DESIGNER", "ACTIVE", None, True),
    ("endedCoTenant", "DESIGNER", "ACTIVE", "RESEARCHER", True),
    ("endedProfessor", "PROFESSOR", "ACTIVE", "PROFESSOR", True),
    ("endedProfessorAdmin", "PROFESSOR", "ACTIVE", "PROFESSOR", True),
)
PLANTS = ("barredMoverPlant", "barredMoverAdminPlant")

ACTIVE_EMPANELMENTS = ("activeEmpanelment", "activeEmpanelmentAdmin")

VICTIM_NOTE = "Empanelled for the Barpali cluster; admitted by hand. Seeded by the provisioning tests."

OWN_CREDENTIALS = account_provisioning.OWN_CREDENTIALS_DETAIL
SESSION_ENDED = "This session is no longer valid. Sign in again."
ACCESS_SUSPENDED = "Your access to this application has been suspended. Contact the administrator."
DESIGNER_SUSPENDED = "Your designer access has been suspended. Contact the administrator."
LINK_WITHDRAWN = "This link was withdrawn. Ask the administrator for a new one."


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        return f"prov-{slug}-{stamp}@example.org".lower()

    async def seed() -> tuple[dict[str, Any], list[Any]]:
        await db.connect()
        try:
            now = datetime.now(UTC)
            people: dict[str, Any] = {}
            for slug, role in ACTORS:
                people[slug] = await db.user.create(
                    data={
                        "email": address(slug),
                        "name": f"Provisioning {slug} {stamp}",
                        "role": role,
                        "passwordHash": hash_password(PASSWORD),
                        "passwordSetAt": now,
                        "mustChangePassword": slug in FLAGGED_ACTORS,
                        "authProvider": "LOCAL",
                    }
                )
                await db.accessroster.create(
                    data={
                        "email": address(slug),
                        "status": "ACTIVE",
                        "admitRole": role,
                        "joinedAt": now,
                        "notes": SEEDED_NOTE,
                    }
                )
            people["googleOnly"] = await db.user.create(
                data={
                    "email": address("googleOnly"),
                    "name": "Old Google Name",
                    "role": "RESEARCHER",
                    "authProvider": "GOOGLE",
                }
            )
            await db.accessroster.create(
                data={
                    "email": address("googleOnly"),
                    "status": "ACTIVE",
                    "admitRole": "RESEARCHER",
                    "joinedAt": now,
                    "notes": SEEDED_NOTE,
                }
            )
            for slug, state in BARRED:
                await db.accessroster.create(
                    data={
                        "email": address(slug),
                        "status": state,
                        "decidedAt": now,
                        "notes": SEEDED_NOTE,
                    }
                )
            for slug in ("empSuspended", "empSuspendedMove", "empSuspendedRole", "empSuspendedAdmin"):
                await db.designerroster.create(
                    data={
                        "email": address(slug),
                        "fullName": "Ended Empanelment",
                        "isActive": False,
                        "revokedAt": now,
                        "addedById": people["admin"].id,
                    }
                )
            for slug, role, state, admit_role, ended in BARRED_ACCOUNTS:
                people[slug] = await db.user.create(
                    data={
                        "email": address(slug),
                        "name": f"Barred {slug} {stamp}",
                        "role": role,
                        "passwordHash": hash_password(PASSWORD),
                        "passwordSetAt": now,
                        "mustChangePassword": False,
                        "authProvider": "LOCAL",
                    }
                )
                await db.accessroster.create(
                    data={
                        "email": address(slug),
                        "status": state,
                        **({"admitRole": admit_role} if admit_role else {}),
                        "joinedAt": now,
                        "decidedAt": now,
                        "decidedById": people["admin"].id,
                        "notes": SEEDED_NOTE,
                    }
                )
                if ended:
                    await db.designerroster.create(
                        data={
                            "email": address(slug),
                            "fullName": "Ended Before The Move",
                            "isActive": False,
                            "revokedAt": now,
                            "addedById": people["admin"].id,
                        }
                    )
            for slug in PLANTS:
                await db.accessroster.create(
                    data={
                        "email": address(slug),
                        "status": "PENDING",
                        "requestedAt": now,
                        "attemptCount": 1,
                    }
                )
            for slug in ACTIVE_EMPANELMENTS:
                await db.designerroster.create(
                    data={
                        "email": address(slug),
                        "fullName": "Somebody Else Empanelled",
                        "isActive": True,
                        "addedById": people["admin"].id,
                    }
                )
            barred_gmail = f"prov.barred.gmail.{stamp}@gmail.com"
            people["barredGmail"] = await db.user.create(
                data={
                    "email": barred_gmail,
                    "name": f"Barred Gmail {stamp}",
                    "role": "RESEARCHER",
                    "passwordHash": hash_password(PASSWORD),
                    "passwordSetAt": now,
                    "mustChangePassword": False,
                    "authProvider": "LOCAL",
                }
            )
            await db.accessroster.create(
                data={
                    "email": canonical_email(barred_gmail),
                    "status": "SUSPENDED",
                    "admitRole": "RESEARCHER",
                    "fullName": "Barred Gmail Person",
                    "joinedAt": now,
                    "decidedAt": now,
                    "decidedById": people["admin"].id,
                    "notes": SEEDED_NOTE,
                }
            )
            victim = f"prov.victim.{stamp}@gmail.com"
            people["gmailVictim"] = await db.user.create(
                data={
                    "email": victim,
                    "name": f"Gmail Victim {stamp}",
                    "role": "DESIGNER",
                    "passwordHash": hash_password(PASSWORD),
                    "passwordSetAt": now,
                    "mustChangePassword": False,
                    "authProvider": "LOCAL",
                }
            )
            await db.accessroster.create(
                data={
                    "email": canonical_email(victim),
                    "status": "ACTIVE",
                    "admitRole": "DESIGNER",
                    "joinedAt": now,
                    "decidedAt": now,
                    "decidedById": people["admin"].id,
                    "notes": VICTIM_NOTE,
                }
            )
            await db.designerroster.create(
                data={
                    "email": canonical_email(victim),
                    "fullName": "Gmail Victim",
                    "isActive": True,
                    "addedById": people["admin"].id,
                }
            )
            for typed in (f"a.mbig.{stamp}@gmail.com", f"am.big.{stamp}@gmail.com"):
                await db.user.create(
                    data={
                        "email": typed,
                        "name": f"Ambiguous {stamp}",
                        "role": "RESEARCHER",
                        "passwordHash": hash_password(PASSWORD),
                        "passwordSetAt": now,
                        "authProvider": "LOCAL",
                    }
                )
            await db.accessroster.create(
                data={
                    "email": f"ambig{stamp}@gmail.com",
                    "status": "ACTIVE",
                    "admitRole": "RESEARCHER",
                    "joinedAt": now,
                    "notes": SEEDED_NOTE,
                }
            )
            people["legacyCase"] = await db.user.create(
                data={
                    "email": f"Legacy.Case-{stamp}@Example.org",
                    "name": f"Legacy Case {stamp}",
                    "role": "RESEARCHER",
                    "passwordHash": hash_password(PASSWORD),
                    "passwordSetAt": now,
                    "authProvider": "LOCAL",
                }
            )
            people["masterVariant"] = await db.user.create(
                data={
                    "email": f"newmaster{stamp}@gmail.com",
                    "name": f"Planted Variant {stamp}",
                    "role": "RESEARCHER",
                    "passwordHash": hash_password(PASSWORD),
                    "passwordSetAt": now,
                    "authProvider": "LOCAL",
                }
            )
            await db.accessroster.create(
                data={
                    "email": f"leg.acy.{stamp}@gmail.com",
                    "status": "PENDING",
                    "requestedAt": now,
                    "attemptCount": 1,
                }
            )
            return people, await _the_mailbox_parity(stamp)
        finally:
            await db.disconnect()

    people, parity = asyncio.run(seed())
    script = _run_the_script_scenarios(address, stamp)
    with TestClient(app) as client:
        yield {
            "client": client,
            "people": people,
            "address": address,
            "stamp": stamp,
            "script": script,
            "parity": parity,
        }


@pytest.fixture
def client(world):
    return world["client"]



def _headers(world: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['people'][slug].id)}"}


def _bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _fresh(world: dict[str, Any], slug: str) -> str:
    return f"prov-new-{slug}-{world['stamp']}@example.org".lower()


def _create(client: Any, world: dict[str, Any], actor: str, email: str, **fields: Any) -> Any:
    body = {
        "email": email,
        "name": fields.pop("name", "Provisioned Person"),
        "password": fields.pop("password", NEW_PASSWORD),
        "role": fields.pop("role", "RESEARCHER"),
        **fields,
    }
    return client.post("/api/users", json=body, headers=_headers(world, actor))


def _login(client: Any, email: str, password: str = NEW_PASSWORD) -> Any:
    return client.post("/api/auth/login", json={"email": email, "password": password})


def _patch(client: Any, world: dict[str, Any], actor: str, user_id: str, **body: Any) -> Any:
    return client.patch(f"/api/users/{user_id}", json=body, headers=_headers(world, actor))


def _link(client: Any, world: dict[str, Any], actor: str, user_id: str) -> Any:
    return client.post(
        "/api/auth/password-links", json={"userId": user_id}, headers=_headers(world, actor)
    )


def _access_rows(client: Any, world: dict[str, Any], term: str) -> dict[str, Any]:
    response = client.get(
        "/api/access/roster",
        params={"search": term, "pageSize": 200},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    return {row["email"]: row for row in response.json()["items"]}


def _accounts(client: Any, world: dict[str, Any], term: str) -> list[dict[str, Any]]:
    response = client.get(
        "/api/users", params={"search": term, "pageSize": 100}, headers=_headers(world, "admin")
    )
    assert response.status_code == 200, response.text
    return response.json()["items"]


def _google(monkeypatch: Any, client: Any, email: str, name: str, picture: str | None = None):
    monkeypatch.setattr(
        auth_routes,
        "verify_google_token",
        lambda _token: {"email": email, "email_verified": True, "name": name, "picture": picture},
    )
    return client.post("/api/auth/login", json={"googleIdToken": "stand-in-for-a-real-token"})


async def _the_mailbox_parity(stamp: str) -> list[tuple[str, Any, Any, set[str], set[str]]]:
    mailbox = f"parity{stamp}@gmail.com"
    spellings = (
        mailbox,
        f"par.ity{stamp}@gmail.com",
        f"parity{stamp}+tag@gmail.com",
        f"Par.Ity{stamp}+Other@GoogleMail.com",
        f"parity{stamp}@googlemail.com",
        f" parity.{stamp}@gmail.com",
        f"parity{stamp}x@gmail.com",
        f"parity{stamp}@example.org",
        f"pàrity{stamp}@gmail.com",
        f"x@parity{stamp}@gmail.com",
    )
    seeded: dict[str, str] = {}
    for spelling in spellings:
        row = await db.user.create(
            data={
                "email": spelling,
                "name": f"Parity {stamp}",
                "role": "RESEARCHER",
                "authProvider": "LOCAL",
            }
        )
        seeded[row.id] = spelling
    answers = []
    for asked in (
        mailbox,
        f"par.ity{stamp}@gmail.com",
        f"PARITY{stamp}+zzz@googlemail.com",
        f"parity{stamp}x@gmail.com",
        f"nobody{stamp}@gmail.com",
        f"parity{stamp}@example.org",
    ):
        swept = await access_roster.accounts_on_the_mailbox(asked)
        folded = await access_roster.accounts_on_the_mailbox_for_sign_in(asked)
        expected = {
            row_id
            for row_id, spelling in seeded.items()
            if canonical_email(spelling) == canonical_email(asked)
        }
        answers.append(
            (
                asked,
                None if swept is None else {account.id for account in swept},
                None if folded is None else {account.id for account in folded},
                expected,
                set(seeded),
            )
        )
    return answers


def _run_the_script_scenarios(address: Any, stamp: str) -> dict[str, tuple[int, str, str]]:
    from scripts import provision_account

    def run(argv: list[str], password: str | None = None) -> tuple[int, str, str]:
        out, err = io.StringIO(), io.StringIO()
        environ = {provision_account.ENV_VARIABLE: password} if password else {}
        code = asyncio.run(provision_account.run(argv, environ=environ, out=out, err=err))
        return code, out.getvalue(), err.getvalue()

    def person(slug: str, role: str, actor: str, *extra: str, name: str = "Script Person"):
        email = slug if "@" in slug else address(slug)
        return [
            "--email", email, "--name", name, "--role", role, "--actor-email", address(actor),
            *extra,
        ]

    return {
        "dry": run(person("scriptDry", "RESEARCHER", "admin"), SCRIPT_PASSWORD),
        "made": run(
            person("scriptMade", "ASSISTANT_DIRECTOR", "ministry", "--apply"), SCRIPT_PASSWORD
        ),
        "final": run(
            person("scriptFinal", "RESEARCHER", "admin", "--no-must-change", "--apply"),
            SCRIPT_PASSWORD,
        ),
        "noPassword": run(person("scriptNone", "RESEARCHER", "admin", "--apply")),
        "argvPassword": run(
            person("scriptArgv", "RESEARCHER", "admin", "--password", SCRIPT_PASSWORD, "--apply")
        ),
        "shortPassword": run(person("scriptShort", "RESEARCHER", "admin", "--apply"), "x7Q!"),
        "overCeiling": run(person("scriptOver", "ADMIN", "ministry", "--apply"), SCRIPT_PASSWORD),
        "flaggedActor": run(
            person("scriptFlagged", "RESEARCHER", "flaggedMinistry", "--apply"), SCRIPT_PASSWORD
        ),
        "unknownActor": run(
            person("scriptUnknown", "RESEARCHER", "nobody", "--apply"), SCRIPT_PASSWORD
        ),
        "googleDry": run(person("scriptGoogleDry", "DESIGNER", "admin", "--google-only")),
        "googleApply": run(
            person("scriptGoogle", "RESEARCHER", "admin", "--google-only", "--apply",
                   name="Google Person")
        ),
        "googleMinistry": run(
            person("scriptGoogleMinistry", "RESEARCHER", "ministry", "--google-only", "--apply")
        ),
        "googleExisting": run(
            person(address("professor"), "RESEARCHER", "admin", "--google-only", "--apply")
        ),
        "googleWithPassword": run(
            person("scriptGooglePw", "RESEARCHER", "admin", "--google-only", "--apply"),
            SCRIPT_PASSWORD,
        ),
        "googleLegacy": run(
            person(f"leg.acy.{stamp}@gmail.com", "RESEARCHER", "admin", "--google-only",
                   "--apply", name="Legacy Person")
        ),
    }



@pytest.mark.parametrize("actor", ["ministry", "admin", "master"])
def test_each_provisioning_tier_creates_an_account_that_signs_in_at_once(world, client, actor):
    email = _fresh(world, f"by-{actor}")
    made = _create(client, world, actor, email)
    assert made.status_code == 201, made.text
    body = made.json()
    assert "passwordHash" not in body
    assert body["authProvider"] == "LOCAL"
    assert body["mustChangePassword"] is True
    assert body["passwordSetAt"] is not None
    assert body["firstLoginAt"] is None

    signed_in = _login(client, email)
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["mustChangePassword"] is True
    assert _access_rows(client, world, email)[email]["status"] == "ACTIVE"


@pytest.mark.parametrize("actor", ["assistant", "regional", "professor"])
def test_the_tiers_that_promote_but_do_not_provision_are_refused(world, client, actor):
    refused = _create(client, world, actor, _fresh(world, f"refused-{actor}"))
    assert refused.status_code == 403, refused.text
    assert refused.json()["detail"] == ACCOUNT_PROVISIONER_REQUIRED_DETAIL


def test_a_ministry_admin_creates_only_below_its_own_tier(world, client):
    own = _create(client, world, "ministry", _fresh(world, "own-tier"), role="MINISTRY_ADMIN")
    assert own.status_code == 403, own.text
    assert own.json()["detail"] == "You can only create accounts with a role below your own."
    below = _create(client, world, "ministry", _fresh(world, "below-tier"), role="REGIONAL_DIRECTOR")
    assert below.status_code == 201, below.text

    admin = _create(client, world, "ministry", _fresh(world, "over-admin"), role="ADMIN")
    assert admin.status_code == 403, admin.text
    assert admin.json()["detail"] == "You can only give a role at or below your own"

    master = _create(client, world, "ministry", _fresh(world, "over-master"), role="MASTER_ADMIN")
    assert master.status_code == 403, master.text
    assert master.json()["detail"] == "Only the master admin can grant master admin"



def test_the_first_sign_in_flag_is_on_unless_the_provisioner_says_otherwise(world, client):
    email = _fresh(world, "final-password")
    made = _create(client, world, "ministry", email, mustChangePassword=False)
    assert made.status_code == 201, made.text
    assert made.json()["mustChangePassword"] is False
    assert made.json()["passwordSetAt"] is not None, "a password was set, so the stamp must be"

    signed_in = _login(client, email)
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["mustChangePassword"] is False



def test_a_ministry_admin_cannot_grant_a_capability(world, client):
    granted = _create(client, world, "ministry", _fresh(world, "granted"), canReview=True)
    assert granted.status_code == 403, granted.text
    assert granted.json()["detail"] == account_provisioning.GRANTS_ARE_ADMIN_ONLY_DETAIL

    echoed = _create(
        client,
        world,
        "ministry",
        _fresh(world, "no-grants"),
        **dict.fromkeys(account_provisioning.GRANT_FLAGS, False),
    )
    assert echoed.status_code == 201, echoed.text


def test_a_ministry_admin_cannot_overturn_a_bar(world, client):
    for slug in ("rejected", "suspended"):
        refused = _create(client, world, "ministry", world["address"](slug))
        assert refused.status_code == 409, refused.text
        assert "on the access screen" in refused.json()["detail"]

    ended = world["address"]("empSuspended")
    designer = _create(client, world, "ministry", ended, role="DESIGNER")
    assert designer.status_code == 409, designer.text
    assert "empanelment" in designer.json()["detail"]

    researcher = _create(client, world, "ministry", ended, role="RESEARCHER")
    assert researcher.status_code == 201, researcher.text


def test_an_admin_may_still_readmit_a_barred_address(world, client):
    email = world["address"]("adminBarred")
    made = _create(client, world, "admin", email)
    assert made.status_code == 201, made.text
    assert _access_rows(client, world, email)[email]["status"] == "ACTIVE"
    assert _login(client, email).status_code == 200



def test_an_address_is_one_account_in_any_letter_case(world, client):
    email = _fresh(world, "once")
    assert _create(client, world, "admin", email).status_code == 201
    again = _create(client, world, "admin", email.upper())
    assert again.status_code == 409, again.text
    assert again.json()["detail"].startswith(f"{email} already has an account (")
    assert again.headers["x-existing-account-id"]


def test_two_requests_racing_for_one_address_still_get_a_409(world, client, monkeypatch):
    email = _fresh(world, "race")
    assert _create(client, world, "admin", email).status_code == 201

    async def _lost_the_race(*_args: Any, **_kwargs: Any) -> None:
        return None

    monkeypatch.setattr(account_provisioning, "existing_account", _lost_the_race)
    raced = _create(client, world, "admin", email)
    assert raced.status_code == 409, raced.text
    assert raced.json()["detail"] == "Email already exists"



def test_a_designer_provisioned_by_a_ministry_admin_is_admitted_and_empanelled(world, client):
    email = _fresh(world, "designer")
    made = _create(client, world, "ministry", email, role="DESIGNER")
    assert made.status_code == 201, made.text

    row = _access_rows(client, world, email)[email]
    assert row["status"] == "ACTIVE"
    assert row["admitRole"] == "DESIGNER"
    assert row["decidedById"] == world["people"]["ministry"].id

    roster = client.get(
        "/api/designers/roster", params={"search": email}, headers=_headers(world, "admin")
    )
    assert roster.status_code == 200, roster.text
    rows = [r for r in roster.json()["items"] if r["email"] == email]
    assert len(rows) == 1 and rows[0]["isActive"] is True
    assert rows[0]["addedById"] == world["people"]["ministry"].id
    assert _login(client, email).status_code == 200



def _settled_account(client: Any, world: dict[str, Any], slug: str, **fields: Any) -> dict:
    email = _fresh(world, slug)
    made = _create(client, world, "admin", email, mustChangePassword=False, **fields)
    assert made.status_code == 201, made.text
    return made.json()


def test_asking_an_account_to_change_its_password_leaves_the_password_alone(world, client):
    account = _settled_account(client, world, "next-sign-in")
    before = _login(client, account["email"])
    assert before.status_code == 200, before.text
    old_token = before.json()["accessToken"]

    raised = _patch(client, world, "ministry", account["id"], mustChangePassword=True)
    assert raised.status_code == 200, raised.text
    assert raised.json()["mustChangePassword"] is True
    assert raised.json()["passwordSetAt"] == account["passwordSetAt"], "the password was replaced"

    ended = client.get("/api/me", headers=_bearer(old_token))
    assert ended.status_code == 401, ended.text
    assert ended.json()["detail"] == SESSION_ENDED

    time.sleep(1.1)
    after = _login(client, account["email"])
    assert after.status_code == 200, after.text
    assert after.json()["user"]["mustChangePassword"] is True


def test_the_flag_cannot_be_raised_on_an_account_with_no_password(world, client):
    google_only = world["people"]["googleOnly"].id
    refused = _patch(client, world, "ministry", google_only, mustChangePassword=True)
    assert refused.status_code == 422, refused.text
    assert refused.json()["detail"] == account_provisioning.NO_PASSWORD_TO_CHANGE_DETAIL
    assert "link" not in refused.json()["detail"]
    assert _link(client, world, "ministry", google_only).status_code == 422

    settings = get_settings()
    now = int(time.time())
    stale = jwt.encode(
        {"sub": google_only, "iat": now - 3600, "exp": now + 3600},
        settings.jwt_secret,
        algorithm=settings.jwt_algorithm,
    )
    told = client.post(
        "/api/auth/change-password",
        json={"currentPassword": "anything-at-all", "newPassword": "a-new-password-for-it"},
        headers=_bearer(stale),
    )
    assert told.status_code == 400, told.text
    assert told.headers.get("x-confirm-provider") == "GOOGLE"


@pytest.mark.parametrize("actor", ["ministry", "admin", "master"])
def test_nobody_sets_their_own_password_or_flag_here(world, client, actor):
    own = world["people"][actor].id
    for body in ({"password": "a-new-password-for-me"}, {"mustChangePassword": True}):
        refused = _patch(client, world, actor, own, **body)
        assert refused.status_code == 403, refused.text
        assert refused.json()["detail"] == OWN_CREDENTIALS


def test_a_password_set_for_somebody_else_is_temporary_and_ends_their_sessions(world, client):
    account = _settled_account(client, world, "reset-for")
    old_token = _login(client, account["email"]).json()["accessToken"]

    reset = _patch(client, world, "admin", account["id"], password="typed-by-an-admin")
    assert reset.status_code == 200, reset.text
    assert reset.json()["mustChangePassword"] is True
    assert client.get("/api/me", headers=_bearer(old_token)).status_code == 401

    final = _patch(
        client,
        world,
        "admin",
        account["id"],
        password="final-by-an-admin",
        mustChangePassword=False,
    )
    assert final.status_code == 200, final.text
    assert final.json()["mustChangePassword"] is False

    time.sleep(1.1)
    assert _login(client, account["email"], "final-by-an-admin").status_code == 200
    assert _login(client, account["email"], "typed-by-an-admin").status_code == 401


def test_a_ministry_admin_cannot_change_a_capability_but_may_echo_one(world, client):
    account = _settled_account(client, world, "grants")
    changed = _patch(client, world, "ministry", account["id"], canReview=True)
    assert changed.status_code == 403, changed.text
    assert changed.json()["detail"] == account_provisioning.GRANTS_ARE_ADMIN_ONLY_DETAIL

    echoed = _patch(client, world, "ministry", account["id"], canReview=False, name="Echoed Name")
    assert echoed.status_code == 200, echoed.text
    assert echoed.json()["canReview"] is False


def test_a_ministry_admin_corrects_a_name_and_an_address(world, client):
    account = _settled_account(client, world, "typo")
    corrected = _fresh(world, "typo-fixed")
    fixed = _patch(
        client, world, "ministry", account["id"], name="Corrected Name", email=corrected
    )
    assert fixed.status_code == 200, fixed.text
    assert fixed.json()["email"] == corrected
    assert _login(client, corrected).status_code == 200, "the allow-list did not follow the address"


def test_a_ministry_admin_cannot_move_an_account_onto_a_barred_address(world, client):
    account = _settled_account(client, world, "move-to-barred")
    refused = _patch(
        client, world, "ministry", account["id"], email=world["address"]("rejected")
    )
    assert refused.status_code == 409, refused.text
    assert "on the access screen" in refused.json()["detail"]


def test_a_ministry_admin_cannot_move_a_designer_onto_an_ended_empanelment(world, client):
    ended = world["address"]("empSuspendedMove")
    designer = _settled_account(client, world, "designer-moving", role="DESIGNER")
    refused = _patch(client, world, "ministry", designer["id"], email=ended)
    assert refused.status_code == 409, refused.text
    assert refused.json()["detail"] == (
        account_provisioning.SUSPENDED_EMPANELMENT_MOVE_DETAIL.format(email=ended)
    )
    kept = [row for row in _accounts(client, world, designer["email"]) if row["id"] == designer["id"]]
    assert [row["email"] for row in kept] == [designer["email"]], "the refused move wrote anyway"

    becoming = _settled_account(client, world, "becoming-a-designer")
    both = _patch(
        client,
        world,
        "ministry",
        becoming["id"],
        email=world["address"]("empSuspendedRole"),
        role="DESIGNER",
    )
    assert both.status_code == 409, both.text
    assert "empanelment" in both.json()["detail"]

    researcher = _settled_account(client, world, "researcher-moving")
    moved = _patch(client, world, "ministry", researcher["id"], email=ended)
    assert moved.status_code == 200, moved.text
    assert moved.json()["email"] == ended


def test_an_admin_may_still_move_a_designer_onto_an_ended_empanelment(world, client):
    ended = world["address"]("empSuspendedAdmin")
    designer = _settled_account(client, world, "designer-moved-by-admin", role="DESIGNER")
    moved = _patch(client, world, "admin", designer["id"], email=ended)
    assert moved.status_code == 200, moved.text
    assert moved.json()["email"] == ended


def test_a_professor_changes_roles_and_nothing_else(world, client):
    account = _settled_account(client, world, "professor-target")
    renamed = _patch(client, world, "professor", account["id"], name="Renamed By A Professor")
    assert renamed.status_code == 403, renamed.text
    assert "Ministry Admin" in renamed.json()["detail"]

    demoted = _patch(client, world, "professor", account["id"], role="FIELD_CONTRIBUTOR")
    assert demoted.status_code == 200, demoted.text
    assert demoted.json()["role"] == "FIELD_CONTRIBUTOR"


def test_a_provisioner_cannot_touch_an_account_at_or_above_its_tier(world, client):
    refused = _patch(
        client, world, "ministry", world["people"]["peerAdmin"].id, name="Renamed Upwards"
    )
    assert refused.status_code == 403, refused.text
    assert refused.json()["detail"] == "You can only manage accounts with a role below your own"



def test_an_admin_cannot_mint_a_link_for_the_master_admin_or_a_peer(world, client):
    for target in ("master", "peerAdmin"):
        refused = _link(client, world, "admin", world["people"][target].id)
        assert refused.status_code == 403, refused.text


def test_a_master_admin_mints_a_link_for_an_admin(world, client):
    issued = _link(client, world, "master", world["people"]["peerAdmin"].id)
    assert issued.status_code == 201, issued.text
    assert set(issued.json()) == {"id", "link", "expiresAt", "purpose", "deliveredBy"}


def test_the_link_is_an_invitation_until_the_account_is_used(world, client):
    email = _fresh(world, "invited")
    made = _create(client, world, "ministry", email)
    assert made.status_code == 201, made.text

    first = _link(client, world, "ministry", made.json()["id"])
    assert first.status_code == 201, first.text
    assert first.json()["purpose"] == "INVITE"

    assert _login(client, email).status_code == 200
    second = _link(client, world, "ministry", made.json()["id"])
    assert second.status_code == 201, second.text
    assert second.json()["purpose"] == "RESET"


def test_a_google_account_with_no_password_gets_no_link(world, client):
    refused = _link(client, world, "ministry", world["people"]["googleOnly"].id)
    assert refused.status_code == 422, refused.text
    assert refused.json()["detail"] == account_provisioning.GOOGLE_ONLY_LINK_DETAIL


def test_a_link_for_nobody_is_a_404(world, client):
    assert _link(client, world, "ministry", "no-such-account").status_code == 404


def test_nobody_mints_themselves_a_link(world, client):
    refused = _link(client, world, "admin", world["people"]["admin"].id)
    assert refused.status_code == 403, refused.text
    assert refused.json()["detail"] == OWN_CREDENTIALS


@pytest.mark.parametrize("actor", ["assistant", "regional", "professor"])
def test_tiers_that_cannot_provision_issue_no_links(world, client, actor):
    account = _settled_account(client, world, f"link-by-{actor}")
    refused = _link(client, world, actor, account["id"])
    assert refused.status_code == 403, refused.text
    assert refused.json()["detail"] == ACCOUNT_PROVISIONER_REQUIRED_DETAIL


def test_revoking_a_link_needs_the_same_authority_as_issuing_it(world, client):
    issued = _link(client, world, "master", world["people"]["peerAdmin"].id)
    assert issued.status_code == 201, issued.text
    link_id = issued.json()["id"]

    for actor in ("admin", "ministry"):
        refused = client.post(
            f"/api/auth/password-links/{link_id}/revoke", headers=_headers(world, actor)
        )
        assert refused.status_code == 403, f"{actor}: {refused.text}"

    revoked = client.post(
        f"/api/auth/password-links/{link_id}/revoke", headers=_headers(world, "master")
    )
    assert revoked.status_code == 200, revoked.text
    missing = client.post(
        "/api/auth/password-links/no-such-link/revoke", headers=_headers(world, "master")
    )
    assert missing.status_code == 404, missing.text


def test_a_link_cannot_be_redeemed_with_the_temporary_password(world, client):
    email = _fresh(world, "redeem")
    made = _create(client, world, "ministry", email)
    assert made.status_code == 201, made.text
    issued = _link(client, world, "ministry", made.json()["id"])
    assert issued.status_code == 201, issued.text
    token = parse_qs(urlsplit(issued.json()["link"]).query)["token"][0]

    same = client.post("/api/auth/set-password", json={"token": token, "password": NEW_PASSWORD})
    assert same.status_code == 400, same.text
    chosen = client.post(
        "/api/auth/set-password", json={"token": token, "password": "the-holders-own-choice"}
    )
    assert chosen.status_code == 200, chosen.text

    time.sleep(1.1)
    signed_in = _login(client, email, "the-holders-own-choice")
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["mustChangePassword"] is False



def test_google_on_a_password_account_keeps_the_password_the_provider_and_the_name(
    world, client, monkeypatch
):
    email = _fresh(world, "google-exact")
    made = _create(client, world, "admin", email, name="Typed By An Admin")
    assert made.status_code == 201, made.text

    picture = "https://example.org/face.png"
    signed_in = _google(monkeypatch, client, email, "Display Name From Google", picture)
    assert signed_in.status_code == 200, signed_in.text
    user = signed_in.json()["user"]
    assert user["id"] == made.json()["id"]
    assert user["name"] == "Typed By An Admin", "Google overwrote the name an administrator typed"
    assert user["authProvider"] == "LOCAL", "Google sign-in converted a password account"
    assert user["mustChangePassword"] is True, (
        "Google sign-in cleared the temporary password's flag"
    )
    assert user["avatarUrl"] == picture
    assert _login(client, email).status_code == 200, "the password stopped working"


def test_google_finds_the_password_account_under_another_spelling_of_the_mailbox(
    world, client, monkeypatch
):
    stamp = world["stamp"]
    typed = f"g.canon.{stamp}@gmail.com"
    mailbox = f"gcanon{stamp}@gmail.com"
    made = _create(client, world, "admin", typed)
    assert made.status_code == 201, made.text

    signed_in = _google(monkeypatch, client, mailbox, "Canon Person")
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["id"] == made.json()["id"], "a second account was created"
    assert signed_in.json()["user"]["email"] == typed
    assert [a for a in _accounts(client, world, mailbox) if a["email"] == mailbox] == []

    original = type(db.user).find_many

    async def _no_sweep(self: Any, *args: Any, **kwargs: Any) -> Any:
        assert "endswith" not in repr(kwargs.get("where")), "a Google sign-in swept every Gmail account"
        return await original(self, *args, **kwargs)

    monkeypatch.setattr(type(db.user), "find_many", _no_sweep)
    again = _google(monkeypatch, client, mailbox, "Canon Person")
    assert again.status_code == 200, again.text
    assert again.json()["user"]["id"] == made.json()["id"]


def test_the_sign_in_fold_answers_exactly_what_the_sweep_answered(world):
    for asked, swept, folded, expected, seeded in world["parity"]:
        assert swept is not None and folded is not None, f"{asked}: an answer was withdrawn"
        assert folded == swept, f"{asked}: the fold and the sweep disagree"
        assert folded & seeded == expected, f"{asked}: {folded & seeded} != {expected}"
    asked_for_the_mailbox = world["parity"][0]
    assert len(asked_for_the_mailbox[3]) == 6, "the six spellings of the one mailbox must all match"


def test_two_password_accounts_on_one_mailbox_are_refused_rather_than_guessed(
    world, client, monkeypatch
):
    stamp = world["stamp"]
    mailbox = f"ambig{stamp}@gmail.com"

    refused = _google(monkeypatch, client, mailbox, "Ambiguous Person")
    assert refused.status_code == 409, refused.text
    assert "password" in refused.json()["detail"]
    assert [a for a in _accounts(client, world, mailbox) if a["email"] == mailbox] == []


def test_a_google_only_account_signs_in_exactly_as_before(world, client, monkeypatch):
    email = world["address"]("googleOnly")
    signed_in = _google(monkeypatch, client, email, "New Google Name")
    assert signed_in.status_code == 200, signed_in.text
    user = signed_in.json()["user"]
    assert user["name"] == "New Google Name"
    assert user["authProvider"] == "GOOGLE"



def test_the_script_dry_run_writes_nothing(world, client):
    code, out, err = world["script"]["dry"]
    assert code == 0, out + err
    assert out.startswith("DRY RUN"), out
    assert "Nothing was written" in out
    assert SCRIPT_PASSWORD not in out + err
    assert _accounts(client, world, world["address"]("scriptDry")) == []


def test_the_script_creates_the_account_the_route_would(world, client):
    code, out, err = world["script"]["made"]
    assert code == 0, out + err
    assert out.startswith("CREATED"), out
    assert SCRIPT_PASSWORD not in out + err
    assert out.count("\n") == 1, f"one summary line, not a report: {out!r}"

    email = world["address"]("scriptMade")
    signed_in = _login(client, email, SCRIPT_PASSWORD)
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["role"] == "ASSISTANT_DIRECTOR"
    assert signed_in.json()["user"]["mustChangePassword"] is True
    row = _access_rows(client, world, email)[email]
    assert row["status"] == "ACTIVE"
    assert row["decidedById"] == world["people"]["ministry"].id, "the actor was not recorded"


def test_the_script_can_make_the_password_final(world, client):
    code, out, err = world["script"]["final"]
    assert code == 0, out + err
    signed_in = _login(client, world["address"]("scriptFinal"), SCRIPT_PASSWORD)
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["mustChangePassword"] is False


def test_the_password_comes_from_the_environment_and_is_never_echoed(world, client):
    code, out, err = world["script"]["noPassword"]
    assert code == 2, out + err
    assert "PROVISION_PASSWORD" in out

    code, out, err = world["script"]["argvPassword"]
    assert code == 2, out + err
    assert SCRIPT_PASSWORD not in out + err, "argparse quoted a password typed on the command line"
    assert _accounts(client, world, world["address"]("scriptArgv")) == []

    code, out, err = world["script"]["shortPassword"]
    assert code == 1, out + err
    assert "x7Q!" not in out + err, "the validation message quoted the password"


def test_the_script_refuses_what_the_route_refuses_that_actor(world, client):
    code, out, err = world["script"]["overCeiling"]
    assert code == 1, out + err
    assert "REFUSED (403)" in out and "at or below your own" in out

    code, out, err = world["script"]["flaggedActor"]
    assert code == 1, out + err
    assert "own password" in out

    code, out, err = world["script"]["unknownActor"]
    assert code == 1, out + err
    assert "no account has the address" in out

    for slug in ("scriptOver", "scriptFlagged", "scriptUnknown"):
        assert _accounts(client, world, world["address"](slug)) == []


def test_google_only_admits_the_address_and_creates_no_account(world, client, monkeypatch):
    code, out, err = world["script"]["googleDry"]
    assert code == 0, out + err
    assert out.startswith("DRY RUN"), out
    assert world["address"]("scriptGoogleDry") not in _access_rows(
        client, world, world["address"]("scriptGoogleDry")
    )

    code, out, err = world["script"]["googleApply"]
    assert code == 0, out + err
    assert out.startswith("ADMITTED"), out
    email = world["address"]("scriptGoogle")
    row = _access_rows(client, world, email)[email]
    assert row["status"] == "ACTIVE"
    assert row["admitRole"] == "RESEARCHER"
    assert row["fullName"] == "Google Person"
    assert row["decidedById"] == world["people"]["admin"].id
    assert _accounts(client, world, email) == [], "google-only mode created an account"

    signed_in = _google(monkeypatch, client, email, "Google Person")
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["role"] == "RESEARCHER"
    assert signed_in.json()["user"]["authProvider"] == "GOOGLE"


def test_google_only_follows_the_access_screens_gate(world, client):
    code, out, err = world["script"]["googleMinistry"]
    assert code == 1, out + err
    assert "REFUSED (403)" in out

    code, out, err = world["script"]["googleExisting"]
    assert code == 1, out + err
    assert "REFUSED (409)" in out and "already exists" in out

    code, out, err = world["script"]["googleWithPassword"]
    assert code == 2, out + err
    assert SCRIPT_PASSWORD not in out + err


def test_google_only_writes_through_a_legacy_spelling_of_the_mailbox(world, client, monkeypatch):
    stamp = world["stamp"]
    legacy = f"leg.acy.{stamp}@gmail.com"
    code, out, err = world["script"]["googleLegacy"]
    assert code == 0, out + err
    assert out.startswith("ADMITTED"), out
    assert legacy in out, "the line must name the row it wrote"

    rows = [
        row
        for row in _access_rows(client, world, f"{stamp}@gmail.com").values()
        if canonical_email(row["email"]) == f"legacy{stamp}@gmail.com"
    ]
    assert [row["email"] for row in rows] == [legacy], "a second row was written for the mailbox"
    assert rows[0]["status"] == "ACTIVE"
    assert rows[0]["admitRole"] == "RESEARCHER"

    signed_in = _google(monkeypatch, client, legacy, "Legacy Person")
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["role"] == "RESEARCHER"



def test_a_ministry_admin_cannot_move_a_suspended_account_onto_a_planted_address(world, client):
    mover = world["people"]["barredMover"]
    plant = world["address"]("barredMoverPlant")
    refused = _patch(client, world, "ministry", mover.id, email=plant)
    assert refused.status_code == 409, refused.text
    assert refused.json()["detail"] == account_provisioning.LEAVING_A_BARRED_ADDRESS_DETAIL.format(
        verb="suspended", email=mover.email
    )
    assert _access_rows(client, world, plant)[plant]["status"] == "PENDING", "the plant was admitted"
    kept = [row for row in _accounts(client, world, mover.email) if row["id"] == mover.id]
    assert [row["email"] for row in kept] == [mover.email], "the refused move wrote anyway"

    still = _login(client, mover.email, PASSWORD)
    assert still.status_code == 403, still.text
    assert still.json()["detail"] == ACCESS_SUSPENDED


def test_a_ministry_admin_cannot_move_a_rejected_account_to_a_fresh_address(world, client):
    mover = world["people"]["rejectedMover"]
    refused = _patch(client, world, "ministry", mover.id, email=_fresh(world, "rejected-away"))
    assert refused.status_code == 409, refused.text
    assert refused.json()["detail"] == account_provisioning.LEAVING_A_BARRED_ADDRESS_DETAIL.format(
        verb="rejected", email=mover.email
    )


def test_a_ministry_admin_cannot_move_a_designer_off_an_ended_empanelment(world, client):
    designer = world["people"]["endedDesigner"]
    refused = _patch(client, world, "ministry", designer.id, email=_fresh(world, "ended-away"))
    assert refused.status_code == 409, refused.text
    assert refused.json()["detail"] == (
        account_provisioning.LEAVING_AN_ENDED_EMPANELMENT_DETAIL.format(email=designer.email)
    )
    still = _login(client, designer.email, PASSWORD)
    assert still.status_code == 403, still.text
    assert still.json()["detail"] == DESIGNER_SUSPENDED


def test_an_admin_may_move_a_barred_account_and_the_bar_goes_with_it(world, client):
    mover = world["people"]["barredMoverAdmin"]
    plant = world["address"]("barredMoverAdminPlant")
    moved = _patch(client, world, "admin", mover.id, email=plant)
    assert moved.status_code == 200, moved.text
    assert moved.json()["email"] == plant
    row = _access_rows(client, world, plant)[plant]
    assert row["status"] == "SUSPENDED", "the planted row admitted a barred account"
    assert access_roster.BAR_CARRIED_BY_EMAIL_MOVE_NOTE in (row["notes"] or "")
    refused = _login(client, plant, PASSWORD)
    assert refused.status_code == 403, refused.text
    assert refused.json()["detail"] == ACCESS_SUSPENDED

    designer = world["people"]["endedDesignerAdmin"]
    fresh = _fresh(world, "ended-moved-by-admin")
    moved = _patch(client, world, "admin", designer.id, email=fresh)
    assert moved.status_code == 200, moved.text
    refused = _login(client, fresh, PASSWORD)
    assert refused.status_code == 403, refused.text
    assert refused.json()["detail"] == DESIGNER_SUSPENDED, (
        "the sign-in at the new address empanelled a designer whose empanelment had been ended"
    )
    roster = client.get(
        "/api/designers/roster", params={"search": fresh}, headers=_headers(world, "admin")
    )
    assert roster.status_code == 200, roster.text
    assert [r["isActive"] for r in roster.json()["items"] if r["email"] == fresh] == [False]



def test_a_promotion_withdraws_the_links_a_lower_provisioner_issued(world, client):
    account = _settled_account(client, world, "promoted-past-its-link")
    issued = _link(client, world, "ministry", account["id"])
    assert issued.status_code == 201, issued.text
    token = parse_qs(urlsplit(issued.json()["link"]).query)["token"][0]

    promoted = _patch(client, world, "admin", account["id"], role="ADMIN")
    assert promoted.status_code == 200, promoted.text
    checked = client.get("/api/auth/set-password", params={"token": token})
    assert checked.json() == {"valid": False, "reason": "revoked", "purpose": None}
    redeemed = client.post(
        "/api/auth/set-password", json={"token": token, "password": "the-issuers-own-choice"}
    )
    assert redeemed.status_code == 400, redeemed.text
    assert redeemed.json()["detail"] == LINK_WITHDRAWN


def test_a_promotion_waits_for_a_temporary_password_to_be_replaced(world, client):
    made = _create(client, world, "ministry", _fresh(world, "promoted-while-temporary"))
    assert made.status_code == 201, made.text
    account_id = made.json()["id"]

    refused = _patch(client, world, "admin", account_id, role="PROFESSOR")
    assert refused.status_code == 409, refused.text
    assert refused.json()["detail"] == (
        account_provisioning.PROMOTION_WITH_A_TEMPORARY_PASSWORD_DETAIL
    )
    assert "on the website's Users page" in refused.json()["detail"]
    lowered = _patch(client, world, "admin", account_id, role="FIELD_CONTRIBUTOR")
    assert lowered.status_code == 200, "only a promotion waits; a demotion does not"

    promoted = _patch(
        client, world, "admin", account_id, role="PROFESSOR", password="typed-by-the-promoter"
    )
    assert promoted.status_code == 200, promoted.text
    assert promoted.json()["role"] == "PROFESSOR"
    assert promoted.json()["mustChangePassword"] is True


def _approve(client: Any, world: dict[str, Any], actor: str, email: str, role: str) -> Any:
    row = _access_rows(client, world, email)[email]
    return client.post(
        f"/api/access/roster/{row['id']}/decision",
        json={"decision": "APPROVE", "role": role},
        headers=_headers(world, actor),
    )


def _role_of(client: Any, world: dict[str, Any], account: dict[str, Any]) -> str:
    rows = [row for row in _accounts(client, world, account["email"]) if row["id"] == account["id"]]
    assert len(rows) == 1, rows
    return rows[0]["role"]


def test_an_approval_never_changes_an_existing_account_or_its_links(world, client, caplog):
    account = _settled_account(client, world, "kept-at-its-role")
    issued = _link(client, world, "master", account["id"])
    assert issued.status_code == 201, issued.text
    token = parse_qs(urlsplit(issued.json()["link"]).query)["token"][0]

    unchanged = _approve(client, world, "admin", account["email"], "RESEARCHER")
    assert unchanged.status_code == 200, unchanged.text
    assert unchanged.json()["accountPromotionHeld"] is None

    kept = _approve(client, world, "master", account["email"], "ADMIN")
    assert kept.status_code == 200, kept.text
    assert (kept.json()["status"], kept.json()["admitRole"]) == ("ACTIVE", "ADMIN")
    assert kept.json()["accountPromotionHeld"] == signin_role.role_kept_sentence(
        account["email"], "RESEARCHER", "ADMIN"
    )
    assert _role_of(client, world, account) == "RESEARCHER"
    assert client.get("/api/auth/set-password", params={"token": token}).json()["valid"] is True

    caplog.set_level("WARNING", logger=signin_role.logger.name)
    promoted = _patch(client, world, "master", account["id"], role="PROFESSOR")
    assert promoted.status_code == 200, promoted.text
    assert _role_of(client, world, account) == "PROFESSOR"
    audits = [r.getMessage() for r in caplog.records if r.getMessage().startswith(signin_role.ROLE_AUDIT_PREFIX)]
    assert len(audits) == 1 and "from=RESEARCHER to=PROFESSOR via=users" in audits[0], audits



def _as_the_master_mailbox(world: dict[str, Any]) -> tuple[str, str]:
    return f"new.master.{world['stamp']}@gmail.com", get_settings().master_admin_email


def test_nobody_but_a_master_admin_puts_an_account_on_any_spelling_of_its_mailbox(world, client):
    stamp = world["stamp"]
    account = _settled_account(client, world, "renamed-towards-the-master")
    configured, previous = _as_the_master_mailbox(world)
    settings = get_settings()
    settings.master_admin_email = configured
    try:
        made = [
            _create(client, world, "ministry", spelling)
            for spelling in (f"newmaster{stamp}+x@gmail.com", f"New.Master.{stamp}@googlemail.com")
        ]
        moved = _patch(
            client, world, "ministry", account["id"], email=f"newmaster{stamp}+y@gmail.com"
        )
    finally:
        settings.master_admin_email = previous
    for refused in (*made, moved):
        assert refused.status_code == 403, refused.text
        assert refused.json()["detail"] == account_provisioning.MASTER_EMAIL_DETAIL


def test_the_master_admins_google_sign_in_never_lands_on_another_spelling(
    world, client, monkeypatch
):
    planted = world["people"]["masterVariant"]
    configured, previous = _as_the_master_mailbox(world)
    settings = get_settings()
    settings.master_admin_email = configured
    try:
        signed_in = _google(monkeypatch, client, configured, "The Real Master")
    finally:
        settings.master_admin_email = previous
    assert signed_in.status_code == 200, signed_in.text
    user = signed_in.json()["user"]
    assert user["id"] != planted.id, "the master's sign-in landed on the planted account"
    assert user["email"] == configured
    assert user["role"] == "MASTER_ADMIN"
    kept = [row for row in _accounts(client, world, planted.email) if row["id"] == planted.id]
    assert [row["role"] for row in kept] == ["RESEARCHER"]



def test_a_provisioner_may_withdraw_a_required_change(world, client):
    account = _settled_account(client, world, "change-withdrawn")
    reset = _patch(client, world, "ministry", account["id"], password="typed-by-a-ministry-admin")
    assert reset.status_code == 200, reset.text
    assert reset.json()["mustChangePassword"] is True

    time.sleep(1.1)
    signed_in = _login(client, account["email"], "typed-by-a-ministry-admin")
    assert signed_in.status_code == 200, signed_in.text
    session = _bearer(signed_in.json()["accessToken"])
    held = client.get("/api/users/directory", headers=session)
    assert held.status_code == 401, held.text
    assert held.headers.get("X-Password-Change-Required") == "1"

    withdrawn = _patch(client, world, "ministry", account["id"], mustChangePassword=False)
    assert withdrawn.status_code == 200, withdrawn.text
    assert withdrawn.json()["mustChangePassword"] is False
    assert client.get("/api/users/directory", headers=session).status_code == 200


def test_a_ministry_admin_cannot_delete_an_account_it_manages(world, client):
    account = _settled_account(client, world, "not-deletable")
    refused = client.delete(f"/api/users/{account['id']}", headers=_headers(world, "ministry"))
    assert refused.status_code == 403, refused.text
    assert refused.json()["detail"] == "Admin access required"
    assert [row["id"] for row in _accounts(client, world, account["email"])] == [account["id"]]


def test_a_correction_onto_a_legacy_address_in_another_letter_case_is_a_duplicate(world, client):
    legacy = world["people"]["legacyCase"]
    account = _settled_account(client, world, "onto-a-legacy-address")
    refused = _patch(client, world, "ministry", account["id"], email=legacy.email.lower())
    assert refused.status_code == 409, refused.text
    assert refused.json()["detail"] == "Email already exists"
    kept = [row for row in _accounts(client, world, account["email"]) if row["id"] == account["id"]]
    assert [row["email"] for row in kept] == [account["email"]]


def test_a_correction_that_loses_the_race_is_a_409_not_a_500(world, client, monkeypatch):
    holder = _settled_account(client, world, "race-holder")
    mover = _settled_account(client, world, "race-mover")

    async def _lost_the_race(*_args: Any, **_kwargs: Any) -> bool:
        return False

    monkeypatch.setattr(users_routes, "email_in_use", _lost_the_race)
    raced = _patch(client, world, "ministry", mover["id"], email=holder["email"])
    assert raced.status_code == 409, raced.text
    assert raced.json()["detail"] == "Email already exists"
    kept = [row for row in _accounts(client, world, mover["email"]) if row["id"] == mover["id"]]
    assert [row["email"] for row in kept] == [mover["email"]]



def _roster_rows_at(client: Any, world: dict[str, Any], email: str) -> list[dict[str, Any]]:
    response = client.get(
        "/api/designers/roster", params={"search": email}, headers=_headers(world, "admin")
    )
    assert response.status_code == 200, response.text
    return [row for row in response.json()["items"] if row["email"] == email]


def test_an_admins_correction_bars_the_new_mailbox_and_leaves_the_old_one_barred(
    world, client, monkeypatch
):
    mover = world["people"]["barredGmail"]
    old_mailbox = canonical_email(mover.email)
    fresh = _fresh(world, "barred-gmail-corrected")
    moved = _patch(client, world, "admin", mover.id, email=fresh)
    assert moved.status_code == 200, moved.text
    assert moved.json()["email"] == fresh

    stamp_rows = _access_rows(client, world, world["stamp"])
    at_old = [row for email, row in stamp_rows.items() if canonical_email(email) == old_mailbox]
    assert [row["status"] for row in at_old] == ["SUSPENDED"], "the bar left the old mailbox"
    carried = stamp_rows[fresh]
    assert carried["status"] == "SUSPENDED"
    assert carried["notes"] == access_roster.BAR_CARRIED_BY_EMAIL_MOVE_NOTE
    assert carried["decidedById"] == world["people"]["admin"].id, "who barred them was lost"
    assert (carried["admitRole"], carried["fullName"]) == ("RESEARCHER", "Barred Gmail Person")

    google = _google(monkeypatch, client, mover.email, "Barred Gmail Person")
    assert google.status_code == 403, google.text
    assert google.json()["detail"] == ACCESS_SUSPENDED
    assert google.headers.get("X-Access-Status") == "SUSPENDED"
    after = _access_rows(client, world, world["stamp"])
    assert [row["status"] for email, row in after.items() if canonical_email(email) == old_mailbox] == [
        "SUSPENDED"
    ], "a refused Google sign-in at the old mailbox queued a fresh request"
    at_new = _login(client, fresh, PASSWORD)
    assert at_new.status_code == 403, at_new.text
    assert at_new.json()["detail"] == ACCESS_SUSPENDED


def test_no_account_is_created_on_a_mailbox_another_account_uses(world, client):
    stamp = world["stamp"]
    first = _create(client, world, "admin", f"one.inbox.{stamp}@gmail.com")
    assert first.status_code == 201, first.text
    for actor, spelling in (
        ("admin", f"oneinbox{stamp}@gmail.com"),
        ("ministry", f"One.Inbox.{stamp}+second@googlemail.com"),
        ("master", f"o.n.e.inbox{stamp}@gmail.com"),
    ):
        again = _create(client, world, actor, spelling)
        assert again.status_code == 409, (actor, again.text)
        assert again.json()["detail"].startswith(f"one.inbox.{stamp}@gmail.com already has an account (")
        assert again.headers["x-existing-account-id"] == first.json()["id"]

    for spelling in (f"dot.ted.{stamp}@example.org", f"dotted.{stamp}@example.org"):
        assert _create(client, world, "admin", spelling).status_code == 201, spelling

    respelled = _patch(
        client, world, "ministry", first.json()["id"], email=f"oneinbox{stamp}@gmail.com"
    )
    assert respelled.status_code == 200, "an account was refused its own mailbox"


def test_no_account_is_moved_onto_another_accounts_mailbox_and_its_owner_keeps_everything(
    world, client
):
    victim = world["people"]["gmailVictim"]
    mover = world["people"]["endedCoTenant"]
    dotless = canonical_email(victim.email)
    assert dotless != victim.email, "the fixture must be two spellings of one mailbox"
    for actor in ("ministry", "admin"):
        refused = _patch(client, world, actor, mover.id, role="RESEARCHER", email=dotless)
        assert refused.status_code == 409, (actor, refused.text)
        assert refused.json()["detail"] == "Email already exists"
    kept = [row for row in _accounts(client, world, mover.email) if row["id"] == mover.id]
    assert [(row["email"], row["role"]) for row in kept] == [(mover.email, "DESIGNER")]

    row = _access_rows(client, world, dotless)[dotless]
    assert (row["status"], row["notes"]) == ("ACTIVE", VICTIM_NOTE), "the victim's row was rewritten"
    assert row["decidedById"] == world["people"]["admin"].id
    assert [r["isActive"] for r in _roster_rows_at(client, world, dotless)] == [True]
    assert _login(client, victim.email, PASSWORD).status_code == 200


def test_a_ministry_admin_cannot_end_an_empanelment_by_moving_an_account_onto_it(world, client):
    mover = world["people"]["endedProfessor"]
    destination = world["address"]("activeEmpanelment")
    refused = _patch(client, world, "ministry", mover.id, email=destination)
    assert refused.status_code == 409, refused.text
    assert refused.json()["detail"] == (
        account_provisioning.ENDING_AN_EMPANELMENT_BY_MOVING_DETAIL.format(
            email=mover.email, destination=destination
        )
    )
    assert [r["isActive"] for r in _roster_rows_at(client, world, destination)] == [True]
    kept = [row for row in _accounts(client, world, mover.email) if row["id"] == mover.id]
    assert [row["email"] for row in kept] == [mover.email], "the refused move wrote anyway"

    fresh = _fresh(world, "ended-professor-moved")
    moved = _patch(client, world, "ministry", mover.id, email=fresh)
    assert moved.status_code == 200, moved.text


def test_an_admins_same_move_carries_the_ending_onto_the_active_empanelment(world, client):
    from app.services.access_roster import EMPANELMENT_CARRIED_BY_EMAIL_MOVE_NOTE

    mover = world["people"]["endedProfessorAdmin"]
    destination = world["address"]("activeEmpanelmentAdmin")
    moved = _patch(client, world, "admin", mover.id, email=destination)
    assert moved.status_code == 200, moved.text
    [row] = _roster_rows_at(client, world, destination)
    assert row["isActive"] is False
    assert EMPANELMENT_CARRIED_BY_EMAIL_MOVE_NOTE in (row["notes"] or "")
