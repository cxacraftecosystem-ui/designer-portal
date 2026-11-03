import asyncio
import re
import sys
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

import app.core.db as core_db
from app.api.router import api_router
from app.core import deps
from app.services.workshop_analytics import (
    _INTERVAL_RANK,
    ADOPTED_STATUSES,
    CONCENTRATION_LIMIT,
    MIN_OBSERVATIONS_FOR_RATE,
    MIN_SHEETS_FOR_RATIO,
    MIN_WORKSHOPS_FOR_RATE,
    MIN_WORKSHOPS_FOR_THEME,
    SURVIVAL_INTERVALS,
    WorkshopRows,
    analyse_archive,
    analyse_cost_ratios,
    analyse_opportunities,
    analyse_survival,
    archive_findings_payload,
    latest_interval_rows,
    read_observation,
    summarise_adoption,
)


def follow_up(status: str, interval: str = "M12", **extra: Any) -> dict[str, Any]:
    return {"adoptionStatus": status, "interval": interval, **extra}


def workshop(index: int, *, craft: str = "Ikat", cluster: str = "Bargarh",
             state: str = "Odisha", statuses: tuple[str, ...] = (),
             interval: str = "M12", **rows: Any) -> WorkshopRows:
    return WorkshopRows(
        workshop_id=f"w{index}",
        title=f"Workshop {index}",
        craft=craft,
        cluster=cluster,
        state=state,
        follow_ups=tuple(follow_up(s, interval) for s in statuses),
        **rows,
    )


def adopted_archive(count: int, *, per_workshop: int = 2, adopted: int = 1,
                    **kwargs: Any) -> list[WorkshopRows]:
    statuses = tuple(
        "ADOPTED_IN_PRODUCTION" if i < adopted else "NOT_ADOPTED" for i in range(per_workshop)
    )
    return [workshop(i, statuses=statuses, **kwargs) for i in range(count)]


def observations(*rows: dict[str, Any], workshop_id: str = "w1") -> list[Any]:
    parsed = [read_observation(workshop_id, row) for row in rows]
    return [o for o in parsed if o is not None]



def test_a_row_with_no_adoption_status_is_dropped_rather_than_read_as_not_known():
    assert read_observation("w1", {"interval": "M3"}) is None
    findings = analyse_archive(
        [WorkshopRows(workshop_id="w1", follow_ups=({"interval": "M3"},))],
        workshops_in_archive=1,
    )
    assert findings.rows_without_status == 1
    assert findings.follow_up_rows == 0
    assert any("no adoption status" in caution for caution in findings.cautions)


def test_money_is_read_off_the_stored_fixed_two_string():
    row = follow_up("ADOPTED_IN_PRODUCTION", revenue="62,700.00", unitsSold=38, ordersReceived=3)
    parsed = read_observation("w1", row)
    assert parsed is not None
    assert parsed.revenue == 62700.0
    assert parsed.units_sold == 38.0
    assert parsed.orders == 3.0



def test_only_the_latest_interval_counts_and_the_rest_are_counted_not_dropped():
    rows = observations(
        follow_up("TRIAL", "M3"),
        follow_up("TRIAL", "M6"),
        follow_up("ADOPTED_IN_PRODUCTION", "M12"),
    )
    kept, superseded = latest_interval_rows(rows)
    assert [o.interval for o in kept] == ["M12"]
    assert superseded == 2

    findings = analyse_archive(
        [WorkshopRows(workshop_id="w1", follow_ups=tuple(
            follow_up(s, i) for s, i in
            (("TRIAL", "M3"), ("TRIAL", "M6"), ("ADOPTED_IN_PRODUCTION", "M12"))
        ))],
        workshops_in_archive=1,
    )
    assert findings.overall.observations == 1
    assert findings.overall.adopted == 1
    assert findings.superseded_rows == 2
    assert any("set aside" in note for note in findings.notes)


def test_ad_hoc_ranks_below_three_months_so_it_cannot_supersede_a_twelve_month_visit():
    rows = observations(
        follow_up("NOT_ADOPTED", "AD_HOC"),
        follow_up("ADOPTED_IN_PRODUCTION", "M12"),
    )
    kept, superseded = latest_interval_rows(rows)
    assert [o.status for o in kept] == ["ADOPTED_IN_PRODUCTION"]
    assert superseded == 1



def test_too_few_workshops_gets_counts_and_no_rate():
    findings = analyse_archive(adopted_archive(3, per_workshop=4, adopted=3),
                               workshops_in_archive=200)
    assert findings.overall.observations == 12
    assert findings.overall.adopted == 9
    assert findings.overall.rate is None
    assert f"at least {MIN_WORKSHOPS_FOR_RATE} workshops" in findings.overall.message


def test_enough_workshops_but_too_few_outcomes_also_gets_no_rate():
    findings = analyse_archive(adopted_archive(MIN_WORKSHOPS_FOR_RATE, per_workshop=1, adopted=1),
                               workshops_in_archive=200)
    assert findings.overall.workshops == MIN_WORKSHOPS_FOR_RATE
    assert findings.overall.observations == MIN_WORKSHOPS_FOR_RATE
    assert findings.overall.observations < MIN_OBSERVATIONS_FOR_RATE
    assert findings.overall.rate is None
    assert f"below the {MIN_OBSERVATIONS_FOR_RATE}" in findings.overall.message


def test_a_rate_appears_only_once_both_floors_are_cleared():
    findings = analyse_archive(adopted_archive(MIN_WORKSHOPS_FOR_RATE, per_workshop=2, adopted=1),
                               workshops_in_archive=200)
    assert findings.overall.workshops == MIN_WORKSHOPS_FOR_RATE
    assert findings.overall.observations == MIN_WORKSHOPS_FOR_RATE * 2
    assert findings.overall.rate == pytest.approx(0.5)
    assert "50%" in findings.overall.message


def test_a_withheld_rate_is_None_and_never_zero():
    group = summarise_adoption("CRAFT", "Ikat", observations(follow_up("ADOPTED_IN_PRODUCTION")))
    assert group.rate is None
    assert group.reported is False
    assert group.adopted == 1



def test_not_known_is_excluded_from_the_rate_and_reported_beside_it():
    archive = adopted_archive(MIN_WORKSHOPS_FOR_RATE, per_workshop=2, adopted=2)
    archive.append(workshop(99, statuses=("UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN")))
    findings = analyse_archive(archive, workshops_in_archive=200)
    assert findings.overall.observations == MIN_WORKSHOPS_FOR_RATE * 2
    assert findings.overall.unknown == 4
    assert findings.overall.rate == pytest.approx(1.0)
    assert "not known" in findings.overall.message


def test_trial_counts_against_adoption_and_never_for_it():
    archive = [workshop(i, statuses=("ADOPTED_ON_ORDER", "TRIAL")) for i in range(5)]
    findings = analyse_archive(archive, workshops_in_archive=200)
    assert findings.overall.trial == 5
    assert findings.overall.adopted == 5
    assert findings.overall.observations == 10
    assert findings.overall.rate == pytest.approx(0.5)


def test_the_adopted_set_matches_the_registry_enum():
    from app.services.stage_schema import ENUMS

    tokens = set(ENUMS["ADOPTION_STATUS"])
    assert tokens >= ADOPTED_STATUSES
    assert tokens - ADOPTED_STATUSES == {"TRIAL", "NOT_ADOPTED", "UNKNOWN"}, (
        "ADOPTION_STATUS has gained a token that workshop_analytics does not classify, so it is "
        "silently falling out of every adoption rate"
    )


def test_the_interval_ladder_matches_the_registry_enum():
    from app.services.stage_schema import ENUMS

    tokens = set(ENUMS["FOLLOWUP_INTERVAL"])

    assert set(_INTERVAL_RANK) <= tokens, (
        "_INTERVAL_RANK ranks an interval the registry does not offer, so no stored row can ever "
        "carry it and the rank is dead code hiding a typo"
    )
    assert tokens - set(_INTERVAL_RANK) == {"AD_HOC"}, (
        "FOLLOWUP_INTERVAL has gained an interval that _INTERVAL_RANK does not rank. An unranked "
        "token scores 0, which is BELOW M3 — so the latest visit to a workshop is set aside as "
        "though it were the earliest, and every adoption figure is computed from stale rows"
    )
    assert set(SURVIVAL_INTERVALS) == set(_INTERVAL_RANK), (
        "the ranked intervals and the reported cross-sections have drifted apart, so an interval "
        "is either ranked and never shown, or shown and never ranked"
    )



def test_one_workshop_dominating_a_group_puts_the_caution_before_the_number():
    archive = [workshop(0, statuses=tuple(["ADOPTED_IN_PRODUCTION"] * 30))]
    archive += [workshop(i, statuses=("ADOPTED_IN_PRODUCTION",)) for i in range(1, 5)]
    findings = analyse_archive(archive, workshops_in_archive=200)

    assert findings.overall.largest_workshop_share > CONCENTRATION_LIMIT
    assert findings.overall.rate is not None
    message = findings.overall.message
    assert message.index("single workshop") < message.index("were adopted"), (
        "the adoption rate is printed before the caution that qualifies it"
    )
    assert message.startswith("the archive: 88% of these outcomes come from a single workshop")


def test_one_cluster_dominating_the_archive_is_named_as_a_caution():
    archive = [workshop(i, cluster="Bargarh", statuses=("ADOPTED_IN_PRODUCTION", "TRIAL"))
               for i in range(9)]
    archive += [workshop(90, cluster="Pipili", statuses=("ADOPTED_IN_PRODUCTION",))]
    findings = analyse_archive(archive, workshops_in_archive=200)
    assert any("one cluster (Bargarh)" in caution for caution in findings.cautions)


def test_a_single_cluster_archive_raises_no_concentration_caution():
    findings = analyse_archive(
        adopted_archive(MIN_WORKSHOPS_FOR_RATE + 3, per_workshop=2, adopted=1),
        workshops_in_archive=200,
    )
    assert not any("one cluster" in caution for caution in findings.cautions)



def test_the_coverage_caution_leads_and_names_both_numbers():
    findings = analyse_archive(adopted_archive(6, per_workshop=2, adopted=1),
                               workshops_in_archive=5607)
    assert findings.cautions[0].startswith("6 of 5,607 workshops")
    assert "not the scheme" in findings.cautions[0]


def test_an_empty_archive_says_so_rather_than_dividing_by_zero():
    findings = analyse_archive([], workshops_in_archive=0)
    assert findings.overall.rate is None
    assert findings.cautions[0] == "The archive holds no live workshops."
    assert archive_findings_payload(findings)["coverage"]["workshopsWithFollowUp"] == 0


def test_a_truncated_read_says_the_archive_was_not_read_whole():
    findings = analyse_archive(adopted_archive(6, per_workshop=2, adopted=1),
                               workshops_in_archive=5607, truncated=True)
    assert findings.truncated is True
    assert any("capped sample" in caution for caution in findings.cautions)



def test_grouping_splits_by_craft_cluster_and_state_independently():
    archive = [workshop(i, craft="Ikat", cluster="Bargarh", state="Odisha",
                        statuses=("ADOPTED_IN_PRODUCTION", "NOT_ADOPTED")) for i in range(5)]
    archive += [workshop(10 + i, craft="Pattachitra", cluster="Raghurajpur", state="Odisha",
                         statuses=("ADOPTED_IN_PRODUCTION", "ADOPTED_ON_ORDER")) for i in range(5)]
    findings = analyse_archive(archive, workshops_in_archive=200)

    crafts = {g.label: g for g in findings.by_craft}
    assert crafts["Ikat"].rate == pytest.approx(0.5)
    assert crafts["Pattachitra"].rate == pytest.approx(1.0)
    states = {g.label: g for g in findings.by_state}
    assert states["Odisha"].observations == 20
    assert states["Odisha"].rate == pytest.approx(0.75)


def test_a_workshop_with_no_cluster_is_excluded_from_the_cluster_comparison_and_counted():
    archive = adopted_archive(5, per_workshop=2, adopted=1)
    archive.append(workshop(90, cluster="", statuses=("ADOPTED_IN_PRODUCTION", "NOT_ADOPTED")))
    findings = analyse_archive(archive, workshops_in_archive=200)

    assert [g.label for g in findings.by_cluster] == ["Bargarh"]
    assert findings.missing_dimension["cluster"] == 2
    assert any("no cluster on the cover sheet" in note for note in findings.notes)



def test_without_a_product_reference_no_survival_curve_is_drawn():
    rows = observations(
        follow_up("ADOPTED_IN_PRODUCTION", "M3"),
        follow_up("ADOPTED_IN_PRODUCTION", "M12"),
    )
    survival = analyse_survival(rows)
    assert survival.cohort_possible is False
    assert survival.tracked_to_12 == 0
    assert "cross-sections" in survival.message
    assert "survival curve" in survival.message


def test_the_cross_sections_are_computed_even_when_the_cohort_cannot_be():
    rows = observations(
        follow_up("TRIAL", "M3"), follow_up("ADOPTED_IN_PRODUCTION", "M3"),
        follow_up("ADOPTED_IN_PRODUCTION", "M12"), follow_up("ADOPTED_ON_ORDER", "M12"),
    )
    sections = {s.interval: s for s in analyse_survival(rows).cross_sections}
    assert sections["M3"].observations == 2
    assert sections["M3"].adopted == 1
    assert sections["M12"].adopted == 2
    assert sections["M6"].observations == 0
    assert "no workshop" in sections["M6"].message
    assert all(s.rate is None for s in sections.values())


def test_a_design_not_revisited_is_not_counted_as_abandoned():
    rows = observations(follow_up("ADOPTED_IN_PRODUCTION", "M3", productRef="PT-01"))
    survival = analyse_survival(rows)
    assert survival.cohort_possible is True
    assert survival.tracked_to_12 == 0
    assert survival.unobserved_after_3 == 1
    assert "not evidence of abandonment" in survival.message


def test_a_design_tracked_from_three_to_twelve_months_is_followed_by_its_product_reference():
    rows = observations(
        follow_up("ADOPTED_IN_PRODUCTION", "M3", productRef="PT-01"),
        follow_up("NOT_ADOPTED", "M12", productRef="PT-01"),
        follow_up("ADOPTED_IN_PRODUCTION", "M3", productRef="PT-02"),
        follow_up("ADOPTED_IN_PRODUCTION", "M12", productRef="PT-02"),
    )
    survival = analyse_survival(rows)
    assert survival.tracked_to_12 == 2
    assert survival.still_adopted_at_12 == 1


def test_a_partly_referenced_archive_says_how_many_rows_could_not_join_a_cohort():
    rows = observations(
        follow_up("ADOPTED_IN_PRODUCTION", "M3", productRef="PT-01"),
        follow_up("ADOPTED_IN_PRODUCTION", "M12", productRef="PT-01"),
        follow_up("ADOPTED_IN_PRODUCTION", "M3"),
        follow_up("NOT_ADOPTED", "M12"),
        follow_up("TRIAL", "M6"),
    )
    survival = analyse_survival(rows)
    assert survival.cohort_possible is True
    assert survival.tracked_to_12 == 1
    assert survival.rows_without_product == 3
    assert "3 follow-up records carry no product reference" in survival.message


def test_the_same_product_code_in_two_workshops_is_two_designs():
    rows = observations(follow_up("ADOPTED_IN_PRODUCTION", "M3", productRef="PT-01"),
                        workshop_id="w1")
    rows += observations(follow_up("NOT_ADOPTED", "M12", productRef="PT-01"), workshop_id="w2")
    survival = analyse_survival(rows)
    assert survival.tracked_to_12 == 0
    assert survival.unobserved_after_3 == 1



def sheet(cost: str, price: str) -> dict[str, Any]:
    return {"materialCost": cost, "labourCost": "0.00", "expectedPrice": price}


def test_cost_ratios_are_withheld_below_the_sheet_floor():
    groups, _ = analyse_cost_ratios([
        WorkshopRows(workshop_id="w1", cluster="Bargarh",
                     cost_sheets=tuple(sheet("100.00", "200.00") for _ in range(2))),
    ])
    assert len(groups) == 1
    assert groups[0].ratio is None
    assert groups[0].sheets == 2
    assert f"Fewer than {MIN_SHEETS_FOR_RATIO} sheets is a list, not a distribution" in \
        groups[0].message


def test_a_cluster_with_enough_sheets_gets_a_distribution_of_the_price_to_cost_multiple():
    prices = ["150.00", "200.00", "250.00", "300.00", "350.00"]
    groups, _ = analyse_cost_ratios([
        WorkshopRows(workshop_id="w1", cluster="Bargarh",
                     cost_sheets=tuple(sheet("100.00", p) for p in prices)),
    ])
    assert groups[0].ratio is not None
    assert groups[0].ratio.median == pytest.approx(2.5)
    assert groups[0].ratio.minimum == pytest.approx(1.5)
    assert groups[0].ratio.maximum == pytest.approx(3.5)
    assert "median of 2.50×" in groups[0].message


def test_a_product_priced_below_its_cost_is_counted_and_said_out_loud():
    groups, _ = analyse_cost_ratios([
        WorkshopRows(workshop_id="w1", cluster="Bargarh",
                     cost_sheets=(sheet("500.00", "300.00"),)),
    ])
    assert groups[0].below_cost == 1
    assert "1 sheet is priced BELOW what it costs to make" in groups[0].message


def test_an_uncostable_sheet_is_counted_rather_than_treated_as_a_ratio_of_zero():
    groups, _ = analyse_cost_ratios([
        WorkshopRows(workshop_id="w1", cluster="Bargarh",
                     cost_sheets=({"expectedPrice": "500.00"}, sheet("100.00", "200.00"))),
    ])
    assert groups[0].sheets == 1
    assert groups[0].uncostable == 1
    assert "could not be costed at all" in groups[0].message


def test_a_cluster_whose_sheets_are_all_uncostable_still_appears_instead_of_vanishing():
    priced = ["150.00", "200.00", "250.00", "300.00", "350.00"]
    groups, unattributed = analyse_cost_ratios([
        WorkshopRows(workshop_id="w1", cluster="Sambalpur",
                     cost_sheets=({"materialCost": "100.00", "labourCost": "20.00"},)),
        WorkshopRows(workshop_id="w2", cluster="Sambalpur",
                     cost_sheets=({"materialCost": "80.00", "labourCost": "0.00"},
                                  {"expectedPrice": "500.00"})),
        WorkshopRows(workshop_id="w3", cluster="Bargarh",
                     cost_sheets=tuple(sheet("100.00", p) for p in priced)),
    ])
    assert unattributed == 0
    by_cluster = {group.cluster: group for group in groups}
    assert set(by_cluster) == {"Sambalpur", "Bargarh"}, \
        "the all-uncostable cluster was dropped, and reads as a cluster that costed nothing"

    dropped = by_cluster["Sambalpur"]
    assert dropped.sheets == 0
    assert dropped.uncostable == 3
    assert dropped.workshops == 2
    assert dropped.ratio is None
    assert dropped.below_cost == 0
    assert "not one of them can be costed" in dropped.message

    assert groups[0].cluster == "Bargarh"
    assert groups[0].ratio is not None
    assert groups[0].sheets == 5


def test_cost_sheets_on_a_workshop_with_no_cluster_are_counted_not_pooled_into_an_empty_group():
    groups, unattributed = analyse_cost_ratios([
        WorkshopRows(workshop_id="w1", cluster="", cost_sheets=(sheet("100.00", "200.00"),)),
    ])
    assert groups == []
    assert unattributed == 1



def test_a_theme_in_one_workshop_has_not_recurred():
    rows = tuple({"title": "Table linen for urban homes"} for _ in range(4))
    categories, terms, naming = analyse_opportunities(
        [WorkshopRows(workshop_id="w1", opportunities=rows)]
    )
    assert categories == []
    assert terms == []
    assert naming == 1


def test_a_term_appearing_in_two_workshops_recurs_and_carries_its_examples():
    archive = [
        WorkshopRows(workshop_id="w1", opportunities=({"title": "Table linen for hotels"},)),
        WorkshopRows(workshop_id="w2", opportunities=({"title": "Linen stoles for export"},)),
    ]
    _, terms, naming = analyse_opportunities(archive)
    by_term = {t.term: t for t in terms}
    assert by_term["linen"].workshops == MIN_WORKSHOPS_FOR_THEME
    assert set(by_term["linen"].examples) == {"Table linen for hotels", "Linen stoles for export"}
    assert naming == 2


def test_an_odia_opportunity_is_tokenised_rather_than_shattered_into_fragments():
    archive = [
        WorkshopRows(workshop_id="w1", opportunities=({"title": "ସମ୍ବଲପୁରୀ ବନ୍ଧ ଶାଢ଼ୀ"},)),
        WorkshopRows(workshop_id="w2", opportunities=({"title": "ସମ୍ବଲପୁରୀ ଗାମୁଛା"},)),
    ]
    _, terms, _ = analyse_opportunities(archive)
    assert "ସମ୍ବଲପୁରୀ" in {t.term for t in terms}


def test_the_closed_category_list_is_reported_separately_from_the_free_text():
    archive = [
        WorkshopRows(workshop_id=f"w{i}",
                     opportunities=({"title": "Anything", "targetCategory": "HOME_TEXTILE"},))
        for i in range(3)
    ]
    categories, _, _ = analyse_opportunities(archive)
    assert [(c.category, c.workshops, c.occurrences) for c in categories] == [("HOME_TEXTILE", 3, 3)]



def test_revenue_is_reported_at_the_latest_visit_only_and_says_so():
    rows = (
        follow_up("ADOPTED_IN_PRODUCTION", "M3", revenue="62700.00", unitsSold=38),
        follow_up("ADOPTED_IN_PRODUCTION", "M12", revenue="98000.00", unitsSold=54),
    )
    findings = analyse_archive([WorkshopRows(workshop_id="w1", follow_ups=rows)],
                               workshops_in_archive=1)
    assert findings.reach.revenue == pytest.approx(98000.0)
    assert findings.reach.rows_with_revenue == 1
    assert "not a total over every visit" in findings.reach.message


def test_a_total_always_carries_the_rows_it_came_from():
    rows = (follow_up("ADOPTED_IN_PRODUCTION", "M12", revenue="1000.00"),
            follow_up("ADOPTED_IN_PRODUCTION", "M12"),
            follow_up("NOT_ADOPTED", "M12"))
    findings = analyse_archive([WorkshopRows(workshop_id="w1", follow_ups=rows)],
                               workshops_in_archive=1)
    assert findings.reach.rows == 3
    assert findings.reach.rows_with_revenue == 1
    assert "1 of 3 standing follow-up records" in findings.reach.message



def test_the_questions_that_cannot_be_answered_are_named_in_the_payload():
    payload = archive_findings_payload(analyse_archive([], workshops_in_archive=0))
    questions = [entry["question"] for entry in payload["notComputed"]]
    assert "Prototypes produced against prototypes planned" in questions
    assert all(entry["reason"] for entry in payload["notComputed"])


_BUILD_TIME_COMMENTARY = (
    "reviewer", "annotator", "source document", "phase 2", "phase 3", "phase 4",
    "plug in", "plug-in", "for now", "we may consider", "deferred to", "at the request of",
)


def test_the_refusals_and_cautions_never_quote_whoever_asked_for_the_feature():
    payload = archive_findings_payload(
        analyse_archive(adopted_archive(6, per_workshop=2, adopted=1), workshops_in_archive=5607)
    )
    prose = (
        [("caution", text) for text in payload["cautions"]]
        + [("note", text) for text in payload["notes"]]
        + [(f"notComputed[{entry['question']}]", entry["reason"])
           for entry in payload["notComputed"]]
    )
    assert prose, "nothing was checked — the payload carried no cautions, notes or refusals"
    offenders = [
        (where, term, text)
        for where, text in prose
        for term in _BUILD_TIME_COMMENTARY
        if term in text.casefold()
    ]
    assert not offenders, (
        "build-time commentary is reaching the admin analytics page:\n"
        + "\n".join(f"  {where}: …{term}… in {text!r}" for where, term, text in offenders)
    )

    markup = [
        (where, text)
        for where, text in prose
        if "`" in text or re.search(r"(?<![A-Za-z0-9`])[a-z]{3,}_[a-z_]{3,}(?![A-Za-z0-9])", text)
    ]
    assert not markup, (
        "a backtick or a source identifier is reaching the admin analytics page — name the label "
        "the reader sees instead:\n" + "\n".join(f"  {where}: {text!r}" for where, text in markup)
    )


def test_no_planned_prototype_count_exists_in_the_registry():
    import app.services.stage_definitions  # noqa: F401  - installs the registry
    from app.services.stage_schema import STAGES

    numeric_targets = [
        f"{stage.key}.{entity.key}.{spec.key}"
        for stage in STAGES
        for entity in stage.entities
        for spec in entity.fields
        if str(getattr(spec.type, "value", spec.type)) in {"INT", "DECIMAL"}
        and any(word in spec.label.lower() for word in ("planned", "target"))
    ]
    assert numeric_targets == [], (
        f"a numeric target field now exists ({numeric_targets}); "
        f"workshop_analytics still refuses to compute prototypes-versus-planned"
    )


def test_the_payload_puts_the_cautions_above_every_figure():
    payload = archive_findings_payload(
        analyse_archive(adopted_archive(6, per_workshop=2, adopted=1), workshops_in_archive=5607)
    )
    assert list(payload)[:4] == ["cautions", "notes", "notComputed", "truncated"]
    assert payload["overall"]["rate"] == pytest.approx(0.5)
    assert payload["byCluster"][0]["label"] == "Bargarh"


ENDPOINT = "/api/analytics/design-workshops"


class _DatabaseTouched(Exception):
    pass


class _Tripwire:
    def __getattr__(self, name: str) -> Any:
        raise _DatabaseTouched(name)


_APP = FastAPI()
_APP.include_router(api_router)
_CURRENT: dict[str, Any] = {"user": None}
_APP.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]


def _call_as(role: str) -> tuple[bool, int | None, str]:
    _CURRENT["user"] = SimpleNamespace(id="u1", email="u1@example.test", name="T", role=role)

    async def run() -> tuple[bool, int | None, str]:
        transport = httpx.ASGITransport(app=_APP)
        async with httpx.AsyncClient(transport=transport, base_url="http://gate.test") as client:
            response = await client.get(ENDPOINT)
        body = response.json() if response.content else {}
        detail = body.get("detail", "") if isinstance(body, dict) else ""
        return False, response.status_code, str(detail)

    try:
        return asyncio.run(run())
    except _DatabaseTouched:
        return True, None, ""


@pytest.fixture(autouse=True)
def _tripwire_db(monkeypatch: pytest.MonkeyPatch):
    tripwire = _Tripwire()
    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", tripwire)
    for module in list(sys.modules.values()):
        if getattr(module, "__name__", "").startswith("app.") and getattr(module, "db", None) is real_db:
            monkeypatch.setattr(module, "db", tripwire, raising=False)
    yield



class _FakeDelegate:
    def __init__(self, rows: list[Any], count: int = 0) -> None:
        self._rows = rows
        self._count = count
        self.calls: list[dict[str, Any]] = []

    async def count(self, **kwargs: Any) -> int:
        self.calls.append(kwargs)
        return self._count

    async def find_many(self, **kwargs: Any) -> list[Any]:
        self.calls.append(kwargs)
        where = kwargs.get("where") or {}
        rows = self._rows
        if "deletedAt" in where:
            rows = [r for r in rows if getattr(r, "deletedAt", None) is where["deletedAt"]]
        wanted = (where.get("id") or {}).get("in") if isinstance(where.get("id"), dict) else None
        if wanted is not None:
            rows = [r for r in rows if r.id in wanted]
        if "entityKey" in where:
            rows = [r for r in rows if r.entityKey in where["entityKey"]["in"]]
        return list(rows)


class _FakeDb:
    def __init__(self, workshops: _FakeDelegate, entries: _FakeDelegate) -> None:
        self.designworkshop = workshops
        self.dwstageentry = entries


def _entry(entry_id: str, workshop_id: str, entity_key: str, data: dict[str, Any]) -> Any:
    return SimpleNamespace(id=entry_id, designWorkshopId=workshop_id, entityKey=entity_key,
                           data=data, deletedAt=None)


def _header(workshop_id: str, *, deleted: bool) -> Any:
    return SimpleNamespace(id=workshop_id, title="W", craftName="Ikat", clusterName="Bargarh",
                           state="Odisha", deletedAt=object() if deleted else None)


def test_a_deleted_workshops_rows_never_reach_the_comparison(monkeypatch: pytest.MonkeyPatch):
    from app.api.routes import analytics as module

    entries = _FakeDelegate([
        _entry("e1", "live", "costSheet",
               {"materialCost": "100.00", "labourCost": "0.00", "expectedPrice": "200.00"}),
        _entry("e2", "gone", "costSheet",
               {"materialCost": "100.00", "labourCost": "0.00", "expectedPrice": "900.00"}),
        _entry("e3", "gone", "followUp", {"adoptionStatus": "ADOPTED_IN_PRODUCTION",
                                          "interval": "M12"}),
    ])
    workshops = _FakeDelegate([_header("live", deleted=False), _header("gone", deleted=True)],
                              count=1)
    monkeypatch.setattr(module, "db", _FakeDb(workshops, entries))

    loaded, archive_size, truncated = asyncio.run(module._load())

    assert [w.workshop_id for w in loaded] == ["live"]
    assert archive_size == 1
    assert truncated is False
    groups, _ = analyse_cost_ratios(loaded)
    assert sum(g.sheets for g in groups) == 1
    assert workshops.calls[0] == {"where": {"deletedAt": None}}


def test_the_load_is_three_queries_and_never_one_per_workshop(monkeypatch: pytest.MonkeyPatch):
    from app.api.routes import analytics as module

    rows = [_entry(f"e{i}", f"w{i}", "followUp",
                   {"adoptionStatus": "ADOPTED_IN_PRODUCTION", "interval": "M12"})
            for i in range(20)]
    entries = _FakeDelegate(rows)
    workshops = _FakeDelegate([_header(f"w{i}", deleted=False) for i in range(20)], count=20)
    monkeypatch.setattr(module, "db", _FakeDb(workshops, entries))

    loaded, _, _ = asyncio.run(module._load())

    assert len(loaded) == 20
    assert len(workshops.calls) == 2, "one count and one header fetch, whatever the archive size"
    assert len(entries.calls) == 1, "one stage-row read, whatever the archive size"


@pytest.mark.parametrize("role", ["DESIGNER", "RESEARCHER", "PROFESSOR", "FIELD_CONTRIBUTOR",
                                  "CROWDSOURCE_VOLUNTEER", "INSPECTOR", "ASSISTANT_DIRECTOR"])
def test_everyone_below_admin_is_refused_by_the_ROUTE_and_not_only_by_the_menu(role):
    reached, status_code, detail = _call_as(role)
    assert reached is False, f"{role} reached the handler body"
    assert status_code == 403, f"{role} got {status_code}: {detail}"
    from app.api.routes.analytics import ANALYTICS_REFUSAL

    assert detail == ANALYTICS_REFUSAL


@pytest.mark.parametrize("role", ["ADMIN", "MASTER_ADMIN", "MINISTRY_ADMIN", "REGIONAL_DIRECTOR"])
def test_admins_get_past_the_gate(role):
    reached, status_code, detail = _call_as(role)
    assert reached is True, f"{role} was refused with {status_code}: {detail}"


def test_the_route_carries_the_admin_dependency_in_its_source():
    import inspect

    from app.api.routes import analytics as module

    for name, handler in vars(module).items():
        if not name.startswith("_") and callable(handler) and getattr(handler, "__module__", "") \
                == module.__name__ and name.endswith("analytics"):
            source = inspect.getsource(handler)
            assert "Depends(require_analytics_reader)" in source, (
                f"{name} does not enforce admin access, so the browser is the only thing stopping "
                f"a designer — and the API and the Android client ignore the browser"
            )


def test_the_web_mirrors_the_server_gate():
    from pathlib import Path

    web = Path(__file__).resolve().parents[2] / "frontend/lib/permissions.ts"
    if not web.is_file():
        pytest.skip("the frontend is not present in this checkout")
    text = web.read_text(encoding="utf-8")
    assert '"/admin/analytics"' in text or "'/admin/analytics'" in text, (
        "the analytics page has no ROUTE_GUARDS row, so the URL is open in the browser to anyone "
        "who has been sent the link"
    )
    guard = text.split("/admin/analytics")[1][:400]
    assert "isAdmin" in guard, "the web guard does not mirror require_admin"


def test_a_regional_director_reads_only_their_posted_workshops(monkeypatch: pytest.MonkeyPatch):
    from app.api.routes import analytics as module
    from app.services.ministry_dashboard import scope_clause

    director = SimpleNamespace(id="rd1", role="REGIONAL_DIRECTOR")
    assert module.analytics_scope(director) == scope_clause(director)
    assert module.analytics_scope(SimpleNamespace(id="ma", role="MINISTRY_ADMIN")) is None

    entries = _FakeDelegate([])
    workshops = _FakeDelegate([], count=0)
    monkeypatch.setattr(module, "db", _FakeDb(workshops, entries))
    asyncio.run(module._load(scope_clause(director)))
    assert workshops.calls[0]["where"]["AND"][1] == scope_clause(director)
    assert entries.calls[0]["where"]["designWorkshop"] == {"is": scope_clause(director)}


def test_the_web_carries_the_same_reader_set():
    from pathlib import Path

    from app.api.routes.analytics import ANALYTICS_READ_ROLES

    web = Path(__file__).resolve().parents[2] / "frontend/lib/permissions.ts"
    if not web.is_file():
        pytest.skip("the frontend is not present in this checkout")
    text = web.read_text(encoding="utf-8")
    block = text.split("export const ANALYTICS_READ_ROLES")[1].split(";")[0]
    assert set(re.findall(r'"([A-Z_]+)"', block)) == set(ANALYTICS_READ_ROLES)
