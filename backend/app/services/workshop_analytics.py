from __future__ import annotations

import math
from collections import Counter
from dataclasses import dataclass
from typing import Any

from app.services.cost_integrity import compute_margin

from app.services.market_analysis import (
    MIN_SAMPLE_FOR_QUANTILES,
    Distribution,
    _tokens,
    as_number,
    describe,
)

from app.services.text_format import by_count, count_noun

MIN_WORKSHOPS_FOR_RATE = 5

MIN_OBSERVATIONS_FOR_RATE = 8

MIN_SHEETS_FOR_RATIO = MIN_SAMPLE_FOR_QUANTILES

CONCENTRATION_LIMIT = 0.6

MIN_WORKSHOPS_FOR_THEME = 2

_THEME_EXAMPLES = 3

ADOPTED_STATUSES = frozenset({"ADOPTED_IN_PRODUCTION", "ADOPTED_ON_ORDER"})

TRIAL_STATUS = "TRIAL"

NOT_ADOPTED_STATUS = "NOT_ADOPTED"

UNKNOWN_STATUS = "UNKNOWN"

_INTERVAL_RANK = {"M3": 1, "M6": 2, "M12": 3}

SURVIVAL_INTERVALS = ("M3", "M6", "M12")

_INTERVAL_LABELS = {"M3": "3 months", "M6": "6 months", "M12": "12 months"}



@dataclass(frozen=True, slots=True)
class WorkshopRows:
    workshop_id: str
    title: str = ""
    craft: str = ""
    cluster: str = ""
    state: str = ""
    follow_ups: tuple[dict[str, Any], ...] = ()
    cost_sheets: tuple[dict[str, Any], ...] = ()
    opportunities: tuple[dict[str, Any], ...] = ()



@dataclass(frozen=True, slots=True)
class Observation:
    workshop_id: str
    interval: str
    status: str
    product: str
    revenue: float | None
    units_sold: float | None
    orders: float | None

    @property
    def is_adopted(self) -> bool:
        return self.status in ADOPTED_STATUSES

    @property
    def is_decided(self) -> bool:
        return self.status in ADOPTED_STATUSES or self.status in {TRIAL_STATUS, NOT_ADOPTED_STATUS}


def read_observation(workshop_id: str, row: dict[str, Any]) -> Observation | None:
    status = str(row.get("adoptionStatus") or "").strip()
    if not status:
        return None
    return Observation(
        workshop_id=workshop_id,
        interval=str(row.get("interval") or "").strip(),
        status=status,
        product=str(row.get("productRef") or "").strip(),
        revenue=as_number(row.get("revenue")),
        units_sold=as_number(row.get("unitsSold")),
        orders=as_number(row.get("ordersReceived")),
    )


def latest_interval_rows(observations: list[Observation]) -> tuple[list[Observation], int]:
    if not observations:
        return [], 0
    top = max(_INTERVAL_RANK.get(o.interval, 0) for o in observations)
    kept = [o for o in observations if _INTERVAL_RANK.get(o.interval, 0) == top]
    return kept, len(observations) - len(kept)



@dataclass(frozen=True, slots=True)
class AdoptionGroup:
    dimension: str
    label: str
    workshops: int
    observations: int
    adopted: int
    trial: int
    not_adopted: int
    unknown: int
    rate: float | None
    largest_workshop_share: float
    message: str

    @property
    def reported(self) -> bool:
        return self.rate is not None


def _percent(value: float) -> str:
    return f"{value * 100:.0f}%"


def _adoption_message(
    label: str,
    workshops: int,
    *,
    observations: int,
    adopted: int,
    unknown: int,
    rate: float | None,
    concentration: float,
) -> str:
    where = label or "the archive"
    if rate is None:
        if workshops < MIN_WORKSHOPS_FOR_RATE:
            return (
                f"{where}: {adopted} of {count_noun(observations, 'recorded outcome')} adopted, "
                f"across {count_noun(workshops, 'workshop')}. No adoption rate is given — a rate needs at least "
                f"{MIN_WORKSHOPS_FOR_RATE} workshops behind it, or it describes one designer's "
                f"fortnight rather than the scheme."
            )
        return (
            f"{where}: {adopted} of {count_noun(observations, 'recorded outcome')} adopted, "
            f"across {workshops} workshops. No adoption rate is given — "
            f"{count_noun(observations, 'outcome')} is "
            f"below the {MIN_OBSERVATIONS_FOR_RATE} this reports a proportion from."
        )

    tail = (
        f" {unknown} further {by_count(unknown, 'outcome was', 'outcomes were')} recorded as not "
        f"known and {by_count(unknown, 'is', 'are')} excluded from the rate."
        if unknown
        else ""
    )
    if concentration > CONCENTRATION_LIMIT:
        return (
            f"{where}: {_percent(concentration)} of these outcomes come from a single workshop, "
            f"so this describes that workshop more than it describes {where} — with that said, "
            f"{adopted} of {observations} were adopted ({_percent(rate)}), across "
            f"{workshops} workshops.{tail}"
        )
    return (
        f"{where}: {adopted} of {observations} recorded outcomes adopted ({_percent(rate)}), "
        f"across {workshops} workshops.{tail}"
    )


def summarise_adoption(
    dimension: str, label: str, observations: list[Observation]
) -> AdoptionGroup:
    workshops = {o.workshop_id for o in observations}
    decided = [o for o in observations if o.is_decided]
    adopted = sum(1 for o in decided if o.is_adopted)
    trial = sum(1 for o in decided if o.status == TRIAL_STATUS)
    not_adopted = sum(1 for o in decided if o.status == NOT_ADOPTED_STATUS)
    unknown = sum(1 for o in observations if o.status == UNKNOWN_STATUS)

    per_workshop = Counter(o.workshop_id for o in decided)
    concentration = (per_workshop.most_common(1)[0][1] / len(decided)) if decided else 0.0

    enough = len(workshops) >= MIN_WORKSHOPS_FOR_RATE and len(decided) >= MIN_OBSERVATIONS_FOR_RATE
    rate = (adopted / len(decided)) if (enough and decided) else None

    return AdoptionGroup(
        dimension=dimension,
        label=label,
        workshops=len(workshops),
        observations=len(decided),
        adopted=adopted,
        trial=trial,
        not_adopted=not_adopted,
        unknown=unknown,
        rate=rate,
        largest_workshop_share=concentration,
        message=_adoption_message(
            label,
            len(workshops),
            observations=len(decided),
            adopted=adopted,
            unknown=unknown,
            rate=rate,
            concentration=concentration,
        ),
    )



@dataclass(frozen=True, slots=True)
class IntervalCrossSection:
    interval: str
    label: str
    workshops: int
    observations: int
    adopted: int
    rate: float | None
    message: str


@dataclass(frozen=True, slots=True)
class Survival:
    cross_sections: tuple[IntervalCrossSection, ...]
    cohort_possible: bool
    tracked_to_12: int
    still_adopted_at_12: int
    unobserved_after_3: int
    rows_without_product: int
    message: str


def _cross_section(interval: str, observations: list[Observation]) -> IntervalCrossSection:
    at_interval = [o for o in observations if o.interval == interval]
    decided = [o for o in at_interval if o.is_decided]
    workshops = {o.workshop_id for o in at_interval}
    adopted = sum(1 for o in decided if o.is_adopted)
    label = _INTERVAL_LABELS[interval]

    enough = len(workshops) >= MIN_WORKSHOPS_FOR_RATE and len(decided) >= MIN_OBSERVATIONS_FOR_RATE
    rate = (adopted / len(decided)) if (enough and decided) else None

    if not at_interval:
        message = f"At {label}: no workshop in the archive has recorded a visit at this interval."
    elif rate is None:
        message = (
            f"At {label}: {adopted} of {count_noun(len(decided), 'recorded outcome')} adopted, "
            f"across {count_noun(len(workshops), 'workshop')} — too few to state as a rate."
        )
    else:
        message = (
            f"At {label}: {adopted} of {len(decided)} recorded outcomes adopted "
            f"({_percent(rate)}), across {len(workshops)} workshops."
        )

    return IntervalCrossSection(
        interval, label, len(workshops), len(decided), adopted, rate, message
    )


def analyse_survival(observations: list[Observation]) -> Survival:
    sections = tuple(_cross_section(i, observations) for i in SURVIVAL_INTERVALS)

    tracked: dict[tuple[str, str], dict[str, Observation]] = {}
    unreferenced = 0
    for o in observations:
        if o.interval not in _INTERVAL_RANK:
            continue
        if not o.product:
            unreferenced += 1
            continue
        tracked.setdefault((o.workshop_id, o.product), {})[o.interval] = o

    cohort_possible = bool(tracked)
    at_3 = [seen for seen in tracked.values() if "M3" in seen]
    to_12 = [seen for seen in at_3 if "M12" in seen]
    still = sum(1 for seen in to_12 if seen["M12"].is_adopted)
    lost = sum(1 for seen in at_3 if "M6" not in seen and "M12" not in seen)

    excluded = (
        f" {count_noun(unreferenced, 'follow-up record')} "
        f"{by_count(unreferenced, 'carries', 'carry')} no product reference and cannot join a "
        f"cohort at all."
        if unreferenced
        else ""
    )

    if not cohort_possible:
        message = (
            "No follow-up record in the archive carries a product reference, so no design can be "
            "matched from one visit to the next. The figures above are cross-sections — how things "
            "stood at each interval — and not a survival curve; drawing one would mean joining "
            "observations that may belong to different designs."
        )
    elif len(to_12) < MIN_OBSERVATIONS_FOR_RATE:
        message = (
            f"{count_noun(len(to_12), 'design')} {by_count(len(to_12), 'has', 'have')} been "
            f"observed at both 3 and 12 months — too few to state "
            f"a survival rate. {still} of them were still adopted at 12 months. A further "
            f"{count_noun(lost, 'design')} seen at 3 months {by_count(lost, 'has', 'have')} not "
            f"been revisited, which is an absence of "
            f"evidence and not evidence of abandonment.{excluded}"
        )
    else:
        message = (
            f"Of {len(to_12)} designs observed at both 3 and 12 months, {still} were still adopted "
            f"at 12 months ({_percent(still / len(to_12))}). A further "
            f"{count_noun(lost, 'design')} seen at 3 months {by_count(lost, 'has', 'have')} not "
            f"been revisited; {by_count(lost, 'it is', 'they are')} not counted here in either "
            f"direction, "
            f"because nobody went back to look.{excluded}"
        )

    return Survival(sections, cohort_possible, len(to_12), still, lost, unreferenced, message)



@dataclass(frozen=True, slots=True)
class CostRatioGroup:
    cluster: str
    workshops: int
    sheets: int
    uncostable: int
    below_cost: int
    ratio: Distribution | None
    message: str


def analyse_cost_ratios(workshops: list[WorkshopRows]) -> tuple[list[CostRatioGroup], int]:
    by_cluster: dict[str, list[tuple[str, float]]] = {}
    unattributed = 0
    uncostable: Counter[str] = Counter()
    uncostable_workshops: dict[str, set[str]] = {}
    below: Counter[str] = Counter()

    for workshop in workshops:
        cluster = workshop.cluster.strip()
        for index, sheet in enumerate(workshop.cost_sheets):
            if not cluster:
                unattributed += 1
                continue
            margin = compute_margin(f"sheet {index + 1}", sheet)
            if margin.verdict == "NOT_COMPUTABLE" or not margin.total_cost:
                uncostable[cluster] += 1
                uncostable_workshops.setdefault(cluster, set()).add(workshop.workshop_id)
                continue
            if margin.verdict == "BELOW_COST":
                below[cluster] += 1
            by_cluster.setdefault(cluster, []).append(
                (workshop.workshop_id, margin.expected_price / margin.total_cost)  # type: ignore[operator]
            )

    groups: list[CostRatioGroup] = []
    for cluster in sorted(set(by_cluster) | set(uncostable)):
        entries = by_cluster.get(cluster, [])
        if not entries:
            groups.append(
                CostRatioGroup(
                    cluster,
                    len(uncostable_workshops.get(cluster, set())),
                    0,
                    uncostable[cluster],
                    below[cluster],
                    None,
                    f"{cluster}: {count_noun(uncostable[cluster], 'cost sheet')} recorded and "
                    f"{by_count(uncostable[cluster], 'it cannot', 'not one of them can')} "
                    f"be costed — each is missing its expected price, missing its cost heads, or "
                    f"totals zero. There is no price-to-cost multiple for this cluster to compare.",
                )
            )
            continue
        values = [ratio for _, ratio in entries]
        workshop_count = len({workshop_id for workshop_id, _ in entries})
        enough = len(values) >= MIN_SHEETS_FOR_RATIO
        distribution = describe(values) if enough else None

        if distribution is None:
            message = (
                f"{cluster}: {count_noun(len(values), 'costed product')} — too few to describe a "
                f"price-to-cost spread. Fewer than {MIN_SHEETS_FOR_RATIO} sheets is a list, "
                f"not a distribution."
            )
        else:
            message = (
                f"{cluster}: across {len(values)} costed products in {workshop_count} "
                f"{by_count(workshop_count, 'workshop', 'workshops')}, the expected price is a "
                f"median of "
                f"{distribution.median:.2f}× the cost to make "
                f"(lowest {distribution.minimum:.2f}×, highest {distribution.maximum:.2f}×)."
            )
        if below[cluster]:
            message += (
                f" {count_noun(below[cluster], 'sheet')} {by_count(below[cluster], 'is', 'are')} "
                f"priced BELOW what {by_count(below[cluster], 'it costs', 'they cost')} to make."
            )
        if uncostable[cluster]:
            message += (
                f" {uncostable[cluster]} further "
                f"{by_count(uncostable[cluster], 'sheet', 'sheets')} could not be costed at all."
            )

        groups.append(
            CostRatioGroup(
                cluster,
                workshop_count,
                len(values),
                uncostable[cluster],
                below[cluster],
                distribution,
                message,
            )
        )

    groups.sort(key=lambda g: (g.ratio is None, -g.sheets, g.cluster))
    return groups, unattributed



@dataclass(frozen=True, slots=True)
class RecurringTheme:
    term: str
    workshops: int
    occurrences: int
    examples: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class RecurringCategory:
    category: str
    workshops: int
    occurrences: int


def analyse_opportunities(
    workshops: list[WorkshopRows],
) -> tuple[list[RecurringCategory], list[RecurringTheme], int]:
    category_workshops: dict[str, set[str]] = {}
    category_counts: Counter[str] = Counter()
    term_workshops: dict[str, set[str]] = {}
    term_counts: Counter[str] = Counter()
    term_examples: dict[str, list[str]] = {}
    contributing: set[str] = set()

    for workshop in workshops:
        for row in workshop.opportunities:
            title = str(row.get("title") or "").strip()
            description = str(row.get("description") or "").strip()
            category = str(row.get("targetCategory") or "").strip()
            if not (title or description or category):
                continue
            contributing.add(workshop.workshop_id)

            if category:
                category_workshops.setdefault(category, set()).add(workshop.workshop_id)
                category_counts[category] += 1

            for term in _tokens(title):
                term_workshops.setdefault(term, set()).add(workshop.workshop_id)
                term_counts[term] += 1
                examples = term_examples.setdefault(term, [])
                if title and title not in examples and len(examples) < _THEME_EXAMPLES:
                    examples.append(title)

    categories = [
        RecurringCategory(category, len(seen), category_counts[category])
        for category, seen in category_workshops.items()
        if len(seen) >= MIN_WORKSHOPS_FOR_THEME
    ]
    categories.sort(key=lambda c: (-c.workshops, -c.occurrences, c.category))

    themes = [
        RecurringTheme(term, len(seen), term_counts[term], tuple(term_examples.get(term, ())))
        for term, seen in term_workshops.items()
        if len(seen) >= MIN_WORKSHOPS_FOR_THEME
    ]
    themes.sort(key=lambda t: (-t.workshops, -t.occurrences, t.term))

    return categories, themes, len(contributing)



@dataclass(frozen=True, slots=True)
class Reach:
    rows: int
    rows_with_revenue: int
    revenue: float
    rows_with_units_sold: int
    units_sold: float
    rows_with_orders: int
    orders: float
    message: str


def summarise_reach(observations: list[Observation]) -> Reach:
    revenue = [o.revenue for o in observations if o.revenue is not None]
    units = [o.units_sold for o in observations if o.units_sold is not None]
    orders = [o.orders for o in observations if o.orders is not None]
    total_revenue = math.fsum(revenue)

    if not observations:
        message = "No follow-up visit in the archive has recorded units or money."
    elif not revenue:
        message = (
            f"{by_count(len(observations), 'The 1 standing follow-up record does not carry', f'None of the {len(observations)} standing follow-up records carries')} "
            f"a revenue figure, so nothing can be said about what the designs earned."
        )
    else:
        message = (
            f"₹{total_revenue:,.0f} is recorded across {len(revenue)} of "
            f"{count_noun(len(observations), 'standing follow-up record')}, with "
            f"{math.fsum(units):,.0f} "
            f"{by_count(1 if f'{math.fsum(units):,.0f}' == '1' else 0, 'unit', 'units')} sold "
            f"reported on {len(units)} of them. "
            f"These are the figures at each workshop's most recent visit, not a total over "
            f"every visit — see the note on why that total is not produced."
        )

    return Reach(
        len(observations),
        len(revenue),
        total_revenue,
        len(units),
        math.fsum(units),
        len(orders),
        math.fsum(orders),
        message,
    )



@dataclass(frozen=True, slots=True)
class ArchiveFindings:
    workshops_in_archive: int
    workshops_analysed: int
    workshops_with_follow_up: int
    workshops_with_cost_sheets: int
    workshops_with_opportunities: int

    follow_up_rows: int
    rows_without_status: int
    superseded_rows: int

    overall: AdoptionGroup
    by_craft: tuple[AdoptionGroup, ...]
    by_cluster: tuple[AdoptionGroup, ...]
    by_state: tuple[AdoptionGroup, ...]
    missing_dimension: dict[str, int]

    survival: Survival
    cost_ratios: tuple[CostRatioGroup, ...]
    cost_sheets_without_cluster: int
    recurring_categories: tuple[RecurringCategory, ...]
    recurring_terms: tuple[RecurringTheme, ...]
    workshops_naming_opportunities: int
    reach: Reach

    cautions: tuple[str, ...] = ()
    not_computed: tuple[dict[str, str], ...] = ()
    truncated: bool = False
    notes: tuple[str, ...] = ()


_NOT_COMPUTED: tuple[dict[str, str], ...] = (
    {
        "question": "Prototypes produced against prototypes planned",
        "reason": (
            "No stage records a planned or target number of prototypes. "
            "Stage 2's “Expected deliverables” is free prose (one per line), stage 18's "
            "“Performance against planned targets” is a rich-text narrative, and stage 18's "
            "prototype count corrects the number recorded rather than setting a target "
            "beforehand, so there is no target to compare against."
        ),
    },
    {
        "question": "Total revenue and units sold across every follow-up visit",
        "reason": (
            "Stage 22 does not say whether “Revenue”, “Units sold” and “Orders received” are "
            "cumulative to date or incremental since the last visit, and the two readings differ by "
            "a factor of three on a workshop visited at 3, 6 and 12 months. Only the standing "
            "figure — the most recent visit — is reported."
        ),
    },
    {
        "question": "Adoption against the sketches that were shortlisted",
        "reason": (
            "Stage 12 (sketch review and shortlisting) is marked “Optional stage”, so "
            "workshops are told they may leave it empty. A funnel whose first step is an optional "
            "stage would report a collapse in workshops that simply did not fill it in."
        ),
    },
)


def _group_by(
    dimension: str, key: str, workshops: list[WorkshopRows], standing: dict[str, list[Observation]]
) -> tuple[list[AdoptionGroup], int]:
    buckets: dict[str, list[Observation]] = {}
    missing = 0
    for workshop in workshops:
        observations = standing.get(workshop.workshop_id) or []
        if not observations:
            continue
        label = str(getattr(workshop, key) or "").strip()
        if not label:
            missing += len(observations)
            continue
        buckets.setdefault(label, []).extend(observations)

    groups = [summarise_adoption(dimension, label, rows) for label, rows in buckets.items()]
    groups.sort(key=lambda g: (not g.reported, -g.observations, g.label))
    return groups, missing


def analyse_archive(
    workshops: list[WorkshopRows], *, workshops_in_archive: int, truncated: bool = False
) -> ArchiveFindings:
    all_observations: list[Observation] = []
    standing: dict[str, list[Observation]] = {}
    rows_without_status = 0
    superseded_rows = 0

    for workshop in workshops:
        observations: list[Observation] = []
        for row in workshop.follow_ups:
            parsed = read_observation(workshop.workshop_id, row)
            if parsed is None:
                rows_without_status += 1
                continue
            observations.append(parsed)
        if not observations:
            continue
        all_observations.extend(observations)
        latest, superseded = latest_interval_rows(observations)
        superseded_rows += superseded
        standing[workshop.workshop_id] = latest

    overall = summarise_adoption("ARCHIVE", "", [o for rows in standing.values() for o in rows])
    by_craft, missing_craft = _group_by("CRAFT", "craft", workshops, standing)
    by_cluster, missing_cluster = _group_by("CLUSTER", "cluster", workshops, standing)
    by_state, missing_state = _group_by("STATE", "state", workshops, standing)

    cost_ratios, sheets_without_cluster = analyse_cost_ratios(workshops)
    categories, terms, naming_opportunities = analyse_opportunities(workshops)
    reach = summarise_reach([o for rows in standing.values() for o in rows])

    with_follow_up = len(standing)
    with_cost_sheets = sum(1 for w in workshops if w.cost_sheets)
    with_opportunities = sum(1 for w in workshops if w.opportunities)

    cautions: list[str] = []
    if workshops_in_archive:
        share = with_follow_up / workshops_in_archive
        cautions.append(
            f"{with_follow_up} of {workshops_in_archive:,} workshops in the archive "
            f"({_percent(share)}) have recorded any follow-up at all. Every adoption figure below "
            f"describes those {with_follow_up}, not the scheme."
        )
    else:
        cautions.append("The archive holds no live workshops.")

    if truncated:
        cautions.append(
            "The archive was too large to read whole and the figures below are computed from a "
            "capped sample of the stage rows. They are not the archive's totals."
        )

    decided = [o for rows in standing.values() for o in rows if o.is_decided]
    if decided:
        cluster_of = {w.workshop_id: w.cluster.strip() for w in workshops}
        by_name = Counter(cluster_of.get(o.workshop_id, "") for o in decided)
        named = Counter({name: n for name, n in by_name.items() if name})
        if named:
            top_name, top_count = named.most_common(1)[0]
            if top_count / len(decided) > CONCENTRATION_LIMIT and len(named) > 1:
                cautions.append(
                    f"{top_count} of {len(decided)} recorded outcomes come from one cluster "
                    f"({top_name}). The archive-wide figures describe that cluster more than they "
                    f"describe the scheme."
                )

    if rows_without_status:
        cautions.append(
            f"{rows_without_status} follow-up entr{'y' if rows_without_status == 1 else 'ies'} "
            "have no adoption status and are excluded."
        )

    notes: list[str] = []
    if superseded_rows:
        notes.append(
            f"{count_noun(superseded_rows, 'follow-up row')} from earlier visits "
            f"{by_count(superseded_rows, 'is', 'are')} set aside: each workshop "
            f"is counted once, at the latest interval it reached, so a workshop visited three times "
            f"does not outweigh one visited once."
        )
    for dimension, missing in (
        ("craft", missing_craft),
        ("cluster", missing_cluster),
        ("state", missing_state),
    ):
        if missing:
            notes.append(
                f"{count_noun(missing, 'recorded outcome')} {by_count(missing, 'belongs', 'belong')} "
                f"to workshops with no {dimension} on the cover sheet and "
                f"{by_count(missing, 'is', 'are')} excluded from the {dimension} comparison."
            )
    if sheets_without_cluster:
        notes.append(
            f"{count_noun(sheets_without_cluster, 'cost sheet')} "
            f"{by_count(sheets_without_cluster, 'belongs', 'belong')} to workshops with no cluster "
            f"recorded and {by_count(sheets_without_cluster, 'is', 'are')} excluded from the cost "
            "comparison."
        )

    return ArchiveFindings(
        workshops_in_archive=workshops_in_archive,
        workshops_analysed=len(workshops),
        workshops_with_follow_up=with_follow_up,
        workshops_with_cost_sheets=with_cost_sheets,
        workshops_with_opportunities=with_opportunities,
        follow_up_rows=len(all_observations),
        rows_without_status=rows_without_status,
        superseded_rows=superseded_rows,
        overall=overall,
        by_craft=tuple(by_craft),
        by_cluster=tuple(by_cluster),
        by_state=tuple(by_state),
        missing_dimension={
            "craft": missing_craft,
            "cluster": missing_cluster,
            "state": missing_state,
        },
        survival=analyse_survival(all_observations),
        cost_ratios=tuple(cost_ratios),
        cost_sheets_without_cluster=sheets_without_cluster,
        recurring_categories=tuple(categories),
        recurring_terms=tuple(terms),
        workshops_naming_opportunities=naming_opportunities,
        reach=reach,
        cautions=tuple(cautions),
        not_computed=_NOT_COMPUTED,
        truncated=truncated,
        notes=tuple(notes),
    )



def _distribution_payload(dist: Distribution | None) -> dict[str, Any] | None:
    if dist is None:
        return None
    return {
        "count": dist.count,
        "minimum": round(dist.minimum, 3),
        "maximum": round(dist.maximum, 3),
        "mean": round(dist.mean, 3),
        "p25": round(dist.p25, 3),
        "median": round(dist.median, 3),
        "p75": round(dist.p75, 3),
        "quantilesReported": dist.quantiles_reported,
    }


def _adoption_payload(group: AdoptionGroup) -> dict[str, Any]:
    return {
        "dimension": group.dimension,
        "label": group.label,
        "workshops": group.workshops,
        "observations": group.observations,
        "adopted": group.adopted,
        "trial": group.trial,
        "notAdopted": group.not_adopted,
        "unknown": group.unknown,
        "rate": None if group.rate is None else round(group.rate, 4),
        "largestWorkshopShare": round(group.largest_workshop_share, 4),
        "message": group.message,
    }


def archive_findings_payload(findings: ArchiveFindings) -> dict[str, Any]:
    return {
        "cautions": list(findings.cautions),
        "notes": list(findings.notes),
        "notComputed": [dict(entry) for entry in findings.not_computed],
        "truncated": findings.truncated,
        "coverage": {
            "workshopsInArchive": findings.workshops_in_archive,
            "workshopsAnalysed": findings.workshops_analysed,
            "workshopsWithFollowUp": findings.workshops_with_follow_up,
            "workshopsWithCostSheets": findings.workshops_with_cost_sheets,
            "workshopsWithOpportunities": findings.workshops_with_opportunities,
            "followUpRows": findings.follow_up_rows,
            "rowsWithoutStatus": findings.rows_without_status,
            "supersededRows": findings.superseded_rows,
            "missingDimension": dict(findings.missing_dimension),
        },
        "overall": _adoption_payload(findings.overall),
        "byCraft": [_adoption_payload(g) for g in findings.by_craft],
        "byCluster": [_adoption_payload(g) for g in findings.by_cluster],
        "byState": [_adoption_payload(g) for g in findings.by_state],
        "survival": {
            "cohortPossible": findings.survival.cohort_possible,
            "trackedTo12": findings.survival.tracked_to_12,
            "stillAdoptedAt12": findings.survival.still_adopted_at_12,
            "unobservedAfter3": findings.survival.unobserved_after_3,
            "rowsWithoutProduct": findings.survival.rows_without_product,
            "message": findings.survival.message,
            "crossSections": [
                {
                    "interval": section.interval,
                    "label": section.label,
                    "workshops": section.workshops,
                    "observations": section.observations,
                    "adopted": section.adopted,
                    "rate": None if section.rate is None else round(section.rate, 4),
                    "message": section.message,
                }
                for section in findings.survival.cross_sections
            ],
        },
        "costRatios": {
            "sheetsWithoutCluster": findings.cost_sheets_without_cluster,
            "groups": [
                {
                    "cluster": group.cluster,
                    "workshops": group.workshops,
                    "sheets": group.sheets,
                    "uncostable": group.uncostable,
                    "belowCost": group.below_cost,
                    "ratio": _distribution_payload(group.ratio),
                    "message": group.message,
                }
                for group in findings.cost_ratios
            ],
        },
        "opportunities": {
            "workshopsNaming": findings.workshops_naming_opportunities,
            "categories": [
                {"category": c.category, "workshops": c.workshops, "occurrences": c.occurrences}
                for c in findings.recurring_categories
            ],
            "terms": [
                {
                    "term": t.term,
                    "workshops": t.workshops,
                    "occurrences": t.occurrences,
                    "examples": list(t.examples),
                }
                for t in findings.recurring_terms
            ],
        },
        "reach": {
            "rows": findings.reach.rows,
            "rowsWithRevenue": findings.reach.rows_with_revenue,
            "revenue": round(findings.reach.revenue, 2),
            "rowsWithUnitsSold": findings.reach.rows_with_units_sold,
            "unitsSold": round(findings.reach.units_sold, 2),
            "rowsWithOrders": findings.reach.rows_with_orders,
            "orders": round(findings.reach.orders, 2),
            "message": findings.reach.message,
        },
    }
