from __future__ import annotations

import logging
import math
import re
import unicodedata
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import TYPE_CHECKING, Any
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

from app.ai_features import registry
from app.ai_features.errors import (
    AiFeatureError,
    FeatureDisabled,
    InvalidBrief,
    ProvenanceViolation,
    ProviderFailed,
)
from app.ai_features.runtime import Deadline
from app.ai_features.settings import AiFeatureSettings, enable_var, get_ai_settings
from app.ai_features.types import (
    RETRIEVED_SOURCE,
    Capability,
    RawListing,
    ResearchBrief,
    ResearchResult,
    RetrievedProduct,
)
from app.services.text_format import by_count, count_noun

if TYPE_CHECKING:
    from app.services.market_analysis import Distribution, PriceObservation

logger = logging.getLogger(__name__)

MIN_RETRIEVED_FOR_DISTRIBUTION = 5

MIN_RETRIEVED_FOR_OUTLIER_REJECTION = 8

MIN_RELEVANCE = 0.34

OUTLIER_IQR_MULTIPLIER = 3.0

_MIN_TOKEN = 3

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
        "buy",
        "online",
        "shop",
        "price",
        "prices",
        "best",
        "top",
        "new",
        "sale",
        "offer",
        "offers",
        "free",
        "delivery",
        "shipping",
        "discount",
        "off",
        "com",
        "www",
    }
)

_CURRENCY_ALIASES: dict[str, str] = {
    "₹": "INR",
    "₨": "INR",
    "rs": "INR",
    "rs.": "INR",
    "inr": "INR",
    "rupees": "INR",
    "$": "USD",
    "us$": "USD",
    "usd": "USD",
    "€": "EUR",
    "eur": "EUR",
    "£": "GBP",
    "gbp": "GBP",
    "¥": "JPY",
    "jpy": "JPY",
    "a$": "AUD",
    "aud": "AUD",
    "c$": "CAD",
    "cad": "CAD",
    "aed": "AED",
    "sgd": "SGD",
}

_TRACKING_PARAMS = frozenset(
    {
        "utm_source",
        "utm_medium",
        "utm_campaign",
        "utm_term",
        "utm_content",
        "utm_id",
        "gclid",
        "fbclid",
        "msclkid",
        "srsltid",
        "ref",
        "ref_",
        "tag",
        "psc",
        "th",
        "_encoding",
        "sr",
        "qid",
        "keywords",
        "sprefix",
        "crid",
        "linkcode",
        "creative",
        "camp",
    }
)

_MAX_QUERY_CHARS = 120

_MAX_KEYWORD_TERMS = 6



def _is_word_character(ch: str) -> bool:
    return ch.isalpha() or unicodedata.category(ch).startswith("M")


_ASCII_WORDS = re.compile(r"[A-Za-z]+")


def _raw_words(text: str) -> list[str]:
    if text.isascii():
        return _ASCII_WORDS.findall(text)
    out: list[str] = []
    current: list[str] = []
    for ch in text + " ":
        if _is_word_character(ch):
            current.append(ch)
        elif current:
            out.append("".join(current))
            current = []
    return out


def content_words(text: str, *, stopwords: frozenset[str] | None = None) -> tuple[str, ...]:
    stops = _STOPWORDS if stopwords is None else stopwords
    out: list[str] = []
    seen: set[str] = set()
    for word in _raw_words(text or ""):
        lowered = word.lower()
        if len(lowered) >= _MIN_TOKEN and lowered not in stops and lowered not in seen:
            seen.add(lowered)
            out.append(lowered)
    return tuple(out)



def plan_queries(brief: ResearchBrief, *, max_queries: int = 3) -> tuple[str, ...]:
    terms = content_words(brief.keyword)[:_MAX_KEYWORD_TERMS]
    if not terms:
        raise InvalidBrief(
            f"the keyword {brief.keyword!r} contains no searchable word",
            capability=Capability.MARKET_RESEARCH,
            remediation=(
                "Give a product to look for — 'sambalpuri ikat stole', not a punctuation mark. "
                "Words shorter than three letters are not searched on their own."
            ),
        )

    facets: list[tuple[str, ...]] = [
        content_words(f"{brief.craft} {brief.category}"),
        tuple(word for material in brief.materials for word in content_words(material))[:3],
        content_words(brief.place),
    ]

    planned: list[str] = []
    for extra in [(), *facets]:
        words = terms + tuple(word for word in extra if word not in terms)
        query = " ".join(words)[:_MAX_QUERY_CHARS].strip()
        if query and query not in planned:
            planned.append(query)
        if len(planned) >= max(1, max_queries):
            break
    return tuple(planned)



@dataclass(frozen=True, slots=True)
class ParsedPrice:
    amount: float
    currency: str
    raw: str
    notes: tuple[str, ...] = ()


def normalise_currency(hint: str, default: str = "") -> str:
    text = (hint or "").strip().lower()
    if not text:
        return default
    if text in _CURRENCY_ALIASES:
        return _CURRENCY_ALIASES[text]
    if len(text) == 3 and text.isalpha():
        return text.upper()
    for symbol, code in _CURRENCY_ALIASES.items():
        if symbol in text:
            return code
    return default


def _amount_from_digits(run: str) -> float | None:
    if not run or not run[0].isdigit() or not run[-1].isdigit():
        return None
    separators = [index for index, ch in enumerate(run) if ch in ".,"]
    if not separators:
        return float(run)
    last = separators[-1]
    tail = run[last + 1 :]
    if not tail.isdigit():
        return None
    head = run[:last].replace(",", "").replace(".", "")
    if not head.isdigit():
        return None
    if len(tail) == 3:
        return float(head + tail)
    if len(tail) in (1, 2):
        return float(f"{head}.{tail}")
    return None


def _digit_runs(text: str) -> list[tuple[str, int]]:
    runs: list[tuple[str, int]] = []
    index = 0
    length = len(text)
    while index < length:
        if not text[index].isdigit():
            index += 1
            continue
        start = index
        end = index
        while index < length and (text[index].isdigit() or text[index] in ".,"):
            if text[index].isdigit():
                end = index
            index += 1
        runs.append((text[start : end + 1], end + 1))
    return runs


def parse_price(text: Any, *, default_currency: str = "") -> ParsedPrice | None:
    raw = str(text or "").strip()
    if not raw:
        return None

    notes: list[str] = []
    currency = normalise_currency(raw, "")
    if not currency:
        if not default_currency:
            return None
        currency = default_currency
        notes.append(f"no currency in {raw!r}; read as {currency}")

    candidates: list[float] = []
    indian_grouping = False
    for run, after in _digit_runs(raw):
        if raw[after : after + 2].lstrip().startswith("%"):
            continue
        amount = _amount_from_digits(run)
        if amount is None or not math.isfinite(amount) or amount <= 0:
            continue
        if any(len(part) == 2 for part in run.split(",")[1:-1]):
            indian_grouping = True
        candidates.append(amount)

    if not candidates:
        return None
    amount = min(candidates)
    if len(set(candidates)) > 1:
        notes.append(
            f"{len(candidates)} numbers in {raw!r}; the lowest ({amount:g}) was taken as the price"
        )
    if indian_grouping:
        notes.append(f"{raw!r} read with Indian digit grouping")
    return ParsedPrice(amount=amount, currency=currency, raw=raw, notes=tuple(notes))



def canonical_url(url: str) -> str:
    text = (url or "").strip()
    if not text:
        return ""
    try:
        parts = urlsplit(text)
    except ValueError:
        return text.lower()
    host = parts.netloc.lower()
    host = host.removeprefix("www.")
    kept = [
        (key, value)
        for key, value in parse_qsl(parts.query, keep_blank_values=True)
        if key.lower() not in _TRACKING_PARAMS
    ]
    path = parts.path.rstrip("/") or "/"
    return urlunsplit((parts.scheme.lower(), host, path, urlencode(sorted(kept)), ""))


def score_relevance(brief_terms: Sequence[str], name: str) -> float:
    if not brief_terms:
        return 0.0
    found = set(content_words(name))
    hits = sum(1 for term in brief_terms if term in found)
    return hits / len(brief_terms)


def _identity(product: RetrievedProduct) -> tuple[str, ...]:
    url = canonical_url(product.source_url)
    if url:
        return ("url", url)
    price = "" if product.price is None else f"{product.price:.2f}"
    return (
        "fields",
        " ".join(sorted(content_words(product.name))),
        " ".join(sorted(content_words(product.seller))),
        product.currency,
        price,
    )


def deduplicate(
    products: Iterable[RetrievedProduct],
) -> tuple[tuple[RetrievedProduct, ...], int]:
    kept: list[RetrievedProduct] = []
    seen: set[tuple[str, ...]] = set()
    removed = 0
    for product in products:
        key = _identity(product)
        if key in seen:
            removed += 1
            continue
        seen.add(key)
        kept.append(product)
    return tuple(kept), removed


def reject_outliers(
    products: Sequence[RetrievedProduct],
) -> tuple[tuple[RetrievedProduct, ...], tuple[tuple[RetrievedProduct, str], ...]]:
    priced = [item for item in products if item.price is not None and item.price > 0]
    if len(priced) < MIN_RETRIEVED_FOR_OUTLIER_REJECTION:
        return tuple(products), ()

    from app.services.market_analysis import quantile

    logs = sorted(math.log(item.price) for item in priced)  # type: ignore[arg-type]
    p25 = quantile(logs, 0.25)
    p75 = quantile(logs, 0.75)
    spread = p75 - p25
    if spread <= 0:
        return tuple(products), ()
    low = math.exp(p25 - OUTLIER_IQR_MULTIPLIER * spread)
    high = math.exp(p75 + OUTLIER_IQR_MULTIPLIER * spread)

    kept: list[RetrievedProduct] = []
    rejected: list[tuple[RetrievedProduct, str]] = []
    for item in products:
        if item.price is None or low <= item.price <= high:
            kept.append(item)
            continue
        rejected.append(
            (
                item,
                f"price {item.price:,.0f} is outside the {low:,.0f}-{high:,.0f} fence "
                f"({OUTLIER_IQR_MULTIPLIER:g} x IQR in log space over {len(priced)} listings)",
            )
        )
    return tuple(kept), tuple(rejected)



def utc_now_iso() -> str:
    return datetime.now(UTC).replace(microsecond=0).isoformat()


def to_products(
    listings: Iterable[RawListing],
    *,
    brief: ResearchBrief,
    default_currency: str,
) -> tuple[tuple[RetrievedProduct, ...], tuple[tuple[RetrievedProduct, str], ...]]:
    terms = content_words(brief.keyword)[:_MAX_KEYWORD_TERMS]
    parsed: list[RetrievedProduct] = []
    unparsed: list[tuple[RetrievedProduct, str]] = []
    for listing in listings:
        price = parse_price(
            listing.price_text,
            default_currency=normalise_currency(listing.currency_hint, default_currency),
        )
        product = RetrievedProduct(
            name=(listing.name or "").strip() or "Unnamed listing",
            seller=(listing.seller or "").strip(),
            price=None if price is None else price.amount,
            currency="" if price is None else price.currency,
            source_url=(listing.url or "").strip(),
            retrieved_at=listing.retrieved_at,
            provider=listing.provider,
            query=listing.query,
            price_text=(listing.price_text or "").strip(),
            relevance=score_relevance(terms, listing.name),
            notes=() if price is None else price.notes,
        )
        if price is None:
            unparsed.append((product, f"no readable price in {product.price_text!r}"))
        else:
            parsed.append(product)
    return tuple(parsed), tuple(unparsed)


def _describe(products: Sequence[RetrievedProduct]) -> Distribution | None:
    from app.services.market_analysis import describe

    return describe([item.price for item in products if item.price is not None])


def consolidate(
    listings: Iterable[RawListing],
    *,
    brief: ResearchBrief,
    provider: str,
    queries: Sequence[str],
    settings: AiFeatureSettings,
    duration_ms: int = 0,
    retrieved_at: str | None = None,
) -> ResearchResult:
    when = retrieved_at or utc_now_iso()
    parsed, rejected_rows = to_products(
        listings, brief=brief, default_currency=brief.currency or settings.market_research_currency
    )
    rejected: list[tuple[RetrievedProduct, str]] = list(rejected_rows)
    cautions: list[str] = []
    notes: list[str] = []

    relevant = [item for item in parsed if item.relevance >= MIN_RELEVANCE]
    if parsed and not relevant:
        cautions.append(
            f"None of the {len(parsed)} listings retrieved shared a word with the brief "
            f"({brief.keyword!r}). They are shown unfiltered; check by hand whether they are "
            f"comparable products at all."
        )
        relevant = list(parsed)
    else:
        for item in parsed:
            if item.relevance < MIN_RELEVANCE:
                rejected.append(
                    (
                        item,
                        f"shares {item.relevance:.0%} of the brief's words (below "
                        f"{MIN_RELEVANCE:.0%}) - probably not a comparable product",
                    )
                )

    deduped, duplicates_removed = deduplicate(relevant)
    if duplicates_removed:
        notes.append(
            f"{count_noun(duplicates_removed, 'duplicate listing')} removed across "
            f"{count_noun(len(queries), 'query', 'queries')}"
        )

    currency, other_currencies, off_currency = _split_by_currency(
        deduped, preferred=brief.currency or settings.market_research_currency
    )
    for item in off_currency:
        rejected.append(
            (
                item,
                f"quoted in {item.currency}, not {currency}; this package does not convert currencies",
            )
        )
    if other_currencies:
        cautions.append(
            f"{count_noun(sum(other_currencies.values()), 'listing')} in "
            f"{', '.join(sorted(other_currencies))} "
            f"{by_count(sum(other_currencies.values()), 'was', 'were')} excluded. Prices are never converted "
            f"here: a conversion needs a rate, and a rate that was true once becomes a wrong "
            f"number in a report later."
        )

    in_currency, outliers = reject_outliers([item for item in deduped if item.currency == currency])
    rejected.extend(outliers)
    if outliers:
        notes.append(
            f"{count_noun(len(outliers), 'listing')} outside the price fence, listed under "
            f"'rejected' rather "
            f"than dropped"
        )

    priced = [item for item in in_currency if item.price is not None]
    distribution = _describe(in_currency) if len(priced) >= MIN_RETRIEVED_FOR_DISTRIBUTION else None
    if priced and distribution is None:
        cautions.append(
            f"Only {count_noun(len(priced), 'comparable listing')} with a readable price "
            f"{by_count(len(priced), 'was', 'were')} retrieved - "
            f"fewer than the {MIN_RETRIEVED_FOR_DISTRIBUTION} this module will describe a "
            f"distribution from. The listings are below; no median or spread is reported."
        )
    if not priced:
        cautions.append(
            f"No listing with a readable price was retrieved for {brief.keyword!r}. That is a "
            f"failed search, not a finding about the market."
        )
    if priced:
        cautions.insert(
            0,
            f"{by_count(len(priced), 'This 1 price was', f'These {len(priced)} prices were')} "
            f"RETRIEVED from {provider} on {when}. "
            f"{by_count(len(priced), 'It is a shop listing', 'They are shop listings')} "
            f"- what a seller asks - not answers from anybody the survey spoke to, and "
            f"they must not be reported as demand, willingness to pay, or a respondent's price "
            f"expectation.",
        )

    return ResearchResult(
        provider=provider,
        brief=brief,
        queries=tuple(queries),
        retrieved_at=when,
        duration_ms=duration_ms,
        products=tuple(in_currency),
        rejected=tuple(rejected),
        duplicates_removed=duplicates_removed,
        currency=currency,
        other_currencies=other_currencies,
        distribution=distribution,
        cautions=tuple(cautions),
        notes=tuple(notes),
    )


def _split_by_currency(
    products: Sequence[RetrievedProduct], *, preferred: str
) -> tuple[str, dict[str, int], list[RetrievedProduct]]:
    counts: dict[str, int] = {}
    for item in products:
        if item.price is None:
            continue
        counts[item.currency] = counts.get(item.currency, 0) + 1
    if not counts:
        return preferred, {}, []
    if preferred in counts:
        chosen = preferred
    else:
        chosen = max(sorted(counts), key=lambda code: counts[code])
    others = {code: count for code, count in counts.items() if code != chosen}
    off = [item for item in products if item.price is not None and item.currency != chosen]
    return chosen, others, off



def to_price_observations(
    result: ResearchResult | Iterable[RetrievedProduct],
    *,
    category: str = "",
) -> list[PriceObservation]:
    from app.services.market_analysis import PriceObservation

    products = result.products if isinstance(result, ResearchResult) else tuple(result)
    resolved_category = category or (
        result.brief.category if isinstance(result, ResearchResult) else ""
    )
    out: list[PriceObservation] = []
    for item in products:
        if item.price is None:
            continue
        seller = item.seller or "unnamed seller"
        where = item.source_url or item.provider
        out.append(
            PriceObservation(
                amount=item.price,
                category=resolved_category,
                source=RETRIEVED_SOURCE,
                group="",
                label=f"{item.name} - {seller} (retrieved {item.retrieved_at} from {where})",
            )
        )
    return out


def surveyed_only(observations: Iterable[PriceObservation]) -> list[PriceObservation]:
    return [item for item in observations if item.source != RETRIEVED_SOURCE]


def assert_surveyed(observations: Iterable[PriceObservation], *, where: str) -> None:
    offending = tuple(
        item.label or f"{item.amount:g}" for item in observations if item.source == RETRIEVED_SOURCE
    )
    if not offending:
        return
    raise ProvenanceViolation(
        f"{len(offending)} retrieved listing(s) reached {where}, which may only see prices "
        f"somebody was asked for",
        offending=offending[:10],
        capability=Capability.MARKET_RESEARCH,
        remediation=(
            "Filter with app.ai_features.market_research.surveyed_only() before this call, and "
            "report retrieved prices in their own section - never as respondent evidence."
        ),
    )



def research_market(
    brief: ResearchBrief | str,
    *,
    provider: str | None = None,
    settings: AiFeatureSettings | None = None,
) -> ResearchResult:
    active = settings or get_ai_settings()
    capability = Capability.MARKET_RESEARCH
    resolved = ResearchBrief(keyword=brief) if isinstance(brief, str) else brief

    if not active.capability_enabled(capability):
        raise FeatureDisabled(
            f"{capability} is switched off",
            capability=capability,
            remediation=(
                f"Set AI_FEATURES_ENABLED=true and {enable_var(capability)}=true, then restart "
                "the process. See docs/MARKET_RESEARCH.md for what each provider then needs."
            ),
        )

    descriptor = registry.resolve(capability, active, requested=provider)
    queries = plan_queries(resolved, max_queries=active.market_research_max_queries)
    deadline = Deadline.start(active.timeout_seconds)
    implementation = registry.load_provider(descriptor, active)

    try:
        listings = implementation.search(queries, resolved, deadline)
    except AiFeatureError:
        raise
    except Exception as exc:
        raise ProviderFailed(
            f"{descriptor.id} raised {type(exc).__name__}: {exc}",
            capability=capability,
            provider=descriptor.id,
            remediation="Check the provider's own logs; this was not an expected failure.",
        ) from exc

    result = consolidate(
        listings,
        brief=resolved,
        provider=descriptor.id,
        queries=queries,
        settings=active,
        duration_ms=deadline.elapsed_ms,
    )
    logger.info(
        "ai_features: market_research via %s in %dms - %d listing(s), %d kept, %d rejected",
        descriptor.id,
        result.duration_ms,
        len(result.products) + len(result.rejected),
        len(result.products),
        len(result.rejected),
    )
    return result
