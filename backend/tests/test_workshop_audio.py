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
            "transcribeRequested": True,
            "transcribeRequestSource": "UPLOAD",
        })
        unticked = await db.mediafile.create(data={
            "originalFilename": "unticked.m4a",
            "mediaType": "AUDIO",
            "mimeType": "audio/mp4",
            "sizeBytes": 512_000,
            "bucket": "test-bucket",
            "objectKey": f"media/{user.id}/{stamp}-unticked.m4a",
            "uploadedById": user.id,
            "transcribeRequested": False,
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
        stranded = await db.mediafile.create(data={
            "originalFilename": "warp-setting-interview.m4a",
            "mediaType": "AUDIO",
            "mimeType": "audio/mp4",
            "sizeBytes": 512_000,
            "bucket": "test-bucket",
            "objectKey": f"media/{user.id}/{stamp}-stranded.m4a",
            "uploadedById": user.id,
            "transcriptStatus": "QUEUED",
            "transcribeRequested": True,
            "transcribeRequestSource": "UPLOAD",
        })
        await db.mediaprocessingjob.create(data={
            "jobType": "TRANSCRIPTION",
            "status": "FAILED",
            "mediaFileId": stranded.id,
            "requestedById": user.id,
            "attempts": 3,
            "maxAttempts": 3,
            "error": "EndpointConnectionError: could not connect to object storage",
        })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        client.headers.update({"Authorization": f"Bearer {create_access_token(subject=user.id)}"})
        yield {
            "client": client,
            "fresh": fresh.id,
            "done": done.id,
            "stranded": stranded.id,
            "unticked": unticked.id,
            "user": user.id,
        }


@pytest.fixture
def client(env):
    return env["client"]


@pytest.fixture
def unanswered(client):
    response = client.post("/api/design-workshops", json={"title": "Audio test workshop"})
    assert response.status_code == 201, response.text
    return response.json()["id"]


@pytest.fixture
def workshop(client, unanswered):
    response = client.post(
        f"/api/design-workshops/{unanswered}/dictation-consent", json={"decision": "REFUSED"}
    )
    assert response.status_code == 200, response.text
    return unanswered


def _grant(client, workshop_id):
    response = client.post(
        f"/api/design-workshops/{workshop_id}/dictation-consent", json={"decision": "GRANTED"}
    )
    assert response.status_code == 200, response.text
    return workshop_id


def _withdraw(client, workshop_id):
    response = client.post(
        f"/api/design-workshops/{workshop_id}/dictation-consent", json={"decision": "REFUSED"}
    )
    assert response.status_code == 200, response.text
    return workshop_id


@pytest.fixture
def granted(client, workshop):
    return _grant(client, workshop)


def _save_audio(client, workshop_id, media_id):
    return client.put(
        f"/api/design-workshops/{workshop_id}/stages/{STAGE}",
        json={"entries": [{"entityKey": ENTITY, "data": {"artisanAudio": media_id}}]},
    )



async def test_audio_attached_to_a_stage_is_queued_for_transcription(env, granted):
    client = env["client"]
    response = _save_audio(client, granted, env["fresh"])
    assert response.status_code == 200, response.text
    assert response.json()["transcriptionsQueued"] == 1
    assert response.json()["transcriptionConsentRefusal"] == ""

    listing = client.get(f"/api/design-workshops/{granted}/transcripts").json()
    entry = next(i for i in listing["items"] if i["mediaId"] == env["fresh"])
    assert entry["status"] == "QUEUED"
    assert entry["includedInReport"] is False, "nothing to print until the worker has run"
    assert entry["stageKey"] == STAGE
    assert entry["fieldLabel"] == "Artisan’s spoken explanation"


async def test_a_stage_clip_whose_box_was_left_unticked_is_not_queued(env, granted):
    client = env["client"]
    response = _save_audio(client, granted, env["unticked"])
    assert response.status_code == 200, response.text
    assert response.json()["transcriptionsQueued"] == 0
    assert _jobs(client.get(f"/api/media/{env['unticked']}").json()) == []


async def test_saving_the_same_stage_again_does_not_queue_a_second_job(env, granted):
    client = env["client"]
    assert _save_audio(client, granted, env["fresh"]).json()["transcriptionsQueued"] in (0, 1)
    assert _save_audio(client, granted, env["fresh"]).json()["transcriptionsQueued"] == 0


async def test_an_existing_transcript_is_never_re_transcribed(env, granted):
    client = env["client"]
    assert _save_audio(client, granted, env["done"]).json()["transcriptionsQueued"] == 0


async def test_a_clip_whose_job_died_before_the_provider_answered_is_re_queued(env, granted):
    client = env["client"]
    before = client.get(f"/api/media/{env['stranded']}").json()
    assert before["transcriptStatus"] == "QUEUED", "the premise: the column says one is on its way"
    assert not any(j["status"] in ("QUEUED", "PROCESSING") for j in _jobs(before)), (
        "the premise: and the queue table says there is not"
    )

    response = _save_audio(client, granted, env["stranded"])
    assert response.status_code == 200, response.text
    assert response.json()["transcriptionsQueued"] == 1, (
        "a recording whose job died before the provider answered was stranded for ever"
    )
    after = client.get(f"/api/media/{env['stranded']}").json()
    assert any(j["status"] == "QUEUED" for j in _jobs(after))


async def test_a_queued_clip_that_really_does_have_a_live_job_is_still_left_alone(env, granted):
    client = env["client"]
    media = _upload_audio(
        client, env["user"], tag=DW_TAG, tag_id=granted, requests=["TRANSCRIPTION"]
    )
    assert media["transcriptStatus"] == "QUEUED"
    assert any(j["status"] == "QUEUED" for j in _jobs(media)), "the premise: a live job"
    assert _save_audio(client, granted, media["id"]).json()["transcriptionsQueued"] == 0



async def test_the_transcript_comes_back_onto_the_stage(env, workshop):
    client = env["client"]
    _save_audio(client, workshop, env["done"])

    payload = client.get(f"/api/design-workshops/{workshop}/stages/{STAGE}").json()
    transcript = payload["transcripts"][env["done"]]
    assert "Before dawn" in transcript["text"]
    assert transcript["speakerCount"] == 2
    assert transcript["firstLine"].startswith("How is the warp set")


async def test_the_transcripts_listing_shows_what_the_report_would_include(env, workshop):
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



async def test_the_ocr_route_503s_cleanly_with_no_provider(client):
    response = client.post(
        "/api/design-workshops/ocr/identity",
        files={"file": ("card.jpg", b"\xff\xd8\xff\xe0not-a-real-jpeg", "image/jpeg")},
    )
    assert response.status_code == 503, response.text
    assert response.json()["detail"] == (
        "Identity-card scanning isn't available right now. Type the number from the card."
    )


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



@pytest.fixture
def consented(client, workshop):
    response = client.post(
        f"/api/design-workshops/{workshop}/dictation-consent", json={"decision": "GRANTED"}
    )
    assert response.status_code == 200, response.text
    return workshop


async def test_the_dictation_size_cap_is_enforced(client, consented):
    from app.api.routes.design_workshops import DICTATION_MAX_BYTES

    oversized = b"0" * (DICTATION_MAX_BYTES + 1)
    response = client.post(
        f"/api/design-workshops/{consented}/dictate",
        files={"file": ("dictation.webm", oversized, "audio/webm")},
    )
    assert response.status_code == 413, response.text
    assert "workshop audio" in response.json()["detail"]


async def test_an_empty_dictation_is_refused(client, consented):
    response = client.post(
        f"/api/design-workshops/{consented}/dictate",
        files={"file": ("dictation.webm", b"", "audio/webm")},
    )
    assert response.status_code == 422, response.text


async def test_the_id_less_dictation_url_is_retired_against_a_real_server(client):
    response = client.post(
        "/api/design-workshops/dictate",
        files={"file": ("dictation.webm", b"\x00" * 32, "audio/webm")},
    )
    assert response.status_code == 410, response.text
    assert response.json()["detail"] == (
        "Update the app to use dictation. You can type the answer instead."
    )


async def test_the_capability_probe_answers_on_the_mounted_application(client):
    response = client.get("/api/design-workshops/dictate")
    assert response.status_code == 200, response.text
    assert response.json()["dictatePath"] == "/design-workshops/{workshop_id}/dictate"


async def test_the_identity_card_probe_answers_200_rather_than_405(client):
    response = client.get("/api/design-workshops/ocr/identity")
    assert response.status_code == 200, response.text
    assert response.json()["ocrIdentity"] is True


DW_TAG = "designWorkshop"


def _upload_audio(client, user_id, *, tag=None, tag_id=None, requests=None):
    stamp = uuid.uuid4().hex
    body = {
        "originalFilename": f"probe-{stamp[:8]}.webm",
        "mediaType": "AUDIO",
        "mimeType": "audio/webm",
        "sizeBytes": 2048,
        "bucket": "test-bucket",
        "objectKey": f"media/{user_id}/{stamp}/probe.webm",
        "url": "ignored-the-server-derives-its-own",
    }
    if tag is not None:
        body["linkedRecordType"] = tag
        body["linkedRecordId"] = tag_id
    if requests is not None:
        body["processingRequests"] = requests
    response = client.post("/api/media/complete", json=body)
    assert response.status_code == 201, response.text
    return response.json()


def _jobs(media):
    return [j for j in (media.get("processingJobs") or []) if j["jobType"] == "TRANSCRIPTION"]



async def test_a_workshop_with_no_answer_has_its_recording_queued_by_default(env, unanswered):
    media = _upload_audio(
        env["client"], env["user"], tag=DW_TAG, tag_id=unanswered, requests=["TRANSCRIPTION"]
    )
    assert len(_jobs(media)) == 1
    assert media["transcriptStatus"] == "QUEUED"


async def test_a_refused_workshops_recording_is_stored_but_not_queued(env, workshop):
    media = _upload_audio(
        env["client"], env["user"], tag=DW_TAG, tag_id=workshop, requests=["TRANSCRIPTION"]
    )
    assert _jobs(media) == [], "an artisan who refused had their recording queued for a provider"
    assert media["transcriptStatus"] == "FAILED"
    assert "that is the answer on record" in (media["transcriptError"] or "")


async def test_a_body_that_asks_for_nothing_queues_nothing(env, workshop):
    media = _upload_audio(env["client"], env["user"], tag=DW_TAG, tag_id=workshop)
    assert _jobs(media) == []
    assert media["transcribeRequested"] is False
    assert media["transcribeRequestSource"] is None


async def test_a_consented_workshops_recording_is_queued_at_upload_time(env, granted):
    media = _upload_audio(
        env["client"], env["user"], tag=DW_TAG, tag_id=granted, requests=["TRANSCRIPTION"]
    )
    assert len(_jobs(media)) == 1
    assert media["transcriptStatus"] == "QUEUED"
    assert media["transcriptError"] is None


async def test_a_recording_that_is_not_workshop_material_is_still_transcribed(env):
    media = _upload_audio(env["client"], env["user"], requests=["TRANSCRIPTION"])
    assert len(_jobs(media)) == 1
    assert media["transcriptStatus"] == "QUEUED"


async def test_a_tag_naming_a_workshop_that_does_not_exist_holds_no_refusal(env):
    media = _upload_audio(
        env["client"],
        env["user"],
        tag=DW_TAG,
        tag_id="no-such-workshop",
        requests=["TRANSCRIPTION"],
    )
    assert len(_jobs(media)) == 1
    assert media["transcriptStatus"] == "QUEUED"



async def test_the_stage_save_refuses_and_says_why_on_the_screen_being_looked_at(env, workshop):
    client = env["client"]
    media = _upload_audio(client, env["user"], requests=[])
    response = _save_audio(client, workshop, media["id"])
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["transcriptionsQueued"] == 0
    assert "that is the answer on record" in body["transcriptionConsentRefusal"]

    refusal = client.post(
        f"/api/design-workshops/{workshop}/dictate",
        files={"file": ("dictation.webm", b"\x00" * 64, "audio/webm")},
    )
    assert refusal.status_code == 409, refusal.text


async def test_a_stage_clip_with_no_tag_is_still_gated_by_the_path_it_arrived_on(env, workshop):
    client = env["client"]
    media = _upload_audio(client, env["user"], requests=[])
    assert media["linkedRecordType"] is None, "the premise: nothing on the row names the workshop"
    assert _save_audio(client, workshop, media["id"]).json()["transcriptionsQueued"] == 0


async def test_recording_the_answer_later_lets_it_through_on_the_next_save(env, workshop):
    client = env["client"]
    media = _upload_audio(
        client, env["user"], tag=DW_TAG, tag_id=workshop, requests=["TRANSCRIPTION"]
    )
    assert media["transcriptStatus"] == "FAILED"

    _grant(client, workshop)
    assert _save_audio(client, workshop, media["id"]).json()["transcriptionsQueued"] == 1
    after = client.get(f"/api/media/{media['id']}").json()
    assert after["transcriptStatus"] == "QUEUED"



async def test_withdrawing_consent_stops_a_transcription_already_queued(env, granted):
    client = env["client"]
    media = _upload_audio(
        client, env["user"], tag=DW_TAG, tag_id=granted, requests=["TRANSCRIPTION"]
    )
    assert _save_audio(client, granted, media["id"]).json()["transcriptionsQueued"] in (0, 1)
    queued = client.get(f"/api/media/{media['id']}").json()
    assert queued["transcriptStatus"] == "QUEUED"
    assert any(j["status"] == "QUEUED" for j in _jobs(queued))

    _withdraw(client, granted)

    after = client.get(f"/api/media/{media['id']}").json()
    assert not any(j["status"] in ("QUEUED", "PROCESSING") for j in _jobs(after)), (
        "the artisan withdrew and the recording was still on its way to a provider"
    )
    assert after["transcriptStatus"] != "QUEUED"
    assert "that is the answer on record" in (after["transcriptError"] or "")



async def test_the_admin_transcribe_now_button_is_refused_for_a_refused_workshop(env, workshop):
    media = _upload_audio(
        env["client"], env["user"], tag=DW_TAG, tag_id=workshop, requests=["TRANSCRIPTION"]
    )
    response = env["client"].post(f"/api/media/{media['id']}/transcribe-now")
    assert response.status_code == 409, response.text
    assert "that is the answer on record" in response.json()["detail"]


async def test_refining_a_transcript_is_refused_too_and_names_openai(env, workshop):
    media = _upload_audio(
        env["client"], env["user"], tag=DW_TAG, tag_id=workshop, requests=["TRANSCRIPTION"]
    )
    response = env["client"].post(
        f"/api/media/{media['id']}/refine-transcript", json={"translate": False}
    )
    assert response.status_code == 409, response.text
    assert "OpenAI's language model" in response.json()["detail"]


async def test_withdrawal_reaches_a_recording_that_no_stage_references_yet(env, granted):
    client = env["client"]
    media = _upload_audio(
        client, env["user"], tag=DW_TAG, tag_id=granted, requests=["TRANSCRIPTION"]
    )
    assert media["transcriptStatus"] == "QUEUED", "the premise: queued at upload time"
    listing = client.get(f"/api/design-workshops/{granted}/transcripts").json()
    assert listing["total"] == 0, "the premise: no stage references it yet"

    _withdraw(client, granted)

    after = client.get(f"/api/media/{media['id']}").json()
    assert not any(j["status"] in ("QUEUED", "PROCESSING") for j in _jobs(after))
    assert after["transcriptStatus"] != "QUEUED"



async def test_a_clip_a_stage_names_is_refused_even_when_it_carries_no_tag(env, workshop):
    client = env["client"]
    media = _upload_audio(client, env["user"], requests=[])
    assert media.get("linkedRecordType") in (None, ""), "the premise: no tag of its own"
    assert _save_audio(client, workshop, media["id"]).status_code == 200

    now = client.post(f"/api/media/{media['id']}/transcribe-now")
    assert now.status_code == 409, now.text
    assert "that is the answer on record" in now.json()["detail"]

    assert client.post(
        f"/api/media/{media['id']}/transcript", json={"text": TRANSCRIPT}
    ).status_code == 200
    refine = client.post(f"/api/media/{media['id']}/refine-transcript", json={"translate": False})
    assert refine.status_code == 409, refine.text
    assert "OpenAI's language model" in refine.json()["detail"]


async def test_a_clip_no_stage_names_and_no_tag_is_still_transcribable(env):
    media = _upload_audio(env["client"], env["user"], requests=["TRANSCRIPTION"])
    assert media["transcriptStatus"] == "QUEUED"
    assert [j["jobType"] for j in _jobs(media)] == ["TRANSCRIPTION"]
    assert (media["transcriptError"] or "") == "", "a consent refusal was written onto an interview clip"


async def test_a_consented_workshops_stage_cannot_send_another_workshops_recording(env, granted):
    client = env["client"]
    other = client.post("/api/design-workshops", json={"title": "Other artisan"}).json()["id"]
    _withdraw(client, other)
    media = _upload_audio(client, env["user"], tag=DW_TAG, tag_id=other, requests=[])

    response = _save_audio(client, granted, media["id"])
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["transcriptionsQueued"] == 0, (
        "a recording whose own artisan refused was queued by another workshop's stage save"
    )
    after = client.get(f"/api/media/{media['id']}").json()
    assert not any(j["status"] in ("QUEUED", "PROCESSING") for j in _jobs(after))


async def test_recording_a_grant_does_not_cancel_the_workshops_own_transcriptions(env, granted):
    client = env["client"]
    media = _upload_audio(
        client, env["user"], tag=DW_TAG, tag_id=granted, requests=["TRANSCRIPTION"]
    )
    assert media["transcriptStatus"] == "QUEUED", "the premise: queued under the grant"

    _grant(client, granted)

    after = client.get(f"/api/media/{media['id']}").json()
    assert "that is the answer on record" not in (after["transcriptError"] or ""), (
        "recording a GRANT stamped this workshop's own recording with the refusal sentence"
    )
    for job in _jobs(after):
        assert "was withdrawn" not in (job.get("error") or ""), (
            "recording a GRANT cancelled the transcription that grant was recorded to permit"
        )
