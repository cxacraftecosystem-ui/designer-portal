"""The deterministic half of market research, which is nearly all of it. NO NETWORK ANYWHERE.

Every provider row in this file is a literal — recorded shapes, not fetched ones — because the
whole argument of `app/ai_features/market_research.py` is that once a listing has arrived, deciding
what it means is arithmetic and string handling rather than judgement. If that is true, it is
exhaustively testable offline, and this file is the proof obligation.

Two of these tests exist to defend properties nothing else can catch:

  * ``test_a_retrieved_price_is_never_counted_as_a_respondents`` and its neighbours, because a
    scraped number reaching a ministry report as fieldwork is the worst outcome this feature has;
  * ``test_the_tokeniser_agrees_with_market_analysis``, because this module reimplements that
    module's Unicode rule and a silent divergence would break Odia and nothing else.
"""

import pytest

from app.ai_features import market_research as mr
from app.ai_features.errors import InvalidBrief, ProvenanceViolation
from app.ai_features.settings import build_settings
from app.ai_features.types import (
    RETRIEVED_SOURCE,
    RawListing,
    ResearchBrief,
    ResearchResult,
    RetrievedProduct,
)
from app.services import market_analysis as ma

SETTINGS = build_settings({})

#: An Odia phrase with a virama and a matra — the two characters a naive ``\w`` treats as word
#: boundaries. "handwoven Sambalpuri silk saree".
ODIA = "ହାତବୁଣା ସମ୍ବଲପୁରୀ ରେଶମ ଶାଢ଼ୀ"


def _listing(
    name: str,
    price: str,
    *,
    seller: str = "A Shop",
    url: str = "",
    provider: str = "local_catalogue",
    query: str = "ikat stole",
    when: str = "2026-08-08T00:00:00+00:00",
    currency_hint: str = "",
) -> RawListing:
    return RawListing(
        name=name,
        seller=seller,
        price_text=price,
        url=url,
        provider=provider,
        query=query,
        retrieved_at=when,
        currency_hint=currency_hint,
    )


def _product(price: float | None, *, name: str = "Ikat stole", url: str = "",
             currency: str = "INR") -> RetrievedProduct:
    return RetrievedProduct(
        name=name,
        seller="A Shop",
        price=price,
        currency=currency if price is not None else "",
        source_url=url,
        retrieved_at="2026-08-08T00:00:00+00:00",
        provider="local_catalogue",
    )


BRIEF = ResearchBrief(keyword="sambalpuri ikat stole", craft="handloom", category="stole")


# --------------------------------------------------------------------------------------------
# 1. Tokenising: the rule is market_analysis's, and it has to stay that way.
# --------------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    "text",
    [
        "Handwoven Sambalpuri silk stole",
        ODIA,
        f"Sambalpuri {ODIA} stole",
        "buy online best price",
        "",
        "a of in on",
        "don't-stop 123 abc456def",
    ],
)
def test_the_tokeniser_agrees_with_market_analysis(text: str) -> None:
    # market_research reimplements market_analysis's Unicode rule rather than importing a private
    # name. This is the check that the copy has not drifted — including through the ASCII fast
    # path, which must produce exactly what the character scan would.
    assert set(mr.content_words(text, stopwords=ma._STOPWORDS)) == ma._tokens(text)


def test_an_odia_keyword_still_has_content_words() -> None:
    # The failure this guards: a combining mark treated as a word boundary shatters every Odia
    # word into fragments, the length floor discards them, and the feature silently does nothing
    # for exactly the fieldwork this application exists to collect.
    words = mr.content_words(ODIA)
    assert len(words) == 4
    assert all(len(word) >= 3 for word in words)


def test_content_words_keep_their_order_and_lose_their_repeats() -> None:
    assert mr.content_words("Stole ikat stole silk") == ("stole", "ikat", "silk")


# --------------------------------------------------------------------------------------------
# 2. Query planning: deterministic, re-runnable, and never circular.
# --------------------------------------------------------------------------------------------


def test_a_bare_keyword_plans_exactly_one_query() -> None:
    # One query is one credit. A brief with no facets must not silently cost three.
    assert mr.plan_queries(ResearchBrief(keyword="ikat stole")) == ("ikat stole",)


def test_facets_narrow_the_query_and_are_capped() -> None:
    planned = mr.plan_queries(
        ResearchBrief(
            keyword="ikat stole",
            craft="handloom",
            category="stole",
            materials=("tussar silk",),
            place="Sambalpur",
        ),
        max_queries=3,
    )
    assert planned[0] == "ikat stole"
    assert "handloom" in planned[1]
    assert len(planned) == 3
    # "stole" is already in the keyword; a facet must not repeat a word into the query.
    assert planned[1].count("stole") == 1


def test_the_declared_price_band_never_reaches_a_query() -> None:
    # THE CIRCULARITY GUARD. Searching "stole under 800" and then judging the designer's 600-800
    # band against what came back would be retrieving evidence selected to agree with the claim
    # under test.
    planned = mr.plan_queries(
        ResearchBrief(keyword="ikat stole", price_low=600, price_high=800, currency="INR"),
        max_queries=4,
    )
    assert all("600" not in query and "800" not in query for query in planned)


def test_a_keyword_with_no_searchable_word_is_refused() -> None:
    for keyword in ("", "   ", "!!!", "a of"):
        with pytest.raises(InvalidBrief) as caught:
            mr.plan_queries(ResearchBrief(keyword=keyword))
        assert "three letters" in (caught.value.remediation or "")


def test_planning_is_stable_across_runs() -> None:
    brief = ResearchBrief(keyword="ikat stole", craft="handloom", place="Sambalpur")
    assert mr.plan_queries(brief) == mr.plan_queries(brief)


# --------------------------------------------------------------------------------------------
# 3. Price parsing. The Indian digit grouping is the case the whole function exists for.
# --------------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("text", "amount", "currency"),
    [
        # The case the brief called out, in both the forms a listing writes it.
        ("₹1,20,000.00", 120000.0, "INR"),
        ("1,20,000", 120000.0, "INR"),
        ("₹1,20,000", 120000.0, "INR"),
        ("Rs 1,299", 1299.0, "INR"),
        ("Rs. 1,299.50", 1299.5, "INR"),
        ("INR 950", 950.0, "INR"),
        ("₹950", 950.0, "INR"),
        ("$45.99", 45.99, "USD"),
        ("USD 45.99", 45.99, "USD"),
        ("£25", 25.0, "GBP"),
        # European grouping: the last separator decides, whichever character it is.
        ("1.234,56 €", 1234.56, "EUR"),
        ("€1.234", 1234.0, "EUR"),
        # A sale price beside a struck-through MRP, and a variant range. Lowest in both.
        ("₹1,299 ₹1,999", 1299.0, "INR"),
        ("₹800 - 1,200", 800.0, "INR"),
        ("from ₹499", 499.0, "INR"),
        # A percentage is not a price.
        ("₹1,299 (20% off)", 1299.0, "INR"),
        ("20% off ₹1,299", 1299.0, "INR"),
        # No currency at all: the default applies and the fact is recorded.
        ("1299", 1299.0, "INR"),
    ],
)
def test_a_price_field_is_read_the_way_a_person_would_read_it(
    text: str, amount: float, currency: str
) -> None:
    parsed = mr.parse_price(text, default_currency="INR")
    assert parsed is not None, text
    assert parsed.amount == pytest.approx(amount)
    assert parsed.currency == currency
    assert parsed.raw == text.strip()


@pytest.mark.parametrize(
    "text",
    [
        "",
        "   ",
        None,
        "Call for price",
        "Free",
        "₹0",
        "-",
        "1,2345",  # four digits after a separator is neither grouping nor a decimal
        "Sold out",
    ],
)
def test_a_field_with_no_readable_price_returns_nothing_rather_than_a_guess(text) -> None:
    assert mr.parse_price(text, default_currency="INR") is None


def test_indian_grouping_is_flagged_so_a_reader_can_check_it() -> None:
    parsed = mr.parse_price("₹1,20,000", default_currency="INR")
    assert parsed is not None
    assert any("Indian digit grouping" in note for note in parsed.notes)
    # Ordinary thousands grouping is unremarkable and gets no note.
    plain = mr.parse_price("₹1,299", default_currency="INR")
    assert plain is not None
    assert not any("Indian" in note for note in plain.notes)


def test_taking_the_lowest_of_several_numbers_is_recorded() -> None:
    parsed = mr.parse_price("₹800 - 1,200", default_currency="INR")
    assert parsed is not None
    assert any("lowest" in note for note in parsed.notes)


def test_a_missing_currency_is_recorded_and_never_invented() -> None:
    assert mr.parse_price("1299", default_currency="") is None
    parsed = mr.parse_price("1299", default_currency="INR")
    assert parsed is not None
    assert any("no currency" in note for note in parsed.notes)


@pytest.mark.parametrize(
    ("hint", "code"),
    [("₹", "INR"), ("Rs.", "INR"), ("inr", "INR"), ("$", "USD"), ("€", "EUR"),
     ("GBP", "GBP"), ("", ""), ("wat", "WAT"), ("!!", "")],
)
def test_currency_hints_normalise_to_iso_codes(hint: str, code: str) -> None:
    assert mr.normalise_currency(hint, "") == code


# --------------------------------------------------------------------------------------------
# 4. Identity and duplicates.
# --------------------------------------------------------------------------------------------


def test_the_same_listing_from_two_queries_is_one_listing() -> None:
    a = "https://Example.invalid/p/12?utm_source=google&colour=red"
    b = "https://www.example.invalid/p/12/?colour=red&utm_campaign=x#reviews"
    assert mr.canonical_url(a) == mr.canonical_url(b)


def test_two_products_on_one_site_stay_two_products() -> None:
    # Stripping the query string wholesale would merge every item on a marketplace into one row.
    assert mr.canonical_url("https://x.invalid/p?id=1") != mr.canonical_url("https://x.invalid/p?id=2")


def test_duplicates_are_removed_and_counted() -> None:
    products = [
        _product(800, url="https://x.invalid/a?utm_source=q1"),
        _product(800, url="https://x.invalid/a?utm_source=q2"),
        _product(950, url="https://x.invalid/b"),
    ]
    kept, removed = mr.deduplicate(products)
    assert removed == 1
    assert len(kept) == 2


def test_listings_without_a_url_fall_back_to_their_fields() -> None:
    kept, removed = mr.deduplicate([
        _product(800, name="Ikat stole"),
        _product(800, name="ikat  STOLE"),
        _product(900, name="Ikat stole"),
    ])
    assert removed == 1
    assert len(kept) == 2


# --------------------------------------------------------------------------------------------
# 5. Outliers: a wide fence, and nothing vanishes.
# --------------------------------------------------------------------------------------------


#: A plausible craft sample: two orders of magnitude of real products, plus a sample swatch and a
#: wholesale lot that are retrieval accidents rather than market information.
_CRAFT_PRICES = [400, 650, 800, 950, 1200, 1500, 2200, 40000]


def test_the_fence_catches_accidents_and_leaves_the_premium_product_alone() -> None:
    products = [_product(price) for price in [5, *_CRAFT_PRICES, 300000]]
    kept, rejected = mr.reject_outliers(products)
    assert sorted(item.price for item, _ in rejected) == [5, 300000]
    # The ₹40,000 heirloom silk is the data a designer most needs to see. A raw 1.5-IQR fence
    # would take it; a log-space 3-IQR fence does not.
    assert 40000 in [item.price for item in kept]


def test_a_rejected_price_is_returned_with_the_fence_that_rejected_it() -> None:
    _kept, rejected = mr.reject_outliers([_product(p) for p in [5, *_CRAFT_PRICES, 300000]])
    assert rejected
    for _item, reason in rejected:
        assert "fence" in reason and "IQR" in reason


def test_nothing_is_rejected_from_a_sample_too_small_to_draw_a_fence_on() -> None:
    products = [_product(p) for p in (400, 900, 100000)]
    kept, rejected = mr.reject_outliers(products)
    assert rejected == ()
    assert len(kept) == 3


def test_a_sample_big_enough_to_describe_is_not_big_enough_to_judge() -> None:
    # Five real craft prices, one of them a heirloom bridal weave. The quartiles of five numbers
    # sit so close together that even a three-IQR log fence rejects the most interesting row —
    # which is why rejection takes the higher floor. Found by a test, not by reasoning.
    products = [_product(p) for p in (1299, 1850, 2450, 3200, 120000)]
    kept, rejected = mr.reject_outliers(products)
    assert rejected == ()
    assert 120000 in [item.price for item in kept]


def test_the_rejection_floor_is_market_analysis_verdict_floor() -> None:
    # Describing a spread takes five observations there and here; calling one of them wrong takes
    # eight in both places. One rule, stated twice, asserted once.
    assert mr.MIN_RETRIEVED_FOR_OUTLIER_REJECTION == ma.MIN_SAMPLE_FOR_VERDICT
    assert mr.MIN_RETRIEVED_FOR_OUTLIER_REJECTION > mr.MIN_RETRIEVED_FOR_DISTRIBUTION


def test_a_sample_with_no_spread_rejects_nothing() -> None:
    # Half a sample at one price is a real thing — a standard mill rate. There is no fence to
    # draw, and drawing one anyway would reject everything that is not the mode.
    products = [_product(800) for _ in range(9)]
    kept, rejected = mr.reject_outliers(products)
    assert rejected == ()
    assert len(kept) == 9


# --------------------------------------------------------------------------------------------
# 6. The merge into PriceObservation — and the provenance that makes it safe.
# --------------------------------------------------------------------------------------------


def _result(prices: list[str], **kwargs) -> ResearchResult:
    listings = [
        _listing(f"Sambalpuri ikat stole {index}", price, url=f"https://x.invalid/p/{index}")
        for index, price in enumerate(prices)
    ]
    return mr.consolidate(
        listings, brief=BRIEF, provider="local_catalogue", queries=("sambalpuri ikat stole",),
        settings=SETTINGS, retrieved_at="2026-08-08T09:00:00+00:00", **kwargs
    )


def test_retrieved_products_become_price_observations_market_analysis_can_use() -> None:
    result = _result(["₹800", "₹950", "₹1,200", "₹1,500", "₹2,200"])
    observations = mr.to_price_observations(result)
    assert len(observations) == 5
    assert isinstance(observations[0], ma.PriceObservation)
    # The existing machinery works on them unchanged — that is the point of the shared shape.
    described = ma.describe([o.amount for o in observations])
    assert described is not None and described.count == 5
    assert described.quantiles_reported is True


def test_every_retrieved_observation_carries_its_url_and_its_timestamp() -> None:
    observations = mr.to_price_observations(_result(["₹800", "₹950"]))
    for observation in observations:
        assert "https://x.invalid/p/" in observation.label
        assert "2026-08-08" in observation.label


def test_a_retrieved_price_is_never_counted_as_a_respondents() -> None:
    retrieved = mr.to_price_observations(_result(["₹800", "₹950", "₹1,200"]))
    assert {o.source for o in retrieved} == {RETRIEVED_SOURCE}
    assert RETRIEVED_SOURCE not in ("RESPONDENT", "COMPETITOR")

    surveyed = ma.collect_observations(
        [{"priceExpectation": "700", "respondentName": "A"}],
        [{"name": "Rival stole", "price": "1500", "category": "stole"}],
    )
    mixed = surveyed + retrieved
    # Both of market_analysis's own filters exclude the retrieved rows without a line of new code.
    assert len([o for o in mixed if o.source == "RESPONDENT"]) == 1
    assert len([o for o in mixed if o.source == "COMPETITOR"]) == 1


def test_retrieved_prices_cannot_move_a_competitors_position() -> None:
    # position_competitors places a shelf price against what BUYERS said. Sixty retrieved listings
    # must not be able to shift that number by a rupee.
    competitors = [{"name": "Rival stole", "price": "1500", "category": "stole"}]
    surveyed = ma.collect_observations(
        [{"priceExpectation": str(400 + index * 100)} for index in range(12)], []
    )
    before = ma.position_competitors(competitors, surveyed)
    after = ma.position_competitors(
        competitors, surveyed + mr.to_price_observations(_result(["₹9,000"] * 3))
    )
    assert before[0].percentile == after[0].percentile
    assert before[0].note == after[0].note


def test_feeding_a_retrieved_price_into_a_surveyed_computation_raises() -> None:
    mixed = ma.collect_observations([{"priceExpectation": "700"}], []) + mr.to_price_observations(
        _result(["₹800"])
    )
    with pytest.raises(ProvenanceViolation) as caught:
        mr.assert_surveyed(mixed, where="the respondent price distribution")
    assert caught.value.offending
    assert "surveyed_only" in (caught.value.remediation or "")
    assert caught.value.as_dict()["code"] == "provenance_violation"


def test_surveyed_only_is_the_deliberate_filter() -> None:
    mixed = ma.collect_observations([{"priceExpectation": "700"}], []) + mr.to_price_observations(
        _result(["₹800"])
    )
    kept = mr.surveyed_only(mixed)
    assert len(kept) == 1
    mr.assert_surveyed(kept, where="a test")  # no longer raises


def test_the_wire_form_says_it_is_retrieved_without_being_read() -> None:
    payload = _result(["₹800", "₹950"]).as_dict()
    assert payload["provenance"] == RETRIEVED_SOURCE
    assert all(item["provenance"] == RETRIEVED_SOURCE for item in payload["products"])
    assert "RETRIEVED" in payload["cautions"][0]


# --------------------------------------------------------------------------------------------
# 7. Refusing to conclude, and saying why.
# --------------------------------------------------------------------------------------------


def test_the_floor_is_the_same_floor_market_analysis_uses() -> None:
    # One floor in the system, not two that drift apart.
    assert mr.MIN_RETRIEVED_FOR_DISTRIBUTION == ma.MIN_SAMPLE_FOR_QUANTILES


def test_below_the_floor_the_listings_are_shown_and_the_distribution_is_withheld() -> None:
    result = _result(["₹800", "₹950", "₹1,200"])
    assert len(result.products) == 3
    assert result.distribution is None
    assert any("no median or spread is reported" in caution for caution in result.cautions)


def test_at_the_floor_a_distribution_is_reported() -> None:
    result = _result(["₹800", "₹950", "₹1,200", "₹1,500", "₹2,200"])
    assert result.distribution is not None
    assert result.distribution.count == 5


def test_finding_nothing_is_reported_as_a_failed_search_not_as_a_market() -> None:
    result = _result([])
    assert result.products == ()
    assert any("failed search, not a finding" in caution for caution in result.cautions)


def test_the_provenance_caution_comes_first_and_names_the_provider_and_the_date() -> None:
    result = _result(["₹800", "₹950", "₹1,200", "₹1,500", "₹2,200"])
    first = result.cautions[0]
    assert first.startswith("These 5 price(s) were RETRIEVED from local_catalogue on 2026-08-08")
    assert "not answers from anybody the survey spoke to" in first
    assert "willingness to pay" in first


def test_the_distribution_payload_matches_market_analysis_field_for_field() -> None:
    # market_research duplicates a private helper rather than importing it. This is the check that
    # the duplicate has not drifted.
    result = _result(["₹800", "₹950", "₹1,200", "₹1,500", "₹2,200"])
    assert result.as_dict()["distribution"] == ma._distribution_payload(result.distribution)


# --------------------------------------------------------------------------------------------
# 8. Currency: normalised, never converted.
# --------------------------------------------------------------------------------------------


def test_a_foreign_currency_is_excluded_and_counted_never_converted() -> None:
    listings = [
        _listing("Sambalpuri ikat stole A", "₹800", url="https://x.invalid/1"),
        _listing("Sambalpuri ikat stole B", "₹950", url="https://x.invalid/2"),
        _listing("Sambalpuri ikat stole C", "$45.99", url="https://x.invalid/3"),
    ]
    result = mr.consolidate(
        listings, brief=BRIEF, provider="serper_shopping", queries=("q",), settings=SETTINGS
    )
    assert result.currency == "INR"
    assert result.other_currencies == {"USD": 1}
    assert all(item.currency == "INR" for item in result.products)
    assert any("never converted" in caution for caution in result.cautions)
    # The excluded listing is still visible, with the reason.
    assert any("does not convert" in reason for _item, reason in result.rejected)


def test_the_configured_currency_wins_over_the_mere_majority() -> None:
    # An Indian craft brief that happens to retrieve more US listings still reports the Indian
    # market, because that is the market the fieldwork is in.
    listings = [
        _listing(f"Sambalpuri ikat stole {i}", "$40.00", url=f"https://x.invalid/u{i}")
        for i in range(4)
    ] + [_listing("Sambalpuri ikat stole in", "₹800", url="https://x.invalid/in")]
    result = mr.consolidate(
        listings, brief=BRIEF, provider="serper_shopping", queries=("q",), settings=SETTINGS
    )
    assert result.currency == "INR"
    assert result.other_currencies == {"USD": 4}


# --------------------------------------------------------------------------------------------
# 9. Relevance: a retrieval aid that refuses to empty the result.
# --------------------------------------------------------------------------------------------


def test_an_off_topic_listing_is_set_aside_with_its_score() -> None:
    listings = [
        _listing("Sambalpuri ikat silk stole", "₹1,200", url="https://x.invalid/1"),
        _listing("Ikat print mobile phone case", "₹299", url="https://x.invalid/2"),
    ]
    result = mr.consolidate(
        listings, brief=BRIEF, provider="serper_shopping", queries=("q",), settings=SETTINGS
    )
    assert [item.name for item in result.products] == ["Sambalpuri ikat silk stole"]
    assert any("not a comparable product" in reason for _item, reason in result.rejected)


def test_when_nothing_matches_the_vocabulary_everything_is_kept_and_flagged() -> None:
    # An English marketplace answering an Odia keyword scores zero on every row. Returning an
    # empty result would report that as "the market has nothing", which is a different and false
    # statement.
    listings = [
        _listing("Handwoven silk scarf", "₹1,200", url="https://x.invalid/1"),
        _listing("Cotton table runner", "₹450", url="https://x.invalid/2"),
    ]
    result = mr.consolidate(
        listings, brief=ResearchBrief(keyword=ODIA), provider="serper_shopping",
        queries=("q",), settings=SETTINGS,
    )
    assert len(result.products) == 2
    assert any("shown unfiltered" in caution for caution in result.cautions)


def test_relevance_scores_what_it_found() -> None:
    assert mr.score_relevance(("sambalpuri", "ikat", "stole"), "Ikat silk stole") == pytest.approx(
        2 / 3
    )
    assert mr.score_relevance(("sambalpuri", "ikat", "stole"), "Plastic bucket") == 0.0
    assert mr.score_relevance((), "anything") == 0.0


# --------------------------------------------------------------------------------------------
# 10. The pipeline accounts for every row it was given.
# --------------------------------------------------------------------------------------------


def test_nothing_is_ever_silently_dropped() -> None:
    listings = [
        _listing("Sambalpuri ikat stole A", "₹800", url="https://x.invalid/1"),
        _listing("Sambalpuri ikat stole A", "₹800", url="https://x.invalid/1?utm_source=q2"),
        _listing("Sambalpuri ikat stole B", "Call for price", url="https://x.invalid/2"),
        _listing("Plastic bucket", "₹99", url="https://x.invalid/3"),
        _listing("Sambalpuri ikat stole D", "$40", url="https://x.invalid/4"),
        _listing("Sambalpuri ikat stole E", "₹950", url="https://x.invalid/5"),
    ]
    result = mr.consolidate(
        listings, brief=BRIEF, provider="serper_shopping", queries=("q1", "q2"), settings=SETTINGS
    )
    assert (
        len(result.products) + len(result.rejected) + result.duplicates_removed == len(listings)
    )
    assert result.duplicates_removed == 1


def test_a_row_with_no_readable_price_is_reported_rather_than_hidden() -> None:
    result = mr.consolidate(
        [_listing("Sambalpuri ikat stole", "Call for price", url="https://x.invalid/1")],
        brief=BRIEF, provider="serper_shopping", queries=("q",), settings=SETTINGS,
    )
    assert any("no readable price" in reason for _item, reason in result.rejected)


def test_the_result_is_json_safe() -> None:
    import json

    json.dumps(_result(["₹800", "₹950", "₹1,200", "₹1,500", "₹2,200"]).as_dict())


# --------------------------------------------------------------------------------------------
# 11. The courtesy throttle, tested without sleeping.
# --------------------------------------------------------------------------------------------


class _Clock:
    def __init__(self) -> None:
        self.now = 1000.0
        self.slept: list[float] = []

    def __call__(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.slept.append(seconds)
        self.now += seconds


@pytest.fixture(autouse=True)
def _clear_throttle():
    from app.ai_features.runtime import reset_throttle_cache

    reset_throttle_cache()
    yield
    reset_throttle_cache()


def test_the_first_call_is_never_delayed() -> None:
    from app.ai_features.runtime import throttle

    clock = _Clock()
    assert throttle("k", 1.0, clock=clock, sleeper=clock.sleep) == 0.0
    assert clock.slept == []


def test_a_second_call_waits_out_the_interval() -> None:
    from app.ai_features.runtime import throttle

    clock = _Clock()
    throttle("k", 1.0, clock=clock, sleeper=clock.sleep)
    clock.now += 0.25
    assert throttle("k", 1.0, clock=clock, sleeper=clock.sleep) == pytest.approx(0.75)
    assert clock.slept == [pytest.approx(0.75)]


def test_a_zero_interval_turns_the_throttle_off_entirely() -> None:
    from app.ai_features.runtime import throttle

    clock = _Clock()
    throttle("k", 0.0, clock=clock, sleeper=clock.sleep)
    throttle("k", 0.0, clock=clock, sleeper=clock.sleep)
    assert clock.slept == []


def test_the_throttle_cannot_eat_a_deadline() -> None:
    from app.ai_features.runtime import Deadline, throttle

    clock = _Clock()
    throttle("k", 30.0, clock=clock, sleeper=clock.sleep)
    spent = Deadline(budget_seconds=0.5, started_at=Deadline.start(0).started_at)
    waited = throttle("k", 30.0, deadline=spent, clock=clock, sleeper=clock.sleep)
    assert waited <= 0.5


def test_keys_do_not_throttle_each_other() -> None:
    from app.ai_features.runtime import throttle

    clock = _Clock()
    throttle("serper", 1.0, clock=clock, sleeper=clock.sleep)
    assert throttle("serpapi", 1.0, clock=clock, sleeper=clock.sleep) == 0.0


# --------------------------------------------------------------------------------------------
# 12. Arithmetic sanity that the rest of the file leans on.
# --------------------------------------------------------------------------------------------


def test_the_log_fence_is_symmetric_in_ratio() -> None:
    # The property the log space buys: a sample doubled everywhere keeps the same members.
    base = [_product(p) for p in [5, *_CRAFT_PRICES, 300000]]
    scaled = [_product(p.price * 7.0) for p in base if p.price is not None]
    kept_base, _ = mr.reject_outliers(base)
    kept_scaled, _ = mr.reject_outliers(scaled)
    assert [p.price for p in kept_scaled] == [
        pytest.approx(p.price * 7.0) for p in kept_base if p.price is not None
    ]


def test_utc_now_iso_is_a_timestamp_somebody_can_read() -> None:
    # One format everywhere it is read, and unambiguous about its zone: a retrieval timestamp in
    # local time is worthless the moment the report crosses a border.
    stamp = mr.utc_now_iso()
    assert stamp.endswith("+00:00")
    assert "T" in stamp and len(stamp) == 25
