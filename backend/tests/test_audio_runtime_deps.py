import warnings


def test_pydub_and_the_audioop_it_needs_import_and_work():
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        import audioop
        from pydub import AudioSegment
        from pydub.generators import Sine

    tone = Sine(440).to_audio_segment(duration=50)
    assert isinstance(tone, AudioSegment)
    assert tone.rms > 0
    assert audioop.rms(tone.raw_data, tone.sample_width) == tone.rms
