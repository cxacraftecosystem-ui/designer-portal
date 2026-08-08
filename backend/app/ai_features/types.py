"""Value types shared by the four capabilities: what goes in, what comes back, what it cost.

Everything here is a frozen dataclass with an ``as_dict()``, because the two plausible consumers
are a background-queue job (which persists a JSON blob) and, eventually, an admin route (which
returns one). Neither wants to reach into provider-specific structures.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum
from typing import TYPE_CHECKING, Any, Literal

if TYPE_CHECKING:  # never at runtime — see market_research.py on why app.services stays unimported
    from app.services.market_analysis import Distribution


class Capability(StrEnum):
    """The four things this package can do. Values double as env-var infixes and JSON keys."""

    BACKGROUND_REMOVAL = "background_removal"
    FOREGROUND_SEPARATION = "foreground_separation"
    VECTORISATION = "vectorisation"
    MARKET_RESEARCH = "market_research"


#: How a resource number was arrived at. Never label an unmeasured number MEASURED — the point of
#: the field is that an operator sizing a box can tell a vendor's claim from our guess.
ResourceBasis = Literal["VENDOR_STATED", "ESTIMATED", "MEASURED"]

ProviderKind = Literal["local", "hosted"]


@dataclass(frozen=True)
class ResourceProfile:
    """What running one image through a provider actually costs, and how we know.

    ``peak_ram_mb`` is the number that decides whether a provider can live on the production box
    at all: it has 1 GiB total, shared with uvicorn, the Prisma query engine and the queue worker.
    """

    basis: ResourceBasis
    model_download_mb: float | None
    peak_ram_mb: float | None
    latency: str
    money: str
    notes: str = ""

    def as_dict(self) -> dict[str, Any]:
        return {
            "basis": self.basis,
            "modelDownloadMb": self.model_download_mb,
            "peakRamMb": self.peak_ram_mb,
            "latency": self.latency,
            "money": self.money,
            "notes": self.notes,
        }


@dataclass(frozen=True)
class ProviderDescriptor:
    """Everything the probe needs to report on a provider WITHOUT importing it.

    ``required_modules`` are checked with ``importlib.util.find_spec``, never an import, so asking
    "is rembg available" costs a path lookup rather than 176 MB of ONNX weights.

    ``implementation`` is a ``"module:ClassName"`` string rather than the class itself for the
    same reason: the registry can describe every provider at import time while the module that
    knows how to talk to onnxruntime stays unloaded until someone actually calls the feature.
    """

    id: str
    label: str
    kind: ProviderKind
    capabilities: frozenset[Capability]
    required_modules: tuple[str, ...]
    required_settings: tuple[str, ...]
    resources: ResourceProfile
    summary: str
    implementation: str = ""
    #: Modules one capability needs on top of ``required_modules``. Splitting a hosted cutout into
    #: layers means compositing locally, which returning the cutout itself never has to do — so
    #: "is separation available" and "is removal available" have genuinely different answers.
    extra_modules: tuple[tuple[Capability, tuple[str, ...]], ...] = ()

    def modules_for(self, capability: Capability) -> tuple[str, ...]:
        """Every module this provider needs to serve one capability, in declaration order."""
        extra = next((mods for cap, mods in self.extra_modules if cap == capability), ())
        return self.required_modules + tuple(
            module for module in extra if module not in self.required_modules
        )

    def as_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "label": self.label,
            "kind": self.kind,
            "capabilities": sorted(str(item) for item in self.capabilities),
            "requiredModules": list(self.required_modules),
            "requiredSettings": list(self.required_settings),
            "resources": self.resources.as_dict(),
            "summary": self.summary,
        }


@dataclass(frozen=True)
class ProviderReadiness:
    """Whether one provider could serve one capability right now, and what is missing if not.

    Produced by the registry from ``find_spec`` lookups and settings values alone, so both the
    probe and the dispatcher answer the question the same way and neither has to import anything.
    """

    provider: str
    capability: Capability
    ready: bool
    missing_modules: tuple[str, ...] = ()
    missing_settings: tuple[str, ...] = ()
    reason: str = ""

    def as_dict(self) -> dict[str, Any]:
        return {
            "provider": self.provider,
            "capability": str(self.capability),
            "ready": self.ready,
            "missingModules": list(self.missing_modules),
            "missingSettings": list(self.missing_settings),
            "reason": self.reason,
        }


@dataclass(frozen=True)
class ImagePayload:
    """A validated input image: bytes plus the facts we could establish from its header alone."""

    data: bytes
    mime_type: str
    extension: str
    width: int
    height: int
    origin: str  # "bytes" or the filename it was read from — useful in logs and provider uploads

    @property
    def byte_size(self) -> int:
        return len(self.data)

    @property
    def pixels(self) -> int:
        return self.width * self.height

    @property
    def filename(self) -> str:
        """A name to put in a multipart upload. Hosted providers sniff the extension."""
        return self.origin if self.origin != "bytes" else f"image.{self.extension}"

    def as_dict(self) -> dict[str, Any]:
        return {
            "mimeType": self.mime_type,
            "byteSize": self.byte_size,
            "width": self.width,
            "height": self.height,
        }


@dataclass(frozen=True)
class _BaseResult:
    provider: str
    duration_ms: int
    source: ImagePayload
    notes: tuple[str, ...] = field(default=())

    def _common(self) -> dict[str, Any]:
        return {
            "provider": self.provider,
            "durationMs": self.duration_ms,
            "source": self.source.as_dict(),
            "notes": list(self.notes),
        }


@dataclass(frozen=True)
class CutoutResult(_BaseResult):
    """Capability 2: the subject on transparency. ``image`` is always a PNG with an alpha channel."""

    image: bytes = b""
    mime_type: str = "image/png"

    def as_dict(self) -> dict[str, Any]:
        payload = self._common()
        payload.update({"mimeType": self.mime_type, "byteSize": len(self.image)})
        return payload


@dataclass(frozen=True)
class SeparationResult(_BaseResult):
    """Capability 1: the two layers plus the matte that relates them.

    Both layers are the original pixels wearing opposite alpha: the foreground carries the matte,
    the background carries its inverse. Compositing one over the other returns the original
    exactly wherever the matte is fully 0 or fully 255, and darkens slightly in the soft band
    between — the unavoidable arithmetic of splitting one image into two straight-alpha layers,
    and the reason the ``matte`` is returned separately for callers that want to re-composite
    their own way.
    """

    foreground: bytes = b""
    background: bytes = b""
    matte: bytes = b""
    mime_type: str = "image/png"

    def as_dict(self) -> dict[str, Any]:
        payload = self._common()
        payload.update(
            {
                "mimeType": self.mime_type,
                "foregroundBytes": len(self.foreground),
                "backgroundBytes": len(self.background),
                "matteBytes": len(self.matte),
            }
        )
        return payload


@dataclass(frozen=True)
class VectorResult(_BaseResult):
    """Capability 3: raster in, SVG out. ``svg`` is UTF-8 encoded markup, not a data URL."""

    svg: bytes = b""
    mime_type: str = "image/svg+xml"

    def as_dict(self) -> dict[str, Any]:
        payload = self._common()
        payload.update({"mimeType": self.mime_type, "byteSize": len(self.svg)})
        return payload


# ------------------------------------------------------------------------------------------------
# Capability 4: market research. Nothing below is an image.
# ------------------------------------------------------------------------------------------------

#: The value stamped into ``market_analysis.PriceObservation.source`` for anything this package
#: retrieved from the internet or from a catalogue file.
#:
#: THIS CONSTANT IS THE WHOLE PROVENANCE GUARANTEE, and it is a string rather than a flag because
#: ``PriceObservation.source`` is already the field the analysis branches on: ``RESPONDENT`` is a
#: person who was asked, ``COMPETITOR`` is a shelf a designer stood in front of. A scraped price is
#: neither. Anything that filters for ``RESPONDENT`` — ``position_competitors`` does, and so does
#: every respondent figure in ``analyse`` — therefore excludes retrieved prices without a line of
#: new code, and anything that counts blindly gets a value it has never seen and cannot mistake for
#: fieldwork. Feeding a retrieved number into a ministry report as though a respondent said it is
#: the worst outcome this feature has; a distinct sentinel plus
#: :func:`app.ai_features.market_research.assert_surveyed` is how it is prevented.
RETRIEVED_SOURCE = "RETRIEVED_LISTING"


@dataclass(frozen=True)
class ResearchBrief:
    """What to go and look for. The ONLY input to a market-research call.

    Deliberately structured rather than free text. Every field here is something the workshop app
    already holds — the craft, the product category, the materials, the district, the price band
    the designer declared in stage 9 — and a structured brief is what lets the query be built
    deterministically instead of inferred by a model. See the module docstring of
    :mod:`app.ai_features.market_research` for where that line is drawn and why it sits here.

    ``keyword`` is the one required field: the product a designer would type. A sentence is
    accepted and reduced to its content words; a paragraph is accepted and truncated, because the
    alternative — a model reading the paragraph — is the one part of this feature that would need
    one.
    """

    keyword: str
    craft: str = ""
    category: str = ""
    materials: tuple[str, ...] = ()
    place: str = ""
    price_low: float | None = None
    price_high: float | None = None
    currency: str = ""
    max_results: int | None = None

    def as_dict(self) -> dict[str, Any]:
        return {
            "keyword": self.keyword,
            "craft": self.craft,
            "category": self.category,
            "materials": list(self.materials),
            "place": self.place,
            "priceLow": self.price_low,
            "priceHigh": self.price_high,
            "currency": self.currency,
            "maxResults": self.max_results,
        }


@dataclass(frozen=True)
class RawListing:
    """One row exactly as a provider handed it over, before this package interprets any of it.

    THE PRICE IS STILL A STRING HERE, on purpose. ``"₹1,20,000.00"``, ``"Rs 1,299 - 1,999"`` and
    ``"$45.99"`` are all things a shopping API returns in its price field, and the moment a
    provider converts one to a float it has made a decision — which digit group is a separator,
    which currency, which end of a range — that nobody can audit afterwards. Providers map vendor
    key names (their only real job) and nothing else; :func:`market_research.parse_price` makes
    every one of those decisions in one tested place and keeps ``price_text`` beside the answer.
    """

    name: str
    seller: str
    price_text: str
    url: str
    provider: str
    query: str
    retrieved_at: str
    currency_hint: str = ""


@dataclass(frozen=True)
class RetrievedProduct:
    """One comparable product, parsed, with the two facts that make it auditable.

    ``source_url`` and ``retrieved_at`` are not decoration. A price with neither is indistinguishable
    from a price somebody made up, and six months later — when the listing has changed and the
    report is being defended — they are the only way to say where the number came from and when it
    was true.
    """

    name: str
    seller: str
    price: float | None
    currency: str
    source_url: str
    retrieved_at: str
    provider: str
    query: str = ""
    price_text: str = ""
    relevance: float = 0.0
    notes: tuple[str, ...] = field(default=())

    def as_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "seller": self.seller,
            "price": self.price,
            "currency": self.currency,
            "sourceUrl": self.source_url,
            "retrievedAt": self.retrieved_at,
            "provider": self.provider,
            "query": self.query,
            "priceText": self.price_text,
            "relevance": round(self.relevance, 4),
            "notes": list(self.notes),
            # Repeated in the wire form so a consumer that never reads this docstring still cannot
            # merge these rows into surveyed ones by accident.
            "provenance": RETRIEVED_SOURCE,
        }


@dataclass(frozen=True)
class ResearchResult:
    """What one market-research call found, what it threw away, and what it refuses to conclude.

    ``rejected`` and ``cautions`` are as much of the answer as ``products`` is. A retrieval that
    silently drops the eleven listings it could not parse and prints a median of the other four is
    the failure mode this whole package is written against.
    """

    provider: str
    brief: ResearchBrief
    queries: tuple[str, ...]
    retrieved_at: str
    duration_ms: int
    products: tuple[RetrievedProduct, ...] = field(default=())
    rejected: tuple[tuple[RetrievedProduct, str], ...] = field(default=())
    duplicates_removed: int = 0
    currency: str = ""
    other_currencies: dict[str, int] = field(default_factory=dict)
    distribution: Distribution | None = None
    cautions: tuple[str, ...] = field(default=())
    notes: tuple[str, ...] = field(default=())

    @property
    def priced(self) -> tuple[RetrievedProduct, ...]:
        """The kept products that carry a usable price. The sample every figure describes."""
        return tuple(item for item in self.products if item.price is not None)

    def as_dict(self) -> dict[str, Any]:
        return {
            "provider": self.provider,
            "brief": self.brief.as_dict(),
            "queries": list(self.queries),
            "retrievedAt": self.retrieved_at,
            "durationMs": self.duration_ms,
            # Cautions first, for the reason market_findings_payload gives: a caution that has to
            # be found is a caution that will not be read.
            "cautions": list(self.cautions),
            "provenance": RETRIEVED_SOURCE,
            "sampleSize": len(self.priced),
            "currency": self.currency,
            "otherCurrencies": dict(sorted(self.other_currencies.items())),
            "duplicatesRemoved": self.duplicates_removed,
            "distribution": _distribution_payload(self.distribution),
            "products": [item.as_dict() for item in self.products],
            "rejected": [
                {"product": item.as_dict(), "reason": reason} for item, reason in self.rejected
            ],
            "notes": list(self.notes),
        }


def _distribution_payload(dist: Distribution | None) -> dict[str, Any] | None:
    """A :class:`~app.services.market_analysis.Distribution` as JSON.

    Mirrors ``market_analysis._distribution_payload`` field for field. It is duplicated rather than
    imported because that one is private — reaching into it would make a refactor of the analysis
    module break a dormant package nobody was thinking about. ``test_market_research.py`` asserts
    the two agree, so the duplication is checked rather than hoped for.
    """
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
