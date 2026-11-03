from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum
from typing import TYPE_CHECKING, Any, Literal

if TYPE_CHECKING:
    from app.services.market_analysis import Distribution


class Capability(StrEnum):
    BACKGROUND_REMOVAL = "background_removal"
    FOREGROUND_SEPARATION = "foreground_separation"
    VECTORISATION = "vectorisation"
    MARKET_RESEARCH = "market_research"
    TRANSCRIPT_INSIGHTS = "transcript_insights"


ResourceBasis = Literal["VENDOR_STATED", "ESTIMATED", "MEASURED"]

ProviderKind = Literal["local", "hosted"]


@dataclass(frozen=True)
class ResourceProfile:
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
    id: str
    label: str
    kind: ProviderKind
    capabilities: frozenset[Capability]
    required_modules: tuple[str, ...]
    required_settings: tuple[str, ...]
    resources: ResourceProfile
    summary: str
    implementation: str = ""
    extra_modules: tuple[tuple[Capability, tuple[str, ...]], ...] = ()

    def modules_for(self, capability: Capability) -> tuple[str, ...]:
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
    data: bytes
    mime_type: str
    extension: str
    width: int
    height: int
    origin: str

    @property
    def byte_size(self) -> int:
        return len(self.data)

    @property
    def pixels(self) -> int:
        return self.width * self.height

    @property
    def filename(self) -> str:
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
    image: bytes = b""
    mime_type: str = "image/png"

    def as_dict(self) -> dict[str, Any]:
        payload = self._common()
        payload.update({"mimeType": self.mime_type, "byteSize": len(self.image)})
        return payload


@dataclass(frozen=True)
class SeparationResult(_BaseResult):
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
    svg: bytes = b""
    mime_type: str = "image/svg+xml"

    def as_dict(self) -> dict[str, Any]:
        payload = self._common()
        payload.update({"mimeType": self.mime_type, "byteSize": len(self.svg)})
        return payload


RETRIEVED_SOURCE = "RETRIEVED_LISTING"


@dataclass(frozen=True)
class ResearchBrief:
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
            "provenance": RETRIEVED_SOURCE,
        }


@dataclass(frozen=True)
class ResearchResult:
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
        return tuple(item for item in self.products if item.price is not None)

    def as_dict(self) -> dict[str, Any]:
        return {
            "provider": self.provider,
            "brief": self.brief.as_dict(),
            "queries": list(self.queries),
            "retrievedAt": self.retrieved_at,
            "durationMs": self.duration_ms,
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
