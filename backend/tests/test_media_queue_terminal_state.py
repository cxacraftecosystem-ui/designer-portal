import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest

from app.services import media_queue


class _JobDelegate:
    def __init__(self, job: Any) -> None:
        self._job = job
        self.updates: list[dict[str, Any]] = []

    async def find_unique(self, **_kwargs: Any) -> Any:
        return self._job

    async def update(self, **kwargs: Any) -> Any:
        self.updates.append(kwargs.get("data") or {})
        return self._job


class _MediaDelegate:
    def __init__(self) -> None:
        self.update_many_calls: list[dict[str, Any]] = []

    async def update_many(self, **kwargs: Any) -> int:
        self.update_many_calls.append(kwargs)
        return 1

    async def update(self, **kwargs: Any) -> Any:
        self.update_many_calls.append(kwargs)
        return None


class _DB:
    def __init__(self, job: Any) -> None:
        self.mediaprocessingjob = _JobDelegate(job)
        self.mediafile = _MediaDelegate()


def _job(**overrides: Any) -> SimpleNamespace:
    base = {
        "id": "job-1",
        "jobType": media_queue.TRANSCRIPTION,
        "mediaFileId": "m-1",
        "attempts": 3,
        "maxAttempts": 3,
    }
    base.update(overrides)
    return SimpleNamespace(**base)


def _fail(monkeypatch, job: Any, exc: Exception = RuntimeError("S3 unreachable")) -> _DB:
    db = _DB(job)
    monkeypatch.setattr(media_queue, "db", db)
    asyncio.run(media_queue._handle_job_failure(job.id, exc))
    return db



def test_an_exhausted_transcription_marks_the_clip_failed(monkeypatch):
    db = _fail(monkeypatch, _job())
    assert db.mediaprocessingjob.updates[0]["status"] == media_queue.FAILED
    assert len(db.mediafile.update_many_calls) == 1
    written = db.mediafile.update_many_calls[0]["data"]
    assert written["transcriptStatus"] == media_queue.FAILED
    assert "S3 unreachable" in written["transcriptError"]


def test_the_clip_is_left_alone_while_attempts_remain(monkeypatch):
    db = _fail(monkeypatch, _job(attempts=1, maxAttempts=3))
    assert db.mediaprocessingjob.updates[0]["status"] == media_queue.QUEUED
    assert db.mediafile.update_many_calls == []


def test_an_existing_transcript_is_never_relabelled(monkeypatch):
    db = _fail(monkeypatch, _job())
    where = db.mediafile.update_many_calls[0]["where"]
    assert where["id"] == "m-1"
    assert where["OR"] == [{"transcriptText": None}, {"transcriptText": ""}]


def test_a_measurement_job_leaves_the_transcript_columns_alone(monkeypatch):
    db = _fail(monkeypatch, _job(jobType=media_queue.MEASUREMENT))
    assert db.mediafile.update_many_calls == []


def test_a_failure_to_write_the_clip_does_not_mask_the_jobs_own_failure(monkeypatch):
    class _Exploding(_MediaDelegate):
        async def update_many(self, **_kwargs: Any) -> int:
            raise RuntimeError("connection reset")

    db = _DB(_job())
    db.mediafile = _Exploding()
    monkeypatch.setattr(media_queue, "db", db)
    asyncio.run(media_queue._handle_job_failure("job-1", RuntimeError("S3 unreachable")))
    assert db.mediaprocessingjob.updates[0]["status"] == media_queue.FAILED



RAW = "**Interviewer:** what dye is that?\n**Interviewee:** Indigo.\n"


def _run_transcription(monkeypatch, refine: Any) -> dict[str, Any]:
    applied: dict[str, Any] = {}

    async def _verdict(*_a: Any, **_k: Any) -> Any:
        return SimpleNamespace(may_send=True, refusal=None)

    async def _transcribe(*_a: Any, **_k: Any) -> dict[str, Any]:
        return {"status": "COMPLETED", "text": RAW}

    async def _apply(_job: Any, result: dict[str, Any]) -> None:
        applied.update(result)

    async def _settings(*_a: Any, **_k: Any) -> dict[str, Any]:
        return {}

    monkeypatch.setattr(media_queue.dictation_consent, "transcription_verdict", _verdict)
    monkeypatch.setattr(media_queue, "head_object", lambda _key: None)
    monkeypatch.setattr(media_queue, "download_to_temp", lambda _key, **_kw: "/tmp/fake-audio")
    monkeypatch.setattr(media_queue, "discard_temp", lambda _path: None)
    monkeypatch.setattr(media_queue, "get_object_bytes", lambda _key: b"audio")
    monkeypatch.setattr(media_queue, "transcribe_audio_bytes", _transcribe)
    monkeypatch.setattr(media_queue, "load_app_settings", _settings)
    monkeypatch.setattr(media_queue, "transcription_mode", lambda _s: "REFINED")
    monkeypatch.setattr(media_queue, "refine_transcript_text", refine)
    monkeypatch.setattr(media_queue, "_apply_transcription_result", _apply)

    job = SimpleNamespace(
        id="job-1",
        jobType=media_queue.TRANSCRIPTION,
        mediaFileId="m-1",
        mediaFile=SimpleNamespace(
            objectKey="k", originalFilename="i.m4a", mimeType="audio/mp4", id="m-1"
        ),
    )
    asyncio.run(media_queue._process_job(job, SimpleNamespace()))
    return applied


def test_a_refinement_that_raises_falls_back_to_the_raw_transcript(monkeypatch):
    async def _boom(*_a: Any, **_k: Any) -> dict[str, Any]:
        raise RuntimeError("refiner 500")

    applied = _run_transcription(monkeypatch, _boom)
    assert applied["status"] == "COMPLETED"
    assert applied["text"] == RAW
    assert "formattedTranscript" not in applied


def test_a_refinement_that_succeeds_is_still_used(monkeypatch):
    async def _ok(*_a: Any, **_k: Any) -> dict[str, Any]:
        return {"status": "COMPLETED", "refined": "**Interviewer:** ...refined..."}

    applied = _run_transcription(monkeypatch, _ok)
    assert applied["formattedTranscript"] == "**Interviewer:** ...refined..."



@pytest.mark.parametrize(("recover", "expected"), [(True, 1), (False, 0)])
def test_stale_recovery_runs_only_for_callers_that_asked_for_it(monkeypatch, recover, expected):
    calls: list[int] = []

    async def _recover() -> int:
        calls.append(1)
        return 0

    async def _settings(*_a: Any, **_k: Any) -> dict[str, Any]:
        return {}

    class _NoJobs:
        async def find_many(self, **_kwargs: Any) -> list[Any]:
            return []

    monkeypatch.setattr(media_queue, "recover_stale_processing_jobs", _recover)
    monkeypatch.setattr(media_queue, "load_app_settings", _settings)
    monkeypatch.setattr(media_queue, "within_processing_window", lambda _s: True)
    monkeypatch.setattr(media_queue, "db", SimpleNamespace(mediaprocessingjob=_NoJobs()))
    asyncio.run(
        media_queue.process_next_media_jobs(
            limit=1, settings=SimpleNamespace(media_queue_batch_size=1), recover=recover
        )
    )
    assert len(calls) == expected



class _QueueDelegate:
    def __init__(self, jobs: list[Any]) -> None:
        self._jobs = jobs
        self.updates: list[dict[str, Any]] = []
        self.update_many_calls: list[dict[str, Any]] = []

    async def find_many(self, **_kwargs: Any) -> list[Any]:
        return list(self._jobs)

    async def update(self, **kwargs: Any) -> Any:
        self.updates.append(kwargs)
        return self._jobs[0]

    async def update_many(self, **kwargs: Any) -> int:
        self.update_many_calls.append(kwargs)
        return 1


def _drain_under_rate_limit(
    monkeypatch, *, retry_after: float | None = None, consecutive: int = 0
) -> _QueueDelegate:
    job = SimpleNamespace(
        id="job-1", jobType=media_queue.TRANSCRIPTION, mediaFileId="m-1", attempts=2
    )
    delegate = _QueueDelegate([job])

    async def _settings(*_a: Any, **_k: Any) -> dict[str, Any]:
        return {}

    async def _lock(_job_id: str, _worker_id: str) -> Any:
        return job

    async def _process(_job: Any, _settings_obj: Any) -> None:
        raise media_queue.RateLimited(retry_after)

    monkeypatch.setattr(media_queue, "db", SimpleNamespace(mediaprocessingjob=delegate))
    monkeypatch.setattr(media_queue, "load_app_settings", _settings)
    monkeypatch.setattr(media_queue, "within_processing_window", lambda _s: True)
    monkeypatch.setattr(media_queue, "_lock_job", _lock)
    monkeypatch.setattr(media_queue, "_process_job", _process)
    monkeypatch.setattr(media_queue, "_rate_limit_cooldown_until", None)
    monkeypatch.setattr(media_queue, "_consecutive_rate_limits", consecutive)
    asyncio.run(
        media_queue.process_next_media_jobs(
            limit=1, settings=SimpleNamespace(media_queue_batch_size=1), recover=False
        )
    )
    return delegate


def test_a_throttled_batch_parks_every_queued_transcription_job(monkeypatch):
    delegate = _drain_under_rate_limit(monkeypatch)
    until = media_queue._rate_limit_cooldown_until
    assert until is not None
    assert len(delegate.update_many_calls) == 1
    call = delegate.update_many_calls[0]
    assert call["where"] == {
        "jobType": media_queue.TRANSCRIPTION,
        "status": media_queue.QUEUED,
        "runAfter": {"lt": until},
    }
    assert call["data"] == {"runAfter": until}


def test_measurement_jobs_are_not_parked_by_a_transcription_backoff(monkeypatch):
    delegate = _drain_under_rate_limit(monkeypatch)
    assert delegate.update_many_calls[0]["where"]["jobType"] == media_queue.TRANSCRIPTION


def test_the_deferred_job_waits_as_long_as_the_pause_and_not_thirty_seconds(monkeypatch):
    delegate = _drain_under_rate_limit(monkeypatch, consecutive=4)
    until = media_queue._rate_limit_cooldown_until
    assert delegate.updates[0]["data"]["runAfter"] == until
    delay = (until - datetime.now(UTC)).total_seconds()
    assert 400 < delay <= 480, delay


def test_a_provider_retry_after_is_honoured_by_both_writes(monkeypatch):
    delegate = _drain_under_rate_limit(monkeypatch, retry_after=120)
    until = media_queue._rate_limit_cooldown_until
    assert delegate.updates[0]["data"]["runAfter"] == until
    assert delegate.update_many_calls[0]["data"]["runAfter"] == until
    delay = (until - datetime.now(UTC)).total_seconds()
    assert 100 < delay <= 120, delay


def test_the_park_never_pulls_a_longer_hold_forward(monkeypatch):
    delegate = _drain_under_rate_limit(monkeypatch, retry_after=60)
    assert delegate.update_many_calls[0]["where"]["runAfter"].keys() == {"lt"}


def test_being_throttled_still_costs_the_job_no_attempt(monkeypatch):
    delegate = _drain_under_rate_limit(monkeypatch)
    assert delegate.updates[0]["data"]["attempts"] == 2
    assert "attempts" not in delegate.update_many_calls[0]["data"]
