from __future__ import annotations

import os
import subprocess
import wave

import pytest

from app.services import ai
from app.transcription import audio as audio_tools


def _write_wav(path: str, frames: int = 1600) -> None:
    with wave.open(path, "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(audio_tools.SAMPLE_RATE)
        handle.writeframes(b"\x00\x00" * frames)


@pytest.fixture
def ffmpeg_calls(monkeypatch):
    calls: list[list[str]] = []
    real = audio_tools.normalise_to_wav

    def runner(args: list[str]) -> subprocess.CompletedProcess[str]:
        calls.append(args)
        _write_wav(args[-1])
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(audio_tools, "normalise_to_wav", lambda source, target: real(source, target, runner=runner))
    return calls


@pytest.fixture
def pydub_reads(monkeypatch):
    pydub = pytest.importorskip("pydub")
    reads: list[tuple[str, str | None]] = []
    real = pydub.AudioSegment.from_file.__func__

    def spy(cls, file, format=None, *args, **kwargs):
        reads.append((file, format))
        return real(cls, file, format, *args, **kwargs)

    monkeypatch.setattr(pydub.AudioSegment, "from_file", classmethod(spy))
    return reads


def test_uploaded_bytes_are_decoded_by_the_allow_listed_ffmpeg(ffmpeg_calls, pydub_reads) -> None:
    chunks = ai._split_audio_into_chunks(b"untrusted container bytes")
    assert chunks is not None
    assert len(ffmpeg_calls) == 1
    args = ffmpeg_calls[0]
    assert args[args.index("-protocol_whitelist") + 1] == "file"
    assert args[args.index("-format_whitelist") + 1] == audio_tools.INPUT_FORMATS
    source = args[args.index("-i") + 1]
    assert not os.path.exists(source)
    assert len(pydub_reads) == 1
    wav_path, fmt = pydub_reads[0]
    assert fmt == "wav" and wav_path == args[-1]
    assert not os.path.exists(wav_path)


def test_a_stored_file_is_decoded_by_the_allow_listed_ffmpeg(tmp_path, ffmpeg_calls, pydub_reads) -> None:
    stored = tmp_path / "take.m4a"
    stored.write_bytes(b"stored container bytes")
    assert ai._split_audio_into_chunks(None, source_path=str(stored)) is not None
    args = ffmpeg_calls[0]
    assert args[args.index("-i") + 1] == os.path.abspath(str(stored))
    assert stored.exists()
    assert [fmt for _, fmt in pydub_reads] == ["wav"]


def test_pydub_never_opens_the_source_when_ffmpeg_refuses(monkeypatch, pydub_reads) -> None:
    monkeypatch.setattr(audio_tools, "normalise_to_wav", lambda source, target: False)
    assert ai._split_audio_into_chunks(b"not audio") is None
    assert pydub_reads == []
