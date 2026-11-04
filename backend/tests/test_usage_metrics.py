import sys
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

import app.core.db as core_db
from app.api.routes import usage as usage_routes
from app.core import deps
from app.services import usage

TEMPLATE = "/design-workshops/{workshop_id}"
OTHER_TEMPLATE = "/artisans/{artisan_id}"

SINCE = datetime(2026, 8, 1, tzinfo=UTC)
UNTIL = datetime(2026, 8, 4, tzinfo=UTC)

SUBJECT_ID = "ckv9r2m4x0001qz8h3n7d2f5g"



class _RawQueries:
    def __init__(self) -> None:
        self.calls: list[tuple[str, tuple[Any, ...]]] = []
        self.timeline: list[dict[str, Any]] = []
        self.latency: list[dict[str, Any]] = []
        self.clients: list[dict[str, Any]] = []

    async def __call__(self, query: str, *args: Any) -> list[dict[str, Any]]:
        self.calls.append((query, args))
        if "date_trunc" in query:
            return self.timeline
        if "percentile_cont" in query:
            return self.latency
        if '"clientApp"' in query:
            return self.clients
        raise AssertionError(f"unstubbed raw statement: {query[:120]}")


class _EventTable:
    def __init__(self) -> None:
        self.groups: list[dict[str, Any]] = []
        self.rows: list[Any] = []
        self.find_many_calls: list[dict[str, Any]] = []

    async def group_by(self, **kwargs: Any) -> list[dict[str, Any]]:
        by = kwargs.get("by") or []
        return [group for group in self.groups if all(key in group for key in by)]

    async def find_many(self, **kwargs: Any) -> list[Any]:
        self.find_many_calls.append(kwargs)
        take = kwargs.get("take") or len(self.rows)
        skip = kwargs.get("skip") or 0
        return self.rows[skip : skip + take]

    async def create_many(self, data: Any) -> int:
        return len(list(data))

    async def delete_many(self, **_: Any) -> int:
        return 0


class _UserTable:
    def __init__(self) -> None:
        self.rows: dict[str, Any] = {}

    async def find_unique(self, *, where: dict[str, Any]) -> Any:
        return self.rows.get(str(where.get("id")))


def _event(index: int, *, at: datetime, status: int = 200) -> Any:
    return SimpleNamespace(
        id=f"e-{index}",
        routeTemplate=TEMPLATE,
        method="GET",
        statusCode=status,
        durationMs=10 * index,
        clientApp="web",
        consentState="GRANTED",
        createdAt=at,
    )


@pytest.fixture
def store(monkeypatch: pytest.MonkeyPatch) -> Iterator[SimpleNamespace]:
    raw = _RawQueries()
    events = _EventTable()
    users = _UserTable()
    fake_db = SimpleNamespace(query_raw=raw, usageevent=events, user=users)

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
    yield SimpleNamespace(raw=raw, events=events, users=users)
    usage.reset_buffer()
    usage.register_known_templates(())


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


def _range(since: datetime = SINCE, until: datetime = UNTIL) -> dict[str, str]:
    return {"from": since.isoformat(), "to": until.isoformat()}


async def _get(application: FastAPI, path: str, **kwargs: Any) -> httpx.Response:
    transport = httpx.ASGITransport(app=application, raise_app_exceptions=False)
    async with httpx.AsyncClient(transport=transport, base_url="http://usage.test") as client:
        return await client.get(path, **kwargs)


def _bucket(label: str, **overrides: Any) -> dict[str, Any]:
    row = {
        "bucket": label,
        "requests": 10,
        "ok": 9,
        "client_errors": 1,
        "server_errors": 0,
        "identified_users": 9,
    }
    row.update(overrides)
    return row



async def test_the_timeline_fills_empty_buckets_with_zero_and_withholds_thin_ones(
    store: SimpleNamespace, caller: dict[str, Any]
) -> None:
    caller["user"] = SimpleNamespace(id="a-1", role="ADMIN")
    store.raw.timeline = [
        _bucket("2026-08-01T00:00:00Z", requests=40, ok=38, server_errors=2, identified_users=9),
        _bucket("2026-08-03T00:00:00Z", requests=6, identified_users=2),
    ]

    response = await _get(
        _read_app(), "/usage/timeline", params={**_range(), "template": TEMPLATE, "bucket": "day"}
    )

    assert response.status_code == 200, response.text
    series = response.json()["series"]
    assert [entry["bucket"] for entry in series] == [
        "2026-08-01T00:00:00Z",
        "2026-08-02T00:00:00Z",
        "2026-08-03T00:00:00Z",
    ], "the half-open window spans three days and every one of them is emitted"

    busy, empty, thin = series
    assert busy["requests"] == 40
    assert busy["clientErrors"] == 1 and busy["serverErrors"] == 2
    assert busy["errorRate"] == round(3 / 40, 4), (
        "BOTH bands count. A rate over 5xx alone would report a screen answering 403 to everybody "
        "as perfectly healthy, which is the one shape of breakage this record exists to surface."
    )
    assert busy["withheld"] is False

    assert empty["requests"] == 0, "a day with no traffic is a zero, not a gap"
    assert empty["withheld"] is False
    assert empty["errorRate"] is None, (
        "0/0 is not 'nothing went wrong', it is 'nothing happened'. A line drawn through a zero here "
        "puts a reassuring flat rate across every outage in which this API answered nothing at all."
    )

    assert thin["withheld"] is True
    assert thin["requests"] is None and thin["errorRate"] is None, (
        "a refusal and never a zero: null coerces to 0 through arithmetic and through ??"
    )
    assert str(usage.MIN_IDENTIFIED_USERS_FOR_ROUTE) in thin["withheldBecause"]


async def test_a_bucket_with_no_identified_people_at_all_is_reported_in_full(
    store: SimpleNamespace, caller: dict[str, Any]
) -> None:
    caller["user"] = SimpleNamespace(id="a-1", role="ADMIN")
    store.raw.timeline = [_bucket("2026-08-01T00:00:00Z", requests=900, identified_users=0)]

    response = await _get(
        _read_app(), "/usage/timeline", params={**_range(), "template": TEMPLATE, "bucket": "day"}
    )

    first = response.json()["series"][0]
    assert first["withheld"] is False
    assert first["requests"] == 900


async def test_an_hourly_timeline_wider_than_the_bucket_cap_is_refused_with_the_arithmetic(
    store: SimpleNamespace, caller: dict[str, Any]
) -> None:
    caller["user"] = SimpleNamespace(id="a-1", role="ADMIN")
    wide = SINCE + timedelta(days=200)

    response = await _get(
        _read_app(),
        "/usage/timeline",
        params={**_range(SINCE, wide), "template": TEMPLATE, "bucket": "hour"},
    )

    assert response.status_code == 400
    detail = response.json()["detail"]
    assert str(usage.MAX_TIMELINE_BUCKETS) in detail
    assert "4800" in detail, "the number of buckets asked for is in the sentence"
    assert "One per day" in detail
    assert store.raw.calls == [], "the refusal happened before any database work"


async def test_a_bucket_finer_than_an_hour_does_not_exist(
    store: SimpleNamespace, caller: dict[str, Any]
) -> None:
    caller["user"] = SimpleNamespace(id="a-1", role="ADMIN")

    response = await _get(
        _read_app(),
        "/usage/timeline",
        params={**_range(), "template": TEMPLATE, "bucket": "minute"},
    )

    assert response.status_code == 400
    assert "hour, day" in response.json()["detail"]
    assert store.raw.calls == []



async def test_latency_reports_three_percentiles_and_withholds_them_under_the_floor(
    store: SimpleNamespace, caller: dict[str, Any]
) -> None:
    caller["user"] = SimpleNamespace(id="a-1", role="ADMIN")
    store.raw.latency = [
        {
            "template": TEMPLATE,
            "requests": 900,
            "identified_users": 11,
            "p50": 118.0,
            "p95": 4012.4,
            "p99": 9000.0,
            "max_ms": 12000,
        },
        {
            "template": OTHER_TEMPLATE,
            "requests": 40,
            "identified_users": 3,
            "p50": 20.0,
            "p95": 60.0,
            "p99": 90.0,
            "max_ms": 120,
        },
    ]

    response = await _get(
        _read_app(),
        "/usage/latency",
        params=[("from", SINCE.isoformat()), ("to", UNTIL.isoformat()),
                ("template", TEMPLATE), ("template", OTHER_TEMPLATE),
                ("template", "/tools/{tool_id}")],
    )

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["percentiles"] == ["p50", "p95", "p99"]
    by_name = {entry["routeTemplate"]: entry for entry in body["routes"]}

    reported = by_name[TEMPLATE]
    assert reported["withheld"] is False
    assert (reported["p50Ms"], reported["p95Ms"], reported["p99Ms"]) == (118, 4012, 9000), (
        "rounded to a whole millisecond, exactly as the stored column is an Int — a percentile "
        "printed to three decimal places reads as far more exact than the thing it measured"
    )

    withheld = by_name[OTHER_TEMPLATE]
    assert withheld["withheld"] is True
    assert withheld["p95Ms"] is None and withheld["requests"] is None

    silent = by_name["/tools/{tool_id}"]
    assert silent["withheld"] is False
    assert silent["p95Ms"] is None and silent["requests"] == 0, (
        "no traffic is not a refusal. The two must not render alike."
    )
    assert any("cannot be worked out from the averages" in note for note in body["notes"]), (
        "the response has to say that a percentile cannot be reconstructed from /usage/routes' "
        "means — otherwise somebody will try, and get a number that looks plausible"
    )



async def test_the_client_split_names_every_known_client_and_explains_the_api_fallback(
    store: SimpleNamespace, caller: dict[str, Any]
) -> None:
    caller["user"] = SimpleNamespace(id="a-1", role="ADMIN")
    store.raw.clients = [
        {
            "client": "api",
            "requests": 4000,
            "ok": 3900,
            "client_errors": 90,
            "server_errors": 10,
            "identified_users": 12,
            "avg_ms": 91.4,
        },
        {
            "client": "web",
            "requests": 60,
            "ok": 60,
            "client_errors": 0,
            "server_errors": 0,
            "identified_users": 2,
            "avg_ms": 40.0,
        },
    ]

    response = await _get(
        _read_app(), "/usage/clients", params={**_range(), "template": TEMPLATE}
    )

    body = response.json()
    assert response.status_code == 200, response.text
    assert body["header"] == usage.CLIENT_APP_HEADER
    assert body["fallback"] == usage.DEFAULT_CLIENT_APP
    by_client = {entry["clientApp"]: entry for entry in body["clients"]}
    assert set(by_client) == set(usage.CLIENT_APPS)

    assert by_client["api"]["requests"] == 4000
    assert by_client["api"]["avgDurationMs"] == 91
    assert by_client["android"]["requests"] == 0, "emitted even with no traffic"
    assert by_client["web"]["withheld"] is True, "two identified people is not an aggregate"
    assert by_client["web"]["requests"] is None

    assert any("did not say which app sent them" in note for note in body["notes"])
    assert any("counts that person twice" in note for note in body["notes"]), (
        "identifiedUsers cannot be summed across clients: one person on both is in both rows"
    )



async def test_the_screen_ranking_excludes_withheld_routes_from_both_orderings(
    store: SimpleNamespace, caller: dict[str, Any]
) -> None:
    caller["user"] = SimpleNamespace(id="a-1", role="ADMIN")
    store.events.groups = [
        {"routeTemplate": TEMPLATE, "statusCode": 200, "_count": {"_all": 500},
         "_avg": {"durationMs": 900.0}, "_max": {"durationMs": 4000}},
        {"routeTemplate": "/tools/{tool_id}", "statusCode": 200, "_count": {"_all": 50},
         "_avg": {"durationMs": 20.0}, "_max": {"durationMs": 40}},
        {"routeTemplate": OTHER_TEMPLATE, "statusCode": 200, "_count": {"_all": 300},
         "_avg": {"durationMs": 5000.0}, "_max": {"durationMs": 9000}},
    ] + [
        {"routeTemplate": TEMPLATE, "userId": f"u-{i}", "_count": {"_all": 5}} for i in range(9)
    ] + [
        {"routeTemplate": "/tools/{tool_id}", "userId": f"u-{i}", "_count": {"_all": 5}}
        for i in range(6)
    ] + [
        {"routeTemplate": OTHER_TEMPLATE, "userId": f"u-{i}", "_count": {"_all": 5}}
        for i in range(2)
    ]

    response = await _get(
        _read_app(),
        "/usage/screens",
        params=[("from", SINCE.isoformat()), ("to", UNTIL.isoformat()),
                ("template", TEMPLATE), ("template", OTHER_TEMPLATE),
                ("template", "/tools/{tool_id}"), ("template", "/crafts/{craft_id}")],
    )

    body = response.json()
    assert response.status_code == 200, response.text
    assert [entry["routeTemplate"] for entry in body["busiest"]] == [
        TEMPLATE,
        "/tools/{tool_id}",
    ], "the 300-request screen is withheld and is absent from the ranking, not last in it"
    assert [entry["routeTemplate"] for entry in body["slowest"]] == [
        TEMPLATE,
        "/tools/{tool_id}",
    ], (
        "the 5000 ms screen is withheld. Sorting it by a null average would have put the slowest "
        "screen in the product at the fast end of this list."
    )
    assert body["withheld"]["routes"] == 1
    assert "/crafts/{craft_id}" not in [entry["routeTemplate"] for entry in body["slowest"]], (
        "a screen with no traffic has no average; ranking it as instantaneous is the same defect"
    )
    assert body["scope"]["source"] == "requested"
    assert any("ranked by average time" in note for note in body["notes"]), (
        "a mean cannot see a tail, and this route says so rather than letting a ranking imply more "
        "than it measured"
    )



@pytest.mark.parametrize(
    "path", ["/usage/timeline", "/usage/latency", "/usage/clients", "/usage/screens"]
)
async def test_every_new_aggregate_states_its_caps_and_is_refused_to_a_designer(
    store: SimpleNamespace, caller: dict[str, Any], path: str
) -> None:
    caller["user"] = SimpleNamespace(id="d-1", role="DESIGNER")
    application = _read_app()

    refused = await _get(application, path, params={**_range(), "template": TEMPLATE})
    assert refused.status_code == 403
    assert "Yours is in Settings" in refused.json()["detail"]

    caller["user"] = SimpleNamespace(id="a-1", role="ADMIN")
    allowed = await _get(application, path, params={**_range(), "template": TEMPLATE})
    assert allowed.status_code == 200, allowed.text
    limits = allowed.json()["limits"]
    assert limits["maxWindowDays"] == usage.MAX_RANGE_DAYS
    assert limits["maxRoutesPerRequest"] == usage.MAX_TEMPLATES_PER_QUERY
    assert limits["minimumIdentifiedUsers"] == usage.MIN_IDENTIFIED_USERS_FOR_ROUTE
    assert allowed.json()["window"]["maxDays"] == usage.MAX_RANGE_DAYS


@pytest.mark.parametrize(
    "path", ["/usage/timeline", "/usage/latency", "/usage/clients", "/usage/screens"]
)
async def test_naming_more_templates_than_the_cap_is_refused_rather_than_truncated(
    store: SimpleNamespace, caller: dict[str, Any], path: str
) -> None:
    caller["user"] = SimpleNamespace(id="a-1", role="ADMIN")
    too_many = [
        ("template", f"/screen-{index}") for index in range(usage.MAX_TEMPLATES_PER_QUERY + 1)
    ]

    response = await _get(
        _read_app(),
        path,
        params=[("from", SINCE.isoformat()), ("to", UNTIL.isoformat()), *too_many],
    )

    assert response.status_code == 400
    detail = response.json()["detail"]
    assert str(usage.MAX_TEMPLATES_PER_QUERY) in detail
    assert str(usage.MAX_TEMPLATES_PER_QUERY + 1) in detail
    assert store.raw.calls == []


async def test_a_deployment_with_no_registered_route_table_is_refused_with_the_cause(
    store: SimpleNamespace, caller: dict[str, Any]
) -> None:
    caller["user"] = SimpleNamespace(id="a-1", role="ADMIN")
    assert usage.known_templates() == frozenset(), "the fixture clears the allow-list"

    response = await _get(_read_app(), "/usage/timeline", params=_range())

    assert response.status_code == 400
    detail = response.json()["detail"]
    assert "Usage figures aren't available right now" in detail
    assert "'template'" not in detail, "an admin is not told to edit a query string"
    assert store.raw.calls == []



async def test_a_person_may_read_their_own_trail_and_it_is_ordered_so_paging_is_total(
    store: SimpleNamespace, caller: dict[str, Any]
) -> None:
    caller["user"] = SimpleNamespace(id="d-1", role="DESIGNER", usageConsent="GRANTED")
    store.events.rows = [
        _event(index, at=SINCE + timedelta(hours=index), status=200 if index else 500)
        for index in range(3)
    ]

    response = await _get(_read_app(), "/usage/me/trail", params=_range())

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["userId"] == "d-1"
    assert len(body["events"]) == 3
    assert body["events"][0]["consentState"] == "GRANTED"
    assert body["maxRows"] == usage.MAX_TRAIL_ROWS

    query = store.events.find_many_calls[0]
    assert query["where"]["userId"] == "d-1"
    assert set(query["where"]["createdAt"]) == {"gte", "lt"}
    assert query["order"] == [{"createdAt": "desc"}, {"id": "desc"}], (
        "createdAt alone is not a total order, and a paged read over a non-total order drops and "
        "repeats rows at the page boundary"
    )
    assert any("in the order they arrived, not a total" in note for note in body["notes"])


async def test_the_trail_page_is_capped_and_the_cap_is_stated(
    store: SimpleNamespace, caller: dict[str, Any]
) -> None:
    caller["user"] = SimpleNamespace(id="d-1", role="DESIGNER", usageConsent="GRANTED")
    application = _read_app()

    over = await _get(
        application,
        "/usage/me/trail",
        params={**_range(), "limit": usage.MAX_TRAIL_ROWS + 1},
    )
    fine = await _get(application, "/usage/me/trail", params={**_range(), "limit": 5})

    assert over.status_code == 422
    assert fine.status_code == 200
    assert fine.json()["limit"] == 5
    assert fine.json()["maxRows"] == usage.MAX_TRAIL_ROWS


@pytest.mark.parametrize("role", ["DESIGNER", "PROFESSOR", "INSPECTOR", "ADMIN"])
async def test_the_account_trail_refuses_every_rank_below_master_admin(
    store: SimpleNamespace, caller: dict[str, Any], role: str
) -> None:
    caller["user"] = SimpleNamespace(id="who-1", role=role)
    store.users.rows[SUBJECT_ID] = SimpleNamespace(id=SUBJECT_ID, usageConsent="GRANTED")

    response = await _get(
        _read_app(), f"/usage/accounts/{SUBJECT_ID}/trail", params=_range()
    )

    assert response.status_code == 403, response.text
    detail = response.json()["detail"]
    assert "master admin" in detail
    assert "yours is in Settings" in detail, "the refusal names where the subject can read it"
    assert store.events.find_many_calls == [], "refused before any query"


@pytest.mark.parametrize(
    ("state", "phrase"),
    [
        ("REFUSED", "declined to have their use of the platform recorded"),
        ("NOT_RECORDED", "hasn't answered yet"),
    ],
)
async def test_the_account_trail_refuses_a_non_consenting_subject_with_a_sentence(
    store: SimpleNamespace, caller: dict[str, Any], state: str, phrase: str
) -> None:
    caller["user"] = SimpleNamespace(id="m-1", role="MASTER_ADMIN")
    store.users.rows[SUBJECT_ID] = SimpleNamespace(id=SUBJECT_ID, usageConsent=state)

    response = await _get(
        _read_app(), f"/usage/accounts/{SUBJECT_ID}/trail", params=_range()
    )

    assert response.status_code == 409, response.text
    assert phrase in response.json()["detail"]
    assert store.events.find_many_calls == [], "no trail was read"


async def test_the_account_trail_404s_an_unknown_account_rather_than_answering_empty(
    store: SimpleNamespace, caller: dict[str, Any]
) -> None:
    caller["user"] = SimpleNamespace(id="m-1", role="MASTER_ADMIN")

    response = await _get(_read_app(), "/usage/accounts/nobody/trail", params=_range())

    assert response.status_code == 404
    assert "nobody" in response.json()["detail"]


async def test_the_master_admin_reads_a_consenting_subject_and_the_rows_carry_their_own_consent(
    store: SimpleNamespace, caller: dict[str, Any], caplog: pytest.LogCaptureFixture
) -> None:
    caller["user"] = SimpleNamespace(id="m-1", role="MASTER_ADMIN")
    store.users.rows[SUBJECT_ID] = SimpleNamespace(
        id=SUBJECT_ID,
        usageConsent="GRANTED",
        usageConsentAt=None,
        usageConsentBasis="REQUIRED_AT_SIGN_IN",
        usageConsentVersion=usage.NOTICE_VERSION,
    )
    store.events.rows = [_event(index, at=SINCE + timedelta(hours=index)) for index in range(2)]

    with caplog.at_level("INFO", logger="app.api.routes.usage"):
        response = await _get(
            _read_app(), f"/usage/accounts/{SUBJECT_ID}/trail", params=_range()
        )

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["userId"] == SUBJECT_ID
    assert body["readBy"] == "m-1"
    assert body["subjectConsent"]["state"] == "GRANTED"
    assert body["subjectConsent"]["basis"] == "REQUIRED_AT_SIGN_IN"
    assert all(event["consentState"] == "GRANTED" for event in body["events"])
    assert any("one person's activity, not a total" in note for note in body["notes"])
    assert any("Small numbers are not hidden here" in note for note in body["notes"]), (
        "there is no group to hide in when the subject is named in the URL, and the response says "
        "so rather than leaving a reader to wonder whether the figures were suppressed"
    )
    logged = [record.getMessage() for record in caplog.records]
    assert any("read the request trail of" in line for line in logged), (
        "the read left no trace at all"
    )
    assert any("m-1" in line and SUBJECT_ID in line for line in logged), (
        "the log line has to name both the reader and the subject, because 'who read whose' is the "
        "only question anybody comes to it with"
    )
