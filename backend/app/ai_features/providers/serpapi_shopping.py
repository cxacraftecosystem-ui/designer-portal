from collections.abc import Sequence
from typing import Any

from app.ai_features.errors import ProviderNotConfigured
from app.ai_features.providers.base import MarketResearchProvider, first_string
from app.ai_features.providers.http import JSON_RESPONSE_CEILING, request_json
from app.ai_features.runtime import Deadline, throttle
from app.ai_features.types import Capability, RawListing, ResearchBrief

_MAX_PAGES = 2
_PAGE_SIZE = 20


class SerpApiShoppingProvider(MarketResearchProvider):
    provider_id = "serpapi_shopping"

    def search(
        self,
        queries: Sequence[str],
        brief: ResearchBrief,
        deadline: Deadline,
    ) -> tuple[RawListing, ...]:
        capability = Capability.MARKET_RESEARCH
        api_key = self.settings.serpapi_api_key
        if not api_key:
            raise ProviderNotConfigured(
                "SERPAPI_API_KEY is not set",
                missing=("SERPAPI_API_KEY",),
                capability=capability,
                provider=self.provider_id,
                remediation="Set SERPAPI_API_KEY in the backend environment and restart.",
            )

        budget = self.settings.market_research_max_results
        per_query = max(1, budget // max(1, len(queries)))
        out: list[RawListing] = []

        for query in queries:
            collected = 0
            for page in range(_MAX_PAGES):
                if len(out) >= budget or collected >= per_query:
                    break
                rows = self._page(query, page * _PAGE_SIZE, api_key, capability, deadline)
                if not rows:
                    break
                retrieved_at = _now()
                for row in rows:
                    out.append(
                        _listing(
                            row, provider=self.provider_id, query=query, retrieved_at=retrieved_at
                        )
                    )
                collected += len(rows)
                if len(rows) < _PAGE_SIZE:
                    break
        return tuple(out)

    def _page(
        self,
        query: str,
        start: int,
        api_key: str,
        capability: Capability,
        deadline: Deadline,
    ) -> list[Any]:
        throttle(
            f"market_research:{self.provider_id}",
            self.settings.market_research_min_interval_seconds,
            deadline=deadline,
        )
        deadline.check(
            f"the SerpApi request for {query!r}", capability=capability, provider=self.provider_id
        )
        payload = request_json(
            self.settings.serpapi_endpoint,
            method="GET",
            params={
                "engine": "google_shopping",
                "q": query,
                "gl": self.settings.market_research_country,
                "hl": self.settings.market_research_language,
                "num": _PAGE_SIZE,
                "start": start,
                "api_key": api_key,
            },
            headers={"User-Agent": self.settings.market_research_user_agent},
            deadline=deadline,
            capability=capability,
            provider=self.provider_id,
            max_response_bytes=JSON_RESPONSE_CEILING,
            unauthorised_remediation="Check SERPAPI_API_KEY against the SerpApi dashboard.",
            credit_remediation=(
                "The SerpApi plan's searches are used up. Wait for the monthly reset, upgrade, or "
                "lower AI_MARKET_RESEARCH_MAX_QUERIES — each query page is one search."
            ),
        )
        if not isinstance(payload, dict):
            raise self._fail(
                f"SerpApi answered with {type(payload).__name__}, not an object",
                capability=capability,
                remediation="Check SERPAPI_ENDPOINT points at the search endpoint.",
            )
        error = first_string(payload, "error")
        if error:
            raise self._fail(
                f"SerpApi refused the search: {error}",
                capability=capability,
                remediation=(
                    "Read the message — an exhausted plan, an unsupported gl/hl pair and a bad "
                    "key all arrive this way."
                ),
            )
        rows = payload.get("shopping_results")
        if rows is None:
            return []
        if not isinstance(rows, list):
            raise self._fail(
                "SerpApi's 'shopping_results' field is not a list",
                capability=capability,
                remediation="The response shape has changed; check SerpApi's documentation.",
            )
        return rows


def _now() -> str:
    from app.ai_features.market_research import utc_now_iso

    return utc_now_iso()


def _listing(row: Any, *, provider: str, query: str, retrieved_at: str) -> RawListing:
    return RawListing(
        name=first_string(row, "title", "name"),
        seller=first_string(row, "source", "seller", "store"),
        price_text=first_string(row, "price", "extracted_price"),
        url=first_string(row, "product_link", "link", "serpapi_product_api"),
        provider=provider,
        query=query,
        retrieved_at=retrieved_at,
        currency_hint=first_string(row, "currency"),
    )
