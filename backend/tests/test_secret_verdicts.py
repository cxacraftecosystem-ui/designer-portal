"""Test verdicts for provider keys that come from the ENVIRONMENT, and what must not be stored.

An engine is frozen at the bottom of the transcription ranking until its API key has been tested and
passes. That verdict was only ever durable for keys typed into the Settings hub, because those have
a ``ManagedSecret`` row to write it on. Production supplies every key from the environment, where
there is no row — so the verdict lived in process memory and every deploy told the admins that
nothing had been verified.

``SecretTestResult`` fixes that, and the tests below are mostly about the two ways it could be wrong
rather than the way it is right: it must not become a place where key material ends up, and it must
not let a rotated key inherit the pass its predecessor earned.
"""

import asyncio
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

import pytest

from app.services import app_settings, managed_secrets

ELEVENLABS = "ELEVENLABS_API_KEY"
A_KEY = "sk_eleven_9f3c1d77b2a84e6091cc55de10a7f4b8"
ANOTHER_KEY = "sk_eleven_00000000000000000000000000000000"


class _Table:
    """An in-memory stand-in for one Prisma model, keyed the way the real code queries it."""

    def __init__(self, pk: str = "key") -> None:
        self.pk = pk
        self.rows: dict[str, SimpleNamespace] = {}
        self.writes = 0

    async def find_many(self, **_):
        return list(self.rows.values())

    async def find_unique(self, where: dict, **_):
        return self.rows.get(where[self.pk])

    async def upsert(self, where: dict, data: dict):
        self.writes += 1
        key = where[self.pk]
        if key in self.rows:
            for field, value in data["update"].items():
                setattr(self.rows[key], field, value)
        else:
            self.rows[key] = SimpleNamespace(**data["create"])
        return self.rows[key]

    async def update(self, where: dict, data: dict):
        self.writes += 1
        row = self.rows[where[self.pk]]
        for field, value in data.items():
            setattr(row, field, value)
        return row

    async def delete_many(self, where: dict):
        self.writes += 1
        self.rows.pop(where[self.pk], None)


def _managed_row(key: str, **overrides) -> SimpleNamespace:
    row = SimpleNamespace(
        key=key,
        valueEnc=managed_secrets.encrypt(A_KEY),
        hint="f4b8",
        description="",
        lastStatus="UNKNOWN",
        lastCheckedAt=None,
        lastError=None,
        updatedById=None,
        updatedBy=None,
        updatedAt=datetime(2026, 7, 26, tzinfo=UTC),
    )
    for field, value in overrides.items():
        setattr(row, field, value)
    return row


class _Stack:
    def __init__(self, secrets: _Table, verdicts: _Table, knobs: SimpleNamespace) -> None:
        self.secrets = secrets
        self.verdicts = verdicts
        self.knobs = knobs

    def rotate_environment_key(self, value: str | None) -> None:
        self.knobs.elevenlabs_api_key = value


@pytest.fixture
def stack(monkeypatch: pytest.MonkeyPatch):
    """managed_secrets wired to in-memory tables, a stubbed environment and a scriptable probe."""

    def build(*, env_key: str | None = A_KEY, probe: tuple[bool, str | None] = (True, None)) -> _Stack:
        secrets, verdicts = _Table(), _Table()
        knobs = SimpleNamespace(
            elevenlabs_api_key=env_key,
            openai_api_key=None,
            deepgram_api_key=None,
            gemini_api_key=None,
            gemini_api_keys_raw="",
            maptiler_api_key=None,
            google_client_id=None,
            secrets_encryption_key=None,
            jwt_secret="a-local-test-signing-secret-0123456789",
        )
        monkeypatch.setattr(
            managed_secrets, "db", SimpleNamespace(managedsecret=secrets, secrettestresult=verdicts)
        )
        monkeypatch.setattr(managed_secrets, "get_settings", lambda: knobs)
        monkeypatch.setattr(managed_secrets, "_safe_probe", lambda spec, value: probe)
        managed_secrets.invalidate()
        return _Stack(secrets, verdicts, knobs)

    managed_secrets._fernet.cache_clear()
    managed_secrets._fingerprint_pepper.cache_clear()
    app_settings.forget_key_verdicts()
    yield build
    managed_secrets._fernet.cache_clear()
    managed_secrets._fingerprint_pepper.cache_clear()
    managed_secrets.invalidate()
    app_settings.forget_key_verdicts()


# --- What must never reach the database -----------------------------------------------------------


def test_a_stored_verdict_contains_no_trace_of_the_key(stack) -> None:
    s = stack()

    asyncio.run(managed_secrets.test_secret(ELEVENLABS))

    row = s.verdicts.rows[ELEVENLABS]
    stored = " ".join(str(value) for value in vars(row).values())
    assert A_KEY not in stored
    # Not a prefix, not a suffix, not any recognisable run of it either.
    for length in (8, 12, 16):
        assert A_KEY[:length] not in stored
        assert A_KEY[-length:] not in stored
    assert set(vars(row)) == {"key", "status", "checkedAt", "error", "fingerprint"}


def test_the_fingerprint_is_peppered_so_a_guessed_key_cannot_be_confirmed_from_a_dump(stack) -> None:
    """An unkeyed digest of a structured key is a guessing oracle; a keyed one is not."""
    import hashlib

    s = stack()
    digest = managed_secrets.fingerprint(A_KEY)

    assert digest != hashlib.sha256(A_KEY.encode()).hexdigest()
    assert digest != hashlib.sha256(A_KEY.encode()).hexdigest().upper()
    # The pepper is what an attacker with the table lacks: change it and the same key hashes
    # differently, so the digest cannot be recomputed without the server's secret.
    s.knobs.jwt_secret = "a-completely-different-signing-secret-9876"
    managed_secrets._fingerprint_pepper.cache_clear()
    assert managed_secrets.fingerprint(A_KEY) != digest


def test_the_fingerprint_is_stable_for_one_value_and_distinct_between_values(stack) -> None:
    stack()

    assert managed_secrets.fingerprint(A_KEY) == managed_secrets.fingerprint(A_KEY)
    assert managed_secrets.fingerprint(A_KEY) != managed_secrets.fingerprint(ANOTHER_KEY)


def test_testing_an_environment_key_never_creates_a_managed_secret_override(stack) -> None:
    """The reason this table exists: a row in ManagedSecret would change which value is SENT."""
    s = stack()

    described = asyncio.run(managed_secrets.test_secret(ELEVENLABS))

    assert s.secrets.rows == {}
    assert s.secrets.writes == 0
    assert described["source"] == managed_secrets.SOURCE_ENVIRONMENT


# --- What the verdict must survive ------------------------------------------------------------------


def test_a_passing_verdict_on_an_environment_key_outlives_the_process(stack) -> None:
    s = stack()
    asyncio.run(managed_secrets.test_secret(ELEVENLABS))
    stored = dict(vars(s.verdicts.rows[ELEVENLABS]))

    # A restart: nothing in memory, the same environment, the same table.
    fresh = stack()
    fresh.verdicts.rows[ELEVENLABS] = SimpleNamespace(**stored)
    app_settings.forget_key_verdicts()

    described = asyncio.run(managed_secrets.describe_secret(ELEVENLABS))
    assert described["lastStatus"] == "OK"
    assert described["lastCheckedAt"] == stored["checkedAt"]
    assert described["source"] == managed_secrets.SOURCE_ENVIRONMENT


def test_a_failing_verdict_persists_with_its_reason(stack) -> None:
    s = stack(probe=(False, "Key rejected by the provider (HTTP 401)"))

    described = asyncio.run(managed_secrets.test_secret(ELEVENLABS))

    assert described["lastStatus"] == "FAILED"
    assert s.verdicts.rows[ELEVENLABS].status == "FAILED"
    assert s.verdicts.rows[ELEVENLABS].error == "Key rejected by the provider (HTTP 401)"


def test_the_listing_shows_the_stored_verdict_for_an_environment_key(stack) -> None:
    s = stack()
    asyncio.run(managed_secrets.test_secret(ELEVENLABS))
    s.verdicts.writes = 0

    listed = {entry["key"]: entry for entry in asyncio.run(managed_secrets.list_managed_secrets())}

    assert listed[ELEVENLABS]["lastStatus"] == "OK"
    # A key with no verdict is still UNKNOWN, not accidentally inheriting another key's.
    assert listed["DEEPGRAM_API_KEY"]["lastStatus"] == "UNKNOWN"
    assert s.verdicts.writes == 0, "reading the panel must not write"


# --- What the verdict must NOT survive --------------------------------------------------------------


def test_rotating_the_environment_key_invalidates_its_verdict(stack) -> None:
    s = stack()
    asyncio.run(managed_secrets.test_secret(ELEVENLABS))
    assert asyncio.run(managed_secrets.describe_secret(ELEVENLABS))["lastStatus"] == "OK"

    s.rotate_environment_key(ANOTHER_KEY)  # a deploy with a new key in the unit file

    described = asyncio.run(managed_secrets.describe_secret(ELEVENLABS))
    assert described["lastStatus"] == "UNKNOWN"
    assert described["lastCheckedAt"] is None
    assert asyncio.run(managed_secrets.environment_verdicts()) == {}


def test_removing_the_environment_key_leaves_nothing_verified(stack) -> None:
    s = stack()
    asyncio.run(managed_secrets.test_secret(ELEVENLABS))

    s.rotate_environment_key(None)

    assert asyncio.run(managed_secrets.environment_verdict(ELEVENLABS)) is None


def test_a_verdict_written_under_a_different_pepper_is_ignored_not_trusted(stack) -> None:
    """A JWT_SECRET rotation must re-freeze the engines, not silently keep vouching for them."""
    s = stack()
    asyncio.run(managed_secrets.test_secret(ELEVENLABS))

    s.knobs.jwt_secret = "the-operator-rotated-the-signing-secret-1234"
    managed_secrets._fingerprint_pepper.cache_clear()

    assert asyncio.run(managed_secrets.environment_verdict(ELEVENLABS)) is None


def test_putting_the_key_back_makes_the_old_verdict_true_again(stack) -> None:
    """Deliberate: the verdict is about a VALUE, so restoring that value restores its meaning."""
    s = stack()
    asyncio.run(managed_secrets.test_secret(ELEVENLABS))

    s.rotate_environment_key(ANOTHER_KEY)
    assert asyncio.run(managed_secrets.environment_verdict(ELEVENLABS)) is None
    s.rotate_environment_key(A_KEY)

    assert asyncio.run(managed_secrets.environment_verdict(ELEVENLABS))["status"] == "OK"


# --- The database-backed path, unchanged --------------------------------------------------------------


def test_a_ui_entered_key_still_records_its_verdict_on_its_own_row(stack) -> None:
    s = stack()
    s.secrets.rows[ELEVENLABS] = _managed_row(ELEVENLABS)

    described = asyncio.run(managed_secrets.test_secret(ELEVENLABS))

    assert described["source"] == managed_secrets.SOURCE_DATABASE
    assert s.secrets.rows[ELEVENLABS].lastStatus == "OK"
    # ...and it did NOT also write an environment verdict, which would be about a different value.
    assert s.verdicts.rows == {}


def test_saving_a_key_through_the_ui_resets_its_verdict_as_before(stack) -> None:
    s = stack()
    s.secrets.rows[ELEVENLABS] = _managed_row(ELEVENLABS, lastStatus="OK")

    asyncio.run(managed_secrets.set_secret(ELEVENLABS, ANOTHER_KEY, None))

    assert s.secrets.rows[ELEVENLABS].lastStatus == "UNKNOWN"
    assert s.secrets.rows[ELEVENLABS].lastCheckedAt is None


def test_an_override_hides_the_environment_verdict_and_deleting_it_gives_it_back(stack) -> None:
    s = stack()
    asyncio.run(managed_secrets.test_secret(ELEVENLABS))  # environment verdict on file

    s.secrets.rows[ELEVENLABS] = _managed_row(ELEVENLABS)
    assert asyncio.run(managed_secrets.describe_secret(ELEVENLABS))["lastStatus"] == "UNKNOWN"

    del s.secrets.rows[ELEVENLABS]
    assert asyncio.run(managed_secrets.describe_secret(ELEVENLABS))["lastStatus"] == "OK"


# --- The freeze the whole thing exists for ------------------------------------------------------------


def test_a_persisted_verdict_leaves_the_engine_rankable_after_a_restart(monkeypatch, stack) -> None:
    """End of the chain: SecretTestResult -> describe -> _provider_verification -> not frozen."""
    from app.api.routes import settings as settings_route

    s = stack()
    checked_at = datetime.now(UTC) - timedelta(days=3)  # tested before the last deploy
    s.verdicts.rows[ELEVENLABS] = SimpleNamespace(
        key=ELEVENLABS,
        status="OK",
        checkedAt=checked_at,
        error=None,
        fingerprint=managed_secrets.fingerprint(A_KEY),
    )
    monkeypatch.setattr(
        settings_route.ai,
        "transcription_provider_configured",
        lambda: {"elevenlabs": True, "deepgram": False, "whisper": False},
    )

    verification = asyncio.run(settings_route._provider_verification())

    assert verification["elevenlabs"]["keyState"] == app_settings.STT_KEY_PASSING
    assert verification["elevenlabs"]["rankable"] is True
    assert verification["elevenlabs"]["frozenReason"] is None
    assert verification["elevenlabs"]["testedAt"] == checked_at
    # An engine with no key at all is still absent, not accidentally thawed.
    assert verification["deepgram"]["keyState"] == app_settings.STT_KEY_ABSENT


def test_a_rotated_key_refreezes_the_engine_it_used_to_thaw(monkeypatch, stack) -> None:
    from app.api.routes import settings as settings_route

    s = stack()
    s.verdicts.rows[ELEVENLABS] = SimpleNamespace(
        key=ELEVENLABS,
        status="OK",
        checkedAt=datetime.now(UTC),
        error=None,
        fingerprint=managed_secrets.fingerprint(ANOTHER_KEY),  # the key that has since been replaced
    )
    monkeypatch.setattr(
        settings_route.ai,
        "transcription_provider_configured",
        lambda: {"elevenlabs": True, "deepgram": False, "whisper": False},
    )

    verification = asyncio.run(settings_route._provider_verification())

    assert verification["elevenlabs"]["keyState"] == app_settings.STT_KEY_UNTESTED
    assert verification["elevenlabs"]["rankable"] is False
    assert "never been tested" in verification["elevenlabs"]["frozenReason"]


# --- The override that cannot be decrypted any more ---------------------------------------------------
#
# THE ROTATION CASUALTY, and the one state in which "a row exists" and "the stored key is what gets
# sent" are different facts. SECRETS_ENCRYPTION_KEY was never set, JWT_SECRET was rotated on a
# deploy, and every ManagedSecret row is now well-formed Fernet ciphertext for a key this process no
# longer holds. `refresh_if_stale` drops those rows from the override cache and flags them FAILED
# with a re-enter message; `peek_secret` falls through to the environment. Everything below is about
# the three surfaces that used to derive provenance from `row is not None` and therefore told the
# admin a fact about the STORED key while handing them the ENVIRONMENT one:
#
#   * GET /secrets/{key}/reveal - an environment plaintext labelled source "database";
#   * POST /secrets/{key}/test - the environment value probed, the verdict stamped on the row, and
#     the "re-enter this key" flag erased until the cache next went stale;
#   * the Settings hub's hint - the last four characters of the key that is NOT in force.
#
# The environment key is deliberately DIFFERENT from the stored one in these tests (ANOTHER_KEY ends
# "0000", A_KEY ends "f4b8"), because a fixture where both are the same value cannot tell a right
# answer from a wrong one on any of the three.


def _lock_the_stored_override(s: _Stack) -> None:
    """Reproduce the accident: rotate the signing secret out from under a row already written.

    Encrypting first and rotating afterwards is what makes the row REAL rather than a string of
    garbage - it is a valid token for a key that is gone, which is exactly what a production row
    looks like the morning after a JWT_SECRET rotation. Both derived keys move (the Fernet key and
    the verdict pepper), because both come from the same source.
    """
    s.knobs.jwt_secret = "the-deploy-rotated-the-signing-secret-2026"
    managed_secrets._fernet.cache_clear()
    managed_secrets._fingerprint_pepper.cache_clear()
    managed_secrets.invalidate()


def _undecryptable(stack, *, env_key: str | None = ANOTHER_KEY) -> _Stack:
    s = stack(env_key=env_key)
    s.secrets.rows[ELEVENLABS] = _managed_row(ELEVENLABS)  # encrypts A_KEY, hint "f4b8"
    _lock_the_stored_override(s)
    return s


def test_the_override_really_is_unreadable_and_the_environment_value_is_what_resolves(stack) -> None:
    """The precondition every test below depends on. If this ever stops holding they all go green
    for the wrong reason, so it is asserted on its own rather than assumed."""
    s = _undecryptable(stack)

    assert asyncio.run(managed_secrets.get_secret(ELEVENLABS)) == ANOTHER_KEY
    assert s.secrets.rows[ELEVENLABS].lastStatus == "FAILED"
    assert s.secrets.rows[ELEVENLABS].lastError == managed_secrets._UNDECRYPTABLE_ERROR


def test_revealing_an_unreadable_override_says_the_value_came_from_the_environment(stack) -> None:
    """The eye button after a rotation. It showed a full, plausible key labelled source "database",
    so the master admin concluded the stored override had survived; it had not, and what they were
    reading was the deployment's environment variable."""
    _undecryptable(stack)

    revealed = asyncio.run(managed_secrets.reveal_secret(ELEVENLABS))

    assert revealed["value"] == ANOTHER_KEY, "the value in force is what the eye button must show"
    assert revealed["source"] == managed_secrets.SOURCE_ENVIRONMENT, "it did NOT come from the row"
    assert revealed["overrideUnreadable"] is True, "and the broken row must still be reported"


def test_the_hint_beside_an_unreadable_override_names_the_key_in_force(stack) -> None:
    """The hint exists so an admin can reconcile against a provider dashboard without revealing
    anything. Printing the stored row's four characters beside the environment key's value points
    them at the credential the deployment is not using - the wrong conclusion, confidently."""
    _undecryptable(stack)

    listed = {entry["key"]: entry for entry in asyncio.run(managed_secrets.list_managed_secrets())}
    entry = listed[ELEVENLABS]

    assert entry["hint"] == ANOTHER_KEY[-4:]
    assert entry["hint"] != "f4b8", "that is the stored key's fingerprint, and it is not in force"
    assert entry["source"] == managed_secrets.SOURCE_ENVIRONMENT
    assert entry["overrideUnreadable"] is True
    assert entry["configured"] is True, "the environment value is a value"


def test_testing_an_unreadable_override_leaves_the_re_enter_flag_alone(stack) -> None:
    """THE ERASURE. Test probed the environment key, then wrote OK over the FAILED that
    `refresh_if_stale` had put on the row, so the one durable signal saying which overrides must be
    re-entered was destroyed by the button meant to check them - and came back minutes later when
    the cache went stale, leaving the row alternating between healthy and broken."""
    s = _undecryptable(stack)

    described = asyncio.run(managed_secrets.test_secret(ELEVENLABS))

    row = s.secrets.rows[ELEVENLABS]
    assert row.lastStatus == "FAILED", "the probe was of a different key; the row keeps its verdict"
    assert row.lastError == managed_secrets._UNDECRYPTABLE_ERROR
    # The verdict about the value that WAS probed goes where an environment verdict belongs.
    assert s.verdicts.rows[ELEVENLABS].status == "OK"
    assert s.verdicts.rows[ELEVENLABS].fingerprint == managed_secrets.fingerprint(ANOTHER_KEY)
    # ...and the answer says both things, because the admin asked a question and deserves an answer.
    assert described["lastStatus"] == "FAILED"
    assert managed_secrets._UNDECRYPTABLE_ERROR in described["lastError"]
    assert "environment value" in described["lastError"]
    assert described["source"] == managed_secrets.SOURCE_ENVIRONMENT
    assert described["overrideUnreadable"] is True


def test_testing_an_unreadable_override_with_nothing_behind_it_keeps_the_reason(stack) -> None:
    """The `value is None` arm of the same defect: it used to write lastError = "Not configured"
    over the undecryptable message, replacing the only sentence that says what to do about it."""
    s = _undecryptable(stack, env_key=None)

    described = asyncio.run(managed_secrets.test_secret(ELEVENLABS))

    assert s.secrets.rows[ELEVENLABS].lastError == managed_secrets._UNDECRYPTABLE_ERROR
    assert s.verdicts.rows == {}, "there was no environment value to record a verdict about"
    assert managed_secrets._UNDECRYPTABLE_ERROR in described["lastError"]
    assert described["source"] == managed_secrets.SOURCE_UNSET
    assert described["configured"] is False, "an unopenable row supplies nothing"
    assert asyncio.run(managed_secrets.reveal_secret(ELEVENLABS))["value"] is None


def test_pressing_test_on_an_unreadable_override_does_not_thaw_the_engine(monkeypatch, stack) -> None:
    """The secondary consequence, end to end: `lastStatus == OK` on the row is what makes an engine
    rankable, so the stamped verdict also lifted the STT freeze on the strength of a key the stored
    row does not contain."""
    from app.api.routes import settings as settings_route

    _undecryptable(stack)
    monkeypatch.setattr(
        settings_route.ai,
        "transcription_provider_configured",
        lambda: {"elevenlabs": True, "deepgram": False, "whisper": False},
    )

    asyncio.run(managed_secrets.test_secret(ELEVENLABS))
    verification = asyncio.run(settings_route._provider_verification())

    assert verification["elevenlabs"]["keyState"] == app_settings.STT_KEY_FAILING
    assert verification["elevenlabs"]["rankable"] is False
    assert "Re-enter the key" in verification["elevenlabs"]["frozenReason"]


def test_a_readable_override_is_still_the_database_with_its_own_hint(stack) -> None:
    """The other direction, and the one an over-eager fix breaks: nothing above may make a healthy
    override read as environment-sourced, or report the environment's four characters for it."""
    s = stack(env_key=ANOTHER_KEY)
    s.secrets.rows[ELEVENLABS] = _managed_row(ELEVENLABS)  # decrypts cleanly under this key

    entry = asyncio.run(managed_secrets.describe_secret(ELEVENLABS))
    revealed = asyncio.run(managed_secrets.reveal_secret(ELEVENLABS))

    assert entry["source"] == managed_secrets.SOURCE_DATABASE
    assert entry["overrideUnreadable"] is False
    assert entry["hint"] == "f4b8", "the stored row's own hint, as before"
    assert revealed["value"] == A_KEY and revealed["source"] == managed_secrets.SOURCE_DATABASE
