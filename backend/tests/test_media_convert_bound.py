import asyncio
import io
import os
import sys
import tempfile
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.api.routes import data_browser
from app.services.s3 import ObjectHead, ObjectTooLarge


class Row(SimpleNamespace):
    def __getattr__(self, name: str) -> Any:
        if name.startswith("__"):
            raise AttributeError(name)
        return None


class _MediaDelegate:
    def __init__(self, row: Any) -> None:
        self._row = row

    async def find_first(self, **_kwargs: Any) -> Any:
        return self._row

    async def find_unique(self, **_kwargs: Any) -> Any:
        return self._row


CEILING = 4096

DOWNLOADER = SimpleNamespace(
    id="user-1", role="RESEARCHER", canDownloadDataset=True, email="r@example.test"
)


def _row(declared: int) -> Row:
    return Row(
        id="m-1",
        mediaType="AUDIO",
        sizeBytes=declared,
        objectKey="media/user-1/interview.m4a",
        originalFilename="interview.m4a",
        mimeType="audio/mp4",
        url=None,
    )


def _spooled(size: int) -> str:
    handle = tempfile.NamedTemporaryFile(delete=False, suffix=".src")  # noqa: SIM115
    try:
        handle.write(b"\0" * size)
    finally:
        handle.close()
    return handle.name


def _install(monkeypatch, *, declared: int, real_bytes: int | None, sized: bool = True):
    monkeypatch.setitem(sys.modules, "pydub", SimpleNamespace())
    monkeypatch.setattr(data_browser, "MAX_CONVERT_BYTES", CEILING)

    async def _scope(*_a: Any, **_k: Any) -> Any:
        return SimpleNamespace(media={})

    monkeypatch.setattr(data_browser, "_scope_for", _scope)
    monkeypatch.setattr(data_browser, "db", SimpleNamespace(mediafile=_MediaDelegate(_row(declared))))

    heads: list[str] = []

    def _head(key: str) -> ObjectHead | None:
        heads.append(key)
        if not sized or real_bytes is None:
            return None
        return ObjectHead(size_bytes=real_bytes, mime_type="audio/mp4")

    spooled: list[str] = []

    def _download(key: str, *, suffix: str = "", max_bytes: int | None = None) -> str:
        assert real_bytes is not None, "the route fetched an object the test said it must not"
        if max_bytes is not None and real_bytes > max_bytes:
            raise ObjectTooLarge(key, real_bytes, max_bytes)
        path = _spooled(real_bytes)
        spooled.append(path)
        return path

    removed: list[str] = []

    def _discard(path: str | None) -> None:
        removed.append(path or "")
        if path and os.path.exists(path):
            os.unlink(path)

    monkeypatch.setattr(data_browser, "head_object", _head)
    monkeypatch.setattr(data_browser, "download_to_temp", _download)
    monkeypatch.setattr(data_browser, "discard_temp", _discard)
    return heads, spooled, removed


def _download_media(monkeypatch, *, declared: int, real_bytes: int | None, sized: bool = True):
    heads, spooled, removed = _install(
        monkeypatch, declared=declared, real_bytes=real_bytes, sized=sized
    )
    converted: list[int] = []

    def _convert(path: str) -> io.BytesIO:
        converted.append(os.path.getsize(path))
        return io.BytesIO(b"mp4")

    monkeypatch.setattr(data_browser, "_convert_audio_to_mp4", _convert)
    result = asyncio.run(data_browser.download_media(media_id="m-1", current_user=DOWNLOADER))
    return result, converted, heads, spooled, removed


def test_a_row_understating_its_size_is_still_refused(monkeypatch):
    with pytest.raises(HTTPException) as caught:
        _download_media(monkeypatch, declared=1024, real_bytes=CEILING + 1)
    assert caught.value.status_code == 413
    assert "too large to convert" in caught.value.detail


def test_nothing_is_fetched_once_head_object_says_it_is_too_big(monkeypatch):
    heads, spooled, _removed = _install(monkeypatch, declared=1024, real_bytes=CEILING + 1)

    def _must_not_run(_path: str):  # pragma: no cover - the point is that it is not reached
        raise AssertionError("the oversized object was handed to ffmpeg")

    monkeypatch.setattr(data_browser, "_convert_audio_to_mp4", _must_not_run)
    with pytest.raises(HTTPException) as caught:
        asyncio.run(data_browser.download_media(media_id="m-1", current_user=DOWNLOADER))
    assert caught.value.status_code == 413
    assert heads == ["media/user-1/interview.m4a"], "the real length was never asked for"
    assert spooled == [], "the object was fetched despite HEAD already refusing it"


def test_an_object_storage_will_not_size_is_still_bounded_by_the_transfer(monkeypatch):
    with pytest.raises(HTTPException) as caught:
        _download_media(monkeypatch, declared=1024, real_bytes=CEILING + 1, sized=False)
    assert caught.value.status_code == 413
    assert "too large to convert" in caught.value.detail


def test_the_declared_size_still_refuses_before_a_byte_moves(monkeypatch):
    heads, spooled, _removed = _install(monkeypatch, declared=CEILING + 1, real_bytes=None)

    def _must_not_run(_path: str):  # pragma: no cover - the point is that it is not reached
        raise AssertionError("the oversized row was converted despite declaring its true size")

    monkeypatch.setattr(data_browser, "_convert_audio_to_mp4", _must_not_run)
    with pytest.raises(HTTPException) as caught:
        asyncio.run(data_browser.download_media(media_id="m-1", current_user=DOWNLOADER))
    assert caught.value.status_code == 413
    assert heads == [], "a HEAD was spent on a row that had already refused itself"
    assert spooled == []


def test_an_ordinary_recording_still_converts(monkeypatch):
    result, converted, _heads, spooled, removed = _download_media(
        monkeypatch, declared=4096, real_bytes=4096
    )
    assert result.media_type == "video/mp4"
    assert converted == [4096]
    assert removed == spooled
    assert not any(os.path.exists(path) for path in spooled)
