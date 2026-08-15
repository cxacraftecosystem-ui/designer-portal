"""Timed text: the cue list a SUBTITLES layer stores, and the two files it renders as.

**WHY THIS IS A MODULE AND NOT A FUNCTION IN THE VERB THAT PRODUCES IT.** A subtitle is the only AI
layer whose principal content is structure rather than prose, and the structure has to survive three
journeys the other kinds never make: it is stored in a Json column, read back by a client that draws
a player, and rendered into two file formats with incompatible syntaxes. Every one of those is a
place where a start time can be written as seconds in one half of the code and as milliseconds in
the other, so the arithmetic lives once, here, and is asserted by ``tests/test_subtitles.py`` with no
database and no provider.

**WHAT THE PROVIDERS ACTUALLY GIVE US, MEASURED FROM ``services/ai.py`` RATHER THAN REMEMBERED.**
This is the fact the whole verb rests on and it is worth stating exactly, because the answer is not
the obvious one:

* **ElevenLabs Scribe v2** is already asked for ``timestamps_granularity=word`` (``ai._elevenlabs_
  fields``) and answers with a ``words`` array carrying ``start``, ``end``, ``text``, ``speaker_id``
  and ``type``. **Every one of those timings is thrown away today**: ``ai._elevenlabs_text`` reads
  ``speaker_id`` and ``text`` and nothing else, and ``_post_elevenlabs_transcription`` then passes
  ``None`` as the payload with the comment *"word-level payload is huge; don't persist it"*.
* **Deepgram Nova-3** answers with ``results.channels[0].alternatives[0].paragraphs.paragraphs[]``,
  whose ``sentences[]`` each carry ``start`` and ``end``, and with a ``words[]`` array carrying
  ``start``, ``end``, ``speaker`` and ``punctuated_word``. ``ai._deepgram_text`` reads the sentence
  TEXT and the paragraph SPEAKER and discards both timings, and the payload is dropped the same way.
* **OpenAI Whisper** is called with ``response_format=json`` (``ai._post_openai_transcription``),
  which returns ``text`` **and no timings at all**. Segment timings need ``verbose_json``, which is a
  different request — so on the Whisper rung the timings do not exist to be discarded.
* **Gemini** is not in the transcription chain at all; it reads grid photographs.

So: **two of the four providers already produce exactly what a subtitle needs, on every transcription
this system has ever run, and the code deletes it before it reaches a caller.** Nothing in the
archive can be turned into subtitles retroactively — the audio has to be sent again. That is the cost
of the verb and it is stated where somebody sizing the bill will find it, in
``ai.transcribe_timed_bytes``, which is a SECOND call and not a change to the existing one: altering
``_post_elevenlabs_transcription`` to keep its payload would change what the media queue writes to
``MediaFile.extraMetadata`` for every recording in the fleet, which is another lane's column.

**WHAT A CUE IS NOT.** It is not a sentence, and the two must not be conflated. A sentence is what a
person said; a cue is what fits on a screen for long enough to be read. :func:`fit_cues` is the
function that turns one into the other, and its rules are legibility rules with sources, not
preferences.

**AND A CUE BOUNDARY IS A CLAIM ABOUT WHEN SOMEBODY SPOKE, WHICH IS WHY :attr:`Cue.estimated`
EXISTS.** A designer plays these against video of an artisan speaking, so a boundary is checkable by
eye — and a boundary this module INVENTED is a different kind of fact from one a provider measured.
Only one thing here invents them: :func:`_split_if_needed`, which divides a too-long fragment
proportionally by character count, and a word does not take time proportional to its length. That
path used to be the COMMON case for Deepgram, whose sentences run past both ceilings in an unhurried
interview; ``ai._deepgram_cues`` now emits a long sentence's MEASURED WORDS instead, so it is reached
only where the words do not ACCOUNT for the sentence — no word array at all, or one that spells less
than the sentence says, which is a response that would otherwise have lost the missing words in
silence rather than merely mistimed them. Where it is still reached, every cue it produces
carries ``estimated=True``, which travels in the payload and, in WebVTT, onto the page — because "one
to two seconds out" is invisible to the person deciding whether to trust the file.

**THE LINE THAT DRAWS, STATED SO IT IS NOT RE-DRAWN BY ACCIDENT.** :func:`_stretch_short_cues` also
moves an ``end``, and it is NOT marked estimated. A stretched end is a DISPLAY duration — how long
the caption stays up, which every subtitling guideline sets by legibility rather than by speech — and
it never claims that a word was still being said. A split boundary is a claim about when a word was
said. Two different facts; one of them is checkable against the audio and wrong, the other is a
convention and right.
"""

from __future__ import annotations

import math
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from typing import Any

#: The payload shape's own name and version, stored on every cue list.
#:
#: A VERSION INSIDE THE JSON BECAUSE THERE IS NO COLUMN TO PUT ONE IN, and a payload that cannot say
#: what shape it is in is a payload every future reader has to sniff. When the shape changes — a
#: per-word karaoke track, a right-to-left flag — a reader can branch on this instead of guessing
#: from which keys happen to be present. The same discipline ``customSchemaVersion`` follows one
#: feature over, in the only place this table can express it.
PAYLOAD_SCHEMA = "dw.subtitles/1"

#: How long a cue may stay on screen, in seconds.
#:
#: Not a preference: the BBC and Netflix timed-text guidelines both cap a subtitle at about seven
#: seconds, because a caption left up longer than that is re-read from the top by anybody who glances
#: away and back. Seven is the looser of the two and is what this uses, since these are field
#: interviews rather than broadcast drama and a slow speaker's clause should not be cut in half.
MAX_CUE_SECONDS = 7.0

#: How briefly a cue may flash. Under about a second a subtitle is gone before the eye has fixed on
#: it, and a viewer experiences it as a flicker rather than as text. Cues shorter than this are
#: EXTENDED to it where the next cue leaves room, never dropped — the words were said.
MIN_CUE_SECONDS = 1.0

#: How many characters may sit in one cue, over at most two lines.
#:
#: 42 per line is the conventional ceiling for a 16:9 frame and two lines is the conventional maximum
#: depth, because a third line covers picture. 84 is those two multiplied and is applied to the whole
#: cue rather than per line, so a wrapper is free to balance the lines.
MAX_CUE_CHARS = 84

#: The longest gap, in seconds, that two neighbouring fragments of one speaker's speech may be joined
#: across. Beyond it they are two utterances and joining them would put a caption on screen during a
#: silence — which reads, to somebody relying on subtitles, as the speaker still talking.
MAX_JOIN_GAP_SECONDS = 0.6


class SubtitleError(ValueError):
    """A cue list that cannot be rendered or stored, refused with a sentence naming the next move.

    A ``ValueError`` rather than an ``HTTPException`` for ``ai_layers.LayerRuleViolation``'s reason:
    this module stays importable and testable with no framework underneath it.
    """


@dataclass(frozen=True, slots=True)
class Cue:
    """One caption: when it appears, when it goes, what it says, and who is speaking.

    ``start`` and ``end`` are SECONDS AS FLOATS, which is the unit both providers answer in, so
    nothing is converted on the way in. Milliseconds are produced only at the moment a timestamp is
    formatted, in :func:`_stamp`, because SRT and WebVTT both want them and no other reader does —
    converting on the way in and back out again is two chances to lose a rounding.

    ``speaker`` is the label the diarization produced, or empty. It is kept beside the text rather
    than glued into it: ``ai._diarized_markdown`` writes ``**Speaker 1:**`` into a transcript because
    a transcript is read as a document, but a subtitle carrying markdown asterisks on screen is a
    rendering bug, and a client that wants to prefix the name can do it in the shape its player uses.
    :func:`label_speakers` is what turns a provider's own id into the label a person reads.

    ``estimated`` says THIS CUE'S BOUNDARIES WERE NOT MEASURED — see the module docstring. Default
    False, and it is never set by a provider's answer: only :func:`_split_if_needed` sets it, on the
    pieces it invents. A reader that ignores it gets today's behaviour; a reader that honours it can
    say which captions are approximate, which is the difference between a subtitle file a designer
    can check and one they have to trust.
    """

    start: float
    end: float
    text: str
    speaker: str = ""
    estimated: bool = False

    def __post_init__(self) -> None:
        for name, value in (("start", self.start), ("end", self.end)):
            if not isinstance(value, (int, float)) or isinstance(value, bool):
                raise SubtitleError(
                    f"A cue's {name} must be a number of seconds; this one is {value!r}. The "
                    f"provider's answer was not in the shape this reader expects — the run is in "
                    f"the server log."
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
        """One cue as it is stored and as a client reads it. camelCase on the wire.

        ``speaker`` and ``estimated`` are written only when they say something, exactly as ``speaker``
        already was: a key present on every cue in a list of two thousand is kilobytes of ``false``
        travelling to a handset on one bar of signal. The COUNT is unconditional on the list — see
        :func:`cues_payload` — so a client renders "142 cues, 11 of them approximate" from a stated
        number rather than by walking the array looking for a key that is usually absent.
        """
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
    """One stored cue back as a :class:`Cue`, refusing anything that could not be rendered.

    Defensive because the input is a Json column: a row written by a backfill, or by a build whose
    payload shape has moved on, must fail here with a sentence rather than reach a renderer and
    produce a subtitle file no player will open.
    """
    if not isinstance(raw, dict):
        raise SubtitleError(
            "A subtitle cue must be an object with start, end and text. This layer's payload holds "
            "something else and cannot be rendered — register the subtitles again."
        )
    return Cue(
        start=raw.get("start"),
        end=raw.get("end"),
        text=str(raw.get("text") or ""),
        speaker=str(raw.get("speaker") or ""),
        # A stored cue with no `estimated` key is one written before the flag existed, or one whose
        # boundaries were measured. Both read as False, and the two are genuinely indistinguishable in
        # the rows already in this database — which is stated in `cues_payload` rather than papered
        # over by treating an absent key as unknown.
        estimated=bool(raw.get("estimated")),
    )


def cues_payload(cues: Sequence[Cue], *, language: str | None = None) -> dict[str, Any]:
    """The whole cue list as the ``DwAiLayer.payload`` column holds it.

    ``count`` and ``durationSeconds`` are stored rather than computed on read, and that is not
    denormalisation for speed — it is so a list screen can say "142 cues, 48 minutes" without
    carrying every cue to the handset, which is the same argument ``PREVIEW_CHARS`` makes for a
    transcript one module over.

    ``estimatedCues`` IS ALWAYS PRESENT AND IS USUALLY ZERO, which is the point of it being a number
    rather than a flag: zero is a measured statement that every boundary in this file came from the
    provider, and a client can print it. **It is not retroactive.** A cue list stored before this key
    existed carries no ``estimated`` markers, so it reads as zero whether or not its boundaries were
    invented — those rows were written when Deepgram sentences were split proportionally as a matter
    of course, so for them zero means "unknown" and not "measured". Nothing here can tell the two
    apart, and the honest place to say so is here rather than in a client that would have to guess.
    """
    ordered = list(cues)
    return {
        "schema": PAYLOAD_SCHEMA,
        # The language of the CUES, repeated here beside them as well as on the row's `language`
        # column. On the row it is provenance; here it is what a WebVTT/SRT consumer needs to label
        # a track, and a track label that has to be looked up in another table is one that ends up
        # wrong. None stays None: an undetected language is not English.
        "language": language or None,
        "count": len(ordered),
        "estimatedCues": sum(1 for cue in ordered if cue.estimated),
        "durationSeconds": round(max((c.end for c in ordered), default=0.0), 3),
        "cues": [cue.payload() for cue in ordered],
    }


def cues_of_payload(payload: Any) -> list[Cue]:
    """The cue list back out of a stored payload, in order.

    Tolerates the bare list as well as the wrapped object, because a payload written by an on-device
    Tier 1 or Tier 2 runner may not have gone through :func:`cues_payload` — the tiers are allowed to
    differ in how they produce a thing and are not allowed to differ in what it means.
    """
    raw = payload.get("cues") if isinstance(payload, dict) else payload
    if not isinstance(raw, (list, tuple)):
        raise SubtitleError(
            "This layer carries no subtitle cues. Its payload is not a cue list, so there is "
            "nothing to render as a subtitle file."
        )
    return [cue_of(item) for item in raw]


# --------------------------------------------------------------------------------------
# Turning what a provider said into what fits on a screen
# --------------------------------------------------------------------------------------


def _ceiling_breaches(seconds: float, text: str) -> tuple[bool, bool]:
    """``(too long, too wide)`` for one fragment. The only place either comparison is written."""
    return (
        float(seconds) > MAX_CUE_SECONDS,
        len((text or "").strip()) > MAX_CUE_CHARS,
    )


def over_ceilings(*, seconds: float, text: str) -> bool:
    """Whether one fragment is too long or too wide to be a cue on its own.

    **PUBLIC, AND EXPORTED, BECAUSE A PROVIDER PARSER HAS TO ASK IT BEFORE THIS MODULE EVER SEES THE
    FRAGMENT.** ``ai._deepgram_cues`` decides per sentence whether to hand over the sentence or its
    measured constituent words, and that decision is only correct if it uses the SAME two numbers
    :func:`_split_if_needed` uses. Two copies of "seven seconds and eighty-four characters" is a
    sentence emitted whole under one rule and then split proportionally under the other — the exact
    invented-boundary defect the word path exists to remove, reintroduced by a constant drifting.
    """
    return any(_ceiling_breaches(seconds, text))


def label_speakers(fragments: Iterable[Cue]) -> list[Cue]:
    """Provider speaker ids as the labels a person reads: ``Speaker 1``, ``Speaker 2``, in order.

    **THE PROVIDERS DO NOT ANSWER WITH A LABEL. THEY ANSWER WITH AN ID**, and the two are not the same
    thing: Deepgram's ``paragraphs[].speaker`` is ``0``, ``1``; Scribe's ``words[].speaker_id`` is
    ``speaker_0``. Stored raw — which is what happened until this function existed — a downloaded
    subtitle reads ``0: The dabu paste is mixed`` and the report annexure prints ``**speaker_0:**``
    into a document going to a ministry. Neither is a label; both are an internal identifier that
    escaped.

    THE RULE IS ``ai._diarized_markdown``'s, DELIBERATELY, AND BOTH HALVES OF IT MATTER:

    * **Numbered in order of first appearance, not by the provider's own id**, which is arbitrary and
      can skip values — "Speaker 3" in a recording with two voices reads as a mistake, and a reader
      who counts three speakers in a sitting of two has been told something false.
    * **One voice means NO label at all.** A solo interview labelled ``Speaker 1:`` on every line
      asserts that a diarizer distinguished it from somebody, when what happened is that it heard
      nobody else. That is the same reason ``_diarized_markdown`` returns None rather than a
      one-speaker document.

    Fragments with no id are left with none: an undiarized response is not a recording with one
    speaker, and inventing a label for it would state a fact nobody established.
    """
    ordered = list(fragments)
    seen: list[str] = []
    # FIRST APPEARANCE MEANS FIRST IN TIME, not first in the list. `fit_cues` sorts by start and this
    # runs before it, so a parser that walked paragraphs out of order — or a provider that answered
    # with them out of order — would otherwise number the voices by the order they were parsed in and
    # call the second speaker heard "Speaker 1". The output keeps the caller's order untouched.
    for cue in sorted(ordered, key=lambda cue: (float(cue.start), float(cue.end))):
        if cue.speaker and cue.speaker not in seen:
            seen.append(cue.speaker)
    if len(seen) < 2:
        return [
            cue if not cue.speaker else Cue(
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
    """Join and split timed fragments into cues a person can actually read.

    **THE PROVIDERS ANSWER IN THE WRONG UNITS FOR A SUBTITLE, IN BOTH DIRECTIONS AT ONCE**, which is
    why this exists rather than the verb storing what it was handed:

    * Scribe answers per WORD. One word per caption is a flicker, not a subtitle.
    * Deepgram answers per SENTENCE, and a sentence in an unhurried interview runs for fifteen
      seconds and two hundred characters — over both the seven-second ceiling and the two-line one.

    So fragments are joined while they belong to one speaker, sit within :data:`MAX_JOIN_GAP_SECONDS`
    of each other, and stay inside both ceilings; and anything still too long is split proportionally
    by character count. **The split timing is an ESTIMATE and this is the one place in the AI-layer
    work that produces a number nobody measured** — a word does not take time proportional to its
    length. It is honest anyway, and the reason is worth stating: the ALTERNATIVE is a fifteen-second
    caption that a viewer has already stopped reading, and the estimate is bounded by real timings on
    both sides. Where the source fragments are words (Scribe), no estimation happens at all, because
    a join never needs to be undone.

    **AND IT IS NO LONGER THE ORDINARY DEEPGRAM PATH, WHICH IS WHAT IT USED TO BE.** A Deepgram
    sentence over either ceiling is now handed to this function as its own MEASURED WORDS
    (``ai._deepgram_cues``), so the join above rebuilds the cue from boundaries the provider timed and
    the split below never runs on it. The split survives for the shapes that leave nothing else: a
    response carrying sentences and no word array, or one whose words do not account for the
    sentence's own text — where using them would drop the words they are missing. Every cue it
    produces is marked
    ``estimated=True`` and says so wherever the format allows it to be said — because a caption
    landing one to two seconds from when the words were said, in a file played against video of an
    artisan speaking, is visible to the designer and invisible in the data.

    A SPEAKER CHANGE ALWAYS BREAKS A CUE, whatever the gap. Two voices in one caption is unreadable
    and, worse, attributes half of it to the wrong person — which in this archive means attributing
    an artisan's words to the interviewer.
    """
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
                # A join keeps the outer two boundaries, which are the two that survive — so the
                # result is estimated only if one of the fragments it is made of already was. It
                # never becomes estimated by being joined.
                estimated=last.estimated or fragment.estimated,
            )
        else:
            joined.append(fragment)

    out: list[Cue] = []
    for cue in joined:
        out.extend(_split_if_needed(cue))
    # SORTED AGAIN, BECAUSE SPLITTING CAN UNDO THE SORT AT THE TOP AND TWO THINGS DEPEND ON IT. The
    # fragments were ordered by start, but a fragment that OVERLAPS the one after it — a provider
    # timing a long utterance across a short interjection — is split into pieces that run past the
    # next fragment's start, and the pieces are appended after it. Run against exactly that, the
    # `.srt` came out numbered 1, 2, 3 with timings 0→6, 6→12, 1→2. Both formats are sequences of
    # blocks read in file order and neither specifies what to do with one that goes backwards;
    # WHAT A PLAYER ACTUALLY DOES WITH IT WAS NOT MEASURED HERE — no player was run in this lane —
    # so the claim is only that the file is written out of order, which is enough.
    # And `_stretch_short_cues` reads `cues[index + 1]` as "the cue that follows", which is only true
    # of a sorted list — out of order it measures its ceiling against the wrong neighbour. This is a
    # no-op for every fragment list the two parsers can currently produce (Deepgram's sentences and
    # Scribe's words are both in time order and neither overlaps), and it is here so that the
    # guarantee holds for the shape rather than for today's callers. It does not REMOVE an overlap:
    # two fragments that cover the same second still do, and that is the provider's answer, not a
    # thing this module may invent a boundary to fix.
    out.sort(key=lambda cue: (float(cue.start), float(cue.end)))
    return _stretch_short_cues(out)


def _split_if_needed(cue: Cue) -> list[Cue]:
    """One cue as however many cues it takes to respect both ceilings.

    **EVERY PIECE THIS RETURNS IS MARKED ESTIMATED, INCLUDING THE FIRST AND THE LAST.** The first
    keeps the source's measured start and the last its measured end, so it is tempting to call those
    two exact — and it would be wrong in the direction that matters. A cue is a PAIR of boundaries,
    and each of those pieces has one boundary this function invented; a viewer checking the first
    caption against the audio is checking when it GOES, which is the invented end. A flag that meant
    "half of this cue is exact" would be read as "this cue is exact".
    """
    too_long, too_wide = _ceiling_breaches(cue.seconds, cue.text)
    if not (too_long or too_wide):
        return [cue]

    words = cue.text.split()
    if len(words) < 2:
        # A single word over the ceiling — a run-on with no spaces, or a very slow utterance. It is
        # left whole: splitting inside a word would put half of somebody's speech on screen as
        # nonsense, and a long caption is a legibility problem where a broken word is a data one.
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
        # The last piece ends exactly where the source cue ended, rather than at the sum of the
        # estimates: the accumulated rounding otherwise leaves the final caption a few milliseconds
        # short of the real end, which is the one boundary a viewer can actually check against the
        # audio.
        end = float(cue.end) if index == len(chunks) - 1 else min(at + share, float(cue.end))
        out.append(
            Cue(start=at, end=max(end, at), text=chunk, speaker=cue.speaker, estimated=True)
        )
        at = end
    return out


def _stretch_short_cues(cues: list[Cue]) -> list[Cue]:
    """Extend anything under :data:`MIN_CUE_SECONDS`, but never into the cue that follows it.

    A flash of text is worse than no text: the viewer sees that something appeared, knows they missed
    it, and has no way to get it back. Extending is safe where the next cue has not started; where it
    has, the short cue is left as it is rather than overlapping, because two captions on screen at
    once is a defect every player renders differently.
    """
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
                # NOT MARKED ESTIMATED, and the module docstring argues it at length: this moves how
                # long a caption STAYS UP, which is a legibility convention, and never claims that a
                # word was still being said.
                estimated=cue.estimated,
            )
        )
    return out


# --------------------------------------------------------------------------------------
# The two files
# --------------------------------------------------------------------------------------


def _stamp(seconds: float, *, separator: str) -> str:
    """``HH:MM:SS,mmm`` for SRT or ``HH:MM:SS.mmm`` for WebVTT — the only difference between them.

    Rounded to the nearest millisecond rather than truncated, because truncation biases every
    timestamp early and the error accumulates visibly across an hour-long interview.
    """
    total_ms = round(max(0.0, float(seconds)) * 1000)
    hours, remainder = divmod(total_ms, 3_600_000)
    minutes, remainder = divmod(remainder, 60_000)
    secs, millis = divmod(remainder, 1000)
    return f"{hours:02d}:{minutes:02d}:{secs:02d}{separator}{millis:03d}"


#: What a file says about its speaker labels, wherever the format lets it say anything.
#:
#: **DIARIZATION IS A MODEL'S GUESS AND THE FILE HAS TO SAY SO.** Nobody in the courtyard told the
#: system how many people were in the room or which of them was speaking; a provider inferred both
#: from the audio. Scribe will separate up to 32 voices and Deepgram numbers whatever it hears, and
#: both of them merge two quiet speakers, split one person who moved away from the microphone, and
#: attribute an interjection to whoever was louder. A label printed with no caution beside an
#: artisan's words in a government archive reads as a record of who spoke.
#:
#: IT IS ADDRESSED TO WHOEVER OPENS THE FILE and names the check that settles it, which is the video —
#: the same discipline ``report_ai_layers.EXPANDED_NOTE`` follows for invented prose.
SPEAKER_NOTE = (
    "The speaker labels in this file were guessed by the model that transcribed the recording. "
    "Nobody told it how many people were speaking or who they were: it decided that from the audio, "
    "and it can merge two voices or split one. Check a line against the video before quoting it as "
    "anybody's words."
)

#: What a file says when some of its boundaries were not measured. Composed with the count, because
#: "some of these are approximate" leaves a reader unable to tell a file with two estimated cues from
#: one where every boundary was invented.
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
    """SubRip. The format every phone gallery and every desktop player already opens.

    CUES ARE NUMBERED FROM 1 AND THE BLOCKS ARE SEPARATED BY A BLANK LINE, both of which SubRip
    requires; a file that gets either wrong opens as an empty subtitle track rather than as an error,
    which is the failure mode worth knowing about because nothing tells the designer.

    ``speakers`` DEFAULTS OFF and is the caller's decision — see :func:`_render` for why the flag is
    a parameter of both formats rather than a second function for one of them.

    **AND SUBRIP CANNOT CARRY A NOTE OF ANY KIND. STATED HERE BECAUSE IT IS A REAL GAP AND NOT AN
    OVERSIGHT.** The format has no comment syntax at all: every non-blank line in a block is either
    an index, a timestamp or subtitle text that a player puts on screen. The only place a caution
    could go is a cue — which would put a paragraph over the first seconds of the video — so
    ``notes=()`` here is a property of SubRip and not a choice, and it costs TWO cautions rather than
    one. Both are named, because declaring one gap and staying silent about its twin is how the
    second one stops being known:

    * :data:`SPEAKER_NOTE` — a ``.srt`` asked for with ``speakers=True`` carries the labels and
      nothing about how they were arrived at.
    * :func:`_estimated_note` — a ``.srt`` built from cues whose boundaries were invented prints
      those timestamps in the same shape as measured ones, with nothing saying which is which. This
      is the worse of the two, because a wrong timestamp is invisible to a reader who is not playing
      the file against the video, whereas a missing speaker label is at least visibly missing.

    ``to_vtt`` can say both and does. **AND THE MITIGATION THAT WAS CLAIMED HERE DOES NOT REACH
    ANYBODY, WHICH IS WHY THIS PARAGRAPH NO LONGER CLAIMS IT.** This docstring used to say the route
    carries the caution "in its own description, which is what a designer reads at the moment they
    ask for the labels". That description is a FastAPI ``Query(description=...)``: it is served in
    ``/openapi.json`` and rendered by ``/docs``, and no screen in either client shows it. Measured:
    ``GET …/subtitles.{fmt}`` has NO caller in ``frontend/`` or in the Android sources — no subtitle
    download exists in either client yet — so today the honest statement is that a ``.srt`` says
    neither thing to anybody, and whoever builds the download control owes both cautions a place
    beside it.
    """
    return _render(cues, separator=",", header="", speakers=speakers, notes=())


def to_vtt(cues: Sequence[Cue], *, speakers: bool = False) -> str:
    """WebVTT. What a browser's ``<track>`` element takes, so the web player needs no conversion.

    THE ``WEBVTT`` HEADER IS MANDATORY and its absence is the single most common reason a track
    silently does not appear: the browser rejects the file and reports nothing to the page.

    **``NOTE`` BLOCKS CARRY WHAT SUBRIP CANNOT.** WebVTT has comments — a block opening with ``NOTE``,
    ended by a blank line, which every conforming parser skips and no player displays — so the
    caution about guessed speakers and the count of estimated timings travel inside the file itself,
    where they are still there when somebody opens it in six months. A ``NOTE`` may not contain
    ``-->``; neither of these does, and :func:`_render` refuses one that did rather than writing a
    file whose cue list silently stops at the comment.
    """
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
    """The shared body of both formats.

    **THE SPEAKER LABEL IS A PLAIN TEXT PREFIX IN BOTH FORMATS, AND WEBVTT'S OWN ``<v>`` MECHANISM
    WAS CONSIDERED AND DECLINED.** WebVTT has a voice span — ``<v Speaker 1>text</v>`` — which is the
    semantically correct way to say who is speaking, and it is the wrong choice here for one decisive
    reason: **a browser renders the cue text and not the voice name.** The name is exposed to CSS
    (``::cue(v[voice="Speaker 1"])``) and to nothing else, so on the one player the ``.vtt`` route
    exists for — a ``<track>`` element — a file full of voice spans shows no labels at all, and a
    designer who asked for them would get a file that looks identical to the one they did not ask
    for. That is the flag lying on the route it was added to serve. Desktop players are worse rather
    than better: support for the tag ranges from stripping it to printing it verbatim.
    So: ``Speaker 1: `` in front of the line, which every player on both formats shows, and the two
    files then say the same thing — which is the other half of the point, since the ``.srt`` and the
    ``.vtt`` of one layer must not differ in what they attribute to whom.

    **NOT MEASURED IN THIS LANE, AND SAID SO RATHER THAN IMPLIED:** no player was run against these
    files here. The paragraph above rests on the WebVTT specification and on how browsers render cue
    text by default, not on a test of VLC, mpv or a phone gallery in this repository. If somebody
    does run them, that measurement belongs beside this comment.
    """
    if not cues:
        raise SubtitleError(
            "There are no cues to write out, so a subtitle file would be an empty one — which a "
            "player opens as a track with no subtitles rather than as an error. Register the "
            "subtitles again, or check that the recording carried speech."
        )
    blocks: list[str] = []
    for note in notes:
        if "-->" in note:
            # A NOTE containing an arrow ends the comment early and turns the rest of the sentence
            # into a malformed cue, which a browser answers by dropping every caption after it. These
            # two notes are constants, so this can only fire on a future edit — which is exactly when
            # a sentence is worth more than a silently truncated subtitle track.
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
    """The cues as one passage of prose, for the layer's ``text`` column.

    **WHY A SUBTITLE LAYER CARRIES TEXT AT ALL, since its principal content is the cue list.** Three
    readers need it and none of them can parse a Json column: the annexure, which prints ``text`` and
    would otherwise show a heading with nothing underneath it; a search over the archive, which
    matches strings; and the acceptance screen's preview, which is what a person reads before putting
    their name to a layer. It is a rendering of the cues and never a second source of truth — the
    cues are what a subtitle file is built from, always.

    Speaker labels are written in ``**Name:**`` form here and NOT in the cue list, because this
    string is read as a document and ``services/transcript_format`` already recognises exactly that
    shape as a speaker turn. The same fact in two shapes for two readers, which is the reason the
    label is kept out of ``Cue.text`` in the first place.
    """
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
