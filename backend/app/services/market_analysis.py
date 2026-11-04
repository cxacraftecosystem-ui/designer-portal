from __future__ import annotations

import math
import unicodedata
from collections import Counter
from dataclasses import dataclass, field
from typing import Any

from app.services.text_format import by_count

MIN_SAMPLE_FOR_QUANTILES = 5

MIN_SAMPLE_FOR_VERDICT = 8

NARROW_COVERAGE = 0.55

_STOPWORDS = frozenset(
    {
        "and",
        "are",
        "but",
        "for",
        "from",
        "has",
        "have",
        "its",
        "not",
        "our",
        "that",
        "the",
        "their",
        "there",
        "they",
        "this",
        "was",
        "were",
        "will",
        "with",
        "you",
        "more",
        "most",
        "some",
        "such",
        "than",
        "then",
        "these",
        "those",
        "can",
        "could",
        "would",
        "should",
        "may",
        "might",
        "also",
        "very",
        "much",
        "many",
    }
)

_MIN_TOKEN = 3


def _is_word_character(ch: str) -> bool:
    return ch.isalpha() or unicodedata.category(ch).startswith("M")


def _tokens(text: str) -> set[str]:
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
    amount: float
    category: str = ""
    source: str = "RESPONDENT"
    group: str = ""
    label: str = ""


@dataclass(frozen=True, slots=True)
class Distribution:
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


def _count(count: int, noun: str) -> str:
    return f"{count} {noun}" if count == 1 else f"{count} {noun}s"


def _were(count: int) -> str:
    return "was" if count == 1 else "were"


def _observations(count: int) -> str:
    return f"{_count(count, 'price observation')} {'exists' if count == 1 else 'exist'}"


def _band_message(
    verdict: str,
    category: str,
    low: float,
    high: float,
    dist: Distribution,
    inside: int,
    below: int,
    above: int,
) -> str:
    label = category or "this category"
    if verdict == "SOUND":
        return (
            f"The band ₹{low:,.0f}–{high:,.0f} covers {inside} of {dist.count} price "
            f"observations for {label}; the median observed is ₹{dist.median:,.0f}."
        )
    if verdict == "LOW":
        return (
            f"The band ₹{low:,.0f}–{high:,.0f} sits below the evidence for {label}: "
            f"{above} of {dist.count} observations are above it, and the median observed is "
            f"₹{dist.median:,.0f}. The prototypes may be priced under what buyers said."
        )
    if verdict == "HIGH":
        return (
            f"The band ₹{low:,.0f}–{high:,.0f} sits above the evidence for {label}: "
            f"{below} of {dist.count} observations are below it, and the median observed is "
            f"₹{dist.median:,.0f}."
        )
    return (
        f"The band ₹{low:,.0f}–{high:,.0f} covers only {inside} of {dist.count} observations "
        f"for {label} ({below} below, {above} above; median ₹{dist.median:,.0f}). "
        f"Widening it, or splitting the category, would match what was recorded."
    )


def judge_band(
    category: str, low: Any, high: Any, observations: list[PriceObservation]
) -> BandVerdict:
    declared_low = as_number(low)
    declared_high = as_number(high)
    if declared_low is not None and declared_high is not None and declared_low > declared_high:
        declared_low, declared_high = declared_high, declared_low

    values = [o.amount for o in observations]
    dist = describe(values)

    if dist is None:
        return BandVerdict(
            category,
            declared_low,
            declared_high,
            None,
            0,
            0,
            0,
            0.0,
            "NO_EVIDENCE",
            f"No price was recorded in stage 8 for {category or 'this category'}, "
            f"so this band cannot be checked against the survey.",
        )

    if declared_low is None or declared_high is None:
        return BandVerdict(
            category,
            declared_low,
            declared_high,
            dist,
            0,
            0,
            0,
            0.0,
            "NO_EVIDENCE",
            f"{dist.count} price {by_count(dist.count, 'observation exists', 'observations exist')} for "
            f"{category or 'this category'}, but the band itself is incomplete.",
        )

    inside = sum(1 for v in values if declared_low <= v <= declared_high)
    below = sum(1 for v in values if v < declared_low)
    above = sum(1 for v in values if v > declared_high)
    coverage = inside / dist.count

    if dist.count < MIN_SAMPLE_FOR_VERDICT:
        return BandVerdict(
            category,
            declared_low,
            declared_high,
            dist,
            inside,
            below,
            above,
            coverage,
            "UNVERIFIABLE",
            f"Only {dist.count} price {by_count(dist.count, 'observation was', 'observations were')} recorded for "
            f"{category or 'this category'} — too few to judge the band against. "
            f"{inside} of them fall inside it.",
        )

    if coverage >= NARROW_COVERAGE:
        verdict = "SOUND"
    elif above > below * 2:
        verdict = "LOW"
    elif below > above * 2:
        verdict = "HIGH"
    else:
        verdict = "NARROW"

    return BandVerdict(
        category,
        declared_low,
        declared_high,
        dist,
        inside,
        below,
        above,
        coverage,
        verdict,
        _band_message(verdict, category, declared_low, declared_high, dist, inside, below, above),
    )


@dataclass(frozen=True, slots=True)
class CompetitorPosition:
    name: str
    seller: str
    category: str
    price: float
    percentile: float | None
    versus_median: float | None
    note: str


def position_competitors(
    competitors: list[dict[str, Any]], observations: list[PriceObservation]
) -> list[CompetitorPosition]:
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

        sample = by_category.get(category) or []
        matched = len(sample) >= MIN_SAMPLE_FOR_QUANTILES
        if not matched:
            sample = pooled
        if len(sample) < MIN_SAMPLE_FOR_QUANTILES:
            out.append(
                CompetitorPosition(
                    name,
                    seller,
                    category,
                    price,
                    None,
                    None,
                    f"₹{price:,.0f}. Too few buyer price expectations were recorded in the survey to "
                    f"place this against them.",
                )
            )
            continue

        at_or_below = sum(1 for v in sample if v <= price)
        percentile = at_or_below / len(sample)
        median = quantile(sample, 0.5)
        whose = (
            f"the {len(sample)} buyers asked about {category}"
            if matched
            else f"all {len(sample)} buyers asked"
        )
        comparison = "above" if percentile >= 0.5 else "below"
        share = percentile if percentile >= 0.5 else 1 - percentile
        out.append(
            CompetitorPosition(
                name,
                seller,
                category,
                price,
                percentile,
                price - median,
                f"₹{price:,.0f} is {comparison} what {share * 100:.0f}% of {whose} said they would "
                f"pay (their median: ₹{median:,.0f}).",
            )
        )
    return out


@dataclass(frozen=True, slots=True)
class SwotSupport:
    kind: str
    point: str
    supported_by: tuple[str, ...] = ()
    overlap: int = 0
    has_own_evidence: bool = False

    @property
    def supported(self) -> bool:
        return self.has_own_evidence or bool(self.supported_by)


_SUPPORT_OVERLAP = 2


def link_swot_evidence(
    swot: list[dict[str, Any]], responses: list[dict[str, Any]]
) -> list[SwotSupport]:
    prepared = []
    for row in responses:
        text = " ".join(str(row.get(k) or "") for k in ("response", "productsDiscussed", "place"))
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
        out.append(
            SwotSupport(
                kind=kind,
                point=point,
                supported_by=tuple(name for _shared, name in matches[:5]),
                overlap=matches[0][0] if matches else 0,
                has_own_evidence=own,
            )
        )
    return out


@dataclass(frozen=True, slots=True)
class TrendCluster:
    members: tuple[str, ...]
    mentions: int
    co_occurrences: int


def cluster_products(
    responses: list[dict[str, Any]], *, minimum_mentions: int = 2, limit: int = 8
) -> list[TrendCluster]:
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
            for b in tags[i + 1 :]:
                pairs[(a, b)] += 1

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


def collect_observations(
    responses: list[dict[str, Any]],
    competitors: list[dict[str, Any]],
    *,
    default_category: str = "",
) -> list[PriceObservation]:
    out: list[PriceObservation] = []
    for row in responses:
        amount = as_number(row.get("priceExpectation"))
        if amount is None:
            continue
        out.append(
            PriceObservation(
                amount=amount,
                category=default_category,
                source="RESPONDENT",
                group=str(row.get("respondentGroup") or ""),
                label=str(row.get("respondentName") or "").strip() or "A respondent",
            )
        )
    for row in competitors:
        amount = as_number(row.get("price"))
        if amount is None:
            continue
        out.append(
            PriceObservation(
                amount=amount,
                category=str(row.get("category") or ""),
                source="COMPETITOR",
                label=str(row.get("name") or "").strip() or "A competitor product",
            )
        )
    return out


def analyse(
    *,
    responses: list[dict[str, Any]],
    competitors: list[dict[str, Any]],
    bands: list[dict[str, Any]],
    swot: list[dict[str, Any]],
) -> MarketFindings:
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
            [
                o
                for o in observations
                if o.category == str(row.get("category") or "") or not o.category
            ],
        )
        for row in bands
    ]

    group_counts = Counter(o.group for o in observations if o.source == "RESPONDENT" and o.group)

    cautions: list[str] = []
    if respondent_values and len(respondent_values) < MIN_SAMPLE_FOR_VERDICT:
        cautions.append(
            f"Only {len(respondent_values)} respondent price "
            f"{by_count(len(respondent_values), 'expectation was', 'expectations were')} recorded. "
            f"The figures below describe what was collected; they are not a market estimate."
        )
    if group_counts:
        dominant, dominant_count = group_counts.most_common(1)[0]
        total = sum(group_counts.values())
        if total >= MIN_SAMPLE_FOR_QUANTILES and dominant_count / total > 0.8:
            cautions.append(
                f"{dominant_count} of {total} priced responses come from one respondent group "
                f"({dominant}). The price figures describe that group rather than the market."
            )
    if competitor_values and not respondent_values:
        cautions.append(
            "Competitor prices were recorded but no buyer price expectations were. Shelf prices "
            "say what is charged, not what the buyers surveyed said they would pay."
        )

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
    return {
        "observations": findings.observations,
        "cautions": list(findings.cautions),
        "unsupportedSwot": [{"kind": s.kind, "point": s.point} for s in findings.unsupported_swot],
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
