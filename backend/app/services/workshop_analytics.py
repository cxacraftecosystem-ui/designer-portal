"""What the whole archive says, as opposed to what two hundred separate reports each say once.

THE PROBLEM THIS SOLVES. Stage 22 asks, months after everyone has gone home, whether each design was
actually taken up: adopted or not, how many units were produced and sold, what revenue came back,
what support is still needed. Stage 17 records what every product costs to make and what it is
expected to fetch. Stage 9 records the design opportunities the survey pointed at. Every workshop
stores all of this, and until this module nothing added any of it up. The archive was a shelf of
documents. The questions the scheme exists to answer — which crafts produce designs that survive
contact with a buyer, which clusters come back with results and which do not, whether the prices
these products are costed at leave anything for the artisan — were all answerable from data already
captured, and none of them were being asked.

WHY THIS ONE IS NOT AN EDGE COMPUTATION, when almost everything else in this repository is. The
rule is that anything computable from data already on the device must run on the device. A
cross-workshop comparison is the case that rule does not reach: the input IS the other workshops,
and a handset holds one. There is deliberately no TypeScript or Kotlin port of this file, and the
absence is the design rather than an omission — see `market_analysis.py`, which HAS both ports
because it reads only the workshop in front of it.

Everything else about the shape follows `market_analysis.py`, which should be read first:

**It is pure.** No database, no network, no ORM row. It takes :class:`WorkshopRows` — a header and
the stored `data` dicts, exactly as they come off `DwStageEntry` — and returns findings. The caller
does the reading and the filtering; this file does the arithmetic. That is what lets the refusals
below be tested exhaustively against synthetic archives rather than against whatever happens to be
in the database this week.

**It refuses to conclude from too little, and names the caution before the number.** An adoption
rate computed from three workshops is a lie told with arithmetic: it is a real percentage of a real
numerator, and it describes one designer's fortnight rather than a scheme. So every figure carries
the sample behind it, a rate is withheld entirely below :data:`MIN_WORKSHOPS_FOR_RATE` workshops
AND :data:`MIN_OBSERVATIONS_FOR_RATE` observations, and where one workshop supplies most of a
group the message says so BEFORE it says the percentage — because a caution placed after a number
is a caution nobody reads.

**It refuses questions the registry cannot answer, out loud.** Three of the comparisons that were
asked for cannot be computed from what is captured, and :attr:`ArchiveFindings.not_computed` says
which and why in the payload, so the page can print the refusal rather than leaving a reader to
assume the section is still loading. The largest is survival: stage 22's own note describes "one
record per product per monitoring interval, which is what makes design-survival analysis possible
across workshops", and that is true only where `productRef` is filled in. Where it is blank, a row
at 12 months cannot be matched to the row at 3 months, and this module reports interval
CROSS-SECTIONS — which are honest and useful — rather than drawing a survival curve through
observations it cannot link.

WHAT IS COUNTED, AND WHY IT IS NOT THE OBVIOUS THING.

*A workshop is counted once at its latest interval.* Stage 22 accumulates rows: a well-followed-up
workshop has four products at 3 months, the same four at 6 and again at 12. Pooling all twelve rows
would weight that workshop three times as heavily as one visited once, and would drag its rate down
with the TRIAL statuses it has since grown out of. So each workshop contributes only the rows at the
highest interval it reached, and the rows set aside are counted and named
(:attr:`ArchiveFindings.superseded_rows`) rather than silently dropped.

*UNKNOWN is not a negative.* "Not known" is the honest answer a follow-up visit gives when nobody
was home, and folding it into the denominator would report a lower adoption rate for the clusters
that were most careful about admitting what they did not see. It is excluded from the rate and
reported beside it.

*TRIAL is not an adoption.* It goes in the denominator and not the numerator. A design under trial
may yet be adopted; calling it adopted today is the optimistic error, and this is a document a
ministry reads.
"""

from __future__ import annotations

import math
from collections import Counter
from dataclasses import dataclass
from typing import Any

# ONE DEFINITION OF WHAT A COST SHEET COSTS, imported rather than re-derived. `sum_cost_heads`
# already encodes the two rules that make the total non-obvious — a blank head counts as zero
# because four of the six are optional, but a sheet with NO head filled has no total at all rather
# than a total of ₹0 — and `compute_margin` already decides what to do when the price is missing or
# the cost is zero. A second implementation here would eventually disagree with the per-workshop
# cost-integrity panel about the same sheet, and a reader with two numbers has none.
from app.services.cost_integrity import compute_margin

# The reading of a stored MONEY string (fixed-2, possibly with grouping commas, never NaN) and the
# quantile method the ports are pinned to. `describe` also carries the sample floor below which it
# refuses to report quartiles at all, which is exactly the behaviour wanted for the cost ratios.
#
# `_tokens` IS IMPORTED BY ITS PRIVATE NAME ON PURPOSE. Its `_is_word_character` exists because
# Python's `\w` treats the virama in ରଙ୍ଗ and the matra in ଦାମ as word boundaries, which shatters
# every Odia phrase into fragments the length floor then discards — so a naive tokeniser scores
# every Odia design opportunity as having no content, and the recurring-theme report below would be
# a report about the workshops that happened to be written in English. Copying the fifteen lines
# here would create a second place for that bug to come back, in a module nobody would think to
# check. Importing the private name keeps one definition and one test suite.
from app.services.market_analysis import (
    MIN_SAMPLE_FOR_QUANTILES,
    Distribution,
    _tokens,
    as_number,
    describe,
)

# --------------------------------------------------------------------------------------
# The floors. Every one of these is a refusal threshold, not a display preference.
# --------------------------------------------------------------------------------------

#: Below this many workshops, a group gets counts but NO rate.
#:
#: Five, and deliberately not three. Three workshops is the number in the brief's own example of a
#: lie told with arithmetic: two adopted designs out of three reads "67% adoption" and describes one
#: designer's fortnight. Five is the smallest number of independent workshops for which a proportion
#: is a description of a group rather than an accident of who happened to file a follow-up.
MIN_WORKSHOPS_FOR_RATE = 5

#: Below this many decided observations, a group gets counts but no rate — checked IN ADDITION to
#: the workshop floor, because five workshops carrying one product each is still five products.
#: Eight matches `market_analysis.MIN_SAMPLE_FOR_VERDICT` for the same reason it was chosen there:
#: describing a small sample is honest, drawing a conclusion from it is not.
MIN_OBSERVATIONS_FOR_RATE = 8

#: Below this many cost sheets, a cluster's cost-to-price ratio is not summarised. Tied to the
#: quantile floor the ports already agree on, so the two cannot drift apart.
MIN_SHEETS_FOR_RATIO = MIN_SAMPLE_FOR_QUANTILES

#: When one workshop supplies more than this share of a group's observations, the group describes
#: that workshop. The rate is still reported — it is arithmetically correct — but the message leads
#: with the concentration, because a reader who sees "78%" first has already formed the belief the
#: caution was meant to prevent.
CONCENTRATION_LIMIT = 0.6

#: A design opportunity has not RECURRED until it has appeared in two different workshops. One
#: workshop naming the same theme in four rows is one workshop with a theme.
MIN_WORKSHOPS_FOR_THEME = 2

#: How many example phrases travel with a recurring term, so a reader can see what it meant in the
#: rows rather than judging a bare word.
_THEME_EXAMPLES = 3

# --------------------------------------------------------------------------------------
# The vocabulary of stage 22, restated from the registry
# --------------------------------------------------------------------------------------

#: The ADOPTION_STATUS tokens that mean the design is being made. Restated here rather than read out
#: of `stage_schema` so this module stays free of the registry and can be tested with dicts; a test
#: pins the two lists together, so a token added to the enum fails a test rather than being silently
#: counted as "not adopted".
ADOPTED_STATUSES = frozenset({"ADOPTED_IN_PRODUCTION", "ADOPTED_ON_ORDER"})

#: Observed, real, and not an adoption. Counted in the denominator, never in the numerator.
TRIAL_STATUS = "TRIAL"

#: A recorded decision that the design was not taken up.
NOT_ADOPTED_STATUS = "NOT_ADOPTED"

#: "Not known" — the honest answer when the follow-up visit could not establish the answer. Excluded
#: from the denominator entirely; see the module docstring.
UNKNOWN_STATUS = "UNKNOWN"

#: The FOLLOWUP_INTERVAL ladder, ranked. AD_HOC ranks 0 — BELOW three months — because it makes no
#: claim about how much time has passed, and a ladder that put it on top would let an ad-hoc note
#: taken the week after the workshop supersede a twelve-month visit.
_INTERVAL_RANK = {"M3": 1, "M6": 2, "M12": 3}

#: The intervals a cross-section is reported for, in order. AD_HOC is deliberately absent: "how were
#: things at some unspecified time" is not a column anybody can read.
SURVIVAL_INTERVALS = ("M3", "M6", "M12")

#: How each interval reads in a sentence.
_INTERVAL_LABELS = {"M3": "3 months", "M6": "6 months", "M12": "12 months"}


# --------------------------------------------------------------------------------------
# Input
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class WorkshopRows:
    """One workshop's header plus the stored rows this module reads.

    The four row lists are the `data` dicts off `DwStageEntry`, unmodified — the caller has nothing
    to reshape, and a test can write the same literals a designer would have typed.

    THE CALLER IS RESPONSIBLE FOR EXCLUDING DELETED WORKSHOPS, and that is not a detail. A design
    workshop is soft-deleted (`DesignWorkshop.deletedAt`) and its stage entries are NOT touched —
    `delete_design_workshop` sets one column on one row — so every follow-up record and cost sheet
    of a deleted workshop is still a live `DwStageEntry` with `deletedAt = null`. A query that
    filtered only the entries would quietly readmit them. This module cannot see the difference, so
    the route filters at both levels and a test pins it.
    """

    workshop_id: str
    title: str = ""
    craft: str = ""
    cluster: str = ""
    state: str = ""
    #: Stage 22 `followUp` rows.
    follow_ups: tuple[dict[str, Any], ...] = ()
    #: Stage 17 `costSheet` rows.
    cost_sheets: tuple[dict[str, Any], ...] = ()
    #: Stage 9 `designOpportunity` rows.
    opportunities: tuple[dict[str, Any], ...] = ()


# --------------------------------------------------------------------------------------
# Reading one follow-up row
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class Observation:
    """One follow-up row, reduced to the four things every comparison here needs."""

    workshop_id: str
    interval: str
    status: str
    #: The `productRef` as stored, or "" — the ONLY thing that can link two intervals to one design.
    product: str
    revenue: float | None
    units_sold: float | None
    orders: float | None

    @property
    def is_adopted(self) -> bool:
        return self.status in ADOPTED_STATUSES

    @property
    def is_decided(self) -> bool:
        """Whether this observation belongs in a rate's denominator at all.

        UNKNOWN does not. A cluster that honestly recorded "not known" for half its products would
        otherwise score a lower adoption rate than one that left the visit unrecorded, which
        rewards the wrong behaviour in exactly the data this scheme is judged on.
        """
        return self.status in ADOPTED_STATUSES or self.status in {TRIAL_STATUS, NOT_ADOPTED_STATUS}


def read_observation(workshop_id: str, row: dict[str, Any]) -> Observation | None:
    """One stored `followUp` row as an :class:`Observation`, or None when it says nothing.

    A row with no `adoptionStatus` is dropped: the field is required at stage 22, so a row without
    it came from an older client or a partial sync, and admitting it as UNKNOWN would invent a
    finding ("not known") out of an absence of data. The two are different and the difference is
    reported — see :attr:`ArchiveFindings.rows_without_status`.
    """
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
    """One workshop's observations narrowed to the LATEST interval it reached, and how many were set
    aside.

    THE STANDING OF A WORKSHOP'S PRODUCTS IS THE LAST TIME SOMEBODY LOOKED, not every time anybody
    looked. Pooling all of a diligently-visited workshop's rows counts it three times over and mixes
    a design's TRIAL status at three months with its ADOPTED status at twelve — the two rows say the
    same design became adopted, and averaging them says it half did.

    The superseded count is returned rather than discarded because a number that quietly ignores
    two thirds of the rows a designer filled in is a number nobody can reconcile with the archive.
    """
    if not observations:
        return [], 0
    top = max(_INTERVAL_RANK.get(o.interval, 0) for o in observations)
    kept = [o for o in observations if _INTERVAL_RANK.get(o.interval, 0) == top]
    return kept, len(observations) - len(kept)


# --------------------------------------------------------------------------------------
# Adoption, by whatever dimension was asked for
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class AdoptionGroup:
    """Adoption within one craft, one cluster, one state — or across the whole archive.

    `rate` is None whenever the group did not clear both floors. That is a REFUSAL and not a zero:
    a caller that renders None as 0% turns "we cannot say" into "nothing was adopted", which is the
    worst available reading of a group that simply has not been followed up yet.
    """

    dimension: str          # ARCHIVE | CRAFT | CLUSTER | STATE
    label: str
    workshops: int
    observations: int       # decided observations — the denominator, when there is one
    adopted: int
    trial: int
    not_adopted: int
    unknown: int            # reported beside the rate, never inside it
    rate: float | None
    largest_workshop_share: float
    message: str

    @property
    def reported(self) -> bool:
        return self.rate is not None


def _percent(value: float) -> str:
    return f"{value * 100:.0f}%"


def _adoption_message(label: str, workshops: int, *, observations: int, adopted: int,
                      unknown: int, rate: float | None, concentration: float) -> str:
    """One sentence that a reader can quote without having to add a caveat themselves.

    The order of the clauses is the whole point and it is not stylistic. Where a group is dominated
    by one workshop the sentence opens with that fact and only then gives the percentage, because a
    reader who has already read "78% adoption" has formed the belief; a caution after it is a
    footnote to something they now believe.
    """
    where = label or "the archive"
    if rate is None:
        if workshops < MIN_WORKSHOPS_FOR_RATE:
            return (f"{where}: {adopted} of {observations} recorded outcome(s) adopted, across "
                    f"{workshops} workshop(s). No adoption rate is given — a rate needs at least "
                    f"{MIN_WORKSHOPS_FOR_RATE} workshops behind it, or it describes one designer's "
                    f"fortnight rather than the scheme.")
        return (f"{where}: {adopted} of {observations} recorded outcome(s) adopted, across "
                f"{workshops} workshops. No adoption rate is given — {observations} outcome(s) is "
                f"below the {MIN_OBSERVATIONS_FOR_RATE} this reports a proportion from.")

    tail = (f" {unknown} further outcome(s) were recorded as not known and are excluded from the "
            f"rate." if unknown else "")
    if concentration > CONCENTRATION_LIMIT:
        # `where` is repeated as typed, NOT lower-cased. It is a craft or cluster name off the cover
        # sheet — "Sambalpuri Bandha (Ikat) handloom weaving" — and a sentence in a document a
        # ministry reads must not print somebody's craft in lower case to satisfy a grammar rule.
        return (f"{where}: {_percent(concentration)} of these outcomes come from a single workshop, "
                f"so this describes that workshop more than it describes {where} — with that said, "
                f"{adopted} of {observations} were adopted ({_percent(rate)}), across "
                f"{workshops} workshops.{tail}")
    return (f"{where}: {adopted} of {observations} recorded outcomes adopted ({_percent(rate)}), "
            f"across {workshops} workshops.{tail}")


def summarise_adoption(dimension: str, label: str,
                       observations: list[Observation]) -> AdoptionGroup:
    """Fold one group's observations into a figure, or into a stated refusal.

    `observations` must already be narrowed to the latest interval per workshop — see
    :func:`latest_interval_rows`. Doing it here would need the workshop boundaries this function
    deliberately does not have.
    """
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
        message=_adoption_message(label, len(workshops), observations=len(decided),
                                  adopted=adopted, unknown=unknown, rate=rate,
                                  concentration=concentration),
    )


# --------------------------------------------------------------------------------------
# The 3 / 6 / 12-month picture
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class IntervalCrossSection:
    """How the designs stood at one monitoring interval, ACROSS the workshops that reached it.

    A cross-section, and the name is the honesty. It is not a survival curve: the products observed
    at 12 months are not necessarily the products observed at 3, and the two columns are computed
    from different workshops whenever a workshop has not yet reached its later visit.
    """

    interval: str
    label: str
    workshops: int
    observations: int
    adopted: int
    rate: float | None
    message: str


@dataclass(frozen=True, slots=True)
class Survival:
    """The 3/6/12-month picture, and — where the rows allow it — actual per-design survival.

    `cohort_possible` is False when no follow-up row carries a `productRef`. That is the ordinary
    case in this archive today, and it is reported rather than worked around: without a product
    reference, the row at 12 months cannot be matched to the row at 3, and any curve drawn through
    them would be joining points that may belong to different designs.
    """

    cross_sections: tuple[IntervalCrossSection, ...]
    cohort_possible: bool
    #: Designs observed at 3 months AND again at 12, by `productRef`.
    tracked_to_12: int
    #: Of those, still adopted at 12 months.
    still_adopted_at_12: int
    #: Designs seen at 3 months and never again. NOT deaths — nobody went back.
    unobserved_after_3: int
    #: Follow-up rows at a 3/6/12 interval with no `productRef`, which therefore cannot join a
    #: cohort. Carried out because a partially-referenced archive is the dangerous case: the cohort
    #: figures look complete, and nothing on screen would say they were computed from a fifth of the
    #: rows. Every skipped row has to be counted where a reader can see it.
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
        message = (f"At {label}: {adopted} of {len(decided)} recorded outcome(s) adopted, across "
                   f"{len(workshops)} workshop(s) — too few to state as a rate.")
    else:
        message = (f"At {label}: {adopted} of {len(decided)} recorded outcomes adopted "
                   f"({_percent(rate)}), across {len(workshops)} workshops.")

    return IntervalCrossSection(interval, label, len(workshops), len(decided), adopted, rate,
                                message)


def analyse_survival(observations: list[Observation]) -> Survival:
    """The interval cross-sections, plus per-design survival where the rows can carry it.

    `observations` here is EVERY follow-up row, not the latest-interval narrowing: a cross-section
    at three months is precisely the rows that were superseded for the adoption figures.
    """
    sections = tuple(_cross_section(i, observations) for i in SURVIVAL_INTERVALS)

    # A design is identified by (workshop, productRef). The workshop half matters: product codes
    # like "PT-01" are chosen per workshop and repeat across the archive constantly, so keying on
    # the code alone would merge one cluster's table runner with another's stole.
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

    # Named in the sentence, not only in the field beside it. The partially-referenced archive is
    # the case that goes wrong quietly: the cohort numbers below look like the whole picture, and
    # a reader has no way to know they came from a fraction of the visits unless the sentence says.
    excluded = (f" {unreferenced} follow-up record(s) carry no product reference and cannot join a "
                f"cohort at all." if unreferenced else "")

    if not cohort_possible:
        message = (
            "No follow-up record in the archive carries a product reference, so no design can be "
            "matched from one visit to the next. The figures above are cross-sections — how things "
            "stood at each interval — and not a survival curve; drawing one would mean joining "
            "observations that may belong to different designs."
        )
    elif len(to_12) < MIN_OBSERVATIONS_FOR_RATE:
        message = (
            f"{len(to_12)} design(s) have been observed at both 3 and 12 months — too few to state "
            f"a survival rate. {still} of them were still adopted at 12 months. A further "
            f"{lost} design(s) seen at 3 months have not been revisited, which is an absence of "
            f"evidence and not evidence of abandonment.{excluded}"
        )
    else:
        message = (
            f"Of {len(to_12)} designs observed at both 3 and 12 months, {still} were still adopted "
            f"at 12 months ({_percent(still / len(to_12))}). A further {lost} design(s) seen at 3 "
            f"months have not been revisited; they are not counted here in either direction, "
            f"because nobody went back to look.{excluded}"
        )

    return Survival(sections, cohort_possible, len(to_12), still, lost, unreferenced, message)


# --------------------------------------------------------------------------------------
# What a product costs against what it is expected to fetch
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class CostRatioGroup:
    """The price-to-cost multiple across one cluster's cost sheets.

    `ratio` is expected price DIVIDED BY total cost, so 1.0 is at cost and 1.3 is a thirty per cent
    markup. A multiple rather than a margin because it survives being compared across clusters whose
    products differ by two orders of magnitude in absolute rupees, and because
    `cost_integrity.compute_margin` already owns the per-sheet margin sentence — this is the
    distribution, not a second opinion on any one sheet.
    """

    cluster: str
    workshops: int
    sheets: int             # sheets a ratio could be computed from
    uncostable: int         # sheets with no price, no cost, or a cost of zero
    below_cost: int         # priced under what they cost to make
    ratio: Distribution | None
    message: str


def analyse_cost_ratios(workshops: list[WorkshopRows]) -> tuple[list[CostRatioGroup], int]:
    """Price-to-cost multiples grouped by cluster, and the sheets no cluster could be found for.

    Sheets belonging to a workshop with a blank `clusterName` are NOT pooled into an empty-named
    group — "the cost ratio for cluster ''" is not a finding — and they are not silently dropped
    either. They are counted and returned, so the page can say how much of the archive the
    comparison could not reach.
    """
    by_cluster: dict[str, list[tuple[str, float]]] = {}
    unattributed = 0
    uncostable: Counter[str] = Counter()
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
                continue
            if margin.verdict == "BELOW_COST":
                below[cluster] += 1
            # `expected_price` and `total_cost` are both non-None and the cost is non-zero for
            # every verdict that is not NOT_COMPUTABLE — compute_margin guarantees it.
            by_cluster.setdefault(cluster, []).append(
                (workshop.workshop_id, margin.expected_price / margin.total_cost)  # type: ignore[operator]
            )

    groups: list[CostRatioGroup] = []
    for cluster, entries in by_cluster.items():
        values = [ratio for _, ratio in entries]
        workshop_count = len({workshop_id for workshop_id, _ in entries})
        enough = len(values) >= MIN_SHEETS_FOR_RATIO
        distribution = describe(values) if enough else None

        if distribution is None:
            message = (f"{cluster}: {len(values)} costed product(s) — too few to describe a "
                       f"price-to-cost spread. Fewer than {MIN_SHEETS_FOR_RATIO} sheets is a list, "
                       f"not a distribution.")
        else:
            message = (f"{cluster}: across {len(values)} costed products in {workshop_count} "
                       f"workshop(s), the expected price is a median of "
                       f"{distribution.median:.2f}× the cost to make "
                       f"(lowest {distribution.minimum:.2f}×, highest {distribution.maximum:.2f}×).")
        if below[cluster]:
            message += (f" {below[cluster]} sheet(s) are priced BELOW what they cost to make.")
        if uncostable[cluster]:
            message += (f" {uncostable[cluster]} further sheet(s) could not be costed at all.")

        groups.append(CostRatioGroup(cluster, workshop_count, len(values), uncostable[cluster],
                                     below[cluster], distribution, message))

    # Clusters that could be summarised first, then by sheet count — a reader scanning for a
    # comparison should not have to skip past six refusals to find the two groups that have one.
    groups.sort(key=lambda g: (g.ratio is None, -g.sheets, g.cluster))
    return groups, unattributed


# --------------------------------------------------------------------------------------
# Which design opportunities recur
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class RecurringTheme:
    """One word that appears in the design opportunities of more than one workshop.

    A WORD-FREQUENCY REPORT, AND IT SAYS SO. This is not semantic clustering and must never be
    presented as though it were: "bag" recurring in nine workshops means nine designers wrote the
    word, which is a place to look and not a finding. The examples travel with it so a reader judges
    the phrases rather than the token.
    """

    term: str
    workshops: int
    occurrences: int
    examples: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class RecurringCategory:
    """One PRODUCT_CATEGORY token named as a target by more than one workshop.

    Stronger evidence than :class:`RecurringTheme` and listed separately for that reason: this is a
    closed list, so two workshops choosing HOME_TEXTILE have chosen the same thing, whereas two
    workshops writing "bag" may not have.
    """

    category: str
    workshops: int
    occurrences: int


def analyse_opportunities(
    workshops: list[WorkshopRows],
) -> tuple[list[RecurringCategory], list[RecurringTheme], int]:
    """What the design opportunities across the archive have in common.

    Returns the closed-list recurrences, the free-text ones, and the number of workshops that
    recorded any opportunity at all — which is the denominator every count here has to be read
    against, and the reason it is returned rather than left for the caller to guess.
    """
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

            # The TITLE only, not the description. A description is a paragraph and its vocabulary
            # is dominated by the words any description of anything would contain; pooling the two
            # makes "product", "market" and "quality" the top three themes in every archive.
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


# --------------------------------------------------------------------------------------
# What came back
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class Reach:
    """Units and money recorded at the LATEST follow-up visit of each workshop.

    THE EARLIER VISITS ARE NOT ADDED IN, and the reason is a refusal rather than an optimisation.
    Stage 22 declares `revenue`, `unitsSold` and `ordersReceived` with no statement anywhere in the
    registry of whether the figure is cumulative to date or incremental since the last visit. A
    designer entering ₹62,700 at three months and ₹98,000 at six may mean either. Adding them would
    count the same rupee twice for one reading and be correct for the other, and nothing in the
    captured data distinguishes them — so this reports the standing figure, says that it is the
    standing figure, and `not_computed` names the total that cannot be produced.
    """

    rows: int                   # rows at a latest interval, the denominator for the three below
    rows_with_revenue: int
    revenue: float
    rows_with_units_sold: int
    units_sold: float
    rows_with_orders: int
    orders: float
    message: str


def summarise_reach(observations: list[Observation]) -> Reach:
    """Totals over the latest-interval observations, each with the rows that actually carried it.

    A total is meaningless without the count behind it: ₹62,700 across 3 of 40 follow-up rows is a
    statement about three products, and printing it beside "40 follow-up records" invites exactly
    the wrong reading.
    """
    revenue = [o.revenue for o in observations if o.revenue is not None]
    units = [o.units_sold for o in observations if o.units_sold is not None]
    orders = [o.orders for o in observations if o.orders is not None]
    total_revenue = math.fsum(revenue)

    if not observations:
        message = "No follow-up visit in the archive has recorded units or money."
    elif not revenue:
        message = (f"None of the {len(observations)} standing follow-up record(s) carries a revenue "
                   f"figure, so nothing can be said about what the designs earned.")
    else:
        message = (f"₹{total_revenue:,.0f} is recorded across {len(revenue)} of "
                   f"{len(observations)} standing follow-up record(s), with "
                   f"{math.fsum(units):,.0f} unit(s) sold reported on {len(units)} of them. "
                   f"These are the figures at each workshop's most recent visit, not a total over "
                   f"every visit — see the note on why that total is not produced.")

    return Reach(len(observations), len(revenue), total_revenue, len(units), math.fsum(units),
                 len(orders), math.fsum(orders), message)


# --------------------------------------------------------------------------------------
# The whole archive
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class ArchiveFindings:
    """Everything this module can say, plus everything it was asked for and cannot."""

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
    #: Observations whose workshop recorded no craft / cluster / state, per dimension. They are
    #: excluded from the lists above and counted here, because a comparison that quietly analysed
    #: 4 of 40 rows is indistinguishable from one that analysed all of them.
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


#: The comparisons that were asked for, that the field registry cannot support, and exactly why.
#:
#: SHIPPED AS DATA RATHER THAN LEFT OUT. A section that is simply absent reads as unfinished, and
#: the next person to be asked for "prototypes against target" will go looking for the field again.
#: Naming the missing field is what turns a refusal into a specification.
_NOT_COMPUTED: tuple[dict[str, str], ...] = (
    {
        "question": "Prototypes produced against prototypes planned",
        "reason": (
            "Nothing in the 496-field registry records a planned or target number of prototypes. "
            "Stage 2's “Expected deliverables” is free prose (one per line), stage 18's "
            "“Performance against planned targets” is a rich-text narrative, and stage 18's "
            "prototype count is an OVERRIDE of the rows recorded rather than a target set "
            "beforehand. Parsing a sentence into a target would be inventing the denominator."
        ),
    },
    {
        "question": "Total revenue and units sold across every follow-up visit",
        "reason": (
            "Stage 22 does not say whether `revenue`, `unitsSold` and `ordersReceived` are "
            "cumulative to date or incremental since the last visit, and the two readings differ by "
            "a factor of three on a workshop visited at 3, 6 and 12 months. Only the standing "
            "figure — the most recent visit — is reported."
        ),
    },
    {
        "question": "Adoption against the sketches that were shortlisted",
        "reason": (
            "Stage 12 (sketch review and shortlisting) is declared `optional_stage=True` and the "
            "reviewer's own note proposes deleting it. A funnel whose first step is a stage "
            "workshops are told they may skip would report a collapse in workshops that simply "
            "did not fill it in."
        ),
    },
)


def _group_by(dimension: str, key: str, workshops: list[WorkshopRows],
              standing: dict[str, list[Observation]]) -> tuple[list[AdoptionGroup], int]:
    """Adoption grouped by one header column, and the observations that column was blank for."""
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
    # Reportable groups first, then the biggest — the same ordering rule as the cost ratios, for
    # the same reason.
    groups.sort(key=lambda g: (not g.reported, -g.observations, g.label))
    return groups, missing


def analyse_archive(workshops: list[WorkshopRows], *, workshops_in_archive: int,
                    truncated: bool = False) -> ArchiveFindings:
    """The whole cross-workshop comparison, from the rows of stages 9, 17 and 22.

    `workshops_in_archive` is the count of LIVE workshops the caller found — including every one
    that contributed no rows at all. It is not derivable from `workshops` and it is the single most
    important number here: "12 follow-up records exist across 5,607 workshops" is the finding that
    tells a reader how much weight every figure below it can carry, and a page that omitted it would
    present the same percentages as though they described the scheme.

    `truncated` is set by the caller when it hit its own row cap. It is carried into the findings so
    the page can say the archive was not read whole, rather than presenting a partial reading as a
    complete one.
    """
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

    # THE COVERAGE CAUTION IS ALWAYS FIRST, and it is the one caution that fires on a healthy
    # archive as well as a thin one. Follow-up happens months after a workshop closes and is not
    # required for a workshop to be complete, so the gap between "workshops held" and "workshops
    # followed up" is the real measurement here — every adoption figure on this page describes only
    # the second number.
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

    # Concentration, at the ARCHIVE level — the "one cluster dominating" case. Checked against the
    # cluster the observations came from rather than the workshop, because five workshops in one
    # cluster is still one cluster, and the workshop-level check inside `summarise_adoption` cannot
    # see that.
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
            f"{rows_without_status} follow-up row(s) carry no adoption status and are excluded. "
            f"The field is required at stage 22, so these came from a partial sync or an older "
            f"client rather than from a visit that found nothing."
        )

    notes: list[str] = []
    if superseded_rows:
        notes.append(
            f"{superseded_rows} follow-up row(s) from earlier visits are set aside: each workshop "
            f"is counted once, at the latest interval it reached, so a workshop visited three times "
            f"does not outweigh one visited once."
        )
    for dimension, missing in (("craft", missing_craft), ("cluster", missing_cluster),
                               ("state", missing_state)):
        if missing:
            notes.append(
                f"{missing} recorded outcome(s) belong to workshops with no {dimension} on the "
                f"cover sheet and are excluded from the {dimension} comparison."
            )
    if sheets_without_cluster:
        notes.append(
            f"{sheets_without_cluster} cost sheet(s) belong to workshops with no cluster recorded "
            f"and are excluded from the cost comparison."
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
        missing_dimension={"craft": missing_craft, "cluster": missing_cluster,
                           "state": missing_state},
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


# --------------------------------------------------------------------------------------
# The wire form
# --------------------------------------------------------------------------------------


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
    """The wire form, camelCased like every other payload this API returns.

    `cautions`, `notes` and `notComputed` are at the TOP of the object, above every figure they
    qualify, for the same reason `market_findings_payload` puts them there: a caution a client has
    to go looking for is a caution that will not be rendered, and the entire value of this endpoint
    rests on a reader seeing "12 of 5,607 workshops have any follow-up" before they see a percentage.
    """
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
