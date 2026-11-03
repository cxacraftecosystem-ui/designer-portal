import dataclasses
import hashlib
import json
from collections.abc import Callable
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from app.api.routes import design_workshops as routes
from app.core import deps
from app.services import stage_schema as ss


@pytest.fixture
def client() -> httpx.AsyncClient:
    app = FastAPI()
    app.include_router(routes.router, prefix="/api")
    app.dependency_overrides[deps.get_current_user] = lambda: SimpleNamespace(
        id="user-designer", email="designer@example.test", role="DESIGNER"
    )
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://dw.test")


SCHEMA = "/api/design-workshops/schema"



def _restage(**field_changes: Any) -> tuple[Any, ...]:
    stages = ss.stages()
    stage, entity = stages[0], stages[0].entities[0]
    field = dataclasses.replace(entity.fields[0], **field_changes)
    entity = dataclasses.replace(entity, fields=(field, *entity.fields[1:]))
    return (dataclasses.replace(stage, entities=(entity, *stage.entities[1:])), *stages[1:])


def _retitle() -> tuple[Any, ...]:
    stages = ss.stages()
    return (dataclasses.replace(stages[0], title=stages[0].title + " (revised)"), *stages[1:])


async def _fetch(client: httpx.AsyncClient, **kwargs: Any) -> httpx.Response:
    return await client.get(SCHEMA, **kwargs)



async def test_a_first_fetch_carries_a_weak_etag_and_a_zero_lifetime(
    client: httpx.AsyncClient,
) -> None:
    response = await _fetch(client)

    assert response.status_code == 200
    tag = response.headers["etag"]
    assert tag.startswith('W/"') and tag.endswith('"')
    assert response.headers["cache-control"] == "private, max-age=0, must-revalidate"


async def test_the_tag_is_a_digest_of_the_bytes_that_were_sent(
    client: httpx.AsyncClient,
) -> None:
    response = await _fetch(client)

    expected = hashlib.sha256(response.content).hexdigest()[:32]
    assert response.headers["etag"] == f'W/"{expected}"'


async def test_the_body_is_unchanged_by_the_etag(client: httpx.AsyncClient) -> None:
    response = await _fetch(client)

    assert response.json() == ss.registry_to_dict()
    assert response.headers["content-type"].startswith("application/json")


async def test_returning_the_tag_gets_a_304_with_no_body(client: httpx.AsyncClient) -> None:
    first = await _fetch(client)
    second = await _fetch(client, headers={"If-None-Match": first.headers["etag"]})

    assert second.status_code == 304
    assert second.content == b""
    assert second.headers["etag"] == first.headers["etag"]
    assert second.headers["cache-control"] == first.headers["cache-control"]
    assert second.headers["vary"] == "Accept-Encoding"


async def test_the_304_is_smaller_than_the_body_by_orders_of_magnitude(
    client: httpx.AsyncClient,
) -> None:
    first = await _fetch(client)
    second = await _fetch(client, headers={"If-None-Match": first.headers["etag"]})

    assert len(first.content) > 100_000
    assert len(second.content) == 0
    assert len(first.content) > 100 * (len(second.content) + 200)



def _vary(response: httpx.Response) -> set[str]:
    return {
        part.strip().lower() for part in response.headers.get("vary", "").split(",") if part.strip()
    }


def _app_with_middleware() -> httpx.AsyncClient:
    from app.main import create_app

    app = create_app()
    app.dependency_overrides[deps.get_current_user] = lambda: SimpleNamespace(
        id="user-designer", email="designer@example.test", role="DESIGNER"
    )
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://dw.test")


async def test_the_200_carries_vary_when_the_middleware_compresses_it() -> None:
    async with _app_with_middleware() as client:
        response = await client.get(SCHEMA, headers={"Accept-Encoding": "gzip"})

    assert response.status_code == 200
    assert response.headers["content-encoding"] == "gzip"
    assert "accept-encoding" in response.headers["vary"].lower()
    assert response.headers["etag"].startswith('W/"')
    assert response.headers["cache-control"] == routes._SCHEMA_CACHE_CONTROL


async def test_the_200_carries_no_vary_when_the_client_refuses_gzip() -> None:
    async with _app_with_middleware() as client:
        response = await client.get(SCHEMA, headers={"Accept-Encoding": "identity"})

    assert response.status_code == 200
    assert "content-encoding" not in response.headers
    assert _vary(response) == {"origin"}
    assert response.headers["cache-control"] == routes._SCHEMA_CACHE_CONTROL


async def test_the_304_carries_vary_even_without_gzip() -> None:
    async with _app_with_middleware() as client:
        first = await client.get(SCHEMA, headers={"Accept-Encoding": "identity"})
        second = await client.get(
            SCHEMA,
            headers={"Accept-Encoding": "identity", "If-None-Match": first.headers["etag"]},
        )

    assert second.status_code == 304
    assert _vary(second) == {"accept-encoding", "origin"}
    assert second.content == b""



def _app_without_identity() -> httpx.AsyncClient:
    from app.main import create_app

    return httpx.AsyncClient(
        transport=httpx.ASGITransport(app=create_app()), base_url="http://dw.test"
    )


async def test_the_tag_is_not_a_way_past_the_identity_dependency() -> None:
    async with _app_without_identity() as client:
        refused = await client.get(SCHEMA, headers={"If-None-Match": "*"})

    assert refused.status_code == 401
    assert refused.json() == {"detail": "Sign in to continue."}
    assert "stages" not in refused.text


async def test_the_304_carries_the_cors_headers_a_browser_needs_to_finish_revalidating() -> None:
    from app.core.config import get_settings

    origins = [o for o in get_settings().cors_origins if o != "*"]
    if not origins:
        pytest.skip("this deployment allows every origin, so there is no per-origin header to check")
    origin = origins[0]

    async with _app_with_middleware() as client:
        first = await client.get(
            SCHEMA, headers={"Origin": origin, "Accept-Encoding": "gzip"}
        )
        second = await client.get(
            SCHEMA,
            headers={
                "Origin": origin,
                "Accept-Encoding": "gzip",
                "If-None-Match": first.headers["etag"],
            },
        )

    assert first.status_code == 200
    assert first.headers["access-control-allow-origin"] == origin

    assert second.status_code == 304
    assert second.headers["access-control-allow-origin"] == origin, (
        "a 304 the browser cannot read is worse than no 304 — fetch rejects and the registry never loads"
    )
    assert second.headers.get("access-control-allow-credentials") == first.headers.get(
        "access-control-allow-credentials"
    )
    as_set = lambda value: {part.strip().lower() for part in value.split(",")}  # noqa: E731
    assert as_set(second.headers["vary"]) == as_set(first.headers["vary"])
    assert as_set(second.headers["vary"]) == {"accept-encoding", "origin"}


async def test_a_star_matches_anything(client: httpx.AsyncClient) -> None:
    assert (await _fetch(client, headers={"If-None-Match": "*"})).status_code == 304


async def test_a_tag_inside_a_list_still_matches(client: httpx.AsyncClient) -> None:
    first = await _fetch(client)
    header = f'"deadbeef", {first.headers["etag"]}, W/"0123"'

    assert (await _fetch(client, headers={"If-None-Match": header})).status_code == 304


async def test_the_weakness_prefix_is_ignored_in_the_comparison(
    client: httpx.AsyncClient,
) -> None:
    first = await _fetch(client)
    stripped = first.headers["etag"][2:]

    assert (await _fetch(client, headers={"If-None-Match": stripped})).status_code == 304



@pytest.mark.parametrize(
    "header",
    [
        "",
        '""',
        '"not-the-tag"',
        'W/"not-the-tag"',
        "garbage without quotes",
        '"a", "b", "c"',
        'W/"has,comma"',
    ],
)
async def test_a_non_matching_validator_gets_the_whole_body(
    client: httpx.AsyncClient, header: str
) -> None:
    response = await _fetch(client, headers={"If-None-Match": header})

    assert response.status_code == 200
    assert len(response.content) > 100_000


async def test_a_stale_tag_from_an_older_registry_gets_the_whole_body(
    client: httpx.AsyncClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    first = await _fetch(client)
    stale = first.headers["etag"]

    monkeypatch.setattr(ss, "STAGES", _restage(label="Renamed by the ministry"))

    second = await _fetch(client, headers={"If-None-Match": stale})
    assert second.status_code == 200
    assert second.headers["etag"] != stale



_UNCOVERED_BY_THE_VERSION: list[tuple[str, Callable[[], tuple[Any, ...]]]] = [
    ("a field label", lambda: _restage(label="Renamed by the ministry")),
    ("a field's help text", lambda: _restage(help="Extra guidance for the designer.")),
    ("a stage title", _retitle),
    ("a column width", lambda: _restage(column_width_pct=37)),
    ("a maximum length", lambda: _restage(max_length=4321)),
    ("a minimum value", lambda: _restage(min_value=-12345)),
]


@pytest.mark.parametrize(
    ("name", "mutate"), _UNCOVERED_BY_THE_VERSION, ids=[n for n, _ in _UNCOVERED_BY_THE_VERSION]
)
async def test_the_tag_moves_for_a_change_the_version_digest_does_not_cover(
    client: httpx.AsyncClient,
    monkeypatch: pytest.MonkeyPatch,
    name: str,
    mutate: Callable[[], tuple[Any, ...]],
) -> None:
    before = await _fetch(client)
    version_before = ss.registry_version()

    monkeypatch.setattr(ss, "STAGES", mutate())

    after = await _fetch(client)
    assert after.headers["etag"] != before.headers["etag"], name
    assert after.content != before.content, name
    assert ss.registry_version() == version_before, name


async def test_the_tag_also_moves_for_a_change_the_version_digest_does_cover(
    client: httpx.AsyncClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    before = await _fetch(client)
    version_before = ss.registry_version()

    current = ss.stages()[0].entities[0].fields[0].type
    replacement = ss.FieldType.INT if current is not ss.FieldType.INT else ss.FieldType.TEXT
    monkeypatch.setattr(ss, "STAGES", _restage(type=replacement))

    after = await _fetch(client)
    assert after.headers["etag"] != before.headers["etag"]
    assert ss.registry_version() != version_before


async def test_an_enum_option_relabelled_moves_the_tag(
    client: httpx.AsyncClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    before = await _fetch(client)
    version_before = ss.registry_version()

    name = next(iter(ss.ENUMS))
    key = next(iter(ss.ENUMS[name]))
    monkeypatch.setitem(ss.ENUMS[name], key, ss.ENUMS[name][key] + " (corrected)")

    after = await _fetch(client)
    assert after.headers["etag"] != before.headers["etag"]
    assert ss.registry_version() == version_before



@pytest.mark.parametrize(
    ("header", "expected"),
    [
        ("", False),
        ("   ", False),
        ("*", True),
        (' * ', True),
        ('W/"abc"', True),
        ('"abc"', True),
        ('w/"abc"', True),
        ('"xyz", W/"abc"', True),
        ('"xyz"', False),
        ('W/"ab"', False),
        ('abc', False),
    ],
)
def test_if_none_match_matches(header: str, expected: bool) -> None:
    assert routes._if_none_match_matches(header, 'W/"abc"') is expected



async def test_the_measured_sizes_are_still_in_the_range_the_docs_claim(
    client: httpx.AsyncClient,
) -> None:
    import gzip

    response = await _fetch(client)
    raw = len(response.content)
    packed = len(gzip.compress(response.content, compresslevel=6))

    assert 100_000 < raw < 300_000, f"registry is now {raw} bytes; re-measure SCALABILITY.md §9.1"
    assert 15_000 < packed < 45_000, f"gzipped registry is now {packed} bytes; re-measure §9.1"
    assert response.content == json.dumps(
        ss.registry_to_dict(), separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")
