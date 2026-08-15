"""The transcript annexure: every recording a workshop collected, printed at the back of the report.

WHAT THIS IS FOR. A design workshop records the artisans talking — the spoken explanation in
stage 5, the voice note against a sketch, the voice feedback on a prototype. Those recordings are
transcribed automatically by the existing media queue (see :mod:`app.services.media_queue`) and the
text lands on ``MediaFile.transcriptText``. Until now it stayed there: the report printed the
photographs and the typed fields, and the only way an officer could read what an artisan actually
said was to be sent the audio. This module turns the whole set into an annexure that a designer
appends with one toggle — ``includeTranscripts`` on the stage-20 report settings.

WHY IT IS A SEPARATE MODULE. :mod:`app.services.report_builder` is the generic template
interpreter, and it is deliberately free of per-stage and per-feature code. So the annexure lives
here and the builder reaches it in one branch — ``ReportBuilder.build``'s
``SpecialSection.ANNEXURE_TRANSCRIPTS`` arm, which calls :func:`append_transcript_annexure` and
nothing else. That branch was missing for the whole of this module's first life, which meant every
report silently dropped the annexure while three surfaces on the Android export screen told the
designer the office's copy would carry it. A module with no call site is not a feature.

WHY THE TRANSCRIPTS TRAVEL ON ``WorkshopData`` RATHER THAN BEING FETCHED HERE. Every renderer in
this pipeline is synchronous, on purpose: that is what lets the Kotlin port on Android be a
transliteration rather than a reimplementation, and it is why media is resolved in one pass before
the render starts (see ``design_workshops.MediaIndex``). A transcript is a database read, so it is
loaded with everything else and carried in, never awaited mid-render.

NOTHING HERE DECIDES WHETHER TRANSCRIPTS ARE WANTED. An annexure with no items renders no heading
and no page break at all, so the toggle is expressed simply by whether the caller attached any
items. A report generated without the toggle is byte-for-byte the report that was generated before
this module existed.
"""

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

DEFAULT_HEADING = "Annexure — Recordings and transcripts"

# A single runaway transcript must not turn a report into a document nobody can open. A two-hour
# group sitting transcribes to a few thousand paragraphs, which is long but legitimate; a provider
# stuck in a repetition loop has produced tens of thousands, and both renderers lay out every one of
# them before the designer sees a single page. The cap is far above any real interview and far below
# the point at which the render stops finishing, and the truncation says so in the document rather
# than silently dropping the tail — a missing half of an artisan's answer that nobody is told about
# is worse than a visible note explaining where it stopped.
MAX_PARAGRAPHS_PER_TRANSCRIPT = 1200

# The stored transcript form, fixed by services/ai.py and services/transcript_format.py: a speaker
# turn is a BOLD span of at most 60 characters ending in a colon at the start of a line. Recognising
# it here is what makes the annexure read as a conversation instead of as one wall of asterisks.
_LOOSE_LABEL_RE = re.compile(r"\*\*\s*([^*\n]{1,60}?)\s*\*\*\s*:")
_SPEAKER_LINE_RE = re.compile(r"^\*\*([^*\n]{1,60}?):\*\*\s*(.*)$")
_RULE_RE = re.compile(r"^\s*(?:-{3,}|\*{3,}|_{3,})\s*$")
_HEADING_RE = re.compile(r"^\s*#{1,6}\s+(\S.*?)\s*#*\s*$")
_BULLET_RE = re.compile(r"^\s*[-*+]\s+(?=\S)")
_CODE_RE = re.compile(r"`([^`\n]+)`")
# Bold before italic, so "**x**" is never read as an empty italic wrapping "*x*".
_EMPHASIS_RE = re.compile(r"\*\*\s*([^*\n]+?)\s*\*\*|\*\s*([^*\n]+?)\s*\*")


# --------------------------------------------------------------------------------------
# One transcript, as every surface reads it
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class TranscriptItem:
    """One recording and its transcript, already resolved from the database.

    Deliberately a flat value object with no Prisma row inside it: the same instance is serialised
    by ``GET /design-workshops/{id}/transcripts`` for the designer to review BEFORE committing to a
    report, and consumed by :func:`append_transcript_annexure` when they do. Two shapes would drift,
    and the drift would show as the preview listing a recording the report then omitted.
    """

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
        """How the recording is titled in the annexure and in the picker.

        The field's own label first ("Artisan's spoken explanation"), because that is what the
        designer pressed record under; the filename only when the registry gave us nothing, and the
        media id only when even that is missing. A heading reading ``rec_0007.m4a`` tells a reader
        nothing about which part of the workshop they are about to read.
        """
        return self.field_label or self.filename or self.media_id

    def payload(self) -> dict[str, Any]:
        """The JSON the transcripts endpoint serves. Camel-case per the house style."""
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


# --------------------------------------------------------------------------------------
# Reading a stored transcript
# --------------------------------------------------------------------------------------


def _normalised_lines(text: str) -> list[str]:
    """The transcript as display lines: labels folded to one shape, rules kept as blanks."""
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
    """The transcript with its Markdown removed — for word counts and for the first line.

    Counting words on the raw text would count ``**Interviewer:**`` as a word and would make the
    listing's word count disagree with what the annexure visibly prints.
    """
    stripped = _EMPHASIS_RE.sub(lambda m: m.group(1) or m.group(2) or "", str(text or ""))
    return " ".join(stripped.split())


def speaker_count(text: str) -> int:
    """How many distinct voices the transcript names.

    Counted from the labels rather than stored on the row on purpose: the provider's own speaker
    count is only known at transcription time and is not persisted (see ``media_queue.
    _transcript_write``, which writes the text and the status and nothing else), while the labels
    survive every later edit including the human corrections a researcher makes by hand. A
    transcript with no labels is one voice if it has any words at all, and zero when it is empty —
    which is what the picker needs in order to show "1 speaker" rather than "0 speakers" for a solo
    voice note.
    """
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
    """The opening words, for a picker row. Never the speaker label on its own.

    The designer is choosing which recordings to append, and a list of rows all reading
    "Interviewer:" is a list they cannot choose from — so the label is dropped and the first line
    that carries actual speech is returned, elided to one line's worth.
    """
    for line in _normalised_lines(text):
        match = _SPEAKER_LINE_RE.match(line)
        body = match.group(2) if match else line
        body = plain_text(body)
        if body:
            return body if len(body) <= limit else body[: limit - 1].rstrip() + "…"
    return ""


def duration_text(seconds: int | None) -> str:
    """``754`` -> ``"12 min 34 s"``. Blank when the duration was never recorded.

    Blank rather than "0 s": a clip whose duration the uploader never sent is not a clip of zero
    length, and printing zero in an annexure of evidence would read as a failed recording.
    """
    if seconds is None or seconds <= 0:
        return ""
    minutes, remainder = divmod(int(seconds), 60)
    hours, minutes = divmod(minutes, 60)
    if hours:
        return f"{hours} h {minutes:02d} min"
    if minutes:
        return f"{minutes} min {remainder:02d} s"
    return f"{remainder} s"


# --------------------------------------------------------------------------------------
# Carrying the transcripts to the renderer
# --------------------------------------------------------------------------------------

# The attribute the items travel on. NOT a declared field of ``WorkshopData``: that dataclass lives
# in report_builder.py, which is owned by another change in flight, and a feature that can be wired
# without editing a file someone else is holding should be. ``WorkshopData`` is a plain dataclass
# with no ``slots``, so the attribute simply attaches; if the field is later declared there for real
# this keeps working unchanged, because both halves go through the two functions below and never
# touch the attribute name directly.
_ATTR = "transcripts"


def attach_transcripts(data: Any, items: list[TranscriptItem] | tuple[TranscriptItem, ...]) -> Any:
    """Put the loaded transcripts on the workshop data the builder will walk. Returns ``data``."""
    try:
        setattr(data, _ATTR, tuple(items))
    except AttributeError:
        # A ``slots`` dataclass would refuse the attribute. Losing the annexure is the right
        # failure: a report missing an appendix is a report, and raising here would take away a
        # designer's ability to generate anything at all over an optional extra.
        return data
    return data


def transcripts_of(data: Any) -> tuple[TranscriptItem, ...]:
    """The transcripts attached to ``data``, or none. Safe on any workshop data object."""
    return tuple(getattr(data, _ATTR, ()) or ())


# --------------------------------------------------------------------------------------
# The blocks
# --------------------------------------------------------------------------------------


def _runs_for_line(line: str) -> tuple[Run, ...]:
    """One display line as runs, with the Markdown emphasis turned into real formatting."""
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
    """One transcript's text as report blocks: a paragraph per speaker turn.

    The speaker label becomes a real bold run and its asterisks disappear, exactly as
    ``transcript_format.transcript_cell`` does for the Excel export. Printing the stored Markdown
    verbatim would put ``**Interviewee 2:**`` into a document submitted to a ministry, which reads
    as a formatting failure rather than as a transcript.
    """
    blocks: list[Block] = []
    truncated = False
    for line in _normalised_lines(text):
        if len(blocks) >= MAX_PARAGRAPHS_PER_TRANSCRIPT:
            truncated = True
            break
        if not line.strip():
            # A topic rule or a blank line: a gap, not an empty paragraph. An empty paragraph in
            # DOCX is a visible blank line that accumulates into pages of nothing.
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
        blocks.append(ParagraphBlock(
            runs=runs_of(
                f"[Transcript truncated after {MAX_PARAGRAPHS_PER_TRANSCRIPT} paragraphs. The "
                f"full text is held against the recording in the repository.]"
            ),
            style=ParaStyle.NOTE,
        ))
    return blocks


#: Printed under every transcript heading whose text carries speaker turns.
#:
#: **A SPEAKER LABEL IS A MODEL'S GUESS AT WHO SPOKE, AND THIS ANNEXURE PRINTED IT LIKE A FACT.** The
#: text below every heading here is ``MediaFile.transcriptText``, and ``ai._diarized_markdown`` builds
#: its ``**Speaker 1:**`` labels by numbering whatever the provider separated out of the audio alone.
#: Nobody in the courtyard told this system how many people were in the room or which of them was
#: talking. Scribe v2 will separate up to 32 voices; both engines merge two quiet speakers, split one
#: person who moved away from the microphone, and hand an interjection to whoever was louder. What an
#: officer sees is a named-looking label down a page of a sanctioned report, which reads as a record of
#: who said what.
#:
#: **THE SAME SENTENCE AS ``report_ai_layers.SPEAKER_NOTE``, AND DELIBERATELY NOT AN IMPORT OF IT.** The
#: AI-layer annexure was given this caution and this one was left, which is the defect; copying the
#: wording rather than sharing the constant is the choice this repository already makes for the speaker
#: regex one module over (*"quoted rather than imported ... kept adjacent and named so a change to one is
#: a change somebody has to make to both"*). A report can carry BOTH annexures, and two paragraphs that
#: are meant to say the same thing must be able to be compared by eye in the document rather than being
#: guaranteed identical by an import that hides which pages actually carry it.
#:
#: **WHY THERE IS NO SOURCE-KIND CONDITION HERE, WHICH IS THE ONE HALF THAT IS SIMPLER THAN THE LAYER
#: CASE.** ``speaker_labels_are_guessed`` over a layer has to check that the layer stands on a
#: RECORDING, because a layer built on supplied text may carry turns a designer typed — "**Rita:** …" in
#: their own note — and telling an officer those were machine-guessed would be a false statement made in
#: the name of honesty. Every item in THIS annexure is a recording by construction:
#: ``workshop_transcripts.load_transcript_items`` builds each one from a ``MediaFile`` row reached
#: through an AUDIO field, and the text is that row's transcript. There is no typed-turns case to
#: exclude. Where a researcher has corrected the transcript by hand through
#: ``POST /media/{id}/transcript``, the labels they corrected are still the ones the diarizer proposed —
#: ``speaker_count``'s own docstring records that the labels *"survive every later edit including the
#: human corrections a researcher makes by hand"* — so the caution stays true of that case too, and the
#: sentence's instruction (check a line against the recording) is exactly what such a researcher did.
SPEAKER_NOTE = (
    "The speaker labels in this transcript — who is saying which line — were decided by the machine "
    "that transcribed the recording, not by anybody who was present. Nothing recorded how many people "
    "were speaking or who they were: the labels are that machine's separation of the voices it heard, "
    "and it can merge two speakers into one or split one across two. The numbering is by order of first "
    "speaking and is not a name. Check a line against the recording before attributing it to a "
    "particular person."
)


def speaker_labels_are_guessed(item: TranscriptItem) -> bool:
    """Whether this transcript prints speaker turns a diarizer decided. See :data:`SPEAKER_NOTE`.

    One condition and not two, unlike the AI-layer twin: every item here came from a recording, so the
    only question is whether the text names speakers at all. A transcript with no labels has nothing to
    caution and gets no paragraph — printing one would tell an officer that a solo voice note's absent
    labels were guessed.
    """
    return bool(item.text) and any(
        _SPEAKER_LINE_RE.match(line) for line in _normalised_lines(item.text)
    )


def transcript_index_block(items: tuple[TranscriptItem, ...]) -> TableBlock:
    """The contents table at the head of the annexure — what was recorded, and how much of it.

    THE ``Speakers`` COLUMN IS A MACHINE'S COUNT AND THE CAPTION NOW SAYS SO. It is derived from the
    labels (``speaker_count``), which are the diarizer's separation of the voices — so a column of bare
    numerals in a contents table was the shortest and most authoritative-looking form of the same claim
    :data:`SPEAKER_NOTE` exists to qualify: "3" in a ministry document reads as three people
    established, not as three voices a model thought it could tell apart. The caption is where it
    belongs, because a reader takes a number out of a table without reading the paragraphs below it.
    """
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
                runs_of(f"{item.stage_number}. {item.stage_title}" if item.stage_number
                        else item.stage_title),
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
    """The one line under each transcript heading saying where the text came from.

    A generated report is read by people who will quote it. They have to be able to tell an
    automatic transcript from a typed answer, and to find the source recording again — so the media
    id, the length, the number of voices and the date are printed together, in the NOTE style that
    every other caveat in this report uses.
    """
    parts = [p for p in (
        duration_text(item.duration_seconds),
        f"{item.speaker_count} speaker{'s' if item.speaker_count != 1 else ''}"
        if item.speaker_count else "",
        item.recorded_at[:10] if item.recorded_at else "",
        f"recording {item.media_id}",
    ) if p]
    return "Transcribed automatically from the workshop recording · " + " · ".join(parts)


def append_transcript_annexure(
    doc: DocumentBuilder,
    items: tuple[TranscriptItem, ...] | list[TranscriptItem],
    *,
    heading: str = "",
    numbered: bool = True,
    page_break_before: bool = True,
) -> int:
    """Append the whole annexure to ``doc``. Returns how many transcripts were printed.

    THE ONE CALL SITE is ``report_builder.ReportBuilder.build``, in the ``if/elif`` chain over
    ``section.special``, beside the ``ANNEXURE_MEDIA`` branch. Keep it the only one: two callers
    would mean two chances to pass a different heading or a different ``numbered``, and a report
    whose annexure is numbered on the phone and unnumbered at the office is exactly the divergence
    the report port exists to end.

    With no transcripts attached — the toggle off, or nothing transcribed yet — this appends
    nothing at all, not even the page break, so a template carrying the section is byte-for-byte
    the template it was before whenever the designer did not ask.

    Headings go through ``doc.heading`` rather than being constructed here because the "3.2"
    counters and the Word bookmarks are the DocumentBuilder's to maintain — a heading built by hand
    would be missing from the table of contents and unclickable in the .docx.
    """
    printed = [item for item in items if item.has_text]
    if not printed:
        return 0
    if page_break_before:
        doc.add(PageBreakBlock())
    doc.heading(heading or DEFAULT_HEADING, 1, numbered=numbered)
    # THE SECOND CLAUSE USED TO BE FALSE, and it was false in the direction that misleads a reader into
    # trusting the page more. It read: "where the transcript names speakers, the names are those of the
    # roles rather than of the individuals" — which says somebody assigned roles. Nobody did. The labels
    # are `**Speaker 1:**`, `**Speaker 2:**`, numbered by order of first speaking out of whatever the
    # provider's diarizer separated (`ai._diarized_markdown`), and "Speaker 2" is not a role: it is an
    # index into a machine's guess. A sentence promising an officer that the labels are roles converted
    # a guess into a taxonomy. What replaces it claims only what is true of the text, and the claim about
    # the labels is made properly, per transcript, by SPEAKER_NOTE below.
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
            # ABOVE THE TRANSCRIPT AND NEVER BELOW IT, which is `report_ai_layers`' own stated rule:
            # "a caution about who is speaking, printed after two pages of dialogue, arrives once the
            # reader has already assigned the lines." It matters more here than there, because a
            # transcript is the longest thing in the document — a note at the end of an eleven-minute
            # interview is three pages after the first line it was meant to qualify.
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
    """The annexure as a plain block tuple, built in isolation.

    For tests, for the web preview, and for any caller that wants the blocks without a document
    around them. The heading numbers restart at 1 because nothing else has been counted, which is
    why the real report goes through :func:`append_transcript_annexure` instead — a section numbered
    "1" in the middle of a report that is already at section 20 is worse than no number.
    """
    scratch = DocumentBuilder(meta=ReportMeta(title=""))
    append_transcript_annexure(
        scratch, items, heading=heading, numbered=numbered,
        page_break_before=page_break_before,
    )
    return scratch.build().blocks


# ``MediaFile.transcriptStatus`` values, split by what the designer can do about them. These are
# NOT job statuses — the two vocabularies are different, and RATE_LIMITED never reaches this column
# because a throttled run writes the clip back to QUEUED for the worker to finish later.
#
# A SECOND COPY OF A RULE IS HOW MOST OF THIS REPOSITORY'S DEFECTS AROSE, SO SAY WHY THIS ONE EXISTS.
# The authority for "which statuses will be re-attempted" is
# ``workshop_transcripts._SETTLED_TRANSCRIPT_STATUSES`` — but ``workshop_transcripts`` imports
# :class:`TranscriptItem` FROM this module, so importing it back is a cycle. The copy is five tokens,
# and ``test_the_annexure_warning_reads_the_same_status_vocabulary_as_the_enqueuer`` compares both
# sets below against that module's own, so the two cannot silently diverge: a status added there
# without a decision here fails a test rather than mis-wording a warning.
_IN_FLIGHT_STATUSES = frozenset({"QUEUED", "PROCESSING"})
#: Terminal on the clip and NOT settled, so the next save of the clip's stage re-queues it — see
#: ``workshop_transcripts._SETTLED_TRANSCRIPT_STATUSES``, which deliberately contains neither.
_STALLED_STATUSES = frozenset({"FAILED", "UNAVAILABLE"})


def annexure_warnings(items: tuple[TranscriptItem, ...] | list[TranscriptItem]) -> list[str]:
    """What the designer should be told about the transcripts they asked to append.

    A recording that is still queued, or whose transcription failed, is silently absent from the
    annexure — and silence is exactly wrong here: the designer toggled "include transcripts",
    counted the recordings they made, and would otherwise have to notice the shortfall themselves
    in a 60-page document. These reach the ``X-Report-Warnings`` header beside the download.

    **"NO TRANSCRIPT YET" IS A PROMISE, AND FOR HALF OF THESE CLIPS IT WAS A FALSE ONE.** This
    function used to count only QUEUED/PROCESSING as pending and fold everything else into one
    sentence reading "N recording(s) have no transcript **yet**" — so a clip whose transcription had
    FAILED, or whose provider is not configured at all (UNAVAILABLE), or whose artisan REFUSED
    consent to transcription (``media_queue._record_transcription_refused`` writes FAILED with the
    refusal in ``transcriptError``) was reported to the designer as a transcript on its way. The
    designer's only rational response to that sentence is to wait, and waiting is the one thing that
    never helps: nothing re-attempts a stalled clip until its stage is saved again.

    That wording became measurably more wrong the day the queue started writing the clip's terminal
    state. Before it, an exhausted transcription job left the column at QUEUED — wrongly, but at
    least inside the "still being transcribed" count. Now ``_handle_job_failure`` writes FAILED, so
    exactly the clips that most need the designer to act are the ones that fell into the silent half
    of this sentence.

    So the two classes are counted and worded separately, and the stalled clause says what to DO.
    Saving the stage again is a real recovery and the only one a designer can reach without an
    admin: FAILED and UNAVAILABLE are deliberately absent from
    ``workshop_transcripts._SETTLED_TRANSCRIPT_STATUSES`` precisely so the next
    ``enqueue_stage_transcriptions`` picks the clip up. Do not "simplify" the two counts back into
    one number — the whole point is that one of them is waiting and the other is not.

    A clip in neither class (an empty status on a recording nothing has looked at yet) is still in
    the leading count and gets no clause of its own: there is nothing true to say about it beyond
    that it is not in the file.
    """
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
        # "will not arrive on their own" rather than "failed": UNAVAILABLE is not a failure of the
        # recording, it is a deployment with no transcription provider configured, and telling a
        # designer their audio failed when the server was never able to try is how a working
        # recording gets deleted and made again.
        clauses.append(
            f"{stalled} will not arrive on their own — saving the stage they are attached to "
            f"re-attempts them"
        )
    detail = f" ({'; '.join(clauses)})" if clauses else ""
    # "have no transcript" and no longer "have no transcript YET": the leading sentence covers both
    # classes, and the word that promised arrival is now only in the clause that can keep it.
    message = (
        f"{len(missing)} recording(s) have no transcript and were left out of the "
        f"transcript annexure{detail}."
    )
    return [message]
