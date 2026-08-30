"""Comparable products through serper.dev's Google Shopping endpoint.

WHY A SHOPPING API AND NOT A CRAWLER. This endpoint answers with fields — ``title``, ``source``,
``price``, ``link`` — which is the entire reason :mod:`app.ai_features.market_research` needs no
model to read a page and no robots.txt logic to be allowed to. The operator agrees to serper's
terms and pays for what they use; nothing here visits a shop's website.

WHAT LEAVES THE BUILDING. The query only — the words a designer typed plus the craft, category,
materials and district on the brief. No respondent name, no photograph, no captured survey row.
That is a materially smaller disclosure than the hosted image providers make, and it is worth
saying explicitly, because "we sent it to a search API" is otherwise a sentence with no bound on it.

Never run against the real service. The request shape follows serper's published documentation as
of 2026-08 and is covered by stubbed tests; the vendor key names are read through
:func:`~app.ai_features.providers.base.first_string` with alternatives, so a rename degrades to a
blank field rather than losing the batch. Expect to spend ten minutes confirming it with an
account, exactly as ``docs/AI_FEATURES.md`` §8 says of remove.bg.
"""

from collections.abc import Sequence
from typing import Any

from app.ai_features.errors import ProviderNotConfigured
from app.ai_features.providers.base import MarketResearchProvider, first_string
from app.ai_features.providers.http import JSON_RESPONSE_CEILING, request_json
from app.ai_features.runtime import Deadline, throttle
from app.ai_features.types import Capability, RawListing, ResearchBrief

#: Pages fetched per query before giving up on reaching the result budget. Three, because every
#: page is a credit and a fourth page of Google Shopping is not comparable products any more.
_MAX_PAGES = 3

#: Results asked for per request. serper documents 10 as the default depth and charges more beyond
#: it, so this is the boundary of one credit.
_PAGE_SIZE = 10


class SerperShoppingProvider(MarketResearchProvider):
    """One POST per page, mapped onto RawListing. No interpretation — see the base class."""

    provider_id = "serper_shopping"

    def search(
        self,
        queries: Sequence[str],
        brief: ResearchBrief,
        deadline: Deadline,
    ) -> tuple[RawListing, ...]:
        capability = Capability.MARKET_RESEARCH
        api_key = self.settings.serper_api_key
        if not api_key:
            raise ProviderNotConfigured(
                "SERPER_API_KEY is not set",
                missing=("SERPER_API_KEY",),
                capability=capability,
                provider=self.provider_id,
                remediation="Set SERPER_API_KEY in the backend environment and restart.",
            )

        budget = self.settings.market_research_max_results
        # THE BUDGET IS SPLIT ACROSS QUERIES, NOT GIVEN TO EACH. Three queries each fetching
        # twenty results is sixty credits for a button press nobody warned the operator about.
        per_query = max(1, budget // max(1, len(queries)))
        out: list[RawListing] = []

        for query in queries:
            collected = 0
            for page in range(1, _MAX_PAGES + 1):
                if len(out) >= budget or collected >= per_query:
                    break
                rows = self._page(query, page, api_key, capability, deadline)
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
                    break  # a short page is the last page; asking for another spends a credit
        return tuple(out)

    def _page(
        self,
        query: str,
        page: int,
        api_key: str,
        capability: Capability,
        deadline: Deadline,
    ) -> list[Any]:
        # Courtesy delay first, then the budget check — so a call that the throttle pushed past
        # the deadline is reported as "out of budget" rather than made anyway.
        throttle(
            f"market_research:{self.provider_id}",
            self.settings.market_research_min_interval_seconds,
            deadline=deadline,
        )
        deadline.check(
            f"the serper request for {query!r}", capability=capability, provider=self.provider_id
        )
        payload = request_json(
            self.settings.serper_endpoint,
            method="POST",
            json_body={
                "q": query,
                "gl": self.settings.market_research_country,
                "hl": self.settings.market_research_language,
                "num": _PAGE_SIZE,
                "page": page,
            },
            headers={
                "X-API-KEY": api_key,
                "Content-Type": "application/json",
                "User-Agent": self.settings.market_research_user_agent,
            },
            deadline=deadline,
            capability=capability,
            provider=self.provider_id,
            max_response_bytes=JSON_RESPONSE_CEILING,
            unauthorised_remediation="Check SERPER_API_KEY against the serper.dev dashboard.",
            credit_remediation=(
                "Top up the serper.dev account, or lower AI_MARKET_RESEARCH_MAX_RESULTS and "
                "AI_MARKET_RESEARCH_MAX_QUERIES — each query page is one credit."
            ),
        )
        if not isinstance(payload, dict):
            raise self._fail(
                f"serper answered with {type(payload).__name__}, not an object",
                capability=capability,
                remediation="Check SERPER_ENDPOINT points at the shopping endpoint.",
            )
        rows = payload.get("shopping")
        if rows is None:
            # An empty market is a legitimate answer and must not look like a failure; a missing
            # key when the vendor also sent an error message is a failure and must not look empty.
            message = first_string(payload, "message", "error")
            if message:
                raise self._fail(
                    f"serper returned no shopping results: {message}",
                    capability=capability,
                    remediation="Check the query and SERPER_ENDPOINT.",
                )
            return []
        if not isinstance(rows, list):
            raise self._fail(
                "serper's 'shopping' field is not a list",
                capability=capability,
                remediation="The response shape has changed; check serper's documentation.",
            )
        return rows


def _now() -> str:
    from app.ai_features.market_research import utc_now_iso  # avoids a cycle

    return utc_now_iso()


def _listing(row: Any, *, provider: str, query: str, retrieved_at: str) -> RawListing:
    """One vendor row as a RawListing. The ONLY vendor-specific knowledge in this file."""
    return RawListing(
        name=first_string(row, "title", "name"),
        seller=first_string(row, "source", "seller", "store", "merchant"),
        # The formatted string is preferred over any numeric field: it carries the currency and it
        # is what a person auditing the figure will see on the page.
        price_text=first_string(row, "price", "priceText", "extractedPrice", "price_value"),
        url=first_string(row, "link", "url", "productLink", "product_link", "offersLink"),
        provider=provider,
        query=query,
        retrieved_at=retrieved_at,
        currency_hint=first_string(row, "currency", "priceCurrency"),
    )
