"""``GET /design-workshops/schema`` and the 149 KB it stops re-sending.

WHAT THIS ENDPOINT IS. The field registry — every stage, entity, field, enum and hydration mapping
the three clients render their forms from. It is a pure constant with no database read, it does not
vary per caller, and it is the largest body this API serves to a cold client. Every cold start of
every handset in every village pays for it on a link ``docs/SCALABILITY.md`` §1 sizes at 40 kB/s.

WHAT IS BEING PROTECTED HERE, in the order it matters.

**A 304 MUST NEVER BE A STALE SCHEMA.** This is the only failure in the feature that is worse than
having no feature. A phone holding a registry the server has moved past renders a form whose keys
the server drops at save time, and a field that silently stops being recorded is indistinguishable,
on the phone, from a field the designer forgot to fill in. So the tests below do not merely check
that a matching tag gets a 304 — they change SEVEN different things the RESPONSE BODY carries and
assert the tag moved for every one. Seven is not a round number; it is the seven that
``registry_version()`` deliberately does not cover, which is exactly why the ETag is not bound to it.
Six of them are the parametrised cases in ``_UNCOVERED_BY_THE_VERSION``; the seventh — an ENUM
option's label — sits in its own test because it lives in a dict rather than on a frozen dataclass,
so it is mutated with ``monkeypatch.setitem`` and not by rebuilding a stage. Counting only the
parametrised six understates the argument by exactly the row that reaches a ministry document.

**A NON-MATCH MUST FALL BACK TO THE FULL BODY.** Every way the header parser can be confused — a
stale tag, a garbled one, an empty one, a list — has to end in a 200. Failing towards bytes is free;
failing towards a 304 is the paragraph above.

**THE BODY MUST NOT HAVE CHANGED.** The route now returns a ``Response`` rather than a dict, so the
one thing a reader will want proved is that the payload is byte-for-byte what FastAPI used to
serialise. ``test_the_body_is_unchanged_by_the_etag`` compares it against ``registry_to_dict()``.

**THE `Vary` COUPLING IS PINNED HERE TOO, AND IT NEEDS THE REAL MIDDLEWARE STACK.** The route sets
`Vary: Accept-Encoding` on its 304 and leaves the 200's copy to `SelectiveGZipMiddleware`. That
division only exists once the middleware is mounted, so the THREE tests that check it build the
actual application through ``create_app()`` instead of the bare router the rest of this file uses —
and they assert the CONDITION rather than a law: the 200 carries `Vary` when the middleware
compresses it, carries none when the client refuses gzip, and the 304 carries it either way because
the route's half is the unconditional one.

NOTHING HERE TOUCHES A DATABASE — the registry is module-level Python, the identity dependency is
overridden, and ``create_app()`` under an ASGI transport never runs its lifespan. IT IS NOT FAST, and
an earlier version of this paragraph said "well under a second", which was wrong by roughly three
orders of magnitude on wall clock.

MEASURED 2026-08-23, three runs of the whole module on this machine: **39 passed in 449.22 s, 477.04 s
and 533.93 s**, of which the tests' own call time is **18.60 s** across 28 non-trivial entries (87
more sit under 5 ms). Everything else is importing ``app.services.stage_definitions``, a cost every
module in this suite pays once. DO NOT TREAT THE WALL FIGURE AS A BOUND: three runs of an unchanged
module spread over 85 s here, and a reviewer on another machine recorded ~720 s. The 18.60 s is the
part that is about these tests; the rest is about whatever else the box was doing. Quote the call
time if you quote anything. The single slowest test is
``test_the_200_carries_vary_when_the_middleware_compresses_it`` at 9.74 s, and that is not the
middleware being slow: it is the first test to call ``create_app()``, so the whole router import
lands inside its call phase. Nothing here is slow; the imports are.
"""

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
    """The design-workshop router mounted where the real app mounts it, with identity overridden.

    ``/api`` rather than bare, matching ``app/api/router.py``, so a path asserted here is a path the
    deployment actually serves.
    """
    app = FastAPI()
    app.include_router(routes.router, prefix="/api")
    app.dependency_overrides[deps.get_current_user] = lambda: SimpleNamespace(
        id="user-designer", email="designer@example.test", role="DESIGNER"
    )
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://dw.test")


SCHEMA = "/api/design-workshops/schema"


# =================================================================================================
# Mutating a registry made of FROZEN dataclasses
#
# `FieldSpec`, `EntitySpec` and `StageSpec` are all `@dataclass(frozen=True, slots=True)`, so a test
# cannot simply assign to an attribute — and `object.__setattr__`, which would work, leaves no way
# for monkeypatch to put it back. So every mutation below rebuilds the ONE stage it touches with
# `dataclasses.replace` and monkeypatches the module-level `STAGES` tuple, which is what
# `registry_to_dict` reads and what monkeypatch can restore cleanly at teardown.
# =================================================================================================


def _restage(**field_changes: Any) -> tuple[Any, ...]:
    """The live registry with stage 1's first field altered."""
    stages = ss.stages()
    stage, entity = stages[0], stages[0].entities[0]
    field = dataclasses.replace(entity.fields[0], **field_changes)
    entity = dataclasses.replace(entity, fields=(field, *entity.fields[1:]))
    return (dataclasses.replace(stage, entities=(entity, *stage.entities[1:])), *stages[1:])


def _retitle() -> tuple[Any, ...]:
    """The live registry with stage 1 retitled — a change no field carries."""
    stages = ss.stages()
    return (dataclasses.replace(stages[0], title=stages[0].title + " (revised)"), *stages[1:])


async def _fetch(client: httpx.AsyncClient, **kwargs: Any) -> httpx.Response:
    return await client.get(SCHEMA, **kwargs)


# =================================================================================================
# The happy path, and the size it saves
# =================================================================================================


async def test_a_first_fetch_carries_a_weak_etag_and_a_zero_lifetime(
    client: httpx.AsyncClient,
) -> None:
    response = await _fetch(client)

    assert response.status_code == 200
    tag = response.headers["etag"]
    # WEAK on purpose: `SelectiveGZipMiddleware` may re-encode these bytes below the route, so one
    # validator describes two content-codings. A strong tag would be a claim about bytes that the
    # middleware can falsify.
    assert tag.startswith('W/"') and tag.endswith('"')
    assert response.headers["cache-control"] == "private, max-age=0, must-revalidate"


async def test_the_tag_is_a_digest_of_the_bytes_that_were_sent(
    client: httpx.AsyncClient,
) -> None:
    """Not of the version, not of a timestamp, not of anything a reader has to take on trust."""
    response = await _fetch(client)

    expected = hashlib.sha256(response.content).hexdigest()[:32]
    assert response.headers["etag"] == f'W/"{expected}"'


async def test_the_body_is_unchanged_by_the_etag(client: httpx.AsyncClient) -> None:
    """The route returns a Response now. It must still be the same payload, byte for byte."""
    response = await _fetch(client)

    assert response.json() == ss.registry_to_dict()
    assert response.headers["content-type"].startswith("application/json")


async def test_returning_the_tag_gets_a_304_with_no_body(client: httpx.AsyncClient) -> None:
    first = await _fetch(client)
    second = await _fetch(client, headers={"If-None-Match": first.headers["etag"]})

    assert second.status_code == 304
    assert second.content == b""
    # RFC 9110 §15.4.5 — the 304 has to carry what keeps the stored response usable, or the client's
    # next revalidation is governed by a heuristic instead of by this endpoint's answer.
    assert second.headers["etag"] == first.headers["etag"]
    assert second.headers["cache-control"] == first.headers["cache-control"]
    # The middleware that would have added this cannot: it passes 304 straight through, having no
    # body to compress. A shared cache with no Vary is free to hand a gzipped body to a client that
    # never asked for one.
    assert second.headers["vary"] == "Accept-Encoding"


async def test_the_304_is_smaller_than_the_body_by_orders_of_magnitude(
    client: httpx.AsyncClient,
) -> None:
    """The whole point of the feature, asserted as a number rather than described.

    A floor of 100x rather than the measured ratio: pinning the exact sizes would make this test
    fail every time a field is added, which is the wrong thing to make expensive.
    """
    first = await _fetch(client)
    second = await _fetch(client, headers={"If-None-Match": first.headers["etag"]})

    assert len(first.content) > 100_000
    assert len(second.content) == 0
    assert len(first.content) > 100 * (len(second.content) + 200)


# =================================================================================================
# The one thing the bare router cannot show: who puts `Vary` on which response
#
# The rest of this file mounts `routes.router` on a bare `FastAPI()`, which is the right scope for
# every assertion about tags and bodies and costs nothing. It is the WRONG scope for `Vary`, because
# the header's other half is appended by `SelectiveGZipMiddleware` and there is no middleware in that
# app at all — so a bare-router test would report "the 200 has no Vary" for the whole wrong reason and
# would go on reporting it after the middleware stopped appending the header. The three tests below
# drive `create_app()` — the 200 with gzip, the 200 without it, and the 304 — and between them they
# cover both halves of the division and the condition on the middleware's half.
# =================================================================================================


def _app_with_middleware() -> httpx.AsyncClient:
    """The real application — every middleware the deployment mounts — with identity overridden."""
    from app.main import create_app

    app = create_app()
    app.dependency_overrides[deps.get_current_user] = lambda: SimpleNamespace(
        id="user-designer", email="designer@example.test", role="DESIGNER"
    )
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://dw.test")


async def test_the_200_carries_vary_when_the_middleware_compresses_it() -> None:
    """The half of the asymmetry the route DELEGATES, asserted where it actually happens.

    `get_stage_schema` deliberately does not set `Vary` on its 200, on the grounds that
    `SelectiveGZipMiddleware` appends it when it compresses. That is a claim about another module, in
    another file, and it had no test — so this is the assertion the 304's own comment implies exists.
    """
    async with _app_with_middleware() as client:
        response = await client.get(SCHEMA, headers={"Accept-Encoding": "gzip"})

    assert response.status_code == 200
    assert response.headers["content-encoding"] == "gzip"
    assert "accept-encoding" in response.headers["vary"].lower()
    # The other two headers are the route's own, and they must survive the middleware.
    assert response.headers["etag"].startswith('W/"')
    assert response.headers["cache-control"] == routes._SCHEMA_CACHE_CONTROL


async def test_the_200_carries_no_vary_when_the_client_refuses_gzip() -> None:
    """AND THE CONDITION UNDER IT, which is the part the comments used to state as a law.

    `SelectiveGZipMiddleware` returns before capturing anything when the request does not offer gzip,
    so nothing appends `Vary` and the 200 goes out without it. Pinned as the current behaviour rather
    than endorsed: it is harmless here because this body does not really vary by request header and
    `private` keeps it out of a shared cache. If someone decides the 200 should always carry `Vary`,
    this is the test that has to change, and changing it is the moment to re-read the route's comment.
    """
    async with _app_with_middleware() as client:
        response = await client.get(SCHEMA, headers={"Accept-Encoding": "identity"})

    assert response.status_code == 200
    assert "content-encoding" not in response.headers
    assert "vary" not in response.headers
    assert response.headers["cache-control"] == routes._SCHEMA_CACHE_CONTROL


async def test_the_304_carries_vary_even_without_gzip() -> None:
    """The route's half is UNCONDITIONAL, which is what makes the division safe to rely on.

    A 304 never reaches the compression branch — the middleware passes 204 and 304 straight through —
    so if the route did not set the header there would be no path by which a 304 could ever carry it.
    """
    async with _app_with_middleware() as client:
        first = await client.get(SCHEMA, headers={"Accept-Encoding": "identity"})
        second = await client.get(
            SCHEMA,
            headers={"Accept-Encoding": "identity", "If-None-Match": first.headers["etag"]},
        )

    assert second.status_code == 304
    assert second.headers["vary"] == "Accept-Encoding"
    assert second.content == b""


async def test_a_star_matches_anything(client: httpx.AsyncClient) -> None:
    """``If-None-Match: *`` means "if you have any representation at all". We always do."""
    assert (await _fetch(client, headers={"If-None-Match": "*"})).status_code == 304


async def test_a_tag_inside_a_list_still_matches(client: httpx.AsyncClient) -> None:
    first = await _fetch(client)
    header = f'"deadbeef", {first.headers["etag"]}, W/"0123"'

    assert (await _fetch(client, headers={"If-None-Match": header})).status_code == 304


async def test_the_weakness_prefix_is_ignored_in_the_comparison(
    client: httpx.AsyncClient,
) -> None:
    """RFC 9110 §13.1.2: If-None-Match uses the weak comparison function."""
    first = await _fetch(client)
    stripped = first.headers["etag"][2:]  # drop the W/

    assert (await _fetch(client, headers={"If-None-Match": stripped})).status_code == 304


# =================================================================================================
# Every way a non-match must end in a full body
# =================================================================================================


@pytest.mark.parametrize(
    "header",
    [
        "",
        '""',
        '"not-the-tag"',
        'W/"not-the-tag"',
        "garbage without quotes",
        '"a", "b", "c"',
        # A tag with a comma in it cannot be parsed by a naive split. It must therefore fail to
        # match and cost bytes, never match by accident and cost correctness.
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
    """The revalidation that actually happens after a deployment."""
    first = await _fetch(client)
    stale = first.headers["etag"]

    monkeypatch.setattr(ss, "STAGES", _restage(label="Renamed by the ministry"))

    second = await _fetch(client, headers={"If-None-Match": stale})
    assert second.status_code == 200
    assert second.headers["etag"] != stale


# =================================================================================================
# THE TRAP: the validator must move for everything the BODY carries, not everything the VERSION does
# =================================================================================================


#: Six changes that alter the payload and leave ``registry_version()`` character-for-character
#: identical. Not invented: each is something ``field_to_dict``/``stage_to_dict`` puts on the wire and
#: ``registry_version`` omits from its digest — which it does on purpose, saying so in its own
#: docstring ("deliberately insensitive to labels and help text: retitling a field must not
#: invalidate every cached draft on every phone").
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
    """Each assertion is doubled on purpose.

    The first half proves the ETag moved — the property the feature needs. The second half proves
    ``registry_version()`` did NOT — the property that makes the first half necessary rather than
    incidental, and the entire reason this endpoint does not reuse the digest it already publishes.
    Drop the second half and the day somebody widens ``registry_version()`` these tests would go on
    passing while quietly testing nothing.
    """
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
    """The control. A retyped field moves both, and the ETag must not be the one that misses it."""
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
    """The seventh, kept apart because it lives in a dict rather than on a frozen dataclass.

    An option's LABEL is what an artisan's answer is printed as in a submitted document, and the
    whole ENUMS table is published at the top of the payload. ``registry_version()`` digests the
    enum's NAME only, so a corrected wording moves the body and not the version.
    """
    before = await _fetch(client)
    version_before = ss.registry_version()

    name = next(iter(ss.ENUMS))
    key = next(iter(ss.ENUMS[name]))
    monkeypatch.setitem(ss.ENUMS[name], key, ss.ENUMS[name][key] + " (corrected)")

    after = await _fetch(client)
    assert after.headers["etag"] != before.headers["etag"]
    assert ss.registry_version() == version_before


# =================================================================================================
# The header parser, unit-tested away from the wire
# =================================================================================================


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
        ('abc', False),  # unquoted: not the same opaque string
    ],
)
def test_if_none_match_matches(header: str, expected: bool) -> None:
    assert routes._if_none_match_matches(header, 'W/"abc"') is expected


# =================================================================================================
# The measurement this feature is justified by, reproduced rather than quoted
# =================================================================================================


async def test_the_measured_sizes_are_still_in_the_range_the_docs_claim(
    client: httpx.AsyncClient,
) -> None:
    """``docs/SCALABILITY.md`` §9.1 records 149,465 bytes and 22,875 gzipped, on 2026-08-22.

    Asserted as a band and not as an equality: a field added tomorrow must not turn a documentation
    figure into a red test. What would be a real regression is the payload leaving the order of
    magnitude the argument rests on — if it ever halves or doubles, §9.1 needs re-measuring, and
    this is what says so.
    """
    import gzip

    response = await _fetch(client)
    raw = len(response.content)
    packed = len(gzip.compress(response.content, compresslevel=6))

    assert 100_000 < raw < 300_000, f"registry is now {raw} bytes; re-measure SCALABILITY.md §9.1"
    assert 15_000 < packed < 45_000, f"gzipped registry is now {packed} bytes; re-measure §9.1"
    # The compact separators FastAPI uses are what makes the digest reproducible off the wire.
    assert response.content == json.dumps(
        ss.registry_to_dict(), separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")
