"""What the media queue writes onto the CLIP when a transcription job dies, and who may recover it.

THREE DEFECTS, ONE SHAPE: the queue recorded an outcome on the JOB and left the MediaFile saying
something else.

**A job that died before the provider answered left its clip reading QUEUED for ever.**
``MediaFile.transcriptStatus`` is written back only by ``_apply_transcription_result``, which is
reached only once the provider has responded. Every raise before that point — the consent read, the
size gate, ``download_to_temp``, ``transcribe_audio_bytes``, ``load_app_settings``,
``refine_transcript_text`` —
went to ``_handle_job_failure``, which updated the job and nothing else. So an eleven-minute
interview whose S3 object was briefly unreachable overnight ended with the job FAILED and the clip
still reading ``transcriptStatus="QUEUED"``, ``transcriptError=None``.

THE RECOVERY HALF OF THAT IS ALREADY FIXED ELSEWHERE and this suite does not re-test it:
``workshop_transcripts`` no longer treats QUEUED/PROCESSING as settled, it checks the queue table,
and ``test_workshop_audio`` pins that a later stage save now re-queues the clip. What was left is the
COLUMN: it still claimed a transcript was on its way, so ``report_annexures`` went on reporting the
recording as "still being transcribed", ``GET /media`` showed a clip permanently waiting on nothing,
and a clip no stage references had no save to be rescued by at all. The terminal state now gets
written where the job's terminal state is already written — one place, one truth.

**A refinement failure threw away a transcript that had already been paid for.** The raw text sits
in ``result`` when ``refine_transcript_text`` is called; an exception out of that optional second hop
sent the whole run to ``_handle_job_failure`` with the text unwritten.

**"Run queue now" recovered stale locks held by a process it cannot see.** ``recover_stale_processing_jobs``
judges a job dead purely from the age of ``lockedAt``. In the queue process that is safe — it is
serial. From the web process, where ``POST /media/jobs/process`` runs, a provider call hung past the
30-minute cutoff is flipped back to QUEUED and honestly re-locked (``_lock_job``'s CAS matches on
``status: QUEUED``, which the recovery has just made true) while the queue process is still awaiting
the first answer: the same recording sent twice, billed twice, two results racing to write
``transcriptText``.

NOTHING HERE TOUCHES A DATABASE OR A PROVIDER. ``media_queue.db`` and the four outbound calls are
replaced, and the private functions are awaited directly, because what is under test is which row
gets which column — a decision that is a pure function of the job once it is loaded.
"""

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


# --------------------------------------------------------------------------------------
# 1. The clip's terminal state
# --------------------------------------------------------------------------------------


def test_an_exhausted_transcription_marks_the_clip_failed(monkeypatch):
    """THE DEFECT. FAILED rather than QUEUED is what makes the clip self-healing: FAILED is
    deliberately ABSENT from ``_SETTLED_TRANSCRIPT_STATUSES``, so the very next save of the stage
    picks the recording up again — the same mechanism the consent-refusal path relies on."""
    db = _fail(monkeypatch, _job())
    assert db.mediaprocessingjob.updates[0]["status"] == media_queue.FAILED
    assert len(db.mediafile.update_many_calls) == 1
    written = db.mediafile.update_many_calls[0]["data"]
    assert written["transcriptStatus"] == media_queue.FAILED
    assert "S3 unreachable" in written["transcriptError"]


def test_the_clip_is_left_alone_while_attempts_remain(monkeypatch):
    """Between two retries the clip really IS queued. Saying FAILED here would let
    ``enqueue_stage_transcriptions`` file a SECOND job for a recording the first is still working
    through, and the two would race to write the same column."""
    db = _fail(monkeypatch, _job(attempts=1, maxAttempts=3))
    assert db.mediaprocessingjob.updates[0]["status"] == media_queue.QUEUED
    assert db.mediafile.update_many_calls == []


def test_an_existing_transcript_is_never_relabelled(monkeypatch):
    """``_transcript_write``'s hard-won rule, applied to a new writer: a clip transcribed months ago
    whose re-run failed must not be stamped FAILED, because that is a lie about the row and hides it
    from every screen that filters on the status.

    The guard is a PREDICATE ON THE UPDATE rather than a read-then-write, so there is no window
    between the two and the whole thing stays one round trip — which is what this asserts.
    """
    db = _fail(monkeypatch, _job())
    where = db.mediafile.update_many_calls[0]["where"]
    assert where["id"] == "m-1"
    assert where["OR"] == [{"transcriptText": None}, {"transcriptText": ""}]


def test_a_measurement_job_leaves_the_transcript_columns_alone(monkeypatch):
    """A failed image measurement has nothing to say about a recording's transcript, and this
    function is shared by both job types."""
    db = _fail(monkeypatch, _job(jobType=media_queue.MEASUREMENT))
    assert db.mediafile.update_many_calls == []


def test_a_failure_to_write_the_clip_does_not_mask_the_jobs_own_failure(monkeypatch):
    """The job's terminal state is already committed by the time this runs. Letting a second write
    raise would propagate out of the batch's ``except`` handler and abandon the rest of the drain."""

    class _Exploding(_MediaDelegate):
        async def update_many(self, **_kwargs: Any) -> int:
            raise RuntimeError("connection reset")

    db = _DB(_job())
    db.mediafile = _Exploding()
    monkeypatch.setattr(media_queue, "db", db)
    asyncio.run(media_queue._handle_job_failure("job-1", RuntimeError("S3 unreachable")))
    assert db.mediaprocessingjob.updates[0]["status"] == media_queue.FAILED


# --------------------------------------------------------------------------------------
# 2. A refinement failure must not discard a paid-for transcript
# --------------------------------------------------------------------------------------


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
    # THE TRANSCRIPTION PATH NO LONGER READS THE OBJECT INTO THE HEAP — it sizes it with
    # ``head_object`` and streams it to a temp file with ``download_to_temp`` — so stubbing
    # ``get_object_bytes`` alone would let a real boto3 call out of this unit test. ``head_object``
    # answers None ("storage will not say"), which is the branch that carries on to the fetch.
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
    """THE DEFECT. The provider has already been paid and the raw text is in hand at that line;
    letting the optional second hop take the whole run down discarded both."""

    async def _boom(*_a: Any, **_k: Any) -> dict[str, Any]:
        raise RuntimeError("refiner 500")

    applied = _run_transcription(monkeypatch, _boom)
    assert applied["status"] == "COMPLETED"
    assert applied["text"] == RAW
    assert "formattedTranscript" not in applied


def test_a_refinement_that_succeeds_is_still_used(monkeypatch):
    """The control: the fallback must not have turned refinement off."""

    async def _ok(*_a: Any, **_k: Any) -> dict[str, Any]:
        return {"status": "COMPLETED", "refined": "**Interviewer:** ...refined..."}

    applied = _run_transcription(monkeypatch, _ok)
    assert applied["formattedTranscript"] == "**Interviewer:** ...refined..."


# --------------------------------------------------------------------------------------
# 3. Who may recover a stale lock
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize(("recover", "expected"), [(True, 1), (False, 0)])
def test_stale_recovery_runs_only_for_callers_that_asked_for_it(monkeypatch, recover, expected):
    """``POST /media/jobs/process`` passes ``recover=False`` because it runs in the WEB process while
    the drain runs in its own systemd unit. Recovery is not lost: the queue process calls this every
    pass, so a genuinely dead lock clears within one drain interval instead of one button press."""
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


# --------------------------------------------------------------------------------------
# 4. A rate-limit cooldown the OTHER process can see
# --------------------------------------------------------------------------------------
#
# The backoff lives in module globals. ``app/worker.py`` runs the drain in its own systemd unit while
# ``POST /media/jobs/process`` — the media panel's "Run queue now" button — drains inside the uvicorn
# process, whose copy of those globals is always empty (the web box ships
# ``MEDIA_QUEUE_WORKER_ENABLED=false``). So an admin who saw the queue growing during a 429 storm and
# pressed the button walked straight through a pause the queue process was observing and re-hit the
# throttled provider — with the whole backlog, because ``_defer_rate_limited_job`` held back only the
# single job the batch broke on, and held it for ``RATE_LIMIT_BASE_SECONDS`` while the pause it was
# taken under could be thirty times longer.
#
# The pause is now written onto ``runAfter``, which already means "do not run this before" and is
# already the predicate the drain selects on — so a process that knows nothing about cooldowns
# declines the work for the right reason, with no new state to fall out of date. In dev both run in
# one process and the globals genuinely are shared, which is why this never showed up in testing.


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
    """One batch whose single transcription job is throttled by the provider."""
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
    """THE DEFECT. Deferring the one job the batch broke on left the other forty advertising
    themselves as runnable, so the button drained them into a provider that was refusing them."""
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
    """The cooldown has always been transcription-only — the lighter MEASUREMENT jobs keep flowing
    through it — and widening it to the whole queue would stall image measurement on a provider that
    never refused anything."""
    delegate = _drain_under_rate_limit(monkeypatch)
    assert delegate.update_many_calls[0]["where"]["jobType"] == media_queue.TRANSCRIPTION


def test_the_deferred_job_waits_as_long_as_the_pause_and_not_thirty_seconds(monkeypatch):
    """``_defer_rate_limited_job`` used to derive its own delay: ``RATE_LIMIT_BASE_SECONDS`` whenever
    the provider named no Retry-After. At the fifth consecutive 429 the ladder is 30 x 2^4 = 480s, so
    the row said "runnable in 30s" while the only process that knew better refused for eight minutes.
    Two accounts of one decision, and the shorter one was the one a second process could read."""
    delegate = _drain_under_rate_limit(monkeypatch, consecutive=4)
    until = media_queue._rate_limit_cooldown_until
    assert delegate.updates[0]["data"]["runAfter"] == until
    delay = (until - datetime.now(UTC)).total_seconds()
    assert 400 < delay <= 480, delay


def test_a_provider_retry_after_is_honoured_by_both_writes(monkeypatch):
    """When the provider names a number the ladder uses it (floored at the base), and the job row and
    the park must still agree — one deadline, computed once."""
    delegate = _drain_under_rate_limit(monkeypatch, retry_after=120)
    until = media_queue._rate_limit_cooldown_until
    assert delegate.updates[0]["data"]["runAfter"] == until
    assert delegate.update_many_calls[0]["data"]["runAfter"] == until
    delay = (until - datetime.now(UTC)).total_seconds()
    assert 100 < delay <= 120, delay


def test_the_park_never_pulls_a_longer_hold_forward(monkeypatch):
    """``runAfter: {"lt": until}`` is load-bearing. A job already parked past this deadline — by a
    previous, longer rung of the ladder, or by a provider's own Retry-After — keeps the later time.
    Dropping the condition would let a second 429 carrying a short Retry-After pull the whole backlog
    forward into a provider still refusing it."""
    delegate = _drain_under_rate_limit(monkeypatch, retry_after=60)
    assert delegate.update_many_calls[0]["where"]["runAfter"].keys() == {"lt"}


def test_being_throttled_still_costs_the_job_no_attempt(monkeypatch):
    """The invariant ``_defer_rate_limited_job`` exists for: rate limiting must never exhaust a
    clip's retries. The park writes only ``runAfter``, and the defer restores the pre-lock count."""
    delegate = _drain_under_rate_limit(monkeypatch)
    assert delegate.updates[0]["data"]["attempts"] == 2
    assert "attempts" not in delegate.update_many_calls[0]["data"]
