"""Comparable products through SerpApi's ``google_shopping`` engine.

THE SECOND VENDOR EXISTS SO THERE IS NO FIRST ONE. A capability with a single hosted provider is a
hardcoded vendor with extra steps: the price goes up, the terms change, the account is refused in a
jurisdiction, and the feature is gone. This one answers the same question through a different
company with a different pricing model, and ``auto`` will take whichever is configured.

Same boundary as serper: a documented commerce API, structured fields, no crawling, and only the
query leaves the building. Same warning too — never run against the real service. The request
follows SerpApi's published ``google_shopping`` documentation as of 2026-08.

ONE SHAPE DIFFERENCE WORTH KNOWING: SerpApi answers 200 with ``{"error": "..."}`` for a query it
could not run, where serper uses a status code. That is handled below rather than left to look like
an empty market, which is a different and much more damaging answer.
"""

from collections.abc import Sequence
from typing import Any

from app.ai_features.errors import ProviderNotConfigured
from app.ai_features.providers.base import MarketResearchProvider, first_string
from app.ai_features.providers.http import JSON_RESPONSE_CEILING, request_json
from app.ai_features.runtime import Deadline, throttle
from app.ai_features.types import Capability, RawListing, ResearchBrief

#: Pages per query. One SerpApi search is one billable search whatever ``num`` says, so the page
#: size is large and the page count small — the opposite tuning to serper, for the same reason.
_MAX_PAGES = 2
_PAGE_SIZE = 20


class SerpApiShoppingProvider(MarketResearchProvider):
    """One GET per page against ``engine=google_shopping``."""

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
                # In the query string because that is the only place SerpApi accepts it. It is
                # therefore in the URL of an outbound request: a proxy that logs full URLs would
                # log this key, which is worth knowing before pointing SERPAPI_ENDPOINT at one.
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
            # 200 + an error string. Left unhandled this reads as "the market is empty", which is
            # the one wrong answer this whole module is written to avoid producing.
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
    from app.ai_features.market_research import utc_now_iso  # avoids a cycle

    return utc_now_iso()


def _listing(row: Any, *, provider: str, query: str, retrieved_at: str) -> RawListing:
    """One SerpApi row as a RawListing. The ONLY vendor-specific knowledge in this file."""
    return RawListing(
        name=first_string(row, "title", "name"),
        seller=first_string(row, "source", "seller", "store"),
        # ``price`` is the formatted string ("₹1,299"); ``extracted_price`` is a bare number and is
        # only the fallback, because a number without a currency is a price nobody can check.
        price_text=first_string(row, "price", "extracted_price"),
        url=first_string(row, "product_link", "link", "serpapi_product_api"),
        provider=provider,
        query=query,
        retrieved_at=retrieved_at,
        currency_hint=first_string(row, "currency"),
    )
