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


def test_a_cue_that_ends_before_it_starts_is_refused():
    with pytest.raises(SubtitleError) as refused:
        Cue(start=4.0, end=1.0, text="The dabu paste…")
    assert "before it starts" in str(refused.value)


@pytest.mark.parametrize("bad", [-1.0, float("nan"), float("inf"), "2.0s", None, True])
def test_a_timing_that_is_not_a_real_number_of_seconds_is_refused(bad):
    with pytest.raises(SubtitleError):
        Cue(start=bad, end=9.0, text="x")


def test_a_cue_with_no_words_is_refused_rather_than_stored():
    with pytest.raises(SubtitleError):
        Cue(start=0.0, end=1.0, text="   ")


def test_a_stored_cue_that_is_not_an_object_is_refused_with_the_next_move():
    with pytest.raises(SubtitleError) as refused:
        cue_of(["0", "2", "hello"])
    assert "make the subtitles again" in str(refused.value).lower()



def _words(*pairs, speaker=""):
    return [Cue(start=s, end=e, text=t, speaker=speaker) for s, e, t in pairs]


def test_scribe_style_words_are_joined_into_readable_cues():
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
    cues = fit_cues(_words((0.0, 0.5, "Yes"), (6.0, 6.4, "afterwards")))
    assert [c.text for c in cues] == ["Yes", "afterwards"]


def test_a_speaker_change_always_breaks_a_cue():
    fragments = [
        Cue(start=0.0, end=0.5, text="How is it mixed?", speaker="1"),
        Cue(start=0.55, end=1.2, text="With gum.", speaker="2"),
    ]
    cues = fit_cues(fragments)
    assert [c.speaker for c in cues] == ["1", "2"]


def test_a_deepgram_style_sentence_that_runs_too_long_is_split():
    long_sentence = " ".join(["mixing"] * 60)
    cues = fit_cues([Cue(start=0.0, end=24.0, text=long_sentence)])
    assert len(cues) > 1
    for cue in cues:
        assert cue.seconds <= MAX_CUE_SECONDS + 0.001
        assert len(cue.text) <= MAX_CUE_CHARS + 12


def test_a_split_ends_exactly_where_the_source_ended():
    cues = fit_cues([Cue(start=10.0, end=34.0, text=" ".join(["word"] * 80))])
    assert cues[0].start == 10.0
    assert cues[-1].end == 34.0


def test_a_flash_of_text_is_extended_but_never_over_the_next_cue():
    stretched = fit_cues([Cue(start=0.0, end=0.2, text="Yes")])
    assert stretched[0].seconds >= MIN_CUE_SECONDS

    crowded = fit_cues(
        [Cue(start=0.0, end=0.2, text="Yes"), Cue(start=5.0, end=5.3, text="No")]
    )
    assert crowded[0].end <= crowded[1].start



def test_every_piece_a_proportional_split_makes_says_its_timings_are_estimated():
    cues = fit_cues([Cue(start=0.0, end=24.0, text=" ".join(["mixing"] * 60))])
    assert len(cues) > 1
    assert all(cue.estimated for cue in cues)


def test_a_cue_whose_boundaries_came_from_the_provider_is_never_marked_estimated():
    cues = fit_cues(_words((0.0, 0.4, "The"), (0.45, 0.9, "dabu"), (0.95, 1.4, "paste")))
    assert [cue.estimated for cue in cues] == [False]


def test_stretching_a_flash_of_text_does_not_make_a_cue_estimated():
    stretched = fit_cues([Cue(start=0.0, end=0.2, text="Yes")])
    assert stretched[0].seconds >= MIN_CUE_SECONDS
    assert stretched[0].estimated is False


def test_the_payload_counts_estimated_cues_and_carries_the_flag_back():
    measured = cues_payload([Cue(start=0.0, end=1.0, text="x")])
    assert measured["estimatedCues"] == 0
    assert "estimated" not in measured["cues"][0]

    guessed = cues_payload([Cue(start=0.0, end=1.0, text="x", estimated=True)])
    assert guessed["estimatedCues"] == 1
    assert guessed["cues"][0]["estimated"] is True
    assert cues_of_payload(guessed)[0].estimated is True


def test_the_webvtt_file_says_how_many_of_its_timings_were_estimated():
    vtt = to_vtt([
        Cue(start=0.0, end=1.0, text="measured"),
        Cue(start=1.0, end=2.0, text="guessed", estimated=True),
    ])
    assert "1 of these 2 captions have estimated" in vtt
    assert vtt.startswith("WEBVTT\n\nNOTE\n")


def test_over_ceilings_is_the_one_definition_of_too_long_and_too_wide():
    assert over_ceilings(seconds=MAX_CUE_SECONDS + 0.1, text="short") is True
    assert over_ceilings(seconds=1.0, text="x" * (MAX_CUE_CHARS + 1)) is True
    assert over_ceilings(seconds=MAX_CUE_SECONDS, text="x" * MAX_CUE_CHARS) is False
    at_ceiling = Cue(start=0.0, end=MAX_CUE_SECONDS, text="x " * 20)
    assert (len(fit_cues([at_ceiling])) > 1) is over_ceilings(
        seconds=at_ceiling.seconds, text=at_ceiling.text
    )


def test_a_split_cue_never_leaves_the_file_out_of_time_order():
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
    cues = fit_cues([
        Cue(start=0.0, end=12.0, text="a long overlapping utterance " * 4),
        Cue(start=1.0, end=1.1, text="mm"),
    ])
    for earlier, later in pairwise(cues):
        assert earlier.start <= later.start


def test_one_unbreakable_word_over_the_ceiling_is_left_whole():
    cues = fit_cues([Cue(start=0.0, end=20.0, text="a" * 200)])
    assert len(cues) == 1



def test_the_payload_names_its_own_shape_and_survives_a_round_trip():
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
    assert len(cues_of_payload([{"start": 0.0, "end": 1.0, "text": "x"}])) == 1



def test_subrip_numbers_its_cues_from_one_and_uses_a_comma():
    srt = to_srt([Cue(start=0.0, end=2.5, text="The dabu paste"), Cue(start=3.0, end=4.0, text="is mixed")])
    assert srt.startswith("1\n00:00:00,000 --> 00:00:02,500\nThe dabu paste\n")
    assert "\n2\n00:00:03,000 --> 00:00:04,000\nis mixed\n" in srt


def test_webvtt_carries_its_mandatory_header_and_uses_a_full_stop():
    vtt = to_vtt([Cue(start=61.5, end=63.0, text="With gum and clay")])
    assert vtt.startswith("WEBVTT\n\n")
    assert "00:01:01.500 --> 00:01:03.000" in vtt


def test_an_hour_long_recording_still_formats_its_hours():
    assert "01:00:00,000" in to_srt([Cue(start=3600.0, end=3601.0, text="x")])


def test_a_millisecond_is_rounded_and_not_truncated():
    assert "00:00:01,235" in to_srt([Cue(start=1.2346, end=2.0, text="x")])
    assert "00:00:01,234" in to_srt([Cue(start=1.2344, end=2.0, text="x")])


def test_the_speaker_label_is_off_by_default_and_available_in_both_formats():
    cues = [Cue(start=0.0, end=1.0, text="With gum.", speaker="Speaker 2")]
    assert "Speaker 2" not in to_srt(cues)
    assert "Speaker 2" not in to_vtt(cues)
    assert "Speaker 2: With gum." in to_srt(cues, speakers=True)
    assert "Speaker 2: With gum." in to_vtt(cues, speakers=True)


def test_the_webvtt_file_says_the_labels_were_guessed_and_the_subrip_cannot():
    cues = [Cue(start=0.0, end=1.0, text="With gum.", speaker="Speaker 2")]
    vtt = to_vtt(cues, speakers=True)
    assert "NOTE\n" in vtt
    assert SPEAKER_NOTE in vtt
    assert "guessed" in vtt

    srt = to_srt(cues, speakers=True)
    assert SPEAKER_NOTE not in srt
    assert srt.startswith("1\n")


def test_the_unlabelled_files_are_byte_for_byte_what_they_always_were():
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



def test_a_providers_speaker_id_becomes_a_label_a_person_can_read():
    labelled = label_speakers([
        Cue(start=0.0, end=1.0, text="How is it mixed?", speaker="0"),
        Cue(start=1.1, end=2.0, text="With gum.", speaker="1"),
    ])
    assert [cue.speaker for cue in labelled] == ["Speaker 1", "Speaker 2"]
    assert "0: How" not in to_srt(labelled, speakers=True)


def test_speakers_are_numbered_by_first_appearance_and_not_by_the_providers_own_id():
    labelled = label_speakers([
        Cue(start=0.0, end=1.0, text="First.", speaker="speaker_7"),
        Cue(start=1.1, end=2.0, text="Second.", speaker="speaker_2"),
        Cue(start=2.1, end=3.0, text="First again.", speaker="speaker_7"),
    ])
    assert [cue.speaker for cue in labelled] == ["Speaker 1", "Speaker 2", "Speaker 1"]


def test_first_appearance_means_first_in_time_and_not_first_in_the_list():
    labelled = label_speakers([
        Cue(start=9.0, end=10.0, text="Later.", speaker="b"),
        Cue(start=0.0, end=1.0, text="Earlier.", speaker="a"),
    ])
    assert {cue.text: cue.speaker for cue in labelled} == {
        "Earlier.": "Speaker 1",
        "Later.": "Speaker 2",
    }


def test_one_voice_carries_no_label_at_all():
    labelled = label_speakers([
        Cue(start=0.0, end=1.0, text="The dabu paste", speaker="0"),
        Cue(start=1.1, end=2.0, text="is mixed.", speaker="0"),
    ])
    assert [cue.speaker for cue in labelled] == ["", ""]


def test_an_undiarized_response_is_not_a_recording_with_one_speaker():
    labelled = label_speakers([Cue(start=0.0, end=1.0, text="x")])
    assert labelled[0].speaker == ""


def test_writing_out_no_cues_at_all_is_refused_rather_than_producing_an_empty_file():
    with pytest.raises(SubtitleError) as refused:
        to_srt([])
    assert "player" in str(refused.value)



def test_the_plain_reading_uses_the_speaker_shape_the_report_already_parses():
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
