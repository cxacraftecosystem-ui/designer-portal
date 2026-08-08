"""Whose files a design workshop may embed, and where a stored media URL comes from.

TWO LEAKS, BOTH FOUND BY AUDIT RATHER THAN BY A TEST, and both reachable by any signed-in account.

**A media id on a stage was treated as permission to read the file.** ``GET /api/media`` hands every
signed-in account the id of every photograph in the repository and deliberately strips the URL so
they cannot fetch it — ``records.viewable_where`` opens READING the repository while
``owned_or_granted_where`` still governs TAKING data out of it. But the report pipeline resolved
media with a bare ``find_many(where={"id": {"in": ...}})``. So pasting a stranger's id into an IMAGE
field of your own workshop and pressing Generate put their photograph — an artisan's portrait, the
photograph of an Aadhaar card a colleague uploaded — into the .docx in your Downloads folder. With
an AUDIO id the same trick handed back that recording's FULL text on the stage read, and
``GET /design-workshops/{id}/transcripts`` showed its filename, duration and opening line.

**``MediaFile.url`` was whatever the uploader said it was.** ``POST /media/complete`` stored the
payload's ``url`` in preference to the one derived from the object key, so a one-byte upload could
plant a row that ``/data/media/{id}/download`` 307-redirects to and the portal renders in an
``<img>`` — a phishing hop beginning on a URL the reader trusts.

These need Postgres: what is under test is which rows a query returns and which string a column
holds, neither of which can be asserted in Python. The module skips itself when ``DATABASE_URL``
does not point at a local database, exactly as ``test_workshop_audio`` does.

    docker compose up -d postgres minio
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma

THE OWNER IS A DESIGNER ON PURPOSE. ``owned_or_granted_where`` is empty for Professor and above, so
a test written with the ADMIN account the rest of this suite uses would pass against the unfixed
code. DESIGNER is rank 35, below PROFESSOR's 40 — the rank a real designer actually holds.
"""

import os
import uuid

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core.db import db
from app.core.security import create_access_token, hash_password
from prisma import Json

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]

PHOTO_STAGE = "WORKSHOP_SETUP"
PHOTO_ENTITY = "workshopSetup"
PHOTO_FIELD = "coverPhoto"

AUDIO_STAGE = "TRADITIONAL_PROCESS_BASELINE"
AUDIO_ENTITY = "traditionalProcess"
AUDIO_FIELD = "artisanAudio"

# Distinctive enough that finding it anywhere in a response body is unambiguous.
STRANGER_TRANSCRIPT = (
    "**Interviewer:** THE-STRANGERS-PRIVATE-WORDS how is the warp set?\n"
    "**Interviewee:** Before dawn, when the yarn is still cool.\n"
)
OWN_TRANSCRIPT = "**Interviewer:** MY-OWN-WORDS what dye is that?\n**Interviewee:** Indigo.\n"


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


async def _media(user_id: str, stamp: str, kind: str, name: str, **extra):
    return await db.mediafile.create(data={
        "originalFilename": name,
        "mediaType": kind,
        "mimeType": "image/jpeg" if kind == "IMAGE" else "audio/mp4",
        "sizeBytes": 4096,
        "bucket": "test-bucket",
        "objectKey": f"media/{user_id}/{stamp}-{name}",
        "uploadedById": user_id,
        **extra,
    })


@pytest.fixture(scope="module")
async def env():
    """A designer, a stranger who uploaded two files, and an admin to prove the gate is rank-aware.

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
            "email": f"ent-designer-{stamp}@example.org", "name": "Ent Designer",
            "role": "DESIGNER", "passwordHash": hash_password("unused"),
        })
        stranger = await db.user.create(data={
            "email": f"ent-stranger-{stamp}@example.org", "name": "Ent Stranger",
            "role": "DESIGNER", "passwordHash": hash_password("unused"),
        })
        admin = await db.user.create(data={
            "email": f"ent-admin-{stamp}@example.org", "name": "Ent Admin",
            "role": "ADMIN", "passwordHash": hash_password("unused"),
        })
        own_photo = await _media(designer.id, stamp, "IMAGE", "my-loom.jpg",
                                 extraMetadata=Json({"width": 800, "height": 600}))
        foreign_photo = await _media(stranger.id, stamp, "IMAGE", "their-artisan.jpg",
                                     extraMetadata=Json({"width": 800, "height": 600}))
        own_audio = await _media(designer.id, stamp, "AUDIO", "my-note.m4a",
                                 transcriptStatus="COMPLETED", transcriptText=OWN_TRANSCRIPT)
        foreign_audio = await _media(stranger.id, stamp, "AUDIO", "their-interview.m4a",
                                     transcriptStatus="COMPLETED",
                                     transcriptText=STRANGER_TRANSCRIPT)
    finally:
        await db.disconnect()

    # ONE TestClient, two tokens. Two nested clients each run the app's lifespan against the SAME
    # module-level Prisma client, and the second teardown disconnects it under the first — which
    # hangs rather than failing. The account is chosen per request instead.
    with TestClient(app) as client:
        yield {
            "client": client,
            "designer": create_access_token(subject=designer.id),
            "admin": create_access_token(subject=admin.id),
            "designer_id": designer.id,
            "own_photo": own_photo.id,
            "foreign_photo": foreign_photo.id,
            "own_audio": own_audio.id,
            "foreign_audio": foreign_audio.id,
        }


class _As:
    """The TestClient bound to one account's token, so a test reads as "as the designer"."""

    def __init__(self, client, token: str) -> None:
        self._client = client
        self._headers = {"Authorization": f"Bearer {token}"}

    def get(self, url: str):
        return self._client.get(url, headers=self._headers)

    def post(self, url: str, json: dict):
        return self._client.post(url, json=json, headers=self._headers)

    def put(self, url: str, json: dict):
        return self._client.put(url, json=json, headers=self._headers)


@pytest.fixture
def client(env):
    return _As(env["client"], env["designer"])


@pytest.fixture
def admin_client(env):
    return _As(env["client"], env["admin"])


def _workshop(client) -> str:
    response = client.post("/api/design-workshops", json={"title": "Entitlement test workshop"})
    assert response.status_code == 201, response.text
    return response.json()["id"]


def _save(client, workshop_id: str, stage: str, entity: str, data: dict) -> None:
    response = client.put(
        f"/api/design-workshops/{workshop_id}/stages/{stage}",
        json={"entries": [{"entityKey": entity, "data": data}]},
    )
    assert response.status_code == 200, response.text


def _preview(client, workshop_id: str) -> dict:
    response = client.get(f"/api/design-workshops/{workshop_id}/report/preview")
    assert response.status_code == 200, response.text
    return response.json()


# --------------------------------------------------------------------------------------
# 1. Photographs
# --------------------------------------------------------------------------------------


def test_a_photograph_uploaded_by_someone_else_never_reaches_the_report(env, client):
    """THE LEAK. A designer pastes a stranger's media id into their own workshop's cover photo.

    Before the fix the resolver looked the id up unfiltered, the builder placed the ImageRef and the
    generated .docx carried the stranger's photograph. The id must now appear nowhere in the
    document the preview describes.
    """
    workshop_id = _workshop(client)
    _save(client, workshop_id, PHOTO_STAGE, PHOTO_ENTITY,
          {"workshopTitle": "Ikat", PHOTO_FIELD: env["foreign_photo"]})

    payload = _preview(client, workshop_id)
    assert env["foreign_photo"] not in str(payload["blocks"]), (
        "the report still carries a photograph the caller may not download"
    )


def test_the_withheld_photograph_is_reported_rather_than_dropped_in_silence(env, client):
    """A picture missing from a report reads as a picture nobody took.

    Saying so is what tells a designer to ask the colleague who uploaded it for a data-access grant
    instead of re-photographing an artisan who has gone home.
    """
    workshop_id = _workshop(client)
    _save(client, workshop_id, PHOTO_STAGE, PHOTO_ENTITY,
          {"workshopTitle": "Ikat", PHOTO_FIELD: env["foreign_photo"]})

    warnings = " ".join(_preview(client, workshop_id)["warnings"])
    assert "could not be included" in warnings, warnings


def test_the_designers_own_photograph_still_reaches_the_report(env, client):
    """The other half of the fix, and the one a blanket refusal would break.

    Without this the entitlement could be "resolve nothing" and the test above would still pass.
    """
    workshop_id = _workshop(client)
    _save(client, workshop_id, PHOTO_STAGE, PHOTO_ENTITY,
          {"workshopTitle": "Ikat", PHOTO_FIELD: env["own_photo"]})

    payload = _preview(client, workshop_id)
    assert env["own_photo"] in str(payload["blocks"])
    assert "could not be included" not in " ".join(payload["warnings"])


def test_an_admin_may_still_be_handed_every_file(env, admin_client):
    """The predicate is the repository's rank-aware one, not a bare "did you upload it".

    An ADMIN outranks PROFESSOR, so ``owned_or_granted_where`` is empty for them and every media row
    resolves — which is what keeps the existing report suite, all of it written as an admin, honest.
    """
    workshop_id = _workshop(admin_client)
    _save(admin_client, workshop_id, PHOTO_STAGE, PHOTO_ENTITY,
          {"workshopTitle": "Ikat", PHOTO_FIELD: env["foreign_photo"]})

    payload = _preview(admin_client, workshop_id)
    assert env["foreign_photo"] in str(payload["blocks"])


# --------------------------------------------------------------------------------------
# 2. Recordings and their transcripts
# --------------------------------------------------------------------------------------


def test_a_strangers_transcript_is_not_listed_by_the_transcripts_endpoint(env, client):
    """This endpoint showed the filename, duration, speaker count and opening line of any recording
    in the repository, to anybody who pasted its id onto a stage — before a report was generated."""
    workshop_id = _workshop(client)
    _save(client, workshop_id, AUDIO_STAGE, AUDIO_ENTITY, {AUDIO_FIELD: env["foreign_audio"]})

    response = client.get(f"/api/design-workshops/{workshop_id}/transcripts")
    assert response.status_code == 200, response.text
    assert response.json()["total"] == 0
    assert "THE-STRANGERS-PRIVATE-WORDS" not in response.text


def test_a_strangers_transcript_does_not_come_back_onto_the_stage(env, client):
    """The stage read carries the transcript text keyed by media id, so it is a second way out."""
    workshop_id = _workshop(client)
    _save(client, workshop_id, AUDIO_STAGE, AUDIO_ENTITY, {AUDIO_FIELD: env["foreign_audio"]})

    response = client.get(f"/api/design-workshops/{workshop_id}")
    assert response.status_code == 200, response.text
    assert env["foreign_audio"] not in response.json()["transcripts"]
    assert "THE-STRANGERS-PRIVATE-WORDS" not in response.text


def test_the_designers_own_recording_is_still_listed_and_still_comes_back(env, client):
    workshop_id = _workshop(client)
    _save(client, workshop_id, AUDIO_STAGE, AUDIO_ENTITY, {AUDIO_FIELD: env["own_audio"]})

    listed = client.get(f"/api/design-workshops/{workshop_id}/transcripts")
    assert listed.status_code == 200, listed.text
    assert listed.json()["total"] == 1

    stage = client.get(f"/api/design-workshops/{workshop_id}")
    assert env["own_audio"] in stage.json()["transcripts"]
    assert "MY-OWN-WORDS" in stage.text


def test_the_report_path_refuses_a_strangers_recording_and_says_so(env, client):
    """The REPORT's own transcript load, not just the picker endpoint — and it must not be silent.

    ``attach_report_transcripts`` is the second reader of the same media rows and it runs only when
    ``includeTranscripts`` is on, so it needs its own test. Asserted through the preview's warnings
    because that is the observable the designer actually gets: a report whose annexure is two
    recordings short, with nothing saying why, is the failure this whole surface exists to avoid.

    THE TEXT NOW REACHES THE DOCUMENT: ``append_transcript_annexure`` has its call site in
    ``ReportBuilder.build``, so a recording that slipped past this gate would be PRINTED into a
    delivered report rather than merely loaded and discarded. This test and
    ``test_a_strangers_transcript_does_not_come_back_onto_the_stage`` — which proves the words
    themselves are withheld — are what stand between a stranger's recording and a ministry.
    """
    mine = _workshop(client)
    _save(client, mine, AUDIO_STAGE, AUDIO_ENTITY, {AUDIO_FIELD: env["own_audio"]})
    _save(client, mine, "REPORT_GENERATION", "reportSettings", {"includeTranscripts": True})
    own_warnings = " ".join(_preview(client, mine)["warnings"])
    assert "transcript annexure" not in own_warnings, own_warnings

    theirs = _workshop(client)
    _save(client, theirs, AUDIO_STAGE, AUDIO_ENTITY, {AUDIO_FIELD: env["foreign_audio"]})
    _save(client, theirs, "REPORT_GENERATION", "reportSettings", {"includeTranscripts": True})
    their_warnings = " ".join(_preview(client, theirs)["warnings"])
    assert "could not be included in the transcript annexure" in their_warnings, their_warnings


# --------------------------------------------------------------------------------------
# 3. The stored media URL
# --------------------------------------------------------------------------------------


HOSTILE_URL = "https://attacker.example/portal-login"


def test_a_caller_supplied_media_url_is_ignored(env, client):
    """``POST /media/complete`` used to store the payload's ``url`` in preference to the derived
    one, so the stored row became an open redirect target under the portal's own domain."""
    key = f"media/{env['designer_id']}/{uuid.uuid4().hex}/planted.jpg"
    response = client.post("/api/media/complete", json={
        "originalFilename": "planted.jpg",
        "mediaType": "IMAGE",
        "mimeType": "image/jpeg",
        "sizeBytes": 1,
        "objectKey": key,
        "url": HOSTILE_URL,
    })
    assert response.status_code == 201, response.text
    stored = response.json()
    assert stored["url"] != HOSTILE_URL
    assert stored["url"] is None or stored["url"].endswith(key), stored["url"]


def test_the_field_is_still_ACCEPTED_so_installed_clients_keep_uploading(env, client):
    """``APIModel`` forbids extra keys and every shipped client sends ``url``. Removing the field
    would 422 every upload from every phone in the field, which cannot be updated retroactively."""
    key = f"media/{env['designer_id']}/{uuid.uuid4().hex}/ordinary.jpg"
    response = client.post("/api/media/complete", json={
        "originalFilename": "ordinary.jpg",
        "mediaType": "IMAGE",
        "mimeType": "image/jpeg",
        "sizeBytes": 1,
        "objectKey": key,
        "url": "http://localhost:9010/design-workshop/" + key,
    })
    assert response.status_code == 201, response.text
