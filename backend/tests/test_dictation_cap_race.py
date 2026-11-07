import asyncio
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.services import dictation_cap


class _Usage:
    def __init__(self) -> None:
        self.rows: dict[tuple[str, str], int] = {}

    @staticmethod
    def _key(where: dict[str, Any]) -> tuple[str, str]:
        if "userId_istDay" in where:
            inner = where["userId_istDay"]
            return inner["userId"], inner["istDay"]
        return where["userId"], where["istDay"]

    @staticmethod
    def _apply(count: int, change: Any) -> int:
        if isinstance(change, dict):
            return count + int(change.get("increment", 0)) - int(change.get("decrement", 0))
        return int(change)

    async def find_unique(self, where: dict[str, Any], **_kw: Any) -> Any:
        await asyncio.sleep(0)
        key = self._key(where)
        if key not in self.rows:
            return None
        return SimpleNamespace(userId=key[0], istDay=key[1], count=self.rows[key])

    async def upsert(self, where: dict[str, Any], data: dict[str, Any], **_kw: Any) -> Any:
        await asyncio.sleep(0)
        key = self._key(where)
        if key in self.rows:
            self.rows[key] = self._apply(self.rows[key], data["update"].get("count", {"increment": 0}))
        else:
            self.rows[key] = int(data["create"].get("count", 0))
        return SimpleNamespace(userId=key[0], istDay=key[1], count=self.rows[key])

    async def update_many(self, where: dict[str, Any], data: dict[str, Any], **_kw: Any) -> int:
        key = self._key(where)
        if key not in self.rows:
            return 0
        bound = where.get("count") or {}
        current = self.rows[key]
        if "lt" in bound and not current < bound["lt"]:
            return 0
        if "gt" in bound and not current > bound["gt"]:
            return 0
        self.rows[key] = self._apply(current, data["count"])
        return 1


class _Clip:
    def __init__(self, size: int = 2048) -> None:
        self._bytes = b"\x00" * size
        self.filename = "dictation.webm"
        self.content_type = "audio/webm"
        self._spent = False

    async def read(self, size: int = -1) -> bytes:
        if self._spent:
            return b""
        self._spent = True
        return self._bytes


@pytest.fixture
def capped(monkeypatch):
    from app.api.routes import design_workshops as routes

    usage = _Usage()
    state = SimpleNamespace(limit=1, provider_calls=0, result={"status": "COMPLETED", "text": "dummy words", "provider": "dummy"})

    async def _settings_row():
        return SimpleNamespace(dwDictationDailyCap=state.limit)

    async def _unpaused():
        return False

    async def _transcribe(content, filename, mime, settings, *, user_id=None):
        state.provider_calls += 1
        for _ in range(5):
            await asyncio.sleep(0)
        return dict(state.result)

    monkeypatch.setattr(dictation_cap, "db", SimpleNamespace(dwdictationdailyusage=usage))
    monkeypatch.setattr(dictation_cap, "_settings_row", _settings_row)
    monkeypatch.setattr(routes.app_settings, "load_transcription_paused", _unpaused)
    monkeypatch.setattr(routes, "transcribe_audio_bytes", _transcribe)
    monkeypatch.setattr(routes, "get_settings", SimpleNamespace)
    return SimpleNamespace(routes=routes, usage=usage, state=state)


def _designer():
    return SimpleNamespace(id="usr_designer", email="d@example.test", name="Dummy", role="DESIGNER")


def _dictate(capped):
    return capped.routes._transcribe_one_dictation(
        _Clip(), None, _designer(), SimpleNamespace(headers={})
    )


def test_concurrent_dictations_at_the_last_slot_reach_the_provider_only_once(capped):
    async def both():
        return await asyncio.gather(_dictate(capped), _dictate(capped), return_exceptions=True)

    outcomes = asyncio.run(both())
    refused = [o for o in outcomes if isinstance(o, HTTPException)]
    assert capped.state.provider_calls == 1, "two requests spent the one remaining dictation"
    assert len(refused) == 1 and refused[0].status_code == 429
    assert list(capped.usage.rows.values()) == [1]


def test_a_dictation_the_provider_could_not_take_hands_its_slot_back(capped):
    capped.state.result = {"status": "UNAVAILABLE", "message": "Transcription is not configured."}
    with pytest.raises(HTTPException) as caught:
        asyncio.run(_dictate(capped))
    assert caught.value.status_code == 503
    assert list(capped.usage.rows.values()) in ([], [0])


def test_a_provider_crash_hands_the_slot_back(capped, monkeypatch):
    async def _boom(*_a, **_k):
        raise RuntimeError("provider unreachable")

    monkeypatch.setattr(capped.routes, "transcribe_audio_bytes", _boom)
    with pytest.raises(RuntimeError):
        asyncio.run(_dictate(capped))
    assert list(capped.usage.rows.values()) in ([], [0])


def test_an_uncapped_server_still_counts_every_dictation(capped):
    capped.state.limit = None
    asyncio.run(_dictate(capped))
    asyncio.run(_dictate(capped))
    assert capped.state.provider_calls == 2
    assert list(capped.usage.rows.values()) == [2]
