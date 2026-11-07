from __future__ import annotations

import logging
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime
from enum import Enum
from typing import Any

from app.core.db import db
from app.services.text_format import by_count

logger = logging.getLogger(__name__)



class LayerKind(str, Enum):
    RAW_TRANSCRIPT = "RAW_TRANSCRIPT"
    CLEANED_TRANSCRIPT = "CLEANED_TRANSCRIPT"
    SUMMARY = "SUMMARY"
    OCR_TEXT = "OCR_TEXT"
    STRUCTURED_TEXT = "STRUCTURED_TEXT"
    TAGS = "TAGS"
    METADATA = "METADATA"
    PROOFREAD = "PROOFREAD"
    EXPANDED = "EXPANDED"
    TRANSLATION = "TRANSLATION"
    CAPTION = "CAPTION"
    SUBTITLES = "SUBTITLES"


class AiTier(str, Enum):
    TIER_1 = "TIER_1"
    TIER_2 = "TIER_2"
    TIER_3 = "TIER_3"

    @property
    def number(self) -> int:
        return int(self.value.rsplit("_", 1)[1])


class SourceKind(str, Enum):
    MEDIA = "MEDIA"
    LAYER = "LAYER"
    SUPPLIED_TEXT = "SUPPLIED_TEXT"


class Decision(str, Enum):
    ACCEPTED = "ACCEPTED"
    WITHDRAWN = "WITHDRAWN"


UNRECORDED = "UNRECORDED"

WRITABLE_TABLES: frozenset[str] = frozenset({"DwAiLayer", "DwAiLayerDecision"})

STAGE_TABLE = "DwStageEntry"

PREVIEW_CHARS = 280


TEXT_KINDS: frozenset[LayerKind] = frozenset(
    {
        LayerKind.RAW_TRANSCRIPT,
        LayerKind.CLEANED_TRANSCRIPT,
        LayerKind.SUMMARY,
        LayerKind.OCR_TEXT,
        LayerKind.PROOFREAD,
        LayerKind.EXPANDED,
        LayerKind.TRANSLATION,
        LayerKind.CAPTION,
    }
)

STRUCTURED_KINDS: frozenset[LayerKind] = frozenset(
    {
        LayerKind.STRUCTURED_TEXT,
        LayerKind.TAGS,
        LayerKind.METADATA,
        LayerKind.SUBTITLES,
    }
)

MEDIA_ROOTED_KINDS: frozenset[LayerKind] = frozenset(
    {
        LayerKind.RAW_TRANSCRIPT,
        LayerKind.OCR_TEXT,
        LayerKind.CAPTION,
        LayerKind.SUBTITLES,
    }
)

TEXT_ROOTED_KINDS: frozenset[LayerKind] = frozenset(
    {
        LayerKind.PROOFREAD,
        LayerKind.EXPANDED,
        LayerKind.TRANSLATION,
    }
)

MAX_SOURCE_TEXT_CHARS = 20_000

DERIVABLE_PROSE_KINDS: frozenset[LayerKind] = frozenset(TEXT_KINDS - {LayerKind.EXPANDED})

ALLOWED_PARENTS: Mapping[LayerKind, frozenset[LayerKind]] = {
    LayerKind.CLEANED_TRANSCRIPT: frozenset({LayerKind.RAW_TRANSCRIPT}),
    LayerKind.SUMMARY: frozenset({LayerKind.RAW_TRANSCRIPT, LayerKind.CLEANED_TRANSCRIPT}),
    LayerKind.STRUCTURED_TEXT: frozenset({LayerKind.OCR_TEXT}),
    LayerKind.TAGS: frozenset(DERIVABLE_PROSE_KINDS | {LayerKind.STRUCTURED_TEXT}),
    LayerKind.METADATA: frozenset(DERIVABLE_PROSE_KINDS | {LayerKind.STRUCTURED_TEXT}),
    LayerKind.PROOFREAD: frozenset(DERIVABLE_PROSE_KINDS - {LayerKind.PROOFREAD}),
    LayerKind.TRANSLATION: frozenset(DERIVABLE_PROSE_KINDS - {LayerKind.TRANSLATION}),
}



class LayerRuleViolation(ValueError):
    pass


def _require_text(value: str | None, *, what: str, remedy: str) -> str:
    text = (value or "").strip()
    if not text:
        raise LayerRuleViolation(f"{what} {remedy}")
    return text



@dataclass(frozen=True, slots=True)
class LayerSource:
    kind: SourceKind
    id: str
    layer_kind: LayerKind | None = None
    text: str | None = None

    def __post_init__(self) -> None:
        if self.kind is SourceKind.SUPPLIED_TEXT:
            supplied = (self.text or "").strip()
            if not supplied:
                raise LayerRuleViolation(
                    "A layer produced from supplied words must carry the words it was given. Send "
                    "the text to work on — an annexure that prints a correction has to be able to "
                    "show what was corrected."
                )
            if self.id:
                raise LayerRuleViolation(
                    "A layer produced from supplied words names no row. Send either the words or "
                    "the id of the layer to work on, never both."
                )
            if self.layer_kind is not None:
                raise LayerRuleViolation(
                    "Supplied words have no parent layer kind. Drop it, or derive this layer from "
                    "the stored layer instead of from the text."
                )
            return
        if not (self.id or "").strip():
            raise LayerRuleViolation(
                "A layer must say what it was derived from. Send the id of the recording or of the "
                "layer it was produced from."
            )
        if self.text is not None:
            raise LayerRuleViolation(
                "A layer derived from a stored row carries no supplied text. Drop it — the words "
                "are read from the row, so a second copy on the layer is a second place for them "
                "to disagree."
            )
        if self.kind is SourceKind.MEDIA and self.layer_kind is not None:
            raise LayerRuleViolation(
                "A layer derived from a recording has no parent layer kind. Drop it, or derive this "
                "layer from the transcript instead of from the file."
            )

    @classmethod
    def media(cls, media_id: str) -> LayerSource:
        return cls(kind=SourceKind.MEDIA, id=media_id)

    @classmethod
    def layer(cls, layer_id: str, layer_kind: LayerKind | None = None) -> LayerSource:
        return cls(kind=SourceKind.LAYER, id=layer_id, layer_kind=layer_kind)

    @classmethod
    def supplied_text(cls, text: str) -> LayerSource:
        return cls(kind=SourceKind.SUPPLIED_TEXT, id="", text=text)

    @property
    def columns(self) -> dict[str, str | None]:
        if self.kind is SourceKind.MEDIA:
            return {"sourceMediaId": self.id, "sourceLayerId": None, "sourceText": None}
        if self.kind is SourceKind.LAYER:
            return {"sourceMediaId": None, "sourceLayerId": self.id, "sourceText": None}
        return {
            "sourceMediaId": None,
            "sourceLayerId": None,
            "sourceText": (self.text or "").strip(),
        }


def source_of(row: Any) -> LayerSource:
    media_id = (getattr(row, "sourceMediaId", None) or "").strip()
    layer_id = (getattr(row, "sourceLayerId", None) or "").strip()
    supplied = (getattr(row, "sourceText", None) or "").strip()
    named = [bool(media_id), bool(layer_id), bool(supplied)]
    if sum(named) != 1:
        raise LayerRuleViolation(
            f"Layer {getattr(row, 'id', '?')} names "
            f"{'more than one source' if any(named) else 'no source'}, which cannot be printed or "
            "trusted. A layer derives from exactly one recording, exactly one other layer, or "
            "exactly one passage of supplied words — repair the row before using it."
        )
    if media_id:
        return LayerSource.media(media_id)
    if supplied:
        return LayerSource.supplied_text(supplied)
    return LayerSource.layer(layer_id)


MAX_CHAIN_HOPS = 8


def media_root(row: Any, by_id: Mapping[str, Any]) -> str | None:
    current = row
    seen: set[str] = set()
    for _ in range(MAX_CHAIN_HOPS):
        try:
            source = source_of(current)
        except LayerRuleViolation:
            return None
        if source.kind is SourceKind.MEDIA:
            return source.id
        if source.id in seen:
            return None
        seen.add(source.id)
        parent = by_id.get(source.id)
        if parent is None:
            return None
        current = parent
    return None


def media_roots(rows: Sequence[Any]) -> dict[str, str | None]:
    by_id = {str(getattr(row, "id", "")): row for row in rows}
    return {str(getattr(row, "id", "")): media_root(row, by_id) for row in rows}


class RootKind(str, Enum):
    MEDIA = "MEDIA"
    SUPPLIED_TEXT = "SUPPLIED_TEXT"
    UNRESOLVED = "UNRESOLVED"


@dataclass(frozen=True, slots=True)
class ChainRoot:
    kind: RootKind
    media_id: str | None = None

    def withheld_from(self, readable_media_ids: Iterable[str]) -> bool:
        if self.kind is RootKind.SUPPLIED_TEXT:
            return False
        if self.kind is RootKind.MEDIA and self.media_id:
            return self.media_id not in set(readable_media_ids)
        return True


def chain_root(row: Any, by_id: Mapping[str, Any]) -> ChainRoot:
    current = row
    seen: set[str] = set()
    for _ in range(MAX_CHAIN_HOPS):
        try:
            source = source_of(current)
        except LayerRuleViolation:
            return ChainRoot(RootKind.UNRESOLVED)
        if source.kind is SourceKind.MEDIA:
            return ChainRoot(RootKind.MEDIA, source.id)
        if source.kind is SourceKind.SUPPLIED_TEXT:
            return ChainRoot(RootKind.SUPPLIED_TEXT)
        if source.id in seen:
            return ChainRoot(RootKind.UNRESOLVED)
        seen.add(source.id)
        parent = by_id.get(source.id)
        if parent is None:
            return ChainRoot(RootKind.UNRESOLVED)
        current = parent
    return ChainRoot(RootKind.UNRESOLVED)


def chain_roots(rows: Sequence[Any]) -> dict[str, ChainRoot]:
    by_id = {str(getattr(row, "id", "")): row for row in rows}
    return {str(getattr(row, "id", "")): chain_root(row, by_id) for row in rows}


def media_ids_to_check(roots: Mapping[str, ChainRoot]) -> set[str]:
    return {
        root.media_id for root in roots.values() if root.kind is RootKind.MEDIA and root.media_id
    }


def is_kind(row: Any, kind: LayerKind) -> bool:
    return _enum_str(getattr(row, "kind", None)) == kind.value


def _kind_of(value: Any) -> LayerKind:
    return LayerKind(str(getattr(value, "value", value)))


def _tier_of(value: Any) -> AiTier:
    return AiTier(str(getattr(value, "value", value)))



class Operation(str, Enum):
    CREATE = "CREATE"
    UPDATE = "UPDATE"


@dataclass(frozen=True, slots=True)
class LayerWritePlan:
    table: str
    operation: Operation
    data: Mapping[str, Any]
    where: Mapping[str, Any] | None = None

    def __post_init__(self) -> None:
        if self.table not in WRITABLE_TABLES:
            raise LayerRuleViolation(
                f"An AI layer may not be written into {self.table}. AI output is annexure content "
                f"and a suggestion: it never feeds a field that is compared between the handset and "
                f"the server, and never a derived or computed one, because the same audio through "
                f"Tier 1 on a phone and Tier 3 in the cloud differs legitimately and for ever. "
                f"Write it to one of {', '.join(sorted(WRITABLE_TABLES))}."
            )
        if self.operation is Operation.UPDATE and not self.where:
            raise LayerRuleViolation(
                "An update must name the single row it changes. Pass where={'id': layer_id}."
            )
        if self.operation is Operation.CREATE and self.where:
            raise LayerRuleViolation("A create names no existing row. Drop the where clause.")



def layer_create_plan(
    *,
    workshop_id: str,
    kind: LayerKind,
    tier: AiTier,
    source: LayerSource,
    provider: str,
    model_id: str,
    model_version: str | None = None,
    language: str | None = None,
    source_language: str | None = None,
    target_language: str | None = None,
    produced_at: datetime | None = None,
    text: str | None = None,
    payload: Any = None,
    created_by_id: str | None = None,
) -> LayerWritePlan:
    workshop = _require_text(
        workshop_id,
        what="A layer belongs to a workshop.",
        remedy="Send the workshop id it was produced for.",
    )
    _check_chain(kind, source)
    _check_source(source)
    _check_content(kind, text=text, payload=payload)
    languages = _check_languages(
        kind, language=language, source_language=source_language, target_language=target_language
    )

    provenance_remedy = (
        f"Name the provider and the model that produced this layer, or pass '{UNRECORDED}' in that "
        f"word if the run did not record one — a systematic model error found in six months cannot "
        f"be traced to the material it damaged without it."
    )
    clean_provider = _require_text(
        provider, what="This layer does not say what produced it.", remedy=provenance_remedy
    )
    clean_model = _require_text(
        model_id, what="This layer does not say which model produced it.", remedy=provenance_remedy
    )

    data: dict[str, Any] = {
        "designWorkshopId": workshop,
        "kind": kind.value,
        "tier": tier.value,
        **source.columns,
        "provider": clean_provider,
        "modelId": clean_model,
        "modelVersion": (model_version or "").strip() or None,
        **languages,
        "producedAt": produced_at,
        "text": (text or "").strip() or None,
        "payload": payload,
        "createdById": created_by_id or None,
    }
    return LayerWritePlan(table="DwAiLayer", operation=Operation.CREATE, data=data)


def check_placement(kind: LayerKind, source: LayerSource) -> None:
    _check_chain(kind, source)
    _check_source(source)


def _where_it_may_sit(kind: LayerKind) -> str:
    places: list[str] = []
    if kind in MEDIA_ROOTED_KINDS:
        places.append("a recording or a photograph")
    parents = sorted(k.value for k in ALLOWED_PARENTS.get(kind, frozenset()))
    if parents:
        places.append(f"a layer of kind {', '.join(parents)}")
    if kind in TEXT_ROOTED_KINDS:
        places.append("words sent with the request")
    return "; ".join(places) or "nothing"


def _check_source(source: LayerSource) -> None:
    if source.kind is not SourceKind.SUPPLIED_TEXT:
        return
    supplied = (source.text or "").strip()
    if len(supplied) > MAX_SOURCE_TEXT_CHARS:
        raise LayerRuleViolation(
            f"That is {len(supplied):,} characters and at most {MAX_SOURCE_TEXT_CHARS:,} can be "
            f"worked on at once. Choose just the passage you want done."
        )


def _check_languages(
    kind: LayerKind,
    *,
    language: str | None,
    source_language: str | None,
    target_language: str | None,
) -> dict[str, str | None]:
    plain = (language or "").strip() or None
    source_lang = (source_language or "").strip() or None
    target_lang = (target_language or "").strip() or None

    if kind is not LayerKind.TRANSLATION:
        if source_lang or target_lang:
            raise LayerRuleViolation(
                f"A {kind.value} is not a translation, so it records one language and not a pair. "
                f"Send `language` alone — or register a TRANSLATION if the words did change "
                f"language, which is a separate layer standing beside this one."
            )
        return {"language": plain, "sourceLanguage": None, "targetLanguage": None}

    if not target_lang:
        raise LayerRuleViolation(
            "A translation must say which language it is INTO. Send targetLanguage — a translated "
            "passage in a report whose language nobody recorded cannot be checked against the "
            "original by anybody."
        )
    if target_lang.lower() == "multi":
        raise LayerRuleViolation(
            "'multi' is something a recording can BE, not something a translation can be INTO. "
            "Name the one language the text was translated into."
        )
    if not source_lang:
        raise LayerRuleViolation(
            "A translation must say which language it came FROM. Send sourceLanguage, or "
            f"'{UNRECORDED}' in that word if the run genuinely did not detect one — a reader who "
            "wants to check the translation against what the artisan said has to know what they "
            "said it in. 'multi' is a real answer here: these interviews code-switch mid-sentence."
        )
    if plain and plain != target_lang:
        raise LayerRuleViolation(
            f"This layer says its text is in {plain} and that it was translated into {target_lang}. "
            f"They are the same fact and cannot disagree — send targetLanguage alone and leave "
            f"language out."
        )
    return {"language": target_lang, "sourceLanguage": source_lang, "targetLanguage": target_lang}


def _check_chain(kind: LayerKind, source: LayerSource) -> None:
    if source.kind is SourceKind.MEDIA:
        if kind not in MEDIA_ROOTED_KINDS:
            raise LayerRuleViolation(
                f"A {kind.value} is produced from another layer, not straight from a media file. "
                f"Register the transcript or the OCR text first, then derive this from it — an "
                f"annexure that prints a conclusion has to be able to show what it was drawn from."
            )
        return

    if source.kind is SourceKind.SUPPLIED_TEXT:
        if kind not in TEXT_ROOTED_KINDS:
            raise LayerRuleViolation(
                f"A {kind.value} is not produced from words sent with the request. It may sit on: "
                f"{_where_it_may_sit(kind)}."
            )
        return

    if kind in MEDIA_ROOTED_KINDS and kind not in ALLOWED_PARENTS:
        raise LayerRuleViolation(
            f"A {kind.value} is produced from a recording or a photograph, not from another "
            f"layer. Derive it from the media file itself."
        )
    allowed = ALLOWED_PARENTS.get(kind, frozenset())
    if not allowed:
        raise LayerRuleViolation(
            f"Nothing may be derived from another layer as a {kind.value}. It may sit on: "
            f"{_where_it_may_sit(kind)}."
        )
    parent = source.layer_kind
    if parent is None:
        raise LayerRuleViolation(
            f"A {kind.value} must name the KIND of the layer it was produced from, because what "
            f"may sit above a rung depends on which rung it is. Load the source layer and pass its "
            f"kind."
        )
    if parent not in allowed:
        raise LayerRuleViolation(
            f"A {kind.value} cannot be derived from a {parent.value}. "
            f"It may sit on: {_where_it_may_sit(kind)}."
        )


def _check_content(kind: LayerKind, *, text: str | None, payload: Any) -> None:
    has_text = bool((text or "").strip())
    has_payload = payload is not None
    if kind in TEXT_KINDS and not has_text:
        raise LayerRuleViolation(
            f"A {kind.value} is prose and this one has none. Send the text the model produced."
        )
    if kind in STRUCTURED_KINDS and not has_payload:
        raise LayerRuleViolation(
            f"A {kind.value} needs the tags, details or values the model produced, and this one "
            f"has none."
        )


def transcript_rungs(
    *, transcript_text: str | None, transcript_summary: str | None
) -> tuple[str, str | None]:
    stored = (transcript_text or "").strip()
    provider_text = (transcript_summary or "").strip()
    if not stored and not provider_text:
        raise LayerRuleViolation(
            "This recording has no transcript to register. Wait for the transcription to finish — "
            "the transcripts screen shows where it is up to."
        )
    if not provider_text:
        return stored, None
    if not stored or provider_text in stored:
        return provider_text, None
    return provider_text, stored


def duplicate_of(
    existing: Iterable[Any],
    *,
    source: LayerSource,
    kind: LayerKind,
    tier: AiTier,
    provider: str,
    model_id: str,
) -> Any | None:
    for row in existing:
        if getattr(row, "deletedAt", None) is not None:
            continue
        try:
            row_source = source_of(row)
            same = (
                row_source.kind is source.kind
                and row_source.id == source.id
                and (row_source.text or "").strip() == (source.text or "").strip()
                and _kind_of(getattr(row, "kind", "")) is kind
                and _tier_of(getattr(row, "tier", "")) is tier
                and (getattr(row, "provider", "") or "") == provider
                and (getattr(row, "modelId", "") or "") == model_id
            )
        except ValueError:
            logger.warning("ai_layers: layer %s cannot be compared", getattr(row, "id", "?"))
            continue
        if same:
            return row
    return None



@dataclass(frozen=True, slots=True)
class DecisionPlans:
    layer: LayerWritePlan
    decision: LayerWritePlan

    def __iter__(self):
        yield self.layer
        yield self.decision


def acceptance_plan(
    row: Any, *, actor_id: str, at: datetime, note: str | None = None
) -> DecisionPlans:
    layer_id = _live_layer_id(row, verb="accepted")
    if getattr(row, "acceptedAt", None) is not None:
        raise LayerRuleViolation(
            "This layer has already been accepted. Withdraw the acceptance first if it should stand "
            "in somebody else's name."
        )
    actor = _require_text(
        actor_id,
        what="An acceptance records who made it.",
        remedy="Sign in and accept it as yourself — an unsigned acceptance is not one.",
    )
    return DecisionPlans(
        layer=LayerWritePlan(
            table="DwAiLayer",
            operation=Operation.UPDATE,
            where={"id": layer_id},
            data={"acceptedAt": at, "acceptedById": actor},
        ),
        decision=_decision_plan(layer_id, Decision.ACCEPTED, actor, note),
    )


def withdrawal_plan(row: Any, *, actor_id: str, note: str | None = None) -> DecisionPlans:
    layer_id = _live_layer_id(row, verb="withdrawn")
    if getattr(row, "acceptedAt", None) is None:
        raise LayerRuleViolation(
            "This layer has not been accepted, so there is nothing to withdraw. Delete it instead "
            "if it should not be offered again."
        )
    actor = _require_text(
        actor_id,
        what="A withdrawal records who made it.",
        remedy="Sign in and withdraw it as yourself.",
    )
    return DecisionPlans(
        layer=LayerWritePlan(
            table="DwAiLayer",
            operation=Operation.UPDATE,
            where={"id": layer_id},
            data={"acceptedAt": None, "acceptedById": None},
        ),
        decision=_decision_plan(layer_id, Decision.WITHDRAWN, actor, note),
    )


def _decision_plan(
    layer_id: str, decision: Decision, actor_id: str, note: str | None
) -> LayerWritePlan:
    return LayerWritePlan(
        table="DwAiLayerDecision",
        operation=Operation.CREATE,
        data={
            "layerId": layer_id,
            "decision": decision.value,
            "note": (note or "").strip() or None,
            "actorId": actor_id,
        },
    )



def deletion_plan(
    row: Any, *, actor_id: str | None, at: datetime, derived: Sequence[Any] = ()
) -> LayerWritePlan:
    layer_id = _live_layer_id(row, verb="deleted")
    live = [d for d in derived if getattr(d, "deletedAt", None) is None]
    if live:
        kinds = sorted({_enum_str(getattr(d, "kind", None)) or "layer" for d in live})
        raise LayerRuleViolation(
            f"{len(live)} {by_count(len(live), 'layer was', 'layers were')} produced from this one "
            f"({', '.join(kinds)}) and would be left describing something no longer on screen. "
            f"Delete {by_count(len(live), 'that', 'those')} first, or leave this one "
            f"in place — nothing here is printed unless it has been accepted."
        )
    return LayerWritePlan(
        table="DwAiLayer",
        operation=Operation.UPDATE,
        where={"id": layer_id},
        data={"deletedAt": at, "deletedById": actor_id or None},
    )


def _live_layer_id(row: Any, *, verb: str) -> str:
    layer_id = _require_text(
        getattr(row, "id", ""),
        what="That layer could not be identified.",
        remedy="Reload the workshop's layers and try again.",
    )
    if getattr(row, "deletedAt", None) is not None:
        raise LayerRuleViolation(
            f"This layer has been deleted and cannot be {verb}. Register it again if the material "
            f"is still wanted — the deleted row stays as the record that it was declined."
        )
    return layer_id



def layer_payload(
    row: Any, *, include_text: bool = False, text_withheld: bool = False
) -> dict[str, Any]:
    text = getattr(row, "text", None) or ""
    accepted_at = getattr(row, "acceptedAt", None)
    source = None
    try:
        stored = source_of(row)
        source = {
            "kind": stored.kind.value,
            "id": stored.id or None,
            "text": None if text_withheld else stored.text,
        }
    except LayerRuleViolation:
        logger.warning("ai_layers: layer %s has no usable source", getattr(row, "id", "?"))

    payload: dict[str, Any] = {
        "id": getattr(row, "id", None),
        "designWorkshopId": getattr(row, "designWorkshopId", None),
        "kind": _enum_str(getattr(row, "kind", None)),
        "tier": _enum_str(getattr(row, "tier", None)),
        "source": source,
        "provider": getattr(row, "provider", None),
        "modelId": getattr(row, "modelId", None),
        "modelVersion": getattr(row, "modelVersion", None),
        "language": getattr(row, "language", None),
        "sourceLanguage": getattr(row, "sourceLanguage", None),
        "targetLanguage": getattr(row, "targetLanguage", None),
        "producedAt": _iso(getattr(row, "producedAt", None)),
        "createdAt": _iso(getattr(row, "createdAt", None)),
        "createdById": getattr(row, "createdById", None),
        "accepted": accepted_at is not None,
        "acceptedAt": _iso(accepted_at),
        "acceptedById": getattr(row, "acceptedById", None),
        "textChars": None if text_withheld else (len(text) if text else 0),
        "preview": None if text_withheld else _preview(text),
        "payload": None if text_withheld else getattr(row, "payload", None),
        "textWithheld": text_withheld,
        "deletedAt": _iso(getattr(row, "deletedAt", None)),
    }
    if include_text and not text_withheld:
        payload["text"] = text or None
    return payload


def decision_payload(row: Any) -> dict[str, Any]:
    return {
        "id": getattr(row, "id", None),
        "layerId": getattr(row, "layerId", None),
        "decision": _enum_str(getattr(row, "decision", None)),
        "note": getattr(row, "note", None),
        "actorId": getattr(row, "actorId", None),
        "createdAt": _iso(getattr(row, "createdAt", None)),
    }


def _preview(text: str) -> str | None:
    flat = " ".join((text or "").split())
    if not flat:
        return None
    if len(flat) <= PREVIEW_CHARS:
        return flat
    return flat[:PREVIEW_CHARS].rstrip() + "…"


def _enum_str(value: Any) -> str | None:
    if value is None:
        return None
    return str(getattr(value, "value", value))


def _iso(value: Any) -> str | None:
    return value.isoformat() if isinstance(value, datetime) else None



def _writable_model(table: str, client: Any = None) -> Any:
    if table not in WRITABLE_TABLES:
        raise LayerRuleViolation(
            f"There is no AI-layer writer for {table}. AI output is annexure content and a "
            f"suggestion; it is written to {', '.join(sorted(WRITABLE_TABLES))} and nowhere else."
        )
    target = client if client is not None else db
    return {"DwAiLayer": target.dwailayer, "DwAiLayerDecision": target.dwailayerdecision}[table]


JSON_COLUMNS: Mapping[str, tuple[str, ...]] = {"DwAiLayer": ("payload",)}


def _json_ready(table: str, data: Mapping[str, Any]) -> dict[str, Any]:
    from prisma import Json

    out = dict(data)
    for column in JSON_COLUMNS.get(table, ()):
        if column in out:
            out[column] = Json(out[column])
    return out


async def apply_plan(plan: LayerWritePlan, client: Any = None) -> Any:
    model = _writable_model(plan.table, client)
    data = _json_ready(plan.table, plan.data)
    if plan.operation is Operation.CREATE:
        return await model.create(data=data)
    return await model.update(where=dict(plan.where or {}), data=data)


async def apply_decision(plans: DecisionPlans, *, workshop_id: str) -> Any:
    from app.services.design_workshop_approvals import open_header

    async with db.tx() as tx:
        await open_header(tx, workshop_id)
        layer = await apply_plan(plans.layer, tx)
        await apply_plan(plans.decision, tx)
    return layer


async def workshop_layers(workshop_id: str, *, include_deleted: bool = False) -> list[Any]:
    where: dict[str, Any] = {"designWorkshopId": workshop_id}
    if not include_deleted:
        where["deletedAt"] = None
    return await db.dwailayer.find_many(where=where, order={"createdAt": "desc"})


async def accepted_layers(workshop_id: str, *, kind: LayerKind | None = None) -> list[Any]:
    where: dict[str, Any] = {
        "designWorkshopId": workshop_id,
        "deletedAt": None,
        "acceptedAt": {"not": None},
    }
    if kind is not None:
        where["kind"] = kind.value
    return await db.dwailayer.find_many(where=where, order={"acceptedAt": "desc"})


async def layers_from_media(workshop_id: str, media_id: str) -> list[Any]:
    return await db.dwailayer.find_many(
        where={"designWorkshopId": workshop_id, "sourceMediaId": media_id}
    )


async def derived_from(layer_id: str) -> list[Any]:
    return await db.dwailayer.find_many(where={"sourceLayerId": layer_id, "deletedAt": None})


async def layer_in_workshop(workshop_id: str, layer_id: str) -> Any | None:
    row = await db.dwailayer.find_unique(where={"id": layer_id})
    if row is None or row.deletedAt is not None:
        return None
    if row.designWorkshopId != workshop_id:
        return None
    return row


async def layer_decisions(layer_id: str) -> list[Any]:
    return await db.dwailayerdecision.find_many(
        where={"layerId": layer_id}, order={"createdAt": "asc"}
    )


DECLINED = "DECLINED"


async def decisions_for_layers(layer_ids: Sequence[str]) -> list[Any]:
    ids = sorted({str(layer_id) for layer_id in layer_ids if layer_id})
    if not ids:
        return []
    return await db.dwailayerdecision.find_many(
        where={"layerId": {"in": ids}}, order=[{"createdAt": "asc"}, {"id": "asc"}]
    )


def history_entries(layers: Sequence[Any], decisions: Sequence[Any]) -> list[dict[str, Any]]:
    kinds = {str(getattr(row, "id", "")): _enum_str(getattr(row, "kind", None)) for row in layers}
    entries: list[dict[str, Any]] = []
    for row in decisions:
        layer_id = str(getattr(row, "layerId", "") or "")
        entries.append(
            {
                "id": str(getattr(row, "id", "") or ""),
                "layerId": layer_id,
                "layerKind": kinds.get(layer_id),
                "decision": _enum_str(getattr(row, "decision", None)),
                "note": getattr(row, "note", None),
                "actorId": getattr(row, "actorId", None),
                "actorName": None,
                "createdAt": _iso(getattr(row, "createdAt", None)),
            }
        )
    for row in layers:
        declined_at = getattr(row, "deletedAt", None)
        if declined_at is None:
            continue
        layer_id = str(getattr(row, "id", "") or "")
        entries.append(
            {
                "id": f"declined-{layer_id}",
                "layerId": layer_id,
                "layerKind": kinds.get(layer_id),
                "decision": DECLINED,
                "note": None,
                "actorId": getattr(row, "deletedById", None),
                "actorName": None,
                "createdAt": _iso(declined_at),
            }
        )
    entries.sort(key=lambda entry: (entry["createdAt"] or "", entry["id"]), reverse=True)
    return entries


async def with_actor_names(entries: list[dict[str, Any]]) -> list[dict[str, Any]]:
    ids = sorted({str(entry["actorId"]) for entry in entries if entry.get("actorId")})
    if not ids:
        return entries
    users = await db.user.find_many(where={"id": {"in": ids}})
    names = {
        str(user.id): (getattr(user, "name", None) or getattr(user, "email", None))
        for user in users
    }
    for entry in entries:
        actor = entry.get("actorId")
        entry["actorName"] = names.get(str(actor)) if actor else None
    return entries
