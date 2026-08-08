"""Stage 9's computed findings: the arithmetic, and the refusals.

Two thirds of these tests are about what the module DECLINES to say. That is deliberate and it is
the point of the module: a market analysis that produces confident quantiles from four responses
is not a weaker version of a good analysis, it is a worse outcome than none, because the
confidence is what gets carried into a document a ministry reads. So the sample floors, the
"unverifiable" verdict and the single-group caution are pinned as hard as the quantile method.

The quantile method itself is pinned by VALUE, not by property, because `frontend/lib/
marketAnalysis.ts` and the Kotlin port have to produce the same rupee figure from the same rows.
A property test ("the median lies between the extremes") would pass for three mutually
incompatible implementations.
"""

import pytest

from app.services.market_analysis import (
    MIN_SAMPLE_FOR_QUANTILES,
    MIN_SAMPLE_FOR_VERDICT,
    analyse,
    as_number,
    cluster_products,
    describe,
    judge_band,
    link_swot_evidence,
    position_competitors,
    quantile,
)

# --------------------------------------------------------------------------------------
# Reading the stored values
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    "raw,expected",
    [
        ("1200.00", 1200.0),      # MONEY's stored form
        ("1,200", 1200.0),        # a designer typing grouping commas
        (450, 450.0),
        (450.5, 450.5),
        ("", None),
        (None, None),
        ("   ", None),
        ("abc", None),
        ("NaN", None),            # would poison every quantile downstream of it
        ("Infinity", None),
        ("-Infinity", None),
        (True, None),             # bool is an int in Python; a tick is not a price
        ("-300", -300.0),         # a negative is a data error, but it is the CALLER's to judge
    ],
)
def test_as_number_reads_what_is_stored_and_refuses_what_is_not(raw, expected):
    assert as_number(raw) == expected


# --------------------------------------------------------------------------------------
# The quantile method, pinned by value for the ports
# --------------------------------------------------------------------------------------


def test_quantile_matches_linear_interpolation_on_n_minus_one():
    """The exact contract the TypeScript and Kotlin ports must reproduce."""
    sample = [10.0, 20.0, 30.0, 40.0]
    # position = q*(n-1) = q*3
    assert quantile(sample, 0.0) == 10.0
    assert quantile(sample, 0.25) == 17.5     # position 0.75 -> 10 + (20-10)*0.75
    assert quantile(sample, 0.5) == 25.0      # position 1.5  -> 20 + (30-20)*0.5
    assert quantile(sample, 0.75) == 32.5     # position 2.25 -> 30 + (40-30)*0.25
    assert quantile(sample, 1.0) == 40.0


def test_quantile_of_a_single_value_is_that_value():
    assert quantile([7.0], 0.25) == 7.0
    assert quantile([7.0], 0.99) == 7.0


def test_quantile_of_an_empty_sample_raises_rather_than_returning_zero():
    """Zero is a price. Returning it for "no data" would print ₹0 into a cost table."""
    with pytest.raises(ValueError):
        quantile([], 0.5)


# --------------------------------------------------------------------------------------
# Describing a sample, and refusing to describe a small one
# --------------------------------------------------------------------------------------


def test_describe_withholds_quartiles_below_the_floor():
    described = describe([100.0, 200.0, 300.0, 400.0])   # 4 < MIN_SAMPLE_FOR_QUANTILES
    assert described is not None
    assert described.count == 4
    assert described.quantiles_reported is False
    # Filled with the median, so a caller ignoring the flag still shows something defensible
    # rather than a quartile computed from two numbers.
    assert described.p25 == described.median == described.p75 == 250.0
    # The extremes and the mean are honest at any sample size and are reported.
    assert (described.minimum, described.maximum, described.mean) == (100.0, 400.0, 250.0)


def test_describe_reports_quartiles_at_the_floor():
    described = describe([100.0, 200.0, 300.0, 400.0, 500.0])
    assert described is not None
    assert described.count == MIN_SAMPLE_FOR_QUANTILES
    assert described.quantiles_reported is True
    assert (described.p25, described.median, described.p75) == (200.0, 300.0, 400.0)


def test_describe_of_nothing_is_none_not_a_zero_distribution():
    assert describe([]) is None


# --------------------------------------------------------------------------------------
# The band verdict
# --------------------------------------------------------------------------------------


def _prices(*values, category="HOME_FURNISHING", source="RESPONDENT"):
    from app.services.market_analysis import PriceObservation

    return [PriceObservation(amount=float(v), category=category, source=source) for v in values]


def test_a_band_covering_most_of_the_evidence_is_sound():
    verdict = judge_band("HOME_FURNISHING", "400", "900",
                         _prices(420, 450, 500, 550, 600, 700, 800, 850, 1200))
    assert verdict.verdict == "SOUND"
    assert verdict.inside == 8
    assert verdict.above == 1
    assert "₹400–900" in verdict.message


def test_a_band_below_the_evidence_is_called_low_and_says_so():
    verdict = judge_band("HOME_FURNISHING", "200", "400",
                         _prices(600, 650, 700, 750, 800, 850, 900, 1000))
    assert verdict.verdict == "LOW"
    assert verdict.above == 8
    assert verdict.inside == 0
    assert "below the evidence" in verdict.message
    # The finding a designer must be able to act on: what the survey actually said.
    assert "775" in verdict.message


def test_a_band_above_the_evidence_is_called_high():
    verdict = judge_band("HOME_FURNISHING", "2000", "3000",
                         _prices(300, 350, 400, 450, 500, 550, 600, 700))
    assert verdict.verdict == "HIGH"
    assert verdict.below == 8
    assert "above the evidence" in verdict.message


def test_a_band_missing_the_middle_from_both_sides_is_narrow():
    verdict = judge_band("HOME_FURNISHING", "490", "510",
                         _prices(100, 200, 300, 500, 700, 800, 900, 1000))
    assert verdict.verdict == "NARROW"
    assert verdict.inside == 1
    assert verdict.below == 3
    assert verdict.above == 4


def test_too_few_observations_is_unverifiable_and_never_a_criticism():
    """The distinction that keeps this honest.

    Seven observations cannot tell a designer their band is wrong. Saying "NARROW" here would be
    a fabricated criticism, and a designer who is told their considered band is wrong by a machine
    counting seven numbers will — correctly — stop believing the tool.
    """
    verdict = judge_band("HOME_FURNISHING", "490", "510",
                         _prices(100, 200, 300, 700, 800, 900, 1000))
    assert len(_prices(100, 200, 300, 700, 800, 900, 1000)) < MIN_SAMPLE_FOR_VERDICT
    assert verdict.verdict == "UNVERIFIABLE"
    assert "too few" in verdict.message.lower()
    # The counts are still reported — the designer may look at them and decide for themselves.
    assert verdict.inside == 0
    assert verdict.evidence is not None and verdict.evidence.count == 7


def test_a_category_with_no_prices_is_no_evidence_not_a_bad_band():
    verdict = judge_band("APPAREL", "400", "900", [])
    assert verdict.verdict == "NO_EVIDENCE"
    assert verdict.evidence is None
    assert "cannot be checked" in verdict.message


def test_a_transposed_band_is_read_the_way_it_was_meant():
    """Low and high are adjacent boxes on a phone; transposing them is a slip, not a claim."""
    verdict = judge_band("HOME_FURNISHING", "900", "400",
                         _prices(420, 450, 500, 550, 600, 700, 800, 850))
    assert (verdict.declared_low, verdict.declared_high) == (400.0, 900.0)
    assert verdict.verdict == "SOUND"


def test_an_incomplete_band_is_reported_without_pretending_to_judge_it():
    verdict = judge_band("HOME_FURNISHING", "400", "",
                         _prices(420, 450, 500, 550, 600, 700, 800, 850))
    assert verdict.verdict == "NO_EVIDENCE"
    assert verdict.declared_high is None


# --------------------------------------------------------------------------------------
# Competitor positioning
# --------------------------------------------------------------------------------------


def test_a_competitor_is_placed_against_what_buyers_said_not_against_other_sellers():
    observations = _prices(300, 400, 500, 600, 700)                       # respondents
    observations += _prices(2000, 2200, category="HOME_FURNISHING", source="COMPETITOR")
    positions = position_competitors(
        [{"name": "Mill stole", "seller": "Big Bazaar", "category": "HOME_FURNISHING", "price": "650"}],
        observations,
    )
    assert len(positions) == 1
    placed = positions[0]
    # 4 of the 5 BUYER prices are at or below 650. If the two competitor prices had been pooled in,
    # the percentile would have been 4/7 and the finding would have been about the shelf, not the
    # buyer — which is the question a designer cannot answer any other way.
    assert placed.percentile == pytest.approx(4 / 5)
    assert placed.versus_median == pytest.approx(150.0)
    assert "80%" in placed.note


def test_a_competitor_with_no_buyer_prices_anywhere_is_not_placed():
    positions = position_competitors(
        [{"name": "Mill stole", "category": "APPAREL", "price": "650"}],
        _prices(300, 400, category="APPAREL"),
    )
    assert positions[0].percentile is None
    assert "too few" in positions[0].note.lower()


def test_a_competitor_falls_back_to_the_pooled_buyers_and_says_so():
    """The case the flagship workshop actually hits, and the one a strict lookup got wrong.

    A respondent's price expectation carries NO category — stage 8 asks what they would pay, not
    what for — so every competitor in a real workshop looked up an empty category sample and the
    entire section read "too few buyer price expectations" against a survey of forty people. The
    pooled sample is used instead, and the message has to say that it is pooled, because
    "against all buyers asked" is a weaker claim than "against buyers asked about this category".
    """
    observations = _prices(300, 400, 500, 600, 700, category="")   # uncategorised, as stage 8 stores
    positions = position_competitors(
        [{"name": "Mill stole", "category": "HOME_FURNISHING", "price": "650"}],
        observations,
    )
    assert positions[0].percentile == pytest.approx(4 / 5)
    assert "all 5 buyers asked" in positions[0].note
    assert "HOME_FURNISHING" not in positions[0].note


def test_a_category_matched_sample_is_preferred_and_named():
    observations = _prices(300, 400, 500, 600, 700, category="")
    observations += _prices(1000, 1100, 1200, 1300, 1400, category="HOME_FURNISHING")
    positions = position_competitors(
        [{"name": "Mill stole", "category": "HOME_FURNISHING", "price": "1250"}],
        observations,
    )
    # Placed against the five HOME_FURNISHING buyers, not the ten pooled ones.
    assert positions[0].percentile == pytest.approx(3 / 5)
    assert "about HOME_FURNISHING" in positions[0].note


def test_a_cheap_competitor_is_described_as_below_rather_than_above_nothing():
    """"above 20% of buyers" is a true sentence that reads as a boast. Say "below 80%" instead."""
    positions = position_competitors(
        [{"name": "Cheap import", "category": "", "price": "310"}],
        _prices(300, 400, 500, 600, 700, category=""),
    )
    assert positions[0].percentile == pytest.approx(1 / 5)
    assert "below what 80%" in positions[0].note


def test_a_competitor_with_no_price_is_skipped_rather_than_counted_as_zero():
    positions = position_competitors(
        [{"name": "Unpriced", "category": "APPAREL", "price": ""}],
        _prices(300, 400, 500, 600, 700, category="APPAREL"),
    )
    assert positions == []


# --------------------------------------------------------------------------------------
# SWOT evidence
# --------------------------------------------------------------------------------------


def test_a_swot_point_sharing_vocabulary_with_a_response_is_offered_that_response():
    supports = link_swot_evidence(
        [{"kind": "WEAKNESS", "point": "Buyers find the colours dull and the price high"}],
        [
            {"respondentName": "Rekha", "response": "The colours are dull compared to mill cloth"},
            {"respondentName": "Sunil", "response": "Nothing to do with weaving at all"},
        ],
    )
    assert supports[0].supported is True
    assert supports[0].supported_by == ("Rekha",)


def test_a_swot_point_no_respondent_touched_is_reported_unsupported():
    """The finding that matters most, and the only one this can make reliably."""
    supports = link_swot_evidence(
        [{"kind": "OPPORTUNITY", "point": "Export demand in Scandinavia is expanding"}],
        [{"respondentName": "Rekha", "response": "The colours are dull compared to mill cloth"}],
    )
    assert supports[0].supported is False
    assert supports[0].supported_by == ()


def test_a_point_that_cited_its_own_evidence_is_supported_and_not_second_guessed():
    supports = link_swot_evidence(
        [{"kind": "STRENGTH", "point": "Wholly unrelated wording",
          "evidence": "Buyer meeting, 12 Feb, notes in annexure C"}],
        [{"respondentName": "Rekha", "response": "Something else entirely"}],
    )
    assert supports[0].has_own_evidence is True
    assert supports[0].supported is True


def test_evidence_matching_works_in_a_non_latin_script():
    """The tokeniser is Unicode-aware, and this is why it has to be.

    A naive [a-z]+ scored every Odia response as having no content words, so the verdict for
    exactly the fieldwork this application exists to collect was "unsupported by the survey".
    """
    supports = link_swot_evidence(
        [{"kind": "WEAKNESS", "point": "ରଙ୍ଗ ମଉଳା ଏବଂ ଦାମ ଅଧିକ"}],
        [{"respondentName": "ରେଖା", "response": "ରଙ୍ଗ ମଉଳା ଲାଗୁଛି"}],
    )
    assert supports[0].supported is True
    assert supports[0].supported_by == ("ରେଖା",)


def test_a_blank_swot_point_is_dropped_rather_than_reported_unsupported():
    assert link_swot_evidence([{"kind": "STRENGTH", "point": "   "}], []) == []


# --------------------------------------------------------------------------------------
# Trend clustering
# --------------------------------------------------------------------------------------


def test_products_discussed_together_twice_become_one_cluster():
    responses = [
        {"productsDiscussed": ["stole", "dupatta"]},
        {"productsDiscussed": ["stole", "dupatta"]},
        {"productsDiscussed": ["floor mat"]},
    ]
    clusters = cluster_products(responses)
    members = [c.members for c in clusters]
    assert ("dupatta", "stole") in members
    # A single mention is not a trend and is not promoted to one.
    assert ("floor mat",) not in members


def test_clustering_is_stable_and_case_insensitive():
    """Same rows, same clusters, every run — which a randomly seeded k-means would not give."""
    responses = [
        {"productsDiscussed": ["Stole", "DUPATTA"]},
        {"productsDiscussed": ["stole", "dupatta"]},
    ]
    first = cluster_products(responses)
    assert first == cluster_products(responses)
    assert first[0].members == ("dupatta", "stole")


def test_a_comma_string_is_read_like_a_tag_list():
    """Android and the web both hand TAGS across as a list, but a hand-edited draft may not."""
    clusters = cluster_products([
        {"productsDiscussed": "stole, dupatta"},
        {"productsDiscussed": "stole, dupatta"},
    ])
    assert clusters[0].members == ("dupatta", "stole")


# --------------------------------------------------------------------------------------
# The whole thing
# --------------------------------------------------------------------------------------


def _survey(n: int, start: float = 400.0, step: float = 50.0, group: str = "CONSUMER"):
    return [
        {
            "respondentName": f"R{i}",
            "respondentGroup": group,
            "priceExpectation": f"{start + i * step:.2f}",
            "response": "The colours are dull and the price is high",
            "productsDiscussed": ["stole", "dupatta"],
        }
        for i in range(n)
    ]


def test_analyse_joins_every_part_and_reports_the_sample_it_used():
    findings = analyse(
        responses=_survey(10),
        competitors=[{"name": "Mill stole", "category": "", "price": "1500"}],
        bands=[{"category": "", "lowPrice": "400", "highPrice": "900"}],
        swot=[{"kind": "WEAKNESS", "point": "Colours are dull"}],
    )
    assert findings.observations == 11
    assert findings.respondent_prices is not None and findings.respondent_prices.count == 10
    assert findings.competitor_prices is not None and findings.competitor_prices.count == 1
    assert findings.bands[0].verdict in {"SOUND", "NARROW", "LOW", "HIGH"}
    assert findings.swot[0].supported is True
    assert findings.clusters


def test_a_single_respondent_group_dominating_is_said_out_loud():
    """A price figure from one group is a figure about that group, and the report must say so."""
    findings = analyse(responses=_survey(10, group="RETAILER"), competitors=[], bands=[], swot=[])
    assert any("one respondent group" in c for c in findings.cautions)
    assert findings.group_counts == {"RETAILER": 10}


def test_a_thin_survey_is_flagged_rather_than_dressed_up():
    findings = analyse(responses=_survey(3), competitors=[], bands=[], swot=[])
    assert any("not a market estimate" in c for c in findings.cautions)
    assert findings.respondent_prices is not None
    assert findings.respondent_prices.quantiles_reported is False


def test_competitor_prices_without_buyer_prices_are_flagged_as_a_different_question():
    findings = analyse(
        responses=[],
        competitors=[{"name": "A", "price": "500"}, {"name": "B", "price": "700"}],
        bands=[],
        swot=[],
    )
    assert any("what is charged" in c for c in findings.cautions)


def test_analyse_on_an_empty_workshop_says_nothing_rather_than_failing():
    """Stage 9 is opened before stage 8 is filled in more often than not."""
    findings = analyse(responses=[], competitors=[], bands=[], swot=[])
    assert findings.observations == 0
    assert findings.respondent_prices is None
    assert findings.cautions == []
    assert findings.unsupported_swot == []


def test_unsupported_swot_is_reachable_as_its_own_list():
    findings = analyse(
        responses=_survey(8),
        competitors=[],
        bands=[],
        swot=[
            {"kind": "WEAKNESS", "point": "Colours are dull"},
            {"kind": "OPPORTUNITY", "point": "Scandinavian export demand expanding rapidly"},
        ],
    )
    unsupported = findings.unsupported_swot
    assert len(unsupported) == 1
    assert unsupported[0].point.startswith("Scandinavian")
