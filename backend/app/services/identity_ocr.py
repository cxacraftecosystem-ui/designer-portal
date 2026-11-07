from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import re
from collections.abc import Mapping
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path
from typing import Any

import requests

from app.core.config import get_settings
from app.services import managed_secrets
from app.services.ai import redact_secrets
from app.services.ai_providers import openai_sampling, post_gemini
from app.services.artisan_identity import (
    AADHAAR_LENGTH,
    mask_aadhaar,
    normalize_pehchan,
    pehchan_error,
    verhoeff_ok,
)

logger = logging.getLogger(__name__)

ENV_VARS: tuple[str, ...] = (
    "IDENTITY_OCR_ENABLED",
    "IDENTITY_OCR_PROVIDER",
    "IDENTITY_OCR_MAX_IMAGE_BYTES",
    "IDENTITY_OCR_TIMEOUT_SECONDS",
)

ENABLE_VAR = "IDENTITY_OCR_ENABLED"
PROVIDER_VAR = "IDENTITY_OCR_PROVIDER"

_DEFAULT_MAX_BYTES = 8 * 1024 * 1024
_DEFAULT_TIMEOUT_SECONDS = 45.0

_TRUE = frozenset({"1", "true", "yes", "on", "y", "t"})
_FALSE = frozenset({"0", "false", "no", "off", "n", "f", ""})

PROVIDERS: tuple[str, ...] = ("gemini", "openai")
PROVIDER_KEYS: dict[str, str] = {"gemini": "GEMINI_API_KEY", "openai": "OPENAI_API_KEY"}

SUPPORTED_MIME_TYPES = frozenset(
    {
        "image/jpeg",
        "image/jpg",
        "image/png",
        "image/webp",
        "image/heic",
        "image/heif",
    }
)


@dataclass(frozen=True)
class IdentityOcrSettings:
    enabled: bool = False
    provider: str = "auto"
    max_image_bytes: int = _DEFAULT_MAX_BYTES
    timeout_seconds: float = _DEFAULT_TIMEOUT_SECONDS
    notes: tuple[str, ...] = field(default=())


def _as_bool(raw: str | None, default: bool, name: str, notes: list[str]) -> bool:
    if raw is None:
        return default
    value = raw.strip().lower()
    if value in _TRUE:
        return True
    if value in _FALSE:
        return False
    notes.append(f"{name}={raw!r} is not a boolean; using {default}")
    return default


def _as_int(raw: str | None, default: int, name: str, notes: list[str], minimum: int) -> int:
    if raw is None or not raw.strip():
        return default
    try:
        value = int(raw.strip())
    except ValueError:
        notes.append(f"{name}={raw!r} is not an integer; using {default}")
        return default
    if value < minimum:
        notes.append(f"{name}={value} is below the minimum {minimum}; using {minimum}")
        return minimum
    return value


def _as_float(raw: str | None, default: float, name: str, notes: list[str]) -> float:
    if raw is None or not raw.strip():
        return default
    try:
        value = float(raw.strip())
    except ValueError:
        notes.append(f"{name}={raw!r} is not a number; using {default}")
        return default
    return value if value > 0 else default


def build_settings(env: Mapping[str, str]) -> IdentityOcrSettings:
    notes: list[str] = []
    provider = (env.get(PROVIDER_VAR) or "").strip().lower() or "auto"
    if provider not in ("auto", *PROVIDERS):
        notes.append(
            f"{PROVIDER_VAR}={provider!r} is not one of {['auto', *PROVIDERS]}; using auto"
        )
        provider = "auto"
    return IdentityOcrSettings(
        enabled=_as_bool(env.get(ENABLE_VAR), False, ENABLE_VAR, notes),
        provider=provider,
        max_image_bytes=_as_int(
            env.get("IDENTITY_OCR_MAX_IMAGE_BYTES"),
            _DEFAULT_MAX_BYTES,
            "IDENTITY_OCR_MAX_IMAGE_BYTES",
            notes,
            minimum=64 * 1024,
        ),
        timeout_seconds=_as_float(
            env.get("IDENTITY_OCR_TIMEOUT_SECONDS"),
            _DEFAULT_TIMEOUT_SECONDS,
            "IDENTITY_OCR_TIMEOUT_SECONDS",
            notes,
        ),
        notes=tuple(notes),
    )


def _env_file_candidates() -> tuple[Path, ...]:
    package_root = Path(__file__).resolve().parents[2]
    return (Path(".env"), package_root / ".env")


def _read_env_file() -> dict[str, str]:
    wanted = set(ENV_VARS)
    found: dict[str, str] = {}
    for candidate in _env_file_candidates():
        try:
            if not candidate.is_file():
                continue
            text = candidate.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for line in text.splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("#") or "=" not in stripped:
                continue
            key, _, value = stripped.partition("=")
            key = key.strip()
            if key not in wanted or key in found:
                continue
            found[key] = value.strip().strip("\"'")
        if found:
            break
    return found


@lru_cache
def _merged_environment() -> dict[str, str]:
    merged = dict(_read_env_file())
    for name in ENV_VARS:
        value = os.environ.get(name)
        if value is not None:
            merged[name] = value
    return merged


@lru_cache
def get_identity_ocr_settings() -> IdentityOcrSettings:
    settings = build_settings(_merged_environment())
    for note in settings.notes:
        logger.warning("identity_ocr configuration: %s", note)
    return settings


def reset_identity_ocr_settings_cache() -> None:
    _merged_environment.cache_clear()
    get_identity_ocr_settings.cache_clear()


def available_providers() -> list[str]:
    settings = get_identity_ocr_settings()
    wanted = PROVIDERS if settings.provider == "auto" else (settings.provider,)
    return [p for p in wanted if (managed_secrets.peek_secret(PROVIDER_KEYS[p]) or "").strip()]



@dataclass(frozen=True, slots=True)
class IdentityCandidate:
    value: str
    kind: str
    confidence: float
    masked: str = ""

    def payload(self) -> dict[str, Any]:
        out: dict[str, Any] = {
            "value": self.value,
            "kind": self.kind,
            "confidence": round(self.confidence, 2),
        }
        if self.masked:
            out["masked"] = self.masked
        return out


@dataclass(frozen=True, slots=True)
class IdentityOcrResult:
    aadhaar: tuple[IdentityCandidate, ...] = ()
    pehchan: tuple[IdentityCandidate, ...] = ()
    provider: str = ""
    rejected_aadhaar_count: int = 0

    def payload(self) -> dict[str, Any]:
        return {
            "aadhaarCandidates": [c.payload() for c in self.aadhaar],
            "pehchanCandidates": [c.payload() for c in self.pehchan],
            "rejectedAadhaarCount": self.rejected_aadhaar_count,
            "provider": self.provider,
            "requiresConfirmation": True,
        }



DISCARD = "DISCARD"

STORE = "STORE"

RETENTION_DECISIONS: tuple[str, ...] = (DISCARD, STORE)

RETENTION_METADATA_KEY = "identityPhotograph"


def parse_retention(raw: Any) -> str:
    if isinstance(raw, str):
        cleaned = raw.strip().upper()
        if cleaned in RETENTION_DECISIONS:
            return cleaned
    return DISCARD


def retention_stamp(decision: str, *, user: Any, at: Any) -> dict[str, Any]:
    return {
        "decision": parse_retention(decision),
        "decidedById": getattr(user, "id", None),
        "decidedByName": getattr(user, "name", None) or getattr(user, "email", None) or "",
        "decidedAt": at.isoformat() if hasattr(at, "isoformat") else str(at),
    }


def retention_of(metadata: Any) -> dict[str, Any] | None:
    if not isinstance(metadata, Mapping):
        return None
    found = metadata.get(RETENTION_METADATA_KEY)
    return dict(found) if isinstance(found, Mapping) else None


def with_retention(metadata: Any, stamp: Mapping[str, Any]) -> dict[str, Any]:
    base = dict(metadata) if isinstance(metadata, Mapping) else {}
    base[RETENTION_METADATA_KEY] = dict(stamp)
    return base


_DIGIT_TOKEN = re.compile(r"[0-9](?:[ \-]?[0-9])*")
_NON_DIGITS = re.compile(r"[^0-9]")


def aadhaar_candidates(text: str) -> tuple[list[str], int]:
    accepted: list[str] = []
    rejected = 0
    seen: set[str] = set()
    for match in _DIGIT_TOKEN.finditer(text or ""):
        digits = _NON_DIGITS.sub("", match.group(0))
        if len(digits) != AADHAAR_LENGTH or digits in seen:
            continue
        seen.add(digits)
        if digits[0] in "01" or not verhoeff_ok(digits):
            rejected += 1
            continue
        accepted.append(digits)
    return accepted, rejected


def pehchan_candidates(values: Any) -> list[str]:
    raw: list[Any] = values if isinstance(values, (list, tuple)) else [values]
    out: list[str] = []
    for item in raw:
        if not isinstance(item, (str, int)):
            continue
        normalised = normalize_pehchan(str(item))
        if not normalised or pehchan_error(normalised) is not None:
            continue
        if normalised not in out:
            out.append(normalised)
    return out


def _confidence(raw: Any, default: float = 0.6) -> float:
    try:
        value = float(raw)
    except (TypeError, ValueError):
        return default
    return max(0.0, min(0.95, value))


def result_from_reply(payload: dict[str, Any], provider: str) -> IdentityOcrResult:
    haystack = "\n".join(
        str(payload.get(key) or "")
        for key in ("aadhaar", "aadhaarNumber", "aadhaarCandidates", "digits", "text", "notes")
    )
    if not haystack.strip():
        haystack = json.dumps(payload, ensure_ascii=False)
    numbers, rejected = aadhaar_candidates(haystack)
    confidence = _confidence(payload.get("confidence"))
    return IdentityOcrResult(
        aadhaar=tuple(
            IdentityCandidate(
                value=n, kind="AADHAAR", confidence=confidence, masked=mask_aadhaar(n) or ""
            )
            for n in numbers
        ),
        pehchan=tuple(
            IdentityCandidate(value=p, kind="PEHCHAN", confidence=confidence)
            for p in pehchan_candidates(
                payload.get("pehchan")
                or payload.get("pehchanNumber")
                or payload.get("pehchanCandidates")
            )
        ),
        provider=provider,
        rejected_aadhaar_count=rejected,
    )


_PROMPT = (
    "This image is a photograph of an Indian identity card (an Aadhaar card or a PM Vishwakarma "
    "artisan Pehchan card). Return ONLY the identification numbers printed on it.\n"
    "Rules:\n"
    "1. Transcribe digits EXACTLY as printed. Do not guess, correct, complete or infer any digit. "
    "If a digit is blurred, covered or unreadable, omit that number entirely.\n"
    "2. Do not return any other information from the card: no name, no address, no date of birth, "
    "no gender, no parent's name.\n"
    "3. An Aadhaar number is 12 digits, usually printed as three groups of four.\n"
    "4. A Pehchan / PM Vishwakarma number is an alphanumeric reference code.\n"
    'Reply with JSON only: {"aadhaar": ["<12 digits or empty>"], "pehchan": ["<code>"], '
    '"confidence": <0 to 1 for how clearly you could read them>}'
)


def _gemini_ocr(content: bytes, mime_type: str, timeout: float) -> dict[str, Any]:
    keys = managed_secrets.gemini_key_pool()
    if not keys:
        raise RuntimeError("No Gemini API key configured")
    body = {
        "contents": [
            {
                "parts": [
                    {"text": _PROMPT},
                    {
                        "inlineData": {
                            "mimeType": mime_type or "image/jpeg",
                            "data": base64.b64encode(content).decode("ascii"),
                        }
                    },
                ]
            }
        ],
        "generationConfig": {"responseMimeType": "application/json", "temperature": 0.0},
    }
    model = get_settings().gemini_caption_model
    model, response = post_gemini(
        requests.post,
        model,
        headers={"x-goog-api-key": keys[0]},
        json=body,
        timeout=timeout,
    )
    response.raise_for_status()
    payload = response.json()
    text = (
        payload.get("candidates", [{}])[0].get("content", {}).get("parts", [{}])[0].get("text", "")
    )
    return _parse_json_reply(text)


def _openai_ocr(content: bytes, mime_type: str, timeout: float) -> dict[str, Any]:
    data_uri = (
        f"data:{mime_type or 'image/jpeg'};base64,{base64.b64encode(content).decode('ascii')}"
    )
    response = requests.post(
        "https://api.openai.com/v1/chat/completions",
        headers={
            "Authorization": f"Bearer {managed_secrets.peek_secret('OPENAI_API_KEY') or ''}",
            "Content-Type": "application/json",
        },
        json={
            "model": get_settings().openai_chat_model,
            **openai_sampling(get_settings().openai_chat_model, 0.0),
            "response_format": {"type": "json_object"},
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": _PROMPT},
                        {"type": "image_url", "image_url": {"url": data_uri, "detail": "high"}},
                    ],
                }
            ],
        },
        timeout=timeout,
    )
    response.raise_for_status()
    payload = response.json()
    return _parse_json_reply(str(payload["choices"][0]["message"]["content"]))


def _parse_json_reply(text: str) -> dict[str, Any]:
    cleaned = str(text or "").strip()
    if cleaned.startswith("```"):
        cleaned = cleaned.strip("`")
        cleaned = cleaned.removeprefix("json").strip()
    try:
        parsed = json.loads(cleaned)
    except json.JSONDecodeError:
        return {"text": cleaned}
    return parsed if isinstance(parsed, dict) else {"text": cleaned}


_CALLS = {"gemini": _gemini_ocr, "openai": _openai_ocr}


class IdentityOcrUnavailable(RuntimeError):
    pass


def _sync_read(
    content: bytes, mime_type: str, providers: list[str], timeout: float
) -> IdentityOcrResult:
    errors: list[str] = []
    for provider in providers:
        try:
            reply = _CALLS[provider](content, mime_type, timeout)
        except requests.RequestException as exc:
            code = getattr(getattr(exc, "response", None), "status_code", None)
            fault = f"HTTP {code}" if code else f"unreachable ({type(exc).__name__})"
            errors.append(f"{provider}: {fault}")
            logger.warning("Identity OCR provider %s failed (%s)", provider, fault)
            continue
        except Exception as exc:  # noqa: BLE001 - a malformed provider payload is not a 500
            errors.append(f"{provider}: unexpected reply")
            logger.warning(
                "Identity OCR provider %s returned an unusable reply: %s",
                provider,
                redact_secrets(type(exc).__name__),
            )
            continue
        return result_from_reply(reply, provider)
    logger.warning("Identity OCR: every provider failed (%s)", "; ".join(errors))
    raise IdentityOcrUnavailable(
        "The card couldn't be read just now. Try again, or type the number from the card."
    )


async def read_identity_card(content: bytes, mime_type: str) -> IdentityOcrResult:
    settings = get_identity_ocr_settings()
    if not settings.enabled:
        raise IdentityOcrUnavailable(
            "Identity-card scanning isn't available right now. Type the number from the card."
        )
    await managed_secrets.refresh_if_stale()
    providers = available_providers()
    if not providers:
        raise IdentityOcrUnavailable(
            "Identity-card scanning isn't available right now. Type the number from the card. "
            "An administrator can turn it on in Settings."
        )
    return await asyncio.to_thread(
        _sync_read, content, mime_type, providers, settings.timeout_seconds
    )
