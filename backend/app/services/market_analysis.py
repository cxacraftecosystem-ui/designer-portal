"""Stage 9's Advanced tier: what the survey data actually says, computed rather than asserted.

THE PROBLEM THIS SOLVES. Stages 7 and 8 capture a real market survey — respondent groups, price
expectations, products discussed, competitor products and their prices. Stage 9 then asks the
designer to write down demand, price bands, competitor analysis and a SWOT. Nothing connected the
two. A designer typed "₹400–800, high demand" into stage 9 having collected twenty-three price
expectations in stage 8, and no part of the system ever compared the two numbers. The report
printed the assertion, the evidence sat one stage away, and a reviewer at the ministry had no way
to tell a considered band from a guess.

So this module computes, from the captured rows alone:

  * the price distribution per product category, from respondents AND competitors, kept apart
    because they answer different questions (what a buyer will pay vs what the shelf charges);
  * a verdict on each declared price band against that distribution — how much of the observed
    market it actually covers, and which way it is wrong when it is wrong;
  * where each competitor product sits in the distribution it competes in;
  * which SWOT points are supported by something a respondent actually said, and which are
    unsupported assertions;
  * which products are discussed together, as co-occurrence clusters.

THREE PROPERTIES, EACH LOAD-BEARING.

**It is pure.** No database, no network, no model. It takes rows and returns findings, which is
what lets it be tested exhaustively, run inside a report render, and — the point — run on the
handset and in the browser. `frontend/lib/marketAnalysis.ts` and the Kotlin port are ports of THIS
file; when they disagree, this one is right. A designer in a village with no signal gets the same
analysis as one at a desk, because there is nothing here that needs a server.

**It refuses to conclude from too little.** Four price expectations are not a market. Every figure
carries the sample it came from, quantiles are withheld below :data:`MIN_SAMPLE_FOR_QUANTILES`, and
a band gets no verdict below :data:`MIN_SAMPLE_FOR_VERDICT` — it is reported as unverifiable, which
is a true statement, rather than as sound, which would be a fabricated one. An analysis tool that
produces confident numbers from three data points is worse than no tool, because the confidence is
what gets carried into the report.

**It never overwrites the designer.** Nothing here edits stage 9. The designer's declared band
stays exactly as typed; this produces a FINDING beside it. The judgement is theirs — they were in
the room and the arithmetic was not — but it is now a judgement made in front of the evidence.

WHY THE QUANTILE METHOD IS SPELLED OUT. :func:`quantile` documents its interpolation exactly,
because three independent ports have to agree to the rupee. numpy's default is linear
interpolation on (n-1) positions; that is what is implemented, by hand, so no port has to depend on
a numerics library to match.
"""

from __future__ import annotations

import math
import unicodedata
from collections import Counter
from dataclasses import dataclass, field
from typing import Any

#: Below this many observations, quantiles are not reported at all. Five is the smallest sample for
#: which a quartile is a description of a spread rather than an accident of two numbers.
MIN_SAMPLE_FOR_QUANTILES = 5

#: Below this many observations, a declared band gets no verdict. Deliberately higher than the
#: quantile floor: showing a spread from six numbers is honest, telling a designer their band is
#: WRONG from six numbers is not.
MIN_SAMPLE_FOR_VERDICT = 8

#: A band is called NARROW when it covers less of the observed market than this. Not a
#: statistical threshold and not presented as one — it is the point at which "your band misses
#: most of the people you asked" becomes worth interrupting a designer to say.
NARROW_COVERAGE = 0.55

#: Words too common to carry evidence. Kept small and English-only ON PURPOSE: the survey text is
#: frequently Odia, Hindi or transliterated, and a large English stop list would strip the rare
#: words that make a match meaningful in exactly the responses that matter most. Matching is a
#: hint for a human to check, never a claim of proof.
#: Only words of three letters or more can survive `_MIN_TOKEN` anyway, so the one- and two-letter
#: articles are absent from this list rather than missing from it.
_STOPWORDS = frozenset({
    "and", "are", "but", "for", "from", "has", "have", "its", "not", "our", "that", "the",
    "their", "there", "they", "this", "was", "were", "will", "with", "you", "more", "most",
    "some", "such", "than", "then", "these", "those", "can", "could", "would", "should",
    "may", "might", "also", "very", "much", "many",
})

#: The shortest run of letters kept as a content word. Two-letter fragments are noise in every
#: script the survey is written in, and the stop list already removes the English ones that matter.
_MIN_TOKEN = 3


def _is_word_character(ch: str) -> bool:
    """Whether `ch` belongs INSIDE a word: a letter, or a mark that attaches to one.

    THE MARKS ARE THE WHOLE POINT, and leaving them out is a bug that hides in plain sight. A
    regex of ``[^\\W\\d_]+`` looks Unicode-correct and is not: Python's ``\\w`` follows
    ``str.isalnum()``, which is False for combining marks, so the virama in ରଙ୍ଗ and the matra in
    ଦାମ are treated as WORD BOUNDARIES. Odia words shatter into one- and two-character fragments,
    the length floor then discards the fragments, and every Odia response scores as having no
    content at all — making "unsupported by the survey" the automatic verdict for precisely the
    fieldwork this application exists to collect. The same is true of Devanagari, Bengali and every
    other Indic script in scope, and it is invisible in English testing.

    THE RULE THE PORTS MUST MATCH: a token is a maximal run of characters whose Unicode general
    category begins with ``L`` (any letter) or ``M`` (any combining mark). JavaScript and Kotlin can
    write that directly as ``[\\p{L}\\p{M}]+`` — Python's ``re`` has no ``\\p{...}``, which is why
    this is a scan rather than a pattern. It is not a hot path: survey responses are sentences.
    """
    return ch.isalpha() or unicodedata.category(ch).startswith("M")


def _tokens(text: str) -> set[str]:
    """Content words of `text`, lowercased. See :func:`_is_word_character` for the tokenising rule."""
    out: set[str] = set()
    current: list[str] = []
    for ch in (text or "") + " ":
        if _is_word_character(ch):
            current.append(ch)
            continue
        if current:
            word = "".join(current).lower()
            current = []
            if len(word) >= _MIN_TOKEN and word not in _STOPWORDS:
                out.add(word)
    return out


def as_number(value: Any) -> float | None:
    """`value` as a finite float, or None.

    MONEY is stored as a fixed-2 STRING and may carry grouping commas, so this is the one place
    that knows how to read it. Non-finite is rejected rather than propagated: a NaN entering a
    quantile silently poisons every figure downstream of it, and a report that prints "₹ nan" has
    already been submitted by the time anybody notices.
    """
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        number = float(value)
        return number if math.isfinite(number) else None
    text = str(value).strip().replace(",", "")
    if not text:
        return None
    try:
        number = float(text)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def quantile(sorted_values: list[float], q: float) -> float:
    """The `q`-quantile of an ALREADY SORTED list, by linear interpolation on (n-1) positions.

    Specified rather than delegated, because `frontend/lib/marketAnalysis.ts` and the Kotlin port
    must produce the same rupee figure as this does. The method is numpy's default:

        position = q * (n - 1);  lower = floor(position);  upper = ceil(position)
        result   = v[lower] + (v[upper] - v[lower]) * (position - lower)

    The caller sorts. That is not laziness — every caller here computes several quantiles from one
    sample, and sorting inside would turn one sort into five.
    """
    if not sorted_values:
        raise ValueError("quantile of an empty sample")
    if len(sorted_values) == 1:
        return sorted_values[0]
    position = q * (len(sorted_values) - 1)
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return sorted_values[lower]
    return sorted_values[lower] + (sorted_values[upper] - sorted_values[lower]) * (position - lower)


@dataclass(frozen=True, slots=True)
class PriceObservation:
    """One price somebody named, and who named it.

    `label` exists so a finding can be traced back to the row that produced it. An analysis a
    designer cannot audit is one they have to take on faith, and the whole purpose of this module
    is to replace faith with arithmetic.
    """

    amount: float
    category: str = ""
    source: str = "RESPONDENT"     # RESPONDENT | COMPETITOR
    group: str = ""                # RESPONDENT_GROUP token, for respondent observations
    label: str = ""


@dataclass(frozen=True, slots=True)
class Distribution:
    """A sample's shape. `quantiles_reported` is False when the sample was too small to describe."""

    count: int
    minimum: float
    maximum: float
    mean: float
    p25: float
    median: float
    p75: float
    quantiles_reported: bool

    @property
    def spread(self) -> float:
        return self.maximum - self.minimum


def describe(values: list[float]) -> Distribution | None:
    """The distribution of `values`, or None for an empty sample.

    Below :data:`MIN_SAMPLE_FOR_QUANTILES` the quartiles are filled with the median and
    `quantiles_reported` is False, so a caller that ignores the flag shows something defensible
    rather than a quartile computed from two numbers.
    """
    if not values:
        return None
    ordered = sorted(values)
    count = len(ordered)
    median = quantile(ordered, 0.5)
    enough = count >= MIN_SAMPLE_FOR_QUANTILES
    return Distribution(
        count=count,
        minimum=ordered[0],
        maximum=ordered[-1],
        mean=sum(ordered) / count,
        p25=quantile(ordered, 0.25) if enough else median,
        median=median,
        p75=quantile(ordered, 0.75) if enough else median,
        quantiles_reported=enough,
    )


@dataclass(frozen=True, slots=True)
class BandVerdict:
    """What the survey says about one declared price band.

    `verdict` is one of:
      SOUND         the band covers most of the observed market
      NARROW        the band excludes most of it, without being wholly on one side
      LOW / HIGH    the band sits below / above where the evidence clusters
      UNVERIFIABLE  too few observations to say anything (NOT a criticism of the band)
      NO_EVIDENCE   no observations in this category at all
    """

    category: str
    declared_low: float | None
    declared_high: float | None
    evidence: Distribution | None
    inside: int
    below: int
    above: int
    coverage: float
    verdict: str
    message: str


def _band_message(verdict: str, category: str, low: float, high: float, dist: Distribution,
                  inside: int, below: int, above: int) -> str:
    """One sentence a designer can act on, naming the numbers rather than grading the designer."""
    label = category or "this category"
    if verdict == "SOUND":
        return (f"The band ₹{low:,.0f}–{high:,.0f} covers {inside} of {dist.count} price "
                f"observations for {label}; the median observed is ₹{dist.median:,.0f}.")
    if verdict == "LOW":
        return (f"The band ₹{low:,.0f}–{high:,.0f} sits below the evidence for {label}: "
                f"{above} of {dist.count} observations are above it, and the median observed is "
                f"₹{dist.median:,.0f}. The prototypes may be priced under what buyers said.")
    if verdict == "HIGH":
        return (f"The band ₹{low:,.0f}–{high:,.0f} sits above the evidence for {label}: "
                f"{below} of {dist.count} observations are below it, and the median observed is "
                f"₹{dist.median:,.0f}.")
    return (f"The band ₹{low:,.0f}–{high:,.0f} covers only {inside} of {dist.count} observations "
            f"for {label} ({below} below, {above} above; median ₹{dist.median:,.0f}). "
            f"Widening it, or splitting the category, would match what was recorded.")


def judge_band(category: str, low: Any, high: Any, observations: list[PriceObservation]) -> BandVerdict:
    """Compare one declared band against every price observed in its category.

    A reversed band (low > high) is read in the order the designer meant rather than refused: the
    two boxes are adjacent on a phone and transposing them is a slip, not a claim, and answering a
    slip with "no verdict" would hide the real finding underneath it.
    """
    declared_low = as_number(low)
    declared_high = as_number(high)
    if declared_low is not None and declared_high is not None and declared_low > declared_high:
        declared_low, declared_high = declared_high, declared_low

    values = [o.amount for o in observations]
    dist = describe(values)

    if dist is None:
        return BandVerdict(category, declared_low, declared_high, None, 0, 0, 0, 0.0, "NO_EVIDENCE",
                           f"No price was recorded in stage 8 for {category or 'this category'}, "
                           f"so this band cannot be checked against the survey.")

    if declared_low is None or declared_high is None:
        return BandVerdict(category, declared_low, declared_high, dist, 0, 0, 0, 0.0, "NO_EVIDENCE",
                           f"{dist.count} price observation(s) exist for "
                           f"{category or 'this category'}, but the band itself is incomplete.")

    inside = sum(1 for v in values if declared_low <= v <= declared_high)
    below = sum(1 for v in values if v < declared_low)
    above = sum(1 for v in values if v > declared_high)
    coverage = inside / dist.count

    if dist.count < MIN_SAMPLE_FOR_VERDICT:
        return BandVerdict(
            category, declared_low, declared_high, dist, inside, below, above, coverage,
            "UNVERIFIABLE",
            f"Only {dist.count} price observation(s) were recorded for "
            f"{category or 'this category'} — too few to judge the band against. "
            f"{inside} of them fall inside it.")

    if coverage >= NARROW_COVERAGE:
        verdict = "SOUND"
    elif above > below * 2:
        verdict = "LOW"
    elif below > above * 2:
        verdict = "HIGH"
    else:
        verdict = "NARROW"

    return BandVerdict(category, declared_low, declared_high, dist, inside, below, above, coverage,
                       verdict,
                       _band_message(verdict, category, declared_low, declared_high, dist,
                                     inside, below, above))


@dataclass(frozen=True, slots=True)
class CompetitorPosition:
    """Where one competitor product sits among the prices observed in its category."""

    name: str
    seller: str
    category: str
    price: float
    percentile: float | None
    versus_median: float | None
    note: str


def position_competitors(competitors: list[dict[str, Any]],
                         observations: list[PriceObservation]) -> list[CompetitorPosition]:
    """Place each competitor product in its category's respondent price distribution.

    Positioned against what BUYERS said they would pay, not against the other competitors — the
    useful question for a designer is "is this shelf price above or below what the people we asked
    are willing to spend", and a competitor-only comparison cannot answer it.
    """
    by_category: dict[str, list[float]] = {}
    pooled: list[float] = []
    for observation in observations:
        if observation.source != "RESPONDENT":
            continue
        pooled.append(observation.amount)
        if observation.category:
            by_category.setdefault(observation.category, []).append(observation.amount)
    for values in by_category.values():
        values.sort()
    pooled.sort()

    out: list[CompetitorPosition] = []
    for row in competitors:
        price = as_number(row.get("price"))
        if price is None:
            continue
        category = str(row.get("category") or "")
        name = str(row.get("name") or "").strip() or "Unnamed product"
        seller = str(row.get("seller") or "").strip()

        # CATEGORY-MATCHED IF POSSIBLE, POOLED IF NOT, AND THE MESSAGE SAYS WHICH.
        #
        # A respondent's price expectation carries NO category — stage 8 asks what they would pay,
        # not what for — so a strict category lookup finds nothing for almost every competitor and
        # the whole section reads "too few buyer price expectations" against a survey of forty
        # people. That is not a cautious answer, it is a useless one, and it is the opposite of
        # what `judge_band` does two functions up: that one already pools the uncategorised
        # observations. The two must not disagree about the same sample.
        #
        # So the fallback is used and DECLARED. "Against all buyers asked" is a weaker statement
        # than "against buyers asked about this category", and a designer has to be able to tell
        # them apart to know how much weight to put on the number.
        sample = by_category.get(category) or []
        matched = len(sample) >= MIN_SAMPLE_FOR_QUANTILES
        if not matched:
            sample = pooled
        if len(sample) < MIN_SAMPLE_FOR_QUANTILES:
            out.append(CompetitorPosition(
                name, seller, category, price, None, None,
                f"₹{price:,.0f}. Too few buyer price expectations were recorded in the survey to "
                f"place this against them."))
            continue

        at_or_below = sum(1 for v in sample if v <= price)
        percentile = at_or_below / len(sample)
        median = quantile(sample, 0.5)
        whose = (f"the {len(sample)} buyers asked about {category}" if matched
                 else f"all {len(sample)} buyers asked")
        comparison = "above" if percentile >= 0.5 else "below"
        share = percentile if percentile >= 0.5 else 1 - percentile
        out.append(CompetitorPosition(
            name, seller, category, price, percentile, price - median,
            f"₹{price:,.0f} is {comparison} what {share * 100:.0f}% of {whose} said they would "
            f"pay (their median: ₹{median:,.0f})."))
    return out


@dataclass(frozen=True, slots=True)
class SwotSupport:
    """Whether one SWOT point is backed by something a respondent actually said."""

    kind: str
    point: str
    supported_by: tuple[str, ...] = ()
    overlap: int = 0
    has_own_evidence: bool = False

    @property
    def supported(self) -> bool:
        return self.has_own_evidence or bool(self.supported_by)


#: How many content words a SWOT point and a survey response must share before the response is
#: offered as support. Two, because one shared word is a coincidence at this vocabulary size and
#: three misses a short point ("price too high") against a short response.
_SUPPORT_OVERLAP = 2


def link_swot_evidence(swot: list[dict[str, Any]],
                       responses: list[dict[str, Any]]) -> list[SwotSupport]:
    """Match each SWOT point to the survey responses that share its vocabulary.

    THIS IS A RETRIEVAL AID, NOT A JUDGEMENT, and the distinction is the reason it can ship without
    a model behind it. Shared words mean a response is worth reading next to the point, nothing
    more; the designer decides whether it supports the claim. What it does reliably is the negative
    case — a SWOT point sharing no vocabulary with any of forty responses and carrying no evidence
    of its own is an assertion, and saying so is worth more than any positive match.

    A point that filled its own `evidence` field is supported by definition: the designer cited
    something. That is not second-guessed.
    """
    prepared = []
    for row in responses:
        text = " ".join(
            str(row.get(k) or "") for k in ("response", "productsDiscussed", "place")
        )
        if isinstance(row.get("productsDiscussed"), list):
            text += " " + " ".join(str(t) for t in row["productsDiscussed"])
        name = str(row.get("respondentName") or "").strip() or "A respondent"
        prepared.append((name, _tokens(text)))

    out: list[SwotSupport] = []
    for row in swot:
        point = str(row.get("point") or "").strip()
        if not point:
            continue
        kind = str(row.get("kind") or "")
        own = bool(str(row.get("evidence") or "").strip())
        wanted = _tokens(point)
        matches: list[tuple[int, str]] = []
        for name, tokens in prepared:
            shared = len(wanted & tokens)
            if shared >= _SUPPORT_OVERLAP:
                matches.append((shared, name))
        matches.sort(key=lambda m: (-m[0], m[1]))
        out.append(SwotSupport(
            kind=kind,
            point=point,
            supported_by=tuple(name for _shared, name in matches[:5]),
            overlap=matches[0][0] if matches else 0,
            has_own_evidence=own,
        ))
    return out


@dataclass(frozen=True, slots=True)
class TrendCluster:
    """Products that respondents mentioned together, and how often."""

    members: tuple[str, ...]
    mentions: int
    co_occurrences: int


def cluster_products(responses: list[dict[str, Any]], *, minimum_mentions: int = 2,
                     limit: int = 8) -> list[TrendCluster]:
    """Group the products respondents discussed by how often they came up in the same conversation.

    Co-occurrence rather than a topic model, deliberately. It is explainable to the designer whose
    data it is ("these three were mentioned together nine times"), it is stable — the same rows
    give the same clusters every run, which a k-means seeded at random does not — and it runs in
    milliseconds on a handset. A model here would buy nothing that a designer could check.

    Single-link agglomeration over pairs seen at least twice: two products join when they were
    discussed together, and a chain of such pairs becomes one cluster.
    """
    tag_lists: list[list[str]] = []
    for row in responses:
        raw = row.get("productsDiscussed")
        tags = raw if isinstance(raw, list) else str(raw or "").split(",")
        cleaned = sorted({str(t).strip().lower() for t in tags if str(t).strip()})
        if cleaned:
            tag_lists.append(cleaned)

    mentions: Counter[str] = Counter()
    pairs: Counter[tuple[str, str]] = Counter()
    for tags in tag_lists:
        mentions.update(tags)
        for i, a in enumerate(tags):
            for b in tags[i + 1:]:
                pairs[(a, b)] += 1

    # Union-find over the pairs that recur. A pair seen once is two people who happened to say the
    # same two words; at two it is worth showing.
    parent: dict[str, str] = {t: t for t in mentions}

    def find(x: str) -> str:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    joined: Counter[str] = Counter()
    for (a, b), seen in pairs.items():
        if seen < minimum_mentions:
            continue
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[rb] = ra
        joined[find(a)] += seen

    groups: dict[str, list[str]] = {}
    for tag in mentions:
        groups.setdefault(find(tag), []).append(tag)

    clusters = [
        TrendCluster(
            members=tuple(sorted(members)),
            mentions=sum(mentions[m] for m in members),
            co_occurrences=joined.get(root, 0),
        )
        for root, members in groups.items()
        if len(members) > 1 or mentions[members[0]] >= minimum_mentions
    ]
    clusters.sort(key=lambda c: (-c.mentions, -len(c.members), c.members))
    return clusters[:limit]


@dataclass(frozen=True, slots=True)
class MarketFindings:
    """Everything this module concluded, plus what it could not conclude and why."""

    observations: int = 0
    respondent_prices: Distribution | None = None
    competitor_prices: Distribution | None = None
    by_category: dict[str, Distribution] = field(default_factory=dict)
    bands: list[BandVerdict] = field(default_factory=list)
    competitors: list[CompetitorPosition] = field(default_factory=list)
    swot: list[SwotSupport] = field(default_factory=list)
    clusters: list[TrendCluster] = field(default_factory=list)
    group_counts: dict[str, int] = field(default_factory=dict)
    cautions: list[str] = field(default_factory=list)

    @property
    def unsupported_swot(self) -> list[SwotSupport]:
        return [s for s in self.swot if not s.supported]


def collect_observations(responses: list[dict[str, Any]],
                         competitors: list[dict[str, Any]],
                         *, default_category: str = "") -> list[PriceObservation]:
    """Every price in stages 8, tagged with where it came from.

    A respondent's `priceExpectation` carries no category of its own — the survey form asks what
    they would pay, not what for — so it inherits `default_category` when one is known and is
    otherwise pooled under "". Pretending to a category it does not have would put a buyer's answer
    about a stole into the price band for floor coverings.
    """
    out: list[PriceObservation] = []
    for row in responses:
        amount = as_number(row.get("priceExpectation"))
        if amount is None:
            continue
        out.append(PriceObservation(
            amount=amount,
            category=default_category,
            source="RESPONDENT",
            group=str(row.get("respondentGroup") or ""),
            label=str(row.get("respondentName") or "").strip() or "A respondent",
        ))
    for row in competitors:
        amount = as_number(row.get("price"))
        if amount is None:
            continue
        out.append(PriceObservation(
            amount=amount,
            category=str(row.get("category") or ""),
            source="COMPETITOR",
            label=str(row.get("name") or "").strip() or "A competitor product",
        ))
    return out


def analyse(*, responses: list[dict[str, Any]], competitors: list[dict[str, Any]],
            bands: list[dict[str, Any]], swot: list[dict[str, Any]]) -> MarketFindings:
    """The whole of stage 9's Advanced tier, from the rows of stages 8 and 9.

    Every argument is a list of the stage entry `data` dicts exactly as they are stored, so a
    caller has nothing to reshape and the browser and the handset can pass what they already hold.
    """
    observations = collect_observations(responses, competitors)
    respondent_values = [o.amount for o in observations if o.source == "RESPONDENT"]
    competitor_values = [o.amount for o in observations if o.source == "COMPETITOR"]

    by_category: dict[str, Distribution] = {}
    grouped: dict[str, list[float]] = {}
    for observation in observations:
        grouped.setdefault(observation.category, []).append(observation.amount)
    for category, values in grouped.items():
        described = describe(values)
        if described is not None:
            by_category[category] = described

    band_verdicts = [
        judge_band(
            str(row.get("category") or ""),
            row.get("lowPrice"),
            row.get("highPrice"),
            [o for o in observations
             if o.category == str(row.get("category") or "") or not o.category],
        )
        for row in bands
    ]

    group_counts = Counter(
        o.group for o in observations if o.source == "RESPONDENT" and o.group
    )

    cautions: list[str] = []
    if respondent_values and len(respondent_values) < MIN_SAMPLE_FOR_VERDICT:
        cautions.append(
            f"Only {len(respondent_values)} respondent price expectation(s) were recorded. "
            f"The figures below describe what was collected; they are not a market estimate.")
    if group_counts:
        dominant, dominant_count = group_counts.most_common(1)[0]
        total = sum(group_counts.values())
        if total >= MIN_SAMPLE_FOR_QUANTILES and dominant_count / total > 0.8:
            cautions.append(
                f"{dominant_count} of {total} priced responses come from one respondent group "
                f"({dominant}). The price figures describe that group rather than the market.")
    if competitor_values and not respondent_values:
        cautions.append(
            "Competitor prices were recorded but no buyer price expectations were. Shelf prices "
            "say what is charged, not what the buyers surveyed said they would pay.")

    return MarketFindings(
        observations=len(observations),
        respondent_prices=describe(respondent_values),
        competitor_prices=describe(competitor_values),
        by_category=by_category,
        bands=band_verdicts,
        competitors=position_competitors(competitors, observations),
        swot=link_swot_evidence(swot, responses),
        clusters=cluster_products(responses),
        group_counts=dict(group_counts),
        cautions=cautions,
    )


def _distribution_payload(dist: Distribution | None) -> dict[str, Any] | None:
    if dist is None:
        return None
    return {
        "count": dist.count,
        "minimum": round(dist.minimum, 2),
        "maximum": round(dist.maximum, 2),
        "mean": round(dist.mean, 2),
        "p25": round(dist.p25, 2),
        "median": round(dist.median, 2),
        "p75": round(dist.p75, 2),
        "quantilesReported": dist.quantiles_reported,
    }


def market_findings_payload(findings: MarketFindings) -> dict[str, Any]:
    """The wire form, camelCased like every other payload this API returns.

    `cautions` and `unsupportedSwot` are named at the TOP of the object rather than buried inside
    the sections they qualify. A caution that has to be found is a caution that will not be read,
    and the whole value of this analysis rests on a designer seeing "these figures come from one
    respondent group" before they see the figures.
    """
    return {
        "observations": findings.observations,
        "cautions": list(findings.cautions),
        "unsupportedSwot": [
            {"kind": s.kind, "point": s.point} for s in findings.unsupported_swot
        ],
        "respondentPrices": _distribution_payload(findings.respondent_prices),
        "competitorPrices": _distribution_payload(findings.competitor_prices),
        "byCategory": {
            category: _distribution_payload(dist)
            for category, dist in sorted(findings.by_category.items())
        },
        "groupCounts": dict(sorted(findings.group_counts.items())),
        "bands": [
            {
                "category": b.category,
                "declaredLow": b.declared_low,
                "declaredHigh": b.declared_high,
                "evidence": _distribution_payload(b.evidence),
                "inside": b.inside,
                "below": b.below,
                "above": b.above,
                "coverage": round(b.coverage, 4),
                "verdict": b.verdict,
                "message": b.message,
            }
            for b in findings.bands
        ],
        "competitors": [
            {
                "name": c.name,
                "seller": c.seller,
                "category": c.category,
                "price": c.price,
                "percentile": None if c.percentile is None else round(c.percentile, 4),
                "versusMedian": None if c.versus_median is None else round(c.versus_median, 2),
                "note": c.note,
            }
            for c in findings.competitors
        ],
        "swot": [
            {
                "kind": s.kind,
                "point": s.point,
                "supported": s.supported,
                "hasOwnEvidence": s.has_own_evidence,
                "supportedBy": list(s.supported_by),
                "overlap": s.overlap,
            }
            for s in findings.swot
        ],
        "clusters": [
            {"members": list(c.members), "mentions": c.mentions, "coOccurrences": c.co_occurrences}
            for c in findings.clusters
        ],
    }
