from __future__ import annotations

import math
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from typing import Any

PAYLOAD_SCHEMA = "dw.subtitles/1"

MAX_CUE_SECONDS = 7.0

MIN_CUE_SECONDS = 1.0

MAX_CUE_CHARS = 84

MAX_JOIN_GAP_SECONDS = 0.6


class SubtitleError(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class Cue:
    start: float
    end: float
    text: str
    speaker: str = ""
    estimated: bool = False

    def __post_init__(self) -> None:
        for name, value in (("start", self.start), ("end", self.end)):
            if not isinstance(value, (int, float)) or isinstance(value, bool):
                raise SubtitleError(
                    f"A caption's {name} time must be a number of seconds; this one is {value!r}. "
                    f"Make the subtitles again."
                )
            if not math.isfinite(float(value)) or float(value) < 0:
                raise SubtitleError(
                    f"A cue's {name} must be a real number of seconds from the start of the "
                    f"recording; this one is {value!r}."
                )
        if float(self.end) < float(self.start):
            raise SubtitleError(
                f"A cue cannot end ({self.end}s) before it starts ({self.start}s). Subtitles with "
                f"reversed timings are refused by every player rather than shown out of order."
            )
        if not (self.text or "").strip():
            raise SubtitleError(
                "A cue with no words is a blank caption on screen. Drop it rather than storing it."
            )

    @property
    def seconds(self) -> float:
        return float(self.end) - float(self.start)

    def payload(self) -> dict[str, Any]:
        cue: dict[str, Any] = {
            "start": round(float(self.start), 3),
            "end": round(float(self.end), 3),
            "text": self.text.strip(),
        }
        if self.speaker:
            cue["speaker"] = self.speaker
        if self.estimated:
            cue["estimated"] = True
        return cue


def cue_of(raw: Any) -> Cue:
    if not isinstance(raw, dict):
        raise SubtitleError(
            "These subtitles can't be shown because a caption is missing its times or text. "
            "Make the subtitles again."
        )
    return Cue(
        start=raw.get("start"),
        end=raw.get("end"),
        text=str(raw.get("text") or ""),
        speaker=str(raw.get("speaker") or ""),
        estimated=bool(raw.get("estimated")),
    )


def cues_payload(cues: Sequence[Cue], *, language: str | None = None) -> dict[str, Any]:
    ordered = list(cues)
    return {
        "schema": PAYLOAD_SCHEMA,
        "language": language or None,
        "count": len(ordered),
        "estimatedCues": sum(1 for cue in ordered if cue.estimated),
        "durationSeconds": round(max((c.end for c in ordered), default=0.0), 3),
        "cues": [cue.payload() for cue in ordered],
    }


def cues_of_payload(payload: Any) -> list[Cue]:
    raw = payload.get("cues") if isinstance(payload, dict) else payload
    if not isinstance(raw, (list, tuple)):
        raise SubtitleError(
            "This text has no subtitles in it, so there is nothing to save as a subtitle file."
        )
    return [cue_of(item) for item in raw]



def _ceiling_breaches(seconds: float, text: str) -> tuple[bool, bool]:
    return (
        float(seconds) > MAX_CUE_SECONDS,
        len((text or "").strip()) > MAX_CUE_CHARS,
    )


def over_ceilings(*, seconds: float, text: str) -> bool:
    return any(_ceiling_breaches(seconds, text))


def label_speakers(fragments: Iterable[Cue]) -> list[Cue]:
    ordered = list(fragments)
    seen: list[str] = []
    for cue in sorted(ordered, key=lambda cue: (float(cue.start), float(cue.end))):
        if cue.speaker and cue.speaker not in seen:
            seen.append(cue.speaker)
    if len(seen) < 2:
        return [
            cue
            if not cue.speaker
            else Cue(
                start=cue.start, end=cue.end, text=cue.text, speaker="", estimated=cue.estimated
            )
            for cue in ordered
        ]
    numbers = {speaker: index + 1 for index, speaker in enumerate(seen)}
    return [
        Cue(
            start=cue.start,
            end=cue.end,
            text=cue.text,
            speaker=f"Speaker {numbers[cue.speaker]}" if cue.speaker else "",
            estimated=cue.estimated,
        )
        for cue in ordered
    ]


def fit_cues(fragments: Iterable[Cue]) -> list[Cue]:
    ordered = sorted(fragments, key=lambda cue: (float(cue.start), float(cue.end)))
    joined: list[Cue] = []
    for fragment in ordered:
        if not joined:
            joined.append(fragment)
            continue
        last = joined[-1]
        gap = float(fragment.start) - float(last.end)
        merged_text = f"{last.text.strip()} {fragment.text.strip()}".strip()
        if (
            last.speaker == fragment.speaker
            and 0 <= gap <= MAX_JOIN_GAP_SECONDS
            and len(merged_text) <= MAX_CUE_CHARS
            and float(fragment.end) - float(last.start) <= MAX_CUE_SECONDS
        ):
            joined[-1] = Cue(
                start=last.start,
                end=fragment.end,
                text=merged_text,
                speaker=last.speaker,
                estimated=last.estimated or fragment.estimated,
            )
        else:
            joined.append(fragment)

    out: list[Cue] = []
    for cue in joined:
        out.extend(_split_if_needed(cue))
    out.sort(key=lambda cue: (float(cue.start), float(cue.end)))
    return _stretch_short_cues(out)


def _split_if_needed(cue: Cue) -> list[Cue]:
    too_long, too_wide = _ceiling_breaches(cue.seconds, cue.text)
    if not (too_long or too_wide):
        return [cue]

    words = cue.text.split()
    if len(words) < 2:
        return [cue]

    pieces = max(
        math.ceil(cue.seconds / MAX_CUE_SECONDS) if too_long else 1,
        math.ceil(len(cue.text.strip()) / MAX_CUE_CHARS) if too_wide else 1,
    )
    per_piece = math.ceil(len(words) / pieces)
    chunks = [
        " ".join(words[index : index + per_piece]) for index in range(0, len(words), per_piece)
    ]
    chunks = [chunk for chunk in chunks if chunk]
    if len(chunks) < 2:
        return [cue]

    total_chars = sum(len(chunk) for chunk in chunks) or 1
    out: list[Cue] = []
    at = float(cue.start)
    for index, chunk in enumerate(chunks):
        share = cue.seconds * (len(chunk) / total_chars)
        end = float(cue.end) if index == len(chunks) - 1 else min(at + share, float(cue.end))
        out.append(Cue(start=at, end=max(end, at), text=chunk, speaker=cue.speaker, estimated=True))
        at = end
    return out


def _stretch_short_cues(cues: list[Cue]) -> list[Cue]:
    out: list[Cue] = []
    for index, cue in enumerate(cues):
        if cue.seconds >= MIN_CUE_SECONDS:
            out.append(cue)
            continue
        ceiling = float(cues[index + 1].start) if index + 1 < len(cues) else float("inf")
        end = min(float(cue.start) + MIN_CUE_SECONDS, ceiling)
        out.append(
            Cue(
                start=cue.start,
                end=max(end, float(cue.end)),
                text=cue.text,
                speaker=cue.speaker,
                estimated=cue.estimated,
            )
        )
    return out



def _stamp(seconds: float, *, separator: str) -> str:
    total_ms = round(max(0.0, float(seconds)) * 1000)
    hours, remainder = divmod(total_ms, 3_600_000)
    minutes, remainder = divmod(remainder, 60_000)
    secs, millis = divmod(remainder, 1000)
    return f"{hours:02d}:{minutes:02d}:{secs:02d}{separator}{millis:03d}"


SPEAKER_NOTE = (
    "The speaker labels in this file were guessed by the model that transcribed the recording. "
    "Nobody told it how many people were speaking or who they were: it decided that from the audio, "
    "and it can merge two voices or split one. Check a line against the video before quoting it as "
    "anybody's words."
)

ESTIMATED_NOTE_PREFIX = "Timing note:"


def _estimated_note(cues: Sequence[Cue]) -> str:
    estimated = sum(1 for cue in cues if cue.estimated)
    if not estimated:
        return ""
    return (
        f"{ESTIMATED_NOTE_PREFIX} {estimated} of these {len(cues)} captions have estimated "
        f"start and end times. The engine that transcribed this recording returned a block of "
        f"speech without timing each word inside it, so the block was divided by how long the words "
        f"are — which is not how long they take to say. Those captions can sit a second or two away "
        f"from the speech. The others are the engine's own measured timings."
    )


def to_srt(cues: Sequence[Cue], *, speakers: bool = False) -> str:
    return _render(cues, separator=",", header="", speakers=speakers, notes=())


def to_vtt(cues: Sequence[Cue], *, speakers: bool = False) -> str:
    notes = [note for note in (_estimated_note(cues), SPEAKER_NOTE if speakers else "") if note]
    return _render(cues, separator=".", header="WEBVTT\n\n", speakers=speakers, notes=tuple(notes))


def _render(
    cues: Sequence[Cue],
    *,
    separator: str,
    header: str,
    speakers: bool,
    notes: tuple[str, ...] = (),
) -> str:
    if not cues:
        raise SubtitleError(
            "There are no cues to write out, so a subtitle file would be an empty one — which a "
            "player opens as a track with no subtitles rather than as an error. Register the "
            "subtitles again, or check that the recording carried speech."
        )
    blocks: list[str] = []
    for note in notes:
        if "-->" in note:
            raise SubtitleError(
                "A WebVTT comment may not contain '-->', which ends it and turns what follows into a "
                "cue no player can read. Reword the note."
            )
        blocks.append(f"NOTE\n{note}\n")
    for index, cue in enumerate(cues, start=1):
        line = cue.text.strip()
        if speakers and cue.speaker:
            line = f"{cue.speaker}: {line}"
        blocks.append(
            f"{index}\n"
            f"{_stamp(cue.start, separator=separator)} --> {_stamp(cue.end, separator=separator)}\n"
            f"{line}\n"
        )
    return header + "\n".join(blocks)


def plain_reading(cues: Sequence[Cue]) -> str:
    lines: list[str] = []
    previous = None
    for cue in cues:
        if cue.speaker and cue.speaker != previous:
            lines.append(f"**{cue.speaker}:** {cue.text.strip()}")
        elif lines:
            lines[-1] = f"{lines[-1]} {cue.text.strip()}"
        else:
            lines.append(cue.text.strip())
        previous = cue.speaker or previous
    return "\n\n".join(lines).strip()


__all__ = [
    "MAX_CUE_CHARS",
    "MAX_CUE_SECONDS",
    "MAX_JOIN_GAP_SECONDS",
    "MIN_CUE_SECONDS",
    "PAYLOAD_SCHEMA",
    "SPEAKER_NOTE",
    "Cue",
    "SubtitleError",
    "cue_of",
    "cues_of_payload",
    "cues_payload",
    "fit_cues",
    "label_speakers",
    "over_ceilings",
    "plain_reading",
    "to_srt",
    "to_vtt",
]
