"""pydub IMPORTS ON THIS INTERPRETER, FOR REAL AND NOT THROUGH A STUB.

pydub 0.25.1, its newest release, is built on the standard library's ``audioop``, which Python 3.13
removed (PEP 594). Its own fallback, ``import pyaudioop``, names a module that does not exist, so on
Python 3.14 ``import pydub`` raises ModuleNotFoundError unless ``audioop-lts``, the maintained port of
the removed module, is installed (backend/pyproject.toml says why it is a core dependency).

Both callers swallow that failure on purpose, which is what makes it dangerous: ``routes/data_browser.py``
answers 503 "pydub is not installed on the server" to every audio-as-mp4 request, and
``services/ai._split_audio_into_chunks`` returns None, so a recording over Whisper's size limit is sent
up whole and refused. ``test_media_convert_bound.py`` replaces pydub with a stub, so until this module
existed nothing in the suite imported the real package at all. Measured 2026-10-09 on CPython 3.14.7:
without audioop-lts, ``import pydub`` fails at ``pydub/utils.py`` line 16.

Needs neither ffmpeg nor a database: the generator synthesises PCM in memory, and ``rms`` is computed by
``audioop`` itself.
"""

import warnings


def test_pydub_and_the_audioop_it_needs_import_and_work():
    with warnings.catch_warnings():
        # pydub warns at import when ffmpeg is not on PATH, which is true of most CI runners and says
        # nothing about the module this test is for.
        warnings.simplefilter("ignore", RuntimeWarning)
        import audioop
        from pydub import AudioSegment
        from pydub.generators import Sine

    tone = Sine(440).to_audio_segment(duration=50)
    assert isinstance(tone, AudioSegment)
    assert tone.rms > 0
    assert audioop.rms(tone.raw_data, tone.sample_width) == tone.rms
