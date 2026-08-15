"""The memory bound on ``/data/media/{id}/download``'s in-process audio conversion.

THE ONE MEMORY BOUND ON THE HEAVIEST SYNCHRONOUS PATH IN THE WEB PROCESS WAS SET BY THE CALLER.
``MAX_CONVERT_BYTES`` was compared against ``MediaFile.sizeBytes``, which is whatever the client
declared at ``POST /media/complete``: the schema bounds it only from below (``Field(gt=0)``), it is
stored verbatim, and nothing in ``backend/app`` ever reconciles it against the stored object — there
is no ``head_object`` anywhere in the tree. The upload signature does not bound the body either:
``s3.presign_put_url`` signs Bucket/Key/ContentType and deliberately no ``content-length-range``,
because a signed condition there breaks every Android build already installed in the field.

So an account holding ``canDownloadDataset`` could presign an upload declaring ``sizeBytes: 1024``,
PUT 1.5 GB to the returned URL, complete it as AUDIO and click download on their own row: the 413
would not fire, ``get_object_bytes`` would pull 1.5 GB into the heap of this single-worker uvicorn
process, and pydub/ffmpeg would decode a second copy beside it. The non-malicious variant is a client
that computes the size wrongly for a long recording and a Professor who clicks it.

WHAT THIS FIX DOES AND DOES NOT CLOSE. The declared size stays as the cheap first refusal — it costs
nothing and turns away an honest large recording before a byte moves. The REAL length is then checked
between the fetch and the transcode, which is where one copy becomes several. What remains is a
single oversized read into the heap; closing that needs a ``head_object`` helper in ``services/s3``.

Awaited directly with the S3 read and the delegate replaced: what is under test is which number the
comparison reads, and no database or bucket can demonstrate that more clearly than this can.
"""

import asyncio
import sys
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.api.routes import data_browser


class Row(SimpleNamespace):
    """A media row answering ``None`` for any column the test did not set.

    ``display_stem`` walks the naming relations (artisan, product, workshop, …) by plain attribute
    access, because on a real Prisma row loaded with ``_NAMING_INCLUDE`` they always exist. A bare
    ``SimpleNamespace`` raises on the first unset one and the test becomes about the fixture.
    """

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


# The real ceiling is 200 MiB. Allocating that twice inside a unit test costs seconds of wall clock
# and a third of a gigabyte of RSS to prove an inequality, so the bound itself is lowered for the
# duration: what is under test is WHICH NUMBER the comparison reads, not what the constant is set to.
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


def _download(monkeypatch, *, declared: int, real_bytes: int):
    """Run the route with a row that LIES about its size by ``real_bytes - declared``."""
    # pydub is an optional runtime dependency (it needs ffmpeg). The route imports it locally and
    # answers 503 when it is missing, which would mask everything under test here, so a stand-in is
    # installed for the duration — the conversion itself is replaced below and never runs.
    monkeypatch.setitem(sys.modules, "pydub", SimpleNamespace())
    monkeypatch.setattr(data_browser, "MAX_CONVERT_BYTES", CEILING)

    async def _scope(*_a: Any, **_k: Any) -> Any:
        return SimpleNamespace(media={})

    converted: list[int] = []

    monkeypatch.setattr(data_browser, "_scope_for", _scope)
    monkeypatch.setattr(data_browser, "db", SimpleNamespace(mediafile=_MediaDelegate(_row(declared))))
    monkeypatch.setattr(data_browser, "get_object_bytes", lambda _key: b"\0" * real_bytes)
    monkeypatch.setattr(
        data_browser,
        "_convert_audio_to_mp4",
        lambda raw: converted.append(len(raw)) or __import__("io").BytesIO(b"mp4"),
    )
    result = asyncio.run(data_browser.download_media(media_id="m-1", current_user=DOWNLOADER))
    return result, converted


def test_a_row_understating_its_size_is_still_refused(monkeypatch):
    """THE DEFECT. One byte over the ceiling on disk, one kilobyte on the row, and the transcode ran."""
    with pytest.raises(HTTPException) as caught:
        _download(
            monkeypatch,
            declared=1024,
            real_bytes=CEILING + 1,
        )
    assert caught.value.status_code == 413
    assert "too large to convert" in caught.value.detail


def test_nothing_is_decoded_once_the_real_length_is_over_the_ceiling(monkeypatch):
    """The refusal has to land BETWEEN the fetch and the decode. ffmpeg decoding compressed audio is
    where one copy becomes several, so this is the half that actually takes the box down — a check
    placed after the transcode would be a comment, not a bound."""
    monkeypatch.setitem(sys.modules, "pydub", SimpleNamespace())
    monkeypatch.setattr(data_browser, "MAX_CONVERT_BYTES", CEILING)

    async def _scope(*_a: Any, **_k: Any) -> Any:
        return SimpleNamespace(media={})

    def _must_not_run(_raw: bytes):  # pragma: no cover - the point is that it is not reached
        raise AssertionError("the oversized object was handed to ffmpeg")

    monkeypatch.setattr(data_browser, "_scope_for", _scope)
    monkeypatch.setattr(data_browser, "db", SimpleNamespace(mediafile=_MediaDelegate(_row(1024))))
    monkeypatch.setattr(
        data_browser,
        "get_object_bytes",
        lambda _key: b"\0" * (CEILING + 1),
    )
    monkeypatch.setattr(data_browser, "_convert_audio_to_mp4", _must_not_run)
    with pytest.raises(HTTPException) as caught:
        asyncio.run(data_browser.download_media(media_id="m-1", current_user=DOWNLOADER))
    assert caught.value.status_code == 413


def test_the_declared_size_still_refuses_before_a_byte_moves(monkeypatch):
    """The cheap first check must survive: an honest 300 MB recording should never be fetched at all.
    Asserted by a reader that would blow up if it were called."""

    def _must_not_run(_key: str) -> bytes:  # pragma: no cover - the point is that it is not reached
        raise AssertionError("the oversized row was fetched despite declaring its true size")

    monkeypatch.setitem(sys.modules, "pydub", SimpleNamespace())
    monkeypatch.setattr(data_browser, "MAX_CONVERT_BYTES", CEILING)

    async def _scope(*_a: Any, **_k: Any) -> Any:
        return SimpleNamespace(media={})

    monkeypatch.setattr(data_browser, "_scope_for", _scope)
    monkeypatch.setattr(
        data_browser, "db", SimpleNamespace(mediafile=_MediaDelegate(_row(CEILING + 1)))
    )
    monkeypatch.setattr(data_browser, "get_object_bytes", _must_not_run)
    with pytest.raises(HTTPException) as caught:
        asyncio.run(data_browser.download_media(media_id="m-1", current_user=DOWNLOADER))
    assert caught.value.status_code == 413


def test_an_ordinary_recording_still_converts(monkeypatch):
    """The control, and the one a careless bound breaks: the whole feature is this conversion."""
    result, converted = _download(monkeypatch, declared=4096, real_bytes=4096)
    assert result.media_type == "video/mp4"
    assert converted == [4096]
