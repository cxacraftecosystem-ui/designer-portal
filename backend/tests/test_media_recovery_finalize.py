import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

from app.services import media_queue


class _QueueDelegate:
    def __init__(self, jobs: list[Any]) -> None:
        self._jobs = jobs
        self.updates: list[dict[str, Any]] = []

    async def find_many(self, **_kwargs: Any) -> list[Any]:
        return list(self._jobs)

    async def find_unique(self, **kwargs: Any) -> Any:
        wanted = (kwargs.get("where") or {}).get("id")
        return next((job for job in self._jobs if job.id == wanted), None)

    async def update(self, **kwargs: Any) -> Any:
        self.updates.append(kwargs)
        return None


class _MediaDelegate:
    def __init__(self) -> None:
        self.update_many_calls: list[dict[str, Any]] = []

    async def update_many(self, **kwargs: Any) -> int:
        self.update_many_calls.append(kwargs)
        return 1


class _DB:
    def __init__(self, jobs: list[Any]) -> None:
        self.mediaprocessingjob = _QueueDelegate(jobs)
        self.mediafile = _MediaDelegate()


def _stale(**overrides: Any) -> SimpleNamespace:
    base = {
        "id": "job-1",
        "jobType": media_queue.TRANSCRIPTION,
        "mediaFileId": "m-1",
        "attempts": 1,
        "maxAttempts": 3,
        "lockedBy": "queue-service",
        "lockedAt": datetime(2026, 9, 3, tzinfo=UTC),
    }
    base.update(overrides)
    return SimpleNamespace(**base)


def _recover(monkeypatch, jobs: list[Any]) -> _DB:
    db = _DB(jobs)
    monkeypatch.setattr(media_queue, "db", db)
    asyncio.run(media_queue.recover_stale_processing_jobs())
    return db


def _job_write(db: _DB, index: int = 0) -> dict[str, Any]:
    return db.mediaprocessingjob.updates[index]["data"]



def test_a_job_with_attempts_remaining_is_requeued(monkeypatch):
    db = _recover(monkeypatch, [_stale(attempts=1, maxAttempts=3)])
    written = _job_write(db)
    assert written["status"] == media_queue.QUEUED
    assert written["lockedAt"] is None
    assert written["lockedBy"] is None
    assert written["error"] == "Restarted after an interruption."
    assert db.mediafile.update_many_calls == []


def test_the_row_one_short_of_the_ladder_is_still_requeued(monkeypatch):
    one_left = _recover(monkeypatch, [_stale(attempts=2, maxAttempts=3)])
    none_left = _recover(monkeypatch, [_stale(attempts=3, maxAttempts=3)])
    assert _job_write(one_left)["status"] == media_queue.QUEUED
    assert _job_write(none_left)["status"] == media_queue.FAILED



def test_an_exhausted_stale_job_is_finalised_not_requeued(monkeypatch):
    db = _recover(monkeypatch, [_stale(attempts=3, maxAttempts=3)])
    written = _job_write(db)
    assert written["status"] == media_queue.FAILED
    assert written["completedAt"] is not None
    assert written["lockedAt"] is None
    assert written["lockedBy"] is None


def test_the_error_names_the_worker_that_did_not_survive(monkeypatch):
    db = _recover(monkeypatch, [_stale(attempts=3, maxAttempts=3, lockedBy="queue-service")])
    error = _job_write(db)["error"]
    assert error == "Processing was interrupted and has stopped. Retry it to try again."
    assert len(error) <= 2000


def test_an_exhausted_job_with_no_worker_recorded_still_says_something(monkeypatch):
    db = _recover(monkeypatch, [_stale(attempts=3, maxAttempts=3, lockedBy=None)])
    assert "None" not in _job_write(db)["error"]



def test_burying_a_transcription_job_finalises_its_clip(monkeypatch):
    db = _recover(monkeypatch, [_stale(attempts=3, maxAttempts=3)])
    assert len(db.mediafile.update_many_calls) == 1
    call = db.mediafile.update_many_calls[0]
    assert call["data"]["transcriptStatus"] == media_queue.FAILED
    assert "queue-service" not in call["data"]["transcriptError"], "the worker host is logged, never shown"
    assert "Processing was interrupted" in call["data"]["transcriptError"]


def test_the_clip_write_keeps_the_transcript_preserving_predicate(monkeypatch):
    db = _recover(monkeypatch, [_stale(attempts=3, maxAttempts=3)])
    where = db.mediafile.update_many_calls[0]["where"]
    assert where["id"] == "m-1"
    assert where["OR"] == [{"transcriptText": None}, {"transcriptText": ""}]


def test_a_buried_measurement_job_leaves_the_transcript_columns_alone(monkeypatch):
    db = _recover(monkeypatch, [_stale(jobType=media_queue.MEASUREMENT, attempts=3, maxAttempts=3)])
    assert _job_write(db)["status"] == media_queue.FAILED
    assert db.mediafile.update_many_calls == []



def test_a_clip_write_failure_does_not_abandon_the_rest_of_the_sweep(monkeypatch):
    class _Exploding(_MediaDelegate):
        async def update_many(self, **_kwargs: Any) -> int:
            raise RuntimeError("connection reset")

    jobs = [
        _stale(id="job-1", mediaFileId="m-1", attempts=3, maxAttempts=3),
        _stale(id="job-2", mediaFileId="m-2", attempts=3, maxAttempts=3),
    ]
    db = _DB(jobs)
    db.mediafile = _Exploding()
    monkeypatch.setattr(media_queue, "db", db)
    asyncio.run(media_queue.recover_stale_processing_jobs())
    assert len(db.mediaprocessingjob.updates) == 2
    assert {call["where"]["id"] for call in db.mediaprocessingjob.updates} == {"job-1", "job-2"}


def test_a_mixed_batch_is_sorted_row_by_row(monkeypatch):
    db = _recover(
        monkeypatch,
        [
            _stale(id="live", attempts=1, maxAttempts=3),
            _stale(id="dead", attempts=3, maxAttempts=3),
        ],
    )
    by_id = {call["where"]["id"]: call["data"]["status"] for call in db.mediaprocessingjob.updates}
    assert by_id == {"live": media_queue.QUEUED, "dead": media_queue.FAILED}



def test_a_row_with_no_max_attempts_is_given_a_run(monkeypatch):
    db = _recover(monkeypatch, [_stale(attempts=0, maxAttempts=None)])
    assert _job_write(db)["status"] == media_queue.QUEUED


def test_a_hand_inserted_row_with_zero_max_attempts_is_not_immortal(monkeypatch):
    db = _recover(monkeypatch, [_stale(attempts=1, maxAttempts=0)])
    assert _job_write(db)["status"] == media_queue.FAILED



def test_handle_job_failure_still_writes_the_clip_through_the_shared_writer(monkeypatch):
    job = _stale(attempts=3, maxAttempts=3)
    db = _DB([job])
    monkeypatch.setattr(media_queue, "db", db)
    asyncio.run(media_queue._handle_job_failure("job-1", RuntimeError("S3 unreachable")))
    assert _job_write(db)["status"] == media_queue.FAILED
    call = db.mediafile.update_many_calls[0]
    assert call["where"]["OR"] == [{"transcriptText": None}, {"transcriptText": ""}]
    assert "S3 unreachable" in call["data"]["transcriptError"]
