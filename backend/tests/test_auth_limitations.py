"""AUTH LIMITATIONS (2026-10-17): the two-step key, a first password's re-authentication, its own
code budget, and the forwarded-hop default.

1. ``TWO_FACTOR_ENCRYPTION_KEY`` — TOTP secrets sealed under their own key, versioned by a key id, so
   rotating ``JWT_SECRET`` no longer locks two-step users out. Legacy values still open; previous keys
   still open; a value moves to the current key on use and through
   ``scripts/reencrypt_two_factor_secrets.py``; the boot warns in the transition and fails only when
   told to.
2. A first password on a provider-only account needs a FRESH provider proof for THIS account — a
   Google ID token or a Microsoft/Yahoo code issued in the last five minutes — not a recent session.
3. Its second-step check spends its own five-per-fifteen-minutes budget, never the sign-in's (N3).
4. ``FORWARDED_CLIENT_HOPS`` defaults to 2.

The first half needs no database. The second follows the sync pattern ``tests/conftest.py``
describes: one ``asyncio.run`` seeds every row before the ``TestClient`` starts, and every later read
goes through ``client.portal``.
"""

from __future__ import annotations

import asyncio
import base64
import functools
import logging
import os
import time
import uuid
from datetime import UTC, datetime
from typing import Any

import pytest
from conftest import needs_db
from oidc_fake_idp import FakeIdP, login_body

from app.core.config import Settings, get_settings
from app.core.db import db
from app.core.deps import password_credential
from app.core.security import create_access_token
from app.scale import rate_limit
from app.services import mailer, two_factor

SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"


def _new_key() -> str:
    return base64.b64encode(os.urandom(32)).decode("ascii")


@pytest.fixture
def keys(monkeypatch):
    """Set the two key settings for one test; ``None`` means unset."""
    settings = get_settings()

    def configure(current: str | None, previous: str | None = None) -> None:
        monkeypatch.setattr(settings, "two_factor_encryption_key", current)
        monkeypatch.setattr(settings, "two_factor_previous_keys", previous)

    configure(None, None)
    return configure


# ==================================================================================================
# 1. The key — no database
# ==================================================================================================


def test_without_a_key_values_are_legacy_and_still_open(keys):
    stored = two_factor.encrypt_secret(SECRET)
    assert stored.startswith("v1.")
    assert two_factor.decrypt_secret(stored) == SECRET
    assert two_factor.is_current(stored), "with no key there is nothing better to move it to"


def test_with_a_key_values_carry_its_id_and_survive_a_jwt_secret_rotation(keys, monkeypatch):
    key = _new_key()
    keys(key)
    stored = two_factor.encrypt_secret(SECRET)
    kid = two_factor.key_id(two_factor.parse_key(key))
    assert stored.startswith(f"v2:{kid}.") and stored.count(".") == 3
    assert SECRET not in stored
    monkeypatch.setattr(get_settings(), "jwt_secret", "a-rotated-jwt-secret-" + "x" * 40)
    assert two_factor.decrypt_secret(stored) == SECRET, "JWT_SECRET no longer touches the key"


def test_legacy_ciphertext_still_opens_once_a_key_is_set_and_moves_to_it(keys):
    legacy = two_factor.encrypt_secret(SECRET)  # no key yet: v1 under JWT_SECRET
    keys(_new_key())
    assert two_factor.decrypt_secret(legacy) == SECRET
    assert not two_factor.is_current(legacy)
    moved = two_factor.reencrypted(legacy)
    assert moved is not None and moved.startswith("v2:")
    assert two_factor.decrypt_secret(moved) == SECRET
    assert two_factor.reencrypted(moved) is None, "already current: nothing to do"


def test_rotation_with_previous_keys(keys):
    old, new = _new_key(), _new_key()
    keys(old)
    under_old = two_factor.encrypt_secret(SECRET)

    keys(new, previous=f"{_new_key()}, {old}")
    assert two_factor.decrypt_secret(under_old) == SECRET, "a previous key still opens its values"
    assert not two_factor.is_current(under_old)
    under_new = two_factor.reencrypted(under_old)
    assert under_new is not None and two_factor.is_current(under_new)

    keys(new)  # the previous key dropped
    assert two_factor.decrypt_secret(under_old) is None, "dropped too early: unreadable, not a 500"
    assert two_factor.decrypt_secret(under_new) == SECRET


def test_a_value_cannot_be_relabelled_to_another_key(keys):
    first, second = _new_key(), _new_key()
    keys(first, previous=second)
    stored = two_factor.encrypt_secret(SECRET)
    _label, rest = stored.split(".", 1)
    other = two_factor.key_id(two_factor.parse_key(second))
    assert two_factor.decrypt_secret(f"v2:{other}.{rest}") is None
    version, iv, tag, body = stored.split(".")
    flipped = body[:-1] + ("A" if body[-1] != "A" else "B")
    assert two_factor.decrypt_secret(".".join((version, iv, tag, flipped))) is None


@pytest.mark.parametrize(
    "bad",
    [
        "not base64 at all!",
        base64.b64encode(b"sixteen-bytes-ab").decode(),
        base64.b64encode(os.urandom(31)).decode(),
    ],
)
def test_a_malformed_key_stops_the_boot_with_the_settings_name(keys, bad):
    keys(bad)
    with pytest.raises(RuntimeError, match="TWO_FACTOR_ENCRYPTION_KEY"):
        two_factor.verify_key_configuration()
    keys(_new_key(), previous=bad)
    with pytest.raises(RuntimeError, match="TWO_FACTOR_PREVIOUS_KEYS"):
        two_factor.verify_key_configuration()


def test_url_safe_and_unpadded_keys_are_the_same_key(keys):
    raw = os.urandom(32)
    standard = base64.b64encode(raw).decode()
    url_safe = base64.urlsafe_b64encode(raw).decode().rstrip("=")
    assert two_factor.parse_key(standard) == two_factor.parse_key(url_safe) == raw


def _census(**counts: int):
    async def census() -> two_factor.EncryptionCensus:
        values = {"enabled": 0, "current": 0, "previous": 0, "legacy": 0, "unreadable": 0}
        values.update(counts)
        return two_factor.EncryptionCensus(**values)

    return census


def test_boot_without_a_key_warns_and_carries_on(keys, monkeypatch, caplog):
    monkeypatch.setattr(two_factor, "encryption_census", _census(enabled=3, legacy=3))
    with caplog.at_level(logging.WARNING, logger="app.services.two_factor"):
        asyncio.run(two_factor.check_keys_at_boot())
    assert any("TWO_FACTOR_ENCRYPTION_KEY is not set" in r.message for r in caplog.records)


def test_boot_fails_loudly_when_the_key_is_required_and_missing(keys, monkeypatch):
    monkeypatch.setattr(get_settings(), "two_factor_key_required", True)
    monkeypatch.setattr(two_factor, "encryption_census", _census(enabled=2, current=2))
    with pytest.raises(RuntimeError, match="TWO_FACTOR_ENCRYPTION_KEY is not set"):
        asyncio.run(two_factor.check_keys_at_boot())
    # Nobody has two-step on: nothing to lock out, so even a required key does not stop the boot.
    monkeypatch.setattr(two_factor, "encryption_census", _census())
    asyncio.run(two_factor.check_keys_at_boot())


def test_boot_with_a_key_names_values_still_under_old_keys(keys, monkeypatch, caplog):
    keys(_new_key())
    monkeypatch.setattr(get_settings(), "two_factor_key_required", True)
    monkeypatch.setattr(two_factor, "encryption_census", _census(enabled=4, current=1, legacy=2, previous=1))
    with caplog.at_level(logging.WARNING, logger="app.services.two_factor"):
        asyncio.run(two_factor.check_keys_at_boot())
    assert any("reencrypt_two_factor_secrets" in r.message for r in caplog.records)


def test_the_forwarded_hop_default_is_two(monkeypatch):
    monkeypatch.delenv("FORWARDED_CLIENT_HOPS", raising=False)
    field = Settings.model_fields["forwarded_client_hops"]
    assert field.default == 2


def test_the_first_password_code_budget_is_five_on_its_own_counter():
    rate_limit.reset_account_credential_budget()
    account = f"acct-{uuid.uuid4().hex}"
    answers = [rate_limit.first_password_code_attempt(account)[0] for _ in range(6)]
    assert answers == [True] * 5 + [False]
    assert rate_limit.FIRST_PASSWORD_CODE_WINDOW_SECONDS == 900.0
    # The sign-in budget for the same account is untouched by all of that.
    assert rate_limit.account_credential_attempt(account)[0] is True
    rate_limit.reset_account_credential_budget()


# ==================================================================================================
# The database half
# ==================================================================================================

#: slug -> (provider, two-step on)
ACCOUNTS: dict[str, tuple[str, bool]] = {
    "fresh": ("GOOGLE", False),
    "stale": ("GOOGLE", False),
    "mismatch": ("GOOGLE", False),
    "microsoft": ("MICROSOFT", False),
    "microsoftStale": ("MICROSOFT", False),
    "twoStep": ("GOOGLE", True),
    "budget": ("GOOGLE", True),
    "rekey": ("GOOGLE", True),
    "backfillA": ("GOOGLE", True),
    "backfillB": ("GOOGLE", True),
    "yahoo": ("YAHOO", False),
    "ordering": ("GOOGLE", True),
    "microsoftTwoStep": ("MICROSOFT", True),
    "locked": ("GOOGLE", True),
}


#: This module's accounts holding a value sealed under a (test-only, random) key.
_SEEDED_SEALED: dict[str, Any] = {
    "email": {"startswith": "authlim-"},
    "OR": [{"twoFactorSecret": {"startswith": "v2:"}}, {"twoFactorPendingSecret": {"startswith": "v2:"}}],
}
_UNSEALED: dict[str, Any] = {"twoFactorSecret": None, "twoFactorPendingSecret": None, "twoFactorEnabled": False}


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        return f"authlim-{slug.lower()}-{stamp}@example.org"

    async def seed() -> dict[str, Any]:
        await db.connect()
        try:
            # An interrupted earlier run can leave values sealed under a random key no longer
            # configured; the boot rightly refuses those (the key was lost), so clear them first.
            await db.user.update_many(where=_SEEDED_SEALED, data=_UNSEALED)
            now = datetime.now(UTC)
            people: dict[str, Any] = {}
            for slug, (provider, two_step) in ACCOUNTS.items():
                data: dict[str, Any] = {
                    "email": address(slug),
                    "name": f"AuthLim {slug}",
                    "role": "RESEARCHER",
                    "passwordHash": None,
                    "authProvider": provider,
                }
                if two_step:
                    # Legacy ciphertext — what every account with two-step on holds today.
                    data |= {
                        "twoFactorEnabled": True,
                        "twoFactorSecret": two_factor.encrypt_secret(SECRET),
                        "twoFactorEnabledAt": now,
                    }
                people[slug] = await db.user.create(data=data)
                await db.accessroster.create(
                    data={
                        "email": address(slug),
                        "status": "ACTIVE",
                        "admitRole": "RESEARCHER",
                        "joinedAt": now,
                        "notes": "Seeded by tests/test_auth_limitations.py.",
                    }
                )
            return people
        finally:
            await db.disconnect()

    if get_settings().two_factor_encryption_key:
        pytest.skip("the seed writes legacy values; run without TWO_FACTOR_ENCRYPTION_KEY set")
    people = asyncio.run(seed())
    with TestClient(app) as client:
        yield {"client": client, "people": people, "address": address}
        # Tests here seal values under random keys; left behind, they would stop the next boot.
        _read(client, db.user.update_many, where=_SEEDED_SEALED, data=_UNSEALED)


@pytest.fixture
def mail_on(monkeypatch):
    monkeypatch.setattr(mailer, "mail_configured", lambda settings=None: True)


def _read(client: Any, call: Any, **kwargs: Any) -> Any:
    return client.portal.call(functools.partial(call, **kwargs))


def _as(world: dict[str, Any], slug: str) -> dict[str, str]:
    person = world["people"][slug]
    token = create_access_token(subject=person.id, credential=password_credential(person))
    return {"Authorization": f"Bearer {token}"}


def _google(monkeypatch: Any, email: str, *, age_seconds: int = 0, verified: bool = True) -> dict[str, str]:
    from app.api.routes import auth as auth_routes

    claims = {"email": email, "email_verified": verified, "iat": int(time.time()) - age_seconds, "sub": "g-1"}
    monkeypatch.setattr(auth_routes, "verify_google_token", lambda _token: claims)
    return {"googleIdToken": "a-google-id-token"}


def _first_password(world: dict[str, Any], slug: str, **body: Any) -> Any:
    return world["client"].post(
        "/api/auth/change-password",
        json={"newPassword": f"first-password-{slug}-1", **body},
        headers=_as(world, slug),
    )


def _row(world: dict[str, Any], slug: str) -> Any:
    return _read(world["client"], db.user.find_unique, where={"id": world["people"][slug].id})


# ── 2. a first password needs a fresh provider proof ──────────────────────────────────────────────


@needs_db
def test_no_proof_is_asked_to_confirm_with_the_provider(world):
    refused = _first_password(world, "stale")
    assert refused.status_code == 400
    assert refused.headers.get("x-confirm-provider") == "GOOGLE"
    assert "Google" in refused.json()["detail"]
    assert _row(world, "stale").passwordHash is None


@needs_db
def test_a_stale_google_token_is_refused(world, monkeypatch):
    refused = _first_password(
        world, "stale", **_google(monkeypatch, world["address"]("stale"), age_seconds=6 * 60)
    )
    assert refused.status_code == 400
    assert refused.headers.get("x-confirm-provider") == "GOOGLE"
    assert _row(world, "stale").passwordHash is None


@needs_db
def test_a_google_token_for_another_mailbox_is_refused(world, monkeypatch):
    other = _google(monkeypatch, "somebody-else@example.org")
    refused = _first_password(world, "mismatch", **other)
    assert refused.status_code == 400
    assert refused.headers.get("x-confirm-provider") == "GOOGLE"
    unverified = _google(monkeypatch, world["address"]("mismatch"), verified=False)
    assert _first_password(world, "mismatch", **unverified).status_code == 400
    assert _row(world, "mismatch").passwordHash is None


@needs_db
def test_a_google_token_that_does_not_verify_is_a_400_not_a_401(world, monkeypatch):
    from fastapi import HTTPException

    from app.api.routes import auth as auth_routes

    def refuse(_token: str) -> dict[str, Any]:
        raise HTTPException(status_code=401, detail="Google sign-in didn't work. Try again.")

    monkeypatch.setattr(auth_routes, "verify_google_token", refuse)
    refused = _first_password(world, "mismatch", googleIdToken="forged")
    assert refused.status_code == 400, "a 401 would sign the web client out of its own session"
    assert refused.headers.get("x-confirm-provider") == "GOOGLE"


@needs_db
def test_a_fresh_google_token_sets_the_password_and_tells_the_owner(world, monkeypatch, mail_on):
    answer = _first_password(world, "fresh", **_google(monkeypatch, world["address"]("fresh")))
    assert answer.status_code == 200, answer.text
    assert answer.headers.get("x-session-token")
    assert _row(world, "fresh").passwordHash is not None
    kinds = [
        row.kind
        for row in _read(
            world["client"], db.emailmessage.find_many, where={"toAddress": world["address"]("fresh")}
        )
    ]
    assert mailer.PASSWORD_ADDED in kinds


@needs_db
def test_a_fresh_microsoft_code_sets_the_password(world, monkeypatch):
    idp = FakeIdP().install(monkeypatch)
    idp.issue(idp.sign(idp.microsoft_claims(world["address"]("microsoft"))))
    answer = _first_password(world, "microsoft", **login_body("MICROSOFT"))
    assert answer.status_code == 200, answer.text
    assert _row(world, "microsoft").passwordHash is not None


@needs_db
def test_a_stale_or_mismatched_microsoft_code_is_refused(world, monkeypatch):
    idp = FakeIdP().install(monkeypatch)
    old = int(time.time()) - 10 * 60
    idp.issue(idp.sign(idp.microsoft_claims(world["address"]("microsoftStale"), iat=old, nbf=old)))
    stale = _first_password(world, "microsoftStale", **login_body("MICROSOFT"))
    assert stale.status_code == 400
    assert stale.headers.get("x-confirm-provider") == "MICROSOFT"
    idp.issue(idp.sign(idp.microsoft_claims("another-person@example.org")))
    other = _first_password(world, "microsoftStale", **login_body("MICROSOFT"))
    assert other.status_code == 400
    assert _row(world, "microsoftStale").passwordHash is None


# ── 3. the second step, on its own budget ─────────────────────────────────────────────────────────


def _wrong(code: str) -> str:
    return "000000" if code != "000000" else "111111"


@needs_db
def test_a_two_step_account_needs_the_code_after_the_proof(world, monkeypatch, keys):
    proof = _google(monkeypatch, world["address"]("twoStep"))
    missing = _first_password(world, "twoStep", **proof)
    assert missing.status_code == 400
    assert missing.headers.get("x-two-factor-required") == "1"
    wrong = _first_password(world, "twoStep", **proof, twoFactorCode=_wrong(two_factor.current_totp(SECRET)))
    assert wrong.status_code == 400
    assert wrong.json()["detail"] == two_factor.WRONG_CODE_DETAIL
    assert _row(world, "twoStep").passwordHash is None
    right = _first_password(world, "twoStep", **proof, twoFactorCode=two_factor.current_totp(SECRET))
    assert right.status_code == 200, right.text


@needs_db
def test_wrong_codes_here_spend_their_own_budget_and_never_lock_sign_in(world, monkeypatch):
    proof = _google(monkeypatch, world["address"]("budget"))
    wrong = _wrong(two_factor.current_totp(SECRET))
    statuses = [
        _first_password(world, "budget", **proof, twoFactorCode=wrong).status_code for _ in range(6)
    ]
    assert statuses == [400] * 5 + [429]
    row = _row(world, "budget")
    assert row.passwordHash is None
    assert (row.twoFactorFailedAttempts or 0) == 0, "the sign-in's code budget was spent"
    assert (row.twoFactorRecoveryFailedAttempts or 0) == 0
    assert not two_factor.is_locked(row) and not two_factor.fully_locked(row)
    # Even the right code waits for the window; the sign-in door is open throughout.
    locked_out = _first_password(world, "budget", **proof, twoFactorCode=two_factor.current_totp(SECRET))
    assert locked_out.status_code == 429


# ── 1, against the database: re-encryption on use, and the backfill ─────────────────────────────


@needs_db
def test_a_right_code_moves_a_legacy_secret_to_the_current_key(world, monkeypatch, keys):
    keys(_new_key())
    before = _row(world, "rekey").twoFactorSecret
    assert before.startswith("v1.")
    proof = _google(monkeypatch, world["address"]("rekey"))
    answer = _first_password(world, "rekey", **proof, twoFactorCode=two_factor.current_totp(SECRET))
    assert answer.status_code == 200, answer.text
    after = _row(world, "rekey").twoFactorSecret
    assert after.startswith("v2:") and two_factor.is_current(after)
    assert two_factor.decrypt_secret(after) == SECRET


@needs_db
def test_the_backfill_is_a_dry_run_by_default_and_idempotent(world, keys):
    from scripts import reencrypt_two_factor_secrets as backfill

    client = world["client"]
    ids = [world["people"]["backfillA"].id, world["people"]["backfillB"].id]
    # One of the two also holds a pending setup, under the legacy key too.
    _read(
        client,
        db.user.update,
        where={"id": ids[1]},
        data={"twoFactorPendingSecret": two_factor.encrypt_secret(SECRET)},
    )
    keys(_new_key())

    dry = _read(client, backfill.run, apply=False, only_ids=ids)
    assert (dry.would_move, dry.moved, dry.current) == (3, 0, 0)
    assert all(_row(world, s).twoFactorSecret.startswith("v1.") for s in ("backfillA", "backfillB"))

    applied = _read(client, backfill.run, apply=True, only_ids=ids)
    assert (applied.moved, applied.unreadable, applied.raced) == (3, 0, 0)
    for slug in ("backfillA", "backfillB"):
        row = _row(world, slug)
        assert two_factor.is_current(row.twoFactorSecret)
        assert two_factor.decrypt_secret(row.twoFactorSecret) == SECRET
    assert two_factor.decrypt_secret(_row(world, "backfillB").twoFactorPendingSecret) == SECRET

    again = _read(client, backfill.run, apply=True, only_ids=ids)
    assert (again.moved, again.current) == (0, 3), "a second run changes nothing"


@needs_db
def test_the_backfill_prints_counts_only_and_refuses_without_a_key(keys, capsys):
    from scripts import reencrypt_two_factor_secrets as backfill

    assert asyncio.run(backfill.main([])) == 2
    out = capsys.readouterr().out
    assert "not set" in out and "@" not in out


# ==================================================================================================
# Review follow-ups (2026-10-17)
# ==================================================================================================


def test_boot_fails_without_a_key_when_values_were_sealed_under_one(keys, monkeypatch):
    # TWO_FACTOR_KEY_REQUIRED is off: a release .env that lost the key also lost the flag.
    monkeypatch.setattr(get_settings(), "two_factor_key_required", False)
    monkeypatch.setattr(two_factor, "encryption_census", _census(enabled=2, unreadable=2, versioned=2))
    with pytest.raises(RuntimeError, match="key was lost"):
        asyncio.run(two_factor.check_keys_at_boot())


def test_the_census_counts_versioned_values_even_when_they_no_longer_open(keys, monkeypatch):
    keys(_new_key())
    sealed = two_factor.encrypt_secret(SECRET)
    keys(None)
    row = type("Row", (), {"twoFactorEnabled": True, "twoFactorSecret": sealed, "twoFactorPendingSecret": None})

    async def find_many(**_kwargs: Any) -> list[Any]:
        return [row]

    from types import SimpleNamespace

    monkeypatch.setattr(two_factor, "db", SimpleNamespace(user=SimpleNamespace(find_many=find_many)))
    census = asyncio.run(two_factor.encryption_census())
    assert (census.versioned, census.unreadable) == (1, 1)


def test_a_proof_issued_beyond_the_future_leeway_is_not_fresh():
    from app.api.routes import auth as auth_routes

    now = time.time()
    assert auth_routes._issued_recently(now + auth_routes.PROVIDER_PROOF_LEEWAY_SECONDS - 1, now)
    assert not auth_routes._issued_recently(now + auth_routes.PROVIDER_PROOF_LEEWAY_SECONDS + 5, now)
    assert not auth_routes._issued_recently(True, now), "a bool is not a timestamp"


def test_a_hop_count_without_cloudfront_is_warned_once(monkeypatch, caplog):
    monkeypatch.setattr(rate_limit, "_hop_mismatch_logged", False)
    monkeypatch.setattr(get_settings(), "forwarded_client_hops", 2)
    direct = {"headers": [(b"x-forwarded-for", b"1.2.3.4, 5.6.7.8")], "client": ("127.0.0.1", 1)}
    via_edge = {"headers": [(b"x-forwarded-for", b"1.2.3.4, 5.6.7.8"), (b"x-amz-cf-id", b"abc")]}
    with caplog.at_level(logging.WARNING, logger="app.scale.rate_limit"):
        assert rate_limit.client_address(via_edge) == "ip:1.2.3.4"
        assert not caplog.records
        rate_limit.client_address(direct)
        rate_limit.client_address(direct)
    assert len([r for r in caplog.records if "FORWARDED_CLIENT_HOPS" in r.message]) == 1


@needs_db
def test_a_google_token_issued_in_the_future_is_refused(world, monkeypatch):
    early = _google(monkeypatch, world["address"]("mismatch"), age_seconds=-5 * 60)
    refused = _first_password(world, "mismatch", **early)
    assert refused.status_code == 400
    assert refused.headers.get("x-confirm-provider") == "GOOGLE"
    assert _row(world, "mismatch").passwordHash is None


@needs_db
def test_a_fresh_yahoo_code_sets_the_password(world, monkeypatch):
    idp = FakeIdP().install(monkeypatch)
    idp.issue(idp.sign(idp.yahoo_claims(world["address"]("yahoo"))))
    answer = _first_password(world, "yahoo", **login_body("YAHOO"))
    assert answer.status_code == 200, answer.text
    assert _row(world, "yahoo").passwordHash is not None


@needs_db
def test_a_code_without_a_proof_spends_nothing(world):
    rate_limit.reset_account_credential_budget()
    wrong = _wrong(two_factor.current_totp(SECRET))
    for _ in range(6):
        refused = _first_password(world, "ordering", twoFactorCode=wrong)
        assert refused.status_code == 400
        assert refused.headers.get("x-confirm-provider") == "GOOGLE"
    account = world["people"]["ordering"].id
    assert [rate_limit.first_password_code_attempt(account)[0] for _ in range(5)] == [True] * 5
    rate_limit.reset_account_credential_budget()


@needs_db
def test_a_microsoft_proof_survives_a_wrong_code_without_a_second_redemption(world, monkeypatch):
    rate_limit.reset_account_credential_budget()
    idp = FakeIdP().install(monkeypatch)
    idp.issue(idp.sign(idp.microsoft_claims(world["address"]("microsoftTwoStep"))))
    # One session throughout, as a client holds one token: the remembered proof is bound to it.
    session = _as(world, "microsoftTwoStep")

    def send(code: str) -> Any:
        return world["client"].post(
            "/api/auth/change-password",
            json={"newPassword": "first-password-ms-1", **login_body("MICROSOFT", code="single-use"), "twoFactorCode": code},
            headers=session,
        )

    wrong = send(_wrong(two_factor.current_totp(SECRET)))
    assert wrong.status_code == 400
    assert wrong.headers.get("x-two-factor-required") == "1"
    # The provider would refuse the code a second time.
    idp.next_answer = (400, {"error": "invalid_grant"})
    right = send(two_factor.current_totp(SECRET))
    assert right.status_code == 200, right.text
    assert len(idp.redemptions) == 1
    rate_limit.reset_account_credential_budget()


@needs_db
def test_a_locked_sign_in_also_holds_the_first_password_door(world, monkeypatch):
    from datetime import timedelta

    from app.core.deps import invalidate_cached_user

    rate_limit.reset_account_credential_budget()
    person = world["people"]["locked"]
    _read(
        world["client"],
        db.user.update,
        where={"id": person.id},
        data={"twoFactorLockedUntil": datetime.now(UTC) + timedelta(minutes=10)},
    )
    invalidate_cached_user(person.id)
    proof = _google(monkeypatch, world["address"]("locked"))
    refused = _first_password(world, "locked", **proof, twoFactorCode=two_factor.current_totp(SECRET))
    assert refused.status_code == 429
    assert int(refused.headers["retry-after"]) > 60
    assert _row(world, "locked").passwordHash is None
    assert [rate_limit.first_password_code_attempt(person.id)[0] for _ in range(5)] == [True] * 5
    rate_limit.reset_account_credential_budget()
