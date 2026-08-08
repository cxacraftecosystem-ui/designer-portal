"""Workshop audio end to end: the queue wiring, the transcript coming back, and the two aids.

These need Postgres — the behaviour under test is a row appearing in ``MediaProcessingJob`` and a
column changing on ``MediaFile``, neither of which can be asserted in Python — so the module skips
itself when ``DATABASE_URL`` does not point at a local database, exactly as ``test_stage_sync``
does.

    docker compose up -d postgres minio
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma

Nothing here reaches a provider. The transcription queue is asserted at the point where the job is
CREATED, and the two synchronous endpoints are asserted on the paths that answer before any
provider is contacted: a feature that is switched off, and a clip that is too big to accept.
"""

import os
import uuid

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
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

STAGE = "TRADITIONAL_PROCESS_BASELINE"
ENTITY = "traditionalProcess"

TRANSCRIPT = (
    "**Interviewer:** How is the warp set?\n"
    "**Interviewee:** Before dawn, when the yarn is still cool.\n"
)


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def env():
    """A signed-in designer, and two audio media rows: one untranscribed, one already done.

    The rows are created here rather than inside a test because the Prisma client is shared with
    the running app and is bound to the TestClient's event loop; touching it from a test's own loop
    is the kind of cross-loop use that fails intermittently rather than honestly.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    await db.connect()
    try:
        user = await db.user.create(data={
            "email": f"audio-test-{stamp}@example.org", "name": "Audio Test", "role": "ADMIN",
            "passwordHash": hash_password("unused"),
        })
        fresh = await db.mediafile.create(data={
            "originalFilename": "artisan-explains.m4a",
            "mediaType": "AUDIO",
            "mimeType": "audio/mp4",
            "sizeBytes": 512_000,
            "bucket": "test-bucket",
            "objectKey": f"media/{user.id}/{stamp}-fresh.m4a",
            "uploadedById": user.id,
        })
        done = await db.mediafile.create(data={
            "originalFilename": "loom-notes.m4a",
            "mediaType": "AUDIO",
            "mimeType": "audio/mp4",
            "sizeBytes": 512_000,
            "bucket": "test-bucket",
            "objectKey": f"media/{user.id}/{stamp}-done.m4a",
            "uploadedById": user.id,
            "transcriptStatus": "COMPLETED",
            "transcriptText": TRANSCRIPT,
        })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        client.headers.update({"Authorization": f"Bearer {create_access_token(subject=user.id)}"})
        yield {"client": client, "fresh": fresh.id, "done": done.id}


@pytest.fixture
def client(env):
    return env["client"]


@pytest.fixture
def workshop(client):
    response = client.post("/api/design-workshops", json={"title": "Audio test workshop"})
    assert response.status_code == 201, response.text
    return response.json()["id"]


def _save_audio(client, workshop_id, media_id):
    return client.put(
        f"/api/design-workshops/{workshop_id}/stages/{STAGE}",
        json={"entries": [{"entityKey": ENTITY, "data": {"artisanAudio": media_id}}]},
    )


# --------------------------------------------------------------------------------------
# 1. An AUDIO field on a stage is transcribed like any other recording
# --------------------------------------------------------------------------------------


async def test_audio_attached_to_a_stage_is_queued_for_transcription(env, workshop):
    """The gap this closes: the clip uploaded, the id landed on the stage, and nothing happened.

    The assertion is deliberately about the QUEUE rather than about a transcript — a workshop clip
    must enter the same job table as an interview recording, so that everything downstream (the
    off-peak window, the provider ranking, the rate-limit backoff, the admin "Transcribe now")
    applies to it without a line of new code.
    """
    client = env["client"]
    response = _save_audio(client, workshop, env["fresh"])
    assert response.status_code == 200, response.text
    assert response.json()["transcriptionsQueued"] == 1

    listing = client.get(f"/api/design-workshops/{workshop}/transcripts").json()
    entry = next(i for i in listing["items"] if i["mediaId"] == env["fresh"])
    assert entry["status"] == "QUEUED"
    assert entry["includedInReport"] is False, "nothing to print until the worker has run"
    assert entry["stageKey"] == STAGE
    assert entry["fieldLabel"] == "Artisan’s spoken explanation"


async def test_saving_the_same_stage_again_does_not_queue_a_second_job(env, workshop):
    """A designer correcting a typo, or a phone replaying its sync queue, must not pay for the
    same audio twice — nor let two jobs race to write one transcript column."""
    client = env["client"]
    assert _save_audio(client, workshop, env["fresh"]).json()["transcriptionsQueued"] in (0, 1)
    assert _save_audio(client, workshop, env["fresh"]).json()["transcriptionsQueued"] == 0


async def test_an_existing_transcript_is_never_re_transcribed(env, workshop):
    """Re-running transcription over a clip a researcher has already corrected by hand would
    overwrite their correction with the provider's guess."""
    client = env["client"]
    assert _save_audio(client, workshop, env["done"]).json()["transcriptionsQueued"] == 0


# --------------------------------------------------------------------------------------
# 2. …and the transcript comes back onto the stage
# --------------------------------------------------------------------------------------


async def test_the_transcript_comes_back_onto_the_stage(env, workshop):
    """Keyed by media id, because a collection can hold five recordings in one stage."""
    client = env["client"]
    _save_audio(client, workshop, env["done"])

    payload = client.get(f"/api/design-workshops/{workshop}/stages/{STAGE}").json()
    transcript = payload["transcripts"][env["done"]]
    assert "Before dawn" in transcript["text"]
    assert transcript["speakerCount"] == 2
    assert transcript["firstLine"].startswith("How is the warp set")


async def test_the_transcripts_listing_shows_what_the_report_would_include(env, workshop):
    """What the ``includeTranscripts`` toggle shows before it is committed to."""
    client = env["client"]
    _save_audio(client, workshop, env["done"])

    listing = client.get(f"/api/design-workshops/{workshop}/transcripts").json()
    assert listing["total"] == 1
    assert listing["withTranscript"] == 1
    assert listing["items"][0]["includedInReport"] is True


async def test_a_workshop_with_no_audio_lists_nothing(client, workshop):
    listing = client.get(f"/api/design-workshops/{workshop}/transcripts").json()
    assert listing == {
        "items": [], "total": 0, "withTranscript": 0, "totalDurationSeconds": None
    }


# --------------------------------------------------------------------------------------
# 3. Identity-card OCR: switched off is a clean 503, not an empty answer
# --------------------------------------------------------------------------------------


async def test_the_ocr_route_503s_cleanly_with_no_provider(client):
    """A 200 with no candidates is indistinguishable from "the card was unreadable", and would
    have a designer re-photographing a card in better light forever. The body has to name the
    setting an operator can act on."""
    response = client.post(
        "/api/design-workshops/ocr/identity",
        files={"file": ("card.jpg", b"\xff\xd8\xff\xe0not-a-real-jpeg", "image/jpeg")},
    )
    assert response.status_code == 503, response.text
    assert "IDENTITY_OCR_ENABLED" in response.json()["detail"]


async def test_an_empty_upload_is_refused_before_any_provider_is_asked(client):
    response = client.post(
        "/api/design-workshops/ocr/identity",
        files={"file": ("card.jpg", b"", "image/jpeg")},
    )
    assert response.status_code == 422, response.text


async def test_a_pdf_is_not_an_identity_card_photograph(client):
    response = client.post(
        "/api/design-workshops/ocr/identity",
        files={"file": ("card.pdf", b"%PDF-1.4", "application/pdf")},
    )
    assert response.status_code == 415, response.text


# --------------------------------------------------------------------------------------
# 4. Dictation: the fallback, with a cap
# --------------------------------------------------------------------------------------


async def test_the_dictation_size_cap_is_enforced(client):
    """The cap is what stops a synchronous endpoint from becoming a back door into the
    transcription queue — a long interview posted here would hold a worker for the whole provider
    round trip, with no retry and no rate-limit backoff. The message says where to put it
    instead."""
    from app.api.routes.design_workshops import DICTATION_MAX_BYTES

    oversized = b"0" * (DICTATION_MAX_BYTES + 1)
    response = client.post(
        "/api/design-workshops/dictate",
        files={"file": ("dictation.webm", oversized, "audio/webm")},
    )
    assert response.status_code == 413, response.text
    assert "workshop audio" in response.json()["detail"]


async def test_an_empty_dictation_is_refused(client):
    response = client.post(
        "/api/design-workshops/dictate",
        files={"file": ("dictation.webm", b"", "audio/webm")},
    )
    assert response.status_code == 422, response.text
