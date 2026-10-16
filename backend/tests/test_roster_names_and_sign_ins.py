"""Who added a roster row, in words, and when an account last signed in (2026-10-17).

* The access roster and the designer roster sent only ``addedById``, so "Added by" on both clients
  could print nothing better than an id. The list routes now load the accounts beside each page in
  one batched read and the payloads carry ``addedByName`` (and ``decidedByName`` on the access list).
* ``User.lastLoginAt`` is stamped on every successful sign-in, beside the once-only ``firstLoginAt``,
  so Manage users can say when somebody last got in. It rides out on ``serialize_user`` like every
  other column.

No database: the payloads are pure, and the routes' use of the relations is a question of what they
pass, which the source answers.
"""

from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace

from app.services import access_roster
from app.services.designers import roster_payload

ROUTES = Path(__file__).resolve().parents[1] / "app" / "api" / "routes"


def _access_row(**extra):
    base = {
        "id": "ar-1", "email": "asha@example.org", "fullName": "Asha", "status": "ACTIVE",
        "admitRole": None, "joinedAt": None, "requestedAt": None, "attemptCount": 0,
        "lastAttemptAt": None, "decidedAt": None, "decidedById": "u-9", "firstSeenAt": None,
        "notes": None, "createdAt": datetime(2026, 10, 1, tzinfo=UTC), "updatedAt": None,
        "addedById": "u-1",
    }
    base.update(extra)
    return SimpleNamespace(**base)


def test_the_access_payload_names_who_added_and_who_decided():
    row = _access_row(
        addedBy=SimpleNamespace(name="Meera Rao", email="meera@example.org"),
        decidedBy=SimpleNamespace(name="  ", email="admin@example.org"),
    )
    payload = access_roster.access_payload(row)
    assert payload["addedById"] == "u-1"
    assert payload["addedByName"] == "Meera Rao"
    # A blank name falls back to the address rather than printing nothing.
    assert payload["decidedByName"] == "admin@example.org"


def test_an_unloaded_or_deleted_account_is_null_never_an_id():
    payload = access_roster.access_payload(_access_row(addedBy=None))
    assert payload["addedByName"] is None
    assert payload["decidedByName"] is None


def test_the_designer_roster_payload_names_who_empanelled():
    row = SimpleNamespace(
        id="dr-1", email="asha@example.org", fullName="Asha", institution=None, notes=None,
        isActive=True, revokedAt=None, firstSeenAt=None, createdAt=None, updatedAt=None,
        addedById="u-1", addedBy=SimpleNamespace(name="Meera Rao", email="meera@example.org"),
    )
    assert roster_payload(row)["addedByName"] == "Meera Rao"


def test_both_roster_lists_load_the_accounts_with_the_page():
    access = (ROUTES / "access.py").read_text(encoding="utf-8")
    assert 'Relation("addedBy", "user", "addedById")' in access
    assert 'Relation("decidedBy", "user", "decidedById")' in access
    assert "relations=ROSTER_PEOPLE" in access
    designers = (ROUTES / "designers.py").read_text(encoding="utf-8")
    assert 'relations=(Relation("addedBy", "user", "addedById"),)' in designers


def test_every_sign_in_stamps_the_last_time_and_the_first_only_once():
    source = (ROUTES / "auth.py").read_text(encoding="utf-8")
    body = source[source.index("async def open_session(") :]
    body = body[: body.index("\nasync def ", 1)]
    assert '{"lastLoginAt": signed_in_at}' in body
    assert 'stamps["firstLoginAt"] = signed_in_at' in body
    # The first-login stamp stays conditional; the last-login one is not.
    assert 'if getattr(user, "firstLoginAt", None) is None:' in body


def test_last_login_is_a_user_column_and_reaches_clients():
    schema = (Path(__file__).resolve().parents[1] / "prisma" / "schema.prisma").read_text(encoding="utf-8")
    user = schema[schema.index("model User {") :]
    user = user[: user.index("\n}\n")] if "\n}\n" in user else user[: user.index("\r\n}\r\n")]
    assert "lastLoginAt        DateTime?" in user
    from app.api.routes.users import serialize_user

    when = datetime(2026, 10, 17, 9, 0, tzinfo=UTC)
    payload = serialize_user({"id": "u-1", "lastLoginAt": when, "passwordHash": "x"})
    assert payload["lastLoginAt"] == when.isoformat()
    assert "passwordHash" not in payload
