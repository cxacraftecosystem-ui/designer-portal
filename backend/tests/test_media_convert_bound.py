"""The memory bound on ``/data/media/{id}/download``'s in-process audio conversion.

THE ONE MEMORY BOUND ON THE HEAVIEST SYNCHRONOUS PATH IN THE WEB PROCESS WAS SET BY THE CALLER.
``MAX_CONVERT_BYTES`` was compared against ``MediaFile.sizeBytes``, which is whatever the client
declared at ``POST /media/complete``: the schema bounds it only from below (``Field(gt=0)``), it is
stored verbatim, and nothing in ``backend/app`` ever reconciled it against the stored object. The
upload signature does not bound the body either: ``s3.presign_put_url`` signs Bucket/Key/ContentType
and deliberately no ``content-length-range``, because a signed condition there breaks every Android
build already installed in the field.

So an account holding ``canDownloadDataset`` could presign an upload declaring ``sizeBytes: 1024``,
PUT 1.5 GB to the returned URL, complete it as AUDIO and click download on their own row: the 413
would not fire, the whole object was pulled into the heap of this single-worker uvicorn process, and
pydub/ffmpeg decoded a second copy beside it. The non-malicious variant is a client that computes the
size wrongly for a long recording and a Professor who clicks it.

WHAT THE FIX NOW CLOSES, AND IN WHAT ORDER. Three bounds, cheapest first, and the tests below are one
per bound:

1. The DECLARED size, which costs not even a round trip and turns away an honest large recording
   before anything is asked of storage.
2. ``s3.head_object``, which reads the object's real ``ContentLength`` and no bytes. This is the
   follow-up the earlier version of this file recorded as outstanding — "closing that needs a
   ``head_object`` helper in ``services/s3``" — and it has landed. A row that understates its size is
   now refused BEFORE the fetch rather than after it.
3. ``s3.download_to_temp``'s ``max_bytes``, which counts what actually lands and raises
   ``ObjectTooLarge`` mid-transfer. Not redundant: ``head_object`` answers ``None`` where storage
   will not say (a custom endpoint without ``HEAD``, a permission the API's own credentials lack),
   and an object can be replaced between the HEAD and the GET.

AND THE READ IS NO LONGER A READ. ``download_to_temp`` streams the object to a temp file in ranged
chunks and ffmpeg opens that file, so the recording is never a contiguous ``bytes`` in this process
at all. The tests therefore assert against a real file on disk — its SIZE is what the transcode is
handed — rather than against a length passed in memory.

Awaited directly with the storage layer replaced: what is under test is which number each comparison
reads, and no database or bucket can demonstrate that more clearly than this can.
"""

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


# The real ceiling is 32 MiB, lowered further by whatever the box says is free (see
# `data_browser.convert_ceiling_bytes` and `services/memory_budget`). Allocating even that twice
# inside a unit test costs wall clock and RSS to prove an inequality, so the bound itself is lowered
# for the duration: what is under test is WHICH NUMBER each comparison reads, not what the constant
# is set to. `budget_bytes` clamps its own floor to the ceiling it is given, so lowering the module
# attribute really does lower what the route enforces.
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
    """A real temp file of *size* bytes, as ``download_to_temp`` would have left behind."""
    handle = tempfile.NamedTemporaryFile(delete=False, suffix=".src")  # noqa: SIM115
    try:
        handle.write(b"\0" * size)
    finally:
        handle.close()
    return handle.name


def _install(monkeypatch, *, declared: int, real_bytes: int | None, sized: bool = True):
    """Wire the route to a storage layer that reports *real_bytes* and spools that many.

    *real_bytes* of ``None`` stands for an object storage would not size AND would not hand over —
    used only by the test that asserts nothing is fetched at all.
    """
    # pydub is an optional runtime dependency (it needs ffmpeg). The route imports it locally and
    # answers 503 when it is missing, which would mask everything under test here, so a stand-in is
    # installed for the duration — the conversion itself is replaced below and never runs.
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
    """Run the route with a row that LIES about its size by ``real_bytes - declared``."""
    heads, spooled, removed = _install(
        monkeypatch, declared=declared, real_bytes=real_bytes, sized=sized
    )
    converted: list[int] = []

    def _convert(path: str) -> io.BytesIO:
        # THE SIZE OF THE FILE ON DISK, which is what the transcode is now handed. Asserting on this
        # rather than on a length passed in memory is the point: if the route ever goes back to
        # reading the object whole, this stops compiling against the real function's signature.
        converted.append(os.path.getsize(path))
        return io.BytesIO(b"mp4")

    monkeypatch.setattr(data_browser, "_convert_audio_to_mp4", _convert)
    result = asyncio.run(data_browser.download_media(media_id="m-1", current_user=DOWNLOADER))
    return result, converted, heads, spooled, removed


def test_a_row_understating_its_size_is_still_refused(monkeypatch):
    """THE DEFECT. One byte over the ceiling on disk, one kilobyte on the row, and the transcode ran.

    It is now refused on ``head_object``'s answer, which is to say before a byte of the object has
    moved rather than after the whole of it has.
    """
    with pytest.raises(HTTPException) as caught:
        _download_media(monkeypatch, declared=1024, real_bytes=CEILING + 1)
    assert caught.value.status_code == 413
    assert "too large to convert" in caught.value.detail


def test_nothing_is_fetched_once_head_object_says_it_is_too_big(monkeypatch):
    """The refusal has to land BEFORE the fetch, which is what ``head_object`` bought.

    Asserted by a spool list that stays empty: the HEAD was made, and nothing was pulled after it.
    """
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
    """``head_object`` answering ``None`` must not read as "small".

    A custom endpoint without ``HEAD``, or a credential without permission for it, gets ``None`` —
    and the bound then has to come from ``download_to_temp``'s own byte count, which raises
    ``ObjectTooLarge`` mid-transfer. Without this the two-bound design would have a hole exactly
    where storage is least standard.
    """
    with pytest.raises(HTTPException) as caught:
        _download_media(monkeypatch, declared=1024, real_bytes=CEILING + 1, sized=False)
    assert caught.value.status_code == 413
    assert "too large to convert" in caught.value.detail


def test_the_declared_size_still_refuses_before_a_byte_moves(monkeypatch):
    """The cheap first check must survive: an honest 300 MB recording should never be fetched at all.

    Asserted by an empty HEAD list — not even the metadata round trip is spent on a row that has
    already said it is too big.
    """
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
    """The control, and the one a careless bound breaks: the whole feature is this conversion."""
    result, converted, _heads, spooled, removed = _download_media(
        monkeypatch, declared=4096, real_bytes=4096
    )
    assert result.media_type == "video/mp4"
    assert converted == [4096]
    # AND THE TEMP FILE IS GONE. A conversion that left the source behind would fill the disk this
    # cap exists to protect, one recording at a time, with nothing on screen to say so.
    assert removed == spooled
    assert not any(os.path.exists(path) for path in spooled)
