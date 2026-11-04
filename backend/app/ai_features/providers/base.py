from abc import ABC, abstractmethod
from collections.abc import Mapping, Sequence
from io import BytesIO
from typing import Any, ClassVar

from app.ai_features.errors import ProviderFailed
from app.ai_features.registry import get_descriptor
from app.ai_features.runtime import Deadline
from app.ai_features.settings import AiFeatureSettings
from app.ai_features.types import (
    Capability,
    CutoutResult,
    ImagePayload,
    ProviderDescriptor,
    RawListing,
    ResearchBrief,
    SeparationResult,
    VectorResult,
)


class AiProvider(ABC):  # noqa: B024 - see below: abstract by intent, not by an abstract method
    provider_id: ClassVar[str] = ""

    def __init__(self, settings: AiFeatureSettings) -> None:
        self.settings = settings

    @property
    def descriptor(self) -> ProviderDescriptor:
        return get_descriptor(self.provider_id)

    def _fail(self, message: str, *, capability: Capability, **kwargs: Any) -> ProviderFailed:
        return ProviderFailed(message, capability=capability, provider=self.provider_id, **kwargs)


class ImageAiProvider(AiProvider):
    pass


class MarketResearchProvider(AiProvider):
    @abstractmethod
    def search(
        self,
        queries: Sequence[str],
        brief: ResearchBrief,
        deadline: Deadline,
    ) -> tuple[RawListing, ...]: ...


class BackgroundRemovalProvider(ImageAiProvider):
    @abstractmethod
    def remove_background(self, image: ImagePayload, deadline: Deadline) -> CutoutResult: ...


class ForegroundSeparationProvider(ImageAiProvider):
    @abstractmethod
    def separate_foreground(self, image: ImagePayload, deadline: Deadline) -> SeparationResult: ...


class VectorisationProvider(ImageAiProvider):
    @abstractmethod
    def vectorise(self, image: ImagePayload, deadline: Deadline) -> VectorResult: ...


def first_string(row: Any, *keys: str) -> str:
    if not isinstance(row, Mapping):
        return ""
    for key in keys:
        value = row.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return str(value)
    return ""


def _encode_png(pillow_image: Any) -> bytes:
    buffer = BytesIO()
    pillow_image.save(buffer, format="PNG")
    return buffer.getvalue()


def extract_alpha(cutout_png: bytes, *, capability: Capability, provider: str) -> bytes:
    from PIL import Image

    try:
        with Image.open(BytesIO(cutout_png)) as image:
            image.load()
            if image.mode != "RGBA":
                image = image.convert("RGBA")  # noqa: PLW2901
            return _encode_png(image.getchannel("A"))
    except Exception as exc:
        raise ProviderFailed(
            f"could not read the alpha channel of the cutout: {exc}",
            capability=capability,
            provider=provider,
            remediation="The provider returned something that is not an RGBA image.",
        ) from exc


def compose_layers(
    image: ImagePayload,
    matte_png: bytes,
    *,
    capability: Capability,
    provider: str,
) -> tuple[bytes, bytes, bytes, tuple[str, ...]]:
    from PIL import Image, ImageChops

    notes: list[str] = []
    try:
        with Image.open(BytesIO(image.data)) as opened:
            opened.load()
            base = opened.convert("RGB")
        with Image.open(BytesIO(matte_png)) as opened_matte:
            opened_matte.load()
            matte = opened_matte.convert("L")
    except Exception as exc:
        raise ProviderFailed(
            f"could not decode the image or its matte: {exc}",
            capability=capability,
            provider=provider,
        ) from exc

    if matte.size != base.size:
        notes.append(
            f"matte was {matte.size[0]}x{matte.size[1]}, resized to "
            f"{base.size[0]}x{base.size[1]} to match the source"
        )
        matte = matte.resize(base.size, Image.Resampling.BILINEAR)

    foreground = base.copy()
    foreground.putalpha(matte)
    background = base.copy()
    background.putalpha(ImageChops.invert(matte))
    return (
        _encode_png(foreground),
        _encode_png(background),
        _encode_png(matte),
        tuple(notes),
    )
