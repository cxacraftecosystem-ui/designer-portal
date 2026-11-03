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

_MAX_CATALOGUE_BYTES = 16 * 1024 * 1024

_MAX_ROWS_SCANNED = 200_000

_DEADLINE_CHECK_EVERY = 1_000

_NAME_KEYS = ("name", "title", "product", "product_name", "item")
_SELLER_KEYS = ("seller", "shop", "source", "vendor", "store", "merchant", "brand")
_PRICE_KEYS = ("price", "amount", "mrp", "rate", "cost")
_CURRENCY_KEYS = ("currency", "currency_code", "ccy")
_URL_KEYS = ("url", "link", "source_url", "product_link")
_UPDATED_KEYS = ("updated", "date", "as_of", "asof", "recorded", "captured")


class LocalCatalogueProvider(MarketResearchProvider):
    provider_id = "local_catalogue"

    def search(
        self,
        queries: Sequence[str],
        brief: ResearchBrief,
        deadline: Deadline,
    ) -> tuple[RawListing, ...]:
        from app.ai_features.market_research import content_words

        capability = Capability.MARKET_RESEARCH
        path = self._path(capability)
        fallback_stamp = _iso(path.stat().st_mtime)
        uri = _uri(path)

        wanted = [(query, frozenset(content_words(query))) for query in queries]
        budget = self.settings.market_research_max_results

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
                    retrieved_at=first_string(row, *_UPDATED_KEYS) or fallback_stamp,
                    currency_hint=first_string(row, *_CURRENCY_KEYS),
                )
            )

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
    try:
        return path.resolve().as_uri()
    except (OSError, ValueError):
        return str(path)


def _rows(path: Path, capability: Capability, provider: str) -> Iterator[dict[str, Any]]:
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
