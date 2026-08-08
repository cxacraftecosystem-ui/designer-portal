"""The market-research providers, end to end, against recorded responses. NO NETWORK ANYWHERE.

The two hosted providers are driven through a stub placed in ``sys.modules`` — possible only
because ``requests`` is imported inside the function that uses it, so the lazy-import rule buys
testability as well as boot time. The local one is driven against real files in ``tmp_path``,
because that is what it reads in production too.

The first section is the one that matters most: **a fresh clone with no new environment variables
behaves exactly as it did before this capability existed.** Everything is off, the probe says which
variable to set, importing the package loads none of the provider modules and does not touch
``app.services``, and a call raises a typed error rather than an ImportError or a network attempt.
"""

import importlib.machinery
import json
import subprocess
import sys
from pathlib import Path

import pytest

from app import ai_features
from app.ai_features import registry
from app.ai_features.errors import (
    FeatureDisabled,
    ProviderFailed,
    ProviderNotConfigured,
    ProviderRateLimited,
    ProviderTimeout,
    UnknownProvider,
)
from app.ai_features.market_research import research_market
from app.ai_features.probe import capability_status, format_probe, probe
from app.ai_features.settings import build_settings
from app.ai_features.types import Capability, ResearchBrief

BACKEND_ROOT = Path(__file__).resolve().parents[1]
ALL_OFF = build_settings({})
BRIEF = ResearchBrief(keyword="sambalpuri ikat stole", craft="handloom", category="stole")

#: Provider implementations and the analysis module they bridge to. None of these may be in
#: sys.modules after ``import app.ai_features``.
LAZY_MODULES = (
    "app.ai_features.providers.serper_shopping",
    "app.ai_features.providers.serpapi_shopping",
    "app.ai_features.providers.local_catalogue",
    "app.services.market_analysis",
    "requests",
)


# --------------------------------------------------------------------------------------------
# Recorded provider responses. Shapes follow each vendor's published documentation; nothing here
# was fetched, and nothing in this file can reach a network.
# --------------------------------------------------------------------------------------------

SERPER_PAGE = {
    "searchParameters": {"q": "sambalpuri ikat stole", "gl": "in", "hl": "en", "page": 1},
    "shopping": [
        {
            "title": "Sambalpuri Ikat Silk Stole - Handwoven",
            "source": "Boyanika",
            "link": "https://boyanika.example.invalid/p/1201?utm_source=google",
            "price": "₹2,450",
            "delivery": "Free delivery",
            "rating": 4.5,
            "position": 1,
        },
        {
            "title": "Sambalpuri Ikat Cotton Stole",
            "source": "Utkalika",
            "link": "https://utkalika.example.invalid/p/88",
            "price": "₹1,299",
            "position": 2,
        },
        {
            # The same listing again, from a second query, with a different campaign parameter.
            "title": "Sambalpuri Ikat Silk Stole - Handwoven",
            "source": "Boyanika",
            "link": "https://boyanika.example.invalid/p/1201?utm_source=shopping&gclid=abc",
            "price": "₹2,450",
            "position": 3,
        },
        {
            "title": "Sambalpuri Ikat Bridal Silk Stole, heirloom weave",
            "source": "Sambalpuri Bastralaya",
            "link": "https://sb.example.invalid/p/9",
            "price": "₹1,20,000",
            "position": 4,
        },
        {
            "title": "Sambalpuri Ikat Stole - made to order",
            "source": "Gramya Handloom",
            "link": "https://gramya.example.invalid/p/3",
            "price": "Call for price",
            "position": 5,
        },
        {
            "title": "Ikat print mobile phone case",
            "source": "A Marketplace",
            "link": "https://market.example.invalid/p/77",
            "price": "₹299",
            "position": 6,
        },
        {
            "title": "Sambalpuri Ikat Stole, tussar",
            "source": "Craft Village",
            "link": "https://cv.example.invalid/p/4",
            "price": "₹3,200 ₹4,500",
            "position": 7,
        },
        {
            "title": "Sambalpuri Ikat Stole, plain border",
            "source": "Craft Village",
            "link": "https://cv.example.invalid/p/5",
            "price": "₹1,850",
            "position": 8,
        },
    ],
    "credits": 1,
}

SERPAPI_PAGE = {
    "search_metadata": {"id": "x", "status": "Success"},
    "search_parameters": {"engine": "google_shopping", "q": "sambalpuri ikat stole"},
    "shopping_results": [
        {
            "position": 1,
            "title": "Sambalpuri Ikat Silk Stole",
            "product_link": "https://boyanika.example.invalid/p/1201",
            "source": "Boyanika",
            "price": "₹2,450",
            "extracted_price": 2450,
        },
        {
            "position": 2,
            "title": "Sambalpuri Ikat Cotton Stole",
            "product_link": "https://utkalika.example.invalid/p/88",
            "source": "Utkalika",
            "price": "₹1,299",
            "extracted_price": 1299,
        },
    ],
}


class _StubResponse:
    def __init__(self, status_code: int, headers: dict, body: bytes) -> None:
        self.status_code = status_code
        self.headers = headers
        self._body = body

    def __enter__(self) -> "_StubResponse":
        return self

    def __exit__(self, *_: object) -> bool:
        return False

    def iter_content(self, chunk_size: int = 8192):
        for start in range(0, len(self._body), chunk_size):
            yield self._body[start:start + chunk_size]


def _json_response(payload, status: int = 200) -> _StubResponse:
    return _StubResponse(
        status, {"Content-Type": "application/json"}, json.dumps(payload).encode("utf-8")
    )


class _StubRequests:
    """The four names the transport touches: request, post, Timeout, RequestException."""

    class RequestException(Exception):
        pass

    class Timeout(RequestException):
        pass

    def __init__(self, responses) -> None:
        # A list is consumed one response per call, so pagination can be driven; a single value is
        # returned for every call.
        self.responses = responses if isinstance(responses, list) else None
        self.single = None if isinstance(responses, list) else responses
        self.calls: list[dict] = []
        # find_spec reads __spec__ off whatever is in sys.modules, and the registry's readiness
        # check asks it whether requests is installed. Without this the stub would make the probe
        # answer "requests is missing" and the call would never be made.
        self.__spec__ = importlib.machinery.ModuleSpec("requests", None)

    def request(self, method: str, url: str, **kwargs):
        self.calls.append({"method": method, "url": url, **kwargs})
        answer = self.single if self.responses is None else (
            self.responses.pop(0) if self.responses else _json_response({"shopping": []})
        )
        if isinstance(answer, Exception):
            raise answer
        return answer

    def post(self, url: str, **kwargs):
        return self.request("POST", url, **kwargs)


@pytest.fixture
def stub_requests(monkeypatch: pytest.MonkeyPatch):
    def install(responses) -> _StubRequests:
        stub = _StubRequests(responses)
        monkeypatch.setitem(sys.modules, "requests", stub)
        return stub

    return install


@pytest.fixture(autouse=True)
def _clear_throttle():
    from app.ai_features.runtime import reset_throttle_cache

    reset_throttle_cache()
    yield
    reset_throttle_cache()


def _serper_settings(**overrides: str):
    base = {
        "AI_FEATURES_ENABLED": "true",
        "AI_MARKET_RESEARCH_ENABLED": "true",
        "AI_MARKET_RESEARCH_PROVIDER": "serper_shopping",
        "AI_MARKET_RESEARCH_MIN_INTERVAL_SECONDS": "0",
        "SERPER_API_KEY": "test-key",
    }
    base.update(overrides)
    return build_settings(base)


def _serpapi_settings(**overrides: str):
    base = {
        "AI_FEATURES_ENABLED": "true",
        "AI_MARKET_RESEARCH_ENABLED": "true",
        "AI_MARKET_RESEARCH_PROVIDER": "serpapi_shopping",
        "AI_MARKET_RESEARCH_MIN_INTERVAL_SECONDS": "0",
        "SERPAPI_API_KEY": "test-key",
    }
    base.update(overrides)
    return build_settings(base)


# --------------------------------------------------------------------------------------------
# 1. Dormant. This section is the contract.
# --------------------------------------------------------------------------------------------


def test_market_research_is_off_on_a_fresh_clone() -> None:
    assert ALL_OFF.market_research_enabled is False
    assert ALL_OFF.capability_enabled(Capability.MARKET_RESEARCH) is False
    status = capability_status(Capability.MARKET_RESEARCH, ALL_OFF)
    assert status.enabled is False
    assert status.available is False
    assert "AI_FEATURES_ENABLED" in status.reason
    assert status.as_dict()["enableVariable"] == "AI_MARKET_RESEARCH_ENABLED"


def test_the_master_switch_alone_leaves_market_research_off() -> None:
    settings = build_settings({"AI_FEATURES_ENABLED": "true"})
    status = capability_status(Capability.MARKET_RESEARCH, settings)
    assert status.available is False
    assert status.reason == "AI_MARKET_RESEARCH_ENABLED is off"


def test_probing_a_dormant_capability_raises_nothing_and_names_every_setting() -> None:
    report = probe(ALL_OFF)
    entry = next(
        item for item in report["capabilities"] if item["capability"] == "market_research"
    )
    assert entry["available"] is False
    named = {name for item in entry["providers"] for name in item["missingSettings"]}
    assert named == {"AI_MARKET_RESEARCH_CATALOGUE_PATH", "SERPER_API_KEY", "SERPAPI_API_KEY"}
    json.dumps(report)  # raises if a dataclass or an enum leaked into the report
    assert "market_research" in format_probe(ALL_OFF)


def test_calling_a_disabled_capability_names_the_variable_to_set() -> None:
    with pytest.raises(FeatureDisabled) as caught:
        research_market("ikat stole", settings=ALL_OFF)
    assert caught.value.code == "disabled"
    assert "AI_FEATURES_ENABLED" in (caught.value.remediation or "")
    assert "AI_MARKET_RESEARCH_ENABLED" in (caught.value.remediation or "")
    assert "docs/MARKET_RESEARCH.md" in (caught.value.remediation or "")


def test_disabled_beats_a_bad_brief() -> None:
    # An operator with the feature off should be told it is off, not told their keyword is empty
    # by a code path that could not have run either way.
    with pytest.raises(FeatureDisabled):
        research_market("", settings=ALL_OFF)


def test_enabled_with_nothing_configured_names_all_three_options() -> None:
    settings = build_settings(
        {"AI_FEATURES_ENABLED": "true", "AI_MARKET_RESEARCH_ENABLED": "true"}
    )
    with pytest.raises(ProviderNotConfigured) as caught:
        research_market(BRIEF, settings=settings)
    assert "local_catalogue" in caught.value.message
    assert "serper_shopping" in caught.value.message
    assert "serpapi_shopping" in caught.value.message
    assert "AI_MARKET_RESEARCH_PROVIDER" in (caught.value.remediation or "")


def test_an_explicit_provider_is_never_silently_swapped() -> None:
    # Asking for the offline catalogue and being charged for a hosted search instead would be
    # worse than an error naming the one thing that has to be set.
    settings = build_settings({
        "AI_FEATURES_ENABLED": "true",
        "AI_MARKET_RESEARCH_ENABLED": "true",
        "AI_MARKET_RESEARCH_PROVIDER": "local_catalogue",
        "SERPER_API_KEY": "a-key-that-would-otherwise-be-used",
    })
    with pytest.raises(ProviderNotConfigured) as caught:
        research_market(BRIEF, settings=settings)
    assert caught.value.provider == "local_catalogue"
    assert "AI_MARKET_RESEARCH_CATALOGUE_PATH" in caught.value.missing


def test_an_unknown_provider_id_lists_the_real_ones() -> None:
    settings = build_settings({
        "AI_FEATURES_ENABLED": "true",
        "AI_MARKET_RESEARCH_ENABLED": "true",
        "AI_MARKET_RESEARCH_PROVIDER": "just_google_it",
    })
    with pytest.raises(UnknownProvider) as caught:
        research_market(BRIEF, settings=settings)
    assert "serper_shopping" in (caught.value.remediation or "")


def test_an_image_provider_cannot_be_pointed_at_market_research() -> None:
    settings = build_settings({
        "AI_FEATURES_ENABLED": "true",
        "AI_MARKET_RESEARCH_ENABLED": "true",
        "AI_MARKET_RESEARCH_PROVIDER": "remove_bg",
    })
    with pytest.raises(UnknownProvider):
        research_market(BRIEF, settings=settings)


def test_importing_the_package_loads_no_provider_and_no_service_module() -> None:
    # Checked in a clean interpreter: in-process, sys.modules is polluted by every other test in
    # this file. This is the test that fails the moment somebody moves an import to module scope.
    script = (
        "import sys;"
        "import app.ai_features;"
        f"names = {LAZY_MODULES!r};"
        "print(','.join(n for n in names if n in sys.modules))"
    )
    completed = subprocess.run(
        [sys.executable, "-c", script],
        cwd=BACKEND_ROOT,
        capture_output=True,
        text=True,
        timeout=120,
    )
    assert completed.returncode == 0, completed.stderr
    assert completed.stdout.strip() == "", (
        f"importing app.ai_features loaded {completed.stdout.strip()} — move that import inside "
        "the function that needs it"
    )


def test_every_market_research_provider_declares_a_labelled_cost() -> None:
    for descriptor in registry.providers_for(Capability.MARKET_RESEARCH):
        resources = descriptor.resources
        assert resources.basis in ("MEASURED", "ESTIMATED", "VENDOR_STATED")
        assert resources.latency and resources.money
        if resources.basis == "MEASURED":
            assert "MEASURED" in resources.notes
        else:
            # An unmeasured figure has to say so where it is read, not only in its basis field.
            assert any(
                label in (resources.money + resources.notes)
                for label in ("VENDOR-STATED", "ESTIMATED", "THIRD-PARTY REPORTED")
            )


def test_auto_prefers_the_provider_that_costs_nothing_and_discloses_nothing(tmp_path) -> None:
    catalogue = tmp_path / "prices.csv"
    catalogue.write_text("name,seller,price,currency\nIkat stole,A Shop,800,INR\n", encoding="utf-8")
    settings = build_settings({
        "AI_FEATURES_ENABLED": "true",
        "AI_MARKET_RESEARCH_ENABLED": "true",
        "AI_MARKET_RESEARCH_CATALOGUE_PATH": str(catalogue),
        "SERPER_API_KEY": "a-key",
    })
    chosen = registry.resolve(Capability.MARKET_RESEARCH, settings)
    assert chosen.id == "local_catalogue"


# --------------------------------------------------------------------------------------------
# 2. serper.dev, through the public entry point.
# --------------------------------------------------------------------------------------------


def test_a_hosted_search_returns_comparable_products_with_their_provenance(stub_requests) -> None:
    stub = stub_requests(_json_response(SERPER_PAGE))

    result = research_market(BRIEF, settings=_serper_settings())

    assert result.provider == "serper_shopping"
    assert result.currency == "INR"
    names = [item.name for item in result.products]
    assert "Sambalpuri Ikat Silk Stole - Handwoven" in names
    # Every kept product carries the two facts that make it auditable.
    for item in result.products:
        assert item.source_url.startswith("https://")
        assert item.retrieved_at.endswith("+00:00")
        assert item.provider == "serper_shopping"

    sent = stub.calls[0]
    assert sent["method"] == "POST"
    assert sent["url"] == "https://google.serper.dev/shopping"
    assert sent["headers"]["X-API-KEY"] == "test-key"
    # An identifying User-Agent on every outbound request, so a vendor has somebody to contact
    # before blocking a shared address.
    assert "DesignWorkshopBackend" in sent["headers"]["User-Agent"]
    assert sent["json"]["gl"] == "in"
    assert sent["timeout"][0] <= 10.0


def test_the_hosted_pipeline_reads_an_indian_grouped_price_correctly(stub_requests) -> None:
    stub_requests(_json_response(SERPER_PAGE))
    result = research_market(BRIEF, settings=_serper_settings())
    heirloom = next(item for item in result.products if "Bridal" in item.name)
    assert heirloom.price == 120000.0
    assert heirloom.price_text == "₹1,20,000"


def test_the_hosted_pipeline_deduplicates_across_campaign_parameters(stub_requests) -> None:
    stub_requests(_json_response(SERPER_PAGE))
    result = research_market(BRIEF, settings=_serper_settings())
    assert result.duplicates_removed >= 1
    links = [item.source_url for item in result.products]
    assert len(links) == len(set(links))


def test_the_hosted_pipeline_sets_aside_the_phone_case(stub_requests) -> None:
    stub_requests(_json_response(SERPER_PAGE))
    result = research_market(BRIEF, settings=_serper_settings())
    assert all("phone case" not in item.name for item in result.products)
    assert any("phone case" in item.name for item, _reason in result.rejected)


def test_a_hosted_result_still_refuses_to_be_mistaken_for_survey_evidence(stub_requests) -> None:
    stub_requests(_json_response(SERPER_PAGE))
    result = research_market(BRIEF, settings=_serper_settings())
    assert result.cautions
    assert "RETRIEVED from serper_shopping" in result.cautions[0]


def test_a_brief_with_no_facets_costs_one_query_not_three(stub_requests) -> None:
    # One query is one credit. A bare keyword must not silently buy the maximum.
    stub = stub_requests(_json_response(SERPER_PAGE))
    research_market("sambalpuri ikat stole", settings=_serper_settings())
    assert len(stub.calls) == 1


def test_the_query_budget_is_split_across_queries_rather_than_multiplied(stub_requests) -> None:
    # Three queries each fetching a full budget would be three times the credits for one button
    # press. A page that already covers a query's share ends its pagination rather than spending
    # another credit to confirm.
    stub = stub_requests(_json_response(SERPER_PAGE))
    brief = ResearchBrief(
        keyword="sambalpuri ikat stole", craft="handloom", place="Sambalpur district"
    )
    research_market(brief, settings=_serper_settings(AI_MARKET_RESEARCH_MAX_QUERIES="3"))
    assert len(stub.calls) == 3
    assert {call["json"]["page"] for call in stub.calls} == {1}
    assert len({call["json"]["q"] for call in stub.calls}) == 3


def test_pagination_stops_when_the_budget_is_reached(stub_requests) -> None:
    full = {"shopping": SERPER_PAGE["shopping"][:8] + [dict(SERPER_PAGE["shopping"][1],
                                                            link=f"https://x.invalid/{i}")
                                                       for i in range(2)]}
    stub = stub_requests([_json_response(full), _json_response(full), _json_response(full)])
    research_market(
        BRIEF,
        settings=_serper_settings(
            AI_MARKET_RESEARCH_MAX_QUERIES="1", AI_MARKET_RESEARCH_MAX_RESULTS="10"
        ),
    )
    # Ten results asked for, ten returned by the first full page: no second page is bought.
    assert len(stub.calls) == 1


def test_an_empty_market_is_not_an_error(stub_requests) -> None:
    stub_requests(_json_response({"shopping": [], "credits": 1}))
    result = research_market(BRIEF, settings=_serper_settings())
    assert result.products == ()
    assert any("failed search, not a finding" in caution for caution in result.cautions)


def test_a_missing_result_key_with_a_message_is_a_failure_not_an_empty_market(
    stub_requests,
) -> None:
    stub_requests(_json_response({"message": "Unsupported gl value"}))
    with pytest.raises(ProviderFailed) as caught:
        research_market(BRIEF, settings=_serper_settings())
    assert "Unsupported gl value" in caught.value.message


@pytest.mark.parametrize(
    ("status", "body", "expected", "needle"),
    [
        (403, {"message": "Unauthorized"}, ProviderNotConfigured, "SERPER_API_KEY"),
        (402, {"message": "Not enough credits"}, ProviderFailed, "MAX_RESULTS"),
        (500, {"message": "boom"}, ProviderFailed, "retry"),
    ],
)
def test_every_hosted_failure_is_typed_and_says_what_to_do(
    stub_requests, status, body, expected, needle
) -> None:
    stub_requests(_json_response(body, status))
    with pytest.raises(expected) as caught:
        research_market(BRIEF, settings=_serper_settings())
    assert needle in (caught.value.remediation or "")


def test_rate_limiting_carries_the_retry_hint(stub_requests) -> None:
    stub_requests(
        _StubResponse(429, {"Retry-After": "30"}, b'{"message":"Too many requests"}')
    )
    with pytest.raises(ProviderRateLimited) as caught:
        research_market(BRIEF, settings=_serper_settings())
    assert caught.value.retry_after == 30.0


def test_a_timeout_is_typed_and_suggests_the_queue(stub_requests) -> None:
    stub_requests(_StubRequests.Timeout("read timed out"))
    with pytest.raises(ProviderTimeout) as caught:
        research_market(BRIEF, settings=_serper_settings())
    assert "queue" in (caught.value.remediation or "")


def test_an_unreachable_host_never_escapes_as_a_requests_error(stub_requests) -> None:
    stub_requests(_StubRequests.RequestException("connection refused"))
    with pytest.raises(ProviderFailed) as caught:
        research_market(BRIEF, settings=_serper_settings())
    assert "could not reach serper_shopping" in caught.value.message


def test_a_200_that_is_not_json_is_refused(stub_requests) -> None:
    stub_requests(_StubResponse(200, {"Content-Type": "text/html"}, b"<html>captive portal</html>"))
    with pytest.raises(ProviderFailed) as caught:
        research_market(BRIEF, settings=_serper_settings())
    assert "not JSON" in caught.value.message


def test_an_endless_response_is_abandoned_rather_than_buffered(stub_requests) -> None:
    stub_requests(_StubResponse(200, {"Content-Type": "application/json"}, b"x" * (3 * 1024 * 1024)))
    with pytest.raises(ProviderFailed) as caught:
        research_market(BRIEF, settings=_serper_settings())
    assert "abandoned" in caught.value.message


# --------------------------------------------------------------------------------------------
# 3. SerpApi — the second vendor, whose failures arrive in a different shape.
# --------------------------------------------------------------------------------------------


def test_the_second_vendor_answers_the_same_question(stub_requests) -> None:
    stub = stub_requests(_json_response(SERPAPI_PAGE))
    result = research_market(BRIEF, settings=_serpapi_settings())
    assert result.provider == "serpapi_shopping"
    assert sorted(item.price for item in result.products) == [1299.0, 2450.0]
    sent = stub.calls[0]
    assert sent["method"] == "GET"
    assert sent["params"]["engine"] == "google_shopping"
    assert sent["params"]["api_key"] == "test-key"
    assert "DesignWorkshopBackend" in sent["headers"]["User-Agent"]


def test_a_200_carrying_an_error_string_is_not_an_empty_market(stub_requests) -> None:
    # SerpApi answers 200 with {"error": ...}. Left unhandled this reads as "the market has
    # nothing", which is the one wrong answer this module exists to avoid producing.
    stub_requests(_json_response({"error": "Your account has run out of searches."}))
    with pytest.raises(ProviderFailed) as caught:
        research_market(BRIEF, settings=_serpapi_settings())
    assert "run out of searches" in caught.value.message


def test_the_second_vendor_reports_no_results_without_complaining(stub_requests) -> None:
    stub_requests(_json_response({"search_metadata": {"status": "Success"}}))
    result = research_market(BRIEF, settings=_serpapi_settings())
    assert result.products == ()


# --------------------------------------------------------------------------------------------
# 4. The local catalogue: the whole capability on the device's own CPU.
# --------------------------------------------------------------------------------------------


CATALOGUE_CSV = """name,seller,price,currency,url,updated
Sambalpuri ikat silk stole,Boyanika,"₹2,450",INR,https://boyanika.example.invalid/p/1,2026-07-01
Sambalpuri ikat cotton stole,Utkalika,"₹1,299",INR,,2026-07-01
Sambalpuri ikat bridal stole,Bastralaya,"₹1,20,000",INR,,2026-07-01
Sambalpuri ikat stole tussar,Craft Village,"₹3,200",INR,,2026-07-01
Sambalpuri ikat stole plain,Craft Village,"₹1,850",INR,,2026-07-01
Terracotta cooking pot,Potters Co-op,"₹350",INR,,2026-07-01
"""


def _catalogue_settings(path: Path, **overrides: str):
    base = {
        "AI_FEATURES_ENABLED": "true",
        "AI_MARKET_RESEARCH_ENABLED": "true",
        "AI_MARKET_RESEARCH_PROVIDER": "local_catalogue",
        "AI_MARKET_RESEARCH_CATALOGUE_PATH": str(path),
        "AI_MARKET_RESEARCH_MIN_INTERVAL_SECONDS": "0",
    }
    base.update(overrides)
    return build_settings(base)


def test_the_offline_provider_answers_the_whole_capability_from_a_file(tmp_path) -> None:
    path = tmp_path / "prices.csv"
    path.write_text(CATALOGUE_CSV, encoding="utf-8")
    result = research_market(BRIEF, settings=_catalogue_settings(path))

    assert result.provider == "local_catalogue"
    assert len(result.products) == 5
    assert result.distribution is not None
    assert result.distribution.count == 5
    assert all("cooking pot" not in item.name for item in result.products)


def test_a_catalogue_row_is_stamped_with_the_date_the_catalogue_claims(tmp_path) -> None:
    # A catalogue price was true when the catalogue was compiled, not when this happened to read
    # it. Stamping it "now" would be a small lie that gets more wrong every month.
    path = tmp_path / "prices.csv"
    path.write_text(CATALOGUE_CSV, encoding="utf-8")
    result = research_market(BRIEF, settings=_catalogue_settings(path))
    assert {item.retrieved_at for item in result.products} == {"2026-07-01"}


def test_a_row_without_a_url_still_gets_a_distinct_citable_source(tmp_path) -> None:
    # The row number goes in the query string, not the fragment: canonical_url drops fragments,
    # so "#row=3" would make every row compare equal and deduplication would keep one of them.
    path = tmp_path / "prices.csv"
    path.write_text(CATALOGUE_CSV, encoding="utf-8")
    result = research_market(BRIEF, settings=_catalogue_settings(path))
    file_rows = [item for item in result.products if item.source_url.startswith("file:")]
    assert len(file_rows) == 4
    assert len({item.source_url for item in file_rows}) == 4
    assert all("?row=" in item.source_url for item in file_rows)


def test_the_offline_provider_reads_jsonl_too(tmp_path) -> None:
    path = tmp_path / "prices.jsonl"
    rows = [
        {"name": "Sambalpuri ikat stole one", "seller": "A", "price": "₹800"},
        {"name": "Sambalpuri ikat stole two", "seller": "B", "price": "₹950"},
        {"not": "json at all"},
        "{ this line is broken",
        {"name": "Terracotta pot", "seller": "C", "price": "₹350"},
    ]
    path.write_text(
        "\n".join(json.dumps(row) if isinstance(row, dict) else row for row in rows),
        encoding="utf-8",
    )
    result = research_market(BRIEF, settings=_catalogue_settings(path))
    # A malformed line is skipped, not raised on: one bad row in a hand-edited price list must
    # not cost the operator the other nine hundred.
    assert sorted(item.price for item in result.products) == [800.0, 950.0]


def test_a_missing_catalogue_is_a_configuration_problem_with_the_variable_named(tmp_path) -> None:
    with pytest.raises(ProviderNotConfigured) as caught:
        research_market(BRIEF, settings=_catalogue_settings(tmp_path / "nothing.csv"))
    assert "AI_MARKET_RESEARCH_CATALOGUE_PATH" in caught.value.missing


def test_a_directory_is_not_a_catalogue(tmp_path) -> None:
    with pytest.raises(ProviderNotConfigured) as caught:
        research_market(BRIEF, settings=_catalogue_settings(tmp_path))
    assert "not a file" in caught.value.message


def test_a_catalogue_with_no_header_says_which_columns_it_wants(tmp_path) -> None:
    path = tmp_path / "prices.csv"
    path.write_text("", encoding="utf-8")
    with pytest.raises(ProviderNotConfigured) as caught:
        research_market(BRIEF, settings=_catalogue_settings(path))
    assert "name,seller,price" in (caught.value.remediation or "")


def test_an_enormous_catalogue_is_refused_before_a_byte_is_read(tmp_path) -> None:
    from app.ai_features.providers import local_catalogue

    path = tmp_path / "prices.csv"
    path.write_text(
        "name,seller,price\n" + "x,y,1\n" * (local_catalogue._MAX_CATALOGUE_BYTES // 6),
        encoding="utf-8",
    )
    with pytest.raises(ProviderFailed) as caught:
        research_market(BRIEF, settings=_catalogue_settings(path))
    assert "catalogue limit" in caught.value.message


def test_the_offline_provider_needs_nothing_installed() -> None:
    # The only provider in the package that adds no install step of any kind: csv and json are
    # stdlib, so "is it available" is purely a question of the path being set.
    assert registry.LOCAL_CATALOGUE.required_modules == ()
    settings = build_settings({
        "AI_FEATURES_ENABLED": "true",
        "AI_MARKET_RESEARCH_ENABLED": "true",
        "AI_MARKET_RESEARCH_CATALOGUE_PATH": "/anywhere/prices.csv",
    })
    assert ai_features.is_available(Capability.MARKET_RESEARCH, settings) is True


def test_a_result_from_the_edge_device_merges_into_market_analysis_unchanged(tmp_path) -> None:
    from app.ai_features.market_research import to_price_observations
    from app.services import market_analysis as ma

    path = tmp_path / "prices.csv"
    path.write_text(CATALOGUE_CSV, encoding="utf-8")
    result = research_market(BRIEF, settings=_catalogue_settings(path))
    observations = to_price_observations(result)

    verdict = ma.judge_band("stole", 1000, 2000, observations)
    # Five observations is below market_analysis's own verdict floor, so it declines to judge —
    # exactly as it would for five surveyed prices. Retrieved data gets no special credibility.
    assert verdict.verdict == "UNVERIFIABLE"
    assert all(o.source == "RETRIEVED_LISTING" for o in observations)
