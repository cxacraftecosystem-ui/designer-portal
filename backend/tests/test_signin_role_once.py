"""The sign-in list rule at designer-portal's own doors (``services/signin_role``, 2026-10-18).

``tests/test_signin_role_contract.py`` holds the shared rule; this file drives the doors that apply
it here, over the contract's vectors, against fakes:

* the provider sign-in (``auth._sign_in_verified_mailbox``) creates a new account at the list's role
  and never writes the role of an existing one — neither from the allow-list nor from the designer
  roster — while the configured master admin address is still restored to MASTER_ADMIN;
* the approval (``access._role_kept``) never writes an account and says when one keeps its role;
* ``PATCH /api/users/{id}`` is the door that changes a role, and it writes the audit entry.

documentation-portal's file of the same name drives its own doors over the same vectors.
"""

from __future__ import annotations

import inspect
import json
import pathlib
from types import SimpleNamespace
from typing import Any

import pytest

from app.api.routes import access as access_routes, auth as auth_routes, users as users_routes
from app.core.config import get_settings
from app.services import signin_role

pytestmark = pytest.mark.anyio

ROOT = pathlib.Path(__file__).resolve().parents[2]
CONTRACT = json.loads((ROOT / "shared" / "signin-role-contract.json").read_text(encoding="utf-8"))
EMAIL = "person@example.org"


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


class _Users:
    """``db.user`` over at most one account, recording every write."""

    def __init__(self, account: Any | None):
        self.account = account
        self.updates: list[dict[str, Any]] = []
        self.creates: list[dict[str, Any]] = []

    async def find_unique(self, where):
        if self.account is not None and where in ({"email": self.account.email}, {"id": self.account.id}):
            return self.account
        return None

    async def update(self, where, data):
        self.updates.append(data)
        return SimpleNamespace(**{**vars(self.account), **data})

    async def create(self, data):
        self.creates.append(data)
        return SimpleNamespace(id="new-account", **data)


def _account(role: str, *, email: str = EMAIL) -> Any:
    return SimpleNamespace(
        id="acct-1", email=email, role=role, passwordHash=None, authProvider="GOOGLE", name="Person"
    )


def _wire(monkeypatch, users: _Users, *, listed: str | None, rostered: bool = False) -> None:
    access = SimpleNamespace(admitRole=listed, fullName=None, status="ACTIVE")

    async def _admits(_email, *, is_master):
        return access

    async def _roster(_email):
        return rostered

    async def _no_fold(_email):
        return None

    monkeypatch.setattr(auth_routes, "db", SimpleNamespace(user=users))
    monkeypatch.setattr(auth_routes, "assert_access_admits", _admits)
    monkeypatch.setattr(auth_routes, "roster_allows", _roster)
    monkeypatch.setattr(auth_routes, "_local_account_on_the_mailbox", _no_fold)
    monkeypatch.setattr(auth_routes, "invalidate_cached_user", lambda _id: None)


async def _sign_in(email: str = EMAIL) -> Any:
    user, _access = await auth_routes._sign_in_verified_mailbox(  # noqa: SLF001
        email, display_name="Person", avatar_url=None, provider="GOOGLE"
    )
    return user


@pytest.mark.parametrize(
    "vector", CONTRACT["existingAccountAtSignIn"], ids=lambda v: f"{v['current']}+{v['listed']}"
)
@pytest.mark.parametrize("rostered", [False, True], ids=["allow-list", "designer-roster"])
async def test_an_existing_account_signs_in_at_its_own_role(monkeypatch, vector, rostered):
    users = _Users(_account(vector["current"]))
    _wire(monkeypatch, users, listed=vector["listed"], rostered=rostered)
    signed_in = await _sign_in()
    assert signed_in.role == vector["expect"]
    assert all("role" not in data for data in users.updates), users.updates
    assert users.creates == []


@pytest.mark.parametrize("vector", CONTRACT["newAccountRole"], ids=lambda v: f"{v['floor']}+{v['listed']}")
async def test_a_new_account_is_created_at_the_lists_role(monkeypatch, vector):
    monkeypatch.setattr(get_settings(), "default_signup_role", vector["floor"])
    users = _Users(None)
    _wire(monkeypatch, users, listed=vector["listed"])
    created = await _sign_in()
    assert created.role == vector["expect"]
    assert users.updates == []


async def test_a_new_account_on_the_designer_roster_is_created_as_a_designer(monkeypatch):
    users = _Users(None)
    _wire(monkeypatch, users, listed=None, rostered=True)
    assert (await _sign_in()).role == "DESIGNER"


async def test_the_master_admin_address_is_still_restored(monkeypatch):
    """The break-glass is not the list: its sign-in writes MASTER_ADMIN, as it always did."""
    master = get_settings().master_admin_email or "master@example.org"
    monkeypatch.setattr(get_settings(), "master_admin_email", master)
    users = _Users(_account("ADMIN", email=master.lower()))
    _wire(monkeypatch, users, listed=None)
    assert (await _sign_in(master.lower())).role == "MASTER_ADMIN"


@pytest.mark.parametrize("vector", CONTRACT["roleKeptSentence"], ids=lambda v: f"{v['current']}+{v['listed']}")
async def test_an_approval_never_writes_an_account_and_says_when_it_keeps_its_role(monkeypatch, vector):
    users = _Users(_account(vector["current"], email=vector["email"]) if vector["current"] else None)
    monkeypatch.setattr(access_routes, "db", SimpleNamespace(user=users))
    said = await access_routes._role_kept(vector["email"], vector["listed"])  # noqa: SLF001
    assert said == vector["expect"]
    assert (users.updates, users.creates) == ([], [])


def test_the_users_patch_is_the_door_that_audits_a_change_of_role():
    source = inspect.getsource(users_routes.update_user)
    assert "signin_role.audit_role_change(" in source
    assert "before=role_before" in source
    assert 'via="users"' in source


def test_no_sign_in_door_writes_a_role_from_the_list():
    """Source guard: the only role the provider sign-in writes onto an existing account is the
    break-glass MASTER_ADMIN. A future ``data["role"] = granted`` would be the rule undone."""
    source = inspect.getsource(auth_routes._sign_in_verified_mailbox)  # noqa: SLF001
    writes = [line.strip() for line in source.splitlines() if 'data["role"]' in line]
    assert writes == ['data["role"] = "MASTER_ADMIN"'], writes
    assert "signin_role.role_for_new_account(" in source
    assert signin_role.NEW_ACCOUNTS_ONLY


@pytest.mark.parametrize("vector", CONTRACT["roleKeptSentence"], ids=lambda v: f"{v['current']}+{v['listed']}")
async def test_adding_an_address_never_writes_an_account_and_says_when_it_keeps_its_role(monkeypatch, vector):
    """Adding an address by hand admits it like an approval, so it answers the same sentence."""
    from app.schemas.access_roster import AccessRosterCreate

    users = _Users(_account(vector["current"], email=vector["email"]) if vector["current"] else None)

    class _Roster:
        async def find_first(self, where):
            return None

    async def _admit(email, *, admit_role, actor_id, full_name, note):
        return SimpleNamespace(email=email, admitRole=admit_role)

    async def _nothing(*_args, **_kwargs):
        return None

    async def _same(row):
        return row

    monkeypatch.setattr(access_routes, "db", SimpleNamespace(user=users, accessroster=_Roster()))
    monkeypatch.setattr(access_routes.access_roster, "admit", _admit)
    monkeypatch.setattr(access_routes.access_roster, "role_of", lambda row: row.admitRole)
    monkeypatch.setattr(access_routes, "_empanel_an_admitted_designer", _nothing)
    monkeypatch.setattr(access_routes, "with_roster_people", _same)
    monkeypatch.setattr(access_routes, "access_payload", lambda row: {"email": row.email})
    monkeypatch.setattr(access_routes, "assert_role", lambda *_args: None)
    actor = SimpleNamespace(id="admin-1", role="MASTER_ADMIN")

    answer = await access_routes.add_to_access_roster(
        AccessRosterCreate(email=vector["email"], role=vector["listed"]), current_user=actor
    )
    assert answer["accountPromotionHeld"] == vector["expect"]
    assert (users.updates, users.creates) == ([], [])


def test_the_sanction_register_audits_its_raise_only_after_the_commit():
    """Inside the transaction a rollback would leave a log line describing a change that never happened."""
    from app.services import sanction_orders

    source = inspect.getsource(sanction_orders.create_from_sanction)
    after_commit = source.index("PHASE 2: AFTER THE COMMIT")
    assert source.count("signin_role.audit_role_change(") == 1
    assert source.index("signin_role.audit_role_change(") > after_commit
    assert 'via="sanction-order"' in source[after_commit:]
