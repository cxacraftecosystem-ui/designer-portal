"""``GET /media/jobs`` — the filter the media processing queue panel narrows on.

THE ROUTE VALIDATED THE WRONG ENUM AND NOBODY NOTICED, because nothing drove it. The handler
narrows ``MediaProcessingJob.status`` — ``QUEUED | PROCESSING | COMPLETED | FAILED | CANCELLED``
(prisma/schema.prisma:126-132) — but passed ``records.RECORD_STATUSES``
(``DRAFT | PENDING | APPROVED | REJECTED | NEEDS_REVISION``) to ``enum_filter_or_422``. The two
enums share NO value, so the route rejected every filter a client is capable of sending, with a
422 naming five statuses that belong to a different table:

    status must be one of APPROVED, DRAFT, NEEDS_REVISION, PENDING, REJECTED

``frontend/components/media/MediaJobsPanel.tsx`` opens by firing two of these at once — the visible
page and an unconditional ``statusFilter=FAILED`` probe for the failure count — so even the "All"
pill failed, `data` stayed null, and the panel sat on "Loading processing jobs..." under a red bar
for every account, forever. The whole queue surface (the failure reason, the Retry button, the
"Run queue now" outcome) was unreachable on the web.

WHY THIS TEST IS AT THE HTTP LAYER AND NEEDS POSTGRES. The defect is not in ``enum_filter_or_422``,
which was and is correct — it is in the ARGUMENT one call site handed it. A unit test of the helper
passes against the broken code. What has to be asserted is that a real request carrying a real job
status comes back 200 with the right rows, and that a value from the OTHER enum is refused; both are
statements about the query the route builds, so the route has to run against a database. The module
skips itself when ``DATABASE_URL`` does not point at a local database, exactly as
``test_media_entitlement`` does.

    docker compose up -d postgres
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma

THE OWNER IS A DESIGNER ON PURPOSE, and there is an admin beside them: ``list_media_processing_jobs``
narrows a non-admin to ``requestedById == me`` and leaves an admin unnarrowed, so a suite written
only with an admin token would not notice if the owner scope were lost while fixing the filter.
"""

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

# Every value the panel's six filter pills can put on the wire ("All" sends no parameter at all).
# Listed literally rather than imported from the route so that renaming the route's constant to
# something that no longer matches the schema is caught here instead of agreeing with itself.
PANEL_FILTERS = ["QUEUED", "PROCESSING", "COMPLETED", "FAILED", "CANCELLED"]


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def env():
    """A designer with one job in each status, an admin, and a stranger's job to prove the scope.

    Rows are created here rather than inside a test because the Prisma client is shared with the
    running app and bound to the TestClient's event loop; touching it from a test's own loop is the
    kind of cross-loop use that fails intermittently rather than honestly.
    """
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
                # The reason column the panel exists to render — asserted below on the FAILED row,
                # because "failed, no reason" is the state this whole surface was built to end.
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

    # ONE TestClient, three tokens. Two nested clients each run the app's lifespan against the SAME
    # module-level Prisma client, and the second teardown disconnects it under the first — which
    # hangs rather than failing. The account is chosen per request instead.
    with TestClient(app) as client:
        yield {
            "client": client,
            "designer": create_access_token(subject=designer.id),
            "admin": create_access_token(subject=admin.id),
            "own": own,
            "foreign": foreign.id,
        }


class _As:
    """The TestClient bound to one account's token, so a test reads as "as the designer"."""

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


# --------------------------------------------------------------------------------------
# 1. The filter values the panel actually sends
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize("job_status", PANEL_FILTERS)
def test_every_panel_filter_pill_is_accepted(client, env, job_status):
    """Each pill returns 200 and only jobs in that status.

    Against the old code EVERY parametrisation of this test was a 422 — that is the regression.
    Asserting 200 alone would be enough to catch it, but the row check is what stops a later "fix"
    that makes the route accept the value and then ignore it.
    """
    response = client.get(f"/api/media/jobs?statusFilter={job_status}&pageSize=100")
    assert response.status_code == 200, response.text
    items = response.json()["items"]
    assert {item["status"] for item in items} == {job_status}
    assert env["own"][job_status] in {item["id"] for item in items}


def test_the_all_pill_still_issues_the_failed_count_probe(client, env):
    """The panel's opening pair: the unfiltered page AND ``statusFilter=FAILED`` for the headline.

    `MediaJobsPanel.load` fires both inside one `Promise.all`, so a 422 on the probe blanked the
    panel even on "All". Both halves are asserted together because either one failing produced the
    identical dead screen.

    ── IT ASSERTS ITS OWN ROWS, NOT A PROPERTY OF THE WHOLE TABLE, AND IT HAD TO LEARN THAT ────────

    This test read ``{item["status"] …} == set(PANEL_FILTERS)`` and ``total == 1``. Both are claims
    about **every job in the database**, and both passed when this file ran alone and failed in the
    full suite — ``QUEUED`` was missing from the set. Nothing was wrong with the endpoint. A
    ``QUEUED`` job is, by definition, work the queue is entitled to pick up: any other module that
    reaches ``drain_media_jobs`` (or a worker that happens to be enabled) will lock this fixture's
    ``QUEUED`` row and move it to ``PROCESSING``/``COMPLETED``/``FAILED``, and the ``FAILED`` count
    goes to two on the way past.

    So the fixture's own five job ids are what is asserted: the unfiltered page must return ALL of
    them, which is exactly what "the All pill filters nothing" means, and it stays true however the
    queue has since transitioned any of them. Asserting the *contents* of a shared table is a test
    asserting that no other test exists.
    """
    listing = client.get("/api/media/jobs?pageSize=100")
    assert listing.status_code == 200, listing.text
    returned = {item["id"] for item in listing.json()["items"]}
    missing = {status: job for status, job in env["own"].items() if job not in returned}
    assert not missing, f"the unfiltered page dropped this fixture's own jobs: {missing}"

    probe = client.get("/api/media/jobs?statusFilter=FAILED&pageSize=100")
    assert probe.status_code == 200, probe.text
    # The headline count the panel prints. Asserted as "at least our own", not "exactly one", for
    # the reason above — and the id check is what stops that looseness from making it vacuous.
    assert probe.json()["total"] >= 1
    assert env["own"]["FAILED"] in {item["id"] for item in probe.json()["items"]}


def test_the_failure_reason_travels_with_a_failed_job(client, env):
    """The column the panel renders on its own line. A 422 meant it never reached any screen.

    ``items[0]`` used to be the subject, which is the same shared-table assumption the test above
    was corrected for: a queue drain in any other module can FAIL this fixture's ``QUEUED`` job, and
    that row — with a different error, or none — then sorts ahead of ours. The fixture's own FAILED
    job is picked out by id instead, so the assertion is about the row whose reason this test set.
    """
    response = client.get("/api/media/jobs?statusFilter=FAILED&pageSize=100")
    assert response.status_code == 200, response.text
    ours = [item for item in response.json()["items"] if item["id"] == env["own"]["FAILED"]]
    assert ours, "the fixture's own FAILED job is not in a FAILED-filtered page"
    assert ours[0]["error"] == "provider said no (FAILED)"


# --------------------------------------------------------------------------------------
# 2. The enum the route must NOT be validating against
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize("record_status", ["DRAFT", "PENDING", "APPROVED", "REJECTED", "NEEDS_REVISION"])
def test_a_record_status_is_refused_and_the_422_names_the_job_statuses(client, record_status):
    """The mirror image of the defect, and the half that keeps the fix from being a widening.

    ``MediaFile.status`` values must not reach ``MediaProcessingJob.status``: Prisma cannot build
    that query and answers with a 500 plus a stack trace, which is the failure ``enum_filter_or_422``
    exists to convert. The message must name the JOB statuses — the old one named these five, which
    is precisely how a developer was told their correct value was wrong.
    """
    response = client.get(f"/api/media/jobs?statusFilter={record_status}")
    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    assert detail == "statusFilter must be one of CANCELLED, COMPLETED, FAILED, PROCESSING, QUEUED"


def test_a_lowercase_or_unknown_status_is_a_422_not_a_500(client):
    """Casing and stale bookmarks are the two ways this arrives in practice; neither is a 500."""
    for value in ("failed", "ALL", "IN_PROGRESS"):
        response = client.get(f"/api/media/jobs?statusFilter={value}")
        assert response.status_code == 422, f"{value!r} -> {response.status_code}: {response.text}"


# --------------------------------------------------------------------------------------
# 3. The owner scope the filter composes with
# --------------------------------------------------------------------------------------


def test_the_filter_narrows_within_the_caller_scope_and_never_widens_it(client, admin_client, env):
    """A non-admin sees only the jobs they requested — with a status filter as well as without.

    Composed with `where["requestedById"]`, so a fix that replaced the whole `where` dict rather than
    adding a key would hand the designer the stranger's FAILED job. The admin sees both, which is
    what proves the designer's single row is a scope and not an empty database.
    """
    mine = client.get("/api/media/jobs?statusFilter=FAILED&pageSize=100")
    assert mine.status_code == 200, mine.text
    ids = {item["id"] for item in mine.json()["items"]}
    assert env["own"]["FAILED"] in ids
    assert env["foreign"] not in ids

    theirs = admin_client.get("/api/media/jobs?statusFilter=FAILED&pageSize=100")
    assert theirs.status_code == 200, theirs.text
    assert env["foreign"] in {item["id"] for item in theirs.json()["items"]}
