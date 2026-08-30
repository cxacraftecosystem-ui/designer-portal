"""The usage recorder: what it writes, what it refuses to write, and what it must never break.

THE FAILURE THIS FILE EXISTS TO STOP IS NOT A CRASH. It is a table that quietly fills with record
ids. ``scope["path"]`` is one keystroke from ``scope["route"].path_format``, the substitution is
invisible in review, and the damage is permanent because the rows are append-only — a shadow copy of
who looked at whose artisans, sketches and interviews, assembled with no access check. So the first
two tests below are about the TEMPLATE and about the 404 placeholder, and both of them assert that
the id string appears nowhere in the row rather than merely that some other value is present.

THE SECOND CLASS OF FAILURE IS INSTRUMENTATION THAT BREAKS THE THING IT MEASURES. A middleware
between a designer and their sketch upload must not be able to swallow a response, buffer a stream,
or turn a working request into a 500 because a background writer had a bad afternoon. Four tests
cover that: a raising handler is still recorded AND still raises, a streamed body arrives byte for
byte, a recorder that throws cannot reach the client, and a database that refuses the write is a
warning in a log and nothing else.

NOTHING HERE TOUCHES A DATABASE. ``db.usageevent`` is replaced by a delegate that captures what
``create_many`` was asked to write, which is the only way to assert what would have been STORED
rather than what was computed on the way there.

IT IS NOT FAST, and the reason is one import: ``app.main`` pulls in the whole router, which pulls in
``app.services.stage_definitions``. Every module in this suite pays that once — see
``test_schema_conditional_get``'s note, which measures it in minutes and warns against quoting a wall
figure. The tests' own work here is trivial in comparison.
"""

import sys
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import APIRouter, Depends, FastAPI, Request
from fastapi.responses import StreamingResponse
from starlette.requests import HTTPConnection

import app.core.db as core_db
from app.api.routes import usage as usage_routes
from app.core import deps
from app.main import UsageEventMiddleware, _mounted_route_templates, create_app
from app.services import usage

#: A cuid of the shape this API actually mints, used as the workshop id everywhere below. It is the
#: string every "must not be stored" assertion looks for, so it is deliberately long and unmistakable
#: rather than "abc" — a three-letter id could pass a substring check by accident.
WORKSHOP_ID = "ckv9r2m4x0001qz8h3n7d2f5g"

#: The window the read tests ask for: comfortably inside the cap, and fixed rather than relative to
#: "now" so a test cannot straddle a day boundary and start reporting a different number of days.
SINCE = datetime(2026, 8, 1, tzinfo=UTC)
UNTIL = datetime(2026, 8, 15, tzinfo=UTC)


# --------------------------------------------------------------------------------------
# The fakes
# --------------------------------------------------------------------------------------


class _UsageEventTable:
    """``db.usageevent``, remembering every batch it was asked to write.

    ``create_many`` is the ONLY write path this feature has — no upsert, no counter, no revisit — so
    capturing it captures everything that could ever reach the table. ``fails`` turns it into a
    database that is refusing writes, which is what the flush-failure tests need.
    """

    def __init__(self) -> None:
        self.batches: list[list[dict[str, Any]]] = []
        self.group_by_calls: list[dict[str, Any]] = []
        self.groups: list[dict[str, Any]] = []
        self.fails = False

    async def create_many(self, data: Any) -> int:
        if self.fails:
            raise RuntimeError("the database is not accepting writes")
        rows = list(data)
        self.batches.append(rows)
        return len(rows)

    async def group_by(self, **kwargs: Any) -> list[dict[str, Any]]:
        self.group_by_calls.append(kwargs)
        by = kwargs.get("by") or []
        # Only the groupings the caller asked for come back, so a test that stubs one shape cannot
        # accidentally satisfy the other.
        return [group for group in self.groups if all(key in group for key in by)]

    @property
    def rows(self) -> list[dict[str, Any]]:
        return [row for batch in self.batches for row in batch]


@pytest.fixture
def table(monkeypatch: pytest.MonkeyPatch) -> Iterator[_UsageEventTable]:
    """A clean buffer, no route allow-list, and a fake ``db`` for the duration of one test.

    THE ALLOW-LIST IS CLEARED DELIBERATELY. ``create_app()`` installs the real application's route
    table, and the probe app below declares routes that application does not have; leaving a
    populated allow-list in place would make those legitimate probe routes record as ``<unsafe>`` and
    the failure would look like a bug in the middleware. Cleared afterwards as well as before, so a
    test that builds the real app cannot leak its list into whatever runs next.
    """
    delegate = _UsageEventTable()
    fake_db = SimpleNamespace(usageevent=delegate)
    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", fake_db)
    for module in list(sys.modules.values()):
        if (
            getattr(module, "__name__", "").startswith("app.")
            and getattr(module, "db", None) is real_db
        ):
            monkeypatch.setattr(module, "db", fake_db)
    usage.reset_buffer()
    usage.register_known_templates(())
    yield delegate
    usage.reset_buffer()
    usage.register_known_templates(())


def _probe_app() -> FastAPI:
    """A small application carrying the middleware and one route of each interesting shape.

    NOT ``create_app()``. The real application is used by the two tests that are about MOUNTING; the
    behaviour tests need routes that raise, stream and 404 on demand, and building those into a
    handful of lines is what keeps each assertion about one thing.
    """
    application = FastAPI()

    @application.get("/design-workshops/{workshop_id}")
    async def one(workshop_id: str) -> dict[str, Any]:  # pragma: no cover - exercised over HTTP
        return {"id": workshop_id}

    @application.get("/design-workshops/{workshop_id}/signed-in")
    async def signed_in(
        workshop_id: str,
        current_user: Any = Depends(deps.get_current_user),
    ) -> dict[str, Any]:  # pragma: no cover - exercised over HTTP
        return {"id": deps.get_value(current_user, "id")}

    # THE "/consented" PROBE AND ITS HAND-WRITTEN GRANT ARE GONE, AND THE DELETION IS THE POINT.
    # Until 2026-08-30 this file carried a dependency that wrote `UsageConsent.GRANTED` straight into
    # `scope["state"]`, standing in for a consent flow nobody had built. The flow exists now, so the
    # stand-in is not merely redundant — it is WRONG: `get_current_user` writes the consent key
    # itself, from the account row, and runs AFTER a route-level dependency. A hand-written grant is
    # overwritten before the handler is reached, so a test relying on one would assert that the
    # middleware records anonymously and call it a stitch failure. The honest expression of "this
    # account has agreed" is now a `usageConsent` column on the row, which is what
    # `test_the_signed_in_account_reaches_the_middleware_through_scope_state` supplies.

    @application.get("/design-workshops/{workshop_id}/boom")
    async def boom(workshop_id: str) -> dict[str, Any]:  # pragma: no cover - exercised over HTTP
        raise RuntimeError("the handler fell over")

    @application.get("/design-workshops/{workshop_id}/stream")
    async def stream(workshop_id: str) -> StreamingResponse:  # pragma: no cover - over HTTP
        async def chunks() -> Any:
            for index in range(4):
                yield f"chunk-{index};".encode()

        return StreamingResponse(chunks(), media_type="text/plain")

    @application.get("/health")
    async def health() -> dict[str, str]:  # pragma: no cover - exercised over HTTP
        return {"status": "ok"}

    @application.get("/usage/routes")
    async def usage_routes_stub() -> dict[str, Any]:  # pragma: no cover - exercised over HTTP
        return {"items": []}

    application.add_middleware(UsageEventMiddleware)
    return application


def _range(since: datetime, until: datetime) -> dict[str, str]:
    """The window as a client would actually send it: ISO-8601 strings on the query string, not
    Python objects. Written out rather than inlined so every read test asks for the same window and a
    change to it is one edit."""
    return {"from": since.isoformat(), "to": until.isoformat()}


async def _get(application: FastAPI, path: str, **kwargs: Any) -> httpx.Response:
    transport = httpx.ASGITransport(app=application, raise_app_exceptions=False)
    async with httpx.AsyncClient(transport=transport, base_url="http://usage.test") as client:
        return await client.get(path, **kwargs)


async def _written(table: _UsageEventTable) -> list[dict[str, Any]]:
    """Everything the buffer would actually put in the database, through the real writer.

    Deliberately NOT a read of ``usage._BUFFER``. The row that matters is the row ``create_many`` is
    handed, after the consent rule has decided whether the identity survives and after the withdrawn
    filter has run — asserting on the buffer would test an earlier draft of the row.
    """
    await usage.flush()
    return table.rows


# --------------------------------------------------------------------------------------
# Where it is mounted
# --------------------------------------------------------------------------------------


@pytest.fixture(scope="module")
def real_app() -> Iterator[tuple[FastAPI, frozenset[str]]]:
    """The real ``create_app()``, plus the route allow-list it installed while building.

    The allow-list is captured HERE rather than read back inside a test because it is module-level
    state in ``services/usage`` that the function-scoped ``table`` fixture deliberately clears. The
    list is emptied before the build so that whatever comes back demonstrably came from this call and
    not from a leftover, and emptied again afterwards so this module leaves nothing behind.

    ``SCALE_RATE_LIMIT_ENABLED`` is forced off so the stack assertion below is about a named
    configuration rather than about whatever ``backend/.env`` happens to hold; ``get_settings`` is
    ``lru_cache``d, so the cache has to be dropped either side of the build.
    """
    with pytest.MonkeyPatch.context() as patch:
        patch.setenv("SCALE_RATE_LIMIT_ENABLED", "false")
        from app.core.config import get_settings

        get_settings.cache_clear()
        usage.register_known_templates(())
        application = create_app()
        installed = usage.known_templates()
        yield application, installed
    from app.core.config import get_settings as _settings

    _settings.cache_clear()
    usage.register_known_templates(())


def test_the_recorder_sits_inside_cors_and_outside_the_router(
    real_app: tuple[FastAPI, frozenset[str]],
) -> None:
    """THE WHOLE STACK, in outermost-first order, asserted as a sequence.

    Starlette runs the most recently added middleware outermost, so ``user_middleware[0]`` is the
    outer layer and each later entry is further in. Reading the list: gzip outermost (it must see the
    finished body and own ``content-length``), the security headers next so they land on preflights
    too, then CORS, then THIS, then the error handler, then the router.

    Every neighbour is load-bearing and none of it is stylistic:

    * BELOW CORS, so a recorded response still travels back out through the layer that stamps
      ``access-control-allow-origin``. Above it, the recorder would be measuring responses the browser
      cannot read.
    * ABOVE the router — which is what ``user_middleware`` membership means at all — because
      ``scope["route"]`` does not exist until the router has matched. There is no position below the
      router from which the template is knowable, which is why this cannot be a dependency.
    * ABOVE ``UnhandledErrorMiddleware``, so a crashed handler arrives here as the 500 the client
      received rather than as an exception with no status.

    Asserted as the entire list rather than as two neighbours, copying
    ``test_rate_limit_install``'s reasoning: it is the version of this test that fails when somebody
    adds a seventh middleware in the wrong place instead of silently continuing to pass.
    """
    application, _ = real_app

    stack = [middleware.cls.__name__ for middleware in application.user_middleware]

    assert stack == [
        "SelectiveGZipMiddleware",
        "SecurityHeadersMiddleware",
        "CORSMiddleware",
        "UsageEventMiddleware",
        "UnhandledErrorMiddleware",
    ]


def test_building_the_app_installs_the_real_route_table_as_an_allow_list(
    real_app: tuple[FastAPI, frozenset[str]],
) -> None:
    """The allow-list is what makes "a raw path cannot be stored" a property rather than a regex.

    THE FAILURE MODE THIS PINS IS SILENT AND VERSION-DEPENDENT. FastAPI 0.141 stopped flattening
    included routers: ``app.routes`` is a handful of the application's own routes plus ONE opaque
    entry standing for the entire API, so the obvious ``[r.path_format for r in app.routes]`` would
    install four templates and leave two hundred out — after which every real route in the product
    would record as ``<unsafe>`` and its traffic would vanish from every aggregate. Nothing would
    raise. The count below is what notices.

    It asserts a floor rather than an exact number on purpose: the route table grows every week, and
    a test that has to be edited whenever somebody adds an endpoint is a test that gets edited without
    being read.
    """
    application, installed = real_app

    templates = _mounted_route_templates(application)

    assert len(templates) > 100, "the walk did not reach inside the mounted API router"
    assert "/design-workshops/{workshop_id}" in templates
    assert "/usage/routes" in templates
    # UNPREFIXED — no `/api` — because the objects carrying these are the ORIGINAL route objects,
    # and that is the form `scope["route"].path_format` reports at request time. The two agree
    # because they are literally the same objects, which is the property that matters and the reason
    # this walk is worth preferring over re-deriving paths by hand.
    assert "/design-workshops/{workshop_id}" in templates
    # THE ONE ROUTE THAT IS PREFIXED, AND IT IS NOT A DEFECT. `/me` is added straight onto the
    # already-prefixed `api_router` with `add_api_route`, which stamps the prefix onto the route
    # object itself, where `include_router` leaves a sub-router's own routes alone. Both shapes are
    # therefore legitimate entries in this table. Asserted rather than merely tolerated because a
    # reader comparing the allow-list against the route modules will otherwise take it for a bug —
    # and because if a FastAPI upgrade ever prefixes EVERYTHING, the same route's traffic would split
    # across two template strings with no error anywhere, and this is the line that would notice.
    assert "/api/me" in templates
    # Installed, not merely computable: the list has to have reached the service or it defends
    # nothing.
    assert "/design-workshops/{workshop_id}" in installed
    # And every entry survives the service's own gate, so the allow-list cannot be the thing that
    # starts refusing real routes.
    assert [template for template in templates if not usage.is_route_template(template)] == []


def test_fastapis_own_documentation_routes_are_left_out_of_the_allow_list(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """THE ALLOW-LIST MUST HOLD ONLY TEMPLATES THE RECORDER CAN ACTUALLY PRODUCE.

    ``scope["route"]`` is written by FastAPI's ``APIRoute`` and by nothing else. ``/openapi.json``,
    ``/docs``, ``/docs/oauth2-redirect`` and ``/redoc`` are plain Starlette routes, so a request that
    reaches one is served, answers 200, and STILL leaves the key absent — the recorder has no name
    for it and files it under ``<unmatched>``.

    Registering those four anyway — which the first draft of ``_mounted_route_templates`` did, because
    they carry a ``path_format`` like everything else — put both halves of a contradiction into the
    same table: their real traffic in the 404 bucket, and their names in ``GET /usage/routes`` as
    four screens reporting zero for ever. That is the failure the default listing's own comment
    refuses in as many words: a row that is structurally always zero reads as "nobody uses this
    screen" rather than as "this screen is not measured", and the two are opposite facts.

    ``BACKEND_EXPOSE_DOCS`` is forced ON, because it is off by default and a test that only ever runs
    against the default would pass on an application that does not mount the routes it is about. It
    is on in local development and on the dev cluster, which is exactly where somebody first looks at
    these numbers.
    """
    from app.core.config import get_settings

    monkeypatch.setenv("BACKEND_EXPOSE_DOCS", "true")
    monkeypatch.setenv("SCALE_RATE_LIMIT_ENABLED", "false")
    get_settings.cache_clear()
    try:
        application = create_app()
        templates = _mounted_route_templates(application)
    finally:
        get_settings.cache_clear()
        usage.register_known_templates(())

    served = {getattr(route, "path_format", None) for route in application.routes}
    # The premise: this application really is serving them. Without this the rest asserts nothing.
    assert {"/openapi.json", "/docs", "/redoc"} <= served

    for documentation in ("/openapi.json", "/docs", "/docs/oauth2-redirect", "/redoc"):
        assert documentation not in templates, (
            f"{documentation} cannot ever be the value the recorder writes, so it must not be in "
            f"the vocabulary GET /usage/routes pages through"
        )
    # And the filter is by route CLASS, not by path: an APIRoute mounted directly on the application
    # still makes it in, which is what keeps /health in the list the skip set is subtracted from.
    assert "/health" in templates
    assert "/design-workshops/{workshop_id}" in templates


async def test_a_route_fastapi_never_names_is_recorded_as_the_placeholder(
    table: _UsageEventTable,
) -> None:
    """The other half of the test above, over HTTP: what actually gets written for such a request.

    ``<unmatched>`` is the honest answer — the middleware has no template and must not invent one
    from ``scope["path"]``, which is the whole discipline of this feature — so the placeholder is
    where a served-but-unnamed route belongs. What must never happen is the path appearing in the
    row, and that is asserted against the whole row rather than against one key.
    """
    application = FastAPI()
    router = APIRouter(prefix="/design-workshops")

    @router.get("/{workshop_id}")
    async def one(workshop_id: str) -> dict[str, str]:  # pragma: no cover - exercised over HTTP
        return {"id": workshop_id}

    # Included rather than declared on the application, because `_mounted_route_templates` refuses
    # to install a list that reached nothing beyond the app's own routes — the guard that notices a
    # FastAPI change breaking the walk.
    application.include_router(router)
    application.add_middleware(UsageEventMiddleware)
    usage.register_known_templates(_mounted_route_templates(application))

    assert "/openapi.json" not in usage.known_templates()

    response = await _get(application, "/openapi.json")
    rows = await _written(table)

    assert response.status_code == 200
    assert [row["routeTemplate"] for row in rows] == [usage.UNMATCHED_ROUTE]
    assert "openapi" not in str(rows)


# --------------------------------------------------------------------------------------
# What gets written
# --------------------------------------------------------------------------------------


async def test_one_request_records_one_event_with_the_template_not_the_path(
    table: _UsageEventTable,
) -> None:
    """THE CENTRAL ASSERTION OF THE WHOLE FEATURE.

    One served request produces exactly one row; that row names the ROUTE and not the path; and the
    workshop id that travelled in the URL appears in no value anywhere. The last clause is the one
    that matters — asserting only that ``routeTemplate`` is right would still pass if a later edit
    added a ``path`` key beside it, which is precisely how this data leaks.
    """
    response = await _get(_probe_app(), f"/design-workshops/{WORKSHOP_ID}")
    rows = await _written(table)

    assert response.status_code == 200
    assert len(rows) == 1
    row = rows[0]
    assert row["routeTemplate"] == "/design-workshops/{workshop_id}"
    assert row["method"] == "GET"
    assert row["statusCode"] == 200
    assert row["clientApp"] == "api"
    assert WORKSHOP_ID not in " ".join(str(value) for value in row.values())
    # A duration, in whole milliseconds, measured on a monotonic clock — so it is a number and it is
    # not negative. Both halves matter: a wall clock that steps backwards produces the second.
    assert isinstance(row["durationMs"], int)
    assert row["durationMs"] >= 0


async def test_a_404_records_a_placeholder_and_never_the_url_that_was_asked_for(
    table: _UsageEventTable,
) -> None:
    """A 404 SWEEP MUST NOT BE A WRITE PATH FOR RAW URLs.

    Nothing sets ``scope["route"]`` when no route matched, so the tempting fallback is
    ``scope["path"]`` — and a scanner walking ``/artisans/<id>`` would then write one distinct record
    id per request into an append-only table, through the one door nobody thinks to guard. A fixed
    placeholder groups every unmatched request into one row's worth of vocabulary.

    The trailing-slash redirect is checked in the same test because it is the non-obvious half:
    Starlette matches the redirect against a COPY of the scope, so the original is never stamped and
    a 307 looks exactly like a 404 from here.
    """
    application = _probe_app()

    missing = await _get(application, f"/artisans/{WORKSHOP_ID}")
    redirect = await _get(application, f"/design-workshops/{WORKSHOP_ID}/")
    rows = await _written(table)

    assert missing.status_code == 404
    assert redirect.status_code == 307
    assert [row["routeTemplate"] for row in rows] == [usage.UNMATCHED_ROUTE, usage.UNMATCHED_ROUTE]
    assert [row["statusCode"] for row in rows] == [404, 307]
    assert WORKSHOP_ID not in " ".join(str(value) for row in rows for value in row.values())


async def test_the_signed_in_account_reaches_the_middleware_through_scope_state(
    table: _UsageEventTable, monkeypatch: pytest.MonkeyPatch
) -> None:
    """THE STITCH, END TO END, over a real request.

    Neither half can do this alone: a pure-ASGI middleware never decodes a bearer token, and
    ``get_current_user`` never sees a status code or a duration. They meet in ``scope["state"]``,
    which Starlette's ``request.state`` writes into by reference.

    The account here carries a recorded GRANT — the ``usageConsent`` column, exactly as a real row
    does — because that is the only circumstance in which the identity survives into the written row.
    See the next test for what an unanswered account produces. Asserting on a granted request is what
    makes this a test of the STITCH rather than of the consent rule.

    **BOTH HALVES OF THE STITCH ARE EXERCISED HERE AND NEITHER IS SIMULATED.** Until 2026-08-30 the
    consent half was a hand-written value pushed into ``scope["state"]`` by a probe dependency,
    because no column existed to read. It is now ``usage.resolve_consent`` reading the column off the
    row ``get_current_user`` already loaded — so this test now fails if the column is renamed, which
    is the failure that would otherwise be silent: a ``getattr`` miss resolves to NOT_RECORDED and
    the whole fleet reverts to anonymous with nothing going red.
    """

    async def _user(credentials: Any, *, allowed_scopes: Any) -> Any:
        return SimpleNamespace(id="u-42", role="DESIGNER", usageConsent="GRANTED")

    monkeypatch.setattr(deps, "_user_from_bearer", _user)

    response = await _get(_probe_app(), f"/design-workshops/{WORKSHOP_ID}/signed-in")
    rows = await _written(table)

    assert response.status_code == 200
    assert len(rows) == 1
    assert rows[0]["userId"] == "u-42"
    assert rows[0]["consentState"] == "GRANTED"
    assert rows[0]["routeTemplate"] == "/design-workshops/{workshop_id}/signed-in"


async def test_an_anonymous_request_carries_no_account(table: _UsageEventTable) -> None:
    """NULL is a first-class answer here and not a gap. An unauthenticated request still has a route,
    a status and a duration, and "the sign-in page is slow for the people who cannot get in" is named
    in the schema as a thing this table should be able to show."""
    await _get(_probe_app(), f"/design-workshops/{WORKSHOP_ID}")
    rows = await _written(table)

    assert rows[0]["userId"] is None
    assert rows[0]["consentState"] is None


async def test_the_default_policy_records_the_request_and_drops_the_name(
    table: _UsageEventTable, monkeypatch: pytest.MonkeyPatch
) -> None:
    """THE HONEST CONSEQUENCE OF THE CONSENT DEFAULT, PINNED SO IT CANNOT DRIFT SILENTLY.

    The stitch above delivers the account id on every authenticated request. Whether it reaches the
    database is a separate decision, made in ``usage.collection_plan``, and today the answer is no:
    nobody has been asked, ``DEFAULT_UNASKED_COLLECTION`` is ``ANONYMOUS``, and the row is written
    with ``userId`` NULL and ``consentState`` NULL — which the schema defines as NOBODY WAS ASKED.

    This is asserted rather than assumed because the difference between this test and the one above
    it is the entire privacy posture of the feature, and because somebody reading only the stitch
    would reasonably conclude the id is being stored. If the default is ever changed, this test is
    where that change becomes visible.
    """

    async def _user(credentials: Any, *, allowed_scopes: Any) -> Any:
        # NOT_RECORDED, which is what an account that has not answered carries — and what a row
        # restored from before the consent column existed resolves to. Never GRANTED by omission.
        return SimpleNamespace(id="u-42", role="DESIGNER", usageConsent="NOT_RECORDED")

    monkeypatch.setattr(deps, "_user_from_bearer", _user)

    response = await _get(_probe_app(), f"/design-workshops/{WORKSHOP_ID}/signed-in")
    rows = await _written(table)

    assert response.json() == {"id": "u-42"}, "the identity did reach the handler"
    assert usage.DEFAULT_UNASKED_COLLECTION is usage.UnaskedCollection.ANONYMOUS
    assert rows[0]["routeTemplate"] == "/design-workshops/{workshop_id}/signed-in"
    assert rows[0]["userId"] is None
    assert rows[0]["consentState"] is None


async def test_the_health_probes_and_the_usage_reads_are_not_recorded(
    table: _UsageEventTable,
) -> None:
    """WHAT IS DELIBERATELY NOT MEASURED, and both entries are decisions rather than omissions.

    ``/health`` arrives on a monitoring timer rather than because anybody navigated anywhere; at one
    probe every few seconds it would be the most-used "screen" in the product for ever. The ``/usage``
    read routes are skipped because recording them would make the dataset partly a record of itself —
    a dashboard left open would raise "requests per day" on its own.

    The list is published by ``GET /usage/collection``, and this asserts the recorder and the
    published method agree, which is the only thing that stops the two drifting.
    """
    application = _probe_app()

    await _get(application, "/health")
    await _get(application, "/usage/routes")
    await _get(application, f"/design-workshops/{WORKSHOP_ID}")
    rows = await _written(table)

    assert [row["routeTemplate"] for row in rows] == ["/design-workshops/{workshop_id}"]
    assert {"/health", "/usage/routes"} <= usage_routes.UNRECORDED_TEMPLATES


# --------------------------------------------------------------------------------------
# What it must never break
# --------------------------------------------------------------------------------------


async def test_a_handler_that_raises_is_still_recorded_and_still_raises(
    table: _UsageEventTable,
) -> None:
    """DO NOT BREAK THE EXCEPTION PATH — and do not go blind on it either.

    The recording is in a ``finally`` precisely for this: the request that crashed is the one anybody
    investigating an outage most wants to see, and it is the one a recorder written on the happy path
    would silently omit. The status is 500 because that is what the client receives from Starlette's
    own error middleware, which sits outside every middleware here.

    The second assertion is the more important one. ``raise_app_exceptions=False`` on the transport is
    what lets the test see the response rather than the exception; the exception itself must still
    have travelled — a middleware that swallowed it would have turned a crash into a silent 200 in
    production, which is a far worse outcome than not measuring it.
    """
    response = await _get(_probe_app(), f"/design-workshops/{WORKSHOP_ID}/boom")
    rows = await _written(table)

    assert response.status_code == 500
    assert len(rows) == 1
    assert rows[0]["routeTemplate"] == "/design-workshops/{workshop_id}/boom"
    assert rows[0]["statusCode"] == 500


async def test_a_streamed_response_passes_through_byte_for_byte(table: _UsageEventTable) -> None:
    """This API streams CSV, NDJSON and media. A recorder that buffered a body to count it would put
    a queue between every export and its client, and would hold a multi-megabyte download in memory
    to learn a number it already had. The middleware forwards every ``http.response.body`` message
    untouched; this is what proves it."""
    response = await _get(_probe_app(), f"/design-workshops/{WORKSHOP_ID}/stream")
    rows = await _written(table)

    assert response.text == "chunk-0;chunk-1;chunk-2;chunk-3;"
    assert rows[0]["routeTemplate"] == "/design-workshops/{workshop_id}/stream"
    assert rows[0]["statusCode"] == 200


async def test_a_recorder_that_throws_cannot_reach_the_client(
    table: _UsageEventTable, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    """MEASUREMENT THAT CAN 500 A SKETCH UPLOAD IS WORSE THAN NO MEASUREMENT.

    ``record_event`` never raises by contract; this replaces it with one that does, because a contract
    is a promise about today's code and the ``try`` in the middleware is the thing that survives
    somebody changing it. An exception escaping the middleware's ``finally`` would arrive after the
    response had already started and would drop a connection whose body the client had begun reading.

    The log line is asserted too: failing silently would leave the dataset short with nothing anywhere
    saying so.
    """

    def _explode(**kwargs: Any) -> bool:
        raise RuntimeError("the recorder fell over")

    monkeypatch.setattr(usage, "record_event", _explode)

    with caplog.at_level("WARNING"):
        response = await _get(_probe_app(), f"/design-workshops/{WORKSHOP_ID}")

    assert response.status_code == 200
    assert response.json() == {"id": WORKSHOP_ID}
    assert [r for r in caplog.records if "could not record" in r.getMessage()], caplog.text


async def test_a_database_that_refuses_the_write_never_reaches_a_request(
    table: _UsageEventTable, caplog: pytest.LogCaptureFixture
) -> None:
    """THE WRITER IS OFF THE REQUEST PATH, AND THIS IS WHERE THAT IS WORTH PROVING.

    ``flush`` runs on a background task; by the time it fails the response is long gone. So a failed
    write is a warning in the log and a retry, never an exception and never a status code — and the
    request served WHILE the database is refusing writes is still a normal 200.

    The rows are held for one more attempt rather than thrown away, and the request that generated
    them is unaffected either way; ``FLUSH_MAX_ATTEMPTS`` is what stops a poison batch being
    re-offered for ever while fresh rows are evicted behind it.
    """
    table.fails = True

    with caplog.at_level("WARNING"):
        response = await _get(_probe_app(), f"/design-workshops/{WORKSHOP_ID}")
        written = await usage.flush()

    assert response.status_code == 200
    assert written == 0
    assert table.batches == []
    assert [r for r in caplog.records if "could not write" in r.getMessage()], caplog.text
    # Held, not lost: the next attempt still has them.
    assert usage.buffer_stats()["retryPending"] == 1


async def test_an_outage_abandons_batches_and_never_reaches_the_buffer_ceiling(
    table: _UsageEventTable,
) -> None:
    """WHICH COUNTER MOVES WHEN THE DATABASE IS AWAY — and it is not the one the numbers suggest.

    An earlier comment on ``BUFFER_CEILING`` read "at a sustained 20 requests a second, 5,000 rows is
    roughly four minutes of total database unavailability before anything is lost at all". That is
    wrong, and wrong in the direction an operator plans around. Simulated against this module's own
    writer at exactly that rate, with every write refused: the first row is lost after 5.25 seconds,
    and four minutes in, 4,720 of 4,800 rows are gone with ``droppedAtCeiling`` still reading zero.

    The mechanism is ``FLUSH_MAX_ATTEMPTS``: a drained batch is offered twice and then written off,
    so the buffer is emptied by the writer whether or not the write lands and never accumulates
    towards the ceiling at all. The ceiling is a MEMORY budget; the attempt count is the outage
    budget. This test pins the distinction in the two counters, because they are the numbers
    ``GET /usage/collection`` publishes and reading one for the other reports an outage as no loss.

    Two flushes rather than a simulated four minutes: the abandonment is what the arithmetic turns
    on, and it happens on the second attempt. A test that slept through an outage would assert the
    same thing and take five minutes to do it.
    """
    table.fails = True
    for _ in range(usage.FLUSH_ROWS):
        usage.record_event(
            route_template="/design-workshops/{workshop_id}",
            method="GET",
            status_code=200,
            duration_ms=3,
        )

    assert await usage.flush() == 0  # attempt one: held for a retry
    assert usage.buffer_stats()["abandoned"] == 0
    assert await usage.flush() == 0  # attempt two: written off

    stats = usage.buffer_stats()
    assert stats["abandoned"] == usage.FLUSH_ROWS
    # THE HALF THAT WOULD HAVE PASSED SILENTLY UNDER THE OLD STORY. Nothing was evicted at the
    # ceiling, because nothing ever got near it — an operator reading `droppedAtCeiling` for the cost
    # of this outage would read zero and conclude nothing was lost.
    assert stats["dropped"] == 0
    assert stats["buffered"] == 0
    assert stats["retryPending"] == 0


async def test_get_current_user_writes_only_the_identity_into_scope_state(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """THE DEPENDENCY HALF OF THE STITCH, ON ITS OWN, WITH NO WEB SERVER AND NO DATABASE.

    Two claims, and the second is the one that will decay. First: both values land under the keys the
    service declares — not keys spelled the same way in two files, which is how a stitch goes quietly
    dead and files every request as anonymous. Second: it writes NOTHING ELSE. This function runs on
    every authenticated request in the product, and the pressure to add "just one more" lookup to it
    is exactly why the assertion is on the whole dict rather than on one key.

    **THE SECOND KEY ARRIVED WITH THE CONSENT FLOW ON 2026-08-30 AND COSTS THE SAME AS THE FIRST:
    nothing.** ``usage.resolve_consent`` is a ``getattr`` off the row that has already been loaded to
    authenticate the request plus an enum lookup — no query, no await, no branch that can raise. It
    was written with that signature a migration before the column existed, precisely so that wiring
    it up here would not put a round trip on the hot path. This account carries no consent column at
    all, which is what a row hand-built in a test looks like and also what a row restored from before
    the migration looks like: both resolve to NOT_RECORDED, never to GRANTED.
    """

    async def _user(credentials: Any, *, allowed_scopes: Any) -> Any:
        return SimpleNamespace(id="u-42", role="DESIGNER")

    monkeypatch.setattr(deps, "_user_from_bearer", _user)
    scope: dict[str, Any] = {"type": "http", "method": "GET", "path": "/x", "headers": []}

    user = await deps.get_current_user(Request(scope), credentials=None)

    assert deps.get_value(user, "id") == "u-42"
    assert scope["state"] == {
        usage.USAGE_USER_ID_KEY: "u-42",
        usage.USAGE_CONSENT_KEY: usage.UsageConsent.NOT_RECORDED,
    }


async def test_the_stitch_asks_for_a_connection_and_not_for_a_request(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """``get_current_user`` TAKES ``HTTPConnection`` AND NOT ``Request``, AND THIS IS THE ONE WORD.

    FastAPI fills a ``Request`` parameter only when the connection IS one —
    ``dependencies/utils.solve_dependencies`` guards it with ``isinstance(request, Request)`` and a
    WebSocket takes the ``elif`` — so a ``Request``-typed parameter on the dependency every
    ``require_*`` in ``deps.py`` funnels through would mean every future WebSocket route dies at
    handshake with ``TypeError: get_current_user() missing 1 required positional argument``. There is
    no WebSocket route in this API today, which is exactly how the narrower annotation would have sat
    unnoticed until somebody added the first one.

    **WHAT THIS TEST DOES NOT CLAIM, because it was written and then measured rather than the other
    way round.** The annotation does not by itself make a WebSocket route work: ``bearer_scheme`` is
    FastAPI's ``HTTPBearer``, whose own ``__call__(self, request: Request)`` hits the identical
    guard, so such a route still fails — with ``TypeError: HTTPBearer.__call__() missing 1 required
    positional argument: 'request'``. Asserting a working handshake here would therefore assert
    something false. What is asserted instead is the pair of facts this repository owns: FastAPI
    resolves the parameter from the CONNECTION rather than from the request, and the stitch itself
    works off a scope that is not HTTP at all.
    """
    from fastapi.dependencies.utils import get_dependant

    dependant = get_dependant(path="/", call=deps.get_current_user)
    assert dependant.http_connection_param_name == "connection"
    assert dependant.request_param_name is None, (
        "get_current_user asks FastAPI for a Request; on a WebSocket scope FastAPI will not fill it "
        "and the call raises TypeError. Annotate it HTTPConnection — see the docstring there."
    )

    async def _user(credentials: Any, *, allowed_scopes: Any) -> Any:
        return SimpleNamespace(id="u-42", role="DESIGNER")

    monkeypatch.setattr(deps, "_user_from_bearer", _user)
    # A websocket scope, and the point is that `.state` is defined on HTTPConnection rather than on
    # Request — so the stitch is written the same way whatever the connection turns out to be.
    scope: dict[str, Any] = {"type": "websocket", "path": "/ws", "headers": []}

    user = await deps.get_current_user(HTTPConnection(scope), credentials=None)

    assert deps.get_value(user, "id") == "u-42"
    assert scope["state"] == {
        usage.USAGE_USER_ID_KEY: "u-42",
        usage.USAGE_CONSENT_KEY: usage.UsageConsent.NOT_RECORDED,
    }


# --------------------------------------------------------------------------------------
# The read API
# --------------------------------------------------------------------------------------


_CALLER: dict[str, Any] = {"user": None}


def _read_app() -> FastAPI:
    """The read routes with the identity overridden and the GATES LEFT REAL.

    ``require_usage_reader`` depends on ``get_current_user``, and FastAPI applies a dependency
    override to nested dependencies too — so overriding the identity leaves the rank check itself
    running against the account under test. Overriding the gate instead would have produced a test
    that asserts nothing.
    """
    application = FastAPI()
    application.include_router(usage_routes.router)
    application.dependency_overrides[deps.get_current_user] = lambda: _CALLER["user"]
    return application


@pytest.fixture
def caller() -> Iterator[dict[str, Any]]:
    _CALLER["user"] = None
    yield _CALLER
    _CALLER["user"] = None


async def test_the_aggregates_refuse_a_designer_and_say_where_their_own_data_is(
    table: _UsageEventTable, caller: dict[str, Any]
) -> None:
    """THE GATE IS THE SERVER'S, AND IT IS THE WHOLE OF IT.

    This repository has twice shipped a UI guard over an open endpoint — the link disappeared and the
    URL stayed open — so the dependency is the boundary and ``frontend/lib/permissions.ts`` merely
    mirrors it. A designer is refused the cross-account aggregates for the reason
    ``can_manage_designer_roster`` already gates a READ at Admin: this is a record of what colleagues
    did, which is more revealing than the roster that gate exists to protect.

    The refusal names the next move rather than only saying no. A person told "403" about their own
    platform learns nothing; a person told where their own usage lives can go and read it.
    """
    caller["user"] = SimpleNamespace(id="d-1", role="DESIGNER")
    application = _read_app()

    routes = await _get(application, "/usage/routes", params=_range(SINCE, UNTIL))
    collection = await _get(application, "/usage/collection")

    assert routes.status_code == 403
    assert collection.status_code == 403
    assert "/api/usage/me" in routes.json()["detail"]


async def test_a_designer_may_always_read_their_own_usage(
    table: _UsageEventTable, caller: dict[str, Any]
) -> None:
    """The inverse of the test above, and the reason the split exists.

    ``/usage/me`` takes the account from the token and offers no parameter for any other, so there is
    nothing here to gate — the alternative would be a product in which a designer has to ask an
    administrator for permission to see what was recorded about them.

    The query is asserted as well as the status: it must be an equality on ``userId`` plus a date
    range, which is the shape ``@@index([userId, createdAt])`` serves, and it must be THIS caller's
    id.
    """
    caller["user"] = SimpleNamespace(id="d-1", role="DESIGNER")

    response = await _get(_read_app(), "/usage/me", params=_range(SINCE, UNTIL))

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["userId"] == "d-1"
    assert table.group_by_calls[0]["where"]["userId"] == "d-1"
    assert set(table.group_by_calls[0]["where"]["createdAt"]) == {"gte", "lt"}


async def test_every_answer_states_the_window_cap_rather_than_leaving_it_to_be_discovered(
    table: _UsageEventTable, caller: dict[str, Any]
) -> None:
    """A CAP THAT IS ANNOUNCED IS A CAP; A CAP THAT IS SILENT IS A LIE — ``analytics.ROW_CAP``'s rule,
    applied to a date range instead of a row count.

    The cap rides on the SUCCESSFUL response and not only in the refusal, because a bound learned by
    tripping over it is a bound that gets tripped over. The interval and the naive-date reading are
    stated for the same reason: a client that meant local time can see that it did not get it, rather
    than reading a window that is quietly wrong by hours.
    """
    caller["user"] = SimpleNamespace(id="a-1", role="ADMIN")
    application = _read_app()

    mine = await _get(application, "/usage/me", params=_range(SINCE, UNTIL))
    routes = await _get(application, "/usage/routes", params=_range(SINCE, UNTIL))

    for response in (mine, routes):
        window = response.json()["window"]
        assert window["maxDays"] == usage.MAX_RANGE_DAYS
        assert window["days"] == 14
        assert window["interval"] == "[from, to)"
        assert window["naiveDatesReadAs"] == "UTC"

    limits = routes.json()["limits"]
    assert limits["maxRoutesPerRequest"] == usage.MAX_TEMPLATES_PER_QUERY
    assert limits["minimumIdentifiedUsers"] == usage.MIN_IDENTIFIED_USERS_FOR_ROUTE


async def test_a_window_wider_than_the_cap_is_refused_with_the_number_in_the_sentence(
    table: _UsageEventTable, caller: dict[str, Any]
) -> None:
    """REFUSED, NOT QUIETLY NARROWED. Narrowing would answer a different question from the one asked
    and label the answer with the dates that were asked for — a report whose own header is wrong. The
    refusal carries the cap and the width of the request, so the next attempt can be right first time
    rather than by bisection."""
    caller["user"] = SimpleNamespace(id="a-1", role="ADMIN")
    too_wide = SINCE + timedelta(days=usage.MAX_RANGE_DAYS + 5)

    response = await _get(_read_app(), "/usage/routes", params=_range(SINCE, too_wide))

    assert response.status_code == 400
    detail = response.json()["detail"]
    assert str(usage.MAX_RANGE_DAYS) in detail
    assert str(usage.MAX_RANGE_DAYS + 5) in detail
    assert table.group_by_calls == [], "the refusal happened before any database work"


async def test_the_route_aggregate_pages_and_never_emits_a_user_id(
    table: _UsageEventTable, caller: dict[str, Any]
) -> None:
    """Two things at once, because they are the same guarantee seen from two sides.

    The response is the house's ``page_payload`` — ``items``/``total``/``page``/``pageSize``/``pages``
    — rather than a bespoke shape invented for one screen. And no user id comes back: the
    distinct-account count is folded into an integer inside ``services/usage.py``, so this route has
    no identity to emit even if a later edit tried to. The grouping that DOES see identities is asked
    for here; the assertion is that nothing it returned reached the wire.
    """
    caller["user"] = SimpleNamespace(id="a-1", role="ADMIN")
    template = "/design-workshops/{workshop_id}"
    table.groups = [
        {
            "routeTemplate": template,
            "statusCode": 200,
            "_count": {"_all": 40},
            "_avg": {"durationMs": 120.0},
            "_max": {"durationMs": 900},
        },
        {
            "routeTemplate": template,
            "statusCode": 500,
            "_count": {"_all": 2},
            "_avg": {"durationMs": 30.0},
            "_max": {"durationMs": 60},
        },
    ] + [
        {"routeTemplate": template, "userId": f"u-{index}", "_count": {"_all": 6}}
        for index in range(6)
    ]

    response = await _get(
        _read_app(), "/usage/routes", params={**_range(SINCE, UNTIL), "template": template}
    )

    body = response.json()
    assert response.status_code == 200, response.text
    assert {"items", "total", "page", "pageSize", "pages"} <= set(body)
    assert body["items"][0]["routeTemplate"] == template
    assert body["items"][0]["requests"] == 42
    assert body["items"][0]["identifiedUsers"] == 6
    assert body["totalsForThisPage"] == {
        "routes": 1,
        "routesWithheld": 0,
        "requests": 42,
        "ok": 40,
        "clientErrors": 0,
        "serverErrors": 2,
    }
    assert "u-0" not in response.text


async def test_a_screen_too_few_people_used_is_withheld_rather_than_reported_as_a_number(
    table: _UsageEventTable, caller: dict[str, Any]
) -> None:
    """AN AGGREGATE OVER TWO PEOPLE IS NOT AN AGGREGATE.

    "Who opened the artisan screen at 2 a.m." is answerable from a page labelled *aggregates* the
    moment a route has one user in the window — and the window is chosen by whoever is asking. So a
    route with between one and ``MIN_IDENTIFIED_USERS_FOR_ROUTE`` identified accounts comes back with
    every metric ``null`` and ``withheld`` true.

    The withheld route is excluded from the page totals and COUNTED there, rather than being folded in
    as a zero: adding a refusal to a sum reports a smaller number as though it were the truth.
    """
    caller["user"] = SimpleNamespace(id="a-1", role="ADMIN")
    template = "/design-workshops/{workshop_id}"
    table.groups = [
        {
            "routeTemplate": template,
            "statusCode": 200,
            "_count": {"_all": 9},
            "_avg": {"durationMs": 100.0},
            "_max": {"durationMs": 400},
        },
        {"routeTemplate": template, "userId": "u-1", "_count": {"_all": 5}},
        {"routeTemplate": template, "userId": "u-2", "_count": {"_all": 4}},
    ]

    response = await _get(
        _read_app(), "/usage/routes", params={**_range(SINCE, UNTIL), "template": template}
    )

    entry = response.json()["items"][0]
    assert entry["withheld"] is True
    assert entry["requests"] is None
    assert entry["identifiedUsers"] is None
    assert str(usage.MIN_IDENTIFIED_USERS_FOR_ROUTE) in entry["withheldBecause"]
    assert response.json()["totalsForThisPage"] == {
        "routes": 0,
        "routesWithheld": 1,
        "requests": 0,
        "ok": 0,
        "clientErrors": 0,
        "serverErrors": 0,
    }


async def test_the_collection_method_states_the_consent_default_and_the_losses(
    table: _UsageEventTable, caller: dict[str, Any]
) -> None:
    """REQUIREMENT 26'S MACHINE-READABLE HALF: a figure and its method, quotable together.

    A methodology section that describes an intended design rather than the running one is how a
    paper ends up reporting a number its own system never produced. So this endpoint reports the
    policy actually in force and the observations this process actually lost — and the loss counters
    are asserted here because a dataset that loses rows without saying how many is a dataset nobody
    can check.
    """
    caller["user"] = SimpleNamespace(id="a-1", role="ADMIN")

    body = (await _get(_read_app(), "/usage/collection")).json()

    assert body["consent"]["unaskedPolicy"] == usage.DEFAULT_UNASKED_COLLECTION.value
    # TRUE SINCE 2026-08-30. It was False for as long as there was no column, no route and no
    # screen, and a reader who quoted this endpoint in a methods section during that period was
    # quoting an honest No. `unaskedPolicy` above is a SEPARATE fact and did not move with it: it
    # governs an account that has not yet answered, which is now overwhelmingly a request with no
    # account at all.
    assert body["consent"]["flowExists"] is True
    assert body["consent"]["noticeVersion"] == usage.NOTICE_VERSION
    assert "CONDITION OF ACCESS" in body["consent"]["askedAt"], (
        "the published method must say that a grant at the door is not a free choice — a "
        "methodology that reports the answer without the circumstance is the defect this whole "
        "endpoint exists to prevent"
    )
    assert set(body["consent"]["bases"]) == {"REQUIRED_AT_SIGN_IN", "OFFERED_IN_SETTINGS"}
    assert set(body["losses"]) >= {"droppedAtCeiling", "abandonedAfterFailedWrites", "written"}
    assert body["limits"]["maxWindowDays"] == usage.MAX_RANGE_DAYS
    assert sorted(usage_routes.UNRECORDED_TEMPLATES) == body["notMeasured"]
    assert body["document"] == "docs/METHODOLOGY-usage-instrumentation.md"
    # The limitations are the point of the endpoint, not decoration: a reader who takes the numbers
    # without them will conclude the app is fast while a designer watches a spinner in a courtyard.
    assert any("not user-perceived latency" in note for note in body["knownLimitations"])
    assert any("not a feature" in note for note in body["knownLimitations"])


async def test_the_published_method_cannot_claim_consent_the_policy_does_not_require(
    table: _UsageEventTable, caller: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    """THE ONE SENTENCE ON THIS ENDPOINT THAT USED TO BE A LIE WAITING FOR A ONE-LINE EDIT.

    ``collects`` used to carry the constant "The account id, ONLY where consent has been recorded as
    granted", which is true of exactly one of the three policies this module ships.
    ``DEFAULT_UNASKED_COLLECTION`` is documented as overrulable in one line and
    ``UnaskedCollection.ATTRIBUTED`` is one of its three values — under which ids ARE recorded for
    people nobody asked. The endpoint would then have gone on telling a reader of the published
    method that attribution follows consent, on the same page whose ``consent.unaskedPolicy`` field
    said ATTRIBUTED, and the prose is the half a person reads.

    So the sentence is derived from ``collection_plan`` — the same function the recorder calls, on
    the same constant — and this test flips the policy to prove the derivation rather than trusting
    it. What must NOT vary is the other claim: ``consentState`` stays NULL under all three policies,
    so no row ever claims a consent that was not given whatever the sentence says. That is asserted
    under the flipped policy too, because it is the claim that would actually matter if somebody
    made the flip.
    """
    caller["user"] = SimpleNamespace(id="a-1", role="ADMIN")

    honest = (await _get(_read_app(), "/usage/collection")).json()
    account_line = [line for line in honest["collects"] if "account id" in line]
    assert len(account_line) == 1
    assert "ONLY where consent has been recorded as granted" in account_line[0]

    monkeypatch.setattr(
        usage, "DEFAULT_UNASKED_COLLECTION", usage.UnaskedCollection.ATTRIBUTED
    )
    attributed = (await _get(_read_app(), "/usage/collection")).json()
    account_line = [line for line in attributed["collects"] if "account id" in line]

    assert len(account_line) == 1
    assert "ONLY where consent" not in account_line[0]
    assert "nobody has asked" in account_line[0]
    assert attributed["consent"]["unaskedPolicy"] == usage.UnaskedCollection.ATTRIBUTED.value
    # Unchanged, and it is the claim that has to be unchangeable: attributing a row is not the same
    # act as recording an agreement, and only one of the two is ever written here.
    assert "NULL on every row written so far" in attributed["consent"]["consentStateWritten"]
    assert usage.collection_plan(usage.UsageConsent.NOT_RECORDED).consent_state is None

    # THE THIRD POLICY, which is the same defect wearing the opposite hat: under NOTHING the list
    # would otherwise go on enumerating seven things being collected by a deployment collecting
    # none of them.
    monkeypatch.setattr(usage, "DEFAULT_UNASKED_COLLECTION", usage.UnaskedCollection.NOTHING)
    nothing = (await _get(_read_app(), "/usage/collection")).json()

    assert len(nothing["collects"]) == 1
    assert nothing["collects"][0].startswith("NOTHING.")
    assert usage.collection_plan(usage.UsageConsent.NOT_RECORDED).record is False
