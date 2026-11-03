from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from app.services.report_model import (
    Align,
    Block,
    DocumentBuilder,
    PageBreakBlock,
    ParagraphBlock,
    ParaStyle,
    ReportMeta,
    Run,
    SpacerBlock,
    TableBlock,
    TableColumn,
    runs_of,
)
from app.services.text_format import by_count, count_noun

DEFAULT_HEADING = "Annexure — Recordings and transcripts"

MAX_PARAGRAPHS_PER_TRANSCRIPT = 1200

_LOOSE_LABEL_RE = re.compile(r"\*\*\s*([^*\n]{1,60}?)\s*\*\*\s*:")
_SPEAKER_LINE_RE = re.compile(r"^\*\*([^*\n]{1,60}?):\*\*\s*(.*)$")
_RULE_RE = re.compile(r"^\s*(?:-{3,}|\*{3,}|_{3,})\s*$")
_HEADING_RE = re.compile(r"^\s*#{1,6}\s+(\S.*?)\s*#*\s*$")
_BULLET_RE = re.compile(r"^\s*[-*+]\s+(?=\S)")
_CODE_RE = re.compile(r"`([^`\n]+)`")
_EMPHASIS_RE = re.compile(r"\*\*\s*([^*\n]+?)\s*\*\*|\*\s*([^*\n]+?)\s*\*")



@dataclass(frozen=True, slots=True)
class TranscriptItem:
    media_id: str
    stage_key: str = ""
    stage_number: int = 0
    stage_title: str = ""
    entity_key: str = ""
    field_key: str = ""
    field_label: str = ""
    filename: str = ""
    recorded_at: str = ""
    duration_seconds: int | None = None
    status: str = ""
    text: str = ""

    @property
    def speaker_count(self) -> int:
        return speaker_count(self.text)

    @property
    def first_line(self) -> str:
        return first_line(self.text)

    @property
    def word_count(self) -> int:
        return len(plain_text(self.text).split())

    @property
    def has_text(self) -> bool:
        return bool(self.text and self.text.strip())

    @property
    def label(self) -> str:
        return self.field_label or self.filename or self.media_id

    def payload(self) -> dict[str, Any]:
        return {
            "mediaId": self.media_id,
            "stageKey": self.stage_key,
            "stageNumber": self.stage_number,
            "stageTitle": self.stage_title,
            "entityKey": self.entity_key,
            "fieldKey": self.field_key,
            "fieldLabel": self.field_label,
            "label": self.label,
            "filename": self.filename,
            "recordedAt": self.recorded_at,
            "durationSeconds": self.duration_seconds,
            "durationText": duration_text(self.duration_seconds),
            "speakerCount": self.speaker_count,
            "wordCount": self.word_count,
            "firstLine": self.first_line,
            "status": self.status,
            "hasTranscript": self.has_text,
        }



def _normalised_lines(text: str) -> list[str]:
    lines: list[str] = []
    for raw in str(text or "").replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        if _RULE_RE.match(raw):
            lines.append("")
            continue
        heading = _HEADING_RE.match(raw)
        if heading:
            lines.append(_CODE_RE.sub(r"\1", heading.group(1)).strip())
            continue
        line = _CODE_RE.sub(r"\1", _BULLET_RE.sub("• ", raw.strip()))
        lines.append(_LOOSE_LABEL_RE.sub(r"**\1:**", line))
    return lines


def plain_text(text: str) -> str:
    stripped = _EMPHASIS_RE.sub(lambda m: m.group(1) or m.group(2) or "", str(text or ""))
    return " ".join(stripped.split())


def speaker_count(text: str) -> int:
    labels = {
        match.group(1).strip().casefold()
        for line in _normalised_lines(text)
        for match in (_SPEAKER_LINE_RE.match(line),)
        if match
    }
    if labels:
        return len(labels)
    return 1 if plain_text(text) else 0


def first_line(text: str, *, limit: int = 160) -> str:
    for line in _normalised_lines(text):
        match = _SPEAKER_LINE_RE.match(line)
        body = match.group(2) if match else line
        body = plain_text(body)
        if body:
            return body if len(body) <= limit else body[: limit - 1].rstrip() + "…"
    return ""


def duration_text(seconds: int | None) -> str:
    if seconds is None or seconds <= 0:
        return ""
    minutes, remainder = divmod(int(seconds), 60)
    hours, minutes = divmod(minutes, 60)
    if hours:
        return f"{hours} h {minutes:02d} min"
    if minutes:
        return f"{minutes} min {remainder:02d} s"
    return f"{remainder} s"


_ATTR = "transcripts"


def attach_transcripts(data: Any, items: list[TranscriptItem] | tuple[TranscriptItem, ...]) -> Any:
    try:
        setattr(data, _ATTR, tuple(items))
    except AttributeError:
        return data
    return data


def transcripts_of(data: Any) -> tuple[TranscriptItem, ...]:
    return tuple(getattr(data, _ATTR, ()) or ())



def _runs_for_line(line: str) -> tuple[Run, ...]:
    pieces: list[Run] = []
    position = 0
    for match in _EMPHASIS_RE.finditer(line):
        if match.start() > position:
            pieces.extend(runs_of(line[position : match.start()]))
        bold, italic = match.groups()
        pieces.extend(runs_of(bold, bold=True) if bold else runs_of(italic, italic=True))
        position = match.end()
    if position < len(line):
        pieces.extend(runs_of(line[position:]))
    return tuple(pieces)


def transcript_body_blocks(text: str) -> list[Block]:
    blocks: list[Block] = []
    truncated = False
    for line in _normalised_lines(text):
        if len(blocks) >= MAX_PARAGRAPHS_PER_TRANSCRIPT:
            truncated = True
            break
        if not line.strip():
            if blocks and not isinstance(blocks[-1], SpacerBlock):
                blocks.append(SpacerBlock(height_pct=1.5))
            continue
        match = _SPEAKER_LINE_RE.match(line)
        if match:
            label, body = match.group(1).strip(), match.group(2).strip()
            runs = runs_of(f"{label}: ", bold=True) + _runs_for_line(body)
        else:
            runs = _runs_for_line(line)
        if runs:
            blocks.append(ParagraphBlock(runs=runs, style=ParaStyle.BODY, align=Align.LEFT))
    if truncated:
        blocks.append(
            ParagraphBlock(
                runs=runs_of(
                    f"[Transcript shortened to the first {MAX_PARAGRAPHS_PER_TRANSCRIPT} paragraphs. "
                    f"The full text is in the online workshop record.]"
                ),
                style=ParaStyle.NOTE,
            )
        )
    return blocks


SPEAKER_NOTE = (
    "The speaker labels in this transcript — who is saying which line — were decided by the machine "
    "that transcribed the recording, not by anybody who was present. Nothing recorded how many people "
    "were speaking or who they were: the labels are that machine's separation of the voices it heard, "
    "and it can merge two speakers into one or split one across two. The numbering is by order of first "
    "speaking and is not a name. Check a line against the recording before attributing it to a "
    "particular person."
)


def speaker_labels_are_guessed(item: TranscriptItem) -> bool:
    return bool(item.text) and any(
        _SPEAKER_LINE_RE.match(line) for line in _normalised_lines(item.text)
    )


def transcript_index_block(items: tuple[TranscriptItem, ...]) -> TableBlock:
    return TableBlock(
        columns=(
            TableColumn("Stage", 26.0),
            TableColumn("Recording", 32.0),
            TableColumn("Length", 14.0, align=Align.RIGHT),
            TableColumn("Speakers", 12.0, numeric=True),
            TableColumn("Words", 16.0, numeric=True),
        ),
        rows=tuple(
            (
                runs_of(
                    f"{item.stage_number}. {item.stage_title}"
                    if item.stage_number
                    else item.stage_title
                ),
                runs_of(item.label),
                runs_of(duration_text(item.duration_seconds) or "—"),
                runs_of(str(item.speaker_count)),
                runs_of(f"{item.word_count:,}"),
            )
            for item in items
        ),
        caption=(
            "Recordings transcribed during this workshop. The speaker count is the number of voices "
            "the transcribing machine separated out of the audio, not a count of the people present."
        ),
    )


def _provenance(item: TranscriptItem) -> str:
    parts = [
        p
        for p in (
            duration_text(item.duration_seconds),
            f"{item.speaker_count} speaker{'s' if item.speaker_count != 1 else ''}"
            if item.speaker_count
            else "",
            item.recorded_at[:10] if item.recorded_at else "",
            f"recording {item.media_id}",
        )
        if p
    ]
    return "Transcribed automatically from the workshop recording · " + " · ".join(parts)


def append_transcript_annexure(
    doc: DocumentBuilder,
    items: tuple[TranscriptItem, ...] | list[TranscriptItem],
    *,
    heading: str = "",
    numbered: bool = True,
    page_break_before: bool = True,
) -> int:
    printed = [item for item in items if item.has_text]
    if not printed:
        return 0
    if page_break_before:
        doc.add(PageBreakBlock())
    doc.heading(heading or DEFAULT_HEADING, 1, numbered=numbered)
    doc.para(
        "The recordings made during this workshop, transcribed in full. The text is produced by "
        "automatic speech recognition and lightly edited; it is the machine's reading of the audio "
        "rather than a certified transcript, and the recordings themselves remain the record.",
        style=ParaStyle.LEAD,
    )
    doc.add(transcript_index_block(tuple(printed)))
    for item in printed:
        doc.heading(item.label, 2, numbered=numbered)
        doc.para(_provenance(item), style=ParaStyle.NOTE)
        if speaker_labels_are_guessed(item):
            doc.para(SPEAKER_NOTE, style=ParaStyle.NOTE)
        for block in transcript_body_blocks(item.text):
            doc.add(block)
    return len(printed)


def transcript_annexure_blocks(
    items: tuple[TranscriptItem, ...] | list[TranscriptItem],
    *,
    heading: str = "",
    numbered: bool = True,
    page_break_before: bool = True,
) -> tuple[Block, ...]:
    scratch = DocumentBuilder(meta=ReportMeta(title=""))
    append_transcript_annexure(
        scratch,
        items,
        heading=heading,
        numbered=numbered,
        page_break_before=page_break_before,
    )
    return scratch.build().blocks


_IN_FLIGHT_STATUSES = frozenset({"QUEUED", "PROCESSING"})
_STALLED_STATUSES = frozenset({"FAILED", "UNAVAILABLE"})


def annexure_warnings(items: tuple[TranscriptItem, ...] | list[TranscriptItem]) -> list[str]:
    missing = [item for item in items if not item.has_text]
    if not missing:
        return []
    statuses = [str(item.status or "").upper() for item in missing]
    pending = sum(1 for status in statuses if status in _IN_FLIGHT_STATUSES)
    stalled = sum(1 for status in statuses if status in _STALLED_STATUSES)
    clauses: list[str] = []
    if pending:
        clauses.append(f"{pending} still being transcribed")
    if stalled:
        clauses.append(
            f"{stalled} will not arrive on their own — saving the stage they are attached to "
            f"re-attempts them"
        )
    detail = f" ({'; '.join(clauses)})" if clauses else ""
    message = (
        f"{count_noun(len(missing), 'recording')} {by_count(len(missing), 'has', 'have')} no "
        f"transcript and {by_count(len(missing), 'was', 'were')} left out of the "
        f"transcript annexure{detail}."
    )
    return [message]
