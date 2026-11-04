from __future__ import annotations

import logging
import re
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import datetime
from enum import Enum
from typing import Any

from app.services import ai_layers, subtitles
from app.services.ai_layers import (
    AiTier,
    LayerKind,
    LayerRuleViolation,
    LayerSource,
    LayerWritePlan,
)

logger = logging.getLogger(__name__)


class Verb(str, Enum):
    PROOFREAD = "PROOFREAD"
    EXPAND = "EXPAND"
    TRANSLATE = "TRANSLATE"
    CAPTION = "CAPTION"
    SUBTITLES = "SUBTITLES"

    @property
    def human(self) -> str:
        return {
            Verb.PROOFREAD: "proofreading",
            Verb.EXPAND: "expanding a note",
            Verb.TRANSLATE: "translation",
            Verb.CAPTION: "describing a photograph",
            Verb.SUBTITLES: "subtitling",
        }[self]


class VerbError(ValueError):
    pass


class VerbUnavailable(VerbError):
    pass



@dataclass(frozen=True, slots=True)
class VerbRun:
    tier: AiTier
    provider: str
    model_id: str
    produced_at: datetime | None = None
    model_version: str | None = None

    @classmethod
    def of_answer(cls, answer: Mapping[str, Any], *, tier: AiTier, at: datetime | None) -> VerbRun:
        return cls(
            tier=tier,
            provider=str(answer.get("provider") or "").strip() or ai_layers.UNRECORDED,
            model_id=str(answer.get("model") or "").strip() or ai_layers.UNRECORDED,
            produced_at=at,
        )


_LANGUAGE_TOKEN = re.compile(r"^[A-Za-z][A-Za-z0-9 \-_()']{0,39}$")

MAX_LANGUAGE_CHARS = 40


def clean_language(raw: str | None, *, what: str) -> str | None:
    token = (raw or "").strip()
    if not token:
        return None
    if not _LANGUAGE_TOKEN.match(token):
        raise VerbError(
            f"{token[:MAX_LANGUAGE_CHARS]!r} isn't a language that can be used for {what}. Give "
            f"the language as a name or a code — 'Odia', 'or', 'Hindi', 'multi' — of at most "
            f"{MAX_LANGUAGE_CHARS} characters, with no punctuation."
        )
    return token



def text_of_answer(answer: Mapping[str, Any], *, verb: Verb) -> str:
    status = str(answer.get("status") or "").upper()
    message = str(answer.get("message") or "").strip()
    if status == "UNAVAILABLE" or answer.get("available") is False:
        raise VerbUnavailable(
            message or f"{verb.human.capitalize()} isn't available right now."
        )
    if status == "REFUSED":
        raise VerbError(
            message
            or f"The AI model declined this, so {verb.human} recorded nothing. You can write the words yourself."
        )
    if status in {"EMPTY", "FAILED"}:
        raise VerbError(message or f"{verb.human.capitalize()} produced nothing.")
    text = str(answer.get("text") or "").strip()
    if not text:
        raise VerbError(
            f"{verb.human.capitalize()} came back with no words, so nothing was recorded. Try "
            f"again."
        )
    return text



def proofread(
    *,
    workshop_id: str,
    source: LayerSource,
    answer: Mapping[str, Any],
    run: VerbRun,
    language: str | None = None,
    created_by_id: str | None = None,
) -> LayerWritePlan:
    return ai_layers.layer_create_plan(
        workshop_id=workshop_id,
        kind=LayerKind.PROOFREAD,
        tier=run.tier,
        source=source,
        provider=run.provider,
        model_id=run.model_id,
        model_version=run.model_version,
        language=language,
        produced_at=run.produced_at,
        text=text_of_answer(answer, verb=Verb.PROOFREAD),
        created_by_id=created_by_id,
    )


def expand(
    *,
    workshop_id: str,
    note: str,
    answer: Mapping[str, Any],
    run: VerbRun,
    language: str | None = None,
    created_by_id: str | None = None,
) -> LayerWritePlan:
    return ai_layers.layer_create_plan(
        workshop_id=workshop_id,
        kind=LayerKind.EXPANDED,
        tier=run.tier,
        source=LayerSource.supplied_text(note),
        provider=run.provider,
        model_id=run.model_id,
        model_version=run.model_version,
        language=language,
        produced_at=run.produced_at,
        text=text_of_answer(answer, verb=Verb.EXPAND),
        created_by_id=created_by_id,
    )


def translate(
    *,
    workshop_id: str,
    source: LayerSource,
    answer: Mapping[str, Any],
    run: VerbRun,
    source_language: str | None,
    target_language: str,
    created_by_id: str | None = None,
) -> LayerWritePlan:
    return ai_layers.layer_create_plan(
        workshop_id=workshop_id,
        kind=LayerKind.TRANSLATION,
        tier=run.tier,
        source=source,
        provider=run.provider,
        model_id=run.model_id,
        model_version=run.model_version,
        source_language=source_language,
        target_language=target_language,
        produced_at=run.produced_at,
        text=text_of_answer(answer, verb=Verb.TRANSLATE),
        created_by_id=created_by_id,
    )


ON_DEVICE_MODEL_IDS: frozenset[str] = frozenset(
    {"gemma-4-E2B-it.litertlm", "gemma-4-E4B-it.litertlm"}
)

ON_DEVICE_PROVIDER = "litert-lm"

ON_DEVICE_KINDS: frozenset[LayerKind] = frozenset({LayerKind.PROOFREAD, LayerKind.TRANSLATION})


def on_device(
    *,
    workshop_id: str,
    kind: LayerKind,
    text: str,
    source_text: str,
    model_id: str,
    model_version: str,
    language: str,
    produced_at: datetime | None,
    created_by_id: str | None = None,
) -> LayerWritePlan:
    if kind not in ON_DEVICE_KINDS:
        raise VerbError(f"A {kind.value} layer is not accepted from a handset.")
    if model_id not in ON_DEVICE_MODEL_IDS:
        raise VerbError("That model is not one this app runs on a handset.")
    run = VerbRun(
        tier=AiTier.TIER_2,
        provider=ON_DEVICE_PROVIDER,
        model_id=model_id,
        produced_at=produced_at,
        model_version=model_version,
    )
    answer = {"status": "COMPLETED", "text": text}
    source = LayerSource.supplied_text(source_text)
    if kind is LayerKind.TRANSLATION:
        return translate(
            workshop_id=workshop_id,
            source=source,
            answer=answer,
            run=run,
            source_language=ai_layers.UNRECORDED,
            target_language=language,
            created_by_id=created_by_id,
        )
    return proofread(
        workshop_id=workshop_id,
        source=source,
        answer=answer,
        run=run,
        language=language,
        created_by_id=created_by_id,
    )


def caption(
    *,
    workshop_id: str,
    media_id: str,
    answer: Mapping[str, Any],
    run: VerbRun,
    language: str | None = None,
    created_by_id: str | None = None,
) -> LayerWritePlan:
    text = text_of_answer(answer, verb=Verb.CAPTION)
    confidence = answer.get("selfReportedConfidence")
    payload: dict[str, Any] | None = None
    if confidence is not None:
        payload = {
            "selfReportedConfidence": confidence,
            "confidenceIsCalibrated": False,
        }
    return ai_layers.layer_create_plan(
        workshop_id=workshop_id,
        kind=LayerKind.CAPTION,
        tier=run.tier,
        source=LayerSource.media(media_id),
        provider=run.provider,
        model_id=run.model_id,
        model_version=run.model_version,
        language=language,
        produced_at=run.produced_at,
        text=text,
        payload=payload,
        created_by_id=created_by_id,
    )


def subtitle(
    *,
    workshop_id: str,
    media_id: str,
    answer: Mapping[str, Any],
    run: VerbRun,
    created_by_id: str | None = None,
) -> LayerWritePlan:
    status = str(answer.get("status") or "").upper()
    if status == "UNAVAILABLE" or answer.get("available") is False:
        raise VerbUnavailable(
            str(answer.get("message") or "").strip()
            or "Subtitling isn't available right now."
        )
    fragments = answer.get("fragments") or []
    if not fragments:
        raise VerbError(
            str(answer.get("message") or "").strip()
            or "No speech with timings was found in that recording, so there is nothing to caption."
        )
    try:
        cues = subtitles.fit_cues(
            subtitles.label_speakers(subtitles.cue_of(fragment) for fragment in fragments)
        )
    except subtitles.SubtitleError as exc:
        raise VerbError(str(exc)) from exc
    if not cues:
        raise VerbError(
            "That recording produced no readable captions — every timed fragment was empty. There "
            "is nothing to record."
        )
    language = str(answer.get("language") or "").strip() or None
    return ai_layers.layer_create_plan(
        workshop_id=workshop_id,
        kind=LayerKind.SUBTITLES,
        tier=run.tier,
        source=LayerSource.media(media_id),
        provider=run.provider,
        model_id=run.model_id,
        model_version=run.model_version,
        language=language,
        produced_at=run.produced_at,
        text=subtitles.plain_reading(cues),
        payload=subtitles.cues_payload(cues, language=language),
        created_by_id=created_by_id,
    )


SUBTITLE_FORMATS: Mapping[str, tuple[Any, str, str]] = {
    "srt": (subtitles.to_srt, "application/x-subrip; charset=utf-8", "srt"),
    "vtt": (subtitles.to_vtt, "text/vtt; charset=utf-8", "vtt"),
}


def render_subtitles(row: Any, *, fmt: str, speakers: bool = False) -> tuple[str, str, str]:
    token = (fmt or "").strip().lower()
    if token not in SUBTITLE_FORMATS:
        raise VerbError(
            f"{fmt!r} isn't a subtitle format that can be downloaded. Choose "
            f"{' or '.join(sorted(SUBTITLE_FORMATS))}."
        )
    if not ai_layers.is_kind(row, LayerKind.SUBTITLES):
        raise VerbError(
            "That layer is not a set of subtitles, so it has no timings to write out. Subtitles are "
            "produced from a recording and carry when each line was said; a transcript does not."
        )
    render, mime, extension = SUBTITLE_FORMATS[token]
    try:
        cues = subtitles.cues_of_payload(getattr(row, "payload", None))
        if speakers and not any(cue.speaker for cue in cues):
            raise VerbError(
                "These subtitles have no speaker labels — only one voice was heard, or the "
                "recording wasn't separated into voices. Download them without speaker labels."
            )
        return render(cues, speakers=speakers), mime, extension
    except subtitles.SubtitleError as exc:
        raise VerbError(str(exc)) from exc


__all__ = [
    "MAX_LANGUAGE_CHARS",
    "SUBTITLE_FORMATS",
    "LayerRuleViolation",
    "Verb",
    "VerbError",
    "VerbRun",
    "VerbUnavailable",
    "caption",
    "clean_language",
    "expand",
    "proofread",
    "render_subtitles",
    "subtitle",
    "text_of_answer",
    "translate",
]
