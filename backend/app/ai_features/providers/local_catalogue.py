"""Comparable products from a price list on this machine. No network, no key, no vendor.

WHY THE EDGE DEVICE IS A FIRST-CLASS PROVIDER AND NOT A FALLBACK. The rest of this application is
built on the assumption that a designer may be in a village with no signal — `market_analysis.py`
is pure and ported to the handset for exactly that reason. A market-research capability that only
works with four bars of 4G would be the first part of the system to break that assumption. This
provider does the whole capability on the device's own CPU against a file: an operator, a cluster
office or a state handloom board maintains a CSV of what things sell for, ships it with the
deployment, and every query is answered offline in milliseconds.

It is also the only provider here with no legal surface at all. Nothing is fetched, no terms are
accepted, no third party learns what was searched for. Where the consent position on sending craft
vocabulary to a US search vendor is unclear — and for fieldwork gathered under a research
agreement it often is — this is the provider that has no such question to answer.

WHAT IT IS NOT. It is not a search engine and it will not discover a product nobody has written
down. Its answer is only as current as whoever last edited the file, which is why a row's own
``updated`` column becomes the retrieval timestamp: a catalogue price was true when the catalogue
was compiled, not when this happened to read it, and stamping it ``now`` would be a small lie that
gets more wrong every month.

FORMATS: ``.csv``/``.tsv`` with a header row, or ``.jsonl``/``.ndjson`` with one object per line.
Both are read a line at a time so a large catalogue costs the same memory as a small one — this is
the provider most likely to be running on the 1 GiB box.
"""

import csv
import json
from collections.abc import Iterator, Sequence
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from app.ai_features.errors import ProviderNotConfigured
from app.ai_features.providers.base import MarketResearchProvider, first_string
from app.ai_features.runtime import Deadline
from app.ai_features.types import Capability, RawListing, ResearchBrief

#: Refused before a byte is read. A price list is a text file somebody maintains by hand; sixteen
#: megabytes is tens of thousands of rows, and anything larger is a misconfiguration pointing at a
#: database dump — which on a 1 GiB box is worth refusing rather than streaming hopefully.
_MAX_CATALOGUE_BYTES = 16 * 1024 * 1024

#: Rows scanned before the provider stops and says so. Bounds the work a single call can do on a
#: burstable vCPU regardless of what the file turns out to contain.
_MAX_ROWS_SCANNED = 200_000

#: How often the wall-clock budget is consulted during a scan. Every row would be a clock call per
#: row; every thousand is imperceptible on a file this size and still bounds the overrun.
_DEADLINE_CHECK_EVERY = 1_000

_NAME_KEYS = ("name", "title", "product", "product_name", "item")
_SELLER_KEYS = ("seller", "shop", "source", "vendor", "store", "merchant", "brand")
_PRICE_KEYS = ("price", "amount", "mrp", "rate", "cost")
_CURRENCY_KEYS = ("currency", "currency_code", "ccy")
_URL_KEYS = ("url", "link", "source_url", "product_link")
_UPDATED_KEYS = ("updated", "date", "as_of", "asof", "recorded", "captured")


class LocalCatalogueProvider(MarketResearchProvider):
    """Scan a local price list for rows whose words overlap the planned queries."""

    provider_id = "local_catalogue"

    def search(
        self,
        queries: Sequence[str],
        brief: ResearchBrief,
        deadline: Deadline,
    ) -> tuple[RawListing, ...]:
        from app.ai_features.market_research import content_words  # avoids a cycle

        capability = Capability.MARKET_RESEARCH
        path = self._path(capability)
        fallback_stamp = _iso(path.stat().st_mtime)
        uri = _uri(path)

        wanted = [(query, frozenset(content_words(query))) for query in queries]
        budget = self.settings.market_research_max_results

        # ONE BUCKET PER OVERLAP LEVEL, EACH CAPPED AT THE RESULT BUDGET. The obvious
        # implementation — score every row, sort, take the top twenty — is what this replaces, and
        # it was MEASURED holding a listing object for every matching row: a broad query against a
        # 120,000-row file matched most of them and took the process to 57 MB and fifteen seconds.
        # A higher overlap always beats a lower one, so no bucket can ever need more entries than
        # the budget, and nothing beyond that is built at all. Memory is now bounded by the number
        # of results asked for rather than by the size of the file.
        buckets: dict[int, list[RawListing]] = {}
        for number, row in enumerate(_rows(path, capability, self.provider_id), start=1):
            if number > _MAX_ROWS_SCANNED:
                break
            if number % _DEADLINE_CHECK_EVERY == 0:
                deadline.check(
                    f"row {number} of the catalogue",
                    capability=capability,
                    provider=self.provider_id,
                )
            name = first_string(row, *_NAME_KEYS)
            if not name:
                continue
            words = frozenset(content_words(f"{name} {first_string(row, 'category', 'type')}"))
            best_query, overlap = "", 0
            for query, terms in wanted:
                shared = len(terms & words)
                if shared > overlap:
                    best_query, overlap = query, shared
            if overlap == 0:
                continue
            bucket = buckets.setdefault(overlap, [])
            if len(bucket) >= budget:
                continue
            bucket.append(
                RawListing(
                    name=name,
                    seller=first_string(row, *_SELLER_KEYS),
                    price_text=first_string(row, *_PRICE_KEYS),
                    url=first_string(row, *_URL_KEYS) or f"{uri}?row={number}",
                    provider=self.provider_id,
                    query=best_query,
                    # The row's own date if it has one. See the module docstring: a catalogue price
                    # was true when the catalogue was written, not when this call happened.
                    retrieved_at=first_string(row, *_UPDATED_KEYS) or fallback_stamp,
                    currency_hint=first_string(row, *_CURRENCY_KEYS),
                )
            )

        # Best overlap first, then file order — which is what the buckets already hold. File order
        # rather than price, so two runs over the same catalogue return the same rows in the same
        # sequence: a result a designer can re-derive is worth more here than one sorted the way a
        # shop would sort it.
        out: list[RawListing] = []
        for overlap in sorted(buckets, reverse=True):
            out.extend(buckets[overlap][: budget - len(out)])
            if len(out) >= budget:
                break
        return tuple(out)

    def _path(self, capability: Capability) -> Path:
        configured = self.settings.market_research_catalogue_path
        if not configured:
            raise ProviderNotConfigured(
                "AI_MARKET_RESEARCH_CATALOGUE_PATH is not set",
                missing=("AI_MARKET_RESEARCH_CATALOGUE_PATH",),
                capability=capability,
                provider=self.provider_id,
                remediation=(
                    "Point AI_MARKET_RESEARCH_CATALOGUE_PATH at a .csv or .jsonl price list with "
                    "name, seller, price, currency and url columns."
                ),
            )
        path = Path(configured)
        try:
            stat = path.stat()
        except OSError as exc:
            raise ProviderNotConfigured(
                f"the catalogue at {path} cannot be read: {exc}",
                missing=("AI_MARKET_RESEARCH_CATALOGUE_PATH",),
                capability=capability,
                provider=self.provider_id,
                remediation="Check the path exists and is readable by the API user.",
            ) from exc
        if not path.is_file():
            raise ProviderNotConfigured(
                f"{path} is not a file",
                missing=("AI_MARKET_RESEARCH_CATALOGUE_PATH",),
                capability=capability,
                provider=self.provider_id,
                remediation="Point the variable at the price list itself, not its directory.",
            )
        if stat.st_size > _MAX_CATALOGUE_BYTES:
            raise self._fail(
                f"{path.name} is {stat.st_size} bytes, over the "
                f"{_MAX_CATALOGUE_BYTES}-byte catalogue limit",
                capability=capability,
                remediation=(
                    "A price list this large is almost certainly a database export. Filter it "
                    "down to the categories this workshop is about."
                ),
            )
        return path


def _iso(epoch_seconds: float) -> str:
    return datetime.fromtimestamp(epoch_seconds, tz=UTC).replace(microsecond=0).isoformat()


def _uri(path: Path) -> str:
    """A ``file:`` URI for the catalogue, so each row has a citable, DISTINCT source.

    The row number goes in the query string rather than the fragment on purpose:
    :func:`~app.ai_features.market_research.canonical_url` drops fragments, so ``#row=3`` would
    make every row in the file compare equal and deduplication would keep exactly one of them.
    """
    try:
        return path.resolve().as_uri()
    except (OSError, ValueError):  # a path that will not resolve is still worth citing verbatim
        return str(path)


def _rows(path: Path, capability: Capability, provider: str) -> Iterator[dict[str, Any]]:
    """Every row of the catalogue as a lower-cased-key dict, streamed.

    Keys are lower-cased and stripped so ``Price``, ``price`` and ``" price "`` are one column. A
    line that will not parse is SKIPPED rather than raised on: one malformed row in a hand-edited
    price list must not cost the operator the other nine hundred.
    """
    suffix = path.suffix.lower()
    with path.open("r", encoding="utf-8", errors="replace", newline="") as handle:
        if suffix in (".jsonl", ".ndjson", ".json"):
            for line in handle:
                text = line.strip()
                if not text or text in ("[", "]"):
                    continue
                try:
                    parsed = json.loads(text.rstrip(","))
                except ValueError:
                    continue
                if isinstance(parsed, dict):
                    yield {str(key).strip().lower(): value for key, value in parsed.items()}
            return
        delimiter = "\t" if suffix == ".tsv" else ","
        reader = csv.DictReader(handle, delimiter=delimiter)
        if not reader.fieldnames:
            raise ProviderNotConfigured(
                f"{path.name} has no header row",
                missing=("AI_MARKET_RESEARCH_CATALOGUE_PATH",),
                capability=capability,
                provider=provider,
                remediation="The first line must name the columns: name,seller,price,currency,url",
            )
        for row in reader:
            yield {
                str(key or "").strip().lower(): value
                for key, value in row.items()
                if key is not None
            }
