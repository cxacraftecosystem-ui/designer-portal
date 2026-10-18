"""Interview summaries, key quotes and English translations of transcripts.

For each transcript the queue worker writes one ``TranscriptInsight`` row: up to five English
summary lines, up to five key quotes (the original words and their English), and an English
translation of the whole transcript when it is in another language. Each run is a new row with the
provider, the model and the prompt version on it; a researcher's edit changes only the current row
and the machine's own output stays frozen in ``generated``.

The Field Repository has the same table, the same routes and this same module; only :func:`_chat`
differs. Here it is ``ai._run_chat_verb``, the plumbing every text verb already uses: the resolved
key (the requester's own, else the deployment's), a thread hop, and every failure as a sentence.

SWITCHED OFF UNLESS BOTH FLAGS ARE ON. ``enabled()`` needs ``AI_FEATURES_ENABLED`` and
``AI_TRANSCRIPT_INSIGHTS_ENABLED`` (app/ai_features). With either off nothing is enqueued, queued
jobs are not drained, every route answers 404 and the clients draw nothing.

ALL GENERATION GOES THROUGH THE QUEUE. Nothing here is called from a request handler except
``enqueue``; the backfill script only enqueues too.

WHICH TEXT IS THE SOURCE. In the REFINED_TRANSLATED transcription mode ``transcriptText`` is already
an English rewrite and the raw original-language text sits in ``transcriptSummary`` (a misnamed
column: it is not a summary). :func:`select_sources` reads both, so quotes can be given in the
speaker's own words. Nothing here writes to either column.

THE TRANSCRIPT LEAVES THE SERVER, and only with the artisan's consent. The queue re-reads
``dictation_consent.transcription_verdict`` with :data:`dictation_consent.INSIGHTS` immediately
before sending, so a withdrawal stops a job queued under the grant. A quote the model returns that
is not in the transcript is dropped: it would be attributed to an artisan who never said it. Only
ids and exception classes are logged by this module, never transcript text or a key.
"""

from __future__ import annotations

import hashlib
import json
import logging
import re
from datetime import UTC, datetime
from typing import Any

from app.ai_features.settings import get_ai_settings
from app.ai_features.types import Capability
from app.core.config import get_settings
from app.core.db import db
from app.services.ai_providers import AiTask
from prisma import Json

logger = logging.getLogger(__name__)

PROMPT_VERSION = "transcript-insights/1"
CHUNK_CHARS = 12_000
SUMMARY_MAX_LINES = 5
QUOTES_MAX = 5
#: What a researcher may save: more quotes than the machine is asked for.
QUOTES_MAX_EDIT = 8
SUMMARY_LINE_MAX_CHARS = 400
QUOTE_FIELD_MAX_CHARS = 1_000
TRANSLATION_MAX_CHARS = 200_000
#: The same input ceiling the other text verbs use (``ai.VERB_MAX_CHARS``).
SOURCE_CLIP_CHARS = 48_000

JOB_TYPE = "TRANSCRIPT_INSIGHTS"
ACTIVE_JOB_STATUSES = ("QUEUED", "PROCESSING")
#: The meter label on ``DwAiVerbDailyUsage`` for a researcher's Regenerate.
CAP_VERB = "TRANSCRIPT_INSIGHTS"

NOT_NEEDED = "NOT_NEEDED"
COMPLETED = "COMPLETED"
PARTIAL = "PARTIAL"
FAILED = "FAILED"

ALREADY_ENGLISH_NOTE = "The transcript is already in English."
TRANSLATION_FAILED_NOTE = "The translation couldn't be finished. Regenerate to try again."

UNRECORDED = "UNRECORDED"

SUMMARY_SYSTEM = (
    "You summarise interview transcripts from craft documentation fieldwork in India, for a reader "
    "who has not read the original.\n"
    "THE MOST IMPORTANT RULE: you add NOTHING. Every fact in your summary must be in the source. You "
    "do not infer, you do not supply what a craft 'usually' involves, and you do not resolve an "
    "ambiguity; where the source is unclear, the summary says so.\n"
    "NEVER drop a number, a measurement, a date, a price, a quantity, a material, a place name or a "
    "person's name that the source states.\n"
    "NEVER drop a problem, a failure, a disagreement or a complaint. A summary that keeps only what "
    "went well is a false report.\n"
    "Keep craft terms, place names and personal names exactly as written.\n"
    "Write the summary in plain English, at most five short lines. No praise, no concluding "
    "flourish.\n"
    "Pick at most five key quotes: short passages copied EXACTLY, character for character, from the "
    "quote source, in its own language and script, each with an English rendering. Never "
    "paraphrase a quote. If a speaker label is shown, give it; otherwise null.\n"
    "Report the main language of the primary transcript as a BCP-47 code (for example hi, gu, en), "
    "or 'multi' when no one language dominates.\n"
    'Reply with JSON only: {"primaryLanguage": "hi", "summary": ["...", "..."], '
    '"quotes": [{"original": "...", "english": "...", "speaker": null}]}.'
)

#: ``ai._TRANSLATE_SYSTEM``'s wording, held here so the Field Repository copy of this module (which
#: has no such constant) and this one send the same instruction.
TRANSLATE_SYSTEM = (
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


class InsightsUnavailable(RuntimeError):
    """No provider can be asked (no key, SDK missing). The message is a sentence for a person."""


class InsightsFailed(RuntimeError):
    """A provider call failed or returned nothing usable. The message is a sentence; it retries."""


# --- Switch and sources -----------------------------------------------------------------------


def enabled() -> bool:
    """True only when AI_FEATURES_ENABLED and AI_TRANSCRIPT_INSIGHTS_ENABLED are both on."""
    return get_ai_settings().capability_enabled(Capability.TRANSCRIPT_INSIGHTS)


def max_translate_chars() -> int:
    return get_ai_settings().transcript_insights_max_translate_chars


def _value(record: Any, key: str) -> Any:
    if isinstance(record, dict):
        return record.get(key)
    return getattr(record, key, None)


def select_sources(media: Any) -> tuple[str, str | None]:
    """``(primary, original)``: the transcript to summarise, and the raw original-language text
    under it when there is one.

    ``original`` is ``transcriptSummary`` only when it is non-empty, differs from the primary text,
    and the transcript has not been replaced by a researcher (``transcriptEditedAt`` NULL). After an
    edit the raw provider text no longer describes what is on screen, so it is not used.
    """
    primary = str(_value(media, "transcriptText") or "").strip()
    raw = str(_value(media, "transcriptSummary") or "").strip()
    original = (
        raw if raw and raw != primary and _value(media, "transcriptEditedAt") is None else None
    )
    return primary, original


def source_hash(primary: str, original: str | None) -> str:
    return hashlib.sha256(f"{primary}\x00{original or ''}".encode()).hexdigest()


def media_source_hash(media: Any) -> str:
    return source_hash(*select_sources(media))


# --- Parsing ----------------------------------------------------------------------------------


_FENCE = re.compile(r"^```(?:json)?\s*|\s*```$", re.MULTILINE)
_SPACE = re.compile(r"\s+")


def _normalise(text: str) -> str:
    return _SPACE.sub(" ", text.casefold()).strip()


def _language(raw: Any) -> str | None:
    if not isinstance(raw, str):
        return None
    value = raw.strip().lower()
    if not value or len(value) > 16 or not re.fullmatch(r"[a-z]{2,3}(-[a-z0-9]{2,8})*|multi", value):
        return "en" if value == "english" else None
    return value


def is_english(language: str | None) -> bool:
    return bool(language) and (language == "en" or str(language).startswith("en-"))


def parse_insight(text: str | None, sources: list[str]) -> dict[str, Any]:
    """``{primaryLanguage, summary, quotes}`` from a model reply. Never raises.

    Fenced JSON is accepted. At most five summary lines of 400 characters each are kept. A quote
    whose ``original`` is not found in one of ``sources`` (after casefolding and collapsing
    whitespace) is dropped: an invented quote would be attributed to an artisan.
    """
    empty: dict[str, Any] = {"primaryLanguage": None, "summary": [], "quotes": []}
    if not text:
        return empty
    cleaned = _FENCE.sub("", text.strip())
    start, end = cleaned.find("{"), cleaned.rfind("}")
    if start < 0 or end <= start:
        return empty
    try:
        payload = json.loads(cleaned[start : end + 1])
    except ValueError:
        return empty
    if not isinstance(payload, dict):
        return empty

    summary: list[str] = []
    raw_summary = payload.get("summary")
    if isinstance(raw_summary, str):
        raw_summary = raw_summary.splitlines()
    for line in raw_summary if isinstance(raw_summary, list) else []:
        if isinstance(line, str) and line.strip():
            summary.append(line.strip().lstrip("-•* ").strip()[:SUMMARY_LINE_MAX_CHARS])
        if len(summary) >= SUMMARY_MAX_LINES:
            break

    haystacks = [_normalise(source) for source in sources if source]
    quotes: list[dict[str, Any]] = []
    raw_quotes = payload.get("quotes")
    for item in raw_quotes if isinstance(raw_quotes, list) else []:
        if not isinstance(item, dict):
            continue
        original = item.get("original")
        if not isinstance(original, str) or not original.strip():
            continue
        needle = _normalise(original.strip().strip('"“”'))
        if not needle or not any(needle in hay for hay in haystacks):
            continue
        english = item.get("english")
        speaker = item.get("speaker")
        quotes.append(
            {
                "original": original.strip().strip('"“”')[:QUOTE_FIELD_MAX_CHARS],
                "english": (english.strip() if isinstance(english, str) else "")[
                    :QUOTE_FIELD_MAX_CHARS
                ],
                "speaker": speaker.strip()[:QUOTE_FIELD_MAX_CHARS]
                if isinstance(speaker, str) and speaker.strip()
                else None,
            }
        )
        if len(quotes) >= QUOTES_MAX:
            break

    return {
        "primaryLanguage": _language(payload.get("primaryLanguage")),
        "summary": summary,
        "quotes": quotes,
    }


def chunk_paragraphs(text: str, size: int = CHUNK_CHARS) -> list[str]:
    """Split ``text`` into chunks of at most ``size`` characters, on paragraph bounds where possible.

    Paragraphs (blank-line separated) are packed greedily. A paragraph longer than ``size`` is split
    on line breaks, and a single line longer than that is cut hard.
    """
    pieces: list[str] = []
    for raw_paragraph in re.split(r"\n\s*\n", text.strip()):
        paragraph = raw_paragraph.strip()
        if not paragraph:
            continue
        if len(paragraph) <= size:
            pieces.append(paragraph)
            continue
        line_buffer = ""
        for raw_line in paragraph.splitlines():
            line = raw_line
            while len(line) > size:
                if line_buffer:
                    pieces.append(line_buffer)
                    line_buffer = ""
                pieces.append(line[:size])
                line = line[size:]
            candidate = f"{line_buffer}\n{line}" if line_buffer else line
            if len(candidate) > size:
                pieces.append(line_buffer)
                line_buffer = line
            else:
                line_buffer = candidate
        if line_buffer:
            pieces.append(line_buffer)

    chunks: list[str] = []
    current = ""
    for piece in pieces:
        candidate = f"{current}\n\n{piece}" if current else piece
        if len(candidate) > size and current:
            chunks.append(current)
            current = piece
        else:
            current = candidate
    if current:
        chunks.append(current)
    return chunks


# --- Provider calls ---------------------------------------------------------------------------


async def _chat(
    system: str, user: str, task: AiTask, user_id: str | None, *, temperature: float
) -> dict[str, Any]:
    """One call through ``ai._run_chat_verb``: ``{text, provider, model, modelVersion}``.

    ``user_id`` None runs on the deployment's key (automatic runs and the backfill). Raises
    :class:`InsightsUnavailable` when no key can run it, :class:`InsightsFailed` otherwise; both
    carry a sentence. ``_run_chat_verb`` already logs failures with secrets redacted.
    """
    from app.services import ai  # imported here: ai pulls in the transcription stack

    what = "Summarising" if task is AiTask.SUMMARISE else "Translation"
    result = await ai._run_chat_verb(
        what=what,
        system=system,
        user=user,
        settings=get_settings(),
        temperature=temperature,
        task=task,
        user_id=user_id,
    )
    status = str(result.get("status") or "").upper()
    if status == "UNAVAILABLE" or result.get("available") is False:
        raise InsightsUnavailable(
            "Summaries aren't set up. An administrator can add an AI key in Settings."
        )
    if status == "REFUSED":
        raise InsightsFailed("The AI model declined to summarise this transcript.")
    if status == "FAILED":
        if "busy" in str(result.get("message") or ""):
            raise InsightsFailed("The AI provider is busy. This will retry shortly.")
        raise InsightsFailed("The summary couldn't be written. Try again.")
    return {
        "text": result.get("text"),
        "provider": result.get("provider"),
        "model": result.get("model"),
        "modelVersion": result.get("responseModel"),
    }


def _summary_prompt(primary: str, original: str | None) -> str:
    if original:
        return (
            "Primary transcript (summarise this):\n\n"
            + primary[:SOURCE_CLIP_CHARS]
            + "\n\nQuote source (the same interview in the speakers' own words; copy quotes from "
            "here):\n\n"
            + original[:SOURCE_CLIP_CHARS]
        )
    return (
        "Primary transcript (summarise this; it is also the quote source):\n\n"
        + primary[:SOURCE_CLIP_CHARS]
    )


async def translate(primary: str, user_id: str | None) -> dict[str, Any]:
    """``{status, text, note}`` for an English translation of ``primary``, chunk by chunk.

    Chunks run one at a time (a 1 GiB box). Past ``max_translate_chars`` the result is PARTIAL with
    a note; any failed chunk makes the whole translation FAILED.
    """
    # The greedy first chunk at the cap's size is the longest paragraph-bounded head that fits.
    heads = chunk_paragraphs(primary, max_translate_chars())
    if not heads:
        return {"status": NOT_NEEDED, "text": None, "note": None}
    head = heads[0]
    truncated = len(heads) > 1
    used = len(head)
    out: list[str] = []
    for chunk in chunk_paragraphs(head, CHUNK_CHARS):
        try:
            result = await _chat(
                TRANSLATE_SYSTEM,
                "Translate the following into English. Return the translation and nothing else — "
                "no preamble and no note about the translation.\n\n" + chunk,
                AiTask.TRANSLATE,
                user_id,
                temperature=0.1,
            )
        except (InsightsFailed, InsightsUnavailable):
            return {"status": FAILED, "text": None, "note": TRANSLATION_FAILED_NOTE}
        text = (result.get("text") or "").strip()
        if not text:
            return {"status": FAILED, "text": None, "note": TRANSLATION_FAILED_NOTE}
        out.append(text)
    joined = "\n\n".join(out)
    if truncated:
        return {
            "status": PARTIAL,
            "text": joined,
            "note": f"Translated the first {used:,} characters.",
        }
    return {"status": COMPLETED, "text": joined, "note": None}


# --- Reading ----------------------------------------------------------------------------------


async def current_insight(media_id: str) -> Any | None:
    """The current row: not superseded, highest version (two can be current for a moment while a
    regeneration lands; the newer one wins)."""
    return await db.transcriptinsight.find_first(
        where={"mediaFileId": media_id, "supersededAt": None},
        order={"version": "desc"},
    )


async def current_insights(media_ids: list[str]) -> dict[str, Any]:
    if not media_ids:
        return {}
    rows = await db.transcriptinsight.find_many(
        where={"mediaFileId": {"in": media_ids}, "supersededAt": None},
        order={"version": "desc"},
    )
    out: dict[str, Any] = {}
    for row in rows:
        out.setdefault(row.mediaFileId, row)
    return out


async def latest_jobs(media_ids: list[str]) -> dict[str, Any]:
    if not media_ids:
        return {}
    jobs = await db.mediaprocessingjob.find_many(
        where={"mediaFileId": {"in": media_ids}, "jobType": JOB_TYPE},
        order={"createdAt": "desc"},
    )
    out: dict[str, Any] = {}
    for job in jobs:
        out.setdefault(job.mediaFileId, job)
    return out


async def active_job(media_id: str) -> Any | None:
    return await db.mediaprocessingjob.find_first(
        where={
            "mediaFileId": media_id,
            "jobType": JOB_TYPE,
            "status": {"in": list(ACTIVE_JOB_STATUSES)},
        }
    )


# --- Enqueueing -------------------------------------------------------------------------------


async def enqueue(
    media_id: str, *, requested_by_id: str | None = None, priority: int = 80
) -> Any | None:
    """Queue one insights job. Does nothing (returns None) when the feature is off, the media has
    no transcript, or an insights job for it is already queued or processing."""
    if not enabled():
        return None
    media = await db.mediafile.find_unique(where={"id": media_id})
    if media is None or not select_sources(media)[0]:
        return None
    if await active_job(media_id) is not None:
        return None
    data: dict[str, Any] = {
        "jobType": JOB_TYPE,
        "status": "QUEUED",
        "priority": priority,
        "maxAttempts": max(get_settings().media_queue_job_max_attempts, 1),
        "mediaFileId": media_id,
    }
    if requested_by_id:
        data["requestedById"] = requested_by_id
    return await db.mediaprocessingjob.create(data=data)


async def enqueue_after_transcript(media_id: str) -> Any | None:
    """The automatic trigger after a transcript lands or is edited. Never raises.

    A researcher-edited insight is never replaced automatically: nothing is queued and the reading
    side shows ``stale: true`` instead. Automatic runs carry no requester, so they use the
    deployment's key and spend nobody's daily allowance.
    """
    if not enabled():
        return None
    try:
        current = await current_insight(media_id)
        if current is not None and current.editedAt is not None:
            return None
        return await enqueue(media_id, requested_by_id=None)
    except Exception as exc:  # noqa: BLE001 - a summary must never fail a transcript write
        logger.error("could not queue transcript insights for %s: %s", media_id, type(exc).__name__)
        return None


# --- Generation -------------------------------------------------------------------------------


async def generate(media: Any, *, user_id: str | None) -> dict[str, Any]:
    """Run both calls and store a new version. Called only by the queue worker, after its consent
    check.

    Returns ``{"status": "EMPTY"}`` when there is no transcript, else ``{"status": "COMPLETED",
    "insightId", "version"}``. Raises :class:`InsightsUnavailable` or :class:`InsightsFailed`.
    """
    primary, original = select_sources(media)
    if not primary:
        return {"status": "EMPTY"}
    media_id = str(_value(media, "id"))

    reply = await _chat(
        SUMMARY_SYSTEM,
        _summary_prompt(primary, original),
        AiTask.SUMMARISE,
        user_id,
        temperature=0.2,
    )
    parsed = parse_insight(reply.get("text"), [primary, original or ""])
    if not parsed["summary"]:
        raise InsightsFailed("The summary couldn't be read. Try again.")
    language = parsed["primaryLanguage"]

    if is_english(language):
        translation = {"status": NOT_NEEDED, "text": None, "note": ALREADY_ENGLISH_NOTE}
    else:
        translation = await translate(primary, user_id)

    provider = str(reply.get("provider") or UNRECORDED)
    model_id = str(reply.get("model") or UNRECORDED)
    reported = reply.get("modelVersion")
    model_version = str(reported) if reported and str(reported) != model_id else None
    now = datetime.now(UTC)
    generated = {
        "summaryLines": parsed["summary"],
        "quotes": parsed["quotes"],
        "translationText": translation["text"],
    }

    previous = await db.transcriptinsight.find_first(
        where={"mediaFileId": media_id}, order={"version": "desc"}
    )
    version = (previous.version if previous else 0) + 1
    # Create first, then supersede: a crash between the two leaves two current rows (readers take
    # the higher version) rather than none. The unique (mediaFileId, version) index stops two
    # workers writing the same version.
    row = await db.transcriptinsight.create(
        data={
            "mediaFileId": media_id,
            "version": version,
            "sourceHash": source_hash(primary, original),
            "sourceLanguage": language,
            "summaryLines": parsed["summary"],
            "quotes": Json(parsed["quotes"]),
            "translationStatus": translation["status"],
            "translationText": translation["text"],
            "translationNote": translation["note"],
            "provider": provider,
            "modelId": model_id,
            "modelVersion": model_version,
            "promptVersion": PROMPT_VERSION,
            "generated": Json(generated),
            "generatedAt": now,
            "requestedById": user_id,
        }
    )
    await db.transcriptinsight.update_many(
        where={"mediaFileId": media_id, "supersededAt": None, "version": {"lt": version}},
        data={"supersededAt": now},
    )
    return {"status": "COMPLETED", "insightId": row.id, "version": version}


# --- The view the routes return ---------------------------------------------------------------


def _iso(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        if value.tzinfo is None:
            value = value.replace(tzinfo=UTC)
        return value.astimezone(UTC).isoformat().replace("+00:00", "Z")
    return str(value)


def _quotes(raw: Any) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for item in raw if isinstance(raw, list) else []:
        if isinstance(item, dict) and isinstance(item.get("original"), str):
            out.append(
                {
                    "original": item["original"],
                    "english": item.get("english") if isinstance(item.get("english"), str) else "",
                    "speaker": item.get("speaker") if isinstance(item.get("speaker"), str) else None,
                }
            )
    return out


def insight_payload(row: Any, *, include_translation: bool = True) -> dict[str, Any]:
    return {
        "id": row.id,
        "version": row.version,
        "sourceLanguage": row.sourceLanguage,
        "summaryLines": list(row.summaryLines or []),
        "quotes": _quotes(row.quotes),
        "translation": {
            "status": row.translationStatus,
            "text": row.translationText if include_translation else None,
            "note": row.translationNote,
        },
        "provenance": {
            "provider": row.provider,
            "modelId": row.modelId,
            "modelVersion": row.modelVersion,
            "promptVersion": row.promptVersion,
            "generatedAt": _iso(row.generatedAt),
        },
        # Never names the editor, for the reason EditedFlag does not.
        "edited": {"at": _iso(row.editedAt)} if row.editedAt is not None else None,
    }


def build_view(
    media: Any,
    row: Any | None,
    job: Any | None,
    *,
    may_edit: bool,
    include_translation: bool = True,
) -> dict[str, Any]:
    """The GET shape. ``job`` is the latest insights job for this media, or None."""
    primary, original = select_sources(media)
    job_status = getattr(job, "status", None)
    error = None
    if job_status in ACTIVE_JOB_STATUSES:
        state = job_status
    elif row is not None:
        state = "READY"
    elif job_status == "FAILED":
        state = "FAILED"
        error = getattr(job, "error", None) or "The summary couldn't be written. Try again."
    elif not primary:
        state = "NO_TRANSCRIPT"
    else:
        state = "NOT_GENERATED"
    return {
        "mediaId": str(_value(media, "id")),
        "state": state,
        "stale": bool(row is not None and row.sourceHash != source_hash(primary, original)),
        "canEdit": bool(may_edit and row is not None),
        "canRegenerate": bool(may_edit and primary),
        "error": error,
        "insight": insight_payload(row, include_translation=include_translation)
        if row is not None
        else None,
    }
