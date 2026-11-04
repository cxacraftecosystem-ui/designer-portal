import asyncio
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

import pytest

from app.services import app_settings, managed_secrets

ELEVENLABS = "ELEVENLABS_API_KEY"
A_KEY = "sk_eleven_9f3c1d77b2a84e6091cc55de10a7f4b8"
ANOTHER_KEY = "sk_eleven_00000000000000000000000000000000"


class _Table:
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



def test_a_stored_verdict_contains_no_trace_of_the_key(stack) -> None:
    s = stack()

    asyncio.run(managed_secrets.test_secret(ELEVENLABS))

    row = s.verdicts.rows[ELEVENLABS]
    stored = " ".join(str(value) for value in vars(row).values())
    assert A_KEY not in stored
    for length in (8, 12, 16):
        assert A_KEY[:length] not in stored
        assert A_KEY[-length:] not in stored
    assert set(vars(row)) == {"key", "status", "checkedAt", "error", "fingerprint"}


def test_the_fingerprint_is_peppered_so_a_guessed_key_cannot_be_confirmed_from_a_dump(stack) -> None:
    import hashlib

    s = stack()
    digest = managed_secrets.fingerprint(A_KEY)

    assert digest != hashlib.sha256(A_KEY.encode()).hexdigest()
    assert digest != hashlib.sha256(A_KEY.encode()).hexdigest().upper()
    s.knobs.jwt_secret = "a-completely-different-signing-secret-9876"
    managed_secrets._fingerprint_pepper.cache_clear()
    assert managed_secrets.fingerprint(A_KEY) != digest


def test_the_fingerprint_is_stable_for_one_value_and_distinct_between_values(stack) -> None:
    stack()

    assert managed_secrets.fingerprint(A_KEY) == managed_secrets.fingerprint(A_KEY)
    assert managed_secrets.fingerprint(A_KEY) != managed_secrets.fingerprint(ANOTHER_KEY)


def test_testing_an_environment_key_never_creates_a_managed_secret_override(stack) -> None:
    s = stack()

    described = asyncio.run(managed_secrets.test_secret(ELEVENLABS))

    assert s.secrets.rows == {}
    assert s.secrets.writes == 0
    assert described["source"] == managed_secrets.SOURCE_ENVIRONMENT



def test_a_passing_verdict_on_an_environment_key_outlives_the_process(stack) -> None:
    s = stack()
    asyncio.run(managed_secrets.test_secret(ELEVENLABS))
    stored = dict(vars(s.verdicts.rows[ELEVENLABS]))

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
    assert listed["DEEPGRAM_API_KEY"]["lastStatus"] == "UNKNOWN"
    assert s.verdicts.writes == 0, "reading the panel must not write"



def test_rotating_the_environment_key_invalidates_its_verdict(stack) -> None:
    s = stack()
    asyncio.run(managed_secrets.test_secret(ELEVENLABS))
    assert asyncio.run(managed_secrets.describe_secret(ELEVENLABS))["lastStatus"] == "OK"

    s.rotate_environment_key(ANOTHER_KEY)

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
    s = stack()
    asyncio.run(managed_secrets.test_secret(ELEVENLABS))

    s.knobs.jwt_secret = "the-operator-rotated-the-signing-secret-1234"
    managed_secrets._fingerprint_pepper.cache_clear()

    assert asyncio.run(managed_secrets.environment_verdict(ELEVENLABS)) is None


def test_putting_the_key_back_makes_the_old_verdict_true_again(stack) -> None:
    s = stack()
    asyncio.run(managed_secrets.test_secret(ELEVENLABS))

    s.rotate_environment_key(ANOTHER_KEY)
    assert asyncio.run(managed_secrets.environment_verdict(ELEVENLABS)) is None
    s.rotate_environment_key(A_KEY)

    assert asyncio.run(managed_secrets.environment_verdict(ELEVENLABS))["status"] == "OK"



def test_a_ui_entered_key_still_records_its_verdict_on_its_own_row(stack) -> None:
    s = stack()
    s.secrets.rows[ELEVENLABS] = _managed_row(ELEVENLABS)

    described = asyncio.run(managed_secrets.test_secret(ELEVENLABS))

    assert described["source"] == managed_secrets.SOURCE_DATABASE
    assert s.secrets.rows[ELEVENLABS].lastStatus == "OK"
    assert s.verdicts.rows == {}


def test_saving_a_key_through_the_ui_resets_its_verdict_as_before(stack) -> None:
    s = stack()
    s.secrets.rows[ELEVENLABS] = _managed_row(ELEVENLABS, lastStatus="OK")

    asyncio.run(managed_secrets.set_secret(ELEVENLABS, ANOTHER_KEY, None))

    assert s.secrets.rows[ELEVENLABS].lastStatus == "UNKNOWN"
    assert s.secrets.rows[ELEVENLABS].lastCheckedAt is None


def test_an_override_hides_the_environment_verdict_and_deleting_it_gives_it_back(stack) -> None:
    s = stack()
    asyncio.run(managed_secrets.test_secret(ELEVENLABS))

    s.secrets.rows[ELEVENLABS] = _managed_row(ELEVENLABS)
    assert asyncio.run(managed_secrets.describe_secret(ELEVENLABS))["lastStatus"] == "UNKNOWN"

    del s.secrets.rows[ELEVENLABS]
    assert asyncio.run(managed_secrets.describe_secret(ELEVENLABS))["lastStatus"] == "OK"



def test_a_persisted_verdict_leaves_the_engine_rankable_after_a_restart(monkeypatch, stack) -> None:
    from app.api.routes import settings as settings_route

    s = stack()
    checked_at = datetime.now(UTC) - timedelta(days=3)
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
    assert verification["deepgram"]["keyState"] == app_settings.STT_KEY_ABSENT


def test_a_rotated_key_refreezes_the_engine_it_used_to_thaw(monkeypatch, stack) -> None:
    from app.api.routes import settings as settings_route

    s = stack()
    s.verdicts.rows[ELEVENLABS] = SimpleNamespace(
        key=ELEVENLABS,
        status="OK",
        checkedAt=datetime.now(UTC),
        error=None,
        fingerprint=managed_secrets.fingerprint(ANOTHER_KEY),
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



def _lock_the_stored_override(s: _Stack) -> None:
    s.knobs.jwt_secret = "the-deploy-rotated-the-signing-secret-2026"
    managed_secrets._fernet.cache_clear()
    managed_secrets._fingerprint_pepper.cache_clear()
    managed_secrets.invalidate()


def _undecryptable(stack, *, env_key: str | None = ANOTHER_KEY) -> _Stack:
    s = stack(env_key=env_key)
    s.secrets.rows[ELEVENLABS] = _managed_row(ELEVENLABS)
    _lock_the_stored_override(s)
    return s


def test_the_override_really_is_unreadable_and_the_environment_value_is_what_resolves(stack) -> None:
    s = _undecryptable(stack)

    assert asyncio.run(managed_secrets.get_secret(ELEVENLABS)) == ANOTHER_KEY
    assert s.secrets.rows[ELEVENLABS].lastStatus == "FAILED"
    assert s.secrets.rows[ELEVENLABS].lastError == managed_secrets._UNDECRYPTABLE_ERROR


def test_revealing_an_unreadable_override_says_the_value_came_from_the_environment(stack) -> None:
    _undecryptable(stack)

    revealed = asyncio.run(managed_secrets.reveal_secret(ELEVENLABS))

    assert revealed["value"] == ANOTHER_KEY, "the value in force is what the eye button must show"
    assert revealed["source"] == managed_secrets.SOURCE_ENVIRONMENT, "it did NOT come from the row"
    assert revealed["overrideUnreadable"] is True, "and the broken row must still be reported"


def test_the_hint_beside_an_unreadable_override_names_the_key_in_force(stack) -> None:
    _undecryptable(stack)

    listed = {entry["key"]: entry for entry in asyncio.run(managed_secrets.list_managed_secrets())}
    entry = listed[ELEVENLABS]

    assert entry["hint"] == ANOTHER_KEY[-4:]
    assert entry["hint"] != "f4b8", "that is the stored key's fingerprint, and it is not in force"
    assert entry["source"] == managed_secrets.SOURCE_ENVIRONMENT
    assert entry["overrideUnreadable"] is True
    assert entry["configured"] is True, "the environment value is a value"


def test_testing_an_unreadable_override_leaves_the_re_enter_flag_alone(stack) -> None:
    s = _undecryptable(stack)

    described = asyncio.run(managed_secrets.test_secret(ELEVENLABS))

    row = s.secrets.rows[ELEVENLABS]
    assert row.lastStatus == "FAILED", "the probe was of a different key; the row keeps its verdict"
    assert row.lastError == managed_secrets._UNDECRYPTABLE_ERROR
    assert s.verdicts.rows[ELEVENLABS].status == "OK"
    assert s.verdicts.rows[ELEVENLABS].fingerprint == managed_secrets.fingerprint(ANOTHER_KEY)
    assert described["lastStatus"] == "FAILED"
    assert managed_secrets._UNDECRYPTABLE_ERROR in described["lastError"]
    assert "default value" in described["lastError"]
    assert described["source"] == managed_secrets.SOURCE_ENVIRONMENT
    assert described["overrideUnreadable"] is True


def test_testing_an_unreadable_override_with_nothing_behind_it_keeps_the_reason(stack) -> None:
    s = _undecryptable(stack, env_key=None)

    described = asyncio.run(managed_secrets.test_secret(ELEVENLABS))

    assert s.secrets.rows[ELEVENLABS].lastError == managed_secrets._UNDECRYPTABLE_ERROR
    assert s.verdicts.rows == {}, "there was no environment value to record a verdict about"
    assert managed_secrets._UNDECRYPTABLE_ERROR in described["lastError"]
    assert described["source"] == managed_secrets.SOURCE_UNSET
    assert described["configured"] is False, "an unopenable row supplies nothing"
    assert asyncio.run(managed_secrets.reveal_secret(ELEVENLABS))["value"] is None


def test_pressing_test_on_an_unreadable_override_does_not_thaw_the_engine(monkeypatch, stack) -> None:
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
    assert "Enter it again" in verification["elevenlabs"]["frozenReason"]


def test_a_readable_override_is_still_the_database_with_its_own_hint(stack) -> None:
    s = stack(env_key=ANOTHER_KEY)
    s.secrets.rows[ELEVENLABS] = _managed_row(ELEVENLABS)

    entry = asyncio.run(managed_secrets.describe_secret(ELEVENLABS))
    revealed = asyncio.run(managed_secrets.reveal_secret(ELEVENLABS))

    assert entry["source"] == managed_secrets.SOURCE_DATABASE
    assert entry["overrideUnreadable"] is False
    assert entry["hint"] == "f4b8", "the stored row's own hint, as before"
    assert revealed["value"] == A_KEY and revealed["source"] == managed_secrets.SOURCE_DATABASE
