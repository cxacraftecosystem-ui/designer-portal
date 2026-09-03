"""``recover_stale_processing_jobs`` — the attempts guard that buries a poison pill.

THE DEFECT. This function requeued EVERY ``PROCESSING`` row older than the thirty-minute cutoff,
unconditionally. The attempts ladder was consulted nowhere in it, because the ladder lived entirely
inside ``_handle_job_failure`` — and that function is reached only by an exception raised IN THIS
PROCESS. A job that takes the process down with it raises nothing anybody catches: an OOM kill, a
SIGKILL from a supervisor, an ffmpeg child that eats the box, a deploy's ``systemctl restart``
landing mid-provider-call.

So the one class of job that most needs a ladder was the one class that could never climb it: a
recording that kills the worker was resurrected every thirty minutes, for ever, taking the whole
drain down on each pass — which means every OTHER clip in the queue was starved by a job that had
already failed more times than its own ``maxAttempts`` allows.

``attempts`` IS ALREADY THE RIGHT NUMBER, WHICH IS WHY NO NEW COLUMN APPEARS. ``_lock_job``
increments it at the instant of claiming, BEFORE any work is attempted, so a row sitting in
PROCESSING has already spent the attempt it is on. ``attempts >= maxAttempts`` on a stale row
therefore means "given every run it is entitled to, and each one ended without a word" — the same
predicate ``_handle_job_failure`` uses, so a job cannot die two different deaths depending on
whether its worker was alive enough to raise.

AND THE CLIP HAS TO BE FINALISED TOO, THROUGH THE SAME WRITER. ``MediaFile.transcriptStatus`` is
written back only by ``_apply_transcription_result``, which is reached only once a provider has
answered — so a buried job would otherwise leave its clip reading ``QUEUED`` for ever, which
``report_annexures`` renders as "still being transcribed" and every transcripts screen renders as a
recording that is on its way. ``_finalize_failed_clip`` was extracted from ``_handle_job_failure``
for exactly this: one writer, one predicate, including the ``transcriptText`` guard that stops a
clip transcribed months ago being relabelled FAILED because its re-run died.

NOTHING HERE TOUCHES A DATABASE OR A PROVIDER. ``media_queue.db`` is replaced and the functions are
awaited directly, because what is under test is which row gets which column — a decision that is a
pure function of the rows once they are loaded.
"""

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
    """One row as ``find_many`` hands it back: PROCESSING, locked, past the cutoff."""
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


# --------------------------------------------------------------------------------------
# 1. A job with attempts left is still recovered, exactly as before
# --------------------------------------------------------------------------------------


def test_a_job_with_attempts_remaining_is_requeued(monkeypatch):
    """THE BEHAVIOUR THAT MUST NOT CHANGE. A worker killed on its first of three attempts — a
    deploy restart, a spot reclaim, a network partition — has said nothing about the job itself, and
    the whole purpose of this sweep is to get that work moving again within one drain interval."""
    db = _recover(monkeypatch, [_stale(attempts=1, maxAttempts=3)])
    written = _job_write(db)
    assert written["status"] == media_queue.QUEUED
    assert written["lockedAt"] is None
    assert written["lockedBy"] is None
    assert written["error"] == "Recovered after worker interruption."
    assert db.mediafile.update_many_calls == []


def test_the_row_one_short_of_the_ladder_is_still_requeued(monkeypatch):
    """The boundary, both sides of it, because this is where an off-by-one lives. ``_lock_job``
    increments ``attempts`` when it CLAIMS, so a row sitting in PROCESSING has already consumed the
    attempt it is on: at 2 of 3 there is exactly one run left and it must get it; at 3 of 3 there is
    none. One test rather than two so the pair cannot drift apart."""
    one_left = _recover(monkeypatch, [_stale(attempts=2, maxAttempts=3)])
    none_left = _recover(monkeypatch, [_stale(attempts=3, maxAttempts=3)])
    assert _job_write(one_left)["status"] == media_queue.QUEUED
    assert _job_write(none_left)["status"] == media_queue.FAILED


# --------------------------------------------------------------------------------------
# 2. A job that has spent the ladder is buried instead
# --------------------------------------------------------------------------------------


def test_an_exhausted_stale_job_is_finalised_not_requeued(monkeypatch):
    """THE DEFECT. Requeuing this row is what made the resurrection loop: a job that kills the
    worker never reaches ``_handle_job_failure``, so before this guard nothing anywhere in the
    system could ever spend its last attempt."""
    db = _recover(monkeypatch, [_stale(attempts=3, maxAttempts=3)])
    written = _job_write(db)
    assert written["status"] == media_queue.FAILED
    assert written["completedAt"] is not None
    assert written["lockedAt"] is None
    assert written["lockedBy"] is None


def test_the_error_names_the_worker_that_did_not_survive(monkeypatch):
    """``lockedBy`` is the ONLY evidence left of what happened — there is no traceback, no provider
    response and no log line in this process — so the sentence has to carry it, or an operator
    reads "failed" and goes looking for an application bug instead of that unit's journal."""
    db = _recover(monkeypatch, [_stale(attempts=3, maxAttempts=3, lockedBy="queue-service")])
    error = _job_write(db)["error"]
    assert "queue-service" in error
    assert len(error) <= 2000


def test_an_exhausted_job_with_no_worker_recorded_still_says_something(monkeypatch):
    """A row whose ``lockedBy`` is NULL (an operator's hand-driven insert, an older schema) must not
    produce "Interrupted while held by None"."""
    db = _recover(monkeypatch, [_stale(attempts=3, maxAttempts=3, lockedBy=None)])
    assert "None" not in _job_write(db)["error"]


# --------------------------------------------------------------------------------------
# 3. The clip goes with it, through the shared writer
# --------------------------------------------------------------------------------------


def test_burying_a_transcription_job_finalises_its_clip(monkeypatch):
    """A job nobody will ever run again must not leave its clip saying QUEUED: that column is what
    ``report_annexures`` reads as "still being transcribed" and what every transcripts screen shows
    as a recording on its way. FAILED is deliberately ABSENT from
    ``workshop_transcripts._SETTLED_TRANSCRIPT_STATUSES``, so the next save of the stage that names
    the recording picks it up again — the clip is finalised, not abandoned."""
    db = _recover(monkeypatch, [_stale(attempts=3, maxAttempts=3)])
    assert len(db.mediafile.update_many_calls) == 1
    call = db.mediafile.update_many_calls[0]
    assert call["data"]["transcriptStatus"] == media_queue.FAILED
    assert "queue-service" in call["data"]["transcriptError"]


def test_the_clip_write_keeps_the_transcript_preserving_predicate(monkeypatch):
    """The guard ``_finalize_failed_clip`` was extracted to carry: a clip transcribed months ago
    whose re-run died must NOT be relabelled FAILED, because that is a lie about the row and hides
    it from every screen that filters on the status. A predicate on the UPDATE rather than a
    read-then-write, so there is no window between the two."""
    db = _recover(monkeypatch, [_stale(attempts=3, maxAttempts=3)])
    where = db.mediafile.update_many_calls[0]["where"]
    assert where["id"] == "m-1"
    assert where["OR"] == [{"transcriptText": None}, {"transcriptText": ""}]


def test_a_buried_measurement_job_leaves_the_transcript_columns_alone(monkeypatch):
    """A failed image measurement has nothing to say about a recording's transcript, and both job
    types run through the same writer."""
    db = _recover(monkeypatch, [_stale(jobType=media_queue.MEASUREMENT, attempts=3, maxAttempts=3)])
    assert _job_write(db)["status"] == media_queue.FAILED
    assert db.mediafile.update_many_calls == []


# --------------------------------------------------------------------------------------
# 4. The sweep survives its own writes failing
# --------------------------------------------------------------------------------------


def test_a_clip_write_failure_does_not_abandon_the_rest_of_the_sweep(monkeypatch):
    """Up to twenty-five rows come back from one pass. The job's terminal state is already committed
    by the time the clip is written, so letting a second write raise would leave every row behind
    the failing one stuck in PROCESSING for another thirty minutes over a row that is already
    correct."""

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
    """The guard is per-row, not per-pass: one poison pill in a batch must not hold back the four
    ordinary interruptions beside it, and four ordinary ones must not rescue the pill."""
    db = _recover(
        monkeypatch,
        [
            _stale(id="live", attempts=1, maxAttempts=3),
            _stale(id="dead", attempts=3, maxAttempts=3),
        ],
    )
    by_id = {call["where"]["id"]: call["data"]["status"] for call in db.mediaprocessingjob.updates}
    assert by_id == {"live": media_queue.QUEUED, "dead": media_queue.FAILED}


# --------------------------------------------------------------------------------------
# 5. A malformed row is requeued, never buried on sight
# --------------------------------------------------------------------------------------


def test_a_row_with_no_max_attempts_is_given_a_run(monkeypatch):
    """``_attempts_exhausted`` reads defensively because it decides whether a row is buried. A
    ``maxAttempts`` that is NULL or zero can only come from a row somebody inserted by hand —
    ``enqueue_media_processing_jobs`` writes ``max(setting, 1)`` — and the safe reading of "I cannot
    tell" is one more run, not a grave."""
    db = _recover(monkeypatch, [_stale(attempts=0, maxAttempts=None)])
    assert _job_write(db)["status"] == media_queue.QUEUED


def test_a_hand_inserted_row_with_zero_max_attempts_is_not_immortal(monkeypatch):
    """The other side of the same defence: the floor is ONE attempt, not "unbounded". A row that has
    already been claimed once under a zero/NULL ceiling has had its run."""
    db = _recover(monkeypatch, [_stale(attempts=1, maxAttempts=0)])
    assert _job_write(db)["status"] == media_queue.FAILED


# --------------------------------------------------------------------------------------
# 6. The extraction did not change the OTHER caller
# --------------------------------------------------------------------------------------


def test_handle_job_failure_still_writes_the_clip_through_the_shared_writer(monkeypatch):
    """``_finalize_failed_clip`` was lifted out of ``_handle_job_failure``. That function's own
    suite (``test_media_queue_terminal_state``) pins the behaviour in detail; this is the one
    assertion that belongs HERE, because it is what proves the two deaths share a writer rather than
    merely resembling each other."""
    job = _stale(attempts=3, maxAttempts=3)
    db = _DB([job])
    monkeypatch.setattr(media_queue, "db", db)
    asyncio.run(media_queue._handle_job_failure("job-1", RuntimeError("S3 unreachable")))
    assert _job_write(db)["status"] == media_queue.FAILED
    call = db.mediafile.update_many_calls[0]
    assert call["where"]["OR"] == [{"transcriptText": None}, {"transcriptText": ""}]
    assert "S3 unreachable" in call["data"]["transcriptError"]
