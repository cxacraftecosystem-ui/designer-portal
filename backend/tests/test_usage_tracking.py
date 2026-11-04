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

WORKSHOP_ID = "ckv9r2m4x0001qz8h3n7d2f5g"

SINCE = datetime(2026, 8, 1, tzinfo=UTC)
UNTIL = datetime(2026, 8, 15, tzinfo=UTC)



class _UsageEventTable:
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
        return [group for group in self.groups if all(key in group for key in by)]

    @property
    def rows(self) -> list[dict[str, Any]]:
        return [row for batch in self.batches for row in batch]


@pytest.fixture
def table(monkeypatch: pytest.MonkeyPatch) -> Iterator[_UsageEventTable]:
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
    return {"from": since.isoformat(), "to": until.isoformat()}


async def _get(application: FastAPI, path: str, **kwargs: Any) -> httpx.Response:
    transport = httpx.ASGITransport(app=application, raise_app_exceptions=False)
    async with httpx.AsyncClient(transport=transport, base_url="http://usage.test") as client:
        return await client.get(path, **kwargs)


async def _written(table: _UsageEventTable) -> list[dict[str, Any]]:
    await usage.flush()
    return table.rows



@pytest.fixture(scope="module")
def real_app() -> Iterator[tuple[FastAPI, frozenset[str]]]:
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
    application, _ = real_app

    stack = [middleware.cls.__name__ for middleware in application.user_middleware]

    assert stack == [
        "SelectiveGZipMiddleware",
        "SecurityHeadersMiddleware",
        "CORSMiddleware",
        "UsageEventMiddleware",
        "UnhandledErrorMiddleware",
        "AppVersionMiddleware",
        "IdempotencyMiddleware",
    ]


def test_building_the_app_installs_the_real_route_table_as_an_allow_list(
    real_app: tuple[FastAPI, frozenset[str]],
) -> None:
    application, installed = real_app

    templates = _mounted_route_templates(application)

    assert len(templates) > 100, "the walk did not reach inside the mounted API router"
    assert "/design-workshops/{workshop_id}" in templates
    assert "/usage/routes" in templates
    assert "/design-workshops/{workshop_id}" in templates
    assert "/api/me" in templates
    assert "/design-workshops/{workshop_id}" in installed
    assert [template for template in templates if not usage.is_route_template(template)] == []


def test_fastapis_own_documentation_routes_are_left_out_of_the_allow_list(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
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
    assert {"/openapi.json", "/docs", "/redoc"} <= served

    for documentation in ("/openapi.json", "/docs", "/docs/oauth2-redirect", "/redoc"):
        assert documentation not in templates, (
            f"{documentation} cannot ever be the value the recorder writes, so it must not be in "
            f"the vocabulary GET /usage/routes pages through"
        )
    assert "/health" in templates
    assert "/design-workshops/{workshop_id}" in templates


async def test_a_route_fastapi_never_names_is_recorded_as_the_placeholder(
    table: _UsageEventTable,
) -> None:
    application = FastAPI()
    router = APIRouter(prefix="/design-workshops")

    @router.get("/{workshop_id}")
    async def one(workshop_id: str) -> dict[str, str]:  # pragma: no cover - exercised over HTTP
        return {"id": workshop_id}

    application.include_router(router)
    application.add_middleware(UsageEventMiddleware)
    usage.register_known_templates(_mounted_route_templates(application))

    assert "/openapi.json" not in usage.known_templates()

    response = await _get(application, "/openapi.json")
    rows = await _written(table)

    assert response.status_code == 200
    assert [row["routeTemplate"] for row in rows] == [usage.UNMATCHED_ROUTE]
    assert "openapi" not in str(rows)



async def test_one_request_records_one_event_with_the_template_not_the_path(
    table: _UsageEventTable,
) -> None:
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
    assert isinstance(row["durationMs"], int)
    assert row["durationMs"] >= 0


async def test_a_404_records_a_placeholder_and_never_the_url_that_was_asked_for(
    table: _UsageEventTable,
) -> None:
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
    await _get(_probe_app(), f"/design-workshops/{WORKSHOP_ID}")
    rows = await _written(table)

    assert rows[0]["userId"] is None
    assert rows[0]["consentState"] is None


async def test_the_default_policy_records_the_request_and_drops_the_name(
    table: _UsageEventTable, monkeypatch: pytest.MonkeyPatch
) -> None:
    async def _user(credentials: Any, *, allowed_scopes: Any) -> Any:
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
    application = _probe_app()

    await _get(application, "/health")
    await _get(application, "/usage/routes")
    await _get(application, f"/design-workshops/{WORKSHOP_ID}")
    rows = await _written(table)

    assert [row["routeTemplate"] for row in rows] == ["/design-workshops/{workshop_id}"]
    assert {"/health", "/usage/routes"} <= usage_routes.UNRECORDED_TEMPLATES



async def test_a_handler_that_raises_is_still_recorded_and_still_raises(
    table: _UsageEventTable,
) -> None:
    response = await _get(_probe_app(), f"/design-workshops/{WORKSHOP_ID}/boom")
    rows = await _written(table)

    assert response.status_code == 500
    assert len(rows) == 1
    assert rows[0]["routeTemplate"] == "/design-workshops/{workshop_id}/boom"
    assert rows[0]["statusCode"] == 500


async def test_a_streamed_response_passes_through_byte_for_byte(table: _UsageEventTable) -> None:
    response = await _get(_probe_app(), f"/design-workshops/{WORKSHOP_ID}/stream")
    rows = await _written(table)

    assert response.text == "chunk-0;chunk-1;chunk-2;chunk-3;"
    assert rows[0]["routeTemplate"] == "/design-workshops/{workshop_id}/stream"
    assert rows[0]["statusCode"] == 200


async def test_a_recorder_that_throws_cannot_reach_the_client(
    table: _UsageEventTable, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
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
    table.fails = True

    with caplog.at_level("WARNING"):
        response = await _get(_probe_app(), f"/design-workshops/{WORKSHOP_ID}")
        written = await usage.flush()

    assert response.status_code == 200
    assert written == 0
    assert table.batches == []
    assert [r for r in caplog.records if "could not write" in r.getMessage()], caplog.text
    assert usage.buffer_stats()["retryPending"] == 1


async def test_an_outage_abandons_batches_and_never_reaches_the_buffer_ceiling(
    table: _UsageEventTable,
) -> None:
    table.fails = True
    for _ in range(usage.FLUSH_ROWS):
        usage.record_event(
            route_template="/design-workshops/{workshop_id}",
            method="GET",
            status_code=200,
            duration_ms=3,
        )

    assert await usage.flush() == 0
    assert usage.buffer_stats()["abandoned"] == 0
    assert await usage.flush() == 0

    stats = usage.buffer_stats()
    assert stats["abandoned"] == usage.FLUSH_ROWS
    assert stats["dropped"] == 0
    assert stats["buffered"] == 0
    assert stats["retryPending"] == 0


async def test_get_current_user_writes_only_the_identity_into_scope_state(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def _user(credentials: Any, *, allowed_scopes: Any) -> Any:
        return SimpleNamespace(id="u-42", role="DESIGNER")

    monkeypatch.setattr(deps, "_user_from_bearer", _user)
    scope: dict[str, Any] = {"type": "http", "method": "GET", "path": "/x", "headers": []}

    user = await deps.get_current_user(Request(scope), credentials=None)

    assert deps.get_value(user, "id") == "u-42"
    assert scope["state"] == {
        usage.USAGE_USER_ID_KEY: "u-42",
        usage.USAGE_CONSENT_KEY: usage.UsageConsent.NOT_RECORDED,
        "appVersionUserId": "u-42",
    }


async def test_the_stitch_asks_for_a_connection_and_not_for_a_request(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
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
    scope: dict[str, Any] = {"type": "websocket", "path": "/ws", "headers": []}

    user = await deps.get_current_user(HTTPConnection(scope), credentials=None)

    assert deps.get_value(user, "id") == "u-42"
    assert scope["state"] == {
        usage.USAGE_USER_ID_KEY: "u-42",
        usage.USAGE_CONSENT_KEY: usage.UsageConsent.NOT_RECORDED,
        "appVersionUserId": "u-42",
    }



_CALLER: dict[str, Any] = {"user": None}


def _read_app() -> FastAPI:
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
    caller["user"] = SimpleNamespace(id="d-1", role="DESIGNER")
    application = _read_app()

    routes = await _get(application, "/usage/routes", params=_range(SINCE, UNTIL))
    collection = await _get(application, "/usage/collection")

    assert routes.status_code == 403
    assert collection.status_code == 403
    assert "Yours is in Settings" in routes.json()["detail"]


async def test_a_designer_may_always_read_their_own_usage(
    table: _UsageEventTable, caller: dict[str, Any]
) -> None:
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
    caller["user"] = SimpleNamespace(id="a-1", role="ADMIN")

    body = (await _get(_read_app(), "/usage/collection")).json()

    assert body["consent"]["unaskedPolicy"] == usage.DEFAULT_UNASKED_COLLECTION.value
    assert body["consent"]["flowExists"] is True
    assert body["consent"]["noticeVersion"] == usage.NOTICE_VERSION
    assert "required to sign in" in body["consent"]["askedAt"], (
        "the published method must say that a grant at the door is not a free choice — a "
        "methodology that reports the answer without the circumstance is the defect this whole "
        "endpoint exists to prevent"
    )
    assert set(body["consent"]["bases"]) == {"REQUIRED_AT_SIGN_IN", "OFFERED_IN_SETTINGS"}
    assert set(body["losses"]) >= {"droppedAtCeiling", "abandonedAfterFailedWrites", "written"}
    assert body["limits"]["maxWindowDays"] == usage.MAX_RANGE_DAYS
    assert sorted(usage_routes.UNRECORDED_TEMPLATES) == body["notMeasured"]
    assert body["document"] == "docs/METHODOLOGY-usage-instrumentation.md"
    assert any("not user-perceived latency" in note for note in body["knownLimitations"])
    assert any("not a feature" in note for note in body["knownLimitations"])


async def test_the_published_method_cannot_claim_consent_the_policy_does_not_require(
    table: _UsageEventTable, caller: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    caller["user"] = SimpleNamespace(id="a-1", role="ADMIN")

    honest = (await _get(_read_app(), "/usage/collection")).json()
    account_line = [line for line in honest["collects"] if "Your name" in line]
    assert len(account_line) == 1
    assert "only if you agree" in account_line[0]

    monkeypatch.setattr(
        usage, "DEFAULT_UNASKED_COLLECTION", usage.UnaskedCollection.ATTRIBUTED
    )
    attributed = (await _get(_read_app(), "/usage/collection")).json()
    account_line = [line for line in attributed["collects"] if "Your name" in line]

    assert len(account_line) == 1
    assert "only if you agree" not in account_line[0]
    assert "before you have answered" in account_line[0]
    assert attributed["consent"]["unaskedPolicy"] == usage.UnaskedCollection.ATTRIBUTED.value
    assert "answer it was recorded under" in attributed["consent"]["consentStateWritten"]
    assert usage.collection_plan(usage.UsageConsent.NOT_RECORDED).consent_state is None

    monkeypatch.setattr(usage, "DEFAULT_UNASKED_COLLECTION", usage.UnaskedCollection.NOTHING)
    nothing = (await _get(_read_app(), "/usage/collection")).json()

    assert len(nothing["collects"]) == 1
    assert nothing["collects"][0].startswith("Nothing until you agree.")
    assert usage.collection_plan(usage.UsageConsent.NOT_RECORDED).record is False
