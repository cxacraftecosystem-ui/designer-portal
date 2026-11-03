from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from app.services.report_annexures import plain_text, transcript_body_blocks
from app.services.report_model import (
    Block,
    DocumentBuilder,
    PageBreakBlock,
    ParagraphBlock,
    ParaStyle,
    ReportMeta,
    Run,
    TableBlock,
    TableColumn,
    runs_of,
)
from app.services.text_format import by_count, count_noun

DEFAULT_HEADING = "Annexure — Machine-assisted text, and what produced it"

MAX_PARAGRAPHS_PER_LAYER = 1200


_KIND_TITLES: dict[str, str] = {
    "RAW_TRANSCRIPT": "Automatic transcript",
    "CLEANED_TRANSCRIPT": "AI-cleaned transcript",
    "SUMMARY": "AI summary",
    "OCR_TEXT": "Text read automatically from a photograph",
    "STRUCTURED_TEXT": "AI-structured text",
    "TAGS": "AI-suggested tags",
    "METADATA": "AI-extracted details",
    "PROOFREAD": "AI-corrected spelling and punctuation",
    "EXPANDED": "Prose written by AI from a designer's note",
    "TRANSLATION": "AI translation",
    "CAPTION": "AI description of a photograph or video",
    "SUBTITLES": "AI subtitles, with their timings",
}

_TIER_WORDS: dict[str, str] = {
    "TIER_1": "on the handset, with no connection",
    "TIER_2": "on the handset, by a small model held on the device",
    "TIER_3": "online, by a hosted model",
}

UNRECORDED = "UNRECORDED"


def kind_title(kind: str) -> str:
    return _KIND_TITLES.get(str(kind or "").upper()) or "Machine-generated text"


def tier_words(tier: str) -> str:
    return _TIER_WORDS.get(str(tier or "").upper()) or "by a machine this report cannot identify"



@dataclass(frozen=True, slots=True)
class AiLayerItem:
    layer_id: str
    kind: str = ""
    tier: str = ""
    provider: str = ""
    model_id: str = ""
    model_version: str = ""
    language: str = ""
    source_language: str = ""
    target_language: str = ""
    produced_at: str = ""
    accepted: bool = False
    accepted_at: str = ""
    accepted_by: str = ""
    accepted_by_id: str = ""
    source_kind: str = ""
    source_id: str = ""
    source_label: str = ""
    text: str = ""
    text_withheld: bool = False

    @property
    def title(self) -> str:
        return kind_title(self.kind)

    @property
    def acceptor(self) -> str:
        name = (self.accepted_by or "").strip()
        if name:
            return name
        account = (self.accepted_by_id or "").strip()
        if account:
            return f"a user whose name is not recorded (account {account})"
        return "somebody this report cannot identify"

    @property
    def has_text(self) -> bool:
        return bool(self.text and self.text.strip())

    @property
    def word_count(self) -> int:
        return len(plain_text(self.text).split())

    @property
    def model_name(self) -> str:
        model = (self.model_id or "").strip()
        version = (self.model_version or "").strip()
        if not model or model == UNRECORDED:
            return "the model was not recorded"
        return f"{model} {version}".strip()

    @property
    def provider_name(self) -> str:
        provider = (self.provider or "").strip()
        if not provider or provider == UNRECORDED:
            return ""
        return provider


_ATTR = "ai_layers"


def attach_ai_layers(data: Any, items: list[AiLayerItem] | tuple[AiLayerItem, ...]) -> Any:
    try:
        setattr(data, _ATTR, tuple(items))
    except AttributeError:
        return data
    return data


def ai_layers_of(data: Any) -> tuple[AiLayerItem, ...]:
    return tuple(getattr(data, _ATTR, ()) or ())



def layer_index_block(items: tuple[AiLayerItem, ...]) -> TableBlock:
    return TableBlock(
        columns=(
            TableColumn("What it is", 26.0),
            TableColumn("Produced", 26.0),
            TableColumn("Model", 22.0),
            TableColumn("Accepted by", 16.0),
            TableColumn("Words", 10.0, numeric=True),
        ),
        rows=tuple(
            (
                runs_of(item.title),
                runs_of(tier_words(item.tier)),
                runs_of(item.model_name),
                runs_of(item.acceptor),
                runs_of(f"{item.word_count:,}"),
            )
            for item in items
        ),
        caption=(
            "Text in this report that was produced by a machine, and the person who accepted each "
            "passage. Every layer listed here was read against its source and accepted before it "
            "was included."
        ),
    )


def provenance_line(item: AiLayerItem) -> str:
    parts: list[str] = [f"Produced {tier_words(item.tier)}"]
    provider = item.provider_name
    if provider:
        parts.append(f"provider {provider}")
    parts.append(item.model_name)
    if item.target_language.strip():
        source = item.source_language.strip() or UNRECORDED
        source_words = (
            "a language nobody recorded"
            if source == UNRECORDED
            else "several languages, interleaved"
            if source.lower() == "multi"
            else source
        )
        parts.append(f"translated from {source_words} into {item.target_language.strip()}")
    elif item.language and item.language.strip():
        language = item.language.strip()
        parts.append(
            "several languages, interleaved" if language.lower() == "multi" else f"in {language}"
        )
    if item.produced_at:
        parts.append(item.produced_at[:10])
    if item.source_kind:
        origin = item.source_label or item.source_id or "an unnamed source"
        source_kind = item.source_kind.upper()
        if source_kind == "MEDIA":
            parts.append(f"made from the recording “{origin}”")
        elif source_kind == "SUPPLIED_TEXT":
            parts.append(f"made from the note “{_elide(origin)}”")
        else:
            parts.append(f"made from “{origin}”")
    accepted = (f"Accepted by {item.acceptor}") + (
        f" on {item.accepted_at[:10]}" if item.accepted_at else ""
    )
    return " · ".join(parts) + ". " + accepted + "."


SOURCE_QUOTE_CHARS = 240


def _elide(text: str) -> str:
    flat = " ".join((text or "").split())
    if len(flat) <= SOURCE_QUOTE_CHARS:
        return flat
    return flat[:SOURCE_QUOTE_CHARS].rstrip() + "…"


def speaker_labels_are_guessed(item: AiLayerItem) -> bool:
    if str(item.source_kind or "").upper() != "MEDIA":
        return False
    if item.text_withheld or not item.text:
        return False
    return bool(_SPEAKER_TURN.search(item.text))


def layer_body_blocks(item: AiLayerItem) -> list[Block]:
    blocks = transcript_body_blocks(item.text)
    if len(blocks) > MAX_PARAGRAPHS_PER_LAYER:
        kept = blocks[:MAX_PARAGRAPHS_PER_LAYER]
        kept.append(
            ParagraphBlock(
                runs=runs_of(
                    f"[Shortened to the first {MAX_PARAGRAPHS_PER_LAYER} paragraphs. The full text "
                    f"is in the online workshop record.]"
                ),
                style=ParaStyle.NOTE,
            )
        )
        return kept
    return blocks


LEAD = (
    "The passages below were produced by automatic transcription, recognition or summarisation, and "
    "each was read against its source and accepted by the person recorded beside it before it was "
    "included. They are printed separately, and named as machine-assisted, so that nothing in the "
    "body of this report is mistaken for a machine's words and nothing a machine wrote is mistaken "
    "for the author's."
)


EXPANDED_NOTE = (
    "This passage was written by a machine from a short note the designer made. The note itself is "
    "quoted above as the source. Anything in this passage that is not in that note — a detail, a "
    "reason, a connection between two things — was supplied by the model and was not recorded in the "
    "field. Treat the note as the record and this as a reading of it, and check any specific claim "
    "against the workshop's own material before quoting it."
)

_SPEAKER_TURN = re.compile(r"\*\*[^*\n]{1,60}?:\*\*")

SPEAKER_NOTE = (
    "The speaker labels in this passage — who is saying which line — were decided by the machine that "
    "transcribed the recording, not by anybody who was present. Nothing recorded how many people were "
    "speaking or who they were: the labels are that machine's separation of the voices it heard, and "
    "it can merge two speakers into one or split one across two. The numbering is by order of first "
    "speaking and is not a name. Check a line against the recording before attributing it to a "
    "particular person."
)

WITHHELD_NOTE = (
    "The text of this passage is not printed in this copy. It was produced from a recording that the "
    "account which generated this report may not read: on this system, permission to open a workshop "
    "and permission to read a particular recording are granted separately. What produced the passage, "
    "and who accepted it, are stated above and are complete. For a copy carrying the text, ask the "
    "colleague who uploaded the recording to generate the report, or to grant access to their media."
)


def append_ai_layer_annexure(
    doc: DocumentBuilder,
    items: tuple[AiLayerItem, ...] | list[AiLayerItem],
    *,
    heading: str = "",
    numbered: bool = True,
    page_break_before: bool = True,
) -> int:
    printed = [item for item in items if item.accepted and (item.has_text or item.text_withheld)]
    if not printed:
        return 0
    if page_break_before:
        doc.add(PageBreakBlock())
    doc.heading(heading or DEFAULT_HEADING, 1, numbered=numbered)
    doc.para(LEAD, style=ParaStyle.LEAD)
    doc.add(layer_index_block(tuple(printed)))
    for item in printed:
        doc.heading(item.title, 2, numbered=numbered)
        doc.para(provenance_line(item), style=ParaStyle.NOTE)
        if str(item.kind or "").upper() == "EXPANDED":
            doc.para(EXPANDED_NOTE, style=ParaStyle.NOTE)
        if speaker_labels_are_guessed(item):
            doc.para(SPEAKER_NOTE, style=ParaStyle.NOTE)
        if item.text_withheld:
            doc.para(WITHHELD_NOTE, style=ParaStyle.NOTE)
            continue
        for block in layer_body_blocks(item):
            doc.add(block)
    return len(printed)


def ai_layer_annexure_blocks(
    items: tuple[AiLayerItem, ...] | list[AiLayerItem],
    *,
    heading: str = "",
    numbered: bool = True,
    page_break_before: bool = True,
) -> tuple[Block, ...]:
    scratch = DocumentBuilder(meta=ReportMeta(title=""))
    append_ai_layer_annexure(
        scratch, items, heading=heading, numbered=numbered, page_break_before=page_break_before
    )
    return scratch.build().blocks


def annexure_warnings(items: tuple[AiLayerItem, ...] | list[AiLayerItem]) -> list[str]:
    warnings: list[str] = []
    unaccepted = [item for item in items if not item.accepted]
    if unaccepted:
        warnings.append(
            f"{count_noun(len(unaccepted), 'machine-generated layer')} "
            f"{by_count(len(unaccepted), 'was', 'were')} left out of the annexure because "
            f"nobody has accepted {by_count(len(unaccepted), 'it', 'them')} yet. Open the workshop's AI layers, read each one against its "
            "source, and accept the ones this report should carry."
        )
    empty = [item for item in items if item.accepted and not item.has_text]
    if empty:
        warnings.append(
            f"{count_noun(len(empty), 'accepted layer')} {by_count(len(empty), 'carries', 'carry')} "
            f"no text and {by_count(len(empty), 'was', 'were')} not printed — "
            f"{by_count(len(empty), 'it holds', 'they hold')} "
            "structured data (tags or extracted details) rather than prose."
        )
    expanded = [
        item
        for item in items
        if item.accepted and item.has_text and str(item.kind or "").upper() == "EXPANDED"
    ]
    if expanded:
        warnings.append(
            f"{count_noun(len(expanded), 'passage')} in the annexure "
            f"{by_count(len(expanded), 'was', 'were')} WRITTEN by a model from your notes "
            "rather than transcribed or corrected — "
            f"{by_count(len(expanded), 'it contains', 'they contain')} sentences nobody said. Each is "
            "printed with the note it was made from and a caution beside it. Read them once more "
            "before this file leaves your hands; withdrawing an acceptance takes the passage out of "
            "the next copy."
        )
    return warnings


def runs_for_heading(item: AiLayerItem) -> tuple[Run, ...]:
    return tuple(runs_of(item.title, bold=True)) + tuple(
        runs_of(f"  ({tier_words(item.tier)})", italic=True)
    )


__all__ = [
    "DEFAULT_HEADING",
    "EXPANDED_NOTE",
    "LEAD",
    "MAX_PARAGRAPHS_PER_LAYER",
    "SPEAKER_NOTE",
    "UNRECORDED",
    "AiLayerItem",
    "ai_layer_annexure_blocks",
    "ai_layers_of",
    "annexure_warnings",
    "append_ai_layer_annexure",
    "attach_ai_layers",
    "kind_title",
    "layer_body_blocks",
    "layer_index_block",
    "provenance_line",
    "runs_for_heading",
    "speaker_labels_are_guessed",
    "tier_words",
]
