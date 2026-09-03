"""``POST /media/jobs/{id}/retry`` — the status predicate that stops a double transcription.

THE DEFECT. The route's update carried no status predicate at all::

    await db.mediaprocessingjob.update(
        where={"id": job.id},
        data={"status": "QUEUED", "lockedAt": None, "lockedBy": None, ...},
    )

A media processing job's LOCK *is* its status: ``media_queue._lock_job`` claims a row with a
compare-and-set on ``status: QUEUED``, and ``lockedAt``/``lockedBy`` are the record of who holds it.
So pressing Retry on a job that was PROCESSING erased the lock out from under a provider call the
queue process was in the middle of — and the queue process runs in its own systemd unit
(``fieldrepo-queue.service``), cannot see this button, and is not asked. The CAS then MATCHED,
because this route had just made ``status: QUEUED`` true: the next drain pass claimed the same job
honestly, sent the same eleven-minute recording to the same paid provider a second time, and the two
answers raced to write ``MediaFile.transcriptText``. Billed twice, and the stored transcript is
whichever call finished last.

**AND THE WEB PANEL IS NOT THE GUARD — corrected 2026-09-03.** An earlier version of this paragraph
said ``frontend/components/media/MediaJobsPanel.tsx`` "offers the Retry control on every row". It
does not, and never did: that file computes ``retryable = status === "FAILED" || status ===
"CANCELLED"`` and renders a dash otherwise, on a line that is pre-wave and present at 0.0.7.

What is pinned below is therefore SERVER-SIDE DEPTH rather than a repair for one screen.
``POST /media/jobs/{id}/retry`` is a plain admin endpoint: curl reaches it, a script reaches it, the
next client written against this API reaches it, and so would this same panel the day somebody adds
a bulk "retry everything unfinished" control over the same route. One client declining to draw a
button is a courtesy to whoever is looking at it; the ``where`` clause is what makes the double
billing impossible, and it has to hold for callers that have never seen the panel.

WHY THIS SUITE NEEDS POSTGRES. What is under test is a WHERE clause — that a row in the wrong state
is *not* written — and "not written" is only demonstrable against a store that can hold the row and
be re-read afterwards. A unit test with a fake delegate can assert the shape of the ``where`` dict,
which is an assertion about this test's own fixture.

    docker compose up -d postgres
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma

TWO ACCOUNTS, AND THE SECOND ONE IS THE READ-BACK MECHANISM RATHER THAN A PERMISSION TEST. Every
row is re-read over HTTP through ``GET /media/jobs`` and never by awaiting ``db`` from a test body:
the Prisma client is bound to the TestClient's own event loop, and touching it from a test's loop is
the cross-loop use that fails intermittently rather than honestly (``test_media_processing_jobs``
records the same rule). ``list_media_processing_jobs`` narrows a NON-admin to
``requestedById == me``, so filing every fixture job under a fresh DESIGNER makes that list return
exactly these rows and nothing else in the developer's database — a deterministic read-back with no
paging guesswork. Retry itself is admin-only, so the admin token drives the button.

THE ROWS ARE PINNED AGAINST A LIVE DRAIN, deliberately, because ``test_media_processing_jobs``
records what happens when they are not: a QUEUED fixture row is by definition work the queue is
entitled to pick up, so any other module that drains — or a worker that happens to be enabled —
moves it partway through the suite and the test fails by ordering rather than by defect. The QUEUED
row here carries a ``runAfter`` far in the future (every drain selects on ``runAfter <= now``) and
the PROCESSING row is locked as of NOW (``recover_stale_processing_jobs`` only reclaims locks older
than thirty minutes), so neither is reachable by any drain running beside this test.
"""

import os
import uuid
from datetime import UTC, datetime, timedelta

import pytest

from app.core.db import db
from app.core.security import create_access_token, hash_password

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

# Both marks, and the pair is copied from ``test_media_processing_jobs`` rather than reasoned about:
# that module drives the same router through a ``TestClient`` off an async module-scoped fixture and
# is the shape known to work under this repository's ``asyncio_mode = "auto"``. The ``anyio`` mark is
# inert on the synchronous tests below and is what the ``anyio_backend`` fixture answers for.
pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]

#: The two states the route will requeue from, and the three it must refuse. Listed literally
#: rather than imported from ``media.RETRYABLE_JOB_STATUSES`` so that widening that constant to
#: include PROCESSING again is caught here instead of agreeing with itself.
RETRYABLE = ["FAILED", "CANCELLED"]
REFUSED = ["QUEUED", "PROCESSING", "COMPLETED"]

#: ONE ROW PER TEST, KEYED ``purpose/STATUS``, and it is not tidiness — it is the difference between
#: a suite that means something and one that passes by ordering. Retry MUTATES: a FAILED job
#: retried once is QUEUED, so a second test sharing that row would assert against whatever the
#: first test left behind and would flip between 200 and 409 depending on collection order. Every
#: test below therefore owns its own job, and the status it starts in is the suffix of its key.
JOB_KEYS = (
    "requeue/FAILED",
    "requeue/CANCELLED",
    "runafter/CANCELLED",
    "refuse/QUEUED",
    "refuse/PROCESSING",
    "refuse/COMPLETED",
    "lock/PROCESSING",
    "finished/COMPLETED",
    "nowrite/QUEUED",
    "adminonly/FAILED",
)


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def env():
    """One job in every state this route can be asked about, plus the two accounts.

    Every state, not only the two the web panel draws a button beside — the predicate has to hold for
    a caller that has never opened the panel. See this module's header.

    Rows are created here rather than inside a test because the Prisma client is shared with the
    running app and bound to the TestClient's event loop; touching it from a test's own loop is the
    kind of cross-loop use that fails intermittently rather than honestly.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    now = datetime.now(UTC)
    await db.connect()
    try:
        admin = await db.user.create(data={
            "email": f"retry-admin-{stamp}@example.org", "name": "Retry Admin",
            "role": "ADMIN", "passwordHash": hash_password("unused"),
        })
        owner = await db.user.create(data={
            "email": f"retry-owner-{stamp}@example.org", "name": "Retry Owner",
            "role": "DESIGNER", "passwordHash": hash_password("unused"),
        })
        media = await db.mediafile.create(data={
            "originalFilename": f"retry-{stamp}.m4a",
            "mediaType": "AUDIO",
            "mimeType": "audio/mp4",
            "sizeBytes": 4096,
            "bucket": "test-bucket",
            "objectKey": f"media/{owner.id}/{stamp}-retry.m4a",
            "uploadedById": owner.id,
        })
        jobs: dict[str, str] = {}
        for key in JOB_KEYS:
            job_status = key.split("/", 1)[1]
            data: dict = {
                "jobType": "TRANSCRIPTION",
                "status": job_status,
                "mediaFileId": media.id,
                "requestedById": owner.id,
                "attempts": 1,
                "maxAttempts": 3,
            }
            if job_status == "PROCESSING":
                # Locked as of NOW: `recover_stale_processing_jobs` only reclaims a lock older than
                # thirty minutes, so a drain running beside this suite leaves this row alone.
                data["lockedAt"] = now
                data["lockedBy"] = "queue-service"
                data["startedAt"] = now
            if job_status == "QUEUED":
                # Held out of every drain's selection, which is `runAfter <= now`.
                data["runAfter"] = now + timedelta(days=3650)
            if job_status in {"FAILED", "COMPLETED"}:
                data["completedAt"] = now
            if job_status == "FAILED":
                data["error"] = "provider said no"
            job = await db.mediaprocessingjob.create(data=data)
            jobs[key] = job.id
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "admin": create_access_token(subject=admin.id),
            "owner": create_access_token(subject=owner.id),
            "jobs": jobs,
        }


def _retry(env, key: str, *, token: str = "admin"):
    return env["client"].post(
        f"/api/media/jobs/{env['jobs'][key]}/retry",
        headers={"Authorization": f"Bearer {env[token]}"},
    )


def _row(env, key: str) -> dict:
    """This fixture's job, read back over HTTP under the owner's scope. See the module header."""
    response = env["client"].get(
        "/api/media/jobs?pageSize=100",
        headers={"Authorization": f"Bearer {env['owner']}"},
    )
    assert response.status_code == 200, response.text
    wanted = env["jobs"][key]
    rows = {item["id"]: item for item in response.json()["items"]}
    assert wanted in rows, "the owner scope should return exactly this module's jobs"
    return rows[wanted]


# --------------------------------------------------------------------------------------
# 1. The states that may be retried still are
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize("job_status", RETRYABLE)
def test_a_terminal_job_is_requeued(env, job_status):
    """The control, and it is the half a careless fix breaks: adding a predicate that refuses
    everything would make every test below pass while taking the feature away."""
    key = f"requeue/{job_status}"
    response = _retry(env, key)
    assert response.status_code == 200, response.text
    row = _row(env, key)
    assert row["status"] == "QUEUED"
    assert row["lockedAt"] is None
    assert row["lockedBy"] is None
    assert row["completedAt"] is None
    assert row["error"] is None


def test_the_requeued_job_is_runnable_immediately(env):
    """``runAfter`` is pushed to now rather than left where a backoff or a rate-limit park put it —
    the whole point of the button is "try this again, now"."""
    assert _retry(env, "runafter/CANCELLED").status_code == 200
    row = _row(env, "runafter/CANCELLED")
    assert row["runAfter"] is not None
    assert datetime.fromisoformat(row["runAfter"]) <= datetime.now(UTC) + timedelta(minutes=5)


# --------------------------------------------------------------------------------------
# 2. The states that may not
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize("job_status", REFUSED)
def test_a_job_in_any_other_state_is_refused_with_409(env, job_status):
    """THE DEFECT, for PROCESSING; the same rule closes COMPLETED and QUEUED beside it.

    A 409 and not a 403: the caller is an admin and is entitled to press this. What is not in a
    state to be retried is the JOB.
    """
    response = _retry(env, f"refuse/{job_status}")
    assert response.status_code == 409, response.text
    assert "retried" in response.json()["detail"]


def test_a_processing_job_keeps_its_lock(env):
    """THE ONE THAT COSTS MONEY. Erasing ``lockedAt``/``lockedBy`` and flipping the status to QUEUED
    is what lets ``_lock_job``'s compare-and-set succeed against a job a provider call is still
    running for — the CAS matches on ``status: QUEUED``, which the old route had just made true, so
    it defends nothing and the recording is sent a second time."""
    assert _retry(env, "lock/PROCESSING").status_code == 409
    row = _row(env, "lock/PROCESSING")
    assert row["status"] == "PROCESSING"
    assert row["lockedBy"] == "queue-service"
    assert row["lockedAt"] is not None


def test_a_completed_job_is_not_re_sent_to_the_provider(env):
    """The smaller half of the same rule. Re-running a finished transcription spends the provider
    again and, through ``_transcript_write``, can only replace a good transcript with a worse one or
    with nothing. "Transcribe now" is the deliberate admin path for redoing a finished clip."""
    assert _retry(env, "finished/COMPLETED").status_code == 409
    row = _row(env, "finished/COMPLETED")
    assert row["status"] == "COMPLETED"
    assert row["completedAt"] is not None


def test_a_refused_retry_writes_nothing_at_all(env):
    """Not merely "the status survived": a partial write — clearing the error, moving ``runAfter``,
    nulling ``completedAt`` — would leave the row telling a different story from the one the 409
    told the caller. The compare-and-set makes the whole update atomic with its own predicate."""
    before = _row(env, "nowrite/QUEUED")
    assert _retry(env, "nowrite/QUEUED").status_code == 409
    after = _row(env, "nowrite/QUEUED")
    for column in ("status", "runAfter", "error", "attempts", "completedAt"):
        assert after[column] == before[column], column


# --------------------------------------------------------------------------------------
# 3. The two refusals stay distinguishable
# --------------------------------------------------------------------------------------


def test_an_unknown_job_is_still_a_404_and_not_a_409(env):
    """The two refusals mean different things and a caller has to be able to tell them apart:
    "there is no such job" is a bad id, "that job cannot be retried" is a live row in the wrong
    state. ``require_record`` runs before the compare-and-set precisely so the first stays a 404."""
    response = env["client"].post(
        f"/api/media/jobs/{uuid.uuid4().hex}/retry",
        headers={"Authorization": f"Bearer {env['admin']}"},
    )
    assert response.status_code == 404, response.text


def test_retry_is_still_admin_only(env):
    """Unchanged, and asserted because this change rewrote the handler around it: the owner of the
    job is a DESIGNER and may READ it on the panel, but requeuing spends provider credit."""
    response = _retry(env, "adminonly/FAILED", token="owner")
    assert response.status_code == 403, response.text
    assert _row(env, "adminonly/FAILED")["status"] == "FAILED"
