"""Reading an Aadhaar or Pehchan number off a photograph of the card, and refusing to guess.

WHAT THIS IS. A designer registering an artisan at a workshop holds the artisan's card and types
twelve digits into a phone, standing in a shed, usually in poor light. This module lets them
photograph the card instead: the image goes to a vision-capable model the deployment already has a
key for, the model is asked for nothing but the digits it can see, and the candidates come back to
POPULATE A FORM FIELD THE DESIGNER THEN CONFIRMS.

WHY THE VERHOEFF CHECK IS THE POINT OF THIS FILE, not a nicety on the end of it.
``Artisan.aadhaarNumber`` is the repository's DEDUPLICATION KEY — the whole reason the column
exists is that the same person documented at two workshops by two researchers must resolve to one
record, and a UNIQUE index enforces it (see :mod:`app.services.artisan_identity`). OCR misreads
0/O, 1/I, 5/S, 8/B and 6/G constantly, and a misread digit does not produce an obviously broken
value: it produces twelve plausible digits belonging to a different human being, or to nobody. That
value passes the uniqueness check — it is unique, it is just wrong — and silently becomes an
artisan's identity in the repository. It will be masked to "XXXX XXXX 9012" everywhere it is shown,
so nobody will ever read it back and notice; it will fail to match the same artisan next year; and
it will occupy the real holder's number, so the person who actually owns it can never be entered.

UIDAI computes a Verhoeff check digit over the first eleven digits precisely because Verhoeff
catches EVERY single-digit error and EVERY transposition of adjacent digits — which is exactly the
error class an OCR misread produces. So every 12-digit candidate this module finds is run through
:func:`app.services.artisan_identity.verhoeff_ok` and only the survivors are returned. Roughly nine
out of ten misreads die there. Without it, this endpoint would be a fast way to write a wrong
national identity number into a research database.

WHAT THIS MODULE NEVER DOES.

* It never logs a recognised number, in full or in part, at any level, including on the error
  paths. Aadhaar is regulated personal data and a log line is the one copy nobody remembers to
  delete. The model's raw reply is not logged either — it contains the digits by construction.
* It never stores the uploaded image. There is deliberately no storage path in this module at all:
  the bytes live in the request handler's memory for the duration of one call and are then gone.
  A photograph of a national identity document is retained only when a designer deliberately
  uploads it through the ordinary media flow, where it is an explicit act with a visible record.

OFF BY DEFAULT, exactly like :mod:`app.ai_features`. Nothing in the API imports this module at
import time except its own route, every flag defaults to false, and with no provider key the
endpoint answers 503 naming the setting to configure. A deployment that does not turn this on
behaves precisely as it did before the feature existed.
"""

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
from app.services.artisan_identity import (
    AADHAAR_LENGTH,
    mask_aadhaar,
    normalize_pehchan,
    pehchan_error,
    verhoeff_ok,
)

logger = logging.getLogger(__name__)

# --------------------------------------------------------------------------------------
# Configuration — read the way app/ai_features reads its own, and for the same reasons
# --------------------------------------------------------------------------------------

#: Every variable this module reads, in documentation order. Iterated by the readiness report, so a
#: new setting cannot be added without becoming visible to an operator.
ENV_VARS: tuple[str, ...] = (
    "IDENTITY_OCR_ENABLED",
    "IDENTITY_OCR_PROVIDER",
    "IDENTITY_OCR_MAX_IMAGE_BYTES",
    "IDENTITY_OCR_TIMEOUT_SECONDS",
)

#: The variable an operator has to set to turn this on. Quoted literally in the 503 body, because
#: "the feature is disabled" is useless to somebody who then has to guess which flag it means.
ENABLE_VAR = "IDENTITY_OCR_ENABLED"
PROVIDER_VAR = "IDENTITY_OCR_PROVIDER"

# A card photographed by a phone is well under this. The ceiling exists because the bytes are
# base64-encoded into a JSON request body — which inflates them by a third — and held in memory the
# whole time; an accidental 40 MB raw upload would be a 55 MB string on a 1 GiB box.
_DEFAULT_MAX_BYTES = 8 * 1024 * 1024
_DEFAULT_TIMEOUT_SECONDS = 45.0

_TRUE = frozenset({"1", "true", "yes", "on", "y", "t"})
_FALSE = frozenset({"0", "false", "no", "off", "n", "f", ""})

#: The vision providers, in the order they are tried when the provider is left on "auto". Gemini
#: first because this repository already sends photographs to it for grid-sheet measurement, so a
#: deployment doing craft documentation almost certainly has that key and its free tier absorbs
#: this volume; OpenAI second because a deployment that transcribes with Whisper has that one.
PROVIDERS: tuple[str, ...] = ("gemini", "openai")
PROVIDER_KEYS: dict[str, str] = {"gemini": "GEMINI_API_KEY", "openai": "OPENAI_API_KEY"}

SUPPORTED_MIME_TYPES = frozenset({
    "image/jpeg", "image/jpg", "image/png", "image/webp", "image/heic", "image/heif",
})


@dataclass(frozen=True)
class IdentityOcrSettings:
    """Resolved configuration. Build through :func:`build_settings`, never by hand."""

    enabled: bool = False
    provider: str = "auto"
    max_image_bytes: int = _DEFAULT_MAX_BYTES
    timeout_seconds: float = _DEFAULT_TIMEOUT_SECONDS
    #: Complaints about malformed values, surfaced rather than raised. A typo in a number must
    #: never stop the app; it falls back to the default and says so.
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
    """Pure translation of a mapping into settings. No file access, no caching, no side effects.

    Pure so a test can assert the default-off contract against ``{}`` without depending on whatever
    happens to be in the developer's environment.
    """
    notes: list[str] = []
    provider = (env.get(PROVIDER_VAR) or "").strip().lower() or "auto"
    if provider not in ("auto", *PROVIDERS):
        notes.append(f"{PROVIDER_VAR}={provider!r} is not one of {['auto', *PROVIDERS]}; using auto")
        provider = "auto"
    return IdentityOcrSettings(
        enabled=_as_bool(env.get(ENABLE_VAR), False, ENABLE_VAR, notes),
        provider=provider,
        max_image_bytes=_as_int(
            env.get("IDENTITY_OCR_MAX_IMAGE_BYTES"), _DEFAULT_MAX_BYTES,
            "IDENTITY_OCR_MAX_IMAGE_BYTES", notes, minimum=64 * 1024,
        ),
        timeout_seconds=_as_float(
            env.get("IDENTITY_OCR_TIMEOUT_SECONDS"), _DEFAULT_TIMEOUT_SECONDS,
            "IDENTITY_OCR_TIMEOUT_SECONDS", notes,
        ),
        notes=tuple(notes),
    )


def _env_file_candidates() -> tuple[Path, ...]:
    """Where ``backend/.env`` might be, in the order pydantic-settings would find it."""
    package_root = Path(__file__).resolve().parents[2]  # …/backend
    return (Path(".env"), package_root / ".env")


def _read_env_file() -> dict[str, str]:
    """Values for :data:`ENV_VARS` found in .env. Unparseable lines are skipped, never raised on."""
    wanted = set(ENV_VARS)
    found: dict[str, str] = {}
    for candidate in _env_file_candidates():
        try:
            if not candidate.is_file():
                continue
            text = candidate.read_text(encoding="utf-8", errors="replace")
        except OSError:  # an unreadable .env is not a reason to fail a request
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
    """Cached settings for the running process. Call :func:`reset_identity_ocr_settings_cache`
    after a change."""
    settings = build_settings(_merged_environment())
    for note in settings.notes:
        logger.warning("identity_ocr configuration: %s", note)
    return settings


def reset_identity_ocr_settings_cache() -> None:
    """Drop the cache. For tests, and for a future "reload settings" admin action."""
    _merged_environment.cache_clear()
    get_identity_ocr_settings.cache_clear()


def available_providers() -> list[str]:
    """The vision providers that currently have a key, in the order they would be tried.

    Resolved through the same managed-secret layer the transcription chain uses, so a key added in
    the Settings hub enables this on the next request rather than the next restart. The caller must
    have primed the cache with ``managed_secrets.refresh_if_stale()`` first — see
    :func:`read_identity_card`, which does.
    """
    settings = get_identity_ocr_settings()
    wanted = PROVIDERS if settings.provider == "auto" else (settings.provider,)
    return [p for p in wanted if (managed_secrets.peek_secret(PROVIDER_KEYS[p]) or "").strip()]


# --------------------------------------------------------------------------------------
# The result
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class IdentityCandidate:
    """One number the model claims to have read, after this module has checked it."""

    value: str
    kind: str            # AADHAAR | PEHCHAN
    confidence: float
    masked: str = ""     # AADHAAR only: what any surface other than the form field may print

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
    #: How many 12-digit runs the model produced that did NOT survive the checksum. Reported as a
    #: count, never as values: a rejected candidate is still somebody's misread identity number.
    rejected_aadhaar_count: int = 0

    def payload(self) -> dict[str, Any]:
        return {
            "aadhaarCandidates": [c.payload() for c in self.aadhaar],
            "pehchanCandidates": [c.payload() for c in self.pehchan],
            "rejectedAadhaarCount": self.rejected_aadhaar_count,
            "provider": self.provider,
            # THE RESPONSE IS A SUGGESTION, NOT A COMMIT, and this field says so to the client as
            # loudly as the comment in the route does. See the route for why nothing here may be
            # written to an artisan without a human confirming it.
            "requiresConfirmation": True,
        }


# --------------------------------------------------------------------------------------
# Turning what a model said into candidates worth offering
# --------------------------------------------------------------------------------------

# A 12-digit run, however the card groups it. Only ASCII digits and only the separators a card
# actually prints: ``str.isdigit()`` is Unicode-aware and would accept Devanagari numerals, which
# would then be stored verbatim and defeat the unique index — see the note in artisan_identity.
_AADHAAR_RUN = re.compile(r"(?<![0-9])((?:[0-9][ \-]?){11}[0-9])(?![0-9])")
_NON_DIGITS = re.compile(r"[^0-9]")


def aadhaar_candidates(text: str) -> tuple[list[str], int]:
    """Every VERHOEFF-VALID Aadhaar number in ``text``, plus how many candidates were rejected.

    The three rules are the same three ``artisan_identity.aadhaar_error`` applies, in the same
    order, because a number this function offers and the validator then refuses would be a form the
    designer cannot submit and cannot correct: twelve ASCII digits, not starting 0 or 1 (UIDAI
    never issues those), and the Verhoeff checksum satisfied. The checksum is the one that matters
    here — the other two catch a bad crop, this one catches a bad READ.
    """
    accepted: list[str] = []
    rejected = 0
    seen: set[str] = set()
    for match in _AADHAAR_RUN.finditer(text or ""):
        digits = _NON_DIGITS.sub("", match.group(1))
        if len(digits) != AADHAAR_LENGTH or digits in seen:
            continue
        seen.add(digits)
        if digits[0] in "01" or not verhoeff_ok(digits):
            rejected += 1
            continue
        accepted.append(digits)
    return accepted, rejected


def pehchan_candidates(values: Any) -> list[str]:
    """Normalised Pehchan card numbers from whatever shape the model returned them in.

    The PM Vishwakarma artisan ID is a plain government reference number with no checksum to lean
    on, so the only defence is normalisation — one card, one spelling — plus the same shape rules
    the validator applies. Everything goes through ``artisan_identity.normalize_pehchan`` so an OCR
    read and a typed entry of the same card cannot be stored as two different strings.
    """
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
    """The model's own confidence, clamped into 0..1. A missing or unusable value is mid-scale.

    Never 1.0, whatever the model says. This value is shown beside a number the designer is being
    asked to CHECK, and a UI that reads "100%" invites them not to.
    """
    try:
        value = float(raw)
    except (TypeError, ValueError):
        return default
    return max(0.0, min(0.95, value))


def result_from_reply(payload: dict[str, Any], provider: str) -> IdentityOcrResult:
    """Turn one model reply into checked candidates. Pure — the whole filtering contract lives here.

    Both the structured fields and the raw text are searched for Aadhaar numbers: a model told to
    return JSON sometimes explains itself in a ``notes`` field instead, and a number the model saw
    but filed under the wrong key is still a number worth checking. The checksum makes that
    generosity safe — a run of twelve digits found in prose that happens to satisfy Verhoeff is
    overwhelmingly likely to be the number on the card.
    """
    haystack = " ".join(
        str(payload.get(key) or "")
        for key in ("aadhaar", "aadhaarNumber", "aadhaarCandidates", "digits", "text", "notes")
    )
    if not haystack.strip():
        haystack = json.dumps(payload, ensure_ascii=False)
    numbers, rejected = aadhaar_candidates(haystack)
    confidence = _confidence(payload.get("confidence"))
    return IdentityOcrResult(
        aadhaar=tuple(
            IdentityCandidate(value=n, kind="AADHAAR", confidence=confidence,
                              masked=mask_aadhaar(n) or "")
            for n in numbers
        ),
        pehchan=tuple(
            IdentityCandidate(value=p, kind="PEHCHAN", confidence=confidence)
            for p in pehchan_candidates(
                payload.get("pehchan") or payload.get("pehchanNumber")
                or payload.get("pehchanCandidates")
            )
        ),
        provider=provider,
        rejected_aadhaar_count=rejected,
    )


# --------------------------------------------------------------------------------------
# Asking a vision model
# --------------------------------------------------------------------------------------

# The prompt is deliberately narrow. A model asked to "read the card" summarises it — name,
# address, date of birth — and every one of those is personal data this endpoint has no reason to
# receive, hold in memory or return. It is asked for digits, and told in as many words not to
# repair or complete them: a model that "corrects" an obscured digit produces a number that passes
# the checksum by luck and is wrong, which is the one failure the checksum cannot catch.
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
    "Reply with JSON only: {\"aadhaar\": [\"<12 digits or empty>\"], \"pehchan\": [\"<code>\"], "
    "\"confidence\": <0 to 1 for how clearly you could read them>}"
)


def _gemini_ocr(content: bytes, mime_type: str, timeout: float) -> dict[str, Any]:
    keys = managed_secrets.gemini_key_pool()
    if not keys:
        raise RuntimeError("No Gemini API key configured")
    body = {
        "contents": [{
            "parts": [
                {"text": _PROMPT},
                {"inlineData": {
                    "mimeType": mime_type or "image/jpeg",
                    "data": base64.b64encode(content).decode("ascii"),
                }},
            ]
        }],
        "generationConfig": {"responseMimeType": "application/json", "temperature": 0.0},
    }
    model = get_settings().gemini_measurement_model
    response = requests.post(
        f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        # Header, never ``?key=``: a query parameter is part of the prepared URL and therefore
        # inside the message of every exception requests can raise from this call.
        headers={"x-goog-api-key": keys[0]},
        json=body,
        timeout=timeout,
    )
    response.raise_for_status()
    payload = response.json()
    text = (
        payload.get("candidates", [{}])[0]
        .get("content", {})
        .get("parts", [{}])[0]
        .get("text", "")
    )
    return _parse_json_reply(text)


def _openai_ocr(content: bytes, mime_type: str, timeout: float) -> dict[str, Any]:
    data_uri = (
        f"data:{mime_type or 'image/jpeg'};base64,"
        f"{base64.b64encode(content).decode('ascii')}"
    )
    response = requests.post(
        "https://api.openai.com/v1/chat/completions",
        headers={
            "Authorization": f"Bearer {managed_secrets.peek_secret('OPENAI_API_KEY') or ''}",
            "Content-Type": "application/json",
        },
        json={
            "model": get_settings().openai_chat_model,
            "temperature": 0.0,
            "response_format": {"type": "json_object"},
            "messages": [{
                "role": "user",
                "content": [
                    {"type": "text", "text": _PROMPT},
                    {"type": "image_url", "image_url": {"url": data_uri, "detail": "high"}},
                ],
            }],
        },
        timeout=timeout,
    )
    response.raise_for_status()
    payload = response.json()
    return _parse_json_reply(str(payload["choices"][0]["message"]["content"]))


def _parse_json_reply(text: str) -> dict[str, Any]:
    """The model's reply as a dict. A non-JSON reply becomes ``{"text": …}`` rather than an error.

    A model that answers in prose has still read the card, and the digit scan in
    :func:`result_from_reply` works on prose. Raising here would throw away a good read over a
    formatting preference.
    """
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
    """No vision provider could be used. Carries the message the route turns into a 503 body."""


def _sync_read(content: bytes, mime_type: str, providers: list[str], timeout: float) -> IdentityOcrResult:
    errors: list[str] = []
    for provider in providers:
        try:
            reply = _CALLS[provider](content, mime_type, timeout)
        except requests.RequestException as exc:
            # ``str(exc)`` is built from the prepared request and the reply is the card's digits,
            # so neither is logged: only which provider failed and with what status.
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
    raise IdentityOcrUnavailable(
        "No vision provider could read the card ("
        + "; ".join(errors)
        + "). Try again, or type the number from the card."
    )


async def read_identity_card(content: bytes, mime_type: str) -> IdentityOcrResult:
    """Read the identity numbers off a card photograph. Raises :class:`IdentityOcrUnavailable`.

    Raising rather than returning an "unavailable" result, unlike the transcription path: this call
    is synchronous and a person is waiting on it with a card in their hand, so "we cannot do this"
    is a 503 they should see immediately, not a status buried in a 200 body that a client has to
    remember to check.
    """
    settings = get_identity_ocr_settings()
    if not settings.enabled:
        raise IdentityOcrUnavailable(
            f"Identity-card scanning is switched off. Set {ENABLE_VAR}=true to enable it, and "
            f"configure a vision key (GEMINI_API_KEY or OPENAI_API_KEY) in Settings."
        )
    # Prime the managed-secret cache on the event loop BEFORE the thread hop: ``peek_secret`` cannot
    # await, so without this both the provider list and the header read fall back to the environment.
    await managed_secrets.refresh_if_stale()
    providers = available_providers()
    if not providers:
        raise IdentityOcrUnavailable(
            "Identity-card scanning needs a vision model. Configure GEMINI_API_KEY (preferred) or "
            "OPENAI_API_KEY in Settings, or type the number from the card."
        )
    return await asyncio.to_thread(
        _sync_read, content, mime_type, providers, settings.timeout_seconds
    )
