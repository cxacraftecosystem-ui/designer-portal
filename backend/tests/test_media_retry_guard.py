import os
import uuid
from datetime import UTC, datetime, timedelta

import pytest

from app.core.db import db
from app.core.security import create_access_token, hash_password

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]

RETRYABLE = ["FAILED", "CANCELLED"]
REFUSED = ["QUEUED", "PROCESSING", "COMPLETED"]

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
                data["lockedAt"] = now
                data["lockedBy"] = "queue-service"
                data["startedAt"] = now
            if job_status == "QUEUED":
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
    response = env["client"].get(
        "/api/media/jobs?pageSize=100",
        headers={"Authorization": f"Bearer {env['owner']}"},
    )
    assert response.status_code == 200, response.text
    wanted = env["jobs"][key]
    rows = {item["id"]: item for item in response.json()["items"]}
    assert wanted in rows, "the owner scope should return exactly this module's jobs"
    return rows[wanted]



@pytest.mark.parametrize("job_status", RETRYABLE)
def test_a_terminal_job_is_requeued(env, job_status):
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
    assert _retry(env, "runafter/CANCELLED").status_code == 200
    row = _row(env, "runafter/CANCELLED")
    assert row["runAfter"] is not None
    assert datetime.fromisoformat(row["runAfter"]) <= datetime.now(UTC) + timedelta(minutes=5)



@pytest.mark.parametrize("job_status", REFUSED)
def test_a_job_in_any_other_state_is_refused_with_409(env, job_status):
    response = _retry(env, f"refuse/{job_status}")
    assert response.status_code == 409, response.text
    assert "retried" in response.json()["detail"]


def test_a_processing_job_keeps_its_lock(env):
    assert _retry(env, "lock/PROCESSING").status_code == 409
    row = _row(env, "lock/PROCESSING")
    assert row["status"] == "PROCESSING"
    assert row["lockedBy"] == "queue-service"
    assert row["lockedAt"] is not None


def test_a_completed_job_is_not_re_sent_to_the_provider(env):
    assert _retry(env, "finished/COMPLETED").status_code == 409
    row = _row(env, "finished/COMPLETED")
    assert row["status"] == "COMPLETED"
    assert row["completedAt"] is not None


def test_a_refused_retry_writes_nothing_at_all(env):
    before = _row(env, "nowrite/QUEUED")
    assert _retry(env, "nowrite/QUEUED").status_code == 409
    after = _row(env, "nowrite/QUEUED")
    for column in ("status", "runAfter", "error", "attempts", "completedAt"):
        assert after[column] == before[column], column



def test_an_unknown_job_is_still_a_404_and_not_a_409(env):
    response = env["client"].post(
        f"/api/media/jobs/{uuid.uuid4().hex}/retry",
        headers={"Authorization": f"Bearer {env['admin']}"},
    )
    assert response.status_code == 404, response.text


def test_retry_is_still_admin_only(env):
    response = _retry(env, "adminonly/FAILED", token="owner")
    assert response.status_code == 403, response.text
    assert _row(env, "adminonly/FAILED")["status"] == "FAILED"
