"""The pure half of workshop transcription, identity-card OCR and the transcript annexure.

Three things are pinned here, and each of them is a real failure rather than a style preference.

**A misread Aadhaar number must not survive.** OCR confuses 0/O, 1/I, 5/S and 8/B, and a misread
digit produces twelve plausible digits belonging to somebody else. That value is the repository's
deduplication key, it is masked everywhere it is displayed, and nobody would ever read it back and
notice. The Verhoeff checksum is the only thing standing between the misread and the record, so
these tests assert that a failing candidate is dropped and never returned.

**The feature is off until somebody turns it on.** ``build_settings({})`` is the contract that a
deployment with no configuration behaves exactly as it did before this existed.

**The annexure prints a conversation, not Markdown.** The transcript is stored with
``**Interviewer:**`` labels; a report submitted to a ministry that shows those asterisks reads as a
formatting failure, so the label has to arrive as a bold run.

No database and no network: every function under test is pure.
"""

from types import SimpleNamespace

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services.artisan_identity import verhoeff_ok
from app.services.identity_ocr import (
    ENABLE_VAR,
    ENV_VARS,
    IdentityOcrSettings,
    aadhaar_candidates,
    available_providers,
    build_settings,
    pehchan_candidates,
    result_from_reply,
)
from app.services.report_annexures import (
    TranscriptItem,
    annexure_warnings,
    attach_transcripts,
    duration_text,
    first_line,
    speaker_count,
    transcript_annexure_blocks,
    transcript_body_blocks,
    transcripts_of,
)
from app.services.report_model import (
    HeadingBlock,
    PageBreakBlock,
    ParagraphBlock,
    TableBlock,
    runs_text,
)
from app.services.workshop_transcripts import (
    audio_field_map,
    audio_references,
    build_transcript_item,
    wants_transcripts,
)

# --------------------------------------------------------------------------------------
# Aadhaar candidates: the Verhoeff gate
# --------------------------------------------------------------------------------------

# Built rather than pasted, so the fixture cannot rot into an invalid number and quietly make the
# "a valid number is returned" test vacuous.
VALID_AADHAAR = next(
    f"2345678901{tail:02d}" for tail in range(100) if verhoeff_ok(f"2345678901{tail:02d}")
)


def test_the_fixture_number_really_does_satisfy_verhoeff():
    assert verhoeff_ok(VALID_AADHAAR)
    assert len(VALID_AADHAAR) == 12


def test_a_verhoeff_invalid_candidate_is_rejected():
    """THE POINT OF THE WHOLE MODULE. One digit of a valid number is changed — exactly what an
    OCR misread does — and the result must not be offered to the designer at all."""
    last = VALID_AADHAAR[-1]
    broken = VALID_AADHAAR[:-1] + ("0" if last != "0" else "1")
    assert not verhoeff_ok(broken)

    accepted, rejected = aadhaar_candidates(f"AADHAAR {broken}")
    assert accepted == []
    assert rejected == 1


def test_a_verhoeff_valid_candidate_is_returned():
    accepted, rejected = aadhaar_candidates(f"Aadhaar No. {VALID_AADHAAR}")
    assert accepted == [VALID_AADHAAR]
    assert rejected == 0


def test_the_card_grouping_is_normalised_away():
    """A card prints "2345 6789 0123"; the column stores twelve digits. Two spellings of one
    number would defeat the unique index this number exists to feed."""
    spaced = f"{VALID_AADHAAR[0:4]} {VALID_AADHAAR[4:8]} {VALID_AADHAAR[8:12]}"
    hyphened = spaced.replace(" ", "-")
    assert aadhaar_candidates(spaced)[0] == [VALID_AADHAAR]
    assert aadhaar_candidates(hyphened)[0] == [VALID_AADHAAR]


def test_a_number_starting_zero_or_one_is_rejected():
    """UIDAI never issues one, so a leading 0 or 1 is a bad crop that swallowed a digit."""
    for lead in "01":
        candidate = lead + VALID_AADHAAR[1:]
        assert aadhaar_candidates(candidate)[0] == []


def test_a_longer_digit_run_is_not_mined_for_twelve_digit_windows():
    """A sixteen-digit number on the card is not four Aadhaar candidates. Sliding a window over
    it would eventually find one that satisfies Verhoeff by luck, which is the single worst
    outcome available: a checksum-valid number that was never printed on the card."""
    assert aadhaar_candidates("4111111111111111 2345678901234567")[0] == []


def test_a_grouped_vid_does_not_yield_its_own_first_twelve_digits():
    """THE CARD PRINTS ITS VID IN GROUPS, AND THAT IS WHAT DEFEATED THE TEST ABOVE.

    ``test_a_longer_digit_run_is_not_mined_for_twelve_digit_windows`` passed against a CONTIGUOUS
    sixteen-digit run, where the old regex's ``(?![0-9])`` lookahead did its job. A real card does
    not print it that way — it prints "VID : 2345 6789 0124 0831" — and there the twelve digits are
    followed by a space, the lookahead is satisfied, and the front of the VID was returned as an
    Aadhaar candidate off a card whose text contains no Aadhaar number at all.

    Verhoeff does not save this. The number below is constructed so that the sixteen-digit VID and
    its twelve-digit prefix BOTH satisfy the checksum, which is not a contrivance: sampled over
    200,000 Verhoeff-valid sixteen-digit numbers, 10.02% have a Verhoeff-valid twelve-digit prefix.
    Nor does the human confirmation step, which is the whole safety net of this feature: the panel
    would print "2345 6789 0124" and the designer would find those exact twelve digits, in that
    order, printed on the card in their hand.
    """
    vid = "2345 6789 0124 0831"
    assert verhoeff_ok(vid.replace(" ", ""))
    assert verhoeff_ok(vid.replace(" ", "")[:12])

    accepted, rejected = aadhaar_candidates(f"Ramesh Kumar Meena\nVID : {vid}\nBagru, Jaipur\n")
    assert accepted == []
    # And it is not counted as a misread either: nothing about the card was misread, so telling the
    # designer to photograph it again in better light would send them after a fault that is not there.
    assert rejected == 0

    # The enrolment number and the pin code go the same way, and for the same reason.
    assert aadhaar_candidates("Enrolment No.: 1234 56789 01234")[0] == []
    assert aadhaar_candidates("Bagru, Jaipur, Rajasthan 303007")[0] == []


def test_devanagari_digits_are_not_accepted():
    """``str.isdigit()`` is True for "१२३", and such a value stored verbatim would sit in the
    unique index as a different string from its ASCII spelling — the same artisan, twice."""
    assert aadhaar_candidates("२३४५६७८९०१२३")[0] == []


def test_duplicate_readings_are_offered_once():
    accepted, _ = aadhaar_candidates(f"{VALID_AADHAAR} / {VALID_AADHAAR}")
    assert accepted == [VALID_AADHAAR]


# --------------------------------------------------------------------------------------
# A whole model reply
# --------------------------------------------------------------------------------------


def test_result_from_reply_keeps_only_checksum_valid_numbers_and_masks_them():
    broken = VALID_AADHAAR[:-1] + ("0" if VALID_AADHAAR[-1] != "0" else "1")
    result = result_from_reply(
        {"aadhaar": [VALID_AADHAAR, broken], "pehchan": ["pm-vw 1234-ab"], "confidence": 0.88},
        "gemini",
    )
    assert [c.value for c in result.aadhaar] == [VALID_AADHAAR]
    assert result.rejected_aadhaar_count == 1
    # The masked form travels beside the raw one so a UI can show the number without printing it.
    assert result.aadhaar[0].masked == f"XXXX XXXX {VALID_AADHAAR[-4:]}"
    assert [c.value for c in result.pehchan] == ["PMVW1234AB"]
    assert result.payload()["requiresConfirmation"] is True


def test_confidence_never_reaches_certainty():
    """The number is shown to a human to CHECK. "100%" invites them not to."""
    result = result_from_reply({"aadhaar": [VALID_AADHAAR], "confidence": 1.0}, "gemini")
    assert result.aadhaar[0].confidence < 1.0


def test_a_prose_reply_is_still_mined_for_a_valid_number():
    """A model that ignores the JSON instruction has still read the card, and the checksum makes
    trusting a digit run found in prose safe."""
    result = result_from_reply(
        {"text": f"The Aadhaar number printed on the card is {VALID_AADHAAR}."}, "openai"
    )
    assert [c.value for c in result.aadhaar] == [VALID_AADHAAR]


def test_pehchan_candidates_are_normalised_and_shape_checked():
    assert pehchan_candidates(["ab-12 34"]) == ["AB1234"]
    assert pehchan_candidates(["x"]) == []          # below the minimum length
    assert pehchan_candidates([None, 12345]) == ["12345"]


# --------------------------------------------------------------------------------------
# Off by default
# --------------------------------------------------------------------------------------


def test_identity_ocr_is_off_with_no_configuration():
    settings = build_settings({})
    assert settings == IdentityOcrSettings()
    assert settings.enabled is False
    assert settings.notes == ()


def test_the_enable_variable_is_one_of_the_documented_ones():
    """The 503 body names this variable, so it has to be a name an operator can actually set."""
    assert ENABLE_VAR in ENV_VARS


def test_a_malformed_flag_falls_back_instead_of_raising():
    settings = build_settings({ENABLE_VAR: "perhaps"})
    assert settings.enabled is False
    assert settings.notes


def test_no_provider_is_available_while_the_feature_is_off(monkeypatch):
    """Whatever keys the box happens to have, a disabled feature reaches no provider."""
    from app.services import identity_ocr

    monkeypatch.setattr(identity_ocr, "get_identity_ocr_settings", lambda: build_settings({}))
    monkeypatch.setattr(identity_ocr.managed_secrets, "peek_secret", lambda key: "")
    assert available_providers() == []


# --------------------------------------------------------------------------------------
# Reading a stored transcript
# --------------------------------------------------------------------------------------

DIALOGUE = (
    "**Interviewer:** How long have you worked the loom?\n"
    "**Interviewee:** Since I was nine. My father set the warp.\n"
    "---\n"
    "**Interviewer:** And the dye?\n"
)


def test_speakers_are_counted_from_the_labels():
    assert speaker_count(DIALOGUE) == 2
    assert speaker_count("just one voice, no labels") == 1
    assert speaker_count("") == 0


def test_the_first_line_is_speech_and_not_the_label():
    assert first_line(DIALOGUE).startswith("How long have you worked")


def test_duration_reads_as_a_length_and_a_missing_one_reads_as_nothing():
    assert duration_text(754) == "12 min 34 s"
    assert duration_text(45) == "45 s"
    assert duration_text(None) == ""
    assert duration_text(0) == "", "an unrecorded duration is not a zero-length clip"


def test_a_speaker_label_becomes_a_bold_run_not_asterisks():
    blocks = transcript_body_blocks(DIALOGUE)
    paragraphs = [b for b in blocks if isinstance(b, ParagraphBlock)]
    first = paragraphs[0]
    assert first.runs[0].bold is True
    assert first.runs[0].text.startswith("Interviewer:")
    assert "**" not in runs_text(first.runs)


# --------------------------------------------------------------------------------------
# The annexure, from a fake media set
# --------------------------------------------------------------------------------------


def _media(
    media_id: str, text: str, *, status: str = "COMPLETED", seconds: int = 754
) -> SimpleNamespace:
    return SimpleNamespace(
        id=media_id,
        originalFilename=f"{media_id}.m4a",
        mediaType="AUDIO",
        mimeType="audio/mp4",
        extraMetadata={"durationSeconds": seconds},
        transcriptStatus=status,
        transcriptText=text,
        recordedAt=None,
        createdAt=None,
    )


FAKE_MEDIA = [
    (
        _media("aud-1", DIALOGUE),
        ("TRADITIONAL_PROCESS_BASELINE", "traditionalProcess", "artisanAudio"),
    ),
    (
        _media("aud-2", "**Speaker 1:** The finish is what the buyer sees.", seconds=63),
        ("WORKSHOP_OUTCOMES", "outcomes", "feedbackAudio"),
    ),
]


def _items() -> list[TranscriptItem]:
    return [build_transcript_item(row, reference) for row, reference in FAKE_MEDIA]


def test_an_item_is_titled_after_the_field_it_was_recorded_into():
    item = _items()[0]
    assert item.field_label == "Artisan’s spoken explanation"
    assert item.stage_number == 5
    assert item.duration_seconds == 754
    assert item.speaker_count == 2
    assert item.payload()["durationText"] == "12 min 34 s"


def test_the_annexure_builds_blocks_from_a_media_set():
    blocks = transcript_annexure_blocks(_items())

    assert isinstance(blocks[0], PageBreakBlock)
    headings = [b for b in blocks if isinstance(b, HeadingBlock)]
    # One annexure heading plus one per recording.
    assert len(headings) == 3
    assert "Annexure" in runs_text(headings[0].runs)
    assert runs_text(headings[1].runs) == "Artisan’s spoken explanation"

    index = next(b for b in blocks if isinstance(b, TableBlock))
    assert len(index.rows) == 2
    assert runs_text(index.rows[0][2]) == "12 min 34 s"

    body = " ".join(
        runs_text(b.runs) for b in blocks if isinstance(b, ParagraphBlock)
    )
    assert "My father set the warp" in body
    assert "The finish is what the buyer sees" in body
    assert "**" not in body


def test_a_recording_with_no_transcript_yet_is_left_out_and_warned_about():
    pending = build_transcript_item(
        _media("aud-3", "", status="QUEUED"),
        ("SKETCH_REVIEW", "sketchReview", "voiceFeedback"),
    )
    items = [*_items(), pending]

    index = next(b for b in transcript_annexure_blocks(items) if isinstance(b, TableBlock))
    assert len(index.rows) == 2, "a recording with no text must not appear as an empty section"

    warnings = annexure_warnings(items)
    assert len(warnings) == 1
    assert "still being transcribed" in warnings[0]


def test_no_transcripts_means_no_annexure_at_all():
    """The toggle is expressed as "were any attached", so a report generated without it has to be
    the report that was generated before this feature existed — not even a page break."""
    assert transcript_annexure_blocks([]) == ()


# --------------------------------------------------------------------------------------
# Carrying them to the renderer, and the toggle
# --------------------------------------------------------------------------------------


def test_transcripts_ride_on_the_workshop_data_and_read_back():
    from app.services.report_builder import WorkshopData

    data = WorkshopData(workshop_id="w1", title="T")
    assert transcripts_of(data) == ()
    attach_transcripts(data, _items())
    assert len(transcripts_of(data)) == 2


# --------------------------------------------------------------------------------------
# The call site
# --------------------------------------------------------------------------------------
#
# WHAT THESE TWO PIN, and why they go through `build_report` rather than through
# `transcript_annexure_blocks`. Every part of this feature was finished and covered by the tests
# above — the enqueue, the queue, the entitlement gate, the loading, the warnings, the blocks — and
# the annexure still never appeared in a single report, because `ReportBuilder.build` had no
# `ANNEXURE_TRANSCRIPTS` branch. The block builder passed while the document was empty. A test that
# only exercises the module can never see that, so the assertion has to be made against a whole
# document built the way the endpoint builds one.


def _report(items, template_id: str = "DCH_STANDARD"):
    """A whole report, built as the generate route builds it, with `items` attached or not."""
    from app.services.report_builder import WorkshopData, build_report
    from app.services.report_model import ReportMeta

    data = WorkshopData(workshop_id="w1", title="Barpali cluster")
    if items:
        attach_transcripts(data, items)
    document, _warnings = build_report(
        data,
        template_id,
        lambda _media_id: None,
        meta=ReportMeta(title="Barpali cluster", generated_at="2026-08-08T00:00:00Z"),
    )
    return document


def test_the_annexure_reaches_a_built_report_and_not_only_its_own_block_builder():
    document = _report(_items())

    headings = [
        runs_text(b.runs) for b in document.blocks if isinstance(b, HeadingBlock)
    ]
    assert any("Annexure — Recordings and transcripts" in h for h in headings), headings
    assert "Artisan’s spoken explanation" in headings, headings

    index = [b for b in document.blocks if isinstance(b, TableBlock)
             and b.caption.startswith("Recordings transcribed during this workshop.")]
    assert len(index) == 1, "the annexure's contents table did not reach the document"
    assert len(index[0].rows) == 2
    # The caption now carries a second sentence about the ``Speakers`` column, and the match above is
    # a prefix so that this test keeps asserting WHICH table it found rather than pinning the caution's
    # wording — which belongs to the test that is about the caution.
    assert "not a count of the people present" in index[0].caption

    body = " ".join(
        runs_text(b.runs) for b in document.blocks if isinstance(b, ParagraphBlock)
    )
    assert "My father set the warp" in body
    assert "**" not in body, "the stored Markdown labels were printed verbatim into the report"


def test_a_report_with_nothing_printable_is_the_report_it_was_before_this_branch():
    """Attached but unprintable is the case that decides whether this branch was safe to add.

    Every template carries the transcript section, so the new branch runs on EVERY report ever
    generated. A workshop whose recordings are all still in the queue attaches items that print
    nothing — and the document must come out identical to the one built with no attachment at all:
    no page break, no numbered heading, no empty table. A blank annexure at the back of every
    report would be a worse regression than the missing one this branch fixes. Compared block for
    block rather than by searching the text, because a stray PageBreakBlock carries no text to
    search for and is exactly what a careless `page_break_before` would leave behind.
    """
    pending = build_transcript_item(
        _media("aud-9", "", status="QUEUED"),
        ("SKETCH_REVIEW", "sketchReview", "voiceFeedback"),
    )
    assert _report([pending]).blocks == _report(None).blocks


@pytest.mark.parametrize(
    ("option", "saved", "expected"),
    [
        (None, {}, False),                          # nobody asked: unchanged behaviour
        (None, {"includeTranscripts": True}, True),  # the stage-20 toggle
        (False, {"includeTranscripts": True}, False),  # this one file, without them
        (True, {}, True),                            # this one file, with them
    ],
)
def test_the_request_overrides_the_saved_setting(option, saved, expected):
    assert wants_transcripts(option, saved) is expected


# --------------------------------------------------------------------------------------
# Finding the audio on a stage
# --------------------------------------------------------------------------------------


def test_audio_fields_are_discovered_from_the_registry_not_a_list():
    """A new AUDIO field must be transcribed with no change to the transcription code."""
    fields = audio_field_map()
    assert fields["traditionalProcess"]["artisanAudio"]
    assert fields["prototype"]["audioNarration"]
    assert "sketch" not in fields, "a stage with no audio must not appear"


def test_only_audio_fields_are_followed():
    """An IMAGE field on the same entity holds a media id too, and transcribing a photograph
    would burn a provider call per sketch."""
    rows = [
        SimpleNamespace(
            stageKey="TRADITIONAL_PROCESS_BASELINE",
            entityKey="traditionalProcess",
            data={"artisanAudio": "aud-1", "processDiagram": "img-1"},
        ),
    ]
    assert audio_references(rows) == {
        "aud-1": ("TRADITIONAL_PROCESS_BASELINE", "traditionalProcess", "artisanAudio")
    }


# --------------------------------------------------------------------------------------
# The speaker labels: a model's guess, printed into a document sent to a ministry
#
# `report_ai_layers` was given this caution and this annexure was left without it, which is the whole
# defect. The transcript annexure is the LONGER of the two and the one that prints `**Speaker 1:**`
# down page after page, so it was the worse of the two places to be missing it.
# --------------------------------------------------------------------------------------


def test_a_transcript_with_speaker_turns_carries_the_caution_above_its_text():
    """ABOVE, and the placement is the assertion. ``report_ai_layers`` states the rule: "a caution
    about who is speaking, printed after two pages of dialogue, arrives once the reader has already
    assigned the lines." An eleven-minute interview puts three pages between the first line and the
    end, so a note at the bottom qualifies nothing a reader has not already believed."""
    from app.services.report_annexures import SPEAKER_NOTE

    blocks = transcript_annexure_blocks(_items())
    paragraphs = [runs_text(b.runs) for b in blocks if isinstance(b, ParagraphBlock)]

    assert any(p == SPEAKER_NOTE for p in paragraphs), "the diarization caution never reached the page"
    caution_at = next(i for i, p in enumerate(paragraphs) if p == SPEAKER_NOTE)
    speech_at = next(i for i, p in enumerate(paragraphs) if "My father set the warp" in p)
    assert caution_at < speech_at, "the caution printed after the dialogue it was meant to qualify"


def test_the_caution_says_a_machine_decided_the_labels_and_names_the_two_ways_it_is_wrong():
    """The content matters as much as the presence. A vague "machine-assisted" note is what the
    annexure's general lead already says; what an officer needs is that the labels can MERGE two
    people or SPLIT one, because that is the error which turns an interviewer's words into an
    artisan's under a named person's acceptance."""
    from app.services.report_annexures import SPEAKER_NOTE

    assert "not by anybody who was present" in SPEAKER_NOTE
    assert "merge two speakers into one or split one across two" in SPEAKER_NOTE
    assert "is not a name" in SPEAKER_NOTE


def test_a_recording_with_no_speaker_labels_gets_no_caution():
    """A solo voice note names no speakers, and telling an officer that its absent labels were
    guessed would be a false statement made in the name of honesty."""
    from app.services.report_annexures import SPEAKER_NOTE, speaker_labels_are_guessed

    solo = build_transcript_item(
        _media("aud-solo", "The vat has to rest three days before it will take."),
        ("WORKSHOP_OUTCOMES", "outcomes", "feedbackAudio"),
    )
    assert speaker_labels_are_guessed(solo) is False

    blocks = transcript_annexure_blocks([solo])
    paragraphs = [runs_text(b.runs) for b in blocks if isinstance(b, ParagraphBlock)]
    assert not any(p == SPEAKER_NOTE for p in paragraphs)


def test_the_lead_no_longer_promises_that_the_labels_are_roles():
    """THE SENTENCE THAT WAS FALSE, and false in the direction that makes a reader trust the page
    more. It read: "where the transcript names speakers, the names are those of the roles rather than
    of the individuals" — which says somebody assigned roles. Nobody did: the labels are
    ``**Speaker 1:**``, numbered by order of first speaking out of whatever the provider's diarizer
    separated. A promise that they are roles converted a guess into a taxonomy."""
    blocks = transcript_annexure_blocks(_items())
    lead = " ".join(runs_text(b.runs) for b in blocks if isinstance(b, ParagraphBlock))
    assert "roles rather than of the individuals" not in lead


def test_the_speaker_count_column_says_whose_count_it_is():
    """A column of bare numerals in a contents table is the shortest and most authoritative-looking
    form of the same claim: "3" in a ministry document reads as three people established, not as
    three voices a model thought it could tell apart. A reader takes a number out of a table without
    reading the paragraphs below it, so the qualification has to be in the caption."""
    blocks = transcript_annexure_blocks(_items())
    index = next(b for b in blocks if isinstance(b, TableBlock))
    assert "Speakers" in [c.header for c in index.columns]
    assert "the transcribing machine separated" in index.caption
