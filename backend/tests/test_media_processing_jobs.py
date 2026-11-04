import os
import uuid

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

PANEL_FILTERS = ["QUEUED", "PROCESSING", "COMPLETED", "FAILED", "CANCELLED"]


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def env():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    await db.connect()
    try:
        designer = await db.user.create(data={
            "email": f"jobs-designer-{stamp}@example.org", "name": "Jobs Designer",
            "role": "DESIGNER", "passwordHash": hash_password("unused"),
        })
        admin = await db.user.create(data={
            "email": f"jobs-admin-{stamp}@example.org", "name": "Jobs Admin",
            "role": "ADMIN", "passwordHash": hash_password("unused"),
        })
        stranger = await db.user.create(data={
            "email": f"jobs-stranger-{stamp}@example.org", "name": "Jobs Stranger",
            "role": "DESIGNER", "passwordHash": hash_password("unused"),
        })
        media = await db.mediafile.create(data={
            "originalFilename": f"queue-{stamp}.m4a",
            "mediaType": "AUDIO",
            "mimeType": "audio/mp4",
            "sizeBytes": 4096,
            "bucket": "test-bucket",
            "objectKey": f"media/{designer.id}/{stamp}-queue.m4a",
            "uploadedById": designer.id,
        })
        own: dict[str, str] = {}
        for job_status in PANEL_FILTERS:
            job = await db.mediaprocessingjob.create(data={
                "jobType": "TRANSCRIPTION",
                "status": job_status,
                "mediaFileId": media.id,
                "requestedById": designer.id,
                "error": f"provider said no ({job_status})" if job_status == "FAILED" else None,
            })
            own[job_status] = job.id
        foreign = await db.mediaprocessingjob.create(data={
            "jobType": "MEASUREMENT",
            "status": "FAILED",
            "mediaFileId": media.id,
            "requestedById": stranger.id,
        })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "designer": create_access_token(subject=designer.id),
            "admin": create_access_token(subject=admin.id),
            "own": own,
            "foreign": foreign.id,
        }


class _As:
    def __init__(self, client, token: str) -> None:
        self._client = client
        self._headers = {"Authorization": f"Bearer {token}"}

    def get(self, url: str):
        return self._client.get(url, headers=self._headers)


@pytest.fixture
def client(env):
    return _As(env["client"], env["designer"])


@pytest.fixture
def admin_client(env):
    return _As(env["client"], env["admin"])



@pytest.mark.parametrize("job_status", PANEL_FILTERS)
def test_every_panel_filter_pill_is_accepted(client, env, job_status):
    response = client.get(f"/api/media/jobs?statusFilter={job_status}&pageSize=100")
    assert response.status_code == 200, response.text
    items = response.json()["items"]
    assert {item["status"] for item in items} <= {job_status}, (
        f"the {job_status} page returned rows of another status"
    )

    everything = client.get("/api/media/jobs?pageSize=100")
    assert everything.status_code == 200, everything.text
    current = {item["id"]: item["status"] for item in everything.json()["items"]}
    ours = env["own"][job_status]
    assert ours in current, "this fixture's own job vanished from the unfiltered page"
    if current[ours] == job_status:
        assert ours in {item["id"] for item in items}, (
            f"job {ours} is {job_status} on the unfiltered page and absent from the {job_status} "
            f"page — the filter accepted the value and ignored it"
        )


def test_the_all_pill_still_issues_the_failed_count_probe(client, env):
    listing = client.get("/api/media/jobs?pageSize=100")
    assert listing.status_code == 200, listing.text
    returned = {item["id"] for item in listing.json()["items"]}
    missing = {status: job for status, job in env["own"].items() if job not in returned}
    assert not missing, f"the unfiltered page dropped this fixture's own jobs: {missing}"

    probe = client.get("/api/media/jobs?statusFilter=FAILED&pageSize=100")
    assert probe.status_code == 200, probe.text
    assert probe.json()["total"] >= 1
    assert env["own"]["FAILED"] in {item["id"] for item in probe.json()["items"]}


def test_the_failure_reason_travels_with_a_failed_job(client, env):
    response = client.get("/api/media/jobs?statusFilter=FAILED&pageSize=100")
    assert response.status_code == 200, response.text
    ours = [item for item in response.json()["items"] if item["id"] == env["own"]["FAILED"]]
    assert ours, "the fixture's own FAILED job is not in a FAILED-filtered page"
    assert ours[0]["error"] == "provider said no (FAILED)"



@pytest.mark.parametrize("record_status", ["DRAFT", "PENDING", "APPROVED", "REJECTED", "NEEDS_REVISION"])
def test_a_record_status_is_refused_and_the_422_names_the_job_statuses(client, record_status):
    response = client.get(f"/api/media/jobs?statusFilter={record_status}")
    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    assert detail == "statusFilter must be one of CANCELLED, COMPLETED, FAILED, PROCESSING, QUEUED"


def test_a_lowercase_or_unknown_status_is_a_422_not_a_500(client):
    for value in ("failed", "ALL", "IN_PROGRESS"):
        response = client.get(f"/api/media/jobs?statusFilter={value}")
        assert response.status_code == 422, f"{value!r} -> {response.status_code}: {response.text}"



def test_the_filter_narrows_within_the_caller_scope_and_never_widens_it(client, admin_client, env):
    mine = client.get("/api/media/jobs?statusFilter=FAILED&pageSize=100")
    assert mine.status_code == 200, mine.text
    ids = {item["id"] for item in mine.json()["items"]}
    assert env["own"]["FAILED"] in ids
    assert env["foreign"] not in ids

    theirs = admin_client.get("/api/media/jobs?statusFilter=FAILED&pageSize=100")
    assert theirs.status_code == 200, theirs.text
    assert env["foreign"] in {item["id"] for item in theirs.json()["items"]}
