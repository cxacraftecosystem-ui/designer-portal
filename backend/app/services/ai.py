import asyncio
import base64
import json
import logging
import os
import re
import threading
from collections.abc import Iterator
from contextlib import contextmanager
from functools import lru_cache
from pathlib import Path
from typing import Any

import requests
from fastapi import UploadFile

from app.core.config import Settings
from app.services import (
    anthropic_verbs,
    app_settings,
    managed_secrets,
    paid_ai_allowance,
    subtitles,
    user_ai_keys,
)
from app.services.ai_providers import (
    AiProvider,
    AiTask,
    ModelDeclined,
    gemini_models,
    gemini_reply_text,
    openai_message_text,
    openai_sampling,
    post_gemini,
)
from app.services.measurement_provenance import (
    MeasurementProvenance,
    self_reported_confidence,
    vision_model_provenance,
)
from app.services.transcription_store import PrismaTranscriptionStore
from app.services.user_ai_keys import AiCredential
from app.transcription import audio as transcription_audio, service as transcription_service
from app.transcription.chain import ChainHooks, ChainState, run_chain
from app.transcription.contract import DISABLED_BY_DEFAULT, ApiKey, AudioInput, SttOptions
from app.transcription.format import diarized_markdown, speaker_count, speaker_turns
from app.transcription.providers import (
    ADAPTERS,
    deepgram as deepgram_adapter,
    elevenlabs as elevenlabs_adapter,
)
from app.transcription.store import StoreHooks

logger = logging.getLogger(__name__)


def _key(name: str) -> str:
    return managed_secrets.peek_secret(name) or ""


_URL_SECRET = re.compile(
    r"((?:key|api[-_]?key|access[-_]?token|token|secret|sig|signature)=)[^&\s\"'>]+",
    re.IGNORECASE,
)


def redact_secrets(text: str) -> str:
    return _URL_SECRET.sub(r"\1REDACTED", text)


def _fault(exc: Exception) -> str:
    code = getattr(getattr(exc, "response", None), "status_code", None)
    if code is None:
        code = getattr(exc, "status_code", None)
    if isinstance(exc, requests.Timeout) or type(exc).__name__ in {"APITimeoutError", "Timeout"}:
        return "the service took too long to answer"
    if code == 402:
        return "the service's credit has run out"
    if code in (401, 403):
        return "the service did not accept the key"
    if code in (429, 503):
        return "the service was busy"
    if code == 413:
        return "the file was too large for the service"
    if code and code >= 500:
        return "the service had a problem"
    if code:
        return "the service turned the request down"
    return "the service could not be reached"


_GEMINI_ROTATE_STATUSES = {400, 401, 403, 429, 500, 503}

_gemini_key_lock = threading.Lock()
_gemini_key_counter = 0


def _next_gemini_start(num_keys: int) -> int:
    global _gemini_key_counter
    if num_keys <= 0:
        return 0
    with _gemini_key_lock:
        start = _gemini_key_counter % num_keys
        _gemini_key_counter = (_gemini_key_counter + 1) % num_keys
    return start


WHISPER_MAX_BYTES = 24 * 1024 * 1024
TRANSCRIPTION_CHUNK_MS = 10 * 60 * 1000
ELEVENLABS_MAX_BYTES = 1000 * 1024 * 1024
DEEPGRAM_MAX_BYTES = 2 * 1024 * 1024 * 1024



def _source_size(content: bytes | None, source_path: str | None) -> int:
    if source_path is not None:
        try:
            return os.path.getsize(source_path)
        except OSError:
            return 0
    return len(content or b"")


@contextmanager
def _upload_body(content: bytes | None, source_path: str | None) -> Iterator[Any]:
    if source_path is not None:
        with open(source_path, "rb") as handle:
            yield handle
    else:
        yield content


def _bytes_of(content: bytes | None, source_path: str | None) -> bytes:
    if source_path is not None:
        with open(source_path, "rb") as handle:
            return handle.read()
    return content or b""


def _transcription_result(text: str, payload: Any = None) -> dict[str, Any]:
    return {
        "available": True,
        "status": "COMPLETED" if text else "EMPTY",
        "text": text,
        "formattedTranscript": f"Transcript\n\n{text}" if text else "",
        "raw": payload,
    }


_CRAFT_VOCABULARY_PATH = Path(__file__).resolve().parents[1] / "data" / "craft_vocabulary.txt"

_KEYTERM_MAX_CHARS = 50
_KEYTERM_MAX_WORDS = 5
_ELEVENLABS_KEYTERM_LIMIT = 100
_DEEPGRAM_KEYTERM_LIMIT = 50
_DEEPGRAM_KEYTERM_TOKEN_BUDGET = 400


@lru_cache(maxsize=1)
def craft_keyterms() -> tuple[str, ...]:
    try:
        raw = _CRAFT_VOCABULARY_PATH.read_text(encoding="utf-8")
    except OSError as exc:
        logger.warning("Craft vocabulary unreadable (%s); transcribing without term boosting", exc)
        return ()
    terms: list[str] = []
    seen: set[str] = set()
    rejected = 0
    for line in raw.splitlines():
        term = line.split("#", 1)[0].strip()
        if not term:
            continue
        if len(term) > _KEYTERM_MAX_CHARS or len(term.split()) > _KEYTERM_MAX_WORDS:
            rejected += 1
            continue
        if term.casefold() in seen:
            continue
        seen.add(term.casefold())
        terms.append(term)
    if rejected:
        logger.warning(
            "%s craft vocabulary entries skipped: a term may be at most %s characters and %s words",
            rejected,
            _KEYTERM_MAX_CHARS,
            _KEYTERM_MAX_WORDS,
        )
    return tuple(terms)


def _deepgram_keyterms() -> list[str]:
    return deepgram_adapter.keyterms_within_budget(craft_keyterms())



def _speaker_turns(fragments: list[tuple[Any, str]]) -> list[tuple[Any, str]]:
    return speaker_turns(fragments)


def _speaker_count(turns: list[tuple[Any, str]]) -> int:
    return speaker_count(turns)


def _diarized_markdown(turns: list[tuple[Any, str]]) -> str | None:
    return diarized_markdown(turns)


def _post_openai_transcription(
    content: bytes | None,
    filename: str,
    mime_type: str,
    settings: Settings | None,
    *,
    source_path: str | None = None,
) -> dict[str, Any]:
    result = ADAPTERS["whisper"].transcribe(
        _audio(content, filename, mime_type, source_path),
        _stt_options(settings),
        _provider_key("whisper"),
    )
    return _transcription_result(result.text, None)


def _post_muse_transcription(
    content: bytes | None,
    filename: str,
    mime_type: str,
    settings: Settings | None,
    *,
    source_path: str | None = None,
) -> dict[str, Any]:
    result = ADAPTERS["muse"].transcribe(
        _audio(content, filename, mime_type, source_path),
        _stt_options(settings),
        _provider_key("muse"),
    )
    return _legacy_result(result, language=False)


def _transcribe_on_personal_key(
    content: bytes | None,
    filename: str,
    mime_type: str,
    credential: AiCredential,
    *,
    source_path: str | None = None,
) -> dict[str, Any] | None:
    if credential.provider is AiProvider.OPENAI:
        with _upload_body(content, source_path) as body:
            response = requests.post(
                "https://api.openai.com/v1/audio/transcriptions",
                headers={"Authorization": f"Bearer {credential.api_key}"},
                data={"model": credential.model, "response_format": "json"},
                files={"file": (filename, body, mime_type or "application/octet-stream")},
                timeout=180,
            )
        response.raise_for_status()
        payload = response.json()
        return _transcription_result(str(payload.get("text") or "").strip(), payload)

    if credential.provider is AiProvider.GEMINI:
        response = requests.post(
            f"https://generativelanguage.googleapis.com/v1beta/models/{credential.model}:generateContent",
            headers={"x-goog-api-key": credential.api_key},
            json={
                "contents": [
                    {
                        "parts": [
                            {
                                "text": "Transcribe this recording word for word, in the language "
                                "spoken. Do not translate, summarise, tidy or omit anything. "
                                "Return only the transcript."
                            },
                            {
                                "inlineData": {
                                    "mimeType": mime_type or "audio/mpeg",
                                    "data": base64.b64encode(
                                        _bytes_of(content, source_path)
                                    ).decode("ascii"),
                                }
                            },
                        ]
                    }
                ]
            },
            timeout=180,
        )
        response.raise_for_status()
        payload = response.json()
        candidates = payload.get("candidates") or []
        parts = ((candidates[0].get("content") or {}).get("parts") or []) if candidates else []
        text = "".join(str(part.get("text", "")) for part in parts).strip()
        return _transcription_result(text, payload) if text else None

    return None


_TRANSCRIPTION_MESSAGES = transcription_service.Messages(
    key_rejected=lambda name: (
        f"{name}: the key was not accepted. An administrator can replace it in Settings."
    ),
    failed=lambda name, status: f"{name}: {_fault_for_status(status)}",
    unreachable=lambda name: f"{name}: the service could not be reached",
    too_large=lambda name: f"{name}: the file is too large",
    busy=lambda name: f"{name} is busy just now; will retry automatically.",
    rate_limited=lambda name: (
        f"{name} is busy just now; will retry automatically."
        if name
        else "Transcription is busy just now; will retry automatically."
    ),
    over_budget=lambda name: f"{name}: its transcription budget is used up",
    all_failed=lambda sentences: "; ".join(sentences) or "All transcription providers failed.",
    unavailable=(
        "Transcription isn't available right now. An administrator can turn it on in Settings."
    ),
    budget_deferred="The transcription budget is used up; will continue in the next window.",
)


def _fault_for_status(status: int | None) -> str:
    if status == 402:
        return "the service's credit has run out"
    if status in (401, 403):
        return "the service did not accept the key"
    if status in (429, 503):
        return "the service was busy"
    if status == 413:
        return "the file was too large for the service"
    if status and status >= 500:
        return "the service had a problem"
    if status:
        return "the service turned the request down"
    return "the service could not be reached"


def _stt_options(
    settings: Settings | None,
    *,
    language_bias: list[str] | None = None,
    session_id: str | None = None,
    keep_payload: bool = False,
    keyterms: list[str] | None = None,
) -> SttOptions:
    models = {"elevenlabs": _elevenlabs_model(settings)}
    deepgram_model = getattr(settings, "deepgram_stt_model", None)
    if deepgram_model:
        models["deepgram"] = deepgram_model
    whisper_model = getattr(settings, "openai_transcription_model", None)
    if whisper_model:
        models["whisper"] = whisper_model
    muse_model = getattr(settings, "muse_stt_model", None)
    if muse_model:
        models["muse"] = muse_model
    return SttOptions(
        language_bias=list(language_bias or []),
        keyterms=list(keyterms) if keyterms else list(craft_keyterms()),
        models=models,
        session_id=session_id,
        keep_payload=keep_payload,
    )


def transcription_options(
    settings: Settings | None, *, language_bias: list[str] | None = None
) -> SttOptions:
    return _stt_options(settings, language_bias=language_bias)


def _audio(
    content: bytes | None, filename: str, mime_type: str, source_path: str | None = None
) -> AudioInput:
    duration = None
    if source_path is not None and transcription_audio.ffmpeg_available():
        try:
            duration = transcription_audio.probe_duration_ms(source_path)
        except Exception:  # noqa: BLE001 - the probe only sharpens the budget estimate
            duration = None
    return AudioInput(
        None if source_path is not None else content,
        source_path,
        filename,
        mime_type or "application/octet-stream",
        duration,
    )


def _provider_key(provider: str) -> ApiKey:
    name = _PROVIDER_KEYS[provider]
    return ApiKey(name, _key(name))


def _legacy_result(result: Any, *, language: bool) -> dict[str, Any]:
    out = _transcription_result(result.text, None)
    out["model"] = result.model
    out["speakers"] = result.speakers
    out["segments"] = [segment.as_json() for segment in result.segments]
    if language and result.language:
        out["languageCode"] = result.language
    return out


_OPTION_REJECTED_STATUSES = {400, 422}

_ELEVENLABS_MODEL = "scribe_v2"
_ELEVENLABS_LEGACY_MODEL = "scribe_v1"


def _elevenlabs_model(settings: Settings | None) -> str:
    configured = (getattr(settings, "elevenlabs_stt_model", None) or "").strip()
    return (
        configured if configured and configured != _ELEVENLABS_LEGACY_MODEL else _ELEVENLABS_MODEL
    )


def _elevenlabs_fields(settings: Settings | None, *, conservative: bool) -> list[tuple[str, str]]:
    return ADAPTERS["elevenlabs"].fields(_stt_options(settings), conservative=conservative)


def _elevenlabs_text(payload: dict[str, Any]) -> tuple[str, int]:
    return elevenlabs_adapter.parse_text(payload)


def _post_elevenlabs_transcription(
    content: bytes | None,
    filename: str,
    mime_type: str,
    settings: Settings | None,
    *,
    source_path: str | None = None,
) -> dict[str, Any]:
    result = ADAPTERS["elevenlabs"].transcribe(
        _audio(content, filename, mime_type, source_path),
        _stt_options(settings),
        _provider_key("elevenlabs"),
    )
    return _legacy_result(result, language=True)


def _deepgram_params(settings: Settings | None, *, conservative: bool) -> dict[str, Any]:
    return ADAPTERS["deepgram"].params(_stt_options(settings), conservative=conservative)


def _deepgram_text(payload: dict[str, Any]) -> tuple[str, int]:
    return deepgram_adapter.parse_text(payload)


def _post_deepgram_transcription(
    content: bytes | None,
    filename: str,
    mime_type: str,
    settings: Settings | None,
    *,
    source_path: str | None = None,
) -> dict[str, Any]:
    result = ADAPTERS["deepgram"].transcribe(
        _audio(content, filename, mime_type, source_path),
        _stt_options(settings),
        _provider_key("deepgram"),
    )
    return _legacy_result(result, language=False)


def _split_audio_into_chunks(
    content: bytes | None, *, source_path: str | None = None
) -> Iterator[tuple[bytes, str, str]] | None:
    try:
        import io

        from pydub import AudioSegment
    except Exception:  # noqa: BLE001 - missing optional dependency
        logger.warning("pydub/ffmpeg unavailable; long audio cannot be chunked for transcription")
        return None
    try:
        audio = AudioSegment.from_file(source_path or io.BytesIO(content or b""))
    except Exception as exc:  # noqa: BLE001 - undecodable container
        logger.warning("Unable to decode audio for chunked transcription: %s", exc)
        return None

    def _stream() -> Iterator[tuple[bytes, str, str]]:
        for index, start in enumerate(range(0, max(len(audio), 1), TRANSCRIPTION_CHUNK_MS)):
            segment = audio[start : start + TRANSCRIPTION_CHUNK_MS].set_channels(1)
            buffer = io.BytesIO()
            segment.export(buffer, format="mp3", bitrate="64k")
            yield (buffer.getvalue(), f"chunk-{index + 1:03d}.mp3", "audio/mpeg")

    return _stream()


def _transcribe_whisper_sync(
    content: bytes | None,
    filename: str,
    mime_type: str,
    settings: Settings,
    *,
    source_path: str | None = None,
) -> dict[str, Any]:
    if _source_size(content, source_path) <= WHISPER_MAX_BYTES:
        return _post_openai_transcription(
            content, filename, mime_type, settings, source_path=source_path
        )

    chunks = _split_audio_into_chunks(content, source_path=source_path)
    if chunks is None:
        return _post_openai_transcription(
            content, filename, mime_type, settings, source_path=source_path
        )

    pieces: list[str] = []
    count = 0
    for chunk_bytes, chunk_name, chunk_mime in chunks:
        count += 1
        result = _post_openai_transcription(chunk_bytes, chunk_name, chunk_mime, settings)
        piece = str(result.get("text") or "").strip()
        if piece:
            pieces.append(piece)
    if not count:
        return _post_openai_transcription(
            content, filename, mime_type, settings, source_path=source_path
        )
    text = " ".join(pieces).strip()
    result = _transcription_result(text, None)
    result["chunks"] = count
    return result


_PROVIDER_KEYS = {
    "elevenlabs": "ELEVENLABS_API_KEY",
    "deepgram": "DEEPGRAM_API_KEY",
    "whisper": "OPENAI_API_KEY",
    "muse": "META_MODEL_API_KEY",
}

_PROVIDER_NAMES = {
    "elevenlabs": "ElevenLabs",
    "deepgram": "Deepgram",
    "whisper": "Whisper (OpenAI)",
    "muse": "Meta Muse",
}


def transcription_provider_catalog() -> list[tuple[str, str, str]]:
    return [(p, _PROVIDER_NAMES[p], key) for p, key in _PROVIDER_KEYS.items()]


def transcription_provider_configured() -> dict[str, bool]:
    return {provider: bool(_key(name)) for provider, name in _PROVIDER_KEYS.items()}


def transcription_provider_chain(
    settings: Settings | None = None,
    order: list[str] | None = None,
    *,
    disabled: set[str] | frozenset[str] | list[str] | None = None,
) -> list[str]:
    ranked = order if order is not None else list(app_settings.DEFAULT_STT_PROVIDER_ORDER)
    off = set(DISABLED_BY_DEFAULT) if disabled is None else set(disabled)
    return [p for p in ranked if p in _PROVIDER_KEYS and p not in off and _key(_PROVIDER_KEYS[p])]


_PROVIDER_CALLS = {
    "elevenlabs": (_post_elevenlabs_transcription, ELEVENLABS_MAX_BYTES),
    "deepgram": (_post_deepgram_transcription, DEEPGRAM_MAX_BYTES),
    "whisper": (_transcribe_whisper_sync, None),
    "muse": (_post_muse_transcription, None),
}


_AUTH_STATUSES = {401, 403}
_DEFER_STATUSES = {429, 503}


def _rate_limited_result(provider: str, response: Any, code: int) -> dict[str, Any]:
    retry_after = None
    if response is not None:
        try:
            header = response.headers.get("Retry-After")
            retry_after = float(header) if header else None
        except (TypeError, ValueError):
            retry_after = None
    name = _PROVIDER_NAMES.get(provider, provider)
    return {
        "available": True,
        "status": "RATE_LIMITED",
        "text": None,
        "formattedTranscript": None,
        "retryAfter": retry_after,
        "provider": provider,
        "message": f"{name} is busy just now; will retry automatically.",
    }


def _transcribe_sync(
    content: bytes | None,
    filename: str,
    mime_type: str,
    settings: Settings | None,
    chain: list[str],
    *,
    source_path: str | None = None,
    state: ChainState | None = None,
    hooks: ChainHooks | None = None,
    opts: SttOptions | None = None,
) -> dict[str, Any]:
    outcome = run_chain(
        _audio(content, filename, mime_type, source_path),
        transcription_service.build_slots(chain, _key),
        opts or _stt_options(settings),
        state,
        hooks,
        cutter_factory=(
            transcription_audio.PartCutter if transcription_audio.ffmpeg_available() else None
        ),
    )
    return transcription_service.outcome_dict(outcome, _TRANSCRIPTION_MESSAGES)


async def transcribe_audio(file: UploadFile, settings: Settings) -> dict[str, Any]:
    content = await file.read()
    return await transcribe_audio_bytes(
        content,
        file.filename or "recording.webm",
        file.content_type or "audio/webm",
        settings,
    )


async def transcribe_audio_bytes(
    content: bytes | None,
    filename: str,
    mime_type: str,
    settings: Settings,
    *,
    user_id: str | None = None,
    media_id: str | None = None,
    job_id: str | None = None,
    language_bias: list[str] | None = None,
    skip_providers: set[str] | None = None,
    source_path: str | None = None,
    keyterms: list[str] | None = None,
) -> dict[str, Any]:
    await managed_secrets.refresh_if_stale()

    if user_id:
        personal = await user_ai_keys.resolve(user_id, AiTask.TRANSCRIBE)
        if personal is not None and personal.is_user_supplied:
            try:
                answer = await asyncio.to_thread(
                    _transcribe_on_personal_key,
                    content,
                    filename,
                    mime_type,
                    personal,
                    source_path=source_path,
                )
                if answer is not None:
                    return answer
            except Exception as exc:  # noqa: BLE001 - fall into the chain, never fail the recording
                logger.warning(
                    "A designer's own transcription key failed (%s); falling back to the "
                    "server's provider chain",
                    redact_secrets(str(exc)),
                )
    chain = transcription_provider_chain(
        settings,
        await app_settings.load_stt_provider_order(),
        disabled=app_settings.stt_providers_disabled(await app_settings.load_app_settings()),
    )
    if not chain:
        return {
            "available": False,
            "status": "UNAVAILABLE",
            "text": None,
            "formattedTranscript": None,
            "message": (
                "Transcription isn't available right now. An administrator can turn it on in "
                "Settings."
            ),
        }
    store = PrismaTranscriptionStore()
    state = await store.load_state(job_id=job_id, providers=chain)
    state.skip |= set(skip_providers or ())
    hooks = StoreHooks(store, asyncio.get_running_loop(), media_id=media_id, job_id=job_id)
    opts = _stt_options(
        settings, language_bias=language_bias, session_id=media_id, keyterms=keyterms
    )
    try:
        return await asyncio.to_thread(
            _transcribe_sync,
            content,
            filename,
            mime_type,
            settings,
            chain,
            state=state,
            hooks=hooks,
            opts=opts,
            source_path=source_path,
        )
    except requests.HTTPError as exc:
        response = exc.response
        code = response.status_code if response is not None else None
        if code in _DEFER_STATUSES:
            retry_after = None
            if response is not None:
                try:
                    retry_after = (
                        float(response.headers.get("Retry-After"))
                        if response.headers.get("Retry-After")
                        else None
                    )
                except (TypeError, ValueError):
                    retry_after = None
            return {
                "available": True,
                "status": "RATE_LIMITED",
                "text": None,
                "formattedTranscript": None,
                "retryAfter": retry_after,
                "message": "Transcription is busy just now; will retry automatically.",
            }
        logger.error("Transcription failed: %s", redact_secrets(str(exc)))
        return {
            "available": True,
            "status": "FAILED",
            "text": None,
            "formattedTranscript": None,
            "message": f"Transcription failed: {_fault(exc)}. Try again later.",
        }
    except requests.RequestException as exc:
        logger.error("Transcription failed: %s", redact_secrets(str(exc)))
        return {
            "available": True,
            "status": "FAILED",
            "text": None,
            "formattedTranscript": None,
            "message": f"Transcription failed: {_fault(exc)}. Try again later.",
        }


_REFINE_MAX_CHARS = 48_000


def _post_openai_chat(
    messages: list[dict[str, str]], settings: Settings, *, temperature: float = 0.2
) -> str:
    response = requests.post(
        "https://api.openai.com/v1/chat/completions",
        headers={
            "Authorization": f"Bearer {_key('OPENAI_API_KEY')}",
            "Content-Type": "application/json",
        },
        json={
            "model": settings.openai_chat_model,
            "messages": messages,
            **openai_sampling(settings.openai_chat_model, temperature),
        },
        timeout=120,
    )
    response.raise_for_status()
    return openai_message_text(response.json())


def _refine_prompts(text: str, translate_to_english: bool) -> tuple[str, str]:
    clipped = text.strip()[:_REFINE_MAX_CHARS]
    translate_clause = (
        " Then translate the entire conversation into clear, natural English, preserving meaning."
        if translate_to_english
        else ""
    )
    system = (
        "You are an expert interview transcript editor. You reformat a raw, unpunctuated speech-to-text "
        "transcript into a clean, readable dialogue. An interview may involve one interviewer (or more) "
        "and ONE OR MORE interviewees. You fix obvious transcription errors, punctuation and "
        "capitalisation, and split the text into speaker turns. You NEVER invent, add, or remove "
        "information — only restructure and lightly correct what is present. If the speaker of a passage "
        "is genuinely unclear, label it **Speaker:**."
    )
    user = (
        "Reformat the following raw interview transcript into a conversation using Markdown. Put each "
        "turn on its own line, beginning with a bold speaker label, followed by that turn's text. Use "
        "`**Interviewer:**` for the interviewer. There may be MULTIPLE interviewees — when you can tell "
        "them apart, label them `**Interviewee 1:**`, `**Interviewee 2:**`, etc.; if there is clearly "
        "only one, use `**Interviewee:**`. The raw transcript may ALREADY carry `**Speaker 1:**`-style "
        "labels from automatic speaker separation: keep those turn boundaries and rename each speaker "
        "to its role, rather than re-splitting the text yourself. Separate clearly distinct topics or "
        "sections with a Markdown horizontal rule on its own line (`---`). Keep it faithful to the "
        "source." + translate_clause + "\n\nRaw transcript:\n\n" + clipped
    )
    return system, user


def _refine_sync(text: str, translate_to_english: bool, settings: Settings) -> dict[str, Any]:
    system, user = _refine_prompts(text, translate_to_english)
    refined = _post_openai_chat(
        [{"role": "system", "content": system}, {"role": "user", "content": user}],
        settings,
    )
    return {
        "available": True,
        "status": "COMPLETED" if refined else "EMPTY",
        "refined": refined,
        "model": settings.openai_chat_model,
        "translated": translate_to_english,
    }


def _refine_keyed_sync(
    text: str, translate_to_english: bool, credential: AiCredential, settings: Settings
) -> dict[str, Any]:
    system, user = _refine_prompts(text, translate_to_english)
    answer = _chat_verb_keyed(system, user, credential, settings, temperature=0.2)
    refined = answer.get("text") or None
    status = str(answer.get("status") or "")
    if status == "REFUSED":
        return {
            "available": True,
            "status": "FAILED",
            "refined": None,
            "message": "The AI model declined this transcript. The raw transcript is unchanged.",
        }
    return {
        "available": True,
        "status": "COMPLETED" if refined else "EMPTY",
        "refined": refined,
        "model": answer.get("model"),
        "translated": translate_to_english,
    }


async def refine_transcript_text(
    text: str | None,
    translate_to_english: bool,
    settings: Settings,
) -> dict[str, Any]:
    credential = await user_ai_keys.resolve(None, AiTask.TRANSLATE)
    if credential is None:
        return {
            "available": False,
            "status": "UNAVAILABLE",
            "refined": None,
            "message": (
                "Refinement isn't available right now. An administrator can turn it on in "
                "Settings."
            ),
        }
    if not text or not text.strip():
        return {
            "available": True,
            "status": "EMPTY",
            "refined": None,
            "message": "There is no transcript text to refine yet.",
        }
    try:
        return await asyncio.to_thread(
            _refine_keyed_sync, text, translate_to_english, credential, settings
        )
    except ModelDeclined:
        return {
            "available": True,
            "status": "FAILED",
            "refined": None,
            "message": "The AI model declined this transcript. The raw transcript is unchanged.",
        }
    except requests.RequestException as exc:
        logger.error("Transcript refinement failed: %s", redact_secrets(str(exc)))
        return {
            "available": True,
            "status": "FAILED",
            "refined": None,
            "message": (
                f"Refinement failed: {_fault(exc)}. The raw transcript is unchanged. Try again "
                "later."
            ),
        }


VERB_MAX_CHARS = 48_000

VERB_TIMEOUT_SECONDS = 90


def _verb_unavailable(what: str, setting: str, *, in_settings: bool = True) -> dict[str, Any]:
    return {
        "available": False,
        "status": "UNAVAILABLE",
        "text": None,
        "message": (
            f"{what} isn't available right now."
            + (" An administrator can turn it on in Settings." if in_settings else "")
            + " You can write the words yourself — nothing you have typed is changed."
        ),
    }


def _verb_failed(what: str, exc: Exception) -> dict[str, Any]:
    return {
        "available": True,
        "status": "FAILED",
        "text": None,
        "message": (
            f"{what} failed: {_fault(exc)}. Nothing was changed. Try again, or write the words "
            f"yourself."
        ),
    }


def _post_openai_chat_keyed(
    messages: list[dict[str, str]], *, api_key: str, model: str, temperature: float
) -> str:
    response = requests.post(
        "https://api.openai.com/v1/chat/completions",
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        json={"model": model, "messages": messages, **openai_sampling(model, temperature)},
        timeout=VERB_TIMEOUT_SECONDS,
    )
    response.raise_for_status()
    return openai_message_text(response.json())


def _post_gemini_chat(
    system: str, user: str, *, api_key: str, model: str, temperature: float
) -> str:
    model, response = post_gemini(
        requests.post,
        model,
        headers={"x-goog-api-key": api_key},
        json={
            "contents": [{"role": "user", "parts": [{"text": user}]}],
            "systemInstruction": {"parts": [{"text": system}]},
            "generationConfig": {"temperature": temperature},
        },
        timeout=VERB_TIMEOUT_SECONDS,
    )
    response.raise_for_status()
    return gemini_reply_text(response.json()), model


def _model_for(credential: AiCredential, settings: Settings) -> str:
    if credential.provider is AiProvider.OPENAI and not credential.is_user_supplied:
        return settings.openai_chat_model
    return credential.model


def _chat_verb_keyed(
    system: str, user: str, credential: AiCredential, settings: Settings, *, temperature: float
) -> dict[str, Any]:
    model = _model_for(credential, settings)
    try:
        return _chat_verb_dispatch(system, user, credential, model, temperature=temperature)
    except ModelDeclined:
        return {
            "available": True,
            "status": "REFUSED",
            "text": None,
            "provider": credential.provider.value.lower(),
            "model": model,
            "message": "The AI model declined this passage. Nothing was changed; you can write the words yourself.",
        }


def _chat_verb_dispatch(
    system: str, user: str, credential: AiCredential, model: str, *, temperature: float
) -> dict[str, Any]:
    if credential.provider is AiProvider.ANTHROPIC:
        return anthropic_verbs.chat_verb(
            system=system, user=user, api_key=credential.api_key, model=model
        )
    if credential.provider is AiProvider.GEMINI:
        text, model = _post_gemini_chat(
            system, user, api_key=credential.api_key, model=model, temperature=temperature
        )
        provider = "gemini"
    else:
        text = _post_openai_chat_keyed(
            [{"role": "system", "content": system}, {"role": "user", "content": user}],
            api_key=credential.api_key,
            model=model,
            temperature=temperature,
        )
        provider = "openai"
    return {
        "available": True,
        "status": "COMPLETED" if text else "EMPTY",
        "text": text or None,
        "provider": provider,
        "model": model,
    }


def _chat_verb_sync(
    system: str, user: str, settings: Settings, *, temperature: float
) -> dict[str, Any]:
    text = _post_openai_chat(
        [{"role": "system", "content": system}, {"role": "user", "content": user}],
        settings,
        temperature=temperature,
    )
    return {
        "available": True,
        "status": "COMPLETED" if text else "EMPTY",
        "text": text,
        "provider": "openai",
        "model": settings.openai_chat_model,
    }


def _clip_for_verb(text: str, *, verb: str) -> str:
    clean = (text or "").strip()
    if len(clean) <= VERB_MAX_CHARS:
        return clean
    logger.warning(
        "%s was given %s characters and the model is sent at most %s; the tail was not sent. The "
        "layer service refuses anything over its own lower bound, so this caller bypassed it.",
        verb,
        len(clean),
        VERB_MAX_CHARS,
    )
    return clean[:VERB_MAX_CHARS]


async def _run_chat_verb(
    *,
    what: str,
    system: str,
    user: str,
    settings: Settings,
    temperature: float,
    task: AiTask,
    user_id: str | None = None,
) -> dict[str, Any]:
    credential = await user_ai_keys.resolve(user_id, task)
    if credential is None:
        return _verb_unavailable(what, "an AI provider key")
    try:
        return await asyncio.to_thread(
            _chat_verb_keyed, system, user, credential, settings, temperature=temperature
        )
    except anthropic_verbs.AnthropicUnavailable:
        logger.error("%s could not run: the anthropic package is not installed", what)
        return _verb_unavailable(what, "the anthropic package", in_settings=False)
    except requests.RequestException as exc:
        logger.error("%s failed: %s", what, redact_secrets(str(exc)))
        return _verb_failed(what, exc)
    except Exception as exc:  # noqa: BLE001 - an SDK error must become a sentence, never a 500
        logger.error("%s failed: %s", what, redact_secrets(str(exc)))
        return _verb_failed(what, exc)


_PROOFREAD_SYSTEM = (
    "You are a meticulous copy-editor working on field research notes and interview transcripts "
    "from craft workshops in India. You correct spelling, grammar, punctuation and capitalisation, "
    "and you change NOTHING else.\n"
    "You NEVER translate. If the text is in Hindi, Odia, Marwari or any other language, or switches "
    "between languages mid-sentence, it stays in exactly those languages — including romanised text, "
    "which stays romanised.\n"
    "You NEVER add, remove, reorder or summarise information. You do not add a concluding sentence, "
    "you do not expand an abbreviation, and you do not turn a note into prose.\n"
    "You NEVER change a proper noun, a place name, a person's name, a craft term, a measurement or a "
    "number, even where one looks misspelled — an unfamiliar word in this material is usually a "
    "craft term and not an error.\n"
    "You keep the layout exactly: line breaks, bullet points, dashes, and any **Speaker:** labels "
    "stay where they are.\n"
    "If there is nothing to correct, return the text exactly as it was given to you."
)


async def proofread_text(
    text: str | None, settings: Settings, *, user_id: str | None = None
) -> dict[str, Any]:
    clean = (text or "").strip()
    if not clean:
        return {
            "available": True,
            "status": "EMPTY",
            "text": None,
            "message": "There is nothing to proofread yet — type or dictate the passage first.",
        }
    terms = craft_keyterms()
    vocabulary = (
        "\n\nThese are craft terms from this research. They are spelled correctly and must not be "
        "changed: " + ", ".join(terms[:120])
        if terms
        else ""
    )
    return await _run_chat_verb(
        what="Proofreading",
        system=_PROOFREAD_SYSTEM + vocabulary,
        user=(
            "Return the corrected text and nothing else — no preamble, no explanation, no list of "
            "what you changed.\n\n" + _clip_for_verb(clean, verb="proofread")
        ),
        settings=settings,
        temperature=0.0,
        task=AiTask.PROOFREAD,
        user_id=user_id,
    )


_EXPAND_SYSTEM = (
    "You expand a designer's shorthand field note into clear, plain prose for a craft documentation "
    "report.\n"
    "THE MOST IMPORTANT RULE: you add NO FACTS. Every name, number, material, measurement, place, "
    "date, technique and person in your output must appear in the note. You do not infer, you do not "
    "supply typical values, you do not describe what a craft 'usually' involves, and you never write "
    "a sentence whose content is not in the note.\n"
    "Where the note is ambiguous, keep the ambiguity rather than resolving it — write what the note "
    "says, not what it probably meant.\n"
    "Write in the same language as the note. Do not translate.\n"
    "Keep craft terms, place names and personal names exactly as written.\n"
    "Write plainly and briefly: this goes into a government record, not a brochure. No adjectives of "
    "praise, no 'rich heritage', no concluding flourish.\n"
    "If the note is too sparse to expand without inventing, say so in one sentence instead of "
    "expanding it."
)


async def expand_text(
    text: str | None, settings: Settings, *, user_id: str | None = None
) -> dict[str, Any]:
    clean = (text or "").strip()
    if not clean:
        return {
            "available": True,
            "status": "EMPTY",
            "text": None,
            "message": "There is no note to expand yet — type or dictate a few words first.",
        }
    return await _run_chat_verb(
        what="Expanding a note",
        system=_EXPAND_SYSTEM,
        user=(
            "Expand this note into prose, adding no facts that are not in it. Return the prose and "
            "nothing else.\n\n" + _clip_for_verb(clean, verb="expand")
        ),
        settings=settings,
        temperature=0.3,
        task=AiTask.EXPAND,
        user_id=user_id,
    )


_TRANSLATE_SYSTEM = (
    "You are a translator working on craft research from India: interview transcripts, artisans' own "
    "words, and designers' field notes.\n"
    "You translate meaning, not word order. The result must read naturally to a reader of the target "
    "language.\n"
    "You NEVER translate a proper noun, a place name, a person's name or a craft term. A craft term "
    "(dabu, ringal, bandhani, and the like) is TRANSLITERATED and left as itself; where its meaning "
    "is not obvious you may add a short gloss in brackets the first time it appears, and only then.\n"
    "You NEVER add or remove information, and you never smooth over a passage you cannot make out — "
    "mark it [unclear] rather than guessing at it.\n"
    "You keep the layout exactly, including any **Speaker:** labels, which stay where they are with "
    "the label itself translated only if it is an ordinary word.\n"
    "The source may switch language mid-sentence. Translate all of it into the target language."
)


async def translate_text(
    text: str | None,
    *,
    target_language: str,
    source_language: str | None,
    settings: Settings,
    user_id: str | None = None,
) -> dict[str, Any]:
    clean = (text or "").strip()
    if not clean:
        return {
            "available": True,
            "status": "EMPTY",
            "text": None,
            "message": "There is nothing to translate yet.",
        }
    from_clause = (
        f" The source is in {source_language}."
        if source_language and source_language.lower() != "multi"
        else " The source may be in several languages, interleaved."
        if source_language
        else ""
    )
    return await _run_chat_verb(
        what="Translation",
        system=_TRANSLATE_SYSTEM,
        user=(
            f"Translate the following into {target_language}.{from_clause} Return the translation "
            f"and nothing else — no preamble and no note about the translation.\n\n"
            + _clip_for_verb(clean, verb="translate")
        ),
        settings=settings,
        temperature=0.1,
        task=AiTask.TRANSLATE,
        user_id=user_id,
    )


_SUMMARISE_SYSTEM = (
    "You summarise field notes and interview transcripts from craft documentation workshops in "
    "India, for a reader who has not read the original.\n"
    "THE MOST IMPORTANT RULE: you add NOTHING. Every fact in your summary must be in the source. You "
    "do not infer, you do not supply what a craft 'usually' involves, and you do not resolve an "
    "ambiguity — where the source is unclear, the summary says so.\n"
    "NEVER drop a number, a measurement, a date, a price, a quantity, a material, a place name or a "
    "person's name that the source states. Those are the record; the prose around them is not.\n"
    "NEVER drop a problem, a failure, a disagreement or a complaint. A summary that keeps only what "
    "went well is a false report, and this material is read to find out what is going wrong.\n"
    "Write in the same language as the source. Do not translate.\n"
    "Keep craft terms, place names and personal names exactly as written.\n"
    "Write plainly and briefly — this goes into a government record. No adjectives of praise, no "
    "concluding flourish, no 'in conclusion'.\n"
    "If the source is already shorter than a summary would be, say so in one sentence instead of "
    "padding it."
)


async def summarise_text(
    text: str | None, settings: Settings, *, user_id: str | None = None
) -> dict[str, Any]:
    clean = (text or "").strip()
    if not clean:
        return {
            "available": True,
            "status": "EMPTY",
            "text": None,
            "message": "There is nothing to summarise yet — type or dictate the passage first.",
        }
    return await _run_chat_verb(
        what="Summarising",
        system=_SUMMARISE_SYSTEM,
        user=(
            "Summarise the following. Return the summary and nothing else — no preamble, no "
            "heading, no note about what you left out.\n\n"
            + _clip_for_verb(clean, verb="summarise")
        ),
        settings=settings,
        temperature=0.2,
        task=AiTask.SUMMARISE,
        user_id=user_id,
    )


summarize_text = summarise_text


CAPTION_PROVIDER = "gemini"

_CAPTION_PROMPT = (
    "This is a photograph from a craft documentation workshop in India. Write ONE sentence "
    "describing what is visible in it, for a report annexure and for a reader using a screen "
    "reader.\n"
    "Describe only what you can see: the object, the material, the activity, the tools, the setting. "
    "Do NOT name the craft, the technique, the region, the community or the artisan — you cannot know "
    "any of them from a photograph, and this caption will be printed in a government record beside "
    "somebody's name.\n"
    "Do not guess at a person's identity, age, caste, religion or relationship to anybody else. Where "
    "a person is visible, describe what they are doing and nothing about who they are.\n"
    "If the photograph is too unclear to describe, say exactly that in one sentence.\n"
    'Return JSON only: {"caption": "…", "confidence": 0.0 to 1.0, "notes": "…"}.'
)


def _caption_language_clause(language: str | None) -> str:
    token = (language or "").strip()
    if not token or token.lower() == "multi":
        return ""
    return f"\nWrite the caption in {token}, and in no other language."


def _post_gemini_caption(
    content: bytes,
    mime_type: str,
    settings: Settings,
    language: str | None = None,
    api_key: str | None = None,
    model: str | None = None,
) -> dict[str, Any]:
    keys = [api_key] if api_key else managed_secrets.gemini_key_pool()
    caption_model = model or settings.gemini_measurement_model
    if not keys:
        raise RuntimeError("No Gemini API key configured")
    body = {
        "contents": [
            {
                "parts": [
                    {"text": _CAPTION_PROMPT + _caption_language_clause(language)},
                    {
                        "inlineData": {
                            "mimeType": mime_type or "image/jpeg",
                            "data": base64.b64encode(content).decode("ascii"),
                        }
                    },
                ]
            }
        ],
        "generationConfig": {"responseMimeType": "application/json"},
    }
    start = _next_gemini_start(len(keys))
    ordered_keys = keys[start:] + keys[:start]
    last_error: Exception | None = None
    for model_id in gemini_models(caption_model):
        for attempt, key in enumerate(ordered_keys):
            try:
                response = requests.post(
                    "https://generativelanguage.googleapis.com/v1beta/models/"
                    f"{model_id}:generateContent",
                    headers={"x-goog-api-key": key},
                    json=body,
                    timeout=VERB_TIMEOUT_SECONDS,
                )
            except requests.RequestException as exc:
                last_error = exc
                logger.info(
                    "Gemini key #%s network error while captioning, rotating: %s",
                    (start + attempt) % len(keys),
                    redact_secrets(str(exc)),
                )
                continue
            if response.status_code == 404:
                last_error = requests.HTTPError("Gemini model unavailable (HTTP 404)", response=response)
                break
            if response.status_code in _GEMINI_ROTATE_STATUSES:
                last_error = requests.HTTPError(
                    f"Gemini rejected the request (HTTP {response.status_code})", response=response
                )
                logger.info(
                    "Gemini key #%s returned HTTP %s while captioning, rotating",
                    (start + attempt) % len(keys),
                    response.status_code,
                )
                continue
            try:
                response.raise_for_status()
            except requests.RequestException as exc:
                last_error = exc
                continue
            payload = response.json()
            raw = (
                payload.get("candidates", [{}])[0]
                .get("content", {})
                .get("parts", [{}])[0]
                .get("text", "")
            )
            parsed = _extract_json(raw)
            caption = str(parsed.get("caption") or parsed.get("rawText") or "").strip()
            return {
                "available": True,
                "status": "COMPLETED" if caption else "EMPTY",
                "text": caption,
                "provider": CAPTION_PROVIDER,
                "model": model_id,
                "selfReportedConfidence": self_reported_confidence(parsed),
                "confidenceIsCalibrated": False,
            }
    raise last_error or RuntimeError("All configured Gemini keys failed")


async def caption_image_bytes(
    content: bytes,
    mime_type: str,
    settings: Settings,
    language: str | None = None,
    *,
    user_id: str | None = None,
) -> dict[str, Any]:
    await managed_secrets.refresh_if_stale()
    if not content:
        return {
            "available": True,
            "status": "EMPTY",
            "text": None,
            "message": "That file is empty, so there is nothing to describe.",
        }

    credential = await user_ai_keys.resolve(user_id, AiTask.CAPTION)
    if credential is not None and credential.is_user_supplied:
        prompt = _CAPTION_PROMPT + _caption_language_clause(language)
        try:
            if credential.provider is AiProvider.ANTHROPIC:
                return await asyncio.to_thread(
                    anthropic_verbs.caption_image,
                    prompt=prompt,
                    content=content,
                    mime_type=mime_type or "image/jpeg",
                    api_key=credential.api_key,
                    model=credential.model,
                )
            if credential.provider is AiProvider.GEMINI:
                return await asyncio.to_thread(
                    _post_gemini_caption,
                    content,
                    mime_type,
                    settings,
                    language,
                    credential.api_key,
                    credential.model,
                )
            return await asyncio.to_thread(
                _caption_openai_sync, prompt, content, mime_type, credential
            )
        except anthropic_verbs.AnthropicUnavailable:
            logger.error("Captioning could not run: the anthropic package is not installed")
            return _verb_unavailable(
                "Describing a photograph", "the anthropic package", in_settings=False
            )
        except Exception as exc:  # noqa: BLE001 - a personal key's failure is still a sentence
            logger.error("Captioning failed on a designer's own key: %s", redact_secrets(str(exc)))
            return _verb_failed("Describing a photograph", exc)

    if not managed_secrets.gemini_key_pool():
        return _verb_unavailable("Describing a photograph", "GEMINI_API_KEY")
    try:
        return await asyncio.to_thread(_post_gemini_caption, content, mime_type, settings, language)
    except requests.RequestException as exc:
        logger.error("Captioning failed: %s", redact_secrets(str(exc)))
        return _verb_failed("Describing a photograph", exc)


def _caption_openai_sync(
    prompt: str, content: bytes, mime_type: str, credential: AiCredential
) -> dict[str, Any]:
    response = requests.post(
        "https://api.openai.com/v1/chat/completions",
        headers={
            "Authorization": f"Bearer {credential.api_key}",
            "Content-Type": "application/json",
        },
        json={
            "model": credential.model,
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": prompt},
                        {
                            "type": "image_url",
                            "image_url": {
                                "url": "data:"
                                + (mime_type or "image/jpeg")
                                + ";base64,"
                                + base64.b64encode(content).decode("ascii")
                            },
                        },
                    ],
                }
            ],
        },
        timeout=VERB_TIMEOUT_SECONDS,
    )
    response.raise_for_status()
    payload = response.json()
    caption = str(payload["choices"][0]["message"]["content"] or "").strip()
    return {
        "available": True,
        "status": "COMPLETED" if caption else "EMPTY",
        "text": caption,
        "provider": "openai",
        "model": credential.model,
        "selfReportedConfidence": None,
        "confidenceIsCalibrated": False,
    }



def _elevenlabs_cues(payload: dict[str, Any]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    untimed = 0
    for word in payload.get("words") or []:
        if word.get("type") in {"audio_event", "spacing"}:
            continue
        text = str(word.get("text") or "").strip()
        start, end = word.get("start"), word.get("end")
        if not text:
            continue
        if start is None or end is None:
            untimed += 1
            continue
        speaker = word.get("speaker_id")
        out.append(
            {
                "start": float(start),
                "end": float(end),
                "text": text,
                "speaker": str(speaker) if speaker is not None else "",
            }
        )
    if untimed:
        logger.warning(
            "elevenlabs: %d of %d spoken words carried no timings and are absent from the subtitle; "
            "the transcript of the same response still has them",
            untimed,
            untimed + len(out),
        )
    return out


def _deepgram_words(alternative: dict[str, Any]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for word in alternative.get("words") or []:
        text = str(word.get("punctuated_word") or word.get("word") or "").strip()
        start, end = word.get("start"), word.get("end")
        if text and start is not None and end is not None:
            speaker = word.get("speaker")
            out.append(
                {
                    "start": float(start),
                    "end": float(end),
                    "text": text,
                    "speaker": str(speaker) if speaker is not None else "",
                }
            )
    return out


def _words_inside(words: list[dict[str, Any]], start: float, end: float) -> list[dict[str, Any]]:
    inside: list[dict[str, Any]] = []
    for word in words:
        middle = (word["start"] + word["end"]) / 2
        if start <= middle < end:
            inside.append(word)
    return inside


def _spoken_characters(text: str) -> str:
    return "".join(character.lower() for character in text if character.isalnum())


def _words_cover(words: list[dict[str, Any]], text: str) -> bool:
    ordered = sorted(
        words, key=lambda word: (float(word.get("start") or 0.0), float(word.get("end") or 0.0))
    )
    spelled = _spoken_characters(" ".join(str(word.get("text") or "") for word in ordered))
    return _spoken_characters(text) in spelled


def _deepgram_cues(payload: dict[str, Any]) -> list[dict[str, Any]]:
    channels = (payload.get("results") or {}).get("channels") or []
    alternatives = (channels[0].get("alternatives") if channels else None) or []
    alternative = alternatives[0] if alternatives else {}
    words = _deepgram_words(alternative)

    out: list[dict[str, Any]] = []
    sentences_seen = False
    for paragraph in (alternative.get("paragraphs") or {}).get("paragraphs") or []:
        speaker = paragraph.get("speaker")
        label = str(speaker) if speaker is not None else ""
        for sentence in paragraph.get("sentences") or []:
            text = str(sentence.get("text") or "").strip()
            start, end = sentence.get("start"), sentence.get("end")
            if not text or start is None or end is None:
                continue
            sentences_seen = True
            start, end = float(start), float(end)
            if not subtitles.over_ceilings(seconds=end - start, text=text):
                out.append({"start": start, "end": end, "text": text, "speaker": label})
                continue
            inside = _words_inside(words, start, end)
            if inside and _words_cover(inside, text):
                out.extend({**word, "speaker": label} for word in inside)
            else:
                logger.warning(
                    "deepgram: a %.1fs sentence exceeded a cue ceiling and its measured words "
                    "(%d in its window) did not account for it; its captions will carry estimated "
                    "boundaries",
                    end - start,
                    len(inside),
                )
                out.append({"start": start, "end": end, "text": text, "speaker": label})
    if sentences_seen:
        return out
    return words


def _timed_provider_chain(chain: list[str]) -> list[str]:
    return [provider for provider in chain if provider in {"elevenlabs", "deepgram"}]


def _transcribe_timed_sync(
    content: bytes | None,
    filename: str,
    mime_type: str,
    settings: Settings,
    chain: list[str],
    *,
    source_path: str | None = None,
) -> dict[str, Any]:
    errors: list[str] = []
    size = _source_size(content, source_path)
    for provider in chain:
        _call, max_bytes = _PROVIDER_CALLS[provider]
        if max_bytes is not None and size > max_bytes:
            errors.append(f"{_PROVIDER_NAMES.get(provider, provider)}: the file is too large")
            continue
        try:
            answered = ADAPTERS[provider].transcribe(
                _audio(content, filename, mime_type, source_path),
                _stt_options(settings, keep_payload=True),
                _provider_key(provider),
            )
            payload = answered.payload or {}
            model = answered.model
            if provider == "elevenlabs":
                fragments = _elevenlabs_cues(payload)
                language = payload.get("language_code")
            else:
                fragments = _deepgram_cues(payload)
                language = "multi"
        except requests.RequestException as exc:
            errors.append(f"{_PROVIDER_NAMES.get(provider, provider)}: {_fault(exc)}")
            logger.warning(
                "%s timed transcription failed (%s); trying next provider",
                provider,
                redact_secrets(str(exc)),
            )
            continue
        if fragments:
            return {
                "available": True,
                "status": "COMPLETED",
                "fragments": fragments,
                "provider": provider,
                "model": model,
                "language": language,
            }
        errors.append(f"{_PROVIDER_NAMES.get(provider, provider)}: no timed words were found")
    return {
        "available": True,
        "status": "FAILED" if errors else "EMPTY",
        "fragments": [],
        "message": "; ".join(errors) or "No speech with timings was found in that recording.",
    }


async def transcribe_timed_bytes(
    content: bytes | None,
    filename: str,
    mime_type: str,
    settings: Settings,
    *,
    source_path: str | None = None,
) -> dict[str, Any]:
    await managed_secrets.refresh_if_stale()
    chain = _timed_provider_chain(
        transcription_provider_chain(
            settings,
            await app_settings.load_stt_provider_order(),
            disabled=app_settings.stt_providers_disabled(await app_settings.load_app_settings()),
        )
    )
    if not chain:
        return {
            "available": False,
            "status": "UNAVAILABLE",
            "fragments": [],
            "message": (
                "Subtitles aren't available right now. An administrator can turn them on in "
                "Settings by adding an ElevenLabs or Deepgram key."
            ),
        }
    try:
        return await asyncio.to_thread(
            _transcribe_timed_sync,
            content,
            filename,
            mime_type,
            settings,
            chain,
            source_path=source_path,
        )
    except requests.RequestException as exc:
        logger.error("Timed transcription failed: %s", redact_secrets(str(exc)))
        return {
            "available": True,
            "status": "FAILED",
            "fragments": [],
            "message": (
                f"Subtitling failed: {_fault(exc)}. Nothing was recorded. Try again later."
            ),
        }


def _extract_json(text: str) -> dict[str, Any]:
    cleaned = text.strip()
    if cleaned.startswith("```"):
        cleaned = cleaned.strip("`")
        cleaned = cleaned.removeprefix("json").strip()
    try:
        parsed = json.loads(cleaned)
        return parsed if isinstance(parsed, dict) else {"raw": parsed}
    except json.JSONDecodeError:
        return {"rawText": text}


_DIMENSION_ALIASES = {
    "length": "length",
    "breadth": "breadth",
    "width": "breadth",
    "height": "height",
}

MEASUREMENT_DIMENSIONS: frozenset[str] = frozenset({"length", "breadth", "height"})

MEASUREMENT_PROVIDER = "gemini"


class UnknownDimension(ValueError):
    pass


def normalize_dimension(raw: str | None) -> str | None:
    if raw is None or not raw.strip():
        return None
    dimension = _DIMENSION_ALIASES.get(raw.strip().lower())
    if dimension is None or dimension not in MEASUREMENT_DIMENSIONS:
        raise UnknownDimension(
            f"{raw.strip()!r} is not a dimension this reader can measure. Ask for length, breadth "
            "or height, or ask for none of them to read length and breadth from one photograph."
        )
    return dimension


def _measurement_prompt(dimension: str | None) -> str:
    dimension = normalize_dimension(dimension)
    if dimension:
        dim = dimension
        return (
            f"The image shows a single craft object placed on a 1 inch square grid sheet. "
            f"By counting the grid squares the object spans, estimate the object's {dim} in inches. "
            f"Return JSON only with valueInches (a number, or null if it cannot be determined), "
            f"confidence from 0 to 1, and notes. If the grid or object is unclear, return null for "
            f"valueInches and explain why in notes."
        )
    return (
        "The image shows a craft object placed on a 1 inch square grid sheet. "
        "Estimate the object's length and breadth in inches. Return JSON only with "
        "lengthInches, breadthInches, confidence from 0 to 1, and notes. If the grid "
        "or object is unclear, return null values and explain in notes."
    )


GEMINI_VISION_FALLBACK_MODEL = "gemini-3.7-flash"


def measurement_models(settings: Settings) -> tuple[str, ...]:
    configured = settings.gemini_measurement_model
    if configured == GEMINI_VISION_FALLBACK_MODEL:
        return (configured,)
    return (configured, GEMINI_VISION_FALLBACK_MODEL)


def _post_gemini_measurement(
    content: bytes, mime_type: str, settings: Settings, dimension: str | None = None
) -> dict[str, Any]:
    keys = managed_secrets.gemini_key_pool()
    if not keys:
        raise RuntimeError("No Gemini API key configured")

    prompt = _measurement_prompt(dimension)
    body = {
        "contents": [
            {
                "parts": [
                    {"text": prompt},
                    {
                        "inlineData": {
                            "mimeType": mime_type or "image/jpeg",
                            "data": base64.b64encode(content).decode("ascii"),
                        }
                    },
                ]
            }
        ],
        "generationConfig": {"responseMimeType": "application/json"},
    }

    start = _next_gemini_start(len(keys))
    ordered_keys = keys[start:] + keys[:start]
    last_error: Exception | None = None

    for model_id in measurement_models(settings):
        for attempt, key in enumerate(ordered_keys):
            try:
                response = requests.post(
                    f"https://generativelanguage.googleapis.com/v1beta/models/{model_id}:generateContent",
                    headers={"x-goog-api-key": key},
                    json=body,
                    timeout=90,
                )
            except requests.RequestException as exc:
                last_error = exc
                logger.info(
                    "Gemini key #%s network error, rotating: %s",
                    (start + attempt) % len(keys),
                    redact_secrets(str(exc)),
                )
                continue

            if response.status_code == 404:
                last_error = requests.HTTPError("Gemini model unavailable (HTTP 404)", response=response)
                break

            if response.status_code in _GEMINI_ROTATE_STATUSES:
                last_error = requests.HTTPError(
                    f"Gemini rejected the request (HTTP {response.status_code})", response=response
                )
                logger.info(
                    "Gemini key #%s returned HTTP %s, rotating: %s",
                    (start + attempt) % len(keys),
                    response.status_code,
                    redact_secrets(str(response.text)[:200]),
                )
                continue

            try:
                response.raise_for_status()
            except requests.RequestException as exc:
                last_error = exc
                continue

            payload = response.json()
            text = (
                payload.get("candidates", [{}])[0]
                .get("content", {})
                .get("parts", [{}])[0]
                .get("text", "")
            )
            parsed = _extract_json(text)
            return {
                "available": True,
                "status": "COMPLETED",
                "analysis": parsed,
                "keysTried": attempt + 1,
                "raw": payload,
                **_measurement_provenance(parsed, settings, model_id).payload(),
            }

    raise last_error or RuntimeError("All configured Gemini keys failed")


def _measurement_provenance(
    analysis: dict[str, Any] | None, settings: Settings, model_id: str | None = None
) -> MeasurementProvenance:
    return vision_model_provenance(
        analysis,
        provider=MEASUREMENT_PROVIDER,
        model_id=model_id or settings.gemini_measurement_model,
    )


async def analyze_measurement_image_bytes(
    content: bytes,
    filename: str,
    mime_type: str,
    settings: Settings,
    dimension: str | None = None,
    *,
    user_id: str | None = None,
) -> dict[str, Any]:
    await managed_secrets.refresh_if_stale()
    if not managed_secrets.gemini_key_pool():
        return {
            "available": False,
            "status": "UNAVAILABLE",
            "analysis": None,
            "message": (
                "Grid measurement isn't available right now. Measure the object and type the "
                "value in. An administrator can turn it on in Settings."
            ),
            **_measurement_provenance(None, settings).payload(),
        }
    await paid_ai_allowance.take(paid_ai_allowance.MEASUREMENT, user_id)
    try:
        return await asyncio.to_thread(
            _post_gemini_measurement,
            content,
            mime_type,
            settings,
            dimension,
        )
    except requests.RequestException as exc:
        logger.error("Gemini measurement analysis failed: %s", redact_secrets(str(exc)))
        return {
            "available": True,
            "status": "FAILED",
            "analysis": None,
            "message": (
                f"Measurement analysis failed: {_fault(exc)}. Measure the object and enter the "
                "value manually."
            ),
            **_measurement_provenance(None, settings).payload(),
        }
