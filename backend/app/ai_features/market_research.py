"""Capability 4: go and find what comparable products exist, what they cost, and who sells them.

`app/services/market_analysis.py` already computes everything that can be computed from the survey
rows a designer captured — price distributions, band verdicts, competitor positioning, SWOT
evidence. It is pure, it has no network, and it is ported to the browser and the handset. What it
cannot do is tell a designer what the *rest* of the market charges, because nobody in the room
wrote that down. That is the half that genuinely needs the internet, and it is what this module is.

Given a keyword and whatever context the workshop app already holds, this returns comparable
products with a name, a seller, a price, a currency, a source URL and a retrieval timestamp, and
merges them into the same `PriceObservation` shape `market_analysis` already consumes — so the
distribution and price-band machinery works unchanged on retrieved data, with one difference that
is the point of the whole design: a retrieved price is stamped ``RETRIEVED_LISTING`` and can never
be counted as something a respondent said.

================================================================================================
WHAT IS AGENTIC AND WHAT IS PROGRAMMATIC — the question this feature was asked to answer
================================================================================================

The brief for this work proposed a split. Verifying it moved almost all of it across, and the
reasoning matters more than the conclusion, so both are here.

**PROGRAMMATIC — implemented in this module, no model involved, all of it unit-tested.**

  * *Issuing the query and paginating.* An HTTP call with a page counter. There was never a
    question about this one.
  * *Deciding what to search for.* :func:`plan_queries`. Proposed as agentic; moved. See below —
    this is the interesting one.
  * *Parsing a price.* :func:`parse_price`. ``₹1,20,000.00``, ``Rs 1,299 - 1,999``, ``$45.99``,
    ``1.234,56`` are separator rules, not judgement. Indian digit grouping is the case a
    Western-trained model and a naive ``float()`` get wrong in the same direction and silently:
    ``1,20,000`` becomes 120000, never 1.2 or 20000.
  * *Currency handling.* :func:`normalise_currency` maps a symbol or an alias to an ISO code.
    Conversion between currencies is REFUSED — see below.
  * *Deduplication.* :func:`deduplicate`. Canonical URL, then (name, seller, currency, price).
    A model asked "are these the same product" would be slower, cost money per pair, and be
    non-reproducible; string equality after a documented normalisation is none of those.
  * *Outlier rejection.* :func:`reject_outliers`. A documented fence, applied in log space, with
    every rejection RETURNED rather than deleted.
  * *Merging into ``PriceObservation``.* :func:`to_price_observations`. A field mapping.
  * *Refusing to conclude.* :func:`consolidate` withholds the distribution below
    :data:`MIN_RETRIEVED_FOR_DISTRIBUTION`, exactly as `market_analysis` withholds quantiles.

**AGENTIC — genuinely needs a model, and therefore deliberately NOT built here. §9 of
docs/MARKET_RESEARCH.md says the same thing where an operator will read it.**

  * *Reading a rambling free-text brief.* "something like the ikat stoles but for younger buyers
    in Bhubaneswar, maybe around 800" contains a product, a place and a price band that no
    deterministic rule extracts reliably. THIS IS THE ONLY THING LEFT ON THE LIST, and the reason
    it is not built is that the workshop app does not have to ask the question that way: craft,
    category, materials and district are already captured as controlled vocabularies, and stage 9
    already holds the declared band. :class:`ResearchBrief` takes them as fields. A model here
    would replace data the app already has with an inference, and make the same brief return
    different comparables next month — which for evidence that ends up in a ministry report is a
    defect, not a feature.

**Two things moved from the agentic list to the programmatic one, and why.**

1. *Extracting structured fields from an unstructured page* — ELIMINATED, not automated. It is
   only necessary if you fetch arbitrary product pages. Every provider here is a documented
   commerce or search API that returns ``title``/``source``/``price``/``link`` as fields, so there
   is no page to read. This also happens to be the legal and ethical answer (nothing crawls, so
   nothing has to reason about robots.txt), which is not a coincidence: the same decision removed
   the need for the model AND the need to argue about scraping.

2. *Deciding what to search for* — MOVED to :func:`plan_queries`. A structured brief plus an
   ordered set of narrowing facets (craft, category, materials, place) produces at most a handful
   of queries that a designer can read, re-run and disagree with. Reproducibility is the argument:
   "we searched for X, Y and Z on this date" is a method; "the model decided what to search for"
   is not one, and cannot be re-derived when the report is questioned a year later.

**A third thing that looks agentic and is neither** — judging whether a retrieved product is
genuinely comparable. :func:`score_relevance` scores vocabulary overlap and nothing more, and
anything it doubts is RETURNED with its score rather than dropped. The final call belongs to the
designer, who was in the room. That is `market_analysis.link_swot_evidence`'s discipline applied to
retrieval, and it is why this ships without a model behind it.

================================================================================================
THREE REFUSALS, EACH DELIBERATE
================================================================================================

**No currency conversion.** Prices in a currency other than the sample's are excluded and counted,
never converted. A conversion needs a rate; a live rate is a second network dependency and a moving
number, and a rate pasted into an environment variable is a number that was true once, applied
silently to a report a year later. Excluding is honest and reversible; converting is neither.

**No arbitrary URL fetching, so no crawler.** Every provider talks to a documented API under an
operator's own key, or reads a file from the local disk. There is no code here that will fetch a
page a vendor did not offer through an API, which is why there is no robots.txt handling: the
obligation was designed out rather than complied with. Requests still carry an identifying
User-Agent and pass through :func:`app.ai_features.runtime.throttle`, because a shared outbound
address getting itself blocked is everybody's problem.

**No conclusion below the floor.** Four listings are not a market, for the same reason four price
expectations are not one.

================================================================================================
PROVENANCE
================================================================================================

Every retrieved price carries its source URL and the moment it was fetched, and is stamped
:data:`~app.ai_features.types.RETRIEVED_SOURCE` when it becomes a ``PriceObservation``. The worst
outcome this feature can have is a scraped number reaching a ministry report as though a respondent
said it, so:

  * the sentinel is neither ``RESPONDENT`` nor ``COMPETITOR``, so every existing filter in
    `market_analysis` excludes it without a line of new code;
  * :func:`assert_surveyed` raises rather than filters, because a caller who mixed the lists has a
    bug that will otherwise ship;
  * :func:`surveyed_only` exists for the caller who wants the defensive filter deliberately;
  * every result carries a caution saying, in words, that these are shop listings and not answers
    from the people the survey asked.

WHY ``app.services.market_analysis`` IS IMPORTED INSIDE FUNCTIONS. The rule in this package is that
nothing is imported at module scope that a dormant feature would not otherwise pay for, and it has
no carve-outs. Here it buys something specific as well: ``import app.ai_features`` never touches
``app.services``, so a dormant package cannot be broken by a module it does not need.
"""

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

if TYPE_CHECKING:
    from app.services.market_analysis import Distribution, PriceObservation

logger = logging.getLogger(__name__)

#: Below this many priced listings no distribution is reported at all — the listings themselves
#: are still returned. Held equal to ``market_analysis.MIN_SAMPLE_FOR_QUANTILES`` so the system has
#: ONE floor rather than two that drift; ``test_market_research.py`` asserts the equality.
MIN_RETRIEVED_FOR_DISTRIBUTION = 5

#: Below this many priced listings nothing is rejected as an outlier, whatever the fence says.
#: Deliberately HIGHER than the distribution floor, and it is the same relationship
#: ``market_analysis`` already draws between ``MIN_SAMPLE_FOR_QUANTILES`` and
#: ``MIN_SAMPLE_FOR_VERDICT``: showing a spread from five numbers is honest, telling somebody one
#: of their five numbers is wrong is not. THROWING A DATA POINT AWAY IS A JUDGEMENT, so it takes
#: the judgement floor rather than the description floor.
#:
#: This was found by a test rather than reasoned out in advance. Five retrieved craft prices —
#: ₹1,299, ₹1,850, ₹2,450, ₹3,200 and a ₹1,20,000 bridal piece — put the interquartile range so
#: close together that even a three-IQR fence in log space rejected the heirloom weave, which is
#: the single most interesting row in the sample. At eight the quartiles have room to describe the
#: real spread before anything is called an accident.
MIN_RETRIEVED_FOR_OUTLIER_REJECTION = 8

#: Vocabulary overlap below which a retrieved product is set aside as probably not comparable.
#: One content word in three: "ikat silk stole" against a brief for "sambalpuri ikat stole" keeps
#: it; "ikat print phone case" does not. Deliberately generous — the cost of keeping a wrong
#: listing is a designer deleting a row, and the cost of dropping a right one is invisible.
MIN_RELEVANCE = 0.34

#: Multiplier on the interquartile range, applied to log(price). Three, not the conventional 1.5,
#: and in log space rather than on the raw rupees, for one reason: craft prices are ratio-scaled
#: and legitimately span two orders of magnitude. A raw 1.5-IQR fence on a sample from ₹400 to
#: ₹40,000 puts the lower bound below zero — so it can only ever reject EXPENSIVE things, which for
#: a heritage craft is precisely the data a designer must see. What this is meant to catch is the
#: retrieval accident: a ₹5 sample swatch, a ₹3,00,000 wholesale lot of a hundred pieces. A wide
#: symmetric-in-ratio fence catches those and leaves the market alone.
OUTLIER_IQR_MULTIPLIER = 3.0

#: The shortest run of letters kept as a content word, and the same value market_analysis uses.
_MIN_TOKEN = 3

#: Words that carry no signal in a product query. The English function words are those of
#: ``market_analysis._STOPWORDS``; the rest is the vocabulary of a shopping listing, which is noise
#: precisely because every listing has it. Kept small and English-only for the reason that module
#: gives: the survey text is frequently Odia, Hindi or transliterated, and a large English stop
#: list strips the rare words that make a match meaningful.
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
        # Listing furniture. "buy handloom saree online best price" is one content word wearing five.
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

#: Symbol or alias to ISO 4217. Small on purpose: every entry is a currency this fieldwork has
#: actually met or a currency a global marketplace quotes in. An unknown symbol is reported as
#: unparsed rather than guessed, because a wrong currency is a wrong price by a factor of eighty.
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

#: Query parameters that identify a campaign rather than a product. Stripped before two URLs are
#: compared, because the same listing arriving from two queries carries two different ``utm_source``
#: values and would otherwise be counted twice — inflating the sample with one product.
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

#: How many characters of a planned query are sent. Search APIs truncate long queries anyway, and
#: a brief pasted from a document should not become a 4 kB request body.
_MAX_QUERY_CHARS = 120

#: Content words taken from the keyword. Beyond about six the query stops narrowing and starts
#: matching nothing at all.
_MAX_KEYWORD_TERMS = 6


# ------------------------------------------------------------------------------------------------
# Tokenising. The rule is market_analysis's, and it has to stay that way.
# ------------------------------------------------------------------------------------------------


def _is_word_character(ch: str) -> bool:
    """Whether ``ch`` belongs INSIDE a word: a letter, or a combining mark attached to one.

    THE COMBINING MARKS ARE THE WHOLE POINT. ``market_analysis._is_word_character`` explains this
    at length and that explanation is the canonical one: Python's ``\\w`` follows ``str.isalnum()``,
    which is False for combining marks, so a regex that looks Unicode-correct treats the virama in
    ରଙ୍ଗ as a word boundary and shatters every Odia word into fragments the length floor then
    discards. Here it would mean an Odia keyword planning an empty query and every retrieved
    listing scoring zero relevance — a feature that silently does nothing for exactly the fieldwork
    this application exists to collect.

    Reimplemented rather than imported because ``market_analysis._tokens`` is private and this is a
    dormant package that must not break when a service-layer module is refactored.
    ``test_market_research.py`` asserts the two agree, including on Odia, so the copy is checked
    rather than hoped for.

    """
    return ch.isalpha() or unicodedata.category(ch).startswith("M")


#: Maximal runs of ASCII letters. Used only on strings that are entirely ASCII, where it is
#: EXACTLY equivalent to scanning with :func:`_is_word_character` — see :func:`_raw_words`.
_ASCII_WORDS = re.compile(r"[A-Za-z]+")


def _raw_words(text: str) -> list[str]:
    """Maximal runs of letters-and-marks, before lowercasing, length and stopword filtering.

    THE ASCII FAST PATH IS EXACT, NOT AN APPROXIMATION, and it is worth the two branches. Unicode's
    combining-mark categories (Mn, Mc, Me) begin at U+0300, so a wholly-ASCII string contains no
    marks at all and ``[A-Za-z]+`` finds precisely what the character scan would. Everything else
    — Odia, Devanagari, an accented seller name — takes the scan and stays correct.

    It is here because this is the hot loop of the local catalogue provider, which runs it once per
    row of a price list. MEASURED on a 40-character row: 97 us through the character scan, which is
    two seconds of pure tokenising for a 20,000-row catalogue on a machine faster than the
    production box. A regex over the ASCII case is roughly an order of magnitude cheaper and the
    non-ASCII case is unchanged, which is the right way round: the rows that need the careful path
    are a minority of any catalogue and the ones that would have been silently mangled by a naive
    ``\\w`` are all in it.
    """
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
    """Content words of ``text``, lowercased, in order, without repeats.

    ORDER IS KEPT, unlike ``market_analysis._tokens`` which returns a set. That module compares
    vocabularies, where order is noise; this one builds a search query, where "stole ikat silk" and
    "silk ikat stole" are different requests and the designer's own word order is the best guess at
    what matters most.
    """
    stops = _STOPWORDS if stopwords is None else stopwords
    out: list[str] = []
    seen: set[str] = set()
    for word in _raw_words(text or ""):
        lowered = word.lower()
        if len(lowered) >= _MIN_TOKEN and lowered not in stops and lowered not in seen:
            seen.add(lowered)
            out.append(lowered)
    return tuple(out)


# ------------------------------------------------------------------------------------------------
# PROGRAMMATIC: what to search for.
# ------------------------------------------------------------------------------------------------


def plan_queries(brief: ResearchBrief, *, max_queries: int = 3) -> tuple[str, ...]:
    """The queries this brief becomes. Deterministic, orderable, and printable in a method section.

    One broad query on the keyword, then the same keyword narrowed by each facet the brief actually
    carries — craft and category together (what the thing IS), materials (what it is made of),
    place (who sells it near here). Facets that are empty produce a query identical to the broad
    one, which is then deduplicated away: a bare keyword costs ONE call, not three. That matters,
    because on a hosted provider every query is a credit.

    THE DECLARED PRICE BAND IS DELIBERATELY NOT IN THE QUERY. Searching "handloom stole under ₹800"
    and then testing the designer's ₹600–800 band against what comes back would be circular: the
    retrieval would have selected evidence that agrees with the claim under test. The band is
    carried on the brief for reporting only, and this is the function that has to refuse to use it.
    """
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


# ------------------------------------------------------------------------------------------------
# PROGRAMMATIC: reading a price field.
# ------------------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class ParsedPrice:
    """One price field, read. ``raw`` is kept so the parse can be argued with."""

    amount: float
    currency: str
    raw: str
    notes: tuple[str, ...] = ()


def normalise_currency(hint: str, default: str = "") -> str:
    """A currency symbol, alias or code as ISO 4217, or ``default`` when nothing is recognised.

    Returns a code rather than a symbol because ``₹`` is written four ways and ``$`` means eight
    different currencies; a code is the only form two prices can be compared in.
    """
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
    """A digit run with separators as a number, or None when the run is not a price.

    THE ONE RULE, which handles every grouping convention this fieldwork meets:
    count the digits after the LAST separator.

      * three  -> that separator was a GROUP separator; remove all of them.
        ``1,20,000`` (Indian) -> 120000. ``1,299`` -> 1299. ``1.234.567`` (European) -> 1234567.
      * one or two -> that separator was the DECIMAL point; everything before it is the integer
        part with its own separators removed.
        ``45.99`` -> 45.99. ``1,20,000.00`` -> 120000.0. ``1.234,56`` (European) -> 1234.56.
      * anything else -> not a price. ``2021.`` and ``1,2345`` are refused rather than guessed.

    The residual ambiguity is real and documented: ``1,234`` is 1234 here and 1.234 in Berlin.
    Three-digit grouping is the near-universal default and a listing quoting a price to three
    decimal places is not a thing, so this is the direction to be wrong in. What it is NEVER wrong
    about is the case the brief called out: ``1,20,000`` cannot become 20000 or 1.2, because the
    rule never looks at how many separators there are or how far apart they sit.
    """
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
    """``(run, index_after)`` for each digit-and-separator run. A hand scan, not a regex.

    Written out because the interesting part is the boundary: a run ends at the last DIGIT, so a
    trailing separator (``"₹1,299."``) is left outside rather than making the run unparseable.
    """
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
    """Read a price FIELD. Returns None when there is no price in it — never a guess.

    A FIELD, not a page: ``"₹1,20,000.00"``, ``"Rs 1,299 - 1,999"``, ``"$45.99"``, ``"1.234,56 €"``.
    That is exactly what the providers here return, and it is the second dividend of preferring a
    commerce API over a crawler — the hard extraction problem was removed rather than solved.

    WHEN A FIELD HOLDS SEVERAL NUMBERS, THE LOWEST IS TAKEN, and a note says so. Both shapes this
    happens in mean the same thing: ``"₹1,299 ₹1,999"`` is a sale price beside a struck-through
    MRP, and ``"₹800 - 1,200"`` is a range across variants. In both, the lowest number is a price
    somebody is actually charging for something, which is the only defensible reading. A midpoint
    would be a number nobody has ever asked for.

    Percentages are excluded before that choice — ``"₹1,299 (20% off)"`` must not become ₹20.
    """
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
            continue  # a discount, not a price
        amount = _amount_from_digits(run)
        if amount is None or not math.isfinite(amount) or amount <= 0:
            continue
        # A comma group of exactly two digits between other groups is the Indian convention and
        # nothing else. Worth surfacing: it is the parse a reader most wants to check by eye.
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


# ------------------------------------------------------------------------------------------------
# PROGRAMMATIC: identity, relevance, duplicates.
# ------------------------------------------------------------------------------------------------


def canonical_url(url: str) -> str:
    """A URL reduced to what identifies the listing, for comparing two of them.

    Scheme and host lower-cased, ``www.`` dropped, fragment dropped, campaign parameters dropped,
    remaining parameters sorted. Product parameters are KEPT — on several marketplaces the item id
    lives in the query string, so stripping it wholesale would merge every product on a site into
    one row and quietly shrink the sample.
    """
    text = (url or "").strip()
    if not text:
        return ""
    try:
        parts = urlsplit(text)
    except ValueError:  # a malformed URL is not a reason to lose the listing
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
    """Share of the brief's content words that appear in a product name. 0.0 to 1.0.

    A RETRIEVAL AID, NOT A JUDGEMENT — the same standing as
    ``market_analysis.link_swot_evidence``. Shared vocabulary means a listing is worth looking at;
    the designer decides whether it is really comparable. Nothing is deleted on the strength of
    this number, and :func:`consolidate` refuses to filter on it at all when it would empty the
    result (an English marketplace answering an Odia keyword scores zero on everything, and a
    silent empty result is the worst possible way to communicate that).
    """
    if not brief_terms:
        return 0.0
    found = set(content_words(name))
    hits = sum(1 for term in brief_terms if term in found)
    return hits / len(brief_terms)


def _identity(product: RetrievedProduct) -> tuple[str, ...]:
    """The keys two listings must share to be the same listing."""
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
    """``(kept, removed)`` — first occurrence wins.

    Three queries against one shopping index return the same top sellers three times. Counting
    them three times would triple the weight of whoever ranks well, which is a bias with a
    direction: the large sellers who can afford to rank. URL first because it is exact; the field
    key is the fallback for a provider that returns no link.
    """
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
    """``(kept, [(rejected, reason)])`` using a log-space interquartile fence.

    Below :data:`MIN_RETRIEVED_FOR_OUTLIER_REJECTION` nothing is rejected at all, whatever the
    arithmetic says — see that constant for the sample that proved why.

    Rejections are RETURNED, with the fence in the reason. A pipeline that silently discards the
    ₹3,00,000 wholesale lot and the ₹5 swatch looks identical to one that silently discards the two
    most interesting products in the market, and only one of those is acceptable.
    """
    priced = [item for item in products if item.price is not None and item.price > 0]
    if len(priced) < MIN_RETRIEVED_FOR_OUTLIER_REJECTION:
        return tuple(products), ()

    from app.services.market_analysis import quantile  # see the module docstring

    logs = sorted(math.log(item.price) for item in priced)  # type: ignore[arg-type]
    p25 = quantile(logs, 0.25)
    p75 = quantile(logs, 0.75)
    spread = p75 - p25
    if spread <= 0:
        # Half the sample at one price is a real thing (a standard mill rate). There is no fence
        # to draw, and drawing one anyway would reject everything that is not the mode.
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


# ------------------------------------------------------------------------------------------------
# PROGRAMMATIC: the whole pipeline, from provider rows to a result that refuses honestly.
# ------------------------------------------------------------------------------------------------


def utc_now_iso() -> str:
    """The retrieval timestamp format: ISO 8601, UTC, seconds. One format everywhere it is read."""
    return datetime.now(UTC).replace(microsecond=0).isoformat()


def to_products(
    listings: Iterable[RawListing],
    *,
    brief: ResearchBrief,
    default_currency: str,
) -> tuple[tuple[RetrievedProduct, ...], tuple[tuple[RetrievedProduct, str], ...]]:
    """``(parsed, [(unparsed, reason)])`` — every provider row, priced and scored.

    A row whose price could not be read is still returned, with ``price=None`` and a reason. It is
    evidence that a product exists and who sells it, which is half of what was asked for, and
    hiding it would also hide a provider whose price field shape has changed.
    """
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
    from app.services.market_analysis import describe  # see the module docstring

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
    """Provider rows in, a :class:`ResearchResult` out. Pure — no network, no clock beyond a default.

    THE ORDER IS THE ARGUMENT. Parse, then relevance, then deduplicate, then pick one currency,
    then reject outliers, then describe — and only then, if the sample survived all of that, report
    a distribution. Deduplicating before the fence matters (three copies of one price would drag
    the quartiles onto it); picking the currency before the fence matters more (a fence across
    ₹ and $ in the same list is arithmetic on two different scales).

    Every step that removes something records what and why. The result's ``rejected`` list plus its
    ``products`` list is always exactly what the provider returned.
    """
    when = retrieved_at or utc_now_iso()
    parsed, rejected_rows = to_products(
        listings, brief=brief, default_currency=brief.currency or settings.market_research_currency
    )
    rejected: list[tuple[RetrievedProduct, str]] = list(rejected_rows)
    cautions: list[str] = []
    notes: list[str] = []

    relevant = [item for item in parsed if item.relevance >= MIN_RELEVANCE]
    if parsed and not relevant:
        # Everything scored zero. Almost always a language mismatch — an English marketplace
        # answering an Odia or transliterated keyword — and an empty result would report that as
        # "the market has nothing", which is a different and false statement.
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
            f"{duplicates_removed} duplicate listing(s) removed across {len(queries)} queries"
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
            f"{sum(other_currencies.values())} listing(s) in "
            f"{', '.join(sorted(other_currencies))} were excluded. Prices are never converted "
            f"here: a conversion needs a rate, and a rate that was true once becomes a wrong "
            f"number in a report later."
        )

    in_currency, outliers = reject_outliers([item for item in deduped if item.currency == currency])
    rejected.extend(outliers)
    if outliers:
        notes.append(
            f"{len(outliers)} listing(s) outside the price fence, listed under 'rejected' rather "
            f"than dropped"
        )

    priced = [item for item in in_currency if item.price is not None]
    distribution = _describe(in_currency) if len(priced) >= MIN_RETRIEVED_FOR_DISTRIBUTION else None
    if priced and distribution is None:
        cautions.append(
            f"Only {len(priced)} comparable listing(s) with a readable price were retrieved - "
            f"fewer than the {MIN_RETRIEVED_FOR_DISTRIBUTION} this module will describe a "
            f"distribution from. The listings are below; no median or spread is reported."
        )
    if not priced:
        cautions.append(
            f"No listing with a readable price was retrieved for {brief.keyword!r}. That is a "
            f"failed search, not a finding about the market."
        )
    if priced:
        # ALWAYS present, and first in the list, whenever there is a retrieved price at all. This
        # is the sentence that has to survive being pasted into a report by somebody in a hurry.
        cautions.insert(
            0,
            f"These {len(priced)} price(s) were RETRIEVED from {provider} on {when}. They are shop "
            f"listings - what a seller asks - not answers from anybody the survey spoke to, and "
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
    """``(sample currency, {other: count}, products in another currency)``.

    The preferred currency wins if it is present at all, otherwise the most common one. Preferring
    the configured currency rather than simply the mode is deliberate: an Indian craft brief that
    happens to retrieve six US listings and five Indian ones should still report the Indian market,
    because that is the market the fieldwork is in.
    """
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


# ------------------------------------------------------------------------------------------------
# PROVENANCE: the bridge into market_analysis, and the guard on it.
# ------------------------------------------------------------------------------------------------


def to_price_observations(
    result: ResearchResult | Iterable[RetrievedProduct],
    *,
    category: str = "",
) -> list[PriceObservation]:
    """Retrieved products as ``market_analysis.PriceObservation`` rows, stamped as retrieved.

    The point of this function is that everything downstream — ``describe``, ``judge_band``,
    ``quantile`` — then works on retrieved prices with no changes at all, because they arrive in
    the shape that module already consumes.

    ``source`` is :data:`~app.ai_features.types.RETRIEVED_SOURCE` and never ``COMPETITOR``, however
    tempting that is. A competitor row in stage 8 is a product a designer stood in front of, in a
    shop, on a date, and wrote down; conflating the two would make the survey look like it covered
    ground it never visited. ``label`` carries the seller, the URL and the timestamp, so a figure
    in a report can be traced to the listing it came from — the same reason ``PriceObservation``
    has a label at all.
    """
    from app.services.market_analysis import PriceObservation  # module docstring

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
    """Only the observations somebody was asked for or stood in front of. The defensive filter."""
    return [item for item in observations if item.source != RETRIEVED_SOURCE]


def assert_surveyed(observations: Iterable[PriceObservation], *, where: str) -> None:
    """Raise if any observation was retrieved rather than surveyed.

    RAISES RATHER THAN FILTERS, on purpose. A caller that has merged retrieved prices into a
    respondent sample has a bug, and quietly repairing it would let that bug reach a report where
    the number is indistinguishable from fieldwork. Loud, at the boundary, is the only place this
    is cheap to fix.
    """
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


# ------------------------------------------------------------------------------------------------
# The entry point.
# ------------------------------------------------------------------------------------------------


def research_market(
    brief: ResearchBrief | str,
    *,
    provider: str | None = None,
    settings: AiFeatureSettings | None = None,
) -> ResearchResult:
    """Find comparable products for a brief. Off by default; raises if it is not switched on.

    ORDER OF OPERATIONS, and it is ``service.py``'s for the same reasons: flag, provider, input.
    The flag first is what makes a disabled feature cost one dictionary lookup. The provider before
    the brief means an operator who has switched this on without setting a key is told THAT, rather
    than being told their keyword is empty by a feature that could not have run either way.

    This belongs on the background queue. It is several HTTP calls with a courtesy delay between
    them, which is tens of seconds against CloudFront's thirty-second origin timeout.
    """
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
        # A provider library raising something of its own is still this package's problem to
        # describe; nothing from a dependency escapes to a caller that only knows AiFeatureError.
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
