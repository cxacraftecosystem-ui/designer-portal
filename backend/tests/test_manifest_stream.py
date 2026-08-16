"""The streamed download manifest: ``GET /export/dataset?stream=1`` and ``GET /data/manifest?stream=1``.

WHAT THIS IS FOR. Both manifest endpoints answered with ONE JSON object holding every entry of a
download, and the entries carry inline text — a ``details.txt`` body per record, an ``answers.txt``
per questionnaire and, unfiltered, ``_transcriptText``: the full transcript of every audio row in the
subtree. The caps are on the entry COUNT (``EXPORT_TAKE``/``MEDIA_TAKE``/``MAX_MANIFEST_FILES``) and
nothing caps the byte size, so ``docs/SCALABILITY.md`` measures 476 kB today and models ~48 MB at
100x the media.

On the handset that single body is the whole failure. Retrofit's kotlinx-serialization converter is
``Serializer.FromString`` — ``decodeFromString(body.string())`` — and ``ResponseBody.string()``
allocates one contiguous ``ByteArray`` the size of the entire body and copies it into one contiguous
``String``, so the app dies with ``OutOfMemoryError: Failed to allocate a N byte allocation``. NDJSON
lets the client decode one entry at a time and never hold more than the longest line.

FOUR PROPERTIES ARE WORTH MORE THAN THE REST AND MOST OF THIS FILE IS ABOUT THEM.

**One entry per line, whatever the entry contains.** Every ``details.txt`` body is multi-line and
every transcript is many lines, so if an entry's text ever reached the wire with a real newline in it
the line protocol would break silently: the client would read one entry as several, most of them
unparseable, and the archive would come out short with no error anywhere in the system. This is
asserted with content that contains newlines, tabs and Devanagari.

**The default shape is unchanged.** ``?stream=1`` is opt-in because the two browser clients
(``frontend/app/(protected)/data/page.tsx``, ``sharing/page.tsx``) and every installed Android build
read the JSON object. A change that makes NDJSON the default is a fleet-wide download outage, so the
JSON response is asserted here beside the streamed one — INCLUDING its exact key set.

**``skippedMedia`` survives the loss of the wrapper.** This repository's ``/export/dataset`` answers
five keys, not four: a media row that could not be addressed at all is counted separately from a
capped table, because the follow-up differs. Once the wrapper object is gone, ``X-Dataset-Skipped``
is the only place that number can travel — and the sibling portal has no such header, so nothing but
this test keeps it from being "tidied" back into parity.

**The entries are released as they are encoded.** That is the server half of the same memory
argument — without it the ~48 MB of encoded bytes sit beside the list that produced them on a
single-worker t3.micro.

NOTHING HERE TOUCHES A DATABASE. ``db`` is replaced with stubs and the routes are driven over HTTP,
the same way ``test_dataset_api.py`` does it.
"""

import asyncio
import json
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from app.api.routes import data_browser, export

# =================================================================================================
# Harness
# =================================================================================================


class _Rec:
    """A record row that answers None for anything it was not given.

    The shared field registry reads far more attributes off a row than any one test cares to name,
    and a ``SimpleNamespace`` raises for the rest. Answering None is what a nullable column does
    anyway.
    """

    def __init__(self, **kw: Any) -> None:
        self.__dict__.update(kw)

    def __getattr__(self, name: str) -> Any:  # only reached when the attribute was not set
        if name.startswith("__"):
            raise AttributeError(name)
        return None


class _Rows:
    """Stands in for a Prisma delegate over a fixed list of rows."""

    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows

    async def find_many(self, where: Any = None, take: int | None = None, **_: Any) -> list[Any]:
        return self.rows[:take] if take else list(self.rows)


def _collect(app: FastAPI, path: str) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://manifest.test") as client:
            return await client.get(path)

    return asyncio.run(run())


@pytest.fixture
def api(monkeypatch: pytest.MonkeyPatch):
    """The real ``/export/dataset`` route over stubbed tables and a stubbed admin."""
    admin = SimpleNamespace(id="admin", email="admin@example.test", name="Admin", role="ADMIN")

    application = FastAPI()
    application.include_router(export.router, prefix="/api")

    tables: dict[str, _Rows] = {}

    class _Tables:
        def __getattr__(self, name: str) -> Any:
            return tables.setdefault(name, _Rows([]))

    monkeypatch.setattr(export, "db", _Tables())
    monkeypatch.setattr(export, "can_download_dataset", lambda _user: True)

    async def _no_filter(*_args: Any, **_kw: Any) -> dict[str, Any]:
        return {}

    monkeypatch.setattr(export, "owned_or_granted_where", _no_filter)
    application.dependency_overrides[export.get_current_user] = lambda: admin

    class _Stack:
        def table(self, delegate: str, rows: list[Any]) -> None:
            tables[delegate] = _Rows(rows)

        def get(self, path: str) -> httpx.Response:
            return _collect(application, path)

    return _Stack()


def _workshop(**kw: Any) -> _Rec:
    fields: dict[str, Any] = {
        "id": "w1",
        "title": "Bagru Block Printers",
        "crafts": [],
        "artisans": [],
    }
    fields.update(kw)
    return _Rec(**fields)


# =================================================================================================
# The line protocol — the property that breaks silently
# =================================================================================================


def _ndjson_app(files: list[dict[str, Any]], truncated: bool = False, skipped: int = 0) -> FastAPI:
    application = FastAPI()

    @application.get("/manifest")
    async def manifest() -> Any:  # pragma: no cover - exercised over HTTP
        return data_browser.manifest_ndjson_response(
            files, 0, truncated, "test.ndjson", skipped_media=skipped
        )

    return application


def test_an_entry_whose_text_contains_newlines_still_occupies_one_line() -> None:
    """The whole line protocol rests on this, and it fails silently if it is ever broken.

    Every ``details.txt`` body is multi-line and so is every ``answers.txt``. If one reached the wire
    with a real newline in it the client would read one entry as several — most of them unparseable —
    and hand back a short archive with no error raised anywhere in the system.
    """
    body = "Title: Bagru\nPlace: Rajasthan\n\tCraft: Block printing\nNotes: हिन्दी"
    files = [
        {"path": "Workshops/Bagru/details.txt", "content": body},
        {"path": "Workshops/Bagru/photo.jpg", "url": "https://bucket/photo.jpg"},
    ]

    response = _collect(_ndjson_app(files), "/manifest")

    assert response.status_code == 200
    lines = response.text.splitlines()
    assert len(lines) == 2, f"one entry per line, got {len(lines)} lines for 2 entries"
    assert json.loads(lines[0])["content"] == body
    assert json.loads(lines[1])["url"] == "https://bucket/photo.jpg"


def test_non_ascii_is_sent_as_itself_not_as_escapes() -> None:
    """``ensure_ascii=False`` — a Devanagari transcript must not triple in size on the wire."""
    files = [{"path": "a.txt", "content": "हिन्दी"}]

    response = _collect(_ndjson_app(files), "/manifest")

    assert "\\u0939" not in response.text
    assert json.loads(response.text.strip())["content"] == "हिन्दी"


def test_the_counts_and_the_flag_arrive_before_the_body() -> None:
    """Headers, because NDJSON has no wrapper object and progress must start at the first entry."""
    files = [{"path": f"f{i}.jpg", "url": f"https://bucket/{i}"} for i in range(7)]

    response = _collect(_ndjson_app(files, truncated=True, skipped=3), "/manifest")

    assert response.headers[data_browser.MANIFEST_TOTAL_HEADER] == "7"
    assert response.headers[data_browser.MANIFEST_TRUNCATED_HEADER] == "true"
    assert response.headers[data_browser.MANIFEST_SKIPPED_HEADER] == "3"
    assert response.headers["content-type"].startswith(data_browser.MANIFEST_NDJSON_MEDIA_TYPE)


def test_a_manifest_larger_than_one_chunk_is_whole() -> None:
    """The chunked writer yields 200 lines at a time; the seam must not eat or duplicate an entry."""
    files = [{"path": f"f{i}.jpg", "url": f"https://bucket/{i}"} for i in range(503)]

    response = _collect(_ndjson_app(files), "/manifest")

    paths = [json.loads(line)["path"] for line in response.text.splitlines()]
    assert paths == [f"f{i}.jpg" for i in range(503)]


def test_the_entries_are_released_as_they_are_encoded() -> None:
    """The server half of the memory argument.

    Without the ``files[index] = None``, the encoded ~48 MB sits beside the list that produced it on
    a box with 1 GiB. This asserts the release actually happens rather than trusting the comment; if
    someone removes it as a tidy-up, this test says so.
    """
    files: list[Any] = [
        {"path": "a.txt", "content": "x" * 64},
        {"path": "b.txt", "content": "y" * 64},
    ]

    response = _collect(_ndjson_app(files), "/manifest")

    assert len(response.text.splitlines()) == 2
    assert files == [None, None]


# =================================================================================================
# The route: same entries either way, and the default is untouched
# =================================================================================================


def test_the_dataset_manifest_streams_the_same_entries_the_json_shape_carries(api) -> None:
    api.table("workshop", [_workshop()])

    plain = api.get("/api/export/dataset")
    streamed = api.get("/api/export/dataset?stream=1")

    assert plain.status_code == 200 and streamed.status_code == 200
    assert plain.headers["content-type"].startswith("application/json")
    assert streamed.headers["content-type"].startswith(data_browser.MANIFEST_NDJSON_MEDIA_TYPE)

    expected = plain.json()["files"]
    got = [json.loads(line) for line in streamed.text.splitlines()]
    assert got == expected
    assert got, "the fixture must produce at least one entry or this asserts nothing"
    assert streamed.headers[data_browser.MANIFEST_TOTAL_HEADER] == str(plain.json()["totalFiles"])


def test_the_default_response_is_still_the_json_object(api) -> None:
    """Every deployed client reads this shape. Making NDJSON the default is a fleet-wide outage.

    The key set is asserted whole, not sampled: an OLD handset decodes this into
    ``DatasetManifestDto`` and an old browser build reads ``manifest.files`` directly, so a key that
    disappears here is a download that stops working on a phone nobody in this repo can reach.
    """
    api.table("workshop", [_workshop()])

    payload = api.get("/api/export/dataset").json()

    assert set(payload) == {"files", "totalFiles", "totalMedia", "skippedMedia", "truncated"}
    assert payload["totalFiles"] == len(payload["files"])


def test_an_old_client_that_never_sends_stream_is_answered_exactly_as_before(api) -> None:
    """The backward-compatibility direction that has handsets behind it.

    ``stream`` defaults to False, so a build that has never heard of the parameter takes the same
    branch it always did. Asserted as an identity between "no parameter" and "stream=0" rather than
    trusted from the signature, because a default flipped to True would pass every other test in this
    file while breaking every installed build at once.
    """
    api.table("workshop", [_workshop()])

    silent = api.get("/api/export/dataset")
    explicit = api.get("/api/export/dataset?stream=0")

    assert silent.headers["content-type"].startswith("application/json")
    assert explicit.headers["content-type"].startswith("application/json")
    assert silent.json() == explicit.json()


def test_an_unauthorised_caller_is_refused_before_anything_is_streamed(api, monkeypatch) -> None:
    """``?stream=1`` must not be a way around the dataset-download permission."""
    monkeypatch.setattr(export, "can_download_dataset", lambda _user: False)

    response = api.get("/api/export/dataset?stream=1")

    assert response.status_code == 403


def test_the_streamed_manifest_reports_truncation_in_its_header(api) -> None:
    """A capped export must say so on the streamed path too, or the client cannot warn."""
    api.table(
        "workshop", [_workshop(id=f"w{i}", title=f"Workshop {i}") for i in range(export.EXPORT_TAKE)]
    )

    response = api.get("/api/export/dataset?stream=1")

    assert response.headers[data_browser.MANIFEST_TRUNCATED_HEADER] == "true"


def test_the_skipped_media_count_is_not_lost_with_the_wrapper(api) -> None:
    """THE DIVERGENCE FROM THE SIBLING PORTAL, AND THE REASON THIS TEST EXISTS.

    ``/export/dataset`` here answers ``skippedMedia`` — media rows that could not be addressed at all
    — beside ``truncated``. The server OR-s the two so a client reading only ``truncated`` is still
    warned, but the counts are kept apart because the follow-up differs: a capped table means "ask an
    admin for a full extract", an unaddressable row means "these specific files are broken in
    storage". Both numbers must survive the streamed path, and the header is the only place the
    second one can go.
    """
    unaddressable = _Rec(
        id="m1",
        workshopId="w1",
        originalFilename="portrait.jpg",
        url=None,
        objectKey=None,
        linkedRecordType=None,
        linkedRecordId=None,
    )
    api.table("workshop", [_workshop()])
    api.table("mediafile", [unaddressable])

    plain = api.get("/api/export/dataset").json()
    streamed = api.get("/api/export/dataset?stream=1")

    assert plain["skippedMedia"] == 1, "the fixture did not produce an unaddressable media row"
    assert streamed.headers[data_browser.MANIFEST_SKIPPED_HEADER] == "1"
    # OR-ed into truncated on BOTH paths, or the two disagree about whether the archive is whole.
    assert plain["truncated"] is True
    assert streamed.headers[data_browser.MANIFEST_TRUNCATED_HEADER] == "true"
