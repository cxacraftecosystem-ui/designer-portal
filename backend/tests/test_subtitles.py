"""Timed text, pinned: the arithmetic, the legibility rules, and the two file formats.

**NO DATABASE, NO NETWORK, NOTHING SKIPS.** Every rule here is decided by a pure function in
``app.services.subtitles``, which is the whole reason that module exists separately from the verb
that produces it: a start time written in seconds in one half of a codebase and milliseconds in the
other is the classic subtitle defect, and it is only catchable by arithmetic tests.

What would break in the field if each group stopped holding:

* **The cue model.** A reversed or negative timing is refused by every player rather than shown out
  of order, so a malformed provider answer has to fail here with a sentence rather than be stored and
  discovered when a video is being shown to a room.
* **``fit_cues``.** Providers answer in the wrong units for a subtitle in BOTH directions — Scribe
  per word, Deepgram per sentence — so the raw answer is either a flicker or a wall of text left on
  screen for fifteen seconds. This is where one becomes the other.
* **The renderers.** A SubRip file with the wrong separator, or a WebVTT file missing its header,
  opens as a track with NO subtitles rather than as an error. Nothing tells the designer.
"""

from itertools import pairwise

import pytest

from app.services.subtitles import (
    MAX_CUE_CHARS,
    MAX_CUE_SECONDS,
    MIN_CUE_SECONDS,
    PAYLOAD_SCHEMA,
    SPEAKER_NOTE,
    Cue,
    SubtitleError,
    cue_of,
    cues_of_payload,
    cues_payload,
    fit_cues,
    label_speakers,
    over_ceilings,
    plain_reading,
    to_srt,
    to_vtt,
)

# --------------------------------------------------------------------------------------
# One cue: what may be stored at all
# --------------------------------------------------------------------------------------


def test_a_cue_that_ends_before_it_starts_is_refused():
    """Players do not show a reversed cue out of order — they refuse the file. So this refuses the
    cue, with a sentence, at the point where the provider's answer is being read."""
    with pytest.raises(SubtitleError) as refused:
        Cue(start=4.0, end=1.0, text="The dabu paste…")
    assert "before it starts" in str(refused.value)


@pytest.mark.parametrize("bad", [-1.0, float("nan"), float("inf"), "2.0s", None, True])
def test_a_timing_that_is_not_a_real_number_of_seconds_is_refused(bad):
    """``True`` is in this list on purpose: it is an ``int`` in Python and would otherwise be stored
    as a cue starting at one second."""
    with pytest.raises(SubtitleError):
        Cue(start=bad, end=9.0, text="x")


def test_a_cue_with_no_words_is_refused_rather_than_stored():
    """A blank caption is a blank rectangle on screen for a second and a half, which reads as a
    rendering fault rather than as a silence."""
    with pytest.raises(SubtitleError):
        Cue(start=0.0, end=1.0, text="   ")


def test_a_stored_cue_that_is_not_an_object_is_refused_with_the_next_move():
    """The payload is a Json column: a row written by a backfill, or by a build whose shape has moved
    on, must fail here rather than reach a renderer and produce a file no player opens."""
    with pytest.raises(SubtitleError) as refused:
        cue_of(["0", "2", "hello"])
    assert "register" in str(refused.value).lower()


# --------------------------------------------------------------------------------------
# fit_cues: a provider's answer is not a subtitle
# --------------------------------------------------------------------------------------


def _words(*pairs, speaker=""):
    return [Cue(start=s, end=e, text=t, speaker=speaker) for s, e, t in pairs]


def test_scribe_style_words_are_joined_into_readable_cues():
    """One word per caption is a flicker, not a subtitle. Scribe answers per word."""
    cues = fit_cues(
        _words(
            (0.0, 0.4, "The"),
            (0.45, 0.9, "dabu"),
            (0.95, 1.4, "paste"),
            (1.45, 1.9, "is"),
            (1.95, 2.6, "mixed"),
        )
    )
    assert len(cues) == 1
    assert cues[0].text == "The dabu paste is mixed"
    assert cues[0].start == 0.0
    assert cues[0].end == 2.6


def test_a_pause_breaks_a_cue_rather_than_leaving_a_caption_over_a_silence():
    """Joining across a long gap puts a caption on screen while nobody is speaking, which reads — to
    somebody relying on subtitles — as the speaker still talking."""
    cues = fit_cues(_words((0.0, 0.5, "Yes"), (6.0, 6.4, "afterwards")))
    assert [c.text for c in cues] == ["Yes", "afterwards"]


def test_a_speaker_change_always_breaks_a_cue():
    """Two voices in one caption attributes half of it to the wrong person, which in this archive
    means attributing an artisan's words to the interviewer."""
    fragments = [
        Cue(start=0.0, end=0.5, text="How is it mixed?", speaker="1"),
        Cue(start=0.55, end=1.2, text="With gum.", speaker="2"),
    ]
    cues = fit_cues(fragments)
    assert [c.speaker for c in cues] == ["1", "2"]


def test_a_deepgram_style_sentence_that_runs_too_long_is_split():
    """A sentence in an unhurried interview runs past both ceilings at once. Neither a fifteen-second
    caption nor a two-hundred-character one is readable."""
    long_sentence = " ".join(["mixing"] * 60)
    cues = fit_cues([Cue(start=0.0, end=24.0, text=long_sentence)])
    assert len(cues) > 1
    for cue in cues:
        assert cue.seconds <= MAX_CUE_SECONDS + 0.001
        assert len(cue.text) <= MAX_CUE_CHARS + 12  # word boundaries, never mid-word


def test_a_split_ends_exactly_where_the_source_ended():
    """The last boundary is the one a viewer can check against the audio, so it is the real end and
    never the sum of the per-piece estimates — which would drift a few milliseconds short."""
    cues = fit_cues([Cue(start=10.0, end=34.0, text=" ".join(["word"] * 80))])
    assert cues[0].start == 10.0
    assert cues[-1].end == 34.0


def test_a_flash_of_text_is_extended_but_never_over_the_next_cue():
    """Under a second a subtitle is gone before the eye has fixed on it and the viewer knows only
    that they missed something. Overlapping two captions is worse — every player renders it
    differently."""
    stretched = fit_cues([Cue(start=0.0, end=0.2, text="Yes")])
    assert stretched[0].seconds >= MIN_CUE_SECONDS

    crowded = fit_cues(
        [Cue(start=0.0, end=0.2, text="Yes"), Cue(start=5.0, end=5.3, text="No")]
    )
    assert crowded[0].end <= crowded[1].start


# --------------------------------------------------------------------------------------
# Estimated boundaries: the one number in this module nobody measured
# --------------------------------------------------------------------------------------


def test_every_piece_a_proportional_split_makes_says_its_timings_are_estimated():
    """**A CUE BOUNDARY IS A CLAIM ABOUT WHEN SOMEBODY SPOKE**, and a designer plays these against
    video of an artisan speaking, so it is checkable by eye. The proportional split divides a block by
    how long the words ARE, and a word does not take time proportional to its length: one pause
    mid-sentence lands a caption a second or two from the speech. That is invisible in the data and
    obvious on the screen, so the data has to say it.

    INCLUDING THE FIRST AND LAST PIECES, which keep one measured boundary each. A cue is a PAIR of
    boundaries and each of those has one this module invented; a flag meaning "half of this cue is
    exact" would be read as "this cue is exact".
    """
    cues = fit_cues([Cue(start=0.0, end=24.0, text=" ".join(["mixing"] * 60))])
    assert len(cues) > 1
    assert all(cue.estimated for cue in cues)


def test_a_cue_whose_boundaries_came_from_the_provider_is_never_marked_estimated():
    """The positive control. If everything were marked, the flag would say nothing."""
    cues = fit_cues(_words((0.0, 0.4, "The"), (0.45, 0.9, "dabu"), (0.95, 1.4, "paste")))
    assert [cue.estimated for cue in cues] == [False]


def test_stretching_a_flash_of_text_does_not_make_a_cue_estimated():
    """The line this draws, pinned so it is not re-drawn by accident: a stretched end is a DISPLAY
    duration — how long the caption stays up, set by legibility in every subtitling guideline — and it
    never claims a word was still being said. A split boundary is a claim about when a word was said.
    One is checkable against the audio and wrong; the other is a convention and right."""
    stretched = fit_cues([Cue(start=0.0, end=0.2, text="Yes")])
    assert stretched[0].seconds >= MIN_CUE_SECONDS
    assert stretched[0].estimated is False


def test_the_payload_counts_estimated_cues_and_carries_the_flag_back():
    """``estimatedCues`` is always present and usually zero, which is the point of a number rather
    than a flag: zero is a statement that every boundary came from the provider."""
    measured = cues_payload([Cue(start=0.0, end=1.0, text="x")])
    assert measured["estimatedCues"] == 0
    assert "estimated" not in measured["cues"][0]

    guessed = cues_payload([Cue(start=0.0, end=1.0, text="x", estimated=True)])
    assert guessed["estimatedCues"] == 1
    assert guessed["cues"][0]["estimated"] is True
    assert cues_of_payload(guessed)[0].estimated is True


def test_the_webvtt_file_says_how_many_of_its_timings_were_estimated():
    """A designer holding a file has no other way to learn it. "Some of these are approximate" would
    leave them unable to tell two estimated cues from a file where every boundary was invented, so
    the note carries the count."""
    vtt = to_vtt([
        Cue(start=0.0, end=1.0, text="measured"),
        Cue(start=1.0, end=2.0, text="guessed", estimated=True),
    ])
    assert "1 of these 2 captions have estimated" in vtt
    assert vtt.startswith("WEBVTT\n\nNOTE\n")


def test_over_ceilings_is_the_one_definition_of_too_long_and_too_wide():
    """``ai._deepgram_cues`` asks this before deciding whether to hand over a sentence or its measured
    words, and ``_split_if_needed`` asks it before splitting. Two copies of "seven seconds and
    eighty-four characters" is a sentence emitted whole under one rule and split under the other —
    the invented-boundary defect reintroduced by a constant drifting."""
    assert over_ceilings(seconds=MAX_CUE_SECONDS + 0.1, text="short") is True
    assert over_ceilings(seconds=1.0, text="x" * (MAX_CUE_CHARS + 1)) is True
    assert over_ceilings(seconds=MAX_CUE_SECONDS, text="x" * MAX_CUE_CHARS) is False
    # The predicate and the splitter must agree on the boundary case, whichever way it falls.
    at_ceiling = Cue(start=0.0, end=MAX_CUE_SECONDS, text="x " * 20)
    assert (len(fit_cues([at_ceiling])) > 1) is over_ceilings(
        seconds=at_ceiling.seconds, text=at_ceiling.text
    )


def test_a_split_cue_never_leaves_the_file_out_of_time_order():
    """**A SUBTITLE FILE IS READ IN FILE ORDER BY BOTH FORMATS**, so a block whose timings run
    backwards is a file whose blocks contradict their own order, and neither format says what should
    happen to it. **What a player does with such a file was not measured here** — no player was run in
    this lane — so what is pinned is only that the file is written in time order, which is the part
    this module is answerable for.

    The shape that produced it, run before this line existed: one fragment timed ACROSS the one after
    it — a long utterance with a short interjection inside it. The fragments are sorted by start, so
    the long one comes first; splitting it makes pieces that run past the interjection's start, and
    they are appended before it. The ``.srt`` came out numbered 1, 2, 3 with timings 0→6, 6→12, 1→2.

    Neither parser can currently produce an overlap (Deepgram's sentences and Scribe's words are both
    ordered and disjoint), so this is a guarantee about the SHAPE rather than about today's callers —
    which is exactly the kind that stops holding quietly when a third provider is added. Note what it
    does NOT claim: the overlap itself survives, because removing it means moving a boundary a
    provider measured.
    """
    cues = fit_cues([
        Cue(start=0.0, end=12.0, text="a long overlapping utterance " * 4),
        Cue(start=1.0, end=2.0, text="interjection"),
    ])
    starts = [cue.start for cue in cues]
    assert starts == sorted(starts), "the rendered file would run backwards at cue 3"

    blocks = to_srt(cues).strip().split("\n\n")
    stamps = [block.split("\n")[1].split(" --> ")[0] for block in blocks]
    assert stamps == sorted(stamps)


def test_the_stretch_rule_measures_its_ceiling_against_the_cue_that_really_follows():
    """``_stretch_short_cues`` reads ``cues[index + 1]`` as "the next cue", which is only true of a
    sorted list — the same defect the test above pins, seen from the rule that depends on it."""
    cues = fit_cues([
        Cue(start=0.0, end=12.0, text="a long overlapping utterance " * 4),
        Cue(start=1.0, end=1.1, text="mm"),
    ])
    for earlier, later in pairwise(cues):
        assert earlier.start <= later.start


def test_one_unbreakable_word_over_the_ceiling_is_left_whole():
    """Splitting inside a word puts half of somebody's speech on screen as nonsense. A long caption
    is a legibility problem; a broken word is a data one."""
    cues = fit_cues([Cue(start=0.0, end=20.0, text="a" * 200)])
    assert len(cues) == 1


# --------------------------------------------------------------------------------------
# The payload: what the Json column holds
# --------------------------------------------------------------------------------------


def test_the_payload_names_its_own_shape_and_survives_a_round_trip():
    """A payload that cannot say what shape it is in is one every future reader has to sniff."""
    cues = [Cue(start=0.0, end=2.0, text="The dabu paste", speaker="1")]
    payload = cues_payload(cues, language="multi")
    assert payload["schema"] == PAYLOAD_SCHEMA
    assert payload["language"] == "multi"
    assert payload["count"] == 1
    assert payload["durationSeconds"] == 2.0
    assert [c.text for c in cues_of_payload(payload)] == ["The dabu paste"]


def test_an_undetected_language_stays_null_and_never_becomes_english():
    assert cues_payload([Cue(start=0.0, end=1.0, text="x")], language=None)["language"] is None


def test_a_payload_that_is_not_a_cue_list_is_refused():
    with pytest.raises(SubtitleError):
        cues_of_payload({"tags": ["dabu"]})


def test_a_bare_list_is_accepted_because_a_device_runner_may_not_have_wrapped_it():
    """The tiers are allowed to differ in how they produce a thing and not in what it means."""
    assert len(cues_of_payload([{"start": 0.0, "end": 1.0, "text": "x"}])) == 1


# --------------------------------------------------------------------------------------
# The two files
# --------------------------------------------------------------------------------------


def test_subrip_numbers_its_cues_from_one_and_uses_a_comma():
    """A SubRip file with the wrong separator opens as a track with no subtitles rather than as an
    error, and nothing tells the designer."""
    srt = to_srt([Cue(start=0.0, end=2.5, text="The dabu paste"), Cue(start=3.0, end=4.0, text="is mixed")])
    assert srt.startswith("1\n00:00:00,000 --> 00:00:02,500\nThe dabu paste\n")
    assert "\n2\n00:00:03,000 --> 00:00:04,000\nis mixed\n" in srt


def test_webvtt_carries_its_mandatory_header_and_uses_a_full_stop():
    """The missing ``WEBVTT`` header is the single most common reason a ``<track>`` silently does not
    appear: the browser rejects the file and reports nothing to the page."""
    vtt = to_vtt([Cue(start=61.5, end=63.0, text="With gum and clay")])
    assert vtt.startswith("WEBVTT\n\n")
    assert "00:01:01.500 --> 00:01:03.000" in vtt


def test_an_hour_long_recording_still_formats_its_hours():
    assert "01:00:00,000" in to_srt([Cue(start=3600.0, end=3601.0, text="x")])


def test_a_millisecond_is_rounded_and_not_truncated():
    """Truncation biases every timestamp early, and the error accumulates visibly across an
    hour-long interview.

    THE EXAMPLE IS DELIBERATELY NOT A HALFWAY CASE, and the first draft of this test used one and
    failed. ``1.2345`` is not 1234.5 milliseconds in binary floating point — it is
    1234.4999999999998 — and even where a value IS exactly halfway, Python's ``round`` breaks the tie
    to even rather than upward. Neither behaviour is a defect here (a sub-millisecond difference in a
    subtitle is not observable), but a test that asserted a halfway case would be pinning the tie
    rule rather than the property this cares about, which is that the digit BELOW the millisecond is
    not simply discarded.
    """
    assert "00:00:01,235" in to_srt([Cue(start=1.2346, end=2.0, text="x")])
    assert "00:00:01,234" in to_srt([Cue(start=1.2344, end=2.0, text="x")])


def test_the_speaker_label_is_off_by_default_and_available_in_both_formats():
    """**THE RULE THIS PINS, STATED AS IT NOW IS RATHER THAN AS IT WAS.**

    It used to read "two renderers, one cue list", and the second renderer —
    ``to_srt_with_speakers`` — had no caller anywhere in the repository. So every subtitle file this
    server ever served was anonymised: Scribe would diarize a sitting of five artisans and an
    interviewer, ``plain_reading`` would print ``**Speaker 1:**`` into the report annexure, and the
    .srt the designer played against the video attributed every line to nobody.

    The real rule is a FLAG ON BOTH FORMATS, off by default — a prefix costs characters out of a hard
    two-line budget and every client written before it expects a file without them — and the two
    formats must agree, because a .srt and a .vtt of one layer disagreeing about who said something
    is worse than neither of them saying.
    """
    cues = [Cue(start=0.0, end=1.0, text="With gum.", speaker="Speaker 2")]
    assert "Speaker 2" not in to_srt(cues)
    assert "Speaker 2" not in to_vtt(cues)
    assert "Speaker 2: With gum." in to_srt(cues, speakers=True)
    assert "Speaker 2: With gum." in to_vtt(cues, speakers=True)


def test_the_webvtt_file_says_the_labels_were_guessed_and_the_subrip_cannot():
    """**DIARIZATION IS A MODEL'S GUESS AT WHO SPOKE**, and a label printed beside an artisan's words
    in a government archive reads as a record of who was in the room.

    WebVTT has ``NOTE`` comments, so the caution travels inside the file and is still there when
    somebody opens it in six months. SubRip has no comment syntax at all — every non-blank line in a
    block is an index, a timestamp, or text a player puts on screen — so the only place a caution
    could go is a caption over the first seconds of the video. This test pins both halves, including
    the one that is a limitation rather than a feature, so that nobody "fixes" the .srt by writing a
    paragraph about diarization into cue 1.
    """
    cues = [Cue(start=0.0, end=1.0, text="With gum.", speaker="Speaker 2")]
    vtt = to_vtt(cues, speakers=True)
    assert "NOTE\n" in vtt
    assert SPEAKER_NOTE in vtt
    assert "guessed" in vtt

    srt = to_srt(cues, speakers=True)
    assert SPEAKER_NOTE not in srt
    assert srt.startswith("1\n")


def test_the_unlabelled_files_are_byte_for_byte_what_they_always_were():
    """The flag is opt-in, so a client that has never heard of it must receive the identical bytes —
    including no ``NOTE`` block, which a strict parser would not object to but which would still be
    a file that changed under a caller who asked for nothing."""
    cues = [Cue(start=0.0, end=2.5, text="The dabu paste"), Cue(start=3.0, end=4.0, text="is mixed")]
    assert to_srt(cues) == to_srt(cues, speakers=False)
    assert to_vtt(cues) == (
        "WEBVTT\n"
        "\n"
        "1\n"
        "00:00:00.000 --> 00:00:02.500\n"
        "The dabu paste\n"
        "\n"
        "2\n"
        "00:00:03.000 --> 00:00:04.000\n"
        "is mixed\n"
    )


def test_a_webvtt_note_that_could_break_the_cue_list_is_refused():
    """``-->`` inside a ``NOTE`` ends the comment early and turns the rest of the sentence into a
    malformed cue, which a browser answers by dropping every caption after it — silently, as always.
    The two notes are constants, so this can only fire on a future edit, which is exactly when it is
    worth more than a truncated track."""
    from app.services import subtitles as module

    with pytest.raises(SubtitleError) as refused:
        module._render(
            [Cue(start=0.0, end=1.0, text="x")],
            separator=".",
            header="WEBVTT\n\n",
            speakers=False,
            notes=("0.0 --> 1.0 is a guess",),
        )
    assert "-->" in str(refused.value)


# --------------------------------------------------------------------------------------
# Speaker labels: a provider answers with an ID, and an ID is not a label
# --------------------------------------------------------------------------------------


def test_a_providers_speaker_id_becomes_a_label_a_person_can_read():
    """**THE PROVIDERS DO NOT ANSWER WITH LABELS.** Deepgram's ``paragraphs[].speaker`` is ``0``;
    Scribe's ``words[].speaker_id`` is ``speaker_0``. Stored raw — which is what happened until
    ``label_speakers`` existed — the downloaded subtitle reads ``0: The dabu paste is mixed`` and the
    annexure prints ``**speaker_0:**`` into a document going to a ministry."""
    labelled = label_speakers([
        Cue(start=0.0, end=1.0, text="How is it mixed?", speaker="0"),
        Cue(start=1.1, end=2.0, text="With gum.", speaker="1"),
    ])
    assert [cue.speaker for cue in labelled] == ["Speaker 1", "Speaker 2"]
    assert "0: How" not in to_srt(labelled, speakers=True)


def test_speakers_are_numbered_by_first_appearance_and_not_by_the_providers_own_id():
    """``ai._diarized_markdown``'s rule, deliberately the same one: a provider id is arbitrary and can
    skip values, and "Speaker 3" in a recording with two voices reads as a mistake — a reader who
    counts three speakers in a sitting of two has been told something false."""
    labelled = label_speakers([
        Cue(start=0.0, end=1.0, text="First.", speaker="speaker_7"),
        Cue(start=1.1, end=2.0, text="Second.", speaker="speaker_2"),
        Cue(start=2.1, end=3.0, text="First again.", speaker="speaker_7"),
    ])
    assert [cue.speaker for cue in labelled] == ["Speaker 1", "Speaker 2", "Speaker 1"]


def test_first_appearance_means_first_in_time_and_not_first_in_the_list():
    """``fit_cues`` sorts by start and this runs before it, so numbering by list order would call the
    second voice heard "Speaker 1" for any provider that answered out of order."""
    labelled = label_speakers([
        Cue(start=9.0, end=10.0, text="Later.", speaker="b"),
        Cue(start=0.0, end=1.0, text="Earlier.", speaker="a"),
    ])
    assert {cue.text: cue.speaker for cue in labelled} == {
        "Earlier.": "Speaker 1",
        "Later.": "Speaker 2",
    }


def test_one_voice_carries_no_label_at_all():
    """A solo interview labelled ``Speaker 1:`` on every line asserts that a diarizer told it apart
    from somebody. What happened is that it heard nobody else. ``_diarized_markdown`` returns None
    for exactly this, and the two must not disagree."""
    labelled = label_speakers([
        Cue(start=0.0, end=1.0, text="The dabu paste", speaker="0"),
        Cue(start=1.1, end=2.0, text="is mixed.", speaker="0"),
    ])
    assert [cue.speaker for cue in labelled] == ["", ""]


def test_an_undiarized_response_is_not_a_recording_with_one_speaker():
    """No id at all means nobody looked, which is a different fact from "one voice was heard", and
    inventing a label for it would state something nobody established."""
    labelled = label_speakers([Cue(start=0.0, end=1.0, text="x")])
    assert labelled[0].speaker == ""


def test_writing_out_no_cues_at_all_is_refused_rather_than_producing_an_empty_file():
    """An empty subtitle file opens as a track with no subtitles — the failure nobody notices until
    the video is being shown."""
    with pytest.raises(SubtitleError) as refused:
        to_srt([])
    assert "player" in str(refused.value)


# --------------------------------------------------------------------------------------
# The prose reading, which is what the annexure prints
# --------------------------------------------------------------------------------------


def test_the_plain_reading_uses_the_speaker_shape_the_report_already_parses():
    """``services/transcript_format`` recognises a bold span ending in a colon as a speaker turn. The
    same fact in two shapes for two readers, which is why the label is kept out of the cue."""
    reading = plain_reading([
        Cue(start=0.0, end=1.0, text="How is it mixed?", speaker="Interviewer"),
        Cue(start=1.1, end=2.0, text="With gum", speaker="Artisan"),
        Cue(start=2.1, end=3.0, text="and clay.", speaker="Artisan"),
    ])
    assert reading == "**Interviewer:** How is it mixed?\n\n**Artisan:** With gum and clay."


def test_an_undiarized_recording_reads_as_one_passage():
    assert plain_reading([
        Cue(start=0.0, end=1.0, text="The dabu paste"),
        Cue(start=1.1, end=2.0, text="is mixed."),
    ]) == "The dabu paste is mixed."
